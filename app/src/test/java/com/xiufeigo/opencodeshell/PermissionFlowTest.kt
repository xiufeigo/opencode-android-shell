package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.v2.V2EventAdapter
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Permission chain: v2 frame adaptation (`permission.asked/replied`) + store
 * reduce + REST wire. Store semantics mirror the upstream single-request dock
 * (`sessionPermissionRequest` returns the session's first pending ask), with
 * events for other sessions ignored at reduce time. v2 field names
 * (`action/resources/save/source{id}`) are renamed in `V2EventAdapter.v2Permission`.
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

    private fun adapt(type: String, data: String): List<ChatStore.Event> =
        V2EventAdapter.adapt(type, JSONObject("""{"id":"evt_1","type":"$type","created":1,"data":$data}"""))

    // ------------------------------------------------------------------ parse

    @Test
    fun parseAskedReadsTheV2RequestShape() {
        val event = adapt(
            "permission.asked",
            """{"id":"per_1","sessionID":"ses_1","action":"bash",
               "resources":["git status","ls -la"],"save":["git *"],
               "metadata":{"reason":"test"},
               "source":{"type":"tool","messageID":"msg_1","id":"call_1"}}""",
        ).single() as ChatStore.Event.PermissionAsked
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
    fun parseAskedToleratesMissingOptionalFields() {
        val event = adapt(
            "permission.asked",
            """{"id":"per_2","sessionID":"ses_1","action":"edit"}""",
        ).single() as ChatStore.Event.PermissionAsked
        assertEquals("per_2", event.request.id)
        assertEquals(emptyList<String>(), event.request.patterns)
        assertEquals(emptyList<String>(), event.request.always)
        assertNull(event.request.tool)
        assertNull(event.request.metadata)

        // An ask without a usable id can never be answered: drop, don't crash.
        assertTrue(adapt("permission.asked", """{"sessionID":"ses_1"}""").isEmpty())
    }

    @Test
    fun parseRepliedReadsRequestID() {
        val event = adapt(
            "permission.replied",
            """{"sessionID":"ses_1","requestID":"per_1","reply":"once"}""",
        ).single()
        // W5-E3: the frame's `reply` is kept alongside the removal.
        assertEquals(ChatStore.Event.PermissionReplied("per_1", "once"), event)

        assertTrue(adapt("permission.replied", """{"sessionID":"ses_1"}""").isEmpty())
        // Unknown events fall through to nothing on the v2 path.
        assertTrue(adapt("permission.unknown", """{}""").isEmpty())
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

    @Test
    fun permissionRequestFromJsonReadsTheV2ToolSourceCallID() {
        // v2 keys the tool call id `id` inside `source` (`Permission.Source`);
        // v1 and hybrid payloads keep `callID`. `id` wins when both are there.
        val v2 = OpenCodeClient.PermissionRequest.fromJson(
            JSONObject(
                """{"id":"per_1","sessionID":"ses_1","action":"bash",
                   "resources":["ls"],"save":["ls"],
                   "source":{"type":"tool","messageID":"msg_1","id":"call_1"}}""",
            ),
        )
        assertEquals("bash", v2.permission)
        assertEquals(listOf("ls"), v2.patterns)
        assertEquals("msg_1", v2.tool?.messageID)
        assertEquals("call_1", v2.tool?.callID)

        val v1 = OpenCodeClient.PermissionRequest.fromJson(
            JSONObject(
                """{"id":"per_2","sessionID":"ses_1","permission":"edit",
                   "source":{"type":"tool","messageID":"msg_2","callID":"call_2"}}""",
            ),
        )
        assertEquals("call_2", v1.tool?.callID)

        val both = OpenCodeClient.PermissionRequest.fromJson(
            JSONObject("""{"id":"per_3","source":{"type":"tool","id":"call_v2","callID":"call_v1"}}"""),
        )
        assertEquals("call_v2", both.tool?.callID)

        // JSON null on either key is absent, not the literal "null".
        val nulled = OpenCodeClient.PermissionRequest.fromJson(
            JSONObject("""{"id":"per_4","source":{"type":"tool","messageID":null,"id":null,"callID":"call_4"}}"""),
        )
        assertEquals("", nulled.tool?.messageID)
        assertEquals("call_4", nulled.tool?.callID)

        // A non-tool source is not a tool request (v2 `source.type` discriminates).
        assertNull(
            OpenCodeClient.PermissionRequest.fromJson(
                JSONObject("""{"id":"per_5","source":{"type":"plugin","id":"call_9"}}"""),
            ).tool,
        )
    }

    // ------------------------------------------------------------------- wire

    private fun client(server: MockWebServer) =
        OpenCodeClient(server.url("/").toString().toHttpUrlOrNull()!!, null, null)

    @Test
    fun listPermissionsGetsPermissionPathAndParsesArray() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"location":{"directory":"/w"},"data":[
                        {"id":"per_1","sessionID":"ses_1","action":"bash",
                         "resources":["ls"],"save":["ls"],
                         "source":{"type":"tool","messageID":"msg_1","callID":"call_1"},
                         "metadata":{}},
                        {"sessionID":"ses_1","action":"edit"}]}""",
                ),
            )
            val c = client(server)
            val list = c.listPermissions("/w")
            // The id-less entry is dropped: it can never be replied to.
            assertEquals(1, list.size)
            assertEquals("per_1", list[0].id)
            assertEquals("bash", list[0].permission)
            assertEquals(listOf("ls"), list[0].patterns)
            assertEquals(listOf("ls"), list[0].always)
            assertEquals("msg_1", list[0].tool?.messageID)
            assertEquals("call_1", list[0].tool?.callID)
            val recorded = server.takeRequest()
            assertEquals("GET", recorded.method)
            assertEquals("/api/permission/request", recorded.requestUrl!!.encodedPath)
            assertEquals("/w", recorded.requestUrl!!.queryParameter("location[directory]"))
            c.close()
        }
    }

    @Test
    fun replyPermissionThrowsAndPointsAtV2Api() {
        MockWebServer().use { server ->
            val c = client(server)
            // No v1-compatible reply route exists on v2 (the reply needs the
            // sessionID this signature never carried): fail loudly and point
            // at V2Api.replyPermission instead of posting to a dead route.
            val e = assertThrows(UnsupportedOperationException::class.java) {
                c.replyPermission("per_1", "always", "/w")
            }
            assertTrue(e.message!!.contains("V2Api.replyPermission"))
            assertEquals(0, server.requestCount)
            c.close()
        }
    }
}
