package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.workspace.WorktreeCreateFailure
import com.xiufeigo.opencodeshell.ui.workspace.WorktreeCreateModel
import com.xiufeigo.opencodeshell.ui.workspace.WorktreeCreatePolicy
import com.xiufeigo.opencodeshell.ui.workspace.WorktreeCreateState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of 「创建工作树」 (`ui/workspace/WorktreeCreate.kt`) — the rules
 * `workspaces/create.ts` and `new-session/workspace/controller.ts` state:
 * the request shape, the branch picker's list, the lifecycle the dialogs
 * render, and the failure copy.
 */
class WorktreeCreateModelTest {

    // ------------------------------------------------------------- request

    @Test
    fun theRequestIsTheProjectsIdItsCanonicalCheckoutAndThePickedBranch() {
        // `create.ts:12-16` + upstream's own assertion (`create.test.ts:62-66`):
        // `{projectID: project.id, from: project.canonical, branch}`.
        val request = WorktreeCreateModel.request(
            projectID = "proj_clone",
            canonical = "/copies/repo",
            branch = "clone-only",
        )
        assertEquals("proj_clone", request.projectID)
        assertEquals("/copies/repo", request.from)
        assertEquals("clone-only", request.branch)
    }

    @Test
    fun aBlankBranchAndCanonicalAreOmittedInsteadOfSentEmpty() {
        // `Worktree.CreateInput.branch` is an optional **non-empty** string: an
        // explicit `""` is a 400, so both halves of the pair collapse to null.
        val blank = WorktreeCreateModel.request("p1", "   ", "")
        assertNull(blank.from)
        assertNull(blank.branch)

        val untrimmed = WorktreeCreateModel.request("p1", " /repo ", "  feat/x  ")
        assertEquals("/repo", untrimmed.from)
        assertEquals("feat/x", untrimmed.branch)
    }

    // -------------------------------------------------------------- branches

    @Test
    fun theBranchListIsTheServersPlusTheCurrentOneDedupedAndCapped() {
        // `controller.ts:234-243`: the loaded list (only when it was read for
        // this project root), then the checkout's own branch when it matches the
        // search, `new Set`-deduped, `.slice(0, 50)`.
        val merged = WorktreeCreateModel.branches(
            loaded = listOf("main", "feat/x"),
            loadedFor = "/repo",
            projectRoot = "/repo",
            current = "feat/x",
            search = "",
        )
        assertEquals(listOf("main", "feat/x"), merged)

        val withCurrent = WorktreeCreateModel.branches(
            loaded = listOf("main"),
            loadedFor = "/repo",
            projectRoot = "/repo",
            current = "detached-work",
            search = "",
        )
        assertEquals(listOf("main", "detached-work"), withCurrent)
    }

    @Test
    fun theSearchFiltersBothHalvesCaseInsensitively() {
        val shown = WorktreeCreateModel.branches(
            loaded = listOf("main", "feat/Login", "release/2"),
            loadedFor = "/repo",
            projectRoot = "/repo",
            current = "FEAT/current",
            search = "feat",
        )
        // The server's matches first, then the checkout's own branch — which the
        // server's own list may not carry yet (`controller.ts:239-241`).
        assertEquals(listOf("feat/Login", "FEAT/current"), shown)

        assertTrue(
            WorktreeCreateModel.branches(
                loaded = listOf("main"),
                loadedFor = "/repo",
                projectRoot = "/repo",
                current = "main",
                search = "nothing",
            ).isEmpty(),
        )
    }

    @Test
    fun aListReadForAnotherProjectRootIsNotUsed() {
        // `loaded?.directory === projectRoot() ? loaded.data : []` — the stale
        // read of the previously selected project must not leak into this one.
        assertTrue(
            WorktreeCreateModel.branches(
                loaded = listOf("other/main"),
                loadedFor = "/elsewhere",
                projectRoot = "/repo",
                current = null,
                search = "",
            ).isEmpty(),
        )
    }

    @Test
    fun theCapIsUpstreamsFifty() {
        val many = (1..80).map { "b$it" }
        val capped = WorktreeCreateModel.branches(
            loaded = many,
            loadedFor = "/repo",
            projectRoot = "/repo",
            current = null,
            search = "",
        )
        assertEquals(WorktreeCreateModel.BRANCH_LIMIT, capped.size)
        assertEquals(50, WorktreeCreateModel.BRANCH_LIMIT)
    }

    // ------------------------------------------------------------- lifecycle

    @Test
    fun oneActivationGoesIdleToPreparing() {
        assertEquals(WorktreeCreateState.Preparing, WorktreeCreateModel.start())
    }

    @Test
    fun anAnsweredCreateWithADirectoryLandsOnCreated() {
        val settled = WorktreeCreateModel.settle("/data/worktree/abcdef/named")
        assertEquals(
            WorktreeCreateState.Created("/data/worktree/abcdef/named"),
            settled,
        )
    }

    @Test
    fun anAnsweredCreateWithoutADirectoryIsAFailureNotAnEmptySelection() {
        // A session must never be drafted in a directory the server did not
        // name — an empty `Worktree.Info.directory` is a failed create.
        val settled = WorktreeCreateModel.settle("  ")
        assertTrue(settled is WorktreeCreateState.Failed)
        assertEquals(
            WorktreeCreateFailure.REQUEST_FAILED,
            (settled as WorktreeCreateState.Failed).reason,
        )
        assertNull(settled.message)
    }

    @Test
    fun aFailedCreateKeepsItsReasonAndTheServersOwnSentence() {
        val failed = WorktreeCreateModel.fail(
            WorktreeCreateFailure.REQUEST_FAILED,
            "HTTP 400 WorktreeError: Worktree destination already exists: /w",
        )
        assertEquals(
            WorktreeCreateState.Failed(
                WorktreeCreateFailure.REQUEST_FAILED,
                "HTTP 400 WorktreeError: Worktree destination already exists: /w",
            ),
            failed,
        )
        assertEquals(
            WorktreeCreateState.Failed(WorktreeCreateFailure.NO_PROJECT, null),
            WorktreeCreateModel.fail(WorktreeCreateFailure.NO_PROJECT, null),
        )
    }

    // ---------------------------------------------------------------- copy

    @Test
    fun theFailureDetailIsTheServersMessageElseRequestFailed() {
        // `errorMessage` (`composer-adapter.ts:218-229`): the error's own
        // sentence, then `common.requestFailed`.
        assertEquals(
            "Worktree destination already exists: /w",
            WorktreeCreateModel.failureDetail("Worktree destination already exists: /w", "Request failed"),
        )
        assertEquals("Request failed", WorktreeCreateModel.failureDetail(null, "Request failed"))
        assertEquals("Request failed", WorktreeCreateModel.failureDetail("   ", "Request failed"))
    }

    @Test
    fun thePreparingHintAppearsOnlyAfterTheBound() {
        // `session.new.worktree.creating` is up for the whole create; the
        // `workspace.error.stillPreparing` line joins it only past the bound.
        assertFalse(WorktreeCreatePolicy.stillPreparing(0L))
        assertFalse(WorktreeCreatePolicy.stillPreparing(WorktreeCreatePolicy.SLOW_MS - 1))
        assertTrue(WorktreeCreatePolicy.stillPreparing(WorktreeCreatePolicy.SLOW_MS))
        assertTrue(WorktreeCreatePolicy.stillPreparing(60_000L))
    }
}
