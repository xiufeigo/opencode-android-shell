# M5 第二阶段实施状态（把终端面板接上真正的 VT 渲染内核 —— termux A′）

> 状态：**代码已交付；`.\gradlew.bat clean :app:compileDebugKotlin :app:assembleDebug :app:testDebugUnitTest`
> 全绿（49 套件 / 364 tests / 0 failed，含本阶段新增 21 tests；clean 重跑）**，见 §4。
> 规格依据：`research/_M5_terminal_status.md` §5（A′ 步骤 1–8）、`research/06_terminal_selection.md` §1.4/§1.6/§4.2。
> 本阶段**只改两处应用侧文件**（`ui/terminal/TerminalPanel.kt` + 新增 `ui/terminal/share/TermuxPtyScreenEngine.kt`）
> 加 vendor 的两个 Java 模块；`PtyClient` / `PtyApi` / `PtyProtocol` / `PtySizeGate` **0 行改动**。

---

## 1. vendored 版本（已核验与 AAR 一致）

| 项 | 值 |
|---|---|
| 上游 | `termux/termux-app` tag **`v0.117`**，commit **`9272a757affc0dae4aa044248800f3987e06c52b`** |
| 取法 | GitHub tarball 源码全量取（非 JitPack AAR 解包），再删 `src/main/jni/`、上游 `src/test/`、上游 `build.gradle` |
| 一致性核验 | 下载真实 AAR（`terminal-view-v0.117.aar` 40 562 B / `terminal-emulator-v0.117.aar` 79 722 B）→ `javap` 与源码逐一对照：`TerminalEmulator(TerminalOutput,int,int,Integer,TerminalSessionClient)` 5 参 + `TerminalViewClient`/`TerminalSessionClient` 成员表 **完全一致** ⇒ `research/06` §1.5 A-2 的「AAR ≠ master」漂移问题在本组合中不存在（不是 master 源码 + AAR 混搭） |
| License | **Apache-2.0**；`app/src/thirdparty/termux/LICENSE.md` 原文拷贝（仓库 GPLv3-only 的例外条款）；**未** vendor `termux-shared` |
| 模块坐标 | `settings.gradle.kts` include `:terminal-emulator` / `:terminal-view`，`projectDir` 指向 `app/src/thirdparty/termux/…`（源码随 app 模块一起走，CI 的 `%TEMP%` 整仓复制也带得走） |
| 构建 | `compileSdk 35` / `minSdk 26` / Java 17；**无 `externalNativeBuild`**；manifest 删 `package`（AGP 8 用 `namespace`） |
| app 依赖 | `implementation(project(":terminal-view"))`（`terminal-view` 已 `api project(":terminal-emulator")`） |

**JNI 免疫已实测**：`app-debug.apk` 内 `lib/` 只有 `libandroidx.graphics.path.so`（Compose 自带），
**没有 `libtermux.so`** ⇒ `research/06` §1.5 A-4 的 16 KB page 债务在本形态下消失。
APK：**29 894 610 B**（第一阶段的 30 083 227 B 反而略小，因为不再有本地 shell 相关代码）。

### 源码级 diff（与上游逐文件哈希比对，可复现）

```
terminal-emulator: 11 个文件逐字节相同 /  2 个修改 / 1 个新增
  M  JNI.java                      （清空：删 System.loadLibrary + 4 个 native）
  M  TerminalSession.java          （掏空：删进程/fd/Handler 泵/3 个线程；仅作类型保留）
  A  TerminalIo.java               （新增：write/updateSize/getEmulator/reset 四成员接口）
terminal-view:     3 个文件逐字节相同 /  4 个修改 / 0 个新增
  M  TerminalView.java             （+126 行 / -14 行，含 javadoc）
  M  TerminalRenderer.java         （+getFontLineSpacingAndAscent()）
  M  TerminalViewClient.java       （仅 javadoc：session 参数在远程形态恒为 null）
  M  textselection/TextSelectionCursorController.java（1 行：COPY 不再碰 null 的 mTermSession）
```

`TerminalEmulator.java`（122 KB 解析器）/ `TerminalBuffer` / `TerminalRow` / `TextStyle` / `WcWidth` /
`KeyHandler` / `TerminalColors` 等 **逐字节未改** —— 没有对上游逻辑做任何行为分叉。

---

## 2. 补丁要点（只 6 个上行替换点 + 3 个新增入口）

| 补丁 | 内容 |
|---|---|
| `TerminalIo`（新，terminal-emulator） | `write(byte[])`、`updateSize(cols,rows,cellW,cellH)`、`getEmulator()`、`reset()`。**没有**让 `TerminalSession` 实现它：`TerminalOutput.write(byte[],int,int)` 与 `TerminalIo.write(byte[])` 会让 `writeCodePoint` 里的两参调用产生歧义（类 javadoc 已记录）。 |
| `TerminalView.attachIo(TerminalIo)`（新） | 设 `mIo`、`mEmulator = io.getEmulator()`、清 `mTermSession`、`updateSize()`、`setVerticalScrollBarEnabled(true)`、同步光标闪烁器的 emulator、`onEmulatorSet()`、`invalidate()`。 |
| `TerminalView.detachFromTerminalHost()`（新） | `AndroidView.onRelease` 调不到 `protected onDetachedFromWindow()`；**上游 `onDetachedFromWindow()` 不清 `mTerminalCursorBlinkerRunnable`**，Compose 卸载后会留下一个 `postDelayed` 无限 invalidate 已死视图。此入口先 `setTerminalCursorBlinkerState(false,false)` 再走上游清理，并把 `mIo` 置空。 |
| `TerminalView.copyTextToClipboard(String)`（新） | 选区 COPY 原走 `mTermSession.clipboardText()`，远程形态必 NPE；改为直接写系统剪贴板（OSC 52 的**回写**仍走 `TerminalOutput.clipboardText` → uplink，不受影响）。 |
| 上行 6 处 | `onKeyDown`（`ACTION_MULTIPLE`/`KEYCODE_UNKNOWN`）、`inputCodePoint`、`handleKeyCode`、`autofill` → `mIo.write(bytes)`；`updateSize()` → `mIo.updateSize(cols, rows, cellW, cellH)`。`mTermSession == null` 守卫 → `mIo == null`。 |
| 字体度量 | `TerminalRenderer.getFontLineSpacingAndAscent()` 新增，使外部能复现 `(h - lineSpacingAndAscent) / lineSpacing` 的行数公式（`research/06` §1.6 step 5）。 |

> 注：任务书 §2 写 `updateSize(cols,rows,cellW,cellH)`，而 `research/06` §4.2 草稿写三参
> `updateSize(cols,rows)`。实现取**四参**（与 `TerminalSession.updateSize(cols,rows)` 上游签名不同，
> 但保留了两个重载），因为远程 PTY 需要把「view 的像素/网格」交给 `PtySizeGate`。

---

## 3. 引擎接线

### 3.1 `TermuxPtyScreenEngine`（新，`ui/terminal/share/TermuxPtyScreenEngine.kt`，纯 JVM 可测）

```
PtyScreenEngine.onOutput(bytes) ─► HandlerSurface ─► TerminalEmulator.append(bytes,len)
PtyScreenEngine.onResize         ─► 同上            ─► TerminalEmulator.resize(clamp(cols), clamp(rows))
PtyScreenEngine.onReset          ─► TerminalEmulator.reset() + 注入 "CSI 2J CSI H"
PtyScreenEngine.answersQueries   = true   ← PtyClient 因此不再切分查询（phase-1 单字段开关按设计生效）
SocketOutput : TerminalOutput    = emulator 的**唯一**上行出口（DSR/DA/OSC52/鼠标上报）
                                 ─► uplink ─► PtyClient.sendBytes ─► PTY（text 帧）
```

1. **`answersQueries = true` 让 phase-1 的开关真正生效**：`PtyClient.dispatch()` 原样把字节交给引擎，
   `CSI 5n`/`CSI 6n`/`DA1`/`OSC 52` 全部由 `TerminalEmulator` 自己回答（phase 1 只回答无状态的 `CSI 5n`，
   `6n`/`DA` 明确不伪造）。已由 `TermuxPtyScreenEngineLinkTest` 在 MockWebServer 上端到端验证
   （服务端收到 `ESC[row;colR`）。**`PtyClient.kt` 0 行改动。**
2. **上行缓冲**：socket 未开时引擎把上行字节缓存在有界队列（64 KiB），并在
   `RemoteTerminalIo.write()` 里**先 flush 再发本次按键** —— 因为「回答」是在 `TerminalOutput` 里
   同步产生的，顺序必须与 PTY 输入流一致。超出上限即丢弃（防病态数据无界增长，已在测试固定）。
3. **`onReset` 真的清屏**：实测 vendored 源码里 `TerminalEmulator.reset()` **不清 screen buffer**
   （上游 `TerminalSession.reset()` 也只是转发），所以引擎在 reset 后注入 `CSI 2J` + `CSI H`。
   否则「`initialCursor = null` 全量回放」会在旧屏幕上叠加追加。
4. **线程模型**：`TerminalEmulator` 是单线程状态机（`append` 改的正是 `onDraw` 读的 buffer），
   因此全部经 `SurfaceRenderer` 单一 turn 队列：生产用 `HandlerSurface`（主线程，与绘制串行化），
   测试用 `DirectSurface`（无 looper 可直接干跑）。
5. **`RemoteTerminalIo`**：`write` → `PtyClient.sendBytes`；`updateSize` →（先引擎 resize 再）
   `PtyClient.resize` → `PtySizeGate`（首帧立即 + 100ms 尾防抖）→ `PUT /pty/:id {size}`；
   `getEmulator()` 即 `attachIo` 装进 View 的那个实例。

### 3.2 `TerminalPanel.kt`（唯一改动的应用侧文件）

- `AndroidView.factory`：`TerminalView(ctx, null)` → `setTerminalViewClient` → **`attachIo(runtime.io)`** →
  主题色（写进 `emulator.mColors.mCurrentColors`：ansi0–15 + fg/bg/cursor 由 `OcTheme.colors` 映射，
  216 色立方与灰阶沿用 termux 默认）→ `setTypeface`（`settings.terminalFont` 能解析就用，否则
  **回退系统 mono**，仓库目前无 JetBrains Mono TTF，只有 Android 加载不了的 woff2）→ `setTextSize(px)`。
- 尺寸：**不再由 Compose 算**。`TerminalView.updateSize()` 读自己的 `TerminalRenderer` 度量
  （`getFontWidth()` / `getFontLineSpacing()` / `getFontLineSpacingAndAscent()`）→ `mIo.updateSize(...)`
  → 引擎 `resize` + `PtyClient.resize`。外层的 Compose `Box` 只负责布局尺寸。改字号后 `update()`
  重新 `setTypeface/setTextSize` 并再 `updateSize()`（`mRenderer` 整体重建，必须重推网格）。
- 光标：`setTerminalCursorBlinkerRate(600ms)` + `setTerminalCursorBlinkerState(isLive, true)`；
  `onRelease` → `detachFromTerminalHost()`（停闪烁 + 上游 detach 清理）。
- 手势：`onScale` 做双指缩放字号（8–32sp）；`onKeyDown`/`onCodePoint` 一律返回 `false`，
  让 `TerminalView` 自带的 `handleKeyCode` + `InputConnection` 走真实转义序列。
- 删掉了第一阶段的 `TerminalPlaceholderView` / `placeholderLines`（含 `onSizeChanged` 里的
  `TerminalGeometry.cell` 近似公式）—— VT 内核已就位，占位渲染器是死代码。`TerminalGeometry`
  本身保留（纯逻辑 + 有测试，作为文档化公式）。

---

## 4. 编译 / 测试（本机实跑）

```
JAVA_HOME=C:\Users\xiufe\AppData\Local\Temp\jdk21\unzip\jdk-21.0.12.1+1

.\gradlew.bat clean :app:compileDebugKotlin :app:assembleDebug :app:testDebugUnitTest
  → BUILD SUCCESSFUL in 1m 22s
  → :app:testDebugUnitTest  49 suites / 364 tests / 0 failures / 0 errors
    （clean 后重算；基线为 343，本阶段 +21）
  → app-debug.apk 29,894,610 B，lib/ 内无 libtermux.so
```

本阶段新增 3 个测试套件（21 tests，全部 JVM，**无 Robolectric**）：

| 套件 | tests | 覆盖 |
|---|---|---|
| `TermuxPtyScreenEngineTest` | 15 | `answersQueries=true` + 网格构造；下行字节上屏（UTF-8 跨帧、`CSI` 定位不打印转义）；`CSI 5n`/`6n`/`DA1` **三者都**从 uplink 出（phase 1 只有第一个）；无 socket 时缓冲 + `io.write` 先 flush 后发键（严格顺序 `ESC[0n` + `a`）；缓冲上限 64 KiB；`io.updateSize` 双写（引擎网格 + REST 尺寸）；`<2` 尺寸夹取；重复尺寸 no-op；`io.emulator === engine.emulator`；空写丢弃；`detachUplink`/`release`；**`onReset` 真清屏**；主题调色板落到 `mColors` |
| `TermuxPtyScreenEngineLinkTest` | 3 | 真 WebSocket（MockWebServer）：`PtyClient` + 真内核的原始流直通与**内核自答 `CSI 6n`**（`ESC[\d+;\d+R`）；`io.write` → PTY 且 DSR-5 回答走 `sendBytes`；`io.updateSize` → `PUT /pty/:id {size:{cols,rows}}`（**不占 WS 帧**）。断言按「可见文本」等待而非字节数采样，连跑 3 次稳定 |
| `VendoredTermuxApiTest` | 3 | vendored API 契约守卫：5 参 ctor、`DEFAULT_TERMINAL_TRANSCRIPT_ROWS=2000`、`NUM_INDEXED_COLORS=259`；`TerminalIo` 恰好 4 个成员；`JNI` 无 native 方法（A′ 不变量） |

**他人用例**：本次 clean 全量 0 失败（M3/M4 的 `HomeSessionsTest`、`SettingsPaletteModelTest`、
`SettingsStructureTest` 等已被各自作者修好，本阶段未触碰它们的文件）。

---

## 5. V1–V5 待验（`research/06` §5）

| # | 项 | 本阶段结论 |
|---|---|---|
| **V1** | Gradle/AGP 对 JitPack 产物的变体解析 | ✅ **已绕过**：走 vendor 源码（`include(":terminal-view")`），不依赖 JitPack 元数据。真实 AGP 8.7.3 + Gradle 8.11.1 已把两个模块编成 AAR 并打进 APK（dex 内可见 `com/termux/view/TerminalView`）。原 AAR 仍下载对照过 API 面。 |
| **V2** | `AndroidView` + `TerminalView` 的 IME 真机行为（Gboard / Samsung / 中文上屏 / `TYPE_NULL`） | ⛔ **真机未验**。已按上游默认接线（`shouldEnforceCharBasedInput()=false` → `InputType.TYPE_NULL`，`IME_FLAG_NO_FULLSCREEN`），`onCreateInputConnection` 未改一行。**必须真机跑**。 |
| **V3** | 补丁后 滚动回滚 / 选区 / 光标闪烁 / alt-screen 无回归 | ⛔ **真机未验**。逻辑上未改（只有 `updateSize`/输入出口换了 provider），但 vim/htop/less 必须实跑；选区 COPY 的 NPE 已修。 |
| **V4** | JetBrains Mono 子集下的宽字符/组合字符、`setTextSize` 后行高取整 | ⚠️ 部分：宽字符渲染是上游 `WcWidth`+`TerminalRenderer` 原码（未改）；但**仓库无 JetBrains Mono TTF**，当前回退系统 mono。字号变化后的网格重算已由 `updateSize()` 重推（代码路径已验证，像素效果待真机截图）。 |
| **V5** | `0x00` 元数据帧 + `cursor` 续传语义对齐 | ✅ 逻辑层沿用 phase 1（不本地推算 cursor）；新增 `onReset` 真清屏，使「全量回放」不叠加。真机断线重连仍待验。 |

**真机未验（本机无设备/模拟器）**：V2/V3 全部、V4 视效、V5 端到端重连。本阶段**只做到编译 + JVM 逻辑/端到端（MockWebServer）绿**。

---

## 6. 关键决策与残余风险

1. **`TerminalSession` 不删，但掏空**：`TerminalSessionClient` / `TerminalViewClient` 的签名里带
   `TerminalSession`，删类会连带改上游接口（回调参数无意义化）。保留为「无 JNI 的空壳类型」成本最低，
   且 vendored 文件清单仍与上游一一对应，diff 可复核。**它不会被本仓库实例化**。
2. **`PtyClient` 0 改动**：`attachUplink`/`sendBytes`/`resize`/`answersQueries` 四个 phase-1 接缝
   原样够用 —— 这是 phase 1 设计正确的证据，本阶段只写了引擎实现。
3. **光标闪烁的上游泄漏已顺手修**：`detachFromTerminalHost()` 是新入口（不是改上游方法体），
   副作用是 `onRelease` 后 `mIo` 置空、视图不再接收输入。这是「关闭即卸载」的期望语义。
4. **`onReset` 注入 `CSI 2J`**：不是上游行为，是远程 PTY 的必需补偿（理由见 §3.1-3，已写进代码注释）。
5. **主题色映射是自定的**（`OcTheme.colors` 语义 token → ansi 0–15 + fg/bg/cursor）。规格只要求
   「主题色（OcTheme.colors）」，映射表没有上游依据；16 个 ansi 槽用的是最近的语义 token
   （`syntaxString`→绿、`syntaxKeyword`→品红 …），**真机配色需肉眼复核**。
6. **面板仍是独立 route**（phase 1 决策未变）；`TerminalPanelHost` 仍是自包含 `Column`，
   侧栏 tab 落地时原样搬。
7. **`Route.terminal(directory)` 仍未被 `ui/session/**` 传入**（会话目录拿不到）—— 与 phase 1 相同，
   属未做项，不是本阶段回归。
8. **未做（本阶段也留白）**：面板内 tab 拖拽、双击改名、buffer 序列化恢复、断连自动 clone、
   命令面板 `terminal.new` keybind、on-screen 修饰键行（Ctrl/Esc/Tab 触摸键）。

---

## 7. 文件清单

| 文件 | 动作 |
|---|---|
| `app/src/thirdparty/termux/**`（LICENSE.md、PATCHES.md、两模块 Java + build.gradle.kts + res） | **新增**（vendor） |
| `app/src/main/java/…/ui/terminal/share/TermuxPtyScreenEngine.kt` | **新增**（引擎 + `RemoteTerminalIo` + `SocketOutput` + `SurfaceRenderer`/`HandlerSurface`/`DirectSurface` + `NoopSessionClient`） |
| `app/src/main/java/…/ui/terminal/TerminalPanel.kt` | **修改**（factory 换 `TerminalView` + `attachIo`；几何交给 renderer；字体/光标/主题色；删占位渲染器） |
| `app/build.gradle.kts` | +1 行 `implementation(project(":terminal-view"))` |
| `settings.gradle.kts` | +两个 include（`projectDir` 指向 thirdparty） |
| `app/src/test/java/…/TermuxPtyScreenEngineTest.kt`、`TermuxPtyScreenEngineLinkTest.kt` | **新增** |
| `research/_M5b_vt_kernel_status.md` | 本文 |

**未改动**：`PtyClient.kt`、`PtyApi.kt`、`PtyProtocol.kt`、`PtySizeGate.kt`、`PtyScreenEngine.kt`、
`TerminalTabs.kt`、`TerminalStream.kt`、`ui/session/**`、`ChatStore`/`OpenCodeClient`/`EventStream`/
`ChatViewModel`/`ChatView`/`MainActivity`、`ui/App.kt`（0 行）。
