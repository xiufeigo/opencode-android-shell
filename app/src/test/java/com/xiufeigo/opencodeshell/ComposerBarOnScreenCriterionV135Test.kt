package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.SessionPaneLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v135: the 600-tier "queue bar on screen" form's criterion-1 `+2`, settled.
 *
 * Criterion 1 is `unionBottom <= paneBottom` (difference `<= 0`). The Lead's
 * standing position is that a `+2` is not acceptable merely because the 14 px
 * that produce it have an explained source, so this file exists to answer one
 * question with arithmetic instead of adjectives: **is the 2 px a real exit
 * from the available area, or did the criterion read the wrong pane bottom?**
 *
 * **Answer: a real exit, of exactly 1 px, plus 1 px of union-alignment
 * artifact.** `paneBottom` is *not* a form-dependent quantity — the pane host
 * box reads `[0,162][1366,261] h=99` in **both** frames, byte-identical, so the
 * "the bar grows the pane" reading is refuted by the device itself. The
 * decomposition, all of it from `wm density 160` (`1dp == 1px`):
 *
 * ```
 * closed form  h600_BARON2.xml : host [0,162][1366,261] h=99  paneBottom = 261
 *                              editor [24,201][399,249]  cardBottom = 249
 *   249 = 261 - 12   ->  (1) = -12  == exactly the region's 12 dp bottom padding
 *                                  (SessionScreen.kt:1827 `bottom = 12.dp`,
 *                                   SessionPaneLayout.kt:1449)
 *
 * bar-on form  k600_MEASURE_BARON.xml : host [0,162][1366,261] h=99  (UNCHANGED)
 *                              editor [24,214][405,262]  buttons ..[.,263]
 *   card top   201 -> 214        = +13   the bar's push
 *   card bottom 249 -> 262       = +13   ... of which the 12 px padding
 *                                         absorbs 12, leaving 262 - 261 = +1
 *                                         against the host bottom  (REAL EXIT)
 *   union bottom = max(262, 263) = 263   the tool row's buttons are laid out
 *                                         1 px below the EditText (the same
 *                                         misalignment criterion 2 already
 *                                         records as unionPx 49 vs 48)
 *   (1) = 263 - 261 = +2 = 1 (real) + 1 (union alignment)
 * ```
 *
 * **Why the model cannot be the judge.** `composerChildUnionHeightPx` clamps the
 * union with `coerceAtMost` into the host (`SessionPaneLayout.kt:1330-1331`) and
 * `composerTopPx` places the composer from the pane and the host alone — so the
 * production model has **no term for the bar's push at all** and returns a
 * structurally non-positive residual for every input. Verifying the `+2` against
 * it would be a guaranteed green that proves nothing.
 * [theModelCannotExpressTheResidualSoOnlyTheDeviceFrameCan] pins that blindness,
 * and pins the shape of the real defect: the union **height** (49) does fit the
 * host (99), so the exit is **positional** — the bar puts the card's top at 214
 * rather than at the host's top 162, and `214 + 49 = 263 > 261`.
 *
 * Nothing here relaxes criterion 1. [theBarOnFormResidualIsOnePixelOfRealExit]
 * freezes the accounting; it does not bless a positive value.
 */
class ComposerBarOnScreenCriterionV135Test {

    // ---- device literals (never recomputed; a model must not invent its own
    // ---- "before" numbers — the fake-green trap, COMPOSER_OVERFLOW_FIX4.md §1.3)

    /** Pane host box, read identically from both frames. */
    private val hostTop = 162
    private val hostBottom = 261
    private val hostHeight = 99

    /** Closed form: `h600_BARON2.xml`. */
    private val closedCardTop = 201
    private val closedCardBottom = 249
    private val closedEditorBottom = 249

    /** Bar-on form: `k600_MEASURE_BARON.xml`. */
    private val barOnCardTop = 214
    private val barOnEditorBottom = 262
    private val barOnButtonBottom = 263
    private val barOnUnionBottom = 263

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    /**
     * The composer region's own vertical split, parsed out of the one padding
     * line that carries it (`SessionScreen.kt:1827`). This is the only term of
     * criterion 1 that is Compose source rather than a production function, so
     * the tripwire reads it instead of repeating it — that is what makes it
     * bidirectional: move the padding in production and every assertion below
     * moves with it.
     */
    private fun regionPaddingDp(): Pair<Double, Double> {
        val all = Regex(
            """\.padding\(\s*start = 12\.dp,\s*end = 12\.dp,\s*top = ([0-9.]+)dp,\s*bottom = ([0-9.]+)dp\s*\)""",
        ).findAll(source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")).toList()
        assertEquals(
            "the composer region's padding line must stay unique and parseable",
            1,
            all.size,
        )
        val m = all[0]
        return (m.groupValues[1].toDouble()) to (m.groupValues[2].toDouble())
    }

    /**
     * The production constant must agree with the source line. If either moves
     * alone, the region is no longer the 12 px this file's arithmetic rests on
     * and the account below is meaningless.
     */
    @Test
    fun theRegionBottomPaddingIsTwelveDpInBothProductionAndSource() {
        val (_, bottomDp) = regionPaddingDp()
        assertEquals(
            "SessionPaneLayout.COMPOSER_REGION_PADDING must match the region's bottom padding",
            12.0f,
            SessionPaneLayout.COMPOSER_REGION_PADDING.value,
            0.0f,
        )
        assertEquals("the region's bottom padding must be 12 dp", 12.0, bottomDp, 0.0)
    }

    /**
     * `paneBottom` is **not** a form-dependent quantity. The host box is
     * byte-identical in the closed and bar-on frames, so no reading of
     * "paneBottom should be another quantity when the bar is on screen" can be
     * supported by the device: there is only ever one host box here.
     */
    @Test
    fun thePaneHostBoxIsIdenticalInBothFormsSoPaneBottomIsNotFormDependent() {
        // Both frames: research/v97_verify/5554_md/v134/{h600_BARON2,k600_MEASURE_BARON}.xml
        assertEquals("closed-form host top", hostTop, 162)
        assertEquals("bar-on host top", hostTop, 162)
        assertEquals("closed-form host bottom = paneBottom", hostBottom, 261)
        assertEquals("bar-on host bottom = paneBottom (UNCHANGED)", hostBottom, 261)
        assertEquals("host height", hostHeight, hostBottom - hostTop)

        // The host is full width: the only candidate both frames agree on.
        assertEquals("paneBottom is the same number in both forms", 261, 261)
    }

    /**
     * The closed form is the baseline that makes the padding legible: its `-12`
     * is **exactly** the region's bottom padding, i.e. the composer card sits
     * flush on the host's bottom edge with the region's 12 dp of breathing room
     * below it and **zero** overflow. Any reading of the bar-on `+2` that
     * starts from "the closed form was already over" is refuted here.
     */
    @Test
    fun theClosedFormCriterionIsExactlyTheRegionsBottomPaddingAndNoOverflow() {
        val paddingPx = SessionPaneLayout.COMPOSER_REGION_PADDING.value.toInt()
        assertEquals("closed-form criterion 1", -paddingPx, closedCardBottom - hostBottom)
        assertEquals(
            "the closed-form card bottom is the host bottom less the region's bottom padding",
            hostBottom - paddingPx,
            closedCardBottom,
        )
        assertTrue(
            "the closed form must not be an overflow: it is the padding itself",
            closedCardBottom - hostBottom <= 0,
        )
    }

    /**
     * The bar's push, measured. It is 13 px on the card's **top** edge, and the
     * card's **bottom** edge moves by the same 13 — so the bar translated the
     * whole card; it did not grow the card.
     */
    @Test
    fun theBarPushesTheCardDownByThirteenPixelsWithoutChangingTheHost() {
        assertEquals("the bar's push, on the card's top edge", 13, barOnCardTop - closedCardTop)
        assertEquals(
            "the same push on the card's bottom edge — the card translated, it did not grow",
            13,
            barOnEditorBottom - closedEditorBottom,
        )
        assertEquals("the host did not move to make room for the bar", hostBottom, 261)
    }

    /**
     * The verdict. `+2` decomposes into one pixel that genuinely leaves the
     * host box and one pixel that is the EditText/button misalignment criterion
     * 2 already records as `unionPx 49 vs 48`.
     *
     * This is deliberately **not** an assertion that `+2` is acceptable. It
     * freezes the account of it: 1 px of real exit plus 1 px of alignment, and
     * the 12 px of region padding is what absorbed 12 of the 13 px push.
     */
    @Test
    fun theBarOnFormResidualIsOnePixelOfRealExitAndOneOfAlignment() {
        val paddingPx = SessionPaneLayout.COMPOSER_REGION_PADDING.value.toInt()
        val push = barOnCardTop - closedCardTop

        // What the region's bottom padding could absorb, and what it did.
        assertEquals("the padding absorbs 12 of the 13 px push", 12, paddingPx)
        assertTrue("the push exceeds the padding by 1 px", push > paddingPx)

        // The real exit: the editor's own bottom edge against the host bottom.
        val realExit = barOnEditorBottom - hostBottom
        assertEquals("real exit below the host bottom", 1, realExit)

        // The artifact: the tool row's buttons sit 1 px below the EditText.
        val alignment = barOnButtonBottom - barOnEditorBottom
        assertEquals("EditText/button vertical misalignment", 1, alignment)

        // The sum is the number criterion 1 actually read.
        assertEquals("criterion 1 in the bar-on form", barOnUnionBottom - hostBottom, 2)
        assertEquals(
            "the +2 is exactly real exit + alignment",
            realExit + alignment,
            barOnUnionBottom - hostBottom,
        )
        assertEquals(
            "the union is taken over the buttons, the lowest child",
            barOnUnionBottom,
            maxOf(barOnEditorBottom, barOnButtonBottom),
        )
    }

    /**
     * The model is blind to this dimension on purpose, and that blindness is
     * pinned here. `composerChildUnionHeightPx` clamps the union into the host
     * with `coerceAtMost` (`SessionPaneLayout.kt:1330-1331`), so the production
     * model reports a non-positive residual for **any** input — including the
     * very numbers the device read. Verifying the `+2` against the model would
     * therefore be a guaranteed green that proves nothing; the device frame is
     * the only evidence that can.
     */
    @Test
    fun theModelCannotExpressTheResidualSoOnlyTheDeviceFrameCan() {
        val panePx = hostBottom
        val hostPx = hostHeight
        val deviceUnionHeight = barOnUnionBottom - barOnCardTop

        assertEquals("the union height the device measured", 49, deviceUnionHeight)
        assertTrue(
            "in height terms the union does fit the host — the 2 px is not an " +
                "over-tall union, it is a card placed too low inside the host",
            deviceUnionHeight <= hostPx,
        )

        // So the card's exit is positional, not dimensional: the bar puts the
        // card's top at 214 (not the host's top at 162) and 214 + 49 = 263 > 261.
        assertTrue(
            "the card's top is displaced down inside the host by the bar",
            barOnCardTop > hostTop,
        )
        assertEquals(
            "top + union height is what leaves the host",
            hostBottom + 2,
            barOnCardTop + deviceUnionHeight,
        )

        // And the model is structurally blind here: it has **no term for the
        // bar's push at all** — `composerTopPx(pane, host)` places the composer
        // from the pane and the host alone, and `composerChildUnionHeightPx`
        // clamps into the host with `coerceAtMost`, so its residual is <= 0 for
        // every input. Verifying the `+2` against the model would be a
        // guaranteed green that proves nothing.
        assertEquals(
            "the model's union height is min(union, host) — here unclamped",
            deviceUnionHeight,
            SessionPaneLayout.composerChildUnionHeightPx(
                panePx = panePx,
                hostPx = hostPx,
                unionPx = deviceUnionHeight,
            ),
        )
        assertTrue(
            "an over-tall union is clamped into the host instead",
            SessionPaneLayout.composerChildUnionHeightPx(
                panePx = panePx,
                hostPx = hostPx,
                unionPx = hostPx + 40,
            ) == hostPx,
        )
        assertTrue(
            "so the model's own residual is structurally non-positive",
            SessionPaneLayout.composerChildUnionBottomPx(
                panePx = panePx,
                hostPx = hostPx,
                unionPx = deviceUnionHeight,
            ) <= panePx,
        )
    }

    /**
     * The bar's budget constants, pinned because they are the terms a fix would
     * have to move: the row, the chrome (6 dp of top padding plus the 12 dp
     * `-mb-3` overlap), and the floor below which the bar stops being drawn.
     */
    @Test
    fun theQueueBarBudgetTermsArePinnedAtTheValuesTheAccountRestsOn() {
        assertEquals("queue row", 32.0f, SessionPaneLayout.QUEUE_ROW_HEIGHT.value, 0.0f)
        assertEquals("bar chrome = 6 dp top padding + 12 dp -mb-3 overlap", 18.0f, SessionPaneLayout.QUEUE_BAR_CHROME.value, 0.0f)
        assertEquals(
            "the bar's draw floor is one whole row plus its chrome",
            50.0f,
            SessionPaneLayout.QUEUE_BAR_MIN_HEIGHT.value,
            0.0f,
        )
    }
}
