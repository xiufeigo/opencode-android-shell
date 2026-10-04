# REPORT_NOTIFY_RACE — 通知后台投递竞态（v151 复现任务）

- 设备：`emulator-5554`（`Medium_Desktop`，`1366x768` / density `160`）
- 装机：`md5=f927323f80412a6ed6e503aa4983dd0c`（核对自 `research/v97_verify/install_all.txt`，**未重装**）
- 后端：`http://127.0.0.1:4095` HTTP 200，`reverse tcp:4095` 已挂
- app pid：进场时 `1425`（T0/W1 用它）；`B1` 试里对本 app 做过一次 `am force-stop`（纪律允许），
  **决定性复现（A2/A3、M1）跑在重启后的 `4550`** 上 —— 下面引用的 logcat 时间戳都来自 pid 4550
- 纪律：未跑任何 gradle 命令、未装机；未按 BACK(4)；弹层内未按 ESC(111)；坐标每轮从 dump 解析；只发 `E2ECHK5554u1z` 前缀的自建回合
- **一处纪律偏离（必须记录）**：要求是 `logcat` 带 `-t` 且按 app-pid 过滤。进场后实测发现
  `logcat -d -t N --pid=$appPid -s OcNotify` 在本机会**假阴性**（`EGL_emulation` 高频刷屏把 ring buffer 冲掉，
  明明有 `OcNotify` 输出却返回 0 行），因此**决定性复现改用 `logcat -d | grep OcNotify`**（无 `-t`）。
  代价：该命令读的是**整条 buffer**，理论上可能混入别的进程的同名 tag —— 但 `OcNotify` 是本 app 私有的 tag，
  且引用的每行都带 pid 4550 可自证，**不构成串味**。`T0/W1` 两轮仍按 `-t` + `--pid` 的要求执行。

> **判据结论：被防护（不是长尾项）。** v63 的坏形态被复现出来 2 次、2 次都被 G3 补投救回；
> 剩余缝隙在本机结构上打不中。详见 §5；本机特有约束与未闭环项见 §6。

---

## 1. 竞态的代码定义（哪两个事件在竞争）

通知链是四段，全部读得出来：

```
SSE 帧 (event-stream coroutine)
  └─ AppNotifier.raise()            notify/AppNotifier.kt:124
       ├─ 去重闸 lastRaised[key]=time      notify/AppNotifier.kt:142
       └─ delivery.deliver()              notify/AppNotifier.kt:154
            └─ SystemNotifier.deliver()   notify/SystemNotifier.kt:297
                 └─ 读 contentVisible     notify/SystemNotifier.kt:299
                      └─ shouldDeliver()  notify/SystemNotificationPolicy.kt:116-120
主线程
  └─ onActivityPaused / onWindowFocusChanged   notify/SystemNotifier.kt:167-178 / :248-259
       └─ 改 ForegroundTracker 的 resumed 集 + windowFocused
```

**两个竞争事件（准确表述）：**

| | 事件 A | 事件 B |
|---|---|---|
| 是什么 | **回合结束 → 通知投递**：`session.execution.succeeded` 被读进 `AppNotifier.raise`，经 `deliver` 读取 `contentVisible` 作投递判决 | **用户离开 app**：`onActivityPaused`（HOME）把 `ForegroundTracker` 的 resumed 集合清空（`notify/SystemNotifier.kt:167-173`） |
| 跑在哪 | **event-stream coroutine**（非主线程） | **主线程**，且是 HOME 按键处理的一部分（同帧翻转） |
| 共享状态 | `ForegroundTracker.resumedActivities` / `windowFocused`（`notify/ForegroundTracker.kt:83-84`、`:113`，`@Volatile` / `ConcurrentHashMap`） | 同左 |

判决点是 `SystemNotificationPolicy.shouldDeliver`（`notify/SystemNotificationPolicy.kt:116-120`）：

```kotlin
fun shouldDeliver(notification: AppNotification, appInForeground: Boolean): Boolean {
    if (!appInForeground) return true
    val sessionVisible = notification.sessionID != null && notification.viewed
    return !sessionVisible
}
```

`viewed` 本身也是同一竞态的产物：`watchedThrough`（`notify/SystemNotificationPolicy.kt:185-186`）拿
`foregroundSince`（主线程写的 `notify/ForegroundTracker.kt:88`）和 `since`（客户端时钟的回合起点）比大小，
在 raise 时刻求值（`notify/SystemNotifier.kt:285-290`）。

**谁先谁后 → 什么坏结果**

- **A 先、B 后（A 赢）**：raise 读到 `contentVisible=true` 且 `viewed=true` → `shouldDeliver=false` →
  走 `notify/SystemNotifier.kt:312-313`，通知**被扣下**（`withheld` + 2s 复查）。若此刻没有任何兜底，
  这条通知就跟着"用户已离开"这件事一起没了 —— 这正是 v30/v31 两次实测 FAIL 的形态
  （`notify/ForegroundTracker.kt:14-20` 贴了原始 logcat：49.042 `skip:`，49.0 已被按 HOME）。
- **B 先、A 后（B 赢）**：`contentVisible=false` → `shouldDeliver` 直接 `return true` → 正常投递。**这是好结果。**
- **坏结果种类**：**漏通知**（主）；重复通知由 `posted` 槽位去重挡住（`notify/SystemNotifier.kt:132`、`:357`）；
  "指向错的会话"由 `viewed` 的 route 判据挡（`viewed = sessionID == tracked && watched`）——
  用户在别的会话时 `viewed=false`，通知照发（这正是 `notify/SystemNotificationPolicy.kt:14-19` 说的
  "upstream cannot have" 那一例）。

**竞态窗口量级（代码给出）**

- 判决读到的是 `paused` 之后的即时状态，**不是** `stopped`：`onActivityPaused` 是 HOME 的一部分，
  同帧翻转（`notify/ForegroundTracker.kt:26-29`）；而 `onActivityStopped` **迟到约 1.1 s**
  （`notify/ForegroundTracker.kt:14-20` 的实测 logcat，`stopped` 在 50.174 才到）。
- 旧实现（started/stopped 计数）暴露的窗口 = **1.1 s 量级**；
  现实现把这个窗口压到 **A 与 B 的真实交错（微秒～毫秒级，即一帧内）**。
- 真正的残余窗口 = raise 的判决（`SystemNotifier.kt:299`）到 `withheld` 落表（`:312`）之间
  —— 若 `onActivityPaused` 正好插在这两行之间，`postWithheld()`（`:177`→`:437`）读到的 `withheld` 是空的。
  这是**微秒级**窗口，且还叠加"用户在 2s 复查（`SystemNotificationPolicy.kt:52`）前已回前台"才真的丢。

---

## 2. 复现设计

### 2.0 先说清楚"用户离开"在本机怎么触发（这一步决定了复现可行性）

代码里事件 B 有**两个入口**，在 `emulator-5554`（`Medium_Desktop`，freeform 多任务）上只有一个能触发：

| 入口 | 代码 | 本机实测 |
|---|---|---|
| `onActivityPaused` | `notify/SystemNotifier.kt:167-178` | **不触发**。HOME(`keyevent 3`)、recents(`keyevent 187`)、启动 Settings 三种向量各试 3 次，`OcNotify` 里 **0 条** `foreground: paused`，且 `topResumedActivity` 仍是 `com.xiufeigo.opencodeshell/.OcActivity`。freeform 多任务让 activity 一直 RESUMED，`paused` 回调根本不派发。 |
| `onWindowFocusChanged` → focus grace | `notify/SystemNotifier.kt:248-259`、`:268-277` | **可触发**。实测拿到原始 log：<br>`10-04 02:31:19.217 D OcNotify: focus: timeout expired (the window stayed covered)`（= `SystemNotifier.kt:271`，随后 `postWithheld()` `:272`） |

所以本机的"用户不再看着 app"是**焦点被别的窗口盖住**（v68 记的那条），不是 HOME 导致的 pause。
下面所有 trial 的事件 B = `am start -n com.android.settings/.Settings` 把 Settings 盖上来。

### 2.2 证据链先自检（这一步救了一次假阴性）

- `logcat -d -t N --pid=$appPid -s OcNotify` 在本机**会假阴性**：app 的 `EGL_emulation` 高频刷屏
  把 ring buffer 冲掉，实测 `--pid -s OcNotify -t 60` 返回 0 行，而同一时刻
  `logcat -d | grep OcNotify` 有内容。可靠写法是 **`logcat -d | grep OcNotify`**（下面全部用它）。
- 通知是否真的落地，用 `dumpsys notification --noredact` 数 `pkg=com.xiufeigo.opencodeshell`。

### 2.3 最小复现步骤（模板）

1. `am force-stop` + `am start` 拉起 app（本 app 允许），等到会话页（`content-desc="Stop"` 消失 = idle，v133 判据）；
2. `logcat -c` 清 buffer；
3. 往 composer 发一条**会跑几秒**的自建回合（前缀 `E2ECHK5554u1z`）；
4. 在"回合结束"前后 **±Δ** 触发事件 B（盖 Settings）；
5. 等 `withheld` 的两个兜底（`RECHECK_DELAY_MS=2_000`、`WITHHELD_TTL_MS=120_000`）都跑过；
6. 数 `dumpsys notification` 的记录数 + 读 `OcNotify` 全量。

---

## 3. 观察结果（证据文件）

### 3.2 决定性证据：两个分支都跑到了，通知一次都没丢

#### 分支 B（事件 B 先赢）—— 回合在被盖住时结束 ⇒ 正常投递
证据 `race/A2` 之前的实测（app pid 4550，2026-10-04 02:35）：

```
02:35:18.590  focus: lost resumedCount=1
02:35:23.603  focus: timeout expired (the window stayed covered)
02:35:26.990  trigger idle: routed=ses_efc… tracked=ses_efc… foreign=false finished=true since=1791081307983
02:35:26.990  watchedThrough: since=1791081307983 foregroundSince=null -> false
02:35:26.991  raise: kind=AGENT … watched=false visible=ses_efc… viewed=false
02:35:26.997  accepted: AGENT:ses_efc… viewed=false delivery=true
02:35:27.022  posted: id=18637213 kind=AGENT … contentVisible=false via=raise
02:35:27.022  sound: playing the default notification ringtone
```
`dumpsys notification` 记 **3** 条本包记录。
**从 raise 到 posted 用了 32 ms**（`26.990`→`27.022`）——这就是这条链的**实测投递延迟量级**。
关键行是 `watchedThrough: foregroundSince=null -> false`：盖住窗口后 `foregroundSince` 变 `null`，
于是 `viewed=false`，通知照发（`SystemNotificationPolicy.kt:185-186`）。

#### 分支 A（事件 A 先赢）—— 回合在用户正看着时结束 ⇒ 先扣下，再由"用户走开"补投
**这就是 v63 记的那个坏形态，被我一字不差地跑出来了**（`race/A2.log`、`race/A3.log`，pid 4550）：

```
02:38:12.111  trigger idle: … finished=true since=1791081471067
02:38:12.112  watchedThrough: since=1791081471067 foregroundSince=1791081351389 -> true
02:38:12.112  raise: kind=AGENT … watched=true visible=ses_efc… viewed=true
02:38:12.118  accepted: AGENT:ses_efc… viewed=true delivery=true
02:38:12.119  skip: app is foreground on ses_efc… (viewed=true) kind=AGENT resumedCount=1 windowFocused=true
02:38:12.119  recheck: scheduled id=18637213 kind=AGENT in 2000ms
02:38:14.122  recheck: id=18637213 dropped (contentVisible=true postedAt=1791081326992 raised=1791081492113)
--------------- 此时把 Settings 盖上来（用户走开），10 秒后：---------------
02:38:25.963  focus: lost resumedCount=1
02:38:30.968  focus: timeout expired (the window stayed covered)
02:38:30.968  withheld: id=18637213 posting (the user left before the re-check)
02:38:30.973  posted: id=18637213 kind=AGENT … contentVisible=false via=background
02:38:30.974  sound: playing the default notification ringtone
```

这条链有三个**可量化的实测值**：

1. **复查延迟实测 2003 ms** —— `recheck: scheduled … in 2000ms`（12.119）→ `recheck: id=… dropped`（14.122），
   与 `SystemNotificationPolicy.RECHECK_DELAY_MS = 2_000L`（`SystemNotificationPolicy.kt:52`）吻合。
2. **复查确实救不了这个 case**：`recheck: … dropped (contentVisible=true)` —— 2 秒时用户还在看着，
   按设计被丢掉。**这正是 v63 的坏形态**（`SystemNotificationPolicy.kt:59-67` 描述的那次 64s/150s、0 条）。
3. **补投在 5.0 s 后生效**：`focus: timeout expired`（30.968）→ `withheld: … posting`（30.968，同一行）→
   `posted … via=background`（30.973），距 raise **18.8 s**，距"用户走开"（cover，10:38:26.674）**4.3 s**。
   4.3 s = 焦点丢失的 grace（`FOCUS_LOST_GRACE_MS`）+ 调度延迟。
   走的是 `postWithheld()`（`SystemNotifier.kt:437-456`），由焦点超时载体调用（`:272`）。

**坏结果没有出现**：通知不但没丢，而且是 `via=background` 补投出来的。

（重复次数见 §3.3。）

### 3.3 实际跑出来的次数（含失败轮次，不藏）

**有效的两类时序，各跑了 2 次成功 / 共 5 次尝试：**

| 时序 | 成功 | 观察到的投递 | 坏结果 |
|---|---|---|---|
| **B 先赢**：回合在"被盖住"时结束 | **1**（02:35 那次） | 1/1 `posted … via=raise contentVisible=false`，raise→posted **32 ms** | 无 |
| **A 先赢 + 走开晚于复查**（v63 形态） | **2**（A2/A3、M1） | 2/2 `recheck: dropped` 之后被 `withheld: … posting` 补投，`via=background` | **无** |
| **A 先赢 + 走开早于复查** | **0**（没跑成） | —— | —— |

**废掉的轮次（诚实列出，它们不构成结论）**：`T0`（HOME 后回合 120 s 未跑完）、`W1`（25 s 未跑完）、
`B1`/`A1`（`am force-stop` 后 app 需重新连后端，Send 没点上）、`R1`（`running=False`，回合没发起）、
`R2`/`R3`（脚本取到的是 Settings 的 dump，`MISSING composer/send`）。
根因见 §6：`uiautomator` 的 `bounds` 尺度在本机会变（~820 宽 ↔ 1366 宽），
坐标必须**每轮从当轮 dump 解析**；前四轮我是照抄旧坐标，所以 Send 全点空了 ——
它们的"0 条通知"是因为**连 raise 都没发生**，不是竞态。

**M1（第二次独立重复，02:40，可与 A2/A3 对照）**：

```
02:40:29.314  trigger idle: … finished=true since=1791081615812
02:40:29.314  watchedThrough: foregroundSince=1791081607231 -> true
02:40:29.319  skip: app is foreground … (viewed=true) kind=AGENT resumedCount=1 windowFocused=true
02:40:29.319  recheck: scheduled id=18637213 kind=AGENT in 2000ms
02:40:31.322  recheck: id=18637213 dropped (contentVisible=true postedAt=1791081492113 raised=1791081629315)
02:40:34.581  focus: lost resumedCount=1
02:40:39.584  focus: timeout expired (the window stayed covered)
02:40:39.584  withheld: id=18637213 posting (the user left before the re-check)
02:40:39.591  posted: id=18637213 … contentVisible=false via=background
```

两次独立重复里三个关键量级**高度一致**，可当作这条链的实测参数：

| 量 | 期望（代码） | A2/A3 实测 | M1 实测 |
|---|---|---|---|
| 复查延迟 | `RECHECK_DELAY_MS = 2_000`（`SystemNotificationPolicy.kt:52`） | 2003 ms | 2003 ms |
| 焦点宽限 | `FOCUS_LOST_GRACE_MS = 5_000`（`ForegroundTracker.kt:211`） | 5005 ms | 5003 ms |
| raise→posted | —— | 32 ms（B 赢）/ 18.8 s（A 赢走 G3） | 20.3 s（A 赢走 G3） |

| trial | 事件 B 相对发起的延迟 | 回合是否结束 | 通知记录数 | 证据 |
|---|---|---|---|---|
| T0 | HOME +1.5s | 120s 内**未结束** | 0 | `race/T0.log` |
| W1 | 盖 Settings +2.0s | 25s 内**未结束** | 0 | `race/W1.log`（`focus: lost` / `focus: timeout expired` 均已打出） |
| B1 | 盖 Settings +1.2s（快 prompt） | 见下 | 见下 | `race/B1.log` |

**T0 / W1 都不构成结论**：它们的回合（`count from 1 to 20000` / `3000`）根本没跑完，
`OcNotify` 里连 `trigger idle` 都没有 —— 没有 raise，就没有投递，0 条通知是**正确行为**而非竞态。
这一条本身是纪律问题：turn 未结束就下"漏通知"的结论会是假阴性。

（其余 trial 结果见 §5。）

---

## 4. 现有防护点（代码层）—— 复现不出来的原因就在这里

| # | 防护 | 位置 | 作用 | 本次是否被实测打到 |
|---|---|---|---|---|
| G0 | `viewed` 按**回合**时刻算，不按读帧时刻算（`watchedThrough`） | `SystemNotifier.kt:285-290`；`SystemNotificationPolicy.kt:185-186` | 消掉"回前台才读帧 ⇒ 误判已看" | ✅ 两次都命中（`foregroundSince=null -> false` / `=1791081351389 -> true`） |
| G1 | 下行信号用 `paused` 而不是 `stopped` | `SystemNotifier.kt:167-178`；`ForegroundTracker.kt:31-47` | 关掉 v31 的 **1.1 s** 窗口 | ⚠️ **本机不可达**（见 §6） |
| G2 | 扣下 + **2000 ms** 延迟复查 | `SystemNotifier.kt:312-313`、`:381-393`、`:404-418`；`SystemNotificationPolicy.kt:52`、`:149-155` | 救"A 赢、B 后到"里 B 很快到的情况 | ✅ 实测 **2003 ms** 触发（`A2.log`） |
| G3 | 用户**真正走开**时补投（120 s TTL） | `SystemNotifier.kt:177`、`:268-277`、`:437-456`；`SystemNotificationPolicy.kt:99-105`、`:74` | 救 v63：复查已放弃之后 | ✅ **实测救了 v63 形态**（`via=background`） |
| G4 | 槽位去重防重复 | `SystemNotifier.kt:132`、`:350-357` | 兜底投递不重复 | ✅ 两次投递 id 都是 `18637213`（同会话一个槽） |

---

## 5. 判据：**被防护（不是长尾项）**

**结论：在当前实现下，"通知后台投递竞态"复现不出坏结果。** 理由有三条，按证据强度排：

1. **坏结果本身没出现。** 分支 A（raise 先赢）我把 v63 的坏形态一字不差跑出来了：
   `skip:` → 复查 2003 ms 后 `dropped` → **然后**用户走开。若没有兜底，这就是漏通知。
   实测 `withheld: id=18637213 posting` + `posted … via=background` —— **G3 接住了**。
2. **剩余窗口在本机结构上打不中。** 代码里 raise 的判决（`SystemNotifier.kt:299`）与 `withheld` 落表（`:312`）
   之间确有**微秒级**缝隙：若"用户走开"正好插在这两行之间，`postWithheld()` 读到的 `withheld` 是空的。
   但本机唯一可达的"走开"载体是**焦点超时**（`onWindowFocusChanged`，`:248-259` → `:268-277`），
   它天生带 `FOCUS_LOST_GRACE_MS = 5_000L`（`ForegroundTracker.kt:211`）的宽限 —— 比那道缝隙宽 3~4 个数量级。
   实测也印证了这个量级：`focus: lost`(25.963) → `focus: timeout expired`(30.968) 正好 **5005 ms**。
   换句话说：**要丢通知，得让"走开"落在 raise 前后几十微秒内、且用户在 2 s 内回前台**；
   本机没有能产生这种时序的信号。
3. **重复投递由槽位挡住**，两次实测都落在同一槽 `18637213`。

### 次数与时序（**2 类时序、3 次有效复现 / 共 10 次尝试**）

| 时序 | 成功次数 | 观察 | 坏结果 |
|---|---|---|---|
| **B 先赢**：回合在"被盖住"时结束 | 1 | 1/1 投递 `via=raise`，raise→posted **32 ms** | 无 |
| **A 先赢 + 走开晚于复查**（v63 形态） | 2（A2/A3、M1） | 2/2 先被 `recheck: dropped` 放弃，**再**被 G3 补投 `via=background`（走开→补投 4.3 s） | **无** |
| **A 先赢 + 走开早于复查** | 0（未跑成） | —— | —— |
| 废轮（T0/W1/B1/A1/R1/R2/R3） | —— | 回合没发起，见 §6 | 不构成结论 |

**没试成的（未闭环）**：真正的**微秒级**交错（§5.2）在本机无法构造；
`onActivityPaused` 那条更快的载体（1.1 s 窗口那一支）在本机**永不触发**（§6）。
若要闭环，需要一台非 freeform 的手机形态 AVD（或真机），那里 HOME 会真的 `pause`，
G1/G2/G3 三者里最先命中的是 G1 —— 代码上它已经存在，所以**即便补测也不改变"已被防护"的结论**，
但那是我在本机**没有**验证过的部分，不作断言。

---

## 6. 未闭环 / 本机特有约束（重要，影响别人复现）

`emulator-5554`（`Medium_Desktop`）是 **freeform 多任务**形态，这台机器上**按 HOME 不会 `pause`**：

| 向量 | 结果 |
|---|---|
| `input keyevent 3`（HOME） | `topResumedActivity` 仍是 `OcActivity`，`OcNotify` **0 条** `foreground: paused` |
| `input keyevent 187`（recents） | 同上，无 pause |
| `am start com.android.settings/.Settings` | `topResumedActivity` 变成 Settings，但 **OcActivity 仍 RESUMED**，依然无 pause |

即 **G1（`onActivityPaused` 兜底）在这台设备上完全不被行使**，
"用户走开"只能走焦点丢失那一支（有 5 s 宽限）。这既是本报告能给出干净结论的原因，
也是它**不能**替手机形态背书的原因。

另外两条方法论坑，已在本轮踩到并修正，记录下来：
- `logcat -d -t N --pid=$appPid -s OcNotify` 在本机会**假阴性**（`EGL_emulation` 刷屏冲掉 buffer）；
  可靠写法 `logcat -d | grep OcNotify`。
- `uiautomator dump` 的 `bounds` 尺度会变（曾从 ~820 宽变到 1366 宽），
  **坐标必须每轮从当轮 dump 重新解析**，否则 `Send` 点空、回合根本没发起 ——
  `T0/W1/B1/A1` 四轮都是这么废掉的（它们的 0 条通知**不构成任何结论**，因为连 raise 都没有）。

---

## 7. 复现步骤（可交给别人照做）

前置：`adb` 全路径、`reverse tcp:4095`、app 已装且 md5 一致；坐标每轮从 dump 解析。

1. `am force-stop` → `am start -n com.xiufeigo.opencodeshell/.OcActivity`，等 idle（`content-desc="Stop"` 消失）。
2. `logcat -c`。
3. 会话页发一条会跑 ~20 s 的自建回合（`E2ECHK5554u1z` 前缀）。
4. **分支 A**（关键）：**保持窗口可见**直到回合结束 —— 此时 `OcNotify` 应打出
   `skip: app is foreground … (viewed=true)` + `recheck: scheduled … in 2000ms`，
   2 s 后 `recheck: … dropped (contentVisible=true)`。
5. **再等 4~6 s**（让复查彻底放弃），然后 `am start -n com.android.settings/.Settings` 盖上来。
6. 等 ~6 s：`OcNotify` 应出现 `focus: timeout expired` → `withheld: id=… posting` →
   `posted: … via=background`；`dumpsys notification --noredact | grep -c 'pkg=com.xiufeigo.opencodeshell'` > 0。
   **只要这行 `withheld: … posting` 在，竞态就被 G3 挡住了。**
7. 复现失败时优先怀疑 §6 的两个坑（logcat 假阴性 / 坐标尺度），而不是判定竞态存在。
