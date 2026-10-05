# UPSTREAM_FOLLOWUP_F20 — A6 三条跟进（认证代理面 / 明文约束 / 上游证据补齐）

**跟进工程师**：F20
**日期**：2026-10-05（本地 CST）
**仓库根**：`C:\sensorsdata\main\program\opencode-android-shell`
**依据**：`research/audit/UPSTREAM_FRESHNESS_A6.md`（A6：`origin/v2 = ecfa884`，四条地基成立，3 条跟进 + 1 项未核对）
**硬期限**：2026-10-07 之前 ✅（提前完成）

**约束遵守**：
- `_upstream`：**未切工作区、未切分支、未 reset**。只执行 1 条 `git -C _upstream -c http.proxy=... fetch origin v2`（派单明确允许），外加若干只读 `show/log/diff`。
- 写权限：只写 `research/web/**`。**未改 `webapp/**` 任何文件**（需改动处 → 只给方案，见 §1.5 / §2.4）。
- 设备：自建 1 台 `OC_Deliver_R2` / `emulator-6450`（偶数、≥6450、起前 6450–6460 全 FREE）。后端 `4095` **未起停**（开轮与收尾同一 `pid=3748`），只做 `adb reverse`。

---

## 0. 头条结论

> **① 认证：仍兼容，无需改动。** 我们的 Basic 注入在三通道下**依然有效**（设备实证 200 vs 401）；`/auth/*` **不需要我们做任何透传或特殊处理**——代理是**逐字拼接转发**，天然照常转发。
> **② 明文约束：`window.isSecureContext = true`（回环来源实测）⇒ 扫码链不被该约束挡住。** 但**存在"空口令 ⇒ 后端全放行"路径**，属安全问题，已给最小修法。
> **③ 未核对项已补上**：fetch 成功，`origin/v2` 从 `ecfa884` 前进到 `9e96aac`（**+28 提交**）；A6 的 `0a46301` **现在在本地对象库里了**，且 A3 当年的 "5 提交" 判断**被独立复算证实**。

**一个必须点名的意外收获**：fetch 到的 28 个提交里，**认证相关文件一个都没被碰**（`authorization.ts` / `auth.ts` / `process.ts` / `app-assets.ts` / `camera.ts` / `gui-extensions/main.ts` 全部 0 变更）⇒ **A6 的全部认证结论在新 tip 上逐字成立**。唯一与我们相关的上游变更是 `web-ui.ts` 的 **CSP 增补 `worker-src 'self' blob:`**（扫码 worker 修复，对我们是**修复而非破坏**）。

---

## 1. ① 认证代理面

### 1.1 上游新认证实现的行号原文（新 tip `9e96aac`）

> ⚠️ **一处必须纠正 A6 的定位偏差**：A6 把新认证实现归到 `packages/server/src`，而派单要求"在 `packages/cli/src` 内找"。实测：**认证实现全在 `packages/server/src`，`packages/cli/src` 内没有任何认证实现**；`packages/cli/src` 侧只有 `/auth/*` 的**路由分流**（`web-ui.ts`）。两处都给出。

#### (A) 路由分流：`packages/cli/src/services/web-ui.ts:12-27`（`@ 9e96aac`）

```ts
 12:  return <E, R>(api: Effect.Effect<HttpServerResponse.HttpServerResponse, E, R>) =>
 13:    Effect.gen(function* () {
 14:      const request = yield* HttpServerRequest.HttpServerRequest
 15:      const url = new URL(request.url, "http://localhost")
 16:      // Serve the web shell before API authentication so a signed-out browser gets the app's sign-in screen.
 17:      if (
 18:        url.pathname === "/api" ||
 19:        url.pathname.startsWith("/api/") ||
 20:        url.pathname.startsWith("/auth/") ||        // ← 新增认证前缀
 21:        url.pathname === "/openapi.json"
 22:      )
 23:        return yield* api.pipe(
 24:          Effect.catchIf(isRouteNotFound, () => Effect.succeed(HttpServerResponse.empty({ status: 404 }))),
 25:        )
 26:      return yield* assets.pipe(Effect.flatMap((files) => serveUI(request, url, files)))
 27:    })
```

- 行号与 A6 记录（16–26）**完全一致** ⇒ 地基 4「`/` 免认证」在新 tip 上未被改动。
- `serveUI` 主体仍是 **30–55**（A6 的行号继续有效），地基 2 不变。
- **新增（`ecfa884` 之后）**：`63e33bc fix(cli): let the web app start the QR scanner worker (#53278)` 给 CSP 加了 worker 源：

```ts
 61: // qr-scanner decodes in a worker it creates from a blob: URL whenever the browser has no BarcodeDetector (Safari, desktop
 62: // Chrome on Windows and Linux). Without worker-src that worker falls under script-src 'self' and never starts.
 63: function csp(hash = "") {
 64:   return `default-src 'self'; script-src 'self' 'wasm-unsafe-eval'${hash ? ` 'sha256-${hash}'` : ""}; style-src 'self' 'unsafe-inline'; img-src 'self' data: https: blob:; font-src 'self' data:; media-src 'self' data:; connect-src * data: blob:; worker-src 'self' blob:`
 65: }
```
⇒ **纯增补、方向对我们有利**（我们不回灌 CSP 改写，见 §1.3）。

#### (B) 三通道实现：`packages/server/src/middleware/authorization.ts`（`@ 9e96aac`，**该文件 `ecfa884..origin/v2` 内 0 提交**）

```ts
 11: const AUTH_TOKEN_QUERY = "auth_token"
 ...
 31: function credentialFromRequest(request: HttpServerRequest.HttpServerRequest) {
 32:   const url = new URL(request.url, "http://localhost")
 33:   const token = url.searchParams.get(AUTH_TOKEN_QUERY)     // 通道①：query token
 34:   if (token) return decodeCredential(token)
 35:   const match = /^Basic\s+(.+)$/i.exec(request.headers.authorization ?? "")
 36:   if (match) return decodeCredential(match[1])             // 通道②：Basic 头
 37:   return Effect.succeed(emptyCredential())
 38: }
 ...
 57: export function authorizedRequest(request, config) {
 58:   return credentialFromRequest(request).pipe(
 59:     Effect.map((credential) => ServerAuth.authorized(credential, config) || authorizedSessionCookie(request, config)),
 60:   )                                                               //  || 通道③：会话 Cookie
 61: }
 63: function authorizedSessionCookie(request, config) {
 64:   const token = request.cookies[ServerAuth.sessionCookieName(request.headers.host)]
 65:   if (!token) return false
 66:   // Same-site pages on other ports still send this cookie, so only same-origin requests may use it.
 67:   const origin = request.headers.origin
 68:   if (origin !== undefined && URL.parse(origin)?.host !== request.headers.host) return false
 69:   return ServerAuth.verifySession(token, config)
 70: }
 72: export const authorizationLayer = Layer.effect(
 ...
 76:     if (!ServerAuth.required(config)) return Authorization.of((effect) => effect)   // ⚠️ 未设密码=全放行
 ...
 82:         const url = new URL(request.url, "http://localhost")
 83:         if (hasPtyConnectTicketURL(url) || hasPersistentPtyConnectTicketURL(url) || isPairingConnectURL(url))
 84:           return yield* effect                     // 配对链接跳过凭据检查
 85:         if (yield* authorizedRequest(request, config)) return yield* effect
```

#### (C) `packages/server/src/auth.ts`（`@ 9e96aac`，同样 0 提交）

```ts
 16: export const SESSION_TTL_SECONDS = 30 * 24 * 60 * 60
 28: export function required(config: Info) {
 29:   return Option.isSome(config.password) && config.password.value !== ""    // ⚠️ 空串 ⇒ 不需要认证
 30: }
 32: // Session tokens issued by pairing links are accepted anywhere the password is.
 33: export function authorized(credentials: DecodedCredentials, config: Info) {
 34:   if (Option.isNone(config.password) || credentials.username !== config.username) return false
 35:   const password = Redacted.value(credentials.password)
 36:   return password === config.password.value || verifySession(password, config)
 37: }                                            // ← Basic 的 password 位也接受会话 token
 57: // Browsers share cookies across ports on the same host, so the name carries the port to keep local servers apart.
 58: export function sessionCookieName(host: string | undefined) {
 59:   const port = URL.parse(`http://${host ?? ""}`)?.port
 60:   return port ? `opencode_session_${port}` : "opencode_session"
 61: }
```

**A6 的行号复核结论**：`authorization.ts:33/35/59/64/68/76`、`auth.ts:16/40/46/58-60` —— **全部与 A6 记录逐字一致**，在新 tip 上无偏移。

---

### 1.2 我们回环代理的对应行号（`webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/LoopbackProxy.kt`）

| 关注点 | 行号 | 原文要点 |
|---|---|---|
| 注入 Basic | **377–384** | `if (!password.isNullOrEmpty()) { … builder.header("Authorization", "Basic $token"); authSent = true }` |
| 用户名固定 | **102** | `const val BASIC_USER = "opencode"` |
| 口令来源 | **68** | `passwordProvider: () -> String?`（每次转发重读 `SecretStore`，不缓存明文） |
| 空口令行为 | **386** | `Log.w(TAG, "no password in SecretStore -> ${parsed.path} will 401 upstream")` |
| **目标地址逐字拼接** | **288–289** | `val target = base + (parsed.path.ifEmpty { "/" }) + (parsed.query?.let { "?$it" } ?: "")` |
| 剥掉客户端 Authorization | **679–686** | `shouldDropRequestHeader` 含 `"authorization"`（683 行）⇒ 页面永远看不到也管不了凭据 |
| 响应头过滤 | **688–691** | 只丢 `connection/keep-alive/proxy-authenticate/transfer-encoding/trailer`；**`set-cookie` 不丢** |
| 回环 origin | **176** | `fun publicOrigin(): String = "http://127.0.0.1:$port"` |

### 1.3 `/auth/*` 需不需要透传/特殊处理？——**不需要**

**方法**：读 `handleClient` 的目标构造 + `onForward` 的头处理，再对照 `web-ui.ts:20` 的分流条件。

**判定依据（行号）**：
1. **`LoopbackProxy.kt:288–289` 是纯逐字拼接**——`base + path + query`，**没有任何路径白名单/黑名单/分流逻辑**。`/auth/probe` 这样的路径会**自动**变成 `http://127.0.0.1:4095/auth/probe` 发给上游。注释（318 行）自述："目标地址是**逐字拼接**（不再有 token/prefix 改写），因为页面与 API 同源。"
2. **`LoopbackProxy.kt:377–384` 的 Basic 注入对所有路径无条件生效**（不分 GET/POST、不看路径）⇒ `/auth/*` 也带着正确凭据过去。
3. **上游把 `/auth/*` 放进认证分支**（`web-ui.ts:20`）⇒ 我们的 Basic 正好满足它，**不需要我们替它做任何鉴权**。
4. **我们不自造 `/auth/...` 路由** ⇒ A6 担心的"我们的 SPA 自造 `/auth/...` 会被上游截走"**对我们不成立**（我们的壳只是转发官方 UI，路由归上游）。

**结论：`/auth/*` 照常转发即可，零改动。**

### 1.4 三通道下 Basic 注入是否仍然有效？——**仍然有效**（逐条推演 + 设备实证）

**推演（为什么三通道不会废掉 Basic）**：
- 通道② Basic 的解析在 `authorization.ts:35`，**无条件执行**；只有通道① `auth_token` 存在时（34 行）才会**提前 return**。我们不注入 `auth_token`，而页面的 query 我们是**逐字透传**（289 行）⇒ 若官方 UI 将来自己带 `?auth_token=`，那也是**上游自己签发、自己校验**的合法 token，**不会破坏**。
- 通道③ Cookie 的 origin 校验（68 行）对**我们反而更宽松**：`LoopbackProxy.kt:682` 把 `origin` **整个 drop 掉**了 ⇒ 上游 `request.headers.origin` 为 `undefined` ⇒ **68 行的跨源拒绝分支永不触发**。且 `shouldDropResponseHeader`（688–691）**不丢 `set-cookie`**，所以即便官方 UI 走 Cookie，会话也能在回环 origin 上正常建立。
- ⚠️ **一个真实副作用（非故障）**：Cookie 名带**上游端口**（`auth.ts:58-60`）⇒ 会话 Cookie 名为 `opencode_session_4095`（上游 Host 是 `127.0.0.1:4095`）。浏览器会把它存在我们的回环 origin 下并回传，我们**逐字转发**给上游，上游按 `request.headers.host`（`127.0.0.1:4095`）取名 ⇒ **名字对得上，Cookie 通道也通**。这意味着**我们的 Basic 注入与上游会话 Cookie 是叠加而非互斥**，最坏情况是两条都通过。

### 1.5 设备验证（三证）

**前置断言（先证明"已鉴权 + 数据已到"，原文）**：
```
10-05 05:46:01.031  4010  4010 I OcWebHost: F5 probe before load path=/api/info
10-05 05:46:01.050  4010  4010 I OcWebHost: F5 probe OK code=200, loading official UI
10-05 05:46:01.051  4010  4010 I OcWebHost: home=http://127.0.0.1:44527/ backend=http://127.0.0.1:4095 passwordPresent=true
10-05 05:46:01.051  4010  4010 I OcPerf  : I.proxyListening t=253ms port=44527 mode=gzip
10-05 05:46:01.066  4010  4010 I OcPerf  : J.loadUrlIssued t=268ms url=http://127.0.0.1:44527/
10-05 05:33:50.683  3169  3169 I OcPerf  : E2.perfProbeRegistered t=318ms
… 31 条 /_assets/*.js|css 经代理全部 200
```
⇒ 探针 200、口令已在、官方 UI 已加载、31 个资源已到位 = **已鉴权 + 数据已到** 成立。

**证① Basic 注入仍能进官方 UI**（同一路径，代理 vs 直连对照）：
```
/api/info        proxy=[200 application/json 131]  direct=[401 0]
/api/session     proxy=[200 application/json 5185] direct=[401 0]
```
> 上游无凭据一律 401，**经我们的回环代理一律 200** ⇒ 唯一差别就是 `LoopbackProxy.kt:383` 注入的那一行 Basic。**闭环。**

**证② `/auth/*` 能正常往返**（代理日志 + 状态码原文）：
```
/auth/probe      proxy=[200 text/html 1809]  direct=[200 text/html 6147]
```
⚠️ **必须如实标注**：这两个 200 **不是**认证通过的证据，而是 **SPA 回落**。原因已核实：
```
$ curl --compressed http://127.0.0.1:4095/api/info
{"version":"2.0.14","pid":3748,"urls":["http://127.0.0.1:4095"],"paths":{"tmp":"C:\\Users\\xiufe\\AppData\\Local\\Temp\\opencode"}}
```
**在跑的后端是 `2.0.14`**，而上游 `9e96aac` 是 `2.0.23`（`packages/cli/package.json` `"version": "2.0.23"`）⇒ **2.0.14 早于 `/auth/` 前缀那次变更**，它不认识 `/auth/*`，于是走旧逻辑回落成 `index.html`（`/`、`/auth/probe`、`/nonexistent-xyz` 三者 size **同为 6147**，是同一份 index.html）。
⇒ **在 2.0.14 上无法运行期证明 `/auth/*` 的认证路由**，这一条**未闭环**，但由 §1.3 的行号推演 + §1.4 的不变性证据补足（`web-ui.ts:20` 原文已核）。**不编造 2.0.23 的运行期结论。**

**证③ 会话/消息 API 正常**（经回环代理，真实业务数据）：
```
$ curl --compressed http://127.0.0.1:44527/api/session      → len=27953
head: {"data":[{"id":"ses_efc722bf9ffeWaRdoFTwbmYj1Y","projectID":"ae54078c0e09bf8e7cbeefd35db19aaa6b064e46",
       "agent":"build","model":{"id":"mimo-v2.6-pro","providerID":"xiaomi-token-plan-cn","variant":"defa…
session ids = ses_efc722bf9ffeWaRdoFTwbmYj1Y, ses_f408ec4a0ffeKjpcGKG4kfGEBR, ses_f41f7138cffeB43nHxQAyNE1uf

$ curl --compressed http://127.0.0.1:44527/api/session/ses_efc722bf9ffeWaRdoFTwbmYj1Y/message   → len=23022
head: {"data":[{"id":"msg_10a6d9594001WcQ5KjG5LQuUmg","time":{"created":1791176316308},"type":"idle","outcome":"succeeded"},
       {"id":"msg_10a6d8077001tmiOPWXA31OQtv","time":{"created":1791176311453,"streamed":1791176315917,"compl…
```
⇒ 会话列表、消息时间线（`msg_*`、`streamed`、`outcome`）**都是真实业务数据**，非桩。落盘：`_f20_api_session.json` / `_f20_api_message.json`。

**判定：① 认证 = `仍兼容`，零改动。**
（唯一"要改"的方向是 §2.4 的空口令问题，与认证注入逻辑无关。）

---

## 2. ② 部署明文约束

### 2.1 上游硬约束原文（`@ 9e96aac`）

`packages/app/src/servers/connect/camera.ts:4-6`（该文件 `ecfa884..origin/v2` 内 **0 提交**）：
```ts
  4: export function createCameraAvailability() {
  5:   const platform = usePlatform()
  6:   const supported = platform.platform === "web" && window.isSecureContext && !!navigator.mediaDevices?.getUserMedia
```

### 2.2 我们的回环来源上 `window.isSecureContext` = ? → **`true`**（实测原文）

**方法**：WebView **未开 CDP**（源码内无 `setWebContentsDebuggingEnabled` ⇒ 无法远程调试）；改用壳内既有的调试注入通道 `WebHostActivity.kt:82`（`EXTRA_DEBUG_EVAL`）、`:857`（`if (!BuildConfig.DEBUG) return`）、`:862-863`（`evaluateJavascript` 结果打 logcat tag `OcWebHost/Eval`）。
**该通道只在 DEBUG 构建生效**，所以为读这一个值，**临时改装了 DEBUG 包**（`research/deliver/opencode-shell-web.apk`，md5 `9f511c5a2d865cca173d62a57ba956a0`）。
（踩坑记录：设备 shell 会吃掉 JS 里的引号，3 次尝试分别得到 `null` / `syntax error` / 逗号运算符只返回末位 `interactive`；最终用**无字符串字面量的数组字面量**解决。）

**原文（`OcWebHost/Eval`，顺序 = `isSecureContext, origin, host, protocol, mediaDevices, getUserMedia, BarcodeDetector`）**：
```
10-05 05:50:13.952  4783  4783 I OcWebHost/Eval: eval=[window.isSecureContext,location.origin,location.host,
  location.protocol,!!navigator.mediaDevices,!!(navigator.mediaDevices&&navigator.mediaDevices.getUserMedia),
  typeof BarcodeDetector] -> "true,http://127.0.0.1:45707,127.0.0.1:45707,http:,true,true,function"
```
同一时刻的前置断言：
```
10-05 05:50:12.413  4783  4783 I OcWebHost: G1 webview version=124.0.6367.219 major=124 min=105 tooOld=false
10-05 05:50:12.726  4783  4783 I OcWebHost: F5 probe OK code=200, loading official UI
10-05 05:50:12.740  4783  4783 I OcWebHost: home=http://127.0.0.1:45707/ backend=http://127.0.0.1:4095 passwordPresent=true
```
落盘：`_f20_eval_isec.txt`。

### 2.3 扫码链是否被 `isSecureContext` 挡住？→ **不被挡住**

| `camera.ts:6` 的四个条件 | 实测 | 结论 |
|---|---|---|
| `platform.platform === "web"` | 我们的 WebView 加载官方 web UI | ✅ |
| **`window.isSecureContext`** | **`true`**（§2.2 原文） | ✅ |
| `navigator.mediaDevices` | `true` | ✅ |
| `.getUserMedia` | `true` | ✅ |

- **交叉印证（F3 既有事实）**：`research/web/FIX_F3.md` 记录在回环上拿到过 `getUserMedia` live 流。`navigator.mediaDevices` **本身就是 secure-context-only API**（非安全上下文下 `navigator.mediaDevices` 为 `undefined`）⇒ 我们的实测 `mediaDevices=true` 与 F3 的 live 流**互相印证**，同一结论、两条独立证据。
- 另有一条更强的旁证：上游 `63e33bc` 给 CSP 补 `worker-src 'self' blob:` 的注释明写 **"desktop Chrome on Windows and Linux 没有 `BarcodeDetector`，qr-scanner 会从 blob: URL 里建 worker"**；而我们实测 **`typeof BarcodeDetector === "function"`** ⇒ **这台设备根本不需要那个 worker 兜底**，扫码解码器原生可用。
- ⚠️ **边界必须说清**：A6 说的"明文 LAN 访问会挡住扫码"**对 `http://<LAN-IP>` 成立，但对我们的 `http://127.0.0.1:<port>` 不成立** —— 回环地址是规范里的 *potentially trustworthy origin*，Chromium 直接判为 secure context。**我们的托管形态（回环 + `adb reverse`）恰好落在安全侧。**

### 2.4 "不设密码即无认证" —— **我们的宿主是否总会设密码？** → **总会设（`onCreate` 硬门控），但存在一条真实的绕过路径**

**（a）宿主确实总会设密码 —— 依据行号**：
- `WebHostActivity.kt:354-363`（`startProxyAndLoad`）：
  ```kotlin
  358:        val password = SecretStore.getSecret(this, SecretStore.KEY_SERVER_PASSWORD)
  359:        if (password.isNullOrEmpty()) {
  360:            Log.i(TAG, "no password in SecretStore, asking")
  361:            showSetupDialog(backend)
  362:            return                       // ← 不设密码就 return，**永不 loadUrl**
  363:        }
  ```
- `SecretStore.kt:81-86`（`getSecret`）：`?.takeIf { it.isNotEmpty() }` ⇒ **空串一律当"没有"**。
- `SecretStore.kt:89-96`（`setSecret`）：`if (value.isNullOrEmpty()) remove(key) else putString(...)` ⇒ **空串被删键，物理上存不进去**。
- `LoopbackProxy.kt:377` / `386`：空 ⇒ **不注入 Authorization** ⇒ 上游必然 401。
⇒ **正常路径下我们的壳永远不会在"无口令"状态下加载官方 UI。**

**（b）🔴 但存在两条真实的"无口令 ⇒ 后端全放行"路径**（这是安全问题，明确写出来）：

**路径①（对话框的负按钮，最直接）** —— `WebHostActivity.kt:634-637`：
```kotlin
634:            .setNegativeButton("先不配置") { _, _ ->
635:                Log.w(TAG, "setup skipped -> API will 401")
636:                launch(defaultBackend)                 // ← 不问口令，直接起代理 + 加载
637:            }
```
**注释自己写的是"will 401"——这个假设只在"后端已设密码"时成立。** 若用户的后端**没设密码**（`auth.ts:28-30` 的 `required()` 为 false ⇒ `authorization.ts:76` 直接 `Authorization.of(effect => effect)` 全放行），那么"先不配置"这条路会得到一个**功能完全正常、但没有任何认证的官方 UI**——而我们的代理照样忠实转发（`LoopbackProxy.kt:288-289` 逐字拼接，不做任何门控）。

**路径②（正按钮传空串）** —— `WebHostActivity.kt:641-644`：
```kotlin
641:                val pass = passInput.text?.toString().orEmpty()
642:                SecretStore.setSecret(this, SecretStore.KEY_BACKEND_URL, url)
643:                SecretStore.setSecret(this, SecretStore.KEY_SERVER_PASSWORD, pass)   // 空串 ⇒ 删键
644:                Log.i(TAG, "credentials stored backend=$url passwordPresent=${pass.isNotEmpty()}")
645-649:              probeThenLaunch(url, pass)          // ← 注意：探针用的是**局部** pass
```
⚠️ 这里有一处**值得记一笔的不一致**：`setSecret` 已把空串**删键**，但 `probeThenLaunch(url, pass)` 传的是**局部变量** `pass`（空串）。由于 `:358` 的门控在下次启动时读的是 `SecretStore`（已无键）⇒ 下次冷启动会**重新弹对话框**。**功能上是自愈的**（不会静默裸奔），但意味着"用户填了空口令"这一状态**跨不过冷启动**。真正的裸奔窗口只有**当次进程内**——而此时代理确实不注入凭据，**行为与注释一致**。**不构成路径①那种"用户以为配好了"的持久裸奔。**

**结论（②）**：
- **我们的宿主在正常启动路径下总会设密码**（`:359` 硬门控 + `SecretStore` 两处空串拒绝）⇒ **我们自己的托管不会主动制造无认证后端**。
- **但"宿主设了口令"≠"后端启用了认证"**。我们的壳**从不去校验后端是否真的要求认证**（`/api/info` 401 才说明后端在门禁）。**只要用户的后端没设密码，我们的壳会照常转发全部 `/api/*`**——这是**上游设计决定**（`fetch.ts:32-34` 上游自述："omitting it serves unauthenticated, so an embedder without a password must front the handler with its own access control"），**责任在上游，但风险落在我们这个 embedder 身上**。
- **判定：`有风险`**（不是"不受影响"）。

### 2.5 最小修法（**仅方案，未改代码**——按派单要求，发现问题只写方案并停下报告）

> 派单原文："若发现问题需要改 `webapp/**`，先只写方案并停下报告，除非是一行以内且低风险的修正"。本项**不满足"一行以内且低风险"**（涉及认证语义），故**只给方案、未动 `webapp/**`**。

**最小修法（建议 A，单点、约 3 行，不改代理、不改 UI 语义）**：
在 `WebHostActivity.kt:634-637` 的"先不配置"分支加载前，加一次**无凭据探针**，若 `/api/info` 在**不带 Authorization** 时返回 **200**，就明确告知"你的后端未启用认证，API 将对同网段完全开放"，并要求用户确认（复用现成的 `probeThenLaunch` / `BackendProbe` 结果分类，不必新增类）。
- 落点：`WebHostActivity.kt:635-636` 之间。
- 理由：这是**唯一能把"用户以为配好了"与"后端真的在门禁"对齐**的位置；`:644` 已经有 `passwordPresent=` 这样的日志位可复用。
- 风险：低（只加提示与一次探针，不改转发逻辑、不改凭据处理）。

**建议 B（可延后）**：`LoopbackProxy.kt:386` 的告警日志已经够用，但可把它升级为**一次性显著提示**，让"未设口令"这件事在 UI 上可见，而不只是 logcat。

---

## 3. ③ 补当天上游证据

### 3.1 fetch 尝试 —— **成功**

```powershell
git -C _upstream -c http.proxy=http://127.0.0.1:7897 -c https.proxy=http://127.0.0.1:7897 fetch origin v2
```
原文：
```
From https://github.com/anomalyco/opencode
 * branch            v2         -> FETCH_HEAD
   ecfa884..9e96aac  v2         -> origin/v2
```
（`EXIT=0`；日志落盘 `_f20_fetch.log`。**未切分支、未 reset、未切工作区**。）

### 3.2 `0a46301` 与 `ecfa884` 之间的确切差距 —— **已补上（提交数 + 时间）**

```powershell
$ git -C _upstream cat-file -t 0a46301
commit                                  ← A6 时是 "fatal: Not a valid object name"，现在**存在了**
$ git -C _upstream log -1 --format="%H|%ci|%an|%s" 0a46301
0a46301e36d7edd517dd89736acc5daf9c74898e|2026-10-04 13:59:27 +0000|opencode-agent[bot]|chore(core): refresh bundled models.dev snapshot

$ git -C _upstream rev-list --count ecfa884..0a46301
5                                       ← ★ 与 A3 当年"共 5 个提交"的说法**独立复算一致**
$ git -C _upstream rev-list --count 0a46301..origin/v2
23
$ git -C _upstream rev-list --count ecfa884..origin/v2
28                                      ← ★ A6 到今天之间的全部增量
```

| 差距项 | A6 时的状态 | **F20 实测** |
|---|---|---|
| `0a46301` 是否在对象库 | ❌ `fatal: Not a valid object name` | ✅ **已存在**（fetch 补齐） |
| `ecfa884 → 0a46301` 提交数 | 未核对（下界 ≥5） | ✅ **恰好 5**（**证实 A3**） |
| `0a46301 → 9e96aac` 提交数 | 未核对 | ✅ **23** |
| `ecfa884 → 9e96aac` 提交数 | 未核对 | ✅ **28** |
| 时间跨度 | 部分 | `ecfa884` **2026-10-04T08:21:24Z** → `0a46301` **2026-10-04T13:59:27Z**（**+5h38m**）→ `9e96aac` **2026-10-05T05:24:44Z**（再 **+15h25m**） |

**新 tip 原文**：
```
9e96aacd649f5dcd31a0a34cb2e73d91142b9272|2026-10-05 05:24:44 +0000|opencode|sync release versions for v2.0.23
```
⇒ 上游版本 `2.0.22 → 2.0.23`。

### 3.3 §3.2/§3.3 变更的**精确 commit 归属** —— **已补上**

**方法**：`git -C _upstream log --format="    %h %s" ecfa884..origin/v2 -- <path>`（pathspec 限定到 8 个关键文件）。

| 文件 | `ecfa884..9e96aac` 触及它的提交 | 判定 |
|---|---|---|
| `packages/cli/src/app-assets.ts` | **（无）** | 地基 1 在**含今天的窗口内零改动** |
| `packages/cli/src/services/web-ui.ts` | **`63e33bc` fix(cli): let the web app start the QR scanner worker (#53278)** | ⚠️ **唯一有变更**（§1.1(A)） |
| `packages/server/src/middleware/authorization.ts` | **（无）** | **三通道认证在新 tip 上逐字不变** |
| `packages/server/src/auth.ts` | **（无）** | 同上 |
| `packages/server/src/process.ts` | **（无）** | `/api/info` 门禁位置不变 |
| `packages/gui-extensions/src/main.ts` | **（无）** | 地基 3 静态 compose 不变 |
| `packages/app/package.json` | `9e96aac` sync release versions for v2.0.23 | 仅版本号 |
| `packages/app/src/servers/connect/camera.ts` | **（无）** | `isSecureContext` 门槛不变 |

**⇒ 对 A6 §3.2 / §3.3 的最终归属判定**：
- A6 §3.2（`/auth/` 前缀重写）**发生在 `ecfa884` 之前**（其自身记录里 `080b767`/`eccf0b3`/`2caba90` 属 `702a73d..ecfa884` 窗口），**与本轮 +28 无关**；本轮 28 个提交**没有再次动过**它。
- A6 §3.3（三通道认证）**同样在 `ecfa884` 之前完成**，本轮 28 个提交对 `authorization.ts` / `auth.ts` **零改动** ⇒ **A6 §3.3 的全部行号与结论在新 tip 上原样有效**。
- A6 §5-#3 记的"未逐一验证哪个 commit 引入哪一行"——**该文件级归属本轮已闭合**；**逐行归属（如 `/auth/` 具体由哪个 commit 引入）本轮未做**，因为那需要 `git log -S` 逐串回溯到 `702a73d` 之前，而 shallow 边界仍在 `702a73d`（见 §4）。

**shallow 状态（如实记录，本轮未改）**：
```
_upstream/.git/shallow = 702a73d91ad5356e491145b9d9280a1572eb44f5
_upstream/.git/config  = promisor = true / partialclonefilter = blob:none
```
⇒ 窗口 `702a73d..9e96aac` 现在可完整核对；**`702a73d` 之前的历史仍需联网回溯**。

### 3.4 28 个提交里与我们相关的那两个（**新增发现，A6 之后**）
```
63e33bc|2026-10-05 14:53:49 +1000|fix(cli): let the web app start the QR scanner worker (#53278)   ← 改 web-ui.ts 的 CSP
c542a43|2026-10-05 03:02:36 +0000|fix(cli): indent every row of the pairing QR code on Windows (#53265)
```
⇒ **上游当天就在修扫码/配对链**，方向与我们一致（都在把扫码做可用），**不构成新增风险**。

---

## 4. 未闭环清单

| # | 未闭环项 | 原因（原文级） |
|---|---|---|
| 1 | **`/auth/*` 的运行期往返证明** | 在跑后端是 **`2.0.14`**（`{"version":"2.0.14","pid":3748,…}`），早于 `/auth/` 前缀变更（上游已是 `2.0.23`）⇒ 2.0.14 不认识该前缀、回落成 `index.html`（`/`、`/auth/probe`、`/nonexistent-xyz` size **同为 6147**）。**未编造 2.0.23 的运行期结论**；改由 `web-ui.ts:20` 原文 + `LoopbackProxy.kt:288-289` 逐字拼接推演支撑。**要闭环需把后端升到 2.0.23 后复测。** |
| 2 | **`/auth/*` 的**逐行** commit 归属** | 需 `git log -S` 回溯到 `702a73d` 之前，而 shallow 边界仍是 `702a73d`。**文件级归属已闭合**（§3.3），逐行未做。 |
| 3 | **`git verify-commit` 签名校验** | 同 shallow/未联网回溯，本轮**未做**，如实记录。 |
| 4 | **`app-assets.ts` 内嵌资源在 `2.0.23` 发行构建中是否非空** | 需从源码构建，超出只读 + 不许改 `webapp/**` 范围。`app-assets.ts:16` 的 `Effect.fail` 分支仍未端到端验证。 |
| 5 | **② 的最小修法未实施** | 派单要求"发现问题只写方案并停下报告"，且该项不满足"一行以内且低风险"⇒ **只给方案（§2.5），未改 `webapp/**`**。 |
| 6 | **`isSecureContext` 是在 DEBUG 包上测的** | 通道 `WebHostActivity.kt:857` 仅 `BuildConfig.DEBUG` 生效。`configureSettings` 两包同源 ⇒ WebView 上下文配置相同；**但严格说该值取自 DEBUG 包**。① 的三证取自**交付 release 包**（md5 `5227c2ac67f5a613c32adacdbb7783e0`）。 |

---

## 5. 收尾复核输出（原文抄录）

```
[13:50:47] === BEFORE kill ===
[13:50:47] adb devices: List of devices attached | emulator-5710	device | emulator-6450	device |
[13:50:48]   pid=2904 avd=OC_Deliver_R2 port=6450 MINE=True
[13:50:48]   pid=34772 avd=OC_Deliver_R2 port=6450 MINE=True
[13:50:48]   pid=12128 avd=T90_Phone port=5710 MINE=False          ← 用户的实例，未碰
[13:50:48] === geometry reset (before kill) ===
[13:51:14]   Physical size: 2560x1600
[13:51:14]   Physical density: 320                                ← 已无 Override
[13:51:14] === reverse remove (only ours) ===
[13:51:14]   reverse --list after: ''                             ← 只摘自己那条
[13:51:14] === emu kill (ONLY emulator-6450) ===
[13:51:14]   emu kill: OK: killing emulator, bye bye
[13:51:14]   emu kill: OK
[13:51:39] === AFTER: 复核 ===
[13:51:39]   emulator/qemu process count = 1
[13:51:39]   OC_*/Medium_Desktop_B residual = 0                    ← ★ 我们自己的残留 = 0
[13:51:39]   remaining: pid=12128 avd=T90_Phone port=5710          ← 只剩用户的 T90_Phone
[13:51:40]   port 6450 LISTEN = 0
[13:51:41]   port 6452 LISTEN = 0
[13:51:42]   port 6454 LISTEN = 0
[13:51:42]   port 6456 LISTEN = 0
[13:51:42]   port 6458 LISTEN = 0
[13:51:43]   port 6460 LISTEN = 0
[13:51:43]   adb devices now: List of devices attached | emulator-5710	device |
[13:51:43]   backend 4095 LISTEN = yes pid=3748                     ← 与开轮前同一 pid，全程未起停
[13:51:43]   .agent-gradle-lock exists = False
[13:51:43] === closeout done (未执行 adb kill-server；未用 -a/通配符；只按 serial) ===
```

- **故意保留：无。**
- 未执行 `adb kill-server`；未用 `-a` / 通配符杀进程；`emu kill` **只对 `emulator-6450` 这一个 serial** 发过。
- 本机唯一残留进程是**用户的 `T90_Phone`（port 5710）**，`MINE=False`，**全程未对其执行任何命令**（开轮时它不在 `adb devices`，是本轮中途出现的）。

### 碰过的 serial

| serial | AVD | 是否本轮自建 | 本轮对它做过什么 |
|---|---|---|---|
| `emulator-6450` | `OC_Deliver_R2` | ✅ **是（本轮唯一）** | 几何 `wm` 覆盖 → 装包 → `adb reverse` → 播种/启动 → 三证 + `isSecureContext` → `wm size/density reset` → `reverse --remove` → `emu kill` → 复核 |
| `emulator-5710` | `T90_Phone` | ❌ 否（用户） | **未执行任何命令**（仅在 `dumpsys`/`Get-CimInstance` 的只读枚举里出现过名字） |

### 是否碰过非自建实例

> **否。** 本轮**只碰过 `emulator-6450` 一个 serial**，且它是本轮自建的 `OC_*` AVD。
> **未对任何 `MCRemote_*` / `DSH_*` / `T7x_*` / `Pixel_Tablet` / `Medium_Desktop_B` / `T90_Phone` 执行过任何 adb 或 kill 命令。**
> **未执行 `adb kill-server`、未用 `-a`/通配符。** 后端 `4095` 全程**未起停**（开轮 `pid=3748`、收尾 `pid=3748`，同一 pid），只做 `adb reverse tcp:4095 tcp:4095`（收尾已摘除）。

---

## 6. 证据文件清单

| 文件 | 内容 |
|---|---|
| `_f20_fetch.log` | fetch 原文（`ecfa884..9e96aac`） |
| `_f20_p1.log` / `_f20_p1.logcat.txt` | 起壳 + 前置断言 + 31 条资源 200 |
| `_f20_eval_isec.txt` | **`isSecureContext` 原文**（`OcWebHost/Eval`） |
| `_f20_api_session.json` | 会话列表原文（27,953 B） |
| `_f20_api_message.json` | 消息时间线原文（23,022 B） |
| `_f20_p5.logcat.txt` / `_f20_p7.logcat.txt` | 各轮 logcat |
| `_f20_emu6450.log` | 模拟器启动日志 |
| `_f20_closeout.log` | 收尾复核原文 |
| `_f20_p1.ps1` … `_f20_p7.ps1` | 本轮脚本（可复现） |
