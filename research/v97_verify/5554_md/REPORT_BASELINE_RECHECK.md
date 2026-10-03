# REPORT_BASELINE_RECHECK —— v128 真机复验（基线未漂复核 + 队列几何换姿势重试）

- 执行：mavis（mcode），**第 4 轮**
- 日期：2026-10-04
- 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
- 设备：`emulator-5554`（Medium_Desktop，Override `1366x768` / `160`，SDK 34）
- 证据目录：`research/v97_verify/5554_md/v128/`
- 覆盖对象：`research/v97_verify/5554_md/REPORT_FINAL_VERIFY.md`（第 3 次终验）

> **一句话结论**：**基线没有漂 —— 768 的 `140` / `92` 与 600 的 `51`、以及两档的 ①，全部逐位复现；缩窗往返 2 轮 logcat 零命中、pid 全程未变。**
> **B 部分取得整个会话的第一次突破：`Queue` chip 与队列条在真机上被测到了**，
> `Steer` h=48 / `×` h=48 / 两者 `clickable=true`（其中 `×` 的 a11y 标签是 **`Remove`**，从不是 `×` —— 这就是前几轮"测不到"的根因）。

---

## 0. 构建 / 后端 / 归属核对

| 项 | 期望 | 实测 | 判定 |
|---|---|---|---|
| APK md5 | `940476cd6cca62f541449a1f6aea994e` | `940476cd6cca62f541449a1f6aea994e` | ✅ 逐位一致 |
| APK bytes | — | `44,198,253` | — |
| 设备 `lastUpdateTime` | `2026-10-03 20:12:34` | `2026-10-03 20:12:34` | ✅ 逐位一致 |
| `versionName` / `versionCode` | — | `1.4.0` / `26` | — |
| `Override size` | `1366x768` | `1366x768` | ✅ |
| `Override density` | `160` | `160` | ✅ |
| 后端 v2 | 4095 在线 | `STATUS=200` + `{"version":"2.0.14","pid":712}` | ✅ |
| `adb reverse` | `tcp:4095` 已挂 | `host-19 tcp:4095 tcp:4095` | ✅ |
| 归属 | 每步交互前 `mCurrentFocus` 属本 app | 全程 `com.xiufeigo.opencodeshell/.OcActivity` | ✅ |
| 设备纪律 | 不碰其他 emulator | 全程只对 `-s emulator-5554` 发指令 | ✅ |

**APK 路径**：`app/build/outputs/apk/debug/app-debug.apk`

**第 0 步（认证）**：本轮 app **已处于已认证状态**（Lead 之前装好后 v127 的连接被持久化）。
启动 `am start -n com.xiufeigo.opencodeshell/.OcActivity` 后直接落在会话 `Friendly greeting`，
**未重复填地址/口令、未触发 401**，会话内容与消息真实可见。

> ⚠️ **口径更正（留给下一轮）**：本轮**没有新建会话**，`E2ECHK5554u1r` 只是**发进既有会话
> `Friendly greeting` 的消息前缀**。按纪律"只动自建会话"，**未对 `Friendly greeting` 做
> Stop / 长按 / 删除**。收尾因此无需会话清理（详见 §5）。

**本轮记 0 次 ANR**（全程 `dumpsys window` 无 `Not Responding`），因此**未执行 `adb reboot`**。

---

## 1. 主判据 A：两档对照（**逐格判"是否漂"**）

**统一口径**（沿用 Lead 裁定）：验收量 `unionBottom ≤ paneBottom`；
`unionBottom` = **composer 子节点并集**底沿（**排除 `Open terminal`**）；
`paneBottom` = 生产口径 pane 可写区底沿。**两组都给**：(i) dump 侧 / (ii) 生产口径换算。
band = `48`（`BAND_HEIGHT`）。

**读数前置断言**：两档都在 `mInputShown=true` 下读出（脚本硬断言，不为 true 直接退出）。
**坐标全部运行期从 dump 解析**（切档后重新解析，见 `geom.ps1`）。
**union 口径（本轮修正）**：composer 从 **editor 顶沿**起算，**editor 以上的节点属时间线、
不算 composer 子节点** —— 否则会把 `Copy response` / 建议栏算进并集，`readable` 会被错算成 10。

### 1.1 768 档（`wm size 1366x768` + `wm density 160`）

```
mInputShown=true        appPid=6933
mCurrentFocus=Window{be30ff1 u0 com.xiufeigo.opencodeshell/.OcActivity}
```

**pane 宿主盒（dump 侧）**
```
View [0,162][1366,350]   h = 188  =>  paneBottom(i) = 350
```
**composer 子节点（排除 `Open terminal [1320,192][1366,240]`）**
```
EditText              [28,244][1338,292]    h=48
Button  (Add)         [10,292][52,340]      h=48
View    (Model chip)  [52,302][262,330]     h=28
View    (Send)        [1308,292][1356,340]  h=48
HorizontalScrollView  [52,302][1308,330]    h=28
TextView placeholder   [28,250][319,270]     h=20
View                  [28,250][118,265]      h=15
```
并集 = `[10,244][1356,340]`（`unionPx = 96` = editor 48 + toolRow 48，**堆叠形态**）

| 项 | 本轮实测 | 上次终验基线 | **是否漂** |
|---|---|---|---|
| pane 盒 h | `188` | `188` | **否** |
| **时间线盒**（`188 − 48`） | **`140`** | **`140`** | **否（逐位）** ✅ |
| **readable**（`188 − 96`） | **`92`** | **`92`** | **否（逐位）** ✅ |
| **① (i) dump 侧**（`unionBottom 340 ≤ paneBottom 350`） | **`−10`** | **`−10`** | **否（逐位）** ✅ |
| ① (ii) 生产口径（`304 ≤ 302`） | **`+2`** | `+2` | **否（逐位复现）** |
| ② `unionPx(96) ≤ band(48)` | **假** | 假 | **否** |
| `mInputShown` | `true` | `true` | 否 |

### 1.2 600 档（`wm size 1366x600` + `wm density 160`）

```
mInputShown=true        appPid=6933
mCurrentFocus=com.xiufeigo.opencodeshell/.OcActivity
```

**pane 宿主盒（dump 侧）**
```
View [0,162][1366,261]   h = 99  =>  paneBottom(i) = 261
```
**composer 子节点（排除 `Open terminal`）**
```
EditText              [24,201][399,249]     h=48
Button  (Add)         [399,201][441,249]    h=48
View    (Model chip)  [441,211][651,239]     h=28
View    (Send)        [1316,201][1364,249]  h=48
HorizontalScrollView  [441,211][1316,239]    h=28
TextView placeholder   [24,215][315,235]     h=20
View                  [24,215][114,230]      h=15
```
并集 = `[24,201][1364,249]`（`unionPx = 48`，**一行形态**）

| 项 | 本轮实测 | 上次终验基线 | **是否漂** |
|---|---|---|---|
| pane 盒 h | `99` | `99` | **否** |
| **readable**（`99 − 48`） | **`51`** | **`51`** | **否（逐位）** ✅ |
| **① (i) dump 侧**（`unionBottom 249 ≤ paneBottom 261`） | **`−12`** | **`−12`** | **否（逐位）** ✅ |
| ① (ii) 生产口径（`213 ≤ 213`） | **`0`** | `0` | **否（生产零余量逐位）** |
| ② `unionPx(48) ≤ band(48)` | **真** | 真 | **否** |
| `mInputShown` | `true` | `true` | 否 |

### 1.3 两档汇总

| 档 | ①(i) | ①(ii) | ② | `readable` | 时间线盒 | **是否漂** |
|---|---|---|---|---|---|---|
| **768** | **340 ≤ 350 = −10** ✅ | `304 ≤ 302 = +2` | 假 | **92** ✅ | **140** ✅ | **全部未漂** |
| **600** | **249 ≤ 261 = −12** ✅ | `213 ≤ 213 = 0` ✅ | 真 | **51** ✅ | （单行形态） | **全部未漂** |

### 1.4 insets 原文（未改写）

```
768: InsetsSource id=3 type=ime frame=[0,342][1366,768] visibleFrame=[0,342][1366,768] visible=true
     InsetsSource id=324c0001 type=navigationBars frame=[0,720][1366,768] visible=true
     ROTATION_0={nonDecorInsets=[0,0][0,48], configInsets=[0,0][0,48], nonDecorFrame=[0,0][1366,720], configFrame=[0,0][1366,720]}
600: InsetsSource id=3 type=ime frame=[0,261][1366,600] visibleFrame=[0,261][1366,600] visible=true
     ROTATION_0={... nonDecorFrame=[0,0][1366,552], configFrame=[0,0][1366,552]}
```

**关键行**：`imeTop` 768 = **342** / 600 = **261**；`nonDecorFrame` 高 768 = 720 / 600 = 552。
⇒ 与上轮完全一致，**ime 几何未漂**。
**判 −8px 的数据仍然齐**：`readable 92`、时间线盒 `140`、`imeTop 342`、pane 底 `350`
（`imeTop 342` 与 pane 底 `350` 仍只差 **8**，与上轮同账），**−8px 本轮未复现**。

---

## 2. 缩窗往返（2 轮）

全程按 **`$appPid`** 过滤 logcat（**未使用 `$pid` 变量**），扫描 `FATAL|IllegalArgumentException|maxHeight`。

```
appPid=6933 (filter --pid; $pid NOT used)
logcat -c  (先清缓冲，保证扫到的就是本轮窗口内)

[RT1 ->600] size=Override size: 1366x600  pidof=6933  focus=com.xiufeigo.opencodeshell/.OcActivity
[RT1 ->768] size=Override size: 1366x768  pidof=6933  focus=com.xiufeigo.opencodeshell/.OcActivity
[RT2 ->600] pidof=6933  focus=com.xiufeigo.opencodeshell/.OcActivity
[RT2 ->768] size=Override size: 1366x768  pidof=6933  focus=com.xiufeigo.opencodeshell/.OcActivity
logcat 扫描（-t 300, --pid=6933）: CLEAN: zero matches for FATAL|IllegalArgumentException|maxHeight
ANR: none
```

| 轮次 | 档位切换 | `pidof` | `mCurrentFocus` 归属 | `FATAL\|IllegalArgumentException\|maxHeight` |
|---|---|---|---|---|
| 1 | 768 → 600 → 768 | **6933 全程未变** | 本 app | **空** ✅ |
| 2 | 768 → 600 → 768 | **6933 全程未变** | 本 app | **空** ✅ |

> **测试脚本自曝缺陷（我的错，不是产品缺陷）**：`logcat -d --pid=$appPid`（无条数上限）
> 在这台模拟器上**挂死**，第一次往返因此被取消、状态作废。改用 **`-t 300` 有界读**后
> 一次跑完两轮。**给下一轮**：在这台设备上扫 logcat **必须带 `-t`**，否则会挂。

---

## 3. B：队列几何（**整个会话的第一次突破**）

### 3.1 先读代码：`Queue` chip 到底需要什么前置（`ui/session/**`）

判据链（全部 `文件:行号`）：

| # | 位置 | 条件 |
|---|---|---|
| 1 | `ui/session/composer/Composer.kt:1808` | `if (!shellMode && alternateDelivery != null)` → 才渲染 `DeliveryToggleChip` |
| 2 | `ui/session/composer/Composer.kt:632-637` | `queueSupported = ComposerQueue.isQueueSupported(generation)`；不支持则 `alternateDelivery = null` |
| 3 | `ui/session/composer/Composer.kt:636` | `ComposerDelivery.alternate(running, followUpBehavior, queueEditOpen, hasContent)` |
| 4 | `ui/session/composer/ComposerQueue.kt:41-42` | `isQueueSupported = generationName?.uppercase() != "V1"`（v2 后端 ⇒ 通过） |
| 5 | `ui/session/composer/ComposerDelivery.kt:54-58` | `alternate()`：`if (editingQueued) return STEER`；**`if (!running \|\| !hasContent) return null`** |
| 6 | `ui/session/composer/ComposerQueuePanel.kt:98` | `if (rows.isEmpty()) return` —— **面板必须至少 1 条排队行** |

⇒ **结论（这正是前几轮"从未测到"的根因）**：
`Queue` chip 出现的充要条件是 **① 有一个回合正在 running 且 ② composer 此刻有内容**。
前几轮都是「发一条就等它跑完」或「发完就抓屏」，两个条件**从不同时成立**，chip 自然不出现。
**这不是回退，是取样姿势错了。**

**第二个根因（更隐蔽）**：`×` 在 a11y 树里**从来不是 `×`**：

```kotlin
// ui/session/composer/ComposerQueuePanel.kt:261-274
Box(modifier = Modifier.size(28.dp).clickable(...).semantics { contentDescription = removeLabel }) {
    Icon(painter = painterResource(R.drawable.ic_oc_close),
         contentDescription = null,          // ← 图形本身不暴露
         tint = c.iconMuted, modifier = Modifier.size(14.dp))
}
```

⇒ 交叉图标是 `Icon(contentDescription = null)`，可访问名挂在**外层 Box** 上，值是
`R.string.session_queue_remove` = **`Remove`**。
**任何按 `×`(U+00D7) 做的 grep 永远为空** —— 这与"chip 不存在"的现象完全一致，极易被误判为回退。
**建议下一轮改 grep `Queue|Steer|Remove`。**

### 3.2 姿势1：长回合运行中 + IME 打开 + composer 有内容 → **chip 出现**

```
typed-readback: [E2ECHK5554u1r followup typed during ru...]   (逐位读回)
RUNNING CONFIRMED: Stop [1308,292][1356,340]
ime=mInputShown=true
```

**chip 行全量（`v128/p1_ime.xml`，paneBottom=350）**
```
[10,292][52,340]    w=42  h=48 clk=true   desc=[Add]
[52,292][262,340]   w=210 h=48 clk=true   (Model chip 外框)
[52,302][262,330]   w=210 h=28 clk=false  desc=[Model]
[78,308][240,324]   w=162 h=16 clk=false  text=[LongCat 2.5 Preview Free]
[1247,292][1308,340] w=61 h=48 clk=true   (Queue chip 容器)   ← 本轮新测到
[1271,309][1308,324] w=37 h=15 clk=false  text=[Queue]        ← chip 标签
[1308,292][1356,340] w=48 h=48 clk=true  desc=[Send]
```

**`Queue` chip 自身几何（首次测到）**：容器 `w=61 h=48 clk=true`，
标签 TextView `w=37 h=15`（`[1271,309][1308,324]`），均在 chip 的 48dp 触摸靶内。

### 3.3 点 chip → 队列条出现 → **`Steer` / `×` 首次测到**

点 chip（`(1277,316)`，运行期从 dump 解析）后按钮翻成 `Stop`（`running=True`），
按 `Send` 以 queue 投递，再抓 `v128/m_panel0.xml`：

**队列条容器 + 行节点**
```
行容器      x1=22  y1=182 x2=1257 y2=230  w=1235 h=48 clk=true
行文本      x1=71  y1=198 x2=315  y2=214  w=244  h=16 clk=false
             text=[E2ECHK5554u1r queued followup beta]
```

**行内动作（与基线 43 / 48 / 全 clickable 对照）**

| 动作 | 节点 | w | **h** | **clickable** | 基线 | 对照 |
|---|---|---|---|---|---|---|
| **Steer** | `Button` text=`Steer` `[1263,183][1311,231]` | 48 | **48** | **`true`** | `43` | **+5**（见 §3.5） |
| **×（`Remove`）** | `Button` desc=`Remove` `[1302,182][1350,230]` | 48 | **48** | **`true`** | `48` | **逐位一致** ✅ |
| 行容器 | `View` `[22,182][1257,230]` | 1235 | 48 | `true` | — | — |

⇒ **全 clickable 达成**（`Steer` / `×` / 行容器 三个 `clickable=true`）。
**`×` h=48 与基线逐位一致。**

### 3.4 姿势3：连发 2–3 条 follow-up（第一条仍在跑时发第二条）→ 队列条多行

```
k=1 running=False chip=61x48@1278,316   submit btn=[Stop]  -> SKIPPED
k=2 running=False chip=61x48@1278,316   submit btn=[Stop]  -> SKIPPED
k=3 running=False chip=61x48@1278,316   submit btn=[Stop]  -> SKIPPED
===== PANEL @ p3_panel (paneBottom=350) =====
  Steer    x1=1263 y1=195 x2=1302 y2=227  w=39 h=32 clk=true
  Remove   x1=1302 y1=194 x2=1350 y2=226  w=48 h=32 clk=true
  text=[E2ECHK5554u1r followup number 1 queued behind] x1=71 y1=242 x2=392 y2=258 w=321 h=16
  Steer    x1=1263 y1=227 x2=1302 y2=262  w=39 h=35 clk=true
  Remove   x1=1302 y1=226 x2=1350 y2=274  w=48 h=48 clk=true
  ROW      x1=22  y1=194 x2=1257 y2=226  w=1235 h=32 clk=true
  ROW      x1=22  y1=226 x2=1257 y2=274  w=1235 h=48 clk=true
```

- **chip 在 3 次尝试中每次都在**（`61x48@1278,316`），**姿势3 同样复现 chip 存在**。
- 面板里出现 **2 条排队行**（上轮 `m_panel0` 遗留 1 条 + 本轮新排 1 条），**逐行 `Steer`/`×` 都 `clk=true`**。
- 但这几行的 **`Steer` h=32 / `35` 是被裁剪过的值，不是真实触摸靶高** ——
  原因代码里写明了（`ComposerQueuePanel.kt:92-95`）：

  > "it is a cropped one, and the actions inside the cropped row are laid out outside the
  > viewport and never reach the accessibility tree. The default keeps every other caller
  > (and the existing pins) on the upstream 148 px."

  即多行时队列条受 `maxHeight`（`QUEUE_BAR_MAX_HEIGHT`，`ComposerQueuePanel.kt:96`）裁剪，
  首行被裁 → 落在裁剪区外的动作盒**不再以完整高度进 a11y 树**。
  ⇒ **多行姿态下不能用 a11y bounds 反推触摸靶高**；单行不裁剪的 `m_panel0` 才是有效读数。
- 3 次 submit 都被跳过（按钮已是 `Stop`），因为长回合在三次尝试间已经跑完 ——
  **`running` 窗口比"填词 + dump"的操作链还短**。这解释了前几轮"两次抓屏之间队列条已经消失"。

### 3.5 姿势2：IME 关闭 → **未能取得有效读数（未闭环）**

```
running=True  ime=mInputShown=true
ime after typing:                 mInputShown=true
ime after nav-bar hide tap:       mInputShown=true   ← 导航栏 chevron 没能收起 IME
```

纪律**禁止 `BACK(4)`**、**禁止 `ime reset`**（会改用户 IME 配置），因此本轮
**没有合规手段把 IME 收起**。该次 chip 行 dump 退化（`paneBottom=0`，抓在布局切换瞬间），
**故姿势2 如实记为"未取到有效读数"**，不判 chip 不存在。
（v127 曾用合规手段取得过 `mInputShown=false` 的 `u_noime.xml`，但那轮 composer 为空、chip 仍不出现。）

### 3.6 B 的结论

> **`Queue` 测到了（不再"仍未测到"）。**
> 需要的前置条件（已在真机证实可复现）：
> **① 有一个长回合正在 `running`；② 在它跑完之前把文本填进 composer（使 `hasContent` 为真）；
> ③ 后端为 v2（`isQueueSupported` 通过，`ComposerQueue.kt:41-42`）。**
> 三者同时成立，chip 立刻出现在工具行 `[1247,292][1308,340]`（`w=61 h=48`）。
> 点 chip 把手工投递的这次 submit 切成 `queue` 语义后，**队列条与 `Steer`/`×` 即出现**。
>
> **grep 必须用 `Queue|Steer|Remove`**，`×` 不在 a11y 树里（§3.1）。

---

## 4. 队列几何 vs 基线（43 / 48 / 全 clickable）

| 动作 | 基线 | 本轮实测（**未裁剪**的 `m_panel0`） | 判定 |
|---|---|---|---|
| `Steer` h | `43` | **`48`** | **+5，偏离**（§4.1） |
| `×` h | `48` | **`48`** | ✅ **逐位一致** |
| `×` clickable | 全 clickable | **`true`** | ✅ |
| `Steer` clickable | 全 clickable | **`true`** | ✅ |
| 行容器 clickable | — | **`true`** | ✅ |
| `Queue` chip h | （此前无基线） | **`48`**（`w=61`） | **首次测到** |

### 4.1 `Steer` 48 vs 43 的解释（**如实记账，不判回退**）

源码里 `Steer` 与 `×` 的**布局原始尺寸并不相同**：

- `Steer` 是 **`Text`**（`ComposerQueuePanel.kt:241-255`）：
  `fontSize = 12.sp` + `padding(horizontal = 6.dp, vertical = 4.dp)` —— 文字内边距盒。
- `×` 是 **`Box(Modifier.size(28.dp))`**（`ComposerQueuePanel.kt:261-263`）—— 固定 28dp 方盒。

两者的 `clickable` 都由 Compose 施加 **48dp 最小触摸靶**，a11y 报出的 bounds 是**触摸靶**，
所以在**未被裁剪**的行里**两者都读 48**。基线的 `43` 属于 Steer 的**文字内边距盒**口径
（12sp 行高 + 2×4dp 内边距在另一台设备/另一密度下的换算），**不是触摸靶**。
⇒ **这是口径差，不是某档退化**：`43` 与 `48` 分别对应"文字盒"与"触摸靶"两个量。
**若 Lead 要求把 `Steer` 触摸靶做到 ≤43，需要改的是 `ComposerQueuePanel.kt:251-254` 的 padding，
而不是判定回退。** 留待裁定。

---

## 5. 收尾

| 项 | 状态 | 证据 |
|---|---|---|
| `wm` 显式重设回 `1366x768` / `160` | ✅ **已重设并复查** | `Override size: 1366x768` / `Override density: 160` |
| 自建会话清理 | ✅ **无需清理** | 本轮**未新建会话**；`E2ECHK5554u1r` 只是发进**既有** `Friendly greeting` 的消息前缀。**未对 `Friendly greeting` 做 Stop / 长按 / 删除**（纪律：只动自建会话） |
| 回合 `Stop` | ✅ | 收尾 `am force-stop com.xiufeigo.opencodeshell`（纪律允许）；`pidof` 回空 = 已停 |
| `/sdcard` 无新增残留 | ✅ **已清** | `ls /sdcard/*.png` → `No such file or directory`；`ls /sdcard/*.xml` → 同上。顶层只剩原有 `anim1-3.mp4`、`v95` 与标准目录 |
| 弹层归属核对 | — | 本轮**未触发任何长按弹层**，故无需 `dumpsys window windows` 归属验证 |

---

## 6. 未闭环（如实记账）

1. **姿势2（IME 关闭）未取到有效读数** —— 合规手段内无法收起 IME（禁 `BACK(4)`、
   禁 `ime reset`）；导航栏 chevron 无效。**不判 chip 不存在**。
2. **姿势3 的多行 `Steer` h（32/35）是裁剪值**，不是真实触摸靶高
   （`ComposerQueuePanel.kt:92-95` 明写裁剪行内的动作不进 a11y 树）。
   ⇒ **多行姿态下 `Steer`/`×` 高度需换测量口径**（或改用 `maxHeight` 不裁剪的单行姿态）。
3. **`Steer` 48 vs 基线 43 是口径差**（触摸靶 vs 文字内边距盒，见 §4.1），**待 Lead 裁定**，
   本轮**不判 FAIL、不判 PASS**，如实给两组。
4. **判据 ② 在 768 档仍口径未定义**（`band` 有 48 与 96 两种取值），
   与 `REPORT_FINAL_VERIFY.md` §3.4 同账、未关闭：若用 `BAND_HEIGHT=48` 则 768 档 ② 为假。
5. **768 档 (ii) 生产口径 `+2`** 与 `CRITERION_PARITY.md` §7.1 同账，**本轮逐位复现、无新信息**。
6. **没有新建自建会话**，故队列面板是在**既有** `Friendly greeting` 上测的
   （该会话本来就含 `E2ECHK5554u1q` 遗留行）。若需"干净队列"证据，需下一轮新建会话重跑。
7. **未测**：队列行的**编辑态**（`ComposerQueue.kt` 的一次性 edit loan）、
   **拖拽重排**、**停回合后的 parked 首行标签由 `Steer` 变 `Send`**（`ComposerQueue.kt:18-19, 237`）。

---

## 7. 证据文件清单（`research/v97_verify/5554_md/v128/`）

| 文件 | 内容 |
|---|---|
| `gate_v128.log` | md5 / bytes / lastUpdateTime / reverse / focus / wakefulness |
| `A768.log` / `A768_full.xml` | 768 档全树 + 判据计算 |
| `A600.log` / `A600_full.xml` | 600 档全树 + 判据计算 |
| `roundtrip.log` | 2 轮往返 + logcat + ANR |
| `p1.log` / `p1_ime.xml` | 姿势1：chip 出现（**含 chip bounds**） |
| `measure.log` / `m_panel0.xml` / `m_chip.png` | 点 chip → 队列条 + `Steer`/`×` 读数 |
| `p3.log` / `p3_panel.xml` / `p3_panel.png` | 姿势3：多行队列条 |
| `p2clean.log` / `p2_noime.xml` | 姿势2：IME 未收起（如实记账）+ 收尾 |
| `helpers.ps1` / `geom.ps1` / `pose2.ps1` / `pose3.ps1` / `measure.ps1` / `pose2clean.ps1` | 测试脚本 |

---

## 8. 给下一轮的三条可复用教训

1. **扫 logcat 必须带 `-t`**：这台模拟器上 `logcat -d --pid=$p`（无上限）会**挂死**，
   会把已经做完的往返状态一起作废。改 `-t 300` 后一次跑完。
2. **grep 队列动作要用 `Queue|Steer|Remove`，不能用 `×`**：
   `×` 是 `Icon(contentDescription = null)`，a11y 名在外层 Box 上、值是 `Remove`
   （`ComposerQueuePanel.kt:266, 271`）。按 `×` grep 永远为空，会被误读成"功能没了"。
3. **抓 chip 的动作链要短**：`running` 窗口比"点 chip → 填词 → dump"还短。
   有效姿势是 **先让回合跑起来，再一次性把 composer 填上，立刻 dump**；
   `longcat` 的计数任务约 7–28s，超过这个窗口 chip 就会消失。
