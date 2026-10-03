package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.terminal.TerminalStripChrome
import com.xiufeigo.opencodeshell.ui.terminal.TerminalTabScroll
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v46 (`research/v46_verify/tD`) — the terminal tab strip's occlusion + edges:
 *
 * 1. `tD_29/31/39/48` — scrolled right, the last tab's ×/⋯ sat flush at the
 *    clip edge next to the fixed `>_` / shells / `+` group and read as
 *    "pressed under the buttons, unreachable". The scrollable row now reserves
 *    a trailing slot exactly as wide as that group, and the active-chip reveal
 *    keeps the slot visible for the last chip.
 * 2. `tD_28/29/47` — a mid-scroll chip hard-cut mid-glyph (`erminal 14`, a chip
 *    decayed to bare `× ⋯` with no title), which reads as broken rendering.
 *    The chips viewport now wears an edge-fade mask, so a clipped chip fades
 *    out instead; a clipped tab never loses its title/×/⋯ in composition —
 *    the mask only affects drawing.
 *
 * 3. `tD_50/52/53/54/55` (⋯ hit area, investigated, NOT a bug): the taps that
 *    opened the menu (`x≈240/280`) land inside the ⋯ button's 40 dp hit box
 *    (≈105 px at the device density, centred on the dots at `x≈260`), while
 *    `x≈330` — just past the box, on the next chip's padding — opened nothing
 *    (`tD_52`) and `x≈160` (the ×) opened nothing (`tD_55`). The "gap" the pass
 *    suspected is the invisible part of the intentional touch target (below
 *    even the 48 dp Material minimum), so the target stays as is — pinned
 *    below so it cannot silently grow.
 *
 * NOTE (v47): the trailing-slot and `DstIn`-mask halves of this fix are
 * superseded — the slot scrolled as dead travel past the last chip
 * (`tD_35_right2/39`) and the in-scroll mask scrolled away with the content
 * (`tD_37_mid2`); both are pinned in `TerminalTabStripV47Test` now. What stays
 * here is the still-true pure arithmetic plus the hit-area verdict.
 */
class TerminalTabStripV46Test {

    private fun terminalPanel(): String =
        listOf(
            File("src/main/java/com/xiufeigo/opencodeshell/ui/terminal/TerminalPanel.kt"),
            File("app/src/main/java/com/xiufeigo/opencodeshell/ui/terminal/TerminalPanel.kt"),
        ).firstOrNull { it.isFile }?.readText() ?: error("missing TerminalPanel.kt")

    // ------------------------------------------- 1. reveal arithmetic (v47: no slot)

    @Test
    fun `the reveal parks the last chip at its own end (v47 - the slot is gone)`() {
        // Two 160 px chips, viewport 155, no trailing slot: the last chip spans
        // 160..320, so showing its end takes exactly 320 - 155.
        assertEquals(
            165,
            TerminalTabScroll.reveal(
                start = 160, width = 160, viewport = 155,
                current = 0, maxScroll = 165,
            ),
        )
        // …and a strip whose ceiling is shorter still clamps instead of
        // overshooting.
        assertEquals(
            165,
            TerminalTabScroll.reveal(
                start = 160, width = 160, viewport = 155,
                current = 0, maxScroll = 200,
            ),
        )
    }

    @Test
    fun `padding never moves a middle or leading chip`() {
        // A middle chip reveals with no padding: its own end is all the
        // viewport has to show (the default keeps every old call site put).
        assertEquals(
            TerminalTabScroll.reveal(start = 160, width = 160, viewport = 155, current = 0, maxScroll = 285),
            TerminalTabScroll.reveal(
                start = 160, width = 160, viewport = 155,
                current = 0, maxScroll = 285, endPadding = 0,
            ),
        )
        // The leading side lines the chip start up; padding is trailing-only.
        assertEquals(
            0,
            TerminalTabScroll.reveal(
                start = 0, width = 160, viewport = 155,
                current = 165, maxScroll = 285, endPadding = 120,
            ),
        )
        // A negative padding is not a direction: it reads as none.
        assertEquals(
            165,
            TerminalTabScroll.reveal(
                start = 160, width = 160, viewport = 155,
                current = 0, maxScroll = 285, endPadding = -50,
            ),
        )
    }

    // ------------------------------------------------- 2. edge-fade arithmetic

    @Test
    fun `a strip that fits draws no mask`() {
        val edges = TerminalStripChrome.fadeEdges(value = 0, maxScroll = 0)
        assertFalse(edges.leading)
        assertFalse(edges.trailing)
        // A negative max (an unmeasured strip) reads as "cannot scroll".
        val unmeasured = TerminalStripChrome.fadeEdges(value = 0, maxScroll = -5)
        assertFalse(unmeasured.leading)
        assertFalse(unmeasured.trailing)
    }

    @Test
    fun `each edge fades only while content hides beyond it`() {
        // At the start only the trailing edge fades…
        assertEquals(
            TerminalStripChrome.EdgeFade(leading = false, trailing = true),
            TerminalStripChrome.fadeEdges(value = 0, maxScroll = 500),
        )
        // …mid-scroll both do…
        assertEquals(
            TerminalStripChrome.EdgeFade(leading = true, trailing = true),
            TerminalStripChrome.fadeEdges(value = 250, maxScroll = 500),
        )
        // …and at the far end only the leading edge does.
        assertEquals(
            TerminalStripChrome.EdgeFade(leading = true, trailing = false),
            TerminalStripChrome.fadeEdges(value = 500, maxScroll = 500),
        )
    }

    // ------------------------------------------------------------ source pins

    @Test
    fun `the chips row wears viewport overlays and reserves no slot (v47)`() {
        val src = terminalPanel()
        // v47 moved the mechanism pins to `TerminalTabStripV47Test`; this stays
        // as the history anchor: the scroll drives the row, the overlays fade
        // it, and the reveal carries no trailing padding.
        assertTrue("scroll drives the row", src.contains(".horizontalScroll(scroll),"))
        assertTrue("overlay fade", src.contains("TerminalStripEdgeFade(leading = true"))
        assertTrue(
            "reveal carries no slot",
            src.contains("TerminalTabScroll.reveal(start, width, viewport, scroll.value, scroll.maxValue)"),
        )
    }

    @Test
    fun `the dots hit target stays one ghost icon box`() {
        val src = terminalPanel()
        // The tD_50/53/54 verdict: taps at x≈240/280 open the menu because they
        // are *on* the ⋯ button's 40 dp box, not because a gap mis-hits. The
        // box must neither grow (dead taps around the chip) nor shrink (the
        // 16 dp glyph alone is untappable); the menu still opens only from it.
        assertTrue("ghost icon box", src.contains("private val ICON_TARGET = 40.dp"))
        assertTrue("menu opens from the dots", src.contains("onOpenMenu = { menuFor = tab.id }"))
    }
}
