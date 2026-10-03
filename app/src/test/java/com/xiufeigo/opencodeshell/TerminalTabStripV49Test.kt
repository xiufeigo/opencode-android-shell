package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.terminal.TerminalStripChrome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v49 tD_65 — the ~49 px blank block at the leading edge of the bottom-docked
 * terminal's tab strip at end-scroll.
 *
 * Verdict: it is the leading edge-fade overlay itself ([STRIP_EDGE_FADE] =
 * 12 dp, ~48 px at the evidence device's density), working as designed — not
 * a clip margin and not a layout bug:
 *
 * - the scroll content is the chips alone (no content padding, no trailing
 *   slot since v47), so no layout margin can park a blank at the edge;
 * - the policy shows the leading overlay exactly while content hides beyond
 *   the leading edge, which is the end-scroll state by definition;
 * - the crop shows the overlay mid-work (the cut glyph fading under it).
 *
 * It only *reads* as a dead block because its opaque end covers the chip's
 * own blank start padding, and it carries no input modifiers — taps fall
 * through to the chip beneath (selecting it). It is kept: the overlay is the
 * shared fade recipe (composer attachment row, `DesktopTabStrip`), and
 * removing it would bring back the mid-glyph hard cuts v47 fixed.
 */
class TerminalTabStripV49Test {

    private fun terminalPanel(): String =
        listOf(
            File("src/main/java/com/xiufeigo/opencodeshell/ui/terminal/TerminalPanel.kt"),
            File("app/src/main/java/com/xiufeigo/opencodeshell/ui/terminal/TerminalPanel.kt"),
        ).firstOrNull { it.isFile }?.readText() ?: error("missing TerminalPanel.kt")

    @Test
    fun `end scroll shows the leading fade only`() {
        // The v49_65 state: scrolled to the end (value == maxScroll > 0), so
        // the "blank block" is the leading overlay the policy asks for.
        assertEquals(
            TerminalStripChrome.EdgeFade(leading = true, trailing = false),
            TerminalStripChrome.fadeEdges(value = 165, maxScroll = 165),
        )
    }

    @Test
    fun `a strip that fits draws no overlay at all`() {
        // The eliminate-the-layout-bug half: with no overflow there is no
        // block on either edge — it cannot be a permanent layout artefact.
        assertEquals(
            TerminalStripChrome.EdgeFade(leading = false, trailing = false),
            TerminalStripChrome.fadeEdges(value = 0, maxScroll = 0),
        )
    }

    @Test
    fun `the scroll content is the chips alone, no clip margin`() {
        // No content padding anywhere in the strip: a blank margin at the
        // leading edge cannot come from the layout.
        assertFalse("no content padding in the strip", terminalPanel().contains("contentPadding"))
    }

    @Test
    fun `the fade overlay carries no input, taps fall through`() {
        val src = terminalPanel()
        val start = src.indexOf("private fun TerminalStripEdgeFade(")
        assertTrue("overlay exists", start >= 0)
        val end = src.indexOf("\n}\n", start)
        assertTrue("overlay ends", end > start)
        val body = src.substring(start, end)
        assertTrue("gradient overlay", body.contains("Brush.horizontalGradient("))
        assertFalse("no clickable on the fade", body.contains("clickable("))
        assertFalse("no semantics on the fade", body.contains("semantics"))
    }
}
