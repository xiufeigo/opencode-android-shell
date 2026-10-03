package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.notify.TurnRunStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B-2: the notification trigger's synchronous mirror of the tracked session's
 * run status — see [TurnRunStatus] for why the trigger may not read
 * `ChatStore.ChatState.runStatus`.
 *
 * The semantics that matter: a session switch forgets the old status (the next
 * turn's terminal frame must not look like the end of a turn this client never
 * watched start), a frame that names another session is not this session's run,
 * and "settled" is sticky until something says running again.
 */
class TurnRunStatusTest {

    @Test
    fun aRunIsRunningUntilItSettles() {
        val status = TurnRunStatus()
        status.watch("ses_1")
        assertFalse("nothing has been reported yet", status.isRunning("ses_1"))

        status.running("ses_1")
        assertTrue(status.isRunning("ses_1"))

        status.settled("ses_1")
        assertFalse(status.isRunning("ses_1"))

        // The next turn on the same session starts the cycle again.
        status.running("ses_1")
        assertTrue(status.isRunning("ses_1"))
    }

    @Test
    fun aSessionSwitchForgetsThePreviousStatus() {
        val status = TurnRunStatus()
        status.watch("ses_1")
        status.running("ses_1")

        status.watch("ses_2")
        assertFalse("the previous session's run is not this one's", status.isRunning("ses_2"))
        assertFalse(status.isRunning("ses_1"))
        // …and the old session's frames cannot resurrect it either.
        status.settled("ses_1")
        status.running("ses_1")
        assertFalse(status.isRunning("ses_2"))
    }

    @Test
    fun aForeignSessionIsNotTheTrackedOne() {
        val status = TurnRunStatus()
        status.watch("ses_1")
        status.running("ses_2")
        assertFalse(status.isRunning("ses_2"))
        assertFalse(status.isRunning("ses_1"))

        status.running("ses_1")
        status.settled("ses_2")
        assertTrue("a foreign settle must not clear it", status.isRunning("ses_1"))
    }

    @Test
    fun aSessionlessFrameBelongsToTheTrackedSession() {
        // `ChatStore.tracksSession` treats a missing id as "this session", and
        // `notifyFor` does the same (`foreign` requires a non-null route).
        val status = TurnRunStatus()
        status.watch("ses_1")
        status.running(null)
        assertTrue(status.isRunning("ses_1"))
        assertTrue(status.isRunning(null))
        status.settled(null)
        assertFalse(status.isRunning("ses_1"))
    }

    @Test
    fun beforeAnySessionIsTrackedNothingIsRunning() {
        val status = TurnRunStatus()
        status.running("ses_1")
        assertFalse(status.isRunning("ses_1"))
        status.watch(null)
        assertFalse(status.isRunning(null))
    }

    // --------------------------------------------- a run is not a turn

    /**
     * v50 tD: 「移动到…」 moves a turn between sessions and the server frames the
     * update as `busy` → `idle` — indistinguishable from a turn by status alone.
     * Only a run that produced output ([TurnRunStatus.step]) may answer
     * [TurnRunStatus.ranTurn], which is what the 「回复已完成」 raise asks.
     */
    @Test
    fun aRunWithoutAStepIsNotATurn() {
        val status = TurnRunStatus()
        status.watch("ses_1")
        status.running("ses_1")
        assertTrue(status.isRunning("ses_1"))
        assertFalse("a session update is not a turn", status.ranTurn("ses_1"))

        // One part frame turns the same run into the turn it looks like.
        status.step("ses_1")
        assertTrue(status.ranTurn("ses_1"))

        status.settled("ses_1")
        assertFalse(status.ranTurn("ses_1"))
    }

    @Test
    fun aStepOfAnotherSessionIsNotThisTurn() {
        val status = TurnRunStatus()
        status.watch("ses_1")
        status.running("ses_1")
        status.step("ses_2")
        assertFalse(status.ranTurn("ses_1"))
        // A part frame that arrives while the session is not running is not a
        // turn either — it cannot be, there was none to belong to.
        status.settled("ses_1")
        status.step("ses_1")
        assertFalse(status.ranTurn("ses_1"))
    }

    @Test
    fun theNextRunStartsWithoutThePreviousTurnsStep() {
        val status = TurnRunStatus()
        status.watch("ses_1")
        status.running("ses_1")
        status.step("ses_1")
        status.settled("ses_1")

        // A busy → idle pair for a Move between two real turns stays out.
        status.running("ses_1")
        assertFalse(status.ranTurn("ses_1"))
    }

    // --------------------------------------------- the raise's own clock

    /**
     * [TurnRunStatus.since] is what the raise dates itself from, and the reason
     * this class carries timestamps at all: on Android the frame that ends a turn
     * is read after the user may already be back, so "was the user looking?"
     * cannot be asked of the current foreground state (see
     * `SystemNotificationPolicy.watchedThroughout`).
     */
    private class Clock(var now: Long = 1_000L)

    @Test
    fun aTurnIsDatedFromWhenItWasFirstSeenRunning() {
        val clock = Clock()
        val status = TurnRunStatus(now = { clock.now })
        clock.now = 1_100L
        status.watch("ses_1")
        assertEquals("no turn seen yet: the session's own start", 1_100L, status.since("ses_1"))

        clock.now = 1_200L
        status.running("ses_1")
        assertEquals(1_200L, status.since("ses_1"))

        // Later frames of the same turn are liveness, not a new start: a
        // `step.streamed` in the last second must not make a user who left early
        // look attentive.
        clock.now = 9_000L
        status.running("ses_1")
        assertEquals(1_200L, status.since("ses_1"))

        // The turn settles; the next one dates itself.
        status.settled("ses_1")
        clock.now = 20_000L
        status.running("ses_1")
        assertEquals(20_000L, status.since("ses_1"))
    }

    @Test
    fun aSettledTurnKeepsItsDateForTheLateRaise() {
        val clock = Clock()
        val status = TurnRunStatus(now = { clock.now })
        clock.now = 1_100L
        status.watch("ses_1")
        clock.now = 1_200L
        status.running("ses_1")
        clock.now = 30_000L
        status.settled("ses_1")
        // The terminal frame is read late: the raise still needs the turn's own
        // start, so settling must not clear it.
        assertEquals(1_200L, status.since("ses_1"))
    }

    @Test
    fun onlyTheTrackedSessionHasADate() {
        val clock = Clock()
        val status = TurnRunStatus(now = { clock.now })
        clock.now = 1_100L
        status.watch("ses_1")
        assertNull("a foreign session has no local history to date from", status.since("ses_2"))
        // A session-less frame belongs to the tracked one, like `isRunning`.
        assertEquals(1_100L, status.since(null))

        clock.now = 5_000L
        status.watch("ses_2")
        assertEquals(5_000L, status.since("ses_2"))
        assertNull(status.since("ses_1"))
    }
}
