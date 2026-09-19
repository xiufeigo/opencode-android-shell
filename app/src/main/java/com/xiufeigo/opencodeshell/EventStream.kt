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
 * Server-Sent Events stream for `GET /event`.
 *
 * - Idle watchdog = the SSE client's 35s read timeout (opencode heartbeats
 *   every 10s; three silent beats mean the connection is blackholed) -> the
 *   read throws and the loop reconnects.
 * - Exponential reconnect backoff 1s..30s.
 * - [close] cancels **only this stream's call**: in-flight REST calls (e.g. a
 *   prompt POST sent just before the app backgrounds) are never touched.
 */
class EventStream(
    private val baseHttpUrl: okhttp3.HttpUrl,
    private val username: String?,
    private val password: String?,
    private val client: OkHttpClient,
    private val listener: Listener,
) {
    interface Listener {
        /** Called on the stream thread. Return value reserved for filtering. */
        fun onEvent(type: String, data: JSONObject)

        fun onState(state: State)
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
            .url(baseHttpUrl.newBuilder().addPathSegment("event").build())
            .header("Accept", "text/event-stream")
            .apply {
                if (!username.isNullOrEmpty() || !password.isNullOrEmpty()) {
                    val basic = Base64.getEncoder()
                        .encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
                    header("Authorization", "Basic $basic")
                }
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
