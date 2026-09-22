package com.xiufeigo.opencodeshell

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Permission chain (`PermissionV1`, `research/04` §2/§3): SSE parse + store
 * reduce + REST wire. Store semantics mirror the upstream single-request dock
 * (`sessionPermissionRequest` returns the session's first pending ask), with
 * events for other sessions ignored at reduce time.
 */
class PermissionFlowTest {

    private fun request(
        id: String,
        sessionID: String = "ses_1",
        permission: String = "bash",
        patterns: List<String> = listOf("ls -la"),
    ) = OpenCodeClient.PermissionRequest(
        id = id,
        sessionID = sessionID,
        permission = permission,
        patterns = patterns,
        always = patterns,
        tool = null,
        metadata = null,
    )

    // ------------------------------------------------------------------ parse

    @Test
    fun parseAskedReadsEnvelopeProperties() {
        val event = ChatStore.parseEvent(
            "permission.asked",
            JSONObject(
                """{"type":"permission.asked","properties":
                   {"id":"per_1","sessionID":"ses_1","permission":"bash",
                    "patterns":["git status","ls -la"],"always":["git *"],
                    "metadata":{"reason":"test"},
                    "tool":{"messageID":"msg_1","callID":"call_1"}}}""",
            ),
        ) as ChatStore.Event.PermissionAsked
        val req = event.request
        assertEquals("per_1", req.id)
        assertEquals("ses_1", req.sessionID)
        assertEquals("bash", req.permission)
        assertEquals(listOf("git status", "ls -la"), req.patterns)
        assertEquals(listOf("git *"), req.always)
        assertEquals("msg_1", req.tool?.messageID)
        assertEquals("call_1", req.tool?.callID)
        assertEquals("test", req.metadata?.optString("reason"))
    }

    @Test
    fun parseAskedToleratesFlatPayloadAndMissingFields() {
        val event = ChatStore.parseEvent(
            "permission.asked",
            JSONObject("""{"type":"permission.asked","id":"per_2","sessionID":"ses_1","permission":"edit"}"""),
        ) as ChatStore.Event.PermissionAsked
        assertEquals("per_2", event.request.id)
        assertEquals(emptyList<String>(), event.request.patterns)
        assertEquals(emptyList<String>(), event.request.always)
        assertNull(event.request.tool)
        assertNull(event.request.metadata)

        // An ask without a usable id can never be answered: drop, don't crash.
        assertNull(
            ChatStore.parseEvent(
                "permission.asked",
                JSONObject("""{"type":"permission.asked","properties":{"sessionID":"ses_1"}}"""),
            ),
        )
    }

    @Test
    fun parseRepliedReadsRequestID() {
        val event = ChatStore.parseEvent(
            "permission.replied",
            JSONObject(
                """{"type":"permission.replied","properties":
                   {"sessionID":"ses_1","requestID":"per_1","reply":"once"}}""",
            ),
        )
        // W5-E3: the frame's `reply` is kept alongside the removal.
        assertEquals(ChatStore.Event.PermissionReplied("per_1", "once"), event)

        assertNull(
            ChatStore.parseEvent(
                "permission.replied",
                JSONObject("""{"type":"permission.replied","properties":{"sessionID":"ses_1"}}"""),
            ),
        )
        // Unknown events still fall through to null.
        assertNull(
            ChatStore.parseEvent(
                "permission.unknown",
                JSONObject("""{"type":"permission.unknown","properties":{}}"""),
            ),
        )
    }

    // ----------------------------------------------------------------- reduce

    @Test
    fun askedAddsUpdatesAndKeepsArrivalOrder() {
        var s = ChatStore.reduce(
            ChatStore.ChatState(sessionID = "ses_1"),
            ChatStore.Event.PermissionAsked(request("per_1")),
        )
        s = ChatStore.reduce(s, ChatStore.Event.PermissionAsked(request("per_2")))
        assertEquals(listOf("per_1", "per_2"), s.pendingPermissions.map { it.id })

        // Re-asking the same id updates in place (no duplicate, no reorder).
        s = ChatStore.reduce(
            s,
            ChatStore.Event.PermissionAsked(request("per_1", patterns = listOf("new"))),
        )
        assertEquals(listOf("per_1", "per_2"), s.pendingPermissions.map { it.id })
        assertEquals(listOf("new"), s.pendingPermissions.first { it.id == "per_1" }.patterns)
    }

    @Test
    fun askedForOtherSessionIsIgnored() {
        val tracked = ChatStore.ChatState(sessionID = "ses_1")
        val s = ChatStore.reduce(
            tracked,
            ChatStore.Event.PermissionAsked(request("per_9", sessionID = "ses_other")),
        )
        assertEquals(tracked, s)
        assertTrue(s.pendingPermissions.isEmpty())

        // A state without a tracked session accepts everything (pre-session).
        val untracked = ChatStore.reduce(
            ChatStore.ChatState(),
            ChatStore.Event.PermissionAsked(request("per_9", sessionID = "ses_other")),
        )
        assertEquals(listOf("per_9"), untracked.pendingPermissions.map { it.id })
    }

    @Test
    fun repliedRemovesByRequestIDOnly() {
        var s = ChatStore.ChatState(sessionID = "ses_1")
        s = ChatStore.reduce(s, ChatStore.Event.PermissionAsked(request("per_1")))
        s = ChatStore.reduce(s, ChatStore.Event.PermissionAsked(request("per_2")))
        s = ChatStore.reduce(s, ChatStore.Event.PermissionReplied("per_1"))
        assertEquals(listOf("per_2"), s.pendingPermissions.map { it.id })

        // Unknown / repeated replies are no-ops (idempotent).
        val after = ChatStore.reduce(s, ChatStore.Event.PermissionReplied("per_1"))
        assertEquals(s, after)
        assertEquals(s, ChatStore.reduce(s, ChatStore.Event.PermissionReplied("per_missing")))
    }

    @Test
    fun snapshotMergesOnlyTrackedSession() {
        var s = ChatStore.ChatState(sessionID = "ses_1")
        // A stale optimistic entry the server no longer reports disappears.
        s = ChatStore.reduce(s, ChatStore.Event.PermissionAsked(request("per_stale")))
        s = ChatStore.reduce(
            s,
            ChatStore.Event.PermissionSnapshot(
                listOf(
                    request("per_1"),
                    request("per_2", sessionID = "ses_other"),
                    request(""),
                ),
            ),
        )
        assertEquals(listOf("per_1"), s.pendingPermissions.map { it.id })
    }

    // ------------------------------------------------------------------- json

    @Test
    fun permissionRequestFromJsonToleratesMissingAndPartialFields() {
        val minimal = OpenCodeClient.PermissionRequest.fromJson(JSONObject("""{"id":"per_1"}"""))
        assertEquals("per_1", minimal.id)
        assertEquals("", minimal.sessionID)
        assertEquals("", minimal.permission)
        assertEquals(emptyList<String>(), minimal.patterns)
        assertEquals(emptyList<String>(), minimal.always)
        assertNull(minimal.tool)
        assertNull(minimal.metadata)

        // Non-string/empty array entries are skipped, partial tool survives.
        val partial = OpenCodeClient.PermissionRequest.fromJson(
            JSONObject(
                """{"id":"per_2","sessionID":"ses_1","permission":"edit",
                   "patterns":["src/a.kt",""],"always":[""],
                   "tool":{"messageID":"msg_1"}}""",
            ),
        )
        assertEquals(listOf("src/a.kt"), partial.patterns)
        assertEquals(emptyList<String>(), partial.always)
        assertEquals("msg_1", partial.tool?.messageID)
        assertEquals("", partial.tool?.callID)
    }

    // ------------------------------------------------------------------- wire

    private fun client(server: MockWebServer) =
        OpenCodeClient(server.url("/").toString().toHttpUrlOrNull()!!, null, null)

    @Test
    fun listPermissionsGetsPermissionPathAndParsesArray() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """[{"id":"per_1","sessionID":"ses_1","permission":"bash",
                        "patterns":["ls"],"always":["ls"],"metadata":{}},
                       {"sessionID":"ses_1","permission":"edit"}]""",
                ),
            )
            val c = client(server)
            val list = c.listPermissions("/w")
            // The id-less entry is dropped: it can never be replied to.
            assertEquals(1, list.size)
            assertEquals("per_1", list[0].id)
            assertEquals(listOf("ls"), list[0].patterns)
            val recorded = server.takeRequest()
            assertEquals("GET", recorded.method)
            assertEquals("/permission", recorded.requestUrl!!.encodedPath)
            assertEquals("/w", recorded.requestUrl!!.queryParameter("directory"))
            c.close()
        }
    }

    @Test
    fun replyPermissionPostsReplyBodyToReplyPath() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("true"))
            val c = client(server)
            c.replyPermission("per_1", "always", "/w")
            val recorded = server.takeRequest()
            assertEquals("POST", recorded.method)
            assertEquals("/permission/per_1/reply", recorded.requestUrl!!.encodedPath)
            assertEquals("/w", recorded.requestUrl!!.queryParameter("directory"))
            assertEquals("always", JSONObject(recorded.body.readUtf8()).getString("reply"))
            c.close()
        }
    }
}
