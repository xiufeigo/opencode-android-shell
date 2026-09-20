# Android 原生重构计划：与 anomalyco/opencode v2 Web 前端 1:1 对齐

> 版本：v1.1（P2 取证已并入）　作者：Team Lead　日期：本次会话
> 依据：`research/01_frontend_architecture.md`（架构）、`research/02_ui_screens.md`（UI 逐屏 2293 行）、
> `research/03_motion_tokens.md`（动效/设计 token）、`research/04_data_api.md`（数据层 716 行）、
> `research/05_palette_host_session.md`（命令面板宿主/tab/设置 DOM）、`research/06_terminal_selection.md`（终端内核选型）、
> `research/07_motion_copy_p2.md`（流式光标/字体/spinner 定案）、
> 以及 `research/_i18n/{en,zh,zht}.json`（三语文案）、`research/_raw/files/`（49 份上游源码副本）。
> 本文件把七份调研合并为**一套可执行的 Android 原生（Jetpack Compose）重构与 1:1 验收口径**。
> **v1.1 变更**：第二轮 P2 取证已结案并并入——命令面板宿主定案（§4.4/§9-i）、终端内核选型定案 A′（§4.6/§9-a,a2,a3）、无流式光标/字体族 Inter/spinner 随机定案（§1.3/§1.6/§9-e,g,h）；实机像素基线以 vendored 产物为准。

---

## 0. 结论速览（给决策者两分钟看完）

- **做什么**：推翻现有「WebView 内嵌 @opencode-ai/app + View 系原生聊天 MVP」混合前端，改为一套完整的 **Jetpack Compose 原生前端**，全屏行为/视觉/动效 **1:1 对齐 anomalyco/opencode v2 Web 前端**。
- **保留不推翻**：已被验证可用的**数据层**（`OpenCodeClient` / `EventStream` / `ChatStore` 三件套）经过**三项必改修正**后继续用作网络层；`LocalGateway`（WebView 专属）在 WebView 退役后**可停用**。
- **技术基座**：Kotlin + Jetpack Compose + Material3（仅取组件，不用 M3 配色，视觉走自建 v2 token 主题）+ OkHttp(+SSE/WS) + 协程/Flow + `StateFlow` + 单 Activity + Compose Navigation（自研 back stack 语义）+ Markwon(仅渲染)或换 Compose Markdown + 终端用 Android 原生 VT 模拟器（见 §4.6）。
- **权限基线**：前端视觉/行为完全照抄 vendored 官方产物（像素基线），**交互语义与结构照抄 dev 分支源码（行为规格）**；两者 token 体系一致，冲突处以**本计划列出裁决**（见 §1.1）。
- **交付形态**：分 7 个里程碑（M0→M6），每个里程碑有独立可验收目标；WebView 可并行保留到 M4 作为「终端」过渡方案，M5 起被原生终端替换。
- **三个必须先修的数据层缺陷**（否则流式/多目录必然出问题）：`/event` 缺 `directory` 参数、`ChatStore` 未处理 `message.part.delta` 追加合并、运行态改用 `session.status`。

---

## 1. 调研结论（四份笔记合并后的关键事实）

### 1.1 版本基线裁决（最重要，先读）

`research/01` 曾提出「vendored=a1.18.31、GitHub v2=2.0.9，存在版本错位」。**该标签已被 `research/02` 实测推翻**：vendored bundle `index-CKBO9Mty.js` 含 dev 分支独有标识（`session-review-v2`×46、`file-tree-v2`×10、`settings-v2`×7、`command-palette-v2`×3、`NewHome` 装配、`ghostty-web` 动态 import、`terminal.toggle`=`ctrl+` 等），即**vendored 产物本身就是 dev 分支 UI**。

- `research/_raw/opencode-src/`（blobless clone 快照）**较旧**，缺 `settings-v2`/`pages/home`/`timeline`，**不作为 UI 规格**。
- **裁决**：
  1. **像素基线 = vendored 构建产物**（`app/src/main/assets/opencode-app/`）；任何「类名/数值」读这里（用 `research/_raw/tree-paths.txt` 与 CSS 检索）。
  2. **行为/交互规格 = dev 分支源码**（`research/02` 已给逐文件 URL）；`02` 已按这份源码逐屏核实。
  3. **`--v2-*` token 体系两版本一致**，是跨版本稳定的唯一权威 token 源。
  4. 凡 `02`/`03` 标注「未找到/未确证」的项，**不要臆造**，进「待取证」清单（§9）。

### 1.2 技术栈实况（用于理解移植对象）

| 维度 | opencode v2 实况 | Android 原生对应 |
|---|---|---|
| UI 框架 | SolidJS 1.9 + Vite 8 + Tailwind v4 + Kobalte 0.13 | Jetpack Compose |
| 状态 | 自研 `createSimpleContext` + `createStore` + `persisted(schema)` + TanStack Query | Kotlin `StateFlow` + Repository + 一个 AppContainer |
| 路由 | `@solidjs/router`（Web）/ Desktop `MemoryRouter` | Compose Navigation；单 Activity + 自研返回栈 |
| Markdown | marked + shiki(WebWorker) + DOMPurify + KaTeX + Mermaid | 首选 Compose Markdown 渲染；语法高亮需载入 Shiki 四色 → Kotlin Token |
| 终端 | **Ghostty(libghostty wasm)**（非 xterm） | Android 原生 VT 模拟器（见 §4.6） |
| 图标 | 自建 SVG sprite | 打包同样的 sprite SVG → Compose `ImageVector`/`Painter` |
| 字体 | Inter(正文) + IBM Plex Mono 440(等宽) + JetBrains Mono(终端) | 打包同一 TTF 子集，`FontFamily(File)` |
| i18n | en/zh/zh-TW 全套 | `research/_i18n/*.json` 直接转 `strings.xml`/Compose 资源 |

路由只有 4 条（也即原生目标顶级页面）：`/`(Home)、`/settings`(设置 x-large 弹窗)、`/server/:serverKey/session/:id`(会话)、`/new-session`(新建)。**无 `/project`、`/terminal` 路由**；「底部 tab 栏」不存在，底部栏 = 把 Titlebar 移到下方（设置 `general.mobileTitlebarPosition∈{top,bottom}`）。「外观/系统通知/音效/高级」是 Settings-**General 内 section**，不是导航一级项。

### 1.3 设计 token 体系（-> Compose 主题，核心值见 `03` 全表）

主题切换机制：`:root`=light 默认 → `[data-color-scheme="dark"]` 覆盖；**没有 `.dark{}` 块**；`data-theme` 仅 legacy 覆盖。Android 对应 `AppTheme(light/dark)` + 跟随 `isSystemInDarkTheme()`，默认色板即 OC-2。

关键语义值（dark/light 对，`dp`/`sp` 按 1px=1dp 近似；half-pixel 边框 `.5px` 要照做，见 §6）：

| 语义 token | dark | light |
|---|---|---|
| `bg-base`（页面层） | `#161616` | `#ffffff` |
| `bg-deep`（最底） | `#080808` | `#fafafa` |
| `layer-01/02/03/04` | `#242424/#2e2e2e/#3a3a3a/#5c5c5c` | `#fafafa/#f2f2f2/#eeeeee/#dbdbdb` |
| `text-base / muted / faint / accent` | `#fafafa/#aeaeae/#808080/#a2bcff` | `#161616/#5c5c5c/#808080/#3b5cf6` |
| `icon-base / muted / accent` | `#dbdbdb/#808080/#a2bcff` | `#3a3a3a/#808080/#3b5cf6` |
| `border-base / strong` | `#ffffff1a / #ffffff33` | `#0000001a / #00000033` |
| 状态 bg/fg/border(以 danger 例) | `#461516/#f17471/#97252b` | `#fceceb/#b82d35/#f2bbb7` |
| `radius`：xs2 / sm4 / md6 / lg8 / **rounded-xl=10px** | | |
| spacing 阶梯 | 4px 基准（`--spacing`） | |
| 阴影 | `elevation-raised/floating/overlay`（多层 box-shadow 见 `02` §0.5） | |

**语法高亮用无前缀 `--syntax-*`**（不是 `--v2-syntax-*`，后者在源码与产物 CSS 中 0 命中）：keyword/property/type/string/constant/primitive/comment。AI mention 着色：`@file`→property 色、`@agent`→type 色、`@reference`→keyword 色。

**字体（P2 定案，`research/07_motion_copy_p2.md`）**：正文 **13px/440**；**字体族=`"Inter",sans-serif`**（`--font-family-text"/--v2-font-family-sans` 均字面量；须打包 `assets/Inter.ttf` wght 100–900 可变字体并映射 `FontWeight(440/530/610)`，否则观感偏细）。通用等宽 = 系统 mono 栈（`ui-monospace, SFMono-Regular, Menlo, ...`，**本构建无 IBM Plex Mono**）；终端 = `"JetBrainsMono Nerd Font Mono", ui-monospace,…`（fontSize 14）。字重结构：**正文 440(38 处)/强调 530(40 处)/组件 500(6 处)/markdown h1、h2=600、一处 610**。**`rounded-xl=10px` 不是 12px**。

### 1.4 六个「防想当然」纠错（决定 1:1 成败）

1. **工具卡没有左侧色条**；`BasicTool` 声明了 `icon` 但 JSX 不渲染图标。颜色只出现在 task 卡图标色 `--task-agent-color`、错误卡图标 `var(--card-accent)`。→ Android 方案：**图标着色 + `layer-01` surface**，不画左竖条。（推翻旧 `plan-chat-ui-fidelity.md` 的「左 2dp 竖条」设计）
2. **reasoning 不是 "Thought" 折叠面板**：DOM 两层直接渲染 13px muted markdown；全仓无 "Thought" 串。只有 `ui.sessionTurn.status.thinking="Thinking"/"思考中"` 的 shimmer 行（运行中提示）。
3. **`PART_MAPPING` 只注册 4 种**：`tool/compaction/text/reasoning`；**file/agent/error 不注册**，assistant 里的 file/agent 不渲染（只在 user 高亮与 composer）。
4. **不存在 "Session/Changes/Context" 三 tab**：桌面=Review | Context | 动态文件；**移动=只有 Session / Changes 两个**。
5. **context ring 不在 titlebar**，在**消息时间线吸顶标题行内**（h-12=48px）。SVG 圆+dasharray，`size=14/strokeWidth=1.5/viewBox=14/r=6.25`、`rotate(-90deg)`、`transition:stroke-dashoffset .35s cubic-bezier(.65,0,.35,1)`。**无 >80% 变红阈值**，仅 0–100 钳制。Tooltip 三行 Cost(USD)/Usage(%)/Tokens。
6. **tab bar 的 `+` 不弹菜单**，直接 `openNewTab()`（tooltip="New session"）。菜单式 `+` 只在侧栏/终端面板。（Android 壳旧补丁改过 + 行为，重构后回正为新建会话。）

### 1.5 数据层事实（`04` 结论）

- **同一 server 并存两套 HTTP 面**：
  - **v1 legacy 无前缀**：`/session`、`/event`、`/file`、`/find`、`GET /session/{id}/diff?messageID` …
  - **v2 `/api/*`**：`/api/session`、`/api/event`、`/api/fs/list|find`、`interrupt` 等（模型 `SessionMessage`，`type` 判别，**无 part 概念**）。
  - **当前 Android 数据层走的是 v1 面**（正确）；v2 面作为后续可选项。
- **SSE 信封已实证**：v1 `/event` 服务端写死 `{id,type,properties}`；心跳是 **10s 一条真实 `server.heartbeat`**（不是注释行）。
- **事件类型（87 种已枚举）**，v1 关键：`message.updated{sessionID,info}`、`message.part.updated{sessionID,part,time}`、`message.part.removed`、`session.status{type:idle|busy|retry,...}`（**`session.idle` 已 deprecated，别用**）、`permission.asked/replied`、`question.*`、`todo.updated`、`session.diff`、`session.error`、`pty.exited`。
- **`message.part.delta`** = **字符串追加**语义（`part[field] += delta`，field 通常 `"text"`），不是替换。
- **Part 12 种**：text/reasoning/tool/file/agent/subtask/step-start/step-finish/snapshot/patch/retry/compaction；官方 `SKIP_PARTS={patch,step-start,step-finish}`；**ToolState 四态字段不同**（`output` 只在 completed、`error` 只在 error）。
- **PTY/WS 不是 JSON 协议**：下行=裸 UTF-8 + 一帧 `0x00`+`{"cursor":N}`；上行直接写 PTY；改尺寸走 `PUT /pty/:id {size}`；ticket 走 query + header `x-opencode-ticket:1`；重连退避 `min(250*2^min(tries,4),4000)`。
- 用户清单中若干端点不存在（`/terminal`、`/app`、`/api/session/{id}/abort|diff|prompt_async`、顶层 `/message`）——**实现时以 `04` 清单为准**。

### 1.6 动效清单（`03`，细节见该笔记）

- 进/退场：菜单/下拉/tooltip 进 120–150ms ease-out、**退 80–100ms ease-in**（退场更快）。
- **tool running 标题 shimmer**：`TextShimmer`，`duration 1.2s`、每字符 `animation-delay=-45ms×index`、`size 360%`、`angle 90deg`、`background-clip:text`→ Compose = `Brush` 渐变扫过文字（显式保留 `-45ms×i` 相位差）。
- **spinner 是 4×4 像素点阵**（不是旋转环）：16 个点、四角恒灭、外圈 .15↔.35、内圈 .4↔1.0、`duration=1+rand*1s`、`delay=rand*1.5s`（**P2 定案：确证随机、无 seed；`NMe` 是模块级 const ⇒ 模块装载时生成一次、同页面共用**；Android 可随机、无需固定 seed）。
- 会话进行中 = 25 点 opacity 跑马灯（`1.2s ease-out infinite`，非 pulse）。
- 圆环 spinner（标题栏）：`.67s linear infinite rotate`、12×12/2px，`--v2-icon-icon-accent`。
- 展开动画：`spring{visualDuration:.35, bounce:0}`；文本出现 `{duration:.32, ease:[.16,1,.3,1]}`；shell 命令宽度 `{visualDuration:.25}`。
- **`prefers-reduced-motion` 16 条降级**都要实现（Android `Settings.Global.ANIMATOR_DURATION_SCALE` 为 0 时全关）。
- **✅ P2 定案：无流式光标**。`blinking-cursor` 是死代码（全库 1 处=CSS 规则自身，JS 0 引用；`▍/█/░`、`caret`、`cursorBlink` 在聊天文本 0 命中）。流式"打字感"来自 **paced reveal**：每 24ms 推进 2/4/8/≤256 字、落后 >512 字直接补齐，词边界 `[\s.,!?;:)\]]`，**不画光标**。→ Android 不实现闪烁光标；若需打字感用 Compose `AnimatedContent`/逐字符 reveal 复刻 paced reveal。
- 滚动条全部自绘：12px 命中条 + 4px/blur(4px) 细条。

---

## 2. 「1:1」的定义与验收口径

所谓 1:1，指**可观测行为**在以下四层与 opencode v2 **逐像素/逐字一致**：

1. **视觉规格**：宽高/间距/圆角/字号/字重/颜色/阴影/边框粗细（含 `.5px` 半像素边框）与 vendored 产物一致。
2. **内容规格**：文案（en/zh/zh-TW）与 i18n key 一致；占位符、空状态、工具标题映射、分组标题一致。
3. **交互规格**：点击/展开/选择/聚焦/hover(点击下等价) 的命中区、状态转换、默认值/默认展开、键盘(场景等价于硬键盘) 行为一致。
4. **动效规格**：时长/缓动/相位差/降级规则一致（Compose 用等同动画学）。

**明确 NON-1:1 / 平台合理改编（写进 §9 作为公开偏差）**：
- 桌面鼠标 hover 在触摸屏无 hover 态 → 用 pressed/长按替代（`research/02` 已有手势说明）。
- Ghostty wasm 无法在 Android 原生跑 → 用 Android VT 模拟器（A′，§4.6）+ 同一 PTY/WS 协议；**终端功能面仅对齐字体/配色/行列尺寸**（无 OSC8 超链接/imgcat/Sixel，§4.6/§9-a）。
- 无流式闪烁光标（官方即无，§1.6）；打字感用 paced reveal 复刻。
- Web 快捷键(Ctrl+K 等) → Android 硬键盘同等映射；软键盘无则经由触摸路径。
- 滚动条自绘但实现引擎不同（Compose Lazy 惯用法），视觉近似即可、不逐像素断言滚动条。

---

## 3. 目标架构（Jetpack Compose 分层）

```
┌────────────────────────────────────────────────────────────┐
│ App(单 Activity, MainActivity)                              │
│  Scaffold + BackHandler + WindowInsets(安全区/IME)           │
└──────────────┬──────────────────────────────────────────────┘
   Compose UI 层(纯 @Composable, 无逻辑)：
     screen/ : home / session / settings / palette / new-session
     ui/ : theme(OC2 tokens) · components(v2 组件) · motion  ·
           markdown · icons · typography
   ViewModel 层(StateFlow, VM 稽核)：
     HomeVM · SessionVM(会话/时间线) · ComposerVM ·
     SettingsVM · PaletteVM · TabsVM/BackStackVM
   ──────────────────────────────────────────────────────────
   Domain/Store 层(纯 Kotlin)：
     ChatStore(reduce) · PartModel(12 型) · SessionModel ·
     DiffModel · TodoModel · Directory/Worktree
   Data 层(现有三件套 + 修正)：
     OpenCodeClient(v1 REST) · EventStream(SSE, 带 directory) ·
     NativeWebSocketHub(PTY)  · AuthStore(localStorage→DataStore)
└──────────────────────────────────────────────────────────────┘
```
- **单 Activity + Compose Navigation**，复刻 desktop MemoryRouter 语义：启动恢复上次页面、返回栈按「Home → 会话」推进、BackHandler 逐层关闭(弹窗→内层→返回)。
- **不引 M3 配色**：Compose 只用其布局/组件基元，色彩全部来自自建 `OcTheme`（§3.3）。
- **数据流单向**：`EventStream` → `ChatStore.reduce` → `StateFlow<UiState>` → Compose 重组；文本增量走 `ListAdapter` 同款 diff(key=partID)。
- 现有 `LocalGateway` 在 WebView 退役前保留；退役后删除 gateway/`frontend/patches`/WebView assets。

### 3.1 模块/包结构（`app/src/main/java/.../ui/` 建议）

```
ui/
  navigation/  (NavHost, routes: home/session/settings/draft; BackStack)
  theme/       (OcTheme.kt, OcColors(dark/light).kt, OcType.kt, OcShapes.kt,
               Elevation.kt, tokens from _raw/files/...default-primitives.ts)
  components/  (ButtonV2, IconButtonV2, TextInputV2, SwitchV2, SelectV2,
               MenuV2, TooltipV2, DialogV2, TabsV2, ScrollView2, KeybindV2,...)
  session/     (timeline, message-part, tool-card, progress-ring,
               composer/prompt-input-v2, attachments, mentions, diff)
  home/  settings/  palette/  terminal/  span(grid: util/nav)
  markdown/    (renderer + shiki-token mapping + git-diff syntax)
  motion/      (InfiniteTransition presets: SpinnerDots, ShimmerText,
               ProgressMarquee25, Pulse, crossfade)
```
（`_raw/files/` 已含 DESIGN 关键组件的上游源码副本，逐组件对照移植。）

### 3.2 主题系统映射（token → Compose）

- 建 `OcColors` 数据类，含上表全部语义字段（bgBase/bgDeep/layer1..4/textBase/.../borderX/stateX*syntaxX/overlayX/elevationX）。提供 `OcDark`/`OcLight` 两份 + `OcTheme`(CompositionLocal)。
- `MaterialTheme` 直连不进：用 `CompositionLocalProvider(OcTheme)` 传递。需要 M3 基元的组件把 `OcTheme.colors` 映射成 `MaterialTheme.colors` 兜底。
- 主题/亮度切换：读取 DataStore `opencode-theme-id` / `opencode-color-scheme`（默认 oc-2/system），`isSystemInDarkTheme()` 跟随系统，等价于 Web `applyThemeCss` 切 `data-color-scheme`。
- 半像素边框：`.5px` = 用 `BorderStroke(width=.5.dp)`（Android 支持 subpixel，用 `Modifier.border`）或 1px+`gradient` 视觉近似，**验收按 0.5dp 声明**。

### 3.3 数据层修正（开工前三件事，`04` 已定位）

1. **给 `/event` 传 `directory`**：`EventStream.kt` 订阅时带 `?directory=<绝对路径>` 或 header `x-opencode-directory`（网关白名单已放行）。否则多目录/异地启动 server 会静默不收事件。
2. **`ChatStore.parseEvent` 补 `message.part.delta` 追加合并**（`part[field] += delta`，field 常为 text），否则丢流式文本。
3. **运行态改用 `session.status`**（`idle|busy|retry`），弃用 `session.idle`。

### 3.4 输入法 / 安全区 / 横竖屏

- Compose `WindowInsets.statusBars/navigationBars/displayCutout` padding 根布局（沿用现有 `MainActivity` 逻辑迁移）。
- IME：`Modifier.imePadding()`，composer 贴在键盘上；焦点 field `bringIntoView`。**软键盘开合与 `Monocle`/Vim 无关，本壳不劫持**。
- 横竖屏：手机竖屏走移动规格（单列 Home、session 底部 Titlebar 可选）；平板横屏(≥640/768/1024px 各断点) 走桌面规格（两栏 Home、右侧面板、vertical-tabs）。断点常量抄 `02`：`mobile<768px`、`lg≥1024px`、`md≥768px`。

---

## 4. 逐屏规格（照 `02`；色板/动效再引 `03`）

### 4.1 Home（移动单列 / ≥1024px 两栏）

- 外层整页一张卡：`m-8dp`→ bg-base + 圆角 10 + elevation-raised。
- 左栏 Projects（≥1024 才显示）：分组标题 28dp+530；项目行 h-28dp 圆角 6，hover bg-layer-01，右侧 hover 三点菜单 + 新会话；右键=打开同菜单；拖拽排序（长按 250ms 激活、位移 4px？——移动端**触摸禁止**）。空状态 = 顶部「添加项目」行 + (有的话)「最近关闭」。
- 右栏 Sessions：搜索框 h-36dp 圆角 6 bg-layer-02@60% 透明；会话行 h-40dp 圆角 6 hover overlay-hover，**只有 头像+标题+项目名**（无时间/model/token）；分组 Today/Yesterday/Older（sticky 顶部，滚动渐隐），上限 64；空状态居中「这里还没有内容 / 创建一个会话以开始。」+ 中性主按钮；骨架屏 = 标题 + 4×h-40 bg-deep @70%。
- 底部 HomeUtilityNav：Settings(gear)+Help 两个项；移动端在页面底部、桌面端在左栏底。
- **新建会话**：Home 右列悬浮「✎」按钮 → `/new-session`。

### 4.2 Session（核心，工作量最大）

- **时间线**：`LazyColumn`，锚定底部、追加自动跟随、距底 80dp 内视为底部、顶部让位 64dp、底部 spacer 64dp、虚拟化 overscan 50；会话「Thinking」shimmer 行 + reasoning 小标题 `TextReveal(travel:25,duration:700)`；**轮次(turn)间距 24dp、助理内 part 间距 12dp**。
- **吸顶标题行**：h-48dp，左 标题(可点击重命名 InlineInput)，右 **context ring** + ⋮ 菜单（重命名/分享/导出/归档/删除）。
- **context ring**：Canvas 圆环 `size14/stroke1.5/r6.25`，`roundCap?` 直译；`transition stroke-dashoffset .35s`；`usage=last tokenTotal>0 / model context limit`；点击→ 右侧 Review/Context 逻辑(移动端= Session/Changes 两 tab)。Tooltip 三行 Cost/Usage/Tokens。
- **消息渲染（PART_MAPPING=4，file/agent/error 不单列）**：
  - **tool 卡**：结构 = 图标槽(16)/标题(14px 500)/副标题(14px 400 muted 单行省略 tabular-nums)/chevron。4 状态：`pending|running`(统一) 标题 shimmer、不可展开；`completed` 停 shimmer+副标题(+edit/write/patch 带 DiffChanges)；`error` 转 `ToolErrorCard`(图标 accent、正文左缩进 24dp, 展开左移留 40dp 给复制按钮)。**图标着色 + layer-01 surface，无左竖条**。task 卡用 `--task-agent-color` 图标色。
  - **展开动画**：`spring{visualDuration:.35,bounce:0}`；默认展开规则 `partDefaultOpen`（bash/shell 取决设置、edit/write 非纯删除时展开、其余折叠）。
  - **default-open 设置**：`shellToolPartsExpanded`/`editToolPartsExpanded`。
  - **text(reasoning)**：13px muted markdown，`margin-top:16dp`。
  - **diff 汇总行**（轮次末）：sticky，`plural("{{count}} 个文件已更改")`，+N/-N 增删计数，折叠按 maxFiles=10，溢出「+{{n}} 个文件」，展开→按文件 Accordion→ `@pierre/diffs` 风格 unified/split（行高 24dp）。
- **grouped context 工具**（连续 read/glob/grep/list 合成组）：默认折叠；计数 read/search/list；“Exploring/正在探索”“Explored/已探索”；`todowrite` 隐藏。**计数进出场动画**（grid 0fr→1fr + blur + 微位移, 480ms, ease `cubic-bezier(.22,1,.36,1)`）。
- **composer（prompt-input v2）**：
  - 容器 `min-h-96dp` 圆角 10 bg-base elevation-raised；编辑区 `min-h-60 max-h-180 overflow-y-auto px-16 pt-16 pb-8` **13px/440/leading-5**；纯 CSS 自适应（**Android: EditText minHeight60 maxHeight180 + scrollbars, 不做无限增高**）。
  - 工具行 h-44dp px-8：左 [+ 附加→菜单(Images and files mod+U / Commands / / / Context @ / Shell !)] + [模型 chip maxW220 h28 + ProviderIcon 16@40% + ▾]；右 发送/停止 button h-28 圆角6 双层渐变, `stopping = working() && blank()`。
  - placeholder 逐字：`Ask anything, / for commands, @ for context...` / zh `随便问点什么， / 可查看命令， @ 可添加上下文...`。
  - **@-mention 弹层**：贴编辑区上沿、maxH320、圆角 12 elevation-raised；kind ∈ reference/agent/resource/file/command；file→syntax-property、agent→syntax-type、reference→syntax-keyword 着色；Enter 提交（非 Shift/非 composing）/ Shift+Enter 换行 / `/` 命令 / `!` shell。
  - 附件 chip：图片 58×46 圆角6 0.5dp 内描边；删除按钮 16dp 圆角 hover 显示；横向滚动 + 左右 24dp 渐隐遮罩。
- **提问/权限 dock**：`SessionQuestionDock`/`SessionPermissionDock`、todo dock(折叠)、followup(排队 ℕ),回滚 dock——对应 `permission.*`/`question.*`/`todo.*` 事件。
- **session-turn 间的 message 高亮**：`@agent→syntax-type`、`@file→syntax-property`。

### 4.3 Settings（`Dialog x-large variant=settings`，5 tab）

- **左导航只有 2 分组 + 5 tab**：Desktop(General/sliders、Shortcuts/keyboard) + Server(Servers、Providers、Models)；底部「OpenCode Desktop v…」。
- **General 面板内 section 顺序**：InterfaceNotice→General(语言/自动接受权限/Shell/推理摘要/shell展开/edit展开)→Appearance(配色 System/Light/Dark、Theme、界面/代码/终端字体)→System notifications(智能体/权限/错误 Switch)→Sound effects(Select)→Updates(desktop only)→Display(报表 n-zoom)→Advanced(文件树/命令面板/服务器状态/显示智能体)。
- 行结构 = title(13/530)+description(13/440 muted) + control；列表容器 layer-01 圆角 8 inset 0.5dp muted 边框；行 padding 20dp、底边 0.5dp border-base。
- **Servers**：搜索框(多 server 时)+ fuzzysort；排序 = 当前置顶→health(healthy 0/unknown1/unhealthy2)；行 = 健康指示点 + name+`v{ver} • username`；操作 = `Default` tag + ⋮。空态 "No servers yet"。
- **Add/Edit Server dialog**（专用 `dialog-server-v2`，非目录选择器）：URL(placeholder `http://localhost:4096`, autofocus) / 名称 / 用户名(默认 `opencode`)/密码；错误 11px danger；Enter 提交。**(Android 定制字段保留：`corsHint/errorAuth/errorCors` 文案，因为原生已反向代理、无需 CORS)**
- **Shortcuts**：GROUPS(G/S/N/ModelAndAgent/Terminal/Prompt)；录制交互；`formatKeybind` 非 mac 用 `+`；`unassigned`/`pressKeys`；冲突 toast。
- **▸ P2 已验证 DOM/尺寸（`05` §5）**：`div[data-component=dialog-v2][data-variant=settings][data-size=x-large]` > `dialog-container` > `dialog-content.settings-v2-dialog` > `dialog-body` > `div[data-component=tabs-v2][variant=settings][orientation=vertical].settings-v2` > `div[data-slot=tabs-v2-list]`（内含 `tabs-v2-section-title`×2 + 5 个 Trigger：general/shortcuts | servers/providers/models）+ `settings-v2-nav-footer` + 5 个 `.settings-v2-panel`。尺寸：list padding 12、gap 4、右侧 1px 边框、bg-layer-01；trigger wrapper `h-28dp/r-4dp`；**x-large 弹窗 = `min(100vw−32,980) × min(100vh−92,600)dp`**。⚠️ **实机以 vendored CSS 为准**：≤639px 时左导航变**顶部竖排导航条**（`root{flex-direction:column}`+`list{width:100%}`+`nav-footer{display:none}`），且 vendored 缺 dev 的 `tabs-v2-trigger` 基础样式（`05` §4 偏差，未能确证哪边更新）。
- **Providers/Models**：联网 `provider.list/model.list` 渲染（参照现有 Settings→服务器 流程原生化）。

### 4.4 命令面板（Ctrl+K≡`mod+k,mod+shift+p`；`mod+p`=文件选择器）

- 容器：宽 `min(vw-24,640)`，高 `auto min280 max480`，`margin-top max(48,(vh-480)/2)` 圆角12 elevation-floating （居中偏上）。
- 结构：搜索(36dp 圆角6 layer-02@60%, autofocus) + 结果滚动。item 三型 `command|file|session`。组标题(Commands/Files/Session)。session 挂 `command.category.session` 组。行高 36dp 圆角6，active overlay-hover；keybind 右侧。
- **≤640 竖屏**：行内文字**纵向堆叠**（标题一行 + 描述一行）。空查询 = COMMON_COMMAND_IDS(前 5)+recent file；Home = 命令+session。
- 选中：Enter 执行；选中文件 → 打开文件 tab(或 Context)；选中 session → 跳会话。
- **✅ P2 宿主定案（`research/05_palette_host_session.md`）**：会话内命令面板**不是会话页 JSX 挂载**，是 `dialog.show()` 推进全局 dialog 栈，真宿主=多态外壳 `DialogSelectFile`（`dialog-select-file.tsx:35-36`：`if(!filesOnly() && newLayoutDesigns()) return <DialogCommandPaletteV2/>`）。调用链：capture keydown → `showPalette→run("command.palette")` → 会话路由重定向 `command.trigger("file.open","palette")`（`session.tsx:1147-1154`）→ `openFile→dialog.show(DialogSelectFile)`（`use-session-commands.tsx:263-268`）。**Android 复刻 = 一个全局对话框层，Home/会话/new-session 三处共用 `DialogCommandPaletteV2`（Home 用 `DialogHomeCommandPaletteV2`）**。
- **✅ P2 修正 02 §6.0**：v2 布局下**会话内 Ctrl+K / Ctrl+P / 头部搜索框（session-header.tsx:300）三者打开的是同一个 v2 palette**，不再是 legacy 文件选择器（仅 `mode="files"` 入口走别支）。

### 4.5 右侧面板 / 文件树 / diff

- 容器 `aside#review-panel`：圆角10 + elevation-raised，宽度过渡 `240ms cubic-bezier(.22,1,.36,1)`；常量 `SESSION_PANEL_WIDTH_MIN=450dp`、`FILE_TREE_WIDTH_MIN=240dp`（桌面；移动端以 Sheet 形式呈现）。
- **移动端 tab = Session / Changes 两个**（不是三 tab）。
- **Context tab**：16 个统计项 + Token 分解条(h-2 圆角满, system/user/assistant/tool/other 五色) + System Prompt + Raw messages（**不是文件列表**）。
- **diff**：`@pierre/diffs` 风格，unified+split，行高 24dp，hunk 行 sticky。
- **文件树**：虚拟滚动，estimateSize 28 gap2；缩进 `INDENT_STEP=16dp`；变更标记 = **A/D/M 单字母徽标**（非 `+N/-N`）。
- **Review tab**：改动文件列表 `{{count}} 个文件已更改`。

### 4.6 Terminal（**最高风险项 · P2 已取证定案，见 `research/06_terminal_selection.md`**）

- **目标**：原生客户端用 **Android VT 模拟器**连接同一 PTY/WS 协议（§1.5）。**「视觉对齐 Ghostty」降级为「字体/配色/行列尺寸对齐」**——Termux VT 无 OSC 8 超链接、图片协议(imgcat/Kitty)、Sixel，Ghostty 功能面**不可能 1:1**（写进 §9 风险 a）。
- **✅ P2 硬修正（否决原 §4.6 的坐标）**：
  - 坐标 `com.termux:terminal-view` **不存在**。正确 = `com.github.termux.termux-app:{terminal-view,terminal-emulator}:v0.117`（**JitPack**；Maven Central 无任何 termux 制品）。
  - **License = Apache-2.0**（仓库根 `LICENSE.md` 将这两模块列为 GPLv3-only 例外，源自 jackpal），闭源可用；⚠️ **不要**连带 vendor `termux-shared`（含 GPLv3-only）。
  - AAR 已本机实测下载 + **javac 编译 POC BUILD SUCCESSFUL**：`TerminalEmulator(TerminalOutput, cols, rows, Integer, TerminalSessionClient)` 可在**不碰 JNI、不起本地进程**下驱动；`TerminalOutput.write(byte[],int,int)` 是唯一上行出口——**正是远程 PTY「终端回答/DSR/DA/OSC52/鼠标上报」回 WS 的匹配点**。
  - **唯一阻断点**：`TerminalView` 只能 `attachSession(TerminalSession)`，后者 final 且构造即 `JNI.createSubprocess` 起本地 shell；无 session 时 `mEmulator` 永不赋值 → **AAR「即插即用」接远程 PTY 不可能**。
  - 新风险：`libtermux.so` 段对齐=4KB → **Android 15/16KB page + targetSdk 35 下无法加载**；凡触 JNI 即炸。跳过 JNI 的形态天然免疫。AAR v0.117 API ≠ master 源码（构造器 5 参 vs 7 参），**不可混用**。
- **✅ 推荐 A′（vendor 两模块源码 + 最小补丁，~20–60 行）**：
  ```kotlin
  interface TerminalIo { fun write(bytes: ByteArray); fun updateSize(cols,rows,cellW,cellH); val emulator: TerminalEmulator }
  // TerminalView: 把 mTermSession.write*/updateSize 改为 mIo?.…，新增 attachIo(io)
  ```
  - `RemotePtyIo.write`→WS；`updateSize`→防抖 100ms 后 `PUT /pty/:id {size}`；`emulator=TerminalEmulator(SocketOutput(),cols,rows,2000,client)`；`SocketOutput:TerminalOutput`→WS。
  - 保留官方 IME(`onCreateInputConnection`)/handleKeyCode/文本选择/滚动/渲染。Compose：`AndroidView { TerminalView(it,null).apply{ setTerminalViewClient; setTypeface(JetBrainsMono); setTextSize } }`；尺寸由 `getFontWidth()/getFontLineSpacing()` 算 cols/rows。
  - 过渡形态：**吃 AAR 配假本地 session**（M5 前冒烟，每终端多 1 个 `sh` + 1 个 PTY fd，非终态）。
- **候选 B/C（P2 已逐条核实，作为后备排序）**：B1 `connectbot/termlib`（Compose+libvterm，形态最匹配但**无 Maven 坐标**，需自建 NDK 模块）→ B2 WebView 过渡 → C 自研 VT(≥2000–4000 行 + 2–4 人周,兜底不建议)。
- **PTY 接线**：`PUT /pty/:id`(size 100ms 防抖)+ ticket + `binaryType=arraybuffer` + 首字节 0 控制帧 `{cursor}` + **续传只用服务端 metaFrame 的 cursor（勿按本地 UTF-16 长度推算）** + 重连退避 + `MAX_TERMINAL_SESSIONS=20` + 关闭即卸载。
- **里程碑站位**：M4 暂用现有 WebView 终番过渡；M5 换 A′ 原生终端；兜底顺序 = **A′ → AAR+假 session → termlib → WebView**（写进 §9 风险 a）。

### 4.7 Tab 栏与「+」

- **▸ P2 确认 tab 双体系（`05` §次要项A），务必区分**：
  1. **应用级标签栏** = `context/tabs.tsx`（`useTabs`）：`Tab = SessionTab | DraftTab`，store 是 `Tab[]`、**无 active 字段**；切换 = `select`→`navigateTab`→`navigate(tabHref(tab))`；活动 tab 由 **URL + `recent.key`** 推导。
  2. **会话内面板 tab**（Review/Context/文件）= `helpers.ts:31 createSessionTabs`（纯派生）+ 真存储 `context/layout.tsx:994-1030`（`sessionTabs[key]={all:string[], active?}`，`setActive`，`all` 过滤掉 `"review"`）。
- Tab 模型 **只有 session/draft** 两种；高 28dp 圆角6；关闭 20×20 @ (top4,right4)；相邻分隔 1.5×12 @ top8；hover 渐隐 `--tab-title-fade-offset 4→24dp`。
- **`+` 直接新建会话**（openNewTab）。hover 预览卡 OPEN_DELAY=2000/SKIP 500。（触摸无 hover：长按/常显等价）

---

## 5. i18n 与文案

- 源：`research/_i18n/en.json` / `zh.json` / `zht.json`（从 vendored 抽取的三语 dict）。
- 转化：保留所有 key → `res/values/strings.xml`(en)、`values-zh-rCN`(zh)、`values-zh-rTW`(zht)。语言跟随系统（entry.html 的 `getLocale` 逻辑等价）。
- 特殊：`{{slash}}`→`/`、`{{at}}`→`@`、placeholder、占位符、工具标题映射(§1.4)、plural(`{{count}}`)在 Web=ICU，Android 用 `resources.getQuantityString` 或自定 plural。

---

## 6. 分阶段落地（Each milestone gated by 验收）

| 里程碑 | 内容 | 退出条件（可演示） |
|---|---|---|
| **M0 地基** | 引入 Compose；`OcTheme`(dark/light)+tokens；导航壳(Home)；单 Activity 迁移；数据三修正 | 启动进 Home；dark/light 切换；SSE 收事件到真实目录 |
| **M1 会话聊天** | 时间线(流式+diff) + 4-part 渲染(tool/text/reasoning/compaction) + composer(text/附图片/attachment) + @-mention + 发送/停止 + message.part.delta | 真机连本地 server 流式收到完整文本；附件/@ 可用；停止按钮两态 |
| **M2 会话增强** | context ring + 吸顶标题重命名 + tool 展开/默认展开/分组 context + 会话抽屉/切会话 + 打开标记 + error card + TodoDock | 时间线与官方 web 同目录逐屏对比一致 |
| **M3 Home/导航** | Home 双栏/单列响应式 + 项目/会话列表/分组/搜索 + 新建会话 + 返回栈恢复上次页面 + 底部/垂直 Titlebar | Home 空态/搜索/分组/打开标记/切会话 全通 |
| **M4 Settings+Palette** | Settings 5-tab 弹窗 + Servers add/edit + 配色/主题/字体/快捷键录制 + 命令面板(两宿主) + Providers/Models | 设置项持久化；快捷键录制；命令面板 ≤640 纵向堆叠 |
| **M5 右侧面板/文件/终端** | Review/Context 面板 + 文件树(A/D/M) + diff(unified/split) + **原生终端(M6 若失败则 WebView 过渡)** | 文件树/diff/终端 可用；Changes 面板 |
| **M6 打磨+动效** | 全套动效(shimmer/spinner/marquee/spring/crossfade) + prefers-reduced-motion + 滚动条自绘 + 字体微调(530/440) + 无障碍 | 动效逐项对照 `03` 全清单；截图 diff |
| （持续）**回归** | WebView 退役清理：删 `LocalGateway`/`frontend/patches`/WebView assets；gateway 停用 | APK 无 WebView 依赖，体积↓ |

**里程碑依赖**：M1←M0；M3 可与 M1/M2 并行（Home 与 Session 独立）；M4/M5 依赖 M2（会话上下文）；M6 收尾。

---

## 7. 团队分工（agent teams，成员统一 `opencodegoresponse/deepseek-flash`，max 推理）

- **Lead（本人）**：架构裁决、token 转换、跨屏规格分配、代码审校、动效学核对、验收口径。
- 并行成员（每轮给独立脚本+验收）：
  - `data-core`：OpenCodeClient/EventStream/ChatStore 三修正 + `message.part.delta` + `session.status` + PTY WS 模块。写 `research/_raw/files` 之外的新代码 + 单测。
  - `theme-tokens`：把 `_raw/files/...default-primitives.ts` + `tokens/{colors,theme}.css` 全量转 Kotlin `OcColors/OcTheme`（dark/light），产出数据类与 Compose 资源。产出 `research/_tokens_kotlin.md` 映射清单。
  - `motion`：`03` 动效清单 → Compose 组件（SpinnerDots/ShimmerText/Marquee25/Pulse/crossfade + reduced-motion）。
  - `screen-home`/`screen-session`/`screen-settings`/`screen-palette` 各自实现 + 对照 `02` 自检。
  - `verify`：启动「1:1 核对表」（§8），逐屏回读对照，产 `FAQ/research` 到 `research/_verify.md`。
- 协作契约：每个成员写 `research/_<scope>.md` 交付物 + 回执；Lead 负责交叉审校与拍板（尤其 tool 卡/context ring/palette 三处易错点）。

---

## 8. 验收清单（reviewer 逐项盖章）

- [ ] **Token**：`OcDark/Light` 与 `03` 表逐字一致（light/dark），无 hex 硬编码散落 Composable（走 theme）。
- [ ] **Home**：≥1024 两栏/移动单列；项目/会话空态文案逐字；分组 Today/Yesterday/Older；搜索。
- [ ] **会话**：时间线 left=Turn 24/tool 卡=图标着色+layer-01 surface（**无左竖条**）；context ring 在吸顶行且 `14/1.5/r6.25` 无变红；composer min96/编辑60–180/440；placeholder 逐字；**发送/停止两态视觉**；`message.part.delta` 追加生效。
- [ ] **Settings**：左导航仅 2 组 5 tab；增删服务器(含 Android 定制 CORS 提示)；快捷键录制；配色/主题/字体持久化。
- [ ] **命令面板**：入口 `Ctrl+K / Ctrl+Shift+P`(**`Ctrl+P` 不是**）；≤640 纵向堆叠；`command|file|session` 三型。
- [ ] **动效**：tool 标题 shimmer(1.2s, per-char -45ms)；4×4 spinner；25 点 marquee；进120–150/退80–100；reduced-motion 16 条全关。
- [ ] **终端**：PTY/WS 协议正确；重连退避；面板 tab 52dp；字体 JetBrains Mono。（V：若走 WebView 过渡则明确标注降级）
- [ ] **数据**：`/event` 带 directory；`message.part.delta` 合并；`session.status`；`part.delta` 不丢流式文本。
- [ ] **回归**：旧功能（附件/@/发送/中止/错误显示）不因重构而回退；**测试**（`OpenCodeClientTest`/`ChatStoreTest`/`EventStreamTest`）全绿。

---

## 9. 风险与开放决策

| # | 风险/存疑 | 处置 |
|---|---|---|
| a | **终端内核替换（最大风险）· P2 已定案** | 走 **A′（vendor termux 两模块 Apache-2.0 源码 + TerminalIo 补丁）**；兜底顺序 = A′ → AAR+假本地 session → connectbot/termlib → WebView 过渡。坐标修正为 `com.github.termux.termux-app:{terminal-view,terminal-emulator}:v0.117`（JitPack，非 `com.termux`）。功能面与 Ghostty 仅字体/配色/行列对齐（无 OSC8/imgcat/Sixel） |
| a2 | **终端 16KB 页面风险（P2 新增）** | `libtermux.so` 段对齐 4KB，Android 15+/16KB page + targetSdk35 无法加载。**用「跳过 JNI 的 A′ 形态」天然免疫**；若必须真 `TerminalSession` 需 NDK `-Wl,-z,max-page-size=16384` 重编 `.so` |
| a3 | **终端真机 IME/兼容待验证（P2 列 V2）** | `AndroidView`+`TerminalView` 中文输入法上屏、`TYPE_NULL`、Gboard/Samsung、Ctrl/Alt/Esc/Tab → M5 阶段专项 POC，是「像终端」的核心 |
| b | **版本基线**：vendored=dev UI（像素基线）与 dev 源码(规格) 潜在细微差 | 以 `02` 规格为准、`03` token 为准；`05` 发现 vendored CSS 有 ≤639px settings 覆盖而 dev 无、且缺 `tabs-v2-trigger` 基础样式 → **Android 实机规格以 vendored CSS 为准**，差异记录到 `_verify.md` |
| c | **v1/v2 HTTP 双面** | Android 坚守 v1 面（现层已正确）；v2 面列后续 roadmap，`04` 已给出 `session.*` vs `session.next.*` 命名断层警告 |
| d | **动效 `spinner` 随机种子（P2 定案）** | 确证随机无 seed；`NMe` 模块级 const ⇒ 模块装载生成一次、同页面共用。Android 可随机，无需固定 seed（要可复现则进程级缓存 16 组值） |
| e | **流式光标（P2 定案）** | **无流式光标**。`blinking-cursor` 死代码；打字感=24ms paced reveal(≤256字/步,>512补齐)，不画光标 |
| f | **扁平 scrollbar 自绘** | 用 Compose `LazyListState`+ overlay track 近似；不逐像素断言 |
| g | **`440/530/610` 可变字重（P2 细化）** | 打包 `Inter.ttf` wght 100–900；`FontWeight(440/530/610)` 直映射（正文440/强调530/h1-h2=600/一处610） |
| h | **`--font-family-text`（P2 定案）** | =`"Inter",sans-serif`；通用等宽=系统 mono 栈（**本构建无 IBM Plex Mono**）；终端=`JetBrainsMono Nerd Font Mono` |
| i | **会话内命令面板宿主（P2 定案）** | 全局 dialog 栈 + `DialogSelectFile` 分派（`dialog-select-file.tsx:35-36`）；三处共用 `DialogCommandPaletteV2`（Home 用 `DialogHomeCommandPaletteV2`）；修正 02：Ctrl+K/P/搜索同开一个 v2 palette |

---

## 10. 后续第一步（若你批准执行）

1. 按 §3.3 三条**先修数据层**并跑绿既有测试。
2. 建 Compose 骨架（M0）：`OcTheme` + 导航 + Home 空壳。
3. 派 M0 的两个并行成员（`data-core`、`theme-tokens`）各自产出并回执。
4. 以 M1 时间线+composer 作为首个「可与官方逐屏对比」的 vertical slice。

> 注：本计划基于 max-推理四路调研，凡标注「未确证」处都明确不臆造，留 P2 取证。