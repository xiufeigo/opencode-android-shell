package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.timeline.appendFingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TimelineFingerprintTest {

    private fun part(id: String, text: String, tool: String? = null, status: String? = null, output: String? = null) =
        ChatStore.UiPart(
            id = id,
            type = if (tool != null) "tool" else "text",
            text = text,
            toolName = tool,
            toolStatus = status,
            toolOutput = output,
        )

    private fun message(id: String, parts: List<ChatStore.UiPart>, role: String = "assistant") =
        ChatStore.UiMessage(id = id, role = role, timeCreated = 1L, parts = parts)

    @Test
    fun emptyVsNonEmptyDiffer() {
        assertNotEquals(appendFingerprint(emptyList()), appendFingerprint(listOf(message("m1", listOf(part("p1", "hi"))))))
    }

    @Test
    fun sameStateIsIdempotent() {
        val a = listOf(message("m1", listOf(part("p1", "hi"), part("p2", "", "bash", "running"))))
        assertEquals(appendFingerprint(a), appendFingerprint(a))
    }

    @Test
    fun messageCountChangesFingerprint() {
        val one = listOf(message("m1", listOf(part("p1", "hi"))))
        val two = listOf(message("m1", listOf(part("p1", "hi"))), message("m2", listOf(part("p2", "bye"))))
        assertNotEquals(appendFingerprint(one), appendFingerprint(two))
    }

    @Test
    fun newPartChangesFingerprint() {
        val before = listOf(message("m1", listOf(part("p1", "hi"))))
        val afterTool = listOf(message("m1", listOf(part("p1", "hi"), part("p2", "", "bash", "running"))))
        assertNotEquals(appendFingerprint(before), appendFingerprint(afterTool))
    }

    @Test
    fun streamedTextGrowthChangesFingerprint() {
        val short = listOf(message("m1", listOf(part("p1", "hello"))))
        val grown = listOf(message("m1", listOf(part("p1", "hello world"))))
        assertNotEquals(appendFingerprint(short), appendFingerprint(grown))
    }

    @Test
    fun toolStatusChangeChangesFingerprint() {
        val running = listOf(message("m1", listOf(part("p2", "", "bash", "running"))))
        val done = listOf(message("m1", listOf(part("p2", "", "bash", "completed"))))
        assertNotEquals(appendFingerprint(running), appendFingerprint(done))
    }

    @Test
    fun toolOutputGrowthChangesFingerprint() {
        val empty = listOf(message("m1", listOf(part("p2", "", "bash", "completed", ""))))
        val filled = listOf(message("m1", listOf(part("p2", "", "bash", "completed", "some output"))))
        assertNotEquals(appendFingerprint(empty), appendFingerprint(filled))
    }

    @Test
    fun handoverOfTheTrailingPlaceholderChangesFingerprint() {
        // The delivery swaps the trailing parked steer for the user message in
        // place — same id, same text length — restyling the bubble without
        // touching any part the old probe read, so the follow never re-pinned
        // the bottom after a post-Stop send.
        val parked = listOf(
            ChatStore.UiMessage(
                id = "msg_u2",
                role = ChatStore.PENDING_ROLE,
                timeCreated = 2L,
                parts = listOf(part("msg_u2:pending:text", "vwrite a 200 word essay")),
            ),
        )
        val delivered = listOf(
            ChatStore.UiMessage(
                id = "msg_u2",
                role = "user",
                timeCreated = 3L,
                parts = listOf(part("msg_u2:text", "vwrite a 200 word essay")),
            ),
        )
        assertNotEquals(appendFingerprint(parked), appendFingerprint(delivered))
    }

    @Test
    fun settlingTheTrailingMessageChangesFingerprint() {
        // A Stop flags the trailing message, pinning the `Interrupted` divider
        // below it — again with no part edit, so again invisible to the old
        // probe and again unpinned until the next scroll.
        val running = listOf(message("m1", listOf(part("p1", "partial"))))
        val settled = listOf(message("m1", listOf(part("p1", "partial"))).copy(interrupted = true))
        assertNotEquals(appendFingerprint(running), appendFingerprint(settled))
    }
}
