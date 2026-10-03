package com.xiufeigo.opencodeshell.v2

/**
 * Wave 1 durable-seq watermark for the v2 per-session log stream
 * (`GET /api/experimental/session/{id}/log?after=&follow=true`).
 *
 * Rules (A5 §4):
 * - only durable frames carry `durable.seq` (per-aggregate, monotone ≥ 0);
 *   ephemeral frames (delta/progress/status) never touch the watermark;
 * - the stream opens with `log.synced{aggregateID, seq}` — "≤ seq is all
 *   here"; the first `?after=` a client sends must come from that trusted
 *   source (or a previously seen durable seq), never a local counter;
 * - `maxSeq` feeds the next reconnect's `?after=`; durable gaps below the
 *   synced watermark reconcile via a full `…/message` fetch (ended-frame
 *   semantics), because ephemeral deltas are unrecoverable by replay.
 */
class V2SeqTracker {
    /** Highest durable seq seen for the tracked aggregate, or null when none. */
    var maxSeq: Long? = null
        private set

    /**
     * `evt_` ids already applied (upstream: same id + type replays take-first),
     * each with its durable seq (`null` for ephemeral frames). Bounded: an id
     * at least [DEDUP_WINDOW] below [maxSeq] can never replay (`?after=` has
     * moved past it) and is dropped; seq-less ids age out by no watermark,
     * so the oldest are hard-capped away at [MAX_SEEN].
     */
    private val seenIDs = LinkedHashMap<String, Long?>()

    /** Last `log.synced` watermark seq, or null when not yet observed. */
    var syncedSeq: Long? = null
        private set

    /** True once a `log.synced` frame opened the stream. */
    val synced: Boolean get() = syncedSeq != null

    /** Feed one frame; returns true when it is new work (not a replay duplicate). */
    fun observe(id: String, seq: Long?, aggregate: Boolean = true): Boolean {
        // [aggregate] is what keeps `?after=` honest: the global `/api/event`
        // stream carries **every** session, and each session's `durable.seq` is
        // its own counter (`research/dupcap/*.sse`: two sessions at seq 15 in the
        // same second). A foreign frame's id must still be remembered — the same
        // event can arrive on this session's log and must not be applied twice —
        // but its seq must not move the watermark: anchoring this session's log
        // replay at another session's seq makes the server skip every durable
        // frame this session missed while the stream was down (`session.execution
        // .succeeded` → the turn-complete notification, `permission.asked` → the
        // ask notification and its dot), which is exactly the gap the replay is
        // for.
        if (aggregate && seq != null && (maxSeq == null || seq > maxSeq!!)) maxSeq = seq
        if (id.isEmpty()) return true
        if (seenIDs.containsKey(id)) return false
        seenIDs[id] = seq
        evictSeen()
        return true
    }

    /** Drop ids that can no longer replay, then hard-cap the rest oldest-first. */
    private fun evictSeen() {
        val cutoff = (maxSeq ?: return) - DEDUP_WINDOW
        val it = seenIDs.entries.iterator()
        while (it.hasNext()) {
            val seq = it.next().value
            if (seq != null && seq <= cutoff) it.remove()
        }
        if (seenIDs.size > MAX_SEEN) {
            val eldest = seenIDs.entries.iterator()
            while (seenIDs.size > MAX_SEEN && eldest.hasNext()) {
                eldest.next()
                eldest.remove()
            }
        }
    }

    /** Record the opening `log.synced{seq}` watermark. */
    fun onSynced(seq: Long?) {
        if (seq != null) {
            syncedSeq = seq
            if (maxSeq == null || seq > maxSeq!!) maxSeq = seq
        } else {
            syncedSeq = syncedSeq ?: -1L
        }
    }

    /** `?after=` for the (re)connect: the trusted watermark, or null to replay from zero. */
    fun afterParam(): Long? = maxSeq

    /**
     * Pure seq math for tests: given the local [maxSeq] and the fresh
     * [syncedSeq] watermark, the durable gap below the watermark that a full
     * message fetch must cover. Empty when already caught up.
     */
    fun gapBelowWatermark(localMax: Long?, watermark: Long?): LongRange? {
        if (watermark == null) return null
        val from = (localMax ?: -1L) + 1
        return if (from <= watermark) from..watermark else null
    }

    fun reset() {
        maxSeq = null
        syncedSeq = null
        seenIDs.clear()
    }

    private companion object {
        /** Durable ids this far below `maxSeq` sit past any `?after=` replay. */
        const val DEDUP_WINDOW = 1024L

        /** Hard cap on remembered ids; ephemeral (seq-less) ids hit no watermark. */
        const val MAX_SEEN = 4096
    }
}
