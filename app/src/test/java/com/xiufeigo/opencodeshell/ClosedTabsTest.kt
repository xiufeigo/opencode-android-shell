package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.nav.ClosedTab
import com.xiufeigo.opencodeshell.ui.nav.ClosedTabs
import com.xiufeigo.opencodeshell.ui.nav.SessionTabRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * B-6: the closed-tab stack (`_upstream/packages/app/src/shell/tabs/closed.ts`).
 *
 * The cases mirror upstream's own `tabs.test.ts:139-200` one for one, so the two
 * frontends can only disagree about the *wire format*, never the behaviour:
 * `tab.close` records a session tab with its index, `tab.reopenClosed` pops the
 * newest entry that is not open again, and the stack is capped at 25.
 */
class ClosedTabsTest {

    private fun tab(id: String) = SessionTabRef(id = id, title = "session $id")

    @Test
    fun recordsTheTabAndItsIndex() {
        assertEquals(
            listOf(ClosedTab(tab("a"), 2)),
            ClosedTabs.push(emptyList(), tab("a"), 2),
        )
    }

    @Test
    fun aNegativeIndexIsClampedToZero() {
        // The index is an insertion point (`Math.min(entry.index, tabs.length)`),
        // so a garbage negative value would otherwise re-insert at -1.
        assertEquals(listOf(ClosedTab(tab("a"), 0)), ClosedTabs.push(emptyList(), tab("a"), -3))
    }

    @Test
    fun capsTheStackAtTwentyFive() {
        var stack = emptyList<ClosedTab>()
        for (i in 0 until 30) stack = ClosedTabs.push(stack, tab("s$i"), i)
        assertEquals(ClosedTabs.LIMIT, stack.size)
        assertEquals("s5", stack.first().tab.id)
        assertEquals("s29", stack.last().tab.id)
    }

    @Test
    fun popsTheMostRecentlyClosedTab() {
        val stack = listOf(ClosedTab(tab("a"), 0), ClosedTab(tab("b"), 1))
        val result = ClosedTabs.take(stack, emptySet())
        assertEquals("b", result.entry?.tab?.id)
        assertEquals(listOf(ClosedTab(tab("a"), 0)), result.stack)
    }

    @Test
    fun skipsEntriesWhoseTabIsOpenAgain() {
        val stack = listOf(ClosedTab(tab("a"), 0), ClosedTab(tab("b"), 1))
        val result = ClosedTabs.take(stack, setOf("b"))
        assertEquals("a", result.entry?.tab?.id)
        assertEquals(emptyList<ClosedTab>(), result.stack)
    }

    @Test
    fun answersNoEntryWhenEverythingIsOpenOrEmpty() {
        assertNull(ClosedTabs.take(emptyList(), emptySet()).entry)
        val result = ClosedTabs.take(listOf(ClosedTab(tab("a"), 0)), setOf("a"))
        assertNull(result.entry)
        assertEquals(emptyList<ClosedTab>(), result.stack)
    }

    @Test
    fun purgesRemovedSessions() {
        val stack = listOf(ClosedTab(tab("a"), 0), ClosedTab(tab("b"), 1), ClosedTab(tab("c"), 2))
        assertEquals(
            listOf(ClosedTab(tab("b"), 1)),
            ClosedTabs.remove(stack, listOf("a", "c")),
        )
        // Nothing to remove keeps the stack as-is (no needless copy).
        assertEquals(stack, ClosedTabs.remove(stack, emptyList()))
    }

    @Test
    fun insertsAtTheRecordedIndexClampedToTheOpenList() {
        val entry = ClosedTab(tab("a"), 2)
        assertEquals(2, ClosedTabs.insertIndex(entry, 5))
        // The tab list shrank while the entry sat on the stack: append.
        assertEquals(1, ClosedTabs.insertIndex(entry, 1))
        assertEquals(0, ClosedTabs.insertIndex(entry, 0))
    }
}
