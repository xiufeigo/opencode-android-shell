package com.xiufeigo.opencodeshell

/**
 * The Stop the server confirmed (`{interrupted:true}`), remembered so the
 * session's **live set** cannot re-open the turn it ended.
 *
 * **The failure it exists for** (emulator-5556 v76,
 * `research/v76_verify/5556/37_third_sent.png`): Stop was pressed, the server
 * answered `{"interrupted":true}`, the transcript showed the `Interrupted`
 * divider with a settled meta line (`Build · space-bunny-free · 22s ·
 * Interrupted`) — and the composer still offered Stop. The device's own logcat
 * says why, and says it in three lines:
 *
 * ```
 * 01:05:18 OcNotify: trigger idle: routed=ses_…ytK1zf6 … finished=false   ← the last idle this session got
 * 01:06:26 ChatViewModel: stop did not interrupt the turn: the server reports no execution to interrupt
 * 01:08:52 ChatViewModel: steer queued failed: HTTP 409 ConflictError: Pending input cannot change to steer
 * ```
 *
 * i.e. (1) the confirmed Stop's terminal frame never reached the store — the
 * tracked session's last `trigger idle` before the second tap is an *update's*
 * busy→idle an minute earlier (`finished=false`, no step), so nothing on the
 * wire was left to unlock the composer; (2) the second tap answered
 * `{"interrupted":false}` **while `GET /api/session/active` still listed the
 * session**, so the client reported 「Stop did not reach the server (the server
 * reports no execution to interrupt)」 for a turn that was already over
 * (`…/29_bottom.png`) — the map is stale, and it is the only authority the
 * client had; (3) the residual RUNNING was not cosmetic: the next message went
 * out as a **steer** into the dead turn (the composer's own delivery chip read
 * 「Steer」, `…/38_sent.png`) and the server refused it with a 409.
 *
 * The client's two carriers for "the turn is over" — [TurnIdleWatchdog] and the
 * reconcile half of `ChatViewModel.refreshSnapshot` — both *ask the server's
 * live set*, and both are right to: a long foreground tool wait emits no part
 * traffic at all, so silence alone proves nothing. But a Stop is where the wire
 * is the more specific of the two authorities: the interrupt answer names **the
 * execution this process was running** (and, upstream,
 * `core/src/session/execution.ts:156-172`, `resume` only wakes a successor when
 * a promotable inbox row is actually parked — with nothing parked, the run is
 * over), while the active set is a coarse per-session busy flag that answered
 * `running` for a session with nothing left to interrupt. So the Stop settles
 * the composer by itself (`ChatViewModel.abort`), and this class keeps that
 * settle from being undone: re-asserting RUNNING from the stale map is exactly
 * what leaves the button on Stop for good — every later probe reads the same
 * entry, and the watchdog's re-arm keeps the snapshot backstop's grace window
 * fresh, so the two carriers hold each other up.
 *
 * So a confirmed Stop stays on record until this client learns of a **new** run
 * — [newRun], from this client's own submit or from a frame that *announces* a
 * run ([startsRun]) — and [watch] clears it for another session. While the
 * record stands the live set may still be *read*: [TurnIdleWatchdog] re-arms on
 * it and the notification mirror hears it, both unchanged. It just may not
 * *assert* RUNNING over a turn the user stopped — not from the live set
 * ([allowsLiveAssert]), and not from the wire either ([allowsRunAssert]).
 *
 * Liveness frames deliberately do **not** clear the record. A stopped turn can
 * still have a step finishing on the wire (`session.step.streamed` /
 * `session.step.ended` are mapped to RUNNING — a liveness signal, not a start),
 * and letting one of those lift the record would hand the turn straight back to
 * the stale live set this class exists to refuse. What does lift it is
 * `session.execution.started` (`core/src/session/execution.ts:116-119`, one
 * publication per drain) or `session.step.started` — the server's own "work
 * begins here" — plus this client's optimistic submit.
 *
 * **[allowsRunAssert] is the second half of that rule, and it is what v78 was
 * missing** (emulator-5556, `research/v78_verify/5556/27-29`): closing the
 * *live-set* door left the wire's own open. `V2EventAdapter` maps seven frame
 * types onto RUNNING and the frame loop applied every one of them to the store
 * whatever the record said, so the step the Stop had just interrupted — its
 * `session.step.streamed` / `session.step.ended` liveness frames, published
 * while the execution fiber ran its finalizers
 * (`run-coordinator.ts:150-172`: the interruption is acknowledged before the
 * cleanup settles) — put the store straight back on RUNNING. The composer went
 * back to Stop and the delivery chip back to 「Steer」 for ~10 s, until the idle
 * watchdog's own grace expired and re-settled a turn the ack had already
 * settled (a trial whose ack was the last word converged in under a second, and
 * the steward of this bug read that difference as an off-bottom viewport). The
 * button state must follow the abort's acknowledgment, not whichever liveness
 * frame the dying turn happens to emit afterwards.
 *
 * Pure Kotlin, no Android and no coroutines, so the policy is pinned by a JVM
 * test.
 */
class StoppedTurn {

    @Volatile
    private var watched: String? = null

    /** The session whose turn this client stopped and has not seen run again. */
    @Volatile
    private var stopped: String? = null

    /** This client now tracks [sessionID]; the previous session's Stop is not its business. */
    fun watch(sessionID: String?) {
        watched = sessionID
        stopped = null
    }

    /** The server confirmed the interrupt of [sessionID]'s turn (`{interrupted:true}`). */
    fun confirmed(sessionID: String?) {
        if (owns(sessionID)) stopped = watched
    }

    /**
     * [sessionID] has a **new** run: this client's own submit, or a frame
     * [startsRun] names. Liveness of the run already in flight does not qualify.
     */
    fun newRun(sessionID: String?) {
        if (owns(sessionID)) stopped = null
    }

    /**
     * May a live-set reading assert RUNNING over the tracked turn? `false` while
     * the confirmed Stop stands, `true` everywhere else — including a session
     * this client has never watched, where the map is the only source there is
     * (a screen opened mid-turn).
     */
    fun allowsLiveAssert(sessionID: String?): Boolean = !(owns(sessionID) && stopped != null)

    /**
     * May a RUNNING frame of [frameType] assert (or re-assert) the tracked
     * turn? `true` everywhere outside a confirmed Stop; while the record stands
     * only a frame that *starts* a run may ([startsRun]) — the server's
     * `session.execution.started` for the successor a `resume` wakes, or a
     * step beginning, which is work happening now whatever the record says.
     *
     * The alternative — applying the frame and letting the next probe sort it
     * out — is the v78 failure this exists for: the record cannot survive a
     * store write it never sees, so the liveness of the turn that was just
     * stopped would re-open it (see the class note).
     */
    fun allowsRunAssert(sessionID: String?, frameType: String): Boolean =
        !(owns(sessionID) && stopped != null) || startsRun(frameType)

    /** A frame with no session id belongs to the tracked session (`ChatStore.tracksSession`). */
    private fun owns(sessionID: String?): Boolean = sessionID == null || sessionID == watched

    companion object {
        /**
         * Does this frame type announce a run *starting*, as opposed to the
         * liveness of one already in flight? The adapter maps seven frame types
         * onto RUNNING; only these two are a beginning, and the distinction is
         * what keeps a stopped turn stopped (see the class note).
         */
        fun startsRun(frameType: String): Boolean = frameType in STARTS_RUN

        private val STARTS_RUN = setOf(
            // `core/src/session/execution.ts:116-119`: one publication per drain,
            // including the successor a `resume` wakes.
            "session.execution.started",
            // A step began: work is happening now, whatever the live set says.
            "session.step.started",
        )
    }
}
