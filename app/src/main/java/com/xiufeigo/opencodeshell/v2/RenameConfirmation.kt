package com.xiufeigo.opencodeshell.v2

/**
 * What a rename attempt ended with, at the transport seam — see
 * [SessionRenameOutcome] for the measured device case this split comes from.
 *
 * The previous shape was `String?`: one `null` for 「the server refused the
 * write by its own code」, 「the connection never answered」, 「the re-read did not
 * confirm」 and 「the session is gone」, so the only thing the caller could do was
 * print one sentence that was wrong for three of the four.
 */
sealed interface RenameResult {
    /** The server holds [title] — the write is confirmed (exact match only). */
    data class Renamed(val title: String) : RenameResult

    /** The server no longer has the session (`404` on either half of the rename). */
    data object Gone : RenameResult

    /**
     * The server answered, and the answer was not an acceptance: [status] is its
     * own code (`500` for the measured broken-location session). Retryable, but
     * not by "checking the connection" — the server itself refused.
     */
    data class Refused(val status: Int) : RenameResult

    /**
     * The write was accepted, but the title never came back as the requested one
     * within the bounded window — the deliberate strict contract
     * ([SessionTransport.renameSession]): a title the user did not type is never
     * mirrored onto a row.
     */
    data object Unconfirmed : RenameResult

    /** No HTTP answer at all (socket/timeout): what the connection copy is for. */
    data class Unreachable(val reason: String?) : RenameResult
}

/**
 * The rename's confirmation policy: **exact match, repeated within a bounded
 * window**. Both halves are deliberate:
 *
 * - the title has to come back **exactly** as requested — never a foreign one —
 *   so the strict re-read the shell has always had is preserved at full strength;
 * - the read is made up to [ATTEMPTS] times. The `PATCH` answers `204` from the
 *   write path, so a single immediate re-read can land before the row is relayed
 *   (the other candidate root cause the v81 report named); retrying can only turn
 *   "not visible yet" into a confirmed success, and can never accept a title the
 *   server does not hold.
 *
 * [RETRY_DELAY_MS] is the pause between attempts; `renameSession` blocks between
 * them, and every caller runs it on `Dispatchers.IO`.
 */
object RenameConfirmation {

    /** Re-reads one rename may make, the first one included. */
    const val ATTEMPTS = 3

    /** Pause between two re-reads, in milliseconds. */
    const val RETRY_DELAY_MS = 150L

    /** The one acceptance rule: the server holds exactly what was requested. */
    fun confirmed(requested: String, readTitle: String?): Boolean = readTitle == requested

    /**
     * Whether another re-read is worth making after [attempt] reads have not
     * confirmed the title. A session the server still has is the only case that
     * reaches this — a `404` ends the rename as [RenameResult.Gone] instead.
     */
    fun shouldRetry(attempt: Int, maxAttempts: Int = ATTEMPTS): Boolean = attempt < maxAttempts

    /**
     * A request that produced an answer instead of a success: `404` is the
     * session being gone, a positive code is the server refusing the write by
     * its own number, and a non-positive one is not an HTTP status at all —
     * [V2Api.JSON_BODY_CODE] is "the body could not be parsed" (a truncated
     * reply, a proxy page), which belongs with the connection copy rather than
     * with a server refusal the user could act on.
     */
    fun fromStatus(status: Int): RenameResult = when {
        status == 404 -> RenameResult.Gone
        status > 0 -> RenameResult.Refused(status)
        else -> RenameResult.Unreachable("unreadable response body")
    }
}
