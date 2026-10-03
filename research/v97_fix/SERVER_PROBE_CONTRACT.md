# SERVER PROBE CONTRACT — 为什么 app 拒收 Lead 起的 `opencode serve`

> v121 诊断报告 · 只读代码 + 只读命令 + 自起临时 serve 实测 · 未改任何生产代码 / 测试
> 仓库：`C:\sensorsdata\main\program\opencode-android-shell` · 日期 2026-10-04

## 0. 一句话结论

**契约就是 `GET {base}/api/info` 必须是 `200` + JSON 对象 + `version` 字段以 `"2."` 开头**；
Lead 那台 `opencode` 实际是 **1.18.33**（npm shim 遮蔽了本机已有的 2.0.14），`/api/info` 返回 **SPA HTML**，
在第 4 条（`text/html`）上就断了 ⇒ `InvalidResponse` ⇒ 那句文案。
**结论 ① 可自解**：换用 `C:\Users\xiufe\.bun\bin\opencode2.exe`（v2.0.14）起 + 填它打印的口令，全清单实测已过。

---

## 1. 判定代码（`文件:行号` + 原文）

### 1.1 判定入口：first-run "Connect to a server" 页

`app/src/main/java/com/xiufeigo/opencodeshell/ui/servers/ConnectServerScreen.kt:99`

```kotlin
val probe = remember { ServerHealthProbe() }
```

`ConnectServerScreen.kt:121-130`

```kotlin
val result = withContext(Dispatchers.IO) {
    probe.check(HealthTarget(url = address, username = null, password = password))
}
checking = false
if (!result.healthy) {
    // `server.connect.failed` (`screen.tsx:25-30,40-43`): never
    // silent, never an exception.
    error = healthFailureMessage(result.failure)
    return@launch
}
```

> 结论：**引导页 = `ServerHealthProbe.check`**，失败文案由 `healthFailureMessage(failure)` 决定。
> settings 侧（`ui/settings/ServersPanel.kt:184,614,624`）走同一个 probe，所以两个入口文案逐字相同——与实测一致。

### 1.2 探测路径常量

`app/src/main/java/com/xiufeigo/opencodeshell/ui/servers/ServerHealth.kt:37-41`

```kotlin
/** `GET /api/info` — the single probe leg, judged by [V2Capability.decideInfo]. */
const val V2_INFO_PATH: String = "/api/info"

/** Probe budget. One request, ≤3 s (E1/D3 requirement). */
const val HEALTH_TIMEOUT_MS: Long = 3_000L
```

`ServerHealth.kt:224-228`（拼 URL + 探一次）

```kotlin
fun check(target: HealthTarget): HealthResult {
    val url = healthUrl(target.url, V2_INFO_PATH)
        ?: return unhealthy(HealthFailure.InvalidUrl)
    return attempt(url, target)
}
```

`ServerHealth.kt:265-270`（地址合法性：`http://` / `https://` 前缀，否则 `InvalidUrl`）

```kotlin
internal fun healthUrl(baseUrl: String, path: String): HttpUrl? {
    val base = baseUrl.trim().trimEnd('/')
    if (base.isEmpty()) return null
    if (!base.startsWith("http://") && !base.startsWith("https://")) return null
    return (base + path).toHttpUrlOrNull()
}
```

`ServerHealth.kt:239-261`（单次 GET；`Authorization` **仅在口令非空时**带上；3 s 预算）

```kotlin
private fun attempt(url: HttpUrl, target: HealthTarget): HealthResult {
    val builder = Request.Builder().url(url).get()
    val authorization = basicAuthorizationHeader(target.username, target.password)
    authorization?.let { builder.header("Authorization", it) }
    return try {
        http.newCall(builder.build()).execute().use { response ->
            healthResult(
                response.code,
                response.header("Content-Type"),
                response.body?.string(),
                credentialsSent = authorization != null,
            )
        }
    } catch (_: SocketTimeoutException) { unhealthy(HealthFailure.Timeout) }
      catch (_: InterruptedIOException)  { unhealthy(HealthFailure.Timeout) }
      catch (_: IOException)            { unhealthy(HealthFailure.Unreachable) }
}
```

`app/src/main/java/com/xiufeigo/opencodeshell/BasicAuth.kt:26-34`（用户名缺省 = 字面量 `opencode`；空口令 = 不发 header）

```kotlin
internal fun basicAuthorizationHeader(username: String?, password: String?): String? {
    val secret = password?.takeIf { it.isNotEmpty() } ?: return null
    val user = username?.takeIf { it.isNotEmpty() } ?: DEFAULT_BASIC_USERNAME
    val credentials = "$user:$secret"
    return "Basic " + Base64.getEncoder().encodeToString(credentials.toByteArray(Charsets.UTF_8))
}

/** Upstream hardcodes this user for HTTP basic auth. */
internal const val DEFAULT_BASIC_USERNAME = "opencode"
```

### 1.3 状态码归类（第一道闸）

`ServerHealth.kt:132-147`

```kotlin
fun healthResult(
    code: Int, contentType: String?, body: String?, credentialsSent: Boolean = true,
): HealthResult = when {
    code == 401 || code == 403 -> unhealthy(
        if (credentialsSent) HealthFailure.Unauthorized else HealthFailure.MissingCredentials,
    )
    code !in 200..299 -> unhealthy(HealthFailure.HttpStatus(code))
    else -> healthBody(contentType, body)
}
```

`ServerHealth.kt:150-157`（2xx 才进身体判定；**非 v2 一律 `InvalidResponse`**）

```kotlin
fun healthBody(contentType: String?, body: String?): HealthResult {
    val info = V2Capability.decideInfo(200, contentType, body)
    return if (info.generation == ServerGeneration.V2) {
        HealthResult(state = ServerHealth.HEALTHY, version = info.version)
    } else {
        unhealthy(HealthFailure.InvalidResponse)
    }
}
```

### 1.4 真正的"是不是 OpenCode 2.x"规则 ★

`app/src/main/java/com/xiufeigo/opencodeshell/v2/V2Capability.kt:561-608`

```kotlin
fun decideInfo(
    statusCode: Int, contentType: String?, body: String?, credentialsSent: Boolean = true,
): ProbeResult {
    val ctype = contentType?.lowercase().orEmpty()
    val trimmed = body?.trim().orEmpty()
    if (statusCode == 401 || statusCode == 403) { ... return ProbeResult(UNKNOWN, null, null, ..., authWall = true, ...) }
    if (statusCode != 200) {
        return ProbeResult(UNKNOWN, null, null, statusCode, contentType, "HTTP $statusCode, not a v2 info response")
    }
    if (ctype.contains("text/html") || trimmed.startsWith("<")) {
        return ProbeResult(UNKNOWN, null, null, statusCode, contentType, "text/html — the SPA shell, so /api/info is not served")
    }
    val json = runCatching { JSONObject(trimmed) }.getOrNull()
        ?: return ProbeResult(UNKNOWN, null, null, statusCode, contentType, "body is not JSON")
    val version = json.optString("version", "")
    if (version.isEmpty()) {
        return ProbeResult(UNKNOWN, null, null, statusCode, contentType, "JSON without the `version` marker")
    }
    if (!version.startsWith("2.")) {
        return ProbeResult(UNKNOWN, version, null, statusCode, contentType, "version $version is not a 2.x server")
    }
    return ProbeResult(ServerGeneration.V2, version = version, httpStatus = statusCode,
        contentType = contentType, detail = "v2 /api/info answered version=$version")
}
```

`V2Capability.kt:14-15`（文件头同一规则的书面版）

```
 * Probe rule: `GET /api/info` → JSON with `version` starting with `"2."`
 * ⇒ [ServerGeneration.V2]; anything else ⇒ [ServerGeneration.UNKNOWN]
```

> **有版本/能力字段校验**：字段名就是 `version`，且必须 `2.` 前缀。SPA HTML 那条早于版本检查就断了，
> 所以 1.18.33 的 `version` 根本没机会被读到（`incompatibleVersion` 留 `null`）。

### 1.5 文案锚

`ServerHealth.kt:170-188`（失败类别 → string res）

```kotlin
HealthFailure.InvalidUrl            -> HealthMessage(R.string.oc_connect_address_invalid)
HealthFailure.Timeout               -> HealthMessage(R.string.oc_connect_error_timeout)
HealthFailure.MissingCredentials    -> HealthMessage(R.string.oc_server_error_auth)
HealthFailure.Unauthorized          -> HealthMessage(R.string.oc_connect_error_auth)
HealthFailure.NotHealthy            -> HealthMessage(R.string.oc_connect_error_unhealthy)
HealthFailure.InvalidResponse       -> HealthMessage(R.string.oc_connect_error_invalid_response)   // ← :181
is HealthFailure.HttpStatus         -> HealthMessage(R.string.oc_connect_error_status, failure.code.toString())
HealthFailure.Unreachable, null     -> HealthMessage(R.string.oc_connect_failed)
```

`app/src/main/res/values/strings.xml:826`

```xml
<string name="oc_connect_error_invalid_response">Something answered, but it is not an OpenCode server.</string>
```

相邻的兄弟文案（同一张表，构成"归类"全貌）：

| res | 行 | 英文原文 | 触发条件 |
|---|---|---|---|
| `oc_connect_address_invalid` | 821 | `Enter a valid HTTP or HTTPS server address.` | 地址不以 `http(s)://` 开头 |
| `oc_connect_error_timeout` | 823 | `The server did not answer in time. …` | 3 s 预算耗尽 |
| `oc_server_error_auth` | 250 | `Server requires authentication. Enter username and password.` | 401/403 且**没发** Authorization |
| `oc_connect_error_auth` | 824 | `The server rejected that password. …` | 401/403 且**发了** Authorization |
| `oc_connect_error_invalid_response` | 826 | `Something answered, but it is not an OpenCode server.` | 2xx 但不是 v2 文档 ★ |
| `oc_connect_error_status` | 827 | `The server answered HTTP %1$s.` | 其它非 2xx |
| `oc_connect_failed` | 822 | `Could not connect. …` | 连不上 / 兜底 |

> 归类链条：`/api/info` 返回 200 + `text/html` ⇒ `healthBody` ⇒ `decideInfo` 判 `text/html` 分支 ⇒
> `generation != V2` ⇒ `HealthFailure.InvalidResponse` ⇒ `strings.xml:826`。
> **与 127.0.0.1 / 10.0.2.2 无关**（两个地址都通到同一台机、同一份响应），也与口令无关——
> 所以两种地址**逐字相同**的文案是必然的，不是巧合。

---

## 2. 契约清单（可执行检查表）

| # | 判定 | 方法 + 路径 | 期望 | 不满足 ⇒ 文案 |
|---|---|---|---|---|
| 1 | 地址形状（不发包） | 输入须以 `http://` / `https://` 开头 | 否则 `healthUrl()` 返回 null | `oc_connect_address_invalid` |
| 2 | 可达 + 预算 | 单次 `GET {base}/api/info`，connect/read/call 各 3 s，无重试 | 3 s 内有响应 | `oc_connect_error_timeout` / `oc_connect_failed` |
| 3 | 鉴权 | 口令非空才带 `Authorization: Basic base64("opencode:<pw>")`；**口令为空则不带** | — | — |
| 4 | 状态码 | 响应状态码 | **恰好 `200`** | `401/403` ⇒ `oc_server_error_auth`(未带凭据)/`oc_connect_error_auth`(带凭据)；其它非 2xx ⇒ `oc_connect_error_status` |
| 5 | **不是 HTML** | `Content-Type` 不得含 `text/html`，body 不得以 `<` 开头 | `application/json` | ★ 落到 `oc_connect_error_invalid_response` |
| 6 | 是 JSON **对象** | `JSONObject(trimmed)` 可解析 | 顶层 `{...}` | ★ 同上 |
| 7 | 有 `version` 字段 | `json.optString("version","")` 非空 | 例 `"2.0.14"` | ★ 同上 |
| 8 | **版本 2.x** | `version.startsWith("2.")` | `"2.0.14"` ✅ | ★ 同上 |
| — | （加分项，不参与判定） | `GET {base}/api/location` | 200 JSON，取 `directory` | 失败**不降级**（`V2Capability.kt:650-658`） |

一行版：`curl -u opencode:<pw> -i http://127.0.0.1:4095/api/info` ⇒ 必须 `200` + `application/json` + `{"version":"2.x…"}`。

---

## 3. 对照实测

### 3.0 CLI 自查（`opencode --version` / `serve --help`）

| 命令 | 结果 |
|---|---|
| `opencode --version`（PATH 解析到 npm shim） | **`1.18.33`** |
| `where.exe opencode` | `C:\Users\xiufe\AppData\Roaming\npm\opencode.cmd` ← **PATH 上排第一**<br>`C:\Users\xiufe\.bun\bin\opencode.exe`<br>`C:\Users\xiufe\.bun\bin\opencode2.exe` |
| `& "$env:USERPROFILE\.bun\bin\opencode2.exe" --version` | **`opencode v2.0.14`** |
| `& "$env:USERPROFILE\.bun\bin\opencode.exe" --version` | **`opencode v2.0.14`** |
| `opencode serve --help`（1.18.33） | `--port` `[default: 0]`、`--hostname` `[default: "127.0.0.1"]`、`--mdns`、`--cors`；**无口令 flag**，口令只走 env `OPENCODE_SERVER_PASSWORD` |
| `opencode2 serve --help`（2.0.14） | `Start the v2 API and web server`；`--hostname`、`--port`、`--cors`、`--service`、`--stdio`；同样无口令 flag |
| 2.0.14 默认行为 | 启动即打印 `server listening on http://127.0.0.1:4095` + **自动生成** `server password <43 chars>` |
| 2.0.14 能否无口令 | **不能**：显式设 `OPENCODE_SERVER_PASSWORD=''` 仍自动生成新口令，且无口令请求仍得 `401` |

> ★ **根因一句话**：这台机器上 **PATH 里的 `opencode` 是 npm 的 1.18.33**，它把 `~/.bun/bin` 里的 **2.0.14** 遮住了。
> Lead 敲的 `opencode serve` 起的是 1.18.33 —— 一个**根本不认识 `/api/info` 这个 v2 路由**的旧版。

### 3.1 TARGET A — 我自起的 **2.0.14** 于 4095（契约逐条）

命令：`& "$env:USERPROFILE\.bun\bin\opencode2.exe" serve --port 4095 --hostname 127.0.0.1`
启动输出：
```
server listening on http://127.0.0.1:4095
server password 7en2Qxm7DhpeFiUNU4vXocG1h-3zRIiKaBIyEtNmwUg
```

| # | 请求 | 期望 | **实际 status** | **实际 content-type** | **实际 body[0:200]** | 判定 |
|---|---|---|---|---|---|---|
| A1 | `GET /api/info` 无 Authorization | 200 JSON v2 | **`401`** | *(空)* | *(空)* | ✗ 但归类正确 ⇒ app 显 `oc_server_error_auth` |
| A2 | `GET /api/info` `-u opencode:7en2Qxm7…` | **200 + `application/json` + `version` 以 `2.` 开头** | **`200`** | **`application/json`** | `version":"2.0.14","pid":27752,"urls":["http://127.0.0.1:4095"],"paths":{"tmp":"C:\Users\xiufe\AppData\Local\Temp\opencode"}}` | ✅ **全清单过** |
| A3 | `GET /api/location`（带 auth） | 200 JSON | `200` | `application/json` | `directory":"C:\sensorsdata\main\program\opencode-android-shell","project":{"id":"ae54078c0e09bf8e7cbeefd35db19aaa6b064e46","directory":"C:\sensorsdata\main\program\opencode-android-shell","can` | ✅（加分项） |
| A4 | `GET /api/health`（带 auth） | — | `404` | *(空)* | *(空)* | 与代码注释一致：**永不作 v2 判据**（`V2Capability.kt:552-553`） |
| A5 | `GET /global/health`（带 auth） | — | `200` | **`text/html`** | `<!doctype html> <html lang="en" style="background-color: var(--v2-background-bg-deep, #fafafa)"> <head> <meta charset="utf-8" /> …` | ⚠ **老报告的根因已过时**：旧报告说"只探 `/global/health`"，现行代码探的是 `/api/info`；但 `/global/health` 至今仍返回 SPA HTML |
| A6 | `GET /`（无 auth） | — | `200` | `text/html` | `<!doctype html> <html lang="en" …` | SPA 兜底路由 |

### 3.2 TARGET B — **1.18.33**（= Lead 实际跑的那台）于 4096

命令：`& "$env:APPDATA\npm\opencode.cmd" serve --port 4096 --hostname 127.0.0.1`
启动输出（与 Lead 转述逐字一致）：
```
Warning: OPENCODE_SERVER_PASSWORD is not set; server is unsecured.
```

| # | 请求 | 期望 | **实际 status** | **实际 content-type** | **实际 body[0:200]** | 判定 |
|---|---|---|---|---|---|---|
| B1 | `GET /api/info` 无 Authorization | **200 + `application/json` + `version` 以 `2.` 开头** | `200` | **`text/html`** | `<!doctype html> <html lang="en" style="background-color: var(--v2-background-bg-deep, #fafafa)"> <head> <meta charset="utf-8" /> <meta name="viewport" content="width=device-width, initial-sca` | ✗✗ **断在第 5 条** |

### 3.3 TARGET C — 2.0.14 + `OPENCODE_SERVER_PASSWORD=opencode` 于 4097（给 Lead 的确定口令版）

| # | 请求 | 实际 status | 实际 content-type | 实际 body[0:200] | 判定 |
|---|---|---|---|---|---|
| C1 | `GET /api/info` `-u opencode:opencode` | **`200`** | **`application/json`** | `version":"2.0.14","pid":12764,"urls":["http://127.0.0.1:4097"],"paths":{"tmp":"C:\Users\xiufe\AppData\Local\Temp\opencode"}}` | ✅ **全清单过** |
| C2 | `GET /api/info` 无 Authorization | `401` | *(空)* | *(空)* | 归类正确 |

### 3.4 ★ 第一条不满足的是哪一条

* **Lead 那台（1.18.33）：第 5 条** —— `Content-Type: text/html`（body 以 `<!doctype html>` 开头）。
  第 1–4 条全过（地址合法、3 s 内应答、`200`），**断在 HTML 这一条**，还没轮到第 6/7/8 条的 JSON 与 `version` 校验。
  → `InvalidResponse` → `strings.xml:826`。
* 若换成 2.0.14 而**不填口令**：断在**第 4 条**（`401`），但文案是 `oc_server_error_auth`（"Enter username and password"），
  **不是** Lead 看到的那句 —— 反证 Lead 看到的那句确实来自 1.18.33 的 HTML。

### 3.5 为什么两种地址给出逐字相同的文案（闭环解释）

`127.0.0.1:4095`（设备自身 / `adb reverse tcp:4095`）与 `10.0.2.2:4095`（模拟器 → 宿主 loopback）**指向同一台宿主机的同一个 1.18.33 进程**，
拿到同一份 SPA HTML，落到同一个分支、同一句文案。**与 scheme（`http://` 前缀与否）、与口令均无关** ——
旧报告已实测过带/不带口令、带/不带 scheme 文案逐字相同，本次代码阅读确认了机理：`decideInfo` 在 HTML 分支就 return，
根本走不到鉴权相关的分支。

---

## 4. 结论：**① 可自解**

契约里没有任何"只有用户那套才满足"的东西：**只要用本机已存在的 2.0.14 二进制起，并把它打印的口令填进 app，全清单即过**（A2/C1 实测 200 JSON `2.0.14`）。用户原来那台 2.0.11 一直是对的 —— 错的只是 Lead 敲的 `opencode` 落到了 npm 的 1.18.33 上。

### 确切命令（推荐：固定口令，app 侧填 `opencode`）

```powershell
# 终端 1（宿主）—— 起 2.0.14，口令固定为 opencode
$env:OPENCODE_SERVER_PASSWORD = "opencode"
& "$env:USERPROFILE\.bun\bin\opencode2.exe" serve --port 4095 --hostname 127.0.0.1
```

app 侧填：
| 字段 | 值 |
|---|---|
| Server address | `http://127.0.0.1:4095`（已挂 `adb reverse tcp:4095`）或 `http://10.0.2.2:4095` |
| Username | 留空（缺省即 `opencode`） |
| Password | `opencode` |

### 备选命令 A（自动口令）

```powershell
& "$env:USERPROFILE\.bun\bin\opencode2.exe" serve --port 4095 --hostname 127.0.0.1
# 读 stdout 第二行 "server password <43 chars>"，原样粘进 app 的 Password
```

### 备选命令 B（让 `opencode` 默认就是 2.0.14，治本）

```powershell
# 把 bun 的 2.0.14 放到 PATH 前面，或直接改名覆盖 npm shim（需自担副作用）
$env:PATH = "$env:USERPROFILE\.bun\bin;$env:PATH"
opencode serve --port 4095 --hostname 127.0.0.1   # 之后解析到 2.0.14
```
（`~/.bun/bin/opencode.exe` 与 `opencode2.exe` 同为 v2.0.14，任选其一。）

### 逐条验证（我已真跑过，命令可直接复现）

```powershell
curl.exe -s -i -u opencode:opencode http://127.0.0.1:4095/api/info
# 期望：HTTP/1.1 200 + content-type: application/json
#       {"version":"2.0.14","pid":…,"urls":[…],"paths":{"tmp":"…"}}
```

**已验证清单全过**（A2 与 C1 两条，均为 2.0.14 + 正确口令；起测完已停、端口已释放，见 §6）。

---

## 5. 上游对照（`_upstream/` 官方 app 自己怎么探测）

### 5.1 上游 app 探测入口 — 同一个 `/api/info`

`_upstream/packages/app/src/runtime/server/health.ts:72-107`

```ts
export async function checkServerHealth(
  server: ServerConnection.HttpBase,
  fetch: typeof globalThis.fetch,
  opts?: CheckServerHealthOptions,
): Promise<ServerHealth> {
  const timeout = opts?.signal ? undefined : timeoutSignal(opts?.timeoutMs ?? defaultTimeoutMs)
  ...
  const headers = server.password
    ? {
        Authorization: `Basic ${authTokenFromCredentials({ password: server.password })}`,
      }
    : undefined
  ...
  const attempt = async (count: number): Promise<ServerHealth> => {
    const current = await OpenCode.make({ baseUrl: server.url, fetch, headers })
      .server.info({ signal })
      .then((status) => ({ data: { healthy: true as const, version: status.version } }))
      .catch((error) => ({ error }))
    if ("data" in current) return current.data
    ...
  }
  return attempt(0).finally(() => timeout?.clear?.())
}
```

要点：**路径、Basic 规则（仅 password 存在时才带头）与我方 `ServerHealth.kt:241-242` / `BasicAuth.kt:26-30` 逐字同源**，
我方注释 `ServerHealth.kt:19-20` 也自认是它的镜像（`health.ts:72-107`）。

### 5.2 上游 SDK 实际打的那条路由

`_upstream/packages/client/src/promise/generated/client.ts:409-414`

```ts
    server: {
      info: (requestOptions?: RequestOptions) =>
        request<ServerInfoOutput>(
          { method: "GET", path: `/api/info`, successStatus: 200, declaredStatuses: [400, 401], empty: false },
          requestOptions,
        ),
    },
```

`_upstream/packages/client/src/promise/generated/types.ts:3`（期望的响应形状）

```ts
export type ServerInfo = { version: string; pid: number; urls: Array<string>; paths: { tmp: string } }
```

`_upstream/packages/client/src/promise/generated/types.ts:2685`
```ts
export type ServerInfoOutput = ServerInfo
```

`_upstream/packages/client/src/promise/service.ts:166`（promise 版直连 fetch，同一路径）
```ts
const result = await fetch(new URL("/api/info", info.url), { headers: headers(endpoint), signal })
```

### 5.3 与我方结论是否一致？—— **一致，但有一处刻意的收紧**

| 维度 | 上游 | 我方 app | 是否一致 |
|---|---|---|---|
| 探测路径 | `GET /api/info` | `GET /api/info` | ✅ 完全一致 |
| 期望状态码 | `successStatus: 200`（`client.ts:412`） | 走到 `decideInfo` 必须 `statusCode == 200`（`V2Capability.kt:586`）；2xx 里的 201/204 会因 `healthBody` 硬传 `200` 而被当 200 处理 | ✅ 一致 |
| 期望形状 | `ServerInfo = { version: string; pid: number; urls: string[]; paths: { tmp: string } }`（`types.ts:3`）—— 生成的强类型 client 在 body 非该形状时解析失败 ⇒ unhealthy | `JSONObject` + **只**校验 `version` 非空（`V2Capability.kt:592-597`），`pid`/`urls`/`paths` **不**校验 | ⚠ 我方**更宽松**（少查 3 个字段） |
| `version` 取值 | **不限前缀**：`{ healthy: true, version: status.version }`（`health.ts:99`），1.x 也会判 healthy | **必须 `2.` 前缀**，否则 UNKNOWN（`V2Capability.kt:598-600`） | ⚠ **我方更严格** —— 1.18.33 会被上游判 healthy、被我方判 UNKNOWN |
| 鉴权 header | 仅 password 非空时发 Basic（`health.ts:81-85`） | 同（`ServerHealth.kt:241-242` + `BasicAuth.kt:26-30`） | ✅ 一致 |
| HTML 兜底页 | `server.info()` 期望 JSON；SPA HTML 会解析失败 ⇒ `{healthy:false}` | 显式 `text/html`/`<` 分支（`V2Capability.kt:589-591`） | ✅ 行为等价（文案更细） |
| 失败归类 | 全部塌成 `{ healthy: false }` | 拆成 7 类 + 7 句文案（§1.5） | ✅ 我方是细化版（`ServerHealth.kt:161-168` 注释自认） |

**结论**：上游与我在"探测契约"上同源（同一路由 `/api/info`、同一 Basic 规则、同一"必须 JSON 文档"预期）。
上游**不**要求 `2.` 前缀，所以官方 app 面对 1.18.33 会显示 healthy；而我方 shell 额外把 `version` 前缀当作代际闸门 ——
**这正是 Lead 那台被拒的确切机制**（1.18.33 的 HTML 连 `version` 都没送到，因此文案是 `InvalidResponse` 而不是"版本不兼容"面板）。
`/api/info` 在 1.18.33 上返回 SPA HTML 这件事，**上游自己也无法把它读成 ServerInfo**。

---

## 6. 自起 serve 的清理状态

| 端口 | 用途 | 收尾 |
|---|---|---|
| **4095** | TARGET A（2.0.14 主验证） | ✅ **已停并确认释放**（`netstat` 无 LISTENING 行，仅剩若干 `TIME_WAIT`，属正常 TCP 残留） |
| 4096 | TARGET B（1.18.33 对照） | ⚠ **残留 PID 12668，SessionId=0**，`Stop-Process` / `taskkill /F /T` / CIM `Terminate` 均返回"拒绝访问"（ReturnValue 2），需**管理员**权限才能结束 |
| 4097 | TARGET C（固定口令版）+ 无口令测试 | ✅ 已停并确认释放 |

需管理员手动清理（4096 是我为对照实验起的，不影响 4095）：
```powershell
# 以管理员身份运行
taskkill /PID 12668 /F
# 确认
netstat -ano | Select-String ':4096'
```

---

## 7. 未闭环 / 交接

1. **设备端未复验**：本报告是主机侧 curl 实测 + 代码闭环。**尚未**在 app 里用 2.0.14 真机走一遍引导页确认那句红字消失（需 Lead 侧一次 1 分钟操作，命令见 §4）。§4 的"全清单过"指的是契约清单全过，不是 app UI 已验。
2. **4096 残留进程**（PID 12668）需管理员清理，见 §6。
3. **PATH 隐患未治**：`opencode` 仍解析到 npm 的 1.18.33，Lead 下次敲 `opencode serve` 还会踩同一个坑。治本命令见 §4 备选 B（**本报告不代为执行**——改 PATH / 覆盖 shim 超出"只读代码"授权，且会影响 Lead 其它工作流）。
4. **1.18.x 的 `version` 字符串是什么** 未读到：1.18.33 的 `/api/info` 走 SPA 兜底，body 里没有 `version` 字段（`V2Capability.kt:594` 的 `optString` 拿不到值）。若将来 1.18.x 真的开始提供 JSON `/api/info`，我方会在**第 8 条**（`2.` 前缀）拒绝它并把文案从 `invalid_response` 改成"版本不兼容"面板（`V2Capability.kt:509-525` 的 `incompatible` 已就位但引导页当前未接）—— 本次不阻塞。
5. **不相关但已核销的旧结论**：`research/wave4_fold_v2_report.md:63-65` 说根因是"只探 `GET /global/health`"——**该描述对现行代码已过时**（`ServerHealth.kt:38` 探的是 `/api/info`）。但 `/global/health` 在 2.0.14 上确实仍返回 SPA HTML（A5），所以旧报告的现象描述没错，只是定位代码过时了。

---

### 证据文件（本报告同目录）

`serve_2014.out.log`（2.0.14 启动输出含自动口令）/ `serve_11833.out.log`（1.18.33 的 unsecured 警告）/ `serve_2014_pw.out.log` / `serve_2014_nopw.out.log` / `*.pid`。
