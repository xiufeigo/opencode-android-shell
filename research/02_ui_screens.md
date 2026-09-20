# opencode v2 前端 UI 逐屏调研笔记

> **调研对象**：`github.com/anomalyco/opencode`，分支 **`dev`**（抓取时间见各节 URL）。
> **交叉参考**：本工作区 vendored 构建产物 `app/src/main/assets/opencode-app/`（`index.html` + `assets/index-CKBO9Mty.js` + `assets/index-D_rl_FNw.css`）。
> **i18n 提取物**：`research/_i18n/en.json` / `zh.json` / `zht.json`（由 `scripts/extract-i18n.mjs` 从 vendored chunk 抽取）。

---

## 🔴 0. 版本基线 —— **`research/01_frontend_architecture.md` 的标签有误，请先读本节**

`research/01_frontend_architecture.md` §0 称 vendored 产物是 **`v1.18.31`**，并据此认为应当"以 v2 分支源码为规格、以 vendored 产物为像素基线"。**本轮核查证明这个标签是错的**：vendored 产物就是我们本次调研的 **`dev` 分支 UI**。

### 证据：vendored bundle 含 dev 分支独有组件（已实测计数）

对 `app/src/main/assets/opencode-app/assets/index-CKBO9Mty.js`（2,766,233 B）做字符串计数：

| dev 分支独有标识 | 出现次数 | 说明 |
|---|---|---|
| `session-review-v2` | **46** | 新版 review 面板（`session-review-v2-tabs-bar` 等） |
| `file-tree-v2` | **10** | 新版文件树（虚拟滚动 + A/D/M 徽标） |
| `settings-v2` | **7** | 新版设置弹窗 |
| `command-palette-v2` | **3** | 新版命令面板 |
| `home-session-scroll-track` | **2** | Home 自绘滚动条轨道 |
| `home-session-row` | **1** | Home 会话行 |
| `home-session-group-header` | **1** | Home 分组吸顶头 |

并且 source-level 证据（bundle 中出现）：
- `NewHome` 的 controller 装配（`createHomeProjectsController` / `createHomeSessionsController` / `createHomeScrollController` / `createHomeSessionSearchController`）→ 对应 dev 的 `packages/app/src/pages/home.tsx`
- `ProjectAvatar` 的 `getProjectAvatarVariant(project.icon?.color)` + `getProjectAvatarSource(id, icon)` + `outline` 变体 → 对应 dev 的 `project-avatar-v2`
- **Ghostty 终端**：`import("./ghostty-web-BXAOpYJJ.js")` + `Ghostty.load()`，`terminal.toggle` keybind 默认 **`ctrl+``**，PTY URL 同时支持 v1/v2 两套路径，且已有 `_serializeBufferByScrollback` / `_serializeBufferByRange`（序列化 addon）

### 结论

| 项 | 事实 |
|---|---|
| vendored 产物对应的 UI | **= `dev` 分支**（本文档所描述的就是它） |
| workspace 里 `research/_raw/opencode-src/` 的源码快照 | **较旧**（无 `packages/app/src/pages/home/`、无 `settings-v2`、无 `pages/session/timeline/`） |
| ⇒ 复刻规格应以 | **本文档（02）为准**；01 的 §3/§4（路由与页面、顶层外壳）是基于旧 fast-snapshot，信息架构 **已过时** |
| ✅ 两者仍一致的部分 | **同一套 `--v2-*` token 体系**（01 §5 与本文 §0 不冲突）；技术栈（SolidJS + Tailwind + Kobalte/TanStack）也一致 |

> **给 Android 复刻的直接影响**：Home 页是 **两栏 grid（280px + 720px，max-w 1080px）**、设置是 **x-large 竖向 Tabs 弹窗**、右侧面板 tab 是 **Review/Context/文件**、命令面板键位是 **Ctrl+K**。这些都**不在** v1.18.31 里，但**都在 vendored 产物里**。

### 其余版本相关事实（本文档各处已引用）

> **Android 定制**：vendored 构建**已包含 Android 定制**，与上游 `dev` 分支源码不同。证据：
> - zh 文案 `settings.general.row.tabPlusCreatesSession.title` = **`「+」新建会话`**，描述 = `关闭时，标签栏「+」打开命令面板（Ctrl+P）。打开后与官方桌面版一致：点「+」新建会话。`
> - `dialog.server.add.corsHint` = `Enter the real backend URL (for example https://backend.example.com). This Android shell reverse-proxies API/SSE natively — you do not need --cors for the WebView origin.`
> - `dialog.server.add.errorCors` = `... The Android shell proxies API calls natively and does not require server CORS.`
> - `dialog.server.add.errorAuth` = `Server requires authentication. Enter username and password.`
> - JS bundle 中出现 **`data-android-webview`（8 次）** 与 `data-android-staged` → 壳会设标志位让应用走 Android 分支
>
> 凡本文标注 **[Android 定制]** 的条目，**上游 GitHub 源码里没有**，只能以 vendored bundle 为准。

---

## ⚠️ 重要：与常见预期不符的 7 个事实（**必须先读，避免做错**）

本次对 `dev` 分支逐文件核查后，以下 7 点**推翻了常见的想当然认知**。若按用户描述的"预期"去实现，会做错：

| # | 常见预期 | **源码事实** | 证据 |
|---|---|---|---|
| 1 | 工具卡有**左侧色条**（左 2dp 竖条） | **不存在**。`basic-tool.css` / `message-part.css` 中无任何 `border-left` / 竖条元素。颜色只出现在两处：task 卡的**图标色** `--task-agent-color`、错误卡的**图标色** `var(--card-accent)` | `basic-tool.css` 全文 |
| 2 | reasoning part 是 **"Thought" 折叠面板** | **不存在折叠容器**。`reasoning-part` 仅两层 DOM，直接渲染 markdown。全仓 i18n **无 "Thought" 字符串**；只有 `ui.sessionTurn.status.thinking` = **"Thinking"/"思考中"**（独立的 shimmer 行，用于运行中提示） | `message-part.tsx` L1763、`message-part.css`、i18n |
| 3 | file part / agent part / error part 各有组件 | `PART_MAPPING` **只注册 4 种**：`tool` / `compaction` / `text` / `reasoning`。**file / agent / error 均未注册**，assistant 消息里的 file/agent part **不渲染**；file/agent 只出现在 **user 消息高亮** 与 **composer** 中 | `message-part.tsx` `PART_MAPPING` |
| 4 | 底部/头部有 **Session / Changes / Context** 三 tab | 源码中**不存在这组三标签**。桌面端面板 tab 是 **`Review` | `Context` | 动态文件tab**；移动端只有 **`Session` / `Changes`** | `session-side-panel.tsx`（已核实） |
| 5 | context ring 就是"会话头部右侧的上下文环" | 它**不在 titlebar**，而在**消息时间线的吸顶标题行**（h-12=48px）内。绘制用 **SVG 圆 + stroke-dasharray/dashoffset**，`ProgressCircleV2` 默认 **size=14, strokeWidth=1.5, viewBox=14, r=6.25**，CSS `transform: rotate(-90deg)` | `progress-circle-v2.tsx`（已核实）、`message-timeline.tsx` |
| 6 | tab bar 的 **`+` 会打开菜单** | **不打开菜单**。直接 `openNewTab()`，tooltip = `New session`。菜单式的 `+` 只存在于**侧栏面板**（打开文件浏览器）和**终端面板**（新建终端） | `titlebar.tsx` |
| 7 | 终端用 **xterm.js** | 用 **Ghostty（libghostty wasm）**。`import("ghostty-web")` + `Ghostty.load()`，addon 仅 **FitAddon** 与自有 **SerializeAddon**；**无** xterm / web-links / webgl / unicode11 / search | `terminal.tsx`、vendored `ghostty-web-*.js`（1.38 MB） |

---

## 0. 设计系统（v2 tokens）

**源文件**：vendored `app/src/main/assets/opencode-app/assets/index-D_rl_FNw.css`（491,836 B）+ `app/src/main/assets/opencode-app/index.html` + `app/src/main/assets/opencode-app/oc-theme-preload.js`

### 0.0 ❗主题切换机制（**Android 实现必须照此**）

**只有 `[data-color-scheme]` 能切换 token**：
```
:root                              ← 原始色阶（147 个 --v2-* 声明，与主题无关）
:root                              ← 语义层（93 个声明）= light 默认值
[data-color-scheme="light"]        ← 92 个声明，首行 color-scheme:light
[data-color-scheme="dark"]         ← 92 个声明，首行 color-scheme:dark
```
- **`[data-theme=...]` 不切换 token**。`data-theme` 只被两类规则使用：`[data-theme="oc-2"] body:not([data-new-layout]){…}`（legacy 语法/markdown 覆盖）以及 `.dark …` / `[data-theme="dark"]` 下的 `.scroll-view__thumb`
- **CSS 中不存在 `.dark { }` token 块** → 用 `.dark` 类做主题切换是错的
- 仅 light 定义的 token：`--font-family-text`、`--v2-font-family-sans`

**页面背景 = `--v2-background-bg-deep`**（light `#fafafa` / dark `#080808`），同时设在 `<html>` 内联 style、`<body>`、`#root` 上。

**`index.html` 关键结构（vendored，1594 B）**：
```html
<html lang="en" style="background-color: var(--v2-background-bg-deep, #fafafa)">
<head>
  <meta name="viewport" content="width=device-width, initial-scale=1,
        interactive-widget=resizes-content, viewport-fit=cover" />
  <meta name="theme-color" content="#fafafa" />
  <meta name="mobile-web-app-capable" content="yes" />
  <meta name="apple-mobile-web-app-capable" content="yes" />
  <meta name="apple-mobile-web-app-status-bar-style" content="black-translucent" />
  <script id="oc-theme-preload-script" src="./oc-theme-preload.js"></script>
  <script type="module" crossorigin src="./assets/index-CKBO9Mty.js"></script>
  <link rel="stylesheet" crossorigin href="./assets/index-D_rl_FNw.css">
</head>
<body class="antialiased overscroll-none text-12-regular overflow-hidden bg-v2-background-bg-deep">
  <div id="root" class="flex flex-col h-dvh bg-v2-background-bg-deep p-px"></div>
</body>
```
- **挂载点 id = `root`**；`p-px` = `padding:1px` → **1px 发丝边框** 包住一个 `100dvh` 的 flex column
- viewport 有 `interactive-widget=resizes-content` + `viewport-fit=cover`；**没有** `user-scalable` / `maximum-scale`
- **没有任何内联 `<script>` 定义 config / base URL / 服务器 URL**（`window.__DSH_BOOT__` 这类注入全局 `未找到`）；也没有 `<link rel="preload">` / `modulepreload`（`未找到`）
- `oc-theme-preload.js` 行为（逐字）：`themeId = localStorage["opencode-theme-id"] || "oc-2"`；`scheme = localStorage["opencode-color-scheme"] || "system"`；`isDark = scheme==="dark" || (scheme==="system" && matchMedia("(prefers-color-scheme: dark)").matches)`；然后设 `documentElement.dataset.theme = themeId`、`documentElement.dataset.colorScheme = isDark ? "dark" : "light"`、`documentElement.style.backgroundColor = isDark ? "#080808" : "#fafafa"`，并改写 `meta[name=theme-color]`
- `site.webmanifest`：`theme_color #080808`、`background_color #080808`、`display standalone`

**滚动条是自绘的**（Android 需自实现）：
```css
.scroll-view__viewport { scrollbar-width:none; overflow-y:auto; flex:auto; min-height:0; outline:none }
.scroll-view__thumb    { position:absolute; top:0; inset-inline-end:0; width:12px; opacity:0;
                         transition:opacity .2s; cursor:default; user-select:none; pointer-events:auto }
.scroll-view__thumb:after { content:""; width:4px; border-radius:9999px; top:0; bottom:0; left:50%;
                            transform:translate(-50%); backdrop-filter:blur(4px);
                            background-color:var(--border-weak-base); transition:background-color .15s }
.scroll-view__thumb:hover:after, [data-dragging=true]:after { background-color:var(--border-strong-base) }
```
→ **命中区 12px / 视觉条 4px / 带 4px 模糊**；**全部 22 条 `::-webkit-scrollbar` 规则都是 `{display:none}`**，`scrollbar-width` 只用 `none` 一个值。

**响应式断点（实际发出的 `@media`）**：`sm` 640px、`md` 768px、`lg` 1024px、`xl` 1280px、`2xl` 1536px；另有 `(hover:none)`、`(hover:none) and (pointer:coarse)`、`(prefers-reduced-motion: reduce)`、`(display-mode:standalone)`、`(max-width:839px)`、`(max-width:639px)`、`(max-width:600px)`。
**容器查询只有 4 个**：`@container getting-started (min-width:17rem)`、`@container (min-width:32rem)`、`@container (max-width:64px)`、`.\[container-type\:inline-size\]`。**`cq` 单位只用 `100cqh`**。

**根字号**：`html` / `:root` / `body` **都没有声明 `font-size`** → **16px 浏览器默认**；`--spacing: .25rem` = **4px**。所有 Tailwind 尺寸写作 `calc(var(--spacing) * N)`。

### 0.0b Android 壳专属证据

- **`data-android-webview`** 属性在 JS bundle 中出现 **8 次** → **Android 壳会设一个标志位让应用读取**（行为分支）
- 另有 `data-android-staged` 属性
- vendored bundle 中确有 Android 定制文案（见文首引言）：`dialog.server.add.corsHint` / `.errorAuth` / `.errorCors`、`settings.general.row.tabPlusCreatesSession`

### 0.1 中性色阶（light / dark 各取一套；同一 token 在 light 与 dark 块中值不同）

| token | light | dark |
|---|---|---|
| `--v2-grey-50` | `#fff` | — |
| `--v2-grey-100` | `#fafafa` | — |
| `--v2-grey-200` | `#f2f2f2` | — |
| `--v2-grey-300` | `#eee` | — |
| `--v2-grey-400` | `#dbdbdb` | — |
| `--v2-grey-500` | `#aeaeae` | — |
| `--v2-grey-600` | `gray` | — |
| `--v2-grey-700` | `#5c5c5c` | — |
| `--v2-grey-800` | `#3a3a3a` | — |
| `--v2-grey-900` | `#2e2e2e` | — |
| `--v2-grey-1000` | `#242424` | — |
| `--v2-grey-1100` | `#161616` | — |
| `--v2-grey-1200` | `#080808` | — |

> CSS 中 grey 只有一套字面值（`#fafafa`→`#080808`），**light/dark 的差异通过语义层 token 的重新映射实现**（见下表），而非灰阶本身。

**彩阶 100→1200（各 12 阶，逐字）**

| step | blue | green | red | yellow | orange | pink | purple | cyan |
|---|---|---|---|---|---|---|---|---|
|100|`#ecf1fe`|`#e7f9ea`|`#fceceb`|`#fefaec`|`#fdf2ed`|`#fdecf3`|`#ebecfe`|`#e2f7fb`|
|200|`#d7e2fc`|`#d0f0d5`|`#f6d5d3`|`#fcefd0`|`#ffe7dc`|`#f7d5e4`|`#d5d5fc`|`#c4edf4`|
|300|`#c3d4fd`|`#b8e9c1`|`#f2bbb7`|`#f7e5b5`|`#ffd8c6`|`#fabcd8`|`#b9b8f5`|`#a3e4ef`|
|400|`#a2bcff`|`#96e3a6`|`#f29b96`|`#f3da9b`|`#ffc1a4`|`#f799c6`|`#9e99f7`|`#65d9eb`|
|500|`#7698fd`|`#6bd586`|`#f17471`|`#f2cf76`|`#ffa478`|`#f26cb2`|`#8271f8`|`#00c5df`|
|600|`#3b5cf6`|`#49c970`|`#f1484f`|`#f6c251`|`#ff8648`|`#f64aab`|`#7152f4`|`#00abcf`|
|700|`#3250df`|`#2eaf5a`|`#d92e3c`|`#e7af36`|`#ee7330`|`#e4429e`|`#623be2`|`#0096b8`|
|800|`#2c47c8`|`#198b43`|`#b82d35`|`#cb9f34`|`#d16427`|`#c83d8b`|`#5230c2`|`#007d9b`|
|900|`#263fa9`|`#1d783c`|`#97252b`|`#ac8833`|`#b35624`|`#aa3576`|`#442aa1`|`#006c85`|
|1000|`#22388f`|`#196130`|`#7a1f23`|`#8e7231`|`#954c27`|`#8c2d61`|`#361f83`|`#005a6e`|
|1100|`#1c2e70`|`#164c26`|`#5f1a1c`|`#68552b`|`#723d22`|`#6f284f`|`#2b1b6a`|`#004756`|
|1200|`#1b2852`|`#14361d`|`#461516`|`#4b4025`|`#5a2c14`|`#5c1d3f`|`#221358`|`#00353f`|

**alpha 阶**（`--v2-alpha-dark-*` = 黑 + alpha，`--v2-alpha-light-*` = 白 + alpha）：
`100: #000 / #fff` · `90: #000000e5 / #ffffffe5` · `80: #000c / #fffc` · `70: #000000b2 / #ffffffb2` · `60: #0009 / #fff9` · `50: #00000080 / #ffffff80` · `40: #0006 / #fff6` · `30: #0000004d / #ffffff4d` · `24: #0000003d / #ffffff3d` · `20: #0003 / #fff3` · `16: #00000029 / #ffffff29` · `14: #00000024 / #ffffff24` · `12: #0000001f / #ffffff1f` · `10: #0000001a / #ffffff1a` · `8: #00000014 / #ffffff14` · `6: #0000000f / #ffffff0f` · `4: #0000000a / #ffffff0a` · `2: #00000005 / #ffffff05` · `0: #0000 / #fff0`

### 0.1b 字号 / 字重 / 行高 token

```
--v2-font-family-sans: "Inter", sans-serif        （仅 light 块；:root 同值）
--font-family-text:    "Inter", sans-serif        （仅 light 块）
--font-family-sans:    ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif
--font-family-mono:    ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace
--font-size-small: 13px | base: 14px | large: 16px | x-large: 20px
--font-weight-regular: 400 | medium: 500
--line-height-normal: 130% | large: 150% | x-large: 180% | 2x-large: 200%
--letter-spacing-normal: 0 | tight: -.16 | tightest: -.32
--paragraph-spacing-base: 0
```
复合类（legacy 组件用）：
```css
.text-12-regular { font-family:var(--font-family-sans); font-size:var(--font-size-small);
                   font-style:normal; font-weight:var(--font-weight-regular);
                   line-height:var(--line-height-large); letter-spacing:var(--letter-spacing-normal) }
.text-14-regular { …font-size:var(--font-size-base); …line-height:var(--line-height-x-large) }
/* 另有 .text-12-medium / -mono、.text-14-medium / -mono、.text-16-medium、.text-20-medium */
```
**`<body>` = `text-12-regular` → 13px / 150% / 400**（这就是"默认正文"）
`html,:host` 全局设 `line-height: var(--line-height-large)`、`tab-size: 4`、`-webkit-tap-highlight-color: transparent`（无 `font-size`）。

**圆角 token**：`--radius-xs .125rem`(2) | `sm .25rem`(4) | `md .375rem`(**6**) | `lg .5rem`(8) | `xl .625rem`(**10**)
→ 注意 **`rounded-xl` = 10px，不是 12px**。

**尺寸换算速查**：`h-7`=28 `h-8`=32 `h-9`=36 `h-10`=40 `h-11`=44 `h-12`=48；`size-3`=12 `size-3.5`=14 `size-4`=16 `size-5`=20 `size-6`=24 `size-7`=28 `size-8`=32；`gap-1`=4 `gap-1.5`=6 `gap-2`=8 `gap-3`=12 `gap-4`=16 `gap-8`=32 `gap-px`=1；`leading-4/5/6`=16/20/24。

### 0.2 语义层 token（**这是实现时真正要用的**）

| 语义 token | light | dark |
|---|---|---|
| `--v2-background-bg-base` | `--v2-grey-50` `#fff` | `--v2-grey-1100` `#161616` |
| `--v2-background-bg-deep` | `--v2-grey-100` `#fafafa` | `--v2-grey-1200` `#080808` |
| `--v2-background-bg-layer-01` | `--v2-grey-100` `#fafafa` | `--v2-grey-1000` `#242424` |
| `--v2-background-bg-layer-02` | `--v2-grey-200` `#f2f2f2` | `--v2-grey-900` `#2e2e2e` |
| `--v2-background-bg-layer-03` | `--v2-grey-300` `#eee` | `--v2-grey-800` `#3a3a3a` |
| `--v2-background-bg-layer-04` | `--v2-grey-400` `#dbdbdb` | `--v2-grey-700` `#5c5c5c` |
| `--v2-background-bg-contrast` | `--v2-grey-700` `#5c5c5c` | `--v2-grey-1000` `#242424` |
| `--v2-background-bg-inverse` | `--v2-grey-1100` `#161616` | `--v2-grey-50` `#fff` |
| `--v2-background-bg-accent` | `--v2-blue-600` `#3b5cf6` | 同 |
| `--v2-text-text-base` | `--v2-grey-1100` `#161616` | `--v2-grey-100` `#fafafa` |
| `--v2-text-text-muted` | `--v2-grey-700` `#5c5c5c` | `--v2-grey-500` `#aeaeae` |
| `--v2-text-text-faint` | `--v2-grey-600` `gray` | 同 |
| `--v2-text-text-inverse` | `--v2-grey-50` | `--v2-grey-1100` |
| `--v2-text-text-accent` | `--v2-blue-600` `#3b5cf6` | `--v2-blue-400` `#a2bcff` |
| `--v2-text-text-accent-hover` | `--v2-blue-700` `#3250df` | `--v2-blue-300` `#c3d4fd` |
| `--v2-text-text-code-accent` | `--v2-blue-900` `#263fa9` | `--v2-blue-400` `#a2bcff` |
| `--v2-icon-icon-base` | `--v2-grey-800` `#3a3a3a` | `--v2-grey-400` `#dbdbdb` |
| `--v2-icon-icon-muted` | `--v2-grey-600` `gray` | 同 |
| `--v2-icon-icon-accent` | `--v2-blue-600` | `--v2-blue-400` |
| `--v2-icon-icon-contrast` | `--v2-grey-100` | `--v2-grey-100` |
| `--v2-border-border-base` | `--v2-alpha-dark-10` `#0000001a` | `--v2-alpha-light-10` `#ffffff1a` |
| `--v2-border-border-muted` | `--v2-alpha-dark-8` `#00000014` | `--v2-alpha-light-8` `#ffffff14` |
| `--v2-border-border-strong` | `--v2-alpha-dark-20` `#0003` | `--v2-alpha-light-20` `#fff3` |
| `--v2-border-border-focus` | `--v2-blue-500` `#7698fd` | 同 |
| `--v2-overlay-simple-overlay-hover` | `--v2-alpha-dark-4` `#0000000a` | `--v2-alpha-light-6` `#ffffff0f` |
| `--v2-overlay-simple-overlay-pressed` | `--v2-alpha-dark-8` `#00000014` | `--v2-alpha-light-10` `#ffffff1a` |
| `--v2-overlay-simple-overlay-scrim` | `--v2-alpha-dark-40` `#0006` | `--v2-alpha-dark-60` `#0009` |

**alpha 阶**：`--v2-alpha-light-N` ∈ {0,2,4,6,8,10,12,14,16,20,24,30,40,50,60,70,80,90,100}；dark 对应同名单调黑。**这正是 v2 所有边框/遮罩的基础**——Android 用 `Color.WHITE`/`BLACK` + 百分比 alpha 直接映射。

### 0.3 状态色（`--v2-state-*`）

| token | light | dark |
|---|---|---|
| `--v2-state-bg-danger` | `--v2-red-100` `#fceceb` | `--v2-red-1200` `#461516` |
| `--v2-state-bg-success` | `--v2-green-100` `#e7f9ea` | `--v2-green-1200` `#14361d` |
| `--v2-state-bg-warning` | `--v2-yellow-100` `#fefaec` | `--v2-yellow-1200` `#4b4025` |
| `--v2-state-bg-info` | `--v2-blue-100` `#ecf1fe` | `--v2-blue-1200` `#1b2852` |
| `--v2-state-fg-danger` | `--v2-red-800` `#b82d35` | `--v2-red-500` `#f17471` |
| `--v2-state-fg-success` | `--v2-green-800` `#198b43` | `--v2-green-500` `#6bd586` |
| `--v2-state-fg-warning` | `--v2-yellow-800` `#cb9f34` | `--v2-yellow-500` `#f2cf76` |
| `--v2-state-fg-info` | `--v2-blue-800` `#2c47c8` | `--v2-blue-500` `#7698fd` |
| `--v2-state-border-danger` | `--v2-red-300` `#f2bbb7` | `--v2-red-900` `#97252b` |
| `--v2-state-border-success` | `--v2-green-300` `#b8e9c1` | `--v2-green-900` `#1d783c` |

### 0.4 Agent 色（工具卡左侧色条 / task 卡使用）

| token | light | dark |
|---|---|---|
| `--v2-agent-build-solid` | `--v2-blue-800` `#2c47c8` | `--v2-blue-300` `#c3d4fd` |
| `--v2-agent-build-border` | `#2c47c81a` | `#a2bcff33` |
| `--v2-agent-build-background` | `#263fa90d` | `#ecf1fe1a` |
| `--v2-agent-explore-solid` | `--v2-yellow-900` `#ac8833` | `--v2-yellow-300` `#f7e5b5` |
| `--v2-agent-explore-border` | `#cb9f3433` | `#f3da9b33` |
| `--v2-agent-explore-background` | `#ac88330d` | `#fefaec1a` |
| `--v2-agent-plan-solid` | `--v2-pink-800` `#c83d8b` | `--v2-pink-400` `#f799c6` |
| `--v2-agent-plan-border` | `#c83d8b33` | `#f799c633` |
| `--v2-agent-plan-background` | `#aa35760d` | `#fdecf31a` |
| `--v2-agent-review-solid` | `--v2-green-800` `#198b43` | `--v2-green-300` `#b8e9c1` |
| `--v2-agent-writer-solid` | `--v2-purple-700` `#623be2` | `--v2-purple-400` `#9e99f7` |

### 0.5 阴影（多层 box-shadow，逐字抄录）

```
--v2-elevation-raised   light: 0px 2px 4px 0px var(--v2-alpha-dark-4),0px 1px 2px -1px var(--v2-alpha-dark-8),0px 0px 0px .5px var(--v2-alpha-dark-12),0px 0px 0px 0px var(--v2-alpha-dark-0)
                        dark:  0px 2px 4px 0px var(--v2-alpha-dark-30),0px 1px 2px 0px var(--v2-alpha-dark-30),0px 0px 0px .5px var(--v2-alpha-light-16),0px -.5px 0px 0px var(--v2-alpha-light-6)
--v2-elevation-floating light: 0px 8px 16px 0px var(--v2-alpha-dark-4),0px 4px 8px 0px var(--v2-alpha-dark-8),0px 0px 0px .5px var(--v2-alpha-dark-12),0px 0px 0px 0px var(--v2-alpha-dark-0)
                        dark:  0px 8px 16px 0px var(--v2-alpha-dark-30),0px 4px 8px 0px var(--v2-alpha-dark-30),0px 0px 0px .5px var(--v2-alpha-light-16),0px -.5px 0px 0px var(--v2-alpha-light-6)
--v2-elevation-overlay  light: 0px 16px 32px 0px var(--v2-alpha-dark-4),0px 8px 16px 0px var(--v2-alpha-dark-8),0px 0px 0px .5px var(--v2-alpha-dark-12),0px 0px 0px 0px var(--v2-alpha-dark-0)
                        dark:  0px 16px 32px 0px var(--v2-alpha-dark-30),0px 8px 16px 0px var(--v2-alpha-dark-30),0px 0px 0px .5px var(--v2-alpha-light-16),0px -.5px 0px 0px var(--v2-alpha-light-6)
--v2-elevation-button-contrast light: 0px 1px 1.5px 0px var(--v2-alpha-dark-10),0px 0px 0px .5px var(--v2-alpha-dark-14)
                                 dark:  0px 1px 2px 0px var(--v2-alpha-dark-40),0px 0px 0px .5px var(--v2-alpha-light-40),0px -.5px 0px 0px var(--v2-alpha-light-30)
--v2-elevation-button-neutral  light: 0px 1px 1.5px 0px var(--v2-alpha-dark-10),0px 0px 0px .5px var(--v2-alpha-dark-14)
                                dark:  0px 1px 2px 0px var(--v2-alpha-dark-40),0px 0px 0px .5px var(--v2-alpha-light-20),0px -.5px 0px 0px var(--v2-alpha-light-10)
--v2-elevation-elements        dark:  0px .5px .5px 0px var(--v2-alpha-dark-40)
--v2-elevation-switch-on/off   inset 系列（见 CSS）
```

### 0.6 字体与字号（v2 语言）

- `--v2-font-family-sans: "Inter", sans-serif`（v2 组件统一用 v2 字体栈；旧组件用 `--font-family-sans`）
- v2 前端的字号**不用 Tailwind 语义类，几乎全是字面 `text-[13px]` / `text-[14px]`**，并统一带 `tracking-[-0.04px]`：
  - 正文/标题：`text-[13px] leading-4 tracking-[-0.04px]`（13px / 16px / -0.04px）
  - 行内长文：`text-[13px] leading-5`（13/20）
  - 工具卡：`font-size: 14px`（basic-tool.css 直写）
  - 字重非常规：`[font-weight:530]`（强调）、`[font-weight:440]`（正文）、`[font-weight:430]`、`var(--font-weight-medium)` / `var(--font-weight-regular)`（旧组件）
  - **注意**：530 / 440 是 Inter 可变字体轴值，Android 需映射到 `FontWeight` 500 / 400 ~ 450。
- 代码/终端用 `font-mono!`

---

## 1. Home 首页

**源文件**
- [`packages/app/src/pages/home.tsx`](https://github.com/anomalyco/opencode/blob/dev/packages/app/src/pages/home.tsx)（`NewHome`，2124 B）
- [`packages/app/src/pages/home/home-projects.tsx`](https://github.com/anomalyco/opencode/blob/dev/packages/app/src/pages/home/home-projects.tsx)
- [`packages/app/src/pages/home/home-projects-view.tsx`](https://github.com/anomalyco/opencode/blob/dev/packages/app/src/pages/home/home-projects-view.tsx)
- [`packages/app/src/pages/home/home-sessions.tsx`](https://github.com/anomalyco/opencode/blob/dev/packages/app/src/pages/home/home-sessions.tsx)
- [`packages/app/src/pages/home/home-sessions-view.tsx`](https://github.com/anomalyco/opencode/blob/dev/packages/app/src/pages/home/home-sessions-view.tsx)

### 1.1 整体布局（`home.tsx`）

```
div.m-2.min-h-0.flex-1.self-stretch.overflow-hidden.rounded-[10px].bg-v2-background-bg-base.shadow-[var(--v2-elevation-raised)]   ← 整页"一张卡"
└─ ScrollView.h-full.[container-type:size]                    ← 自定义滚动，带 thumbContainer/thumbHoverTarget
   └─ div.mx-auto.grid.min-h-full.w-full.max-w-[1080px]
        .grid-rows-[auto_minmax(0,1fr)_auto].gap-4.px-3
        .lg:grid-cols-[280px_minmax(0,720px)].lg:grid-rows-1.lg:gap-8.lg:px-6
      ├─ <HomeProjects/>            ← 左栏（项目/服务器）
      ├─ <HomeSessions/>            ← 右栏（最近会话）
      └─ <HomeUtilityNav class="flex lg:hidden"/>   ← 移动端底部设置/帮助
```

**关键尺寸**
- 外层卡：`margin: 8px`，`border-radius: 10px`，背景 `bg-base`，阴影 `elevation-raised`
- 内容区：`max-width: 1080px` 居中；移动端**单列**（`grid-rows-[auto_minmax(0,1fr)_auto]`，间距 16px，左右 padding 12px）
- ≥1024px（`lg:`）：**两列** `280px` + `minmax(0,720px)`，列间距 32px，左右 padding 24px
- **响应式结论**：手机端必须走单列 —— 项目区在上、会话区在中、`HomeUtilityNav` 固定在底部（`flex lg:hidden`）

### 1.2 左栏：项目 / 服务器列表（`home-projects-view.tsx`）

```
aside.mt-6.flex.min-h-0.min-w-0.flex-col.gap-4.overflow-hidden
     .lg:sticky.lg:top-14.lg:mt-14.lg:h-[calc(100cqh-56px)].lg:self-start.lg:pt-[52px]
     aria-label="项目"            ← t("home.projects") = "Projects" / "项目"
├─ div.flex.h-7.items-center.justify-between.pl-1.5.pr-3        ← 分组标题行，高 28px
│   ├─ div.text-v2-text-text-muted.[font-weight:530]            ← "Projects"（13px muted 530）
│   └─ (仅当 servers.length === 1) TooltipV2 + IconButtonV2
│         data-action="home-add-project" variant="ghost-muted" size="large"
│         icon=<IconV2 name="folder-add-left"/>                 ← 「添加项目」图标按钮
├─ ScrollView[data-slot="home-projects-scroll"].min-h-0.shrink
│   └─ div.pr-3
│       ├─ (servers.length > 1) For each server → HomeServerRow + 分隔线 + HomeProjectList
│       └─ fallback（单服务器）
│           ├─ (有项目) HomeProjectList
│           └─ (无项目) HomeProjectEmpty
└─ HomeUtilityNav.hidden.lg:flex.mb-8.mt-4                       ← 桌面端才显示
```

**`HomeProjectNavButton`（项目/服务器行的通用按钮）** — 逐字 class：
```
flex h-7 min-w-0 w-full shrink-0 cursor-default items-center gap-2 rounded-[6px] bg-transparent px-1.5
text-left text-v2-text-text-muted [font-weight:440]
transition-[background-color,color,box-shadow] duration-[120ms] ease-in-out
hover:bg-v2-background-bg-layer-01 hover:text-v2-text-text-base
data-[selected]:bg-v2-background-bg-layer-03 data-[selected]:text-v2-text-text-base
data-[selected]:hover:bg-v2-background-bg-layer-03
focus-visible:bg-v2-background-bg-layer-01 focus-visible:text-v2-text-text-base focus-visible:outline-none
focus-visible:[box-shadow:inset_0_0_0_0.5px_var(--v2-border-border-muted)]
```
→ **数值**：行高 **28px**，圆角 **6px**，左右 padding **6px**，图标-文字间距 **8px**，过渡 **120ms ease-in-out**；选中态 `bg-layer-03`，hover 态 `bg-layer-01`。

**`HomeProjectRow`（项目行）**
- 外层 `div.group/project.relative.flex.h-7.items-center.rounded-[6px]`
- 行内按钮 `data-component="home-project-row"` + `class="pr-16"`（右侧预留 64px 给 hover 操作区）
- 左侧 `<ProjectAvatar fallback={名称} src={...} variant={...}/>`，中间 `span.min-w-0.flex-1.overflow-hidden.text-ellipsis.whitespace-nowrap`（常量 `HOME_PROJECT_NAV_LABEL`）
- **右侧 hover 操作区**（`.hover-reveal .absolute .right-1 .top-1/2 .-translate-y-1/2 .flex .items-center .gap-1`，`opacity:0` → `group-hover/project:opacity-100`）：
  1. `MenuV2`（`gutter={6}` `modal={false}` `placement="bottom-end"`）触发 `IconButtonV2 icon="outline-dots"` → 菜单项：**新建会话** / 编辑项目 / 在文件管理器中显示 / 清除通知 / ── 分隔 ── / **关闭**
  2. `IconButtonV2 data-action="home-project-new-session" icon="edit"` → 直接新建会话
- **右键**（`onContextMenu`）直接把该行的 menu 置为 open
- **拖拽排序**：`@dnd-kit/solid`，`RestrictToVerticalAxis` + `RestrictToElement`；**触摸需长按 250ms（tolerance 5）**，鼠标位移 **4px** 激活；`preventActivation` 排除 `[data-action]` 元素
- **选择语义（重要交互细节）**：同服务器下按 `pointerdown` 即选中；`click` 时若已选中则**取消选中**（toggle）；跨服务器必须等 `click`（避免拖拽时误切服务器）；**触摸点击不参与 pointerdown 选择**（防误触）

**空状态 `HomeProjectEmpty`**（**这就是「空状态长什么样」的答案**）
```
div.flex.min-w-0.flex-col.gap-1
├─ HomeProjectNavButton data-action="home-add-project-row"      ← 首行就是「添加项目」按钮
│    <IconV2 name="folder-add-left" size="small"/> + 「添加项目」
└─ (recentlyClosed.length > 0)
     ├─ div.mt-3.flex.h-7.items-center.pl-1.5.pr-3
     │    └─ div.text-v2-text-text-faint.[font-weight:530]  ← "Recently closed" / "最近关闭"
     └─ For each → HomeRecentlyClosedRow
```
- 「最近关闭」行：`data-component="home-recently-closed-row"`，`ProjectAvatar variant="outline"`（描边款），文字为 `displayName(project)`；`TooltipV2 placement="right"` 显示 `~` 缩写后的完整路径
- 点击 → `onAddProjects(server, [worktree])` **重新加入项目列表**

### 1.3 右栏：最近会话（`home-sessions-view.tsx`）

```
section.min-h-0.min-w-0.flex-1.flex.flex-col  aria-label="最近会话"（t("sidebar.project.recentSessions")）
├─ div.sticky.top-0.z-30.shrink-0.bg-v2-background-bg-base.pb-3.pt-6.lg:pt-12
│   ├─ HomeSessionSearch（见下）
│   └─ Suspense → (有分组 && 可创建) ButtonV2
│        data-action="home-new-session" variant="ghost-muted" size="normal" icon="edit"
│        class="pointer-events-auto h-7 px-2 [font-weight:530]"
│        绝对定位：absolute right-0 top-[84px] lg:top-[108px]  ← 「新建会话」悬浮按钮
├─ div.pointer-events-none.sticky.top-[84px].z-40.h-0.-mr-3.lg:top-[108px]
│   └─ div[data-component="home-session-scroll-track"]
│        .relative.ml-auto.h-[calc(100cqh-84px)].w-3.lg:h-[calc(100cqh-108px)]   ← 自定义滚动条轨道（宽 12px）
└─ div.-mr-3.min-h-[calc(100cqh-72px)].lg:min-h-[calc(100cqh-96px)]
    └─ Suspense(fallback=HomeSessionSkeleton) → 分组列表 / HomeSessionsEmpty
        └─ div.flex.flex-col.pt-3.pr-3.pb-16
            For each group:
              HomeSessionGroupHeader（sticky top-[84px] / lg:top-[108px]，高 h-7）
              div.flex.flex-col.gap-px.pt-4 (+ mb-6 非最后一组)
                For each → HomeSessionRow
```

**`HomeSessionSearch`（搜索框）**
- 关闭态：`label.relative.z-20.flex.h-9.w-full.items-center.gap-2.rounded-[6px].py-1.pl-3.pr-2.bg-v2-background-bg-layer-02/60.text-v2-icon-icon-muted.transition-[background-color,box-shadow].duration-[120ms]`
  → **高度 36px，圆角 6px，左 padding 12px 右 8px，gap 8px**，背景为 `layer-02` 的 **60% 透明**
- 左侧 `IconV2 name="magnifying-glass"`；`input` 为无边框透明，`placeholder:text-v2-text-text-faint`
- `placeholder` = `t("home.sessions.search.placeholder")` = **"Search sessions" / "搜索会话"**（有 scope 时用 `home.sessions.search.placeholder.scoped` = "Search sessions in {{scope}}"）
- 有输入时右侧出现 `IconButtonV2 icon="close"` 清除按钮
- **键盘**：`ArrowDown/ArrowUp` 移动选中（若按 Alt/Meta 则让位）；`Enter`（非 IME）选中；`Escape` 关闭并 blur；有 `aria-expanded` / `aria-controls` / `aria-activedescendant`
- 打开态面板 `data-component="home-session-search-panel"`：
  `absolute.flex.flex-col.overflow-hidden.rounded-[12px].bg-v2-background-bg-base.shadow-[var(--v2-elevation-floating)]`
  内联样式 `top:-6px; left:-6px; width:calc(100% + 12px)`（**比输入框四周各外扩 6px**）
  - 结果区 `role="listbox"`，`gap-4 pt-4`
  - 标题 `<p>` = `t("home.sessions.search.sessions")` = **"Sessions" / "会话"**，样式 `my-1.5 pl-[18px] pr-6 text-[13px] leading-4 tracking-[-0.04px] text-v2-text-text-muted [font-weight:440]`
  - 结果列表 `ScrollView.max-h-80`（**最大高 320px**）→ `div.flex.flex-col.gap-px.pb-2`
  - 加载中：居中 `Spinner.size-4`
  - 无结果：`t("home.sessions.search.noResults")` = "No sessions found for {{query}}" / `未找到与 {{query}} 相关的会话`
- 结果行 `data-component="home-session-search-row"` `role="option"`：
  `flex h-10 w-full items-center gap-2 border-0 py-3 pl-[18px] pr-6 text-left` → **高 40px**，左 padding 18px，右 24px；hover/选中 `bg-v2-overlay-simple-overlay-hover`

**`HomeSessionRow`（会话行）**
```
div.group/session.relative.flex.h-10.items-center.rounded-[6px]        ← 高 40px
├─ button[data-component="home-session-row"]
│    flex h-10 w-full flex-1 items-center gap-2 rounded-[6px] border-0 bg-transparent
│    py-3 pl-3 pr-10 text-left text-v2-text-text-muted [font-weight:530]
│    transition-[background-color,color,box-shadow] duration-[120ms] ease-in-out
│    hover:bg-v2-overlay-simple-overlay-hover focus-visible:...          ← 左右 padding 12px / 40px
│  ├─ <HomeSessionLeading>
│  │    div.relative.shrink-0
│  │    ├─ (若该会话已打开为 tab) span 绝对定位右侧 = "calc(100% + 4px)"，
│  │    │    h-3 w-0.5 -translate-y-1/2 rounded-[2px] bg-v2-background-bg-layer-04   ← 打开的标记竖条 12×2px
│  │    └─ <SessionTabAvatarView project unread loading revealProjectOnHover/>
│  ├─ <HomeSessionTitle>  span.min-w-0.overflow-hidden.text-ellipsis.whitespace-nowrap.text-v2-text-text-base.[font-weight:530]
│  │    有项目名时 max-w-[min(70%,480px)] flex-[0_1_auto]，否则 flex-[1_1_auto]
│  └─ (showProjectName) <HomeSessionProjectName> span text-v2-text-text-muted [font-weight:440]
└─ (SHOW_HOME_SESSION_ARCHIVE === false)  ← 归档按钮开关常量被硬编码为 false
```
- **打开行为**：`onClick` → `onOpenSession(session, { background })`
- `isBackgroundOpen(event)`：中键，或 macOS 上 Cmd+click / 其它平台 Ctrl+click（`shouldOpenSessionInBackground`，见 `../home-session-open`）→ **后台开 tab 不导航**
- 中键 `mousedown` 会 `preventDefault` 抑制自动滚动

**分组头 `HomeSessionGroupHeader`**
`pointer-events-none.sticky.top-[84px].flex.h-7.items-center.justify-between.bg-v2-background-bg-base.pl-3.lg:top-[108px]`
- 首个分组加 `home-session-group-header z-[5]`，其余 `z-10`
- 文字用 `HOME_SECTION_LABEL = "text-v2-text-text-muted [font-weight:440]"`，并带 `style={{opacity: titleOpacity}}`（滚动渐隐，由 `home-scroll-controller` 驱动）
- 分组标题文案：`home.sessions.group.today` = **"Today"/"今天"**、`home.sessions.group.yesterday` = **"Yesterday"/"昨天"**、`home.sessions.group.older` = **"Older"/"更早"**

**右栏空状态 `HomeSessionsEmpty`（重要）**
```
div.flex.min-h-full.flex-col.items-center.gap-4.px-6.pt-[52px].text-center
├─ div.shrink-0.text-[13px].leading-[13px].tracking-[-0.04px].text-v2-text-text-base.[font-weight:530]
│     = t("home.sessions.empty")            → "Nothing here yet" / "这里还没有内容"
├─ p.mb-1.text-[13px].leading-5.tracking-[-0.04px].text-v2-text-text-muted.[font-weight:440]
│     = t("home.sessions.empty.description") → "Create a session to get started" / "创建一个会话以开始。"
└─ ButtonV2 data-action="home-new-session" variant="neutral" size="normal" icon="edit"
      = t("command.session.new")              → "New session" / "新建会话"
```
→ **首页空状态 = 居中「这里还没有内容」+ 一行说明 + 一个中性主按钮**，顶部留白 52px。

**骨架屏 `HomeSessionSkeleton`**：标题行 + 4 个 `div.h-10.rounded-[6px].bg-v2-background-bg-deep.opacity-70`

### 1.4 底部工具导航 `HomeUtilityNav`
```
div.[class].min-w-0.flex-col.gap-1.pr-3
├─ HomeProjectNavButton text-v2-text-text-faint  [data-slot=icon-svg]:text-v2-icon-icon-muted
│    <IconV2 name="settings-gear" size="small"/> + t("sidebar.settings")  → "Settings"/"设置"
└─ HomeProjectNavButton 同上
     <IconV2 name="help" size="small"/> + t("sidebar.help")              → "Help"/"帮助"
```
出现两次：`home-projects-view` 内 `hidden lg:flex`（桌面左栏底部），`home.tsx` 内 `flex lg:hidden`（移动端底部）。

---

## 2. Session 聊天会话页

**源文件**
- [`packages/app/src/pages/session/timeline/message-timeline.tsx`](https://github.com/anomalyco/opencode/blob/dev/packages/app/src/pages/session/timeline/message-timeline.tsx)（`MessageTimeline`）
- [`packages/app/src/pages/session/composer/session-composer-region.tsx`](https://github.com/anomalyco/opencode/blob/dev/packages/app/src/pages/session/composer/session-composer-region.tsx)
- [`packages/app/src/components/session-context-usage.tsx`](https://github.com/anomalyco/opencode/blob/dev/packages/app/src/components/session-context-usage.tsx)（context ring）
- [`packages/session-ui/src/components/session-turn.css`](https://github.com/anomalyco/opencode/blob/dev/packages/session-ui/src/components/session-turn.css)

### 2.1 消息时间线布局

**虚拟列表**（`@tanstack/solid-virtual`）关键参数 —— 直接决定 Android 行为：
| 参数 | 值 | 含义 |
|---|---|---|
| `estimateSize` | `() => 60`（`timelineFallbackItemSize`） | 未测量行的估算高 **60px** |
| `anchorTo` | `"end"` | 锚定底部 |
| `followOnAppend` | `true` | 追加内容自动跟随 |
| `scrollEndThreshold` | `80` | 距底 **80px** 内视为"在底部" |
| `scrollMargin` | `showHeader() ? 64 : 0` | 有标题时顶部让位 **64px** |
| `overscan` | `50` | 虚拟化预渲染 50 |
| `paddingEnd` | `64` | 底部内边距 **64px**（给 composer 让位） |
| 冷启动 overscan | `6`（`coldBottomMount`）否则 `20` | |

- 底部 spacer：`div[data-timeline-row="bottom-spacer"].h-16.absolute.top-0.left-0.w-full`，`translateY(totalSize - 64px)` → **`h-16` = 64px**
- 虚拟内容容器 `div[data-timeline-virtual-content]`：`height: totalSize`、`position: relative`、`width: 100%`
- 每行带 `data-timeline-key`（用于**前插锚点保持**：`applyPrependAnchor` 用 rAF 循环最多 180 帧 / 稳定 30 帧地校正 `scrollTop`，避免加载历史时跳动）
- 缓存的测量值与工具展开状态：`timelineCache`，最多 16 个会话

**`session-turn.css` 关键规则**（DOM 契约 = `data-slot`）
```css
[data-component="session-turn"]                       display:flex; align-items:flex-start; height:100%
  [data-slot="session-turn-content"]                  flex-grow:1; width:100%; overflow-y:auto; scrollbar-width:none
  [data-slot="session-turn-message-container"]        display:flex; flex-direction:column; align-items:flex-start; gap:0; overflow-anchor:none
  [data-slot="session-turn-message-content"]          margin-top:0; width:100%; max-width:100%
  [data-slot="session-turn-compaction"]               width:100%
  [data-slot="session-turn-thinking"]                 display:flex; align-items:center; gap:8px; margin-top:12px;
                                                      color:var(--text-weak); font-size:var(--font-size-base);
                                                      font-weight:medium; line-height:20px; min-height:20px
  [data-component="text-reveal"].session-turn-thinking-heading  flex:1 1 auto; color:var(--text-weaker); font-weight:regular
  .error-card                                         color:var(--v2-text-text-base); max-height:240px; white-space:pre-wrap;
                                                      overflow-wrap:anywhere; word-break:break-word; overflow-y:auto
  [data-slot="session-turn-assistant-content"]        flex-direction:column; align-self:stretch; gap:12px     ← 助理各 part 间距 12px
[data-slot="session-turn-list"]                       gap:24px                                              ← 轮次(turn)间距 24px
```
- **思考中行** = `TextShimmer`（文案 `ui.sessionTurn.status.thinking` = "Thinking"/"思考中"）+ `TextReveal` 显示 reasoning 小标题（`travel:25, duration:700`）
- `--sticky-header-height: calc(var(--session-title-height, 0px) + 24px)`

**TimelineDiffSummaryRow（轮次末尾的改动汇总）** — `data-slot` 结构：
```
div[data-slot="session-turn-diffs"][data-component="session-turn-diffs-group"][data-show-all?]
├─ div[data-slot="session-turn-diffs-header"]         ← position:sticky; top:var(--sticky-accordion-top); z-index:20;
│   │                                                   background:var(--v2-background-bg-base); height:44px;
│   │                                                   padding-top:4px; padding-bottom:12px; gap:8px
│   ├─ span[data-slot="session-turn-diffs-label"]     字体 tabular-nums, medium, color text-strong
│   │     = plural("ui.sessionTurn.diffs.changed", n) → "{{count}} 个文件已更改"
│   ├─ <DiffChanges changes={diffs}/>                  ← 增删行数标记
│   └─ (overflow>0) span[data-slot="session-turn-diffs-toggle"]
│         = "全部显示"/"收起"（默认 opacity:0，组 hover 或 data-show-all 时 opacity:1）
└─ div[data-component="session-turn-diffs-content"]
    └─ Accordion (multiple, --sticky-accordion-offset:44px)
        For each file（最多显示 `maxFiles = 10`）:
          div[data-slot="session-turn-diff-trigger"]     display:flex; justify-content:space-between; gap:12px
          ├─ span[data-slot="session-turn-diff-path"]     flex-grow:1; direction:ltr; unicode-bidi:isolate
          │   ├─ span[data-slot="session-turn-diff-directory"]  direction:rtl; text-align:left; ellipsis
          │   │     内容 = "\u202A" + getDirectory(file) + "\u202C"  ← 注意左右嵌入控制符
          │   └─ span[data-slot="session-turn-diff-filename"]   color text-strong; font-weight:medium
          └─ div[data-slot="session-turn-diff-meta"]      gap:10px
              ├─ span[data-slot="session-turn-diff-changes"]
              └─ span[data-slot="session-turn-diff-chevron"]  transform:rotate(-90deg); transition:.15s
                 展开时（[data-slot=accordion-item][data-expanded]）→ rotate(0deg)
    Accordion.Content → TimelineDiffView
       div[data-slot="session-turn-diff-view"][data-scrollable]  background:var(--surface-inset-base); overflow-y:auto
          <Dynamic component={fileComponent} mode="diff" virtualize={false} fileDiff={...}/>
```
- 折叠时按 `maxFiles = 10` 截断，溢出显示 `div[data-slot="session-turn-diffs-more"]` = `t("ui.sessionTurn.diffs.more", {count})` → **"+{{count}} 个文件"**
- 展开状态存于 `state.expanded: string[]`（按 `diff.file` 值）

### 2.2 会话头部（session header）+ context ring

**头部 DOM**（源自 message-timeline.tsx 的 sticky header 区）
```
div[class h-12 w-full flex items-center justify-between gap-2 …]     ← h-12 = 48px
├─ 左区 div.flex.items-center.gap-1.min-w-0.flex-1
│   ├─ (子会话) button[data-slot="session-title-parent"].min-w-0.max-w-[40%].truncate.pl-2
│   │            text-[13px].font-[530].leading-4.tracking-[-0.04px].text-v2-text-text-faint
│   └─ span[data-slot="session-title-separator"]  "/"，text-[11px].font-medium.text-v2-text-text-faint
│   └─ (子会话/编辑中) h1[data-slot="session-title-child"]
│        truncate text-[13px] font-[530] leading-4 tracking-[-0.04px] text-v2-text-text-base
│        新版布局额外：w-fit rounded-[6px] px-2 py-1 hover:bg-v2-overlay-simple-overlay-hover
│        （点击 → InlineInput 重命名；Enter 保存 / Esc 取消 / blur 保存）
└─ 右区 div.shrink-0.flex.items-center（新版 gap-2 = 8px，旧版 gap-3 = 12px）
    ├─ <SessionContextUsage placement="bottom" buttonAppearance={v2 ? "v2" : "default"}/>
    └─ (非子会话) MenuV2 触发 button IconButtonV2 icon=<IconV2 name="outline-dots"/>
         variant="ghost-muted" size="large" aria-label="更多选项"
         菜单项顺序：重命名 / 分享…（share 未禁用时）/ 导出… / 归档 / ──分隔── / 删除…
         新版菜单 Content 宽度固定 style={{width:"120px","min-width":"120px"}}
```

**Context ring（上下文环）** —— **源文件 `packages/app/src/components/session-context-usage.tsx` + `packages/ui/src/v2/components/progress-circle-v2.tsx`**

> **位置澄清**：它**不在 titlebar**（`SessionHeaderV2Actions` 里没有）。它渲染在**消息时间线的吸顶标题行**内，右侧。见下文「挂载位置」。

- **绘制方式：SVG 圆 + `stroke-dasharray` / `stroke-dashoffset`**（不是 conic-gradient，不是 canvas）
- **`ProgressCircleV2`（v2 外观，已核实源码）**：
  - 默认 `size = 14`、`strokeWidth = 1.5`；`viewBox = "0 0 14 14"`（`viewBoxSize = 14`）
  - `center = 7`，`radius = center - strokeWidth/2 = 6.25`，`circumference = 2πr ≈ 39.27`
  - `offset = circumference * (1 - clamp(percentage, 0, 100) / 100)`
  - 两个 `<circle>`：`data-slot="progress-circle-v2-background"` 与 `data-slot="progress-circle-v2-progress"`（后者带 `stroke-dasharray`/`stroke-dashoffset`）
  - 根元素 `data-component="progress-circle-v2"`，CSS：`display: block; transform: rotate(-90deg)`
  - CSS 颜色：background `stroke: var(--v2-background-bg-layer-04)`；progress `stroke: var(--v2-icon-icon-base)`
  - CSS 过渡：`transition: stroke-dashoffset 0.35s cubic-bezier(0.65, 0, 0.35, 1)`
- **旧外观 `ProgressCircle`（`buttonAppearance !== "v2"` 时）**：`size=16`、`strokeWidth=2`、`viewBox="0 0 16 16"`、`center=8`、`radius = 8 - 1 = 7`，三个 circle（多一个 `-background-overlay`）
- `variant="indicator"` 时通过内联 CSS 变量改色：
  `--progress-circle-background: var(--v2-background-bg-layer-04, var(--border-weak-base))`、
  `--progress-circle-background-overlay: var(--v2-overlay-simple-overlay-pressed, transparent)`、
  `--progress-circle-progress: var(--v2-icon-icon-base, var(--icon-base))`
- **阈值变色：`未找到`**。源码只有 `Math.max(0, Math.min(100, percentage))` 钳制，**没有任何 >80% 变红的逻辑或 CSS 分支**。→ 不要在 Android 里臆造阈值。
- **外层按钮**：
  - `buttonAppearance="v2"` → `IconButtonV2 variant="ghost-muted" size="large" icon={circleV2()}`
  - `buttonAppearance="default"` → `Button variant="ghost" class="size-6"`
  - `variant="indicator"` → 只渲染圆环本身（无按钮）
  - `aria-label = t("context.usage.view")` = **"View context usage"**
- **百分比来源**（`packages/app/src/components/session/session-context-metrics.ts`）：
  - 取**最后一条 `tokenTotal > 0`** 的 assistant message
  - `tokenTotal = tokens.input + output + reasoning + cache.read + cache.write`
  - `limit = provider.models[modelID].limit.context`
  - `usage = limit ? Math.round(total / limit * 100) : null`
- **点击行为 `openContext()`**（已在 8.2 交叉引用）：
  1. 无 `params.id` → return
  2. 已可见（`reviewPanel.opened() && activeTab() === "context"`）→ `tabs().close("context")`；若 `reviewPanel.source() === "context-button"` 且无其它 tab，则连面板一起关
  3. 否则打开 reviewPanel → 若文件树已开且 `tab() !== "all"` 则 `setTab("all")` → `tabs.open("context")` → `tabs.setActive("context")`
- **Tooltip**（`TooltipV2` hover 卡，**不是 popover 组件**）：`<div class="flex w-[120px] flex-col gap-2">`，3 行 `ContextTooltipRow`：
  | key | en 标签 | 值 |
  |---|---|---|
  | `context.usage.cost` | **"Cost"** | `Intl.NumberFormat(locale, {style:"currency", currency:"USD"}).format(info()?.cost ?? 0)` |
  | `context.usage.usage` | **"Usage"** | `` `${context()?.usage ?? 0}%` `` |
  | `context.usage.tokens` | **"Tokens"** | `context()?.total.toLocaleString(locale) ?? "0"` |
  - 每行：`div.flex.min-w-0.items-center.gap-4` → `span.shrink-0.text-v2-text-text-muted`（名）+ `span.ml-auto.min-w-0.truncate.text-right.text-v2-text-text-base`（值）
  - `placement` 由调用方传（时间线传 `"bottom"`），`shift={-8}`

**挂载位置（已核实，重要）**：`SessionContextUsage` 在 `message-timeline.tsx` 的吸顶标题行里：
```jsx
<div data-session-title
     classList={{ "sticky top-0 z-30": true,
                   "bg-[linear-gradient(to_bottom,var(--v2-background-bg-base)_48px,transparent)]": v2,
                   "w-full": true, "pb-4": true, "pr-3": true,
                   "pl-2.5": v2, "pl-2 md:pl-4": !v2,
                   "md:max-w-200 md:mx-auto 2xl:max-w-[1000px]": centered && !v2 }}>
  <div class="h-12 w-full flex items-center justify-between gap-2">     ← 48px
    …左侧标题…
    <div classList={{ "shrink-0 flex items-center": true, "gap-2": v2, "gap-3": !v2 }}>
      <SessionContextUsage placement="bottom" buttonAppearance={v2 ? "v2" : "default"} />
      <MenuV2>…outline-dots…</MenuV2>
```
- `showHeader() = !!(title || parentID)`；相关常量：`"--sticky-accordion-top": showHeader() ? "48px" : "0px"`、virtualizer `scrollMargin: showHeader() ? 64 : 0`、底部 spacer `h-16`（64px）

### 2.3 底部 composer 区（`session-composer-region.tsx`）

```
div[data-component="session-prompt-dock"]
    w-full shrink-0 flex flex-col justify-center items-center pb-3 pointer-events-none
    bg-v2-background-bg-base (新布局) / bg-background-stronger (旧布局)      ← 底部内边距 12px
└─ div.w-full.px-3.pointer-events-auto
     .md:max-w-200.md:mx-auto.2xl:max-w-[1000px]   ← centered 时：md(≥768px) 最大宽 800px，2xl 最大宽 1000px
   ├─ (questionRequest) <SessionQuestionDock/>
   ├─ (permissionRequest) <SessionPermissionDock/>
   └─ (showComposer)
       ├─ (dock) div.overflow-hidden + style max-height = dockHeight * dockProgress
       │    └─ <SessionTodoDock/>                      ← 待办列表，可折叠
       ├─ (promptReady=false) 占位：div.w-full.min-h-32.md:min-h-40.rounded-md.border.border-border-weak-base
       │     .bg-background-base/50.px-4.py-3.text-text-weak.whitespace-pre-wrap.pointer-events-none
       │     文案 = handoffPrompt() || t("prompt.loading")  → "正在加载提示..."
       └─ (promptReady=true)
           ├─ (有 revert) <SessionRevertDock/>   ← 被回滚消息
           ├─ div.relative.z-[70] style margin-top = -lift()
           │   ├─ (followup 有内容) <SessionFollowupDock/>   ← 排队消息
           │   └─ controller.child()
           │       ? div.w-full.rounded-[12px].border.border-border-weak-base.bg-background-base.p-3
           │           .text-16-regular.text-text-weak
           │           ="无法向子智能体会话发送提示词。" + [返回主会话。] 按钮
           │       : props.promptInput  ← 真正的输入框
```
- **`SessionTodoDock`** 可折叠：`collapseLabel = t("session.todo.collapse")`="折叠"、`expandLabel = t("session.todo.expand")`="展开"
- 排队消息摘要：`session.followupDock.summary` = plural `{{count}} 条排队消息`；按钮 `session.followupDock.sendNow`="立即发送"、`session.followupDock.edit`="编辑"
- 回滚摘要：`session.revertDock.summary` = plural `{{count}} 条已回滚消息`；`session.revertDock.restore`="恢复消息"

---

## 3. 消息 part 渲染

**源文件**
- [`packages/session-ui/src/components/basic-tool.tsx`](https://github.com/anomalyco/opencode/blob/dev/packages/session-ui/src/components/basic-tool.tsx) + [`basic-tool.css`](https://github.com/anomalyco/opencode/blob/dev/packages/session-ui/src/components/basic-tool.css)
- [`packages/session-ui/src/components/part-default-open.ts`](https://github.com/anomalyco/opencode/blob/dev/packages/session-ui/src/components/part-default-open.ts)
- [`packages/session-ui/src/components/tool-error-card.tsx`](https://github.com/anomalyco/opencode/blob/dev/packages/session-ui/src/components/tool-error-card.tsx) + [`tool-error-card.css`](https://github.com/anomalyco/opencode/blob/dev/packages/session-ui/src/components/tool-error-card.css)
- [`packages/session-ui/src/components/shell-submessage.css`](https://github.com/anomalyco/opencode/blob/dev/packages/session-ui/src/components/shell-submessage.css)
- [`packages/session-ui/src/components/file.css`](https://github.com/anomalyco/opencode/blob/dev/packages/session-ui/src/components/file.css)

### 3.1 工具卡 `[data-component="tool-trigger"]`

**完整 DOM 层级（已核实）**
```
div[data-component="tool-part-wrapper"][data-timeline-part-id]
└─ Collapsible.tool-collapsible
   ├─ Collapsible.Trigger
   │  └─ div[data-component="tool-trigger"][data-clickable][data-hide-details]
   │     └─ div[data-slot="basic-tool-tool-trigger-content"]
   │        └─ div[data-slot="basic-tool-tool-info"]         font-size:14px
   │           └─ (结构化时) div[data-slot="basic-tool-tool-info-structured"]
   │              └─ div[data-slot="basic-tool-tool-info-main"]   display:flex;align-items:baseline;gap:8px
   │                 ├─ span[data-slot="basic-tool-tool-title"]
   │                 ├─ span[data-slot="basic-tool-tool-subtitle"]
   │                 └─ span[data-slot="basic-tool-tool-arg"]
   │     └─ span[data-slot="basic-tool-tool-action"]          右侧动作槽
   └─ Collapsible.Arrow                                       展开 chevron
```

```css
[data-component="tool-trigger"]                       width:100%; display:flex; align-items:center; gap:0;
                                                      justify-content:flex-start; content-visibility:auto
  &[data-clickable="true"]                            cursor:pointer
  [data-slot="basic-tool-tool-trigger-content"]       flex:0 1 auto; max-width:calc(100% - 24px);
                                                      display:flex; align-items:center; gap:8px
  [data-slot="basic-tool-tool-indicator"]             width:16px; height:16px; inline-flex; center
  [data-slot="basic-tool-tool-spinner"]               width:16px; height:16px; color:var(--v2-text-text-faint)
  [data-slot="basic-tool-tool-info"]                  font-size:14px
  [data-slot="basic-tool-tool-info-structured"]       display:inline-flex; align-items:center; gap:8px
  [data-slot="basic-tool-tool-info-main"]             display:flex; align-items:baseline; gap:8px; overflow:hidden
  [data-slot="basic-tool-tool-title"]                 font-size:14px; font-weight:var(--font-weight-medium);
                                                      line-height:var(--line-height-large); color:var(--v2-text-text-base)
                                                      &.capitalize { text-transform:capitalize }
  [data-slot="basic-tool-tool-subtitle"]              font-size:14px; font-weight:regular; color:var(--v2-text-text-muted);
                                                      overflow:hidden; text-overflow:ellipsis; white-space:nowrap;
                                                      font-variant-numeric:tabular-nums
                                                      &.clickable { cursor:pointer; text-decoration:underline }
                                                      &.subagent-link { color:var(--text-interactive-base) }
  [data-slot="basic-tool-tool-arg"]                   同 subtitle 但不可点
  [data-slot="basic-tool-tool-action"]                display:inline-flex; align-items:center; flex-shrink:0
```
**关键数值**：图标槽 **16×16**，图标-文字间距 **8px**，标题/副标题**同为 14px**（标题 medium `text-text-base`，副标题 regular `text-text-muted`），副标题单行省略 + 数字等宽，`trigger-content` 最大宽 `calc(100% - 24px)`。

> #### ❗「左色条」与「图标」的真相（已核实，**推翻了 `plan-chat-ui-fidelity.md` 的设计**）
> - **左色条不存在**。`basic-tool.css` / `message-part.css` 中**没有任何左侧竖条元素或类**（无 `border-left`、无 `--card-accent` 竖条）。
> - **`BasicTool` 声明了 `icon: IconProps["name"]` prop，但它的 JSX 不渲染任何图标节点**（该文件未 import `Icon`）。所以工具卡**没有通用图标槽被填充**。
> - 真正承载颜色的只有两处：
>   1. **task 卡**：`--task-agent-color` / `--task-agent-legacy-color` 作用在**图标颜色**（`[data-component="task-tool-icon"] [data-component="icon"]`）与 surface 背景上
>   2. **error 卡**：图标 `var(--card-accent)`
> - `[data-slot="basic-tool-tool-indicator"]`（16×16）这个槽**只被各工具自己的 trigger 使用**（如 task 卡的 `Icon name="subagent"`、`Spinner`、`SessionProgressIndicatorV2`），不是通用图标位。
> - → **Android 复刻请改为「图标着色 + `layer-01` surface」方案，不要画左竖条。**

**`task-tool-card`（task/子智能体专用卡片）**
```css
[data-component="task-tool-card"]    width:100%; display:flex; align-items:center; gap:8px;
                                     padding:8px 12px; border-radius:6px;
                                     border:0.5px solid var(--border-weak-base, rgba(255,255,255,0.08));
                                     background:color-mix(in srgb, var(--background-base) 92%, transparent)
  [data-component="task-tool-surface"]   display:flex; align-items:center; gap:8px; flex:1 1 auto
  [data-component="task-tool-title"]     font-size:14px; font-weight:medium; text-transform:capitalize;
                                          color:var(--task-agent-legacy-color, var(--text-strong))
  [data-component="task-tool-action"]    margin-left:auto; opacity:0 → hover 时 1; transition:opacity .15s
  &:hover / &:focus-visible              background:color-mix(in srgb, var(--background-stronger) 88%, transparent)
```
**新版布局（`body[data-new-layout]`）下的差异 —— 这才是 v2 视觉**：
- 卡片**去掉边框/背景/圆角**（`border:0; background:transparent; box-shadow:none; padding:0`）
- 改为 surface 层：`[data-component="task-tool-surface"] { padding:8px 12px; border-radius:8px; background:var(--v2-background-bg-layer-01) }`
- 标题：`font-size:13px; font-weight:530; line-height:20px; letter-spacing:-0.04px; color:var(--v2-text-text-base); height:20px`
- 副标题：`font-size:13px; font-weight:440; line-height:20px; letter-spacing:-0.04px; color:var(--v2-text-text-base)`
- 图标色：`[data-component="task-tool-icon"] [data-component="icon"] { color: var(--task-agent-color, var(--v2-icon-icon-base)) }`
- 它是**可点击链接**：`triggerAsLink` / `triggerHref`，点击 `data.navigateToSession(id)`
- 副标题内容：`input.description` 或子会话 `sessionId`；`metadata.background === true` 时追加 `" (background)"`
- 动作图标：`square-arrow-top-right`，`opacity:0` → hover `1`

**展开箭头（chevron）渲染条件（已核实）**
仅当 **`hasChildren() && !hideDetails && !locked && (!pending() || allowOpenWhilePending)`** 才渲染 `Collapsible.Arrow`。

### 3.2 工具卡 4 状态（`pending` / `running` / `completed` / `error`）

> **注意**：源码中 `pending` 与 `running` **走同一分支**（统一用 `pending()` 判定）。

| 状态 | 视觉差异 | 可展开？ | 其它 |
|---|---|---|---|
| **pending / running** | 标题用 `TextShimmer active`（**标题文字做 shimmer 扫光**）；副标题：`Show when={!pending() \|\| subtitle \|\| args}` ⇒ **无副标题时会隐藏副标题** | **不能展开**（`handleOpenChange` 直接 `return`） | action 槽隐藏；箭头隐藏，除非 `allowOpenWhilePending`（**shell 工具设了**）；shell 用 `ShellSubmessage` 显示命令 |
| **completed** | shimmer 停止；副标题 / args / `DiffChanges`（仅 edit/write/patch）出现 | 能，按 `partDefaultOpen` | 箭头出现 |
| **error** | `ToolPartDisplay` 切换到 `ToolErrorCard`（见 3.3） | 见 3.3 | question 被 dismiss 时特例：右对齐 `span.text-13-regular.text-text-weak`（`Questions dismissed`） |

**展开动画与挂载策略（已核实）**
- 模块级 `deferredMounts` + rAF，**LIFO 顺序**（**先从列表底部重的块开始挂载**）配 `ready` 状态 → 避免一次性展开多个卡时卡顿
- `animated` 时：`motion.animate(contentRef, { height: "auto" | "0px" }, { type: "spring", visualDuration: 0.35, bounce: 0 })`，完成后置 `overflow: visible; height: auto`
- 受控开合：`props.open` + `onToolOpenChange`（app 时间线用 `toolOpen` store，并缓存进 `timelineCache.toolOpen` 实现跨会话记忆）

### 3.2b 工具卡默认展开规则（`part-default-open.ts` 全文逻辑）


```ts
export function partDefaultOpen(part, shell = false, edit = false) {
  if (part.type !== "tool") return
  if (part.tool === "bash" || part.tool === "shell") return shell
  if (part.tool === "edit" || part.tool === "write" || part.tool === "patch" || part.tool === "apply_patch") {
    if (!edit) return false
    return !deletionOnly(part)      // 纯删除的 diff 不默认展开
  }
}
```
- 由设置项驱动：`settings.general.row.shellToolPartsExpanded`（"展开 Shell 工具调用"）→ `shell`，`settings.general.row.editToolPartsExpanded`（"展开编辑工具调用"）→ `edit`
- `deletionOnly(part)`：`metadata.files` 全部 `type === "delete"`，或 `metadata.filediff.additions === 0 && deletions > 0`
- 其余工具 → 返回 `undefined`（即**默认不展开**，除非调用方另定策略）
- 展开状态提升到时间线层：`const [toolOpen, setToolOpen] = createStore<Record<string, boolean | undefined>>(cached?.toolOpen ?? {})`，并随 `takeSnapshot()` 一起缓存

### 3.3 error card（`tool-error-card.css`）

```css
[data-component="card"][data-kind="tool-error-card"]
  --card-pad-y: 8px
  --card-line-pad: 12px
  [data-slot="basic-tool-tool-title"]            color:var(--v2-text-text-base)
  [data-slot="basic-tool-tool-subtitle"]         color:var(--v2-text-text-muted)
  [data-slot="collapsible-arrow"], […-icon]      color:var(--v2-text-text-faint)
  > [data-component="collapsible"].tool-collapsible               gap:0px
  > [data-component="collapsible"].tool-collapsible[data-open="true"]   gap:4px
  [data-component="tool-error-card-icon"] [data-component="icon"]  color:var(--card-accent)   ← 错误图标用 accent
  [data-slot="tool-error-card-content"]          position:relative; padding-left:24px; margin-bottom:8px;
                                                 user-select:text
  展开时（[data-open=true]）                      padding-right:40px
  [data-slot="tool-error-card-copy"]             position:absolute; top:0; right:0; opacity:0; pointer-events:none;
                                                 transition:opacity .15s; will-change:opacity
  &:hover / &:focus-within 上的 copy             opacity:1; pointer-events:auto
  选中文本                                       background:var(--v2-state-bg-danger); color:var(--v2-text-text-base)
```
**关键数值**：垂直 padding **8px**，行内 padding **12px**，错误正文左缩进 **24px**、下边距 **8px**，展开时右侧留 **40px** 给复制按钮；复制按钮默认隐藏、hover/focus 出现（150ms）。

### 3.4 附件 chip / 文件 part

- `file.css`：`[data-component="file"] { content-visibility: auto }`；时间线行内改为 `content-visibility: visible`
- `[data-component="file"][data-mode="text"] { overflow: hidden }`；`[data-mode="diff"]` 下有
  - `[data-slot="diff-hunk-separator-line-number"]`：`position:sticky; left:0; background:var(--surface-diff-hidden-strong); z-index:2`，内部 `[data-slot="diff-hunk-separator-line-number-icon"] { aspect-ratio:1; width:24px; height:24px; color:var(--icon-strong-base) }`
  - `[data-slot="diff-hunk-separator-content"]`：`position:sticky; left:var(--diffs-column-number-width); width:var(--diffs-column-content-width); background:var(--surface-diff-hidden-base); padding-left:8px; user-select:none`；内部 span 用 `mix-blend-mode:var(--text-mix-blend-mode)`
  → **diff 采用「行号列 + 内容列」的 CSS 变量驱动宽度**，hunk 分隔行是 sticky 的
- **composer 里的附件 chip**（`packages/session-ui/src/v2/components/prompt-input/index.tsx`）：
  - 图片：`<img class="w-[58px] h-[46px] rounded-[6px] object-cover"/>` + 覆盖层 `div.absolute.inset-0.rounded-[6px].shadow-[inset_0_0_0_0.5px_var(--v2-border-border-base)]` → **58×46px，圆角 6px，0.5px 内描边**
  - 非图片：`<AttachmentCardV2 title={filename}>{typeLabel(filename, mime, i18n.t("ui.common.file"))}</AttachmentCardV2>`
  - 删除按钮：`button.absolute.-top-1.-end-1.size-4.rounded-full.bg-v2-icon-icon-muted.outline-solid.outline-1.outline-v2-icon-icon-contrast.opacity-0.group-hover:opacity-100` → **16px 圆角按钮，默认透明，hover 出现**
  - 横向滚动容器：`[data-slot="prompt-attachments-scroll"] class="flex flex-nowrap gap-2 overflow-x-auto no-scrollbar px-2 pt-2 pb-1"`
  - 两侧渐隐遮罩：`[data-slot="prompt-attachments-fade-left"/-right]`：`absolute inset-y-0 w-6 bg-[linear-gradient(...)]` → **宽 24px**
  - 评论 chip：`<CommentCardV2/>`，`TooltipV2 openDelay={800} contentClass="max-w-[300px] break-words"`

### 3.5 reasoning part（"Thought" 折叠）

- 相关文案（i18n）：
  - `ui.sessionTurn.status.thinking` = **"Thinking" / "思考中"**
  - `ui.sessionTurn.status.thinkingWithTopic` = "Thinking: {{topic}}" / "思考：{{topic}}"
  - `ui.sessionTurn.status.gatheringThoughts` = "正在整理思路"
  - `ui.sessionTurn.status.consideringNextSteps` = "正在考虑下一步"
  - `ui.sessionTurn.status.planning` = "正在规划下一步"、`delegating`="正在委派工作"、`gatheringContext`="正在探索"、`gatheredContext`="已探索"、`searchingCodebase`="正在搜索代码库"、`searchingWeb`="正在搜索网页"、`makingEdits`="正在修改"、`runningCommands`="正在运行命令"
- 渲染：`TimelineThinkingRow` → `div[data-slot="session-turn-thinking"]` + `TextShimmer(text="思考中")`
- 当 `showReasoningSummaries` 设置关闭时，额外渲染 `TextReveal` 显示 reasoning 小标题（`.session-turn-thinking-heading`，`travel:25, duration:700`）
- 设置项：`settings.general.row.reasoningSummaries.title` = "显示推理摘要" / `description` = "在时间线中显示模型推理摘要"

> #### ❗纠正：reasoning part **不是** "Thought" 折叠面板
> **源文件**：[`packages/session-ui/src/components/message-part.tsx`](https://github.com/anomalyco/opencode/blob/dev/packages/session-ui/src/components/message-part.tsx)（`PART_MAPPING.reasoning`，约 L1763）+ `message-part.css`
>
> **DOM 只有两层，没有任何折叠容器**：
> ```
> div[data-component="reasoning-part"][data-timeline-part-id]
> └─ <PacedMarkdown>            ← 直接渲染 markdown 正文
> ```
> - **折叠/展开默认值、`"Thought"` 标签文本、时长显示、专属动画：全部 `未找到`**
> - 全仓 i18n 中**不存在 `Thought` 字符串**；唯一相关的是 `ui.sessionTurn.status.thinking` = **"Thinking"**（那是**运行中的 shimmer 提示行**，属 app 层的 `session-turn-thinking`，**不是** reasoning part）
> - **显示门槛**：`renderable()` 要求 `showReasoningSummaries`（设置项）+ 文本非空
>
> **CSS 关键数值**：
> ```css
> reasoning-part                 { width:100%; color:var(--v2-text-text-muted); line-height:var(--line-height-normal) }
> reasoning-part markdown        { margin-top:16px; font-size:13px }
> reasoning-part strong,b        { 与父同色 }
> reasoning-part p:has(strong)   { margin-top:24px; margin-bottom:0 }
> /* 暗色下 */ :not(pre) > code   { opacity:.6 }
> ```
> → **Android 实现**：reasoning 就是一段 **13px 的 muted markdown**，上边距 16px；"思考中"是**另一个独立的 shimmer 行**（`gap:8px; margin-top:12px; min-height:20px; color:var(--text-weak); font-weight:medium`），`showThinking()` 仅在 `working() && !error() && status !== "retry"` 且无可见 part 时为真。
> **不要做 "Thought" 折叠卡。**

### 3.5b text part（markdown）—— 补充细节

**源文件**：`message-part.tsx` `PART_MAPPING.text`、`markdown.tsx`、`markdown-stream.ts`、`message-part.css`

```
div[data-component="text-part"][data-timeline-part-id]
└─ div[data-slot="text-part-body"]
   └─ <PacedMarkdown>
      └─ div[data-component="markdown"][dir="auto"]     ← innerHTML 由 morphdom 逐块 patch
         每个块 = div[data-markdown-block][data-markdown-key][data-markdown-hash] style="display:contents"
   └─ (可选) div[data-slot="text-part-copy-wrapper"]
```
- **markdown 库组合**：**marked**（`marked.lexer`）+ **remend**（`heal()` 修复未闭合语法，`linkMode: "text-only"`）+ **morphdom**（块级 DOM diff）+ **shiki**（`highlightStreamingCode`，跑在 worker `markdown-worker.ts`），HTML 过 `sanitizeMarkdown`
- **流式**：`stream()/project()` 产出 `Block { raw, src, mode: "full"|"live"|"code", language, complete }`
  - code fence 未闭合 ⇒ `mode: "code", complete: false`（**每帧只追加 token span**）
  - 引用式链接 `/^[ \t]{0,3}\[[^\]]+\]:/m` 命中 → 整段降级 `live`
- **打字机节流 `createPacedValue`**：`TEXT_RENDER_PACE_MS = 24`、`TEXT_RENDER_IMMEDIATE = 512`、`step()` = `2/4/8/ceil(size/4)`(≤256)、对齐标点 `TEXT_RENDER_SNAP = /[\s.,!?;:)\]]/`
- **代码块结构**：`div[data-component="markdown-code"]`（`data-language`、`data-code-kind="shell"`）→ `pre.shiki.OpenCode` → `code.language-<lang>` → `span[style]`
  - 复制按钮 `div[data-slot="markdown-copy-button"]`（`IconV2 outline-copy` + `check` 双图标，`data-copied="true"` 保持 **2000ms**），点击 `navigator.clipboard.writeText(code.textContent)`
- **行内代码**：非 `pre` 的 `code` 加 `data-inline-code-kind`；纯 URL 包成 `a.external-link[target=_blank][rel=noopener noreferrer]`
- **复制/元信息行**：`div[data-slot="text-part-copy-wrapper"]`（`MessageActionButton`：copy/check，label **"Copy response" / "Copied"**）+ `span[data-slot="text-part-meta"] class="text-12-regular text-text-weak cursor-default"`；**仅最后一个 text part** 显示复制
- **CSS 关键数值**：
  ```css
  text-part                 { width:100%; margin-top:24px }        /* body 的 margin-top:0 */
  text-part-copy-wrapper    { min-height:24px; margin-top:4px; gap:10px; opacity:0 → 1 }
                            /* :hover / :focus-within 触发，transition: opacity .15s */
  text-part[data-interrupted] { width:100%; justify-content:flex-end; gap:12px }
  ```
- **user 消息的 markdown 高亮**（`HighlightedText`，非 text-part）：`<span data-highlight="agent">` → `color: var(--syntax-type); direction:ltr; unicode-bidi:isolate`；`[data-highlight="file"]` → `color: var(--syntax-property)`

### 3.6 shell 子消息（`@!` / bash 输出）

```css
[data-component="shell-submessage"]                 min-width:0; max-width:100%; display:inline-flex;
                                                    align-items:baseline; vertical-align:baseline
  [data-slot="shell-submessage-width"]              min-width:0; max-width:100%; display:inline-flex;
                                                    align-items:baseline; overflow:hidden
  [data-slot="basic-tool-tool-subtitle"]            ← 中间层
  [data-slot="shell-submessage-value"]              display:inline-block; vertical-align:baseline;
                                                    min-width:0; line-height:inherit; white-space:nowrap
```
**完整 DOM**：`span[data-component="shell-submessage"][dir="ltr"]` → `span[data-slot="shell-submessage-width"]` → `span[data-slot="basic-tool-tool-subtitle"]` → `span[data-slot="shell-submessage-value"]`

- **触发条件**：shell 工具 trigger 内 `Show when={!open() && props.input.command}` ⇒ **仅在卡片折叠时**把命令行作为副标题显示；标题是 `TextShimmer "Shell"`（`pending()` 时 active）
- **动画（仅首次 pending 时 `sawPending` 为真）**：`requestAnimationFrame` 内
  ```ts
  animate(widthRef, { width: "auto" }, { type: "spring", visualDuration: 0.25, bounce: 0 })
  animate(valueRef, { opacity: 1, filter: "blur(0px)" },
          { duration: 0.32, ease: [0.16, 1, 0.3, 1] })
  // 初值：width:0px / opacity:0; filter:blur(2px)
  ```
  → 行内（`inline-flex`）而非块级；值不换行（`white-space:nowrap`），靠外层 `overflow:hidden` 裁切。
- **展开后的输出 `bash-output`**：
  ```css
  [data-component="bash-output"][dir="ltr"]     { border:0.5px solid var(--v2-border-border-base);
                                                  border-radius:6px; overflow:hidden }
  [data-slot="bash-copy"]                       { position:absolute; top:4px; right:4px }   /* hover 显形 */
  [data-slot="bash-scroll"][data-scrollable]    { max-height:240px; overflow-y:auto }        /* 滚动条隐藏 */
  [data-slot="bash-pre"]                        { padding:12px }
  /* code: 13px; white-space:pre-wrap; overflow-wrap:anywhere */
  ```
- **文本内容**：`` `$ ${command}\n\n${stripAnsi(output)}` ``（`\r\n?` → `\n`），复制后 **2 秒复位**

### 3.7 工具分组汇总（`ContextToolGroup`，**已核实合并规则**）

- **何时合并**：`groupParts()` **只把「连续」的 `read | glob | grep | list`** 合成一个 `{ type: "context" }` 组
  - 中间夹任何其它 part 即**断开**（`flush()` 收尾）
  - 常量：`CONTEXT_GROUP_TOOLS = new Set(["read","glob","grep","list"])`；另有 **`HIDDEN_TOOLS = new Set(["todowrite"])`**
  - 最后一个组且 `working` 时传 `busy`
  - 统计口径：`contextToolSummary()` → read / search(glob+grep) / list
- **行 DOM**：
```
Collapsible.variant="ghost".tool-collapsible[data-timeline-part-ids]
└─ div[data-component="context-tool-group-trigger"]
   ├─ span[data-slot="context-tool-group-title"] class="min-w-0 flex items-center gap-2 text-14-medium text-text-strong"
   │  ├─ span[data-slot="context-tool-group-label"]      ToolStatusTitle split={false}
   │  └─ span[data-slot="context-tool-group-summary"]
   │       class="min-w-0 overflow-hidden text-ellipsis whitespace-nowrap font-normal text-text-base"
   │       ← AnimatedCountList
   └─ Collapsible.Arrow
展开后 → div[data-component="context-tool-group-list"]
          内是「不可展开的纯文本行」 div[data-component="tool-trigger"]（title / subtitle / args）
```
- **文案**：进行中 `ui.sessionTurn.status.gatheringContext` = **"Exploring" / "正在探索"**；完成 `gatheredContext` = **"Explored" / "已探索"**
  计数项：`{{count}} read/reads`、`{{count}} search/searches`、`{{count}} list/lists`（前缀逗号是独立 span）
- **默认折叠**（`localOpen = false`）
- **CSS 关键数值**：
  ```css
  context-tool-group-trigger { min-height:24px; display:flex; gap:0 }
  context-tool-group-list    { padding-left:12px; gap:4px }
  /* 计数动画 */
  item/empty : grid-template-columns: 0fr → 1fr  +  opacity 0→1  +  blur(2px)→0
               +  translateY(.06em) scale(.985) → 0
  逗号       : max-width 0 → 1ch; margin-right 0 → .45ch; translateX(-.08em) → 0
  动效令牌    : --tool-motion-spring-ms:480ms
               --tool-motion-fade-ms:240ms / 280ms / 320ms
               --tool-motion-ease:cubic-bezier(0.22,1,0.36,1)
               --tool-motion-blur:2px
               prefers-reduced-motion → 全部 0ms
  ```
  `ToolStatusTitle` 用 `inline-grid { grid-area: 1/1 }` 双层（active/done）交换 + 宽度过渡 **480ms**
- 组件：`tool-count-label.tsx` / `tool-count-summary.tsx`（+ 各自 `.css`）；文案来自 i18n：
  - `ui.messagePart.context.read` = plural `{{count}} 次读取`
  - `ui.messagePart.context.search` = plural `{{count}} 次搜索`
  - `ui.messagePart.context.list` = plural `列出 {{count}} 次`
  - `ui.messagePart.compaction` = "会话已压缩"

  - `ui.messagePart.diagnostic.error` = "错误"
  - `ui.messagePart.title.edit` = "编辑"、`ui.messagePart.title.write` = "写入"
- 组件 `ContextToolGroup` 从 `@opencode-ai/session-ui/message-part` 导出（时间线里按 context 分组）
- **具体分组阈值（连续多少个 read/grep 才合并成一行）需看 `tool-count-summary.tsx` 全文，本次未逐行核对 → `待确认`**

### 3.8 工具标题映射（`ui.tool.*`，与 Android 复刻强相关）

| tool | en | zh |
|---|---|---|
| read | Read | 读取 |
| list | List | 列出文件 |
| glob | Glob | Glob |
| grep | Grep | Grep |
| webfetch | Webfetch | Webfetch |
| websearch | Web search | 网页搜索 |
| bash/shell | Shell | Shell |
| patch | Patch | 补丁 |
| todos | To-dos | 待办 |
| task | Task | 任务 |
| skill | Skill | 技能 |
| agent | {{type}} agent | {{type}} 智能体 |
| loaded | Loaded | 已加载 |

---

## 4. Composer 输入框（prompt-input v2）

**源文件**：[`packages/session-ui/src/v2/components/prompt-input/index.tsx`](https://github.com/anomalyco/opencode/blob/dev/packages/session-ui/src/v2/components/prompt-input/index.tsx)（**这是 v2 的真身**）；Android 侧组合层 [`packages/app/src/components/prompt-input-v2.tsx`](https://github.com/anomalyco/opencode/blob/dev/packages/app/src/components/prompt-input-v2.tsx)

### 4.1 外层结构

```html
<div class="relative size-full flex flex-col gap-0 [class]">
  <input type="file" multiple hidden accept="…" class="hidden"/>      ← 隐藏文件选择器
  <PromptInputV2Popover/>                                             ← @ / 补全弹层（绝对定位，在上方）
  <form data-component="prompt-input-v2" data-dock-border-underlay?="v2"
        class="group/prompt-input relative min-h-[96px] w-full overflow-clip rounded-xl bg-v2-background-bg-base"
        class:shadow-[var(--v2-elevation-raised)]  ← 非 borderUnderlay 时
        class:border border-v2-icon-icon-info border-dashed  ← 拖拽中（state.drag==="active"）
    ├─ 拖拽提示遮罩 div.pointer-events-none.absolute.inset-0.z-20.grid.place-items-center.rounded-xl.bg-v2-background-bg-base/90
    │    文案 ui.promptInput.dropFiles = "Drop files to attach" / "拖放文件以添加附件"
    ├─ <PromptInputV2Attachments/>（仅 mode==="normal"）
    ├─ div.relative.min-h-[60px]
    │   ├─ div[data-component="prompt-input"][role="textbox"][aria-multiline="true"][contenteditable]
    │   └─ (无内容时) 假 placeholder div.pointer-events-none.absolute.inset-x-0.top-0.px-4.pt-4
    └─ div.flex.h-11.items-center.px-2                          ← 工具行 h-11 = 44px
        ├─ div.flex.min-w-0.flex-1.items-center.gap-1           ← 左组，shell 模式下 opacity:0 + pointer-events:none + inert
        │   ├─ <PromptInputV2AddMenu/>   (+) 按钮
        │   ├─ (view.agent)  ConfiguredSelect
        │   ├─ modelControl | (view.model) ConfiguredSelect
        │   └─ (variantControlVisible && view.variant && options>1) ConfiguredSelect
        └─ <PromptInputV2SubmitButton/>
```

### 4.2 关键尺寸（逐字）

| 元素 | 数值 |
|---|---|
| form 容器 | `min-height: 96px`，`border-radius: rounded-xl`，`overflow: clip` |
| 编辑区 | `min-height: 60px`，**`max-height: 180px`**，`overflow-y: auto` |
| 编辑区排版 | `padding: px-4 pt-4 pb-2` → 左右 16px、上 16px、下 8px |
| 编辑区字号 | `text-[13px] font-[440] leading-5`（13px / 行高 20px / 字重 440） |
| 编辑区行为 | `whitespace-pre-wrap`、`empty:before:content-['\200B']`（空时用零宽空格撑住行高） |
| 工具行 | `height: 44px`（`h-11`），`padding: 0 8px`（`px-2`），`gap: 8px`（左组 `gap-1` = 4px） |
| shell 模式 | `font-mono!`（等宽），`autocapitalize="off"` `autocorrect="off"` `spellcheck={false}` |
| normal 模式 | `autocapitalize="sentences"` `autocorrect="on"` `spellcheck` |
| 拖拽边框 | `--v2-icon-icon-info` + `border-dashed` |

**多行自适应机制（结论）**：**纯 CSS**。`min-h-[60px] max-h-[180px] overflow-y-auto` + `contenteditable` div。**没有 JS 测量高度**——超过 180px 后编辑区内部滚动，form 高度不再增长。
> Android 对应实现：`EditText` 设 `minHeight=60dp`、`maxHeight=180dp`、`scrollbars=vertical`、`inputType=textMultiLine`，**不要**用自适应无限增长。

**编辑区 class 全文**：
```
relative z-10 block min-h-[60px] max-h-[180px] w-full overflow-y-auto whitespace-pre-wrap bg-transparent
px-4 pt-4 pb-2 text-[13px] font-[440] leading-5 text-v2-text-text-base focus:outline-none
empty:before:content-['\200B']
[&_[data-mention=file]]:text-syntax-property
[&_[data-mention=agent]]:text-syntax-type
[&_[data-mention=reference]]:text-syntax-keyword
```
→ **mention 着色规则**：`data-mention="file"` → `--syntax-property` 色；`"agent"` → `--syntax-type`；`"reference"`（目录）→ `--syntax-keyword`。渲染时把 mention 做成 `<span contenteditable="false">` 并带 `data-name` / `data-path` / `data-mime` / `data-filename`。

### 4.3 placeholder 文案（**权威来源：vendored bundle + 上游 `i18n/zh.ts`**）

| 场景 | key | en | zh |
|---|---|---|---|
| 默认（v2 composer） | `ui.promptInput.placeholder.normal` | `Ask anything, {{slash}} for commands, {{at}} for context...` | `随便问点什么， {{slash}} 可查看命令， {{at}} 可添加上下文...` |
| shell 模式 | `ui.promptInput.placeholder.shell` | `Enter shell command...` | `输入 shell 命令...` |
| 旧版（v1） | `prompt.placeholder.simple` | `Ask anything...` | `随便问点什么...` |
| 旧版 + 示例 | `prompt.placeholder.normal` | `Ask anything... "{{example}}"` | `随便问点什么... "{{example}}"` |
| 旧版 shell | `prompt.placeholder.shell` | `Enter shell command... {{example}}` | `输入 shell 命令... {{example}}` |
| 单条评论 | `prompt.placeholder.summarizeComment` | `Summarize the comment…` | `总结该评论…` |
| 多条评论 | `prompt.placeholder.summarizeComments` | `Summarize the comments…` | `总结评论…` |

→ 实际传入的 `{{slash}}` = `"/"`，`{{at}}` = `"@"`（见 `prompt-input-v2.tsx` 的 `promptDesignPlaceholder`）。
> **注意**：`plan-chat-ui-fidelity.md` 里写的 placeholder `"Plan, build, and fix anything"` **在本次抓取的数据中不存在** → 标 `未找到`。真正相关的近似串是 `session.new.title` = **`Build anything`**（zh: `构建任何内容`），那是 **new-session 页的标题**，不是 composer placeholder。

### 4.4 附件按钮「+」=`PromptInputV2AddMenu`

- 触发：`MenuV2.Trigger as={IconButtonV2} data-action="prompt-attach" icon={<IconV2 name="plus"/>} variant="ghost-muted" size="large" disabled={shell 模式}`
- Tooltip = `t("ui.promptInput.add")` = **"Add images and files" / "添加图片和文件"**，后面拼 `KeybindV2`（默认 `["Mod","U"]`）
- 菜单 `MenuV2 placement="top-start" gutter={6} modal={false}`，`Content style={{minWidth:"180px"}}`，结构：

| 菜单项 | label key | en / zh | shortcut 显示 |
|---|---|---|---|
| 附加文件 | `ui.promptInput.attachments` | `Images and files` / `图片和文件` | `Mod+U`（`attachShortcut`，默认 `"Mod+U"`） |
| ── 分隔 ── | | | |
| 命令 | `ui.promptInput.commands` | `Commands` / `命令` | `/` |
| 上下文 | `ui.promptInput.context` | `Context` / `上下文` | `@` |
| Shell 命令 | `ui.promptInput.shell` | `Shell command` / `Shell 命令` | `!` |

### 4.5 模型选择器 chip

**两条路径**：
1. **通用**（`PromptInputV2ConfiguredSelect` → 最终 `PromptInputV2Select`）：
   - 触发 `ButtonV2 variant="ghost-muted" size="normal" class="max-w-[220px] justify-start ![font-weight:440]"`
   - 内容：`currentIcon`（model 时有 `<ProviderIcon class="size-4 opacity-60"/>`）+ `span.truncate.capitalize.leading-5` + `IconV2 name="chevron-down"`（外层 `span.-ms-0.5.-me-1`）
   - Tooltip = `title` + `KeybindV2`：agent → `Mod+.`；model → `Mod+M`；variant → `Shift+Mod+D`
   - 菜单 `MenuV2.RadioGroup`，每项 `MenuV2.RadioItem class="capitalize" closeOnSelect`
   - **最大宽度 220px**
2. **Android/App 定制**（`prompt-input-v2.tsx` 的 `PromptInputV2ModelControl`）：
   - `ButtonV2 variant="ghost-muted" size="normal" style={{height:"28px"}} class="min-w-0 max-w-[220px] justify-start ![font-weight:440] group"` → **固定高 28px，最大宽 220px**
   - `data-action="prompt-model"`，`data-control-type` 为 `"popover"`（已付费）或 `"dialog"`（未付费）
   - 未付费 → `DialogSelectModelUnpaidV2`；已付费 → `ModelSelectorPopoverV2`
   - loading 时整块不渲染（`Show when={!props.loading}`），出现时带 `animate-in fade-in`
   - `ProviderIcon class="size-4 shrink-0 opacity-40 group-hover:opacity-100 transition-opacity duration-150"`
   - Tooltip = `t("command.model.choose")` = **"Choose model"/"选择模型"** + keybind `model.choose`

### 4.6 发送 / 停止按钮

```jsx
<IconButton
  data-action="prompt-submit"
  type="button"
  disabled={!stopping && disabled}
  tabIndex={mode === "normal" ? undefined : -1}
  icon={stopping ? "stop" : mode === "shell" ? "arrow-undo-down" : "arrow-up"}
  variant="primary"
  class="size-7 rounded-md p-[6px] text-v2-icon-icon-muted shadow-[var(--v2-elevation-button-contrast)] disabled:opacity-50"
  style={{ "background-image":
    "linear-gradient(180deg,var(--v2-alpha-light-20) 0%,var(--v2-alpha-light-0) 100%),linear-gradient(90deg,var(--v2-background-bg-contrast) 0%,var(--v2-background-bg-contrast) 100%)" }}
/>
```
- **尺寸 28×28px（`size-7`），圆角 6px（`rounded-md`），内 padding 6px**，禁用时 `opacity: 50%`
- 图标三态：`stop`（停止方形）/ `arrow-undo-down`（shell 模式，回车提交）/ `arrow-up`（普通发送）
- 背景是**双层渐变叠加**：上层白色 12%→0% 的垂直高光，下层纯 `bg-contrast`
- `stopping` 判定（`prompt-input-v2.tsx`）：`working() && blank()` —— 即"正在运行且输入框为空（无文本、无附件、无评论）"时才显示停止
- Tooltip：`stopping ? "Stop" : "Send"`（`ui.promptInput.stop` / `.send`）；`inactive={!stopping && disabled}`

### 4.7 @-mention 触发与补全弹层（`PromptInputV2Popover`）

```jsx
<div class="absolute inset-x-0 -top-2 z-40 flex max-h-80 -translate-y-full flex-col overflow-auto
            rounded-xl bg-v2-background-bg-base p-2 shadow-[var(--v2-elevation-raised)] no-scrollbar"
     onMouseDown={e => e.preventDefault()}>            ← 阻止失焦
```
- **定位**：贴在输入框**上沿外侧**（`-top-2` = 上移 8px，`-translate-y-full` = 整体上翻一个自身高度），`z-40`，**最大高 320px（`max-h-80`）**，圆角 12px，阴影 `elevation-raised`，内边距 8px，隐藏滚动条
- **命令模式额外搜索框**（`popover.type === "command-menu"`）：
  `div.px-2.py-1 > input` 无边框透明，`text-[13px] leading-5`，placeholder = `"/"`，label = `t("ui.promptInput.commands")`，挂载后 `requestAnimationFrame` 自动 focus
- **空态**：`div.px-2.py-1.text-v2-text-text-muted` = `t("ui.promptInput.noMatchingItems")` = **"No matching items" / "没有匹配项"**
- **候选项行**：
  `button[data-suggestion-id]` `flex w-full items-center gap-2 rounded-md px-2 py-1 text-start hover:bg-v2-overlay-simple-overlay-hover`（激活项 `bg-v2-overlay-simple-overlay-hover`）
  - 左：`div.flex.min-w-0.flex-1.items-center.gap-2` → 图标 + `span.shrink-0.text-v2-text-text-base`（label）+ `span.min-w-0.truncate.text-v2-text-text-muted`（description）
  - 右：`span.shrink-0.text-v2-text-text-muted`（keybind，用 `"+"` 连接）
  - `onPointerMove` 更新高亮；`onClick` 选择
- **候选图标**：`kind === "agent"` → `<Icon name="brain" size="small" class="text-icon-info-active"/>`；`kind === "command"` → **无图标**；否则 → `<FileIcon class="size-4 shrink-0"/>`（`reference` 类型当目录）
- **候选类型（`kind` 联合）**：`reference` | `agent` | `resource` | `file` | `command`
- **候选来源**（`prompt-input-v2.tsx` 的 `context` / `commands` memo）：
  - `references`：来自 `sync().data.reference`（过滤 `hidden`），label `@${name}`，kind `reference`
  - `agents`：`controls.agents.available` 中 `!hidden && mode !== "primary"`，label `@${name}`，kind `agent`
  - `resources`：`sync().data.mcp_resource`，label `@${name}`，kind `resource`
  - `recent`：当前会话的**文件 tab**（`createSessionTabs().activeFileTab` 优先，其余按打开顺序去重），label 为路径，`recent: true`，kind `file`
  - `commands`：`sync().data.command`（custom）+ `command.options`（builtin，过滤 `disabled`、`suggested.` 前缀、无 `slash`），label `/${trigger}`
  - **动态文件搜索**：`searchContextFiles(query)` → `files.searchFilesAndDirectories(query)` 映射为 `kind: "file"`
- **提交**：`onSubmit = () => void submission.handleSubmit(new Event("submit"))`（左组首项）；`onStop = () => void submission.abort()`
- **快捷键注册**（`command.register("prompt-input", …)`）：
  | id | keybind | 说明 |
  |---|---|---|
  | `file.attach` | `mod+u` | 附加文件（仅 normal 模式） |
  | `prompt.mode.shell` | `mod+shift+x` | 切到 shell |
  | `prompt.mode.normal` | `mod+shift+e` | 切回 normal |
- **键盘**：`Enter`（非 Shift、非 `isComposing`、非 `repeat`）→ submit；`Shift+Enter` → 换行；shell 模式 `Esc` 退出

---

## 5. Settings 设置页（**已核实，与"预期"差异较大**）

**源文件**
- [`packages/app/src/components/settings-dialog.tsx`](https://github.com/anomalyco/opencode/blob/dev/packages/app/src/components/settings-dialog.tsx)（命令注册 + 入口）
- [`packages/app/src/components/settings-v2/dialog-settings-v2.tsx`](https://github.com/anomalyco/opencode/blob/dev/packages/app/src/components/settings-v2/dialog-settings-v2.tsx)（v2 弹窗 + 左导航）
- [`packages/app/src/components/settings-v2/general.tsx`](https://github.com/anomalyco/opencode/blob/dev/packages/app/src/components/settings-v2/general.tsx)
- [`packages/app/src/components/settings-v2/servers.tsx`](https://github.com/anomalyco/opencode/blob/dev/packages/app/src/components/settings-v2/servers.tsx)
- [`packages/app/src/components/settings-v2/parts/row.tsx`](https://github.com/anomalyco/opencode/blob/dev/packages/app/src/components/settings-v2/parts/row.tsx) / `parts/list.tsx`
- [`packages/app/src/components/settings-keybinds.tsx`](https://github.com/anomalyco/opencode/blob/dev/packages/app/src/components/settings-keybinds.tsx)
- [`packages/app/src/components/settings-v2/settings-v2.css`](https://github.com/anomalyco/opencode/blob/dev/packages/app/src/components/settings-v2/settings-v2.css)

> **重要架构澄清**：**不存在 `pages/settings*` 路由页面**（404）。设置 UI 只在 `components/settings-v2/` 里，是一个 **`Dialog size="x-large" variant="settings"`** 弹窗。

### 5.0 打开方式

- 命令 `id: "settings.open"`，title = `command.settings.open` = **"Open settings"**，category = `command.category.settings` = **"Settings"**，**keybind `mod+comma`（Ctrl/Cmd + ,）**
- `onSelect` 懒加载 `@/components/settings-v2` 的 `DialogSettings`
- 页面入口：Home 左下角 `HomeUtilityNav` 的 `settings-gear` 按钮（见 1.4）；Help 按钮打开 `https://opencode.ai/desktop-feedback`

### 5.1 左导航结构（**这是关键交付；与"外观/系统通知/音效/高级"那一层不是同一级**）

根结构：
```jsx
<Dialog size="x-large" variant="settings" class="settings-v2-dialog">
  <TabsV2 orientation="vertical" variant="settings" value={tab()} onChange={…} class="settings-v2">
    <TabsV2.List>
      <div class="flex flex-col justify-between h-full w-full">
        <div class="flex flex-col gap-3 w-full">
          <div class="flex flex-col gap-1.5">   ← 第 1 分组
          <div class="flex flex-col gap-1.5">   ← 第 2 分组
        </div>
        <div class="settings-v2-nav-footer">    ← 底部版本信息
```

**左导航一级分类（精确顺序 + 图标 + 文案）**：

| 分组标题（`TabsV2.SectionTitle`） | `value` | 图标 | 标签 key | en |
|---|---|---|---|---|
| `settings.section.desktop` = **"Desktop"** | `general` | `sliders` | `settings.tab.general` | **"General"** |
| | `shortcuts` | `keyboard` | `settings.tab.shortcuts` | **"Shortcuts"** |
| `settings.section.server` = **"Server"** | `servers` | `server` | `status.popover.tab.servers` | **"Servers"** |
| | `providers` | `providers` | `settings.providers.title` | **"Providers"** |
| | `models` | `models` | `settings.models.title` | **"Models"** |

底部：`div.settings-v2-nav-footer` 含两个 span：`app.name.desktop` = **"OpenCode Desktop"**、`"v" + platform.version`。

> #### ❗纠正一个常见误解
> **"外观 / 系统通知 / 音效 / 高级"这些不是左导航的一级项**，而是 **General 面板内部的 section 标题**。
> 左导航只有 2 个分组标题（Desktop / Server）+ 5 个 tab（General / Shortcuts / Servers / Providers / Models）。
> 用户描述里的"桌面/服务器/通用/外观/系统通知/音效/高级"实际跨越了两个层级。

### 5.2 General 面板（渲染顺序 + 分区标题）

`InterfaceSection` → `InterfaceNoticeSection` → `GeneralSection`（**无 section 标题**）→ `AppearanceSection` → `NotificationsSection` → `SoundsSection` → `UpdatesSection`（仅 desktop）→ `DisplaySection`（仅 desktop）→ `AdvancedSection`

分区标题字面量：

| key | en | 说明 |
|---|---|---|
| `settings.general.section.appearance` | **"Appearance"** | 对应中文"外观" |
| `settings.general.section.notifications` | **"System notifications"** | 对应中文"系统通知" |
| `settings.general.section.sounds` | **"Sound effects"** | 对应中文"音效" |
| `settings.general.section.updates` | **"Updates"** | 更新 |
| `settings.general.section.display` | **"Display"** | 显示 |
| `settings.general.section.advanced` | **"Advanced"** | 对应中文"高级" |
| `settings.general.section.feed` | **"Feed"**（字典中存在，`general.tsx` 未使用） | 动态 |

**各分区的行（`data-action` 值为实抓）**

| 分区 | `data-action` | 控件 | 标题 |
|---|---|---|---|
| General | `settings-language` | `SelectV2` | `settings.general.row.language.title` = "Language" |
| General | `settings-auto-accept-permissions` | `Switch` | `command.permissions.autoaccept.enable` = "Auto-accept permissions" |
| General | `settings-shell` | `SelectV2` | `settings.general.row.shell.title` = "Terminal shell"（auto 选项 `settings.general.row.shell.autoDefault` = "Auto (Default)"） |
| General | `settings-feed-reasoning-summaries` | — | "Show reasoning summaries" |
| General | `settings-feed-shell-tool-parts-expanded` | — | "Expand shell tool parts" |
| General | `settings-feed-edit-tool-parts-expanded` | — | "Expand edit tool parts" |
| General | `settings-mobile-titlebar-bottom` | — | 仅移动端 + 非 prod 构建 |
| Appearance | `settings-color-scheme` | `SelectV2` | 选项 `theme.scheme.system`="System" / `.light`="Light" / `.dark`="Dark" |
| Appearance | `settings-theme` | — | `settings.general.row.theme.title` = "Theme" |
| Appearance | `settings-ui-font` / `settings-code-font` / `settings-terminal-font` | `TextInputV2`（外层 `w-full sm:w-[220px]`，内联 `font-family`） | 界面/代码/终端字体 |
| Notifications | `settings-notifications-agent` / `-permissions` / `-errors` | `Switch` | 智能体 / 权限 / 错误 |
| Sounds | `settings-sounds-agent` / `-permissions` / `-errors` | `SelectV2`（`soundOptions`） | 智能体 / 权限 / 错误 |
| Updates | `settings-release-notes` | `Switch` + 检查更新按钮 | `settings.general.row.releaseNotes.title` = "Release notes" |
| Display | `settings-pinch-zoom` | — | "Pinch to zoom" |
| Advanced | `settings-show-file-tree` / `settings-show-search` / `settings-show-status` / `settings-show-custom-agents` | — | 文件树 / 命令面板 / 服务器状态 / 显示智能体 |

**行组件结构**：`div[data-component="settings-v2-row"]` → `div[data-slot="settings-v2-row-copy"]`（title + description）+ `div[data-slot="settings-v2-row-control"]`；列表容器 `[data-component="settings-v2-list"]`。

### 5.3 settings-v2.css 关键数值（**直接可用于 Android 还原**）

```css
[data-component="dialog-v2"][data-variant="settings"] [data-slot="dialog-body"]
    { padding: 0; overflow: hidden }
.settings-v2-tab-header        sticky top:0 z-index:10; padding: 40px 40px 32px;
                               background: linear-gradient(to bottom, var(--v2-background-bg-base) calc(100% - 24px), transparent)
.settings-v2-tab-title         font-size:15px; font-weight:640; line-height:1; color: var(--v2-text-text-base)
.settings-v2-tab-body          gap:36px; padding: 0 40px 40px
.settings-v2-section           gap:16px
.settings-v2-section-title     padding-bottom:8px; font-size:15px; font-weight:640
[data-component="settings-v2-list"]
    border-radius:8px; background: var(--v2-background-bg-layer-01);
    padding-inline: 20px; box-shadow: inset 0 0 0 0.5px var(--v2-border-border-muted)
[data-component="settings-v2-row"]
    display:flex; flex-wrap:wrap; gap:16px; padding-block:20px;
    border-bottom: 0.5px solid var(--v2-border-border-base)   /* :last-child 无边框 */
    @media (min-width:640px) { flex-wrap: nowrap }
    title:       13px / 530 / letter-spacing -0.04px
    description: 13px / 440 / line-height 20px; margin-block: -3.5px; color: var(--v2-text-text-muted)
@media (max-width:639px)       左列表 width/min-width: 144px; padding-inline: 8px
.settings-v2-nav-footer        gap:8px; padding: 4px 0 4px 4px; span 11px/440 color var(--v2-text-text-faint)
/* Server 对话框 */
容器                            width:480px; max-width: calc(100vw - 32px); border-radius:8px
header / footer                padding: 24px 24px 16px / 24px
错误文字                        11px, color var(--v2-state-fg-danger)
/* Shortcuts */
列表                            padding:20px; border-radius:6px
.settings-v2-keybind-button    padding: 6px 8px; margin: -6px -8px; border-radius:2px;
                               font-size:11px; font-weight:530; letter-spacing:0.05px;
                               font-variant-numeric: tabular-nums; color: var(--v2-text-text-faint)
                               hover/active bg: var(--v2-background-bg-layer-02)
/* Servers 行 */
.settings-v2-servers-row       display:flex; justify-content:space-between; gap:16px
  非末行                        padding-bottom:16px; margin-bottom:16px; border-bottom: 0.5px solid var(--v2-border-border-base)
.settings-v2-servers-name      13px / 530
.settings-v2-servers-meta      11px / 440
```

### 5.4 服务器管理列表（`servers.tsx`）

- 头部：`div.settings-v2-tab-header.settings-v2-servers-header`（有搜索时加 `--stacked`）
  → `div.settings-v2-tab-header-row` = `h2.settings-v2-tab-title`（**"Servers"**）+ `<AddServerMenu onAddServer={openAdd}/>`
- **搜索框仅在 `非 WSL 服务器数 + WSL 数 > 1` 时出现**：`div.settings-v2-tab-search` > `TextInputV2 type="search" appearance="base"`，placeholder = `dialog.server.search.placeholder` = "Search servers"；清空按钮 `.settings-v2-tab-search-clear`（`position:absolute; inset-inline-end:6px`）
- 过滤用 **fuzzysort**，keys = `serverName(item)` 与 `item.http.url`
- 行：`div.settings-v2-servers-row`
  → `div.settings-v2-servers-lead`（`ServerHealthIndicator` + `div.settings-v2-servers-copy`：name + meta `v{version}` • username，无用户名回退 `server.row.noUsername` = "no username"）
  → `div.settings-v2-servers-actions`（`<Tag>` = `dialog.server.status.default` = "Default" + `ServerRowMenu`）
- 空态 `.settings-v2-servers-status`：无过滤 → `dialog.server.empty` = "No servers yet"；有过滤 → `palette.empty` = "No results found" 并回显 `"{{filter}}"`
- 排序（[`dialog-select-server.tsx`](https://github.com/anomalyco/opencode/blob/dev/packages/app/src/components/dialog-select-server.tsx) 的 `useServerManagementController`）：**当前服务器置顶**，其余按健康度 rank（healthy=0 / unknown=1 / unhealthy=2），同 rank 保持原序
- 新增/编辑走 `checkServerHealth`；legacy 布局下 `detectServerProtocol === "v2"` 会被拒绝；错误文案 `dialog.server.add.error` = "Could not connect to server"

### 5.5 添加/编辑服务器对话框（**专用组件，不是目录选择器**）

**源文件**：[`packages/app/src/components/settings-v2/dialog-server-v2.tsx`](https://github.com/anomalyco/opencode/blob/dev/packages/app/src/components/settings-v2/dialog-server-v2.tsx)

```
<Dialog fit class="settings-v2-server-dialog">
└─ DialogHeader hideClose > DialogTitle        "Add server" / "Edit server"
   DividerV2
   DialogBody class="flex w-full min-w-0 flex-1 flex-col px-4 pt-4 pb-2"
   DialogFooter
```
**字段顺序与字面量**：
1. `label.settings-v2-server-dialog-label` = `dialog.server.add.url` = **"Server address"**，placeholder `dialog.server.add.placeholder` = `http://localhost:4096`，`autofocus`
2. `dialog.server.add.name` = **"Server name (optional)"**
3. `div.grid.grid-cols-2.gap-4` 内：
   - `dialog.server.add.username` = **"Username (optional)"**，默认值常量 **`DEFAULT_USERNAME = "opencode"`**
   - `dialog.server.add.password` = **"Password (optional)"**，`type="password"`
- 全部字段 `appearance="large"`、`class="!w-full self-stretch"`
- **交互**：任意字段 `onKeyDown` 的 Enter（非 IME）→ `preventDefault()` + `controller.submitForm()`
- 错误行 `span.settings-v2-server-dialog-error`
- 按钮：`common.cancel` = "Cancel"；提交 add → `dialog.server.add.button` = "Add server"，busy → `dialog.server.add.checking` = "Checking..."，edit → `common.save` = "Save"
- 状态：`opened` signal；onMount `startAdd()` / `startEdit(server)`；`createEffect` 离开 form 模式时 `dialog.close()`；onCleanup `resetForm()`

### 5.6 键盘快捷键显示页（`settings-keybinds.tsx`）

- 分组顺序（常量 `GROUPS`）：`["General", "Session", "Navigation", "Model and agent", "Terminal", "Prompt"]`
  对应 key `settings.shortcuts.group.*` → `general` / `session` / `navigation` / `modelAndAgent` / `terminal` / `prompt`
- 分组归属 `groupFor(id)`：`command.palette`→General；`terminal.*`→Terminal；`model./agent./mcp.`→Model and agent；`file./fileTree.`→Navigation；`prompt.`→Prompt；`session./message./permissions./steps./review.`→Session；其余→General
- DOM：
```
div.settings-v2-tab-header.settings-v2-tab-header--stacked
├─ div.settings-v2-tab-header-row
│  ├─ h2.settings-v2-tab-title          "Keyboard shortcuts"
│  └─ ButtonV2 variant="ghost"          "Reset to defaults"（无 override 时 disabled）
├─ div.settings-v2-tab-search           TextInputV2 type="search" + 清空按钮
└─ div.settings-v2-tab-body
   └─ div.settings-v2-shortcuts.flex.flex-col.gap-8
      └─ 每组 div.settings-v2-section > h3.settings-v2-section-title + SettingsListV2
         └─ 行 div.flex.items-center.justify-between.gap-4.py-3.border-b.border-border-weak-base.last:border-none
            ├─ <span> 标题
            └─ button[data-keybind-id].settings-v2-keybind-button（active 加 --active）
```
- 字面量：未绑定 `settings.shortcuts.unassigned` = **"Unassigned"**；录制中 `settings.shortcuts.pressKeys` = **"Press keys"**；搜索占位 `settings.shortcuts.search.placeholder` = "Search shortcuts"；无结果 `settings.shortcuts.search.empty` = "No shortcuts found"（`.settings-v2-shortcuts-status { padding-block: 48px }`）
- **交互/状态**：点击按钮进入捕获（`command.keybinds(false)` **暂停全局快捷键**，`store.active = id`）；`Escape` 取消；`Backspace`/`Delete` → 设为 `"none"`；冲突 toast `settings.shortcuts.conflict.title` = "Shortcut already in use" / `description` = "`{{keybind}}` is already assigned to `{{titles}}`."；重置 toast `settings.shortcuts.reset.toast.title` = "Shortcuts reset"
- 搜索用 fuzzysort（keys `["title","keybind"]`，`threshold: -10000`）
- 键位串格式化在 `context/command.tsx` 的 `formatKeybind/formatKeybindParts`：**mac 用 `⌃⌥⇧⌘` 符号拼接，非 mac 用 `+` 连接**

---

## 6. 命令面板 Command palette

**源文件**
- [`packages/app/src/context/command.tsx`](https://github.com/anomalyco/opencode/blob/dev/packages/app/src/context/command.tsx)（键位 + 打开逻辑）
- [`packages/app/src/components/command-palette.ts`](https://github.com/anomalyco/opencode/blob/dev/packages/app/src/components/command-palette.ts)（数据模型）
- [`packages/app/src/components/dialog-command-palette-v2.tsx`](https://github.com/anomalyco/opencode/blob/dev/packages/app/src/components/dialog-command-palette-v2.tsx)
- [`packages/app/src/components/dialog-command-palette-v2.css`](https://github.com/anomalyco/opencode/blob/dev/packages/app/src/components/dialog-command-palette-v2.css)

### 6.0 ❗打开快捷键 —— **Ctrl+P 不是命令面板**

```ts
export const DEFAULT_PALETTE_KEYBIND = "mod+k,mod+shift+p"
```
| 平台 | 命令面板 | 文件选择器 |
|---|---|---|
| Windows / Linux | **Ctrl+K** 与 **Ctrl+Shift+P** | **Ctrl+P**（`file.open`，打开 legacy `DialogSelectFile`） |
| macOS | **Cmd+K** 与 **Cmd+Shift+P** | **Cmd+P** |

- `const PALETTE_ID = "command.palette"`；keydown 在 **document capture 阶段**处理；命中 `isPalette` 时 `preventDefault + stopPropagation` 后 `showPalette()`
- **palette 优先于可编辑元素的屏蔽逻辑**（`isEditableTarget`）；但 `suspended()`（快捷键录制中）或 `dialog.active` 时不响应
- `EDITABLE_KEYBIND_IDS = new Set(["terminal.toggle", "terminal.new", "file.attach"])`（这些在输入框内也生效）
- 会话头部搜索框（见 2.2）点击也走 `command.trigger("file.open")` → 即 **Ctrl+P 系**

### 6.1 数据模型（真实 union）

```ts
export type CommandPaletteEntry = {
  id: string
  type: "command" | "file" | "session"        // ← 只有这三类
  title: string
  description?: string
  keybind?: string
  category: string
  option?: CommandOption
  path?: string
  directory?: string
  sessionID?: string
  server?: ServerConnection.Key
  project?: LocalProject
  archived?: number
  updated?: number
}
```
- **没有 `model` 类别**（模型选择通过命令项 `model.choose` 等出现）
- `ENTRY_LIMIT = 5`；`COMMON_COMMAND_IDS = ["session.new","workspace.new","session.previous","session.next","terminal.toggle","review.toggle"]`
- id 前缀：`"command:" + option.id`、`"file:" + path`、`` `session:${server}:${session.id}` ``
- `uniqueCommandPaletteEntries` 按 id 去重（保留首个）
- **session 条目来源**（`createServerSessionEntries`）：**100ms 防抖**后
  `session.list({ parentID: null, search, limit: 50 }, { signal })`
  过滤 `!session.time.archived`；`title = session.title || untitled()`（`untitled()` = `command.session.new` = "New session"）；`description = project ? displayName(project) : getFilename(session.directory)`；`category = command.category.session` = "Session"；`updated = session.time.updated`

### 6.2 分组与标签

- `groups(entries)`：按 `entry.category` 顺序分组（`Map` 保序），**非空才渲染 group title**
- 类别字面量：`palette.group.commands` = **"Commands"**、`palette.group.files` = **"Files"**、`command.category.session` = **"Session"**
  → **session 结果挂在 `command.category.session` 分组下**（不是 `palette.group.*`），这解答了上一轮的疑问
- **空查询内容**：
  - `DialogCommandPaletteV2`（会话内）：`[...preferredCommandEntries(), ...recentFileEntries()]`
    - `preferredCommandEntries`：优先取 `COMMON_COMMAND_IDS`（存在则按该顺序排序，否则 `all.slice(0, ENTRY_LIMIT)`）
    - `recentFileEntries`：已打开 tab（**活动文件 tab 置顶**、去重）`.slice(0, 5)`
    - 另有 `rootFileEntries`（根目录文件排序后 5 个，导出但空查询未使用）
  - `DialogHomeCommandPaletteV2`（Home）：`commandEntries().slice(0, 5)`，**不含文件类**
- **非空查询**：`matchesEntry` 对 `[title, description, category]` 做 `toLowerCase().includes(query)`
  - Home = 命中命令 + session 结果
  - 会话内 = 命中命令 + session 结果 + `file.searchFiles(q)` 产生的 `file` 条目（`category = palette.group.files`）
- 占位符：`palette.search.placeholder` = **"Search files, commands, and sessions"**；Home 用 `palette.search.placeholder.home` = **"Search commands and sessions"**

### 6.3 面板外壳尺寸

```css
.command-palette-v2 { overflow: hidden }
/* 锚定到一个「居中 480px 高对话框」的顶边，使顶部固定、高度随内容增减 */
[data-component="dialog-v2"]:has(.command-palette-v2) { align-items: flex-start }
[data-component="dialog-v2"]:has(.command-palette-v2) [data-slot="dialog-container"] {
  width: min(calc(100vw - 24px), 640px);
  height: auto;
  min-height: 280px;
  max-height: min(calc(100vh - 96px), 480px);
  margin-top: max(48px, calc((100vh - 480px) / 2));
  border-radius: 12px;
  background: var(--v2-background-bg-base);
  box-shadow: var(--v2-elevation-floating);
}
```
**数值总结**：宽 `min(视口−24px, 640px)`；高 `auto`，`min 280px`，`max min(视口−96px, 480px)`；顶部外边距 `max(48px, (100vh−480px)/2)`；圆角 **12px**；阴影 `elevation-floating`。**居中偏上，顶部固定、底部随内容伸缩。**

### 6.4 内部 DOM 结构（精确）

```
Dialog class="command-palette-v2" size="large"
└─ DialogBody class="command-palette-v2-body"
   ├─ div.command-palette-v2-search                      padding:6px
   │  └─ TextInputV2 (appearance="large", autofocus, autocomplete="off", spellcheck=false,
   │                 leadingIcon=<Icon name="magnifying-glass"/>, onKeyDown=handleKeyDown)
   └─ ScrollView class="command-palette-v2-scroll" viewportRef → resultsRef
      └─ div.command-palette-v2-results [role="listbox"]
         └─ For(groupedEntries) → div.command-palette-v2-group          gap:1px
            ├─ Show(category) → div.command-palette-v2-group-title
            └─ For(entries) → PaletteRow:
               button.command-palette-v2-row.group [type=button][role="option"][aria-selected][data-active]
               ├─ type==="command":
               │  ├─ div.command-palette-v2-row-main > div.command-palette-v2-row-text
               │  │   ├─ span.command-palette-v2-title
               │  │   └─ Show(description) → span.command-palette-v2-description
               │  └─ Show(keybind) → KeybindV2 keys={formatKeybindParts(...)} variant="neutral"
               ├─ type==="session":
               │  ├─ div.command-palette-v2-row-main
               │  │  ├─ div.relative.shrink-0
               │  │  │  ├─ Show(open) → span[aria-hidden]
               │  │  │  │    "pointer-events-none absolute top-1/2 h-3 w-0.5 -translate-y-1/2
               │  │  │  │     rounded-[2px] bg-v2-background-bg-layer-04"
               │  │  │  │    style={{ right: "calc(100% + 4px)" }}        ← 已打开会话的竖条
               │  │  │  └─ SessionTabAvatar
               │  │  └─ div.command-palette-v2-row-text（title 的 classList: {"opacity-70": archived}）
               │  └─ Show(updated) → span.command-palette-v2-meta = getRelativeTime(...)
               └─ type==="file"（fallback）:
                  div.command-palette-v2-row-main
                  ├─ FileIcon class="command-palette-v2-row-icon size-4"
                  └─ div.command-palette-v2-file-path                 display:flex; align-items:baseline
                     ├─ span.command-palette-v2-file-dir   = getDirectory(path)
                     └─ span.command-palette-v2-file-name  = getFilename(path)
```

### 6.5 CSS 关键数值

| class | 关键声明 |
|---|---|
| `.command-palette-v2-body` | `display:flex; min-height:0; flex:1; flex-direction:column; gap:0; padding:0` |
| `.command-palette-v2-search` | `flex-shrink:0; padding:6px` |
| `.command-palette-v2-search [data-component="text-input-v2"]` | `width:100%; height:36px; border-radius:6px; outline:0; background: color-mix(in srgb, var(--v2-background-bg-layer-02) 60%, transparent); box-shadow:none; transition: background-color 120ms ease-in-out, box-shadow 120ms ease-in-out` |
| 同上 `:hover` / `:focus-within` | `background: var(--v2-background-bg-layer-02); box-shadow:none` |
| `… [data-slot="text-input-v2-value"]` | `border-radius:6px; background:transparent; gap:8px` |
| `… [data-slot="text-input-v2-leading-icon"]` | `padding-left: 12px` |
| `.command-palette-v2-scroll` | `min-height:0; flex:1` |
| `.command-palette-v2-results` | `display:flex; flex-direction:column; gap:16px; padding: 6px 6px 8px` |
| `.command-palette-v2-group` | `gap: 1px` |
| `.command-palette-v2-group-title` | `margin: 6px 0; padding: 0 12px; color: var(--v2-text-text-muted); font-size:13px; font-weight:440; line-height:16px; letter-spacing:-0.04px; user-select:none` |
| `.command-palette-v2-row` | `height:36px; width:100%; flex-shrink:0; gap:8px; padding: 0 12px; border:0; border-radius:6px; background:transparent; color: var(--v2-text-text-base); text-align:left; cursor:default; scroll-margin: 6px 0` |
| `.command-palette-v2-row[data-active]` / `:focus-visible` | `background: var(--v2-overlay-simple-overlay-hover)`（focus-visible 另加 `outline:none`） |
| `.command-palette-v2-row-main` | `display:flex; min-width:0; flex:1; align-items:center; gap:8px` |
| `.command-palette-v2-row-icon` | `flex-shrink:0; color: var(--v2-icon-icon-muted)` |
| `.command-palette-v2-row-text` | `display:flex; min-width:0; align-items:center; gap:6px` |
| `.command-palette-v2-title` | `13px / 530 / 16px / -0.04px`；ellipsis |
| `.command-palette-v2-description`, `-meta` | `13px / 440 / 16px / -0.04px`，`color: var(--v2-text-text-muted)`；`-meta` 加 `flex-shrink:0` |
| `.command-palette-v2-file-path` | `display:flex; align-items:baseline; 13px/16px/-0.04px` |
| `.command-palette-v2-file-dir` | `color: var(--v2-text-text-muted); font-weight:440`；ellipsis |
| `.command-palette-v2-file-name` | `flex-shrink:0; color: var(--v2-text-text-base); font-weight:530; white-space:nowrap` |
| `.command-palette-v2-state` | `display:grid; min-height:120px; place-items:center; 13px/440/16px/-0.04px`，`color: muted` |

**响应式 `@media (max-width: 640px)`（**移动端必须实现**）**：
```css
.command-palette-v2-row-text { flex-direction: column; align-items: flex-start; gap: 1px }
.command-palette-v2-description { max-width: 100% }
```
→ 手机竖屏时行内文字改**纵向堆叠**（标题一行、描述一行）。

本 CSS 引用的 `--v2-*`：`--v2-background-bg-base`、`--v2-background-bg-layer-02`、`--v2-text-text-base`、`--v2-text-text-muted`、`--v2-overlay-simple-overlay-hover`、`--v2-icon-icon-muted`、`--v2-elevation-floating`。

### 6.6 键盘 / 鼠标 / 选中行为

**键盘**（`handleKeyDown`，全部 `preventDefault`）：

| 键 | 行为 |
|---|---|
| `ArrowDown` | `move(1)` |
| `ArrowUp` | `move(-1)` |
| `Enter` | `props.select(activeEntry())` |
| `Escape` | `props.close()` |

- **没有 Tab 处理**（palette 内 Tab 未绑定）
- `move(delta)`：`count === 0` 直接 return；`setActive((i + delta + count) % count)` —— **首尾循环**；随后 `requestAnimationFrame(() => resultsRef?.querySelector("[data-active]")?.scrollIntoView({ block: "nearest" }))`

**鼠标**：
- `onMouseMove` **仅当 `movementX !== 0 || movementY !== 0`** 才 `onActive()`（避免键盘滚动时静止光标抢焦点）
- `onMouseDown` → `preventDefault()`（防止抢焦点）
- `onClick` → `select(item)`

**状态**：`query`、`active`（索引）；`createResource(query, loadItems, { initialValue: [] })`；`visibleEntries = uniqueCommandPaletteEntries(entries.latest ?? [])`（**加载中保留旧结果以避免闪烁**）；`createEffect` 在 `query()` / `visibleEntries()` 变化时 `setActive(0)`；另一个 `createEffect` 调 `props.highlight(activeEntry())`；`openSessions` = tabs store 中 `type === "session"` 的 `${server}\0${sessionId}` 集合（用于行首"已打开"竖条）。

**选中行为**（`createCommandPaletteModel`，Home 版本一致）：先 `state.committed = true` → `dialog.close()`，然后按 `type` 分派：

| type | 行为 |
|---|---|
| **command** | `item.option?.onSelect?.("palette")` |
| **session** | `directory = item.project?.worktree ?? item.directory`；`serverCtx.projects.open(directory)` + `touch`；`appTabs.addSessionTab({ server, sessionId })` + `appTabs.select(tab)`（Home 版包在 `startTransition` 内） |
| **file** | `openFile(path)` = `file.tab(path)` → `tabs().open(value)` → `file.load(path)` → 若 review 面板未开则 `view().reviewPanel.open()` → `layout.fileTree.setTab("all")` → `onOpenFile?.(path)` → `tabs().setActive(value)` |

- highlight 生命周期：`onHighlight` 返回 cleanup，切换/关闭时调用；若未 `committed`，`onCleanup` 也会清掉

**空态 / 加载态**：`Show when={visibleEntries().length > 0}` 的 fallback 是 `div.command-palette-v2-state`，内容 = `entries.loading ? t("common.loading") : t("palette.empty")` → **"Loading"** / **"No results found"**

### 6.7 未确证项

> **`未找到`**：**会话路由内 v2 命令面板的挂载宿主组件**。已确认 `DEFAULT_PALETTE_KEYBIND`、Home 侧 `command.palette` 注册（`home-sessions-controller.tsx` 的 `command.register("home.palette", …)`, `hidden: true`）、以及 Home / session 两个对话框组件都存在；但 `components/`、`components/session/`、`pages/session/`、`pages/session/v2/` 下均无 palette 宿主文件。`pages/session.tsx`（80 KB）与 `pages/layout.tsx`（87 KB）**本次未抓取**，因此**会话内 `command.palette` 的注册位置与触发按钮位置未确证**。v2 `titlebar.tsx` 分支只注册了 `home.toggle / tab.new / tab.close / tab.reopenClosed`，未注册 palette。

---


## 7. 会话抽屉 / 会话列表

**源文件**：[`packages/app/src/pages/home/home-sessions-view.tsx`](https://github.com/anomalyco/opencode/blob/dev/packages/app/src/pages/home/home-sessions-view.tsx)（Home 右栏即会话列表）；`packages/app/src/pages/layout.tsx`（旧版侧边栏）

- **Home 上的会话列表** = 第 1.3 节（`HomeSessionRow`，h-10 / 圆角 6 / hover `overlay-hover` / 前导 avatar / 打开的会话左侧 12×2px 竖条）
- **切换会话**：`onOpenSession(session, { background })`；中键或 Cmd/Ctrl+click → 后台开 tab 不导航（`shouldOpenSessionInBackground`）
- **新建会话入口**三处：
  1. Home 右栏悬浮按钮 `data-action="home-new-session"`（绝对定位 `right-0 top-[84px] / lg:top-[108px]`，`h-7 px-2`）
  2. 右栏空状态中央的 `ButtonV2 variant="neutral"`（`data-action="home-new-session"`）
  3. 项目行 hover 的 `IconButtonV2 data-action="home-project-new-session" icon="edit"`，以及项目右键菜单里的 `command.session.new`
- **归档**：`SHOW_HOME_SESSION_ARCHIVE = false`（硬编码关闭），按钮为 `data-action="home-session-archive"` + `IconV2 name="archive"`，文案 `common.archive`="归档"。逻辑在 [`packages/app/src/pages/home/home-session-archive.ts`](https://github.com/anomalyco/opencode/blob/dev/packages/app/src/pages/home/home-session-archive.ts)
- **旧版侧边栏（`layout.tsx`）** 的会话相关文案：`sidebar.project.recentSessions`="最近会话"、`sidebar.project.viewAllSessions`="查看全部会话"、`sidebar.project.clearNotifications`="清除通知"、`sidebar.empty.title`="没有打开的项目"、`sidebar.empty.description`="打开一个项目以开始使用"、`sidebar.menu.toggle`="切换菜单"、`sidebar.nav.projectsAndSessions`="项目和会话"
- **列表分组的排序/时间**：`common.time.justNow`="刚刚"、`common.time.minutesAgo.short`="{{count}}分钟前"、`hoursAgo.short`、`daysAgo.short`

---

## 8. 右侧面板：Review / Context / 文件 tab（**不是"Session / Changes / Context"**）

**源文件**
- [`packages/app/src/pages/session/session-side-panel.tsx`](https://github.com/anomalyco/opencode/blob/dev/packages/app/src/pages/session/session-side-panel.tsx)（**面板容器 + tab 列表，已全文核实**）
- [`packages/app/src/components/session/session-context-tab.tsx`](https://github.com/anomalyco/opencode/blob/dev/packages/app/src/components/session/session-context-tab.tsx)（Context tab 内容）
- [`packages/session-ui/src/components/session-review.tsx`](https://github.com/anomalyco/opencode/blob/dev/packages/session-ui/src/components/session-review.tsx)（diff 视图）
- [`packages/app/src/components/file-tree-v2.tsx`](https://github.com/anomalyco/opencode/blob/dev/packages/app/src/components/file-tree-v2.tsx)（文件树）

### 8.1 ❗ tab 名称 —— 不存在 "Session / Changes / Context" 三标签

**桌面端面板 tab（`session-side-panel.tsx` 实读，按 DOM 顺序）**：

| # | 内容 | 文案来源 | en |
|---|---|---|---|
| 1 | 侧栏开关槽 `session-review-v2-sidebar-toggle-slot` | `SessionReviewV2SidebarToggle` | — |
| 2 | review tab（`value="review"`） | `hasReview() ? "session.review.filesChanged"({count}) : "session.tab.review"` | **"Files Changed {n}"** 或 **"Review"** |
| 3 | context tab（`value="context"`） | `session.tab.context` | **"Context"** |
| 4 | 动态文件 tab（`value={SESSION_OPEN_FILE_TAB}`） | `command.file.open`，`italic` | **"Open file"** |
| 5 | 尾部 `+`（`IconButtonV2`，`Icon name="plus-small"`） | `command.file.open` | → `openFileBrowser()` |
| 6 | `session-review-v2-open-in-app-slot` | `<OpenInAppV2 directory=.../>` | — |

**移动端（`session.tsx` 的 `mobileTabs()`）只有 2 个 tab**：`session.tab.session` = **"Session"**、`hasReview() ? "Files Changed {n}" : session.review.change.other` = **"Changes"**。

> **结论**：`Session / Review / Context` 与 `Session / Changes` 是**两套不同断点的组合**。
> 用户描述的 "Session / Changes / Context" 三标签**在源码中不存在**（`未找到`）。
> 大概率是把**移动端两 tab（Session/Changes）**与**桌面端 Context tab**混记了 —— 建议按上表分别实现。

**多语言 key**（i18n 权威）：
| key | en | zh |
|---|---|---|
| `session.tab.session` | Session | 会话 |
| `session.tab.review` | Review | 审查 |
| `session.tab.context` | Context | 上下文 |
| `session.tab.unknown` | Unknown Session | 未知会话 |
| `session.panel.reviewAndFiles` | Review and files | 审查和文件 |
| `session.review.filesChanged` | Files Changed {{count}} | — |
| `session.review.change.one` / `.other` | Change / Changes | — |
| `command.file.open` | Open file | 打开文件 |
| `session.files.all` | All files | — |
| `session.files.empty` | No files | — |
| `session.files.selectToOpen` | Select a file to open | — |

### 8.2 面板容器与尺寸

```jsx
<aside id="review-panel"
       aria-label={language.t("session.panel.reviewAndFiles")}   // "Review and files" / "审查和文件"
       aria-hidden={!open()} inert={!open()}
       class="relative min-w-0 flex overflow-hidden"
       classList={{ "bg-v2-background-bg-base": v2, "rounded-[10px] shadow-[var(--v2-elevation-raised)] overflow-hidden": v2,
                    "h-full shrink-0": !stacked, "h-full min-h-0": stacked, "pointer-events-none": !open(),
                    "transition-[width] duration-[240ms] ease-[cubic-bezier(0.22,1,0.36,1)] will-change-[width] motion-reduce:transition-none": …,
                    "flex-1": reviewOpen() }}
       style={{ width: panelWidth() }}>            // "0px" | "auto" | `${fileTreeWidth()}px`
```
**关键数值**

| 项 | 值 |
|---|---|
| 宽度过渡 | `duration-240ms`、`ease-[cubic-bezier(0.22,1,0.36,1)]`、`will-change: width` |
| `SESSION_PANEL_WIDTH_MIN` | **450** |
| `REVIEW_PANE_WIDTH_MIN` / `REVIEW_PANE_WIDTH_MIN_SPLIT` | **480** / **800** |
| `sessionPanelWidthMax` | `max(450, available − (split ? 800 : 480))` |
| `FILE_TREE_WIDTH_MIN` | **240**，`max 480` |
| 终端堆叠 `ResizeHandle` | `min 100`、`max window.innerHeight * 0.6`、`collapseThreshold 50` |
| 打开条件 | `isDesktop = createMediaQuery("(min-width: 768px)")` |

### 8.3 Context tab 内容（`session-context-tab.tsx`）—— **不是"上下文中的文件列表"**

```
ScrollView class="@container h-full"
└─ div.px-6.pt-4.pb-10.flex.flex-col.gap-10
   ├─ 统计网格 grid grid-cols-1 @[32rem]:grid-cols-2 gap-4   ← 16 个 Stat
   ├─ Token 分解条
   ├─ System Prompt 块
   └─ Raw messages（Accordion）
```
- **16 个 `Stat`**（标签 `text-12-regular text-text-weak`，值 `text-12-medium text-text-strong`），key `context.stats.*`：
  **Session / Messages / Provider / Model / Context Limit / Total Tokens / Usage / Input Tokens / Output Tokens / Reasoning Tokens / Cache Tokens (read/write) / User Messages / Assistant Messages / Total Cost / Session Created / Last Activity**
- **Token 分解条**：标题 `context.breakdown.title` = **"Context Breakdown"**
  ```jsx
  <div class="h-2 w-full rounded-full bg-surface-base overflow-hidden flex">   // 高 8px 圆角条
    <div class="h-full" style={{ width: `${segment.width}%`, "background-color": BREAKDOWN_COLOR[key] }}/>
  ```
  颜色：system `var(--syntax-info)`、user `var(--syntax-success)`、assistant `var(--syntax-property)`、tool `var(--syntax-warning)`、other `var(--syntax-comment)`
  图例：`size-2 rounded-sm` 色块 + 文案 **System / User / Assistant / Tool Calls / Other** + 百分比
- **System Prompt**：`context.systemPrompt.title` = "System Prompt"，Markdown 渲染，容器 `border border-border-base rounded-md bg-surface-base px-3 py-2`
- **Raw messages**：`context.rawMessages.title` = "Raw messages"，`Accordion multiple`，每项 `[role] • [id]` + 时间 + `chevron-grabber-vertical`，内容为 JSON（`File mode="text"`）；右上 `Export session` 按钮（icon `download`）

> **`未找到`**：没有"每个文件的 token 归属"这种数据 → **"context panel 显示上下文中的文件列表"这一预期不成立**。

### 8.4 diff 视图（`session-review.tsx`，**`@pierre/diffs`**）

- **库**：`@pierre/diffs`（`packages/ui` 与 `packages/session-ui` 的 `dependencies` 均为 `"@pierre/diffs": "catalog:"`）。**不是自研 diff**。shiki 仅用于高亮。
- 渲染入口：
  ```jsx
  <Dynamic component={useFileComponent()} mode="diff"
           fileDiff={diff().fileDiff} preloadedDiff={diff().preloaded}
           diffStyle={diffStyle()} enableLineSelection enableGutterUtility
           media={{ mode: "auto", path: file, deleted, readFile }} />
  ```
- **`createDefaultOptions(style)` 默认值（逐字）**：
  ```
  theme: "OpenCode" | themeType: "system" | disableLineNumbers: false
  overflow: "wrap" | diffStyle: style ?? "unified"
  diffIndicators: "bars" | lineHoverHighlight: "both" | disableBackground: false
  expansionLineCount: 20 | hunkSeparators: "line-info-basic"
  lineDiffType: style === "split" ? "word-alt" : "none"
  maxLineDiffLength: 1000 | maxLineLengthForHighlighting: 1000
  disableFileHeader: true
  ```
- **尺寸变量**：`--diffs-font-family: var(--font-family-mono)`、`--diffs-font-size: var(--font-size-small)`、**`--diffs-line-height: 24px`**、`--diffs-tab-size: 2`、`--diffs-min-number-column-width: 3ch`、`--diffs-gap-block: 0`
- **行号 gutter**：`[data-column-number]`；`[data-indicators='bars'] [data-column-number][data-line-type='change-addition'|'change-deletion']::before { width: 2px }`（删除条 `background-color: var(--diffs-deletion-base)`）；`[data-background] [data-column-number] { --mix-light: 88%; --mix-dark: 80% }`；`[data-column-number] { cursor: default !important }`
- **增删底色**：
  ```
  --diffs-bg-addition-override: light-dark(
      color-mix(in lab, var(--diffs-bg) 33.333%, var(--diffs-addition-base)),
      color-mix(in lab, var(--diffs-bg) 60%,   var(--diffs-addition-base)))
  /* deletion 同构，33.333% / 60% */
  --diffs-bg-addition-number-override / -deletion-number-override   复用同值
  ```
- **选中/评论**：`--diffs-selection-base: var(--v2-background-bg-accent)`、`--diffs-selection-number-fg: var(--v2-text-text-accent)`、`--diffs-comment-bg: rgb(from var(--v2-background-bg-accent) r g b / 0.06)`、`--diffs-bg-selection: rgb(from var(--diffs-selection-base) r g b / 0.2)`
- **unified vs split**：`RadioGroup options={["unified","split"]}`，标签 `ui.sessionReview.diffStyle.unified` = **"Unified"** / `.split` = **"Split"**；默认 `props.diffStyle ?? (props.split ? "split" : "unified")` → **两种都支持**（此前认为"只有 unified"是错的）
- **每文件 Accordion**：`<Accordion.Item value={file} data-file={file} data-slot="session-review-accordion-item">` → `StickyAccordionHeader` + `Accordion.Trigger` → `session-review-trigger-content` / `-file-info` / `-file-name-container` / `-directory`（`\u202A…\u202C`）/ `-filename` / `-view-button` / `-trigger-actions` / `-change`（`data-type="added"|"removed"|"modified"`）/ `-diff-chevron` → `Accordion.Content data-slot="session-review-accordion-content"` → `session-review-diff-wrapper`
- **头部**：`data-slot="session-review-header"`，标题默认 `ui.sessionReview.title` = **"Session changes"**（另有 `title.git` = "Git changes"、`title.branch` = "Branch changes"、`title.lastTurn` = "Last turn changes"）；`Expand all` / `Collapse all`（`Button size="small" icon="chevron-grabber-vertical" class="w-[106px] justify-start"`）；滚动 `session-review-scroll`；列表 `session-review-list class="pb-6"`
- **大文件保护**：`MAX_DIFF_CHANGED_LINES = 500` → `session-review-large-diff`，文案 "Diff too large to render" / "Limit: {{limit}} changed lines. Current: {{current}} changed lines." / 按钮 "Render anyway"
- **懒挂载**：`REVIEW_MOUNT_MARGIN = 300`，占位 `session-review-diff-placeholder` `style={{ height: "160px" }}`
- `SessionReviewTab`（app 包装）负责滚动位置持久化（`view().setScroll("review", {x, y})`），默认 classes `root: "pr-3"`、`header: "px-3"`、`container: "pl-3"`

### 8.5 文件树（`file-tree-v2.tsx`）

- 容器 `data-component="file-tree-v2"` + `data-total-rows`；虚拟滚动 `createVirtualizer`：**`estimateSize: () => 28`**、**`gap: 2`**、`overscan: 10`
- 行：`data-slot="file-tree-v2-row"`（`as="button"`）
  - 缩进：**`INDENT_STEP = 16`**；目录 `rowPaddingStart = 8 + level*16`；文件 `level === 0 ? 8 : 8 + level*16 - 16`
  - 辅助线 `data-slot="file-tree-v2-guide"`，位置 `guideLineStart(level) = rowPaddingStart(level, "directory") + 8`
  - 展开箭头 `data-slot="file-tree-v2-chevron"` + `data-expanded`；点击 `file.tree.expand/collapse`
- **变更标记：不是 `+N/-N` 数字**，而是**单字母徽标**：
  ```jsx
  <span data-slot="file-tree-v2-change" data-change={kindChange(kind)}>{kindLabel(kind)}</span>
  // kindLabel: add → "A", del → "D", 其余 → "M"
  // kindChange: "added" | "deleted" | "modified"
  ```
  kind 由 side panel 计算：`diff.status === "added" ? "add" : diff.status === "deleted" ? "del" : "mix"`，并**逐级 merge 到父目录**（`merge(a,b)`：相同取同值，不同取 `"mix"`）。
  真正的 `+N/-N` 由 `<DiffChanges changes={diff}/>`（`@opencode-ai/ui/diff-changes`）在 **diff 头部 / 回合摘要**渲染。
- **文件树自己的 pill tabs**：`<Tabs variant="pill" value={fileTreeTab()} onChange={setFileTreeTabValue}>`
  - `value="changes"` → `session.review.filesChanged` = "Files Changed {n}"（legacy 为 `session.review.change.one|other`）
  - `value="all"` → `session.files.all` = **"All files"**
  - 内容区均 `class="bg-background-stronger px-3 py-0"`，树 `class="pt-3"`
  - `changes` 视图：`<FileTree allowed={diffFiles()} kinds={kinds()} draggable={false} active={activeDiff} onFileClick={focusReviewDiff}/>`；加载中显示 `common.loading` + `common.loading.ellipsis`
  - `all` 视图：`<FileTree modified={diffFiles()} kinds={kinds()} onFileClick={openTab}/>`；无文件 → `session.files.empty` = "No files"
- v2 文件浏览器侧栏：`SessionFilePanelV2` + `SessionReviewV2Sidebar`（filter 输入、宽度可拖），搜索 `file.searchFiles(value, { limit: 200, signal })`，结果列表 `SessionFileListV2`（`role="listbox"`），空态 `palette.empty` = "No results found"
- **空 tab 态**：`activeTab() === "empty"` → `<Mark class="w-14 opacity-10"/>` + `session.files.selectToOpen` = **"Select a file to open"**

### 8.6 打开/关闭 Context 面板（`openSessionContext`）

```ts
view.reviewPanel.open(view.reviewPanel.opened() ? "other" : "context-button")
if (layout.fileTree.opened() && layout.fileTree.tab() !== "all") layout.fileTree.setTab("all")
void tabs.open("context")
tabs.setActive("context")
```
→ 点 context ring 会：打开 review 面板 → 把**文件树切回 `all`** → 打开并激活 `context` tab。
**关闭**：`tabs().close("context")`；若 `reviewPanel.source() === "context-button"` 且无其它 tab，则连面板一起关。


---

## 9. Terminal 终端 / PTY（**已核实：Ghostty，不是 xterm**）

**源文件**
- [`packages/app/src/components/terminal.tsx`](https://github.com/anomalyco/opencode/blob/dev/packages/app/src/components/terminal.tsx)（23,706 B）
- [`packages/app/src/pages/session/terminal-panel-v2.tsx`](https://github.com/anomalyco/opencode/blob/dev/packages/app/src/pages/session/terminal-panel-v2.tsx)（面板 chrome）
- [`packages/app/src/utils/terminal-websocket-url.ts`](https://github.com/anomalyco/opencode/blob/dev/packages/app/src/utils/terminal-websocket-url.ts)（PTY 端点）
- [`packages/app/src/context/terminal.tsx`](https://github.com/anomalyco/opencode/blob/dev/packages/app/src/context/terminal.tsx)（状态模型）

### 9.1 终端实现（**Ghostty / libghostty wasm**）

**精确 import（源码实读）**：
```ts
import type { FitAddon, Ghostty, Terminal as Term } from "ghostty-web"
import { SerializeAddon } from "@/addons/serialize"      // opencode 自有 addon
// 懒加载：
shared = import("ghostty-web").then(async (mod) => ({ mod, ghostty: await mod.Ghostty.load() }))
```
- **源码中没有任何 `@xterm/xterm` 引用**
- **加载的 addon 只有 2 个**：`FitAddon`（来自 ghostty-web）+ `SerializeAddon`（自有）
- **`web-links` / `webgl` / `unicode11` / `search` 均未加载**
- vendored 侧交叉印证：独立 chunk `assets/ghostty-web-BXAOpYJJ.js`（**1,381,507 B**）+ `assets/wasm-CG6Dc4jp.js`（622,337 B）

**DOM：单 div 即容器**，ghostty 自行 `t.open(container)` 注入 canvas/textarea：
```jsx
<div ref={container} data-component="terminal" dir="ltr" data-prevent-autofocus tabIndex={-1}
     style={{ "background-color": terminalColors().background }}
     classList={{ "select-text": true,
                 "size-full px-6 py-3 font-mono relative overflow-hidden": true }} />
```
面板侧覆盖：`<Terminal … class="!px-[14px]" />` → **最终水平 padding 14px**。
**没有独立的 `terminal.css`**（`未找到`）；尺寸/字体全部来自 Tailwind 类 + 内联 `background-color`。

**构造参数（逐字）**：
```ts
new mod.Terminal({
  cursorBlink: true,
  cursorStyle: "bar",
  cols: restoreSize?.cols,
  rows: restoreSize?.rows,
  fontSize: 14,
  fontFamily: terminalFontFamily(settings.appearance.terminalFont()),
  allowTransparency: false,
  convertEol: false,
  theme: terminalColors(),
  scrollback: 10_000,
  ghostty: g,
})
```
**主题派生**：`resolveThemeVariant(variant, dark)` → 前景/光标取 `text-stronger`；背景（v2）`resolveV2Token(resolveThemeVariantV2(...), "v2-background-bg-base")`，legacy 用 `background-stronger`；`selectionBackground = withAlpha(text, dark ? 0.25 : 0.2)`。
兜底常量：light `#fcfcfc` / `#211e1e`(α0.2)；dark `#191515` / `#d4d4d4`(α0.25)。
`createEffect` 里 `setOptionIfSupported(term, "theme", colors)` 与 `"colorScheme"`；字体 / webview zoom 变化触发 `scheduleFit()`。

**UI 事件**（`useTerminalUiBindings`，capture 阶段）：`copy`/`paste`（`term.paste(text)`）、`pointerdown`（外部元素 blur + `focusTerminal()`）、`click` capture 开链接（**需 shift/ctrl/meta 且非 alt**；`getHoveredLinkText`，`file:` → `platform.openLocalFile`，否则 `openExternal`）、textarea focus/blur 切换 `cursorBlink`。

**快捷键**：`t.attachCustomKeyEventHandler` —— `ctrl+shift+c` → `document.execCommand("copy")` 并**吞掉**；其余键交给 `matchKeybind(parseKeybind(settings.keybinds.get("terminal.toggle") ?? "ctrl+`"), event)` 以放行 toggle 到父级。

### 9.2 PTY 接线（WebSocket + connect ticket）

**WebSocket 端点**（`terminal-websocket-url.ts`）：

| 版本 | 路径 | query |
|---|---|---|
| v1 | `${url}/pty/${id}/connect` | `directory` |
| v2 | `${url}/api/pty/${id}/connect` | `location[directory]` |

公共 query：`cursor=<seek>`、可选 `ticket`；v1 且非同源/带 `authToken` 时加 `auth_token=authTokenFromCredentials(...)`；协议 `https:` → `wss:`。

**建连前取 ticket**：
```ts
sdk().client.pty.connectToken({ ptyID, directory },
  { throwOnError: false, headers: { "x-opencode-ticket": "1" } })
// 200 → ticket；404/405 → 无 ticket；403 → terminal.connectTicket.csrfError
```

**生命周期 API**：
| 操作 | API |
|---|---|
| 创建 | `sdk.client.pty.create({ title })` / `sdk.api.pty.create({ location, title })` |
| 更新 | `pty.update({ ptyID, location, title, size: { cols, rows } })` |
| 关闭 | `pty.remove({ ptyID })` / `pty.remove({ ptyID, location })` |
| 探测 | `pty.get(...)`（v2 判 `data.status === "exited"` / `PtyNotFoundError`；v1 判 `response.status === 404`） |

**输入**：`t.onData(data => { if (ws?.readyState === WebSocket.OPEN) ws.send(data) })`；`onKey` 中 Enter 触发 `props.onSubmit?.()`。

**输出（二进制控制帧协议）**：`socket.binaryType = "arraybuffer"`
- `ArrayBuffer` 且**首字节为 `0`** → 视为控制帧，payload 是 JSON `{ cursor }` → 更新 `cursor/seek`
- 字符串帧 → `output.push(data)`（经 `terminalWriter` 批处理）并 `cursor += data.length`
- **重连时用 `cursor` 续传**（断点恢复）

**resize**：`t.onResize(size => scheduleSize(size.cols, size.rows))` → 首次立即 `pushSize`，之后 **100ms 去抖** → v2 `sdk().api.pty.update({ ptyID, location: { directory }, size: { cols, rows } })`

**重连退避**：`close` 且 `code !== 1000` → `retry(err)`，`ms = Math.min(250 * 2 ** Math.min(tries, 4), 4_000)`；先 `gone()` 检测 PTY 是否已退出（退出则 `onConnectError`）；连上后 `tries = 0`、`onConnect?.()`、重发 size；若 `t.getMode(2031)` 则写 `"\x1b[?996n"`。

**现场恢复**：`restore = pty.buffer`、`restoreSize = { cols, rows }`、`scrollY`；onCleanup 时 `SerializeAddon.serialize()` 写回 `onCleanup({ id, buffer, cursor, rows, cols, scrollY })`。

**状态模型**（`context/terminal.tsx`）：
- `LocalPTY = { id, title, titleNumber, rows?, cols?, buffer?, scrollY?, cursor? }`
- **`MAX_TERMINAL_SESSIONS = 20`**
- 按 workspace 缓存（`getWorkspaceTerminalCacheKey(dir, scope)`，`"__workspace__"`）
- `sdk.event.on("pty.exited", …)` 自动移除
- `update` 会把 title/size 推给服务器，失败时回滚本地状态

### 9.3 面板 chrome（`terminal-panel-v2.tsx`）

```jsx
<aside ref={root} id="terminal-panel" role="region"
       aria-label={language.t("terminal.title")}
       aria-hidden={!opened()} inert={!opened()}
       class="relative shrink-0 overflow-hidden bg-v2-background-bg-base">
```
- 高度 `panelHeight()`：桌面 `stacked ? ${pane()}px : "100%"`；移动 `opened ? ${pane()}px : "0px"`
- `pane = Math.min(layout.terminal.height(), window.visualViewport.height * 0.6)`
- **Tab 条**：`<Tabs variant={newLayout ? "normal" : "alt"} value={terminal.active()} onChange={id => terminal.open(id)} class={newLayout ? "!h-[52px] !flex-none" : "!h-auto !flex-none"}>` → **v2 高 52px**（legacy `h-10`）
- **Tab**（`SortableTerminalTabV2`）：`Tabs.Trigger value={pty.id}`，标题 `terminal.title.numbered` = **"Terminal {{number}}"** / `terminal.title` = "Terminal"
  - **双击进入标题编辑**：`input` 覆盖层 `bg-v2-background-bg-layer-01 rounded-[6px] shadow-[inset_0_0_0_0.5px_var(--v2-border-border-muted)] px-2`；Enter 保存 / Escape 取消
  - **右键** `MenuV2.Context` 菜单：Rename / Close
- **关闭**：`IconButton icon="close-small" variant="ghost" class="h-5 w-5"`，aria-label `terminal.close` = **"Close terminal"**；`onMiddleClick={close}`；**最后一个关闭时同时关闭面板**
- **新建**：`IconButton icon="plus-small" variant="ghost" iconSize="large" onClick={() => terminal.new({ focus: true })}`，aria-label `command.terminal.new` = **"New terminal"**，tooltip 附 `command.terminal.new.description` = "Create a new terminal tab" + `KeybindV2 keys={command.keybindParts("terminal.new")}`（**keybind 字符串本身 `未找到`**）
- **打开且 0 个 PTY 时自动 `terminal.new()`**（`autoCreated` 标记）
- **拖拽排序**：`@dnd-kit/solid`，`PointerActivationConstraints.Distance({ value: 4 })`，`preventActivation` 排除 `[data-slot="tabs-trigger-close-button"], input, [contenteditable="true"]`，`AutoScroller.configure({ acceleration: 8, threshold: { x: 0.05, y: 0 } })`，`onDragEnd → terminal.move(id, index)`
- 加载态：`terminal.loading` = **"Loading terminal..." / "正在加载终端..."**

### 9.4 挂载 / 保活策略（**Android 需注意**）

```jsx
<Show when={opened() && terminal.active()} keyed>
  <div id={`terminal-wrapper-${id}`} class="absolute inset-0">
    <Terminal autoFocus={terminal.focusRequested(id)} onAutoFocus={…consumeFocus}
              onConnect={…markTerminalConnected/trim} onCleanup={ops.update}
              onConnectError={…recoverTerminal/clone} />
```
- **关闭时组件被卸载（不是"隐藏但保持挂载"）**
- 恢复依赖 **`SerializeAddon` 序列化 buffer** + `LocalPTY` 持久化（`Persist.serverWorkspace(scope, dir, "terminal", legacy)`）
- 断连时 `recoverTerminal` 会 `ops.clone(id)` **创建一个新 PTY** 并沿用 title/titleNumber

### 9.5 文案（i18n）

| key | en | zh |
|---|---|---|
| `terminal.loading` | Loading terminal... | 正在加载终端... |
| `terminal.title` | Terminal | 终端 |
| `terminal.title.numbered` | Terminal {{number}} | 终端 {{number}} |
| `terminal.close` | Close terminal | 关闭终端 |
| `terminal.connectionLost.title` | Connection lost | 连接已丢失 |
| `terminal.connectionLost.description` | — | 终端连接已中断。这可能发生在服务器重启时。 |
| `terminal.connectionLost.abnormalClose` | — | WebSocket 异常关闭：{{code}} |
| `terminal.connectTicket.csrfError` | — | PTY 连接票证因来源或 CSRF 检查被拒绝。请检查服务器 CORS 配置。 |
| `terminal.connectTicket.statusError` | — | PTY 连接票证失败，状态为 {{status}} |
| `command.terminal.toggle` | — | 切换终端 |
| `command.terminal.new` | New terminal | 新建终端 |
| `command.terminal.new.description` | Create a new terminal tab | 创建新的终端标签页 |

→ **关键架构结论**：终端走 **WebSocket + connect-ticket（票证）**；票证可能因 **CSRF/Origin 检查**被拒。**Android 壳已用原生反向代理解决 CORS**（见第 0 节引言里的 Android 定制文案）。

---

## 9b. Tab bar 与 `+` 按钮

**源文件**
- [`packages/app/src/components/titlebar.tsx`](https://github.com/anomalyco/opencode/blob/dev/packages/app/src/components/titlebar.tsx)
- [`packages/app/src/components/titlebar-tab-strip.tsx`](https://github.com/anomalyco/opencode/blob/dev/packages/app/src/components/titlebar-tab-strip.tsx)
- [`packages/app/src/components/titlebar-tab-nav.tsx`](https://github.com/anomalyco/opencode/blob/dev/packages/app/src/components/titlebar-tab-nav.tsx) + [`titlebar-tab-nav.css`](https://github.com/anomalyco/opencode/blob/dev/packages/app/src/components/titlebar-tab-nav.css)
- [`packages/app/src/components/titlebar-tab-popover.tsx`](https://github.com/anomalyco/opencode/blob/dev/packages/app/src/components/titlebar-tab-popover.tsx) + `.css`
- [`packages/app/src/context/tabs.tsx`](https://github.com/anomalyco/opencode/blob/dev/packages/app/src/context/tabs.tsx)

### 9b.1 tab bar DOM（v2）

```jsx
<div data-slot="titlebar-tabs" class="relative min-w-0">
  <div data-slot="titlebar-tabs-scroll"
       class="flex min-w-0 flex-row items-center gap-1.5 overflow-x-auto no-scrollbar [app-region:no-drag]">
    <DragDropProvider …>
      <div data-titlebar-tab-list class="flex w-full min-w-0 flex-row items-center">
        <div data-titlebar-tab-slot data-tab-key={…} data-active={…}
             class="relative flex w-56 min-w-7 max-w-56 flex-shrink">
          <div data-titlebar-tab data-slot="titlebar-tab-item" data-state="pressed"
               class="group relative flex h-7 w-full min-w-0 select-none flex-row items-center gap-1.5
                      overflow-hidden whitespace-nowrap rounded-[6px] px-1.5 [container-type:inline-size]">
            <a data-slot="tab-link" data-titlebar-tab-link
               class="flex h-full min-w-0 flex-1 flex-row items-center gap-1.5 text-[13px] font-medium
                      text-v2-text-text-faint group-data-[active='true']:text-v2-text-text-base">
            <div data-slot="tab-close">
              <IconButtonV2 size="small" variant="ghost-muted" icon={<IconV2 name="xmark-small"/>} class="hover-reveal"/>
            </div>
          </div>
        </div>
      </div>
    </DragDropProvider>
  </div>
  <div data-slot="titlebar-tabs-fade-left"  class="… absolute inset-y-0 left-0 z-10 w-6
       bg-[linear-gradient(to_right,var(--v2-background-bg-deep),transparent)]"/>
  <div data-slot="titlebar-tabs-fade-right" class="… absolute inset-y-0 right-0 z-10 w-6
       bg-[linear-gradient(to_left,var(--v2-background-bg-deep),transparent)]"/>
</div>
```

**标题栏本体**：`<header data-slot="titlebar-v2" class="shrink-0 relative flex flex-row h-9 bg-v2-background-bg-deep overflow-visible">`（**v2 高 36px**；legacy `h-10`=40px）。内层 `div.h-full.flex-1.overflow-hidden.flex.flex-row.items-center.gap-1.5.px-2.md:pr-3`。
v2 标题栏从左到右：`ChannelIndicator`（DEV 徽标）→ Windows/Linux `WindowsAppMenu` → Home 按钮（`grid-plus`，keybind `home.toggle = mod+b`）→ `TitlebarTabStrip` → **`+`** → `flex-1` → `TitlebarV2Right`。

### 9b.2 CSS 关键数值（`titlebar-tab-nav.css`）

| 项 | 值 |
|---|---|
| tab 高度 | **`h-7` = 28px** |
| 圆角 | `rounded-[6px]` |
| 内边距 | `px-1.5` = 6px |
| 图标/标题间距 | `gap-1.5` = 6px |
| 列表间距 | `gap: 6px` |
| 槽宽 | `w-56` = **224px**；最小 `min-w-7` = 28px |
| 标题字号 | `text-[13px]`、`leading-4`（16px） |
| 关闭按钮 | `position:absolute; top:4px; inset-inline-end:4px; height:20px; width:20px`；`[data-editing="true"]` 时 `display:none` |

**颜色状态机**：
```css
[data-titlebar-tab] { --tab-base: var(--v2-background-bg-deep); --tab-overlay: transparent;
                      background: linear-gradient(var(--tab-overlay),var(--tab-overlay)), var(--tab-base) }
/* hover（且非 pressed/dragging/editing） */
--tab-base: var(--v2-background-bg-layer-02); --tab-overlay: var(--v2-overlay-simple-overlay-hover)
/* [data-state="pressed"] | [data-dragging="true"] | [data-editing="true"] */
--tab-overlay: var(--v2-overlay-simple-overlay-pressed)
```
**相邻分隔线**：
```css
[data-titlebar-tab-slot]:not(:first-child):not([data-active="true"])::before {
  content:""; position:absolute; top:8px; inset-inline-start:-3.75px;
  width:1.5px; height:12px; border-radius:9999px; background: var(--tab-separator) }
/* --tab-separator: var(--v2-background-bg-layer-03)；[data-color-scheme="dark"] 下用 layer-02 */
/* active 槽与其后槽、hover 槽两侧隐藏 */
```
**标题溢出渐隐**：
```css
[data-title-overflow="true"] … [data-slot="tab-link"] {
  mask-image: linear-gradient(to right, black 0,
    black calc(100% - var(--tab-title-fade-offset) - 16px),
    transparent calc(100% - var(--tab-title-fade-offset)), transparent 100%) }
/* --tab-title-fade-offset: 4px；hover/active 时 24px；RTL 用 to left */
```
**滚动渐隐（纯 CSS scroll-driven，无 JS 状态）**：
```css
[data-slot="titlebar-tabs"] { timeline-scope: --titlebar-tabs-scroll }
/* scroll-timeline: --titlebar-tabs-scroll x */
/* animation-range: 0 0.1px  /  calc(100% - 1.1px) calc(100% - 1px) */
```
**窄容器容器查询**：`@container (max-width: 64px)` → 隐藏标题、链接居中、关闭按钮 `left:50%; transform:translateX(-50%)`。

`TitlebarTabStrip` 逻辑：`refreshOverflow()` 用 `scrollRef.scrollWidth > scrollRef.clientWidth` 驱动 `forceTruncate`；`createResizeObserver([scrollRef, listRef], …)` + rAF 合帧。

### 9b.3 Tab 数据模型

```ts
type Tab = SessionTab | DraftTab                                  // ← 只有两种
type SessionTab = { type: "session"; server: ServerConnection.Key; sessionId: string }
type DraftTab   = { type: "draft"; draftID: string; server; directory; worktree? }
```
- **没有 file / terminal / diff tab 类型**（文件 tab 属会话侧栏，终端 tab 属终端面板，各自独立模型）
- 标识：`tabKey(tab) = tab.type === "draft" ? `draft:${draftID}` : `${server}\n${tabHref(tab)}``
- `tabHref`：draft → `/new-session?draftId=…`；否则 `sessionHref(server, sessionId)`
- 持久化：`Persist.window("tabs")`、`"tabs.recent"`、`"tabs.info"`、`"tabs.closed"`；关闭入 `closed` 栈以支持 `reopenClosedTab()`；切服务端时清理
- 可见性：`SessionTabSlot` 仅在 session 已解析 / 缺失 / 有持久化 title 时可见（`onVisibleChange`），影响索引与快捷键

### 9b.4 ❗`+` 按钮行为 —— **不打开菜单**

```jsx
<TooltipV2 placement="bottom"
           value={<>{language.t("command.session.new")}<KeybindV2 keys={newTabTooltipKeybind(command)} variant="neutral"/></>}>
  <IconButtonV2 type="button" variant="ghost-muted" size="large" class="shrink-0"
                icon={<IconV2 name="plus"/>} onClick={openNewTab}
                aria-label={language.t("command.session.new")} />
</TooltipV2>
```
文案 = **"New session" / "新建会话"**。**点击直接新建，没有弹层。**

`openNewTab()` 分支优先级：
1. 当前是 session 路由 → 复制当前 session tab 的 prompt model 并 `tabs.newDraft({ server, directory }, "", model)`
2. 当前 tab 是 draft → 同理 `newDraft`
3. home 路由 → 用 `layout.home.selection()` + 已连接 server/project 建 draft
4. `layout.projects.list()[0]`
5. 任一已连接 server 的第一个项目
6. 全无 → return

**相关 keybind**（`command.register`）：
| id | keybind | title |
|---|---|---|
| `tab.new` | `mod+t,mod+n` | New session |
| `tab.close` | `mod+w` | Close tab |
| `tab.reopenClosed` | `mod+shift+t` | Reopen closed tab |
| `home.toggle` | `mod+b` | Home |
| `tab.prev` | `mod+option+ArrowLeft,ctrl+shift+tab` | — |
| `tab.next` | `mod+option+ArrowRight,ctrl+tab` | — |
| `tab.1`…`tab.9` | `mod+1`…`mod+9` | 全部 `hidden: true` |

> **注意**：菜单式的 `+` 只存在于两处 —— **侧栏面板**的 `+`（打开文件浏览器，见 8.1）与**终端面板**的 `+`（新建终端，见 9.3）。**标题栏 tab bar 的 `+` 没有菜单**。
> **`tabPlusCreatesSession` 这个 Android 定制开关正是用来切回/切离这个行为**（见第 0 节与 5.2）。

### 9b.5 Hover 预览弹层（`TabPreviewPopover`，**不是 `+` 的菜单**）

- Kobalte `HoverCard`：`placement="bottom-start"`、`gutter={6}`、`ignoreSafeArea`
- 常量：`OPEN_DELAY = 2_000`、`CLOSE_DELAY = 0`、`SKIP_WINDOW = 500`（关闭后 500ms 内 hover 相邻 tab **立即显示**，`data-instant` 抑制动画）
- 内容：**宽 256px**、`padding: 12px`、`gap: 6px`、`border-radius: 6px`、`box-shadow: var(--v2-elevation-floating)`、`pointer-events: none`、`data-component="session-tab-popover"`
  - slot `header`：`project`（13px/440/`--v2-text-text-muted`）、`title`（13px/530/`--v2-text-text-base`）
  - slot `row` / `detail`：路径（home 前缀替换为 `~`，`overflow-wrap:anywhere`）
  - slot `server`：仅多 server 连接时显示
- 触发抑制 `previewBlocked()`：`dragging || editing || menu.open || pressed || !session()`
- **打开 tab 的右键菜单**（`MenuV2.Context`）项：`common.rename` = "Rename"、`common.closeTab` = "Close tab"

### 9b.6 手势 / 中键 / 拖拽

- **中键**：`const MIDDLE_MOUSE_BUTTON = 1`；`onMouseDown` 中键 `preventDefault() + stopPropagation()`；`onAuxClick` 中键 → `closeTab()`
- **导航**：link 的 `onMouseDown`（button 0、非 editing、未被 `suppressNavigation()` 抑制）**立即 `onNavigate()`**（源码注释："shave the press-release delay"）；`onClick` 仅在 `event.detail === 0`（键盘激活）时导航
- **重命名**：标题 `onDblClick={openRename}`（受 `canOpenTabRename(dragging, editing, pending)` 保护）→ `contenteditable`，rAF 聚焦并全选；Enter 保存 / Escape 还原 / blur 保存 / 点击 tab 外 capture `pointerdown` 保存；走 `ctx.sdk.api.session.rename({ sessionID, title })`，失败回滚 title 并 toast `common.requestFailed`
- **关闭按钮**：`data-slot="tab-close"`；`isTabCloseTarget(target) = target.closest('[data-slot="tab-close"]')` 用于阻止拖拽启动
- **拖拽排序**（`@dnd-kit/solid`）：
  ```ts
  useSortable({ id: tabKey, index })
  PointerSensor.configure({
    activationConstraints: [new PointerActivationConstraints.Distance({ value: 4 })],
    preventActivation: (e) => !canStartTabDrag(e.pointerType)
      || isTabCloseTarget(e.target) || e.target.closest('[contenteditable="true"]'),
  })
  canStartTabDrag = pointerType !== "touch"      // ← 触摸禁止拖拽排序
  modifiers: [RestrictToHorizontalAxis, RestrictToElement.configure({ element: () => listRef })]
  plugins: 去掉 Accessibility；AutoScroller.configure({ acceleration: 8, threshold: { x: 0.05, y: 0 } });
           Feedback.configure({ dropAnimation: null })
  ```
- `onDragStart` 先 `props.onNavigate(tab, tabEl)`；`onDragEnd` 若 `!event.canceled && isSortable(source) && initialIndex !== index` → `arrayMove(current, initialIndex, index)` → `props.onReorder(mergeVisibleTabOrder(allKeys, visibleIds, nextVisible))` → `tabsStoreActions.reorder(keys)`（按 key 重建 store，**长度不一致则放弃**）
- `titlebar-tab-order.ts`：`adjacentTabKey(order, current, offset)` 循环取上/下一个（供 `tab.prev` / `tab.next`）；`mergeVisibleTabOrder(all, current, next)` 把可见子序列换位映射回完整列表

> **`待确认`**：`terminal.tsx` 的 import 语句、addon 清单、fit/webgl/theme 配置、`/pty` 端点细节、cols/rows resize 协议 —— 本次未逐行读取该文件。**Ghostty 集成 + WebSocket + connect ticket 已确证；addon 细节未确证，不要臆造。**

---

## 10. Project 项目页 / 目录选择

**源文件**
- `packages/app/src/pages/home/home-projects-view.tsx`（已全文获得，见 1.2）
- [`packages/app/src/components/dialog-select-directory-v2.tsx`](https://github.com/anomalyco/opencode/blob/dev/packages/app/src/components/dialog-select-directory-v2.tsx) + [`dialog-select-directory-v2.css`](https://github.com/anomalyco/opencode/blob/dev/packages/app/src/components/dialog-select-directory-v2.css)
- [`packages/app/src/components/dialog-edit-project-v2.tsx`](https://github.com/anomalyco/opencode/blob/dev/packages/app/src/components/dialog-edit-project-v2.tsx)

### 10.1 目录选择对话框文案（`dialog.directory.*`）

| key | zh |
|---|---|
| `dialog.directory.search.placeholder` | **搜索文件夹** |
| `dialog.directory.empty` | **未找到文件夹** |
| `dialog.directory.action.selectFile` | **选择文件** |
| `dialog.directory.action.selectFolder` | **选择文件夹** |
| `dialog.directory.root` | **根目录** |
| `dialog.directory.parent` | **上级目录** |
| `dialog.directory.readError` | **无法读取此文件夹** |

→ 结构推断（有直接 key 证据）：**搜索框 + 当前路径（根目录 / 上级目录导航）+ 子项列表 + 底部「选择文件夹」确认按钮**，并支持"选择文件"模式（同一对话框双用）。

### 10.2 添加项目流程

1. 入口：`data-action="home-add-project"`（图标 `folder-add-left`）或空状态首行 `data-action="home-add-project-row"`
2. 点击 → `onChooseProject(server)` → 打开目录选择对话框
3. 确认 → `onAddProjects(server, directories[])`（`recently closed` 行也走同一入口，传入单个 worktree）
4. 若添加的目录同时要开新会话 → `onOpenProjectNewSession(server, worktree)`

### 10.3 编辑项目对话框（`dialog.project.edit.*`）

| key | en | zh |
|---|---|---|
| `dialog.project.edit.title` | Edit project | **编辑项目** |
| `dialog.project.edit.name` | Name | 名称 |
| `dialog.project.edit.icon` | Icon | 图标 |
| `dialog.project.edit.icon.alt` | — | 项目图标 |
| `dialog.project.edit.icon.hint` | — | 点击图片或拖拽 |
| `dialog.project.edit.icon.recommended` | — | 推荐：128x128 像素 |
| `dialog.project.edit.color` | — | 颜色 |
| `dialog.project.edit.color.select` | — | {{color}} 颜色 |
| `dialog.project.edit.worktree.startup` | — | **workspace 启动脚本** |
| `dialog.project.edit.worktree.startup.description` | — | 新建 workspace (Git worktree) 后运行 |
| `dialog.project.edit.worktree.startup.placeholder` | — | 例如：. 打包 |

→ 字段：**名称 + 图标（点击/拖拽上传，推荐 128×128）+ 颜色 + workspace 启动脚本**。

### 10.4 项目头像 `ProjectAvatar`

- 来自 `@opencode-ai/ui/v2/project-avatar-v2`
- 有 `variant`（`getProjectAvatarVariant(project.icon?.color)`）与 `outline`（`variant="outline"`，用于"最近关闭"行）
- 头像色板 token：`--v2-avatar-bg-{blue,cyan,gray,green,orange,pink,purple,red,yellow}` / `--v2-avatar-border-*` / `--v2-avatar-fg: #fff`
  - dark：blue `#3250df`、cyan `#0096b8`、green `#2eaf5a`、orange `#ee7330`、pink `#e4429e`、purple `#623be2`、red `#d92e3c`、yellow `#e7af36`、gray `#5c5c5c`
  - light：blue `#263fa9`、cyan `#005a6e`、green `#196130`、orange `#723d22`、pink `#8c2d61`、purple `#361f83`、red `#7a1f23`、yellow `#68552b`、gray `#5c5c5c`

---

## 11. 新会话页（new-session）

**源文件**
- [`packages/app/src/pages/new-session.tsx`](https://github.com/anomalyco/opencode/blob/dev/packages/app/src/pages/new-session.tsx)
- [`packages/app/src/pages/new-session/new-session-view.tsx`](https://github.com/anomalyco/opencode/blob/dev/packages/app/src/pages/new-session/new-session-view.tsx)

```jsx
<div class="relative size-full overflow-hidden flex flex-col">
  <NewSessionStatus mount={rightMount} visible={settings.visibility.status}/>   ← 状态按钮 Portal 到 titlebar 右槽
  <div class="flex-1 min-h-0 flex flex-col gap-2 p-2">
    <NewSessionView/>
```
```jsx
<div class="@container relative flex flex-col min-h-0 h-full flex-1">
  <div data-component="session-new-design"
       class="relative flex-1 min-h-0 overflow-hidden rounded-[10px] bg-v2-background-bg-deep">
    <div class="absolute inset-x-0 top-[25.375%] flex justify-center px-6">   ← 垂直定位在 25.375%
      <div class={NEW_SESSION_CONTENT_WIDTH}>
        <WordmarkV2 class="h-auto w-full text-v2-background-bg-inverse"/>        ← 品牌字标
        <div class="mt-8 flex flex-col gap-8">                                  ← 间距 32px
          <PromptInputV2Composer/>
          (project 为空) <PromptProjectAddButton/>
          (project 已选) div.flex.min-h-7.items-center.justify-center.gap-0.text-v2-text-text-faint.sm:flex-row
                          <PromptProjectSelector placement="bottom"/>
                          <PromptWorkspaceSelector/> | <PromptGitStatus/>
```
- 内容宽度常量：`NEW_SESSION_CONTENT_WIDTH`（来自 `@/pages/session/new-session-layout`）—— **具体数值未确认**
- **ProviderTip**（底部提示条）：
  - 仅当 `provider_ready && persistedReady && providers.paid().length === 0 && 距上次 dismiss ≥ 30 天` 时显示
  - 容器 `div.pointer-events-none.absolute.inset-x-0.bottom-4.flex.justify-center.px-10`
  - 内层 `div[data-component="provider-tip"].group/provider-tip.relative.flex.h-6.max-w-full.items-center` → **高 24px**
  - 文字按钮：`h-6 rounded-[4px] pl-1.5 text-[13px].leading-none.tracking-[-0.04px].text-v2-text-text-faint` + 右侧 `chevron-down`（`size-6`，`-rotate-90`）
  - 文案 `home.providerTip` = **"Connect to 75+ providers to use other models, including Claude, GPT, Gemini, etc"** / **"连接 75 个以上的提供商，使用包括 Claude、GPT、Gemini 在内的更多模型"**
  - 关闭按钮 `IconV2 name="xmark-small"`，`hover-reveal`（默认透明，hover 250ms 延迟后显示，150ms 淡入；focus-within 立即显示）
  - 点击文字 → `dialog.show(<DialogConnectProvider/>)`（动态 import）
- 相关文案：`session.new.title`="Build anything"/"构建任何内容"、`session.new.project.new`="新建项目"、`session.new.project.search`="搜索项目"、`session.new.project.add`="添加项目"、`session.new.worktree.main`="主分支"、`mainWithBranch`="主分支（{{branch}}）"、`session.new.worktree.create`="创建新的 worktree"、`session.new.workspace.runIn`="运行会话的位置"、`session.new.workspace.triggerLocal`="本地"、`session.new.workspace.local`="本地仓库"、`session.new.workspace.existing`="工作区…"、`session.new.git.none`="无 Git"、`session.new.lastModified`="最后修改"

---

## 12. 移动端 / Android 壳相关（从 bundle 与源码确证）

1. **`settings.general.row.mobileTitlebarBottom`**：title「底部导航」，desc「在移动设备上将标题栏和会话标签页置于屏幕底部」 → **标题栏可以沉到屏幕底部**
2. **[Android 定制] `tabPlusCreatesSession`**：控制 tab bar 的 `+` 行为（见第 9b 节）
3. **`@container` + `cqh` 单位被大量使用**（`h-[calc(100cqh-56px)]`、`min-h-[calc(100cqh-72px)]`、`[container-type:size]`）→ 布局按**容器高度**而非视口高度响应，Android 需注意等价实现。**`cq` 单位实际只用到 `100cqh`**；容器查询只有 4 个（见 0.0）
4. **`createMediaQuery("(min-width: 768px)")`**（`session-context-usage.tsx`、`session-side-panel.tsx`）作为桌面判定 → **移动端不渲染右侧面板与文件树**
5. `dialog-v2` 容器宽度上限 `640px`（命令面板）；`2xl:max-w-[1000px]`（composer 居中时）；`md:max-w-200` = 800px
6. 命令面板在 `≤640px` 时会切换行内文字为纵向堆叠（见 6.5）
7. **设置弹窗窄屏 `max-width: 639px` 时**左导航压缩为 `width/min-width: 144px; padding-inline: 8px`
8. **`data-android-webview` 属性在 JS bundle 中出现 8 次** → 壳会设标志位让应用走 Android 分支
9. **`#root` 有 `p-px`（1px padding）+ `h-dvh`**；`<body>` 是 `text-12-regular`（13px/150%）
10. **viewport** 用 `interactive-widget=resizes-content` + `viewport-fit=cover`，无 `user-scalable` 限制
11. **滚动条全部自绘**（12px 命中区 / 4px 视觉条 / `blur(4px)`），原生滚动条全被 `display:none`（见 0.0）
12. **移动端只有 2 个会话 tab**（Session / Changes），桌面端才有多 tab（见 8.1）

---

## 13. 确证状态总表（**已解决 / 仍未解决**）

### 13.1 ✅ 已由本轮确证（否定或修正了原先的假设）

| 项 | 结论 | 证据文件 |
|---|---|---|
| 工具卡左侧色条 | **不存在** | `basic-tool.css`、`message-part.css` 全文 |
| `BasicTool` 的 `icon` prop 是否渲染 | **不渲染**（该文件未 import `Icon`） | `basic-tool.tsx` |
| reasoning part 的 "Thought" 折叠面板/标签/时长/动画 | **全部不存在**；只有 13px muted markdown + 独立的 "Thinking" shimmer 行 | `message-part.tsx` L1763、`message-part.css` |
| `PART_MAPPING` 注册了哪些 part | **只有 4 种**：`tool` / `compaction` / `text` / `reasoning`；`file` / `agent` / `error` **未注册** | `message-part.tsx` |
| 消息行是否有头像 / 角色标签 | **不存在**（靠 user 消息自身 `margin-inline-start:auto` 右对齐） | `session-turn.css`、`message-part.css` |
| 面板 tab 是否为 "Session / Changes / Context" | **不存在该组合**。桌面 = Review / Context / 文件 tab；移动 = Session / Changes | `session-side-panel.tsx`（已全文核实） |
| Context tab 是否显示"上下文中的文件列表" | **不是**。是 16 个统计项 + Token 分解条 + System Prompt + Raw messages | `session-context-tab.tsx` |
| 文件树是否有 `+N/-N` 数字 | **不是数字**，是 **A / D / M 单字母徽标**；数值由 `<DiffChanges/>` 渲染 | `file-tree-v2.tsx` |
| diff 是否只支持 unified | **unified 与 split 都支持**（RadioGroup，默认 unified） | `session-review.tsx` `createDefaultOptions` |
| 命令面板的打开键是否为 Ctrl+P | **不是**。是 `mod+k,mod+shift+p`；**`mod+p` 是 `file.open`** | `context/command.tsx` `DEFAULT_PALETTE_KEYBIND` |
| 命令面板的 item 种类 | **只有 `command` / `file` / `session`**（无 model 类） | `command-palette.ts` `CommandPaletteEntry` |
| 命令面板 session 结果的分组 | 挂在 **`command.category.session` = "Session"** 下，不是 `palette.group.*` | `dialog-command-palette-v2.tsx` |
| 设置页是否为独立路由页面 | **不是**。是 `Dialog size="x-large" variant="settings"`；`pages/settings*` 404 | `settings-dialog.tsx`、`dialog-settings-v2.tsx` |
| 设置左导航是否有"外观/通知/音效/高级"一级项 | **没有**。一级只有 Desktop(General, Shortcuts) + Server(Servers, Providers, Models)；那些是 **General 面板内的 section** | `dialog-settings-v2.tsx`、`general.tsx` |
| "添加服务器"是否是目录选择器 | **不是**。是专用 `dialog-server-v2.tsx`，含 URL/名称/用户名/密码 | `settings-v2/dialog-server-v2.tsx` |
| 终端是否用 xterm.js | **用 Ghostty（libghostty wasm）**；addon 仅 FitAddon + SerializeAddon | `terminal.tsx` |
| 终端是否"隐藏但保持挂载" | **关闭即卸载**；靠 `SerializeAddon` + `LocalPTY` 持久化恢复 | `terminal-panel-v2.tsx`、`context/terminal.tsx` |
| tab bar 的 `+` 是否打开菜单 | **不打开**，直接 `openNewTab()`；菜单式 `+` 只在侧栏面板与终端面板 | `titlebar.tsx` |
| 主题是否用 `.dark` 类切换 | **不是**。只有 `[data-color-scheme="light|dark"]` 切 token；**无 `.dark{}` token 块** | vendored CSS |
| Context ring 的颜色阈值 | **确认不存在**（只有 0–100 钳制，无 >80% 变红逻辑） | `progress-circle-v2.tsx`（已读全文）+ `session-context-usage.tsx` |
| 工具分组的合并规则 | **只合并连续的 `read|glob|grep|list`**；`HIDDEN_TOOLS = {todowrite}` | `message-part.tsx` `groupParts` |

### 13.2 ⚠️ 仍未确证（**不要臆造**）

| 项 | 状态 | 建议下一步 |
|---|---|---|
| **会话路由内 v2 命令面板的挂载宿主组件** | `未找到`。已确认 `DEFAULT_PALETTE_KEYBIND`、Home 侧 `command.register("home.palette")`、两个 Dialog 组件都存在；但 `components/`、`components/session/`、`pages/session/`、`pages/session/v2/` 均无宿主文件。v2 `titlebar.tsx` 只注册了 `home.toggle / tab.new / tab.close / tab.reopenClosed` | 抓 `packages/app/src/pages/session.tsx`（80,351 B）与 `packages/app/src/pages/layout.tsx`（86,694 B） |
| `packages/session-ui/src/pierre/` 完整文件清单 | `未取到`（GitHub API 403 限流 + jsdelivr 403 超 50MB）。仅由 import 间接确认 `src/pierre/index.ts`、`selection-bridge.ts`、`media.ts` | 直连 raw 逐个尝试 |
| `command.terminal.new` / `file.open` 的 keybind 字符串常量 | `未找到`（只见 `command.keybindParts(...)` / `command.keybind(...)` 调用点） | `context/command.tsx` 中 `command.register` 段落 |
| 独立 `terminal.css` | `未找到`（终端样式 = Tailwind 类 + 内联 style） | — |
| `NEW_SESSION_CONTENT_WIDTH` 数值 | `未确证` | `packages/app/src/pages/session/new-session-layout.ts` |
| `home-sessions-controller` 的文件扩展名 | **是 `.tsx`**（不是 `.ts`；`.ts` 为 404） | 已完成纠正 |
| Home 会话列表的时间戳/模型/token 显示 | **不显示**（行内只有 头像 + 标题 + 项目名） | 已完成纠正 |
| 逐帧的 `--diffs-*` 具体取值 | Pierre 在**运行时注入样式表**；静态 CSS 只有 `--diffs-column-content-width` / `--diffs-column-number-width`；~140 个 `--diffs-*` 名字在 JS 里 | `@pierre/diffs` 包本身 |
| Shiki 的 `--shiki-light` / `--shiki-dark` | 全项目**只有 1 处 `--shiki` 前缀字面量**（在 Shiki runtime 里）；**没有任何 CSS 声明 `--shiki-*`** → Shiki 在运行时以**内联 style** 写到 `<pre>`/token span 上 | 已完成说明 |

### 13.3 命名规范提醒（写 Android 代码时命名保持一致）

`data-component` 与 `data-slot` 是**真正的 DOM 契约**：
- 静态 CSS 中：**129 个 `[data-component=...]`** + **435 个 `[data-slot=...]`**
- JS 中额外出现：`data-component="..."` 字面量 **7** 个、`"data-component": "..."` 对象值 **53** 个、`data-slot="..."` 字面量 **26** 个
- JS 中出现的 `data-*` 属性名共 **270** 个

> **注意**：这几个属性**在任何 CSS 和 JS 中都不存在**（不要照抄）：
> `[data-component="session-new-design"]`（只有 JS class 串里出现过一次，CSS/JS 均无选择器）、`[data-component="provider-tip"]`（`provider-tip` 只是 Tailwind group 名 `group-hover/provider-tip:*`）、`[data-component="home-session-search"]`（CSS 里 0 条规则，仅 JS 中存在）。
> 另外 `[data-slot="home-projects-scroll"] { timeline-scope: --home-projects-scroll }`、`[data-component="home-session-scroll-track"] { height: 0 }`。

`--v2-*` 名字被 JS 引用的有 **103 个**（含动态前缀字符串 `"--v2-grey-"`）。

---

## 14. 方法学与可复现步骤

```bash
# 1. 从 vendored 构建提取权威 i18n 文案（含 Android 定制串）
node scripts/extract-i18n.mjs
# → research/_i18n/{en,zh,zht}.json

# 2. 提取 v2 设计 token（PowerShell）
$css = Get-Content "app\src\main\assets\opencode-app\assets\index-D_rl_FNw.css" -Raw
[regex]::Matches($css, '--v2-[a-zA-Z0-9-]+\s*:\s*[^;{}]+') | % { $_.Value } | Sort-Object -Unique
```
> 注意：部分 pwsh 会话处于 **ConstrainedLanguage** 模式，`Get-Content -Raw` / `[System.IO.File]::` 会失败；此时改用 `read` / `grep` 工具或 `node -e`。

### 关键文件位置速查

| 内容 | 路径 |
|---|---|
| Home 页 | `packages/app/src/pages/home.tsx` + `pages/home/*` |
| Home 项目列表 | `packages/app/src/pages/home/home-projects-view.tsx`（含 `HomeUtilityNav`、`HomeProjectEmpty`） |
| Home 会话列表 | `packages/app/src/pages/home/home-sessions-view.tsx` + `home-sessions-controller.tsx`（**`.tsx`**） |
| 时间线 | `packages/app/src/pages/session/timeline/message-timeline.tsx` + `timeline/rows.tsx` + `timeline/projection.ts` |
| 消息 part 渲染 | `packages/session-ui/src/components/message-part.tsx`（`PART_MAPPING`）+ `message-part.css` |
| composer 外框 | `packages/app/src/pages/session/composer/session-composer-region.tsx` |
| composer 本体 | `packages/session-ui/src/v2/components/prompt-input/index.tsx` |
| composer 接线 | `packages/app/src/components/prompt-input-v2.tsx` |
| **placeholder 文案逻辑** | `packages/app/src/components/prompt-input/placeholder.ts` |
| context ring | `packages/app/src/components/session-context-usage.tsx` + `packages/ui/src/v2/components/progress-circle-v2.tsx` |
| **右侧面板 + tab 列表** | `packages/app/src/pages/session/session-side-panel.tsx` |
| **Context tab 内容** | `packages/app/src/components/session/session-context-tab.tsx` |
| **diff 视图** | `packages/session-ui/src/components/session-review.tsx`（`@pierre/diffs`） |
| **文件树** | `packages/app/src/components/file-tree-v2.tsx` |
| 工具卡 | `packages/session-ui/src/components/basic-tool.{tsx,css}` |
| 默认展开 | `packages/session-ui/src/components/part-default-open.ts` |
| 错误卡 | `packages/session-ui/src/components/tool-error-card.{tsx,css}` |
| 命令面板 | `packages/app/src/components/dialog-command-palette-v2.{tsx,css}` + `command-palette.ts` |
| **命令面板键位** | `packages/app/src/context/command.tsx`（`DEFAULT_PALETTE_KEYBIND`） |
| **设置弹窗** | `packages/app/src/components/settings-v2/dialog-settings-v2.tsx` + `settings-v2.css` |
| **设置-General** | `packages/app/src/components/settings-v2/general.tsx` + `parts/row.tsx` + `parts/list.tsx` |
| **设置-Servers** | `packages/app/src/components/settings-v2/servers.tsx` + `dialog-server-v2.tsx` |
| **设置-快捷键** | `packages/app/src/components/settings-keybinds.tsx` |
| **目录选择器** | `packages/app/src/components/dialog-select-directory-v2.tsx` + `directory-picker-domain.ts` |
| **编辑项目** | `packages/app/src/components/dialog-edit-project-v2.tsx` + `edit-project.ts` |
| 服务器对话框文案 | `packages/app/src/i18n/{en,zh}.ts` 中 `dialog.server.*` |
| 设置项文案 | 同上，`settings.*` |
| i18n 基座 | `packages/app/src/context/language.tsx` |
| 终端 | `packages/app/src/components/terminal.tsx` + `pages/session/terminal-panel-v2.tsx` + `utils/terminal-websocket-url.ts` + `context/terminal.tsx` |
| **tab bar** | `packages/app/src/components/titlebar.tsx` + `titlebar-tab-strip.tsx` + `titlebar-tab-nav.{tsx,css}` + `titlebar-tab-popover.{tsx,css}` + `titlebar-tab-order.ts` + `context/tabs.tsx` |
| vendored CSS/JS/HTML | `app/src/main/assets/opencode-app/{index.html,oc-theme-preload.js,assets/index-D_rl_FNw.css,assets/index-CKBO9Mty.js}` |
| **i18n 提取脚本** | `scripts/extract-i18n.mjs` → `research/_i18n/{en,zh,zht}.json`（en 1399 / zh 1311 / zht 1309 条） |

---

## 15. 本轮调研覆盖度自评

| 用户要求的项 | 覆盖 | 备注 |
|---|---|---|
| 1. Home 首页 | ✅ **完整** | 布局/空状态/项目列表/会话列表/搜索/UtilityNav 全部有源码级细节 |
| 2. Session 聊天页（时间线 / header / context ring / composer / tab `+`） | ✅ **完整** | 时间线虚拟化参数、吸顶标题行、ring SVG 几何、composer 尺寸、`+` 行为全部确证 |
| 3. 消息 part（text/tool/reasoning/file/agent/error） | ✅ **完整** | 且**纠正了 5 处常见误解**（无左色条、无 Thought 折叠、file/agent/error 未注册、无头像、无 `+N/-N`） |
| 4. Composer（placeholder/附件/模型 chip/发送/停止/@-mention/多行） | ✅ **完整** | 含逐字 placeholder 与 `max-height:180px` 无 JS 测量的结论 |
| 5. Settings（分类导航/服务器列表/添加服务器/快捷键） | ✅ **完整** | 左导航只有 5 项 + 2 分组标题；"外观/通知/音效/高级"是 General 内 section |
| 6. 命令面板 | ✅ **完整** | **纠正键位**：Ctrl+K / Ctrl+Shift+P（非 Ctrl+P）；item 只有 3 类；选中行为逐类型 |
| 7. 会话抽屉/会话列表 | ✅ **完整** | Home 右栏即列表；分组规则/上限 64/归档开关关闭 |
| 8. Context 面板三 tab / 文件树 / diff | ✅ **完整** | **纠正 tab 命名**；Context tab 实为统计页；diff 为 `@pierre/diffs` 且支持 unified+split |
| 9. Terminal / PTY | ✅ **完整** | Ghostty + FitAddon/SerializeAddon + WebSocket 控制帧协议 + 100ms resize 去抖 + 退避重连 |
| 10. Project 项目页 / 目录选择 | ✅ **完整** | 目录选择器为 `@pierre/trees` web component（无 breadcrumb） |
| 附：设计 token | ✅ **完整** | 147 原始 + 93+92×2 语义 + 全部阴影 + 字号 token |
| 附：Android 定制差异 | ✅ **完整** | 4 条定制文案 + `data-android-webview` 标志 |

**唯一主要缺口**：**会话路由内 v2 命令面板的挂载宿主组件**（需读 `pages/session.tsx` / `pages/layout.tsx`，各 80KB+）。其余项均达到"可用于 1:1 复刻"的粒度。

