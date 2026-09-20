# OpenCode 前端架构调研笔记（面向 Android/Jetpack Compose 1:1 复刻）

> 调研对象：`github.com/anomalyco/opencode`
> 调研方式：`git clone --depth 1 --branch v2 --filter=blob:none` + `raw.githubusercontent.com` 逐文件取证 + 本地 vendored 构建产物交叉验证。
> 所有结论均给出对应仓库路径；凡本地无法取证的一律标注「未取证/推断」。

---

## 0. 版本基线（**先看这一节，它会影响你的复刻目标**）

| 项 | GitHub `v2` 分支（本文主要依据） | 本地 vendored 产物 |
| --- | --- | --- |
| 仓库 | `anomalyco/opencode` | 同 |
| 提交 | `c42f1c92324e1ca9ed29c288446e24c008ddd098`（2026-09-19） | 由 `frontend/OPENCODE_APP_VERSION` 记录：`014614d35b397775e5d397a490fc72368c894ec2` |
| tag | 无（分支 HEAD） | `v1.18.31` |
| 前端包名 / 版本 | `@opencode/app` `2.0.9` | `@opencode-ai/app` `1.18.31` |
| 包 scope | `@opencode/*` | `@opencode-ai/*` |
| 样式入口 | `@opencode/ui/styles/tokens` | `@opencode-ai/ui/v2/styles/tailwind.css` |

**结论：vendored 产物是 v1.18.31，与 v2 分支是两套（包名 scope 改过、目录重构过）。**
两者共享 **同一套 `--v2-*` design token 体系**（v1.18.31 的构建产物 CSS 里已含全部 `--v2-*` 变量，见 §5），但 v2 分支把前端源码从「扁平的 `src/`」重构成 `shell/ runtime/ session/ settings/ new-session/ workspaces/ composer/` 等域目录。

✅ **复刻目标建议**：以 **v2 分支源码**为规格（结构清晰、可读），以 **vendored 产物**为像素/行为基线（你能直接跑起来对照）。二者 token 一致，主要差异在信息架构。

---

## 1. Monorepo 结构

### 1.1 工作区配置

`package.json`（根，`packages/` 之外）：

```json
"version": "2.0.9",
"packageManager": "bun@1.4.2",
"workspaces": { "packages": ["packages/*", "services/*", "packages/console/*", "packages/stats/*"] }
```

包管理器是 **Bun 1.4.2**（不是 pnpm），使用 **Bun catalog** 统一锁版本（`"catalog": { "@kobalte/core": "0.13.13", "solid-js": "1.9.15", ... }`）。子包用 `"@kobalte/core": "catalog:"` 引用。

来源：<https://github.com/anomalyco/opencode/blob/v2/package.json>

### 1.2 `packages/` 下全部 35 个包

```
ai  app  cli  client  codemode  console  containers  core  desktop
effect-drizzle-sqlite  enterprise  function  http-recorder  httpapi-codegen
identity  latex  merman  plugin  plugin-browser  posts  protocol  schema
script  sdk  server  session-ui  simulation  stats  storybook  theme
tui  ui  util  web
```

> ⚠️ **纠正任务描述里的三个假设**：v2 分支 **不存在** `packages/ocr`、`packages/components`、`packages/types`。
> - 原以为的 `components` → 实际是 **`packages/ui`**（设计系统组件库）；
> - 原以为的 `types` → 实际是 **`packages/schema`**（Effect Schema 领域类型）与 **`packages/protocol`**（OpenAPI/HTTP 协议）；
> - 没有 OCR 包。
>
> `packages/merman`（mermaid 渲染）、`packages/latex` 是与 UI 渲染相关的两个独立包。

### 1.3 与前端强相关的包及其职责（含实测依赖边）

| 包 | 版本 | 职责（证据） | 关键依赖（实测自其 `package.json`） |
| --- | --- | --- | --- |
| **`packages/app`** | 2.0.9 | **前端应用主体**：路由、外壳、会话界面、设置、命令面板。Vite 构建，产物即 vendored 的 `index.html + assets/`。 | `@opencode/{client,schema,plugin-browser,session-ui,ui,util}`、`solid-js`、`@solidjs/router`、`@solidjs/meta`、`@kobalte/core`、`@corvu/drawer`、`@dnd-kit/*`、`@tanstack/solid-query`、`@tanstack/solid-virtual`、`@pierre/trees`、`ghostty-web`、`effect`、`fuzzysort`、`remeda`、`qr-scanner`、`tailwindcss` |
| **`packages/session-ui`** | 2.0.9 (private) | **会话渲染层**：消息/工具调用/markdown/diff/时间线投影。被 app 消费，导出 `./timeline`、`./markdown`、`./file`、`./session-review` 等子入口。 | `@opencode/{client,ui,util}`、`@pierre/diffs`、`@shikijs/stream`、`shiki`、`marked`、`dompurify`、`mermaid`、`motion`、`diff`、`effect`、`remend`、`strip-ansi`、`@kobalte/core` |
| **`packages/ui`** | 2.0.9 | **设计系统**：Button/Icon/Dialog/Menu/Tabs/Tooltip/Toast…「Kobalte 风格」自研组件 + **全部 CSS token 与 Tailwind 预设**。`sideEffects: ["**/*.css"]`。 | `@kobalte/core`、`@pierre/diffs`、`katex`、`marked`、`marked-shiki`、`shiki`、`motion`、`solid-sonner`、`solid-list`、`fuzzysort`、`remeda`；peer: `solid-js@^1.9.8`、`@solidjs/meta@^0.29` |
| **`packages/desktop`** | 2.0.9 (private) | Electron 桌面外壳。**提供 `DesktopMemoryRouter`**（渲染进程使用 memory history）。 | devDeps 里 `@opencode/app`、`@opencode/{client,schema,plugin-browser,ui}`；`electron@42.10.1`、`electron-vite@6.0.0-beta.1`、`@lydell/node-pty` |
| **`packages/theme`** | 2.0.9 | **TUI 主题引擎**（`@opentui/core` + `effect`），导出 `./tui`、`./tui/v1`。注意：**Web/桌面主题不在这里**，Web 主题 JSON 在 `packages/ui/src/theme/`。 | `@opentui/core`、`effect` |
| **`packages/tui`** | — | 终端 UI（OpenTUI + Solid）。`src/app.tsx`、`src/runtime.tsx`。 | — |
| **`packages/client`** | — | **生成的 HTTP API 客户端**，三套入口：`./effect`（Effect）、`./promise`（Promise）、`./solid`（**响应式数据层 `Data`**）。app 通过 `@opencode/client/solid` 的 `createData` 拿数据。 | — |
| **`packages/sdk`** | — | 对外 SDK（`src/opencode.ts`、`src/promise.ts`、`src/tool.ts`）。 | — |
| **`packages/schema`** | — | Effect Schema 领域模型（`agent.ts`、`event.ts`、`filesystem.ts`、`file-diff.ts` …）。 | — |
| **`packages/protocol`** | — | 协议层：`openapi.json`、`src/api.ts`、`src/client.ts`、`src/errors.ts`。 | — |
| **`packages/web`** | — | **Astro 官网/文档站**（`astro.config.mjs`），与 app 无关，勿混淆。 | — |

其余为后端/服务端/工具链包：`core`（引擎）、`server`、`cli`、`ai`、`util`、`plugin`、`plugin-browser`、`console`、`stats`、`enterprise`、`storybook`、`posts`、`containers`、`identity`、`function`、`codemode`、`simulation`、`latex`、`merman`、`httpapi-codegen`、`http-recorder`、`effect-drizzle-sqlite`、`script`。

### 1.4 依赖方向（前端视角）

```
                 ┌──────────────────────────┐
                 │ packages/desktop (Electron)│
                 └───────────┬──────────────┘
                             │ devDep: @opencode/app
                             ▼
   packages/client  ──►  packages/app  ◄── packages/session-ui ──► packages/ui
   (solid/promise/          │                    │                      ▲
    effect 三态)             │                    └──────────────────────┘
                             ▼
                    packages/schema / protocol (类型与协议)
```

- `app` 依赖 `session-ui` 与 `ui`；`session-ui` 也依赖 `ui` → **`ui` 是叶子（base design system）**。
- `ui` 与 `session-ui` 之间有一条 **边界测试**：`packages/session-ui/src/import-boundaries.test.ts`。
- `app`、`client`、`sdk` 之间也有 `packages/sdk/test/import-boundaries.test.ts`、`packages/client/test/import-boundaries.test.ts` 强制约束。

---

## 2. 前端技术栈

### 2.1 框架与构建（实测 `packages/app/package.json`）

| 项 | 结论 | 证据 |
| --- | --- | --- |
| 框架 | **SolidJS**（`solid-js@1.9.15` catalog） | app/ui/session-ui 的 `package.json` |
| 构建 | **Vite**（app 锁 `vite@8.2.2`，含根 `patchedDependencies: vite@8.2.2`），`vite-plugin-solid@2.11.14` | `packages/app/package.json` devDeps |
| 路由 | **`@solidjs/router`**（catalog `1.0.0`，源码用 `createMemoryHistory` / `MemoryRouter`） | `packages/app/src/shell/routes/routes.tsx`、`packages/desktop/src/renderer/window/router.tsx` |
| 服务端状态 | **`@tanstack/solid-query@5.91.4`**（`QueryClient` 关闭了 refetchOnMount/Focus/Reconnect） | `packages/app/src/app.tsx` L36-47 |
| 虚拟滚动 | `@tanstack/solid-virtual` | `packages/app/src/session/timeline/virtualizer.tsx` |
| 类型/校验 | **Effect**（`effect@4.0.0-rc.112`，`Schema` 用于设置持久化 schema） | `packages/app/src/shell/commands/command.tsx` L5 |
| PWA | `vite-plugin-pwa@1.3.0` + `sw.js` 注册 + `site.webmanifest` | `packages/app/src/entry.tsx` L48-50 |

> **没有 Jotai / Zustand / Redux / MobX**。状态全靠 Solid 原生 `createStore`/`createSignal` + `createSimpleContext` 自研 context + TanStack Query（见 §6）。

### 2.2 CSS 方案：**Tailwind CSS v4**（不是 v3，也不是 CSS-in-JS）

- `packages/app/package.json`：`"tailwindcss": "4.3.3"`、`"@tailwindcss/vite": "4.3.3"`、`"tw-animate-css": "1.4.0"`。
- v4 语法特征在源码里处处可见：`@import "tailwindcss/theme.css" layer(theme)`、`@theme { }`、`@utility`、`@source`、`@layer theme, base, components, utilities;`。
  - `packages/ui/src/styles/tailwind/index.css`：`@source "../../../../app/src"` 等 —— **v4 的显式内容扫描**。
  - `packages/ui/src/styles/tailwind/utilities.css`：自定义 `@utility no-scrollbar / hover-reveal / badge-mask / truncate-start / fade-up-text`。
- **`bg-v2-background-bg-deep` / `text-12-regular` 是怎么来的**（任务里问的两个类）：
  1. `packages/ui/script/tailwind.ts` 从 `colors.txt` 逐行生成 `packages/ui/src/styles/tailwind/colors.css`，产出形如
     `--color-v2-background-bg-deep: var(--v2-background-bg-deep);`（实测该文件含 **53** 条 `--color-v2-*` 别名）。
     于是 Tailwind v4 自动派生出 `bg-v2-background-bg-deep`、`text-v2-text-text-base`、`border-v2-border-border-base` 等工具类。
  2. 但 `text-12-regular` **不是 Tailwind 工具类，是手写的普通 CSS 类**，定义在
     `packages/ui/src/styles/utilities.css` L46-118（`.text-12-regular/.text-12-medium/.text-12-mono/.text-14-*/.text-16-medium/.text-20-medium`）。
- **`text-12-regular` 的来历与终局**（两版本有差异，别搞混）：
  - **v2 分支** `packages/app/index.html` 的 `<body>` 已经是终态：
    `class="antialiased overscroll-none font-(family-name:--font-family-text) text-[13px] font-[440] overflow-hidden bg-v2-background-bg-deep"`
    → 正文 **13px / weight 440**。
  - **vendored v1.18.31** 的 `index.html` 里 `<body>` 写的是 `… text-12-regular …`。
  - 无论哪个版本，运行时 `BodyTypography()`（`packages/app/src/app.tsx` L49-57）都会 `classList.remove("text-12-regular")`
    再 `add("font-(family-name:--font-family-text)", "text-[13px]", "font-[440]")` —— 是一条**防御性兜底**。
- v2 的 `<meta name="viewport">` 含 **`interactive-widget=resizes-content, viewport-fit=cover`**（`packages/app/index.html`）
  —— 这是软键盘/刘海屏的关键声明，Android WebView 复刻时务必对齐（本仓库已有 `android-webview-z-ime-*.patch` 在补这块）。

### 2.3 UI 组件库：**Kobalte**（不是 Radix）

- `@kobalte/core@0.13.13`（catalog），同时被 `packages/ui`、`packages/session-ui`、`packages/app` 依赖。
- 但 **不是直接裸用 Kobalte**：`packages/ui/src/*` 在手写一层自研组件再包 Kobalte 原语，导出路径如
  `"./dialog": "./src/overlays/dialog/dialog.tsx"`、`"./menu": "./src/navigation/menu/menu.tsx"`、`"./tabs"`、`"./tooltip"`、`"./toast"`、`"./accordion"`…
- 另有非 Kobalte 的补充件：`@corvu/drawer`（移动端抽屉，见 `shell/mobile-drawer.tsx`、`titlebar.tsx` 的 `data-slot="mobile-tabs-drawer"`）、`solid-sonner`（Toast）、`solid-list`、`motion`（动画）、`@dnd-kit/*`（标签拖拽）。
- **图标**：**自建 spritesheet SVG**，不是 lucide/heroicons。
  - `packages/ui/src/components/app-icons/sprite.svg`、`file-icons/sprite.svg`、`provider-icons/sprite.svg`
  - `vite-plugin-icons-spritesheet@3.0.1`（ui 的 devDep）在构建期生成；类型入口 `"./icons/app"`、`"./icons/file-type"`、`"./icons/provider"`。
  - 使用方式：`<Icon name="sliders" />`（见 `packages/app/src/settings/pages.ts` 的 `pageIcons` 映射表）。

### 2.4 字体

`packages/app/src/index.css` 声明了三套 `@font-face`：

| 家族 | 用途 | 声明位置 | 备注 |
| --- | --- | --- | --- |
| **Inter** (`/assets/Inter.ttf`, weight 100–900) | 正文/UI 文本 | `packages/app/src/index.css` L13-18 | `--font-family-sans` 首选；`--font-family-text` 指向它 |
| **IBM Plex Mono**（`@ibm/plex`，`IBMPlexMono-Text.woff2`，**weight 440**） | 代码/等宽 | `packages/app/src/index.css` L20-26 | `packages/ui/src/styles/theme.css` L4-5 的 `--font-family-mono` 首选 |
| **JetBrainsMono Nerd Font Mono** | 终端 | `packages/app/src/index.css` L6-11 | Nerd Font = 含终端图标字形 |

- 注意 `packages/ui/src/components/font.tsx` 内容就是 `export const Font = () => null` —— **`<Font />` 是空操作占位**，字体完全靠 CSS `@font-face` 生效。
- ⚠️ **版本差异**：v2 分支 `packages/ui/src/styles/theme.css` 把 Inter / IBM Plex Mono 写进了 `--font-family-sans` / `--font-family-mono`；
  但在 **vendored v1.18.31 构建产物**里 `--font-family-sans` 仍是系统栈 `ui-sans-serif,system-ui,…`、`--font-family-mono` 仍是系统 mono，
  只有 `--font-family-text:"Inter",sans-serif` 是硬编码的。复刻时**以产物实测值为准**。

### 2.5 Markdown 与代码高亮

| 能力 | 实现 | 证据 |
| --- | --- | --- |
| Markdown 解析 | **`marked`**（`18.0.11`）+ `remend`（流式增量补全） | `packages/session-ui/package.json` |
| 代码高亮 | **Shiki**（`shiki@4.4.3`）+ **`@shikijs/stream`**（流式高亮）+ `marked-shiki`（把 shiki 接到 marked） | `packages/session-ui`、`packages/ui` 的 deps |
| 高亮执行环境 | **Web Worker**：`markdown.worker.ts` + `markdown-worker-queue.ts` / `-transport.ts` / `-protocol.ts` | `packages/session-ui/src/components/` |
| 输出容器 | 生成 `<pre class="shiki OpenCode">`（主题名 `OpenCode`） | `packages/session-ui/src/components/markdown.tsx` L753 |
| 净化 | **DOMPurify** `3.4.14` | `packages/session-ui/package.json` |
| 数学公式 | **KaTeX** `0.16.47` + `katex/dist/katex.min.css` | `packages/ui/src/styles/index.css` L7；`packages/ui/package.json` |
| 图表 | **Mermaid** `11.17.2`（`src/components/markdown-mermaid.ts`） | `packages/session-ui/package.json` |
| Diff/文件树 | **`@pierre/diffs`**（`1.2.10`）、**`@pierre/trees`**（`1.0.0-beta.4`，有 patch） | ui / app 的 deps |
| **是否用 `github-markdown-css`** | ❌ **没有**。markdown 样式是自研 `packages/session-ui/src/components/markdown.css`（8.8KB） | 依赖表 + 样式文件 |

> 相关性能实验目录：`packages/session-ui/performance/highlighting/`、`e2e/performance/markdown/`。

---

## 3. 路由结构与页面

### 3.1 路由定义（**全部只有 4 条路由**）

文件：`packages/app/src/shell/routes/routes.tsx`

```tsx
export function AppRoutes() {
  return (
    <Route component={AppLayout}>
      <Route path="/"                                 component={Home} />
      <Route path="/settings"                         component={SettingsScreen} />
      <Route path="/server/:serverKey/session/:id"    component={…SessionRoute…} />
      <Route path="/new-session"                      component={DraftRoute} />
    </Route>
  )
}
```

| path | 组件 | 源码 | 懒加载 |
| --- | --- | --- | --- |
| `/` | `Home` | `packages/app/src/home/route.tsx` | 否（静态 import） |
| `/settings` | `SettingsScreen` | `packages/app/src/settings/shell.tsx` | ✅ `lazy()` |
| `/server/:serverKey/session/:id` | `TargetSessionRouteContent` | `packages/app/src/session/route.tsx` | ✅ `lazy()` + `File.preload()` |
| `/new-session` | `DraftRoute` | `packages/app/src/new-session/route.tsx` | ✅ `lazy()` |

- **没有独立的 `/project`、`/terminal` 路由**：项目是 home 的一个区域（`home/projects/*`），终端是会话内的面板/标签（`session/terminal/*`）。
- 兜底：`AppLayout` 中 `<Show when={servers.list.length > 0} fallback={<ConnectServerScreen />}>`
  → **没配服务器时直接渲染配对页**（`packages/app/src/servers/connect/screen.tsx`）。
- 预加载：`export function preloadRoute(url: string)` 手动匹配 3 个 path 调 `.preload()`（`routes.tsx` L25-32）。
- 会话 URL 生成器：`packages/app/src/shell/routes/session.ts` 的 `sessionHref` / `requireServerKey`。
- 草稿页 URL：`/new-session?draftId=<uuid>`（`packages/app/src/shell/tabs/tabs.tsx` L41 `draftHref`）。

### 3.2 `packages/app/src/` 目录形态（122+ 文件，域驱动而非「pages/」）

> ⚠️ 任务里假设的 `packages/app/src/pages/` **不存在**。v2 用的是**按域分目录**：

```
src/
├─ app.tsx                 # 根 Provider 栈 + <Router> + <AppRoutes/>
├─ entry.tsx               # 浏览器入口，render() 挂载到 #root
├─ index.ts / desktop.ts   # 对外导出
├─ index.css               # @import 各样式 + @font-face + 组件级 CSS
├─ shell/                  # ★ 应用外壳
│  ├─ shell.tsx            #   布局盒（标题栏 + main + Toast）
│  ├─ routes/routes.tsx    #   路由表
│  ├─ titlebar/            #   titlebar / tab-strip / tab-nav / tab-popover / windows-menu
│  ├─ tabs/                #   TabsProvider（标签状态、持久化、关闭栈）
│  ├─ commands/            #   command.tsx(命令注册中心) / palette.ts / dialog.tsx(⌘P 面板)
│  ├─ state/               #   layout.tsx / scroll.ts / session-tabs.ts
│  ├─ notifications/       #   toast.tsx / notification.tsx / sound.ts
│  ├─ updates/             #   highlights / release-notes / download
│  ├─ errors/  debug/  layout/
├─ session/                # ★ 会话页
│  ├─ route.tsx screen.tsx session-frame.tsx session-layout.ts
│  ├─ timeline/            #   虚拟化消息时间线（controller.tsx / virtualizer.tsx / projection.ts）
│  ├─ composer/            #   输入区（queue.ts 排队）
│  ├─ files/               #   文件树/文件标签（pierre-tree、file-tabs）
│  ├─ review/              #   更改审查面板
│  ├─ summary/             #   右侧摘要/上下文面板
│  ├─ terminal/            #   终端面板（ghostty-web）
│  ├─ header/  requests/  browser/  commands/
├─ new-session/            # ★ 新建会话页（project/ workspace/ 选择器）
├─ home/                   # ★ 首页（projects/ sessions/ 两个 region + command-palette.tsx）
├─ settings/               # ★ 设置（见 §3.3）
├─ composer/               # 共享输入组件（editor/ attachments/ suggestions/ history/）
├─ runtime/                # 运行时：i18n(70+ 语言) / persistence / platform / server
├─ servers/                # 服务器连接：connect(扫码配对) / registry / ssh / wsl
├─ workspaces/             # 工作区、文件树缓存、路径
└─ providers/              # 模型/凭证/Provider 目录与对话框
```

### 3.3 设置页子页面（`/settings` 内部的 12 个 tab）

`packages/app/src/settings/pages.ts` 定义了 `SettingsRootTab` 联合类型与图标/文案：

| id | 图标 | 文案 key | 组件文件 |
| --- | --- | --- | --- |
| `general` | `sliders` | `settings.tab.preferences` | `settings/general/general.tsx` |
| `appearance` | `appearance` | `settings.general.section.appearance` | `settings/appearance/appearance.tsx` |
| `notifications` | `notifications` | `settings.tab.notifications` | `settings/notifications/notifications.tsx` |
| `shortcuts` | `keyboard` | `settings.shortcuts.title` | `settings/keybinds/keybinds.tsx` |
| `projects` | `folder` | `settings.tab.projects` | `settings/workspaces/projects.tsx` |
| `workspaces` | `outline-worktree` | `settings.tab.workspaces` | `settings/workspaces/workspaces.tsx` |
| `providers` | `providers` | `settings.providers.title` | `settings/providers/providers.tsx` |
| `models` | `models` | `settings.models.title` | `settings/models/models.tsx` / `providers/models/*` |
| `extensions` | `extensions` | `settings.tab.extensions` | `settings/providers/extensions.tsx` |
| `servers` | `server` | `settings.section.server` | `settings/servers/servers.tsx` |
| `experimental` | `flask` | `settings.tab.experimental` | `settings/experimental/experimental.tsx` |
| `about` | `info` | `settings.tab.about` | `settings/about/about.tsx` |

设置页是 **同一个路由下的 tab 面板**（`settings/shell.tsx` + `settings/navigation.tsx` 的 `Menu.RadioGroup`/`Tabs.List`），**不是嵌套路由**。
另有全屏设置搜索：`settings/search.tsx`、`search-catalog.ts`、`search-index.ts`、`search-reveal.ts`。

### 3.4 Router 究竟是不是 `MemoryRouter`？

**两者都用，取决于宿主**：

```tsx
// packages/app/src/app.tsx  L100-139
<Dynamic component={props.router ?? Router} root={Root}>
  <AppRoutes />
</Dynamic>
```

- **Web/PWA 默认**：`@solidjs/router` 的 `Router`（**真实 History**，URL 会变）。
- **Desktop(Electron)**：注入 `DesktopMemoryRouter`（**内存路由**，每个窗口独立、并能恢复上次 URL）：

```tsx
// packages/desktop/src/renderer/window/router.tsx
export function DesktopMemoryRouter(props: BaseRouterProps & { windowID: string }) {
  const history = createMemoryHistory()
  const initialUrl = getLastActiveUrl(props.windowID)
  if (initialUrl !== "/") history.set({ value: initialUrl, replace: true, scroll: false })
  onCleanup(history.listen((value) => setLastActiveUrl(props.windowID, value)))
  return <MemoryRouter {...props} history={history} />
}
```

- **对你的 Android 复刻**：`frontend/patches/` 里已有 `android-webview-*` 系列补丁，`OPENCODE_APP_VERSION` 的备注明确写着 *「Desktop MemoryRouter」* —— 现有 Android 外壳正是**借用 Desktop 的 `MemoryRouter` 路线**。Compose 里对应「单 Activity + 自有 back stack」，不要映射到 URL 路由。

---

## 4. 顶层应用外壳

### 4.1 Provider 栈（`packages/app/src/app.tsx`）

```
MetaProvider                                   (@solidjs/meta)
└─ Font (no-op) → ThemeProvider                (@opencode/ui/theme/context)
   └─ LanguageProvider → UiI18nBridge
      └─ ErrorBoundary  (catch → ErrorPage, 上报 Sentry)
         └─ QueryProvider (TanStack QueryClient)
            └─ WslServersProvider → DialogProvider → SshProvider
               └─ FileComponentProvider(component={File})
                  └─ AppInterface
```

`AppInterface` 里：`ServersProvider` → `SettingsProvider` → `Dynamic(router)`，**渲染布局的 `Root` 组件被放在 `router.root` 上**，这样布局在路由切换时**不卸载**：

```tsx
const Root = (rootProps) => (
  <TabsProvider>
    <GlobalProvider>
      <BodyTypography />
      <CommandProvider>
        <DesktopCommands /> <SshRestore /> <HighlightsProvider>
          {props.children}{rootProps.children}
      </CommandProvider>
    </GlobalProvider>
  </TabsProvider>
)
```
（`app.tsx` L100-139，注释原文：*“The visual layout lives in the router root so it remains mounted across route changes.”*）

### 4.2 外壳布局 `packages/app/src/shell/shell.tsx`

结构（用 Tailwind + CSS 变量做安全区内边距）：

```
<div class="relative bg-v2-background-bg-deep flex-1 min-h-0 min-w-0 flex flex-col select-none"
     style="--shell-top-inset / --shell-bottom-inset">
  <Titlebar update verticalTabs? debugTools />      ← 顶部（移动端可改到底部）
  <div class="flex flex-1 min-h-0 min-w-0 flex-row">
     <Show when={verticalTabs()}>                   ← 侧边栏（可选）
       <aside data-slot="vertical-tabs-sidebar" …>
         <ResizeHandle min={140} max={520} />
       </aside>
     </Show>
     <main class="flex-1 … contain-content">
       <SshAuthentication><Suspense>{children}</Suspense></SshAuthentication>
     </main>
  </div>
  <Show when={state.debugTools}><DebugBar/></Show>
  <ToastRegion /> <UploadToastHost />
</div>
```

关键点（对 Android 极其重要）：

- **没有「底部 tab 栏」这种组件**。所谓底部栏 = **`Titlebar` 被移到屏幕底部**：
  `const mobile = createMediaQuery("(max-width: 767px)")`；
  `const bottomTitlebar = () => mobile() && preferences.general.mobileTitlebarPosition() === "bottom"`。
  设置项 `mobileTitlebarPosition: Schema.Literals(["top","bottom"])`（`packages/app/src/settings/model.tsx` L93 / L250，默认 `"top"`）。
- **「侧边栏」= 垂直标签栏**：由设置 `appearance.tabLayout: "horizontal" | "vertical"` 控制（`settings/model.tsx` L105 / L257），
  宽度默认 `260`，可拖拽 140–520。挂载点通过 `ref → state.tabsMount` 传给 `Titlebar`。
- **安全区**：统一用 `--safe-area-inset-bottom` / `env(safe-area-inset-bottom)` 与 `--shell-top-inset` / `--shell-bottom-inset` 计算。
  Android 侧应直接把这些变量映射到 `WindowInsets`。
- `main` 上 `contain-content` 的注释值得抄：*“Size containment collapses percentage-height descendants in WebKit.”*

### 4.3 标题栏 `packages/app/src/shell/titlebar/titlebar.tsx`（38.6KB，最大单文件之一）

`data-slot` 骨架（实测 grep 结果）：

| `data-slot` | 含义 |
| --- | --- |
| `titlebar-v2` | 标题栏根（`data-slot="titlebar-v2"` L150） |
| `mobile-tabs-trigger` | **移动端**：点开标签抽屉的按钮（L472） |
| `mobile-tab-title` | 移动端当前标签标题（`truncate text-start`，L514） |
| `mobile-tabs-drawer` / `mobile-tabs-drawer-list` | 移动端标签抽屉（`data-corvu-no-drag`，即 **Corvu Drawer**，L521-522） |
| `project-avatar-slot` | 项目头像位（L479） |
| `vertical-tabs-footer` | 垂直标签栏底部区（放更新入口等，L685） |
| `titlebar-update` / `titlebar-update-loader` | 更新按钮与其 spinner |
| `channel-indicator` | 渠道标记（dev/beta/prod） |

配套：`tab-strip.tsx`、`tab-nav.tsx`/`.css`、`tab-popover.tsx`/`.css`、`windows-menu.tsx`、
手势 `tab-gesture.ts`（移动端横滑切标签）、`tab-order.ts`、`tab-title.ts`、`session-events.ts`、`history.ts`。

### 4.4 标签（tabs）状态 `packages/app/src/shell/tabs/tabs.tsx`

- `TabsProvider` 由 `createSimpleContext` 生成；内部 `createStore(…)` + **持久化** `Persisted`（`runtime/persistence/storage`，key `settings.v3` 同族）。
- 支持：草稿标签（`draftHref`）、关闭栈（`closed.ts` 的 push/take/remove）、重开后恢复（`memory.ts`）、跨服务器标签、
  与路由联动（`useLocation/useNavigate/useParams`）。
- 标签种类（从目录可见）：**聊天 / 文件 / 更改(review) / 上下文(context) / 终端(terminal) / 浏览器(browser)** ——
  对应 `session/files/tab.tsx`、`session/review/review-tab.tsx`、`session/files/session-context-tab.tsx`、`session/terminal/tab.tsx`、`session/browser/pane.tsx`。

### 4.5 命令面板（Command Palette）

- **命令注册中心**：`packages/app/src/shell/commands/command.tsx`
  - `CommandProvider` 暴露 `register(key, cb)`；`CommandOption = { id, title, category, onSelect, keybind? , when? }`。
  - 键盘匹配：`parseKeybind()` / `matchKeybind()` / `formatKeybind()` / `formatKeybindParts()`。
  - 重复 id 会 warning 并保留首个（L301）。
  - 持久化用 `Persistence` + `effect` 的 `Schema`（L5-6）。
- **面板 UI**：`shell/commands/dialog.tsx` + `dialog.css`，基于 `@opencode/ui/context/dialog`（Kobalte Dialog 包装）；
  打开入口是 `useDialog()`（`palette.ts` L4、L87）。
- **模糊搜索**：`fuzzysort`（app 与 ui 均依赖）；`commandPaletteOptions()` 在 `command.tsx` L99。
- **面板内容组装**：`packages/app/src/shell/commands/palette.ts`
  - `createCommandPaletteModel({ filesOnly, onOpenFile })` 聚合三类条目：命令、文件（`workspace` 文件树）、服务器/会话。
  - 默认快捷键 `Ctrl/Cmd+P`。
- **首页版本**：`packages/app/src/home/sessions/command-palette.tsx`。
- 有专门的性能基准：`e2e/performance/command-palette-benchmark.spec.ts`。

### 4.6 其它外壳元素

- **Toast**：`shell/notifications/toast.tsx`（`solid-sonner`）+ `UploadToastHost`（附件上传）+ `sound.ts`（提示音，`packages/ui/src/assets/audio/` 里有 `*.aac`）。
- **移动端抽屉**：`shell/mobile-drawer.tsx`/`.css`、`shell/mobile-panel-drawer.tsx`/`.css`（**这两个文件是你 Compose 复刻的直接参考**）。
- **调试条**：`shell/debug/debug-bar.tsx`（懒加载，`debugBar.toggle` 命令）。
- **SSH/WSL**：`servers/ssh/*`（认证状态机、重连）、`servers/wsl/*`。
- **扫码配对**：`servers/connect/scanner.tsx`（`qr-scanner`）、`screen.tsx`、`pairing.ts`、`camera.ts`。

---

## 5. 样式系统与 Design Token

### 5.1 分层总览：**这里其实有两套 token 并存**

| 层 | 前缀 | 定义文件（v2 分支） | 用途 |
| --- | --- | --- | --- |
| **v2 语义 token** | `--v2-background-*` `--v2-text-*` `--v2-border-*` `--v2-icon-*` `--v2-state-*` `--v2-overlay-*` `--v2-elevation-*` `--v2-avatar-*` `--v2-agent-*` `--v2-illustration-*` | `packages/ui/src/styles/tokens/theme.css` | **新 UI 全部使用**（组件类 `bg-v2-background-bg-deep` 等） |
| **v2 原始色阶** | `--v2-grey-50…1200`、`--v2-red/orange/yellow/green/cyan/blue/purple/pink-100…1200`、`--v2-alpha-dark-0…100`、`--v2-alpha-light-0…100` | `packages/ui/src/styles/tokens/colors.css` | 语义 token 的取值来源 |
| **v1 旧 token** | `--background-base`、`--text-*`、`--surface-*`、`--border-*`、`--icon-*`、`--syntax-*`、`--color-*` | `packages/ui/src/styles/theme.css`（25.5KB）、`packages/ui/src/styles/colors.css`（25.2KB） | 旧组件 + **代码高亮** + Tailwind `--color-*` 别名 |

聚合入口：`packages/ui/src/styles/tokens/index.css`
```css
@import "./colors.css";
@import "./theme.css";
```

`packages/app/src/index.css` 顶部四行 import：
```css
@import "@opencode/ui/styles/tailwind";     /* Tailwind v4 presets + app/session-ui 扫描 + ui/styles/index.css */
@import "@opencode/session-ui/styles";      /* session-ui 的组件 CSS */
@import "@opencode/ui/styles/tokens";       /* ★ 全部 --v2-* token 在这里 */
@import "tw-animate-css";
```

### 5.2 `--v2-*` 语义 token 清单（实测自 `tokens/theme.css`）

**Background**（11 个）
`--v2-background-bg-base`、`-deep`、`-layer-01/02/03/04`、`-inverse`、`-contrast`、`-icon-button-contrast`、`-button-neutral`、`-accent`、`-code-path`

**Text**（9）
`--v2-text-text-base`、`-muted`、`-faint`、`-inverse`、`-contrast`、`-accent`、`-accent-hover`、`-code-accent`、`-code-path`

**Icon**（7）
`--v2-icon-icon-base`、`-muted`、`-faint`、`-inverse`、`-contrast`、`-accent`、`-accent-hover`

**Border**（5）
`--v2-border-border-muted`、`-base`、`-strong`、`-inverse`、`-focus`

**Overlay**（10）
`--v2-overlay-simple-overlay-hover/-pressed/-contrast-hover/-contrast-pressed/-scrim`、`--v2-overlay-gradient-depth-overlay-depth-top/-bot`、`--v2-overlay-simple-tab-active-scrim/-hover-scrim/-scrim`

**State**（12）
`--v2-state-{bg,fg,border}-{success,warning,danger,info}`

**Agent**（9）+ **Avatar**（19）+ **Elevation**（8）+ **Illustration**（3）
`--v2-agent-{plan,build,explore,review,writer}-{solid,border,background}`、
`--v2-avatar-{fg,bg-*,border-*}`（8 色）、
`--v2-elevation-{raised,floating,overlay,button-neutral,button-contrast,elements,switch-off,switch-on}`、
`--v2-illustration-illustration-layer-01/02/03`

### 5.3 ⚠️ 关于 `--v2-syntax-*`：**不存在**

- 在 **源码** 与 **vendored 480KB 主 CSS** 中检索 `--v2-syntax` → **0 命中**。
- 实际的高亮 token 是 **无前缀的 `--syntax-*`**（v1 层），共 22 个：
  `--syntax-{comment,string,keyword,primitive,operator,variable,property,type,constant,punctuation,regexp,object,critical,info,success,warning,diff-add,diff-delete,diff-unknown,…}`
  在 v1 层 `packages/ui/src/styles/theme.css` 定义，并被主题 JSON 覆写（产物中可见 `html[data-theme=oc-2] body:not([data-new-layout]){ --syntax-keyword: light-dark(...); … }`）。
- 另有 Tailwind 别名 `--color-syntax-keyword: var(--syntax-keyword);` 等（`tailwind/colors.css`）。
- **落地建议**：代码配色请直接读 `--syntax-*`（v1 语义），不要去找 `--v2-syntax-*`。

### 5.4 light / dark 切换机制（**三层**）

源码 `packages/ui/src/styles/tokens/theme.css` 中三块：

1. **`:root`（默认 = light）** — L2-141：`color-scheme: light;` + 全部 light 取值。
   注意 L143-258 有一大段 **`@media (prefers-color-scheme: dark)` 被整段注释掉了**（带 `/*…*/`），说明「纯 CSS 跟随系统」已被**主动弃用**，改为 JS 驱动。
2. **`[data-color-scheme="light"]`** — L260-383：显式 light（覆盖系统偏好）。
3. **`[data-color-scheme="dark"]`** — L386-509：显式 dark。

选择器是 **`[data-color-scheme=dark]`（属性选择器，无引号也能匹配）**，写在 `<html>` 上。

**运行时切换逻辑**在 `packages/ui/src/theme/context.tsx`：

```ts
const [store, setStore] = createStore({ themeId, colorScheme, mode, … })
const mode = colorScheme === "system" ? getSystemMode() : colorScheme   // matchMedia('(prefers-color-scheme: dark)')

function applyThemeCss(theme, themeId, mode) {
  const tokens  = resolveThemeVariant(variant, isDark)      // v1 token → --background-base 等
  const v2      = themeV2ToCss(resolveThemeVariantV2(variant, isDark))  // v2 token → --v2-* 等
  const fullCss = `:root { color-scheme: ${mode};
    --text-mix-blend-mode: ${isDark ? "plus-lighter" : "multiply"};
    ${css}\n${v2} }`
  ensureThemeStyleElement().textContent = fullCss           // <style id="oc-theme">
  document.documentElement.dataset.theme = themeId
  document.documentElement.dataset.colorScheme = mode        // ★ 就是这里写 data-color-scheme
  document.documentElement.style.backgroundColor = isDark ? "#080808" : "#fafafa"
  meta[name=theme-color].content = isDark ? "#080808" : "#fafafa"
}
```

- localStorage keys：`opencode-theme-id`、`opencode-color-scheme`、`opencode-theme-css-light`、`opencode-theme-css-dark`。
- **闪烁防护（FOUC）**：`packages/app/index.html` 里 `<script id="oc-theme-preload-script" src="/oc-theme-preload.js"></script>` 在 `<body>` 之前同步执行，
  从 localStorage 读 theme/scheme，先设置 `documentElement.dataset.theme / dataset.colorScheme / backgroundColor`，并从缓存注入 `<style id="oc-theme-preload">`。
  → **Compose 侧对应做法：Application.onCreate / Activity 启动时同步读 DataStore 决定主题，避免首帧闪白。**
  （本地 vendored 副本：`app/src/main/assets/opencode-app/oc-theme-preload.js`，共 1395 字节，可直接对读。）

### 5.5 主题（Theme）生成管线：JSON → OKLCH 色阶 → CSS 变量

- 内置主题：`packages/ui/src/theme/themes/*.json`，共 **35+ 个**（`amoled, aura, ayu, catppuccin*, cobalt2, cursor, dracula, everforest, flexoki, github, gruvbox, kanagawa, material, matrix, mercury, monokai, nightowl, nord, oc-2, one-dark, onedarkpro, orng, osaka-jade, palenight, rosepine, shadesofpurple, solarized, synthwave84, tokyonight, vercel, vesper, zenburn …`）。
  **默认主题 = `oc-2`（展示名 "OpenCode"）**，见 `theme/context.tsx` L46-48 / L84。
- 解析：
  - v1：`packages/ui/src/theme/resolve.ts`（25KB）`resolveThemeVariant` / `themeToCss`。
  - v2：`packages/ui/src/theme/v2/resolve.ts`
    - `generateV2HueScale(seed, isDark)`：把种子色转 OKLCH，按 12 级固定亮度梯度 + 12 个 chroma 乘数生成 `100…1200`；
    - `generateV2NeutralScale`：由 `neutral`/`ink` 生成灰阶；
    - `mapV2Semantics(isDark)` → 语义 token；`mapV2Foreground(...)` → 前景色；`mergeV2Tokens(...)`
    - `themeV2ToCss(tokens)` → `--key: value;` 文本。
    - `V2_PRIMITIVES_DEFAULT`（`v2/default-primitives.ts`）是 OC-2 的原始色阶常量（与 `tokens/colors.css` 完全一致）。
- 主题 JSON schema：`packages/ui/src/theme/desktop-theme.schema.json`。

### 5.6 排版与间距

- 字号/字重（`packages/ui/src/styles/theme.css` L8-17）：
  `--font-size-small: 13px`（=.text-12-*）/ `--font-size-base: 14px` / `-large: 16px` / `-x-large: 20px`；
  `--font-weight-regular: 400` / `-medium: 500`；
  `--line-height-{tight:12px, compact:16px, base:20px, large:150%, x-large:180%}`；
  `--letter-spacing-{normal:0, tight:-0.16, tightest:-0.32}`。
- **正文实际值**：`document.body` 被改为 `text-[13px] font-[440]`（`app.tsx` L49-57）—— **440** 这个非常规字重对应 IBM Plex Mono Text 的 440。
- Tailwind 桥接（`packages/ui/src/styles/tailwind/index.css`）：`@theme { --*: initial; … }` 先清空默认主题，再逐个映射
  `--font-sans / --font-mono / --text-sm|base|lg|xl / --font-weight-regular|medium / --leading-* / --tracking-* / --radius-xs…xl / --breakpoint-sm…5xl / --container-3xs…7xl / --shadow-*`。
- 圆角：`--radius-xs: .125rem` → `--radius-xl: .625rem`（4/6/8/10 px）。
- 断点：`sm 40rem / md 48rem / lg 64rem / xl 80rem / 2xl 96rem / 3xl 112rem / 4xl 128rem / 5xl 144rem`。
  **代码内用 `767px` 判定 mobile**（`shell.tsx` 的 `createMediaQuery("(max-width: 767px)")`），而不是 `md`。

### 5.7 组件级样式分布

| 文件 | 位置 |
| --- | --- |
| ui 组件样式 | `packages/ui/src/**/*.css`，由 `packages/ui/src/styles/index.css` 用 `@import … layer(components)` 汇总（37 行） |
| session-ui 组件样式 | `packages/session-ui/src/components/*.css`，由 `packages/session-ui/src/styles/index.css` 汇总（14 行） |
| app 页面样式 | `packages/app/src/**/*.css`（`shell/titlebar/titlebar.css`、`shell/mobile-drawer.css`、`settings/settings.css`、`session/summary/summary.css`、`home/projects/view.css` …），再加 `packages/app/src/index.css` 里的全局组件层 |

`layer` 顺序统一为 **`theme, base, components, utilities`**。

---

## 6. 状态管理与数据流

### 6.1 用的是什么

| 关注点 | 方案 | 证据 |
| --- | --- | --- |
| 全局单例状态 | **`createSimpleContext`（自研）**：`packages/ui/src/context/helper.tsx`，产出 `{ use, provider }` 对 | 全仓 `export const { use: xxx, provider: XxxProvider } = createSimpleContext({…})` |
| 局部状态 | `createStore`（`solid-js/store`）+ `createSignal` + `produce`/`reconcile` | `shell/tabs/tabs.tsx`、`shell/commands/command.tsx` |
| 服务端缓存 | **TanStack Query**（`QueryClient` 关闭 `refetchOnMount/Focus/Reconnect`） | `app.tsx` L36-47 |
| 持久化 | 自研 `persisted(...)` / `Persist` / `removePersisted`（`packages/app/src/runtime/persistence/storage.ts`），带 schema 校验与命名空间 | `settings/model.tsx` L280：`persisted({ key: "settings.v3" }, settingsPersistence, defaultSettings)` |
| 事件总线 | `@solid-primitives/event-bus` + `makeEventListener` | `package.json` / `theme/context.tsx` |
| 无 | ❌ Jotai / Zustand / Redux / MobX / Valtio —— **一个都没有** | 全仓依赖表 |

### 6.2 分层 Provider（谁包谁）

```
ServersProvider        runtime/server/registry.tsx   服务器连接表（local/ssh/wsl/http），持久化在 runtime/server/persistence.ts
└─ SettingsProvider    settings/model.tsx            设置 store（settings.v3），Schema 校验
   └─ [Router root]
      ├─ TabsProvider        shell/tabs/tabs.tsx     打开的标签 + 关闭栈
      ├─ GlobalProvider      runtime/server/runtime.tsx   ★ 每个 Server 一个 ServerCtx
      ├─ CommandProvider     shell/commands/command.tsx  命令注册中心
      └─ HighlightsProvider  shell/updates/highlights.tsx
```
会话路由额外套一层 `ServerProvider`（`runtime/server/current.tsx`），由 URL 的 `:serverKey` 解析出连接
（`routes.tsx` 的 `TargetServerRoute` + `requireServerKey`）。

### 6.3 **SDK / 事件如何流进 UI**（任务 Q6 的重点）

三条通路，逐层都是 Solid 响应式：

**① 结构化数据（请求 / 缓存 / 乐观更新）**
```
HTTP API ──► @opencode/client/solid 的 createData()  ──► Data 对象
             (packages/client/src/solid/data.ts)           （data.location.command / mcp / provider / model / session …）
                                        └─► runtime/server/sync.tsx 的 createServerSyncContextInner(serverSDK, data)
                                            · bootstrapGlobal / bootstrapDirectory
                                            · createChildStoreManager → 每个 directory 一个 child store
                                            · createRefreshQueue（限流、暂停、bootstrapInstance）
                                        └─► 组件通过 useServerSDK() / useGlobal() 读取
```
证据：`packages/app/src/runtime/server/runtime.tsx` L8 `import { createData } from "@opencode/client/solid"`、
`packages/app/src/runtime/server/sync.tsx` L49 `createServerSyncContextInner(serverSDK: ServerSDK, data: Data)`。

**② 服务端推送事件（SSE / event stream）**
```ts
// packages/app/src/runtime/server/sync.tsx  L227-246
const unsub = serverSDK.event.listen((event) => {
  connection.handleEvent({ type: event.type })          // 连接健康/重连状态机
  if (event.type === "project.updated") applyProjectUpdate(event.data)
  if (event.type === "worktree.updated") { … }
  if (!event.location) {
    if (event.type === "config.updated" || event.type === "agent.updated") bootstrap.refetch()
    return
  }
  const key = directoryKey(event.location.directory)
  if (!children.children[key]) return
  children.mark(key)                                    // LRU 保活
  if (event.type === "config.updated" || event.type === "agent.updated") queue.push(key)
})
```
- 事件形状：`{ type, data, location: { directory } }`。**按 directory 路由到对应 child store**，未加载的目录直接丢弃（省内存）。
- 重连：`createConnectionSync({ status, invalidate, connected })` → 重连后 `invalidateQueries` + 按 `reconnectOrder` 重新同步活跃目录。
- 全局桥接：`packages/app/src/runtime/server/runtime.tsx` L119
  `listen: (handler) => sdk.event.listen((event) => handler({ name: event.type, details: event }))`

**③ 会话内渲染（时间线投影）**
- `packages/session-ui/src/timeline/projection.ts`（28.8KB）把原始消息/事件**投影成「行（row）」**，
  再由 `packages/app/src/session/timeline/controller.tsx` + `virtualizer.tsx` 虚拟化渲染。
- `packages/app/src/session/timeline/controller-projection.ts` 是 app 侧的桥。
- 增量 markdown：`session-ui/src/components/markdown-stream.ts` + `markdown-cache.tsx` + `markdown-projection.ts`。

**④ 其它 store**
- 文件树：`packages/app/src/workspaces/files/tree-store.ts`、`content-cache.ts`、`watcher.ts`。
- 终端：`packages/app/src/session/terminal/context.tsx`（PTY 会话，`runtime-adapters.ts` 负责宿主适配）。
- 通知：`shell/notifications/coordinator.ts` + `notification.ts`。

---

## 7. 给 Android / Jetpack Compose 复刻的落地要点

1. **目标版本先定**：v2 分支源码（2.0.9）结构最清晰，但 vendored 产物是 1.18.31。二者 token 一致、包名 scope 与目录不同。
2. **路由**：只有 4 个目的地（`Home` / `Settings` / `Session(serverKey,id)` / `NewSession`）。
   Compose 用 Navigation-Compose 时建议：单 Activity + `NavHost`，但**按 Desktop `MemoryRouter` 语义**做（URL 不暴露），
   并用 `SavedStateHandle` 复刻 `getLastActiveUrl/setLastActiveUrl` 的「恢复上次页面」行为。
3. **Shell 结构直接照搬 `shell.tsx` 的盒子模型**：
   `Column { Titlebar; Row { optional(vertical tabs aside); main } }`，`--shell-top-inset`/`--shell-bottom-inset` → `WindowInsets`。
   移动端把 Titlebar 放顶部或底部由设置项 `general.mobileTitlebarPosition ∈ {top, bottom}` 决定。
4. **Token 层建议做成 `ColorScheme + CompositionLocal`**，字段名与 `tokens/theme.css` 一一对应（`bgBase/bgDeep/bgLayer01-04/...`），
   并保留 `[data-color-scheme]` 的「显式覆盖系统」语义（`scheme ∈ {system, light, dark}`）。
   **不要引入 `--v2-syntax-*`（不存在）**；代码高亮用 `--syntax-*` 那一套。
5. **字体**：正文 Inter（或系统等效）+ 13px/440；等宽 IBM Plex Mono（440）；终端 JetBrainsMono Nerd Font。
   Android 需要自带 TTF/OTF 并注意 Nerd Font 的授权与体积。
6. **图标**：上游是自建 sprite（`packages/ui/src/(components)/*-icons/sprite.svg`）。
   建议把 `packages/ui/src/components/app-icons/sprite.svg`、`file-icons/sprite.svg`、`provider-icons/sprite.svg`
   导出为 PDF/VectorDrawable，而不是逐个手绘。
7. **Markdown**：Compose 侧最接近的替代是自研 AST 渲染 + 本地 Prettier/高亮方案。
   上游的关键设计值得复刻：**Shiki 在 Web Worker 里跑 + `@shikijs/stream` 流式高亮 + 有专门的 worker 队列和协议**（`markdown-worker-*.ts`），
   以及 `markdown-cache` 缓存已完成块的渲染结果。Android 上对应「后台线程 + 分块缓存 + 流式追加」。
8. **事件驱动**：核心是 `event.type + event.location.directory` 的路由模型 + 每目录 child store + LRU 驱逐 +
   按目录限流的 refresh queue。Compose 里可用「按 workspace 分片的 `ViewModel` + `StateFlow`」等价实现。
9. **性能基线可参考上游 e2e**：`packages/app/e2e/performance/timeline/*`（首帧导航、首页列表、标签切换、
   `timeline-stability/*`：滚动/交互/工具变更矩阵）—— 这些场景值得在你 Android 侧逐条建对应的 macrobenchmark。
10. **移动端已有专门的实现**：`shell/mobile-drawer.tsx|css`、`shell/mobile-panel-drawer.tsx|css`、
    `session/files/session-mobile-files.tsx|css`、`titlebar` 的 `mobile-tabs-trigger/drawer`、`tab-gesture.ts`。
    **这几处是「移动端交互」的一手规格，优先级最高。**

---

## 8. 本仓库内可对照的证据文件

| 用途 | 路径 |
| --- | --- |
| 完整 v2 文件清单（7698 条） | `research/_raw/tree-paths.txt` |
| 已下载的 v2 源码副本 | `research/_raw/files/packages__**__**.ts[x]\|css\|json` |
| v2 sparse clone（仅顶层，blob 已过滤） | `research/_raw/opencode-src/` |
| vendored 主 CSS（480KB，含全部 token） | `app/src/main/assets/opencode-app/assets/index-D_rl_FNw.css` |
| vendored 主题闪烁防护脚本 | `app/src/main/assets/opencode-app/oc-theme-preload.js` |
| vendored 构建版本记录 | `frontend/OPENCODE_APP_VERSION` |
| Android 侧已有补丁（MemoryRouter / 竖屏设置 / IME / 代理 / 标签 + 等） | `frontend/patches/*.patch` |

> 说明：GitHub REST API 在本次调研中触发过匿名限流（60 req/h）。已改用
> `git clone --filter=blob:none --sparse`（树对象全在本地，可离线 `git ls-tree`）+
> `raw.githubusercontent.com` 逐文件下载，二者不受该限流影响。若需再抓，沿用此路径。

---

## 9. 明确未能取证 / 存疑项

1. **`--font-family-text` 的最终来源**：v2 源码 `tokens/theme.css` 写的是 `var(--font-family-sans)`，
   而 vendored 1.18.31 产物里是字面量 `"Inter",sans-serif`（且同产物里 `--font-family-sans` 仍是系统栈）。
   两者是不同版本导致，**未逐行定位 1.18.31 的赋值处**。复刻以产物实测值为准。
2. **`packages/tui` 与 `packages/web` 的详细依赖**：仅按目录与 `AGENTS.md`/配置文件判断角色，**未逐一读其 `package.json`**。
3. **会话页内部 tab（聊天/更改/上下文/终端）的精确切换逻辑**：从文件目录与 `data-slot` 命名可确认存在，
   `session/screen.tsx`、`session/files/file-tabs.tsx` 未逐行阅读。
4. **设置面板 `settings/shell.tsx` 的具体 DOM 结构**：只确认是 tab 面板而非嵌套路由（来自 `pages.ts` + `navigation.tsx`），未读 `shell.tsx` 全文。
