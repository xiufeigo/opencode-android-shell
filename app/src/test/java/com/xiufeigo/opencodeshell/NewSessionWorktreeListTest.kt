package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.home.NewSessionWorkspaceModel
import com.xiufeigo.opencodeshell.ui.workspace.WorktreeRemoveOutcome
import com.xiufeigo.opencodeshell.ui.workspace.removeWorktree
import com.xiufeigo.opencodeshell.ui.workspace.worktreeForceRequired
import com.xiufeigo.opencodeshell.v2.V2Api
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v67 (BUG-6): after `POST /api/worktree` created `glowing-mountain`, the
 * new-session worktree list showed only the older `tidy-orchid` — and Refresh
 * did not add it either — while the list carried no delete entry at all.
 *
 * Two rules, both pure half of the fix: the list **merges** the worktrees this
 * device created behind the server's rows, and a deleted worktree is dropped
 * from the list. [removeWorktree] is the write itself
 * (`DELETE /api/worktree`), including the dirty-checkout retry.
 *
 * v68: the same symptom with a second half — the create picked no branch, so
 * the record was never written ("no branch, no record"), and the server cwd
 * sat inside the fresh data-dir worktree, so no project owned the directory
 * and the whole read was dropped. The records now admit branch-less creates,
 * surface even when no project is picked, and a create invalidates the
 * read; a delete reconciles the server inventory behind it.
 */
class NewSessionWorktreeListTest {

    private val root = "/copies/repo"
    private val tidy = "/data/worktree/abc123/tidy-orchid"
    private val fresh = "/data/worktree/abc123/glowing-mountain"

    private fun row(directory: String) = JSONObject().put("directory", directory)

    // ------------------------------------------------------------------ list

    @Test
    fun aWorktreeTheCreateJustMadeIsInTheListEvenWhenTheServerOmitsIt() {
        val listed = NewSessionWorkspaceModel.worktreeDirectories(
            rows = listOf(row(tidy)),
            projectRoot = root,
            created = listOf(fresh),
        )
        assertEquals(listOf(tidy, fresh), listed)
    }

    @Test
    fun theMergeNeverDuplicatesWhatTheServerAlreadyAnswers() {
        val listed = NewSessionWorkspaceModel.worktreeDirectories(
            rows = listOf(row(tidy), row(fresh)),
            projectRoot = root,
            created = listOf(fresh),
        )
        assertEquals(listOf(tidy, fresh), listed)
    }

    @Test
    fun theProjectRootStaysOutOfItsOwnWorktreeList() {
        val listed = NewSessionWorkspaceModel.worktreeDirectories(
            rows = listOf(row(root), row(tidy)),
            projectRoot = root,
            created = listOf("$root/", fresh),
        )
        assertEquals(listOf(tidy, fresh), listed)
    }

    @Test
    fun sandboxesAreStillPartOfTheList() {
        val listed = NewSessionWorkspaceModel.worktreeDirectories(
            rows = listOf(row(tidy)),
            projectRoot = root,
            sandboxes = listOf("/data/sandbox/one"),
            created = listOf(fresh),
        )
        assertEquals(listOf(tidy, "/data/sandbox/one", fresh), listed)
    }

    // ---------------------------------------------------------------- delete

    @Test
    fun aRecordUnderAnotherProjectIsNotMergedIntoThisProjectsList() {
        val created = listOf(fresh, "/copies/other-repo/.worktree/x")
        val mine = NewSessionWorkspaceModel.worktreeRecordsForProject(
            created = created,
            projectRoot = root,
            projectRoots = listOf(root, "/copies/other-repo"),
        )
        assertEquals(listOf(fresh), mine)
    }

    @Test
    fun withoutAKnownProjectEveryRecordStays() {
        val created = listOf(fresh)
        assertEquals(
            created,
            NewSessionWorkspaceModel.worktreeRecordsForProject(created, projectRoot = null),
        )
    }

    @Test
    fun recordsSurfaceWhenNoProjectOwnsTheDirectory() {
        // v68: the server cwd was the fresh data-dir worktree — contained by
        // no checkout — so the read used to be dropped entirely. The records
        // are the list then.
        val listed = NewSessionWorkspaceModel.worktreeDirectories(
            rows = emptyList(),
            projectRoot = null,
            created = listOf(fresh),
        )
        assertEquals(listOf(fresh), listed)
    }

    @Test
    fun aDeletedWorktreeLeavesTheList() {
        val listed = NewSessionWorkspaceModel.withoutWorktree(listOf(tidy, fresh), fresh)
        assertEquals(listOf(tidy), listed)
    }

    @Test
    fun aDeletedWorktreeIsGoneWhicheverSpellingNamedIt() {
        val listed = NewSessionWorkspaceModel.withoutWorktree(
            listOf(tidy, "/data/worktree/abc123/glowing-mountain/"),
            fresh,
        )
        assertEquals(listOf(tidy), listed)
    }

    // ----------------------------------------------------------------- write

    /**
     * The delete resolves the owning project before it writes (v69), so every
     * test here enqueues the one `GET /api/worktree?projectID=proj_clone` that
     * read answers first; `worktreeOwnerProjectID`'s own order is pinned by
     * `WorktreeDeleteOwnerV69Test`.
     */
    private fun ownedInventory(directory: String) = MockResponse().setBody(
        JSONArray().put(row(directory)).toString(),
    )

    @Test
    fun theDeleteSendsTheProjectIdDirectoryAndNoForce() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ownedInventory(fresh))
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setResponseCode(204))
            val outcome = removeWorktree(api(server), "proj_clone", fresh)

            server.takeRequest()
            val request = server.takeRequest()
            assertEquals("DELETE", request.method)
            assertEquals("/api/worktree", request.requestUrl!!.encodedPath)
            val body = JSONObject(request.body.readUtf8())
            assertEquals("proj_clone", body.getString("projectID"))
            assertEquals(fresh, body.getString("directory"))
            assertFalse(body.getBoolean("force"))
            assertEquals(WorktreeRemoveOutcome.Removed(fresh), outcome)
            // v68: the delete reconciles the saved inventory behind it, so a
            // directory removed outside the API stops answering on re-read.
            assertEquals(3, server.requestCount)
        }
    }

    @Test
    fun aDeleteReconcilesTheServerInventoryBehindIt() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ownedInventory(fresh))
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setResponseCode(204))
            val outcome = removeWorktree(api(server), "proj_clone", fresh)

            assertEquals(WorktreeRemoveOutcome.Removed(fresh), outcome)
            server.takeRequest()
            server.takeRequest()
            val refresh = server.takeRequest()
            assertEquals("POST", refresh.method)
            assertEquals("/api/worktree/refresh", refresh.requestUrl!!.encodedPath)
            assertEquals("proj_clone", JSONObject(refresh.body.readUtf8()).getString("projectID"))
        }
    }

    @Test
    fun aFailedReconcileStillReportsRemoved() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ownedInventory(fresh))
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setResponseCode(500))
            val outcome = removeWorktree(api(server), "proj_clone", fresh)

            assertEquals(WorktreeRemoveOutcome.Removed(fresh), outcome)
            assertEquals(3, server.requestCount)
        }
    }

    @Test
    fun aDirtyWorktreeIsRetriedWithForceAndOnlyThenRemoved() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ownedInventory(fresh))
            server.enqueue(
                MockResponse().setResponseCode(400).setBody(
                    """{"name":"WorktreeError","data":{"message":"Worktree has uncommitted changes","forceRequired":true}}""",
                ),
            )
            server.enqueue(MockResponse().setResponseCode(204))
            val outcome = removeWorktree(api(server), "proj_clone", fresh)

            server.takeRequest()
            server.takeRequest()
            val forced = JSONObject(server.takeRequest().body.readUtf8())
            assertTrue(forced.getBoolean("force"))
            assertEquals(WorktreeRemoveOutcome.Removed(fresh), outcome)
        }
    }

    @Test
    fun aTransportFailureIsNotAForceRequest() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ownedInventory(fresh))
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"name":"WorktreeError","data":{"message":"nope"}}"""))
            val outcome = removeWorktree(api(server), "proj_clone", fresh)

            assertTrue(outcome is WorktreeRemoveOutcome.Failed)
            // The owning read, then one delete only: `forceRequired` was
            // absent, so the delete is not re-sent with `force = true` over an
            // uncommitted checkout.
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun forceRequiredIsReadFromBothErrorShapes() {
        assertTrue(worktreeForceRequired("""{"data":{"forceRequired":true}}"""))
        assertTrue(worktreeForceRequired("""{"name":"WorktreeError","data":{"forceRequired":true}}"""))
        assertFalse(worktreeForceRequired("""{"data":{"forceRequired":false}}"""))
        assertFalse(worktreeForceRequired(null))
        assertFalse(worktreeForceRequired("not json"))
    }

    private fun api(server: MockWebServer) = V2Api(
        baseHttpUrl = server.url("/").toString().toHttpUrlOrNull()!!,
        http = V2Api.defaultClient(),
        directory = null,
    )
}
