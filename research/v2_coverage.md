# v2 `/api/*` 覆盖表（Wave 1 实测，接线契约）

> Supersedes the pre-Wave-1 version of this file (which probed the 1.18.31
> hybrid on :4096, where unimplemented `/api/*` answered SPA HTML).
> Re-probed live 2026-09-21 against **2.0.11 @ `http://localhost:4095`**
> (Basic `opencode:opencode`) — GET-only — plus that server's live
> `/openapi.json` route table (**113 paths**). The normative table is
> `V2Capability.COVERAGE` in code; this file is its human-readable mirror.

## 探针规则（binding contract, A5 §0.1）

| 服务器 | 信号 | 实测 |
|---|---|---|
| v2 (2.0.11 @ 4095) | `GET /api/info` → JSON `{"version":"2.0.11",…}` | version 以 `2.` 开头 ⇒ V2 |
| v1 (1.18.31 @ 4096) | `GET /global/health` → `{"healthy":true,"version":"1.18.31"}` | 有 version 字段 ⇒ V1 |
| 任一方向 miss | SPA HTML / 非 JSON / 无 version / 网络失败 | UNKNOWN（保持既有行为，下次连接重探） |

反选保证（实测）：v2 上 `/api/health` 是 **404 空体**，所有 v1 端点回 SPA
HTML；v1 上 `/api/info` 是 `200 text/html`（SPA）。V1Transport 永不选中 v2
服务器，反之亦然。

## 结论一：v2 已实现的能力族（本 App 可直接用，Wave 1 已接）

- 探针/发现：`/api/info`、`/api/location`（裸 `LocationInfo`，无 envelope）
- 会话：`GET /api/session`（`{data[],cursor?}`；目录过滤用裸 `?directory=`；
  另有 `parentID` fork 树查询）、`POST /api/session`（`{data:SessionInfo}`）、
  `GET/PATCH/DELETE /api/session/{id}`（改名 body 仅 `{title\|null,
  permissions\|null}`；删除 204）、`GET /api/session/active`
- 文本：`GET …/message?limit&order&cursor&type`（`{data:扁平union[],cursor}`；
  另有单条 `GET …/message/{messageID}`）、`GET …/context`（active window）、
  `GET …/diff?from&to&context`（`{data:FileDiff[]}`，messageID 参数已消失）
- 写：`POST …/prompt`（**扁平** `{text(required),files?,agents?,skills?,
  metadata?,delivery?,resume?}`；旧 `{prompt:{text}}` 包裹 400）、
  `POST …/shell{id?,command(required)}`、`POST …/command{name,text(required),
  files?,agents?,skills?,delivery?}`、`POST …/interrupt?resume=`（200
  `{interrupted}`）、`POST …/compact{id?,delivery?}`、
  `POST …/agent{agent}`、`POST …/model{model}`、
  `POST …/revert/stage{messageID,files?}` →
  `{data:{messageID,…}}`、`DELETE …/revert`（clear；`POST …/revert/clear`
  不存在）、`POST …/revert/commit`（空 body）、`POST …/fork{before?}`
  （`messageID` 改名；空会话 fork 400 `empty_session`）
- 许可：`GET /api/permission/request`（`{location,data[]}`）、
  `GET …/permission`（`{data[]}`，无 location）、`GET …/permission/{requestID}`、
  `POST …/permission`（自造 ask `{action,resources,…}` →
  `{data:{id, effect:allow}}`）、`POST …/permission/{requestID}/reply`
  （**`{decision: once\|always\|reject, message?}`**；`{reply}` 400）、
  `GET/DELETE /api/permission/saved(/{id})`
- 表单（v2 取代 question）：`GET /api/form`（`{location,data[]}`）、
  `GET/POST /api/session/{id}/form`、`GET …/form/{formID}`、
  `POST …/form/{formID}/reply{answer}`（204；`DELETE …/form/{id}` 已回复后 409，
  无 dismiss 路由）
- 目录：`GET /api/agent`（条目**有 `name`**）、`GET /api/command`
  （`{name,description}`，无 `template`/`source`）、`GET /api/skill`
  （`{id,name,description,path,content}`，`location`→`path`）、
  `GET /api/provider`（`{id,integrationID?,name,activation?,package?,settings?…}`）、
  `GET /api/provider/{id}`、`GET /api/model`（含 `modelID/compatibility/package/
  settings/time` 新键；`variants:[{id,…}]`）、`GET /api/model/default`
  （`{location,data:ModelEntry}`）、`GET /api/config`（**裸数组**
  `[{type,path,info}]`）、`GET /api/project`（**裸数组**
  `[{id,canonical,time,sandboxes}]`，+ `PATCH /api/project/{id}`）、
  `GET /api/vcs`（`{location,data:{branch:{}}}`）、`GET /api/vcs/branch|diff|status`、
  `GET /api/mcp`（`{location,data:[{name,status}]}`）、`GET /api/plugin`、
  `GET /api/shell`（`{location,data:[]}`）、`GET /api/worktree`
  （`?projectID` 必填，否则 400）、`GET /api/integration`、
  `GET /api/reference`（`{location,data:[]}`）
- 流：`GET /api/event`（首帧 `server.connected`；混入各目录 durable 帧；
  `: heartbeat` 保活）＋
  `GET /api/experimental/session/{id}/log?after=&follow=true`
  （开场 `log.synced{aggregateID,seq}` 水位；帧 envelope
  `{id,type,created,data,durable?,location?,metadata?}`，无 `event:`/`id:` 行）
- 会话环境：`PUT …/environment{variables}`、`POST …/move{directory,delivery?}`、
  `POST …/view{idle}`、`POST …/synthetic`、`POST …/background`、
  `GET …/inbox`（`{data:[]}`）、`POST /api/experimental/session/{id}/wait`

## 结论二：v2 确无、保留 V1_ONLY 的能力

| 缺失的 v2 路由 | 我们 App 的等价能力 | Wave 1 决策 |
|---|---|---|
| `/api/question*`、`/api/session/{id}/question*`（全部 404，openapi 无） | v1 question dock | V1_ONLY；v2 下 form 替代（Wave 2 渲染） |
| `/api/session/{id}/history`、`/api/session/{id}/event`（404，openapi 无） | 无（v1 无回放） | 回放载体改为 experimental log 流 |
| `/api/session/{id}/wait`（仅 experimental 前缀有） | 无（本地 grace 模拟） | 用 experimental 路径 |
| share/archive（openapi 无；`time.archived` 只读出现） | `sessionShare/Unshare/Archive` | V1_ONLY（v2 分享走 export，Wave 2） |
| todo（openapi 无 `todo`） | `sessionTodos` + `todo.updated` | V1_ONLY（v2 从 todowrite tool 派生，Wave 2） |
| v1 `prompt_async/shell/command/abort` 旧包体 | `promptAsync/shellCommand/runCommand/abort` | V1_ONLY（函数保留做 v1 分发） |

## Envelope / Query 总规则（已测）

catalog/工具类 → `{location, data}`；session/message/permission/saved →
`{data}` 无 location；`message` 另带 `cursor{previous,next}`（opaque
base64，不可与 order 混用）；`project`/`config` 是**裸数组**；写操作多为
**204 空体**；错误为 JSON `{_tag,…}`（`SessionNotFoundError`、
`PermissionNotFoundError`、`ProviderNotFoundError`、`SessionBusyError`、
`InvalidRequestError{kind}` 实测）。catalog 类用 `?location[directory]=`
（deepObject explode）；`session.list` 用裸 `?directory=`；会话级路由不接受
location。

## 复现命令（GET-only；写验证见 wave1-notes.md §验证）

```bash
A='Authorization: Basic b3BlbmNvZGU6b3BlbmNvZGU='
curl -s http://localhost:4095/api/info -H "$A"            # {"version":"2.0.11",…}
curl -s http://localhost:4096/global/health               # {"healthy":true,"version":"1.18.31"}
curl -s http://localhost:4095/openapi.json -H "$A"        # 113 paths
for e in config model/default project vcs mcp form shell plugin worktree; do
  curl -s -o /dev/null -w "%{http_code} $e\n" http://localhost:4095/api/$e -H "$A"
done
```
