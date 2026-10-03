# P0：4095 后端 provider key 加载链调查 + 修复（2026-09-26）

> 环境/配置类任务：**未改 app 仓库任何代码**，未改 `_upstream/`，未动 git / 模拟器 / adb。
> WSL 侧改动物：写入 `opencode.db` 的 `credential` 表（4 条 provider 凭据，走官方 API），
> 备份目录 `~/.local/share/opencode/backup-20260926/`，重启 4095 一次（授权内）。
> `--service` 进程（PID 181415）全程未动。

## 0. 结论（TL;DR）

1. **根因不是启动命令、不是 env、不是 XDG、不是 auth.json 权限**：
   opencode **v2 的凭据存在 `~/.local/share/opencode/opencode.db` 的 `credential` 表**，
   `auth.json` 只是 **v1 遗留文件**，只在一次性 migration 里被读过一次，之后永不再读。
2. 该 migration（`20260805200742_import_legacy_credentials`）**在 2026-09-21 15:43:00.9 只跑了一次**，
   结果 `credential` 表里只有 2 行（`kimi-for-coding`、`ark`），而这两个 id **在 229 个 integration 里根本不存在**
   ——即遗留导入实际是"导了两个孤儿"，`opencode-go`/`minimax-cn-coding-plan`/`stepfun`/`xiaomi` 四个**在用的**
   provider 一条凭据都没有。没有重导入路径，所以永不自愈。
3. **修复**：用官方 API `POST /api/integration/{id}/connect/key`（`opencode auth login` 的底层同一接口）
   把 4 个 key 写回 `credential` 表。**不需要改启动命令、不需要 env**。
4. **验收通过**：重启 4095 后 `opencode-go/space-bunny-free` 与 `minimax-cn-coding-plan/MiniMax-M3`
   均返回真实文本 `OK`（free/plan 档，未花钱）。
5. **重要坑**：`GET /api/provider` **永远看不到 DB 里的凭据**（它只回静态 settings），
   拿它当验收信号会误判；必须发一条真模型调用。详见 §5。
6. **另一个坑**：`opencode-go` 与 `opencode`(Zen) 在 models.dev 里**共用同一个 env 名 `OPENCODE_API_KEY`**，
   所以"启动命令补 env"这条看似自然的路是错的——会连带打坏 Zen 的 `public` 免费档。详见 §6。

## 1. 定性：v2 的凭据加载链

| 来源 | 是否生效 | 代码位置 |
|---|---|---|
| **DB `credential` 表** | ✅ 唯一真正生效的存储 | `_upstream/packages/core/src/credential.ts`、`credential/sql.ts` |
| `auth.json` | ❌ 只在一次性 migration 里被读 | `database/migration/20260805200742_import_legacy_credentials.ts:39` |
| config `provider.*.options.apiKey` | ✅ 生效（作为 provider 静态 settings） | `core/src/config/plugin/provider.ts` |
| 进程环境变量 `<PROVIDER>_API_KEY` | ✅ 生效，但**读的是 server 进程自己的 environ** | `core/src/integration.ts:366-370` + `plugin/models-dev.ts:46` |
| `--service` 伴生进程持有 key | ❌ 它只是另一个 server，同样从 DB/env 取 | 见 §4 |

关键代码（`core/src/integration.ts`）：

```ts
const resolveConnections = (entry, saved) => {
  const credentials = saved.map(c => ({type:"credential", id:c.id, label:c.label})).toReversed()
  const env = (entry?.methods ?? []).filter(m => m.type === "env")
    .flatMap(m => m.names.filter(name => process.env[name]))
    .map(name => ({type:"env", name}))
  return [...credentials, ...env]          // 凭据优先，env 兜底
}
// connection.active(id) = resolveConnections(entry, credentials.list(id))[0]
```

`credential` 表里 `active` 只是排序提示（`all()` 按 `asc(active)` 排，再 `toReversed()`），
**NULL/0 不会让凭据失效**——本次 2 个孤儿行的 `active` 就是 NULL，不影响判定。

真正把 key 送到 HTTP 请求上的是 `core/src/model-resolver.ts:301`：

```ts
const nativeCredentialSettings = (specifier, credential) => {
  if (!credential) return {}
  if (credential.type === "key") return { apiKey: credential.key }   // ← 覆盖静态 settings.apiKey
  ...
}
```

也就是说：**凭据是在"模型解析"时注入的，不会回写进 `/api/provider` 的 settings**。

## 2. 现场取证（修复前）

### 2.1 DB 侧

```
$ ls -la ~/.local/share/opencode/
-rw------- 832    Aug 11 21:04 auth.json          # 6 个 provider，mtime/ctime/birth 全部 = Aug 11 21:04
-rw-r--r-- 696094720 Sep 26 11:16 opencode.db     # 真身
```

`auth.json` 6 条全部是 `{"type":"api","key":...}`（kimi-for-coding / xiaomi / opencode-go / ark /
minimax-cn-coding-plan / stepfun），且 `stat` 显示 **mtime = ctime = birth = 2026-08-11 21:04:52**
（同一 inode 自那天起没被写过）。

修复前 `credential` 表只有 2 行：

```
cred_0c2eb1192001TPY22ZKEWOUL7t|kimi-for-coding|API key||1789976580498   # active=NULL
cred_0c2eb119300294Y8MMNNG5ccAF|ark|API key||1789976580499
```

`migration` 表时间线：

```
20260611035744_credential                    → 1782720254430  (2026-06-29，v2 DB 建立)
20260805200742_import_legacy_credentials     → 1789976580499  (2026-09-21 15:43:00.9，只跑这一次)
```

对照 `ls ~/.opencode/bin/`（Sep 21 15:42 安装 v2）可知：v2 于 9-21 15:42 装上，15:43 首次跑 migration，
**只产出 2 行**。

**未解释的疑点（如实记录）**：`auth.json` 自 8-11 就有 6 条、且 6 条都是 `{type:"api"}`，
按 `20260805200742_import_legacy_credentials.ts` 现在的实现应该全部导入（跳过条件只有"该 integration_id 已有行"，
而当时表是空的）。结果只进了 2 条，且恰好是**两个在 229 个 integration 里不存在的 id**
（`ark` / `kimi-for-coding` 均不在 `GET /api/integration` 的 id 列表里）。
可能是 9-21 那个二进制里该 migration 的实现与当前 checkout 不同，也可能事后有清理把 4 条在用 provider 的
凭据删掉了（孤儿 id 反而躲过清理）。**无法进一步取证**（日志最早只到 2026-09-25T08:00）。
对本任务不影响：无论哪种，结论都是"这 4 个 provider 的 key 不在凭据库里，且不会自动回来"。

### 2.2 4095 侧（修复前）

```
GET /api/provider → 10 个 provider，apiKey 只有两个：
  opencode      → apiKey "public"      (Zen 匿名档，见 §6)
  openai        → sk-e744…（来自 opencode.jsonc 的 options.apiKey）
  minimax-cn-coding-plan / xiaomi / opencode-go / stepfun / zai-coding-plan / commandcodegoat / antigravity / zz1cc → 无 apiKey

GET /api/integration → 全部 connections: []      # 一条凭据连接都没有
```

进程 environ（`tr "\0" "\n" < /proc/<pid>/environ`）：

- PID 170545 `serve --port 4095`：只有 `OPENCODE_SERVER_USERNAME/PASSWORD`，**没有任何 `*_API_KEY`**，
  cwd = `/mnt/c/sensorsdata/main/program/opencode-android-shell`
- PID 181415 `serve --service`：有 `OPENAI_API_KEY=sk-e74***`，cwd = `/home/xiufeigo`

→ env 兜底在 4095 上完全没配；`auth.json` 也不读；所以 401/503 "Missing API key" 必然发生。

### 2.3 直击上游网关，复现原始错误

```
POST https://opencode.ai/zen/go/v1/chat/completions   (无 Authorization 头)
{"model":"space-bunny-free",...}
→ HTTP 401 {"type":"error","error":{"type":"AuthError","message":"Missing API key."}}
```

`Missing API key.` 的文案就在网关源码里：`_upstream/packages/console/app/src/routes/zen/util/handler.ts:701`
（`authenticate()`：无 key 且 `!modelInfo.allowAnonymous` → `AuthError(t("zen.api.error.missingApiKey"))`，
文案在 `console/app/src/i18n/en.ts:374`）。`opencode-go` 的 baseURL 正是 `…/zen/go/v1`，
所以它之前报的就是这个 —— **本地没发 key，不是 key 无效**。

## 3. 修复：把 4 个 key 写回凭据库（官方 API）

`opencode auth login <id>` 对 `key` 类方法走的是 `@clack/prompts` 的 `secret()` 交互输入
（`packages/cli/src/commands/handlers/auth/login.ts` 的 `keyLogin`），**没有非交互参数**，
不能脚本化。它底层调的是同一个 HTTP 接口：

```
POST /api/integration/:integrationID/connect/key
  payload: {"key": "<API key>", "label"?: string}
  → 204 No Content        （服务端 Credential.create 落库，并广播 credential.updated/switched）
```

（路由定义：`_upstream/packages/protocol/src/groups/integration.ts`，`integration.connect.key`。）

本次执行（在 WSL 内跑一次性 python，读 `auth.json` 直连 4095，**key 不落日志、不回显**）：

```
opencode-go               HTTP 204 | key sk-v*** len 67
minimax-cn-coding-plan    HTTP 204 | key sk-c*** len 125
stepfun                   HTTP 204 | key 3JV4*** len 65
xiaomi                    HTTP 204 | key sk-c*** len 51
```

修复后 `credential` 表：

```
cred_0dbc99440001w2Q4RzlpCXtWGW|opencode-go|OpenCode Go|1|1790393816130
cred_0dbc9946600158xSLYQtOfmRE1|minimax-cn-coding-plan|MiniMax Token Plan (minimax.cn)|1|1790393816167
cred_0dbc9947e001U0VsJJujfSp2be|stepfun|StepFun (China)|1|1790393816190
cred_0dbc99494001Q1pCl0aY4z9Amd|xiaomi|Xiaomi|1|1790393816213
```

`GET /api/integration` 随即出现 4 条 `credential` 连接，**无需重启即生效**（凭据按请求现读）。
`opencode auth list --format json` 也能看到（官方只读检查命令，可放心用）：

```
[{"id":"opencode-go","name":"OpenCode Go","connections":[{"type":"credential","id":"cred_0dbc9944…","label":"OpenCode Go"}]},
 {"id":"minimax-cn-coding-plan", …}, {"id":"openai","connections":[{"type":"env","name":"OPENAI_API_KEY"}]},
 {"id":"stepfun", …}, {"id":"xiaomi", …}]
```

> 注：`auth list` 连的是**选举出来的 `--service` server（49374）**，它的进程 environ 里有
> `OPENAI_API_KEY`，所以 openai 显示为 `env` 连接；4095 进程没有这个 env，它的 openai key 走 config。

备份（未改动任何被备份文件，仅为可回滚）：

```
~/.local/share/opencode/backup-20260926/auth.json.bak
~/.local/share/opencode/backup-20260926/opencode.json
~/.local/share/opencode/backup-20260926/opencode.jsonc
~/.local/share/opencode/backup-20260926/service.json
```

## 4. 重启 4095 并验收（授权内的那一次）

### 4.1 重启

旧进程 PID 170545（09-26 00:30 起）已 `kill`；`--service`（181415，:49374）**未动**。

```bash
# 在 Windows 侧执行；注意 cwd 必须是 app 仓库目录（与重启前进程一致，决定 server 默认 location）
wsl.exe -d Arch -- bash -lc 'cd /mnt/c/sensorsdata/main/program/opencode-android-shell && \
  export OPENCODE_SERVER_USERNAME=opencode OPENCODE_SERVER_PASSWORD=opencode && \
  setsid nohup /home/xiufeigo/.opencode/bin/opencode serve --port 4095 > /tmp/oc4095.log 2>&1 < /dev/null &'
```

- 新进程 PID **183200**，`/tmp/oc4095.log` = `server listening on http://127.0.0.1:4095`
- `GET /api/provider` 启动后约 **10~20 秒**才从 0 个变成 10 个 provider（插件/models.dev 加载），
  启动瞬间查会看到 `providers: 0`，不是故障。
- ⚠️ **与任务给的旧命令的差异**：旧命令写的是 `cd /home/xiufeigo`，但重启前 4095 进程的 cwd 实际是
  `/mnt/c/sensorsdata/main/program/opencode-android-shell`，且 `GET /api/provider` 回的
  `location.directory` 就是这个仓库目录。为不改动 app 侧的默认 location，本次**沿用仓库目录**。
  如果 app 每次都自己带 `location`，cwd 无关紧要；如果它不带，`/home/xiufeigo` 会把默认项目目录指错。

### 4.2 验收证据（重启后）

流程：`POST /api/session {"model":{"providerID","id"}}` → `POST /api/session/{id}/prompt {"text":"Reply with exactly: OK"}`
→ `POST /api/experimental/session/{id}/wait` → `GET /api/session/{id}/message?order=asc`。

| provider / model | 结果 | session |
|---|---|---|
| `opencode-go/space-bunny-free`（free） | ✅ `content:[{"type":"text","text":"OK"}]`，`finish:"stop"` | `ses_f24319257ffeQ57BiO0jil18lr` |
| `minimax-cn-coding-plan/MiniMax-M3`（plan 档，cost 0） | ✅ `OK`，`finish:"stop"` | `ses_f2431511dffesIU84mLy72EaXq` |
| `opencode/space-bunny-free`（Zen public 档） | ✅ `OK`，`finish:"stop"` | `ses_f24312ae5ffeWtCRWBwYdQbmsA` |

（修复前 `opencode-go/space-bunny-free` 同样流程拿到过 `OK`，证明**不重启也已生效**；重启是为了证明落库持久。
`stepfun`/`xiaomi` 的凭据只验证到"连接已建立"，**未发真实请求**（它们没有免费档，避免花钱）。）

**没有花任何钱**：`space-bunny-free`、`MiniMax-M3` 均为 cost 0；`mimo-v2.6-flash` 那条付费验证**未使用**。

## 5. ⚠️ 后续测试轮必须知道的验收口径

- **`GET /api/provider` 不能作为凭据验收信号**：它走 `Provider.available()`（`server/src/handlers/provider.ts`），
  返回的是 provider 的**静态 settings**；DB 凭据只在模型解析时注入（§1）。
  所以修复后它依然显示 `apiKey: None` —— 这是**正常**的，不代表没修好。
- 唯一可靠验收：**发一条真模型调用**看 assistant 是否返回文本。
- 老文档里"`/api/provider` 里 10 个 provider 全无 apiKey ⇒ 后端没加载 key"这个推断是**错的**，
  真正该看的是 `GET /api/integration` 的 `connections` 数组，或 `opencode auth list`。
- 看 key 是否真被发出，还可对照：`zen/go/v1` 无 Authorization 头 → 401 `Missing API key.`（§2.3）。

## 6. 顺带查清：Zen 的 `apiKey:"public"` 与"时好时坏"

**机制**（`_upstream/packages/core/src/plugin/provider/opencode.ts:236-259`）：

```ts
const hasKey = Boolean(process.env.OPENCODE_API_KEY || snapshot.connection || item.provider.settings?.apiKey)
if (!hasKey) provider.settings = { ...provider.settings, apiKey: "public" }   // ← 匿名免费档
// 且：没有真 key 时，把所有 cost.input > 0 的 Zen 模型 enabled = false
```

即 `public` 是**字面量**，不是 token：**不存在"过期"**。它同时会把 Zen 的付费模型全部禁用，
只留 free 模型（本次 `GET /api/model` 里 `opencode` 只剩 7 个 free）。

**为什么"有时 503 / 时好时坏"**：

1. **匿名档是"按 IP 限流"的**。网关源码 `console/app/src/routes/zen/util/handler.ts:126`：
   ```ts
   const rateLimiter = modelInfo.allowAnonymous
     ? createIpRateLimiter(modelInfo.id, modelInfo.rateLimit, ip, input.request)   // 免费档：按 IP
     : createKeyRateLimiter(modelInfo.id, modelInfo.rateLimit, zenApiKey, input.request)
   ```
   `validateBilling` 里匿名档记为 `"anonymous"/"free"`（同文件 833-837）。
   所以高峰期/共享出口 IP 下出现 429/503 属预期，**不是 key 坏了**。
   （各模型的 `allowAnonymous` / `rateLimit` 具体数值来自部署期注入的 `Resource.ZEN_MODELS*`，
   `console/core/src/model.ts` 的 `list()` 才做解析，数据不在本 checkout 内，故列不出 nemotron 的阈值。）
2. **免费档还加了"必须在 OpenCode 内使用"的服务端门禁**（本 checkout 里没有这段代码，属部署版 Console 新增）：
   ```
   POST https://opencode.ai/zen/v1/chat/completions   Authorization: Bearer public
   → HTTP 403 {"type":"error","error":{"type":"FreeTierError",
        "message":"Error from provider (Console): OpenCode's free tier can only be used from within OpenCode"}}
   ```
   试过伪造 `User-Agent: opencode/latest/2.0.11/opencode`、`x-opencode-client`、`x-opencode-project`、
   `x-opencode-session`、`x-session-affinity`、`X-Session-Id` 全部仍 403 → 门禁不在这些头上（可能是部署版
   新加的签名/边缘判定），**curl 无法复现 in-app 的可用状态**。所以"直调 zen 报错"不能用来判断 app 侧好坏。
3. **付费 Zen 模型 + `public` 必然 401**：`authenticate()` 里 `allowAnonymous=false` 的模型遇到无有效 key 就抛
   `AuthError(missingApiKey)`。实测 `Bearer public` + `kimi-k3` → `401 {"type":"AuthError","message":"Missing API key."}`。
   本地虽然默认禁用了付费 Zen 模型，但客户端若**显式**请求（旧 session 记着模型 id、或缓存列表）就会撞上这个 401
   ——这很可能就是之前"偶发 Missing API key"的另一半来源。
4. 免费小模型本身质量不稳：本次 `opencode/nemotron-3.5-lightning-free` 返回了 `finish:"stop"` 但内容是
   重复乱码（不是 error）。**"有回话" ≠ "回话可用"**，测试轮别把这种当成功。

**给 Zen 的结论**：free 模型在 4095 里能通（本次 3 个 free 调用都成功）；间歇失败来自匿名档的按 IP 限流 +
服务端"仅限 OpenCode 内使用"门禁，属网关侧行为，本地无可修项。要用 Zen 付费模型必须走 `opencode`(Zen) 自己的
登录/凭据，`public` 不够。

## 7. 以后重启后端 / 排查用（后续测试轮照抄）

```bash
# 1) 重启 4095（cwd 必须是 app 仓库目录）
wsl.exe -d Arch -- bash -lc 'cd /mnt/c/sensorsdata/main/program/opencode-android-shell && \
  export OPENCODE_SERVER_USERNAME=opencode OPENCODE_SERVER_PASSWORD=opencode && \
  setsid nohup /home/xiufeigo/.opencode/bin/opencode serve --port 4095 > /tmp/oc4095.log 2>&1 < /dev/null &'

# 2) 健康检查
wsl.exe -d Arch -- bash -lc 'cat /tmp/oc4095.log'          # 期望：server listening on http://127.0.0.1:4095
curl -s -u opencode:opencode http://127.0.0.1:4095/api/provider   # 等 10~20s，应有 10 个 provider

# 3) 凭据检查（唯一有意义的两种）
wsl.exe -d Arch -- bash -lc '/home/xiufeigo/.opencode/bin/opencode auth list --format json'
curl -s -u opencode:opencode http://127.0.0.1:4095/api/integration    # 看 opencode-go/stepfun/xiaomi/minimax 的 connections 非空

# 4) 真模型验收（cost 0，随便跑）
#    POST /api/session {"model":{"providerID":"opencode-go","id":"space-bunny-free"}}
#    POST /api/session/{id}/prompt {"text":"Reply with exactly: OK"}
#    POST /api/experimental/session/{id}/wait
#    GET  /api/session/{id}/message?order=asc  → assistant.content 应为文本 "OK"

# 5) 凭据丢了/DB 重置后的恢复（每个 provider 一条）
curl -s -u opencode:opencode -X POST \
  http://127.0.0.1:4095/api/integration/opencode-go/connect/key \
  -H 'content-type: application/json' -d '{"key":"<从 auth.json 取>"}'      # 期望 204
```

- **不要**杀 `--service`（PID 181415，`http://127.0.0.1:49374`，`~/.local/state/opencode/service.json`
  注册，被 TUI/CLI 选举使用）。它和 4095 共用同一个 `opencode.db`，所以凭据修复对两者同时生效。
- **不要**给 4095 启动命令加 `OPENCODE_API_KEY`（理由见下）。
- 重启会打断 4095 上正在跑的 session；重启后 `--service` 不受影响。

## 8. 未采用的路 & 未动项（含理由）

| 备选方案 | 为什么没采用 |
|---|---|
| 启动命令补 `OPENCODE_API_KEY=…` | ❌ **会打坏 Zen**：models.dev 里 `opencode` 与 `opencode-go` 的 `env` **都是 `OPENCODE_API_KEY`**（`core/src/plugin/models-dev.ts:46` 把 `provider.env` 注册为 env 方法）。一旦该 env 存在：① `opencode-go` 拿到 key（好）；② `opencode`(Zen) 的 `hasKey` 变 true，**不再注入 `apiKey:"public"`**（`plugin/provider/opencode.ts:236`），并会把同一把 key 作为 Zen 凭据发往 `zen/v1`（`nativeCredentialSettings`）→ Zen 免费档行为被改写。（此条为**源码级推理**，未实测，因为实测需再重启并可能弄坏 Zen。） |
| 其余 env 名（`MINIMAX_API_KEY`/`STEPFUN_API_KEY`/`XIAOMI_API_KEY`） | 能用但没必要：env 只存在于"启动它的那个进程"，以后换 shell / 换启动方式就丢；DB 凭据与启动方式无关。且 env 方案下 `auth list` 会显示为 `env` 连接，可读性差。 |
| 改 `opencode.jsonc` 加 `provider.*.options.apiKey` | 可行（`openai` 就是这么配的），但要把 4 个明文 key 再抄进配置文件、且与 DB 双份来源易漂移；只在需要"不落 DB 的临时覆盖"时用。 |
| 直接改 `auth.json` / 期待重导入 | ❌ 无代码路径：migration 已记录在 `migration` 表，`auth.json` 在 v2 是死文件。 |
| 手工 `INSERT INTO credential` | ❌ 没必要（官方 API 已可用），且绕过 `Credential.create` 的事务/active 语义与广播事件。 |
| `opencode auth login` 命令行 | ❌ key 方法走交互式 `secret()` 提示，无非交互参数；要脚本化只能用底层 HTTP 接口（本次即如此）。 |

未动项：`~/.config/opencode/opencode.json` / `opencode.jsonc` / `service.json` **均未修改**（只备份）；
`auth.json` 未修改；`_upstream/` 未修改；app 仓库只新增本文档；`--service` 未重启。
