# TRIPWIRE_CRITERION_FIX2 —— v113：清掉第二条「假绊线」（v112 留给下一轮的那条）

- 修复工程师：mavis（v113，用 mcode 执行）
- 日期：2026-10-03
- 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
- 前序：`TRIPWIRE_CRITERION_FIX.md`（v112）**§7#1「未闭环①」**

> ## 状态：**本轮交付完成** —— v109 那条假绊线已定位、已改为**调生产函数**的活判据、
> **反向验证实跑变红（原文见 §4）**、**历史记录一条没丢**、全测试树剩余假绊线已清账（§6）。
>
> - **改了 1 个文件**：`app/src/test/java/com/xiufeigo/opencodeshell/ComposerUnionBottomP5V109Test.kt`
> - **生产代码零改动**（`ui/session/**` 三个文件 md5 注入前后一致，§4.3）
> - 正向 `BUILD SUCCESSFUL in 3s` / **11 tests / 0 failures**；反向 `BUILD FAILED in 3s` / 2 failed
> - 断言强度 **11 tests 9 → 11**，新判据 **实测双向可红**；**零放宽、零恒真化、零删除**

---

## 0. 落盘纪律

- §0.1 本文件在任何代码改动之前写定（`write` 先行，1406 字节骨架）。
- §0.2 每取得一条证据立刻追加/回写；最终版为证据齐备后整体重写，**证据未增一删**。

## 0.1 本轮授权范围（遵守情况）

| 项 | 状态 |
|---|---|
| 可写 | **测试文件**（只改 `ComposerUnionBottomP5V109Test.kt`）+ `research/v97_fix/**` ✅ |
| 禁令文件 | `_upstream/` / `app/build.gradle.kts` / `ChatViewModel.kt` / `ui/servers,home,terminal,nav` / `ui/App.kt` / `OcActivity.kt` **一个没碰** ✅ |
| gradle | 只跑 `:app:testDebugUnitTest --tests "com.xiufeigo.opencodeshell.ComposerUnionBottomP5V109Test"`（**单类**，3 次，全部前台跑完）；**未** assembleDebug / clean / 全量 / 装机 ✅ |
| 抢锁 | 每次等锁 → 写锁 → 跑完 → 删锁（3 次）✅（**一次自伤**：反向跑完忘删锁 → 终验任务自旋 90s 被我发现并 stop，见 §4.4） |
| 用户可见字符串 | **零新增**（全是断言消息 + KDoc 注释） ✅ |
| 断言强度 | 未放宽、未恒真化、未删断言；**9 → 11 tests** ✅ |
| 生产几何 / IME 路径 | `SessionScreen.kt` / `SessionPaneLayout.kt` **零改动**（md5 见 §4.3） ✅ |

---

## 1. 口径（沿用 v112 §1，本轮不重开）

### 1.1 验收量

```
criterion 1:  unionBottom ≤ paneBottom
```

- **`paneBottom`** = 生产 pane 盒（`SessionScreen.kt:3185` 的 `Layout` 节点）自身的底沿，
  在 `SessionPaneLayout` 摆位函数共用的坐标系里 **`paneBottom ≡ panePx`**；
  `panePx` 由 `SessionPaneLayout.believedImeBottomPx`（`:1531`）算出。
- **`unionBottom`** = composer 子节点并集（排除 `Open terminal`）底沿 =
  `composerChildUnionBottomPx`（`:1340`）＋ 区域顶内边距项（**从生产源码行解析**）。

### 1.2 为什么这才是活判据

v112 已证：**pane 盒与 a11y dump 那个容器不是同一节点**（两档都恰好多出一条 composer 带高
`48 = MIN_INTERACTIVE_COMPONENT_PX`）。所以任何拿 dump 容器底沿当尺子的写法，
一旦生产几何变化就会**静默失配**；必须让尺子跟生产函数走。

---

## 2. 假绊线清单（本轮逐条判定）

### 2.1 v109 那一批（`ComposerUnionBottomP5V109Test.kt`）

`grep` 判定全类 9 个测试、逐个断言看「是否读生产」：

| # | 位置（改前） | 内容 | 判定 | 本轮处置 |
|---|---|---|---|---|
| **F1** | **`:103-106`**（改后行号见 §3）<br>`theFivePixelIsFourOfRegionTopPaddingPlusOneOfSubPixelPlacement` 末条 | `assertTrue("OPEN: unionBottom(218) > paneBottom(213) on 1366x600", unionBottomAbs > paneBottomAbs)` | 🔴 **假绊线**：`unionBottomAbs` 的**越界两个成因项 `regionTopPaddingPx=4` 与 `+1` 亚像素都是本文件私有字面量**（`:63`、`:96`），`paneBottomAbs = paneTop + paneHeight600` 也是本文件字面量。`218 > 213` **按本文件自身常量构造即真**，改生产永不翻红 | **✅ 已改**：判据升级为 §3 的生产算术；**本条按「历史复现」保留、标注、不删不改强度** |
| **F2** | **`:269-272`**（改后行号见 §3）<br>`theSixHundredTierIsOpenOnEveryQueueDepthAndPinnedAsSuch` 循环内 | `assertTrue("OPEN: 1366x600 + IME + queue $q unionBottom($ub) > paneBottom($pb)", ub > pb)` | 🔴 **假绊线**（与 F1 同性质）：`ub` 虽过了生产 `composerChildUnionBottomPx`，但**缺陷关键项 `regionTopPx = regionTopPaddingPx`（字面量 4）与 `subPixelPx = 1`（字面量）由本文件注入**，构造即真 | **✅ 已改**：同 F1 |
| 1 | `:81-88` `theFivePixel…` 前半 | `assertEquals("host top (pane-relative) on the 600 tier is 3", 3, hostTop)` 等 4 条，`hostTop = SessionPaneLayout.composerTopPx(51, 48)` | ✅ **合法** —— **真调生产函数**，改 `composerTopPx` 即红 | 原样保留 |
| 2 | `:88` | `assertEquals("absolutely, the host bottom is the pane bottom, 213", 213, paneTop + hostBottom)` | ✅ **合法** —— 断言的是「宿主底沿 == pane 底沿」这条**构造性恒等式**，经 `composerBottomPx` 求值 | 原样保留 |
| 3 | `:97` | `assertEquals("union bottom reproduces the device reading 218", 218, unionBottomAbs)` | ✅ **合法观测值固定** —— v98 真机读数复现，方法名自带 reproduces，且它记录的是**旧状态** | 原样保留 |
| 4 | `:110-121` `theHostBoxBottomIsThePaneBottomInBothTiers` | `composerBottomPx(140, 94) == 140` / `composerBottomPx(51, 48) == 51` | ✅ **合法** —— 纯生产函数，两档各一 | 原样保留 |
| 5 | `:126-162` `theBottomPaddingAbsorbs…` | `assertTrue("6 < 8, so the bottom padding alone yields it", over768 < regionBottomPaddingPx)`、`assertTrue("12 > 8: …", over600 > regionBottomPaddingPx)` | ⚠️ **边界**：算的是**本文件自建 padding 模型**（4/8），不是生产值 ⇒ 按构造真。**但它断言的是「v98 那次 padding 拆分的物理后果」，是真机读数的因果复现**，不是对今天生产行为的断言 | **本轮不动**（记录性因果推演），但已列入 §6 下一轮清单 |
| 6 | `:202-242` `sevenSixEightTierHoldsTheContainmentOnEveryCell` | `hostPx <= paneHeight768`、`ub <= pb`，走 `composerHostHeightPx` + `composerChildUnionBottomPx` | ✅ **合法** —— **真调生产函数**（`regionTopPx = 0` 是 768 档真机事实：编辑器顶沿 == 宿主顶沿） | 原样保留 |
| 7 | `:264-266` | `assertEquals("…pane bottom is 213", 213, pb)` / `assertEquals("…union bottom is 218", 218, ub)` / `assertEquals("…overruns by 5", 5, ub - pb)` | ✅ **合法观测值固定** —— 逐位记 v98 现场 | 原样保留 |
| 8 | `:277-306` `fourEightyTierStaysContained…` | `ub >= paneTop`、`ub <= pb`，全走生产 | ✅ **合法** —— 480 档纯结构格，`regionTopPx=0` 已注明「不对该档的 region-top 项作任何主张」 | 原样保留 |
| 9 | `:309-317` `theHostNeverExceedsThePaneOnAnyTier` | `hostPx <= paneHeight` / `>= 0`，走 `composerHostHeightPx` | ✅ **合法** | 原样保留 |
| 10 | `:322-327` `theTouchTargetIsUntouched…` | `assertEquals("the enforced minimum touch target is 48dp", 48, MIN_INTERACTIVE_COMPONENT.value.toInt())` | ✅ **合法** —— 钉的是**生产常量** | 原样保留 |
| 11 | `:330-339` `theRegionPaddingIsTheOnlyVerticalTermInTheChain` | `assertEquals(12, regionTopPaddingPx + regionBottomPaddingPx)` + 与 `COMPOSER_REGION_PADDING.value` 对齐 | ✅ **合法** —— 断言的是**总量 12 dp**，v110 只挪位置不改总量，故今天仍真；且第二句**读生产常量** | 原样保留 |

**⇒ 假绊线 2 条**（F1、F2，同一处根因），**已改**。
**合法 10 条 / 边界 1 条**，**原样保留，一个数没动**。

### 2.2 关键事实（必须一并记账）

- **v109 时 `OPEN` 是真的；v110 之后它就是假的。** v110 把 `SessionScreen.kt:1827`
  的 `top = 4.dp` 挪到底边（`top = 0.dp, bottom = 12.dp`，本轮已 grep 复核命中），
  真机随后读 `unionBottom 249 ≤ paneBottom 261`（`COMPOSER_OVERFLOW_FIX8.md` §4.2①）。
  ⇒ 所以本轮**不是把 `assertTrue(ub > pb)` 翻成 `<=`**（那会抹掉「当初错在哪」），
  而是**把它按「v98 历史复现」标注保留，同时另立一条活判据**。

---

## 3. 核心 diff

**唯一改动文件**：`app/src/test/java/com/xiufeigo/opencodeshell/ComposerUnionBottomP5V109Test.kt`
**生产代码 diff：无。**

### 3.1 新增：生产取值入口（读生产，不复述生产）

```diff
+import java.io.File
+
+    private fun source(relative: String): String =
+        listOf(File(relative), File("app/$relative"))
+            .firstOrNull { it.isFile }?.readText()?.replace("\r\n", "\n")
+            ?: error("missing $relative")
+
+    private val sessionScreen =
+        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")
+
+    // v113: 区域垂直拆分从**生产那一行**解析，唯一性在此断言
+    private fun regionPaddingDp(): Pair<Double, Double> {
+        val all = Regex(
+            """\.padding\(\s*start = 12\.dp,\s*end = 12\.dp,\s*top = ([0-9.]+)dp,\s*bottom = ([0-9.]+)dp\s*\)""",
+        ).findAll(sessionScreen).toList()
+        assertEquals("the composer region's padding line must stay unique and parseable", 1, all.size)
+        val m = all[0]
+        return (m.groupValues[1].toDouble()) to (m.groupValues[2].toDouble())
+    }
+
+    // 600 档 pane 高，走生产减法：438 - believedImeBottomPx(...) = 51
+    private fun panePx600(): Int = paneBox600 - SessionPaneLayout.believedImeBottomPx(
+        visibleBoxPx = paneBox600, floorPx = 0, imeBottomPx = imeBottom600,
+        essentialPx = SessionPaneLayout.PANE_ESSENTIAL_HEIGHT.value.toInt(),
+    )
+
+    private val paneBox600 = 438   // paneTop(162)..window(600)
+    private val imeBottom600 = 387 // 600 - imeTop(213)
```

> `unionBottom(...)` / `paneBottom(...)` **沿用本类原有 helper 不动** ——
> 它本来就走生产 `composerChildUnionBottomPx`（`:1340`）。本轮**不另建第二套模型**，
> 只把**注入项**（`regionTopPx` / `subPixelPx`）从「本文件字面量」换成「生产解析值 + 显式参数」。

### 3.2 新增：活判据（正向，当前绿）

```diff
+    @Test
+    fun theSixHundredTierHoldsThePaneBottomCriterionAgainstProductionArithmetic() {
+        val panePx = panePx600()                                   // 生产: 438-387=51
+        val paneBottom = paneTop + panePx                          // 生产推得: 213
+        val hostPx = SessionPaneLayout.MIN_INTERACTIVE_COMPONENT_PX // 生产: 48
+        val (regionTopDp, regionBottomDp) = regionPaddingDp()      // 源码真值: 0 / 12
+
+        val ub = unionBottom(
+            paneHeightPx = panePx, hostPx = hostPx, unionPx = hostPx,
+            regionTopPx = regionTopDp.toInt(), subPixelPx = 0,
+        )
+
+        assertEquals("pane bottom is the production-derived 213 == imeTop 213", 213, paneBottom)
+        assertEquals("the production pane is 51 px on the 600 tier", 51, panePx)
+        assertEquals("the band's top padding is the 0 dp v110 moved it to", 0, regionTopDp.toInt())
+        assertEquals("and the 12 dp total is untouched by the move",
+                     SessionPaneLayout.COMPOSER_REGION_PADDING.value.toDouble(),
+                     regionTopDp + regionBottomDp, 0.0)
+        assertTrue("criterion 1: unionBottom($ub) must stay inside paneBottom($paneBottom)",
+                   ub <= paneBottom)
+    }
```

### 3.3 新增：反向腿（旧行为必须被侦测）

```diff
+    @Test
+    fun theCriterionTripwireDetectsTheOldRegionTopPaddingAndTheOldSubPixelTerm() {
+        val panePx = panePx600(); val paneBottom = paneTop + panePx
+        val hostPx = SessionPaneLayout.MIN_INTERACTIVE_COMPONENT_PX
+
+        // 1. 复原 v110 拿掉的杠杆 `top = 4.dp`
+        val withOldTopPadding = unionBottom(panePx, hostPx, hostPx, regionTopPx = 4, subPixelPx = 0)
+        assertTrue("a restored `top = 4.dp` must break criterion 1 (was $withOldTopPadding vs $paneBottom)",
+                   withOldTopPadding > paneBottom)
+        assertEquals("and it must reproduce the v98 overrun's 4 px term, not a different one",
+                     4, withOldTopPadding - paneBottom)
+
+        // 2. 完整 v98 现场（含亚像素）⇒ 必须落回 218 / +5
+        val withFullV98 = unionBottom(panePx, hostPx, hostPx, regionTopPx = 4, subPixelPx = 1)
+        assertTrue("the full v98 state must break criterion 1 (was $withFullV98 vs $paneBottom)",
+                   withFullV98 > paneBottom)
+        assertEquals("and it must land on the v98 device overrun exactly: 218 - 213", 5, withFullV98 - paneBottom)
+        assertEquals("which is the v98 device reading itself", 218, withFullV98)
+
+        // 3. 今天生产必须站在线的另一侧（否则前两条只证「算术能坏」，证不了「真能红」）
+        val (regionTopDp, _) = regionPaddingDp()
+        val today = unionBottom(panePx, hostPx, hostPx, regionTopPx = regionTopDp.toInt(), subPixelPx = 0)
+        assertTrue("today's production split must hold criterion 1 (was $today vs $paneBottom)", today <= paneBottom)
+        assertEquals("with 0 px to spare in the production frame …", 0, today - paneBottom)
+    }
```

### 3.4 假绊线本体：标注保留（**未删、未改比较、未改强度**）

```diff
-            // NOT relaxed into a pass: this is the assertion that must start failing
-            // the day the 600 tier is actually fixed, and it is the open item.
+            // NOT relaxed into a pass. v113: it was never a *live* pass to relax —
+            // it pinned the v98 defect's own constants. Kept verbatim as the record;
+            // the live criterion is
+            // [theSixHundredTierHoldsThePaneBottomCriterionAgainstProductionArithmetic].
             assertTrue(
-                "OPEN: 1366x600 + IME + queue $queueDepth unionBottom($ub) > paneBottom($pb)",
+                "v98 re-enactment (NOT an open item): 1366x600 + IME + queue $queueDepth " +
+                    "unionBottom($ub) > paneBottom($pb)",
                 ub > pb,
             )
```

另：`OPEN:` → `v98 re-enactment (NOT an open item):`（F1 同款）、
类头 KDoc 增补 v113 沿革段、两个历史常量 `regionTopPaddingPx/regionBottomPaddingPx`
加注「这是 v98 记录，不是生产描述」。

---

## 4. 🔴 **反向验证：实跑输出原文（注入旧行为 ⇒ 变红）**

### 4.1 怎么做的（**未改任何生产代码**）

在**测试内**把取值桩 `regionPaddingDp()` 临时改成返回 v110 之前的旧拆分 `4.0 to 8.0`
（= 复原 `SessionScreen.kt:1813` 的 `top = 4.dp`），并把正向判据那条 `assertTrue`
**临时提到方法最前**（这样失败落在**判据**上，而不是先落在 padding pin 上），
跑同一个类，然后**按 md5 校验原样还原**。

### 4.2 反向输出原文（**红**）

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest --console=plain `
  --tests "com.xiufeigo.opencodeshell.ComposerUnionBottomP5V109Test"
```

```
> Task :app:testDebugUnitTest FAILED

ComposerUnionBottomP5V109Test > theSixHundredTierHoldsThePaneBottomCriterionAgainstProductionArithmetic FAILED
    java.lang.AssertionError at ComposerUnionBottomP5V109Test.kt:504

ComposerUnionBottomP5V109Test > theCriterionTripwireDetectsTheOldRegionTopPaddingAndTheOldSubPixelTerm FAILED
    java.lang.AssertionError at ComposerUnionBottomP5V109Test.kt:594

11 tests completed, 2 failed

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':app:testDebugUnitTest'.
> There were failing tests. See the report at: file:///C:/sensorsdata/main/program/opencode-android-shell/app/build/reports/tests/testDebugUnitTest/index.html

BUILD FAILED in 3s
52 actionable tasks: 2 executed, 50 up-to-date
EXIT=1
```

XML 报告里的失败原文（`TEST-com.xiufeigo.opencodeshell.ComposerUnionBottomP5V109Test.xml`）：

```
<testsuite name="com.xiufeigo.opencodeshell.ComposerUnionBottomP5V109Test" tests="11" skipped="0" failures="2" errors="0" …>
FAILMSG: java.lang.AssertionError: criterion 1: unionBottom(217) must stay inside paneBottom(213)
FAILMSG: java.lang.AssertionError: today's production split must hold criterion 1 (was 217 vs 213)
```

> ### 抄一行原文（回报用）
> **`criterion 1: unionBottom(217) must stay inside paneBottom(213)`**
>
> `217 > 213` = **越界 +4**，与 v98 真机读数 `165 + 4 + 48 = 217 > 213`（`Composer.kt:2298-2299`
> 亚像素项之外的整项）**逐位吻合**；再把亚像素 `+1` 加回去就正好是本类头记录的 `218` / `+5`
> —— 这条等价关系由 §3.3 的反向腿**常驻断言**（`assertEquals(5, …)` + `assertEquals(218, …)`），
> 不靠人记。
>
> 旧行为一注入，**判据立刻红**，且**红在判据上、不是红在别的 pin 上**（这正是临时提前的用意）。

### 4.3 还原校验（生产零改动）

```
注入前  TEST_MD5=BAECEFE6359DF57A7EE67733AED4C678
        SS_MD5  =8DA222B0E85B464304A9ADB24A8C05C4   (SessionScreen.kt)
        SPL_MD5 =C00A1E9CC72FE92DACF5730CC2D383BC   (SessionPaneLayout.kt)
还原后  TEST_MD5=BAECEFE6359DF57A7EE67733AED4C678   ← 与注入前逐位一致
        SS_MD5  =8DA222B0E85B464304A9ADB24A8C05C4
        SPL_MD5 =C00A1E9CC72FE92DACF5730CC2D383BC
        grep "REVERSE-INJECTION" → 0 命中
```

### 4.4 如实记账：一次**自伤**（抢锁纪律执行不到位）

反向跑完我**忘删 `.agent-gradle-lock`**，终验任务于是在自己的锁上自旋（无输出 90s+）。
我主动发现 → `task_stop` 取消该任务 → 读锁内容确认为自己留下的 `v113 rev` →
`rm -- ".agent-gradle-lock"` 释放 → **重跑终验成功**（§5.1）。
未影响任何产物与证据，**但抢锁纪律应写成「跑完立刻删锁」**。

### 4.5 双向性判定

| 方向 | 状态 | 证据 |
|---|---|---|
| **正向**（当前已修行为） | 🟢 **绿** | §5.1 `BUILD SUCCESSFUL in 3s` / 11 tests / 0 failures |
| **反向**（复原 v110 前 `top = 4.dp`） | 🔴 **红** | §4.2 `unionBottom(217) > paneBottom(213)` |
| **反向**（完整 v98 现场含 `+1` 亚像素） | 🔴 **红** | §3.3 常驻断言 `218 > 213`，越界量 `+5` 逐位吻合 |
| **反向**（`composerTopPx` 改动） | 🔴 **可红** | `ub` 经 `composerChildUnionBottomPx → composerTopPx`（`:1340→:1426`） |
| **反向**（`believedImeBottomPx` 改动） | 🔴 **可红** | `paneBottom` 经 `panePx600()`（`:1531`） |
| **反向**（`SessionScreen.kt:1827` padding 行改写） | 🔴 **可红** | `regionPaddingDp()` **解析该行**，且断言其**唯一性** |

**⇒ 这不再是「按构造永不触发」的绊线：它对「修好」与「回退」两个方向都有反应。**

---

## 5. 为什么不降强度

### 5.1 正向终验（单类，前台，抢锁→跑→删锁）

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest --console=plain `
  --tests "com.xiufeigo.opencodeshell.ComposerUnionBottomP5V109Test"
```

```
> Task :app:compileDebugUnitTestKotlin
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 3s
52 actionable tasks: 2 executed, 50 up-to-date
EXIT=0
```

XML 汇总：

```
<testsuite name="com.xiufeigo.opencodeshell.ComposerUnionBottomP5V109Test"
           tests="11" skipped="0" failures="0" errors="0" …>
FAILURE_MSGS=0
```

**11 tests / 0 failures / 0 errors，`BUILD SUCCESSFUL`，`EXIT=0`（还原后终验）。**

### 5.2 强度逐条对照

| 维度 | 修前 | 修后 | 判读 |
|---|---|---|---|
| 本类 tests 数 | 9 | **11** | ↑ |
| 判据来源 | **本文件字面量**（`regionTopPaddingPx=4`、`+1`、`paneHeight600=51`） | `believedImeBottomPx` + `composerChildUnionBottomPx`(→`composerTopPx`/`composerChildUnionHeightPx`) + `MIN_INTERACTIVE_COMPONENT_PX` + `COMPOSER_REGION_PADDING` + **从 `SessionScreen.kt:1827` 解析**的拆分 | ↑↑ **换成生产** |
| 改生产代码能否侦测 | ❌ **不能** | ✅ **能**（6 条路径，见 §4.5） | ↑↑ **本轮核心** |
| 活判据可红性 | ❌ 按构造真 | ✅ **实跑变红**（§4.2） | ↑↑ |
| 原有 `assertTrue(ub > pb)` | 2 条 | **2 条，仍在、比较未动、强度未动** | ✅ 没删没放宽 |
| 原有字面量/注释记录 | 全在 | **全在**（`218 / 213 / +5 / 4+1` 逐位保留） | ✅ |
| 容差 | — | **零容差**（`0.0` / 精确 `≤` / 精确 `+4` `+5`） | 未引入容差 |
| 恒真式 | ❌ 是**恒定的假绿** | ❌ **不是**（§4.2 实测红） | ✅ |
| 新增断言消息里的用户可见字符串 | — | **零** | ✅ |
| 生产代码改动 | — | **零**（md5 §4.3） | ✅ |

### 5.3 为什么「保留 `assertTrue(ub > pb)`」不是纵容假绿

- 那两条断言**确实还在、还绿**，但它们绿**不是因为钉住了真相，而是因为它们建模的是 v98 那个已被修好的状态**。把 `4` 改成 `0`，它们照样绿 —— 这恰恰说明它们**只能当记录**。
- 因此本轮的处置是**双轨**：
  1. **记录轨**（原有两条 + 全部字面量注释）—— 回答「当初错在哪」；
  2. **活判据轨**（新增 2 个测试、读生产）—— 回答「今天对不对、回退会不会被发现」。
- 只有把两轨分清，「`assertTrue` 还在」才是诚实的；否则它就是在制造假安全感。
  **两条 `assertTrue` 的消息已从 `OPEN:` 改成 `v98 re-enactment (NOT an open item):`** ——
  措辞不是断言强度，**比较运算符与两侧操作数一个字节没动**。

### 5.4 一处**如实记账**的判读（与 v112 §5.3 同一形状）

正向余量是 **0 px**（`213 == 213`，`assertEquals(0, today - paneBottom)` 常驻）。
即：**在生产算术里，当前配置的余量恰好为零** —— 任何把高度加在带子之上的生产改动都会立刻翻红。
这是**特性不是缺陷**（正是 v112 想要的「零容差」），但必须写明：
**它不等于 v110 真机读的 `−12`**（`COMPOSER_OVERFLOW_FIX8.md` §4.2①），
因为两者**不是同一口径**（v112 §1.2 已证 dump 容器 ≠ 生产 pane 盒，差 48px 带高），
**不可直接相减**。

---

## 6. 全测试树剩余假绊线清单（给下一轮）

`grep` 全 `app/src/test/**` 的 `218` / `213` / `> 213` / `> 5` / 形如 `assertTrue(<全字面量>)`，
逐条判定。**本轮只清 v109 那一批；以下仅列清单与精确判定，未改。**

### 6.1 🔴 确凿的假绊线（与本轮 F1/F2 同性质：**断言的是一个行为主张，但两侧全是字面量**）

| # | 位置 | 原文 | 判定 | 下一轮建议 |
|---|---|---|---|---|
| **R1** | **`ComposerPositionDecompositionV107Test.kt:108-111`** | `assertEquals(48, 48) // 600: unionPx`<br>`assertEquals(48, 48) // 768: unionPx`<br>`assertEquals(48, 48) // 600: hostPx`<br>`assertEquals(48, 48) // 768: hostPx` | 🔴 **最劣形态**：字面量 vs 字面量，**连 `SessionPaneLayout` 都没 import 到这一段**，改生产绝无反应 | 与 v109 同法：改走 `MIN_INTERACTIVE_COMPONENT_PX` / `composerChildUnionHeightPx` |
| **R2** | **`ComposerPositionDecompositionV107Test.kt:115-118`** | `val delta600 = 5; val delta768 = 2`<br>`assertTrue("identical heights cannot produce different overshoots", delta600 != delta768)`<br>`assertTrue("600 is the composer's own placement, 768 is the pane's height", delta600 > delta768)` | 🔴 **两条字面量 `assertTrue`，恒真**；第二句还是**因果主张**（「600 是 composer 的错、768 是 pane 的错」）却零生产依据 | 删掉 `val` 后直接比 `criterion1` 的生产推得值；因果主张必须落到生产函数上 |
| **R3** | **`ComposerPositionDecompositionV107Test.kt:73`** | `assertTrue("all of criterion 1 is position", position == criterion1)` | 🔴 两条都是本方法 `:59-66` 的字面量差（`218-165` vs `170-165+0`），按构造真 | 同 R2 |
| **R4** | **`ComposerBandTouchTargetV105Test.kt:305`** | `assertTrue("the device's union bottom 218 > host bottom 213", 218 > 213)` | 🔴 **字面量 `assertTrue`**，形状与本轮 F1 **一模一样**；且用的是**已被 v108 取代的 `hostBottom` 口径** | 保留读数（改 `assertEquals(5, 218 - 213)` 记账），但**活判据必须另立生产算术** |
| **R5** | **`ComposerBandTallEnoughV106Test.kt:331-334`** | `assertEquals("真机 600 档 ①：unionBottom 218 > hostBottom(imeTop) 213 ⇒ +5", 5, 218 - 213)` | ⚠️ 名字与注释明写「真机读数」，**记账合法**；但 `5 == 218-213` 是编译期常量，**零侦测力** | 同 R4：可留作记录，但**不要再把它当判据引用** |
| **R6** | **`ComposerUnionBottomP5V109Test.kt:132-135` / `:153-156`** | `assertTrue("6 < 8, so the bottom padding alone yields it…", over768 < regionBottomPaddingPx)`<br>`assertTrue("12 > 8: …", over600 > regionBottomPaddingPx)` | ⚠️ 本轮**明确不动**（见 §2.1 #5）：断言的是**v98 padding 拆分的物理后果**，属记录性因果推演 | 若下一轮要清，须先确认这 4/8 拆分是否还算「现场」；可改为「从生产解析的拆分」重跑同一推演 |
| **R7** | **`ComposerChildUnionV104Test.kt:241-246`** | `val union600Before = 49 // y=169..218` | ✅ **合法观测值固定**（命名为 `Before` 的真机原文）—— **不列入待改** | — |
| **R8** | **`ComposerPositionDecompositionV107Test.kt:53-74` / `:78-101`** | 整段 `paneTop/paneHeight/hostPx/bandTop/unionBottom` 全字面量分解 | ⚠️ 是 **v107 的诊断模型**（类头明写「deliberately asserts nothing about a fix」） | 若保留为诊断模型，**须像 v113 一样在类头标明「模型，非判据」**；否则读者会误当防线 |

### 6.2 判读小结

- **确凿假绊线：R1、R2、R3、R4**（4 条，全部是「字面量 vs 字面量」或「字面量 `assertTrue`」，
  改生产代码**绝无反应**，符合本仓「比没有测试更糟」的定义）。
- **边界（记账合法但零侦测力）：R5、R6、R8**。
- **合法观测值固定：R7** 及其它（V104:191、V107:32 表格、V110/V111 报告读数）—— 原样保留。
- **grep 命中的非本主题行**（`SettingsStructureTest.kt:53`、`SshFormReachabilityV98Test.kt:27`、
  `SshPromptOfferV94Test.kt:266`、`WorktreeCreateModelTest.kt:181`、`DropdownMenuDarkSurfaceTest.kt:33`、
  `ServersFormImeFloorV88Test.kt:31`、`SessionPaneShortWindowV57Test.kt:156`、
  `QueueTimelineFloorV101Test.kt:234`）—— **只是行号/读数里含 `218`/`213`**，与 composer/pane 判据无关，**排除**。

---

## 7. 未闭环（如实记账）

1. **下一轮清单 §6.1 的 R1–R4 四条确凿假绊线本轮未改** —— 本轮授权与时间窗锁定在 v109 那一批
   （Lead 指定项）。清单已给到**行号级 + 原文 + 建议改法**，可直接开工。
2. **R5 / R6 / R8 三个边界项需要 Lead 裁定**：「保留为 v98 历史复现」是否可接受，
   还是必须也升级为生产算术。v113 的处理方式（双轨：记录轨 + 活判据轨）可作为模板。
3. **本轮无真机读数** —— 环境纪律禁止 `assembleDebug` / 装机。
   正向方向是**生产算术 + 源码真值**，不是新的设备读数。
4. **正向余量 `0` 与 v110 真机 `−12` 不可直接相减**（口径不同，见 §5.4），
   跨轮比数字前必须先按 v112 §1.2 统一口径。
5. **a11y dump 容器 ↔ 生产 pane 盒节点的精确身份仍未证到行**（v112 §7#4 结转，本轮未重开）。
6. **`git diff` 对本文件不显示内容**（该文件在 git 里是 `??` 未跟踪）——
   **不代表没改**；实际改动以 §3 的 diff、§4.3 的 md5 校验、§5.1 的实跑为准。

---

## 8. 回报（给 Lead）

- **状态**：**本轮交付完成** —— v109 那条假绊线已定位判定、已改为**调生产函数**的活判据、
  **反向验证实跑变红**、**历史记录一条没丢**、全测试树剩余假绊线已清账。
  **1 suite / 11 tests / 0 failures / 0 errors**，`BUILD SUCCESSFUL in 3s`，`EXIT=0`。
- **「v109 那条是什么」**：`ComposerUnionBottomP5V109Test` 里 **2 条 `assertTrue(ub > pb)`**
  （类首测试末条 F1、队列变体循环内 F2）。它们断言「600 档缺陷存在」，
  但越界成因项 `regionTopPx = 4` 与 `subPixelPx = 1` **全是本文件私有字面量**，
  `paneBottomAbs` 也是本文件字面量 ⇒ `218 > 213` **按自身常量构造即真，改生产永不翻红**。
  且它自称 `OPEN`，**但 v110 已修**（`SessionScreen.kt:1827` `top = 4.dp` → `0.dp`）——
  属于「断言一个假的世界」。
- **「怎么改的」**：**双轨，不删不弱**。
  1. 原有 2 条 `assertTrue(ub > pb)` **逐字保留**（比较运算符与两侧操作数零改动），
     消息由 `OPEN:` 改为 `v98 re-enactment (NOT an open item):`，KDoc 注明是 v98 现场复现；
  2. **新增 2 个测试**构成活判据：
     `theSixHundredTierHoldsThePaneBottomCriterionAgainstProductionArithmetic`（正向）
     + `theCriterionTripwireDetectsTheOldRegionTopPaddingAndTheOldSubPixelTerm`（反向）。
     走的生产入口：`believedImeBottomPx`（`:1531`）、
     `composerChildUnionBottomPx`（`:1340` → `composerTopPx` `:1426` +
     `composerChildUnionHeightPx` `:1330`）、`MIN_INTERACTIVE_COMPONENT_PX`（`:108`）、
     `COMPOSER_REGION_PADDING`（`:1449`）、`PANE_ESSENTIAL_HEIGHT`，
     外加**从 `SessionScreen.kt:1827` 解析**的区域垂直拆分（并断言该行唯一）。
     反向腿要求旧 `top = 4.dp` **必须翻红且越界量恰为 `+4`**，补上亚像素后**必须落回 `218` / `+5`**。
- **「反向验证是否变红」**：**变红**。原文一行：
  **`criterion 1: unionBottom(217) must stay inside paneBottom(213)`**
  （`BUILD FAILED in 3s`，`EXIT=1`，`11 tests completed, 2 failed`）。
  注入方式为**测试内取值桩** `regionPaddingDp()` 临时返回旧拆分 `4.0 to 8.0`，
  **`ui/session/**` 零改动**，跑完按 md5 `BAECEFE6…` / `8DA222B0…` / `C00A1E9C…` **原样还原**。
- **「全测试树还发现几条假绊线」**：**确凿 4 条** ——
  `ComposerPositionDecompositionV107Test.kt:108-111`（4 个 `assertEquals(48,48)`）、
  `:115-118`（2 个字面量 `assertTrue`）、
  `:73`（`position == criterion1` 字面量比）、
  `ComposerBandTouchTargetV105Test.kt:305`（`assertTrue(218 > 213)`，与本轮 F1 同形）；
  另有 **3 条边界项**（`ComposerBandTallEnoughV106Test.kt:331-334`、
  `ComposerUnionBottomP5V109Test.kt:132-135`/`:153-156`、
  `ComposerPositionDecompositionV107Test.kt:53-101`）需 Lead 裁定是否也升级。
- **改了哪些文件**：**只有 1 个** ——
  `app/src/test/java/com/xiufeigo/opencodeshell/ComposerUnionBottomP5V109Test.kt`。
  **生产代码 `ui/session/**` 零改动**（md5 校验）；禁令文件一个没碰；零新增用户可见字符串。
- **命令与用例数**：
  `:app:testDebugUnitTest --console=plain --tests "com.xiufeigo.opencodeshell.ComposerUnionBottomP5V109Test"`
  ⇒ **1 suite / 11 tests / 0 failures / 0 errors**，`BUILD SUCCESSFUL in 3s`，`EXIT=0`。
  本轮共 **3 次** gradle（正向首跑 4s / 反向注入 3s 红 / 还原后终验 3s），
  全部**单类**、全部前台、全部按纪律抢锁；**未** assembleDebug / clean / 全量 / 装机。
- **未闭环**：6 条，见 §7 —— 最重要的是 **§6.1 R1–R4 四条确凿假绊线留给下一轮**，
  以及 **§5.4 正向余量 `0` 与 v110 真机 `−12` 口径不同、不可直接相减**。
- **报告**：`research/v97_fix/TRIPWIRE_CRITERION_FIX2.md`
