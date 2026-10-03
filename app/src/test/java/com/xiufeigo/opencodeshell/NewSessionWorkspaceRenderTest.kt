package com.xiufeigo.opencodeshell

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source pins for the new-session workspace picker
 * (`new-session/workspace/selector.tsx` projection in `NewSessionScreen.kt`).
 */
class NewSessionWorkspaceRenderTest {

    private fun screen(): String {
        val relative = "src/main/java/com/xiufeigo/opencodeshell/ui/home/NewSessionScreen.kt"
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText()
    }

    @Test
    fun thePickerOffersLocalAndTheWorktreeListWithUpstreamEmptyCopy() {
        val body = screen()
        assertTrue(body.contains("R.string.oc_new_session_workspace_run_in"))
        assertTrue(body.contains("R.string.oc_new_session_workspace_local"))
        assertTrue(body.contains("R.string.oc_new_session_workspace_search_empty"))
        assertTrue(body.contains("R.string.oc_new_session_workspace_empty"))
    }

    @Test
    fun theSearchFieldFollowsUpstreamsTenRowGate() {
        val body = screen()
        assertTrue(body.contains("NewSessionWorkspaceModel.showsWorktreeSearch(worktrees.size)"))
        assertTrue(body.contains("R.string.oc_new_session_workspace_search_placeholder"))
    }

    @Test
    fun selectionFeedsCreateSessionAndConsumesThePreselectedDirectory() {
        val body = screen()
        assertTrue(body.contains("NewSessionWorkspaceModel.destination(selected, project?.canonical)"))
        assertTrue(body.contains("preselectedDirectory"))
        assertTrue(body.contains("R.string.oc_new_session_create"))
    }

    @Test
    fun workspaceDataIsTheV2ProjectAndWorktreePair() {
        val body = screen()
        assertTrue(body.contains("api.projects()"))
        assertTrue(body.contains("api.worktrees(picked.id)"))
    }
}
