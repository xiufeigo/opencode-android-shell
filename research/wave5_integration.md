# Wave 5-INT — 四个波次遗留接线点一次性接通（交付报告）

工作区 `C:\sensorsdata\main\program\opencode-android-shell`，Windows + PowerShell，2026/09/21。
范围：**只接线，不重写**。上游波次的报告是唯一规格来源（`wave5_events.md` §5、`wave6_summary.md` §2、
`wave6_export.md` §2、`wave5_review.md` §7、`wave5_settings.md` §D4、`wave5_composer.md` §4）。

构建门（本报告所有数字的来源，**真实执行两次**，第二次是在并发波次停笔后重跑，为最终结论）：

```powershell
$env:JAVA_HOME='C:\Users\xiufe\AppData\Local\Temp\jdk21\unzip\jdk-21.0.12.1+1'
.\gradlew.bat clean :app:testDebugUnitTest :app:assembleDebug --console=plain
# 第 1 次：BUILD SUCCESSFUL in 1m 22s / 84 actionable tasks: 84 executed
# 第 2 次（等 90s 让并发写 `ui/session/summary/SessionSummaryPanel.kt`(10:50:27) 落盘后重跑）：
#         BUILD SUCCESSFUL in 1m 17s / 84 actionable tasks: 84 executed
```

```
suites=94  tests=924  failures=0  errors=0  skipped=0     # app/build/test-results/testDebugUnitTest（两次运行一致）
基线 93 suites / 906 tests → 94 / 924（+1 suite / +18 tests；本波次贡献 16 例，其余 2 例来自并发波次）
APK：app/build/outputs/apk/debug/app-debug.apk（30,905,039 bytes, 10:54:12，clean 后重新打包）
设备安装/模拟器：**未做**（按要求不装模拟器，设备验证命令见 §9）
```

---

## 0. 结论速览

| # | 接线点 | 状态 | 入口（三处一致） |
|---|---|---|---|
| 1 | **A1 会话摘要** | ✅ 接通（宽屏 popover + 窄屏整屏） | header 图标 / `Ctrl+Shift+Y` / 命令面板 `session.summary.toggle` / 手机 ⋯ 菜单 |
| 2 | **A8 导出** | ✅ 接通（SAF 写盘 + toast） | header ⋮ →「导出会话」 |
| 3 | **事件刷新**（projects/sessions/vcs/files） | ✅ 接通（files 300 ms debounce） | 无入口，SSE 驱动 |
| 4 | **通知横幅** | ✅ 接通（`!viewed` 才显示，知道了 / 全部清除） | 无入口，`AppNotifier` 驱动 |
| 5 | **pty 多端同步** | ⛔ **本轮不实现**（理由见 §5） | — |
| 6 | **快捷键覆盖表 → `globalChord`** | ✅ 接通（默认=现状、覆盖优先、冲突可解释） | 设置 → 快捷键 录制后立即生效 |

改动文件（严格在允许集内，未新增依赖、未 commit）：

| 文件 | 类型 | 内容 |
|---|---|---|
| `ui/App.kt` | 改 | 通知横幅；`GlobalChord.SUMMARY`；覆盖表匹配器；`summaryRevision` + `serverStore` 透传 |
| `ui/session/SessionScreen.kt` | 改 | 三条 revision 消费 + files debounce；摘要双形态挂载；导出动作；header 尾部间距 |
| `ui/session/header/SessionHeader.kt` | 改 | ⋮ 新增「导出会话」；摘要图标按钮；`SessionSummaryPopover`（bottom-end 锚定） |
| `ui/home/HomeScreen.kt` | 改 | `LaunchedEffect` key 加 `projectsRevision` + `sessionsRevision` |
| `ui/palette/AppCommands.kt` | 改 | `session.summary.toggle`（`Ctrl+Shift+Y`） |
| `OpenCodeClient.kt` | 改（**只加法**） | `sessionJson` / `messagesJson`（原始 JSON） |
| `res/values*/strings.xml` ×3 | 改（只追加） | `oc_command_summary_toggle`、`oc_export_success`、`oc_export_failed` |
| `app/src/test/.../Wave5IntegrationWiringTest.kt` | **新建** | 16 例：覆盖表纯逻辑 7 + 接线源码守卫 7 + 三语文案 2 |
| `research/wave5_integration.md` | **新建** | 本文件 |

**未改**（逐条确认）：`ui/session/composer/**`、`timeline/**`、`review/**`、`summary/**`、`export/**`、
`ui/settings/**`、`notify/**`、`ChatStore.kt`、`ChatViewModel.kt`、`EventStream.kt`、`ui/theme/**`、
`ui/palette/PaletteModel.kt`、`ui/palette/SessionCommandIntents.kt`、gradle、`_upstream/**`。

---

## 1. A1 会话摘要（`SessionSummaryPanel` 挂载 + 命令）

**参数来源**（全部在 `SessionScreen.kt:434-441`，与 `wave6_summary.md` §2.2 清单逐条对应；面板只收值，
不读 VM）：

| 参数 | 来源 | 备注 |
|---|---|---|
| `directory` | `currentDirectory ?: activeSession.directory`（即既有 `reviewRootPath`，`SessionScreen.kt:415`） | 与评审面板同一份工作区 |
| `branch` | `reviewBranch` ← `chatViewModel.reviewVcsInfo().branch` | 打开摘要时**顺手重拉**：`summaryOpen` 已加进评审数据 effect 的 key（`:341`），非 git 目录为 `null` → 面板自动隐藏该行 |
| `changedFiles` / `additions` / `deletions` | `state.diff.size` / `sumOf { it.additions }` / `sumOf { it.deletions }`（`:434-436`） | `state.diff` 就是评审面板那条 `GET /session/:id/diff` |
| `serverLabel` / `serverUrl` | `null` / `serverStore?.server?.url`（`:446`） | **偏差**：`ServerConfigStore.Server` 没有 label 字段，`SessionSummaryModel.serverRows` 又在 label 非空时优先显示 label，所以按报告「label 若无则传 url」传 `null`；行显示 `http://127.0.0.1:4096` |
| `onClose` | popover 关闭 / 手机端关闭行 / 切 tab | 手机端切 tab、打开 Usage 都会收起摘要 |

**挂载方式**：

* **宽屏（≥768 dp）**：`SessionSummaryPopover`（`SessionHeader.kt:469`，`SUMMARY_POPOVER_END = 12.dp`、
  `SUMMARY_POPOVER_TOP = 38.dp = top-2.5(10) + h-7(28)`、`SUMMARY_POPOVER_WIDTH = 280.dp`，
  `popover.tsx:17-34` 的逐值映射）。Popup 是 header Box 的子节点 → `anchorBounds` **就是** header 带，
  落点 = 头部右下角（bottom-end），点击外部 `onDismissRequest` 收起。
  调用点 `SessionScreen.kt:570-583`。
* **窄屏（<768 dp）**：`MobileSessionTabs(onOpenSummary = …)`（`SessionScreen.kt:804-810`）把 ⋯ 菜单里
  **原本禁用**的「会话详情」行打开；选中后整屏渲染 `SessionSummaryPanel(mobile = true)`（`:825-841`），
  对应 `screen.tsx:191-240` 的整屏 details。一个 tab / Usage 都不会与它并存。

> `MobileSessionTabs` 是 `ui/session/review/**`（禁改），但它的 `onOpenSummary: (() -> Unit)? = null`
> 参数**已经存在**（空=禁用），所以接线完全落在调用点，禁改文件一行未动。

**命令入口**（同一个开关）：

| 入口 | 位置 |
|---|---|
| 键盘 `Ctrl+Shift+Y` | `App.kt:732`（`GlobalChord.SUMMARY`）+ 表项 `App.kt:792`（id `session.summary.toggle`，`mod+shift+y`，`popover.tsx:17-34`） |
| 命令面板行 | `ui/palette/AppCommands.kt:144-154`（`id = "session.summary.toggle"`、`keybind = "Ctrl+Shift+Y"`、`titleResId = oc_command_summary_toggle`），host 动作 `onToggleSummary` 由 `ui/App.kt:445` 注入 |
| 宽屏 header 图标 | `SessionHeader.kt:217-225`（`ic_oc_grid`，`active = summaryOpen` 时 accent 着色） |

**窄屏 ⋯ 菜单里也新增了「打开」路径**，但**没有**再往 header 加按钮：手机工具栏 5 个图标会挤掉标题，
上游也是只在宽屏渲染该 popover 触发器。

**怎么自测（JVM）**：`Wave5IntegrationWiringTest.theSummaryPanelIsMountedOnBothForms` /
`theSummaryCommandIsRegisteredInBothEntryPoints` 用源码断言钉住「两处挂载、参数清单、两处入口、只在会话页 bump」。

**已知偏差 / 未接项**：

1. 图标不是上游的 `window-analytics`：本仓库 `res/drawable` 没有该字形，**新增 drawable 不在本波次允许的
   文件集内**，故用现有的 `ic_oc_grid`（与底栏 Home 同一个 2×2 网格）。要 1:1 只需加一个
   `ic_oc_window_analytics.xml` 并改 `SessionHeader.kt:219` 一行。
2. 折叠态仍是面板内 `remember`（`SessionSummaryPanel` 自带说明），持久化留给设置波次。
3. `session.summary.toggle` **没有进 `SHORTCUT_ROWS`**（`ui/settings/**` 禁改）：默认和弦与命令面板行都在，
   但设置页快捷键表暂时看不到这一行、也就无法从面板录制覆盖它（若设置波次补上该行，本波次的覆盖表会
   自动生效——`GLOBAL_CHORD_COMMAND_IDS` 已含该 id）。

---

## 2. A8 会话导出（原始 JSON + SAF + toast）

**取数据（只加法）**：`OpenCodeClient.kt:951` `sessionJson(id, directory)`、`:960` `messagesJson(id, directory, limit)`
——与既有 `session()`/`messages()` **同一套 `url`/`builder`/`call`/`pagedUrl`**，只是不解析；解析路径
（`Session.fromJson`、`Message::fromJson`）一行未动，`limit` 语义与 `messages()` 完全一致（`null` = 全量，
v1 无 cursor 分页，`wave6_export.md` §2.1）。

**入口**：`SessionHeader.kt:404-413` 的 ⋮ 新增「导出会话」（复用三语齐全的既有 key
`command_session_export` = "Export session"/"导出会话"/"匯出工作階段"，**不再新增第四份同句文案**），
原来的「*Export…* needs server share/export … still missing」占位注释已删除（`SessionHeader.kt:301-307`
的 KDoc 也同步改成事实描述）。`SessionMenuAction` 加 `EXPORT`（`SessionHeader.kt:74`），
调用点 `SessionScreen.kt:539` → `exportSession()`（`:459-490`）：

```
⋮ → EXPORT → exportSession()
  client.sessionJson(id, reviewRootPath) + client.messagesJson(id, reviewRootPath)   [Dispatchers.IO]
  → SessionExport.serialize(info, messages)                                          [同一 IO 段]
  → SessionExport.filename(id, title) → exportSaver.save(filename, json) { ok -> toast }
```

* 走的是 `rememberSessionExportSaver()`（`ui/session/export/SessionExportSaver.kt`，未改）＝ SAF
  `CreateDocument("application/json")`；**取消对话框不回调、不弹 toast**（上游语义，`wave6_export.md` §1）。
* 成功 toast = `oc_export_success`（带 `%1$s` = 文件名），失败 = `oc_export_failed`；**新 key 前缀
  `oc_export_`，三 locale 齐全**（`values/strings.xml:692-694` 与两份 zh）。
* 服务器不可用（`serverStore == null` 或 `forServer` 失败）→ 直接失败 toast，不静默。

**偏差（重要，评审需知）**：导出用**屏幕自己构造的轻客户端**读原始 JSON，没有走 `ChatViewModel`：

```kotlin
val exportClient = remember(serverStore, serverRevision) {
    serverStore?.server?.let { OpenCodeClient.forServer(it) }   // SessionScreen.kt:450
}
```

原因：`ChatViewModel` 只暴露**已解析**模型，而本波次**禁改 `ChatViewModel.kt`**；报告 §2.1 的推荐①
（给 client 加原始 JSON 重载）已做，但「谁来调用」只能落在允许改的 `SessionScreen`，且
`serverStore` 正是 `ui/App.kt:543` 传给终端面板的同一个对象（同一模式，无第二份配置来源）。
代价：一次导出 = 两个 `GET` + 一个只在该屏幕存活的 Client；收益：零共享状态、VM 与解析路径零风险。

**怎么自测（JVM）**：`Wave5IntegrationWiringTest.theClientGainedRawJsonReadersAndKeptTheParsedPaths` /
`theExportRowIsWiredAndThePlaceholderIsGone`（含「占位文案必须消失」的反向断言）。

**未接**：分页导出（v1 无 cursor，`?before=` 400）、归档（上游自己也没做，`wave6_export.md` §4）、
>10 万条时的进度提示（本轮不装设备，未实测内存峰值）。

---

## 3. W5-E §5 事件刷新接线

| 报告条目 | 改哪里 | 怎么自测 |
|---|---|---|
| §5.1 `project.updated` → Home 项目列 | `HomeScreen.kt:92-101`：`projectsRevision` / `sessionsRevision` collect + `LaunchedEffect(chatViewModel, projectsRevision, sessionsRevision)` 内重跑 `listProjects` | 在另一个窗口/另一端 `POST /session` 新建会话 → Home 项目列/会话列自动出现新条目（旧行为：必须离开再进 Home） |
| §5.2 `session.created/deleted` → 会话列 | 同上（Home）；抽屉 `SessionScreen.kt:330`：`LaunchedEffect(drawerState.isOpen, drawerOpen, sessionsRevision)`，抽屉**开着**也刷新 | 抽屉打开时在别处删掉一个会话 → 该行消失（旧行为：要关掉抽屉再开） |
| §5.3 `vcs.branch.updated` → 分支 chip | `SessionScreen.kt:341`：`vcsRevision` 进评审数据 effect 的 key，重跑 `reviewVcsInfo` + `refreshDiff`/`refreshTodos` | 在终端 `git checkout` 另一分支 → 评审面板分支 chip 跟随（旧行为：重进会话才变） |
| §5.4 `file.edited` → 评审面板失效 | `SessionScreen.kt:356-364`：独立 effect `LaunchedEffect(activeSession.id, reviewOpen, mobileReview, filesRevision)` + `delay(FILE_EDITED_DEBOUNCE_MS = 300L)` + `refreshDiff()`/`refreshTodos()` | 让 agent 改一个文件 → 评审 diff 在 300 ms 后刷新，且**分支 chip 不闪** |

两个实现细节（评审会问的）：

1. **`filesRevision` 刻意没进 §5.3 那个 effect 的 key**：那个 effect 会 `reviewBranch = null` 重置分支，
   若按报告字面把两个 revision 塞进同一个 key，每次文件写入都会让分支 chip 闪一下再回来。拆成两条
   effect 后语义更准：`vcsRevision` 重建全部（分支可能变），`filesRevision` 只重取 diff/todos。
2. **Home 的 revision 是「粘」的**：Home 本身不开 SSE（M1 契约，`wave5_events.md` §5 前言），所以
   revision 只在会话页跑动时 +1；值可粘 → 下一次 Home 组合就能看到，不需要第二条连接或轮询。
   同时把 `loading = true` 收窄为「列表为空才显示」（否则每次 revision bump 都会闪一次加载态）。

**未接（有意）**：报告 §5.4 建议「`ReviewPanel` 的 Files 树以 `filesRevision` 作 key」。**没做**，因为把
`SessionReviewPanel` / `ReviewFilesTab` 以 `filesRevision` 重新 key 会在每次文件写入后**丢掉用户已展开的目录
与在预览的文件**（那两张表在禁改目录里，也没有"只重取根"的入口）。等价效果由 `refreshDiff()` 提供
（评审面板的 diff 数据与 A/M/D 徽标都来自它），树结构本身用抽屉/预览重新进入即可刷新。

---

## 4. E5 通知横幅（`ui/App.kt`，OcTheme 内、DialogHost 外）

* 挂载点 `App.kt:255-263`：`Box(Modifier.fillMaxSize()) { DialogHost { … }; NotificationBanner(…) }`
  —— **是 `DialogHost` 的兄弟节点**（`Wave5IntegrationWiringTest` 用括号配对断言"横幅在 DialogHost 块闭合
  之后"），所以 dialog 的 scrim 不会盖住它，settings 面也不会裁掉它；又在 `OcTheme` 内，用实时 token。
* 显示条件 `App.kt:1218`：`notifications.lastOrNull { !it.viewed }` —— **`!viewed` 才弹**；`viewed` 条目只留在
  数据流里（报告 §5.5 推荐的抑制策略）。同屏只显示**最新一条**，避免三张卡叠在 header 上。
* 文案：`kind → titleResId()`（`App.kt:1273-1278`）单点映射到既有 `oc_notify_title_agent/ permission/
  question/ error`；第二行 = `sessionTitle ?: detail`（报告原文）。按钮 = 既有 `oc_notify_dismiss` /
  `oc_notify_dismiss_all`（三语齐全，**未新增任何字符串**）。
* 行为：`chatViewModel.dismissNotification(id)` / `clearNotifications()`；`chatViewModel == null`（preview /
  深链）时 `NO_NOTIFICATIONS` 兜底 —— flow 的 collect 是**无条件**的，否则横幅一旦早退就再也不会回来。
* 位置：`Alignment.TopCenter` + `WindowInsets.safeDrawing` 内边距，不遮挡状态栏。

**未接**：点横幅跳会话。Upstream 的 `notification.action.goToSession` 需要 session 路由与
`NavController`，两者都在 **`AppScaffold`（横幅的子节点）**里；要在横幅里做，得把
`openSessions`/`navController` 上提到 `OcApp` 或给横幅开一条 intent 通道 —— 属于 shell 结构改动，
本轮按「最小横幅」交付并在此声明。同理未做系统通知（`NotificationManager`）与音效播放器（`sound*` 三键），
与 `wave5_events.md` §6 的边界一致。

---

## 5. pty 决策：**本轮不实现**（按报告 §5.6 的建议）

理由（两条同时成立，直接引用 `wave5_events.md` §5.6，并复核了当前代码）：

1. `ui/terminal/share/PtyApi.kt` **没有 `GET /pty` 列表读**（只有 create/exists/remove/updateSize），
   要做「别端新建 → 本端长出 tab」必须先补一个列表 API；`PtyApi.kt` 不在本轮允许集内。
2. **终端路由不持有 `ChatViewModel`**：`Route.TERMINAL → TerminalPanelHost`（`ui/App.kt:600-612`）只拿
   `server`/`directory`，而 SSE 只在 `SessionScreen` 存活期间运行（`start`/`stop` 由它控制，
   `SessionScreen.kt:282-284`）——两个页面基本不同时存在，「信号驱动终端刷新」在当前路由/连接模型下
   收益极小。

`ptyRevision` 已经在 `ChatViewModel` 里就绪（`wave5_events.md` 表最后四行）。要接线，得先由 Lead 决策
「终端页是否也持有流」或「终端页打开时重放一次 revision」+ 补 `GET /pty`——两条都不在本波次的边界内。

---

## 6. 快捷键覆盖表 → `globalChord`（W5-S §D4 的最后一公里）

设置波次交付了 `Settings.keybinds`（持久化覆盖表）与纯函数 `keybindSignatures` / `applyKeybindOverrides`，
但当时**没有**接进键盘处理。本波次接通（`ui/App.kt`，`ui/settings/**` 一行未动）：

```
Settings.keybinds (App.kt:467) → effectiveGlobalChords(keybinds) (App.kt:825)
   = SHORTCUT_ROWS 的默认值 (defaultGlobalChords, App.kt:806)
     ⊕ 用户覆盖（keybindSignatures(bindingForSignatures(binding)) 展开成 signature→action 表）
     覆盖优先：先铺默认，再铺覆盖（同一 signature 上覆盖写赢）
```

| 语义 | 实现 | 自测 |
|---|---|---|
| **默认值 = 现状** | `defaultGlobalChords()` **直接读 `SHORTCUT_ROWS`**（`command.palette` 的 `Ctrl+K / Ctrl+Shift+P`、`settings.open` 的 `Ctrl+,`、`file.open`、`session.new`/`tab.new`、`terminal.toggle`），`session.search`/`session.summary.toggle` 由 `EXTRA_GLOBAL_CHORDS` 提供（表里没有这两行） | `withNoOverridesEveryChordStillBehavesAsBefore`（16 条断言，含 `mod+shift+k` 不触发、KeyUp 不触发、纯修饰不触发）+ 既有 `ShellWiringTest` 17 例全绿 |
| **用户覆盖优先** | 覆盖值替换该 id 的默认值；**旧键位随之失效**（覆盖是替换而不是追加） | `aRecordedOverrideWinsAndFreesItsOldChord`：`terminal.toggle → Ctrl+Shift+S` 后 `Ctrl+\`` 不再触发，tile 其他行不受影响 |
| **未分配** | `none` / 空白 → 该动作没有任何和弦（与面板显示的 `Unassigned` 同义） | `anUnassignedOverrideRemovesTheAction` |
| **冲突不生效可解释** | 面板的冲突检测（`keybindConflicts`）根本不允许把重复键位写进 store，所以冲突只可能来自手改 prefs：此时**覆盖赢默认**，两个覆盖之间**表序靠后者赢**（`GLOBAL_CHORD_COMMAND_IDS` 顺序固定，非 HashMap 序） | `idsWithoutAGlobalChordStayInertOnPurpose` + 注释/报告双写 |
| **哪些 id 真的能用** | 只有实现了全局和弦的 8 个 id：`command.palette`、`settings.open`、`file.open`、`session.new`、`tab.new`、`terminal.toggle`、`session.search`、`session.summary.toggle` | `idsWithoutAGlobalChordStayInertOnPurpose` |
| **inert 的 id** | `review.toggle`、`fileTree.toggle`、`prompt.submit`：它们的行只从命令面板/输入区进入，**没有**全局处理；把 `review.toggle` 记成 `Ctrl+Shift+Y` 也**不会**抢走摘要的和弦（默认=现状，不新增动作） | 同上 |
| `⌘/Meta` | 仍按 `mod` 处理（Android 无 ⌘，录制器也只写 `Ctrl`，`formatKeybindChord` KDoc），`alt` 参与匹配 | `metaStillReadsAsCtrl`（第一个用例里的 meta 分支）+ `alt` 不匹配断言 |
| 词汇表一致性 | 事件侧 token = `chordSignatureToken`，与面板的 `keybindSignatures` 同词典；**除 `,` 这个上游分隔符歧义外**逐字一致 | `theTokenVocabularyIsTheOneTheSettingsPanelWrites` |

> **发现的一个真坑（已修，值得记录）**：`keybindSignatures("Ctrl+,")` 返回 `["ctrl"]`——因为 `,` 是上游
> `parseKeybind` 的**列表分隔符**，`settings.open` 的默认键位恰好就是 `Ctrl+,`。若直接把行值喂进签名表，
> 设置和弦会失效。修法：`bindingForSignatures()`（`App.kt:852`）在做签名前把这个"裸逗号"拼成
> `Ctrl+Comma`，事件侧的 `Key.Comma` token 也统一拼 `comma`。**`keybindSignatures` 本身在
> `ui/settings/**`（禁改），所以它的这个歧义行为保持原样**（面板的冲突检测仍按旧值工作），
> 两边行为差异已由 `Wave5IntegrationWiringTest` 显式钉住并写进这里。

**未接**：`review.toggle` / `fileTree.toggle` 的键位仍然"面板显示了但按下去没反应"（**与接线前一致**，
因为本轮要求「默认值=现状」）。要让它们也生效只需把两个 id 加进 `GLOBAL_CHORD_COMMAND_IDS` 并把
`SessionCommandIntents.toggleReview()/openFileTree()` 接到 `GlobalKeybindFocus` 的 when 分支——
属于新增动作，明确留给下一波。

---

## 7. 汇总：本波次没接的项 + 原因

| 项 | 状态 | 原因 |
|---|---|---|
| pty 多端同步 | ⛔ | §5：缺 `GET /pty`，且终端路由不持有 `ChatViewModel`（报告 §5.6 的建议就是本轮不做） |
| 横幅点击跳会话 | ⛔ | §4：`navController`/`openSessions` 在 `AppScaffold`（横幅子节点），需 shell 结构改动 |
| 系统通知 / 音效 | ⛔ | 需 channel/权限/前台策略与原生播放器，W5-E §6 已明确排除 |
| 摘要折叠态持久化 | ⛔ | 面板内 `remember`；持久化属设置域（`wave6_summary.md` §3） |
| `window-analytics` 图标 1:1 | ⚠ | 新增 drawable 不在允许集，用 `ic_oc_grid` 顶替（§1 偏差 1） |
| Files 树按 `filesRevision` 重新 key | ⛔ | 会丢展开态；改为 `refreshDiff/refreshTodos`（§3 未接说明） |
| `review.toggle`/`fileTree.toggle`/`prompt.submit` 的键位生效 | ⛔ | 无全局和弦（保持"默认值=现状"，§6） |
| `session.summary.toggle` 出现在设置页快捷键表 | ⛔ | `ui/settings/**` 禁改；覆盖机制已支持该 id，补行即生效 |
| 双机 E2E（真机） | ⏳ | 按要求不装模拟器，命令见 §9，由集成方执行 |

---

## 8. 并发与构建说明

* 本轮与 `ui/settings/**`、`ui/session/summary/**`、`ui/session/export/**` 等波次共用一个 worktree，
  `clean` 会清掉别人的中间产物。第 1 次全量门跑完后发现并发的
  `ui/session/summary/SessionSummaryPanel.kt` 在 10:50:27 又被写过（在编译窗口之后），于是等了 90 s、
  确认没有新的源码写入后**重跑了整条门**（§0 的第 2 次），两次都是 94/924/0 且 84/84 executed；
  `SessionSummaryPanel`/`SessionExport` 的公开签名在此期间未变（新增的是带默认值的可选参数），
  所以本波次的调用点仍然成立。
* 本轮**没有**改动 `ui/session/summary/**` / `ui/session/export/**`（只调用它们的公开 API）。
* 收尾前复核：`git status --porcelain` 中本轮只新增/修改了 §0 表格列出的文件；**未 commit**；
  未新增依赖；临时构建日志（`research/_w5int_gate*.log`）已删除。

## 9. 设备验证命令（集成方按此做双机 E2E，可整段复跑）

> 环境坑沿用 `research/wave5_status.md` §4：**模拟器串号重启后会互换**（先核对 `wm size`）；
> **每次 `install -r` 后必须重做 `adb reverse`**；中文 IME 下 `input text` 可能产出全角字符。
> launcher 是 `.OcActivity`（`AndroidManifest.xml:18-30`）；下面用 `$S` 指代被测设备串号。

```powershell
# 0) 找设备（折叠屏 2076x2152 / 平板 2560x1600，串号会互换）
adb devices
foreach ($d in (adb devices | Select-String "emulator-\d+" | ForEach-Object { ($_ -split "\s+")[0] })) {
  "$d -> " + (adb -s $d shell wm size)
}
$S = "emulator-5556"        # ← 换成上面查到的串号（平板测宽屏 popover，折叠屏测手机 ⋯ 菜单）

# 1) 安装本轮 APK（clean 后的产物）+ 端口反向（每次 install 之后都要重做）
adb -s $S install -r app\build\outputs\apk\debug\app-debug.apk
adb -s $S reverse tcp:4096 tcp:4096
adb -s $S shell am start -n com.xiufeigo.opencodeshell/.OcActivity
Start-Sleep -Seconds 3

# 2) 【事件刷新 §3】本端停在会话页，另一端建/删会话，看 Home 与抽屉是否自动跟随
#    另开一个 shell：
#    curl.exe -s -X POST "http://127.0.0.1:4096/session" -H "content-type: application/json" -d '{}'
#    预期：抽屉（若打开）与 Home 会话列 1~2 秒内出现新行，无需重进页面

# 3) 【A1 宽屏】在会话页按摘要和弦 → 头部右下角应出现 280dp 面板（工作区/分支/变更/服务器四行）
adb -s $S shell input keyevent --longpress 0     # 仅为了确保应用在前台拿到焦点
adb -s $S shell input keyevent KEYCODE_CTRL_LEFT KEYCODE_SHIFT_LEFT KEYCODE_Y
Start-Sleep -Seconds 2
adb -s $S exec-out screencap -p > research\wave5_shots\w5int-summary-tablet.png
#    预期：面板锚在 header 右下角（bottom-end），非 git 目录不显示 Branch 行，无变更不显示 Changes 行；
#    点面板外任意处应关闭；再按一次 Ctrl+Shift+Y 应重新打开

# 4) 【A1 命令面板 + 窄屏入口】
adb -s $S shell input keyevent KEYCODE_CTRL_LEFT KEYCODE_K      # 打开命令面板
adb -s $S shell input text "summary"                            # 预期：出现「Session summary  Ctrl+Shift+Y」一行
adb -s $S shell input keyevent KEYCODE_BACK                     # 关掉
#    窄屏（折叠屏设备）：点会话页右下 ⋯ → 菜单里「Session details」应为**可点**（不再灰）→ 点开为整屏面板
#    坐标：⋮ 在 tab 条最右（≈ (screenWidth-20, tabStripY)）

# 5) 【A8 导出】会话页右上 ⋮ →「导出会话 / Export session」→ 系统保存对话框 → 选 Downloads 保存
adb -s $S shell input tap 1240 40           # 平板：header ⋮ 坐标（换设备请按截图微调）
Start-Sleep -Seconds 1
adb -s $S exec-out screencap -p > research\wave5_shots\w5int-menu.png    # 预期：Rename / Export session / Delete 三行
adb -s $S shell input tap 1200 120          # 第 2 行 = 导出
Start-Sleep -Seconds 3
adb -s $S exec-out screencap -p > research\wave5_shots\w5int-saf.png     # 预期：系统 CreateDocument 对话框
#    保存后：
adb -s $S shell ls -l /sdcard/Download | Select-String "json"            # 预期：<title>.json
adb -s $S shell "head -c 200 /sdcard/Download/<title>.json"              # 预期：{"info":{...},"messages":[...]}（2 空格缩进）
#    取消对话框（返回键）时**不应**出现任何 toast

# 6) 【通知横幅 §4】让 agent 跑完一轮（或在另一端触发 permission.asked）
#    预期：OcTheme 顶部出现一条横幅（标题来自 oc_notify_title_*，第二行 = 会话标题/ detail），
#    「Dismiss / 知道了」点掉当前条，「Dismiss all / 全部清除」清空；横幅**不会**被 dialog 遮住
#    （开着设置对话框时横幅仍可见）

# 7) 【快捷键覆盖 §6】设置 → 快捷键 → 点 terminal.toggle 的胶囊 → 按 Ctrl+Shift+U → Reset all
#    预期：面板立刻显示 Ctrl+Shift+U；此后 Ctrl+` **不再**开关终端，Ctrl+Shift+U 才有反应；
#    录一个已被占用的键（如 Ctrl+K）→ 出现冲突提示且**不写盘**

# 8) 收尾：卸载可选
# adb -s $S uninstall com.xiufeigo.opencodeshell
```

**验收判读**：§3 的新行自动出现、§4 的 SAF 对话框与磁盘 JSON、§5 的两个摘要形态、§6 的横幅按钮、
§7 的"录制后旧键位失效"——五条都能复现即接线完成。

## 10. 只跑本波次相关套件的命令

```powershell
$env:JAVA_HOME='C:\Users\xiufe\AppData\Local\Temp\jdk21\unzip\jdk-21.0.12.1+1'
.\gradlew.bat :app:testDebugUnitTest --tests "com.xiufeigo.opencodeshell.Wave5IntegrationWiringTest" `
  --tests "com.xiufeigo.opencodeshell.ShellWiringTest" `
  --tests "com.xiufeigo.opencodeshell.SessionExportTest" `
  --tests "com.xiufeigo.opencodeshell.SessionSummaryModelTest" --console=plain
# Wave5IntegrationWiringTest 16/16、ShellWiringTest 17/17（2026/09/21 10:49 实测，0 failures）
```
