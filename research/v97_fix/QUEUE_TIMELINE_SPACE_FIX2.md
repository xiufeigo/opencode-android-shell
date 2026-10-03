# v99-b 修复 — U1 修过头了：队列条收缩到裁行，`Steer`/`×` 从可及性树里消失

- 修复工程师：mavis（U1-b）
- 日期：2026-10-03
- 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
- 上一轮修法：`research/v97_fix/QUEUE_TIMELINE_SPACE_FIX.md`（U1：时间线被压到 16px 且 100% 覆盖）
- 触发依据：`research/v97_verify/5556_v98/REPORT_U1.md`（主缺陷已修，但**过度收缩**：队列 11/23 条时全树
  `Steer`/`×` **0 个**、队列行高塌到 **2–3px**，打破 `REPORT_QUEUE_ACTIONS.md` §3.1 的既有 PASS）
- 写权限：仅 `ui/session/SessionPaneLayout.kt`、`ui/session/composer/ComposerQueuePanel.kt`、
  `app/src/test/.../QueueTimelineSpaceV99Test.kt`。未碰 `_upstream/`、`app/build.gradle.kts`、
  `ChatViewModel.kt`、`ui/servers|home|terminal|nav/**`、`ui/App.kt`、`OcActivity.kt`、
  `ui/session/timeline/**`。
- 未新增任何用户可见字符串；**未改动、未放宽任何既有断言**；未跑 assembleDebug / clean / 装机。

> **状态：DONE（JVM 已闭环 · 真机待复验）** — 根因定位到 `文件:行号`（含一处**读数归属修正**，
> 见 §1.3）；修法 = **双向最小值 + 内部滚动**：时间线保留上一轮的最小可读高度（未退回 16px），
> 队列条新增**行级最小高度 32dp**、**天花板不再低于一整行**、条目多时在面板内部滚动；
> 新增 **7 例**纯 JVM pin（既有 10 例**原样通过**），**45 个类 414 例 0 失败**。

---

## 1. 根因

### 1.1 收缩的下限是什么：**0 dp**

`SessionPaneLayout.kt` 的 v99 段（修前原文）：

```kotlin
fun queueBarCeiling(
    pane: Dp,
    timelineFloor: Dp = TIMELINE_FLOOR_UNDER_QUEUE,
    composerFloor: Dp = COMPOSER_MINIMAL_HEIGHT,
): Dp {
    if (pane <= 0.dp) return 0.dp
    val room = pane - timelineFloor - composerFloor
    return room.coerceIn(0.dp, QUEUE_BAR_MAX_HEIGHT)   // ← 下限 0.dp
}
```

**上一轮给队列条定义的重分配下限是 `0.dp`**。`queueBarHeight(rows, pane) = min(intrinsic, ceiling)`
因此可以是 `(0, 50)` 之间的**任意值**——那正是"半个面板"。

而这个下限有一个**不对称的后果**：pane 一旦小到
`pane < 时间线地板(64) + 卡片(56) + 50`，`room < 50`，面板就**按比一行还矮的高度**被布局。

### 1.2 为什么行高会塌：视口只有 `max`、**没有 `min`**

修前 `ComposerQueuePanel.kt` 的滚动视口：

```kotlin
LazyColumn(
    modifier = Modifier
        .fillMaxWidth()
        .heightIn(max = maxHeight),        // ← 只有上限
)
```

行本身是**精确高度**（`ComposerQueuePanel.kt:167` `.height(ROW_HEIGHT)`，`:296`
`ROW_HEIGHT = SessionPaneLayout.QUEUE_ROW_HEIGHT = 32.dp`），但 `Modifier.height(h)` 在
Compose 里是 `targetSize.constrain(constraints)`：**会把自己夹到父给的 `maxHeight`**。

⇒ 因果链（4 步，每步可读）：

1. `SessionPaneLayout.kt:1247` `room = pane − 64 − composerFloor`，下限被 `coerceIn` 压成 `0.dp`。
2. `SessionScreen.kt:1879-1883` 把这个 `queueBarCeiling(...)` 作为 `queueBarMaxHeight` 传下去。
3. `Composer.kt:1389` 转发 `maxHeight = queueBarMaxHeight` → `ComposerQueuePanel`。
4. `ComposerQueuePanel.kt` 的 `LazyColumn` 只有 `heightIn(max = maxHeight)`：
   **面板内部确实是可滚动容器**（`LazyColumn`，所以"条目多时在队列条内部滚动"这条路本身存在），
   但**视口没有一个"至少一行"的地板**。当 `maxHeight < 32dp` 时，
   滚动容器里能显示的只有**被裁掉一部分的行**——不是"行变矮"，是**行被裁**。

**行高是被裁、不是被按比例分配**：`Row` 没有最小高度的问题在于它**根本没有机会拿到最小高度**，
因为 `heightIn` 的 `min` 是 0；`Row` 里也没有任何 `weight` 参与纵向分配（`QueueRow` 的
`weight(1f)` 只用在行内横向排布正文，`ComposerQueuePanel.kt:177`）。

### 1.3 为什么 `Steer`/`×` 从 a11y 树里消失：被裁 ⇒ 不进树

`QueueRow` 的两个动作都在**那一行之内**：

- `Steer`：`ComposerQueuePanel.kt:241-255` 的 `Text` + `clickable`，行内右侧；
- `×`：`ComposerQueuePanel.kt:257-276` 的 `Box(Modifier.size(28.dp).clickable{}.semantics{ contentDescription = removeLabel })`。

视口比一行矮 ⇒ 这一行的 y 区间有一部分落在视口之外 ⇒ 动作控件的 bounds 被父视口裁掉
⇒ **`uiautomator dump` 只报可见节点**，所以"全树 0 个 `Steer`、0 个 `×`"。
`REPORT_U1.md` §3 判据四的读数与这一步完全自洽：**"0 个节点"和"行高 2–3px"是同一件事的两种说法。**

### 1.4 ⚠️ 读数归属修正：「2–3px、48px 宽、右缘」那一组读数极可能**不是队列条**

`REPORT_U1.md` §2/§3 判据四把「队列 11/23 条」读成「行 `x=1316..1364`（**48px 宽**）、
`y=140..208`、h=2–3px、容器 `ScrollView [1328,162][1352,186] h=24 scrollable=true`」。
该轮报告 §3 判据四已自陈「无法 100% 排除『右缘轨 = 时间线内的消息跳转轨』」。本轮从代码侧给出
两条独立反证，指向**同一个别的组件**：

| # | 证据 | 读数侧 | 代码侧 |
|---|---|---|---|
| 1 | **宽度** | 轨 `x=1316..1364` = **48px 窄轨** | 队列条是 `fillMaxWidth()`（`ComposerQueuePanel.kt:96`），且 `REPORT_QUEUE_ACTIONS.md` §2 实测它的行是 `[22,180][1257,228]` ≈ **1235px 宽** —— 队列条**不可能**是 48px 宽。而 `MessageNavRail.kt:64` `.width(RAIL_WIDTH)` 正是**右缘窄轨** |
| 2 | **2–3px 的来源** | 行高 **2–3px** | 全仓**唯一**一处"把可用高度按条目数等分、并压到 3 dp 地板"的代码：`MessageNavRail.kt:128-132` `tickHeightDp(count, availableDp) = (availableDp / count).coerceIn(MIN_TICK_HEIGHT_DP=3f, TICK_HEIGHT_DP=12f)`；`:66` `.verticalScroll(scroller)` 正好对应读数里那个 `scrollable=true` 的 `ScrollView` |

再加上**数值对账**（本轮真值表，`pane = 720 − 378 = 342`，composer 单行 `card = 56`，
队列 ≥5 条 ⇒ `bar = 148`）：预测**时间线 = 342 − 56 − 148 = 138 dp**，
而 5556 实测 **140px @160dpi**（1 dp = 1 px，**Δ=2px**）。
若真按"视口 148 + chrome 18 = 166"结算，预测应是 120px，与实测差 20px。
⇒ **模型与读数吻合，说明队列条在 11–23 条时确实按预算整块占位，根本没有被压扁。**

**结论（诚实标注）**：队列条在 11/23 条时被裁行这一**代码缺陷真实存在**（§1.1–1.3，已修），
但 `REPORT_U1` 那组 **2–3px / 48px 宽** 的具体读数**极可能来自时间线的消息跳转轨**
（`ui/session/timeline/MessageNavRail.kt`），而不是 `ComposerQueuePanel`。
**该组件不在本轮写权限内**（我上一轮没改过它），故**未改**，改为把它的读数特征写进 §6 的复验步骤，
让下一轮用**真正的 ≥11 条队列**把两件事分开验。**我不宣称 100% 排除**，因为那一轮并未证明队列态存在。

### 1.5 顺带发现并修掉的模型 bug（pre-existing，非本轮引入）

`queueBarHeight(rows, pane)` 内部调 `queueBarCeiling(pane)` 用**默认的便宜卡片 56dp**，
而 `timelineShare` 用**真实的卡片**（折行 116dp）结算。两边预算不同 ⇒ 真值表的
"band"与"bar"两半**不相加等于 pane**。这被本轮新增断言
`timeline + card + bar <= pane` 当场抓出（`1366x600 / 折 2 行 / 2 条`：`64 + 116 + 82 = 262 > 261`，
多出 1 dp）。修法：给 `queueBarHeight` 加 `composerFloor` 形参（**默认值不变**，
既有 pin 看到的数值逐字不变），并让 `timelineShare` 走同一条路（`SessionPaneLayout.kt:1303`）。

---

## 2. 修法（双向最小值 + 内部滚动）

### 2.1 改了什么

| 符号 | 位置 | 作用 |
|---|---|---|
| `QUEUE_BAR_MIN_HEIGHT = 32 + 18 = 50dp` | `SessionPaneLayout.kt:1157` | **面板级地板**：一整行 + 面板 chrome。上游 148dp 是按 **dp** 写的，本壳在 @160dpi 下 `1dp = 1px`，故 `QUEUE_ROW_HEIGHT = 32.dp` 与 `QUEUE_BAR_MAX_HEIGHT = 148.dp` **本就是 px 量级**，无需换算 |
| **`queueBarViewportFloor(rows)`** | `SessionPaneLayout.kt:1184` | **行级地板**：`if (rows <= 0) 0.dp else minOf(32.dp, intrinsic)` ⇒ 只要有行，**视口永不低于一整行** |
| **`queueBarVisibleRows(rows, bar)`** | `SessionPaneLayout.kt:1193` | 一个面板能**整行**显示几条（其余靠滚动），供预演表与 pin 使用 |
| `queueBarCeiling` | `SessionPaneLayout.kt:1247-1249` | **下限抬高**：`if (room < QUEUE_BAR_MIN_HEIGHT) return 0.dp` —— 面板拿不到"只够裁一行"的房间，就**不画行** |
| `queueBarHeight(rows, pane, composerFloor)` | `SessionPaneLayout.kt:1268` | 与 `timelineShare` 扣**同一张卡**（§1.5），默认值不变 |
| `LazyColumn` 视口 | `ComposerQueuePanel.kt:130-131` | `.heightIn(min = queueBarViewportFloor(rows.size))` **在** `.heightIn(max = maxHeight)` **之前** —— 地板在上限之内合成，**上限仍然胜出**（`heightIn` 对会把 min 收敛到 max），所以这个地板**永远不会撑破 pane 预算** |

### 2.2 冲突时的优先级（**显式写明**）

当 `pane` 装不下「时间线 + 卡片 + 队列条」时，**按此顺序付钱**：

1. **时间线地板 `TIMELINE_FLOOR_UNDER_QUEUE = 64dp`（= `TIMELINE_MIN_CONTENT`「一行半」）** —— 队列条永不可吃；
2. **composer 卡片地板**（单行 56dp / 折行 116dp）—— 工具行（v51）永不可吃；
3. **队列条拿余量**，上限 148dp；**超出部分在面板内部滚动**（`LazyColumn` 一直在），**行高恒为 32dp，永不缩放**；
4. **余量 < 50dp（一整行放不下）⇒ 队列条退化为纯 chrome、行不可见**，那点余量**全部还给时间线**
   —— 因为天花板是队列条**唯一**的索取权，它不要的都不会留在外面被裁成半行；
5. **若 `pane < 64 + card + 50`**：**时间线优先，队列条不可达、动作不可点**。这是**唯一**会丢
   `Steer`/`×` 的窗口，**显式声明并已 pin**，不假装它是 PASS。
   （本壳最紧的实测读数 `1366x600` + 折 2 行：`64 + 116 + 50 = 230 < pane 261`，
   仍比这条线宽 31dp，**保得住一整行** —— 见 §3 表 ⑩⑪。）

### 2.3 不动的

队列条出现/消失的时机（`Composer.kt:1372` `queueSupported && queuedRows.isNotEmpty()`）、
`composerSharesViewport` 的形制判据、`viewportHeightPx` 的算式、`chromeMaxHeight` 阶梯、
composer 的键盘 inset 路径、任何字符串资源、`Row` 的 32dp 行高与 `Steer`/`×` 的角色与次序。

### 2.4 diff

```diff
--- a/.../ui/session/SessionPaneLayout.kt
+++ b/.../ui/session/SessionPaneLayout.kt
@@ v99 段 @@
-    /**
-     * The smallest a queue bar may be drawn at — one whole row plus its chrome.
-     * ... so [queueBarCeiling] yields to it and the timeline is what gives way instead.
-     */
+    /**
+     * v99-b: the smallest a queue bar may be **drawn** at — one whole `h-8` row
+     * plus the panel's chrome. ... so it is the line the budget snaps to (see
+     * [queueBarCeiling]): a bar with less room than this is not a bar, because
+     * what would be left of its only row is a sliver whose action buttons are
+     * clipped out of the layout — and a clipped node is not in the
+     * accessibility tree at all ...
+     */
     val QUEUE_BAR_MIN_HEIGHT: Dp = QUEUE_ROW_HEIGHT + QUEUE_BAR_CHROME

+    /** v99-b: the **row-level** floor — the height of the bar's scroll viewport
+     *  while it has rows to show. One whole `h-8` row, never a fraction of one. */
+    fun queueBarViewportFloor(rows: Int): Dp =
+        if (rows <= 0) 0.dp else minOf(QUEUE_ROW_HEIGHT, queueBarIntrinsicHeight(rows))
+
+    /** v99-b: how many rows a bar of [barHeight] shows **whole**. The rest are
+     *  reachable by scrolling the viewport down to [queueBarViewportFloor] ... */
+    fun queueBarVisibleRows(rows: Int, barHeight: Dp): Int {
+        if (rows <= 0 || barHeight <= 0.dp) return 0
+        val floor = queueBarViewportFloor(rows)
+        if (floor <= 0.dp) return 0
+        return ((barHeight - QUEUE_BAR_CHROME).coerceAtLeast(floor) / floor).toInt()
+            .coerceIn(0, rows)
+    }

@@ queueBarCeiling @@
         if (pane <= 0.dp) return 0.dp
         val room = pane - timelineFloor - composerFloor
+        if (room < QUEUE_BAR_MIN_HEIGHT) return 0.dp
         return room.coerceIn(0.dp, QUEUE_BAR_MAX_HEIGHT)

@@ queueBarHeight / timelineShare @@
-    fun queueBarHeight(rows: Int, pane: Dp): Dp =
-        queueBarIntrinsicHeight(rows).coerceAtMost(queueBarCeiling(pane))
+    fun queueBarHeight(
+        rows: Int,
+        pane: Dp,
+        composerFloor: Dp = COMPOSER_MINIMAL_HEIGHT,
+    ): Dp = queueBarIntrinsicHeight(rows)
+        .coerceAtMost(queueBarCeiling(pane, composerFloor = composerFloor))

     // timelineShare:
-        val bar = queueBarIntrinsicHeight(queueRows)
-            .coerceAtMost(queueBarCeiling(pane, composerFloor = card))
+        // v99-b: through [queueBarHeight], so the band and the bar are two halves
+        // of **one** budget and always add up to the pane.
+        val bar = queueBarHeight(queueRows, pane, composerFloor = card)

--- a/.../ui/session/composer/ComposerQueuePanel.kt
+++ b/.../ui/session/composer/ComposerQueuePanel.kt
         LazyColumn(
             modifier = Modifier
                 .fillMaxWidth()
+                // v99-b: the viewport's **floor** is one whole row, so the cap
+                // above is spent on hiding rows, never on squashing them. ...
+                // `maxHeight` still wins (a `heightIn` pair coerces min down to
+                // max), which is why the pane budget can never be overflowed.
+                .heightIn(min = SessionPaneLayout.queueBarViewportFloor(rows.size))
                 .heightIn(max = maxHeight),
         ) {

--- a/app/src/test/.../QueueTimelineSpaceV99Test.kt
+++ b/app/src/test/.../QueueTimelineSpaceV99Test.kt
+  7 例新增（既有 10 例逐字未动）：
+  theBarIsNeverHandedACeilingThatCanOnlyCropARow
+  aBarWithRowsIsEitherWholeOrNotDrawn
+  theRowKeepsItsHeightAtEveryQueueDepth
+  bothFloorsHoldAtEveryQueueDepthOnBothWindows
+  theShortWindowPaysTheBandAndTheFirstQueueRow
+  thePanelHoldsTheViewportAtOneWholeRow
+  theBarNeverExceedsItsCeilingAnywhereInTheSweep
```

---

## 3. 修后预演表

用本壳自己的算式（`dp = px @160dpi`）：
`pane = 内容区 − 键盘高`；`card = composerFloorFor(行数)`；
`ceiling = 0（若 pane−64−card < 50）否则 min(pane−64−card, 148)`；`bar = min(32n+18, ceiling)`；
`时间线 = pane − card − bar`。**队列行高恒为 32dp**（`Modifier.height` 精确高，父只裁不缩放）。

| 状态 | 窗口 | pane | composer 行 | 队列行 | card | 天花板 | 队列条高 | **时间线** | **队列行高** | 整行可见 | **是否内部滚动** |
|---|---|---|---|---|---|---|---|---|---|---|---|
| ① 无队列 | 1366x768 | 342 | 1 | 0 | 56 | — | 0 | **286** | — | — | 否 |
| ② 队列 2 条 | 1366x768 | 342 | 1 | 2 | 56 | 148 | 82 | **204** | **32** | 2 | 否（恰好放下） |
| ③ 队列 5 条 | 1366x768 | 342 | 1 | 5 | 56 | 148 | 148 | **138** | **32** | 4 | **是** |
| ④ 队列 11 条 | 1366x768 | 342 | 1 | 11 | 56 | 148 | 148 | **138** | **32** | 4 | **是** |
| ⑤ 队列 23 条 | 1366x768 | 342 | 1 | 23 | 56 | 148 | 148 | **138** | **32** | 4 | **是** |
| ⑥ 队列 2 条（折 2 行） | 1366x768 | 342 | 2 | 2 | 116 | 148 | 82 | **144** | **32** | 2 | 否 |
| ⑦ 队列 5/11/23 条（折 2 行） | 1366x768 | 342 | 2 | ≥5 | 116 | 148 | 148 | **78** | **32** | 4 | **是** |
| ⑧ 队列 2 条 | **1366x600** | 261 | 1 | 2 | 56 | 141 | 82 | **123** | **32** | 2 | 否 |
| ⑨ 队列 5/11/23 条 | **1366x600** | 261 | 1 | ≥5 | 56 | 141 | 141 | **64** | **32** | 3 | **是** |
| ⑩ 队列 2 条（折 2 行） | **1366x600** | 261 | 2 | 2 | 116 | 81 | 81 | **64** | **32** | 1 | **是**（只差 1dp） |
| ⑪ 队列 5/11/23 条（折 2 行） | **1366x600** | 261 | 2 | ≥5 | 116 | 81 | 81 | **64** | **32** | 1 | **是** |

**读表要点**：

- **时间线一列全部 ≥ 64**（①～⑪），没有一处回到 16px。
- **队列行高一列恒为 32**，与队列条数（2 → 23）**无关**：深度只买"滚动"，不买"压扁"。
- **⑩ 是本轮最好的一行**：`pane 261 − card 116 − 天花板 81`，时间线**恰好** 64dp，
  而队列条仍保有**一整行**可见 —— 修前这格是"行被裁成 2–3px"。
- ⑤ 与 REPORT_U1 实测对照：预测 **138dp** vs 实测 **140px**（Δ=2px），模型可对账。
- **⑨⑪ 的时间线恰好等于地板**：这是优先级 1 生效的样子（band 先付），不是巧合溢出。

---

## 4. 为什么不降强度

- **既有 10 例 `QueueTimelineSpaceV99Test` 一行未改**，包括两条"精确等值"断言
  （`theBarYieldsBeforeTheBandAndBeforeTheComposer` 的
  `assertEquals(60.dp, queueBarCeiling(180.dp))` 与 `queueBarHeight(2, 180.dp) == 60`）——
  本轮改动让它们**原值通过**（`room = 180−64−56 = 60 ≥ 50`，抬高下限不影响该格）。
- **新 pin 全部只增不减**，`QueueRowActionsPinTest`、`QueuedLabelTest`、
  `SessionPane*`（11 个）、`Composer*`（31 个）、`Wave23a*`、`StringsLocaleParityTest` 共
  **414 例 0 失败**。
- 结构 pin **锁死新地板、也锁死旧地板不得回退**：面板必须
  `heightIn(min = queueBarViewportFloor(rows.size))` 且**排在** `heightIn(max = maxHeight)` 之前；
  不得再出现 `heightIn(min = 32.dp)` 硬编码、不得出现 `fillMaxHeight`；
  `QueueRow` 的行高必须**精确且非 weight 分配**（新 pin 直接对行链前 240 字符断言无 `weight(`）。
- 真值表从"只扫 band"扩成"**同时**扫 band + 行高 + 是否内部滚动"：
  `theBarNeverExceedsItsCeilingAnywhereInTheSweep` 覆盖
  56 总高 × 16 键盘高 × 3 composer 行 × 24 队列行 = **64 512 组**。
- 过程中被自己的 pin 打回两版（**没有为了让它通过而放宽断言**）：
  ① `Dp * Dp` 编译不过（`:474`）→ 改用 `.value` 算术；
  ② `assertEquals(0.dp, queueBarVisibleRows(...))` 类型不符 → 改 `assertEquals(0, …)`；
  ③ **真 bug**：`timeline + card + bar = 262 > pane 261` 抓出 §1.5 的双预算不一致，
  修生产代码（`queueBarHeight` 加 `composerFloor`）而不是改断言。

---

## 5. 测试证据

命令（等 `.agent-gradle-lock` 消失 → 写锁 → **前台跑完** → 释放；仅 `:app:testDebugUnitTest`，
无 assembleDebug / clean / 装机）：

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew :app:testDebugUnitTest --tests com.xiufeigo.opencodeshell.SessionPaneArrangementV76Test `
  --tests com.xiufeigo.opencodeshell.SessionPaneChromeFloorV79Test ... `
  --tests com.xiufeigo.opencodeshell.StringsLocaleParityTest `
  --tests com.xiufeigo.opencodeshell.Wave23aSettingsPaletteTerminalTest --console=plain
# 覆盖 SessionPane* / PaneLayout* / Queue* / Queued* / Composer* / Wave23a* / StringsLocaleParityTest 共 45 个类
```

原文（末尾）：

```text
> Task :app:compileDebugKotlin
> Task :app:compileDebugUnitTestKotlin
> Task :app:compileDebugUnitTestJavaWithJavac NO-SOURCE
> Task :app:processDebugUnitTestJavaRes UP-TO-DATE
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 9s
52 actionable tasks: 5 executed, 47 up-to-date
GRADLE_EXIT=0
```

用例数（`app/build/test-results/testDebugUnitTest` 汇总）：

```text
CLASSES=45 TESTS=414 FAILURES=0 ERRORS=0 SKIPPED=0
QueueTimelineSpaceV99Test: tests=17 failures=0 errors=0
```

`QueueTimelineSpaceV99Test` 17 例（**新增 7 例加粗**）：

```text
  - theFailingFrameLeavesATimelineBand                 (既有)
  - theBandsHeightIsIndependentOfHowManyPromptsAreQueued(既有)
  - theBarYieldsBeforeTheBandAndBeforeTheComposer      (既有, 含两条精确等值断言)
  - theBarCeilingIsBoundedAtBothEnds                   (既有)
  - noQueuedPromptsMeansNoBarHeight                    (既有)
  - everyPaneThatCanPayForTheBandKeepsIt               (既有)
  - theTimelinePaneCarriesAMinimumHeightConstraint     (既有)
  - theBarTakesItsCeilingFromThePaneNotFromAConstant   (既有)
  - theBarCeilingIsDerivedFromThePaneNotFromTheHost    (既有)
  - theBandAndTheComposerArePaidBeforeTheBar          (既有)
  - theBarIsNeverHandedACeilingThatCanOnlyCropARow     (新)
  - aBarWithRowsIsEitherWholeOrNotDrawn                (新)
  - theRowKeepsItsHeightAtEveryQueueDepth              (新)
  - bothFloorsHoldAtEveryQueueDepthOnBothWindows       (新)
  - theShortWindowPaysTheBandAndTheFirstQueueRow       (新)
  - thePanelHoldsTheViewportAtOneWholeRow              (新)
  - theBarNeverExceedsItsCeilingAnywhereInTheSweep     (新)
```

新 pin 的核心断言（摘）：

- `theBarIsNeverHandedACeilingThatCanOnlyCropARow` — 扫 `pane = 0..2000dp` × 3 种卡片，
  **天花板恒为 `0` 或 `≥ 50dp`**；并卡死边界：`pane = 64+56+50` 时 `= 50dp`，`−1dp` 时 `= 0dp`。
- `theRowKeepsItsHeightAtEveryQueueDepth` — 2/5/11/23 条时
  `queueBarViewportFloor(rows) == 32.dp` 且 `≥ 32.dp`（即 `REPORT_U1` 读到的 2–3px 不可再表达）。
- `bothFloorsHoldAtEveryQueueDepthOnBothWindows` — **两个窗口 × 1/2 行 × 2/5/11/23 条**同时断言
  ① `时间线 ≥ 64` ② `时间线 + card + bar ≤ pane` ③ 有面板时 `bar ≥ 50dp` 且整行可见 ≥1。
- `theShortWindowPaysTheBandAndTheFirstQueueRow` — `1366x600` 逐格钉死，含
  `queueBarCeiling(261.dp, card=116) == 81dp`。

---

## 6. 真机复验步骤（**重点：队列 ≥11 条**）

前置：`wm size` / `wm density` 记下入测值；`show_ime_with_hard_keyboard` 核对；自建会话；
每条读数前记录 `mInputShown`。姿势沿用 `REPORT_QUEUE_ACTIONS.md` §2：**长回合 → 点 `Queue` 切换 chip**
（默认 `followUpBehavior=steer` 会让队列恒空，那不是缺陷）。

1. **先把队列堆到 ≥ 11 条**（建议 **11 条与 23 条各做一次**）：turn 运行中连续入队 11/23 条。
2. **断言 A（时间线带，本轮未动的老判据）**：scrollable 时间线容器高度 **≥ 64 px**（@160）；
   且队列条矩形与时间线带 y 区间**无包含关系**（`REPORT_U1` 已是"角落相交"，目标是交集为空）。
3. **断言 B（行高，本轮新判据）**：队列行节点 bounds 的**高度 ≥ 32 px**。
   取 ≥2 个行节点逐个读；出现 `h < 32` 即为回归。
4. **断言 C（`Steer`/`×` 可见，本轮主判据）**：dump 中 `text="Steer"`（或 `Send`）节点 **≥1 个**、
   `content-desc="Remove"` 节点 **≥1 个**，且二者 `clickable=true`、**bounds 非空且高度 ≥ 32**。
   ⇒ 对应 `REPORT_U1` 判据四的 `STEER_NODES=0 / XMARK_NODES=0`。
5. **断言 D（内部滚动）**：队列容器 `scrollable=true`，且**行数 > 可见整行数**
   （11 条应只见 4 条整行）。这证明"深度买的是滚动，不是压扁"。
6. **实点**：点 `×` ⇒ 队列少一行且该条不发送（沿用 `REPORT_QUEUE_ACTIONS` §4.2 判据）；
   点 `Steer` ⇒ 该条并入当前回合、turn 不打断（§4.1）。
7. **对照无队列态**：时间线应 ≈ §3 表 ① 的 286 量级。
8. **极端窗口**：**先设 `wm size 1366x600` + `wm density 160`，再一次性发长回合 + 连发 ≥11 条**
   （`REPORT_U1` §5.2 就是因为先 dump 后入队而 `QROWS=0`）。断言同 2/3/4/5，
   预期时间线 **64–123px**、行高 **32px**、可见整行 **1–3**。
   **收尾必须 `wm size reset` + `wm density reset` 并复核读回。**
9. **把两件事分开验（针对 §1.4 的归属修正）**：额外 dump 一次**队列为空**但时间线有多条消息的态，
   看右缘轨是否存在。若存在且其条目高 `≈ available/count`（2–12px 随条目数变化），
   则 `REPORT_U1` 那组 2–3px 读数属于 `MessageNavRail`，**与队列条无关**。

---

## 7. 未闭环

| # | 项 | 状态 | 原因 / 下一步 |
|---|---|---|---|
| 1 | **真机复验 §6 全部 9 步** | **未做** | 本轮纪律只允许 JVM 单测（`testDebugUnitTest`），禁装机/跑设备。§6 待复验轮执行 |
| 2 | **「2–3px 轨 = `MessageNavRail`」的归属** | **推断（证据强，未 100%）** | 宽度 48px vs 队列条全宽 1235px、3dp 等分公式、容器 `verticalScroll`、138dp↔140px 数值对账，四条同向；但 `REPORT_U1` 那一轮**未证明队列态存在**，且我没读过那些 dump 原文。`MessageNavRail.kt` 不在本轮写权限内，**未改**。§6 第 9 步专门用来把它分开验 |
| 3 | **面板 chrome（18dp）相对视口上限的结算口径** | **沿用 v99，未改** | `heightIn(max = maxHeight)` 限的是**行视口**，18dp chrome 在其之外 ⇒ 面板实际高可至 `ceiling + 18`（≤18dp 建模松弛）。改它要动既有精确等值断言（`queueBarCeiling(180) == 60`），**本轮选择不动**，仅在此声明。§3 表按现行口径给出 |
| 4 | `pane < 64 + card + 50` 窗口里 `Steer`/`×` 不可达 | **已声明，未消除** | 这是优先级 1（时间线先付）的**直接后果**：pane 装不下三者时，时间线赢。已 pin（`theBarIsNeverHandedACeilingThatCanOnlyCropARow`），属**产品取舍**，如需改判请 Lead 指示 |
| 5 | 面板"退化区"绘制为纯 chrome（18dp 空条） | **已知形态，未改** | 余量 ∈ (0,50) 时面板仍组合（不改出现/消失时机语义），但没有行可画。视觉上是一条空条。改为整条不画需新增 `ceiling > 0` 门，会改动"出现/消失时机"，超出本轮授权 |
| 6 | 极端窗口下带队列的真机读数 | **未取到** | 沿用 `REPORT_U1` §5.2 的同一缺口（`QROWS=0`）。§6 第 8 步给了更短的构造路径 |
| 7 | composer 折 2 行 | **仍不可构造** | 沿用 `REPORT_U1` §5.1（288 字符仍 h=48）。§3 表的"折 2 行"行是**按本壳算式外推**，非 px 读数 |

---

## 8. 一句话总结

**上一轮把队列条做成了"可收缩"，收缩的下限是 `0.dp`（`queueBarCeiling` 的 `coerceIn(0.dp, 148.dp)`），
而面板的滚动视口只有 `heightIn(max = …)`、没有 `min` ⇒ 天花板一旦小于 32dp，
`Modifier.height(32.dp)` 的行就被父视口裁掉，`Steer`/`×` 随行一起出界，
被裁节点不进 a11y 树（`uiautomator` 只报可见节点），于是"全树 0 个 `Steer`/`×`"；
本轮把下限改成**双向最小值**——面板级 `QUEUE_BAR_MIN_HEIGHT = 50dp`（天花板不足即整条不画行，
余量还给时间线）+ 行级 `queueBarViewportFloor = 32dp`（`heightIn(min = …)` 排在 `max` 之前，
深度只买滚动、**行高恒 32dp**），并顺带修掉 `queueBarHeight`/`timelineShare` 扣两张不同卡片
导致的 1dp 预算不一致**；时间线地板 64dp 一行未动，预演表 11 格时间线全部 ≥64、
队列行高全部 32；既有 10 例 pin 逐字未改，新增 7 例，45 类 414 例 BUILD SUCCESSFUL 0 失败。**
