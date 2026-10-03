package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ChatStore.RunStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The turn lifecycle state machine behind the foreground idle watchdog.
 *
 * The bug this pins (research/v54_verify/tB_58, tB_66): the turn's terminal
 * frame never reached the client, `ChatStore.ChatState.runStatus` stayed
 * RUNNING, and the composer kept offering Stop on an idle session — 44 s after
 * the assistant's last message was `completed`, and for good.
 */
class TurnIdleWatchdogTest {

    private var clock = 1_000L
    private var activity = 1_000L

    private fun watchdog(grace: Long = 10_000L) =
        TurnIdleWatchdog(grace, { clock }, { activity })

    @Test
    fun `a live turn is never probed before the grace elapses`() {
        val watchdog = watchdog()
        clock = 1_000L + 9_999L
        assertFalse(watchdog.probeDue(RunStatus.RUNNING))
        assertEquals(TurnIdleAction.NONE, watchdog.onProbe(RunStatus.RUNNING, "idle"))
    }

    @Test
    fun `busy settles back to idle once the server does not list the session`() {
        val watchdog = watchdog()
        clock = 1_000L + 10_000L
        assertTrue(watchdog.probeDue(RunStatus.RUNNING))
        // v54 tB: the server's active set no longer has the session, the frame
        // that would have reset the store is gone — this is the verdict that
        // gives the composer its send button back.
        assertEquals(TurnIdleAction.SETTLE_IDLE, watchdog.onProbe(RunStatus.RUNNING, null))
        assertEquals(TurnIdleAction.SETTLE_IDLE, watchdog.onProbe(RunStatus.RUNNING, ""))
    }

    @Test
    fun `a session the server still lists is re-armed, never settled`() {
        val watchdog = watchdog()
        clock = 1_000L + 30_000L
        // The long foreground tool wait: no part traffic for 30 s, but the
        // server is still running it.
        for (live in listOf("running", "busy", "retry")) {
            assertEquals(TurnIdleAction.REARM, watchdog.onProbe(RunStatus.RUNNING, live))
        }
    }

    @Test
    fun `an errored or already idle turn is left alone`() {
        val watchdog = watchdog()
        clock = 1_000L + 60_000L
        // ERROR already restored the send button (the composer is Stop only
        // while RUNNING); IDLE has nothing to reconcile.
        assertFalse(watchdog.probeDue(RunStatus.ERROR))
        assertFalse(watchdog.probeDue(RunStatus.IDLE))
        assertEquals(TurnIdleAction.NONE, watchdog.onProbe(RunStatus.ERROR, null))
        assertEquals(TurnIdleAction.NONE, watchdog.onProbe(RunStatus.IDLE, "running"))
    }

    @Test
    fun `a turn that starts producing again pushes the probe out`() {
        val watchdog = watchdog()
        clock = 1_000L + 10_000L
        assertTrue(watchdog.probeDue(RunStatus.RUNNING))
        // The verdict is REARM, the caller stamps `activity`, and the next tick
        // finds the turn busy again.
        assertEquals(TurnIdleAction.REARM, watchdog.onProbe(RunStatus.RUNNING, "busy"))
        activity = clock
        clock += 3_000L
        assertFalse(watchdog.probeDue(RunStatus.RUNNING))
        clock = activity + 10_000L
        assertTrue(watchdog.probeDue(RunStatus.RUNNING))
    }

    @Test
    fun `a live set the confirmed Stop refuted settles instead of re-arming`() {
        val watchdog = watchdog()
        clock = 1_000L + 10_000L
        // v76: `GET /api/session/active` kept answering `running` a minute past
        // an `{interrupted:true}` answer, so a re-arm here would keep the
        // composer on Stop for good ([StoppedTurn]).
        assertEquals(
            TurnIdleAction.SETTLE_IDLE,
            watchdog.onProbe(RunStatus.RUNNING, "running", liveSetTrusted = false),
        )
        // …and the trust is the only difference: the same reading re-arms a
        // session nobody has stopped.
        assertEquals(
            TurnIdleAction.REARM,
            watchdog.onProbe(RunStatus.RUNNING, "running", liveSetTrusted = true),
        )
    }

    @Test
    fun `quietFor is measured on the client clock from the last part frame`() {
        val watchdog = watchdog()
        clock = 1_000L + 7_500L
        assertEquals(7_500L, watchdog.quietFor())
        // A never-touched stamp (a turn the client opened mid-flight) is not
        // negative and does not throw.
        activity = 0L
        assertEquals(clock, watchdog.quietFor())
    }
}
