package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.timeline.ErrorCardExpansion
import com.xiufeigo.opencodeshell.ui.session.timeline.TimelineRows
import com.xiufeigo.opencodeshell.ui.session.timeline.errorCardStateKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The failure card's **identity** must not drift between the live session and a
 * session re-entry (device v12: two cards expanded, leave the session, re-enter
 * — both came back collapsed).
 *
 * The card scopes its expand state to `TimelineRows.Row.Error.key`, and
 * `ErrorCardExpansion` holds those keys for the life of the process, so the
 * re-entry reads the state back **only** when the key is byte-identical. The row
 * key is `error:<rowID>`, and the row id used to embed a stamp derived from the
 * anchor message's `timeCreated` — which the two paths disagree on:
 *
 * - live, the v2 stream builds the assistant envelope **client-side** (there is
 *   no `session.message.updated` frame in v2; `PartUpdated` /
 *   `MessageContentUpdated` fall back to `System.currentTimeMillis()`), so the
 *   anchor carries the wall clock when the failure frame lands;
 * - re-entered, the same row is rebuilt from one server snapshot
 *   (`materializeMessageErrors`) and carries the server's own `time.created`.
 *
 * The drift is systematic, not flaky: every live failure row is keyed off a wall
 * clock and every rebuilt one off the server clock. Pinning it here keeps the
 * row id anchored on the message plus the failure's own content instead.
 */
class SessionErrorReentryKeyTest {

    private val line = "This model is not available in your country."

    private val detail =
        """{"type":"provider.auth","message":"This model is not available in your country.","status":403}"""

    private val secondLine = "Missing API key."

    private val secondDetail = """{"type":"provider.auth","message":"Missing API key.","status":401}"""

    private fun part(id: String, text: String = "hi") = ChatStore.UiPart(id = id, type = "text", text = text)

    private fun serverMessage(
        id: String,
        role: String = "assistant",
        created: Long,
        error: ChatStore.UiMessageError? = null,
    ) = ChatStore.UiMessage(
        id = id,
        role = role,
        timeCreated = created,
        parts = listOf(part("$id:0")),
        error = error,
    )

    private fun failure(message: String, raw: String) =
        ChatStore.UiMessageError(message = message, type = "provider.auth", status = 403, raw = raw)

    private fun errorRows(state: ChatStore.ChatState) =
        TimelineRows.build(state.messages).filterIsInstance<TimelineRows.Row.Error>()

    private fun expansionKey(row: TimelineRows.Row.Error) =
        errorCardStateKey(row.key, row.message, row.detail)

    /**
     * The live path for one failed turn: the assistant envelope is created by the
     * stream itself (`PartUpdated`), then the terminal frame appends the card.
     */
    private fun liveFailure(message: String = line, raw: String = detail): ChatStore.ChatState {
        var state = ChatStore.ChatState(sessionID = "ses_1")
        state = ChatStore.reduce(state, ChatStore.Event.MessageUpdated(serverMessage("msg_u1", role = "user", created = 1_000L)))
        state = ChatStore.reduce(state, ChatStore.Event.PartUpdated("msg_a1", part("msg_a1:0")))
        return ChatStore.reduce(
            state,
            ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.ERROR, message, errorDetail = raw),
        )
    }

    /** The same session, reopened: a fresh state fed by one server snapshot. */
    private fun reopened(created: Long = 1_100L): ChatStore.ChatState = ChatStore.reduce(
        ChatStore.ChatState(sessionID = "ses_1"),
        ChatStore.Event.Snapshot(
            listOf(
                serverMessage("msg_u1", role = "user", created = 1_000L),
                serverMessage("msg_a1", created = created, error = failure(line, detail)),
            ),
        ),
    )

    @Before
    fun resetExpansionMemory() {
        ErrorCardExpansion.clear()
    }

    // ------------------------------------------------------------- the repro

    @Test
    fun theFailureRowKeyIsByteIdenticalAcrossASessionReentry() {
        val live = errorRows(liveFailure()).single()
        val afterReentry = errorRows(reopened()).single()

        assertEquals("the live and the rebuilt row name the same turn", live.messageID, afterReentry.messageID)
        assertEquals(
            "the row key must not drift with the anchor's time source",
            live.key,
            afterReentry.key,
        )
        assertEquals(
            "…and neither may the key the card scopes its expand state to",
            expansionKey(live),
            expansionKey(afterReentry),
        )
    }

    @Test
    fun anExpandedCardReadsItsStateBackAfterAReentry() {
        val live = errorRows(liveFailure()).single()
        ErrorCardExpansion.setExpanded(expansionKey(live), true)

        val afterReentry = errorRows(reopened()).single()
        assertTrue(
            "an expanded failure card must still be expanded after leaving and reopening the session",
            ErrorCardExpansion.isExpanded(expansionKey(afterReentry)),
        )
    }

    @Test
    fun everyCardOfATwoFailureSessionKeepsItsKeyAcrossAReentry() {
        // The v12 repro: two failed turns, both cards expanded, leave, re-enter.
        var live = liveFailure()
        live = ChatStore.reduce(live, ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.RUNNING))
        live = ChatStore.reduce(live, ChatStore.Event.MessageUpdated(serverMessage("msg_u2", role = "user", created = 2_000L)))
        live = ChatStore.reduce(live, ChatStore.Event.PartUpdated("msg_a2", part("msg_a2:0")))
        live = ChatStore.reduce(
            live,
            ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.ERROR, secondLine, errorDetail = secondDetail),
        )

        val afterReentry = ChatStore.reduce(
            ChatStore.ChatState(sessionID = "ses_1"),
            ChatStore.Event.Snapshot(
                listOf(
                    serverMessage("msg_u1", role = "user", created = 1_000L),
                    serverMessage("msg_a1", created = 1_100L, error = failure(line, detail)),
                    serverMessage("msg_u2", role = "user", created = 2_000L),
                    serverMessage("msg_a2", created = 2_100L, error = failure(secondLine, secondDetail)),
                ),
            ),
        )

        val liveRows = errorRows(live)
        val reentryRows = errorRows(afterReentry)
        assertEquals(2, liveRows.size)
        assertEquals("one card per failed turn", liveRows.map { it.key }, reentryRows.map { it.key })
        for (row in liveRows) ErrorCardExpansion.setExpanded(expansionKey(row), true)
        for (row in reentryRows) {
            assertTrue(
                "card ${row.messageID} lost its expanded state on re-entry",
                ErrorCardExpansion.isExpanded(expansionKey(row)),
            )
        }
    }

    // ---------------------------------------------------- the id's own contract

    @Test
    fun replayingTheSnapshotFromScratchYieldsTheSameKeys() {
        val once = errorRows(reopened())
        val twice = errorRows(reopened())

        assertEquals(once.map { it.key }, twice.map { it.key })
        assertEquals(once.map { expansionKey(it) }, twice.map { expansionKey(it) })
    }

    @Test
    fun twoDifferentFailuresOnOneTurnStayTwoDistinctRows() {
        var state = ChatStore.reduce(ChatStore.ChatState(sessionID = "ses_1"), ChatStore.Event.MessageUpdated(serverMessage("msg_a1", created = 1_100L)))
        state = ChatStore.reduce(state, ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.ERROR, line, errorDetail = detail))
        state = ChatStore.reduce(state, ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.ERROR, secondLine, errorDetail = secondDetail))

        val rows = errorRows(state)
        assertEquals(2, rows.size)
        assertEquals(2, rows.map { it.key }.toSet().size)
        assertEquals(listOf(line, secondLine), rows.map { it.message })
    }

    @Test
    fun identicalFailuresOnTwoTurnsStayTwoDistinctRows() {
        val state = ChatStore.reduce(
            ChatStore.ChatState(sessionID = "ses_1"),
            ChatStore.Event.Snapshot(
                listOf(
                    serverMessage("msg_a1", created = 1_100L, error = failure(line, detail)),
                    serverMessage("msg_a2", created = 2_100L, error = failure(line, detail)),
                ),
            ),
        )

        val rows = errorRows(state)
        assertEquals(2, rows.size)
        assertEquals("the anchor still tells two identical failures apart", 2, rows.map { it.key }.toSet().size)
    }

    @Test
    fun theAnchorPrefixStillCarriesARevertBoundary() {
        // The revert rule is `id >= boundary` on message ids, so the failure row
        // must keep sorting after its own turn and before the next one — the row
        // id stays `<anchorID>:error:<…>`.
        val state = errorRows(reopened()).single()
        assertTrue(state.messageID.startsWith("msg_a1:error:"))
    }

    @Test
    fun theFingerprintDependsOnTheFailureAlone() {
        // Deterministic, so the live and the rebuilt id agree…
        assertEquals(ChatStore.errorFingerprint(line, detail), ChatStore.errorFingerprint(line, detail))
        // …and distinct per failure, so two cards on one turn stay two rows.
        assertNotEquals(ChatStore.errorFingerprint(line, detail), ChatStore.errorFingerprint(secondLine, detail))
        assertNotEquals(ChatStore.errorFingerprint(line, detail), ChatStore.errorFingerprint(line, secondDetail))
        assertNotEquals("the halves must not run together", ChatStore.errorFingerprint("ab", "c"), ChatStore.errorFingerprint("a", "bc"))
        // A missing payload and a blank one are the same failure (both fold to
        // null in `appendErrorRow`).
        assertEquals(ChatStore.errorFingerprint(line, null), ChatStore.errorFingerprint(line, ""))
    }
}
