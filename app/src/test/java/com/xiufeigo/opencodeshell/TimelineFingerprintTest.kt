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
}
