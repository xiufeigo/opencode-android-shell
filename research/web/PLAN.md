# v163 — 把上游前端搬到 Android 上跑：可行性 + 落地计划

> 范围：只调研 + 出计划。**未改本壳生产代码**、**未装设备**、**未动 git 分支**（当前 `main`，HEAD `55c27a5`）。
> 唯一副作用：在 `_upstream/packages/app/dist` 产出了构建产物（任务 2 明确授权），日志在 `research/web/_build-app.log`。

## 结论摘要（先看这里）

1. **上游前端 = `packages/app`（`@opencode/app` v2.0.11）**，纯 SolidJS + Vite SPA，**可独立 `vite build` 出静态目录**。**已在本机实际构建成功**（84.6 MB / 1665 文件）。
2. **它名义上独立、实际上强依赖同源**：生产构建里 API base = `location.origin`（`src/runtime/platform/web.ts:63`），不是配置项。放到 `appassets` origin 上，**每个 fetch 都会打到不存在的同源路径**。**这是本方案最大的、且不是"配一下就能绕过"的结构性障碍。**
3. **本仓库已有一套跑通这套架构的 WebView 壳**（`MainActivity.kt` + `LocalGateway.kt` + 19 个 patch），但它 pin 的是 **旧 npm 包 `@opencode-ai/app` v1.18.31**，代码布局是 `src/context/` `src/pages/` `src/utils/`；v2.0.11 已重构为 `src/runtime/` `src/shell/`。**19 个 patch 全部打不上去**（`src/context`、`src/pages`、`src/utils`、`components`、`i18n` 五个目录在 v2 里全部不存在）。这是真实迁移成本的主要来源，也是本轮最该先决策的事。
4. 推荐托管方式：**WebView + `WebViewAssetLoader` + 复用现有 `LocalGateway` 反代**（即现有 legacy 壳的架构），但**必须先解决 API base 注入**，并把"打 patch"换成"实现 `Platform` 接口"。

---

## 任务 1：上游前端定位（行号 + 原文）

### 1.1 是哪个包

`_upstream/packages/app/`，包名 **`@opencode/app`**，版本 **2.0.11**（`packages/app/package.json:4`）。

先排除掉的相似物：

| 包 | 是什么 | 判定 |
| --- | --- | --- |
| `packages/web` | `@opencode/web` v2.0.11，`"build": "astro build"`，依赖 `@astrojs/starlight` | **不是**，这是官网/文档站（Starlight 文档主题） |
| `packages/desktop` | `@opencode/desktop`，`"build": "electron-vite build"` | **不是**，它是 Electron 壳，**反过来依赖 `@opencode/app`**（`packages/desktop/package.json` devDeps 含 `"@opencode/app": "workspace:*"`） |
| `packages/tui` | `@opencode/tui`，opentui 终端 UI | **不是**，终端 UI，非浏览器 |
| `packages/session-ui` | 私有组件库 | **不是**本体，但**是 app 的关键依赖**（`"@opencode/session-ui": "workspace:*"`），会话时间线/消息/输入框的绝大部分实现在这里 |
| `packages/client` | `@opencode/client`，SDK 客户端 | **不是**，是 API SDK |
| **`packages/app`** | **`@opencode/app`，vite build，SolidJS** | ✅ **就是它** |

### 1.2 入口

- `packages/app/index.html:28` — `<script src="/src/entry.tsx" type="module"></script>`
- `packages/app/src/entry.tsx:1-108` — 真实入口。关键行：
  - `src/entry.tsx:46` — `const web = createWebPlatform(pkg.version)`
  - `src/entry.tsx:75-76` — `authFromToken(new URLSearchParams(location.search).get("auth_token"))` 后立刻 `clearAuthToken()`
  - `src/entry.tsx:80-89` — 用 `web.currentServerUrl` 构造 `ServerConnection.Http`（`{ type:"http", authToken, http:{ url, ...auth } }`）
  - `src/entry.tsx:90-106` — `render(<PlatformProvider value={web.platform}> … <AppInterface …/> …)`，`AppInterface` 在 `src/app.tsx:105-134`，内部 `<Dynamic component={props.router ?? Router} root={Root}>` + `<AppRoutes/>`

### 1.3 构建脚本

`packages/app/package.json:12-19`：

```json
"scripts": {
  "start": "vite",
  "dev": "vite",
  "build": "vite build",
  "serve": "vite preview",
  ...
}
```

- `packages/app/vite.config.ts:26-44` — `defineConfig({ plugins:[desktopPlugin, icons(channel), serviceWorker(...), sentry], build:{ assetsDir:"_assets", target:"esnext", sourcemap:true } })`
- `packages/app/vite.js:1-6` — 关键：`solidPlugin()` + `tailwindcss()`，即 **SolidJS 编译 + Tailwind v4**
- `packages/app/vite.pwa.ts` — `VitePWA({ strategies:"generateSW", navigateFallback:"/index.html" })`

### 1.4 框架与版本

从根 `package.json` 的 `workspaces.catalog` + `packages/app/package.json` 读出：

| 项 | 版本 | 证据 |
| --- | --- | --- |
| SolidJS | `1.9.15` | `package.json` catalog `"solid-js": "1.9.15"`（且有 `patches/solid-js@1.9.15.patch`） |
| Vite | `8.2.2` | `packages/app/package.json` devDeps `"vite": "8.2.2"`（有 `patches/vite@8.2.2.patch`） |
| vite-plugin-solid | `2.11.14` | 同上 |
| Tailwind | `4.3.3` | `"@tailwindcss/vite": "4.3.3"` |
| @solidjs/router | `1.0.0` | catalog |
| vite-plugin-pwa | `1.3.0` | devDeps |
| mermaid | `11.17.2` | `packages/session-ui/package.json` |
| shiki | `4.4.3` | catalog（代码高亮，懒加载） |

> 注意：**没有 React**。SolidJS 与 React 的 JSX 运行时、hydration 模型、响应式更新粒度都不同，不能拿 React 生态的优化经验直接套。

### 1.5 路由

`packages/app/src/shell/routes/routes.tsx:34-58`：

```
routes.tsx:34  export function AppRoutes() {
routes.tsx:36    <Route component={AppLayout}>
routes.tsx:37      <Route path="/" component={Home} />
routes.tsx:38      <Route path="/settings" component={SettingsScreen} />
routes.tsx:40      <Route path="/server/:serverKey/session/:id" ... />
routes.tsx:57      <Route path="/new-session" component={DraftRoute} />
routes.tsx:58    </Route>
```

只有 4 条路由，且**全是 `lazy()` 代码分割**（`routes.tsx:14-22`）。会话路由是 `/server/:serverKey/session/:id`。

**路由是浏览器 History 路由**，且 `app.tsx:105` 暴露了 `router?: Component<BaseRouterProps>` 注入口 —— 官方 Desktop 就是用 `MemoryRouter` 注入的（见 `README-webview-legacy.md`："Android uses the same **MemoryRouter** pattern as OpenCode Desktop so `.../opencode-app/index.html` is not treated as a Solid route"）。**这是移植的关键抓手**，v2 里依然保留（`app.tsx:105,133`）。

### 1.6 依赖服务端的哪些接口

**认证**（`packages/app/src/runtime/server/api.ts:5-34`）：

```ts
api.ts:5   export function authTokenFromCredentials(input: { password: string }) {
api.ts:6     return btoa(`opencode:${input.password}`)
api.ts:9   export function authFromToken(token: string | null) { ... 解析成 { password } }
api.ts:23    return OpenCode.make({ baseUrl: input.server.url, headers: ... Authorization: `Basic ${...}` })
```

→ **HTTP Basic，用户名硬编码 `opencode`**，与本壳现有约定完全一致。凭据还可以通过 **URL query `?auth_token=<base64>`** 传入（`entry.tsx:75`），传完立刻从地址栏抹掉（`entry.tsx:39-44`）——**这对 WebView 注入凭据非常好用**。

**API base 的决定逻辑**（`packages/app/src/runtime/platform/web.ts:55-64`）—— **本报告最重要的一段**：

```ts
web.ts:55  function getCurrentServerUrl() {
web.ts:56    if (import.meta.env.VITE_OPENCODE_SERVER_MODE === "none") return undefined
web.ts:57    if (import.meta.env.DEV) { ... return `http://${host}:${port}` }
web.ts:63    return location.origin          // ← 生产构建：无条件用页面 origin
web.ts:64  }
```

生产构建里 **`VITE_OPENCODE_SERVER_HOST` / `VITE_OPENCODE_SERVER_PORT` 只在 `import.meta.env.DEV` 分支生效**。也就是说 **PROD 包无法通过环境变量改 API base**，只能：① 页面同源托管；或 ② 预置 `localStorage["opencode.settings.dat:defaultServerUrl"]`（`web.ts:5,66-73`）并在 UI 里选为默认；或 ③ 改代码/打 patch 注入。

**事件流 SSE**（服务端侧 `packages/server/src/handlers/event.ts:9-34`）：

- 端点：`packages/protocol/src/groups/event.ts:44` — `HttpApiEndpoint.get("event.subscribe", "/api/event", …)`
- `handlers/event.ts:23-33` — `HttpServerResponse.stream(..., { contentType:"text/event-stream", headers:{ "Cache-Control":"no-cache, no-transform", "X-Accel-Buffering":"no", "X-Content-Type-Options":"nosniff" } })`
- `handlers/event.ts:22` — **心跳 `Stream.tick("15 seconds")`**，发 `": heartbeat\n\n"`
- `handlers/event.ts:14-18` — 连上先发一条 `server.connected` 事件
- 客户端侧 `packages/app/src/runtime/server/client.tsx:24-57` `createOpenCodeEventSource()`（基于 `@solid-primitives/event-bus`，**不是 `EventSource`**，是自己包 event-bus + transport）
- `packages/app/src/runtime/server/request-queue.ts:110` — 对 `/api/event` 特殊放行（不排队）

**其它接口**：

- `/api/info` — `packages/protocol/src/groups/server.ts:17` `HttpApiEndpoint.get("server.info", "/api/info", …)`
- `/api/config` — `packages/protocol/src/groups/config.ts:9`
- 客户端 SDK 全部走 `@opencode/client`，路径都是相对 `baseUrl` 的绝对路径（`/api/...`、`/global/...`）

**CORS 白名单**（`packages/server/src/cors.ts:3,11-20`）：

```ts
cors.ts:3   const opencodeOrigin = /^https:\/\/([a-z0-9-]+\.)*opencode\.ai$/
cors.ts:13  if (input.startsWith("http://localhost:")) return true
cors.ts:14  if (input.startsWith("http://127.0.0.1:")) return true
cors.ts:15  if (input.startsWith("oc://renderer")) return true
cors.ts:16  if (input === "tauri://localhost" || …) return true
cors.ts:19  return opts?.cors?.includes(input) ?? false
```

→ **`https://appassets.androidplatform.net` 不在白名单里**。`cors.ts:22-25` 的 `isAllowedRequestOrigin` 允许 `Origin` host 与 `Host` 相同的情况（同源），但资产 origin 与真实服务器 host 永远不同。所以**跨源直连后端会被 CORS 拦**——必须靠服务端 `--cors` 放行，或靠本地反代（同源）。

**PTY WebSocket**：`/pty/{id}/connect`（Upgrade）。`Legacy README` 记录了这条 HTTPS 混内容坑：WebView 里 `ws://127.0.0.1` 会被静默 no-op，需要原生 OkHttp WebSocket polyfill（本壳已有 `NativeWebSocketHub.kt` / `NativeWebSocketJs.kt`）。

**Node 专属 API**：`packages/app/src` 全树 grep `from "node:" | require( | process.platform` → **无匹配**。**纯浏览器包，无 Node 依赖。** 这是好消息。

### 1.7 ⭐ 明确回答：服务端托管 还是 独立 SPA？

**两者都是，但生产意图是"同源托管"：**

- **它是独立 SPA**：有独立 `package.json`、独立 `vite build`、`dist/` 是纯静态文件（`index.html` + `_assets/*` + `sw.js` + `site.webmanifest`），**不打包 agent/服务端**。本次已实测独立构建成功。
- **但它不是"随便放哪都能跑"的 SPA**：`web.ts:63` 生产构建把 `location.origin` 硬绑成 API base。它设计上假设"**页面和 API 同一个 origin**"，由服务端或同源网关托管。
- 另有旁证：`dist/_headers`（Cloudflare Workers/Pages 风格的响应头清单）说明官方是按**同源部署**（Cloudflare）发布的；`index.html:13` 引用 `/site.webmanifest`、`sw.js`，也都是同源相对路径。

→ **一句话：可以独立构建，但不能独立部署到任意 origin。放到 `appassets` origin 上必须额外解决 API base 注入。** 这直接决定了第 3 节的技术方案。

---

## 任务 2：构建可行性

### 2.1 本机工具链实际解析结果

| 命令 | 实际解析路径 | 版本 |
| --- | --- | --- |
| `bun` | `C:\Users\xiufe\AppData\Roaming\npm\bun.ps1` | **1.4.2** |
| `node` | `C:\Program Files\nodejs\node.exe` | **v24.18.0** |
| `npm` | `C:\Program Files\nodejs\npm.ps1` | **11.16.0** |
| `pnpm` | `C:\Users\xiufe\AppData\Local\pnpm\bin\pnpm.ps1` | **11.21.0** |
| `yarn` | `C:\Users\xiufe\AppData\Roaming\npm\yarn.ps1` | 1.22.22 |
| `npx` | `C:\Program Files\nodejs\npx.ps1` | 11.16.0 |

版本一致性核对：根 `package.json:7` 声明 `"packageManager": "bun@1.4.2"`，**与本机 bun 解析结果完全一致** ✅。没有"PATH 解析到旧版"的问题（bun 1.4.2 / node 24 / npm 11 都是当前较新版本）。

### 2.2 代价预估（动手前）

- **依赖已经是装好的**：`_upstream/node_modules` 存在（bun 的 `.bun` 符号链接布局，19 个顶层条目），`packages/app/node_modules` 存在（33 个条目）。**不需要联网重装 monorepo。**
- 关键构建依赖逐个实测可解析（`bun -e "require.resolve(...)"`）：

  | 包 | 解析结果 |
  | --- | --- |
  | `vite` | `…\.bun\vite@8.2.2+3da8b5321b904a7e\…` ✅ |
  | `solid-js` | `…\.bun\solid-js@1.9.15\…` ✅ |
  | `vite-plugin-solid` | `…\.bun\vite-plugin-solid@2.11.14+…` ✅ |
  | `@tailwindcss/vite` | `…\.bun\@tailwindcss+vite@4.3.3+…` ✅ |
  | `vite-plugin-pwa` | `…\.bun\vite-plugin-pwa@1.3.0+…` ✅ |
  | `@sentry/vite-plugin` | `…\.bun\@sentry+vite-plugin@5.4.0+…` ✅ |
  | `@opencode/session-ui` | `…\packages\session-ui\package.json` ✅（workspace 链接） |

- **预估：几十秒级、零下载**。远达不到"几 GB 依赖 / 数小时"的停手线。**判定：可行，直接动手。**

### 2.3 实际构建结果 ✅ BUILD SUCCESSFUL

**命令**（在 `_upstream/` 下）：

```powershell
$env:OPENCODE_CHANNEL="dev"
bun run --cwd packages/app build
```

**结果：成功**（后台任务状态 `succeeded`，`dist/index.html` + `dist/sw.js` 均产出，产物内 773 个 `.js`）。

**耗时**（按 `dist` 内文件 mtime 测得）：
- 写出窗口 **≈ 4 s**（`16:29:57` 首个 entry chunk → `16:30:01` `sw.js`）
- 全程 wall-clock 约 **1 分钟量级**（含 vite 启动、依赖预构建、rolldown 打包）。原计划的 `Stopwatch` 精确计时行未留在日志里，**以文件 mtime 为准，见"未核对项"**。

**产物**：

| 指标 | 值 |
| --- | --- |
| 路径 | `_upstream/packages/app/dist/` |
| 文件数 | **1665**（其中 1649 为本次构建，16 个是 2026-09-21 的旧图标等残留） |
| 总体积 | **84.6 MB** |
| `.map`（sourcemap） | 752 个，**52.0 MB** |
| **去掉 sourcemap** | **32.6 MB**（含 773 个 `.js`） |

**entry chunk**：`dist/_assets/index-AWnJzdVG.js`（由 `dist/index.html` 的 `<script src="/_assets/index-AWnJzdVG.js" type="module">` 引入）。

**体积大户**（非 sourcemap）：

| 文件 | 大小 | 说明 |
| --- | --- | --- |
| `ghostty-web-Ch-lxWIv.js` | 1.38 MB | **终端模拟器（ghostty-web）**，最大单项 |
| `JetBrainsMonoNerdFontMono-Regular.woff2` | 1.06 MB | 字体 |
| `sprite-Cw-OOOIB.svg` | 955 KB | 图标雪碧图 |
| `Inter.ttf` | 875 KB | 字体 |
| `emacs-lisp-c_oH4hRZ.js` / `cpp-*.js` | ~790 KB ×2 | **shiki 语言语法高亮，按需懒加载** |
| `route-IBGsPZAl.js` | 668 KB | 会话路由 chunk |
| `wasm-*.js` | 622 KB ×2 | |

> **这 32.6 MB 不是启动成本**：shiki 语言包、mermaid、ghostty-web 都是 `lazy()` 异步 chunk，启动只下 `index-*.js`。但 **APK 体积会实打实 +32 MB**（或 +84 MB 如果带着 sourcemap，**绝对不要**）。本壳当前 release 29.4 MB / R8 后 17.8 MB（见 `git log` a93acbf），叠加后 APK 会显著变大。

**构建告警（非致命，已确认不影响产物）**：
- vite：`Your Vite config uses features that are unsupported by configLoader: 'native'`（`vite.config.ts:5,6` 省略扩展名）
- CSS：2 处 `'highlight' is not recognized as a valid pseudo-element`（`::highlight(timeline-search-hit)`）
- rolldown：3 个 hook 计时不可测（`vite:css transform` / `vite:asset load` / `@tailwindcss/vite:generate:build`）——**这是打包器计时警告，不是构建错误**
- **无 `error during build` / `Could not resolve` / `ELIFECYCLE`**

---

## 任务 3：Android 托管的技术方案与风险

### 3.1 ⛔ 头号障碍：API base 硬绑 `location.origin`

`web.ts:63` `return location.origin`。把 SPA 放 `https://appassets.androidplatform.net`，`OpenCode.make({ baseUrl: "https://appassets.androidplatform.net" })` 会让**所有** `/api/…` 请求打到这个不存在的 origin。

三条可行路径：

| 方案 | 做法 | 评价 |
| --- | --- | --- |
| **A. 预置 localStorage（推荐）** | `VITE_OPENCODE_SERVER_MODE=none` 构建 → `web.ts:56` 使 `currentServerUrl=undefined` → 改从 `localStorage["opencode.settings.dat:defaultServerUrl"]` 读 → WebView 在导航前用 `evaluateJavascript` 预置该 key | 零上游改动（只改构建环境变量），但**依赖用户进 Settings 手动选一次**或由壳注入 |
| **B. 改 `getCurrentServerUrl()`** | 加一个 `Platform` 字段（如 `apiBaseUrl?`），由壳注入 | 干净，但要改上游源码 |
| **C. 同源反代** | 壳内本地服务器同时吐 SPA **和** 反代 `/api` | 架构最重，但顺带解决 CORS |

> **强烈建议走 v2 的 `Platform` 接口**（`src/runtime/platform/platform.tsx:33-60`，已有 `openExternal` / `restart` / `notify` 等 20+ 能力槽位），新增一个 `PlatformName = "android"`（`platform.tsx:22` 现在是 `"web" | "desktop"`）并实现 `apiBaseUrl`/`storage` 等。**这比继续打 patch 划算得多**——见 3.6。

### 3.2 托管方式对比

| | **A. WebViewAssetLoader** | **B. WebView + 本地服务器** | **C. Capacitor 换壳** | **D. 纯原生（现状）** |
| --- | --- | --- | --- | --- |
| 做法 | `WebViewAssetLoader` 把 `dist/` 挂 `https://appassets.androidplatform.net`；API 走 `127.0.0.1` 反代 | Ktor/NanoHTTPD 在 `127.0.0.1` 随机端口同时吐 SPA+API+SSE+WS 隧道 | 换 Capacitor 工程，WebView 由 Capacitor 管 | 现状 Jetpack Compose |
| 一致性 | ★★★★★ 像素级同桌面 | ★★★★★ | ★★★★☆（Capacitor 有插件差异） | ★★☆☆ |
| 输入延迟 | ★★★☆☆ | ★★★☆☆ | ★★★☆☆ | ★★★★★ |
| 省电 | ★★★☆☆ | ★★☆☆☆（常驻本地服务器 + 长连） | ★★★☆☆ | ★★★★☆ |
| 实现成本 | **低**（本壳已有） | 中 | 中高（换构建体系、丢原生代码） | 0（已有） |
| APK 体积 | **+32.6 MB** | +32.6 MB | +32.6 MB | 0 |
| 风险 | origin≠API 需注入 | 端口/生命周期/Doze 复杂 | 放弃全部现有原生工作 | 一致性目标达不到 |

**推荐：A**（`WebViewAssetLoader` + 复用现有 `LocalGateway.kt` 反代），即**现有 legacy 壳的架构**。B 的本地服务器能顺带解决同源，但常驻监听端口在 Doze 下要额外前台服务，**省电上直接吃亏**。

### 3.3 运行期需求

| 项 | 结论 | 证据 |
| --- | --- | --- |
| **origin** | **必须**是 `https://appassets.androidplatform.net`（真 origin）。`file://` **不行** | ES modules + ServiceWorker + `location.origin`（`web.ts:63`）+ `localStorage`（`web.ts:5`）在 `file://` 下全废 |
| **API base** | 见 3.1，必须注入 | `web.ts:55-64` |
| **同源/CORS** | `appassets` origin 不在白名单 → 必须走同源反代或让服务端 `--cors` 放行 | `cors.ts:3-19` |
| **SSE** | `/api/event`，15s 心跳。反代必须 `proxy_buffering off`；OkHttp 要 `no-transform` | `handlers/event.ts:22-31` |
| **WS (PTY)** | `/pty/{id}/connect`；HTTPS 页面里 `ws://127.0.0.1` 会被静默 no-op → 需原生 WebSocket polyfill | `NativeWebSocketHub.kt` 已实现 |
| **认证** | Basic `opencode:<pwd>`；可用 `?auth_token=base64` 注入后自抹 | `api.ts:5-6`、`entry.tsx:39-44,75-76` |
| **ServiceWorker** | PROD 会 `register("/sw.js")`，asset origin 下可用但**须 `WebViewAssetLoader` 正确给 `application/javascript`** | `entry.tsx:48-50`、`dist/_headers` 有 `/sw.js` MIME 规则 |
| **Node 专属 API** | **无** ✅ | `src` 全树 grep `node:`/`require(`/`process.platform` 无匹配 |
| **路由** | History 路由，需 `MemoryRouter` 注入，否则 `index.html` 被当路由 | `app.tsx:105,133` |
| **大字重依赖** | ghostty-web 1.38 MB / 字体 1.9 MB / shiki 语法包数十个 —— **只在开终端/高亮时懒加载** | `dist/_assets` 体积表 |

### 3.4 必须补的原生桥（本壳已有 vs 必须新增）

| # | 能力 | Android API | 落在哪一层 | 现状 |
| --- | --- | --- | --- | --- |
| 1 | **通知** | `POST_NOTIFICATIONS`（33+ 运行时申请）+ `NotificationChannel` | `notify/SystemNotifier.kt`；JS 侧 `web.ts:21-40` `platform.notify` | ✅ **已有**（`AndroidManifest.xml:22`） |
| 2 | **返回键** | `OnBackInvokedDispatcher` + `enableOnBackInvokedCallback="true"` | `MainActivity.kt:146,240,255` → 先喂 JS（`AndroidBackJs.kt`） | ✅ **已有**（`AndroidManifest.xml:56`） |
| 3 | **IME / 安全区** | `WindowInsetsCompat.ime()` + `setDecorFitsSystemWindows(false)` + `WindowInsetsAnimationCompat` | `MainActivity.kt:303-339` `applyImeAwarePadding` | ✅ **已有**，且踩过一整套坑 |
| 4 | **键盘 inset（JS 侧）** | — | `src/runtime/platform/keyboard.ts:3-33` `KeyboardInsets`（`visualViewport` 监听） | ✅ **上游 v2 自带** |
| 5 | **文件选择/下载** | `ACTION_OPEN_DOCUMENT` / `ACTION_CREATE_DOCUMENT` + `<queries>` | `AndroidManifest.xml:27-41` 已声明 | ⚠️ 部分；WebView `<input type=file>` 默认能弹 SAF，但**下载保存**需 `DownloadListener` |
| 6 | **分享** | `FileProvider` + `ACTION_SEND` | `<queries>` 已声明 | ⚠️ 有 FileProvider，WebView 侧需桥 |
| 7 | **WebSocket (PTY)** | OkHttp WebSocket | `NativeWebSocketHub.kt` | ✅ **已有** |
| 8 | **本地反代** | OkHttp + `127.0.0.1` 随机端口 | `LocalGateway.kt` | ✅ **已有**（CORS 豁免 + WS 隧道） |
| 9 | **系统栏外观** | `WindowInsetsControllerCompat` | `SystemBarsJs.kt` / `OcSystemBars.kt` | ✅ **已有**（1.3.15 修过白状态栏） |
| 10 | **凭据存储** | `EncryptedSharedPreferences` / `DataStore` | `ServerConfigStore.kt` | ✅ **已有**；但 WebView 侧 `localStorage` 里的密码是**明文**（见风险） |
| 11 | **屏幕常亮** | `FLAG_KEEP_SCREEN_ON` / `WindowManager.LayoutParams` | — | ❌ **需确认/新增** |
| 12 | **省电挂起** | `pauseTimers()` / `onPause` | `MainActivity.kt:197,207,269` 已有雏形 | ⚠️ 需复核 |
| 13 | **渲染性能开关** | `setLayerType(LAYER_TYPE_HARDWARE)` | — | 需确认（默认已是 HW） |

**新增必做项：#11 屏幕常亮、#5 下载保存、#10 localStorage 明文凭据**。

### 3.5 性能与省电的现实评估（诚实版）

#### 延迟

- **固有风险**：`<textarea>` 走 IME 合成（`InputConnection`）→ Chromium → JS `input` 事件 → Solid 响应式 → 重新布局 → 上屏。**比原生 `Compose TextField` 多 2~3 个环节**。**大概率 WebView 慢于本壳现有原生输入。**
- **具体风险点**：
  1. **JS 长任务**堵输入。`shiki` 高亮 + `mermaid` 解析 + `marked` 渲染 markdown 流式输出，都在主线程。上游已有 `@tanstack/solid-virtual` 虚拟列表 + `markdown-cache`，但**流式输出时仍在主线程**。
  2. **合成层过多**。`backdrop-filter` / `blur` / 大量 `box-shadow` 在低端 Android GPU 上掉帧。
  3. **IME 高度动画**：键盘弹出是系统动画，WebView 侧只能靠 `visualViewport.resize` 跟上 —— `keyboard.ts:20-21` 已经监听，但**在 120Hz 屏上仍可能掉一两帧**。
  4. **焦点滚动**：`scrollIntoView` 补救本身是额外一帧。
- **缓解手段**：`WebViewAssetLoader` 走本地零网络（首帧快、离线可用）✅ 已有；`sourcemap:false` 构建省 52 MB ✅；`assetsDir:"_assets"` + 懒加载 ✅；必要时 `android:hardwareAccelerated="true"`（默认开）+ 关 `setBackgroundColor` 透明省合成；把 `mermaid`/`shiki` 挪出主线程；`prefers-reduced-motion` 降动画。

#### 省电

- **主要耗电源**：
  1. **长连 SSE**。`handlers/event.ts:22` 每 **15 秒**一个心跳 —— **这是恒定唤醒源**。屏灭后仍在跑。
  2. **常驻 WebView 渲染器**。后台不自动停。
  3. **R8 未覆盖**：SolidJS 响应式 + 大 bundle，GC 压力。
  4. **`sw.js`** 预缓存 1665 个文件（`vite.pwa.ts` `globPatterns:["**/*"]`、`maximumFileSizeToCacheInBytes: MAX_SAFE_INTEGER`）—— 安装时一次性 IO 峰值。
- **可用手段**：`onStop` 时 `pauseTimers()` + 断 SSE（`MainActivity.kt:207,269` 已有）；`visibilitychange` 暂停渲染；退到 Doze（`WebView` 会被系统冻结，配合 `WorkManager` 做重连）；**避免轮询**（上游已 `refetchOnReconnect:false/refetchOnWindowFocus:false/refetchOnMount:false`，`app.tsx:39-42` —— ✅ 很好，别退化）；心跳间隔与服务端协商放大（需改上游）。

#### 量化对比清单（要证明"不输原生"必须测这些）

| # | 指标 | 怎么测 | 判据 |
| --- | --- | --- | --- |
| 1 | 首帧时间 | `adb shell am start -W` + WebView `onPageCommitVisible` 打点 | 冷/热启动各 10 次取 P50/P95 |
| 2 | **输入→上屏延迟** | 在 `<textarea>` 输入，Chrome `Performance`/`adb shell dumpsys gfxinfo`；或埋 `keydown`→`requestAnimationFrame` 双打点 | P95 **< 100 ms** 才追得上原生 |
| 3 | 卡顿率 | `adb shell dumpsys gfxinfo <pkg> framestats` → janky frames % | P95 场景 jank **< 1%** |
| 4 | 帧时间分布 | `dumpsys gfxinfo` histogram | 16.6 ms 内占比 |
| 5 | **每小时耗电** | `adb shell dumpsys batterystats --charged <pkg>` | mAh/h 对比原生壳 |
| 6 | CPU 占用 | `adb shell top -H -p <pid>`；SSE 空闲 vs 活跃 | 空闲态 CPU **< 3%** |
| 7 | 后台耗电 | 息屏 30 min 后测 batterystats | 有无 wakelock 泄漏 |
| 8 | 内存 | `adb shell dumpsys meminfo <pkg>`；WebView 峰值 | 无 OOM 抖动 |
| 9 | 冷启动 APK 体积 | `aapt dump badging` / APK 大小 | +32.6 MB 的实际影响 |
| 10 | 滚动流畅度 | 长时间滚动 + `gfxinfo` | 长会话（1000+ 消息）不劣化 |

### 3.6 ⭐ 四条目标的内在冲突与折中

**核心冲突（必须承认，无法同时满足）：**

| 目标 | 内在矛盾 |
| --- | --- |
| "**完全一致**" ⇒ 用它的前端 | 但桌面版有 **ghostty-web 1.38 MB 真实终端**、完整 desktop 快捷键/菜单，**手机端这些恰好是不该照搬的部分** |
| "**最低延迟**" ⇒ 原生输入路径 | 与"用它的前端"**直接对立**——DOM 输入永远多几跳 |
| "**最省电**" ⇒ 少跑 JS、少唤醒 | 与"长连 SSE + 实时流式渲染 + PWA 全量预缓存"**对立** |
| "**最一致**" ⇒ 照搬全部交互 | 但**桌面鼠标交互 ≠ 触屏交互**（hover tooltip、右键菜单、多列）——**照搬反而更不一致**（与用户手里这台的直觉不一致） |

**折中选项（按推荐度）：**

1. **⭐ 折中 A：内容区走 Web，输入条走原生**（推荐）
   - 会话时间线、设置、diff 全部 WebView（一致性最大化）
   - **底部输入条用本壳现有原生 Compose**（延迟最小化）
   - 代价：需要一条 `evaluateJavascript` 双向同步协议（草稿/附件/发送），**工程量中等**，但**把延迟最敏感的一处做到最优**
2. **折中 B：纯 Web + 输入路径专项优化**
   - 全部 WebView，但：输入框用最小 DOM 树（不上 markdown 渲染）、输入期间暂停时间线重排、`content-visibility:auto` 隔离长列表、IME 动画用 `visualViewport` 同步
   - 代价：改动集中在一个组件；风险是**治标**
3. **折中 C：分设备形态** —— pad 走纯 Web（触屏+键盘，接近桌面），手机走折中 A
   - 代价：两套路径要测两遍
4. **不推荐 D：硬追"最省电 + 最低延迟" ⇒ 放弃上游前端** —— 那就等于回到原生，等于否掉本轮方向

### 3.7 ⚠️ 迁移成本的真实估计（本轮最重要的风险）

本壳 **已经有一套跑通的 WebView 壳**（`README-webview-legacy.md` 描述得很完整：proxy 免 CORS、`MemoryRouter`、IME、back、system bars、PTY WS 隧道……）。**但它 pin 的是旧包**：

- `frontend/OPENCODE_APP_VERSION` → `name=@opencode-ai/app`、**`tag=v1.18.31`**、`commit=014614d…`
- 而 `_upstream/packages/app` → **`@opencode/app` v2.0.11**，HEAD `702a73d`

**19 个 patch 全部基于 v1 布局，v2 里目标目录已不存在**（实测）：

| patch 目标目录 | v2.0.11 中 |
| --- | --- |
| `packages/app/src/context/` | ❌ 不存在（→ `src/runtime/server/`） |
| `packages/app/src/pages/` | ❌ 不存在（→ `src/session/`、`src/home/`） |
| `packages/app/src/utils/` | ❌ 不存在（→ `src/runtime/`） |
| `packages/app/src/components/` | ❌ 不存在（→ `@opencode/session-ui`） |
| `packages/app/src/i18n/` | ❌ 不存在（→ `src/runtime/i18n/`） |

`git tag --list 'v1.18*'` 在 `_upstream` 里**无输出**，`git describe` 报 `No names found` —— **这份 checkout 没有 tag，无法直接 checkout v1.18.31 做 diff**，需要另取对应 npm 包或 commit。

**决策建议**：**停止"patch 上游"路线**。v2 提供了正规扩展点（`Platform` 接口 `src/runtime/platform/platform.tsx`、`router` 注入口 `app.tsx:105`、SSR/预载 `preloadRoute`），把 19 个 patch 里真正必要的（IME、back、proxy、WS、system bars、offline stub）**重写为 `Platform` 实现 + 壳侧代码**，其余上游行为随版本自动升级。**代价：一次性的重写工；收益：以后升级不用重打 19 个 patch。**

---

## 未核对项（本轮没查/没验）

1. **构建精确耗时**：原计划用 `Stopwatch` 打印，但该行没留在日志里（`BUILD_EXITCODE`/`ELAPSED_SEC` 未捕获到）。**已用 `dist` 文件 mtime 给出 ≈4 s 写出窗口 / 全程约 1 分钟量级**，属推算，非精确计时。
2. **产物是否真能在 WebView 跑起来**：本轮**只构建，未在设备/模拟器加载**。`/api` 全路径、CORS、SSE、WS、SW 注册**均未端到端验证**。
3. **`/api/info` 等具体端点清单**只抽查了 `event.ts:44`、`server.ts:17`、`config.ts:9`，未穷举。
4. **v1.18.31 与 v2.0.11 的行为差异**未做 diff（`_upstream` 无 tag，无法 checkout v1）。
5. **19 个 patch 逐个评估**未做——只验证了目标目录不存在（足以判定"全部打不上"），未逐个确认是否有部分 hunk 可复用。
6. **`#11 屏幕常亮` 在 legacy 壳里的实现**未确认（`MainActivity.kt` 未 grep 到 `KEEP_SCREEN_ON`）。
7. **APT 32.6 MB 对安装体积/首装的实际影响**未实测。
8. **未安装设备、未改生产代码、未动分支** —— 按本轮约束执行。
