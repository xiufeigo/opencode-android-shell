package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.data.HomeProjectRecords
import com.xiufeigo.opencodeshell.data.HomeProjectStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The client-local opened-project store
 * (`_upstream/packages/app/src/runtime/server/registry.tsx:34-99`) — the 项目
 * column's real source.
 *
 * [HomeProjectRecords] is the pure half and [HomeProjectStore] is the
 * `SharedPreferences` shell around it, exactly like `NavRestore` /
 * `LastRouteStore`; only the pure half is reachable from a JVM test.
 */
class HomeProjectStoreTest {

    private val home = HomeProjectRecords.State()

    private fun opened(state: HomeProjectRecords.State): List<String> = state.list.map { it.worktree }

    // ------------------------------------------------------ the five operations

    @Test
    fun `open prepends and is idempotent`() {
        val state = HomeProjectRecords.open(HomeProjectRecords.open(home, "/work/api"), "/work/site")

        assertEquals(listOf("/work/site", "/work/api"), opened(state))
        // Re-opening a listed project is a no-op: upstream keeps its position
        // (`registry.tsx:65`), only a user `move` reorders.
        assertEquals(state, HomeProjectRecords.open(state, "/work/api"))
        // A blank directory never becomes a row.
        assertEquals(state, HomeProjectRecords.open(state, "   "))
        assertEquals(HomeProjectRecords.State(), HomeProjectRecords.open(home, ""))
    }

    @Test
    fun `re-opening a closed project takes it out of recently closed`() {
        val state = HomeProjectRecords.close(
            HomeProjectRecords.open(HomeProjectRecords.open(home, "/work/api"), "/work/site"),
            "/work/api",
        )
        val reopened = HomeProjectRecords.open(state, "/work/api")

        assertEquals(listOf("/work/api", "/work/site"), opened(reopened))
        assertTrue(reopened.recentlyClosed.isEmpty())
        // A re-open of an already listed project still clears its closed entry.
        val again = HomeProjectRecords.open(reopened, "/work/api")
        assertEquals(listOf("/work/api", "/work/site"), opened(again))
    }

    @Test
    fun `close moves the project into recently closed, most recent first`() {
        val state = HomeProjectRecords.close(
            HomeProjectRecords.close(
                HomeProjectRecords.open(HomeProjectRecords.open(home, "/work/api"), "/work/site"),
                "/work/api",
            ),
            "/work/site",
        )

        assertTrue(state.list.isEmpty())
        assertEquals(listOf("/work/site", "/work/api"), state.recentlyClosed)
        // Closing the same directory twice moves one entry instead of stacking
        // two (upstream filters by `pathKey`, `registry.tsx:73`).
        val twice = HomeProjectRecords.close(state, "/work/api/")
        assertEquals(listOf("/work/api/", "/work/site"), twice.recentlyClosed)
        // Closing a directory that is not open still records it, like upstream.
        assertEquals(listOf("/other", "/work/api/", "/work/site"), HomeProjectRecords.close(twice, "/other").recentlyClosed)
    }

    @Test
    fun `touch records the most recently used directory`() {
        assertNull(home.lastProject)
        val touched = HomeProjectRecords.touch(HomeProjectRecords.touch(home, "/work/api"), "/work/site")
        assertEquals("/work/site", touched.lastProject)
        // A blank touch changes nothing, and re-touching the same directory is a
        // no-op (so no preference write happens).
        assertEquals(touched, HomeProjectRecords.touch(touched, "/work/site"))
        assertEquals(touched, HomeProjectRecords.touch(touched, " "))
    }

    @Test
    fun `move reorders and clamps`() {
        val state = HomeProjectRecords.open(
            HomeProjectRecords.open(HomeProjectRecords.open(home, "/a"), "/b"),
            "/c",
        )
        assertEquals(listOf("/c", "/b", "/a"), opened(state))

        assertEquals(listOf("/a", "/c", "/b"), opened(HomeProjectRecords.move(state, "/a", 0)))
        assertEquals(listOf("/b", "/a", "/c"), opened(HomeProjectRecords.move(state, "/c", 2)))
        // Out of range clamps to the end (`Array.splice`), and moving the last
        // entry to the end is a no-op.
        assertEquals(listOf("/b", "/a", "/c"), opened(HomeProjectRecords.move(state, "/c", 99)))
        assertEquals(state, HomeProjectRecords.move(state, "/a", 99))
        // Unknown worktree and a no-op move leave the record alone.
        assertEquals(state, HomeProjectRecords.move(state, "/missing", 0))
        assertEquals(state, HomeProjectRecords.move(state, "/b", 1))
    }

    @Test
    fun `directories is the worktree plus its sandboxes`() {
        val project = HomeProjectRecords.Project("/work/api", sandboxes = listOf("/work/api-sandbox", "  "))
        assertEquals(listOf("/work/api", "/work/api-sandbox"), HomeProjectRecords.directories(project))
        assertEquals(listOf("/work/api"), HomeProjectRecords.directories(HomeProjectRecords.Project("/work/api")))
    }

    // ---------------------------------------------------------------- the JSON

    @Test
    fun `the record round trips through its persisted form`() {
        val state = HomeProjectRecords.State(
            list = listOf(
                HomeProjectRecords.Project("C:\\src\\app", sandboxes = listOf("C:\\src\\app-sandbox")),
                HomeProjectRecords.Project("/work/api"),
            ),
            lastProject = "/work/api",
            recentlyClosed = listOf("C:\\src\\old"),
        )

        val raw = HomeProjectRecords.encode(state)
        assertEquals(state, HomeProjectRecords.decode(raw))
        // The field names are upstream's (`projects` / `lastProject` /
        // `recentlyClosed`), so a record can be read back by name.
        assertTrue(raw.contains("\"projects\""))
        assertTrue(raw.contains("\"lastProject\""))
        assertTrue(raw.contains("\"recentlyClosed\""))
        // A sandbox-less project does not carry an empty array.
        assertFalse(raw.contains("\"sandboxes\":[]"))
    }

    @Test
    fun `a blank or missing record is an empty state`() {
        assertEquals(HomeProjectRecords.State(), HomeProjectRecords.decode(null))
        assertEquals(HomeProjectRecords.State(), HomeProjectRecords.decode(""))
        assertEquals(HomeProjectRecords.State(), HomeProjectRecords.decode("   "))
    }

    @Test
    fun `a damaged record degrades instead of crashing Home`() {
        // Not JSON at all.
        assertEquals(HomeProjectRecords.State(), HomeProjectRecords.decode("{oops"))
        // JSON, but with the wrong shape: entries without a worktree, duplicates
        // and blanks are dropped; unknown fields are ignored.
        val decoded = HomeProjectRecords.decode(
            """
            {"projects":["/work/api",{"worktree":"  "},{"worktree":"/work/api"},{"sandboxes":["/x"]},{"worktree":"/work/site","sandboxes":["/s"]}],
             "recentlyClosed":["", "/closed", "/closed/"],
             "lastProject":"  ",
             "hidden":["server-1"]}
            """.trimIndent(),
        )

        assertEquals(listOf("/work/api", "/work/site"), opened(decoded))
        assertEquals(listOf("/s"), decoded.list[1].sandboxes)
        assertEquals(listOf("/closed"), decoded.recentlyClosed)
        assertNull(decoded.lastProject)
        // A record whose `projects` is not an array at all is empty, not a crash.
        assertEquals(HomeProjectRecords.State(), HomeProjectRecords.decode("""{"projects":42}"""))
    }

    // ------------------------------------------------- what the store persists

    @Test
    fun `the store keys one record per server`() {
        assertEquals("oc_home_projects", HomeProjectStore.PREFS)
        assertEquals("projects:default", HomeProjectStore.key(HomeProjectStore.DEFAULT_SCOPE))
        assertEquals("projects:s1a2b3c4", HomeProjectStore.key("s1a2b3c4"))
        // Distinct servers must not share a record (upstream `store.projects[scope]`).
        assertTrue(HomeProjectStore.key("s1") != HomeProjectStore.key("s2"))
    }
}
