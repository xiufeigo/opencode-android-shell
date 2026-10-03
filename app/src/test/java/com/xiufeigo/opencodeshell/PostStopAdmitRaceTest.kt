package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.timeline.TimelineRows
import com.xiufeigo.opencodeshell.v2.V2EventAdapter
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v75 P2, emulator-5556: after a Stop, the next sent message's user bubble did
 * not render in the live transcript — the timeline read as though it had lost
 * the last turn — while the server had the message intact. Switching away and
 * back (or scrolling up) revealed it.
 *
 * The suspect interaction is the post-interrupt refresh ordering: the abort's
 * `refreshSnapshot` in flight against the new message's admit row (v62
 * `admitHandoffRow`) and the interrupt markers (v74 `TurnMarker`). This suite
 * replays every application order of those three at the store level and pins
 * the invariant the fix requires: **a snapshot merge must never drop newer
 * local rows**, and a late interrupt of the stopped turn must never mark the
 * turn that came after it.
 */
class PostStopAdmitRaceTest {

    private fun userRow(id: String, at: Long, text: String = "vwrite a 200 word essay") =
        ChatStore.UiMessage(
            id = id,
            role = "user",
            timeCreated = at,
            parts = listOf(ChatStore.UiPart(id = "$id:text", type = "text", text = text)),
        )

    private fun assistantRow(id: String, at: Long, text: String = "1 2 3") =
        ChatStore.UiMessage(
            id = id,
            role = "assistant",
            timeCreated = at,
            parts = listOf(ChatStore.UiPart(id = "$id:text", type = "text", text = text)),
        )

    private fun reduce(state: ChatStore.ChatState, event: ChatStore.Event): ChatStore.ChatState =
        ChatStore.reduce(state, event)

    private fun interruptFrame(created: Long): List<ChatStore.Event> = V2EventAdapter.adapt(
        "session.execution.interrupted",
        JSONObject(
            """{"id":"evt_x","type":"session.execution.interrupted","created":$created,"data":{"sessionID":"ses_1","reason":"user"}}""",
        ),
    )

    /** The stopped turn, settled the way the abort's own refresh leaves it. */
    private fun stoppedTurn(): List<ChatStore.UiMessage> = listOf(
        userRow("msg_u1", 1_000L, "count to 100 slowly"),
        assistantRow("msg_a1", 1_001L).copy(interrupted = true, timeCompleted = 2_000L),
    )

    /** The abort's refresh: a snapshot that predates the next message. */
    private val staleSnapshot = ChatStore.Event.Snapshot(stoppedTurn())

    /** The submit's refresh once the server has published the next message. */
    private fun freshSnapshot(next: ChatStore.UiMessage) =
        ChatStore.Event.Snapshot(stoppedTurn() + next)

    private fun handoff(): ChatStore.UiMessage =
        handoffPromptRow("msg_u2", "vwrite a 200 word essay", 0, emptyList())

    private fun userBubblePresent(messages: List<ChatStore.UiMessage>): Boolean =
        messages.any { it.id == "msg_u2" && (it.role == "user" || ChatStore.isPendingRow(it)) }

    // --------------------------------------- the merge never drops the admit

    @Test
    fun `a stale snapshot landing after the admit keeps the handoff row`() {
        var state = ChatStore.ChatState()
        state = reduce(state, ChatStore.Event.InboxPendingSteers(listOf(handoff())))
        state = reduce(state, staleSnapshot)
        assertTrue(userBubblePresent(state.messages))
    }

    @Test
    fun `a stale snapshot landing before the admit still converges`() {
        var state = ChatStore.ChatState()
        state = reduce(state, staleSnapshot)
        state = reduce(state, ChatStore.Event.InboxPendingSteers(listOf(handoff())))
        assertTrue(userBubblePresent(state.messages))
    }

    @Test
    fun `every refresh order converges on the delivered message`() {
        val delivered = userRow("msg_u2", 3_000L)
        val orders = listOf(
            listOf(
                ChatStore.Event.InboxPendingSteers(listOf(handoff())),
                staleSnapshot,
                freshSnapshot(delivered),
            ),
            listOf(
                staleSnapshot,
                ChatStore.Event.InboxPendingSteers(listOf(handoff())),
                freshSnapshot(delivered),
            ),
            listOf(
                ChatStore.Event.InboxPendingSteers(listOf(handoff())),
                freshSnapshot(delivered),
                staleSnapshot,
            ),
        )
        for (events in orders) {
            var state = ChatStore.ChatState()
            for (event in events) state = reduce(state, event)
            assertTrue("order $events lost the turn", userBubblePresent(state.messages))
            assertEquals(
                "the handover leaves one row, never two",
                1,
                state.messages.count { it.id == "msg_u2" },
            )
            assertTrue(
                "the delivered message replaces the placeholder",
                state.messages.any { it.id == "msg_u2" && it.role == "user" },
            )
        }
    }

    // ------------------------- the stopped turn's late frame marks its own turn

    @Test
    fun `a late interrupt of the stopped turn never marks the next turn`() {
        val next = userRow("msg_u2", 5_000L)
        var state = ChatStore.ChatState()
        state = reduce(state, freshSnapshot(next))
        for (event in interruptFrame(created = 2_000L)) {
            if (event is ChatStore.Event.TurnInterrupted) state = reduce(state, event)
        }
        val rows = TimelineRows.build(state.messages)
        assertTrue(
            "the late Stop must not plant a divider on the next turn",
            rows.filterIsInstance<TimelineRows.Row.Separator>()
                .filter { it.kind == TimelineRows.SeparatorKind.INTERRUPTED }
                .none { it.anchorID == "msg_u2" || it.anchorID == ChatStore.interruptMarkerID("msg_u2") },
        )
        assertTrue(
            "the next turn's user row is untouched",
            state.messages.any { it.id == "msg_u2" && !it.interrupted },
        )
        assertTrue(userBubblePresent(state.messages))
    }

    @Test
    fun `the interrupt frame still settles the turn it stopped`() {
        var state = ChatStore.ChatState()
        state = reduce(
            state,
            ChatStore.Event.Snapshot(
                listOf(userRow("msg_u1", 1_000L), assistantRow("msg_a1", 1_001L)),
            ),
        )
        for (event in interruptFrame(created = 2_000L)) {
            if (event is ChatStore.Event.TurnInterrupted) state = reduce(state, event)
        }
        assertTrue(state.messages.single { it.id == "msg_a1" }.interrupted)
    }
}
