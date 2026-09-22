# Wave 6 规格提取（A1 会话摘要面板 / A4 待发送队列 / A8 导出与归档）

> 上游根：`_upstream/packages/`（只读参考；本次为**纯只读**任务，唯一写入文件即本文件）。
> 本机实测事实（由 Lead 提供，本文件直接引用、未重测）：
> - server = opencode **1.18.31**，`http://127.0.0.1:4096`。
> - v1 `GET /session` 的 `time` 只有 `created`/`updated`，**无 `archived`**；v2 `GET /api/session/<id>/archive`
>   返回 `text/html`（SPA 兜底）→ **归档在当前 server 不可实现**。
> - v1 `GET /session/:id/message` 支持 `?limit=`（返回**最新 N 条**、按时间正序）；`?before=` 返回 400。
> - 我们的 App 已具备 `OpenCodeClient.messages()/session()`、SAF 附件选择、`ChatViewModel`。
>
> 引用格式：`相对仓库根的路径:行号`。未读到 / 无法判定的写「未确认」，**不编造**。
> 全文中所有「Android 落地建议」段落均为**我的工程建议**，不是上游规格。

---

## 0. 三条关键结论速览

| 功能 | 上游是什么 | 对本 server（1.18.31）能不能做 | 阻塞点 |
|---|---|---|---|
| **A1 摘要面板** | 一个 header 右侧 popover：项目卡（工作区/分支/变更/后台任务）+ 服务卡（MCP/Plugins/Skills/LSP 四个二级菜单） | **大部分可做**（数据来自 v1 已实测端点：`/project`、`/vcs`、`/session/:id/diff`、`/config`、`/mcp`、`/lsp` 等） | 二级菜单里 **plugins** 走 v2 `sdk.api.plugin.list`（v1 无对应端点）；工作区移动（`SessionWorkspaceMenu`）依赖 worktree API |
| **A4 待发送队列** | composer 上方的「拉动抽屉」，读/写 **inbox**（v2：`session.inbox.list/cancel/update` + `prompt{delivery,resume}`） | **完全不可做** | 当前 app 是 **纯 v1**（`OpenCodeClient` 无 inbox 方法，见 §A4.5/A4.6）；v1 无 inbox 概念，且 1.18.31 无 `session.inbox.*` 端点 |
| **A8 导出 / 归档** | 导出 = `session.get` + 分页 `message.list(limit=200, order=asc)` → `{info,messages}` JSON 存盘；归档 = `session.archive` + 本地移除 + 通知 titlebar | **导出可做且上游规格已可 1:1 落地**；**归档不可做** | 归档：v1 `time.archived` 字段本 server 不返回，`/api/session/<id>/archive` 未实现，上游自己也是 `SHOW_HOME_SESSION_ARCHIVE = false` + `Promise.reject`（见 §A8.3） |

---

# A1 会话摘要面板（session summary）

## A1.1 上游结构（逐块 + file:line）

### A1.1.1 文件清单

| 文件 | 行数 | 职责 |
|---|---|---|
| `_upstream/packages/app/src/session/summary/popover.tsx` | 66 | 触发按钮 + 命令注册 + Kobalte Popover 外壳 |
| `_upstream/packages/app/src/session/summary/panel.tsx` | 144 | 面板主体：项目卡 + 服务卡的编排 |
| `_upstream/packages/app/src/session/summary/project-card.tsx` | 48 | 可折叠的「项目」section 外壳（头像 + 标题 + 展开内容） |
| `_upstream/packages/app/src/session/summary/server-panel.tsx` | 536 | 可折叠的「Extensions」section + 四个服务二级菜单 |
| `_upstream/packages/app/src/session/summary/background.tsx` | 89 | 后台任务汇总行 + 任务列表 popover |
| `_upstream/packages/app/src/session/summary/summary.css` | 318 | 全量样式（面板尺寸、行、服务菜单、move 行、响应式） |
| `_upstream/packages/app/src/session/summary/configured-lsp.ts` | 17 | 纯函数：从 `ConfigEntry[]` 推「已配置且未 disabled 的 LSP 名」 |

### A1.1.2 分层（从上到下）

```
SummaryPopover (popover.tsx)
└ Popover.Trigger  = IconButton "window-analytics"
└ Popover.Portal > Popover.Content.session-summary-popover   ← 滚动容器
   └ SessionSummaryPanel (panel.tsx:44)   div[data-component=session-summary-panel]
      ├ 项目卡 ProjectSummaryCard (project-card.tsx:20)   section.session-summary-card[data-section=project]
      │   ├ 折叠标题行（ProjectAvatar + displayName + chevron-down）      project-card.tsx:21-40
      │   └ session-summary-rows（展开时 <Show when={expanded()}>）      project-card.tsx:41-45
      │       ├ 工作区行 SessionWorkspaceMenu（monitor / outline-worktree 图标 + location 文本 + fill-triangle-down）
      │       │                                                          panel.tsx:48-62
      │       ├ 分支行（branch 图标 + 分支名，或 "No branch" + "· Based on {base}"）
      │       │                                                          panel.tsx:63-87
      │       ├ 变更行 <button onClick=onReview>（review 图标 + N Changed files · DiffChanges / "Loading changes…" / "No changes"）
      │       │                                                          panel.tsx:88-113
      │       └ BackgroundWorkSummary（后台任务行 / 无任务时整块不渲染）  panel.tsx:114 / background.tsx:38
      ├ move 行（条件渲染的「Move to worktree」+ xmark 关闭）              panel.tsx:116-139
      └ 服务卡 SessionServerPanel (server-panel.tsx:66)   section.session-summary-card[data-section=server]
          └ 四个 ServiceMenu：MCP / Plugins / Skills / LSP              server-panel.tsx:31-36, 83-95
```

### A1.1.3 面板 props（`panel.tsx:15-31`）

| prop | 类型 | 用途 |
|---|---|---|
| `shown` | `boolean?` | 透传给服务卡；`props.shown !== false` 才算「可见」（`panel.tsx:141`） |
| `mobile` | `boolean?` | 决定所有 popover 的 `placement` 与 `data-mobile` |
| `project` | `Project` | 项目卡数据（name/worktree/icon） |
| `avatar` | `JSX.Element?` | 覆盖默认 `ProjectAvatar` |
| `directory` | `string` | 工作区目录（服务查询的 `location.directory`） |
| `local` | `boolean` | 「本地仓库」判定（图标 monitor vs outline-worktree、文案） |
| `branch` / `baseBranch` | `string?` | 当前分支 / 基线分支 |
| `diffs` | `{additions,deletions}[]?` | `undefined` = 加载中；`[]` = 无变更 |
| `sessionID` | `string` | 工作区移动菜单用 |
| `moveEligible` / `moveDismissed` / `onMoveDismiss` | — | move 行的显示与关闭 |
| `onReview` | `() => void` | 点击变更行 → 关闭面板 + 打开 review |
| `backgroundTasks` | `BackgroundTask[]` | 后台任务列表 |

`location()` 的取值规则（`panel.tsx:38-42`）：`local` → `session.new.workspace.local`（"Local repository"）；
否则在 `workspaceDirectories(project)` 里找包含 `props.directory` 的那一项并取 `getFilename`，找不到就取 `props.directory` 的文件名。

### A1.1.4 项目卡字段明细（`project-card.tsx`）

- 容器：`<section class="session-summary-card" data-section="project">`（:20）。
- 标题按钮：`aria-label={displayName(project)}`、`aria-expanded={expanded()}`、`aria-controls={contentID}`（:24-26），
  点击切换 `settings.sessionSummary.setProjectExpanded(!expanded())`（:27）。
- 头像：`props.avatar ?? <ProjectAvatar fallback={displayName(project)}
  src={getProjectAvatarSource(project.id, project.icon)} variant={getProjectAvatarVariant(project.icon?.color)}/>`（:29-35）。
- 文本：`displayName(project)`，`dir="auto"`（:36-38）。
- 尾部：`<Icon name="chevron-down" size="small" class="session-summary-disclosure"/>`（:39）。
- 展开内容容器：`<div id={contentID} class="session-summary-rows">`（:42）。

### A1.1.5 `server-panel.tsx`（536 行）逐块清单

服务定义（:31-36）：`mcp`→icon `mcp`/key `session.summary.mcp`；`plugins`→`cube`/`session.summary.plugins`；
`skills`→`graduation-cap`/`session.summary.skills`；`lsp`→`code-slash`/`session.summary.lsp`。

| 子块 | 行号 | 用途 |
|---|---|---|
| `SessionServerPanel` | :50-101 | 服务卡外壳：标题行（server 图标 + `name()` + chevron）、`settings.sessionSummary.serverExpanded` 折叠、`submenu` 单选状态、`createEffect(on([...]))` 在 directory/shown/expanded 变化时清空子菜单（:63） |
| `name()` | :57-61 | 服务器数 < 2 → `session.summary.server`（"Extensions"）；否则取当前连接名 `serverName(...)` |
| `ServiceMenu` 分发 | :103-107 | `mcp`→`McpMenu`，`lsp`→`LspMenu`，其余→`ServiceCatalog` |
| **LspMenu** | :109-155 | 打开时 `data.location.config.invalidate/sync`（:113-119）；`configuredLsps(...)` 取名单（:120）；订阅 `config.updated` 重取（:121-123）；有内容时：标题 `session.summary.lsp.configured`（:139）+ 每行一个 LSP 名（:140-148）+ footer 配置链接（:149-151）；无内容时 `ServiceEmpty` + `session.summary.lsp.empty`（:136） |
| **McpMenu** | :157-275 | 打开时 sync `data.location.mcp.server`（+preview 时同时 sync `config`）（:161-170）；服务器按名排序（:171-175）；`defaults()` 从 config document 的 `info.mcp.servers` 取 `!disabled`（:176-184）；标题 `session.summary.mcp.title`（:204）；`props.mcp?.preview` 时渲染 `session-service-message`「Applies when the worktree is created」（:205-209）；每行是 `Switch`（:237-265）：状态点（`session-service-dot[data-status]`）+ 名称 + 状态文案（failed/connecting/needsAuth）+ `description` 撑成 `sr-only`；`pending()` 时 readOnly + aria-disabled（:242-243）；footer 配置链接（:269-271）；空 → `ServiceEmpty` + `session.summary.mcp.empty`（:201） |
| **ServiceCatalog**（plugins / skills） | :277-371 | plugins：`sdk.api.plugin.list({location:{directory}})`，过滤 `source.type !== "builtin"`，映射 `{name, status, error}`（:284-293）；skills：`data.location.skill.invalidate/sync`（:294-295）后读 `data.location.skill.list(...)`，状态恒为 `active`（:306-310）；两者都按名排序（:311）；订阅 `plugin.updated` / `skill.updated` 重取（:313-319）；标题 `plugins.configured` / `skills.configured`（:345-351）；每行 = 状态点 + 名称 + （failed 时）`session.summary.failed`（:352-364）；空 → `ServiceEmpty`（:336-343） |
| **ServicePopover** | :373-438 | 二级菜单外壳：`placement` = `mobile?"top-end"` : rtl?`right-start`:`left-start`（:384-386）；`gutter={4}`、`overflowPadding={16}`、`modal={false}`（:395-397）；打开且非 loading 时 `retry()`（:392）；Trigger 行 = 图标 + label + `fill-triangle-down`（:399-403）；Content `.session-service-menu`，`data-service`、`data-empty`、`aria-busy`（:405-411）；三级状态：未 ready → `common.loading`（:415-417）；有 error → `common.requestFailed` + `session.summary.retry` 按钮（:423-428）；否则渲染 children |
| **ServiceConfigLink** | :440-525 | 分隔线 `.session-service-config-separator`（role=separator，:485）；label = `server.isLocal ? session.summary.configure("Configuration file") : session.summary.copyConfigPath`（:446）；非本地 → 复制**配置文件路径**到剪贴板 + 2s「Copied」态（:456-479，`store.copied` 定时器 :447-451）；本地 → `platform.revealPath(path)` 否则 `platform.openPath(getDirectory(path))`（:471-472）；路径来源 = `sdk.api.config.get({location:{directory}})` 里**最后**一个 `path` 匹配 `/\.jsonc?$/` 的 document，且优先取 `info[service] !== undefined` 的那条（:457-464）；失败 → error toast（:474-480）；不支持 reveal/open 时退化为静态行（:486-493） |
| **ServiceEmpty** | :527-535 | 空态：`<strong>{title}</strong>` + `session-service-footer` 里的 `ServiceConfigLink` |

## A1.2 交互与状态

| 交互 | 实现 | 位置 |
|---|---|---|
| 打开/关闭 | `SummaryPopover` 每帧注册命令 `session.summary.toggle`（标题 `command.session.summary.toggle`="Toggle summary"，分类 `command.category.view`="View"，键位 **`mod+shift+y`**），`onSelect` 取反 `props.onOpenChange` | `popover.tsx:17-29` |
| 命令归属 | `props.active === false` 时注册**空数组**（缓存但不可见的 timeline 不抢命令） | `popover.tsx:17-19` |
| 触发按钮 | `IconButton icon={<Icon name="window-analytics"/>} variant="ghost-muted" size="large"`，`state={open?"pressed":undefined}`，`aria-label`/`aria-expanded` = `session.summary.title`（"Session details"） | `popover.tsx:46-54` |
| tooltip | `Tooltip placement="bottom"`，值 = `session.summary.tooltip`（"Summary"）+ `<Keybind>` 键位胶囊 | `popover.tsx:35-45` |
| 锚点 | `Popover.Anchor` = `absolute end-3 top-2.5 h-7 w-0`（对齐按钮竖直边界；gutter 8 + content padding 4 = 12px 卡间距） | `popover.tsx:33-34` |
| 定位/尺寸 | `placement="bottom-end"`、`gutter={8}`、`overflowPadding={16}`；Content = `z-50 max-h-[calc(100dvh-96px)] overflow-y-auto border-0 bg-transparent p-1` | `popover.tsx:32, 57-60` |
| 项目卡折叠 | 标题行点击 → 持久化 `settings.sessionSummary.projectExpanded`（默认 **true**） | `project-card.tsx:27`、`settings/model.tsx:256, 371-374` |
| 服务卡折叠 | 标题行点击 → `settings.sessionSummary.serverExpanded`（默认 **true**） | `server-panel.tsx:72`、`settings/model.tsx:256, 375-378` |
| 二级菜单 | `store.submenu` 单选；同一时刻最多一个子菜单；directory/shown/expanded 任一变化即关闭 | `server-panel.tsx:62-63, 89-92` |
| 变更行 | 点击 → `props.onReview()`；桌面端调用方先 `setSummary(false)` 再打开 review | `message-timeline.tsx:566-569` |
| move 行显示条件 | `expanded() && props.local && props.diffs?.length && props.moveEligible && !props.moveDismissed` | `panel.tsx:116` |
| 自动关闭（切会话） | `createEffect(on(sessionID, () => { setSummary(false); setWorkspaceSuggestionDismissed(false) }))` | `message-timeline.tsx:179-184` |
| 自动关闭（失活） | `props.active === false` 时 `setSummary(false)`（同时清 title 编辑器状态） | `message-timeline.tsx:285-289` |
| 摘要打开状态同步 | 打开/关闭同时通知外层 `props.onSummaryOpenChange(open)`（桌面端 = `review.details.setOpen`，即 side panel 占用） | `message-timeline.tsx:174-177`、`screen.tsx:269` |
| 挂载点（桌面） | header 右侧 toolbar，`<Show when={!parentID() && project()}>` → `SummaryPopover active={props.active} ...` → `<SessionSummaryPanel shown={summaryOpen()} project avatar directory local branch baseBranch diffs sessionID .../>` | `message-timeline.tsx:544-573` |
| 挂载点（移动） | **不用 popover**：作为 `SessionMobileViewTabs` 的 `details` 内容，`<SessionSummaryPanel mobile project directory local branch baseBranch diffs sessionID ... onReview={关闭 tabs → review.mobile.setTab("changes") → 关终端}/>` | `screen.tsx:191-240`（渲染处 :202-225） |
| 新建会话页复用 | `<SummaryPopover open={store.summary}>` 包 `<NewSessionSummary shown={store.summary}/>`（不同的内层内容，不在本次范围） | `new-session/view.tsx:95-108` |
| 后台任务 popover | `Popover` + Trigger 行（`data-component="session-background-summary"`，`aria-label` = `session.background.tasksRunning` 复数，`TextShimmer active` 闪烁）；`placement` = mobile `top-end` / rtl `right-end` / 否则 `left-end`，`gutter={4}` | `background.tsx:32-54` |
| 后台任务列表 | `tasks.slice(0, 10)`（**最多 10 行**）；subagent 行渲染成 `<a href={data.sessionHref?.(task.id)}>` 且可 `navigateToSession`（阻止默认、关面板）；shell 行是 `<div>` 不可点 | `background.tsx:61-83` |
| 后台任务类型标签 | `shell` → `ui.tool.shell`；subagent 无 agent → `ui.tool.agent.default`；否则 `agent` 首字母大写（:25-29） | `background.tsx:25-29` |
| 任务清空后自动收起 | `createEffect(() => { if (props.tasks.length > 0) return; setStore("open", false) })` | `background.tsx:21-24` |
| 后台任务数据来源 | `composer.requests.background.tasks()`（`session/requests/model.ts:133` ← `session/requests/background.ts:85-115`） | 见 §A1.4 |

## A1.3 样式规格（数值 / token）

### A1.3.1 popover 与面板

| 选择器 | 属性 | 值 | 行号 |
|---|---|---|---|
| `.session-summary-popover` | `animation` | `menu-v2-in 120ms ease-out`（keyframes：`opacity 0→1` + `scale(0.96)→scale(1)`，见 `packages/ui/src/navigation/menu/menu.css:302-311`） | `summary.css:1-4` |
| `.session-summary-popover` | `transform-origin` | `var(--kb-popover-content-transform-origin)` | `summary.css:2` |
| `[data-component="session-summary-panel"]` | `display/flex-direction/gap` | `flex / column / 8px` | `summary.css:6-9` |
| 同上 | `width` / `max-width` | **280px** / `calc(100vw - 32px)` | `summary.css:10-11` |
| 同上`[data-mobile]` | `width` / `max-width` | `100%` / `none` | `summary.css:13-16` |
| `.session-summary-card` | `padding` / `border-radius` / `background` / `box-shadow` | `2px` / `6px` / `var(--v2-background-bg-base)` / `var(--v2-elevation-raised)` | `summary.css:19-26` |
| `[data-color-scheme="dark"] .session-summary-card` | `background` | `var(--v2-background-bg-layer-01)` | `summary.css:28-30` |

token 实际色值（`packages/ui/src/styles/tokens/theme.css` + `colors.css`）：
`--v2-background-bg-base` light `#ffffff`（`theme.css:6`→`colors.css:4`）/ dark `#242424`（`theme.css:148`→`colors.css:14`）；
`--v2-background-bg-layer-01` light `#fafafa`（`:8`→`colors.css:5`）/ dark `#2e2e2e`（`:150`→`colors.css:13`）；
`--v2-background-bg-layer-02` light `#f2f2f2`（`:9`→`colors.css:6`）/ dark `#3a3a3a`（`:151`→`colors.css:12`）；
`--v2-elevation-raised` = `0 2px 4px var(--v2-alpha-dark-4), 0 1px 2px -1px var(--v2-alpha-dark-8), 0 0 0 0.5px var(--v2-alpha-dark-12), 0 0 0 0 var(--v2-alpha-dark-0)`（`theme.css:106-108`），
`--v2-elevation-floating` = `0 8px 16px …-4, 0 4px 8px …-8, 0 0 0 0.5px …-12`（`theme.css:109-111`）。

### A1.3.2 行（`.session-summary-row` / `.session-service-row` / `.session-service-config` / `.session-mcp-row`）

| 属性 | 值 | 行号 |
|---|---|---|
| `display / align-items / gap` | `flex / center / **8px**` | `summary.css:43-45` |
| `width` | `100%` | `summary.css:46` |
| `min-height` | `**32px**` | `summary.css:47` |
| `padding` | `**4px 12px**` | `summary.css:48` |
| `border-radius` | **4px** | `summary.css:49` |
| `color` | `var(--v2-text-text-base)` | `summary.css:50` |
| `font-size / font-weight / line-height` | `**13px / 440 / var(--line-height-base)`（=20px） | `summary.css:51-53` |
| `.session-summary-card .session-summary-row` | `padding-inline-end: **36px**` | `summary.css:57-60` |
| `.session-summary-card .session-summary-row:has(> .session-summary-menu-indicator)` | `padding-inline-end: **8px**` | `summary.css:62-65` |
| `.session-summary-menu-indicator` | `margin-inline-start: 4px` | `summary.css:67-69` |
| 卡片与 rows 容器 | `flex / column / gap **2px**` | `summary.css:32-37` |
| 焦点环 | `outline: none; background: var(--v2-overlay-simple-overlay-hover)` | `summary.css:71-76` |
| hover（仅 `@media (hover: hover)`） | `background: var(--v2-overlay-simple-overlay-hover)` | `summary.css:78-85` |
| `[data-expanded]` | `background: var(--v2-overlay-simple-overlay-pressed)` | `summary.css:87-89` |
| `.session-summary-heading` | `color: var(--v2-text-text-muted)`；`font-weight: **530**` | `summary.css:91-94` |
| 行标签省略 | `flex:1; min-width:0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; text-align:start` | `summary.css:96-103` |
| heading 内标签 | `flex: 0 1 auto` | `summary.css:104-106` |
| 展开指示器收起态 | `transform: rotate(-90deg)`（RTL 时 `rotate(90deg)`） | `summary.css:107-116` |

`--v2-overlay-simple-overlay-hover` light `#0000000a`（4% 黑）/ dark `#ffffff0f`（6% 白）；
`--v2-overlay-simple-overlay-pressed` light `#00000014`（8%）/ dark `#ffffff1a`（10%）；
`--v2-text-text-faint` light `#808080` / dark `#808080`（`theme.css:22, 161`→`colors.css:10`）。
`--line-height-tight/compact/base = 12px/16px/20px`（`theme.css:138-140`）。

### A1.3.3 二级菜单（`.session-service-menu`）

| 属性 | 值 | 行号 |
|---|---|---|
| `z-index` | **60** | `summary.css:119` |
| `width` / `max-width` | 280px / `calc(100vw - 32px)` | `summary.css:120-121` |
| `max-height` | `**min(480px, calc(100dvh - 32px))`** | `summary.css:122` |
| 滚动 | `overflow-y: auto; overscroll-behavior: contain` | `summary.css:123-124` |
| `padding / border-radius / background / box-shadow` | `2px / 6px / var(--v2-background-bg-layer-01) / var(--v2-elevation-floating)` | `summary.css:125-128` |
| 文字 | `13px / 440 / var(--line-height-compact)`（=16px） | `summary.css:131-133` |
| `data-empty` | `width: 232px` | `summary.css:137-139` |
| 相邻行间距 | `margin-block-start: 2px` | `summary.css:150-152` |
| `.session-service-title` | `min-height:32px; padding: 0 12px; color: var(--v2-text-text-faint); font-weight: 530; letter-spacing: 0.05px` | `summary.css:154-163` |
| `.session-service-empty` | `flex/column/gap:0; padding:0; color: var(--v2-text-text-faint); overflow-wrap: anywhere`；`strong{padding: 8px 12px; font-weight: 530}` | `summary.css:165-180` |
| `.session-service-message` | `padding: 6px 12px` | `summary.css:182-186` |
| `.session-service-config-separator` | `height:1px; width:calc(100% + 4px); margin: 2px -2px; background: var(--v2-border-border-muted)` | `summary.css:190-196` |
| `.session-service-config-arrow` | `opacity:0` → hover/focus `opacity:1`（`transition 150ms ease-out`）；focus 环 `2px solid var(--v2-border-border-focus)` + `offset 2px` | `summary.css:207-220` |
| `.session-service-dot` | `16×16` grid 居中；`::before` 内点 `6×6` 圆；`color: var(--v2-icon-icon-faint)` | `summary.css:222-237` |
| 状态点颜色 | `connected`/`active` → `var(--icon-success-base)`；`failed` → `var(--icon-critical-base)`；`needs_auth` → `var(--icon-warning-base)` | `summary.css:238-247` |
| `.session-service-status` | `flex-shrink:0; color: var(--v2-text-text-faint); line-height: var(--line-height-compact)` | `summary.css:261-266` |
| MCP 行禁用态 | `[aria-disabled="true"] [data-slot="switch-control"] { opacity: 0.5 }` | `summary.css:267-269` |

### A1.3.4 move 行与 dismiss

| 属性 | 值 | 行号 |
|---|---|---|
| `.session-summary-move` | `position:relative; padding: 10px 2px 2px; margin-top: **-6px**; border-radius: 0 0 6px 6px; background: var(--v2-background-bg-layer-02)`（前 6px 藏在卡下，露出 4px 可见间距） | `summary.css:270-277` |
| `.session-summary-move > .session-summary-row` | `padding-inline-end: 36px` | `summary.css:278-280` |
| `.session-summary-dismiss` | `absolute; inset-inline-end:8px; bottom:8px; **20×20**; border-radius:4px; color: var(--v2-icon-icon-muted)` | `summary.css:281-292` |

### A1.3.5 响应式与动画降级

| 规则 | 值 | 行号 |
|---|---|---|
| `@media (max-width: 767px)` | 面板行、服务标题/行/配置/mcp 行 `min-height: **44px**` | `summary.css:299-307` |
| `@media (prefers-reduced-motion: reduce)` | `.session-summary-popover` / `.session-service-menu` `animation:none`；config-arrow `transition:none` | `summary.css:309-318` |

## A1.4 数据来源（端点 / 字段）

| 面板内容 | 数据 | 上游调用 | 我们可用的 v1 端点 |
|---|---|---|---|
| 项目名/头像 | `Project.name`、`worktree`、`icon` | `ProjectSummaryCard` props（调用方传入） | `GET /project`（`OpenCodeClient.projects()` `OpenCodeClient.kt:874`） |
| 工作区名 | `workspaceDirectories(project)` + `directory` | `panel.tsx:40-41` | 本地计算（`GET /project` 的 worktree/sandboxes） |
| 分支 / 基线分支 | `location.vcs.info({directory}).branch.current` | `message-timeline.tsx:559-560`、`screen.tsx:207-213` | `GET /vcs`（`OpenCodeClient.vcsInfo()` `:1156`） |
| 变更条数 | `diffs`（`{additions,deletions}[]`） | `props.diffs` ← `review.details.diffs` | `GET /session/:id/diff`（`OpenCodeClient.sessionDiff()` `:1126`） |
| 后台任务 | `BackgroundTask{id,type:"shell"\|"subagent",label,agent?}` | `session/requests/background.ts:4-6, 85-115` | 需从 `GET /session/:id/message` 的 assistant tool parts（`shell`/`subagent` 工具、`state.metadata.shellID/sessionID`、`input.command/description`）＋ `GET /session/:id` 状态推导（上游算法见 `background.ts:15-115`） |
| MCP 列表与开关 | `data.location.mcp.server.list()` + `useMcpToggle` | `server-panel.tsx:157-275`、`providers/connect/mcp.ts` | `GET /mcp`（v1）；开关写配置（v1 `PATCH /config` 未确认） |
| Plugins 列表 | `sdk.api.plugin.list({location})`（**v2**），过滤 builtin，字段 `name/state.status/state.error` | `server-panel.tsx:284-293` | **未确认**（v1 无 plugin 端点；`GET /config` 的 `plugin` 键只能给「配置了哪些」，给不了运行时状态） |
| Skills 列表 | `data.location.skill.list()` | `server-panel.tsx:306-310` | `GET /skill`（`OpenCodeClient.skills()` `:1062`） |
| LSP 列表 | `configuredLsps(config.list())`：config documents 的 `info.lsp`，`boolean` → 清空；否则取未 `disabled` 的键名并排序 | `configured-lsp.ts:3-17` | `GET /config`（`OpenCodeClient.config()` `:854`） |
| 配置文件路径 / 复制 | `sdk.api.config.get({location}).filter(type==="document" && /\.jsonc?$/).at(-1).path`，优先 `info[service] !== undefined` 的 | `server-panel.tsx:457-464` | 同上（v1 `GET /config` 返回数组含 `path`） |

## A1.5 Android 落地建议（**建议**，非上游规格）

> ⚠️ 我的工程建议。当前 Compose 端 `app/src/main/java/com/xiufeigo/opencodeshell/` 下**没有** summary 面板实现，
> `ui/session/header/` 只有 `ContextRing.kt` / `ContextUsage.kt`（`SessionHeader.kt:305` 也自己写了「上游 Export 还没做」的注释）。

1. **形态选择**：移动端上游**不用 popover**，而是 `SessionMobileViewTabs` 的 `details` 整屏面板（`screen.tsx:202-225`）。
   Android 直接照这个形态做更省事：在 `SessionScreen` 的 header 右侧放一个 `window-analytics` 图标按钮，
   点击后 `ModalBottomSheet` 或整屏 `details` 页，不要做锚点 popover（Compose 没有等价 `gutter/overflowPadding` 语义）。
2. **必做子集（建议 P0）**：项目行（名 + 工作区名）、分支行（`/vcs`）、变更行（`/session/:id/diff` 计数，点击跳 review）、
   服务卡里的 **LSP + Skills + MCP** 三个二级菜单（三个都有 v1 端点），Plugins 可先「未确认/跳过」。
3. **数据获取批量化**：服务菜单的 sync 语义是「打开时刷新」`server-panel.tsx:161-170, 392`。Android 建议打开面板时并发
   `GET /config`、`GET /mcp`、`GET /skill`、`GET /lsp`（v1 是否有 `/lsp` **未确认**；`configured-lsp` 只读 config，所以没有 `/lsp` 也能出「已配置」列表，
   拿不到运行时状态而已），用 `viewModelScope` + `async` 并发，逐块 `state = Loading/Ready/Empty/Error`（对应上游 `ServicePopover:412-433` 的三态）。
4. **持久化的两个折叠开关**：上游是 `settings.sessionSummary.projectExpanded/serverExpanded`（`settings/model.tsx:137`，默认都 `true`）。
   Android 建议存进 `SettingsStore`（而不是 `rememberSaveable`），否则每次进会话都重置展开态。
5. **后台任务行**：`BackgroundWorkSummary` 的语义是「本项目/turn 里仍在跑的 shell 与 subagent」，
   上游从 tool parts + 子会话状态推导（`background.ts:85-115`），**不是**一个现成端点。
   建议 P1 再做；实现时要沿用它的两条去重规则（完成后由 synthetic 完成通知的 `childID/shellID/jobID` 剔除；被 `blocking` 项覆盖的同 id 项剔除）。
6. **不要移植的东西**：`SessionWorkspaceMenu`（move 到 worktree）与 `moveEligible/moveDismissed` 那一整套（`panel.tsx:116-139`）
   依赖 v2 worktree/workspace 放置语义；1.18.31 下本项目也没有 worktree 概念 → 建议直接不做，或在 `local` 时也隐藏。

## A1.6 未知项

1. `server-panel.tsx` 的 **Plugins** 菜单：v1 `1.18.31` 是否有可替代端点，**未确认**（上游走 v2 `api.plugin.list`）。
2. v1 是否有 `GET /lsp`：**未确认**；但 LSP 块的渲染只依赖 config，缺运行时状态不影响「已配置」列表。
3. MCP 开关的写入路径：上游 `useMcpToggle`（`providers/connect/mcp.ts`）实际写的是 **config**（`PATCH`/`config.update`），
   v1 的写接口与语义**未确认**；只读展示没有风险，写入需要单测确认。
4. `props.mcp?.preview`（新建会话场景的 MCP 预览）是另一条产品线（new-session），本次未展开。
5. `data.location.*` 的客户端缓存/失效语义（`invalidate/sync/list`）在 `@opencode/client` 内，**本地包不在**
   （`_upstream/packages/` 只有 app/schema/session-ui/theme/ui，没有 `client`）→ 只能按调用点推断，**未确认其内部实现**。

---

# A4 待发送队列面板（composer queue）

## A4.1 上游结构（逐块 + file:line）

### A4.1.1 文件清单

| 文件 | 行数 | 职责 |
|---|---|---|
| `_upstream/packages/app/src/session/composer/queue-panel.tsx` | 196 | 视图：抽屉容器 + 行 + 拖拽 + 动作按钮 |
| `_upstream/packages/app/src/session/composer/queue.ts` | 360 | 逻辑：数据模型、edit/steer/remove/reorder、delivery 语义、附件还原 |
| `_upstream/packages/app/src/session/composer/controller.ts` | 57 | 组装：`createSessionQueue` + `createComposerModel(adapter, { queue })` |
| `_upstream/packages/app/src/session/composer/region.tsx` | 230 | 挂载点：`<SessionQueuePanel queue={model.queue}/>` 在 composer 卡片上方（:216-229） |
| `_upstream/packages/app/src/composer/adapter.ts` | 110 | 契约类型：`ComposerDelivery = "steer" \| "queue"`（:42）、`ComposerQueue`（:48-60） |
| `_upstream/packages/app/src/composer/model.ts` | 484 | 提交路径：`queue.confirmEdit(...)`（:398-400）、`delivery(alternate)`（:271-275） |
| `_upstream/packages/app/src/composer/editor/editor.tsx` | 915 | 键位：`Mod+↑` → `editFirst()`（:207-210）、`Enter`/`Mod+Enter`（:211-215）、备用投递提示按钮（:824-865） |
| `_upstream/packages/app/src/session/timeline/controller.tsx` | 281 | steer 消息在时间线的提前显示：`pendingUserMessageIDs`（:74-81） |
| `_upstream/packages/app/src/session/requests/background.ts` | 117 | 后台任务（A1 用到；与队列**无**数据关系） |
| `_upstream/packages/app/src/runtime/server/global-sync/queue.ts` | 87 | ⚠️ **与本功能无关**：这是「按 directory 去重刷新 global-sync 引导」的调度队列（`createRefreshQueue`，:8-87），只是名字撞车 |

### A4.1.2 面板容器（`queue-panel.tsx:18-83`）

- 根：`<Show when={count() > 0}>`（`count = queue.rows().length`，:20, :23）→ 队列空时**整块不渲染**。
- 容器 `div[data-component="session-queue-panel"]`（:25）：
  `relative z-0 -mb-3 rounded-xl bg-v2-background-bg-base px-1.5 pt-1.5 shadow-[inset_0_0_0_0.5px_var(--v2-border-border-base)]`（:26）。
- 底部 padding 随条数切换：`count() > 4 → pb-3`（12px）；`count() <= 4 → pb-[18px]`（:28）。
- 计数行：仅 `count() > 3` 时渲染（:30-34）：`px-1.5 pt-1 pb-px text-[11px] font-[530] uppercase leading-[var(--line-height-tight)] tracking-[0.05px] text-v2-text-text-muted [font-variant-numeric:tabular-nums]`，
  文案 = `language.plural("session.queue.count", count())`（en：`{{count}} queued`）。
- 列表容器（:64-74）：`flex flex-col gap-px`；`count() > 4` 时 `max-h-[148px] overflow-y-auto` + mask
  `linear-gradient(to bottom, transparent, black 8px, black calc(100% - 12px), transparent)`。
  注释给出尺寸推导：**4 行 × 32px + 4 条 1px gap + 半行暗示还有更多**（:63）。
- 列表 `For` 的 key 是 `rows().map(row => row.id)`，注释明确「store 更新要移动行元素而不是重挂载，否则会杀掉进行中的拖拽」（:61-77）。

### A4.1.3 单行（`queue-panel.tsx:85-196`）

单行 = 一行 flex（**不是多行**），高度固定 32px：

`div[data-component="session-queue-row"]`（:107-115）
`class="group/queue-row flex h-8 shrink-0 items-center justify-between gap-2 rounded-md py-1 ps-1 pe-2"`（`:110`）
`classList`: 编辑中 → `bg-v2-overlay-simple-overlay-hover`；拖拽源 → `opacity-60`（:111-114）。

行内三段（无缩进层级——所有行同层，靠 `gap-px` 分隔）：

| 段 | 内容 | 行号 |
|---|---|---|
| 拖拽把手 | `button ref={sortable.handleRef}`，`grid shrink-0 cursor-grab touch-none grid-cols-2 gap-x-[2px] gap-y-[2.25px] p-1`，`aria-label = session.queue.reorder`（"Reorder queued prompt"），内容 = 6 个 `2×2px` 点（`bg-v2-background-bg-layer-04`） | :117-126 |
| 文本/编辑按钮 | `button[data-action="session-queue-edit"] dir="auto"`，`max-w-full min-w-0 self-start truncate rounded-sm text-start text-[13px] font-[440] leading-[var(--line-height-compact)]`；编辑中 `text-v2-text-text-faint`，否则 `cursor-text text-v2-text-text-base hover:bg-v2-overlay-simple-overlay-hover`；`onClick = queue.edit(id)`；文本 = `entry.text \|\| plural("session.queue.attachments", entry.attachments)` | :128-142 |
| 附件数徽标 | `Show when={entry.attachments && entry.text}` → `span` 紧随其后，`text-[13px] font-[440] leading-[var(--line-height-compact)] text-v2-text-text-muted`，文案 `session.queue.attachments` | :143-147 |
| 动作区 | `div[data-slot="session-queue-actions"]`，`flex shrink-0 items-center gap-1.5`；非首行/非编辑时 `opacity-0`，`focus-within`/`group-hover`/`[@media(hover:none)]` 时 `opacity-100`；busy 时 `pointer-events-none` | :150-158 |
| Steer 按钮 | `Button data-action="session-queue-steer" size="small" variant="ghost-faint" icon="arrow-up" class="![font-weight:530]"`；文案 = `working() ? session.queue.steer("Steer") : session.queue.send("Send")`；Tooltip = `session.queue.steerTooltip`（"Send without interrupting"），`inactive={!working()}`（即非工作中不弹） | :159-178 |
| 删除按钮 | `IconButton data-action="session-queue-remove" size="small" variant="ghost-muted" icon={<Icon name="outline-xmark"/>}`，`aria-label = session.queue.remove`（"Remove"），Tooltip 同文案 | :179-190 |

首行特殊态（`queue-panel.tsx:89-92`）：`active() = !working() && index === 0` —— **回合停止时队列保持停留（parked）**，
首行动作**不靠 hover 也常显**，且按钮文案读作 "Send"：这就是「停住的队列如何被重新发起」。

### A4.1.4 数据模型（`queue.ts`）

```ts
// queue.ts:19
export type QueuedPrompt = Extract<SessionInboxInfo, { type: "user" }>
```

`queued()` 过滤条件（`queue.ts:80-84`）：
`data.session.pending.list(sessionID)` → `.filter(item => item.type === "user" && item.delivery === "queue")`
→ **面板只显示 `delivery === "queue"` 的 user 项**；被 steer 的项从面板消失、改为提前显示在时间线里。

行视图模型（`queue.ts:245-254`）：

```ts
queuedPromptRows(items, replacement?) → { id: string; text: string; attachments: number }[]
// attachments = (payload.files?.length ?? 0) + (readPromptPresentation(payload.metadata)?.attachments.length ?? 0)
```

行文本（`queue.ts:256-259`）：`payload.metadata["displayText"]` 为非空字符串则用它，否则 `payload.payload.text`。

inbox 项本体（服务端契约，`_upstream/packages/schema/src/session-inbox.ts`）：

| 字段 | 类型 | 行号 |
|---|---|---|
| `delivery` | `"steer" \| "queue"` | `session-inbox.ts:11` |
| `type` | 标签联合 `user`/`synthetic`/`compaction`/`move` | `session-inbox.ts:37-49` |
| `User.payload` | `Prompt.fields`（`text`、`files[]`、`agents[]`、`skills[]`）+ `metadata?` | `session-inbox.ts:15-18` |
| `Enqueued` 公共字段 | `id: SessionMessage.ID`、`sessionID`、`time.created` | `session-inbox.ts:52-56` |
| `files[].source` | `{type:"inline"}` / `{type:"uri", uri}` | 由 `queue.ts:290-292` 与 `queue.test.ts:63,99` 反推 |
| `files[]` 其它字段 | `data`(base64) / `mime` / `name?` / `description?` / `mention?{start,end,text}` | `queue.ts:115-121`、`queue.test.ts:63-64` |

### A4.1.5 挂载位置与容器关系

```
ActiveSessionComposerRegion            region.tsx:216-229
└ <div class="relative">
   ├ <SessionQueuePanel queue={model.queue} />      region.tsx:222   ← z-0，-mb-3 滑到 composer 卡片下面
   └ <div class="relative z-10"><Composer model borderUnderlay /></div>   region.tsx:223-225
```
Composer 卡片本体：`class="group/composer relative min-h-[96px] w-full overflow-clip rounded-xl bg-v2-background-bg-base"`
（`composer/editor/editor.tsx:139`）——**`overflow-clip` + 不透明 `bg-base` 是「负 margin 被裁掉、两个面看起来连成一体」的关键**（`queue-panel.tsx:15-17` 注释明说）。

## A4.2 交互与状态

| 交互 | 行为 | 位置 |
|---|---|---|
| **投递语义（delivery）** | `delivery() = working() ? behavior() : "steer"`；`behavior` = 设置 `settings.general.followUpBehavior`（默认 **`"steer"`**，可 `"queue"`） | `queue.ts:217`、`settings/model.tsx:96, 253` |
| **备用投递（alternate）** | 编辑中恒 `"steer"`；非工作中 `undefined`（隐藏提示）；否则取 `behavior` 的反面 | `queue.ts:218-222` |
| Enter / Mod+Enter | `Enter`（非 shift、非 composing、非 repeat）→ `controller.submit()`（用 `delivery()`）；`Mod+Enter` → `submit({alternate:true})`（用 `alternate()`） | `editor.tsx:211-215` |
| 提示按钮 | `composer-alternate-delivery`：文案 `ui.promptInput.steer`("Steer") / `ui.promptInput.queue`("Queue") + 键位胶囊；`Tooltip inactive={delivery!=="steer"}`，值 `ui.promptInput.steerHint`；仅在 `queue.alternate()` 有值（回合运行中且输入框有内容）时出现，回合结束自动消失 | `editor.tsx:824-865`（`createMemo` :831-836） |
| Mod+↑ | `view.submit.queue?.editFirst()` → 把**第一条**排队项载入 composer；返回 true 才 `preventDefault()` | `editor.tsx:207-210`、`queue.ts:209-213` |
| 点行文本 → 编辑 | `edit(id)`：把当前草稿 stash 起来（prompt/cursor/mode/retry，`queue.ts:21-26, 154-162`），模式置 `normal`，把「排队项文本 + 还原的附件」写进输入框并 `restoreFocus(text.length)` | `queue.ts:147-171` |
| 编辑中取消 | Esc（编辑器侧）→ `cancelEdit()`：**先 mode、再 prompt、最后 retry**（注释：mode 与 prompt 写入都会清 retry 标记） | `queue.ts:172-182` |
| 编辑中确认 | `confirmEdit(delivery)`：空文本且无附件 → 当作取消；与原件「完全一致且 delivery===queue」→ 直接取消；否则走 mutation | `queue.ts:183-208` |
| 编辑 = 替换 | 先 **admit 新条目**（`data.session.prompt({id: replacement, delivery, ...(queue? {resume:false}:{})})`）再 `inbox.cancel(original)`（注释：先 admit 保证失败不丢原件）；`delivery==="queue"` 时再 `rewrite` 把新 id 插回原位 | `queue.ts:64-75` |
| 删除 | `remove(id)` → `server.api.session.inbox.cancel({sessionID, inboxID})`，失败 toast `common.requestFailed` | `queue.ts:138-141, 40` |
| Steer 一条 | `steer(id)` → `server.api.session.inbox.update({..., delivery:"steer"})`（**不是**重新 prompt） | `queue.ts:132-137` |
| 拖拽排序 | dnd-kit：`DragDropProvider`；`PointerSensor` 距离阈值 **4px**；修饰器 `RestrictToVerticalAxis` + `RestrictToElement(listRef)`；插件换 `AutoScroller acceleration:8, threshold:{x:0,y:0.05}` + `Feedback dropAnimation:null`；`onDragEnd` 用 `arrayMove(rows.map(r=>r.id), initialIndex, index)` 调 `reorder`；相同 index 或 canceled 直接返回 | `queue-panel.tsx:35-59` |
| 排序的实现代价 | `rewrite(inboxIDs)`：先 `inbox.list`；**若存在 `delivery==="queue" && type!=="user"` 的控制项则抛错**「Queued control items block reordering」（:102-103）；按期望顺序重排当前队列、长度不等抛「Queued prompts changed before reordering」（:105-106）；只对**变化后缀**逐条 `data.session.prompt({...payload, delivery:"queue", resume:false})` 重新 admit，再逐条 `inbox.cancel` 旧 id（注释：现有 inbox API 不能重排，只能替换变化的后缀）（:110-130） |
| 草稿编辑自动失效 | `createEffect`：编辑项已不在队列里且非 mutation 中 → 清 `editing`（:93-97）；`onCleanup(cancelEdit)`（:98） |
| 行列表的替换态 | `rows()` 在 mutation 进行中注入 replacement，让「原件 → 新件」在视觉上只是一行的 id 变化（`queuedPromptRows` 的 filter，:245-254；测试 `queue.test.ts:25-53`） |
| 时间线里的 steer | `pendingUserMessageIDs` = `type==="user" && delivery==="steer"` 的 id 集合，用于把 steer 内容提前显示在时间线上（`timeline/controller.tsx:74-81`）；另有判定 `!pending.list(id).some(i => i.type==="user" && i.delivery==="steer")`（`message-timeline.tsx:343`） |
| 未读/忙态门 | 所有动作 `disabled={queue.busy()}`（mutation pending）；`reorder` 在 busy 时直接返回 `Promise.resolve()`（:142-145）；`edit` 在 busy 时返回 false（:148） |

## A4.3 样式规格（数值 / token）

| 元素 | 属性 | 值 | 行号 |
|---|---|---|---|
| 抽屉容器 | 位移 | `-mb-3`（== 与 composer 重叠 12px） | `queue-panel.tsx:26` |
| | 圆角 | `rounded-xl`（pill 级圆角，与 composer 卡一致） | `queue-panel.tsx:26` |
| | 背景 | `bg-v2-background-bg-base`（不透明，盖住 composer 卡顶边） | `queue-panel.tsx:26` |
| | padding | `px-1.5 pt-1.5` = 6px / 6px | `queue-panel.tsx:26` |
| | 描边 | `shadow-[inset_0_0_0_0.5px_var(--v2-border-border-base)]`（0.5px 内描边，不是 border） | `queue-panel.tsx:26` |
| | 底部 padding | `>4 行 → pb-3`(12px)；`<=4 行 → pb-[18px]` | `queue-panel.tsx:28` |
| | z 序 | 面板 `z-0`，composer 包裹 `z-10` | `queue-panel.tsx:26`、`region.tsx:223` |
| 计数行 | 字号/字重/行高/字距 | `11px / 530 / var(--line-height-tight)`(=12px) / `0.05px`，`uppercase`，`tabular-nums` | `queue-panel.tsx:31` |
| 列表 | 行间距 | `gap-px`（1px） | `queue-panel.tsx:66` |
| | 滚动上限 | `count>4 → max-h-[148px]` + `overflow-y-auto`（= 4×32 + 4×1 + 半行暗示） | `queue-panel.tsx:63, 67` |
| | 上下渐隐 | `mask-image: linear-gradient(to bottom, transparent, black 8px, black calc(100% - 12px), transparent)` | `queue-panel.tsx:69-72` |
| 行 | 高度 / 圆角 / 内距 | `h-8`(32px) / `rounded-md` / `py-1 ps-1 pe-2`(=4px / 4px / 8px) | `queue-panel.tsx:110` |
| | 文字 | `13px / 440 / var(--line-height-compact)`(=16px) | `queue-panel.tsx:133, 144` |
| | 行内间距 | `gap-2`(8px)；动作区 `gap-1.5`(6px) | `queue-panel.tsx:110, 152` |
| 把手点阵 | 点尺寸/间距 | `2×2px`；`grid-cols-2`，列间 `2px`，行间 `2.25px`；容器 `p-1`(4px) | `queue-panel.tsx:120, 124` |
| 拖拽态 | 源行 | `opacity-60`；拖拽中 `cursor-grab` + `touch-none` | `queue-panel.tsx:113, 120` |
| 动作区 | 渐显 | `opacity-0` → hover/focus-within/无 hover 设备 → `opacity-100` | `queue-panel.tsx:154` |

颜色 token：`--v2-border-border-base` light `#0000001a` / dark `#ffffff1a`（`theme.css:41, 178`）；
`--v2-overlay-simple-overlay-hover` 见 §A1.3.2；`bg-v2-background-bg-layer-04` dark `#5c5c5c`（`theme.css:394`→`colors.css:11`）。

## A4.4 数据来源（端点 / 字段）

上游走的是 **v2 inbox**（`_upstream/packages/app/e2e/utils/mock-server.ts` 与
`_upstream/packages/app/e2e/regression/session-queue.spec.ts` 把契约钉得很死）：

| 用途 | 端点 / 调用 | 证据 |
|---|---|---|
| 读队列 | `server.api.session.inbox.list({sessionID})`（`rewrite` 用）＋ 本地 store `data.session.pending.list(id)` / `pending.sync(id)` | `queue.ts:101, 77`；`timeline/model.ts:22` |
| 入队 | `data.session.prompt({ text, files[], agents, skills, metadata, delivery, resume })`；`delivery:"queue"` 时必须带 `resume:false` | `queue.ts:65-70, 111-127`；e2e `session-queue.spec.ts:56-76, 232` |
| 转 steer | `server.api.session.inbox.update({sessionID, inboxID, delivery:"steer"})` | `queue.ts:132-137`；e2e 期望 `session.inbox.delivery.changed`（`session-queue.spec.ts:88-94`） |
| 删除 | `server.api.session.inbox.cancel({sessionID, inboxID})` | `queue.ts:71, 129, 140`；e2e 期望 `session.inbox.cancelled`（`session-queue.spec.ts:85`） |
| 事件 | `session.inbox.enqueued` / `session.inbox.delivered` / `session.inbox.cancelled` / `session.inbox.delivery.changed` | `session-queue.spec.ts:71-93, 334` |
| 客户端 store | `data.session.pending.{list,sync}`（`@opencode/client/solid` 的 `Data`，包不在本地） | `queue.ts:81, 77`；`runtime/server/current.tsx:26` |

## A4.5 Android 落地建议（**建议**，非上游规格）

> ⚠️ 先说结论：**在当前 server 上不建议动手**。理由见 §A4.6 与 §A8.4；如果坚持要 UX，
> 只能用「本地队列」近似，但那会与上游语义分叉（见下面第 3 条）。

1. **端点阻塞（硬）**：本 App 目前是**纯 v1**（`OpenCodeClient` 只拼 `/session`、`/session/:id/message`、`/session/:id/prompt_async` 等，
   `OpenCodeClient.kt:43-50, 86-90, 922-936, 977`），没有 `session.inbox.*`。1.18.31 也没有这些端点。
   → 先做**能力探测**（例如 `GET /api/session/<id>/inbox` 返回 404 还是 HTML 兜底）再决定是否暴露入口。
2. **若将来 server 升级**：需要的新 API 是
   `GET /session/:id/inbox`（列表）、`inbox.update(delivery)`、`inbox.cancel`、`prompt{delivery,resume:false}`。
   建议照上游的 4 个事件（enqueued/cancelled/delivery.changed/delivered）做 SSE → 本地列表增量，而不是轮询。
   `OpenCodeClient` 里新增方法要纯加法，沿用 `pagedUrl/call` 既有模式。
3. **本地近似方案（不推荐，但可作降级）**：`ChatViewModel` 里维护 `pendingLocal: List<QueuedPrompt>`，
   `working==true` 时按 `followUpBehavior` 决定 `Enter` 是立刻 `POST /session/:id/prompt_async`（= steer）
   还是只压进本地列表、等 `session.idle`（v1 事件名 **未确认**，需按现有 `EventStream` 实测）再逐条发。
   风险点：App 进程被杀就丢队列（上游是服务端持久化的 `session_inbox` 行）；
   且**无法区分 steer/queue 在时间线上的表现**（上游靠 `pendingUserMessageIDs` 提前渲染，见 `timeline/controller.tsx:74-81`）。
4. **UI 骨架**（若做）：一行 32dp、`rounded-md`、`13sp/440`、行内 8dp 间距、把手 6 个 2dp 点、
   动作区默认透明 + hover/focus 才显（Android 无 hover → 常显，对应上游 `[@media(hover:none)]:opacity-100`）、
   计数行只在 `>3` 条时出现、`>4` 条时列表限高 148dp（4 行 + 半行暗示）+ 上下 8px/12px 渐隐。
   拖拽建议用 Compose `LazyColumn` + `detectDragGesturesAfterLongPress` 自己实现（dnd-kit 的
   `RestrictToVerticalAxis` + `AutoScroller` 语义要手工对齐：只允许竖直、距离阈值 4dp、自动滚动加速）。
5. **首行特殊语义要抄**：回合停止且队列非空时，首行**不靠交互也常显动作**、按钮文案读 "Send"（`queue-panel.tsx:89-92, 175`）。
   这是「停住的队列怎么被重新点火」的唯一入口，漏掉会导致停止后无法恢复队列。

## A4.6 未知项

1. **v1 是否有任何等价队列端点**：`未确认`（1.18.31 的 `/doc` 未在本次任务范围内复核；Lead 的实测是 v2 inbox 路径不可用）。
2. `data.session.pending` 的客户端载荷是否与 `SessionInboxInfo` 完全一致、排序是 `time.created` 升序还是服务端返回顺序：
   **未确认**（`_upstream/packages/client` 不在本地）。上游 `queue-panel` 直接信任数组顺序
   （`rows()[0]` 就是「下一条要送的」，`queue-panel.tsx:92`），所以**顺序语义必须先实测确认**再照抄。
3. `rewrite()` 里「控制项阻挡重排」的报错只抛给 mutation 的 `onError`（toast `common.requestFailed`，`queue.ts:40, 76`），
   面板上没有专门文案 → **未确认**是否有更好的产品化文案。
4. `session.queue.delivery`（若存在这样的 i18n key）：本次未找到，**未确认**上游是否给 delivery 本身单独文案
   （只有 `ui.promptInput.steer/queue` 与 `session.queue.steer/send`）。
5. `runtime/server/global-sync/queue.ts` 与队列面板**无关**（`createRefreshQueue`，:8-87）——命名撞车，勿误接。

---

# A8 会话导出与归档

## A8.1 导出：上游结构（逐块 + file:line）

### A8.1.1 `session/commands/export.ts`（复核 + 补全，68 行）

| 导出物 | 行号 | 规格 |
|---|---|---|
| `SessionExportData` | :5-8 | `{ info: SessionInfo; messages: SessionMessageInfo[] }` — **只有这两个字段**，无游标、无版本号 |
| `fetchSessionExport({sessionID, api})` | :10-34 | 并发发两个请求：`api.session.get({sessionID})` 与 `api.message.list({sessionID, **limit: 200, order: "asc"**})`（:14-17，`Promise.all`） |
| **分页策略** | :18-28 | `pages = [first]`；循环条件 `pages.at(-1)?.cursor.next`（**cursor.next 为假即停**）；后续页只传 `{sessionID, limit:200, cursor: <上一页的 cursor.next ?? undefined>}`，**不再传 `order`**（第 1 页传了 `order:"asc"`，后续页依赖服务端游标顺序）；返回值 `messages = pages.flatMap(p => p.data)`（:32，**丢掉每页的 cursor**） |
| 每页大小 | :16, 24 | **200**（常量内联，非具名常量） |
| **文件名规则** `sessionExportFilename` | :36-43 | 1) `name = session.title \|\| session.slug \|\| session.id`（:37）；2) `name.toLowerCase()`（:39）；3) `.replace(/[^a-z0-9_-]+/gi, "-")`（**非** `[a-z0-9_-]` 的连续串折成一个 `-`）（:40）；4) `.replace(/^-+\|-+$/g, "")` 去首尾 `-`（:41）；5) `${clean \|\| session.id}.json`（:42，全部被清空时回退原始 id，**注意此处不再做清洗**） |
| 浏览器下载 `downloadSessionExport` | :45-56 | `JSON.stringify(data, null, 2)` → `Blob(type:"application/json")` → `URL.createObjectURL` → 动态 `<a download>` → `appendChild/click/removeChild` → `URL.revokeObjectURL` |
| **保存路径分支** `saveSessionExport` | :58-68 | `if (!platform.saveFile) { downloadSessionExport(...); return true }`（:63-66）；否则 `return platform.saveFile({ defaultPath: filename }, JSON.stringify(data, null, 2))`（:67）——**返回 boolean**，`false` = 用户取消 |
| `Platform.saveFile` 签名 | `runtime/platform/platform.tsx:68` | `saveFile?(opts: SaveFilePickerOptions, content: string): Promise<boolean>` → 桌面用原生保存对话框；纯浏览器没有该能力 → 走下载 |

单测佐证（`session/commands/export.test.ts`）：`"Clone PR in worktree from fork"` → `clone-pr-in-worktree-from-fork.json`（:9-12）；
有 slug 用 slug（:15）；都没有用 id（:19）；`platform.saveFile` 返回 false 时 `saveSessionExport` 返回 false（:70）；
返回 true 时返回 true（:83）；session 不存在时 reject `"Session not found"`（:56）。

### A8.1.2 四个调用点的 toast 文案等值

| 调用点 | 行号 | toast 成功 / 失败 |
|---|---|---|
| `home/sessions/controller.tsx` `exportSession` | :169-190 | 成功 `variant:"success" icon:"circle-check"`，title `toast.session.export.success.title`，description `toast.session.export.success.description` 带 `{filename}`（:177-182）；失败 `variant:"error"`，title `toast.session.export.failed.title`，description = `cause.message` 或 `toast.session.export.failed.description`（:184-188）；`saveSessionExport` 返回 false（取消）→ **静默返回**（:176） |
| `session/files/session-context-tab.tsx` | :195-218 | 同上（:205-217） |
| `session/commands/use-session-commands.tsx` | :98-121 | 同上（:108-120） |
| `session/timeline/controller.tsx` | :171-189 | 同上（:176-188） |

i18n 值（`runtime/i18n/en.ts`）：
`toast.session.export.success.title` = "Session exported"（:637）；
`toast.session.export.success.description` = "Saved session to {{filename}}"（:638）；
`toast.session.export.failed.title` = "Failed to export session"（:639）；
`toast.session.export.failed.description` = "An error occurred while exporting the session"（:640）。

UI 入口文案：Home 会话行菜单 `Menu.Item` → `common.export`（"Export"）+ 尾随 `…`（`home/sessions/view.tsx:695-697`）；
时间线标题菜单 → `common.export`（"Export"）+ `…`（`session/timeline/message-timeline.tsx:526-528`，仅 `Show when={!parentID()}` 之外).

## A8.2 归档：上游结构（逐块 + file:line）

### A8.2.1 `home/sessions/archive.ts`（25 行）

```ts
// archive.ts:7-25
export async function archiveHomeSession(input: {
  server: ServerConnection.Key
  session: HomeSession            // Pick<SessionInfo, "id" | "location">  (:5)
  archive: (sessionID: string) => Promise<unknown>
  remove: () => void
  onError?: (error: unknown) => void
})
```
流程：`await input.archive(input.session.id)` → `.then(() => { input.remove(); notifySessionTabsRemoved({
server, directory: input.session.location.directory, sessionIDs: [input.session.id] }) })`（:15-23）→ `.catch(error => input.onError?.(error))`（:24）。
即：**服务端归档 → 本地列表移除 → 通过 window 事件通知 titlebar 关闭「已删除」的标签页**。

`notifySessionTabsRemoved`（`shell/titlebar/session-events.ts:11-13`）：`window.dispatchEvent(new CustomEvent("opencode:session-tabs-removed", { detail: {server, directory, sessionIDs} }))`；事件常量 `:3`；
接收端 `shell/titlebar/titlebar.tsx:265-266`（`makeEventListener(window, SESSION_TABS_REMOVED_EVENT, ...)` + `readSessionTabsRemovedDetail`，:15-34 做严格校验）。

### A8.2.2 `home/sessions/index.ts` 的 `time.archived` 过滤

| 位置 | 规则 | 行号 |
|---|---|---|
| `parseHomeSessionIndex(sessions)` | `sessions.filter(s => !s.parentID && typeof s.time.archived !== "number")` —— 用 **`typeof === "number"`** 而不是真值判断（0 也算已归档） | `index.ts:49-52` |
| `loadHomeSessionIndex` | 每页 `limit: HOME_V2_SESSION_PAGE_LIMIT = 5_000`、`order:"desc"`、`parentID: null`；页大小不足或 `!cursor.next` 即停；每页到达即折叠进 `retainHomeSessions` | `index.ts:5, 14-47` |
| `retainHomeSessions` | 每个 directory 先按 `updated ?? created` 降序、id 升序破平，取前 `limit`，再补最近窗口（`SESSION_RECENT_WINDOW`/`SESSION_RECENT_LIMIT`） | `index.ts:60-71` |
| `mergeHomeSessionIndex` | 合并 fetched + known（按 id 去重，后到者覆盖）后**再走一遍** `parseHomeSessionIndex` | `index.ts:54-58` |

其它 `archived` 消费点（同一语义，全部是「隐藏」而非「删除」）：

| 位置 | 行号 |
|---|---|
| `workspaces/paths.ts`：工作区会话过滤 | `paths.ts:38`、`:72`（`session.time.archived === undefined`） |
| `shell/layout/helpers.ts`：`pathKey(directory)===` 且 `!parentID && !session.time.archived` | `helpers.ts:20` |
| `shell/commands/palette.ts`：命令面板过滤 | `palette.ts:272` |
| `home/sessions/controller.tsx`：`lookup(sessionID)` 若 `result.time.archived` 直接返回 undefined | `controller.tsx:263` |
| `session/timeline/controller.tsx`：删除后选「下一条」时排除 archived | `controller.tsx:193` |
| 类型定义 | `packages/schema/src/v1/session.ts:560-565`：`time: {created, updated, compacting?, archived?}`；`schema/src/session.ts:50` 是 v2 侧 `archived: DateTimeUtcFromMillis.pipe(optional)` |

### A8.2.3 ⚠️ 上游自己就把归档关掉了（这是最关键的证据）

| 证据 | 行号 |
|---|---|
| Home 行的归档按钮被 feature flag 关掉：`const SHOW_HOME_SESSION_ARCHIVE = false` | `home/sessions/view.tsx:27`（消费点 `:705-727`，按钮 `data-action="home-session-archive"`、`Icon name="archive"`、`aria-label = common.archive`） |
| controller 的 archive 是**恒拒绝**的占位：`archive: async (_sessionID) => Promise.reject(new Error("Session archiving is unavailable"))`，注释 `// TODO: Restore archiving when the V2 client exposes a session archive API.`；`remove() {}` 是空实现 | `home/sessions/controller.tsx:290-306`（关键行 :297-299） |
| 失败路径 → toast `common.requestFailed` + `errorMessage(cause, …)` | `controller.tsx:300-305` |
| 时间线标题菜单里归档项也被移除，只留注释 `{/* TODO: Need a session archive API. */}` | `session/timeline/message-timeline.tsx:530-536` |

## A8.3 Android 落地建议（**建议**，非上游规格）

### A8.3.1 导出：可行，且**仓库里已经有一半**

- **现状（重要）**：`app/src/main/java/com/xiufeigo/opencodeshell/ui/session/export/SessionExport.kt` **已经存在**
  （68 行，纯逻辑：`filename()` :32-50、`joinPages()` :57、`serialize()` :60-67，`INDENT = 2` :24）。
  它注释里已对齐 `export.ts:10-43`。**但 `SessionExportSaver`（SAF 落盘）尚不存在**（同包只有这一个文件），
  调用方也还没接（`grep oc_export/SessionExport` 在 `SessionScreen.kt` 等 UI 层无命中）。
- **建议的做法（与上游等价映射）**：
  | 上游 | Android |
  |---|---|
  | `fetchSessionExport` | `OpenCodeClient.session(id)` → `Session.fromJson` 原始 `JSONObject`（**需要新增一个返回 `JSONObject` 的重载**，现有 `session()` 只回数据类，丢字段）＋ `OpenCodeClient.messages(id)`（v1 版**无分页**，见下） |
  | 分页（200/页 + cursor） | v1 `GET /session/:id/message?limit=N` **没有 cursor**（Lead 实测 `?before=` 400）→ 只能一次全量或「limit 拉最新 N 条」。**导出必须全量**，建议不带 `limit`（与「导出全部历史」的产品语义一致） |
  | `saveSessionExport` → `platform.saveFile` | SAF `ActivityResultContracts.CreateDocument("application/json")`：`launcher.launch(filename)`；回调拿到 `uri` 后 `context.contentResolver.openOutputStream(uri)` 写 `SessionExport.serialize(...)` 的字符串。已有同类 SAF 用法可参考：`ui/session/composer/AttachmentPicker.kt:49-51`（`OpenMultipleDocuments` + `rememberLauncherForActivityResult`） |
  | 取消（返回 `false`）→ 静默 | `CreateDocument` 回调返回 `null` uri → 静默返回，不弹 toast |
  | toast 成功/失败 | 复用现有 `strings.xml` 文案键或新增 `oc_export_*`（三份 strings 只追加，参照 W5-C 的 `oc_composer_*` 前缀约定） |
- **建议的落点是时间线标题菜单**（`SessionHeader.kt` 的 `⋮` 菜单，`research/audit_session_ui.md:561` 已记录该菜单缺 Export…），
  与上游 `message-timeline.tsx:526-528` 一致；Home 行菜单是次选。
- **注意文件名规则**：上游对**已清洗过**的名字不再二次兜底（`clean || id` 里的 id 未清洗）；
  现有 Kotlin `SessionExport.filename()` 已按此实现（`SessionExport.kt:49`），**不要**改成再清洗一遍。
- **JSON 格式细节**：`JSONObject.toString(2)` 与 `JSON.stringify(data, null, 2)` 在**浮点数与 `\\u` 转义**上未必逐字节一致
  （Android `org.json` 不保证键序、数字格式），所以「导出 JSON 与上游字节一致」**未确认**，不建议写成断言。

### A8.3.2 归档：在当前 server **不可实现**（证据链）

1. **v1 服务端不返回 `archived`**：`GET /session` 的 `time` 只有 `created`/`updated`（Lead 实测）。
   上游 UI 的隐藏逻辑完全建立在 `typeof session.time.archived === "number"` 上（`home/sessions/index.ts:51` 等）→ 字段缺失 ⇒ 永远不隐藏。
2. **v2 归档端点未实现**：`GET /api/session/<id>/archive` 返回 `text/html`（SPA 兜底）→ 不是真端点。
3. **上游自己也是关闭状态**：`SHOW_HOME_SESSION_ARCHIVE = false`（`view.tsx:27`）+ `Promise.reject` 占位（`controller.tsx:297-299`）。
   → 结论：Android **不要实现归档**；如果产品一定要「从列表里去掉」，只能做**本地隐藏**，并且必须：
   - 明确标注为「本机隐藏」，不是服务端归档；
   - 存 `SettingsStore`/Room 的 `hiddenSessionIds`，并**在所有列表/命令面板/工作区分组处统一过滤**
     （对应上游 5 个消费点：`paths.ts:38,72`、`helpers.ts:20`、`palette.ts:272`、`controller.tsx:263`、`timeline/controller.tsx:193`）；
   - 不要发任何 HTTP 请求（避免依赖 404/HTML 兜底行为）。
4. 若要**探测**能力（未来 server 升级用于自动开启）：建议 `GET /api/session/<id>/archive` 或
   `GET /session/<id>` 的响应里查 `time.archived` 是否存在，二者都失败即判定不支持。探测结果应缓存，避免每次进列表都打。

## A8.4 未知项

1. v1 `SessionInfo` 的完整字段清单里 `time.archived` 是否存在：schema 有（`packages/schema/src/v1/session.ts:564`），
   但 **1.18.31 的服务端不返回**（Lead 实测）→ **「schema 有、运行时没有」是已知的 schema/实现落差**，不要以 schema 为准。
2. v1 `GET /session/:id/message` 的 `before` 参数语义：Lead 实测 `400`，**未确认**正确用法；
   因此 Android 端**不要**指望反向分页做出「导出全部历史」以外的能力。
3. `platform.saveFile` 在桌面端的真实实现（Electron 主进程通道）**未确认**（`runtime/platform/platform.tsx` 只有可选签名 `:68`）；
   浏览器分支才是 `downloadSessionExport`。Android 映射到 SAF 是**我们的选择**，不是上游规格。
4. Home `loadHomeSessionIndex` 用的是 **v2 分页端点**（`limit/order/parentID/cursor`，`index.ts:14-46`）；
   v1 的 `/session` 列表排序/分页语义不同 → Android 的 Home 会话列表**不要**照抄这套分页参数（当前 `listSessions()` 只发 `?directory=`，`OpenCodeClient.kt:847-848`）。
5. `toast.session.export.*` 的 `description` 插值参数名（`{{filename}}`）在中文本地化文件里是否已存在：**未确认**（只核了 `en.ts`）。

---

## 9. 实现优先级建议表

| 项 | 工作量子项 | 工作量级 | 依赖 | server 限制 |
|---|---|---|---|---|
| **A1 摘要面板** | 面板外壳 + 项目/分支/变更行 | **中** | `GET /project`、`GET /vcs`、`GET /session/:id/diff`，已有 `ChatViewModel`；需新增「summary 折叠态」持久化 | **不阻塞**（v1 端点可用） |
| | 服务卡：Skills / MCP / LSP 三个二级菜单 | 中 | `GET /skill`、`GET /mcp`、`GET /config`；MCP **写入**路径未确认（先只读） | 读不阻塞；写未确认 |
| | 服务卡：Plugins 菜单 | 小 | v2 `plugin.list` | **阻塞**（v1 无等价端点，未确认） |
| | move 行 / worktree | 小 | v2 workspace/worktree | **阻塞**（建议不做） |
| | 后台任务行（BackgroundWorkSummary） | 中 | 从 message tool parts + 子会话状态自行推导（无端点） | 不阻塞但成本高，建议 P1 |
| **A4 队列面板** | 全部 | **大** | v2 `session.inbox.{list,update,cancel}` + `prompt{delivery,resume}` + 4 个 inbox 事件 + 客户端 pending store | **完全阻塞**（1.18.31 无 inbox；本 App 纯 v1）。可做「本地队列」降级，但与上游语义分叉、丢持久化 |
| **A8 导出** | 纯逻辑（文件名 / 拼接 / 序列化） | **小（已完成）** | `ui/session/export/SessionExport.kt` 已存在且已对齐 `export.ts:36-43` | 不阻塞 |
| | SAF 落盘 + 菜单入口 + toast | **小–中** | 需新增「返回原始 `JSONObject` 的 `session()`」以保留 info 全量字段；`CreateDocument("application/json")`；`SessionHeader` 菜单 | 不阻塞（v1 全量 `GET /session/:id/message` 可用；**cursor 分页不可用**，只能全量） |
| **A8 归档** | 全部 | **小（但不建议做）** | 需要 `session.archive` API + `time.archived` 字段 | **不可实现**（三重证据：v1 无字段 / v2 端点 HTML / 上游自己 `SHOW_HOME_SESSION_ARCHIVE=false` + `Promise.reject`）。只能做「本地隐藏」，须标注非服务端归档 |

**推荐顺序**：A8 导出（最小、纯增量、已有纯逻辑，只差 SAF + 菜单）→ A1 的「项目/分支/变更 + Skills/LSP/MCP 只读」（中等，端点齐）→ A1 后台任务 → A4 / A1-Plugins / A1-move 等 server 解封后再做。

---

## 10. 引用索引（本次实际读过的文件）

上游（`_upstream/packages/`）：
`app/src/session/summary/{panel.tsx,popover.tsx,project-card.tsx,server-panel.tsx,background.tsx,summary.css,configured-lsp.ts}`、
`app/src/session/composer/{queue.ts,queue-panel.tsx,controller.ts,region.tsx,queue.test.ts}`、
`app/src/session/{commands/export.ts,commands/export.test.ts,commands/use-session-commands.tsx,timeline/controller.tsx,timeline/message-timeline.tsx,files/session-context-tab.tsx,screen.tsx,list.ts,requests/background.ts,requests/model.ts}`、
`app/src/home/sessions/{archive.ts,index.ts,controller.tsx,view.tsx,region.tsx}`、
`app/src/composer/{adapter.ts,model.ts,editor/editor.tsx}`、
`app/src/runtime/{i18n/en.ts,server/current.tsx,server/global-sync/queue.ts,platform/platform.tsx}`、`app/src/settings/model.tsx`、
`app/src/shell/titlebar/session-events.ts`、`app/e2e/regression/session-queue.spec.ts`、
`schema/src/session-inbox.ts`、`schema/src/v1/session.ts`、`schema/src/session.ts`、
`ui/src/styles/tokens/{theme.css,colors.css}`、`ui/src/navigation/menu/menu.css`、`ui/src/i18n/en.ts`。

本仓库（只读）：`app/src/main/java/com/xiufeigo/opencodeshell/{OpenCodeClient.kt,ChatViewModel.kt}`、
`app/src/main/java/com/xiufeigo/opencodeshell/ui/session/export/SessionExport.kt`、
`app/src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/AttachmentPicker.kt`、
`research/{wave5_composer.md,audit_session_ui.md,audit_api_events.md,04_data_api.md}`。
