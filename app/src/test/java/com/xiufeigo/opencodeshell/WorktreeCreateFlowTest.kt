package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.workspace.WorktreeCreateOutcome
import com.xiufeigo.opencodeshell.ui.workspace.WorktreeSource
import com.xiufeigo.opencodeshell.ui.workspace.createWorktree
import com.xiufeigo.opencodeshell.ui.workspace.loadWorktreeSource
import com.xiufeigo.opencodeshell.v2.V2Api
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The flow half of 「创建工作树」: `createWorktree` / `loadWorktreeSource`
 * (`ui/workspace/WorktreeCreate.kt`) against [MockWebServer].
 *
 * This is the projection of upstream's `workspaces/create.test.ts` onto this
 * shell's routes: `GET /api/project` replaces `location.get`, then
 * `POST /api/worktree` with `{projectID, from, branch}` (the same body
 * `create.test.ts:62-66` asserts), and the bare `{directory}` answer becomes
 * the created worktree. `UNVERIFIED` live (write probe refused), like
 * [V2ApiMissingVerbsTest].
 */
class WorktreeCreateFlowTest {

    private companion object {
        /** `GET /api/project` — a bare array (`V2Api.projects`). */
        const val PROJECTS =
            """[{"id":"proj_clone","canonical":"/copies/repo","time":{"created":1,"updated":2},"sandboxes":[]}]"""

        /** `Worktree.Info` — bare `{directory}`, **no** `data` envelope (`worktree.ts:36-48`). */
        const val WORKTREE_INFO = """{"directory":"/data/worktree/abcdef/named"}"""

        /** `400 WorktreeError` (`worktree.ts:12-22`). */
        const val WORKTREE_ERROR =
            """{"name":"WorktreeError","data":{"message":"Worktree destination already exists: /data/worktree/abcdef/named","forceRequired":false}}"""

        /** `GET /api/vcs/branch` → `{location,data:[string]}` (measured shape). */
        const val BRANCHES = """{"location":{"directory":"/copies/repo"},"data":["main","feat/x"]}"""

        /** `GET /api/vcs` → `{location,data:{branch:{current,default}}}` (measured shape). */
        const val VCS = """{"location":{"directory":"/copies/repo"},"data":{"branch":{"current":"main","default":"main"}}}"""
    }

    private fun api(server: MockWebServer, directory: String? = null) = V2Api(
        baseHttpUrl = server.url("/").toString().toHttpUrlOrNull()!!,
        http = V2Api.defaultClient(),
        directory = directory,
    )

    // ---------------------------------------------------------------- create

    @Test
    fun theCreatePostsTheProjectsIdItsCanonicalCheckoutAndTheBranch() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(PROJECTS))
            server.enqueue(MockResponse().setResponseCode(200).setBody(WORKTREE_INFO))

            // The session is being drafted inside a subdirectory of the project
            // (`create.test.ts`'s "clone subdirectory" row): containment, not
            // equality, is what names the project.
            val outcome = createWorktree(api(server), "/copies/repo/packages/app", "feat/x")

            val projects = server.takeRequest()
            assertEquals("GET", projects.method)
            assertEquals("/api/project", projects.requestUrl!!.encodedPath)

            val create = server.takeRequest()
            assertEquals("POST", create.method)
            assertEquals("/api/worktree", create.requestUrl!!.encodedPath)
            // No query at all: the project id travels in the body.
            assertNull(create.requestUrl!!.query)
            val body = JSONObject(create.body.readUtf8())
            assertEquals("proj_clone", body.getString("projectID"))
            assertEquals("/copies/repo", body.getString("from"))
            assertEquals("feat/x", body.getString("branch"))
            assertEquals(3, body.length())

            assertEquals(WorktreeCreateOutcome.Created("/data/worktree/abcdef/named"), outcome)
        }
    }

    @Test
    fun anUnpickedBranchOmitsTheKeyInsteadOfSendingAnEmptyString() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(PROJECTS))
            server.enqueue(MockResponse().setResponseCode(200).setBody(WORKTREE_INFO))

            createWorktree(api(server), "/copies/repo", null)

            server.takeRequest()
            val body = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals("proj_clone", body.getString("projectID"))
            assertEquals("/copies/repo", body.getString("from"))
            // `Worktree.CreateInput.branch` is an optional non-empty string.
            assertFalse(body.has("branch"))
        }
    }

    @Test
    fun aFailedCreateKeepsTheServersOwnSentenceForTheDialogsCopy() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(PROJECTS))
            server.enqueue(MockResponse().setResponseCode(400).setBody(WORKTREE_ERROR))

            val outcome = createWorktree(api(server), "/copies/repo", "feat/x")

            assertTrue(outcome is WorktreeCreateOutcome.Failed)
            val message = (outcome as WorktreeCreateOutcome.Failed).message.orEmpty()
            // `V2Api.errorText` folds `name` + `data.message` into one sentence,
            // so the picker's failure line is never a wall of JSON.
            assertTrue(message, message.contains("Worktree destination already exists: /data/worktree/abcdef/named"))
        }
    }

    @Test
    fun anUnreachableCatalogFailsInsteadOfInventingAProject() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setBody("""{"message":"unavailable"}"""))

            val outcome = createWorktree(api(server), "/copies/repo", null)

            assertTrue(outcome is WorktreeCreateOutcome.Failed)
            // Only the catalog read was attempted — no create against a project
            // that was never resolved.
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun anEmptyProjectListIsNoProject() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))

            assertEquals(WorktreeCreateOutcome.NoProject, createWorktree(api(server), "/copies/repo", null))
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun aDirectorylessAnswerIsAFailedCreate() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(PROJECTS))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"directory":""}"""))

            assertEquals(
                WorktreeCreateOutcome.Failed(null),
                createWorktree(api(server), "/copies/repo", null),
            )
        }
    }

    // ---------------------------------------------------------------- source

    @Test
    fun theSourceReadIsTheProjectItsCanonicalBranchesAndTheirCurrent() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(PROJECTS))
            server.enqueue(MockResponse().setResponseCode(200).setBody(BRANCHES))
            server.enqueue(MockResponse().setResponseCode(200).setBody(VCS))

            val source = loadWorktreeSource(api(server), "/copies/repo/packages/app")

            assertEquals(
                WorktreeSource.Ready(
                    projectID = "proj_clone",
                    canonical = "/copies/repo",
                    branches = listOf("main", "feat/x"),
                    current = "main",
                ),
                source,
            )

            server.takeRequest()
            val branches = server.takeRequest()
            assertEquals("/api/vcs/branch", branches.requestUrl!!.encodedPath)
            // `controller.ts:150-157`: the list is scoped to the project root
            // (`location[directory]`) and capped at 50.
            assertEquals("/copies/repo", branches.requestUrl!!.queryParameter("location[directory]"))
            assertEquals("50", branches.requestUrl!!.queryParameter("limit"))
            assertNull(branches.requestUrl!!.queryParameter("search"))

            val vcs = server.takeRequest()
            assertEquals("/api/vcs", vcs.requestUrl!!.encodedPath)
            assertEquals("/copies/repo", vcs.requestUrl!!.queryParameter("location[directory]"))
        }
    }

    @Test
    fun anUnreadableBranchListFailsThePickerInsteadOfShowingNothing() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(PROJECTS))
            server.enqueue(MockResponse().setResponseCode(500).setBody("""{"message":"boom"}"""))

            assertTrue(loadWorktreeSource(api(server), "/copies/repo") is WorktreeSource.Failed)
        }
    }

    @Test
    fun aProjectlessServerReportsNoProjectRatherThanAnEmptyPicker() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))

            assertEquals(WorktreeSource.NoProject, loadWorktreeSource(api(server), "/copies/repo"))
        }
    }

    @Test
    fun anUnreadableVcsOnlyCostsTheCurrentBranch() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(PROJECTS))
            server.enqueue(MockResponse().setResponseCode(200).setBody(BRANCHES))
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"_tag":"InvalidRequestError"}"""))

            val source = loadWorktreeSource(api(server), "/copies/repo")

            assertEquals(
                WorktreeSource.Ready("proj_clone", "/copies/repo", listOf("main", "feat/x"), null),
                source,
            )
        }
    }
}
