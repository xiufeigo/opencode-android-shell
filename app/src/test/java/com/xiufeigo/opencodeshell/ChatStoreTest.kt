package com.xiufeigo.opencodeshell

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        assertEquals("v2", merged.parts.first { it.id == "p1" }.text)
        assertEquals("stream", merged.parts.first { it.id == "p2" }.text)
    }

    @Test
    fun partUpdatedKeepsToolNameWhenLaterFramesOmitIt() {
        val started = ChatStore.UiPart(id = "c1", type = "tool", toolName = "shell", toolStatus = "streaming")
        var s = ChatStore.reduce(state(), ChatStore.Event.PartUpdated("msg_1", started))
        // v2 input.ended / called / success payloads carry no tool name.
        s = ChatStore.reduce(
            s,
            ChatStore.Event.PartUpdated(
                "msg_1",
                ChatStore.UiPart(id = "c1", type = "tool", toolStatus = "running", toolInput = "{}"),
            ),
        )
        s = ChatStore.reduce(
            s,
            ChatStore.Event.PartUpdated(
                "msg_1",
                ChatStore.UiPart(id = "c1", type = "tool", toolStatus = "completed", toolOutput = "ok"),
            ),
        )
        val part = s.messages.single().parts.single()
        assertEquals("shell", part.toolName)
        assertEquals("completed", part.toolStatus)
        assertEquals("ok", part.toolOutput)

        // An incoming name still wins.
        s = ChatStore.reduce(
            s,
            ChatStore.Event.PartUpdated("msg_1", ChatStore.UiPart(id = "c1", type = "tool", toolName = "tar")),
        )
        assertEquals("tar", s.messages.single().parts.single().toolName)
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

    // --------------------------------------- initial model-selection notice

    private fun modelNotice(id: String, time: Long) =
        ChatStore.UiMessage(
            id = id,
            role = "assistant",
            timeCreated = time,
            parts = listOf(ChatStore.UiPart(id = "$id:notice", type = "model-switched")),
        )

    @Test
    fun initialModelSelectionLeavesAnEmptyTranscriptEmpty() {
        // Upstream `data.ts` drops the `session.model.selected` marker while
        // the session has no message list: the initial frame names the
        // current model, it is not a switch, so an empty session must not
        // open with a stray "Model changed" row (5554/5560).
        val s = ChatStore.reduce(
            ChatStore.ChatState(),
            ChatStore.Event.MessageUpdated(modelNotice("msg_evt1", 100)),
        )
        assertTrue(s.messages.isEmpty())
    }

    @Test
    fun modelSwitchOntoATranscriptStillLands() {
        val s = ChatStore.reduce(
            state(message("msg_1", 100, listOf(part("p1", "hello")), role = "user")),
            ChatStore.Event.MessageUpdated(modelNotice("msg_evt2", 200)),
        )
        assertEquals(listOf("msg_1", "msg_evt2"), s.messages.map { it.id })
    }

    @Test
    fun ordinaryRowsStillLandOnAnEmptyTranscript() {
        // The gate names the pure `model-switched` notice shape only: the
        // first real row of a session is never dropped by it.
        val s = ChatStore.reduce(
            ChatStore.ChatState(),
            ChatStore.Event.MessageUpdated(message("msg_1", 100, listOf(part("p1", "hello")))),
        )
        assertEquals(listOf("msg_1"), s.messages.map { it.id })
    }
}
