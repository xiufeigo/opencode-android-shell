package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.data.HomeProjectRecords
import com.xiufeigo.opencodeshell.data.HomeProjectStore
import com.xiufeigo.opencodeshell.data.ProjectPaths
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
    fun `open treats separator case and trailing slash variants as one project`() {
        var state = HomeProjectRecords.open(home, "C:\\src\\app")
        state = HomeProjectRecords.open(state, "C:/src/app")
        state = HomeProjectRecords.open(state, "c:/SRC/app/")
        assertEquals(listOf("C:\\src\\app"), opened(state))
        // A re-open under another spelling keeps the row's position.
        state = HomeProjectRecords.open(HomeProjectRecords.open(state, "/work/site"), "c:\\SRC\\APP")
        assertEquals(listOf("/work/site", "C:\\src\\app"), opened(state))
        // POSIX stays case-sensitive: a different case is a different directory.
        state = HomeProjectRecords.open(home, "/work/api")
        assertEquals(
            listOf("/work/API", "/work/api"),
            opened(HomeProjectRecords.open(state, "/work/API")),
        )
    }

    @Test
    fun `close removes every spelling of the directory`() {
        val state = HomeProjectRecords.open(
            HomeProjectRecords.open(home, "C:\\src\\app"),
            "/work/site",
        )
        val closed = HomeProjectRecords.close(state, "c:/src/app/")
        assertEquals(listOf("/work/site"), opened(closed))
        assertEquals(listOf("c:/src/app/"), closed.recentlyClosed)
    }

    @Test
    fun `move finds the project under another spelling`() {
        val state = HomeProjectRecords.open(
            HomeProjectRecords.open(HomeProjectRecords.open(home, "C:\\a"), "C:\\b"),
            "C:\\c",
        )
        assertEquals(
            listOf("C:\\a", "C:\\c", "C:\\b"),
            opened(HomeProjectRecords.move(state, "c:/a", 0)),
        )
    }

    @Test
    fun `decode collapses windows case variants and unions their sandboxes`() {
        val decoded = HomeProjectRecords.decode(
            """
            {"projects":[{"worktree":"C:\\Repo","sandboxes":["C:\\sb1"]},{"worktree":"c:/repo/","sandboxes":["c:\\SB1","C:\\sb2"]}],
             "recentlyClosed":["C:\\Old","c:/old/"],
             "lastProject":"C:\\Repo"}
            """.trimIndent(),
        )
        assertEquals(listOf("C:\\Repo"), opened(decoded))
        assertEquals(listOf("C:\\sb1", "C:\\sb2"), decoded.list.single().sandboxes)
        assertEquals(listOf("C:\\Old"), decoded.recentlyClosed)
        // The healed record persists as one row.
        assertEquals(decoded, HomeProjectRecords.decode(HomeProjectRecords.encode(decoded)))
    }

    @Test
    fun `msys and win32 spellings share one match key`() {
        val msys = "/mnt/c/sensorsdata/main/program/opencode-android-shell"
        // Separator, case and trailing-slash variants all fold together.
        assertEquals(
            ProjectPaths.matchKey("C:/sensorsdata/main/program/opencode-android-shell"),
            ProjectPaths.matchKey(msys),
        )
        assertEquals(
            ProjectPaths.matchKey("C:\\SensorsData\\Main\\Program\\OpenCode-Android-Shell"),
            ProjectPaths.matchKey(msys),
        )
        assertEquals(ProjectPaths.matchKey(msys), ProjectPaths.matchKey("$msys/"))
        assertEquals(ProjectPaths.matchKey("C:\\"), ProjectPaths.matchKey("/mnt/c"))
        assertEquals(ProjectPaths.matchKey("c:/"), ProjectPaths.matchKey("/mnt/c/"))
        // Not a drive: `/mnt/<multi-letter>/…` and bare `/mnt/` stay POSIX and
        // must not merge with any drive key.
        assertEquals("/mnt/cfoo/bar", ProjectPaths.matchKey("/mnt/cfoo/bar"))
        assertEquals("/mnt/data", ProjectPaths.matchKey("/mnt/data"))
        assertTrue(ProjectPaths.matchKey("/mnt/data") != ProjectPaths.matchKey("d:/ata"))
    }

    @Test
    fun `cygdrive and win32 spellings share one match key`() {
        val cygdrive = "/cygdrive/c/sensorsdata/main/program/opencode-android-shell"
        assertEquals(
            ProjectPaths.matchKey("C:/sensorsdata/main/program/opencode-android-shell"),
            ProjectPaths.matchKey(cygdrive),
        )
        assertEquals(
            ProjectPaths.matchKey("C:\\SensorsData\\Main\\Program\\OpenCode-Android-Shell"),
            ProjectPaths.matchKey(cygdrive),
        )
        assertEquals(ProjectPaths.matchKey(cygdrive), ProjectPaths.matchKey("$cygdrive/"))
        assertEquals(ProjectPaths.matchKey("C:\\"), ProjectPaths.matchKey("/cygdrive/c"))
        assertEquals(ProjectPaths.matchKey("c:/"), ProjectPaths.matchKey("/CYGDRIVE/C/"))
        // The two POSIX drive spellings are twins of each other too.
        assertEquals(
            ProjectPaths.matchKey("/mnt/c/sensorsdata"),
            ProjectPaths.matchKey("/cygdrive/c/sensorsdata"),
        )
        // Not a drive: multi-letter names and bare prefixes stay POSIX.
        assertEquals("/cygdrive/cfoo/bar", ProjectPaths.matchKey("/cygdrive/cfoo/bar"))
        assertEquals("/cygdrive", ProjectPaths.matchKey("/cygdrive"))
        assertEquals("/cygdrive", ProjectPaths.matchKey("/cygdrive/"))
        assertTrue(ProjectPaths.matchKey("/cygdrive/data") != ProjectPaths.matchKey("d:/ata"))
    }

    @Test
    fun `wsl unc spellings share one match key`() {
        val backslash = "\\\\wsl.localhost\\Ubuntu\\home\\xiufeigo\\repo"
        val dollar = "//wsl$/Ubuntu/home/xiufeigo/repo"
        assertEquals(ProjectPaths.matchKey(backslash), ProjectPaths.matchKey(dollar))
        // Separator mix and host case still fold together.
        assertEquals(
            ProjectPaths.matchKey("//WSL$/Ubuntu/home/xiufeigo/repo"),
            ProjectPaths.matchKey(dollar),
        )
        assertEquals(
            ProjectPaths.matchKey("//wsl.localhost\\Ubuntu\\home\\xiufeigo\\repo"),
            ProjectPaths.matchKey(dollar),
        )
        assertEquals(ProjectPaths.matchKey(dollar), ProjectPaths.matchKey("$dollar/"))
        // A non-WSL share keeps its own key and stays case-folded.
        assertEquals("//host/share/dir", ProjectPaths.matchKey("\\\\HOST\\SHARE\\dir"))
        assertTrue(ProjectPaths.matchKey("//host/share") != ProjectPaths.matchKey(dollar))
        // POSIX paths are untouched by the UNC fold.
        assertEquals("/home/xiufeigo/repo", ProjectPaths.matchKey("/home/xiufeigo/repo"))
    }

    @Test
    fun `open merges cygdrive and unc twins into one row`() {
        val win32 = "C:\\sensorsdata\\main\\program\\opencode-android-shell"
        val cygdrive = "/cygdrive/c/sensorsdata/main/program/opencode-android-shell"
        var state = HomeProjectRecords.open(HomeProjectRecords.open(home, win32), cygdrive)
        assertEquals(listOf(win32), opened(state))
        val uncA = "\\\\wsl.localhost\\Ubuntu\\home\\xiufeigo\\repo"
        val uncB = "//wsl$/Ubuntu/home/xiufeigo/repo"
        state = HomeProjectRecords.open(HomeProjectRecords.open(home, uncA), uncB)
        assertEquals(listOf(uncA), opened(state))
        val closed = HomeProjectRecords.close(state, uncB)
        assertTrue(closed.list.isEmpty())
        assertEquals(1, closed.recentlyClosed.size)
        assertTrue(HomeProjectRecords.open(closed, uncA).recentlyClosed.isEmpty())
    }

    @Test
    fun `open merges msys and win32 twins into one row`() {        val win32 = "C:\\sensorsdata\\main\\program\\opencode-android-shell"
        val msys = "/mnt/c/sensorsdata/main/program/opencode-android-shell"
        // Each spelling opened once leaves a single row (first spelling wins).
        var state = HomeProjectRecords.open(HomeProjectRecords.open(home, win32), msys)
        assertEquals(listOf(win32), opened(state))
        var reverse = HomeProjectRecords.open(HomeProjectRecords.open(home, msys), win32)
        assertEquals(listOf(msys), opened(reverse))
        // A re-open under the twin spelling keeps the row's position.
        state = HomeProjectRecords.open(HomeProjectRecords.open(state, "/work/site"), "$msys/")
        assertEquals(listOf("/work/site", win32), opened(state))
    }

    @Test
    fun `close clears msys twins from either spelling`() {
        val win32 = "C:\\sensorsdata\\main\\program\\opencode-android-shell"
        val msys = "/mnt/c/sensorsdata/main/program/opencode-android-shell"
        // A record an older build persisted as two spellings of one directory.
        val twins = HomeProjectRecords.State(
            list = listOf(
                HomeProjectRecords.Project(msys),
                HomeProjectRecords.Project(win32),
                HomeProjectRecords.Project("/work/site"),
            ),
        )
        // Closing with the Win32 spelling removes both twins, one tombstone.
        val closedWin = HomeProjectRecords.close(twins, "c:/sensorsdata/main/program/opencode-android-shell/")
        assertEquals(listOf("/work/site"), opened(closedWin))
        assertEquals(1, closedWin.recentlyClosed.size)
        // Closing with the MSYS spelling does the same.
        val closedMsys = HomeProjectRecords.close(twins, msys)
        assertEquals(listOf("/work/site"), opened(closedMsys))
        assertEquals(1, closedMsys.recentlyClosed.size)
        // Re-opening under either spelling drops the twin tombstone too.
        assertTrue(HomeProjectRecords.open(closedMsys, win32).recentlyClosed.isEmpty())
        assertTrue(HomeProjectRecords.open(closedWin, msys).recentlyClosed.isEmpty())
    }

    @Test
    fun `decode collapses msys and win32 twins and unions their sandboxes`() {
        val decoded = HomeProjectRecords.decode(
            """
            {"projects":[{"worktree":"C:\\Repo","sandboxes":["C:\\sb1"]},{"worktree":"/mnt/c/repo/","sandboxes":["/mnt/c/SB1","C:\\sb2"]}],
             "recentlyClosed":["/mnt/c/old","C:\\Old"],
             "lastProject":"/mnt/c/repo"}
            """.trimIndent(),
        )
        assertEquals(listOf("C:\\Repo"), opened(decoded))
        assertEquals(listOf("C:\\sb1", "C:\\sb2"), decoded.list.single().sandboxes)
        assertEquals(listOf("/mnt/c/old"), decoded.recentlyClosed)
        // The healed record persists as one row.
        assertEquals(decoded, HomeProjectRecords.decode(HomeProjectRecords.encode(decoded)))
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
