package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.terminal.share.SHELL_LIST_POLL_MS
import com.xiufeigo.opencodeshell.ui.terminal.share.ShellCreateOutcome
import com.xiufeigo.opencodeshell.ui.terminal.share.sortShellRows
import com.xiufeigo.opencodeshell.v2.ShellCommandInfo
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [sortShellRows] — the background-shell list ordering the panel polls with:
 * running shells first, newest first within each group.
 */
class BackgroundShellsTest {

    private fun shell(id: String, status: String, started: Long): ShellCommandInfo =
        ShellCommandInfo.fromJson(
            JSONObject()
                .put("id", id)
                .put("status", status)
                .put("command", "cmd $id")
                .put("time", JSONObject().put("started", started)),
        )

    @Test
    fun `running shells sort before finished ones regardless of age`() {
        val rows = listOf(
            shell("old-done", ShellCommandInfo.STATUS_EXITED, started = 10L),
            shell("new-running", ShellCommandInfo.STATUS_RUNNING, started = 5L),
        )
        assertEquals(listOf("new-running", "old-done"), sortShellRows(rows).map { it.id })
    }

    @Test
    fun `newest first within each status group`() {
        val rows = listOf(
            shell("run-old", ShellCommandInfo.STATUS_RUNNING, started = 1L),
            shell("run-new", ShellCommandInfo.STATUS_RUNNING, started = 9L),
            shell("done-old", ShellCommandInfo.STATUS_KILLED, started = 2L),
            shell("done-new", ShellCommandInfo.STATUS_TIMEOUT, started = 8L),
        )
        assertEquals(
            listOf("run-new", "run-old", "done-new", "done-old"),
            sortShellRows(rows).map { it.id },
        )
    }

    @Test
    fun `empty and singleton lists pass through`() {
        assertTrue(sortShellRows(emptyList()).isEmpty())
        val one = listOf(shell("s1", ShellCommandInfo.STATUS_RUNNING, started = 1L))
        assertEquals(listOf("s1"), sortShellRows(one).map { it.id })
    }

    @Test
    fun `the list poll fires within seconds`() {
        assertTrue(SHELL_LIST_POLL_MS in 1_000L..5_000L)
    }

    @Test
    fun `a created shell closes the form and refetches`() {
        assertEquals(
            listOf(ShellCreateOutcome.Effect.CloseForm, ShellCreateOutcome.Effect.Refetch),
            ShellCreateOutcome.effects("sh_1"),
        )
    }

    @Test
    fun `a failed create reports and still refetches`() {
        // The server may have created it despite the error.
        for (id in listOf(null, "", "  ")) {
            assertEquals(
                listOf(ShellCreateOutcome.Effect.ShowCreateError, ShellCreateOutcome.Effect.Refetch),
                ShellCreateOutcome.effects(id),
            )
        }
    }

    @Test
    fun `blank commands never reach the server`() {
        assertEquals("sleep 60", ShellCreateOutcome.cleanCommand("  sleep 60\n"))
        for (raw in listOf("", "   ", "\n\t ")) {
            assertNull(raw, ShellCreateOutcome.cleanCommand(raw))
        }
    }
}
