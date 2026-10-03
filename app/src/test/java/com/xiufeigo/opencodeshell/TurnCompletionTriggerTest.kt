package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.notify.AppNotification
import com.xiufeigo.opencodeshell.notify.AppNotificationKind
import com.xiufeigo.opencodeshell.notify.AppNotifier
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
 * B-2 / E5, the turn-complete half of the chain on the JVM: the frames the
 * server really sends (`research/dupcap/global.sse` shapes) → [V2EventAdapter] →
 * the trigger's guard → [AppNotifier] → the delivery decision.
 *
 * **The break this pins** (device v29 tB: the app was backgrounded while the
 * turn was `Working`, the turn finished, the transcript caught up — and no
 * notification was ever posted, `dumpsys notification` count 0). The trigger
 * asked the *store* for the session's run status, and the store is filled by
 * `scope.launch { applyEvent(…) }` — on `Dispatchers.Main`, i.e. **after** the
 * read that produced the event:
 *
 * - one read that carries a whole turn (`…started` → … → `…succeeded`) reaches
 *   the terminal frame with the store still on the previous turn's status, so
 *   "was this session running?" answers `false`. A durable replay after a
 *   reconnect delivers exactly that burst — and the app's own
 *   `refreshSnapshot` says "session.idle can be missed while backgrounded", so
 *   a backgrounded turn is precisely when it happens;
 * - `refreshSnapshot`'s settle path writes IDLE silently once it concludes a
 *   missed frame ended the turn, which clears the transition before the frame
 *   arrives.
 *
 * [theOldStoreGuardWouldHaveLostTheBurst] keeps that failure visible: the same
 * frames, the same frames' guard, one notification short. The fix is
 * [TurnRunStatus] — the status the **wire** reported, written synchronously by
 * the read — plus a raise from the reconciliation that concludes the turn
 * ended, because that is the only carrier left once the frame is gone.
 */
class TurnCompletionTriggerTest {

    private val tracked = "ses_1"

    /** `session.execution.started` / `.succeeded` as captured from the live server. */
    private val startFrame =
        """{"id":"evt_start","created":1790053402296,"type":"session.execution.started",""" +
            """"data":{"sessionID":"ses_1"},"durable":{"aggregateID":"ses_1","seq":3,"version":1}}"""

    private val doneFrame =
        """{"id":"evt_done","created":1790053436643,"type":"session.execution.succeeded",""" +
            """"data":{"sessionID":"ses_1"},"durable":{"aggregateID":"ses_1","seq":4,"version":1}}"""

    private val stepFrame =
        """{"id":"evt_step","created":1790053403000,"type":"session.step.started",""" +
            """"data":{"sessionID":"ses_1","assistantMessageID":"msg_asst_1","started":1790053403000},""" +
            """"durable":{"aggregateID":"ses_1","seq":4,"version":1}}"""

    private val idleOnlyFrame =
        """{"id":"evt_idle","created":1790053436643,"type":"session.idle",""" +
            """"data":{"sessionID":"ses_1"}}"""

    private val failedFrame =
        """{"id":"evt_fail","created":1790053436643,"type":"session.execution.failed",""" +
            """"data":{"sessionID":"ses_1","error":{"name":"ProviderError","message":"Rate limit"}},""" +
            """"durable":{"aggregateID":"ses_1","seq":5,"version":1}}"""

    /**
     * `ChatViewModel`'s notification loop, reduced to what the trigger needs: the
     * frames go through the real adapter, the guard is the mirror the read
     * writes, and the raise goes through the real [AppNotifier].
     */
    private class Harness(private val tracked: String) {
        val delivered = mutableListOf<AppNotification>()

        /** The trigger's own view of the wire ([ChatViewModel.turnRunStatus]). */
        val wire = TurnRunStatus().also { it.watch(tracked) }

        /**
         * `ChatStore.ChatState.runStatus`, i.e. what `applyEvent` writes — after
         * the read, never during it.
         */
        var store = ChatStore.RunStatus.IDLE

        private val notifier = AppNotifier(
            enabled = { true },
            delivery = NotificationDelivery { delivered += it },
        )

        /**
         * One SSE read. [guard] selects what "was that session running?"
         * answers, so the old expression and the fixed one can be run over the
         * same frames.
         */
        fun read(vararg frames: String, guard: (String?) -> Boolean = { wire.ranTurn(it) }) {
            val applied = mutableListOf<ChatStore.RunStatus>()
            for (json in frames) {
                val envelope = JSONObject(json)
                val routed = V2EventAdapter.sessionID(envelope)
                val events = V2EventAdapter.adapt(envelope.optString("type"), envelope)
                for (event in events) {
                    if (event !is ChatStore.Event.RunStatusChanged) continue
                    when (event.status) {
                        ChatStore.RunStatus.RUNNING -> wire.running(routed)

                        ChatStore.RunStatus.ERROR -> {
                            wire.settled(routed)
                            raise(AppNotificationKind.ERROR, routed, event.error)
                        }

                        ChatStore.RunStatus.IDLE -> {
                            val foreign = routed != null && routed != tracked
                            val finished = !foreign && guard(routed)
                            wire.settled(routed)
                            if (foreign || finished) raise(AppNotificationKind.AGENT, routed)
                        }
                    }
                    applied += event.status
                }
                // A part frame is what makes a run a *turn* — `ChatViewModel`
                // feeds it to the same mirror right after the status event.
                if (events.any { it is ChatStore.Event.PartUpdated }) wire.step(routed)
            }
            // `scope.launch { applyEvent(event) }`: the store lands afterwards.
            for (status in applied) store = status
        }

        /** The reconciliation's conclusion (`ChatViewModel.refreshSnapshot`'s settle branch). */
        fun reconcileSettled() {
            raise(AppNotificationKind.AGENT, tracked)
            wire.settled(tracked)
            store = ChatStore.RunStatus.IDLE
        }

        /**
         * `ChatViewModel.raise`'s route half: the tracked session's title, and
         * `viewed` = "the session is the route". The *other* half — whether the
         * app stayed in the foreground for the turn, which is what decides
         * delivery when the terminal frame is read late — needs a foreground
         * history and is pinned by `NotifyBackgroundDeliveryTest`.
         */
        private fun raise(kind: AppNotificationKind, sessionID: String?, detail: String? = null) {
            notifier.raise(
                kind = kind,
                sessionID = sessionID,
                sessionTitle = sessionID?.takeIf { it == tracked }?.let { "OK" },
                detail = detail,
                viewed = sessionID != null && sessionID == tracked,
            )
        }
    }

    // ------------------------------------------------------------ the break

    @Test
    fun theOldStoreGuardWouldHaveLostTheBurst() {
        val harness = Harness(tracked)
        harness.read(startFrame, stepFrame, doneFrame) { harness.store == ChatStore.RunStatus.RUNNING }
        // The store is only on IDLE once the read is over — which is why the
        // terminal frame answered "not running" and nothing was ever posted.
        assertEquals(ChatStore.RunStatus.IDLE, harness.store)
        assertEquals(emptyList<AppNotification>(), harness.delivered)
    }

    @Test
    fun aTurnThatRanOnTheWireRaisesOneAgentNotification() {
        val harness = Harness(tracked)
        harness.read(startFrame, stepFrame, doneFrame)

        assertEquals(1, harness.delivered.size)
        val raised = harness.delivered.single()
        assertEquals(AppNotificationKind.AGENT, raised.kind)
        assertEquals(tracked, raised.sessionID)
        assertEquals("OK", raised.sessionTitle)
    }

    // ------------------------------------------- the two delivery branches

    @Test
    fun aBackgroundedAppIsToldAndAViewerOfThatSessionIsNot() {
        val harness = Harness(tracked)
        harness.read(startFrame, stepFrame, doneFrame)
        val raised = harness.delivered.single()
        // `viewed` is true: the raise happened while that session was the route.
        assertTrue(raised.viewed)
        // HOME: no resumed activity → the user is told.
        assertTrue(SystemNotificationPolicy.shouldDeliver(raised, appInForeground = false))
        // Looking at the session: the in-app banner already said it.
        assertFalse(SystemNotificationPolicy.shouldDeliver(raised, appInForeground = true))
    }

    @Test
    fun aTurnThatSettledOffTheScreenIsToldToo() {
        val harness = Harness(tracked)
        harness.read(startFrame, stepFrame, doneFrame)
        // The raise happened while the route was elsewhere (`viewed` false) — the
        // neighbouring-session case the feed exists for.
        val offScreen = AppNotification(
            id = "AGENT:other:1",
            kind = AppNotificationKind.AGENT,
            sessionID = "ses_other",
            sessionTitle = null,
            detail = null,
            time = 1L,
            viewed = false,
        )
        assertTrue(SystemNotificationPolicy.shouldDeliver(offScreen, appInForeground = true))
        assertTrue(SystemNotificationPolicy.shouldDeliver(offScreen, appInForeground = false))
    }

    // ------------------------------------------------- the guard's intent

    @Test
    fun anIdleForASessionThatNeverRanRaisesNothing() {
        val harness = Harness(tracked)
        // The server emits idle for sessions that were already idle; that is not
        // "response ready".
        harness.read(idleOnlyFrame)
        assertEquals(emptyList<AppNotification>(), harness.delivered)
    }

    /**
     * v50 tD: a session **update** is a busy → idle pair on the wire too — the
     * 「移动到…」 move made the session report `busy` and then `idle` — and the
     * session had never been prompted. Answering that idle with 「回复已完成」
     * gave the user a notification per Move (three Moves, three banners).
     *
     * A run that produced no step is not a turn, so it may not settle as one.
     */
    @Test
    fun aSessionUpdateThatNeverProducedAStepIsNotAResponseReady() {
        val harness = Harness(tracked)
        harness.read(
            """{"id":"evt_busy","type":"session.status","data":{"sessionID":"ses_1","type":"busy"},""" +
                """"durable":{"aggregateID":"ses_1","seq":9,"version":1}}""",
            """{"id":"evt_idle2","type":"session.status","data":{"sessionID":"ses_1","type":"idle"},""" +
                """"durable":{"aggregateID":"ses_1","seq":10,"version":1}}""",
        )
        assertEquals(emptyList<AppNotification>(), harness.delivered)
    }

    /** …while the same pair with a step in between is the turn it looks like. */
    @Test
    fun theSameBusyIdlePairAroundAStepStillRaises() {
        val harness = Harness(tracked)
        harness.read(
            startFrame,
            stepFrame,
            """{"id":"evt_idle3","type":"session.idle","data":{"sessionID":"ses_1"}}""",
        )
        assertEquals(listOf(AppNotificationKind.AGENT), harness.delivered.map { it.kind })
    }

    @Test
    fun aForeignSessionIsTakenAtFaceValue() {
        val harness = Harness(tracked)
        harness.read(
            """{"id":"evt_f1","type":"session.execution.succeeded","data":{"sessionID":"ses_other"},""" +
                """"durable":{"aggregateID":"ses_other","seq":2,"version":1}}""",
        )
        // This client never watched that session run, but its idle is still the
        // end of a turn the user should hear about.
        assertEquals(listOf("ses_other"), harness.delivered.map { it.sessionID })
        // …and it must not have taught the mirror anything about the tracked one.
        assertFalse(harness.wire.isRunning(tracked))
    }

    @Test
    fun aTerminalErrorSettlesTheRunSoALaterIdleCannotClaimOne() {
        val harness = Harness(tracked)
        harness.read(startFrame, stepFrame, failedFrame)
        assertEquals(listOf(AppNotificationKind.ERROR), harness.delivered.map { it.kind })
        // The run is over: a trailing `session.status{idle}` must not raise a
        // second, "response ready" notification.
        harness.read(doneFrame)
        assertEquals(listOf(AppNotificationKind.ERROR), harness.delivered.map { it.kind })
    }

    // ------------------------------------------------ the recovery carrier

    @Test
    fun theReconciliationRaisesWhenTheFrameNeverArrived() {
        val harness = Harness(tracked)
        // The stream saw the turn run (a `…started` frame, or the server's live
        // set — `refreshSnapshot` writes both into the mirror) and then dropped
        // before the terminal frame.
        harness.read(startFrame, stepFrame)
        assertEquals(emptyList<AppNotification>(), harness.delivered)

        // The reconnect snapshot concludes the turn is over. This used to write
        // IDLE and nothing else, so a backgrounded user was never told.
        harness.reconcileSettled()
        assertEquals(1, harness.delivered.size)
        val raised = harness.delivered.single()
        assertEquals(AppNotificationKind.AGENT, raised.kind)
        assertTrue(SystemNotificationPolicy.shouldDeliver(raised, appInForeground = false))
    }

    @Test
    fun theReconciliationAndTheLateFrameAreOneNotification() {
        val harness = Harness(tracked)
        harness.read(startFrame, stepFrame)
        harness.reconcileSettled()
        // The frame the reconnect replayed arrives right after: the mirror is
        // settled, so it is not a second "response ready".
        harness.read(doneFrame)
        assertEquals(1, harness.delivered.size)
    }
}
