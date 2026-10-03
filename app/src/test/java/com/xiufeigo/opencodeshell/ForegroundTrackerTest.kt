package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.notify.ForegroundTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The foreground signal `SystemNotifier` feeds to
 * `SystemNotificationPolicy.shouldDeliver`, pinned on the JVM — including the
 * callback **order** a real device produces, which is the whole reason the
 * tracker exists.
 *
 * The measured v31 break (`research/v31_verify/tA`, `logcat -s OcNotify`): the
 * user pressed HOME, the SSE idle frame was read 40 ms later and the raise was
 * skipped as "the app is foreground", because the started/stopped counter still
 * read `1` — `onActivityStopped` only arrived **1.1 s** after the decision. The
 * first test below is that timeline, with the delay spelled out.
 */
class ForegroundTrackerTest {

    /** The clock the tracker stamps [ForegroundTracker.foregroundSince] from. */
    private var clock = 1_000L

    private fun tracker() = ForegroundTracker(now = { clock })

    /** The shell is a single-activity app; the key is only an identity. */
    private val activity = Any()

    @Test
    fun homeIsBackgroundedByThePauseCallbackNotTheLateStop() {
        val foreground = tracker()
        foreground.resumed(activity)
        assertTrue(foreground.inForeground)
        assertEquals(1_000L, foreground.foregroundSince)

        // HOME at 1 000 (the pause callback is part of handling the press), the
        // idle frame is read 40 ms later: the app must already read backgrounded.
        clock = 1_040L
        assertTrue(foreground.paused(activity))
        assertFalse(foreground.inForeground)
        assertNull(foreground.foregroundSince)

        // …and the stop callback, 1.1 s after the decision, only confirms it.
        clock = 2_100L
        assertFalse("the activity was already paused", foreground.stopped(activity))
        assertFalse(foreground.inForeground)
        assertNull(foreground.foregroundSince)
    }

    @Test
    fun theSeedIsTheOwnersOwnEntryAndOnlyItsOwnCallbacksRemoveIt() {
        val foreground = tracker()
        // A composition that runs after `onActivityResumed` seeds the owner (the
        // activity) itself: the pause/stop that later arrives carries the same
        // identity and takes it back.
        foreground.resumed(activity)
        assertTrue(foreground.inForeground)

        // A callback for *another* activity cannot remove the seed — which is
        // what a bare counter gets wrong, and why the state is a set of keys.
        assertFalse(foreground.paused(Any()))
        assertTrue(foreground.inForeground)
        assertEquals(1_000L, foreground.foregroundSince)

        assertTrue(foreground.paused(activity))
        assertFalse(foreground.inForeground)
    }

    @Test
    fun theTimestampMovesOnTheTransitionOnly() {
        val foreground = tracker()
        foreground.resumed(activity)
        assertEquals(1_000L, foreground.foregroundSince)

        // A second activity on top of the first does not re-date a foreground
        // period that never ended: the user was watching throughout.
        clock = 5_000L
        val other = Any()
        foreground.resumed(other)
        assertEquals(1_000L, foreground.foregroundSince)

        // The top activity leaving does not either — the app is still on screen.
        foreground.paused(other)
        assertTrue(foreground.inForeground)
        assertEquals(1_000L, foreground.foregroundSince)

        // The last one leaving ends the period; the next resume starts a new one.
        foreground.paused(activity)
        assertNull(foreground.foregroundSince)
        clock = 9_000L
        foreground.resumed(activity)
        assertEquals(9_000L, foreground.foregroundSince)
    }

    @Test
    fun aStaleStopForAnAlreadyPausedActivityCannotDecrementTheRest() {
        val foreground = tracker()
        val second = Any()
        foreground.resumed(activity)
        foreground.resumed(second)
        assertEquals(2, foreground.resumedCount)

        // The first activity paused, the second resumed on top; its late stop
        // arrives afterwards. A counter would now read 1 for the wrong activity —
        // or 0, and post a notification the user can see.
        foreground.paused(activity)
        assertFalse(foreground.stopped(activity))
        assertTrue(foreground.inForeground)
        assertEquals(1, foreground.resumedCount)
    }

    @Test
    fun aDoubleResumeDoesNotLeakACount() {
        val foreground = tracker()
        foreground.resumed(activity)
        foreground.resumed(activity)
        assertEquals(1, foreground.resumedCount)
        // One pause is enough to leave: the set cannot hold the same key twice.
        assertTrue(foreground.paused(activity))
        assertFalse(foreground.inForeground)
    }

    /**
     * v68 5554 (`research/v68_verify/5554/80_notif_wait.png`): the Settings
     * window covers the app while multi-resume keeps the activity resumed. The
     * content is invisible, but only once the focus loss outlasts the grace —
     * a shade peek or a glance that taps back stays "visible".
     */
    @Test
    fun aCoveredWindowReadsAwayOnlyPastTheFocusGrace() {
        val foreground = tracker()
        foreground.resumed(activity)
        assertTrue(foreground.contentVisible)

        // The covering window takes the focus at 2 000; inside the grace the
        // content still counts as visible.
        clock = 2_000L
        foreground.focusLost()
        assertTrue(foreground.inForeground)
        assertTrue(foreground.contentVisible)
        clock = 2_000L + ForegroundTracker.FOCUS_LOST_GRACE_MS - 1
        assertTrue(foreground.contentVisible)
        assertFalse("still inside the grace", foreground.focusTimedOut())

        // Past the grace the viewing period ends, once.
        clock = 2_000L + ForegroundTracker.FOCUS_LOST_GRACE_MS
        assertFalse(foreground.contentVisible)
        assertTrue(foreground.focusTimedOut())
        assertNull(foreground.foregroundSince)
        assertFalse("applied once", foreground.focusTimedOut())
        assertFalse(foreground.contentVisible)
    }

    @Test
    fun aFlappingFocusInsideTheGraceNeverEndsTheViewingPeriod() {
        val foreground = tracker()
        foreground.resumed(activity)
        clock = 2_000L
        foreground.focusLost()
        // Back inside the grace: the loss is forgotten, the period intact.
        clock = 3_000L
        foreground.focusGained()
        assertTrue(foreground.contentVisible)
        assertEquals(1_000L, foreground.foregroundSince)
        assertFalse(foreground.focusTimedOut())
        // A repeated loss re-arms from its own instant — it cannot inherit the
        // earlier stamp to stretch the grace.
        clock = 4_000L
        foreground.focusLost()
        foreground.focusLost()
        clock = 4_000L + ForegroundTracker.FOCUS_LOST_GRACE_MS - 1
        assertTrue(foreground.contentVisible)
    }

    @Test
    fun regainingFocusAfterTheTimeoutStartsANewViewingPeriod() {
        val foreground = tracker()
        foreground.resumed(activity)
        clock = 2_000L
        foreground.focusLost()
        clock = 2_000L + ForegroundTracker.FOCUS_LOST_GRACE_MS
        assertTrue(foreground.focusTimedOut())
        // The user taps back at 60 000: visible again, dated from the return —
        // a turn that finished under the cover is not "watched".
        clock = 60_000L
        foreground.focusGained()
        assertTrue(foreground.contentVisible)
        assertEquals(60_000L, foreground.foregroundSince)
    }

    @Test
    fun resumeWhileUnfocusedWaitsForFocusToDateThePeriod() {
        val foreground = tracker()
        foreground.resumed(activity)
        clock = 2_000L
        foreground.focusLost()
        // HOME on top of the cover, then back — but the window focus has not
        // returned yet, so nobody is watching and nothing is dated.
        clock = 3_000L
        foreground.paused(activity)
        assertNull(foreground.foregroundSince)
        clock = 9_000L
        foreground.resumed(activity)
        assertTrue(foreground.inForeground)
        assertFalse("focus is still away", foreground.contentVisible)
        assertNull(foreground.foregroundSince)
        // The stamp lands with the focus, not with the resume.
        clock = 9_100L
        foreground.focusGained()
        assertTrue(foreground.contentVisible)
        assertEquals(9_100L, foreground.foregroundSince)
    }
}
