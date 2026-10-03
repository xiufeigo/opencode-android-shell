package com.xiufeigo.opencodeshell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The confirmed-Stop record ([StoppedTurn]) — v76's residual Stop button: the
 * server answered `{interrupted:true}`, the transcript showed the `Interrupted`
 * divider, and the composer stayed on Stop because the server's live set kept
 * listing the session and the client's snapshot reconcile re-asserted RUNNING
 * from it (`research/v76_verify/5556/37_third_sent.png`, `38_sent.png`).
 */
class StoppedTurnTest {

    private fun stopped(): StoppedTurn = StoppedTurn().apply { watch("ses_1") }

    @Test
    fun `a fresh tracker asserts whatever the live set says`() {
        // A screen opened mid-turn: the map is the only source there is.
        assertTrue(stopped().allowsLiveAssert("ses_1"))
    }

    @Test
    fun `a confirmed Stop refuses the live set`() {
        val turn = stopped()
        turn.confirmed("ses_1")
        assertFalse(turn.allowsLiveAssert("ses_1"))
    }

    @Test
    fun `a refused Stop is not a confirmed one`() {
        // `reportStopUndelivered` never reaches `confirmed`: the turn may still
        // be running, so the live set keeps its vote.
        assertTrue(stopped().allowsLiveAssert("ses_1"))
    }

    @Test
    fun `a new run lifts the record`() {
        val turn = stopped()
        turn.confirmed("ses_1")
        turn.newRun("ses_1")
        assertTrue(turn.allowsLiveAssert("ses_1"))
    }

    @Test
    fun `another session's run does not lift this session's record`() {
        val turn = stopped()
        turn.confirmed("ses_1")
        turn.newRun("ses_2")
        assertFalse(turn.allowsLiveAssert("ses_1"))
    }

    @Test
    fun `opening another session forgets the Stop`() {
        val turn = stopped()
        turn.confirmed("ses_1")
        turn.watch("ses_2")
        assertTrue(turn.allowsLiveAssert("ses_2"))
        assertTrue(turn.allowsLiveAssert(null))
    }

    @Test
    fun `a frame with no session id is the tracked session's`() {
        val turn = stopped()
        turn.confirmed(null)
        assertFalse(turn.allowsLiveAssert(null))
        assertFalse(turn.allowsLiveAssert("ses_1"))
        turn.newRun(null)
        assertTrue(turn.allowsLiveAssert("ses_1"))
    }

    @Test
    fun `a session nobody watched is never suppressed`() {
        val turn = stopped()
        turn.confirmed("ses_1")
        assertTrue(turn.allowsLiveAssert("ses_2"))
    }

    @Test
    fun `only a run starting lifts the record - liveness does not`() {
        // The seven frame types the v2 adapter maps onto RUNNING
        // (`V2EventAdapter.adaptFrame`); a step of the stopped turn finishing
        // late is not a new turn, and lifting the record on it would hand the
        // turn straight back to the stale live set.
        assertFalse(StoppedTurn.startsRun("session.status"))
        assertFalse(StoppedTurn.startsRun("session.step.streamed"))
        assertFalse(StoppedTurn.startsRun("session.step.ended"))
        assertFalse(StoppedTurn.startsRun("session.execution.interrupted"))
        assertTrue(StoppedTurn.startsRun("session.execution.started"))
        assertTrue(StoppedTurn.startsRun("session.step.started"))
    }

    @Test
    fun `the interrupted settle leaves the record standing`() {
        val turn = stopped()
        turn.confirmed("ses_1")
        // The frames the abort itself drags in: a late `step.streamed` and the
        // `step.ended` of the step that died, then the transcript's divider.
        for (type in listOf("session.step.streamed", "session.step.ended", "session.status")) {
            if (StoppedTurn.startsRun(type)) turn.newRun("ses_1")
        }
        assertFalse(turn.allowsLiveAssert("ses_1"))
    }
}
