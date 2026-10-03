package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ChatStore.RunStatus

/**
 * The foreground idle watchdog's decision, kept apart from the coroutine that
 * does the talking.
 *
 * **The failure it exists for** (research/v54_verify/tB: the assistant's last
 * message was `completed` and the server had been idle for 44 s+, yet the
 * composer still offered Stop). `ChatStore.ChatState.runStatus` only ever
 * leaves RUNNING through a frame the adapter turns into `RunStatusChanged`
 * (`session.idle`, `session.status{idle}`, `session.execution.succeeded`,
 * `session.execution.interrupted`). If that terminal frame is lost — a dropped
 * socket whose durable replay has already passed the seq watermark, a frame
 * the watermark filtered as a duplicate, a run the server simply never
 * reported idle for — nothing resets the flag: the snapshot backstop
 * ([ChatViewModel.refreshSnapshot]) only runs when something *else* triggers
 * it, so a foreground session that just sits there stays RUNNING forever and
 * the send button never comes back.
 *
 * So this is a *second* carrier for the same conclusion, and it asks the
 * server rather than guessing from silence: once a turn has been quiet for
 * [graceMs] the watchdog probes the server's live set
 * (`SessionTransport.activeRunStatus`, read the same way by the snapshot
 * backstop), and
 *
 * - a session the server still lists as running is re-armed, not settled — a
 *   long foreground tool wait emits no part traffic at all (the reason the
 *   backstop asks the server), so silence alone proves nothing;
 * - a session the server does not list is settled to IDLE.
 *
 * `null` means "not in the active set" (or a read that failed — the transport
 * swallows that into the same answer, as it does for the backstop): the turn is
 * either over or unreachable, and in both cases a locked composer is the worse
 * outcome. The re-arm branch is what keeps a live turn safe — a server that
 * lists the session is never settled, **except** while a Stop this client
 * confirmed stands: that map is the authority the Stop refuted, and re-arming
 * on it is what kept the composer on Stop over an `Interrupted` transcript
 * (v76, [StoppedTurn]).
 *
 * Pure Kotlin, no Android and no coroutines, so the policy below is pinned by a
 * JVM test.
 */
class TurnIdleWatchdog(
    /** How long a turn may be silent before the server is asked about it. */
    private val graceMs: Long,
    private val now: () -> Long = { System.currentTimeMillis() },
    /** The client's own last "this turn produced something" stamp. */
    private val lastActivityAt: () -> Long,
) {

    /** How long the tracked turn has produced no traffic, on the client clock. */
    fun quietFor(): Long = now() - lastActivityAt()

    /**
     * Whether this tick is worth a server probe: the tracked session believes a
     * turn is running and nothing has been heard of it for [graceMs]. Only the
     * RUNNING case probes — ERROR already restored the send button (the composer
     * is only Stop while RUNNING), and IDLE has nothing to reconcile.
     */
    fun probeDue(status: RunStatus): Boolean =
        status == RunStatus.RUNNING && quietFor() >= graceMs

    /**
     * What the server's answer means for the tracked turn.
     *
     * [live] is the active-set answer: `"running"` / `"busy"` / `"retry"` for a
     * session the server is still working on, anything else (including `null`)
     * for one it is not.
     *
     * [liveSetTrusted] is the caller's answer to "may this map still speak for
     * the turn?" — `false` while a Stop this client confirmed stands
     * ([StoppedTurn]). The map is exactly the authority that Stop refuted (it
     * kept answering `running` a minute past an `{interrupted:true}` answer),
     * and a re-arm here is an assertion of life: it stamps the activity clock
     * and, by doing so, keeps the snapshot backstop's grace window fresh, so the
     * two carriers hold each other up and neither ever settles. An untrusted map
     * therefore settles rather than re-arms — and the record is lifted by the
     * next run the wire *starts*, which is where a genuine successor (the one a
     * `resume` wakes) announces itself.
     */
    fun onProbe(status: RunStatus, live: String?, liveSetTrusted: Boolean = true): TurnIdleAction {
        if (!probeDue(status)) return TurnIdleAction.NONE
        if (!liveSetTrusted) return TurnIdleAction.SETTLE_IDLE
        return when (live) {
            "running", "busy", "retry" -> TurnIdleAction.REARM
            else -> TurnIdleAction.SETTLE_IDLE
        }
    }
}

/** What the caller does with one [TurnIdleWatchdog] verdict. */
enum class TurnIdleAction {
    /** Leave the run status alone (no turn running, or still inside the grace). */
    NONE,

    /** The turn is alive server-side: stamp fresh activity so the next probe is another grace away. */
    REARM,

    /** The terminal frame is never coming: drop back to IDLE so the composer can send again. */
    SETTLE_IDLE,
}
