package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.V2EventAdapter
import com.xiufeigo.opencodeshell.v2.V2Transport
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v68 C4 (5558): a **new** session opened with a 「Model changed」 row over its
 * first user turn, and the empty-transcript gate agent-688 added did not stop
 * it.
 *
 * The row is not a coincidence of timing. This shell creates a session with no
 * model at all — `ChatViewModel.createSession` posts `model = null` — and then
 * pins the composer's pick through `switchModel`
 * (`ChatViewModel.createSession`, the `selectedModelPin()` block). The server
 * publishes `ModelSelected` for that pin because the session's model really did
 * change, from *nothing* to the pick: `core/src/session/session.ts:89-101`
 * returns early only on an identical ref, and names what it changed **from** in
 * `previous`. So the first model a session ever gets is a `ModelSelected` frame
 * with no `previous`, and it lands over the first prompt.
 *
 * Upstream's own gate does not cover it
 * (`client/src/solid/data.ts:668`, `if (!store.session.message[sessionID])
 * return`): a synced *empty* session stores `[]`, which is truthy, and the first
 * user message can land before the frame — both of which keep the transcript
 * test from being the signal. The signal is the payload: a selection with
 * nothing to have selected from is not a switch.
 *
 * Two paths build the row, so both are pinned here — the live frame, and the
 * durable row the server persists for every `ModelSelected` and hands back
 * through `GET /session/{id}/message` (without the second fix, a reopened new
 * session grew the row back out of its own history).
 */
class ModelSwitchNoticeV69Test {

    // ------------------------------------------------------------- fixtures

    /** The shape a session created by this shell produces: model, no `previous`. */
    private fun initialSelectionFrame(): JSONObject = JSONObject(
        """
        {"id":"evt_pin1","type":"session.model.selected","created":1790435677683,
         "data":{"sessionID":"ses_new","model":{"providerID":"anthropic","id":"claude-x","variant":null}}}
        """.trimIndent(),
    )

    /** A real switch: the server names the model it moved off. */
    private fun switchFrame(): JSONObject = JSONObject(
        """
        {"id":"evt_sw1","type":"session.model.selected","created":1790435677684,
         "data":{"sessionID":"ses_new",
                 "model":{"providerID":"anthropic","id":"claude-y","variant":null},
                 "previous":{"providerID":"anthropic","id":"claude-x"}}}
        """.trimIndent(),
    )

    private fun noticeRows(events: List<ChatStore.Event>): List<ChatStore.UiMessage> =
        events.filterIsInstance<ChatStore.Event.MessageUpdated>()
            .map { it.message }
            .filter { message -> message.parts.any { it.type == "model-switched" } }

    private fun selectionChanges(events: List<ChatStore.Event>): List<ChatStore.Event.SessionSelectionChanged> =
        events.filterIsInstance<ChatStore.Event.SessionSelectionChanged>()

    // ------------------------------------------------------- the live frame

    @Test
    fun theFirstModelOfANewSessionRecordsTheSelectionButWritesNoRow() {
        val events = V2EventAdapter.adapt("session.model.selected", initialSelectionFrame())
        assertTrue(
            "a selection with no previous must not open the transcript: $events",
            noticeRows(events).isEmpty(),
        )
        // The selection itself still lands — the composer chip and the ring read
        // it, and a session whose model never got recorded would show the wrong
        // one until the next snapshot.
        val selection = selectionChanges(events).single()
        assertEquals("ses_new", selection.sessionID)
        assertEquals("anthropic/claude-x", selection.model)
    }

    @Test
    fun aRealSwitchStillWritesTheRow() {
        val events = V2EventAdapter.adapt("session.model.selected", switchFrame())
        val row = noticeRows(events).single()
        assertEquals("msg_sw1", row.id)
        assertEquals("model-switched", row.parts.single().type)
        assertEquals("anthropic/claude-y", row.model)
        assertEquals("ses_new", selectionChanges(events).single().sessionID)
    }

    @Test
    fun anExplicitNullPreviousIsStillTheInitialSelection() {
        // A server that serialises the absent ref as `null` instead of omitting
        // it says the same thing: there was no model to change from.
        val frame = JSONObject(
            """
            {"id":"evt_pin2","type":"session.model.selected","created":1,
             "data":{"sessionID":"ses_new","model":{"providerID":"p","id":"m"},"previous":null}}
            """.trimIndent(),
        )
        val events = V2EventAdapter.adapt("session.model.selected", frame)
        assertTrue(noticeRows(events).isEmpty())
        assertEquals("p/m", selectionChanges(events).single().model)
    }

    @Test
    fun anAgentSwitchIsNotAffectedByTheModelRule() {
        // The gate is about `model-switched` only: an `agent-switched` row has
        // its own payload shape and must keep rendering.
        val frame = JSONObject(
            """
            {"id":"evt_ag1","type":"session.agent.selected","created":1,
             "data":{"sessionID":"ses_new","agent":"build"}}
            """.trimIndent(),
        )
        val events = V2EventAdapter.adapt("session.agent.selected", frame)
        assertEquals("agent-switched", events.filterIsInstance<ChatStore.Event.MessageUpdated>()
            .single().message.parts.single().type)
    }

    // ---------------------------------------------------- the durable row

    private val transport = V2Transport(V2Api("http://127.0.0.1:1/".toHttpUrl()))

    private fun snapshot(row: JSONObject): ChatStore.UiMessage? =
        with(transport) { V2Api.Message.fromJson(row).toUi() }

    @Test
    fun thePersistedInitialSelectionIsNotProjectedBackIntoTheTranscript() {
        val row = JSONObject(
            """
            {"id":"msg_pin1","type":"model-switched","time":{"created":1790435677683},
             "model":{"providerID":"anthropic","id":"claude-x"}}
            """.trimIndent(),
        )
        assertNull(snapshot(row))
    }

    @Test
    fun thePersistedRealSwitchIsProjectedAsTheNoticeRow() {
        val row = JSONObject(
            """
            {"id":"msg_sw1","type":"model-switched","time":{"created":1790435677684},
             "model":{"providerID":"anthropic","id":"claude-y"},
             "previous":{"providerID":"anthropic","id":"claude-x"}}
            """.trimIndent(),
        )
        val message = snapshot(row) ?: error("the switch row must still project")
        assertEquals("msg_sw1", message.id)
        assertEquals("model-switched", message.parts.single().type)
        assertEquals("anthropic/claude-y", message.model)
    }

    @Test
    fun otherNoticeRowsKeepTheirSnapshotProjection() {
        // The rule is scoped to `model-switched`: a `location-switched` /
        // `agent-switched` durable row has no `previous` either and must not be
        // swallowed by a rule meant for the model selection.
        for (type in listOf("agent-switched", "location-switched", "shell", "system")) {
            val row = JSONObject(
                """{"id":"msg_$type","type":"$type","time":{"created":1}}""",
            )
            assertNotNull("$type must still project", snapshot(row))
        }
    }

    // --------------------------------------------- the store gate stays put

    @Test
    fun theEmptyTranscriptGateIsUnchanged() {
        // agent-688's gate is upstream's (`data.ts:668`) and is still correct for
        // a switch onto a transcript that has none; this fix does not replace it,
        // it answers a case the transcript cannot see.
        val notice = ChatStore.UiMessage(
            id = "msg_sw2",
            role = "assistant",
            timeCreated = 1L,
            parts = listOf(ChatStore.UiPart(id = "msg_sw2:notice", type = "model-switched")),
        )
        val empty = ChatStore.reduce(ChatStore.ChatState(), ChatStore.Event.MessageUpdated(notice))
        assertTrue(empty.messages.isEmpty())
        val seeded = ChatStore.reduce(
            ChatStore.ChatState().copy(
                messages = listOf(
                    ChatStore.UiMessage(
                        id = "msg_u1",
                        role = "user",
                        timeCreated = 0L,
                        parts = listOf(ChatStore.UiPart(id = "msg_u1:text", type = "text", text = "hi")),
                    ),
                ),
            ),
            ChatStore.Event.MessageUpdated(notice),
        )
        assertEquals(2, seeded.messages.size)
    }
}
