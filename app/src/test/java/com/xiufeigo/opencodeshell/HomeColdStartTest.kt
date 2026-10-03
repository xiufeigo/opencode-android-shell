package com.xiufeigo.opencodeshell

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P1 cold-start race (emulator-5564, 2026-09-24): kill app → restart → Home
 * briefly showed "Nothing here yet", and a failed first pull could latch that
 * empty state until a force-stop. The screen rendered its initial cached empty
 * list as "genuinely no sessions" before the first pull had settled.
 *
 * Compose needs an Android host, so these contracts are pinned against the
 * `HomeScreen` source — same technique as [ProbeCredentialRaceTest]. Each test
 * names the behavior it forbids.
 */
class HomeColdStartTest {

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

    private val home by lazy {
        source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeScreen.kt")
    }

    @Test
    fun failedReadsNeverClobberTheList() {
        val body = window(home, "viewModel.listSessions { list ->", "viewModel.listProjects(")
        assertTrue(
            "only a confirmed read may mark the state as genuinely zero — a failed " +
                "one answers the last good rows and must not latch the empty state",
            body.contains("if (!viewModel.sessionsLoadFailed.value)"),
        )
        assertTrue(body.contains("sessions = merged"))
    }

    @Test
    fun emptyStateWaitsForAConfirmedRead() {
        assertTrue("first success must be tracked", home.contains("firstReadOk"))
        assertTrue(
            "a pending probe is loading, not empty — the skeleton holds until the verdict lands",
            home.contains("browseFailure == TransportFailure.ProbePending"),
        )
    }

    @Test
    fun previewHostsNeverReadAPendingProbe() {
        assertTrue(home.contains("NO_FAILURE_FLOW = MutableStateFlow<TransportFailure?>(null)"))
    }

    @Test
    fun effectKeysStayOnTheTwoRevisions() {
        assertTrue(
            "revision + configMissing flows re-drive through the collector, not new effect keys",
            home.contains("LaunchedEffect(chatViewModel, projectsRevision, sessionsRevision)"),
        )
    }
}
