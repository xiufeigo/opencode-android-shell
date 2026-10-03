package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.data.HomeProjectRecords
import com.xiufeigo.opencodeshell.ui.home.HomeProjectEdit
import com.xiufeigo.opencodeshell.ui.home.HomeProjects
import com.xiufeigo.opencodeshell.ui.home.HomeSessions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Project rename display (P1-B): upstream `displayName(project)`
 * (`shell/layout/helpers.ts:50-51`) is
 * `project.name || getFilename(project.worktree) || project.worktree`, but the
 * column only ever printed the folder name — a rename saved through
 * 「Edit project」 never showed, not even after a restart, even though
 * `GET /api/project` already returned the new `name`.
 *
 * The server/catalogue half arrives as [HomeProjectEdit.namesByKey] (match key
 * → name), the client-local half as [HomeProjectRecords.setName]; both feed
 * [HomeProjects.displayName] (server > local > folder name > path).
 */
class HomeProjectRenameTest {

    private fun serverProject(worktree: String, name: String, sandboxes: List<String> = emptyList()) =
        HomeProjectEdit.ServerProject(id = worktree, worktree = worktree, sandboxes = sandboxes, name = name)

    private fun catalogProject(worktree: String) =
        OpenCodeClient.Project(id = worktree, worktree = worktree, vcs = null, iconColor = null)

    private fun namesOf(vararg projects: HomeProjectEdit.ServerProject): Map<String, String> =
        HomeProjectEdit.namesByKey(projects.toList())

    // ---- displayName priority ------------------------------------------------

    @Test
    fun `server name wins over the folder name`() {
        val names = namesOf(serverProject("/home/xiufeigo", "E2ECHK_xiufeigo"))
        assertEquals("E2ECHK_xiufeigo", HomeProjects.displayName("/home/xiufeigo", names))
    }

    @Test
    fun `missing name falls back to the folder name`() {
        assertEquals("xiufeigo", HomeProjects.displayName("/home/xiufeigo"))
        assertEquals("xiufeigo", HomeProjects.displayName("/home/xiufeigo", emptyMap(), localName = "  "))
    }

    @Test
    fun `blank server name falls back to the folder name`() {
        val names = namesOf(serverProject("/home/xiufeigo", "   "))
        assertTrue(names.isEmpty())
        assertEquals("xiufeigo", HomeProjects.displayName("/home/xiufeigo", names))
    }

    @Test
    fun `server name wins over the local record name`() {
        val names = namesOf(serverProject("/home/xiufeigo", "E2ECHK_xiufeigo"))
        assertEquals("E2ECHK_xiufeigo", HomeProjects.displayName("/home/xiufeigo", names, localName = "Stale"))
    }

    @Test
    fun `local record name covers a server without one`() {
        assertEquals("Offline", HomeProjects.displayName("/home/xiufeigo", emptyMap(), localName = "Offline"))
    }

    @Test
    fun `nameless root falls back to its path`() {
        assertEquals("/", HomeProjects.displayName("/"))
        assertEquals("C:\\", HomeProjects.displayName("C:\\"))
    }

    // ---- merge / openRows ----------------------------------------------------

    @Test
    fun `merge prints the server name`() {
        val merged = HomeProjects.merge(
            projects = listOf(catalogProject("/home/xiufeigo")),
            sessions = emptyList(),
            names = namesOf(serverProject("/home/xiufeigo", "E2ECHK_xiufeigo")),
        )
        assertEquals("E2ECHK_xiufeigo", merged.single().name)
    }

    @Test
    fun `openRows prefers the server name then the record name`() {
        val opened = listOf(HomeProjectRecords.Project("/home/xiufeigo", name = "Local"))
        val server = namesOf(serverProject("/home/xiufeigo", "E2ECHK_xiufeigo"))
        val withServer = HomeProjects.openRows(opened = opened, known = emptyList(), names = server)
        assertEquals("E2ECHK_xiufeigo", withServer.single().name)
        val offline = HomeProjects.openRows(opened = opened, known = emptyList())
        assertEquals("Local", offline.single().name)
    }

    @Test
    fun `rename reflects immediately through setName`() {
        // The Save → Home frame: the dialog's onSaved stores the name before
        // the next catalogue read arrives.
        var state = HomeProjectRecords.open(HomeProjectRecords.State(), "/home/xiufeigo")
        state = HomeProjectRecords.setName(state, "/home/xiufeigo", "E2ECHK_xiufeigo")
        val rows = HomeProjects.openRows(opened = state.list, known = emptyList())
        assertEquals("E2ECHK_xiufeigo", rows.single().name)
        // Clearing the name falls back to the folder name, never an empty row.
        val cleared = HomeProjectRecords.setName(state, "/home/xiufeigo", "  ")
        assertEquals("xiufeigo", HomeProjects.openRows(opened = cleared.list, known = emptyList()).single().name)
    }

    @Test
    fun `setName matches across spellings and ignores unknown directories`() {
        var state = HomeProjectRecords.open(HomeProjectRecords.State(), "C:\\repo")
        state = HomeProjectRecords.setName(state, "c:/repo/", "Renamed")
        assertEquals("Renamed", state.list.single().name)
        assertEquals(state, HomeProjectRecords.setName(state, "/elsewhere", "Nope"))
        assertEquals(state, HomeProjectRecords.setName(state, "   ", "Nope"))
    }

    @Test
    fun `name survives an encode decode round trip`() {
        val state = HomeProjectRecords.setName(
            HomeProjectRecords.open(HomeProjectRecords.State(), "/home/xiufeigo"),
            "/home/xiufeigo",
            "E2ECHK_xiufeigo",
        )
        val restarted = HomeProjectRecords.decode(HomeProjectRecords.encode(state))
        assertEquals("E2ECHK_xiufeigo", restarted.list.single().name)
        assertEquals(
            "E2ECHK_xiufeigo",
            HomeProjects.openRows(opened = restarted.list, known = emptyList()).single().name,
        )
    }

    @Test
    fun `msys and win32 spellings share one server name`() {
        // The catalogue reports `/mnt/c/repo`, the record holds `C:\repo`:
        // one row, server-named.
        val names = namesOf(serverProject("/mnt/c/repo", "Shared"))
        val opened = listOf(HomeProjectRecords.Project("C:\\repo"))
        val rows = HomeProjects.openRows(opened = opened, known = emptyList(), names = names)
        assertEquals(1, rows.size)
        assertEquals("Shared", rows.single().name)
    }

    // ---- namesByKey ----------------------------------------------------------

    @Test
    fun `namesByKey maps sandbox spellings and drops blanks`() {
        val names = namesOf(
            serverProject("/home/u/repo", "Main", sandboxes = listOf("/home/u/repo-sb")),
            serverProject("/home/u/plain", ""),
        )
        assertEquals("Main", names[HomeSessions.matchKey("/home/u/repo-sb")])
        assertNull(names[HomeSessions.matchKey("/home/u/plain")])
    }

    // ---- session rows / closed rows ------------------------------------------

    @Test
    fun `session row labels prefer the server name`() {
        val session = OpenCodeClient.Session(id = "a", title = "a", directory = "/home/xiufeigo", updatedAt = 1L)
        val names = namesOf(serverProject("/home/xiufeigo", "E2ECHK_xiufeigo"))
        assertEquals("E2ECHK_xiufeigo", HomeSessions.rows(listOf(session), projectNames = names).single().project)
        assertEquals("xiufeigo", HomeSessions.rows(listOf(session)).single().project)
        assertNull(HomeSessions.projectLabel("   ", names))
    }

    @Test
    fun `closedRows prints the server name`() {
        val rows = HomeProjects.closedRows(
            closed = listOf("/home/xiufeigo"),
            names = namesOf(serverProject("/home/xiufeigo", "E2ECHK_xiufeigo")),
        )
        assertEquals("E2ECHK_xiufeigo", rows.single().name)
    }
}
