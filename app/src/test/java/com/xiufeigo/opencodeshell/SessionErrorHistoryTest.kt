package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.timeline.TimelineRows
import com.xiufeigo.opencodeshell.ui.session.timeline.showWorkingRow
import com.xiufeigo.opencodeshell.ui.session.timeline.trailingErrorMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Failure history (v8-B finding: two consecutive failures rendered as **one**
 * card, the second overwriting the first).
 *
 * Upstream renders one card per failed turn — `TimelineRow.Error` pushed with
 * the turn's own `userMessageID`, keyed `error:<userMessageID>`
 * (`_upstream/packages/session-ui/src/timeline/projection.ts:355-359`,
 * `timeline/timeline-row.ts:60-63,99-100`) — so the port keeps one row per
 * failure too ([ChatStore.ERROR_ROLE], appended by `ChatStore.reduce`), renders
 * them as separate `SessionErrorCard`s, and rings the history at
 * [ChatStore.MAX_ERROR_ROWS].
 */
class SessionErrorHistoryTest {

    private val regionBlock =
        """{"type":"provider.auth","message":"This model is not available in your country.","status":403}"""

    private fun text(id: String, value: String) = ChatStore.UiPart(id = id, type = "text", text = value)

    /**
     * Wall-clock-stamped rows in visit order: real transcripts carry epoch
     * `time.created` and the store re-sorts on every message event, so the
     * fixtures must be ordered the way the server would order them.
     */
    private var clock = System.currentTimeMillis() - 60_000
    private fun stamp(): Long = clock++

    private fun message(
        id: String,
        role: String = "assistant",
        parts: List<ChatStore.UiPart> = listOf(text("$id:0", "hi")),
        model: String? = null,
    ) = ChatStore.UiMessage(id = id, role = role, timeCreated = stamp(), parts = parts, model = model)

    private fun failed(
        state: ChatStore.ChatState,
        error: String,
        detail: String? = null,
    ) = ChatStore.reduce(state, ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.ERROR, error, errorDetail = detail))

    /** What a new prompt's `running` frame does between two turns. */
    private fun running(state: ChatStore.ChatState) =
        ChatStore.reduce(state, ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.RUNNING))

    private fun updated(state: ChatStore.ChatState, message: ChatStore.UiMessage) =
        ChatStore.reduce(state, ChatStore.Event.MessageUpdated(message))

    private fun errors(state: ChatStore.ChatState) = state.messages.filter { ChatStore.isErrorRow(it) }

    private fun errorRows(state: ChatStore.ChatState) =
        TimelineRows.build(state.messages).filterIsInstance<TimelineRows.Row.Error>()

    /** The two-turn repro: `Reply hello` then `Reply world`, both 403 region blocks. */
    private fun twoFailedTurns(): ChatStore.ChatState {
        var state = ChatStore.ChatState(sessionID = "ses_1")
        state = updated(state, message("msg_u1", role = "user"))
        state = failed(state, "This model is not available in your country.", regionBlock)
        // The second prompt: a `running` frame, then its user row, then a failure
        // with the very same provider line — the repro's shape.
        state = running(state)
        state = updated(state, message("msg_u2", role = "user"))
        return failed(state, "This model is not available in your country.", regionBlock)
    }

    // ------------------------------------------------- one card per failure

    @Test
    fun twoConsecutiveFailuresRenderTwoCardsNotOne() {
        val state = twoFailedTurns()

        assertEquals(2, errors(state).size)
        assertEquals(
            listOf(
                "This model is not available in your country.",
                "This model is not available in your country.",
            ),
            errorRows(state).map { it.message },
        )
    }

    @Test
    fun eachCardSitsAfterItsOwnTurnAndHasItsOwnKey() {
        val rows = TimelineRows.build(twoFailedTurns().messages)

        val first = rows.indexOfFirst { it is TimelineRows.Row.Error }
        val user1 = rows.indexOfFirst { it is TimelineRows.Row.Message && it.message.id == "msg_u1" }
        val user2 = rows.indexOfFirst { it is TimelineRows.Row.Message && it.message.id == "msg_u2" }
        val last = rows.indexOfLast { it is TimelineRows.Row.Error }
        // turn 1 (… its failure) then turn 2 (… its failure) — the second card
        // never replaces the first.
        assertTrue("failure 1 follows turn 1", first in (user1 + 1) until user2)
        assertTrue("failure 2 follows turn 2", last > user2)

        val keys = rows.filterIsInstance<TimelineRows.Row.Error>().map { it.key }
        assertEquals(2, keys.toSet().size)
        assertTrue(keys.all { it.startsWith("error:") })
        // Distinguishable rows → the LazyColumn slots (and with them the
        // cards' `rememberSaveable` expand state) are per failure.
        assertNotEquals(keys[0], keys[1])
    }

    @Test
    fun expandedStateKeyInputsArePerFailure() {
        val rows = errorRows(twoFailedTurns())
        // SessionErrorCard keys its toggle on the failure's identity —
        // `TimelineRows.Row.key` (`error:<messageID>`) when the timeline hands
        // it in, its own content otherwise (SessionErrorCard.kt). Two identical
        // failures share the *content* key, so the per-row key is what keeps
        // their expand states apart.
        assertEquals(1, rows.map { it.message to it.detail }.toSet().size)
        assertEquals(2, rows.map { it.messageID }.toSet().size)
        assertEquals(2, rows.map { it.key }.toSet().size)
    }

    @Test
    fun aRefailureOfTheSameKindAfterANewTurnAddsASecondCard() {
        var state = twoFailedTurns()
        state = running(state)
        state = updated(state, message("msg_u3", role = "user"))
        state = failed(state, "This model is not available in your country.", regionBlock)

        assertEquals(3, errors(state).size)
    }

    // ------------------------------------------------------- frame dedupe

    @Test
    fun redeliveredFrameOfTheStillTrailingFailureDoesNotStack() {
        // `session.step.failed` + `session.execution.failed` describe one
        // terminal error, so the second frame lands while the store is already
        // in ERROR for exactly that failure.
        val once = failed(ChatStore.ChatState(sessionID = "ses_1"), "boom")
        val twice = failed(once, "boom")

        assertEquals(1, errors(twice).size)
        assertEquals(1, errorRows(twice).size)
    }

    @Test
    fun aNewTurnCanFailWithTheSameLineAndStillGetItsOwnCard() {
        // The dedupe keys on the run state, not on neighbours: an identical line
        // after a later turn's `running` frame is a different failure.
        var state = ChatStore.ChatState(sessionID = "ses_1")
        state = failed(state, "boom")
        state = running(state)
        state = failed(state, "boom")

        assertEquals(2, errors(state).size)
    }

    @Test
    fun aDifferentFailureAlwaysGetsItsOwnCard() {
        val state = failed(failed(ChatStore.ChatState(sessionID = "ses_1"), "boom"), "bang")

        assertEquals(listOf("boom", "bang"), errors(state).map { ChatStore.errorRowText(it) })
    }

    @Test
    fun blankFailureLineAddsNoRow() {
        // `ChatViewModel` passes `e.message`, which is null for some transports.
        val state = failed(ChatStore.ChatState(sessionID = "ses_1"), "")
        val nulled = ChatStore.reduce(
            state,
            ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.ERROR, null),
        )

        assertTrue(errors(state).isEmpty())
        assertTrue(errors(nulled).isEmpty())
    }

    @Test
    fun theDetailRidesAlongWithTheLine() {
        val state = failed(ChatStore.ChatState(sessionID = "ses_1"), "region block", regionBlock)

        assertEquals("region block", ChatStore.errorRowText(errors(state).single()))
        assertEquals(regionBlock, ChatStore.errorRowDetail(errors(state).single()))
    }

    // ------------------------------------------------------------- the ring

    @Test
    fun historyIsCappedAtEightNewestWins() {
        var state = ChatStore.ChatState(sessionID = "ses_1")
        for (i in 1..ChatStore.MAX_ERROR_ROWS + 1) state = failed(state, "boom $i")

        val kept = errors(state).map { ChatStore.errorRowText(it) }
        assertEquals(ChatStore.MAX_ERROR_ROWS, kept.size)
        assertTrue("the oldest card fell out of the ring", "boom 1" !in kept)
        assertEquals("boom 2", kept.first())
        assertEquals("boom ${ChatStore.MAX_ERROR_ROWS + 1}", kept.last())
    }

    // ------------------------------------------------- auto-retry replaces it

    @Test
    fun anAutoRetryReplacesTheCardItWasScheduledFor() {
        var state = updated(ChatStore.ChatState(sessionID = "ses_1"), message("msg_a1"))
        state = failed(state, "boom")
        state = ChatStore.reduce(
            state,
            ChatStore.Event.RunStatusChanged(
                ChatStore.RunStatus.RUNNING,
                retry = ChatStore.RetryInfo(1, 2L, "boom", null),
            ),
        )

        assertTrue("upstream renders the Retry row instead of the error", errors(state).isEmpty())
        // …and the transcript ends on the assistant turn the `Working` row wants.
        assertEquals("msg_a1", state.messages.last().id)
    }

    @Test
    fun aTrailingFailureRowDoesNotHideTheWorkingRow() {
        var state = updated(ChatStore.ChatState(sessionID = "ses_1"), message("msg_a1", parts = emptyList()))
        state = failed(state, "boom")
        state = running(state)

        // The failure closes the failed turn, so it is the newest row …
        assertTrue(ChatStore.isErrorRow(state.messages.last()))
        // … while the `Working` fallback reads the newest *turn*.
        assertTrue(showWorkingRow(state.messages, state.runStatus, hasPendingRequest = false))
    }

    // ------------------------------------------------------------ no leakage

    @Test
    fun aRevertCarriesTheFailuresOfTheRewoundTurnAway() {
        val state = twoFailedTurns()
        val kept = errors(state).first().id
        val reverted = ChatStore.reduce(
            state,
            ChatStore.Event.SessionRevertCommitted(sessionID = "ses_1", to = "msg_u2"),
        )

        assertEquals(listOf(kept), errors(reverted).map { it.id })
    }

    @Test
    fun aNewSessionStartsWithAnEmptyHistory() {
        // `ChatViewModel.observeSession` / `newSession` assign a fresh state;
        // nothing about the previous session's failures survives it.
        val previous = twoFailedTurns()
        assertFalse(errors(previous).isEmpty())

        val next = ChatStore.reduce(
            ChatStore.ChatState(sessionID = "ses_2"),
            ChatStore.Event.Snapshot(listOf(message("msg_b1"))),
        )

        assertTrue(errors(next).isEmpty())
        assertEquals(listOf("msg_b1"), next.messages.map { it.id })
    }

    // ------------------------------------------- recovery retires stale cards

    /**
     * v57 (`E2ECHK5556-28…30`): `adb reverse` pulled → a send raised
     * 「无法连接到此服务器 / Failed to connect to /127.0.0.1:4095」 → the reverse
     * was restored → the card stayed on screen for good. A failure row is a
     * transcript row, so nothing retired it, and 「the link is down」 had stopped
     * being true the moment the next turn reached the server.
     */
    @Test
    fun aConnectionFailureCardGoesAwayWhenTheNextTurnRuns() {
        var state = updated(ChatStore.ChatState(sessionID = "ses_1"), message("msg_u1", role = "user"))
        state = failed(state, "Failed to connect to /127.0.0.1:4095")
        assertEquals(1, errors(state).size)

        // The reverse is restored and the user sends again: a `running` status can
        // only come from a server that answered.
        state = updated(state, message("msg_u2", role = "user"))
        state = running(state)

        assertTrue("the stale connection card is retired", errors(state).isEmpty())
    }

    @Test
    fun recoveryKeepsTheRealFailuresAndTheTranscript() {
        var state = updated(ChatStore.ChatState(sessionID = "ses_1"), message("msg_u1", role = "user"))
        state = failed(state, "Failed to connect to /127.0.0.1:4095")
        state = failed(state, "This model is not available in your country.", regionBlock)
        state = updated(state, message("msg_u2", role = "user"))
        state = running(state)

        // Only the transport card goes; the 403 is the transcript (upstream
        // projects it as `TimelineRow.Error` for good).
        assertEquals(
            listOf("This model is not available in your country."),
            errors(state).map { ChatStore.errorRowText(it) },
        )
        assertTrue(errors(state).none { ChatStore.errorRowText(it).contains("Failed to connect") })
    }

    @Test
    fun aDroppedConnectionCardDoesNotComeBackOnAReconnectSnapshot() {
        // The snapshot path materializes the failures the *server* transcript
        // carries (`materializeMessageErrors`); a client-side transport card is
        // not one of them, so the reconnect must not resurrect it.
        var state = updated(ChatStore.ChatState(sessionID = "ses_1"), message("msg_u1", role = "user"))
        state = failed(state, "Failed to connect to /127.0.0.1:4095")
        state = updated(state, message("msg_u2", role = "user"))
        state = running(state)
        state = ChatStore.reduce(state, ChatStore.Event.Snapshot(state.messages))

        assertTrue(errors(state).isEmpty())
    }

    // -------------------------------------------- timeline wiring (last mile)
    @Test
    fun failureRowsDoNotDisturbTheSeparatorsOrTheRail() {
        var state = ChatStore.ChatState(sessionID = "ses_1")
        state = updated(state, message("msg_a1", model = "opencode/a"))
        state = failed(state, "boom")
        state = updated(state, message("msg_a2", model = "opencode/b"))
        state = updated(state, message("msg_u2", role = "user"))

        val rows = TimelineRows.build(state.messages)
        // The model switch still keys on the real messages (a failure row has no
        // model), and the rail still counts user turns only.
        assertEquals(1, rows.filterIsInstance<TimelineRows.Row.Separator>().size)
        assertEquals(listOf("msg_u2"), TimelineRows.userTicks(rows).map { it.messageID })
        assertEquals(1, rows.count { it is TimelineRows.Row.Error })
        // A failure is a card, not a message row.
        assertTrue(rows.filterIsInstance<TimelineRows.Row.Message>().none { ChatStore.isErrorRow(it.message) })
    }

    @Test
    fun theTrailingCardIsSuppressedOnlyForTheFailureAlreadyOnScreen() {
        val state = twoFailedTurns()
        val rows = TimelineRows.build(state.messages)

        // Same failure as the newest row → the row's own card is the only card.
        assertNull(trailingErrorMessage("This model is not available in your country.", rows))
        // A failure the rows do not carry (their history was cut away) → the
        // trailing card keeps it visible instead of losing it.
        assertEquals("boom", trailingErrorMessage("boom", rows))
        assertNull(trailingErrorMessage(null, rows))
    }

    @Test
    fun theCardWiringIsKeyedPerFailure() {
        fun source(relative: String): String {
            val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
            return file.readText()
        }

        // One Lazy item per failure row, keyed by the row (and each row key is
        // `error:<messageID>`), rendering the shared SessionErrorCard.
        val timeline = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/timeline/MessageTimeline.kt")
        assertTrue(timeline.contains("is TimelineRows.Row.Error -> SessionErrorCard("))
        assertTrue(timeline.contains("items(items = reversed, key = { it.key },"))
        val rows = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/timeline/TimelineRows.kt")
        assertTrue(rows.contains("override val key: String get() = \"error:\$messageID\""))
        // …and the card's toggle is keyed on the failure's own identity: the
        // row key the timeline passes in, its content otherwise. The identity
        // (not the saveable slot) is also what lets the state survive leaving
        // and reopening the session (`ErrorCardExpansion`, device v11).
        val card = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/timeline/SessionErrorCard.kt")
        assertTrue(card.contains("var expanded by rememberSaveable(expansionKey)"))
        assertTrue(card.contains("errorCardStateKey(stateKey, message, detail)"))
        assertTrue(timeline.contains("stateKey = row.key,"))
    }
}
