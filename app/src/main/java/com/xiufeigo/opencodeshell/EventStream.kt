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
 * Server-Sent Events stream for `GET /event` (v1) and `GET /api/event` /
 * per-session log (v2, Wave 1).
 *
 * - Idle watchdog = the SSE client's 35s read timeout (opencode heartbeats
 *   every 10s; three silent beats mean the connection is blackholed) -> the
 *   read throws and the loop reconnects.
 * - Exponential reconnect backoff 1s..30s.
 * - [close] cancels **only this stream's call**: in-flight REST calls (e.g. a
 *   prompt POST sent just before the app backgrounds) are never touched.
 *
 * Wave 1: [v2] switches the path builder to the `/api` surface
 * (`GET /api/event`), and [sessionLog] targets
 * `GET /api/experimental/session/{id}/log?after=&follow=true`. Frame parsing
 * is unchanged (the `data:`/heartbeat rules are identical on both
 * generations); the *envelope* differs and is handled by the caller's adapter
 * (`ChatStore.parseEvent` v1, `V2EventAdapter` v2).
 */
class EventStream(
    private val baseHttpUrl: okhttp3.HttpUrl,
    private val username: String?,
    private val password: String?,
    private val client: OkHttpClient,
    private val listener: Listener,
    /**
     * Workspace directory. `/event` is an **instance-scoped** stream filtered by
     * `instance.directory`; without it the server degrades to its process `cwd`,
     * silently missing events for sessions in any other workspace (data-layer
     * defect #1). Passed as `?directory=<abs path>`. Ignored when [global].
     * v2 catalog/global reads use `location[directory]` instead, but the v2
     * *event* routes take no location — this stays null on v2 streams.
     */
    private val directory: String? = null,
    /**
     * `true` → `GET /global/event` (v1) or `GET /api/event` (v2, when [v2]):
     * the server-wide stream. One connection covers sessions across all
     * project directories, so session switches never need a reconnect
     * (research/04 §2.2). v1 callers unwrap the `payload` envelope
     * (`ChatStore.unwrapGlobal`) and gate by session; v2 frames carry
     * `location.directory` + `data.sessionID` instead.
     */
    private val global: Boolean = false,
    /**
     * Wave 1 v2: `true` → build `/api/…` paths instead of v1 paths. Only
     * meaningful with [global] (global stream); per-session v2 traffic uses
     * [sessionLog].
     */
    private val v2: Boolean = false,
    /**
     * Wave 1 v2: per-session log stream — `sessionID to afterSeq`. When set
     * (and [global] is false), builds
     * `GET /api/experimental/session/{id}/log?after=&follow=true` and ignores
     * [directory]/[v2]. `afterSeq` comes from [V2SeqTracker.afterParam] — a
     * trusted watermark, never a local counter.
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
    private val lock = Any()
    private var call: Call? = null
    private var backoffMs = INITIAL_BACKOFF_MS
    private var consecutiveClientErrors = 0

    fun start() {
        if (!running.compareAndSet(false, true)) return
        backoffMs = INITIAL_BACKOFF_MS
        connectLoop()
    }

    fun close() {
        running.set(false)
        synchronized(lock) { call?.cancel() }
    }

    private fun connectLoop() {
        if (!running.get()) return
        listener.onState(State.CONNECTING)
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
        val newCall = client.newCall(request)
        synchronized(lock) {
            if (!running.get()) return
            call = newCall
        }
        newCall.enqueue(object : okhttp3.Callback {
            override fun onResponse(call: Call, response: Response) {
                if (!running.get()) {
                    response.close()
                    return
                }
                if (!response.isSuccessful) {
                    val code = response.code
                    response.close()
                    Log.w(TAG, "event stream HTTP $code")
                    listener.onHttpError(code)
                    // A 4xx is permanent (bad URL, auth) — retrying forever just
                    // burns battery. 5xx and 3xx stay retryable.
                    if (code in 400..499) {
                        consecutiveClientErrors++
                        if (consecutiveClientErrors >= MAX_CLIENT_ERROR_RETRIES) {
                            running.set(false)
                            listener.onState(State.CLOSED)
                            return
                        }
                    } else {
                        consecutiveClientErrors = 0
                    }
                    scheduleReconnect()
                    return
                }
                consecutiveClientErrors = 0
                listener.onState(State.ESTABLISHED)
                backoffMs = INITIAL_BACKOFF_MS
                try {
                    readStream(response)
                } catch (_: IOException) {
                    // Idle timeout, blackholed socket or reset -> reconnect below.
                } finally {
                    response.close()
                }
                if (running.get()) scheduleReconnect() else listener.onState(State.CLOSED)
            }

            override fun onFailure(call: Call, e: IOException) {
                if (!running.get()) {
                    listener.onState(State.CLOSED)
                    return
                }
                Log.w(TAG, "event stream failure: ${e.message}")
                scheduleReconnect()
            }
        })
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
     * URL for this stream's generation. v1 paths are byte-identical to the
     * old inline builder (`/global/event`, `/event?directory=`); v2 builds
     * `/api/event` or `/api/experimental/session/{id}/log?after=&follow=`.
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
        if (v2) {
            return baseHttpUrl.newBuilder()
                .addPathSegment("api")
                .addPathSegment("event")
                .build()
        }
        return baseHttpUrl.newBuilder()
            .apply { if (global) addPathSegment("global") }
            .addPathSegment("event")
            .apply { if (!global && !directory.isNullOrBlank()) addQueryParameter("directory", directory) }
            .build()
    }

    private fun scheduleReconnect() {
        if (!running.get()) {
            listener.onState(State.CLOSED)
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

        /** opencode heartbeats every 10s; 35s of silence = dead connection. */
        const val IDLE_READ_TIMEOUT_MS = 35_000L
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 30_000L
        private const val MAX_CLIENT_ERROR_RETRIES = 5

        fun sseClient(shared: OkHttpClient): OkHttpClient = shared.newBuilder()
            .readTimeout(IDLE_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
