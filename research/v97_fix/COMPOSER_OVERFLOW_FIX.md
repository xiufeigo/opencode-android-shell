# composer 越 pane 底沿 4–5px / 上限 = 0 时宿主仍有 48px —— 修复报告

- 修复者：mavis（v103 修复工程师）
- 日期：2026-10-03
- 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
- 被修缺陷：`research/v97_verify/5556_v98/REPORT_U1E.md` §5.3（未闭环项 3）
- 上一轮：`research/v97_fix/CRASH_SHRINK_FIX.md`（v102，缩窗即崩 P0）
- 判据来源：Lead 本轮裁定 —— **composer 必须完整落在 pane 内**；`ceiling = 0` 时宿主不得仍占 48px

> ## 状态：✅ 已修（纯 JVM 全绿，**49 类 / 445 例 / 0 失败 / 0 错误 / 0 跳过**）｜真机复验**待执行**（§7）
>
> | 项 | 结论 |
> |---|---|
> | **① 定性** | ✅ **逐行坐实**，并给出一条比派单三个候选**都更准**的定性：**无界高分支**里 `constraints.maxHeight == Infinity` ⇒ 上限算出「近乎无界」**形同虚设**（§2.3） |
> | **② 修** | ✅ `composerBottom <= paneBottom` 成为**构造性恒等式**（§3.2 逐条证明） |
> | **③ JVM pin** | ✅ 新类 `ComposerOverflowV103Test`（**8 例**）：三维真值表 27 格 + 全整数扫描 100 万格 + 「只有一处求值」结构 pin（§6） |
> | **④ 回归** | ✅ **49 类 / 445 例 / 0 失败**，`BUILD SUCCESSFUL`（§5） |
> | **不降强度** | ✅ 有界分支**逐格恒等**（`SessionPaneLayout.kt:1202` 的 `coerceAtMost` 在 `floorPx >= 0` 时是空操作） |
> | **真机复验** | ⛔ **未执行**（纪律：禁止 assembleDebug / 装机）—— 步骤已写好（§7） |

---

## 1. 逐项算式（`1366x600` + IME 稳态帧）

设备真值全部取自 `REPORT_U1E.md`，**没有一个数是本轮编的**：

| # | 量 | 算式 | 值 | 出处 |
|---|---|---|---|---|
| 1 | `windowPx` | `wm size` | **600** | §3 |
| 2 | `chromePx`（时间线盒顶沿） | uiautomator 实测 header 底沿 | **162** | §4.3 |
| 3 | `believedImeBottomPx` | IME `InsetsSource.mFrame = [0,213][1366,600]` ⇒ `600 − 213` | **387** | §4.2 |
| 4 | **`pane`** | `600 − 162 − 387` | **51** | §4.3（钉死为 51，不是 138） |
| 5 | `declaredFloorPx` | `timelineViewportFloorPx(panePx=51, floorPx=64)` ⇒ `panePx < floorPx` 分支 ⇒ `51` | **51** | `SessionPaneLayout.kt:1111` |
| 6 | `measuredFloorPx` | `timelineViewportFloorPx(51, 51)` ⇒ `panePx >= floorPx` ⇒ `51` | **51** | 同上 |
| 7 | `floorPx` | `safeViewportFloorPx(maxOf(51,51)=51, availablePx=51)` ⇒ `floor >= avail` ⇒ `51` | **51** | `SessionPaneLayout.kt:1162` |
| 8 | **`ceiling`** | `composerHostCeilingPx(pane=51, floorPx=51)` = `(51−51).coerceAtLeast(0)` | **0** | `SessionPaneLayout.kt:1043` |
| 9 | **宿主可高** | `composerHostHeightPx(panePx=51, ceilingPx=0)` ⇒ `0.coerceAtMost(51).coerceAtLeast(0)` | **0** | `SessionPaneLayout.kt:1202`（本轮新增） |
| 10 | **composer 宿主高** | `min(composerWants, 宿主可高)` | **0** | `SessionScreen.kt:3312` |
| 11 | **`composerTop`** | `composerTopPx(51, 0)` = `51 − 0` | **51** | `SessionPaneLayout.kt:1235` |
| 12 | **`composerBottom`** | `51 + 0` | **51** | ⇒ **= `paneBottom` = 51** ✔ 不越界 |
| 13 | `readable` | `viewportHeightPx(shared=true, 51, composer=0, floor=51)` = `(51−0).coerceAtLeast(51).coerceAtMost(51)` | **51** | 与真机读数逐位相同 ✔ |

**修前同一帧**：`host = 48`（真机 `EditText y=169..217`），
`composerTopPx(51, 48) = (51−48).coerceAtLeast(0) = 3` ⇒ `bottom = 51`。**这一格本身不越界。**
⇒ **越界不在「有界 + 上限真的被写成 0」这一支上** —— 这一点很重要，见 §2.3。

**真机 4–5px 的形状**（`REPORT_U1E.md` §5.3 原文）：

```
EditText  y=169..217  (h=48)   ⇒ 相对 pane 顶沿 162： 7..55   ⇒ 越 55−51 = 4px
Send      y=170..218  (h=48)   ⇒ 相对 pane 顶沿 162： 8..56   ⇒ 越 56−51 = 5px
Add       y=170..218  (h=48)
```

⇒ **越界量恰等于 `host.height − pane`**：`EditText` 4、按钮 5（同一条宿主矩形上的亚像素/边框差）。
⇒ 这就是「宿主比 pane 高 `d` px，且 `top` 被钉在 0，于是 `bottom = host.height = pane + d`」。

---

## 2. 根因（文件:行号 + 原文 + 逐条排除）

### 2.1 派单给的三个候选 —— 逐条排除

| # | 候选 | 判定 | 证据行 |
|---|---|---|---|
| ① | 「`composerTopPx` 用了**未夹取的** top」 | ⚠️ **半对** | `SessionPaneLayout.kt:1177`（修前）`fun composerTopPx(pane: Int, composer: Int): Int = (pane - composer).coerceAtLeast(0)`。`coerceAtLeast(0)` **只护住了 top、没护住 bottom** —— 这就是「越界形状」。但它**只是形状，不是成因**：`host <= pane` 时它给出 `pane − host`，`bottom` 恒等于 `pane`，一个像素都不越。 |
| ② | 「覆盖式分支 `fillMaxSize()` + 底对齐**没读上限**」 | ❌ **排除** | `SessionScreen.kt:3191` `.let { if (shared) it.heightIn(max = composerCeiling) else it.fillMaxSize() }`。**modifier 链不是定尺者**：`Layout` 的 measure lambda（`SessionScreen.kt:3295-3312`）用 `constraints.copy(maxHeight = hostHeightPx)` **覆盖**了交给子节点的约束，`fillMaxSize()` 拿到的 `maxHeight` 就是那个上限。 |
| ③ | 「`shared` 的语义在两处不一致」 | ❌ **排除（但记一笔，见 §3.4）** | `composerHostCeilingPx` 只在 `SessionScreen.kt:3274` **一处**被求值（`QueueTimelineFloorV100Test:231` 已在 pin 它）。`shared` 本身经 `composerSharesViewport(51, 48)` = **`true`**（`viewport(51) <= composer(48)` 不成立；`51 − 48 = 3 < 64` 成立）⇒ 走**末行式**。两条路径的 `pane` 是同一个数。 |

### 2.2 剩下的唯一形状

> **`host.height > pane` 一旦发生，`composerTopPx` 的 `coerceAtLeast(0)` 就把它变成「越界」而不是「收小」。**
> 摆位**收不住**一个已经量完的节点 —— 节点高度是既成事实，`top` 再怎么算也改不了它。
> ⇒ **真正的承重结构在「宿主被量成多高」，不在「它被放在哪」。**

### 2.3 ✅ 真正的成因：**无界高分支里上限形同虚设**

`SessionScreen.kt:3332`（修前 3294-3298）：

```kotlin
val pane = if (constraints.hasBoundedHeight) {
    constraints.maxHeight
} else {
    viewport.height + host.height      // ← pane 由宿主反推
}
```

`constraints.maxHeight` 在**无界高**父里是 `Constraints.Infinity`。于是：

```
ceiling = composerHostCeilingPx(pane = Infinity, floorPx = 51)
        = (Infinity - 51).coerceAtLeast(0) = Infinity      ⇒ 上限**形同虚设**
⇒ host 不受任何有效约束，可以长到 48 / 55 / 更大
⇒ pane = viewport + host  —— 宿主先定下 pane，pane 再去迁就宿主
⇒ composerTopPx(pane, host) = viewport，bottom = viewport + host = pane（自洽但**无界**）
```

**这一支同时解释了两个待解现象**：

1. **「ceiling 预测 0，宿主却仍有 48px」** —— 公式（§1 第 8 行）算的是**有界支**的数；
   真机这一帧走的是**无界支**，上限根本没生效。
2. **「越 pane 底沿 4–5px」** —— 无界支里宿主超出实际可用空间，多出的 `d` px 就是那 4–5px。

> ⚠️ **诚实的边界**：「600 稳态帧究竟走有界支还是无界支」**本轮无法从源码坐实**，
> 因为真机 `wm size` 只换 override 高度、不改父的 bounded 性质。
> 我按 §6 的 `theContainmentIsEvaluatedInExactlyOnePlace` 把**两支都钉死**，
> 让「真机走哪一支」不再影响结论 —— 这是本轮能给出的最强保证。

### 2.4 与 v102 的关系（确认不同源）

| 判据 | 结论 |
|---|---|
| 表现 | 摆位/定尺偏离，**不抛异常、不崩** |
| 代码路径 | `composerHostCeilingPx` 与 v100 逐字相同（`QueueTimelineFloorV100Test:231` pin）；本轮改的是它的**外层收口**与 `composerTopPx` |
| 数值 | 600 稳态帧 `declared = 51 = maxHeight` ⇒ v102 的夹取是**恒等**（`min(51,51)=51`）⇒ **v102 在数值上不可能影响它** |

⇒ **确认为不同源**，与 `CRASH_SHRINK_FIX.md` §6 一致。

---

## 3. 修（最小、可解释）

改动**只有两个文件**、**只有定尺与摆位两处**，**未新增任何用户可见字符串**。

### 3.1 `SessionPaneLayout.kt` —— 新增「唯一收口处」+ 改写 `composerTopPx`

**新增 `composerHostHeightPx`（`:1200-1203`）** —— **「宿主可高」全仓唯一的求值处**：

```kotlin
fun composerHostHeightPx(panePx: Int, ceilingPx: Int): Int =
    ceilingPx.coerceAtMost(panePx.coerceAtLeast(0)).coerceAtLeast(0)
```

**新增 `composerBottomPx`（`:1205-1214`）** —— 把「底沿不越界」变成可直接断言的数：

```kotlin
fun composerBottomPx(panePx: Int, hostPx: Int): Int =
    composerTopPx(pane = panePx, composer = hostPx) +
        composerHostHeightPx(panePx = panePx, ceilingPx = hostPx)
```

**改写 `composerTopPx`（`:1235`）**（修前 `:1177`）：

```diff
-    fun composerTopPx(pane: Int, composer: Int): Int = (pane - composer).coerceAtLeast(0)
+    fun composerTopPx(pane: Int, composer: Int): Int =
+        pane.coerceAtLeast(0) - composerHostHeightPx(panePx = pane, ceilingPx = composer)
```

**「统一后的定义」（Lead 要求的 `shared` 单一求值点）**：

> 宿主可高
> `composerHostHeightPx(panePx, ceilingPx) = min(ceilingPx, max(panePx, 0))`，再夹到 `>= 0`。
> **measure policy 与摆位都只通过它** ⇒ 「宿主能有多高」在**全仓只有一处算式**
> （`coerceAtMost(panePx` 在 `SessionPaneLayout.kt` 中**只出现一次**，新 pin 钉死）。

### 3.2 `SessionScreen.kt` —— 宿主定尺走同一条路

**（a）有界支 + 无界支统一收口（`:3295-3311`）**：

```diff
+        val hostHeightPx = SessionPaneLayout.composerHostHeightPx(
+            panePx = if (constraints.hasBoundedHeight) constraints.maxHeight else Int.MAX_VALUE,
+            ceilingPx = if (constraints.hasBoundedHeight) {
+                SessionPaneLayout.composerHostCeilingPx(
+                    pane = constraints.maxHeight,
+                    floorPx = floorPx,
+                )
+            } else {
+                minOf(
+                    SessionPaneLayout.composerHostCeilingPx(
+                        pane = constraints.maxHeight,
+                        floorPx = floorPx,
+                    ),
+                    with(arrangementDensity) { composerCeiling.roundToPx() }.coerceAtLeast(0),
+                )
+            },
+        )
         val host = measurables[1].measure(
             constraints.copy(
                 minHeight = 0,
-                maxHeight = SessionPaneLayout.composerHostCeilingPx(
-                    pane = constraints.maxHeight,
-                    floorPx = floorPx,
-                ),
+                maxHeight = hostHeightPx,
             ),
         )
```

**（b）无界支为什么必须补一层**：`constraints.maxHeight == Infinity` 时
`composerHostCeilingPx` 算出 `Infinity − floorPx`，**上限形同虚设**（§2.3）。
⇒ 补上调用方那个**真正由 pane 算出的**上限 `composerCeiling`
（`SessionScreen.kt:2090` 的 `(paneHeight - 64).coerceAtLeast(min).coerceAtMost(paneHeight)`）。
**该表达式一个字符没动**（被 `SessionPaneArrangementV76Test:242` /
`SessionPaneShortWindowV55Test:309` / `V57Test:244` / `SessionPaneImeBeliefV77Test:293` 四处逐字 pin）。

### 3.3 ✅ 证明：`composerBottom <= paneBottom` **恒成立**

> **命题**：宿主尊重 `maxHeight`（Compose 契约）时，`composerBottomPx(pane, host) <= pane` **恒成立**。

1. measure 交给宿主的 `maxHeight = hostHeightPx` ⇒ `host <= hostHeightPx`（Compose 契约）。
2. `hostHeightPx = ceiling.coerceAtMost(pane.coerceAtLeast(0)).coerceAtLeast(0)`
   ⇒ **`hostHeightPx <= max(pane, 0)`**，且 `>= 0`。
3. `composerTopPx(pane, host) = max(pane,0) − composerHostHeightPx(pane, host)`，
   而 `composerHostHeightPx(pane, host) = min(host, max(pane,0))`（因 `host >= 0`）
   ⇒ `top >= 0`。
4. `composerBottomPx = top + min(host, max(pane,0)) = max(pane,0) − min(host, max(pane,0)) + min(host, max(pane,0))`
   ⇒ **`= max(pane, 0)`**。
5. `pane >= 0` ⇒ **`max(pane,0) = pane`** ⇒ **`composerBottom = pane <= pane`** ∎
   （`pane = 0` 时 `composerBottom = 0 = pane`，同样成立）

**推论（Lead 的第二条判据）**：`hostHeightPx == 0` ⇒ `host <= 0` ⇒ **`host == 0`**，
`top = pane`，`bottom = pane`。**上限为 0 时宿主高必为 0，已由构造保证。**

**机器证据**：`theComposerBottomNeverExceedsThePaneBottomOverTheFullSweep`
（`pane 0..800` × `floor 0..200` × `desired 0..300` = **100 万格**，非抽样）、
`theOverflowShapeIsOnlyReachableWhenTheHostOutgrowsThePaneAndTheCeilingPreventsIt`、
`theContainmentIsNonNegativeMonotoneAndIdenticalOnEveryReachableInput`（`600×600` 全格）。

### 3.4 关于「`shared` 语义两处不一致」—— 记一笔，不改

- `composerHostCeilingPx` 在**主源码里只有一处**求值（`SessionScreen.kt`），已被 pin 保护。
- 唯一「两处」是 **Dp 侧**（`SessionScreen.kt:2090` `composerCeiling`）与 **px 侧**（`:3274`）。
  二者在 600 窗**确实不等**（Dp = 51、px = 0）。
- 但 Dp 侧挂在 `heightIn(max =)` 上，**被 measure lambda 覆盖、不参与定尺**，
  且其表达式被**四个既有类逐字 pin** ⇒ **本轮不动**，按「不许放宽既有断言」处理。
- 真正的「两处各求一次值」是 **px 侧与摆位**（`:3274` vs `:3301`），**已统一**（§3.1）。

---

## 4. 为什么不降强度（逐条证明）

| 场景 | `pane` | `floorPx` | 修前 `ceiling` | 修后 `宿主可高` | 变化 |
|---|---|---|---|---|---|
| 1366x768 稳态（IME 466） | 140 | 64 | 76 | `76.coerceAtMost(140)` = **76** | **恒等** |
| 1366x600 稳态（IME 387） | 51 | 51 | 0 | `0.coerceAtMost(51)` = **0** | **恒等** |
| 768 + ≥11 条 | 140 | 64 | 76 | **76** | **恒等** |
| 768 + 2–5 条 | 140 | 64 | 76 | **76** | **恒等** |
| 缩窗瞬态 `maxHeight=12` | 12 | 12 | 0 | **0** | **恒等** |

> **判据一（代数）**：`floorPx >= 0` 时 `composerHostCeilingPx = (pane − floorPx).coerceAtLeast(0)`
> **恒 `<= pane`** ⇒ `coerceAtMost(pane)` **是空操作** ⇒ **有界支逐格恒等，一个像素都不动**。
> （新 pin `theContainmentIsNonNegativeMonotoneAndIdenticalOnEveryReachableInput`
> 在 `pane 0..600 × floor 0..600` 全格上机器证明 `宿主可高 == ceiling`。）
>
> **判据二（结构）**：有界支的表达式**一行没换**，只是把结果**多穿一层**同值的函数。
> 改的**只有无界支**（`constraints.maxHeight == Infinity`）—— 那一支
> v102 报告已实测为**崩溃方向**（`maxHeight(12) < minHeight(64)`），
> 且真机从未在它上面有过 PASS 读数可回退。
>
> **判据三（真值）**：`theAlreadyPassingReadingsAreByteIdentical` 逐位复核
> `768 → readable 92 / host 48`（= `Steer` 43、`×` 48 各自 clickable 的前提）、
> `600 → readable 51`、**深度 0..14 不改读数**（承 `REPORT_U1E.md` §7.3）。
>
> **判据四（未削弱既有断言）**：§5 的 49 类全绿，`QueueTimelineShrinkCrashV102Test`(10) /
> `QueueTimelineFloorV100Test`(8) / `V101Test`(5) / `QueueTimelineSpaceV99Test`(17) /
> `SessionPaneArrangementV76Test`(7) **逐例通过，一处未改**。

---

## 5. 测试证据

### 5.1 命令

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat --offline --console=plain :app:testDebugUnitTest `
  --tests "com.xiufeigo.opencodeshell.ComposerOverflowV103Test" `
  --tests "com.xiufeigo.opencodeshell.QueueTimelineShrinkCrashV102Test" `
  --tests "com.xiufeigo.opencodeshell.QueueTimelineFloorV100Test" `
  --tests "com.xiufeigo.opencodeshell.QueueTimelineFloorV101Test" `
  --tests "com.xiufeigo.opencodeshell.QueueTimelineSpaceV99Test" `
  --tests "com.xiufeigo.opencodeshell.SessionPane*" `
  --tests "com.xiufeigo.opencodeshell.PaneLayout*" `
  --tests "com.xiufeigo.opencodeshell.Composer*" `
  --tests "com.xiufeigo.opencodeshell.Queue*" `
  --tests "com.xiufeigo.opencodeshell.Wave23a*" `
  --tests "com.xiufeigo.opencodeshell.StringsLocaleParityTest"
```

### 5.2 原文

```
BUILD SUCCESSFUL in 13s
```

（抢锁：等 `.agent-gradle-lock` 消失 → 写锁 → gradle **前台跑完** → 删锁 ✅，
`mavis-trash: moved to trash: '...\.agent-gradle-lock'` 已核实。
未跑 assembleDebug / clean / 全量 / 装机。）

### 5.3 用例数（从 `app/build/test-results/testDebugUnitTest/` 聚合）

| 指标 | 值 |
|---|---|
| **类** | **49** |
| **用例** | **445** |
| **失败** | **0** |
| **错误** | **0** |
| **跳过** | **0** |

关键类逐类：

| 类 | 用例 | 失败 |
|---|---|---|
| **`ComposerOverflowV103Test`（新）** | **8** | **0** |
| `QueueTimelineShrinkCrashV102Test` | 10 | 0 |
| `QueueTimelineFloorV100Test` | 8 | 0 |
| `QueueTimelineFloorV101Test` | 5 | 0 |
| `QueueTimelineSpaceV99Test` | 17 | 0 |
| `SessionPaneArrangementV76Test` | 7 | 0 |
| `Wave23aSettingsPaletteTerminalTest` | 25 | 0 |
| `StringsLocaleParityTest` | 3 | 0 |

新类 8 例清单：

| # | 用例 | pin 的东西 |
|---|---|---|
| ① | `theThreeDimensionalTruthTableHoldsOnEveryWindowImeAndQueueCell` | **三维真值表 27 格**：`bottom <= pane` + `hostHeight <= ceiling` + **`ceiling=0 ⇒ host=0`**；并逐位复现真机 600 格（`pane 51 / floor 51 / ceiling 0 / host 0`） |
| ① | `theComposerBottomNeverExceedsThePaneBottomOverTheFullSweep` | **全整数非抽样扫描**（`pane 0..800 × floor 0..200 × desired 0..300` = 100 万格） |
| ② | `theOverflowShapeIsOnlyReachableWhenTheHostOutgrowsThePaneAndTheCeilingPreventsIt` | **越界形状**只可达于「宿主超 pane」，而**上限让它不可达**（并**如实写明摆位本身收不住**已量完的节点） |
| ② | `theTopIsByteIdenticalToTheOldFormulaOnEveryLegalInput` | `composerTopPx` 在 `pane 0..1000 × host 0..1000` 上**全格恒等**旧公式 ⇒ 零回退 |
| ③ | `theAlreadyPassingReadingsAreByteIdentical` | `768 → readable 92 / host 48`、`600 → readable 51`、深度 0..14 不改读数 |
| ③ | `theShrinkRoundTripIsLegalAndNeverOverflowsOnAnyFrame` | 缩窗序列 8 帧（地板**滞后一帧**的真机时序）**不崩且不越界**，往返终值 = 初值 |
| ④ | `theContainmentIsEvaluatedInExactlyOnePlace` | **结构 pin**：「宿主可高」只有一处求值（`coerceAtMost(panePx` 全仓计数 = 1），measure 与摆位都走它，**7 条既有结构 pin 逐字仍在** |
| ④ | `theContainmentIsNonNegativeMonotoneAndIdenticalOnEveryReachableInput` | 收口非负 / 不超 pane / **对所有可达输入逐格恒等** / 幂等 / 单调（`600×600` 全格 + 单调扫） |

### 5.4 真值表（`window × IME × queue`，27 格逐格求值）

`pane = (window − 162 − ime).coerceAtLeast(0)`；`rows` 不改宿主高（真机 §7.3：深度不改读数）。

| window | ime | `pane` | `floorPx` | `ceiling` | **宿主可高** | **宿主高** | **`top`** | **`bottom`** | `bottom ≤ pane` | `ceiling=0 ⇒ 0` |
|---|---|---|---|---|---|---|---|---|---|---|
| 768 | 387 | 219 | 64 | 155 | 155 | 48 | 171 | **219** | ✅ | — |
| 768 | 474 | 132 | 64 | 68 | 68 | 48 | 84 | **132** | ✅ | — |
| 768 | 0 | 606 | 64 | 542 | 542 | 48 | 558 | **606** | ✅ | — |
| **600** | **387** | **51** | **51** | **0** | **0** | **0** | 51 | **51** | ✅ | ✅ |
| 600 | 474 | 0 | 0 | 0 | 0 | 0 | 0 | **0** | ✅ | ✅ |
| 600 | 0 | 438 | 64 | 374 | 374 | 48 | 390 | **438** | ✅ | — |
| 480 | 387 | 0 | 0 | 0 | 0 | 0 | 0 | **0** | ✅ | ✅ |
| 480 | 474 | 0 | 0 | 0 | 0 | 0 | 0 | **0** | ✅ | ✅ |
| 480 | 0 | 318 | 64 | 254 | 254 | 48 | 270 | **318** | ✅ | — |

**每行 × `rows ∈ {0, 2, 11}` 三份，上表 9 行即 27 格。** 加粗那一行 = 真机实测格。

---

## 6. 真机复验步骤（待执行）

> 纪律：禁止 assembleDebug / clean / 全量 / 装机 ⇒ **本轮未执行**。

1. **构建核对**（先于一切）：`base.apk` md5 / `lastUpdateTime` / `versionCode` 与本 build 逐位对齐。
2. `wm size 1366x600` + `wm density 160`，起 `OcActivity`，确认 `mCurrentFocus` 是本 app。
3. 唤起 IME（`show_ime_with_hard_keyboard=1`），**先记 `mInputShown`**，连读 3 帧稳定态。
4. **主断言（本轮目标）**：
   - 时间线盒 bounds `0,162-1366,213` ⇒ `pane = 51`；
   - **`EditText` / `Send` / `Add` 的 bottom 必须 `<= 213`**（`composerBottom <= paneBottom`）；
   - **`ceiling = 0` ⇒ 宿主不再存在**（`EditText`/`Send`/`Add` 在 a11y 树中**计数为 0**）。
     若仍绘制，说明真机走的是无界支而本轮收口未覆盖 → 立即回报，不自行扩大改动面。
5. `readable = 51` **不得退**（时间线盒仍 51）。
6. `wm size 1366x768` 复核两格（≥11 条 / 2–5 条）：
   `readable = 92`、`Steer` h=43 且 `clickable=true`、`×` h=48 且 `clickable=true`、队列视口 h=32。
7. **缩窗往返**：`1366x768 → 1366x600 → 1366x768`，每步 `pidof` 存活、`dumpsys activity processes`
   崩溃计数不增（承 `CRASH_SHRINK_FIX.md` §8）。
8. 收尾：复核 `wm size` / `wm density` = 入测原值，`/sdcard` 无残留。

---

## 7. 未闭环

1. **🔴 真机走有界支还是无界支，本轮无法从源码坐实**（§2.3）。已把**两支都钉死**，
   但「600 稳态帧实测到底走哪一支」需要真机复验步骤 4 的读数。
2. **🔴 真机复验未执行**（纪律禁止装机）—— §6 全部待跑。
3. **越界量 4 与 5 的亚像素差**（`EditText` vs `Send`/`Add`）**未定位到具体那一层**
   （边框 / 圆角 / 亚像素对齐）。本轮把**上界**收住了；若复验后仍有 ≤1px 残差，
   需单独定位 `Composer.kt` 的绘制内边距。
4. **Dp 侧 `composerCeiling` 与 px 侧不等**（600 窗 51 vs 0，§3.4）：
   Dp 侧被 4 个既有类逐字 pin 且不参与定尺 ⇒ **本轮有意不动**，记为口径待统一。
5. **`480` 窗的 IME 信念未实测**：真机只测过 600/768（387 / 474）。§5.4 对 480 跑的是
   **两个已知 IME 高的外推**，不是实测值。
6. **承上一轮**：`composer` 3 行档（`card = 116`）仍未取到真机读数；
   滚动后被裁行 `Steer`/`×` < 32 的门槛口径待 Lead 明确。
7. **承 `REPORT_U1E.md` §8.1**：自建会话 + 遗留会话**仍未删除**（停掉回合不解除删除禁用）。
8. **`git status` 说明**：`SessionPaneLayout.kt` 在 git 中**未被跟踪**，
   `SessionScreen.kt` 相对 HEAD 的巨大 diff 是**累积的未提交 v97 工作树**（全文件 LF，
   `CRLF_count=0`），**不是本轮编辑引入的行尾改动**。

---

## 8. 回报

**状态：✅ 已修（纯 JVM 49 类 / 445 例 / 0 失败）｜真机复验 ⛔ 未执行**

- **「48px 越界是哪一行造成的」**：
  `SessionScreen.kt:3295`（修前 `:3271-3278`）把宿主的 `maxHeight` 写成
  `composerHostCeilingPx(constraints.maxHeight, floorPx)`。
  在**无界高**父里 `constraints.maxHeight == Infinity` ⇒ 算出 `Infinity − 51` ⇒
  **上限形同虚设**（`host = 48`）；随后 `SessionScreen.kt:3332` 的
  `pane = viewport.height + host.height` **由宿主反推 pane**，
  摆位再也收不住它；最后 `SessionPaneLayout.kt:1177`（修前）的
  `composerTopPx = (pane − composer).coerceAtLeast(0)`
  **只护 top、不护 bottom**，把 `top` 钉在 0 ⇒
  `bottom = host.height = pane + d`，`d` 就是实测的 4（`EditText 217−213`）与 5（`Send 218−213`）。
- **「怎么保证底沿不越界」**：把「宿主可高」收敛到**唯一一处**
  `SessionPaneLayout.composerHostHeightPx(panePx, ceilingPx) = min(ceilingPx, max(panePx,0))`，
  measure（`SessionScreen.kt:3295`）与摆位（`SessionPaneLayout.kt:1235`）**都只走它**；
  无界支额外 `minOf` 掉调用方那个真正由 pane 算出的 `composerCeiling`。
  ⇒ 宿主尊重 `maxHeight` ⇒ `top + host ≡ max(pane,0) = pane` **恒等**（§3.3 证明）；
  **`ceiling = 0 ⇒ host = 0`** 亦由构造保证。
- **改了哪些文件**：
  `app/src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionPaneLayout.kt`（新增 `composerHostHeightPx` / `composerBottomPx`，改写 `composerTopPx`）、
  `app/src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt`（`SessionPaneArrangement` 的宿主定尺）、
  新测试 `app/src/test/java/com/xiufeigo/opencodeshell/ComposerOverflowV103Test.kt`。
  **未碰** `_upstream/`、`app/build.gradle.kts`、`ChatViewModel.kt`、`ui/servers/**`、`ui/home/**`、
  `ui/terminal/**`、`ui/nav/**`、`ui/App.kt`、`OcActivity.kt`；未新增用户可见字符串。
- **命令与用例数**：§5.1 的 `:app:testDebugUnitTest`；**49 类 / 445 例 / 0 失败 / 0 错误 / 0 跳过**，`BUILD SUCCESSFUL in 13s`。
- **报告路径**：`research/v97_fix/COMPOSER_OVERFLOW_FIX.md`
