package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.nav.ClosedTab
import com.xiufeigo.opencodeshell.ui.nav.SessionTabRef
import com.xiufeigo.opencodeshell.ui.nav.SessionTabs
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v50 LOW-2 (`research/v50_verify/tD`): the two tab-lifecycle bugs of the v50
 * device run, both pinned on the pure half.
 *
 * - **a)** a cold start restored `LastRouteStore`'s route into a session deleted
 *   while the process was gone — the nav listener gave the dead id a tab and
 *   「This session cannot be found」 was the screen under it. A cold start bumps
 *   no `sessionsRevision`, so the shell never asked the server about it;
 * - **b)** a newly created session's tab did not appear (content had already
 *   switched to the new session). A catalogue read that raced the create
 *   answers without the new row, and the prune the read triggers took the tab
 *   the navigation had just added back.
 *
 * Both are decisions of `SessionTabs.pruneAgainstServerList`: which ids the
 * server does not list die, and the escape hatch ([protectedIds]) is narrow
 * enough that a restored dead id is still prunable — which is what makes (a) a
 * fix rather than a second way to lose a tab.
 */
class SessionTabServerPruneTest {

    private val restored = SessionTabRef("ses_gone", "E2ECHK5560")
    private val live = SessionTabRef("ses_live", "Beta")
    private val fresh = SessionTabRef("ses_fresh", null)

    /**
     * (a) The restored route's tab: the server does not list it, so it is
     * dropped — and the caller learns it had to back out of the route.
     */
    @Test
    fun `a tab the server no longer lists is pruned`() {
        val result = SessionTabs.pruneAgainstServerList(
            tabs = listOf(restored, live),
            closed = emptyList(),
            serverIds = listOf(live.id),
        )
        assertEquals(listOf(live), result.tabs)
        assertEquals(listOf(restored.id), result.prunedSessionIds)
    }

    /** The order of the surviving tabs is the strip's, not the server's. */
    @Test
    fun `the surviving tabs keep their order`() {
        val result = SessionTabs.pruneAgainstServerList(
            tabs = listOf(live, restored),
            closed = emptyList(),
            serverIds = listOf(live.id),
        )
        assertEquals(listOf(live), result.tabs)
    }

    /** Every tab the server lists survives, whatever the catalogue's order. */
    @Test
    fun `tabs the server lists survive`() {
        val result = SessionTabs.pruneAgainstServerList(
            tabs = listOf(restored, live, fresh),
            closed = emptyList(),
            serverIds = listOf(fresh.id, live.id),
        )
        assertEquals(listOf(live, fresh), result.tabs)
        assertEquals(listOf(restored.id), result.prunedSessionIds)
    }

    /**
     * (b) The race: the create landed but the read was issued before it, so the
     * new id is protected and the tab the navigation just added stays up.
     */
    @Test
    fun `a session created since the read keeps its tab`() {
        val result = SessionTabs.pruneAgainstServerList(
            tabs = listOf(live, fresh),
            closed = emptyList(),
            serverIds = listOf(live.id),
            protectedIds = setOf(fresh.id),
        )
        assertEquals(listOf(live, fresh), result.tabs)
        assertEquals(emptyList<String>(), result.prunedSessionIds)
    }

    /** The session on screen is the other half of the protection. */
    @Test
    fun `the session on screen keeps its tab`() {
        val result = SessionTabs.pruneAgainstServerList(
            tabs = listOf(restored, live),
            closed = emptyList(),
            serverIds = listOf(live.id),
            protectedIds = setOf(restored.id),
        )
        assertEquals(listOf(restored, live), result.tabs)
    }

    /**
     * Protection is not a second way to lose a dead tab: it is the caller's job
     * to clear the id once a read that landed carries it, and a read that does
     * carry it prunes nothing anyway.
     */
    @Test
    fun `a protected id the server lists is pruned like any other`() {
        val result = SessionTabs.pruneAgainstServerList(
            tabs = listOf(live, fresh),
            closed = emptyList(),
            serverIds = listOf(live.id),
            protectedIds = setOf(live.id, fresh.id),
        )
        assertEquals(listOf(live, fresh), result.tabs)
    }

    /** Nothing to prune returns the very same lists (no needless state writes). */
    @Test
    fun `a read that prunes nothing changes nothing`() {
        val closed = listOf(ClosedTab(live, 0))
        val result = SessionTabs.pruneAgainstServerList(
            tabs = listOf(live),
            closed = closed,
            serverIds = listOf(live.id),
        )
        assertEquals(listOf(live), result.tabs)
        assertEquals(closed, result.closed)
        assertEquals(emptyList<String>(), result.prunedSessionIds)
    }

    /**
     * The closed stack follows the tab set (`removeClosedTabs`,
     * closed.ts:27-30): a recorded close for a session the server no longer
     * lists must not be reopenable — otherwise `tab.reopenClosed` navigates
     * straight back into a 404.
     */
    @Test
    fun `a closed entry for a gone session is dropped too`() {
        val closed = listOf(ClosedTab(restored, 0), ClosedTab(live, 0))
        val result = SessionTabs.pruneAgainstServerList(
            tabs = listOf(live),
            closed = closed,
            serverIds = listOf(live.id),
        )
        assertEquals(listOf(ClosedTab(live, 0)), result.closed)
    }

    /** Protection covers the closed stack too, in the same pass. */
    @Test
    fun `a protected id stays on the closed stack`() {
        val closed = listOf(ClosedTab(restored, 0))
        val result = SessionTabs.pruneAgainstServerList(
            tabs = emptyList(),
            closed = closed,
            serverIds = emptyList(),
            protectedIds = setOf(restored.id),
        )
        assertEquals(closed, result.closed)
    }
}
