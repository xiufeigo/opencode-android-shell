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

    // Title of the tracked session; null means "no session yet" (rendered as
    // the localized "New session" label by the view).
    private val _currentTitle = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val currentTitle: kotlinx.coroutines.flow.StateFlow<String?> = _currentTitle

    // Default model from `GET /config` ("provider/modelID"), shown on the
    // composer chip like the official UI.
    private val _modelLabel = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val modelLabel: kotlinx.coroutines.flow.StateFlow<String?> = _modelLabel

    // True while no usable server config has been seen; the chat tab then shows
    // a setup hint instead of pretending to be a dead conversation.
    private val _configMissing = kotlinx.coroutines.flow.MutableStateFlow(false)
    val configMissing: kotlinx.coroutines.flow.StateFlow<Boolean> = _configMissing

    var sessionID: String? = null
        private set

    /** Workspace directory of the tracked session; drives `?directory=` on `/event`. */
    private var directory: String? = null

    fun observeSession(id: String, title: String? = null, directory: String? = null) {
        sessionID = id
        this.directory = directory
        _state.value = ChatStore.ChatState()
        _currentTitle.value = title
        if (started.get()) refreshSnapshot()
    }

    /**
     * Drops back to the pre-session state: the next send creates the session
     * server-side (existing createSession flow). Nothing is created eagerly.
     */
    fun newSession() {
        sessionID = null
        directory = null
        _state.value = ChatStore.ChatState()
        _currentTitle.value = null
    }

    /**
     * Read-only wrapper over [OpenCodeClient.listSessions] sorted by updatedAt
     * descending; also re-syncs [currentTitle] with the tracked session.
     */
    fun listSessions(onResult: (List<OpenCodeClient.Session>) -> Unit) {
        val client = clientRef.get()
        if (client == null) {
            onResult(emptyList())
            return
        }
        scope.launch {
            val sessions = try {
                withContext(Dispatchers.IO) { client.listSessions() }
                    .sortedByDescending { it.updatedAt }
            } catch (e: Exception) {
                Log.w(TAG, "list sessions failed: ${e.message}")
                emptyList()
            }
            if (sessions.isNotEmpty()) {
                _currentTitle.value = sessions.firstOrNull { it.id == sessionID }?.title
                    ?: _currentTitle.value
            }
            onResult(sessions)
        }
    }

    /**
     * M3 addition (purely additive; [start] routes through it, so its behaviour
     * is unchanged): builds the REST client + default model label **without
     * opening the SSE stream**.
     *
     * Home lists sessions through [listSessions], which needs a client, and the
     * M1 contract is explicit that sitting on Home costs no connection — calling
     * [start] there would open a stream the home page never reads. Returns
     * `false` (and raises [configMissing]) when no usable server is configured,
     * exactly like the corresponding checks in [start].
     */
    fun ensureClient(): Boolean {
        if (clientRef.get() != null) return true
        val server = serverConfig.server
        if (server == null || !server.isComplete) {
            _configMissing.value = true
            return false
        }
        val client = OpenCodeClient.forServer(server, sharedClient) ?: run {
            _configMissing.value = true
            return false
        }
        clientRef.set(client)
        refreshModelLabel()
        return true
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        val server = serverConfig.server
        if (server == null || !server.isComplete) {
            // No toast here: the view shows an inline setup hint, and the
            // WebView config sync usually lands within seconds of launch.
            _configMissing.value = true
            started.set(false)
            return
        }
        val parsed: HttpUrl = server.url.toHttpUrlOrNull() ?: run {
            _configMissing.value = true
            started.set(false)
            return
        }
        if (!ensureClient()) {
            started.set(false)
            return
        }
        _configMissing.value = false
        val stream = EventStream(
            baseHttpUrl = parsed,
            username = server.username,
            password = server.password,
            client = EventStream.sseClient(sharedClient),
            directory = directory,
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
            onConfigMissing()
            _configMissing.value = true
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

    /** Default model for the composer chip; failure leaves the chip hidden. */
    private fun refreshModelLabel() {
        val client = clientRef.get() ?: return
        scope.launch {
            try {
                val config = withContext(Dispatchers.IO) { client.config() }
                _modelLabel.value = config.optString("model", "")
                    .takeIf { it.isNotEmpty() }
                    ?.substringAfterLast('/')
            } catch (e: Exception) {
                Log.w(TAG, "config fetch failed: ${e.message}")
            }
        }
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

    /**
     * M2 addition: rename the tracked (or any) session through
     * `PATCH /session/:id` and, on success, mirror it into [currentTitle] so the
     * sticky header updates without a `listSessions()` round-trip. Read-only
     * with respect to every other method: no state machine, no stream touched.
     */
    fun renameSession(id: String, title: String, onResult: (Boolean) -> Unit = {}) {
        val client = clientRef.get()
        val trimmed = title.trim()
        if (client == null || id.isEmpty() || trimmed.isEmpty()) {
            onResult(false)
            return
        }
        scope.launch {
            val renamed = try {
                withContext(Dispatchers.IO) { client.renameSession(id, trimmed, directory) }
            } catch (e: Exception) {
                Log.w(TAG, "rename session failed: ${e.message}")
                null
            }
            if (renamed != null && id == sessionID) _currentTitle.value = renamed.title
            onResult(renamed != null)
        }
    }

    fun createSession(onReady: (String) -> Unit) {
        val client = requireClient() ?: return
        scope.launch {
            try {
                val session = withContext(Dispatchers.IO) { client.createSession() }
                observeSession(id = session.id, directory = session.directory)
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
        parts = parts.map { part -> part.toUiPart() },
        // Additive M2 display payload for the context ring (see ChatStore).
        tokens = tokens,
    )

    private fun OpenCodeClient.Part.toUiPart(): ChatStore.UiPart {
        val state = raw.optJSONObject("state")
        val output = state?.opt("output")
        return ChatStore.UiPart(
            id = id,
            type = type,
            text = text.orEmpty(),
            toolName = toolName,
            toolStatus = toolStatus,
            mime = mime,
            filename = filename,
            url = url,
            toolInput = state?.optJSONObject("input")?.toString(),
            toolOutput = when (output) {
                null, JSONObject.NULL -> null
                is String -> output.takeIf { it.isNotEmpty() }
                else -> output.toString().takeIf { it.isNotEmpty() }
            },
            toolError = state?.optString("error", "")?.takeIf { it.isNotEmpty() },
        )
    }

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
