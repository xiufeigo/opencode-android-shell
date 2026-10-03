package com.xiufeigo.opencodeshell

import androidx.compose.ui.unit.dp
import com.xiufeigo.opencodeshell.ui.ShellChromeBudget
import com.xiufeigo.opencodeshell.ui.session.SessionPaneLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v120：把 v119（`DIVERGENCE_LOCATE.md`）留下的两笔账**再啃一轮** ——
 * §7.1 的「60 需真机复测才能归因」与「−8px 静态解释不了」。
 *
 * ## 纪律（沿用 v119，本轮不放松）
 *
 * 1. **生产代码零改动** —— 本类只读生产函数/常量。
 * 2. **不许造假绊线** —— 数值断言的生产侧一律取自 [SessionPaneLayout] /
 *    [ShellChromeBudget] 的**真函数/真常量**；字面量只允许出现在**真机读数**一侧
 *    （`COMPOSER_OVERFLOW_FIX8.md` 原文，`wm density 160` ⇒ **1dp == 1px**）。
 * 3. **只钉「当前生产值 == 偏差值」**，不实现未来态。
 *
 * ## 本轮两条结论
 *
 * * **60 已归因到行，逐位**：`60 = COMPOSER_MIN_HEIGHT(116) − COMPOSER_MINIMAL_HEIGHT(56)`。
 *   设备那一侧 `140 = TIMELINE_MIN_HEIGHT(72) + COMPOSER_MINIMAL_HEIGHT(56) + COMPOSER_REGION_PADDING(12)`
 *   **逐位等于一行形态的地板** ⇒ 生产按**堆叠**收账是唯一的差额来源。
 *   判定链：`SessionScreen.kt:2385-2389` → `SessionPaneLayout.kt:1039-1040` → 阈值 `:1008-1010`（**164 dp**）。
 * * **−8px 已判掉（v124）**：它不是 nav bar floor、不是取整，是**模型用了一个错的
 *   `imeBottomPx`** —— `474`（反推 ime 顶边 294）不是当前 build 的顶边；真机 insets 原文是
 *   `type=ime frame=[0,342][1366,768]` ⇒ `imeBottomPx = 426`。
 *   而 `474 − 426 = 48` **恰好等于导航条** ⇒ 那个 8 是一个 48 的差被错投出来的残影。
 *   同时 `floorPx` 从 `0` 修正为实测的 `48` ⇒ 「多算 144」更正为 **96**。
 *   详见 `research/v97_fix/CRITERION_MODEL_FIX.md`。
 */
class ComposerDivergenceLocate2V120Test {

    // ---- 真机读数（density 160 ⇒ 1dp == 1px；**故意保留的字面量**）----
    /** 768 档：pane 盒 = 606（= 窗口 768 − paneTop 162）。 */
    private val paneTop = 162
    private val paneBox768 = 606
    /** 768 档 ime 底 inset = `768 − imeTop(342)` = **426**（第 3 次终验 §4 insets 原文）。 */
    private val imeBottom768 = 426
    /** 768 档导航条底 inset = `768 − navBarTop(720)` = **48**（同一段 insets 原文）。 */
    private val navFloor768 = 48
    /** 768 档设备真实 pane 盒高（`188 − 48`，`COMPOSER_OVERFLOW_FIX8.md:344`）。 */
    private val devicePane768 = 140
    /**
     * 768 档：**钳位关掉**后生产认为的 pane 盒 = `606 − 426` = **180**。
     *
     * **v124 修正**：v119 §4.2 记的 `132` 是按退役的 `imeBottomPx = 474` 算的；
     * 按实测 426 算，它应当是 `606 − 426 = 180`。
     */
    private val paneClampOff768 = 180

    // ---- 生产量（一律取自生产）----------------------------------------------
    private fun essentialPx(): Int = SessionPaneLayout.PANE_ESSENTIAL_HEIGHT.value.toInt()
    private fun chromeFullPx(): Int = SessionPaneLayout.CHROME_FULL_HEIGHT.value.toInt()
    private fun stackedFloorPx(): Int = SessionPaneLayout.COMPOSER_MIN_HEIGHT.value.toInt()
    private fun minimalFloorPx(): Int = SessionPaneLayout.COMPOSER_MINIMAL_HEIGHT.value.toInt()
    private fun timelineFloorPx(): Int = SessionPaneLayout.TIMELINE_MIN_HEIGHT.value.toInt()
    private fun chromeMinPx(): Int = SessionPaneLayout.CHROME_MIN_HEIGHT.value.toInt()
    private fun timelineMinContentPx(): Int = SessionPaneLayout.TIMELINE_MIN_CONTENT.value.toInt()

    /** `SessionScreen.kt:1827` 的 region 竖向切分，从**生产源码**解析。 */
    private fun regionBottomPaddingPx(): Int {
        val all = Regex(
            """\.padding\(\s*start = 12\.dp,\s*end = 12\.dp,\s*top = ([0-9.]+)dp,\s*bottom = ([0-9.]+)dp\s*\)""",
        ).findAll(productionText("SessionScreen.kt")).toList()
        assertEquals("the composer region's padding line must stay unique and parseable", 1, all.size)
        return all[0].groupValues[2].toDouble().toInt()
    }

    private fun productionText(relative: String): String = listOf(
        File("src/main/java/com/xiufeigo/opencodeshell/ui/session/$relative"),
        File("app/src/main/java/com/xiufeigo/opencodeshell/ui/session/$relative"),
    ).first { it.isFile }.readText().replace("\r\n", "\n")

    private fun uiText(relative: String): String = listOf(
        File("src/main/java/com/xiufeigo/opencodeshell/ui/$relative"),
        File("app/src/main/java/com/xiufeigo/opencodeshell/ui/$relative"),
    ).first { it.isFile }.readText().replace("\r\n", "\n")

    // =======================================================================
    //  §1  60 归因到行：堆叠地板 − 一行地板
    // =======================================================================

    /**
     * §1.1 **60 = 堆叠地板 − 一行地板，逐位。**
     *
     * 设备那一侧的 140 = `72 + 56 + 12`（一行形态地板），生产那一侧的 essential
     * 收的是 `72 + 116 + 12`（堆叠形态地板）⇒ 差 `116 − 56 = 60`。
     * 时间线地板与 region 装饰在**两侧同时出现**，逐项相消 ⇒ 60 只可能来自 composer 那一项。
     */
    @Test
    fun `the sixty is the stacked floor minus the one row floor digit exact`() {
        val timeline = timelineFloorPx()
        val region = regionBottomPaddingPx()

        assertEquals(
            "the timeline floor is TIMELINE_MIN_HEIGHT = 72dp (SessionPaneLayout.kt:165)",
            72,
            timeline,
        );
        assertEquals(
            "the region's bottom decoration is 12dp (SessionScreen.kt:1827)",
            12,
            region,
        );
        assertEquals(
            "the stacked composer's own floor is COMPOSER_MIN_HEIGHT = 116dp (SessionPaneLayout.kt:95)",
            116,
            stackedFloorPx(),
        );
        assertEquals(
            "the one-row composer's own floor is COMPOSER_MINIMAL_HEIGHT = 56dp (SessionPaneLayout.kt:811)",
            56,
            minimalFloorPx(),
        )

        // 两侧的 essential：时间线 + region 逐项相同，只有 composer 那一项不同。
        val stackedEssential = timeline + stackedFloorPx() + region
        val oneRowEssential = timeline + minimalFloorPx() + region

        assertEquals(
            "the stacked form's floor sum is 72 + 116 + 12",
            200,
            stackedEssential,
        );
        assertEquals(
            "the one-row form's floor sum is 72 + 56 + 12 — digit exact with the device's 140",
            devicePane768,
            oneRowEssential,
        );
        assertEquals(
            "so the residual 60 is exactly the composer's stacked-vs-one-row floor",
            stackedFloorPx() - minimalFloorPx(),
            stackedEssential - oneRowEssential,
        );
        assertEquals(
            "…and that residual is the 60 v119 left unaccounted for",
            60,
            essentialPx() - chromeFullPx() - devicePane768,
        );
    }

    /**
     * §1.2 **判定链逐位**：768 判成「堆叠」的**唯一**原因是 `body = 284 ≥ 164`，
     * 而这个 body 是**钳位自己的输出**。
     *
     * `isShortWindow(768)` = false（`ShellChromeBudget.kt:49-50`）⇒ 只由 body 决定；
     * 阈值 = `56 + 44 + 64 = 164`（`SessionPaneLayout.kt:1008-1010`）；
     * body = `paneBox − believed`（`SessionScreen.kt:2384`）= `606 − 322 = 284`。
     */
    @Test
    fun `the seven six eight tier is stacked only because the clamp fed the form decision its own output`() {
        val windowHeight768 = paneBox768 + paneTop

        // ① 短窗项在 768 档不成立 ⇒ 形态**只**由 body 决定。
        assertFalse(
            "a 768dp window is not short (SHORT_WINDOW_DP = 480), so the window term is out",
            ShellChromeBudget.isShortWindow(windowHeight768),
        )

        // ② 阈值：生产真函数的分界线。
        val threshold = minimalFloorPx() + chromeMinPx() + timelineMinContentPx()
        assertEquals(
            "the composerMinimal threshold is 56 + 44 + 64",
            164,
            threshold,
        )

        // ③ body：钳位开 ⇒ paneBox − believed，其中 believed 由生产函数给出。
        //    v124：floor 换成实测的 48 ⇒ 钳位返回 `room + floor` ⇒ body = `essential − floor`。
        val believed = SessionPaneLayout.believedImeBottomPx(
            visibleBoxPx = paneBox768,
            floorPx = navFloor768,
            imeBottomPx = imeBottom768,
            essentialPx = essentialPx(),
        )
        val bodyWhenClamped = (paneBox768 - maxOf(navFloor768, believed)).coerceAtLeast(0)
        assertEquals(
            "with the clamp on, the pane is pinned to PANE_ESSENTIAL_HEIGHT − the measured floor",
            essentialPx() - navFloor768,
            bodyWhenClamped,
        );
        assertEquals(
            "…which is 284 − 48 = 236, still digit exact on the measured inputs",
            236,
            bodyWhenClamped,
        );
        assertTrue(
            "…and that clamped body still clears the 164dp one-row threshold, so 768 goes STACKED",
            bodyWhenClamped >= threshold,
        );
        assertFalse(
            "production's own form decision at 768: stacked, not one-row",
            SessionPaneLayout.composerMinimal(bodyWhenClamped.toDouble().dp),
        );
        assertFalse(
            "…which is exactly SessionScreen.kt:2385-2387's bodyOneRow",
            SessionPaneLayout.composerOneRow(
                ShellChromeBudget.isShortWindow(windowHeight768),
                bodyWhenClamped.toDouble().dp,
            ),
        );

        // ④ 自证循环：body 用的是**钳位的输出**，而 essential 的 116 正是该输出选出的形态。
        assertEquals(
            "the 116 in PANE_ESSENTIAL_HEIGHT is the floor of the form the clamp's own output selected",
            stackedFloorPx(),
            essentialPx() - chromeFullPx() - timelineFloorPx() - regionBottomPaddingPx(),
        );
    }

    /**
     * §1.3 **承重自证：140 只能是一行形态的地板。**
     *
     * 把 `COMPOSER_MIN_HEIGHT` 当未知量 `c`：要求 `(72 + 56 + 12) = 140` 成立时，
     * 反解设备侧那一项 —— 它必须等于 `COMPOSER_MINIMAL_HEIGHT`，
     * 且**不等于** `COMPOSER_MIN_HEIGHT`（否则设备也跑堆叠，60 无从产生）。
     */
    @Test
    fun `one forty is the one row essential so the device can only have been running the minimal form`() {
        val timeline = timelineFloorPx()
        val region = regionBottomPaddingPx()

        // 反解：设备侧 composer 那一项 = 140 − timeline − region。
        val deviceComposerTerm = devicePane768 - timeline - region
        assertEquals(
            "the device's 140 decomposes as 72 + ? + 12 ⇒ the composer term is 56",
            minimalFloorPx(),
            deviceComposerTerm,
        );

        // 设备侧**不等于**堆叠地板（否则 60 这笔账不存在）。
        assertTrue(
            "the device's composer term must NOT be the stacked floor, or the 60 vanishes",
            deviceComposerTerm != stackedFloorPx(),
        );

        // 逐项穷举：只有一行形态的地板能凑出 140。
        var matching = 0
        for (candidate in listOf(minimalFloorPx(), stackedFloorPx())) {
            if (timeline + candidate + region == devicePane768) {
                matching++
                assertEquals("only the minimal floor may reproduce the device's 140", minimalFloorPx(), candidate)
            }
        }
        assertEquals(
            "exactly one of {minimal, stacked} reproduces the device's pane box",
            1,
            matching,
        );

        // 反过来：把 60 当已知量反解堆叠地板，唯一解就是生产的 116。
        var solving = 0
        for (candidate in 40..160) {
            if ((timeline + candidate + region) - devicePane768 == 60) {
                solving++
                assertEquals("only the production stacked floor solves the 60", stackedFloorPx(), candidate)
            }
        }
        assertEquals("exactly one stacked floor in [40,160] solves the 60", 1, solving)
    }

    /**
     * §1.4 **对 v119 §4.1 的修正：只去掉 chrome 修不好 60。**
     *
     * v119 建议「`PANE_ESSENTIAL_HEIGHT` 去掉 `CHROME_FULL_HEIGHT` ⇒ essential 284→200」。
     * 但 200 **仍然 > 164 的阈值** ⇒ 形态**仍然判堆叠** ⇒ 60 **原样不动**。
     * ⇒ 要动 60，必须让形态判定看到**设备侧的真实 body**，而不是钳位的产物。
     */
    @Test
    fun `removing the chrome alone cannot touch the sixty the body still clears the one row threshold`() {
        val threshold = minimalFloorPx() + chromeMinPx() + timelineMinContentPx()
        val essentialWithoutChrome = essentialPx() - chromeFullPx()

        assertEquals(
            "v119's proposal: essential drops from 284 to 200",
            200,
            essentialWithoutChrome,
        );
        assertTrue(
            "…but 200 still clears the 164dp threshold, so the form stays STACKED",
            essentialWithoutChrome >= threshold,
        );
        assertFalse(
            "…so composerMinimal is still false at the v119 proposal's body",
            SessionPaneLayout.composerMinimal(essentialWithoutChrome.toDouble().dp),
        );

        // v124：实测输入下，essential = 200 时**钳位不再触发**，生产原样相信键盘。
        val believedWithoutChrome = SessionPaneLayout.believedImeBottomPx(
            visibleBoxPx = paneBox768,
            floorPx = navFloor768,
            imeBottomPx = imeBottom768,
            essentialPx = essentialWithoutChrome,
        )
        val bodyWithoutChrome = (paneBox768 - maxOf(navFloor768, believedWithoutChrome)).coerceAtLeast(0)
        assertEquals(
            "the 60's own cause is untouched by the proposal: 116 − 56 = 60",
            60,
            stackedFloorPx() - minimalFloorPx(),
        );
        assertEquals(
            "with the measured ime (426) and floor (48), room 406 >= claimed 378 — the clamp does not fire",
            imeBottom768,
            believedWithoutChrome,
        );
        assertEquals(
            "…so the body is 606 − 426 = 180, not 200 — and not the device's 140 either",
            180,
            bodyWithoutChrome,
        );
        assertEquals(
            "the residual vs the device is 40 = (606 − 426) − 140; 48 − 40 is the phantom 8",
            40,
            bodyWithoutChrome - devicePane768,
        );
    }

    // =======================================================================
    //  §2  −8px：v119 把 floorPx 硬写成 0 —— 结构 pin（**不编数值**）
    // =======================================================================

    /**
     * §2.1 **v124：−8px 判掉 —— 它不是 nav bar floor，是一个错的 `imeBottomPx`。**
     *
     * v120 当时只能钉结构（"答案未知，不编数值"）。第 3 次终验把 insets 原文取回来了：
     * `type=ime frame=[0,342][1366,768]` ⇒ `imeBottomPx = 426`，而模型用的是 `474`。
     * `474 − 426 = 48` **恰好等于导航条** ⇒ 那个 8 是「把导航条那一带算进键盘」的残影。
     *
     * 代入实测 `imeBottomPx = 426` 后，钳位关掉的 pane 是 `606 − 426 = **180**`，
     * 对设备的 140 是 `140 − 180 = **−40**` —— **既不是 +8 也不是 0，而 40 = 48 − 8**：
     * 旧口径的 8 与新的 40 相差恰好一个导航条，**同一笔账的两面**。
     *
     * **生产读 floor 的那几行仍然必须原样在**（`SessionScreen.kt:2362-2369`）：它们是对的，
     * 只是 v119 的模型没喂给它真值。⇒ 下面四条源码 pin **一字未改**。
     */
    @Test
    fun `the eight pixel gap was a mis read ime bottom not a navigation bar the measured insets read forty over`() {
        val src = productionText("SessionScreen.kt")

        // 生产侧：floor 来自 navigationBars（真实行，必须还在）。
        assertTrue(
            "SessionScreen.kt:2362 must still read navigationBars' bottom into navBottomPx",
            Regex("""val navBottomPx\s*=\s*WindowInsets\.navigationBars\.getBottom\(density\)""").containsMatchIn(src),
        )
        assertTrue(
            "…and paneFloorPx must still be the one handed to believedImeBottomPx (SessionScreen.kt:2363)",
            Regex("""val paneFloorPx\s*=\s*if \(titlebarBottom && !desktop\) 0 else navBottomPx""").containsMatchIn(src),
        )
        assertTrue(
            "…and it must be passed as floorPx (SessionScreen.kt:2364-2369)",
            Regex("""floorPx\s*=\s*paneFloorPx""").containsMatchIn(src),
        )
        assertTrue(
            "…and paneBottomDp is maxOf(paneFloorPx, believed) — the floor is paid twice on the device side",
            Regex("""maxOf\(paneFloorPx,\s*believedImeBottomPx\)""").containsMatchIn(src),
        )

        // 钳位关掉：按实测 ime（426），生产读到的 pane 盒是 180，不是 132。
        assertEquals(
            "with the clamp off and the measured ime, production reads 606 − 426 = 180 (not 132)",
            paneClampOff768,
            paneBox768 - imeBottom768,
        )
        assertEquals(
            "the retired 132 was 606 − 474, i.e. it came from the mis-read ime bottom, not from the floor",
            132,
            paneBox768 - 474,
        );
        assertEquals(
            "…and the measured reading over-reads the device by 40 = one nav bar (48) less the phantom 8",
            -40,
            devicePane768 - paneClampOff768,
        );
        assertEquals(
            "…so 48 − 40 = 8: the old gap was this same 48px mis booked as 8",
            8,
            navFloor768 - (paneClampOff768 - devicePane768),
        )
    }

    /**
     * §2.2 **结构 pin：§2.3 的读数问题清单必须还在。**
     *
     * **为什么不能更强**：−8px 的**答案未知**。任何把它写成数值的断言都是编造。
     * 这一条保证的是**账不被销掉**（清单不许被悄悄删掉），不是**账已结清** ——
     * 而这正是它此刻**应该**保证的东西。
     * 一旦 `dumpsys window insets` 的 `navigationBars` 读数到手，
     * 本用例**应被替换**为带真实 `navBottomPx` 的数值断言（`DIVERGENCE_LOCATE2.md` §2.3 ①）。
     */
    @Test
    fun `the eight pixel question list is still open and must not be deleted before the dumpsys reading lands`() {
        val report = listOf(
            File("research/v97_fix/DIVERGENCE_LOCATE2.md"),
            File("app/research/v97_fix/DIVERGENCE_LOCATE2.md"),
            File("../research/v97_fix/DIVERGENCE_LOCATE2.md"),
        ).firstOrNull { it.isFile }
        assertTrue("DIVERGENCE_LOCATE2.md must exist to carry the open −8px account", report != null)
        val text = report!!.readText().replace("\r\n", "\n")

        // 五条读数问题，逐条仍在。
        for (needle in listOf(
            "dumpsys window insets",
            "navigationBars",
            "mCurFrame",
            "paneTop",
            "roundToInt",
        )) {
            assertTrue(
                "the −8px reading list must still carry '$needle' — do not delete an open account",
                text.contains(needle),
            )
        }
        assertTrue(
            "the list must still state that ① is the deciding reading",
            text.contains("①"),
        )
        assertTrue(
            "…and must still say the −8px is NOT closed",
            text.contains("−8px **未闭环**") || text.contains("−8px 未闭环"),
        )
    }
}
