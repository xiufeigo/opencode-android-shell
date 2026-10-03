package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.v2.V2EventAdapter
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W5-E, store side: the E2 invalidation signals and the E3 `PartDelta`
 * field coverage, adapted to the v2 frame path (`V2EventAdapter`; the v1
 * `parseEvent` is gone).
 *
 * Wire anchors: `_upstream/packages/schema/src/event-manifest.ts` (signal
 * inventory), `project.ts:59`, `pty.ts:34-37`, `vcs-event.ts:8`,
 * `filesystem.ts:6-13`, `agent.ts:11` / `model.ts:12` / `provider.ts:26` /
 * `config.ts:134`, `credential.ts:18-22` … `location-event.ts:6`.
 */
class ChatStoreWave5EventTest {

    private fun textPart(id: String = "p1", text: String = "") =
        ChatStore.UiPart(id = id, type = "text", text = text)

    private fun message(id: String = "m1", parts: List<ChatStore.UiPart> = listOf(textPart())) =
        ChatStore.UiMessage(id = id, role = "assistant", timeCreated = 1, parts = parts)

    private fun state(parts: List<ChatStore.UiPart> = listOf(textPart())) =
        ChatStore.ChatState(sessionID = "ses_1", messages = listOf(message(parts = parts)))

    private fun adapt(type: String, json: String = "{}"): List<ChatStore.Event> =
        V2EventAdapter.adapt(
            type,
            JSONObject("""{"id":"evt_1","type":"$type","created":1,"data":$json}"""),
        )

    private fun signalOf(type: String, json: String = "{}"): ChatStore.Event.ServerSignal =
        adapt(type, json).single() as ChatStore.Event.ServerSignal

    // ------------------------------------------------- E2: signal coverage

    /** Every wire type [ChatStore.Signal] models, one row per event definition. */
    private val signalTypes = listOf(
        "provider.updated" to ChatStore.Signal.PROVIDER_UPDATED,
        "model.updated" to ChatStore.Signal.MODEL_UPDATED,
        "agent.updated" to ChatStore.Signal.AGENT_UPDATED,
        "config.updated" to ChatStore.Signal.CONFIG_UPDATED,
        "project.updated" to ChatStore.Signal.PROJECT_UPDATED,
        "vcs.branch.updated" to ChatStore.Signal.VCS_BRANCH_UPDATED,
        "file.edited" to ChatStore.Signal.FILE_EDITED,
        "session.created" to ChatStore.Signal.SESSION_CREATED,
        "session.deleted" to ChatStore.Signal.SESSION_DELETED,
        // A-3: the session-row write. v1's own frame; on v2 the adapter
        // synthesizes it from the row-writing session events (model/agent
        // selection, permissions, revert) — see `ChatStore.Signal`.
        "session.updated" to ChatStore.Signal.SESSION_UPDATED,
        "pty.created" to ChatStore.Signal.PTY_CREATED,
        "pty.updated" to ChatStore.Signal.PTY_UPDATED,
        "pty.exited" to ChatStore.Signal.PTY_EXITED,
        "pty.deleted" to ChatStore.Signal.PTY_DELETED,
        "credential.updated" to ChatStore.Signal.CREDENTIAL_UPDATED,
        "credential.switched" to ChatStore.Signal.CREDENTIAL_SWITCHED,
        "integration.updated" to ChatStore.Signal.INTEGRATION_UPDATED,
        "plugin.updated" to ChatStore.Signal.PLUGIN_UPDATED,
        "worktree.updated" to ChatStore.Signal.WORKTREE_UPDATED,
        "worktree.resolved" to ChatStore.Signal.WORKTREE_RESOLVED,
        "worktree.ready" to ChatStore.Signal.WORKTREE_READY,
        "worktree.failed" to ChatStore.Signal.WORKTREE_FAILED,
        "persistent-pty.added" to ChatStore.Signal.PERSISTENT_PTY_ADDED,
        "persistent-pty.removed" to ChatStore.Signal.PERSISTENT_PTY_REMOVED,
        "shell.created" to ChatStore.Signal.SHELL_CREATED,
        "shell.exited" to ChatStore.Signal.SHELL_EXITED,
        "shell.deleted" to ChatStore.Signal.SHELL_DELETED,
        "websearch.updated" to ChatStore.Signal.WEBSEARCH_UPDATED,
        "reference.updated" to ChatStore.Signal.REFERENCE_UPDATED,
        "skill.updated" to ChatStore.Signal.SKILL_UPDATED,
        "command.updated" to ChatStore.Signal.COMMAND_UPDATED,
        "lsp.updated" to ChatStore.Signal.LSP_UPDATED,
        "location.shutdown" to ChatStore.Signal.LOCATION_SHUTDOWN,
        // `mcp-event.ts:5-26` — the three MCP settle frames (W2 A-5: they used
        // to be a deliberate drop, so the MCP list never went live).
        "mcp.status.changed" to ChatStore.Signal.MCP_STATUS_CHANGED,
        "mcp.tools.changed" to ChatStore.Signal.MCP_TOOLS_CHANGED,
        "mcp.resources.changed" to ChatStore.Signal.MCP_RESOURCES_CHANGED,
    )

    @Test
    fun everyWiredSignalTypeParsesToItsSignal() {
        for ((wire, expected) in signalTypes) {
            val event = adapt(wire).single()
            assertEquals("$wire must map to $expected", ChatStore.Event.ServerSignal(expected), event)
            assertEquals("$wire", expected.wireType)
            assertEquals(expected, ChatStore.Signal.ofWireType(wire))
        }
        // The enum is exactly this set: a new upstream event must be a
        // deliberate addition, not a silently dropped frame.
        assertEquals(signalTypes.map { it.second }.toSet(), ChatStore.Signal.entries.toSet())
    }

    @Test
    fun unknownEventsStillFallThroughToNothing() {
        // Not modelled on purpose: no TUI surface here, `server.heartbeat` is a
        // keepalive, and `models-dev.refreshed` is server-internal. They must
        // produce no event so nothing downstream starts reacting to a type the
        // store does not model.
        for (type in listOf("server.heartbeat", "tui.toast.show", "sync", "totally.made.up", "models-dev.refreshed")) {
            assertTrue(type, adapt(type).isEmpty())
        }
        assertNull(ChatStore.Signal.ofWireType("models-dev.refreshed"))
    }

    @Test
    fun projectUpdatedCarriesTheProjectSubject() {
        val event = signalOf(
            "project.updated",
            """{"id":"prj_7","canonical":"/work/app","time":{"created":1,"updated":2},"sandboxes":[]}""",
        )
        assertEquals(ChatStore.Signal.PROJECT_UPDATED, event.signal)
        assertEquals("prj_7", event.subject)
        // Project events are not session-routed.
        assertNull(event.sessionID)
    }

    @Test
    fun ptySignalsCarryTheirTerminalId() {
        assertEquals(
            "pty_9",
            signalOf(
                "pty.created",
                """{"info":{"id":"pty_9","title":"Terminal 1",
                   "command":"bash","args":[],"cwd":"/w","status":"running","pid":42}}""",
            ).subject,
        )
        assertEquals("pty_9", signalOf("pty.updated", """{"info":{"id":"pty_9"}}""").subject)
        assertEquals("pty_9", signalOf("pty.exited", """{"id":"pty_9","exitCode":0}""").subject)
        assertEquals("pty_9", signalOf("pty.deleted", """{"id":"pty_9"}""").subject)
        // Missing payload → signal still fires, just without a subject.
        assertNull(signalOf("pty.created", """{}""").subject)
    }

    @Test
    fun fileEditedCarriesTheWrittenPath() {
        val event = signalOf("file.edited", """{"file":"src/App.kt"}""")
        assertEquals(ChatStore.Signal.FILE_EDITED, event.signal)
        assertEquals("src/App.kt", event.subject)
    }

    @Test
    fun sessionSignalsCarryTheirSessionForListRefreshes() {
        val created = signalOf("session.created", """{"sessionID":"ses_new","slug":"s","version":"2"}""")
        assertEquals(ChatStore.Signal.SESSION_CREATED, created.signal)
        // Unlike the chat-state events, the signal is read *before* the session
        // gate, so a session created in another project still reaches the owner.
        assertEquals("ses_new", created.sessionID)

        val deleted = signalOf("session.deleted", """{"sessionID":"ses_old"}""")
        assertEquals(ChatStore.Signal.SESSION_DELETED, deleted.signal)
        assertEquals("ses_old", deleted.sessionID)
    }

    @Test
    fun signalFramesWithoutPayloadFieldsStillFire() {
        // Several signal payloads are empty by schema (`provider.updated`,
        // `skill.updated`, …) — the signal itself is the message.
        val flat = adapt("vcs.branch.updated").single()
        assertEquals(ChatStore.Event.ServerSignal(ChatStore.Signal.VCS_BRANCH_UPDATED), flat)
    }

    @Test
    fun signalsNeverTouchTheChatState() {
        var s = state()
        for ((wire, _) in signalTypes) {
            s = ChatStore.reduce(s, ChatStore.Event.ServerSignal(ChatStore.Signal.ofWireType(wire)!!))
            assertEquals("$wire must not change the transcript", state(), s)
        }
        // …not even when the payload names the tracked session.
        val withSession = ChatStore.Event.ServerSignal(ChatStore.Signal.SESSION_DELETED, sessionID = "ses_1")
        assertEquals(state(), ChatStore.reduce(state(), withSession))
    }

    // ------------------------------------------- E3: any string field delta

    @Test
    fun partDeltaAppendsReasoningText() {
        var s = ChatStore.reduce(state(), ChatStore.Event.Snapshot(listOf(message())))
        s = ChatStore.reduce(s, ChatStore.Event.PartUpdated("m1", textPart()))
        // A reasoning part carries its body in `text` (`v1/session.ts:118-128`).
        s = ChatStore.reduce(
            s,
            ChatStore.Event.PartUpdated("m1", ChatStore.UiPart(id = "p2", type = "reasoning")),
        )
        s = ChatStore.reduce(s, ChatStore.Event.PartDelta("m1", "p2", "reasoning", "thinking…"))
        assertEquals("thinking…", s.messages.single().parts.first { it.id == "p2" }.text)
    }

    @Test
    fun partDeltaAppendsTheToolStateOutputAndError() {
        var s = state(parts = listOf(ChatStore.UiPart(id = "t1", type = "tool", toolName = "bash")))
        s = ChatStore.reduce(s, ChatStore.Event.PartDelta("m1", "t1", "state.output", "line 1\n"))
        s = ChatStore.reduce(s, ChatStore.Event.PartDelta("m1", "t1", "output", "line 2\n"))
        s = ChatStore.reduce(s, ChatStore.Event.PartDelta("m1", "t1", "state.error", "boom"))
        // `output` is accepted as the short spelling of `state.output` — the
        // server may send either (`field` is an arbitrary string).
        assertEquals("line 1\nline 2\n", s.messages.single().parts.single().toolOutput)
        assertEquals("boom", s.messages.single().parts.single().toolError)
    }

    @Test
    fun partDeltaAppendsFilePartStrings() {
        var s = state(parts = listOf(ChatStore.UiPart(id = "f1", type = "file")))
        s = ChatStore.reduce(s, ChatStore.Event.PartDelta("m1", "f1", "mime", "image/png"))
        s = ChatStore.reduce(s, ChatStore.Event.PartDelta("m1", "f1", "filename", "shot.png"))
        s = ChatStore.reduce(s, ChatStore.Event.PartDelta("m1", "f1", "url", "data:image/png"))
        val part = s.messages.single().parts.single()
        assertEquals("image/png", part.mime)
        assertEquals("shot.png", part.filename)
        assertEquals("data:image/png", part.url)
    }

    @Test
    fun unknownDeltaFieldsAndOrphansAreDroppedNotGuessed() {
        var s = state(parts = listOf(ChatStore.UiPart(id = "t1", type = "tool", toolName = "bash")))
        val before = s
        // `input`/`toolInput` is an *object* field: appending a string would
        // corrupt it, so it stays untouched (the old text-only reducer's rule,
        // kept for every field this store cannot model).
        assertEquals(before, ChatStore.reduce(s, ChatStore.Event.PartDelta("m1", "t1", "toolInput", "x")))
        assertEquals(before, ChatStore.reduce(s, ChatStore.Event.PartDelta("m1", "t1", "state.input", "x")))
        assertEquals(before, ChatStore.reduce(s, ChatStore.Event.PartDelta("m1", "t1", "future.field", "x")))
        // Orphan delta (no such message / part) — never crashes, never creates.
        assertEquals(before, ChatStore.reduce(s, ChatStore.Event.PartDelta("m_none", "t1", "text", "x")))
        assertEquals(before, ChatStore.reduce(s, ChatStore.Event.PartDelta("m1", "p_none", "text", "x")))
        s = ChatStore.reduce(s, ChatStore.Event.PartDelta("m1", "t1", "text", "kept"))
        assertEquals("kept", s.messages.single().parts.single().text)
    }

    @Test
    fun toolOutputDeltaStopsAtTheDisplayCap() {
        val full = ChatStore.UiPart(id = "t1", type = "tool", toolName = "bash", toolOutput = "x".repeat(16_000))
        val capped = ChatStore.reduce(state(parts = listOf(full)), ChatStore.Event.PartDelta("m1", "t1", "state.output", "y"))
        assertEquals("a full buffer ignores further deltas", full.toolOutput, capped.messages.single().parts.single().toolOutput)

        val near = ChatStore.UiPart(id = "t1", type = "tool", toolName = "bash", toolOutput = "x".repeat(15_990))
        val grown = ChatStore.reduce(state(parts = listOf(near)), ChatStore.Event.PartDelta("m1", "t1", "state.output", "y".repeat(100)))
        val output = grown.messages.single().parts.single().toolOutput!!
        assertTrue("crossing the cap marks the truncation", output.endsWith("(truncated)"))
    }

    @Test
    fun v2DeltaFramesMapToPartDeltaEvents() {
        // v2 deltas are `session.{text,reasoning}.delta{assistantMessageID,
        // ordinal, delta}` (`session-event.ts:396-406`) — the adapter keys the
        // part by `messageID:kind:ordinal` and always appends to `text`.
        val event = adapt(
            "session.text.delta",
            """{"sessionID":"ses_1","assistantMessageID":"m1","ordinal":2,"delta":"hi"}""",
        ).single()
        assertEquals(ChatStore.Event.PartDelta("m1", "m1:text:2", "text", "hi"), event)

        // A delta with no assistant is dropped — nothing to append to.
        assertTrue(adapt("session.text.delta", """{"sessionID":"ses_1","ordinal":0,"delta":"x"}""").isEmpty())
    }

    // ------------------------------------- E3: permission.replied keeps reply

    @Test
    fun permissionRepliedParsesAndKeepsTheReply() {
        assertEquals(
            ChatStore.Event.PermissionReplied("per_1", "always"),
            adapt("permission.replied", """{"sessionID":"ses_1","requestID":"per_1","reply":"always"}""").single(),
        )
        // An older server may omit `reply`: the removal must still happen.
        assertEquals(
            ChatStore.Event.PermissionReplied("per_1", ""),
            adapt("permission.replied", """{"sessionID":"ses_1","requestID":"per_1"}""").single(),
        )
        assertTrue(adapt("permission.replied", """{"sessionID":"ses_1"}""").isEmpty())
    }

    @Test
    fun permissionRepliesAreStoredSoTheAnswerCanBeShownLater() {
        var s = ChatStore.reduce(
            ChatStore.ChatState(sessionID = "ses_1"),
            ChatStore.Event.PermissionAsked(
                OpenCodeClient.PermissionRequest(
                    id = "per_1",
                    sessionID = "ses_1",
                    permission = "bash",
                    patterns = listOf("*"),
                    always = emptyList(),
                    tool = null,
                    metadata = null,
                ),
            ),
        )
        s = ChatStore.reduce(s, ChatStore.Event.PermissionReplied("per_1", "reject"))
        assertTrue("the pending dock entry is gone", s.pendingPermissions.isEmpty())
        assertEquals(listOf(ChatStore.PermissionReply("per_1", "reject")), s.permissionReplies)
        assertEquals("reject", s.permissionReply("per_1")?.reply)

        // Idempotent: replaying the frame (or a duplicate) changes nothing —
        // no second record, no error.
        assertEquals(s, ChatStore.reduce(s, ChatStore.Event.PermissionReplied("per_1", "reject")))

        // A reply for a request this screen never had pending records nothing:
        // the record list is scoped to its own asks (and stays bounded).
        val foreign = ChatStore.reduce(s, ChatStore.Event.PermissionReplied("per_other", "once"))
        assertEquals(s, foreign)
    }

    @Test
    fun anAskThatBecomesPendingAgainRetiresItsOldAnswer() {
        val ask = OpenCodeClient.PermissionRequest(
            id = "per_1",
            sessionID = "ses_1",
            permission = "bash",
            patterns = emptyList(),
            always = emptyList(),
            tool = null,
            metadata = null,
        )
        var s = ChatStore.reduce(ChatStore.ChatState(sessionID = "ses_1"), ChatStore.Event.PermissionAsked(ask))
        s = ChatStore.reduce(s, ChatStore.Event.PermissionReplied("per_1", "always"))
        assertEquals("always", s.permissionReply("per_1")?.reply)

        // The rollback path (`ChatViewModel.replyPermission` on a failed POST):
        // the request is pending again, so the "answered always" record must go
        // with it — otherwise the dock and the record contradict each other.
        s = ChatStore.reduce(s, ChatStore.Event.PermissionAsked(ask))
        assertEquals(listOf("per_1"), s.pendingPermissions.map { it.id })
        assertNull(s.permissionReply("per_1"))
        assertTrue(s.permissionReplies.isEmpty())
    }

    @Test
    fun permissionReplyHistoryIsBounded() {
        var s = ChatStore.ChatState(sessionID = "ses_1")
        repeat(30) { i ->
            s = ChatStore.reduce(
                s,
                ChatStore.Event.PermissionAsked(
                    OpenCodeClient.PermissionRequest(
                        id = "per_$i",
                        sessionID = "ses_1",
                        permission = "bash",
                        patterns = emptyList(),
                        always = emptyList(),
                        tool = null,
                        metadata = null,
                    ),
                ),
            )
            s = ChatStore.reduce(s, ChatStore.Event.PermissionReplied("per_$i", "once"))
        }
        // `MAX_RESOLVED_REQUESTS = 20`: newest win, the oldest fall off.
        assertEquals(20, s.permissionReplies.size)
        assertEquals("per_10", s.permissionReplies.first().requestID)
        assertEquals("per_29", s.permissionReplies.last().requestID)
        assertNull(s.permissionReply("per_0"))
    }

    // ------------------------------------------------------------ wiring order

    @Test
    fun aSignalFrameKeepsItsSessionRoutingOnTheV2Envelope() {
        // v2 frames carry routing in `data.sessionID`; the gate that drops a
        // foreign session's transcript would drop this frame too — which is
        // exactly why the owner reads signals first.
        val frame = JSONObject(
            """{"id":"evt_9","type":"session.deleted","created":1,"data":{"sessionID":"ses_x"}}""",
        )
        val event = V2EventAdapter.adapt("session.deleted", frame).single()
        assertEquals(
            ChatStore.Event.ServerSignal(ChatStore.Signal.SESSION_DELETED, sessionID = "ses_x"),
            event,
        )
        assertEquals("ses_x", ChatStore.eventSessionId(frame))
        assertFalse(ChatStore.acceptsSession("ses_mine", frame))
        assertTrue(ChatStore.acceptsSession("ses_x", frame))
        assertNotNull(ChatStore.Signal.ofWireType("session.deleted"))
    }
}
