# M6「动效与打磨」交付说明

> 范围：`ui/*`（Compose）动效层。未改数据层、`ChatView`、`MainActivity`，未改已验证的 M2–M5 组件语义。
> 权威依据：`research/03_motion_tokens.md`、`research/07_motion_copy_p2.md`。
> 校验：`gradlew :app:clean :app:testDebugUnitTest` 全绿（见 §5）。

---

## 1. 文件清单

### 新增（7 个源文件 + 1 个测试）

| 文件 | 内容 |
|---|---|
| `ui/theme/OcMotion.kt`（**扩展**，非新增文件） | M6 全部动效 token 与纯函数：进/退场时长表、16 条 reduced-motion 目标、shimmer 扫光模型、4×4 spinner 模型、5×5 进度点模型、计数器 gather 数值 |
| `ui/theme/OcReducedMotion.kt` | `ReducedMotion.isReduced()` 纯函数 + `LocalOcReducedMotion` + `ProvideOcReducedMotion()`（`Settings.Global.ANIMATOR_DURATION_SCALE` + `ValueAnimator.areAnimatorsEnabled()`，`ContentObserver` 跟随系统开关） |
| `ui/components/TextShimmer.kt` | tool running 标题 / 「Thought」/「Exploring」的 `Brush` 扫光（base 层 + sweep 层，220 ms swap 淡出） |
| `ui/components/SpinnerDots.kt` | 4×4 点阵 spinner + `SpinnerModel`（模块级随机表） |
| `ui/components/SessionProgressIndicator.kt` | 5×5（25 点）会话进行中跑马灯 |
| `ui/components/OcScrollbar.kt` | 自绘滚动条（12 dp 命中条 + 3 dp 细条）+ `OcScrollbarModel` 纯几何 |
| `ui/components/OcOverlayMotion.kt` | `OcEnterOnMount` / `OcEnterExitVisibility` / `OcCountItem` 三个统一封装 |
| `app/src/test/.../OcMotionM6Test.kt` | 26 个 JVM 单测（数值表、纯函数、契约不变量） |

### 接线（改动）

| 文件 | 改动 |
|---|---|
| `ui/App.kt` | `OcTheme` 内包一层 `ProvideOcReducedMotion` |
| `ui/overlay/DialogHost.kt` | 图层进出场（scrim 只淡、内容 `.98→1`+淡）；`close()` 改为「标记 closing → 播完退场再出栈」 |
| `ui/session/timeline/PartRenderer.kt` | tool running 标题→`TextShimmer`；running 时尾部挂 16 dp `SpinnerDots`；Thought 标题→`TextShimmer`；删掉 M1 的 `shimmerAlpha()` 呼吸 |
| `ui/session/timeline/ContextToolGroupCard.kt` | `tool-status-title` 双槽 blur/位移交叉淡入（active 槽带 shimmer）；计数器逐桶 `OcCountItem` |
| `ui/session/timeline/MessageTimeline.kt` | 叠加 `OcScrollbar`（按 header/composer 高度内缩） |
| `ui/session/drawer/SessionDrawer.kt` | 新增 `runningSessionId`，运行中会话行显示 25 点indicator |
| `ui/session/SessionScreen.kt` | 传 `runningSessionId`（`RunStatus.RUNNING`） |
| `ui/session/header/SessionHeader.kt` | ⋮ 菜单 `Popup` 内容加 `OcEnterOnMount(MENU)` |
| `ui/session/composer/Composer.kt` | `@` 补全弹层改 `OcEnterExitVisibility(POPOVER)` |
| `ui/session/composer/ModelChip.kt` | M3 `DropdownMenu` → v2 `menu-v2-content` 自绘 `Popup` + `OcEnterOnMount(DROPDOWN)` |

---

## 2. 与 `research/03` 的逐条对齐

| 项 | 实现值 |
|---|---|
| 进/退场 | 9 个 overlay 家族：MENU/SELECT/TOOLTIP/PALETTE 120→80、DROPDOWN/DIALOG/TOAST/POPOVER/DEFAULT 150→100（`ease-out` / `ease-in`，`Overlay.allWithinRhythm()` 单测断言 120–150 / 80–100） |
| scale | `.96`（dropdown/menu/tooltip/popover）、`.98`（dialog / command palette）；SELECT 额外 `translateY(-2px)` |
| expand | 保留 M2 的 `spring(dampingRatio=1, StiffnessMediumLow)` |
| reduced motion | `durationMs(base, reduced)` 统一置 0；`ReducedMotionTarget` 枚举精确列 16 条 CSS 规则与各自降级方式 |
| shimmer | 1.2 s linear infinite、`-45 ms × index` 逐字相位、`360%` 拉伸、`5.2ch` 半带宽、90deg、220 ms swap；`peak(u)=2.6u-0.8` 由 `background-size:360%` + `background-position:100%→0%` 推导 |
| spinner | 16 点 `3×3 rx=1`、pitch 4、viewBox 15；四角灭、外圈 `.15↔.35`、内 2×2 `.4↔1`；`duration=1+rand*1 s`、`delay=rand*1.5 s`；**进程级一次**（对齐 `research/07` §3） |
| 进度点 | **几何从 bundle 读出**（`index-CKBO9Mty.js` @1680971）：`viewBox 0 0 16 16`、`x=1.5+i%5*3`、`y=1.5+⌊i/5⌋*3`、`rect 2×2`、25 点 = 5×5、`data-dot=12` 为正中；1.2 s、base `.2`、居中点恒 1 |
| 计数器 | 480 ms `cubic-bezier(.22,1,.36,1)` 宽度 + 280/320 ms 淡入淡出 + `2px` blur + `.06em`/`.985` 位移缩放（`OcCountItem`） |
| status title swap | 240 ms；隐藏槽 `blur(2px×.45)` + `translateY(.03em)` |
| 滚动边缘渐隐 | 未做（见 §4）；改为自绘滚动条（brief 指定） |
| 流式光标 | **未实现**（`research/07` §1 定案）；单测断言 motion token 中不存在 cursor/caret 命名 |

---

## 3. 关键决策（需 reviewer 知晓）

1. **shimmer 用「整行一条多段 `Brush`」而非逐字 `Text`**。CSS 每字一个 `background-clip:text` 盒；Compose 里逐字 `Row<Text>` 会破坏 shaping，故按「每字 3 个采样点、各自相位」把波形烘进**一条** `Brush.horizontalGradient`（stop 位置严格递增）。`5.2ch` 按「1ch ≈ 1 字符盒宽」折算（Inter 下 digit ≈ .6em、字母 ≈ .5em，纯 CSS 无法在 Compose 里精确换算），已在注释中标注该假设。
   - 代价：`TextShimmer` 要求调用方**不要**给它 `fillMaxWidth()`（渐变为文本自身宽度归一化）。
2. **25 点 indicator 的 9 段 opacity 表只实现语义、未逐张复刻**。bundle 里只找到 `dot-0 / dot-12 / dot-17` 三张表（`research/03` §7 亦列为未取得）。实现取「索引越大相位越靠后、衰减 1→.75→.5→.2、中点恒 1」。
3. **进度点放在会话抽屉的行尾**。bundle 中该组件用在 session-row / tab 的加载槽（`f(WG)` 替换项目头像）；本壳该行没有头像槽，故选行尾、不遮挡标题与选中条。
4. **spinner 放在 tool 卡尾部**（16 dp）。`research/03` §2.4/§3.5 明确「工具卡内统一 16×16」，但 JS 里 `yu`（spinner）的三个调用点都在会话/工作区行，未在 tool 卡内确认到调用点 —— 按 research 的结论落地，注释已说明。
5. **ModelChip 的下拉换成自绘 Popup**（本里程碑唯一被替换的既有组件）。M3 的 `material3.DropdownMenu` 无法承载 v2 的 `menu-v2-content` token（layer-01/radius 6/160–368 dp）与 150/100 进出场。`SettingsComponents` / `ServersPanel` 的两处 M3 菜单**未动**（M4 范围，避免回归）。
6. **DialogHost 的 `close()` 变为异步出栈**：图层多存活 `exitMs + 32 ms`，期间 `LocalDialogActive` 仍为 true（全局快捷键继续被抑制，符合上游「动画期间不响应 chord」）。
7. **命令面板不再叠加第二段动画**：它本就是 dialog 图层，`DialogSize.LARGE` 走 `Overlay.PALETTE`(120/80)、其余走 `Overlay.DIALOG`(150/100)，因此一层只播一次。上游 `command-palette-v2.css` 自身只有 120 ms 背景/阴影过渡，无 scale。

---

## 4. 未做 / 明确偏离

| 项 | 说明 |
|---|---|
| 滚动边缘渐隐（`*-fade-*`） | `research/03` §2.7 明确它是 scroll-timeline 驱动的**可见性开关**，不是时长动画；brief 未列入 M6，未做 |
| 停止按钮 `scale .95↔1.12` 脉冲 | `research/03` §2.6 列为 no-preference 媒体查询内；本壳停止按钮为静态图标，未加脉冲（避免新增未验证交互） |
| `paced reveal 24ms`（`research/07` §1） | `MarkdownStream` 的合并节奏由 M1 交付，M6 未改（brief 只要求「不实现流式光标」） |
| tooltip 进出场 | 本壳无 tooltip 组件（只有 `contentDescription`），`Overlay.TOOLTIP` 已定义但无宿主 |
| 滚动条仅接 `MessageTimeline` | 会话抽屉/Home 列表较短，未接 |
| 抽屉（drawer）进出场 | `research/03` §3.4 明确 CSS 中 `drawer` 0 命中；仍用 M3/M2 的 `ModalNavigationDrawer` 默认动画 |
| shimmer `5.2ch≈字符盒` 折算 | 见 §3.1，属近似而非 1:1 |
| 25 点几何的**相位表** | 见 §3.2，属近似 |

---

## 5. 测试

`app/src/test/java/com/xiufeigo/opencodeshell/OcMotionM6Test.kt`（26 例，纯 JVM，无 Compose runtime）：

- overlay：9 家族进出场时长区间、逐家族关键帧值、scale 0.95–1、enter/exit spec（含 reduced 归零）、`exitHoldMs`
- reduced motion：`isReduced` 三种系统状态、9 个时长常量归零、16 条目标表（数量/唯一性/降级类型）
- shimmer：常量、`charPhase(i)=i*45/1200`、`peak` 三点（-0.8 / 0.5 / >1.7）、`intensity` 极值与值域、逐字相位差
- spinner：几何/分组划分/呼吸区间、随机表范围、两次抽样不同、reduced 静态姿态
- 进度点：5×5 几何、`x/y` 公式、中点恒 1、值域 [.2,1]、后点相位滞后
- 计数器：6 个 gather 数值
- 滚动条：反序列表 thumb（底=progress 1、顶=0、中段 0.3–0.6）、短列表降级、`indexForProgress` 往返
- 不变量：motion token 内**不存在** cursor/caret 命名（呼应「无流式光标」定案）

命令：`gradlew :app:clean :app:testDebugUnitTest` → **BUILD SUCCESSFUL**，
`50 suites / 390 tests / 0 failures / 0 skipped`（`OcMotionM6Test` 26 例全绿）；唯一编译告警是既有的 `ChatView.kt:107 Condition is always 'true'`，与 M6 无关。
