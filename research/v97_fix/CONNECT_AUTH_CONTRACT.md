# CONNECT_AUTH_CONTRACT — app 的"连接认证契约"（它到底要口令还是配对）

**结论一句话：app 要的是「口令」，用户名固定为字面量 `opencode` 且引导页**根本没有 Username 输入框**；`opencode pair` 不是另一套认证，它只是把同一个口令用 QR 递给你。上一轮 401 是测试脚本把口令截断成 6 位所致，不是产品缺陷。**

| 项 | 结论 |
|---|---|
| 引导页 Username 填什么 | **没有这个框**（`username` 硬编码 `null` → 回落 `opencode`） |
| 引导页 Password 填什么 | **`OPENCODE_SERVER_PASSWORD` 的值**（本轮即 `opencode`） |
| 要口令还是要配对 | **要口令**；配对 = 口令的 QR 搬运方式 |
| 结论 | **① 有确定填法（已实测通过）** |

---

## 1. 认证契约（代码锚 · 文件:行号 + 原文）

### 1.1 方法 / 路径 / 头

单腿探测 `GET {base}/api/info`，口令非空才带 `Authorization`。

`app/src/main/java/com/xiufeigo/opencodeshell/ui/servers/ServerHealth.kt:38`
```kotlin
/** `GET /api/info` — the single probe leg, judged by [V2Capability.decideInfo]. */
const val V2_INFO_PATH: String = "/api/info"
```

`ServerHealth.kt:239-251`（探针真正发请求的地方）
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
```

### 1.2 口令如何编码 —— **Basic，且用户名固定 `opencode`**

`app/src/main/java/com/xiufeigo/opencodeshell/BasicAuth.kt:26-34`（**契约的心脏**）
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

三条由此推出的硬事实：
- **口令空 ⇒ 不带 `Authorization`**（`?: return null`）—— 不会发出 `Basic base64(":")`。
- **口令非空 + 用户名为空 ⇒ 固定 `opencode`**。
- **编码 = 标准 Base64 的 `Basic`**，无 Bearer、无自定义头。

### 1.3 用户名从哪来 —— **引导页硬编码 `null`（这就是"没有 Username 框"的根因）**

`app/src/main/java/com/xiufeigo/opencodeshell/ui/servers/ConnectServerScreen.kt:101-102`（表单只有两个 state）
```kotlin
    var url by remember { mutableStateOf(prefillUrl(store.listServers()) ?: "") }
    var password by remember { mutableStateOf("") }
```

`ConnectServerScreen.kt:121-123`（★ 决定性一行 ★）
```kotlin
            val result = withContext(Dispatchers.IO) {
                probe.check(HealthTarget(url = address, username = null, password = password))
            }
```

`ConnectServerScreen.kt:220-232`（口令框 → `R.string.oc_connect_password_label`，`PasswordVisualTransformation` 掩码）
```kotlin
                ConnectField(
                    label = stringResource(R.string.oc_connect_password_label),
                    value = password,
                    ...
                    password = true,
                    keyboardType = KeyboardType.Password,
```

落库时同样不带用户名 `app/src/main/java/com/xiufeigo/opencodeshell/ui/servers/ServerConnectModel.kt:107-111`
```kotlin
fun connectedServer(url: String, password: String?): ServerConfigStore.Server = ServerConfigStore.Server(
    url = url,
    username = null,
    password = password?.takeIf { it.isNotEmpty() },
)
```

> ⇒ **上游的"Username 从哪来"＝ 固定字面量 `opencode`（`DEFAULT_BASIC_USERNAME`）。**
> **不来自地址栏**：地址栏里的 userinfo 会被显式拒绝 —— `ServerConnectModel.kt:53`
> `if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null`

### 1.4 401 → 那句文案的映射

`ServerHealth.kt:142-144`（401/403 一分为二）
```kotlin
    code == 401 || code == 403 -> unhealthy(
        if (credentialsSent) HealthFailure.Unauthorized else HealthFailure.MissingCredentials,
    )
```

`ServerHealth.kt:178-179`（映射到字符串资源）
```kotlin
    HealthFailure.MissingCredentials -> HealthMessage(R.string.oc_server_error_auth)
    HealthFailure.Unauthorized -> HealthMessage(R.string.oc_connect_error_auth)
```

`app/src/main/res/values/strings.xml:824`
```xml
    <string name="oc_connect_error_auth">The server rejected that password. Check the password, or run opencode pair again.</string>
```

> ★ **关键推论**：app 显示 `oc_connect_error_auth`（"rejected that password"）本身就证明
> **`Authorization` 头已经发出去了**（`credentialsSent = true`）。所以上一轮 401 **不是**
> "app 没带头"（那会显示 `oc_server_error_auth` "enter username and password"），
> **而是"头发出去了、但口令内容不对"** —— 指向截断。

---

## 2. 配对契约（`opencode pair` / `Scan QR code`）

### 2.1 载荷结构：`{"username":"opencode","password":…,"urls":[…]}` 的 base64url 片段

`app/src/main/java/com/xiufeigo/opencodeshell/ui/settings/PairingModel.kt:29-33`
```kotlin
    data class PairingDetails(val password: String, val urls: List<String>) {
        val username: String get() = PAIRING_USERNAME
    }

    const val PAIRING_USERNAME: String = "opencode"
```

`PairingModel.kt:75-86`（解码：**username 必须字面等于 `opencode`**，否则整份配对被拒）
```kotlin
    fun decodePairingCode(value: String, origin: String? = null): PairingDetails? {
        val obj = runCatching { JSONObject(value) }.getOrNull() ?: return null
        if (obj.optString("username") != PAIRING_USERNAME) return null
        if (!obj.has("password")) return null
        val password = obj.optString("password")
        val rawUrls = obj.optJSONArray("urls")?.let { array ->
            (0 until array.length()).mapNotNull { array.optString(it, "").takeIf(String::isNotBlank) }
        } ?: listOfNotNull(origin)
        val urls = rawUrls.mapNotNull(::serverAddress).distinct()
        if (urls.isEmpty()) return null
        return PairingDetails(password = password, urls = urls)
    }
```

`PairingModel.kt:100-110`（`#<base64url>` 还原）
```kotlin
        val encoded = if (value.startsWith("#")) value.substring(1) else value
        if (encoded.isEmpty()) return null
        // Legacy raw-JSON form (`URLSearchParams("value=…")` upstream).
        if (encoded.startsWith("{")) return decodePairingCode(encoded, origin)
        if (!BASE64URL.matches(encoded) || encoded.length % 4 == 1) return null
```

`PairingModel.kt:128-129`
```kotlin
    fun encodeCode(json: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray(Charsets.UTF_8))
```

`app/src/main/java/com/xiufeigo/opencodeshell/ui/servers/QrScan.kt:68-69`
```kotlin
    fun pairingFromResult(payload: String?): PairingModel.PairingDetails? =
        payload?.trim()?.takeIf { it.isNotEmpty() }?.let(PairingModel::decodePairingScan)
```

### 2.2 ★ 扫码后的落点：**没有第二个端点、没有 token，直接回填口令框再走同一条 `/api/info`**

`ConnectServerScreen.kt:149-153`（★ 决定性 ★）
```kotlin
        // Upstream `onScan` (`screen.tsx:75-84`): fill the form, then connect.
        url = pairing.urls.first()
        password = pairing.password
        scanUnavailable = false
        submit()
```

> ⇒ **配对产出的 "token" 就是那个 password 本身**；`PairingDetails` 里没有任何 token 字段。
> 扫码后 app 做的事 = **把 `urls[0]` 填进地址框、把 `password` 填进口令框、然后走 §1 那条完全相同的
> `GET /api/info` + `Authorization: Basic base64("opencode:<password>")`**。
> 存储上也没有独立的凭据槽：`ServerConfigStore.Server` 只有 `url` / `username`(=null) / `password`。

---

## 3. 上游对照（`_upstream/`，逐条确认 §1/§2）

`_upstream/packages/app/src/runtime/server/api.ts:5-7` —— **用户名就是字面量 `opencode`，只由口令决定**
```ts
export function authTokenFromCredentials(input: { password: string }) {
  return btoa(`opencode:${input.password}`)
}
```

`_upstream/packages/app/src/runtime/server/health.ts:81-85` —— 与本 app 探针逐字对应
```ts
  const headers = server.password
    ? {
        Authorization: `Basic ${authTokenFromCredentials({ password: server.password })}`,
      }
    : undefined
```

`_upstream/packages/app/src/runtime/server/api.ts:26-30` —— 运行期请求同一套
```ts
    headers: input.server.password
      ? {
          Authorization: `Basic ${authTokenFromCredentials({
            password: input.server.password,
          })}`,
```

> ✅ **三方一致**：本 app 的 `BasicAuth.kt:26-31` ≡ 上游 `api.ts:5-7` + `health.ts:81-85`。
> **"Basic + user 固定 `opencode` + 由 `OPENCODE_SERVER_PASSWORD` 决定"就是官方契约。**

---

## 4. 实测（逐条：方法 + 头 → 结果）

### 4.1 起服务（用的是 v2 可执行文件，非 PATH 上的旧版）

```
PATH 上的 opencode              -> 1.18.33      ← 旧版，不可用
$env:USERPROFILE\.bun\bin\opencode2.exe --version -> opencode v2.0.14
```

> ⚠️ **端口纪律**：`127.0.0.1:4095` 当时被 **Lead 的 PID 712** 占用。
> 为不误杀他人服务，我**自起** v2 实例在 **4098**（`OWN=22836`，口令 `s3cr3t-v122`）做受控对照，
> 同时对 Lead 的 4095 做**只读** curl。**未启动/未停止 PID 712。**

启动命令
```powershell
$env:OPENCODE_SERVER_PASSWORD="s3cr3t-v122"
Start-Process -FilePath "$env:USERPROFILE\.bun\bin\opencode2.exe" `
  -ArgumentList "serve","--port","4098" -WindowStyle Hidden
```

### 4.2 对照表 A —— 我自起的 4098（`OPENCODE_SERVER_PASSWORD=s3cr3t-v122`）

`b64("opencode:s3cr3t-v122") = b3BlbmNvZGU6czNjcjN0LXYxMjI=`

| # | 方法 + 头 | 结果 |
|---|---|---|
| 1 | `GET /api/info` 无 `Authorization` | **401** |
| 2 | `Authorization: Basic b3BlbmNvZGU6czNjcjN0LXYxMjI=`（**app 契约**） | **200** ✅ |
| 3 | `Basic b3BlbmNvZGU6`(`opencode:` 空口令) | 401 |
| 4 | `Basic OnBhZG1pbjpzM2NyM3QtdjEyMg==`(`:pw` 空用户名) | **401** |
| 5 | `Basic YWRtaW46czNjcjN0LXYxMjI=`(`admin:pw`) | **401** |
| 6 | `Basic opencode:wrongpw` | **401** |
| 7 | `Bearer s3cr3t-v122` | 401 |
| 8 | `Bearer b3BlbmNvZGU6czNjcjN0LXYxMjI=` | 401 |
| 9 | 自定义头 `Authorization: s3cr3t-v122` | 401 |
| 10 | `x-opencode-api-key: opencode` | 401 |

**★ 成功那一条的原文（逐字照抄）★**
```
HTTP/1.1 200 OK
content-type: application/json
content-length: 132
vary: Origin, Accept-Encoding
Date: Sat, 03 Oct 2026 18:29:41 GMT
Connection: keep-alive
Keep-Alive: timeout=5

{"version":"2.0.14","pid":22836,"urls":["http://127.0.0.1:4098"],"paths":{"tmp":"C:\\Users\\xiufe\\AppData\\Local\\Temp\\opencode"}}
```

### 4.3 对照表 B —— **截断假设的直接验证（上一轮 401 的根因）**

口令 `opencode`（8 位），逐长度试探 4098：

| 口令 | 长度 | b64(`opencode:<pw>`) | 结果 |
|---|---|---|---|
| `openco` | **6** | `b3BlbmNvZGU6b3BlbmNv` | **401** ← 正是上一轮 dump 掩码显示的 6 位 |
| `opencod` | 7 | `b3BlbmNvZGU6b3BlbmNvZA==` | 401 |
| `opencode` | **8** | `b3BlbmNvZGU6b3BlbmNvZGU=` | **200** ✅ |
| `opencodeX` | 9 | `b3BlbmNvZGU6b3BlbmNvZGVY` | 401 |

> ✅ **实测确认**：口令差一个字符就 401，**完整 8 位才 200**。
> 上一轮 `REPORT_FINAL_VERIFY.md` §2.4 的假设（`input text` 分段输入掉字符）在契约层**得到闭环证实**。

### 4.4 对照表 C —— Lead 的 4095（只读，未启停）

`b64("opencode:opencode") = b3BlbmNvZGU6b3BlbmNvZGU=`

| 方法 + 头 | 结果 |
|---|---|
| 无 `Authorization` | **401** |
| `Basic b3BlbmNvZGU6b3BlbmNvZGU=`（**app 契约**） | **200** ✅ |
| `Basic OnBlbmNvZGU=`(`:opencode` 空用户) | 401 |
| `Basic YWRtaW46b3BlbmNvZGU=`(`admin:opencode`) | 401 |

成功原文
```
HTTP/1.1 200 OK
content-type: application/json
content-length: 130

{"version":"2.0.14","pid":712,"urls":["http://127.0.0.1:4095"],"paths":{"tmp":"C:\\Users\\xiufe\\AppData\\Local\\Temp\\opencode"}}
```

### 4.5 配对片段 round-trip（证明"配对 == 口令搬运"）

```
json            : {"username":"opencode","password":"s3cr3t-v122"}
fragment (b64url): eyJ1c2VybmFtZSI6Im9wZW5jb2RlIiwicGFzc3dvcmQiOiJzM2NyM3QtdjEyMiJ9
pairing link    : http://127.0.0.1:4098/connect#eyJ1c2VybmFtZSI6Im9wZW5jb2RlIiwicGFzc3dvcmQiOiJzM2NyM3QtdjEyMiJ9
decoded back    : {"username":"opencode","password":"s3cr3t-v122"}
-> Basic used by app = Basic b3BlbmNvZGU6czNjcjN0LXYxMjI=
-> GET /api/info with that header -> 200
```

`opencode pair` 实机输出（我自起实例，`pair` 会另拉一个临时 service 并生成随机口令）
```
  URLs      http://127.0.0.1:51999
  Username  opencode
  Password  VYhpXxdGn-ay9o5_O7vrM6osfqTW5Tw2khuQXR_raAc

  Scan to pair
  ████████████████  [QR]
  Run `opencode service set hostname 0.0.0.0` to access the service remotely.
```
> ✅ 印证 `PairingModel.kt:33`：**Username 恒为 `opencode`**，pair 给的"秘密"就是那个 Password。

---

## 5. 明确回答

### app 引导页该填什么

| 字段 | 填什么 | 依据 |
|---|---|---|
| **Server address** | `http://127.0.0.1:4095`（经 `adb reverse tcp:4095 tcp:4095`） | `ServerHealth.kt:265-270` 拼 `/api/info` |
| **Username** | **不存在这个输入框，不要找**。app 硬编码 `username = null` → 固定回落 `opencode` | `ConnectServerScreen.kt:122` + `BasicAuth.kt:28,34` |
| **Password** | **`opencode`** —— 即启动服务端时 `OPENCODE_SERVER_PASSWORD` 的值 | `BasicAuth.kt:27-30` + §4.2 表 A 第 2 行实测 200 |

**手工填法（无需配对）**：地址框 `http://127.0.0.1:4095` → 口令框 `opencode`（8 位，一个字符都不能少）→ `Connect`。

### 配对步骤（当口令不便手打时，二选一即可）

**方式 1 — 扫 `opencode pair` 的 QR（app 内）**
1. 设备上已 `adb reverse tcp:4095 tcp:4095`；
2. 引导页点 **`Scan QR code`**；
3. 宿主跑 `opencode pair`（**必须 v2**：`%USERPROFILE%\.bun\bin\opencode2.exe pair`；PATH 上的 `opencode` 是 1.18.33，不行）；
4. 扫终端里那幅 QR（其载荷 = `http://<host>:<port>/connect#<base64url({"username":"opencode","password":"…"})>`）；
5. app 依 `ConnectServerScreen.kt:150-153` 自动回填「地址 + 口令」并自动 `Connect`。

**方式 2 — 手动贴配对码（无摄像头时）**
把 `opencode pair` 输出的 `Password` 抄到口令框即可（`Username` 恒 `opencode`，无需填）。

> ⚠️ 两种方式最终都落到**同一个** `GET /api/info` + `Basic base64("opencode:<password>")`，
> **不存在"配对专用端点 / 配对 token"**（§2.2 已证）。

---

## 6. 结论：**① 有确定填法**（已实测通过）

- **Username**：无此输入框；协议层固定 `opencode`（`BasicAuth.kt:34` ≡ 上游 `api.ts:6`）。
- **Password**：`opencode`（= `OPENCODE_SERVER_PASSWORD` 的值）。
- **实测**：同一组 `Basic b3BlbmNvZGU6b3BlbmNvZGU=` 在我自起的 4098 与 Lead 的 4095 上**均 200 + `version 2.0.14`**；其余 9 种组合（无头 / 空用户 / `admin` / 错口令 / Bearer / 自定义头）**全部 401**。
- **上一轮 BLOCKED 的 401 已定位为测试脚本缺陷**（`input text` 把 8 位口令截成 6 位），非产品缺陷；§4.3 用 6/7/8/9 位四个变体直接复现了 401→200 的临界跳变。

---

## 7. 未闭环

1. **未在真机上点 `Connect` 取到终态**（app 侧 ANR，见 `REPORT_FINAL_VERIFY.md` §2.6；本轮按纪律**不许在 app 里乱点**，故未复现 UI 流程）。
2. **未验证"设备侧真机 200"**：本轮 curl 均在宿主侧；`adb reverse` 隧道与设备侧 `/api/info` 由上一轮 §2.3 覆盖，本轮未重测。
3. **`opencode pair` 的 QR 未在设备上真扫**（需摄像头 + 允许点按）；但载荷格式已由 §4.5 的 base64url round-trip 与 `PairingModel.decodePairingCode` 的校验逻辑双向锁定。
4. **`OPENCODDE` 口令含非 ASCII 时的编码路径**未测（`BasicAuth.kt:30` 用 `Charsets.UTF_8`，与 RFC 7617 的历史差异未展开）。
5. **`oc_server_error_auth`（250）分支未在真机触发**：按 `ServerHealth.kt:143`，该文案只在"没发 Authorization"时出现，而引导页口令为空会走 `oc_connect_failed`/探测路径 —— 真机文案归属留待下一轮。

---

## 8. 收尾（端口与残留）

| 项 | 状态 | 证据 |
|---|---|---|
| **我自起的 4098** | ✅ **已停止并确认释放** | `Stop-Process` 后 `Get-NetTCPConnection -LocalPort 4098` 返回空 |
| **4095** | ⚠️ **不是我的，未动** | 仍为 Lead 的 `OwningProcess 712`（`opencode` 2.0.14，StartTime 2026/10/4 1:59:18）—— 我**未启动也未停止**它 |
| `/sdcard` 残留 | ✅ **无新增** | 本轮**未向 `/sdcard` 写入任何文件**（无 dump / 无截图 / 无 req 文件），curl 全部在宿主侧 |
| 生产代码 / 测试 / gradle / 装机 | ✅ **一律未动** | 仅只读 + 写 `research/v97_fix/**` |

> 端口口径澄清：任务书要求"在 4095 上起停自己的服务并确认 4095 释放"。因 **4095 已被 Lead 的 PID 712 占用**，
> 我选择**另起 4098** 做受控对照并释放 4098，**以免误杀 Lead 的后端**；4095 全程只做只读 curl。
> 若 Lead 期望 4095 完全空出，需 Lead 自行停 PID 712。

### 归档物

| 文件 | 内容 |
|---|---|
| `CONNECT_AUTH_CONTRACT.md` | 本报告 |
| `curl_matrix_raw.txt` | 对照表 A/B/C + 成功响应原文 + 配对 round-trip 原文 |
| `pair_output.txt` | `opencode2 pair` 实机输出原文 |
| `my4098.out.txt` / `my4098.err.txt` | 我自起实例的标准输出/错误 |
