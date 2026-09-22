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

    /** `evt_` ids already applied (upstream: same id + type replays take-first). */
    private val seenIDs = LinkedHashSet<String>()

    /** Last `log.synced` watermark seq, or null when not yet observed. */
    var syncedSeq: Long? = null
        private set

    /** True once a `log.synced` frame opened the stream. */
    val synced: Boolean get() = syncedSeq != null

    /** Feed one frame; returns true when it is new work (not a replay duplicate). */
    fun observe(id: String, seq: Long?): Boolean {
        if (seq != null && (maxSeq == null || seq > maxSeq!!)) maxSeq = seq
        if (id.isEmpty()) return true
        return seenIDs.add(id)
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
}
