package com.xiufeigo.opencodeshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v35 tB BUG-2: after an `adb reverse` outage that came back, Home showed
 * 「Nothing here yet」 over a project that has sessions, and only a process
 * restart listed them again (`research/v35_verify/tB_lifecycle/51_search.png`,
 * `55_restart.png`).
 *
 * Two halves, both pinned here:
 *
 * - **a failed read must not answer an empty list.** Home's column is
 *   `remember`ed, so a route change during the outage hands it a fresh empty
 *   list; the failed read that followed kept it empty and marked it as a
 *   confirmed read. `ChatViewModel.listSessions` now answers the last list it
 *   did read ([ChatViewModel.lastSessionRows]) with [ChatViewModel.sessionsLoadFailed]
 *   still reporting the failure.
 * - **something must re-read once the connection is back.** Home opens no
 *   stream (M1 contract), so nothing did: the failed read schedules a re-drive
 *   through `sessionsRevision` — the channel every list surface already
 *   listens on — with a growing backoff, cancelled by the first landed read.
 *
 * The read itself also rides the shared transient-retry seam now, so a single
 * blip no longer surfaces as a permanent failure. The ViewModel needs an
 * Android `Context`, so the wiring is pinned against the source function-window
 * (the technique [HomeColdStartTest] uses for the same screen); the backoff is
 * pure and tested for real.
 */
class HomeSessionsReconnectTest {

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

    private infix fun String.count(needle: String): Int = split(needle).size - 1

    private val vm by lazy {
        source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")
    }

    // ------------------------------------------------- 1. the self-heal cadence

    @Test
    fun theSelfHealBackoffGrowsThenCaps() {
        assertEquals(SESSION_LIST_RETRY_DELAY_MS, sessionListRetryDelayMs(0))
        assertEquals(SESSION_LIST_RETRY_DELAY_MS * 2, sessionListRetryDelayMs(1))
        assertEquals(SESSION_LIST_RETRY_DELAY_MS * 4, sessionListRetryDelayMs(2))
        assertEquals(SESSION_LIST_RETRY_MAX_DELAY_MS, sessionListRetryDelayMs(4))
        assertEquals(
            "a long outage costs one read a minute, not a spin",
            SESSION_LIST_RETRY_MAX_DELAY_MS,
            sessionListRetryDelayMs(500),
        )
        assertEquals("a negative round is still the first step", SESSION_LIST_RETRY_DELAY_MS, sessionListRetryDelayMs(-3))
        assertTrue(
            "the cadence never shortens",
            (0..8).zipWithNext().all { (a, b) -> sessionListRetryDelayMs(a) <= sessionListRetryDelayMs(b) },
        )
        assertTrue("and it is never zero (that would be a hot loop)", sessionListRetryDelayMs(0) > 0)
    }

    // --------------------------------------- 2. a failed read keeps the rows

    @Test
    fun aFailedReadAnswersTheLastGoodRowsNotAnEmptyList() {
        val body = window(vm, "fun listSessions(onResult", "private fun scheduleSessionListRetry()")
        assertFalse(
            "an empty answer is what wiped Home's column",
            body.contains("onResult(emptyList())"),
        )
        assertEquals("both failure paths answer the cache", 2, body count "onResult(lastSessionRows)")
        assertEquals("…and both schedule the re-read", 2, body count "scheduleSessionListRetry()")
        assertTrue("a landed read refreshes the cache", body.contains("lastSessionRows = rows"))
    }

    @Test
    fun theFailureIsStillReportedAndTheRetryIsCancelledBySuccess() {
        val body = window(vm, "fun listSessions(onResult", "private fun scheduleSessionListRetry()")
        assertTrue("callers must still be able to tell a failed read", body.contains("_sessionsLoadFailed.value = sessions == null"))
        assertTrue("the no-transport branch marks it too", body.contains("_sessionsLoadFailed.value = true"))
        assertTrue("a landed read cancels the pending retry", body.contains("sessionListRetry?.cancel()"))
        assertTrue("…and restarts its cadence", body.contains("sessionListRetryRound = 0"))
    }

    @Test
    fun theRetryRedrivesTheChannelEveryListSurfaceListensOn() {
        val body = window(vm, "private fun scheduleSessionListRetry()", "private suspend fun awaitProbeSettled()")
        assertTrue("one retry pending at a time", body.contains("if (sessionListRetry?.isActive == true) return"))
        assertTrue("the re-drive is the existing revision channel", body.contains("_sessionsRevision.value += 1"))
        assertTrue(body.contains("delay(sessionListRetryDelayMs(round))"))
    }

    @Test
    fun theListReadRidesTheTransientRetrySeam() {
        val body = window(vm, "private suspend fun fetchSessionRows()", "private suspend fun mergeLocationAsks(")
        assertTrue(
            "one dropped socket used to surface as a permanent empty list",
            body.contains("readTransport { api.sessions(cursor = cursor, directoryScoped = false) }"),
        )
    }

    // ------------------------------------------- 3. Home adopts the answer

    @Test
    fun homeAdoptsTheAnsweredListAndKeepsFirstReadAConfirmedFlag() {
        val home = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeScreen.kt")
        val body = window(home, "viewModel.listSessions { list ->", "viewModel.listProjects(")
        assertTrue("the answered list is adopted", body.contains("sessions = merged"))
        assertTrue(
            "only a confirmed read marks the state as genuinely zero",
            body.contains("if (!viewModel.sessionsLoadFailed.value) firstReadOk = true"),
        )
        assertFalse(
            "the old guard skipped the adoption, which is what latched the empty state",
            body.contains("if (!viewModel.sessionsLoadFailed.value) {\n                sessions = list"),
        )
    }

    @Test
    fun theImportPathUsesTheSameContract() {
        val home = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeScreen.kt")
        val body = window(home, "val onSessionImported: (String) -> Unit = { id ->", "val onAddProject: () -> Unit = {")
        assertTrue(body.contains("sessions = mergeCatalogueRead(sessions, list, renamedTitles)"))
    }
}
