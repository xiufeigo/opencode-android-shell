package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.export.EXPORT_FETCH_TIMEOUT_MS
import com.xiufeigo.opencodeshell.ui.session.export.ExportStageResult
import com.xiufeigo.opencodeshell.ui.session.export.SessionExportFallback
import com.xiufeigo.opencodeshell.ui.session.export.withExportDeadline
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The export payload read's deadline ([withExportDeadline]).
 *
 * v9: `⋮ → Export session` spun for 12–75 s on a read that never answered, and
 * the mask then closed with no message — the screen awaited the transport
 * forever, so there was nothing to report and nothing to time out. This suite
 * pins the three ways the race can end and, above all, that the caller returns
 * **on the deadline** rather than when the abandoned read finally gives up.
 *
 * Time runs on the injected `sleeper`, exactly like [SessionImporterTest]: no
 * test waits for wall-clock time.
 */
class SessionExportDeadlineTest {

    private fun testScope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Test
    fun anAnswerInsideTheDeadlineIsReturnedAsOk() = runBlocking {
        // a timer that never fires: the block is the only side that can finish
        val result = withExportDeadline(testScope(), sleeper = { awaitCancellation() }) { 41 }
        assertEquals(ExportStageResult.Ok(41), result)
    }

    @Test
    fun aSilentReadLosesToTheDeadlineAndIsCancelled() = runBlocking {
        val abandoned = CompletableDeferred<Unit>()
        val result = withExportDeadline(testScope(), timeoutMs = 60_000L, sleeper = { }) {
            try {
                awaitCancellation()
            } finally {
                // the loser is cancelled, not left running to write a second
                // outcome later
                abandoned.complete(Unit)
            }
        }
        assertEquals(ExportStageResult.TimedOut, result)
        withTimeout(1_000L) { abandoned.await() }
    }

    @Test
    fun aLateAnswerAfterTheDeadlineIsIgnored() = runBlocking {
        val gate = CompletableDeferred<Int>()
        val result = withExportDeadline(testScope(), sleeper = { }) { gate.await() }
        assertEquals(ExportStageResult.TimedOut, result)
        // the abandoned read may still answer; nobody is listening, and the
        // caller has already reported
        gate.complete(7)
        assertEquals(ExportStageResult.TimedOut, result)
    }

    @Test
    fun aThrowingReadIsAFailureNotATimeout() = runBlocking {
        val result = withExportDeadline(testScope(), sleeper = { awaitCancellation() }) {
            error("requireTransport blew up")
        }
        assertTrue(result is ExportStageResult.Failed)
        assertEquals("requireTransport blew up", (result as ExportStageResult.Failed).cause.message)
    }

    @Test
    fun theDeadlineKeepsTheExportPathOrderOfMagnitude() {
        // Same order as the two other watches the export path runs — the SAF
        // dialog and the import route — and above the transport's own 30 s
        // read timeout, so only a genuinely wedged read is cut off.
        assertEquals(60_000L, EXPORT_FETCH_TIMEOUT_MS)
        assertEquals(EXPORT_FETCH_TIMEOUT_MS, SessionExportFallback.SAF_TIMEOUT_MS)
    }
}
