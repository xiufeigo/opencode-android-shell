# A5 — v2 REST + SSE ↔ Native 数据层协议映射（2.0.11 实测）

> 审计对象：`_upstream/packages/schema`（v2 当前契约）+ `_upstream/packages/app/src/runtime/server/*`、`composer/submit.ts`
>（官方客户端行为）↔ `app/src/main/java/com/xiufeigo/opencodeshell/`（`OpenCodeClient.kt`、`ChatStore.kt`、
> `ChatViewModel.kt`、`EventStream.kt`、`v2/V2Api.kt`、`v2/V2Capability.kt`）。
> 实测基线：v2 serve **2.0.11**@`http://localhost:4095`（Basic `opencode:opencode`，下文 `AUTH`），
> v1 serve 1.18.31@4096。只发 read-only GET（另有 `/openapi.json` 全量路由表，113 条 path）。
> 报告里的"已测"指本次 curl 实测；"文档"指 live `/openapi.json`；"UNVERIFIED"指两者都没覆盖（留给 Wave 1 用 POST 验证）。

## 0. 结论先行（给 Wave 1 的 7 个必读差异）

1. **探针必须换**：`GET /api/health` 在 2.0.11 上是 **404 空体**（`V2Capability.decideHealth/probe` 按 1.18.31  hybrid
   写的，已过期）。v2 探针信号改为 **`GET /api/info` → `{"version":"2.0.11",…}`**（有 version 即 v2；v1 无此路由，
   v1 的版本在 `GET /global/health` → `{"healthy":true,"version":"1.18.31"}`）。
2. **`V2Capability.COVERAGE` 表大面积过期**：它基于 1.18.31 hybrid 实测（"未实现的 /api/* 回 SPA HTML"）。
   2.0.11 上本次 GET 测到：`session/{id}` PATCH（改名）+ DELETE（删除）、`session/{id}/shell`、
   `session/{id}/command`、`session/{id}/diff`、`GET /api/config`（注意是**数组**）、`/api/model/default`、
   `/api/project`（**裸数组，无 envelope**）、`/api/vcs*` 全家、`/api/mcp` 全都**存在**。
   只有 question 系路由是真没有（见 4）。Wave 1 开工前必须重跑一遍 COVERAGE。
3. **per-session SSE 与 history 路由不存在**：`/api/session/{id}/event` 与 `/api/session/{id}/history` 在
   openapi 里**没有**，live 均为 404。`V2Api.sessionEventRequest/history()` 指向了不存在的路由。
   替代品是 **`GET /api/experimental/session/{id}/log?after=&follow=`**（SSE，见 §5）。
4. **question 体系在 v2 REST 里消失**：`/api/question*`、`/api/session/{id}/question*` 全部 404（openapi 也无）。
   待办问答的 v2 载体是 **form**（`GET /api/form`、`GET/POST /api/session/{id}/form`、
   `GET/DELETE /api/session/{id}/form/{formID}`、`POST …/reply`）+ transcript 里的 question tool 状态。
   `ChatStore` 的 question dock 需要 form 适配层（§1.5、§6）。
5. **permission 回复字段叫 `decision` 不是 `reply`**：openapi `POST …/permission/{requestID}/reply`
   body `{decision: Permission.Reply(required), message?}`。`V2Api.replyPermission` 发的 `{reply}` **会被 400**
  （`additionalProperties:false`）。另有 `GET …/permission/{requestID}`（单条）与 `POST …/permission`（创建）是新增能力。
6. **prompt 请求体是扁平的**：openapi `POST …/prompt` body `required:[text]`，
   字段 `{id?, text, files?, agents?, skills?, metadata?, delivery?, resume?}`——**没有 `prompt:{}` 包裹层**，
   也没有 `resume:false` 之外的执行开关之外的东西。`V2Api.prompt()` 当前发 `{prompt:{text},delivery,resume}`，
   与文档矛盾（它自称"实测通过"，可能是旧 hybrid 的形状）。Wave 1 必须在一次性 session 上 POST 验证到底哪种被接受，
   在此之前不要照抄。`command`（`required:[name,text]`）、`shell`（`required:[command]`，`{id?,command}`）同理以 openapi 为准。
7. **revert-clear 的方法是 `DELETE /api/session/{id}/revert`**，不是 `POST …/revert/clear`
  （后者在 openapi 里不存在）。`revert/stage`（`required:[messageID]`）与 `revert/commit`（空 body）存在。
   `fork` body 是 `{before?}`（`^msg_`），不是 v1 的 `{messageID?}`。

---

## 1. 事件映射表：v2 event type → `ChatStore` 折叠方式

线包格式（实测 + `schema/event.ts`）：`{id:"evt_…", type, created, data:{…}, durable?:{aggregateID,seq,version},
location?:{directory}, metadata?}`。SSE 帧是 `data: {json}` + 空行分隔，`: heartbeat` 保活，
**无 `event:`/`id:` 行**（`V2Api.readEventStream` 的写法正确）。durable 事件带 `durable.seq`
（per-aggregate ≥0）；ephemeral（delta/progress/status 类）无 seq、只 live、不回放。

全局流 `GET /api/event` 首帧必为 `{"type":"server.connected","data":{}}`（已测），之后混入各目录事件
（`location.directory` 区分，对应 `EventStream global` 思路，但 v2 不用 `{directory,payload}` 包裹，
`location` 是帧的顶层键——`unwrapGlobal` 的 v1 形状分支要保留给 v1 服务器）。

### 1.1 会话执行与状态 → `RunStatus` / `RetryInfo` / `error`

| v2 event type | durability | fold 进 `ChatStore` | 说明 |
|---|---|---|---|
| `session.status` `{idle\|busy\|retry{attempt,message,action,next}}` | ephemeral | 现有 `RunStatusChanged` 分支**原样复用**（busy/retry→RUNNING，idle→IDLE，retry 带 `RetryInfo`） | 实测全局流可直接看到 `provider.updated`/`model.updated`，status 帧在 turn 期间出现；`session.idle`（deprecated）保留做兼容 |
| `session.execution.started` | durable v1 | RUNNING（乐观 busy 的服务端确认） | 无 payload，除 sessionID 外无字段 |
| `session.execution.succeeded` | durable v1 | IDLE + 触发一次限流 snapshot（对齐现有 IDLE 分支的 `refreshSnapshotThrottled`） | idle marker 语义的最小 durable 信号 |
| `session.execution.failed` `{error:{type,message,status?}}` | durable v1 | `RunStatusChanged(ERROR, errorMessageOf, errorRawJsonOf)` | **v2 error 形状变了**：`{type,message,status?}`（`session-error.ts`），v1 是 `{name,data:{message}}`。现有 `errorMessageOf` 读序 `data.message→name→message`，v2 走第三顺位 `message`——可用，但建议首位再加 `type` 回退（field-less 错误只有 type）。`isInterrupted` 已同时读 `name`/`type`——可用 |
| `session.execution.interrupted` `{reason:user\|shutdown\|superseded\|inactivity}` | durable v1 | `RUNNING→IDLE`（reason=user/superseded 视为用户中断，timeline 置 `interrupted` 标记；shutdown 不收 idle marker，按 AGENTS.md V2 Session Core 约定恢复后继续同一 turn） | v1 `abort` 的语义后继；`POST …/interrupt?resume=`（`resume` 是 query，不是 body） |
| `session.retry.scheduled` `{assistantMessageID,attempt,at,error}` | durable v1 | RUNNING + `RetryInfo(attempt, next=at, message=error.message)`（复用 countdown UI，`session-ui/.../session-retry.tsx`） | 比 `session.status{retry}` 更早的信号；`at` 是 epoch millis，直接进 `RetryInfo.next` |
| `session.usage.updated` `{cost,tokens}` | ephemeral | `_sessionCost`（现有 cost 字段；v2 tokens 形状 `{input,output,reasoning,cache{read,write}}`，求和规则沿用 `tokenTotal`，另加 reasoning 项） | `session.usage.recorded{source:title\|compaction}` 是内部事件不下发，忽略 |
| `session.compacted` `{sessionID}` | ephemeral | `ServerSignal` 级别：置 transcript 修订位，触发 `message` 重拉（compaction 后上下文窗口变化） | 与下面的 compaction 消息配合 |

### 1.2 文本 / 推理 / 工具增量 → `UiMessage`/`UiPart`（核心转写规则）

v2 转写目标是 **`GET …/message` 的扁平 union**（`session-message.ts`，已测实物），不是 v1 的 `{info,parts}`：

- `user{ id,time,text,files?,agents?,skills? }` → `UiMessage(role=user)`，1 个 `UiPart(type=text)`；
  `metadata.displayText/attachments/comments/agent/model` 是 composer 写入的、读回时用 `text` 即可。
- `assistant{ agent,model{id,providerID,variant?},content[],snapshot?,finish?,rawFinish?,providerState?,cost?,tokens?,error?,retry?,time{created,streamed?,completed?} }`
  → `UiMessage(role=assistant, agent, model="providerID/modelID", tokens=input+output(+reasoning/cache),
  timeCompleted, interrupted=isInterrupted(error))`；
  content 三态：`text{text,state?}`→`UiPart(type=text)`；`reasoning{text,state?,time?}`→`UiPart(type=reasoning,text=正文)`；
  `tool{id,name,executed?,providerState?,providerResultState?,state:{streaming{input}|running{input,metadata}|completed{input,content,metadata?}|error{input,error,content?,metadata?}},time}`→`UiPart(type=tool,toolName=name,toolStatus=state.status,toolInput=input JSON,toolOutput=content,toolError=error)`。
  已测实物：`state.input={command:…}`，`content=[{type:text,text}]`，`metadata={output,exit,truncated}`——
  与现有 `parsePart` 字段一一对应，**转写函数可复用，只换输入形状**。
- `shell{shellID,command,status,exit?,output?,time}` / `synthetic` / `system` / `skill` /
  `agent-switched` / `model-switched` / `location-switched` / `compaction{running|completed|failed}` /
  `idle{outcome}` → 全部是 notice 行（对齐 `timeline/projection.ts` 的 `Notice` 定义与 `isNotice` 规则；
  synthetic 无 description 时折叠）。

流式事件 → store 事件对照（`assistantMessageID` 关联到已存在的 assistant `UiMessage`，`ordinal` 区分同消息多 text/reasoning 块；part id 规则沿用 projection：tool 用 call id，非 tool 用 `msg:type:ordinal`）：

| v2 event type | durability | fold |
|---|---|---|
| `session.step.started` `{assistantMessageID,agent,model,snapshot?,started}` | durable | 确保 assistant 信封存在（`PartUpdated` 的 synthetic-envelope 模式），置 RUNNING；`started` 是分发时刻 |
| `session.step.streamed` `{assistantMessageID}` | durable | provider 响应体边界：把该 assistant 标记"已流式、可渲染"，不改变 parts（解决"有 content 但 time.completed 缺失"的 thinking 判断） |
| `session.step.ended` `{assistantMessageID,finish,rawFinish?,providerState?,cost?,tokens?,snapshot?,files?}` | durable | `MessageUpdated`：写 `finish/tokens/cost/snapshot`；`files` 失效 review 面板的 git scope（v2 的 `GET …/diff?from&to&context` 替代 `GET /session/:id/diff?messageID`） |
| `session.step.failed` `{assistantMessageID,error,finish?,…}` | durable | 同上 + `RunStatusChanged(ERROR)`（除 abort/interrupt 类→IDLE + interrupted 标记，沿用 `isInterrupted`） |
| `session.text.started` / `session.reasoning.started` `{assistantMessageID,ordinal}` | durable | 预创建空 `UiPart`（解决 v1 "delta 先于 envelope"问题在 v2 的正统解法；现有 synthetic-envelope 逻辑保留做乱序兜底） |
| `session.text.delta` / `session.reasoning.delta` `{assistantMessageID,ordinal,delta}` | **ephemeral** | `PartDelta(messageID,partID,field=text,delta)`——现有 `applyPartDelta` 的 `text`/`reasoning` 分支直接复用；**只做字符串 append，不参与 seq** |
| `session.text.ended` `{assistantMessageID,ordinal,text,state?}` / `reasoning.ended` | durable | `PartUpdated` 全值覆盖（回放/重连后以此为准，覆盖 ephemeral 累积值） |
| `session.tool.input.started` `{assistantMessageID,id,name}` | durable | 预创建 tool `UiPart(toolStatus=streaming)` |
| `session.tool.input.delta` `{assistantMessageID,id,delta}` | **ephemeral** | tool input JSON 字符串 append（显示可截断；现有 `toolInput` 分支是 drop，v2 建议按 `MAX_TOOL_TEXT_CHARS` append，与 output 同规则） |
| `session.tool.input.ended` `{…,text}` | durable | `toolInput=text` 全值覆盖 |
| `session.tool.called` `{…,input,executed,state?}` | durable | `toolStatus=running`，`toolInput=input`（对象→JSON），`executed` 进 raw/展示 |
| `session.tool.progress` `{…,metadata}` | **ephemeral** | 原地更新 tool metadata（bash 的 `output/exit/truncated` 在这里先出现；只保留最近一份，不累积） |
| `session.tool.success` `{…,content(NonEmpty),metadata?,executed,resultState?}` | durable v2 | `toolStatus=completed`，`toolOutput=content`（text 拼 / file 转引用行），`PartUpdated` |
| `session.tool.failed` `{…,error,content?,metadata?,executed,resultState?}` | durable v2 | `toolStatus=error`，`toolError=error.message` + raw；question 类失败走 dock（§1.5） |
| `session.compaction.started/delta/ended/failed` | durable/delta-ephemeral | compaction notice 消息 + `Event.SessionDiff`-式的上下文失效：compaction 完成后重拉 `…/context`（active window）而不仅是 `…/message` |
| `session.message.content.updated` `{messageID,content[]}` | durable（replay-only，不进 public manifest） | `MessageUpdated` 全量替换该 assistant 的 content（旧版本回放兼容分支） |
| `session.shell.started/ended` `{shell:Shell.Info, output?}` | durable | shell notice 行（`session-message.Shell`）；`output{output,cursor,size,truncated}` 分页语义：`cursor<size` 继续拉 `GET /api/shell/{id}/output?cursor&limit` |
| `session.synthetic` / `session.skill.activated` / `session.instructions.updated{delta,text?}` | durable | notice 行（instructions 的 `text` 有即渲染，无则只记状态变更） |

### 1.3 会话元事件 → 列表 / 标题 / 导航（`Signal` 扩展）

| v2 event | fold |
|---|---|
| `session.created`（全字段：location, subpath, agent, model, metadata…） | 现有 `SESSION_CREATED`：Home 列表修订位 +1（payload 自带完整行，可直接 upsert，v1 只有信号） |
| `session.deleted` | 现有 `SESSION_DELETED`：列表移除 + 若为当前会话→空态/回 Home |
| `session.renamed{title}` / `session.agent.selected` / `session.model.selected`（均带 previous） | `SESSION_*` 新信号或直接 `MessageUpdated`-style 的 header patch：标题/agent/model chip 原地更新，不必等 snapshot；model/agent 切换同时清 composer override（对齐 `applySelection` 语义） |
| `session.permissions{permissions}` | composer 锁/提示重算（Ruleset 全量） |
| `session.viewed{idle}` | 只读水位，他端已读指示；本机不主动发（除非要做已读同步：`POST …/view{idle}`） |
| `session.moved{location,projectID,subpath?}` | 当前会话的 `directory` 改道：后续 REST 的 location 默认值 + SSE 目录过滤都要换；列表修订 |
| `session.forked{parentID,boundary,instructions?}` | 新会话行（`POST …/fork{before?}` 的响应即新 `SessionInfo`；注意字段是 `before` 不是 `messageID`） |
| `session.revert.staged{revert}/cleared/committed{to}` | `_revertMessageID` 三态机（现有字段保留；stage 响应 `RevertState{messageID,partID?,snapshot?,diff?,files?}` 直接采用；commit 后 transcript 重拉——revert 改写历史） |
| `session.inbox.enqueued{item:{type,payload,delivery}}` / `delivered{inboxID}` / `cancelled` / `delivery.changed{delivery}` | **新能力，无 v1 等价**：排队态 UI（composer 下方 pending 条，steer/queue 徽标；delivered 后该 inboxID 对应的 user 消息出现，可做 optimistic 关联——`id` 即未来的 `msg_`，见 §2 prompt）；cancelled/delivery.changed 原地更新 pending 条 |

### 1.4 `session.status` 之外的执行信号

`GET /api/session/active → {data:{ses_id:{type:"running"}}}`（已测 idle/文档 busy）是 REST 版 run 状态：
用作 SSE 断线期间的轮询兜底（进后台回来先调一次，再决定是否全量 snapshot；比盲目 snapshot 便宜）。

### 1.5 permission / question / form → dock（v1→v2 断裂带）

- `permission.asked`（payload 即 `Request{id:per_,sessionID,action,resources[],save?,metadata?,source?{type:tool,messageID,id}}`）
  → 现有 `PermissionAsked`，字段重命名：`permission→action`、`patterns→resources`、`always→save`、
  tool 源 `source:{messageID,callID}`（v2 文档名；实测需 Wave 1 补——live 无 pending ask）。
  `permission.replied{sessionID,requestID,reply}` → `PermissionReplied`（`reply` 值域仍是 `once|always|reject`，
  但**回复请求体是 `{decision}`**，见 §0.5；store 内字段名可保留 `reply`，发包时写 `decision`）。
- **question 事件在 schema 层已不存在**（`question.ts` 只剩 Prompt/Answer 类型；无 Asked/Replied 事件定义；
  REST 也无）。v2 的"向用户提问"走两条路：① transcript 里 `name=question` 的 tool（`state.status` 流转，
  `renderable` 里 question 在 streaming/running 时隐藏的规则继续有效）；② **form 事件**
  `form.created{form{id:frm_,sessionID,title,metadata?,fields}}` → 新 dock（字段类型 string/number/integer/
  boolean/multiselect/external，`when` 条件、`required/hidden`；`form.replied{id,sessionID,answer}`/
  `form.cancelled` 关闭 dock）。注意 `sessionID` 可为 `"global"`（MCP elicitation 哨兵值）——dock 必须能挂非会话级的 form。
- `question.reply/reject` 的 v2 对应物是 `POST …/form/{formID}/reply{answer}` 与 `DELETE …/form/{formID}`。
  旧的 `answers:[[label]]` 形状作废。

### 1.6 目录 / 项目 / 工具链信号 → 现有 `Signal` 表的增删

现有 13 个 `Signal` 中可直接复用的（名或语义相同）：`provider.updated`、`model.updated`（全局流已测实物，
payload `{location,data:{}}`）、`agent.updated`、`config.updated`、`project.updated`、`pty.*`（`pty.updated/deleted`
在 2.0.11 全局流是 durable 帧，照收）、`vcs.branch.updated`（REST 有 `/api/vcs*`，事件名不变）。
`file.edited`（v1 filesystem）在 v2 manifest 里无对应——文件变化改走 `session.step.ended{files[]}`（会话级）
与 `…/diff` 重拉。**新增**：`config.updated` 仍在（`GET /api/config` 存在，见 §2）；
`mcp.status.changed/mcp.tools.changed/mcp.resources.changed{server}`（`GET /api/mcp`、`…/mcp/resource` 存在）；
`worktree.updated{projectID}/ready/failed`（`GET/POST/DELETE /api/worktree`、`…/worktree/refresh` 存在）；
`location.shutdown{}`（该 location 缓存全失效，nagy：重 bootstrap 该目录）；`session.compacted`（§1.1）；
`plugin.updated`（schema 归为兼容层最小 payload，按 `project.updated` 同模式处理）；
`shell.created/exited/deleted`（`/api/shell` 全家存在）；`credential`（`/api/credential/{id}(/activate)`，
OAuth 完成后刷新 provider/integration）。
`session.created/deleted` 在 v2 manifest 语义下是**会话事件**（带 sessionID 聚合）不是 v1 的全局信号——
路由时同时驱动列表（§1.3）与当前会话门控。

---

## 2. REST 映射表：每个 `ChatViewModel`/`OpenCodeClient` 调用 → v2 等价

Envelope 总规则（已测）：catalog/工具类 → `{location, data}`；session/message/permission/saved 类 →
`{data}` 无 location；`message` 另带 `cursor{previous,next}`（opaque base64，**不可与 order 混用**）；
`session.list` 另可用 `parentID`（fork 树！v1 没有）；写操作多为 **204 空体**；错误为 JSON `{_tag,…}`
（`SessionNotFoundError`、`ProviderNotFoundError`、`PtyNotFoundError`、`InvalidRequestError{kind}` 已见实物）。
location 传参：catalog 类用 `?location[directory]=`（deepObject explode，已测 `%5B%5D` 编码可用）；
`session.list` 用裸 `?directory=`；`…/message` **不支持 location**（会话级路由不接受 location 参数）。

| # | 现有调用（v1） | v2 等价 | 包体/响应差异（Wave 1 照此改） |
|---|---|---|---|
| 探针 | `GET /global/health` | **`GET /api/info`** → `{version,pid,urls,paths}` | `/api/health` 404 已测；`V2Capability.probe` 改打 `/api/info`，`v2 = version startsWith "2."`（`decideHealth` 整段换掉，保留 SPA-HTML 防御：非 JSON 即非 v2） |
| cwd | `GET /path` → `{…directory}` | `GET /api/location` → 裸 `LocationInfo{directory,project{id,directory,canonical}}` | 无 envelope；`workspaceID` 缺省（无 workspace 注册时） |
| 会话列表 | `GET /session?limit`（裸数组） | `GET /api/session?limit&order&search&cursor&directory&project&subpath&parentID` → `{data[],cursor?}`（limit Meter 后才有 cursor；默认 newest-first） | `directory` 过滤已测（3/50）；`parentID` 是 fork 树查询新能力；分页用 `cursor.next` 迭代 |
| 新建会话 | `POST /session` | `POST /api/session{id?,title?,agent?,model?,location?}` → `{data:SessionInfo}` | 响应包 `{data}`；`location{directory,workspace?}` 替代顶层 `directory`；`SessionV2Info` 无 `slug/version`，`directory` 移进 `location`，tokens 扁平 `{input,output,reasoning,cache}`，`time{created,updated,archived?}` |
| 取会话 | `GET /session/{id}` | `GET /api/session/{id}` → `{data}` | 404 体 `{_tag:SessionNotFoundError}`（按 _tag 分流"已删" vs 网络错） |
| 改名 | `PATCH /session/{id}{title}` | **`PATCH /api/session/{id}{title\|null,permissions\|null}`** | 存在（COVERAGE 曾标 V1_ONLY，过期）；`additionalProperties:false`，只发这两个键 |
| 删除 | `DELETE /session/{id}` | **`DELETE /api/session/{id}`**（空体） | 同上，存在 |
| 发 prompt | `POST /session/{id}/prompt_async{parts[],model?,agent?,variant?,system?}` | `POST /api/session/{id}/prompt{id?,text(**required**),files?,agents?,skills?,metadata?,delivery?,resume?}` → `{data:{admittedSeq,id,sessionID,prompt?,delivery?,timeCreated,promotedSeq?}}` | **三处断裂**：① 扁平 `text`（`V2Api` 的 `prompt:{}` 包裹待 Wave 1 POST 验证）；② `parts[]`（v1 text+file 混排）→ `text` + `files[]`（`PromptInput.FileAttachment`，沿用 data-URL/uri 附件）+ `agents[]` + `skills[]` + `metadata{}`（displayText/comments/附件清单进这里）；③ `model/agent/variant/system` **不在 body 里**——先调 `POST …/model{model}` / `POST …/agent{agent}`（§0/composer 对齐 `applySelection`），steer 才切、queue 不切（`submit.ts:363` 规则不变）。`id:msg_` 由客户端传（optimistic 关联键！handoff 机制保留）。`resume:false` = 只入库不执行（admit-only，新能力，可做"保存草稿到服务端"）。`delivery:steer\|queue` 不变 |
| shell turn | `POST /session/{id}/shell{agent,command,model?}` | **`POST /api/session/{id}/shell{id?,command(required)}`** | 存在（COVERAGE 曾标 V1_ONLY，过期）；`agent` 不再是 body 字段（会话既有 agent 生效）；响应即创建的消息（v1 语义保留） |
| slash 命令 | `POST /session/{id}/command{command,arguments,agent?,model(flat string)?,variant?}` | **`POST /api/session/{id}/command{name,text(required),files?,agents?,skills?,delivery?}`** | 存在（同上过期）；`command→name`，`arguments→text`，model 平串写法作废（先切 model 再发） |
| 中断 | `POST /session/{id}/abort` | `POST /api/session/{id}/interrupt?resume=` → 204（idle 调即 no-op，已测） | `resume` 是 query；语义对齐 `execution.interrupted` |
| compact | `POST /session/{id}/summarize` | `POST /api/session/{id}/compact{id?,delivery?}` → 204（UNVERIFIED：live 未敢动 fixture） | `delivery` 可选（后台 compact 新能力） |
| 等待 idle | （无，本地 grace 模拟） | `POST /api/experimental/session/{id}/wait` → 204（UNVERIFIED） | 注意前缀是 **experimental**；GET 版不存在 |
| 切 agent/model | （v1 无，prompt 包里夹带） | `POST …/agent{agent}` / `POST …/model{model:{id,providerID,variant?}}` → 204（已测） | 服务端去重（`submit.ts:340` 注释保留：缓存 SSE 状态可能滞后，照发） |
| revert 系 | `POST …/revert{messageID,partID?}` / `…/unrevert` | `POST …/revert/stage{messageID(required ^msg_),files?}` → `{data:RevertState}`；**`DELETE …/revert`**（clear）；`POST …/revert/commit`（空）→ 204 | `V2Api.revertClear` 的 `POST …/revert/clear` 不存在；`partID` 只在 stage 响应里回（请求体无 partID，v1 的单 part revert 语义消失——按整消息）；`unrevert` 无对应（clear 即反悔） |
| fork | `POST …/fork{messageID?}` → 新 Session | `POST …/fork{before?(^msg_)}` → 新 Session（文档） | 字段改名 `messageID→before`；`GET …?parentID=` 可查 fork 树 |
| share 系 | `POST/DELETE …/share`、`time.archived` PATCH | **无**（openapi 无 share/archive；`time.archived` 仍在 SessionInfo 里只读出现） | v2 服务器之间分享/归档走 `snapshot`/`export`？`POST /api/experimental/session/{id}/export` 存在但语义 UNVERIFIED。Wave 1 决策：隐藏 share/archive 入口（v2 下），或仅本地过滤。`sessionShare/sessionUnshare/sessionArchive` 标 V1_ONLY |
| transcript | `GET …/message?limit` → `WithParts[]` | `GET /api/session/{id}/message?limit&order&cursor&type` → `{data:扁平union[],cursor}` | §1.2 形状；`order=asc` 拉全量（export/首屏分页用）；`type` 过滤是新能力；**v1 老 transcript 在 v2 message 里可能为空**（`V2Api` 注释称 hybrid 上测到过；2.0.11 上本会话有数据，未复现——Wave 1 若撞见则回退读 experimental log 重建） |
| 单条消息 | （无） | `GET …/message/{messageID}` | 新能力：纠错/定位单条 |
| active window | （无） | `GET …/context` → `{data:[union…]}`（compaction 后窗口，已测非空） | compaction 后刷新用它 |
| durable 回放 | （无） | `GET /api/experimental/session/{id}/log?after&follow`（SSE，`log.synced{aggregateID,seq?}` 开场） | §5；`history` 路由不存在 |
| 默认模型 | `GET /config` → `model` 串 | `GET /api/model/default?location=` → `{location,data:ModelEntry}`（已测） | composer chip 默认值首选；`GET /api/config` 另作设置页用（形状是**数组** `[{type:document,path,info}]`，不是对象！`Config` 全键在 `info` 里） |
| provider/model 目录 | `GET /provider`（`{all,default,connected}` + models map + variants Record） | `GET /api/provider` + `GET /api/model`（均 `{location,data[]}`）+ `GET /api/provider/{id}` | 三处断裂：① provider 条目 `{id,integrationID?,name,activation?,package?,settings?…}`（已测实物！`V2Api.ProviderInfo` 的 `api{type,package,url}/request/disabled` 是旧 hybrid 形状，**DTO 要重写**）；② model 条目 `{id,modelID,providerID,family,name,compatibility,package,settings,capabilities{tools,input[],output[]},variants[{id,…}],time,cost[{input,output,cache}],status,enabled?,limit?}`（已测键表；`V2Api.ModelInfo` 大体对，`limit{context,output}`、`enabled`、`status` 以本次键表为准再对一次）；③ variants 是**对象数组**（取 `.id`），v1 是 Record 键。`connectedProviders/model()` 选择逻辑重写 |
| agent/command/skill | `GET /agent`（`name,…native,variant`）、`GET /command`（`source`）、`GET /skill` | `GET /api/agent`（条目 `{id,name,request,description,mode,hidden,permissions}`——**有 `name`**，`V2Api` 注释放错了）、`GET /api/command`（`{name,description}`，已测无 `template`/`source`）、`GET /api/skill`（`{id,name,description,path,content}`） | agent 选择规则 `selectable = !hidden && (mode==primary|all)` 不变；command 以 `name` 为键（`submit.ts:findCommand` 的 `/name` 解析不变） |
| permission 待办 | `GET /permission`（裸数组，目录过滤） | `GET /api/permission/request?location=`（`{location,data[]}`）+ `GET /api/session/{id}/permission`（`{data[]}`，无 location） | 双视图：snapshot 合并时以 session 视图为准（去重 by id），location 视图做跨会话徽标；条目字段重命名见 §1.5 |
| permission 回复 | `POST /permission/{id}/reply{reply}` | `POST …/permission/{requestID}/reply{decision(required),message?}` → 204（UNVERIFIED：无 live ask） | 字段改名（§0.5）；`message` 是拒因备注；另有 `GET …/permission/{requestID}`（单条）、`POST …/permission`（创建 ask，body `{id?,action,resources,…}`——auto-approve 之外的"代问"新能力） |
| saved 规则 | （无） | `GET /api/permission/saved` → `{data:[string]}`（已测 `[]`）+ `DELETE …/saved/{id}` | "always" 规则可视化/删除新能力 |
| question 待办/回复 | `GET /question`、`POST …/reply{answers}`、`POST …/reject` | **无**（§0.4）→ form 体系 | `replyQuestion/rejectQuestion` 标 V1_ONLY；v2 下 question dock 改由 form 驱动 + question-tool 状态 |
| diff | `GET /session/{id}/diff?messageID?` | `GET /api/session/{id}/diff?from&to&context` → 200（已测，空会话 `size:11` 即 `[]`…实为 `{"data":[]}` 形） | 参数语义变（commit/消息区间，不是单 messageID）；review 面板 Git/Branch scope 另有 `GET /api/vcs/diff?mode&base&context` + `…/vcs/status` + `…/vcs/branch?search&limit`（v1 `GET /vcs`、`GET /vcs/diff?mode=` 的直接后继） |
| todo | `GET /session/{id}/todo` + `todo.updated` | **无**（openapi 无 todo） | todo 面板在 v2 下降级：从 transcript 的 todowrite tool 状态派生（`renderable` 本就过滤 todowrite，数据在 content 里）；`sessionTodos` 标 V1_ONLY |
| 文件读/找 | `GET /file?path=`（绝对路径）、`GET /file/content?path=`、`GET /find/file?query`（裸串数组） | `GET /api/fs/list?path=`、`GET /api/fs/read/<rel>`（裸字节！）、`GET /api/fs/find?query(required)&type?&limit=`（`{location,data:[{path,type}]}`） | 三处断裂：① 路径是**相对 location directory**（Windows 目录带尾 `\`，用 `location.directory` 拼接；`..` 可越界，已测）；② read 返回裸字节 + 扩展名 content-type（`fsRead` 的实现已对）；③ find 的 `query` 必填、无则 400 `InvalidRequestError{kind:Query}`，且是模糊排序（调用方不得做字面包含假设） |
| pty | `GET/POST /pty`、`GET/PUT/DELETE /pty/{id}`、`POST …/connect-token`、`WS …/connect` | `GET/POST /api/pty`、`GET/PUT/DELETE /api/pty/{id}`、`POST …/connect-token`（**必须带 `x-opencode-ticket: 1`**，值只能是 `1`，已测）、`WS …/connect?ticket&cursor` | `V2Api` 全套已对；`cursor=-1` 表只收新输出 |
| shell 服务 | （无独立服务，经 session.shell） | `GET/POST /api/shell?location=`、`GET /api/shell/{id}/output?cursor&limit` | 后台 shell 新能力（§6）；`Output{output,cursor,size,truncated}` 分页 |
| project | `GET /project`（裸数组） | `GET /api/project`（裸数组，已测 `[{id,canonical,time,sandboxes}]`）+ `PATCH /api/project/{id}{canonical?,name?,icon?,commands?}` | envelope **无**（别套 `dataObject`）；`listProjects` 直接换源 |
| mcp/plugin/credential | （无） | `GET /api/mcp?location=`、`GET /api/mcp/resource`、`POST /api/experimental/mcp/{server}/{connect,disconnect}`、`GET /api/plugin`（+check/update）、`GET /api/credential/{id}`（+activate） | 设置页/MCP 开关新能力（§6） |
| reference | （无） | `GET /api/reference?location=`（已测 `[]` envelope） | @ 提及的新候选源（UNVERIFIED 元素形状） |
| integration | （无） | `GET /api/integration`（`{location,data[{id,name,methods[],connections}]}`，v1.18  hybrid 称 222 条）+ OAuth/key connect 全家 | provider 登录流后继 |
| websearch | （无） | `POST /api/websearch?location=` + `…/websearch/provider` | websearch dock 后端 |
| worktree | （无） | `GET/POST/DELETE /api/worktree` + `…/worktree/refresh` | §6 |
| session 环境 | （无） | `PUT …/environment{variables}`、`POST …/move{directory(required),delivery?}`、`POST …/background`（空）、`POST …/synthetic{id?,text?,…}`、`POST …/view{idle(required)}`、`POST …/generate`（空，文档）、`GET …/inbox` + `DELETE/PATCH …/inbox/{inboxID}{delivery}` | move=跨目录搬会话（`session.moved`）；background=后台化（§6）；synthetic=注记消息；view=已读水位；generate=重跑？（UNVERIFIED）；inbox=pending 队列管理 |
| 统计/导入导出 | （无） | `GET /api/experimental/session/stats?from&to&project&timezone&tools`、`POST …/import`、`POST …/session/{id}/export`、`GET …/skill`（session 级 skill）、`PUT/DELETE …/instructions/entries/{key}`、`GET …/terminal(+read)`、`POST …/persistent-pty/handoff` 等 | Home 统计/导出/迁移新能力，按需排期 |

V1_ONLY 保留清单（v2 确无对应）：`sessionShare/sessionUnshare/sessionArchive`（share/archive）、
`replyQuestion/rejectQuestion`（question REST）、`sessionTodos`+`todo.updated`、
`promptAsync/shellCommand(旧包体)/runCommand(旧包体)/abort`（被上表新包体取代，函数可复名复用，
包体构造器 `promptPayload/shellPayload/commandPayload` 重写——字段映射见上表）。

### env/type 细节：`location` 参数写法

- catalog/工具类（agent/model/provider/command/skill/integration/reference/fs/mcp/config/vcs/shell/location）：
  `?location[directory]=`（deepObject explode；OkHttp 编码后 `%5B%5D` 可用，已测）。`V2Api.url(withLocation)` 已对。
- `GET /api/session` 的目录过滤是裸 `?directory=`（`V2Api.sessions(directoryScoped)` 已对）。
- 会话级路由（`…/message/context/prompt/model/agent/permission/form/…`）**不接受 location**（会话自带归属）。
- `GET /api/location` 本身接受 `?location=`（self，忽略即可）。

---

## 3. v2 bootstrap 序列（`ChatViewModel.start()` 的镜像）

```
probe: GET /api/info  → version startsWith "2." ? V2 : (GET /global/health → V1 : 离线)
  └─ 失败/HTML/无 version → 走 v1 旧链路（EventStream /global/event + 旧 snapshot），v2 对象全不建
snapshot（全部 {data} 解包；任一失败不阻断 SSE，先渲染已有）：
  1. GET /api/location                       → directory（location 默认值；失败则沿用配置目录）
  2. GET /api/session/{id}                   → title/directory/cost/tokens/agent/model（header + chip + cost）
  3. GET /api/session/{id}/message?limit=N&order=desc → transcript（upsert 合并，N 沿用 SNAPSHOT_MESSAGE_LIMIT；
     若空但 v1 有数 → 告警并回退 experimental log 重建，见 §2 message 行）
  4. GET /api/session/{id}/context           → active window（compaction 指示器/上下文环用它，不用 message 全量）
  5. GET /api/session/active                 → 他会话 busy 徽标/当前 RUNNING 交叉确认
  6. catalogs（并行，`{location,data}` 解包）：/api/agent, /api/model, /api/provider,
     /api/command, /api/skill, /api/model/default（chip 默认；v1 的 GET /config 回退链保留）
  7. GET /api/config                         → 设置页 + 全局默认（数组形状！`[{path,info}]`，key 在 info 里）
  8. GET /api/session/{id}/permission        → PermissionSnapshot（再扫一遍 location 视图做跨会话徽标）
     GET /api/form?location= + GET /api/session/{id}/form → form dock 初态（question 无 REST，只能靠这个）
  9. GET /api/session/{id}/diff              → review Changes 初值（VCS scope 另拉 /api/vcs/status）
SSE（两条，同 Basic auth；都不进 request-queue 预算）：
  A. GET /api/event                          → 全局：status/permission/form/mcp/worktree/vcs/provider/model/agent/
     project/shell/pty/server.connected（§1 全表）；首帧 server.connected 即"已连接"
  B. GET /api/experimental/session/{id}/log?follow=true → 本会话 durable 回放+续流（§5）
```

与官方客户端的对应：health check（`health.ts`：`server.info()`+30s 超时+2 次重试+750ms 缓存+10s 轮询）
→ 换成 `/api/info` 同参数；`flushInterval:16ms` 的合批思想保留（16ms 窗内同 messageID 的 delta 合并一次
`applyEvent`，DiffUtil 按 stream tick 比较的压力不变）；`location[directory]` scoping 换成上表 env 规则；
`pageLifecycle`（后台断流）对应 Android 前后台：进后台不断 SSE（现有策略）但切到 `active` 轮询，出后台先
`active` + `log?after=` 再 snapshot（§5）；optimistic（`handoffMessage` + `msg_` 自传 id + steer/queue
delivery + 失败回滚 `failSubmission`）全部保留，只是包体换扁平形状。

---

## 4. SSE 重连 / 回放策略（durable seq + after）

1. **序列号归属**：只有 durable 事件有 `durable{aggregateID,seq,version}`（per-aggregate 单调增；
   同一会话的 session.* 共享同一 seq 空间——`aggregate=sessionID`）。ephemeral（text/reasoning/tool-input
   delta、progress、status、compaction.delta）无 seq，断线即丢。
2. **重放载体**：`GET /api/experimental/session/{id}/log?after=<seq>`（string after；`follow=true` 则回放完不断流）。
   开场必为 `data: {"type":"log.synced","aggregateID","seq":N}`——**N 即"≤N 全有"的水位线**（已测 `seq:15`）。
   以 N 为准：本地 `maxSeq<N` 的 durable 缺口 → 用 `…/message` 全值覆盖对应消息（Ended 帧语义）；`>N` 不可能出现（出现即 bug，上报）。
3. **客户端水位维护**：`maxSeq` = 见过的最大 `durable.seq`（只统计本会话 aggregate；global 流里混入的他会话帧各算各）。
   另记 `lastTextState`（每个 assistant 最后一次 durable Ended 的全值 hash），用于检测"delta 累积值 vs Ended 全值"漂移。
4. **重连流程**：`server.connected`（重连后首帧必有）→ 开 `log?after=maxSeq&follow=true` → 回放 durable
   （按 seq 排序应用，`evt_` id 去重——`Event.ID` 全局唯一，`msg_` 幂等规则见 AGENTS.md V2 Session Core §2：
   同 id 同类型重放取首次）→ 回放排空（收到 `log.synced` 且其 seq==maxSeq 或流已续上 live）→
   用 `GET …/message?limit` 做一次**全值对账**（ephemeral 缺口只能这样补）→ 恢复 live。
   全局流 `/api/event` **无 after 参数**（openapi parameters:[]）：重连后只做 2、8、9 步的快照刷新
   （permission/form/catalogs/active），不做事件回放。
5. **IDLE 结算**：`execution.succeeded/failed/interrupted` 任一即结算（durable，可靠）；
   现有 `IDLE_GRACE_MS` + `lastPartEventAt` 后备保留（ephemeral status 在后台可能丢）。
6. **并发写**：`POST …/prompt` 响应 `{admittedSeq,…}`——把 `admittedSeq` 也喂给水位（`maxSeq=max(maxSeq,admittedSeq)`），
   防止"发出 prompt 但 SSE 还没回显"期间的重连把自己的 enqueue 重放成 duplicate（配合 inboxID 去重）。
7. **心跳与超时**：`: heartbeat` 约 10s（已测 25s 窗内 2 次）；读超时沿用 35s；退避 1s→30s；4xx×5 停连——
   全部沿用 `EventStream` 现状。`?after=` 的 seq 来自可信源（`log.synced`/durable 信封）才可发，
   禁止用本地计数器编造（`event.ts:15-19` 的 after-cursor 约束）。

---

## 5. v2-only 能力清单（含工作量估计；S <2d，M 2–5d，L >5d，均指原生端）

| 能力 | v2 载体 | v1 对应 | 估计 | 备注 |
|---|---|---|---|---|
| inbox 队列（steer/queue/取消/改 delivery） | `session.inbox.*` + `GET/DELETE/PATCH …/inbox[/{id}]` + prompt `delivery/resume` | 无 | **S** | store 加 pending-inbox 列表即可；UI 是 composer 下方 pending 条 |
| 后台任务 | `POST …/background` + `GET …/inbox` | 无 | **M** | 需要后台状态机 + 通知（`notifyFor` 扩展）；执行语义（wake/claim）是服务端事 |
| 表单 dock（含 MCP elicitation 的 `"global"` form） | `form.created/replied/cancelled` + `GET /api/form` + `…/form[/{id}[/reply]]` | 无（question dock 被取代） | **M** | 字段 6 类型 + `when` 联动 + external 跳链；`SessionQuestionDock` 改写为 form 渲染 |
| MCP 状态/工具刷新 | `mcp.*.changed` + `GET /api/mcp(/resource)` + experimental connect/disconnect | 无 | **M** | 设置页开关 + tool catalog 失效（`sync.tsx:135-147` 的 onMcp 镜像） |
| worktree 管理 | `worktree.updated/ready/failed`(+durable `worktree.resolved`) + `GET/POST/DELETE /api/worktree` + refresh | 无 | **M** | Home/项目层新入口；创建是分钟级长任务（官方 `setupRequestHeadersTimeoutMs=10min` 照抄） |
| shell 服务（后台命令+输出分页） | `shell.created/exited/deleted` + `GET/POST /api/shell` + `GET …/output?cursor&limit` | 只有会话内 shell turn | **M** | 终端 dock 的第二数据源；`Output{cursor,size,truncated}` 分页器 |
| 位置迁移/多会话视图 | `POST …/move` + `session.moved` + `session.viewed`/`POST …/view` + `GET …/message/{messageID}` | 无 | **S** | move 后换 directory 默认值；view 水位做他端已读 |
| synthetic 注记/环境变量 | `POST …/synthetic` + `PUT …/environment` + `session.synthetic` | 无 | **S** | 调试/注记入口；env 影响后续 turn |
| config 可读（设置页） | `GET /api/config`（数组！）+ `PATCH /api/experimental/config` | `GET /config`（对象） | **S** | 解析器按 `[{path,info}]` 重写；写操作 experimental，设确认框 |
| 默认模型/项目/VCS 全家 | `/api/model/default`、`/api/project(+PATCH)`、`/api/vcs/branch/diff/status` | `GET /config.model`、`GET /project`、`GET /vcs` | **S** | 一一映射（§2）；project envelope 缺失注意 |
| websearch 后端 | `POST /api/websearch` + provider | 无 | **S–M** | websearch dock 接真后端 |
| reference/index | `GET /api/reference` | 无 | **S** | @ 提及候选（元素形状 UNVERIFIED，先透传 raw） |
| integration/plugin/credential（登录流） | `/api/integration*` OAuth/key connect、`GET /api/plugin(+check/update)`、`credential/{id}/activate` | 无 | **M–L** | 外部浏览器回调（官方 `platform.openExternal` 镜像）+ 轮询 attempt |
| 会话 fork 树/导入导出/统计 | `?parentID=`、`POST …/import`、`POST …/export`、`GET …/stats`、instructions entries、terminal/persistent-pty handoff | 只有 fork/share | **M–L**（分开排） | export/import 先做（分享替代链）；stats 给 Home；handoff 最后 |
| `POST …/generate`、skill(session 级) | 文档有，语义不明 | 无 | **S（验证）** | Wave 1 先 `OPTIONS`/文档][]对齐再定 |

`sessionShare/sessionUnshare/sessionArchive`、`replyQuestion/rejectQuestion`、`sessionTodos`、
`promptAsync/shellCommand/runCommand/abort`（旧包体版）在 v2 下标记 V1_ONLY/删除，函数名可保留做分发。

---

## 6. Wave 1 实施顺序建议 + 验证清单（POST/DELETE 全部留给 Wave 1）

1. 探针：`V2Capability.probe` 改打 `GET /api/info`（`decideHealth` 换 `decideInfo`：JSON + `version` 以 `2.` 开头；
   保留"HTML/非 JSON ⇒ 非 v2"防御）。附带 `GET /api/location` 确认目录（已测可用）。
2. DTO：重写 `ProviderInfo`（`{id,integrationID?,name,activation?,package?,settings?}`）、校准 `ModelInfo`
  （本次键表为准）、`AgentInfo`（加回 `name`）、`CommandInfo`（`template` 置空容忍）、`SessionInfo`（已有可用）。
3. 写包体重写并在**一次性会话**上 POST 验证：prompt 扁平 `text`（vs `prompt:{}` 二选一，以 server 200 为准）、
   shell `{command}`、command `{name,text}`、interrupt、`revert/stage`→`DELETE …/revert`→commit 全链、
   `permission/{id}/reply{decision}`（需先造 ask：调 `POST …/permission` 自造一个再回，测完删）、
   form 创建→回复→删除全链、`compact`（危险：单独会话测）、`fork{before}`、`move`、`PATCH title`。
4. 读链：message/context/active/config-array/project-bare-array/vcs 全家/diff 新参/permission 双视图/form 初态。
5. 流：`/api/event` 全局（`server.connected` 即 connected；`location.directory` 过滤替代 `unwrapGlobal` 的 v1 分支，
   v1 分支保留）+ `experimental …/log?after&follow=true` per-session（§4 流程；`readEventStream` 复用，
   加 `log.synced` 水位处理）。
6. store：§1 事件折叠（status/error 形状兼容、delta 全值覆盖、inbox pending、form dock、Signal 增补）。
7. 删除/隐藏 v2 下无源入口（share/archive/todo/question-REST），`COVERAGE` 按 2.0.11 重测一遍并更新
   `V2Api`/`V2Capability` 注释里所有"SPA/不存在"结论（本报告 §2 表即新表底稿）。

## 附录：关键 live 证据（2026-09-21，4095/4096）

- `GET /api/info` → `{"version":"2.0.11","pid":67503,"urls":["http://127.0.0.1:4095"],"paths":{"tmp":"/tmp/opencode"}}`
- `GET /api/health` → `404` 空体；`GET /global/health`(4096) → `{"healthy":true,"version":"1.18.31"}`
- `GET /api/location` → `{"directory":"/home/xiufeigo","project":{"id":"0a95…","directory":"/home/xiufeigo","canonical":"/home/xiufeigo"}}`
- `GET /api/session?limit=2` → `{data:[{id:ses_0ed9…,projectID:global,agent:build,model:{id:big-pickle,providerID:opencode,variant:default},cost:0,tokens:{input…},time:{created,updated},title,subpath,location:{directory}}…],cursor:{previous,next}}`
- `GET /api/session/{id}/message?limit=3` → `{data:[assistant{content:[reasoning{text,time},text,tool{id,name:bash,state:{status:completed,input:{command},content:[{type:text}],metadata:{output,exit:0,truncated:false}},time}]},finish,cost,tokens]…],cursor}`；`order=asc` 下 user 形 `{id,time:{created},text,type:user}`
- `GET …/context` → `{data:[compaction{status:completed,reason:manual,summary,recent},user…]}`（非空）
- `GET /api/session/active` → `{"data":{}}`；`GET …/{id}/permission` → `{"data":[]}`；
  `GET /api/permission/request` → `{"location":{…},"data":[]}`；`GET /api/permission/saved` → `{"data":[]}`；
  `GET /api/question/request`、`GET …/{id}/question`、`GET …/history`、`GET …/{id}/event` → 全部 `404` 空体
- `GET /api/event`（25s）→ `server.connected` 首帧 + `: heartbeat`×2 + `provider.updated`/`model.updated`（`{location,data:{}}`）
- `GET /api/experimental/session/{id}/log?after=0`（±`follow=true`）→ 仅 `data: {"type":"log.synced","aggregateID":"ses_0ed9…","seq":15}`
- `GET /api/agent` → `{location,data:[{id:build,name:Build,request,description,mode:primary,hidden:false,permissions[…]}…]}`（7 条）
- `GET /api/model` 首条键 `capabilities,compatibility,cost,enabled,family,id,limit,modelID,name,package,providerID,settings,status,time,variants`
- `GET /api/provider` 首条 `{id,integrationID,name,activation,package,settings}`（8 条）；`/api/command` → `{location,data:[{name:init,description}…]}`；
  `/api/skill` 首条 `{id,name,description,path,content}`；`/api/model/default` → `{location,data:{…model}}`
- `GET /api/config` → `[{type:document,path:…/opencode.json,info:{…}}]`；`GET /api/project` → `[{id,canonical,time,sandboxes}…]`；
  `GET /api/vcs` → `{location,data:{branch:{}}}`；`GET …/{id}/diff` → `200` 空 data；`GET /api/pty` → `{location,data:[]}`
- `GET /openapi.json` → 113 paths（§2 路由存在性判定以此为准；`V2EventEncoded`/`SessionLogItemEncoded` 均为 opaque string schema）
