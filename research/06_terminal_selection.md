# 06. Android 原生终端选型（Compose + opencode PTY/WebSocket）

> 面向 `PLAN-android-native-refactor.md` §4.6（**最高风险项**）。
> 协议依据：`research/04_data_api.md` §6（帧协议不是 JSON；下行裸 UTF-8 + 一次 `0x00`+`{"cursor":N}` 元数据帧；上行直接写 PTY；改尺寸走 `PUT /pty/:id`）。
> 本文所有"已确证"结论均附来源 URL 或**在本机真实执行过**的命令与输出。

---

## 0. 结论（TL;DR）

| 问题 | 结论 |
|---|---|
| 候选 A 是否存在 | ✅ **存在且可用**，但坐标**不是** `com.termux:terminal-view`，而是 `com.github.termux.termux-app:terminal-view:v0.117`（JitPack）。Maven Central **无任何** termux 制品。 |
| License | ✅ **Apache-2.0**（仓库整体 GPLv3-only，但 `LICENSE.md` 明确把 `terminal-view` / `terminal-emulator` 列为 Apache-2.0 例外，源自 jackpal/Android-Terminal-Emulator）。可安全闭源使用。 |
| 能否自定义输入/输出 | ✅ **Java 层可以**：`TerminalEmulator` 是 public final，构造参数就是 `TerminalOutput`（自己做 WS 发送器）；`TerminalView` 是普通 `android.view.View`，自带 `onCreateInputConnection` + `handleKeyCode` + 文本选择。 |
| 能否直接包进 Compose | ✅ 可以（`AndroidView`），但有一个**必须改造点**（见 §1.5）。 |
| **能否"零改动"直接接远程 PTY** | ❌ **不能**。`TerminalView` 唯一装填入口是 `attachSession(TerminalSession)`，而 `TerminalSession` 构造即 JNI `createSubprocess` 起本地 shell。必须打补丁或伪造本地 session。 |
| **最终推荐** | **A′ = 把 termux 的 `terminal-emulator` + `terminal-view` 源码 vendor 进本仓库（Apache-2.0 合规），补丁自持 remote-PTY 会话接口。** 直接用 JitPack AAR 作为**过渡/验证**手段（零源码改动即可跑通），但不要作为终态。 |
| 兜底 | C（自研 Compose VT）风险最高，仅在前两者都不可行时使用；**不建议**投入。 |

---

## 1. 候选 A：Termux `terminal-emulator` + `terminal-view`

### 1.1 仓库与模块（已确证）

仓库：<https://github.com/termux/termux-app>，模块 `app/`、`terminal-emulator/`、`terminal-view/`、`termux-shared/`。
- `terminal-view/build.gradle` 声明 `namespace "com.termux.view"`、`api project(":terminal-emulator")`、`minSdkVersion` 来自 `project.properties`。
  来源：<https://raw.githubusercontent.com/termux/termux-app/master/terminal-view/build.gradle>
- `app/build.gradle` 用 `implementation project(":terminal-view")`。
  来源：<https://raw.githubusercontent.com/termux/termux-app/master/app/build.gradle>
- 模块结构说明：<https://deepwiki.com/termux/termux-app/2-build-system-and-module-structure>
- **关键点**：`terminal-view`/`terminal-emulator` 不依赖 `termux-shared`、不依赖 Termux 运行时（不需要 `com.termux` 包名、不需要 Termux 的 `$PREFIX`），是**可独立嵌入**的通用 VT 组件。

### 1.2 License（已确证 Apache-2.0）

仓库根 `LICENSE.md` 原文：

> The `termux/termux-app` repository is released under **GPLv3 only** license.
> ### Exceptions
> - **Terminal Emulator for Android** code is used which is released under **Apache 2.0** license. Check `terminal-view` and `terminal-emulator` libraries.

来源：<https://raw.githubusercontent.com/termux/termux-app/master/LICENSE.md>
旁证（社区就此提问并得到同样结论）：<https://github.com/termux/termux-app/issues/4257>

⚠️ 注意只取这两个模块；`termux-shared` 是 **MIT + 部分 GPLv3-only**（<https://raw.githubusercontent.com/termux/termux-app/master/termux-shared/LICENSE.md>），**不要**一起 vendor。
⚠️ JitPack 生成的 POM `<licenses>` 写的是 `Other`（JitPack 自动生成，不解析 `LICENSE.md`），**不要**据此判断许可证。
POM 证据：<https://jitpack.io/com/github/termux/termux-app/terminal-view/v0.117/terminal-view-v0.117.pom>

### 1.3 坐标与制品（已确证，含实测）

| 项目 | 值 |
|---|---|
| group | `com.github.termux.termux-app` |
| artifact | `terminal-view` / `terminal-emulator` |
| 版本 | `v0.117`（最新成功构建的 release 标签；`0.117` 亦可用） |
| 仓库 | JitPack：`https://jitpack.io` |

```
https://jitpack.io/com/github/termux/termux-app/terminal-view/v0.117/terminal-view-v0.117.aar
https://jitpack.io/com/github/termux/termux-app/terminal-view/v0.117/terminal-view-v0.117.pom
https://jitpack.io/com/github/termux/termux-app/terminal-emulator/v0.117/terminal-emulator-v0.117.aar
https://jitpack.io/com/github/termux/termux-app/terminal-emulator/v0.117/terminal-emulator-v0.117.pom
```

- **不存在** `com.termux:terminal-view`。JitPack 页面 `https://jitpack.io/#com.termux/terminal-view` 只是一个通用"如何使用 JitPack"模板页（无构建产物），**不要**照抄 PLAN §4.6 里写的 `com.termux:terminal-view`。
- Maven Central 查询 `q=termux` → `numFound=1`，唯一命中是无关的 `com.gitee.archermind-ti:termux-tasker:1.0.0-beta`。查询 `https://repo1.maven.org/maven2/com/termux/` → **404**。
- JitPack 完整建版本列表：<https://jitpack.io/api/builds/com.github.termux/termux-app>
  成功样例：`v0.117 / 0.117 / v0.116 / 0.116 / 0.92 / v0.75 / 0.68 …`；**`v0.102 / 0.104 / 0.105 / 0.107 / 0.108 / 0.109 / 0.110 / v0.115` 均 Error**。挑选版本必须以 `.../latest` 或该 API 为准，不能假设任意 tag 可构建。

### 1.4 API 是否允许"自定义输入 / 自定义输出"（已确证，javap 实测）

在本地对真实 AAR 执行 `javap`（命令见 §6）。

```java
// —— 输出侧：完全可自定义 ——
public final class com.termux.terminal.TerminalEmulator {
  public TerminalEmulator(TerminalOutput, int, int, Integer, TerminalSessionClient); // (sink, cols, rows, transcriptRows, client)
  public void append(byte[], int);        // ← 把 WS 收到的字节喂进来
  public void resize(int, int);           // ← 本地/远端尺寸变更
  public void paste(String);
  public void sendMouseEvent(int, int, int, boolean);
  public void setCursorBlinkingEnabled(boolean);
  public void doDecSetOrReset(boolean, int);
  public void reset();
  public TerminalBuffer getScreen();
  public final TerminalColors mColors;    // public final int[] mCurrentColors（调色板）
  public int mRows; public int mColumns;
}
public abstract class com.termux.terminal.TerminalOutput {
  public final void write(String);
  public abstract void write(byte[], int, int);   // ★ 唯一上行出口：DSR/DA/OSC52/回显都走这里
  public abstract void titleChanged(String, String);
  public abstract void clipboardText(String);
  public abstract void onBell();
  public abstract void onColorsChanged();
}
```

> **架构关键**：`TerminalEmulator` 只认 `TerminalOutput` 接口。**远程 PTY 时，实现一个 `TerminalOutput` 子类把字节发到 WebSocket 即可** —— 终端对 DSR/DA/鼠标/OSC52 的"回答"天然会被送回服务端。这条路径**不需要** `TerminalSession`、**不需要** JNI、**不需要** `libtermux.so`。

```java
// —— 输入侧：View 已自带，但内部写死 session ——
public final class com.termux.view.TerminalView extends android.view.View {
  public TerminalSession mTermSession;     // public 字段
  public TerminalEmulator mEmulator;       // public 字段
  public boolean attachSession(TerminalSession);            // ★ 唯一装填入口
  public InputConnection onCreateInputConnection(EditorInfo); // IME 走这里，已实现
  public boolean handleKeyCode(int, int);                     // 硬键盘 → 终端转义序列
  public void inputCodePoint(int, boolean, boolean);
  public void setTextSize(int);
  public void setTypeface(android.graphics.Typeface);          // ← 可设 JetBrains Mono
  public void updateSize();
  public void startTextSelectionMode(MotionEvent);
  public void stopTextSelectionMode();
}
public interface com.termux.view.TerminalViewClient { /* onScale/onSingleTapUp/readControlKey/readAltKey/onKeyDown/onKeyUp/onCodePoint/onLongPress/shouldEnforceCharBasedInput/… */ }
public interface com.termux.terminal.TerminalSessionClient { /* onTextChanged/onTitleChanged/onSessionFinished/onClipboardText/onBell/onColorsChanged/getTerminalCursorStyle/log* */ }
```

输入链路（源码实测）：`onKeyDown` → `mClient.onKeyDown(...)` → `mTermSession.write(event.getCharacters())`；
`handleKeyCode` → `KeyHandler.getCode(...)` → `mTermSession.write(code)`；
`inputCodePoint` → `mTermSession.writeCodePoint(...)`；
`onCreateInputConnection` 在 `mClient.isTerminalViewSelected()` 为真时按 `shouldEnforceCharBasedInput()` 设 `InputType`。
源码：<https://raw.githubusercontent.com/termux/termux-app/master/terminal-view/src/main/java/com/termux/view/TerminalView.java>
（`attachSession` 见 L290-306，`updateSize` 见 L985-1019，`onDraw` 见 L1022-1032，`onCreateInputConnection` 见 L309）

### 1.5 ❌ 不可行点（必须在方案里正面处理）

**A-1（阻断级）`TerminalView` 只能挂 `TerminalSession`，且 `TerminalSession` 会起本地 shell。**
`TerminalSession` 是 **final** 类，构造器 `TerminalSession(String shellPath, String cwd, String[] args, String[] env, Integer transcriptRows, TerminalSessionClient)`，其 `updateSize(cols, rows, cellW, cellH)` → `initializeEmulator(...)` → **`JNI.createSubprocess(...)`**（`stdio` 指向 `/dev/ptmx`），并 spawn 读线程与 `waitFor` 线程。
源码：<https://raw.githubusercontent.com/termux/termux-app/master/terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java>、
<https://raw.githubusercontent.com/termux/termux-app/master/terminal-emulator/src/main/java/com/termux/terminal/JNI.java>
后果：
- `TerminalView.updateSize()` 有 `if (viewWidth == 0 || viewHeight == 0 || mTermSession == null) return;`（L988），**没有 session 就永远不会给 `mEmulator` 赋值**，`onDraw` 会一直只画黑底（L1023-1024）。
- 输入链路全部落在 `mTermSession.write*`，**外部无法注入输入**。
只有两条出路：(a) vendor 源码后给 `TerminalView` 加一个"设置既有 emulator + 用 `TerminalOutput` 做输入回调"的旁路；(b) 伪造一个本地 `TerminalSession` 作壳（`DummyOutput` 吞掉本地 shell 输出），再用**另一个** `TerminalEmulator`（`RemoteOutput` → WS）交给 renderer —— 但 `attachSession` 内部会用 `mTermSession.updateSize()` 得到的 emulator 覆盖 `mEmulator`，所以 (b) 必须配合把本地 session 的 `TerminalSessionClient.onTextChanged` 等回调做空转，**并接受每终端多 1 个本地 `sh` 进程 + 1 个 PTY fd**。
本地 shell 进程数受 `MAX_TERMINAL_SESSIONS=20` 与 Android 每应用进程/fd 限制约束，不是优雅方案。

**A-2（版本漂移）JitPack 产物 ≠ master 源码，不能混用。**
| API | published v0.117 (AAR) | master 源码 |
|---|---|---|
| `TerminalEmulator` 构造 | `(TerminalOutput, int, int, Integer, TerminalSessionClient)` | `(TerminalOutput, int, int, int cellW, int cellH, Integer, TerminalSessionClient)` |
| `TerminalViewClient` | 无 `isTerminalViewSelected()` | 有 `isTerminalViewSelected()` / `onCopyModeChanged` / `setTerminalCursorBlinkingEnabled` 等 |
| `TerminalEmulator.setTopRow` | 无 | 无（在 `TerminalView`/`TerminalBuffer` 上） |
→ **要么只用 AAR，要么只用某个具体 commit 的源码**。参考"怎么调 API"时，网络上大多数文章/DeepWiki 用的是 master，签名会不匹配。

**A-3（发布渠道风险）JitPack 现场构建，非官方 Maven 发布。**
`{group}:{artifact}:{version}` 是实时 clone + 现场 Gradle 构建；`terminal-emulator` 需 NDK 才能出 `libtermux.so`（JitPack 用 `JITPACK_NDK_VERSION ?: project.properties.ndkVersion`，见 app/build.gradle）。若 JitPack 侧 NDK/JDK 变动，同一 tag 可能失败或产物结构变化。仓库维护者有相关 issue：<https://github.com/termux/termux-app/issues/4613>（JitPack libraries not working）。

**A-4（16 KB page size，Android 15+ 前瞻风险）**
本机实测下载的 `jni/arm64-v8a/libtermux.so` 的 `PT_LOAD` 段对齐为 **0x1000（4 KB）**：

```
PT_LOAD flags=5 offset=0     vaddr=0x0    align=0x1000 (4096)
PT_LOAD flags=6 offset=7376  vaddr=0x2CD0 align=0x1000 (4096)
```
在 16 KB page size 设备/模拟器（targetSdk 35+ 需 16 KB 兼容）上，该 `.so` 无法加载 → **凡是会触到 JNI 的路径（即任何真实 `TerminalSession`）都会失败**。
当前 app（`minSdk 26 / targetSdk 35`）**不立即受影响**（4 KB 设备占比绝大多数），但这是必须记账的债务；`aaptOptions { noCompress "so" }` 或 `android.bundle.enableUncompressedNativeLibs` 之类**不能**解决，需要自己用 NDK（本机已装 `27.2.12479018 / 28.2.13676358 / 29.0.14206865`）以 `-Wl,-z,max-page-size=16384` 重编 `termux.c`。
→ **再次强化推荐：走"A 的 Java 层 + 不碰 JNI"的形态**，则 A-4 天然消失。

**A-5（能力/细节边界，需实测）**
`TerminalRenderer` 的 `render` 是逐 row 绘制（`mRenderer.getFontWidth()` / `getFontLineSpacing()` 决定行列数），支持宽字符（`WcWidth.width`）、组合字符、256 色与真彩（`TextStyle` 里 24-bit color 与 palette index 并存，`mColors.mCurrentColors` 可重写调色板）。
- 未确证：JetBrains Mono 的字体 shaping 质量、闪烁光标能耗、长 `scrollback`（`DEFAULT_TERMINAL_TRANSCRIPT_ROWS=2000`，`MAX=50000`）的滚动性能。
- **未见** hyperlink(OSC 8)、图片协议（iTerm2 imgcat / Kitty graphics）、六角格（Sixel）。官方前端用 **Ghostty**，视觉上会比 Termux VT 更"新"——PLAN §4.6 说的"视觉对齐 Ghostty"应降级为"配色/字体/尺寸对齐"。Ghostty 的功能面不可能 1:1。

### 1.6 Compose 结合：可行步骤（本体已验证到"可编译"，运行期待验）

1. **接入**（两条路，二选一）：

   **(a) 终结形态 — vendor 源码（推荐）**
   ```kotlin
   // settings.gradle.kts
   rootProject.name = "OpenCode"
   include(":app", ":terminal-emulator", ":terminal-view")   // 复制 termux 的两个模块目录进来
   ```
   ```kotlin
   // app/build.gradle.kts
   implementation(project(":terminal-view"))   // terminal-view 已 `api` 传递 terminal-emulator
   ```
   `terminal-view/build.gradle.kts` 需要 `namespace = "com.termux.view"`、`compileSdk = 35`、`minSdk = 26`、Java 17；
   `terminal-emulator/build.gradle.kts` 需要 `namespace = "com.termux.terminal"`，**不配 `externalNativeBuild`**（远程 PTY 用不到 JNI；若保留 `TerminalSession.java` 请同时保留 `jni/termux.c` 或直接删掉该文件，别忘了 `JNI.java` 的 `static { System.loadLibrary("termux"); }` 只在类初始化时触发，删净调用点即可；同时补上那两个模块的 Apache-2.0 `LICENSE`/`NOTICE`）。
   > 不要直接把源码塞进 `app/src/main/java`（`app` 当前是 `com.android.application` 单模块 + `viewBinding`），
   > 也不要用 JitPack 的 AAR 与源码混搭（见 §1.5 A-2）。

   **(b) 过渡形态 — 直接吃 JitPack AAR（零源码改动，用于 M5 前后快速验证 / 冒烟）**
   ```kotlin
   // settings.gradle.kts：在现有仓库列表里追加 JitPack
   dependencyResolutionManagement {
       repositories {
           google()
           mavenCentral()
           maven {
               url = uri("https://jitpack.io")
               // 供应商白名单，避免 JitPack 承接其它 group 的构建
               content { includeGroup("com.github.termux.termux-app") }
           }
       }
   }
   ```
   ```kotlin
   // app/build.gradle.kts
   implementation("com.github.termux.termux-app:terminal-view:v0.117")
   implementation("com.github.termux.termux-app:terminal-emulator:v0.117")
   ```
   ⚠️ 此形态**绕不开** §1.5 A-1，必须配假 session（或直接先不做终端面板，只验协议）。

2. **连接层**（`RemotePtyConnector`，与 UI 解耦，可单测）：
   - `POST /pty/{id}/connect-token`（带 header `x-opencode-ticket: 1`）→ `ticket`；
   - `GET ws(s)://…/pty/{id}/connect?ticket=…&cursor=<serverCursor>`，`directory`/`workspace` 参数按需；
   - **第一帧可能是 `0x00` 开头的元数据帧**：`buffer[0] == 0x00` → 只解析 `{"cursor":N}`（后续 UTF-8 JSON，本帧不要喂 emulator），其余帧直接 `emulator.append(bytes, len)`；
   - 关闭码 `1000`（进程退出）/ `4404 session not found` 要区分；
   - 断线重连 `delay = min(250 * 2^min(tries,4), 4000)`，用**服务端返回的 cursor**续传（**不要**本地按 Kotlin `String.length`（UTF-16）推算，官方前端按 JS string length 累加，与 UTF-8 字节数不等价）。

3. **输出映射**：`SocketOutput : TerminalOutput` → `ws.send(Binary/Text)`；`clipboardText()` → `ClipboardManager`；`titleChanged()` → 面板标题。
4. **输入映射**：
   - 直接复用 `TerminalView` 自带 `onCreateInputConnection`（IME）+ `handleKeyCode`（硬键盘）；
   - vendor 形态下把落点从 `mTermSession.write*` 改成 `viewInputSink(bytes)` → WS；
   - 粘贴走 `TerminalEmulator.paste(String)`；复制走 `emulator.getSelectedText(...)` 或 `TerminalBuffer.getSelectedText(col,row,…,joinLines)`。
5. **尺寸**：`onConfigurationChanged` / `onSizeChanged` 后用 `renderer.getFontWidth()`、`getFontLineSpacing()` 算 `(cols, rows)`：
   ```kotlin
   val cols = max(4, (viewWidth / renderer.fontWidth).toInt())
   val rows = max(4, ((viewHeight - renderer.fontLineSpacingAndAscent) / renderer.fontLineSpacing).toInt())
   emulator.resize(cols, rows)
   remotePty.putSize(rows, cols)   // PUT /pty/:id {size} — 首帧立即，之后 100ms 防抖
   ```
6. **Compose 包裹**：
   ```kotlin
   AndroidView(
     factory = { ctx -> TerminalView(ctx, null).apply { setTerminalViewClient(client); setTypeface(jetBrainsMono); setTextSize(px); isFocusable = true } },
     update = { v -> /* 主题/字号变化时 setTypeface/setTextSize */ },
     onRelease = { v -> v.onDetachedFromWindow(); connector.close() },  // 关闭即卸载
   )
   ```
   注意：`AndroidView` 的 `factory` 只跑一次；焦点/IME 显隐需 `focusRequester` + `LocalSoftwareKeyboardController`；终端面板 tab 高 52dp 由外层 Compose 承担。

---

## 2. 候选 B：其它可用的 CTS/VT 实现（逐一核实过存在性）

| 候选 | 是否有真实坐标 | 能否用于 Android 原生渲染 | 判定 |
|---|---|---|---|
| `com.termux:terminal-view`（PLAN 原文写法） | ❌ 不存在 | — | 坐标错误，改用 `com.github.termux.termux-app:*` |
| Maven Central `q=termux` | 仅 `com.gitee.archermind-ti:termux-tasker:1.0.0-beta` | ❌ 与终端无关 | 排除 |
| <https://github.com/connectbot/termlib> | ⚠️ **有仓库、无坐标**。`org.connectbot` 在 Central 只有 `simplesocks` / `sshlib` / `jbcrypt`，无 termlib | ✅ 形态最匹配：**Kotlin + C++，Jetpack Compose 组件，libvterm(JNI)**，支持 256/真彩、双宽、组合字符、选区、缩放、可 resize、imgcat/Kitty 图形、复杂文字 shaping（README 明确列 Feature） | **潜在最优但未发布**：需要自己 clone + 建 NDK 模块；README 明确 "Pasting support" 仍在 **Planned**；无版本/无变更日志 → 不能作为主选，**列为一号备选** |
| JetBrains JediTerm | JitPack 页面存在（<https://jitpack.io/p/JetBrains/jediterm>），但 `https://jitpack.io/api/builds/JetBrains/jediterm` → **404**，`com/github/JetBrains/jediterm/latest` → **404**；Maven Central `q=jediterm` → **0** | ❌ 还有 `terminal` 模块依赖 IJ 平台；`jediterm-ui` 是 **Swing**（`JComponent`），Android 无 Swing | 排除 |
| BossTerm | `com.risaboss:bossterm-core` / `bossterm-compose`（<https://github.com/kshivang/BossTerm>，docs/embedding.md） | ❌ **Compose Desktop（JVM/Swing）**，不是 Android | 排除（可借鉴其 API 设计） |
| `Mrfixsit/Android-Terminal-Emulator` 等 GitHub fork | JitPack **页面**存在（<https://jitpack.io/p/Mrfixsit/Android-Terminal-Emulator>） | ⚠️ 即 jackpal 老库的 fork，就是 termux 那两个模块的上游；维护度、AndroidX/API 35 兼容性都不如 termux 现行版 | 不建议 |
| `org.jline` / `org.aesh` / `mintshell` 等 | Central 有坐标（如 `org.jline:jline-terminal:3.26.3`） | ❌ 服务端行编辑/REPL 框架，不做屏幕渲染 | 排除 |
| Android 平台 `external/libvterm` | AOSP 源码（<https://android.googlesource.com/platform/external/libvterm>） | ⚠️ 只有 Native 解析器 + JNI 自研 + Canvas 自研渲染 = 等于自研（并入候选 C 的工作量） | 仅作为 C 的加速件 |

**结论**：除 Termux 之外，**在 Maven/JitPack 上不存在"拿来即用"的 Android VT/Compose 终端解析 + 渲染库**。B 类里唯一值得预留的备选是 **connectbot/termlib（自建 NDK 模块）**。

---

## 3. 候选 C：自研 Compose VT（兜底，量化后不建议）

要重做的事（不是"ANSI 子集解析器"那么简单）：

| 子项 | 说明 |
|---|---|
| VT 解析状态机 | CSI/OSC/DCS/ESC、参数与中间字节、`DECSET/DECRST`（alt screen、mouse tracking、bracketed paste、focus events）、`CSI 6n` 等**需要回写**的查询 |
| 屏幕模型 | 主/备缓冲、光标（含 DECSC/DECRC 保存栈）、Tab stop、字符属性（fg/bg/underline/反显/加粗/斜体/隐藏）、擦除/插入/删除/滚动区域（DECSTBM）、行末换行标记 |
| 宽度与组合 | `WcWidth` 东亚双宽、零宽组合字符、emoji ZWJ |
| 渲染 | 等宽字形度量、双宽绘制、下划线/删除线、光标形状（block/bar/underline）+ 闪烁、选区高亮 |
| 交互 | 文本选择（触摸手柄）、复制粘贴、IME `InputConnection`、鼠标上报（`?1000/1002/1006`） |
| 滚动回滚 | 环形 transcript + `LazyRow/ScrollState` 映射 |
| 性能 | 高频 `append` 下的差分重绘（Termux 用 `invalidate()` + 整屏 `Canvas` 绘制，简单但够快） |

保守估计 **2000–4000 行 Kotlin + 2–4 人周**，而且"跑通"到"跑对"之间，`vim`/`htop`/`top`/`git rebase -i`/`claude` 这类 TUI 会持续暴露解析 bug。
参考工作量锚点：termux `TerminalEmulator.java` 约 **175 KB** 源码（本机实测下载大小），connectbot/termlib 是 Kotlin + 独立 libvterm C 两个方向都做了很久。
**判定：只在 A/A′ 全部失败时启用，且建议"用 libvterm(JNI) 做解析 + 自研 Compose 渲染"而非从零写解析器。**

---

## 4. 推荐结论与落地改造点

### 4.1 选型
**选 A，但形态是 A′（vendor 源码 + 最小补丁），而不是直接吃 JitPack AAR，也不是伪造本地 session。**

理由：
1. Apache-2.0 例外明确，闭源可用（§1.2）。
2. `TerminalEmulator` 的输出接口天然就是"远程 PTY 宿主"的形状（§1.4），这是**协议层最省事的匹配**：`0x00` 元数据帧在 connector 层剥掉，其余字节直接 `append`；上行（DSR/DA/OSC52/鼠标）从 `TerminalOutput` 原样回 WS。
3. 阻断点只有一个：`TerminalView` 的 session 装填（§1.5 A-1）。vendor 后加 **~20–60 行补丁**即可（新增 `attachEmulator(emulator, inputSink)` 或把 `TerminalSession` 抽成接口），保留官方 IME/选择/滚动/渲染代码——这比自研 C 或吃 A-1/(b) 假 session 都便宜。
4. 不碰 JNI ⇒ 同时消掉 A-4（16 KB page size）与本地 shell 进程浪费。

### 4.2 落地改造点（把 `TerminalSession`/`TerminalEmulator` 接上 `InputConnection`/`onKey`/尺寸）
```kotlin
// terminal-emulator: 新增（或让 TerminalSession 实现它）
interface TerminalIo {                     // 取代 TerminalSession 在 View 侧的最小契约
    fun write(bytes: ByteArray)            // 输入 → WS（原 mTermSession.write*）
    fun updateSize(cols: Int, rows: Int, cellW: Int, cellH: Int)  // → PUT /pty/:id {size}（100ms 防抖）
    val emulator: TerminalEmulator
}
// terminal-view: TerminalView 内所有 `mTermSession.write(...)` / `updateSize(...)` 改为 `mIo?.…`
//   + 新增 `fun attachIo(io: TerminalIo)`：设置 mIo、mEmulator = io.emulator、
//     setVerticalScrollBarEnabled(true)、updateSize()、invalidate()
```
- `RemotePtyIo : TerminalIo`：`write` → `ws.send`；`updateSize` → 防抖后 `PUT /pty/:id`；`emulator = TerminalEmulator(SocketOutput(), cols, rows, 2000, sessionClient)`。
- `TerminalSession` / `TerminalSessionClient` 的 14 个 `log*` 方法在 v0.117 里是**必须实现**的（无默认实现）→ 建议一并抽成 Kotlin `interface …Client` 带默认实现，避免样板。
- 字体/字号：`TerminalView.setTypeface(FontFamily(File(jbMonoTtf)))` + `setTextSize(px)`；改字号后必须再 `updateSize()`（`mRenderer` 会整体重建）。
- 光标：`setTerminalCursorBlinkerRate(int)` / `setTerminalCursorBlinkerState(Boolean, Boolean)`；`onResume` 恢复、`onPause` 关闭（省电）。

### 4.3 与规划文档的差异（需要同步修改 §4.6）
| PLAN 原文 | 修正为 |
|---|---|
| 坐标 `com.termux:terminal-view` | `com.github.termux.termux-app:terminal-view:v0.117`（JitPack）/ 或 vendor 源码 |
| "成熟 VT 模拟器…JitPack/Maven" | JitPack 有；**Maven Central 没有** |
| "视觉对齐 Ghostty" | 降级为"字体/配色/行列尺寸对齐"；Ghostty 的 OSC8 超链接、图片协议等 Termux VT 不具备 |
| "首选即插即用" | **有且仅有一个改造点**（`TerminalView` 的 session 装填），所以不可能"零改动接入" |
| M5 失败则 WebView 兜底 | 建议改为：M5 主路径 = vendor 版 A′；A′ 若因 IME/渲染细节谈不拢，兜底顺序 = A(AAR)+假 session 过渡 → connectbot/termlib → WebView |

---

## 5. 能 / 不能：确证 vs 待验证

### ✅ 已确证（有来源或本机实测）
1. JitPack 坐标 `com.github.termux.termux-app:{terminal-view|terminal-emulator}:v0.117` **可解析、可下载**。
   **本机实跑**（Gradle 9.2.1 + JDK 21，见 §6）：
   ```
   RESOLVED: com.github.termux.termux-app:terminal-view:v0.117      type=aar -> terminal-view-v0.117.aar (40562 bytes)
   RESOLVED: com.github.termux.termux-app:terminal-emulator:v0.117   type=aar -> terminal-emulator-v0.117.aar (79722 bytes)
   EXTRACTED: terminal-view-v0.117/classes.jar exists=true
   EXTRACTED: terminal-emulator-v0.117/classes.jar exists=true
   BUILD SUCCESSFUL
   ```
2. AAR 结构完整：`classes.jar` + `AndroidManifest.xml`(`minSdkVersion 24`, `targetSdkVersion 28`) + `R.txt` + `res/` + `jni/{arm64-v8a,armeabi-v7a,x86,x86_64}/libtermux.so`。
3. 版本命名空间无冲突：`com.termux.view` / `com.termux.terminal`，**不需要** `applicationId` 或 `com.termux` 包名。
4. 上面 §1.4 列出的**每一个** public 成员，都由 `javap` 在真实 AAR 上打印出来（非文档推测）。
5. `TerminalEmulator` 可在**完全不触碰 JNI / 不创建本地进程**的前提下构造并驱动 —— **本机 `javac` 编译通过**（`pocCompile` BUILD SUCCESSFUL，产出 `ApiSurfacePoc.class`、`ApiSurfacePoc$SocketOutput.class`）。
6. License 是 Apache-2.0（§1.2）；`libtermux.so` 的 `PT_LOAD` 对齐为 4 KB（§1.5 A-4）。
7. `TerminalView` 继承 `android.view.View`、`TerminalRenderer` 是 public final 且 `render(TerminalEmulator, Canvas, …)` → **可编译期确认可被 Compose `AndroidView` 包裹**。

### ⚠️ 待验证（本机未能确证，不要当结论用）
| # | 待验项 | 建议验证方式 | 风险 |
|---|---|---|---|
| V1 | **Gradle 8.11.1 + AGP 8.7.3 实机解析/打包**。本机未装 8.11.1（`~/.gradle/wrapper/dists` 无），且 JDK 25 与 AGP 8.7 不兼容，故只做到"Gradle 9.2.1 非 Android 消费者 + artifactType=aar"级别 | 在仓库里加 `implementation("com.github.termux.termux-app:terminal-view:v0.117")` 后 `./gradlew :app:assembleDebug`（首次会下载 Gradle 8.11.1） | 中（JitPack 元数据由 Gradle 7.1 生成；变体选择需真实 AGP 走一遍） |
| V2 | `AndroidView` + `TerminalView` 的 **IME 实际行为**：`onCreateInputConnection` 依赖 `TerminalViewClient.isTerminalViewSelected()`（master）→ v0.117 无此方法，键盘显隐、`TYPE_NULL` 与 Gboard 兼容性、中文 IME 上屏 | 真机：Gboard 拼音 + 手写 + 硬键盘（含 Ctrl/Alt/Esc/Tab） | 高（这是"最像终端"的体验核心） |
| V3 | 补丁后的 `TerminalView` 在 **滚动回滚 + 选区 + 光标闪烁 + alt-screen 切换** 全链路无回归 | 跑 vim / htop / top / less / `claude` | 中 |
| V4 | 视效：JetBrains Mono 子集下的宽字符与组合字符、`setTextSize` 后的行高取整 | 真机截图比对 | 低-中 |
| V5 | `0x00` 元数据帧 + `cursor` 续传的**语义对齐**（服务端 cursor 单位、Kotlin 侧不得本地推算） | 真机断线重连：`cursor=-1` / 指定 cursor / 省略（全量重放，≤2 MiB） | 中 |
| V6 | connectbot/termlib 备选的**可用性**：无坐标，需要自建模块 + NDK，且 README 标注 pasting 未实现 | clone 后建 NDK 模块编译 | 中（仅备选） |
| V7 | 若未来必须用真 `TerminalSession`：`.so` 需重编译为 16 KB 对齐 | NDK 28/29（本机已装）`-Wl,-z,max-page-size=16384` | 低（但会重新引入本地 shell） |

---

## 6. 复现命令（本机实跑）

```powershell
# 1) 目录探测
https://jitpack.io/api/builds/com.github.termux/termux-app        # 版本列表（ok / Error）
https://jitpack.io/com/github/termux/termux-app/terminal-view/v0.117/terminal-view-v0.117.pom
https://jitpack.io/com/github/termux/termux-app/terminal-emulator/v0.117/terminal-emulator-v0.117.aar
https://search.maven.org/solrsearch/select?q=termux&rows=40&wt=json   # numFound=1（无关制品）
https://repo1.maven.org/maven2/com/termux/                            # 404

# 2) API 面（javap 真实 AAR）
javap -cp "<terminal-view.aar 内 classes.jar>;<terminal-emulator.aar 内 classes.jar>;<android-36/android.jar>" `
      com.termux.view.TerminalView com.termux.terminal.TerminalEmulator com.termux.terminal.TerminalOutput

# 3) 编译期 POC（Gradle，AAR 由 JitPack 解析；非 Android 消费者用 artifactType=aar 模拟 AGP）
gradle --no-daemon pocResolve pocCompile     # → BUILD SUCCESSFUL
```

POC 工程（临时，可复现）：`%TEMP%\termux_poc_plain`（`build.gradle.kts` + `src/main/java/.../ApiSurfacePoc.java`）。
其中 `ApiSurfacePoc.java` 演示了「`SocketOutput : TerminalOutput` → `TerminalEmulator` → `TerminalView`」的完整接线，并**显式注释了 A-1 阻断点**（`attachRequiresSession`）。

---

## 7. 来源清单

| 主题 | URL |
|---|---|
| Termux 仓库 | https://github.com/termux/termux-app |
| 模块结构 | https://deepwiki.com/termux/termux-app/2-build-system-and-module-structure |
| License（Apache-2.0 例外） | https://raw.githubusercontent.com/termux/termux-app/master/LICENSE.md |
| License 争议/确认 | https://github.com/termux/termux-app/issues/4257 |
| termux-shared License（别一起 vendor） | https://raw.githubusercontent.com/termux/termux-app/master/termux-shared/LICENSE.md |
| terminal-view build.gradle | https://raw.githubusercontent.com/termux/termux-app/master/terminal-view/build.gradle |
| TerminalView.java（源码） | https://raw.githubusercontent.com/termux/termux-app/master/terminal-view/src/main/java/com/termux/view/TerminalView.java |
| TerminalSession.java（JNI spawn） | https://raw.githubusercontent.com/termux/termux-app/master/terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java |
| JNI.java（System.loadLibrary） | https://raw.githubusercontent.com/termux/termux-app/master/terminal-emulator/src/main/java/com/termux/terminal/JNI.java |
| jni/termux.c（/dev/ptmx + exec） | https://raw.githubusercontent.com/termux/termux-app/master/terminal-emulator/src/main/jni/termux.c |
| JitPack 构建状态 API | https://jitpack.io/api/builds/com.github.termux/termux-app |
| JitPack AAR | https://jitpack.io/com/github/termux/termux-app/terminal-view/v0.117/terminal-view-v0.117.aar |
| JitPack 模块元数据 | https://jitpack.io/com/github/termux/termux-app/terminal-view/v0.117/terminal-view-v0.117.module |
| JitPack 稳定性 issue | https://github.com/termux/termux-app/issues/4613 |
| Termux Libraries wiki | https://github.com/termux/termux-app/wiki/Termux-Libraries |
| connectbot/termlib（备选） | https://github.com/connectbot/termlib |
| JediTerm（Swing，排除） | https://jitpack.io/p/JetBrains/jediterm |
| BossTerm（Desktop，排除） | https://github.com/kshivang/BossTerm/blob/master/docs/embedding.md |
| AOSP libvterm（C 的加速件） | https://android.googlesource.com/platform/external/libvterm |
| Compose 内嵌 View | https://developer.android.com/develop/ui/compose/migrate/interoperability-apis/views-in-compose |
| 项目协议依据 | `research/04_data_api.md` §6（PTY / WebSocket 帧协议） |
