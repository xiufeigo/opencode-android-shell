package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.composer.ComposerPopoverOverlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v67: the two `/` command-panel faults filed against `emulator-5556`
 * (`research/v67_verify/5556/`) and the placement contract that fixes both.
 *
 * Device evidence this pins:
 * - `30_slashpanel.png` / `41_clickbtw.png` — with the keyboard up, the panel
 *   filled the room above the keyboard and the composer card was gone (zero
 *   height); clicking `/btw` did nothing at all.
 * - `31_slashscroll.png` … `40_backslash.png` — the list itself would not
 *   scroll, so `/fork` and every command past the visible rows stayed
 *   unreachable whether the keyboard was up or down.
 * - `42_after.png` — one keystroke (`/x`) brought the composer back: the
 *   layout had been thrashing, not persistently miscalculated.
 *
 * Root cause (see the call-site comment in `Composer.kt`): the popover was a
 * stacked child of the composer column, so it counted toward the measured
 * composer height that `SessionPaneLayout.composerSharesViewport` (the pane's
 * cover/shared decision) reads. On a pane sitting at the threshold the open
 * popover flipped the pane into the shared form, whose `heightIn` cap starved
 * the card's weighted editor, and the capped measurement fell back under the
 * threshold, flipping the pane again — every gesture landed mid-re-parent, so
 * the row click and the list's drag were torn down before they completed.
 * The popover now draws above the stack (`absolute -top-2 -translate-y-full`,
 * `editor.tsx:765-772`) and reports zero stack height.
 */
class ComposerPopoverOverlayV67Test {

    // ------------------------------------------------------- the contract

    @Test
    fun thePopoverReportsNoStackHeight() {
        // Whatever the card measures at — a full `max-h-80` list, the empty
        // state, mid-exit animation — the composer column must see nothing,
        // or the pane decision flaps again.
        assertEquals(0, ComposerPopoverOverlay.STACK_HEIGHT)
    }

    @Test
    fun thePopoverIsPlacedEntirelyAboveTheStack() {
        // Its bottom edge (POPOVER_GAP included) lands on the card's top,
        // exactly where the stacked layout put it — only the accounting
        // changes, not the visual position.
        assertEquals(-96, ComposerPopoverOverlay.contentTop(96))
        assertEquals(-368, ComposerPopoverOverlay.contentTop(368))
        assertEquals(0, ComposerPopoverOverlay.contentTop(0))
    }

    // ----------------------------------------------------------- the wiring

    @Test
    fun thePopoverDrawsAboveWithoutEnteringTheMeasuredHeight() {
        val composer = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt")
        val window = window(composer, ".layout { measurable, constraints ->", "keybindRows = keybindRows,")
        // The slot that used to be a direct AnimatedVisibility child of the
        // composer column is now measured through the zero-report wrapper:
        // full-size measurement in, zero stack height out, placed above.
        assertTrue(window.contains("val popover = measurable.measure(constraints)"))
        assertTrue(window.contains("layout(popover.width, ComposerPopoverOverlay.STACK_HEIGHT)"))
        assertTrue(window.contains("popover.place(0, ComposerPopoverOverlay.contentTop(popover.height))"))
    }

    @Test
    fun aRowClickStillIsAnAccept() {
        val composer = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt")
        val window = window(composer, ".layout { measurable, constraints ->", "keybindRows = keybindRows,")
        // Upstream's row is a button whose click selects the item
        // (`editor.tsx:802`); the tap must run the same accept as Enter, and
        // the pick must still close the card and run/insert the command.
        assertTrue(window.contains("onPick = ::accept"))
    }

    @Test
    fun theShrinkCapStillBoundsTheScrollableList() {
        val popover = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/MentionPopover.kt")
        // v66's repair stands: `max-h-80` ∩ the window's room bounds the card,
        // and the LazyColumn is measured inside those bounds — a shrunken card
        // is a shorter viewport, never an unscrollable cut-off one.
        assertTrue(popover.contains(".heightIn(max = maxHeight)"))
        assertTrue(popover.contains("LazyColumn(modifier = Modifier.fillMaxWidth())"))
    }

    // ------------------------------------------------------------- helpers

    private fun window(src: String, from: String, to: String): String {
        val start = src.indexOf(from)
        require(start >= 0) { "missing $from" }
        val end = src.indexOf(to, start)
        require(end > start) { "missing $to after $from" }
        return src.substring(start, end)
    }

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")
}
