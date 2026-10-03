package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.SessionPaneLayout
import com.xiufeigo.opencodeshell.ui.session.SessionPaneLayout.composerBandChildTopPx
import com.xiufeigo.opencodeshell.ui.session.SessionPaneLayout.composerBandUnionBottomPx
import com.xiufeigo.opencodeshell.ui.session.SessionPaneLayout.composerBandTouchTargetDp
import com.xiufeigo.opencodeshell.ui.session.SessionPaneLayout.composerBandHeightDp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v136: the 600-tier "queue bar on screen" form's two residual 1 px, pinned to
 * **production functions** instead of left as an accounted-for residual.
 *
 * v135 settled that criterion 1 reads `+2 = 1 (real exit) + 1 (union alignment)`
 * (`unionBottom 263 > paneBottom 261`, `research/v97_verify/5554_md/v134/
 * k600_MEASURE_BARON.xml`). This class pins **where each 1 px comes from** and the
 * rule that removes it:
 *
 * 1. **the union-alignment 1 px** — `Composer.kt`'s one-row `Row` used
 *    `Alignment.CenterVertically`. With a 48 dp band and a control whose measured
 *    height is odd, Compose's centring offset `(band - child) / 2f` **rounds a half
 *    pixel up**, so the control lands at `+1`, and its 48 dp touch target (centred on
 *    it) reads `215..263` while the `EditText` reads `214..262` — same 48 high, top
 *    edges 1 apart, union height 49 against the 48 baseline. The one-row form now uses
 *    [androidx.compose.ui.Alignment.Top]: controls belong flush against a band that
 *    **is** the touch target.
 * 2. **the real-exit 1 px** — positional, not dimensional. The bar pushes the card's
 *    top from 201 to 214 (`+13`); the region's 12 px bottom padding absorbs 12 of it,
 *    leaving `262 - 261 = +1`. The band's own placement rule is now one function,
 *    [composerBandChildTopPx], so `top + height <= band` holds for **each** child and
 *    the two can no longer be placed by two different rules.
 *
 * **Nothing here is a claim that criterion 1 is met on device.** The device frame is
 * the only judge (v135 §1.6: the model has no term for the bar's push and is
 * structurally non-positive). What is pinned is the arithmetic and the production
 * quantities, so a regression in the placement rule cannot pass silently. The v135
 * device literals are kept verbatim as the **regression trigger** — if the real frame
 * moves, these numbers are the first thing that must be re-read.
 *
 * ---------------------------------------------------------------------------
 * v138 REDOWNGRADE — **this class is a JVM model, NOT device truth.**
 * ---------------------------------------------------------------------------
 *
 * v136 claimed "the union-alignment 1 px is fixed" and pinned the mechanism with
 * this class. **v137's device run falsified the claim** (`research/v97_verify/
 * 5554_md/REPORT_1PX_RESIDUAL.md`, build md5 `9142f0eb…`, emulator-5554,
 * 600 tier, queue bar on screen):
 *
 * ```
 * EditText  [24,214][405,262]   h=48     ← top 214
 * Add       [399,215][441,263]  h=48     ← top 215   (union 49, topDelta −1)
 * Stop      [1316,215][1364,263] h=48    ← top 215
 * scroller  [441,228][1316,249] h=21     ← the 21-high layout the 48 dp target wraps
 * unionPx = 49  (baseline 48)     criterion 1 = +2  (not +1)
 * ```
 *
 * So every assertion in this class describes **a model that the device does not
 * exhibit**. They are kept — they are not relaxed, not deleted and not made
 * trivially true — but they must never be read as "the 1 px is fixed".
 *
 * v138's static finding, which is what makes the gap mechanical rather than
 * philosophical (full argument in `research/v97_fix/UNION_1PX_NOT_EFFECTIVE.md`):
 *
 *  * `composerBandChildTopPx` / `composerBandUnionBottomPx` **are** on the live
 *    path (`Composer.kt:2362/2366/2370`, inside `if (minimal)` at `:2326`, and
 *    `minimal` is provably `true` here: the 48 dp band at `Composer.kt:2189`
 *    exists only on the `minimal` branch).
 *  * They are nevertheless **provable no-ops on this form**: the tool row
 *    carries `Modifier.height(BAND_HEIGHT)` (`Composer.kt:2189`) which coerces
 *    its own height to exactly `band`, and the editor is measured with
 *    `maxHeight = band`, so `childPx == bandPx == 48` for both and
 *    `composerBandChildTopPx(48, 48) = 0` — bit-identical to the pre-v136
 *    `toolRow.place(0, 0)` and `editorTop = (band - editor.height)/2`.
 *  * ⇒ the model answers `unionBottom = 48` while the device answers `49`.
 *    The 1 px is produced **below** `place()`, by the material3-enforced 48 dp
 *    `minimumInteractiveComponentSize` expanding each control's *semantics*
 *    bounds about a 21-high layout, which this model has no term for.
 *  * The only line of the v136 diff that can move a pixel on device is
 *    `verticalAlignment = if (minimal) Alignment.Top …` (`Composer.kt:2210`),
 *    and v137's own frame carries the `Alignment.CenterVertically` fingerprint
 *    (`roundToInt((48 − 21)/2f) = 14`, the scroller measured at `214 + 14 = 228`).
 *    `Alignment.Top` can only emit `0`.
 *
 * The pin `theOneRowFormAlignsControlsToTheTopEdgeAndTheStackedFormDoesNot`
 * therefore stays green by asserting **source text**, which is exactly the
 * fake-green this repo has now hit five times. It is kept verbatim; the device
 * side of the contradiction is pinned by
 * [theV137DeviceReadingsAreTheRegisterAndTheModelStillDisagreesWithThem].
 */
class ComposerBandUnionV136Test {

    // ---- v135 device literals, verbatim (never recomputed — a model must not
    // ---- invent its own "before" values; COMPOSER_OVERFLOW_FIX4.md §1.3).

    private val hostTop = 162
    private val hostBottom = 261
    private val hostHeight = 99

    private val closedCardTop = 201
    private val closedCardBottom = 249

    private val barOnCardTop = 214
    private val barOnEditorBottom = 262
    private val barOnButtonBottom = 263
    private val barOnUnionBottom = 263

    /** The baseline both children of the one-row band are supposed to reach. */
    private val bandPx = 48

    // ---- the two residuals, decomposed the way v135 left them.

    private val realExit = barOnEditorBottom - hostBottom
    private val alignment = barOnButtonBottom - barOnEditorBottom
    private val criterion1 = barOnUnionBottom - hostBottom

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("source not found: $relative")

    private val composerSource: String by lazy {
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt")
    }

    // ------------------------------------------------------------------ 1. the
    // production placement rule, asserted as a function (not as a comment).

    /**
     * The rule that removes the alignment 1 px: both children of the band are placed
     * by the **same** function, and each one's `top + its own height` is `<= band`.
     * The 47-high case is the device case — it is what produced `215..263`.
     */
    @Test
    fun eachBandChildIsPlacedByOneRuleAndNeverExceedsTheBand() {
        // The device's own pair: 48-high editor, and the odd-height control that the
        // centring used to push to +1.
        assertEquals("a 48-high child in a 48 band sits at the top edge", 0, composerBandChildTopPx(48, 48))
        assertEquals("a 47-high child is no longer pushed to +1", 0, composerBandChildTopPx(48, 47))
        assertEquals("a 49-high child (over the band) is pinned to 0", 0, composerBandChildTopPx(48, 49))

        // `top + height <= band` for each child, across the whole admissible range.
        for (band in listOf(0, 1, 28, 32, 44, 47, 48, 49, 60, 94, 180)) {
            for (child in 0..band) {
                val top = composerBandChildTopPx(band, child)
                assertTrue(
                    "top $top + height $child must stay inside band $band",
                    top >= 0 && top + child <= band,
                )
            }
        }
    }

    /**
     * The union's bottom edge, computed by production. With the device's pair the
     * fixed rule returns **48** — the baseline — where the old two-rule placement
     * returned 49.
     */
    @Test
    fun theUnionBottomReturnsToTheBandBaselineForTheDevicePair() {
        // Before: centring pushed the odd child to +1, so the union was 49.
        val oldUnion = maxOf(0 + 48, 1 + 48)
        assertEquals("the pre-fix union height, the +2 report's alignment term", 49, oldUnion)

        // After: one rule, both flush against the top edge.
        val unionBottom = composerBandUnionBottomPx(bandPx = bandPx, editorPx = 48, toolRowPx = 47)
        assertEquals("the union bottom returns to the band baseline", bandPx, unionBottom)
        assertTrue("unionPx <= band, the v105/v106 claim, now actually holds", unionBottom <= bandPx)
    }

    /**
     * The union bottom is **monotone and never above the band** for any admissible
     * pair — the structural form of "the editor's bottom edge cannot leave the box".
     */
    @Test
    fun theUnionBottomIsBoundedByTheBandForEveryAdmissiblePair() {
        for (band in listOf(28, 32, 44, 47, 48, 49, 60, 94)) {
            for (editor in 0..band) {
                for (toolRow in 0..band) {
                    val union = composerBandUnionBottomPx(band, editor, toolRow)
                    assertTrue(
                        "union $union must stay inside band $band (editor $editor, toolRow $toolRow)",
                        union <= band,
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------ 2. the
    // source really carries the fix (a pin that only restates itself is fake green).

    /** The one-row `Row` aligns to `Top`; the stacked form keeps its centring. */
    @Test
    fun theOneRowFormAlignsControlsToTheTopEdgeAndTheStackedFormDoesNot() {
        assertTrue(
            "the one-row Row must branch on `minimal` for its verticalAlignment",
            composerSource.contains(
                "verticalAlignment = if (minimal) Alignment.Top else Alignment.CenterVertically",
            ),
        )
    }

    /** The arrangement places **both** children through the shared production rule. */
    @Test
    fun bothBandChildrenArePlacedThroughTheSharedProductionFunction() {
        assertTrue(
            "the editor's placement must go through composerBandChildTopPx",
            composerSource.contains("val editorTop = SessionPaneLayout.composerBandChildTopPx("),
        )
        assertTrue(
            "the tool row's placement must go through composerBandChildTopPx too",
            composerSource.contains("val toolRowTop = SessionPaneLayout.composerBandChildTopPx("),
        )
        assertTrue(
            "the editor must be placed at that computed top",
            composerSource.contains("editor.place(0, editorTop)"),
        )
        assertTrue(
            "the tool row must be placed at that computed top, not pinned to 0",
            composerSource.contains("toolRow.place(0, toolRowTop)"),
        )
    }

    // ------------------------------------------------------------------ 3. hard
    // constraints that must NOT have moved.

    /**
     * The 48 dp touch target is **one pixel** untouched: the band height, the touch
     * target function and its `48` result are all exactly what v106 left.
     */
    @Test
    fun theTouchTargetIsStillFortyEightDpOnAPixel() {
        assertEquals("the band is still max(44dp, 48dp)", 48f, composerBandHeightDp(44.dp0()).value, 0f)
        assertEquals("the 48 dp floor is still the enforced one", 48f, SessionPaneLayout.MIN_INTERACTIVE_COMPONENT.value, 0f)
        assertEquals(
            "a 48 dp band still yields the full 48 dp target (one row)",
            48f,
            composerBandTouchTargetDp(oneRow = true, band = 48.dp0()).value,
            0f,
        )
        assertEquals(
            "a 44 dp band still caps to the band (unchanged v105 behaviour)",
            44f,
            composerBandTouchTargetDp(oneRow = true, band = 44.dp0()).value,
            0f,
        )
    }

    // ------------------------------------------------------------------ 4. the
    // v135 account, restated as a trigger — not re-judged.

    /**
     * The v135 device literals, unchanged. Criterion 1 still reads `+2` **on the
     * archived frame**; this class does not declare it met, and these numbers are the
     * regression trigger for the next device run.
     */
    @Test
    fun theArchivedFrameStillReadsPlusTwoSoTheNextDeviceRunIsTriggered() {
        assertEquals("the host box is identical in both forms", 99, hostBottom - hostTop)
        assertEquals("the bar's push on the card's top edge", 13, barOnCardTop - closedCardTop)
        assertEquals("the region padding absorbs 12 of it", 12, barOnEditorBottom - closedCardBottom - 1)
        assertEquals("the real exit, the positional 1 px", 1, realExit)
        assertEquals("the union alignment, the odd-height 1 px", 1, alignment)
        assertEquals("criterion 1 on the archived frame, still +2", 2, criterion1)
        assertEquals("+2 = 1 + 1", realExit + alignment, criterion1)
    }

    // ------------------------------------------------------------------ 5. v138:
    // the **device** reading register. This is the half of the contract that was
    // missing: everything above 1..4 is a model, and v137 proved the model and the
    // device disagree by 1 px. The device half is pinned here so the disagreement
    // is a *failing assertion* the day somebody fixes it, instead of a discovery
    // somebody has to make on an emulator again.

    /**
     * The v137 device readings, verbatim, from
     * `research/v97_verify/5554_md/artifacts/v137_BARON_M.xml`
     * (build md5 `9142f0eb…`, `wm size 1366x600` / `wm density 160` ⇒ 1dp == 1px,
     * 600 tier, queue bar on screen, one-row form).
     *
     * **These are the numbers that must move together when the defect is fixed.**
     * Nothing here may be recomputed from the model — they are a *reading*, and a
     * model is not allowed to invent its own "before" values
     * (`COMPOSER_OVERFLOW_FIX4.md` §1.3).
     */
    @Test
    fun theV137DeviceReadingsAreTheRegisterAndTheModelStillDisagreesWithThem() {
        // ---- the reading, verbatim from the frame -------------------------------
        val devPaneBottom = 261
        val devBandTop = 214
        val devEditorTop = 214
        val devEditorBottom = 262
        val devControlTop = 215 // the 48 dp semantics bounds of Add / Stop / Model
        val devControlBottom = 263
        val devScrollerTop = 228
        val devScrollerHeight = 21 // the 21-high layout the 48 dp target wraps
        val devBand = 48

        val devUnionBottom = maxOf(devEditorBottom, devControlBottom)
        val devUnionPx = devUnionBottom - devBandTop
        val devTopDelta = devControlTop - devEditorTop
        val devCriterion1 = devUnionBottom - devPaneBottom

        // ---- the register: these five readings, asserted -------------------------
        assertEquals("v137 device: the band top is the editor's top edge", devBandTop, devEditorTop)
        assertEquals("v137 device: the 48 dp control bounds sit 1 px lower", 1, devTopDelta)
        assertEquals("v137 device: unionPx is still 49, the 48 baseline is NOT met", 49, devUnionPx)
        assertEquals("v137 device: union bottom 263 = max(262, 263)", 263, devUnionBottom)
        assertEquals("v137 device: criterion 1 is still +2, not +1", 2, devCriterion1)
        assertEquals(
            "v137 device: the controls' own layout is 21 high inside a 48 band",
            21,
            devScrollerHeight,
        )

        // ---- the fingerprint that says which alignment produced the frame -------
        //
        // Compose's `Alignment.CenterVertically` is `BiasAlignment(verticalBias =
        // 0.5f)`, whose offset is `roundToInt((band - child) / 2f)`. On this frame:
        //
        //   roundToInt((48 - 21) / 2f) = roundToInt(13.5f) = 14
        //   214 + 14 = 228  == the scroller's measured top
        //
        // `Alignment.Top.align` returns 0 for every child, by construction, so a
        // measured child at `bandTop + 14` is proof that the frame was rendered by
        // the **pre-v136** `Alignment.CenterVertically` branch, whatever the source
        // tree says today. This is what separates v137's two still-open hypotheses
        // (install ownership vs. a misattributed line) without a rebuild: the frame
        // answers it arithmetically.
        val centredOffset = Math.round((devBand - devScrollerHeight) / 2.0).toInt()
        assertEquals("the centring offset of a 21-high child in a 48 band", 14, centredOffset)
        assertEquals(
            "v137 device: the scroller's top IS bandTop + that centring offset",
            devScrollerTop,
            devBandTop + centredOffset,
        )
        assertNotEquals(
            "`Alignment.Top` is not what rendered this frame: its offset is 0 for every " +
                "child by construction, so the scroller would have measured $devBandTop, " +
                "not $devScrollerTop",
            devBandTop + 0,
            devScrollerTop,
        )

        // ---- and the disagreement that made this register necessary --------------
        //
        // The production function — the one this whole class pins — is fed the two
        // heights the arrangement actually measures. Both are 48, because the tool
        // row carries `Modifier.height(BAND_HEIGHT)` and the editor is measured with
        // `maxHeight = band`:
        //
        //   composerBandChildTopPx(48, 48)      = 0
        //   composerBandUnionBottomPx(48, 48, 48) = max(0+48, 0+48) = 48
        //
        // …so the model says the union is **48** and the device says **49**. That 1
        // is the whole defect, and it is invisible to every assertion in sections
        // 1..4 because the model's only term is the *placeable* bottom while the
        // device measures *semantics* bounds — a 48 dp `minimumInteractiveComponentSize`
        // centred on a 21-high layout, a term this model does not have.
        //
        // **This assertion is the tripwire.** It is deliberately asserting that the
        // model and the device DISAGREE. The day the 1 px is really fixed on device
        // (unionPx 48, topDelta 0, criterion 1 +1), this line must fail and the
        // device constants above must be re-read — it must never be edited into an
        // `assertEquals` to make it pass.
        val modelUnionBottom = composerBandUnionBottomPx(
            bandPx = devBand,
            editorPx = 48,
            toolRowPx = 48,
        )
        assertEquals(
            "the model's answer for the arrangement's own (48, 48) pair",
            48,
            modelUnionBottom,
        )
        assertEquals(
            "and the model is STILL 1 px short of what the device reads — this is the " +
                "v138 redowngrade: the JVM pin is green while the frame reads $devUnionPx. " +
                "When the device is fixed this must FAIL and force a re-read.",
            1,
            devUnionPx - modelUnionBottom,
        )
        assertEquals(
            "the v136 'fix' is a provable no-op on this form: both children measure " +
                "exactly `band`, so the shared rule returns the same 0 the pre-v136 " +
                "hard-coded `toolRow.place(0, 0)` returned",
            0,
            composerBandChildTopPx(bandPx = devBand, childPx = devBand),
        )
    }
}

/** `44.dp` without importing the whole Dp surface into this file's every signature. */
private fun Int.dp0() = androidx.compose.ui.unit.Dp(this.toFloat())
