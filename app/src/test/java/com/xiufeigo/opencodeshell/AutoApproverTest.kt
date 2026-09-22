package com.xiufeigo.opencodeshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Auto-approve core (`AutoApprover`, upstream
 * `_upstream/packages/app/src/session/requests/auto-approve.ts`):
 * setting-gated immediate replies with a bounded per-request retry budget
 * (upstream `retryLimit = 2` ⇒ 3 total attempts).
 */
class AutoApproverTest {

    @Test
    fun settingOffNeverTriggers() {
        var on = false
        val approver = AutoApprover(enabled = { on })
        assertFalse(approver.tryMark("per_1"))
        assertEquals(0, approver.attemptsOf("per_1"))

        // Turning the setting on later lets the next sweep/event reply.
        on = true
        assertTrue(approver.tryMark("per_1"))
        assertEquals(1, approver.attemptsOf("per_1"))
    }

    @Test
    fun triggerFiresOncePerAttemptWithinBudget() {
        val approver = AutoApprover(enabled = { true })
        assertTrue(approver.tryMark("per_1"))   // initial reply
        assertTrue(approver.tryMark("per_1"))   // retry 1
        assertTrue(approver.tryMark("per_1"))   // retry 2 (upstream retryLimit)
        assertFalse(approver.tryMark("per_1"))  // budget exhausted: no loop
        assertFalse(approver.tryMark("per_1"))
        assertEquals(AutoApprover.MAX_ATTEMPTS, approver.attemptsOf("per_1"))
    }

    @Test
    fun budgetsArePerRequest() {
        val approver = AutoApprover(enabled = { true })
        repeat(AutoApprover.MAX_ATTEMPTS) { assertTrue(approver.tryMark("per_1")) }
        assertFalse(approver.tryMark("per_1"))
        // Another request still has its full budget.
        assertTrue(approver.tryMark("per_2"))
        assertEquals(1, approver.attemptsOf("per_2"))
    }

    @Test
    fun forgetResetsTheBudget() {
        val approver = AutoApprover(enabled = { true })
        repeat(AutoApprover.MAX_ATTEMPTS) { approver.tryMark("per_1") }
        assertFalse(approver.tryMark("per_1"))
        // The request left the pending list (replied/rejected/merged away); a
        // recycled id starts fresh.
        approver.forget("per_1")
        assertEquals(0, approver.attemptsOf("per_1"))
        assertTrue(approver.tryMark("per_1"))
    }

    @Test
    fun emptyIdNeverTriggers() {
        val approver = AutoApprover(enabled = { true })
        assertFalse(approver.tryMark(""))
        assertEquals(0, approver.attemptsOf(""))
    }

    @Test
    fun customCapIsHonored() {
        val approver = AutoApprover(enabled = { true }, maxAttempts = 1)
        assertTrue(approver.tryMark("per_1"))
        assertFalse(approver.tryMark("per_1"))
    }
}
