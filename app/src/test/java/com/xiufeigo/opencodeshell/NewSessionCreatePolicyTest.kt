package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.home.NewSessionCreatePolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「Create session」's auto-retry rule: only the measured silent drop
 * (`createSession` returns without a callback while `requireTransport()` is
 * null) is re-issued on its own — a timed-out or errored create may already
 * have a session in flight and must surface instead of double-creating.
 */
class NewSessionCreatePolicyTest {

    @Test
    fun notReadyIsReissuedOnceThenSurfaced() {
        assertTrue(
            NewSessionCreatePolicy.shouldAutoRetry(1, NewSessionCreatePolicy.Failure.NOT_READY),
        )
        assertFalse(
            NewSessionCreatePolicy.shouldAutoRetry(2, NewSessionCreatePolicy.Failure.NOT_READY),
        )
        assertFalse(
            NewSessionCreatePolicy.shouldAutoRetry(3, NewSessionCreatePolicy.Failure.NOT_READY),
        )
    }

    @Test
    fun timedOutIsNeverReissued() {
        // The first call may still complete server-side; retrying would open a
        // second session. The user gets the timeout reason and the Retry button.
        assertFalse(
            NewSessionCreatePolicy.shouldAutoRetry(1, NewSessionCreatePolicy.Failure.TIMED_OUT),
        )
    }

    @Test
    fun erroredIsNeverReissued() {
        // `onError` can fire after `transport.createSession` already returned an
        // id (a later `observeSession` throw) — surface the reason instead.
        assertFalse(
            NewSessionCreatePolicy.shouldAutoRetry(1, NewSessionCreatePolicy.Failure.ERRORED),
        )
    }
}
