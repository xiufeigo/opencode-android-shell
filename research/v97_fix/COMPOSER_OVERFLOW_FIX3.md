# composer 溢出 v105 —— **44dp 的带装不下子节点被强制的 48dp 触摸靶 ⇒ 子节点溢出自己的行**

- 修复工程师：mavis
- 日期：2026-10-03
- 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
- 权威口径：`research/v97_verify/5556_v98/REPORT_OVERFLOW_DIAG.md`（§5.2 两条算式）
- 设备：**本轮未装机、未跑真机**（纪律：只跑 `:app:testDebugUnitTest --tests "<类名>"`）

> ## 状态：**✅ JVM 上两条算式全格通过**（96 格真值表 + 1802 格非抽样全扫 + 全部既有 pin 0 失败）
> ## 选定方案：**B —— 受控覆盖 = 把靶封顶到带本身**（`TOOL_ROW_HEIGHT` 仍是 44dp，composer 总高**一像素未动**）
> ## 「48dp 是谁强制的」：**material3**（`LocalMinimumInteractiveComponentSize` 的 `48.dp` 默认值），**不是本仓** —— 全树无任何 `provides 48.dp`（pin）

---

## 1. 定性：`band(44dp)` 与最小触摸靶(48dp) 的关系，**用代码锚钉死**

| 量 | 值 | 代码锚（修后行号） |
|---|---|---|
| 一行带高 | **44 dp** | `Composer.kt:2583` `private val TOOL_ROW_HEIGHT = 44.dp`；`Composer.kt:2140` `.height(TOOL_ROW_HEIGHT)`；`Composer.kt:2221` `val band = minOf(TOOL_ROW_HEIGHT.roundToPx(), constraints.maxHeight)` |
| 控件的最小触摸靶 | **48 dp** | `Clickable` 链（`controls()`）挂 `Modifier.clickable` ⇒ Compose 的 `minimumInteractiveComponentSize()`；`Composer.kt:32` `import androidx.compose.material3.LocalMinimumInteractiveComponentSize` |
| 居中 | `Alignment.CenterVertically` | `Composer.kt:2147`（`Row` 的 `verticalAlignment`） |
| 宿主/摆位算式 | `composerTopPx` / `composerHostHeightPx` | `SessionPaneLayout.kt:1278-1279` / `1202-1203` |

### 1.1 明确回答：**48dp 是谁强制的？**

**material3 强制的，不是本仓。** 证据链（全部可复现）：

1. **谁挂了 `clickable`**：`Add` / `Stop`(Send) / `Model` chip 每一个都在 `controls()` 链上挂
   `Modifier.clickable`（`Composer.kt:2156` 起的注释与 `:2181` 的**单一调用点** pin 钉住）。
2. **谁把 48dp 拉起来的**：`minimumInteractiveComponentSize()` 读
   `LocalMinimumInteractiveComponentSize`，**非 `Unspecified`** 的值即强制最小盒。
3. **本仓读的是 material3 那一个**：`Composer.kt:32`
   `import androidx.compose.material3.LocalMinimumInteractiveComponentSize`
   （不是 foundation 的同名 local）。
4. **48dp 是那个 local 的默认值，值来自库里**（material3 **1.4.0** 字节码，本轮实测反编译）：

   ```
   class androidx/compose/material3/InteractiveComponentSizeKt
     private static final ProvidableCompositionLocal<Dp> LocalMinimumInteractiveComponentSize
     private static final Dp LocalMinimumInteractiveComponentSize$lambda$1();
       0: bipush 48
       5: iload_0 / 6: i2f
       7: invokestatic  androidx/compose/ui/unit/Dp."constructor-impl":(F)F
      10: invokestatic  androidx/compose/ui/unit/Dp."box-impl":(F)Landroidx/compose/ui/unit/Dp;
      ⇒ staticCompositionLocalOf { Dp(48.0f) }      ← 48dp 在这里被写死
   ```

5. **本仓一处 `provides 48.dp` 都没有**：全树唯一命中是 v104 那处**关闭**
   （`LocalMinimumInteractiveComponentSize provides Dp.Unspecified`）⇒ 地板是**库给的默认值**，
   本仓从未设过、也从未覆盖过它。

> pin：`ComposerBandTouchTargetV105Test.theFortyEightDpFloorComesFromMaterialThreeAndTheCapHasOneEvaluationPoint`
> 逐条断言 1/3/5（扫描时**先剥掉注释**，所以只读代码，不被本文档里的 `provides 48.dp` 字样误伤）、
> 以及 v105 的接线只有**一个求值处**。第 4 条（库内字节码）由 §8.6 记为**未在测试内自动化**，理由见那里。

### 1.2 明确回答：**能否在不破坏可点性的前提下让它等于 band？**

**能，而且这正是 v105 采用的做法（方案 B）。** `LocalMinimumInteractiveComponentSize` 是一个
**可被局部覆盖的 composition local**：只影响**布局**（把该节点的最小盒封顶），**不影响**
`clickable` 的语义（`onClick` / role / enabled / a11y `clickable=true` 全部照旧），也**不撤销**
`clickable` 本身。把它设成 `band` ⇒ 强制盒从 48dp 变 44dp，而控件的**固有**高仍是 28dp
（`SUBMIT_SIZE`）⇒ a11y 节点从 48×48 变 **44×44**：仍然远大于视觉尺寸，可点性**未退化**（见 §4）。

### 1.3 那 5px 的算式（与 `REPORT_OVERFLOW_DIAG.md` §5.1 逐位对上）

```
+5 = 4（48dp 触摸靶 − 44dp 带） + 1（居中的亚像素取整）
```
真机两档原文：`1366x600`+IME `Add/Stop/chip h=48` 装在 `band=44` 里 ⇒ `unionBottom 218 > hostBottom 213`；
`1366x768`+IME 宿主盒 94 装得下 ⇒ `union 88 ≤ 94` 合格。**只有 600 档那一格越界。**

---

## 2. 方案取舍：**选 B（受控覆盖）**

| 维度 | 方案 A：band ≥ 48dp | **方案 B：靶封顶到 band（选用）** |
|---|---|---|
| composer 总高 | **+4dp**（44 → 48） | **0**（`TOOL_ROW_HEIGHT` 仍 44dp，源码 pin 钉住） |
| 时间线地板 / `readable` | **必须连带重算**：`600` 的 `readable=51`、`768` 的 `readable=92`、`92/43/48` 队列两格、140px 时间线盒全部要重验 | **逐字不动**（宿主盒高由 `composerHostHeightPx(pane, ceiling)` 决定，与靶无关） |
| 已 PASS 读数 | 44dp 带本身被 pin 在 5 个测试里（`ComposerEditorFormV80Test:254`、`ComposerModelChipV75Test:141`、`ComposerOneRowEditorFloorV86Test:305`、`ComposerChildUnionV104Test:495`）⇒ A 要改 5 处 pin = **放宽/改动既有断言** | 一个常量都没动 |
| 两条算式 | 也能过 | **能过，且是一行形态的构造性恒等式** |
| 风险面 | 全局几何（所有窗口、所有机型、所有队列态） | 只改 `LocalMinimumInteractiveComponentSize` 的**值** |

**结论：选 B。** A 的代价是**改 5 处既有 pin**（任务明令"不许放宽任何既有断言"），并把一次
`ui/session/composer` 的局部修复升级成全局几何变更；B 保持 44dp 带与所有已 PASS 读数**逐字不变**，
且在"可点面积"这一维**比 v104 更好**（见 §4）。

### 2.1 为什么不选 A 的连带影响分析（若 Lead 后续仍要 A）

A 会把一行带抬到 48dp ⇒ 宿主盒在每个窗口 +4px ⇒ `panePx` 不变、`viewportHeightPx` 的
`composer` 份额 +4 ⇒ `1366x768` 的 `readable 92 → 88`、`1366x600` 的 `readable 51` 被挤到 47
（`pane=51` 里 composer 份额 44 → 48），队列两格的 92/43 与 140px 时间线盒全部要重算。
本轮**未采用**，故这些数字**未变更**（`ComposerBandTouchTargetV105Test` 逐字 pin 住 51 / 92）。

---

## 3. 修：v105 的三支闸门（`Composer.kt`）

```kotlin
val bandControls: @Composable () -> Unit = { controls() }
val bandTouchTarget = SessionPaneLayout.composerBandTouchTargetDp(
    oneRow = minimal,
    band = bandMaxHeight,
)
if (bandMaxHeight >= MIN_TOUCH_TARGET) {
    bandControls()                                          // 带 >= 48：原值，一像素不动
} else if (bandTouchTarget != Dp.Unspecified) {            // v105：封顶到带本身
    CompositionLocalProvider(
        LocalMinimumInteractiveComponentSize provides bandTouchTarget,
    ) { bandControls() }
} else {                                                    // v84/v86 地板之下：才撤掉地板
    CompositionLocalProvider(
        LocalMinimumInteractiveComponentSize provides Dp.Unspecified,
    ) { bandControls() }
}
```

**唯一求值处**（新增，纯函数，JVM 可测）：

```kotlin
// SessionPaneLayout.kt
fun composerBandTouchTargetDp(
    oneRow: Boolean,
    band: Dp,
    minInteractive: Dp = MIN_INTERACTIVE_COMPONENT,   // 48.dp —— material3 的地板
    controlFloor: Dp = CONTROL_FLOOR,                 // 28.dp —— SUBMIT_SIZE
): Dp {
    val cap = if (oneRow) minOf(minInteractive.value, band.value) else minInteractive.value
    return when {
        cap >= controlFloor.value -> cap.dp
        else -> Dp.Unspecified
    }
}
```

| 带 | 一行形态（一行带的 composer box **就是**这条带，`layout(..., constrainHeight(band))`） | 堆叠形态（box = `editor + toolRow`，由宿主 `Constraints.maxHeight` 收着） |
|---|---|---|
| `>= 48` | **48（原值，一像素不动）** | **48（原值）** |
| `[28,48)` | **封顶到带本身**（44dp 带 ⇒ 44dp 靶 ⇒ `unionPx == band == 44`） | 48（`1366x768` 宿主 94 装得下，`h=48` 逐字不变） |
| `< 28` | `Dp.Unspecified`（v86 地板之下） | 同左 |

**为什么 `oneRow` 必须区分**：一行形态的 composer 盒**就是**那条 44dp 的带，靶必须跟着它走；
堆叠形态的盒是 `editor + toolRow`（真机 768 档宿主 94），靶保持 48dp 装得下 —— 这也是
`1366x768` 那一档 `Add/Send h=48` 已 PASS 读数**保住**的原因。

### 3.1 两条算式怎么变成构造性恒等式

```
一行形态：target = min(48, band)  ⇒  toolRow = max(28, target) = min(48, max(28,band)) <= band
          editor = min(48, band)  ⇒  editorTop = (band - editor)/2 >= 0
          unionPx = max(editorTop + editor, toolRow) <= band
          unionBottom = hostTop + unionPx <= hostTop + band = hostTop + hostPx = hostBottom  (v103)
堆叠形态：editor <= host - toolRow ⇒ unionPx = editor + toolRow <= hostPx = box
```
⇒ **`unionBottom <= hostBottom` 与 `unionPx <= band` 同时成立，且不是"碰巧"，是收口的结果。**

### 3.2 diff

```diff
--- a/app/src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionPaneLayout.kt
+++ b/app/src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionPaneLayout.kt
@@ val COMPOSER_MIN_HEIGHT: Dp = 116.dp
+    const val MIN_INTERACTIVE_COMPONENT_PX: Int = 48      // material3 的强制地板
+    const val CONTROL_FLOOR_PX: Int = 28                  // SUBMIT_SIZE = v86 的控件地板
+    val CONTROL_FLOOR: Dp = CONTROL_FLOOR_PX.dp
+    val MIN_INTERACTIVE_COMPONENT: Dp = MIN_INTERACTIVE_COMPONENT_PX.dp
@@ fun composerChildUnionBottomPx(...)
+    fun composerBandTouchTargetDp(oneRow, band, minInteractive, controlFloor): Dp { ... }   // v105 唯一求值处

--- a/app/src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt
+++ b/app/src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt
@@  val bandControls: @Composable () -> Unit = { controls() }
+    val bandTouchTarget = SessionPaneLayout.composerBandTouchTargetDp(oneRow = minimal, band = bandMaxHeight)
     if (bandMaxHeight >= MIN_TOUCH_TARGET) {
         bandControls()
-    } else {
+    } else if (bandTouchTarget != Dp.Unspecified) {
+        CompositionLocalProvider(
+            LocalMinimumInteractiveComponentSize provides bandTouchTarget,
+        ) { bandControls() }
+    } else {
         CompositionLocalProvider(
             LocalMinimumInteractiveComponentSize provides Dp.Unspecified,
         ) { bandControls() }
     }
```

**改动的文件只有两个（都在授权范围内）**：
`ui/session/SessionPaneLayout.kt`（新增纯函数 + 两个常量）、`ui/session/composer/Composer.kt`（闸门接线 + 注释）。
新增测试一个：`app/src/test/java/com/xiufeigo/opencodeshell/ComposerBandTouchTargetV105Test.kt`。
**未碰** `_upstream/`、`app/build.gradle.kts`、`ChatViewModel.kt`、`ui/servers/**`、`ui/home/**`、
`ui/terminal/**`、`ui/nav/**`、`ui/App.kt`、`OcActivity.kt`；未新增任何用户可见字符串。

---

## 4. 为什么不降强度（可点性 / a11y 证据）

| 证据 | 修前（真机 600 档） | v104（关掉地板） | **v105（封顶到带）** |
|---|---|---|---|
| a11y 节点高 | **48dp** | **28dp** | **44dp** |
| 可点面积（`Add`/`Stop`/chip） | 48×48 | 28×28 | **44×44** |
| 并集是否越宿主盒 | **`+5px` ❌** | 合格 | **合格** |
| 底沿与 IME | 底沿 `218` **落在 `imeTop 213` 之下** ⇒ **那 4px 被键盘压住、点不到** | 全在带内 | 全在带内 |

1. **净增可点面积**：44×44 比 v104 的 28×28 **多 16dp**（比真机修前的 48×48 少 4dp，但那 4dp 原本
   被 IME 遮住不可点 ⇒ **真正可点的面积净增**）。
2. **a11y 语义未退化**：local 只参与**布局**（强制最小盒），不改 `clickable` 的 `onClick` / role /
   enabled / `clickable=true` / content-desc；a11y 树里这些节点**仍是可点的 44×44 盒**，
   并且**完整落在带内**（修前它们的底沿落在键盘遮挡区）。
3. **控件仍是单一调用点**：`controls()` 在 `ComposerArrangement` 里**只出现一次**
   （v80/v81 的 "mounted once and never move"）—— v105 只加了一个 local 的取值，**没有新增调用点**。
4. **v104 的 pin 一个字没放宽**：`MIN_TOUCH_TARGET = 48.dp`、`if (bandMaxHeight >= MIN_TOUCH_TARGET) {`、
   `provides Dp.Unspecified`（仍**恰好一处**）、`TOOL_ROW_HEIGHT = 44.dp`、`.height(TOOL_ROW_HEIGHT)`、
   `val band = minOf(TOOL_ROW_HEIGHT.roundToPx(), constraints.maxHeight)` 全部原样（见 §6 的回归）。

---

## 5. JVM pin：纯函数真值表（`ComposerBandTouchTargetV105Test`）

| 用例 | 覆盖 | 格数 |
|---|---|---|
| `everyBandTouchTargetWindowImeAndQueueCellKeepsTheUnionInsideTheBox` | **band × 触摸靶 × 窗口{600,480,768,1280} × IME{387,466,关} × 队列条数{0,2,5,11} × 形态{一行,堆叠}**，逐格断言 `unionBottom <= hostBottom` 且 `unionPx <= band`（一行形态另钉字面量那条算式），并每格复核 v103 恒等式 `hostBottom == pane` | **96** |
| `aNonSampledSweepOfEveryPaneHeightAndFormHoldsBothFormulas` | **非抽样**：`pane 0..900` × 2 形态，逐格断言靶 `<= band`（一行形态）/ `<= 盒`（堆叠形态）、`union <= hostPx` | **1802** |
| `theFivePixelOverflowIsGoneAndTheSurvivingReadingsStayByteIdentical` | 真机那 5px 逐位复现（修前 `48−44=4`、`49−44=+5`、`218>213`）；修后 600 档 `target=44`、`union=band=44`、`unionBottom == hostBottom`；768 档 `target=48` / `toolRow=48` 逐字不变；`readable 51` / `readable 92` 一个像素不退 | 2 档 |
| `theFortyEightDpFloorComesFromMaterialThreeAndTheCapHasOneEvaluationPoint` | 48dp 是 material3 的（import + 全树无 `provides 48.dp` + 唯一求值处 + v104 的 pin 一个字没动 + 44dp 常量原样） | 结构 |
| `theCappedTargetIsNeverSmallerThanTheControlItWraps` | 封顶值 `∈ [28, min(band,48)]`（`band 28..96` 全扫）；堆叠形态地板原样 48；`band < 28` 才交还 `Dp.Unspecified` | 69+ |

### 5.1 真值表（**JVM 实算**，与 `Frame` 的算式逐字一致）

`band` × 触摸靶 × 窗口 × IME × 队列条数 × 形态，**每格断言两条算式**。下列格子是**模型实算值**
（pane 相对坐标；绝对坐标 = 该值 + `paneTop` 162）：

| 窗口 | IME | 队列 | 形态 | pane | hostPx | **band** | **靶** | toolRow | editor | **unionPx** | 盒 | `unionPx ≤ 盒` | `unionBottom` | `hostBottom` | `unionBottom ≤ hostBottom` |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 600 | 387(开) | 0 | 一行 | **51** | 44 | **44** | **44** | 44 | 44 | **44** | 44 | ✔ `44≤44` | **51** (=绝对 **213**) | 51 | ✔ |
| 600 | 387(开) | 2 | 一行 | 0 | 0 | 0 | `Unspec` | 28 | 0 | 28 | 0 | ⛔ 定义域外 | — | — | ⛔ 见下注 |
| 600 | 0(关) | 0 | 一行 | 438 | 44 | 44 | 44 | 44 | 44 | 44 | 44 | ✔ | 438 | 438 | ✔ |
| 600 | 0(关) | 1 | 一行 | 410 | 44 | 44 | 44 | 44 | 44 | 44 | 44 | ✔ | 410 | 410 | ✔ |
| 600 | 387(开) | 0 | 堆叠 | 51 | 51 | 44 | 48 | 48 | 3 | 51 | 51 | ✔ `51≤51` | 51 | 51 | ✔ |
| 768 | 466(开) | 0 | 堆叠 | **140** | **94** | 44 | **48** | **48** | 46 | **94** | 94 | ✔ `94≤94` | 140 | 140 | ✔ |
| 768 | 466(开) | 1 | 堆叠 | 112 | 94 | 44 | 48 | 48 | 46 | 94 | 94 | ✔ | 112 | 112 | ✔ |
| 768 | 466(开) | 2 | 堆叠 | 84 | 84 | 44 | 48 | 48 | 36 | 84 | 84 | ✔ | 84 | 84 | ✔ |
| 768 | 0(关) | 2 | 一行 | 550 | 44 | 44 | 44 | 44 | 44 | 44 | 44 | ✔ | 550 | 550 | ✔ |
| 1280 | 0(关) | 0 | 一行 | 1118 | 44 | 44 | 44 | 44 | 44 | 44 | 44 | ✔ | 1118 | 1118 | ✔ |

**三条要读出来的**：

1. **第一行就是本轮要修的那一格**。真机实测 `pane = 51`（`paneTop 162 + 51 = 213`）、`hostPx = 44`
   ——与 `REPORT_OVERFLOW_DIAG.md` §4.2 逐位吻合。修**前** `unionPx = 48 > band = 44`、
   `unionBottom = 218 > hostBottom = 213`；修**后** `unionPx = 44 = band`、
   `unionBottom = 51 = hostBottom`（绝对 `213 = 213`）⇒ **两条算式同时成立**。
2. **`768` 档一行没动**：靶仍 `48`、工具行仍 `48`（真机 `Add/Send h=48` 逐字不变），
   `pane = 140`（与真机 `1366x768` 的 140 逐位一致）。
3. **定义域边界（不是静默跳过）**：当队列把 pane 吃到 **28px 以下**（`600+IME` 档：IME 已占 387，
   再来 2 行队列 `pane` 就是 0），或 `480` 窗口配 `600` 档那个 IME 高时（`480-162-387 < 0`），
   两条算式**没有定义** —— 一个 28dp 的整控件都放不下，这是 **v84/v86 已 pin 的裁剪**行为。
   测试用 `Frame.coveredByTheFormula`（一行形态 `band >= 28`、堆叠形态 `盒 >= 48`）**显式**划出定义域。
   ⚠️ 注意：**v104 的模型完全忽略 `rows`**（队列那一维在它那儿是死的）⇒ 本表是**更严**的扫描；
   另有一遍 `aNonSampledSweepOfEveryPaneHeightAndFormHoldsBothFormulas` 对 `pane 0..900` × 2 形态
   **1802 格非抽样**逐格断言，**不依赖表里任何一个模型输入常数**（含 `QUEUE_ROW_PX`）。

---

## 6. 回归跑

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest --console=plain `
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
> Task :app:compileDebugUnitTestKotlin
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 1m 30s
52 actionable tasks: 2 executed, 50 up-to-date
EXIT=0
```

**结果：457 用例 / 0 失败 / 51 个测试类**
（`app/build/test-results/testDebugUnitTest/TEST-*.xml` 汇总：`classes=51 tests=457 failures+errors=0`），
其中新类 `ComposerBandTouchTargetV105Test` **5 个用例全过**：

```
- theCappedTargetIsNeverSmallerThanTheControlItWraps
- theFivePixelOverflowIsGoneAndTheSurvivingReadingsStayByteIdentical
- theFortyEightDpFloorComesFromMaterialThreeAndTheCapHasOneEvaluationPoint
- aNonSampledSweepOfEveryPaneHeightAndFormHoldsBothFormulas
- everyBandTouchTargetWindowImeAndQueueCellKeepsTheUnionInsideTheBox
```

覆盖 `Composer*` / `Queue*` / `SessionPane*` / `PaneLayout*` / `Wave23a*` / `StringsLocaleParityTest`
全量 + v99–v104 全部显式 pin。

### 6.1 纪律

- 只跑 `:app:testDebugUnitTest --tests "<类名>"`；**未** `assembleDebug` / `clean` / 全量 / 装机；
  gradle **前台跑完**（一次跑到 `BUILD SUCCESSFUL`），抢锁：`.agent-gradle-lock` 不存在 → 写锁 → 跑完删
  （锁已释放，`Test-Path` 复核为 `False`）。
- **未改任何既有断言**（v99–v104 的 pin 逐条保留，其中 v104 的源码文本 pin 原样通过）。
- **边做边写盘**：本报告在最后一次 gradle 之前就已落盘。
- **本轮共 5 次 gradle 调用，全部如实记录**（前 4 次都是**本轮新代码**的问题，无一次是回归失败）：

  | # | 结果 | 原因 |
  |---|---|---|
  | 1 | `compileDebugUnitTestKotlin` FAILED | 新测试用了 `Dp.isSpecified` / `isUnspecified`（本仓 Compose 版本里不可解析）⇒ 改用 `.value.isNaN()` |
  | 2 | FAILED | `px()` 写在外层类、但 `Frame` 是嵌套类访问不到 ⇒ 挪进 `companion object` |
  | 3 | **457 tests, 4 failed**（4 条**全在新类**；453 条既有 pin 全过） | ① 队列 IME 列表写成 2 项（格数 64≠96）；② `readable 92` 用错了 `composer` 参数（v104 的 92 取 `composer = 48`）；③ `provides 48.dp` 全树扫描**扫到了我自己的注释**⇒ 改为剥注释后再扫；④ 非抽样全扫里堆叠形态误用了"靶 ≤ band"（堆叠的盒是宿主高，不是那条 44dp 的行） |
  | 4 | FAILED | 上一条的全扫断言改成按形态分派（一行 ≤ band / 堆叠 ≤ 盒） |
  | 5 | **`BUILD SUCCESSFUL`，457 / 0** | — |

---

## 7. 真机复验步骤（**下一轮复验员照此执行**）

> 口径全部换成 `REPORT_OVERFLOW_DIAG.md` §5.2：两条算式 + `imeTop` 断言 + 排除 `Open terminal`。

### 7.1 装机前（归属闸门）
```bash
adb shell 'md5sum /data/app/~~*/com.xiufeigo.opencodeshell-*/base.apk'   # 记 md5 + lastUpdateTime
adb shell pidof com.xiufeigo.opencodeshell                               # 记 pid，全程须恒定（零重启）
adb shell dumpsys window | grep mCurrentFocus                            # 必须是 …/OcActivity
```

### 7.2 档位（两者必须配对）
```bash
adb shell wm size 1366x600 && adb shell wm density 160
adb shell dumpsys input_method | grep -E 'mInputShown|mFrame'   # 断言 mInputShown=true；mFrame 顶沿 = imeTop
adb shell uiautomator dump /sdcard/s5.xml && adb pull /sdcard/s5.xml
```

### 7.3 两条主算式
```
paneTop    := 二级工具条底沿（实测 162）
imeTopPx   := dumpsys input_method 的 mFrame 顶沿（600 档 = 213）   ← pane 底沿改用它
paneBottom := imeTopPx
   ✅ 并【断言】paneBottom == paneTop + panePx（600: 162+51=213）；不成立就作废本帧
hostPx     := min(TOOL_ROW_HEIGHT.roundToPx(), panePx)             (600: 44)
hostTop    := paneTop + (panePx − hostPx)                          (600: 169)
hostBottom := hostTop + hostPx                                     (600: 213)
band       := min(TOOL_ROW_HEIGHT.roundToPx(), constraints.maxHeight)

① ✅ unionBottom <= hostBottom    真机 600 档：218 > 213 ❌  → 修后应为 213 <= 213
② ✅ unionPx     <= band          真机：48 > 44 ❌            → 修后应为 44 <= 44
```

### 7.4 选节点规则（避免 §3.4 的 `+19` 假阳性）
```
composer 子节点 := 与时间线节点【互为兄弟】、且落在 [hostTop, hostBottom] 带内的
                   EditText + 该工具带上的 clickable 控件
⛔ 排除 Open terminal [1320,184][1366,232]  （悬浮按钮，非 composer 布局，混进来 ⇒ +19）
⛔ 排除 二级工具条（y=110..162）、tab 条、chip 行自身的 HorizontalScrollView
⛔ 排除 对根 Box / 内容根底沿(600) 的比较（恒 −382，恒过，测不出东西）
⛔ 停用「ceiling = 0 ⇒ 宿主高必须为 0」（输入模型错 ≥48px，永久作废）
```

### 7.5 修后**期望读数**（逐位）
| 档 | `band` | `Add`/`Stop`/chip 高 | `EditText` | `unionPx` | `unionBottom` | `hostBottom` | 判定 |
|---|---|---|---|---|---|---|---|
| `1366x600` + IME | 44 | **44**（原 48） | **44**（原 48） | **44**（原 49） | **213**（原 218） | 213 | ① ② 双过 |
| `1366x768` + IME | 44 | **48（逐字不变）** | 48 | 88 | 296 | 302 | ① ② 双过 |

### 7.6 不回退项（逐条量）
- 往返缩窗（`600 ⇄ 768 ⇄ 480`）**不崩**：回 `Crash log`/`pidof` 恒定。
- `1366x600` `readable` 仍是 **51**；`1366x768` 时间线盒仍是 **140px**、`readable` 仍是 **92**。
- 队列两格：**92 / 43 / 48**，全部 **clickable**。
- `Open terminal` 仍是 48dp 的悬浮按钮（**它不参与并集**，但**仍在**）。

---

## 8. 未闭环 / 交 Lead 裁定

1. **🔴 真机未验**：本轮纪律只允许 JVM 单测，**未装机、未改窗口**。§7.5 的"期望读数"是**推导值**，
   必须由下一轮复验员在 5556 上逐位确认（尤其 `1366x600` 那一格的 `Add/Stop/chip h=44`）。
2. **🟠 堆叠形态的 48dp 靠"盒装得下"兜底，不是靠读数**：堆叠形态的闸门在 composition 里
   **读不到**宿主盒高（measure policy 只把 `maxHeight = band` 递给 `BoxWithConstraints`，
   而**不能**改递法——`BoxWithConstraints` 会用 `constraints.maxHeight` 当**自己的盒高**，
   改递法会让堆叠形态的盒高从 44 变 94 ⇒ 布局整体撑开）。故堆叠形态保持"48 地板 + `editor`
   被 `maxHeight − toolRow` 收着"这条既有结构，前置条件是**宿主盒 ≥ 48**（堆叠 composer 自身地板
   `60 + 44 = 104`，`SessionPaneImeBandV84Test` 已 pin `>= 28`）。真值表按 `box >= 48` 划定
   这一格的定义域，**已在测试里写明**，不是遗漏。
3. **🟠 编辑器那一维（真机 `EditText 217 > 213`，+4）** 靠 measure policy 既有的
   `maxHeight = band`（`Composer.kt:2229-2235`）收口；本轮**未改**它，只在真值表里按
   `editor = min(48, band)` 钉死。若真机复验发现 EditText 仍 48（说明子组合忽略了约束），
   需要再单独处理 —— 见 §9。
4. **🟠 三个文件仍未提交 git**（沿用 v104 的状态）：`Composer.kt` = ` M`、`SessionPaneLayout.kt` = `??`、
   新测试 = `??`。被测构建与 HEAD 的对应关系**依旧无法自证**，建议随本修复一并提交。
5. **🟡 可见性未量化**：修前那 4px 落在 IME 顶沿之下是否真的被裁，本轮未做像素级比对
   （沿用 `REPORT_OVERFLOW_DIAG.md` §7.5 的未闭环项）。
6. **🟡 库内 48dp 的字节码证据未进测试**：§1.1 第 4 条（本轮用 `javap` 在
   `material3-android-1.4.0` 的 `classes.jar` 上实测）**没有**做成自动化 pin ——
   它的路径在 `~/.gradle/caches` 里，随机器与 material3 版本漂移，写进测试会变成一条
   **依赖构建环境的脆 pin**。测试只 pin 本仓那一侧（import + 全树无 `provides 48.dp`）。
   ⇒ 若 material3 升级改了默认值，**本测试不会报**，需人工重跑本节的 `javap` 一行。

---

## 9. 若真机复验仍报 `EditText` 溢出（预置处置）

`EditText` 的 48dp 来自编辑器自身链里的最小高，而不是触摸靶。若它在 `maxHeight = band` 下仍报 48，
说明该最小高是 `required*`（Compose 的 `requiredHeight` **不受** `maxHeight` 约束）⇒ 处置是
在编辑器链上按 v80/v86 已有的 `heightIn` 模式**改成可被 `maxHeight` 收掉的写法**，
或在该帧把 `unionPx` 的上界改成 `max(toolRowPx, editorPx)` 并在**摆位**上收口
（`SessionPaneLayout.composerChildUnionBottomPx` 已在 `SessionPaneLayout.kt:1252` 就位，
但仍只有测试在调它 —— **真机上零调用**这一条沿用 `REPORT_OVERFLOW_DIAG.md` §4.5 的结论，未闭环）。
