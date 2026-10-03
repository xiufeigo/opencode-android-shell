package com.xiufeigo.opencodeshell

import android.util.Log
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Server-Sent Events stream for `GET /api/event` and per-session log (v2).
 *
 * - Idle watchdog = the SSE client's 35s read timeout ([IDLE_READ_TIMEOUT_MS];
 *   the server heartbeats every 15s — `server/src/handlers/event.ts:22`,
 *   measured 5 beats/62 s on 2.0.18 — so two missed beats mean the connection
 *   is blackholed) -> the read throws and the loop reconnects.
 * - Exponential reconnect backoff 1s..30s, and the loop **never stops** on a
 *   failure path: every terminal path releases the single-flight token and
 *   reschedules, and a stream the client-error breaker stopped is revivable by
 *   [reconnectNow] (a network return is new information about that verdict).
 * - [close] cancels **only this stream's call**: in-flight REST calls (e.g. a
 *   prompt POST sent just before the app backgrounds) are never touched.
 *
 * [sessionLog] targets
 * `GET /api/experimental/session/{id}/log?after=&follow=true`; otherwise the
 * global `GET /api/event` is used. Frame parsing is the `data:`/heartbeat
 * rules; the envelope is handled by the caller's `V2EventAdapter`.
 */
class EventStream(
    private val baseHttpUrl: okhttp3.HttpUrl,
    private val username: String?,
    private val password: String?,
    private val client: OkHttpClient,
    private val listener: Listener,
    /**
     * Per-session log stream — `sessionID to afterSeq`. When set, builds
     * `GET /api/experimental/session/{id}/log?after=&follow=true`.
     * `afterSeq` comes from [V2SeqTracker.afterParam] — a
     * trusted watermark, never a local counter. Null = the global
     * `GET /api/event` stream.
     */
    private val sessionLog: Pair<String, Long?>? = null,
) {
    interface Listener {
        /** Called on the stream thread. Return value reserved for filtering. */
        fun onEvent(type: String, data: JSONObject)

        fun onState(state: State)

        /** Non-successful HTTP response on connect, before retry bookkeeping. */
        fun onHttpError(code: Int) {}
    }

    enum class State { CONNECTING, ESTABLISHED, CLOSED }

    private val running = AtomicBoolean(false)

    /**
     * The client-error breaker stopped the loop — **not** the owner's [close].
     * A network return is new information about the very thing the breaker
     * judged (it only ever saw "the host answered 4xx"), so [reconnectNow] may
     * revive it; [close] is final, because the owner is about to drop this
     * stream and build another one.
     */
    private val gaveUp = AtomicBoolean(false)
    private val lock = Any()

    /**
     * The one in-flight dial, or `null` while the loop is parked.
     *
     * This is the **single-flight token**: [connectLoop] claims it and every
     * terminal path releases it ([finishAttempt]), so `call == null` means
     * exactly "nothing is dialing right now" — which is what lets
     * [reconnectNow] tell a live dial from a parked backoff chain, and what
     * makes a chain that stopped with nothing behind it dialable again. It is
     * read and written under [lock] only.
     */
    private var call: Call? = null
    @Volatile
    private var backoffMs = INITIAL_BACKOFF_MS
    private var consecutiveClientErrors = 0

    fun start() {
        if (!running.compareAndSet(false, true)) return
        gaveUp.set(false)
        backoffMs = INITIAL_BACKOFF_MS
        consecutiveClientErrors = 0
        connectLoop()
    }

    fun close() {
        running.set(false)
        synchronized(lock) {
            val live = call
            call = null
            try {
                live?.cancel()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * The OS reports the network is back: drop the backoff and re-dial now
     * instead of waiting out the idle watchdog ([IDLE_READ_TIMEOUT_MS] on a
     * half-open established socket) plus the grown backoff (up to
     * [MAX_BACKOFF_MS]).
     *
     * **Every state the loop can be in is covered, because a poke that does
     * nothing is a user who waits 40 s or force-stops** (emulator-5558 v81
     * BUG-01: airplane mode off, the streams silent, the session on its error
     * card):
     *
     * - **a dial is in flight** ([call] non-null) — cancel it. That drives
     *   exactly one continuation (`onFailure` / the read error →
     *   [scheduleReconnect] with the reset backoff), so the re-dial is the
     *   loop's own, about a second out, and the single-flight token stays with
     *   the attempt that owns it.
     * - **the loop is parked on a sleeper** (no call: the backoff chain is
     *   between attempts) — dial directly. The parked sleeper keeps counting
     *   down its old delay and fires later; its [connectLoop] then finds the
     *   token taken and collapses, so the two can never double-dial.
     * - **the chain stopped with nothing behind it** — the same branch: there
     *   is no claim to honour, so the poke *is* the dial. This is the state
     *   the old `connectAttempt` gate could not heal: a claim that outlived its
     *   attempt (an exception between the claim and the call assignment) left
     *   the loop with nothing dialing and the poke cancelling a call that had
     *   already finished. The claim and the call are now one object under
     *   [lock], and [connectLoop] releases it on every path, so the state is
     *   unreachable *and* the poke no longer depends on reaching it.
     * - **the breaker stopped the stream** ([gaveUp]: [MAX_CLIENT_ERROR_RETRIES]
     *   consecutive 4xx) — revive it, with a fresh error budget. `running` false
     *   because the *owner* closed the stream is left alone: that stream is on
     *   its way out of the process.
     *
     * Later failures back off exponentially to the same ceiling, so a dead
     * server is never hammered.
     */
    fun reconnectNow() {
        if (!running.get()) {
            if (!gaveUp.compareAndSet(true, false)) return
            consecutiveClientErrors = 0
            backoffMs = INITIAL_BACKOFF_MS
            running.set(true)
            connectLoop()
            return
        }
        // The reset also covers the *next* schedule: a failure after this dial
        // must not inherit the 30 s ceiling the outage built.
        backoffMs = INITIAL_BACKOFF_MS
        val live = synchronized(lock) {
            val current = call
            call = null
            current
        }
        if (live != null) {
            try {
                live.cancel()
            } catch (_: Exception) {
            }
            return
        }
        connectLoop()
    }

    /**
     * One dial. The claim, the request and the call assignment are a single
     * critical section: [call] is the single-flight token, so a dial can never
     * be claimed without its call being observable, and overlapping pokes
     * collapse into one dial by construction. Every exit path releases the
     * token — including a throw out of the request build or `enqueue`, which
     * used to leave the loop claimed forever with nothing dialing (the
     * "backoff chain stopped" state [reconnectNow] has to heal).
     */
    private fun connectLoop() {
        if (!running.get()) return
        val dial = synchronized(lock) {
            if (!running.get() || call != null) return
            val request = Request.Builder()
                .url(streamUrl())
                .header("Accept", "text/event-stream")
                .apply {
                    // Same rule as `OpenCodeClient.basicAuthorization()`: a password
                    // is what makes the credential meaningful. The old check also
                    // fired on a username-only config and interpolated a null
                    // password, sending `Basic base64("user:null")` — a real path,
                    // because the server form keeps an explicitly typed username
                    // (`ServerConfigStore.fromHttp`).
                    basicAuthorizationHeader(username, password)?.let { header("Authorization", it) }
                }
                .build()
            client.newCall(request).also { call = it }
        }
        reportState(State.CONNECTING)
        try {
            dial.enqueue(object : okhttp3.Callback {
                override fun onResponse(call: Call, response: Response) {
                    try {
                        if (!running.get()) {
                            response.close()
                            return
                        }
                        if (!response.isSuccessful) {
                            val code = response.code
                            response.close()
                            Log.w(TAG, "event stream HTTP $code")
                            reportHttpError(code)
                            // A 4xx is permanent (bad URL, auth) — retrying forever just
                            // burns battery. 5xx and 3xx stay retryable.
                            if (code in 400..499) {
                                consecutiveClientErrors++
                                if (consecutiveClientErrors >= MAX_CLIENT_ERROR_RETRIES) {
                                    // Stopped, but revivable: a network return is
                                    // new information about the verdict (see
                                    // [reconnectNow] and [gaveUp]).
                                    gaveUp.set(true)
                                    running.set(false)
                                    reportState(State.CLOSED)
                                    return
                                }
                            } else {
                                consecutiveClientErrors = 0
                            }
                            scheduleReconnect()
                            return
                        }
                        consecutiveClientErrors = 0
                        reportState(State.ESTABLISHED)
                        backoffMs = INITIAL_BACKOFF_MS
                        try {
                            readStream(response)
                        } catch (_: IOException) {
                            // Idle timeout, blackholed socket or reset -> reconnect below.
                        } catch (t: Exception) {
                            // A poison frame (or a listener bug on the dispatch
                            // path) ends this attempt, never the loop: the
                            // reconnect below still runs.
                            Log.w(TAG, "event stream dispatch failure, reconnecting", t)
                        } finally {
                            response.close()
                        }
                        if (running.get()) scheduleReconnect() else reportState(State.CLOSED)
                    } finally {
                        finishAttempt(call)
                    }
                }

                override fun onFailure(call: Call, e: IOException) {
                    try {
                        if (!running.get()) {
                            reportState(State.CLOSED)
                            return
                        }
                        Log.w(TAG, "event stream failure: ${e.message}")
                        scheduleReconnect()
                    } finally {
                        finishAttempt(call)
                    }
                }
            })
        } catch (t: Exception) {
            // `enqueue` itself threw (shut-down dispatcher), or the request could
            // not be built: the attempt never started, so release it and park on
            // the backoff like any failure.
            finishAttempt(dial)
            Log.w(TAG, "event stream enqueue failure: ${t.message}")
            scheduleReconnect()
        }
    }

    /** Releases the single-flight token if [attempt] still owns it. */
    private fun finishAttempt(attempt: Call) {
        synchronized(lock) {
            if (call === attempt) call = null
        }
    }

    /** Blocking SSE parse on the call's IO thread; returns on stream end. */
    private fun readStream(response: Response) {
        val source = response.body?.source() ?: return
        var eventType: String? = null
        val data = StringBuilder()
        while (true) {
            val line = source.readUtf8Line() ?: break
            when {
                line.startsWith("event:") -> eventType = line.removePrefix("event:").trim()
                line.startsWith("data:") -> {
                    // SSE strips exactly one leading space, not all whitespace.
                    data.appendLine(line.removePrefix("data:").removePrefix(" "))
                }
                line.isEmpty() -> {
                    val payload = data.toString().trim()
                    if (payload.isNotEmpty()) {
                        val parsed = try {
                            JSONObject(payload)
                        } catch (_: JSONException) {
                            null
                        }
                        if (parsed != null) {
                            val type = parsed.optString("type", "")
                                .ifEmpty { eventType.orEmpty() }
                                .ifEmpty { "unknown" }
                            listener.onEvent(type, parsed)
                        }
                    }
                    eventType = null
                    data.setLength(0)
                }
                // SSE comment lines (`:heartbeat`) fall through as keepalives.
            }
        }
    }

    /**
     * URL for this stream: `/api/experimental/session/{id}/log?after=&follow=`
     * or the global `/api/event`.
     */
    private fun streamUrl(): okhttp3.HttpUrl {
        sessionLog?.let { (sessionID, after) ->
            return baseHttpUrl.newBuilder()
                .addPathSegment("api")
                .addPathSegment("experimental")
                .addPathSegment("session")
                .addPathSegment(sessionID)
                .addPathSegment("log")
                .apply { if (after != null) addQueryParameter("after", after.toString()) }
                .addQueryParameter("follow", "true")
                .build()
        }
        return baseHttpUrl.newBuilder()
            .addPathSegment("api")
            .addPathSegment("event")
            .build()
    }

    /** A listener call that must never break the reconnect loop. */
    private fun reportState(state: State) {
        try {
            listener.onState(state)
        } catch (t: Exception) {
            Log.w(TAG, "event stream listener failure", t)
        }
    }

    /** Same guard for the HTTP-error hook (it can launch client work). */
    private fun reportHttpError(code: Int) {
        try {
            listener.onHttpError(code)
        } catch (t: Exception) {
            Log.w(TAG, "event stream listener failure", t)
        }
    }

    private fun scheduleReconnect() {
        if (!running.get()) {
            reportState(State.CLOSED)
            return
        }
        val delay = backoffMs
        backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        Thread({
            try {
                Thread.sleep(delay)
            } catch (_: InterruptedException) {
            }
            connectLoop()
        }, "oc-event-reconnect").apply { isDaemon = true }.start()
    }

    companion object {
        private const val TAG = "EventStream"

        /**
         * opencode heartbeats every 10s; 35s of silence = dead connection.
         *
         * Measured on 2.0.18/4095 (`handlers/event.ts:22`: `Stream.tick("15
         * seconds")` → `: heartbeat\n\n`): a 62 s `curl -N /api/event` got 5
         * comment lines, ~12 s apart. 35 s is more than two missed beats.
         */
        const val IDLE_READ_TIMEOUT_MS = 35_000L
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 30_000L
        private const val MAX_CLIENT_ERROR_RETRIES = 5

        /**
         * Client for the SSE routes: the shared REST client's settings, minus the
         * two things that make a stream a liability for everything else.
         *
         * **Its own dispatcher and connection pool.** `newBuilder()` copies the
         * shared `Dispatcher` *by reference*, and OkHttp counts an async call as
         * running until its `onResponse` returns — which for a stream is
         * "never". Two streams therefore held two of the shared
         * `maxRequestsPerHost = 5` slots for the life of the process, and every
         * REST read (the snapshot, permissions, forms, inbox, the list reads)
         * competed for the remaining three. OkHttp applies its timeouts only to
         * calls that are *executing*, so once the slots were gone every later
         * read queued with no socket, no timeout and no error — the measured
         * v74 shape: Stop, then `snapshot failed: timeout` / `list sessions
         * failed: timeout` / `project list failed: timeout` with the emulator
         * side holding no TCP connection to the server at all, healed only by a
         * force-stop. A stream that cannot touch the REST dispatcher cannot do
         * that, and `evictAll()` on the REST pool (every reconnect heal) can no
         * longer race a stream's socket either.
         *
         * **A finite idle read.** `readTimeout(0)` made the class's own idle
         * watchdog ([IDLE_READ_TIMEOUT_MS], the first line of this file's
         * contract) dead code: a blackholed stream — the server wedged with its
         * sockets still open, the exact state the backend was in twice during
         * the v74 round — blocks `readUtf8Line()` forever, so the read never
         * throws, [scheduleReconnect] never runs, and `onState(ESTABLISHED)`
         * (which is what re-runs the snapshot) never fires again. With the
         * timeout restored, two missed heartbeats end the stream and the
         * reconnect loop does its job.
         */
        fun sseClient(shared: OkHttpClient): OkHttpClient = shared.newBuilder()
            .dispatcher(okhttp3.Dispatcher())
            .connectionPool(okhttp3.ConnectionPool())
            .readTimeout(IDLE_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
