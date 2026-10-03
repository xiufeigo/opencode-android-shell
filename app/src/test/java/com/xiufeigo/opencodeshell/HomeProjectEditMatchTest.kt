package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.home.HomeProjectEdit
import com.xiufeigo.opencodeshell.ui.home.ProjectFault
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The project-row lookup behind 「Edit project…」 / 「Refresh worktrees」: which
 * `GET /api/project` row owns a Home worktree path, and how a failed catalog
 * call is reported. Complements `HomeProjectEditModelTest` (the patch builder).
 */
class HomeProjectEditMatchTest {

    private val main = HomeProjectEdit.ServerProject(
        id = "prj_main",
        worktree = "/home/u/repo",
        sandboxes = emptyList(),
    )

    private val checkout = HomeProjectEdit.ServerProject(
        id = "prj_wt",
        worktree = "C:\\sensorsdata\\cyber-watcher\\.worktrees\\foundation",
        sandboxes = listOf("C:\\sensorsdata\\cyber-watcher"),
    )

    @Test
    fun canonicalMatchWinsOverASandboxClaim() {
        val projects = listOf(
            main,
            HomeProjectEdit.ServerProject(id = "prj_other", worktree = "/elsewhere", sandboxes = listOf("/home/u/repo")),
        )
        assertEquals("prj_main", HomeProjectEdit.projectIdFor("/home/u/repo", projects))
    }

    @Test
    fun aSandboxOnlyDirectoryResolvesToTheProjectThatClaimedIt() {
        // The parent of a `.worktrees/<branch>` checkout is registered only in
        // that project's `sandboxes` — exact-canonical matching reads it as
        // unregistered and 「Refresh worktrees」 fails with 「request failed」.
        val projects = listOf(main, checkout)
        assertEquals("prj_wt", HomeProjectEdit.projectIdFor("c:/sensorsdata/cyber-watcher", projects))
        assertEquals(
            "prj_wt",
            HomeProjectEdit.projectIdFor("C:\\sensorsdata\\cyber-watcher\\", projects),
        )
    }

    @Test
    fun theCheckoutItselfMatchesOnCanonical() {
        val projects = listOf(main, checkout)
        assertEquals(
            "prj_wt",
            HomeProjectEdit.projectIdFor(
                "c:/sensorsdata/cyber-watcher/.worktrees/foundation",
                projects,
            ),
        )
    }

    @Test
    fun anUnclaimedPathAndABlankPathResolveToNothing() {
        val projects = listOf(main, checkout)
        assertNull(HomeProjectEdit.projectIdFor("/home/u/nowhere", projects))
        assertNull(HomeProjectEdit.projectIdFor("", projects))
        assertNull(HomeProjectEdit.matchIn("   ", projects))
    }

    @Test
    fun theGlobalPseudoProjectStillHasNoPatchRouteFromASandboxMatch() {
        val global = HomeProjectEdit.ServerProject(
            id = "global",
            worktree = "/home/u/repo",
            sandboxes = listOf("/home/u/repo-extra"),
        )
        assertNull(HomeProjectEdit.projectIdFor("/home/u/repo-extra", listOf(global)))
    }

    @Test
    fun bareListRowsWithoutNameIconCommandsDefaultTheEditFields() {
        // Measured `GET /api/project` row: `{id,canonical,time,sandboxes}` — the
        // display fields are optional (`Project.Info`) and absent until set.
        val raw = JSONObject(
            """{"id":"prj_1","canonical":"/home/u/repo","time":{"created":1,"updated":2},"sandboxes":[]}""",
        )
        val parsed = HomeProjectEdit.originalFromJson("prj_1", "/home/u/repo", raw)
        assertEquals("prj_1", parsed.projectID)
        assertEquals("", parsed.name)
        assertNull(parsed.color)
        assertEquals("", parsed.startup)
        assertEquals("repo", parsed.folderName)
    }

    @Test
    fun faultOfKeepsTheTransportReasonAndFallsBackToTheClassName() {
        assertEquals(
            ProjectFault.Request("HTTP 401 unauthorized"),
            HomeProjectEdit.faultOf(RuntimeException("HTTP 401 unauthorized")),
        )
        assertEquals(
            ProjectFault.Request("IllegalStateException"),
            HomeProjectEdit.faultOf(IllegalStateException()),
        )
        assertEquals(
            ProjectFault.Request("IllegalStateException"),
            HomeProjectEdit.faultOf(IllegalStateException("   ")),
        )
        assertSame(ProjectFault.NoServer, ProjectFault.NoServer)
    }
}
