package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.notify.AppNotification
import com.xiufeigo.opencodeshell.notify.AppNotificationKind
import com.xiufeigo.opencodeshell.notify.AppNotifier
import com.xiufeigo.opencodeshell.notify.ForegroundTracker
import com.xiufeigo.opencodeshell.notify.NotificationDelivery
import com.xiufeigo.opencodeshell.notify.SystemNotificationPolicy
import com.xiufeigo.opencodeshell.notify.TurnRunStatus
import com.xiufeigo.opencodeshell.v2.V2EventAdapter
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v68 5554 (`research/v68_verify/5554/75_stop_t5.png` … `89_shade_fg2.png`,
 * `Medium_Desktop` freeform): a long turn finished while the app sat covered by
 * the Settings window — 144 s + 140 s, `dumpsys notification` 0 — yet the
 * foreground Stop of the earlier turn had shown its 「Response ready」 card.
 *
 * Both halves are the same funnel (`ChatViewModel.notifyFor`'s IDLE branch →
 * `raise` → `AppNotifier.raise` → `NotificationDelivery.deliver` →
 * `SystemNotificationPolicy.shouldDeliver`): a user Stop and a normal finish
 * both arrive as `session.execution.*` → `RunStatusChanged.IDLE`, so there is
 * no "interrupt path" that could bypass the gate — the card in 75 is the
 * **in-app banner** (`NotificationBanner`, the only place with Dismiss actions;
 * `SystemNotifier` posts none), which is exactly what the foreground semantic
 * wants. What differed was the visibility state at raise time: multi-resume
 * keeps the covered activity resumed, so the resumed-set gate read foreground
 * for a user looking at another window, `viewed` computed `true` off a
 * `foregroundSince` that never cleared, and the completion was skipped,
 * re-checked into a drop, and withheld for a pause that never came.
 *
 * The harness below drives that timeline over the real [ForegroundTracker],
 * [TurnRunStatus], [AppNotifier] and policy functions: the covered completion
 * is told (via the focus-timeout carrier), the foreground interruption is not
 * (the banner owns it), and both terminal frames raise identically.
 */
class NotifyCoveredWindowV68Test {

    private val tracked = "ses_1"

    private val startFrame =
        """{"id":"evt_start","created":1790053402296,"type":"session.execution.started",""" +
            """"data":{"sessionID":"ses_1"},"durable":{"aggregateID":"ses_1","seq":3,"version":1}}"""

    private val stepFrame =
        """{"id":"evt_step","created":1790053403000,"type":"session.step.started",""" +
            """"data":{"sessionID":"ses_1","assistantMessageID":"msg_asst_1","started":1790053403000},""" +
            """"durable":{"aggregateID":"ses_1","seq":4,"version":1}}"""

    private val doneFrame =
        """{"id":"evt_done","created":1790053436643,"type":"session.execution.succeeded",""" +
            """"data":{"sessionID":"ses_1"},"durable":{"aggregateID":"ses_1","seq":4,"version":1}}"""

    private val interruptedFrame =
        """{"id":"evt_int","created":1790053436643,"type":"session.execution.interrupted",""" +
            """"data":{"sessionID":"ses_1","reason":"user"},"durable":{"aggregateID":"ses_1","seq":5,"version":1}}"""

    /**
     * `ChatViewModel`'s trigger + raise, reduced to the notify chain: the
     * `notifyFor` IDLE branch over the real adapter and wire mirror, `raise`'s
     * `viewed` over the real focus history, the real [AppNotifier] feed, and
     * the delivery gate fed by [ForegroundTracker.contentVisible] — the exact
     * input `SystemNotifier.deliver` passes to `shouldDeliver`.
     */
    private class Harness(val tracked: String) {
        var now = 1_000L

        val wire = TurnRunStatus(now = { now }).also { it.watch(tracked) }
        val foreground = ForegroundTracker(now = { now })

        /** Every accepted raise is offered here — the funnel has no other mouth. */
        val offered = mutableListOf<AppNotification>()
        private val notifier = AppNotifier(
            enabled = { true },
            delivery = NotificationDelivery { offered += it },
        )

        var visibleSession: String? = tracked
        private val activity = Any()

        fun resumed() = foreground.resumed(activity)

        fun read(vararg frames: String) {
            for (json in frames) {
                val envelope = JSONObject(json)
                val routed = V2EventAdapter.sessionID(envelope)
                val events = V2EventAdapter.adapt(envelope.optString("type"), envelope)
                for (event in events) {
                    if (event !is ChatStore.Event.RunStatusChanged) continue
                    when (event.status) {
                        ChatStore.RunStatus.RUNNING -> wire.running(routed)

                        ChatStore.RunStatus.IDLE -> {
                            val foreign = routed != null && routed != tracked
                            val finished = !foreign && wire.ranTurn(routed)
                            val since = wire.since(routed)
                            wire.settled(routed)
                            if (foreign || finished) raise(AppNotificationKind.AGENT, routed, since)
                        }

                        ChatStore.RunStatus.ERROR -> Unit
                    }
                }
                if (events.any { it is ChatStore.Event.PartUpdated }) wire.step(routed)
            }
        }

        /** `ChatViewModel.raise`: the route crossed with the focus history. */
        private fun raise(kind: AppNotificationKind, sessionID: String?, since: Long?) {
            val watched = since == null ||
                SystemNotificationPolicy.watchedThroughout(foreground.foregroundSince, since)
            val viewed = sessionID != null && sessionID == visibleSession && watched
            notifier.raise(
                kind = kind,
                sessionID = sessionID,
                sessionTitle = sessionID?.takeIf { it == tracked }?.let { "OK" },
                detail = null,
                viewed = viewed,
            )
        }

        /** `SystemNotifier.deliver`'s gate, with the tracker's own answer. */
        fun deliverDecision(notification: AppNotification): Boolean =
            SystemNotificationPolicy.shouldDeliver(notification, foreground.contentVisible)
    }

    @Test
    fun aTurnThatFinishesUnderACoveringWindowIsTold() {
        val harness = Harness(tracked)
        harness.resumed()
        // The turn starts while the user is watching.
        harness.now = 2_000L
        harness.read(startFrame, stepFrame)
        // The Settings window covers the app at 3 000; the activity stays resumed.
        harness.now = 3_000L
        harness.foreground.focusLost()
        assertTrue(harness.foreground.inForeground)
        // The grace expires without a pause ever arriving: the focus-timeout
        // carrier ends the viewing period, as `SystemNotifier` runs it.
        harness.now = 3_000L + ForegroundTracker.FOCUS_LOST_GRACE_MS
        assertTrue(harness.foreground.focusTimedOut())
        // The turn finishes ~144 s later, read on the live stream.
        harness.now = 3_000L + ForegroundTracker.FOCUS_LOST_GRACE_MS + 144_000L
        harness.read(doneFrame)

        val raised = harness.offered.single()
        assertEquals(AppNotificationKind.AGENT, raised.kind)
        // Unwatched: the cover ended the period long before the turn did.
        assertFalse(raised.viewed)
        assertFalse(harness.foreground.contentVisible)
        assertTrue("the covered user is told", harness.deliverDecision(raised))
        // The old answer for contrast: a `viewed` raise against the resumed set
        // is exactly the skip the device measured.
        assertFalse(
            SystemNotificationPolicy.shouldDeliver(raised.copy(viewed = true), appInForeground = true),
        )
    }

    @Test
    fun aRaiseSkippedInsideTheGraceIsSpokenForByTheFocusTimeout() {
        val harness = Harness(tracked)
        harness.resumed()
        harness.now = 2_000L
        harness.read(startFrame, stepFrame)
        // Covered at 3 000, finished at 4 000 — still inside the grace, so the
        // raise is skipped as "the user is looking" and withheld.
        harness.now = 3_000L
        harness.foreground.focusLost()
        harness.now = 4_000L
        harness.read(doneFrame)

        val raised = harness.offered.single()
        assertTrue(raised.viewed)
        assertFalse("inside the grace: no system post", harness.deliverDecision(raised))
        // The cover stays: the timeout ends the period and the background
        // carrier (`shouldPostOnBackground`) speaks for the withheld raise.
        harness.now = 3_000L + ForegroundTracker.FOCUS_LOST_GRACE_MS
        assertTrue(harness.foreground.focusTimedOut())
        assertTrue(
            SystemNotificationPolicy.shouldPostOnBackground(
                raised,
                postedAt = null,
                now = System.currentTimeMillis(),
            ),
        )
    }

    @Test
    fun aForegroundStopInterruptionTakesTheBannerNotTheSystemPost() {
        val harness = Harness(tracked)
        harness.resumed()
        harness.now = 2_000L
        harness.read(startFrame, stepFrame)
        // The user hits Stop at 5 000 while looking at the session.
        harness.now = 5_000L
        harness.read(interruptedFrame)

        // The feed (and so the banner) still gets its entry — the gate only
        // decides the system post, and there is no path around it.
        val raised = harness.offered.single()
        assertEquals(AppNotificationKind.AGENT, raised.kind)
        assertTrue(raised.viewed)
        assertFalse("foreground: the banner owns it", harness.deliverDecision(raised))
    }

    @Test
    fun completionAndInterruptionRaiseThroughTheSameGate() {
        val completed = Harness(tracked)
        completed.resumed()
        completed.now = 2_000L
        completed.read(startFrame, stepFrame)
        completed.now = 5_000L
        completed.read(doneFrame)

        val interrupted = Harness(tracked)
        interrupted.resumed()
        interrupted.now = 2_000L
        interrupted.read(startFrame, stepFrame)
        interrupted.now = 5_000L
        interrupted.read(interruptedFrame)

        val done = completed.offered.single()
        val stopped = interrupted.offered.single()
        assertEquals(done.kind, stopped.kind)
        assertEquals(done.sessionID, stopped.sessionID)
        assertEquals(done.viewed, stopped.viewed)
        assertEquals(completed.deliverDecision(done), interrupted.deliverDecision(stopped))
    }
}
