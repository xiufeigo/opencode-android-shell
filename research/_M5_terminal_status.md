# M5「终端外壳」第一阶段实施状态（PTY/WS 数据链路 + 终端面板 chrome）

> 状态：**代码已交付；本机 `:app:compileDebugKotlin` + 本阶段 6 个测试套件（57 tests）全绿**（见 §1 的隔离验证说明）。
> 规格依据：`PLAN-android-native-refactor.md` §4.6 / §9-a,a2,a3、`research/06_terminal_selection.md`（A′ 定案）、
> `research/04_data_api.md` §6.2 / §6.3 / §7.3、`research/02_ui_screens.md` §9.1–§9.5。
> 本阶段**不接 VT 模拟器**：数据链路 + 面板 chrome + VT 内核接口（`PtyScreenEngine` / 活动 buffer 回调）立起来，
> 渲染内核（termux A′ 补丁）是第二阶段，步骤见 §5。

---

## 1. 验证结果（本机实跑）

```
JAVA_HOME=C:\Users\xiufe\AppData\Local\Temp\jdk21\unzip\jdk-21.0.12.1+1

.\gradlew.bat :app:compileDebugKotlin   → BUILD SUCCESSFUL
.\gradlew.bat :app:assembleDebug        → BUILD SUCCESSFUL（app-debug.apk 30,083,227 B，CI 等价门禁）
.\gradlew.bat :app:testDebugUnitTest    → 343 tests / 3 failed
                                          本阶段 6 套件 **57 tests / 0 failed**：
                                            PtyApiTest 13 · PtyClientTest 8 · PtyProtocolTest 8
                                            PtySizeGateTest 8 · TerminalStreamTest 11 · TerminalTabsTest 9
                                          3 个失败全部**不属于 M5 终端**，均为并行 agent 的用例：
                                            HomeSessionsTest（M3 home 路径分隔符）
                                            SettingsPaletteModelTest、SettingsStructureTest（M4 settings/palette）
```

**关于并行改写**：M5 执行期间本工作区同时有其它里程碑 agent 在改 `ui/App.kt`、`ui/home/*`、`ui/settings/*`、
`strings.xml`，期间出现过「A 修好 B 又坏」的多轮抖动（`SettingsStructureTest.kt` 的 `headerTitleResId`/`toEntry`
未同步、`App.kt` 的 `BottomTabStrip` 参数缺省等）。为不阻塞交付，本阶段另在
`%TEMP%\oc-m5-verify`（工作区整体复制，仅移除当时正在被改写、与终端无关的 2 个 M4 测试文件）复跑过一轮：
299 tests / 1 failed（同样只有他人用例）。**被测对象（`ui/terminal/**`、`ui/terminal/share/**`、
`App.kt` 的终端接线、`strings.xml` 的 `oc_terminal_*`）与工作区一致**，最终工作区结果以上方实跑为准。

---

## 2. 文件清单

### 新增（纯 Kotlin，JVM 可测 —— `ui/terminal/share/`）
| 文件 | 内容 |
|---|---|
| `PtyProtocol.kt` | 协议常量与纯规则：`MAX_TERMINAL_SESSIONS=20`、`RESIZE_DEBOUNCE_MS=100`、`backoffDelayMs = min(250*2^min(tries,4),4000)`、`classifyBinaryFrame()`（`0x00`+`{"cursor":N}` → `Meta`，其余 binary → UTF-8 `Output`）、`parseCursor()`、`closeDisposition()`（1000=EXITED / 4404 或 `session not found`=GONE / 其它=RETRY）、v1/v2 路径与 `directory` / `location[directory]` query 名 |
| `TerminalStream.kt` | 下行流后处理：`TerminalAnswers`（`queryNeedsReply`：CSI `n`/`c`/窗口 `t`、OSC 52 查询；`replyFor`：**只**回答无状态的 `CSI 5n → ESC[0n`）、`QuerySplitter`（按整帧切分 Output/Query，**跨帧携带未完成的转义序列**，>512 字符的未终止序列按输出冲刷）、`AnsiStripper`（去转义/去控制符、`\r\n`→`\n`，同样跨帧）、`PtyOutputPreview`（有界尾缓冲，不劈开代理对）、`sanitizeForPreview()`、`AnsiScanner` |
| `PtySizeGate.kt` | `PtySizeGate`：首帧立即 + 100ms 尾防抖 + 窗口内合并 + 同尺寸去重 + `reset()`（重连后首帧再次立即）；`TerminalGeometry`：`cell()` 与 `colsRows()`（`max(4, w/fontWidth)`、`max(4,(h-fontLineSpacingAndAscent)/fontLineSpacing)`，零尺寸槽返回 `null`） |
| `PtyScreenEngine.kt` | **VT 内核接口（唯一接缝）**：`answersQueries` / `onOutput(bytes)` / `onResize(cols,rows,cellW,cellH)` / `onReset()` / `attachUplink(sink)` / `release()`；`PlaceholderPtyScreenEngine`：字节计数 + 网格 + 去转义尾巴（`assert answersQueries=false`） |
| `PtyApi.kt` | REST 面：`create`（`POST /pty {title?,command?,cwd?}`，v2 `{location,data}` 拆封）、`connectToken`（**必带** `x-opencode-ticket: 1` + 自身 Origin；403 → `Rejected`，404/405 → `Unsupported`）、`updateSize`（`PUT /pty/:id {size:{rows,cols}}`）、`exists`（**只有** 404 判 gone，5xx 继续重试）、`remove`、`socketRequest`（`directory`/`cursor`/`ticket`，无 ticket 时回落 `auth_token=base64(user:pass)` —— 与 ticket 互斥） |
| `PtyClient.kt` | WS 生命周期：ticket→升级→OPEN；下行分帧（text = 输出；binary 首字节 0 = cursor，`Meta(null)` 保留旧值）；`dispatch()`（无内核时分流查询并回答无状态项，`answersQueries=true` 时原样交给引擎）；上行 `send/sendBytes`（text 帧）；`resize()` 走 `PtySizeGate`→`PUT`；退避重连 + `exists()` 探活 + `FAILED` 上限 12 次；`close()` = 卸载（socket `cancel()` + `engine.release()` + `scheduler.shutdown()`） |
| `TerminalTabs.kt` | 纯 tab 模型：20 上限、开即聚焦、关活动 tab 优先右邻再左邻、**关最后一个返回 `false`（面板自关）**、号码不复用、`activate/rename/move/clear` |

### 新增（Compose）
| 文件 | 内容 |
|---|---|
| `ui/terminal/TerminalPanel.kt` | 面板 chrome（§9.3）：**52dp** tab 条（返回 = 关闭面板 / 每 tab 标题 + 20dp 关闭 / 右侧 `+` 新建）、`TerminalRuntime`（每终端可观察状态）、`TerminalSlot`（`AndroidView` 占位渲染槽 + `onSizeChanged` 算 cols/rows → `engine.onResize` + `client.resize`）、`TerminalPlaceholderView`（黑底 + 诊断行 + 裸流尾巴自绘）、`TerminalEntryButton`（会话页触摸入口，28dp ghost） |
| `res/drawable/ic_oc_console.xml` 复用 | 终端入口图标（**复用**仓库已有 `ic_oc_console` / `ic_oc_plus` / `ic_oc_close` / `ic_oc_chevron_down`，本阶段未新增 drawable） |

### 修改
| 文件 | 改动（最小、附加） |
|---|---|
| `ui/App.kt` | 新增 `Route.TERMINAL = "terminal?directory={directory}"` + `Route.terminal(dir?)`、`ARG_DIRECTORY`；`Route.TERMINAL` 目的地 → `TerminalPanelHost(server = serverStore?.server, …)`；SESSION 目的地包一层 `Box` 放 `TerminalEntryButton`（`top=52dp,end=8dp`，在 48dp 吸顶标题之下）；`GlobalKeybindFocus`/`showPaletteDialog`/`PaletteDialogLayer` 透传 `onOpenTerminal` |
| `ui/palette/AppCommands.kt` | `terminal.toggle` 的 action 从空改为可选回调 `onOpenTerminal`（M4 作者留的空 action；默认 `null` = 行为不变） |
| `OcActivity.kt` | 同一个 `ServerConfigStore` 既给 `ChatViewModel` 也给 `OcApp(serverStore = …)`（原先 store 在 `remember` 内局部创建） |
| `res/values/strings.xml` | +15 条 `oc_terminal_*`（`terminal.title`/`title.numbered`/`loading`/`connectionLost.*`/`connectTicket.csrfError` 取上游 en 字面量；其余为 Android 补充） |

**未改动**：`OpenCodeClient.kt`、`EventStream.kt`、`ChatStore.kt`、`ChatViewModel.kt`、`ChatView.kt`、`MainActivity.kt`、`ui/session/**`（0 行）。

---

## 3. 关键决策（本机裁决，均已落代码 + 注释）

1. **面板宿主 = 独立 route**（`Route.TERMINAL`），而非塞进会话页。上游终端是会话页的可堆叠底部面板（§9.3），
   而 M5 的另一半（review/context 侧面板）尚未落地；作为 route 宿主可以**独立验证 PTY 链路**，且 `TerminalPanelHost`
   本身已是一个自包含 `Column`，第二阶段把它原样搬进侧栏的 terminal tab 即可（已写进文件注释）。
2. **两个入口**（都在 `App.kt` 内裁决，互不重复）：
   - 键盘路径 = 命令面板 `terminal.toggle`（`Ctrl+``，上游 keybind），把 M4 留的空 action 接上；
   - 触摸路径 = 会话页右上角 28dp ghost chip。**理由**：现有 palette 只能由硬件键盘的 `Ctrl+K` 打开，
     纯触摸手机上 palette 不可达，而 M5 验收要求「能进终端面板」。
   第二阶段有了侧栏 tab 后，chip 由 tab 取代（chip 是过渡物，注释已标注）。
3. **关闭 tab ⇒ 连带删除服务端 PTY**（`close()` 卸载本端 + `DELETE /pty/:id`）。上游 `context/terminal.tsx` 的 close
   即 remove；不删会在服务端留下无人认领的 shell（v1 面还会让它在 WebView 壳里“幽灵可见”）。
4. **终端编号不复用**：关掉 Terminal 2 再新建得 Terminal 3。上游行为未取证，这里取「不误导」的一侧（已在代码注释声明）。
5. **cursor 语义严格照抄**：首连 `?cursor=-1`（新终端无历史，只要新输出）；metaFrame 的 `cursor` 是唯一权威，
   **绝不**用 Kotlin `String.length` 推算；`initialCursor = null` 才走「省略 = 全量重放」，且此时会先 `engine.onReset()`
   以免重复追加（服务端 2 MiB 回放）。
6. **回答路由是过渡态但接口是终态**：`TerminalOutput.write(byte[],int,int)`（`research/06` §1.4）才是终态唯一上行出口，
   因此接口按它设计（`attachUplink`）。当前只回答无状态的 `CSI 5n`；`CSI 6n`/`DA`/`OSC 52` 一律**不伪造**，
   交给引擎（第二阶段 termux 自己回答），并把「是否分流」做成 `answersQueries` 开关 —— 第二阶段改 1 个字段即可。
7. **占位槽画裸流尾巴**：任务只要求「黑底 + 说明」，这里多画了 `state / grid / received / cursor / pty id` 与去转义后的
   尾巴。理由：没有 VT 内核时，这是真机上**唯一**能证明「WS 通了、帧解析对了、cursor 到了」的可见证据；它明确标注
   “VT kernel not wired yet”，不冒充终端。浅色主题下面板底是白、槽是黑，这是规格本身的要求（占位黑底）。
8. **tab 高度 52dp**（v2 `!h-[52px]`），不采用 legacy `h-10`；字号 14（§9.1 `fontSize: 14`）。
9. **数据层零侵入**：只新增 `ui/terminal/share/*`；`OcActivity` 仅把同一个 store 同时交给 ViewModel 与 `OcApp`
   （`OcApp` 新增参数有默认值 `null`，预览/旧调用点不受影响）。

---

## 4. 协议实现要点（与 `research/04` §6.2 / §7.3 逐条对应）

| 规格条目 | 实现 |
|---|---|
| `WS /{api}pty/{id}/connect`，ticket 走 query | `PtyApi.socketRequest()`：v1 `/pty/:id/connect`、v2 `/api/pty/:id/connect`；URL 保留 `http(s)` scheme，由 OkHttp 完成升级（与本仓库 `NativeWebSocketHub` 同形） |
| header `x-opencode-ticket: 1` | 只加在 `POST /pty/:id/connect-token`（上游仅此一处；WS 升级不带）。另附 `Origin: scheme://host[:port]` —— ticket 校验会看 Origin，原生客户端必须自证同源；403 时上报 CSRF 文案并**回落** Basic + `auth_token` |
| `binaryType=arraybuffer` | OkHttp 天然分流：text 帧 = 裸 UTF-8 → 引擎；binary 帧按 `classifyBinaryFrame` 判（`0x00` 前缀 = `{"cursor":N}`，否则仍当 UTF-8 输出）。额外防御：NUL 开头的 **text** 帧也按元数据处理 |
| 末尾一帧 `0x00`+`{"cursor":N}` | 只解析、**不上屏**、缓存 cursor；`Meta(null)`（畸形）保留旧 cursor |
| 上行 = text/binary 直接写 PTY | `send()` 发 text；`sendBytes()` 也是 text（服务端对 binary 做 **fatal** UTF-8 解码，逐字节更稳） |
| 改尺寸走 `PUT /pty/:id {size}`，首帧立即 + 100ms 防抖 | `PtySizeGate`（纯状态机 + 注入时钟）→ `PtyApi.updateSize(id, cols, rows)`；**永不走 WS**；重连 OPEN 后 `reset()` 立即重发当前尺寸 |
| 重连退避 `min(250*2^min(tries,4),4000)` | `PtyProtocol.backoffDelayMs`，`tries` = 连续失败次数（第 1 次 = 500ms）；重连前 `GET /pty/:id` 探活，404 → `GONE` 不再重试；12 次上限 → `FAILED` |
| 关闭码语义 | `1000` → `EXITED`（不重连）；`4404` / reason 含 `not found` → `GONE`（不重连）；其它 → `RETRY` |
| `MAX_TERMINAL_SESSIONS=20` | `TerminalTabs(max=20)` + `PtyProtocol.MAX_TERMINAL_SESSIONS`；第 21 个被拒（并回滚服务端刚建的 PTY） |
| 「关闭即卸载」 | 关 tab：`client.close()`（socket `cancel()`、`engine.release()`、`scheduler.shutdown()`）+ `DELETE /pty/:id`；面板卸载：全部 client + 两个 OkHttp client 释放 |
| DSR/DA/OSC52/鼠标上报从下行剥离后回写 | `QuerySplitter` 剥离查询序列，`TerminalAnswers.replyFor` 回写无状态回答；鼠标上报属**上行**（`engine` → `attachUplink`），接口同一条路 |

---

## 5. 未做 / 下一步：VT 渲染内核（第二阶段 A′ 补丁步骤）

本阶段**刻意不接** VT 模拟器（`06` §1.5 A-1：`TerminalView` 只能 `attachSession(TerminalSession)`，
后者构造即 `JNI.createSubprocess`）。第二阶段按 A′ 落地，具体步骤：

1. **vendor 源码**：复制 `termux-app` 的 `terminal-emulator/` + `terminal-view/`（Apache-2.0 例外，**不要**带 `termux-shared`）；
   `settings.gradle.kts` `include(":terminal-emulator", ":terminal-view")`；两个模块 `namespace` 保持 `com.termux.terminal` /
   `com.termux.view`，`compileSdk=35`、`minSdk=26`、Java 17；`app` 加 `implementation(project(":terminal-view"))`。
2. **不配 `externalNativeBuild`**：远程 PTY 不需要 JNI，`TerminalSession.java` / `JNI.java` / `jni/termux.c` 整删或至少删净调用点
   ⇒ **天然免疫 16KB page 风险（§1.5 A-4）**，也不再浪费本地 shell 进程。
3. **最小补丁（20–60 行）**：新增
   `interface TerminalIo { fun write(bytes: ByteArray); fun updateSize(cols: Int, rows: Int, cellW: Int, cellH: Int); val emulator: TerminalEmulator }`；
   `TerminalView` 内所有 `mTermSession.write*/updateSize` → `mIo?.…`，并新增
   `fun attachIo(io: TerminalIo)`（设 `mIo`、`mEmulator = io.emulator`、`setVerticalScrollBarEnabled(true)`、`updateSize()`、`invalidate()`）。
   注意 A-2：**AAR(v0.117) API ≠ master**，要么只用 AAR，要么只用某个具体 commit 的源码，不可混用。
4. **接线（零改本阶段文件）**：`RemotePtyIo : TerminalIo`：`write` → `PtyClient.sendBytes`；`updateSize` → `PtyClient.resize`；
   `emulator = TerminalEmulator(SocketOutput(), cols, rows, 2000, sessionClient)`；`SocketOutput : TerminalOutput` 的 `write` → `PtyScreenEngine.attachUplink` 的同一个 sink。
5. **引擎实现**：新增 `TermuxPtyScreenEngine : PtyScreenEngine`，`answersQueries = true`、`onOutput → emulator.append(bytes, len)`、
   `onResize → emulator.resize(cols, rows)`、`onReset → emulator.reset()`；**只有 `TerminalPanel` 的 `AndroidView.factory` 与这一处引擎实现要改**
   （`factory` 换成 `TerminalView(ctx, null).apply { setTerminalViewClient(client); setTypeface(...); setTextSize(px); attachIo(remotePtyIo) }`，
   `onRelease = { it.onDetachedFromWindow() }`）。
6. **字体/光标/尺寸**：`setTypeface(Typeface.create(settings.terminalFont))` + `setTextSize(px)`，改字号后必须再 `updateSize()`；
   `TerminalGeometry.colsRows` 的输入换成 `renderer.getFontWidth()` / `getFontLineSpacing()` / `getFontLineSpacingAndAscent()`（公式与 `max(4,…)` 不变）；
   光标闪烁 `setTerminalCursorBlinkerRate`，`onResume` 恢复 / `onPause` 关闭。
7. **功能面预期管理**（§1.5 A-5）：无 OSC 8 超链接 / imgcat / Sixel ⇒ 「对齐 Ghostty」降级为**字体/配色/行列尺寸对齐**。
8. **仍待验证**（`06` §5 V1–V5）：AGP 8.7.3 对 JitPack 产物的变体解析；`AndroidView + TerminalView` 的 IME 中文上屏 / `TYPE_NULL` /
   Gboard·Samsung 兼容；vim / htop / less 的 alt-screen 与选区回归；cursor 续传语义真机对齐。
9. **未做（本阶段也刻意留白）**：面板内的 tab 拖拽排序（上游 `@dnd-kit`）、双击改名 + 右键菜单（Rename/Close）、
   buffer 序列化恢复（上游 `SerializeAddon` + `LocalPTY` 持久化）、断连自动 `clone` 新 PTY、命令面板 `terminal.new`/`file.open` keybind 录制。
10. **未尽事项**：`ui/session/*` 未动，因此会话页仍拿不到该会话的 `directory`；终端面板目前以「无 directory」打开
    （服务端用实例 cwd），`Route.terminal(directory)` 已预留参数，第二阶段由侧栏 tab / 会话 ⋮ 菜单传入真实工作区。

---

## 6. 测试清单（57 tests / 6 suites，全部 JVM）

| 套件 | 覆盖 |
|---|---|
| `PtyProtocolTest`(8) | 退避公式五个拐点 + 上限 + 负数防御；`0x00`+JSON 元数据帧（含 `Double` cursor、字符串 cursor、坏 JSON、只有 0x00）；非 `0x00` 二进制 = 输出；空帧；UTF-8 三字节字符按字节不按 UTF-16 计；关闭码四态（含 reason 覆盖 code）；v1/v2 路径与 query 名；20/100ms 常量 |
| `TerminalStreamTest`(11) | `CSI 5n → ESC[0n`；`6n/?6n/c/>c/OSC52/?t` 需要回答但**不伪造**；普通转义/私有模式不算查询；`QuerySplitter` 混合切分顺序、**跨帧 CSI 与跨三帧 OSC**、未终止序列按上限冲刷；`AnsiStripper` 去转义 + `\r\n`/孤立 `\r` + 跨帧 CR；`PtyOutputPreview` 有界尾 + **不劈代理对**；`sanitizeForPreview` 限长；占位引擎字节计数/网格/`answersQueries=false`/reset |
| `PtySizeGateTest`(8) | 首帧立即；100ms 窗口内合并（3 次 resize → 1 次 send）；早于窗口的 `onTimer` 返回 `Wait` 而非丢弃；窗口外立即；同尺寸去重；`reset()` 后再次立即；非法尺寸不占用「首帧」名额；`cell()/colsRows()` 公式、`max(4,…)` 夹取、零尺寸槽 → `null`、默认 `lineSpacingAndAscent` 推导 |
| `TerminalTabsTest`(9) | 开即聚焦 / `focus=false` 不抢焦点；重复与空 id 拒绝；20 上限；号码不复用；关活动 tab 取右邻再左邻；**关最后一个 → `false`（面板自关）**；关非活动 tab 不动 active；activate/rename（空标题回落编号）/move 边界；`clear()` 复位 |
| `PtyApiTest`(13) | `create` 的 method/path/body/解析（v1 裸对象 + v2 `{location,data}`）；无 id 抛错；`connectToken` 的 `x-opencode-ticket: 1` + Origin + Basic，404/405 → `Unsupported`、403 → `Rejected`；`updateSize` 的 PUT + `{size:{rows,cols}}`；`exists` 200/404/500 三态；`remove` DELETE；`socketRequest` 的 directory/cursor/ticket、`auth_token` 回落且与 ticket 互斥、v2 路径与 `location[directory]`、无凭据时零鉴权、Origin 去默认端口 |
| `PtyClientTest`(8) | 真 WebSocket（MockWebServer 升级）：ticket + `cursor=-1` 升级参数；text 帧 → 引擎（去转义预览 + 字节计数）+ 元数据帧 → cursor 且**不上屏**；坏元数据帧不覆写 cursor；上行 text/bytes 到 PTY；`CSI 5n` 被回写而 `CSI 6n` 不被伪造；resize 首帧立即 + 100ms 合并后恰好 2 次 PUT（`requestCount==4`）；异常关闭 → 调度 500ms 重试 + `RECONNECTING`；`1000` → `EXITED` 且不重试；`4404` → `GONE` 且不重试；`close()` → `CLOSED` + scheduler 释放 + engine 释放 + 无残留重连任务 |

---

## 7. 风险与开放项

| # | 项 | 状态 / 处置 |
|---|---|---|
| 1 | **真机未验**（本机无设备/模拟器） | IME 中文上屏、触摸滚动、真实 PTY 建连（ticket→升级→帧）只在 MockWebServer 上证过。第二阶段 POC 必须上真机（对应 `06` V2/V3） |
| 2 | **服务端对 `Origin` 的接受度** | ticket 校验会看 Origin；已按「同源 Origin + 403 回落 Basic」处理，但未对真实服务端实测（`research/04` 未给出允许清单） |
| 3 | 面板打开后 WebView 才同步 server config | `serverStore.server` 不是 observable，面板会停在 “No server configured” 提示，重进 route 即可（`PtyApi` 的 `remember` 键含 `server.url`，配置到位并重组后会自动建终端） |
| 4 | 全量重放的引擎去重 | 只有显式 `initialCursor=null` 才走全量回放并 `onReset()`；默认 `-1` 不重放 ⇒ 不会重复追加 |
| 5 | 并行 agent 冲突 | M5 执行期间 `ui/App.kt`、`strings.xml`、`ui/home/*`、`ui/settings/*` 被多 agent 同时改写，出现「A 修好 B 又坏」的抖动。本阶段改动均为**附加**（新 route / 新参数带默认值 / 追加 resource），最后需在工作区收敛后再跑一次全量门禁 |
| 6 | 占位槽与主题 | 规格要求黑底；浅色主题下面板底为白，黑槽是**有意**的占位（VT 内核接入后由 `terminalColors()` 决定） |
| 7 | `QuerySplitter` 的 512 字符上限 | 未终止序列超限即当输出冲刷（防止病态数据无界增长）；已在测试中固定该行为 |
