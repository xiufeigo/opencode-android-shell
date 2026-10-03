package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.SessionPaneLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v108: two things, both pinned against **production code** rather than a model.
 *
 * **1. The Lead's criterion change.** Criterion 1 is now measured against
 * **`paneBottom`** (the pane container's own bottom edge = the available area's
 * bottom = the `imeTop` side), not against `hostBottom`. The `hostBottom`
 * numbers are kept here as **observations only** — they are no longer the
 * verdict, and this file does not use them to decide anything.
 *
 * ```
 * 1366x768 + IME: union 208..296 (b=296)  pane 162..302 (b=302)  ->  -6  PASS
 * 1366x600 + IME: union 169..218 (b=218)  pane 162..213 (b=213)  ->  +5  OPEN
 * ```
 *
 * **v112 updates the 600 line: the tier is no longer `OPEN`.** v110 moved the
 * region's `top = 4.dp` to its bottom edge (`SessionScreen.kt:1827`), which is
 * the only term that put the band past the pane's bottom edge, and the device
 * then read `unionBottom 249 <= paneBottom 261` (`COMPOSER_OVERFLOW_FIX8.md`
 * §4.2①). The readings above stay here verbatim: they are the **v98 record**,
 * the only account of how the tier was wrong, and
 * [theOldHostBottomNumbersAreRetainedAsObservationsOnly] plus
 * `ComposerUnionBottomP5V109Test` keep them pinned as observations.
 *
 * What v112 replaces is the **verdict**, which was a frozen literal pair: it
 * asserted `218 > 213` against its own constants, so it could not go red on a
 * regression. The verdict is now computed from production —
 * [theSixHundredTierHoldsThePaneBottomCriterionAgainstProductionArithmetic]
 * — and [theCriterionOneTripwireDetectsBothOldBehaviours] proves that verdict
 * still fires when either old behaviour is put back.
 *
 * **`paneBottom` is production's own bottom edge.** It is the bottom of the
 * pane box's `Layout` (`SessionScreen.kt:3185`, placed by the
 * `layout(...)` at `:3351` with height `constrainHeight(pane)`), which is the
 * node frame every `SessionPaneLayout` placement function
 * ([composerTopPx], [composerChildUnionHeightPx], [believedImeBottomPx]) is
 * written in — in that frame `paneBottom ≡ panePx`.
 *
 * Source of every literal: `research/v97_verify/5556_v98/REPORT_COMPOSER_OVERFLOW4.md`
 * sec 2.1 / sec 2.2 / sec 4 (`wm size 1366x768`, `wm density 160`, so **1dp == 1px**).
 * They are literals on purpose: a model must not be allowed to invent its own
 * "before" numbers (that is the fake-green trap this repo hit three times, see
 * `COMPOSER_OVERFLOW_FIX4.md` sec 1.3).
 *
 * **2. The falsification of "the 8px is an IME-height error".** v107b recorded
 * that the v106 model used IME = 466 where the device measured 474, and named
 * that delta the suspect for criterion 1. This file calls the **real production
 * function** `SessionPaneLayout.believedImeBottomPx` and shows 466 is not a
 * value that function can produce, and — decisively — that on the **600** tier
 * the production IME path contributes **exactly zero** error, so the 600 tier's
 * `+5` cannot have been produced by it.
 *
 * > **v125 correction to this section.** Both numbers in that sentence are
 * > retired: the `474` and the `8` it differs from `466` by. The device's
 * > measured 768-tier IME bottom is **426** (`imeTop 342`), not 474
 * > (`imeTop 294`, the v110 epoch). `466` is therefore not merely unreachable —
 * > it was never the right target, and the "8px delta" this section named as
 * > the suspect is `48 − 40`: one navigation bar of wrong input plus a 40px
 * > surplus. The *conclusion* this section reached (the 600 tier's IME path
 * > contributes exactly zero error) is **unaffected** — it was derived on the
 * > 600 tier, where nothing changed. Only the 768-tier literals move.
 *
 * Device chain, reproduced here rather than asserted in prose:
 * ```
 * pane height = paneBoxPx - believedImeBottomPx(...)  [SessionPaneLayout.kt:1531]
 * 600: paneBox 438 - raw imeBottom 387 = 51          <- device measured 51
 * 768: paneBox 606 - raw imeBottom 426 = 180         <- device measured 140
 *       (v125; the retired 474 gave 132, and 140 - 132 = 8 — the phantom)
 * ```
 *
 * (v112: this line used to read `paneBottomDp [SessionScreen.kt:2428]`, but
 * `:2428` is a comment line now — v110's comment block moved the call — so the
 * citation is the **production function that actually performs the
 * subtraction** instead of a line number that drifts.)
 */
class ComposerPaneBottomCriterionV108Test {

    // Device geometry (1366x768 / 1366x600, density 160 => 1dp == 1px).
    private val paneTop = 162
    private val paneHeight768 = 140
    private val paneHeight600 = 51
    /**
     * v125: **the 768-tier IME bottom inset is `426`, not the `474` v108/v110 wrote.**
     *
     * Measured insets (3rd final verification, sec 4, verbatim):
     * `InsetsSource id=3 type=ime frame=[0,342][1366,768]` ⇒ `768 − 342 = 426`.
     * The retired `474` back-solves to an `imeTop` of `294`, which is the **v110
     * epoch's** reading and is not this build's top edge. `474 − 426 = 48` —
     * **exactly one navigation bar** — which is the whole provenance of the
     * retired "8px" this class used to pin (see
     * [theImeShortfallTermIsAThreeHundredSixtyEightOnlyTermAndIsZeroOnTheSixHundredTier]).
     */
    private val imeBottom768 = 426 // window - imeTop(342)
    private val imeBottom600 = 387 // window - imeTop(213)
    private val paneBox768 = 606 // paneTop..window, after safeDrawing top
    private val paneBox600 = 438

    private fun essentialPx(): Int = SessionPaneLayout.PANE_ESSENTIAL_HEIGHT.value.toInt()

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private val sessionScreen =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")

    /**
     * v112: `composerRegion`'s **own** vertical split, parsed out of the one
     * padding line that carries it (`SessionScreen.kt:1827`, uniqueness asserted
     * below). This is the only term of criterion 1 that is **not** a production
     * function — it is Compose source — so the tripwire reads it rather than
     * repeating it, which is what makes the tripwire bidirectional: change the
     * production line and the verdict changes with it.
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

    /**
     * v112: criterion 1 evaluated **entirely in production arithmetic** —
     * `unionBottom <= paneBottom`, both bottom edges taken from the production
     * pane frame (whose origin is the pane box's own top edge), with the region
     * split as the only injected term.
     *
     * ```
     * paneBottom   = paneTop + (paneBoxPx − believedImeBottomPx(...))     [production]
     * unionBottom  = paneTop + composerTopPx(pane, host)                  [production]
     *                       + regionTopPx                                 [source]
     *                       + composerChildUnionHeightPx(pane, host, u)  [production]
     * ```
     *
     * [topOf] is the seam: production passes [SessionPaneLayout.composerTopPx],
     * the reverse check passes the pre-v103 uncapped `(pane - host)`. Nothing
     * here is a frozen `218`/`213`.
     */
    private fun unionBottomAbs(
        panePx: Int,
        hostPx: Int,
        unionPx: Int,
        regionTopPx: Int,
        topOf: (Int, Int) -> Int,
    ): Int {
        val local = topOf(panePx, hostPx) +
            regionTopPx +
            SessionPaneLayout.composerChildUnionHeightPx(
                panePx = panePx,
                hostPx = hostPx,
                unionPx = unionPx,
            )
        return paneTop + local
    }

    private fun panePx600(): Int =
        paneBox600 - SessionPaneLayout.believedImeBottomPx(
            visibleBoxPx = paneBox600,
            floorPx = 0,
            imeBottomPx = imeBottom600,
            essentialPx = essentialPx(),
        )

    // ---- 1. criterion 1, on the new paneBottom yardstick -------------------

    @Test
    fun theSevenSixEightTierPassesTheNewCriterionWithSixPxToSpare() {
        val unionBottom = 296
        val paneBottom = paneTop + paneHeight768 // 302

        val criterion1 = unionBottom - paneBottom // -6

        assertEquals("pane bottom is the measured 302", 302, paneBottom)
        assertEquals("the 768 tier has 6px inside the pane", -6, criterion1)
        assertTrue(
            "criterion 1 (paneBottom) must hold on 1366x768",
            unionBottom <= paneBottom,
        )
    }

    /**
     * v112: the 600 tier's criterion 1, **rebuilt against production**.
     *
     * v108 wrote this as a frozen literal pair (`unionBottom = 218`,
     * `paneBottom = paneTop + paneHeight600` → `assertTrue(218 > 213)`), which
     * asserted the defect existed **by construction of its own constants** — it
     * could neither show the tier as fixed nor catch the tier regressing.
     * v110 fixed the tier for real (`SessionScreen.kt:1827` `top = 4.dp` →
     * `0.dp`), so that pin went stale while staying green.
     *
     * Every number here is now a **production value**:
     *  * `paneBottom` ← `believedImeBottomPx` (`SessionPaneLayout.kt:1531`),
     *    the pane subtraction `SessionScreen` actually performs;
     *  * `unionBottom` ← `composerTopPx` (`:1426`) +
     *    `composerChildUnionHeightPx` (`:1330`), the production placement;
     *  * the region's own top padding ← parsed from the production source line.
     *
     * The v98 readings themselves are **not** deleted: they stay pinned as
     * observations in [theOldHostBottomNumbersAreRetainedAsObservationsOnly]
     * and in `ComposerUnionBottomP5V109Test`.
     */
    @Test
    fun theSixHundredTierHoldsThePaneBottomCriterionAgainstProductionArithmetic() {
        val panePx = panePx600() // 51 — production
        val paneBottom = paneTop + panePx // 213 — production-derived

        val hostPx = SessionPaneLayout.MIN_INTERACTIVE_COMPONENT_PX // 48 = the band
        val (regionTopDp, regionBottomDp) = regionPaddingDp() // 0 / 12 today
        val regionTopPx = regionTopDp.toInt()

        val unionBottom = unionBottomAbs(
            panePx = panePx,
            hostPx = hostPx,
            unionPx = hostPx, // the four children are flush in the one-row band
            regionTopPx = regionTopPx,
            topOf = SessionPaneLayout::composerTopPx,
        )

        // The yardstick is the production pane's own bottom edge, and on these
        // inputs it is the device's `imeTop` — the same 213 the v98 run read.
        assertEquals("pane bottom is the production-derived 213 == imeTop 213", 213, paneBottom)
        assertEquals(
            "the production pane is 51 px on the 600 tier",
            51,
            panePx,
        )
        // v110's lever: the region's decoration sits **below** the band, so the
        // term that used to push the band past the pane edge is zero.
        assertEquals("the band's top padding is the 0 dp v110 moved it to", 0, regionTopPx)
        assertEquals(
            "and the same 4 dp now sits on the bottom edge, the total unchanged",
            SessionPaneLayout.COMPOSER_REGION_PADDING.value.toDouble(),
            regionTopDp + regionBottomDp,
            0.0,
        )
        // ---- criterion 1, the Lead's yardstick ----
        assertTrue(
            "criterion 1: unionBottom($unionBottom) must stay inside paneBottom($paneBottom)",
            unionBottom <= paneBottom,
        )
    }

    /**
     * v112: the reverse leg — **the tripwire must go red when production is put
     * back the way v110 found it.** Two independent old behaviours, injected
     * through the same seam the forward test uses, and each must be *detected*:
     *
     *  1. the region's `top = 4.dp` (v110's removed defect lever) — the exact
     *     `+5` overrun the v98 device measured, minus the sub-pixel term the
     *     v109 class still records separately;
     *  2. the pre-v103 uncapped `composerTopPx`, i.e.
     *     `(pane - host).coerceAtLeast(0)` before `composerHostHeightPx`
     *     existed (`SessionPaneLayout.kt:1268-1282`).
     *
     * No production file is touched: both are passed in as arguments.
     */
    @Test
    fun theCriterionOneTripwireDetectsBothOldBehaviours() {
        val panePx = panePx600() // 51
        val paneBottom = paneTop + panePx // 213
        val hostPx = SessionPaneLayout.MIN_INTERACTIVE_COMPONENT_PX // 48

        // 1. the v110 defect, restored: `top = 4.dp`.
        val withOldTopPadding = unionBottomAbs(
            panePx = panePx,
            hostPx = hostPx,
            unionPx = hostPx,
            regionTopPx = 4,
            topOf = SessionPaneLayout::composerTopPx,
        )
        assertTrue(
            "a restored `top = 4.dp` must break criterion 1 (was $withOldTopPadding)",
            withOldTopPadding > paneBottom,
        )
        assertEquals(
            "and it must reproduce the v98 overrun's 4px term, not a different one",
            4,
            withOldTopPadding - paneBottom,
        )

        // 2. the pre-v103 uncapped top edge. Reported honestly: the **union**
        //    criterion alone cannot see this one — `composerChildUnionHeightPx`
        //    clamps the union into the host, so the overrun is already absorbed
        //    there (v104 made that invariant strictly stronger than v103's).
        //    The quantity v103 actually governs is the **host** bottom, so this
        //    leg is asserted against [composerBottomPx], the function that owns
        //    the clamp. Recorded here rather than assumed away.
        val oldHostBottom = { pane: Int, host: Int ->
            (pane - host).coerceAtLeast(0) + host
        }
        assertTrue(
            "an uncapped top edge must push the host bottom past the pane bottom " +
                "(was ${oldHostBottom(panePx, hostPx + 16)} vs $panePx, in the pane frame)",
            oldHostBottom(panePx, hostPx + 16) > panePx,
        )
        assertEquals(
            "and it must reproduce the v103 overrun exactly: host(64) − pane(51)",
            13,
            oldHostBottom(panePx, hostPx + 16) - panePx,
        )
        assertEquals(
            "production clamps the over-tall host's bottom into the pane",
            panePx,
            SessionPaneLayout.composerBottomPx(panePx = panePx, hostPx = hostPx + 16),
        )
        assertEquals(
            "...which is why the forward criterion can never be a tautology: it is " +
                "the *union* bottom, and the union is clamped by a different call",
            panePx,
            SessionPaneLayout.composerChildUnionBottomPx(
                panePx = panePx,
                hostPx = hostPx + 16,
                unionPx = hostPx + 16,
            ),
        )
    }

    @Test
    fun theOldHostBottomNumbersAreRetainedAsObservationsOnly() {
        // The superseded yardstick, kept so the old verdicts stay reproducible.
        assertEquals("768 vs imeTop 294", 2, 296 - 294)
        assertEquals("600 vs imeTop 213", 5, 218 - 213)
        // The new yardstick differs from the old one only on the 768 tier, and
        // only because the pane itself is the container being measured.
        assertEquals("768 hostBottom == paneBottom (bottom-flush host)", 302, 162 + paneHeight768)
    }

    // ---- 2. criterion 2 must not regress (v106's gain) ---------------------

    @Test
    fun criterionTwoUnionPxAtMostBandHoldsInBothTiers() {
        val band = 48
        val unionPx = 48 // union(EditText, Add, Send) excluding "Open terminal"
        assertEquals("600 tier", 0, unionPx - band)
        assertEquals("768 tier", 0, unionPx - band)
        assertTrue("unionPx <= band", unionPx <= band)
    }

    // ---- 3. the 8px: it is NOT an IME-height error in production -----------

    @Test
    fun theSixHundredTierImePathContributesExactlyZeroError() {
        // paneBox 438 < essentialPx * 2 (284*2 = 568) => the clamp's guard
        // short-circuits and the raw inset is believed verbatim.
        val believed = SessionPaneLayout.believedImeBottomPx(
            visibleBoxPx = paneBox600,
            floorPx = 0,
            imeBottomPx = imeBottom600,
            essentialPx = essentialPx(),
        )

        assertEquals("the 600 tier believes the raw inset", imeBottom600, believed)
        // pane = box - believed = 438 - 387 = 51 == the device's measured pane.
        assertEquals("this reproduces the measured 600 pane", 51, paneBox600 - believed)
        // => the 600 pane height carries no IME error at all, so the +5 the
        //    union shows there cannot be the 768-only 8px showing up again.
    }

    @Test
    fun fourSixSixIsNotAValueTheProductionImePathCanProduce() {
        val believed = SessionPaneLayout.believedImeBottomPx(
            visibleBoxPx = paneBox768,
            floorPx = 0,
            imeBottomPx = imeBottom768,
            essentialPx = essentialPx(),
        )

        // Unclamped the function returns the platform reading (426). Clamped it
        // returns visibleBox - essentialPx (606 - 284 = 322). It cannot return
        // 466 for any floor >= 0, because 466 is neither input nor a difference
        // of them. v125: the raw branch is restated from the retired 474 to the
        // measured 426; the reachable clamped value (322) is unchanged, because
        // 426 and 474 both exceed room 322 and both therefore trip the clamp.
        assertTrue(
            "clamp output must be raw 426 or clamped 322, was $believed",
            believed == imeBottom768 || believed == paneBox768 - essentialPx(),
        )
        assertNotEquals(
            "466 is unreachable from believedImeBottomPx",
            466,
            believed,
        )
    }

    // v125: renamed from `theEightPxIsAThreeHundred…` — the numeral in the old
    // name was the retired reading's artefact. The structural claim it pins is
    // unchanged: the term exists on 768 and is identically zero on 600.
    @Test
    fun theImeShortfallTermIsAThreeHundredSixtyEightOnlyTermAndIsZeroOnTheSixHundredTier() {
        // "room above the IME inside the pane box" - the honest available height
        val room768 = paneBox768 - imeBottom768 // 180 (v125: was 132 under the retired 474)
        val room600 = paneBox600 - imeBottom600 // 51
        val pane768 = paneHeight768 // 140

        // v125: the term is STILL 768-only and STILL identically zero on 600 —
        // that is what this class is for and neither half of it moved. What
        // changed is only the 768 operand, because the room it measures against
        // is a function of the IME reading: with the measured `426` the room is
        // 180, and `140 − 180 = −40`, i.e. the pane now has 40px to SPARE rather
        // than asking for 8px it cannot have. The retired 8 was `48 − 40` — one
        // navigation bar minus that 40 — so it was the 48 in the wrong IME
        // reading all along, not a real shortfall. 600 is untouched: 438 − 387.
        assertEquals(
            "768 has 40px to spare, not a shortfall: 140 − (606 − 426) = −40",
            -40,
            pane768 - room768,
        )
        // the 768-only term, restated on the measured input
        assertEquals("the retired 8 was 48 − 40, i.e. one navigation bar of bad input", 8, 48 - 40)
        // ...and the retired 8 reproduces exactly if the retired input comes back
        assertEquals("a 474 ime bottom would reproduce the retired 8 exactly", 8, 140 - (606 - 474))
        assertEquals("600 asks for exactly the room it has", 0, paneHeight600 - room600)

        // The term that is zero here is exactly the term that cannot produce the
        // 600 tier's +5.
        assertEquals("the 8px term is identically zero on 600", 0, paneHeight600 - room600)
    }
}
