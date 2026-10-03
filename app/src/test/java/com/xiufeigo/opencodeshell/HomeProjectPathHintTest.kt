package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.home.HomeProjects
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Telling two same-named projects apart, and re-using a registered project's
 * entry when a new one is created for the same directory (v52 tD: a session
 * 「移动到…」 a registered project's directory added a second `v36projC` row,
 * and the column printed nothing that said which was which).
 *
 * [HomeProjects.canonicalFor] is the "reuse, do not duplicate" half;
 * [HomeProjects.pathHint] / [HomeProjects.rowLabel] are the "distinguish" half.
 */
class HomeProjectPathHintTest {

    private fun project(name: String, path: String) = HomeProjects.Project(name = name, path = path)

    // ---- canonicalFor: one directory is one project ------------------------

    @Test
    fun `a registered worktree answers for its own spelling`() {
        val known = listOf(project("repo", "/work/api"))
        assertEquals(
            "/work/api",
            HomeProjects.canonicalFor("/work/api", known),
        )
    }

    @Test
    fun `an MSYS spelling of a registered worktree adopts the catalogue's`() {
        val known = listOf(project("projC", "C:\\src\\v36projC"))
        assertEquals("C:\\src\\v36projC", HomeProjects.canonicalFor("/mnt/c/src/v36projC", known))
        assertEquals("C:\\src\\v36projC", HomeProjects.canonicalFor("/cygdrive/c/src/v36projC", known))
        assertEquals("C:\\src\\v36projC", HomeProjects.canonicalFor("c:/src/v36projc/", known))
        assertEquals("C:\\src\\v36projC", HomeProjects.canonicalFor("//?/C:/src/v36projC", known))
    }

    @Test
    fun `the drive-less picker spelling of a registered worktree adopts the catalogue's`() {
        val known = listOf(project("projC", "C:\\src\\v36projC"))
        assertEquals("C:\\src\\v36projC", HomeProjects.canonicalFor("/src/v36projC", known))
    }

    @Test
    fun `a UNC spelling of a registered worktree adopts the catalogue's`() {
        val known = listOf(project("repo", "\\\\host\\share\\repo"))
        assertEquals("\\\\host\\share\\repo", HomeProjects.canonicalFor("//host/share/repo", known))
    }

    @Test
    fun `an unregistered directory keeps the spelling it was handed`() {
        assertNull(HomeProjects.canonicalFor("/work/new", listOf(project("api", "/work/api"))))
        assertNull(HomeProjects.canonicalFor("   ", listOf(project("api", "/work/api"))))
        assertNull(HomeProjects.canonicalFor("/work/api", emptyList()))
    }

    @Test
    fun `two genuinely different directories never collapse onto one entry`() {
        val known = listOf(project("v36projC", "/home/xiufeigo/v36projC"))
        // Same basename, different directory: this is the v52 tD pair, and it
        // must stay two projects — the catalogue lookup is identity, not name.
        assertNull(HomeProjects.canonicalFor("/mnt/c/sensorsdata/repo/v36projC", known))
    }

    // ---- pathHint / rowLabel: make the twins readable ----------------------

    @Test
    fun `a unique name prints no path`() {
        val rows = listOf(project("v36projC", "/home/xiufeigo/v36projC"), project("api", "/work/api"))
        val row = rows.first()
        assertNull(HomeProjects.pathHint(row, rows))
        assertEquals("v36projC", HomeProjects.rowLabel(row, rows))
    }

    @Test
    fun `same-named rows get the shortest distinguishing tail`() {
        val rows = listOf(
            project("v36projC", "/home/xiufeigo/v36projC"),
            project("v36projC", "/mnt/c/sensorsdata/main/program/opencode-android-shell/v36projC"),
        )
        assertEquals("xiufeigo/v36projC", HomeProjects.pathHint(rows[0], rows))
        assertEquals("opencode-android-shell/v36projC", HomeProjects.pathHint(rows[1], rows))
        assertEquals(
            "v36projC · xiufeigo/v36projC",
            HomeProjects.rowLabel(rows[0], rows),
        )
    }

    @Test
    fun `siblings under one parent differ at the parent segment`() {
        val rows = listOf(project("api", "/work/alpha/api"), project("api", "/work/beta/api"))
        assertEquals("alpha/api", HomeProjects.pathHint(rows[0], rows))
        assertEquals("beta/api", HomeProjects.pathHint(rows[1], rows))
    }

    @Test
    fun `a pair that differs only in case falls back to the whole path`() {
        val rows = listOf(project("Repo", "/work/repo"), project("Repo", "/WORK/REPO"))
        // Case-only difference: on a case-insensitive host these are one
        // directory, on a POSIX one they are two — either way the hint must not
        // read as the bare name that is already on screen, so it widens all the
        // way to the full path rather than pretending `work/repo` tells them
        // apart.
        assertEquals("/work/repo", HomeProjects.pathHint(rows[0], rows))
        assertEquals("/WORK/REPO", HomeProjects.pathHint(rows[1], rows))
    }

    @Test
    fun `identical paths never hint at themselves`() {
        val rows = listOf(project("api", "/work/api"), project("api", "/work/api"))
        assertNull(HomeProjects.pathHint(rows[0], rows))
        assertEquals("api", HomeProjects.rowLabel(rows[0], rows))
    }

    @Test
    fun `a trailing separator widens the hint to the full path`() {
        // The two spellings normalise to one directory, so no segment tail can
        // tell them apart — the answer stays the full path, which does.
        val rows = listOf(project("api", "/work/api"), project("api", "/work/api/"))
        assertEquals("/work/api", HomeProjects.pathHint(rows[0], rows))
        assertEquals("/work/api/", HomeProjects.pathHint(rows[1], rows))
    }

    @Test
    fun `a single-segment path falls back to the whole path`() {
        val rows = listOf(project("repo", "/repo"), project("repo", "/home/repo"))
        assertEquals("/repo", HomeProjects.pathHint(rows[0], rows))
        assertEquals("home/repo", HomeProjects.pathHint(rows[1], rows))
    }

    @Test
    fun `windows separators are normalised into the hint`() {
        val rows = listOf(
            project("projC", "C:\\src\\v36projC"),
            project("projC", "C:\\src\\worktree\\v36projC"),
        )
        assertEquals("src/v36projC", HomeProjects.pathHint(rows[0], rows))
        assertEquals("worktree/v36projC", HomeProjects.pathHint(rows[1], rows))
    }
}
