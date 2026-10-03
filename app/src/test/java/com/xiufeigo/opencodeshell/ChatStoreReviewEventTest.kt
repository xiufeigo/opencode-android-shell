package com.xiufeigo.opencodeshell

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `session.diff` / `todo.updated` store semantics: the `reduce` session gating
 * (global stream safety — a foreign session's diff must never land in this
 * screen's state). The v1 SSE parse layer is gone with `parseEvent`; v2
 * producers construct these events directly.
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
