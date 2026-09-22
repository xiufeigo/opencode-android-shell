package com.xiufeigo.opencodeshell

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatStoreTest {

    private fun part(id: String, text: String) =
        ChatStore.UiPart(id = id, type = "text", text = text)

    private fun message(id: String, time: Long, parts: List<ChatStore.UiPart>, role: String = "assistant") =
        ChatStore.UiMessage(id = id, role = role, timeCreated = time, parts = parts)

    private fun state(vararg messages: ChatStore.UiMessage) =
        ChatStore.ChatState(messages = messages.toList())

    @Test
    fun snapshotMergesWithoutClobberingStreamedParts() {
        var s = ChatStore.reduce(
            ChatStore.ChatState(),
            ChatStore.Event.Snapshot(listOf(message("msg_1", 100, listOf(part("p1", "v1"))))),
        )
        // Deltas landed during the reconnect window...
        s = ChatStore.reduce(s, ChatStore.Event.PartUpdated("msg_1", part("p1", "v2")))
        s = ChatStore.reduce(s, ChatStore.Event.PartUpdated("msg_1", part("p2", "stream")))
        // ...and the reconnect snapshot replays the stale p1 without p2.
        s = ChatStore.reduce(
            s,
            ChatStore.Event.Snapshot(listOf(message("msg_1", 100, listOf(part("p1", "v1"))))),
        )
        val merged = s.messages.single { it.id == "msg_1" }
        assertEquals(listOf("p1", "p2"), merged.parts.map { it.id })
        assertEquals("v1", merged.parts.first { it.id == "p1" }.text)
        assertEquals("stream", merged.parts.first { it.id == "p2" }.text)
    }

    @Test
    fun partUpdatedReplacesAppendsAndSynthesizesMissingEnvelopes() {
        val m0 = message("msg_0", 50, listOf(part("p0", "a")), role = "user")
        val m1 = message("msg_1", 100, listOf(part("p1", "v1")))
        var s = ChatStore.reduce(state(m0, m1), ChatStore.Event.PartUpdated("msg_1", part("p1", "v2")))
        assertEquals("v2", s.messages.single { it.id == "msg_1" }.parts.single().text)

        s = ChatStore.reduce(s, ChatStore.Event.PartUpdated("msg_1", part("p3", "new")))
        assertEquals(listOf("p1", "p3"), s.messages.single { it.id == "msg_1" }.parts.map { it.id })

        val held = part("ps", "held")
        s = ChatStore.reduce(s, ChatStore.Event.PartUpdated("msg_9", held))
        val envelope = s.messages.single { it.id == "msg_9" }
        assertEquals("assistant", envelope.role)
        assertEquals(listOf(held), envelope.parts)
        // Messages stay ordered by timeCreated.
        assertEquals(listOf("msg_0", "msg_1", "msg_9"), s.messages.map { it.id })
    }

    @Test
    fun replayedEventsAreIdempotent() {
        val initial = state(message("msg_1", 100, listOf(part("p1", "v1"))))
        val partEvent = ChatStore.Event.PartUpdated("msg_1", part("p1", "v2"))
        val afterPart = ChatStore.reduce(initial, partEvent)
        assertEquals(afterPart, ChatStore.reduce(afterPart, partEvent))

        val snapshotEvent = ChatStore.Event.Snapshot(
            listOf(message("msg_1", 100, listOf(part("p1", "v1")))),
        )
        val afterSnapshot = ChatStore.reduce(initial, snapshotEvent)
        assertEquals(afterSnapshot, ChatStore.reduce(afterSnapshot, snapshotEvent))

        val removeEvent = ChatStore.Event.MessageRemoved("msg_1")
        val afterRemove = ChatStore.reduce(initial, removeEvent)
        assertEquals(afterRemove, ChatStore.reduce(afterRemove, removeEvent))

        val statusEvent = ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.RUNNING)
        val afterStatus = ChatStore.reduce(initial, statusEvent)
        assertEquals(afterStatus, ChatStore.reduce(afterStatus, statusEvent))
    }

    @Test
    fun messageUpdatedReplacesButKeepsStreamedWindowParts() {
        val streamed = ChatStore.UiPart(id = "p_tool", type = "tool", toolName = "bash", toolStatus = "running")
        var s = ChatStore.reduce(
            ChatStore.ChatState(),
            ChatStore.Event.Snapshot(listOf(message("msg_1", 100, listOf(part("p1", "v1"))))),
        )
        s = ChatStore.reduce(s, ChatStore.Event.PartUpdated("msg_1", streamed))
        s = ChatStore.reduce(s, ChatStore.Event.MessageUpdated(message("msg_1", 100, listOf(part("p1", "v2")))))

        val parts = s.messages.single().parts
        assertEquals(listOf("p1", "p_tool"), parts.map { it.id })
        assertEquals("v2", parts.first { it.id == "p1" }.text)
        assertEquals("bash", parts.first { it.id == "p_tool" }.toolName)
        assertEquals("running", parts.first { it.id == "p_tool" }.toolStatus)
    }

    @Test
    fun messageRemovedDropsOnlyTargetMessage() {
        var s = state(
            message("msg_a", 1, listOf(part("p", "x"))),
            message("msg_b", 2, listOf(part("q", "y"))),
        )
        s = ChatStore.reduce(s, ChatStore.Event.MessageRemoved("msg_a"))
        assertEquals(listOf("msg_b"), s.messages.map { it.id })
        s = ChatStore.reduce(s, ChatStore.Event.MessageRemoved("msg_missing"))
        assertEquals(listOf("msg_b"), s.messages.map { it.id })
    }

    @Test
    fun runStatusChangedCarriesStatusAndError() {
        var s = ChatStore.reduce(
            ChatStore.ChatState(),
            ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.RUNNING),
        )
        assertEquals(ChatStore.RunStatus.RUNNING, s.runStatus)
        assertNull(s.error)
        s = ChatStore.reduce(s, ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.ERROR, "boom"))
        assertEquals(ChatStore.RunStatus.ERROR, s.runStatus)
        assertEquals("boom", s.error)
    }

    @Test
    fun parseSnapshotHandlesInfoWrappedAndBareMessages() {
        val body = """
            [
              {"info":{"id":"m1","role":"assistant","time":{"created":100}},
               "parts":[{"id":"p1","type":"text","text":"wrapped"}]},
              {"id":"m2","role":"user","time":{"created":50},
               "parts":[{"id":"p2","type":"text","text":"bare"}]}
            ]
        """.trimIndent()
        val messages = ChatStore.parseSnapshot(body)
        assertEquals(2, messages.size)

        val wrapped = messages.first { it.id == "m1" }
        assertEquals("assistant", wrapped.role)
        assertEquals(100L, wrapped.timeCreated)
        assertEquals("wrapped", wrapped.parts.single().text)

        val bare = messages.first { it.id == "m2" }
        assertEquals("user", bare.role)
        assertEquals(50L, bare.timeCreated)
        assertEquals("bare", bare.parts.single().text)

        assertEquals(emptyList<ChatStore.UiMessage>(), ChatStore.parseSnapshot("   "))
    }

    @Test
    fun parseEventUnwrapsPropertiesEnvelope() {
        // Real wire format: {"type":"...","properties":{...}} (vendored SDK
        // reads t.properties.part / t.properties.info / t.properties.messageID).
        val partEvent = ChatStore.parseEvent(
            "message.part.updated",
            JSONObject(
                """{"type":"message.part.updated","properties":
                   {"sessionID":"ses_1","messageID":"msg_1",
                    "part":{"id":"prt_1","type":"text","text":"hi"}}}""",
            ),
        )
        assertEquals(ChatStore.Event.PartUpdated("msg_1", ChatStore.parsePart(JSONObject("""{"id":"prt_1","type":"text","text":"hi"}"""))), partEvent)

        val updated = ChatStore.parseEvent(
            "message.updated",
            JSONObject(
                """{"type":"message.updated","properties":
                   {"info":{"id":"msg_1","role":"assistant","time":{"created":5}},
                    "parts":[]}}""",
            ),
        )
        assertEquals("msg_1", (updated as ChatStore.Event.MessageUpdated).message.id)

        val removed = ChatStore.parseEvent(
            "message.removed",
            JSONObject("""{"type":"message.removed","properties":{"messageID":"msg_9"}}"""),
        )
        assertEquals(ChatStore.Event.MessageRemoved("msg_9"), removed)

        val idle = ChatStore.parseEvent(
            "session.idle",
            JSONObject("""{"type":"session.idle","properties":{"sessionID":"ses_1"}}"""),
        )
        assertEquals(ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.IDLE), idle)

        // Flat payloads (no properties) still parse.
        assertEquals(
            ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.IDLE),
            ChatStore.parseEvent("session.idle", JSONObject("""{"type":"session.idle"}""")),
        )
        // part envelope without messageID anywhere is dropped, not crashed on.
        assertNull(
            ChatStore.parseEvent(
                "message.part.updated",
                JSONObject("""{"type":"message.part.updated","properties":{"part":{"id":"x"}}}"""),
            ),
        )
    }

    @Test
    fun sessionIdOfReadsEnvelopeAndInfoShapes() {
        assertEquals("ses_1", ChatStore.sessionIdOf(JSONObject("""{"properties":{"id":"ses_1"}}""")))
        assertEquals(
            "ses_2",
            ChatStore.sessionIdOf(JSONObject("""{"properties":{"info":{"id":"ses_2"}}}""")),
        )
        assertEquals("ses_3", ChatStore.sessionIdOf(JSONObject("""{"id":"ses_3"}""")))
        assertNull(ChatStore.sessionIdOf(JSONObject("""{"properties":{}}""")))
    }

    @Test
    fun partDeltaAppendsTextAndDropsOrphans() {
        var s = ChatStore.reduce(
            ChatStore.ChatState(),
            ChatStore.Event.Snapshot(listOf(message("msg_1", 100, listOf(part("p1", "hello"))))),
        )
        s = ChatStore.reduce(s, ChatStore.Event.PartDelta("msg_1", "p1", "text", " world"))
        assertEquals("hello world", s.messages.single().parts.single().text)

        // Orphan delta (missing message or part) is dropped, never crashes.
        val before = s
        assertEquals(before, ChatStore.reduce(s, ChatStore.Event.PartDelta("msg_none", "p1", "text", "x")))
        assertEquals(before, ChatStore.reduce(s, ChatStore.Event.PartDelta("msg_1", "p_none", "text", "x")))
        // Non-text fields are ignored (text-only reducer for now).
        assertEquals(before, ChatStore.reduce(s, ChatStore.Event.PartDelta("msg_1", "p1", "toolInput", "x")))
    }

    @Test
    fun parseEventHandlesPartDeltaAndSessionStatus() {
        val delta = ChatStore.parseEvent(
            "message.part.delta",
            JSONObject(
                """{"type":"message.part.delta","properties":
                   {"sessionID":"ses_1","messageID":"msg_1","partID":"p1","field":"text","delta":" more"}}""",
            ),
        )
        assertEquals(ChatStore.Event.PartDelta("msg_1", "p1", "text", " more"), delta)

        val busy = ChatStore.parseEvent(
            "session.status",
            JSONObject(
                """{"type":"session.status","properties":{"sessionID":"ses_1","status":{"type":"busy"}}}""",
            ),
        )
        assertEquals(ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.RUNNING), busy)

        // A retry frame is still "running" but now carries its own detail
        // (`ChatState.retry`, see ChatStoreWave4EventTest); it must NOT be
        // reported as an `error`, which would mount the red error card.
        val retry = ChatStore.parseEvent(
            "session.status",
            JSONObject(
                """{"type":"session.status","properties":{"sessionID":"ses_1","status":{"type":"retry"}}}""",
            ),
        ) as ChatStore.Event.RunStatusChanged
        assertEquals(ChatStore.RunStatus.RUNNING, retry.status)
        assertNull(retry.error)
        assertEquals(0, retry.retry?.attempt)

        val idle = ChatStore.parseEvent(
            "session.status",
            JSONObject(
                """{"type":"session.status","properties":{"sessionID":"ses_1","status":{"type":"idle"}}}""",
            ),
        )
        assertEquals(ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.IDLE), idle)

        // Unknown status type is dropped, not mapped.
        assertNull(
            ChatStore.parseEvent(
                "session.status",
                JSONObject("""{"type":"session.status","properties":{"status":{"type":"weird"}}}"""),
            ),
        )
    }
}
