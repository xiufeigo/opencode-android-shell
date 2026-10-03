# P0 追踪：换网关不换模型实验（muse-spark 地域绕行，2026-09-24）

> 环境/配置类任务：**未改 app 仓库任何代码**，未改 `_upstream/`，
> 未改 WSL 侧任何配置（`opencode.json/jsonc` 均未动），**未重启 4095**
> （步骤 2 的触发条件“有可用网关”未满足，授权未使用）。
> 4095 只做了只读 GET + 6 个探测 session 的 `DELETE` 清理。

前置报告：`research/p0-region-block-muse-spark.md`（zen 网关边缘 403 RegionError，只看出口 IP）。

## 1. 根因（一两句）

换网关路线走不通：唯一托管同款 muse-spark 模型的第三方网关
`api.commandcode.ai` 需要其自有 API key，而用户配置、serve 进程环境、
登录 shell 环境、`auth.json` 里**都没有这个 key**（`401 UNAUTHORIZED`）；
另一个有 key 的网关 `d1studio.top` 只卖 GPT 模型，无 muse-spark。
结论维持上一轮：**需非 CN 出口/代理**。

## 2. 网关实测结果（各 1–2 次，未刷配额）

| 网关 | 模型清单探测 | 回话探测（`Reply with exactly: OK`） | 结论 |
|---|---|---|---|
| `commandcodegoat` → `https://api.commandcode.ai/provider/v1`（`opencode.jsonc` 已配 provider，无 key） | `GET /models`（免 key）可列：含 `meta/muse-spark-1.1`、`1.2`、`1.2-contributor`、`1.3`、`1.3-contributor`，即**同款模型确实有货** | `POST /chat/completions` 与 `POST /messages`（`meta/muse-spark-1.3-contributor`）各 1 次 → 均为 `401 {"code":"UNAUTHORIZED","message":"Invalid 'Authorization' header or token."}` | 有模型、无 key，**不可用** |
| `openai` → `https://www.d1studio.top/v1`（`opencode.jsonc` 内嵌用户自有 key `sk-e744…`，仅用于本次 `/models` 查询） | `GET /models`（带 key）返回 27 个：全 GPT 系（`gpt-5.x` 等）；`spark` 关键字仅命中 `gpt-5.3-codex-spark`（GPT 家族，**不是** Muse Spark）；无任何 `muse-*` | 未做（连模型都没有，免得浪费用户配额） | 无同款模型，**出局** |

Key 缺失的核对链（只查名、不读值，未翻任何私密文件）：
`opencode.jsonc` 的 `commandcodegoat.options` 无 `apiKey`；
4095 serve 进程（PID 269）`/proc/<pid>/environ` 变量名清单无任何 key 类变量；
登录 shell `env` 同样无；`auth.json` 只有
`kimi-for-coding/xiaomi/opencode-go/ark/minimax-cn-coding-plan/stepfun`。
上游机制佐证（只读参照）：`_upstream/packages/ai/AGENTS.md:117`——openai-compatible
类 provider 按 `AuthOptions.bearer(options, "<PROVIDER>_API_KEY")` 解析，
即显式 `apiKey` 或 `COMMANDCODEGOAT_API_KEY` 环境变量，
两处皆空则请求无鉴权头，401 是必然结果。

## 3. 附带发现：zen 网关并非全 block（新证据）

清理前复核 6 个 `region-probe*` session 时发现，其中 `region-probe2`
（`ses_f2d25d1c…`，已删）用的是**非受限模型** `opencode/space-bunny-free`，
prompt `Reply with exactly: OK` 拿到了真实回复 `OK`
（`finish: stop`，`tokens: {input: 8550, output: 1}`，含一条 reasoning）。
证明同一 CN 出口下 zen 对未 region 限的模型服务正常，
地域块是**按模型**施加的（muse-spark 全系被拦），与上一轮“边缘按 IP+模型判定”的结论一致。

## 4. 清理：6 个探测 session 已删（均为 204）

`GET /api/session` 按标题 `region-probe*` + 相邻 3.5 分钟时间窗核对后删除，
其余 50 个 session（多为其它 agent 的 sleep/shell 实验）**未动**：

- `ses_f2d2597fcffejHWWGuQajeHFce`（region-probe3，muse-spark-free 403）
- `ses_f2d232376ffeCsdfjlkpL03c8m`（region-probe-go，muse-spark-go 403）
- `ses_f2d265029ffexWB4k8MJ0Nu1W3`（region-probe，空）
- `ses_f2d263f48ffe7z6BMGUEJy2ceV`（region-probe，空）
- `ses_f2d25d1cbffe1lvNhLF9w8HQ4S`（region-probe2，space-bunny 回了 OK，见 §3）
- `ses_f2d265df1ffeT1gLJwGQ6EoDfl`（region-probe，空）

注：任务描述说“4 个”，实际同标题/时间窗的有 6 个（多出的 2 个见上），
内容核对无误后一并删除；删除后复查 `region-probe` 残留 = 0。
拿不准未删的：无（其它标题的 session 一律未碰）。

## 5. 未决风险 / 后续（一句话版：都要用户侧给东西）

1. 若用户能提供 commandcode.ai 的 API key（配进 `opencode.jsonc` 的
   `commandcodegoat.options.apiKey` 或 `COMMANDCODEGOAT_API_KEY` 后重启 4095），
   本实验可立即重做步骤 2；但注意模型 ID 仍须是
   `opencode/muse-spark-1.3-contributor-free` 或
   `opencode-go/muse-spark-1.3-contributor` 之一，
   而 commandcode 侧的模型叫 `meta/muse-spark-1.3-contributor`——
   `opencode`/`opencode-go` provider 的 baseURL 覆盖会把 zen 侧模型 ID
   原样送过去，网关大概率报 `model_not_found`，届时需用户先松绑模型 ID 口径。
2. 在拿到 key 或非 CN 出口之前，两个允许模型的 V2 全流程验收**不可能通过**，
   不要反复 prompt 重试（烧 zen 配额且无意义）。
3. `d1studio.top` 的内嵌 key 本次仅用于一次 `/models` 列表查询，
   未做任何聊天调用，未消耗其实质配额；报告里 key 已打码。
