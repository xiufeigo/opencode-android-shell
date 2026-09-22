# AUDIT A1 — Design System Delta（v2 frontend vs 原生实现）

> 方法：逐项比对 `_upstream` 只读源码与 `app/.../ui/theme` + `ui/components` 五个动效组件。不修改任何源码、不构建。
> 默认主题以 **oc-2**（`packages/ui/src/theme/themes/oc-2.json` 的 `v2Overrides`/`overrides`）为准；
> `mapping.ts` 的通用 dark 映射与 oc-2.json/`tokens/theme.css [data-color-scheme=dark]` 多处不一致（如下 layer-02~04），原生以 oc-2 为准是对的，不计为 delta。

## 已达 parity（抽查确认一致，无需改动）

- 灰阶 ramp（grey-50~1200）、alpha dark/light ramp、红/橙/黄/绿/青/蓝/紫/粉 ramp 取值：`tokens/colors.css:4-170` == `oc-2.json` == 原生注释转录（`OcTheme.kt` 用 CSS 顺序经 `c()` 换序解析，正确）。
- Light 背景组（bgBase/Deep/L01~L04/Inverse/Contrast/Accent/ButtonNeutral）、文本三件套、iconBase/Muted/Inverse/Accent、边框三件套+Focus：与 `oc-2.json light:159-191` 全对。
- Dark bgBase/Deep/L01~L04/Inverse/Accent/ButtonNeutral、文本四件套、iconBase/Muted/Inverse/Accent、边框组：与 `oc-2.json dark:410-442` 全对（contrast 除外，见 #1）。
- State 成功/警告/危险三组 × 明暗：与 `oc-2.json` 全对（dark info-bg 除外，见 #5）。
- Agent solids：除 light-build 外全对（plan/build-light? 见 #6、explore/review/writer 明暗）。
- 字体：Inter variable（wght 100–900，`res/font/inter_variable.ttf`）+ 440/530/500/600/610 档位：与 v2 用量一致（`OcFonts.kt`）。
- 圆角 xs2/sm4/md6/lg8/xl10：与 `--radius-*`（`styles/theme.css:45-49`、`tailwind/index.css:76-80`）全对。
- 动效数值已对：ring 350ms + `cubic(.65,0,.35,1)`（`progress-circle.css:27`）、softSpring `cubic(.22,1,.36,1)`、SWAP 150ms、shimmer step45/duration1200/swap220/angle90、spinner 几何+随机区间+内外圈透明度、session-progress 几何（5×5/pitch3/inset1.5/2px/16px/center12）+1200ms、tool-count 480/280/320/blur2px/.06em/.985/status240/.45/.03em、tooltip 120/80、select 120/80+translateY(-2px)、scrollbar fade 200ms+12dp hit strip、reduced-motion 二值塌缩机制。

## Delta 表

| # | Delta | v2 source (path:line) | Native file | Priority |
|---|-------|----------------------|-------------|----------|
| 1 | Dark `bg-contrast` 错误：原生 `#242424`（grey-1000），应为 `#5c5c5c`（grey-700） | `_upstream/packages/ui/src/styles/tokens/theme.css:396`；`oc-2.json:417` | `ui/theme/OcTheme.kt:122` | P0 |
| 2 | Light `overlayScrim` 错误：原生 `#00000099`（dark-60），应为 `#00000066`（dark-40）；浅色遮罩过深 | `tokens/theme.css:51`；`oc-2.json:196` | `ui/theme/OcTheme.kt:199` | P0 |
| 3 | Light `textAccentHover` 错误：原生 `#3250df`（blue-800），应为 `#0b34f4`（blue-700） | `tokens/colors.css:137`；`oc-2.json:177` | `ui/theme/OcTheme.kt:187` | P0 |
| 4 | Light `textCodeAccent` 错误：原生 `#263fa9`（blue-1000），应为 `#2c47c8`（blue-900） | `tokens/colors.css:139`；`oc-2.json:178` | `ui/theme/OcTheme.kt:188` | P0 |
| 5 | Dark `stateInfoBg` 错误：原生 `#1b2852`（不在 v2 ramp 内），应为 `#021945`（blue-1200） | `tokens/colors.css:142`；`oc-2.json:462` | `ui/theme/OcTheme.kt:152` | P0 |
| 6 | Light `agentBuild` 错误：原生 `#2c47c8`（blue-900），应为 `#3250df`（blue-800） | `oc-2.json:217`；`styles/theme.css:287` | `ui/theme/OcTheme.kt:213` | P1 |
| 7 | 代码高亮 ramp 系统性错误（light）：keyword `#a753ae`→应 pink-800 `#c83d8b`；string `#006656`→应 green-800 `#198b43`；primitive `#fb4804`→应 pink-800；property `#ed6dc8`→应 orange-800 `#d16427`；type `#596600`→应 purple-800 `#5230c2`（constant `#007b80` 唯一正确） | `oc-2.json:36-45`（light overrides） | `ui/theme/OcTheme.kt:217-224` | P0 |
| 8 | 代码高亮 ramp 系统性错误（dark）：keyword `#edb2f1`→应 pink-400 `#f799c6`；string `#00ceb9`→应 green-400 `#96e3a6`；primitive `#8cb0ff`→应 pink-400；property `#fab283`→应 orange-400 `#ffc1a4`；type `#fcd53a`→应 purple-400 `#9e99f7`；comment/weak `#8f8f8f`→应 text-muted grey-500 `#aeaeae`（constant `#93e9f6` 唯一正确） | `oc-2.json:287-297`（dark overrides） | `ui/theme/OcTheme.kt:160-167` | P0 |
| 9 | 缺失的语义色字段（两 scheme）：`text-contrast`（light grey-50 / dark grey-50）、`text-code-path`（light blue-900 / dark blue-300）、`icon-faint`（light grey-500 / dark grey-700）、`icon-contrast`（light grey-100 / dark grey-100）、`icon-accent-hover`（light blue-700 / dark blue-300）、`border-inverse`（light grey-1000 / dark grey-100）、`bg-code-path`（light cyan-100 / dark cyan-1200）、`bg-icon-button-contrast`（light=contrast / dark grey-400） | `tokens/theme.css:12,24,28,33-35,43,167-168`（light）；`:389-424`（dark）；`oc-2.json:159-186,410-436` | `ui/theme/OcTheme.kt:21-90`（`OcColors` 无这些字段） | P0 |
| 10 | 缺失 agent border/background（每 scheme 6 个 rgba token：plan/build/explore × border/background）：原生只有 5 个 solid，无边框/底色，tool 卡边框与底色无法还原 | `tokens/theme.css:73-80`（light）；`:451-458`（dark）；`mapping.ts:6-32` | `ui/theme/OcTheme.kt:76-80` | P1 |
| 11 | 缺失 project avatar 19 token × 2 scheme：`OcColors` 无 avatar 字段（`HomeProjects` 注释引用了 token 但颜色需调用方自拼） | `theme/v2/avatar.ts:1-48`；`tokens/theme.css:85-103,462-480` | `ui/theme/OcTheme.kt`；`ui/home/HomeProjectsList.kt:455` | P1 |
| 12 | 缺失 illustration layer 01~03（light grey-300/400/500；dark grey-900/800/700）与 gradient-depth、tab scrims（active/hover/scrim 三色 × 2 scheme） | `tokens/theme.css:52-56,131-133,431-435,506-508` | `ui/theme/OcTheme.kt`（无字段） | P2 |
| 13 | 缺失 legacy `--syntax-*` 其余项与 markdown/avatr-legacy：regexp/operator/variable/punctuation/object/success/warning/critical/info/diff-add/delete/unknown、`--markdown-*` 15 项、`--text-stronger` | `styles/theme.css:313-357`（light）；`:584-629`（dark） | `ui/theme/OcTheme.kt:81-89`（仅 8 个 syntax 字段） | P1 |
| 14 | Dark elevation 太淡且丢 ring 层：原生 `0x1E000000`（≈11.8%），应为 dark-30 `#0000004d`（30%）；且 4 层 box-shadow 被压缩为单层，丢了 `0.5px light-16` ring 与 `-0.5px light-6` 底边（dark 下表面分界主靠 ring） | `tokens/theme.css:482-490` | `ui/theme/OcTheme.kt:238-242`；`ui/theme/OcShadows.kt:19-25` | P0 |
| 15 | 缺失 elevation：button-neutral、button-contrast、elements、switch-off/on（原生只有 raised/floating/overlay；`OcShadows.kt` 甚至没有 overlay helper，只有 raised/floating） | `tokens/theme.css:115-128`（light）；`:491-504`（dark） | `ui/theme/OcTheme.kt:231-249`；`ui/theme/OcShadows.kt:27-35` | P1 |
| 16 | 缺失 legacy `--shadow-*`：xs/md/lg 及 `xs-border*`（含 focus/select 3px ring）、`lg-border-base`；popover-standard/tooltip-standard 用 `shadow-md`，原生无对应 | `styles/theme.css:51-88`；`tailwind/index.css:82-90` | `ui/theme/OcShadows.kt` | P1 |
| 17 | 缺失 `line-height-tight 12px` token（`OcType` 只有 compact16/base20）；tooltip 11px/12px 等小字无归宿，调用方只能错配 compact（12px 行高配 16px，偏高 33%） | `tokens/theme.css:138`；`AGENTS.md:16`；`tooltip/tooltip.css:23-25` | `ui/theme/OcTheme.kt:269-295` | P1 |
| 18 | 缺失全部 letter-spacing（normal 0 / tight -0.16 / tightest -0.32）：dialog 标题 -0.13px、select -0.04px、tooltip 0.05px 均无法表达 | `styles/theme.css:18-20`；`tailwind/index.css:72-74`；`overlays/dialog/dialog.css:66,88` | `ui/theme/OcTheme.kt`；`ui/theme/OcFonts.kt` | P1 |
| 19 | 缺失 leading-lg/xl/2xl（150%/180%/200%）：长文本/markdown 段落行高无 token | `styles/theme.css:14-17`；`tailwind/index.css:65-70` | `ui/theme/OcTheme.kt:269-295` | P1 |
| 20 | Mono 不是 IBM Plex Mono：原生 `FontFamily.Monospace`（Roboto Mono/ Noto），字宽/字形与 `"IBM Plex Mono", ui-monospace, …` 不同，代码对齐与 diff 列宽漂移 | `styles/theme.css:4-5`；`app/src/settings/model.tsx:19-26`；`app/src/index.css:21` | `ui/theme/OcFonts.kt:84,100`（有意为之，已文档化） | P2 |
| 21 | Terminal 缺 JetBrainsMono Nerd Font：woff2 无法装机，原生回退系统 mono，终端内 Nerd 图标有 tofu 风险 | `app/src/index.css:7-8`；`model.tsx:21-26` | `ui/theme/OcFonts.kt:92`（TODO(M5) 已记录） | P1 |
| 22 | `TextShimmer` 默认 14sp/W500 破坏继承纪律：上游 `font/letter-spacing/line-height: inherit`（`AGENTS.md`：覆盖写父级），原生默认值让调用方在 13px/440 父级里得到 14px/500 | `typography/text-shimmer/text-shimmer.css:20-25,40-43`；`ui/AGENTS.md:19` | `ui/components/TextShimmer.kt:65-67` | P1 |
| 23 | Shimmer 颜色错：原生 base=textMuted/peak=textBase（v2 语义色），上游 `--text-shimmer-base-color: var(--text-weak)` / peak `var(--text-strong)`（legacy 非 v2 色：light #8F8F8F/#171717，dark rgba 白 42%/93.6%） | `text-shimmer.css:10-11`；`styles/theme.css:164-167,435-438` | `ui/components/TextShimmer.kt:62,64` | P1 |
| 24 | Shimmer `SIZE_RATIO 3.6` 模型错误：上游 `--text-shimmer-size: 200% + 2×gutter`（gutter=6.2ch，随字形变），非常量 360%；`peak()` 推导方向（100%→0%）与按字符负延迟的相位关系未经 bundle 验证 | `text-shimmer.css:8-9,59-81` | `ui/theme/OcMotion.kt:288-320` | P1 |
| 25 | Spinner 用错 easing：原生 `FastOutSlowInEasing`（Material 0.4,0,0.2,1），上游 `ease-in-out`（0.42,0,0.58,1）；`OcMotion.easeInOutEasing` 已定义却未使用 | `components/spinner.tsx:43`；`styles/animations.css:2` | `ui/components/SpinnerDots.kt:128` | P1 |
| 26 | Spinner 默认 16dp vs 上游 CSS 宽 18px | `components/spinner.css:1-6` | `ui/components/SpinnerDots.kt:92` | P2 |
| 27 | SessionProgress 用 Linear 计相位，上游为 `ease-out`（自述 1.2s ease-out infinite both）；8 段透明度表（1→.75→.5→.2）为语义重构非 25 字面表（注释已自承，`SessionProgress.opacity`） | `OcMotion.kt:419-427` 自述；CSS 侧仅 `session-progress-indicator-v2.css:1-5`（颜色） | `ui/theme/OcMotion.kt:457-483`；`SessionProgressIndicator.kt:70-84` | P1 |
| 28 | SessionProgress reduced-motion 姿态不同：上游隐藏全部帧、仅显示中央 2px 实心 rect（其余全隐），原生保留 25 点 floor .2 + 中央点亮 | `session-progress-indicator-v2.tsx:38-49`；`.css:12-25` | `ui/components/SessionProgressIndicator.kt:51-55` | P1 |
| 29 | Toast 动效为虚构值：原生 TOAST 150/100+scale，上游无 pop keyframe，实为 transform 280ms `cubic(.2,0,0,1)` + opacity 160ms + height 280ms；另 `toast.tsx:286` 的 JS `matchMedia` 分支原生无等价 | `feedback/toast/toast.css:28-32`；`toast.tsx:286` | `ui/theme/OcMotion.kt:215` | P1 |
| 30 | Dropdown 关闭 150ms（非 100ms）且为 ease-out：`dropdown-menu-close 150ms ease-out`，原生 DROPDOWN exit=100+ease-in | `navigation/menu/menu.css:197-199` | `ui/theme/OcMotion.kt:205` | P1 |
| 31 | Popover/hover-card 关闭 150ms ease-out（非 100ms ease-in） | `components/popover.css:22-28,89-98`；`components/hover-card.css:26-57` | `ui/theme/OcMotion.kt:218` | P1 |
| 32 | DIALOG/PALETTE 来源不可验证：`dialog.css`（180 行）无任何 keyframe/animation；`contentShow/contentHide`、`command-palette-v2 .12s` 在 packages 内无出处；tab-popover 实际 `animation: none`（instant），原生 POPOVER 150/100 若被复用则反错 | `overlays/dialog/dialog.css` 全文；`app/src/shell/commands/dialog.css:7-11`；`app/src/shell/titlebar/tab-popover.css:26-45` | `ui/theme/OcMotion.kt:209,221` | P1 |
| 33 | 缺失 presence 动效系（terminal/side panel 200~240ms `cubic(.22,1,.36,1)`）：原生无任何 presence 模型（opacity/size/translate 三组 keyframe） | `app/src/index.css:98-198,200-271` | （无原生文件） | P1 |
| 34 | Reduced-motion 覆盖不足：原生枚举 16 条，实际 27+ 处（含 wordmark、mobile-drawer、settings/search/summary/project、markdown、session-review、tab-popover、`animated-wordmark.tsx:17` 与 `toast.tsx:286` 的 JS `matchMedia` 检查），遗漏处在省电/无动画模式下仍会动 | 见 `Grep prefers-reduced-motion` 27 命中（app/session-ui/ui） | `ui/theme/OcMotion.kt:100-165`；`ui/theme/OcReducedMotion.kt` | P1 |
| 35 | Scrollbar thumb 3dp vs 4px、颜色 iconMuted vs `--border-weak-base`（light `#DBDBDB`/dark `#282828`）、缺 `backdrop-filter: blur(4px)` 与 hover/dragging 加深态 | `components/scroll-view.css:52-95` | `ui/components/OcScrollbar.kt:109,112,190` | P1 |
| 36 | Scrollbar 虚构常量：`THUMB_ALPHA .45`、`HIDE_DELAY 900ms`、`MIN_SIZE .08`、minThumb 24dp（上游 `data-visible` 无超时值、无最小高） | `components/scroll-view.css:31-38,97-99` | `ui/components/OcScrollbar.kt:51,111,199-212` | P2 |
| 37 | `OcCountItem` gather 用 240ms（status-swap 值）驱动 blur/scale，但 summary 的 transform 是 480ms spring；prefix 槽（max-width 0→1ch、margin .45ch、translateX -.08em、150/165/132ms）与 label-suffix（250ms×4、translateX -.04em、blur .42）无模型 | `session-ui/.../tool-count-summary.css:31-47,71-94`；`tool-count-label.css:25-44` | `ui/components/OcOverlayMotion.kt:144-147`；`OcMotion.kt:494-520` | P2 |
| 38 | 未移植的动效组件/工具类：TextReveal（motion spring）、AnimatedNumber、TextStrikethrough（visualDuration .35/bounce 0）、Typewriter、LoaderV2、titlebar-update-loader、`fade-up-text`（0.4s+0.1s×30 stagger）、`badge-mask(-tight)`、`hover-reveal`、`truncate-start`（rtl 起始省略；原生只有 END 省略）、`pulse-scale` 1.2s、accordion slide 250ms | `components/text-strikethrough.tsx:19`；`motion-spring.tsx:1-8`；`styles/animations.css:16-141`；`tailwind/utilities.css:11-35`；`data-display/accordion/accordion.css:105+` | （无原生文件；`ChatView.kt:655` 仅 END 省略） | P2 |
| 39 | `EXIT_HOLD_SLACK_MS 32` 无上游来源（虚构一帧余量） | — | `ui/theme/OcMotion.kt:269` | P2 |
| 40 | 行高/字号用 sp（跟随系统字体缩放），CSS px 固定；`opsz` 14 默认、`slnt 0`、`tabular-nums`、13px≥16px 行高纪律（`AGENTS.md:17`）在原生侧无强制手段（`Unspecified` lineHeight + 缺 tight token） | `ui/AGENTS.md:14-19`；`tooltip.css:28`；`select.css:133-139` | `ui/theme/OcTheme.kt:277-293`；`OcFonts.kt` | P2 |
| 41 | Expand spring 映射未验证：Framer `{visualDuration:.35, bounce:0}` → `spring(NoBouncy, MediumLow)`，stiffness 具体值无换算依据（`basic-tool.tsx:63` 为 collapsible 唯一出处） | `session-ui/src/components/basic-tool.tsx:63`；`ui/src/components/motion-spring.tsx:1-8` | `ui/theme/OcMotion.kt:44-47` | P2 |

## 备注（非 delta，供后续 audit 定界）

- `mapping.ts` dark（layer-02 grey-600 / layer-03 grey-500 / layer-04 grey-400 / contrast grey-700 计 `mapping.ts:102-168`）与 `oc-2.json` dark（layer-02 grey-900 / layer-03 grey-800 / layer-04 grey-700 / contrast grey-700）及 `tokens/theme.css:389-396` 不一致——以后者（oc-2 默认主题 + 编译 CSS）为准，原生当前取值与 oc-2 一致（#1 除外），`mapping.ts` 视为通用回退过期值。
- 断点/容器/spacing  scale 属桌面布局系统，移动端无对应物，不计 delta。
- 原生 `c()` 的 CSS `#RRGGBBAA`→Android `#AARRGGBB` 换序正确（`OcTheme.kt:107-111`），此前全 palette 漂移已修复，不在 delta 内。
