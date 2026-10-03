# TRIPWIRE_CRITERION_FIX3 —— v114：清掉全测试树**剩余**的假绊线（v113 §6.1 的 R1–R4 + 3 条边界裁定）

- 修复工程师：mavis（v114，用 mcode 执行）
- 日期：2026-10-03
- 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
- 前序：`TRIPWIRE_CRITERION_FIX2.md`（v113，§6.1 清单 R1–R8）、`TRIPWIRE_CRITERION_FIX.md`（v112，口径）

> ## 状态：**本轮交付完成** —— 4 条确凿假绊线**全部改为调生产函数**的活判据、
> **反向验证实跑变红（原文见 §4）**、3 条边界项**逐条裁定并执行**、
> **历史读数一条没丢**、收尾复扫**假绊线归零**（§6）。
>
> - **改了 4 个测试文件**（V107 / V105 / V106 / V109），**生产代码零改动**（md5 §4.3）
> - 正向 `BUILD SUCCESSFUL in 53s` / **7 suites / 51 tests / 0 failures**
> - 反向 `BUILD FAILED in 4s` / **10 tests completed, 4 failed** / `EXIT=1`
> - 断言强度：V107 **3 → 4** tests、V105 **5 → 6** tests；**零放宽、零恒真化、零删除**

---

## 0. 落盘纪律与授权范围

- §0.1 本文件在任何代码改动之前写定（`write` 先行，2377 字节骨架），之后逐条回写证据。

| 项 | 状态 |
|---|---|
| 可写 | **测试文件**（V107 / V105 / V106 / V109）+ `research/v97_fix/**` ✅ |
| `ui/session/**` | **一个字节没动**（`:1827` padding 行 md5 校验见 §4.3）✅ |
| 禁令文件 | `_upstream/` / `app/build.gradle.kts` / `ChatViewModel.kt` / `ui/servers,home,terminal,nav` / `ui/App.kt` / `OcActivity.kt` **一个没碰** ✅ |
| gradle | 只跑 `:app:testDebugUnitTest --tests "<类名>"`，共 **3 次**，全部前台跑完；**未** assembleDebug / clean / 全量 / 装机 ✅ |
| 抢锁 | 每次等锁 → 写锁 → 跑 → **立刻删锁**（3 次）✅（§4.4 如实记账一次失手） |
| 用户可见字符串 | **零新增**（全是断言消息 + KDoc 注释）✅ |
| 断言强度 | 未放宽、未恒真化、未删断言 ✅ |

---

## 1. 口径（沿用 v112 §1，本轮不重开）

```
criterion 1:  unionBottom ≤ paneBottom
```

- **`paneBottom`** = `paneTop + panePx`，`panePx` 走 `SessionPaneLayout.believedImeBottomPx`（`:1531`）。
- **`unionBottom`** 走 `composerTopPx`（`:1426`）/ `composerChildUnionHeightPx`（`:1330`）/
  `composerChildUnionBottomPx`（`:1340`）/ `composerBottomPx`（`:1301`）。
- **区域垂直拆分**（`top =` / `bottom =`）**从 `SessionScreen.kt:1827` 那一行解析**，并断言该行**唯一**。
- 已证（v112 §1.2）：a11y dump 容器 **≠** 生产 pane 盒（两档都恰好多出 48px 带高）。

### 1.1 本轮沿用的注入缝（seam）

v113 用的那条缝本轮继续用，并且**扩到两个类**：
把 `regionPaddingDp()` 这个**取值桩**临时改成返回 v110 之前的旧拆分 `4.0 to 8.0`。
`ui/session/**` 期间零改动，跑完按 md5 逐位还原。

---

## 2. v113 §6.1 清单 → 本轮裁定总表

| # | 位置 | 判定 | 处置 | 证据 |
|---|---|---|---|---|
| **R1** | `V107Test.kt:108-111` 四个 `assertEquals(48, 48)` | 🔴 确凿假绊线 | ✅ **改**：走 `MIN_INTERACTIVE_COMPONENT_PX` + `composerChildUnionHeightPx` | §3.1 / §4.2 |
| **R2** | `V107Test.kt:115-118` 两个字面量 `assertTrue` | 🔴 确凿假绊线 | ✅ **改**：`val` 去字面量化，记录转 `assertEquals`，**新增生产版因果主张** | §3.1 / §4.2 |
| **R3** | `V107Test.kt:73` `position == criterion1` | 🔴 确凿假绊线 | ✅ **改**：同一主张在**同一方法内**用生产算术重做 | §3.1 / §4.2 |
| **R4** | `V105Test.kt:305` `assertTrue(218 > 213)` | 🔴 确凿假绊线 | ✅ **改**：转记录 `assertEquals`，**新增生产活判据 + 反向腿** | §3.2 / §4.2 |
| **R5** | `V106Test.kt:331-334` | ⚠️ 边界 | 🟢 **保留**（记账合法，方法内有生产调用） | §5.1 |
| **R6** | `V109Test.kt:132-135` / `:153-156` | ⚠️ 边界 | 🟢 **保留**（记账合法，方法内有 `composerTopPx` 调用） | §5.2 |
| **R8** | `V107Test.kt:53-101`（含 768 档） | ⚠️ 边界 | 🔵 **改**（拿不准按"改"处理）：768 档也补生产腿 | §3.1 / §4.2 |
| R7 | `V104Test.kt:241-246` | ✅ 合法观测值固定 | 原样保留 | — |

---

## 3. 改动 diff

**改动文件 4 个，全在 `app/src/test/**`。生产代码 diff：无。**

### 3.1 `ComposerPositionDecompositionV107Test.kt`（R1 + R2 + R3 + R8）

**(a) 新增生产取值缝**（与 v113 同款，`:108`/`:1301`/`:1330`/`:1340`/`:1426`/`:1531`）：

```diff
+import com.xiufeigo.opencodeshell.ui.session.SessionPaneLayout
+import java.io.File
+
+    private fun source(relative: String): String =
+        listOf(File(relative), File("app/$relative"))
+            .firstOrNull { it.isFile }?.readText()?.replace("\r\n", "\n")
+            ?: error("missing $relative")
+
+    private val sessionScreen = source("src/main/java/…/ui/session/SessionScreen.kt")
+
+    private fun regionPaddingDp(): Pair<Double, Double> {
+        val all = Regex("""\.padding\(\s*start = 12\.dp,\s*end = 12\.dp,\s*top = ([0-9.]+)dp,\s*bottom = ([0-9.]+)dp\s*\)""")
+            .findAll(sessionScreen).toList()
+        assertEquals("the composer region's padding line must stay unique and parseable", 1, all.size)
+        val m = all[0]
+        return (m.groupValues[1].toDouble()) to (m.groupValues[2].toDouble())
+    }
+
+    private val paneBox600 = 438
+    private val imeBottom600 = 387
+
+    private fun panePx600(): Int = paneBox600 - SessionPaneLayout.believedImeBottomPx(
+        visibleBoxPx = paneBox600, floorPx = 0, imeBottomPx = imeBottom600,
+        essentialPx = SessionPaneLayout.PANE_ESSENTIAL_HEIGHT.value.toInt(),
+    )
+
+    private fun criterion1OnSixHundred(regionTopPx: Int = regionPaddingDp().first.toInt()): Int {
+        val hostPx = SessionPaneLayout.MIN_INTERACTIVE_COMPONENT_PX
+        val panePx = panePx600()
+        val unionBottom = SessionPaneLayout.composerTopPx(panePx, hostPx) + regionTopPx +
+            SessionPaneLayout.composerChildUnionHeightPx(panePx, hostPx, hostPx)
+        return unionBottom - panePx
+    }
```

**(b) R3 —— `:73` 原来的 `assertTrue("all of criterion 1 is position", position == criterion1)`**：

```diff
-        assertTrue("all of criterion 1 is position", position == criterion1)
+        val liveHostPx    = SessionPaneLayout.MIN_INTERACTIVE_COMPONENT_PX      // 48, :108
+        val livePanePx    = panePx600()                                           // 51, :1531
+        val liveHostTop   = SessionPaneLayout.composerTopPx(livePanePx, liveHostPx)          // 3,  :1426
+        val liveHostBottom= SessionPaneLayout.composerBottomPx(livePanePx, liveHostPx)       // 51, :1301
+        val liveUnion     = SessionPaneLayout.composerChildUnionHeightPx(livePanePx, liveHostPx, liveHostPx) // 48, :1330
+        val livePosition  = regionPaddingDp().first.toInt()                       // 0, 源���解析
+        val liveHeight    = liveUnion - liveHostPx
+        val liveCriterion1= liveHostTop + livePosition + liveUnion - liveHostBottom
+        assertEquals("the production pane on the 600 tier is 51 px", 51, livePanePx)
+        assertEquals("the host is production's 48 px minimum interactive component", 48, liveHostPx)
+        assertEquals("the height term is still closed in production", 0, liveHeight)
+        assertTrue("all of today's criterion 1 is position (the height term is 0, production)",
+                   livePosition == liveCriterion1)
+        assertTrue("criterion 1: production unionBottom(${…}) <= paneBottom($liveHostBottom)", … <= …)
```

**(c) R1 —— `:108-111` 四个 `assertEquals(48, 48)`**：

```diff
-        assertEquals(48, 48) // 600: unionPx
-        assertEquals(48, 48) // 768: unionPx
-        assertEquals(48, 48) // 600: hostPx
-        assertEquals(48, 48) // 768: hostPx
+        val hostPx600Live = SessionPaneLayout.MIN_INTERACTIVE_COMPONENT_PX   // 48, :108
+        val hostPx768Live = SessionPaneLayout.MIN_INTERACTIVE_COMPONENT_PX   // 48, :108
+        val union600Live  = SessionPaneLayout.composerChildUnionHeightPx(panePx(), hostPx600Live, hostPx600Live)
+        val union768Live  = SessionPaneLayout.composerChildUnionHeightPx(paneHeight768, hostPx768Live, hostPx768Live)
+        assertEquals("600: the production union height == the host ⇒ height term 0", hostPx600Live, union600Live)
+        assertEquals("768: the production union height == the host ⇒ height term 0", hostPx768Live, union768Live)
+        assertEquals("768 stacked: the production union still fits its 94 px host exactly", unionPx768,
+            SessionPaneLayout.composerChildUnionHeightPx(paneHeight768, hostPx768, unionPx768))
```

**(d) R2 —— `:115-118` 两个字面量 `assertTrue`**：

```diff
-        val delta600 = 5 // 600 tier criterion 1
-        val delta768 = 2 // 768 tier criterion 1
-        assertTrue("identical heights cannot produce different overshoots", delta600 != delta768)
-        assertTrue("600 is the composer's own placement, 768 is the pane's height", delta600 > delta768)
+        val delta600 = 5 // v98 device reading — record
+        val delta768 = 2 // v98 device reading — record
+        assertEquals("v98 record: the 600 tier overran by 5", 5, delta600)
+        assertEquals("v98 record: the 768 tier overran by 2", 2, delta768)
+        assertTrue("the two tiers' v98 criteria really did differ", delta600 != delta768)
+        // …and the causal claim re-made on production:
+        val live600 = criterion1OnSixHundred()                     // 0  (production)
+        val live768 = unionBottomAbsOnSevenSixEight() - imeTop768   // +2 (production)
+        assertEquals("600 production: nothing is charged to the 600 tier any more (v110 took the +5 to 0)", 0, live600)
+        assertEquals("768 production: the +2 is still there, and it is the pane's", 2, live768)
+        assertTrue("production: the two tiers' criteria still differ", live600 != live768)
+        assertTrue("criterion 1: the 600 tier's production union bottom is inside the pane's", live600 <= 0)
```

**(e) R8 —— 768 档补生产腿**（`unionBottom` 走 `composerChildUnionBottomPx`，归属分解 `-6 + 8 = +2` 走生产）：

```diff
+        val liveUnionBottom   = unionBottomAbsOnSevenSixEight()                                   // 296
+        val liveHostBottom768 = SessionPaneLayout.composerBottomPx(paneHeight768, hostPx768)      // 140
+        val liveSlack         = liveUnionBottom - (paneTop768 + liveHostBottom768)                // -6
+        val livePaneOverrun   = (paneTop768 + paneHeight768) - imeTop768                           // +8
+        assertEquals("768 production: the host is bottom-flush with the pane", paneHeight768, liveHostBottom768)
+        assertEquals("768 production: the union reproduces the device's 296", 296, liveUnionBottom)
+        assertEquals("768 production: the union is 6 px inside the pane bottom", -6, liveSlack)
+        assertEquals("768 production: the pane bottom sits 8 px below imeTop", 8, livePaneOverrun)
+        assertEquals("768 production: the +2 decomposes into the pane's own over-ask", 2, liveSlack + livePaneOverrun)
+        assertTrue("768: the composer never reaches the ime top on its own", liveUnionBottom < (paneTop768 + liveHostBottom768))
```

**(f) 新增反向腿**（常驻，不是演示一次就完）：

```diff
+    @Test
+    fun theCriterionOneTripwireDetectsTheOldBandTopPaddingOnTheSixHundredTier() {
+        val today = criterion1OnSixHundred()
+        val withOldTopPadding = criterion1OnSixHundred(regionTopPx = 4)
+        assertTrue("a restored `top = 4.dp` must break criterion 1 (was … vs …)", withOldTopPadding > 0)
+        assertEquals("…by exactly the 600 tier's own 4 px band offset, not some other amount", 4, withOldTopPadding)
+        assertEquals("…and with the device's +1 sub-pixel placement it lands on the v98 reading 218", 218, (withOldTopPadding + 1) + (paneTopAbs() + panePx600()))
+        assertTrue("today's production must hold criterion 1 (was $today)", today <= 0)
+        assertEquals("today the 600 tier's union bottom is flush with the pane bottom", 0, today)
+    }
```

### 3.2 `ComposerBandTouchTargetV105Test.kt`（R4）

```diff
-        assertTrue("the device's union bottom 218 > host bottom 213", 218 > 213)
+        assertEquals("the device's union bottom 218 overran the host bottom 213 by 5", 5, 218 - 213)
```

```diff
+    private val sessionScreen = source("src/main/java/…/ui/session/SessionScreen.kt")
+    private fun regionPaddingDp(): Pair<Double, Double> { …解析 SessionScreen.kt 的 padding 行，断言唯一… }
+
+    @Test
+    fun theDeviceOverrunIsGoneInProductionAndItsTripwireCanStillGoRed() {
+        val hostPx = SessionPaneLayout.MIN_INTERACTIVE_COMPONENT_PX          // 48, :108
+        val panePx = 438 - SessionPaneLayout.believedImeBottomPx(438, 0, 387, PANE_ESSENTIAL_HEIGHT) // 51, :1531
+        val paneBottom = CHROME_PX + panePx                                   // 213
+        val regionTopPx = regionPaddingDp().first.toInt()                     // 0, 源码解析
+        val unionBottom = CHROME_PX + regionTopPx + SessionPaneLayout.composerChildUnionBottomPx(panePx, hostPx, hostPx) // 213
+        assertTrue("unionBottom($unionBottom) <= paneBottom($paneBottom) — the device's 218 > 213 is gone", unionBottom <= paneBottom)
+        assertEquals("and it is flush, 0 px of slack", 0, unionBottom - paneBottom)
+        // 判据之后才放 padding pin —— 这样反向跑时红在**判据**上，不是红在 pin 上
+        assertEquals("today's band top padding is the 0 dp v110 moved it to", 0, regionTopPx)
+        val oldUnionBottom = unionBottom + 4
+        assertTrue("a restored `top = 4.dp` must break criterion 1 (was …)", oldUnionBottom > paneBottom)
+        assertEquals("…by exactly the band's own 4 px, not some other amount", 4, oldUnionBottom - paneBottom)
+        assertEquals("…plus the device's +1 sub-pixel that lands on the v98 reading 218", 218, oldUnionBottom + 1)
+    }
```

---

## 4. 🔴 **反向验证：实跑输出原文（注入旧行为 ⇒ 变红）**

### 4.1 怎么做的（**未改任何生产代码**）

在**测试内**把 `regionPaddingDp()` 这个取值桩，在 **V107 与 V105 两个类里同时**临时改成返回
v110 之前的旧拆分 `4.0 to 8.0`（= 复原 `SessionScreen.kt:1827` 的 `top = 4.dp`），
跑这两个类，然后**按 md5 逐位还原**。

```diff
         val m = all[0]
+        // REVERSE-INJECTION v114: put the pre-v110 split back through the seam
+        return 4.0 to 8.0
-        return (m.groupValues[1].toDouble()) to (m.groupValues[2].toDouble())
```

### 4.2 反向输出原文（**红**）

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest --console=plain `
  --tests "com.xiufeigo.opencodeshell.ComposerPositionDecompositionV107Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerBandTouchTargetV105Test"
```

```
> Task :app:testDebugUnitTest FAILED

ComposerBandTouchTargetV105Test > theDeviceOverrunIsGoneInProductionAndItsTripwireCanStillGoRed FAILED
    java.lang.AssertionError at ComposerBandTouchTargetV105Test.kt:396

ComposerPositionDecompositionV107Test > bothTiersShareChildHeightFortyEightSoHeightCannotExplainTheDelta FAILED
    java.lang.AssertionError at ComposerPositionDecompositionV107Test.kt:336

ComposerPositionDecompositionV107Test > criterionOneSplitsExactlyIntoPositionPlusHeight FAILED
    java.lang.AssertionError at ComposerPositionDecompositionV107Test.kt:214

ComposerPositionDecompositionV107Test > theCriterionOneTripwireDetectsTheOldBandTopPaddingOnTheSixHundredTier FAILED
    java.lang.AssertionError at ComposerPositionDecompositionV107Test.kt:376

10 tests completed, 4 failed

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':app:testDebugUnitTest'.
> There were failing tests. See the report at: file:///C:/…/testDebugUnitTest/index.html

BUILD FAILED in 4s
52 actionable tasks: 2 executed, 50 up-to-date
EXIT=1
```

XML 报告里的失败原文（`FAILMSG` 逐条）：

```
== com.xiufeigo.opencodeshell.ComposerBandTouchTargetV105Test  tests=6 failures=1
   FAILMSG: java.lang.AssertionError: unionBottom(217) <= paneBottom(213) — the device's 218 > 213 is gone
== com.xiufeigo.opencodeshell.ComposerPositionDecompositionV107Test  tests=4 failures=3
   FAILMSG: java.lang.AssertionError: 600 production: nothing is charged to the 600 tier any more (v110 took the +5 to 0) expected:<0> but was:<4>
   FAILMSG: java.lang.AssertionError: criterion 1: production unionBottom(55) <= paneBottom(51)
   FAILMSG: java.lang.AssertionError: today's production must hold criterion 1 (was 4)
```

> ### 抄一行原文（回报用）
> **`criterion 1: production unionBottom(55) <= paneBottom(51)`**
>
> `55 = 3(宿主顶) + 4(旧 `top = 4.dp`) + 48(并集高)`，`51 = 438 − 387`（`believedImeBottomPx` 推得），
> **越界 +4**；再把真机那条 `+1` 亚像素加回去正好落回 v98 读数 `218`（由 §3.2 的常驻
> `assertEquals(218, oldUnionBottom + 1)` 钉住，不靠人记）。
> V105 那条同款：`unionBottom(217) <= paneBottom(213)`。
>
> 旧行为一注入，**两个类的判据立刻红，且红在判据上**（V105 的 padding pin 特意排在判据之后）。

### 4.3 还原校验（生产零改动）

```
注入前  V107_TEST_MD5 = 5833A6BF5E373B451FCA0417BF542DF0
        V105_TEST_MD5 = F9C4E3F0B92FDA08BA0A01B09D3B73DC
        SS_MD5        = 8DA222B0E85B464304A9ADB24A8C05C4   (SessionScreen.kt)
        SPL_MD5       = C00A1E9CC72FE92DACF5730CC2D383BC   (SessionPaneLayout.kt)
还原后  V107_TEST_MD5 = 5833A6BF5E373B451FCA0417BF542DF0   ← 与注入前逐位一致
        V105_TEST_MD5 = F9C4E3F0B92FDA08BA0A01B09D3B73DC   ← 与注入前逐位一致
        SS_MD5        = 8DA222B0E85B464304A9ADB24A8C05C4
        SPL_MD5       = C00A1E9CC72FE92DACF5730CC2D383BC
        REVERSE_MARKER_HITS = 0
```

> `SS_MD5` / `SPL_MD5` 与 **v113 §4.3 记录的值完全相同** ⇒ 生产几何与 IME 路径跨两轮**一字节没动**。

### 4.4 抢锁纪律：一次**失手**（如实记账）

第一次正向跑完，我把删锁写成 `rm -- "$lock"`，`mavis-trash` 拿到的是**未展开的 `$lock` 字面量**，
于是报 `No such file or directory`、`LOCK_REMOVED=False` —— **锁当时还在**。
我立刻发现并用**绝对路径** `rm -- "C:\…\.agent-gradle-lock"` 补删（`mavis-trash: moved to trash`），
之后两次（反向跑完、终验跑完）都是**跑完下一条命令就删**，均已确认 `Test-Path = False`。
未影响任何产物与证据，但纪律要写成：**`rm --` 必须给绝对路径字面量，不能给变量**。

### 4.5 双向性判定

| 方向 | 状态 | 证据 |
|---|---|---|
| **正向**（当前已修行为） | 🟢 **绿** | §5.1 `BUILD SUCCESSFUL in 53s` / 51 tests / 0 failures |
| **反向**（复原 v110 前 `top = 4.dp`，V107） | 🔴 **红** | §4.2 `criterion 1: production unionBottom(55) <= paneBottom(51)` |
| **反向**（同上，V105） | 🔴 **红** | §4.2 `unionBottom(217) <= paneBottom(213)` |
| **反向**（`composerTopPx` 改动 `:1426`） | 🔴 **可红** | V107 `liveHostTop` / `criterion1OnSixHundred` 都经它 |
| **反向**（`composerChildUnionHeightPx` 改动 `:1330`） | 🔴 **可红** | R1 的两条等式 + `liveUnion` |
| **反向**（`composerChildUnionBottomPx` 改动 `:1340`） | 🔴 **可红** | V105 活判据 / V107 768 腿 |
| **反向**（`composerBottomPx` 改动 `:1301`） | 🔴 **可红** | V107 `liveHostBottom` / 768 `liveHostBottom768` |
| **反向**（`believedImeBottomPx` 改动 `:1531`） | 🔴 **可红** | `panePx600()` ⇒ 两个类的尺子都动 |
| **反向**（`SessionScreen.kt:1827` padding 行改写） | 🔴 **可红** | `regionPaddingDp()` **解析该行**且断言其唯一性 |

**⇒ 这 4 条不再是「按构造永不触发」的绊线：对「修好」与「回退」两个方向都有反应。**

---

## 5. 3 条边界项裁定

### 5.1 R5 —— `ComposerBandTallEnoughV106Test.kt:331-334`：**🟢 保留**

| 项 | 判读 |
|---|---|
| 形态 | `assertEquals("真机 600 档 ①：unionBottom 218 > hostBottom(imeTop) 213 ⇒ +5", 5, 218 - 213)`，是编译期常量，**单独看零侦测力** |
| 是否 fixture | ✅ 是 —— 方法名 `theFivePixelOverflowIsReproducedOnTheDeviceReadingsAndGoneOnTheNewBand` 自带 `DeviceReadings`，注释逐位抄了 `REPORT_COMPOSER_OVERFLOW3.md` 原文 |
| **旁边有没有生产函数断言** | ✅ **有** —— 同一方法的「修后」半段 `Frame(600, 387, bandHeightPx = …)`、`Frame(768, 466, …)`，同类还 pin 了 `SessionPaneLayout.viewportHeightPx(51/140, …)` |
| 裁定 | **合法固定**，原样保留，一个数没动 |
| 本轮动作 | 只在断言上方加了一段 **v114 裁定注释**，写明「零侦测力、只作记录、判据的活版本在 V107/V109」，防止再被当判据引用 |

### 5.2 R6 —— `ComposerUnionBottomP5V109Test.kt:132-135` / `:153-156`：**🟢 保留**

| 项 | 判读 |
|---|---|
| 形态 | `assertTrue(over768 < regionBottomPaddingPx)` / `assertTrue(over600 > regionBottomPaddingPx)`，四个操作数（`4 / 88 / 8 / 94 / 48`）**全是本文件字面量** ⇒ 按构造真 |
| 是否 fixture | ✅ 是 —— 断言的是 **v98 那次 padding 拆分（4+8）的物理后果**；`top = 4.dp` 自 v110 起已不存在，这套拆分**今天不是现场，是历史** |
| **旁边有没有生产函数断言** | ✅ **有** —— **同一个方法内** `SessionPaneLayout.composerTopPx(paneHeight768, 94)`（`:187`）；同类已有 v113 的完整活判据 `theSixHundredTierHoldsThePaneBottomCriterionAgainstProductionArithmetic` 与反向腿 |
| 裁定 | **合法固定**，保留 |
| 本轮动作 | 两条消息前缀统一为 `v98 re-enactment: …`（**数字 `6 < 8` / `12 > 8` 一个没动**），并在方法上方加 v114 裁定注释：写明「按构造真但旁边有生产调用 ⇒ 保留；它们是记录不是判据」 |

### 5.3 R8 —— `ComposerPositionDecompositionV107Test.kt:53-101`：**🔵 改**

- v113 判它是 ⚠️ 边界（「v107 的诊断模型，类头明写 deliberately asserts nothing about a fix」）。
- Lead 兜底规则「**拿不准的按"改"处理**」⇒ 本轮**改**。
- **改法 = 追加生产腿，不删 fixture**：v98 的 `paneTop/paneHeight/hostPx/bandTop/unionBottom/slack/paneOverrun/height`
  分解**逐字保留**，另在 600 档（§3.1b,R3）与 768 档（§3.1e）各加一段**走生产**的重做。
- 类头 KDoc 同步加 v114 段，写明「诊断轨 / 活轨」的区别，以及 **v110 之后 600 档的 position 项是 0、不是 +5** 这个必须面对的事实。

### 5.4 三条边界的共同判读

**Lead 的规则是「fixture 合法，**前提是旁边有至少一条调用生产函数的断言**」，
所以 R5 / R6 的判据是同一条**：它们各自所在的方法里**都已经有**生产函数调用，
记录本身因此获得了「生产一旦变就有人报警」的邻居。本轮把这条边界在**注释里写死**，
让下一轮不必重新推断。

---

## 6. 收尾复扫：假绊线**归零**

扫法（两条机械规则，覆盖 v113 R1–R4 的全部形态）：

```
① assertEquals(<数字字面量>, <数字字面量>[, )]          —— 两侧都是字面量
② assertTrue(<消息>, <字面量> <比较符> <字面量>)       —— 常量比较
③ assertTrue(<消息>, <局部val> <比较符> <局部val>)      —— 两个局部量（补扫）
```

**① 全测试树 `app/src/test/**`：**

```
.\java\com\xiufeigo\opencodeshell\ComposerPositionDecompositionV107Test.kt:54:  (KDoc 文字)
.\java\com\xiufeigo\opencodeshell\ComposerPositionDecompositionV107Test.kt:277: (注释文字)
```

**⇒ 2 条命中，全部是本轮 v114 自己写的 KDoc/注释（描述「曾经是四个 `assertEquals(48,48)`」）。**
**可执行断言里，一个都不剩。**

**② 同上：** 同样只有 2 条命中，都是 v114 注释里复述的原文（`V105Test.kt:324/365`）。

**③ `Composer*Test.kt` 补扫「两个局部量」**（17 条命中）逐条判读：

| 命中 | 判读 |
|---|---|
| `V104:301/442/444/448`、`V103:237/383/384/387/390`、`V109:321/441` | ✅ 合法 —— 两侧都来自 `SessionPaneLayout.*` 生产函数求值（循环变量只是参数） |
| `V108:330`、`V105:499` | ✅ 合法 —— 同上 |
| `V86:157`、`V81:102/104` | ✅ 合法 —— 走 `SessionPaneLayout` / 焦点模型，非本主题且非字面量 |
| **`V107:325`** `assertTrue("the two tiers' v98 criteria really did differ", delta600 != delta768)` | ✅ **合法记录** —— 两侧是 v98 读数 `5`/`2`（fixture），**同一方法内 `:341` 就是生产版**（`live600 != live768`）。符合 Lead 的「旁边必须有生产断言」前提 |
| **`V107:341`** | ✅ **生产版**，本轮新增 |

**④ `Session*Test.kt` 的字面量 `assertEquals`：** `No matches found`。

### 6.1 复扫结论

> **composer / pane 相关 pin 的假绊线已归零。**
> 剩下的「字面量对字面量」只有 **1 条可执行断言**（`V107:325`），
> 它是 **v98 读数记录**、**旁边紧挨着生产断言**（`V107:341`）、且**两档两个方向都记**，
> 符合 Lead 的合法保留条件 ⇒ **不是**假绊线。
> 另有若干**真机观测值固定**（`V104:241-246`、`V106:326-337` 的 `5`/`2`/`4` 等）——
> 按 v112 §2 早已裁定为**合法记录**，本轮不动。

---

## 7. 为什么不降强度

### 7.1 强度逐条对照

| 维度 | 修前 | 修后 | 判读 |
|---|---|---|---|
| `V107Test` tests 数 | 3 | **4** | ↑ |
| `V105Test` tests 数 | 5 | **6** | ↑ |
| 本轮涉及的 7 个 suite 总用例 | 49 | **51** | ↑ |
| 判据来源（4 条确凿项） | **本文件字面量** | `believedImeBottomPx` / `composerTopPx` / `composerBottomPx` / `composerChildUnionHeightPx` / `composerChildUnionBottomPx` / `MIN_INTERACTIVE_COMPONENT_PX` / **`SessionScreen.kt:1827` 解析** | ↑↑ 换成生产 |
| 改生产代码能否侦测 | ❌ **不能** | ✅ **能**（7 条路径，见 §4.5） | ↑↑ **本轮核心** |
| 活判据可红性 | ❌ 按构造真 | ✅ **实跑变红**（§4.2，`BUILD FAILED` / 4 failed） | ↑↑ |
| 历史读数 `218/213/+5/+4/48/51/140/296/302/294/170/208` | 全在 | **全在**，一个数字没动 | ✅ |
| 是否删断言 | — | ❌ **一条没删** | — |
| 是否放宽断言 | — | ❌ **没有**。`assertTrue(5 > 2)` 被移除，但**不是因为它变容易了**，而是因为它**恒真**；它主张的世界（600 档 +5）**自 v110 起不存在**（§3.1d） | — |
| 是否恒真化 | — | ❌ **没有**：4 条新判据**实测会红** | — |
| 容差 | — | **零容差**（精确 `0` / 精确 `+4` / 精确 `218`） | 未引入容差 |
| 新增用户可见字符串 | — | **零** | ✅ |
| 生产代码改动 | — | **零**（md5 §4.3，与 v113 记录同值） | ✅ |

### 7.2 为什么「把 `assertTrue(delta600 > delta768)` 拿掉」不是放宽

- 那条断言主张的是「**600 档是 composer 自己的错、768 档是 pane 的错**」，
  而今天生产算术里 600 档的 `criterion1` 是 **0**（v110 把 `top = 4.dp` 挪到底边），
  **`5 > 2` 在今天的生产里是假的**。
- 继续断言它成立 = 断言一个**不存在的世界**（v112 §5.2 对 `218 > 213` 的同一判读）。
- **处置不是翻成 `<`**，而是：把读数按其本来身份 **record 钉住**（两条 `assertEquals`），
  再把**因果主张本身**落到生产函数上（`live600 == 0` / `live768 == 2` / `live600 != live768` /
  `live600 <= 0`）。**主张一个都没丢，只是换了它的依据。**
- 净效果是**断言变多、判据变活**，不是变松。

### 7.3 一处**如实记账**的判读（不掩盖）

`criterion1OnSixHundred()` 今天返回 **0** —— 600 档的并集底沿与 pane 底沿**恰好齐平**，
**余量为 0 px**。这是**零容差特性**（任何把高度加在带子之上的生产改动立刻翻红），
但它**不等于** v110 真机读到的 `−12`（`COMPOSER_OVERFLOW_FIX8.md` §4.2①）——
两者**不是同一口径**（v112 §1.2 已证 dump 容器 ≠ 生产 pane 盒，差 48px 带高），**不可直接相减**。

---

## 8. 回归测试证据

### 8.1 终验命令（前台跑完；抢锁 → 跑 → **立刻删锁**）

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest --console=plain `
  --tests "com.xiufeigo.opencodeshell.ComposerPositionDecompositionV107Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerBandTouchTargetV105Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerBandTallEnoughV106Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerUnionBottomP5V109Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerPaneBottomCriterionV108Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerChildUnionV104Test" `
  --tests "com.xiufeigo.opencodeshell.ComposerOverflowV103Test"
```

**gradle 输出原文**：

```
BUILD SUCCESSFUL in 53s
52 actionable tasks: 2 executed, 50 up-to-date
EXIT=0
```

**XML 报告汇总**（`app/build/test-results/testDebugUnitTest/TEST-*.xml` 逐文件读出）：

```
com.xiufeigo.opencodeshell.ComposerBandTallEnoughV106Test            tests=7  failures=0 errors=0
com.xiufeigo.opencodeshell.ComposerBandTouchTargetV105Test           tests=6  failures=0 errors=0
com.xiufeigo.opencodeshell.ComposerChildUnionV104Test                tests=7  failures=0 errors=0
com.xiufeigo.opencodeshell.ComposerOverflowV103Test                  tests=8  failures=0 errors=0
com.xiufeigo.opencodeshell.ComposerPaneBottomCriterionV108Test       tests=8  failures=0 errors=0
com.xiufeigo.opencodeshell.ComposerPositionDecompositionV107Test    tests=4  failures=0 errors=0
com.xiufeigo.opencodeshell.ComposerUnionBottomP5V109Test            tests=11 failures=0 errors=0
TOTAL tests=51 failures=0 errors=0
```

**7 suites / 51 tests / 0 failures / 0 errors，`BUILD SUCCESSFUL`，`EXIT=0`。**

### 8.2 本轮跑过的 3 次 gradle（全部单类/少类、全部前台、全部按纪律抢锁）

| # | 目的 | 结果 |
|---|---|---|
| 1 | 正向首跑（V107 + V105） | `BUILD SUCCESSFUL in 4s` / `EXIT=0` / V107 4 tests、V105 6 tests |
| 2 | **反向注入 R1**（V107 + V105 同时注入） | `BUILD FAILED in 4s` / `EXIT=1` / `10 tests completed, 4 failed` |
| 3 | 还原后终验（7 类） | `BUILD SUCCESSFUL in 53s` / `EXIT=0` / **51 tests / 0 failures** |

**未** assembleDebug / clean / 全量 / 装机。

---

## 9. 未闭环（如实记账）

1. **本轮无真机读数** —— 环境纪律禁止 `assembleDebug` / 装机。
   正向方向是**生产算术 + 源码真值**，不是新的设备读数。
2. **正向余量 `0` 与 v110 真机 `−12` 口径不同，不可直接相减**（v113 §5.4 结转，§7.3 再次记账）。
3. **a11y dump 容器 ↔ 生产 pane 盒节点的精确身份仍未证到行**（v112 §7#4 结转，两轮未重开）。
4. **`R5` / `R6` 两条记录断言本身永远不会有侦测力**（这是 fixture 的定义，不是缺陷）；
   它们的侦测力**全部押在同方法/同类的生产断言上**。若将来有人把那些生产断言删掉，
   这两条会静默退化成纯装饰 —— 建议下轮在 V106/V109 各补一条**跨类**的活判据。
5. **V107 类头那张 v98 读数表（`600 / 768` 两行）没有随 v110 更新**：
   记录保留是对的（它是 v98 现场），但读者可能误读成"当前读数"。
   本轮已在 KDoc 里加了 v110 段落说明，**表格本身刻意未动**。
6. **V109 的 `theSixHundredTierIsOpenOnEveryQueueDepthAndPinnedAsSuch` 方法名仍带 `Open`** ——
   v113 已把消息改成 `v98 re-enactment (NOT an open item)`，但**方法名**没改（改名会动到
   `--tests` 过滤与历史报告引用）。留待下轮一并处理。
7. **抢锁时 `rm -- "$lock"` 变量不展开**（§4.4）—— 纪律更正为「`rm --` 给绝对路径字面量」。

---

## 10. 回报（给 Lead）

- **状态**：**本轮交付完成** —— v113 §6.1 的 **4 条确凿假绊线全部改为调生产函数的活判据**、
  **3 条边界项逐条裁定并执行**、**反向验证实跑变红**、**历史读数一条没丢**、
  **收尾复扫假绊线归零**。**7 suites / 51 tests / 0 failures / 0 errors**，`BUILD SUCCESSFUL in 53s`，`EXIT=0`。
- **「4 条是否清完」**：**清完了**。
  - `V107:108-111` 四个 `assertEquals(48,48)` → 走 `MIN_INTERACTIVE_COMPONENT_PX` + `composerChildUnionHeightPx`
  - `V107:115-118` 两个字面量 `assertTrue` → 读数转 `assertEquals` 记录，**新增生产版因果主张**（`live600==0` / `live768==2` / `live600!=live768` / `live600<=0`）
  - `V107:73` `position == criterion1` → 同方法内改走 `composerTopPx` / `composerBottomPx` / `composerChildUnionHeightPx` / padding 解析
  - `V105:305` `assertTrue(218 > 213)` → 转 `assertEquals` 记录，**新增生产活判据 + 反向腿**
- **「3 条边界项裁定结果」**：**R5 保留**（V106，方法内有 `Frame` / `viewportHeightPx`）、
  **R6 保留**（V109，同方法内有 `composerTopPx`；消息统一标 `v98 re-enactment:`，数字未动）、
  **R8 改**（按「拿不准按改处理」：768 档补生产腿，诊断记录逐字保留）。
- **「反向验证是否变红」**：**变红**。原文一行：
  **`criterion 1: production unionBottom(55) <= paneBottom(51)`**
  （`BUILD FAILED in 4s`，`EXIT=1`，`10 tests completed, 4 failed`；V105 同款
  `unionBottom(217) <= paneBottom(213)`）。注入方式为**测试内取值桩** `regionPaddingDp()`
  在两个类里临时返回旧拆分 `4.0 to 8.0`，**`ui/session/**` 零改动**，
  还原后 md5 `5833A6BF…` / `F9C4E3F0…` 与注入前逐位一致，`REVERSE-INJECTION` **0 命中**。
- **「复扫后假绊线是否归零」**：**归零**。全 `app/src/test/**` 的「字面量对字面量」与
  「常量 `assertTrue`」两条机械规则，**可执行断言里 0 条残留**（4 条命中全是本轮 v114 自己写的注释文字）。
  补扫「两个局部量」17 条，全部合法；唯一形似的是 `V107:325` 的 v98 读数记录，
  **同方法内 `:341` 就是生产版断言** ⇒ 合 Lead 的合法保留条件。
- **改了哪些文件**：**4 个，全在 `app/src/test/**`** ——
  `ComposerPositionDecompositionV107Test.kt`、`ComposerBandTouchTargetV105Test.kt`、
  `ComposerBandTallEnoughV106Test.kt`（仅裁定注释）、`ComposerUnionBottomP5V109Test.kt`（仅裁定注释 + 消息前缀）。
  **生产代码 `ui/session/**` 零改动**（md5 与 v113 记录同值）；禁令文件一个没碰；**零新增用户可见字符串**。
- **命令与用例数**：
  `:app:testDebugUnitTest --console=plain --tests "…V107Test" --tests "…V105Test" --tests "…V106Test" --tests "…V109Test" --tests "…V108Test" --tests "…V104Test" --tests "…V103Test"`
  ⇒ **7 suites / 51 tests / 0 failures / 0 errors**，`BUILD SUCCESSFUL in 53s`，`EXIT=0`。
  本轮共 **3 次** gradle（正向 4s / 反向注入 4s 红 / 还原终验 53s），
  全部**单类或少类**、全部**前台**、全部按纪律抢锁；**未** assembleDebug / clean / 全量 / 装机。
- **未闭环**：7 条，见 §9 —— 最重要的是 §9#4（R5/R6 的侦测力**全部押在旁侧生产断言**上，
  将来删掉那些就会静默退化）与 §9#6（V109 方法名仍带 `Open`，下轮一并处理）。
- **报告**：`research/v97_fix/TRIPWIRE_CRITERION_FIX3.md`
