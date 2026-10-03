package com.xiufeigo.opencodeshell.v2

import com.xiufeigo.opencodeshell.OpenCodeClient
import kotlinx.coroutines.delay
import java.io.IOException

/**
 * Retry policy for **idempotent reads** over a live transport, plus the
 * connection-failure vocabulary the error card classifies with.
 *
 * The v32 tB report: an export read failed with
 * `Failed to connect to /127.0.0.1:4095` while `adb reverse` was up and
 * `GET /api/info` answered — the app's socket had been dropped (the reverse
 * mapping blipped, the backend restarted, the OS killed the idle socket) and
 * nothing re-tried or re-connected, so one transient blip surfaced as a
 * permanent error until the user did something else. The probe already heals a
 * *missing* transport
 * ([com.xiufeigo.opencodeshell.ChatViewModel.maybeReprobe]); this covers the
 * other half — a transport that exists but whose connection pool holds dead
 * sockets.
 *
 * Two rules keep the retry honest:
 *
 * - **only transient failures retry** ([isTransient]). A definitive HTTP answer
 *   (400/401/403/404/409/422) is the server's own verdict and repeating the
 *   identical request reproduces it; a rejected pagination cursor is called out
 *   on its own because it is *client* state ([V2Api.ApiException.isInvalidCursor]).
 *   Connection-level [IOException]s (connect refused / timed out / reset /
 *   unexpected end of stream / unknown host) are the ones a second attempt can
 *   fix.
 * - **only reads retry.** Writes (`prompt`, `session.create`, `revert`,
 *   permission replies …) are never replayed automatically: a POST that timed
 *   out may have been admitted server-side, so a blind retry can duplicate a
 *   turn. The write path instead reports the failure with the connection copy
 *   ([isConnectionFailureText] → `ChatStore.ProviderErrorKind.TRANSPORT`) and
 *   leaves the retry to the user, who can see whether the first attempt landed.
 *
 * Pure policy + one suspend runner, so both halves are JVM-testable without a
 * device.
 */
object TransportRetry {

    /**
     * Total attempts for one read (the first try plus its retries). The report
     * asked for 1–2 retries; three attempts over ~1.2 s of backoff covers a
     * reverse-mapping blip without holding a UI read hostage on a dead server.
     */
    const val READ_ATTEMPTS = 3

    /** First backoff step; later steps double it ([delayMs]). */
    const val BASE_DELAY_MS = 400L

    /**
     * Statuses worth a second identical GET: the server (or the proxy in front
     * of it) said "not now", not "no". A 500 stays out — it is as often a
     * deterministic application error as a blip — and every other 4xx is a
     * verdict about the request itself.
     */
    private val RETRYABLE_STATUS = setOf(408, 429, 502, 503, 504)

    /**
     * Exponential backoff for the retry after `attempt` (0-based): 400 ms,
     * 800 ms, 1.6 s … Capped at 2^8 so a future larger budget cannot overflow
     * the shift.
     */
    fun delayMs(attempt: Int): Long = BASE_DELAY_MS shl attempt.coerceIn(0, 8)

    /**
     * Whether `attempt` (0-based, already failed) may be followed by another
     * try: [error] must be transient and the budget must not be spent.
     */
    fun shouldRetry(attempt: Int, error: Throwable?): Boolean =
        attempt + 1 < READ_ATTEMPTS && isTransient(error)

    /**
     * True when repeating the request can plausibly change the answer — a
     * connection-level failure, or a "not now" status. See the class docs for
     * why a definitive status and a rejected cursor are excluded.
     */
    fun isTransient(error: Throwable?): Boolean = when (error) {
        null -> false
        is V2Api.ApiException -> !error.isInvalidCursor && error.code in RETRYABLE_STATUS
        is OpenCodeClient.ApiException -> error.code in RETRYABLE_STATUS
        is IOException -> true
        else -> false
    }

    /**
     * The **text** half of the same rule, for a failure that reaches the UI as
     * a message rather than a throwable (the prompt-POST path keeps only the
     * line). Matches what the transports actually produce on a dropped
     * connection: OkHttp's `Failed to connect to /127.0.0.1:4095` (also what a
     * connect timeout renders as), `Connection reset by peer`, `unexpected end
     * of stream`, `Software caused connection abort`, `Unable to resolve host …`.
     *
     * Deliberately narrow — a provider's own words ("rate limit", "not
     * available in your country", a bare "timed out") must keep their own card,
     * so only connection-level vocabulary is listed.
     *
     * v82 BUG-C: the **client-authored half** of the same family is included.
     * `ChatViewModel` raises five fixed sentences for "this client has no usable
     * link to the server" — `Not connected — open the Terminal tab once to load
     * the server config.` (the v76 failed-send line), `Not connected — the
     * server did not answer.` and the three siblings in `setGeneration`'s
     * UNKNOWN branch. They are *by construction* statements about the link, and
     * the whole recovery apparatus keys off this classifier: without them,
     * `ChatStore.Event.TransportRecovered` (and `RunStatusChanged(RUNNING)`'s
     * `dropStaleTransportRows`) classified the card the failed send had just
     * raised as `OTHER` and left it on screen **for good** — measured
     * emulator-5558 v82 (`research/v82_verify/5558/45_reg_sent.png`,
     * `50_reg_wait120.png`: the 500-byte-per-minute card was still there long
     * after REST answers were back).
     *
     * [isClientNotConnectedText] is a *leading* match rather than a substring:
     * every sentence in the family begins with `Not connected`, while a
     * provider's own message can mention the phrase mid-sentence ("upstream not
     * connected") and must keep its own card. The em dash is not part of the
     * match, so `Not connected to an OpenCode 2.x server.` (the fail-fast
     * `createSession` raises) joins without a second rule.
     */
    fun isConnectionFailureText(text: String?): Boolean {
        val line = text?.lowercase().orEmpty()
        if (line.isBlank()) return false
        return isClientNotConnectedText(line) || CONNECTION_PHRASES.any { line.contains(it) }
    }

    /**
     * Whether [text] is one of this client's own 「Not connected — …」 sentences
     * (see [isConnectionFailureText]): trimmed, case-insensitive, and required at
     * the **start** of the line.
     */
    fun isClientNotConnectedText(text: String?): Boolean =
        text?.trim()?.lowercase()?.startsWith(CLIENT_NOT_CONNECTED_PREFIX) == true

    private const val CLIENT_NOT_CONNECTED_PREFIX = "not connected"

    private val CONNECTION_PHRASES = listOf(
        "failed to connect",
        "connection refused",
        "connection reset",
        "connection aborted",
        "software caused connection abort",
        "unexpected end of stream",
        "broken pipe",
        "socket closed",
        "socket is closed",
        "network is unreachable",
        "no route to host",
        "unable to resolve host",
        "unknownhostexception",
        "econnrefused",
        "econnreset",
    )

    /**
     * One idempotent read with [READ_ATTEMPTS] attempts and [delayMs] backoff.
     *
     * [onFailure] runs for every **transient** failure — before each retry and
     * before the final rethrow — which is where the caller drops the dead
     * connections the failed attempt left in the pool, so the next attempt
     * builds a fresh socket instead of reusing the broken one.
     *
     * [delayFor] is the sleep seam (tests pass a no-op); non-transient failures
     * and coroutine cancellation are rethrown untouched, so this can never
     * swallow a `CancellationException` or retry a definitive rejection.
     */
    suspend fun <T> runRead(
        onFailure: (Throwable) -> Unit = {},
        delayFor: suspend (Long) -> Unit = { delay(it) },
        block: suspend () -> T,
    ): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: Throwable) {
                if (!isTransient(e)) throw e
                onFailure(e)
                if (!shouldRetry(attempt, e)) throw e
                delayFor(delayMs(attempt))
                attempt++
            }
        }
    }
}
