# API / SSE 事件对齐审计（只读）

> 审计对象：Android 原生客户端 `C:\sensorsdata\main\program\opencode-android-shell`
> 上游参照：`_upstream`（分支 `v2`）
> 审计时间基线：本文件写入时的工作区状态；**未修改任何既有代码**，仅新增本报告。
>
> **证据分级（全文通用，每条结论都标注）**
> - **【U】**上游 clone 内可直接定位的 `file:line`（最硬证据）
> - **【L】**本地调研文档 `research/04_data_api.md`（**非上游**，指向的是另一个 revision / 另一个仓库 `anomalyco/opencode@dev`，无法在本 clone 内复核）
> - **【A】**Android 代码 `file:line`
> - **未确认**：以上三者都找不到

---

## 0. 先决结论：本 clone 的可用证据边界（**必须先读，否则会误读两张表**）

任务描述假设 `_upstream/packages/schema/src/v1/` 里有 `session.ts`、`event.ts`、`permission.ts`、`question.ts`、`file.ts`、`project.ts`、`config.ts`、`provider.ts`、`vcs.ts`、`tui.ts`。**实际 ls 结果不是这样。**

`_upstream/packages/schema/src/v1/` 全集只有 **5 个文件**（逐个已读）：

| 文件 | 大小 | 内容 |
|---|---|---|
| `v1/session.ts` | 20841 B | Part/Message/SessionInfo + 10 个 v1 会话事件 |
| `v1/permission.ts` | 2691 B | `PermissionV1.Request/Reply` + 2 个事件 |
| `v1/question.ts` | 2888 B | `QuestionV1.Request/Reply` + 3 个事件 |
| `v1/filesystem.ts` | 297 B | `file.edited` 1 个事件 |
| `v1/legacy-event.ts` | 503 B | `command.executed` 1 个事件 |

**【U】更关键：这 5 个文件里没有任何 HTTP 端点（方法 + 路径）定义。** 它们是 pure schema（`Schema.Struct` + `Event.durable/ephemeral`），只有事件 `type` 字面量，没有路由。

`_upstream` 的 sparse-checkout 清单（`git sparse-checkout list`）为：
```
packages/app
packages/schema/src/v1
packages/session-ui
packages/theme
packages/ui
```
实测以下路径 **全部不存在**（`Test-Path` 为 `False`）：
`packages/protocol`、`packages/server`、`packages/core`、`packages/cli`、`packages/tui`、`packages/sdk`、`packages/client`、`packages/protocol/openapi.json`。

### 0.1 由此产生的三个硬约束

1. **表 A 的"上游 v1 端点 schema"在本 clone 内不存在。** 因此表 A 每一行的"上游证据"列，凡涉及 v1 路由/参数/响应形状的，一律标 `未确认（本 clone 缺 packages/{protocol,server,core}）`，并给出两个可用的替代证据：
   - **【U】v2 后继契约**：`_upstream/V2_HTTP_API_AUDIT.md`（139→143 条 `/api/*` 端点，源头是缺失的 `packages/protocol/openapi.json`，见 `V2_HTTP_API_AUDIT.md:3`）与 `_upstream/packages/app/e2e/utils/mock-api.ts`（上游前端 e2e 里逐条 `HttpApiEndpoint` 声明，是 clone 内最完整的在册端点清单）。
   - **【U】v1 数据模型**：`_upstream/packages/schema/src/v1/*.ts`（事件与请求/响应体的字段形状，这部分**是**在 clone 内的）。
2. **表 B 的"上游事件类型全集"是充分的**，因为事件定义就在 `v1/*.ts` 里（17 个 v1 事件），另有 `packages/schema/src/event-manifest.ts` 作为权威清单入口（【U】`event-manifest.ts:41-101`）。**但事件的"传输信封"不在 clone 内**——详见 §3.1，这是一个高严重度未决项。
3. **`todo.updated` 与 `message.updated.v2` 在本 clone 的上游 schema 中根本不存在**（见 §3.4），Android 侧对它们的解析分支属于**无上游定义**的分支。

### 0.2 一个必须点明的坐标系事实

上游 v2 分支的**官方前端已经不在用 v1 无前缀面**：`_upstream/packages/app/e2e/utils/mock-api.ts:36-281` 声明的全部是 `/api/...`；`_upstream/packages/app/src/runtime/server/client.tsx:1-2` 导入的是 `@opencode/client/promise` + `@opencode/client/solid`；`_upstream/packages/app/src/runtime/server/api.ts:19-34` 走 `OpenCode.make({baseUrl, headers})`。

而 **Android 客户端整体建立在 v1 无前缀面上**（【A】`OpenCodeClient.kt:604-815`、`EventStream.kt:76-92`）。这不是缺陷本身（v1 面确实与 v2 并存，【L】`research/04_data_api.md:35-36`），但意味着：**本 clone 里没有任何上游文件能为 Android 正在调用的这套路径背书**，所有 v1 路由证据都只能来自【L】。

---

## 表 A：REST 端点覆盖

### A-1 已接入端点（`OpenCodeClient.kt`，22 个）

"上游证据"列：v1 路由形状一律 `未确认`；紧随其后给【U】/【L】可核对的**数据集证据**。

| # | 上游端点（方法 路径） | 上游证据 (file:line) | Android 实现（方法 + 行号） | 状态 |
|---|---|---|---|---|
| 1 | `GET /session` | 路由 `未确认`；列表项模型【U】`v1/session.ts:543-569`；query 面【L】`research/04:209` | `listSessions()` `OpenCodeClient.kt:604-606`（调用点 `ChatViewModel.kt:190`） | **部分**：只发 `?directory=`。未发 `scope` / `path` / `roots` / `start` / `search` / `limit`。Session 只解析 `id`/`title`/`directory`/`time.updated`（`:166-173`） |
| 2 | `GET /config` | 路由 `未确认` | `config()` `OpenCodeClient.kt:609`（调用点 `ChatViewModel.kt:336`） | **1:1**（整包 `JSONObject` 透传，仅消费 `model` 默认值；未解析字段不算丢弃） |
| 3 | `GET /provider` | 路由 `未确认`；响应形状【L】`research/04:274` | `providers()` `OpenCodeClient.kt:615-616` | **部分**：`Provider` 只留 `id`/`name`/`models`（`:380-394`）；`Model` 只留 `id`/`name`/`capabilities{reasoning,attachment,toolcall\|tools}`（`:417-458`）。丢弃 `all[]` 上的 env/api/npm/doc 等、model 的 cost/limit/options 等；`default` 只读 `providerID→modelID` 映射 |
| 4 | `POST /session` | 路由 `未确认`；body 字段【L】`research/04:217` | `createSession()` `OpenCodeClient.kt:618-622`（调用点 `ChatViewModel.kt:587`） | **部分**：body 只发 `title`。未发 `parentID` / `agent` / `model` / `metadata` / `permission` / `workspaceID` |
| 5 | `GET /project` | 路由 `未确认`；`Project` 模型【U】`packages/schema/src/project.ts`（存在，未逐行读） | `projects()` `OpenCodeClient.kt:629-631` | **部分**：只取 `id`/`worktree`/`vcs`/`icon.color`（`:186-206`）；丢弃 `name`/`commands`/`time`/`sandboxes`；无 `worktree` 的条目被整条丢弃（`:194-195`） |
| 6 | `GET /path` | 路由 `未确认`；响应形状【L】`research/04:268` | `serverPath()` `OpenCodeClient.kt:638`（调用点 `ChatViewModel.kt:637-638`） | **1:1**（透传；只读 `directory`） |
| 7 | `GET /file?path=` | 路由 `未确认`；元素形状【L】`research/04:263` | `listFiles()` `OpenCodeClient.kt:645-651` | **1:1**：`name`/`path`/`absolute`/`type`/`ignored` 全覆盖（`:214-235`） |
| 8 | `PATCH /session/:sessionID` | 路由 `未确认`；body 字段【L】`research/04:218` | `renameSession()` `OpenCodeClient.kt:659-662`（调用点 `ChatViewModel.kt:556`） | **部分**：body 只发 `title`。未发 `metadata` / `permission` / `time.archived` |
| 9 | `GET /session/:sessionID` | 路由 `未确认` | `session()` `OpenCodeClient.kt:665-666`（调用点 `ChatViewModel.kt:804`） | **部分**：同 #1，只留 4 个字段。丢弃 `slug`/`projectID`/`workspaceID`/`path`/`parentID`/`summary`/`cost`/`tokens`/`share`/`agent`/`model`/`version`/`permission`/`revert`/`time.created`/`time.compacting`/`time.archived`（`v1/session.ts:543-569`） |
| 10 | `GET /session/:sessionID/message` | 路由 `未确认`；`WithParts`【U】`v1/session.ts:493-500`；query 面【L】`research/04:215` | `messages()` `OpenCodeClient.kt:668-670`（调用点 `ChatViewModel.kt:784`） | **部分**：只发 `?directory=`，**未发 `limit` / `before`**。`Message` 只留 `id`/`sessionID`/`role`/`time.created`/`parts`/`tokens`/`providerID`/`modelID`（`:145-154`）；丢弃 user 的 `agent`/`model`/`system`/`tools`/`format`/`summary`，assistant 的 `cost`/`error`/`finish`/`parentID`/`path`/`mode`/`structured`（`v1/session.ts:332-355, 453-488`） |
| 11 | `POST /session/:sessionID/abort` | 路由 `未确认`；【L】`research/04:221` | `abort()` `OpenCodeClient.kt:672-674`（调用点 `ChatViewModel.kt:432`） | **1:1** |
| 12 | `GET /find/file` | 路由 `未确认`；query 面【L】`research/04:261` | `findFiles()` `OpenCodeClient.kt:680-692`（调用点 `ChatViewModel.kt:516,533`） | **部分**：只发 `query`。未发 `dirs` / `type` / `limit(1..200)`；返回按 `string[]` 解析并兼容 `{path}`（`:685-691`） |
| 13 | `POST /session/:sessionID/prompt_async` | 路由 `未确认`；PromptPayload【L】`research/04:234-255`；parts 输入模型【U】`v1/session.ts:397-451` | `promptAsync()` `OpenCodeClient.kt:703-730`（调用点 `ChatViewModel.kt:415`） | **部分**：`parts[{text},{file:mime,filename,url}]` + `model{providerID,modelID}` 正确；未发 `messageID`（幂等重试/乐观 UI）/ `agent` / `variant` / `noReply` / `tools` / `format` / `system` |
| 14 | `GET /permission` | 路由 `未确认`；元素【U】`v1/permission.ts:27-36` | `listPermissions()` `OpenCodeClient.kt:737-739`（调用点 `ChatViewModel.kt:845`） | **1:1**（v1 字段全覆盖：`id`/`sessionID`/`permission`/`patterns`/`always`/`tool`/`metadata`） |
| 15 | `POST /permission/:requestID/reply` | 路由 `未确认`；`Reply`【U】`v1/permission.ts:38`；`ReplyBody`【U】`v1/permission.ts:41-44` | `replyPermission()` `OpenCodeClient.kt:746-747`（调用点 `ChatViewModel.kt:453,887`） | **部分**：body 只发 `reply`（`once`/`always`/`reject` 全覆盖）；未发 `ReplyBody.message`（可选） |
| 16 | `GET /question` | 路由 `未确认`；元素【U】`v1/question.ts:35-40` | `listQuestions()` `OpenCodeClient.kt:754-756`（调用点 `ChatViewModel.kt:866`） | **1:1**（`id`/`sessionID`/`questions[]`/`tool` 全解析；`Info` 的 `question`/`header`/`options`/`multiple`/`custom` 全覆盖 `:306-334`） |
| 17 | `POST /question/:requestID/reply` | 路由 `未确认`；`Reply`【U】`v1/question.ts:42-46` | `replyQuestion()` `OpenCodeClient.kt:762-770`（调用点 `ChatViewModel.kt:479`） | **1:1**：`{answers: string[][]}` 形状与 `Answer = string[]`（`v1/question.ts:41`）一致 |
| 18 | `POST /question/:requestID/reject` | 路由 `未确认`；【L】`research/04:278` | `rejectQuestion()` `OpenCodeClient.kt:776-777`（调用点 `ChatViewModel.kt:498`） | **1:1**（空 body） |
| 19 | `GET /session/:sessionID/diff` (`messageID?`) | 路由 `未确认`；query 面【L】`research/04:214`；元素【U】`file-diff.ts:16-22` | `sessionDiff()` `OpenCodeClient.kt:784-790`（调用点 `ChatViewModel.kt:688`） | **1:1**：`messageID` 已支持；`FileDiff{file?,patch?,additions,deletions,status?}` 与 `LegacyInfo` 逐字段一致 |
| 20 | `GET /session/:sessionID/todo` | 路由 `未确认`；**元素模型在本 clone 内不存在**（无 `packages/schema/src/session-todo.ts`；全量 `type:"…"` 抽取无任何 todo 事件/类型） | `sessionTodos()` `OpenCodeClient.kt:796-798`（调用点 `ChatViewModel.kt:703`） | **未确认**：Android 按 `{content,status,priority}` 解析（`:537-554`），但上游无对应 schema 可核对。且 `ChatState.todos` 无任何 UI 消费者（【A】`ChatStore.kt:78-82` 注释自述"review panel 不渲染它"） |
| 21 | `GET /file/content?path=` | 路由 `未确认`；响应形状【L】`research/04:264` | `fileContent()` `OpenCodeClient.kt:804-811`（调用点 `ChatViewModel.kt:767`） | **部分**：`type→binary`、`content`、`diff`、`encoding`、`mimeType` 已解析；`patch` 是 `{hunks[]}` 对象，被 `toString()` 压成字符串（`:576-580`）→ **hunk 结构丢失**，无法做逐 hunk 渲染 |
| 22 | `GET /vcs` | 路由 `未确认`；v2 侧变更【U】`V2_HTTP_API_AUDIT.md:231` | `vcsInfo()` `OpenCodeClient.kt:814-815`（调用点 `ChatViewModel.kt:724`） | **部分**：只取 `branch`/`default_branch`（`:588-599`）。【U】`V2_HTTP_API_AUDIT.md:231` 记载 v2 已"保留 branch 嵌套并新增 selected VCS provider ID"，v1 是否也有该字段 `未确认` |

**A-1 统计：22 个端点 → `1:1` 9 个，`部分` 12 个，`未确认` 1 个，`缺失` 0 个。**

### A-1b 另一套 REST 客户端：PTY（`ui/terminal/share/PtyApi.kt`，5 个端点 + 1 个 WS）

任务指定以 `OpenCodeClient.kt` 为准，但终端面是独立实现，一并列出以免漏账。

| # | 上游端点 | 上游证据 | Android 实现 | 状态 |
|---|---|---|---|---|
| 23 | `POST /pty` | 路由 `未确认`；`Pty.Info`【U】`packages/schema/src/pty.ts`（存在，未逐行读） | `PtyApi.create()` `PtyApi.kt:114-127` | **部分**：body 只发 `title`/`command`/`cwd`；未发 `args` / `env`（【L】`research/04:524`） |
| 24 | `POST /pty/:ptyID/connect-token` | `未确认`；header 约定【L】`research/04:526-528` | `PtyApi.connectToken()` `PtyApi.kt:135-151` | **1:1**：`x-opencode-ticket: 1`（`:137,224`）+ `Origin`（`:138`）+ 404/405→`Unsupported` 回退（`:146-148`） |
| 25 | `PUT /pty/:ptyID` | `未确认`；【L】`research/04:525` | `PtyApi.updateSize()` `PtyApi.kt:154-158` | **1:1**（`{size:{rows,cols}}`，不走 WS） |
| 26 | `GET /pty/:ptyID` | `未确认` | `PtyApi.exists()` `PtyApi.kt:161-168` | **1:1**（仅 404 判死；网络错误不判死，`:166-167`） |
| 27 | `DELETE /pty/:ptyID` | `未确认`；v1 返 `true` / v2 返 204【L】`research/04:573` | `PtyApi.remove()` `PtyApi.kt:171-173` | **1:1** |
| 28 | `GET /pty/:ptyID/connect`（WebSocket） | 帧协议【L】`research/04:550-559` | `PtyApi.socketRequest()` `PtyApi.kt:188-205` + `TerminalStream.kt` | **部分**：`directory`/`cursor`/`ticket`/`auth_token`（v1 only）均已构造（`:192-201`）；帧解析（`0x00` 元数据帧）在 `TerminalStream.kt` / `PtyProtocol.kt`，本次未逐行核对 → `未确认` |
| — | `GET /pty/shells` | 【L】`research/04:523` | **无实现** | **缺失**（无法列出可用 shell / `acceptable` 校验） |

### A-2 v1 面**未接入**端点（按域）

"适用性"列针对"前端接入"目标：`前端接入相关` = 官方 App/TUI 会用到、Android 缺失会造成功能或体验缺口；`不适用` = TUI/服务端专用、当前原生 UI 无对应场景。

| # | 方法 路径 | 上游证据 | Android | 状态 | 适用性说明 |
|---|---|---|---|---|---|
| 29 | `GET /session/status` | 路由 `未确认`；status 模型【U】`session-status-event.ts:9-33` | 无 | **缺失** | 前端接入相关：无法一次性获知所有会话忙闲（只能逐个 SSE） |
| 30 | `GET /session/:id/children` | `未确认` | 无 | **缺失** | 前端接入相关：子会话树（官方 `sessionTreeIDs` 依赖它） |
| 31 | `GET /session/:id/message/:messageID` | `未确认` | 无 | **缺失** | 前端接入相关：单条消息补齐（当前只能整表拉） |
| 32 | `DELETE /session/:id` | `未确认`；v2 保留【U】`V2_HTTP_API_AUDIT.md:146` | 无 | **缺失** | 前端接入相关：**无法删除会话** |
| 33 | `POST /session/:id/fork` | `未确认`；v2 保留【U】`V2_HTTP_API_AUDIT.md:147` | 无 | **缺失** | 前端接入相关：**无法分叉会话** |
| 34 | `POST /session/:id/command` | `未确认` | 无 | **缺失** | 前端接入相关：命令面板无法执行斜杠命令 |
| 35 | `POST /session/:id/shell` | `未确认` | 无 | **缺失** | 前端接入相关：shell 工具消息无法从 UI 发起 |
| 36 | `POST /session/:id/revert` / `/unrevert` | `未确认`；v2 改为 `revert/stage`+`commit`【U】`V2_HTTP_API_AUDIT.md:180-182` | 无 | **缺失** | 前端接入相关：**无法回滚消息/文件** |
| 37 | `DELETE /session/:id/message/:mid` | `未确认` | 无 | **缺失** | 前端接入相关：无法删除单条消息 |
| 38 | `PATCH`/`DELETE /session/:id/message/:mid/part/:pid` | `未确认` | 无 | **缺失** | 前端接入相关：无法编辑/删除单个 part |
| 39 | `POST /session/:id/summarize` / `/init` | `未确认` | 无 | **缺失** | 不适用（内部/自动流程；压缩另有 `session.compacted` 事件） |
| 40 | `POST`/`DELETE /session/:id/share` | `未确认` | 无 | **缺失** | 不适用（分享链接非当前 UI 范围） |
| 41 | `POST /session/:id/permissions/:pid` | 【L】`research/04:230` 明标 **deprecated** | 无 | **缺失** | **不适用**（已由 `POST /permission/:requestID/reply` 取代，本客户端用的是后者） |
| 42 | `POST /session/:id/message`（阻塞式 prompt） | `未确认`；【L】`research/04:225` | 无 | **缺失** | **不适用**（已用 `prompt_async`，非阻塞更优） |
| 43 | `GET /find` (内容搜索) | `未确认`；【L】`research/04:260` | 无 | **缺失** | 前端接入相关：无 ripgrep 内容搜索（只有文件名搜索） |
| 44 | `GET /find/symbol` | `未确认` | 无 | **缺失** | 不适用（无 LSP UI） |
| 45 | `GET /file/status` | `未确认`；【L】`research/04:265` | 无 | **缺失** | 前端接入相关：工作区改动状态（Review 的另一种数据源） |
| 46 | `GET /vcs/status` | `未确认`；v2 保留【U】`V2_HTTP_API_AUDIT.md:233` | 无 | **缺失** | 前端接入相关：工作区 status 列表 |
| 47 | `GET /vcs/diff?mode=&context=` | `未确认`；v2 保留【U】`V2_HTTP_API_AUDIT.md:235` | 无 | **缺失** | 前端接入相关：Android 用 `session.diff` 替代，但拿不到"分支对比/未提交对比"维度 |
| 48 | `GET /vcs/diff/raw` | `未确认` | 无 | **缺失** | 低（`text/x-diff` 原文） |
| 49 | `POST /vcs/apply` | `未确认` | 无 | **缺失** | 不适用（服务端写操作，非 UI 场景） |
| 50 | `GET /command` | `未确认` | 无 | **缺失** | 前端接入相关：命令面板无服务端命令目录 |
| 51 | `GET /agent` | `未确认` | 无 | **缺失** | 前端接入相关：无 agent 选择器（v2 有 `POST /api/session/:id/agent`【U】`mock-api.ts:201`） |
| 52 | `GET /skill` | `未确认`；v2 保留【U】`V2_HTTP_API_AUDIT.md:98` | 无 | **缺失** | 低 |
| 53 | `GET /lsp`、`GET /formatter` | `未确认` | 无 | **缺失** | 不适用（无 LSP/formatter UI） |
| 54 | `GET /config/providers` | `未确认`；【L】`research/04:273` | 无 | **缺失** | 不适用（已用 `GET /provider`） |
| 55 | `PATCH /config` | `未确认` | 无 | **缺失** | 不适用（配置由服务器/WebView 管） |
| 56 | `GET /provider/auth`、`POST /provider/:id/oauth/*` | `未确认` | 无 | **缺失** | 不适用（provider 登录流程；v2 改为 integration/credential【U】`V2_HTTP_API_AUDIT.md:112-133`） |
| 57 | `GET /mcp`、`POST /mcp*`、`DELETE /mcp/:name/auth` | `未确认`；v2 保留【U】`V2_HTTP_API_AUDIT.md:123` | 无 | **缺失** | 前端接入相关：无 MCP 状态/开关面板 |
| 58 | `GET /project/current` | 【U】`V2_HTTP_API_AUDIT.md:60-64`：v2 已 **Remove**，由 `GET /api/location` 取代 | 无 | **缺失** | **不适用**（`GET /path` + `GET /project` 已覆盖） |
| 59 | `POST /project/git/init` | `未确认` | 无 | **缺失** | 前端接入相关：新项目无 git init 入口 |
| 60 | `GET /project/:id/directories` | `未确认` | 无 | **缺失** | 低 |
| 61 | `GET /global/health` | 【U】`V2_HTTP_API_AUDIT.md:54-58`：v2 已合并改名 `GET /api/info` | 无 | **缺失** | **前端接入相关（高）**：官方前端用 `/global/health` 做 **v1/v2 协议探测**【L】`research/04:104-106,616-618`；Android 无任何健康检查，`configMissing` 只能靠请求失败推断 |
| 62 | `GET`/`PATCH /global/config` | `未确认` | 无 | **缺失** | 不适用 |
| 63 | `POST /global/dispose`、`POST /global/upgrade` | `未确认` | 无 | **缺失** | 不适用 |
| 64 | `PUT`/`DELETE /auth/:providerID` | `未确认` | 无 | **缺失** | **不适用**（这是 provider 凭据 API，**不是**客户端鉴权；客户端鉴权见检查项 6） |
| 65 | `POST /log` | `未确认` | 无 | **缺失** | 不适用 |
| 66 | `GET /doc` | `未确认`；【L】`research/04:284` | 无 | **缺失** | 不适用（运行时 OpenAPI JSON） |
| 67 | `POST /instance/dispose` | `未确认` | 无 | **缺失** | 不适用 |
| 68 | `GET /tui/*`（TUI 专用组） | — | 无 | **不适用** | 明确 TUI 专用：事件侧是 `tui.prompt.append`/`tui.command.execute`/`tui.toast.show`/`tui.session.select`（【U】`tui-event.ts:11,14,42,54`），端点组亦为 TUI 服务；原生 UI 无对应场景 |

**A-2 统计：40 行 → `前端接入相关` 22 个，`不适用`/低 18 个。**

### A-3 三条值得单独记录的端点契约漂移（v1 → v2，来自【U】）

| 主题 | v1（Android 在用） | v2（本 clone 内可查） | 证据 |
|---|---|---|---|
| 事件流入口 | `GET /event`、`GET /global/event` | `GET /api/event` | 【U】`mock-api.ts:38`、`V2_HTTP_API_AUDIT.md:276` |
| 鉴权请求头 | `Authorization: Basic base64("opencode:"+password)` | **完全相同** | 【U】`api.ts:5-7,26-32` |
| 权限应答字段 | v1 `Reply = once\|always\|reject`，`ReplyBody{reply,message?}` | v2 `POST .../reply` body 字段名改为 **`decision`** | 【U】`v1/permission.ts:38-44` vs `V2_HTTP_API_AUDIT.md:215` |
| 权限请求字段 | v1 `permission`/`patterns`/`always`/`tool` | v2 `action`/`resources`/`save`/`source` | 【U】`v1/permission.ts:27-36` vs `permission.ts:25-38` |
| 提问协议 | v1 `question.asked` + `POST /question/:id/reply` | v2 **无 question 端点**，被 **form** 取代（`form.created/replied/cancelled`） | 【U】`v1/question.ts:58-60` vs `form.ts:169-171` + `V2_HTTP_API_AUDIT.md:202-208` |

> 结论：**v1 与 v2 的权限/提问协议字段名完全不同，两套解析器不能混用。** Android 目前只实现 v1（正确，因为走的是 v1 面）。

---

## 表 B：SSE 事件覆盖

### B-0 上游事件全集（本 clone 可核验）

**v1 事件 = 17 个**（`packages/schema/src/v1/` 全量抽取，`type:` 字面量）：

```
文件                    file:line              事件 type
v1/session.ts          :573                   session.created        (durable)
v1/session.ts          :581                   session.updated        (durable)
v1/session.ts          :589                   session.deleted        (durable)
v1/session.ts          :597                   message.updated        (durable)
v1/session.ts          :605                   message.removed        (durable)
v1/session.ts          :613                   message.part.updated   (durable)
v1/session.ts          :622                   message.part.removed   (durable)
v1/session.ts          :633                   message.part.delta     (ephemeral)
v1/session.ts          :644                   session.diff           (ephemeral)
v1/session.ts          :652                   session.error          (ephemeral)
v1/permission.ts       :61                    permission.asked       (ephemeral)
v1/permission.ts       :63                    permission.replied     (ephemeral)
v1/question.ts         :58                    question.asked         (ephemeral)
v1/question.ts         :59                    question.replied       (ephemeral)
v1/question.ts         :60                    question.rejected      (ephemeral)
v1/filesystem.ts       :7                     file.edited            (ephemeral)
v1/legacy-event.ts     :9                     command.executed       (ephemeral)
```

**当前面（可能同流下发）额外事件**，权威入口【U】`event-manifest.ts:71-101`（`ServerDefinitions` / `Definitions`）。
**注意【U】`event-manifest.ts:86-101` 的 `Definitions` 同时包含 `LegacyEventV1`、`FileSystemV1`、`SessionStatusEvent`、`V2` 各域**，所以"v1 流里只会出现 v1 事件"这个假设不成立。

### B-1 事件覆盖表

`ChatStore.parseEvent` 定义于【A】`ChatStore.kt:334-416`（`when(type)` 分支体）；信封解包在 `:337`；`else -> null` 在 `:414`。

| # | 上游事件 type | 上游证据 (file:line) | Android `parseEvent` 分支 | 状态 |
|---|---|---|---|---|
| 1 | `session.created` | 【U】`v1/session.ts:573`（durable，`{sessionID, info}` `:571-579`） | **无分支** → `else` `:414` 丢弃 | **缺失**：会话列表不实时更新（Home 靠 `listSessions()` 重拉）。副作用见 §3.1（durable 变量） |
| 2 | `session.updated` | 【U】`v1/session.ts:581`（durable，`{sessionID, info}`） | **无分支** | **缺失**（部分间接）：`ChatViewModel.kt:267-278` 对 `session.*` 前缀调 `maybeRefreshOnSession`（`:901-908`），后者经 `sessionIdOf`（`ChatStore.kt:419-424`，读 `properties.info.id`）**只有这三个 session.* 能命中** → 命中后节流重拉快照并在 `:804-816` 回填标题 |
| 3 | `session.deleted` | 【U】`v1/session.ts:589`（durable） | **无分支** | **缺失**：被删会话仍留在屏上，无任何提示 |
| 4 | `message.updated` | 【U】`v1/session.ts:597`（durable，`{sessionID, info}`，**无 parts**） | `:339-342`（同时吃 `message.updated.v2`） | **部分**：正确取 `properties.info` 后包装成 `{info}` 交给 `parseMessage`（`:287-302`），因此 `parts` 为空 → 依赖 `upsert`+`mergeParts`（`:239-247, 270-277`）保留旧 parts（**正确**）。丢弃 assistant 的 `cost`/`error`/`finish`/`parentID`/`path`/`mode`/`structured`，user 的 `agent`/`model`/`system`/`tools`/`format`/`summary`（`v1/session.ts:332-355, 453-488`） |
| 5 | `message.removed` | 【U】`v1/session.ts:605`（durable，`{sessionID, messageID}`） | `:362-364` | **1:1**（只取 `messageID`；`sessionID` 由外层 `acceptsSession` 门控 `ChatViewModel.kt:279`） |
| 6 | `message.part.updated` | 【U】`v1/session.ts:613`（durable，`{sessionID, part, time}`） | `:344-348` | **部分**：见 §1/§2。part 字段只取 `id`/`type`/`text`/`tool`/`state.status`/`state.input`/`state.output`/`state.error`/`mime`/`filename`/`url`（`parsePart` `:304-324`）；丢弃 `callID`、`time`、`metadata`、`synthetic`、`ignored`、`snapshot`、`hash`+`files`(patch)、`name`(agent)、`cost/reason/tokens`(step-finish)、`attachments`、`attempt/error`(retry)、`auto/overflow`(compaction) |
| 7 | `message.part.removed` | 【U】`v1/session.ts:622`（durable，`{sessionID, messageID, partID}`） | **无分支**；`ChatStore.Event` 里**没有**对应事件类型（`ChatStore.kt:87-126`） | **缺失**：被删除的 part 会**一直留在时间线上**（无任何回收路径） |
| 8 | `message.part.delta` | 【U】`v1/session.ts:633`（ephemeral，`{sessionID, messageID, partID, field, delta}` `:632-641`） | `:350-360` + `applyPartDelta` `:256-268` | **部分**：条目缺失时正确丢弃（`:259-264`，与上游一致【L】`research/04:475`）。但 `:257 if (event.field != "text") return state` → **只支持 `field == "text"`**，其他字段的 delta 静默丢弃。无"打字累加器"（官方前端有独立 `part_text_accum_delta`【L】`research/04:482-483`）→ 仅影响动画/滚动锚定 |
| 9 | `session.diff` | 【U】`v1/session.ts:644`（ephemeral，`{sessionID, diff: FileDiff.LegacyInfo[]}`） | `:408-409` | **1:1**：整表替换；`FileDiff` 字段与 `LegacyInfo`（【U】`file-diff.ts:16-22`）逐字段一致；`sessionID` 门控见 `ChatStore.kt:221-226` |
| 10 | `session.error` | 【U】`v1/session.ts:652`（ephemeral，`{sessionID?, error: AssistantError}`；`AssistantError` 是 `{name, data:{…}}` 对象，`v1/session.ts:385-395`） | `:376-379` | **部分 / 有缺陷**：`p.optString("error", …)` 对**对象**取值。Android `org.json` 的 `optString` 在值为 `JSONObject` 时返回其 `toString()` → 错误卡正文会显示**整段 JSON 文本**（`SessionErrorCard.kt:147-153` 按 monospace 原样渲染），而不是 `data.message`。未提取 `error.name` / `error.data.message` / `APIError.statusCode` 等 |
| 11 | `permission.asked` | 【U】`v1/permission.ts:61`（ephemeral，properties = `Request.fields`） | `:386-388` | **部分（协议层 1:1，消费层缺字段）**：见 §3 |
| 12 | `permission.replied` | 【U】`v1/permission.ts:63`（`{sessionID, requestID, reply}`） | `:390-392` | **部分**：只用 `requestID` 移除；`reply`（`once`/`always`/`reject`）丢弃 → 无法区分"谁批准/拒绝了" |
| 13 | `question.asked` | 【U】`v1/question.ts:58`（ephemeral，properties = `Request.fields`） | `:397-399` | **1:1**（见 §4） |
| 14 | `question.replied` | 【U】`v1/question.ts:59`（`{sessionID, requestID, answers}` `:47-53`） | `:401-403` | **部分**：只用 `requestID`；`answers: string[][]` 丢弃（dock 不需要回显，可接受） |
| 15 | `question.rejected` | 【U】`v1/question.ts:60`（`{sessionID, requestID}` `:54-56`） | `:401-403` | **1:1** |
| 16 | `file.edited` | 【U】`v1/filesystem.ts:7`（`{file}`） | **无分支** | **缺失**：文件树/Review 不随编辑自动失效。**不适用度中等**——当前 Review 由 `session.diff` 驱动 |
| 17 | `command.executed` | 【U】`v1/legacy-event.ts:9`（`{name, sessionID, arguments, messageID}`） | **无分支** | **缺失**：**不适用**（Android 无命令面板执行路径） |
| 18 | `session.status` | 【U】`session-status-event.ts:35-43`（`{sessionID, status: idle\|retry\|busy}`；`Info` `:9-33`） | `:368-374` | **部分**：只映射 `busy`/`retry`→`RUNNING`、`idle`→`IDLE`。**丢弃 `status.attempt`、`status.message`、`status.next`（重试倒计时）、`status.action{reason,provider,title,message,label,link}`**（【U】`:14-28`）→ 无法显示"第 N 次重试 / 原因 / 倒计时" |
| 19 | `session.idle` | 【U】`session-status-event.ts:47`（**源码内标注 `// deprecated`**，`:45`） | `:381` | **1:1**（兼容保留合理；官方已弃用，`session.status` 是替代） |
| 20 | `session.compacted` | 【U】`session-compaction-event.ts:7` | **无分支** | **缺失**：UI 只有 `compaction` **part** 的一行提示（`PartRenderer.kt:121, 433-440`），事件本身未消费 |
| 21 | `server.connected` | 【U】`server-event.ts:5`（`server.connected`）；首帧语义【L】`research/04:79` | **无分支**（`EventStream` 只据此不做事，靠 HTTP 成功即 `ESTABLISHED` `EventStream.kt:124`） | **缺失（体验）**：官方前端用它确认"流真的活了"；Android 只能靠 35s 读超时（`EventStream.kt:203`）反推 |
| 22 | `server.heartbeat` | **未确认**（clone 内无此 schema；【L】`research/04:80,137` 记为 `handlers/event.ts:65`） | **无分支**（落入 `else`） | **不适用**（忽略即正确；它仍起保活作用，使 35s 读超时不触发） |
| 23 | `server.instance.disposed` | **未确认**（【L】`research/04:114,138`） | **无分支** | **缺失**：全局流下某个 directory 实例销毁不感知 |
| 24 | `global.disposed` | 【U】`server-event.ts:6`（`global.disposed`） | **无分支** | **缺失**：服务端销毁后 Android 只能等读超时重连 |
| 25 | `vcs.branch.updated` | 【U】`vcs-event.ts:8` | **无分支** | **缺失**：分支 chip 不实时刷新（只在 `refreshSnapshot` 时拉 `GET /vcs`，`ChatViewModel.kt:724`） |
| 26 | `project.updated` | 【U】`project.ts:59` | **无分支** | **缺失**：Home 项目列表不自动刷新 |
| 27 | `pty.created` / `pty.updated` / `pty.exited` / `pty.deleted` | 【U】`pty.ts:34-37`（同一文件连续 4 行） | **无分支** | **缺失（多端同步）**：终端 tab 靠 REST 探活 + WS（`PtyApi.kt:161-168`），别的客户端新建/关闭 PTY 不会同步过来 |
| 28 | `lsp.updated` | 【U】`lsp-event.ts:5` | **无分支** | **不适用**（无 LSP UI） |
| 29 | `session.next.*`（v2 durable/ephemeral，40+ 个） | 【U】`session-event.ts` 全量枚举（如 `:398 session.text.delta`、`:501 session.tool.called`、`:386 session.text.started` …） | **无分支** | **不适用（当前走 v1）**。⚠️ 但有**误触发**副作用：这些 type 以 `session.` 开头，会命中 `ChatViewModel.kt:267` 的 `startsWith("session.")` 分支去 bump liveness 时钟（`:275`），在 v2 server 下会让空闲判定失效 |
| 30 | `form.created` / `form.replied` / `form.cancelled` | 【U】`form.ts:169-171` | **无分支** | **不适用（v1 面）**；但**这是 v2 取代 question 的协议**（【U】`V2_HTTP_API_AUDIT.md:202-208`），未来切 v2 时必须实现 |
| 31 | `mcp.tools.changed` / `mcp.status.changed` / `mcp.resources.changed` | 【U】`mcp-event.ts:7,14,23` | **无分支** | **缺失**（无 MCP 面板） |
| 32 | `tui.prompt.append` / `tui.command.execute` / `tui.toast.show` / `tui.session.select` | 【U】`tui-event.ts:11,14,42,54` | **无分支** | **不适用**（TUI 专用） |
| 33 | `worktree.ready` / `worktree.failed` / `worktree.updated` / `worktree.resolved` | 【U】`worktree-event.ts:8,16`、`worktree.ts:56,61` | **无分支** | **不适用**（v2 工作树未接入） |
| 34 | `workspace.ready` / `workspace.failed` / `workspace.status` | 【U】`workspace-event.ts:14,21,28` | **无分支** | **不适用** |
| 35 | `shell.created` / `shell.exited` / `shell.deleted` | 【U】`shell.ts:54-56` | **无分支** | **部分不适用**（Android 终端走 PTY 而非 server shell） |
| 36 | 其余目录类事件：`agent.updated`(`agent.ts:11`)、`model.updated`(`model.ts:12`)、`provider.updated`(`provider.ts:26`)、`config.updated`(`config.ts:134`)、`command.updated`(`command.ts:7`)、`skill.updated`(`skill.ts:36`)、`reference.updated`(`reference.ts:8`)、`plugin.updated`(`plugin.ts:46`)、`installation.updated`(`installation-event.ts:7`)、`credential.updated/switched`(`credential.ts:18,22`)、`integration.updated`(`integration.ts:53`)、`filesystem.changed`(`filesystem.ts:9`)、`location.shutdown`(`location-event.ts:6`)、`ide.installed`(`ide-event.ts:7`)、`websearch.updated`(`websearch.ts:41`) | 【U】各文件对应行 | **无分支** | **缺失/不适用混合**：`provider/model/agent/config.updated` 对 Android 的 model picker（`ChatViewModel.kt:362`）**有实际影响**——目录变化后 UI 不会自动刷新；其余不适用 |
| 37 | `todo.updated` | **未确认 —— 本 clone 内不存在该事件定义**。`packages/schema/src/session-todo.ts` 不存在（schema/src 全量文件清单已 ls 确认）；schema/src 全量 `type:"…"` 抽取中**无任何 `todo`** | `:411-412` | **缺失（无上游定义可对齐）**：Android 解析 `{sessionID, todos[]}` 且 `ChatState.todos` 无 UI 消费者（`ChatStore.kt:78-82`）。**该分支在 v1 面是否有线上来源，本 clone 无法证实** |
| 38 | `message.updated.v2` | **未确认 —— 本 clone 内不存在该字符串** | `:339`（与 `message.updated` 同一分支） | **死分支**（无上游定义） |

### B-2 三项特别核查（任务点名）

#### B-2.1 `/global/event` 信封解包

【A】`ChatStore.unwrapGlobal` `:433-434`：
```kotlin
fun unwrapGlobal(data: JSONObject): JSONObject? =
    data.optJSONObject("payload")?.takeIf { !it.optString("type", "").isEmpty() }
```
调用点【A】`ChatViewModel.kt:265-266`；`EventStream` 以 `global = true` 构造（【A】`ChatViewModel.kt:252-260`，`EventStream.kt:76-92` 生成 `GET /global/event`）。

- ✅ 能正确处理"普通事件"帧 `{directory, project?, workspace?, payload:{id,type,properties}}`。
- ❌ **不能处理 `sync` 包装帧**：`parseEvent` 的 `when(type)` 里**没有任何 `"sync"` 分支**（`:338-415`），`unwrapGlobal` 只校验 `payload.type` 非空，`sync` 帧的 `type == "sync"` 会通过校验，随后 `parseEvent("sync", …)` 落入 `else -> null`（`:414`）**被整帧丢弃**。
- 证据链：`sync` 包装的存在性由【L】`research/04:112-119` 记载（`groups/global.ts:36-49`，该文件**不在 clone 内** → **上游 `未确认`**）；代码侧"无 `sync` 分支"是**【A】确定事实**。
- **高严重度含义**：若线上确实以 `sync` 包装 durable 事件，则 **B-1 中所有 durable 事件（#1–#7）在全局流上全部静默丢失**，只剩 ephemeral 事件（delta / status / diff / error / permission / question）可用；且 §B-2.2 的节流刷新也依赖 `session.created/updated/deleted`，会一并失效 → 实时刷新退化为"仅连接建立时一次快照"。
  反之，若线上 durable 事件**不**被包装，则本节降级为"防御性缺口"。
  **→ 必须对真实 server 实测一次（见 §6 验证清单 V1）。**

#### B-2.2 `properties.sessionID` 路由

两层：

1. **门控层**`ChatStore.eventSessionId` `:444-450` + `acceptsSession` `:457-460`，调用点 `ChatViewModel.kt:279`：
   `properties.sessionID` → `properties.info.sessionID` → `properties.part.sessionID`，三级回退；无路由信息则放行（`:458`）。
   ✅ 覆盖了 v1 全部有 session 语义的事件：`session.*`（`v1/session.ts:571-657` 均带 `sessionID`）、`message.updated`（`info.sessionID`，`v1/session.ts:327-330`）、`message.part.updated`（`part.sessionID`，`v1/session.ts:83`；且 v1 事件体本身也带 `sessionID`，`v1/session.ts:616`）、`permission.*`/`question.*`。
2. **刷新层**`ChatStore.sessionIdOf` `:419-424`（**另一套逻辑**：读 `properties.id`，再读 `properties.info.id`）→ `ChatViewModel.maybeRefreshOnSession` `:901-908`。
   ⚠️ **该路径基本失效**：`properties` = `event.data`（事件体），而**事件体不含 `id` 字段**（`id` 在信封上，【U】`v1/session.ts:571-657` 的各 `schema` 只有 `sessionID`/`info`/`part`/`messageID`…）。因此 `sessionIdOf` 只在 `session.created`/`session.updated`/`session.deleted`（事件体含 `info`）上返回非空。
   → 结论：**节流刷新只可能被这 3 个 durable 事件触发**，而它们又受 §B-2.1 的 `sync` 风险影响；`message.*` / `part.*` 事件**永远不会**触发刷新。

#### B-2.3 `message.part.updated` 的 part 类型全集 vs 我们渲染的类型

上游 v1 `Part.Union` = **12 种**（【U】`v1/session.ts:357-383`，判别字段 `type`）：

| # | part type | 上游定义 | Android 渲染 | Android 解析（`parsePart` `ChatStore.kt:304-324`） |
|---|---|---|---|---|
| 1 | `text` | 【U】`v1/session.ts:102-115` | ✅ `PartRenderer.kt:95-103` | ✅ `text` |
| 2 | `reasoning` | 【U】`:118-128` | ✅ `:117-119`（可折叠思考） | ✅ `text` |
| 3 | `tool` | 【U】`:315-325` | ✅ `:105-115`（ToolCard） | ✅ `tool`/`state.status`/`state.input`/`state.output`/`state.error` |
| 4 | `file` | 【U】`:171-179` | ✅ `:123`（AttachmentChip） | ✅ `mime`/`filename`/`url` |
| 5 | `compaction` | 【U】`:195-202` | ⚠️ `:121` 仅一行固定文案（`oc_compaction`），**不看 `auto`/`overflow`/`tail_start_id`** | ❌ 三字段全丢 |
| 6 | `subtask` | 【U】`:204-218` | ❌ **无分支 → 不渲染** | ❌ **`prompt`/`description`/`agent`/`model`/`command` 全丢** |
| 7 | `step-start` | 【U】`:233-238` | ❌ **无分支** | ❌ `snapshot` 丢弃 |
| 8 | `step-finish` | 【U】`:240-257` | ❌ **无分支** | ❌ **`reason`/`cost`/`tokens{input,output,reasoning,cache}` 全丢**（→ 无法做 per-step 成本/上下文增量） |
| 9 | `snapshot` | 【U】`:87-92` | ❌ **无分支** | ❌ `snapshot` 丢弃 |
| 10 | `patch` | 【U】`:94-100` | ❌ **无分支**（详见 §2） | ❌ **`hash`/`files[]` 全丢** |
| 11 | `agent` | 【U】`:181-193` | ❌ **无分支** | ❌ `name`/`source` 丢弃 |
| 12 | `retry` | 【U】`:220-231` | ❌ **无分支** | ❌ **`attempt`/`error{APIError}`/`time` 全丢** |

**渲染覆盖：5 / 12（其中 `compaction` 是降级渲染，实质 4.5 / 12）。**
未渲染的 7 种（`subtask`/`step-start`/`step-finish`/`snapshot`/`patch`/`agent`/`retry`）在 `parsePart` 里只保留了 `id` + `type` 两个字段，**其余 payload 全部丢弃**，因此即使后续想渲染也拿不到数据（需重新解析 `raw`；注：`UiPart` 无 `raw` 字段，而 `OpenCodeClient.Part` 有 `raw`（`:93`）——但从 REST 快照过来的 part 经 `ChatViewModel.kt:940-949` 的 `toUiPart()` 转成 `UiPart` 时 **`raw` 未被带过来**）。

> 对照：官方前端对 `patch`/`step-start`/`step-finish` 是**主动跳过**的（`SKIP_PARTS`，【L】`research/04:398-399`）。但该文件 `packages/app/src/context/global-sync/event-reducer.ts` **在本 clone 内不存在**（`Test-Path` = `False`），且全仓 `SKIP_PARTS` **零命中** → **上游证据 `未确认`**。需要注意官方跳过的是这 3 种，**没有跳 `subtask`/`agent`/`retry`/`snapshot`**——这 4 种我们是无分支而非有意跳过。

---

## 额外必查项（逐条结论 + 证据）

### 1. 上游 part 类型全集 vs `UiPart`/`PartRenderer` 支持的渲染类型

见 §B-2.3。

- 上游全集：**12 种**（【U】`v1/session.ts:357-383`）。
- `ChatStore.UiPart` 可承载字段：`id`/`type`/`text`/`toolName`/`toolStatus`/`mime`/`filename`/`url`/`toolInput`/`toolOutput`/`toolError`（【A】`ChatStore.kt:18-33`）。
- `PartRenderer` 实际渲染：**`text`/`tool`/`reasoning`/`compaction`/`file` 共 5 种**（【A】`PartRenderer.kt:94-124`，`when` 无 `else`）。
- **缺 7 种**：`subtask`、`step-start`、`step-finish`、`snapshot`、`patch`、`agent`、`retry`。
- 其中**按官方口径可接受的跳过**：`patch`、`step-start`、`step-finish`（【L】`research/04:398-399`，**上游 `未确认`**）。
- **非官方跳过、属于真实缺口**：`subtask`（子任务卡片）、`agent`（agent mention 回显）、`retry`（重试提示）、`snapshot`（纯内部，影响小）。
- 另注：`ChatView.kt:1003-1008`（已退役的 View 版适配器）渲染 `text`/`reasoning`/`tool`/`file`，**连 `compaction` 都没有**；`MessageTimeline.kt:252-253` 只按 `type=="text"` / `"file"` 取正文与附件。

### 2. `patch` part 是否被渲染

**结论：未渲染，确认缺失。**

- 上游 `PatchPart` = `{...partBase, type:"patch", hash: string, files: string[]}`（【U】`v1/session.ts:94-100`）。
- `PartRenderer` 的 `when(part.type)` 分支为 `text`/`tool`/`reasoning`/`compaction`/`file`（【A】`PartRenderer.kt:94-124`）——**没有 `"patch"`**。`ChatStore.parsePart` 也不提取 `hash`/`files`（【A】`ChatStore.kt:304-324`）。
- **易混淆点（避免误判为已实现）**：`patch` 作为**工具名**是被处理的——`ToolPresentation.kt:112`（`"patch" -> Icon.CODE`）、`:198`（`toolName == "patch"` 判为 edit 类）、`ChatView.kt:784`。这是 `type=="tool" && tool=="patch"`（apply-patch 工具调用），**与 `type=="patch"` 的 PatchPart 是两回事**。
- 部分信息通过**另一条通道**可达：`GET /session/:id/diff` + `session.diff` 事件 → `ChatState.diff`（【A】`ChatStore.kt:72-77, 221-226`）→ Review 面板 Changes tab。所以"文件改动可见"，但**patch part 自身的 `hash`/`files` 永远不可见**。
- 严重度：**低**（与官方跳过策略一致；不影响主流程）。

### 3. 权限协议字段（`always[]`、`metadata`、`tool`）

**协议解析：1:1；UI 消费：3 个字段被丢弃。**

- 上游 v1 `Request`（【U】`v1/permission.ts:27-36`）：`id`、`sessionID`、`permission`、`patterns[]`、`metadata: Record<string, unknown>`（**必填**）、`always[]`、`tool?: {messageID, callID}`。
- Android 解析（【A】`OpenCodeClient.kt:264-272`）：**7 个字段全部解析**，`tool`/`metadata` 允许缺失。
- UI 使用情况（【A】`ui/session/dock/SessionPermissionDock.kt`）：
  | 字段 | 是否被 UI 使用 | 证据 |
  |---|---|---|
  | `permission` | ✅ 用于标题提示文案 + 工具描述映射（`"read"/"edit"/"bash"/…` 共 14 个分支） | `:83`、`:276-290` |
  | `patterns` | ✅ 逐条渲染为资源行 | `:125`、`:140-144` |
  | `id` / `sessionID` | ✅ 回复与路由 | `ChatStore.kt:174,186,191`；`ChatViewModel.kt:887` |
  | **`always`** | ❌ **解析后无任何消费者** | 全仓 grep 无 `.always` 使用；`OpenCodeClient.kt:269` 是唯一出现点 |
  | **`metadata`** | ❌ **解析后无任何消费者** | `OpenCodeClient.kt:271` 是唯一出现点 |
  | **`tool`** | ❌ **解析后无任何消费者** | `OpenCodeClient.kt:270`；`ChatStore.kt:174-181` 只做 id/session 门控 |
- **影响**：`always` 决定了"允许并记住"会持久化哪些 pattern —— 官方 UI 会把它显示给用户（"Always allow `<pattern>`"）；我们只在按钮上写死「始终允许」（`oc_permission_allow_always`，`:180`），**用户看不到自己将要持久化什么**。`tool` 丢失导致无法跳到发起该 ask 的工具卡片。
- 应答体：只发 `reply`（【A】`OpenCodeClient.kt:746-747`）；上游 `ReplyBody` 还有可选 `message`（【U】`v1/permission.ts:41-44`），未使用（低）。
- 严重度：**中**（`always` 的信息透明性属安全相关 UX）。

### 4. `question` 协议（`multiple`、`custom`、`answers: string[][]`）

**结论：v1 协议完整，1:1。**

- 上游 v1：`Info = {question, header, options:[{label,description}], multiple?, custom?}`（【U】`v1/question.ts:20-30`，`custom` 注释明写 `default: true`，`:29`）；`Request = {id, sessionID, questions[], tool?}`（`:35-40`）；`Answer = string[]`（`:41`）；`Reply = {answers: Answer[]}`（`:42-46`）；`Replied = {sessionID, requestID, answers}`（`:47-53`）；`Rejected = {sessionID, requestID}`（`:54-56`）。
- Android 解析（【A】`OpenCodeClient.kt:297-363`）：
  - `multiple` ✅ 解析且 `default false`（`:329`）
  - `custom` ✅ 解析且 `default true`（`:330`）——与上游注释一致
  - `options.label`/`description` ✅（`:297-304`），空 label 的选项被丢弃（`:322`）
  - `tool` ✅ 解析但 ❌ 无消费者（同 §3）
- 应答：`{answers: string[][]}`（【A】`OpenCodeClient.kt:762-770`），数组长度与题目数对齐、未答题目填空数组（【A】`SessionQuestionDock.kt:133-142`、注释 `:69-75`）。
- UI 语义（【A】`SessionQuestionDock.kt`）：`multiple` 切换（`:107-108`）vs 单选替换（`:110`）；`custom` 非 `false` 时出现「自定义…」行（`:300-302`），单选下自定义文本**就是**答案（`:140`），多选下**追加**为额外 label（`:138`）——与上游 `pick()`/`customOpen` 语义一致（doc 注释 `:71-75` 自述）。
- **一处需留意**：v1 的 options **无 value 概念**（只有 `label`），把用户自由文本当作 label 提交是唯一可行语义（上游亦然）→ 不算缺口。
- 严重度：**无缺口**。

### 5. 分页 / 游标类参数

| 端点 | 上游支持 | Android | 结论 |
|---|---|---|---|
| session 列表 | 【L】`research/04:209`：`scope?`/`path?`/`roots?`/`start?`/`search?`/`limit?`；v2【U】`V2_HTTP_API_AUDIT.md:141` 明载"保留现有 filtering, ordering, and **cursor** contract" | `listSessions(directory)`（【A】`OpenCodeClient.kt:604-606`）**只发 `?directory=`**；调用点 `ChatViewModel.kt:190` 无参；本地再 `sortedByDescending { it.updatedAt }`（`:191`） | **缺失**：无 `limit`、无游标、无搜索、无 scope/roots 过滤 → 会话很多时一次性拉全量 |
| message 列表 | 【L】`research/04:215`：`limit?(int≥0)&before?`；v2【L】`research/04:328`：`limit(1..200)&order(asc\|desc)&cursor`；v2【U】`V2_HTTP_API_AUDIT.md:190` | `messages(sessionID, directory)`（【A】`OpenCodeClient.kt:668-670`）**只发 `?directory=`**；调用点 `ChatViewModel.kt:784` 是**每次 `refreshSnapshot` 全量拉取** | **缺失（影响最大）**：长会话每轮对话/每次重连都全量传输 + 全量 `JSONArray` 解析 + 全量 DiffUtil 比较 → O(历史) 的开销 |
| 单条消息 | `GET /session/:id/message/:mid` | 无实现 | **缺失**（A-2 #31） |
| diff | 【L】`research/04:214`：仅 `messageID` | `sessionDiff(sessionID, messageID?, directory?)`（【A】`OpenCodeClient.kt:784-790`） | **1:1** |
| 文件列表 | `?path=` | `listFiles(path, directory)`（`:645-651`） | **1:1** |
| 文件搜索 | 【L】`research/04:261`：`query`/`dirs?`/`type?`/`limit?(1..200)` | `findFiles(query, directory)`（`:680-692`）**只发 `query`** | **部分**：无 `type`/`dirs`/`limit` → 结果集不可控 |
| 事件流补偿 | v2 用 `/api/session/:id/history?after=<seq>` + `/api/session/:id/event?after=<seq>` 做**精确断点续传**（【L】`research/04:128-129, 513-514`）；v1 无 `Last-Event-ID` 支持 | `EventStream` **不发 `Last-Event-ID`**（【A】`EventStream.kt:76-92` 请求头只有 `Accept` 与可选 `Authorization`）；重连后靠 `refreshSnapshot()` 全量兜底（【A】`ChatViewModel.kt:288`） | **缺失但可接受**（v1 面本就没有 seq 回放；v2 才有） |
| SSE 重连节流 | — | 指数退避 1s..30s（【A】`EventStream.kt:189, 204-205`） | ✅ |

**汇总：Android 没有任何一个端点使用 `limit`/游标参数。** 全部列表接口都是"全量 + 本地排序/过滤"。

### 6. 认证（用户名/密码 Basic auth、`Authorization` 头）是否与服务端约定一致

**上游约定（本 clone 内可查，【U】）**：
- `_upstream/packages/app/src/runtime/server/api.ts:5-7`：
  ```ts
  export function authTokenFromCredentials(input: { password: string }) {
    return btoa(`opencode:${input.password}`)   // ← 用户名硬编码 "opencode"
  }
  ```
- `api.ts:26-32`：仅当 `server.password` 存在时挂 `Authorization: Basic …`；否则 `undefined`。
- `api.ts:9-17` `authFromToken`：从 `auth_token` 解码时**只取第一个冒号之后的部分作为 password**，显式丢弃 legacy username。
- 测试佐证：`api.test.ts:9-11`（"ignores legacy usernames and preserves colons in passwords"，`:secret` → `{password:"secret"}`）、`api.test.ts:26-27`（`{username:"legacy",password:"secret"}` → `btoa("opencode:secret")`）、`client.test.ts:119,123`、`image.test.ts:41`、`health.ts:81-83`。

**Android 实现（【A】）**：
- `OpenCodeClient.kt:54-58`：`if (!username.isNullOrEmpty() || !password.isNullOrEmpty())` → `Basic base64("$username:$password")`。
- `EventStream.kt:86-90`：同一规则，同一拼接方式。
- `PtyApi.kt:217-220` + `:199-201`：Basic；无 ticket 且 v1 时额外用 `?auth_token=base64(user:pass)`。
- `ServerConfigStore.kt:150-157`：
  ```kotlin
  val username = http.optString("username", "").takeIf { it.isNotEmpty() }
      ?: password?.let { DEFAULT_USERNAME }   // DEFAULT_USERNAME = "opencode" (:246)
  ```

**结论：基本一致，但有 2 处可导致 401 / 行为分歧的偏差。**

| 偏差 | 上游 | Android | 后果 |
|---|---|---|---|
| **honour 了 legacy `username`** | 硬编码 `opencode`，**故意忽略**存储里的 `username`（【U】`api.ts:5-7` + `api.test.ts:26-27`） | 若存储带 `username`（如官方迁移遗留的 `legacy`）则**照用**（【A】`ServerConfigStore.kt:154`） | 若某 server 的凭据是 `opencode:<pw>`，而本地存储残留了别的 username → Android 发 `legacy:<pw>` → **401，而官方前端能连上** |
| **只有 username 无 password 时仍发 Basic** | 仅 `password` 存在才发（【U】`api.ts:26`） | `username` 非空即发（【A】`OpenCodeClient.kt:54`、`EventStream.kt:86`）；且 `"$username:$password"` 在 password 为 null 时产生字面量 `"null"` → `Basic base64("user:null")` | 无密码的 server 上多带一个错误凭据头（通常被忽略，但语义不干净） |

- 其他一致点：✅ `Authorization: Basic`（非 Bearer）；✅ 不依赖 Cookie；✅ `auth_token` 仅用于 PTY WS（与官方一致，且官方 v2 分支已注释掉 ticket 申请【L】`research/04:655-658`）；✅ 无凭据时不发头。
- 未做：**没有任何 401 专门处理**（`OpenCodeClient.kt:65-67` 统一抛 `ApiException`；`EventStream.kt:110-116` 把 4xx 计为"永久错误"重试 5 次后 `CLOSED`）。若服务端要求鉴权而本地未配，用户只会看到"连接失败"，没有"请填写密码"的引导。
- v2 面的 `auth_token` / credential 体系（【U】`V2_HTTP_API_AUDIT.md:129-131`）`不适用`。

---

## 5. 会导致功能不可用或明显降级的缺口清单（按严重度排序）

| 级别 | # | 缺口 | 证据 | 影响 |
|---|---|---|---|---|
| **P0** | 1 | **`/global/event` 的 `sync` 包装帧无处理分支** → 若线上确用该包装，**全部 durable 事件被整帧丢弃**（`session.created/updated/deleted`、`message.updated`、`message.part.updated`、`message.removed`、`message.part.removed`） | 【A】`ChatStore.kt:433-434`（只校验 `payload.type`）、`:338-415`（无 `"sync"` 分支）、`:414`；【A】`ChatViewModel.kt:265-266`；存在性【L】`research/04:112-119`（**上游 `未确认`**） | 实时渲染退化为"仅重连时一次快照"。工具卡/正文只有 `message.part.delta`（ephemeral）能补；`message.part.updated`、`message.removed` 完全丢失。⚠️ **必须先实测（V1）**，这决定了后面几条的真实严重度 |
| **P0** | 2 | **`message.part.removed` 完全未实现**（无事件类型、无分支、无回收路径） | 【U】`v1/session.ts:622`；【A】`ChatStore.kt:87-126`（`Event` 无该类型）、`:338-415`（无分支） | 服务端删除的 part **永久留在时间线**（如被压缩/被撤回的内容），只能靠整屏重建消失 |
| **P0** | 3 | **`maybeRefreshOnSession` 刷新路径基本失效** | 【A】`ChatStore.kt:419-424`（`sessionIdOf` 读事件体的 `id`，而 v1 事件体不含 `id`）→ 【A】`ChatViewModel.kt:901-908`；唯一能命中者是 `session.created/updated/deleted`（事件体含 `info`，【U】`v1/session.ts:571-595`） | 服务端后台改名、标题、目录、会话列表变化**不会**被流式事件驱动刷新（仅靠 `refreshSnapshot` 在连接建立/切换会话时各拉一次）。与 #1 叠加后刷新面进一步收窄 |
| **P0** | 4 | **消息列表无 `limit`/游标，每次快照全量拉取** | 【A】`OpenCodeClient.kt:668-670`（只发 `?directory=`）、`ChatViewModel.kt:784`（每次 `refreshSnapshot` 全量）；上游支持 `limit`/`before`【L】`research/04:215`，v2 为 `limit(1..200)&cursor`【L】`research/04:328`、【U】`V2_HTTP_API_AUDIT.md:190` | 长会话下每次重连/切会话的延迟、内存、DiffUtil 开销随历史线性增长 → 首屏卡顿、OOM 风险 |
| **P1** | 5 | **12 种 part 类型只渲染 5 种；7 种连 payload 都没解析** | 【U】`v1/session.ts:357-383`；【A】`PartRenderer.kt:94-124`、`ChatStore.kt:304-324` | `subtask`（子任务卡片）、`agent`（agent 回显）、`retry`（重试提示）**既无渲染也无数据**；`step-finish` 的 `cost`/`tokens` 全丢 → 无法做 per-step 成本/上下文增量展示。注意 `UiPart` 无 `raw` 字段，REST 路径 `ChatViewModel.kt:940-949` 也没把 `OpenCodeClient.Part.raw`（`:93`）带过来 → **数据在转换时已不可逆丢失** |
| **P1** | 6 | **`session.error` 载荷解析错误**：对 `AssistantError` **对象**调用 `optString` | 【U】`v1/session.ts:652`（`error: Assistant.fields.error`）、`:385-395`（`{name,data:{…}}` 判别联合）、`:460`；【A】`ChatStore.kt:376-379`；渲染 【A】`SessionErrorCard.kt:147-153` | 错误卡正文显示**整段 JSON**（如 `{"name":"APIError","data":{"message":"…","statusCode":429,…}}`），而非 `data.message`；`isRetryable`/`statusCode`/`retryCount` 等可用于给出可操作提示的信息全部浪费 |
| **P1** | 7 | **`session.status` 的 retry 细节全部丢弃** | 【U】`session-status-event.ts:9-33`（`attempt`/`message`/`next`/`action{reason,provider,title,message,label,link}`）；【A】`ChatStore.kt:368-374` | 重试中无法显示"第 N 次重试 / 原因 / 倒计时 / 跳转链接"，用户只看到一个转圈 |
| **P1** | 8 | **`always[]` 被解析但从不展示** | 【U】`v1/permission.ts:33`；【A】`OpenCodeClient.kt:269`（唯一出现点）、`SessionPermissionDock.kt:180`（按钮写死文案） | 用户点「始终允许」时**看不到将要持久化的 pattern**（安全相关的信息透明性） |
| **P1** | 9 | **认证与上游约定有 2 处偏差**（honour legacy `username`；只有 username 时也发 Basic 且 password 为 null 时拼出 `"null"`） | 【U】`api.ts:5-7, 26-32` + `api.test.ts:26-27`；【A】`ServerConfigStore.kt:154`、`OpenCodeClient.kt:54-58`、`EventStream.kt:86-90` | 存储残留 `username` 的机器上，**官方前端能连而 Android 401**；且无 401 引导 |
| **P2** | 10 | **无 `GET /global/health`**（协议探测 / 连接健康） | 【U】`V2_HTTP_API_AUDIT.md:54-58`（v2 已合并为 `/api/info`）；用法【L】`research/04:104-106,616-618`；【A】全仓无实现 | 无法区分"服务器不可达 / 需要鉴权 / 是 v2 server"，也不能像官方前端那样自动判定 v1/v2 面；`configMissing` 只能靠请求失败反推 |
| **P2** | 11 | **`message.part.delta` 只支持 `field == "text"`** | 【U】`v1/session.ts:632-641`（`field` 是任意 string）；【A】`ChatStore.kt:257` 早退 | 非 text 字段的增量（若有）静默丢失；无打字累加器 → 增量动画/滚动锚定略逊于官方 |
| **P2** | 12 | **`permission.replied` 丢 `reply`；`question.replied` 丢 `answers`** | 【U】`v1/permission.ts:63`、`v1/question.ts:47-53`；【A】`ChatStore.kt:390-392, 401-403` | 无法回显"已允许/已拒绝"及用户当时的答案（体验损失，非功能阻断） |
| **P2** | 13 | **会话列表无 `limit`/`scope`/`search`；文件搜索无 `type`/`limit`** | 【A】`OpenCodeClient.kt:604-606, 680-692`；上游面【L】`research/04:209, 261`、【U】`V2_HTTP_API_AUDIT.md:141` | 大工作区下 Home 列表与 `@` 提及搜索的一次性开销量不可控 |
| **P2** | 14 | **无会话删除 / 分叉 / 回滚 / 单条消息与 part 的增删改** | 【U】`V2_HTTP_API_AUDIT.md:146,147,180-182`；A-2 #32,#33,#36,#37,#38 | 功能面缺口（不是解析缺口）：删会话、fork、revert、编辑 part 全部不可用 |
| **P2** | 15 | **`todo.updated` / `message.updated.v2` 分支在本 clone 的上游**无定义 | 全量 `type:"…"` 抽取无 `todo.*`；`packages/schema/src/session-todo.ts` 不存在；无 `message.updated.v2` 字符串；【A】`ChatStore.kt:339, 411-412` | 无法确认这两个分支是否真有线上来源；`ChatState.todos` 亦无 UI 消费者（`ChatStore.kt:78-82`）。**应实测确认后决定保留/删除** |
| **P3** | 16 | `patch`/`step-start`/`step-finish` part 未渲染 | 【U】`v1/session.ts:94-100, 233-257`；【A】`PartRenderer.kt:94-124` | **可接受**（官方亦是跳过，但该上游证据在本 clone 内 `未确认`，`SKIP_PARTS`/`event-reducer.ts` 均不存在） |
| **P3** | 17 | `file.edited` / `vcs.branch.updated` / `project.updated` / `provider.updated` / `model.updated` / `agent.updated` / `config.updated` / `pty.*` 未消费 | 【U】各文件对应行 | 相关面板（分支 chip、Home 项目、模型选择器、终端 tab、MCP）**不会自动刷新**，需手动重进页面 |
| **P3** | 18 | 无 `Last-Event-ID`；v1 面本无 seq 回放 | 【A】`EventStream.kt:76-92`；【L】`research/04:513-514` | v1 面固有；靠 `refreshSnapshot` 兜底可接受。切 v2 时必须改用 `/api/session/:id/history?after=<seq>` |
| **P3** | 19 | `GET /pty/shells` 未实现；`POST /pty` 未发 `args`/`env` | 【A】`PtyApi.kt:114-127`；【L】`research/04:523` | 终端无法列可用 shell、无法传参/环境变量（低） |
| **P3** | 20 | `GET /file/content` 的 `patch.{hunks[]}` 被压成字符串 | 【A】`OpenCodeClient.kt:576-580`；形状【L】`research/04:264` | 无法做逐 hunk 渲染（信息未丢，但结构丢失） |

---

## 6. 需要实测确认的验证清单（本次只读审计无法判定）

| # | 待验事实 | 方法 | 关联结论 |
|---|---|---|---|
| **V1** | 连到目标 v1 server 时，`/global/event` 的 durable 事件是否以 `sync` 包装下发？ | 抓一帧 `message.part.updated` / `session.updated` 的原始 SSE `data:` 文本，看是否存在 `payload.syncEvent` 且 `payload.type == "sync"` | P0#1、P0#3（决定这两条是"已失效"还是"防御性缺口"） |
| **V2** | `todo.updated` 与 `message.updated.v2` 是否真的出现过？ | 长时间抓取 `/global/event` 全量 type 集合 | P2#15 |
| **V3** | `/event`（实例级）与 `/global/event`（全局级）在目标 server 上是否都可用？ | 各发一次带 `?directory=` 与不带参的请求 | 现固定用 global（`ChatViewModel.kt:260`） |
| **V4** | `session.error` 的 `error` 实际是对象还是字符串？ | 触发一次失败回合，看 `properties.error` 的类型 | P1#6 |
| **V5** | 目标 server 是否真的支持 `GET /session`、`GET /permission`、`GET /question`、`GET /path`、`GET /project`、`GET /vcs`、`GET /find/file`、`GET /file/content`、`POST /session/:id/prompt_async` 这些 v1 路径？ | 逐条 `curl -i`（带 Basic） | **整张表 A**（本 clone 无上游路由定义，全部 `未确认`） |
| **V6** | `GET /session/:id/todo` 的响应元素字段 | `curl` 一次 | A-1 #20 |

---

## 7. 参考索引

**上游（本 clone 内，【U】）**
| 主题 | 路径 |
|---|---|
| v1 事件与数据模型 | `_upstream/packages/schema/src/v1/{session,permission,question,filesystem,legacy-event}.ts` |
| 当前事件清单入口 | `_upstream/packages/schema/src/event-manifest.ts` |
| 事件定义机制（`data` 信封） | `_upstream/packages/schema/src/event.ts:83-143` |
| 会话状态事件 | `_upstream/packages/schema/src/session-status-event.ts` |
| 当前权限 / 提问（v2 形状） | `_upstream/packages/schema/src/permission.ts`、`question.ts`、`form.ts` |
| v2 会话事件全集 | `_upstream/packages/schema/src/session-event.ts` |
| diff 元素 | `_upstream/packages/schema/src/file-diff.ts` |
| v2 端点清单（文档） | `_upstream/V2_HTTP_API_AUDIT.md` |
| v2 端点清单（e2e 声明） | `_upstream/packages/app/e2e/utils/mock-api.ts:36-281` |
| 前端取流 / 事件分发 | `_upstream/packages/app/src/runtime/server/client.tsx:24-120` |
| 前端鉴权约定 | `_upstream/packages/app/src/runtime/server/api.ts:5-34`、`health.ts:81-83` |

**Android（【A】）**
| 主题 | 路径 |
|---|---|
| REST 客户端（22 端点） | `app/src/main/java/com/xiufeigo/opencodeshell/OpenCodeClient.kt` |
| SSE 客户端（`/event`、`/global/event`） | `app/src/main/java/com/xiufeigo/opencodeshell/EventStream.kt` |
| 事件解析 / 归约 | `app/src/main/java/com/xiufeigo/opencodeshell/ChatStore.kt:334-460` |
| ViewModel 接线 | `app/src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt:252-295, 776-928` |
| part 渲染 | `app/src/main/java/com/xiufeigo/opencodeshell/ui/session/timeline/PartRenderer.kt:94-124` |
| 权限 / 提问 dock | `.../ui/session/dock/SessionPermissionDock.kt`、`SessionQuestionDock.kt` |
| 错误卡 | `.../ui/session/timeline/SessionErrorCard.kt` |
| PTY 客户端 | `.../ui/terminal/share/PtyApi.kt` |
| 服务器配置与凭据 | `app/src/main/java/com/xiufeigo/opencodeshell/ServerConfigStore.kt` |

**非上游辅助（【L】，仅作线索，不可作为上游证据）**
- `research/04_data_api.md`（指向 `anomalyco/opencode@dev`，其引用的 `packages/{protocol,server,core}`、`packages/app/src/context/global-sync/event-reducer.ts`、`server-session-v2-reducer.ts` **均不在本 clone 内**）
