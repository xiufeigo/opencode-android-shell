package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.credentialRemove
import com.xiufeigo.opencodeshell.v2.formCancel
import com.xiufeigo.opencodeshell.v2.persistentPtyRemove
import com.xiufeigo.opencodeshell.v2.persistentPtyUpdate
import com.xiufeigo.opencodeshell.v2.shellCreate
import com.xiufeigo.opencodeshell.v2.worktreeCreate
import com.xiufeigo.opencodeshell.v2.worktreeRemove
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The secondary verbs of `V2ApiMissingVerbs.kt` — `DELETE`
 * `credential/{id}`, `PUT`/`DELETE` `experimental/persistent-pty/{id}`,
 * `POST /api/shell`, `POST`/`DELETE /api/worktree` and `DELETE`
 * `session/{id}/form/{formID}` — asserted on the wire against [MockWebServer],
 * same harness as [V2ApiCredentialPluginTest].
 *
 * Success bodies and error bytes are document-derived from the live 2.0.11
 * `openapi.json` and the cited `protocol/src/groups` route files (`UNVERIFIED` live:
 * write routes never hit a real server); where a shape is shared with a
 * measured route (`Shell.Info`, `PtyNotFoundError`, `WorktreeError`) the
 * fixture keeps those measured bytes.
 */
class V2ApiMissingVerbsTest {

    private companion object {
        /** `schema/src/persistent-pty.ts:9-16` — `{"data":PersistentPty.Info}`. */
        const val PTY_INFO =
            """{"data":{"id":"pty_0c27c0805001TESTPTY0001","title":"bash","command":"bash","args":[],"cwd":"/w","status":"running","pid":4242,"sessionID":"ses_1","foregroundProcess":"bash","size":{"cols":120,"rows":40},"output":{"head":0,"tail":12}}}"""

        /** `schema/src/shell.ts:38-51`, running shape (same bytes as `V2ApiPtyShellTest`). */
        const val SHELL_INFO_RUNNING =
            """{"location":{"directory":"/w"},"data":{"id":"sh_0c27c0805001TESTSHELL01","status":"running","command":"sleep 5","cwd":"/w","shell":"bash","file":"/tmp/opencode/shell/sh_0c27c0805001TESTSHELL01.out","pid":4242,"metadata":{"sessionID":"ses_1"},"time":{"started":1789969243559}}}"""

        /** `Worktree.Info` — bare `{directory}`, **no** `data` envelope (`worktree.ts:36-48`). */
        const val WORKTREE_INFO = """{"directory":"/data/worktree/abcdef/named"}"""

        /** `400 WorktreeError` — `worktree.ts:12-22` (`name`+`data`, not `_tag`). */
        const val WORKTREE_ERROR =
            """{"name":"WorktreeError","data":{"message":"Worktree destination already exists: /data/worktree/abcdef/named","forceRequired":false}}"""

        /** `409 FormAlreadySettledError` — `protocol/src/errors.ts:220-227` (`id`+`message`). */
        const val FORM_ALREADY_SETTLED =
            """{"_tag":"FormAlreadySettledError","id":"frm_1","message":"Form already settled: frm_1"}"""
    }

    private fun api(
        server: MockWebServer,
        directory: String? = null,
        username: String? = null,
        password: String? = null,
    ) = V2Api(
        baseHttpUrl = server.url("/").toString().toHttpUrlOrNull()!!,
        username = username,
        password = password,
        http = V2Api.defaultClient(),
        directory = directory,
    )

    // ------------------------------------------------------------ credential

    @Test
    fun credentialRemoveSendsDeleteWithoutBodyOrLocation() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            api(server, directory = "/w", password = "secret").credentialRemove("cred_x")

            val request = server.takeRequest()
            assertEquals("DELETE", request.method)
            assertEquals("/api/credential/cred_x", request.requestUrl!!.encodedPath)
            // The credential group takes neither query (`credential.ts:35-46`).
            assertNull(request.requestUrl!!.queryParameter("location[directory]"))
            assertEquals(0L, request.bodySize)
        }
    }

    // -------------------------------------------------------- persistent-pty

    @Test
    fun persistentPtyUpdatePutsTheRequiredSizeAndParsesTheInfo() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(PTY_INFO))
            val info = api(server).persistentPtyUpdate("pty_0c27c0805001TESTPTY0001", cols = 120, rows = 40)

            val request = server.takeRequest()
            assertEquals("PUT", request.method)
            assertEquals(
                "/api/experimental/persistent-pty/pty_0c27c0805001TESTPTY0001",
                request.requestUrl!!.encodedPath,
            )
            assertNull(request.requestUrl!!.queryParameter("location[directory]"))
            assertEquals("application/json", request.headers["Content-Type"]?.substringBefore(";"))
            assertEquals(
                JSONObject().put("size", JSONObject().put("cols", 120).put("rows", 40)).toString(),
                request.body.readUtf8(),
            )

            assertEquals("pty_0c27c0805001TESTPTY0001", info.id)
            assertEquals("running", info.status)
            assertEquals(4242L, info.pid)
            assertEquals("ses_1", info.sessionID)
            assertEquals("bash", info.foregroundProcess)
            assertEquals(120, info.cols)
            assertEquals(40, info.rows)
            assertEquals(12L, info.outputTail)
        }
    }

    @Test
    fun persistentPtyUpdateWithAttachmentIdPutsItNextToTheSize() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(PTY_INFO))
            api(server).persistentPtyUpdate(
                "pty_1",
                cols = 80,
                rows = 24,
                attachmentID = "att_9",
            )

            val request = server.takeRequest()
            assertEquals(
                JSONObject().put("attachmentID", "att_9")
                    .put("size", JSONObject().put("cols", 80).put("rows", 24))
                    .toString(),
                request.body.readUtf8(),
            )
        }
    }

    @Test
    fun persistentPtyRemoveDeletesAndAcceptsNoContent() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            api(server).persistentPtyRemove("pty_1")

            val request = server.takeRequest()
            assertEquals("DELETE", request.method)
            assertEquals("/api/experimental/persistent-pty/pty_1", request.requestUrl!!.encodedPath)
            assertNull(request.requestUrl!!.query)
            assertEquals(0L, request.bodySize)
        }
    }

    @Test
    fun persistentPtyErrorsKeepThePtyNotFoundTag() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(404)
                    .setBody("""{"_tag":"PtyNotFoundError","ptyID":"pty_dead","message":"PTY session not found: pty_dead"}"""),
            )
            try {
                api(server).persistentPtyRemove("pty_dead")
                fail("expected ApiException")
            } catch (e: V2Api.ApiException) {
                assertEquals(404, e.code)
                assertTrue(e.message!!.contains("PtyNotFoundError"))
            }
        }
    }

    // ------------------------------------------------------------------ shell

    @Test
    fun shellCreatePostsTheCommandAndParsesTheInfo() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(SHELL_INFO_RUNNING))
            val info = api(server, directory = "/w").shellCreate("sleep 5")

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/shell", request.requestUrl!!.encodedPath)
            assertEquals("/w", request.requestUrl!!.queryParameter("location[directory]"))
            assertEquals("application/json", request.headers["Content-Type"]?.substringBefore(";"))
            assertEquals("""{"command":"sleep 5"}""", request.body.readUtf8())

            assertEquals("sh_0c27c0805001TESTSHELL01", info.id)
            assertEquals("running", info.status)
            assertTrue(info.running)
            assertEquals("/tmp/opencode/shell/sh_0c27c0805001TESTSHELL01.out", info.file)
            assertEquals("ses_1", info.metadata.optString("sessionID", ""))
        }
    }

    @Test
    fun shellCreateSendsTheOptionalKeysAndTheLocationOverride() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(SHELL_INFO_RUNNING))
            api(server, directory = "/instance").shellCreate(
                command = "echo hi",
                cwd = "/w",
                timeout = 1500,
                metadata = JSONObject().put("sessionID", "ses_1"),
                locationDirectory = "/override",
            )

            val request = server.takeRequest()
            // One key, the caller's: the override replaces the instance default
            // rather than standing beside it (the server resolves the **first**
            // occurrence, so a default sent first would win — same rule as
            // `V2ApiPtyShellTest.shellGetWithALocationOverrideReplaces…`).
            val directories = request.requestUrl!!.queryParameterValues("location[directory]")
            assertEquals(listOf("/override"), directories)
            val body = JSONObject(request.body.readUtf8())
            assertEquals("echo hi", body.getString("command"))
            assertEquals("/w", body.getString("cwd"))
            assertEquals(1500, body.getInt("timeout"))
            assertEquals("ses_1", body.getJSONObject("metadata").getString("sessionID"))
        }
    }

    // --------------------------------------------------------------- worktree

    @Test
    fun worktreeCreatePostsProjectIdAndReadsTheBareDirectory() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(WORKTREE_INFO))
            val info = api(server, directory = "/w").worktreeCreate(projectID = "p1")

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/worktree", request.requestUrl!!.encodedPath)
            // No query at all: `projectID` travels in the body (`worktree.ts:36-38`).
            assertNull(request.requestUrl!!.query)
            assertEquals("""{"projectID":"p1"}""", request.body.readUtf8())

            // Bare `Worktree.Info`, not a `data` envelope.
            assertEquals("/data/worktree/abcdef/named", info.directory)
            assertEquals("/data/worktree/abcdef/named", info.raw.optString("directory", ""))
        }
    }

    @Test
    fun worktreeCreateSendsTheOptionalFields() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(WORKTREE_INFO))
            api(server).worktreeCreate(
                projectID = "p1",
                from = "/checkouts/main",
                branch = "feat/x",
                directory = "/data/worktree",
                name = "named",
            )

            val request = server.takeRequest()
            assertEquals(
                JSONObject()
                    .put("projectID", "p1")
                    .put("from", "/checkouts/main")
                    .put("branch", "feat/x")
                    .put("directory", "/data/worktree")
                    .put("name", "named")
                    .toString(),
                request.body.readUtf8(),
            )
        }
    }

    @Test
    fun worktreeRemoveSendsTheRemoveInputAsDeleteBodyNotQuery() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            api(server, directory = "/w").worktreeRemove(
                projectID = "p1",
                directory = "/data/worktree/abcdef/named",
                force = true,
            )

            val request = server.takeRequest()
            assertEquals("DELETE", request.method)
            assertEquals("/api/worktree", request.requestUrl!!.encodedPath)
            // `RemoveInput` is a **payload** (`worktree.ts:50-54`), so the query stays empty.
            assertNull(request.requestUrl!!.query)
            assertEquals("application/json", request.headers["Content-Type"]?.substringBefore(";"))
            assertEquals(
                JSONObject()
                    .put("projectID", "p1")
                    .put("directory", "/data/worktree/abcdef/named")
                    .put("force", true)
                    .toString(),
                request.body.readUtf8(),
            )
        }
    }

    @Test
    fun worktreeCreateErrorKeepsTheWorktreeErrorMessage() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setBody(WORKTREE_ERROR))
            try {
                api(server).worktreeCreate(projectID = "p1", name = "named")
                fail("expected ApiException")
            } catch (e: V2Api.ApiException) {
                assertEquals(400, e.code)
                assertTrue(e.message!!.contains("Worktree destination already exists"))
            }
        }
    }

    // ------------------------------------------------------------------- form

    @Test
    fun formCancelDeletesTheDocumentedRoute() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            api(server, username = "opencode", password = "opencode").formCancel("ses_1", "frm_1")

            val request = server.takeRequest()
            assertEquals("DELETE", request.method)
            assertEquals("/api/session/ses_1/form/frm_1", request.requestUrl!!.encodedPath)
            assertNull(request.requestUrl!!.query)
            assertEquals(0L, request.bodySize)
            assertTrue(request.headers["Authorization"]!!.startsWith("Basic "))
        }
    }

    @Test
    fun formCancelSurfacesFormAlreadySettled() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(409).setBody(FORM_ALREADY_SETTLED))
            try {
                api(server).formCancel("ses_1", "frm_1")
                fail("expected ApiException")
            } catch (e: V2Api.ApiException) {
                assertEquals(409, e.code)
                assertTrue(e.message!!.contains("FormAlreadySettledError"))
                assertTrue(e.message!!.contains("Form already settled"))
            }
        }
    }

    @Test
    fun worktreeRemoveForceFalseIsSentAsFalseNotDropped() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            api(server).worktreeRemove(projectID = "p1", directory = "/d", force = false)

            val request = server.takeRequest()
            val body = JSONObject(request.body.readUtf8())
            // `force` is required by `RemoveInput` — a false must still travel.
            assertTrue(body.has("force"))
            assertFalse(body.getBoolean("force"))
        }
    }
}
