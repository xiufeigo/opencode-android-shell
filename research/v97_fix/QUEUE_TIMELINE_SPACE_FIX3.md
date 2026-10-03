# v100 修复 — U1-c：时间线的最小高度是**余数**不是**下限**，composer 一变高就整块被扣

- 修复工程师：mavis（U1-c 第三轮）
- 日期：2026-10-03
- 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
- 触发依据：`research/v97_verify/5556_v98/REPORT_U1B.md`（**先整篇读**）
  - ❌ 核心待修：判据 ① 前半「时间线 ≥ 阈值」**FAIL** —— 同一扇窗（`1366x768`@160 + IME + 队列条真实存在）里
    时间线在 **16px** 与 **140px** 两档并存，`16px` **连读 3 帧逐字稳定**，逐字等于原缺陷
    `REPORT_SWEEPD3` §2.2 ③ 的 `[0,162][1366,178]`；**唯一相变量是 composer 高度 h=34（⇔16px）/ h=48（⇔140px）**。
  - ✅ 必须保留的既有 PASS：队列条真实存在的 **8 个有效帧**里 `Steer`/`×` **全部在树中**（h=43–48、`clickable=true`），
    **即使时间线塌到 16px 也仍可点**；「不被覆盖」PASS。
  - ℹ️ 已采纳的读数更正：上一轮 `REPORT_U1` 的「行高 2–3px、`Steer`/`×` 全树 0」测的是 `MessageNavRail`，
    且那轮**队列条根本没出现**。**因此本轮不以「队列条被压扁」为前提**，只动时间线的最小高度。
- 写权限：仅 `ui/session/SessionScreen.kt`、`ui/session/SessionPaneLayout.kt`、新测试
  `app/src/test/java/com/xiufeigo/opencodeshell/QueueTimelineFloorV100Test.kt`。
  未碰 `_upstream/`、`app/build.gradle.kts`、`ChatViewModel.kt`、`ui/servers|home|terminal|nav/**`、
  `ui/App.kt`、`OcActivity.kt`、`ui/session/timeline/**`、`ui/session/composer/**`。
- 未新增任何用户可见字符串；**未改动、未放宽任何既有断言**（含前两轮我自己的 7+10 例）；
  未跑 assembleDebug / clean / 装机；gradle 前台跑完，锁已释放。

> **状态：DONE（JVM 已闭环 · 真机待复验）** —— 根因定位到 `文件:行号`，**那 34px 的算式**在 §1.3；
> 修法 = **把 band 变成宿主自身约束里的一项**（`composerHostCeilingPx`）+ **给减法加硬地板**
> （`viewportHeightPx(…, floorPx)`）；新增 **6 例**纯 JVM pin（**二维 96 格真值表**，不走对角线），
> **84 个类 560 例 0 失败**，`QueueTimelineSpaceV99Test` 17 例逐字未动全绿。

---

## 1. 根因

### 1.1 一句话

> **时间线的高度在这套布局里从来不是「最小值」，而是「余数」**：`viewportHeightPx` 的下限写的是 **0**，
> 而那个唯一的减数（composer 宿主）被允许按 `minHeight = 0` + **整个 pane 的 `maxHeight`** 去测量。
> 于是 composer 每长高一档，差额**整块**落在时间线上；下限 0 意味着这笔账**没有底**。

### 1.2 决定性的三处代码（修前原文）

**(a) 减法的下限是 0** —— `SessionPaneLayout.kt:991-992`（修前）：

```kotlin
fun viewportHeightPx(shared: Boolean, pane: Int, composer: Int): Int =
    if (!shared) pane else (pane - composer).coerceAtLeast(0)
```

`.coerceAtLeast(0)` —— **`TIMELINE_FLOOR_UNDER_QUEUE`（64dp）根本不在这个式子里。**
时间线拿到的是 `pane − host.height`，**下限 0**。这直接解释了 `REPORT_U1B` §4.1：
`132 − 116 = 16`，**逐字命中 F1–F3 的 h=16**；而 F4 的 h=140 是 `!shared` 分支的 `= pane`。

**(b) 唯一的减数没有上限** —— `SessionScreen.kt:3203`（修前）：

```kotlin
val host = measurables[1].measure(constraints.copy(minHeight = 0))
```

`minHeight = 0`、**`maxHeight` 原封不动 = `constraints.maxHeight`（整个 pane）**。
原注释还把这当成设计：

> `// The host is measured with a **zero** minimum height: … the pane's own minHeight (the mobile viewport floor) is a fact about the pane, not about the composer`

**这句话就是缺陷本身**：band 是 pane 的事实，所以 composer 就不受它约束 —— 于是 composer 可以把 pane 全部吃掉。

**(c) 桌面端根本没有地板** —— `SessionScreen.kt:2443`（修前同一行）：

```kotlin
timelinePane(Modifier.weight(1f).fillMaxHeight(), headerSlot)
```

`viewportMinHeight`（`SessionScreen.kt:2424-2426`）**只被移动端分支用在 `SessionScreen.kt:2597`**。
真机是 `Medium_Desktop` ⇒ 走桌面分支 ⇒ **时间线上一次都没有出现过 `heightIn(min = …)`**。
这就是「最小高度写在**错误的作用域**」的直接证据：地板写在**移动端分支里那个被压缩的节点上**。

### 1.3 那 34px 的预算算式（**必答**）

用 `REPORT_U1B` §2 自己给的坐标系（`导航栏 y=672..768`、`IME 顶 y=294`、`时间线容器上沿 y=162`）
与 §4.1 的 F1–F4 读数：

```
pane      = IME顶(294) − 时间线上沿(162) = 132 px        ← F1–F4 两档都读到时间线 top = 162
```

**F1–F3（timeline = 16，相变量 composer h=34）**：

```
host.height = pane − timeline = 132 − 16 = 116 px
             = 队列条 50（32 行 + 18 chrome）
             + composer 卡片 66（EditText 34 + 工具行 + 12 区域 padding）
timeline    = viewportHeightPx(shared=true, pane=132, composer=116)
            = (132 − 116).coerceAtLeast(0) = 16          ← 下限 0，band 64 在式子里不存在
```

**F4（timeline = 140，相变量 composer h=48）**：

```
timeline    = viewportHeightPx(shared=false, pane=132, composer=…)
            = pane = 132 …  实测 140（同量级：F4 那一帧整个底栈上移了 26px，
                            队列行 y=154..202 压到时间线 top 之上）
```

⇒ **两档不是「两次不同的扣款」，而是同一个减法落在 0 的哪一侧。**

**那 34px 到底从哪一项预算里扣？——分两步，答案不是「34」：**

```
第 1 步（触发）：composer 的自动增高编辑框 34 → 48，编辑器 Δ=14、卡片含工具行 Δ=34
        ⇒ composerHeight 变高 34px
        ⇒ composerSharesViewport(paneHeight, composerHeight)  (SessionPaneLayout.kt:500-504)
             viewport − composerHeight < TIMELINE_MIN_CONTENT(64)  这一行被跨过
        ⇒ 排列在 shared ⇄ floating 之间翻转（"pane − composer 差多少"是唯一的判据）

第 2 步（扣款）：翻转落到 shared=true 后，宿主是**未设上限**的减数
        host = 116（队列条 50 + 卡片 66）
        timeline = (132 − 116).coerceAtLeast(0) = 16
        ⇒ 被扣掉的是 **整笔 host.height = 116px**，不是 34px。
        34px 只是把判据推过悬崖的那**一脚**，不是账单。
```

**为什么这一档没扣到队列条/自身、而扣到了时间线？** 因为优先级是**结构性**的，不是数值上的：

| 谁 | 位置 | 拿多少 | 为什么 |
|---|---|---|---|
| 队列条 | `ComposerQueuePanel`，**在 composer 宿主的 Column 里，是第一个子节点** | `min(32n+18, queueBarCeiling(pane, …))`，**有 148dp 天花板** | 它在**宿主内部**被预算，天花板先于时间线生效 |
| composer 卡片 | 同一 Column 的第二个子节点 | **无上限**（`:3203` `minHeight=0` + 整个 pane） | 宿主拿多少它就画多少 |
| **时间线** | `SessionPaneLayout.kt:991-992` 的减法 | **`coerceAtLeast(0)`** | **它不是参与者，是余数** |

⇒ 队列条**从来不是**一个独立的预算行；它只能通过「撑大宿主」间接影响时间线。
**只要地板不在减法里，链条上任何一个环节变高，账单 100% 落到时间线头上。**
这就是「为什么有时扣到队列条、有时扣到自身」—— 实际上**永远扣到时间线**，
`REPORT_SWEEPD3` §2.4 读到的「124px 来自队列条」是**间接**的：队列条把宿主撑大，宿主把时间线压小。

### 1.4 逐条排除（派单要求的六条）

| # | 假设 | 判定 | 依据 |
|---|---|---|---|
| 1 | 最小高度写在**错误的作用域**（被压缩的子节点而非分配高度的容器） | ✅ **正是根因** | `SessionScreen.kt:2443` 桌面分支**根本没有** `heightIn(min=…)`；`viewportMinHeight` 只喂 `:2597` 的移动端分支。地板写在**被压的节点**上，从没写到**分配高度的那个减法**上 |
| 2 | `weight(1f)` 与 `heightIn(min=…)` 语义冲突（weight 覆盖 min） | ✅ **成立（且是放大器）** | `heightIn` 的实现是 `newMin = min.roundToPx().coerceIn(constraints.minHeight, constraints.maxHeight)` —— **min 被收敛到传入的 max**。`Column` 给 weighted 子节点的约束是 `minHeight=0, maxHeight=余量`，所以余量 16 时 `heightIn(min=64)` 静默变成 `min=max=16`。**一个写在 weighted 节点上的 min 不是下限**。桌面端虽无 `heightIn`，但 `fillMaxHeight()` + 权重余量是同一回事：`timeline = 余量` |
| 3 | `imePadding` / `navigationBarsPadding` 叠加 | ❌ **排除** | IME 走 `believedImeBottomPx` → `Box(padding(bottom = paneBottomDp))`（`:2349-2357, 2427`），是**一次性**内边距；`REPORT_SWEEPD2` §2 已定案「平台没有 resize，是本壳消费 insets」，且两档的 IME 顶**都是 294**（读数自证） |
| 4 | `MessageNavRail` 与时间线在同一个 `Box` 里抢位 | ❌ **排除（宽度证伪）** | `MessageNavRail.kt:64` 是 **48px 窄轨**（`x=1316..1364`），`REPORT_U1B` §3.1 已用**宽度差 27 倍** + 文本语义把它和时间线容器分开；且本轮症状是**时间线容器本身** 162..178，不是 162..302 的全带。窄轨贴在右缘，不参与纵向分配 |
| 5 | composer 用固定高度把总账撑破 | ❌ **排除（方向相反）** | composer **不是**固定高：`EditText` 实测 34 与 48 两档，是**内容驱动**的自增高。恰恰因为它不定高、不封顶，才会一路长到「把 band 吃完」。固定高度至少是可预算的 |
| 6 | `Arrangement` / `Spacer` 暗中占高 | ❌ **排除** | `SessionPaneArrangement` 恰好两个 measurables（viewport / host，`SessionScreen.kt:3170-3191`），无 Spacer；`host.place(0, composerTopPx(pane, host.height))` 只放坐标不占预算。占高的**只有 host 一个** |

### 1.5 顺带确认（不是本轮根因，但记录）

`SessionScreen.kt:2090-2098` 的 `composerCeiling` 用 `.coerceAtLeast(COMPOSER_MINIMAL_HEIGHT / COMPOSER_COMPACT_HEIGHT)`
—— 这是个**天花板**却带 `coerceAtLeast`，理论上能把它抬回 band 之上的数值。
但该 ceiling 只在 `shared` 分支经 `SessionScreen.kt:3188` 的 `heightIn(max = …)` 施加，
而 v100 之后宿主还有一道更硬的 `pane − floor` 上限（`SessionScreen.kt:3221`），**它已不再是可观测的缺陷路径**。
⚠️ **诚实标注**：我**没有**为它单独加 pin，也**没有**改它 —— 改它会动 `SessionPaneArrangementV76Test` 的既有口径，
超出本轮「只修时间线地板」的授权范围。列入 §7 未闭环。

---

## 2. 修法

**把 band 从「减法之后的事」搬成「宿主自己的约束」，并给减法加硬地板。**

| 符号 | 位置 | 作用 |
|---|---|---|
| `viewportHeightPx(shared, pane, composer, floorPx)` | `SessionPaneLayout.kt:1017-1026` | 4 参重载：`(pane − composer).coerceAtLeast(floorPx).coerceAtMost(pane)`。**3 参形态逐字保留**（`floorPx` 默认 0 ≡ 原 `.coerceAtLeast(0)`） |
| `composerHostCeilingPx(pane, floorPx)` | `SessionPaneLayout.kt:1043-1044` | `pane − floor`：**宿主**能拿的上限。band 变成减数自身的约束项 |
| `timelineViewportFloor(pane)` | `SessionPaneLayout.kt:1051-1052` | `min(64, pane)`：地板按 pane 实际能给的封顶，pane 比 64 还小时不凭空要空间 |
| measure policy | `SessionScreen.kt:3217-3238` | 宿主 `maxHeight = composerHostCeilingPx(...)`；视口 `minHeight = floorPx` 且 `maxHeight = viewportHeightPx(…, floorPx)` |
| 形参 | `SessionScreen.kt:3166`、调用点 `:2108` | `timelineFloor: Dp` 由 `timelineViewportFloor(paneHeight)` 传入 |

### 2.1 冲突时的优先级（**显式**，与派单要求逐条对齐）

1. **时间线地板 `min(64, pane)` 永不可扣** —— 现在它是宿主 `maxHeight` 里减掉的一项，**在测量之前**；
2. **队列条内部滚动优先** —— 队列条是宿主的第一个子节点，先被 `maxHeight` 压到
   `queueBarCeiling`，再由 `LazyColumn` 的 `heightIn(min = queueBarViewportFloor(rows))`
   保住**一整行**、其余滚动（`ComposerQueuePanel`，v99-b 已就位，**本轮未动**）；
3. **其次限制队列条整体高度** —— `QUEUE_BAR_MAX_HEIGHT = 148dp` + `queueBarCeiling` 的 50dp 地板；
4. **最后才允许压缩 composer 卡片** —— 宿主被 `pane − floor` 封顶后，压缩落在卡片上
   （`Composer` 自身的 `composerCeiling` 阶梯仍在）；
5. **永远不从时间线的最小高度里扣** —— 第 1 项是**约束**（`maxHeight` 里减掉 + `minHeight` 兜底），
   不是「希望」，因为 `weight` 与 `heightIn(min)` 都会把 min 收敛到余量（§1.4 #2）。

### 2.2 diff

```diff
--- a/.../ui/session/SessionPaneLayout.kt
+++ b/.../ui/session/SessionPaneLayout.kt
@@ v76 段 @@
     fun viewportHeightPx(shared: Boolean, pane: Int, composer: Int): Int =
         if (!shared) pane else (pane - composer).coerceAtLeast(0)

+    /**
+     * v100 P0: the same subtraction, with the timeline's floor made a **hard**
+     * number instead of a hope.
+     *
+     * [viewportHeightPx] floors the remainder at **0**, so the viewport is a
+     * *remainder*, not a *minimum* …  (`REPORT_U1B.md` §4.1)
+     */
+    fun viewportHeightPx(
+        shared: Boolean,
+        pane: Int,
+        composer: Int,
+        floorPx: Int,
+    ): Int = if (!shared) {
+        pane
+    } else {
+        (pane - composer).coerceAtLeast(floorPx).coerceAtMost(pane)
+    }
+
+    /** v100 P0: the tallest the composer host may be while the timeline keeps
+     *  [TIMELINE_FLOOR_UNDER_QUEUE] — the ceiling the host is measured against
+     *  **before** the viewport is given what is left. */
+    fun composerHostCeilingPx(pane: Int, floorPx: Int): Int =
+        (pane - floorPx).coerceAtLeast(0)
+
+    /** v100 P0: the band's own floor as the pane can afford it. */
+    fun timelineViewportFloor(pane: Dp): Dp =
+        TIMELINE_FLOOR_UNDER_QUEUE.coerceAtMost(pane.coerceAtLeast(0.dp))

--- a/.../ui/session/SessionScreen.kt
+++ b/.../ui/session/SessionScreen.kt
@@ 调用点 (~2104) @@
                 SessionPaneArrangement(
                     shared = sharedComposer,
                     composerCeiling = composerCeiling,
                     onViewportSize = { viewportHeightPx = it.height },
+                    timelineFloor = SessionPaneLayout.timelineViewportFloor(paneHeight),
                     composer = { composerSlot(composerRegion) },
                 ) {

@@ 形参 (~3161) @@
 private fun SessionPaneArrangement(
     shared: Boolean,
     composerCeiling: Dp,
     onViewportSize: (IntSize) -> Unit,
+    timelineFloor: Dp,
     composer: @Composable () -> Unit,
     viewport: @Composable BoxScope.() -> Unit,
 ) {
+    val arrangementDensity = LocalDensity.current
     Layout(

@@ measure policy (~3203，修前) @@
-        // The host is measured with a **zero** minimum height: it wraps the
-        // composer, and the pane's own `minHeight` (the mobile viewport floor) is
-        // a fact about the pane, not about the composer …
-        val host = measurables[1].measure(constraints.copy(minHeight = 0))
-        val viewportConstraints = constraints.copy(
-            maxHeight = SessionPaneLayout.viewportHeightPx(
-                shared = shared,
-                pane = constraints.maxHeight,
-                composer = host.height,
-            ),
-        )
+@@ measure policy (~3217，修后) @@
+        val floorPx = with(arrangementDensity) { timelineFloor.roundToPx() }.coerceAtLeast(0)
+        val host = measurables[1].measure(
+            constraints.copy(
+                minHeight = 0,
+                maxHeight = SessionPaneLayout.composerHostCeilingPx(
+                    pane = constraints.maxHeight,
+                    floorPx = floorPx,
+                ),
+            ),
+        )
+        val viewportConstraints = constraints.copy(
+            minHeight = floorPx,
+            maxHeight = SessionPaneLayout.viewportHeightPx(
+                shared = shared,
+                pane = constraints.maxHeight,
+                composer = host.height,
+                floorPx = floorPx,
+            ),
+        )
         val viewport = measurables[0].measure(viewportConstraints)
```

### 2.3 不动的

`timelinePane` 的两处**调用点 modifier**（`:2443` 桌面 / `:2748` 移动）**一个字符没改**；
`composerSharesViewport` 的形制判据、`viewportHeightPx` / `composerTopPx` 的**既有 3 参形态**、
`chromeMaxHeight` 阶梯、`QUEUE_BAR_*` / `QUEUE_ROW_HEIGHT` 全部常量、
`ComposerQueuePanel.kt`（v99-b 的行级地板与内部滚动）、`Composer.kt` 的队列条挂载判据、
任何字符串资源。

---

## 3. 修后二维预演表

`dp = px @160dpi`。用**出厂算式**实算（`card = composerFloorFor(lines)`；`ceiling = 0 若 pane−64−card<50，否则 min(pane−64−card,148)`；
`bar = min(32n+18, ceiling)`；`floor = min(64,pane)`；`hostCeiling = pane−floor`；`hostPaid = min(bar+card, hostCeiling)`；
`timeline(末行式) = max(pane−hostPaid, floor)`；`timeline(覆盖式) = pane`）。

### 3.1 `pane = 342`（`1366x768` + IME，`REPORT_U1B` 口径）

| composer 行 | 队列条 | card | 天花板 | 队列条高 | hostPaid | **时间线（末行式）** | **时间线（覆盖式）** | **队列行高** | 整行可见 | **内部滚动** |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 0 | 56 | 148 | 0 | 56 | **286** | 342 | — | — | 否 |
| 1 | 2 | 56 | 148 | 82 | 138 | **204** | 342 | **32** | 2 | 否 |
| 1 | 11 | 56 | 148 | 148 | 204 | **138** | 342 | **32** | 4 | **是** |
| 1 | 23 | 56 | 148 | 148 | 204 | **138** | 342 | **32** | 4 | **是** |
| 2 | 0 | 116 | 148 | 0 | 116 | **226** | 342 | — | — | 否 |
| 2 | 2 | 116 | 148 | 82 | 198 | **144** | 342 | **32** | 2 | 否 |
| 2 | 11 | 116 | 148 | 148 | 264 | **78** | 342 | **32** | 4 | **是** |
| 2 | 23 | 116 | 148 | 148 | 264 | **78** | 342 | **32** | 4 | **是** |
| 3 | 0 | 116 | 148 | 0 | 116 | **226** | 342 | — | — | 否 |
| 3 | 2 | 116 | 148 | 82 | 198 | **144** | 342 | **32** | 2 | 否 |
| 3 | 11 | 116 | 148 | 148 | 264 | **78** | 342 | **32** | 4 | **是** |
| 3 | 23 | 116 | 148 | 148 | 264 | **78** | 342 | **32** | 4 | **是** |

### 3.2 `pane = 261`（极端窗口 `1366x600` + IME）

| composer 行 | 队列条 | card | 天花板 | 队列条高 | hostPaid | **时间线（末行式）** | **时间线（覆盖式）** | **队列行高** | 整行可见 | **内部滚动** |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 0 | 56 | 141 | 0 | 56 | **205** | 261 | — | — | 否 |
| 1 | 2 | 56 | 141 | 82 | 138 | **123** | 261 | **32** | 2 | 否 |
| 1 | 11 | 56 | 141 | 141 | 197 | **64** | 261 | **32** | 3 | **是** |
| 1 | 23 | 56 | 141 | 141 | 197 | **64** | 261 | **32** | 3 | **是** |
| 2 | 0 | 116 | 81 | 0 | 116 | **145** | 261 | — | — | 否 |
| 2 | 2 | 116 | 81 | 81 | 197 | **64** | 261 | **32** | 1 | **是** |
| 2 | 11 | 116 | 81 | 81 | 197 | **64** | 261 | **32** | 1 | **是** |
| 2 | 23 | 116 | 81 | 81 | 197 | **64** | 261 | **32** | 1 | **是** |
| 3 | 0 | 116 | 81 | 0 | 116 | **145** | 261 | — | — | 否 |
| 3 | 2 | 116 | 81 | 81 | 197 | **64** | 261 | **32** | 1 | **是** |
| 3 | 11 | 116 | 81 | 81 | 197 | **64** | 261 | **32** | 1 | **是** |
| 3 | 23 | 116 | 81 | 81 | 197 | **64** | 261 | **32** | 1 | **是** |

**读表要点**：

- **时间线一列 24 格全部 ≥ 64**（两种排列各 12 格），**没有一格回到 16px 量级**。`1366x600` 的 4 个
  `64` 是**恰好压在地板上** —— 那是优先级 1 生效的样子（`hostPaid = 197 = 261 − 64`，`ceil(197−116) = 81`），
  不是溢出。
- **队列行高一列恒为 32**，与队列深度（0→23）**无关**：深度只买「滚动」，不买「压扁」。
- **与 `REPORT_U1B` §4.3 对账**：`pane 342 / 1 行 / ≥5 条` 预测 **138** vs 修前实测 **140px**（Δ=2px，模型对得上）。
  **本轮这一格不变**（138）—— 因为 `hostPaid = 204 = 138+66 < pane−64 = 278`，
  **新上限在本窗不咬合**：它只在 `bar + card > pane − 64` 时才起作用。
- **新上限咬合的格**是 `pane 261 / 2~3 行`：修前 `bar + card = 81 + 116 = 197`，`pane − 64 = 197`，
  **恰好相等**；而如果 composer 再高一档（`card` 变 132），修前 `232 > 197` ⇒ 时间线 29px，
  v100 后 `hostPaid` 被钳到 197 ⇒ **时间线 64**。**这正是「composer 一变高就吃掉时间线」的那一类格。**

---

## 4. 为什么不降强度

- **`QueueTimelineSpaceV99Test` 17 例一行未改**（含 `theBarYieldsBeforeTheBandAndBeforeTheComposer`
  的两条精确等值断言 `assertEquals(60.dp, queueBarCeiling(180.dp))` / `queueBarHeight(2, 180.dp) == 60`）——
  本轮**原值通过**。理由是**数值上零接触**：`floorPx` 是**新增形参且默认 0**，
  `viewportHeightPx` 的 3 参形态与修前**逐字相同**，`queueBarCeiling` / `queueBarHeight` /
  `timelineShare` / `TIMELINE_FLOOR_UNDER_QUEUE` **一个字节都没动**。
- **「没放宽」是机器可证的**：新 pin `theOldThreeArgumentCallSeesExactlyWhatItSawBefore`
  穷举 `pane 0..600 × composer 0..600 × shared/floating`，断言
  `viewportHeightPx(s,p,c) == viewportHeightPx(s,p,c,0)`，**任何回归都会红**。
- **新 pin 只增不减**，且**结构 pin 锁死新地板不得回退**（`theMeasurePolicyChargesTheBandBeforeItChargesTheViewport`）：
  measure policy 必须出现 `composerHostCeilingPx(`、`floorPx = floorPx,`、`minHeight = floorPx,`、
  形参 `timelineFloor: Dp,`、调用点 `timelineFloor = SessionPaneLayout.timelineViewportFloor(paneHeight)`，
  且 4 参重载里**不得**再出现 `coerceAtLeast(0)`。
- **不靠删断言过关**：本轮新 pin 第一次跑就红了一次（`budget overflow host=56 timeline=342 pane=342
  (shared=false)`）。查证后是**我的建模错误**——覆盖式里 composer 宿主是按设计压在视口上的不透明盖子，
  `host + timeline ≤ pane` 只对末行式成立。我把该断言**按形态分别定义**（末行式求和 / 覆盖式 `timeline == pane`），
  **没有删它、没有改成恒真**；第二次红是格数守卫 `assertEquals(192, cells)` 写错（实为 96），
  改的是**我自己的计数常量**，不是断言强度。
- **既有 PASS 的保护**：`theQueueBarStillAppearsUnderTheSameRule` 钉住
  `Composer.kt` 的 `queueSupported && queuedRows.isNotEmpty()` —— 队列条出现时机语义不变。
  `ComposerQueuePanel.kt` **本轮 0 字节改动** ⇒ v99-b 的行级地板与内部滚动原样在位。

---

## 5. 测试证据

命令（等 `.agent-gradle-lock` 消失 → 写锁 → **前台跑完** → 释放；**仅** `:app:testDebugUnitTest`，
无 assembleDebug / clean / 装机）：

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew :app:testDebugUnitTest `
  --tests "*Queue*" --tests "*SessionPane*" --tests "*PaneLayout*" `
  --tests "*Composer*" --tests "*Wave23a*" --tests "*StringsLocaleParityTest" --console=plain
# 覆盖 Queue*(含新增 QueueTimelineFloorV100Test) / SessionPane* / PaneLayout* /
#     Composer* / Wave23a* / StringsLocaleParityTest
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
LOCK_RELEASED exists=False
```

用例数（`app/build/test-results/testDebugUnitTest` 汇总）：

```text
CLASSES=84 TESTS=560 FAILURES=0 ERRORS=0 SKIPPED=0
QueueTimelineFloorV100Test: tests=6 failures=0 errors=0
QueueTimelineSpaceV99Test: tests=17 failures=0 errors=0
```

`QueueTimelineFloorV100Test` 6 例（全部新增，既有类 0 改动）：

```text
  - everyComposerLineByQueueDepthCellKeepsTheBandAndTheRow   ← 二维 96 格真值表
  - theFloorIsNeverSubtractedFromTheBandAnywhereInTheSweep   ← pane 0..900 × composer 0..900 × 5 地板
  - theBandSurvivesEvenWhenTheHostAsksForEverything           ← 贪婪宿主（bar+card 不让）
  - theOldThreeArgumentCallSeesExactlyWhatItSawBefore         ← 既有 pin 数值零变化
  - theMeasurePolicyChargesTheBandBeforeItChargesTheViewport  ← 结构 pin
  - theQueueBarStillAppearsUnderTheSameRule                   ← 出现时机语义不变
```

核心断言（摘）：

- `everyComposerLineByQueueDepthCellKeepsTheBandAndTheRow` — **2 窗口 × 2 IME × 3 composer 行 ×
  4 队列深度 × 2 排列 = 96 格**，每格五条：①`timeline ≥ min(64, pane)` ②队列行高 `≥ 32` 且整行可见 `≥ 1`
  ③`timeline > 16`（**不出现原缺陷值**）④末行式 `host + timeline ≤ pane`、覆盖式 `timeline == pane`
  ⑤`host ≤ pane − floor`（**永不从 band 里扣**）。末尾 `assertEquals(96, cells)` 保证**不走对角线** ——
  少跑一格就红。
- `theFloorIsNeverSubtractedFromTheBandAnywhereInTheSweep` — 全整数扫描，
  断言 `viewportHeightPx(…) ≥ min(floor, pane)` 且 `≤ pane`，`composerHostCeilingPx(pane, 64) ≤ pane − 64`。

---

## 6. 真机复验步骤（下一轮照此执行）

**前置**：`wm size 1366x768` + `wm density 160`（与 `REPORT_U1B` §2 逐字一致）；
`mInputShown=true` 每次读数前断言；`Steer`/`×` 定位**精确匹配** `text -eq 'Queue'` 且 `y ∈ [246,298]`
（`REPORT_U1B` §6.1 的坑：右缘跳转轨文本 `Queued prompt number N…` 会被 `*Queue*` 误命中）；
chip 只在 `running && hasContent` 时出现 ⇒ 先让回合跑起来、再让 composer 有草稿；
**每 1–2 条重解析 `Send` 坐标**。

### 6.1 判据 ①（本轮核心）：时间线**任何一帧**都不得落到 16px 量级

| 步 | 动作 | 断言 |
|---|---|---|
| 1 | 新建自建会话 → 发长回合 A（`Write a long detailed explanation of how Android window insets work, covering at least 30 numbered points.`） | 回合进入 RUNNING |
| 2 | 草稿输入 `toggle queue` → 精确点 `Queue` chip → 连发 **13** 条 | `TURN_STATE='Stop'`、队列深度读数记录（`QUEUED=N`） |
| 3 | 点 composer 唤出 IME，**断言 `mInputShown=true`**，记 IME 顶 | 记数 |
| 4 | **连续切换 composer 1 行 ↔ 2 行，每档连读 3 帧**（见 6.2） | 每帧记：时间线 `scrollable` 容器 bounds、`EditText` bounds、`Steer`/`×` bounds、IME 顶 |
| 5 | 判定 | **24 帧（2 档 × 3 帧 × 4 次往返）里时间线高度 `≥ 64`；出现任何一帧 `< 64` 即 FAIL** |

**6.2 怎么稳定造出「1 行 ↔ 2 行」两档（`REPORT_U1B` §8.3 未闭环第 3 项）**

- **1 行档**：草稿**清空**（全选删除，不用 BACK）⇒ `EditText` 落到 `h=34`（`REPORT_U1B` §4.1 的 16px 档）；
- **2 行档**：草稿**加到第二行**（先输 `a` + 空格 + `b`，空格一律 `keyevent 62`，词内 ≤8 字符分段）⇒ `h=48`；
- 每一档**静置 ≥1 帧后连 dump 3 次**（不是抽一帧就下结论 —— `REPORT_U1B` 已证明单帧会误判）；
- **循环 4 次**（1→2→1→2），验证不是「一去一回就回不来」的单向效应。

**同时给 `Steer`/`×` bounds**（判据 ② 保护）：每帧记
`Steer` 的 `[x1,y1][x2,y2] h` 与 `clickable`、**`Remove`(×)** 同上。
**门槛沿用 `REPORT_U1B` §5.1 已达成的标准：两者在树中、bounds 非空、h ≥ 32、`clickable=true`。**

### 6.3 判据 ③ 回归

| 项 | 断言 |
|---|---|
| composer 不被键盘遮 | `EditText` 底 `y` `<` IME 顶 `y`（`REPORT_U1B` §7.4 达 38px 净空） |
| 队列条不被时间线覆盖 | 队列行 y 区间**不包含**时间线带 y 区间（沿用 `REPORT_U1B` §4.2 口径） |
| 队列条内部滚动 | 队列深度 ≥11 时取到 `scrollable=true` 的队列内部视口，读 `h ≥ 32` |
| 极端窗口 | `wm size 1366x600` 复跑 6.1，**时间线 ≥ 64**（`REPORT_U1B` §7.2 曾读到单帧 26px） |

### 6.4 收尾

`wm size reset` + `wm density reset` **并复核**；还原入测基线（`1366x768` / `160`）；
`show_ime_with_hard_keyboard` 全程**只读**；`/sdcard` 产物 pull 后即删；自建会话按标题删除并复核消失。

---

## 7. 未闭环

| # | 项 | 状态 | 原因 / 下一步 |
|---|---|---|---|
| 1 | **真机复验** | ⛔ **未做** | 本轮无 gradle 装机授权（纪律禁止 assembleDebug/装机）。§6 已给出可直接执行的操作序列，**重点是 6.1 步 4：同一窗口连续切换 composer 1↔2 行各 3 帧** |
| 2 | 队列 **≥11 条**这一格 | ⛔ 沿用 `REPORT_U1B` §8.3 第 1 项 | 上一轮因 chip 定位失误未取到。§6.1 步 2 已采纳其修正姿势（精确匹配 + composer 工具行 y 区间），但**本轮未验证该姿势能堆到 11+** |
| 3 | `composerCeiling` 的 `coerceAtLeast` | 🟡 **记录未改** | `SessionScreen.kt:2090-2098` 的天花板带 `coerceAtLeast`，理论上能抬回 band 之上。v100 后被 `pane − floor` 封住，**不再是可观测缺陷路径**；改它会动 `SessionPaneArrangementV76Test` 既有口径，超出本轮授权。**建议单独立项** |
| 4 | 覆盖式下队列条与时间线**视觉重叠** | 🟡 **口径未变，未闭环** | `REPORT_U1B` §4.1 的 F4 帧里队列行 `y=154..202` 压到时间线 `top=162` **之上**（覆盖式下宿主是不透明盖子）。本轮**没有**改变覆盖式的这个几何（`!shared` 分支仍 `= pane`），只保证**盒子** ≥ 地板。`REPORT_U1B` §4.2 用 `154..202 ∌ 162..302` 判 PASS **在集合论上不成立**（前者是后者的子集）。⇒ **「不被覆盖」这条判据在覆盖式下需要重新定义**，建议 Lead 裁定 |
| 5 | `pane < 64` 的极小窗 | 🟡 记录 | `timelineViewportFloor` 封顶到 `min(64, pane)`，此时 band 不可保。JVM pin 覆盖到 `pane=0`，**真机未构造** |
| 6 | `1366x600` composer 净空 −16px | 🟡 沿用 `REPORT_U1B` §8.3 第 5 项 | 上一轮单帧、未连读。本轮**未改动该路径**，仍待复核 |
