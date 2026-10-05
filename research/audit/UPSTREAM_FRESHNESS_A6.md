# UPSTREAM_FRESHNESS_A6 — 上游新鲜度复核（交付日当天重核「我们的托管假设」）

**审计员**：上游新鲜度复核工程师 A6
**日期**：2026-10-05（本地 CST）
**仓库根**：`C:\sensorsdata\main\program\opencode-android-shell`
**目的**：确认路线 A（四条地基）在**今天**的上游 `origin/v2` 上是否仍然成立——**逐条重核，不抄上次结论**。
**约束遵守**：纯只读核查；未碰设备、未起模拟器、未跑 gradle、未改任何代码、未切换 `_upstream` 工作区、未 `fetch`；唯一写入为本文件（`research/audit/**`）。

---

## 0. 头条结论（先看这个）

> **本次复核的最大发现不是"地基变了"，而是「我们手上能核对的上游对象，本身比上次审计记录的还旧」。**

- 本地 `origin/v2` = **`ecfa884`**（2026-10-04T08:21:24Z）。
- 上次 A3 记录的 tip = **`0a46301`**（2026-10-04T13:59:27Z），**比本地对象新**，且**在本地对象库里根本不存在**。
- 也就是说：**A3 当时的 `0a46301` 不是从本地 git 对象拿到的**（它自述走的是 `api.github.com` REST），而**本地 `_upstream` 克隆自始至终没前进过**，一直停在 A3 自己的**基线** `ecfa884`。
- 因此本轮**能核对的只有 `ecfa884`（2026-10-04T08:21Z 及更早）**；`ecfa884` 之后到今天的增量 **未核对**。
- 在**能核对的范围内**（526 个提交、约 14.9 天），**四条地基全部成立**；但**认证口径与 `/auth/` 路由面在窗口内发生过真实变更**，其中 **1 条会直接影响我们的壳**（见 §4）。

---

## 1. `origin/v2` 当前 HEAD 与「距上次」差距

### 1.1 HEAD（全部来自本地 git 对象，只读）

| 项 | 值 |
|---|---|
| **短 SHA** | `ecfa884` |
| **长 SHA** | `ecfa884ff82e9116e475b71a0c99383b8438d710` |
| **提交标题** | `feat(session-ui): show model variant in message meta (#53112)` |
| **提交时间** | **2026-10-04T08:21:24Z**（= 2026-10-04 16:21 CST） |
| **提交者** | `Luke Parker` |
| 审计基线工作区 `refs/heads/v2` | `702a73d`（2026-09-20T09:37:50Z）——**未切换，仅读对象** |

### 1.2 与上次 `0a46301` 的差距 —— **未核对（且方向对我们不利）**

```
$ git -C _upstream cat-file -t 0a46301
fatal: Not a valid object name 0a46301
$ git -C _upstream rev-list --count 0a46301..origin/v2
fatal: ambiguous argument '0a46301..origin/v2': unknown revision or path not in the working tree
$ git cat-file -t 0a46301            # 主仓库同样没有
fatal: Not a valid object name 0a46301
```

**原因（已核实，非猜测）**：`_upstream` 是一个 **shallow + partial(promisor) 克隆**。

```
=== REFS ===
refs/heads/v2           702a73d  2026-09-20 09:37:50 +0000
refs/remotes/origin/v2  ecfa884  2026-10-04 08:21:24 +0000
=== SHALLOW? === SHALLOW CLONE   ( .git/shallow = 702a73d91ad5356e491145b9d9280a1572eb44f5 )
=== reflog === 702a73d HEAD@{2026-09-20 17:40:54 +0800}: clone: from https://github.com/anomalyco/opencode.git
```

补网失败（**未执行 fetch，以下为只读命令触发的 promisor 取数失败**，与 A3 §0 记录的网络状况一致）：

```
fatal: unable to access 'https://github.com/anomalyco/opencode.git/':
Failed to connect to github.com:443 after 21107 ms: Could not connect to server
fatal: could not fetch 8ddd6eafd83eb888abd09579903798f1823da382 from promisor remote
```

| 差距项 | 结果 |
|---|---|
| **提交数差距（vs `0a46301`）** | **未核对** —— `0a46301` 不在本地对象库，无法 `rev-list`。下界 **≥ 5**（A3 曾看到 `ecfa884..0a46301` 共 5 个提交） |
| **时间跨度（vs `0a46301`）** | 本地最新对象 `ecfa884`（2026-10-04T08:21Z）**早于** A3 的 tip `0a46301`（2026-10-04T13:59Z）约 **5 小时 38 分** ⇒ 本地相对"上次已知 tip"是**落后**，不是领先 |
| **本地可核对的窗口** | `702a73d..origin/v2` = **526 个提交**，2026-09-20T09:37:50Z → 2026-10-04T08:21:24Z（约 **14.95 天**） |

> ⚠️ **必须点名**：本轮**无法**用本地对象复算 A3 那 5 个提交的影响。A3 的"0 变更"结论是**它的证据，不是本轮的证据**。本轮只对 `ecfa884` 及更早负责。

---

## 2. 四条地基逐条复核（`origin/v2` = `ecfa884` 原文 + 行号）

### 地基 1 — 服务端自托管官方前端　✅ **仍然成立**

`packages/cli/src/app-assets.ts`（全文 54 行）。**该文件在整个可核对窗口内无任何提交触及**（`git log 702a73d..origin/v2 -- <path>` 输出为空）。

```ts
 10: export const load = Effect.fn("cli.app-assets.load")(function* () {
 11:   const embedded = yield* Effect.tryPromise(() => import("virtual:opencode-app-assets")).pipe(Effect.option)
 12:   if (Option.isSome(embedded) && (Object.keys(embedded.value.default).length > 0 || !OPENCODE_LOCAL))
 13:     return lazy(embedded.value.default, (key) =>
 14:       brotliDecompressSync(Buffer.from(embedded.value.default[key]!, "base64")),
 15:     )
 16:   if (!OPENCODE_LOCAL) return yield* Effect.fail(new Error("Web UI assets are missing from the CLI build"))
 17:   return yield* sourceAssets()
 18: })
 ...
 20: const sourceAssets = Effect.fnUntraced(function* () {
 21:   const fs = yield* FileSystem.FileSystem
 22:   const root = path.resolve(import.meta.dirname, "../../app/dist")
 23:   const files = yield* fs.readDirectory(root, { recursive: true })
```

**判定：成立。** 三级优先级不变：① 内嵌 `virtual:opencode-app-assets`（11–15，brotli）→ ② 仅 `OPENCODE_LOCAL` 时回落 `../../app/dist`（22）→ ③ 非 LOCAL 且内嵌为空则 `Effect.fail`（16）。
**对我们仍然重要的真实失败模式**：`app-assets.ts:16` —— 若发行构建漏掉内嵌步骤，服务端**抛异常启动**而不是空白页。

---

### 地基 2 — SPA 回落 / 路由 / 资源前缀　✅ **仍然成立**（但本文件在窗口内**发生过变更**，见 §3.2）

`packages/cli/src/services/web-ui.ts`。**`serveUI` 主体（30–55 行）行号与 A3 完全一致，且文件级 diff 显示其未被改动**。原文：

```ts
 30: function serveUI(request: HttpServerRequest.HttpServerRequest, url: URL, assets: AssetMap) {
 31:   const key = url.pathname.replace(/^\//, "")
 32:   const requested = assets[key]
 33:   if ((key.startsWith("_assets/") || key.startsWith("icons/")) && requested === undefined)
 34:     return Effect.succeed(HttpServerResponse.empty({ status: 404, headers: { "cache-control": "no-store" } }))
 35:   const name = requested !== undefined ? key : "index.html"
 36:   const file = requested ?? assets["index.html"]
 37:   if (file === undefined) return Effect.succeed(HttpServerResponse.empty({ status: 404 }))
 38:   if (request.method !== "GET" && request.method !== "HEAD")
 39:     return Effect.succeed(HttpServerResponse.empty({ status: 405 }))
 40:   const html = name === "index.html"
 41:   const revalidate = html || name === "sw.js" || name === "registerSW.js"
 42:   const headers = {
 43:     "content-type": FSUtil.mimeType(name),
 44:     "cache-control": revalidate ? "no-cache" : "public, max-age=31536000, immutable",
 45:     "content-security-policy": html
 46:       ? cspForHtml(typeof file === "string" ? file : Buffer.from(file).toString())
 47:       : csp(),
 48:     "x-content-type-options": "nosniff",
 49:   }
```

**判定：成立。** 行号可沿用 A3：`35–36` = SPA 回落；`33–34` = `/_assets/`、`icons/` **显式硬契约**（缺失直接 404，**不回落成 HTML**）；`31` = 路由 `/`（`key=""` → `undefined` → 落到 `index.html`）；`44` = 不可变缓存。

---

### 地基 3 — 扩展编译进 app　✅ **仍然成立**（文件在窗口内被改过两次，形态未变）

**证据 A**：`packages/app/package.json` 第 66 行
```json
 64:     "@opencode/client": "workspace:*",
 66:     "@opencode/gui-extensions": "workspace:*",
```
（`"version": "2.0.22"` 仍在第 3 行。）

**证据 B**：`packages/gui-extensions/src/main.ts` 第 19–32 行
```ts
 19: export const builtins = Extension.compose(
 20:   context,
 21:   btw,
 22:   debug,
 23:   terminal,
 24:   file,
 25:   review,
 26:   details,
 27:   { ...browser, main: () => import("./browser/main") },
 28:   { ...pairing, main: () => import("./pairing/main") },
 29:   { ...updater, main: () => import("./updater/main") },
 30:   { ...ssh, main: () => import("./ssh/main") },
 31:   { ...wsl, main: () => import("./wsl/main") },
 32: )
```

**判定：成立。** 12 个内置扩展仍是 `Extension.compose` 的**静态字面量**（非动态注册）⇒ 打包期可见性成立，我们"托管即自动获得内置扩展"的前提未被破坏。
> 该文件在窗口内被 `0048c97 feat(gui-extensions): add typed composition and lifetime primitives (#52868)` 与 `f406d93 refactor(app): move GUI features into built-in extensions (#52369)` 触及，但**静态组合形态保持**。

---

### 地基 4 — 免认证与认证口径　✅ **口径未变，且"壳先于认证下发"被上游显式化**（细节见 §3.2、§4）

**(a) `GET /` 免认证** —— `web-ui.ts` 第 16–26 行，注释是上游自己写的：
```ts
 16:       // Serve the web shell before API authentication so a signed-out browser gets the app's sign-in screen.
 17:       if (
 18:         url.pathname === "/api" ||
 19:         url.pathname.startsWith("/api/") ||
 20:         url.pathname.startsWith("/auth/") ||
 21:         url.pathname === "/openapi.json"
 22:       )
 23:         return yield* api.pipe(
 24:           Effect.catchIf(isRouteNotFound, () => Effect.succeed(HttpServerResponse.empty({ status: 404 }))),
 25:         )
 26:       return yield* assets.pipe(Effect.flatMap((files) => serveUI(request, url, files)))
```
⇒ 只有 `/api`、`/api/*`、**`/auth/*`（新增）**、`/openapi.json` 进认证分支；**其余（含 `/`）直接下发壳**。

**(b) `/api/info` 无认证 401 / Basic 200** —— `packages/server/src/process.ts` 第 185–188 行：
```ts
185:     if (request.method === "GET" && url.pathname === "/api/info" && !ready) {
186:       if (!(yield* authorizedRequest(request, auth))) return unauthorizedResponse(request)
187:       return yield* infoResponse(status, version, urls, tmp)
188:     }
```
```ts
206:   const state = yield* status.current
207:   return HttpServerResponse.jsonUnsafe(
208:     { version, pid: process.pid, urls: urls(), paths: { tmp } },
209:     {
210:       status: state.type === "ready" ? 200 : state.type === "failed" ? 500 : 503,
```
`packages/server/src/middleware/authorization.ts`：
```ts
 35:   const match = /^Basic\s+(.+)$/i.exec(request.headers.authorization ?? "")
 36:   if (match) return decodeCredential(match[1])
 ...
 42: // Browsers show a native credentials prompt for a Basic challenge even on fetch, which would stall the web app
 43: // before it can show its own sign-in screen. Only non-browser clients and page navigations get the challenge.
 44: function challengeRequest(request: HttpServerRequest.HttpServerRequest) {
 45:   const mode = request.headers["sec-fetch-mode"]
 46:   return mode === undefined || mode === "navigate"
 47: }
 ...
 50: export function unauthorizedResponse(request: HttpServerRequest.HttpServerRequest) {
 51:   return HttpServerResponse.jsonUnsafe(
 52:     { _tag: "UnauthorizedError", message: UNAUTHORIZED_MESSAGE },
 53:     { status: 401, headers: challengeRequest(request) ? { "www-authenticate": WWW_AUTHENTICATE } : undefined },
 54:   )
 55: }
```
Basic 用户名必须为 `opencode`（`auth.ts:20`、`:34`）。

**🔴 口径精确化（本轮新发现，A3 未点明）**：
> **"/api/info 无认证 401" 是有条件的——仅当服务端配置了密码时才成立。**
> `auth.ts:28-30`：`required(config) = Option.isSome(config.password) && config.password.value !== ""`
> `authorization.ts:76`：`if (!ServerAuth.required(config)) return Authorization.of((effect) => effect)` ⇒ **未设密码 = 全部放行**
> `fetch.ts:32-34` 上游自述：*"omitting it serves unauthenticated, so an embedder without a password must front the handler with its own access control."*
> ⇒ **我们的自托管若不设密码，等于把 API 裸奔到 LAN/公网**；A3 的 401 观测是在 `OPENCODE_SERVER_PASSWORD=opencode` 下取得的。

**判定：成立**（`/` 免认证 + `/api/info` 401/200 两条口径均未变；仅补上"依赖密码配置"这一前提）。

---

## 3. 「会影响我们」的变更清单（可核对窗口 `702a73d..ecfa884`，526 提交）

只列**触及 `packages/*/src` 且可能影响我们托管壳**的。无关提交（TUI 键位、AI 测试录像、models.dev 快照、文档等）不列。

### 3.1 文件级触碰事实（`git log 702a73d..origin/v2 -- <path>`）

| 文件 | 窗口内触及它的提交 | 对我们 |
|---|---|---|
| `packages/cli/src/app-assets.ts` | **（无）** | 地基 1 纹丝未动 |
| `packages/cli/src/services/web-ui.ts` | `080b767`、`eccf0b3`、`2caba90` | ⚠️ **有变更**（§3.2） |
| `packages/app/package.json` | `f406d93`、`d259ae7` 等 | 仅版本号同步 |
| `packages/gui-extensions/src/main.ts` | `f406d93`、`0048c97` | 形态未变（静态 compose） |
| `packages/gui-extensions/src/renderer.ts` | `f406d93`、`56145fc`、`0048c97` | 形态未变 |
| `packages/server/src/middleware/authorization.ts` | `eccf0b3`、`2caba90` | ⚠️ **有变更**（§3.3） |
| `packages/server/src/auth.ts` | `eccf0b3` | ⚠️ **有变更**（§3.3） |
| `packages/server/src/process.ts` | `080b767`、`eccf0b3`、`2caba90` | `/api/info` 门禁位置 |

### 3.2 ⭐ `web-ui.ts`：壳下发路径被重写，**且新增 `/auth/` 认证前缀**

文件级 diff（`git diff 702a73d..origin/v2 -- packages/cli/src/services/web-ui.ts`）实测原文：

```diff
-    api.pipe(
-      Effect.catchIf(isRouteNotFound, () =>
-        HttpServerRequest.HttpServerRequest.pipe(
-          Effect.flatMap((request) => {
-            const url = new URL(request.url, "http://localhost")
-            if (url.pathname === "/api" || url.pathname.startsWith("/api/"))
-              return Effect.succeed(HttpServerResponse.empty({ status: 404 }))
-            return assets.pipe(Effect.flatMap((files) => serveUI(request, url, files)))
-          }),
-        ),
-      ),
-    )
+    Effect.gen(function* () {
+      const request = yield* HttpServerRequest.HttpServerRequest
+      const url = new URL(request.url, "http://localhost")
+      // Serve the web shell before API authentication so a signed-out browser gets the app's sign-in screen.
+      if (
+        url.pathname === "/api" ||
+        url.pathname.startsWith("/api/") ||
+        url.pathname.startsWith("/auth/") ||
+        url.pathname === "/openapi.json"
+      )
+        return yield* api.pipe(
+          Effect.catchIf(isRouteNotFound, () => Effect.succeed(HttpServerResponse.empty({ status: 404 }))),
+        )
+      return yield* assets.pipe(Effect.flatMap((files) => serveUI(request, url, files)))
+    })
 function serveUI(request: HttpServerRequest.HttpServerRequest, url: URL, assets: AssetMap) {
```

- **旧**：所有路径先走 `api`（认证在此），仅在 `RouteNotFound` 时才回落壳。
- **新**：先按前缀分流；**非 API 路径绕开认证直接下发壳**；`/auth/*` 被**新增**进认证分支。
- `serveUI` 主体（30–55）在同一 diff 中**无改动** ⇒ 地基 2 的行号/行为不变。
- **对我们**：① 地基 4「`/` 免认证」被上游**显式化并加固**（不再依赖 `RouteNotFound` 的副作用）；② **新增 `/auth/*` 属于认证命名空间**——若我们的壳自造 `/auth/...` 路由，会被上游截走并要求认证，**不能**再当作 SPA 深链。

### 3.3 ⭐ 认证从"仅 Basic"扩展为"Basic / query token / 会话 Cookie"三重通道

`packages/server/src/auth.ts` 新增（现仍在 HEAD）：
```ts
 16: export const SESSION_TTL_SECONDS = 30 * 24 * 60 * 60
 32: // Session tokens issued by pairing links are accepted anywhere the password is.
 40: export function issueSession(config: Info, now = Date.now()) {
 46: export function verifySession(token: string, config: Info, now = Date.now()) {
 57: // Browsers share cookies across ports on the same host, so the name carries the port to keep local servers apart.
 58: export function sessionCookieName(host: string | undefined) {
 59:   const port = URL.parse(`http://${host ?? ""}`)?.port
 60:   return port ? `opencode_session_${port}` : "opencode_session"
```
`authorization.ts` 第 31–38、57–70 行把它们并列接入：
```ts
 33:   const token = url.searchParams.get(AUTH_TOKEN_QUERY)      // "auth_token"（第 11 行）
 35:   const match = /^Basic\s+(.+)$/i.exec(request.headers.authorization ?? "")
 59:     Effect.map((credential) => ServerAuth.authorized(credential, config) || authorizedSessionCookie(request, config)),
 64:   const token = request.cookies[ServerAuth.sessionCookieName(request.headers.host)]
 66:   // Same-site pages on other ports still send this cookie, so only same-origin requests may use it.
 68:   if (origin !== undefined && URL.parse(origin)?.host !== request.headers.host) return false
```
**对我们**：官方 UI 可能改用**会话 Cookie**（`opencode_session_<port>`）而非 Basic 完成登录。**我们壳里若自己实现认证/注入凭据，会与之冲突**；`origin` 校验（68 行）意味着**跨源/跨端口代理场景下 Cookie 不生效**，退回 401。

### 3.4 ⭐ 扫码/配对链完好，但**新增硬前提：必须安全上下文**

`packages/app/src/servers/connect/camera.ts`：
```ts
  4: export function createCameraAvailability() {
  5:   const platform = usePlatform()
  6:   const supported = platform.platform === "web" && window.isSecureContext && !!navigator.mediaDevices?.getUserMedia
```
- `getUserMedia` 在全仓 `packages/*/src` 下**仅此一处**，**未被窗口内任何提交删除**。
- **对我们**：`window.isSecureContext` 是硬门槛 ⇒ **我们的自托管若以 `http://<LAN-IP>` 明文访问，扫码配对整条链直接不可用**（只能 `localhost` 或 HTTPS）。这是托管部署的**真实硬约束**，上一轮未点名。

### 3.5 ⭐ 断点：**只有 768，没有 1024**

- `(min-width: 768px)` 共 **8 处**：`app/src/runtime/extension/host-apis.tsx:83,368`、`panels.tsx:184,402`、`side-region.tsx:54`、`session/header/session-header.tsx:5`、`session/model.ts:29`、`session/timeline/virtualizer.tsx:89`
- `(max-width: 767px)` 共 **4 处**：`home/route.tsx:14`、`settings/search.tsx:27`、`settings/general/general.tsx:224`、`runtime/extension/settings-pages.ts:13`、CSS 侧 `home/sessions/view.css:1`、`settings/settings.css:31`
- `gui-extensions/src/sdk/host-apis.ts:646` 注释：`/** Viewport under 768px. Reactive. */`
- **`1024px` 媒体查询在 `app/src` / `gui-extensions/src` 下不存在**（`git grep "1024"` 命中的全是字节数常量，如 `20 * 1024 * 1024`）。
- **对我们**：任务书提到的"**~1024dp 桌面断点**"在当前上游**并不存在**。若我们的壳/设计按 1024dp 切桌面，与官方 UI 的实际 768dp 行为**不一致**——官方 UI 在 768–1023px 之间已经是"桌面"形态。

### 3.6 通知种类 union：**未变**（2 种）

`packages/app/src/shell/notifications/notification.tsx`：
```ts
 19: const NotificationBase = {
 20:   directory: Schema.optional(Schema.String),
 21:   session: Schema.optional(Schema.String),
 22:   metadata: Schema.optional(Schema.Unknown),
 23:   time: Schema.Finite,
 24:   viewed: Schema.Boolean,
 25: }
 26: export const Notification = Schema.Union([
 27:   Persistence.struct({ ...NotificationBase, type: Schema.Literal("turn-complete") }),
 28:   Persistence.struct({ ...NotificationBase, type: Schema.Literal("error"), error: SessionError.Error }),
 29: ])
```
⇒ 恰为 `turn-complete` | `error` 两成员，窗口内无增删。

### 3.7 其余触发项：均**未发生破坏**

| 触发项 | 结论 |
|---|---|
| 改动/删除自托管前端入口 | **否**。`app-assets.ts` 窗口内零提交；`virtual:opencode-app-assets` 入口完好（:11） |
| 资源前缀 | **否**。`web-ui.ts:33-34` 原样 |
| 认证口径 | **有变更但兼容面变宽**（§3.3）；`/` 免认证被加固 |
| `getUserMedia` / 扫码配对 | **未破坏**，但新增 `isSecureContext` 前提（§3.4） |
| 通知 union | **未变**（§3.6） |
| 断点 | **768 确认；1024 不存在**（§3.5）——属认知修正，非代码破坏 |

---

## 4. 总判定

### 4.1 四条地基

| # | 地基 | 判定 |
|---|---|---|
| 1 | 服务端自托管官方前端（`app-assets.ts`） | ✅ **成立**（窗口内零改动） |
| 2 | SPA 回落 / 路由 / 资源前缀（`web-ui.ts:30-55`） | ✅ **成立**（行号沿用 30–37 / 33–34；同文件 handler 另有变更，见 §3.2） |
| 3 | 扩展编译进 app（`package.json:66` + `main.ts:19-32`） | ✅ **成立**（12 扩展静态 compose 形态未变） |
| 4 | 免认证与认证口径（`/` 免认证；`/api/info` 401/200） | ✅ **成立**（口径未变；**新增前提**：仅在配置了密码时才 401） |

### 4.2 一句话总判定

> **兼容性：需要跟进（轻量）——在能核对的上游 `ecfa884` 上，四条地基全部成立，我们的壳不会因地基而崩；但有 3 个必须跟进的点 + 1 个必须先补的证据（上游新鲜度）。**

**必须跟进（会影响我们，按优先级）**
1. **🔴 上游新鲜度证据链是断的**：本地 `origin/v2` 停在 `ecfa884`（2026-10-04T08:21Z），比上次记录的 `0a46301`（13:59Z）**还旧**，且 `0a46301` 不在本地对象库；`github.com:443` 不可达、不得 fetch。**交付前必须换一条能取到当天上游的通道复核**（A3 用的是 `api.github.com` REST，本轮按只读约束未采用）。否则"当天兼容性"这句话只覆盖到 2026-10-04 08:21Z。
2. **认证面从"Basic"扩到三重通道**（Basic / `?auth_token=` / `opencode_session_<port>` Cookie，且带 origin 校验）：我们若自建认证或代理转发，**跨源/跨端口下 Cookie 会被拒并回 401**；同时**新增 `/auth/*` 属认证命名空间**，我们的 SPA 自造 `/auth/...` 路由会被截走。
3. **托管部署硬约束两条**：(a) 扫码配对要求 `isSecureContext`（`camera.ts:6`）⇒ **明文 LAN 访问下扫码不可用**；(b) **不设密码 = API 全放行**（`auth.ts:28-30` + `authorization.ts:76`）⇒ 自托管若裸露，等于无认证。

**认知修正（非阻塞，但建议同步给设计/前端）**
4. **上游只有 768px 断点，没有 ~1024dp**。若我们的壳按 1024dp 切桌面，与官方 UI 实际行为不一致（官方在 768–1023px 已是桌面形态）。

**无需动作**
5. 自托管入口、资源前缀、通知 union、12 个内置扩展静态组合、768 断点本身 —— 全部未变。

---

## 5. 未核对清单（**明确未验证，不得当作已验证**）

| # | 未核对项 | 原因 |
|---|---|---|
| 1 | **`0a46301` 及其之后到今天的全部增量**（提交数/时间跨度/是否破坏地基） | `0a46301` 不在本地对象库（shallow 边界 `702a73d`）；`github.com:443` 不可达，本轮不得 fetch。**故"四条地基在 2026-10-04T08:21Z 之后仍成立"未经验证** |
| 2 | `git fetch` / `git verify-commit` 签名校验 / 提交签名 | 同上，网络不可达 |
| 3 | **§3.2 / §3.3 变更的精确 commit 归属** | `git log -S` / `git show <commit> -- <path>` 需要 promisor 缺失 blob，联网取回失败。可确认的只有**文件级**证据：`702a73d..origin/v2` 的 diff 原文 + 触及该文件的三/四个 commit 的 subject（`080b767`、`eccf0b3`、`2caba90`、`0048c97`、`f406d93`），**未逐一验证哪个 commit 引入哪一行** |
| 4 | **运行期复验**（`/` 200、`/_assets/*` 200+immutable、缺失 `_assets` 404、深链回落、`/api/info` 401/200） | 本轮为**纯只读**核查，未起服务。A3 §3 的运行期结论覆盖的是 **v2.0.14** 发行版，**不是 `ecfa884` 的构建产物** |
| 5 | `app-assets.ts` 内嵌资源在 `ecfa884` 发行构建中是否非空 | 需从源码 `bun install` + `vite build`，超出只读范围。`app-assets.ts:16` 的 `Effect.fail` 分支因此**未做端到端验证** |
| 6 | `1024dp` 断点是否曾存在过（被删还是从未有） | 需跨版本比对 `packages/app/src` 的历史 CSS/媒体查询；本轮只核了 `origin/v2` 的**当前状态**（当前不存在） |
| 7 | `ecfa884` 之后上游是否已改 `web-ui.ts` / `authorization.ts` | 同 #1 |

---

## 6. 复现命令（全部只读，供下一轮复用）

```powershell
# HEAD 与差距
git -C _upstream log --oneline -1 origin/v2
git -C _upstream log -1 --format="%H|%ci|%s" origin/v2
git -C _upstream cat-file -t 0a46301          # => Not a valid object name（本轮的"陈旧"证据）
git -C _upstream rev-list --count 702a73d..origin/v2
if (Test-Path _upstream/.git/shallow) { Get-Content _upstream/.git/shallow }

# 四条地基
git -C _upstream show origin/v2:packages/cli/src/app-assets.ts
git -C _upstream show origin/v2:packages/cli/src/services/web-ui.ts
git -C _upstream show origin/v2:packages/gui-extensions/src/main.ts
git -C _upstream show origin/v2:packages/server/src/middleware/authorization.ts
git -C _upstream show origin/v2:packages/server/src/auth.ts
git -C _upstream diff 702a73d..origin/v2 -- packages/cli/src/services/web-ui.ts

# 破坏性扫描（pathspec 限定 packages/*/src）
git -C _upstream grep -n "getUserMedia" origin/v2 -- packages/app/src packages/gui-extensions/src packages/cli/src
git -C _upstream grep -n "min-width"  origin/v2 -- packages/app/src packages/gui-extensions/src
git -C _upstream grep -n "@media"     origin/v2 -- packages/app/src
```
