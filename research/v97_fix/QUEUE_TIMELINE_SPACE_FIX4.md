# v101 修复 — 极端窗口 `1366x600` 下 `readable = 51px < 64`：**地板让位阈值显式化 + 地板对真实 pane 求值**

- 修复工程师：mavis（极端窗口地板裁定轮）
- 日期：2026-10-03
- 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
- 触发依据：`research/v97_verify/5556_v98/REPORT_U1D.md`（**已整篇读**）
- 上一轮修复：`research/v97_fix/QUEUE_TIMELINE_SPACE_FIX3.md`（v100：硬地板 + `composerHostCeilingPx`）
- 写权限：仅 `ui/session/SessionPaneLayout.kt`、`ui/session/SessionScreen.kt`、
  新测试 `app/src/test/java/com/xiufeigo/opencodeshell/QueueTimelineFloorV101Test.kt`、
  加严 `app/src/test/java/com/xiufeigo/opencodeshell/QueueTimelineFloorV100Test.kt`。
  **未碰** `_upstream/`、`app/build.gradle.kts`、`ChatViewModel.kt`、`ui/servers|home|terminal|nav/**`、
  `ui/App.kt`、`OcActivity.kt`、`ui/session/timeline/**`、`ui/session/composer/**`。
- 未新增任何用户可见字符串；**未改动、未放宽任何既有断言**（含 `QueueTimelineFloorV100Test` /
  `QueueTimelineSpaceV99Test` 的，详见 §5）；未跑 assembleDebug / clean / 装机；
  gradle 前台跑完，锁已获取并在结束后释放。

> **状态：DONE（JVM 已闭环 · 真机待复验）** —— §1 定性（**51px = pane 本身**，
> 逐项预算无一项被浪费）；§2 裁定 **方案 B 显式化 + 一处方案 A 杠杆落地**；
> **47 个类 427 例 0 失败**；`QueueTimelineSpaceV99Test` 17 例、`QueueTimelineFloorV100Test`
> 8 例（新增 2 例加严）逐字绿。

---

## 1. 定性：51px 是什么（必答）

### 1.1 一句话

> **51px 不是「地板被别的预算吃掉」，也不是「地板失效」，而是 `pane` 本身就只有 51px**：
> 地板规则 `min(64, pane)` **生效了**，让位到 `pane`；宿主（composer）被地板吃成 **0**、
> 队列条 **0**、时间线 **51**，`51 + 0 + 0 = 51 = pane` —— **没有第四项预算可以再挤**。
> 真正该追问的不是地板，而是**这 600px 窗口的 pane 为什么只有 51**（见 §1.4）。

### 1.2 600px 窗口的逐项预算算式

设备读数（`REPORT_U1D.md` §7.3，`wm size 1366x600` + `wm density 160`，IME 起来后连读 3 帧**逐字相同**）：

```
时间线节点  = 0,162 - 820,213        ⇒ 盒高 51
队列条      = 不在树（0 个 Steer / 0 个 Queued）
```

**窗口纵向切分（`dp == px @160dpi`）**

```
窗口            = 600
  y   0..158    header / 会话标题带（不属于 pane）
  y 158..162    4px 间隙
  ───────────── pane 顶 y = 162
  y 162..213    pane                ⇒ pane 高 = 213 − 162 = 51
  y 213..600    believedImeBottomPx = 387（= 234dp）  ← 壳自认的 IME
  校验：162 + 51 + 387 = 600  ✔ 逐位自洽
```

**逐项预算（按代码 `文件:行号` 代入）**

| # | 预算项 | 算式 | 代入 | 结果 |
|---|---|---|---|---|
| ① | `pane` | 窗口 − header − `believedImeBottomPx` | `600 − 162 − 387` | **51** |
| ② | 地板 `floor` | `timelineViewportFloor(pane)` = `min(64, pane)` | `min(64, 51)` | **51**（**让位生效**） |
| ③ | 宿主上限 | `composerHostCeilingPx(pane, floor)` = `pane − floor` | `51 − 51` | **0** ⇒ `host.height = 0` |
| ④ | composer 卡片 `card` | `composerFloorFor(lines)` = 56 / 116 | — | 被 ③ 压成 **0** |
| ⑤ | 队列条额度 | `queueBarCeiling(pane, 64, card)`：`room = pane − 64 − card < QUEUE_BAR_MIN_HEIGHT(50)` ⇒ `0.dp` | `51 − 64 − 56 = −69 < 50` | **0** ⇒ 整条不画 ✔ 与设备「队列条整体消失」一致 |
| ⑥ | **时间线 `readable`** | `viewportHeightPx(shared, pane, hostPaid, floor)` = `max(pane − hostPaid, floor)` | `max(51 − 0, 51)` | **51** ✔ 逐位命中设备读数 |
| ⑦ | **合计** | 时间线 + 宿主 + 队列条 | `51 + 0 + 0` | **51 = pane**，无余额 |

**⇒ 结论（对应派单的两个选项）**

- **不是「地板没生效」**：地板在 ⑥ 里正是把 `readable` 顶到 51 的那一项（`coerceAtLeast(floorPx)`）。
- **也不是「地板本身被缩小」**：`min(64, pane)` 是 v100 **有意**写下的随 pane 缩放形式；
  让位的**唯一触发条件**是 `pane < 64`，而这里 `pane = 51 < 64` —— 规则按设计让位。
- **真正的问题在这一格之前**：pane 之所以是 51，是因为**壳把 387px（占窗口 64.5%）当成了 IME**。
  若 `believedImeBottomPx` 实为 ~300，则 `pane = 138`、`floor = 64`、`readable = 64`（§3.2 对照行）。

### 1.3 排除项（为什么不怪别人）

| 假设 | 判定 | 依据 |
|---|---|---|
| 队列条吃了时间线 | ❌ 排除 | ⑤ 已算成 `0`，设备侧队列条 `Steer`/`Queued` **各 0 个**、不在树 |
| composer 吃了时间线 | ❌ 排除 | ③ `hostCeiling = 0` ⇒ composer 拿到 **0**；它比时间线先让路（优先级 1） |
| 地板的减法仍留在 0（v100 之前的 `coerceAtLeast(0)`） | ❌ 排除 | ⑥ 走的是 4 参重载 `coerceAtLeast(floorPx)`，`SessionPaneLayout.kt:1080-1100` |
| `imePadding` / `navigationBarsPadding` 叠加 | ❌ 排除 | pane 底沿 213 与 IME 顶 213 相接，**一次性**内边距（`FIX3.md` §1.4 #3 已定案） |
| 1366x600 出现了第二个 `min`（`pane` 在别处又被缩） | ⚠️ **本轮无法在设备上证实**，见 §6 | 需要真机读 `pane`；本轮已把该读数**变成可 pin 的量**（§2.3） |

### 1.4 诚实的未证实项（必须在真机闭环）

`REPORT_U1D.md` **没有记录该窗的 `pane` 读数，也没有记录 IME 实际顶沿**，
所以「`pane = 51`」是**从时间线节点 51 反解出来的唯一自洽解**（在 v100 代码下：
`readable = 51` ⇒ `pane − hostPaid ≤ 51` 且 `floor = min(64,pane)`；若 `pane ≥ 64` 则 `floor = 64`
⇒ `readable ≥ 64`，与 51 矛盾 ⇒ **必有 `pane < 64`**）。这个推理是**闭合**的，
但「`pane` 恰好 51、也就是 `believedImeBottomPx` 恰好 387」这一步**依赖设备坐标反解**，
需要 §6 的一次真机读数把它钉死。

---

## 2. 裁定与修法

### 2.1 为什么不选方案 A（把 `readable ≥ 64` 在 1366x600 下做成**无条件**成立）

**算术上不成立**：`readable` 是 pane 内的一个盒子，**不能大于 pane 本身**。

```
要求：readable ≥ 64
现状：pane = 51
⇒ 64 > 51 = pane          ⇒ 布局必须画出 pane 之外 13px
⇒ 那 13px 只能落在两种地方之一：
   (a) 压到窗口底部之外 / 落到 IME 之下 ⇒ 被系统裁掉，屏幕上看不见，且在 uiautomator
       dump 里量到的仍是 51 —— 换成一个**假过**，不是修复；
   (b) 去吃 header（158）或 `believedImeBottomPx`（387）⇒ 那是**改 IME 信念 / 改 header**
       的另一场修复（`v75_ime_space` / `v77_ime_inset_belief` 两条线），不在本轮授权内，
       且没有真机读数支撑，盲改会把 768 窗已经过的那一格也带崩。
```

**⇒ 方案 A 在「`pane` 真的只有 51」这个前提下是几何上不可能的。**
把地板强行按比例上调（方案 B 的公式化形态）只会把同一件事藏得更深：它让 51 变成 47，
而 `pane` 仍然是 51 —— **地板不再等于可读带的物理上限，pin 就会开始保护一个不存在的数**。

### 2.2 选定：**B（显式声明 + pin 阈值与公式）**，理由三条

1. **51 已经是该窗的物理上限**（§1.2 ⑦：没有任何预算项有余额），所以「51 是否可接受」这个问题
   的正确答案是：**它不是「低」，它是「全部」**。真正的缺陷是**它此前是沉默的** ——
   阈值既没有名字、也没有 pin，`min(64, pane)` 一行代码就能把它改掉而 CI 全绿。
2. **显式化把「取舍」变成可审计的契约**：`TIMELINE_FLOOR_YIELD_PANE = 64.dp` + 两分支规则
   + `timelineFloorGuaranteed` 谓词 + 三维真值表逐格 pin（§4）。从此「让位」是一个**被断言的声明**。
3. **A 的唯一在权内杠杆我仍然落地了**（见 §2.3）—— 能修的那一半我修掉，不能修的那一半我论证并写进未闭环。

### 2.3 修法（三处，v101）

**(a) 地板规则从「一行 `coerceAtMost`」改成**显式两分支 + 命名边界 + 谓词 + px 域同构
（`SessionPaneLayout.kt:1050-1130`）。**数值与 v100 逐字相同**（`min(64,pane)` 的等价写法），
所以对已 PASS 的两格**零影响**；变的是**可审计性**：

```kotlin
val TIMELINE_FLOOR_YIELD_PANE: Dp
    get() = TIMELINE_FLOOR_UNDER_QUEUE                       // 64.dp：让位边界

fun timelineViewportFloor(pane: Dp): Dp = when {             // 显式两分支
    pane <= 0.dp -> 0.dp
    timelineFloorGuaranteed(pane) -> TIMELINE_FLOOR_UNDER_QUEUE   // pane >= 64 → 硬地板
    else -> pane                                              // pane <  64 → 声明让位
}

fun timelineFloorGuaranteed(pane: Dp): Boolean = pane >= TIMELINE_FLOOR_YIELD_PANE

fun timelineViewportFloorPx(panePx: Int, floorPx: Int): Int = when {   // density-free 同构
    panePx <= 0 -> 0
    floorPx <= 0 -> 0
    panePx >= floorPx -> floorPx
    else -> panePx
}

fun timelineReadableFloorPx(panePx: Int, floorPx: Int): Int =   // 每格该断言的那个数
    timelineViewportFloorPx(panePx, floorPx)
```

**(b) 真修：地板改为对「测量期真实存在的 pane」求值**（`SessionScreen.kt:3217-3240`）。
这是 v100 留下的**唯一真实漏洞**，也是 A 唯一在权内的杠杆：

- v100 的地板只喂组合期读到的 `paneHeight` —— 一个**早一帧的 `Dp` state**；
- 于是一个**在测量时早已超过 64 的 pane**，仍可能按那个陈旧的、更小的 `paneHeight` 让位 ——
  **地板为一个付得起它的 pane 让了路**，这正是「51px」这类读数的另一条成因；
- 修法：地板取两个读数的 **`maxOf`**，只会**加严**地板，且只加不减：

```kotlin
val declaredFloorPx = with(arrangementDensity) { timelineFloor.roundToPx() }.coerceAtLeast(0)
val measuredFloorPx = SessionPaneLayout.timelineViewportFloorPx(
    panePx = constraints.maxHeight,        // 测量期真实的 pane（px）
    floorPx = declaredFloorPx,
)
val floorPx = maxOf(declaredFloorPx, measuredFloorPx)   // 之后所有约束都用它
```

⇒ **地板现在只可能因为「测量期真实的 pane 自己不足 64」而让位**，这正是 §2.2 声明的那一种。

**(c) 下游约束全部改用这个 `floorPx`**（`SessionScreen.kt:3241-3260`，
`composerHostCeilingPx(pane = constraints.maxHeight, floorPx = floorPx)` 与
`viewportHeightPx(…, floorPx = floorPx)` + `minHeight = floorPx`）——
**这三行调用与 v100 逐字相同**，只是 `floorPx` 的值更硬了。

### 2.4 diff

```diff
--- a/app/src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionPaneLayout.kt
+++ b/app/src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionPaneLayout.kt
@@ v100 段：timelineViewportFloor @@
-    fun timelineViewportFloor(pane: Dp): Dp =
-        TIMELINE_FLOOR_UNDER_QUEUE.coerceAtMost(pane.coerceAtLeast(0.dp))
+    val TIMELINE_FLOOR_YIELD_PANE: Dp
+        get() = TIMELINE_FLOOR_UNDER_QUEUE
+
+    fun timelineViewportFloor(pane: Dp): Dp = when {
+        pane <= 0.dp -> 0.dp
+        timelineFloorGuaranteed(pane) -> TIMELINE_FLOOR_UNDER_QUEUE
+        else -> pane
+    }
+
+    fun timelineFloorGuaranteed(pane: Dp): Boolean =
+        pane >= TIMELINE_FLOOR_YIELD_PANE
+
+    fun timelineViewportFloorPx(panePx: Int, floorPx: Int): Int = when {
+        panePx <= 0 -> 0
+        floorPx <= 0 -> 0
+        panePx >= floorPx -> floorPx
+        else -> panePx
+    }
+
+    fun timelineReadableFloorPx(panePx: Int, floorPx: Int): Int =
+        timelineViewportFloorPx(panePx, floorPx)

--- a/app/src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt
+++ b/app/src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt
@@ SessionPaneArrangement measure policy (3217) @@
-        val floorPx = with(arrangementDensity) { timelineFloor.roundToPx() }.coerceAtLeast(0)
+        val declaredFloorPx =
+            with(arrangementDensity) { timelineFloor.roundToPx() }.coerceAtLeast(0)
+        val measuredFloorPx = SessionPaneLayout.timelineViewportFloorPx(
+            panePx = constraints.maxHeight,
+            floorPx = declaredFloorPx,
+        )
+        val floorPx = maxOf(declaredFloorPx, measuredFloorPx)
```

`SessionScreen.kt` 里**其余零改动**：`timelineFloor: Dp` 形参（`:3166`）、调用点
`timelineFloor = SessionPaneLayout.timelineViewportFloor(paneHeight)`（`:2108`）、
`composerHostCeilingPx(…)` / `viewportHeightPx(…, floorPx = floorPx,)` / `minHeight = floorPx,`
三行**全部逐字保留**（v100 的结构 pin 因此原样通过）。

---

## 3. 三格预演表

`dp == px @160dpi`。`floor = timelineViewportFloor(pane)`；`card = composerFloorFor(lines)`（56 / 116）；
`bar = queueBarHeight(rows, pane, card)`；`hostCap = composerHostCeilingPx(pane, floor)`；
`hostPaid = min(bar + card, hostCap)`；`timeline = viewportHeightPx(true, pane, hostPaid, floor)`。

### 3.1 格 ① `1366x768` @160 + IME 294 —— **必须 ≥64 且不回退**

| composer 行 | 队列条 | pane | floor | card | bar | hostCap | hostPaid | **timeline** | 判定 |
|---|---|---|---|---|---|---|---|---|---|
| 1 | 0 | 474 | **64** | 56 | 0 | 410 | 56 | **418** | ✅ |
| 1 | 11 | 474 | **64** | 56 | **148** | 410 | 204 | **270** | ✅ |
| 3 | 11 | 474 | **64** | 116 | **148** | 410 | 264 | **210** | ✅ |
| 3 | 23 | 474 | **64** | 116 | **148** | 410 | 264 | **210** | ✅ |

- **真机实测（`REPORT_U1D.md` §5，14 条）`readable = 92 ≥ 64`**，比本表保守。
- **v101 对本格的影响 = 0**：`maxOf(64, timelineViewportFloorPx(474, 64)=64) = 64` —— 两侧都是 64。
- 队列条：`bar = 148 = QUEUE_BAR_MAX_HEIGHT`，深度只买**滚动**、不买压扁（行高恒 32）。

### 3.2 格 ② `1366x600` @160 + IME 387 —— **本轮 FAIL 格（设备实读）**

| composer 行 | 队列条 | pane | floor | card | bar | hostCap | hostPaid | **timeline** | 判定 |
|---|---|---|---|---|---|---|---|---|---|
| 1 | 0 | **51** | **51** | 56 | **0** | **0** | **0** | **51** | 声明让位（= 全部 pane） |
| 2 | 0 | **51** | **51** | 116 | **0** | **0** | **0** | **51** | 同上 |
| 3 | 11 | **51** | **51** | 116 | **0** | **0** | **0** | **51** | 同上（队列条也画不出） |

**同一格的对照（若 `believedImeBottomPx` 实为 300 而非 387）**：

| composer 行 | 队列条 | pane | floor | card | bar | hostCap | hostPaid | **timeline** | 判定 |
|---|---|---|---|---|---|---|---|---|---|
| 1 | 0 | **138** | **64** | 56 | 0 | 74 | 56 | **82** | ✅ ≥64 |
| 3 | 11 | **138** | **64** | 116 | 0（`138−64−116 <50`） | 74 | 74 | **64** | ✅ 恰好压地板 |

⇒ **这一行就是本轮未闭环的那把钥匙**：地板在 `pane = 138` 时**照付 64**，一分不让。
**唯一变量是 pane 本身**（§6 的真机复验就是量这个）。

### 3.3 格 ③ 更极端：`480x800` @160 + IME 378

| composer 行 | 队列条 | pane | floor | card | bar | hostCap | hostPaid | **timeline** | 判定 |
|---|---|---|---|---|---|---|---|---|---|
| 1 | 0 | 102 | **64** | 56 | 0 | 38 | **38** | **64** | ✅ 恰好压地板 |
| 1 | 11 | 102 | **64** | 56 | 0（`102−64−56 <50`） | 38 | 38 | **64** | ✅ |
| 3 | 11 | 102 | **64** | 116 | 0 | 38 | 38 | **64** | ✅（composer 被压到 38，**不越界**） |
| — 更极端：IME 430 ⇒ pane 50 | 0 | **50** | **50** | 56 | 0 | 0 | 0 | **50** | 声明让位（= 全部 pane） |

**读表要点**

- **只要 `pane ≥ 64`，三行全 ≥ 64**：格 ③ 里 `pane = 102` 时，**composer 卡片被压到 38**
  （`hostCap = 102 − 64`），**优先牺牲的是 composer，不是时间线** —— 这就是 v100 优先级 1
  在更小窗口上继续成立。
- **只有 `pane < 64` 才让位**，且**让位让到极限**（`timeline = pane`、`host = 0`），
  不存在「让一半」。

---

## 4. 为什么不降强度（逐条）

1. **地板在能保的窗里一分没降**：`timelineViewportFloor` 的数值与 v100 **逐字等价**
   （`min(64,pane)` 的两分支写法），`pane ≥ 64` 时恒为 64 —— 新 pin
   `theBandIsHardAndUnscaledOnEveryAffordablePane` 对 `pane ∈ 64..900` **全整数**断言 `== 64.dp`。
2. **`QueueTimelineSpaceV99Test` 17 例一行未动、全绿**：`floorPx` 仍是新增形参且默认 0，
   `viewportHeightPx` 3 参形态逐字未变；`queueBarCeiling` / `queueBarHeight` / `timelineShare` /
   `QUEUE_*` 常量**一个字节没动** —— 数值上零接触。
3. **`QueueTimelineFloorV100Test` 只加严**：既有 6 例**逐字未动**（含 96 格真值表、
   `pane ∈ 0..900` 全扫描、3 参兼容扫描、三条结构 pin），另**新增 2 例**（§5）。
4. **对已 PASS 的两格影响可证为 0**：`maxOf(declared, measured)` 在该两格的 pane 上
   两侧都是 64（`1366x768` + IME：pane = `768 − 294` = **474**；`U1B` 口径下同为 342 的那一格
   亦然，**只要 `pane ≥ 64` 两侧就都等于 64**；§3.1）；`Steer`/`×` 的可达性由
   `aDeepQueueEitherVanishesOrKeepsOneWholeActionableRow` 覆盖：
   **队列条要么整条不画（`bar = 0`），要么至少画出一整行 32px**（`bar − 18 ≥ 32` 且 `bar ≥ 50`），
   并且**永远不吃地板**（`bar ≤ pane − floor − card`）。
5. **没有为了让测试变绿而动判据**：地板口径的变更（若有）会让 `floor < 64` 的格变得「不达标」；
   本轮**没有**下调 64，而是把让位写成**被 pin 的声明**。

---

## 5. 测试证据

### 5.1 新增 / 更新

| 文件 | 变更 | 用例数 |
|---|---|---|
| `app/src/test/java/com/xiufeigo/opencodeshell/QueueTimelineFloorV101Test.kt` | **新增**（5 例） | 5 |
| `app/src/test/java/com/xiufeigo/opencodeshell/QueueTimelineFloorV100Test.kt` | **加严**（既有 6 例逐字未动 + 新增 2 例） | 8 |
| `app/src/test/java/com/xiufeigo/opencodeshell/QueueTimelineSpaceV99Test.kt` | **未动** | 17 |

`QueueTimelineFloorV101Test` 的 5 例：

1. `theWindowHeightDimensionKeepsTheDeclaredFloorInEveryCell` — **三维真值表**：
   窗口 `{768, 600, 480}` × IME `{294, 0}` × composer 行 `{1,2,3}` × 队列 `{0,2,11,23}` ×
   排列 `{shared, floating}` = **144 格**（断言 `cells == 144`，不走对角线）。逐格断言
   `timeline >= timelineReadableFloorPx(pane, 64)`；`pane ≥ 64` 时**额外**断言 `floor == 64` 且
   `timeline ≥ 64`；`pane < 64` 时断言 `timeline == pane` 且 `hostPaid == 0`；末行式断言预算不溢出、
   覆盖式断言「视口拿整个 pane」；每格断言 `hostPaid ≤ hostCap`。
2. `aDeepQueueEitherVanishesOrKeepsOneWholeActionableRow` — 队列 `{0,2,11,23,60}`：
   `bar > 0 ⇒ bar − 18 ≥ 32 ∧ bar ≥ 50 ∧ bar ≤ pane − floor − card`（`Steer`/`×` 的 h ≥ 32 门槛）。
3. `theSixHundredPixelFrameIsTheWholePaneNotAStolenBand` — **把 `REPORT_U1D` §7.3 那一帧当数据重放**：
   `pane = 51` ⇒ `floor = 51`、`hostCeiling = 0`、`hostPaid = 0`、`bar = 0`、`timeline = 51`（1/2/3 行全验）。
4. `theYieldBoundaryHasNoGapAroundSixtyFour` — `panePx ∈ 0..400` 全整数：
   `≥64 ⇒ floor == 64 ∧ guaranteed`，`<64 ⇒ floor == pane ∧ !guaranteed`，且 **Dp 表述与 px 表述逐字相等**。
5. `theMeasurePolicyRederivesTheFloorAgainstTheMeasuredPane` — 结构 pin：
   measure policy 必须含 `timelineViewportFloorPx(` 与 `maxOf(declaredFloorPx, measuredFloorPx)`，
   且 v100 的三条结构 pin（`composerHostCeilingPx(` / `floorPx = floorPx,` / `minHeight = floorPx,`）**原样保留**。

`QueueTimelineFloorV100Test` 新增 2 例（**只加严**）：

6. `theBandIsHardAndUnscaledOnEveryAffordablePane` — `pane ∈ 64..900` 全整数 `floor == 64.dp`。
7. `aPaneBelowTheBandYieldsToTheLimitAndNotPastIt` — `pane ∈ 0..63`：让位后 `hostCeiling == 0`
   且 `timeline == pane`（让到极限，不越界）。

### 5.2 回归命令与结果

```
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest `
  --tests "com.xiufeigo.opencodeshell.QueueTimelineFloorV101Test" `
  --tests "com.xiufeigo.opencodeshell.QueueTimelineFloorV100Test" `
  --tests "com.xiufeigo.opencodeshell.QueueTimelineSpaceV99Test" `
  --tests "com.xiufeigo.opencodeshell.SessionPane*" `
  --tests "com.xiufeigo.opencodeshell.PaneLayout*" `
  --tests "com.xiufeigo.opencodeshell.Queue*" `
  --tests "com.xiufeigo.opencodeshell.Composer*" `
  --tests "com.xiufeigo.opencodeshell.Wave23a*" `
  --tests "com.xiufeigo.opencodeshell.StringsLocaleParityTest"
```

日志：`research/v97_fix/_v101_gradle.log`（锁 `.agent-gradle-lock` 已获取，跑完已释放）

```
> Task :app:compileDebugKotlin
> Task :app:compileDebugUnitTestKotlin
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 10s
52 actionable tasks: 2 executed, 50 up-to-date
```

**用例数**（`app/build/test-results/testDebugUnitTest/*.xml` 汇总）：

| 项 | 值 |
|---|---|
| 测试类 | **47** |
| 用例 | **427** |
| **失败** | **0** |
| 错误 | **0** |
| 跳过 | **0** |
| `QueueTimelineFloorV101Test` | 5 / 0 失败 |
| `QueueTimelineFloorV100Test` | 8 / 0 失败 |
| `QueueTimelineSpaceV99Test` | **17 / 0 失败（逐字未动）** |

> 环境纪律遵守：只跑 `:app:testDebugUnitTest` + `--tests` 过滤；**未** assembleDebug / clean / 全量 / 装机；
> gradle **前台跑完**；抢锁（等 → 写 → 跑 → 删）；全程未碰设备。
> ⚠️ 过程记录：前两次调用因**编译错误**失败并已当场修正（`TIMELINE_FLOOR_YIELD_PANE` 对
> 后声明的 `val` 前向引用 ⇒ 改 `get()`；测试里 `declared` ⇒ `declaredFloor`）。两次都**未**改判据。

---

## 6. 真机复验步骤（`1366x600` 断言 `readable` 与地板的关系 + 复核两格未回退）

前置：`wm size 1366x768` + `wm density 160`（入测原值），焦点在 `…OcActivity`，
`base.apk` md5 / `lastUpdateTime` 需与派单值核对。**坐标每轮从 dump 重解析**；
读任何高度前**先断言 `mInputShown`**；只压 `emulator-5556`；`/sdcard` 产物 pull 后即删。

### 6.1 格 ③ 极端窗口（本轮核心）—— 判定「让位是声明的，不是沉默的」

1. `adb shell wm size 1366x600` + `adb shell wm density 160`，`wm size` / `wm density` 复核生效。
2. 进会话 → 点 `EditText` 唤起 IME，**轮询到 `mInputShown=true` 才取数**（`U1B`/`U1C` 两轮栽在这里）。
3. `uiautomator dump --compressed` + pull。**同一次 dump 里必须同时取到三样**：
   - 时间线盒 bounds ⇒ `readable = 盒高 − |时间线 ∩ 队列条|`
   - 右侧常驻面板顶沿 / IME 顶沿 ⇒ **`pane`（= 时间线盒顶 .. pane 底沿）**
   - `Queue` chip / `Steer` / `×` 是否在树
4. **判定（本次的正式判据，两分支）**：

   | 读数 | 期望 | 含义 |
   |---|---|---|
   | `pane ≥ 64` | **`readable ≥ 64`** | v101 真修生效：地板不再为陈旧 `paneHeight` 让位 |
   | `pane < 64` | **`readable == pane`**（让位到极限）且 `host == 0` | 声明的让位，**且 64 在该窗物理不可达** |

   - **硬失败条件**（任一命中即 FAIL）：`pane < 64` 但 `readable < pane`（说明有预算项既没让路、
     地板又被压低）；`pane ≥ 64` 但 `readable < 64`；`readable` 连读 3 帧不稳定。
5. **顺带量那把钥匙**：`pane 底沿` 与「窗口高 600」的差 = `believedImeBottomPx`。
   **若它 ≈ 387** ⇒ §1.2 的自洽解被证实，地板按设计让位；
   **若它明显更小（≈300）而 pane 因此 ≥ 64 但 `readable` 仍 < 64** ⇒ v101 真修有残留问题，
   立即回报（这是本轮唯一可能失败的情形）。

### 6.2 复核 ≥11 条格（`REPORT_U1D` §5/§6 已 PASS，不得回退）

6. 窗口复原 `1366x768` @160；按 `REPORT_U1D` §4 的手法建「回合运行 + 队列 ≥11」
   （**点 `Queue` chip 入队**，以树内 `N queued` 计数为准，不以点击次数为准）。
7. 断言：`readable ≥ 64`（`U1D` 实测 **92**）；`Steer`/`×` 满行可见那条 **h ≥ 32 且 `clickable=true`**
   （`U1D` 实测 43/48）；队列行高 **32**；队列条内部**可滚动**（滑一次后可见行文本改变，
   滚动前后 `Steer`/`×` 都取得到，`readable` 两端 ≥64）。

### 6.3 复核 `1366x768` 格（`U1D` 深度档，不得回退）

8. 同窗读时间线盒高与队列条并集，重算 `readable`，断言 `≥ 64`。

### 6.4 收尾

9. `wm size 1366x768` + `wm density 160` **复原入测原值**（`U1D` §8.1 的 BLOCKED 复现点：
   `wm` 连续切换后 app 进程消失、焦点落到 `org.chromium.home.pc` —— 若再次出现，
   **按纪律停手**、不点 launcher、写下 BLOCKED）。
10. 删自建会话（`U1D` §9.2 未闭环项）；`/sdcard` 复查 CLEAN。

---

## 7. 未闭环

1. **「`pane` 是否真的只有 51」需一次真机读数钉死。** §1.2 是从时间线读数 51 **反解**出的
   唯一自洽解（推理闭合），但「`pane` 恰好 51、`believedImeBottomPx` 恰好 387」这一步依赖设备坐标。
   §6.1 第 3–4 步就是为它设计的。
2. **真正的 A 杠杆（把 600px 拉回 ≥64）不在本轮授权内**：若 `believedImeBottomPx` 过量认领，
   修它要动 IME 信念链（`v75_ime_space` / `v77_ime_inset_belief`），**本轮无设备读数，未盲改**。
   §3.2 的对照行给出了它的收益上界（`pane` 138 ⇒ `readable` 82）。
3. **`REPORT_U1D` §9.3**：极端窗口下「回合运行 + 队列 ≥11」**仍未复建成功**，
   所以「`readable` 随队列深度变化」在 600px 窗下**仍未验证**（本轮 §3.2 三行的 `11` 行格
   是**模型格**，不是设备格）。
4. **`REPORT_U1D` §6 的「滚动后被裁行 `Steer` h=20 / `×` h=27 < 32」门槛口径**待 Lead 明确
   （是否限定为「至少一行整行达标」）。本轮 v101 **未改动** `queueBarViewportFloor`，
   满行可见那条的 32px 门槛由 §4.2 的 pin 保住。
5. **`FIX3.md` §1.5 遗留**：`composerCeiling` 的 `coerceAtLeast(...)` 仍可能在理论上被抬回
   band 之上；v100/v101 的宿主上限已使其不再是可观测缺陷路径，**仍未单独加 pin**（改它会动
   `SessionPaneArrangementV76Test` 的既有口径，超出本轮授权）。
6. **`REPORT_U1D` §9.5**：composer 3 行档（`card=116`）的设备读数仍未取到。

---

## 8. 回报

**状态：DONE（JVM 已闭环 · 真机待复验）** —— 裁定为**方案 B（显式声明 + pin 阈值与公式）**，
并顺带落地了方案 A 在授权范围内的唯一杠杆（地板对测量期真实 pane 求值）。

- **51px 的成因（一句话 + 算式）**：**`readable = 51` 就是 `pane` 本身**——
  `pane = 600 − 162(header) − 387(believed IME) = 51`，
  `floor = min(64, 51) = 51`（让位生效）、`hostCeiling = 51 − 51 = 0`（composer 先让路）、
  `bar = 0`（`51 − 64 − 56 < 50`，整条不画）、`timeline = max(51 − 0, 51) = 51`，
  `51 + 0 + 0 = 51 = pane` —— **地板没被吃、没失效，是 pane 装不下 64**。
- **选 A 还是 B，为什么**：**B**。`readable` 不能大于 `pane`，在 `pane = 51` 时
  「`readable ≥ 64`」几何上不可能（只能画到 pane 之外 ⇒ 被裁 ⇒ 假过）；
  按比例下调地板只会把 51 变成 47，**让 pin 去保护一个不存在的数**。
  真正的缺陷是让位**此前是沉默的**，所以我把阈值/公式写成**命名常量 + 谓词 + 144 格三维 pin**；
  同时把 A 唯一在权内的杠杆（地板只喂陈旧 `paneHeight`）修掉了。
- **改了哪些文件**：
  `app/src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionPaneLayout.kt`（地板显式两分支 +
  `TIMELINE_FLOOR_YIELD_PANE` / `timelineFloorGuaranteed` / `timelineViewportFloorPx` /
  `timelineReadableFloorPx`）；
  `app/src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt`
  （measure policy 的 `floorPx = maxOf(declaredFloorPx, measuredFloorPx)`，其余调用逐字未动）；
  新增 `app/src/test/java/com/xiufeigo/opencodeshell/QueueTimelineFloorV101Test.kt`；
  加严 `app/src/test/java/com/xiufeigo/opencodeshell/QueueTimelineFloorV100Test.kt`（+2 例，既有 6 例未动）。
- **命令与用例数**：`.\gradlew.bat :app:testDebugUnitTest --tests "…QueueTimelineFloorV101Test"
  --tests "…QueueTimelineFloorV100Test" --tests "…QueueTimelineSpaceV99Test" --tests "…SessionPane*"
  --tests "…PaneLayout*" --tests "…Queue*" --tests "…Composer*" --tests "…Wave23a*"
  --tests "…StringsLocaleParityTest"` ⇒ **BUILD SUCCESSFUL in 10s**，
  **47 个类 / 427 例 / 0 失败 / 0 错误 / 0 跳过**（v101 5 例、v100 8 例、v99 **17 例逐字未动**）。
- **报告路径**：`research\v97_fix\QUEUE_TIMELINE_SPACE_FIX4.md`
