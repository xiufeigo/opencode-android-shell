package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.export.IMPORT_READ_TIMEOUT_MS
import com.xiufeigo.opencodeshell.ui.session.export.IMPORT_REQUEST_TIMEOUT_MS
import com.xiufeigo.opencodeshell.ui.session.export.ImportFinish
import com.xiufeigo.opencodeshell.ui.session.export.SessionExportFallback
import com.xiufeigo.opencodeshell.ui.session.export.SessionImporter
import com.xiufeigo.opencodeshell.v2.SessionTransferData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1 watchdog coverage for the SAF import path ([SessionImporter]): no stage
 * may hang forever, every terminal state reaches exactly one callback, and a
 * late picker answer after a reclaim is ignored.
 *
 * Timeout branches run on an injected [sleeper] rather than wall-clock time —
 * the suite does no real waiting (the only real `delay` is a 60s timer that is
 * always cancelled before firing). A `Uri` instance needs an Android runtime,
 * so the tests drive [SessionImporter.readAndImport] with a plain thunk and
 * [SessionImporter.deliver] with `null`; the production `Uri` wiring in
 * `deliver` is a three-line delegation over the covered logic.
 */
class SessionImporterTest {

    private val validJson = """{"info":{"id":"ses_1"},"messages":[{"info":{"id":"m1"}}]}"""

    private fun importer(
        launched: MutableList<Unit> = mutableListOf(),
        launch: () -> Unit = { launched.add(Unit) },
        hasHandler: Boolean = true,
        readTimeoutMs: Long = 10_000L,
        importTimeoutMs: Long = 10_000L,
        pickerTimeoutMs: Long = 60_000L,
        sleeperCalls: MutableList<Long> = mutableListOf(),
        sleeper: suspend (Long) -> Unit = { sleeperCalls.add(it) },
        import: suspend (SessionTransferData) -> String? = { "ses_new" },
    ): SessionImporter = SessionImporter(
        readBytes = { error("tests drive readAndImport directly") },
        launchOpenDocument = launch,
        importTransfer = import,
        hasOpenHandler = { hasHandler },
        readTimeoutMs = readTimeoutMs,
        importTimeoutMs = importTimeoutMs,
        pickerTimeoutMs = pickerTimeoutMs,
        sleeper = sleeper,
    )

    private fun testScope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    // ------------------------------------------------------------ read+import

    @Test
    fun validDocumentImportsAndHandsOverTheParsedTransfer() = runBlocking {
        var seen: SessionTransferData? = null
        val outcome = importer(import = { transfer -> seen = transfer; "ses_new" })
            .readAndImport { validJson }
        assertEquals(ImportFinish.Imported("ses_new"), outcome)
        assertEquals("ses_1", seen?.info?.optString("id"))
        assertEquals(1, seen?.messages?.size)
    }

    @Test
    fun missingMessagesHalfIsAnEmptyListNeverAnError() = runBlocking {
        var seen: SessionTransferData? = null
        val outcome = importer(import = { transfer -> seen = transfer; "ses_new" })
            .readAndImport { """{"info":{"id":"ses_1"}}""" }
        assertEquals(ImportFinish.Imported("ses_new"), outcome)
        assertEquals(0, seen?.messages?.size)
    }

    @Test
    fun nullBytesAreAFailureNeverAHang() = runBlocking {
        val outcome = withTimeout(5_000) { importer().readAndImport { null } }
        assertEquals(ImportFinish.Failed(timedOut = false), outcome)
    }

    @Test
    fun malformedJsonAndMissingInfoAreFailures() = runBlocking {
        assertEquals(ImportFinish.Failed(timedOut = false), importer().readAndImport { "nope" })
        assertEquals(
            ImportFinish.Failed(timedOut = false),
            importer().readAndImport { """{"messages":[]}""" },
        )
    }

    @Test
    fun throwingReadIsAFailureNeverACrash() = runBlocking {
        val outcome = withTimeout(5_000) { importer().readAndImport { error("provider died") } }
        assertEquals(ImportFinish.Failed(timedOut = false), outcome)
    }

    @Test
    fun stalledReadFailsAsTimedOutWithoutRealWaiting() = runBlocking {
        val gate = CompletableDeferred<String?>()
        val sleeperCalls = mutableListOf<Long>()
        val outcome = withTimeout(5_000) {
            importer(sleeperCalls = sleeperCalls).readAndImport { gate.await() }
        }
        assertEquals(ImportFinish.Failed(timedOut = true), outcome)
        assertEquals(listOf(10_000L), sleeperCalls)
    }

    @Test
    fun throwingRouteIsAFailureNeverACrash() = runBlocking {
        val outcome = withTimeout(5_000) {
            importer(import = { error("409") }).readAndImport { validJson }
        }
        assertEquals(ImportFinish.Failed(timedOut = false), outcome)
    }

    @Test
    fun nullRouteAnswerIsAFailure() = runBlocking {
        val outcome = withTimeout(5_000) {
            importer(import = { null }).readAndImport { validJson }
        }
        assertEquals(ImportFinish.Failed(timedOut = false), outcome)
    }

    @Test
    fun stalledRouteFailsAsTimedOutWithoutRealWaiting() = runBlocking {
        val gate = CompletableDeferred<String?>()
        val outcome = withTimeout(5_000) {
            importer(import = { gate.await() }).readAndImport { validJson }
        }
        assertEquals(ImportFinish.Failed(timedOut = true), outcome)
    }

    @Test
    fun slowTimerStillLetsASuccessThrough() = runBlocking {
        // The 60s timer never fires; the win cancels it, so this returns fast.
        val outcome = withTimeout(5_000) {
            importer(sleeper = { delay(60_000) }).readAndImport { validJson }
        }
        assertEquals(ImportFinish.Imported("ses_new"), outcome)
    }

    // ------------------------------------------------------- picker watchdog

    @Test
    fun dismissedLegacyPickerStaysSilent() = runBlocking {
        val importer = importer()
        var called = false
        importer.importSession { called = true }
        assertTrue(importer.hasPending)
        importer.deliver(null)
        assertFalse(called)
        assertFalse(importer.hasPending)
    }

    @Test
    fun dismissedScopedPickerIsACancel() = runBlocking {
        val scope = testScope()
        try {
            val importer = importer(sleeper = { CompletableDeferred<Unit>().await() })
            val finishes = mutableListOf<ImportFinish>()
            importer.importSession(scope) { finishes.add(it) }
            importer.deliver(null)
            assertEquals(listOf(ImportFinish.Cancelled), finishes)
            assertFalse(importer.hasPending)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun silentPickerTripsTheWatchdogAndLateAnswersAreIgnored() = runBlocking {
        val scope = testScope()
        try {
            val sleeperCalls = mutableListOf<Long>()
            val importer = importer(pickerTimeoutMs = 60_000L, sleeperCalls = sleeperCalls)
            val finishes = mutableListOf<ImportFinish>()
            importer.importSession(scope) { finishes.add(it) }
            // Unconfined runs the watchdog eagerly; the immediate sleeper fires it.
            assertEquals(listOf(ImportFinish.Failed(timedOut = true)), finishes)
            assertEquals(listOf(60_000L), sleeperCalls)
            assertFalse(importer.hasPending)
            importer.deliver(null) // late answer — must not double-apply
            assertEquals(1, finishes.size)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun reclaimCancelsTheWatchdog() = runBlocking {
        val scope = testScope()
        try {
            val gate = CompletableDeferred<Unit>()
            val importer = importer(sleeper = { gate.await() })
            val finishes = mutableListOf<ImportFinish>()
            importer.importSession(scope) { finishes.add(it) }
            assertTrue(importer.hasPending)
            assertTrue(importer.cancelPending())
            assertFalse(importer.hasPending)
            gate.complete(Unit) // the watchdog wakes after the reclaim — nothing may fire
            withTimeout(5_000) { gate.await() }
            assertTrue(finishes.isEmpty())
            importer.deliver(null)
            assertTrue(finishes.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun secondImportWhilePendingFailsFast() = runBlocking {
        val scope = testScope()
        try {
            val importer = importer(sleeper = { CompletableDeferred<Unit>().await() })
            var legacyCalled = false
            importer.importSession { legacyCalled = true }
            val finishes = mutableListOf<ImportFinish>()
            importer.importSession(scope) { finishes.add(it) }
            assertEquals(listOf(ImportFinish.Failed(timedOut = false)), finishes)
            // The first request is still the pending one; reclaim drops it silently.
            assertTrue(importer.cancelPending())
            assertFalse(legacyCalled)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun throwingLauncherReportsFailureInsteadOfThrowing() {
        val legacy = mutableListOf<String?>()
        importer(launch = { error("no activity") }).importSession { legacy.add(it) }
        assertEquals(listOf(null), legacy)

        val scope = testScope()
        try {
            val finishes = mutableListOf<ImportFinish>()
            importer(launch = { error("no activity") }).importSession(scope) { finishes.add(it) }
            assertEquals(listOf(ImportFinish.Failed(timedOut = false)), finishes)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun missingHandlerSkipsThePickerAndFailsFast() {
        val launched = mutableListOf<Unit>()
        val scope = testScope()
        try {
            val finishes = mutableListOf<ImportFinish>()
            importer(launched = launched, hasHandler = false)
                .importSession(scope) { finishes.add(it) }
            assertEquals(listOf(ImportFinish.Failed(timedOut = false)), finishes)
            assertTrue(launched.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    // ---------------------------------------------------------------- bounds

    @Test
    fun timeoutsAreFiniteButNotTriggerHappy() {
        assertTrue(IMPORT_READ_TIMEOUT_MS in 1_000L..120_000L)
        assertTrue(IMPORT_REQUEST_TIMEOUT_MS in 1_000L..120_000L)
    }

    @Test
    fun pickerWatchdogDefaultsToTheSharedSafTimeout() = runBlocking {
        val scope = testScope()
        try {
            val sleeperCalls = mutableListOf<Long>()
            importer(sleeperCalls = sleeperCalls).importSession(scope) { }
            assertEquals(listOf(SessionExportFallback.SAF_TIMEOUT_MS), sleeperCalls)
        } finally {
            scope.cancel()
        }
    }
}
