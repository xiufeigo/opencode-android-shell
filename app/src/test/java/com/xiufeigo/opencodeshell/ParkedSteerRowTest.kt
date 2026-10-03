package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.timeline.TimelineComments
import com.xiufeigo.opencodeshell.ui.session.timeline.TimelineRows
import com.xiufeigo.opencodeshell.ui.session.timeline.showWorkingRow
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WAVE 2.4c — the **parked steer** row (device P1, 2026-09-25).
 *
 * A follow-up admitted with `delivery:"steer"` while the turn is still running
 * parks in `/session/:id/inbox` and has no surface on the device: the composer's
 * queue panel renders `delivery:"queue"` items only, and the server publishes no
 * transcript row until delivery. The reported case was a side question sitting in
 * the inbox with zero feedback for as long as the hung turn lasted.
 *
 * The fix has two halves, both pinned here:
 *  - `ChatViewModel.refreshInbox` maps the inbox's steer items to
 *    [ChatStore.PENDING_ROLE] rows ([pendingSteerRow]);
 *  - the store keeps them at the tail of `messages` and hands each row over to
 *    the real message once the server delivers it.
 *
 * The upstream rule this mirrors is `visibleTimelineMessages`
 * (`_upstream/packages/app/src/session/timeline/controller-projection.ts:12-33`):
 * pending steers stay visible and move to the end, and they do not own assistant
 * work until delivery.
 */
class ParkedSteerRowTest {

    private fun part(id: String, type: String = "text", text: String = "") =
        ChatStore.UiPart(id = id, type = type, text = text)

    private fun message(
        id: String,
        role: String,
        time: Long,
        parts: List<ChatStore.UiPart>,
        model: String? = null,
        interrupted: Boolean = false,
    ) = ChatStore.UiMessage(
        id = id,
        role = role,
        timeCreated = time,
        parts = parts,
        model = model,
        interrupted = interrupted,
    )

    /** `Session.Inbox.User` as measured (openapi.json, `GET /session/{id}/inbox`). */
    private fun inboxItem(
        id: String = "msg_parked1",
        delivery: String = "steer",
        type: String = "user",
        payload: String = """{"text":"what does @file do?","metadata":{"displayText":"what does @file do?"}}""",
        time: String = """{"created":1790000000000}""",
    ) = JSONObject("""{"id":"$id","sessionID":"ses_1","time":$time,"type":"$type","payload":$payload,"delivery":"$delivery"}""")

    // --------------------------------------------------------------- mapping

    @Test
    fun aSteerItemBecomesAPendingRowCarryingItsAdmissionId() {
        val row = pendingSteerRow(inboxItem())!!

        // The id is the *admission* id — the one the delivered message will use —
        // which is what lets the store replace this row instead of double it.
        assertEquals("msg_parked1", row.id)
        assertEquals(ChatStore.PENDING_ROLE, row.role)
        assertTrue(ChatStore.isPendingRow(row))
        assertTrue(ChatStore.isClientRow(row))
        assertEquals("what does @file do?", ChatStore.pendingRowText(row))
        assertEquals(1790000000000L, row.timeCreated)
    }

    @Test
    fun onlyUserSteersArePendingRows() {
        // A queued prompt belongs to the composer's queue panel, which owns its
        // own rows (`ComposerQueueHolder.rowFromJson`).
        assertNull(pendingSteerRow(inboxItem(delivery = "queue")))
        // Control traffic in the same inbox: synthetic / compaction / move.
        assertNull(pendingSteerRow(inboxItem(type = "synthetic")))
        assertNull(pendingSteerRow(inboxItem(type = "compaction")))
        assertNull(pendingSteerRow(inboxItem(type = "move")))
        // No id → nothing to key the row on.
        assertNull(pendingSteerRow(inboxItem(id = "")))
    }

    @Test
    fun displayTextWinsOverTheExpandedPromptText() {
        val row = pendingSteerRow(
            inboxItem(
                payload = """{"text":"what does /abs/path/file.kt do?","metadata":{"displayText":"what does @file do?"}}""",
            ),
        )!!
        // The composer sends the user's own typed text in `metadata.displayText`
        // and the mention-expanded text in `payload.text`; the timeline shows the
        // former, exactly like the composer's own rows.
        assertEquals("what does @file do?", ChatStore.pendingRowText(row))
    }

    @Test
    fun aJsonNullNeverBecomesTheLiteralNullString() {
        // The org.json split that has bitten this codebase three times: Android's
        // optString stringifies the JSON-null sentinel as "null", the JVM returns
        // the fallback. Every field here must read through optStringOrNull, or a
        // `{"text":null}` payload would render the word "null" as the question.
        val row = pendingSteerRow(
            inboxItem(
                payload = """{"text":null,"metadata":{"displayText":null}}""",
                time = """{"created":null}""",
            ),
        )!!
        assertEquals("", ChatStore.pendingRowText(row))
        assertTrue(
            "a null time must not read as 0 (that would sort the row to the top)",
            row.timeCreated > 0L,
        )

        // A literal "null" string from the server counts as absent too.
        val literal = pendingSteerRow(
            inboxItem(payload = """{"text":"null","metadata":{"displayText":"null"}}"""),
        )!!
        assertEquals("", ChatStore.pendingRowText(literal))
    }

    @Test
    fun attachmentsBecomeCountOnlyParts() {
        val row = pendingSteerRow(
            inboxItem(
                payload = """{"text":"look","files":[{"data":"aGk=","mime":"image/png","source":{"type":"data"},"name":"shot.png"},{"data":"aGk=","mime":"text/plain","source":{"type":"data"}}]}""",
            ),
        )!!
        val files = ChatStore.pendingRowFiles(row)
        assertEquals(2, files.size)
        assertEquals("shot.png", files.first().filename)
        // No `url`: the inbox payload embeds content (base64 `data`), and the
        // pending row shows a count rather than re-rendering the bytes.
        assertNull(files.first().url)
    }

    @Test
    fun theAttachmentCountCoversBothCarriers() {
        // `ComposerQueueHolder.rowFromJson` counts `payload.files` plus
        // `metadata.attachments` (`queue-panel.tsx`); the timeline row must agree
        // with the queue panel about how many attachments a prompt carries.
        val row = pendingSteerRow(
            inboxItem(
                payload = """{"text":"look","files":[{"data":"aGk=","mime":"image/png","source":{"type":"data"}}],
                             "metadata":{"displayText":"look","attachments":[{"type":"image"}]}}""",
            ),
        )!!
        assertEquals(2, ChatStore.pendingRowFiles(row).size)
    }

    @Test
    fun theSteersStructuredCommentsRideAlongAsCommentParts() {
        // F-4: `metadata.comments` (`composer/submit.ts:169-177`) — upstream
        // renders them on the pending row too (`session/route.tsx:73`), and the
        // row's own text is the note-free `displayText`, so the parts are the
        // only carrier until delivery.
        val row = pendingSteerRow(
            inboxItem(
                payload = """{"text":"look\n(comment note)","metadata":{"displayText":"look",
                             "comments":[{"path":"src/a.kt","comment":"why?",
                                          "selection":{"startLine":3,"startChar":0,"endLine":3,"endChar":0},
                                          "origin":"review"},
                                         {"path":null,"comment":"dropped"}]}}""",
            ),
        )!!
        assertEquals("look", ChatStore.pendingRowText(row))
        val comments = row.parts.filter { it.type == TimelineComments.PART_TYPE }
        assertEquals(1, comments.size)
        assertEquals("why?", comments[0].text)
        assertEquals("src/a.kt", comments[0].filename)
        // The entry is kept verbatim, so the card reads the wire shape back.
        assertEquals("why?", TimelineComments.ofParts(row.parts)[0].comment)
        assertEquals(":3", TimelineComments.ofParts(row.parts)[0].lineSuffix)
    }

    // ----------------------------------------------------------------- store

    @Test
    fun theSnapshotReplacesThePreviousRowsWholesale() {
        var state = ChatStore.ChatState(
            sessionID = "ses_1",
            messages = listOf(message("m1", "assistant", 1L, listOf(part("p1")))),
        )
        state = ChatStore.reduce(
            state,
            ChatStore.Event.InboxPendingSteers(listOf(pending("msg_a", 10L), pending("msg_b", 11L))),
        )
        assertEquals(listOf("m1", "msg_a", "msg_b"), state.messages.map { it.id })

        // A second refresh with one item left: the delivered one is gone because
        // the server's own list no longer carries it — no client bookkeeping.
        state = ChatStore.reduce(state, ChatStore.Event.InboxPendingSteers(listOf(pending("msg_b", 11L))))
        assertEquals(listOf("m1", "msg_b"), state.messages.map { it.id })

        // The terminal refresh (delivered / cancelled) clears the tail.
        state = ChatStore.reduce(state, ChatStore.Event.InboxPendingSteers(emptyList()))
        assertEquals(listOf("m1"), state.messages.map { it.id })
    }

    @Test
    fun aDeliveredMessageTakesItsPlaceholderOver() {
        var state = ChatStore.ChatState(sessionID = "ses_1", messages = emptyList())
        state = ChatStore.reduce(state, ChatStore.Event.InboxPendingSteers(listOf(pending("msg_a", 10L))))

        // Delivery: the server publishes the very same id as a user message
        // (the client supplies the admission id on the prompt request).
        state = ChatStore.reduce(
            state,
            ChatStore.Event.MessageUpdated(message("msg_a", "user", 10L, listOf(part("prt_1", text = "hi")))),
        )

        assertEquals(listOf("msg_a"), state.messages.map { it.id })
        val row = state.messages.single()
        assertFalse(ChatStore.isPendingRow(row))
        assertEquals("user", row.role)
        // The placeholder's local part is dropped, not merged: mergeParts keeps
        // parts the incoming envelope does not know, which would print the
        // question twice.
        assertEquals(listOf("prt_1"), row.parts.map { it.id })
    }

    @Test
    fun aStalePlaceholderNeverDisplacesTheDeliveredMessage() {
        // The inbox read trails the SSE stream by a round-trip: the delivery can
        // land first, and the refresh that follows still lists the item.
        var state = ChatStore.ChatState(
            sessionID = "ses_1",
            messages = listOf(message("msg_a", "user", 10L, listOf(part("prt_1", text = "hi")))),
        )
        state = ChatStore.reduce(state, ChatStore.Event.InboxPendingSteers(listOf(pending("msg_a", 10L))))

        assertEquals(listOf("msg_a"), state.messages.map { it.id })
        assertEquals("user", state.messages.single().role)
        assertEquals(listOf("prt_1"), state.messages.single().parts.map { it.id })
    }

    @Test
    fun theClientRowHandOverLeavesRealMessagesAlone() {
        // Non-client rows keep the ordinary merge: a same-id part takes the
        // incoming copy, other parts stay (the established `upsert` contract).
        var state = ChatStore.ChatState(
            sessionID = "ses_1",
            messages = listOf(message("m1", "assistant", 1L, listOf(part("p1", text = "a"), part("p2", text = "b")))),
        )
        state = ChatStore.reduce(
            state,
            ChatStore.Event.MessageUpdated(message("m1", "assistant", 1L, listOf(part("p1", text = "a2")))),
        )
        val row = state.messages.single()
        assertEquals(listOf("p1", "p2"), row.parts.map { it.id })
        assertEquals("a2", row.parts.first().text)
    }

    @Test
    fun parkedRowsStayOutOfTheTurnRules() {
        val transcript = listOf(
            message("m1", "user", 1L, listOf(part("p1", text = "hi"))),
            message("m2", "assistant", 2L, emptyList()),
        )
        val withSteer = transcript + pending("msg_a", 10L)

        // The `Working` row is keyed on the newest *turn*: a pending steer does
        // not own the assistant work of the running turn (upstream
        // `controller-projection.ts:28`), so the row must stay up while the user
        // waits for their parked steer.
        assertTrue(showWorkingRow(withSteer, ChatStore.RunStatus.RUNNING))
        assertTrue(showWorkingRow(transcript, ChatStore.RunStatus.RUNNING))

        // …but the steer must not own the assistant work either: with a visible
        // running tool card, `Working` stays hidden for both lists.
        val visible = transcript.dropLast(1) +
            message("m2", "assistant", 2L, listOf(ChatStore.UiPart("p1", "text", text = "written"))) +
            pending("msg_a", 10L)
        assertFalse(showWorkingRow(visible, ChatStore.RunStatus.RUNNING))
    }

    @Test
    fun parkedRowsLeaveTheRailAndSeparatorsAlone() {
        val rows = TimelineRows.build(
            listOf(
                message("m1", "user", 1L, listOf(part("p1", text = "hi")), model = "opencode/muse"),
                message("m2", "assistant", 2L, emptyList(), model = "opencode/muse", interrupted = true),
                pending("msg_a", 10L),
            ),
        )

        // The rail ticks real user turns only.
        assertEquals(listOf("m1"), TimelineRows.userTicks(rows).map { it.messageID })
        // The trailing "Interrupted" divider still closes the interrupted turn —
        // and the parked steer renders after it (upstream moves pending steers to
        // the end of the visible list).
        assertEquals(
            listOf("m1", "m2", "sep:interrupted:m2", "msg_a"),
            rows.map { it.key },
        )
        // …and the pending steer is not a separator anchor of its own.
        assertTrue(rows.none { it is TimelineRows.Row.Separator && it.anchorID == "msg_a" })
    }

    private fun pending(id: String, time: Long) = ChatStore.UiMessage(
        id = id,
        role = ChatStore.PENDING_ROLE,
        timeCreated = time,
        parts = listOf(ChatStore.UiPart(id = "$id:pending:text", type = "text", text = "parked")),
    )
}
