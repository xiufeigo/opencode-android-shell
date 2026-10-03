package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.data.HomeProjectRecords
import com.xiufeigo.opencodeshell.data.ProjectPaths
import com.xiufeigo.opencodeshell.ui.home.HomeSessions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v58 P0: 「每新建一次会话，项目路径就多拼一段 `app/src`」
 * (`research/v58_verify/5556_b/REPORT.md` §0 — the whole-site `500` that had been
 * read as a backend outage was this loop).
 *
 * opencode 2.0.18 answers a session created in `<repo>/app/src` with
 * `location.directory=<repo>/app/src` **and** `subpath=app/src` (verified live on
 * 4095), so joining the two produced `<repo>/app/src/app/src`; that value went
 * into the project store, became the next create's directory, and one `app/src`
 * was added per session until every request of that session answered
 * `500 FileSystem.realPath … ENOENT`.
 */
class ProjectDirectoryGrowthV58Test {

    private val repo = "/mnt/c/sensorsdata/main/program/opencode-android-shell"
    private val polluted = "$repo/app/src/app/src/app/src/app/src"

    private fun session(directory: String?, subpath: String?) =
        OpenCodeClient.Session(id = "ses_a", title = "t", directory = directory, subpath = subpath, updatedAt = 0L)

    // ------------------------------------------ 1. the increment itself

    @Test
    fun `a subpath the directory already ends with is not appended again`() {
        // The measured row: `directory=<repo>/app/src`, `subpath=app/src`.
        assertEquals(
            "$repo/app/src",
            HomeSessions.sessionDirectory(session("$repo/app/src", "app/src")),
        )
    }

    @Test
    fun `creating a session never grows the directory`() {
        // Every step of the user's loop, as the server answers it.
        var directory: String = "$repo/app/src"
        val seen = mutableListOf(directory)
        repeat(5) {
            directory = HomeSessions.sessionDirectory(session(directory, "app/src"))!!
            seen += directory
        }
        assertEquals(List(6) { "$repo/app/src" }, seen)
    }

    @Test
    fun `a Windows subpath session keeps its drive spelling`() {
        assertEquals(
            "C:\\repo\\app\\src",
            HomeSessions.sessionDirectory(session("C:\\repo\\app\\src", "app\\src")),
        )
    }

    @Test
    fun `a session whose directory is only the root still joins once`() {
        assertEquals(
            "$repo/app/src",
            HomeSessions.sessionDirectory(session(repo, "app/src")),
        )
    }

    // ------------------------------------------ 2. normalizeLocation

    @Test
    fun `a polluted directory normalizes to the real one`() {
        assertEquals(
            "$repo/app/src",
            ProjectPaths.normalizeLocation(polluted),
        )
        // Idempotent: normalizing the answer changes nothing.
        assertEquals(
            "$repo/app/src",
            ProjectPaths.normalizeLocation(ProjectPaths.normalizeLocation(polluted)),
        )
    }

    @Test
    fun `a repeated single segment is folded too`() {
        assertEquals("$repo/app", ProjectPaths.normalizeLocation("$repo/app/app"))
    }

    @Test
    fun `a normal directory is not rewritten`() {
        for (path in listOf(
            repo,
            "$repo/app/src",
            "$repo/app/src/main",
            "/home/xiufeigo/v36projC",
            "/tmp",
            "C:\\Users\\xiufe\\repos\\opencode-android-shell",
            "/mnt/c/Users/xiufe/repos/opencode-android-shell",
            "//wsl.localhost/Ubuntu/home/xiufeigo/proj",
        )) {
            assertEquals(path, ProjectPaths.normalizeLocation(path))
        }
    }

    @Test
    fun `dot segments and duplicate separators resolve without moving the root`() {
        assertEquals("$repo/app", ProjectPaths.normalizeLocation("$repo/app/./src/.."))
        assertEquals("$repo/app", ProjectPaths.normalizeLocation("$repo//app///src/.."))
        assertEquals("C:\\repo\\app", ProjectPaths.normalizeLocation("C:\\repo\\app\\.\\"))
        assertEquals("//host/share/repo", ProjectPaths.normalizeLocation("\\\\host\\share\\repo"))
    }

    @Test
    fun `the Windows and WSL spellings of one directory survive unchanged`() {
        assertEquals("/mnt/c/sensorsdata", ProjectPaths.normalizeLocation("/mnt/c/sensorsdata"))
        assertEquals("C:/sensorsdata", ProjectPaths.normalizeLocation("C:/sensorsdata"))
        assertEquals("C:\\sensorsdata", ProjectPaths.normalizeLocation("C:\\sensorsdata"))
    }

    @Test
    fun `a relative directory keeps its relativity and a blank one is null`() {
        assertEquals("app/src", ProjectPaths.normalizeLocation("app/src"))
        assertEquals("../repo", ProjectPaths.normalizeLocation("../repo"))
        assertNull(ProjectPaths.normalizeLocation(null))
        assertNull(ProjectPaths.normalizeLocation("   "))
    }

    // ------------------------------------------ 3. the store write

    @Test
    fun `a polluted session directory heals before it reaches the store`() {
        // What Home does (`registerProject`): open + touch the session's
        // directory. The heal happens on the way in, so the row the user sees in
        // 「编辑项目」 is the real path.
        val healed = HomeSessions.sessionDirectory(session(polluted, "app/src"))
        var state = HomeProjectRecords.open(HomeProjectRecords.State(), healed!!)
        state = HomeProjectRecords.touch(state, healed)
        assertEquals(listOf("$repo/app/src"), state.list.map { it.worktree })
        assertEquals("$repo/app/src", state.lastProject)
    }

    @Test
    fun `a normal project row is stored verbatim`() {
        val state = HomeProjectRecords.open(HomeProjectRecords.State(), "$repo/app/src")
        assertEquals(listOf("$repo/app/src"), state.list.map { it.worktree })
        assertEquals("$repo/app/src", ProjectPaths.normalizeLocation(state.list.single().worktree))
    }
}
