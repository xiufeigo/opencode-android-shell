package com.xiufeigo.opencodeshell.ui.session.context

import com.xiufeigo.opencodeshell.ChatStore

/**
 * When the context tab's **backing read** must run again — the pure bookkeeping
 * behind `SessionScreen`'s payload effect.
 *
 * The tab renders two halves with two very different costs:
 *
 * - the `ctx()` half (provider / model / limit / token buckets / last activity)
 *   is derived from `ChatViewModel.activeContextRows`, the **live** read the
 *   header ring's numerator comes from, so it carries no reload key of its own —
 *   it moves with the ring on the ViewModel's snapshot cadence, plus the O(1)
 *   re-read `SessionScreen` issues at every settle ([settle] below);
 * - the frame half (counts, raw message list, system prompt, export payload)
 *   comes from the paged export drain (`ChatViewModel.fetchExportFrameResult` →
 *   `GET /api/session/:id` + `GET /api/session/:id/message`, up to
 *   `V2Api.messagesRawPage`'s page cap). That read is too expensive to repeat per
 *   event, so it runs once per [Key].
 *
 * [Key] is what makes "once" mean something specific. The v17-23 device finding
 * was a plain boolean latch (`contextLoaded`): once the tab had loaded, *nothing*
 * re-read the frame — not a new turn, not the panel's 「刷新」 tap, which evicted
 * the review location and re-probed the diff but left the context payloads
 * untouched. A key turns every one of those into a new identity, and the latch
 * into "loaded *for this key*":
 *
 * - [session] — a different session must never show the previous one's frame;
 * - [serverRevision] — a re-pointed server invalidates every payload;
 * - [reload] — bumped by the panel's 「刷新」 (and by the no-git CTA's own
 *   evict-and-re-read tail), i.e. the explicit "re-read it now" gesture;
 * - [settle] — one bump per **settled run** ([settleCount]), i.e. the turn
 *   boundary. The v19 device finding is why this one is not left to the user:
 *   with the panel open, a new turn's counts — and the token rows beside them —
 *   kept the previous turn's numbers until the panel was closed and re-opened.
 *   The frame half never re-read between 「刷新」 taps, and the live half
 *   (`activeContextRows`, read by `ChatViewModel` itself) rides the ViewModel's
 *   own snapshot, which is throttled and drops exactly the fast turn/finish
 *   cycle that settles a turn.
 *
 * The hidden state is not a key: [needsLoad] refuses to read for a panel nobody
 * is looking at, and `SessionScreen` drops the loaded key when the panel closes,
 * so re-opening it reads again (a session that advanced while the tab was closed
 * must not come back as the previous visit's numbers).
 *
 * The transcript **length** is still not a key: the raw list comes from a paged
 * export drain, so re-draining it per streamed part would cost O(pages) requests
 * per streamed token. [settle] is the compromise the device report forced — the
 * drain runs at most once per settled turn, and only while the panel is visible
 * ([needsLoad]); the live token rows cost one O(1) `refreshActiveContext()` on
 * the same edge, so the panel tracks the ring through the turn that just ended
 * instead of waiting for a close/re-open.
 */
object SessionContextLoad {

    /**
     * Identity of one frame read: everything that, when it changes, makes the
     * frame in hand the wrong one.
     */
    data class Key(
        /** `Session.Info.id` the frame belongs to; `null` before one is known. */
        val session: String?,
        /** The screen's server revision — a re-pointed server re-reads. */
        val serverRevision: Int,
        /** 「刷新」 counter: every bump forces a re-read (see the class doc). */
        val reload: Int,
        /**
         * Turn-settle counter ([settleCount]): one bump per run that ended while
         * this screen was up. The v19 finding's half of the identity — see the
         * class doc. Defaults to `0` so the pre-settle call sites (and their
         * tests) keep the same key.
         */
        val settle: Int = 0,
    )

    /**
     * Whether the frame must be read now: only for a visible panel, and only when
     * the loaded frame is not already the one [key] describes ([loaded] = `null`
     * for "nothing loaded yet", which includes every failed read — a failure must
     * not latch the tab into showing the empty projection forever).
     */
    fun needsLoad(loaded: Key?, visible: Boolean, key: Key): Boolean = visible && loaded != key

    /**
     * The settle counter after observing [status], with [previous] as the status
     * observed before it. It bumps on exactly one edge — a run **ending**:
     *
     * - `RUNNING → IDLE` — the turn settled. Its counts and its usage are the
     *   ones the panel must now show;
     * - `ERROR → IDLE` — a failed run settles too, and its rows are as stale as
     *   a clean turn's (the assistant row that failed is still a row).
     *
     * Everything else returns [count] unchanged, and that is the point of having
     * the rule at all:
     *
     * - `null → IDLE` — the first observation after the screen opened. The read
     *   that opening already issues is the one this key describes; bumping here
     *   would drain the frame a second time for nothing;
     * - `IDLE → IDLE` — a quiet session's every snapshot, and every
     *   recomposition of an idle screen;
     * - `* → RUNNING` and `RUNNING → RUNNING` — the streaming half of a turn. A
     *   part event per streamed token moves the transcript but must never move
     *   the key: that is the O(pages)-per-token drain the class doc rules out.
     */
    fun settleCount(previous: ChatStore.RunStatus?, status: ChatStore.RunStatus, count: Int): Int =
        if (status == ChatStore.RunStatus.IDLE && previous != null && previous != ChatStore.RunStatus.IDLE) {
            count + 1
        } else {
            count
        }
}
