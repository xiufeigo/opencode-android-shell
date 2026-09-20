# opencode 前端数据层调研（SDK / HTTP / SSE / WebSocket / Part 模型）

> 目标仓库：`anomalyco/opencode`（默认分支 `dev`，即上游 opencode）。
> 方法：`git` blobless shallow clone 到本地后**逐文件读源码**（`packages/protocol`、`packages/schema`、
> `packages/opencode/src/server`、`packages/client/src/generated`、`packages/app/src/context`），
> 并与本地 vendored bundle `app/src/main/assets/opencode-app/assets/index-CKBO9Mty.js` 交叉验证。
> 所有结论后附 repo 相对路径。标 `未确认` 的是源码中未能证实的内容。
>
> 本文件面向「Android 原生对接」，因此同时覆盖 **两套并存的 API**：
> - **v1 / legacy「instance API」**：无前缀路径（`/session`、`/event`、`/file`…），返回 `parts` 数组模型。
> - **v2「server HttpApi」**：统一 `/api/*` 前缀，返回 `SessionMessage`（`type` 判别联合）。

---

## 0. 坐标系：三套东西，别混淆

| 名称 | 前缀 | 源码位置 | 数据模型 | 本地 Kotlin 现状 |
|---|---|---|---|---|
| v1 instance API | 无（`/session`…） | `packages/opencode/src/server/routes/instance/httpapi/groups/*.ts` | `SessionV1.WithParts = {info, parts[]}` | **已在用**（`OpenCodeClient.kt`） |
| v1 SSE | `/event`、`/global/event` | `.../httpapi/handlers/event.ts`、`groups/global.ts` | 信封 `{id,type,properties}` | **已在用**（`EventStream.kt`） |
| v2 server HttpApi | `/api/*` | `packages/protocol/src/groups/*.ts` → `packages/server/src/api.ts` | `SessionMessage.Message` | 未使用 |

路由组装（`packages/opencode/src/server/routes/instance/httpapi/server.ts:130-203`）：

```
rootApiRoutes   = RootHttpApi(Control + ControlPlane + Global)   // /global/*, /auth/*, /log
eventApiRoutes  = EventApi        // GET /event
ptyConnectApiRoutes = PtyConnectApi  // GET /pty/:ptyID/connect (WebSocket upgrade + ticket 鉴权)
instanceApiRoutes   = InstanceHttpApi // 其余全部 v1 无前缀路由
serverRoutes    = Api             // 全部 v2 /api/* 路由
GET /doc        // PublicApi 的 OpenAPI JSON（懒生成并缓存）
* /*            // UI 静态 catch-all（serveUIEffect）
```

关键现实结论：**这个 opencode 是「一次构建里同时挂了 v1 与 v2 两套 HTTP 面」**，
vendored bundle 也确实同时引用了两套路径（见 §3.3）。

---

## 1. 鉴权与通用请求约定

### 1.1 鉴权
`packages/opencode/src/server/routes/instance/httpapi/middleware/authorization.ts`
- `Authorization: Basic base64("<user>:<pass>")`，或 query `auth_token=<base64(user:pass)>`。
- 仅在 `ServerAuth.required(config)` 为真时校验；失败 → `401` + `www-authenticate: Basic realm="Secure Area"`。
- **例外 1**：`/pty/:ptyID/connect?ticket=...` 带 ticket 时**跳过 Basic**，由 handler 自己校验 ticket。
- **例外 2**：`/doc` 与 UI catch-all 使用 router 级中间件，公开静态资源可绕过。
- v2 面（`packages/protocol/src/middleware/authorization.ts`）同样挂 `Authorization`。

> Android 现有 `OpenCodeClient` / `EventStream` 用 Basic 是正确且足够的。

### 1.2 通用 query 参数
带 `WorkspaceRoutingMiddleware` 的 group（session / file / config / mcp / project / pty / question /
permission / provider / sync / tui / workspace / experimental / instance）都接受：
- `directory?: string`（绝对路径）
- `workspace?: string`

`packages/opencode/src/server/routes/instance/httpapi/middleware/workspace-routing.ts`
- 解析优先级：`?directory` → header `x-opencode-directory` → `process.cwd()`。
- workspace 命中远端时请求会被代理到远端；非法 workspace → `400 {message, kind:"Query", field:"workspace"}`。
- root 组（`/global/*`、`/auth/*`、`/log`）**不接受**这两个参数。

v2 面的 location 参数形如 `location[directory]`、`location[workspace]`
（`packages/protocol/src/groups/location.ts`、`packages/protocol/src/groups/pty.ts:133`）。

### 1.3 通用错误形状
`400 {name:"BadRequest", data:{message, kind?}}`、`404 {name, data:{message}}`
（`.../httpapi/errors.ts`）。

---

## 2. SSE 事件协议（**这是对接最关键的一节**）

### 2.1 `/event`（v1，Android 当前在用）——信封是 `{id, type, properties}`
`packages/opencode/src/server/routes/instance/httpapi/handlers/event.ts:25-86`

```ts
Stream.map((event) => ({ id: event.id, type: event.type, properties: event.data }))
// 首帧：{ id: evt_xxx, type: "server.connected", properties: {} }
// 心跳：Stream.tick("10 seconds") -> { id: evt_xxx, type: "server.heartbeat", properties: {} }
```

**实证**：`packages/opencode/src/event-v2-bridge.ts:39-60` 把事件从 `EventV2` 桥接进 GlobalBus 时，
payload 写成 `{ id, type, properties: event.data }`。所以：
- 事件字段在 **`properties`** 下，不是 `data`。`ChatStore.parseEvent()` 的 `data.optJSONObject("properties")` 判断**正确**。
- SSE 帧是标准 `event: message` + `data: <JSON>`（`eventData()`，`handlers/event.ts:12-19`），
  即 **`type` 在 JSON body 里**，不在 SSE 的 `event:` 行。`EventStream.kt` 优先用 JSON 的 `type` 是对的。
- 心跳**不是注释行**，而是一条真实的 `server.heartbeat` 事件（10s 一次）。Android 侧忽略未知 type 即可，
  但仍可用作「连接存活」信号（比 `:` 注释更可靠）。
- HTTP 头：`Cache-Control: no-cache, no-transform`、`X-Accel-Buffering: no`、`X-Content-Type-Options: nosniff`；
  `Content-Type: text/event-stream`。

> ⚠️ **`/event` 是「实例级」流，必须带 `?directory=`**（`handlers/event.ts:35-39`：
> `event.location?.directory === instance.directory` 过滤）。同一个 server 进程会服务多个
> directory 实例，**不传 `directory` 就会订阅到 `process.cwd()` 那个实例**，很可能收不到目标会话的事件。
> Android 侧务必把当前服务器的 `directory`（工作区路径）作为 query 传进去。

**官方前端的 SSE 客户端实现**（`packages/app/src/context/server-sdk.tsx:260-317` + 生成式 SDK 的 SSE 层）：
- **不用 `EventSource`**（bundle 里 0 命中），而是 `fetch()` + `response.body.getReader()` + `TextDecoder`
  流式解码，`\r\n`/`\r` 归一后按 `\n\n` 切块，取 `data:` 行（`startsWith("data:")` 则 `slice(5).trimStart()`）。
- 带 `Last-Event-ID` 请求头做断点续传；默认重连延迟 `sseDefaultRetryDelay = 3000` ms，可配置 `sseMaxRetryAttempts`。
- 应用层还有一层外层重连循环 + `pagehide`/`pageshow` 处理 + WebView 下 3s 定时器兜底判定"已连接"。
- **取流入口**：v1 → `GET /global/event`；v2 → `GET /api/event`（`server-sdk.tsx:276-279`）。
- 协议探测 `MT()`：`/global/health` 返回 `healthy === true` → 判为 v1；本地 host 且 `port === "0"` → v2；
  都失败默认 v2。（有报告称「`/api/health` 返回数字 `pid` → v2」，但源码
  `packages/protocol/src/groups/health.ts` 返回的是 `{healthy:true}`，该谓词 `未确认`。）

### 2.2 `/global/event`（v1 全局流）
`packages/opencode/src/server/routes/instance/httpapi/groups/global.ts:36-49`

```ts
{ directory: string, project?: string, workspace?: string,
  payload: {id, type, properties}          // 普通事件
         | {id, type:"server.instance.disposed", properties:{directory}}
         | {type:"sync", id, syncEvent:{ type:"<type>.<version>", id, seq, aggregateID, data }} }
```

注意：全局流多一层 `directory/project/workspace` 路由信封，且 durable 事件用 `sync` 包装
（type 被改写成 `versionedType(type, version)`，如 `session.next.text.delta.1`）。

### 2.3 `/api/event`（v2）
`packages/protocol/src/groups/event.ts:29-47`
- `GET /api/event` → `StreamSse({ data: EventSchema })`；**信封是 `{id, type, data, durable?, location?, metadata?}`**，
  由 `packages/schema/src/event.ts:29-70` 的 `define()` 决定。
- 即 v2 用 `data` 字段，**没有 `properties`**；与 v1 不兼容。
- `/api/session/:sessionID/event?after=<seq>` 是**会话级** SSE，先回放 `after` 之后的 durable 事件再续推
  （`packages/protocol/src/groups/session.ts:326-343`）。
- `GET /api/session/:sessionID/history?limit=<1..100>&after=<seq>` 返回
  `{data: SessionEvent.Durable[], hasMore: boolean}` —— 断线补偿用**分页历史**而非 only-SSE。

### 2.4 完整事件类型清单

#### A. v1 常用（Android 主要处理这些）
| type | properties 字段 | 源码 |
|---|---|---|
| `server.connected` | `{}` | `handlers/event.ts:70` |
| `server.heartbeat` | `{}` | `handlers/event.ts:65` |
| `server.instance.disposed` | `{directory}` | `packages/opencode/src/server/event.ts` |
| `session.created` / `session.updated` / `session.deleted` | `{sessionID, info: SessionInfo}` | `packages/schema/src/v1/session.ts:571-595` |
| `message.updated` | `{sessionID, info: Message}`（**无 parts**） | 同上 `:596-603` |
| `message.removed` | `{sessionID, messageID}` | 同上 `:604-611` |
| `message.part.updated` | `{sessionID, part: Part, time: number}` | 同上 `:612-620` |
| `message.part.removed` | `{sessionID, messageID, partID}` | 同上 `:621-629` |
| `message.part.delta` | `{sessionID, messageID, partID, field, delta}` | 同上 `:632-641`（**无 durable**） |
| `session.diff` | `{sessionID, diff: FileDiff.Info[]}` | 同上 `:643-649` |
| `session.error` | `{sessionID?, error}` | 同上 `:651-657` |
| `session.status` | `{sessionID, status: {type:"idle"}\|{type:"retry",attempt,message,action?,next}\|{type:"busy"}}` | `packages/schema/src/session-status-event.ts:35-41` |
| `session.idle` | `{sessionID}`（**已标注 deprecated**） | 同上 `:44-49` |
| `permission.asked` | `{id, sessionID, permission, patterns[], metadata, always[], tool?}` | `packages/schema/src/v1/permission.ts:61` |
| `permission.replied` | `{sessionID, requestID, reply:"once"\|"always"\|"reject"}` | 同上 `:62-65` |
| `question.asked` / `question.replied` / `question.rejected` | `{id,sessionID,questions[],tool?}` / `{sessionID,requestID,answers:string[][]}` / `{sessionID,requestID}` | `packages/schema/src/v1/question.ts:58-60` |
| `todo.updated` | `{sessionID, todos: {content, status, priority}[]}` | `packages/schema/src/session-todo.ts` |
| `pty.created` / `pty.updated` | `{info: Pty.Info}` | `packages/schema/src/pty.ts:34-37` |
| `pty.exited` | `{id, exitCode}` | 同上 |
| `pty.deleted` | `{id}` | 同上 |
| `vcs.branch.updated` | `{branch?}` | `packages/schema/src/vcs-event.ts` |
| `lsp.updated` | （触发重新拉 `/lsp`） | `packages/schema/src/lsp-event.ts` |
| `reference.updated` | （触发重新拉 `/experimental/resource`） | `packages/schema/src/reference.ts` |
| `file.watcher.updated` | 文件系统变更 | `packages/schema/src/filesystem-watcher.ts` |
| `file.edited` | 文件被编辑 | `packages/schema/src/filesystem.ts` |
| `project.updated` | `Project` | `packages/schema/src/project.ts` |
| `project.directories.updated` | — | `packages/schema/src/project-directories.ts` |
| `catalog.updated` / `installation.updated` / `integration.updated` / `integration.connection.updated` / `plugin.added` | — | 各自 schema 文件 |
| `mcp.tools.changed` / `mcp.browser.open.failed` | — | `packages/schema/src/mcp-event.ts` |
| `session.compacted` | — | `packages/schema/src/session-compaction-event.ts` |
| `global.disposed` | — | `packages/schema/src/server-event.ts` |
| `tui.command.execute` / `tui.prompt.append` / `tui.session.select` / `tui.toast.show` | — | `packages/schema/src/tui-event.ts` |
| `workspace.ready` / `workspace.failed` / `workspace.status` | — | `packages/schema/src/workspace-event.ts` |
| `worktree.ready` / `worktree.failed` | — | `packages/schema/src/worktree-event.ts` |

> 全套枚举来源：对 `packages/{schema,core,opencode}/src/**/*.ts` 全量抽取 `type: "…"` 字面量
> （378 个候选文件），再按前缀分组。`packages/schema/src/event-manifest.ts` 是权威清单入口。

#### B. v2 durable（`/api/*` 面，`session.next.*`）
`packages/schema/src/session-event.ts:448-512`（durable 会带 `{aggregateID, seq, version}`，aggregate = `sessionID`）：

- 生命周期：`session.next.agent.switched`、`session.next.model.switched`、`session.next.moved`、
  `session.next.synthetic`、`session.next.context.updated`
- 输入：`session.next.prompted`、`session.next.prompt.admitted`
- shell：`session.next.shell.started`、`session.next.shell.ended`
- step：`session.next.step.started`、`session.next.step.ended`、`session.next.step.failed`
- 文本：`session.next.text.started`、`session.next.text.delta`（**live-only，不 durable**）、`session.next.text.ended`
- 推理：`session.next.reasoning.started` / `.delta`（live-only） / `.ended`
- 工具：`session.next.tool.input.started` / `.delta`（live-only） / `.ended`、
  `session.next.tool.called`、`session.next.tool.progress`、`session.next.tool.success`、`session.next.tool.failed`
- 其他：`session.next.retried`、`session.next.compaction.started/.delta/.ended`、
  `session.next.revert.staged/.cleared/.committed`
- 另有 `permission.v2.asked` / `permission.v2.replied`（`packages/schema/src/permission.ts:43-52`）、
  `question.v2.asked/.replied/.rejected`（`packages/schema/src/question.ts:70-86`）

> ⚠️ **版本偏移警告**：仓库中 `packages/app/src/context/server-session-v2-reducer.ts` 处理的是
> **不带 `.next.`** 的名字（`session.text.delta`、`session.tool.called`、`session.input.admitted`、
> `session.step.started`…），vendored bundle 里也能检索到这些字符串（`session.text.delta` ×2、
> `session.tool.called` ×1、`session.input.admitted` ×1），但当前 `dev` 源码中**只有** `session.next.*`。
> 说明 bundle / app reducer 与当前 server schema 之间存在版本差。**Android 侧应以「连接到的
> server 实际发出的 type 字符串」为准，且对两种命名都做兼容容错**，不要硬编码单一命名。

---

## 3. REST 端点清单

### 3.1 v1（无前缀）— Android 当前可用的主面

来源：`packages/opencode/src/server/routes/instance/httpapi/groups/*.ts`（除非另注，均接受 `?directory=`、`?workspace=`）。

**Session**（`groups/session.ts`，`SessionPaths` 见 `:78-105`）
| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/session` | query `scope?="project"`、`path?`、`roots?`、`start?`、`search?`、`limit?` → `Session.Info[]` |
| GET | `/session/status` | → `Record<sessionID, Status>` |
| GET | `/session/:sessionID` | → `Session.Info` |
| GET | `/session/:sessionID/children` | → `Session.Info[]` |
| GET | `/session/:sessionID/todo` | → `Todo.Info[]` |
| GET | `/session/:sessionID/diff?messageID?` | → `Snapshot.FileDiff[]`（OpenAPI 已确认仅 `messageID` 一个业务 query）。元素 = `{file?, patch?, additions, deletions, status?:"added"\|"deleted"\|"modified"}`（`packages/schema/src/file-diff.ts`，identifier 即 `SnapshotFileDiff`） |
| GET | `/session/:sessionID/message?limit?(int≥0)&before?` | → `SessionV1.WithParts[]` = `{info, parts[]}` |
| GET | `/session/:sessionID/message/:messageID` | → `WithParts` |
| POST | `/session` | payload `{parentID?, title?, agent?, model?, metadata?, permission?:Ruleset, workspaceID?}`（整体可空）→ `Session.Info` |
| PATCH | `/session/:sessionID` | `{title?, metadata?, permission?, time?:{archived?:number}}` → `Session.Info` |
| DELETE | `/session/:sessionID` | → `boolean` |
| POST | `/session/:sessionID/fork` | `{messageID?}` → `Session.Info` |
| POST | `/session/:sessionID/abort` | 无 body → `boolean` |
| POST | `/session/:sessionID/init` | `{providerID, modelID, messageID}` → `boolean` |
| POST | `/session/:sessionID/summarize` | `{providerID, modelID, auto?}` → `boolean` |
| POST | `/session/:sessionID/share` / DELETE 同路径 | → `Session.Info` |
| POST | `/session/:sessionID/message` | **PromptPayload** → `WithParts`（阻塞到有回复） |
| POST | `/session/:sessionID/prompt_async` | **PromptPayload** → **HTTP 204 NoContent**（立即返回） |
| POST | `/session/:sessionID/command` | CommandPayload → `WithParts` |
| POST | `/session/:sessionID/shell` | ShellPayload → `WithParts` |
| POST | `/session/:sessionID/revert` / `/unrevert` | → `Session.Info` |
| POST | `/session/:sessionID/permissions/:permissionID` | `{response:"once"\|"always"\|"reject"}` → `boolean`（**deprecated**） |
| DELETE | `/session/:sessionID/message/:messageID` | → `boolean` |
| PATCH / DELETE | `/session/:sessionID/message/:messageID/part/:partID` | payload/返回 `SessionV1.Part` / `boolean` |

**PromptPayload 精确定义**（`groups/session.ts:70` = `Struct.omit(SessionPrompt.PromptInput.fields, ["sessionID"])`；
原始定义 `packages/opencode/src/session/prompt.ts:1499`）：

```jsonc
{
  "messageID": "msg_…?",                       // 可选，客户端自定义消息 ID（幂等重试）
  "model": { "providerID": "…", "modelID": "…" }, // 可选
  "agent": "build",                            // 可选
  "noReply": false,                            // 可选
  "tools": { "bash": true },                   // 可选，@deprecated
  "format": { "type": "text" } | { "type": "json_schema", "schema": {...}, "retryCount": 2 },
  "system": "…",                               // 可选
  "variant": "…",                              // 可选（模型变体，如 thinking 档位）
  "parts": [ … ]                               // **必填**
}
```

`parts` 元素（`packages/schema/src/v1/session.ts:397-451`）：
- `text`：`{id?:PartID, type:"text", text:string, synthetic?:bool, ignored?:bool, time?:{start:int,end?:int}, metadata?:Record<string,any>}`
- `file`：`{id?, type:"file", mime:string, filename?:string, url:string, source?:FilePartSource}` ← **注意是 `url`（data URL 或 `file://`）**
- `agent`：`{id?, type:"agent", name:string, source?:{value,start,end}}`
- `subtask`：`{id?, type:"subtask", prompt, description, agent, model?:{providerID,modelID}, command?}`

> 现有 `OpenCodeClient.promptAsync()` 发的 `{parts:[{type:"text",text},{type:"file",mime,filename,url}]}` 与之完全一致 ✅。

**File / Find**（`groups/file.ts`）
- `GET /find?pattern=` → ripgrep 结果 `{path:{text}, lines:{text}, line_number, absolute_offset, submatches[]}[]`
- `GET /find/file?query=&dirs?="true"|"false"&type?="file"|"directory"&limit?(1..200)` → **`string[]`**（Android 已兼容）
- `GET /find/symbol?query=` → `LSP.Symbol[]`
- `GET /file?path=` → `{name,path,absolute,type:"file"|"directory",ignored:boolean}[]`
- `GET /file/content?path=` → `{type:"text"|"binary", content, diff?, patch?:{hunks[]}, encoding?:"base64", mimeType?}`
- `GET /file/status` → `{path,added,removed,status}[]`

**Instance**（`groups/instance.ts`）
- `POST /instance/dispose`、`GET /path` → `{home,state,config,worktree,directory}`
- `GET /vcs` → `{branch?, default_branch?}`、`GET /vcs/status`、`GET /vcs/diff?mode="git"|"branch"&context?`、`GET /vcs/diff/raw`（`text/x-diff`）、`POST /vcs/apply`
- `GET /command`、`GET /agent`、`GET /skill`、`GET /lsp`、`GET /formatter`

**Config / Provider / MCP / Project / Permission / Question**
- `GET /config`、`PATCH /config`、`GET /config/providers` → `{providers, default}`
- `GET /provider` → `{all, default, connected}`、`GET /provider/auth`、`POST /provider/:id/oauth/authorize|callback`
- `GET /mcp` → `Record<name, Status>`、`POST /mcp`、`POST /mcp/:name/auth|auth/callback|auth/authenticate|connect|disconnect`、`DELETE /mcp/:name/auth`
- `GET /project`、`GET /project/current`、`POST /project/git/init`、`PATCH /project/:projectID`、`GET /project/:projectID/directories`
- `GET /permission` → `PermissionV1.Request[]`；`POST /permission/:requestID/reply` body `{reply, message?}`
- `GET /question` → `QuestionV1.Request[]`；`POST /question/:requestID/reply` body `{answers: string[][]}`；`POST /question/:requestID/reject`

**Global / 其他**
- `GET /global/health` → `{healthy:true, version}`；`GET /global/event`；`GET|PATCH /global/config`；
  `POST /global/dispose`；`POST /global/upgrade`
- `PUT|DELETE /auth/:providerID`、`POST /log`
- `GET /doc` → 完整 OpenAPI JSON
- UI catch-all `* /*`

### 3.2 v2 `/api/*`（权威清单）

以 `packages/client/src/generated/client.ts`（**由服务端 OpenAPI 自动生成**，是唯一权威）为准，
共 46 条路径；vendored bundle 里的 `/api/*` 字符串集合与它**完全一致**：

```
GET  /api/health
GET  /api/event
GET  /api/location
GET  /api/agent
GET  /api/command
GET  /api/skill
GET  /api/reference
GET  /api/model
GET  /api/provider                 GET  /api/provider/{providerID}
GET/… /api/credential/{credentialID}
GET  /api/integration              GET/POST /api/integration/{integrationID}
POST /api/integration/{integrationID}/connect/key | /connect/oauth
GET/POST /api/integration/attempt/{attemptID}  POST …/complete
GET  /api/fs/list                  GET  /api/fs/find
GET  /api/permission/request       GET  /api/permission/saved   DELETE /api/permission/saved/{id}
GET  /api/question/request
GET  /api/pty                      POST /api/pty
GET/PUT/DELETE /api/pty/{ptyID}
POST /api/pty/{ptyID}/connect-token
GET  /api/pty/{ptyID}/connect        ← WebSocket upgrade
GET  /api/session                  POST /api/session
GET  /api/session/active
GET  /api/session/{sessionID}
POST /api/session/{sessionID}/agent          body {agent}
POST /api/session/{sessionID}/model          body {model:{id,providerID,variant?}}
POST /api/session/{sessionID}/prompt         body {id?, prompt, delivery?, resume?}
POST /api/session/{sessionID}/interrupt      204
POST /api/session/{sessionID}/compact        204
POST /api/session/{sessionID}/wait           204
POST /api/session/{sessionID}/revert/stage   body {messageID, files?} → {data: Revert.State}
POST /api/session/{sessionID}/revert/clear   204
POST /api/session/{sessionID}/revert/commit  204
GET  /api/session/{sessionID}/context        → {data: SessionMessage.Message[]}
GET  /api/session/{sessionID}/history?limit&after → {data: DurableEvent[], hasMore}
GET  /api/session/{sessionID}/event?after    ← SSE (会话级)
GET  /api/session/{sessionID}/message?limit(1..200)&order(asc|desc)&cursor → {data, cursor}
GET  /api/session/{sessionID}/message/{messageID}
GET  /api/session/{sessionID}/permission              → {data: Permission.Request[]}
POST /api/session/{sessionID}/permission              → {data:{id, effect}}
GET  /api/session/{sessionID}/permission/{requestID}
POST /api/session/{sessionID}/permission/{requestID}/reply   body {reply, message?} → 204
GET  /api/session/{sessionID}/question
POST /api/session/{sessionID}/question/{requestID}/reply     body {answers: string[][]}
POST /api/session/{sessionID}/question/{requestID}/reject
```

来源：`packages/protocol/src/groups/{session,message,pty,fs,event,permission,question,provider,model,agent,command,skill,reference,health,location,integration,credential,project-copy}.ts`。

**v2 `prompt` 的 body 与 v1 完全不同**（`packages/schema/src/prompt-input.ts`）：

```jsonc
{
  "id": "msg_…?",                     // 可选，= SessionMessage.ID
  "prompt": {
    "text": "…",
    "files":  [ { "uri": "…", "name": "…?", "description": "…?", "source": {…}? } ],  // 注意是 uri！
    "agents": [ { "name": "…", "source": {…}? } ]
  },
  "delivery": "steer" | "queue"?,     // SessionDelivery.Delivery（packages/schema/src/session-delivery.ts）
  "resume": true?
}
```
返回 `{data: SessionInput.Admitted}` = `{admittedSeq, id, sessionID, prompt, delivery, timeCreated, promotedSeq?}`
（`packages/schema/src/session-input.ts:14-23`）。成功是 **200 + Admitted**，不是 204。

### 3.3 用户清单中若干端点的澄清（避免臆测）

| 提法 | 事实 |
|---|---|
| `/api/session/{id}/abort` | **不存在**。v2 用 `POST /api/session/{sessionID}/interrupt`（204）。v1 是 `POST /session/{sessionID}/abort` |
| `/api/session/{id}/diff` | **v2 不存在**。v1 是 `GET /session/{sessionID}/diff?messageID?` |
| `/api/session/{id}/prompt_async` | **v2 不存在**。v1 是 `POST /session/{sessionID}/prompt_async`（204） |
| `/terminal` | **不存在**。PTY 走 `/pty`（v1）与 `/api/pty`（v2） |
| `/app` | **不存在**。`app.agents`/`app.skills`/`app.log` 只是 OpenAPI identifier，实际路径是 `/agent`、`/skill`、`POST /log` |
| `/model`（v1） | **v1 无此路径**。v2 有 `GET /api/model`；v1 用 `GET /config/providers` 与 `GET /provider` |
| `/message`（顶层） | **不存在**。消息在 `/session/{id}/message`（v1）与 `/api/session/{id}/message`（v2） |
| `/permission/request` | v1 是 `GET /permission`；v2 是 `GET /api/permission/request` + `GET /api/session/{id}/permission` |
| `/fs`(file tree v2) | v2 `GET /api/fs/list?path=&location[directory]=`、`GET /api/fs/find?query=&type=&limit=`（返回 `FileSystem.Entry[]`，`Location.response` 包装为 `{location, data}`）。v1 对应 `/file`、`/find/file` |
| `/global` | `GET /global/health`、`GET /global/event`、`GET|PATCH /global/config`、`POST /global/dispose`、`POST /global/upgrade` |

---

## 4. 消息与 Part 数据模型

### 4.1 v1 Part（`packages/schema/src/v1/session.ts:357-383`）
`Part.Union` 共 **12 种**，判别字段 `type`：

| type | 关键字段 |
|---|---|
| `text` | `text`, `synthetic?`, `ignored?`, `time?{start,end?}`, `metadata?` |
| `reasoning` | `text`, `time{start,end?}`, `metadata?` |
| `tool` | `callID`, `tool`, `state: ToolState`, `metadata?` |
| `file` | `mime`, `filename?`, `url`, `source?: FilePartSource` |
| `agent` | `name`, `source?{value,start,end}` |
| `subtask` | `prompt`, `description`, `agent`, `model?{providerID,modelID}`, `command?` |
| `step-start` | `snapshot?` |
| `step-finish` | `reason`, `snapshot?`, `cost`, `tokens{total?,input,output,reasoning,cache{read,write}}` |
| `snapshot` | `snapshot` |
| `patch` | `hash`, `files: string[]` |
| `retry` | `attempt`, `error: APIError`, `time{created}` |
| `compaction` | `auto`, `overflow?`, `tail_start_id?` |

公共前缀：`{id: PartID("prt…"), sessionID, messageID}`。
> Android 的 `ChatStore.parsePart` 目前覆盖 text/tool/file；`reasoning`（思考过程）、
> `step-start`/`step-finish`（耗时与 token）、`patch`（文件改动）尚未渲染。
> 前端 `packages/app/src/context/global-sync/event-reducer.ts:20` 的做法是
> `SKIP_PARTS = new Set(["patch","step-start","step-finish"])` —— **直接跳过不渲染**，Android 可照抄。

**`ToolState` 四态**（`v1/session.ts:259-325`，判别字段 `status`）：
- `pending`：`{status:"pending", input: Record<string,any>, raw: string}`
- `running`：`{status:"running", input, title?, metadata?, time:{start}}`
- `completed`：`{status:"completed", input, output: string, title: string, metadata, time:{start,end,compacted?}, attachments?: FilePart[]}`
- `error`：`{status:"error", input, error: string, metadata?, time:{start,end}}`

> 即：**`state.output`（字符串）只在 `completed` 存在；`state.error`（字符串）只在 `error` 存在；
> `running` 只有 `input/metadata`，没有 output。** `ChatStore` 的 `toolOutput`/`toolError` 取值方式正确。

**`FilePartSource`**（`type` 判别，`v1/session.ts:144-169`）：
- `{type:"file", path, text:{value,start,end}}`
- `{type:"symbol", path, range:{start:{line,character},end:{…}}, name, kind}`
- `{type:"resource", clientName, uri, text:{…}}`

### 4.2 v1 Message（`packages/schema/src/v1/session.ts:327-500`）
`Info = User | Assistant`，判别字段 **`role`**：
- `user`：`{id, sessionID, role:"user", time:{created}, format?, summary?{title?,body?,diffs:FileDiff.Info[]}, agent, model:{providerID,modelID,variant?}, system?, tools?}`
- `assistant`：`{id, sessionID, role:"assistant", time:{created, completed?}, error?: AssistantError, parentID, modelID, providerID, mode, agent, path:{cwd,root}, summary?, cost, tokens{total?,input,output,reasoning,cache{read,write}}, structured?, variant?, finish?}`

传输容器：`WithParts = { info: Info, parts: Part[] }`。
`AssistantError`（`name` 判别）：`ProviderAuthError` / `UnknownError{message,ref?}` / `MessageOutputLengthError` /
`MessageAbortedError` / `StructuredOutputError{message,retries}` / `ContextOverflowError` / `ContentFilterError` / `APIError{message,statusCode?,isRetryable,responseHeaders?,responseBody?,metadata?}`。

> Android `ChatStore.parseMessage` 的 `info = obj.optJSONObject("info") ?: obj` 双重兼容写法是对的
> （`GET /session/:id/message` 返回 `{info, parts}`；但某些投影/事件路径可能直接给扁平对象）。

### 4.3 v2 模型（`packages/schema/src/session-message.ts`）
`SessionMessage.Message` 是 **8 种** `type` 判别联合（**不是 role**）：
`user` / `assistant` / `synthetic` / `system` / `shell` / `agent-switched` / `model-switched` / `compaction`。

公共 `Base`：`{id: "msg_…", metadata?, time:{created}}`。
- `user`：`{text, files?: FileAttachment[], agents?}`
- `assistant`：`{agent, model: Model.Ref{id,providerID,variant?}, content: AssistantContent[], snapshot?{start,end,files?}, finish?, cost?, tokens?, error?, time:{created,completed?}}`
- `assistant.content[]` 三种（判别 `type`，`session-message.ts:159-162`）：
  - `text`：`{type:"text", id, text}`
  - `reasoning`：`{type:"reasoning", id, text, providerMetadata?, time?{created,completed?}}`
  - `tool`：`{type:"tool", id, name, provider?:{executed,metadata?,resultMetadata?}, state: ToolState, time:{created,ran?,completed?,pruned?}}`
- v2 `ToolState`（判别 `status`，与 v1 字段**不同**）：
  - `pending`：`{status, input: string}`（字符串！）
  - `running`：`{status, input: Record, structured: Record, content: ToolContent[]}`
  - `completed`：`{status, input, content, structured, outputPaths?, result?, attachments?}`
  - `error`：`{status, input, content, structured, error: {type:"unknown", message}, result?}`
- `ToolContent` = `{type:"text", text}` | `{type:"file", uri, mime, name?}`（`packages/schema/src/llm.ts:11-27`）
- `AgentSwitched{agent}` / `ModelSwitched{model}` / `System{text}` / `Shell{callID,command,output,time}` /
  `Synthetic{sessionID,text}` / `Compaction{reason:"auto"|"manual", summary, recent}`

> **v2 没有「part」概念**：文本/推理/工具是 `assistant.content[]` 的数组元素，`id` 由事件里的
> `textID`/`reasoningID`/`callID` 承担。

### 4.4 会话类型
- v1 `Session.Info`（`packages/schema/src/v1/session.ts:543-569`）：
  `{id:"ses…", slug, projectID, workspaceID?, directory, path?, parentID?, summary?{additions,deletions,files,diffs?}, cost?, tokens?, share?{url}, title, agent?, model?{id,providerID,variant?}, version, metadata?, time:{created,updated,compacting?,archived?}, permission?:Ruleset, revert?{messageID,partID?,snapshot?,diff?}}`
- v2 `Session.Info`（`packages/schema/src/session.ts:19-44`）：
  `{id, parentID?, projectID, agent?, model?:Model.Ref, cost, tokens{input,output,reasoning,cache{read,write}}, time:{created,updated,archived?}, title, location:{directory,workspaceID?}, subpath?, revert?}`

> ⚠️ 任务描述里提到的 `worktree` 字段属于 **`Project.Info`**（`{id, worktree, vcs?, name?, icon?, commands?, time, sandboxes[]}`），
> 不在 `Session.Info` 上；session 上对应的是 `directory` + `path`。
> 另外 v1 `time.created/updated` 是**毫秒 int**（`Timestamp = Finite`），v2 是 `DateTimeUtcFromMillis` 编码的 ISO 字符串。

---

## 5. 流式增量与前端归并语义

### 5.1 两种更新方式
1. **整块替换**：`message.part.updated` 携带**完整 part**。前端做法是 `Binary.search` 后
   `reconcile(part)`（`event-reducer.ts:313-339`），并**清掉**该 part 的 delta 累加器
   （`delete draft.part_text_accum_delta[part.id]`）。
2. **增量追加**：`message.part.delta` 携带 `{messageID, partID, field, delta}`，
   语义是 **`part[field] = (part[field] ?? "") + delta`**（`event-reducer.ts:364-388`），
   **追加，不是替换**。

> 发布侧实证（`packages/opencode/src/session/processor.ts`）：
> - `text-start` → `session.updatePart(完整 text part)`；`text-delta` → `session.updatePartDelta({…, field:"text", delta})`
> - `reasoning-start` → `updatePart`；`reasoning-delta` → `updatePartDelta({…, field:"text", …})`
> - **孤儿 delta（没有前置 `*-start`）会被直接丢弃** —— 所以客户端遇到找不到 part 的 delta 时丢弃是安全且正确的。

### 5.2 前端 store 归并（v1，`packages/app/src/context/global-sync/event-reducer.ts`）
- `message.updated`：按 `messageKey(info)`（= `id`）二分查找 → 命中则 `reconcile(info)`，未命中则**按序插入**
  （消息列表按 id 有序，不是按时间）。
- `message.part.updated`：按 `part.messageID` 分桶；桶内按 `part.id` 二分；命中 `reconcile`，否则插序。
- `message.part.removed`：删 part；桶空了就删桶。
- `message.part.delta`：**只对已存在的 part 生效**（找不到 part 就丢弃）；同时维护一个独立的
  `part_text_accum_delta[partID]` 累加器，用于 UI 的增量打字动画/滚动锚定。
- `message.removed`：删消息 + 删该消息所有 part + 清累加器。
- `session.diff` / `todo.updated` / `session.status` / `permission.asked|replied` / `question.asked|replied|rejected`
  各自按 `sessionID` 分桶做 reconcile/插入/删除（`:256-469`）。
- `session.created|updated|deleted`：同步维护 session 列表（含 `time.archived` 时移出列表）。

### 5.3 与现有 `ChatStore.kt` 的对照
| 语义 | 前端 | `ChatStore.kt` | 评价 |
|---|---|---|---|
| `message.updated` 合并 | `reconcile(info)` | `upsert` + `mergeParts` | ✅ 等价（Kotlin 更保守，保留旧 parts） |
| `message.part.updated` | 整块替换该 part | `PartUpdated` → 同 id 替换 | ✅ 等价 |
| `message.part.delta` | **累加字符串** | ❌ **未处理** | ⚠️ **缺口**：不处理会丢增量文本，只能等 `part.updated` 兜底 |
| 未知 messageID 的 part | 需要 `message.updated` 先到 | 合成 `assistant` 信封兜底 | ✅ 可行（更鲁棒） |
| `SKIP_PARTS` | 跳过 patch/step-start/step-finish | 未跳过（照常渲染） | 建议补上 |
| 排序 | 按 `id`（内含时间前缀） | `sortedBy { timeCreated }` | 基本等价，但 `time.created` 为 0 时会乱序 |
| 运行态 | `session.status`（idle/busy/retry） | `session.idle` / `session.error` | ⚠️ 建议改用 `session.status`，`session.idle` 已 deprecated |

### 5.4 v2 归并（`packages/app/src/context/server-session-v2-reducer.ts`，供参考）
- 用 `ordinal`（同类型内容在 `content[]` 中的序号）定位，而不是 id 全局索引：
  `session.text.delta` → `text = text + delta`；`session.text.ended` → **整块覆盖 `text`**（权威值）。
- `session.reasoning.delta`/`ended` 同理。
- 工具：`input.started` 建 `{status:"streaming", input:""}` → `input.delta` 累加字符串 →
  `called` 变 `{status:"running", input:对象}` → `progress` 更新 `metadata` →
  `success` 变 `{status:"completed", content}` / `failed` 变 `{status:"error", error}`。
- `session.step.started/ended/failed` 负责 assistant 消息的创建与收尾（cost/tokens/finish/snapshot）。
- 关键约定：**delta 是 live-only（不持久化），`*.ended` 才是可回放的全量权威值**
  （`packages/schema/src/session-event.ts:209,247,291` 注释明示）。

### 5.5 重连与补偿
- v1：`GET /session/:id/message` 全量快照 + `/event` 增量；`session.updated` 可触发重拉。
- v2：`/api/session/:id/history?after=<seq>`（分页 durable 事件）+ `/api/session/:id/event?after=<seq>`（SSE 回放），
  durable 事件带 `{aggregateID, seq, version}`，**可用 seq 做精确断点续传**——这是 v2 相对 v1 的最大优势。
- 心跳：v1 `/event` 每 10s 一条 `server.heartbeat`。`EventStream.kt` 的 35s 读超时（= 3 次心跳）合理。

---

## 6. 终端 / PTY

### 6.1 HTTP
v1（`groups/pty.ts`，**只返回 running 会话**；`/api/pty` 才包含 exited）：
- `GET /pty/shells` → `{path,name,acceptable:boolean}[]`
- `GET /pty` → `Pty.Info[]`；`POST /pty` body `{command?, args?:string[], cwd?, title?, env?:Record<string,string>}` → `Pty.Info`
- `GET|PUT|DELETE /pty/:ptyID`（PUT body `{title?, size?:{rows,cols}}`）
- `POST /pty/:ptyID/connect-token` → `{ticket: uuid, expires_in: 秒}`。
  **必带 header `x-opencode-ticket: 1`**（`PTY_CONNECT_TOKEN_HEADER`）且 Origin 合法，否则 403。
  ticket 一次性、TTL 默认 60s（`packages/core/src/pty/ticket.ts`）。
v2（`packages/protocol/src/groups/pty.ts`）：路径同形但加 `/api` 前缀，成功响应包成 `{location, data}`。

`Pty.Info`（`packages/schema/src/pty.ts:22-32`）：
`{id:"pty_…", title, command, args:string[], cwd, status:"running"|"exited", pid, exitCode?}`

### 6.2 WebSocket `/pty/:ptyID/connect`
`packages/opencode/src/server/routes/instance/httpapi/handlers/pty.ts` +
`packages/core/src/pty/protocol.ts`

查询参数：`directory?`、`workspace?`、`cursor?`、`ticket?`。

**升级前置检查顺序**（保持 legacy 语义）：
1. 会话不存在或非 running → **404 空响应**
2. query 解码失败 → 400 空
3. 有 `ticket` 时校验 Origin + 一次性 ticket → 失败 **403 空**；带 ticket 时跳过 Basic 鉴权
4. 升级为 WebSocket

**`cursor` 语义**：`-1` = 只收新输出；安全整数 = 从该绝对输出游标重放；**省略 = 全量重放**
（服务端 replay buffer 上限 `BUFFER_LIMIT = 2 MiB`，超出丢弃最旧并推进 `bufferCursor`，
见 `packages/core/src/pty.ts:52-59,272-285`）；其他非法值按「未定义」处理。

**帧协议（重要，不是 JSON RPC！）**（`packages/core/src/pty/protocol.ts`）：
- **下行（服务端→客户端）**：
  1. replay 阶段：**原始 UTF-8 文本帧**，按 `REPLAY_CHUNK = 64 KiB` 切片
  2. 紧跟**一帧元数据**：单字节 `0x00` + UTF-8 JSON `{"cursor":<绝对输出游标 number>}`，**只发一次**
  3. 之后实时输出同样是**原始 UTF-8 文本帧**（无任何 JSON 包装）
  - 进程退出 → `CloseEvent(1000)`；会话不存在/已退出 → `CloseEvent(4404, "session not found")`
- **上行（客户端→服务端）**：字符串或二进制，二进制按 UTF-8（`fatal:true`）解码，
  **解码结果直接写入 PTY**；无 JSON 控制帧。**改窗口尺寸不走 WS**，走 `PUT /pty/:ptyID {size:{rows,cols}}`。
- 服务端单队列串行写出，保证 `replay → meta → live → close` 顺序
  （`handlers/pty.ts:244-246`：`chunks(replay)` → `metaFrame(cursor)` → `attachment.activate()`）。

> Android 若要做原生终端：收到以 `0x00` 开头的帧要当**元数据**解析并记录 cursor；其余当终端字节流渲染；
> 断线重连时用上次 cursor 作 `?cursor=` 参数即可续传。
> 官方前端按 **JS string length** 累加游标（`packages/app/src/components/terminal.tsx`），
> Kotlin 侧需注意是 UTF-16 长度而非 UTF-8 字节数——是否影响续传 `未确认`，建议以服务端返回的
> metaFrame `cursor` 为准，不要本地推算。

### 6.3 legacy `/pty` 与 v2 `/api/pty` 的差异

| | legacy `/pty` | v2 `/api/pty` |
|---|---|---|
| shells | `GET /pty/shells` → `[{path,name,acceptable}]` | **无此路由** |
| list / get | **只返回 `status==="running"`**；exited 一律 404 | **保留 exited**（含 `exitCode`）直至显式删除 |
| remove | `DELETE /pty/:id` → `true` | `DELETE /api/pty/:id` → **204 NoContent** |
| query | `directory`、`workspace` | `location[directory]`、`location[workspace]` |
| 成功响应包装 | 裸对象 | `Location.response()` → `{location, data}` |

来源：`packages/opencode/src/server/routes/instance/httpapi/groups/pty.ts`（legacy）、
`packages/protocol/src/groups/pty.ts:23-142`（v2）、`packages/sdk/openapi.json`（两套路径并存）。

---

## 7. 数据层其它要点

- **ID 前缀约定**（可用于本地校验/分流）：
  `ses_` 会话、`msg_` 消息、`prt_` part（v1）、`per_` 权限请求、`que_` 提问、`pty_` 终端、`evt_` 事件。
- **命名一致性**：v1 的 `Part.id` 是 `prt…`，v1 `MessageID` 是 `msg…`（`statics` 只校验前缀字符串）。
- **前端使用的 SDK**：`packages/app` 里 v1 类型来自 `@opencode-ai/sdk/v2/client`，
  v2 事件类型来自 `@opencode-ai/client/promise`（`packages/client/src/generated/types.ts`，112 KB 生成类型）。
  旧 JS SDK 位于 `packages/sdk/js/src/gen`（v1）与 `packages/sdk/js/src/v2/gen`（v2）；
  `packages/sdk/openapi.json` 是 v1 面的完整 OpenAPI。
- **文档面**：`packages/docs/` 只有 `openapi.json` + 少量 mdx，**没有** API 参考手册；
  真正的权威是 `GET /doc`（运行时生成）。
### 7.1 vendored bundle 交叉验证结果（含一处**重要纠偏**）
- 路径字面量：bundle 同时包含 v2 `/api/*` 与 v1 无前缀两套集合，且 v2 集合与
  `packages/client/src/generated/client.ts` **完全一致**——证明 bundle 就是这份前端的构建产物。
- **信封纠偏**：bundle 的 reducer 里读的是 `t.properties.part` / `.info` / `.delta`，但这**不等于 wire 上是
  `properties`**。bundle 里有一个内联的归一化 shim：

  ```js
  function DSe(e){ return e.type==="permission.v2.asked"
      ? {id:e.id, type:"permission.asked", properties:{id:e.data.id, sessionID:e.data.sessionID, permission:e.data.action, ...}}
      : {id:e.id, type:e.type, properties:e.data, current:e} }
  // 分派：const ge = "payload" in V;  const B = ge ? V.payload : DSe(V);
  ```

  即：**wire 上 v2 用 `data`（`/api/event`），v1 `/global/event` 用外层 `payload`（其内才是 `properties`）；
  bundle 统一归一化成内部的 `.properties` 再交给 reducer。**
  所以正确的结论是：**看 wire 不看内部**——
  - v1 `/event`（服务端 `handlers/event.ts:40` 明写 `properties: event.data`）→ **`properties`** ✅（Android 现状正确）
  - v1 `/global/event` → 外层 `{directory, project?, workspace?, payload:{id,type,properties}}`
  - v2 `/api/event` → **顶层 `data`**（`packages/schema/src/event.ts` `define()`），且 `permission.v2.asked`
    会被 bundle 改写成 `permission.asked`。
- 前端只过滤 `new Set(["patch","step-start","step-finish"])`，与 `event-reducer.ts:20` 逐字一致。
- 前端 SSE 不是 `EventSource`（0 命中），而是 `fetch()` + `ReadableStream` + `TextDecoderStream` 手写解析，
  带 `Last-Event-ID` 请求头与默认 3000ms 重连延迟。
- **协议探测**：bundle 运行期探测 `/global/health`（v1，返回 `{healthy:true, version}`）与
  `/api/health`（v2，源码 `packages/protocol/src/groups/health.ts` 返回 `{healthy:true}`）；
  具体判别谓词 `未确认`（有报告称 v2 侧返回数字 `pid`，与源码不符，存疑）。
- bundle 额外引用了 `/api/debug/location`、`/api/form/request`、`/api/generate`、`/api/plugin`、
  `/api/server`、`/api/service/stop`、`/api/shell`、`/api/config` —— 这些**不在** `packages/client`
  生成清单里，推测属于 console / 其它服务的路径（`未确认`）。

### 7.2 ⚠️ 已知风险：**v2** 会话流式事件名可能存在断层
`packages/app` 的 v2 reducer 与 vendored bundle 都在处理**不带 `.next.`** 的名字
（`session.text.delta` / `session.reasoning.delta` / `session.tool.input.delta` / `session.step.started` /
`session.input.admitted` / `session.tool.called` …），而当前 `dev` 服务端 schema
（`packages/schema/src/session-event.ts` + `packages/core/src/session/message-updater.ts`）
**只发 `session.next.*`**。也就是说：若 Android 直接对接「HEAD 版 server 的 v2 面」，
会话流式事件名将对不上（bundle 里只认 `session.next.moved` 这一个 `.next.` 名）。
- 影响面：**仅 v2 `/api/*` 面**。v1 的 `message.part.updated` / `message.part.delta` 不受影响。
- 建议：Android 继续走 v1；如要上 v2，**必须先对目标 server 实测一次**事件名再写解析器，
  并对 `session.X` 与 `session.next.X` 双命名做容错。

### 7.3 前端 PTY 连接实现（可直接照抄的参数构造）
可读源码：`packages/app/src/utils/terminal-websocket-url.ts`（与 bundle 内联函数逐行一致）。
bundle 中 PTY WebSocket URL 构造函数（反混淆后）：

```js
function ptyUrl(e) {
  const v1 = e.protocol === "v1"
  const base = origin(e.url)
  const url = new URL(`${base}${v1 ? `/pty/${e.id}/connect` : `/api/pty/${e.id}/connect`}`)
  v1 ? url.searchParams.set("directory", e.directory)
     : url.searchParams.set("location[directory]", e.directory)
  url.searchParams.set("cursor", String(e.cursor))
  url.protocol = url.protocol === "https:" ? "wss:" : "ws:"
  if (e.ticket) { url.searchParams.set("ticket", e.ticket); return url }
  if (v1 && e.password && (!e.sameOrigin || e.authToken))
    url.searchParams.set("auth_token", base64(`${e.username}:${e.password}`))
  return url
}
```

- 取 ticket：v1 调 `POST /pty/{ptyID}/connect-token`，**带 header `x-opencode-ticket: 1`**，
  读 `data.ticket`；404/405 → 放弃 ticket（旧服务端无此路由）；403 → CSRF 错误。
  **v2 分支在源码里被注释掉了**（`packages/app/src/components/terminal.tsx:579-585`）→
  **v2 下前端完全不申请 ticket**，WS URL 也不会追加 `auth_token`，身份只能靠 Cookie/Basic。
  （这可能是 v2 终端面的一个待修点，`未确认`。）
- 收发：`socket.binaryType = "arraybuffer"`；`onmessage` 若收到 `ArrayBuffer` 且 `bytes[0] === 0`，
  解析 `{"cursor":N}` 写回本地游标；否则按字符串写终端。发送直接把键盘输入 `ws.send(str)`。
- 重连退避：`Math.min(250 * 2 ** Math.min(tries, 4), 4000)` ms；非 1000 关闭码提示 `terminal.connectionLost.abnormalClose`。

---

## 8. Android 落地建议（基于以上事实）

1. **保持走 v1 面**：`/session`、`/session/{id}/message`、`/session/{id}/prompt_async`、`/event`。
   这是现状兼容性最好、字段最稳定的组合，且 bundle 证明了官方前端也用同一套。
2. **补 `message.part.delta` 处理**：`state.delta` 追加到 `parts[partID][field]`（字段通常是 `text`）。
   这是目前原生实现里最明显的功能缺口（v2 之前的 opencode 主要靠全量 `part.updated`，但 delta 已经上线）。
2b. **`EventStream` 必须带 `directory`（疑似现存缺陷）**：
   `ChatViewModel.kt:135-139` 用 `baseHttpUrl = parsed`（纯 server URL，无 query）构造 `EventStream`，
   而 `EventStream.kt:62` 只做 `addPathSegment("event")` —— **没有传 `directory`**。
   服务端 `/event` 是按 `instance.directory` 过滤的实例级流（见 §2.1 警告），
   不传就退化到 server 进程的 `cwd`。单实例、且 server 恰好起在工作区目录时能工作，
   多目录/异地启动时会静默收不到事件。
   修法二选一：`GET /event?directory=<工作区绝对路径>`，或加 header `x-opencode-directory: <路径>`
   （server 侧解析优先级 `?directory` → `x-opencode-directory` → `cwd`；
   `LocalGateway.kt:424` 的白名单里已经放行了该 header）。
   同一问题不适用于 REST：`OpenCodeClient.url()` 已正确附加 `?directory=`。
3. **改用 `session.status`** 判定运行态：`{type:"idle"|"busy"|"retry"}`，比 `session.idle`/`session.error`
   更完整（能显示重试倒计时 `next`、重试原因 `action`）；同时保留 `session.error` 兜底。
4. **渲染扩展**：在现有 text/tool/file 之外补 `reasoning`（思考）、`step-finish`（token/cost 统计）；
   `patch`/`step-start`/`step-finish` 可按官方前端做法跳过。
5. **Prompt 可增强**：现有只发 `parts`，可以再补 `model{providerID,modelID}`、`agent`、`variant`、`messageID`
   （`messageID` 可用于乐观 UI 与幂等重试）。
6. **断线补偿**：重连后重拉 `/session/{id}/message`，再用 `/event` 增量；注意 `session.updated` 触发会话列表刷新。
7. **若日后切 v2**：注意 `/api/session/{id}/prompt` 的 body 是 `{prompt:{text,files[{uri}],agents}}`（不是 `parts`），
   且返回 `Admitted` 而非 204；事件类型是 `session.next.*` 且信封用 `data` 而非 `properties`；
   消息模型变成 `SessionMessage`（`type` 判别，无 part）。**两套不能混用同一个解析器。**

---

## 9. 主要来源路径索引

| 主题 | 路径 |
|---|---|
| v1 路由组 | `packages/opencode/src/server/routes/instance/httpapi/groups/*.ts` |
| v1 路由挂载 / 目录 / doc / UI | `packages/opencode/src/server/routes/instance/httpapi/server.ts` |
| SSE 实现 | `packages/opencode/src/server/routes/instance/httpapi/handlers/event.ts` |
| 事件信封桥接 | `packages/opencode/src/event-v2-bridge.ts` |
| v1 Part / Message / Session / 事件定义 | `packages/schema/src/v1/session.ts` |
| v1 权限 / 提问 | `packages/schema/src/v1/{permission,question}.ts` |
| 会话状态事件 | `packages/schema/src/session-status-event.ts` |
| 事件清单入口 | `packages/schema/src/event-manifest.ts`、`packages/schema/src/event.ts` |
| v2 durable 会话事件 | `packages/schema/src/session-event.ts` |
| v2 消息 / 会话 / prompt | `packages/schema/src/{session-message,session,session-input,prompt,prompt-input}.ts` |
| v2 路由组 | `packages/protocol/src/groups/*.ts` |
| v2 权威客户端清单 | `packages/client/src/generated/client.ts`、`…/types.ts` |
| PTY 协议 | `packages/core/src/pty/protocol.ts`、`packages/core/src/pty/ticket.ts`、`packages/schema/src/pty.ts` |
| 前端归并（v1） | `packages/app/src/context/global-sync/event-reducer.ts` |
| 前端归并（v2） | `packages/app/src/context/server-session-v2-reducer.ts` |
| 前端事件归一化 / 取流 | `packages/app/src/context/server-sdk.tsx`（`:28-57` `adaptServerEvent`、`:260-317` 重连） |
| 前端 PTY URL / 终端组件 | `packages/app/src/utils/terminal-websocket-url.ts`、`packages/app/src/components/terminal.tsx` |
| 前端协议探测 | `packages/app/src/context/server-sdk.tsx`（`MT()`） |
| v1 OpenAPI | `packages/sdk/openapi.json`、`GET /doc` |
