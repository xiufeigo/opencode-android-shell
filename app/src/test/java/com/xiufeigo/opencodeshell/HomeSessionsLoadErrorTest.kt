package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.home.HomeSessions
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sessions column's load-error state (emulator-5554 v74): after a
 * force-stop + relaunch the list sat on its skeleton forever — a failed read
 * left `loading` true with no terminal state and no retry.
 *
 * [HomeSessions.shouldShowLoadError] is the precedence rule behind it: real
 * loading always wins (a retry in flight shows the skeleton again), the error
 * needs a *settled* failed read, and only with no rows to show — a cached list
 * keeps rendering under a failed refresh.
 */
class HomeSessionsLoadErrorTest {

    @Test
    fun loadingAlwaysWinsOverTheError() {
        assertFalse(HomeSessions.shouldShowLoadError(loading = true, loadFailed = true, rowCount = 0))
    }

    @Test
    fun settledFailureWithNoRowsShowsTheError() {
        assertTrue(HomeSessions.shouldShowLoadError(loading = false, loadFailed = true, rowCount = 0))
    }

    @Test
    fun settledFailureWithCachedRowsKeepsTheList() {
        assertFalse(HomeSessions.shouldShowLoadError(loading = false, loadFailed = true, rowCount = 3))
    }

    @Test
    fun settledSuccessWithNoRowsIsTheEmptyState() {
        assertFalse(HomeSessions.shouldShowLoadError(loading = false, loadFailed = false, rowCount = 0))
    }
}
