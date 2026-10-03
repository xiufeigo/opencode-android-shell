package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.components.TabReorder
import com.xiufeigo.opencodeshell.ui.nav.SessionTabRef
import com.xiufeigo.opencodeshell.ui.nav.SessionTabs
import com.xiufeigo.opencodeshell.ui.terminal.share.TerminalTabs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B-20 / D-15: the drag half of the tab strips, pinned without a device.
 *
 * The gestures themselves are Compose (and therefore `@Composable`-only), so
 * what a JVM test can hold is the decision they delegate to:
 * [TabReorder.dropIndex] (which slot the pointer has reached) and the two
 * models that apply it ([SessionTabs.reorder], upstream's `arrayMove`;
 * [TerminalTabs.move], `terminal.move`). A wrong index here is what would move
 * the wrong tab on screen, so this is the half worth pinning.
 */
class TabReorderTest {

    private val a = SessionTabRef("a", "Alpha")
    private val b = SessionTabRef("b", "Beta")
    private val c = SessionTabRef("c", null)
    private val d = SessionTabRef("d", "Delta")

    // --- TabReorder.dropIndex -------------------------------------------------

    /** Travel inside half a slot is not a step: the slot stays where it is. */
    @Test
    fun `travel inside half a slot does not move the tab`() {
        assertEquals(1, TabReorder.dropIndex(fromIndex = 1, dragPx = 0f, pitchPx = 100f, count = 4))
        assertEquals(1, TabReorder.dropIndex(fromIndex = 1, dragPx = 30f, pitchPx = 100f, count = 4))
        assertEquals(1, TabReorder.dropIndex(fromIndex = 1, dragPx = -30f, pitchPx = 100f, count = 4))
    }

    /** Half a slot already counts — a drag of half the neighbour is a step. */
    @Test
    fun `half a slot in either direction is one step`() {
        assertEquals(2, TabReorder.dropIndex(fromIndex = 1, dragPx = 50f, pitchPx = 100f, count = 4))
        assertEquals(0, TabReorder.dropIndex(fromIndex = 1, dragPx = -50f, pitchPx = 100f, count = 4))
    }

    /** A fast drag crosses several slots at once. */
    @Test
    fun `a long drag skips the slots it crossed`() {
        assertEquals(3, TabReorder.dropIndex(fromIndex = 0, dragPx = 250f, pitchPx = 100f, count = 5))
        assertEquals(0, TabReorder.dropIndex(fromIndex = 3, dragPx = -250f, pitchPx = 100f, count = 5))
    }

    /** The ends of the strip clamp instead of running off the list. */
    @Test
    fun `the index is clamped to the list`() {
        assertEquals(0, TabReorder.dropIndex(fromIndex = 0, dragPx = -5000f, pitchPx = 100f, count = 4))
        assertEquals(3, TabReorder.dropIndex(fromIndex = 3, dragPx = 5000f, pitchPx = 100f, count = 4))
    }

    /** A slot that has not been measured yet has no grid to step on. */
    @Test
    fun `an unmeasured or single-slot strip keeps the index`() {
        assertEquals(2, TabReorder.dropIndex(fromIndex = 2, dragPx = 999f, pitchPx = 0f, count = 4))
        assertEquals(0, TabReorder.dropIndex(fromIndex = 0, dragPx = 999f, pitchPx = 100f, count = 1))
    }

    /** A stale gesture (index from an older list) reports "no target". */
    @Test
    fun `an out-of-range source index reports no target`() {
        assertEquals(-1, TabReorder.dropIndex(fromIndex = 4, dragPx = 10f, pitchPx = 100f, count = 4))
        assertEquals(-1, TabReorder.dropIndex(fromIndex = -1, dragPx = 10f, pitchPx = 100f, count = 4))
        assertEquals(-1, TabReorder.dropIndex(fromIndex = 0, dragPx = 10f, pitchPx = 100f, count = 0))
    }

    // --- SessionTabs.reorder (upstream arrayMove) -----------------------------

    @Test
    fun `reorder moves a tab right and left`() {
        assertEquals(listOf("b", "c", "a", "d"), SessionTabs.reorder(listOf(a, b, c, d), 0, 2).map { it.id })
        assertEquals(listOf("a", "d", "b", "c"), SessionTabs.reorder(listOf(a, b, c, d), 3, 1).map { it.id })
    }

    /** The list is untouched when there is nothing to do (identity, not a copy). */
    @Test
    fun `reorder is a no-op for an unknown or unchanged index`() {
        val tabs = listOf(a, b, c)
        assertSame(tabs, SessionTabs.reorder(tabs, 1, 1))
        assertSame(tabs, SessionTabs.reorder(tabs, 3, 0))
        assertSame(tabs, SessionTabs.reorder(tabs, 0, -1))
    }

    /** Every other tab keeps its relative order — the drag moves one entry. */
    @Test
    fun `reorder keeps the other tabs in order`() {
        assertEquals(listOf("a", "c", "d", "b"), SessionTabs.reorder(listOf(a, b, c, d), 1, 3).map { it.id })
    }

    // --- TerminalTabs.move (D-15) --------------------------------------------

    /** One live drag step is the same move the menu rows already perform. */
    @Test
    fun `a terminal drag step is the model's move`() {
        val tabs = TerminalTabs()
        tabs.add("pty_1")
        tabs.add("pty_2")
        tabs.add("pty_3")
        // Drag the first chip one slot right, as the gesture does live.
        val to = TabReorder.dropIndex(fromIndex = 0, dragPx = 40f, pitchPx = 80f, count = tabs.size)
        assertEquals(1, to)
        assertTrue(tabs.move(0, to))
        assertEquals(listOf("pty_2", "pty_1", "pty_3"), tabs.tabs.map { it.id })
    }

    /** A fast drag that crosses two chips lands where a step-by-step drag would. */
    @Test
    fun `a multi-slot terminal drag matches the step-by-step result`() {
        val stepped = TerminalTabs()
        listOf("pty_1", "pty_2", "pty_3", "pty_4").forEach { stepped.add(it) }
        stepped.move(0, 1)
        stepped.move(1, 2)

        val jumped = TerminalTabs()
        listOf("pty_1", "pty_2", "pty_3", "pty_4").forEach { jumped.add(it) }
        jumped.move(0, 2)

        assertEquals(stepped.tabs.map { it.id }, jumped.tabs.map { it.id })
        assertEquals(listOf("pty_2", "pty_3", "pty_1", "pty_4"), jumped.tabs.map { it.id })
    }

    /** A drag never closes or re-focuses a terminal: only the order changes. */
    @Test
    fun `a terminal drag keeps the focused tab`() {
        val tabs = TerminalTabs()
        tabs.add("pty_1")
        tabs.add("pty_2")
        tabs.activate("pty_1")
        assertTrue(tabs.move(1, 0))
        assertEquals("pty_1", tabs.activeId)
        assertEquals(listOf("pty_2", "pty_1"), tabs.tabs.map { it.id })
        assertFalse(tabs.move(0, 0))
    }
}
