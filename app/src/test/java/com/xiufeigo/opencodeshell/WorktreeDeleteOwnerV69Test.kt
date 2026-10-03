package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.workspace.WorktreeRemoveModel
import com.xiufeigo.opencodeshell.ui.workspace.WorktreeRemoveOutcome
import com.xiufeigo.opencodeshell.ui.workspace.removeWorktree
import com.xiufeigo.opencodeshell.ui.workspace.worktreeOwnerProjectID
import com.xiufeigo.opencodeshell.v2.V2Api
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v69: deleting a worktree from the new-session list never reached the server,
 * and the dialog answered the refusal with the row's own path
 * (`Couldn't delete the worktree: /…/264884/lucky-planet` — `research/
 * v69_verify/5556/94_correct_delete.png`), so neither the delete nor a reason
 * was ever on screen.
 *
 * The delete was sent under the **draft's** project id. That id cannot be
 * trusted: the list merges the picked project's server rows with this device's
 * create records, and a draft parked in a worktree owns no project at all
 * (no project's checkout contains a worktree), so the screen bailed out
 * before the request. `DELETE /api/worktree` looks the row up by
 * `projectID` + `directory` (`core/src/worktree.ts:259-262`), so the id that
 * travels must be the one the row belongs to.
 *
 * Pinned here: the owning project is resolved before the write, a row the
 * server does not hold is never deleted, the retry still re-sends only
 * `forceRequired`, the server's own sentence survives, and the shell's own
 * line is used when the server sent none.
 */
class WorktreeDeleteOwnerV69Test {

    private val home = "/home/xiufeigo"
    private val lucky = "/home/xiufeigo/.local/share/opencode/worktree/264884/lucky-planet"
    private val calm = "/home/xiufeigo/.local/share/opencode/worktree/ae5407/calm-eagle"
    private val homeProject = "264884bb26d35255a58501108794dbfde2777686"
    private val shellProject = "ae54078c0e09bf8e7cbeefd35db19aaa6b064e46"
    private val junkProject = "f3a60d014135ef8f42ccd5814e75867d76742496"

    private fun api(server: MockWebServer) = V2Api(
        baseHttpUrl = server.url("/").toString().toHttpUrlOrNull()!!,
        http = V2Api.defaultClient(),
        directory = null,
    )

    private fun catalog(vararg entries: Pair<String, String>) =
        JSONArray(entries.map { JSONObject().put("id", it.first).put("canonical", it.second) }).toString()

    private fun projects() = catalog(
        homeProject to home,
        shellProject to "/mnt/c/sensorsdata/main/program/opencode-android-shell",
    )

    private fun inventory(vararg directories: String) =
        JSONArray(directories.map { JSONObject().put("directory", it) }).toString()

    private fun owned(directories: List<String>) = MockResponse().setBody(
        inventory(home, *directories.toTypedArray()),
    )

    // ------------------------------------------------- the row decides the id

    @Test
    fun aRowFromAnotherProjectIsDeletedUnderTheProjectThatHoldsIt() = runBlocking {
        MockWebServer().use { server ->
            // The hint the caller passes is the draft's project, and the draft
            // is parked in the `ae5407` worktree — a project whose checkout
            // does not contain it.
            server.enqueue(owned(listOf(calm)))
            server.enqueue(MockResponse().setBody(projects()))
            server.enqueue(owned(listOf(lucky)))
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setResponseCode(204))

            val outcome = removeWorktree(api(server), shellProject, lucky)

            repeat(3) { server.takeRequest() }
            val delete = server.takeRequest()
            assertEquals("DELETE", delete.method)
            val body = JSONObject(delete.body.readUtf8())
            assertEquals(homeProject, body.getString("projectID"))
            assertEquals(lucky, body.getString("directory"))
            assertEquals(WorktreeRemoveOutcome.Removed(lucky), outcome)
        }
    }

    @Test
    fun theIdTravelsEvenWhenTheCallerNamesNoProjectAtAll() = runBlocking {
        MockWebServer().use { server ->
            // v69's exact shape: the draft owned no project, so the screen used
            // to answer with the path instead of sending anything.
            server.enqueue(MockResponse().setBody(projects()))
            server.enqueue(owned(listOf(lucky)))
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setResponseCode(204))

            val outcome = removeWorktree(api(server), null, lucky)

            server.takeRequest()
            server.takeRequest()
            val body = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals(homeProject, body.getString("projectID"))
            assertEquals(WorktreeRemoveOutcome.Removed(lucky), outcome)
        }
    }

    @Test
    fun aCorrectHintCostsOneReadAndNoWalk() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(owned(listOf(lucky)))
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setResponseCode(204))

            assertEquals(WorktreeRemoveOutcome.Removed(lucky), removeWorktree(api(server), homeProject, lucky))

            val read = server.takeRequest()
            assertEquals("GET", read.method)
            assertEquals("/api/worktree", read.requestUrl!!.encodedPath)
            assertEquals(homeProject, read.requestUrl!!.queryParameter("projectID"))
            assertEquals("DELETE", server.takeRequest().method)
            // 1 read + the delete + the reconcile, and no catalog read.
            assertEquals(3, server.requestCount)
        }
    }

    // ------------------------------------------------------ nothing to delete

    @Test
    fun aRowNoProjectHoldsIsNeverDeleted() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(owned(listOf(calm)))
            server.enqueue(MockResponse().setBody(projects()))
            server.enqueue(owned(listOf(calm)))

            val outcome = removeWorktree(api(server), homeProject, lucky)

            assertEquals(WorktreeRemoveOutcome.NoProject, outcome)
            // The walk read every inventory it was told to and wrote nothing.
            assertEquals(3, server.requestCount)
            repeat(3) { assertFalse(server.takeRequest().method == "DELETE") }
        }
    }

    @Test
    fun aProjectTheServerWillNotEnumerateDoesNotStopTheWalk() = runBlocking {
        MockWebServer().use { server ->
            // v69's server was unhealthy between rounds: one project's
            // inventory answering 500 must not read as "no owner exists".
            server.enqueue(owned(listOf(calm)))
            server.enqueue(
                MockResponse().setBody(
                    catalog(
                        junkProject to "/nope_",
                        homeProject to home,
                        shellProject to "/mnt/c/sensorsdata/main/program/opencode-android-shell",
                    ),
                ),
            )
            server.enqueue(MockResponse().setResponseCode(500))
            server.enqueue(owned(listOf(lucky)))
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setResponseCode(204))

            val outcome = removeWorktree(api(server), shellProject, lucky)

            assertEquals(WorktreeRemoveOutcome.Removed(lucky), outcome)
            repeat(4) { server.takeRequest() }
            assertEquals(homeProject, JSONObject(server.takeRequest().body.readUtf8()).getString("projectID"))
        }
    }

    @Test
    fun theResolverAloneNamesTheOwnerWithoutWriting() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(projects()))
            server.enqueue(owned(listOf(lucky)))
            server.enqueue(owned(listOf(calm)))

            assertEquals(shellProject, worktreeOwnerProjectID(api(server), calm))
            // 1 catalog + one inventory per project walked, and no write.
            assertEquals(3, server.requestCount)
        }
    }

    // ------------------------------------------------------- force and reason

    @Test
    fun aDirtyRowIsStillRetriedWithForceUnderTheOwningProject() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(owned(listOf(lucky)))
            server.enqueue(
                MockResponse().setResponseCode(400).setBody(
                    """{"name":"WorktreeError","data":{"message":"fatal: contains modified or untracked files, use --force to delete it","forceRequired":true}}""",
                ),
            )
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setResponseCode(204))

            val outcome = removeWorktree(api(server), homeProject, lucky)

            server.takeRequest()
            assertFalse(JSONObject(server.takeRequest().body.readUtf8()).getBoolean("force"))
            val forced = JSONObject(server.takeRequest().body.readUtf8())
            assertTrue(forced.getBoolean("force"))
            assertEquals(homeProject, forced.getString("projectID"))
            assertEquals(WorktreeRemoveOutcome.Removed(lucky), outcome)
        }
    }

    @Test
    fun aRefusalCarriesTheServersOwnReason() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(owned(listOf(lucky)))
            server.enqueue(
                MockResponse().setResponseCode(400).setBody(
                    """{"name":"WorktreeError","data":{"message":"Worktree directory unavailable: $lucky"}}""",
                ),
            )

            val failed = removeWorktree(api(server), homeProject, lucky) as WorktreeRemoveOutcome.Failed

            assertTrue(failed.message!!.contains("Worktree directory unavailable"))
            // The screen renders exactly this, not the row's path.
            assertEquals(
                failed.message,
                WorktreeRemoveModel.detail(failed, noProject = "no-project", requestFailed = "Request failed"),
            )
        }
    }

    @Test
    fun aFailureWithNoSentenceFallsBackToTheShellsOwnLine() {
        assertEquals(
            "Request failed",
            WorktreeRemoveModel.detail(
                WorktreeRemoveOutcome.Failed(null),
                noProject = "no-project",
                requestFailed = "Request failed",
            ),
        )
        assertEquals(
            "Request failed",
            WorktreeRemoveModel.detail(
                WorktreeRemoveOutcome.Failed("   "),
                noProject = "no-project",
                requestFailed = "Request failed",
            ),
        )
    }

    @Test
    fun anUnsendableDeleteNamesTheUnownedRowInsteadOfItsPath() {
        assertEquals(
            "no-project",
            WorktreeRemoveModel.detail(
                WorktreeRemoveOutcome.NoProject,
                noProject = "no-project",
                requestFailed = "Request failed",
            ),
        )
        assertNull(
            WorktreeRemoveModel.detail(
                WorktreeRemoveOutcome.Removed(lucky),
                noProject = "no-project",
                requestFailed = "Request failed",
            ),
        )
    }

    // ------------------------------------------------------------ the screen

    @Test
    fun theConfirmNeverAnswersWithTheRowsOwnPath() {
        val body = listOf(File("src"), File("app/src"))
            .map { File(it, "main/java/com/xiufeigo/opencodeshell/ui/home/NewSessionScreen.kt") }
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing NewSessionScreen.kt")
        // v69 set `worktreeError = target` on both refusal branches, so the
        // dialog's own line and the argument were the same path twice.
        assertFalse(body.contains("worktreeError = target"))
        assertFalse(body.contains("result.message ?: target"))
        assertTrue(body.contains("WorktreeRemoveModel.detail(result"))
        // The draft's project is now only a hint, never a precondition.
        assertTrue(body.contains("removeWorktree(serverStore, project?.id, target)"))
    }

    @Test
    fun theUnownedReasonIsTranslatedInEveryLocale() {
        val key = "oc_new_session_worktree_delete_no_project"
        val english = stringValue("values", key)
        assertTrue("values/$key is empty", english.isNotBlank())
        for (locale in listOf("values-zh-rCN", "values-zh-rTW")) {
            val translated = stringValue(locale, key)
            assertTrue("$locale/$key is empty", translated.isNotBlank())
            assertFalse("$locale/$key is still the English copy", english == translated)
        }
    }

    private fun stringValue(locale: String, key: String): String {
        val file = listOf(File("src"), File("app/src"))
            .map { File(it, "main/res/$locale/strings.xml") }
            .firstOrNull { it.isFile }
            ?: error("missing $locale/strings.xml")
        return Regex("""<string name="$key">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(file.readText())
            ?.groupValues
            ?.get(1)
            ?: error("$locale missing $key")
    }
}
