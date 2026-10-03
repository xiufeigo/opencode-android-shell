package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.terminal.TerminalTabScroll
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P1: the terminal strip's `+` **did** create a second tab (the server log shows
 * a `POST /api/pty` per press and the persisted tab list carried every id), but
 * the chip was never visible — the side pane leaves ≈155 dp of chip space for a
 * ≈160 dp chip, so the focused new tab sat entirely outside the viewport and the
 * strip still read 「Terminal 1」 while the pane showed the new (fresh) shell.
 * The device pass reported that as "the `+` does nothing and the PTY restarted".
 *
 * [TerminalTabScroll.reveal] is the pure half of the fix; the source pins below
 * hold the wiring that feeds it (a hoisted `ScrollState`, the running-sum chip
 * offsets, the animation).
 */
class TerminalNewTabVisibilityTest {

    private fun terminalPanel(): String =
        listOf(
            File("src/main/java/com/xiufeigo/opencodeshell/ui/terminal/TerminalPanel.kt"),
            File("app/src/main/java/com/xiufeigo/opencodeshell/ui/terminal/TerminalPanel.kt"),
        ).firstOrNull { it.isFile }?.readText() ?: error("missing TerminalPanel.kt")

    // ------------------------------------------------------------- arithmetic

    @Test
    fun `a chip already inside the viewport does not move the strip`() {
        // Viewport 0..155, chip 0..160: its tail is cut off by 5 px, and the
        // strip may not scroll at all (maxScroll = 0) — no movement.
        assertEquals(0, TerminalTabScroll.reveal(start = 0, width = 160, viewport = 155, current = 0, maxScroll = 0))
        // A chip fully inside keeps the user's own scroll position.
        assertEquals(40, TerminalTabScroll.reveal(start = 60, width = 80, viewport = 155, current = 40, maxScroll = 400))
    }

    @Test
    fun `the second chip is revealed by scrolling exactly its tail into view`() {
        // Two 160 px chips, viewport 155: the second spans 160..320, so the
        // minimal offset that shows it whole is 320 - 155 = 165.
        assertEquals(165, TerminalTabScroll.reveal(start = 160, width = 160, viewport = 155, current = 0, maxScroll = 165))
    }

    @Test
    fun `a chip before the viewport scrolls back to its start`() {
        // The active tab is the first one while the strip sits at the far end.
        assertEquals(0, TerminalTabScroll.reveal(start = 0, width = 160, viewport = 155, current = 165, maxScroll = 165))
        assertEquals(20, TerminalTabScroll.reveal(start = 20, width = 160, viewport = 155, current = 165, maxScroll = 400))
    }

    @Test
    fun `the result is clamped to the scrollable range`() {
        // A viewport wider than the content can never scroll: the target is 0.
        assertEquals(0, TerminalTabScroll.reveal(start = 320, width = 160, viewport = 900, current = 0, maxScroll = 0))
        // A negative max (an unmeasured strip) is treated as "cannot scroll".
        assertEquals(0, TerminalTabScroll.reveal(start = 320, width = 160, viewport = 155, current = 0, maxScroll = -1))
    }

    @Test
    fun `an unmeasured strip stays where it is`() {
        // `viewportSize` is 0 until the first layout: moving on a guess would
        // scroll the strip to a position the content does not have yet.
        assertEquals(7, TerminalTabScroll.reveal(start = 320, width = 160, viewport = 0, current = 7, maxScroll = 500))
    }

    // ------------------------------------------------------------ source pins

    @Test
    fun `the strip scrolls the active chip into view`() {
        val src = terminalPanel()
        // One hoisted ScrollState drives the chips row (a fresh
        // `rememberScrollState()` per composition could never be scrolled).
        assertTrue("hoisted strip scroll", src.contains("val scroll = rememberScrollState()"))
        // D-15b: the call carries the press-suspension argument
        // (`enabled = chipDownId == null`, pinned in `TerminalTabDragClaimTest`),
        // so only the call's head is matched here.
        assertTrue("chips row uses it", src.contains(".horizontalScroll(scroll"))
        assertTrue("reveal arithmetic", src.contains("TerminalTabScroll.reveal("))
        assertTrue("actually scrolls", src.contains("scroll.animateScrollTo(target)"))
        // The offsets are the running sum of the widths the drag already
        // measures (the chips sit flush, so a chip's width is the whole pitch).
        assertTrue("chip widths", src.contains("chipWidths[tab.id]"))
        assertTrue("layout wait", src.contains("withFrameNanos"))
    }

    @Test
    fun `the plus button still creates the tab it has to reveal`() {
        val src = terminalPanel()
        assertTrue(src.contains("onCreate = { createTerminal() }"))
        assertTrue(src.contains("contentDescription = stringResource(R.string.oc_terminal_new)"))
        // …and the created tab is focused, which is what makes it the chip the
        // reveal rule has to show (upstream `terminal.new()` focuses too).
        assertTrue(src.contains("tabs.add(id, title = null, focus = true)"))
    }
}
