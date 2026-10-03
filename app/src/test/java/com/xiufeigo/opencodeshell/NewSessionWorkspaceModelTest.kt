package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.home.NewSessionWorkspaceModel
import com.xiufeigo.opencodeshell.ui.home.NewSessionWorkspaceModel.ProjectRef
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pure rules of the new-session workspace picker
 * (`new-session/workspace/selector.tsx` + `controller.ts` at the v2 head).
 */
class NewSessionWorkspaceModelTest {

    @Test
    fun localSelectionCoversSentinelRootAndEmpty() {
        assertTrue(NewSessionWorkspaceModel.isLocal(null, "/work/repo"))
        assertTrue(NewSessionWorkspaceModel.isLocal("", "/work/repo"))
        assertTrue(NewSessionWorkspaceModel.isLocal(NewSessionWorkspaceModel.LOCAL, "/work/repo"))
        assertTrue(NewSessionWorkspaceModel.isLocal("/work/repo", "/work/repo"))
        // Trailing separators and Windows casing must not defeat the match.
        assertTrue(NewSessionWorkspaceModel.isLocal("C:\\Work\\Repo\\", "c:/work/repo"))
        assertFalse(NewSessionWorkspaceModel.isLocal("/work/repo/.worktrees/feat", "/work/repo"))
    }

    @Test
    fun sameDirectoryAndContainsDirectoryNormalizeSeparators() {
        assertTrue(NewSessionWorkspaceModel.sameDirectory("C:\\work\\repo", "c:/work/repo/"))
        assertFalse(NewSessionWorkspaceModel.sameDirectory(null, "/x"))
        assertTrue(NewSessionWorkspaceModel.containsDirectory("/work/repo", "/work/repo/.worktrees/x"))
        assertTrue(NewSessionWorkspaceModel.containsDirectory("/work/repo/", "/work/repo"))
        // A sibling with a shared prefix must not count as contained.
        assertFalse(NewSessionWorkspaceModel.containsDirectory("/work/repo", "/work/repo-other"))
    }

    @Test
    fun worktreeNameIsTheLastPathSegment() {
        assertEquals("feat", NewSessionWorkspaceModel.worktreeName("/work/repo/.worktrees/feat"))
        assertEquals("feat", NewSessionWorkspaceModel.worktreeName("C:\\work\\repo\\.worktrees\\feat\\"))
        assertEquals("feat", NewSessionWorkspaceModel.worktreeName("feat"))
    }

    @Test
    fun filterWorktreesMatchesFilenameCaseInsensitively() {
        val rows = listOf("/w/Alpha", "/w/beta", "/w/gamma")
        assertEquals(listOf("/w/Alpha"), NewSessionWorkspaceModel.filterWorktrees(rows, "alp"))
        assertEquals(rows, NewSessionWorkspaceModel.filterWorktrees(rows, ""))
        assertTrue(NewSessionWorkspaceModel.filterWorktrees(rows, "zzz").isEmpty())
    }

    @Test
    fun worktreeSearchMountsAtTenRowsLikeUpstream() {
        assertFalse(NewSessionWorkspaceModel.showsWorktreeSearch(9))
        assertTrue(NewSessionWorkspaceModel.showsWorktreeSearch(10))
        assertTrue(NewSessionWorkspaceModel.showsWorktreeSearch(11))
    }

    @Test
    fun worktreeDirectoriesReadsRowKeysTolerantlyAndDropsTheProjectRoot() {
        val rows = listOf(
            JSONObject("""{"directory":"/w/repo/.worktrees/a"}"""),
            JSONObject("""{"worktree":"/w/repo/.worktrees/b"}"""),
            JSONObject("""{"path":"/w/repo"}"""),
            JSONObject("""{"name":"no-path"}"""),
        )
        val dirs = NewSessionWorkspaceModel.worktreeDirectories(
            rows = rows,
            projectRoot = "/w/repo",
            sandboxes = listOf("/w/repo/.sandboxes/s1", "/w/repo/.worktrees/a"),
        )
        assertEquals(listOf("/w/repo/.worktrees/a", "/w/repo/.worktrees/b", "/w/repo/.sandboxes/s1"), dirs)
    }

    @Test
    fun pickProjectTakesTheNamedDirectoryOrTheServerCwdOnly() {
        val projects = listOf(
            ProjectRef("p1", "/work/one", timeUpdated = 5),
            ProjectRef("p2", "/work/two", timeUpdated = 9),
        )
        // The caller's directory wins, even one level below the root.
        assertEquals("p1", NewSessionWorkspaceModel.pickProject(projects, "/work/one/src")?.id)
        // Nothing named: the server's own cwd decides — never the newest project on
        // a shared server, which is another device's (v58, 「Untitled E2ECHK5560_proj」).
        assertEquals("p2", NewSessionWorkspaceModel.pickProject(projects, null, "/work/two/pkg")?.id)
        assertEquals("p1", NewSessionWorkspaceModel.pickProject(projects, null, "/work/one")?.id)
        assertNull(NewSessionWorkspaceModel.pickProject(projects, null))
        assertNull(NewSessionWorkspaceModel.pickProject(projects, null, ""))
        // A named directory no project owns: no project is invented for it (the
        // create still uses the directory itself through `destination`).
        assertNull(NewSessionWorkspaceModel.pickProject(projects, "/elsewhere"))
        assertNull(NewSessionWorkspaceModel.pickProject(emptyList(), null))
    }

    @Test
    fun pickProjectResolvesTheCwdBeforeAnyTimeOrdering() {
        // The old fallback (`maxByOrNull { timeUpdated }`) is the regression this
        // pins: with the server cwd unreadable, no project may be adopted at all,
        // even when the catalogue has a newest row.
        val projects = listOf(
            ProjectRef("other", "/work/other-device", timeUpdated = 99),
            ProjectRef("mine", "/work/mine", timeUpdated = 1),
        )
        assertEquals("mine", NewSessionWorkspaceModel.pickProject(projects, null, "/work/mine/sub")?.id)
        assertNull(NewSessionWorkspaceModel.pickProject(projects, null, null))
        assertNull(
            "a cwd outside every project picks none of them",
            NewSessionWorkspaceModel.pickProject(projects, null, "/srv/elsewhere"),
        )
    }

    @Test
    fun theDraftPageFeedsTheServerCwdIntoTheProjectPick() {
        // Without the cwd the pick would read "no project" on every entry, and the
        // tempting fix (the newest project) is exactly the v58 bug.
        val screen = listOf(
            File("src/main/java/com/xiufeigo/opencodeshell/ui/home/NewSessionScreen.kt"),
            File("app/src/main/java/com/xiufeigo/opencodeshell/ui/home/NewSessionScreen.kt"),
        ).firstOrNull { it.isFile }?.readText()?.replace(Regex("\\s+"), " ")
            ?: error("missing NewSessionScreen.kt")
        assertTrue(
            "the project pick must be handed the server cwd",
            screen.contains("pickProject(projects, preselectedDirectory, cwd)"),
        )
        assertFalse(
            "no project may be adopted by time ordering alone",
            screen.contains("timeUpdated }"),
        )
    }

    @Test
    fun destinationStillCarriesAnUnownedPreselectedDirectory() {        // With no project card, the requested directory is still the destination —
        // picking no project must not silently move the session to the server cwd.
        assertEquals("/elsewhere", NewSessionWorkspaceModel.destination("/elsewhere", null))
        assertNull(NewSessionWorkspaceModel.destination(null, null))
    }

    @Test
    fun destinationMapsLocalToTheProjectRootAndWorktreesToTheirPath() {
        assertEquals("/w/repo", NewSessionWorkspaceModel.destination(null, "/w/repo"))
        assertEquals("/w/repo", NewSessionWorkspaceModel.destination(NewSessionWorkspaceModel.LOCAL, "/w/repo"))
        assertEquals("/w/repo/.worktrees/a", NewSessionWorkspaceModel.destination("/w/repo/.worktrees/a", "/w/repo"))
        // No project known: local keeps the pre-picker server-cwd behaviour.
        assertNull(NewSessionWorkspaceModel.destination(null, null))
    }
}
