# V2 Parity Audit — 2026-09-26（只读对比审计）

> 基线：`_upstream/` = `anomalyco/opencode@v2` 快照（`packages/protocol` v**2.0.11**，git `702a73d` 2026-09-20）；文档站 [opencode.ai/v2/docs/api](https://opencode.ai/v2/docs/api/)（本次实测提取 126 个 Operation ID，与快照路由表同一集合）。
> 上游路由表：`_upstream/packages/protocol/src/groups/*.ts` 共 **136 个 `HttpApiEndpoint`**，归一化后 **111 条唯一路径**；本次以 `builder(...)`/`postUnit(...)`/`addPathSegment(...)` 全量反查我方 `v2/V2Api*.kt`，**111/111 命中**（详见 ✅ 段）。
> 约束：本次**只读**，未改任何代码（唯一写入本文件）；未动 git/模拟器/adb/后端；`Read.line_offset` 坏，一律 `sed -n 'X,Yp'`。
> 承前：`research/parity-audit-2026-09-24.md` 的 ❌（F-1 slash 别名 / F-2 行评论 / F-3 内嵌 browser / F-4 move 入口）本轮**复查已闭环**，不再列。**有意偏离**（picker/Models 全量、⋮ 仅 Export、context 圆环直达、websearch consent 无记忆、字体 2 档、mobile `/terminal` App 级）与**已知在修**项按约定不列。

## 三行摘要

- **功能**：❌ 4 项该做未做——`@` 提及缺 **reference / MCP resource** 两类来源、**新建会话无 MCP 草稿开关**、**会话 summary 的 MCP 子菜单无连接开关**、**行评论不带 `metadata.comments`**（时间线因此无 comment card）；其余（slash 别名、fork/move/undo/redo/export、auto-approve、form 全字段、btw、websearch）抽查一致。
- **界面**：⚠️ 以"降级/精简"为主——markdown 图片只出 alt 文本、会话 tab 无 unread/loading 指示、Home 项目行缺"导入会话/清除通知"、会话 404 直接返回而非"未找到"面板、服务子菜单缺配置页脚、新建会话页缺 summary popover/git 状态/tips；无新增结构性缺面板。
- **接入**：✅ 与上游语义一致（Basic 鉴权、`/api/event` 全局流 + `/experimental/session/{id}/log?after=&follow=` 续播、1s..30s 指数退避、404 熔断、`location.shutdown` 恢复、auto-approve sweep、pty 一次性 ticket）；⚠️ 唯一实差：**`mcp.*` 三个事件被显式丢弃**（`V2EventAdapter.kt:537-539`），MCP 列表状态不实时。

---

## ❌ 缺失（上游有、壳该有、我们无）

### F-1 `@` 提及菜单缺 reference 与 MCP resource 两类来源
- 上游：`_upstream/packages/app/src/composer/model.ts:148-176`（`references()` ← `GET /api/reference`，过滤 `hidden`；`resources()` ← `GET /api/mcp/resource`），汇总顺序见 `:195-...`（`context` = references → skills → agents → resources → recent → files）。
- 我们：`app/src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/ComposerSuggestions.kt:229-289`（`mentionItems` 只有 agents / commands / skills / files）；调用点 `ui/session/composer/Composer.kt:302-314` 也只传这四类。两条 API **零 UI 调用方**：`v2/V2Api.kt:1466`（`references()`）、`v2/V2ApiMcp.kt:95`（`mcpResourceCatalog()`，仅在 Settings→Extensions 模态列出，`ui/settings/SettingsPanels.kt:1118`）。
- 影响：用户无法 `@` 引用 reference（如上游 `@docs`）或 MCP resource；模型也收不到对应 `files` 条目。
- 修复：把两类并入 `mentionItems` 的 `fixed` 段（reference 走 `GET /reference` + `hidden` 过滤；resource 走 `mcpResourceCatalog()` 的 `resources[]`，`uri` 作 `path`），行内图标沿用现有 SKILL/AGENT 分类。**M**

### F-2 新建会话页缺 MCP 草稿开关（创建前选启用哪些 MCP server）
- 上游：`_upstream/packages/app/src/new-session/mcp.ts:11-60`（`createDraftMcpControls`：草稿态 `states` + 创建后 `apply()` 落地）+ `_upstream/packages/app/src/new-session/summary.tsx:58`（`SessionServerPanel … mcp={props.mcp.controls}`）。
- 我们：`app/src/main/java/com/xiufeigo/opencodeshell/ui/home/NewSessionScreen.kt:197-445` 全文件无 MCP 引用（`grep -nE "Mcp|mcp" ui/home/*.kt` 无命中）。MCP 只能在会话内 `ui/overlay/McpChooserDialog.kt`（`ui/session/SessionScreen.kt:1904`）或 Settings→Extensions 改，创建会话那一刻无法预选。
- 修复：新建页加一段 MCP 开关列表（复用 `SettingsServerData.mcpServers()` + `mcpConnect/mcpDisconnect`），创建成功后按草稿态落地。**M**

### F-3 会话 summary 面板的 MCP 子菜单缺连接/断开开关
- 上游：`_upstream/packages/app/src/session/summary/server-panel.tsx:157-270`（`McpMenu`：`Switch` 在 `:237-265`，`toggle.mutate({name, enabled})` 在 `:234`；状态点 `session-service-dot`）。
- 我们：`app/src/main/java/com/xiufeigo/opencodeshell/ui/session/summary/SessionSummaryPanel.kt:473-560`（`ServiceSubmenusOf` 只渲染 `item.name` + `mcpStatusLabel(item.status)` 文本，无 Switch）；`mcpConnect`/`mcpDisconnect` 的唯一调用方是 `ui/settings/SettingsV2Delegate.kt:164`。
- 影响：summary 里能看到 MCP 服务器状态却无法就地开关。
- 修复：给 `ServiceSubmenusOf` 增加可选 `toggle`（仅 MCP 子菜单传），行内加 `Switch`，写入后 bump reload。**S/M**

### F-4 发送消息不带结构化行评论（`metadata.comments`）→ 时间线无 comment card
- 上游：`_upstream/packages/app/src/composer/submit.ts:169-177`（`metadata.comments:[{path, comment, selection, preview, origin}]`）+ `_upstream/packages/session-ui/src/message/comment-card.tsx:6-60` 渲染卡片；`_upstream/packages/app/src/composer/request.ts:88-133` 同时把评论展开为文本 note（**两条路都走**）。
- 我们：`app/src/main/java/com/xiufeigo/opencodeshell/ui/session/review/ReviewComments.kt:33-34,126-156`（只把 `ReviewCommentPrompt.formatNote` 文本 `appendComposerText` 进草稿），`ChatViewModel.kt:1740-1770` 的 prompt 体不带 `metadata`；`ui/session/timeline/` 无 comment card 渲染（全目录 `comment` 只命中 `ToolPresentation.kt:383` 的语法高亮 `--`/`---` 说明，与行评论无关）。
- 影响：发出的消息里评论降级成正文文字，无法卡片化、无法按行锚定回跳。
- 修复：`prompt()` 已支持 `metadata`（`v2/V2Api.kt:635`），补 `metadata.comments` 序列化 + 时间线 comment card。**M**

---

## ⚠️ 差异（语义/降级/参数）

### A-1 markdown 图片不渲染（只出 alt 文本）
- 上游：`_upstream/packages/session-ui/src/components/markdown-image.ts:31-60`（`createMarkdownImages`：解析本地路径 → `fs.read` → blob URL 落 `<img>`）+ `image-preview.tsx:1-30`（点击放大对话框）。
- 我们：`app/src/main/java/com/xiufeigo/opencodeshell/ui/markdown/MarkdownParser.kt:488-500`，注释原文 "renders as its alt text (the timeline has no inline image slot)"。
- 修复：`![alt](rel/path)` → `GET /api/fs/read/<rel>` 取字节 → Compose `Image` + 点击进现有图片预览（`AttachmentPreviewRow.kt` 已有解码/降采样工具）。**M**

### A-2 会话 tab 无 unread / loading 指示
- 上游：`_upstream/packages/app/src/shell/layout/session-tab-avatar.tsx:10-49`（`SessionTabAvatarView`：`loading` → 进度指示，否则 avatar 带 `unread`）+ `_upstream/packages/app/src/shell/layout/project-avatar-state.ts:26-49`（`unread = 有 permission/form 未决 || notification.session.unseenCount>0`）。
- 我们：`app/src/main/java/com/xiufeigo/opencodeshell/ui/nav/BottomTabStrip.kt:183-232`（`SessionTab` 只有标题 + 关闭）；`ui/session/drawer/SessionDrawer.kt:233-235` 只有 running 进度点；unread 仅作为 `AppNotifier` 的内部 `viewed` 标记（`notify/AppNotifier.kt:34-48`），无任何 tab/行显示。
- 修复：给 tab 与 drawer 行加一个"待处理"点（permission/form 未决或未读通知）。**S/M**

### A-3 Home 项目行菜单缺"导入会话"与"清除通知"
- 上游：`_upstream/packages/app/src/home/projects/view.tsx:737-758`（`canImportSession` → `command.session.import`；`sidebar.project.clearNotifications`，`unseen==0` 时 disabled）。
- 我们：`app/src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeProjectsList.kt:274-286`（只有 Edit / Refresh worktrees / Close）；`clearNotifications` 只有全局 `ChatViewModel.kt:324`（`appNotifier.clear()`），无 per-project。
- 修复：菜单补两行；`session.import` 的落地逻辑 palette 已有（`ui/palette/SessionCommands.kt:171-174`），接同一入口即可。**S**

### A-4 会话 404 直接返回，无"会话未找到"面板
- 上游：`_upstream/packages/app/src/session/route-error.tsx:31-61`（`session.error.notFound` + 描述 + server/sessionID + Close tab 按钮）。
- 我们：`app/src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt:542-545`（`sessionMissing` → `onBack()`，无面板、无 id 展示）。
- 影响：一次瞬时 404 会把用户直接弹回 Home，且不给会话 id 供排查。
- 修复：加一个与 `SessionErrorCard`（`ui/session/timeline/SessionErrorCard.kt`）同形的整屏态。**S**

### A-5 MCP 状态事件被显式丢弃 → MCP 列表状态不实时
- 上游：`_upstream/packages/app/src/session/summary/server-panel.tsx:157-270` 由 `mcp.status.changed` 驱动的 data store 刷新 Switch 状态。
- 我们：`app/src/main/java/com/xiufeigo/opencodeshell/v2/V2EventAdapter.kt:537-539` 对 `mcp.status.changed` / `mcp.tools.changed` / `mcp.resources.changed` 返回 `emptyList()`（注释 "no MCP surface in this app" 已过时——chooser/settings 都已存在）；`ui/settings/SettingsPanels.kt:1036-1120`、`ui/overlay/McpChooserDialog.kt:64-75` 只在打开/写入后手动 `reload++`。
- 影响：`pending → connected/failed` 的服务端状态迁移（或另一客户端改动）不会反映到 UI。
- 修复：加 `ChatStore.Signal.MCP_*` 并接到一个 revision。**S**

### A-6 服务子菜单缺"配置 / 复制配置路径"页脚
- 上游：`_upstream/packages/app/src/session/summary/server-panel.tsx:270,366,440-480`（`ServiceConfigLink`：`GET /api/config` 找 `info[service]` 所在文档路径 → 本地 reveal / 远程复制路径）。
- 我们：`ui/session/summary/SessionSummaryPanel.kt:473-560` 无页脚；`GET /api/config` 只用于 LSP 名单（`SessionSummaryModel.kt:133-165`）。
- 修复：移动端只做"复制配置路径"。**S**

### A-7 prompt 的 files/agents/skills 缺 `mention{start,end,text}`
- 上游：`_upstream/packages/schema/src/prompt-input.ts:11-16`（`FileAttachment{uri,name?,description?,mention?}`）+ `_upstream/packages/app/src/composer/request.ts:68-82`（每个 file/agent/skill 都带 `mention`）；`_upstream/packages/core/src/session/runner/to-llm-message.ts:87-95` 用 `file.mention.text` 做内联图片去重。
- 我们：`app/src/main/java/com/xiufeigo/opencodeshell/v2/V2Api.kt:2426-2433`（`PromptFile{uri,name,description}`，无 mention）+ `ChatViewModel.kt:1822-1837`（`promptFiles` 不带 mention）。
- 影响：同一图片被多次 `@` 时会重复送进 LLM（去重键缺失）；mentions 无法被服务端按 span 识别。低危。
- 修复：`PromptFile` 加可选 `mention` 并在 `MentionTokens` 侧回填 span。**S**

### A-8 `GET /api/permission/saved` 缺 `projectID` 过滤
- 上游：`_upstream/packages/protocol/src/groups/permission.ts:37-40`（query `{projectID?: Project.ID}`）。
- 我们：`app/src/main/java/com/xiufeigo/opencodeshell/v2/V2Api.kt:1004-1005`（`savedPermissions()` 无参）。
- 修复：加可选 `projectID`。**S**

### A-9 `POST /api/session/{id}/permission` 缺 metadata / source / agent
- 上游：`_upstream/packages/protocol/src/groups/permission.ts:63-85`（payload `{id?, action, resources, save, metadata, source, agent?}`）。
- 我们：`app/src/main/java/com/xiufeigo/opencodeshell/v2/V2Api.kt:964-980`（只 id/action/resources/save）。
- 修复：补三个可选参数（调用方暂传 null）。**S**

### A-10 `GET /api/session/{id}/form/{formID}` 读成 Info 而非 Detail（丢 `state`）
- 上游：`_upstream/packages/protocol/src/groups/session.ts:824-837`（`Form.Detail` = `Info` + `state{pending | answered(+answer) | cancelled}`，`_upstream/packages/schema/src/form.ts:150-160`）。
- 我们：`app/src/main/java/com/xiufeigo/opencodeshell/v2/V2Api.kt:1059-1061`（`form()` → `FormInfo`；`FormInfo.fromJson` 只取 `id/sessionID/title/fields`，`V2Api.kt:1082-1093`）。
- 影响：读单个表单时无法判断已答/已取消（当前 UI 主要走 list 路由，风险低）。
- 修复：`form()` 返回带 `state` 的 DTO。**S**

### A-11 新建会话页缺 summary popover / git 分支状态 / onboarding tips
- 上游：`_upstream/packages/app/src/new-session/view.tsx:118`（草稿期 Composer）+ `:122-160`（project/workspace/git status）+ `_upstream/packages/app/src/new-session/summary.tsx:12-64`（ProjectSummaryCard + workspace selector + server panel）+ `view.tsx:167-200`（provider/workspace tips）。
- 我们：`app/src/main/java/com/xiufeigo/opencodeshell/ui/home/NewSessionScreen.kt:197-445`（选目录 + Create 按钮；`WorkspaceSelector` 在 `:444-600`，但无 summary / git 状态 / tips / 草稿 composer）。
- 影响：创建前看不到项目卡与分支状态，也无 tips；首条 prompt 只能在创建后的会话里输入（功能可绕，体验差一截）。
- 修复：M（tips 可单独 S）

### A-12 Pairing 缺 App 内摄像头扫码
- 上游：`_upstream/packages/app/src/servers/connect/scanner.tsx:1-40`（`preferredCamera: "environment"`，应用内取景框）。
- 我们：`app/src/main/java/com/xiufeigo/opencodeshell/ui/settings/PairingPanel.kt:67` 与 `:174` 两处 `TODO(pairing/scan)`；现走系统扫码 App（`ui/servers/QrScan.scanIntent()`，缺失则提示）或相册选图解码。
- 修复：**M**（需相机库；生成/复制/手工输入三路已闭环）

### A-13 文件查看器不渲染 audio（保留二进制占位）
- 上游：`_upstream/packages/session-ui/src/pierre/media.ts:3-40`（`MediaKind = image | audio | svg`，含 `mp3/wav/ogg/m4a/aac/flac/opus`）+ `_upstream/packages/session-ui/src/components/file-media.tsx:1-40`。
- 我们：`app/src/main/java/com/xiufeigo/opencodeshell/ui/session/review/ArtifactView.kt:83`（注释：video/audio/pdf/font 保留 `ArtifactBinary` 占位；image 已实现，`:352-393`）。
- 修复：**S**（移动端价值低，可后置）

---

## ✅ 抽查一致（证据抽样）

- **API 全覆盖**：上游 136 个 `HttpApiEndpoint` 归一化 111 条路径 × 我方 `builder(...)`/`postUnit(...)`/`addPathSegment(...)` 反查 **111/111 命中**（0 缺）。逐条确认了几个易漏点：`POST /api/experimental/generate`（`v2/V2ApiExperimentalMisc.kt:60`）、`POST /api/location/reload`（`v2/V2ApiWebsearchVcs.kt:94`）、`POST /api/experimental/session/{id}/wait`（`V2Api.kt:704`）、`POST …/skill`（`V2ApiSessionInstructions.kt:103`）、`instructions/entries/:key` PUT/DELETE（`V2ApiSessionInstructions.kt`）、`experimental/mcp/:server[/connect|/disconnect]`（`V2ApiMcp.kt:50,74,84`，`addPathSegment` 防注入）、`pty/:ptyID/connect` 与 `persistent-pty/:ptyID/connect` 全参 `ticket/cursor/role/attachment_id/takeover/input_protocol`（`V2ApiPersistentPty.kt:99-111`）、`vcs.diff?mode&base&context`（`V2Api.kt:1210-1222`）、`vcs.branch?search&limit`（`:1193-1200`）、`session.diff?from&to&context`（`:1119-1130`）、`session.export?sanitize`（`V2ApiSessionTransfer.kt:49-51`）、`session.import{info,messages,location}`（`:70-78`）、`worktree create/remove/refresh` body（`V2ApiMissingVerbs.kt:151-185`、`V2ApiCatalogExtras.kt:101-130`）、`shell.output?cursor&limit`（`V2ApiPtyShell.kt:148-161`）、`mcp.add{config}`（`V2ApiMcp.kt:47-48`）、`integration` 全 11 条含 `oauth.complete`（`v2/V2ApiIntegrationOauth.kt`）。文档站 126 个 Operation ID 与快照同一集合。
- **事件**：`_upstream/packages/schema/src` 共 105 个事件类型；我方映射除桌面/TUI 专属（`tui.command.execute` / `tui.prompt.append` / `tui.session.select` / `tui.toast.show` / `ide.installed` / `installation.update-available` / `installation.updated` / `global.disposed` / `models-dev.refreshed` / `workspace.ready|failed|status`，上游前端本身也无消费者）外**全覆盖**。重点抽查：`session.status{idle|busy|retry(+action)}`（`v2/V2EventAdapter.kt:97-113` + `ChatStore.kt:200-330` 的 `RetryInfo`/`UsageUpsell`）、`session.execution.*`、`session.step.*`、`session.inbox.*`、`session.shell.started|ended`、`session.instructions.updated`、`session.permissions`、`filesystem.changed`→`FILE_EDITED`（`ChatViewModel.kt:4202`）、`vcs.branch.updated`、`worktree.*`、`credential/integration/plugin/skill/command/lsp/reference/websearch.updated` → `catalogRefresh()`（`ChatViewModel.kt:4175-4250`）。
- **功能**：slash 别名 9 条与上游 `slash` 字段一一对应（我方 `ui/palette/SessionCommands.kt:368-376` ↔ `_upstream/packages/app/src/session/commands/use-session-commands.tsx:253,260,268,276,291,299,319,358,436`），`/model` `/agent` `/btw` 在 composer 内（`ComposerSuggestions.kt:83-111,146-228`）；fork 选择器（`ui/App.kt:1629-1660`）、move（`ui/session/actions/SessionActionsDialogs.kt:48`）、auto-approve sweep（`AutoApprover.kt` + `ChatViewModel.kt:3945-3998`）、form 全字段 + `when`/`hidden`（`ui/session/dock/SessionFormModel.kt:158-200`）、summary 服务卡四子菜单 + configured-LSP（`SessionSummaryModel.kt:133-165`）、stats 卡、websearch 运行/同意两 dock。
- **界面**：review 面板含 `diffStyle` unified/split（`ReviewChangesTab.kt:184,573-610`）、文件过滤（`ReviewModels.kt:259-333`）、行评论增删改（`ReviewComments.kt`）；settings 13 tab 且 row 表与上游 `pages.ts` 对齐（`SettingsModel.kt:44-57`）；时间线搜索（`TimelineSearchBar.kt` 含 `active/count` + prev/next）；文件树/上下文 tab（`SessionContextPanel.kt`，`MobileUsagePanel.kt:34-42` 复用）；终端 pty/持久 pty/后台 shell 多 tab。
- **接入**：Basic（`V2Api.kt:60-120`，与 `_upstream/packages/server/src/auth.ts` 一致）、pty 一次性 ticket + 强制 `x-opencode-ticket: 1`（`V2Api.kt:1639-1660`）、双流 + 1s..30s 指数退避（`EventStream.kt:224-225`）、4xx 永久失败不重试（`EventStream.kt:106-107`）、404 熔断（`ChatViewModel.kt` `markSessionMissing`）、`location.shutdown` 恢复（`ChatViewModel.kt:4246`）、`?after=<maxSeq>` 续播 + `DEDUP_WINDOW`（`v2/V2SeqTracker.kt`）。

## 不该在 Android 壳做的（verdict）

- TUI 专属：`tui.*` 四事件、vim 键位、鼠标事件、marquee 动效 —— **不做**（触屏无对应物）。
- Desktop 专属：应用内更新检查（`shell/updates`、`installation.*`）、`revealPath`/文件管理器打开、WSL 面板、pinch-zoom、多窗口 —— **不做**（`ui/settings/ShellPanelUpdates.kt` 现状正确）。
- 多服务器 settings scope（上游 `SettingsServerTab`/`SettingsProjectTab` 层级 + `settings/server-scope.tsx`）：本壳单活动服务器，扁平 13 tab 属**有意简化**，不列缺口。
- Experimental tab 的 `tabs`/`projectName` 行在上游是可用开关、我方渲染为 disabled（`ui/settings/SettingsPanels.kt:707-717`）：属已文档化的 Android 降级（`SettingsModel.kt:32` 注释），不列缺口。

## 修复清单（按建议顺序）

1. **F-1** `@` 提及补 reference + MCP resource（M）
2. **A-2** tab/drawer unread 点（S/M）→ **A-3** Home 菜单两行（S）→ **A-5** MCP 事件接通（S）
3. **F-3** summary MCP 开关（S/M）→ **F-2** 新建页 MCP 草稿开关（M）
4. **F-4** `metadata.comments` + comment card（M）
5. **A-1** markdown 图片（M）→ **A-4** 未找到面板（S）→ **A-6/A-8/A-9/A-10**（4×S，可一批）
6. **A-11** 新建页 summary/git 状态（M）、**A-12** 摄像头扫码（M）→ **A-7/A-13**（2×S）
