package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.timeline.TimelineRows
import com.xiufeigo.opencodeshell.v2.V2EventAdapter
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v73, emulator-5556: Stop mid-stream left the interrupted turn showing only
 * the user bubble + timestamp — no `Interrupted` marker and no partial output.
 *
 * The live `session.step.failed` frame (the abort error `{type:"aborted"}`)
 * used to adapt to a bare `RunStatusChanged(IDLE)`: the assistant row never
 * learned `interrupted` from the stream, so the marker depended entirely on
 * the abort's snapshot re-read winning a race against the server's error
 * write — and the follow-up frames only schedule a *throttled* re-read, which
 * the abort's own read usually suppresses. The row now settles from the frame
 * itself ([ChatStore.Event.AssistantInterrupted]), the snapshot staying the
 * second source it already was.
 */
class StopLiveInterruptTest {

    private fun adapt(type: String, data: String): List<ChatStore.Event> =
        V2EventAdapter.adapt(
            type,
            JSONObject("""{"id":"evt_1","type":"$type","created":1700000000000,"data":$data}"""),
        )

    private fun textPart(id: String, text: String) =
        ChatStore.UiPart(id = id, type = "text", text = text)

    private fun reduce(vararg events: ChatStore.Event) =
        events.fold(ChatStore.ChatState()) { state, event -> ChatStore.reduce(state, event) }

    // ------------------------------------------------- the frame mapping

    @Test
    fun `an aborted step failure settles the row and idles the turn`() {
        val events = adapt(
            "session.step.failed",
            """{"assistantMessageID":"msg_a","error":{"type":"aborted","message":"Turn interrupted"}}""",
        )
        val flag = events.filterIsInstance<ChatStore.Event.AssistantInterrupted>().single()
        assertEquals("msg_a", flag.messageID)
        assertEquals("Turn interrupted", flag.error?.message)
        assertEquals("aborted", flag.error?.type)
        assertEquals(1700000000000L, flag.timeCompleted)
        assertTrue(
            "the Stop button still reverts on the same frame",
            events.filterIsInstance<ChatStore.Event.RunStatusChanged>()
                .single().status == ChatStore.RunStatus.IDLE,
        )
    }

    @Test
    fun `a failed step that is not an interrupt raises the error as before`() {
        val events = adapt(
            "session.step.failed",
            """{"assistantMessageID":"msg_a","error":{"type":"provider.auth","message":"denied"}}""",
        )
        assertTrue(events.none { it is ChatStore.Event.AssistantInterrupted })
        val status = events.filterIsInstance<ChatStore.Event.RunStatusChanged>().single()
        assertEquals(ChatStore.RunStatus.ERROR, status.status)
    }

    @Test
    fun `a step failure without an assistant row maps to nothing new`() {
        assertTrue(adapt("session.step.failed", """{"error":{"type":"aborted"}}""").isEmpty())
    }

    // ------------------------------------------- the settled row renders

    @Test
    fun `a stopped turn keeps its streamed text and gains the divider`() {
        val state = reduce(
            ChatStore.Event.PartUpdated("msg_a", textPart("msg_a:text:0", "partial")),
            ChatStore.Event.PartDelta("msg_a", "msg_a:text:0", "text", " essay"),
            ChatStore.Event.AssistantInterrupted("msg_a"),
        )
        val row = state.messages.single { it.id == "msg_a" }
        assertEquals("partial essay", row.parts.single { it.type == "text" }.text)
        assertTrue(row.interrupted)
        val rows = TimelineRows.build(state.messages)
        assertEquals(
            "the divider anchors on the interrupted turn",
            listOf("msg_a"),
            rows.filterIsInstance<TimelineRows.Row.Separator>()
                .filter { it.kind == TimelineRows.SeparatorKind.INTERRUPTED }
                .map { it.anchorID },
        )
        assertTrue(
            "an interrupt is a separator, never an error card",
            rows.filterIsInstance<TimelineRows.Row.Error>().isEmpty(),
        )
    }

    @Test
    fun `a stopped turn that streamed nothing leaves no residue`() {
        val state = reduce(
            ChatStore.Event.PartUpdated(
                "msg_a",
                ChatStore.UiPart(id = "msg_a:step:1", type = "step-start"),
            ),
            ChatStore.Event.AssistantInterrupted("msg_a"),
        )
        assertTrue(
            "bookkeeping parts alone are the Stop residue the store drops",
            state.messages.none { it.id == "msg_a" },
        )
    }

    @Test
    fun `the flag on an unknown row waits for the snapshot`() {
        val state = reduce(ChatStore.Event.AssistantInterrupted("msg_missing"))
        assertTrue(state.messages.isEmpty())
    }

    @Test
    fun `the v73 turn reads as user bubble plus marker`() {
        val user = ChatStore.UiMessage(
            id = "msg_u",
            role = "user",
            timeCreated = 0,
            parts = listOf(textPart("msg_u:text:0", "Write a long essay")),
        )
        val parked = user.copy(id = "admit_1", role = ChatStore.PENDING_ROLE)
        val streamed = reduce(
            ChatStore.Event.Snapshot(listOf(user)),
            ChatStore.Event.PartUpdated("msg_a", textPart("msg_a:text:0", "The history")),
            ChatStore.Event.InboxPendingSteers(listOf(parked)),
            ChatStore.Event.AssistantInterrupted("msg_a"),
        )
        assertTrue(streamed.messages.any { it.id == "msg_u" })
        assertTrue(streamed.messages.single { it.id == "msg_a" }.interrupted)
        val rows = TimelineRows.build(streamed.messages)
        assertTrue(
            rows.filterIsInstance<TimelineRows.Row.Separator>()
                .any { it.kind == TimelineRows.SeparatorKind.INTERRUPTED && it.anchorID == "msg_a" },
        )
    }
}
