package com.xiufeigo.opencodeshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v58, emulator-5556: 「发了 prompt 点了 Stop，什么都没发生」 — a turn the server
 * aborts before it writes anything leaves no trace at all: no user row, no
 * `Interrupted` divider, no error, and the composer idle, so the session reads
 * as though the prompt had never been sent.
 *
 * The upstream check that decides the fix (and it is what upstream does, not an
 * invention):
 *
 * - upstream's `Interrupted` separator is a **message-level** fact —
 *   `isInterrupted(message.error)` (`session-ui/src/timeline/projection.ts:408-410`)
 *   with `to-session-error.ts:52` mapping `UserInterruptedError` onto
 *   `{type:"aborted"}` on the assistant message. `SessionEvent.Execution.Interrupted`
 *   itself only projects the session to idle (`core/src/session/projector.ts:665`);
 *   with no message there is nothing to anchor a divider to, so **upstream has
 *   no interrupted marker in this case either**.
 * - what upstream *does* have is the prompt: `submit.ts:97` writes the user
 *   message locally (`session.handoff?.set(handoffMessage(value))`) before the
 *   request that creates it, and the whole transcript afterwards is a hand-over
 *   onto the server's copy. So the scenario is not invisible upstream — the
 *   user's own words are on screen.
 *
 * The shell had only the server's half: a prompt reached the transcript when a
 * later snapshot carried the message, and an aborted-before-write turn never
 * produces one. So the fix is upstream's landing point, reproduced with the
 * shell's parked-steer vocabulary: the admission id the answer already returns
 * becomes a [ChatStore.PENDING_ROLE] row the moment the prompt is admitted
 * ([admitHandoffRow]), retired by the delivered message rather than by the next
 * inbox read ([refreshInbox]).
 *
 * The [ChatViewModel] half is pinned against the source function-window, the
 * technique [StopInterruptTest] and [UndoRevertCommitTest] already use; the row
 * itself is pure and runs for real.
 */
class StopHandoffRowV62Test {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private fun window(src: String, from: String, to: String): String {
        val start = src.indexOf(from)
        assertTrue("missing window start: $from", start >= 0)
        val end = src.indexOf(to, start + from.length)
        assertTrue("missing window end: $to", end > start)
        return src.substring(start, end)
    }

    private val vm: String get() = source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")

    // ------------------------------------------------- 1. the row is a real row

    @Test
    fun `an admitted prompt gets a parked row carrying the admission id`() {
        val row = handoffPromptRow("msg_admit1", "hello", 0, emptyList())
        assertEquals("msg_admit1", row.id)
        assertEquals(ChatStore.PENDING_ROLE, row.role)
        assertTrue(ChatStore.isPendingRow(row))
        assertTrue(ChatStore.isClientRow(row))
        assertEquals("hello", ChatStore.pendingRowText(row))
    }

    @Test
    fun `the row shows the user's own words and counts the attachments`() {
        val row = handoffPromptRow("msg_a", "look at this", 2, emptyList())
        assertEquals("look at this", ChatStore.pendingRowText(row))
        assertEquals(
            "one chip per attachment, and no url — the content is inline on the wire",
            2,
            row.parts.count { it.type == "file" },
        )
        assertTrue(row.parts.filter { it.type == "file" }.none { it.url != null })
    }

    @Test
    fun `a prompt carrying review comments keeps its note-free text and its cards`() {
        val text = "The user made the following comment regarding this file of app/build.gradle.kts: bump it\n" +
            "why is this here?"
        val note = com.xiufeigo.opencodeshell.ui.session.review.ReviewNote(
            path = "app/build.gradle.kts",
            startLine = 1,
            endLine = 1,
            comment = "bump it",
        )
        val row = handoffPromptRow("msg_c", text, 0, listOf(note))
        assertEquals(
            "upstream reads metadata.displayText — the note line is a comment card, not body text",
            "why is this here?",
            ChatStore.pendingRowText(row),
        )
        val card = row.parts.single { it.type == com.xiufeigo.opencodeshell.ui.session.timeline.TimelineComments.PART_TYPE }
        assertEquals("bump it", card.text)
        assertEquals("app/build.gradle.kts", card.filename)
    }

    @Test
    fun `the delivered message replaces the row in place instead of doubling it`() {
        // The hand-over the placeholder exists for: same admission id, server
        // role, so `ChatStore.upsert` swaps the row rather than adding a second.
        val placeholder = handoffPromptRow("msg_x", "hi", 0, emptyList())
        val delivered = ChatStore.UiMessage(
            id = "msg_x",
            role = "user",
            timeCreated = placeholder.timeCreated + 1,
            parts = listOf(ChatStore.UiPart(id = "p1", type = "text", text = "hi")),
        )
        val state = ChatStore.reduce(
            ChatStore.ChatState(),
            ChatStore.Event.InboxPendingSteers(listOf(placeholder)),
        )
        val after = ChatStore.reduce(state, ChatStore.Event.MessageUpdated(delivered))
        assertEquals(listOf("msg_x"), after.messages.map { it.id })
        assertEquals("user", after.messages.single().role)
    }

    @Test
    fun `a snapshot that never mentions the admission leaves the row on screen`() {
        // The v58 case exactly: the server aborted before writing anything, so
        // the transcript read back after the Stop is empty. The user's prompt
        // must survive that read — it is the only evidence the turn existed.
        val state = ChatStore.reduce(
            ChatStore.ChatState(),
            ChatStore.Event.InboxPendingSteers(listOf(handoffPromptRow("msg_s", "why", 0, emptyList()))),
        )
        val after = ChatStore.reduce(state, ChatStore.Event.Snapshot(emptyList()))
        assertEquals(listOf("msg_s"), after.messages.map { it.id })
    }

    // ------------------------------------------------------ 2. the wiring

    @Test
    fun `an admitted steer gets its row at submit time`() {
        val body = window(vm, "fun submit(", "private fun filesOf(")
        assertTrue(
            "the row must be raised where the admission id is known, not left to a later snapshot",
            body.contains("admitHandoffRow(id, admittedID, text,"),
        )
        val admit = body.indexOf("admitHandoffRow(")
        val queue = body.indexOf("wireDelivery == \"queue\"")
        assertTrue(
            "a queue admission belongs to the queue panel, which owns its own rows",
            admit > queue,
        )
    }

    @Test
    fun `the row is not a parked-steer one the inbox read may sweep away`() {
        val body = window(vm, "private fun refreshInbox()", "Log.w(TAG, \"refresh inbox failed")
        assertTrue(
            "a prompt whose turn was aborted leaves the inbox, and the row is then the only trace",
            body.contains("handoffPrompts.removeAll { isMessageDurable(it) }") &&
                body.contains("it.id in handoffPrompts"),
        )
        assertTrue(
            "a handoff the server did publish is retired first",
            body.indexOf("handoffPrompts.removeAll") < body.indexOf("it.id in handoffPrompts"),
        )
    }

    @Test
    fun `the row belongs to the session it was admitted in`() {
        val body = window(vm, "private val pendingDeliveries", "private val handoffPrompts")
        assertTrue("per-session state, like the steer watchdog", body.isNotEmpty())
        for (anchor in listOf("fun observeSession(", "fun newSession(")) {
            val cleared = window(vm, anchor, "\n    }")
            assertTrue(
                "$anchor must not inherit another session's handoff rows",
                cleared.contains("handoffPrompts.clear()"),
            )
        }
    }

    @Test
    fun `a failed submit raises no row`() {
        val body = window(vm, "fun submit(", "private fun filesOf(")
        val admit = body.indexOf("admitHandoffRow(")
        val catchBlock = body.indexOf("} catch (e: Exception) {")
        assertTrue(
            "the row is raised only after the server answered with an admission id",
            admit in 0 until catchBlock,
        )
        assertFalse(
            "a POST that threw has no admission, so the failure path must not raise one",
            body.substring(catchBlock).contains("admitHandoffRow("),
        )
    }
}
