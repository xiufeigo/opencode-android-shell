package com.xiufeigo.opencodeshell

import androidx.compose.ui.text.TextRange
import com.xiufeigo.opencodeshell.ui.nav.tabRenameEntrySelection
import com.xiufeigo.opencodeshell.ui.terminal.TerminalStripChrome
import com.xiufeigo.opencodeshell.ui.terminal.TerminalTabRename
import com.xiufeigo.opencodeshell.ui.terminal.TerminalTabScroll
import com.xiufeigo.opencodeshell.ui.terminal.TerminalTabTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v47 (`research/v47_verify/tD`) — the terminal tab strip's "fade + over-scroll
 * + rename prefill" batch, verified against a real device (source-level pins
 * below guard the mechanism; the screenshots are the visibility proof).
 *
 * 1. `tD_37_mid2` — mid-scroll, `Terminal 28` hard-cut by the right edge and
 *    `Terminal 27` cut to `erminal 27` by the left edge, NO fade on either
 *    side. Root cause (not the blend mode, not the offscreen): the v46 `DstIn`
 *    mask was drawn by a modifier *inside* `horizontalScroll`, but
 *    `horizontalScroll` offsets its content with a placement-layer transform
 *    (`ScrollingLayoutNode.measure` → `placeRelativeWithLayer`, foundation
 *    1.7.6 `Scroll.kt`) — so the mask was drawn in *content* coordinates and
 *    translated away with the scroll. Past the first fade-width of travel both
 *    masks sat off-viewport (leading fully clipped, trailing shifted onto
 *    empty row space where it erases nothing, because the strip background
 *    lives outside the mask's layer) — hence hard cuts *and* no erased slit.
 *    The strip now fades with overlay boxes pinned to the viewport
 *    (`TerminalStripEdgeFade`), the same recipe the composer's attachment row
 *    already ships (`AttachmentEdgeFade`): strip-colour → transparent, no
 *    blend modes, nothing a driver can silently drop.
 *
 * 2. `tD_35_right2` / `tD_39` — scrolled to the end, the last tab stops at
 *    `x≈590` while the viewport runs to `980`: the v46 trailing slot (a
 *    `Spacer` as wide as the fixed action group, *inside* the scroll content)
 *    scrolled as ~390 px of dead travel past the last chip. The slot is gone —
 *    the scrollable content is the chips alone — and the active-chip reveal
 *    ends at the chip's own end (still clamped to `[0, maxScroll]`), so the
 *    last chip parks with its end flush at the viewport end, fully visible.
 *
 * 3. `tD_45_rename` / `tD_46_renamed` — the rename field opened on the
 *    placeholder alone (empty value), so one keystroke renamed the tab to just
 *    that stroke (`-R`). The draft now opens prefilled with the chip's
 *    displayed title (custom name, else the numbered default — a numbered tab
 *    usually has no custom title, which is why the prefill reads through the
 *    resolved label rather than the raw model), with the whole title selected
 *    on entry, matching the session strips.
 */
class TerminalTabStripV47Test {

    private fun terminalPanel(): String =
        listOf(
            File("src/main/java/com/xiufeigo/opencodeshell/ui/terminal/TerminalPanel.kt"),
            File("app/src/main/java/com/xiufeigo/opencodeshell/ui/terminal/TerminalPanel.kt"),
        ).firstOrNull { it.isFile }?.readText() ?: error("missing TerminalPanel.kt")

    // ------------------------------------------- 1. the fade rides the viewport

    @Test
    fun `the strip fades with viewport-pinned overlays, not an in-scroll mask`() {
        val src = terminalPanel()
        // The v46 mask drew inside `horizontalScroll` and scrolled away with
        // the content (`placeRelativeWithLayer`) — it must not come back.
        assertFalse("no in-scroll mask", src.contains("terminalStripEdgeFade"))
        assertFalse("no DstIn in the strip", src.contains("BlendMode.DstIn"))
        assertFalse("no offscreen in the strip", src.contains("CompositingStrategy.Offscreen"))
        // …instead one overlay box per overflowed edge, pinned to the
        // viewport's own un-scrolled Box.
        assertTrue("overlay composable", src.contains("private fun TerminalStripEdgeFade("))
        assertTrue("leading overlay", src.contains("TerminalStripEdgeFade(leading = true"))
        assertTrue("trailing overlay", src.contains("TerminalStripEdgeFade(leading = false"))
        assertTrue("start edge", src.contains("Modifier.align(Alignment.CenterStart)"))
        assertTrue("end edge", src.contains("Modifier.align(Alignment.CenterEnd)"))
    }

    @Test
    fun `the overlay is a plain strip-colour gradient any driver can draw`() {
        val src = terminalPanel()
        // Strip background → transparent (leading) and the mirror (trailing):
        // translucent overdraw on top of the chips, no blend mode involved.
        assertTrue("gradient", src.contains("Brush.horizontalGradient("))
        assertTrue("leading colours", src.contains("listOf(c.bgBase, Color.Transparent)"))
        assertTrue("trailing colours", src.contains("listOf(Color.Transparent, c.bgBase)"))
        assertTrue("fade width", src.contains(".width(STRIP_EDGE_FADE)"))
        assertTrue("full chip height", src.contains(".fillMaxHeight()"))
    }

    @Test
    fun `each overlay shows only while content hides beyond its edge`() {
        val src = terminalPanel()
        // The same pure policy v46 introduced still decides — now read in
        // composition (recomposing two small boxes per fling frame) instead of
        // in a draw phase.
        assertTrue(
            "policy drives overlays",
            src.contains("val edges = TerminalStripChrome.fadeEdges(scroll.value, scroll.maxValue)"),
        )
        assertTrue("leading condition", src.contains("if (edges.leading)"))
        assertTrue("trailing condition", src.contains("if (edges.trailing)"))
    }

    // ------------------------------------------- 2. no dead travel past the end

    @Test
    fun `no trailing slot scrolls inside the chips row`() {
        val src = terminalPanel()
        // The v46 spacer is what produced the ~390 px dead zone past the last
        // chip (`tD_35_right2`): the scrollable content is the chips alone now.
        assertFalse("no slot spacer", src.contains("Spacer(Modifier.width(ICON_TARGET * trailingCount))"))
        assertFalse("no slot count", src.contains("trailingActionCount("))
    }

    @Test
    fun `the reveal parks the last chip flush at the viewport end`() {
        // Two 160 px chips, viewport 155, no trailing slot: the last chip spans
        // 160..320 and showing its end takes exactly 320 - 155.
        assertEquals(
            165,
            TerminalTabScroll.reveal(
                start = 160, width = 160, viewport = 155,
                current = 0, maxScroll = 165,
            ),
        )
        // …and the strip calls it with no end padding: the source of the old
        // dead travel was the call site, not the clamp.
        assertTrue(
            "reveal carries no slot",
            terminalPanel().contains(
                "TerminalTabScroll.reveal(start, width, viewport, scroll.value, scroll.maxValue)",
            ),
        )
    }

    // ------------------------------------------- 3. rename prefills the current name

    @Test
    fun `the rename draft is the displayed title, not the raw model`() {
        // A numbered tab with no custom title prefills its own label…
        assertEquals(
            "Terminal 28",
            TerminalTabTitle.resolve(null, 28) { number -> "Terminal $number" },
        )
        // …a blank custom title still falls back to it…
        assertEquals(
            "Terminal 28",
            TerminalTabTitle.resolve("   ", 28) { number -> "Terminal $number" },
        )
        // …and a custom title is kept verbatim.
        assertEquals(
            "my db",
            TerminalTabTitle.resolve("my db", 28) { number -> "Terminal $number" },
        )
    }

    @Test
    fun `the chip seeds its draft from the displayed title`() {
        val src = terminalPanel()
        // `title` here is the already-resolved chip label
        // (`TerminalTabTitle.resolve(tab.title, tab.number)` at the call site),
        // so an untitled tab edits "Terminal 28" instead of an empty value.
        assertTrue(
            "draft prefilled",
            src.contains("var draft by remember(title, editing) { mutableStateOf(title) }"),
        )
        assertFalse("no empty seed", src.contains("mutableStateOf(currentTitle ?: \"\")"))
        assertTrue("menu seeds the label", src.contains("draft = title"))
    }

    @Test
    fun `the terminal rename entry selection matches the session strips`() {
        // Whole-title selection on entry, exactly like `tabRenameEntrySelection`
        // (`BottomTabStrip.kt`) and the header's `InlineTitleEdit.entrySelection`.
        assertEquals(TextRange(0, 11), TerminalTabRename.entrySelection("Terminal 28"))
        assertEquals(TextRange(0, 0), TerminalTabRename.entrySelection(""))
        assertEquals(tabRenameEntrySelection("Terminal 28"), TerminalTabRename.entrySelection("Terminal 28"))
        assertEquals(tabRenameEntrySelection(""), TerminalTabRename.entrySelection(""))
        assertTrue(
            "field carries the selection",
            terminalPanel().contains(
                "var edit by remember { mutableStateOf(TextFieldValue(value, TerminalTabRename.entrySelection(value))) }",
            ),
        )
    }

    // ------------------------------------------------------------ edge policy kept

    @Test
    fun `the fade policy still dims only overflowed edges`() {
        // Unchanged from v46 — the overlay only changed *how* the policy draws.
        assertEquals(
            TerminalStripChrome.EdgeFade(leading = false, trailing = true),
            TerminalStripChrome.fadeEdges(value = 0, maxScroll = 500),
        )
        assertEquals(
            TerminalStripChrome.EdgeFade(leading = true, trailing = true),
            TerminalStripChrome.fadeEdges(value = 250, maxScroll = 500),
        )
        assertEquals(
            TerminalStripChrome.EdgeFade(leading = true, trailing = false),
            TerminalStripChrome.fadeEdges(value = 500, maxScroll = 500),
        )
    }
}
