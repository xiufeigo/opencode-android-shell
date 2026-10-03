package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.terminal.TerminalTabGesture
import com.xiufeigo.opencodeshell.ui.terminal.TerminalTabMenu
import com.xiufeigo.opencodeshell.ui.terminal.TerminalTabTitle
import com.xiufeigo.opencodeshell.ui.terminal.share.TerminalTabs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v40 (`research/v40_verify/tD`) — the four terminal-strip defects the device
 * pass reported, in the order they were filed:
 *
 * 1. `tD_42/45/71` — past one chip the strip could not be dragged at all
 *    (identical pixels before/after), so the other terminals were unreachable;
 * 2. `tD_48 → tD_49` — "Move tab left" relabelled the neighbour (「Terminal 2」
 *    became 「Terminal 1」 with nothing created or closed), and a later move lost
 *    a chip entirely (`tD_44 → tD_46`);
 * 3. `tD_53/54` — the chip's inline Rename never took focus: no soft keyboard,
 *    and the characters it was sent were answered by the shell's chords;
 * 4. `tD_48 vs tD_52` — the first chip still offered "Move tab left" while the
 *    last one correctly hid "Move tab right".
 *
 * The pure halves ([TerminalTabTitle], [TerminalTabMenu], [TerminalTabGesture],
 * [TerminalTabs]) carry the rules; the source pins hold the wiring that feeds
 * them, the same split [TerminalNewTabVisibilityTest] uses.
 */
class TerminalTabStripV40Test {

    private fun terminalPanel(): String =
        listOf(
            File("src/main/java/com/xiufeigo/opencodeshell/ui/terminal/TerminalPanel.kt"),
            File("app/src/main/java/com/xiufeigo/opencodeshell/ui/terminal/TerminalPanel.kt"),
        ).firstOrNull { it.isFile }?.readText() ?: error("missing TerminalPanel.kt")

    private fun numbered(number: Int): String = "Terminal $number"

    // -------------------------------------------------------------- 1. scroll

    @Test
    fun `a press that is already a scroll never becomes a reorder`() {
        val slop = 8f
        // Still at the deadline: the hold is the reorder (or the menu) gesture.
        assertEquals(TerminalTabGesture.Press.HOLD, TerminalTabGesture.press(0f, slop))
        assertEquals(TerminalTabGesture.Press.HOLD, TerminalTabGesture.press(slop, slop))
        assertEquals(TerminalTabGesture.Press.HOLD, TerminalTabGesture.press(-slop, slop))
        // Past the slop the strip's scroll owns the finger — claiming it at the
        // deadline would jump the strip back mid-drag.
        assertEquals(TerminalTabGesture.Press.SCROLL, TerminalTabGesture.press(slop + 0.5f, slop))
        assertEquals(TerminalTabGesture.Press.SCROLL, TerminalTabGesture.press(-40f, slop))
    }

    @Test
    fun `the strip scrolls from anywhere on it`() {
        val src = terminalPanel()
        // The suspension is gone: the chips sit flush, so a chip press is the
        // only place there is to drag from and the overflow was unreachable.
        assertFalse(
            "the strip must not be disabled while a chip is pressed",
            src.contains("horizontalScroll(scroll, enabled"),
        )
        assertTrue("the chips row still scrolls", src.contains(".horizontalScroll(scroll)"))
        // …and the long press keeps its own arbitration instead.
        assertTrue(
            "the claim drops a press that already scrolled",
            src.contains("TerminalTabGesture.press(travel, viewConfiguration.touchSlop)"),
        )
        assertTrue("the dead suspension state is gone", !src.contains("chipDownId"))
    }

    // -------------------------------------------------------------- 2. titles

    @Test
    fun `a chip label is the terminal's own number, not its slot`() {
        assertEquals("Terminal 2", TerminalTabTitle.resolve(null, 2, ::numbered))
        assertEquals("Terminal 2", TerminalTabTitle.resolve("", 2, ::numbered))
        assertEquals("build", TerminalTabTitle.resolve("build", 2, ::numbered))
    }

    @Test
    fun `a move permutes the tabs without relabelling any of them`() {
        val tabs = TerminalTabs()
        val a = tabs.add("pty-a")!!
        val b = tabs.add("pty-b")!!
        val c = tabs.add("pty-c")!!
        val before = tabs.tabs.associate { it.id to it.number }

        assertTrue(tabs.move(fromIndex = 1, toIndex = 0))

        // The id→number map is identical: a reorder cannot renumber anything.
        assertEquals(before, tabs.tabs.associate { it.id to it.number })
        // …and the labels the strip would render are unchanged per terminal.
        assertEquals(
            before.mapValues { (_, number) -> TerminalTabTitle.resolve(null, number, ::numbered) },
            tabs.tabs.associate { it.id to TerminalTabTitle.resolve(it.title, it.number, ::numbered) },
        )
        // The order really did change, so the assertion above is not vacuous.
        assertEquals(listOf(b.id, a.id, c.id), tabs.tabs.map { it.id })
        // Out-of-range moves are still no-ops (they used to look like "the tab
        // vanished", `tD_44 → tD_46`).
        assertFalse(tabs.move(fromIndex = 0, toIndex = 3))
        assertEquals(3, tabs.size)
    }

    @Test
    fun `a user title survives a move untouched`() {
        val tabs = TerminalTabs()
        tabs.add("pty-a")
        val b = tabs.add("pty-b")!!
        tabs.rename(b.id, "logs")
        tabs.move(fromIndex = 1, toIndex = 0)
        assertEquals("logs", tabs.find(b.id)?.title)
        assertEquals("logs", TerminalTabTitle.resolve(tabs.find(b.id)?.title, b.number, ::numbered))
    }

    @Test
    fun `the strip reads the number off the tab, never off the index`() {
        val src = terminalPanel()
        assertTrue("labels go through the resolver", src.contains("TerminalTabTitle.resolve(tab.title, tab.number)"))
        // The numbered default is built from the tab's own counter…
        assertTrue(
            "default title from the tab",
            src.contains("stringResource(R.string.oc_terminal_title_numbered, tab.number)"),
        )
        // …and no label is ever derived from the loop index or the position.
        assertFalse(
            "an index-derived terminal label would come back on every move",
            Regex("""oc_terminal_title_numbered,\s*(index|tabs\.)""").containsMatchIn(src),
        )
    }

    // ----------------------------------------------------------- 3. menu rows

    @Test
    fun `move rows follow the same boundary rule in both directions`() {
        for (count in 1..5) {
            for (index in 0 until count) {
                assertEquals(
                    "move left at $index of $count",
                    index > 0,
                    TerminalTabMenu.canMoveLeft(index, count),
                )
                assertEquals(
                    "move right at $index of $count",
                    index < count - 1,
                    TerminalTabMenu.canMoveRight(index, count),
                )
            }
        }
        // A lone tab has neither neighbour — the reported asymmetry was exactly
        // that the first chip still offered "Move tab left".
        assertFalse(TerminalTabMenu.canMoveLeft(0, 1))
        assertFalse(TerminalTabMenu.canMoveRight(0, 1))
        // Out-of-range indices are inert.
        assertFalse(TerminalTabMenu.canMoveLeft(2, 2))
        assertFalse(TerminalTabMenu.canMoveRight(-1, 3))
    }

    @Test
    fun `the chip menu is wired to the shared boundary rule`() {
        val src = terminalPanel()
        assertTrue("left", src.contains("TerminalTabMenu.canMoveLeft(index, tabs.size)"))
        assertTrue("right", src.contains("TerminalTabMenu.canMoveRight(index, tabs.size)"))
        // The rows themselves are still rendered conditionally (a null handler
        // is how the row disappears).
        assertTrue(src.contains("if (onMoveLeft != null) {"))
        assertTrue(src.contains("if (onMoveRight != null) {"))
    }

    // --------------------------------------------------------- 4. rename IME

    @Test
    fun `the inline rename takes focus and the keyboard when it opens`() {
        val src = terminalPanel()
        // Focus + IME on composition: without them the field never got the
        // keyboard and the keystrokes were answered by the shell instead.
        assertTrue("focus requester", src.contains("val focus = remember { FocusRequester() }"))
        assertTrue("request", src.contains("focus.requestFocus()"))
        assertTrue("soft keyboard", src.contains("keyboard?.show()"))
        assertTrue("wired to the field", src.contains(".focusRequester(focus)"))
        // The soft keyboard's Done completes the rename, like hardware Enter.
        assertTrue("ime action", src.contains("OcKeyboard.identifier(imeAction = ImeAction.Done)"))
        assertTrue("done confirms", src.contains("KeyboardActions(onDone = { onConfirm() })"))
    }
}
