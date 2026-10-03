package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.export.ExportAttemptLedger
import com.xiufeigo.opencodeshell.ui.session.export.ExportFeedback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "one attempt, one message" guard of the export overlay
 * ([ExportAttemptLedger]).
 *
 * v9: the mask hung 12–75 s and then closed with nothing said. The funnel pins
 * in [SessionExportFeedbackTest] make `SessionScreen`'s report function the only
 * writer of the overlay flag; this class pins the other half — that the funnel
 * can answer a racing exit (a Cancel tap while the payload read is still out, a
 * late saver outcome) without a second toast, which is what lets every exit
 * report unconditionally instead of guarding itself with `if (busy)`-style
 * flags that used to leak.
 */
class ExportAttemptLedgerTest {

    @Test
    fun theFirstReportWinsAndTheRestAreDropped() {
        val ledger = ExportAttemptLedger()
        ledger.begin()
        assertNull("nothing reported yet", ledger.reported)
        assertFalse(ledger.settled)

        assertTrue("the exit that ends the attempt reports", ledger.settle(ExportFeedback.SAVED))
        assertEquals(ExportFeedback.SAVED, ledger.reported)
        assertTrue(ledger.settled)

        // a late second exit (the payload answer after a Cancel, a late picker
        // outcome) must not become a second toast
        assertFalse(ledger.settle(ExportFeedback.FAILED))
        assertEquals("the first report is the one that stands", ExportFeedback.SAVED, ledger.reported)
    }

    @Test
    fun beginStartsAFreshAttempt() {
        val ledger = ExportAttemptLedger()
        ledger.begin()
        assertTrue(ledger.settle(ExportFeedback.CANCELLED))
        ledger.begin()
        assertFalse("a new attempt may report", ledger.settled)
        assertNull(ledger.reported)
        assertTrue(ledger.settle(ExportFeedback.SAVED_TO_DOWNLOADS))
    }

    @Test
    fun everyOutcomeIsSettledOnce() {
        // The ledger is outcome-agnostic on purpose (it guards the message
        // count, `ExportFeedback` owns the copy): each of the four settled
        // attempts must report exactly once.
        for (outcome in ExportFeedback.values()) {
            val ledger = ExportAttemptLedger()
            ledger.begin()
            assertTrue(outcome.name, ledger.settle(outcome))
            assertFalse(outcome.name, ledger.settle(outcome))
        }
    }
}
