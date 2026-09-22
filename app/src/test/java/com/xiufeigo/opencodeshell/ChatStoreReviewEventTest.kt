package com.xiufeigo.opencodeshell

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `session.diff` / `todo.updated` SSE events: `ChatStore.parseEvent` parsing
 * (both envelope shapes) and the `reduce` session gating (global stream
 * safety — a foreign session's diff must never land in this screen's state).
 */
class ChatStoreReviewEventTest {

    private fun diffEvent(sessionID: String): ChatStore.Event.SessionDiff = ChatStore.Event.SessionDiff(
        sessionID,
        listOf(OpenCodeClient.FileDiff("a.kt", "@@ -1 +1 @@\n-x\n+y", 1, 1, "modified")),
    )

    private fun todoEvent(sessionID: String): ChatStore.Event.TodoUpdated = ChatStore.Event.TodoUpdated(
        sessionID,
        listOf(OpenCodeClient.TodoInfo("task", "pending", "low")),
    )

    // ------------------------------------------------------------------ parse

    @Test
    fun parseEventReadsSessionDiffFromPropertiesEnvelope() {
        val data = JSONObject(
            """{"type":"session.diff","properties":{"sessionID":"ses_1","diff":[
                {"file":"src/A.kt","patch":"@@ -1,1 +1,1 @@\n-old\n+new","additions":1,"deletions":1,"status":"modified"},
                {"file":"b.md","additions":3,"deletions":0,"status":"added"}
            ]}}""",
        )
        val event = ChatStore.parseEvent("session.diff", data)
        assertTrue(event is ChatStore.Event.SessionDiff)
        event as ChatStore.Event.SessionDiff
        assertEquals("ses_1", event.sessionID)
        assertEquals(2, event.diff.size)
        assertEquals("src/A.kt", event.diff[0].file)
        assertEquals(1, event.diff[0].additions)
        assertNull(event.diff[1].patch)
    }

    @Test
    fun parseEventReadsSessionDiffFromFlatPayload() {
        val data = JSONObject(
            """{"sessionID":"ses_2","diff":[{"file":"f","additions":1,"deletions":0}]}""",
        )
        val event = ChatStore.parseEvent("session.diff", data)
        assertTrue(event is ChatStore.Event.SessionDiff)
        assertEquals("ses_2", (event as ChatStore.Event.SessionDiff).sessionID)
        assertEquals(1, event.diff.size)
    }

    @Test
    fun parseEventReadsTodoUpdated() {
        val data = JSONObject(
            """{"type":"todo.updated","properties":{"sessionID":"ses_1","todos":[
                {"content":"write tests","status":"in_progress","priority":"high"}
            ]}}""",
        )
        val event = ChatStore.parseEvent("todo.updated", data)
        assertTrue(event is ChatStore.Event.TodoUpdated)
        event as ChatStore.Event.TodoUpdated
        assertEquals("ses_1", event.sessionID)
        assertEquals("write tests", event.todos.single().content)
        assertEquals("in_progress", event.todos.single().status)
    }

    @Test
    fun parseEventDropsDiffEventsWithoutArray() {
        assertNull(ChatStore.parseEvent("session.diff", JSONObject("""{"sessionID":"s"}""")))
        assertNull(ChatStore.parseEvent("todo.updated", JSONObject("""{"sessionID":"s"}""")))
    }

    // ----------------------------------------------------------------- reduce

    @Test
    fun sessionDiffReplacesTheWholeList() {
        var state = ChatStore.ChatState(sessionID = "ses_1")
        state = ChatStore.reduce(state, diffEvent("ses_1"))
        assertEquals(listOf("a.kt"), state.diff.map { it.file })
        // A later event carries the full list again — replacement, not merge.
        state = ChatStore.reduce(state, ChatStore.Event.SessionDiff("ses_1", emptyList()))
        assertTrue(state.diff.isEmpty())
    }

    @Test
    fun sessionDiffFromAnotherSessionIsGated() {
        val state = ChatStore.reduce(ChatStore.ChatState(sessionID = "ses_1"), diffEvent("ses_1"))
        val after = ChatStore.reduce(state, diffEvent("ses_other"))
        assertEquals(state, after)
        assertEquals(listOf("a.kt"), after.diff.map { it.file })
    }

    @Test
    fun sessionDiffWithoutRoutingInfoPasses() {
        // Same rule as `acceptsSession`: no sessionID on the event = no
        // routing info, so it must not be dropped on the floor.
        val state = ChatStore.reduce(ChatStore.ChatState(sessionID = "ses_1"), diffEvent(""))
        assertEquals(listOf("a.kt"), state.diff.map { it.file })
    }

    @Test
    fun sessionDiffAcceptedBeforeSessionKnown() {
        val state = ChatStore.reduce(ChatStore.ChatState(sessionID = null), diffEvent("ses_1"))
        assertEquals(1, state.diff.size)
    }

    @Test
    fun todoUpdatedReplacesAndGates() {
        var state = ChatStore.ChatState(sessionID = "ses_1")
        state = ChatStore.reduce(state, todoEvent("ses_1"))
        assertEquals(listOf("task"), state.todos.map { it.content })

        val gated = ChatStore.reduce(state, todoEvent("ses_other"))
        assertEquals(state, gated)

        val cleared = ChatStore.reduce(state, ChatStore.Event.TodoUpdated("ses_1", emptyList()))
        assertTrue(cleared.todos.isEmpty())
    }
}
