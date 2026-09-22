package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.terminal.TerminalPaneSize
import com.xiufeigo.opencodeshell.ui.terminal.TerminalPaneSizes
import com.xiufeigo.opencodeshell.ui.terminal.TerminalPlacement
import com.xiufeigo.opencodeshell.ui.terminal.dockedPanePlacement
import com.xiufeigo.opencodeshell.ui.settings.GENERAL_PANEL
import com.xiufeigo.opencodeshell.ui.settings.TimelineDisplayPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B3 docked-terminal policy: the pure half of the placement setting, the pane
 * geometry the drag handle feeds and the per-session size memory.
 *
 * The numbers are transcriptions of upstream, not invented: `min={100}`,
 * `max = window.innerHeight * 0.6`, `collapseThreshold={50}`
 * (`session/screen.tsx:455-464,517-531`), `Math.min(height, view * 0.6)`
 * (`session/terminal/panel.tsx:75-86`) and
 * `@opencode/ui/src/components/resize-handle.tsx:53-83` for the drag semantics —
 * the rendered size is clamped while the **collapse** verdict is read from the
 * unclamped drag, so exactly 50 is still an open pane and 49 closes it.
 */
class TerminalLayoutTest {

    // ----------------------------------------------------------- placement

    @Test
    fun placementDefaultAndIdsAreUpstreams() {
        // `settings/model.tsx:95,252` — `["side","bottom"]`, default `side`.
        assertEquals(TerminalPlacement.SIDE, TerminalPlacement.Default)
        assertEquals(listOf("side", "bottom"), TerminalPlacement.Ids)
        assertEquals(TerminalPlacement.Ids.toSet(), SettingsStore.PLACEMENTS)
        // The store's default literal and the enum must not drift.
        assertEquals(TerminalPlacement.Default.id, SettingsStore.DEFAULT_TERMINAL_PLACEMENT)
        // …nor may the UI fallback differ from the stored default.
        assertEquals(TerminalPlacement.Default, TimelineDisplayPrefs.Default.terminalPlacement)
    }

    @Test
    fun storedPlacementParsesAndUnknownValuesReadAsTheDefault() {
        assertEquals(TerminalPlacement.BOTTOM, TerminalPlacement.fromId("bottom"))
        assertEquals(TerminalPlacement.SIDE, TerminalPlacement.fromId("side"))
        assertEquals(TerminalPlacement.SIDE, TerminalPlacement.fromId(null))
        assertEquals(TerminalPlacement.SIDE, TerminalPlacement.fromId(""))
        assertEquals(TerminalPlacement.SIDE, TerminalPlacement.fromId("nope"))
    }

    @Test
    fun bothPlacementsAreGatedOnTheDesktopBreakpoint() {
        // `session/screen-layout.ts:19-20` — `isDesktop() && placement == …`; the
        // breakpoint itself is `MobileForm.DESKTOP_MIN_WIDTH_DP` (768).
        assertEquals(TerminalPlacement.SIDE, dockedPanePlacement(TerminalPlacement.SIDE, 768))
        assertEquals(TerminalPlacement.BOTTOM, dockedPanePlacement(TerminalPlacement.BOTTOM, 1024))
        assertNull(dockedPanePlacement(TerminalPlacement.SIDE, 767))
        assertNull(dockedPanePlacement(TerminalPlacement.BOTTOM, 360))
    }

    // ------------------------------------------------------------- geometry

    @Test
    fun ceilingIsSixtyPercentOfTheViewportOnThePanesAxis() {
        assertEquals(480, TerminalPaneSize.maxDp(800))
        assertEquals(463, TerminalPaneSize.maxDp(772))
        assertEquals(0, TerminalPaneSize.maxDp(0))
    }

    @Test
    fun clampKeepsTheMinAndTheSixtyPercentCeiling() {
        // Exactly the minimum stays.
        assertEquals(100, TerminalPaneSize.clamp(100, 800))
        // Below the minimum is lifted to it.
        assertEquals(100, TerminalPaneSize.clamp(50, 800))
        assertEquals(100, TerminalPaneSize.clamp(99, 800))
        // Exactly 60% stays; one dp more is capped.
        assertEquals(480, TerminalPaneSize.clamp(480, 800))
        assertEquals(480, TerminalPaneSize.clamp(481, 800))
        assertEquals(480, TerminalPaneSize.clamp(4_000, 800))
    }

    @Test
    fun degenerateViewportNeverThrowsAndNeverExceedsItsCeiling() {
        // 60% of a tiny viewport is below the minimum: the ceiling wins, because
        // `coerceIn(min, max)` needs an ordered range.
        assertEquals(60, TerminalPaneSize.clamp(500, 100))
        assertEquals(0, TerminalPaneSize.clamp(500, 0))
    }

    @Test
    fun defaultBeforeTheFirstDragIsThePreB3Pane() {
        // Bottom: upstream `DEFAULT_TERMINAL_HEIGHT = 280` capped at 60%.
        assertEquals(280, TerminalPaneSize.defaultDp(TerminalPlacement.BOTTOM, 800))
        assertEquals(240, TerminalPaneSize.defaultDp(TerminalPlacement.BOTTOM, 400))
        // Side: a share of the row (40%), same clamp.
        assertEquals(320, TerminalPaneSize.defaultDp(TerminalPlacement.SIDE, 800))
        assertEquals(307, TerminalPaneSize.defaultDp(TerminalPlacement.SIDE, 768))
        assertEquals(100, TerminalPaneSize.defaultDp(TerminalPlacement.SIDE, 200))
        // The two placements are not the same number: a remembered value belongs
        // to exactly one axis.
        assertNotEquals(
            TerminalPaneSize.defaultDp(TerminalPlacement.SIDE, 800),
            TerminalPaneSize.defaultDp(TerminalPlacement.BOTTOM, 800),
        )
    }

    @Test
    fun restoreFallsBackToTheDefaultAndReclampsAgainstTodaysViewport() {
        assertEquals(280, TerminalPaneSize.restore(null, TerminalPlacement.BOTTOM, 800))
        assertEquals(320, TerminalPaneSize.restore(null, TerminalPlacement.SIDE, 800))
        assertEquals(400, TerminalPaneSize.restore(400, TerminalPlacement.BOTTOM, 800))
        assertEquals(100, TerminalPaneSize.restore(90, TerminalPlacement.BOTTOM, 800))
        // A window that shrank must not keep a pane that swallows the timeline.
        assertEquals(240, TerminalPaneSize.restore(400, TerminalPlacement.BOTTOM, 400))
    }

    // ----------------------------------------------------------------- drag

    @Test
    fun dragClampsToTheMinimumAndTheCeiling() {
        assertEquals(300, TerminalPaneSize.drag(280, growthDp = 20f, viewportDp = 800).sizeDp)
        assertEquals(100, TerminalPaneSize.drag(280, growthDp = -500f, viewportDp = 800).sizeDp)
        assertEquals(480, TerminalPaneSize.drag(280, growthDp = 5_000f, viewportDp = 800).sizeDp)
        assertEquals(480, TerminalPaneSize.drag(470, growthDp = 20f, viewportDp = 800).sizeDp)
    }

    @Test
    fun collapseHappensStrictlyBelowTheThreshold() {
        // Exactly 50 is *not* collapsed (`current < collapseThreshold`), and it
        // renders at the minimum, not at 50.
        val atThreshold = TerminalPaneSize.drag(280, growthDp = -230f, viewportDp = 800)
        assertEquals(100, atThreshold.sizeDp)
        assertFalse(atThreshold.collapsed)

        // Exactly the minimum is not collapsed either.
        val atMinimum = TerminalPaneSize.drag(280, growthDp = -180f, viewportDp = 800)
        assertEquals(100, atMinimum.sizeDp)
        assertFalse(atMinimum.collapsed)

        // One dp past it closes the pane.
        val past = TerminalPaneSize.drag(280, growthDp = -231f, viewportDp = 800)
        assertEquals(100, past.sizeDp)
        assertTrue(past.collapsed)

        // A grown pane is never collapsed, and the verdict ignores the clamp.
        assertFalse(TerminalPaneSize.drag(280, growthDp = 0f, viewportDp = 800).collapsed)
        assertTrue(TerminalPaneSize.drag(40, growthDp = -1f, viewportDp = 800).collapsed)
    }

    @Test
    fun growthIsMeasuredFromTheGestureStartNotFromTheClampedSize() {
        // The whole gesture's growth is added to the size the drag *started* at,
        // exactly like `current = startSize + delta` (`resize-handle.tsx:63`): a
        // drag that ran into the floor and came back does not immediately grow
        // again, it has to cross the floor first.
        assertEquals(480, TerminalPaneSize.drag(280, growthDp = 400f, viewportDp = 800).sizeDp)
        assertEquals(280, TerminalPaneSize.drag(280, growthDp = 0f, viewportDp = 800).sizeDp)
        assertEquals(100, TerminalPaneSize.drag(280, growthDp = -180f, viewportDp = 800).sizeDp)
        // Starting from 100 (the floor) with the same accumulated growth gives the
        // same answer as starting from 280.
        assertEquals(
            TerminalPaneSize.drag(280, growthDp = -180f, viewportDp = 800).sizeDp,
            TerminalPaneSize.drag(100, growthDp = 0f, viewportDp = 800).sizeDp,
        )
    }

    @Test
    fun pointerDeltaMapsToGrowthAlongTheSplittingEdge() {
        // Bottom pane: the handle is on the top edge, so dragging up (`-dy`) grows.
        assertEquals(12f, TerminalPaneSize.growth(deltaDp = -12f, vertical = true, rtl = false))
        assertEquals(-12f, TerminalPaneSize.growth(deltaDp = 12f, vertical = true, rtl = false))
        // Side pane docked at the end of the row: its leading edge moves towards
        // the start to grow it, mirroring `ReviewPanelWidth.resize` (`current - dx`).
        assertEquals(12f, TerminalPaneSize.growth(deltaDp = -12f, vertical = false, rtl = false))
        assertEquals(-12f, TerminalPaneSize.growth(deltaDp = 12f, vertical = false, rtl = false))
        // RTL flips the horizontal axis back.
        assertEquals(-12f, TerminalPaneSize.growth(deltaDp = -12f, vertical = false, rtl = true))
        // …and leaves the vertical one alone.
        assertEquals(12f, TerminalPaneSize.growth(deltaDp = -12f, vertical = true, rtl = true))
    }

    // ----------------------------------------------------- remembered sizes

    @Test
    fun rememberedSizesAreKeyedPerSessionAndPerPlacement() {
        val side = TerminalPaneSizes.key("ses_1", TerminalPlacement.SIDE)
        val bottom = TerminalPaneSizes.key("ses_1", TerminalPlacement.BOTTOM)
        assertNotEquals(side, bottom)
        assertNotEquals(side, TerminalPaneSizes.key("ses_2", TerminalPlacement.SIDE))
        assertEquals("ses_1|side", side)
    }

    @Test
    fun rememberedSizesRoundTripThroughTheSaveableEncoding() {
        val sizes = TerminalPaneSizes()
        sizes[TerminalPaneSizes.key("ses_1", TerminalPlacement.BOTTOM)] = 372
        sizes[TerminalPaneSizes.key("ses_2", TerminalPlacement.SIDE)] = 420

        val restored = TerminalPaneSizes.decode(sizes.encoded())
        assertEquals(372, restored[TerminalPaneSizes.key("ses_1", TerminalPlacement.BOTTOM)])
        assertEquals(420, restored[TerminalPaneSizes.key("ses_2", TerminalPlacement.SIDE)])
        assertNull(restored[TerminalPaneSizes.key("ses_3", TerminalPlacement.BOTTOM)])

        // An untouched holder restores to "nothing remembered", which is what
        // makes the pre-first-drag default apply.
        assertTrue(TerminalPaneSizes().encoded().isEmpty())
        assertTrue(TerminalPaneSizes.decode(emptyList()).encoded().isEmpty())
    }

    @Test
    fun malformedRememberedRowsAreDroppedInsteadOfCrashingTheRestore() {
        val restored = TerminalPaneSizes.decode(listOf("ses_1|bottom=372", "junk", "=5", "ses_2|side=x", "ses_3|side=-7"))
        assertEquals(372, restored["ses_1|bottom"])
        assertNull(restored["junk"])
        assertNull(restored["ses_2|side"])
        assertEquals(-7, restored["ses_3|side"])
    }

    // ------------------------------------------------- settings panel wiring

    @Test
    fun thePlacementRowOffersExactlyTheTwoUpstreamLiterals() {
        val row = GENERAL_PANEL.flatMap { it.rows }.single { it.action == "settings-terminal-placement" }
        assertEquals(TerminalPlacement.Ids, row.options.map { it.value })
        assertEquals(R.string.oc_setting_terminal_placement, row.titleResId)
    }
}
