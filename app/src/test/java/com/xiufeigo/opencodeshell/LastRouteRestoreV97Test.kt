package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.Route
import com.xiufeigo.opencodeshell.ui.nav.NavMemory
import com.xiufeigo.opencodeshell.ui.nav.NavRestore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v97 N-1 (`research/v97_verify/5560_sweepA/REPORT_v97b.md`, emulator-5560
 * Pixel_Tablet, 3/3): a cold start never came back to the session it was left
 * on, and the value on disk was rewritten to `home`.
 *
 * The store was fine. The *order* was not: `AppScaffold` registers an
 * `OnDestinationChangedListener` in a `DisposableEffect`, and
 * `NavController.addOnDestinationChangedListener` immediately calls a new
 * listener back with the current destination (navigation-common 2.8.5
 * `NavController.kt:413-428`). On a cold start that destination is the graph's
 * start destination — Home — so the write-back half ran with Home **before** the
 * read half (`LaunchedEffect { withContext(Dispatchers.IO) { load() } }`, whose
 * body is only dispatched afterwards) had read anything, and
 * `SharedPreferences.apply()` had already updated the in-memory map. The read
 * then found `home`, `NavRestore.target` answered `Route.HOME`, and the
 * `target != Route.HOME` branch never navigated. Upstream orders the two halves
 * the other way round: the restore is applied to the history *first* and the
 * persistence listener is attached after it
 * (`_upstream/packages/desktop/src/renderer/window/router.tsx:7-9`).
 *
 * This class replays that exact cold start on the pure half — a fake
 * [NavMemory] plus the two production predicates, no Compose and no Robolectric
 * — and pins the source of the call sites, so the ordering cannot be undone by
 * a later edit of `App.kt`.
 */
class LastRouteRestoreV97Test {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private val app = source("src/main/java/com/xiufeigo/opencodeshell/ui/App.kt")
    private val store = source("src/main/java/com/xiufeigo/opencodeshell/ui/nav/LastRouteStore.kt")

    /**
     * What `LastRouteStore` is on disk, and the only half a JVM test can reach.
     * `save` mirrors the real one: `putString(key, null)` drops the key, which is
     * what "home" leaves behind.
     */
    private class FakeNavMemory(route: String?, val sessionId: String?) : NavMemory {
        var route: String? = route
            private set
        var saves: Int = 0
            private set

        override fun load(): NavRestore.Stored? = NavRestore.stored(route, sessionId)

        override fun save(route: String, sessionId: String?) {
            this.route = route
            saves++
        }
    }

    /**
     * One cold start, in the order `AppScaffold` runs it. Returns the route the
     * shell is on when it settles.
     *
     * - the first composition sets the graph (`NavHost.kt:502`), which lands on
     *   `Route.HOME`;
     * - the effects then register the destination listener, which is called back
     *   with that Home *synchronously*, before the read coroutine is dispatched;
     * - the read coroutine finally runs and decides the restore target;
     * - a non-Home target is navigated to, and the destination change that
     *   causes is what finally persists the route.
     */
    private fun coldStart(
        memory: NavMemory,
        serverKey: String = "s1",
        pruneRestoredTarget: (String) -> Boolean = { false },
    ): String {
        var settled = false
        // `addOnDestinationChangedListener` → the immediate Home callback.
        NavRestore.toStore(settled, "home", null)?.let { memory.save(it.route, it.sessionId) }
        // The dispatched read.
        val target = NavRestore.target(memory.load(), serverKey)
        settled = true
        if (target == null || target == Route.HOME) return Route.HOME
        // `pruneRestoredTabs()` — a session the catalogue no longer lists backs
        // out to Home, and *that* navigation is what persists `home`.
        if (pruneRestoredTarget(target)) {
            NavRestore.toStore(settled, Route.HOME, null)?.let { memory.save(it.route, it.sessionId) }
            return Route.HOME
        }
        NavRestore.toStore(settled, Route.SESSION, target.substringAfterLast('/'))
            ?.let { memory.save(it.route, it.sessionId) }
        return target
    }

    // ------------------------------------------------ 1. a live session restores

    @Test
    fun `a stored session with a live id comes back on that session`() {
        val memory = FakeNavMemory("session", "ses_f03e2d5b8ffeBCDbQGqZHCliRd")
        val landed = coldStart(memory)
        assertEquals("server/s1/session/ses_f03e2d5b8ffeBCDbQGqZHCliRd", landed)
        // …and the value on disk is still that session, so the *next* cold start
        // restores it too instead of degrading to Home.
        assertEquals("session", memory.route)
        assertEquals(NavRestore.Stored("session", "ses_f03e2d5b8ffeBCDbQGqZHCliRd"), memory.load())
    }

    @Test
    fun `the stored value survives the first frame`() {
        // The whole bug in one assertion: the Home callback the listener is
        // handed on registration must not reach disk.
        val memory = FakeNavMemory("session", "ses_1")
        NavRestore.toStore(settled = false, destinationRoute = "home", sessionId = null)
            ?.let { memory.save(it.route, it.sessionId) }
        assertEquals("session", memory.route)
        assertEquals(0, memory.saves)
        assertEquals(NavRestore.Stored("session", "ses_1"), memory.load())
    }

    // --------------------------------------------- 2. an unusable id falls back

    @Test
    fun `a stored session with no id falls back to Home`() {
        for (id in listOf(null, "", "   ")) {
            val memory = FakeNavMemory("session", id)
            assertNull("id=$id must not restore", memory.load())
            assertEquals(Route.HOME, coldStart(memory))
        }
    }

    @Test
    fun `a stored id that cannot fill the route falls back to Home instead of throwing`() {
        // A worktree *directory* written as a session id (the v65 P0 shape, and
        // a hand-edited prefs file). `target` used to percent-encode it into a
        // route no destination matches, which the `NavController` rejects with
        // `IllegalArgumentException`; it has to answer null — upstream accepts
        // only `/^\/server\/[^/]+\/session\/[^/]+$/` for the same value
        // (`route-storage.ts:21`).
        val stored = NavRestore.stored("session", "/home/x/.local/share/opencode/worktree/ae54")
        assertNotNull(stored)
        assertNull(NavRestore.target(stored, "s1"))
        assertEquals(Route.HOME, coldStart(FakeNavMemory("session", "/home/x/wt")))
    }

    @Test
    fun `the raw route builder still encodes, so the v65 contract is untouched`() {
        assertEquals(
            "server/s1/session/%2Fhome%2Fx%2Fwt",
            NavRestore.session("s1", "/home/x/wt"),
        )
    }

    // ------------------------------------------------------ 3. a stored home

    @Test
    fun `a stored home stays on Home and never navigates`() {
        val memory = FakeNavMemory("home", null)
        assertEquals(Route.HOME, coldStart(memory))
        // No restore, so no write either: the value it already held is the truth
        // and a Home cold start must not flash a session frame.
        assertEquals("home", memory.route)
        assertEquals(0, memory.saves)
    }

    // ------------------------------------------------- 4. write-back protection

    @Test
    fun `nothing is persisted before the read has settled`() {
        assertNull(NavRestore.toStore(settled = false, destinationRoute = "home", sessionId = null))
        assertNull(
            NavRestore.toStore(
                settled = false,
                destinationRoute = Route.SESSION,
                sessionId = "ses_1",
            ),
        )
    }

    @Test
    fun `the gate is the whole difference between the bug and the fix`() {
        // Pre-fix the listener called the *ungated* overload with the first
        // destination it was handed — this is the write that destroyed
        // `oc_last_route=session` on every cold start:
        assertEquals(NavRestore.Stored("home", null), NavRestore.toStore("home", null))
        // The call `App.kt` makes now, with the restore read still outstanding:
        assertNull(NavRestore.toStore(settled = false, destinationRoute = "home", sessionId = null))
        // …and the same destination once the read has landed, which is the
        // value that *is* meant to be on disk by then.
        assertEquals(
            NavRestore.Stored("home", null),
            NavRestore.toStore(settled = true, destinationRoute = "home", sessionId = null),
        )
    }

    @Test
    fun `after the read every destination persists exactly as it did`() {
        val destinations = listOf(
            Triple("home", null, NavRestore.Stored("home", null)),
            Triple(Route.SESSION, "ses_1", NavRestore.Stored("session", "ses_1")),
            Triple("new-session", null, null),
            Triple("settings", null, null),
            Triple("terminal", null, null),
        )
        for ((route, id, expected) in destinations) {
            assertEquals("route=$route", expected, NavRestore.toStore(settled = true, route, id))
            // The gate only ever withholds; it never changes a settled answer.
            assertEquals(expected, NavRestore.toStore(route, id))
        }
    }

    @Test
    fun `a dead session falls back to Home and persists home only once it is proven dead`() {
        // The v50 LOW-2a prune: the stored id is a well-formed route, so the
        // restore navigates to it and the catalogue read is what decides it is
        // gone. The write that follows is the *answer*, not a guess made by the
        // first frame.
        val memory = FakeNavMemory("session", "ses_gone")
        var prunedTarget: String? = null
        val landed = coldStart(memory, pruneRestoredTarget = { target ->
            prunedTarget = target
            true
        })
        assertEquals("server/s1/session/ses_gone", prunedTarget)
        assertEquals(Route.HOME, landed)
        assertEquals("home", memory.route)
        assertEquals(NavRestore.Stored("home", null), memory.load())
    }

    @Test
    fun `a first run with nothing on disk writes nothing`() {
        val memory = FakeNavMemory(null, null)
        assertEquals(Route.HOME, coldStart(memory))
        assertEquals(0, memory.saves)
    }

    // -------------------------------------------------------- 5. the call sites

    @Test
    fun `the write-back is gated on the restore having read`() {
        // The call site is the fix: the listener passes the read's own gate.
        assertTrue(
            app.contains("NavRestore.toStore(navRestoreSettled, route, sessionId)"),
        )
        // …and the ungated form is gone from the nav listener. (It is still the
        // implementation `NavRestore.toStore(settled, …)` delegates to, so this
        // is asserted against `App.kt` only.)
        assertFalse(
            app.contains("NavRestore.toStore(route, sessionId)"),
        )
    }

    @Test
    fun `the restore is still a read that decides the initial route`() {
        assertTrue(app.contains("val stored = withContext(Dispatchers.IO) { lastRoute?.load() }"))
        assertTrue(app.contains("restoreTarget = NavRestore.target(stored, serverKey)"))
        assertTrue(app.contains("val target = restoreTarget"))
        // A Home restore still must not navigate (`target != Route.HOME`), so a
        // Home cold start cannot flash a session frame.
        assertTrue(app.contains("if (navRestoreSettled && target != null && target != Route.HOME) {"))
        assertTrue(app.contains("navController.navigate(target) { launchSingleTop = true }"))
    }

    @Test
    fun `the cold-start prune that backs a dead session out is still wired`() {
        // Unchanged by this fix, and the reason a *valid-looking but gone* id
        // ends on Home rather than on an error screen.
        assertTrue(app.contains("pruneRestoredTabs()"))
        assertTrue(app.contains("pruneRestoredTabs = pruneTabsAgainstServer"))
        val prune = app.substringAfter("val pruneTabsAgainstServer: () -> Unit = prune@{")
            .substringBefore("pruneRestoredTabs = pruneTabsAgainstServer")
        assertTrue(prune.contains("currentSession in result.prunedSessionIds"))
        assertTrue(prune.contains("navController.navigate(Route.HOME) { launchSingleTop = true }"))
    }

    @Test
    fun `the store still reads the keys the report found on disk`() {
        // The defect report's evidence is these exact keys, in this exact file.
        assertTrue(store.contains("const val KEY_ROUTE = \"oc_last_route\""))
        assertTrue(store.contains("const val KEY_SESSION_ID = \"oc_last_session_id\""))
        assertTrue(store.contains("const val PREFS = \"oc_nav_state\""))
        // `putString(key, null)` drops the key — "home" must not keep a stale id.
        assertTrue(store.contains(".putString(NavRestore.KEY_SESSION_ID, sessionId)"))
    }
}
