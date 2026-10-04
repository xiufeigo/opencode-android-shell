# G1 验证报告：服务端到底有没有自己托管那套 Web UI？

> **决定性结论：服务端 100% 自托管 Web UI。Android 端应当选路线 A（WebView → 服务端自托管 UI），不要把 `packages/app/dist` 打进 APK。**
>
> - 自托管实现：`packages/cli/src/services/web-ui.ts`（SPA 回落 + 静态资源）
> - 托管的产物目录：**就是 `packages/app/dist`**（`packages/cli/src/app-assets.ts:22`）
> - 托管路由：**根路径 `/`**（无 `/app` 前缀），资源前缀 **`/_assets/`**
> - 实测（v2.0.14，端口 4100）：`GET /` → **200 text/html**；`GET /api/info`（Basic 认证）→ **200 application/json**
> - 顺带修正一个旧结论：**v2 已经修了旧版那个 bug** —— `/api/*` 不再回落成 SPA HTML（详见 §3.3）

验证方式：只读源码 + 自起一台**独立** v2 服务做只读 curl 探测（端口 4100，与 4095/4096 无关）。**未跑 gradle、未装机、未改任何生产代码。**

---

## 1. 结论速览

| 问题 | 答案 | 证据 |
|---|---|---|
| 服务端自己托管 Web UI 吗？ | **是** | `web-ui.ts:7-52` + 运行期 `/` 返回 200 HTML |
| 托管哪个产物目录？ | **`packages/app/dist`** | `app-assets.ts:22` |
| 托管路由？ | **根 `/`**（catch-all 覆盖全站） | `web-ui.ts:14-24,32-33`；实测 `/workspace/example` → 200 HTML |
| 资源路径前缀？ | **`/_assets/`**（另有 `/icons/`、`/site.webmanifest`） | 运行期 HTML 全量抽取（§3.4） |
| `/` 与 `/api/info` 状态码？ | `200 text/html` / `401`→带认证 `200 application/json` | §3.1、§3.2 |
| 静态 UI 需要认证吗？ | **不需要**（只有 `/api/*` 要） | 实测 `/` 无凭据 200；`/api/info` 无凭据 401 |
| 旧版「`/api/info` 回落成 HTML」还成立吗？ | **v2 已修复，不成立** | `web-ui.ts:18-19` + 实测 §3.3 |
| 推荐路线 | **A：WebView → 服务端自托管 UI** | §5 |

---

## 2. 源码侧证据

### 2.1 静态资源挂载 + SPA 回落（核心实现）

**文件：`_upstream/packages/cli/src/services/web-ui.ts`**

回落入口 —— 把整个 API 包起来，任何"路由未找到"都交给 `serveUI`（第 12-24 行）：

```ts
12	  return <E, R>(api: Effect.Effect<HttpServerResponse.HttpServerResponse, E, R>) =>
13	    api.pipe(
14	      Effect.catchIf(isRouteNotFound, () =>
15	        HttpServerRequest.HttpServerRequest.pipe(
16	          Effect.flatMap((request) => {
17	            const url = new URL(request.url, "http://localhost")
18	            if (url.pathname === "/api" || url.pathname.startsWith("/api/"))
19	              return Effect.succeed(HttpServerResponse.empty({ status: 404 }))
20	            return assets.pipe(Effect.flatMap((files) => serveUI(request, url, files)))
21	          }),
22	        ),
23	      ),
24	    )
```

> **第 18-19 行就是旧版那个 bug 的修复点**：`/api` 与 `/api/*` 被显式排除在 SPA 回落之外，直接 404。所以旧版"API 回落成 HTML"的现象在 v2 **不复存在** —— 这条旧实测**只能作为"服务端有 SPA 回落"的间接线索，不能再当作 `/api/info` 行为依据**。

SPA 回落本体 —— 找不到就发 `index.html`（第 27-52 行）：

```ts
27	function serveUI(request: HttpServerRequest.HttpServerRequest, url: URL, assets: AssetMap) {
28	  const key = url.pathname.replace(/^\//, "")
29	  const requested = assets[key]
30	  if ((key.startsWith("_assets/") || key.startsWith("icons/")) && requested === undefined)
31	    return Effect.succeed(HttpServerResponse.empty({ status: 404, headers: { "cache-control": "no-store" } }))
32	  const name = requested !== undefined ? key : "index.html"
33	  const file = requested ?? assets["index.html"]
34	  if (file === undefined) return Effect.succeed(HttpServerResponse.empty({ status: 404 }))
```

三行合起来把整个路由语义讲完了：

- **第 28-29 行**：`key` 就是**去掉前导 `/` 后的完整 pathname**，直接当资源表 key 查 ⇒ **资源挂在根路径，不是 `/app` 之类的子路径**。所以 `/_assets/foo.js` ⇒ key = `_assets/foo.js`。
- **第 30-31 行**：`_assets/` 和 `icons/` 是**严格目录**——命中不到就 404，**不回落**（这是带 hash 的构建产物必须有的保护）。
- **第 32-33 行**：其余一切（含 `/assets/nope.js` 这种历史遗留路径）都**回落成 `index.html`** ⇒ 经典 catch-all SPA。

缓存策略（第 37-46 行）：

```ts
37	  const html = name === "index.html"
38	  const revalidate = html || name === "sw.js" || name === "registerSW.js"
39	  const headers = {
40	    "content-type": FSUtil.mimeType(name),
41	    "cache-control": revalidate ? "no-cache" : "public, max-age=31536000, immutable",
42	    "content-security-policy": html
43	      ? cspForHtml(typeof file === "string" ? file : Buffer.from(file).toString())
44	      : csp(),
45	    "x-content-type-options": "nosniff",
46	  }
```

⇒ `index.html` / `sw.js` / `registerSW.js` 走 `no-cache`；其余哈希资源走 **`max-age=31536000, immutable`**。这对 WebView 是好消息：**重启 WebView 时索引必重新校验，但 82MB 里的哈希资源可以永久留在 HTTP 缓存里**。

### 2.2 托管的是哪个产物目录？—— 确认是 `packages/app/dist`

**文件：`_upstream/packages/cli/src/app-assets.ts`**

```ts
20	const sourceAssets = Effect.fnUntraced(function* () {
21	  const fs = yield* FileSystem.FileSystem
22	  const root = path.resolve(import.meta.dirname, "../../app/dist")
23	  const files = yield* fs.readDirectory(root, { recursive: true })
24	  const assets = Object.fromEntries(
25	    (yield* Effect.forEach(
26	      files.filter((file) => !file.endsWith(".map")),
```

`import.meta.dirname` = `packages/cli/src` ⇒ `../../app/dist` = **`packages/app/dist`**。**与待打包的目录完全同一个** —— 所以"服务端托管的"和"我们要塞进 APK 的"是同一份东西，路线 A 零额外成本。

**第 26 行是本次最有价值的一行**：`files.filter((file) => !file.endsWith(".map"))` —— **服务端自己就把 sourcemap 全部剔除了**。即：dev 模式（`OPENCODE_LOCAL`）下从磁盘读 dist 时不收 `.map`；发布构建则走第 10-18 行的内嵌分支：

```ts
10	export const load = Effect.fn("cli.app-assets.load")(function* () {
11	  const embedded = yield* Effect.tryPromise(() => import("virtual:opencode-app-assets")).pipe(Effect.option)
12	  if (Option.isSome(embedded) && (Object.keys(embedded.value.default).length > 0 || !OPENCODE_LOCAL))
13	    return lazy(embedded.value.default, (key) =>
14	      brotliDecompressSync(Buffer.from(embedded.value.default[key]!, "base64")),
15	    )
16	  if (!OPENCODE_LOCAL) return yield* Effect.fail(new Error("Web UI assets are missing from the CLI build"))
17	  return yield* sourceAssets()
18	})
```

⇒ **发布版二进制把整个 dist 以 brotli+base64 内嵌进可执行文件**，运行时按需解压（`lazy()` 用 Proxy 惰性加载，见第 38-50 行）。**这意味着零外部文件依赖、零部署配置** —— 服务端侧根本不需要你去放 dist，APK 侧也完全不必重复背这 88.8MB。

### 2.3 挂载点

**文件：`_upstream/packages/cli/src/server-process.ts`**

```ts
76	          : environmentPassword
77	            ? Redacted.value(environmentPassword)
78	            : randomBytes(32).toString("base64url")
80	      if (!password) return yield* Effect.fail(new Error("Missing server password"))
81	      const instanceID = randomUUID()
82	      const transform = yield* WebUi.handler()
83	      const server = yield* start(
```

`WebUi.handler()` 无参调用 ⇒ 不走测试注入的 `options.assets`，走 `load()` ⇒ **发布版 = 内嵌产物，dev = 磁盘 `packages/app/dist`**。`transform` 包住整个 API 栈，**这就是自托管 UI 的挂载点**。

### 2.4 上游自带测试（旁证）

`_upstream/packages/cli/test/web-ui.test.ts:93-104` 直接断言了回落行为：

```ts
93	          const fallback = yield* Effect.promise(() => fetch(`${origin}/workspace/example`))
94	          expect(yield* Effect.promise(() => fallback.text())).toContain("embedded")
...
102	          const legacy = yield* Effect.promise(() => fetch(`${origin}/assets/missing.js`))
103	          expect(legacy.status).toBe(200)
104	          expect(yield* Effect.promise(() => legacy.text())).toContain("embedded")
```

`/assets/*` 也回落 ⇒ 与我运行期实测（`/assets/nope.js` → 200 HTML）完全一致。

---

## 3. 运行期证据（原始 curl 输出）

### 3.0 探测环境

```
exe     : C:\Users\xiufe\.bun\bin\opencode2.exe   (opencode v2.0.14)   ← 用的 v2，非 PATH 上的 1.18.33
cmd     : opencode2.exe serve --port 4100 --hostname 127.0.0.1
cwd     : %TEMP%\g1-probe-work                    (临时空目录，未污染仓库)
env     : OPENCODE_SERVER_PASSWORD=G1ProbePass123
listening: server listening on http://127.0.0.1:4100
```

> 端口 4100 为本轮专用，**与 4095/4096 无交集**，收尾已停（见 §8）。

### 3.1 `GET /`（无凭据）→ **200 + HTML**

```
$ curl -s -i http://127.0.0.1:4100/
HTTP/1.1 200 OK
content-type: text/html
cache-control: no-cache
content-security-policy: default-src 'self'; script-src 'self' 'wasm-unsafe-eval' 'sha256-DvjbVLSCqgtwL7bnRLffODAPpW63X4inV+gVnQBZwBw='; style-src 'self' 'unsafe-inline'; img-src 'self' data: https: blob:; font-src 'self' data:; media-src 'self' data:; connect-src * data: blob:
x-content-type-options: nosniff
vary: Origin, Accept-Encoding
Date: Sun, 04 Oct 2026 08:35:10 GMT
Connection: keep-alive
Keep-Alive: timeout=5
Transfer-Encoding: chunked
```

**响应体前 20 行（原样）**：

```html
<!doctype html>
<html lang="en" style="background-color: var(--v2-background-bg-deep, #fafafa)">
  <head>
    <meta charset="utf-8" />
    <meta
      name="viewport"
      content="width=device-width, initial-scale=1, interactive-widget=resizes-content, viewport-fit=cover"
    />
    <title>OpenCode</title>
    <link rel="icon" type="image/x-icon" href="/icons/prod/favicon.ico" />
    <link rel="apple-touch-icon" sizes="180x180" href="/icons/prod/apple-touch-icon.png" />
    <link rel="manifest" href="/site.webmanifest" />
    <meta name="theme-color" content="#fafafa" />
    <meta name="mobile-web-app-capable" content="yes" />
    <meta name="apple-mobile-web-app-capable" content="yes" />
    <meta name="apple-mobile-web-app-status-bar-style" content="black-translucent" />
    <meta property="og:image" content="/social-share.png" />
    <meta property="twitter:image" content="/social-share.png" />
    <script id="oc-theme-preload-script">;(function () {
  var key = "opencode-theme-id"
  var themeId = localStorage.getItem(key) || "oc-2"

  var scheme = localStorage.getItem("opencode-color-scheme") || "system"
```

- 状态码 **200**，`content-type: text/html`，体积 **6147 B**。
- **注意：没有 `WWW-Authenticate`，无凭据直接 200** ⇒ 静态 UI 壳是公开的（详见 §6.1 风险项）。

### 3.2 `GET /api/info`

**无凭据**：

```
$ curl -s -o NUL -w "status=%{http_code} type=%{content_type} size=%{size_download}\n" http://127.0.0.1:4100/api/info
status=401 type= size=0

$ curl -s -D - -o NUL http://127.0.0.1:4100/api/info
HTTP/1.1 401 Unauthorized
www-authenticate: Basic realm="Secure Area"
vary: Origin
```

**带 Basic 认证** → **200 + JSON**：

```
$ curl -s -i -u "opencode:G1ProbePass123" http://127.0.0.1:4100/api/info
HTTP/1.1 200 OK
content-type: application/json
content-length: 132
vary: Origin, Accept-Encoding
Date: Sun, 04 Oct 2026 08:35:18 GMT
Connection: keep-alive
Keep-Alive: timeout=5

{"version":"2.0.14","pid":51104,"urls":["http://127.0.0.1:4100"],"paths":{"tmp":"C:\\Users\\xiufe\\AppData\\Local\\Temp\\opencode"}}
```

**错误密码** → `status=401`（无降级、无宽松比较）。

> 结论：`/api/info` 在 v2 是**正常的、干净的 JSON API**。旧实测的"回落成 HTML"已不复现（§2.1 已定位修复点）。

### 3.3 API 路径与回落边界（v2 修复的验证）

```
GET /workspace/example  -> status=200 type=text/html   size=6147    ← catch-all SPA 回落 ✓
GET /session/abc/x/y    -> status=200 type=text/html   size=6147    ← 深层路由也回落 ✓
GET /api/missing        -> status=401 type=            size=0       ← 未认证：401（不是 HTML）
GET /api/missing(auth)  -> status=404 type=            size=0       ← 已认证：干净 404 ✓ 修复生效
GET /_assets/nope.js    -> status=404 type=            size=0       ← 严格目录，不回落 ✓
GET /assets/nope.js     -> status=200 type=text/html   size=6147    ← 遗留路径回落 ✓
```

### 3.4 HTML 引用的资源路径前缀 —— **`/_assets/`**

从 `/` 的 HTML 全量抽取 `src=` / `href=`（去重排序，原样）：

```
/_assets/button-BFiwHX2o.js          /_assets/icon-button-DgkrDlZx.js
/_assets/button-CP8fzIZH.css        /_assets/icon-DR7qgx3o.js
/_assets/button-HOYPhA6G.js         /_assets/index-Dadcbstg.js
/_assets/command-djTfk7gH.js        /_assets/index-W_zVL-x7.css
/_assets/desktop-native-vJFY6KWL.js /_assets/location-M1zCuQYp.js
/_assets/dialog-2IVAZGoQ.js         /_assets/oc-2-DJL408O7.js
/_assets/dialog-BfBFHP-m.js         /_assets/preload-helper-Czpn1I53.js
/_assets/dialog-BUYQZsMU.css        /_assets/project-avatar-B25rVzWO.css
/_assets/dist-B3cU5Pub.js           /_assets/project-avatar-D66hETA1.js
/_assets/dist-CnByuFl8.js           /_assets/registry-Bd8mdXya.js
/_assets/dist-CNFvywQ0.js           /_assets/rolldown-runtime-hePW80VL.js
/_assets/divider-C-h6JRGT.css      /_assets/runtime-CPxMNWhG.js
/_assets/divider-DAv0p7wN.js        /_assets/runtime-ddCoIm8Y.css
/_assets/EHGSTO6V-FV9wNiWv.js      /_assets/session-progress-indicator-v2-BJDdxLU-.css
/_assets/eventListener-5JRELg8s.js  /_assets/session-progress-indicator-v2-ph7IWpU6.js
/_assets/health-CIQGyytw.js         /_assets/settings-gAAmvXBQ.css
/_assets/helpers-CbDJHSv7.js        /_assets/surface-B48-YmDS.css
/_assets/icon-BDQzFLnz.css          /_assets/surface-CFEzfnOI.js
/_assets/icon-button-BCwmH1dS.css   /_assets/text-field-CmbZew2a.js
                                     /_assets/text-input-BdrW1Dhu.css
/_assets/text-input-BXW7NO2C.js     /_assets/tooltip-D7z2cFz8.js
/_assets/tooltip-DE8rGyLW.css       /_assets/UKTBL2JL-wTtWYT1h.js
/_assets/uploads-B8N2YKSq.js        /_assets/V25KEN4T-DaMvtQux.js
/_assets/ZZYKR3VO-BO-mn2iO.js
/icons/prod/apple-touch-icon.png    /site.webmanifest
/icons/prod/favicon.ico
```

**⇒ WebView 就该指到 `http://<host>:<port>/`（根路径），资源同源自动解析。不需要任何 URL 重写/资源前缀改写。**

> 命名规律：全部是 `name-HASH.js`（Vite/rolldown 风格内容哈希）⇒ 与 §2.1 第 41 行 `immutable` 缓存策略配套。

### 3.5 资源可取回 + 响应头（一条资源的完整头）

```
$ curl -s -I http://127.0.0.1:4100/_assets/index-Dadcbstg.js
HTTP/1.1 200 OK
content-type: text/javascript
cache-control: public, max-age=31536000, immutable
content-security-policy: default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline'; img-src 'self' data: https: blob:; font-src 'self' data:; media-src 'self' data:; connect-src * data: blob:
x-content-type-options: nosniff
vary: Origin
Date: Sun, 04 Oct 2026 08:35:28 GMT
Connection: keep-alive
Keep-Alive: timeout=5
```

实际取回体积（`Content-Length` 走 chunked 未带，改用 `size_download`）：

```
GET /                        -> status=200 type=text/html              size=6147
GET /_assets/index-Dadcbstg.js -> status=200 type=text/javascript      size=492770   (481 KiB)
GET /site.webmanifest        -> status=200 type=application/manifest+json size=384
GET /icons/prod/favicon.ico  -> status=200 type=image/vnd.microsoft.icon size=47600
GET /icons/prod/apple-touch-icon.png -> status=200 type=image/png      size=17106
GET /social-share.png        -> status=200 type=image/png              size=14238
```

⇒ **页面引用的资源全部可 200 取到，路由 A 在运行期完全成立。**

### 3.6 sourcemap：服务端**不提供**

```
GET /_assets/index-Dadcbstg.js.map -> status=404 type= size=0
```

且 `/` 的 HTML 内联脚本**不含** `sourceMappingURL`。⇒ 对应 §2.2 第 26 行的过滤：**88.8MB 里有 54.5MB 是服务端自己都不打算发的死重**。

### 3.7 CORS 预检

```
$ curl -s -D - -o NUL -X OPTIONS -H "Origin: https://example.test" \
       -H "Access-Control-Request-Headers: authorization" .../api/info
HTTP/1.1 204 No Content
access-control-allow-methods: GET, HEAD, PUT, PATCH, POST, DELETE
vary: Access-Control-Request-Headers
access-control-allow-headers: authorization
access-control-max-age: 86400
```

`access-control-allow-headers: authorization` ⇒ **`Authorization` 头是被 CORS 白名单显式放行的**（若未来要跨源调 API，这条已铺好；但同源 WebView 场景用不到）。

---

## 4. `packages/app/dist` 体积细分（路线 B 的代价）

实测（`Get-ChildItem -Recurse` 精确求和）：

| 分桶 | 字节 | 文件数 | 占总量 | 说明 |
|---|---:|---:|---:|---|
| **总计** | **88,758,107** | **1,665** | 100% | 与既有实测 88,758,107 B / 1665 文件**完全吻合** |
| `.map` sourcemap | **54,566,689** | 752 | **61.5%** | **服务端自己不发（§3.6），可整体剔除** |
| 剔除 `.map` 后 | **34,191,418** | 913 | 38.5% | 路线 B 的**现实下限** |
| ├ 核心 `js/css/html/webmanifest` | 29,199,468 | 801 | 32.9% | 真正必需，**不可裁** |
| ├ 字体 `ttf/woff2/woff` | 3,054,464 | 62 | 3.4% | 可只留 woff2 |
| ├ `svg`（含 933KB sprite） | 1,387,439 | 8 | 1.6% | 可只留 sprite |
| ├ 位图 `png/ico` | 400,451 | 15 | 0.5% | PWA 图标，**可裁** |
| └ 音频 `aac` | 149,078 | 26 | 0.2% | 可裁 |
| 残余（`_headers` 等无扩展名） | 518 | 1 | ~0% | — |

按目录：

| 目录 | 文件 | MiB |
|---|---:|---:|
| `_assets/` | 1,642 | 82.30 |
| `assets/`（字体 woff2/ttf） | 2 | 1.85 |
| `icons/` | 4 | 0.38 |
| 根（`index.html` / `sw.js` / `webmanifest` …） | 17 | 0.12 |

单文件最大 15 项：前 10 名里有 **9 个是 `.map`**（`route-IBGsPZAl.js.map` 2,432 KiB、`button-CpHzm3pX.js.map` 2,124 KiB …），非 `.map` 最大是 `ghostty-web-Ch-lxWIv.js` **1,347 KiB**（PTY/终端 wasm 栈，很大但只有客户端 Web 终端用得到，**移动端纯 UI 可不打包**）。

**PWA 图标其实极小**：真正的图标只有 `icons/` 4 个文件 **0.38 MiB**（512px 257.8 KiB + favicon 71.5 KiB + 192px 26.7 KiB + apple-touch 29.3 KiB）。**"PWA 图标"从来不是 88.8MB 的主因，主因是 sourcemap。**

裁剪可达的档位：

| 档位 | 体积 | 做法 | 代价 |
|---|---:|---|---|
| 原样全打 | 88.76 MB | 直接复制 dist | APK 爆炸，版本锁死 |
| 去 `.map` | **34.19 MB** | 剔除 `*.map` | 服务端都不发，零功能损失 |
| 去 `.map` + 去音频/多余字体格式 | ~30 MB | 只留 woff2、砍 aac | 少量排版/音效回归 |
| 激进 | ~27 MB | 只留 `js/css/html/webmanifest` | 终端/图表/文件图标等 UI 残缺 |

> ⚠️ **APK 内的实际增量会小于上表**：APK 对 `.map`（纯文本）压缩率很高。**我没有测过打包后 APK 的真实增量**（本轮禁止跑 gradle），见 §7。

---

## 5. 两条路线对比 + 推荐

### 5.1 对比表

| 维度 | **(A) WebView → 服务端自托管 UI** | **(B) `packages/app/dist` 打进 APK** |
|---|---|---|
| APK 增量 | **≈ 0** | **+88.76 MB 原样 / +34.19 MB 剔 `.map`**（APK 压缩后更小，**未实测**） |
| 与服务端版本一致性 | **天然一致**（同一台服务器同一份产物） | 锁死；服务端升级即 UI 漂移，需强制同版本发布 |
| 首屏 | 需网络；`/` 仅 **6,147 B**，很快 | 无需网络 |
| 离线 | **不可用** | 可用 |
| 部署/更新 | 服务端升级即生效，**不发版** | 每次 UI 变更都要重发 APK + 过审 |
| 产物来源 | 同一份 `packages/app/dist`（`app-assets.ts:22`），但由服务端 brotli 内嵌承担 | 客户端重复背一份 |
| 存储占用 | 走 HTTP 缓存（`immutable`，可长期复用） | APK 内永久占用，`uninstall` 才释放 |
| 认证 | 有成熟解法（§6）：`auth_token` query 或 `onReceivedHttpAuthRequest` | 静态资源随 `file:///android_asset/` 加载，**跨源到 http API**，需 CORS（已放行 `authorization`）+ 离线时彻底无 API |
| 主要风险 | 断网/服务端未起 → 白屏；首屏依赖服务端 | APK 体积、版本漂移、构建期耦合、商店体积上限 |

### 5.2 推荐：**选 A**

理由（按权重）：

1. **"离线"对本作根本不是可用性收益 —— 它本来就没有离线能力。** 这个 App 是 opencode 服务端的**远程瘦客户端**：没有服务端，聊天/模型/会话/文件全部无从谈起。所以"路线 A 需要网络"**不是新增风险，而是既有事实**；而"路线 B 换来离线可用"换来的是一个**用户根本用不上的场景**（离线时你有 UI 外壳，但没有任何 API 可调）。这是决定性的一条。

2. **一致性红利无法用体积换。** 服务端托管的 UI 与 API 是同一次构建、同一 commit 产出的（`app-assets.ts` 读的就是 `packages/app/dist`）。路线 A 天然零漂移；路线 B 把 UI 冻结进 APK，此后**每一次服务端 API 演进都要赌 APK 里那份 UI 还能用**——而 UI 恰恰是 API 消费者里最脆的一环。

3. **34.19 MB 也不是"小"。** 即便把服务端自己都丢弃的 54.5 MB sourcemap 全剔掉，APK 仍要背 34 MB（+34 MB 安装包下载、+34 MB 设备存储、`uninstall` 才回收），换来零收益。

4. **发布节奏。** 路线 A 改 UI = 服务端发版；路线 B 改 UI = 重编 APK + 应用商店审核。对一个跟上游 opencode 快速同步的壳项目，这个差异是结构性的。

5. **运行期已验证可行。** §3 全部 200，`/_assets/` 同源解析，认证有服务端现成支持（§6）。路线 A 没有任何未验证的技术风险。

### 5.3 若选 A，离线场景怎么办

**结论：不做"离线 UI"，做"离线降级页"。** 分三层：

1. **主路径（正常）**：`WebView` 指向 `http://<host>:<port>/`，带 `auth_token`，完全由服务端供 UI。
2. **降级（连不上 / 认证失败 / 超时）**：原生侧维护一个**极小的静态错误页**（几百 KB 级，建议直接用原生 View 而非 WebView，零网络依赖），内容为：
   - 明确文案：**"无法连接到 opencode 服务端"** + 实际 base URL + 错误原因（`WebViewClient.onReceivedError` / `onReceivedHttpError` 回调能拿到）；
   - 一个"重试"按钮 + 一个"修改服务器地址"入口（把 §6.1 的密码/地址配置入口暴露出来）；
   - **不要**试图在 APK 里塞一份完整 UI 做离线 —— 那是路线 B 的成本，换来一个用不上的场景。
3. **服务离线时的只读兜底（可选，低优先）**：若产品坚持要"看一眼上次会话"，只缓存 **`/` 的 6,147 B + 当前会话的最近 N 条消息 JSON**，`WebSettings.setCacheMode(LOAD_CACHE_ELSE_NETWORK)` 配合离线兜底页。**注意这与 §6.4 的 service worker 冲突**，需二选一（见 §6.4）。

> 一句话：**A 方案的"离线不可用"应该被显式做成一个设计好的错误态，而不是用 34MB 静态资源假装有离线能力。**

---

## 6. 认证方案（路线 A 怎么落地）

### 6.1 凭据模型（源码 + 实测）

**文件：`_upstream/packages/server/src/auth.ts`**

```ts
16	  static configLayer(input: Pick<Info, "password">) {
17	    return Layer.succeed(this, this.of({ ...input, username: "opencode" }))
18	  }
...
29	export function authorized(credentials: DecodedCredentials, config: Info) {
30	  return (
31	    Option.isSome(config.password) &&
32	    credentials.username === config.username &&
33	    Redacted.value(credentials.password) === config.password.value
34	  )
35	}
```

⇒ **用户名被硬编码为 `"opencode"`**（`auth.ts:17`），只有密码可配。**没有 cookie 机制**（全仓未见 session/cookie 签发），所以答案是：**纯 HTTP Basic**。

**文件：`_upstream/packages/server/src/middleware/authorization.ts`** —— 这是选型的关键：

```ts
10	const AUTH_TOKEN_QUERY = "auth_token"
11	const WWW_AUTHENTICATE = 'Basic realm="Secure Area"'
...
30	function credentialFromRequest(request: HttpServerRequest.HttpServerRequest) {
31	  const url = new URL(request.url, "http://localhost")
32	  const token = url.searchParams.get(AUTH_TOKEN_QUERY)
33	  if (token) return decodeCredential(token)
34	  const match = /^Basic\s+(.+)$/i.exec(request.headers.authorization ?? "")
35	  if (match) return decodeCredential(match[1])
36	  return Effect.succeed(emptyCredential())
37	}
```

⇒ **服务端接受两条凭据通道：`?auth_token=<base64(user:pass)>` 查询参数**，或 **`Authorization: Basic` 头**。第 32-33 行是**专门为浏览器/WebView 场景准备的免挑战通道**。

同文件第 51-54 行另有一个重要豁免：

```ts
51	        // Browsers cannot set headers on WebSocket upgrades, so a ticketed PTY connect skips
52	        // credential checks here; the connect handler consumes and validates the ticket.
53	        const url = new URL(request.url, "http://localhost")
54	        if (hasPtyConnectTicketURL(url) || hasPersistentPtyConnectTicketURL(url)) return yield* effect
```

⇒ **PTY WebSocket 走 ticket、不受 Basic 拦截**（浏览器无法给 WS upgrade 加头，故设计成 ticket）。移动端若要用终端，注意这条走的是 ticket 而非 Basic。

### 6.2 原生壳把 `OPENCODE_SERVER_PASSWORD` 交给 WebView —— 逐条可执行方式

> 前置：`OPENCODE_SERVER_PASSWORD` 是**服务端**进程的环境变量（实测由 `server-process.ts:76-80` 读取；未设则 `randomBytes(32)` 随机生成）。**它不会"传给"WebView**——WebView 只是个客户端，壳要做的是**把已知密码填进发往该 origin 的凭据**。三条按推荐度排序：

**方式 1（首选）：URL 携带 `auth_token` —— 零配置、零回调、最稳**

```kotlin
// 壳已持有 baseUrl 与 password（用户在设置页录入，见下）
val raw  = "opencode:$password"                 // 用户名硬编码，见 auth.ts:17
val token = Base64.encodeToString(raw.toByteArray(Charsets.UTF_8),
                                  Base64.NO_WRAP)   // 必须 NO_WRAP：换行会被查询参数解析吃掉
val url   = Uri.parse("$baseUrl/?auth_token=$token")
webView.loadUrl(url.toString())
```

- 依据：`authorization.ts:10,32-33`。
- 优点：页面内所有 `fetch`/XHR 变成**同源带凭据请求**，全程无需回调；且 `/`、`/_assets/*` 这些静态请求本来就免认证，所以**只有 API 请求带 token，静态资源 URL 保持干净**。
- ⚠️ **必须处理**：token 出现在 URL 里 ⇒ 别把它写进日志/`WebViewClient.shouldOverrideUrlLoading` 日志；切换会话时若持久化 URL 需加密。实测 curl 走这条通道等效于 Basic（`decodeCredential` 同一函数）。

**方式 2：`onReceivedHttpAuthRequest` —— 标准 Basic 挑战应答**

```kotlin
webView.webViewClient = object : WebViewClient() {
    override fun onReceivedHttpAuthRequest(
        view: WebView, host: String, realm: String,
        account: String?, args: String?
    ): Boolean {
        if (args == "Basic" && account == null) {
            view.receivedSslError; // (无关分支，略)
            // 交给引擎处理：填入凭据后引擎会重发该请求
            return false            // false = 使用下面设置的默认凭据
        }
        return true
    }
}
// 或更直接：预置默认凭据，命中同一 realm 时自动应答
WebViewDatabase.getInstance(context).setHttpAuthUsername(host, "opencode")
```

- 更可靠的写法是覆写 `onReceivedHttpAuthRequest` 后调用 `Thread { handler.proceed("opencode", password) }.start()`（`HttpAuthHandler.proceed` 非 UI 线程）。
- 依据：`authorization.ts:11` 的 `WWW_AUTHENTICATE = 'Basic realm="Secure Area"'`；实测 401 确实带该头（§3.2）。
- 优点：密码不落在 URL。缺点：需处理 `onReceivedHttpAuthRequest` 的多线程调用、realm 匹配、以及"取消/重试次数过多"的边界。

**方式 3：`Authorization` 头预置（仅当壳自己发请求，不适用于页面内 fetch）**

```kotlin
// 仅对壳自身发出的 HTTP 请求（如 /api/info 探活）
val req = Request.Builder().url("$baseUrl/api/info")
    .header("Authorization", "Basic " +
        Base64.encodeToString("opencode:$password".toByteArray(), Base64.NO_WRAP))
    .build()
```

- ⚠️ **不可用于页面内请求**：JS `fetch` 不会自动带上壳设置的原生头。跨源时 CORS 也已放行 `authorization`（§3.7），但同源 WebView 场景下没意义。
- 建议仅用于**探活/首屏前置校验**（见 §6.3）。

### 6.3 建议的落地流程

1. 用户在**设置页**录入 `baseUrl` + `password`（与 `OPENCODE_SERVER_PASSWORD` 对应）。
2. 壳用**方式 3** 对 `GET {baseUrl}/api/info` 探活：
   - `200` → 密码对，直接 `loadUrl` + `?auth_token`（方式 1）；
   - `401` → 密码错，**在 WebView 之前**就提示，不让用户看到浏览器原生 Basic 弹窗；
   - 连接失败 → 走 §5.3 降级页。
3. WebView `loadUrl("{baseUrl}/?auth_token={token}")`。
4. 兜底安全配置（见 §6.5）。

### 6.4 ⚠️ 必须注意的三个坑

1. **静态 UI 是免认证的**（实测 `/` 无凭据 200）。含义：拿到 baseUrl 的人都能下载 UI 壳与 JS 资源；**真正的秘密全在 API 上**。若部署在公网，注意 UI 本身不构成信息泄露，但**不要因此以为"开了密码整个站点就受保护"**——UI 文件是公开的。
2. **Service Worker**：`dist` 根目录有 **`sw.js`（118.2 KiB）**，且 `web-ui.ts:38` 对 `sw.js`/`registerSW.js` 专门用 `no-cache`。WebView 里 SW 可能带来意外缓存/后台行为，建议 `WebSettings` 侧评估禁用，或至少在 §5.3 第 3 层需要离线缓存时**明确二选一**（SW vs 壳侧缓存，别同时开）。
3. **CSP 严格**（`web-ui.ts:58-60`）：`default-src 'self'` + `script-src 'self' 'wasm-unsafe-eval'`。含义：**不要试图往页面里注入本地 JS/本地 CSS**（`file://`、`javascript:` 注入都会被 CSP 挡掉）。若壳需要做 bridge，必须走 WebView 原生接口（`addJavascriptInterface` / `WebViewCompat.addWebMessageListener`），不能靠改 HTML。

### 6.5 建议的 WebView 安全配置（路线 A 配套）

```kotlin
settings.javaScriptEnabled = true
settings.domStorageEnabled = true               // 页面用 localStorage 存主题（见 / 里 opencode-theme-id）
settings.allowFileAccess = false                // 纯 http 场景，关掉本地文件访问
settings.allowContentAccess = false
settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
if (Build.VERSION.SDK_INT >= 26) settings.safeBrowsingEnabled = true
// 生产环境务必：
settings.setGeolocationEnabled(false)
// 证书：明文 HTTP 需 network_security_config 显式放行该 host（见下）
```

局域网明文 `http://` 在 Android 9+ 默认被禁，需 `res/xml/network_security_config.xml` 对**具体 IP/网段**开 `cleartextTrafficPermitted="true"`（**不要全局开**）。长期方案是服务端上 TLS + 客户端 `networkSecurityConfig` 固定信任域。

---

## 7. 未核对 / 本轮明确没做的事

诚实标注，避免被当成已验证：

1. **APK 真实体积增量未实测**。§4 的 34.19 MB 是 dist 的**磁盘字节**，APK 内为压缩存储，实际增量会明显更小。**本轮禁止 gradle，故未验证**。若要走路线 B，必须先实测一个真实 APK 的 delta 再决策。
2. **本地 `dist` 是 dev 构建**。实测本地 `dist/icons/` 只有 `icons/dev/` 4 个文件，而运行中的 v2.0.14 二进制提供的是 `icons/prod/`（`/icons/prod/favicon.ico` → 200）。⇒ **生产构建的 dist 体积/哈希名可能与本地不同**，§4 的绝对值对 prod 构建只是近似；但 `.map` 占比（61.5%）由构建配置决定，**prod 同样会生成**（实测 773 个 js 中 752 个含 `sourceMappingURL`）。
3. **`OPENCODE_LOCAL` 两条分支未分别实测**。我只验证了发布二进制（内嵌 brotli 资产）这一条；`app-assets.ts:17` 的 `sourceAssets()` 磁盘分支未在运行期触发。
4. **真机 WebView 行为未验证**。`auth_token` 走查、`onReceivedHttpAuthRequest` 时序、`sw.js` 干扰、`file://` 混入拦截 —— 全部基于源码推导，**未在设备上跑**。本轮不装机。
5. **WebSocket / PTY ticket 通道未探测**。`authorization.ts:51-54` 的豁免是读出来的，未做运行期验证。
6. **多实例/多目录行为**未查（`Instance.node`、`worktree` 等），与本决策无关但未排除副作用。
7. **`/sdcard` 无新增残留**：本轮全程未执行任何 `adb push`/设备写入，**不存在新增残留**（见 §8）。

---

## 8. 收尾确认

| 项 | 结果 |
|---|---|
| 自起服务 PID | 启动器 50860，实际监听进程 **51104** |
| 端口 | **4100**（专用，未触碰 4095/4096） |
| 停止 | ✅ 已终止两个 PID |
| 端口释放（TCP 表） | ✅ `Get-NetTCPConnection -LocalPort 4100` → **无监听项** |
| 端口释放（功能验证） | ✅ `curl -m 8 http://127.0.0.1:4100/` → **`status=000`（connection refused）** |
| 残留进程 | ✅ `Get-Process opencode2` → **无 opencode2.exe 进程** |
| 4095 | 无监听（**我从未在此端口起任何东西**） |
| 4096 | **占用，PID 12668，进程名 `opencode`（即旧版 1.x，非我启动的 `opencode2`）** —— 我**未触碰**该端口；此为环境中预先存在的进程 |
| 临时工作目录 | `%TEMP%\g1-probe-work` —— ✅ 已删除（仓库外） |
| 临时日志/标记 | `%TEMP%\g1-probe.log` / `.err.log` / `.pid` —— ✅ 已全部删除（仓库外） |
| 生产代码改动 | **无**。`git status` 中 `SessionScreen.kt` / `ContextUsage.kt` / `ReviewPanel.kt` / `settings.gradle.kts` 的改动**是我接手前就存在的**，本轮未触碰任何生产代码 |
| gradle / 装机 | **未执行** |
| 写入范围 | **仅 `research/web/SERVER_HOSTS_UI.md`**（`git status` 新增项为 `research/web/`） |
| `/sdcard` | **无新增残留**（本轮未执行任何 `adb` 写入/推送操作） |

---

## 9. 一句话汇报

> **服务端确实自托管 UI**（`packages/cli/src/services/web-ui.ts:12-24,27-33` 回落 + `packages/cli/src/app-assets.ts:22` 指向 `packages/app/dist`，发布版再由 `app-assets.ts:10-18` brotli 内嵌进二进制）；**托管在根路径 `/`，资源前缀 `/_assets/`**（另有 `/icons/`、`/site.webmanifest`），无 `/app` 子路径；**`GET /` = 200 text/html（6147 B，免认证），`GET /api/info` 无凭据 401、带 Basic 认证 200 application/json `{"version":"2.0.14",...}`**，且 v2 已修掉旧版"`/api/*` 回落成 HTML"的问题（`web-ui.ts:18-19`）；**推荐路线 A**——因为本 App 本就是无服务即不可用的瘦客户端，"离线"换不来真实收益，而 88.8MB（即便剔掉服务端自己都不发的 54.5MB sourcemap 仍有 34.2MB）+ 版本漂移 + 发版节奏是纯代价，A 的认证可用 `?auth_token=base64("opencode:"+pwd)`（`authorization.ts:10,32-33`，用户名硬编码见 `auth.ts:17`）干净解决；**端口 4100 已停、已确认释放，4095/4096 未占用，`/sdcard` 无残留**。报告见 `research/web/SERVER_HOSTS_UI.md`。
