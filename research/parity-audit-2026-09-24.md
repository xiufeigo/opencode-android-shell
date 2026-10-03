# V2 Parity Audit — 2026-09-24（只读对比审计）

> 基线：`_upstream/` = `anomalyco/opencode@v2` 快照；后端活体 `2.0.11`（`GET /api/info` 实测）；文档 [OpenCode API](https://opencode.ai/v2/docs/api)（实测提取 126 个 Operation ID，截断项归一后与路由表一致）。
> 上游路由表：`_upstream/packages/protocol/src/groups/*.ts` 共 **136 个 `HttpApiEndpoint` 定义**；此前宣称的“113/113 + 5 次级动词”口径小于快照实际数量，但结论不受影响——**按操作逐项核对后，我方 `v2/V2Api*.kt` 对全部操作均有对应方法**（含 `integration.oauth.complete`，见 EVP-0）。
> 约束：本次只读，未改任何代码；`Read.line_offset` 不可用处均改用 `sed -n`。

## 三行摘要

- **功能**：❌ 缺 3 项该做的（composer 会话级 `/` 别名、review 行评论、内嵌 browser 页），另 `session.move` 有 API 无入口；其余（revert/fork/权限/表单/MCP/智能体切换）一致。
- **界面**：⚠️ 以有意删减为主（主题选择器、UI/Code 字体、mermaid 降级均已文档化）， notably 时间线缺虚拟化（大会话性能风险 M）。
- **接入**：✅ 与上游语义一致（Basic 鉴权、全局流 volatile + 会话 log `?after=` 续播、cursor/order 互斥、`location.shutdown` 恢复）；⚠️ 仅单会话流并发（移动端可接受，需显式确认为设计）。

---

## ❌ 缺失（上游有、壳该有、我们无）

### F-1 composer `/` 菜单缺会话级 slash 别名
- 上游：`_upstream/packages/app/src/composer/model.ts:231-245`（`slashCommands` = 服务器自定义命令 + **所有**带 `slash` 的注册命令），`session/commands/use-session-commands.tsx:257-340` 注册了 `undo/redo/compact/fork/export/open/terminal/mcp` 等别名；选中后直接执行命令（`editor/interaction.ts:168-171`）。
- 我们：`app/src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/ComposerSuggestions.kt:87-130`（`commandItems` 只收 `/model`、`/agent`、服务器 `GET /command`），`195-210` 明确“未上榜的 slash 行按普通 prompt 发送”。
- 影响：`/undo`、`/compact`、`/fork` 等在手机上变成一句发给模型的普通话，语义错。
- 建议：把 `SessionCommands` 中带 slash 语义的项（至少 undo/redo/compact/fork/export/open/terminal/mcp）并入 `commandItems`，选中走现有 palette 执行路径。**M**

### F-2 review 页无行评论（line comment）能力
- 上游：`_upstream/packages/app/src/session/review/pane
l.tsx:49-53,94,172`（`onLineComment/onLineCommentUpdate/onLineCommentDelete` + `comments`），`session-ui` 有 `line-comment.tsx`，`composer/comments.tsx` 把行评论聚合成 prompt 上下文。
- 我们：`ui/session/review/` 全目录无 comment 概念（仅 `ArtifactView.kt:556` 的语法高亮 `Kind.COMMENT` 同名误中）。
- 建议：先做只读展示 + “加入提问”（复用 composer 上下文附件），后做增删改。**M**（若只做“选中代码加入 composer”，可拆为 **S** 先行）

### F-3 无内嵌 browser 页（agent 浏览器工具台面）
- 上游：`_upstream/packages/app/src/session/browser/pane.tsx` + `model.ts`（地址栏、加载态、dialog 联动，为 agent 的 browser 动作提供可视台面）。
- 我们：`ui/session/` 下的 `browser` 命中均为文件浏览/`OpenInApp`，无 web 台面。
- 建议：Android 上不必内嵌渲染，用 Custom Tabs / 外部 intent 接 URL（`ToolRenderers` 里的 browser 工具输出挂入口）。该做，但优先级低于 F-1/F-2。**M**

### F-4 `session.move` 有 API、无 UI 入口
- 上游：`session-workspace-menu.tsx`（header 内的换目录菜单，`directory + delivery`）。
- 我们：`ChatViewModel.kt:3303`（`moveSessionTo`，经 `transport.moveSession` 调通 `POST /api/session/{id}/move`）**零调用方**；`SessionActionsModel.menu`（`ui/session/actions/SessionActionsModel.kt:173` 起）无 move 行；仅 `session.location.cycle` 命令（`SessionCommands.kt:271`）做浏览切换。
- 建议：在 ⋮ 会话动作菜单加 “移动到…” 行，复用目录选择器 + delivery（steer/queue）复用 `ComposerDelivery`。**S**

---

## ⚠️ 差异（语义/参数/有意偏离）

### A-1 `PATCH /api/session/{id}` 只发 `title`，缺 `permissions`
- 上游：`protocol/src/groups/session.ts:2007-2020`（payload `{title?, permissions?: Permission.Ruleset}`）。
- 我们：`v2/V2Api.kt:783-788`（`renameSession` 只拼 `title`）。
- 建议：`renameSession` 加可选 `permissions: JSONObject?` 参数（默认 null 保持现状）。**S**

### A-2 `POST /api/session` 缺 `metadata`、`permissions`
- 上游：`protocol` session.create payload `{id?, title?, agent?, model?, location?, metadata?, permissions?}`（`/tmp` 证据见审计过程 `1869-1888`）。
- 我们：`v2/V2Api.kt:328-345`（`createSession` 只有 id/title/agent/model/location）。
- 建议：补两个可选参数；调用方（新建会话）暂传 null。**S**

### A-3 `POST /api/session/{id}/command` 缺 `agents`、`skills`
- 上游：payload = `{name, ...PromptInput.Prompt(text/files/agents/skills), delivery?}`（`groups/session.ts:2062-2080`；`Prompt` 含 agents/skills，见 `core/src/session/prompt.ts:20-27`）。
- 我们：`v2/V2Api.kt:746-770`（`command()` 只发 name/text/files/delivery）。`prompt()`（`V2Api.kt:498`）倒是有 agents/skills，属单边遗漏。
- 建议：与 `prompt()` 同形补参。**S**

### A-4 `GET /api/session` 缺 `parentID` 过滤、多发 `workspace` 参数
- 上游 `SessionsQuery`：`limit/order/search/parentID + {directory} | {project, subpath?} | {}`，cursor 为 base64url JSON（含 anchor，不含 limit），`cursor+order` 同传 400（`groups/session.ts:50-171`，`handlers/message.ts:36` 同理）。
- 我们：`v2/V2Api.kt:289-322`（`sessions()` 无 `parentID`；多发上游不存在的 `?workspace=`，服务端大概率忽略——未实测，建议抓包确认；`directoryScoped` 开关本身合理）。
- cursor/order 互斥我方已正确处理（`messagesRaw`，`V2Api.kt:443` 起有实测记录）。建议：加 `parentID` 参数、去 `workspace` 查询键（location 体保留）。**S**

### U-1 外观：主题选择器 + UI/Code 字体被隐藏（有意，但与文档差半步）
- 上游：`settings/appearance/appearance.tsx:93-120`（theme picker，外链主题文档）+ 三种字体（ui/code/terminal）行。
- 我们：`SettingsPanels.kt:84-98` 注释明示隐藏 theme picker 与 UI/code 字体，只留 scheme + terminal 字体；`SettingsStore` 仍保留 `themeId/uiFont/codeFont` 键（`SettingsPanels.kt:264-267` 映射），属“存而不用”。
- 建议：维持隐藏（移动端合理），但把“主题跟随上游自定义主题”列入 backlog；若要做，`OcTheme` 接上游 theme 包是 **L**，只做浅/深/跟随系统则是 ✅ 已有。**S（文档化）/ L（全量主题）**

### U-2 mermaid 降级为代码块（有意，已文档化）
- 我们：`ui/markdown/MarkdownRenderer.kt:293-295,333-334`（无图表渲染器，保留 fence + `mermaid` 标签）。
- 建议：保持（Android 原生渲染 mermaid 成本高）；最多加“复制源码”行。**S（如做）**

### U-3 草稿只存文本，上游 handoff 带文件选择 + 消息
- 上游：`session/handoff.ts`（`setSessionHandoff` 存 `{files}` + `setSessionMessageHandoff` 存 user 消息，新会话可继承）。
- 我们：`ComposerDrafts.kt:25-90`（按 session key 存 `Draft(text)`，无 files/message 跨会话继承）。
- 建议：handoff 的 files 部分并入发起新会话时的上下文附件。**S**

### U-4 时间线无虚拟化（大 transcript 性能风险）
- 上游：`session/timeline/virtualizer.tsx` + `virtual-items.ts` + `cache.ts`。
- 我们：`MessageTimeline.kt` + `TimelineRows.kt`（`LazyColumn`，无窗口化/缓存层）。
- 现状可用，属风险项：百消息级已有分组，千级 tool 输出可能掉帧。建议：先加“工具输出折叠阈值 + 分页回看”（已有 `ToolCardPolicy`/`collapse-tool-output` 思想可抄），再考虑虚拟化。**M**

### P-1（结转）`usage-exceeded` 24h 去重持久化收尾中
- 背景已知结论：弹窗本身已实现（`ui/overlay/UsageExceededDialog.kt`，对照 `session/usage-exceeded-dialogs.tsx`）；“24h 去重持久化可能仍在收尾”——本次只读审计确认文案/触发位存在，不重复排查，去重存储键的落地状态请并行 agent 确认。**S**

### P-2（有意偏离，重申）模型 picker 全量列出
- 背景已知：上游 connected-only，我方全量 + 未连接分组（`SettingsModelsUnconnectedTest`、`ModelPickerTest` 锁定）。审计确认属产品决策，非缺口。✅ 无需改。

### C-1 并发模型：单会话流（移动端可接受，需确认为设计）
- 我们：`ChatViewModel.kt:906-960`（`openV2Streams` = 1 条全局 `GET /api/event` + 1 条当前会话 `GET /experimental/session/{id}/log?after=&follow=true`；切会话先 `close()` 再重开；404 熔断 `markSessionMissing`）。
- 上游桌面：多开会话各有订阅；`EventFeed` 明确“慢消费者溢出即断流、断线期事件丢失”（`server/src/event-feed.ts` + `protocol/groups/event.ts:243-260`，description 原文 volatile by contract）。
- 恢复语义我方已对齐：`?after=<maxSeq>` 续播（`V2SeqTracker.kt`：`log.synced` 水位 + `DEDUP_WINDOW` 去重）+ `ESTABLISHED` 即 `refreshSnapshot()` + `location.shutdown` 处理（`ChatStore.kt:460`）+ `session.view` idle 确认（`V2Transport.kt:175`）。
- 建议：把“后台会话只靠快照刷新、不保活其 log 流”写进设计文档一句话；若产品要后台实时（如后台任务提示），再为后台会话加轻量轮询。**S（文档）**

---

## ✅ 一致（抽查过，证据抽样）

- **API 全覆盖**：文档 126 操作 × 路由表 136 定义，我方 `V2Api*.kt` 逐项有方法：`agent/command/skill/reference/model/provider`（`V2Api.kt:1223-1324`）、`permission/saved/request + session permission/form`（`:803-951` 含 `formCancel` 在 `V2ApiMissingVerbs.kt:199`）、`revert stage/clear/commit`（`:704-732`）、`fork/compact/interrupt/background/generate/move/synthetic/view`（`:547-702`）、`inbox list/update/cancel`（`:992-1019`）、`diff from/to/context`（`:970-990`，与上游 `from/to/context` 一致）、`context`、单消息读（v2 新增，`:467`）、`stats from/to/project/timezone/tools`（`V2ApiSessionTransfer.kt:99`）、`export/import`（同文件 `:49,70`）、`instructions entries + skill`（`V2ApiSessionInstructions.kt:60-100`）、`session terminal list/create/read`（`V2ApiSessionTerminal.kt:36-89`）、`pty 全套 + connect-token + socket`（`V2Api.kt:1389-1548`、`V2ApiPtyShell.kt:66`）、`persistentPty 全套 + handoff/shutdown/snapshot`（`V2ApiPersistentPty.kt` + `MissingVerbs.kt:58-86`）、`shell create/get/output/remove`（`MissingVerbs.kt:108` + `V2ApiPtyShell.kt:134-161`，output 的 `cursor/limit` 与上游“绝对字节游标”一致）、`fs list/read/find/write`（`:1324-1388` + `V2ApiFsWrite.kt`）、`config get/shells/update`（`configUpdate` 经核对 `Config.Patch={shell}` 正确）、`credential update/activate/remove`（`V2ApiCredentialPlugin.kt`）、`plugin check/update/list`、`rpc.call`、`debug location list/evict`、`migration.v1.status`、`generate.text`（base 配置标题生成）、`websearch providers/query`（`V2ApiWebsearchVcs.kt:41`）、`vcs get/base/status/branch/diff`（`vcsBase` 在 WebsearchVcs 文件）、`mcp list/add/remove/connect/disconnect/resource-catalog`（`V2ApiMcp.kt`）、`integration list/get/key/oauth(connect/status/complete/cancel)/command(connect/status/cancel)/wellknown-add`（`V2ApiIntegration*.kt`；`…/complete` 在快照 `groups/integration.ts:121-134` + `handlers/integration.ts:132-141` 均有，旧审计若称“无此路由”应以快照为准）、`project list/update`、`worktree list/create/remove/refresh`（`V2Api.kt:1146` + `MissingVerbs.kt:151-180` + `CatalogExtras.kt:101`）、`server.info`、`location get/reload`、`event.subscribe`、`session.log?after=&follow=`。
- **v1 已死、v2 无 share**：快照 `groups/session.ts` 无 `share` 路由；`SessionActionsModel.kt:40-41` 把 share/archive 标为 v1 404 并禁用——正确，不做。
- **界面**：home 项目/会话（`ui/home/`）、NewSession（含 `WorkspaceMenuRow`）、会话 header（含 context ring、SSH 隧道行、timeline 搜索）、composer（shell `!` 模式、队列、steer/queue、mention、附件+dropzone+全 MIME、历史、草稿）、命令面板（app/会话/builtin 三源）、设置（11+2 tab：General/Appearance/Notifications/Shortcuts/Projects/Workspaces/Providers/Models/Extensions/Servers/Shell/Experimental/About + 搜索）、终端（pty/持久 pty/后台 shell/多 tab）、文件+review（含 file tabs/tree/context tab/open-in-app/可排序 tab）、permission/form/websearch 三 dock、summary（含 stats）、pairing/QR/SSH。
- **接入**：Basic（`opencode` 用户，`V2Api.kt:60-120`，与 `server/src/auth.ts` 一致；pty ticket 走一次性票据）、双流 + 退避/心跳（`ChatViewModel:899` 起经 `EventStream`）、404 熔断、鉴权墙/可重试二分（`V2Capability.ProbeResult`）。

## 不该在 Android 壳做的（ verdict ）

- TUI 专属：vim 键位、鼠标事件、终端按键转义（`tui/terminal-key-event`）、marquee/scrollbar 动效——**不做**（触屏无对应物；快捷键面板保留桌面键位展示即可）。
- Desktop 专属：WSL 面板（`desktop/wsl`）、titlebar 原生菜单、应用内更新检查（`shell/updates`）、多窗口——**不做**（our `ServersPanel`/`AboutPanel` 现状正确）。
- `message-gesture.ts` 实为桌面滚轮边界判定——**不做**；移动端 swipe-dismiss 已有（`SessionBottomSheet.kt`）。

## 修复清单（按建议顺序）

1. F-1 composer slash 别名（M）→ 2. A-1/A-3/A-4 参数补齐（3×S，可一批）→ 3. F-4 move 入口（S）→ 4. F-2 行评论 S 先行 → 5. U-4 折叠阈值 → 6. C-1 一句话设计文档（S）→ 7. F-3 browser（M，排后）。
