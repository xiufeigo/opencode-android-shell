package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.timeline.TimelineRows
import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.V2Transport
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Failure persistence across a session reopen (the multi-error card agent's
 * cross-boundary finding, `research/v8_verify/err_review`).
 *
 * A failure card used to live only in the frame history: `ChatStore.reduce`
 * appended it from the terminal `session.error` / `step.failed` frame, and a
 * **reopened** session starts from a fresh `ChatState` fed by one snapshot, so
 * the card was gone while the server still carried the failure on its own
 * message (`SessionMessageAssistant.error` — upstream projects the card from
 * that field, `_upstream/packages/session-ui/src/timeline/projection.ts:355-359`).
 *
 * `V2Transport` therefore maps the message's `error` onto [ChatStore.UiMessage],
 * and `ChatStore` rebuilds the card from it — anchored on that message, deduped
 * against the frame that may already have added it, so 「关掉会话再开，错误卡还在
 * 原回合」 holds without double cards on the live path.
 */
class SessionErrorPersistenceTest {

    private fun api(server: MockWebServer) = V2Api(
        baseHttpUrl = server.url("/").toString().toHttpUrlOrNull()!!,
        username = null,
        password = null,
        http = V2Api.defaultClient(),
        directory = null,
    )

    /**
     * A real failed turn, captured from the live 2.0.11 process on 4095
     * (`GET /api/session/{id}/message?order=asc`): `finish:"error"` and the
     * provider error body. Trimmed to the fields the mapper reads.
     */
    private val failedAssistantRow =
        """{"id":"msg_a1","time":{"created":1790255981660,"completed":1790255987425},""" +
            """"type":"assistant","agent":"build","model":{"id":"muse","providerID":"opencode","variant":"default"},""" +
            """"content":[],"finish":"error",""" +
            """"error":{"type":"provider.auth","message":"This model is not available in your country.","status":403}}"""

    /** Also measured live: an aborted turn persists `{type:"aborted"}` and no status. */
    private val abortedAssistantRow =
        """{"id":"msg_a2","time":{"created":1782720327444,"completed":1782720329084},"type":"assistant",""" +
            """"content":[],"error":{"type":"aborted","message":"Aborted"}}"""

    private val userRow =
        """{"id":"msg_u1","time":{"created":1790255981000},"text":"Say hi","type":"user"}"""

    private fun transportSnapshot(server: MockWebServer, body: String): List<ChatStore.UiMessage> {
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))
        return V2Transport(api(server)).snapshotMessages("ses_1", limit = 50)
    }

    private fun text(id: String, value: String) = ChatStore.UiPart(id = id, type = "text", text = value)

    /** A server row as the transport maps it — used for the pure store tests. */
    private fun serverMessage(
        id: String,
        role: String = "assistant",
        created: Long,
        error: ChatStore.UiMessageError? = null,
        interrupted: Boolean = false,
    ) = ChatStore.UiMessage(
        id = id,
        role = role,
        timeCreated = created,
        parts = if (role == "user") listOf(text("$id:text", "hi")) else emptyList(),
        interrupted = interrupted,
        error = error,
    )

    private fun regionBlock() = ChatStore.UiMessageError(
        message = "This model is not available in your country.",
        type = "provider.auth",
        status = 403,
        raw = """{"type":"provider.auth","message":"This model is not available in your country.","status":403}""",
    )

    private fun errors(state: ChatStore.ChatState) = state.messages.filter { ChatStore.isErrorRow(it) }

    private fun errorRows(state: ChatStore.ChatState) =
        TimelineRows.build(state.messages).filterIsInstance<TimelineRows.Row.Error>()

    // ------------------------------------------------------------ mapping

    @Test
    fun theAssistantRowCarriesTheServerErrorOntoTheSnapshot() {
        MockWebServer().use { server ->
            val messages = transportSnapshot(server, """{"data":[$userRow,$failedAssistantRow]}""")

            val assistant = messages.single { it.id == "msg_a1" }
            val error = assistant.error!!
            assertEquals("This model is not available in your country.", error.message)
            assertEquals("provider.auth", error.type)
            assertEquals(403, error.status)
            assertEquals(failedAssistantRow.let { JSONObject(it).getJSONObject("error").toString() }, error.raw)
            assertFalse("a provider failure is not an interrupt", assistant.interrupted)
        }
    }

    @Test
    fun anAbortedTurnKeepsTheInterruptAndNoFailureLine() {
        MockWebServer().use { server ->
            val assistant = transportSnapshot(server, """{"data":[$abortedAssistantRow]}""").single()

            assertTrue("`aborted` is the Interrupted separator", assistant.interrupted)
            // Carried, but the store refuses to build a card from it (`reduce`).
            assertEquals("Aborted", assistant.error?.message)
        }
    }

    @Test
    fun aRowWithoutAnErrorCarriesNone() {
        MockWebServer().use { server ->
            val user = transportSnapshot(server, """{"data":[$userRow]}""").single()

            assertNull(user.error)
            assertFalse(user.interrupted)
        }
    }

    // --------------------------------------------------- the reopen path

    @Test
    fun reopeningASessionKeepsTheCardOnItsOwnTurn() {
        // What the store sees after `observeSession` + `refreshSnapshot`: a
        // fresh state, one snapshot, no frame history at all.
        val state = ChatStore.reduce(
            ChatStore.ChatState(sessionID = "ses_1"),
            ChatStore.Event.Snapshot(
                listOf(
                    serverMessage("msg_u1", role = "user", created = 1_000L),
                    serverMessage("msg_a1", created = 1_100L, error = regionBlock()),
                    serverMessage("msg_u2", role = "user", created = 2_000L),
                ),
            ),
        )

        val rows = TimelineRows.build(state.messages)
        assertEquals(1, rows.filterIsInstance<TimelineRows.Row.Error>().size)
        val card = rows.indexOfFirst { it is TimelineRows.Row.Error }
        val failedTurn = rows.indexOfFirst { it is TimelineRows.Row.Message && it.message.id == "msg_a1" }
        val nextTurn = rows.indexOfFirst { it is TimelineRows.Row.Message && it.message.id == "msg_u2" }
        assertTrue("card follows its own turn", card > failedTurn)
        assertTrue("card stays before the next turn", card < nextTurn)
        assertEquals("This model is not available in your country.", errorRows(state).single().message)
        assertEquals(regionBlock().raw, errorRows(state).single().detail)
    }

    @Test
    fun everyFailedTurnOfASnapshotKeepsItsOwnCard() {
        val state = ChatStore.reduce(
            ChatStore.ChatState(sessionID = "ses_1"),
            ChatStore.Event.Snapshot(
                listOf(
                    serverMessage("msg_u1", role = "user", created = 1_000L),
                    serverMessage("msg_a1", created = 1_100L, error = regionBlock()),
                    serverMessage("msg_u2", role = "user", created = 2_000L),
                    serverMessage("msg_a2", created = 2_100L, error = regionBlock()),
                ),
            ),
        )

        assertEquals(2, errors(state).size)
        assertEquals(2, errorRows(state).map { it.key }.toSet().size)
        val rows = TimelineRows.build(state.messages)
        val first = rows.indexOfFirst { it is TimelineRows.Row.Error }
        val second = rows.indexOfLast { it is TimelineRows.Row.Error }
        val user2 = rows.indexOfFirst { it is TimelineRows.Row.Message && it.message.id == "msg_u2" }
        assertTrue("the second card sits after the second turn", second > user2)
        assertTrue(first < user2)
    }

    @Test
    fun anInterruptedTurnAddsNoCard() {
        val state = ChatStore.reduce(
            ChatStore.ChatState(sessionID = "ses_1"),
            ChatStore.Event.Snapshot(
                listOf(
                    serverMessage("msg_u1", role = "user", created = 1_000L),
                    serverMessage(
                        "msg_a1",
                        created = 1_100L,
                        interrupted = true,
                        error = ChatStore.UiMessageError(
                            message = "Aborted",
                            type = "aborted",
                            raw = """{"type":"aborted","message":"Aborted"}""",
                        ),
                    ),
                ),
            ),
        )

        assertTrue(errors(state).isEmpty())
        assertTrue(
            TimelineRows.build(state.messages).any {
                it is TimelineRows.Row.Separator &&
                    it.kind == TimelineRows.SeparatorKind.INTERRUPTED
            },
        )
    }

    // -------------------------------------------------------------- dedupe

    @Test
    fun theSnapshotDoesNotStackASecondCardOnTheLiveFrame() {
        // Live order: the assistant message lands first (tool/step frames), the
        // terminal frame appends the card, and the same failure is then
        // re-delivered by a message frame / reconnect snapshot.
        var state = ChatStore.ChatState(sessionID = "ses_1")
        state = ChatStore.reduce(state, ChatStore.Event.MessageUpdated(serverMessage("msg_u1", role = "user", created = 1_000L)))
        state = ChatStore.reduce(state, ChatStore.Event.MessageUpdated(serverMessage("msg_a1", created = 1_100L)))
        state = ChatStore.reduce(
            state,
            ChatStore.Event.RunStatusChanged(
                ChatStore.RunStatus.ERROR,
                "This model is not available in your country.",
                errorDetail = regionBlock().raw,
            ),
        )
        assertEquals(1, errors(state).size)

        state = ChatStore.reduce(
            state,
            ChatStore.Event.MessageUpdated(serverMessage("msg_a1", created = 1_100L, error = regionBlock())),
        )
        assertEquals("message frame for an already-carded failure adds nothing", 1, errors(state).size)

        state = ChatStore.reduce(
            state,
            ChatStore.Event.Snapshot(
                listOf(
                    serverMessage("msg_u1", role = "user", created = 1_000L),
                    serverMessage("msg_a1", created = 1_100L, error = regionBlock()),
                ),
            ),
        )
        assertEquals("…and neither does a reconnect snapshot", 1, errors(state).size)
    }

    @Test
    fun reApplyingTheSameSnapshotIsIdempotent() {
        val snapshot = ChatStore.Event.Snapshot(
            listOf(
                serverMessage("msg_u1", role = "user", created = 1_000L),
                serverMessage("msg_a1", created = 1_100L, error = regionBlock()),
            ),
        )
        val once = ChatStore.reduce(ChatStore.ChatState(sessionID = "ses_1"), snapshot)
        val twice = ChatStore.reduce(once, snapshot)

        assertEquals(once.messages.map { it.id }, twice.messages.map { it.id })
        assertEquals(1, errors(twice).size)
    }

    @Test
    fun anIdenticalFailureOnTwoTurnsStaysTwoCards() {
        // The two-model repro, replayed through the snapshot path: the dedupe is
        // per turn (`<messageID>:error:<fingerprint>`), never per content.
        val state = ChatStore.reduce(
            ChatStore.ChatState(sessionID = "ses_1"),
            ChatStore.Event.Snapshot(
                listOf(
                    serverMessage("msg_a1", created = 1_100L, error = regionBlock()),
                    serverMessage("msg_a2", created = 2_100L, error = regionBlock()),
                ),
            ),
        )

        assertEquals(2, errors(state).size)
        // Two rows, distinguishable keys — the LazyColumn slots (and with them
        // each card's expand state) are per failure, not per content.
        assertEquals(2, errors(state).map { it.id }.toSet().size)
        assertEquals(1, errors(state).map { ChatStore.errorRowDetail(it) }.toSet().size)
    }

    @Test
    fun aFailureTheRowsCannotRepresentIsStillReadable() {
        // The classification the card renders from (title/advice) reads the raw
        // payload, so the rebuilt card must be classifiable exactly like the
        // live one: same `message` line + raw detail pair.
        val state = ChatStore.reduce(
            ChatStore.ChatState(sessionID = "ses_1"),
            ChatStore.Event.Snapshot(listOf(serverMessage("msg_a1", created = 1_100L, error = regionBlock()))),
        )

        val row = errors(state).single()
        assertEquals("This model is not available in your country.", ChatStore.errorRowText(row))
        assertEquals(regionBlock().raw, ChatStore.errorRowDetail(row))
        assertEquals(
            ChatStore.ProviderErrorKind.REGION_RESTRICTED,
            ChatStore.classifyStoredError(ChatStore.errorRowText(row), ChatStore.errorRowDetail(row)).kind,
        )
    }

    @Test
    fun theFailureRingStillBoundsAServerTranscriptWithManyFailures() {
        val messages = (1..ChatStore.MAX_ERROR_ROWS + 3).map { i ->
            serverMessage("msg_a$i", created = 1_000L + i * 10, error = regionBlock())
        }
        val state = ChatStore.reduce(ChatStore.ChatState(sessionID = "ses_1"), ChatStore.Event.Snapshot(messages))

        assertEquals(ChatStore.MAX_ERROR_ROWS, errors(state).size)
    }
}
