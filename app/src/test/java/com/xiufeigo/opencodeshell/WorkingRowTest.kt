package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.timeline.NO_RESPONSE_THRESHOLD_MS
import com.xiufeigo.opencodeshell.ui.session.timeline.showWorkingRow
import com.xiufeigo.opencodeshell.ui.session.timeline.stallNoticeMinutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `showWorking()` (`_upstream/packages/app/src/session/timeline/
 * message-timeline.tsx:331-372`): the `Working` row is the fallback for "busy,
 * but nothing visible to look at yet".
 */
class WorkingRowTest {

    private fun part(
        id: String,
        type: String,
        toolName: String? = null,
        toolStatus: String? = null,
        text: String = "",
    ) = ChatStore.UiPart(
        id = id,
        type = type,
        toolName = toolName,
        toolStatus = toolStatus,
        text = text,
    )

    private fun message(id: String, role: String, parts: List<ChatStore.UiPart>) =
        ChatStore.UiMessage(id = id, role = role, timeCreated = 1L, parts = parts)

    private fun assistant(parts: List<ChatStore.UiPart>) = message("m2", "assistant", parts)

    @Test
    fun onlyRunsWhileTheTurnIsBusy() {
        val messages = listOf(message("m1", "user", listOf(part("p1", "text", text = "hi"))))
        assertFalse(showWorkingRow(messages, ChatStore.RunStatus.IDLE))
        assertFalse(showWorkingRow(messages, ChatStore.RunStatus.ERROR))
        // The newest message is still the user's prompt.
        assertFalse(showWorkingRow(messages, ChatStore.RunStatus.RUNNING))
    }

    @Test
    fun showsForAnEmptyTrailingAssistantMessage() {
        val messages = listOf(
            message("m1", "user", emptyList()),
            assistant(emptyList()),
        )
        assertTrue(showWorkingRow(messages, ChatStore.RunStatus.RUNNING))
    }

    @Test
    fun hiddenWhileThereIsAPendingRequest() {
        val messages = listOf(assistant(emptyList()))
        assertFalse(showWorkingRow(messages, ChatStore.RunStatus.RUNNING, hasPendingRequest = true))
    }

    @Test
    fun hiddenOnceTextIsVisible() {
        val writing = listOf(assistant(listOf(part("p1", "text", text = "hello"))))
        assertFalse(showWorkingRow(writing, ChatStore.RunStatus.RUNNING))
        // A blank text part is not visible content.
        val blank = listOf(assistant(listOf(part("p1", "text", text = " "))))
        assertTrue(showWorkingRow(blank, ChatStore.RunStatus.RUNNING))
    }

    @Test
    fun aRetryPartWinsOverTheWorkingRow() {
        val retrying = listOf(assistant(listOf(part("p1", "retry"))))
        assertFalse(showWorkingRow(retrying, ChatStore.RunStatus.RUNNING))
        // … even when an earlier text part is already visible.
        val both = listOf(assistant(listOf(part("p1", "text", text = "x"), part("p2", "retry"))))
        assertFalse(showWorkingRow(both, ChatStore.RunStatus.RUNNING))
    }

    @Test
    fun aStandaloneRunningToolIsVisibleActivity() {
        // `question` renders standalone even under the compact preset, so a
        // running one is visible activity of its own.
        val standalone = listOf(
            assistant(listOf(part("p1", "tool", toolName = "question", toolStatus = "running"))),
        )
        assertFalse(showWorkingRow(standalone, ChatStore.RunStatus.RUNNING))
    }

    @Test
    fun groupedToolsKeepTheFallback() {
        // Upstream keeps `Working` for `context` groups regardless of disclosure
        // (`message-timeline.tsx:362-363`).
        val grouped = listOf(assistant(listOf(part("p1", "tool", toolName = "read", toolStatus = "running"))))
        assertTrue(showWorkingRow(grouped, ChatStore.RunStatus.RUNNING))
    }

    @Test
    fun stepPartsAreNotVisibleActivity() {
        val messages = listOf(
            assistant(
                listOf(
                    part("p1", "step-start"),
                    part("p2", "step-finish"),
                    part("p3", "snapshot"),
                ),
            ),
        )
        assertTrue(showWorkingRow(messages, ChatStore.RunStatus.RUNNING))
    }

    // ------------------------------------------------- the stall notice (P1)

    /**
     * Device P1, 2026-09-25: a turn whose provider call hangs keeps the timeline
     * byte-identical to a busy one, so the shimmering `Working` label sat on
     * screen for the whole 38-minute silence with nothing telling the user that
     * nothing was arriving. [stallNoticeMinutes] is the pure half of the fix: the
     * notice appears only after the threshold, and the elapsed minutes it shows
     * are the ones the user can count on.
     */
    @Test
    fun theStallNoticeWaitsForTheThreshold() {
        assertEquals(0, stallNoticeMinutes(0L))
        assertEquals(0, stallNoticeMinutes(NO_RESPONSE_THRESHOLD_MS - 1))
        // Inclusive: exactly two minutes of silence is the first moment the
        // notice may claim "2 min".
        assertEquals(2, stallNoticeMinutes(NO_RESPONSE_THRESHOLD_MS))
        assertEquals(2, stallNoticeMinutes(179_999L))
        assertEquals(3, stallNoticeMinutes(180_000L))
        // The reported hang: 38 minutes.
        assertEquals(38, stallNoticeMinutes(38L * 60_000L))
    }

    /**
     * The notice shares the `Working` slot, so it can only ever appear where the
     * row itself appears — while the run is busy *and* nothing visible explains
     * the wait. Every guard below is the one [showWorkingRow] already enforces,
     * which is why the two can never contradict each other.
     */
    @Test
    fun theStallNoticeOnlyReplacesAVisibleWorkingRow() {
        val empty = listOf(assistant(emptyList()))
        assertTrue(showWorkingRow(empty, ChatStore.RunStatus.RUNNING))
        assertFalse(showWorkingRow(empty, ChatStore.RunStatus.IDLE))
        assertFalse(showWorkingRow(empty, ChatStore.RunStatus.RUNNING, hasPendingRequest = true))
        // Visibly writing, a live retry card, or a standalone running tool: the
        // row is off, so the notice is off (server activity is already on screen).
        assertFalse(
            showWorkingRow(
                listOf(assistant(listOf(part("p1", "text", text = "still writing")))),
                ChatStore.RunStatus.RUNNING,
            ),
        )
        assertFalse(showWorkingRow(listOf(assistant(listOf(part("p1", "retry")))), ChatStore.RunStatus.RUNNING))
        assertFalse(
            showWorkingRow(
                listOf(assistant(listOf(part("p1", "tool", toolName = "question", toolStatus = "running")))),
                ChatStore.RunStatus.RUNNING,
            ),
        )
    }
}
