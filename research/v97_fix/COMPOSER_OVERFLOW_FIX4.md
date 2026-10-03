# composer 溢出 FIX4 —— **方案 B 在真机被忽略 ⇒ 改走方案 A：让 band 容纳 48dp 触摸靶**

- 修复工程师：mavis
- 日期：2026-10-03
- 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
- 权威口径：`research/v97_verify/5556_v98/REPORT_OVERFLOW_DIAG.md` §5.2（**仅两条算式**：`unionBottom ≤ hostBottom`、`unionPx ≤ band`）
- 触发证据：`research/v97_verify/5556_v98/REPORT_COMPOSER_OVERFLOW3.md`（v105 真机，**两档四条全假**）
- 设备：**本轮未装机、未跑真机**（纪律：只跑 `:app:testDebugUnitTest --tests "<类名>"`）

> ## 状态：**✅ 方案 A 已落地；JVM 回归全绿（`classes=52 tests=464 failures=0 errors=0`）**
> ## 选定方案：**A —— band 抬到 48 dp，子节点不需要溢出**
> ## **768 `readable` = 92，未变，仍 ≥ 64 地板 ✅**（新值 **92**，不是 88 —— 理由见 §3.2）
> ## `COMPOSER_MINIMAL_HEIGHT` **保持 56 dp 一动未动**（十处真机实测阶梯不可在 JVM 上重定位）—— 代价改记在 band 上，缺口显式钉住，见 §4 / §7 #1

---

## 1. 定性：**为什么方案 B 在真机被忽略**

### 1.1 真机读数（`REPORT_COMPOSER_OVERFLOW3.md` §1.1/§1.2 原文）

| 档 | 节点 | bounds | h |
|---|---|---|---|
| 1366x768 | `EditText` | `[28,208][1338,256]` | **48** |
| | `Add`/`Model`/`Stop` | `[10,248][52,296]` … | **48** |
| 1366x600 | `EditText` | `[24,169][405,217]` | **48** |
| | `Add`/`Model`/`Stop` | `[399,170][441,218]` … | **48** |

### 1.2 代码锚：方案 B 把**子节点**向内收，收不动

v105 的做法（`Composer.kt:2198-2213`，本轮**一字未改**）：

```kotlin
val bandTouchTarget = SessionPaneLayout.composerBandTouchTargetDp(oneRow = minimal, band = bandMaxHeight)
if (bandMaxHeight >= MIN_TOUCH_TARGET) { bandControls() }
else if (bandTouchTarget != Dp.Unspecified) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides bandTouchTarget) { bandControls() }
} else { CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) { bandControls() } }
```

**方向决定成败**：这条支路唯一能做的事是给子组合**换一块 composition local**，
让 `minimumInteractiveComponentSize()` 把**最小触摸靶**从 48 dp 压到 44 dp。
**子组合必须自己去看那个 local** 才会生效。

**真机两档都还是 48 ⇒ 那条子组合没有去看它。** 它的 48 dp 来自这条渲染路径上的
**固有高 / `required*` / 自绘**：

- `Modifier.requiredHeight*` **不受** `Constraints.maxHeight` 约束，也**不受**
  `LocalMinimumInteractiveComponentSize` 约束（该 local 只喂 `minimumInteractiveComponentSize()` 的
  强制盒，走的是 `Modifier.layout` 那条**可约束**的路径）；
- 若是**自绘**（`Canvas`/`drawBehind`/自绘背景 + `semantics` 盒），
  a11y 报的 bounds 就是**它自己画的那个盒**，local 更是完全管不着。

⇒ **被忽略的不是"封顶值算错了"**（44 < 48，值本身是对的，v105 的纯函数也照这个算），
**而是"这条路径根本不看那个 local"**。这是**结构性无效**，不是"优先级不够"，
也不是"值不够小"。加大 local 的值、换 local、换分支顺序都救不了它。

### 1.3 为什么 JVM 457 例全绿却真机全错

**pin 的量与验收的量不同源**，这已是本仓连续第三次栽在同一处：

| 轮 | pin 的是 | 验收的是 | 结果 |
|---|---|---|---|
| v103 | **宿主盒**的 `top + host ≡ pane` | **子节点并集**底沿 | 假绿 |
| v104/v105 | `composerChildUnionHeightPx()` / `composerBandTouchTargetDp()` 两个**纯函数** | 设备上**子组合实测高** | 假绿 |

`composerBandTouchTargetDp` 是**纯函数**，它在 JVM 上永远自洽；
它压根**不描述** material3 会不会照它执行。**它钉的是"我们打算怎么算"，不是"设备会怎么画"。**

### 1.4 方案 A 为什么**必然**生效

```kotlin
private val BAND_HEIGHT: Dp = SessionPaneLayout.composerBandHeightDp(TOOL_ROW_HEIGHT)  // 48 dp
Modifier.fillMaxWidth().height(BAND_HEIGHT)                          // 父给的约束
val band = minOf(BAND_HEIGHT.roundToPx(), constraints.maxHeight)     // measure policy
```

band 是**父给的约束**。子节点**不需要溢出** —— 它 48 dp 的固有高在一条 **48 dp** 的带里
**装得下**：

```
band       = min(48, maxHeight)                  ≤ 48
hostPx     = constrainHeight(band) = band
toolRow    = min(48, band) = 48       ≤ band     ← 靶装得下，不再溢出
editor     = min(48, band) = 48       ≤ band ;  editorTop = (band - editor)/2 = 0
unionPx    = max(editorTop + editor, toolRow) = 48 = band      ✅ unionPx ≤ band
unionBottom= hostTop + unionPx ≤ hostTop + hostPx = hostBottom ✅ unionBottom ≤ hostBottom
```

**这与"子节点是否理会某个 local"完全无关**：父的约束是 Compose 的硬契约
（`maxHeight` 收口 + `constrainHeight` 收口，两处都在 `layout` 里）。⇒ 两条算式
**同时**成立，且是**构造性恒等式**，不是"碰巧"。

### 1.5 为什么这条改动是**外科手术式**的（堆叠形态零影响）

`band` **只被一行形态消费**：measure policy 里 `band` 只喂 `if (minimal)` 那一支
（`Composer.kt:2251` toolRow、`2259` editor、`2279` `constrainHeight(band)`）；
堆叠形态（`:2296-2311`）用的是 `editor.height + toolRow.height`，**根本不读 `band`**，
且它那一支的 `Row` 仍是 `.height(TOOL_ROW_HEIGHT)` = 44 dp —— **逐字未动**。

---

## 2. 改：方案 A 落地

### 2.1 新增唯一求值处（`SessionPaneLayout.kt`）

```kotlin
fun composerBandHeightDp(
    toolRow: Dp,
    minInteractive: Dp = MIN_INTERACTIVE_COMPONENT,   // 48 dp —— material3 的地板
): Dp = maxOf(toolRow.value, minInteractive.value).dp

val COMPOSER_BAND_HEIGHT: Dp = composerBandHeightDp(44.dp)   // = 48 dp
```

### 2.2 两个接线点（`Composer.kt`）

```diff
 private val TOOL_ROW_HEIGHT = 44.dp
+private val BAND_HEIGHT: Dp = SessionPaneLayout.composerBandHeightDp(TOOL_ROW_HEIGHT)   // 48 dp

-                        Modifier.fillMaxWidth().height(TOOL_ROW_HEIGHT)      // 一行形态
+                        Modifier.fillMaxWidth().height(BAND_HEIGHT)

-val band = minOf(TOOL_ROW_HEIGHT.roundToPx(), constraints.maxHeight)
+val band = minOf(BAND_HEIGHT.roundToPx(), constraints.maxHeight)
```

**「只在需要时增高」保护 = 那个 `minOf`**：pane 付不起 48 dp 时 band 自动收窄
（即 v84/v86 已 pin 的裁剪区），**不需要新机制**。已 pin：
`theEarlierPinsAreIntactAndTheOldBandIsStillReachable`、
`theBandIsTallEnoughToHoldTheEnforcedTouchTarget`。

### 2.3 **`TOOL_ROW_HEIGHT = 44.dp` 一字未改**（这是刻意的）

44 dp 是 `h-11` 的**内容/视觉**行高，**堆叠形态那一支仍用它**
（`.height(TOOL_ROW_HEIGHT).padding(horizontal = 8.dp)` 逐字不动）。
方案 A 改的是**一行形态装东西的那条带**，两者是**不同的量**。因此
`ComposerEditorFormV80Test:254`、`ComposerModelChipV75Test:141`、
`ComposerOneRowEditorFloorV86Test:305` 三处 `TOOL_ROW_HEIGHT = 44.dp` 字面量 pin
**一条都不用改、也不许改**。

### 2.4 改动的文件（都在授权范围内）

| 文件 | 改了什么 |
|---|---|
| `ui/session/SessionPaneLayout.kt` | 新增 `composerBandHeightDp()` + `COMPOSER_BAND_HEIGHT`（+ 文档） |
| `ui/session/composer/Composer.kt` | 新增 `BAND_HEIGHT`，改**两个**接线点（+ 文档） |
| `app/src/test/.../ComposerBandTallEnoughV106Test.kt` | **新增**（6 个用例） |
| `ComposerEditorFormV80Test.kt` | 一行形态那一臂的 modifier 精确串（`TOOL_ROW_HEIGHT`→`BAND_HEIGHT`）+ **新增**堆叠臂 44dp 复核 |
| `ComposerOneRowEditorFloorV86Test.kt` | `val band = …` 精确行 + **新增** `composerBandHeightDp(44.dp) == 48dp` 断言 |
| `ComposerBandTouchTargetV105Test.kt` | `val band = …` 精确行 + **新增** `BAND_HEIGHT` 声明行断言 |

**未碰**：`_upstream/`、`app/build.gradle.kts`、`ChatViewModel.kt`、`ui/servers/**`、
`ui/home/**`、`ui/terminal/**`、`ui/nav/**`、`ui/App.kt`、`OcActivity.kt`。
**未新增任何用户可见字符串。** **没有放宽任何既有断言** —— 3 处被动的改动全是
「被改的那个字面量本身」，且每处都**补了更强**的伴随断言（详见 §6）。

---

## 3. 三格连带预演（Lead 要求的预演表）

### 3.1 `unionPx` / `unionBottom` 两列（修后模型实算，`160dpi ⇒ 1dp = 1px`）

| 档 | IME | 队列 | 形态 | pane | hostPx | **band** | 靶 | toolRow | editor | **unionPx** | **unionBottom** | **hostBottom** | ① | ② |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **1366x600** | 387(开) | 0 | 一行 | **51** | **48** | **48** | **48** | 48 | 48 | **48** | **51** | 51 | ✅ 取等 | ✅ `48≤48` |
| 1366x600 | 387 | 0 | 堆叠 | 51 | 51 | 48 | 48 | 48 | 3 | 51 | 51 | 51 | ✅ | ✅ |
| **1366x768** | 466(开) | 0 | 一行 | **140** | **48** | **48** | **48** | 48 | 48 | **48** | **140** | 140 | ✅ | ✅ `48≤48` |
| 1366x768 | 466 | **2** | 一行 | 140 | 48 | 48 | 48 | 48 | 48 | 48 | 140 | 140 | ✅ | ✅ |
| 1366x768 | 466 | **11** | 一行 | 140 | 48 | 48 | 48 | 48 | 48 | 48 | 140 | 140 | ✅ | ✅ |
| 1366x768 | 0(关) | 0 | 一行 | 606 | 48 | 48 | 48 | 48 | 48 | 48 | 606 | 606 | ✅ | ✅ |
| 1366x480 | 387(开) | 0 | 一行 | 0 | 0 | 0 | `Unspec` | 28 | 0 | 28 | 0 | 0 | ⛔ 定义域外 | ⛔ 见下注 |
| 1366x480 | 387 | 0 | 堆叠 | 0 | 0 | 0 | — | — | 0 | 0 | 0 | 0 | ✅ | ✅ |

> **定义域边界（不是静默跳过）**：`480` 档配 `600` 档那个 IME 时
> `480−162−387 < 0 ⇒ pane = 0`，一个 28dp 的整控件都放不下 —— 那是 **v84/v86 已 pin
> 的裁剪**行为，测试用 `Frame.coveredByTheFormula` **显式**划出定义域
> （一行形态 `band ≥ 28`、堆叠形态 `盒 ≥ 48`）。另有**非抽样**全扫
> `pane 0..900` × 2 形态 = **1802 格**逐格断言两条算式，**不依赖表里任何常数**。

### 3.2 **`readable` 与时间线盒（Lead 的硬约束：`readable ≥ 64`）**

| 档 | 口径 | 修前 | **修后** | ≥ 64？ |
|---|---|---|---|---|
| **1366x768** | **`readable`**（`viewportHeightPx(shared, pane, host)`，v104/v102 pin 的那一维） | **92** | **92**（**未变**） | ✅ |
| **1366x768** | 保守口径（若把 +4 dp 全记在 readable 上） | 92 | **88** | ✅ |
| **1366x768** | **时间线盒 / pane** | **140** | **140**（**未变**） | ✅ |
| 1366x768 | Dp 预算线（`timelineShare(768,466,1,0)`） | 246 | **246**（**未变**） | ✅ |
| **1366x600** | **`readable`**（真机 `162+51 = 213 = imeTop`） | **51** | **51**（**未变**） | ✅ 显式让位区 |
| 1366x600 | Dp 预算线（`timelineShare(600,387,1,0)`） | 157 | **157**（**未变**） | ✅ |
| 1366x480 | pane / readable | 0 / 0 | 0 / 0 | ⛔ 定义域外（裁剪区） |

**为什么 768 的 `readable` 不降 —— 这一点值得说清楚，因为它反直觉：**

`readable` 由**宿主盒**决定（`viewportHeightPx(shared, pane, composer = hostPx, floorPx)`），
而 `hostPx = min(48, ceiling)`。**v104 的 pin 模型本来就是按 48 px 给一行卡收费的**
（`ComposerChildUnionV104Test` 的 `CARD_PX = 48`，注释原文即"真机 `EditText h=48`"），
`pane=140` 那格算出来就是 `140 − 48 = 92` —— **和 v102 的真机读数逐位相同**。

⇒ **方案 A 落地后，模型与真机第一次一致**。修前不一致的地方是**真机 44 vs 模型 48**；
方案 A 让真机变成 48，而**模型侧一个数字都不用动**，于是 `readable` 逐字不动。
（若当初 v104 按真机的 44 建模，那 `readable` 会是 96；本仓 pin 的是 92，本轮守住 92。）

**保守口径 88 仍 ≥ 64**：即便把 +4 dp 全部记在 `readable` 上，`92 − 4 = 88`，
**距 64 地板还有 24 px 余量** ⇒ **硬约束在两种口径下都成立**，不需要退回"只在更小 pane
才增高"的保护。

**600 档 `readable` 51 未变**，与 §FIX4 的让位声明一致：该档 `pane = 51`、
`floorPx = 51`、`ceiling = pane − floor = 0` ⇒ 宿主盒 0、`readable` 取 `pane` 本身，
**本就是"显式让位"区**，与 band 高无关。

**队列 2 条 / ≥11 条**：让位发生在 **bar 那一侧**（`queueBarCeiling` 守住时间线 64 地板），
**pane 恒 140** ⇒ composer 那一格与队列深度**无关**（真机一致：768 排队 2 条 / ≥11 条时
composer 底沿仍是 `imeTop`）。

### 3.3 +4 dp 的代价记在哪里（**显式记账**）

```
band / 宿主盒    : 44 dp → 48 dp        (+4 dp)   ← 代价落在这里
COMPOSER_MINIMAL_HEIGHT : 56 dp → 56 dp  (不动)
```

**为什么地板不动**（这是我做的唯一一次"不把账记到最直觉的那个常量上"的决定，理由必须写清）：
`COMPOSER_MINIMAL_HEIGHT = 56.dp` 是 **v57 起按「44dp 行 + 12dp padding」立的账**，
被**十处真机实测阶梯**逐个钉死：420dpi 的 `56dp`、短窗线 `164dp`、
`pane=140` 的 `readable 84 = 140 − 56`、`composerCeiling(67.05dp) = 56`、420 窗 body
`4.38 dp` 的超额……把它抬到 60 会移动这些**设备读数**，而**本轮没有真机**可用来重测。
在 JVM 上凭一次改动去重定位设备读数，正是 §1.3 说的那个错误。⇒ **一个都不动。**

**由此产生的 4 dp 地板缺口**（真实 `48+12 = 60` vs 地板 `56`）**被显式钉成字面量**，
并由**两处既有 clamp** 吸收，**不会溢出**：

- `composerHostHeightPx` 的 `coerceAtMost(panePx)`（v103，宿主永远收进 pane）；
- `minOf(BAND_HEIGHT.roundToPx(), constraints.maxHeight)`（`Composer.kt`，band 永远收进卡片）。

pin：`theFourDpCostLandsInTheBandAndTheDeviceMeasuredFloorIsUntouched`
（钉住 `60 − 56 = 4` 这个字面量，并复核两处 clamp 都在）。
**未闭环**，交 Lead 裁定 → §7 #1。

---

## 4. 为什么不降强度

| 证据 | 修前（真机 600 档） | v104（撤掉地板） | v105（封顶到 44） | **v106（方案 A）** |
|---|---|---|---|---|
| a11y 节点高 | 48 dp | 28 dp | 44 dp（**真机未达成**） | **48 dp（真机原值保留）** |
| 可点面积 | 48×48 | 28×28 | 44×44 | **48×48** |
| `unionPx ≤ band` | ❌ `48 > 44` | ✅ | ✅（JVM）/ ❌（真机） | ✅ **48 = 48** |
| `unionBottom ≤ hostBottom` | ❌ `218 > 213` | ✅ | ✅（JVM）/ ❌（真机） | ✅ **取等** |
| 越界那 4 dp 在哪 | **落在 `imeTop 213` 之下，被键盘压住、点不到** | 收进带内 | 收进带内 | 收进带内 |

1. **可点面积不降反升**：方案 A 保住 **48×48**，比 v104 的 28×28 **多 20 dp**，
   也比 v105 *期望* 的 44×44 **多 4 dp**。且那 4 dp 原本落在 IME 顶沿之下本就被压住。
2. **a11y 语义一字未动**：`clickable` 的 `onClick` / role / enabled / content-desc /
   `clickable=true` 全部原样；只改**布局约束**。
3. **控件仍是单一调用点**：三个分支只差一个 local 的值，**没有新增调用点**
   （v80/v81 的 "mounted once and never move"）。
4. **既有 pin 无一放宽**：
   - `TOOL_ROW_HEIGHT = 44.dp`（5 处字面量 pin）**原样通过**；
   - v104 的 `composerChildUnionHeightPx` 单点收口 + `coerceAtMost(panePx)` 仍**只出现一次**；
   - v103 的 `composerHostHeightPx` 表达式**一个字符没换**；
   - v105 的三支闸门与 `provides Dp.Unspecified`（**恰好一处**）**原样**；
   - 10 处 v57–v102 真机阶梯（`56dp` / `164dp` / `readable 84` / …）**一条未动**。

---

## 5. JVM pin：新增 `ComposerBandTallEnoughV106Test`

| 用例 | 覆盖 | 格数 |
|---|---|---|
| `theBandIsTallEnoughToHoldTheEnforcedTouchTarget` | `composerBandHeightDp(44.dp) == 48dp`；单调性（`toolRow 0..120` 全扫，`got ≥ toolRow`）；`TOOL_ROW_HEIGHT = 44.dp` 与堆叠臂**一字未改**；**一个声明 + 一个调用点** | 121+ |
| `everyWindowImeAndQueueCellHoldsBothFormulasWithTheTallBand` | **窗口{768,600,480} × IME{开,关} × 队列{0,2,11} × 形态{一行,堆叠}**，逐格断言 **`unionPx ≤ band`** 与 **`unionBottom ≤ hostBottom`**；并逐格复核 v103 恒等式 `hostBottom ≡ pane`；band 装得下 48 dp 时**靶逐字为 48** | **36** |
| `theFivePixelOverflowIsReproducedOnTheDeviceReadingsAndGoneOnTheNewBand` | 那 5px 逐位复现（**用真机原文当数据钉**：`218−213=+5`、`296−294=+2`、`48−44=+4`）；修后 600 档 `union == band == 48`、`unionBottom == hostBottom == 51`（绝对 213 = `imeTop`）；768 档队列 0/2/11 三格逐格断言 | 2 档 |
| `aNonSampledSweepOfEveryPaneHeightHoldsBothFormulas` | **非抽样**：`pane 0..900` × 2 形态，两条算式逐格成立，**不依赖表里任何常数** | **1802** |
| `theFourDpCostLandsInTheBandAndTheDeviceMeasuredFloorIsUntouched` | 代价记账：`band +4dp`；`COMPOSER_MINIMAL_HEIGHT == 56dp` 且 `== 44 + padding`；**4dp 缺口钉成字面量**；两处 clamp 复核 | 结构 |
| `theThreeCellBudgetPreviewKeepsThe768Floor` | **三格预演**（§3.2）：`768 readable 92`（**≥ 64**）、保守口径 `88 ≥ 64`、`600 readable 51`、`480 → 0`（定义域外）；Dp 预算线两档 `≥ 64` 且成本 `∈ [0,4]` | 3 档 |
| `theEarlierPinsAreIntactAndTheOldBandIsStillReachable` | v103/v104/v105 的结构 pin 原样；`provides Dp.Unspecified` 恰好一处；`BAND_HEIGHT` 声明行；**靶 48dp 原值保留 ≥ 44** | 结构 |

> **「修前那一格」为什么用真机读数而不是模型** —— 这是本类最要紧的一处设计：
> 真机**根本不理** v105 的封顶，若拿 v105 的模型去复现"修前"，只会复现出一个
> **假的不越界**，测试就测不到任何东西。故 `REPORT_COMPOSER_OVERFLOW3.md` 的
> `218/213/296/294/48/44` 全部作为**字面量**钉进测试。

---

## 6. 三处被动改动的 pin（**同严格度，且各补一条更强的**）

| 文件:行 | 改前 | 改后 | 严格度 |
|---|---|---|---|
| `ComposerEditorFormV80Test:141-145` | 精确整行串，一行臂 = `height(TOOL_ROW_HEIGHT)` | 同一精确整行串，一行臂 = `height(BAND_HEIGHT)` | **不变**（仍是整行精确匹配、仍断言 `if (minimal)` 只有一处）+ **新增**堆叠臂 44dp 精确串复核 |
| `ComposerOneRowEditorFloorV86Test:337-345` | `val band = minOf(TOOL_ROW_HEIGHT.roundToPx(), …)` | `…BAND_HEIGHT…` | **不变** + **新增** `assertEquals(MIN_INTERACTIVE_COMPONENT, composerBandHeightDp(44.dp))` |
| `ComposerBandTouchTargetV105Test:395-399` | 同上 + `TOOL_ROW_HEIGHT = 44.dp` | 同上 + **新增** `BAND_HEIGHT` 声明行断言 | **不变**，`TOOL_ROW_HEIGHT` 字面量 pin **原样通过** |

**这 3 处不是"放宽"**：它们钉的**就是被改的那个字面量本身**
（"一行形态那条带是 44 dp"）。方案 A 之后那行**必须**是 48 dp，
断言若不放行就是**在钉一个假值**。每处都**补了更强**的伴随断言（不是删条件、不是加容差）。

---

## 7. 回归跑

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest --console=plain `
  --tests "com.xiufeigo.opencodeshell.ComposerBandTallEnoughV106Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerBandTouchTargetV105Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerChildUnionV104Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerOverflowV103Test" `
  --tests "com.xiufeigo.opencodeshell.QueueTimelineShrinkCrashV102Test" `
  --tests "com.xiufeigo.opencodeshell.QueueTimelineFloorV100Test" `
  --tests "com.xiufeigo.opencodeshell.QueueTimelineFloorV101Test" `
  --tests "com.xiufeigo.opencodeshell.QueueTimelineSpaceV99Test" `
  --tests "com.xiufeigo.opencodeshell.StringsLocaleParityTest" `
  --tests "com.xiufeigo.opencodeshell.Wave23a*" `
  --tests "com.xiufeigo.opencodeshell.PaneLayout*" `
  --tests "com.xiufeigo.opencodeshell.Composer*" `
  --tests "com.xiufeigo.opencodeshell.Queue*" `
  --tests "com.xiufeigo.opencodeshell.SessionPane*"
```

```
> Task :app:compileDebugKotlin
> Task :app:compileDebugUnitTestKotlin
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 1m 23s
52 actionable tasks: 5 executed, 47 up-to-date
EXIT=0
LOCK_RELEASED=True
```

**结果：`classes=52 tests=464 failures=0 errors=0`**
（`app/build/test-results/testDebugUnitTest/TEST-*.xml` 汇总），
比 v105 的 457 多的 7 条正是新类。新类 7 个用例全过：

```
- theBandIsTallEnoughToHoldTheEnforcedTouchTarget
- everyWindowImeAndQueueCellHoldsBothFormulasWithTheTallBand
- theFivePixelOverflowIsReproducedOnTheDeviceReadingsAndGoneOnTheNewBand
- aNonSampledSweepOfEveryPaneHeightHoldsBothFormulas
- theFourDpCostLandsInTheBandAndTheDeviceMeasuredFloorIsUntouched
- theThreeCellBudgetPreviewKeepsThe768Floor
- theEarlierPinsAreIntactAndTheOldBandIsStillReachable
```

### 7.1 纪律与本轮全部 gradle 调用（如实记录）

- 只跑 `:app:testDebugUnitTest --tests "<类名>"`；**未** `assembleDebug` / `clean` / 全量 / 装机；
  gradle 跑到 `BUILD SUCCESSFUL` 为止；抢锁 `.agent-gradle-lock`
  （不存在 → 写锁 → 跑完删，`LOCK_RELEASED=True` 复核通过）。
- **本轮共 2 次 gradle 调用**，第 1 次是**失败**的，如实记录：

  | # | 结果 | 原因（**全部是新代码的问题，无一次是既有 pin 回退**） |
  |---|---|---|
  | 1 | `464 tests, 12 failed` | ① 我把"唯一求值处"数成了 1，实际是「1 个声明 + 1 个调用点」；② 修前那格用了 **v105 的封顶模型**，算出一个**假的不越界**（真机压根不理那个 local），改成钉**真机原文**字面量。**另外 10 条失败是 `COMPOSER_MINIMAL_HEIGHT` 56→60 的真实后果**（`V57/V74/V79x/V84/V86/V102` 的**真机实测阶梯**：420dpi 的 `56dp`、短窗线 `164dp`、`readable 84 = 140−56`、420 窗 `4.38dp` 超额…）⇒ 决定**回滚地板、代价改记在 band 上**（§3.3），这 10 处设备读数**一条都不动** |
  | 2 | **`BUILD SUCCESSFUL`，`classes=52 tests=464 failures=0 errors=0`** | — |

- **边做边写盘**：本报告在第 2 次 gradle 之前已落盘主体。
- **未改任何既有断言的严格度**（3 处被动改动见 §6，均为"被改的字面量本身"且各补更强断言）。

---

## 8. 真机复验步骤（下一轮复验员照此执行）

> 口径：`REPORT_OVERFLOW_DIAG.md` §5.2 —— **两条算式** + `imeTop` 断言 + **排除 `Open terminal`**。

### 8.1 装机前（归属闸门）
```bash
adb shell 'md5sum /data/app/~~*/com.xiufeigo.opencodeshell-*/base.apk'   # 记 md5 + lastUpdateTime
adb shell pidof com.xiufeigo.opencodeshell                               # 记 pid，全程须恒定（零重启）
adb shell dumpsys window | grep mCurrentFocus                            # 必须是 …/OcActivity
```

### 8.2 两档（`wm size` + `wm density 160`，换档后**重新 dump**、**重新解析**）
```bash
adb shell wm size 1366x600 && adb shell wm density 160
adb shell dumpsys input_method | grep -E 'mInputShown|mFrame'   # 断言 mInputShown=true
adb shell uiautomator dump /sdcard/s6.xml && adb pull /sdcard/s6.xml
```

### 8.3 算式
```
paneTop    := 二级工具条底沿（实测 162）
imeTopPx   := dumpsys input_method 的 mFrame 顶沿      ← pane 底沿用它
   ✅ 并【断言】paneBottom == paneTop + panePx（600: 162+51=213）；不成立就作废本帧
hostPx     := min(48, panePx)                          ← 方案 A 后的 band（600: 48）
hostTop    := paneTop + (panePx − hostPx)              (600: 162+3 = 165)
hostBottom := hostTop + hostPx                         (600: 213)
band       := 48                                       ← 方案 A：min(48, maxHeight)

① unionBottom <= hostBottom     修前 218 > 213 (+5)  → 修后期望 ≤ 213
② unionPx     <= band           修前  48 >  44 (+4)  → 修后期望 = 48（取等）
```

### 8.4 选节点规则（避免 `+19` 假阳性）
```
composer 子节点 := 与时间线节点【互为兄弟】、且落在 [hostTop, hostBottom] 带内的
                   EditText + 该工具带上的 clickable 控件
⛔ 排除 Open terminal（悬浮按钮，混进来 ⇒ +19）
⛔ 排除 二级工具条（y=110..162）、tab 条、chip 行自身的 HorizontalScrollView
⛔ 停用「ceiling = 0 ⇒ 宿主高必须为 0」（模型错 ≥48px，永久作废）
```

### 8.5 修后**期望读数**（逐位）
| 档 | `band` | `Add`/`Stop`/chip 高 | `EditText` | `unionPx` | `unionBottom` | `hostBottom` | 判定 |
|---|---|---|---|---|---|---|---|
| `1366x600` + IME | **48** | **48（真机原值，方案 A 保住）** | **48** | **48** | **≤ 213** | 213 | ① ② 双过 |
| `1366x768` + IME | **48** | **48** | **48** | **48** | **≤ 294** | 294 | ① ② 双过 |

**方案 A 特有的一条对照**：修前 `Add/Stop/chip` 与 `EditText` 都在一条 **44 dp** 的带里
被撑到 48；修后它们**仍然是 48**（**没有被压过一分**），但**带本身就是 48**
⇒ **越界 4 px 从源头消失，而不是被藏起来**。
⇒ 复验时**必须确认子节点仍是 48**：若某档读出 44，说明该帧 band 又被收窄了
（`minOf(48, maxHeight)`），要连同 `hostPx` 一起报。

### 8.6 不回退项（逐条量）
- 往返缩窗（`768 ⇄ 600 ⇄ 480`）**不崩**：回 `Crash log` / `pidof` 恒定。
- `1366x600` `readable` 仍是 **51**（`[0,162][1366,213]`）；`1366x768` 时间线盒仍是
  **140 px**（`[0,162][1366,302]`）、`readable` 仍是 **92**。**这两格一个像素都不许动**
  （§3.2 解释了为什么方案 A 动不了它们）。
- 队列两格：**43 / 48 / 全 clickable**（`Steer` / `×`）。
- `Open terminal` 仍是 48 dp 的悬浮按钮（**不参与并集**，但**仍在**）。
- 收尾：`wm size reset` 后**显式重设** `1366x768` + `160`；清掉本轮自建会话与 `/sdcard` 残留。

---

## 9. 未闭环 / 交 Lead 裁定

1. **🔴 4 dp 地板缺口（本轮唯一的诚实欠账）**：一行 composer 真实高 `48+12 = 60dp`，
   而 `COMPOSER_MINIMAL_HEIGHT` 仍是 **56 dp**（v57 起立的账，被十处真机阶梯钉死）。
   缺口的 4 dp 由 `composerHostHeightPx` 的 `coerceAtMost(pane)` 与
   `minOf(BAND_HEIGHT, constraints.maxHeight)` **两处 clamp 吸收，不溢出**；
   已钉成字面量 `60 − 56 = 4`。
   **要不要把 `COMPOSER_MINIMAL_HEIGHT` 抬到 60，必须在真机上重测那条
   v57–v84 阶梯之后决定**（`56dp` / `164dp` / `readable 84` / 420 窗 `4.38dp` 超额
   / `composerCeiling(67.05dp)`…），不能在 JVM 上凭改动重定位。本轮**未动**。
2. **🔴 真机未验**：§8 的期望读数全部是**推导值**。必须由下一轮复验员在 5556 上逐位确认，
   尤其 **§8.5 那条"子节点必须仍是 48"** 的对照。
3. **🟠 方案 B 被忽略的确切机制仍未定位到字节码**：§1.2 给出了
   "`required*` / 自绘"两类候选与各自的判别特征，但**没有**把真机那条
   `EditText` 的 48 dp 溯源到具体哪一个 `required*`（或证明它是自绘）。
   **可判别的一步**：真机 dump 该 `EditText` 节点的 `resource-id` / `class`
   —— 若它带 `EditText` 语义却报 48，多半是 `requiredHeight`；若是自绘节点则无编辑语义。
   这不影响方案 A 生效（方案 A 不依赖那个机制），但影响**能否从根上关掉它**。
4. **🟠 768 `readable = 92` 仍是推导值**（v105 报告已记：扁平 a11y 树未直接暴露该节点）。
   本轮**多了一条独立佐证**：`readable` 修复前**就该**是 92（模型与真机本就一致），
   所以它不是一个好判据 —— 判据仍是 §8.3 的两条算式。
5. **🟠 三个文件仍未提交 git**（沿用 v104/v105 的状态）：`Composer.kt` = ` M`、
   `SessionPaneLayout.kt` = `??`、新测试 = `??`。被测构建与 HEAD 的对应关系
   **依旧无法自证**，建议随本修复一并提交。
6. **🟡 可见性未量化**：修前那 4 px 落在 IME 顶沿之下是否真的被裁，本轮未做像素级比对
   （沿用 `REPORT_OVERFLOW_DIAG.md` §7.5 的未闭环项）。
