# HOST_REQUIREMENTS — 官方前端在 Android WebView 里跑起来，宿主必须满足什么

- **作者**：F1 集成分析工程师（只读分析，未跑 gradle、未装机、未改任何生产代码）
- **日期**：2026-10-04
- **宿主工程**：`C:\sensorsdata\main\program\opencode-android-shell`（`minSdk=26` / `targetSdk=35` / `compileSdk=35`，`app/build.gradle.kts:9,13,14`；已依赖 `androidx.webkit:webkit:1.12.1`（`app/build.gradle.kts:120`）→ `WebViewAssetLoader` **可用，无需新增依赖**）
- **上游只读**：`C:\sensorsdata\main\program\opencode-android-shell\_upstream`（未做任何修改）

> **本报告的规矩**：所有结论都带 `文件:行号` 或命令输出。查不到的一律写「未核对」，不做推测性断言。

---

## 0. 一页速览（TL;DR）

| 事项 | 结论 | 证据 |
|---|---|---|
| `dist/` 绝对路径 | `C:\sensorsdata\main\program\opencode-android-shell\_upstream\packages\app\dist` | `Glob _upstream/**/dist/index.html` 唯一命中 |
| `dist/` 体积 | **84.65 MB / 1665 文件**（其中 **52.04 MB 是 752 个 `.map`**，可删 → 净 **32.6 MB**） | 见 §1.2 命令输出 |
| 资源路径前缀 | **绝对路径**（`/_assets/…`、`/icons/…`、`/site.webmanifest`），**不是**相对路径 | `dist/index.html:53-97`，`vite.config.ts:41` 无 `base` |
| 托管前缀 | **PathHandler 前缀必须是 `/`**（只能用根，不能是 `/assets/`） | 绝对路径 → 必须占满 origin 根 |
| 是否要改 base | **不要改**。`--base` **不需要**，改了反而破坏 SW scope / `navigateFallback` | §2.3 |
| 唯一推荐托管 | `WebViewAssetLoader` + 自定义域名 + 前缀 `/` + `AssetsPathHandler` | §2.1 |
| 后端 origin | **生产构建默认 `location.origin`（= 假域名，会 404）**，必须用 `VITE_OPENCODE_SERVER_MODE=none` 在构建期关掉 | `web.ts:56,63` |
| SW 要不要禁 | **建议禁**（SW 会 precache 32 MB 且永不更新） | §3.2 |
| API 路径 | `/api/*`（如 `/api/session`、`/api/config`、`/api/info`、`/api/pty`） | §3.3 |
| SSE 路径 | `GET /api/event`（**fetch 流式，不是 EventSource**） | `event.ts:44-45`、`client.ts:361` |
| WS 路径 | `/api/pty/{id}/connect?ticket=…`（`ws:`/`wss:`，**在 server 域非同源**） | `pty.ts:29,33,49,55` |
| 认证 | **HTTP Basic，用户名硬编码 `opencode`**；URL `?auth_token=base64("opencode:密码")` 引导，随后被抹掉 | `api.ts:6,28`、`entry.tsx:39-44,75` |
| CSP | `dist/index.html` **无** CSP meta；上游 CLI 用**响应头**发严格 CSP（内联脚本需 sha256） | §3.6 |

---

## 1. 产物定位（Artifact location）

### 1.1 绝对路径

```
C:\sensorsdata\main\program\opencode-android-shell\_upstream\packages\app\dist
```

**定位方法**（唯一命中，无歧义）：

```
Glob  _upstream/**/dist/index.html
→  _upstream\packages\app\dist\index.html
```

构建日志印证（`research/web/_build-app.log`）：

```
6:  vite v8.2.2 building client environment for production...
31: dist/site.webmanifest                              0.38 kB
78: dist/index.html                                    6.12 kB
```

### 1.2 体积与顶层结构（命令输出）

```powershell
Get-ChildItem -Recurse -File | Measure-Object -Property Length -Sum
```

```
TOTAL_FILES: 1665
TOTAL_BYTES: 88758107
TOTAL_MB: 84.65
```

顶层结构：

```
  [DIR ] _assets    files=1642  MB=82.30      <- JS/CSS/字体/音频/图片，注意不是 assets/ 而是 _assets/
  [DIR ] assets     files=2     MB=1.85       <- 只有 Inter.ttf + JetBrainsMonoNerdFontMono-Regular.woff2
  [DIR ] icons      files=4     MB=0.38
  [FILE] index.html                6126
  [FILE] sw.js                  120995         <- Workbox 7.4.0
  [FILE] site.webmanifest           382
  [FILE] _headers                   518         <- Netlify/Cloudflare 风格响应头
  [FILE] oc-theme-preload.js       1184
  [FILE] social-share.png            43
  [FILE] favicon.ico                 39
  [FILE] favicon.svg                 39
  [FILE] favicon-v3.ico              42
  [FILE] favicon-v3.svg              42
  [FILE] favicon-96x96.png            45
  [FILE] favicon-96x96-v3.png         48
  [FILE] apple-touch-icon.png         48
  [FILE] apple-touch-icon-v3.png      51
  [FILE] web-app-manifest-192x192.png 56
  [FILE] web-app-manifest-512x512.png 56
  [FILE] social-share-zen.png         47
```

> `registerSW.js` **不存在于 dist**（已用 `Test-Path` 确认为 ABSENT）。`injectRegister: false`（`vite.pwa.ts:10`）所以虚拟模块没生成；`_headers:26` 与 `web-ui.ts:38` 里对它的引用是**死代码**，宿主不用管这个文件。

### 1.3 🔴 `.map` 必须删掉 —— 省 52 MB

```powershell
Get-ChildItem -Recurse -Filter *.map -File | Measure-Object -Property Length -Sum
→ count=752  MB=52.04
```

**84.65 MB 里有 52.04 MB（61%）是 sourcemap。** 如果整包拷进 `webapp/src/main/assets/`，APK 直接多 52 MB。

证据（上游自己也这么做）：

- `_upstream/packages/app/vite.config.ts:43` — `sourcemap: true`
- `_upstream/packages/cli/script/app-assets.ts:17` — `.filter((key) => !key.endsWith(".map"))` ← **上游打包进二进制时明确丢弃 `.map`**
- `_upstream/packages/app/vite.pwa.ts:24` — `globIgnores: ["**/*.map", "_headers", "_redirects"]` ← SW 也不 precache 它们

**结论：随 APK 打包时排除 `**/*.map`（净体积 84.65 − 52.04 = 32.61 MB）。** 建议再加 brotli/gzip 预压缩（上游也是 brotli 质量 6，`app-assets.ts:30-34`），但注意 **WebViewAssetLoader 默认不解压预压缩文件**，除非宿主自己处理 `Content-Encoding` 头 —— 这一点标为**未核对**（见 §6）。

### 1.4 `index.html` 原文（完整，`dist/index.html`，105 行）

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
    <link rel="icon" type="image/x-icon" href="/icons/dev/favicon.ico" />
    <link rel="apple-touch-icon" sizes="180x180" href="/icons/dev/apple-touch-icon.png" />
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
  var isDark = scheme === "dark" || (scheme === "system" && matchMedia("(prefers-color-scheme: dark)").matches)
  var mode = isDark ? "dark" : "light"

  document.documentElement.dataset.theme = themeId
  document.documentElement.dataset.colorScheme = mode
  document.documentElement.style.backgroundColor = isDark ? "#080808" : "#fafafa"

  // Update theme-color meta tag to match app color scheme
  var metas = document.querySelectorAll("meta[name='theme-color']")
  if (metas.length > 0) metas[0].setAttribute("content", isDark ? "#080808" : "#fafafa")

  if (themeId === "oc-2") return

  var css = localStorage.getItem("opencode-theme-css-" + mode)
  if (css) {
    var style = document.createElement("style")
    style.id = "oc-theme-preload"
    style.textContent =
      ":root{color-scheme:" +
      mode +
      ";--text-mix-blend-mode:" +
      (isDark ? "plus-lighter" : "multiply") +
      ";" +
      css +
      "}"
    document.head.appendChild(style)
  }
})()
</script>
    <script type="module" crossorigin src="/_assets/index-AWnJzdVG.js"></script>
    <link rel="modulepreload" crossorigin href="/_assets/rolldown-runtime-hePW80VL.js">
    <link rel="modulepreload" crossorigin href="/_assets/icon-DhNOMwJt.js">
    <link rel="modulepreload" crossorigin href="/_assets/eventListener-D2dL8mSg.js">
    <link rel="modulepreload" crossorigin href="/_assets/preload-helper-Czpn1I53.js">
    <link rel="modulepreload" crossorigin href="/_assets/desktop-native-vJFY6KWL.js">
    <link rel="modulepreload" crossorigin href="/_assets/button-CpHzm3pX.js">
    <link rel="modulepreload" crossorigin href="/_assets/registry-W3rcrtz3.js">
    <link rel="modulepreload" crossorigin href="/_assets/health-Bgzr7yU2.js">
    <link rel="modulepreload" crossorigin href="/_assets/runtime-BzUHKMUG.js">
    <link rel="modulepreload" crossorigin href="/_assets/EHGSTO6V-fvl9U5aH.js">
    <link rel="modulepreload" crossorigin href="/_assets/UKTBL2JL-rUQ5eBIS.js">
    <link rel="modulepreload" crossorigin href="/_assets/dist-_089tLNc.js">
    <link rel="modulepreload" crossorigin href="/_assets/dist-K38yuZ6t.js">
    <link rel="modulepreload" crossorigin href="/_assets/dialog-CwqiMNtY.js">
    <link rel="modulepreload" crossorigin href="/_assets/dialog-C4f_FEBM.js">
    <link rel="modulepreload" crossorigin href="/_assets/oc-2-DJL408O7.js">
    <link rel="modulepreload" crossorigin href="/_assets/command-GCEc1h1r.js">
    <link rel="modulepreload" crossorigin href="/_assets/button-B9B4Vjj8.js">
    <link rel="modulepreload" crossorigin href="/_assets/divider-BTbmnVLO.js">
    <link rel="modulepreload" crossorigin href="/_assets/text-input-CWVd408U.js">
    <link rel="modulepreload" crossorigin href="/_assets/surface-4YxRqeXB.js">
    <link rel="modulepreload" crossorigin href="/_assets/ZZYKR3VO-DyH0zdnY.js">
    <link rel="modulepreload" crossorigin href="/_assets/icon-button-BFngD2vf.js">
    <link rel="modulepreload" crossorigin href="/_assets/tooltip-BRAzdtTh.js">
    <link rel="modulepreload" crossorigin href="/_assets/text-field-CdIwrdbm.js">
    <link rel="modulepreload" crossorigin href="/_assets/uploads-C_xE5p6s.js">
    <link rel="modulepreload" crossorigin href="/_assets/dist-DAusw-AM.js">
    <link rel="modulepreload" crossorigin href="/_assets/helpers-Cwv1iYCK.js">
    <link rel="modulepreload" crossorigin href="/_assets/project-avatar-d-wfVptR.js">
    <link rel="modulepreload" crossorigin href="/_assets/session-progress-indicator-v2-B9haywTX.js">
    <link rel="modulepreload" crossorigin href="/_assets/location-BSCsX7pA.js">
    <link rel="stylesheet" crossorigin href="/_assets/icon-BDQzFLnz.css">
    <link rel="stylesheet" crossorigin href="/_assets/button-CP8fzIZH.css">
    <link rel="stylesheet" crossorigin href="/_assets/runtime-ddCoIm8Y.css">
    <link rel="stylesheet" crossorigin href="/_assets/dialog-BUYQZsMU.css">
    <link rel="stylesheet" crossorigin href="/_assets/divider-C-h6JRGT.css">
    <link rel="stylesheet" crossorigin href="/_assets/text-input-BdrW1Dhu.css">
    <link rel="stylesheet" crossorigin href="/_assets/surface-B48-YmDS.css">
    <link rel="stylesheet" crossorigin href="/_assets/settings-DF0FCDfy.css">
    <link rel="stylesheet" crossorigin href="/_assets/icon-button-BCwmH1dS.css">
    <link rel="stylesheet" crossorigin href="/_assets/tooltip-DE8rGyLW.css">
    <link rel="stylesheet" crossorigin href="/_assets/project-avatar-B25rVzWO.css">
    <link rel="stylesheet" crossorigin href="/_assets/session-progress-indicator-v2-BJDdxLU-.css">
    <link rel="stylesheet" crossorigin href="/_assets/index-DE7lA75S.css">
  </head>
  <body
    class="antialiased overscroll-none font-(family-name:--font-family-text) text-[13px] font-[440] overflow-hidden bg-v2-background-bg-deep"
  >
    <noscript>You need to enable JavaScript to run this app.</noscript>
    <div id="root" class="flex flex-col h-dvh bg-v2-background-bg-deep p-px"></div>
  </body>
</html>
```

### 1.5 ✅ 明确回答：资源路径是**绝对**，不是相对

**绝对（root-absolute）。** 三重证据：

1. **构建产物**：`dist/index.html:53` — `src="/_assets/index-AWnJzdVG.js"`（**无 `./`，无 `../`**）；`:54-97` 全部 `href="/_assets/…"`；`:10` `href="/icons/dev/favicon.ico"`；`:12` `href="/site.webmanifest"`。
2. **构建配置**：`_upstream/packages/app/vite.config.ts` 里**没有 `base` 字段**（第 25-45 行只有 `plugins` / `server` / `build.assetsDir` / `target` / `sourcemap`）→ Vite 默认 `base: "/"` → 一律输出绝对路径。
3. **产物目录名**：`_assets`（下划线）来自 `vite.config.ts:41` — `assetsDir: "_assets"`。注意这**不是** Vite 默认的 `assets/`，所以别按 `/assets/` 去配前缀。

> ⚠️ 由此推出两条硬约束：
> 1. 页面**必须被托管在 origin 根**（`https://<假域名>/`），不能挂在 `/shell/` 之类子路径下，否则所有资源 404。
> 2. `PathHandler` 前缀**必须是 `/`**，不能是 `/assets/`。

---

## 2. 托管方案推导（唯一推荐）

### 2.1 🔴 唯一推荐：`WebViewAssetLoader` + 自定义域名 + 前缀 `/`

```kotlin
// 域名：必须是 WebViewAssetLoader 保留域名，不能用别的
private val DOMAIN = "appassets.androidplatform.net"   // androidx.webkit 的保留域名
private val PATH_PREFIX = "/assets/webapp/"            // ← 见 §2.2 的重要修正

val loader = WebViewAssetLoader.Builder()
    .setDomain(DOMAIN)
    .addPathHandler(PATH_PREFIX, WebViewAssetLoader.AssetsPathHandler(context))
    .build()

webView.webViewClient = object : WebViewClientCompat() {
    override fun shouldInterceptRequest(
        view: WebView, request: WebResourceRequest
    ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)
}
```

启动 URL：

```kotlin
webView.loadUrl("https://$DOMAIN$PATH_PREFIX/index.html")
```

> 为什么域名必须用 `appassets.androidplatform.net`：`WebViewAssetLoader` 只对自己注册的域名返回资源，WebView 对**其他**域名会发起**真实网络请求**。这是 WebView 官方提供的「安全本地托管」机制，且 `https://` scheme 让页面处于 **secure context**（`isSecureContext === true`），从而 **`getUserMedia`（摄像头）、`navigator.clipboard`、`IndexedDB` 等能力可用**。若用 `file://` 或 `http://` 自定义域，这些能力全部被禁。

### 2.2 ⚠️ 重要修正：PathHandler 前缀怎么定才既正确又不打架

`WebViewAssetLoader.AssetsPathHandler` 的语义是「URL 路径前缀 → assets 子目录」：

```
https://appassets.androidplatform.net/assets/webapp/_assets/index-XXX.js
                                               ^^^^^^^^^^^^^^ 去掉这段前缀
                                                              → assets/webapp/_assets/index-XXX.js
```

**问题：页面自己请求的是 `/_assets/…`（根），而我们的 URL 是 `/assets/webapp/index.html`（子路径）。两者对不上。**

- 若把前缀设成 `/assets/webapp/`，则 `location.origin` = `https://appassets.androidplatform.net`，而 `index.html` 里写死的 `/_assets/index-AWnJzdVG.js` 会去请求 `https://appassets.androidplatform.net/_assets/index-AWnJzdVG.js` → **未命中任何 PathHandler → 真实网络请求 → DNS 失败 / 404，白屏。**
- 若把前缀设成 `/`，则 `https://appassets.androidplatform.net/_assets/index-AWnJzdVG.js` 命中 → 映射到 `assets/_assets/index-AWnJzdVG.js` → ✅ 正确。

**因此推导结论：**

| 方案 | PathHandler 前缀 | 启动 URL | 结果 |
|---|---|---|---|
| A | `/` | `https://appassets.androidplatform.net/index.html` | ✅ **资源全部命中**（`/_assets/…`、MD5-hash 天然 immutable 缓存） |
| B | `/shell/` + 构建期 `--base=/shell/` | `https://…/shell/index.html` | ⚠️ 可行但需构建参数，且要改 `navigateFallback`，见 §2.3 |
| C | `/assets/webapp/` | `https://…/assets/webapp/index.html` | ❌ **白屏**（路径不匹配） |

**⇒ 选方案 A：域名 `appassets.androidplatform.net`，PathHandler 前缀 `/`，assets 根目录放 dist 内容本身。**

> 命名空间提醒：assets 根目录即 `webapp/src/main/assets/`，所以 dist 的 `index.html` 必须落在 `webapp/src/main/assets/index.html`（**不是** `webapp/src/main/assets/webapp/index.html`）。W1 建目录时注意这一点。

### 2.3 要不要用构建期 `--base`？—— **不要**

**答案：推荐方案完全不需要改 `base`。** 理由：

1. 方案 A 已经在 origin 根托管，绝对路径天然正确。
2. 改 `base` 会连带影响 SW：`vite.pwa.ts:20` — `navigateFallback: "/index.html"` 是**硬编码字符串**（不是从 `base` 推导的）。改 base 后 SPA 路由回退仍指向 `/index.html`，与新 base 冲突。
3. 改 base 还会改变 SW 的注册 scope（`entry.tsx:49` 写死 `register("/sw.js")`，scope = 根）。

**如果将来确实要挂到子路径（例如和宿主自己的 `/api` 共存），确切命令如下**（**仅作备查，本方案不采用**）：

```powershell
# 备查方案 B：构建期指定 base（不改任何上游源码，只传环境变量 + CLI 参数）
$env:OPENCODE_CHANNEL            = "prod"
$env:VITE_OPENCODE_SERVER_MODE   = "none"      # 见 §2.4，必需
Push-Location C:\sensorsdata\main\program\opencode-android-shell\_upstream\packages\app
bunx vite build --base=/shell/ --mode production
Pop-Location
```

配套必须做的两件事（不改源码做不到）：
- SW 的 `navigateFallback` 仍是 `/index.html` → 需宿主在 `/shell/**` 未知路径时回退到 `/shell/index.html`；
- 启动 URL 用 `https://appassets.androidplatform.net/shell/index.html`，前缀设 `/shell/`。

**算不算「改上游源码」？不算。** `--base` 与 `VITE_OPENCODE_SERVER_MODE` 都是**构建参数/环境变量**，源码零改动 —— 符合我们的规矩（只允许构建参数）。上游自己也用环境变量控制构建行为（`app-assets.ts:11` 传 `VITE_OPENCODE_SERVER_MODE: "origin"`）。

### 2.4 🔴 后端 origin：生产构建默认打 `location.origin` —— 必踩的坑

这是**最容易导致白屏/一直转圈**的一条。

```ts
// _upstream/packages/app/src/runtime/platform/web.ts:55-64
function getCurrentServerUrl() {
  if (import.meta.env.VITE_OPENCODE_SERVER_MODE === "none") return undefined
  if (import.meta.env.DEV) {
    const loopback =
      location.hostname === "localhost" || location.hostname === "[::1]" || location.hostname.startsWith("127.")
    const host = import.meta.env.VITE_OPENCODE_SERVER_HOST ?? (loopback ? location.hostname : "localhost")
    return `http://${host}:${import.meta.env.VITE_OPENCODE_SERVER_PORT ?? "4096"}`
  }
  return location.origin        // ← 生产构建走这里
}
```

- **生产构建下没有环境变量能让它指向一个绝对 URL**，只有两条路：`location.origin`（= 我们的假域名 `https://appassets.androidplatform.net` → 所有 `/api/*` 打到本地资源 → 404）或 `undefined`。
- 取值来源：`entry.tsx:80` — `const server: ServerConnection.Http | undefined = web.currentServerUrl ? {...} : undefined`；`:95-97` 传给 `<AppInterface defaultServer=... servers=...>`。

**⇒ 推荐构建参数：`VITE_OPENCODE_SERVER_MODE=none`**，让 `currentServerUrl === undefined`，由 App 走自己的「连接服务器」流程（`packages/app/src/servers/connect/` 确实存在）。类型定义：`packages/app/src/env.d.ts:2` — `readonly VITE_OPENCODE_SERVER_MODE?: "none" | "origin"`。

**推荐的完整构建命令（不改上游源码）：**

```powershell
# 在 C:\sensorsdata\main\program\opencode-android-shell 下执行
$env:OPENCODE_CHANNEL          = "prod"        # 上游用它切图标渠道，见 vite.icons.ts
$env:VITE_OPENCODE_SERVER_MODE = "none"        # 关键：不要绑到假 origin
Push-Location .\_upstream\packages\app
bun run build                    # package.json: "build": "vite build"
Pop-Location
```

**⇒ 运行期宿主要注入的配置（三选一，按侵入性排序）：**

| 方式 | 做法 | 证据 |
|---|---|---|
| ① **URL 引导密码**（最简，只带密码不带 URL） | `loadUrl("https://appassets.androidplatform.net/index.html?auth_token=" + Base64("opencode:" + password))` | `entry.tsx:75` 读 `auth_token` → `authFromToken` → `{password}`；`:76`+`:39-44` 立刻从 URL 抹掉 |
| ② **预置 localStorage** | 在 `loadUrl` 前用 `evaluateJavascript` 写 `localStorage["opencode.settings.dat:defaultServerUrl"] = "http://<host>:4096"` | `web.ts:5,69,79`（键名与读写） |
| ③ **让用户在 App 内填** | 不预置，用户在「连接服务器」UI 里填 URL+密码 | `packages/app/src/servers/connect/`；持久化于 `localStorage["opencode.global.dat:server"]`（`registry.tsx:203-207`，`storage.ts:30`） |

> ⚠️ **注意 ① 只能设密码、不能设 URL**（`authFromToken` 只解析出 `password`，`api.ts:9-17`）。要指定后端 URL 必须用 ② 或 ③。
> ⚠️ 方式 ② 需要在页面脚本执行前写入。`evaluateJavascript` 与 `loadUrl` 存在竞态；**稳妥做法是走 ③**（或先用 `onPageStarted` 尽早注入）。竞态窗口未实测 → 见 §6。

---

## 3. 运行期依赖（逐条 + 证据）

### 3.1 Service Worker / PWA

**注册条件**（唯一注册点，全 app 仅此一处）：

```ts
// _upstream/packages/app/src/entry.tsx:48-50
if (import.meta.env.PROD && "serviceWorker" in navigator) {
  window.addEventListener("load", () => void navigator.serviceWorker.register("/sw.js"), { once: true })
}
```

- 门控：**仅生产构建** + `"serviceWorker" in navigator`。无 localhost 判断、无功能开关、无错误处理（`void` 吞掉 rejection，失败只出现在 console）。
- 未传 `scope` → **scope = 整个 origin 根**。`/sw.js` 必须能从 origin 根取到。
- 产物验证（已 grep 编译后 `dist/_assets/index-AWnJzdVG.js`）：

```js
`serviceWorker`in navigator&&window.addEventListener(`load`,()=>void navigator.serviceWorker.register(`/sw.js`),{once:!0})
```

- `dist/sw.js` = 120,995 bytes，Workbox **7.4.0**（`workbox:core:7.4.0` / `workbox:routing:7.4.0`，来自 `dist/sw.js` 开头）。
- `vite-plugin-pwa` 配置（`_upstream/packages/app/vite.pwa.ts`）：
  - `:8` `strategies: "generateSW"`
  - `:9` `registerType: "prompt"`
  - `:10` `injectRegister: false`、`:11` `manifest: false`
  - `:16` `clientsClaim: false`、`:18` `skipWaiting: false`
  - `:20-21` `navigateFallback: "/index.html"`，`navigateFallbackDenylist: [/^\/api(?:\/|$)/, /^\/(?:_assets|assets)(?:\/|$)/]`
  - `:23` `globPatterns: ["**/*"]`、`:25` `maximumFileSizeToCacheInBytes: MAX_SAFE_INTEGER`
- 预缓存规模（`research/web/_build-app.log`）：`precache 911 entries (33271.39 KiB)` → **约 32.5 MB 全量离线缓存**。

**不注册会不会坏？—— 不会坏，但会失去离线/缓存。**

理由：

1. `registerType: "prompt"` + `skipWaiting: false` + `clientsClaim: false`（`vite.pwa.ts:9,16,18`）意味着**新 SW 永远停在 `waiting`**，而 app 里**没有任何 `controllerchange` / `updatefound` / `postMessage({type:"SKIP_WAITING"})` 调用**（全 app 已 grep，`NOT FOUND`），也**没有更新提示 UI**（`packages/app/src/shell/updates/` 是**桌面/CLI 二进制更新器**，`shell/updates/action.ts:32` — `platform.updater?.state()`，与 SW 无关）。
   ⇒ **在 WebView 里发新版前端，用户永远拿不到新版**，只能靠宿主清 SW 数据。
2. 它注册**无条件**（只判 PROD），宿主**无法通过配置关掉**——`entry.tsx:48` 硬编码。
3. 离线能力对「本地托管在 APK 里的前端」**毫无价值**——资源本来就在本地。

### 3.2 ✅ SW 要不要禁 / 怎么禁 → **建议禁**

**结论：禁掉。** 理由：预缓存 32.5 MB 白占 WebView 的 Cache Storage；`skipWaiting: false` 导致**版本永不更新**（发新版前端 = 静默不生效）；而离线能力在本地托管场景下无收益。

**宿主侧怎么禁（不改上游源码，推荐第 1 种）：**

| 方式 | 做法 | 评价 |
|---|---|---|
| ① **不提供 `/sw.js`**（推荐） | 资源包里**不打包** `dist/sw.js`。`register("/sw.js")` 请求 404 → 注册失败 → **静默降级为无 SW**，`entry.tsx:48` 的 `void` 不 catch，页面照常工作 | ✅ 零源码改动、零构建参数、失败无副作用 |
| ② 构建参数 | 上游 `vite.pwa.ts` **没有** `VITE_*` 开关可关 PWA（已核对 `env.d.ts:1-6`，只有 `SERVER_MODE/HOST/PORT/CHANNEL`）→ **做不到** | ❌ 不可行 |
| ③ 运行时抑制 | 宿主实现 `ServiceWorkerController`（`ServiceWorkerClientCompat`）拦截并返回空；或用 `ServiceWorkerControllerCompat` + 自定义 `ServiceWorkerClientCompat.shouldInterceptRequest` 永远返回 404 | ⚠️ 可行但更复杂，收益不大 |

**⇒ 选 ①：打包时排除 `dist/sw.js`，顺带排除 `dist/_headers`（Netlify 专用，对我们无意义）。**

> ⚠️ 配套提醒：若走方案 ①，**必须同时保证 `/sw.js` 返回明确的 404**（`AssetsPathHandler` 对不存在的文件默认返回 404 ✅），并且宿主在 WebView 里**不要**残留历史 SW 数据。开发期若已注册过，需在测试机上清 `WebView` 数据。

### 3.3 后端接口：origin、路径、是否可配

**前缀：`/api/*`**（无 group 前缀，每个端点写死完整字面量；`packages/protocol` 里**没有** `.prefix(` 调用）。

| 端点 | 路径 | 证据 |
|---|---|---|
| 会话列表 | `GET /api/session` | `packages/protocol/src/groups/session.ts:181` |
| 建会话 | `POST /api/session` | `packages/protocol/src/groups/session.ts:220` |
| 配置 | `GET /api/config` | `packages/protocol/src/groups/config.ts:9` |
| 服务端信息 | `GET /api/info` | `packages/protocol/src/groups/server.ts:17` |
| PTY 列表 | `GET /api/pty` | `packages/protocol/src/groups/pty.ts:23` |
| 客户端实现 | `new URL(descriptor.path, options.baseUrl)` | `packages/httpapi-codegen/src/index.ts:970`+（`ClientOptions.baseUrl`） |

**同源还是可配？—— 生产构建默认「同源 = 自己的 origin」，但可通过 App 内服务器注册表改成任意 origin。**

- 默认：`web.ts:63` — `return location.origin`（**本项目里 = 假域名，必须用 `VITE_OPENCODE_SERVER_MODE=none` 规避**，见 §2.4）
- 可配：
  - 持久化默认服务器：`localStorage["opencode.settings.dat:defaultServerUrl"]`（`web.ts:5,69,79`）
  - 服务器列表（含密码）：`localStorage["opencode.global.dat:server"]`（`storage.ts:30`、`registry.tsx:203-207`）
  - 逐连接 baseUrl：`api.ts:24` — `baseUrl: input.server.url`

**SSE（事件流）**：

| 项 | 结论 | 证据 |
|---|---|---|
| 路径 | `GET /api/event` | `packages/protocol/src/groups/event.ts:44` |
| 传输 | **`fetch` + `ReadableStream` 流式，NOT `EventSource`** | `client.ts:361` — `const reader = response.body.getReader()`；`connection.ts:97` — `api.event.subscribe(...)[Symbol.asyncIterator]()`；`packages/app/src` 内 `new EventSource` **0 命中** |
| 服务端 | `contentType: "text/event-stream"`，15s 心跳 | `packages/server/src/handlers/event.ts:26,22` |
| 客户端校验 | `if (!isContentType(response, "text/event-stream"))` | `client.ts:354` |

> **对宿主的含义**：SSE 走 `fetch` 流式 → 需要 `ReadableStream` + 长连接支持（Android WebView 80+ 满足，minSdk 26 对应 WebView 80+，但 WebView 版本可独立更新）。**不能用 `EventSource` 的原生重连语义**，这点要留意 app 自己的重连逻辑。**心跳/超时具体行为未核对**。

**WebSocket（PTY 终端）**：

```ts
// _upstream/packages/client/src/solid/pty.ts:29-35
const url = new URL(`/api/pty/${encodeURIComponent(input.ptyID)}/connect`, options.url)
if (input.location?.directory) url.searchParams.set("location[directory]", input.location.directory)
if (input.cursor !== undefined) url.searchParams.set("cursor", String(input.cursor))
url.searchParams.set("ticket", result.data.ticket)
url.protocol = url.protocol === "https:" ? "wss:" : "ws:"
const socket = options.openSocket?.(url) ?? new WebSocket(url)
socket.binaryType = "arraybuffer"
```

| 项 | 结论 | 证据 |
|---|---|---|
| 路径 | `/api/pty/{ptyID}/connect?ticket=…&cursor=…` | `pty.ts:29-32` |
| 持久终端路径 | `/api/experimental/persistent-pty/{ptyID}/connect?ticket=…&attachment_id=…&takeover=…&input_protocol=1` | `pty.ts:49-54` |
| 协议 | `http:`→`ws:`，`https:`→`wss:`（**从 API base 推导**） | `pty.ts:33,55` |
| base 来源 | `createPtyClient(api, { url: http.url })` | `packages/app/src/runtime/server/client.tsx:131` |
| 二进制 | `socket.binaryType = "arraybuffer"` | `pty.ts:36,58` |
| 服务端 | **同一个 HTTP server 同一端口做 upgrade** | `packages/server/src/handlers/pty.ts:167` — `ctx.request.upgrade`；`persistent-pty.ts:126` 同 |
| 鉴权 | 走 **ticket**，**不走 Basic** | `packages/server/src/middleware/authorization.ts:54` — `if (hasPtyConnectTicketURL(url) …) return`；ticket 正则 `protocol/src/groups/pty.ts:13` |

> 🔴 **对宿主的致命含义：`WebSocket` 是发往「后端 server 的 origin」，不是页面 origin。所以 `WebViewAssetLoader` 代理不了它**（`shouldInterceptRequest` 不参与 WebSocket 握手）。**后端必须是真实可路由的网络地址**，`ws://`/`wss://` 必须能连通。这就锁死了 §2.4 的选择：不能用「本地假域 + 代理 /api」的方案，必须让 app 指向真实 server。

### 3.4 认证：怎么带凭据

**机制：HTTP Basic，用户名硬编码 `opencode`。无 cookie、无 bearer、无 CSRF token。**

**客户端：**

```ts
// _upstream/packages/app/src/runtime/server/api.ts:5-7
export function authTokenFromCredentials(input: { password: string }) {
  return btoa(`opencode:${input.password}`)
}

// _upstream/packages/app/src/runtime/server/api.ts:23-33
return OpenCode.make({
  baseUrl: input.server.url,
  fetch: input.fetch,
  headers: input.server.password
    ? { Authorization: `Basic ${authTokenFromCredentials({ password: input.server.password })}` }
    : undefined,
})
```

**URL 引导（登录一次即抹掉）：**

```ts
// _upstream/packages/app/src/entry.tsx:39-44
const clearAuthToken = () => {
  const params = new URLSearchParams(location.search)
  if (!params.has("auth_token")) return
  params.delete("auth_token")
  history.replaceState(null, "", location.pathname + (params.size ? `?${params}` : "") + location.hash)
}
// entry.tsx:75
const auth = authFromToken(new URLSearchParams(location.search).get("auth_token"))
```

**解码（`authFromToken`，`api.ts:9-17`）**：base64 → 取第一个 `:` 之后的部分作为 `password`。测试断言 `authFromToken(btoa("opencode:secret"))` → `{password: "secret"}`（`packages/app/src/runtime/server/api.test.ts:6`）。

**凭据存哪：**

| 存储 | 键 | 证据 |
|---|---|---|
| `localStorage` | `opencode.global.dat:server`（服务器列表 + 密码） | `storage.ts:30` — `const GLOBAL_STORAGE = "opencode.global.dat"`；`storage.ts:399-400` — `global(key)`；`registry.tsx:203-207` — `Persist.global("server")`, `previousKey: "server.v3"` |
| `localStorage` | `opencode.settings.dat:defaultServerUrl` | `web.ts:5,69,79` |
| 密码字段 schema | `password: Schema.optional(Schema.String)` | `packages/app/src/runtime/server/persistence.ts:8` |

**服务端期望：**

```ts
// _upstream/packages/server/src/middleware/authorization.ts:10-11
const AUTH_TOKEN_QUERY = "auth_token"
const WWW_AUTHENTICATE = 'Basic realm="Secure Area"'
// authorization.ts:32-35 —— 先查 query token，再解析 Authorization 头
```
- 用户名固定：`packages/server/src/auth.ts:17` — `{ ...input, username: "opencode" }`
- 比对：`packages/server/src/auth.ts:29-33` — 比对 username + 精确密码，**无 cookie/session**
- WS 旁路：`authorization.ts:54`（见 §3.3）

> **对宿主的含义：**
> 1. `Authorization` 头**不是** CORS safelisted 头 → 跨源场景会触发 **OPTIONS 预检**，服务端必须放行（**服务端是否配了 CORS = 未核对**，见 §6）。**这是跨源方案最大的未知风险。**
> 2. 密码以**明文 base64** 存在 `localStorage`。若页面 origin 是 `https://appassets.androidplatform.net`（secure context），WebView 会把该 origin 的数据加密存储，风险可控。
> 3. 宿主**不需要**自己管理 cookie / `CookieManager`（无 cookie 流程）。

### 3.5 WebView 需要开的权限 / 设置（逐条）

| 能力 | 用到了？ | 证据 | 宿主必须做什么 |
|---|---|---|---|
| `localStorage` | ✅ | `runtime/persistence/storage.ts:352`、`web.ts:69`、`pwa.ts:16,37` | **默认可用**，无需设置。但 **DOM storage 必须开着**，且**不能**用 `WebSettings.setAppCacheEnabled` 之类的老 API 清它 |
| `IndexedDB` | ✅ | `runtime/persistence/drafts.ts:348` — `indexedDB.open("opencode-drafts", 1)` | **需要 secure context** → 用 `https://appassets.androidplatform.net` 即可满足。**不要**用 `setDatabaseEnabled(false)` |
| `navigator.clipboard` | ✅ | `session/commands/use-session-commands.tsx:127`（`writeText`）、`session/summary/server-panel.tsx:467`、`session/files/open-in-app.tsx:210` | 需要 **secure context** + `ClipboardManager.setPrimaryClip` 兜底建议。WebView 对 `navigator.clipboard` 的支持依赖 WebView 版本，**建议宿主实现 `OnClipboardCommitCallback`/长按菜单兜底** |
| `Notification` | ✅ | `web.ts:31` `new Notification(...)`；`:26` 权限请求；`:29` 可见且有焦点时**直接 return** | **WebView 原生不支持 `new Notification()`**（无系统通知 UI）。**未核对具体降级行为**。宿主如需通知，得自己用 `evaluateJavascript` → `evaluateJavascript` 桥接原生 `NotificationManager`；**或直接接受它不显示**（源码已做 `if (!("Notification" in window)) return` 与焦点检查） |
| `window.open` | ✅ | `web.ts:18` — `window.open(url.href, "_blank", "noopener,noreferrer")`；`runtime/platform/external-link.tsx:14` — `target={local.target ?? "_blank"}` | 🔴 **必须实现 `WebChromeClient.onCreateWindow`**（`setSupportMultipleWindows(true)` + `javaScriptCanOpenWindowsAutomatically=true`），否则外链全丢。**必须实现 `shouldOverrideUrlLoading`** 做外链拦截 |
| 文件选择上传 | ✅ | `composer/editor/editor.tsx:107` — `<input type="file" multiple>`；`settings/workspaces/project.tsx:96-97` — `type="file" accept="image/*"` | 🔴 **必须实现 `WebChromeClient.onShowFileChooser`**（`fileChooserParams.createIntent()` → `startActivityForResult` → `onActivityResult` 回填 `FileChooserParams.parseResult`）。**且 Android 13+ 需 `READ_MEDIA_IMAGES` 等权限或 SAF** |
| Blob 下载 | ✅ | `session/commands/export.ts:47-55` — `new Blob` / `URL.createObjectURL` / `a.download` / `a.click()` / `URL.revokeObjectURL`；`:63-65`（无 `platform.saveFile` 时的 web 路径） | 🔴 **必须实现 `DownloadListener`**（`webView.setDownloadListener`）。⚠️ `blob:` URL 的下载**不触发** `DownloadListener`（Blob 不走网络栈）——需宿主注入 JS 拦截 `a.click()` 或用 `JavascriptInterface` 接收 base64 后写文件。**该拦截方案未核对，需 W1 验证** |
| 摄像头 | ✅ | `servers/connect/camera.ts:6` — `!!navigator.mediaDevices?.getUserMedia`（依赖 `isSecureContext`） | 需要 `CAMERA` 权限 + `onPermissionRequest`。secure context 已由 `https://` 假域名满足。**建议不实现，让它走不到** |
| `matchMedia` | ✅ | `pwa.ts:8` `(display-mode: standalone)`、`session/browser/pane.tsx:179` `(prefers-color-scheme: dark)`、`settings/about/animated-wordmark.tsx:17` `(prefers-reduced-motion: reduce)` | API 本身可用。⚠️ **但 `(prefers-color-scheme: dark)` 不保证随系统 `uiMode` 翻转**（Chromium 只有 embedder 允许 algorithmic darkening 时才当深色）⇒ 宿主必须把 uiMode 真值转交给页面，见 `oc-host.js` 的深浅色桥与 `scripts/verify-android-scheme.mjs` |
| `visualViewport` | ✅ | `runtime/platform/keyboard.ts:5`、`session/terminal/panel.tsx:72,93,94` | **需要处理软键盘 inset** → 宿主要正确配置 `adjustResize`/`adjustPan` + `WindowInsets` |
| `BroadcastChannel` | ✅ | `runtime/persistence/storage.ts:595` | 默认可用 |
| `WebSocket` | ✅ | `pty.ts:35,57` | 见 §3.3；跨域 WS 不做 CORS 预检，但**服务端要接受 `Origin` 头** |
| `fetch` 流式响应 | ✅ | `client.ts:361` `response.body.getReader()` | WebView 80+ 支持 |
| `ServiceWorker` | ✅ | `entry.tsx:49` | **建议按 §3.2 禁掉** |
| `sessionStorage` | ❌ | `packages/app/src` 内 NOT FOUND | — |
| `navigator.vibrate` / `wakeLock` / `geolocation` / `getDisplayMedia` / `requestIdleCallback` / `SharedWorker` / `new Worker(` | ❌ | `packages/app/src` 内均 NOT FOUND | — |
| `navigator.share` / `saveAs` / `showOpenFilePicker`（File System Access） | ❌ | NOT FOUND | — |
| `document.cookie` | 仅 locale，非认证 | `runtime/i18n/language.tsx:249` | 无需处理 |

**另需的 WebView 设置（基于上表推导）：**

```kotlin
webSettings.apply {
    javaScriptEnabled = true                 // 必须
    domStorageEnabled = true                 // 必须（localStorage）
    databaseEnabled = true                   // 必须（IndexedDB）
    setSupportMultipleWindows(true)          // 必须（window.open）
    javaScriptCanOpenWindowsAutomatically = true  // 必须（window.open）
    allowFileAccess = false                  // 建议关（收紧）
    allowContentAccess = false               // 建议关（收紧）
    mediaPlaybackRequiresUserGesture = true  // 建议保持
    cacheMode = WebSettings.LOAD_NO_CACHE    // 建议：本地资源无缓存价值；见 §4
    setGeolocationEnabled(false)             // 建议关
    // 不调用 setAppCacheEnabled / setDatabaseEnabled(false) / clearCache on load
}
webView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
```

### 3.6 CSP / meta 头

**`dist/index.html` 里没有任何 CSP meta 标签**（§1.4 原文，`<head>` 内只有 `charset` / `viewport` / `theme-color` / `mobile-web-app-capable` / `og:image` / 一个 inline `<script id="oc-theme-preload-script">`）。**没有 `<meta http-equiv="Content-Security-Policy">`。**

`dist/_headers`（518 B，Netlify/Cloudflare 风格）**也不含 CSP**，只有 Content-Type 与 sw.js 的 no-cache：

```
/_assets/*.js        Content-Type: application/javascript
/_assets/*.mjs       Content-Type: application/javascript
/_assets/*.css       Content-Type: text/css
/*.js                Content-Type: application/javascript
/*.mjs               Content-Type: application/javascript
/*.css               Content-Type: text/css
/site.webmanifest    Content-Type: application/manifest+json
/sw.js               Content-Type: application/javascript   Cache-Control: no-cache
/registerSW.js       Content-Type: application/javascript   Cache-Control: no-cache
```

**但上游 CLI 真实运行时用响应头发严格 CSP**（这是最相关的参考，因为它是「官方」的托管实现）：

```ts
// _upstream/packages/cli/src/services/web-ui.ts:58-60
function csp(hash = "") {
  return `default-src 'self'; script-src 'self' 'wasm-unsafe-eval'${hash ? ` 'sha256-${hash}'` : ""}; style-src 'self' 'unsafe-inline'; img-src 'self' data: https: blob:; font-src 'self' data:; media-src 'self' data:; connect-src * data: blob:`
}
// web-ui.ts:62-67 —— cspForHtml：把 index.html 里那个 inline 脚本的正文 sha256 塞进 script-src
```

拆解这条 CSP 对宿主的含义：

| 指令 | 值 | 含义 / 是否踩坑 |
|---|---|---|
| `script-src` | `'self' 'wasm-unsafe-eval' 'sha256-<内联脚本hash>'` | 🔴 **`<script id="oc-theme-preload-script">` 是内联的，没有 hash 就会被拦** → 主题不生效（白底闪一下）。我们用 `WebViewAssetLoader` 默认**不发 CSP**，反而安全。**若想对齐上游，必须算 hash**（`web-ui.ts:62-67` 有现成算法） |
| `'wasm-unsafe-eval'` | — | 应用用了 **WASM**（仓库内有 `packages/effect-drizzle-sqlite`）→ 若加 CSP，**漏掉 `wasm-unsafe-eval` 会让 SQLite/WASM 挂掉** |
| `style-src` | `'self' 'unsafe-inline'` | 主题 CSS 是内联注入的（`index.html:37-50`）→ 必须 `unsafe-inline` |
| `img-src` | `'self' data: https: blob:` | 必须有 `https:`（通知图标 `https://opencode.ai/favicon-96x96-v3.png`，`web.ts:33`）+ `blob:`（图片预览 `drafts.ts:61,91`） |
| `font-src` | `'self' data:` | `/assets/*.ttf`、`*.woff2` 同源 ✅ |
| `media-src` | `'self' data:` | 音频资源（`dist/_assets/*.aac`，见构建日志 46-85 行）✅ |
| `connect-src` | `* data: blob:` | 🔴 **`*` = 允许任意跨源 fetch/WS** → 这正是跨源后端的合法性依据（浏览器侧不拦） |

**⇒ 建议：宿主第一版不要加 CSP**（`AssetsPathHandler` 默认不加），把 CSP 列为后续加固项。真要加，必须同时满足：① 算 `oc-theme-preload-script` 的 sha256；② 带 `'wasm-unsafe-eval'`；③ `style-src` 带 `'unsafe-inline'`；④ `connect-src` 不能收窄成 `'self'`（否则跨源 API 全挂）。

### 3.7 顺带核对的：Content-Type / SPA 回退

上游 CLI 的托管规则（`web-ui.ts:27-51`）值得照抄，因为**`AssetsPathHandler` 不会自动做 SPA 回退**：

```ts
// web-ui.ts:30-33
if ((key.startsWith("_assets/") || key.startsWith("icons/")) && requested === undefined)
  return HttpServerResponse.empty({ status: 404, headers: { "cache-control": "no-store" } })
const name = requested !== undefined ? key : "index.html"   // 未知路径 → 回退 index.html
// web-ui.ts:37-41
const revalidate = html || name === "sw.js" || name === "registerSW.js"
"cache-control": revalidate ? "no-cache" : "public, max-age=31536000, immutable"
// web-ui.ts:18-19
if (url.pathname === "/api" || url.pathname.startsWith("/api/")) return 404   // /api 归后端，不归 UI
```

⇒ **宿主必须自己实现 SPA 回退**：宿主 WebView 里是**单页应用**（`entry.tsx` 没有路由注册，但 app 内部有客户端路由，见 `pwa.ts:20-22` 允许的路径 `/`、`/new-session`、`/server/{id}/session/{id}`），内部跳转若触发整页加载，需要宿主把未知路径映射到 `index.html`。
⚠️ `WebViewAssetLoader.AssetsPathHandler` 对未命中文件返回 404，**不做回退** → 需自定义 `PathHandler` 包一层，或在客户端用 hash/history 路由规避。**具体回退实现需 W1 验证**（见 §6）。

---

## 4. 风险清单（已知会坏 / 会变差的点）

| # | 风险 | 依据 | 若发生，先查哪里 |
|---|---|---|---|
| **R1** | 🔴 **白屏：`/_assets/…` 全部 404**（PathHandler 前缀配成 `/assets/webapp/` 之类） | 资源是绝对路径（`index.html:53-97`）；`vite.config.ts` 无 `base` | ① `WebViewAssetLoader` 前缀是否严格为 `/`；② `WebViewClientCompat.shouldInterceptRequest` 是否真的调了 `loader.shouldInterceptRequest`；③ 打开 `chrome://inspect` 看 `/_assets/index-*.js` 的状态码 |
| **R2** | 🔴 **接口全 404 / 一直转圈**：生产构建把 API 绑到 `location.origin` = 假域名 | `web.ts:63` `return location.origin`；`api.ts:24` `baseUrl: input.server.url` | ① 构建时有没有设 `VITE_OPENCODE_SERVER_MODE=none`（`web.ts:56`）；② 运行时 `localStorage["opencode.settings.dat:defaultServerUrl"]`（`web.ts:5,69`）有没有写对；③ App 内是否停在「连接服务器」界面 |
| **R3** | 🔴 **CORS 预检失败**：`Authorization` 头触发 OPTIONS，服务端可能没配 CORS | `api.ts:28-30` 发 `Authorization: Basic …`（非 safelisted 头）；**服务端 CORS 配置未核对** | ① `chrome://inspect` Network 面板看 `/api/session` 的 OPTIONS 是否 404/401；② 服务端是否装了 CORS 中间件（去 `packages/server/src/middleware/` 核对） |
| **R4** | 🔴 **终端（PTY）连不上**：WebSocket 是发往后端 origin，`WebViewAssetLoader` 代理不了 WS 握手 | `pty.ts:29,33`（URL 基于 `http.url`，`client.tsx:131`）；服务端 `handlers/pty.ts:167` 同端口 upgrade | ① `ws://`/`wss://` 是否被 `networkSecurityConfig` 放行（**明文流量**）；② 服务端是否接受跨源 `Origin` 头；③ ticket 是否过期（`pty.ts:32` 先要 `/api/pty/{id}/connect` 拿 ticket） |
| **R5** | 🔴 **明文 HTTP/WS 被 Android 拦**：`targetSdk 35` 默认禁明文 | `app/build.gradle.kts:14` `targetSdk = 35` | 查 `AndroidManifest.xml` 的 `usesCleartextTraffic` 与 `networkSecurityConfig`；`web://inspect` 看 `net::ERR_CLEARTEXT_NOT_PERMITTED` |
| **R6** | 🟠 **前端发新版不生效**：SW `skipWaiting:false` + app 无更新提示 UI，新 SW 永远 `waiting` | `vite.pwa.ts:9,16,18`；app 内 `controllerchange`/`updatefound`/`SKIP_WAITING` 全 **NOT FOUND** | ① 确认已按 §3.2 排除 `sw.js`；② 装机后清 WebView 数据 / `pm clear` 应用数据重测；③ 查 `chrome://inspect` → Application → Service Workers 是否还有残留 |
| **R7** | 🟠 **APK 胖 52 MB**：整包拷 dist | `dist` 84.65 MB 含 **752 个 `.map` / 52.04 MB**；上游 `app-assets.ts:17` 自己过滤 `.map` | 查打包脚本有没有排除 `**/*.map`；`unzip -l app-release.apk | grep '\.map'` 应为 0 |
| **R8** | 🟠 **外链全丢**：`window.open(_blank)` 无 `onCreateWindow` | `web.ts:18`；`external-link.tsx:14` | 查 `setSupportMultipleWindows(true)` + `WebChromeClient.onCreateWindow` 是否实现；未实现时点外链无反应 |
| **R9** | 🟠 **附件/图标上传无反应**：无 `onShowFileChooser` | `composer/editor/editor.tsx:107`；`settings/workspaces/project.tsx:96` | 查 `WebChromeClient.onShowFileChooser` + `onActivityResult` 回填 `parseResult`；Android 13+ 检查 SAF/媒体权限 |
| **R10** | 🟠 **导出下载无反应**：`blob:` URL 不触发 `DownloadListener` | `session/commands/export.ts:47-55`（`createObjectURL` + `a.click()`） | 查 `setDownloadListener`；blob 场景需 JS 拦截 + `JavascriptInterface` 写文件（**方案未核对**） |
| **R11** | 🟠 **软键盘遮挡终端输入**：`visualViewport` 被用来算 inset | `runtime/platform/keyboard.ts:5`；`session/terminal/panel.tsx:72,93,94` | 查 `windowSoftInputMode`（`adjustResize`）与 `WindowInsets` IME 处理 |
| **R12** | 🟡 **主题闪烁/不生效**：若宿主加了 CSP 而没带内联脚本 hash | `web-ui.ts:42-44,62-67`；`index.html:19-52` 内联脚本 | 查是否设了 CSP；是则算 `oc-theme-preload-script` 的 sha256 加进 `script-src` |
| **R13** | 🟡 **WASM/SQLite 崩**（若加 CSP 漏 `wasm-unsafe-eval`） | `web-ui.ts:59` 明确带 `'wasm-unsafe-eval'`；仓库有 `packages/effect-drizzle-sqlite` | 查 CSP `script-src` 是否含 `wasm-unsafe-eval` |
| **R14** | 🟡 **通知不显示**：`new Notification()` 在 WebView 无系统 UI | `web.ts:31`；`web.ts:22` 已有 `if (!("Notification" in window)) return` | 源码已优雅降级（`web.ts:22,29`），一般不需处理；如需原生通知要自己桥接 |
| **R15** | 🟡 **首屏慢/内存高**：预缓存 32.5 MB + 1642 个 JS chunk 懒加载 | 构建日志 `precache 911 entries (33271.39 KiB)`；`dist/_assets` 1642 文件 | `chrome://inspect` Performance 录制；确认已禁 SW；`setCacheMode(LOAD_NO_CACHE)` 避免双份缓存 |
| **R16** | 🟡 **SPA 深链刷新 404**：宿主无回退 | `web-ui.ts:32` 上游有回退，`AssetsPathHandler` 没有 | 查路径 `/new-session`、`/server/{id}/session/{id}`（`pwa.ts:20-22` 白名单）直接访问是否白屏 |

---

## 5. 宿主 checklist（给 W1，可直接照做）

> 假设 `webapp` module 已在建（现状：`webapp/build.gradle.kts`、`webapp/src/main/{assets,java,res}`、`webapp/src/main/AndroidManifest.xml` 已存在）。

### 5.1 打包资源

- [ ] 把 `dist` 内容（**排除 `**/*.map`，排除 `sw.js`，排除 `_headers`**）拷到 **`webapp/src/main/assets/` 根**，使 `assets/index.html` 存在。
      → **不要**放成 `assets/webapp/index.html`（见 §2.2 方案 C 会白屏）。
- [ ] 目标体积约 **32.6 MB**（84.65 − 52.04 sourcemap − 0.12 sw.js）。装机后校验：`unzip -l app-*.apk | grep -c '\.map'` 应为 **0**。
- [ ] 保留：`_assets/`、`assets/`（两个字体）、`icons/`、`index.html`、`site.webmanifest`、各 favicon/apple-touch/social-share png、`oc-theme-preload.js`。

### 5.2 域名与 PathHandler（**唯一推荐**）

```kotlin
private const val DOMAIN  = "appassets.androidplatform.net"
private const val PREFIX  = "/"                       // ← 必须严格是 "/"

val assetLoader = WebViewAssetLoader.Builder()
    .setDomain(DOMAIN)
    .addPathHandler(PREFIX, WebViewAssetLoader.AssetsPathHandler(requireContext()))
    .build()

webView.webViewClient = object : WebViewClientCompat() {
    override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest) =
        assetLoader.shouldInterceptRequest(req.url)
}
```

- [ ] 域名 = `appassets.androidplatform.net`（`androidx.webkit` 保留域名；`https` ⇒ secure context ⇒ IndexedDB/clipboard/getUserMedia 可用）
- [ ] PathHandler 前缀 = `/`（**不是** `/assets/`、**不是** `/webapp/`）
- [ ] assets 根 = `webapp/src/main/assets/`
- [ ] 启动：`webView.loadUrl("https://appassets.androidplatform.net/index.html")`
- [ ] **不设 `base`**（`--base` 不需要；改了会破坏 `navigateFallback: "/index.html"`，见 §2.3）
- [ ] 构建时 `VITE_OPENCODE_SERVER_MODE=none`（`web.ts:56`），否则 API 会打到假域名（R2）

### 5.3 WebView 设置

- [ ] `javaScriptEnabled = true`
- [ ] `domStorageEnabled = true`（localStorage，`storage.ts:352`）
- [ ] `databaseEnabled = true`（IndexedDB，`drafts.ts:348`）
- [ ] `setSupportMultipleWindows(true)` + `javaScriptCanOpenWindowsAutomatically = true`（`web.ts:18`）
- [ ] `allowFileAccess = false`、`allowContentAccess = false`（收紧）
- [ ] `setGeolocationEnabled(false)`（用不到）
- [ ] **不要**调 `setDatabaseEnabled(false)` / `setAppCacheEnabled`
- [ ] `setWebContentsDebuggingEnabled(BuildConfig.DEBUG)`
- [ ] `windowSoftInputMode = adjustResize` + `WindowInsets` IME（`keyboard.ts:5`、`terminal/panel.tsx:72`）

### 5.4 必须实现的回调

- [ ] `WebViewClientCompat.shouldInterceptRequest` → 转发 `assetLoader`（**不加就是白屏**，R1）
- [ ] `WebViewClientCompat.shouldOverrideUrlLoading` → 拦外链（`external-link.tsx:14` 的 `_blank`；`web.ts:17` 只允许 `http/https/mailto`）
- [ ] `WebChromeClient.onCreateWindow` → 新开 WebView 承载 `window.open`（`web.ts:18`）
- [ ] `WebChromeClient.onShowFileChooser` → `startActivityForResult` + `FileChooserParams.parseResult` 回填（`editor.tsx:107`、`project.tsx:96`）
- [ ] `setDownloadListener` → **注意 `blob:` 不触发**，`export.ts:47-55` 需 JS 拦截 + `JavascriptInterface` 写文件（R10）
- [ ]（可选）`onPermissionRequest` → 摄像头 `camera.ts:6`

### 5.5 要禁 / 要放的东西

- [ ] 🔴 **禁 SW**：不打包 `dist/sw.js` → `/sw.js` 404 → 注册静默失败（`entry.tsx:48-49` 无条件注册，无法用配置关）
- [ ] 🔴 **不要**设 CSP（第一版）。`WebViewAssetLoader` 默认不发 CSP，`index.html` 也无 CSP meta ⇒ 天然安全
- [ ] **清空 SW 残留**：测试机上 `pm clear <applicationId>` 或清 WebView 数据（R6）
- [ ]（可选加固）若一定要加 CSP，必须：`script-src 'self' 'wasm-unsafe-eval' 'sha256-<oc-theme-preload-script 的 hash>'`、`style-src 'self' 'unsafe-inline'`、`img-src 'self' data: https: blob:`、`font-src 'self' data:`、`connect-src * data: blob:`（**不能收窄成 `'self'`**，否则跨源 API 全挂）——照抄 `web-ui.ts:58-67`

### 5.6 要注入的运行期配置

- [ ] 🔴 **后端 URL**（三选一）：
  - [ ] ③（推荐）让用户在 App 内「连接服务器」填 URL+密码（`packages/app/src/servers/connect/`）
  - [ ] ② 预置 `localStorage["opencode.settings.dat:defaultServerUrl"] = "http://<host>:4096"`（`web.ts:5`）— ⚠️ 需在页面脚本前写入，有竞态
  - [ ] ① 仅带密码：`loadUrl("...index.html?auth_token=" + Base64("opencode:" + pwd))`（`entry.tsx:75`、`api.ts:6`）— **不能设 URL**
- [ ] 密码存储：App 自己放 `localStorage["opencode.global.dat:server"]`（`storage.ts:30`、`registry.tsx:203-207`），宿主不用管
- [ ] 无 cookie 流程 → 宿主**不需要**碰 `CookieManager`

### 5.7 网络与 Manifest

- [ ] `INTERNET` 权限
- [ ] 后端若为 **HTTP/WS 明文**：`targetSdk 35` 默认禁 → 需 `android:usesCleartextTraffic="true"` 或 `networkSecurityConfig` 精确放行（**别用全量放行**，只放行后端 host）（R5）
- [ ] （可选）`POST_NOTIFICATIONS`（Android 13+），若要桥接原生通知

### 5.8 联调自检（10 分钟内可跑完）

1. `chrome://inspect` → 打开 WebView → **Console 无红字**、**Network 里 `/_assets/index-*.js` = 200**
2. 点「设置/连接服务器」→ 能连上后端（验证 R2）
3. `Network` 里 `GET /api/info` = 200（验证 R3 的 CORS 预检）
4. 开一个终端 → WebSocket 帧在流（验证 R4）
5. 传一个附件 → 文件选择器弹出（验证 R9）
6. 导出 session → 有文件落到下载目录（验证 R10）
7. 点一个外链 → 打开新页（验证 R8）
8. 输入法弹出时终端不被遮挡（验证 R11）
9. 装个新包覆盖安装 → 前端内容确实变了（验证 R6）
10. `unzip -l app-release.apk | grep -c '\.map'` → 0（验证 R7）

---

## 6. 未核对清单（NOT VERIFIED）

| # | 未核对项 | 为什么重要 / 怎么补 |
|---|---|---|
| U1 | **服务端是否配置了 CORS**（`packages/server/src/middleware/` 未逐个核对是否有 CORS 插件） | 🔴 直接决定 R3。若无 CORS，跨源方案（假域 + 远端 server）**整个不成立**，必须改为「后端自己托管前端」或宿主加 OkHttp 代理 REST。**补法**：`grep -ri cors packages/server/src` |
| U2 | **`WebViewAssetLoader.AssetsPathHandler` 对预压缩文件（br/gzip）的处理** | 决定能否把 32.6 MB 再压到 ~10 MB。**补法**：小规模实测（把一个 `.js` 预压成 `.br` 并在 `WebResourceResponse` 里带 `Content-Encoding`） |
| U3 | **SPA 回退（deep link 刷新）**：`AssetsPathHandler` 404 后宿主如何回退到 `index.html` | 白名单路径见 `pwa.ts:20-22`（`/`、`/new-session`、`/server/{id}/session/{id}`）。**补法**：实现自定义 `PathHandler` 包一层并实测 |
| U4 | **`blob:` 下载的可用拦截方案** | R10 的解法未验证。**补法**：注入 JS hook `HTMLAnchorElement.prototype.click` + `JavascriptInterface` 收 base64 |
| U5 | **`servers=[]` 时 App 是否显示「连接服务器」界面** | 方案 A（`VITE_OPENCODE_SERVER_MODE=none`）依赖这个 UX。`entry.tsx:95-97` 传 `servers={[]}` / `defaultServer={undefined}`，但 `AppInterface` 内部行为**未逐行读**。**补法**：真机点一次确认 |
| U6 | **localStorage 预置与页面脚本的竞态窗口** | 方案 ② 的可靠性。**补法**：实测 `evaluateJavascript` vs `loadUrl` 顺序 |
| U7 | **SSE 重连/心跳在 WebView 中的实际行为** | 15s 心跳在 `server/src/handlers/event.ts:22`，但客户端重连退避策略**未读**。**补法**：抓包看 `/api/event` 断网恢复 |
| U8 | **Android WebView 实际版本**（`minSdk 26` 只保证下限，WebView 可独立更新） | 影响 `ReadableStream` / `clipboard` / `IndexedDB` 细节。**补法**：目标机型上 `chrome://inspect` 看 WebView 版本 |
| U9 | **`.aac` 音频与 KaTeX 字体在移动端的加载耗时** | 首屏体验。**补法**：Performance 录制 |
| U10 | **是否需要 `registerSW.js`** | 已确认 dist 中 **ABSENT**，但 `_headers:26` / `web-ui.ts:38` 引用它 → 判定为**死代码**，未再深挖历史原因 |
| U11 | **WASM/SQLite 在 WebView 的实际启用与体积** | 仅从 CSP `'wasm-unsafe-eval'` 与包名 `packages/effect-drizzle-sqlite` 推断，**未在 dist 中定位到 `.wasm` 文件**（扫描 dist 时未见 `.wasm`）→ 实际是否真的用 WASM 待确认 |
| U12 | **通知的原生桥接方案** | 源码优雅降级（`web.ts:22,29`），但产品上是否需要原生通知未与需求方确认 |
| U13 | **服务端密码的环境变量名** | `packages/server/src/auth.ts:16` 只接受注入的 `{ password }`，**未找到**读取 env 的位置 → 宿主无法从源码确定如何配置后端密码 |

---

## 附录 A：上游托管实现速记（给 W1 当参考实现）

`_upstream/packages/cli/src/services/web-ui.ts` 是官方托管实现，规则如下（W1 可 1:1 照抄）：

| 规则 | 代码位置 |
|---|---|
| `/api/*` 归后端，UI 不碰 | `web-ui.ts:18-19` |
| 未知路径 → `index.html`（SPA 回退） | `web-ui.ts:32-33` |
| `_assets/` `icons/` 未命中 → **404 不回退** | `web-ui.ts:30-31` |
| `index.html` / `sw.js` / `registerSW.js` → `no-cache` | `web-ui.ts:37-38, 41` |
| 其他 → `max-age=31536000, immutable` | `web-ui.ts:41` |
| `x-content-type-options: nosniff` | `web-ui.ts:45` |
| HTML 带内联脚本 hash 的 CSP；其他带基础 CSP | `web-ui.ts:42-44, 58-67` |
| 非 GET/HEAD → 405 | `web-ui.ts:35-36` |
| 打包时丢弃 `.map` | `packages/cli/script/app-assets.ts:17` |
| 打包时 brotli 质量 6 | `packages/cli/script/app-assets.ts:30-34` |

> `immutable` + 内容 hash 文件名是上游的核心假设：`_assets/<name>-<hash>.js` 变了文件名就变 → 宿主可放心长缓存。**但 `WebViewAssetLoader` 不方便设 `Cache-Control`，实践上靠 `LOAD_NO_CACHE` 更简单**（本地资源读盘很快，缓存意义不大）。

## 附录 B：证据文件速查

| 用途 | 路径 |
|---|---|
| 构建产物 | `_upstream/packages/app/dist/` |
| 构建日志 | `research/web/_build-app.log` |
| 页面入口 | `dist/index.html`（原文见 §1.4） |
| Vite 配置（`assetsDir`、无 `base`） | `_upstream/packages/app/vite.config.ts:41` |
| PWA/SW 配置 | `_upstream/packages/app/vite.pwa.ts`（8-25 行） |
| SW 注册（唯一） | `_upstream/packages/app/src/entry.tsx:48-50` |
| 后端 origin 决策 | `_upstream/packages/app/src/runtime/platform/web.ts:55-64` |
| 默认服务器持久化键 | `_upstream/packages/app/src/runtime/platform/web.ts:5,69,79` |
| API base + Basic 认证 | `_upstream/packages/app/src/runtime/server/api.ts:5-33` |
| `auth_token` 抹除 | `_upstream/packages/app/src/entry.tsx:39-44,75` |
| 官方托管实现 + CSP | `_upstream/packages/cli/src/services/web-ui.ts:18-67` |
| 官方打包（丢 `.map`） | `_upstream/packages/cli/script/app-assets.ts:11,17,30-34` |
| SSE 端点 | `_upstream/packages/protocol/src/groups/event.ts:44-45` |
| SSE 客户端（流式） | `_upstream/packages/client/src/promise/generated/client.ts:354,361` |
| WS / PTY 端点 | `_upstream/packages/client/src/solid/pty.ts:29-36, 49-58` |
| WS 服务端 upgrade | `_upstream/packages/server/src/handlers/pty.ts:167` |
| 服务端鉴权 | `_upstream/packages/server/src/middleware/authorization.ts:10-11,32-35,54` |
| `window.open` | `_upstream/packages/app/src/runtime/platform/web.ts:14-19` |
| 文件上传 | `_upstream/packages/app/src/composer/editor/editor.tsx:107`、`settings/workspaces/project.tsx:96` |
| Blob 下载 | `_upstream/packages/app/src/session/commands/export.ts:47-55,63-65` |
| IndexedDB | `_upstream/packages/app/src/runtime/persistence/drafts.ts:348` |
| 宿主 Android 配置 | `app/build.gradle.kts:9,13,14,120` |
