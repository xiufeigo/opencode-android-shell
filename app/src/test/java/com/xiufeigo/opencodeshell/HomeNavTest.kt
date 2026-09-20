package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.nav.NavRestore
import com.xiufeigo.opencodeshell.ui.nav.TabLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M3: the "restore last page" contract (PLAN §3) and the optional bottom title
 * bar switch (`research/02` §9b / §12). Both are pure functions of what is on
 * disk, so the nav behaviour is pinned without a device.
 */
class HomeNavTest {

    @Test
    fun `home round trips`() {
        val stored = NavRestore.stored(NavRestore.ROUTE_HOME, null)
        assertEquals(NavRestore.Stored(NavRestore.ROUTE_HOME, null), stored)
        assertEquals("home", NavRestore.target(stored, "s1"))
    }

    @Test
    fun `a session restores to its concrete route`() {
        val stored = NavRestore.stored(NavRestore.ROUTE_SESSION, "sess-1")
        assertEquals(NavRestore.Stored(NavRestore.ROUTE_SESSION, "sess-1"), stored)
        assertEquals("server/s1/session/sess-1", NavRestore.target(stored, "s1"))
    }

    @Test
    fun `a session without an id restores to nothing`() {
        assertNull(NavRestore.stored(NavRestore.ROUTE_SESSION, null))
        assertNull(NavRestore.stored(NavRestore.ROUTE_SESSION, "   "))
        assertNull(NavRestore.target(null, "s1"))
    }

    @Test
    fun `transient or unknown pages restore to nothing`() {
        // new-session / settings / terminal must not become a cold-start target.
        assertNull(NavRestore.stored("new-session", null))
        assertNull(NavRestore.stored("settings", null))
        assertNull(NavRestore.stored("terminal?directory=%2Ftmp", null))
        assertNull(NavRestore.stored(null, "sess-1"))
        assertNull(NavRestore.stored("", null))
    }

    @Test
    fun `reached destinations map back to what should be stored`() {
        assertEquals(
            NavRestore.Stored(NavRestore.ROUTE_HOME, null),
            NavRestore.toStore("home", null),
        )
        assertEquals(
            NavRestore.Stored(NavRestore.ROUTE_SESSION, "abc"),
            NavRestore.toStore("server/{serverKey}/session/{id}", "abc"),
        )
        assertNull(NavRestore.toStore("new-session", null))
        assertNull(NavRestore.toStore("settings", null))
        assertNull(NavRestore.toStore("terminal", null))
        assertNull(NavRestore.toStore("server/{serverKey}/session/{id}", ""))
        assertNull(NavRestore.toStore(null, "abc"))
    }

    @Test
    fun `session route pattern matches the graph`() {
        // `ui/App.kt#Route.SESSION` — the nav graph owns the pattern; this is the
        // only place the restore path hard-codes it.
        assertEquals("server/{serverKey}/session/{id}", NavRestore.session("{serverKey}", "{id}"))
    }

    @Test
    fun `server key is stable and path safe`() {
        val url = "http://127.0.0.1:4096"
        assertEquals(NavRestore.serverKeyOf(url), NavRestore.serverKeyOf(url))
        assertTrue(NavRestore.serverKeyOf(url).startsWith("s"))
        assertFalse(NavRestore.serverKeyOf(url).contains('/'))
        assertFalse(NavRestore.serverKeyOf(url).contains(':'))
        assertEquals(NavRestore.DEFAULT_SERVER_KEY, NavRestore.serverKeyOf(null))
        assertEquals(NavRestore.DEFAULT_SERVER_KEY, NavRestore.serverKeyOf("   "))
    }

    @Test
    fun `bottom tab strip is off unless appearance tab layout says vertical`() {
        assertFalse(TabLayout.bottomStrip(null))
        assertFalse(TabLayout.bottomStrip(TabLayout.HORIZONTAL))
        assertFalse(TabLayout.bottomStrip("grid"))
        assertFalse(TabLayout.bottomStrip("VERTICAL"))
        assertTrue(TabLayout.bottomStrip(TabLayout.VERTICAL))
        assertTrue(TabLayout.bottomStrip("  vertical  "))
    }
}
