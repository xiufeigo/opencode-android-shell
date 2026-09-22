# 路由与界面清单对齐审计（Android native shell ↔ 上游 opencode v2 前端）

- 审计类型：**只读**。未修改任何既有代码；本文件为新建报告。
- Android 侧根：`C:\sensorsdata\main\program\opencode-android-shell`
- 上游侧根：`C:\sensorsdata\main\program\opencode-android-shell\_upstream`（分支 `v2`）
- 路径约定：上游路径相对 `_upstream/`，Android 路径相对仓库根。
- 状态取值只有四个：`1:1` / `近似` / `缺失` / `不适用`。
- **证据纪律**：每个 `file:line` 都来自本次实际的 `read`/`grep`。凡本次未落实到行的判断，一律写 `未确认`，不臆测。
- 文案中英文差异不计为差异（i18n）。仅当上游该处**根本没有对应字符串**时才记差异。

> 本报告把同一套四列表格按三类拆开呈现（路由 / 对话框与覆盖层 / 页面与区域），
> 列名完全一致；最后一节的统计数字覆盖三张表全部行。

---

## 0. 结论速览

上游 v2 前端的路由只有 4 条（`_upstream/packages/app/src/shell/routes/routes.tsx:34-60`），
但**用户可感知的界面绝大多数不在路由里，而在对话框栈与页面区域里**：全局对话框栈
（`packages/ui/src/context/dialog.tsx:159`）承载了命令面板、服务器增删改、供应商连接、
模型选择、Fork、删除确认、图片预览等；设置则是**独立路由 + 独立全屏 surface**
（`routes.tsx:38` → `settings/shell.tsx:70` → `settings.css:8` 的 `.settings-screen`），
而不是对话框。

Android 侧的路由表在 `app/src/main/java/com/xiufeigo/opencodeshell/ui/App.kt:102-136`（`Route` 对象），
`composable(...)` 注册在 `App.kt:258 / 272 / 280 / 309 / 341`；
所有 UI 层共用宿主 `ui/overlay/DialogHost.kt:123`。

---

## 表 A — 路由

| 上游项 | 上游证据 (file:line) | Android 对应实现 (file:line) | 状态 |
|---|---|---|---|
| `/` 首页路由（`Home`） | `_upstream/packages/app/src/shell/routes/routes.tsx:37`；组件 `app/src/home/route.tsx:13` | `Route.HOME = "home"` `ui/App.kt:103`；`composable(Route.HOME)` `ui/App.kt:258`；`HomeScreen` `ui/home/HomeScreen.kt:59` | 1:1 |
| `/server/:serverKey/session/:id` 会话路由 | `shell/routes/routes.tsx:39-56`（`TargetSessionRouteContent`，`app/src/session/route.tsx:31`） | `Route.SESSION = "server/{serverKey}/session/{id}"` `ui/App.kt:104`；`composable(Route.SESSION)` `ui/App.kt:309`；`SessionScreen` `ui/session/SessionScreen.kt:92` | 1:1（路径串逐字相同） |
| `/new-session` 草稿路由 | `shell/routes/routes.tsx:57`（`DraftRoute`，`app/src/new-session/route.tsx:17`）；上游用 `?draftId=` 从全局 tab store 取草稿（`new-session/route.tsx:18-29`） | `Route.NEW_SESSION = "new-session?directory={directory}"` `ui/App.kt:112`；`composable` + `directory` 参数 `ui/App.kt:280-308`；`NewSessionScreen` `ui/home/NewSessionScreen.kt:72` | 近似：路径一致、语义一致，但上游以 `draftId` 绑定 app 级草稿 tab（`shell/tabs/tabs.tsx` 的 `DraftTab`），Android 无草稿 tab store，改用 `?directory=` 预选目录（`ui/App.kt:107-112`、`Route.newSession()` `ui/App.kt:134-135`） |
| `/settings` 设置路由 | `shell/routes/routes.tsx:38` → `app/src/settings/shell.tsx:70`；全屏 surface `.settings-screen`（`app/src/settings/settings.css:8-27`，宽 `min(100%,1048px)`，侧栏 `328px`，`settings.css:42-56`） | `Route.SETTINGS = "settings"` `ui/App.kt:105`；**该 composable 不做渲染**，只 `onOpenSettings()` 后 `popBackStack()`（`ui/App.kt:272-279`）；真实 UI 是 `DialogSize.X_LARGE` 对话框里的 `SettingsDialogContent`（`ui/App.kt:580-591`、`ui/settings/SettingsScreen.kt:61`） | 近似（结构性差异）：上游是**路由 + 全屏卡片 surface**（1048px 内容宽 / 328px 侧栏 / 12 个根 tab），Android 复刻成 **`x-large` 对话框**（`min(100vw-32,980) × min(100vh-92,600)`，`SettingsScreen.kt:154-165`，240dp 侧栏 `SettingsScreen.kt:79`），根 tab 只剩 5 个（`ui/settings/SettingsModel.kt:17-23`）。差异原因写在 `ui/App.kt:84-90` 的注释里，但注释所依据的「上游用 Dialog 承载设置」与上游 v2 源码不符 —— 上游只有「管理模型 / 选择模型」用 `Dialog variant="settings"`（`providers/models/manage.tsx:62`、`providers/models/select-dialog.tsx:549`） |
| 首启无服务器时的**全屏连接页** `ConnectServerScreen` | `shell/routes/routes.tsx:79`（`fallback={<ConnectServerScreen />}`）；组件 `app/src/servers/connect/screen.tsx:18`（`<main data-component="connect-server">` `:50`）：Wordmark `:52-54` + 标题描述 `:55-58` + URL/密码表单 `:82-137` + 页脚 `opencode pair` `:158-161`；**摄像头扫码配对仅 web 平台**（`screen.tsx:138` 的 `platform.platform === "web"` 门控；`PairingScanner` 定义 `servers/connect/scanner.tsx:9`） | 无任何等价页面。`OcActivity` 直接 `setContent { OcApp(...) }`（`OcActivity.kt:29-63`，manifest 里 `OcActivity` 是 LAUNCHER）；未配置服务器时只在各页打一行提示：`oc_setup_hint`（`ui/home/HomeSessionsList.kt:106`、`ui/home/NewSessionScreen.kt:212`、`ui/session/SessionScreen.kt:316`），旧 WebView 壳用系统 Toast（`MainActivity.kt:123`）。URL/密码表单只存在于「设置 → 服务器 → 新增」内联对话框（`ui/settings/ServersPanel.kt:258`） | 缺失（上游这是所有平台的整屏兜底；扫码子功能才是 web 专属，Android 两者都没有） |
| `preloadRoute(url)` 路由预加载 | `shell/routes/routes.tsx:25-32`（`/new-session`、`/settings`、`/server/*/session/*` 三条做 `import()` 预加载）；调用点 `app.tsx:25` | 无对应实现 | 不适用：这是浏览器代码分割的按需 `import()` 优化（`routes.tsx:14-23` 的 `lazy(...)`），原生 Compose 无 JS 包加载成本，不需要等价物 |

---

## 表 B — 对话框与覆盖层

### B-1 全局对话框栈与尺寸体系

| 上游项 | 上游证据 (file:line) | Android 对应实现 (file:line) | 状态 |
|---|---|---|---|
| 全局对话框栈 `DialogProvider` + `<div data-component="dialog-stack">` | `_upstream/packages/ui/src/context/dialog.tsx:159-169`（`data-component="dialog-stack"` 在 `:164`）；每层 `data-dialog-layer={layer}` 且 `z-index = 50 + layer*10`（`:78-118`）；Esc 全局捕获关闭（`:65-76`）；关闭用 `setTimeout(...,100)` 播完退场再卸载（`:57-62`） | `DialogHost` `ui/overlay/DialogHost.kt:123`；`DialogController.show/close/closeAll` `:94/:99/:107`；层渲染 `DialogLayer` `:164`；Esc `:181-188`；退场保留 `OcMotion.exitHoldMs` `:132-137` | 近似：`DialogEntry`/`DialogSize`/`LocalDialogActive`(「有层即抑制全局快捷键」，上游是 `active` = 栈顶那一个，`dialog.tsx:183`) 都对得上。**接口差异**：上游 `show` 是「先清空整栈再挂载」（`dialog.tsx:140-149`），`push` 才是叠层（`:131-138`）；Android 只有一种 `show`，语义等同上游 `push`（`:94-96`），清栈要靠显式 `closeAll()`（`:107`）。因此上游任何依赖 `show` 替换当前层的链路（如 `usage-exceeded-dialogs.tsx:86` 从超限弹窗切到连接弹窗）在 Android 上会变成叠层 |
| `Dialog` 容器尺寸/变体：`size="normal"|"large"|"x-large"`、`variant="default"|"settings"`、`fit` | `packages/ui/src/overlays/dialog/dialog.tsx:7`（size 联合）、`:8`（variant 联合）、`:12`（`fit`）、`:84-114`（`data-component="dialog-v2"` `:89` / `data-variant` `:90` / `data-fit` `:91` / `data-size={size ?? "normal"}` `:92`）；尺寸 CSS：默认 `480×368`（`dialog.css:19-29`，`:23-24`）、`fit` → `height:auto`（`dialog.css:161-169`）、`large` → `640×480`（`dialog.css:171-174`）、`x-large` → `min(100vw-32,980) × min(100vh-92,600)`（`dialog.css:176-179`）。生产环境用到 `variant="settings"` 的**只有 2 处**（`providers/models/select-dialog.tsx:549`、`providers/models/manage.tsx:62`）；`size="large"` 5 处；**`x-large` 仅出现在 Storybook**（`packages/ui/src/overlays/dialog/dialog.stories.tsx:97`）；`normal` 生产有用（如 `providers/connect/mcp-dialog.tsx:34`） | `enum class DialogSize { FIT, LARGE, X_LARGE }` `ui/overlay/DialogHost.kt:75`；按 size 选动效 `overlayFor` `:228-231`。具体尺寸由各调用方自己算：命令面板 `min(w-24,640) / min(h-96,480)`（`ui/palette/CommandPalette.kt:86-88`）、设置 `min(w-32,980) × min(h-92,600)`（`ui/settings/SettingsScreen.kt:154-165`）、服务器对话框 `min(w-32,480)`（`ui/settings/ServersPanel.kt:265`） | 近似：`FIT`/`LARGE` 语义对得上。差异：①**缺 `normal`（480×368）这一档** —— Android 的 `DialogSize` 没有 `NORMAL`，所有小对话框都被并到 `FIT`，而上游 `normal` 是生产默认值；②`variant="settings"` 无对应概念；③Android 把设置对话框实现成 `X_LARGE`（`ui/App.kt:585`、`ui/settings/SettingsScreen.kt:154-165`），但上游 `x-large` 在生产代码里**从未被使用**（仅 Storybook）——见下方 `/settings` 行 |

### B-2 通过全局栈打开的对话框

| 上游项 | 上游证据 (file:line) | Android 对应实现 (file:line) | 状态 |
|---|---|---|---|
| 命令面板（会话/新会话）`DialogCommandPalette` | 定义 `app/src/shell/commands/dialog.tsx:30`；`<Dialog class="command-palette" size="large">` `:125`；打开点：`session/commands/use-session-commands.tsx:166`（`file.open`）、`new-session/commands.tsx:29`（`command.palette`）、`session/composer/region.tsx:167-174` 把 `command.palette` 重定向到 `file.open`；空查询 = 常用命令 + 最近文件（`commands/dialog.tsx:32-35`），数据源 = 命令 + 会话 + `/find/file`（`:41-49`） | `CommandPalette` `ui/palette/CommandPalette.kt:68`；宿主层 `PaletteDialogLayer` `ui/App.kt:477`；打开点 = 全局 ⌘K/⌘⇧P 和弦 `ui/App.kt:418-430` → `showPaletteDialog(...PaletteHostKind.SESSION...)` `ui/App.kt:559-572`，`DialogSize.LARGE` `ui/App.kt:569`；数据由 `ChatViewModel.listSessions` + `searchFiles` 装配 `ui/App.kt:506-529` | 近似：几何/键盘/分组/高亮都对得上（`ui/palette/CommandPalette.kt:86-88,122-152,184-200`；模型 `ui/palette/PaletteModel.kt:109-199`）。差异：①**最近文件恒为空**（`ui/App.kt:526-528` 注释明说无数据源）；②选中 `file` 行**不做任何事**（`ui/App.kt:540-543`，注释写「文件预览属于 Review 面板」）；③`file.open` 命令**根本没有注册**（`ui/palette/AppCommands.kt:30-68` 只有 7 条，无 `file.open`），所以硬件键盘路径只能靠 ⌘K；④命令行的 keybind 是原始串直接渲染（`CommandPalette.kt:356-363`），没有上游 `Keybind` 组件的按键徽标（`commands/dialog.tsx:232`）；⑤文件行没有 `FileIcon`，只有自绘 `FileGlyph`（`CommandPalette.kt:372-383`） |
| 首页专用命令面板 `HomeCommandPalette`（占位符 `palette.search.placeholder.home`、**无 file 分类**、空查询 = 前 5 条命令） | `app/src/home/sessions/command-palette.tsx:14`；占位符 `:67`；空查询 `:55-58`；打开点 = `home/sessions/controller.tsx:111-120`（Home 路由自己注册 `command.palette`） | 模型层**已实现但不可达**：`PaletteHostKind.HOME` 只在 `ui/palette/PaletteModel.kt:202-234` 内被引用（占位符 `:212`、数据装配 `:226`），`ui/App.kt` 从不传 `HOME`——全局和弦只走 `PaletteHostKind.SESSION`（`ui/App.kt:421`）。字符串 `oc_palette_placeholder_home` 存在但无调用点 | 缺失（用户可见行为缺失）：在 Home 按 ⌘K 打开的是**会话版**面板（带 Files 分类与 `palette.search.placeholder`），上游 Home 打开的是无 file 分类的首页版 |
| 服务器新增/编辑对话框 `DialogServer`（`Dialog fit class="settings-server-dialog"`，`hideClose`，地址→名称→用户名/密码，Enter 提交，web 平台额外给扫码） | 定义 `app/src/servers/connect/dialog.tsx:39`；`<Dialog fit class="settings-server-dialog">` `:88`；`hideClose` `:89`；标题 key `dialog.server.add.title` / `dialog.server.edit.title` `:78-79`；提交按钮文案（含 `dialog.server.add.checking`）`:81-85`；扫码入口仅 web `:162`；打开点：`settings/shell.tsx:215`、`settings/servers/servers.tsx:29`、`home/projects/controller.tsx:84` | `AddEditServerDialog` `ui/settings/ServersPanel.kt:258`：字段顺序/默认用户名 `opencode`/Enter 提交/错误行都在（`:318-356`、`DEFAULT_SERVER_USERNAME` `ui/settings/SettingsModel.kt:397`）；**但它是面板内联渲染**（`ui/settings/ServersPanel.kt:109-125`），没有走 `dialog.show`；bridge 实现 `ui/settings/SettingsServerBridge.kt:18` | 近似：字段与文案对得上。差异：①**宿主不同** —— 上游/上游其它入口走全局栈，Android 内联在设置面板里，导致「首页项目右键编辑服务器」这类入口无法复刻（Android 也没有该入口）；②无健康检查/`checking` 态（`ui/settings/SettingsServerBridge.kt:14-16` 明写不做 health、恒 `UNKNOWN`）；③无扫码配对；④Android 增加了 CORS 提示行（`ServersPanel.kt:359-364`，上游没有该文案） |
| 供应商连接/鉴权对话框 `DialogConnectProvider` | 定义 `app/src/providers/connect/dialog.tsx:37`；`<Dialog>` `:77`（**无 size/variant**，用 `containerClass` 定 640×512 `:78`）；`<DialogHeader closeLabel>` `:81-96`；内嵌子面板 `CustomProviderForm` `app/src/providers/credentials/dialog.tsx:16`；打开点：`new-session/view.tsx:215`、`providers/models/unpaid.tsx:40`、`providers/models/select-dialog.tsx:538`、`providers/models/manage.tsx:31`、`session/usage-exceeded-dialogs.tsx:86`、`settings/providers/providers.tsx:45` | 无。设置里的供应商面板是**只读列表**（`ui/settings/SettingsPanels.kt:137-168`，空态文案 `oc_providers_empty`） | 缺失 |
| MCP 选择对话框 `DialogSelectMcp` | 定义 `app/src/providers/connect/mcp-dialog.tsx:17`；`<Dialog>` `:34`（默认 `normal` = 480×368）；标题 key `dialog.mcp.title` `:37`；打开点 `session/commands/use-session-commands.tsx:212`（命令 `mcp.toggle`，`:430-439`） | 无 | 缺失 |
| 会话 Fork 对话框 `DialogFork` | 定义 `app/src/session/commands/fork-dialog.tsx:27`；`<Dialog>` `:97`；标题 key `command.session.fork` `:99`；打开点 `session/commands/use-session-commands.tsx:244`（命令 `session.fork`，`:287-294`） | 无（`session.fork` 命令也没注册，`ui/palette/AppCommands.kt:30-68`） | 缺失 |
| 删除会话确认 `DeleteDialog`（`Dialog fit` + 标题组 + 取消/删除页脚） | 首页版定义 `app/src/home/sessions/controller.tsx:219`（`<Dialog fit>` `:226`，`DialogHeader hideClose` `:227`，标题 key `session.delete.title` `:229`，`DialogFooter` `:233`），打开点 `:310`；会话版定义 `app/src/session/timeline/controller.tsx:210`（`<Dialog fit>` `:219`，标题 `:222`），打开点 `:272`；行菜单入口 `home/sessions/view.tsx:699-701` | 无。会话头 ⋮ 只实现了 Rename（`ui/session/header/SessionHeader.kt:67-68` 枚举含 `DELETE`，但 `ui/session/SessionScreen.kt:277` 的 `onMenuAction = { }` 是空实现，注释 `:273-276` 明说 Delete/Share/Export/Archive 待后续里程碑）。首页会话行也没有右键/长按菜单 | 缺失 |
| 模型选择对话框 `DialogSelectModel`（`size="large" variant="settings"`） | 定义 `app/src/providers/models/select-dialog.tsx:530`；`<Dialog size="large" variant="settings">` `:549`；标题 key `dialog.model.select.title` `:551`；打开点 `composer/commands.tsx:45`（命令 `model.choose` `:51`）；该对话框内还能再打开「管理模型」`:253` 与「连接供应商」`:538` | 弹出层 `ModelPickerContent` `ui/session/composer/ModelPicker.kt:144`；入口 `ModelChip` `ui/session/composer/ModelChip.kt:84`（`ui/session/SessionScreen.kt:343-354` 接线）；搜索/归一化 `ModelPicker.kt:106-131` | 近似：都有可搜索的模型列表与「用服务器默认」项（`oc_model_picker_use_default` 等字符串齐备）。差异：①Android 是 composer 上的浮层，不是全局对话框层（`ui/App.kt` 里没有任何 `dialog.show` 打开模型选择）；②**没有**「管理模型 / 连接供应商」的下钻入口；③没有 agent/variant 维度 |
| 管理模型对话框 `DialogManageModels`（`size="large" variant="settings"`） | 定义 `app/src/providers/models/manage.tsx:23`；`<Dialog size="large" variant="settings" class="settings-manage-models-dialog">` `:62`；标题 key `dialog.model.manage` `:65`；打开点 `providers/models/select-dialog.tsx:253`、`:544` | 无 | 缺失 |
| 用量超限 / 未付费对话框 `DialogUsageExceeded` + `DialogSelectModelUnpaid` | `app/src/providers/connect/usage-exceeded.tsx:16`（`<Dialog fit>` `:33`）、`app/src/providers/models/unpaid.tsx:19`（`<Dialog fit containerClass="!h-auto max-h-… !w-[min(…,640px)]">` `:69-72`）；驱动器 `app/src/session/usage-exceeded-dialogs.tsx:45-93`（免费额度 `:73`、账户限流 `:93`）；另一入口 `composer/composer.tsx:41` | 无 | 缺失 |
| 目录选择（打开项目）`DirectoryPickerDialog`（`size="large"`） | 定义 `app/src/workspaces/selection/dialog.tsx:46`；`<Dialog size="large" class="directory-picker">` `:283`；标题 `props.title ?? command.project.open` `:285`；页脚 `:372`；打开点 `workspaces/selection/picker.tsx:38`（`useDirectoryPicker`），调用方另有 `servers/ssh/dialog.tsx:27`、`home/projects/controller.tsx:50`（`command.project.open`） | `DirectoryPickerDialog` `ui/home/DirectoryPickerDialog.kt:57`；打开点 `ui/home/HomeScreen.kt:112`、`ui/home/NewSessionScreen.kt:120`，都是 `DialogSize.FIT` | 近似：导航语义一致（服务端 cwd 起步、只列目录、底部当前绝对路径、确认回填）。差异：①尺寸 `FIT` vs 上游 `large`；②Android 没有 git worktree/分支创建与选择（上游对话框内嵌工作区选择）；③Android 增加了 `HomeProjects.parentOf` 的上级目录推导（`ui/home/HomeProjects.kt:88-106`） |
| SSH 服务器对话框 `DialogSsh`（`Dialog fit class="settings-server-dialog"`，主机密钥确认等） | 定义 `app/src/servers/ssh/dialog.tsx:41`；`<Dialog fit>` `:112`；打开点 `servers/ssh/context.tsx:66`、`servers/wsl/settings.tsx:76`、`shell/commands/desktop.tsx:29`（命令 `server.ssh.add` `:26`） | 无 SSH 概念：Android 的 `ServerConfigStore.Server` 只有 `url/username/password`（`ui/settings/SettingsServerBridge.kt:44-48`），连接方式是 http + Basic auth | 缺失（非桌面专属：SSH 是一种远端服务器连接方式，Android 端的空缺点在于 `ServerConfigStore` 只支持 http） |
| WSL 服务器对话框 `DialogAddWslServer` / `DialogWslSetup` | 定义 `app/src/servers/wsl/dialog.tsx:32`、`:382`；打开点 `servers/wsl/settings.tsx:31` | 无 | 不适用：WSL（Windows Subsystem for Linux 发行版探测/启动）需要 Windows 宿主机（`app/src/servers/wsl/probes.ts`、`servers/wsl/model.ts`），Android 设备上没有该宿主 |
| 更新说明 / 稳定版下载对话框 `DialogReleaseNotes` / `DialogStableDownload` | `app/src/shell/updates/release-notes.tsx:18`（`<Dialog size="large" fit class="w-[min(…,720px)] h-[min(…,400px)] …">` `:64-68`；标题取自数据 `:75`，**无 i18n 标题 key**）；`app/src/shell/updates/download.tsx:31`（`<Dialog fit>` `:40`，标题 key `settings.updates.migration.title` `:43`）；打开点 `shell/updates/highlights.tsx:200`、`shell/updates/download.tsx:27` | 无 | 不适用：自更新只在桌面平台提供 —— `platform.updater?: UpdaterPlatform`（`app/src/runtime/platform/platform.tsx:77`），消费方 `shell/shell.tsx:37`、`shell/titlebar/titlebar.tsx:99-111`、`shell/errors/error.tsx:255-346`。Android 通过 APK 分发，无自更新通道 |
| 删除 Workspace 确认 `DialogDeleteWorkspace` / `DialogDeleteWorkspaces` | 定义 `app/src/settings/workspaces/workspaces.tsx:554`（`<Dialog fit>` `:582`，标题 key `workspace.delete.confirm` `:585`）、`:515`（`<Dialog fit>` `:530`，标题 `props.title` `:533` ← `settings.workspaces.deleteAll` `:304` / `settings.workspaces.deleteWithoutSessions` `:321`）；打开点 `:279`、`:301`、`:318` | 无 Workspace 概念与界面 | 缺失 |
| 图片预览 `ImagePreview`（全屏灯箱） | UI 组件定义 `_upstream/packages/ui/src/components/image-preview.tsx:11`（根 `data-component="image-preview"` `:14`，**不带 `<Dialog>` 外壳**，直接裸用 Kobalte `Content` `:16`）；打开封装 `_upstream/packages/session-ui/src/components/image-preview.tsx:15`（`dialog.show`）；打开点 `session-ui/src/message/message-content.tsx:269`、`session-ui/src/components/markdown.tsx:398`、`session-ui/src/tools/tool-renderer.tsx:1370`、`app/src/composer/model.ts:328` | 无全屏预览。消息里的附件只渲染成 `AttachmentChip`（`ui/session/timeline/PartRenderer.kt:446`） | 缺失 |
| Toast 区域 `ToastRegion` + 上传 Toast 宿主 `UploadToastHost` | `app/src/shell/shell.tsx:125`（`<ToastRegion />`）、`:126`（`<UploadToastHost />`）；定义 `app/src/shell/notifications/toast.tsx:9`、`app/src/composer/attachments/uploads.tsx` | 无应用内 Toast。Android 用系统 `android.widget.Toast` 零散提示：`ChatView.kt:152/485/511`、`MainActivity.kt:123/585/711`、`ui/session/composer/AttachmentPicker.kt:55/63`、`ui/session/composer/ModelChip.kt:108` | 缺失（作为 overlay） |
| 移动端标题栏抽屉 `MobileDrawer` / `MobileDrawerContent` | 定义 `app/src/shell/mobile-drawer.tsx:6`、`:31`；使用方 `shell/titlebar/titlebar.tsx:28` | 无等价物（Android 的抽屉是会话列表，见下） | 缺失 |
| 移动端面板抽屉 `MobilePanelDrawer`（带标题 + 关闭按钮的面板容器） | 定义 `app/src/shell/mobile-panel-drawer.tsx:7`（`data-slot="mobile-panel"` `:25`）；使用方 `session/review/view.tsx:105-112`（会话详情） | 无 | 缺失 |
| SSH 认证覆盖层 `SshAuthentication` | `app/src/shell/shell.tsx:115-117`；定义 `app/src/servers/ssh/authentication.tsx:13` | 无 | 缺失 |
| 调试面板 `DebugBar` | `app/src/shell/debug/debug-bar.tsx:179`；挂载 `shell/shell.tsx:120-124`（`lazy` + `diagnostics={import.meta.env.DEV}`）；开关命令 `debugBar.toggle` `shell/shell.tsx:49-56` | 无 | 缺失（开发态专用面板；上游即使在非 DEV 构建也可通过命令显示，Android 无对应 dev 面板） |
| Composer 拖放区 `ComposerDropzone` | `app/src/composer/dropzone.tsx`；挂载 `session/screen.tsx:285-289`、`new-session/view.tsx:87-90` | 无拖放；Android 用 SAF 选择器（`ui/session/composer/AttachmentPicker.kt:36`） | 不适用：HTML5 drag & drop 是鼠标/指针交互（上游 `composer/dropzone.tsx` 的 `active={...drag === "active"}` 依赖 DOM DnD），触屏端由系统文件选择器取代 |

---

## 表 C — 页面与区域

### C-1 应用外壳

| 上游项 | 上游证据 (file:line) | Android 对应实现 (file:line) | 状态 |
|---|---|---|---|
| 外壳布局（标题栏 + 主区 + Toast + DebugBar + safe-area 变量） | `app/src/shell/shell.tsx:19-130`；`--shell-top-inset` / `--shell-bottom-inset` `:63-72` | `AppScaffold` 的 `Column`（`ui/App.kt:255-383`）+ `windowInsetsPadding(WindowInsets.safeDrawing)`（`ui/home/HomeScreen.kt:129`、`ui/session/SessionScreen.kt:243`） | 近似：分层对得上，但 Android 没有标题栏组件，安全区靠各页自己加 |
| 标题栏 `Titlebar`（前后退历史、标签区、更新胶囊、品牌图标、移动端抽屉触发） | `app/src/shell/titlebar/titlebar.tsx:52`；返回/前进命令 `:131-146`；移动端底部位置判断 `:65`；桌面三平台判断 `:67-72` | 无标题栏。导航用 Compose `NavHost` + 系统 Back（`ui/App.kt:257`、`ui/session/header/SessionHeader.kt:114-119` 的返回箭头） | 不适用（浏览器式前进/后退历史是 web/桌面窗口 chrome；Android 用系统返回栈。证据：上游历史栈 `shell/titlebar/history.ts` + `titlebar.tsx:81-129`） |
| 标签条 `TitlebarTabStrip` / `TabNavItem` / 标签预览 `TabPreviewPopover` / 草稿标签 `DraftTabItem` | 定义 `shell/titlebar/tab-strip.tsx:234`、`shell/titlebar/tab-nav.tsx:25`、`:371`、`shell/titlebar/tab-popover.tsx:24` | `BottomTabStrip` `ui/nav/BottomTabStrip.kt:56`（仅当 `appearance.tabLayout == "vertical"` 才出现：`ui/App.kt:148-149`、`OcActivity.kt:62`、`ui/nav/AppearancePrefs.kt`） | 近似（差异明显）：①上游标签条是多标签 + 拖拽排序 + 关闭 + 悬停预览 + 草稿标签（`shell/tabs/*`、`shell/titlebar/tab-order.ts`、`tab-gesture.ts`），Android 只显示「当前这一个会话」（`ui/nav/BottomTabStrip.kt:48-53` 注释明确记录该收窄）；②Home 在 Android 是按钮不是标签；③`+` 直接新建会话、无菜单 |
| （移动端）底部标题栏 | `shell/shell.tsx:33`（`mobile() && mobileTitlebarPosition() === "bottom"`）、`shell/titlebar/titlebar.tsx:65` | `BottomTabStrip`（同上）；位置固定底部（`ui/App.kt:359-381`） | 近似：都有「移动端底部标签栏」这一形态，但内容规模差异同上 |
| 垂直标签侧栏（可拖拽宽度 140–520） | `shell/shell.tsx:80-99`（`verticalTabs()` 受 `!mobile()` + `tabLayout === "vertical"` 门控，`:32`） | 无 | 不适用：桌面专属，上游自身用 `createMediaQuery("(max-width: 767px)")` 排除移动端（`shell/shell.tsx:26,32`） |
| Windows 原生应用菜单 `WindowsAppMenu` | `shell/titlebar/windows-menu.tsx:28`；使用 `shell/titlebar/titlebar.tsx:15` | 无 | 不适用：Windows 原生标题栏菜单（`@tauri`/native overlay） |
| 通知/未读体系（`useNotification`、声音、`shell/notifications/*`） | `shell/notifications/notification.tsx:118`（`createServerNotificationState`，`useNotification` `:367`；持久化 Schema `:30`；未读索引 `:88`；`session.execution.*` 订阅 `:283`；系统通知 `platform.notify` `:245`/`:275`）、`sound.ts`、`coordinator.ts`；会话进入时标记已读 `session/route.tsx:187-195`；会话行未读徽标 `home/sessions/view.tsx:201-227` | 无未读/通知/声音逻辑（`grep` 未在 Android 找到 notification/sound 相关实现；设置里的「通知」「声音」只是开关行 `ui/settings/SettingsModel.kt:209-251`） | 缺失 |

### C-2 Home

| 上游项 | 上游证据 (file:line) | Android 对应实现 (file:line) | 状态 |
|---|---|---|---|
| Home 页面骨架（bg-base 卡片、`max-w-[1080px]` 居中、桌面双列 280+720） | `app/src/home/route.tsx:13-61`（卡片 `:21-26`；桌面双列 `:40-50`；移动端左下 `HomeProjects dropdown` `:27-31`；工具导航 `:52-59`） | `HomeScreen` `ui/home/HomeScreen.kt:59`（卡片 `:131-139`、`CONTENT_MAX_WIDTH=1080` `:208`、`LG_BREAKPOINT=1024` `:211`、`PROJECTS_WIDTH=280` `:214`）；`HomeScreen.kt:148-198` 桌面/移动分支 | 近似：骨架与断点对得上。差异：桌面分支用 `maxWidth >= 1024dp` 而不是上游的 `lg:` 容器查询 + `md` 中间态；移动端没有顶部 `dropdown` 形态的项目栏（上游 `home/route.tsx:27-31`，Android 移动端直接隐藏项目列，`HomeScreen.kt:179-197`） |
| Home 项目列 `HomeProjects` / `HomeProjectsView` | `app/src/home/projects/region.tsx:5`、`app/src/home/projects/view.tsx:74`（`HomeProjectsPanel` `:153`、`HomeServerRow` `:329`、`HomeProjectRow` `:624`、`HomeProjectEmpty` `:564`、`HomeRecentlyClosedRow` `:595`、`HomeProjectNavButton` `:779`） | `HomeProjectsColumn` `ui/home/HomeProjectsList.kt:54` | 近似（差异明显）：上游有**多服务器行 + 服务器折叠/编辑/默认/隐藏/移除（`data-action="home-server-collapse"` `view.tsx:384`、`"home-server-authenticate"` `:263`）、项目拖拽排序（`@dnd-kit`，`HomeProjectList` `view.tsx:493`）、项目右键菜单（`data-action="home-project-menu"` `:726`，含新建/导入/编辑/在文件管理器打开/清除通知/关闭 `:734-762`）、未读徽标**（`home/projects/view.tsx:1-26,34-72`、`servers/registry/row-menu.tsx`、`servers/registry/row.tsx`）；Android 只有单服务器 + 项目行 + 行尾 ✎，且**显式放弃**拖拽排序、右键菜单、`recently closed` 的恢复动作（`ui/home/HomeProjectsList.kt:43-51` 注释逐条列出） |
| Home 最近关闭项目 `HomeRecentlyClosed` | `home/projects/view.tsx`（`recentlyClosed` prop `:39`；`HomeProjectEmpty` 内 `:585`；`HomeRecentlyClosedRow` `:595`）；数据 `home/model.ts` | 标题行 + 行的静态渲染 `ui/home/HomeProjectsList.kt:113-130`；数据源恒为空 `HomeProjects.RECENTLY_CLOSED`（`ui/home/HomeScreen.kt:152`） | 近似：有 UI 槽位但**永远是空的**（无数据源，`HomeProjectsList.kt:46-47` 注明），恢复动作也没有 |
| Home 会话列 `HomeSessions` / `HomeSessionsView`（粘性搜索 + 悬浮新建 + Today/Yesterday/Older 分组 + 骨架 + 空态） | `app/src/home/sessions/region.tsx:7`、`app/src/home/sessions/view.tsx:92`（分组 `:148-170`、骨架 `:792-803`、空态 `:762-790`） | `HomeSessionsColumn` `ui/home/HomeSessionsList.kt:69`（分组 `ui/home/HomeSessions.kt` → `HomeSessions.sections`、骨架 `HomeSessionSkeleton` `:119`、空态 `HomeSessionsEmpty` `:505`） | 近似：结构齐备。差异：①悬浮新建按钮 Android 是**纯图标 28dp**（`HomeSessionsList.kt:581-597`），上游是**带文字的 ghost 按钮**（`home/sessions/view.tsx:107-117` 含 `command.session.new` 文案 + `h-7 px-2`）；②上游的归档槽位默认关闭（`home/sessions/view.tsx:27` `SHOW_HOME_SESSION_ARCHIVE = false`、`:705-727`），Android 同样保留占位不实现（`HomeSessionsList.kt:396-397`）→ 此项**不算差异**；③上游有滚动渐隐的组标题（`titleOpacity`，`view.tsx:151-156`），Android 有 `MIN_HEADER_OPACITY`（`HomeSessionsList.kt:642`） |
| Home 会话行 `HomeSessionRow`（重命名内联输入、右键/长按菜单：Rename / Export / Delete） | `home/sessions/view.tsx:443-730`；菜单项 `:694-701`；长按 500ms `:30,:597-609` | 行渲染 `ui/home/HomeSessionsList.kt`（行组件在 `:230-498` 区间）；**无行菜单、无内联重命名、无导出/删除**（`grep` 未命中 Home 行菜单；改名只在会话头 `ui/session/header/SessionHeader.kt:95-171`） | 近似（差异明显）：Android 的 Home 行只能打开 |
| Home 会话搜索（服务端 `session.list({search, limit:50})`、Sessions 分组标题、无结果文案、方向键/回车/Esc、`alt/meta` 忽略） | `home/sessions/view.tsx:229-366`；键盘 `:323-346`；数据 `home/sessions/search.ts` | `HomeSearchField` `ui/home/HomeSessionsList.kt:162`、结果面板 `HomeSessionSearchPanel` `:231`、`HomeSessions.filter` 本地过滤 | 近似：UI/键盘/文案都对得上（`oc_home_search_group`/`oc_home_search_no_results` 已存在）。差异：Android 是**本地过滤已加载列表**，上游是**服务端搜索**（`view.tsx:32` 的 `load: (search, signal) => session.list(...)`）；因此 Android 搜不到未加载的历史会话 |
| Home 工具导航 `HomeUtilityNav`（Settings / Help 两行，两处渲染） | `home/projects/view.tsx:299`；桌面挂载 `home/route.tsx:52-59`；Help 打开外链 `home/projects/controller.tsx:181`（`https://opencode.ai/desktop-feedback`） | `HomeUtilityNav` `ui/home/HomeUtilityNav.kt:50`（桌面列内 `ui/home/HomeProjectsList.kt:136-139`，移动端 `ui/home/HomeScreen.kt:192-195`） | 近似：两行/图标/几何对得上。差异：**Help 不打开任何东西**，只在行内提示「后续里程碑提供」（`HomeUtilityNav.kt:78-83`、字符串 `oc_home_help_soon`） |

### C-3 New session（草稿页）

| 上游项 | 上游证据 (file:line) | Android 对应实现 (file:line) | 状态 |
|---|---|---|---|
| 草稿页整体（raised 卡片、`top-[25.375%]` 内容、`NEW_SESSION_CONTENT_WIDTH`） | `app/src/new-session/screen.tsx:16-94`、`app/src/new-session/view.tsx:78-166`（内容定位 `:110`，宽度常量 `new-session/layout.ts`） | `NewSessionScreen` `ui/home/NewSessionScreen.kt:72`（`TOP_FRACTION = 0.25375f` `:391`、`CONTENT_MAX_WIDTH = 880.dp` `:394`） | 近似：定位/宽度/composer 位置一致。差异：Android **没有 raised 卡片**（背景是 `bgDeep`，`NewSessionScreen.kt:142`），上游是 `bg-base + elevation-raised`（`view.tsx:85`） |
| 品牌 wordmark（`Logo` SVG + shimmer 两层） | `app/src/new-session/wordmark.tsx:4-16`（`<Logo class="... opacity-[0.16]">` + `.wordmark-shimmer`），样式 `new-session/wordmark.css` | 无 Logo 资源，改用一段 28sp 文字（`ui/home/NewSessionScreen.kt:156-164`），文案取 `oc_timeline_empty` = "Build anything" | 近似（差异明显）：上游是图形 wordmark，Android 是文字；且该文案在上游是未被任何 `.tsx` 引用的键 `session.new.title`（`packages/app/src/runtime/i18n/en.ts:834`），同一个键在 Android 还被会话空态复用（`ui/session/SessionScreen.kt:301`） |
| 项目选择器 `PromptProjectSelector` + 空态 `PromptProjectAddButton` | `app/src/new-session/project/selector.tsx:194`（`PromptProjectSelector`）、`:439`（`PromptProjectAddButton`）；使用 `new-session/view.tsx:119-124` | `ProjectSelectorRow` `ui/home/NewSessionScreen.kt:248`（下拉菜单 + 底部「添加项目」`：310-386`） | 近似：都能选项目/添加目录、都带项目头像。差异：Android 是 Material `DropdownMenu` 且无 `placement="bottom"` 定位概念；上游空态是独立按钮（`view.tsx:119-121`），Android 用「默认目录（服务器 cwd）」占位（`:294`、字符串 `oc_newsession_default_directory`） |
| 工作区/分支选择器 + git 状态 `PromptWorkspaceSelector` / `PromptGitStatus` | `app/src/new-session/workspace/selector.tsx:11`（`PromptWorkspaceSelector`，引导点 `data-slot="workspace-onboarding-dot"` `:122`）、`:374`（`PromptGitStatus`）；使用 `new-session/view.tsx:122-150`；控制器 `new-session/workspace/controller.ts` | 无 | 缺失 |
| 会话详情摘要 popover `SummaryPopover` + `NewSessionSummary`（右上角 48dp 条） | `app/src/session/summary/popover.tsx:11`（Anchor `:34`）；`app/src/new-session/summary.tsx:11`（lazy 于 `new-session/view.tsx:30-33`）；挂载 `new-session/view.tsx:91-109`（`data-slot="new-session-summary"` `:92`） | 无 | 缺失 |
| 草稿页提示条（provider / workspace tip，可关闭，30 天节流） | `app/src/new-session/view.tsx:168-268`（`data-component="new-session-tip"` `:233`；文案 key `home.workspaceTip` / `home.providerTip` `:244`；节流常量 `:35`） | 无 | 缺失 |
| 草稿提交（首个 prompt 提交后提升为真实 session） | `new-session/composer-adapter.ts`、`new-session/mcp.ts`；路由守卫 `new-session/route.tsx:20-29` | `ChatViewModel.createSession(directory, firstPrompt, attachments)`，成功后 `onCreated(id)` → 导航到 `Route.SESSION` 并 `popUpTo(NEW_SESSION)`（`ui/home/NewSessionScreen.kt:182-199`、`ui/App.kt:298-305`） | 近似：语义一致（提交时才创建）。差异：Android 直接 `POST` 后跳转，上游是 app 级 `DraftTab` 提升 + tab 守恒 |

### C-4 Session 页

| 上游项 | 上游证据 (file:line) | Android 对应实现 (file:line) | 状态 |
|---|---|---|---|
| 会话页布局（chat 面板 + 侧栏 presence + 底部终端 + `md:flex-row`） | `app/src/session/screen.tsx:52-544`（chat 面板 `:349-395`、侧栏 presence `:397-504`、底部终端 `:507-540`、移动端视图 `:191-242`） | `SessionScreen` `ui/session/SessionScreen.kt:92`（时间线 `:246-297`、composer `:307-363`、review 侧栏 `:367-389`） | 近似（结构简化）：Android 无面板 presence 动画/宽度拖拽（`session-panel-width.ts`）、无底部终端槽、无 `md:` 双栏断点（侧栏固定 340dp，`SessionScreen.kt:443`） |
| 会话身份头 `SessionIdentityHeader` + 项目菜单 `SessionProjectMenu` | `app/src/session/session-identity-header.tsx:207`、`:38`；样式 `session-identity-header.css` | `SessionHeader` `ui/session/header/SessionHeader.kt:95`（标题 + 内联重命名 + 上下文环 + ⋮ `:107-171`） | 近似：单行粘性头 + 标题 + 环 + ⋮ 对得上（几何见 `SessionHeader.kt:70-92`）。差异：**没有项目切换菜单**（上游 `SessionProjectMenu`），Android 用 ☰ 打开会话抽屉（`SessionHeader.kt:87-89` 注明这是 Android 增设） |
| 上下文用量环 `SessionContextUsage` | `app/src/session/timeline/session-context-usage.tsx:40`；使用 `session/screen.tsx`（会话头区域） | `ContextRing.kt:38` + 计算 `ui/session/header/ContextUsage.kt:29-47`；接线 `ui/session/SessionScreen.kt:272`（`limit = null`） | 近似（功能缺口）：公式与上游一致（`ContextUsage.kt:12-17` 抄录上游），但 Android **永远传 `limit = null`**，`percentOf` 返回 `null` → 环永远是空轨道（`ContextUsage.kt:24-27` 注明需要 `GET /config/providers`） |
| 上下文用量 Tab `SessionContextTab`（桌面侧栏 `context` 标签 **与** 移动端 `usage` 视图） | `app/src/session/files/session-context-tab.tsx:85`（内容 `data-slot="session-usage-content"` `:276`）；挂载 `session/screen.tsx:307-309`（移动端 `mobileView() === "usage"`）与 `session/files/session-side-panel.tsx:581`（桌面侧栏） | 无（只有头部的用量环，见上一行） | 缺失 |
| 桌面右侧面板骨架 `SessionSidePanel`（`#review-panel`：文件/评审/浏览器/上下文字签条 + 打开文件标签 + 文件树「变更/全部」+ 宽度拖拽 + `OpenInAppButton`） | 定义 `app/src/session/files/session-side-panel.tsx:61`（`<aside id="review-panel">` `:258`，`data-slot="session-review-content"` `:276`；标签条 `:316-550`；文件树面板 `:634-724`；宽度 `ResizeHandle` `:708-722`；`OpenInAppButton` `:545`）；`isDesktop = createMediaQuery("(min-width: 768px)")` `:87`，门控 `:90,:94,:103,:257` | Android 只有 `SessionReviewPanel` 的「变更 / 文件」两个 tab（`ui/session/review/ReviewPanel.kt:41,129-165`） | 近似（差异明显）：Android 没有「把文件开成标签页」的文件标签条、没有文件树 `变更/全部` 切换、没有浏览器标签、没有侧栏宽度拖拽、没有 `OpenInAppButton`；上下文用量只有头部环，没有侧栏 context 标签 |
| 评审内容视图 `SessionDesktopReview` / `ReviewPanel` / `ReviewPanelSidebar` / `SessionReviewTab` | `app/src/session/review/view.tsx:127`、`app/src/session/review/panel.tsx:59`（`ReviewPanelView` `:74`，`ReviewPanelSidebar` `:185`）、`app/src/session/review/review-tab.tsx:50`；挂载 `session/screen.tsx:441`（桌面）、`session/screen.tsx:316`（移动 `SessionReview`） | `SessionReviewPanel` `ui/session/review/ReviewPanel.kt:65`（CHANGES/FILES 两 tab `:41`、切换 `:129-165`）；开关按钮 `ui/session/SessionScreen.kt:281-287`、`ReviewToggleButton` `:419` | 近似：两 tab（变更/文件）+ diff hunks + 文件树 + 文本预览都在（`ReviewChangesTab.kt:55`、`ReviewFilesTab.kt:62`），vcs 分支 chip 在（`ReviewPanel.kt:102-118`）。差异：无文件标签页、无 `changes/all` 文件树切换、无侧栏宽度拖拽与 presence 动画（细节见上一行）；上游桌面挂载点的评审开关由 `SessionHeaderActions`/`SessionReviewToggle` 提供（`session/header/session-header-actions.tsx:11,37`），并为它预留 `SessionHeaderSpacer`（`session/header/session-header.tsx:4`，仅桌面 `:8`） |
| 移动端会话视图标签（Session / Changes / Files / Terminal + ⋮ → Usage / Session details） | `app/src/session/review/view.tsx:23-117`（`data-slot="session-mobile-view-navigation"` `:42`；`⋮` 菜单 `:66-102`） | 无。Android 只有头部的 review 开关（`ui/session/SessionScreen.kt:281-287`）+ 独立终端路由 | 缺失 |
| 移动端文件浏览 `SessionMobileFiles` | `app/src/session/files/session-mobile-files.tsx:14`；使用 `session/screen.tsx:310-314` | 无（只有侧栏内的 `ReviewFilesTab`） | 缺失 |
| 会话摘要面板 `SessionSummaryPanel`（项目卡片 / 服务器面板 / 后台任务 / 移动端项目详情） | `app/src/session/summary/panel.tsx:15`、`summary/project-card.tsx:10`、`summary/server-panel.tsx:50`、`summary/background.tsx:17`；使用 `session/screen.tsx:191-242` | 无 | 缺失 |
| 时间线 `MessageTimeline`（虚拟化、滚动锚定、pin/unpin、消息导航） | `app/src/session/timeline/message-timeline.tsx:106`；虚拟化 `timeline/virtualizer.tsx`、`virtual-items.ts`；交互 `timeline/interaction.ts` | `MessageTimeline` `ui/session/timeline/MessageTimeline.kt:65`；分组 `ui/session/timeline/TimelineGrouping.kt`；卡片 `PartRenderer.kt:71` | 近似：消息渲染/工具卡/分组/空态都在。差异：无虚拟化与滚动锚定恢复、无 `message.previous/next` 键盘导航（上游命令 `:411-428`）、无 `BackgroundMoveHint`（`message-timeline.tsx:55`） |
| 时间线搜索栏 `TimelineSearchBar` + 控制器 | `app/src/session/timeline/search-bar.tsx:7`（`data-component="timeline-search-bar"` `:12`，计数 `data-slot="timeline-search-count"` `:53`，关闭 `:59-67`）、`timeline/search-controller.ts`；接线 `session/screen.tsx:63-68,273-277` | 无 | 缺失 |
| 待发送队列面板 `SessionQueuePanel` | `app/src/session/composer/queue-panel.tsx:18`（`data-component="session-queue-panel"` `:25`，行 `data-component="session-queue-row"` `:109`，操作 `data-slot="session-queue-actions"` `:151`）；接线 `session/composer/region.tsx:222` | 无可见队列 UI；只有「运行中且有内容 → 保持发送按钮（排队）」的纯逻辑（`ui/session/composer/ComposerSubmit.kt:10-35`） | 缺失（可见界面） |
| 权限请求 dock `SessionPermissionDock` | `app/src/session/requests/session-permission-dock.tsx:8` | `SessionPermissionDock` `ui/session/dock/SessionPermissionDock.kt:73`；接线 `ui/session/SessionScreen.kt:335-342`；工具名映射 `:285-288` | 近似（字段/操作层面）：once / always / reject 三档与工具类型标签齐备（字符串 `oc_permission_allow_once`/`_allow_always`/`_reject` + 各工具名）。**未确认**：上游 ask 模式的全部分支（如外部目录、doom-loop 的专用文案）未逐条对照，不作为差异结论 |
| 提问 dock `SessionQuestionDock` | `app/src/session/requests/session-question-dock.tsx`（同目录） | `SessionQuestionDock` `ui/session/dock/SessionQuestionDock.kt:78`；接线 `ui/session/SessionScreen.kt:322-330` | 近似：单选/多选/自定义输入/提交/驳回都有（`SessionQuestionDock.kt:315-476`，字符串 `oc_question_*`）。**未确认**：上游请求树的全部状态（`requests/session-request-tree.ts`）未逐条对照 |
| WebSearch 请求 dock `SessionWebSearchDock` | `app/src/session/requests/session-websearch-dock.tsx:10`；样式 `session-websearch-dock.css` | 无 | 缺失 |
| 后台任务/`BackgroundMoveHint`（阻塞请求移到后台） | `app/src/session/timeline/message-timeline.tsx:55`；命令 `session.background` `use-session-commands.tsx:280-286` | 无 | 缺失 |
| 会话工作区切换菜单 `SessionWorkspaceMenu`（worktree / 分支） | `app/src/session/timeline/session-workspace-menu.tsx:15` | 无 | 缺失 |
| 会话准备中页面 `PreparingSession` / `PreparingComposer` | `app/src/session/route.tsx:53-93`、`app/src/session/preparing-composer.tsx:8` | 无 | 缺失 |
| 服务器不兼容面板 `IncompatibleServerPanel` | `app/src/session/incompatible-server-panel.tsx:8`；使用 `session/route.tsx:128-136`、`new-session/route.tsx:53-66` | 无 | 缺失 |
| 路由级错误回退 `SessionErrorFallback` + 应用级 `ErrorPage` | `app/src/session/route-error.tsx:11`（使用点 `:32`）；`app/src/shell/errors/error.tsx:222`（经 `app.tsx:22` 导入、`:77-82` 的 `ErrorBoundary` 包裹） | 时间线错误卡 `SessionErrorCard` `ui/session/timeline/SessionErrorCard.kt:67`（接线 `ui/session/SessionScreen.kt:295`）；**无路由级/应用级 fallback** | 近似（会话内错误提示对得上；应用级/路由级兜底缺失） |
| 会话浏览器面板 `SessionBrowserPane` | `app/src/session/browser/pane.tsx:16`；命令 `browser.reload` `:47` | 无 | 缺失 |
| 终端面板 `TerminalPanel`（会话页的 side / bottom 两种停靠 + 多 PTY + 可拖拽标签） | `app/src/session/terminal/panel.tsx:39`；停靠判定 `session/screen.tsx:100-101`；底部终端槽 `:507-540`；标签 `session/terminal/tab.tsx:12` | `TerminalPanelHost` `ui/terminal/TerminalPanel.kt:104`；**独立路由** `Route.TERMINAL` `ui/App.kt:121` + `composable` `:341-356`；会话页入口为 28dp 幽灵 chip `TerminalEntryButton` `ui/App.kt:332-337` / `TerminalPanel.kt:706`；标签模型 `ui/terminal/share/TerminalTabs.kt` | 近似（宿主不同）：PTY 数据链路与标签都在。差异：①上游把它作为**会话页的面板**（side/bottom + 高度拖拽 + 收起阈值），Android 是**独立路由页面**（`ui/terminal/TerminalPanel.kt:91-95` 记录该收窄决策）；②Android 标签无拖拽排序；③`terminal.new` / `terminal.close` 命令未注册（`ui/palette/AppCommands.kt:30-68` 只有 `terminal.toggle`） |
| 会话内「打开方式」`OpenInAppButton` / `OpenInAppContextMenuV2`（VSCode / Android Studio / Sublime / Finder / PowerShell …） | `app/src/session/files/open-in-app-button.tsx:11`、`:140`；应用表 `session/files/open-in-app.tsx:41-97` | 无 | 不适用：这是桌面端「用本机编辑器/文件管理器打开远端路径」的能力（应用清单全部是桌面程序，且依赖 `platform.revealPath`，见 `session/composer/region.tsx:82-96`） |
| 首页项目「在文件管理器中显示」`fileManagerApp` | `app/src/home/projects/file-manager.ts`；使用 `home/projects/view.tsx:25` | 无 | 不适用（同上，桌面文件管理器） |

### C-5 Composer

| 上游项 | 上游证据 (file:line) | Android 对应实现 (file:line) | 状态 |
|---|---|---|---|
| Composer 主体 `Composer` + 会话内区域 `SessionComposerRegion` | `app/src/composer/composer.tsx`；`app/src/session/composer/session-composer-region.tsx:18`；接线 `session/composer/region.tsx:216-229` | `Composer` `ui/session/composer/Composer.kt:86`；接线 `ui/session/SessionScreen.kt:343-362`、`ui/home/NewSessionScreen.kt:166-201` | 近似：输入、发送/停止、附件、模型 chip、@ 提及、占位符都在（`Composer.kt:82`、`MentionPopover.kt:47`、`AttachmentPreviewRow.kt:70`）。差异：无斜杠命令、无 agent 选择、无 `borderUnderlay`、无拖放、无队列面板 |
| Composer 命令：`model.choose` / `model.variant.cycle` / `agent.cycle` / `agent.cycle.reverse` | `app/src/composer/commands.tsx:51,59,66,75` | 仅 `model.choose` 有等价入口（`ModelChip.kt:84` → `ModelPicker.kt:144`）；variant / agent 维度无实现 | 近似（`model.choose` 对得上）：`model.variant.cycle`、`agent.cycle`、`agent.cycle.reverse` 缺失 |
| 会话命令全集（session.new/undo/redo/compact/background/fork/export/copyID、project.copyID、file.open/close、context.addSelection、terminal.toggle、review.toggle、fileTree.toggle、input.focus、terminal.close/new、message.previous/next、mcp.toggle、permissions.autoaccept） | `app/src/session/commands/use-session-commands.tsx:248-463` | `appCommands(...)` `ui/palette/AppCommands.kt:22-68`：只有 `settings.open`、`session.new`、`workspace.new`、`session.previous`、`session.next`、`terminal.toggle`、`review.toggle`；其中 `session.new`/`workspace.new`/`session.previous`/`session.next`/`review.toggle` 的 `onSelect` 是**空实现**（`:41,:46,:51,:56,:67`） | 近似（命令表存在但动作缺失）：只有 `settings.open`（`ui/App.kt:493`）与 `terminal.toggle`（`ui/App.kt:62`）真正生效；`session.new` 按了没反应，尽管 `Route.NEW_SESSION` 已有 |
| 命令面板的按路由注册（`command.register("session"/"session-palette"/"home.palette"/"new-session"/"settings")`） | `shell/commands/command.tsx`、`session/composer/region.tsx:167`、`home/sessions/controller.tsx:111`、`new-session/commands.tsx:19-56`、`settings/command.tsx:16` | 单一 app 级列表（`ui/palette/AppCommands.kt:6-20` 注释记录该合并） | 近似：合并本身被显式记录为过渡方案；后果是上游「同一 id 在不同路由有不同动作」（如 `command.palette` 在会话页被重定向到 `file.open`）无法表达 |
| 快捷键表（可录制/回放、按 `groupFor(id)` 分组） | `app/src/settings/keybinds/keybinds.tsx:343`、`:367` | `ShortcutsPanel` `ui/settings/SettingsPanels.kt:113`（只读 `KeybindChip`）；行表 `ui/settings/SettingsModel.kt:334-341`（**6 行**：command.palette / settings.open / file.open / session.new / terminal.toggle / prompt.submit）；分组 `:288-325` | 近似：分组规则与上游 `groupFor` 一致（`SettingsModel.kt:311-325`）。差异：①只有 6 行 vs 上游完整命令表（`use-session-commands.tsx` 就 20+ 条）；②无录制/回放（`ui/settings/SettingsPanels.kt:124-126` 注释说明是占位） |

### C-6 设置

上游 `/settings` 是一个**路由 surface**，拥有 3 层视图：`root`（12 个根 tab）、`server`（6 个 tab）、`project`（3 个 tab），见 `app/src/settings/surface.tsx:9-40,26-40`、`settings/shell.tsx:70-180,182-332,334-407,409-455`。
Android 是 `x-large` 对话框内的 **5 个根 tab、无嵌套视图**（`ui/settings/SettingsModel.kt:17-23`、`ui/settings/SettingsScreen.kt:124-141`）。

| 上游项 | 上游证据 (file:line) | Android 对应实现 (file:line) | 状态 |
|---|---|---|---|
| 设置总览（根 tab 列表 + 分组 + 侧栏 + 页脚 + Esc 逐级返回 + 搜索） | `settings/shell.tsx:70-180`（`RootSettings` `:182`、`ServerSettings` `:334`、`ProjectSettings` `:409`；Esc 分级 `:152-161`）；分组常量 `:39-68,218-246`；导航骨架 `settings/navigation.tsx:25`（`Tabs orientation="vertical" variant="settings"` `:50`；移动端导航条 `:51-97`；桌面侧栏 `:98-140`）；搜索 `settings/search.tsx:20`、`navigation.tsx:104-106` | `SettingsDialogContent` `ui/settings/SettingsScreen.kt:61`；导航列表 `:170-201`（两组）、顶部条 `:204-223`、页脚 `:257-273`；无搜索、无 Esc 分级 | 近似（差异明显）：除 tab 数量外，Android **没有设置搜索**（上游 `settings.search.focus` 命令 `settings/search.tsx:50-61`），也没有 root/server/project 三态视图切换（上游 `settings/shell.tsx:163-177` 的 `Switch/Match`） |
| 设置页：General（`settings.tab.preferences`） | `settings/general/general.tsx:297`（`mobile` `:301`、`desktop` `:303`）；tab 定义 `settings/shell.tsx:40`；标签 `settings/pages.ts:21` | `GeneralPanel` `ui/settings/SettingsPanels.kt:31` + 行表 `ui/settings/SettingsModel.kt:124-277` | 近似：语言/自动接受权限/shell/推理摘要/工具展开/配色/主题/字体/通知/声音/高级开关都在。差异：上游把 Appearance、Notifications 拆成**独立 tab**（`settings/shell.tsx:41-42`），Android 合并进 General；上游的 Updates / Display 分区是桌面专属，Android 有意丢弃（`SettingsModel.kt:117-123`） |
| 设置页：Appearance（`settings.general.section.appearance`） | `settings/appearance/appearance.tsx:64`；tab `settings/shell.tsx:41`；标签 `settings/pages.ts:22` | 作为 General 内的一节 `SettingsModel.kt:172-207`（配色/主题/UI 字体/代码字体/终端字体） | 近似：字段在，但**不是独立可寻址的 tab** |
| 设置页：Notifications（`settings.tab.notifications`） | `settings/notifications/notifications.tsx:56`；tab `settings/shell.tsx:42`；标签 `settings/pages.ts:23` | 作为 General 内的两节：通知开关 `SettingsModel.kt:208-227` + 声音 `:228-251` | 近似：字段在，非独立 tab；且没有实际通知/声音实现（见 C-1） |
| 设置页：Keyboard shortcuts（`settings.tab.shortcuts`） | `settings/keybinds/keybinds.tsx:343`（控制器 `:197`）；tab `settings/shell.tsx:43`；标签 `settings/pages.ts:24` | `ShortcutsPanel` `ui/settings/SettingsPanels.kt:113` | 近似（同 C-5 快捷键行）：结构在，行为（录制）与行数不足 |
| 设置页：Projects（`settings.tab.projects`） | `settings/workspaces/projects.tsx:18`；tab `settings/shell.tsx:47`；标签 `settings/pages.ts:25`；使用 `settings/shell.tsx:294-304`、`:376-386` | 无 | 缺失 |
| 设置页：Workspaces（`settings.tab.workspaces`） | `settings/workspaces/workspaces.tsx:50`；tab `settings/shell.tsx:48`；标签 `settings/pages.ts:26`；预览 `settings/workspaces/queries.ts`；行组件 `settings/workspaces/project-row.tsx:17` | 无 | 缺失 |
| 设置页：Providers（`settings.providers.title`） | `settings/providers/providers.tsx:31`；tab `settings/shell.tsx:49`；标签 `settings/pages.ts:27`；连接入口 `:45` | `ProvidersPanel` `ui/settings/SettingsPanels.kt:137`（只读列表，数据来自 `SettingsPanelExtras.providers` `ui/settings/SettingsScreen.kt:98-103`） | 近似：能列出已连接的供应商。差异：**不能连接/断开/鉴权**（上游 `DialogConnectProvider`）；没有 `providers.popular` 分区（Android 字符串 `oc_providers_section_popular` 存在但面板只渲染 `_connected`，`SettingsPanels.kt:143`） |
| 设置页：Models（`settings.models.title`） | `settings/models/models.tsx:27`；tab `settings/shell.tsx:50`；标签 `settings/pages.ts:28` | `ModelsPanel` `ui/settings/SettingsPanels.kt:172`（只读） | 近似：能列出模型；不能管理（上游 `DialogManageModels`） |
| 设置页：Extensions（`settings.tab.extensions`，含 `mcps/plugins/skills/lsps` 子 tab） | `settings/providers/extensions.tsx:24`；tab `settings/shell.tsx:51`；标签 `settings/pages.ts:29`；子 tab 类型 `settings/surface.tsx:38`；使用 `settings/shell.tsx:317-319`、`:399-401` | 无 | 缺失 |
| 设置页：Servers（`settings.section.server`） | `settings/servers/servers.tsx:18`（`SettingsServerGeneral`）；根分组项 `settings/shell.tsx:241`；嵌套 tab `:59-62`、`:358-406`；清单 `settings/servers/inventory.ts` | `ServersPanel` `ui/settings/ServersPanel.kt:48` | 近似：列表/搜索（>1 台才显示 `:64-74`，与 `showsServerSearch` `SettingsModel.kt:391` 一致）/新增/编辑/删除/设为默认都在。差异：无 health 检查（恒 `UNKNOWN`）、无 `AddServerMenu`（SSH/WSL 多入口菜单） |
| 设置页：Experimental（`settings.tab.experimental`） | `settings/experimental/experimental.tsx:13`；tab `settings/shell.tsx:55`；标签 `settings/pages.ts:31` | 无 | 缺失 |
| 设置页：About（`settings.tab.about`） | `settings/about/about.tsx:30`（动画字标 `settings/about/animated-wordmark.tsx:7`、贡献者 `settings/about/contributors.ts`）；tab `settings/shell.tsx:56`；标签 `settings/pages.ts:32` | 只有页脚两行（应用名 + 版本，`ui/settings/SettingsScreen.kt:257-273`） | 缺失（作为页面）：上游 About 是完整页面 |
| 服务端嵌套设置视图（`{type:"server"}`，6 个 tab） | `settings/surface.tsx:23,28,56-63`；`settings/shell.tsx:334-407`；进入点 `:168`、`:143` | 无（`SettingsTab` 是扁平 5 项，`ui/settings/SettingsModel.kt:17-23`） | 缺失 |
| 项目嵌套设置视图（`{type:"project"}`，3 个 tab） | `settings/surface.tsx:24,29-35,64-68`；`settings/shell.tsx:409-455`（`SettingsProjectGeneral` `settings/workspaces/project.tsx:17`、`ProjectSettingsExtensions` `settings/workspaces/project-extensions.tsx:208`） | 无 | 缺失 |
| 设置搜索（跨页搜索、结果分组、跳转高亮、`settings.search.focus`） | `settings/search.tsx:20-322`、`settings/search-index.ts`、`settings/search-catalog.ts`、`settings/search-reveal.ts`；命令 `settings/search.tsx:52` | 无 | 缺失 |
| 设置页快捷键 `settings.open`（`mod+comma`） | `settings/command.tsx:11-24`；另在 `shell/titlebar/titlebar.tsx:61` 用 `useSettingsDialog()` | 和弦 `isSettingsChord` `ui/palette/PaletteModel.kt:290` + `globalChord` `ui/App.kt:452-466` → `showSettingsDialog` `ui/App.kt:580-591`；Home 齿轮 `ui/App.kt:191` | 1:1（快捷键与会话一致；差异只在承载方式，已计入 `/settings` 行） |

---

## 4. 上游有、Android 完全缺失的界面

> 只列「用户可感知的独立界面/覆盖层」，每项给出上游证据。顺序按用户可感知程度。

1. **首启无服务器时的全屏连接页** —— `shell/routes/routes.tsx:79`、`servers/connect/screen.tsx:18`（URL/密码表单 `:82-137`、页脚 `:158-161`）；摄像头扫码配对为 web 专属（`screen.tsx:138`、`servers/connect/scanner.tsx:9`）。Android 未配置服务器时只有一行 `oc_setup_hint`（`ui/home/HomeSessionsList.kt:106`）。
2. **供应商连接/鉴权对话框 `DialogConnectProvider`（含自定义供应商子面板 `CustomProviderForm`）** —— `providers/connect/dialog.tsx:37,77`、`providers/credentials/dialog.tsx:16`（6 个入口，见上表）。
3. **模型管理 `DialogManageModels`** —— `providers/models/manage.tsx:23,62`。
4. **会话删除确认 `DeleteDialog`，以及会话行的右键/长按菜单（Rename / Export / Delete）** —— `home/sessions/controller.tsx:219,226,310`、`home/sessions/view.tsx:660-704`、`session/timeline/controller.tsx:210,219,272`。
5. **移动端会话视图标签（Session / Changes / Files / Terminal + ⋮ → Usage / Session details）与移动端文件浏览** —— `session/review/view.tsx:23-117`、`session/screen.tsx:191-242`、`session/files/session-mobile-files.tsx:14`。
6. **会话摘要面板（项目卡片 / 服务器面板 / 后台任务）与 `SummaryPopover`** —— `session/summary/panel.tsx:15`、`summary/project-card.tsx:10`、`summary/server-panel.tsx:50`、`summary/background.tsx:17`、`summary/popover.tsx:11`、`new-session/summary.tsx:11`、`new-session/view.tsx:91-109`。
7. **时间线搜索栏** —— `session/timeline/search-bar.tsx:7,12`（接线 `session/screen.tsx:273-277`）。
8. **待发送队列面板 `SessionQueuePanel`** —— `session/composer/queue-panel.tsx:18,25`（接线 `session/composer/region.tsx:222`）。
9. **Fork 会话 `DialogFork`** —— `session/commands/fork-dialog.tsx:27,97`。
10. **MCP 选择 `DialogSelectMcp`** —— `providers/connect/mcp-dialog.tsx:17,34`。
11. **桌面右侧面板骨架里的文件标签条 / 浏览器标签 / 上下文标签 / `OpenInAppButton`** —— `session/files/session-side-panel.tsx:61,316-550,634-724`。
12. **用量超限 / 未付费对话框** —— `providers/connect/usage-exceeded.tsx:16,33`、`providers/models/unpaid.tsx:19,69-72`、`session/usage-exceeded-dialogs.tsx:45-93`。
13. **设置：Projects / Workspaces / Extensions / Experimental / About 五个页面，以及服务端、项目两级嵌套设置视图** —— `settings/pages.ts:5-18`、`settings/shell.tsx:47-51,55-56,294-330,334-407,409-455`、`settings/surface.tsx:9-40`。
14. **设置搜索** —— `settings/search.tsx:20`（挂载 `settings/navigation.tsx:104-106`）。
15. **会话准备中页面与服务器不兼容面板** —— `session/route.tsx:53-93,128-136`、`session/preparing-composer.tsx:8`、`session/incompatible-server-panel.tsx:8`。
16. **会话工作区/分支切换（worktree）与 git 状态、workspace 选择器** —— `session/timeline/session-workspace-menu.tsx:15`、`new-session/workspace/selector.tsx:11,374`。
17. **会话浏览器面板 `SessionBrowserPane`** —— `session/browser/pane.tsx:16`。
18. **上下文用量页面 `SessionContextTab`（桌面侧栏 context 标签 + 移动端 usage 视图）** —— `session/files/session-context-tab.tsx:85`（挂载 `session/files/session-side-panel.tsx:581`、`session/screen.tsx:307-309`）。
19. **WebSearch 请求 dock** —— `session/requests/session-websearch-dock.tsx:10`（挂载 `session/composer/session-composer-region.tsx:36-38`）。
20. **图片全屏预览 `ImagePreview`** —— `_upstream/packages/ui/src/components/image-preview.tsx:11`（打开封装 `_upstream/packages/session-ui/src/components/image-preview.tsx:15`；打开点 `session-ui/src/message/message-content.tsx:269` 等）。
21. **应用内 Toast 区域与上传 Toast** —— `shell/shell.tsx:125-126`、`shell/notifications/toast.tsx:9`（UI 实现 `packages/ui/src/feedback/toast/toast.tsx:13`）、`composer/attachments/uploads.tsx:43,74`。
22. **会话未读/通知/提示音体系** —— `shell/notifications/notification.tsx:118,245,275,283,367`、`sound.ts`、`coordinator.ts`；标记已读 `session/route.tsx:187-195`；会话行未读徽标 `home/sessions/view.tsx:201-227`。
23. **首页专用命令面板**（`palette.search.placeholder.home`、无 Files 分类）—— `home/sessions/command-palette.tsx:14,67`；Android 的 `PaletteHostKind.HOME` 不可达（见上表 B-2）。
24. **移动端标题栏标签抽屉 `MobileDrawer` 与面板抽屉 `MobilePanelDrawer`** —— `shell/mobile-drawer.tsx:6,31`（挂载 `shell/titlebar/titlebar.tsx:463,520`）、`shell/mobile-panel-drawer.tsx:7`（挂载 `session/review/view.tsx:105`）。
25. **SSH 服务器接入（对话框 + 认证覆盖层）** —— `servers/ssh/dialog.tsx:41,112`、`servers/ssh/authentication.tsx:13`。
26. **调试面板 `DebugBar`** —— `shell/debug/debug-bar.tsx:179`（挂载 `shell/shell.tsx:120-124`）。
27. **设置里的 Projects/Workspaces 预览与项目详情页（`server-scope` + `project-row`）** —— `settings/server-scope.tsx:6`、`settings/workspaces/project-row.tsx:17`。
28. **标题栏的更新胶囊与自更新安装链路**（桌面专属，见「不适用」）—— `shell/titlebar/titlebar.tsx:736,750`、`shell/updates/download.tsx:9,31`。

---

## 5. Android 有、上游没有的界面

> 「上游没有」= 在本次检索范围内（`_upstream/packages/{app,ui,session-ui}/src`）找不到对应实现或路由。

1. **独立终端路由页 `Route.TERMINAL`** —— Android `ui/App.kt:121`（`"terminal?directory={directory}"`）+ `composable` `ui/App.kt:341-356` + `TerminalPanelHost` `ui/terminal/TerminalPanel.kt:104`。上游把终端做成**会话页内的 side/bottom 面板**（`session/screen.tsx:100-101,507-540`、`session/terminal/panel.tsx:39`），没有 `/terminal` 路由；Android 自己在 `ui/terminal/TerminalPanel.kt:91-95` 记录了这条收窄决策。
2. **会话页头部的终端入口 chip `TerminalEntryButton`** —— Android `ui/terminal/TerminalPanel.kt:706`，挂载 `ui/App.kt:332-337`（28dp，`top = 52.dp, end = 8.dp`）。上游会话页同一位置是 `SessionReviewToggle`（`session/screen.tsx:338-348`、`session/header/session-header-actions.tsx:11`），没有终端 chip。
3. **会话列表抽屉 `SessionDrawerContent` + `ModalNavigationDrawer`** —— Android `ui/session/drawer/SessionDrawer.kt:57`，宿主 `ui/session/SessionScreen.kt:201-237`。上游会话页没有会话列表抽屉：会话列表只在 Home 与标题栏标签里（`home/sessions/view.tsx:92`、`shell/titlebar/tab-strip.tsx:234`）；Android 自己在 `ui/session/header/SessionHeader.kt:87-89` 注明「☰ 是 Android 增设，因为手机壳在会话打开时不显示 Home」。
4. **底部标签栏作为「设置项驱动的可选外壳」** —— Android `ui/nav/BottomTabStrip.kt:56` + `appearance.tabLayout == "vertical"` 开关（`ui/App.kt:148-149`、`OcActivity.kt:62`、`ui/nav/AppearancePrefs.kt`）。上游有移动端底部标题栏（`shell/titlebar/titlebar.tsx:65`），但它是**标题栏位置选项**、由 `general.mobileTitlebarPosition()` 驱动（`shell/shell.tsx:33`），不是 `appearance.tabLayout`；Android 把「标签布局」这一个设置项复用成了底部栏开关。
5. **`Route.SETTINGS` 深链 shim** —— Android `ui/App.kt:272-279`：进入 `settings` 路由后立刻打开对话框并 `popBackStack()`，使 Home 成为唯一真实目的地。上游 `/settings` 就是真实页面（`routes.tsx:38`）。
6. **URL 输入的全角字符归一化（`normalizeUrlInput`）** —— Android `ui/settings/ServersPanel.kt:234-246`，处理 CJK 输入法提交全角 `:` `/` `.`。上游无此逻辑。
7. **服务器对话框里的 CORS 提示行** —— Android `R.string.oc_server_cors_hint`（`ui/settings/ServersPanel.kt:359-364`；字符串 `app/src/main/res/values/strings.xml` 中键名 `oc_server_cors_hint`）。上游 `servers/connect/dialog.tsx` 只在 web 平台出现混合内容/扫码分支（`:162`、`:285`），没有这条 Android 专属提示。
8. **`oc_setup_hint` 内联配置提示** —— Android 在 Home / 新会话 / 会话三处渲染（`ui/home/HomeSessionsList.kt:106`、`ui/home/NewSessionScreen.kt:212`、`ui/session/SessionScreen.kt:316`）。上游对应位置是首启全屏连接页（`routes.tsx:79`）。
9. **工具卡片的「Context tool group」聚合行** —— Android `ui/session/timeline/ContextToolGroupCard.kt:77`（`read/glob/grep/list` 合成一行 + `ContextSummary` 计数 `:182-220`，`TimelineGrouping.kt:88-100`）。上游有 `data-component=tool-count-summary` 的样式引用（`ui/theme/OcMotion.kt:129-130` 抄录的选择器），但**本次检索未在 `_upstream/packages/{app,session-ui}/src` 找到把 read/glob/grep/list 合并成一行的组件** —— 该项判为 **未确认**（可能来自上游内联 bundle 而非源码包）。

---

## 6. 统计

按上面三张表的全部行计数（逐行核对，重复项已合并去重）：

| 表 | 行数 | 1:1 | 近似 | 缺失 | 不适用 |
|---|---|---|---|---|---|
| 表 A — 路由 | 6 | 2 | 2 | 1 | 1 |
| 表 B — 对话框与覆盖层 | 24 | 0 | 6 | 15 | 3 |
| 表 C-1 — 外壳 | 7 | 0 | 3 | 1 | 3 |
| 表 C-2 — Home | 7 | 0 | 7 | 0 | 0 |
| 表 C-3 — New session | 7 | 0 | 4 | 3 | 0 |
| 表 C-4 — Session 页 | 24 | 0 | 10 | 12 | 2 |
| 表 C-5 — Composer / 命令 | 5 | 0 | 5 | 0 | 0 |
| 表 C-6 — 设置 | 17 | 1 | 8 | 8 | 0 |
| **合计** | **97** | **3** | **45** | **40** | **9** |

`1:1` 的 3 项：
- `/` 首页路由（`_upstream/packages/app/src/shell/routes/routes.tsx:37` ↔ `ui/App.kt:103,258`）
- `/server/:serverKey/session/:id` 会话路由（`_upstream/packages/app/src/shell/routes/routes.tsx:39` ↔ `ui/App.kt:104,309`）
- 设置快捷键 `settings.open` / `mod+comma`（`_upstream/packages/app/src/settings/command.tsx:16-24` ↔ `ui/App.kt:452-466,580-591`）

**未确认**：另有 3 处局部判断未落实到行，已写在对应行备注中，**不计入**上表四类：
1. 权限 dock 上游 `ask` 模式的全部分支；
2. 提问 dock 上游请求树的全部状态；
3. Android `ui/session/timeline/ContextToolGroupCard.kt:77` 的「read/glob/grep/list 合成一行」是否源自上游源码包（本次在上游 `packages/{app,session-ui}/src` 未找到对应组件）。

---

## 7. 最关键的 10 条缺失/差异（按用户可感知程度排序）

1. **首启无服务器时没有连接页** —— 上游 `_upstream/packages/app/src/shell/routes/routes.tsx:79` + `_upstream/packages/app/src/servers/connect/screen.tsx:18`（整屏表单；扫码子功能仅 web，`screen.tsx:138`）；Android 只有 `oc_setup_hint`（`ui/home/HomeSessionsList.kt:106`、`ui/home/NewSessionScreen.kt:212`、`ui/session/SessionScreen.kt:316`）与旧壳 Toast（`MainActivity.kt:123`）。状态：缺失。
2. **设置不是完整 surface，只复刻了 5/12 个根 tab 且无嵌套视图、无搜索** —— 上游 `_upstream/packages/app/src/settings/pages.ts:5-18`、`_upstream/packages/app/src/settings/surface.tsx:9-40`、`_upstream/packages/app/src/settings/shell.tsx:70-180`、搜索 `_upstream/packages/app/src/settings/search.tsx:20`（挂载 `settings/navigation.tsx:104-106`）；Android `ui/settings/SettingsModel.kt:17-23`、`ui/App.kt:580-591`。缺 Projects / Workspaces / Extensions / Experimental / About 与 server、project 两级嵌套。状态：近似（差距大）。
3. **供应商连接/鉴权对话框完全缺失，Providers 面板只读** —— 上游 `_upstream/packages/app/src/providers/connect/dialog.tsx:37,77`（6 个打开点）；Android `ui/settings/SettingsPanels.kt:137-168`。状态：缺失。
4. **会话行没有菜单：不能删除、导出、归档会话** —— 上游 `_upstream/packages/app/src/home/sessions/view.tsx:660-704`、删除确认 `_upstream/packages/app/src/home/sessions/controller.tsx:219,310`、`_upstream/packages/app/src/session/timeline/controller.tsx:210,272`；Android 会话头 ⋮ 的 `onMenuAction` 是空实现（`ui/session/SessionScreen.kt:273-277`），首页行也无菜单（`ui/home/HomeSessionsList.kt` 行渲染区间 `:230-498`）。状态：缺失。
5. **命令面板里 5/7 条命令点了没反应，且 `file.open` 未注册** —— 上游 `_upstream/packages/app/src/session/commands/use-session-commands.tsx:248-463`、`_upstream/packages/app/src/shell/commands/dialog.tsx:30`；Android `ui/palette/AppCommands.kt:41,46,51,56,67`（空 `onSelect`），整表仅 7 条。状态：近似（用户点了没反应，等同缺失）。
6. **移动端会话视图标签（Session/Changes/Files/Terminal + ⋮ Usage/Details）缺失** —— 上游 `_upstream/packages/app/src/session/review/view.tsx:23-117`、`_upstream/packages/app/src/session/screen.tsx:191-242,303-324`；Android `ui/session/SessionScreen.kt:281-287` 只有一个 review 开关。状态：缺失。
7. **会话摘要（项目/服务器/后台任务）与 `SummaryPopover` 缺失** —— 上游 `_upstream/packages/app/src/session/summary/panel.tsx:15`、`_upstream/packages/app/src/session/summary/project-card.tsx:10`、`_upstream/packages/app/src/session/summary/server-panel.tsx:50`、`_upstream/packages/app/src/session/summary/background.tsx:17`、`_upstream/packages/app/src/session/summary/popover.tsx:11`（新会话页也有：`_upstream/packages/app/src/new-session/view.tsx:91-109`）；Android 无。状态：缺失。
8. **时间线搜索栏与待发送队列面板缺失** —— 上游 `_upstream/packages/app/src/session/timeline/search-bar.tsx:7,12`、`_upstream/packages/app/src/session/composer/queue-panel.tsx:18,25`（接线 `_upstream/packages/app/src/session/composer/region.tsx:222`、`_upstream/packages/app/src/session/screen.tsx:273-277`）；Android 无（队列只有纯逻辑 `ui/session/composer/ComposerSubmit.kt:10-35`）。状态：缺失。
9. **上下文用量环永远是空轨道（`limit` 恒为 null）** —— 上游计算依赖 `GET /config/providers` 的 `limit.context`（`_upstream/packages/app/src/session/timeline/session-context-usage.tsx:40`）；Android `ui/session/SessionScreen.kt:272` 传 `limit = null`，`ui/session/header/ContextUsage.kt:24-27` 注明「M4 范围」。状态：近似（有控件无数据）。
10. **终端面板宿主从「会话内面板」变成「独立路由」，且缺少 `terminal.new`/`terminal.close` 命令与标签拖拽** —— 上游 `_upstream/packages/app/src/session/screen.tsx:100-101,507-540`、`_upstream/packages/app/src/session/terminal/panel.tsx:39`、`_upstream/packages/app/src/session/commands/use-session-commands.tsx:393-409`；Android `ui/App.kt:121,341-356`、`ui/terminal/TerminalPanel.kt:91-95,104`。状态：近似。

**备选第 11 条（结构性、非缺失）**：`/settings` 上游是路由 + 全屏 surface（1048px 卡片 / 328px 侧栏，`_upstream/packages/app/src/settings/settings.css:8-56`），Android 做成 `x-large` 对话框（980×600 / 240dp 侧栏，`ui/settings/SettingsScreen.kt:79,154-165`）。`ui/App.kt:84-90` 的注释称上游用 `Dialog variant=settings` 承载设置，但上游源码里 `variant="settings"` 只出现在「管理模型」和「选择模型」两处（`_upstream/packages/app/src/providers/models/manage.tsx:62`、`_upstream/packages/app/src/providers/models/select-dialog.tsx:549`），设置本身是路由；而且 `size="x-large"` 在整个生产代码里**从未被使用**（只有 Storybook `_upstream/packages/ui/src/overlays/dialog/dialog.stories.tsx:97`）。这条注释依据与上游 v2 源码不符，建议后续修正。

---

## 8. 未确认清单（不臆测）

- 权限 dock 上游 `ask` 模式的全部分支/文案（含外部目录、doom-loop 等特例）未逐条对照 `_upstream/packages/app/src/session/requests/session-permission-dock.tsx`。
- 提问 dock 上游请求树（`_upstream/packages/app/src/session/requests/session-request-tree.ts`）的全部状态未逐条对照。
- Android `ui/session/timeline/ContextToolGroupCard.kt:77` 的「read/glob/grep/list 合成一行」在本次检索的上游源码包（`_upstream/packages/{app,session-ui}/src`）中未找到对应组件；可能来自内联构建产物，判为未确认，未作为差异计入。
- 上游 `FileTabContent`（`_upstream/packages/app/src/session/files/file-tabs.tsx:171`）在本次检索中只命中定义、未见调用方；该文件标签内容是否真的可达，未确认。
- `_upstream/packages/app/src/session/files/session-side-panel.tsx:578-584` 的 `value="context"` 与 `SessionContextTab` 的 `Tabs.Content` 注册表未读取确认，因此「桌面侧栏 context 标签确实渲染 `SessionContextTab`」这一条按挂载点（`:581`）判定，未逐层验证。
- Android 侧 `ui/session/timeline/` 的 `ToolCardPolicy` / `ToolPresentation` 与上游 `session-ui` 工具渲染器的逐工具对照不在本次「路由与界面清单」范围内，未做。

> 已解决、不再列为未确认：上游 `DialogConnectProvider` 的尺寸 —— 已确认它**不传 `size`/`variant`**，用 `containerClass` 定 640×512（`_upstream/packages/app/src/providers/connect/dialog.tsx:77-78`）；上游 `HomeProjectsView` 的行级菜单全集也已逐条落实（`_upstream/packages/app/src/home/projects/view.tsx:734-762`）。
