package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.palette.PaletteLayerState
import com.xiufeigo.opencodeshell.ui.palette.onScrimTap
import com.xiufeigo.opencodeshell.ui.palette.paletteBoxHeight
import com.xiufeigo.opencodeshell.ui.palette.paletteFallbackGuardedBottom
import com.xiufeigo.opencodeshell.ui.palette.paletteShell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v98 — **the command palette's dead zone** (`research/v97_verify/5564_v98/REPORT_PERF.md`
 * §6.3: 「命令面板（Command Palette）打开后遮罩吞掉了底栏点击」, both form factors).
 *
 * What the device showed: a tap on the bottom tab strip while the palette was
 * open did **nothing at all** — the palette did not close and the strip did not
 * get the event. The perf run's own evidence rules out a stall (UI node count
 * constant across 14 polls / 28 s, 196 frames at p99 = 101 ms, zero ANR), so it
 * is hit-testing, not performance.
 *
 * Cause, in one line: `CommandPalette` never reported its card bounds, so
 * [com.xiufeigo.opencodeshell.ui.overlay.DialogHost] guarded its scrim against
 * the **full-width layout box** the host measured as the layer's content, and
 * every tap inside that box — the bottom strip among it — was answered "inside
 * the content box" by `scrimTapDismisses`: no dismissal, and no hand-down.
 *
 * The palette autofocuses its search field, so the IME is up whenever the layer
 * is open; the IME shrinks `visibleHeight`, which collapses the shell's
 * `topMargin` onto its 48 dp floor and drags the layout box's bottom edge up
 * into the tab strip. That is what made the dead zone, and it is why it
 * reproduced in the default and the `vertical` form alike.
 *
 * Two pins, per the task: the **structure** (the card must report its bounds,
 * like every other modal — `DialogScrimResidueTest` already pins the idiom for
 * thirteen hand-rolled surfaces; the palette was the one riding `DialogHost`
 * that never joined them) and the **close exit** as a pure state transition
 * ([onScrimTap]). No Compose, no Robolectric.
 */
class PaletteScrimDeadZoneV98Test {

    // ------------------------------------------------------ the two form factors

    /** 1200x1600 @320, `vertical` — the mobile form of REPORT_PERF §1.5. */
    private val mobileWindowHeight = 1600f / (320f / 160f)

    /** 2076x2152 @390 — the default form of REPORT_PERF §1.5. */
    private val defaultWindowHeight = 2152f / (390f / 160f)

    /** A representative soft-keyboard height, as the autofocus makes it the default state. */
    private val imeHeight = 300f

    /** `BottomTabStrip`'s band at the foot of the window (dp, measured for this pin). */
    private val tabStripHeight = 80f

    private fun tabStripTop(windowHeight: Float): Float = windowHeight - tabStripHeight

    // ------------------------------------------------- 1. the guarded box (the bug)

    @Test
    fun theUnreportedLayoutBoxCoveredTheBottomTabStripOnBothFormFactors() {
        // The box the host measured as the palette layer's content: full width,
        // paletteBoxHeight tall, centred in the window.
        for (windowHeight in listOf(mobileWindowHeight, defaultWindowHeight)) {
            val visibleHeight = windowHeight - imeHeight
            val shell = paletteShell(windowHeight, visibleHeight)
            val boxHeight = paletteBoxHeight(windowHeight, shell.topMargin)

            // Centred in the window (DialogHost's Alignment.Center), so the box's
            // top edge lands exactly on the shell's margin-top and its bottom
            // edge is `windowHeight - topMargin`.
            assertEquals(
                "centering must put the box's top edge on margin-top",
                shell.topMargin,
                (windowHeight - boxHeight) / 2f,
                0.01f,
            )
            val guardedBottom = paletteFallbackGuardedBottom(windowHeight, shell.topMargin)
            assertEquals(
                "the guarded box's bottom edge must match the centred layout box",
                shell.topMargin + boxHeight,
                guardedBottom,
                0.01f,
            )

            // The IME is what collapses topMargin toward its 48 dp floor, and
            // that is what pulls the guarded box down over the strip. (Exactly
            // 48 on the phone form; a little above it on the taller default
            // form, which the overlap check below absorbs.)
            assertTrue(
                "window=$windowHeight: the IME must have collapsed margin-top (was " +
                    "${shell.topMargin})",
                shell.topMargin < tabStripHeight,
            )
            assertTrue(
                "window=$windowHeight: the guarded box reached into the tab strip " +
                    "(guarded bottom ${guardedBottom} > strip top ${tabStripTop(windowHeight)})",
                guardedBottom > tabStripTop(windowHeight),
            )
        }
    }

    @Test
    fun theDeadZoneIsTheImeNotTheFormFactor() {
        // Honest scoping: with the keyboard down the centred box clears the
        // strip on the phone form, so this is not a form-factor bug. The
        // autofocus is what makes the IME the default state of an open palette.
        val shell = paletteShell(mobileWindowHeight, mobileWindowHeight)
        assertTrue(
            "with the IME down the layout box cleared the strip, which is why only " +
                "an open (autofocused) palette shows the dead zone",
            paletteFallbackGuardedBottom(mobileWindowHeight, shell.topMargin) <=
                tabStripTop(mobileWindowHeight),
        )
    }

    // --------------------------------------------- 2. the card, not the layout box

    @Test
    fun theReportedCardClearsTheTabStripOnBothFormFactors() {
        // The fix is structural: the card reports its own bounds, so the guarded
        // box is `topMargin + cardHeight` tall at worst (maxHeight), not
        // `windowHeight - topMargin`.
        for (windowHeight in listOf(mobileWindowHeight, defaultWindowHeight)) {
            val shell = paletteShell(windowHeight, windowHeight - imeHeight)
            val cardBottom = shell.topMargin + shell.maxHeight
            assertTrue(
                "window=$windowHeight: the card (bottom $cardBottom) must stop well " +
                    "above the tab strip (top ${tabStripTop(windowHeight)})",
                cardBottom < tabStripTop(windowHeight),
            )
            // …and the layout box is strictly the bigger of the two, so the fix
            // actually moved the boundary rather than restating the same number.
            assertTrue(
                "the layout box must remain the dead zone, or this pin proves nothing",
                paletteFallbackGuardedBottom(windowHeight, shell.topMargin) > cardBottom,
            )
        }
    }

    // -------------------------------------------------- 3. the close exit (state)

    @Test
    fun anOpenPaletteAlwaysHasAWorkingCloseExitOnItsBackdrop() {
        // The dead zone's exit: open + backdrop tap ⇒ closed. There is no third
        // outcome in which the tap is swallowed.
        assertEquals(PaletteLayerState.CLOSED, onScrimTap(open = true, tapInsideCard = false))
    }

    @Test
    fun aTapOnTheCardLeavesThePaletteOpen() {
        // Unchanged semantics: a tap on the card is a tap on the dialog, not on
        // the backdrop (the rule in DialogLayerPolicy).
        assertEquals(PaletteLayerState.OPEN, onScrimTap(open = true, tapInsideCard = true))
    }

    @Test
    fun aClosedLayerStaysClosed() {
        assertEquals(PaletteLayerState.CLOSED, onScrimTap(open = false, tapInsideCard = false))
        assertEquals(PaletteLayerState.CLOSED, onScrimTap(open = false, tapInsideCard = true))
    }

    // --------------------------------------------------- 4. the structural pin

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText()
    }

    private val palette = source("src/main/java/com/xiufeigo/opencodeshell/ui/palette/CommandPalette.kt")

    @Test
    fun thePaletteCardReportsItsBoundsSoTheScrimHasARealGuard() {
        // The host guards taps against the layer's *reported* card; a layer that
        // reports nothing is guarded against its own measured content box.
        assertTrue(
            "the palette must read the layer's scrim state",
            palette.contains("LocalDialogScrimState.current"),
        )
        assertTrue(
            "the palette card must report its bounds — without it the layer is " +
                "guarded against the full-width layout box and swallows the tab strip",
            palette.contains("Modifier.dialogCardBounds(scrim)"),
        )
    }

    @Test
    fun theBoundsMarkerRidesTheCardNotTheLayoutBox() {
        // Same placement rule as every other card (DialogScrim's doc): after the
        // size modifiers, before the card's own decoration. A marker on the
        // outer `fillMaxWidth` box would just re-report the dead zone.
        val chain = palette.substringAfter("Column(").substringBefore(".onPreviewKeyEvent")
        assertTrue("the card chain must size itself", chain.contains(".width(width)"))
        assertTrue(
            "the card chain must cap its height",
            chain.contains(".heightIn(min = PALETTE_MIN_HEIGHT.dp, max = maxHeight)"),
        )
        val widthAt = chain.indexOf(".width(width)")
        val boundsAt = chain.indexOf("Modifier.dialogCardBounds(scrim)")
        val shadowAt = chain.indexOf(".shadow(")
        assertTrue("the marker must follow the size modifiers", widthAt in 0 until boundsAt)
        assertTrue("the marker must precede the card's own decoration", boundsAt < shadowAt)

        // The layout box the bug lived in must stay marker-free.
        val outer = palette.substringAfter("Box(\n        modifier.fillMaxWidth()")
            .substringBefore("Column(")
        assertFalse(
            "the full-width layout box must not report card bounds",
            outer.contains("dialogCardBounds"),
        )
    }

    @Test
    fun thePaletteAddsNoBackdropOfItsOwn() {
        // One scrim per layer: a private backdrop would dim the screen twice and
        // give the palette a second, differently-ruled close path. The palette's
        // `clickable`s are its *rows* — the residue scan
        // (`DialogScrimResidueTest`) is what proves no full-screen one is left.
        assertFalse("no hand-rolled backdrop", palette.contains("detectTapGestures"))
        assertFalse("no hand-rolled backdrop", palette.contains("DialogScrim("))
    }

    @Test
    fun theExitSemanticsAreUntouched() {
        // v50: a closing layer leaves the hit path so the first tap after a
        // close reaches the screen behind. The palette owns none of that — it
        // must not grow its own exit gating that would re-block that window.
        val policy = source("src/main/java/com/xiufeigo/opencodeshell/ui/overlay/DialogLayerPolicy.kt")
        assertTrue(policy.contains("fun acceptsPointerInput(closing: Boolean): Boolean"))
        assertTrue(policy.contains("fun isHitTransparent(closing: Boolean): Boolean"))
        assertTrue(policy.contains("fun ghostOffsetPx("))
        // …and the palette keeps its own Escape, unchanged.
        assertTrue("the palette keeps its Escape close", palette.contains("Key.Escape"))
    }
}
