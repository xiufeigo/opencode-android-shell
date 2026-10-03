package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.terminal.createErrorText
import com.xiufeigo.opencodeshell.ui.terminal.mergeShellRows
import com.xiufeigo.opencodeshell.ui.terminal.unresolvedShellPins
import com.xiufeigo.opencodeshell.v2.ShellCommandInfo
import com.xiufeigo.opencodeshell.v2.V2Api
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The background-shell panel pins a create response into the list because the
 * server lists running shells only: a command that exits before the next poll
 * must still appear with its terminal status instead of vanishing silently.
 */
class BackgroundShellMergeTest {

    private fun shell(
        id: String,
        status: String = ShellCommandInfo.STATUS_RUNNING,
        started: Long = 1_000L,
        exit: Int? = null,
    ) = ShellCommandInfo(
        id = id,
        status = status,
        command = "cmd $id",
        cwd = "/w",
        shell = "/bin/sh",
        file = "/tmp/out",
        pid = 7,
        exit = exit,
        metadata = JSONObject(),
        timeStarted = started,
        timeCompleted = if (exit == null) null else started + 1,
        raw = JSONObject(),
    )

    @Test
    fun instantExitPinSurvivesAnEmptyList() {
        val exited = shell("sh_9", ShellCommandInfo.STATUS_EXITED, exit = 127)
        val rows = mergeShellRows(emptyList(), listOf(exited))
        assertEquals(listOf("sh_9"), rows.map { it.id })
        assertFalse(rows.single().running)
        assertEquals(127, rows.single().exit)
    }

    @Test
    fun listedRowWinsOnIdConflict() {
        val listed = shell("sh_1", ShellCommandInfo.STATUS_RUNNING, started = 2_000L)
        val stalePin = shell("sh_1", ShellCommandInfo.STATUS_RUNNING, started = 1_000L)
        val rows = mergeShellRows(listOf(listed), listOf(stalePin))
        assertEquals(1, rows.size)
        assertEquals(2_000L, rows.single().timeStarted)
    }

    @Test
    fun blankIdPinsAreDropped() {
        assertTrue(mergeShellRows(emptyList(), listOf(shell(""))).isEmpty())
    }

    @Test
    fun runningFirstOrderHoldsAcrossListAndPins() {
        val rows = mergeShellRows(
            listOf(shell("old-run", started = 1_000L)),
            listOf(
                shell("new-exit", ShellCommandInfo.STATUS_EXITED, started = 3_000L, exit = 1),
                shell("new-run", started = 4_000L),
            ),
        )
        assertEquals(listOf("new-run", "old-run", "new-exit"), rows.map { it.id })
    }

    @Test
    fun unresolvedIsOnlyRunningPinsMissingFromTheList() {
        val pins = mapOf(
            "gone-run" to shell("gone-run"),
            "gone-exit" to shell("gone-exit", ShellCommandInfo.STATUS_EXITED, exit = 0),
            "still-there" to shell("still-there"),
        )
        val unresolved = unresolvedShellPins(pins, setOf("still-there"))
        assertEquals(listOf("gone-run"), unresolved.map { it.id })
    }

    @Test
    fun exitedInfoParsesWithExitCode() {
        val info = ShellCommandInfo.fromJson(
            JSONObject(
                """{"id":"sh_2","status":"exited","command":"false","cwd":"/w",""" +
                    """"shell":"/bin/sh","file":"/tmp/out","pid":9,"exit":1,"metadata":{},""" +
                    """"time":{"started":1789990000000,"completed":1789990000500}}""",
            ),
        )
        assertFalse(info.running)
        assertEquals(1, info.exit)
        assertEquals(1789990000500L, info.timeCompleted)
        // The pinned terminal row renders through the same merge path.
        assertEquals("sh_2", mergeShellRows(emptyList(), listOf(info)).single().id)
    }

    @Test
    fun createErrorTextKeepsTheServerReason() {
        val cause = V2Api.ApiException(400, "HTTP 400 InvalidRequestError: bad cwd")
        assertEquals("HTTP 400 InvalidRequestError: bad cwd", createErrorText(cause, "fallback"))
    }

    @Test
    fun createErrorTextFallsBackOnBlankOrMissingMessage() {
        assertEquals("fallback", createErrorText(IOException("   "), "fallback"))
        assertEquals("fallback", createErrorText(IOException(), "fallback"))
        assertEquals("fallback", createErrorText(null, "fallback"))
    }
}
