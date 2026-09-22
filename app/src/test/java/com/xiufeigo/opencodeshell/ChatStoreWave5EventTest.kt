package com.xiufeigo.opencodeshell

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W5-E, store side: the E2 invalidation signals (`provider.updated`,
 * `project.updated`, `vcs.branch.updated`, `file.edited`, `session.*`,
 * `pty.*`) and the two E3 parsing gaps (`message.part.delta` beyond
 * `field == "text"`, and the dropped `permission.replied.reply` /
 * `question.replied.answers` payloads).
 *
 * Wire anchors: `_upstream/packages/schema/src/v1/session.ts:632-641`
 * (`PartDelta`), `v1/permission.ts:61-65`, `v1/question.ts:47-60`,
 * `project.ts:59`, `pty.ts:34-37`, `vcs-event.ts:8`, `v1/filesystem.ts:7`,
 * `agent.ts:11` / `model.ts:12` / `provider.ts:26` / `config.ts:134`.
 */
class ChatStoreWave5EventTest {

    private fun textPart(id: String = "p1", text: String = "") =
        ChatStore.UiPart(id = id, type = "text", text = text)

    private fun message(id: String = "m1", parts: List<ChatStore.UiPart> = listOf(textPart())) =
        ChatStore.UiMessage(id = id, role = "assistant", timeCreated = 1, parts = parts)

    private fun state(parts: List<ChatStore.UiPart> = listOf(textPart())) =
        ChatStore.ChatState(sessionID = "ses_1", messages = listOf(message(parts = parts)))

    private fun parse(type: String, json: String) = ChatStore.parseEvent(type, JSONObject(json))

    private fun signalOf(type: String, json: String): ChatStore.Event.ServerSignal =
        parse(type, json) as ChatStore.Event.ServerSignal

    // ------------------------------------------------- E2: signal coverage

    /** The 13 wire types E2 consumes, one row per event definition. */
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
        "pty.created" to ChatStore.Signal.PTY_CREATED,
        "pty.updated" to ChatStore.Signal.PTY_UPDATED,
        "pty.exited" to ChatStore.Signal.PTY_EXITED,
        "pty.deleted" to ChatStore.Signal.PTY_DELETED,
    )

    @Test
    fun everyWiredSignalTypeParsesToItsSignal() {
        for ((wire, expected) in signalTypes) {
            val event = parse(wire, """{"type":"$wire","properties":{}}""")
            assertEquals("$wire must map to $expected", ChatStore.Event.ServerSignal(expected), event)
            assertEquals("$wire", expected.wireType)
            assertEquals(expected, ChatStore.Signal.ofWireType(wire))
        }
        // The enum is exactly this set: a new upstream event must be a
        // deliberate addition, not a silently dropped frame.
        assertEquals(signalTypes.map { it.second }.toSet(), ChatStore.Signal.entries.toSet())
    }

    @Test
    fun unknownEventsStillFallThroughToNull() {
        // Not modelled on purpose: no MCP / LSP / TUI surface here, and
        // `server.heartbeat` is a keepalive. They must stay `null` so nothing
        // downstream starts reacting to a type the store does not model.
        for (type in listOf("lsp.updated", "server.heartbeat", "tui.toast.show", "sync", "totally.made.up")) {
            assertNull(type, parse(type, """{"type":"$type","properties":{}}"""))
        }
        assertNull(ChatStore.Signal.ofWireType("lsp.updated"))
    }

    @Test
    fun projectUpdatedCarriesTheProjectSubject() {
        val event = signalOf(
            "project.updated",
            """{"type":"project.updated","properties":{"id":"prj_7","canonical":"/work/app",
               "time":{"created":1,"updated":2},"sandboxes":[]}}""",
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
                """{"type":"pty.created","properties":{"info":{"id":"pty_9","title":"Terminal 1",
                   "command":"bash","args":[],"cwd":"/w","status":"running","pid":42}}}""",
            ).subject,
        )
        assertEquals(
            "pty_9",
            signalOf("pty.updated", """{"type":"pty.updated","properties":{"info":{"id":"pty_9"}}}""").subject,
        )
        assertEquals(
            "pty_9",
            signalOf("pty.exited", """{"type":"pty.exited","properties":{"id":"pty_9","exitCode":0}}""").subject,
        )
        assertEquals("pty_9", signalOf("pty.deleted", """{"type":"pty.deleted","properties":{"id":"pty_9"}}""").subject)
        // Missing payload → signal still fires, just without a subject.
        assertNull(signalOf("pty.created", """{"type":"pty.created","properties":{}}""").subject)
    }

    @Test
    fun fileEditedCarriesTheWrittenPath() {
        val event = signalOf("file.edited", """{"type":"file.edited","properties":{"file":"src/App.kt"}}""")
        assertEquals(ChatStore.Signal.FILE_EDITED, event.signal)
        assertEquals("src/App.kt", event.subject)
    }

    @Test
    fun sessionSignalsCarryTheirSessionForListRefreshes() {
        val created = signalOf(
            "session.created",
            """{"type":"session.created","properties":{"sessionID":"ses_new","info":{"id":"ses_new"}}}""",
        )
        assertEquals(ChatStore.Signal.SESSION_CREATED, created.signal)
        // Unlike the chat-state events, the signal is read *before* the session
        // gate, so a session created in another project still reaches the owner.
        assertEquals("ses_new", created.sessionID)

        val deleted = signalOf("session.deleted", """{"type":"session.deleted","properties":{"sessionID":"ses_old"}}""")
        assertEquals(ChatStore.Signal.SESSION_DELETED, deleted.signal)
        assertEquals("ses_old", deleted.sessionID)
    }

    @Test
    fun signalFramesWithoutAnEnvelopeStillParse() {
        // Flat payloads are tolerated everywhere else in `parseEvent`; a signal
        // must not be the one branch that needs `properties`.
        val flat = parse("vcs.branch.updated", """{"type":"vcs.branch.updated"}""")
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
    fun parseEventReadsTheDeltaFieldForEachShape() {
        val reasoning = parse(
            "message.part.delta",
            """{"type":"message.part.delta","properties":{"sessionID":"ses_1","messageID":"m1",
               "partID":"p1","field":"state.output","delta":"hi"}}""",
        )
        assertEquals(ChatStore.Event.PartDelta("m1", "p1", "state.output", "hi"), reasoning)

        // `field` omitted → the historical default stays "text".
        val missing = parse(
            "message.part.delta",
            """{"type":"message.part.delta","properties":{"sessionID":"ses_1","messageID":"m1",
               "partID":"p1","delta":"bare"}}""",
        )
        assertEquals(ChatStore.Event.PartDelta("m1", "p1", "text", "bare"), missing)

        // Ids are still required: without them there is nothing to append to.
        assertNull(
            parse(
                "message.part.delta",
                """{"type":"message.part.delta","properties":{"sessionID":"ses_1","field":"text","delta":"x"}}""",
            ),
        )
    }

    // ------------------------------------- E3: permission.replied keeps reply

    @Test
    fun permissionRepliedParsesAndKeepsTheReply() {
        assertEquals(
            ChatStore.Event.PermissionReplied("per_1", "always"),
            parse(
                "permission.replied",
                """{"type":"permission.replied","properties":
                   {"sessionID":"ses_1","requestID":"per_1","reply":"always"}}""",
            ),
        )
        // An older server may omit `reply`: the removal must still happen.
        assertEquals(
            ChatStore.Event.PermissionReplied("per_1", ""),
            parse("permission.replied", """{"properties":{"sessionID":"ses_1","requestID":"per_1"}}"""),
        )
        assertNull(parse("permission.replied", """{"properties":{"sessionID":"ses_1"}}"""))
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

    // ------------------------------------- E3: question.replied keeps answers

    @Test
    fun questionRepliedKeepsTheAnswerMatrix() {
        assertEquals(
            ChatStore.Event.QuestionResolved("que_1", listOf(listOf("a.kt", "b.kt"), listOf("yes"))),
            parse(
                "question.replied",
                """{"type":"question.replied","properties":{"sessionID":"ses_1","requestID":"que_1",
                   "answers":[["a.kt","b.kt"],["yes"]]}}""",
            ),
        )
        // `answers` absent / malformed → an empty answer list, never a crash.
        assertEquals(
            ChatStore.Event.QuestionResolved("que_1"),
            parse("question.replied", """{"properties":{"sessionID":"ses_1","requestID":"que_1"}}"""),
        )
        assertEquals(
            ChatStore.Event.QuestionResolved("que_1"),
            parse(
                "question.replied",
                """{"properties":{"sessionID":"ses_1","requestID":"que_1","answers":"nope"}}""",
            ),
        )
        assertEquals(
            ChatStore.Event.QuestionResolved("que_1", listOf(emptyList())),
            parse(
                "question.replied",
                """{"properties":{"sessionID":"ses_1","requestID":"que_1","answers":[[]]}}""",
            ),
        )
    }

    @Test
    fun questionRejectedIsDistinguishableFromAnEmptyAnswer() {
        assertEquals(
            ChatStore.Event.QuestionResolved("que_2", rejected = true),
            parse(
                "question.rejected",
                """{"type":"question.rejected","properties":{"sessionID":"ses_1","requestID":"que_2"}}""",
            ),
        )
    }

    @Test
    fun questionResolutionsAreStoredAndIdempotent() {
        var s = ChatStore.reduce(
            ChatStore.ChatState(sessionID = "ses_1"),
            ChatStore.Event.QuestionAsked(
                OpenCodeClient.QuestionRequest(
                    id = "que_1",
                    sessionID = "ses_1",
                    questions = listOf(
                        OpenCodeClient.QuestionRequest.Info(
                            question = "Which file?",
                            header = "File",
                            options = emptyList(),
                            multiple = false,
                            custom = true,
                        ),
                    ),
                    tool = null,
                ),
            ),
        )
        s = ChatStore.reduce(s, ChatStore.Event.QuestionResolved("que_1", listOf(listOf("a.kt"))))
        assertTrue(s.pendingQuestions.isEmpty())
        assertEquals(listOf(listOf("a.kt")), s.questionResolution("que_1")?.answers)
        assertFalse(s.questionResolution("que_1")!!.rejected)

        assertEquals(s, ChatStore.reduce(s, ChatStore.Event.QuestionResolved("que_1", listOf(listOf("a.kt")))))
        assertEquals(s, ChatStore.reduce(s, ChatStore.Event.QuestionResolved("que_other")))
    }

    // ------------------------------------------------------------ wiring order

    @Test
    fun aSignalFrameOnTheGlobalStreamKeepsItsSessionRouting() {
        // `/global/event` wraps the payload: the signal must survive unwrapping
        // and still carry its session / subject (`GlobalEventTest` covers the
        // envelope itself).
        val frame = JSONObject(
            """{"directory":"/w","payload":{"id":"e1","type":"session.deleted",
               "properties":{"sessionID":"ses_x","info":{"id":"ses_x"}}}}""",
        )
        val inner = ChatStore.unwrapGlobal(frame) ?: error("must unwrap")
        val event = ChatStore.parseEvent(inner.optString("type"), inner)
        assertEquals(
            ChatStore.Event.ServerSignal(ChatStore.Signal.SESSION_DELETED, sessionID = "ses_x"),
            event,
        )
        // The gate that drops a foreign session's transcript would drop this
        // frame too — which is exactly why the owner reads signals first.
        assertFalse(ChatStore.acceptsSession("ses_mine", inner))
        assertNotNull(ChatStore.Signal.ofWireType("session.deleted"))
    }
}
