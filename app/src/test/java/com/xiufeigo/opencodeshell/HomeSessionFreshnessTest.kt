package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.home.HOME_SESSIONS_FRESHNESS_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * BUG-1 (v36 tB): after `adb reverse` came back, Home's session list never
 * refreshed — a session created during the outage was still missing minutes
 * later, a pull-down did nothing, and only leaving and re-entering Home showed
 * it (`research/v36_verify/tB/v36tb_2c_after_restore.png` →
 * `v36tb_2f_home_final.png`).
 *
 * The measured shape is the gap this pins: **no read was in flight while Home
 * sat idle** — Home opens no stream (M1 contract), so no frame arrived, nothing
 * failed, and `scheduleSessionListRetry` (armed only by a failed read) was
 * never started. Every re-read Home had was event- or failure-driven, so a
 * connection that dropped and returned *silently* produced no read at all.
 *
 * Two halves, both asserted here:
 *
 * - **a freshness tick** while Home is composed (`HomeScreen`'s 45 s loop), so
 *   a recovery is picked up within the ≤1 min the report asks for instead of at
 *   the next route change;
 * - **the retry chain reads by itself** (`ChatViewModel.scheduleSessionListRetry`),
 *   instead of only bumping `sessionsRevision` and hoping a mounted surface
 *   calls `listSessions` — and only a read that actually **landed** ends it,
 *   never the cached rows a failed read answers.
 *
 * The ViewModel needs an Android `Context`, so the wiring is pinned against the
 * source function-window (the technique [HomeSessionsReconnectTest] uses for the
 * same screen); the cadence constants are pure and asserted for real.
 */
class HomeSessionFreshnessTest {

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

    private val vm by lazy { source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt") }
    private val home by lazy { source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeScreen.kt") }

    // ------------------------------------------------ 1. the freshness cadence

    @Test
    fun theFreshnessTickLandsInsideTheOneMinuteTheReportAsksFor() {
        assertTrue("a tick must exist", HOME_SESSIONS_FRESHNESS_MS > 0)
        assertTrue(
            "recovery must be noticed within a minute",
            HOME_SESSIONS_FRESHNESS_MS <= 60_000L,
        )
        assertTrue(
            "and it must not be a spin",
            HOME_SESSIONS_FRESHNESS_MS >= 10_000L,
        )
    }

    @Test
    fun homeReReadsTheListOnItsOwnClock() {
        val tick = window(home, "LaunchedEffect(chatViewModel, trackedTitle) {", "// The 项目 column:")
        assertTrue("the loop waits first (no double read on entry)", tick.contains("delay(HOME_SESSIONS_FRESHNESS_MS)"))
        assertTrue("…and then asks for the list", tick.contains("viewModel.listSessions { list ->"))
        assertTrue("the answered list is adopted", tick.contains("sessions = tickMerged"))
        assertTrue(
            "a failed read must not mark the state as a confirmed zero",
            tick.contains("if (!viewModel.sessionsLoadFailed.value) firstReadOk = true"),
        )
    }

    @Test
    fun theTickIsNotKeyedOnTheRevisionsSoABumpDoesNotRestartItsClock() {
        // A revision already re-reads through the revision-keyed effect; folding
        // the tick into that effect would restart the timer on every event and
        // could postpone the silent check indefinitely.
        assertTrue(
            "the entry effect keeps its three keys",
            home.contains("LaunchedEffect(chatViewModel, projectsRevision, sessionsRevision)"),
        )
        val entry = window(
            home,
            "LaunchedEffect(chatViewModel, projectsRevision, sessionsRevision) {",
            "serverNames = withContext(Dispatchers.IO) { projectApi.loadNames() }",
        )
        assertFalse(
            "the freshness delay belongs to the tick alone",
            entry.contains("HOME_SESSIONS_FRESHNESS_MS"),
        )
        assertTrue(
            // v54 P1: the tracked session's title joins the key — the server
            // renames a session off its first message while another tab of this
            // shell tracks it, and Home printed the stale 「Untitled」 for a whole
            // 45 s tick after the tab strip above had already moved. The
            // revisions stay out, or a bump would restart the clock.
            "and the tick is its own effect, keyed on the client and the tracked title",
            home.contains("LaunchedEffect(chatViewModel, trackedTitle) {"),
        )
        assertFalse(
            "the revisions must not restart the tick",
            home.contains("LaunchedEffect(chatViewModel, trackedTitle, sessionsRevision)"),
        )
    }

    // ------------------------------------ 2. the retry chain reads for itself

    @Test
    fun theRetryChainPerformsTheReadInsteadOfOnlyPingingTheChannel() {
        val body = window(vm, "private fun scheduleSessionListRetry()", "private suspend fun awaitProbeSettled()")
        assertTrue(
            "a bump alone is a no-op with no list surface mounted",
            body.contains("val rows = fetchSessionRows()"),
        )
        assertTrue(
            "a landed read publishes the fresh rows for every surface",
            body.contains("lastSessionRows = fresh"),
        )
        assertTrue(
            "…and re-drives the channel mounted surfaces listen on",
            body.contains("_sessionsRevision.value += 1"),
        )
        assertTrue("the failure report is cleared by the landed read", body.contains("_sessionsLoadFailed.value = false"))
        assertTrue("…and so is the browse failure", body.contains("_browseFailure.value = null"))
        assertTrue("the cadence restarts on the next outage", body.contains("sessionListRetryRound = 0"))
        assertTrue("one round pending at a time", body.contains("if (sessionListRetry?.isActive == true) return"))
        assertTrue("the backoff is still the tested one", body.contains("delay(sessionListRetryDelayMs(round))"))
    }

    @Test
    fun theChainIsEndedOnlyByAReadThatLanded() {
        val body = window(vm, "private fun scheduleSessionListRetry()", "private suspend fun awaitProbeSettled()")
        assertFalse(
            "the cached answer is not a landed read — it must never end the chain",
            body.contains("onResult(lastSessionRows)"),
        )
        assertTrue(
            "an unreachable server keeps the chain alive",
            body.contains("sessionListRetry = null\n            scheduleSessionListRetry()"),
        )
        assertTrue(
            "the round re-probes when it has no transport at all",
            body.contains("if (v2Api() == null) maybeReprobe()"),
        )
        assertEquals(
            "the landed read is what publishes the rows",
            1,
            body count "lastSessionRows = fresh",
        )
    }

    @Test
    fun aFailedReadStillAnswersTheCacheAndSchedulesTheChain() {
        // The half HomeSessionsReconnectTest pins must survive this change: the
        // cached answer is what keeps Home's column from wiping, and the chain
        // is what turns it fresh again.
        val body = window(vm, "fun listSessions(onResult", "private fun scheduleSessionListRetry()")
        assertEquals(2, body count "onResult(lastSessionRows)")
        assertEquals(2, body count "scheduleSessionListRetry()")
        assertTrue(body.contains("lastSessionRows = rows"))
        assertTrue(body.contains("sessionListRetry?.cancel()"))
    }
}
