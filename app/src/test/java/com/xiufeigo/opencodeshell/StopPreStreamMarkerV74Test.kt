package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.timeline.TimelineRows
import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.V2EventAdapter
import com.xiufeigo.opencodeshell.v2.V2Transport
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v74, emulator-5556: two long-prompt turns were stopped mid-air and left
 * **nothing** on screen — the user bubble, then blank space: no `Interrupted`
 * divider, no partial output, no assistant row at all.
 *
 * The frames behind that turn, captured with curl against 2.0.18/4095 while
 * `opencode-go/space-bunny-free` hung for 65 s with no output:
 *
 * - live: `session.execution.interrupted` + `{sessionID, reason:"user"}` —
 *   no `session.step.failed` at all, because no *step* ever started, so the
 *   v73 carrier ([ChatStore.Event.AssistantInterrupted], keyed on
 *   `assistantMessageID`) never fired;
 * - durable (`GET /api/session/{id}/message?order=asc`), the turn's only
 *   record: `user msg_0ef247fed…` then `idle
 *   {"outcome":"interrupted"} msg_0ef248c9a…`, three seconds apart, **no
 *   assistant row in between** — while a turn stopped *while* streaming had
 *   `assistant {error:{type:"aborted"}}` + the same `idle` marker.
 *
 * The `idle` row is the server's own end-of-turn marker
 * (`schema/src/session-message.ts:288-292`, projected by
 * `core/src/session/projector.ts:665` for every terminal). The shell projected
 * it as an unknown notice type — an assistant-role row whose single part had
 * empty text — so the Stop's one fact went nowhere. It is now folded through
 * [ChatStore.applyTurnMarker] by both the snapshot and the live frame.
 */
class StopPreStreamMarkerV74Test {

    private fun adapt(type: String, data: String): List<ChatStore.Event> =
        V2EventAdapter.adapt(
            type,
            JSONObject("""{"id":"evt_1","type":"$type","created":1700000000000,"data":$data}"""),
        )

    private fun reduce(vararg events: ChatStore.Event) =
        events.fold(ChatStore.ChatState()) { state, event -> ChatStore.reduce(state, event) }

    private fun userRow(id: String = "msg_u", at: Long = 1_000L) = ChatStore.UiMessage(
        id = id,
        role = "user",
        timeCreated = at,
        parts = listOf(ChatStore.UiPart(id = "$id:text", type = "text", text = "hello")),
    )

    private fun dividers(messages: List<ChatStore.UiMessage>): List<String> =
        TimelineRows.build(messages)
            .filterIsInstance<TimelineRows.Row.Separator>()
            .filter { it.kind == TimelineRows.SeparatorKind.INTERRUPTED }
            .map { it.anchorID }

    // ------------------------------------------------------- the live frame

    @Test
    fun `a user interrupt carries the marker even with no id to name`() {
        val events = adapt("session.execution.interrupted", """{"sessionID":"ses_1","reason":"user"}""")
        assertEquals(1700000000000L, events.filterIsInstance<ChatStore.Event.TurnInterrupted>().single().timeCompleted)
        assertEquals(
            "the composer still settles on the same frame",
            ChatStore.RunStatus.IDLE,
            events.filterIsInstance<ChatStore.Event.RunStatusChanged>().single().status,
        )
    }

    @Test
    fun `a shutdown interrupt resumes the same turn and marks nothing`() {
        val events = adapt("session.execution.interrupted", """{"sessionID":"ses_1","reason":"shutdown"}""")
        assertTrue(events.none { it is ChatStore.Event.TurnInterrupted })
        assertEquals(
            ChatStore.RunStatus.RUNNING,
            events.filterIsInstance<ChatStore.Event.RunStatusChanged>().single().status,
        )
    }

    // ------------------------------------------------- the store's own rule

    @Test
    fun `a turn with no assistant row gets the partless marker`() {
        val state = reduce(
            ChatStore.Event.Snapshot(listOf(userRow())),
            ChatStore.Event.TurnInterrupted(timeCompleted = 4_000L),
        )
        val marker = state.messages.single { it.id == ChatStore.interruptMarkerID("msg_u") }
        assertTrue(marker.interrupted)
        assertTrue("the marker draws nothing of its own", marker.parts.isEmpty())
        assertEquals("assistant", marker.role)
        assertEquals(4_000L, marker.timeCompleted)
        assertEquals(listOf(ChatStore.interruptMarkerID("msg_u")), dividers(state.messages))
    }

    @Test
    fun `a turn that streamed keeps its row and gains the divider`() {
        val state = reduce(
            ChatStore.Event.Snapshot(listOf(userRow())),
            ChatStore.Event.PartUpdated(
                "msg_a",
                ChatStore.UiPart(id = "msg_a:reasoning:0", type = "reasoning", text = "thinking…"),
            ),
            // The live frame's own stamp: the Stop lands after the row the
            // stream created (whose envelope carries the local clock).
            ChatStore.Event.TurnInterrupted(timeCompleted = System.currentTimeMillis() + 5_000L),
        )
        val row = state.messages.single { it.id == "msg_a" }
        assertTrue(row.interrupted)
        assertEquals("thinking…", row.parts.single().text)
        assertTrue(
            "no second row is invented for a turn that already has one",
            state.messages.none { it.id.startsWith(ChatStore.interruptMarkerID("msg_u")) },
        )
        assertEquals(listOf("msg_a"), dividers(state.messages))
    }

    @Test
    fun `a turn whose only output was residue becomes the marker`() {
        val state = reduce(
            ChatStore.Event.Snapshot(listOf(userRow())),
            ChatStore.Event.PartUpdated(
                "msg_a",
                ChatStore.UiPart(id = "msg_a:step:1", type = "step-start"),
            ),
            ChatStore.Event.TurnInterrupted(timeCompleted = System.currentTimeMillis() + 5_000L),
        )
        assertTrue(
            "the empty-bubble residue stays dropped",
            state.messages.none { it.id == "msg_a" },
        )
        assertTrue(
            "…and the Stop still shows its divider",
            state.messages.any { it.id == ChatStore.interruptMarkerID("msg_u") && it.interrupted },
        )
        assertEquals(listOf(ChatStore.interruptMarkerID("msg_u")), dividers(state.messages))
    }

    @Test
    fun `a succeeded marker changes nothing`() {
        val messages = listOf(userRow())
        assertEquals(
            messages,
            ChatStore.applyTurnMarker(messages, ChatStore.TurnMarker(outcome = "succeeded", time = 4_000L)),
        )
    }

    @Test
    fun `the marker is idempotent across both carriers`() {
        val once = reduce(
            ChatStore.Event.Snapshot(listOf(userRow())),
            ChatStore.Event.TurnInterrupted(timeCompleted = 4_000L),
        )
        val twice = reduce(
            ChatStore.Event.Snapshot(listOf(userRow())),
            ChatStore.Event.TurnInterrupted(timeCompleted = 4_000L),
            ChatStore.Event.TurnInterrupted(timeCompleted = 4_000L),
        )
        assertEquals(once.messages.size, twice.messages.size)
        val marker = ChatStore.interruptMarkerID("msg_u")
        assertEquals(1, twice.messages.count { it.id == marker })
    }

    @Test
    fun `a marker only closes the turn it follows`() {
        val later = userRow(id = "msg_u2", at = 9_000L)
        val state = reduce(
            ChatStore.Event.Snapshot(listOf(userRow(), later)),
            ChatStore.Event.TurnInterrupted(timeCompleted = 4_000L),
        )
        assertEquals(
            "the marker sits between the two user rows, not at the tail",
            listOf("msg_u", ChatStore.interruptMarkerID("msg_u"), "msg_u2"),
            state.messages.map { it.id },
        )
        assertEquals(listOf(ChatStore.interruptMarkerID("msg_u")), dividers(state.messages))
    }

    @Test
    fun `a second stop marks its own turn, not the previous marker`() {
        val state = reduce(
            ChatStore.Event.Snapshot(listOf(userRow())),
            ChatStore.Event.TurnInterrupted(timeCompleted = 4_000L),
            ChatStore.Event.MessageUpdated(userRow(id = "msg_u2", at = 9_000L)),
            ChatStore.Event.TurnInterrupted(timeCompleted = 12_000L),
        )
        assertEquals(
            listOf(
                "msg_u",
                ChatStore.interruptMarkerID("msg_u"),
                "msg_u2",
                ChatStore.interruptMarkerID("msg_u2"),
            ),
            state.messages.map { it.id },
        )
        assertEquals(
            listOf(ChatStore.interruptMarkerID("msg_u"), ChatStore.interruptMarkerID("msg_u2")),
            dividers(state.messages),
        )
    }

    // -------------------------------------------- the snapshot projection

    private fun snapshot(transport: V2Transport): List<ChatStore.UiMessage> =
        transport.snapshotMessages("ses_1", limit = 50)

    /** The measured pre-stream Stop page, newest first — the wire's own order. */
    private fun enqueuePreStreamStop(server: MockWebServer) {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"data":[
                  {"id":"msg_idle","time":{"created":1790718545050},"type":"idle","outcome":"interrupted"},
                  {"id":"msg_u","time":{"created":1790718541855},"text":"hello","type":"user"}
                ],"cursor":{"previous":null,"next":null}}
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `the durable idle row becomes the marker instead of an empty notice`() {
        MockWebServer().use { server ->
            enqueuePreStreamStop(server)
            val transport = V2Transport(V2Api(server.url("/"), http = OkHttpClient()))

            val rows = snapshot(transport)

            assertEquals(
                "the idle row is a turn marker, never a row of its own",
                listOf("msg_u", ChatStore.interruptMarkerID("msg_u")),
                rows.map { it.id },
            )
            val marker = rows.single { it.id == ChatStore.interruptMarkerID("msg_u") }
            assertTrue(marker.interrupted)
            assertTrue(marker.parts.isEmpty())
            assertEquals("assistant", marker.role)
            assertEquals(1790718545050L, marker.timeCompleted)
            assertEquals(listOf(ChatStore.interruptMarkerID("msg_u")), dividers(rows))

            val request = server.takeRequest()
            assertEquals("/api/session/ses_1/message?limit=50&order=desc", request.path)
        }
    }

    @Test
    fun `a streamed abort pins the row the idle row closes, once`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {"data":[
                      {"id":"msg_idle","time":{"created":1790718331947},"type":"idle","outcome":"interrupted"},
                      {"id":"msg_a","time":{"created":1790718281063},"type":"assistant","agent":"build",
                       "content":[{"type":"reasoning","text":"thinking…"}],
                       "error":{"type":"aborted","message":"Step interrupted"}},
                      {"id":"msg_u","time":{"created":1790718267127},"text":"Write","type":"user"}
                    ],"cursor":{"previous":null,"next":null}}
                    """.trimIndent(),
                ),
            )
            val transport = V2Transport(V2Api(server.url("/"), http = OkHttpClient()))

            val rows = snapshot(transport)

            assertEquals(listOf("msg_a", "msg_u"), rows.map { it.id }.sorted())
            assertTrue(rows.single { it.id == "msg_a" }.interrupted)
            assertEquals(listOf("msg_a"), dividers(rows))
        }
    }

    @Test
    fun `a successful turn's idle row is not a row at all`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {"data":[
                      {"id":"msg_idle","time":{"created":300},"type":"idle","outcome":"succeeded"},
                      {"id":"msg_u","time":{"created":100},"text":"hi","type":"user"}
                    ],"cursor":{"previous":null,"next":null}}
                    """.trimIndent(),
                ),
            )
            val transport = V2Transport(V2Api(server.url("/"), http = OkHttpClient()))

            val rows = snapshot(transport)

            assertEquals(listOf("msg_u"), rows.map { it.id })
            assertTrue(dividers(rows).isEmpty())
            assertNull(rows.single().timeCompleted)
        }
    }
}
