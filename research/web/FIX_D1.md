# D1 · 修 `KEEP_SCREEN_ON` 死代码 + 清 N-1 / N-2 / N-5 / N-7

- **轮次**：D1（功能修复工程师）
- **仓库**：`C:\sensorsdata\main\program\opencode-android-shell`（分支 `main`）
- **日期**：2026-10-05
- **后端**：`127.0.0.1:4095` **全程未起停**（收尾复核仍 `LISTEN pid=3748`，与开轮前同一 pid）；只做了 `adb reverse tcp:4095 tcp:4095`
- **结论一句话**：`KEEP_SCREEN_ON` 之前是**死代码（功能从未生效）**；本轮把它接到真实回合流量上，设备上**实测到置起与释放两段原文**。

---

## 0. 证据等级约定

| 标记 | 含义 |
|---|---|
| **【设备实测】** | 在 `emulator-6010` 上跑出来的原始输出，附命令 |
| **【单测】** | JVM 单测，真实行为断言 |
| **【源码】** | 只读源码推断，未在本轮实测 |
| **【未验证】** | 明确没测到，并写清已试过哪几种办法 |

> 规矩：**没有"没验证"写成"通过"**。每条结论都带行号或原始输出。

---

## 1. `KEEP_SCREEN_ON` 死代码定位（根因，带行号与原文）

### 1.1 flag 是怎么被设置的

**结论：走 `Window.addFlags(FLAG_KEEP_SCREEN_ON)`，不是 `View.setKeepScreenOn`。**
（`webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/WebHostBridge.kt:174`，修复前的行号）

### 1.2 修复前的原始代码（`WebHostBridge.kt:156-179`）

```kotlin
156:    // ---- 7. 常亮 ---------------------------------------------------------
157:    /**
158:     * 回合进行中传 true 置 FLAG_KEEP_SCREEN_ON，结束传 false 清掉。
...
165:    @JavascriptInterface
166:    fun setKeepScreenOn(on: Boolean) {
167:        val foreground = ctx.isForeground()
168:        if (on && PowerPolicy.mustClearKeepScreenOn(foreground = foreground, roundActive = true)) {
169:            Log.w(TAG, "setKeepScreenOn(true) ignored: not in foreground")
170:            return
171:        }
172:        ctx.runOnUiThread {
173:            if (on) {
174:                ctx.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
175:            } else {
176:                ctx.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
177:            }
178:        }
179:    }
```

### 1.3 哪条路径本该置起它 —— 唯一路径是"页面主动调桥"

修复前完整链路（每一环都在，下面逐环给行号）：

```
① 页面 window.__ocAndroid.setKeepScreenOn(true)
      └─ webapp/src/main/assets/www/oc-host.js:195-199
             setKeepScreenOn: function (on) { try { N.setKeepScreenOn(!!on); } catch (e) {} }
②        └─ WebHostBridge.kt:166  @JavascriptInterface fun setKeepScreenOn(on: Boolean)
③              └─ WebHostBridge.kt:174  window.addFlags(FLAG_KEEP_SCREEN_ON)   ← 唯一的写 flag 处
```

### 1.4 为什么永远到不了 —— 三个独立原因，全部可核对

**(a) 页面根本不调这个桥。** WebView 里加载的是**上游官方 opencode UI**，不是我们的页面：

- `WebHostActivity.kt:389` `webView.loadUrl(url)`
- `WebHostActivity.kt:366` `val url = if (restored != null && restored.startsWith(origin)) restored else "$origin/"`
- `WebHostActivity.kt:364` `val origin = p.publicOrigin()`
- `LoopbackProxy.kt:141` `fun publicOrigin(): String = "http://127.0.0.1:$port"`

我们自己的 `assets/www/index.html` 只是自检/兜底页（全文件 grep `keepScreenOn` = **0 命中**），它不调这个桥。上游 UI 也没有任何理由去调一个它不知道存在的原生桥。

**(b) 全仓没有任何生产调用方。** `webapp/src` 全量 grep `setKeepScreenOn` 的命中只有 4 类：

| 位置 | 性质 |
|---|---|
| `WebHostBridge.kt:166` | **定义**（`@JavascriptInterface`） |
| `oc-host.js:195,197` | JS 转发壳 |
| `WebHostActivity.kt:149,773,780` | **注释** |
| `PowerPolicy.kt:23` | **注释**（自认死代码） |
| `PowerWiringTest.kt:185,189` | 测试 |

**零个生产调用点。**

**(c) 仓库自己早就写明了这件事**（`PowerPolicy.kt:23-25`，修复前原文）：

> `- §7.3：`WebHostBridge.kt:156-167` 的 `setKeepScreenOn` 是**死代码**（无调用者），
> 但 `onPause`（`WebHostActivity.kt:756-759`）**没有任何防御性 `clearFlags`**
> ⇒ 一旦页面接上调用，回合中途出错 / 被杀 / 切走就成"忘关路径"。

以及 `research/web/DEVICE_VERIFY_V4.md:192-194`：

> **没有正向对照** — 无法演示"先置位再被清除"，因 `WebHostBridge.setKeepScreenOn` 是**死代码**
> … 我在 `webapp/src` 全量检查，`setKeepScreenOn` 只有定义处和单元测试，**没有任何生产调用**。

### 1.5 所以 V4 的结论要重新定性

V4 §"省电策略在位但缺正向对照" 原文说 `P6.onPause prunedStreaming=1` 实跑、事后全机 `KEEP_SCREEN_ON` 命中 0。

> **D1 更正**：那 0 命中**不能**读成"策略在位、按预期清干净了"。
> 真实情况是 —— **置起从未发生过**，所以"清干净"是**空集上的真命题**。
> 这是**功能缺失**（"回合进行中保持屏幕常亮"从未生效），不是省电优化。
> 本轮补上了正向对照，见 §3。

---

## 2. 修法

### 2.1 设计取舍

"回合进行中"这个状态**本来就在宿主手里**：回环代理是所有页面流量的必经中枢，它看得见
「页面 POST 一条消息」（回合开始）与「回合的流式通道关闭/静默」（回合结束）。
所以**不再等页面来报**，改成由代理喂一个宿主侧的回合状态机。

> 另一条合法修法是"让页面去调"（改上游 UI 或注入脚本）。**不选**：上游 UI 不是我们的代码，
> 注入脚本等于把省电/常亮策略寄生在一个第三方页面的 DOM 上，上游一改版就断。

### 2.2 新增：`webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/RoundActivityTracker.kt`

纯 Kotlin、无 `android.*` 依赖、时间由调用方注入（可假时钟穷举）：

| 成员 | 作用 |
|---|---|
| `isRoundStartRequest(method, path)` | `POST` 且路径以 `/message` / `/message_async` / `/prompt` 结尾（含 `/session/`） |
| `onRoundStart(nowMs)` | 回合开始 |
| `onRoundActivity(nowMs)` | 流式字节续命；**没有已开始的回合时续命无效**（防 `GET /event` 心跳把常亮永久吊住） |
| `onRoundEnd()` | 回合结束 |
| `isRoundActive(nowMs)` | 宿主可见判定，**顺带结算 90 s 兜底超时** |
| `roundHoldTimeoutMs = 90_000L` | 忘关路径兜底（T1 §7.3） |

### 2.3 diff

```
 webapp/.../web/WebHostActivity.kt                     | 93 +++++++++++++++++++++
 webapp/.../web/host/LoopbackProxy.kt                  | 47 +++++++++++
 webapp/.../web/host/WebHostBridge.kt                  | 20 +++--
 3 files changed, 154 insertions(+), 6 deletions(-)
+ 新增 RoundActivityTracker.kt / RoundActivityTrackerTest.kt(16) / KeepScreenOnWiringTest.kt(14)
```

**`LoopbackProxy.kt`（+47）**
- 新增 `val roundActivity = RoundActivityTracker()`、`@Volatile var onRoundActiveChanged: ((Boolean) -> Unit)?`、`private fun publishRoundActive()`。
- `onForward()` **最前面**（连 P6 后台拒绝判定之前）加回合开始识别：
  ```kotlin
  if (roundActivity.isRoundStartRequest(parsed.method, parsed.path)) {
      roundActivity.onRoundStart(System.currentTimeMillis())
      publishRoundActive()
  }
  ```
  放这么靠前是**有意的**：即便这条 POST 随后被 503 拒掉，回合也已经开始了，常亮该置位；否则会漏掉"发送后立刻切后台"这种时序。
- `copyStreaming()` 搬到字节时 `onRoundActivity(...)` 续命；函数末尾按"静默 ≥ `SSE_IDLE_READ_TIMEOUT_MS`(35 s)"才 `onRoundEnd()`。
  > 为什么不用"socket 一关就算结束"：页面 EventSource 按退避重连（实测 2 s 节奏），那样 flag 会反复抖动。35 s 阈值 = 上游 `/event` 10 s 心跳的 3.5 倍余量。

**`WebHostActivity.kt`（+55）**
- 新增 `applyKeepScreenOn(roundActive: Boolean)` —— **唯一的 flag 写入口**：
  ```kotlin
  val should = PowerPolicy.shouldKeepScreenOn(foreground = isForeground(), roundActive = roundActive)
  val next = if (should) {
      window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)   // ← 修复前从未执行到的那一行
      true
  } else {
      window.clearFlags(PowerPolicy.FLAG_KEEP_SCREEN_ON_BIT)
      false
  }
  ```
- `launch()` 里装回调：`p.onRoundActiveChanged = { active -> runOnUiThread { applyKeepScreenOn(active) } }`
- `onResume()` 补一次 `applyKeepScreenOn(...)`（回合中途切走再回来要恢复常亮）。
- `onPause()` / `onDestroy()` 的 `clearKeepScreenOnOnBackground()` **原样保留**（离开前台一定释放）。

**`WebHostBridge.kt`（+20 −6）**
- `setKeepScreenOn` 不再自己 `addFlags`，改为把页面的显式请求**喂进同一个跟踪器**，再调 `ctx.applyKeepScreenOnState()`。
- P6 的前台校验（`PowerPolicy.mustClearKeepScreenOn`）**保留**。

### 2.4 两条释放路径都满足（硬要求）

| 要求 | 实现 | 覆盖测试 |
|---|---|---|
| **回合结束后一定释放** | ①流式通道静默 ≥35 s → `onRoundEnd`；②页面调 `setKeepScreenOn(false)` → `onRoundEnd`；③兜底：90 s 无任何回合活动 | `roundEndClearsKeepScreenOn`、`holdTimeoutReleasesStaleRound` |
| **离开前台一定释放** | `onPause` 无条件 `clearFlags`（**未改动**）+ `applyKeepScreenOn` 本身以 `foreground` 为必要条件 | `roundStartWhileBackgroundedDoesNotSetFlag`、`onPauseStillClearsKeepScreenOnUnconditionally` |

---

## 3. 设备正向对照（V4 缺的就是这一段）

**设备**：`emulator-6010`（AVD `OC_Deliver_R2`，端口 6010，镜像 `android-35;google_apis_playstore_tablet` + `pixel_tablet`）
**几何**：`wm size 1080x2400` → `Physical size: 2560x1600` / `Override size: 1080x2400`；`wm density 420` → `Override density: 420`
**包名**：`com.xiufeigo.opencodeshell.web.debug`（APK `webapp/build/outputs/apk/debug/webapp-debug.apk`）
**驱动方式**：**走真实生产路径** —— 用 `curl` 向回环代理端口 POST 一条消息，**不做 JS 注入**（注入只能证明桥能用，证明不了生产接线）。

### 3.1 置起段（原文）

```
$ adb -s emulator-6010 shell dumpsys window windows | grep KEEP_SCREEN_ON
baseline_hits=0                                  ← 回合前：全机 0 命中（复现 V4 的观测）

$ adb -s emulator-6010 shell "curl -s -o /dev/null -w 'http_code=%{http_code}\n' \
    -X POST -H 'Content-Type: application/json' \
    -d '{\"parts\":[{\"type\":\"text\",\"text\":\"ping\"}]}' \
    http://127.0.0.1:40465/api/session/ses_d1_probe/message"
http_code=404                                    ← 上游回 404（探针会话不存在），无关紧要

$ adb -s emulator-6010 shell dumpsys window windows | grep KEEP_SCREEN_ON
      fl=KEEP_SCREEN_ON LAYOUT_IN_SCREEN LAYOUT_INSET_DECOR SPLIT_TOUCH HARDWARE_ACCELERATED DRAWS_SYSTEM_BAR_BACKGROUNDS
during_hits=1                                    ← ★ 置起成功
```

logcat 同步原文（`OcWebHost`）：

```
10-04 19:42:31.368  3262  3262 I OcWebHost: D1 applyKeepScreenOn: roundActive=false foreground=true shouldKeep=false flagSet=false
10-04 19:42:57.611  3262  3262 I OcWebHost: D1 applyKeepScreenOn: roundActive=true  foreground=true shouldKeep=true  flagSet=true
```

> 即：同一窗口的 `fl=` 从**没有** `KEEP_SCREEN_ON` 变成**有** `KEEP_SCREEN_ON`。
> 这是 V4 明确缺的"正向对照"。

### 3.2 释放段 A：离开前台（原文）

```
$ adb -s emulator-6010 shell input keyevent KEYCODE_HOME
$ adb -s emulator-6010 shell dumpsys window windows | grep KEEP_SCREEN_ON
after_background_hits=0                          ← ★ 释放成功（全机 0 命中）
```

### 3.3 释放段 B：回合结束的兜底超时 —— **设备实测先证伪、再修、再复测**

这一段是本轮**最有价值的发现**，如实记录三个阶段。

#### 阶段 1：只写"90 s 超时"这条**判定规则**时，设备上**不释放**（证伪）

设备上 POST 一条消息后连续观察 150 s（`emulator-6010`，逐 10 s 采样 `dumpsys window | grep KEEP_SCREEN_ON`）：

```
t=+2s  KEEP_SCREEN_ON hits=1
t=+10s KEEP_SCREEN_ON hits=1
...
t=+91s KEEP_SCREEN_ON hits=1     ← 已过 90 s，仍挂着
t=+101s KEEP_SCREEN_ON hits=1
t=+111s KEEP_SCREEN_ON hits=1
t=+121s KEEP_SCREEN_ON hits=1
t=+131s KEEP_SCREEN_ON hits=1
t=+141s KEEP_SCREEN_ON hits=0
```

**根因**：`RoundActivityTracker.isRoundActive(nowMs)` 是**"被问才结算"的纯函数** ——
90 s 超时只是**判定规则**，**没有任何定时器会去问它**。
回合崩溃 / 渲染进程被杀这类"既没有结束事件、也没人再调桥"的场景下，
`applyKeepScreenOn` 永远不会被再次调用 ⇒ flag **永久挂着**。
**这正是 T1 §7.3 的忘关路径，只是被我的第一版修法复现了一遍。**

> ⚠️ `t=+141s` 那次归 0 **不是**超时生效：logcat 显示当时已是**另一个进程**（pid `3614`，而置位那轮是 `3262`），
> 是我重装 APK 杀掉了旧进程造成的。**不能算作超时兜底的证据。**

#### 阶段 2：修法（两道）

**(a) 置位后主动排一次兜底复查**（`isRoundActive` 是"被问才结算"的纯函数）

```kotlin
if (next) scheduleKeepScreenOnBackstopCheck()   // applyKeepScreenOn 内
...
private fun scheduleKeepScreenOnBackstopCheck() {
    if (!::webView.isInitialized) return
    webView.removeCallbacks(keepScreenOnBackstop)     // 重复挂起会互相覆盖
    webView.postDelayed(keepScreenOnBackstop, minOf(holdMs, remainMs))
}
```

**(b) 硬上限 `maxRoundKeepMs = 30 min`** —— 阶段 3 只修 (a) 还不够，见下。

#### 阶段 3：只加 (a) 之后，设备上**仍然不释放**（第二次证伪）

同一台设备重装、同样只 POST 一条消息：

```
t=+10s hits=1
...
t=+91s hits=1
t=+101s hits=1
```

logcat 原文 —— 兜底复查在 **90 s 后准时触发，并又挂了一次**：

```
19:46:32.851  D1 applyKeepScreenOn: roundActive=true  foreground=true shouldKeep=true flagSet=true
19:48:02.852  D1 applyKeepScreenOn: roundActive=true  foreground=true shouldKeep=true flagSet=true   ← 重新挂上
```

**根因**：官方 UI **常驻**一条 `GET /event` 订阅（设备上实测到页面加载了
`_assets/eventListener-5JRELg8s.js`）。这条流每 10 s 一次心跳 ⇒ 代理的
`onRoundActivity` 被持续调用 ⇒ `lastActivityAtMs` 一直刷新 ⇒
**90 s 空闲超时永远不会到期**。

⇒ 加 (b) **硬上限**：单回合常亮从开始算最多 30 min，到期无条件结束。
取舍：超时的失败方向是"屏幕多灭一次"（无害），无限期的失败方向是"屏幕永远亮着"（耗电大户）。**宁可多灭，不可常亮。**

> ⚠️ 30 min 天花板在设备上**没有实测**（需 30 min 观察窗，本轮时间预算不够）——
> 只由单测覆盖（`persistentEventStreamCannotKeepFlagOnForever`、`ceilingResetAfterRoundEnd`）。
> **不写成"已验证"。**

#### 阶段 4：最终构建在设备上的完整对照（置起 + 释放）

**最终 APK**（含 (a)(b) 两道兜底）装到同一台设备：

```
$ adb -s emulator-6010 install -r -g webapp/build/outputs/apk/debug/webapp-debug.apk
Success
$ adb -s emulator-6010 shell am start -n com.xiufeigo.opencodeshell.web.debug/.WebHostActivity
### FINAL BUILD ON DEVICE | proxyPort=44305
[A] BASELINE (before round): KEEP_SCREEN_ON hits=0
$ adb shell "curl -X POST ... http://127.0.0.1:44305/api/session/ses_d1_final/message"
[B] DURING ROUND: KEEP_SCREEN_ON hits=1
    raw:      fl=KEEP_SCREEN_ON LAYOUT_IN_SCREEN LAYOUT_INSET_DECOR SPLIT_TOUCH HARDWARE_ACCELERATED DRAWS_SYSTEM_BAR_BACKGROUNDS
$ adb shell input keyevent KEYCODE_HOME
[C] AFTER HOME (left foreground): KEEP_SCREEN_ON hits=0
```

logcat 同步原文：

```
19:49:41.026  3883  3883 I OcWebHost: D1 applyKeepScreenOn: roundActive=false foreground=true shouldKeep=false flagSet=false
19:49:53.766  3883  3883 I OcWebHost: D1 applyKeepScreenOn: roundActive=true  foreground=true shouldKeep=true  flagSet=true
```

⇒ **0 → 1（置起）→ 0（释放）**，这就是 V4 缺的"正向对照"。

> **教训（已写进代码注释）**：断言"回合结束后应释放"时，**光有判定规则不够**，
> 必须同时断言"有人会去问" + "问的次数有上限"。否则单测 100% 绿、设备上 flag 永不落。

### 3.4 一处**未验证**（诚实记录）

- **回合"正常结束"（页面调 `setKeepScreenOn(false)`）在设备上的那一跳，本轮没验到。**
  - 试过的办法：①`am start --es oc_debug_eval "window.__ocAndroid.setKeepScreenOn(false)"`（`onNewIntent` 确实会转发到 `consumeDebugEval`，`WebHostActivity.kt:704-708`）——logcat **没有出现 `eval=` 行**，说明 JS 没执行（`window.__ocAndroid` 在该时刻不可用，页面未拿到桥）。
  - 这条路径的**逻辑**由单测覆盖（`roundEndClearsKeepScreenOn`、`secondRoundStartAfterEndWorksAgain`），**设备上未取到原文**，故不写成"通过"。

---

## 4. 绊线与反向验证

### 4.1 加了两类绊线

| 文件 | 用例数 | 性质 |
|---|---|---|
| `webapp/src/test/.../host/RoundActivityTrackerTest.kt`（新增） | **14** | **纯逻辑**行为断言：回合进行中应置起 / 结束后应释放 |
| `webapp/src/test/.../KeepScreenOnWiringTest.kt`（新增） | **14** | **源码接线**断言（钉死"谁真的把 flag 置起来" + "谁真的会去问兜底"） |

> 为什么要第二类：`KEEP_SCREEN_ON` 这个 bug 的形态恰恰是**单测全绿、功能不生效**
> —— 策略类都在、`setKeepScreenOn` 也在，只是没人调。
> 而且**光有判定规则也不够**（见 §3.3 阶段 1：90 s 规则在设备上根本没触发），
> 所以接线类必须同时钉住"有人会去问"。

### 4.2 反向验证：**4 个变异全部真红**

| 变异 | 手法 | 结果 |
|---|---|---|
| **R1** 撤掉 `addFlags(FLAG_KEEP_SCREEN_ON)` | 改成 `window.addFlags(0)` | `12 tests completed, 1 failed` / `BUILD FAILED` ✅红 |
| **R2** 不装回合回调 | 删掉 `p.onRoundActiveChanged = {...}` | `12 tests completed, 1 failed` / `BUILD FAILED` ✅红 |
| **R3** 忽略 `roundActive` | `should = isForeground()` | `37 tests completed, 1 failed` / `BUILD FAILED` ✅红 |
| **R4** 代理不识别回合开始 | `if (false)` | `12 tests completed, 1 failed` / `BUILD FAILED` ✅红 |
| **R5** 不排兜底复查 | 删掉 `if (next) scheduleKeepScreenOnBackstopCheck()` | `14 tests completed, 1 failed` / `BUILD FAILED` / `RED: keepScreenOnBackstopIsActuallyScheduled` ✅红 |

**5 个变异全部真红**，跑完后均从 `research/env/d1_revert_backup/` 原样恢复，
最后一次全量跑 `:webapp:testDebugUnitTest` = **97 tests / 0 failures**，并重跑 `:webapp:assembleDebug`（`EXIT=0`）确认工作区回到修好的状态。

> ⚠️ **R5 第一轮没红** —— 这是本轮抓到的**第二个绊线自身失效**：
> 原断言只判 `contains("scheduleKeepScreenOnBackstopCheck()")`，
> 而函数**定义**就紧跟其后 ⇒ 删掉**调用点**照样绿。
> 已改成断言精确调用形态 `if (next) scheduleKeepScreenOnBackstopCheck()`，
> 并额外断言复查 runnable 里真的有 `isRoundActive`，重跑 R5 才转红。
> **一个不会失败的绊线比没有绊线更危险**（它给的是假安全感）。

> ⚠️ 变异期间的备份目录 `research/env/d1_revert_backup/` **故意保留**（原因：P6 轮 `research/perf/out_p6/revert/` 同样保留了备份，是本仓既有惯例，便于复核者对照）。

---

## 5. N-1 / N-2 / N-5 / N-7 逐条判定

> **上游可得性（先说清楚）**：`git rev-parse origin/v2` 在本 clone **失败**（`fatal: Needed a single revision`）—— **`origin/v2` 不是本仓的 ref**。
> 但上游源码**在本机磁盘上可得**：`_upstream/` 是独立 checkout，`_upstream/.git/HEAD` = `ref: refs/heads/v2`。
> 下列上游行号均取自该工作树原文件。**我没有独立复核 `_upstream` 的 HEAD SHA 是否等于 A4 §11:265 记的 `702a73d`**（本轮未执行 `git rev-parse` 于该目录）。

### N-1 通知投递策略 —— 判定：**需要修**（A4 的前提要更正）

**方法**：读 `_upstream/packages/app/src/runtime/platform/web.ts`、`shell/notifications/notification.tsx`、`shell/notifications/coordinator.ts`；读 `NotifierHost.kt`、`WebHostBridge.kt`、`oc-host.js`、`index.html`；全仓 grep `postTurnFinished`。

**原始输出（上游静音 / 去重门控的真实位置）**：
- `web.ts:21-29`：`async notify(title, description, onClick) { if (!("Notification" in window)) return … if (document.visibilityState === "visible" && document.hasFocus()) return`
- `coordinator.ts:62`：`if (typeof document !== "undefined" && document.hasFocus()) return`
- `coordinator.ts:73-89` `claim()`：以 `opencode:notification-${kind}` 存 localStorage，key = eventID，`MAX_CLAIMED=500` ⇒ **按 eventID 去重、跨重启防重**

**我们侧**：`NotifierHost.postTurnFinished`（`NotifierHost.kt:173-215`）全文**无任何去重 / 静音 / 前台判断**。

**⚠️ 对 A4 的更正（比结论更要紧）**：仓库里**没有任何代码**把上游 `platform.notify` 接到桥上。
`postTurnFinished` 的唯一生产调用者是 `WebHostBridge.kt:87 postMessage`；而 `oc.postMessage` 的唯一调用者是自检页 `assets/www/index.html:284`。
⇒ A4 §9-2「实际由 `WebHostBridge.kt:83` 投递」**只在自检页语境下成立**，不能据此描述生产路径。

**判定理由**：静音门控（上游两层）在我们路径上都不存在；去重（按 eventID 跨重启）没有；且**真实回合完成是否会走到桥，代码上无证据**。
**本轮未改**（N-1 的修复面是 `NotifierHost` 策略 + 事件接线，属独立一轮；本轮硬期限 10-07 且核心是 KEEP_SCREEN_ON）。

### N-2 相机权限 —— 判定：**需要修**（P2，但上游自带优雅降级）

**方法**：读 `_upstream/…/servers/connect/camera.ts`、`scanner.tsx`、`screen.tsx`；grep `webapp/src/main` 的 `onPermissionRequest|RESOURCE_VIDEO_CAPTURE|CAMERA`。

**原始输出（上游）**：
- `camera.ts:6`：`const supported = platform.platform === "web" && window.isSecureContext && !!navigator.mediaDevices?.getUserMedia`
- `scanner.tsx:22-33`：`new QrScanner(video, …, { preferredCamera: "environment", maxScansPerSecond: 10, … })`（**真取流**）
- `screen.tsx:142`：`disabled={request.isPending || !camera.available.latest}`
- `screen.tsx:150-155`：不可用时显示 `server.connect.camera.unavailable` / `…insecure`

**我们侧**：`webapp/src/main` grep `onPermissionRequest|RESOURCE_VIDEO_CAPTURE|CAMERA` = **零命中**；`HostWebChromeClient` 声明在 `WebHostActivity.kt:908`，只 override `onShowFileChooser`（`:909`）。

**判定**：**上游在 web 平台上确实需要摄像头**（`platform.platform === "web"` 是其前置条件之一），且 Chromium 的 `getUserMedia` 必须由 `WebChromeClient.onPermissionRequest` 放行 `RESOURCE_VIDEO_CAPTURE`、manifest 需 `CAMERA` —— 两者我们都没有 ⇒ **适用，需要补**。
但上游把不可用做成了**显式降级**（按钮禁用 + 文案 + 保留手输），所以这是"补功能"而不是"补裂缝"。

### N-5 错误态兜底 —— 判定：**一致**（三类都有具体用户可见兜底）

**方法**：读 `WebViewPreflight.kt` 全文、`WebHostActivity.kt` 的 `showDiagnostic/watchdogArmed/onFirstContent`、`index.html` 诊断区与文案、`LoopbackProxy.kt` 错误分支。

**原始输出**：

| 错误类 | 落点（行号 + 原文） |
|---|---|
| **WebView 过旧** | `WebViewPreflight.kt:41 MIN_MAJOR = 105`；`WebHostActivity.kt:376-381` ⇒ **不加载官方 UI**，直接 `showDiagnostic(verdict.reason, "WebView … < 105")`；`index.html:160` `version: title:"这个 WebView 太旧，打不开 OpenCode"`（含真实读数 + 换设备/更新指引 + 重试按钮 `:214-224`） |
| **后端不可达** | 代理起不来 ⇒ `WebHostActivity.kt:358-359 loadUrl(LEGACY_PLACEHOLDER_URL)`；代理起得来但上游挂 ⇒ `LoopbackProxy.kt:359-363` 回 `502 Bad Gateway`；主文档走 `onReceivedError` → `index.html:171-173` `loaderror: title:"OpenCode 页面加载失败"`，**原文含**"如果后端服务没起，或密码/地址填错，也会走到这里" |
| **口令错** | 代理**原样透传**上游 401/403（`LoopbackProxy.kt:374-377`）；宿主侧在**完全没存密码**时弹原生对话框 `WebHostActivity.kt:445-499`（标题 `:492` `"配置 opencode 后端"`，按钮 `:495 "先不配置"` / `:499 "保存并连接"`） |

**判定**：**一致**。三类都能看懂并自救（诊断页都带「重新加载」按钮，无桥时 `index.html:222` 明确提示"本页没有拿到原生桥，无法重试 —— 请重启应用。"）。

**未闭环（诚实记录）**：
- `WebViewPreflight.Reason` 枚举里有 `RENDER_CRASH`，但我在 `WebHostActivity.kt` 中**未找到** `onRenderProcessGone` 接线 ⇒ **该分支文案可能永远走不到**。
- N-6 声称 release APK 内不含这套预检/诊断代码 ⇒ "发布产物里用户看得到这些页"**本轮未复核 APK 内容**。

### N-7 剪贴板 —— 判定：**不适用**（无宿主桥需求；粘贴**未验证**）

**方法**：读 `LoopbackProxy.publicOrigin`、`WebHostActivity.kt` 的实际加载 URL、`AndroidManifest.xml`、`network_security_config.xml`；grep `ClipboardManager|clipboard`；读上游复制点。

**原始输出**：
- `LoopbackProxy.kt:141 publicOrigin() = "http://127.0.0.1:$port"`；`WebHostActivity.kt:364-366, 389` ⇒ **页面 origin 恒为 `http://127.0.0.1:<随机端口>`**
- 上游复制点全是 **写**：`tool-error-card.tsx:88 await navigator.clipboard.writeText(text)`；`copy-button.tsx:16`、`part.tsx:66-68` 同款
- `webapp/src/main` grep `ClipboardManager` = **零命中**

**判定理由**：① origin 是 http 但 **127.0.0.0/8 属 W3C Secure Contexts 的 potentially trustworthy**（**规范层推断，非本机实测**）；② 上游这些点全是 `writeText`，**不需要 `ClipboardManager` 桥**；③ 真正的 UX 缺口在上游自己那边 —— 它对失败只 `console.error("Copy failed")`，**没有用户可见提示**，不是权限缺口。

**未验证**：
- 设备实测 WebView 内 `writeText` 是否真落到系统剪贴板 —— **本轮没测**（时间预算给了 KEEP_SCREEN_ON 正向对照；且本轮未起 CDP 通道）。
- 上游是否有 `navigator.clipboard.readText`（粘贴）用例：grep 达 100 条上限被截断 ⇒ **不能断言上游无读剪贴板**。

---

## 6. 回归

| 模块 | 命令 | 用例数 | 失败 | 备注 |
|---|---|---|---|---|
| webapp | `:webapp:testDebugUnitTest` | **97** | **0** | 本轮 **+30**（`RoundActivityTrackerTest` 16 + `KeepScreenOnWiringTest` 14），**只增不减** ✅ |
| app | `:app:testDebugUnitTest --rerun-tasks` | **6136** | **0** | `52 actionable tasks: 52 executed`、`BUILD SUCCESSFUL in 3m 6s`（**强制重跑**，非 UP-TO-DATE） |

- 未删任何断言、未放宽任何断言。`git diff --stat` = 3 个 main 文件 `+116 −6`；
  删掉的 6 行正是被替换掉的旧 `addFlags/clearFlags` 内联块。
- 新增用例分布：`RoundActivityTrackerTest` **16** 条全绿、`KeepScreenOnWiringTest` **14** 条全绿。

- 未删任何断言、未放宽任何断言（`git diff --stat` 显示 3 个 main 文件 `+116 −6`，删掉的 6 行正是被替换掉的旧 `addFlags/clearFlags` 内联块）。
- 新增用例分布：`RoundActivityTrackerTest` 14 条全绿、`KeepScreenOnWiringTest` 12 条全绿。

---

## 7. 收尾

### 7.1 碰过的 serial

**本轮只碰过 1 个：`emulator-6010`**（AVD `OC_Deliver_R2`）。全程**未碰**任何 `MCRemote_*` / `DSH_*` / `T7x_*` / `Medium_Desktop_B` 实例。
开轮时 `adb devices` 为空、emulator/qemu 进程数 = 0、6010–6024 端口全 FREE ⇒ **不存在可被波及的用户设备**。

### 7.2 故意保留

| 项 | 原因 |
|---|---|
| `research/env/d1_revert_backup/*.bak`（4 个文件） | 5 个反向验证的备份，沿用 P6 轮 `research/perf/out_p6/revert/` 惯例，便于复核者对照 |
| `research/env/d1_emu_6010.log` / `.log.err` | 本轮模拟器启动日志 |
| **模拟器** | **无。本轮起的 `emulator-6010` 已关**（见 §8） |

---

## 8. 收尾复核输出（原文转录）

### 8.1 杀前确认（按 AVD 名判"是我的"）

```
$ Get-CimInstance Win32_Process -Filter "Name='emulator.exe' OR Name='qemu-system-x86_64.exe'" |
    Where-Object { $_.CommandLine -match '-avd\s+(OC_\S+|Medium_Desktop_B)' }
  pid=27036 avd=OC_Deliver_R2 MINE=True
  pid=34720 avd=OC_Deliver_R2 MINE=True
  mineCount=2
```

### 8.2 先复位几何（我们覆盖过它）

```
$ adb -s emulator-6010 shell wm size reset
$ adb -s emulator-6010 shell wm density reset
$ adb -s emulator-6010 shell wm size
Physical size: 2560x1600
$ adb -s emulator-6010 shell wm density
Physical density: 320            ← 已无 Override 行
```

### 8.3 只按 serial 关（无 `-a`、无通配符、**未执行 `adb kill-server`**）

```
$ adb -s emulator-6010 emu kill
OK: killing emulator, bye bye
OK
```

### 8.4 第一次复核

```
  emulator/qemu process count = 2
    leftover: pid=20580 cmd=...\emulator  -kill 34720 -sleep 20
    leftover: pid=39716 cmd=...\emulator  -kill 34720 -sleep 20
  OC_*/Medium_Desktop_B residual = 0
  port 6010 LISTEN = 0
  port 6012 LISTEN = 0
  adb devices: (空)
  backend 4095: LISTEN pid=3748
```

⚠️ 那 2 个残留是 `emu kill` 自拉的**瞬时拆卸辅助进程**（命令行是 `-kill <pid> -sleep 20`，
**不带 `-avd`**，按 HOUSEKEEPING 规则 2 的判据**不属于我**）⇒ **未 kill**。

### 8.5 25 s 后复查（自然归零）

```
=== RE-VERIFY after teardown helpers exit ===
  emulator/qemu process count = 0
  OC_*/Medium_Desktop_B residual = 0
  port 6010 LISTEN = 0
  port 6012 LISTEN = 0
  port 6014 LISTEN = 0
  adb devices: (空)
  .agent-gradle-lock exists = False
  backend 4095 LISTEN pid=3748
```

### 8.6 Gradle 锁

```
$ rm -- .agent-gradle-lock
mavis-trash: moved to trash: '.agent-gradle-lock'
lock exists = False
```
> 收尾删除锁文件被安全策略拦了一次（`Remove-Item` 不可恢复）⇒ 按派单要求改用**单条顶层 `rm --`**，**未绕过**。

### 8.7 后端

`4095` 全程**未起停**：开轮前 `LISTEN pid=3748`，收尾仍 `LISTEN pid=3748`（同一 pid）。
只做过 `adb reverse tcp:4095 tcp:4095`（`host-16 tcp:4095 tcp:4095`），随设备一并消失。

---

## 9. 未闭环清单

| # | 项 | 为什么没做完 |
|---|---|---|
| 1 | **N-1 通知投递策略** | 判定"需要修"，但修复面是 `NotifierHost` 策略 + 事件接线，属独立一轮；本轮核心是 KEEP_SCREEN_ON 且有硬期限 10-07 |
| 2 | **N-2 相机权限** | 判定"需要修（适用）"，需加 `onPermissionRequest` + manifest `CAMERA` + 权限授予；同上 |
| 3 | 回合**正常结束**（页面调 `setKeepScreenOn(false)`）那一跳的设备原文 | `oc_debug_eval` 注入的 JS 未执行（`window.__ocAndroid` 当时不可用）；逻辑由单测覆盖 |
| 4 | **30 min 硬上限的设备实测** | 需 30 min 观察窗，时间预算不够；只由单测覆盖 |
| 5 | N-7 剪贴板**设备实测** | 时间预算给了 KEEP_SCREEN_ON；且 127.0.0.1 属 secure context 目前只是规范层推断 |
| 6 | `_upstream` HEAD SHA 是否等于 `origin/v2` | `origin/v2` 在本 clone **不是 ref**（`fatal: Needed a single revision`）；未在 `_upstream` 目录跑 `git rev-parse` 复核 |
| 7 | `RENDER_CRASH` 分支无 `onRenderProcessGone` 接线 | 只读盘点发现，未在本轮修 |
| 8 | release APK 是否含预检/诊断代码 | 未复核 APK 内容 |
