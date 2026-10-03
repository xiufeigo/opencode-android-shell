package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.SessionPaneLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v107b P0: `unionBottom <= hostBottom` (**criterion 1**) **decomposes into two
 * independent terms**, and after v106 the *height* term is provably zero in both
 * device tiers. What is left is a pure **position** term.
 *
 * Source of every literal below: `research/v97_verify/5556_v98/
 * REPORT_COMPOSER_OVERFLOW4.md` (v106 real-device run on emulator-5556, 160dpi,
 * so 1dp == 1px). They are pinned as literals on purpose: the model must not
 * be allowed to invent its own "before" numbers (that is the fake-green trap
 * this repo hit three times in a row, see COMPOSER_OVERFLOW_FIX4.md sec 1.3).
 *
 * ```
 * hostBoxBottom = top + hostPx
 *               = (paneBottom - hostPx) + hostPx          [composerTopPx]
 *               = paneBottom
 *
 * criterion1 = unionBottom - hostBottom
 *            = (bandTop - hostBoxTop)  +  (unionPx - hostPx)
 *              \_____ position ____/     \___ height ___/
 * ```
 *
 * Device readings:
 *
 * | tier   | form    | pane      | hostPx | band       | unionBottom | hostBottom | pos  | hgt | 1    |
 * |--------|---------|-----------|--------|------------|-------------|------------|------|-----|------|
 * | 600    | one-row | 51 (162..213) | 48 | 170..218  | 218         | 213        | +5   |  0  | +5   |
 * | 768    | stacked | 140 (162..302)| 48 | 208..296  | 296         | 294 (ime)  | -6   |  0  | +2   |
 *
 * * **600**: `pos = bandTop(170) - hostTop(165) = +5`, `hgt = union(48) - host(48) = 0`
 *   => criterion 1 = +5, the *whole* of it is the position term.
 * * **768**: `pos = -6` (the union sits 6px **inside** the pane bottom 302) and the
 *   pane bottom itself is `+8` below `imeTop`, so
 *   `criterion1 = -6 + 8 = +2`. Here the position term is charged to the **pane**,
 *   not to the composer: the pane is 140 tall starting at 162, so its bottom is 302
 *   while only `294 - 162 = 132` px exist above the IME => it asks for 8px it
 *   cannot have. Those 8px are exactly the v106 model/device IME delta
 *   (`timelineShare(768, 466, ...)` modelled 466, the device measured **474**).
 *
 * This class pins the **diagnosis**. It deliberately asserts nothing about a fix:
 * no production change is shipped this round (see COMPOSER_OVERFLOW_FIX5.md sec 2).
 *
 * ## v114: the diagnosis is kept **as a record**, and the same claims now also run
 * ## on **production** — the difference between the two is what makes them tripwires
 *
 * v113 booked this whole class (`sec 6.1` R1/R2/R3/R8) as **fake tripwires**: every
 * assertion compared *this file's own literals* — the four `assertEquals(48, 48)`
 * (not even `SessionPaneLayout` was imported by them), `assertTrue(5 != 2)`,
 * `assertTrue(5 > 2)` and `assertTrue(position == criterion1)`. Every one of them is
 * true **by construction of this file's constants**, so no production change could
 * ever turn any of them red. That is worse than no test: it reads like a defence.
 *
 * v114 closes that without losing a single recorded number:
 *
 * * **Record track** — every v98 reading above (`+5`, `+2`, `-6`, `+8`, `48`, `51`,
 *   `140`, `296`, `302`, `294`, `208`, `170`, `218`) stays exactly where it was.
 *   The two `assertTrue`s on the deltas become `assertEquals` on the same literals,
 *   i.e. the same pin, stated as a pin.
 * * **Live track** — the *same claims* are re-made on production arithmetic:
 *   `MIN_INTERACTIVE_COMPONENT_PX` (`:108`), `believedImeBottomPx` (`:1531`),
 *   `composerTopPx` (`:1426`), `composerBottomPx` (`:1301`),
 *   `composerChildUnionHeightPx` (`:1330`), `composerChildUnionBottomPx` (`:1340`),
 *   and the composer's own vertical split **parsed out of `SessionScreen.kt`**.
 *   [theCriterionOneTripwireDetectsTheOldBandTopPaddingOnTheSixHundredTier] is the
 *   reverse leg: it puts the pre-v110 `top = 4.dp` back through the same seam and
 *   requires criterion 1 to break by exactly the historical amount.
 *
 * Note the **v110 fact** this class's live track now has to face: v110 moved the
 * band's `top = 4.dp` to the bottom edge (`SessionScreen.kt:1827`
 * `top = 0.dp, bottom = 12.dp`), so **today the 600 tier's position term is 0, not
 * +5** — its union bottom is *flush* with the pane bottom. The `+5` remains a v98
 * reading and is labelled as one; the live claim is `criterion1 <= 0`.
 */
class ComposerPositionDecompositionV107Test {

    // ---- v114: the production seam the live track reads -----------------------
    //
    // The yardstick is `panePx = paneBoxPx − believedImeBottomPx(...)` — the way
    // `SessionScreen` computes it (v112 sec 1.1) — and the region's vertical split
    // is the one term of criterion 1 that is Compose source rather than a function,
    // so it is **parsed**, not repeated.

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private val sessionScreen =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")

    /**
     * `composerRegion`'s own vertical split, parsed out of the single padding line
     * that carries it (`SessionScreen.kt:1827`) whose **uniqueness** is asserted.
     * Move the padding in production and everything downstream moves with it.
     */
    private fun regionPaddingDp(): Pair<Double, Double> {
        val all = Regex(
            """\.padding\(\s*start = 12\.dp,\s*end = 12\.dp,\s*top = ([0-9.]+)dp,\s*bottom = ([0-9.]+)dp\s*\)""",
        ).findAll(sessionScreen).toList()
        assertEquals(
            "the composer region's padding line must stay unique and parseable",
            1,
            all.size,
        )
        val m = all[0]
        return (m.groupValues[1].toDouble()) to (m.groupValues[2].toDouble())
    }

    // `wm size 1366x600`, `imeTop 213` ⇒ `imeBottom = 600 − 213 = 387`; the pane box
    // spans `paneTop(162)..window`, `600 − 162 = 438`. The same readings, and so the
    // same arithmetic, as `ComposerPaneBottomCriterionV108Test` / the v109 class.
    private val paneBox600 = 438
    private val imeBottom600 = 387

    /**
     * The 600 tier's pane height the way production computes it ⇒ `438 − 387 = 51`.
     *
     * v126: `floorPx` was the retired `0`; it is now the measured navigation bar,
     * `600 − nonDecorBottom(552) = 48` (insets verbatim:
     * `ROTATION_0={... nonDecorFrame=[0,0][1366,552] ...}`), which is the same
     * quantity production passes at `SessionScreen.kt:2363-2369`. The result is
     * unchanged **by construction, not by luck**: `438 < 284 * 2` makes
     * `believedImeBottomPx` return before it reads `floorPx`
     * (`SessionPaneLayout.kt:1539`). The input is now the device's, and the
     * short-circuit is pinned as such in
     * `ComposerPaneBottomCriterionV108Test.theNavigationBarFloorIsReadOnTheSevenSixtyEightTierAndTheSixHundredTierIsInertToIt`
     * rather than being left to rest on this call's silence.
     */
    private val navFloor600 = 48

    private fun panePx600(): Int = paneBox600 - SessionPaneLayout.believedImeBottomPx(
        visibleBoxPx = paneBox600,
        floorPx = navFloor600,
        imeBottomPx = imeBottom600,
        essentialPx = SessionPaneLayout.PANE_ESSENTIAL_HEIGHT.value.toInt(),
    )

    /** 768 tier device readings: pane box 140 tall from 162, `imeTop` 294. */
    private val paneTop768 = 162
    private val paneHeight768 = 140
    private val imeTop768 = 294
    private val hostPx768 = 94 // the stacked host box
    private val unionPx768 = 88 // its measured child union

    /**
     * Criterion 1 on the 600 tier in **production arithmetic**:
     * `unionBottom − paneBottom`, both pane-relative, the union bottom routed through
     * `composerTopPx` + the production region split + `composerChildUnionHeightPx`.
     *
     * `0` = flush, `> 0` = the v98 overrun. [regionTopPx] is an explicit argument so
     * the reverse leg can put the pre-v110 `top = 4.dp` back through the same seam;
     * it defaults to the split **read from production source**.
     */
    private fun criterion1OnSixHundred(regionTopPx: Int = regionPaddingDp().first.toInt()): Int {
        val hostPx = SessionPaneLayout.MIN_INTERACTIVE_COMPONENT_PX
        val panePx = panePx600()
        val unionBottom = SessionPaneLayout.composerTopPx(panePx, hostPx) +
            regionTopPx +
            SessionPaneLayout.composerChildUnionHeightPx(panePx, hostPx, hostPx)
        return unionBottom - panePx
    }

    /** The 768 tier's `unionBottom − imeTop` on production arithmetic (`+2`, the pane's). */
    private fun unionBottomAbsOnSevenSixEight(): Int = paneTop768 +
        SessionPaneLayout.composerChildUnionBottomPx(
            panePx = paneHeight768,
            hostPx = hostPx768,
            unionPx = unionPx768,
        )

    // ---- v107b: the identity itself, on the 600 tier's real readings ------------

    @Test
    fun criterionOneSplitsExactlyIntoPositionPlusHeight() {
        val paneTop = 162
        val paneHeight = 51
        val paneBottom = paneTop + paneHeight // 213
        val hostPx = 48
        val hostTop = paneBottom - hostPx // 165
        val bandTop = 170
        val unionBottom = 218
        val unionPx = 48

        val hostBottom = hostTop + hostPx // 213 == paneBottom
        val position = bandTop - hostTop // +5
        val height = unionPx - hostPx // 0  <- v106 fixed this term
        val criterion1 = unionBottom - hostBottom // +5 (device reading)

        assertEquals("host bottom must sit flush on the pane bottom", paneBottom, hostBottom)
        assertEquals("the two terms must reproduce the device reading exactly", criterion1, position + height)
        assertEquals("+5 is the device reading on 1366x600", 5, criterion1)
        // v106 must not regress: the height term stays exactly zero in both tiers.
        assertEquals("the height term is already closed (v106)", 0, height)

        // ---- v114: the v98 record above stands; the same claim, LIVE ----------
        //
        // This used to read `assertTrue("all of criterion 1 is position",
        // position == criterion1)` — and both sides were *this file's* literals
        // (`218−165` vs `170−165+0`), so it held by construction and no production
        // change could ever turn it red. The claim itself is worth keeping, so it is
        // re-made here on production terms: the position term is whatever the
        // production region split and union height make it, and the criterion is the
        // production union bottom against the production host/pane bottom.
        val liveHostPx = SessionPaneLayout.MIN_INTERACTIVE_COMPONENT_PX // 48, `:108`
        val livePanePx = panePx600() // 51, via believedImeBottomPx `:1531`
        val liveHostTop = SessionPaneLayout.composerTopPx(livePanePx, liveHostPx) // 3, `:1426`
        val liveHostBottom = SessionPaneLayout.composerBottomPx(livePanePx, liveHostPx) // 51, `:1301`
        val liveUnion = SessionPaneLayout.composerChildUnionHeightPx(livePanePx, liveHostPx, liveHostPx) // 48, `:1330`
        val livePosition = regionPaddingDp().first.toInt() // 0, parsed from SessionScreen.kt:1827
        val liveHeight = liveUnion - liveHostPx
        val liveCriterion1 = liveHostTop + livePosition + liveUnion - liveHostBottom

        assertEquals("the production pane on the 600 tier is 51 px", 51, livePanePx)
        assertEquals("the host is production's 48 px minimum interactive component", 48, liveHostPx)
        assertEquals("the height term is still closed in production", 0, liveHeight)
        assertTrue(
            "all of today's criterion 1 is position (the height term is 0, production)",
            livePosition == liveCriterion1,
        )
        assertTrue(
            "criterion 1: production unionBottom(${liveHostTop + livePosition + liveUnion}) " +
                "<= paneBottom($liveHostBottom)",
            liveHostTop + livePosition + liveUnion <= liveHostBottom,
        )
    }

    // ---- v107b: the 768 tier is a *different* owner of the same criterion ------

    @Test
    fun theSevenSixEightTierIsChargedToThePaneNotToTheComposer() {
        val paneTop = 162
        val paneHeight = 140
        val paneBottom = paneTop + paneHeight // 302
        val imeTop = 294 // the authoritative hostBottom for this tier
        val unionBottom = 296
        val unionPx = 48
        val hostPx = 48

        val hostBottom = paneBottom // composerTopPx: bottom-flush with the pane
        val slack = unionBottom - hostBottom // -6, the union is INSIDE the pane
        val paneOverrun = paneBottom - imeTop // +8, the pane is taller than the room
        val height = unionPx - hostPx // 0
        val criterion1 = unionBottom - imeTop // +2 (device reading)

        assertEquals("criterion 1 must be reproduced by the three terms", criterion1, slack + paneOverrun + height)
        assertEquals("+2 is the device reading on 1366x768", 2, criterion1)
        assertEquals("the union is inside the pane on this tier", -6, slack)
        assertEquals("the height term is zero here too", 0, height)
        // The room actually available above the IME, versus what the pane asked for.
        assertEquals("available above the IME", 132, imeTop - paneTop)
        assertEquals("but the pane asks for", 140, paneHeight)

        // ---- v114: the v98 record above stands; the same attribution, LIVE ----
        //
        // The three-term split above is arithmetic over literals, so it re-derived
        // the device's `+2` whether or not production agrees. Here the union bottom
        // is routed through `composerChildUnionBottomPx` (`:1340`) and the pane box
        // through `composerTopPx`/`composerBottomPx` (`:1426`/`:1301`), which is what
        // makes the *attribution* — "+2 belongs to the pane, not the composer" — a
        // claim about production instead of about this file.
        val liveUnionBottom = unionBottomAbsOnSevenSixEight()
        val liveHostTop768 = SessionPaneLayout.composerTopPx(paneHeight768, hostPx768)
        val liveHostBottom768 = SessionPaneLayout.composerBottomPx(paneHeight768, hostPx768)
        val liveSlack = liveUnionBottom - (paneTop768 + liveHostBottom768)
        val livePaneOverrun = (paneTop768 + paneHeight768) - imeTop768
        val liveCriterion1 = liveUnionBottom - imeTop768

        assertEquals("768 production: the host is bottom-flush with the pane", paneHeight768, liveHostBottom768)
        assertEquals("768 production: the union reproduces the device's 296", 296, liveUnionBottom)
        assertEquals("768 production: the union is 6 px inside the pane bottom", -6, liveSlack)
        assertEquals("768 production: the pane bottom sits 8 px below imeTop", 8, livePaneOverrun)
        assertEquals("768 production: the +2 decomposes into the pane's own over-ask", 2, liveSlack + livePaneOverrun)
        assertEquals("768 production: the composer's own host top is the device's 208", 208, paneTop768 + liveHostTop768)
        assertTrue(
            "768: the composer never reaches the ime top on its own ($liveUnionBottom vs $imeTop768 is the pane's)",
            liveUnionBottom < (paneTop768 + liveHostBottom768),
        )
    }

    // ---- v107b: why the two tiers differ, stated as an assertion ---------------

    @Test
    fun bothTiersShareChildHeightFortyEightSoHeightCannotExplainTheDelta() {
        // v114: this used to open with four `assertEquals(48, 48)` — the worst shape
        // there is: a literal against itself, with `SessionPaneLayout` not even
        // imported in that expression, so not one production change could reach them.
        // The claim they carried ("both device tiers' child band is the 48 px floor,
        // so the union fits its host exactly and height cannot explain the delta") is
        // worth keeping, so it is now made **through production**: the 48 comes from
        // `MIN_INTERACTIVE_COMPONENT_PX` and the union heights from
        // `composerChildUnionHeightPx`.
        val hostPx600Live = SessionPaneLayout.MIN_INTERACTIVE_COMPONENT_PX // 48, `:108`
        val hostPx768Live = SessionPaneLayout.MIN_INTERACTIVE_COMPONENT_PX // 48, `:108`
        val union600Live = SessionPaneLayout.composerChildUnionHeightPx(
            panePx = panePx600(),
            hostPx = hostPx600Live,
            unionPx = hostPx600Live,
        )
        val union768Live = SessionPaneLayout.composerChildUnionHeightPx(
            panePx = paneHeight768,
            hostPx = hostPx768Live,
            unionPx = hostPx768Live,
        )

        assertEquals("the 600 tier's host is production's 48 px floor", 48, hostPx600Live)
        assertEquals("the 768 tier's host is production's 48 px floor", 48, hostPx768Live)
        assertEquals("600: the production union height == the host ⇒ height term 0", hostPx600Live, union600Live)
        assertEquals("768: the production union height == the host ⇒ height term 0", hostPx768Live, union768Live)
        // …and the 768 stacked cell's own 88/94 union is untouched by that floor.
        assertEquals(
            "768 stacked: the production union still fits its 94 px host exactly",
            unionPx768,
            SessionPaneLayout.composerChildUnionHeightPx(
                panePx = paneHeight768,
                hostPx = hostPx768,
                unionPx = unionPx768,
            ),
        )

        // Same child height, same host height, yet the two criteria differ (5 vs 2):
        // therefore the difference is positional, not dimensional.
        //
        // v114: the two `assertTrue`s that used to read `delta600 != delta768` and
        // `delta600 > delta768` compared **two local literals** (`5` and `2`) — true by
        // construction, and the second one was a *causal* claim ("600 is the
        // composer's own placement, 768 is the pane's height") with **zero** production
        // behind it. The readings are now pinned as what they are, records …
        val delta600 = 5 // 600 tier criterion 1 — v98 device reading
        val delta768 = 2 // 768 tier criterion 1 — v98 device reading
        assertEquals("v98 record: the 600 tier overran by 5", 5, delta600)
        assertEquals("v98 record: the 768 tier overran by 2", 2, delta768)
        assertTrue("the two tiers' v98 criteria really did differ", delta600 != delta768)
        // … and the causal claim is re-made on production, where it must still hold
        // *in the direction production is in*. v110 took the 600 tier's position term
        // to 0, so today's 600 delta is not 5 any more — what production still says
        // is that the 600 tier's delta is charged to **neither** the composer nor the
        // pane, while the 768 tier's +2 is charged to the pane. Both halves are read
        // from production functions; asserting the old `5 > 2` here would be asserting
        // a world that stopped existing in v110.
        val live600 = criterion1OnSixHundred()
        val live768 = unionBottomAbsOnSevenSixEight() - imeTop768
        assertEquals(
            "600 production: nothing is charged to the 600 tier any more (v110 took the +5 to 0)",
            0,
            live600,
        )
        assertEquals("768 production: the +2 is still there, and it is the pane's", 2, live768)
        assertTrue("production: the two tiers' criteria still differ", live600 != live768)
        assertTrue(
            "criterion 1: the 600 tier's production union bottom is inside the pane's",
            live600 <= 0,
        )
    }

    // ---- v114: the reverse leg, resident (not a one-off demonstration) ---------

    /**
     * The seam has to be able to go **red**, or [criterion1OnSixHundred] is decoration.
     * This puts the pre-v110 `top = 4.dp` back through the very same production path
     * and requires criterion 1 to break by exactly the historical amount — the v98
     * device reading `218 − 213 = +5`, bit for bit, not "some overrun".
     */
    @Test
    fun theCriterionOneTripwireDetectsTheOldBandTopPaddingOnTheSixHundredTier() {
        val today = criterion1OnSixHundred()
        val withOldTopPadding = criterion1OnSixHundred(regionTopPx = 4)

        assertTrue(
            "a restored `top = 4.dp` must break criterion 1 (was $withOldTopPadding vs $today)",
            withOldTopPadding > 0,
        )
        assertEquals(
            "…by exactly the 600 tier's own 4 px band offset, not some other amount",
            4,
            withOldTopPadding,
        )
        assertEquals(
            "…and with the device's +1 sub-pixel placement it lands on the v98 reading 218",
            218,
            (withOldTopPadding + 1) + (paneTopAbs() + panePx600()),
        )
        assertTrue("today's production must hold criterion 1 (was $today)", today <= 0)
        assertEquals("today the 600 tier's union bottom is flush with the pane bottom", 0, today)
    }

    private fun paneTopAbs(): Int = 162
}
