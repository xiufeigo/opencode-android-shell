package com.xiufeigo.opencodeshell

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Orchestrates the native chat: owns [OpenCodeClient] + [EventStream] while
 * visible, merges SSE deltas into [ChatStore], and exposes sends/aborts.
 *
 * Recovery order (per review MUST-FIX #2): connect -> established (first
 * event or HTTP 200) -> REST snapshot, merged with idempotent upserts so
 * deltas received during the window are never clobbered. The server is NOT
 * assumed to honor Last-Event-ID replay.
 */
class ChatViewModel(
    private val serverConfig: ServerConfigStore,
    private val onConfigMissing: () -> Unit,
) {
    data class Attachment(
        val mime: String,
        val filename: String,
        val dataUrl: String,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val started = AtomicBoolean(false)
    private var eventStream: EventStream? = null
    private val clientRef = AtomicReference<OpenCodeClient?>(null)
    private val sharedClient: OkHttpClient = OpenCodeClient.defaultClient()
    @Volatile
    private var lastSnapshotAt = 0L
    @Volatile
    private var lastPartEventAt = 0L

    private val _state = kotlinx.coroutines.flow.MutableStateFlow(ChatStore.ChatState())
    val state: kotlinx.coroutines.flow.StateFlow<ChatStore.ChatState> = _state

    var sessionID: String? = null
        private set

    fun observeSession(id: String) {
        sessionID = id
        _state.value = ChatStore.ChatState()
        if (started.get()) refreshSnapshot()
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        val server = serverConfig.server
        if (server == null || !server.isComplete) {
            onConfigMissing()
            started.set(false)
            return
        }
        val parsed: HttpUrl = server.url.toHttpUrlOrNull() ?: run {
            onConfigMissing()
            started.set(false)
            return
        }
        val client = OpenCodeClient.forServer(server, sharedClient) ?: run {
            onConfigMissing()
            started.set(false)
            return
        }
        clientRef.set(client)
        val stream = EventStream(
            baseHttpUrl = parsed,
            username = server.username,
            password = server.password,
            client = EventStream.sseClient(sharedClient),
            listener = object : EventStream.Listener {
                override fun onEvent(type: String, data: JSONObject) {
                    if (type.startsWith("session.")) {
                        // Any turn activity on the tracked session (tool runs,
                        // queueing) means the run is alive: bump the liveness
                        // clock so the IDLE grace check cannot misfire.
                        val tracked = ChatStore.sessionIdOf(data)
                        if (tracked == null || tracked == sessionID) {
                            lastPartEventAt = System.currentTimeMillis()
                        }
                        maybeRefreshOnSession(data)
                    }
                    val event = ChatStore.parseEvent(type, data) ?: return
                    if (event is ChatStore.Event.PartUpdated || event is ChatStore.Event.MessageUpdated) {
                        lastPartEventAt = System.currentTimeMillis()
                    }
                    scope.launch { applyEvent(event) }
                }

                override fun onState(state: EventStream.State) {
                    if (state == EventStream.State.ESTABLISHED) refreshSnapshot()
                }
            },
        )
        eventStream = stream
        stream.start()
        refreshSnapshot()
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        // Only the SSE stream is torn down. The shared OkHttpClient (also used
        // by the SSE client via newBuilder) must NEVER be shut down here:
        // OkHttp's Dispatcher executor does not rebuild after shutdown() and
        // every later enqueue would be rejected, leaving the stream dead for
        // the rest of the process. A prompt POST already in flight keeps
        // running to completion (review MUST-FIX #3 semantics).
        eventStream?.close()
        eventStream = null
        clientRef.set(null)
    }

    private fun requireClient(): OpenCodeClient? {
        val client = clientRef.get()
        if (client == null) {
            _state.value = ChatStore.reduce(
                _state.value,
                ChatStore.Event.RunStatusChanged(
                    ChatStore.RunStatus.ERROR,
                    "Not connected — open the Terminal tab once to load the server config.",
                ),
            )
        }
        return client
    }

    fun send(text: String, attachments: List<Attachment>) {
        val id = sessionID ?: return
        val client = requireClient() ?: return
        val parts = attachments.map { attachment ->
            ChatStore.UiPart(
                id = "att:${attachment.filename}:${attachment.dataUrl.hashCode()}",
                type = "file",
                mime = attachment.mime,
                filename = attachment.filename,
                url = attachment.dataUrl,
            )
        }
        scope.launch {
            _state.value = ChatStore.reduce(_state.value, ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.RUNNING))
            try {
                withContext(Dispatchers.IO) { client.promptAsync(id, text, parts) }
            } catch (e: Exception) {
                Log.w(TAG, "prompt failed: ${e.message}")
                _state.value = ChatStore.reduce(
                    _state.value,
                    ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.ERROR, e.message),
                )
            }
        }
    }

    fun abort() {
        val id = sessionID ?: return
        val client = clientRef.get() ?: return
        scope.launch {
            try {
                withContext(Dispatchers.IO) { client.abort(id) }
            } catch (e: Exception) {
                Log.w(TAG, "abort failed: ${e.message}")
            }
        }
    }

    fun searchFiles(query: String, onResult: (List<String>) -> Unit) {
        val client = clientRef.get() ?: return
        scope.launch {
            val results = try {
                withContext(Dispatchers.IO) { client.findFiles(query) }
            } catch (_: Exception) {
                emptyList()
            }
            onResult(results)
        }
    }

    fun createSession(onReady: (String) -> Unit) {
        val client = requireClient() ?: return
        scope.launch {
            try {
                val session = withContext(Dispatchers.IO) { client.createSession() }
                observeSession(session.id)
                onReady(session.id)
            } catch (e: Exception) {
                Log.w(TAG, "create session failed: ${e.message}")
                _state.value = ChatStore.reduce(
                    _state.value,
                    ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.ERROR, e.message),
                )
            }
        }
    }

    private fun refreshSnapshot() {
        val id = sessionID ?: return
        val client = clientRef.get() ?: return
        lastSnapshotAt = System.currentTimeMillis()
        scope.launch {
            try {
                val messages = withContext(Dispatchers.IO) { client.messages(id) }
                    .map { it.toUi() }
                applyEvent(ChatStore.Event.Snapshot(messages))
                // session.idle can be missed while backgrounded; after a
                // reconnect snapshot with no part traffic in the grace window,
                // drop back to IDLE so the send button is not stuck on abort.
                if (_state.value.runStatus == ChatStore.RunStatus.RUNNING) {
                    delay(IDLE_GRACE_MS)
                    if (started.get() &&
                        _state.value.runStatus == ChatStore.RunStatus.RUNNING &&
                        System.currentTimeMillis() - lastPartEventAt >= IDLE_GRACE_MS
                    ) {
                        applyEvent(ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.IDLE))
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "snapshot failed: ${e.message}")
            }
        }
    }

    /** A new assistant turn bumps the session; refresh to catch tool runs. */
    private fun maybeRefreshOnSession(data: JSONObject) {
        val id = sessionID ?: return
        val tracked = ChatStore.sessionIdOf(data) ?: return
        if (tracked != id) return
        val now = System.currentTimeMillis()
        if (now - lastSnapshotAt < SNAPSHOT_THROTTLE_MS) return
        refreshSnapshot()
    }

    private suspend fun applyEvent(event: ChatStore.Event) {
        val next = try {
            ChatStore.reduce(_state.value, event)
        } catch (_: JSONException) {
            _state.value
        }
        _state.value = next
    }

    /** Typed REST models flatten into the UI store; ids match SSE part ids. */
    private fun OpenCodeClient.Message.toUi(): ChatStore.UiMessage = ChatStore.UiMessage(
        id = id,
        role = role,
        timeCreated = timeCreated,
        parts = parts.map { part ->
            ChatStore.UiPart(
                id = part.id,
                type = part.type,
                text = part.text.orEmpty(),
                toolName = part.toolName,
                toolStatus = part.toolStatus,
                mime = part.mime,
                filename = part.filename,
                url = part.url,
            )
        },
    )

    fun clearError() {
        if (_state.value.error != null) {
            _state.value = ChatStore.reduce(
                _state.value,
                ChatStore.Event.RunStatusChanged(_state.value.runStatus, null),
            )
        }
    }

    fun destroy() {
        stop()
        scope.cancel()
    }

    companion object {
        private const val TAG = "ChatViewModel"
        private const val SNAPSHOT_THROTTLE_MS = 3_000L
        private const val IDLE_GRACE_MS = 10_000L
    }
}
