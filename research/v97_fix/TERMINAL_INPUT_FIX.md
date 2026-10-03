# D8 — persistent session terminal 输入未送达 PTY · 修复报告

- 角色：D8 修复工程师
- 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
- 缺陷来源：`research/v97_verify/5556_v98/REPORT_SWEEPC2.md` §项目 4（`c2_049/050/052/054/055/057/059*`，pid 22644）
- 状态：**已修 + 已钉 + 已回归（273 用例 0 失败）**，真机复验待测试员执行

---

## 1. 定性：断在哪一环

### 1.1 先排除的两个方向（都不是断点）

任务给的三个怀疑方向，逐个用代码证据排除：

| 怀疑方向 | 结论 | 证据 |
|---|---|---|
| 终端视图没拿到焦点 / 没向终端要键盘（v73 同类） | **不是断点** | persistent 路径的 ask 齐全：`TerminalPanel.kt:518` `focusRequest.ask(info.id)` 在 `attachSessionRuntime` 之前；`SessionTerminalsPanel.kt:111` 的 `Effect.Attach` → `onAttach` → `openSessionTerminal` 链路完整；`TerminalSlot` 由同一个 `TerminalPanelHost` 渲染，两条路径**共用**同一个 `focusRequest`（`TerminalPanel.kt:796`）和同一个 `AndroidView` factory（`TerminalPanel.kt:1815-1818` 消费 ask） |
| PTY 会话与 `PtyClient`/`TerminalStateStore` 绑定对象不是同一个 | **不是断点** | 两条路径的 runtime 都注册进同一个 `runtimes` map（`TerminalPanel.kt:434` / `:507`），都由 `key(runtime.id)` 包裹同一个 `TerminalSlot` 渲染（`TerminalPanel.kt:788-799`），`runtime.io` 在建 runtime 时即赋值并由 factory `attachIo`（`TerminalPanel.kt:1781`） |
| 输入事件被外层 composer / 面板吃掉 | **不是断点** | 桌面停靠临时终端与 persistent session terminal 是**同一个 panel host 的两个 tab**，共享同一个 `TerminalView` / `TerminalIo` / focus 通道。吃 composer 的话两条路径会一起坏，实测只坏一条 |

**焦点/绑定两条路完全对称，唯一不对称的是 up-link。** 这就是断点。

### 1.2 真正的差异：两条路径的输入投递出口

| 环节 | 桌面停靠临时终端 | persistent session terminal |
|---|---|---|
| 客户端 | `PtyClient` | `PersistentPtyClient`（session 模式） |
| 屏幕恢复来源 | `snapshot.checkpointBytes()` | `readSessionTerminal(session)` 屏幕文本 + 光标 |
| **恢复时建立 up-link 前置条件** | 不适用（无 gate） | **本该建立，但漏了** |
| `sendBytes` 是否有 gate | **无** | **有**：`restored` + `lastSize` |
| 结论 | 输入直接出 socket ✅ | 输入可能永远排队 ❌ |

**临时终端侧（PASS 的那条路）** —— `PtyClient.kt:212-215`：

```kotlin
override fun sendBytes(bytes: ByteArray) {
    if (bytes.isEmpty()) return
    send(String(bytes, Charsets.UTF_8))   // 无 restored / lastSize gate，直接发
}
```

**persistent session terminal 侧** —— `PersistentPtyClient.kt:153-166`（修复前）：

```kotlin
override fun sendBytes(bytes: ByteArray) {
    if (bytes.isEmpty()) return
    if (closed.get() || currentState != PtyClient.State.OPEN) return
    val size = lastSize
    if (!restored || size == null) {
        enqueueInput(bytes)   // ← 没有任何 frame 可构造时就只是排队
        return
    }
    ...
}
```

而 `flushPendingInput()`（`PersistentPtyClient.kt:457-474`）**第一行就是 `if (!restored) return`**，第二行 `val size = lastSize ?: return`。所以只要 `restored`/`lastSize` 没到位，队列既发不出也排不空。

### 1.3 根因：session 分支提前 return，两个前置条件一个都没建立

`PersistentPtyClient.restoreScreen()` 里两条分支的**不对称**就是根因。

**bare-persistent 分支（正常）** —— `PersistentPtyClient.kt:300-310`：

```kotlin
val snapshot = api.persistentPtySnapshot(ptyId)
engine.onReset()
if (snapshot.checkpoint.isNotEmpty()) { ... }
if (snapshot.info.cols > 0 && snapshot.info.rows > 0) {
    engine.onResize(snapshot.info.cols, snapshot.info.rows, 0f, 0f)
    lastSize = PtySizeGate.Size(snapshot.info.cols, snapshot.info.rows)   // ← 前置条件在这里建立
}
return snapshot.info.outputTail
```

**session 分支（断的）** —— 修复前 `PersistentPtyClient.kt:270-283`：

```kotlin
private fun restoreScreen(): Long {
    val session = sessionID
    if (session != null) {
        val read = runCatching { api.readSessionTerminal(session) }.getOrNull()
        if (read != null) {
            engine.onReset()
            engine.onOutput(SessionTerminalRestore.screenBytes(read.text, read.cursorX, read.cursorY))
            // The read carries the screen but no output offset — the info
            // does (`output.tail` is the reconnect cursor).
            return api.persistentPtyGet(ptyId).outputTail   // ← 提前 return
        }
    }
    ...
}
```

session 分支只做了**下行**（画屏幕 + 续流游标），**既没有 `engine.onResize`、也没有 `lastSize`**，而且它返回得比 bare 分支早，所以 bare 分支里那两行建立 up-link 前置条件的代码**根本执行不到**。

### 1.4 因果链（对上 D8 截图）

1. 建 persistent session terminal → `openSessionTerminal`（`TerminalPanel.kt:512-520`）→ `attachSessionRuntime`（`TerminalPanel.kt:452-509`，`sessionID != null`）→ `PersistentPtyClient` 走 **session 分支**。
2. `restoreScreen()` 走 session 分支：REST 读回**整屏**屏幕文本（`last login` 那行就是它画上去的）→ 提前 return，**`lastSize` 未建立、`restored` 仍为 `false`**。
3. socket 打开、开始续流 —— 屏幕正常、块状光标正常（`onOutput` 直接喂 engine，不经过任何 gate）。**下行完美，故障不可见。**
4. 用户敲 `echo E2ECHK5556c2`：`TerminalView` → `TerminalIo.write` → `sendBytes`。此时 `lastSize == null`（除非 view 恰好先跑过一次 `updateSize()`）**或** `restored == false` → 走 `enqueueInput`。
5. 恢复它需要 `restored = true`，而 `restored` 全局**只由一个地方设置**：`PersistentPtyClient.kt:313` 的 `ReplayComplete` 消息。session 模式的连接是**在 `output.tail`（流的尖端）续上的，前面没有可重放的历史**（`restoreScreen` 的注释 `terminal-pane.tsx:175-188`），所以该连接上是否、以及何时到达 `replay_complete`，客户端没有任何本地保证。
6. 一旦这个 session 连接上没有 `replay_complete`，`restored` 永远为 `false` → `flushPendingInput` 永远第一行就 return → **队列永不出队，键盘永远到不了 PTY**，而且**不报错、不重连、状态灯一切正常**。
7. 结果：终端正文永远停在 `last login` + 块状光标，`input text` 连打 3 轮零回显 —— 与 D8 报告完全一致（`c2_052/054/055/059*.png`）。

> 关键点：报告里「终端体出现块状光标并显示 `last login` 行」**恰恰是 session 分支 REST 画屏的产物**，它证明下行通了，**不证明** socket 侧重放已完成。所以屏幕好看恰恰是此 bug 的伪装色。

---

## 2. 改动

### 2.1 改动文件

| 文件 | 性质 |
|---|---|
| `app/src/main/java/com/xiufeigo/opencodeshell/ui/terminal/share/PersistentPtyClient.kt` | 主修复（`restoreScreen()` session 分支） |
| `app/src/test/java/com/xiufeigo/opencodeshell/TerminalInputDeliveryV97Test.kt` | 新增 JVM pin（4 用例） |

未触碰 `_upstream/`、`app/build.gradle.kts`、`ui/home/**`、`ui/nav/**`、`ui/App.kt`、`ui/servers/**`、`ui/settings/**`、`ui/session/header/**`、`ui/session/review/**`。
未新增任何用户可见字符串（改动只在 `ui/terminal/share/**` + 新测试文件）。

### 2.2 diff（`PersistentPtyClient.kt`，`restoreScreen()` session 分支）

```diff
 private fun restoreScreen(): Long {
     val session = sessionID
     if (session != null) {
         val read = runCatching { api.readSessionTerminal(session) }.getOrNull()
         if (read != null) {
             engine.onReset()
             engine.onOutput(SessionTerminalRestore.screenBytes(read.text, read.cursorX, read.cursorY))
             // The read carries the screen but no output offset — the info
             // does (`output.tail` is the reconnect cursor).
-            return api.persistentPtyGet(ptyId).outputTail
+            val tail = api.persistentPtyGet(ptyId).outputTail
+            // D8: a session terminal's screen is already **whole** before the
+            // socket opens — the read above painted it and the stream resumes
+            // at the tip — so the two up-link preconditions that the bare
+            // persistent branch gets from the snapshot branch (`lastSize` and
+            // `restored`) have to be established here too. Without them every
+            // keystroke takes the `sendBytes` enqueue branch and never leaves:
+            // no frame can be built without `{cols,rows}`, and a session
+            // terminal that never sees `replay_complete` would keep the gate
+            // shut for good — the D8 symptom, where the down-link looks perfect
+            // (block cursor, `last login`) and no key ever reaches the PTY.
+            // Both are facts about the very same REST read, so they are set
+            // where the down-link restore is set.
+            if (read.cols > 0 && read.rows > 0) {
+                engine.onResize(read.cols, read.rows, 0f, 0f)
+                lastSize = PtySizeGate.Size(read.cols, read.rows)
+            }
+            restored = true
+            return tail
         }
     }
```

现落盘位置：`PersistentPtyClient.kt:270-299`（新增块 `:279`、`:280-296`）。

### 2.3 为什么这样修（可解释性）

- **不是**去掉 gate，而是**把 session 路径缺的输入端前置条件补齐**，让它和 bare-persistent 路径对称。gate 本身保留。
- `restored = true` 在 session 模式下是**语义正确**的：这一条连接的屏幕在 socket 打开**之前**就已由 REST read 画完、流从 `output.tail`（尖端）续接，**没有 socket 重放可等**。而 `input_protocol=1` 的上行 frame 只需要 `{cols,rows}`，不需要等重放。
- `lastSize` 取自**同一次 read 自带的 `screen.cols/rows`**（`V2ApiSessionTerminal.kt:173-174`），不是新发请求 —— **零额外 HTTP**，四条请求序列不变（`terminal/read` → info → ticket → connect，由 pin 钉住）。
- 若 daemon 之后仍发 `replay_complete`，`PersistentPtyClient.kt:313` 会再置一次 `restored = true`，幂等无副作用。
- 重连时 `failAndRetry`（`:395`）清 `restored`，下次 `connectNow` → `restoreScreen` 再置回，状态自洽。

### 2.4 为什么不算降强度

| 项 | 判定 |
|---|---|
| 是否放宽了既有断言 | **否**。`PersistentPtyClientTest` 全部 8 个用例未改一行，0 失败 |
| 是否削弱了 bare-persistent 的重放边界 | **否**。`restored = true` 只写在 `sessionID != null` 的分支内，bare 路径代码路径完全未变；`PersistentPtyClientTest.inputBeforeTheReplayIsQueuedAndFlushedAfterwards` 仍在断言「`replay_complete` 前不得有任何帧上线路」，并继续通过 |
| 是否新增了反向 pin | **是**。`theSnapshotPathStillQueuesInputUntilReplayComplete` 在新文件里**重新独立钉住**这条边界，防止日后有人把 `restored = true` 挪到 session 分支之外 |
| 是否改动 PASS 的桌面停靠路径 | **否**。临时终端走 `PtyClient`，本次一行未改 |
| 是否新增用户可见字符串 | **否** |

---

## 3. 补的 JVM pin

`app/src/test/java/com/xiufeigo/opencodeshell/TerminalInputDeliveryV97Test.kt` —— 纯 JVM，无 Compose / 无 Robolectric，直接把 `PersistentPtyClient` 打到 `MockWebServer` 的真 socket 上断言**线上字节**。

| # | 用例 | 钉住什么 |
|---|---|---|
| 1 | `sessionTerminalReachesTheWireWithNoReplayCompleteAndNoResize` | **D8 正钉**：session 终端在「已 OPEN、已画屏、**没有** `replay_complete`、**没有**任何 `resize()`」状态下 `send("echo")`，线上必须出现 `[1, 0, 80, 0, 24] + "echo"` 的 type-1 帧。修复前该 `poll` 返回 null，测试会以 `D8: session terminal input never left the client` 失败 |
| 2 | `sessionTerminalSeedsTheGridFromTheReadAndALaterResizeStillWins` | 帧头用 read 上报的 80×24（≠ engine 构造初值），且 view 侧真实 resize（100×30）仍能接管 —— 既是「补了 grid」也是「没把 grid 钉死」 |
| 3 | `theSnapshotPathStillQueuesInputUntilReplayComplete` | **不降强度**：bare-persistent 在 `replay_complete` 前必须一帧不发，之后先 type-0 再 type-1 |
| 4 | `bothPathsOpenTheSameConnectRoute` | 两条路径共用同一条 connect 路由，且 `input_protocol=1`、`attachment_id` 原样带上；REST 四步序列不变（无新增请求） |

---

## 4. 测试证据

命令（`$env:JAVA_HOME` 已设为 Android Studio jbr）：

```
.\gradlew.bat --console=plain :app:testDebugUnitTest `
  --tests "com.xiufeigo.opencodeshell.TerminalInputDeliveryV97Test" `
  --tests "com.xiufeigo.opencodeshell.PtyClientTest" `
  --tests "com.xiufeigo.opencodeshell.PersistentPtyClientTest" `
  ... (共 33 个 --tests)
```

BUILD 原文（末次运行）：

```
> Task :app:compileDebugUnitTestKotlin
> Task :app:compileDebugUnitTestJavaWithJavac NO-SOURCE
> Task :app:processDebugUnitTestJavaRes UP-TO-DATE
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 6s
52 actionable tasks: 2 executed, 50 up-to-date
GRADLE_EXIT=0
```

结果（`app/build/test-results/testDebugUnitTest/`）：

```
CLASSES=33 TESTS=273 FAILURES=0 SKIPPED=0
TerminalInputDeliveryV97Test: tests=4 failures=0 errors=0
  - theSnapshotPathStillQueuesInputUntilReplayComplete
  - sessionTerminalSeedsTheGridFromTheReadAndALaterResizeStillWins
  - sessionTerminalInputReachesTheWireWithNoReplayCompleteAndNoResize
  - bothPathsOpenTheSameConnectRoute
```

**273 用例 / 33 类 / 0 失败 / 0 跳过。**

### 4.1 pin 可证伪性验证（不是假 pin）

为确认新 pin 真的咬得住（而不是恒过），临时把 `restored = true` 一行摘掉、其余保持不变，单跑新类：

```
TerminalInputDeliveryV97Test > sessionTerminalInputReachesTheWireWithNoReplayCompleteAndNoResize FAILED
    java.lang.AssertionError at TerminalInputDeliveryV97Test.kt:191
    （即 "D8: session terminal input never left the client"）

4 tests completed, 2 failed
BUILD FAILED in 15s
```

- 用例 1 失败在**我写的显式断言**上：socket 上 poll 5s 拿不到任何帧 → 精确复现 D8「输入从未离开客户端」。
- 用例 2（grid）随之 NPE，同样因无帧。
- **对照组用例 3（`theSnapshotPath…`）仍然通过** —— 证明它确实与本次改动解耦，是有效护栏而非陪跑。
- 恢复该行后重跑 33 类，回到 `BUILD SUCCESSFUL` / 273 / 0 失败（终态，见上）。

### 4.2 纪律执行

覆盖的 33 类（grep `TerminalPanel|PtyClient|TerminalTabs|PersistentPtyClient|TerminalSlot|TerminalStateStore` 找齐的引用方 + 强制的 `StringsLocaleParityTest`）：
`TerminalInputDeliveryV97Test`(新) · `PtyClientTest` · `PersistentPtyClientTest` · `TerminalTabsTest` · `TerminalFocusRoutingV73Test` · `InputConnectionV74Test` · `TerminalSlotRebindTest` · `TerminalPtyCreateTest` · `TerminalInputLosslessTest` · `TerminalFontAndSelectionTest` · `TerminalNumberPersistenceTest` · `TerminalNewTabVisibilityTest` · `TerminalRenamePersistTest` · `TerminalTabStripV40Test` · `TerminalTabStripV46Test` · `TerminalTabStripV47Test` · `TerminalTabStripV49Test` · `TerminalTabDragClaimTest` · `LocalTerminalRestoreTest` · `TermuxPtyScreenEngineTest` · `TermuxPtyScreenEngineLinkTest` · `AppTerminalRouteCaptionTest` · `MobileTerminalSessionIdFixTest` · `SessionTabStripEdgeFadeTest` · `TabRenameFieldSelectionTest` · `TabDragWiringTest` · `TabReorderTest` · `OcKeyboardOptionsTest` · `ChordCommandWiringTest` · `Wave23aSettingsPaletteTerminalTest` · `SettingsStructureTest` · `DropdownMenuDarkSurfaceTest` · `StringsLocaleParityTest`

纪律执行：只跑 `:app:testDebugUnitTest --tests <精确类名>`（含 1 次可证伪性验证的单类运行），未 assembleDebug / 未 clean / 未全量测试 / 未装机；gradle 前台同步跑完；`.agent-gradle-lock` 取锁后跑完立即释放（终态已确认 `LOCK_RELEASED: True`）。

---

## 5. 真机复验步骤（给测试员）

前置：装本次构建，进到某个会话，打开会话终端面板（桌面停靠）。

**① 对照组 —— 桌面停靠临时终端（必须仍 PASS）**
1. 面板里用 `+` 新建一个**临时**终端。
2. `adb shell input text "echo"`，`adb shell input keyevent 62`（空格），`adb shell input text "V97OK"`，`adb shell input keyevent 66`（回车）。
3. **断言：终端正文出现 `echo V97OK` 及回显。**（回归护栏 —— 这条路走 `PtyClient`，本次未改）

**② 主用例 —— persistent session terminal（修复目标）**
1. 面板右上角 `>_` / `New session terminal` 新建一个会话终端（会多出一个 `Terminal N` tab）。
2. 同样的 `input text` × 4 步：`echo` → 空格(62) → `V97OK` → 回车(66)。
3. **断言：终端正文出现 `echo V97OK` 及回显。**（修复前：连续 3 轮零回显）
4. 建议连打 **3 轮**（与 D8 报告同强度），确认不是偶发。

**③ 切 tab 后再输入**
1. 点回 ① 的临时终端 tab，切到 ② 的 session terminal tab。
2. 再 `input text "echo"` + 空格 + `V97OKD` + 回车。
3. **断言：session terminal 仍有回显**；再切回临时终端输入一次，**断言临时终端仍有回显**（确认两条路的 `runtime.io` 没串）。

**④（可选，建议做）持久性**
1. 杀进程重进同一会话，重开终端面板，session terminal 应能从 `readSessionTerminal` 恢复屏幕**并且可以输入**。
2. 断言：屏幕内容还在 + 新输入有回显。

辅助取证：`adb shell dumpsys input_method | grep -i served`，session terminal 获得焦点时应 served `TerminalView`（v73 遗留护栏，回归 ① 用的就是这条）。

---

## 6. 未闭环 / 风险

1. **未在真机验证。** 本次只跑了 JVM 单测；D8 的第 5 节复验步骤需测试员在 5556 上执行后回填。修复的因果链是代码级可证的（gate 无本地保证地依赖单一服务端消息），但**「该 session 连接确实没收到 `replay_complete`」这一步是推断，不是抓包实证** —— 报告里的屏幕证据只能证明下行通、不能证明上行 gate 状态。若复验 ② 仍不回显，下一步应抓 `handlers/persistent-pty.ts` 侧该连接是否发 `replay_complete`。
2. **未触碰 `PtyClient`。** 若复验发现**桌面停靠临时终端**也偶发不回显，那是 `PtyClient`/IME 侧的独立问题（v73/v74 家族），不在本次改动范围。
3. **`resized` 消息路径未动。** `PersistentPtyClient.kt:322` 的 `Resized` 只重画 checkpoint，不更新 `lastSize`；本次未改，因为 view 侧 resize 会覆盖，且属既有行为。
4. **工作区有并行改动。** `ui/terminal/**` 下另有他人未提交改动（`git diff --stat` 显示 `TerminalPanel.kt` 等 7 个文件均有大量改动，`PersistentPtyClient.kt` 目前是 untracked 新文件）。本次只改了该文件的 `restoreScreen()`，未触碰其余。合并时请以 `PersistentPtyClient.kt:270-299` 为准核对。
