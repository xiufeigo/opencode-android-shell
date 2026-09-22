package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.nav.CloseResult
import com.xiufeigo.opencodeshell.ui.nav.SessionTabRef
import com.xiufeigo.opencodeshell.ui.nav.SessionTabs
import com.xiufeigo.opencodeshell.ui.nav.TabCloseTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W7: the pure half of the desktop session tab bar (`research/02` §9b).
 *
 * The two behaviours pinned here are transcribed from upstream —
 * `nextTabAfterClose` (`_upstream/packages/app/src/shell/tabs/closed.ts:31-34`,
 * used by `tabs.tsx:185`) and `sessionTabTitle`
 * (`shell/titlebar/tab-title.ts:4-6`) — so the desktop strip and the mobile
 * bottom strip cannot drift apart, and neither needs a device to verify.
 */
class SessionTabsTest {

    private val a = SessionTabRef("a", "Alpha")
    private val b = SessionTabRef("b", "Beta")
    private val c = SessionTabRef("c", null)

    private fun close(tabs: List<SessionTabRef>, id: String, active: String?): CloseResult =
        SessionTabs.close(tabs, id, active)

    /** `nextTabAfterClose` returns `undefined` when the closed tab was not on screen. */
    @Test
    fun `closing an inactive tab does not move the route`() {
        val result = close(listOf(a, b, c), "b", "a")
        assertEquals(listOf(a, c), result.tabs)
        assertEquals(TabCloseTarget.Stay, result.target)
    }

    /** A session route that is not the closed tab is still "not active". */
    @Test
    fun `closing a tab while another one is on screen stays put`() {
        assertEquals(TabCloseTarget.Stay, SessionTabs.nextAfterClose(listOf(a, b, c), 2, active = false))
        assertEquals(TabCloseTarget.Stay, close(listOf(a, b), "a", active = "unknown-id").target)
    }

    /** `tabs[index + 1]` — the right neighbour wins. */
    @Test
    fun `closing the active middle tab activates the tab to the right`() {
        val result = close(listOf(a, b, c), "b", active = "b")
        assertEquals(listOf(a, c), result.tabs)
        assertEquals(TabCloseTarget.Open("c"), result.target)
    }

    /** The first tab of a list also looks right, not left. */
    @Test
    fun `closing the active first tab activates the second`() {
        val result = close(listOf(a, b, c), "a", active = "a")
        assertEquals(listOf(b, c), result.tabs)
        assertEquals(TabCloseTarget.Open("b"), result.target)
    }

    /** `tabs[index + 1] ?? tabs[index - 1]` — there is nothing to the right. */
    @Test
    fun `closing the active last tab falls back to the tab on the left`() {
        val result = close(listOf(a, b, c), "c", active = "c")
        assertEquals(listOf(a, b), result.tabs)
        assertEquals(TabCloseTarget.Open("b"), result.target)
    }

    /** `nextTabAfterClose` returns `null` — upstream navigates to `/` (Home). */
    @Test
    fun `closing the only active tab returns home`() {
        val result = close(listOf(a), "a", active = "a")
        assertTrue(result.tabs.isEmpty())
        assertEquals(TabCloseTarget.Home, result.target)
    }

    /** Ids that are not open close nothing (and are not a navigation trigger). */
    @Test
    fun `closing an unknown tab changes nothing`() {
        val tabs = listOf(a, b)
        val result = close(tabs, "zzz", active = "zzz")
        assertEquals(tabs, result.tabs)
        assertEquals(TabCloseTarget.Stay, result.target)
    }

    /** The survivors keep their open order — no re-sorting on close. */
    @Test
    fun `close preserves the order of the remaining tabs`() {
        val result = close(listOf(a, b, c), "a", active = "c")
        assertEquals(listOf("b", "c"), result.tabs.map { it.id })
    }

    /** `sessionTabTitle(title, t("session.tab.session"))` — missing title. */
    @Test
    fun `a missing title falls back to the session label`() {
        assertEquals("Session", SessionTabs.displayTitle(null, "Session"))
        assertEquals("Session", SessionTabs.displayTitle("", "Session"))
        assertEquals("Session", SessionTabs.displayTitle("   ", "Session"))
    }

    /**
     * `isFallbackTitle` is not in this checkout (`research/audit_session_ui.md:585`),
     * so the auto-title shape `session/title.ts:4` pins is what is covered.
     */
    @Test
    fun `an auto-generated title falls back to the session label`() {
        val auto = "New session - 2025-01-31T09:15:12.345Z"
        val child = "Child session - 2025-01-31T09:15:12.345Z"
        assertEquals("Session", SessionTabs.displayTitle(auto, "Session"))
        assertEquals("Session", SessionTabs.displayTitle(child, "Session"))
        assertTrue(SessionTabs.isFallbackTitle(auto))
        assertTrue(SessionTabs.isFallbackTitle(child))
    }

    /** A real title is shown verbatim — the fallback must not swallow it. */
    @Test
    fun `a real title is shown verbatim`() {
        assertEquals("Fix the tab bar", SessionTabs.displayTitle("Fix the tab bar", "Session"))
        assertEquals("New session", SessionTabs.displayTitle("New session", "Session"))
        assertEquals("Session 42", SessionTabs.displayTitle("Session 42", "Session"))
        assertFalse(SessionTabs.isFallbackTitle("New session"))
        assertFalse(SessionTabs.isFallbackTitle("New session - yesterday"))
        assertFalse(SessionTabs.isFallbackTitle(null))
    }
}
