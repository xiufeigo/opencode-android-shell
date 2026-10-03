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
import java.io.File

/**
 * The measured v30 break (`research/v30_verify/tA_notify_ws/`), pinned end to
 * end on the JVM: the app sends the prompt from its own UI, the user leaves
 * immediately, the turn finishes in the background in four seconds — and
 * `cmd notification list` never shows a single entry for the app, while the
 * transcript heals so the completion is visible on return.
 *
 * **Why nothing was posted.** The raise did happen; the *delivery* decision
 * dropped it. `SystemNotificationPolicy.shouldDeliver` asked "is the app in the
 * foreground, on that session?" and on Android the honest answer at that moment
 * is yes — because the frame that ends the turn is read after the user is back:
 * a backgrounded app is a cached process (the platform freezes it, and a socket
 * that dies while cached replays on reconnect), so `onV2Frame` processes
 * `session.execution.succeeded` minutes later, on the way back to the
 * foreground, where `viewed` was also computed. Both gates then said "the user
 * already saw this", and the notification was silently dropped.
 *
 * The fix dates the raise from the **turn** instead of from the frame's read
 * time — `TurnRunStatus.since` (the client's own clock) plus
 * `NotificationDelivery.watchedThrough` — so `viewed` means "the user was
 * looking at that session when the turn ended", which is what upstream's
 * `document.hasFocus()` + `viewedInCurrentSession` pair amounts to.
 *
 * The second measured break (`research/v31_verify/tA/`, the third device FAIL)
 * was the *other* half of the same decision: the foreground state itself was a
 * count over `onActivityStarted`/`onActivityStopped`, and Android defers the
 * stop callback by more than a second — so a raise 40 ms after HOME was still
 * judged foreground, and both gates dropped it. The harness below therefore
 * drives the **real** [ForegroundTracker] with the callback order a device
 * produces (`leaveApp` = `onActivityPaused`, `lateStop` = the delayed
 * `onActivityStopped`), and the deferred re-check's decision is the real
 * `SystemNotificationPolicy.shouldPostDeferred`.
 *
 * The trigger and the raise are re-implemented here over the **real**
 * [V2EventAdapter], [TurnRunStatus], [AppNotifier] and policy functions, exactly
 * like [TurnCompletionTriggerTest]; [theRaiseIsTheRealOne] pins the two lines of
 * `ChatViewModel` that this harness stands in for, so the model cannot drift
 * away from the code it describes.
 */
class NotifyBackgroundDeliveryTest {

    private val tracked = "ses_1"

    /** `session.execution.started` / `.succeeded` as captured from the live server. */
    private val startFrame =
        """{"id":"evt_start","created":1790053402296,"type":"session.execution.started",""" +
            """"data":{"sessionID":"ses_1"},"durable":{"aggregateID":"ses_1","seq":3,"version":1}}"""

    private val doneFrame =
        """{"id":"evt_done","created":1790053436643,"type":"session.execution.succeeded",""" +
            """"data":{"sessionID":"ses_1"},"durable":{"aggregateID":"ses_1","seq":4,"version":1}}"""

    /**
     * `session.step.started` — the frame that makes a run a *turn*. The adapter
     * turns it into a part plus a `RUNNING`, and the part is what
     * `TurnRunStatus.step` counts; without one, a busy/idle pair is a session
     * update (v50 tD's Move) and must not raise "response ready".
     */
    private val stepFrame =
        """{"id":"evt_step","created":1790053403000,"type":"session.step.started",""" +
            """"data":{"sessionID":"ses_1","assistantMessageID":"msg_asst_1","started":1790053403000},""" +
            """"durable":{"aggregateID":"ses_1","seq":4,"version":1}}"""

    /**
     * The device timeline, with both clocks under the test's control: [clock] is
     * the client's (the only one the decision may use) and the notifier's
     * foreground state is the **real** [ForegroundTracker] over that clock
     * (`ForegroundTracker.foregroundSince` is `null` while the app is
     * backgrounded, the entry instant otherwise). The callbacks arrive in the
     * order a device produces them, including the late `onActivityStopped` that
     * made the old counter lie.
     */
    private class Harness(private val tracked: String) {
        val delivered = mutableListOf<AppNotification>()

        var clock = 1_000L

        /** `SystemNotifier`'s state, over this harness's clock. */
        private val foreground = ForegroundTracker(now = { clock })

        /** The one activity of the shell; only its identity matters. */
        private val activity = Any()

        /** What `SystemNotificationPolicy.shouldDeliver` is fed. */
        val appInForeground: Boolean get() = foreground.inForeground

        /** The trigger's own view of the wire, on the client clock. */
        val wire = TurnRunStatus(now = { clock }).also { it.watch(tracked) }

        private val notifier = AppNotifier(
            enabled = { true },
            now = { clock },
            delivery = object : NotificationDelivery {
                override fun deliver(notification: AppNotification) {
                    delivered += notification
                }

                /** `SystemNotifier.watchedThrough`, over this harness's history. */
                override fun watchedThrough(since: Long?): Boolean =
                    SystemNotificationPolicy.watchedThroughout(foreground.foregroundSince, since)
            },
        )

        init {
            // The composition that built the notifier ran after the activity's
            // resume, so `SystemNotifier` seeds it as already on screen.
            foreground.resumed(activity)
        }

        /** The user pressed HOME: `onActivityPaused`, part of handling the press. */
        fun leaveApp() {
            foreground.paused(activity)
        }

        /**
         * The late `onActivityStopped` (~1.1 s after the pause on the measured
         * device): it must not move the state any more.
         */
        fun lateStop() {
            foreground.stopped(activity)
        }

        /** The user came back to the app: `onActivityResumed`. */
        fun returnToApp() {
            foreground.resumed(activity)
        }

        /** One SSE read, wherever the reader happens to be running. */
        fun read(vararg frames: String) {
            for (json in frames) {
                val envelope = JSONObject(json)
                val routed = V2EventAdapter.sessionID(envelope)
                val events = V2EventAdapter.adapt(envelope.optString("type"), envelope)
                for (event in events) {
                    if (event !is ChatStore.Event.RunStatusChanged) continue
                    when (event.status) {
                        ChatStore.RunStatus.RUNNING -> wire.running(routed)

                        ChatStore.RunStatus.ERROR -> {
                            val since = wire.since(routed)
                            wire.settled(routed)
                            raise(AppNotificationKind.ERROR, routed, since)
                        }

                        ChatStore.RunStatus.IDLE -> {
                            val foreign = routed != null && routed != tracked
                            val finished = !foreign && wire.ranTurn(routed)
                            val since = wire.since(routed)
                            wire.settled(routed)
                            if (foreign || finished) raise(AppNotificationKind.AGENT, routed, since)
                        }
                    }
                }
                // A part frame is what makes a run a *turn*: `ChatViewModel`
                // feeds it to the same mirror after the status event of its own
                // frame, so the order here is the same.
                if (events.any { it is ChatStore.Event.PartUpdated }) wire.step(routed)
            }
        }

        /** `ChatViewModel.refreshSnapshot`'s settle conclusion. */
        fun reconcileSettled(since: Long = wire.since(tracked) ?: clock) {
            raise(AppNotificationKind.AGENT, tracked, since)
            wire.settled(tracked)
        }

        /** `ChatViewModel.raise`: the route **and** the watched-through test. */
        private fun raise(kind: AppNotificationKind, sessionID: String?, since: Long?) {
            val watched = since == null ||
                SystemNotificationPolicy.watchedThroughout(foreground.foregroundSince, since)
            notifier.raise(
                kind = kind,
                sessionID = sessionID,
                sessionTitle = sessionID?.takeIf { it == tracked }?.let { "OK" },
                viewed = sessionID != null && sessionID == tracked && watched,
            )
        }
    }

    // ------------------------------------------------- the reported scenario

    @Test
    fun aTurnThatEndedWhileTheAppWasAwayIsDelivered() {
        val harness = Harness(tracked)
        // The user is on the session and sends the prompt from the app UI: the
        // server's `…started` is read while the app is still foreground.
        harness.clock = 1_100L
        harness.read(startFrame, stepFrame)
        assertTrue("the turn is live", harness.wire.isRunning(tracked))

        // HOME right after sending.
        harness.clock = 1_200L
        harness.leaveApp()

        // The turn ends four seconds later, in the background. The frame is read
        // only once the app is back — the cached process was frozen, the socket
        // replayed, whatever the platform did: the reader is late, and late is
        // the normal case here, not an edge case.
        harness.clock = 30_000L
        harness.returnToApp()
        harness.read(doneFrame)

        assertEquals(1, harness.delivered.size)
        val raised = harness.delivered.single()
        assertEquals(AppNotificationKind.AGENT, raised.kind)
        assertEquals(tracked, raised.sessionID)
        // The raise is honest about what the user saw: they were away.
        assertFalse("the user did not see this turn end", raised.viewed)
        // …and that is what makes it leave the app, even though the reader is
        // foreground on that very session by now — the pre-fix expression
        // (`viewed = route == session`) answered `true` here and dropped it.
        assertTrue(
            "a foreground app is still told about a turn it did not watch",
            SystemNotificationPolicy.shouldDeliver(raised, appInForeground = true),
        )
        assertTrue(SystemNotificationPolicy.shouldDeliver(raised, appInForeground = false))
    }

    @Test
    fun aTurnTheUserWatchedToTheEndIsNotDelivered() {
        val harness = Harness(tracked)
        harness.clock = 1_100L
        harness.read(startFrame, stepFrame)
        // No trip to the background: the app has been foreground since before the
        // turn began, so the user watched it end — upstream's focused window.
        harness.clock = 1_400L
        harness.read(doneFrame)

        val raised = harness.delivered.single()
        assertTrue(raised.viewed)
        assertFalse(
            "the in-app banner already said it",
            SystemNotificationPolicy.shouldDeliver(raised, appInForeground = true),
        )
        assertTrue(SystemNotificationPolicy.shouldDeliver(raised, appInForeground = false))
    }

    @Test
    fun aLateFrameForATurnThisClientNeverWatchedIsStillDatedFromTheSession() {
        val harness = Harness(tracked)
        // The session was opened (and `watch`ed) at t=1000; the turn's `…started`
        // never reached this client — a stream that was down when it was emitted.
        // The reference then falls back to the session's own start, which is
        // still *before* the user left, so the raise is delivered.
        harness.clock = 2_000L
        harness.leaveApp()
        harness.clock = 9_000L
        harness.returnToApp()
        harness.read(doneFrame)

        // The mirror never saw a run, so the guard alone would have dropped it —
        // the frame is the recovery carrier's job here.
        assertEquals(emptyList<AppNotification>(), harness.delivered)
        harness.reconcileSettled()
        val raised = harness.delivered.single()
        assertFalse(raised.viewed)
        assertTrue(SystemNotificationPolicy.shouldDeliver(raised, appInForeground = true))
    }

    @Test
    fun theReconciliationCarrierIsDeliveredToo() {
        val harness = Harness(tracked)
        harness.clock = 1_100L
        harness.read(startFrame, stepFrame)
        harness.clock = 1_200L
        harness.leaveApp()
        // The reconnect snapshot concludes the turn is over — which only happens
        // on the way back to the foreground. Dating the raise from the turn is
        // what keeps it from being suppressed by construction.
        harness.clock = 60_000L
        harness.returnToApp()
        harness.reconcileSettled()

        val raised = harness.delivered.single()
        assertFalse(raised.viewed)
        assertTrue(SystemNotificationPolicy.shouldDeliver(raised, appInForeground = true))
    }

    @Test
    fun aTurnWatchedFromAnotherPageOfTheAppStillReachesTheUser() {
        val harness = Harness(tracked)
        harness.clock = 1_100L
        harness.read(startFrame, stepFrame)
        harness.clock = 1_400L
        harness.read(doneFrame)
        // Same app, foreground throughout — but the raise is about a session the
        // user is not on (`viewed` false because the route is elsewhere). That is
        // the case upstream cannot have and the reason `shouldDeliver` is not a
        // plain `!focused` test.
        val offScreen = harness.delivered.single().copy(sessionID = "ses_other", viewed = false)
        assertTrue(SystemNotificationPolicy.shouldDeliver(offScreen, appInForeground = true))
    }

    // ------------------------------------- the v31 race: HOME, then the idle frame

    /**
     * The measured v31 timeline (`research/v31_verify/tA/`), where the raise was
     * skipped as "the app is foreground" although the user had pressed HOME
     * 40 ms earlier — because the state came from `onActivityStarted` /
     * `onActivityStopped` and Android only delivered the *stop* callback 1.1 s
     * after the decision. Here the state is the real [ForegroundTracker] and the
     * idle frame lands 300 ms after HOME: inside the window the old counter spent
     * lying.
     */
    @Test
    fun anIdleFrameArrivingWithinASecondOfHomeIsAlreadyBackgrounded() {
        val harness = Harness(tracked)
        harness.clock = 1_100L
        harness.read(startFrame, stepFrame)

        // HOME: `onActivityPaused` is part of handling the press.
        harness.clock = 1_200L
        harness.leaveApp()
        assertFalse("the pause callback already said backgrounded", harness.appInForeground)

        // The turn ends 300 ms later, while the user is away.
        harness.clock = 1_500L
        harness.read(doneFrame)

        // The stop callback arrives 1.1 s after HOME, exactly as on the device —
        // and it changes nothing, because the state was already right.
        harness.clock = 2_300L
        harness.lateStop()
        assertFalse(harness.appInForeground)

        val raised = harness.delivered.single()
        assertFalse("the user left before the turn ended", raised.viewed)
        assertTrue(
            "a raise 300 ms after HOME is delivered",
            SystemNotificationPolicy.shouldDeliver(raised, appInForeground = harness.appInForeground),
        )
    }

    /**
     * The other half of the v31 fix: when the raise *is* skipped — the SSE thread
     * read the frame a few milliseconds before the main thread ran the pause
     * callback, which no signal can rule out — the deferred re-check posts it two
     * seconds later, once the state has settled.
     */
    @Test
    fun aRaiseJudgedForegroundWhileTheUserWasLeavingIsPostedByTheDeferredCheck() {
        val harness = Harness(tracked)
        harness.clock = 1_100L
        harness.read(startFrame, stepFrame)
        // The raise wins the race: it runs while the notifier still reads
        // foreground, so `viewed` is true and `shouldDeliver` skips it.
        harness.clock = 1_400L
        harness.read(doneFrame)
        val raised = harness.delivered.single()
        assertTrue(raised.viewed)
        assertFalse(
            "the raise itself is skipped",
            SystemNotificationPolicy.shouldDeliver(raised, appInForeground = true),
        )

        // The pause callback lands 5 ms later; two seconds after the raise the
        // re-check runs with the user away.
        harness.clock = 1_405L
        harness.leaveApp()
        harness.clock = 3_400L
        assertFalse(harness.appInForeground)
        assertTrue(
            "the user is away and this slot was never posted",
            SystemNotificationPolicy.shouldPostDeferred(
                notification = raised,
                appInForeground = harness.appInForeground,
                postedAt = null,
            ),
        )
    }

    /**
     * …and the re-check never double-posts: a user who is back (or never left)
     * already has the in-app banner, and a slot that has since carried a newer
     * notification must not be overwritten by the stale entry on the timer.
     */
    @Test
    fun theDeferredCheckNeverDoublePosts() {
        fun notification(time: Long, viewed: Boolean = false) = AppNotification(
            id = "AGENT:ses_1:$time",
            kind = AppNotificationKind.AGENT,
            sessionID = "ses_1",
            sessionTitle = "OK",
            detail = null,
            time = time,
            viewed = viewed,
        )

        // Still looking at the session: the skip stands.
        assertFalse(
            SystemNotificationPolicy.shouldPostDeferred(
                notification = notification(1_400L, viewed = true),
                appInForeground = true,
                postedAt = null,
            ),
        )
        // The slot has carried a newer raise since (posted at 3 000): the stale
        // entry waiting on the timer is dropped, not posted on top of it.
        assertFalse(
            SystemNotificationPolicy.shouldPostDeferred(
                notification = notification(1_400L),
                appInForeground = false,
                postedAt = 3_000L,
            ),
        )
        // The same instant was already posted — this raise is covered.
        assertFalse(
            SystemNotificationPolicy.shouldPostDeferred(
                notification = notification(3_000L),
                appInForeground = false,
                postedAt = 3_000L,
            ),
        )
        // A newer raise than what was posted still gets through.
        assertTrue(
            SystemNotificationPolicy.shouldPostDeferred(
                notification = notification(4_000L),
                appInForeground = false,
                postedAt = 3_000L,
            ),
        )
        // v50 tD: the re-check is the raise's **own** gate asked a second time,
        // not a weaker one built out of `appInForeground` alone. It can only
        // ever disagree with the raise about the foreground state, so a raise
        // the user is still looking at is dropped the same way twice — while a
        // raise about a session that is off screen is still told, because that
        // is what `shouldDeliver` says both times.
        val watching = notification(4_000L, viewed = true)
        assertFalse(
            SystemNotificationPolicy.shouldPostDeferred(
                notification = watching,
                appInForeground = true,
                postedAt = null,
            ),
        )
        val offScreen = notification(4_000L, viewed = false)
        assertTrue(
            SystemNotificationPolicy.shouldPostDeferred(
                notification = offScreen,
                appInForeground = true,
                postedAt = null,
            ),
        )
    }

    // ---------------------------------------------------- the harness is honest

    @Test
    fun theRaiseIsTheRealOne() {
        val viewModel = source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")
        // The two lines this harness stands in for: the raise must ask the
        // Android half whether the user watched the turn, and must pass the
        // turn's own date.
        assertTrue(
            viewModel.contains("val watched = since == null || (notificationDelivery?.watchedThrough(since) ?: true)"),
        )
        assertTrue(viewModel.contains("val viewed = sessionID != null && sessionID == visibleSessionID && watched"))
        // …and the trigger must date the raise from the mirror before settling it.
        assertTrue(viewModel.contains("val since = turnRunStatus.since(routed)"))
        assertTrue(viewModel.contains("raise(AppNotificationKind.AGENT, sessionID = routed, since = since)"))
        // v50 tD: the guard is "ran a turn", and a turn is a run that produced a
        // part frame — a Move's busy/idle pair never does.
        assertTrue(viewModel.contains("val finished = !foreign && turnRunStatus.ranTurn(routed)"))
        assertTrue(viewModel.contains("turnRunStatus.step(routed)"))
        // The reconciliation carrier dates itself from the same turn.
        assertTrue(viewModel.contains("val since = turnRunStatus.since(id) ?: lastPartEventAt"))
    }

    @Test
    fun theAndroidHalfAnswersTheWatchedQuestionFromItsOwnHistory() {
        val notifier = source("src/main/java/com/xiufeigo/opencodeshell/notify/SystemNotifier.kt")
        assertTrue(notifier.contains("override fun watchedThrough(since: Long?)"))
        assertTrue(notifier.contains("SystemNotificationPolicy.watchedThroughout(foregroundSince, since)"))
        // The state is seeded from the activity's lifecycle: a composition runs
        // after the first `onActivityResumed`, so the listener alone would report
        // a backgrounded app for a whole launch.
        assertTrue(notifier.contains("owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)"))
        assertTrue(source("src/main/java/com/xiufeigo/opencodeshell/OcActivity.kt").contains("owner = this@OcActivity"))
    }

    /**
     * The v31 fix itself, at the level a JVM test can hold it: the state comes
     * from the *resume* callbacks (the pause one is immediate; the stop one is
     * the 1.1 s lie), and a raise that is still skipped is carried by the
     * deferred re-check instead of being dropped.
     */
    @Test
    fun theForegroundSignalIsTheResumeCallbackAndTheSkipHasACarrier() {
        val notifier = source("src/main/java/com/xiufeigo/opencodeshell/notify/SystemNotifier.kt")
        // The state is ForegroundTracker, fed by the three callbacks that are
        // allowed to touch it — and `started` is not one of the up signals.
        assertTrue(notifier.contains("ForegroundTracker()"))
        assertTrue(notifier.contains("val appInForeground: Boolean get() = foreground.inForeground"))
        assertTrue(notifier.contains("foreground.resumed(activity)"))
        assertTrue(notifier.contains("foreground.paused(activity)"))
        assertTrue(notifier.contains("foreground.stopped(activity)"))
        assertFalse(
            "the started callback must not be an up signal",
            notifier.contains("started.incrementAndGet()"),
        )
        // A skipped raise is re-judged, not dropped.
        assertTrue(notifier.contains("scheduleRecheck(notification)"))
        assertTrue(notifier.contains("SystemNotificationPolicy.shouldPostDeferred("))
        assertTrue(notifier.contains("SystemNotificationPolicy.RECHECK_DELAY_MS"))
        // The re-check is de-duplicated through the slot's own post history, and a
        // newer post cancels the stale timer.
        assertTrue(notifier.contains("posted[id] = notification.time"))
        assertTrue(notifier.contains("rechecks.remove(id)?.let { handler.removeCallbacks(it) }"))
        // The tracker's callback ordering is pinned by ForegroundTrackerTest.
        assertTrue(
            source("src/test/java/com/xiufeigo/opencodeshell/ForegroundTrackerTest.kt")
                .contains("homeIsBackgroundedByThePauseCallbackNotTheLateStop"),
        )
    }

    /** The chain's single log tag, so a device run is diagnosable from logcat. */
    @Test
    fun everyGateOfTheChainLogsUnderOneTag() {
        val tag = "OcNotify"
        val notifier = source("src/main/java/com/xiufeigo/opencodeshell/notify/SystemNotifier.kt")
        val appNotifier = source("src/main/java/com/xiufeigo/opencodeshell/notify/AppNotifier.kt")
        val viewModel = source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")
        val activity = source("src/main/java/com/xiufeigo/opencodeshell/OcActivity.kt")
        assertTrue(notifier.contains("const val TAG = \"$tag\""))
        // The gates themselves, one log each.
        assertTrue(notifier.contains("skip: notifications disabled for the package"))
        assertTrue(notifier.contains("skip: app is foreground on "))
        assertTrue(notifier.contains("\"posted: id=\$id kind="))
        assertTrue(notifier.contains("deliver failed: kind="))
        assertTrue(appNotifier.contains("const val TAG = \"$tag\""))
        assertTrue(appNotifier.contains("gate: kind=\$kind disabled by the settings switch"))
        assertTrue(appNotifier.contains("de-bounced"))
        assertTrue(appNotifier.contains("accepted: \$key viewed=\$viewed"))
        assertTrue(viewModel.contains("private const val NOTIFY_TAG = \"$tag\""))
        assertTrue(viewModel.contains("gate settings: kind=\$kind enabled=\$enabled"))
        assertTrue(viewModel.contains("trigger idle: routed="))
        assertTrue(viewModel.contains("raise: kind=\$kind session=\$sessionID"))
        assertTrue(activity.contains("permission: sdk=\${Build.VERSION.SDK_INT}"))
    }

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")
}
