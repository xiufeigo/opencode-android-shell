package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.home.HomeSessions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v84 「backend unreachable: Recent sessions sits on the skeleton forever」.
 *
 * Device evidence (`research/v84_verify/5554/57_home.png` … `66_restart.png`):
 * with the backend down the sessions column shows the skeleton blocks with no
 * empty state, no error and no retry — even 5 minutes and a restart later —
 * while Projects reads fine from the local store. The v83 bound did flip
 * `sessionsLoadFailed` on the first load, but `HomeScreen` OR-ed a pending
 * probe verdict on top of the read's own spinner, and with no transport every
 * catalogue read reports `ProbePending` (a probe is in flight, or no verdict
 * ever landed while a server row is configured) — so `loading` never settled
 * and `shouldShowLoadError` never fired.
 *
 * [HomeSessions.isColumnLoading] is the precedence rule behind the fix: a
 * catalogue read that actually failed is the terminal answer (the error/retry
 * state), and a still-churning probe must not drag it back to the skeleton.
 */
class HomeSessionsFailedProbeLoadingV84Test {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    // ------------------------------------------------- the loading precedence

    @Test
    fun aFailedReadBeatsAPendingProbe() {
        // The measured v84 state: first read failed, probe still churning, no
        // rows — the column must leave the skeleton for the error state.
        assertFalse(
            HomeSessions.isColumnLoading(
                localLoading = false,
                loadFailed = true,
                firstReadOk = false,
                rowCount = 0,
                probePending = true,
            ),
        )
    }

    @Test
    fun aFailedRefreshKeepsACachedList() {
        assertFalse(
            HomeSessions.isColumnLoading(
                localLoading = false,
                loadFailed = true,
                firstReadOk = true,
                rowCount = 3,
                probePending = true,
            ),
        )
    }

    @Test
    fun aPendingProbeStillHoldsTheSkeletonBeforeAnyFailure() {
        // A healthy first launch is unchanged: no failure recorded, so the
        // pending probe keeps the skeleton instead of flashing the empty state.
        assertTrue(
            HomeSessions.isColumnLoading(
                localLoading = false,
                loadFailed = false,
                firstReadOk = false,
                rowCount = 0,
                probePending = true,
            ),
        )
    }

    @Test
    fun aRetryInFlightShowsTheSkeletonAgain() {
        // The read flips the local spinner back on — never the error over a
        // live read — even when the previous round failed.
        assertTrue(
            HomeSessions.isColumnLoading(
                localLoading = true,
                loadFailed = true,
                firstReadOk = false,
                rowCount = 0,
                probePending = true,
            ),
        )
    }

    @Test
    fun aSettledProbeOrAConfirmedReadIsNotLoading() {
        assertFalse(
            HomeSessions.isColumnLoading(
                localLoading = false,
                loadFailed = false,
                firstReadOk = false,
                rowCount = 0,
                probePending = false,
            ),
        )
        assertFalse(
            HomeSessions.isColumnLoading(
                localLoading = false,
                loadFailed = false,
                firstReadOk = true,
                rowCount = 0,
                probePending = true,
            ),
        )
    }

    @Test
    fun theFailedFirstLoadReachesTheErrorState() {
        // End to end of the v84 contract: not loading + failed + no rows is
        // exactly what `shouldShowLoadError` renders the error/retry on.
        val loading = HomeSessions.isColumnLoading(
            localLoading = false,
            loadFailed = true,
            firstReadOk = false,
            rowCount = 0,
            probePending = true,
        )
        assertTrue(HomeSessions.shouldShowLoadError(loading, loadFailed = true, rowCount = 0))
    }

    // ------------------------------------------------- the screen that reads it

    @Test
    fun bothSessionColumnsShareTheOnePrecedenceRule() {
        val home = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeScreen.kt")
        assertEquals(
            "the desktop and the mobile column must not drift apart",
            2,
            Regex("HomeSessions\\.isColumnLoading\\(").findAll(home).count(),
        )
        assertTrue(home.contains("loadFailed = sessionsFailed"))
        assertTrue(home.contains("firstReadOk = firstReadOk"))
        assertTrue(home.contains("rowCount = sessions.size"))
        assertTrue(home.contains("probePending = browseFailure == TransportFailure.ProbePending"))
        assertTrue(home.contains("localLoading = loading"))
    }

    @Test
    fun theFailedReadIsWhatLiftsThePendingProbe() {
        val model = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeSessions.kt")
        val rule = model.substringAfter("fun isColumnLoading(")
        assertTrue(rule.contains("!loadFailed"))
        assertTrue(rule.contains("probePending"))
        assertTrue(rule.contains("!firstReadOk"))
    }
}
