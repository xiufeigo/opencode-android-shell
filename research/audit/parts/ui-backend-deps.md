# 官方 UI 对后端的硬依赖清单（origin/v2 基线）

- 取证日期：2026-10-04
- 权威基线：`origin/v2` = `ecfa884`（release v2.0.22）
- 对照旧基线：`702a73d`（2026-09-20）
- 取证方式：只读 git 对象（`git -C _upstream show / grep / ls-tree / log origin/v2:...`），**未改动 `_upstream` 工作树**（仍停在 `702a73d`）
- UI 侧代码范围：`packages/app/src` + `packages/gui-extensions/src`
- 路由侧代码范围：`packages/client/src/promise/generated/client.ts`（生成的类型化客户端，权威 path 映射）、`packages/protocol/src/groups/*.ts`（服务端 HttpApiGroup 定义）

## 口径说明（必读）

1. 「是否会话界面硬依赖」的判据：**去掉该项后，会话界面（时间线 + 输入框 + 请求坞）无法正常渲染或交互**。右侧栏/浏览器/终端/设置等一律标为「否（外围）」。
2. **本节所有 `file:line` 均为我在 `origin/v2` 实际 `git show` / `git grep` 观察到的行号。** 凡未亲自观察到的，标记 `未核对`，不做推测。
3. **关于 2.0.14 后端的行为，本文一律 `未核对`** —— 本轮不允许启动任何服务，未运行过 2.0.14 二进制。凡涉及 2.0.14 实际表现的结论均不给出。
4. `packages/gui-extensions` 在 `702a73d` **完全不存在**（`git -C _upstream ls-tree 702a73d -- packages/gui-extensions` → 空输出），由 `f406d93 refactor(app): move GUI features into built-in extensions (#52369)` 在本区间内新建。因此下文凡在 `gui-extensions` 下的依据，**一律是本基线新增**。

---

## A. HTTP / RPC 端点硬依赖

### A1. 会话界面硬依赖（load-bearing）

| 依赖项 | 类型 | origin/v2 依据（文件:行号 + 原文） | 是否会话界面硬依赖 | 说明 |
|---|---|---|---|---|
| `session.get` | `GET /api/session/{sessionID}` | `packages/client/src/promise/generated/client.ts:635` `path: \`/api/session/${encodeURIComponent(input.sessionID)}\`,`；UI 调用 `packages/app/src/session/requests/model.ts:49` `const session = data.session.get(sessionID) ?? (await serverSDK.api.session.get({ sessionID }))` | **是** | 会话标题/状态/元数据首屏来源。另有 7 处调用：`shell/routes/session-ui-provider.tsx:48`、`session/commands/export.ts:15`、`gui-extensions/src/context/export.ts:8`、`home/sessions/controller.tsx:289`、`shell/commands/palette.ts:148`、`shell/titlebar/titlebar.tsx:206`、`home/sessions/command-palette.tsx:33` |
| `session.list` | `GET /api/session` | `packages/client/src/promise/generated/client.ts:540` `path: \`/api/session\`,`；query 见 `:541-545` `limit / order / search / parentID` | **是** | 首页会话列表 + 命令面板。`packages/app/src/home/sessions/controller.tsx:63` `({ signal }) => loadHomeSessionIndex((input, options) => ctx.sdk.api.session.list(input, options), signal)` |
| `session.list` 游标分页 | 同上，`cursor` 往返 | `packages/app/src/session/list.ts:5` `const result = await api.list({ ...input, limit: input.limit ?? 100, cursor })`；`:7` `if (result.data.length === 0 || !result.cursor.next) return sessions`；`:8` `return [...sessions, ...(await load(result.cursor.next))]` | **是** | **契约关键：响应信封必须含 `result.cursor.next`**。该文件在 `702a73d..origin/v2` 区间**无提交**（UNCHANGED）→ 长期存在，非本基线新增 |
| `message.list` | `GET /api/session/{sessionID}/message?limit&order&cursor&type` | `packages/client/src/promise/generated/client.ts:1102` `path: \`/api/session/${encodeURIComponent(input.sessionID)}/message\`,`；`:1103` `query: { limit: input["limit"], order: input["order"], cursor: input["cursor"], type: input["type"] },`；`:1105` `declaredStatuses: [400, 401, 404, 500],` | **是** | 消息分页拉取。服务端声明 404/500，UI 需能容忍 |
| `message.list` `cursor.next` 循环 | 同上 | `packages/gui-extensions/src/context/export.ts:9` `input.api.message.list({ sessionID: input.sessionID, limit: 200, order: "asc" }),`；`:14` `while (pages.at(-1)?.cursor.next) {`；`:19` `cursor: pages.at(-1)!.cursor.next ?? undefined,` | **是** | 与 `session/list.ts` 同契约。**`order: "asc"` 为显式入参**，2.0.14 若不支持 `order` 取值将导致顺序错乱（未核对）。同名实现在 `packages/app/src/session/commands/export.ts:16,22` 重复一份 |
| 事件流（SSE） | `GET /api/event` | `packages/client/src/promise/generated/client.ts:1692` `{ method: "GET", path: \`/api/event\`, successStatus: 200, declaredStatuses: [400, 401], empty: false },` | **是** | 详见 §B。协议定义 `packages/protocol/src/groups/event.ts:42` `"server.event"`、`:44` `"event.subscribe"` |
| SSE 内容类型校验 | — | `packages/client/src/promise/generated/client.ts:366` `if (!isContentType(response, "text/event-stream"))` | **是** | **后端必须以 `text/event-stream` 返回 `/api/event`**，否则 UI 直接判定失败 |
| `server.info` | `GET /api/info` | `packages/client/src/promise/generated/client.ts:424` `{ method: "GET", path: \`/api/info\`, successStatus: 200, declaredStatuses: [400, 401], empty: false },` | **是** | 健康检查唯一入口。`packages/app/src/runtime/server/health.ts:104` `.server.info({ signal })`；另 `packages/app/src/composer/attachments/destination.ts:26` `const info = await sdk.api.server.info({ signal })` |
| `session.diff` | `GET /api/session/{sessionID}/diff` | `packages/client/src/promise/generated/client.ts:870` `path: \`/api/session/${encodeURIComponent(input.sessionID)}/diff\`,`；UI `packages/gui-extensions/src/review/model.ts:163` `? () => view().server.client.session.diff({ sessionID: view().id })`；`:339` `queryFn: () => view().server.client.session.diff({ sessionID: view().id, context: 3 }),` | **是** | Review 面板的会话内变更。**`context: 3` 上下文行数为显式入参**（未核对 2.0.14 是否支持） |
| `vcs.diff` | `GET /api/vcs/diff?location&mode&base&context` | `packages/client/src/promise/generated/client.ts:2111` `path: \`/api/vcs/diff\`,`；`:2112` `query: { location: input["location"], mode: input["mode"], base: input["base"], context: input["context"] },`；`:2114` `declaredStatuses: [400, 401, 404, 503],` | **是** | 未提交变更。UI `packages/gui-extensions/src/review/model.ts:182` `mode: "working"`（未提交工作区）、`:166`、`:360`；`packages/app/src/settings/workspaces/workspaces.tsx:215` `mode: "branch"`。**`mode` 取值域含 `working` / `branch`，且声明了 503**（后端可能因仓库过大而拒绝） |
| `file.read` | `GET /api/fs/read/{path}`（二进制） | `packages/client/src/promise/generated/client.ts:1598` `path: \`/api/fs/read/${encodePath(input.path)}\`,`；`:1603` `binary: true,`；服务端定义 `packages/protocol/src/groups/fs.ts:32` `HttpApiEndpoint.get("fs.read", "/api/fs/read/*", {`；UI `packages/app/src/runtime/server/image.ts:28` `const bytes = await api.file.read(` | **是** | 消息内嵌图片渲染。**注意路由为通配 `/api/fs/read/*`，且响应体是 `Schema.Uint8Array`（`fs.ts:34`）而非 JSON** |
| `file.list` | `GET /api/fs/list` | `packages/client/src/promise/generated/client.ts:1611` `path: \`/api/fs/list\`,`；服务端 `packages/protocol/src/groups/fs.ts:47` `HttpApiEndpoint.get("fs.list", "/api/fs/list", {`；UI `packages/app/src/workspaces/files/model.tsx:63` `serverSDK.api.file.list({ path: dir, location: { directory: scope() } })` | 是（文件面板/选目录） | 服务端语义 `fs.ts:57` `"List direct children using an absolute path or a path relative to the requested location, including parents and siblings outside its directory."` |
| `permission.request.list` | `GET /api/permission/request` | `packages/client/src/promise/generated/client.ts:1504` `path: \`/api/permission/request\`,`；UI `packages/app/src/session/requests/auto-approve.ts:62-63` `input.sdk.api.permission.request` / `.list({ location: { directory: location.directory } })` | **是** | 自动批准需先列出待处理权限请求 |
| `permission.reply` | `POST /api/session/{sessionID}/permission/{requestID}/reply` | `packages/client/src/promise/generated/client.ts:1584` `path: \`/api/session/${encodeURIComponent(input.sessionID)}/permission/${encodeURIComponent(input.requestID)}/reply\`,`；UI `packages/app/src/session/requests/model.ts:115` `serverSDK.api.permission` / `.reply({ sessionID: perm.sessionID, requestID: perm.id, decision: response })` | **是** | 权限坞应答。自动批准同形：`auto-approve.ts:117-118` `.reply({ sessionID: permission.sessionID, requestID: permission.id, decision: "once" })` |
| `session.interrupt` | `POST /api/session/{sessionID}/interrupt` | `packages/client/src/promise/generated/client.ts:980` `path: \`/api/session/${encodeURIComponent(input.sessionID)}/interrupt\`,`；UI `packages/app/src/session/revert.ts:47` `await server.api.session.interrupt({ sessionID }).catch(() => undefined)` | **是** | 停止生成 / 回滚前置 |
| `session.compact` | `POST /api/session/{sessionID}/compact` | `packages/client/src/promise/generated/client.ts:800` `path: \`/api/session/${encodeURIComponent(input.sessionID)}/compact\`,`；UI `packages/app/src/session/commands/use-session-commands.tsx:246` `await serverSDK.api.session.compact({ sessionID })` | 是（会话命令） | 压缩上下文 |
| `session.prompt` | `POST /api/session/{sessionID}/prompt` | `packages/client/src/promise/generated/client.ts:717` `path: \`/api/session/${encodeURIComponent(input.sessionID)}/prompt\`,` | **是** | 输入框提交。UI 侧调用点 `未核对`（本轮未逐一定位 `session.prompt` 的调用行） |
| `session.active` | `GET /api/session/active` | `packages/client/src/promise/generated/client.ts:624` `path: \`/api/session/active\`,`；UI `packages/app/src/session/requests/auto-approve.ts:82` `const active = await input.sdk.api.session.active().catch(() => undefined)` | 是（自动批准前置） | |
| `session.background` | `POST /api/session/{sessionID}/background` | `packages/client/src/promise/generated/client.ts:992` `path: \`/api/session/${encodeURIComponent(input.sessionID)}/background\`,`；UI `packages/app/src/session/requests/model.ts:90` `await serverSDK.api.session.background({ sessionID }).catch((error) => {` | 是（请求坞） | |
| `websearch.providers` | `GET /api/websearch/provider` | `packages/client/src/promise/generated/client.ts:2167` `path: \`/api/websearch/provider\`,`；UI `packages/app/src/session/requests/model.ts:50` `const result = await serverSDK.api.websearch.providers({` | 是（websearch 请求坞） | 对应 UI 文件 `session/requests/session-websearch-dock.tsx` |

### A2. 外围（非会话界面硬依赖）

| 依赖项 | 类型 | origin/v2 依据（文件:行号 + 原文） | 是否会话界面硬依赖 | 说明 |
|---|---|---|---|---|
| `session.command` | `POST /api/session/{sessionID}/command` | `generated/client.ts:738` `path: \`/api/session/${encodeURIComponent(input.sessionID)}/command\`,`；`packages/app/src/new-session/composer-adapter.ts:144` `command: (input) => afterCreation(() => serverSDK.api.session.command(input)),` | 否（外围） | 斜杠命令 |
| `session.shell` | `POST /api/session/{sessionID}/shell` | `generated/client.ts:788` `path: \`/api/session/${encodeURIComponent(input.sessionID)}/shell\`,`；`composer-adapter.ts:145` `shell: (input) => afterCreation(() => serverSDK.api.session.shell(input)),` | 否（外围） | |
| `session.switchAgent` | `POST /api/session/{sessionID}/agent` | `generated/client.ts:669` `path: \`/api/session/${encodeURIComponent(input.sessionID)}/agent\`,`；`composer-adapter.ts:146` `switchAgent: (input) => afterCreation(() => serverSDK.api.session.switchAgent(input)),` | 否（外围） | |
| `session.switchModel` | `POST /api/session/{sessionID}/model` | `generated/client.ts:681` `path: \`/api/session/${encodeURIComponent(input.sessionID)}/model\`,`；`composer-adapter.ts:147` `switchModel: (input) => afterCreation(() => serverSDK.api.session.switchModel(input)),` | 否（外围） | |
| `session.update`（PATCH） | `PATCH /api/session/{sessionID}` | `generated/client.ts:693` `path: \`/api/session/${encodeURIComponent(input.sessionID)}\`,`；`packages/app/src/shell/titlebar/tab-strip.tsx:118` `await ctx.sdk.api.session.update({ sessionID: value.id, title })` | 否（外围） | 标签页重命名 |
| `session.remove` | `DELETE /api/session/{sessionID}` | `generated/client.ts:646` `path: \`/api/session/${encodeURIComponent(input.sessionID)}\`,`；`packages/app/src/runtime/server/runtime.tsx:163` `remove: (sessionID) => sdk.api.session.remove({ sessionID }),` | 否（外围） | |
| `session.view` | `GET /api/session/{sessionID}/view` | `generated/client.ts:1088` `path: \`/api/session/${encodeURIComponent(input.sessionID)}/view\`,` | 未核对 | 本轮未定位 UI 调用点 |
| `session.context` | `GET /api/session/{sessionID}/context` | `generated/client.ts:859` `path: \`/api/session/${encodeURIComponent(input.sessionID)}/context\`,` | 否（外围） | 对应 Context 面板 |
| `session.inbox` | `GET/POST /api/session/{sessionID}/inbox` | `generated/client.ts:883` `path: \`/api/session/${encodeURIComponent(input.sessionID)}/inbox\`,` | 否（外围） | 事件 `session.inbox.*` 见 §B |
| `mcp.list` | `GET /api/mcp` | `generated/client.ts:1329` `path: \`/api/mcp\`,`；`packages/app/src/new-session/mcp.ts:62` `const catalog = await sdk.api.mcp.list({ location: { directory } })` | 否（外围） | 新建会话页 |
| `mcp.connect` / `mcp.disconnect` | `POST /api/mcp` | `generated/client.ts:1329`（同一 group）；`packages/app/src/runtime/server/sync.tsx:299` `await serverSDK.api.mcp.connect({ server: name, location: { directory: key } })` | 否（外围） | |
| `location.get` | `GET /api/location` | `generated/client.ts:449` `path: \`/api/location\`,`；`packages/app/src/runtime/server/global-sync/bootstrap.ts:152` `retry(() => input.api.location.get({ location: { directory: input.directory } }))` | 否（外围） | 全局 bootstrap 必需，非会话界面必需 |
| `vcs.status` | `GET /api/vcs/status` | `generated/client.ts:2085` `path: \`/api/vcs/status\`,`；`packages/app/src/settings/workspaces/workspaces.tsx:214` `context.sdk.api.vcs.status({ location: { directory: workspace.directory } }),` | 否（外围） | |
| `config.get` / `config.update` / `config.shells` | `GET /api/config` 等 | `generated/client.ts:2194` `path: \`/api/config\`,`、`:2206` `/api/config/shell`；`packages/app/src/settings/general/controllers.ts:35` `context.sdk.api.config.get().catch(() => []),` | 否（外围） | |
| `plugin.list` | `GET /api/plugin` | `generated/client.ts:500` `path: \`/api/plugin\`,`；`packages/app/src/settings/providers/extensions.tsx:51` `() => serverSdk.api.plugin.list().then((result) => result.data),` | 否（外围） | |
| `project.list` / `project.update` | `GET /api/project` | `generated/client.ts:1463` `{ method: "GET", path: \`/api/project\`, successStatus: 200, declaredStatuses: [400, 401, 404], empty: false },`；`packages/app/src/settings/workspaces/queries.ts:12` `queryFn: () => sdk.api.project.list(),` | 否（外围） | |
| `worktree.create` | `POST /api/worktree` | `generated/client.ts:2023` `path: \`/api/worktree\`,`；`packages/app/src/workspaces/create.ts:12` `const created = await input.api.worktree.create({` | 否（外围） | |
| `integration.get` | `GET /api/integration` | `generated/client.ts:1182` `path: \`/api/integration\`,`；`packages/app/src/providers/connect/mcp.ts:43` `const integration = await serverSDK.api.integration.get({ integrationID: current.integrationID, location: ref })` | 否（外围） | |
| `credential.remove` | `DELETE /api/credential` | `generated/client.ts:1411` `path: \`/api/credential\`,`；`packages/app/src/settings/providers/providers.tsx:218` `credentials.map((credential) => serverSdk.api.credential.remove({ credentialID: credential.id })),` | 否（外围） | |
| `pty` | `POST /api/pty` | `generated/client.ts:1701` / `:1713` `path: \`/api/pty\`,` | 否（外围） | 终端扩展 |
| `api.shell.output` | 路由 `未核对` | UI 调用点 `packages/app/src/shell/routes/session-ui-provider.tsx:79` `shellOutput={(input) => serverSDK.api.shell.output(input)}`；`generated/client.ts:1911` / `:1923` 存在 `path: \`/api/shell\`,` 但**本轮未逐一确认哪一条对应 `shell.output`** | 否（外围） | 路由映射标 `未核对` |

---

## B. 事件 / 流契约

**传输层**：`GET /api/event`，SSE。客户端订阅入口 `packages/client/src/solid/connection.ts:97` `const iterator = api.event.subscribe({ signal: request.signal, onActivity: touch })[Symbol.asyncIterator]()`；断线重连日志 `:162` `"event stream disconnected"`。

**UI 侧订阅点**：`packages/app/src/runtime/server/sync.tsx:227` `const unsub = serverSDK.event.listen((event) => {` —— 单一全局订阅，按 `event.location.directory` 分发到子 store（`:241-243`）。事件源封装 `packages/app/src/runtime/server/client.tsx:93-95` `onEvent(event) { events.publish(event) }`。

**事件 → UI 状态的落地映射**在 `packages/client/src/solid/data.ts`（`case "<name>":` 逐事件归约）。下表为会话界面渲染真正需要的事件：

| 事件名 | origin/v2 依据（文件:行号 + 原文） | 是否会话界面硬依赖 | 说明 |
|---|---|---|---|
| `session.step.started` | `packages/client/src/solid/data.ts:838` `case "session.step.started"` | **是** | 一轮 assistant 步骤开始 |
| `session.step.streamed` | `data.ts:873` `case "session.step.streamed"` | **是** | 流式内容 |
| `session.step.ended` | `data.ts:878` `case "session.step.ended"` | **是** | |
| `session.step.failed` | `data.ts:890` `case "session.step.failed"` | **是** | 另 UI 直接订阅 `packages/app/src/session/usage-exceeded-dialogs.tsx:70` `sdk().event.on("session.step.failed", (evt) => {` |
| `session.text.started` / `.delta` / `.ended` | `data.ts:904` / `:909` / `:914` `case "session.text.delta"` 等 | **是** | 文本 part 三段式 |
| `session.tool.input.started` / `.delta` / `.ended` | `data.ts:919` / `:930` / `:935` | **是** | 工具入参流；`data.ts:932` `if (tool.state.status === "streaming") tool.state.input += event.data.delta` |
| `session.tool.called` / `.progress` / `.success` / `.failed` | `data.ts:940` / `:948` / `:953` / `:967` | **是** | 工具生命周期四态 |
| `session.reasoning.started` / `.delta` / `.ended` | `data.ts:982` / `:992` / `:997` | **是** | 思考过程 part |
| `session.execution.started` / `.succeeded` / `.failed` / `.interrupted` | `data.ts:1009` / `:1025` / `:1026` / `:1027`（另 `:1033`、`:1039`、`:1041` 有二次判定） | **是** | 会话运行态，驱动停止/忙碌指示 |
| `session.retry.scheduled` | `data.ts:1004` `case "session.retry.scheduled"` | 是 | |
| `permission.asked` | `data.ts:1160` `case "permission.asked"`；UI 直接订阅 `packages/app/src/session/requests/auto-approve.ts:18` `input.sdk.event.on("permission.asked", (event) => {` | **是** | 权限坞出现的前提 |
| `permission.replied` | `data.ts:1167` `case "permission.replied"` | **是** | 坞消失 |
| `session.created` | `data.ts:631` `case "session.created"` | 是 | 另 UI 订阅 `packages/app/src/providers/models/selection.tsx:329` `serverSDK.event.on("session.deleted", (event) => {` |
| `session.deleted` | `data.ts:642` `case "session.deleted"` | 是 | |
| `session.renamed` | `data.ts:687` `case "session.renamed"` | 是 | 标题实时更新 |
| `session.status` | UI 订阅 `packages/app/src/session/usage-exceeded-dialogs.tsx:84` `sdk().event.on("session.status", (evt) => {` | 是（配额弹窗） | |
| `session.model.selected` / `session.agent.selected` | `data.ts:665` / `:652`；UI 订阅 `packages/app/src/providers/models/selection.tsx:327` / `:326` | 是（头部模型/Agent 显示） | |
| `session.compaction.started` / `.delta` / `.ended` / `.failed` | `data.ts:1012` / `:1093` / `:1099` / `:1133` | 是 | 压缩上下文进度 |
| `session.revert.staged` / `.cleared` / `.committed` | `data.ts:1068` / `:1072` / `:1076` | 是 | 回滚 UI |
| `session.shell.started` / `.ended` | `data.ts:815` / `:828` | 是 | |
| `session.moved` | `data.ts:704` `case "session.moved"` | 否（外围） | |
| `session.permissions` | `data.ts:700` `case "session.permissions"` | 否（外围） | |
| `session.usage.updated` | `data.ts:645` `case "session.usage.updated"` | 否（外围，Context 面板） | |
| `session.inbox.delivered` / `.enqueued` / `.cancelled` / `.delivery.changed` | `data.ts:749` / `:773` / `:768` / `:765`；UI 订阅 `packages/app/src/new-session/composer-adapter.ts:182` `event.on("session.inbox.enqueued"` | 否（外围） | |
| `session.instructions.updated` | `data.ts:791` `case "session.instructions.updated"` | 否（外围） | |
| `session.synthetic` | `data.ts:805` `case "session.synthetic"` | 否（外围） | |
| `session.viewed` | `data.ts:1063` `case "session.viewed"` | 否（外围） | |
| `form.replied` / `form.cancelled` | `data.ts:1170` / `:1171` | 否（外围） | |
| `server.connected` | `data.ts:595` `case "server.connected"` | 否（外围） | |
| `project.updated` | `data.ts:628` `case "project.updated"`；`sync.tsx:229` `if (event.type === "project.updated") applyProjectUpdate(event.data)` | 否（外围） | |
| `worktree.resolved` | `data.ts:727` `case "worktree.resolved"` | 否（外围） | |
| `config.updated` | `data.ts:1275` `case "config.updated"`；UI 订阅 `packages/app/src/settings/workspaces/project-extensions.tsx:133` `server.event.on("config.updated", (event) => {` | 否（外围） | |
| `filesystem.changed` | UI 订阅 `packages/app/src/workspaces/files/model.tsx:236` `const stop = sdk().event.on("filesystem.changed", (even...` | 否（外围，文件面板） | 该事件在 `data.ts` 中无 `case`，走独立订阅 |
| `mcp.status.changed` | `data.ts:1287` `case "mcp.status.changed"` | 否（外围） | |
| **question / 问题请求事件** | — | **`未核对`** | `git -C _upstream grep -n "question" origin/v2 -- packages/client/src/solid/data.ts` **无命中**；UI 侧存在 `packages/app/src/session/requests/session-question-dock.tsx`，但其事件来源与对应 `case` 本轮**未定位**。不排除它复用 `form.*` 或 `session.inbox.*` 通道 |

**事件契约总判**：`/api/event` 的 SSE 流 + 上述约 25 个 `session.*` / `permission.*` 事件名，是会话界面的**单一实时数据来源**。`data.ts` 在 `702a73d..origin/v2` 区间仅 1 个提交（`53179da feat(core): order projects by recent activity (#50790)`，与本表所列事件名无直接关系）→ **事件名集合长期稳定，非本基线新增**。

---

## C. 扩展 / 运行时（renderer ↔ host IPC）

扩展点定义集中在 `packages/gui-extensions/src/sdk/points.ts`。**这些点全部位于本基线新建的 `packages/gui-extensions` 包内（`702a73d` 无此包）→ 均为本基线新增**。

| 扩展点 / 契约 | origin/v2 依据（文件:行号 + 原文） | 是否会话界面硬依赖 | 说明 |
|---|---|---|---|
| `Panel` | `packages/gui-extensions/src/sdk/points.ts:637` `export const Panel = Point.define<Panel>("panel")` | **否（外围）** | 右侧栏/底部坞的全部来源。无 Panel 时侧栏为空，**但时间线与输入框仍完整可用** |
| `Panel.region` | `points.ts:239` `readonly region: "side" \| "dock"` | 否（外围） | |
| `Panel` 注册（`side` 区） | `browser/renderer.tsx:150` `region: "side",`；`btw/renderer.tsx:47`；`context/renderer.tsx:40`；`details/renderer.tsx:54`；`file/renderer.tsx:201`；`review/renderer.tsx:139` | 否（外围） | 6 个 side 标签 |
| `Panel` 注册（`dock` 区） | `terminal/renderer.tsx:146` `region: "dock",` | 否（外围） | |
| `Command` | `points.ts:612` `export const Command = Point.define<Command>("command")` | 否（外围） | |
| `MenuItem` | `points.ts:622` `export const MenuItem = Point.define<MenuItem>("menu-item")`；联合类型 `points.ts:127` `export type MenuItem = SessionPanelMenuItem \| ServerAddMenuItem \| ServerRowMenuItem` | 否（外围） | |
| `Slot` / `SlotMap` | `points.ts:687` `export const Slot = Point.define<Slot>("slot")`；`points.ts:550` `export interface SlotMap`；`points.ts:579` `export type Slot = {` | 否（外围） | |
| `Server` / `ServerEntry` | `points.ts:476` `export interface Server`；`points.ts:421` `export interface ServerEntry` | 否（外围） | 扩展贡献的服务器 |
| `Contract` 定义机制 | `packages/gui-extensions/src/sdk/core.ts:940` `* export const Changes = Contract.define<Changes, "review.changes">("review.changes")` | 否（外围） | 文档示例行 |
| **`review.changes` 契约** | `packages/gui-extensions/src/review/contract.ts:39` `export const Changes = Contract.define<Changes, "review.changes">("review.changes")`；契约面 `:7-36` `export interface Changes`，含 `:9` `diffs(session: SessionRef): readonly FileDiffInfo[]`、`:20` `details(session: SessionRef): readonly FileDiffInfo[] \| undefined`、`:33` `watch(screen: SessionScreen, source: "tree" \| "files" \| "details"): () => void` | **否（外围）** | **不是会话界面所需**。`git grep -rn "review.changes" origin/v2 -- packages/gui-extensions/src packages/app/src` 命中仅 2 处：定义处（`contract.ts:39`）与文档示例（`core.ts:940`），**无消费方注册**。它是 review 扩展对 details 扩展的**内部**契约，不跨进程 |
| `ServerState`（含 `incompatible`） | `points.ts:372` `export type ServerState = "stopped" \| "starting" \| "auth" \| "ready" \| "failed" \| "incompatible"` | 否（外围） | 见 §D |

**C 段结论**：**没有任何 `Contract` 是会话界面的硬依赖。** 扩展点（Panel / Command / MenuItem / Slot）全部只影响右侧栏、命令面板、菜单与坞区。即使后端 fork 移除了全部内建扩展的注册，会话时间线与输入框仍依赖 §A 的 HTTP 端点 + §B 的事件流。

---

## D. 版本下限（Version floor）

**这是本轮最重要的发现，且与任务预设的预期相反。**

| 检查项 | origin/v2 依据（文件:行号 + 原文） | 结论 |
|---|---|---|
| `incompatible-server-panel.tsx` 是否做版本比较 | `packages/app/src/session/incompatible-server-panel.tsx:19-22` `{server: serverName(server.conn),` / `version: server.health?.version ?? "1",` / `})} ` | **否**。该文件**仅把版本号渲染到文案里**，无任何比较、分支或阈值 |
| 健康检查是否比较版本 | `packages/app/src/runtime/server/health.ts:104-105` `.server.info({ signal })` / `.then((status) => ({ data: { healthy: true as const, version: status.version } }))` | **否**。`checkServerHealth`（`health.ts:78-113`）只**记录** `version`，从不与任何常量比较。类型 `ServerHealth`（`health.ts:8-14`）的 `incompatible?: boolean`（`:11`）在 HTTP 路径上**恒不置位** |
| `incompatible` 何时出现 | `packages/app/src/runtime/server/health.ts:161` `stage: conn.type === "extension" ? conn.state : undefined,`；`:171-172` `: conn.stage === "incompatible"` / `? { healthy: false, incompatible: true }` | **仅对 `type === "extension"` 的连接生效**（即 SSH 等由扩展贡献的服务器）。`sidecar` / 普通 HTTP 连接的 `stage` 恒为 `undefined`，**永远不会进入 `incompatible` 分支** |
| `incompatible` 的语义声明 | `packages/gui-extensions/src/sdk/points.ts:370` `* - \`incompatible\`: running a version this app does not support.` | 注释声称是版本不支持，但**产生该状态的实际判定并非版本比较**（见下条） |
| 实际判定逻辑 | `packages/gui-extensions/src/ssh/remote-cli.ts:19-23` `export function requireVersion(version: string) {` / `  if (version !== "local" && !/^[0-9][a-zA-Z0-9.+-]*$/.test(version))` / `    throw new Failure({ code: "version", detail: version })` / `  return version` | **这是字符串格式检查，不是 semver 下限比较。** 正则 `^[0-9][a-zA-Z0-9.+-]*$` 只要求「以数字开头且后续是合法版本字符」。`2.0.14` **能通过**；`2.0.9`、`2.0.1` 同样能通过 |
| 状态映射 | `packages/gui-extensions/src/ssh/controller.ts:225` `stage: code === "version" ? "incompatible" : "failed",` | SSH 连接失败时把 `code === "version"` 映射为 `incompatible` |
| 失败码取值域 | `packages/gui-extensions/src/ssh/remote-cli.ts:7` `code: Schema.Literals(["platform", "version", "install"]),` | 三个码，**无"版本过低"语义** |

### D 段结论

> **官方 UI 在 `origin/v2`（v2.0.22）中不存在任何显式的服务器版本兼容性检查，也没有任何硬编码的最低服务器版本常量。**

取证范围与反证：

1. `git -C _upstream grep -n -E "MINIMUM_SERVER|MIN_SERVER|minimum.*version|serverVersion|requiredVersion|minVersion|SUPPORTED_" origin/v2 -- packages/app/src packages/gui-extensions/src` → **仅命中 i18n 文案**，无常量。
2. 对 `app` / `gui-extensions` / `desktop` / `cli` / `server` / `client` / `sdk` / `tui` / `web` / `session-ui` / `core` 逐包 grep 硬编码版本字面量 `"2.x.y"` → **零命中**。
3. `git grep -n -E "semver|compare.*version|satisfies\(" origin/v2 -- packages/app/src packages/gui-extensions/src` → **零命中**。
4. 唯一名为「版本兼容」的状态 `incompatible`，其唯一产生路径是 SSH 远端 CLI 的**版本字符串格式**校验（`remote-cli.ts:19-23`），且只作用于扩展型连接。

### D 段的基线新颖度

| 项 | 判定 | 依据 |
|---|---|---|
| `packages/app/src/session/incompatible-server-panel.tsx` | **长期存在，本区间无改动** | `git -C _upstream ls-tree 702a73d -- packages/app/src/session/incompatible-server-panel.tsx` → `100644 blob 7dbfc7fd5b8c50831949451c0f85b44e29ca8478`（旧基线已存在）；`git log --oneline 702a73d..origin/v2 -- <该文件>` → **无输出** |
| `packages/app/src/runtime/server/health.ts` | 本区间有 2 个提交 | `f406d93 refactor(app): move GUI features into built-in extensions (#52369)`、`2caba90 feat(app): replace password pairing with one-time links (#50972)` |
| `packages/gui-extensions/src/ssh/remote-cli.ts` | **本基线新增文件** | `packages/gui-extensions` 包在 `702a73d` 不存在；该文件随 `f406d93` 迁入 |
| 其余基线新颖度 | 见下表 | |

### 全部依据的基线新颖度一览

| 文件 | `702a73d..origin/v2` 状态 |
|---|---|
| `packages/gui-extensions/**`（全包，含 `sdk/points.ts`、`review/*`、`context/export.ts`、`ssh/*`） | **本基线新增**（`702a73d` 无此包；由 `f406d93` 引入）。`points.ts` 区间内有 6 个提交（`5589ca4`、`0048c97`、`56145fc`、`f406d93` 等）；`review/model.ts` 6 个；`context/export.ts` 1 个（`0048c97 feat(gui-extensions): add typed composition and lifetime primitives (#52868)`） |
| `packages/app/src/session/list.ts` | **UNCHANGED**（区间内无提交） |
| `packages/app/src/session/incompatible-server-panel.tsx` | **UNCHANGED**（旧基线已存在，区间内无提交） |
| `packages/app/src/runtime/server/health.ts` | CHANGED（2 个提交） |
| `packages/client/src/promise/generated/client.ts` | CHANGED（10 个提交，含 `3e1aa1e fix(server): return 404 when a location folder is missing (#52668)`、`41516c7`、`19f2038 feat(plugin): support parent session creation (#52359)`） |
| `packages/client/src/solid/data.ts` | CHANGED（1 个提交 `53179da`，与本表所列事件名无直接关系） |

---

## 对 v2 后端 2.0.14 的判定建议

> **前置声明**：本轮**未启动任何服务、未运行 2.0.14 二进制**。下表中凡涉及 2.0.14 实际行为的判断，一律为 `未核对`。「已核对」仅表示**该依赖在 `origin/v2` UI 侧的契约已取证**，不表示 2.0.14 满足它。

### 硬依赖逐条

| 硬依赖 | UI 侧契约 | 对 2.0.14 的判定 | 需运行期验证的具体内容 |
|---|---|---|---|
| `GET /api/session/{id}` | `已核对` | `未核对` | 2.0.14 是否存在该路由，且响应体形状与 `SessionGetOutput` 一致 |
| `GET /api/session` + `cursor.next` 信封 | `已核对`（`session/list.ts` UNCHANGED，契约长期稳定） | `未核对` | **重点**：2.0.14 的 `/api/session` 响应是否含 `{ data, cursor: { next } }` 信封。若返回裸数组，`listAllSessions` 的 `result.cursor.next` 会抛错，会话列表**整体不可用** |
| `GET /api/session/{id}/message` + `cursor.next` | `已核对` | `未核对` | 同上信封问题；另需确认是否支持 `order=asc` 与 `limit` |
| `GET /api/event`（SSE） | `已核对` | `未核对` | **最高优先级**：2.0.14 是否暴露 `/api/event`，是否以 `text/event-stream` 返回（`generated/client.ts:366` 会硬校验），是否支持长连接与断线重连 |
| §B 的约 25 个事件名 | `已核对`（事件名集合长期稳定，`data.ts` 区间内仅 1 个无关提交） | `未核对` | 2.0.14 是否发布全部事件名。**缺失任一 `session.text.*` / `session.tool.*` / `permission.asked` 即导致时间线不渲染或权限坞不出现** |
| `GET /api/info` | `已核对` | `未核对` | 2.0.14 是否提供 `/api/info`；缺失则健康检查失败（`health.ts:104`） |
| `GET /api/session/{id}/diff` | `已核对` | `未核对` | 是否支持 `context` 参数（UI 传 `context: 3`） |
| `GET /api/vcs/diff`（`mode=working` / `branch`） | `已核对` | `未核对` | `mode` 取值域是否含 `working`。**缺失则右侧 review 面板与「未提交变更」整体失效**；另注意 UI 容忍 503 |
| `GET /api/fs/read/*`（二进制） | `已核对` | `未核对` | 路径是否仍为通配 `/api/fs/read/*`；响应体是否仍为原始 `Uint8Array` 而非 JSON/base64 |
| `GET /api/fs/list` | `已核对` | `未核对` | |
| `GET /api/permission/request` + `POST .../permission/{id}/reply` | `已核对` | `未核对` | `reply` 入参 `decision` 的取值域（UI 用 `"once"` 与 `response`）。**若 2.0.14 的 decision 枚举不同，权限坞无法应答 → 会话卡死** |
| `POST /api/session/{id}/interrupt` | `已核对` | `未核对` | |
| `POST /api/session/{id}/compact` | `已核对` | `未核对` | |
| `POST /api/session/{id}/prompt` | `未核对`（路由已核对，UI 调用行未定位） | `未核对` | 请求体 schema（未在本文逐字段取证） |
| `GET /api/session/active`、`POST .../background`、`GET /api/websearch/provider` | `已核对` | `未核对` | |

### 版本下限判定（对 §D 的直接回答）

| 问题 | 答案 |
|---|---|
| 官方 UI 要求的最低服务器版本是多少？ | **不存在这个数字。** UI 在 `origin/v2` 未硬编码任何最低版本常量（§D 反证 1–3）。唯一名为 `incompatible` 的状态由 `remote-cli.ts:19-23` 的**版本字符串格式正则**产生，不是 semver 下限 |
| 2.0.14 会被 UI 判为 `incompatible` 吗？ | 对**普通 HTTP / sidecar 连接：不会** —— `health.ts:161` 使这类连接的 `stage` 恒为 `undefined`，永不进入 `incompatible` 分支。对 **SSH 扩展连接：不会** —— `2.0.14` 能通过正则 `^[0-9][a-zA-Z0-9.+-]*$` |
| 既然没有版本闸门，2.0.14 能否承载官方 UI？ | **版本号本身不构成阻碍；真正的阻碍是 §A/§B 的路由与事件契约漂移。** 2.0.14 与 2.0.22 之间跨 526 个提交（`packages/client/src/promise/generated/client.ts` 在此区间有 10 个提交），路由与事件集可能已发生实质变化 |
| 建议的最小验证动作 | 启动 2.0.14 后依次 curl：① `GET /api/info` ② `GET /api/session`（**检查是否含 `cursor.next` 信封**）③ `GET /api/session/{id}/message?limit=200&order=asc`（同上）④ `curl -N -H 'Accept: text/event-stream' /api/event`（**检查 Content-Type 与是否推送 `session.text.delta` / `permission.asked`**）⑤ `GET /api/vcs/diff?directory=…&mode=working` ⑥ `GET /api/fs/read/<file>` |
| 本轮未做 | 上述 6 项 curl **全部未执行**（本轮禁止启动服务）。故 2.0.14 的实际行为**全部为 `未核对`** |

---

## 未核对清单（本轮明确未取证，勿据此下结论）

1. `session.prompt`（`generated/client.ts:717`）在 UI 侧的**具体调用行**与请求体 schema。
2. `api.shell.output` 对应的**确切路由**（`generated/client.ts:1911` / `:1923` 未逐一确认）。
3. `session.view`（`generated/client.ts:1088`）的 UI 调用点。
4. **question / 问题请求的事件来源** —— `session-question-dock.tsx` 存在，但 `data.ts` 中无 `question` 相关 `case`，其数据通路未定位。
5. §A 各端点的**请求体/响应体完整 schema**（本文只取证了路由 path、query 参数名与部分 success 形状）。
6. **2.0.14 后端的任何实际行为** —— 未启动、未运行。
7. `packages/server/src` 侧的路由**实现**（本文路由依据取自 `packages/protocol/src/groups/*.ts` 的 `HttpApiEndpoint` 定义与生成客户端，未逐一核对 `server` 包的 handler 实现）。
