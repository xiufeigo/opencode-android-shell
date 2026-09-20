package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.terminal.share.PtyProtocol
import com.xiufeigo.opencodeshell.ui.terminal.share.TerminalTabs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Terminal tab model (`research/02_ui_screens.md` §9.3): the 20-session cap,
 * focus on open, neighbour activation on close, and "the last close closes the
 * panel" (which is what `TerminalPanelHost` maps onto the route pop).
 */
class TerminalTabsTest {

    @Test
    fun addFocusesTheNewTabAndNumbersIt() {
        val tabs = TerminalTabs()
        assertEquals(1, tabs.add("pty_1")?.number)
        assertEquals(2, tabs.add("pty_2")?.number)
        assertEquals("pty_2", tabs.activeId)
        // A background tab (focus = false) never steals focus.
        assertTrue(tabs.add("pty_3", focus = false) != null)
        assertEquals("pty_2", tabs.activeId)
    }

    @Test
    fun duplicateAndBlankIdsAreRefused() {
        val tabs = TerminalTabs()
        tabs.add("pty_1")
        assertNull(tabs.add("pty_1"))
        assertNull(tabs.add(""))
        assertEquals(1, tabs.size)
    }

    @Test
    fun panelIsCappedAtTwentyTerminals() {
        val tabs = TerminalTabs()
        for (i in 1..PtyProtocol.MAX_TERMINAL_SESSIONS) {
            assertTrue(tabs.add("pty_$i") != null)
        }
        assertTrue(tabs.isFull)
        assertNull(tabs.add("pty_21"))
        assertEquals(PtyProtocol.MAX_TERMINAL_SESSIONS, tabs.size)
    }

    @Test
    fun numbersAreNotReusedAfterAClose() {
        val tabs = TerminalTabs()
        tabs.add("pty_1")
        tabs.add("pty_2")
        tabs.close("pty_2")
        // "Terminal 2" must keep meaning the same session as before.
        assertEquals(3, tabs.add("pty_3")?.number)
    }

    @Test
    fun closingTheActiveTabPrefersTheRightNeighbour() {
        val tabs = TerminalTabs()
        tabs.add("pty_1")
        tabs.add("pty_2")
        tabs.add("pty_3")
        tabs.activate("pty_2")
        assertTrue(tabs.close("pty_2"))
        assertEquals("pty_3", tabs.activeId)

        // Closing the last one falls back to the new last tab.
        tabs.activate("pty_3")
        assertTrue(tabs.close("pty_3"))
        assertEquals("pty_1", tabs.activeId)
    }

    @Test
    fun closingTheLastTabClosesThePanel() {
        val tabs = TerminalTabs()
        tabs.add("pty_1")
        tabs.add("pty_2")
        assertTrue(tabs.close("pty_1"))
        assertFalse(tabs.close("pty_2"))
        assertNull(tabs.activeId)
        assertTrue(tabs.isEmpty)
    }

    @Test
    fun closingAnInactiveTabKeepsTheActiveOne() {
        val tabs = TerminalTabs()
        tabs.add("pty_1")
        tabs.add("pty_2")
        tabs.activate("pty_1")
        assertTrue(tabs.close("pty_2"))
        assertEquals("pty_1", tabs.activeId)
    }

    @Test
    fun activateRenameAndMove() {
        val tabs = TerminalTabs()
        tabs.add("pty_1")
        tabs.add("pty_2")
        assertFalse(tabs.activate("pty_missing"))
        assertTrue(tabs.activate("pty_1"))
        assertEquals("pty_1", tabs.activeId)

        assertTrue(tabs.rename("pty_1", "build"))
        assertEquals("build", tabs.find("pty_1")?.title)
        // A blank rename falls back to the numbered default.
        assertTrue(tabs.rename("pty_1", "   "))
        assertNull(tabs.find("pty_1")?.title)
        assertFalse(tabs.rename("pty_missing", "x"))

        assertTrue(tabs.move(fromIndex = 0, toIndex = 1))
        assertEquals(listOf("pty_2", "pty_1"), tabs.tabs.map { it.id })
        assertFalse(tabs.move(fromIndex = 0, toIndex = 0))
        assertFalse(tabs.move(fromIndex = 5, toIndex = 0))
        assertEquals("pty_1", tabs.activeId)
    }

    @Test
    fun clearResetsEverythingOnPanelUnmount() {
        val tabs = TerminalTabs()
        tabs.add("pty_1")
        tabs.clear()
        assertTrue(tabs.isEmpty)
        assertNull(tabs.activeId)
        // Numbering restarts: nothing survives an unmount (§9.4).
        assertEquals(1, tabs.add("pty_9")?.number)
    }
}
