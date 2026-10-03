package com.xiufeigo.opencodeshell.notify

/**
 * The run status of the session the shell is tracking, as **the wire** reported
 * it: the notification trigger's own synchronous mirror of
 * `ChatStore.ChatState.runStatus`.
 *
 * The trigger may not read the store, and that is the whole point of this
 * class. `ChatViewModel.onV2Frame` reads the frames on the SSE thread but
 * applies them through `scope.launch { applyEvent(event) }` on
 * `Dispatchers.Main`, so the store is filled **after** the read that produced
 * the event. Two cases follow, and both lose the turn-complete notification
 * (device v29 tB: a backgrounded turn finished, the transcript caught up
 * through the REST snapshot, and nothing was ever posted):
 *
 * - **a burst** — one read that carries a whole turn (`…started` → … →
 *   `…succeeded`), which is exactly what the durable replay after a reconnect
 *   delivers. The terminal frame is read while the store is still on the
 *   previous turn's status, so "was this session running?" answers `false`;
 * - **a settle first** — `ChatViewModel.refreshSnapshot` writes IDLE silently
 *   when it concludes that a missed frame ended the turn (its own comment says
 *   "session.idle can be missed while backgrounded"), which clears the
 *   transition before the frame that carries it arrives.
 *
 * Everything that *learns* a status writes here — the stream's status frames
 * and the server's live-set read — so the guard is "did this client watch that
 * session run?", and no carrier of the transition can lose it. The session is
 * the tracked one ([watch]); a frame that names another session is a foreign
 * session's run and must not be mistaken for it (the trigger treats a foreign
 * idle at face value, exactly as before).
 *
 * **The client's own clock** ([watchedAt] / [runSince], [since]) is the second
 * job of this class, and the reason it does not only mirror a boolean. The
 * delivery decision needs to answer "was the user looking at this session when
 * the turn *ended*", and on Android the frame that ends a turn is not
 * necessarily processed when the turn ends: a backgrounded app is a cached
 * process (the platform freezes it, and a dropped socket replays on reconnect),
 * so the terminal frame can be read minutes later, when the user is already
 * back on the session. Both timestamps are read from this class's injected
 * clock, never from a frame's `created` — the server's clock is not the
 * device's, and the two are hours apart on a real device
 * (`research/v30_verify/tA_notify_ws`: device 11:14, host 07:14).
 *
 * Pure Kotlin, no Android, so the semantics below are pinned by a JVM test.
 */
class TurnRunStatus(
    /** Injectable clock: every stamp below is the *client's* notion of "now". */
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    @Volatile
    private var watched: String? = null

    @Volatile
    private var running: Boolean = false

    /** When [watch] last took over — "this client started looking at the session". */
    @Volatile
    private var watchedAt: Long = 0L

    /** When the current (or most recent) turn was first seen running, or `null`. */
    @Volatile
    private var runSince: Long? = null

    /**
     * Whether the run seen since [runSince] has produced a **step** — a part
     * frame, i.e. a unit of model output.
     *
     * A run without one is not a turn: the server also reports a session as
     * busy while a session *update* is in flight (`session.updated`,
     * `session.moved` — the 「移动到…」 move, a rename, a location change), and
     * the terminal `idle` that follows such an update looks exactly like the end
     * of a turn on the wire. Raising "response ready" for it told the user a
     * reply had arrived for a session that had never been prompted (v50 tD:
     * three Moves, three notifications). [ranTurn] is what the trigger asks, so
     * only a run that actually produced output can settle as one.
     */
    @Volatile
    private var sawStep: Boolean = false

    /**
     * The shell now tracks [sessionID] (a session screen opened, or `null` for
     * the pre-session draft): the previous session's status is forgotten, so a
     * terminal frame of the *next* turn cannot inherit it.
     */
    fun watch(sessionID: String?) {
        watched = sessionID
        watchedAt = now()
        running = false
        runSince = null
        sawStep = false
    }

    /** [sessionID] is running — a `…started` / `status{busy,retry}` frame, or the server's live set. */
    fun running(sessionID: String?) {
        if (!owns(sessionID)) return
        // The *first* report of this turn is what dates it: the delivery decision
        // asks whether the user watched the whole turn, so a later frame (a
        // `step.streamed` in the last second of a 40 s turn) must not move the
        // reference forward and make a user who left early look attentive.
        // A new run also starts without a step — the one before it ended.
        if (!running) {
            runSince = now()
            sawStep = false
        }
        running = true
    }

    /**
     * The run of [sessionID] produced a part — one unit of model output. Only
     * then is the run a *turn* ([ranTurn]); a session update (a Move, a rename)
     * is busy and idle again without ever reaching here.
     */
    fun step(sessionID: String?) {
        if (owns(sessionID) && running) sawStep = true
    }

    /** [sessionID] settled — a terminal frame, or a reconciliation that concluded the same. */
    fun settled(sessionID: String?) {
        if (owns(sessionID)) {
            running = false
            sawStep = false
        }
    }

    /** `true` while this client has watched [sessionID] run and has not seen it settle. */
    fun isRunning(sessionID: String?): Boolean = owns(sessionID) && running

    /**
     * `true` when the run [isRunning] describes is a **real turn** — one that
     * produced output ([step]) — so its terminal frame is "response ready".
     *
     * This is the trigger's guard ([ChatViewModel.notifyFor]'s `finished`): a
     * session update on the wire is a busy/idle pair too, and answering it with
     * 「回复已完成」 was the v50 tD failure.
     */
    fun ranTurn(sessionID: String?): Boolean = owns(sessionID) && running && sawStep

    /**
     * The client-clock instant a notification about [sessionID] must date itself
     * from: when its turn was first seen running, else when this client started
     * looking at the session (a turn whose `…started` this client never saw — a
     * stream that was down when it was emitted, or a session opened mid-turn —
     * still ended *after* the session was opened, so the session's own start is
     * the honest earlier bound).
     *
     * `null` for a session this client is not tracking: a foreign session's
     * events have no local history to date them from, and the caller keeps its
     * face-value behaviour there (`ChatViewModel.raise`'s `viewed`).
     */
    fun since(sessionID: String?): Long? = if (owns(sessionID)) runSince ?: watchedAt else null

    /**
     * A frame with no session id belongs to the tracked session — the same rule
     * `ChatStore` uses (`tracksSession`), and the reason `routed == null` is not
     * a foreign session in `notifyFor`.
     */
    private fun owns(sessionID: String?): Boolean = sessionID == null || sessionID == watched
}
