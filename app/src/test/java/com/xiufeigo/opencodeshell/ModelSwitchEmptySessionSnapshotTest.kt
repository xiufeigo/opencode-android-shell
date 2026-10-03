package com.xiufeigo.opencodeshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The empty-session gate for the snapshot path (emulator-5558 v74): in a
 * brand-new empty session each variant change wrote a `Model changed` system
 * row, accumulating several after a few taps.
 *
 * The live `MessageUpdated` gate (v68) already drops a `model-switched`
 * notice onto an empty transcript, but every variant change persists a
 * durable row server-side (`Session.switchModel` compares the variant too),
 * and the next snapshot read them all back — the `Snapshot` reduce path had
 * no gate at all.
 *
 * Upstream does render `model-switched` as a timeline separator
 * (`session-timeline-row.tsx` `session-timeline-notice`), but an empty session
 * here stays empty until its first real content (the v68/v74 product rule), so
 * a transcript whose rows are all notice chrome drops its `model-switched`
 * notices on this path too. The selection itself is untouched (that is
 * `SessionSelectionChanged`, not a row), and a switch made **after** the first
 * content row is kept exactly as it was.
 *
 * v76 walked the boundary from "is there a `user` row yet" to "does the row
 * precede the first content row": the old shape asked about the store at the
 * moment of the merge, so the durable rows a cold start handed back came in the
 * moment the first prompt supplied a user row (three 「Model changed」 markers
 * over the first bubble, 5558 `research/v76_verify/5558/17_stream.png`, with the
 * count flickering as snapshots with and without that row alternated). See
 * `ChatStore.withoutPreContentModelNotices` and
 * `ModelSwitchColdStartReplayV76Test`.
 */
class ModelSwitchEmptySessionSnapshotTest {

    private fun modelNotice(id: String, time: Long) =
        ChatStore.UiMessage(
            id = id,
            role = "assistant",
            timeCreated = time,
            parts = listOf(ChatStore.UiPart(id = "$id:notice", type = "model-switched")),
        )

    private fun agentNotice(id: String, time: Long) =
        ChatStore.UiMessage(
            id = id,
            role = "assistant",
            timeCreated = time,
            parts = listOf(ChatStore.UiPart(id = "$id:notice", type = "agent-switched")),
        )

    private fun userMessage(id: String, time: Long) =
        ChatStore.UiMessage(
            id = id,
            role = "user",
            timeCreated = time,
            parts = listOf(ChatStore.UiPart(id = "$id:text", type = "text", text = "hi")),
        )

    @Test
    fun variantRowsOnAnEmptySnapshotStayOutOfTheTranscript() {
        // The measured report: four durable variant-change rows, no user
        // message anywhere — the session must stay empty.
        val rows = (1..4).map { modelNotice("msg_var$it", it.toLong()) }
        val s = ChatStore.reduce(ChatStore.ChatState(), ChatStore.Event.Snapshot(rows))
        assertTrue(s.messages.isEmpty())
    }

    @Test
    fun modelNoticesAfterTheFirstUserMessageAreKept() {
        // v76: the boundary is *position*, not the store's current shape — a
        // notice that comes after the user row is a switch onto a transcript and
        // renders, whether it arrives live or in a snapshot.
        val rows = listOf(
            userMessage("msg_u1", 0L),
            modelNotice("msg_var1", 1L),
            modelNotice("msg_var2", 2L),
        )
        val s = ChatStore.reduce(ChatStore.ChatState(), ChatStore.Event.Snapshot(rows))
        assertEquals(listOf("msg_u1", "msg_var1", "msg_var2"), s.messages.map { it.id })
    }

    @Test
    fun modelNoticesFromBeforeTheUserMessageStayOutEvenThoughOneExists() {
        // The same rows the other way round — the cold-start replay: written
        // while the session was empty, handed back by the server after the first
        // prompt landed. They belong to the empty-session window and never
        // render (5558 v76, three 「Model changed」 rows over the first bubble).
        val rows = listOf(
            modelNotice("msg_var1", 1L),
            modelNotice("msg_var2", 2L),
            userMessage("msg_u1", 3L),
        )
        val s = ChatStore.reduce(ChatStore.ChatState(), ChatStore.Event.Snapshot(rows))
        assertEquals(listOf("msg_u1"), s.messages.map { it.id })
    }

    @Test
    fun otherNoticeRowsAreNotAffectedByTheModelRule() {
        val rows = listOf(agentNotice("msg_ag1", 1L), modelNotice("msg_var1", 2L))
        val s = ChatStore.reduce(ChatStore.ChatState(), ChatStore.Event.Snapshot(rows))
        assertEquals(listOf("msg_ag1"), s.messages.map { it.id })
    }

    @Test
    fun aLaterSnapshotKeepsWorkingAfterTheGateDroppedRows() {
        var s = ChatStore.reduce(
            ChatStore.ChatState(),
            ChatStore.Event.Snapshot(listOf(modelNotice("msg_var1", 1L))),
        )
        assertTrue(s.messages.isEmpty())
        s = ChatStore.reduce(
            s,
            ChatStore.Event.Snapshot(listOf(userMessage("msg_u1", 0L), modelNotice("msg_var2", 2L))),
        )
        assertEquals(listOf("msg_u1", "msg_var2"), s.messages.map { it.id })
    }
}
