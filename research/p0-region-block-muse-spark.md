# P0：muse-spark 地域限制 + Token Plan 配额来源调查（2026-09-24）

> 环境/配置类任务：**未改 app 仓库任何代码**，未改 `_upstream/`，未改 WSL 侧任何配置/安装产物，
> 未重启 4095（`opencode v2.0.11`，serve 自 08:01 起一直运行）。4095 只做了只读 GET + 建 session/
> 发 prompt 的复现探测（验收所必需的最小写操作）。

## 1. 复现（两个允许模型全部命中，100% 稳定）

V2 流程：`POST /api/session` → `POST /api/session/{id}/model`
`{"model":{"id":"…","providerID":"…"}}` → `POST /api/session/{id}/prompt` `{"text":"…"}`。

- `opencode/muse-spark-1.3-contributor-free`
  （session `ses_f2d2597fcffejHWWGuQajeHFce`，`GET /api/session/{id}/message?order=asc`）：
  ```json
  {"type":"assistant","agent":"build",
   "model":{"id":"muse-spark-1.3-contributor-free","providerID":"opencode","variant":"default"},
   "content":[],"finish":"error",
   "error":{"type":"provider.auth","message":"This model is not available in your country.","status":403}}
  ```
- `opencode-go/muse-spark-1.3-contributor`
  （session `ses_f2d232376ffeCsdfjlkpL03c8m`，同法读取）：
  ```json
  {"type":"assistant","agent":"build",
   "model":{"id":"muse-spark-1.3-contributor","providerID":"opencode-go","variant":"default"},
   "content":[],"finish":"error",
   "error":{"type":"provider.auth","message":"This model is not available in your country.","status":403}}
  ```
- 另外留了两个空探测 session：`ses_f2d265029ffexWB4k8MJ0Nu1W3`、`ses_f2d263f48ffe7z6BMGUEJy2ceV`（无影响，可删）。
- `/tmp/oc4095.log` 无请求级日志（只有 `server listening on http://127.0.0.1:4095`），后端本身健康。

## 2. 根因：上游网关按出口 IP 地域拦截，与本地无关

1. **错误串不在 opencode 源码里**：`_upstream/` 全仓 grep `not available in your country`
   无命中（`packages/` 下 `*.ts` 搜 `country` 也只有 Meta tool 的 `userLocation` 等无关项）。
2. **分类器只是透传**：`_upstream/packages/ai/src/provider-error.ts:161`
   `status 403 → AuthenticationError`，即 API 呈现的 `provider.auth`；message 是上游原文。
3. **绕过本地后端直击网关，同样 403**（WSL 内，**无任何鉴权头**）：
   ```
   POST https://opencode.ai/zen/v1/chat/completions
   {"model":"muse-spark-1.3-contributor-free",...}
   → HTTP 403 {"type":"error","error":{"type":"RegionError",
        "message":"This model is not available in your country."}}
   ```
   结论：地域判定在 **opencode.ai zen 网关边缘、鉴权之前**，只看 TCP/TLS 来源 IP，
   与账号、与本地 opencode 二进制/配置都无关。
4. **出口 IP 即原罪**：WSL 出口 `223.73.218.100`，`ipinfo.io/country` 返回 `CN`；
   WSL 与 Windows 侧均无代理环境变量，Windows 常用代理端口
   （7890/7891/10808/10809/8080/3128/1080）无任何监听。
5. **catalog 无 region 字段**：`GET /api/model`（共 132 个）中该模型条目只有
   `settings: {"apiKey":"public","baseURL":"https://opencode.ai/zen/v1"}`（go 版为
   `…/zen/go/v1`），无 region/baseURL 替代项可配。`~/.config/opencode/opencode.json`
   对两 provider 只有 `blacklist`（本任务允许的两个模型不在黑名单内，属“被留下”的）。

## 3. 已试的解除手段（全部无效，均有记录）

| 手段 | 结果 |
|---|---|
| 换 provider 路径 `zen/v1` → `zen/go/v1`（第二个允许模型） | 同样 403 RegionError |
| 伪造 `X-Forwarded-For: 8.8.8.8` + `CF-IPCountry: US` 直调 zen | 同样 403（边缘按连接 IP 判定，头不可伪造） |
| 查本地代理（env + 端口扫描） | 不存在可用代理 |
| 找替代 baseURL / region 配置项 | catalog 与配置中均不存在 |

**未动项说明**：安装产物 JS 无需 grep（直调网关已定罪，本地无责）；
未打补丁（任何本地补丁都变不出模型回复）；未重启 4095（后端健康，重启无意义；
授权未使用）。header/baseURL/环境变量三条路已证伪——IP 地域是唯一变量。

## 4. 验收结论：未通过（非本地可修）

两个允许模型均未拿到真实回复，持续返回 403 RegionError。
**还缺的东西（二选一，需用户侧提供，本机无法变出）：**
1. 非 CN 出口网络：给 WSL 一个可用的 HTTP(S)/SOCKS 代理（需真实境外出口），
   然后以 `HTTPS_PROXY/HTTP_PROXY/ALL_PROXY`（或 `https_proxy` 小写）启动 4095
   serve 即可——opencode 系 bun/Node 运行时走系统代理变量；
   代理就绪后验收命令：切 model → prompt `Reply with exactly: OK` → assistant 应返回文本而非 error。
2. 或把账号/调用搬到境外网络环境执行。

## 5. Token Plan 用量上限 429 来源定位

- 来源：**opencode.ai zen 网关（账号级配额），不是本地网关/后端**。代码证据：
  - `_upstream/packages/ai/src/provider-error.ts:55-66` 注释明示：
    `OpenCode Zen reports account caps as typed 429/402 errors that are not throttles`；
    `402` 或（`429` + 配额文本 `insufficient-quota|quota-exceeded|budget exceeded|usage limit`、
    或 code 命中 `insufficient_quota/usage_not_included/billing_error/
    gousagelimiterror/freeusagelimiterror/creditlimitexceeded`）→ `QuotaExceededError`；
    纯 429（无配额信号）→ `RateLimitError`（可重试的节流）。
  - 网关侧错误目录 `_upstream/packages/console/app/src/i18n/en.ts`
    `zen.api.error.*`：`subscriptionQuotaExceeded`、
    `goSubscriptionRollingLimitExceeded`（5 小时窗）、
    `goSubscriptionWeeklyLimitExceeded`（周）、`goSubscriptionMonthlyLimitExceeded`（月）、
    `noPaymentMethod`、`insufficientBalance`、workspace/user 月度 spending limit、
    `rateLimitExceeded`（纯节流，重试即可）。
  - 口径：`Go models are hosted in the US`（同文件 `go.faq.a5`）——顺带解释了地域块的另一半：
    美国托管模型 + CN 出口 IP。
- 与本次 403 的区别：403 RegionError 是地域拒绝（换 IP 即解）；
  429/402 配额类是账号用量上限（换 IP 无用，要走下面的解除条件）。
- 本次调查**未实际触发 429**（配额未耗尽，且不允许故意打爆配额），以上为源码级定位。

### Token Plan 配额类 429/402『解除条件』清单（无法充值情况下的全部出路）

1. **等滚动窗口重置**：按错误体里的 `retryIn` 等待——5 小时窗 / 周 / 月三档；
   免费模型另有 `200 requests/day` 日配额（`go.faq.a9`）。
2. **用余额抵扣（不断用量）**：`enable usage from your available balance`
  （`consoleGoUrl`），需账户已有余额——无余额则回到第 4 条。
3. **升档/加购**：Go 订阅（$5 首月后 $10/月，用量上限远高于免费档）或 top up credit；
   无支付方式会先报 `noPaymentMethod`，余额不足报 `insufficientBalance`。
4. **调高 spending limit**：workspace 月度上限 / user 月度上限在 billing/members 页可调
   （需管理员权限，调的是上限不是充值）。
5. **换免费配额**：切到免费模型（Big Pickle 等，每日 200 请求配额）应急。
6. **绕开 zen 计费**：用自有 key 建自定义 provider（如已有 OpenAI/Anthropic key，
   `opencode.json` 配 `provider.*.options.apiKey/baseURL`），不再消耗 zen 配额。
   （与 P0 地域任务无关，未实施，只记录。）
7. **纯节流型 429**（`rateLimitExceeded`，无 quota 文案）：等一会儿重试即可，不是配额问题；
   区分方法是看 message 是否含 quota/usage-limit/balance 文案或 code。

## 6. 附：只记录（名下文件之外 / 未授权动项）

- `~/.config/opencode/opencode.jsonc` 配了第三方 `commandcodegoat`
 （`https://api.commandcode.ai/provider/v1`，含 `meta/muse-spark-1.3-contributor` 同权重模型）
  与 `openai.baseURL → https://www.d1studio.top/v1`（内嵌第三方 key）。
  是否走这些通道能绕开地域块**未测试**（任务明令只许用两个模型，动用即算“绕过”；
  且会消耗第三方配额）。若用户明确授权，可作为后续试验项，但验收口径需用户先松绑。
- 两个 provider 的 `blacklist` 很长（opencode 60+ 项），当前 132 模型是过滤后的；
  与本次故障无关。
