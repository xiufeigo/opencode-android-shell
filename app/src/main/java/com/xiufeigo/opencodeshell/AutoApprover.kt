package com.xiufeigo.opencodeshell

/**
 * Auto-approve decision core for `SettingsStore.autoAcceptPermissions`
 * (upstream `_upstream/packages/app/src/session/requests/auto-approve.ts`).
 *
 * Semantics mirrored from upstream, adapted to a single-directory client:
 * - `permission.asked` arriving while the setting is on is replied `"once"`
 *   immediately ([tryMark] returns true exactly when a reply should fire).
 * - A connect/refresh sweep re-offers every still-pending request — but the
 *   per-request attempt counter makes repeated sweeps idempotent: a request
 *   whose reply already fired is not replied again.
 * - Retries after a failed reply are bounded: [maxAttempts] per request
 *   (upstream `retryLimit = 2` retries ⇒ 3 total attempts).
 * - The counter is cleared when the request leaves the pending list
 *   ([forget]), so a genuinely new ask with a recycled id starts fresh.
 * - Setting off ⇒ [tryMark] never fires; nothing is touched.
 *
 * Pure JVM (no Android, no coroutines) so the trigger/gating/cap contract is
 * unit-testable; [ChatViewModel] owns the actual POST + delayed retry.
 */
class AutoApprover(
    private val enabled: () -> Boolean,
    private val maxAttempts: Int = MAX_ATTEMPTS,
) {
    private val attempts = HashMap<String, Int>()

    /**
     * Whether an auto reply should fire for [requestID] right now. Records
     * the attempt on success, so callers never need their own bookkeeping.
     */
    fun tryMark(requestID: String): Boolean {
        if (!enabled()) return false
        if (requestID.isEmpty()) return false
        val used = attempts[requestID] ?: 0
        if (used >= maxAttempts) return false
        attempts[requestID] = used + 1
        return true
    }

    /** The request left the pending list (replied/rejected/merged away). */
    fun forget(requestID: String) {
        attempts.remove(requestID)
    }

    /** Attempts recorded so far (test introspection + logging). */
    fun attemptsOf(requestID: String): Int = attempts[requestID] ?: 0

    companion object {
        /** Upstream `retryLimit = 2` ⇒ initial try + 2 retries. */
        const val MAX_ATTEMPTS = 3
    }
}
