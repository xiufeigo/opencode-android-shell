# v37 tA 丢键：`cd /tmp` → `d /tmp` 的根因、修复与复测

日期 2026-09-27。现象来自 `research/v37_verify/tA/`（Pixel_10_Pro_Fold，原生 Compose 终端，
`ui/terminal/TerminalPanel.kt` 的 docked pane，plain PTY tab「终端 1」）：

| 输入 | 屏幕/`bash` 看到 |
|---|---|
| `cd /tmp` | `d /tmp` → `bash: d: command not found`（两次） |
| `KEYCODE_C`×2 + `XYZ` | `cXYZ`（少一个 `c`） |
| 先发空格再 `cd /tmp` | 成功，**空格本身被吞** |

## 根因（两层，都是真 bug）

### 1. 框架的 touch mode 会吃掉「接触终端后的第一个键」

`ViewRootImpl.EarlyPostImeInputStage.processKeyEvent` 在把键交给视图树之前先跑
`checkForLeavingTouchModeAndConsume(event)`（源码：`android-36.1` SDK
`android/view/ViewRootImpl.java:9283`）：

* 窗口在 touch mode（**任何** `ACTION_DOWN`/`ACTION_SCROLL` 都会进入，
  `ViewRootImpl.java:8004-8008`）且事件无修饰键时：
  * 导航键（`isNavigationKey`：**space**、tab、enter、dpad、home/end/page）→
    `return ensureTouchMode(false)` = **事件被消费**，一个字都不进视图树；
  * 可打印键（`isTypingKey`：`getUnicodeChar() > 0`）→ `ensureTouchMode(false)` 后
    `return false`，但 `ensureTouchMode(false)` → `leaveTouchMode()`
    （`ViewRootImpl.java:7415`）在「当前焦点是一个 `FOCUS_AFTER_DESCENDANTS` 的 ViewGroup」
    时会执行 `mView.restoreDefaultFocus()` —— **焦点在派发这个键之前被移走**，键于是交给
    新的焦点视图，终端拿不到。

两条都只影响「接触终端之后的第一个键」，之后 touch mode 已关，一切正常 —— 与真机观察逐条吻合
（丢一个键；先发空格则空格被吞、后面的字符全对）。

触发前提是「窗口在 touch mode 且焦点不在终端（或不在任何 View）」。Compose 互操作（`AndroidView`
→ `AndroidViewHolder`/`AndroidComposeView` 都是 ViewGroup）让这个前提很容易出现：`TerminalView`
原先只在 `onSingleTapUp` 里 `requestFocus()`，**没有离开 touch mode**；而 `onSingleTapUp` 要等双击
判定超时（≈300 ms）才触发，所以「点一下终端紧接着打键」正好落在窗口期里。人类慢慢打也照样中招，
只是合成按键（`adb shell input keyevent` / `input text`，测试脚本 tap→type 循环）更容易稳定复现。

### 2. App 上行链路会静默丢帧（同一路径上的第二个洞）

* `PtyClient.send`：`socket == null` 或状态非 `OPEN` 时直接 `return`；`ws.send()` 的返回值被忽略
  （OkHttp 在已入队 close 帧后返回 `false`）。
* `PersistentPtyClient.sendBytes`：`!restored` 只入队；`lastSize == null` 时**直接丢**，
  `flushPendingInput()` 更是「先清空队列再判断 size」，size 未知时整队蒸发。

## 修复

| 文件 | 改动 |
|---|---|
| `app/src/thirdparty/termux/terminal-view/.../TerminalView.java` | `onUp()`：手指抬起且**终端已持有焦点**时 `requestFocusFromTouch()`（= 离开 touch mode + 保焦）；`onSingleTapUp()`：`requestFocus()` → `requestFocusFromTouch()`；新增 `onKeyMultiple()` 处理 `ACTION_MULTIPLE`/`KEYCODE_UNKNOWN` 字符突发（原先写在 `onKeyDown` 里是死代码，字符被静默丢弃）；删除该死分支 |
| `ui/terminal/share/PtyClient.kt` | 新增有界 `pendingInput` 队列：非 `OPEN`/`ws.send` 返回 `false` 时入队，`onOpen` 与每次 `send` 前按序 flush；`close()` 清空；`queuedInputBytes` 供诊断/测试 |
| `ui/terminal/share/PersistentPtyClient.kt` | 同样的队列语义：`!restored \|\| lastSize == null` 入队而非丢弃；`flushPendingInput()` 不再先清空后丢弃，缺 size/replay 时保持队列，`resize()` 拿到 size 后补发；`close()` 清空 |

## 测试

`app/src/test/java/com/xiufeigo/opencodeshell/TerminalInputLosslessTest.kt`（新，10 例）：

* 行为（MockWebServer + 手动 scheduler，驱动真 `PtyClient`）：socket 未开时的键会入队并在 `onOpen`
  后送达；队列保序且不被后来的键插队；队列有上限（`PENDING_INPUT_LIMIT`）；`close()` 清空队列。
* 源码钉（JVM 无法实例化 Android View 的部分）：`TerminalView` 的 `onUp`/`onSingleTapUp` 都调用
  `requestFocusFromTouch()`（`onUp` 带 `hasFocus()` 守卫，防拖拽抢键盘）；`ACTION_MULTIPLE` 走
  `onKeyMultiple` 且写入 `mIo`；`onKeyDown` 不再有 `ACTION_MULTIPLE` 分支；`inputCodePoint` 对可打印
  键无条件 `mIo.write`；Compose slot 的 `attachIo` 链与 `writeBytes = client.sendBytes`；
  两个 client 的「入队 + 检查 send 结果」形态。

已跑（精确 `--tests`，全绿）：
`TerminalInputLosslessTest`(10) `PtyClientTest`(11) `PersistentPtyClientTest`(8) `TerminalStreamTest`(11)
`TerminalNewTabVisibilityTest`(7) `TabDragWiringTest`(4) `TerminalLayoutTest`(16) = 67；
`SessionTabFocusSendGateTest` `PtyApiTest` `PtyProtocolTest` `PtySizeGateTest` `PersistentPtyProtocolTest`
`V2ApiPersistentPtyTest` `V2ApiPtyShellTest` `TerminalPtyCreateTest` `TerminalSlotRebindTest`
`LocalTerminalRestoreTest` `TerminalRevivePolicyTest` `SessionTerminalRestoreTest`
`SessionTerminalCreateOutcomeTest` `TerminalNumberPersistenceTest` `TerminalTabsTest`
`ShellCreateVisibilityTest` `ShellPanelUpdatesTest` `MobileTerminalSessionIdFixTest`
`TermuxPtyScreenEngineTest` `TermuxPtyScreenEngineLinkTest` = 143，全绿。

## 真机复测（建议）

1. 装新 APK，开终端；`input keyevent KEYCODE_SPACE` 之外**不要**先发任何“垫键”。
2. 先点一下终端（确保聚焦），**紧接着**（≤300 ms）`input text 'cd%s/tmp'` → 应执行 `cd /tmp`，
   不出现 `d /tmp`。
3. 发 `KEYCODE_C KEYCODE_C KEYCODE_X KEYCODE_Y KEYCODE_Z` → 行上应为 `ccXYZ`。
4. 关键回归：先发一个空格再打字 —— 空格仍应被输入（旧行为的“空格被吞”也一并消失）。
5. 反向检查（不能回归）：点 composer 输入框打字 → 字符只进 composer，终端不抢键盘；
   终端内上下拖动滚屏不应把键盘从 composer 抢走。

## 残留风险

* 「终端**没有**焦点时点它 + 300 ms 内按键」这一窗口仍在（`onSingleTapUp` 的触发时机由双击判定
  决定，`onUp` 只在已持焦时生效）。要彻底关掉需要 `ACTION_UP` 就抢焦点，但那会让「在 composer 里
  打字时滚动终端」抢走键盘，故未做。
* 若丢键其实发生在服务端（daemon 的 `input_protocol` 帧处理），本次改动只能保证 app 侧不丢：
  真机复测若仍复现，下一步应抓 `adb logcat` + 服务端 pty 日志，确认第 1 个 text frame 是否发出。
