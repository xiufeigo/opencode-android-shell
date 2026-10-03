package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.nav.ClosedTab
import com.xiufeigo.opencodeshell.ui.nav.SessionTabRef
import com.xiufeigo.opencodeshell.ui.nav.SessionTabs
import com.xiufeigo.opencodeshell.ui.nav.TabCloseTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v49 BUG-2 (`research/v49_verify/tC/v49_42/43/44_stale_tab.png`): deleting a
 * session from the shell left its tab in the strip — the row was gone from every
 * list, the tab was not. The shell's own prune asks the server
 * (`listSessions` on every `sessionsRevision` bump), and a server that still
 * answers with the deleted row leaves the tab up for good.
 *
 * So the delete closes the tab itself, and the decision is pinned here:
 * `SessionTabs.afterSessionDeleted`.
 */
class SessionTabDeleteSyncTest {

    private val imported = SessionTabRef("ses_imported", "E2ECHK5558")
    private val original = SessionTabRef("ses_original", "E2ECHK5558")
    private val other = SessionTabRef("ses_other", "Beta")

    @Test
    fun `the deleted session's tab closes`() {
        val result = SessionTabs.afterSessionDeleted(
            tabs = listOf(other, imported),
            closed = emptyList(),
            id = imported.id,
        )
        assertEquals(listOf(other), result.tabs)
    }

    /**
     * The imported copy carries the **same title** as the session it was
     * imported from (`v49_43`) — which is the suspected cause of the original
     * bug. Matching on the id closes exactly one tab.
     */
    @Test
    fun `a same-named sibling session keeps its own tab`() {
        val result = SessionTabs.afterSessionDeleted(
            tabs = listOf(original, imported),
            closed = emptyList(),
            id = original.id,
        )
        assertEquals(listOf(imported), result.tabs)
    }

    @Test
    fun `a recorded close for the deleted session is dropped with it`() {
        val stack = listOf(
            ClosedTab(SessionTabRef("ses_gone", "Gone"), 0),
            ClosedTab(SessionTabRef("ses_kept", "Kept"), 1),
        )
        val result = SessionTabs.afterSessionDeleted(listOf(other), stack, "ses_gone")
        assertEquals(listOf(stack[1]), result.closed)
    }

    @Test
    fun `an id that is not open changes nothing`() {
        val tabs = listOf(other)
        val stack = listOf(ClosedTab(SessionTabRef("ses_gone", "Gone"), 0))
        val result = SessionTabs.afterSessionDeleted(tabs, stack, "ses_absent")
        assertEquals(tabs, result.tabs)
        assertEquals(stack, result.closed)
    }

    @Test
    fun `an empty id is refused rather than closing every tab`() {
        val tabs = listOf(other)
        val result = SessionTabs.afterSessionDeleted(tabs, emptyList(), "")
        assertEquals(tabs, result.tabs)
    }

    // ------------------------------- v63 BUG-1: the delete Home performs

    /**
     * Home's row delete used to drop the tab by itself. Going through one
     * function means the closed stack follows too — a recorded close of a
     * session the server just deleted must not stay reopenable.
     */
    @Test
    fun `a delete from Home purges the closed stack as well`() {
        val stack = listOf(ClosedTab(SessionTabRef("ses_gone", "Gone"), 0))
        val result = SessionTabs.afterDeleteFromElsewhere(
            tabs = listOf(other),
            closed = stack,
            id = "ses_gone",
            activeId = null,
        )
        assertEquals(listOf(other), result.tabs)
        assertEquals(emptyList<ClosedTab>(), result.closed)
        assertEquals(TabCloseTarget.Stay, result.target)
    }

    /** The session on screen: the neighbour to the right takes over. */
    @Test
    fun `deleting the session on screen moves the route to its right neighbour`() {
        val tabs = listOf(other, original, imported)
        val result = SessionTabs.afterDeleteFromElsewhere(tabs, emptyList(), original.id, activeId = original.id)
        assertEquals(listOf(other, imported), result.tabs)
        assertEquals(TabCloseTarget.Open(imported.id), result.target)
    }

    /** Last tab standing: Home, like upstream's `nextTabAfterClose` → null. */
    @Test
    fun `deleting the only tab goes home`() {
        val result = SessionTabs.afterDeleteFromElsewhere(listOf(other), emptyList(), other.id, activeId = other.id)
        assertEquals(emptyList<SessionTabRef>(), result.tabs)
        assertEquals(TabCloseTarget.Home, result.target)
    }

    @Test
    fun `deleting a session that is not open leaves the route where it is`() {
        val tabs = listOf(other)
        val result = SessionTabs.afterDeleteFromElsewhere(tabs, emptyList(), "ses_absent", activeId = other.id)
        assertEquals(tabs, result.tabs)
        assertEquals(TabCloseTarget.Stay, result.target)
    }

    /** Wiring: App.kt's Home delete must go through the shared decision. */
    @Test
    fun `Home's row delete uses the shared tab decision`() {
        val app = java.io.File("app/src/main/java/com/xiufeigo/opencodeshell/ui/App.kt")
            .takeIf { it.isFile }?.readText()
            ?: java.io.File("src/main/java/com/xiufeigo/opencodeshell/ui/App.kt").readText()
        assertTrue(
            "Home's delete must not splice the tab list by hand any more",
            !app.contains("openSessions.removeAll { it.id == row.id }"),
        )
        assertTrue(app.contains("SessionTabs.afterDeleteFromElsewhere("))
    }
}
