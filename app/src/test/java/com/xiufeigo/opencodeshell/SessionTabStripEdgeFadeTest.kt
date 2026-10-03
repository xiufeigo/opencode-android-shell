package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.terminal.TerminalStripChrome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v48 (`research/v48_verify/tD/tD_39_scroll_right_end`) — the session tab
 * strip's edge fades.
 *
 * The terminal strip already faded both overflowed edges (v47 `tD_43`, the
 * `TerminalStripEdgeFade` overlays), but the session strip scrolled in the
 * same scene hard-cut mid-glyph at full opacity (`itled`, `tD_39`) — the two
 * tab bars faded inconsistently. The session strip now reuses the terminal
 * strip's overlay recipe: the same pure edge policy
 * ([TerminalStripChrome.fadeEdges]) drives a session-background twin overlay
 * (`SessionStripEdgeFade`, `DesktopTabStrip.kt`) pinned to the scrolling
 * list's viewport. The terminal strip itself is untouched.
 *
 * The fades live inside `@Composable` bodies no JVM test can invoke (the same
 * reason `TerminalTabStripV47Test` scans sources), so this pins the wiring at
 * the source level; the screenshots are the visibility proof.
 */
class SessionTabStripEdgeFadeTest {

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText()
    }

    private fun desktop() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/nav/DesktopTabStrip.kt")

    private fun terminalPanel() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/terminal/TerminalPanel.kt")

    // ------------------------------------------- 1. the fade rides the viewport

    @Test
    fun `the session strip fades with viewport-pinned overlays, not an in-scroll mask`() {
        val src = desktop()
        // A mask drawn inside `horizontalScroll` scrolls away with the content
        // (`placeRelativeWithLayer`) — the v47 lesson the terminal strip
        // already learned — so it must not appear here either.
        assertFalse("no in-scroll mask", src.contains("sessionStripEdgeFade"))
        assertFalse("no DstIn in the strip", src.contains("BlendMode.DstIn"))
        assertFalse("no offscreen in the strip", src.contains("CompositingStrategy.Offscreen"))
        // …instead one overlay box per overflowed edge, pinned to the
        // viewport's own un-scrolled Box.
        assertTrue("overlay composable", src.contains("private fun SessionStripEdgeFade("))
        assertTrue("leading overlay", src.contains("SessionStripEdgeFade(leading = true"))
        assertTrue("trailing overlay", src.contains("SessionStripEdgeFade(leading = false"))
        assertTrue("start edge", src.contains("Modifier.align(Alignment.CenterStart)"))
        assertTrue("end edge", src.contains("Modifier.align(Alignment.CenterEnd)"))
    }

    @Test
    fun `the overlay is a plain bar-colour gradient any driver can draw`() {
        val src = desktop()
        // Bar background → transparent (leading) and the mirror (trailing):
        // translucent overdraw on top of the tabs, no blend mode involved. The
        // colour is the session bar's own `bgDeep` — the terminal's `bgBase`
        // would show a seam.
        assertTrue("gradient", src.contains("Brush.horizontalGradient("))
        assertTrue("leading colours", src.contains("listOf(c.bgDeep, Color.Transparent)"))
        assertTrue("trailing colours", src.contains("listOf(Color.Transparent, c.bgDeep)"))
        assertTrue("fade width", src.contains(".width(SESSION_STRIP_EDGE_FADE)"))
        assertTrue("full bar height", src.contains(".fillMaxHeight()"))
    }

    @Test
    fun `each overlay shows only while content hides beyond its edge`() {
        val src = desktop()
        // The terminal strip's own pure policy decides — shared, not copied —
        // read in composition (recomposing two small boxes per fling frame)
        // instead of in a draw phase.
        assertTrue(
            "policy drives overlays",
            src.contains("val edges = TerminalStripChrome.fadeEdges(scroll.value, scroll.maxValue)"),
        )
        assertTrue("leading condition", src.contains("if (edges.leading)"))
        assertTrue("trailing condition", src.contains("if (edges.trailing)"))
    }

    // ------------------------------------------- 2. the strip geometry is kept

    @Test
    fun `a short list still trails the plus instead of floating it right`() {
        val src = desktop()
        // The viewport Box keeps the strip's `weight(1f, fill = false)`
        // geometry: content-width while short, scrolling inside the leftover
        // space while long.
        assertTrue("viewport keeps the weight", src.contains(".weight(1f, fill = false)"))
        // The content-sized scrolled row would otherwise paint past a short
        // viewport — the Box clips it, exactly the old Row bounds.
        assertTrue("viewport clips", src.contains(".clipToBounds()"))
    }

    @Test
    fun `the overlays cannot steal the strip gestures`() {
        val src = desktop()
        val overlays = src.substringAfter("private fun SessionStripEdgeFade(")
        assertFalse("no tap handling", overlays.contains(".clickable("))
        assertFalse("no pointer handling", overlays.contains("pointerInput("))
    }

    // ------------------------------------------- 3. consistent with the terminal strip

    @Test
    fun `both tab bars fade the same width`() {
        // The widths are separate constants (the terminal's stays private in
        // `TerminalPanel.kt`, untouched by this fix), pinned equal here so a
        // width tweak to one fails here instead of drifting apart silently.
        assertTrue(
            "terminal width",
            terminalPanel().contains("private val STRIP_EDGE_FADE = 12.dp"),
        )
        assertTrue(
            "session width",
            desktop().contains("private val SESSION_STRIP_EDGE_FADE = 12.dp"),
        )
    }

    @Test
    fun `the shared edge policy dims only overflowed edges`() {
        // The policy the session strip now reads is the terminal strip's own —
        // unchanged behaviour on both bars.
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
        assertEquals(
            "a strip that fits draws no overlay",
            TerminalStripChrome.EdgeFade(leading = false, trailing = false),
            TerminalStripChrome.fadeEdges(value = 0, maxScroll = 0),
        )
    }

    // ------------------------------------------- 4. the drawer needs no horizontal fade

    @Test
    fun `the mobile drawer has no horizontal scroller to fade`() {
        // `MobileTabsDrawer.kt` is the session strip's other placement, but its
        // list is a vertical `LazyColumn` of full-width ellipsis rows — there
        // is no horizontal clip edge a fade would soften. If a horizontal
        // scroller ever lands there, this is the reminder to fade it too.
        val drawer = source("src/main/java/com/xiufeigo/opencodeshell/ui/nav/MobileTabsDrawer.kt")
        assertFalse("no horizontal scroller", drawer.contains("horizontalScroll"))
    }
}
