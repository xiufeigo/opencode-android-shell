# 缩窗即崩 P0 修复报告 — `maxHeight(12) must be >= minHeight(64)`

- 修复者：mavis（v102 P0 修复工程师）
- 日期：2026-10-03
- 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
- 被修缺陷：`research/v97_verify/5556_v98/REPORT_U1E.md` §6.2 / §9.1（四源互证的真机崩溃）
- 上一轮：`research/v97_fix/QUEUE_TIMELINE_SPACE_FIX4.md`（v101）

> ## 状态：✅ 已修（纯 JVM 全绿，437 例 0 失败）｜真机复验**待执行**（步骤见 §8）
>
> | 项 | 结论 |
> |---|---|
> | **① 因果链** | ✅ **逐行坐实**，并给出一条比派单假设更强的定性：`maxOf` **可证是恒等** |
> | **② 修** | ✅ `minHeight > maxHeight` **不可能再出现**（逐条证明见 §5） |
> | **③ JVM pin** | ✅ 新类 `QueueTimelineShrinkCrashV102Test`（10 例）+ 三个既有 floor/pane 类**只加严**、逐字未削弱 |
> | **④ 回归** | ✅ **48 类 / 437 例 / 0 失败 / 0 跳过**，`BUILD SUCCESSFUL` |
> | **composer 越 pane 底沿 4–5px** | ⚠️ **不同根因**，本轮**不改**，给出最小改动面（§6） |
> | **真机复验** | ⛔ **未执行**（纪律：禁止 assembleDebug/装机）—— 步骤已写好（§8） |

---

## 1. 崩溃因果链（逐行坐实）

### 1.1 抛出点

```
java.lang.IllegalArgumentException: maxHeight(12) must be >= minHeight(64)
    at androidx.compose.ui.unit.Constraints.copy(Constraints.kt:759)
    at com.xiufeigo.opencodeshell.ui.session.SessionScreenKt$SessionPaneArrangement$2$1.measure-3p2s80s(SessionScreen.kt:3250)
```

`Constraints` 是 value class，`copy()` 走构造函数，`checkPrecondition(maxHeight >= minHeight)` 在
**构造那一刻**就抛 —— 所以栈顶是 `SessionScreen.kt` 里**那一行 `constraints.copy(`**，
而不是它内部的某个参数。**修前的 `SessionScreen.kt:3250` 正是 `val viewportConstraints = constraints.copy(`**，
真正的 `minHeight = floorPx` 在同一表达式的下一行（修前 3255）。

### 1.2 逐行链条

| # | 位置（修前行号） | 原文 | 作用 |
|---|---|---|---|
| 1 | `SessionScreen.kt:2102` | `.onSizeChanged { paneHeightPx = it.height }` | **地板的来源**。`paneHeight` 是一个由本回调写入的 `Dp` state |
| 2 | `SessionScreen.kt:2108` | `timelineFloor = SessionPaneLayout.timelineViewportFloor(paneHeight)` | 组合期把 `paneHeight` 求值成地板 |
| 3 | `SessionScreen.kt:3217-3218` | `val declaredFloorPx = with(arrangementDensity) { timelineFloor.roundToPx() }.coerceAtLeast(0)` | `declaredFloorPx = 64`（缩窗**之前**那一帧的读数） |
| 4 | `SessionScreen.kt:3235-3238` | `val measuredFloorPx = SessionPaneLayout.timelineViewportFloorPx(panePx = constraints.maxHeight, floorPx = declaredFloorPx)` | 测量期重新求值 = **12** |
| 5 | `SessionScreen.kt:3240` | `val floorPx = maxOf(declaredFloorPx, measuredFloorPx)` | **= 64**（不是 12） |
| 6 | `SessionScreen.kt:3250-3262` | `constraints.copy(minHeight = floorPx, maxHeight = SessionPaneLayout.viewportHeightPx(...))` | `min = 64`，`max = 12` ⇒ **抛异常** |

### 1.3 `floorPx` 的**求值时机** —— 根因是「两个数字来自两个时刻」

> **这是整条链的关键，也是派单里没有点透的那一层。**
>
> - `constraints.maxHeight` = **这一帧真实**的 pane 高度。
> - `timelineFloor` ← `paneHeight` ← `paneHeightPx` state ← **`onSizeChanged` 回调**。
>   `onSizeChanged` 是在**布局完成之后**才回调的，它写回的 state **最早只能在下一帧**生效。
>
> ⇒ **`maxHeight` 是本帧的真值，`declaredFloorPx` 是上一帧的残值。** 缩窗时两者不同步，
> 于是出现 `maxHeight = 12`（新）配 `floorPx = 64`（旧）的那一帧。
> 异常就发生在这个错位窗口里。

### 1.4 `maxOf(declared, measured)` 的**语义** —— **它可证是恒等，救不了这一帧**

`SessionPaneLayout.kt:1111-1116`：

```kotlin
fun timelineViewportFloorPx(panePx: Int, floorPx: Int): Int = when {
    panePx <= 0 -> 0            // 0 <= floorPx（floorPx 已 coerceAtLeast(0)）
    floorPx <= 0 -> 0           // 0 <= floorPx
    panePx >= floorPx -> floorPx // == floorPx
    else -> panePx              // panePx < floorPx
}
```

**四个分支的返回值恒 `<= floorPx`。** 代入 `floorPx = declaredFloorPx`：

> **`maxOf(declaredFloorPx, measuredFloorPx) ≡ declaredFloorPx`** —— 恒等，
> **它从不把地板调小**，因此**结构上不可能**救下瞬态帧。

这有三重意义：

1. **v101 的 `maxOf` 不是崩溃的引入者**（见 §1.6）。
2. **唯一合法位置只能是「约束落地处」**（夹在写进 `minHeight` 之前）。
3. 新 pin `theMaxOfMergeIsProvablyTheIdentityAndCouldNeverHaveSavedTheFrame`
   对 `pane 0..400 × declared {0,1,51,63,64,65,140}` 逐格证明这条恒等式 ——
   哪天它不成立了，测试会立刻报出来。

### 1.5 为什么**放窗不崩、缩窗崩**（瞬态顺序）

| 方向 | `maxHeight` 帧序列 | 与 `declaredFloorPx = 64` 的关系 | 结果 |
|---|---|---|---|
| **缩窗** 768→600 | 140 → 120 → 90 → 64 → 51 → 38 → 24 → **12** → 51(稳) | **穿过整个亚 64 区间**：`maxHeight < 64` 而 state 仍停在 64 | ❌ **必崩**（`maxHeight(12) < minHeight(64)`） |
| **放窗** 600→768 | 51 → 64 → 90 → 120 → 140 | 每一帧 `maxHeight >=` 终值，而终值 `>= 64` ⇒ **恒 `maxHeight >= 64 = minHeight`** | ✅ **存活** |

⇒ **决定变量是「窗口是否变矮」**，与 `wm density` 本身无关 —— 这与 `REPORT_U1E.md` §6.4
的实测完全一致（放窗方向同样改了 density，不崩）。
⇒ 同样的道理，**分屏 / 旋转 / IME 开关 / 多窗口折叠**只要产生一次「先塌到亚 64、
state 还没跟上」的帧，就同样崩 —— 这就是派单说的「任何缩窗 / 分屏 / 旋转都可能崩」。

### 1.6 为什么 v100 不崩 —— **高置信推断**（未回测，如实标注）

`REPORT_U1E.md` §6.4 说 v101 引入的 `maxOf` 是那一步。**从源码看这个说法要修正**：

- v100 没有 `maxOf`，地板 = `declaredFloorPx`；
- v101 有 `maxOf`，而 `maxOf ≡ declaredFloorPx`（§1.4）；
- ⇒ **v100 与 v101 在崩溃窗口里的 `floorPx` 逐字相同**，`minHeight` 表达式也逐字相同
  （`QueueTimelineFloorV100Test:239` pin 的就是 `minHeight = floorPx,`）。

**所以这不是一个可以由源码坐实的「v101 回归」**：两个版本在那一帧都会得到
`minHeight = 64 > maxHeight = 12`。v100 未崩**只能是时序侥幸** ——
那一瞬态帧是否出现，取决于 measure pass 与 `onSizeChanged` state 写回之间谁先赢，
而这是竞态，**本轮未回测 v100，故按派单要求记为高置信推断**。

> `REPORT_U1E.md` §6.4 末句「⚠️ 但本轮只测了 v101，未回测 v100，故「回归」为高置信推断、
> 非实锤」**与本节结论一致**；本节进一步指出：**即使回测 v100 成功，也不能证明 v100 结构上
> 没有这个窗口**，因为 §1.4 的恒等式表明两版取值相同。要坐实「谁引入的」，
> 需要在真机同一 build 上打点比较 `paneHeightPx` 与 `constraints.maxHeight` 的时序。

---

## 2. 改了什么（diff）

改动**只有两个文件**、**只有几何与约束落地两处**，没有新增任何用户可见字符串。

### 2.1 `SessionPaneLayout.kt` —— 新增一个纯函数

**`SessionPaneLayout.kt:1130-1166`（新增 `safeViewportFloorPx`）**

```diff
     fun timelineReadableFloorPx(panePx: Int, floorPx: Int): Int =
         timelineViewportFloorPx(panePx, floorPx)
+
+    /**
+     * v102 P0: the band **as a legal lower bound** — [floorPx] clamped into the
+     * space the pane can actually hand out, so a measure policy can never build
+     * a `Constraints` whose `minHeight` exceeds its `maxHeight`.
+     * ...
+     */
+    fun safeViewportFloorPx(floorPx: Int, availablePx: Int): Int = when {
+        availablePx <= 0 -> 0
+        floorPx <= 0 -> 0
+        floorPx >= availablePx -> availablePx
+        else -> floorPx
+    }
```

### 2.2 `SessionScreen.kt` —— 夹在约束落地之前

**`SessionScreen.kt:3267-3270`（`SessionPaneArrangement` 的 measure policy）**

```diff
         val measuredFloorPx = SessionPaneLayout.timelineViewportFloorPx(
             panePx = constraints.maxHeight,
             floorPx = declaredFloorPx,
         )
-        // The band every constraint below is charged with.
-        val floorPx = maxOf(declaredFloorPx, measuredFloorPx)
+        // v102 P0: the band every constraint below is charged with — **clamped
+        // into the space this frame can actually hand out** before it is written
+        // into a `Constraints`. …（完整论证见 SessionPaneLayout.safeViewportFloorPx）
+        val floorPx = SessionPaneLayout.safeViewportFloorPx(
+            floorPx = maxOf(declaredFloorPx, measuredFloorPx),
+            availablePx = constraints.maxHeight,
+        )
```

`minHeight = floorPx,`（**`SessionScreen.kt:3285`**）与
`maxHeight = SessionPaneLayout.viewportHost…viewportHeightPx(...)`（3286）**一行未动** ——
夹取发生在**变量求值**这一步，所以下游所有 `floorPx` 的用法自动继承安全性。

### 2.3 既有结构 pin 逐字仍在（机器核实）

`QueueTimelineFloorV100Test` / `V101Test` / `QueueTimelineSpaceV99Test` 全部 pin 了
measure policy 的**源码文本**。改动后逐条复核（`contains` 命中数）：

| 既有 pin 文本 | 归属 | 命中 |
|---|---|---|
| `SessionPaneLayout.timelineViewportFloorPx(` | V101 | ✅ 1 |
| `maxOf(declaredFloorPx, measuredFloorPx)` | V101 | ✅ 2 |
| `SessionPaneLayout.composerHostCeilingPx(` | V100 | ✅ 1 |
| `floorPx = floorPx,` | V100 | ✅ 2 |
| `minHeight = floorPx,` | V100 | ✅ 1 |
| `timelineFloor: Dp,` | V100 | ✅ 1 |
| `timelineFloor = SessionPaneLayout.timelineViewportFloor(paneHeight)` | V100 | ✅ 1 |
| `coerceAtLeast(floorPx)`（4 参 `viewportHeightPx` 内） | V100 | ✅ 未动 |

⇒ **没有任何既有断言被放宽、删除或改写。** 新 pin
`theClampIsInTheMeasurePolicyAndEveryOlderStructuralPinSurvives` 把这 7 条**反向钉进新类**，
将来谁动了口径会立刻红。

---

## 3. 修法为什么是「夹到可用空间」而不是别的

派单给了两个候选（「`min(floorPx, maxHeight)`」或「瞬态帧用 `declaredFloorPx`」）。
**第二个被否决，理由是可证的**：§1.4 表明瞬态帧的 `declaredFloorPx` 恰恰是**错误的那个**
（64，而本帧只有 12）。改用它不会崩，但会让地板在缩窗全程**恒为 64**，
在 12px 的 pane 上向 viewport 索要 64px —— 布局溢出，违反 v101 §2 的裁定
（「地板不得认领不存在的空间」）。**只有夹取同时满足「不抛」与「不溢出」。**

---

## 4. v101 语义零回退 —— 为什么 92px 与两格读数不可能退

| 场景 | `declared` | `maxHeight` | 修前 `floorPx` | 修后 `floorPx` | 是否变化 |
|---|---|---|---|---|---|
| 1366x768 稳态 | 64 | 140 | 64 | 64 | **恒等** |
| 1366x600 稳态 | 51 | 51 | 51 | 51 | **恒等** |
| 768 + 深队列 | 64 | 140 | 64 | 64 | **恒等** |
| 768 + 2–5 队列 | 64 | 140 | 64 | 64 | **恒等** |
| **缩窗瞬态** | 64 | **12** | 64 → ❌抛 | **12** | **变（这就是修的那一帧）** |

> **判据：夹取只可能改变 `declared > maxHeight` 的帧。**
> 而 `declared > maxHeight` 恰恰就是**崩溃的定义**（§1.2）—— 即
> **「夹取改变的那些帧，全都是修前会抛异常的帧」。**
>
> ⇒ 在**所有不会崩的帧上，夹取都是恒等**，所以 `readable` 一个像素都不会动：
> 1366x768 的 **92**（`(140-48).coerceAtLeast(64) = 92`）与
> 「≥11 条 / 2–5 条」两格（`REPORT_U1E.md` §7）**逐字不变**；
> 1366x600 稳态仍是让位到极限的 **51**（§5.1）。
>
> 这一点也有机器证据：§7 的 437 例既有测试（含 144 格真值表、768/600/480 × 2 IME ×
> 3 档 × 4 深度 × 2 排列）**全部逐字通过** —— 它们比较的是绝对数值，不是相对关系。

---

## 5. 为什么安全 —— 逐条：`minHeight > maxHeight` 不再可能

> **命题**：对任意 `constraints`（含瞬态、含任意 `maxHeight`），measure policy 生成的
> viewport constraints 满足 `minHeight <= maxHeight`。

**证明（穷尽、无分支遗漏）：**

1. `minHeight = floorPx = safeViewportFloorPx(merged, availablePx)`，
   其中 `availablePx = constraints.maxHeight`。
   由 `safeViewportFloorPx` 的四个分支，`floorPx <= availablePx` **恒成立**
   （`availablePx<=0→0<=availablePx` 只在 `availablePx=0` 时取等；此情形下
   `minHeight=0 <= maxHeight`，见第 3 条）。⇒ **`minHeight <= constraints.maxHeight`。**
2. `maxHeight = viewportHeightPx(shared, pane = constraints.maxHeight, composer, floorPx)`：
   - `shared = false` ⇒ 返回 `pane = constraints.maxHeight`；
   - `shared = true` ⇒ `(pane - composer).coerceAtLeast(floorPx).coerceAtMost(pane)`。
     `coerceAtMost(pane)` 是**最后一道**，返回值恒 `<= pane = constraints.maxHeight`。
   ⇒ 两种排列下 **`maxHeight <= constraints.maxHeight`**。
3. 由 1、2：`minHeight <= constraints.maxHeight` 且 `maxHeight <= constraints.maxHeight`
   ⇒ **`minHeight <= maxHeight`**。∎
4. 非负性：Compose 的 `checkPrecondition` 还要求 `minHeight >= 0 && maxHeight >= 0`。
   `floorPx >= 0`（`safeViewportFloorPx` 的 `floorPx <= 0 -> 0` 分支 + 两个正分支）；
   `maxHeight >= 0`（`viewportHeightPx` 三个返回路径都经 `coerceAtMost`/`coerceAtLeast`
   且两个分支都非负）。⇒ **也不会抛「must be non-negative」。**
5. 宿主那一侧（`constraints.copy(minHeight = 0, maxHeight = composerHostCeilingPx(...))`）
   修前已经安全：`0 <= (pane - floorPx).coerceAtLeast(0)`。**本轮未改动，
   新 pin 逐帧复核它。**
6. **`ui/session/**` 内没有第二处 `minHeight = <可变量>`**：
   全量扫描 `minHeight\s*=` 命中 `SessionScreen.kt:3255`（已修）、
   `SessionPaneLayout.kt` 的三处**只在注释里**、`composer/Composer.kt:2171/2178/2193`
   三处 `minHeight = 0`（**恒安全**）。⇒ 崩溃点唯一。

**机器证据**（不是推理，是跑出来的）：

- `minHeightCanNeverExceedMaxHeightOverTheFullCrossProduct`：
  `maxHeight ∈ {0,1,12,32,51,63,64,65,100,138,140,474,600,768,1440}` ×
  `declared ∈ {0,51,64}` × `shared ∈ {T,F}` × `lines 1..3` × `rows ∈ {0,2,11}`
  = **810 格**，逐格断言 `minHeight <= maxHeight` **且** 逐格过一遍复刻的
  `Constraints.copy` 前置条件。**810/810 合法。**
- `theWholeSubSixtyFourWindowIsSafeForEveryIntegerHeight`：
  `maxHeight` **全整数扫描 0..800** × 三个 `declared` = 2412 格，
  把缩窗瞬态真正经过的**亚 64 整段**逐像素覆盖（不抽样）。
- `theExactCrashFrameIsNowTheDeclaredYieldInsteadOfAnException`：
  **崩溃那一帧逐位重放** —— `maxHeight=12 / declared=64` 现在得到
  `floorPx = 12`（声明让位）而不是 64，且 constraints 合法。
- `everyContiguousSubSequenceOfTheShrinkCycleIsLegal`：
  8 个高度（140/120/90/64/51/38/24/12）的**全部 36 个连续片段**、
  每帧**地板滞后一帧**（真机时序）、× 2 排列 = **240 帧**全部合法。
  ⇒ **缩窗 / 放窗 / 缩→放→缩 / 放→缩 任意连续组合都被覆盖。**
- `theClampIsMonotoneAndIdempotent`：`floor 0..200 × available 0..200` 全扫描，
  夹取**幂等**、**对 available 单调**、**恒不超 available**。

---

## 6. composer 越 pane 底沿 4–5px（`REPORT_U1E.md` §5.3）—— **不同根因，本轮不改**

**设备读数**（1366x600，`pane = 51`）：公式预测 `hostCeiling = pane - floor = 51 - 51 = 0`
⇒ 宿主应为 0；实测 `EditText y=169..217`（h=48），且底沿 `217/218` 比 pane 底沿 `213` 低 4–5px。

**为什么它不是这次 P0 的同一根因：**

| 判据 | 结论 |
|---|---|
| 表现 | **绘制/摆位偏离**，不是 `Constraints` 前置条件违反 —— **不抛异常、不崩** |
| 代码路径 | `composerHostCeilingPx` 与 `fillMaxSize`/`heightIn` 的选择**与 v100 逐字相同**（V100Test 已 pin），**本轮未触碰** |
| 数值 | 1366x600 稳态帧 `declared = 51 = maxHeight = 51` ⇒ 夹取是**恒等**（`min(51,51)=51`）⇒ **修前修后该帧的 `floorPx` 完全相同**（`floorPx=51`、`hostCeiling=0`），**本改动在数值上不可能影响它** |
| 证据缺口 | `REPORT_U1E.md` §5.3 自己就标注：U1D（v100）未记录宿主读数，**无法判断是 v101 引入还是 v100 既有** |

**最小改动面（留给 Lead 裁定后的一轮，本轮按派单未做）：**

- 现象与「末行式（`shared=true`，`heightIn(max=…)`，宿主上限 0）」的**几何**自相矛盾 ——
  上限 0 时宿主不可能有 48px。因此需要先在真机打点 **`shared` 的真值**：
  若 600 窗实为**覆盖式（`shared=false`，宿主 `fillMaxSize()` = 51，composer 48 底对齐）**，
  则宿主 48 与「底对齐后仍差 4–5px」是**同一个摆放问题**（`composerTopPx` 用的是
  `pane` 而绘制基线是 IME 上沿，两者差 4–5px 的**边框/圆角偏移**），
  修法应在 `composerTopPx` 的摆位，不在上限。
- 落点：`SessionPaneLayout.composerTopPx`（`SessionPaneLayout.kt:1138`）
  与 `SessionScreen.kt` 的 `host.place(0, …)`。**一行量级**，但**需要真机读数才能定**。

---

## 7. 测试证据

### 7.1 命令

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat --offline --console=plain :app:testDebugUnitTest `
  --tests "com.xiufeigo.opencodeshell.QueueTimelineShrinkCrashV102Test" `
  --tests "com.xiufeigo.opencodeshell.QueueTimelineFloorV100Test" `
  --tests "com.xiufeigo.opencodeshell.QueueTimelineFloorV101Test" `
  --tests "com.xiufeigo.opencodeshell.QueueTimelineSpaceV99Test" `
  --tests "com.xiufeigo.opencodeshell.SessionPane*" `
  --tests "com.xiufeigo.opencodeshell.PaneLayout*" `
  --tests "com.xiufeigo.opencodeshell.Queue*" `
  --tests "com.xiufeigo.opencodeshell.Composer*" `
  --tests "com.xiufeigo.opencodeshell.Wave23a*" `
  --tests "com.xiufeigo.opencodeshell.StringsLocaleParityTest"
```

### 7.2 原文

```
BUILD SUCCESSFUL in 6s
```

（抢锁：等 `.agent-gradle-lock` 消失 → 写锁 → gradle **前台跑完** → 删锁 ✅ 已完成，
`lock_exists=False` 已核实。未跑 assembleDebug / clean / 全量 / 装机。）

### 7.3 用例数（从 `app/build/test-results/testDebugUnitTest/` 聚合）

| 指标 | 值 |
|---|---|
| **类** | **48** |
| **用例** | **437** |
| **失败** | **0** |
| **错误** | **0** |
| **跳过** | **0** |

关键类逐类：

| 类 | 用例 | 失败 |
|---|---|---|
| `QueueTimelineShrinkCrashV102Test`（新） | **10** | 0 |
| `QueueTimelineFloorV100Test` | 8 | 0 |
| `QueueTimelineFloorV101Test` | 5 | 0 |
| `QueueTimelineSpaceV99Test` | 17 | 0 |
| `SessionPaneArrangementV76Test` | 7 | 0 |
| `Wave23aSettingsPaletteTerminalTest` | 25 | 0 |
| `StringsLocaleParityTest` | 3 | 0 |

新类 10 例清单：

| # | 用例 | pin 的东西 |
|---|---|---|
| ① | `minHeightCanNeverExceedMaxHeightOverTheFullCrossProduct` | **边界安全**：810 格全组合 + 逐格过 Compose 前置条件 |
| ① | `theWholeSubSixtyFourWindowIsSafeForEveryIntegerHeight` | 2412 格全整数扫描（亚 64 整段） |
| ① | `theExactCrashFrameIsNowTheDeclaredYieldInsteadOfAnException` | **崩溃那一帧逐位重放**（`12 / 64`） |
| ② | `theSevenSixtyEightReadingOfNinetyTwoIsByteIdentical` | **语义不回退**：1366x768 的 92 + 深度不改读数 |
| ② | `theTwoBranchFloorRuleSurvivesTheClamp` | v101 两分支逐字不变（`pane 0..400`） |
| ② | `theMaxOfMergeIsProvablyTheIdentityAndCouldNeverHaveSavedTheFrame` | **`maxOf` 恒等**（§1.4 的机器形式） |
| ③ | `theShrinkRoundTripIsLegalOnEveryFrameAndReturnsToItsStartingValue` | **768→600→768 往返**，16 帧 × 2 排列 = 32 帧，终值 = 初值 |
| ③ | `everyContiguousSubSequenceOfTheShrinkCycleIsLegal` | 任意连续组合，240 帧 |
| ④ | `theClampIsInTheMeasurePolicyAndEveryOlderStructuralPinSurvives` | 真修在约束落地处 + **7 条既有结构 pin 逐字仍在** |
| ④ | `theClampIsMonotoneAndIdempotent` | 夹取单调 + 幂等 |

### 7.4 全组合表（`maxHeight` × `declared floor`，共 **45** 格）

`hostPaid` 按既有 pin 口径 `min(bar + card, hostCeiling)`，此处取 `card = 56`（1 行档）。
**粗体 = 修前抛异常**（`minHeight > maxHeight`）。

| `maxHeight` | `declared` | `measured` | `merged`（修前 `minHeight`） | `floorPx`（修后） | `hostCeiling` | `hostPaid` | `viewportMax` | 修前 | 修后 |
|---|---|---|---|---|---|---|---|---|---|
| 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | legal | legal |
| 0 | 51 | 0 | 51 | 0 | 0 | 0 | 0 | **THROW** | legal |
| 0 | 64 | 0 | 64 | 0 | 0 | 0 | 0 | **THROW** | legal |
| 1 | 0 | 0 | 0 | 0 | 1 | 1 | 0 | legal | legal |
| 1 | 51 | 1 | 51 | 1 | 0 | 0 | 1 | **THROW** | legal |
| 1 | 64 | 1 | 64 | 1 | 0 | 0 | 1 | **THROW** | legal |
| 12 | 0 | 0 | 0 | 0 | 12 | 12 | 0 | legal | legal |
| **12** | 51 | 12 | 51 | 12 | 0 | 0 | 12 | **THROW** | legal |
| **12** | **64** | **12** | **64** | **12** | 0 | 0 | 12 | **THROW ← 崩溃帧** | **legal** |
| 32 | 0 | 0 | 0 | 0 | 32 | 32 | 0 | legal | legal |
| 32 | 51 | 32 | 51 | 32 | 0 | 0 | 32 | **THROW** | legal |
| 32 | 64 | 32 | 64 | 32 | 0 | 0 | 32 | **THROW** | legal |
| 51 | 0 | 0 | 0 | 0 | 51 | 51 | 0 | legal | legal |
| 51 | 51 | 51 | 51 | 51 | 0 | 0 | 51 | legal | legal |
| 51 | 64 | 51 | 64 | 51 | 0 | 0 | 51 | **THROW** | legal |
| 63 | 0 | 0 | 0 | 0 | 63 | 56 | 7 | legal | legal |
| 63 | 51 | 51 | 51 | 51 | 12 | 12 | 51 | legal | legal |
| 63 | 64 | 63 | 64 | 63 | 0 | 0 | 63 | **THROW** | legal |
| 64 | 0 | 0 | 0 | 0 | 64 | 56 | 8 | legal | legal |
| 64 | 51 | 51 | 51 | 51 | 13 | 13 | 51 | legal | legal |
| 64 | 64 | 64 | 64 | 64 | 0 | 0 | 64 | legal | legal |
| 65 | 0 | 0 | 0 | 0 | 65 | 56 | 9 | legal | legal |
| 65 | 51 | 51 | 51 | 51 | 14 | 14 | 51 | legal | legal |
| 65 | 64 | 64 | 64 | 64 | 1 | 1 | 64 | legal | legal |
| 100 | 0 | 0 | 0 | 0 | 100 | 56 | 44 | legal | legal |
| 100 | 51 | 51 | 51 | 51 | 49 | 49 | 51 | legal | legal |
| 100 | 64 | 64 | 64 | 64 | 36 | 36 | 64 | legal | legal |
| 138 | 0 | 0 | 0 | 0 | 138 | 56 | 82 | legal | legal |
| 138 | 51 | 51 | 51 | 51 | 87 | 56 | 82 | legal | legal |
| 138 | 64 | 64 | 64 | 64 | 74 | 56 | 82 | legal | legal |
| **140** | 0 | 0 | 0 | 0 | 140 | 56 | **84** | legal | legal |
| **140** | 51 | 51 | 51 | 51 | 89 | 56 | **84** | legal | legal |
| **140** | **64** | 64 | 64 | 64 | 76 | 56 | **84** | legal | **legal** |
| 474 | 0 | 0 | 0 | 0 | 474 | 56 | 418 | legal | legal |
| 474 | 51 | 51 | 51 | 51 | 423 | 56 | 418 | legal | legal |
| 474 | 64 | 64 | 64 | 64 | 410 | 56 | 418 | legal | legal |
| 600 | 0 | 0 | 0 | 0 | 600 | 56 | 544 | legal | legal |
| 600 | 51 | 51 | 51 | 51 | 549 | 56 | 544 | legal | legal |
| 600 | 64 | 64 | 64 | 64 | 536 | 56 | 544 | legal | legal |
| 768 | 0 | 0 | 0 | 0 | 768 | 56 | 712 | legal | legal |
| 768 | 51 | 51 | 51 | 51 | 717 | 56 | 712 | legal | legal |
| 768 | 64 | 64 | 64 | 64 | 704 | 56 | 712 | legal | legal |
| 1440 | 0 | 0 | 0 | 0 | 1440 | 56 | 1384 | legal | legal |
| 1440 | 51 | 51 | 51 | 51 | 1389 | 56 | 1384 | legal | legal |
| 1440 | 64 | 64 | 64 | 64 | 1376 | 56 | 1384 | legal | legal |

**读表要点（三条，全部可从表上直接看出）：**

1. **`merged == declared` 在 45/45 格成立** —— `maxOf` 恒等性的直接表格证据（§1.4）。
2. **修前 `THROW` 恰好 13 格，全部是 `declared > maxHeight` 的格**；
   其余 32 格修前就 legal。⇒ 夹取**只**改变那 13 格。
3. **`maxHeight ≥ declared` 的 32 格修前修后 `floorPx` 逐字相同** ⇒ §4「零回退」。
   1366x768（`maxHeight=140`）与 1366x600（`maxHeight=51`）都在这 32 格里。

---

## 8. 真机复验步骤（⛔ 本轮**未执行** —— 纪律禁止 assembleDebug / 装机）

### 8.1 前置

- 构建：由**持有装机权限**的轮次产出 base.apk；核对 md5 + `lastUpdateTime`。
- 入测原值复核：`wm size` = `Override 1366x768`、`wm density` = `Override 160`。
- `adb devices` 认领 `emulator-5556`（本轮只碰 5556）。
- 硬闸：每一轮交互前 `mCurrentFocus` 必须是本 app（`…/OcActivity`），
  否则**只**用 `am start` 本 app 恢复，**不在 launcher 上做任何点击**。
- `logcat -c` 清缓冲后再开始。

### 8.2 往返 ≥3 次

对 **i = 1..3**，每一步执行后立即取证：

```powershell
# ① 记 pid（崩溃 ⇒ 变 EMPTY）
adb -s emulator-5556 shell pidof com.xiufeigo.opencodeshell

# ② 断言无崩溃（期望：FATAL / IllegalArgumentException 计数为 0）
adb -s emulator-5556 logcat -d | Select-String 'FATAL|IllegalArgumentException'

# ③ 系统侧独立第二来源（期望：crashes since then 不再 +1）
adb -s emulator-5556 shell dumpsys activity processes | Select-String 'opencodeshell|last crashed'

# ④ 两格读数（见 8.3）
```

窗口切换（**≥3 轮往返 = 6 次 `wm size` 变更**）：

| 步 | 命令 | 期望 |
|---|---|---|
| 1 | `wm size 1366x768` + `wm density 160` | pid **不变**；两格 `readable = 92` |
| 2 | `wm size 1366x600` + `wm density 160` | pid **不变**；时间线盒 51、`readable = 51`、队列条不画 |
| 3 | `wm size 1366x768` + `wm density 160` | pid **不变**；两格**回到 `readable = 92`**（不回退） |
| 4–6 | 重复 1–3 | 同上 |

**断言汇总：**

- `pidof` 在 6 步之间**始终同一个值**（无重启、无死亡）。
- `logcat -d | Select-String 'FATAL|IllegalArgumentException'` **零命中**。
- `dumpsys activity processes` 的 `crashes since then` **不增长**。
- 两格读数在每次回到 768 后**都等于 92**（`REPORT_U1E.md` §7 的 12 条 / 4 条两格）。

### 8.3 建议追加的两个组合（派单点名「任意组合」）

| 组合 | 命令 | 期望 |
|---|---|---|
| **分屏** | `adb shell am start` 后切分屏 / `wm size 683x768` | 不崩；两格让位但不抛 |
| **旋转** | `wm user-rotation`（横↔竖），再回横 | 不崩；回到 92 |
| **IME 开关** | `input keyevent 111` 唤出/收起键盘，3 次 | 不崩 |

### 8.4 收尾

- `wm size` / `wm density` 复核 = 入测原值（`1366x768` / `160`）。
- `/sdcard` 证据文件 pull 后 `rm -f`。
- 记 `wm` 切换轮数（≤3 轮「往返」的纪律口径需 Lead 确认：派单要求 ≥3 次往返）。
- 证据目录：`research/v97_verify/5556_v98/u1f/`（建议）。

---

## 9. 未闭环

1. ⛔ **真机复验未执行**（§8）—— 本轮纪律禁止 `assembleDebug` / 装机。
   **P0 的最终闭环必须等这一轮。**
2. ⚠️ **「v100 是否也有此窗口」未实锤**（§1.6）。源码分析表明**两版取值相同**，
   需真机打点 `paneHeightPx` vs `constraints.maxHeight` 的时序才能定论。
3. ⚠️ **composer 越 pane 底沿 4–5px 未修**（§6）—— 不同根因，最小改动面已给出，
   但需 Lead 先裁定 600 窗的 `shared` 真值。
4. ⚠️ **640/480 更极端窗**未测（承 `REPORT_U1E.md` §9.8）。
   JVM 侧已覆盖（`0..800` 全扫描 + 810 格全组合），**真机未覆盖**。
5. ⚠️ **IME 高度信念链**在更矮的窗上（`REPORT_U1E.md` §9.8）可能与 600 窗不同，
   真机组合 §8.3 的 IME 开关正是为此。
6. ⚠️ **承 `REPORT_U1E.md` §8.1：自建会话与 U1D 遗留会话仍未删** —— 本轮无装机权限，未触碰。

---

## 10. 回报

**状态：✅ 已修（纯 JVM 48 类 / 437 例 / 0 失败 / BUILD SUCCESSFUL）｜⛔ 真机复验未执行**

- **因果链一句话**：`floorPx` 的来源是 `onSizeChanged` 写入的 `paneHeight`（**比测量晚一帧**），
  缩窗时 `constraints.maxHeight` 已塌到 12 而 state 仍停在 64 ⇒
  `Constraints.copy(minHeight = 64, maxHeight = 12)` 抛
  `IllegalArgumentException: maxHeight(12) must be >= minHeight(64)` ⇒ 进程死亡；
  放窗不崩是因为它每一帧 `maxHeight >= 64`，不经过亚 64 窗口。
- **怎么保证 `minHeight` 永不超过 `maxHeight`**：新增纯函数
  `SessionPaneLayout.safeViewportFloorPx(floorPx, availablePx) = min(floorPx, availablePx)`
  （两个非负分支），在**写进 `minHeight` 之前**把地板夹到本帧真实空间
  （`SessionScreen.kt:3267`）；因 `viewportHeightPx` 恒 `<= constraints.maxHeight`，
  故 `minHeight <= constraints.maxHeight >= maxHeight`，**不可能再违反**；
  机器证据 810 格全组合 + 2412 格全整数扫描 + 240 帧连续子序列 0 失败。
- **顺带一条更强的定性**：v101 的 `maxOf(declared, measured)` **可证是恒等**
  （`timelineViewportFloorPx` 返回值恒 `<= floorPx`），**它从不把地板调小** ——
  所以「v101 引入」**不能从源码坐实**，且**唯一合法修点就是约束落地处**。
- **改了哪些文件**（仅 2 个生产文件 + 1 个新测试）：
  - `app/src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionPaneLayout.kt`
    —— 新增 `safeViewportFloorPx`（`:1162`）+ v102 论证注释（`:1130-1161`）
  - `app/src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt`
    —— measure policy 夹取（`:3267-3270`）；`minHeight = floorPx,`（`:3285`）一行未动
  - `app/src/test/java/com/xiufeigo/opencodeshell/QueueTimelineShrinkCrashV102Test.kt`（新增，10 例）
  - **未新增任何用户可见字符串；既有断言一条未放宽**（7 条结构 pin 逐字仍在，§2.3）
- **命令与用例数**：
  `:app:testDebugUnitTest` × 10 个 `--tests` 过滤器
  ⇒ **48 类 / 437 用例 / 0 失败 / 0 错误 / 0 跳过**，`BUILD SUCCESSFUL in 6s`
- **报告路径**：`research/v97_fix/CRASH_SHRINK_FIX.md`
