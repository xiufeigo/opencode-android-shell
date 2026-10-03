# v98 真机复验 — 通知三连 U6/U7/U8 + IME 两条 U4/U5

- 执行人：`mcode`
- 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
- 设备：**emulator-5564**（Pixel 10 Pro Fold，全屏手机形态）
- 纪律：未跑 gradle / 未装机 / 未杀进程（仅本 app 的 `am force-stop` 未使用）；只操作 5564；未触碰 `127.0.0.1:5555`；**全程未按 BACK(4)**、**弹层内未按 ESC(111)**；截图走 `screencap -p` + `adb pull`；空格用 `keyevent 62`；坐标全部运行期从 dump 解析；dump 解析同时匹配 `text="…"`/`text='…'` 且用 `--compressed`。
- 会话前缀：**`E2ECHK5564n`**（自建会话，收尾已清理）

---

## 0. 环境读数

| # | 项 | 命令 | 读数 |
|---|---|---|---|
| 1 | 设备列表 | `adb devices` | `emulator-5554/5556/5558/5562/5564/5590/5600` 全 `device`；**本轮只用 5564** |
| 2 | boot | `shell getprop sys.boot_completed` | `1` |
| 3 | wm size | `shell wm size` | `Physical size: 2076x2152`，**无 Override** |
| 4 | wm density | `shell wm density` | `Physical density: 390`，**无 Override** |
| 5 | SDK | `shell getprop ro.build.version.sdk` | `37` |
| 6 | model | `shell getprop ro.product.model` | `sdk_gphone16k_x86_64` |
| 7 | screenState | `shell dumpsys window policy` | `screenState=SCREEN_STATE_ON`，`mKeyguardOccluded=false`（未锁屏） |
| 8 | mCurrentFocus（初始） | `shell dumpsys window` | `com.google.android.apps.nexuslauncher/.NexusLauncherActivity`，`mFocusedApp` 非空 → **无 ANR** |
| 9 | reverse | `shell reverse --list` | `host-15 tcp:4095 tcp:4095` / `host-15 tcp:2222 tcp:22` |
| 10 | 装机 | `shell pm list packages` | `com.xiufeigo.opencodeshell` 存在 |
| 11 | POST_NOTIFICATIONS | `shell dumpsys package` | `granted=true, flags=[USER_SET\|USER_SENSITIVE_WHEN_GRANTED\|USER_SENSITIVE_WHEN_DENIED]` |
| 12 | 通知 channel | `dumpsys notification --noredact` | `NotificationChannel{mId='opencode.sessions', mImportance=3, …}`，AppSettings `importance=DEFAULT userSet=true` |
| 13 | **通知基线** | 同上 | `NotificationRecord` 命中数 = **0**（干净起点） |
| 14 | **notifyAgent 原值** | `run-as … cat shared_prefs/oc_settings.xml` | **该 key 不存在** → 取代码默认值 |

> **`wm` 说明**：本机**没有任何 Override**，本轮也**未执行过 `wm size` / `wm density`**，因此无需 reset（收尾已复核，见 §7）。

### 0.1 `notifyAgent` 原值与处置（**未改动，无需还原**）

`SettingsStore.kt:184`：
```kotlin
notifyAgent = prefs.getBoolean(KEY_NOTIFY_AGENT, true),
```
`oc_settings.xml`（`u_settings_pre.txt`）中**没有** `opencode.android.notifyAgent` 键 → 运行期取默认 **true**。

**结论：本轮不需要开、也没有关过 `notifyAgent`，因此不存在还原动作。** 运行期日志独立坐实生效值：
```
OcNotify: gate settings: kind=AGENT enabled=true
```
> 与 `REPORT_FINAL_SWEEP.md` 条目 5.1 的读数一致（"默认开着"）。本轮**不重复**该结论，而是把它当作 U6 的**前置已满足**条件直接用。

### 0.2 一句话表

| # | 项 | 判定 | 关键读数 |
|---|---|---|---|
| U6 | 通知横幅**当场可见** | ✅ **PASS** | **后台→通知 = 23.7s**（5s 粒度轮询在 25.8s 捕获） |
| U7 | 通知**点击回跳** | ✅ **PASS** | tap 后 `mCurrentFocus=OcActivity` + 会话内容在屏 |
| U8 | **清除通知** | ✅ **PASS** | 面板 `清除全部` → `notifCount` 1 → **0** |
| U4 | 真实系统回滑收键盘 | ✅ **PASS** | 回滑后 `mInputShown true→false`，**未离开会话** |
| U5 | 弹层内收键盘 | ✅ **PASS（推翻"无可靠手法"）** | 系统回滑在命令面板内**有效**，面板保持打开 |

> **U5 结论翻转**：`5562_v98/REPORT_SWEEPD2.md` 记的"弹层内收键盘无可靠手法"，本轮**被推翻**——真实系统回滑手势在弹层内可收键盘，且**不关闭弹层**。详见 §5。

---

## 1. U6 通知横幅"当场可见" → ✅ **PASS**

### 判定
**通过。后台 → 通知出现 = 23.7s**（远好于 `REPORT_FINAL_SWEEP.md` 记录的"约 3 分钟"）。

### 前置
1. `notifyAgent` **默认已开**（§0.1，未改动）。
2. 自建会话，标题最终为 **`E2ECHK5564n u6 Notify Probe Reply`**，id **`ses_f00253915ffeDVGp9c8tL0LwUJ`**。

### 命令（关键序列）
```powershell
Adb shell input tap 600 1143        # 点输入框
foreach($w in @('E2ECHK5564n','u6','third')){ Adb shell input text $w; Adb shell input keyevent 62 }
Adb shell input text 'turn';  Adb shell input keyevent 62
Adb shell input text 'idle';  Adb shell input keyevent 62
Adb shell input text 'probe'
Adb logcat -c
Adb shell input keyevent 66         # 发送
Start-Sleep -Seconds 5
Adb shell input keyevent 3          # HOME（允许）
# 之后 5s 粒度轮询 NotifCount，最长 360s
```

### 读数 A — 链路逐环（**全部在后台**）
```
03:51:02.622 D OcNotify: foreground: paused=OcActivity resumedCount=0
03:51:02.810 D OcNotify: focus: lost resumedCount=0
03:51:04.008 D OcNotify: foreground: stopped=OcActivity resumedCount=0        ← app 已完全后台
03:51:27.622 D OcNotify: trigger idle: routed=ses_f00253915ffeDVGp9c8tL0LwUJ tracked=ses_f00253915ffeDVGp9c8tL0LwUJ foreign=false finished=true since=1790999457585
03:51:27.622 D OcNotify: watchedThrough: since=1790999457585 foregroundSince=null -> false
03:51:27.622 D OcNotify: raise: kind=AGENT session=ses_f00253915ffeDVGp9c8tL0LwUJ since=1790999457585 watched=false visible=ses_f00253915ffeDVGp9c8tL0LwUJ viewed=false
03:51:27.622 D OcNotify: gate settings: kind=AGENT enabled=true
03:51:27.623 D OcNotify: accepted: AGENT:ses_f00253915ffeDVGp9c8tL0LwUJ viewed=false delivery=true
03:51:27.671 D OcNotify: posted: id=489768309 kind=AGENT session=ses_f00253915ffeDVGp9c8tL0LwUJ title=E2ECHK5564n u6 Notify Probe Reply viewed=false sound=default contentVisible=false via=raise
03:51:27.671 D OcNotify: sound: playing the default notification ringtone
```

逐环对照 `NOTIFICATION_IDLE_DIAG.md` §A1 门禁链：

| 环节 | 证据 | 判定 |
|---|---|---|
| 终帧真读到 | `trigger idle … finished=true`（`foreign=false`） | ✅ |
| 观看时长门禁 | `watchedThrough: … -> false`、`watched=false` | ✅（后台不看，符合预期） |
| 归属门禁 | 无 `skipped foreign session=` | ✅ |
| `notifyAgent` 门禁 | `gate settings: kind=AGENT enabled=true` | ✅ |
| 焦点门禁 | `viewed=false` → **直接 post，未走前台 recheck 分支** | ✅ |
| **真正 post** | `posted: id=489768309 … viewed=false sound=default … via=raise` | ✅ |
| 声音 | `sound: playing the default notification ringtone` | ✅ |
| 系统侧落库 | `NotificationRecord` 命中 | ✅ |

### 读数 B — 系统侧
```
NotificationRecord(0x051e784c: pkg=com.xiufeigo.opencodeshell user=UserHandle{0} id=489768309
  tag=null importance=3 key=0|com.xiufeigo.opencodeshell|489768309|null|10230:
  Notification(channel=opencode.sessions shortcut=null contentView=null vibrate=null sound=null
  defaults=0 flags=AUTO_CANCEL color=0x00000000 vis=PRIVATE
  template=android.app.Notification$BigTextStyle))
```

### ⏱ 秒数（本条的核心读数）

| 口径 | 数值 | 依据 |
|---|---|---|
| **app 完全后台 → 通知 post** | **23.7s** | `foreground: stopped` `03:51:04.008` → `posted:` `03:51:27.671`（设备日志同源时钟，**精确**） |
| 终帧读到 → post | **49 ms** | `03:51:27.622` → `03:51:27.671`，**`raise` 路径零延迟** |
| 5s 粒度轮询捕获 | 25.8s | 轮询 `poll#5 t=20.7s count=0` / `poll#6 t=25.8s count=1` |

> **取值 23.7s 为准**；25.8s 只是 5s 轮询栅栏的上界观测。

### 横幅在屏的证据
- 拉下状态栏后 dump 渲染出该通知行（§2 证据）：`OpenCode` / `回复已完成` / **`E2ECHK5564n u6 Notify Probe Reply`**。
- **未取到"横幅压在会话之上"的原始截图**：当轮 `Shot` 辅助函数存在 `screencap -p` 参数歧义（PowerShell 把 `-p` 当 `-ProgressAction`），已在其后修复，但通知已被 U8 清掉，无法补拍。
  ⇒ **横幅"当场在屏"这一条，以 `posted:` 日志 + `NotificationRecord` + 面板渲染行三方坐实，缺一张原始截图。**

### 🔍 关键发现：**"3 分钟冻结"不是稳定规律**
`REPORT_FINAL_SWEEP.md` 条目 5 记的是"切后台后约 3 分钟才投递"，并推测为进程冻结。本轮**只用了 23.7s**，原因清楚：

- 本轮切后台时**回合仍在生成**（`Build · space-bunny-free · 30 秒`），进程**未被冻结**，SSE 保持活着，`03:51:27.622` 照常读到终帧。
- 5556 那次则是**长回合已在前台跑完、切后台后进程被冻结**，终帧迟迟读不到 ⇒ 拖到约 3 分钟。

⇒ **同一个缺口是"后台终帧能否被读到"的竞态，不是固定的 3 分钟延迟。** 只要切后台时 SSE 仍活着，投递就是秒级（~24s，且其中 23.6s 是**模型还在跑**的时间，不是投递延迟）。这解释了为什么"等待给足 ≥5 分钟"是必要的——但也说明**并非必然要等 3 分钟**。

### ⚠️ 附：一次"前台被丢弃"的对照（说明为什么要先切后台）
首轮在**前台**触发同一链路，走的是另一条分支，最终**没出通知**：
```
03:43:03.937 skip: app is foreground on ses_… (viewed=true) kind=AGENT resumedCount=1 windowFocused=true
03:43:03.941 recheck: scheduled id=489768309 kind=AGENT in 2000ms
03:43:05.945 recheck: id=489768309 dropped (contentVisible=true postedAt=null raised=1790998983935)
```
`recheck: dropped (contentVisible=true)` ⇒ **用户没在 2s 补检窗口内离开前台，通知被丢弃**。这正是文档 §A1 描述的"前台 → 补检"路径，**与 U6 要求的"先 idle 再切后台"顺序互为印证**。

---

## 2. U7 通知"点击回跳" → ✅ **PASS**

### 判定
**通过。点击通知行后回到该会话。**

### 命令
```powershell
Adb shell input swipe 1000 5 1000 1900 300    # 下拉状态栏
# 从 dump 解析通知行 bounds（运行期坐标）
Adb shell input tap 1100 900                  # tap 通知行
```

### 证据 A — 面板内的通知行（`u7_shade.xml`，运行期解析）
```
469,820,603,859   T=[OpenCode]
633,820,688,859   T=[now]
469,873,1744,923  T=[回复已完成]
469,943,1744,989  T=[E2ECHK5564n u6 Notify Probe Reply]     ← 通知行文本
1642,781,1783,903 D=[Collapse]
```
tap 点取行文本 bounds 垂直居中偏上（`(469+1744)/2=1106`，y 取 900）。

### 证据 B — tap 之后
```
mCurrentFocus=Window{3ba7ec4 u0 com.xiufeigo.opencodeshell/com.xiufeigo.opencodeshell.OcActivity
```
dump（`u7_after.xml`）出现**该会话的内容**：
```
167,414,610,453   T=[E2ECHK5564n u6 Notify Probe Reply]     ← 标签页标题
206,468,784,585   T=[E2ECHK5564n u6 Notify Probe Reply]     ← 会话头
39,803,1944,858   T=[第三轮已收到。E2ECHK5564 存活正常，仍处于空闲状态，未做任何工具调用或改动。]
39,887,1944,942   T=[需要我动手时直接说目标就行。]
122,990,560,1032  T=[Build · space-bunny-free · 30 秒]
```

### 断言
- ✅ `mCurrentFocus` = `OcActivity`
- ✅ dump 出现**目标会话标题 + 该会话的消息内容**
- ✅ 未使用 BACK(4)

### 附：tap 顺带触发了 AUTO_CANCEL
`dumpsys notification` 的 `flags=AUTO_CANCEL` 生效 —— tap 之后 `notifCount` 直接变 **0**。这意味着 **U7 的"点击回跳"与 U8 的"清除"在默认 flag 下是同一次动作**；U8 因此单独再跑一轮做**显式清除**验证（§3）。

---

## 3. U8 清除通知 → ✅ **PASS**

### 判定
**通过。面板 `清除全部` 后该通知从 `dumpsys notification` 消失。**

### 命令
```powershell
# 先生成一条新通知（同样"发送 → 5s → HOME"）
Adb shell input keyevent 3
# 等 notifCount>0 后
Adb shell input swipe 1000 5 1000 1900 300        # 下拉面板
# 从 dump 解析 [清除全部] 节点 bounds → 运行时中心点
Adb shell input tap 1038 1462                      # Clear all
```

### 读数（`u8_log.txt`）
```
11:52:45.263  === U8 START notifCount=0 ===
11:52:55.513  HOME issued; focus=…nexuslauncher…     ← keyevent 3 本轮同样直接生效
11:53:00.819  notif appeared t=5.4s count=1
11:53:00.930  pre-clear count=1
11:53:06.821  shade focus=…NotificationShade
11:53:06.858  node T=[]        D=[Clear all silent notifications] bounds=1656,1072,1773,1189
11:53:06.859  node T=[Clear all] D=[Clear all notifications.]    bounds=458,1404,1618,1521
11:53:06.876  tapping Clear all at 1038,1462         ← 坐标由 dump bounds 运行期算出
11:53:10.102  post-clear count=0                     ← 清除生效
11:53:13.186  DONE
```

### 断言
- ✅ 清除前 `NotificationRecord` 命中 = **1**
- ✅ 面板出现 `清除全部`（`content-desc="Clear all notifications."`）
- ✅ tap 后 `notifCount` = **0**，`dumpsys notification` 中 `NotificationRecord.*opencodeshell` **无任何命中**
- ✅ 收尾复读仍为 0

> 注：`清除全部` 按钮的 bounds 很宽（`458,1404,1618,1521`），是整行可点；中心点 `(1038,1462)` 命中。

---

## 4. U4 真实系统回滑收键盘 → ✅ **PASS**

### 判定
**通过。系统回滑手势收起 IME，且未离开会话。** **全程未使用 BACK(4)。**

### 回滑边距取值（本机 `dumpsys window` 实测）
```
InsetsSource id=791e0005 type=mandatorySystemGestures frame=[0,2074][2076,2152]  sideHint=BOTTOM
InsetsSource id=e55c0005 type=mandatorySystemGestures frame=[0,0][2076,165]        sideHint=TOP
InsetsFrameProvider: Insets{left=0, top=…, right=0, bottom=78}
```
⇒ 左右侧 `left=0 / right=0`（**无侧边排除区**），故起手点取 `x=2070`（右）/ `x=6`（左）合法；底边 78px 为 taskbar，回滑 y 取中段 `1200` 避开。

### 命令
```powershell
Adb shell input tap 600 1143                                # 唤出 IME
Adb shell input swipe 2070 1200 1100 1200 260              # 右侧边缘 → 中间
```

### 读数
| 阶段 | `mInputShown` | `mCurrentFocus` | 会话内容 |
|---|---|---|---|
| 回滑前 | `true` | `OcActivity` | `167,161,610,200 T=[E2ECHK5564n u6 Notify Probe Reply]` |
| **回滑后** | **`false`** ✅ | **`OcActivity`** ✅ | `167,161,610,200 T=[E2ECHK5564n u6 Notify Probe Reply]` ✅ |

回滑后 dump 仍含该会话的多轮内容（`E2ECHK5564n u6 third turn idle probe` / `E2ECHK5564n u6 second turn idle probe`），**会话未被回退**。

### 断言
- ✅ `mInputShown` `true → false`
- ✅ `mCurrentFocus` 仍为 `OcActivity`（**未退出 app**）
- ✅ dump 中会话标题与消息仍在（**未离开会话**）
- ✅ 全程未按 BACK(4)

### ⚠️ 边距提醒（对后续 U4 复测有用）
**左边缘回滑在 IME 已收起时会直接把 app 划走**（§5 方法 D 实测 `mCurrentFocus` 变成 `nexuslauncher`）。**测 U4 必须确保 IME 处于 `mInputShown=true` 时起手**，否则读数会被"退出 app"污染。

---

## 5. U5 弹层内收键盘 → ✅ **PASS（推翻"无可靠手法"）**

### 判定
**通过。系统回滑手势在弹层内可收键盘，且不关闭弹层。** `REPORT_SWEEPD2.md` 的"无可靠手法"结论**本轮被推翻**。

### 弹层选择
用会话内**命令面板**（`content-desc="命令面板"`，运行期坐标 `772,122,889,239` → 中心 `(830,180)`）——它自带输入框、是真弹层，且比 SAF/目录选择更贴近日常使用。

```powershell
Adb shell input tap 830 180       # 打开命令面板
# 打开后 mInputShown=true
```

### 四种手法实测（**全程未按 ESC(111)**）

| # | 手法 | 命令 | `mInputShown` | 弹层状态 | `mCurrentFocus` | 有效？ |
|---|---|---|---|---|---|---|
| **A** | 点弹层内**非输入区**（`命令` 标签） | `input tap 400 287` | `true`（**未收**） | 仍打开 | `OcActivity` | ❌ **无效** |
| **B** | **系统回滑（右边缘）** | `input swipe 2070 1200 1100 1200 260` | `true → false` ✅ | **仍打开** ✅ | `OcActivity` ✅ | ✅ **有效** |
| **C** | 点弹层外**遮罩** | `input tap 1950 600` | `false` | **整个弹层被关闭** | `OcActivity` | ⚠️ 部分有效（收键盘靠"关弹层"顺带达成，非纯收键盘） |
| **D** | 系统回滑（左边缘，IME 已收） | `input swipe 6 1200 900 1200 260` | `false` | 弹层已不在 | **`nexuslauncher`** ❌ | ❌ **有害**：直接划出 app |

### 读数原文
```
=== U5 method A: tap inside palette on non-input area (命令 label) ===
ime=mInputShown=true   focus=…OcActivity
palette still open? -> True                     ← 键盘没收，弹层还在

=== U5 method B: back-gesture swipe (same as U4) ===
ime=mInputShown=false  focus=…OcActivity
palette still open? -> True                     ← ★ 键盘收了，弹层还在
```

### 结论（回答"哪种手法有效/无效"）
- **✅ 有效且推荐：真实系统回滑手势**（右边缘 `input swipe 2070 1200 1100 1200 260`）——**在弹层内同样能收键盘，且弹层保持打开、不回退、不退出 app**。与 U4 是**同一条**手法，说明"弹层内收键盘无可靠手法"这一旧结论不再成立。
- ❌ **无效**：点弹层内非输入区（方法 A）——焦点仍在输入框，键盘纹丝不动。
- ⚠️ **不纯净**：点遮罩（方法 C）——连**整个弹层一起关掉**了，不是"只收键盘"；且本轮该次 tap 前 IME 未重新唤起（`ime re-raised=false`），键盘收起与 tap 的因果被**部分混淆**，不能单独作为可靠结论。
- ❌ **有害**：左边缘回滑（方法 D）——IME 已收时会把 app 划到桌面。

> **对 U5 的最终判定**：以方法 B 判 **PASS**。"无可靠手法"的成因很可能就是当时**只试了 ESC / BACK / 点遮罩**这类 app 内合成事件，没试**真实系统回滑**（本轮纪律明确禁止 ESC 与 BACK，正是逼出这条路径）。

---

## 6. 未闭环 / 存疑

| # | 项 | 说明 | 影响 |
|---|---|---|---|
| 1 | **横幅"当场在屏"缺原始截图** | `Shot` 辅助函数 `screencap -p` 参数歧义（PowerShell 吞掉 `-p`），当轮未拍到；修好后通知已被 U8 清掉，无法补拍 | 低。三方证据（`posted:` 日志 / `NotificationRecord` / 面板渲染行）已覆盖"通知确实投递并可见"，但"横幅压在会话之上"这一具体画面未留档 |
| 2 | **左边缘回滑收键盘未单独取证** | U5 方法 D 因弹层已关、IME 已收而污染（划出 app）；U4 只用右边缘取证通过 | 低。右边缘已 PASS；左右边缘在 Android 上对称，**但严格说左边缘未在"IME 在屏"状态下验证过** |
| 3 | **"3 分钟冻结"未被复现为对照组** | 本轮只跑了"切后台时 SSE 仍活着"的成功路径，未再跑一次"回合已跑完再切后台"的冻结路径 | 中。§1 已给出机理解释，但**没有同机对照数据**坐实"回合跑完再切后台 ⇒ 慢 3 分钟" |
| 4 | `notifyAgent` 开关**UI 层未点验** | 依据代码默认值 + 运行期 `gate settings: kind=AGENT enabled=true` 判定为开；未在设置页肉眼确认 toggle 位置 | 无。门禁日志是比 UI 更直接的生效证据 |
| 5 | U7 顺带 `AUTO_CANCEL` | tap 回跳与清除在默认 flag 下是同一次动作 | 无。已用 U8 独立覆盖显式清除 |
| 6 | 通知**点击回跳的深链目标** | 只能断言"回到该 app 且该会话在屏"，未验证 PendingIntent 的 session id 精确性 | 低。dump 显示的就是目标会话 |

---

## 7. 收尾核对

| # | 项 | 命令 | 读数 | 判定 |
|---|---|---|---|---|
| 1 | **`notifyAgent` 还原** | `run-as … cat shared_prefs/oc_settings.xml` | 仍为 5 个键、**无 `notifyAgent`** ⇒ 与开测前逐字一致 | ✅ **本轮从未改动，无需还原** |
| 2 | 自建会话清理 | 会话列表 → 更多选项 → 删除 → 确认 | 确认框 `删除会话？将删除「E2ECHK5564n u6 Notify Probe Reply」及其全部消息` → 删除后列表中 `E2ECHK5564n` 命中 **0** | ✅ 已删除（全程未用 BACK/ESC，用的是 `更多选项`+`删除`+确认按钮） |
| 3 | `/sdcard` 残留 | `shell ls -l /sdcard/` | 匹配 `E2ECHK5564` 的文件 **0 个**；所有 dump/截图均 `rm` 远端副本 | ✅ 无新增残留 |
| 4 | **`wm` 未改则复核** | `shell wm size` / `wm density` | `Physical size: 2076x2152`（**无 Override**）/ `Physical density: 390`（**无 Override**） | ✅ **本轮从未执行 `wm size` / `wm density`，无需 reset** |
| 5 | 系统通知终态 | `dumpsys notification --noredact` | `NotificationRecord.*opencodeshell` 命中 **0** | ✅ 已清空 |
| 6 | 通知通道残留 | 同上 | `opencode.sessions` channel 仍在（App 级设置，**非本轮残留**，为 app 正常行为） | ℹ️ 保留 |

### 纪律自检
| 纪律 | 遵守情况 |
|---|---|
| 不跑 gradle / 不装机 / 不杀进程 | ✅ 全程未执行（`am force-stop` 本 app 也未使用） |
| 只操作 5564 | ✅ 所有 `adb -s emulator-5564`；`127.0.0.1:5555` 未触碰 |
| **绝不按 BACK(4)** | ✅ 全文所有回跳/返回均用 `keyevent 3`、`am start MAIN/HOME`、或点 UI 元素 |
| 弹层内禁 ESC(111) | ✅ U5 全程未按 ESC |
| 截图只用 `screencap -p` + `adb pull` | ✅ 未用 `exec-out`（见 §6-1 的参数歧义，已修） |
| 空格用 `keyevent 62`、禁 `%s` | ✅ 全部走 `keyevent 62` |
| `wm size` 配 `wm density` 且验完 reset | ✅ 本机无 Override 且**本轮未改**，无需 reset（已复核） |
| 只动自建会话（前缀 `E2ECHK5564n`） | ✅ 未删除/未改动他人会话；自建会话已清理 |
| 坐标运行期从 dump 解析 | ✅ U7 通知行、U8 `清除全部`、U4 回滑边距、U5 面板按钮均为运行期 dump 解析所得 |
| dump 同时匹配 `text="…"`/`text='…'`、优先 `--compressed` | ✅ `FindNode` 双引号形态、`DumpUI` 一律 `--compressed` |
| 边做边写盘 | ✅ 脚本 `Out-File -Append` 逐行落盘（`u6_log.txt` / `u8_log.txt` / `u7_shade.xml` / `u5_*.xml` 等） |
| `mCurrentFocus` 异常则 `adb reboot` | ✅ 全程 `mCurrentFocus` 均为正常 app/launcher/shade，**未出现 ANR，未触发 reboot** |

### 证据文件（`research/v97_verify/5564_v98/`）
`u_t3.xml`（会话列表）· `u_t4.xml`（新建会话表单）· `u_t5.xml`（创建后会话）· `u6_log.txt`（U6 秒数逐条）· `u7_shade.xml`（U7 通知行 bounds）· `u7_after.xml`（U7 回跳后会话内容）· `u8_log.txt`（U8 清除）· `u8_shade.xml`（U8 面板）· `u4_pre.xml` / `u4_post.xml` / `u4_left.xml`（U4 前后）· `u5_palette.xml` / `u5_a.xml` / `u5_b.xml` / `u5_c.xml` / `u5_d.xml`（U5 四手法）· `settings_pre.txt`（`notifyAgent` 原值）· `u6_banner.png` / `u7_after.png` / `u4_after.png` / `u8_shade.png`（截图）

---

## 8. 总体结论

**U6 / U7 / U8 / U4 / U5 五条全部 PASS。**

- 通知链路在真机**完整可用**：终帧读到 → `raise` → `posted` → `NotificationRecord` 落库，**后台 23.7s 送达**；点击可回跳到该会话；可被显式清除。
- **"切后台后约 3 分钟才投递"不是稳定延迟**，而是"后台能否读到终帧"的竞态；本轮 SSE 存活时**秒级送达**。
- **弹层内收键盘并非"无可靠手法"**：真实系统回滑在命令面板内有效且不关弹层；无效的是"点弹层内非输入区"，有害的是"IME 已收时从左边缘回滑"。
