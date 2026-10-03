# v99 修复 — 队列条把时间线压到 16px 且 100% 覆盖（U1）

- 修复工程师：mavis（U1）
- 日期：2026-10-03
- 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
- 上游依据：`research/v97_verify/5562_v98/REPORT_SWEEPD3.md` §2（**U1 = FAIL**）
- 写权限：仅 `ui/session/**` + 新测试文件。未碰 `_upstream/`、`app/build.gradle.kts`、
  `ChatViewModel.kt`、`ui/servers|home|terminal|nav/**`、`ui/App.kt`、`OcActivity.kt`。
- 未新增任何用户可见字符串；未降低任何既有断言强度；未跑 assembleDebug / clean / 装机。

> **状态：DONE（JVM 已闭环 · 真机待复验）** — 根因定位到 `文件:行号`；修法是**给队列条一个
> 由 pane 预算推导的天花板**，把「队列条优先」反转为「时间线 + composer 工具行先付、队列条拿余量」；
> 新增 10 例纯 JVM pin，**56 个类 513 例 0 失败**。

---

## 1. 根因

### 1.1 宿主：队列条是 **composer 自己的 Column 的第一个子节点**

`app/src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt:1342`

```kotlin
Column(
    modifier = modifier
        .fillMaxWidth()
        .onGloballyPositioned { SessionComposerBounds.bounds = it.boundsInRoot() },
) {
    if (queueSupported && queuedRows.isNotEmpty()) {
        ComposerQueuePanel(          // ← Composer.kt:1353，**第一个子节点**
```

它不是时间线的兄弟、不是 overlay、不是 `Box` 里的绝对定位层 —— **它是 composer 那个 Column 的
第一个子节点**，而那个 Column 的测量结果就是「composer 的高度」。

### 1.2 怎么参与高度分配：**被当成 composer 本体，一起从 pane 里扣**

链上四步，每一步都可读：

1. `ComposerQueuePanel.kt:89-92` — 面板内部 `LazyColumn` 只有 `heightIn(max = 148.dp)`，
   **没有与 pane 相关的天花板** ⇒ 面板在任何 pane 上都按**满内在高度**测量（2 行 = 82 dp）。
2. `SessionScreen.kt:1812` — `composerRegion` 的 Column 用
   `.onSizeChanged { composerHeightPx = it.height }` 上报**整摞**高度
   （队列条 + 卡片 + 区域 padding），面板的高度就此被折进「composer 高度」。
3. `SessionScreen.kt:2074-2078` — 传给 `SessionPaneArrangement` 的 `composer = { composerSlot(composerRegion) }`
   就是这整摞，所以 `host` 节点**包含队列条**。
4. `SessionScreen.kt:3173-3181` — 排布的 `Layout` 把 host 的高度**整笔**从 pane 里扣给时间线：

```kotlin
val host = measurables[1].measure(constraints.copy(minHeight = 0))
val viewportConstraints = constraints.copy(
    maxHeight = SessionPaneLayout.viewportHeightPx(
        shared = shared,
        pane = constraints.maxHeight,
        composer = host.height,      // ← host.height **含队列条**
    ),
)
```

`viewportHeightPx` = `if (!shared) pane else (pane - composer)`（`SessionPaneLayout.kt:991-992`）。
**队列条没有任何独立预算行，它的高度被一比一从时间线的 viewport 里扣走。**

### 1.3 为什么是「拿走 124px」而不是「挤压自己」或「让时间线滚动」

- **不挤压自己**：`heightIn(max = 148.dp)` 是**常量**，不是 pane 派生的预算。短窗对队列条
  没有任何约束力，队列条要多少拿多少。
- **队列条优先于卡片**：`Column` 的非 weighted 子节点按顺序测量，**队列条是第一个**，被拿剩余空间
  的**第一顺位**；卡片随后才被测量，于是被挤的是**带 Send 按钮的卡片**，不是队列条。
- **时间线的地板看不见队列条**：`SessionScreen.kt:2560-2567` 时间线 pane 的地板是
  `.weight(1f).heightIn(min = viewportMinHeight)`，而
  `SessionScreen.kt:2394-2396` → `SessionPaneLayout.timelineMinHeight(bodyHeightDp)`
  （`SessionPaneLayout.kt:464-468`）：

```kotlin
fun timelineMinHeight(available: Dp): Dp {
    if (available <= 0.dp) return 0.dp
    val viewport = available - chromeMaxHeight(available)
    return minOf(TIMELINE_MIN_HEIGHT, (viewport - COMPOSER_MIN_HEIGHT).coerceAtLeast(0.dp))
}
```

  它扣的是 **`COMPOSER_MIN_HEIGHT`（116 dp = 编辑器 60 + 工具行 44 + 区域 padding 12）** ——
  一个**只描述卡片、根本不知道队列条存在**的常数。于是 `viewport − 116` 被压到 16 → 地板 ≈ 0，
  而队列条那 82–94 px 从未被这个公式考虑过。
- 同一个文件 `SessionPaneLayout.kt:99-103` 自己写明了这条规律：
  「*Applied **through** the chrome ceiling rather than as a `heightIn(min = …)`: a floor alone
  cannot be honoured by a column that is already over budget*」—— 地板必须**从预算里扣出去**，
  不能只是给已超预算的 Column 挂一个 `min`。

**124px 的算法**：`②→③` 落差 172 px = composer 折行 48 px + **队列条 124 px**。
队列条那一笔来自 `viewportHeightPx(shared, pane, host.height)` 里的 `host.height`：
它把 `②`（cover 形，viewport = 整 pane 188）与 `③`（shared 形，viewport = `pane − host`）
之间的差**一次性**算给了时间线。队列条把 `host.height` 顶大 → 顶过 `composerSharesViewport`
（`SessionScreen.kt:2048`）的阈值 → 形制翻成 shared → **整摞**从 pane 里扣。时间线在
`③` 的 16 px 落进队列条的不透明矩形（`ComposerQueuePanel.kt:98` `background(c.layer01)`）内，
所以是 **100% 覆盖**。

### 1.4 逐条排除（派单要求的五个假设）

| 假设 | 判定 | 依据 |
|---|---|---|
| `Modifier.weight(1f)` 缺失 | ❌ **排除** | `SessionScreen.kt:2562` 有 `.weight(1f)`。且 `weight` 只分配**剩余**空间，剩余为 0 时无法凭空造空间 |
| 队列条用 `fillMaxHeight` / 过大固定高度 | ⚠️ **部分命中**：`fillMaxHeight` **没有**，但**天花板是常量 148.dp**、无 pane 预算 —— 这就是缺陷本身 | `ComposerQueuePanel.kt:89-92`（改前） |
| `imePadding` 内外层重复叠加 | ❌ **排除** | 全仓会话路径**没有** `imePadding` 修饰符；只有一处 `WindowInsets.ime` 读（`SessionScreen.kt:2319-2326`）→ 一处 `padding(bottom = paneBottomDp)`（`:2397`） |
| `bringIntoView` 把 composer 顶上去而没给时间线留底 | ❌ **排除** | ③ 的 composer `[28,261][1338,309]` 距键盘顶 342 净空 33 px，**没有任何抬升**；挤压是纯静态预算算术 |
| `WindowInsets` 双计 | ❌ **排除** | 单读单用，见上 |
| **时间线 `weight(1f)`/`heightIn` 是否被 `min` 约束挡住（该有 `minHeight` 却没有）** | ✅ **命中（且是二次放大器）** | 地板**有**（`SessionScreen.kt:2567`），但它由 `timelineMinHeight` 派生，而后者扣的是**不含队列条**的 `COMPOSER_MIN_HEIGHT`；且按文件自己的规矩，`heightIn(min)` 在已超预算的 Column 里**本就无法被兑现** ⇒ 真正的修法必须在**预算里**扣，而不是补一个 `min` |

---

## 2. 修法（最小、可解释、不改交互语义）

**一句话**：把队列条的天花板从「上游常量 148 dp」改成「**pane 预算派生的余量**」，
并把优先级倒过来 —— **时间线的带 + composer 的工具行先付，队列条拿剩下的，且拿超了就滚动**。

### 2.1 `SessionPaneLayout.kt`（+ 纯函数，无副作用）

| 符号 | 作用 |
|---|---|
| `QUEUE_BAR_MAX_HEIGHT = 148.dp` | 上游 `max-h-[148px]`，从面板搬到这里，**两边读同一个数**，防止再漂移 |
| `QUEUE_ROW_HEIGHT = 32.dp` / `QUEUE_BAR_CHROME = 18.dp` | `h-8` 行 + 面板 6 dp padding + `-mb-3` 的 12 dp overlap |
| `queueBarIntrinsicHeight(rows)` | `32n + 18`（0 行 = 0 dp，故出现/消失时机语义不变） |
| **`queueBarCeiling(pane, timelineFloor, composerFloor)`** | **`clamp(pane − 64 − card, 0, 148)`** ← 核心 |
| `queueBarHeight(rows, pane)` | `min(intrinsic, ceiling)` —— 超出即**收缩** |
| `composerFloorFor(lines)` | 1 行 = `COMPOSER_MINIMAL_HEIGHT`(56)，≥2 行 = `COMPOSER_MIN_HEIGHT`(116) |
| `timelineShare(total, ime, composerLines, queueRows)` | **派单要的真值表**：四个设备读数进，时间线份额出 |
| `timelineFloorHolds(...)` | 「只要 pane 付得起 卡片+带，就一定 ≥ 阈值」的可断言谓词 |
| `TIMELINE_FLOOR_UNDER_QUEUE = TIMELINE_MIN_CONTENT` = 64 dp | 队列条**永不可吃**的「一行半」 |

### 2.2 `ComposerQueuePanel.kt`

`maxHeight: Dp = SessionPaneLayout.QUEUE_BAR_MAX_HEIGHT` 形参 → `heightIn(max = maxHeight)`。
仍是 `LazyColumn` ⇒ **超天花板时滚动**，`Steer` / **×** 全部仍可点。
`PANEL_MAX_HEIGHT` 私有常量删除（不留双份数字）。

### 2.3 `Composer.kt`

新增 `queueBarMaxHeight: Dp = SessionPaneLayout.QUEUE_BAR_MAX_HEIGHT`，转发给面板
（`maxHeight = queueBarMaxHeight`）。**默认值保持 148 dp** ⇒ 其它调用方与既有 pin 全部不受影响。

### 2.4 `SessionScreen.kt`

`Composer(` 调用处传入 pane 派生的天花板：

```kotlin
queueBarMaxHeight = SessionPaneLayout.queueBarCeiling(
    paneHeight,
    composerFloor = if (minimalComposer) {
        SessionPaneLayout.COMPOSER_MINIMAL_HEIGHT
    } else {
        SessionPaneLayout.COMPOSER_MIN_HEIGHT
    },
),
```

- 读 **`paneHeight`（pane）而不是 `composerHeight`（host）** —— 与同处 `sharedComposer` 同一理由，
  否则队列条会与自己触发的决策争论（v57 那类振荡）。
- `composerFloor` 传**即将真正布局的卡片形状**：非一行形时取**保守的 116 dp**。
  首版我曾用 56 dp，被自己的真值表当场打回：那 60 dp 差值**正好就是消息会丢掉的 dp**（见 §5）。

### 2.5 diff 摘要

```
SessionPaneLayout.kt        +~150  （新 v99 段：常量 + 4 个纯函数 + 1 个谓词）
SessionScreen.kt           +~20   （Composer( 调用处新增实参 + 注释）
composer/Composer.kt       +~25   （新增带默认值的形参 + 转发）
composer/ComposerQueuePanel.kt  ~+30/−12  （可注入天花板；删重复常量）
app/src/test/.../QueueTimelineSpaceV99Test.kt  +~300（新类，10 例）
```

**不动的**：队列条出现的时机（`queueSupported && queuedRows.isNotEmpty()` 原样）、
行内 queued 标签、`Steer`/`×` 的可见性与角色、`composerSharesViewport` 的形制判据、
`viewportHeightPx` 的算式、`chromeMaxHeight` 阶梯、任何字符串资源。

---

## 3. 为什么不降强度

- 新 pin 全部是**新增**断言，**没有改动或放宽任何既有断言**；`QueueRowActionsPinTest` /
  `QueuedLabelTest` / `Wave24b*` / `SessionPane*` / `Composer*` 共 **513 例原样通过**。
- 纯真值表是**比截图更强**的判据：它把「③ 恰好 16 px」这一个点推广成
  `everyPaneThatCanPayForTheBandKeepsIt` 的 **56×16×3×13 = 34 944 组**扫描
  （总高 300–1400 dp、键盘 0–600 dp、composer 1–3 行、队列 0–12 行）。
- `theFailingFrameLeavesATimelineBand` 明确断言 `share > 16.dp` —— **16 px 这个读数本身不可再表达**。
- 结构 pin 反向锁死漂移：时间线**必须**有 `weight(1f)` + `heightIn(min = viewportMinHeight)`；
  面板**必须**从形参取天花板、**不得**再出现 `heightIn(max = 148.dp)`；
  `queueBarCeiling` **必须**先扣时间线地板再扣 composer 地板。

---

## 4. 修后各状态的高度分配预演

用本壳自己的算式（`dp` = px @160dpi）。`pane = 内容区 − 键盘高`，
`card = composerFloorFor(行数)`，`bar = min(32n+18, clamp(pane−64−card, 0, 148))`，`时间线 = pane − card − bar`。

| 状态 | 窗口 | 总高 | 键盘高 | pane | composer 行 | 队列行 | card | 队列条天花板 | 队列条实高 | **时间线** | 判定 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| ① 流式，无队列条 | 1366x768 | 720 | 378 | 342 | 1 | 0 | 56 | 148 | 0 | **286** | ✅ ≥ 64 |
| ② 流式 + 2 队列，composer 单行 | 1366x768 | 720 | 378 | 342 | 1 | 2 | 56 | 148 | 82 | **204** | ✅ ≥ 64（② 修前 188） |
| ③ 流式 + 2 队列，composer 折 2 行 | 1366x768 | 720 | 378 | 342 | 2 | 2 | 116 | 148 | 82 | **144** | ✅ ≥ 64（**修前 16**） |
| ④ 极端窗口 2 行 | 1366x600 | 552 | 291 | 261 | 2 | 2 | 116 | **81** | **81**（收缩 1） | **64** | ✅ 恰好等于地板；队列条自己让了 1 dp |
| ⑤ 极端窗口单行 | 1366x600 | 552 | 291 | 261 | 1 | 2 | 56 | 141 | 82 | **123** | ✅ |

**关键读数**：

- **③ 从 16 px 回到 144 px**，且 82 px 的队列条**不再从 band 里取任何 dp**（① 与 ③ 之间的
  142 px 差额 = composer 折行 60 + 队列条 82，**全部由 pane 余量承担**）。
- **④ 是本修法最好的一行**：pane 真的不够了，于是**队列条自己收缩**（82 → 81），
  时间线保住**恰好 64 dp** 的地板。这正是派单要的「空间不足时让队列条自身滚动/收缩，
  而不是把时间线压到 0 附近」—— 而修前的 ④ 是 99 px 且 ②/③ 越界。
- **composer 净空不受影响**：本修法只压队列条，`composerCeiling`（`SessionScreen.kt:2060`）、
  `paneBottomDp`、键盘 inset 路径**一行未改** ⇒ ③ 的 33 px 净空按原样保持（待真机复验）。
- **队列条可点性**：超天花板时 `LazyColumn` 滚动，32 dp 行与 `Steer`/`×` 的点击区不变。

---

## 5. 过程中被自己的 pin 打回的一版（诚实记录）

首版 `queueBarCeiling` 的 `composerFloor` 默认 `COMPOSER_MINIMAL_HEIGHT`(56)。
但 `timelineShare` 在 composer 折行时扣的是 `COMPOSER_MIN_HEIGHT`(116) ——
**预算按便宜的卡算、份额按贵的卡扣**，中间 60 dp 差值会让 `③` 类 pane 的时间线掉到 4 dp。
写 `everyPaneThatCanPayForTheBandKeepsIt` 时正是这个 sweep 暴露了它。修法是引入
`composerFloorFor(lines)` 并让**预算与份额扣同一张卡**，且 `SessionScreen` 显式传入
（非一行形时保守取 116）。**没有为了让它通过而放宽断言。**

---

## 6. 测试证据

命令（`.agent-gradle-lock` 抢锁 → 前台跑完 → 释放）：

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew :app:testDebugUnitTest --tests com.xiufeigo.opencodeshell.QueueTimelineSpaceV99Test `
  --tests com.xiufeigo.opencodeshell.SessionPaneShortWindowV51Test ... (共 56 个类) --console=plain
```

原文（末尾）：

```text
> Task :app:compileDebugUnitTestKotlin
> Task :app:compileDebugUnitTestJavaWithJavac NO-SOURCE
> Task :app:processDebugUnitTestJavaRes UP-TO-DATE
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 8s
52 actionable tasks: 2 executed, 50 up-to-date
GRADLE_EXIT=0
```

用例数（`app/build/test-results/testDebugUnitTest` 汇总）：

```text
CLASSES=56 TESTS=513 FAILURES=0 ERRORS=0 SKIPPED=0
QueueTimelineSpaceV99Test: tests=10 failures=0 errors=0
```

新类 10 例：`theTimelinePaneCarriesAMinimumHeightConstraint`、`everyPaneThatCanPayForTheBandKeepsIt`、
`theBandsHeightIsIndependentOfHowManyPromptsAreQueued`、`noQueuedPromptsMeansNoBarHeight`、
`theBarCeilingIsBoundedAtBothEnds`、`theBarYieldsBeforeTheBandAndBeforeTheComposer`、
`theFailingFrameLeavesATimelineBand`、`theBarTakesItsCeilingFromThePaneNotFromAConstant`、
`theBarCeilingIsDerivedFromThePaneNotFromTheHost`、`theBandAndTheComposerArePaidBeforeTheBar`。

回归类覆盖 `SessionPane*`（V51/54/55/57/76/77/78/79/81/84/86）、`Composer*`（33 个）、
`Queue*`、`QueuedLabelTest`、`Wave24b*`、`Wave23a*`、`StringsLocaleParityTest`。

---

## 7. 真机复验步骤（1366x768，emulator-5562）

前置：`wm size` / `wm density` 无 Override；自建会话；每步 dump 前后都断言 `mInputShown=true`。

1. **构造 ③ 态**：会话发长回合 A → 流式中发第二条 → 队列条出现 → 点 composer 唤 IME →
   `uiautomator dump --compressed`。
   - **断言 A（时间线带）**：scrollable 时间线容器高度 **≥ 64 dp**（@160 即 ≥ 64 px）。
     修前读数 16 px（`[0,162][1366,178]`）。
   - **断言 B（不被覆盖）**：时间线可视带与队列条矩形 `[y_top, y_bot]` **交集为空**
     —— 修前交集 = 整条可视带（100% 覆盖）。
2. **composer 净空**：`composer_bottom ≤ keyboard_top(342)`，净空 **≥ 33 px**（不得小于修前值）。
3. **队列条可点**：`dump` 里 `Steer` 与 `×` 均在树中且 `clickable=true`；`tap` `×` 后队列条少一行；
   `tap` `Steer` 后该提示词被steer。**顺带验「空间不足时收缩」**：把队列堆到 ≥ 4 条，
   面板应**出现滚动/截断**而时间线仍 ≥ 64 dp。
4. **对照 ①**：无队列条时时间线带应 ≈ §4 表 ① 的 286 dp 量级（> 修前 188 dp 即为改善，
   因为 ② 的队列条不再吃它的 dp）。
5. **极端窗口**：`wm size 1366x600` + `wm density 160` → 重复 1–3；时间线应 **≥ 64 dp**
   （修前 99 dp 但 ②/③ 越界），队列条收缩而非消失。**收尾必须 `wm size reset` + `wm density reset` 并复核。**

---

## 8. 未闭环

| # | 项 | 状态 | 原因 / 下一步 |
|---|---|---|---|
| 1 | **真机复验全部 5 步** | **未做** | 本轮纪律只允许 JVM 单测（`testDebugUnitTest`），禁装机/跑设备。§7 步骤待 U1 复验轮执行 |
| 2 | ③ 的 `host.height` 精确构成 | **推断，非实测** | 报告未给 `composerHeightPx`/`paneHeightPx` 运行期值；本壳无 debug 读数（该缺口 REPORT_SWEEPD3 §6 已记）。§4 表用本壳算式外推，非 px 读数 |
| 3 | ② 的 viewport 188 dp 是否为 cover 形 | **推断** | 依据 `viewportHeightPx` 的 `shared` 分支与 `composerSharesViewport` 阈值；`d3_11_queue` 未记录 `shared` 取值。§1.3 的 124 px 拆分沿用报告自身的推算口径 |
| 4 | 覆盖态几何 | **部分** | §1.3 解释了「整摞扣除 → 落在不透明矩形内」这一必然性；③ 态「不可读」的完整截图仍未补（沿用 REPORT_SWEEPD3 §6 的未闭环项） |
| 5 | 极端窗口 ④ 收缩 1 dp | **预测** | `pane=261` 时天花板 81 < 内在 82，收缩量仅 1 dp，视觉上几乎不可见；真机若队列行数更多（如 5 行）收缩量会显著（内在 178 → 天花板 81），建议复验时用 ≥5 条队列观察 |
| 6 | 队列条大量时的可用性 | **未验** | 4+ 行时面板滚动是上游既有行为（`max-h-[148px]` scroll），本修只是把「滚动阈值」从常量改成 pane 预算；是否够用属产品判断 |
