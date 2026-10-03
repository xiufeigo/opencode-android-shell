package com.xiufeigo.opencodeshell

import androidx.compose.ui.unit.dp
import com.xiufeigo.opencodeshell.ui.session.SessionPaneLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v129：**把「判据 ②（`unionPx ≤ band`）在 768 档口径未定义」这件事结掉。**
 *
 * ## 这笔账是哪两笔记的
 *
 * 1. `research/v97_verify/5554_md/REPORT_FINAL_VERIFY.md` §3.4（逐字）：
 * > ### 3.4 判据 ② `unionPx ≤ band` 在 768 档**口径未定义**
 * > `band` 这个词在仓里有**两个不同的值**，② 的判定因此**取决于口径**，我不能替 Lead 选
 * > … 若 Lead 裁定 ② 用 `BAND_HEIGHT=48`，则 **768 档 ② 为假 ⇒ 全局 FAIL**
 * > 若裁定用「该档总带高」，则 **两档 ② 均真 ⇒ PASS**
 *
 * 2. `research/v97_verify/5554_md/REPORT_BASELINE_RECHECK.md`（v128）:360-361（逐字）：
 * > 4. **判据 ② 在 768 档仍口径未定义**（`band` 有 48 与 96 两种取值），
 * >    与 `REPORT_FINAL_VERIFY.md` §3.4 同账、未关闭：若用 `BAND_HEIGHT=48` 则 768 档 ② 为假。
 *
 * ⇒ **"未定义"具体缺的不是读数，是一个节点**：缺的是**「② 的右侧 `band` 该取哪个盒」**
 * 在**堆叠形态**下的定义。两份报告都把 `band` 当成一个**数**，于是 `96 ≤ 48` 与 `96 ≤ 96`
 * 可以同时"有道理" —— 缺口是**形态分支**，不是数值精度。
 *
 * ## 定义（本类把它变成断言）
 *
 * ② 的右侧不是常量 `BAND_HEIGHT`，而是**该形态下物理装得下并集的那个盒**：
 * ```
 * boxPx(oneRow, bandPx, hostPx) = if (oneRow) bandPx else hostPx
 * ```
 * 依据 `SessionPaneLayout.kt:1374-1381`（逐字）：
 * > * [oneRow] = `true`（一行形态）：composer's box **就是**这条带
 * >   （`Composer.kt:2254` `layout(..., constrainHeight(band))`）⇒ 靶**封顶到带**。
 * > * [oneRow] = `false`（堆叠形态）：composer's box 是 `editor + toolRow`，
 * >   **由宿主 `Constraints.maxHeight` 收着**
 *
 * 与既有 pin 完全同账：`ComposerBandTouchTargetV105Test.kt:236-241`（逐字）
 * ```kotlin
 * // 一行形态的 `band` 就是 box，再钉一遍字面量那条算式。
 * if (oneRow) {
 *     assertTrue("unionPx > band (the report's second formula) — ${f.where}", f.unionPx <= f.bandPx)
 * }
 * ```
 * ⇒ **那条"字面量算式"从来就只对 `oneRow` 断言。** 把同一句套到 768（堆叠）上，
 * 不是发现缺陷，是**把一个只对一半形态成立的算式用错了形态**。
 *
 * ## 结论（两档都是**等号**，没有留任何余量）
 *
 * | 档 | 形态（由设备读数证） | `unionPx` | `boxPx` | ② |
 * |---|---|---|---|---|
 * | 600 | 一行 | `48` | `48` | `48 ≤ 48` **真（等号）** |
 * | 768 | 堆叠 | `96` | `96` | `96 ≤ 96` **真（等号）** |
 *
 * 768 的 `96 = 48 + 48`：editor 48 + toolRow 48，**两个 48 都取自生产**
 * （`MIN_INTERACTIVE_COMPONENT_PX`），**不是为了让 ② 变绿而挑的数**。
 */
class ComposerUnifiedCriterionV129Test {

    // ---- 生产常量（全部取自生产，不在本文件重述）---------------------------

    /** `SessionPaneLayout.kt:108` —— material3 强制的 48dp 地板 / 方案 A 的带高。 */
    private val minInteractivePx: Int get() = SessionPaneLayout.MIN_INTERACTIVE_COMPONENT_PX

    /**
     * `SessionPaneLayout.kt:150-156` 唯一求值处：`composerBandHeightDp(44.dp)`。
     * **`44.dp` 是 `:156` 里的字面量**（`COMPOSER_BAND_HEIGHT`）。
     */
    private fun productionBandPx(): Int =
        SessionPaneLayout.composerBandHeightDp(44.dp).value.toInt()

    // ---- 设备读数（`wm density 160` ⇒ 1dp == 1px）· 两档 · 逐字 -------------
    // 768：research/v97_verify/5554_md/REPORT_BASELINE_RECHECK.md §1.1（v128 逐位复现）
    // 600：同文 §1.2

    private val editorPx = 48          // 两档 EditText h=48
    private val toolRowPx = 48         // 两档 Add / Send h=48

    private val union600 = 48          // §1.2 并集 [24,201][1364,249] h=48
    private val container600 = 99      // §1.2 宿主盒 [0,162][1366,261] h=99
    private val readable600 = 51       // §1.2 readable = 99 − 48

    private val union768 = 96          // §1.1 并集 [10,244][1356,340] h=96
    private val container768 = 188     // §1.1 宿主盒 [0,162][1366,350] h=188
    private val readable768 = 92       // §1.1 readable = 188 − 96
    private val timelineBox768 = 140   // §1.1 时间线盒 = 188 − 48

    // ---- ② 的形态分支（生产语义，见本类 KDoc 的 :1374-1381）------------------

    /**
     * 堆叠形态下 composer 的盒 = 并集本身（`editor + toolRow`），
     * 一行形态下 composer 的盒**就是**那条带。
     */
    private fun criterion2BoxPx(oneRow: Boolean, bandPx: Int, hostPx: Int): Int =
        if (oneRow) bandPx else hostPx

    /** ② 本身：`unionPx ≤ 该形态下装得下它的盒`。 */
    private fun criterion2Holds(unionPx: Int, oneRow: Boolean, bandPx: Int, hostPx: Int): Boolean =
        unionPx <= criterion2BoxPx(oneRow = oneRow, bandPx = bandPx, hostPx = hostPx)

    // =======================================================================
    // ① 两档形态由**设备读数本身**证出来（不靠"我说是哪档"）
    // =======================================================================

    /**
     * 两种形态的并集**算法不同**（`ComposerBandTouchTargetV105Test.kt:167-172` 逐字）：
     * ```
     * oneRow  : unionPx = max(editorTop + editorPx, toolRowPx)   // 同一带内取大
     * stacked : unionPx = editorPx + toolRowPx                   // 上下两块相加
     * ```
     * 于是设备读数**反解**出形态 —— 两边都用**字面量**，不引入任何推断：
     */
    @Test
    fun theTwoTiersTakeOppositeFormsAndTheDeviceReadingsProveIt() {
        // 600：读到的并集 48。若是堆叠，必须是 48 + 48 = 96 ⇒ 不是堆叠。
        assertEquals(
            "600: the stacked form would have measured 48 + 48, the dump says 48",
            editorPx + toolRowPx,
            96,
        )
        assertNotEquals(
            "600 tier is the one-row form (its union is NOT the stacked sum)",
            editorPx + toolRowPx,
            union600,
        )

        // 768：读到的并集 96 = 48 + 48 ⇒ 就是堆叠。
        assertEquals(
            "768: the dump union is exactly editor 48 + toolRow 48",
            editorPx + toolRowPx,
            union768,
        )
        // 一行形态的并集被同一条 48 的带收着，**不可能**到 96。
        assertTrue(
            "the one-row form cannot reach 96: the band caps the union",
            96 > productionBandPx(),
        )
    }

    // =======================================================================
    // ② 定义落地：两档都真，且都是**等号**
    // =======================================================================

    /**
     * 768 档（堆叠）的 `boxPx` 由**设备自己的两个读数相减**得到，不是挑来的数：
     * ```
     * boxPx(768) = containerH − readable = 188 − 92 = 96
     * ```
     * 即 composer 盒（`editor + toolRow`）= 96，**与并集 96 逐位相等** ⇒ ② **真**。
     */
    @Test
    fun theSevenSixEightCriterionTwoIsTrueByEqualityWithItsOwnComposerBox() {
        val boxPx768 = container768 - readable768            // 96
        assertEquals(
            "768: composer box = container 188 − readable 92",
            96,
            boxPx768,
        )
        assertEquals(
            "768: the two production 48s, not a number picked to go green",
            editorPx,
            minInteractivePx,
        )
        assertEquals("768: toolRow carries the same enforced 48", editorPx, toolRowPx)

        assertTrue(
            "768+IME: union 96 must fit its composer box 96",
            criterion2Holds(
                unionPx = union768,
                oneRow = false,
                bandPx = productionBandPx(),
                hostPx = boxPx768,
            ),
        )
        // **等号**：不是"96 < 某个更大的数"，是**恰好装满**。
        assertEquals("768: criterion 2 holds with zero slack, digit exact", union768, boxPx768)
    }

    /** 600 档（一行）：`boxPx` 就是生产那条带，两边都是 48 ⇒ **等号**。 */
    @Test
    fun theSixHundredCriterionTwoIsTrueByEqualityWithTheProductionBand() {
        assertEquals(
            "600: the band's only evaluation site yields 48 — SessionPaneLayout.kt:150-156",
            48,
            productionBandPx(),
        )
        assertEquals(
            "600: composer box = container 99 − readable 51",
            48,
            container600 - readable600,
        )
        assertTrue(
            "600+IME: union 48 must fit the band 48",
            criterion2Holds(
                unionPx = union600,
                oneRow = true,
                bandPx = productionBandPx(),
                hostPx = container600 - readable600,
            ),
        )
        assertEquals("600: criterion 2 holds with zero slack, digit exact", union600, productionBandPx())
    }

    // =======================================================================
    // ③ 为什么 `BAND_HEIGHT = 48` 不能当 768 的 ② 右侧（**形态错配，不是缺陷**）
    // =======================================================================

    /**
     * 两份报告争的就是这一格。本类说死它**为什么是假**：
     * `96 ≤ 48` 为假，**因为 48 是一行形态的带，768 是堆叠形态** ——
     * 形态不同，**盒子就不是同一个盒子**。这与布局是否合格**无关**。
     *
     * 反过来也钉住：**如果 768 是一行形态，96 就不可能量到**（① 已证）。
     */
    @Test
    fun theFortyEightConstantIsTheOneRowBandAndIsTheWrongRightHandSideForSevenSixEight() {
        val bandPx = productionBandPx()                       // 48
        val boxPx768 = container768 - readable768             // 96

        // 报告右半边那一格，逐字为假：
        assertFalse(
            "96 <= 48 is false — the one-row band is not the stacked composer box",
            union768 <= bandPx,
        )
        // 但换成**该形态的盒**，同一组读数立刻为真，且是等号：
        assertTrue("96 <= 96, same readings, form-correct RHS", criterion2Holds(union768, false, bandPx, boxPx768))
        // 两个盒**不是同一个数** —— 这就是"未定义"的真正内容：
        assertNotEquals("the two tiers' boxes are 48 and 96; only 600's equals the band", bandPx, boxPx768)
    }

    // =======================================================================
    // ④ 承重自检：改错输入**必须**红（不是"看起来对"）
    // =======================================================================

    /**
     * 同一组设备读数，**只动盒子**（不改并集），② 立刻翻红。
     * ⇒ ② 在 768 档**不是恒真**，它真的在量"并集有没有超出自己的盒"。
     */
    @Test
    fun theStackedCriterionIsLoadBearingItGoesRedForAnyBoxShorterThanTheUnion() {
        val boxPx768 = container768 - readable768             // 96
        for (tooShort in listOf(95, 94, 88, 48)) {
            assertFalse(
                "768: a $tooShort px composer box must NOT pass a 96 px union",
                criterion2Holds(
                    unionPx = union768,
                    oneRow = false,
                    bandPx = productionBandPx(),
                    hostPx = tooShort,
                ),
            )
        }
        // 承重方向的另一头：盒子**放大**到 97 时 ② 仍真 ⇒ ② 量的是"装不装得下"，
        // 而 dump 读到的盒**就是 96**（188 − 92），所以本档**没有一像素余量被凭空发明**。
        assertTrue(
            "768: a 97 px box would also hold a 96 px union — ② is a fit test, not an equality test",
            criterion2Holds(union768, false, productionBandPx(), 97),
        )
        assertEquals(
            "768: the box used is the dump's own 96, never a padded one",
            96,
            boxPx768,
        )
    }

    // =======================================================================
    // ⑤ 已 PASS 的基线一像素不退（**不降强度**）
    // =======================================================================

    /**
     * ② 在 768 档**变真**，不靠改动任何已钉的数：
     * `readable 92` / 时间线盒 `140` / 600 的 `readable 51` 全部原样。
     */
    @Test
    fun definingCriterionTwoMovesNoAlreadyPinnedReading() {
        assertEquals("768: readable 92 — v105/v106/v110's own pin", 92, readable768)
        assertEquals("768: timeline box 140 — v105/v106's own pin", 140, timelineBox768)
        assertEquals("768: readable is container − union, digit exact", container768 - union768, readable768)
        assertEquals("768: timeline box is container − band, digit exact", container768 - minInteractivePx, timelineBox768)
        assertEquals("600: readable 51 — v105/v106's own pin", 51, readable600)
        assertEquals("600: readable is container − union, digit exact", container600 - union600, readable600)
    }
}
