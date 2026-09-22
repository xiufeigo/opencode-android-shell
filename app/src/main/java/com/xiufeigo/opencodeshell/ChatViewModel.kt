package com.xiufeigo.opencodeshell

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.xiufeigo.opencodeshell.notify.AppNotification
import com.xiufeigo.opencodeshell.notify.AppNotificationKind
import com.xiufeigo.opencodeshell.notify.AppNotifier
import com.xiufeigo.opencodeshell.v2.ServerGeneration
import com.xiufeigo.opencodeshell.v2.SessionTransport
import com.xiufeigo.opencodeshell.v2.V1Transport
import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.V2Capability
import com.xiufeigo.opencodeshell.v2.V2EventAdapter
import com.xiufeigo.opencodeshell.v2.V2SeqTracker
import com.xiufeigo.opencodeshell.v2.V2Transport
import com.xiufeigo.opencodeshell.ui.session.actions.ForkMessage
import com.xiufeigo.opencodeshell.ui.session.actions.RevertStep
import com.xiufeigo.opencodeshell.ui.session.actions.SessionActionsModel
import com.xiufeigo.opencodeshell.ui.session.composer.ComposerDrafts
import com.xiufeigo.opencodeshell.ui.session.composer.ComposerHistory
import com.xiufeigo.opencodeshell.ui.session.composer.ComposerQueue
import com.xiufeigo.opencodeshell.ui.session.composer.ComposerSubmission
import com.xiufeigo.opencodeshell.ui.session.composer.ModelPickerItems
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID
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
    private val settingsStore: SettingsStore? = null,
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
    private var sessionLogStream: EventStream? = null
    private val clientRef = AtomicReference<OpenCodeClient?>(null)
    private val transportRef = AtomicReference<SessionTransport?>(null)
    private val sharedClient: OkHttpClient = OpenCodeClient.defaultClient()
    @Volatile
    private var lastSnapshotAt = 0L
    @Volatile
    private var lastPartEventAt = 0L
    /** Per-session durable watermark for the v2 log stream (`?after=` replay). */
    private val seqTracker = V2SeqTracker()
    /** Generation chosen for the current connection (probe result or cache). */
    @Volatile
    private var activeGeneration: ServerGeneration = ServerGeneration.V1
    /** True while a generation probe is in flight (dedupes concurrent connects). */
    private val probing = AtomicBoolean(false)

    /**
     * The **raw** store: everything the server reported, including the messages
     * a revert currently hides (the server keeps returning those rows — verified
     * live on 1.18.31). Every rule that has to see the full history — the 撤销 /
     * 重做 boundary (`SessionActionsModel.undoStep` / `redoStep`) and the fork
     * picker — reads this one through `_state.value.messages`.
     *
     * The renderer reads [state] instead, which is this store minus the reverted
     * tail.
     */
    private val _state = kotlinx.coroutines.flow.MutableStateFlow(ChatStore.ChatState())

    // Title of the tracked session; null means "no session yet" (rendered as
    // the localized "New session" label by the view).
    private val _currentTitle = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val currentTitle: kotlinx.coroutines.flow.StateFlow<String?> = _currentTitle

    // Effective model's display label (catalog name, else the raw modelID),
    // shown on the composer chip like the official UI. See syncModelLabel().
    private val _modelLabel = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val modelLabel: kotlinx.coroutines.flow.StateFlow<String?> = _modelLabel

    // True while no usable server config has been seen; the chat tab then shows
    // a setup hint instead of pretending to be a dead conversation.
    private val _configMissing = kotlinx.coroutines.flow.MutableStateFlow(false)
    val configMissing: kotlinx.coroutines.flow.StateFlow<Boolean> = _configMissing

    // ---- model picker ------------------------------------------------------
    // The provider catalog (`GET /provider`) plus the effective model. Upstream
    // shows the model *name* on the chip (`composer.tsx:39`
    // `modelName= selection.current()?.name`), so `_modelLabel` resolves
    // through the catalog once it has landed.

    private val _availableProviders =
        kotlinx.coroutines.flow.MutableStateFlow<OpenCodeClient.Providers?>(null)
    val availableProviders: kotlinx.coroutines.flow.StateFlow<OpenCodeClient.Providers?> =
        _availableProviders

    /** Effective model: the picker's override when set, else `config.model`. */
    private val _selectedModel =
        kotlinx.coroutines.flow.MutableStateFlow<Pair<String, String>?>(null)
    val selectedModel: kotlinx.coroutines.flow.StateFlow<Pair<String, String>?> = _selectedModel

    /** True while the user pinned a model instead of following the server. */
    private val _modelOverrideActive = kotlinx.coroutines.flow.MutableStateFlow(false)
    val modelOverrideActive: kotlinx.coroutines.flow.StateFlow<Boolean> = _modelOverrideActive

    /** Explicit picker choice; null = follow the server default. Persisted. */
    @Volatile
    private var modelOverride: Pair<String, String>? = null

    /** `GET /config`'s `model`, parsed by [OpenCodeClient.parseModelRef]. */
    @Volatile
    private var serverDefaultModel: Pair<String, String>? = null

    // ---- agent / variant / command catalogs (W5-C) ---------------------------
    //
    // Three read-only catalogs the composer consumes: `GET /agent` (agent chip),
    // `GET /command` (slash menu) and `GET /skill` (`@` category). They are
    // fetched alongside the provider catalog — same "one silent retry, never
    // fails the screen" policy — and are purely additive: nothing here changes an
    // existing send path unless the user actually pins a selection.

    private val _availableAgents =
        kotlinx.coroutines.flow.MutableStateFlow<List<OpenCodeClient.Agent>>(emptyList())

    /** `GET /agent`, server order; the chip filters via [OpenCodeClient.Agent.selectable]. */
    val availableAgents: kotlinx.coroutines.flow.StateFlow<List<OpenCodeClient.Agent>> =
        _availableAgents

    private val _availableCommands =
        kotlinx.coroutines.flow.MutableStateFlow<List<OpenCodeClient.CommandInfo>>(emptyList())

    /** `GET /command` — the composer's slash-command catalog. */
    val availableCommands: kotlinx.coroutines.flow.StateFlow<List<OpenCodeClient.CommandInfo>> =
        _availableCommands

    private val _availableSkills =
        kotlinx.coroutines.flow.MutableStateFlow<List<OpenCodeClient.SkillInfo>>(emptyList())

    /** `GET /skill` — the `@`-mention skill category. */
    val availableSkills: kotlinx.coroutines.flow.StateFlow<List<OpenCodeClient.SkillInfo>> =
        _availableSkills

    /**
     * The agent the user pinned on the composer chip, or null to follow the
     * server's own default (`primary` agents only, see
     * [OpenCodeClient.Agent.defaultName]).
     */
    private val _selectedAgent = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val selectedAgent: kotlinx.coroutines.flow.StateFlow<String?> = _selectedAgent

    /**
     * The model variant (reasoning effort) the user pinned in the model picker,
     * or null to follow the server/agent default
     * (`providers/models/variant.ts:31-35`).
     */
    private val _selectedVariant = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val selectedVariant: kotlinx.coroutines.flow.StateFlow<String?> = _selectedVariant

    // ---- server-level invalidation signals (W5-E2) --------------------------
    //
    // `ChatStore.Event.ServerSignal` (catalog changes, project/session-list
    // changes, branch moves, file writes, another client's terminals) carries no
    // chat state, so it is published here as a monotonic revision instead. A
    // consumer keys its read on the flow (`LaunchedEffect(revision)` /
    // `collectAsStateWithLifecycle`) and re-reads; nothing about *how* it re-reads
    // belongs in this class. The UI side of that wiring is deliberately left to
    // the shell owner — see `research/wave5_events.md` §4.
    //
    // Note for consumers: the stream only runs while a session screen holds it
    // (`start`/`stop`), so a revision that arrives meanwhile is still visible
    // afterwards — the value is sticky, which is exactly what a "list went stale
    // while I was away" fetch key needs.

    private val _projectsRevision = MutableStateFlow(0)

    /** Bumped on `project.updated`; `GET /project` is stale (Home projects column). */
    val projectsRevision: StateFlow<Int> = _projectsRevision.asStateFlow()

    private val _sessionsRevision = MutableStateFlow(0)

    /** Bumped on `session.created` / `session.deleted`; the session list is stale. */
    val sessionsRevision: StateFlow<Int> = _sessionsRevision.asStateFlow()

    private val _vcsRevision = MutableStateFlow(0)

    /** Bumped on `vcs.branch.updated`; `GET /vcs` is stale (branch chip). */
    val vcsRevision: StateFlow<Int> = _vcsRevision.asStateFlow()

    private val _filesRevision = MutableStateFlow(0)

    /** Bumped on `file.edited`; the review panel's file view is invalid. */
    val filesRevision: StateFlow<Int> = _filesRevision.asStateFlow()

    private val _ptyRevision = MutableStateFlow(0)

    /** Bumped on `pty.*`; another client created / changed / closed a terminal. */
    val ptyRevision: StateFlow<Int> = _ptyRevision.asStateFlow()

    /** Last catalog re-read; a burst of `provider.updated` must not hammer `GET /provider`. */
    @Volatile
    private var lastCatalogRefreshAt = 0L

    // ---- in-app notifications (W5-E5) ---------------------------------------

    /**
     * Feed of E5 notifications, gated by `SettingsStore.notifyAgent` /
     * `notifyPermissions` / `notifyErrors` — the three settings keys that had no
     * consumer before this wave. Presentation is the UI layer's job; this class
     * only decides *what* happened (kind / session / detail) and de-bounces it.
     */
    private val appNotifier = AppNotifier(enabled = ::notificationEnabled)

    /** Newest-last feed of raised notifications; see [AppNotifier]. */
    val notifications: StateFlow<List<AppNotification>> = appNotifier.notifications

    /** Drops one entry of [notifications]; `true` when it was present. */
    fun dismissNotification(id: String): Boolean = appNotifier.dismiss(id)

    /** Empties [notifications]. */
    fun clearNotifications() = appNotifier.clear()

    /**
     * `viewed` bookkeeping for the banner: the user is now on [sessionID], so
     * its entries stop being "new". Returns `true` when something changed.
     */
    fun markNotificationsViewed(sessionID: String): Boolean = appNotifier.markViewed(sessionID)

    /**
     * The three settings are the gate (`SettingsStore.Settings.notifyAgent` /
     * `notifyPermissions` / `notifyErrors`). With no store at all (previews,
     * legacy callers) the store's documented defaults apply, i.e. all three on.
     */
    private fun notificationEnabled(kind: AppNotificationKind): Boolean {
        val settings = settingsStore?.state?.value ?: return true
        return when (kind) {
            AppNotificationKind.AGENT -> settings.notifyAgent
            AppNotificationKind.PERMISSION, AppNotificationKind.QUESTION -> settings.notifyPermissions
            AppNotificationKind.ERROR -> settings.notifyErrors
        }
    }

    init {
        // Restored before the first config fetch, so a returning user never
        // flashes the server default on the chip.
        modelOverride = settingsStore?.selectedModelOverride?.let(OpenCodeClient::parseModelRef)
        _selectedModel.value = modelOverride
        _modelOverrideActive.value = modelOverride != null
    }

    // The session's first pending permission request (upstream shows a single
    // request per session tree: `sessionPermissionRequest` returns the first
    // match). Null while nothing is asking.
    val pendingPermission: kotlinx.coroutines.flow.StateFlow<OpenCodeClient.PermissionRequest?> =
        _state.map { it.pendingPermissions.firstOrNull() }
            .stateIn(scope, SharingStarted.Eagerly, null)

    // Wave 1 v2: first pending form (the v2 carrier that replaces question
    // REST; Wave 2 renders the dock). Null while nothing is asking.
    val pendingForm: kotlinx.coroutines.flow.StateFlow<ChatStore.FormAsk?> =
        _state.map { it.pendingForms.firstOrNull() }
            .stateIn(scope, SharingStarted.Eagerly, null)

    /** Generation of the active connection (V1 default = zero regression risk). */
    private val _generation = MutableStateFlow(ServerGeneration.V1)
    val generation: StateFlow<ServerGeneration> = _generation.asStateFlow()

    // WAVE 2.4b (queue/steer VM half): the session's queued follow-ups. The
    // composer renders these rows and forwards row ids back; every inbox
    // round-trip stays here. In-memory per session — cleared on session
    // switch like history, never persisted.
    private val queueHolder = ComposerQueueHolder()
    val queuedRows: StateFlow<List<ComposerQueue.Row>> = queueHolder.rows
    val queueBusy: StateFlow<Boolean> = queueHolder.busy

    /**
     * WAVE 2.4b: the `followUpBehavior` setting (`steer` | `queue`) the
     * composer reads to default the Steer/Queue toggle. Read-only here — the
     * composer never writes it (upstream keeps it in settings).
     */
    val followUpBehavior: StateFlow<String> =
        settingsStore?.state?.map { it.followUpBehavior }
            ?.stateIn(scope, SharingStarted.Eagerly, settingsStore.state.value.followUpBehavior)
            ?: MutableStateFlow("steer")

    // Id of the permission request currently being answered (upstream
    // `permissionResponding`); the dock disables its buttons while set.
    private val _permissionResponding = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val permissionResponding: kotlinx.coroutines.flow.StateFlow<String?> = _permissionResponding

    // The session's first pending question request (mirrors
    // [pendingPermission]; upstream mounts the question dock above the
    // permission dock, `session-composer-region.tsx:39-60`). Null while
    // nothing is asking.
    val pendingQuestion: kotlinx.coroutines.flow.StateFlow<OpenCodeClient.QuestionRequest?> =
        _state.map { it.pendingQuestions.firstOrNull() }
            .stateIn(scope, SharingStarted.Eagerly, null)

    // Id of the question request currently being answered; the dock disables
    // its buttons while set.
    private val _questionResponding = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val questionResponding: kotlinx.coroutines.flow.StateFlow<String?> = _questionResponding

    // Auto-approve (`SettingsStore.autoAcceptPermissions`, upstream
    // `requests/auto-approve.ts`): permission asks are replied "once" as they
    // arrive, with a bounded per-request retry budget. The counter lives
    // outside ChatState so a reduce round-trip never resets it.
    private val autoApprover = AutoApprover(
        enabled = { settingsStore?.state?.value?.autoAcceptPermissions == true },
    )

    var sessionID: String? = null
        private set

    /** Workspace directory of the tracked session; drives REST scoping. */
    private var directory: String? = null

    /**
     * The tracked session's workspace directory as confirmed by the server
     * (`refreshSnapshot`'s `GET /session/:id`). The Review panel roots its file
     * tree here: `/session` list only covers the server's own workspace, so
     * sessions living elsewhere never appear in the drawer backfill and the
     * screen's local `activeSession.directory` stays null.
     */
    private val _currentDirectory = MutableStateFlow<String?>(null)
    val currentDirectory: StateFlow<String?> = _currentDirectory.asStateFlow()

    // -------------------------------------------------- W6 session actions
    //
    // The three bits of `Session.Info` the ⋮ menu acts on (revert boundary,
    // share URL, fork result). Each is refreshed from the write's own response —
    // every route of `OpenCodeClient.session*` answers with the full session —
    // and `refreshSnapshot` re-adopts them on entry, so a session opened after a
    // revert/share elsewhere reports the same state (`use-session-commands.tsx`
    // reads `data.revertMessageID()` from the shared session model).

    /**
     * `Session.Info.revert.messageID` (`session/revert.ts:94`) — the boundary the
     * timeline is cut at. Everything at or after it is hidden from the store
     * (see [withoutRevertedTail]).
     */
    private val _revertMessageID = MutableStateFlow<String?>(null)
    val revertMessageID: StateFlow<String?> = _revertMessageID.asStateFlow()

    /**
     * What every renderer reads: [ChatStore.ChatState] with the reverted tail cut
     * off (`SessionActionsModel.hiddenMessageIDs`, upstream
     * `timeline/controller-projection.ts:25`). `_state` stays the raw store —
     * the server answers `GET /session/:id/message` with the hidden rows too
     * (verified live), so hiding them is a client projection, not a filter the
     * store may apply to itself.
     *
     * Declared **after** [_revertMessageID]: a `combine` built before the
     * boundary flow exists would capture a null reference.
     */
    val state: StateFlow<ChatStore.ChatState> = combine(_state, _revertMessageID) { raw, boundary ->
        raw.copy(messages = withoutRevertedTail(raw.messages, boundary))
    }.stateIn(scope, SharingStarted.Eagerly, ChatStore.ChatState())

    /**
     * `_state.messages` minus everything at or after [revertMessageID]. Delegates
     * the comparison to `SessionActionsModel.hiddenMessageIDs`, so the id
     * ordering rule (`message.id >= boundary`, UTF-16 code-unit `<`) has exactly
     * one implementation.
     */
    private fun withoutRevertedTail(
        messages: List<ChatStore.UiMessage>,
        revertMessageID: String?,
    ): List<ChatStore.UiMessage> {
        val hidden = SessionActionsModel.hiddenMessageIDs(messages.map { it.id }, revertMessageID)
        if (hidden.isEmpty()) return messages
        val hiddenIDs = hidden.toSet()
        return messages.filterNot { it.id in hiddenIDs }
    }

    /**
     * `Session.Info.share.url`, or null while the session is private.
     *
     * Live measurement (opencode 1.18.31) forces one deviation from "just read the
     * session": `DELETE /session/:id/share` answers `200` **and keeps reporting
     * `share`** — three DELETEs in a row, in the response and in every later
     * `GET /session/:id`. A purely server-driven toggle would therefore still
     * offer 取消分享 forever, so a successful unshare is remembered for the
     * session on screen ([shareRevoked]) and only an explicit share clears it.
     */
    private val _shareUrl = MutableStateFlow<String?>(null)
    val shareUrl: StateFlow<String?> = _shareUrl.asStateFlow()

    /** Set by [unshareSession] until the session is shared again. See [_shareUrl]. */
    private var shareRevoked = false

    /**
     * The session `POST /session/:id/fork` just created — the id the UI has to
     * navigate to (`session/commands/fork-dialog.tsx:88`
     * `navigate(sessionHref(server.key, forked.id))`). Sticky until
     * [consumeForkedSession] takes it, so a navigation that happens one
     * recomposition later still sees it.
     */
    private val _forkedSessionID = MutableStateFlow<String?>(null)
    val forkedSessionID: StateFlow<String?> = _forkedSessionID.asStateFlow()

    /**
     * The tracked session no longer exists server-side (a 404 on the session's
     * own reads — deleted by another client or expired). Set once, reset by
     * [observeSession]/[newSession]; the session screen backs out on it and all
     * session-scoped refreshes stop, so a stale route cannot spin a 404 loop.
     */
    private val _sessionMissing = MutableStateFlow(false)
    val sessionMissing: StateFlow<Boolean> = _sessionMissing.asStateFlow()

    fun observeSession(id: String, title: String? = null, directory: String? = null) {
        sessionID = id
        this.directory = directory
        _sessionMissing.value = false
        _currentDirectory.value = directory
        _state.value = ChatStore.ChatState(sessionID = id)
        _currentTitle.value = title
        _sessionCost.value = null
        _revertMessageID.value = null
        _shareUrl.value = null
        shareRevoked = false
        _forkedSessionID.value = null
        // WAVE 2.4b: the queue is per-session in-memory state, like history.
        queueHolder.clear()
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
        _sessionCost.value = null
        _revertMessageID.value = null
        _shareUrl.value = null
        shareRevoked = false
        _forkedSessionID.value = null
        _sessionMissing.value = false
        // WAVE 2.4b: no tracked session, no queue.
        queueHolder.clear()
    }

    /** True when [e] is the server's "no such session" answer on either protocol. */
    private fun isSessionGone(e: Exception): Boolean = when (e) {
        is V2Api.ApiException -> e.code == 404
        is OpenCodeClient.ApiException -> e.code == 404
        else -> false
    }

    /**
     * Latch [sessionMissing] and drop the per-session log stream — the global
     * stream stays up (other sessions still live), but nothing session-scoped
     * should keep polling a session the server forgot.
     */
    private fun markSessionMissing() {
        if (_sessionMissing.value) return
        _sessionMissing.value = true
        sessionLogStream?.close()
        sessionLogStream = null
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
     * Wave 1: also resolves the server generation. A cached generation is used
     * immediately (v1 default = the old path, byte-identical); when the cache
     * is empty the probe runs on IO (`GET /api/info`, then
     * `GET /global/health`) before the transport is chosen, and the result is
     * persisted per serverKey. Callers must not assume the transport exists
     * synchronously after this returns — [transportRef] is set on the IO
     * thread; generation-sensitive reads use [requireTransport], which falls
     * back to the v1 client until the probe lands.
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
        ensureTransport(server)
        refreshModelLabel()
        refreshProviders()
        refreshCatalogs()
        return true
    }

    /**
     * Wave 1 generation resolution: cached generation wins (no network);
     * otherwise probe on IO, persist, then build the transport.
     *
     * The v1 client above is ALWAYS built first so every existing read keeps
     * working during the probe window; the transport only *upgrades* reads to
     * v2 once the probe confirms V2. A V2Transport is never constructed for a
     * V1/UNKNOWN verdict and vice versa.
     */
    private fun ensureTransport(server: ServerConfigStore.Server) {
        val cached = serverConfig.generationOf(server.serverKey)
        if (cached != null && cached != ServerGeneration.UNKNOWN) {
            setGeneration(cached, server)
            return
        }
        if (!probing.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                val result = V2Capability.probeUrl(
                    server.url,
                    username = server.username,
                    password = server.password,
                    http = sharedClient,
                )
                val generation = result.generation
                if (generation != ServerGeneration.UNKNOWN) {
                    serverConfig.rememberGeneration(server.serverKey, generation)
                    // The server may have switched under us; only adopt when it
                    // is still the active one.
                    if (serverConfig.server?.serverKey == server.serverKey) {
                        withContext(Dispatchers.Main) { setGeneration(generation, server) }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "generation probe failed: ${e.message}")
            } finally {
                probing.set(false)
            }
        }
    }

    /** Install the transport for [generation] (idempotent per generation). */
    private fun setGeneration(generation: ServerGeneration, server: ServerConfigStore.Server) {
        if (activeGeneration == generation && transportRef.get() != null) return
        activeGeneration = generation
        _generation.value = generation
        val parsed = server.url.toHttpUrlOrNull()
        val transport: SessionTransport? = when (generation) {
            ServerGeneration.V2 -> {
                if (parsed == null) {
                    null
                } else {
                    V2Transport(V2Api(parsed, server.username, server.password, sharedClient, directory))
                }
            }
            ServerGeneration.V1, ServerGeneration.UNKNOWN -> {
                val client = clientRef.get()
                if (client == null || parsed == null) {
                    null
                } else {
                    V1Transport(client, parsed, directory)
                }
            }
        }
        transportRef.get()?.close()
        transportRef.set(transport)
        if (generation == ServerGeneration.V2) {
            // v2 catalogs + default model resolve through the new surface;
            // the v1 refreshes below become no-ops via requireTransport.
            refreshV2Catalogs()
        }
    }

    /** Active transport, or null when it has not been chosen yet (probe in flight). */
    private fun transport(): SessionTransport? = transportRef.get()

    /**
     * Generation-aware client access: v2 while a V2Transport is installed,
     * else the v1 client. Kept so every pre-seam call site keeps compiling
     * and behaving identically until it is individually migrated.
     */
    private fun requireTransport(): SessionTransport? = transport() ?: clientRef.get()?.let { client ->
        val server = serverConfig.server ?: return@let null
        val parsed = server.url.toHttpUrlOrNull() ?: return@let null
        V1Transport(client, parsed, directory)
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
        // Wave 1: the generation may still be probing (transport lands async
        // via ensureTransport). Open the v1 global stream immediately — zero
        // regression — and upgrade to v2 streams once V2 is confirmed.
        openV1Stream(parsed, server)
        if (activeGeneration == ServerGeneration.V2) openV2Streams(server)
        refreshSnapshot()
    }

    /**
     * Wave 1 v1 stream (extracted verbatim from the old `start()` body): one
     * `/global/event` connection + the v1 frame path. Untouched semantics.
     */
    private fun openV1Stream(parsed: HttpUrl, server: ServerConfigStore.Server) {
        val stream = EventStream(
            baseHttpUrl = parsed,
            username = server.username,
            password = server.password,
            client = EventStream.sseClient(sharedClient),
            // `/global/event`: one connection carries every project instance,
            // so sessions opened in other directories (draft's project picker)
            // stream live without reconnecting per workspace.
            global = true,
            listener = v1Listener(),
        )
        eventStream = stream
        stream.start()
    }

    private fun v1Listener() = object : EventStream.Listener {
        override fun onEvent(type: String, data: JSONObject) {
            onV1Frame(type, data)
        }

        override fun onState(state: EventStream.State) {
            if (state == EventStream.State.ESTABLISHED) refreshSnapshot()
        }
    }

    /** Wave 1 v1 frame path (byte-identical to the old inline listener). */
    private fun onV1Frame(type: String, data: JSONObject) {
        // Global frames wrap the real event in `payload`; instance
        // frames are already the event envelope.
        val inner = ChatStore.unwrapGlobal(data) ?: data
        val innerType = if (inner !== data) inner.optString("type", type) else type
        // Session the frame is routed to, read once: the liveness
        // clock, the notifications and the gate all need it.
        val routed = ChatStore.eventSessionId(inner)
        if (innerType.startsWith("session.")) {
            // Any turn activity on the tracked session (tool runs,
            // queueing) means the run is alive: bump the liveness
            // clock so the IDLE grace check cannot misfire. Foreign
            // sessions' activity must NOT bump it (global stream),
            // or a busy neighbour would stall our idle settle.
            if (routed == null || routed == sessionID) {
                lastPartEventAt = System.currentTimeMillis()
            }
            maybeRefreshOnSession(inner)
        }
        val event = ChatStore.parseEvent(innerType, inner) ?: return
        // E2/E5 run *before* the session gate: they are app-wide, not
        // screen-scoped. A `project.updated` from another workspace,
        // another session's permission ask, or a neighbouring
        // session's finished turn must all reach them — while the
        // transcript below stays gated on the tracked session.
        if (event is ChatStore.Event.ServerSignal) {
            onServerSignal(event)
            return
        }
        notifyFor(event, routed)
        if (!ChatStore.acceptsSession(sessionID, inner)) return
        if (event is ChatStore.Event.PartUpdated || event is ChatStore.Event.MessageUpdated) {
            lastPartEventAt = System.currentTimeMillis()
        }
        scope.launch { applyEvent(event) }
        // Turn settled: reconcile with the server. Parts the server
        // retracted or superseded during the run never reach us as
        // frames we act on (`message.part.removed` only removes),
        // so one throttled snapshot closes the gap.
        if (event is ChatStore.Event.RunStatusChanged &&
            event.status == ChatStore.RunStatus.IDLE
        ) {
            refreshSnapshotThrottled()
        }
    }

    /**
     * Wave 1 v2 streams: `GET /api/event` (global) + per-session
     * `GET /api/experimental/session/{id}/log?after=&follow=true` (durable
     * replay + live). Both share the v1 backoff/heartbeat rules via
     * [EventStream]; only the request builder and the frame adapter differ.
     *
     * Called when V2 is confirmed — either synchronously from `start()` (warm
     * cache) or async when the probe lands (then the v1 stream is closed
     * first so the two generations never double-drive the store).
     */
    private fun openV2Streams(server: ServerConfigStore.Server) {
        val parsed = server.url.toHttpUrlOrNull() ?: return
        val transport = transportRef.get() as? V2Transport ?: return
        // One generation drives the store: drop the v1 stream before opening
        // the v2 pair (probe-landed-late path).
        eventStream?.close()
        eventStream = null
        val global = EventStream(
            baseHttpUrl = parsed,
            username = server.username,
            password = server.password,
            client = EventStream.sseClient(sharedClient),
            global = true,
            v2 = true,
            listener = object : EventStream.Listener {
                override fun onEvent(type: String, data: JSONObject) {
                    onV2Frame(transport, type, data)
                }

                override fun onState(state: EventStream.State) {
                    if (state == EventStream.State.ESTABLISHED) refreshSnapshot()
                }
            },
        )
        eventStream = global
        global.start()
        openSessionLogStream(server, transport)
    }

    /** (Re)opens the per-session log stream for the tracked session (v2 only). */
    private fun openSessionLogStream(server: ServerConfigStore.Server, transport: V2Transport) {
        val id = sessionID ?: return
        val parsed = server.url.toHttpUrlOrNull() ?: return
        sessionLogStream?.close()
        val log = EventStream(
            baseHttpUrl = parsed,
            username = server.username,
            password = server.password,
            client = EventStream.sseClient(sharedClient),
            sessionLog = id to seqTracker.afterParam(),
            listener = object : EventStream.Listener {
                override fun onEvent(type: String, data: JSONObject) {
                    onV2Frame(transport, type, data, fromLog = true)
                }

                override fun onState(state: EventStream.State) {
                    if (state == EventStream.State.ESTABLISHED) refreshSnapshot()
                }

                override fun onHttpError(code: Int) {
                    // The per-session log 404s only when the server forgot
                    // this session — latch the breaker instead of burning
                    // the client-error retry budget on a dead aggregate.
                    if (code == 404) scope.launch { markSessionMissing() }
                }
            },
        )
        sessionLogStream = log
        log.start()
    }

    /**
     * Wave 1 v2 frame path: `log.synced` feeds the seq watermark (never the
     * store); durable frames dedupe by `evt_` id and advance `maxSeq`;
     * ephemeral frames apply live. Reconnect replays via `?after=maxSeq` and
     * reconciles ephemeral gaps with a throttled full-message fetch.
     */
    private fun onV2Frame(
        transport: V2Transport,
        type: String,
        data: JSONObject,
        fromLog: Boolean = false,
    ) {
        if (type == "log.synced") {
            val seq = data.optLong("seq", -1L).takeIf { data.has("seq") }
            seqTracker.onSynced(seq)
            // Watermark landed: durable gap below it reconciles via snapshot.
            refreshSnapshotThrottled()
            return
        }
        val frame = V2EventAdapter.frameOf(data)
        val useSeq = !fromLog || frame.aggregateID == sessionID
        val isNew = if (useSeq) {
            seqTracker.observe(frame.id, frame.seq)
        } else {
            true
        }
        if (!isNew) return
        val routed = V2EventAdapter.sessionID(data)
        val eventType = frame.type.ifEmpty { type }
        // Same liveness rule as the v1 path: any session.* frame routed to the
        // tracked session means the turn is alive — including the ones the
        // adapter maps to no store event (tool.progress, step.streamed), which
        // are the only traffic a tool-heavy turn produces.
        if (eventType.startsWith("session.") && (routed == null || routed == sessionID)) {
            lastPartEventAt = System.currentTimeMillis()
        }
        // WAVE 2.4b: `session.inbox.*` (enqueued/delivered/cancelled/
        // delivery.changed) is the queue's server truth changing — re-read it
        // via `GET …/inbox` (event-driven only, no polling). `refreshInbox`
        // itself no-ops off-session and off-v2.
        if (eventType.startsWith("session.inbox.") && acceptsV2Session(sessionID, routed)) refreshInbox()
        val events = transport.parseFrame(eventType, data)
        for (event in events) {
            if (event is ChatStore.Event.ServerSignal) {
                onServerSignal(event)
                continue
            }
            notifyFor(event, routed)
            if (!acceptsV2Session(sessionID, routed)) continue
            if (event is ChatStore.Event.PartUpdated || event is ChatStore.Event.MessageUpdated) {
                lastPartEventAt = System.currentTimeMillis()
            }
            if (event is ChatStore.Event.RunStatusChanged && event.status == ChatStore.RunStatus.RUNNING) {
                lastPartEventAt = System.currentTimeMillis()
            }
            scope.launch { applyEvent(event) }
            if (event is ChatStore.Event.RunStatusChanged &&
                event.status == ChatStore.RunStatus.IDLE
            ) {
                refreshSnapshotThrottled()
                // WAVE 2.4b: the turn settled with queued follow-ups — drain
                // the parked first row as steer.
                drainQueueOnIdle()
            }
        }
    }

    /** v2 session gate: `data.sessionID` (or the durable aggregate) routes; session-less passes. */
    private fun acceptsV2Session(currentID: String?, routed: String?): Boolean {
        if (routed == null) return true
        return currentID == null || currentID == routed
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
        sessionLogStream?.close()
        sessionLogStream = null
        clientRef.set(null)
        transportRef.get()?.close()
        transportRef.set(null)
        seqTracker.reset()
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

    /**
     * Server default model. v1: `GET /config` → `model` = `"provider/modelID"`.
     * v2: `GET /api/model/default` (handled by [refreshV2DefaultModel] — this
     * path returns early on v2). It becomes the effective selection only while
     * the user has not pinned a model in the picker — upstream semantics
     * (`providers/models/selection.tsx`): recent/picked locally, default from
     * config. Failure leaves the chip hidden.
     */
    private fun refreshModelLabel() {
        if (activeGeneration == ServerGeneration.V2) return
        val client = clientRef.get() ?: return
        scope.launch {
            try {
                val config = withContext(Dispatchers.IO) { client.config() }
                serverDefaultModel = OpenCodeClient.parseModelRef(config.optString("model", ""))
                // Only ever *adopt* a configured default — never clear the chip
                // with it. This server leaves `/config.model` empty, so the
                // session-derived model (`refreshSnapshot`: the latest assistant
                // turn's own `providerID`/`modelID`) is the only truth available;
                // unconditionally assigning the (null) default here wiped it, and
                // the chip fell back to the generic「模型」placeholder whenever the
                // config refresh landed after the snapshot.
                if (modelOverride == null && serverDefaultModel != null) {
                    _selectedModel.value = serverDefaultModel
                }
                syncModelLabel()
            } catch (e: Exception) {
                Log.w(TAG, "config fetch failed: ${e.message}")
            }
        }
    }

    /** Provider catalog for the picker; one silent retry, then the chip toasts. v1 only (v2: model list). */
    private fun refreshProviders() {
        if (activeGeneration == ServerGeneration.V2) {
            refreshV2Providers()
            return
        }
        val client = clientRef.get() ?: return
        scope.launch {
            val fetched = fetchProviders(client) ?: run {
                delay(PROVIDERS_RETRY_DELAY_MS)
                fetchProviders(client)
            }
            if (fetched != null) {
                _availableProviders.value = fetched
                syncModelLabel()
            }
        }
    }

    private suspend fun fetchProviders(client: OpenCodeClient): OpenCodeClient.Providers? = try {
        withContext(Dispatchers.IO) { client.providers() }
    } catch (e: Exception) {
        Log.w(TAG, "providers fetch failed: ${e.message}")
        null
    }

    /**
     * Wave 1 v2 provider+model catalogs: `GET /api/provider` +
     * `GET /api/model` folded into the v1 `Providers` shape the picker
     * already renders (`connected` = all ids — v2 has no connected subset;
     * model entries keep `limit.context` for the context ring and variant ids
     * for the effort picker).
     */
    private fun refreshV2Providers() {
        val transport = transportRef.get() as? V2Transport ?: return
        scope.launch {
            try {
                val api = transportApi(transport) ?: return@launch
                val providers = withContext(Dispatchers.IO) { api.providers(directory) }
                val models = withContext(Dispatchers.IO) { api.models(directory) }
                if (providers.isEmpty()) return@launch
                val all = providers.map { p ->
                    OpenCodeClient.Providers.Provider(
                        id = p.id,
                        name = p.name,
                        models = models.filter { it.providerID == p.id }.map { m ->
                            OpenCodeClient.Providers.Model(
                                id = m.modelID,
                                name = m.name,
                                capabilities = OpenCodeClient.Providers.Capabilities(
                                    reasoning = null,
                                    attachment = null,
                                    toolCall = m.tools,
                                ),
                                limitContext = m.limitContext,
                                limitOutput = m.limitOutput,
                                variants = m.variants,
                            )
                        },
                    )
                }
                _availableProviders.value = OpenCodeClient.Providers(
                    all = all,
                    connected = all.map { it.id },
                    default = emptyMap(),
                )
                syncModelLabel()
            } catch (e: Exception) {
                Log.w(TAG, "v2 providers fetch failed: ${e.message}")
            }
        }
    }

    /** Chip label = catalog model name (upstream chip), falling back to the raw modelID. */
    private fun syncModelLabel() {
        val selected = _selectedModel.value
        _modelLabel.value = selected?.let { (providerID, modelID) ->
            _availableProviders.value?.model(providerID, modelID)?.name ?: modelID
        }
    }

    // ---- catalogs / selections (W5-C) ---------------------------------------

    /**
     * `GET /agent` + `GET /command` + `GET /skill`, one request each, silently
     * retried once. v1 only — v2 catalogs resolve through [refreshV2Catalogs]
     * (different DTOs; the composer-facing lists are shared). Read-only: a
     * failure leaves the previous catalog in place, so the composer simply
     * keeps showing what it had (and the `@` popover keeps working for files).
     */
    private fun refreshCatalogs() {
        if (activeGeneration == ServerGeneration.V2) return
        val client = clientRef.get() ?: return
        scope.launch {
            val agents = fetchCatalog { client.agents(directory) } ?: emptyList()
            if (agents.isNotEmpty()) _availableAgents.value = agents
            val commands = fetchCatalog { client.commands(directory) } ?: emptyList()
            if (commands.isNotEmpty()) _availableCommands.value = commands
            val skills = fetchCatalog { client.skills(directory) } ?: emptyList()
            if (skills.isNotEmpty()) _availableSkills.value = skills
        }
    }

    /**
     * Wave 1 v2 catalogs: `GET /api/agent` + `/api/command` + `/api/skill`
     * (location-scoped `{location,data}` reads) mapped onto the SAME
     * composer-facing lists (`_availableAgents/_availableCommands/
     * _availableSkills`) the UI already renders — zero UI changes. v2 command
     * entries carry no template (client-side template expansion stays v1-only);
     * v2 skill `path` maps onto the v1 `location` slot.
     */
    private fun refreshV2Catalogs() {
        val transport = transportRef.get() as? V2Transport ?: return
        scope.launch {
            try {
                val api = transportApi(transport) ?: return@launch
                val agents = withContext(Dispatchers.IO) { api.agents(directory) }
                if (agents.isNotEmpty()) {
                    _availableAgents.value = agents.map { it.toV1() }
                }
                val commands = withContext(Dispatchers.IO) { api.commands(directory) }
                if (commands.isNotEmpty()) {
                    _availableCommands.value = commands.map { it.toV1() }
                }
                val skills = withContext(Dispatchers.IO) { api.skills(directory) }
                if (skills.isNotEmpty()) {
                    _availableSkills.value = skills.map { it.toV1() }
                }
                refreshV2DefaultModel(api)
            } catch (e: Exception) {
                Log.w(TAG, "v2 catalog fetch failed: ${e.message}")
            }
        }
    }

    /** `GET /api/model/default` → the chip default (v1 reads `GET /config.model`). */
    private suspend fun refreshV2DefaultModel(api: V2Api) {
        try {
            val entry = withContext(Dispatchers.IO) { api.defaultModel(directory) } ?: return
            val ref = entry.providerID to entry.modelID
            if (modelOverride == null) {
                serverDefaultModel = ref
                _selectedModel.value = ref
                syncModelLabel()
            }
        } catch (e: Exception) {
            Log.w(TAG, "v2 default model fetch failed: ${e.message}")
        }
    }

    /** V2Api handle for catalog reads (same instance the transport owns). */
    private fun transportApi(transport: V2Transport): V2Api? {
        val server = serverConfig.server ?: return null
        val parsed = server.url.toHttpUrlOrNull() ?: return null
        return V2Api(parsed, server.username, server.password, sharedClient, directory)
    }

    private fun V2Api.AgentInfo.toV1(): OpenCodeClient.Agent = OpenCodeClient.Agent(
        name = id,
        description = description,
        mode = mode,
        native = false,
        hidden = hidden,
    )

    private fun V2Api.CommandInfo.toV1(): OpenCodeClient.CommandInfo =
        OpenCodeClient.CommandInfo(name = name, description = description, source = "", template = template)

    private fun V2Api.SkillInfo.toV1(): OpenCodeClient.SkillInfo =
        OpenCodeClient.SkillInfo(name = name, description = description, location = location)

    /** One catalog read with one silent retry; `null` = still failed. */
    private suspend fun <T> fetchCatalog(read: suspend () -> T): T? {
        val first = try {
            withContext(Dispatchers.IO) { read() }
        } catch (e: Exception) {
            Log.w(TAG, "catalog fetch failed: ${e.message}")
            null
        }
        if (first != null) return first
        delay(PROVIDERS_RETRY_DELAY_MS)
        return try {
            withContext(Dispatchers.IO) { read() }
        } catch (e: Exception) {
            Log.w(TAG, "catalog retry failed: ${e.message}")
            null
        }
    }

    /**
     * Pin the agent the next prompt/shell runs as (`GET /agent` names). Null
     * clears the pin and returns to the server default; an unknown name is
     * refused so the chip can never show something the server cannot run.
     */
    fun selectAgent(name: String?) {
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            _selectedAgent.value = null
            return
        }
        val known = OpenCodeClient.Agent.selectable(_availableAgents.value).any { it.name == trimmed }
        if (known) _selectedAgent.value = trimmed
    }

    /**
     * Pin the model variant ([OpenCodeClient.Providers.Model.variants] label).
     * Null (or `"default"`) means "follow the server/agent default".
     */
    fun selectVariant(label: String?) {
        val trimmed = label?.trim().orEmpty()
        _selectedVariant.value = trimmed
            .takeIf { it.isNotEmpty() && it != ModelPickerItems.VARIANT_DEFAULT }
    }

    /**
     * The agent a write actually runs as: the pinned one, else the server's first
     * selectable primary agent (`"build"` as the last resort — the value
     * `POST /session/:id/shell` requires).
     */
    fun effectiveAgent(): String =
        _selectedAgent.value ?: OpenCodeClient.Agent.defaultName(_availableAgents.value)

    /**
     * The variant a write actually sends: the pinned label, validated against the
     * selected model's catalog entry, else the agent's own configured variant when
     * the agent pins the same model
     * (`getConfiguredAgentVariant`, `providers/models/variant.ts:22-29`).
     */
    fun effectiveVariant(): String? {
        val selected = _selectedModel.value ?: return null
        val variants = _availableProviders.value?.model(selected.first, selected.second)?.variants
            ?: return null
        val agentVariant = _availableAgents.value
            .firstOrNull { it.name == _selectedAgent.value }
            ?.takeIf { it.providerID == selected.first && it.modelID == selected.second }
            ?.variant
        return ModelPickerItems.resolveVariant(
            selected = _selectedVariant.value ?: agentVariant,
            variants = variants,
            configured = agentVariant,
        )
    }

    /**
     * Context window of the currently selected model, straight from the
     * provider catalog (`GET /provider` → `limit.context`). `null` when the
     * catalog has not loaded yet or the model advertises no limit — the ring
     * then renders its documented empty track instead of a fake denominator.
     *
     * Upstream reads the same number from its provider/model store
     * (`session/timeline/session-context-usage.tsx:40`).
     */
    val contextLimit: kotlinx.coroutines.flow.StateFlow<Int?> =
        kotlinx.coroutines.flow.combine(
            _selectedModel,
            _availableProviders,
        ) { selected: Pair<String, String>?, providers: OpenCodeClient.Providers? ->
            contextLimitOf(providers, selected)
        }.stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * Session spend for the context-ring tooltip (`Session.Info.cost`,
     * upstream `context.usage.cost`). Refreshed with the snapshot's session-info
     * read; `null` = not reported (the tooltip then hides the row).
     */
    private val _sessionCost = MutableStateFlow<Double?>(null)
    val sessionCost: StateFlow<Double?> = _sessionCost.asStateFlow()

    /**
     * Picker selection. Persisted as `"providerID/modelID"`
     * ([SettingsStore.selectedModelOverride]) so the choice survives restarts
     * and session switches, and pinned onto every prompt by [send].
     */
    fun selectModel(providerID: String, modelID: String) {
        val ref = providerID to modelID
        modelOverride = ref
        settingsStore?.selectedModelOverride = OpenCodeClient.formatModelRef(ref)
        _selectedModel.value = ref
        _modelOverrideActive.value = true
        // W5-C B4: variant labels belong to one model's catalog entry, so a model
        // switch drops the pin instead of carrying an unrelated effort over.
        if (hasVariants(ref)) {
            if (_selectedVariant.value != null && !hasVariant(ref, _selectedVariant.value!!)) {
                _selectedVariant.value = null
            }
        } else {
            _selectedVariant.value = null
        }
        syncModelLabel()
    }

    /** True when the catalog advertises at least one variant for [ref]. */
    private fun hasVariants(ref: Pair<String, String>): Boolean =
        _availableProviders.value?.model(ref.first, ref.second)?.variants?.isNotEmpty() == true

    /** True when [label] is one of [ref]'s advertised variants. */
    private fun hasVariant(ref: Pair<String, String>, label: String): Boolean =
        _availableProviders.value?.model(ref.first, ref.second)?.variants?.contains(label) == true

    /** 「使用服务器默认」: drops the local override so `config.model` applies again. */
    fun clearModelOverride() {
        modelOverride = null
        settingsStore?.selectedModelOverride = null
        _selectedModel.value = serverDefaultModel
        _modelOverrideActive.value = false
        syncModelLabel()
    }

    /**
     * A plain prompt (`POST /session/:id/prompt_async`). Model / agent / variant
     * are the composer's current selections (W5-C B4): the pinned model as before,
     * plus the pinned agent and the resolved variant — both omitted when the user
     * pinned neither, so the server keeps its own defaults.
     *
     * A failed POST hands the text back to the composer's draft slot
     * ([ComposerDrafts.restore]) and drops the just-recorded history entry
     * (`composer/submit.ts:407-418` + `entry.ts:51-61`), so nothing the user typed
     * is lost.
     */
    fun send(text: String, attachments: List<Attachment>) {
        submit(text = text, attachments = attachments)
    }

    /**
     * W5-C submit router (`composer/submit.ts:96-141`): shell mode, a server slash
     * command, or a plain prompt. Every branch shares the same failure contract —
     * the draft comes back and the run status turns ERROR.
     *
     * Wave 1: routes through the active [SessionTransport]. v1 behavior is
     * byte-identical (same `OpenCodeClient` calls with the same args); v2
     * sends the flat prompt body and switches agent/model first on steer
     * (upstream `submit.ts` rule: a queued follow-up must not reconfigure the
     * running turn).
     *
     * WAVE 2.4b: accepts the composer's structured submit ([ComposerSubmission])
     * — mention `file://` refs, agent/skill names, and the steer/queue delivery.
     * V1 ignores `delivery` (forced to steer: no inbox API) and carries mention
     * files as file parts after the attachment parts; agents/skills have no v1
     * carrier (they stay inline in the text). V2 maps them onto the flat prompt
     * body (`files` mentions-first, `agents[]`, `skills[]`,
     * `metadata.displayText`, `delivery`) and admits a `delivery=queue` prompt
     * into [queuedRows] optimistically.
     */
    fun submit(
        text: String,
        attachments: List<Attachment> = emptyList(),
        shell: Boolean = false,
        command: String? = null,
        arguments: String = "",
        // WAVE 2.4b: the structured submit (all defaulted — old callers keep compiling).
        files: List<ComposerSubmission.FileEntry> = emptyList(),
        agents: List<String> = emptyList(),
        skills: List<ComposerSubmission.ComposerSkill> = emptyList(),
        delivery: String = "steer",
    ) {
        val id = sessionID ?: return
        val transport = requireTransport() ?: run {
            requireClient()
            return
        }
        if (text.isBlank() && attachments.isEmpty()) return
        // WAVE 2.4b §4: the VM is the trust boundary — v1 always steers,
        // whatever the composer sent (the composer already guarantees it).
        val wireDelivery = if (transport.generation == ServerGeneration.V1) "steer" else delivery
        val model = _selectedModel.value
        val variant = effectiveVariant()
        val agent = _selectedAgent.value
        scope.launch {
            _state.value = ChatStore.reduce(_state.value, ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.RUNNING))
            try {
                val admittedID: String? = withContext(Dispatchers.IO) {
                    when (val t = transport) {
                        is V1Transport -> when {
                            // Shell mode has its own route; it *requires* an agent.
                            shell -> {
                                t.sendShellAs(
                                    sessionID = id,
                                    command = text,
                                    agent = effectiveAgent(),
                                    model = model,
                                )
                                // v1 admits carry no queue key (see the seam).
                                null
                            }

                            command != null -> {
                                t.sendCommandAs(
                                    sessionID = id,
                                    command = command,
                                    arguments = arguments,
                                    agent = agent,
                                    model = model,
                                    variant = variant,
                                )
                                null
                            }

                            else -> {
                                val parts = attachments.map { attachment ->
                                    ChatStore.UiPart(
                                        id = "att:${attachment.filename}:${attachment.dataUrl.hashCode()}",
                                        type = "file",
                                        mime = attachment.mime,
                                        filename = attachment.filename,
                                        url = attachment.dataUrl,
                                    )
                                } + mentionFileParts(files)
                                clientRef.get()?.promptAsync(
                                    id,
                                    text,
                                    parts,
                                    directory = directory,
                                    model = model,
                                    agent = agent,
                                    variant = variant,
                                )
                                null
                            }
                        }

                        is V2Transport -> {
                            // Steer reconfigures the (possibly running) turn;
                            // queue leaves the session selection alone.
                            val steer = wireDelivery == "steer"
                            if (steer) {
                                if (agent != null) t.switchAgent(id, agent)
                                if (model != null) {
                                    t.switchModel(id, model.first, model.second, variant)
                                }
                            }
                            when {
                                shell -> {
                                    t.sendShell(id, text, messageID = null)
                                    null
                                }
                                command != null -> {
                                    t.sendCommand(
                                        id,
                                        command,
                                        arguments,
                                        filesOf(attachments),
                                        wireDelivery,
                                    )
                                    null
                                }
                                else -> t.sendStructuredPrompt(
                                    id,
                                    text,
                                    promptFilesFor(files, attachments),
                                    messageID = null,
                                    delivery = wireDelivery,
                                    resume = null,
                                    agents = agents.map { JSONObject().put("name", it) }
                                        .takeIf { it.isNotEmpty() },
                                    skills = skills.map { JSONObject().put("id", it.id).put("name", it.name) }
                                        .takeIf { it.isNotEmpty() },
                                    metadata = JSONObject().put("displayText", text),
                                )
                            }
                        }

                        // Unreachable (the seam has exactly two transports) —
                        // keeps the expression exhaustive without an `else`
                        // swallowing a future generation.
                        else -> null
                    }
                }
                // WAVE 2.4b §3: a `delivery=queue` admit joins the queue
                // optimistically (the `session.inbox.*` refresh confirms it).
                if (wireDelivery == "queue" && !admittedID.isNullOrBlank() && command == null && !shell) {
                    queueHolder.add(
                        ComposerQueue.Row(
                            id = admittedID,
                            text = text,
                            attachmentCount = promptFilesFor(files, attachments).size,
                            submission = ComposerSubmission.Model(
                                text = text,
                                files = files,
                                agents = agents,
                                skills = skills,
                                delivery = ComposerSubmission.Delivery.of(wireDelivery),
                                shell = false,
                                command = null,
                                arguments = "",
                            ),
                        ),
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "prompt failed: ${e.message}")
                restoreDraft(id, text, shell)
                _state.value = ChatStore.reduce(
                    _state.value,
                    ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.ERROR, e.message),
                )
            }
        }
    }

    /** v2 file attachments (`PromptInput.FileAttachment{uri,…}`): data URLs pass through. */
    private fun filesOf(attachments: List<Attachment>): List<V2Api.PromptFile> =
        attachments.map { V2Api.PromptFile(uri = it.dataUrl, name = it.filename) }

    /**
     * WAVE 2.4b: v1 file parts for `@file` mentions (`{type:file, mime,
     * filename, url}` with `url = file.uri` — the server resolves `file://`),
     * appended AFTER the attachment parts. Agents/skills have no v1 carrier.
     */
    private fun mentionFileParts(files: List<ComposerSubmission.FileEntry>): List<ChatStore.UiPart> =
        files.filter { it.uri.startsWith("file://") }.map { entry ->
            ChatStore.UiPart(
                id = "att:${entry.name ?: entry.uri}:${entry.uri.hashCode()}",
                type = "file",
                mime = entry.mime ?: V1Transport.mimeOf(entry.uri),
                filename = entry.name
                    ?: entry.uri.substringAfterLast('/').takeIf { it.isNotEmpty() }
                    ?: "file",
                url = entry.uri,
            )
        }

    /**
     * WAVE 2.4b: the v2 prompt `files` in `request.ts` order — mention
     * `file://` refs first, then the attachment chips. `data:` entries already
     * present in [files] (the composer embeds the chips in its submit model)
     * are skipped when they duplicate an attachment chip, kept otherwise (the
     * queue-edit confirm path carries attachments only through the model).
     */
    private fun promptFilesFor(
        files: List<ComposerSubmission.FileEntry>,
        attachments: List<Attachment>,
    ): List<V2Api.PromptFile> {
        val chipUris = attachments.map { it.dataUrl }.toHashSet()
        val mentions = files.filter { it.uri.startsWith("file://") }
            .map { V2Api.PromptFile(uri = it.uri, name = it.name) }
        val inline = files.filter { it.uri.startsWith("data:") && it.uri !in chipUris }
            .map { V2Api.PromptFile(uri = it.uri, name = it.name) }
        return mentions + filesOf(attachments) + inline
    }

    /** `POST /session/:id/shell` — the composer's shell mode (W5-C B3). */
    fun sendShell(text: String) {
        submit(text = text, shell = true)
    }

    /** `POST /session/:id/command` — a `/name args` line the server lists (W5-C B1). */
    fun runCommand(name: String, arguments: String) {
        submit(text = name, command = name, arguments = arguments)
    }

    /**
     * Failed-send rollback: put the text back in the composer's draft for this
     * session and forget the history entry the submit recorded — exactly the two
     * things upstream's `failSubmission` does (`submit.ts:407-418`).
     */
    private fun restoreDraft(sessionID: String, text: String, shell: Boolean) {
        if (text.isBlank()) return
        val key = ComposerDrafts.key(sessionID)
        ComposerDrafts.restore(key, text)
        ComposerHistory.of(key).remove(text, shell)
    }

    // --------------------------------------- WAVE 2.4b queue mutations (v2-only)

    /**
     * Promote a queued follow-up to the running turn
     * (`PATCH …/inbox/{id}{delivery:"steer"}`, `queue.ts` steer). Optimistic
     * remove — the server's `delivery.changed` event confirms via [refreshInbox].
     * A failed PATCH puts the row back. NO-OP on v1 (the panel never shows there).
     */
    fun steerQueued(inboxID: String) {
        val id = sessionID ?: return
        val transport = transport() as? V2Transport ?: return
        scope.launch {
            val row = queueHolder.remove(inboxID) ?: return@launch
            queueHolder.setBusy(true)
            try {
                withContext(Dispatchers.IO) { transport.updateInboxDelivery(id, inboxID, "steer") }
            } catch (e: Exception) {
                Log.w(TAG, "steer queued failed: ${e.message}")
                queueHolder.add(row)
            } finally {
                queueHolder.setBusy(false)
            }
        }
    }

    /**
     * Drop a queued follow-up (`DELETE …/inbox/{id}`, `queue.ts` remove).
     * Optimistic remove — `inbox.cancelled` confirms. A failed DELETE puts the
     * row back. NO-OP on v1.
     */
    fun cancelQueued(inboxID: String) {
        val id = sessionID ?: return
        val transport = transport() as? V2Transport ?: return
        scope.launch {
            val row = queueHolder.remove(inboxID) ?: return@launch
            queueHolder.setBusy(true)
            try {
                withContext(Dispatchers.IO) { transport.cancelInbox(id, inboxID) }
            } catch (e: Exception) {
                Log.w(TAG, "cancel queued failed: ${e.message}")
                queueHolder.add(row)
            } finally {
                queueHolder.setBusy(false)
            }
        }
    }

    /**
     * Confirm a queue edit: admit the replacement FIRST, then cancel the
     * original (`queue.ts:65-74` admit-before-cancel — a failed replacement
     * never discards the original; the replacement text is restored to the
     * draft instead). A pristine value re-queued is a no-op; a pristine value
     * steered promotes the original in place. NO-OP on v1.
     */
    fun confirmQueueEdit(originalID: String, replacement: ComposerSubmission.Model) {
        val id = sessionID ?: return
        val transport = transport() as? V2Transport ?: return
        scope.launch {
            val row = queueHolder.current().find { it.id == originalID } ?: return@launch
            val pristine = replacement.text == row.text &&
                replacement.files.size == row.attachmentCount
            if (pristine) {
                // Upstream: confirming an unchanged value in place is a no-op;
                // the alternate action steers the original instead.
                if (replacement.delivery == ComposerSubmission.Delivery.QUEUE) return@launch
                steerQueued(originalID)
                return@launch
            }
            queueHolder.setBusy(true)
            try {
                val replacementDelivery = replacement.delivery.wire
                val admittedID = withContext(Dispatchers.IO) {
                    sendStructured(transport, id, replacement)
                }
                queueHolder.remove(originalID)
                if (replacementDelivery == "queue" && !admittedID.isNullOrBlank()) {
                    queueHolder.add(
                        ComposerQueue.Row(
                            id = admittedID,
                            text = replacement.text,
                            attachmentCount = promptFilesFor(replacement.files, emptyList()).size,
                            submission = replacement,
                        ),
                    )
                }
                // The original is superseded — cancel it best-effort (the
                // refresh confirms; a failed cancel only leaves a server row
                // the next refresh reconciles).
                try {
                    withContext(Dispatchers.IO) { transport.cancelInbox(id, originalID) }
                } catch (e: Exception) {
                    Log.w(TAG, "cancel superseded queued failed: ${e.message}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "confirm queue edit failed: ${e.message}")
                ComposerDrafts.restore(ComposerDrafts.key(id), replacement.text)
            } finally {
                queueHolder.setBusy(false)
            }
        }
    }

    /**
     * WAVE 2.4b: re-admit a structured replacement (the queue-edit confirm
     * path). Mirrors [submit]'s v2 prompt mapping with a client-generated
     * message id; shell/command never reach here (the composer never sends
     * tokens with them). Returns the admitted message id.
     */
    private fun sendStructured(
        transport: V2Transport,
        sessionID: String,
        replacement: ComposerSubmission.Model,
    ): String? {
        val wireDelivery = replacement.delivery.wire
        if (wireDelivery == "steer") {
            val agent = _selectedAgent.value
            val model = _selectedModel.value
            if (agent != null) transport.switchAgent(sessionID, agent)
            if (model != null) transport.switchModel(sessionID, model.first, model.second, effectiveVariant())
        }
        return transport.sendStructuredPrompt(
            sessionID,
            replacement.text,
            promptFilesFor(replacement.files, emptyList()),
            messageID = newMessageID(),
            delivery = wireDelivery,
            resume = null,
            agents = replacement.agents.map { JSONObject().put("name", it) }
                .takeIf { it.isNotEmpty() },
            skills = replacement.skills.map { JSONObject().put("id", it.id).put("name", it.name) }
                .takeIf { it.isNotEmpty() },
            metadata = JSONObject().put("displayText", replacement.text),
        )
    }

    /** Client-generated prompt id (`msg_…`, mirroring the measured 27-char shape). */
    private fun newMessageID(): String =
        "msg_" + UUID.randomUUID().toString().filter { it.isLetterOrDigit() }.take(27)

    /**
     * WAVE 2.4b: server truth for the queue — `GET …/inbox` mapped to rows,
     * wholesale replace. Driven by `session.inbox.*` events ([onServerSignal])
     * and after mutations settle; event-driven only, no polling.
     */
    private fun refreshInbox() {
        val id = sessionID ?: return
        val transport = transport() as? V2Transport ?: return
        scope.launch {
            try {
                val items = withContext(Dispatchers.IO) { transport.fetchInbox(id) }
                queueHolder.replace(items.mapNotNull { ComposerQueueHolder.rowFromJson(it) })
            } catch (e: Exception) {
                Log.w(TAG, "refresh inbox failed: ${e.message}")
            }
        }
    }

    /**
     * WAVE 2.4b: the turn went IDLE with rows present — the parked first row's
     * Send re-admits it as steer (`queue-panel.tsx:89-92,175`), i.e.
     * [steerQueued]. One row per IDLE; the next IDLE drains the next.
     */
    private fun drainQueueOnIdle() {
        if (activeGeneration != ServerGeneration.V2) return
        if (queueHolder.busy.value) return
        val first = queueHolder.current().firstOrNull() ?: return
        steerQueued(first.id)
    }

    fun abort() {
        val id = sessionID ?: return
        val transport = requireTransport() ?: return
        scope.launch {
            try {
                withContext(Dispatchers.IO) { transport.interrupt(id) }
            } catch (e: Exception) {
                Log.w(TAG, "abort failed: ${e.message}")
            }
        }
    }

    /**
     * Answer a pending permission ask (`once` | `always` | `reject`). The
     * request is removed optimistically and re-added when the POST fails (the
     * server's own `permission.replied` event is then the source of truth and
     * simply no-ops against the list). v1 posts `{reply}` to
     * `POST /permission/:id/reply`; v2 posts `{decision}` to the
     * session-scoped route — the [SessionTransport] hides the difference.
     */
    fun replyPermission(requestID: String, reply: String) {
        val id = sessionID
        val transport = requireTransport() ?: return
        if (_permissionResponding.value == requestID) return
        val removed = _state.value.pendingPermissions.firstOrNull { it.id == requestID } ?: return
        _permissionResponding.value = requestID
        // The local optimistic removal carries the same `reply` the POST sends,
        // so the E3 record (`ChatState.permissionReplies`) is already correct
        // before — and instead of — the server's own `permission.replied`.
        _state.value = ChatStore.reduce(_state.value, ChatStore.Event.PermissionReplied(requestID, reply))
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (id != null) {
                        transport.replyPermission(id, requestID, reply)
                    } else {
                        clientRef.get()?.replyPermission(requestID, reply, directory)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "permission reply failed: ${e.message}")
                // Roll back the optimistic removal so the dock reappears.
                applyEvent(ChatStore.Event.PermissionAsked(removed))
            } finally {
                _permissionResponding.value = null
            }
        }
    }

    /**
     * Wave 1 v2: answer a pending form (`POST …/form/{formID}/reply{answer}`).
     * Same optimistic-removal contract as [replyPermission]: the server's own
     * `form.replied` event no-ops against the list, a failed POST rolls the
     * form back so the dock reappears. v1 servers have no forms (no-op).
     */
    fun replyForm(formID: String, answer: Map<String, Any?>) {
        val id = sessionID ?: return
        val transport = transportRef.get() as? V2Transport ?: return
        if (_questionResponding.value == formID) return
        val removed = _state.value.pendingForms.firstOrNull { it.id == formID } ?: return
        _questionResponding.value = formID
        _state.value = ChatStore.reduce(_state.value, ChatStore.Event.FormResolved(formID))
        scope.launch {
            try {
                withContext(Dispatchers.IO) { transport.replyForm(id, formID, answer) }
            } catch (e: Exception) {
                Log.w(TAG, "form reply failed: ${e.message}")
                applyEvent(ChatStore.Event.FormCreated(removed))
            } finally {
                _questionResponding.value = null
            }
        }
    }

    /**
     * Wave 2.4a: dismiss a pending form (`DELETE …/form/{formID}`, the
     * documented `session.form.cancel` route — "Cancel a pending form").
     *
     * Same optimistic-removal contract as [replyForm]: the server's own
     * `form.cancelled` event no-ops against the list, a failed DELETE rolls
     * the form back so the dock reappears. Wave 1 kept dismissal local-only
     * (`V2Transport.dismissForm`) after measuring a 409 — but that probe ran
     * *after* a reply, i.e. against an already-settled form
     * (`FormAlreadySettledError`); cancelling a *pending* form is the route's
     * documented purpose and what upstream's question dock calls on Dismiss.
     * v1 servers have no forms (no-op).
     */
    fun dismissForm(formID: String) {
        val id = sessionID ?: return
        if (activeGeneration != ServerGeneration.V2) return
        if (_questionResponding.value == formID) return
        val removed = _state.value.pendingForms.firstOrNull { it.id == formID } ?: return
        _questionResponding.value = formID
        _state.value = ChatStore.reduce(_state.value, ChatStore.Event.FormResolved(formID))
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val server = serverConfig.server
                        ?: throw IllegalStateException("no server")
                    val request = com.xiufeigo.opencodeshell.ui.session.dock.SessionFormModel
                        .formCancelRequest(server.url, id, formID, server.username, server.password)
                        ?: throw IllegalStateException("bad server url")
                    sharedClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            throw IllegalStateException("cancel form: HTTP ${response.code}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "form dismiss failed: ${e.message}")
                applyEvent(ChatStore.Event.FormCreated(removed))
            } finally {
                _questionResponding.value = null
            }
        }
    }

    /**
     * Answer a pending question ask (`POST /question/:id/reply`, body
     * `{answers: string[][]}` — one label array per question, in question
     * order). Same optimistic-removal contract as [replyPermission]: the
     * server's own `question.replied` event no-ops against the list, a failed
     * POST rolls the request back so the dock reappears.
     */
    fun replyQuestion(requestID: String, answers: List<List<String>>) {
        val client = clientRef.get() ?: return
        if (_questionResponding.value == requestID) return
        val removed = _state.value.pendingQuestions.firstOrNull { it.id == requestID } ?: return
        _questionResponding.value = requestID
        // Same rule as [replyPermission]: the optimistic removal already carries
        // the answer matrix the POST is sending (E3 display data).
        _state.value = ChatStore.reduce(_state.value, ChatStore.Event.QuestionResolved(requestID, answers))
        scope.launch {
            try {
                withContext(Dispatchers.IO) { client.replyQuestion(requestID, answers, directory) }
            } catch (e: Exception) {
                Log.w(TAG, "question reply failed: ${e.message}")
                applyEvent(ChatStore.Event.QuestionAsked(removed))
            } finally {
                _questionResponding.value = null
            }
        }
    }

    /** Dismiss a pending question ask (`POST /question/:id/reject`). */
    fun rejectQuestion(requestID: String) {
        val client = clientRef.get() ?: return
        if (_questionResponding.value == requestID) return
        val removed = _state.value.pendingQuestions.firstOrNull { it.id == requestID } ?: return
        _questionResponding.value = requestID
        // A dismissal is a distinct outcome from "answered with nothing", so the
        // record marks it (E3 `QuestionResolution.rejected`).
        _state.value = ChatStore.reduce(_state.value, ChatStore.Event.QuestionResolved(requestID, rejected = true))
        scope.launch {
            try {
                withContext(Dispatchers.IO) { client.rejectQuestion(requestID, directory) }
            } catch (e: Exception) {
                Log.w(TAG, "question reject failed: ${e.message}")
                applyEvent(ChatStore.Event.QuestionAsked(removed))
            } finally {
                _questionResponding.value = null
            }
        }
    }

    fun searchFiles(query: String, onResult: (List<String>) -> Unit) {
        val client = clientRef.get() ?: return
        // Launch straight on IO: while a turn streams, 3 s snapshot merges can
        // saturate the main thread (huge message re-layout), and a Main-launched
        // coroutine queues behind them — the mention search then fires minutes
        // late or dies to the composer's 4 s latch timeout.
        scope.launch(Dispatchers.IO) {
            val results = try {
                client.findFiles(query)
            } catch (_: Exception) {
                emptyList()
            }
            onResult(results)
        }
    }

    /**
     * Directory-scoped variant for the new-session draft: before the session
     * exists the tracked directory is null, so the draft passes its picked
     * project explicitly and the `@` mention search hits the right workspace.
     */
    fun searchFiles(query: String, directory: String?, onResult: (List<String>) -> Unit) {
        val client = clientRef.get() ?: return
        scope.launch(Dispatchers.IO) {
            val results = try {
                client.findFiles(query, directory)
            } catch (_: Exception) {
                emptyList()
            }
            onResult(results)
        }
    }

    /**
     * M2 addition: rename the tracked (or any) session and, on success, mirror
     * it into [currentTitle] so the sticky header updates without a
     * `listSessions()` round-trip. Wave 1: routes through the transport
     * (`PATCH /session/:id` v1, `PATCH /api/session/:id` v2 — both 204/200).
     * Read-only with respect to every other method: no state machine, no
     * stream touched.
     */
    fun renameSession(id: String, title: String, onResult: (Boolean) -> Unit = {}) {
        val transport = requireTransport()
        val trimmed = title.trim()
        if (transport == null || id.isEmpty() || trimmed.isEmpty()) {
            onResult(false)
            return
        }
        scope.launch {
            val renamed = try {
                withContext(Dispatchers.IO) { transport.renameSession(id, trimmed) }
            } catch (e: Exception) {
                Log.w(TAG, "rename session failed: ${e.message}")
                null
            }
            if (renamed != null && id == sessionID) _currentTitle.value = renamed
            onResult(renamed != null)
        }
    }

    /**
     * Delete a session. [onResult] reports the server's
     * answer and never throws, so a failed delete leaves the row in place
     * instead of crashing the sheet. [directory] defaults to the tracked
     * session's workspace — pass the row's own directory when deleting a
     * session from another one (v1 only; v2 sessions are self-scoped).
     *
     * Deleting the **tracked** session also resets the screen to the
     * pre-session state: the id no longer exists server-side, so any later send
     * would 404. The caller is expected to navigate home afterwards.
     */
    fun deleteSession(id: String, directory: String? = null, onResult: (Boolean) -> Unit = {}) {
        val transport = requireTransport()
        if (transport == null || id.isEmpty()) {
            onResult(false)
            return
        }
        scope.launch {
            val deleted = try {
                withContext(Dispatchers.IO) { transport.deleteSession(id) }
            } catch (e: Exception) {
                Log.w(TAG, "delete session failed: ${e.message}")
                false
            }
            if (deleted && id == sessionID) newSession()
            onResult(deleted)
        }
    }

    // ------------------------------------------------------- W6 session actions
    //
    // The six writes behind the ⋮ menu's session rows and the fork picker. Each
    // is "run one `OpenCodeClient.session*` write in the same shape as every
    // other mutation here" (coroutine on [scope], state refreshed from the
    // response, failure logged and reported through `onResult` — never thrown),
    // and each one refreshes `_revertMessageID` / `_shareUrl` from the returned
    // `Session.Info`, which the server documents as authoritative for that write.

    /**
     * Adopt the three `Session.Info` bits the ⋮ menu acts on from a payload the
     * server just answered with (every `OpenCodeClient.session*` write and
     * [refreshSnapshot]'s `GET /session/:id`).
     *
     * Revert is taken verbatim: the server stores `revert.messageID` and answers
     * `POST /revert` with it set, `POST /unrevert` with it gone (verified live on
     * 1.18.31) — including the case where 撤销 named a message the server does
     * not know, which answers `200` **without** `revert` and is therefore a
     * no-op that must not leave a phantom boundary on screen.
     *
     * Share is only adopted while this screen has not unshared the session
     * locally ([shareRevoked]): `DELETE /session/:id/share` keeps reporting the
     * old `share.url` in its response *and* in every later `GET /session/:id`, so
     * the payload alone can never turn the row back into 分享会话.
     */
    fun applySessionInfo(info: OpenCodeClient.Session) {
        _revertMessageID.value = info.revertMessageID
        if (!shareRevoked) _shareUrl.value = info.shareUrl
    }

    /**
     * 撤销本轮变更 (`session/revert.ts:92-99`): rewind the revert boundary one
     * user message, computed by `SessionActionsModel.undoStep` over the **raw**
     * user-message list (the boundary is an index into that list, so the visible
     * projection must never be fed to it). Nothing to rewind → no request, and
     * `onResult(false)`; the menu row is disabled in exactly that state anyway
     * (`SessionActionsModel.menu`).
     *
     * [onResult] reports "finished without error": `false` is a failed or
     * impossible write, while a boundary with no earlier user message is a
     * legitimate no-op that reports `true` — nothing failed, so the UI must not
     * raise a failure toast for it (that state is reachable when the boundary is
     * older than the snapshot window, in which case `undoStep` is `null`).
     */
    fun undoSession(onResult: (Boolean) -> Unit = {}) {
        val transport = requireTransport()
        val id = sessionID
        val target = SessionActionsModel.undoStep(
            SessionActionsModel.userMessageIDs(_state.value.messages),
            _revertMessageID.value,
        )
        if (transport == null || id.isNullOrEmpty()) {
            onResult(false)
            return
        }
        if (target == null) {
            onResult(true)
            return
        }
        scope.launch {
            interruptIfRunning(id)
            val boundary = try {
                withContext(Dispatchers.IO) { transport.stageRevert(id, target) }
            } catch (e: Exception) {
                Log.w(TAG, "revert failed: ${e.message}")
                null
            }
            if (boundary != null) _revertMessageID.value = boundary
            onResult(boundary != null)
        }
    }

    /**
     * 重做变更 (`session/revert.ts:101-119`): move the boundary one user message
     * forward, or — past the last user message — clear it with
     * `POST /session/:id/unrevert` (`revert.clear`). `SessionActionsModel.redoStep`
     * owns which of the two the current boundary means.
     *
     * [onResult]: see [undoSession] — `null` step (nothing reverted, or a
     * boundary outside the snapshot window) is a no-op reported as `true`.
     */
    fun redoSession(onResult: (Boolean) -> Unit = {}) {
        val transport = requireTransport()
        val id = sessionID
        val step = SessionActionsModel.redoStep(
            SessionActionsModel.userMessageIDs(_state.value.messages),
            _revertMessageID.value,
        )
        if (transport == null || id.isNullOrEmpty()) {
            onResult(false)
            return
        }
        if (step == null) {
            onResult(true)
            return
        }
        scope.launch {
            // Only the two "move the boundary" writes interrupt; `revert.clear`
            // (`RevertStep.Unrevert`) does not upstream either.
            if (step is RevertStep.RevertTo) interruptIfRunning(id)
            val ok = try {
                withContext(Dispatchers.IO) {
                    when (step) {
                        is RevertStep.RevertTo -> transport.stageRevert(id, step.messageID) != null
                        RevertStep.Unrevert -> {
                            transport.clearRevert(id)
                            _revertMessageID.value = null
                            true
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "redo failed: ${e.message}")
                false
            }
            // v1 applies reverts immediately (no commit step); v2 needs the
            // commit to make a staged boundary permanent.
            if (ok && step is RevertStep.RevertTo && transport.generation == ServerGeneration.V2) {
                try {
                    withContext(Dispatchers.IO) { transport.commitRevert(id) }
                } catch (e: Exception) {
                    Log.w(TAG, "revert commit failed: ${e.message}")
                    onResult(false)
                    return@launch
                }
                _revertMessageID.value = step.messageID
            } else if (ok && step is RevertStep.RevertTo) {
                _revertMessageID.value = transport.let {
                    // v1 returns the boundary from the write; re-read cheaply
                    // via stageRevert's return (already the messageID).
                    step.messageID
                }
            }
            onResult(ok)
        }
    }

    /**
     * `revert.ts:54-56`: `stage()` interrupts a running turn before it moves the
     * boundary — the revert rewinds a history the session must not still be
     * writing to. Best effort, exactly like upstream's
     * `.catch(() => undefined)`: a failed interrupt must not block the revert.
     */
    private suspend fun interruptIfRunning(id: String) {
        if (_state.value.runStatus != ChatStore.RunStatus.RUNNING) return
        val transport = requireTransport() ?: return
        try {
            withContext(Dispatchers.IO) { transport.interrupt(id) }
        } catch (e: Exception) {
            Log.w(TAG, "interrupt before revert failed: ${e.message}")
        }
    }

    /** Legacy overload (v1 callers); prefer [interruptIfRunning]`(id)`. */
    private suspend fun interruptIfRunning(client: OpenCodeClient, id: String) {
        if (_state.value.runStatus != ChatStore.RunStatus.RUNNING) return
        try {
            withContext(Dispatchers.IO) { client.abort(id) }
        } catch (e: Exception) {
            Log.w(TAG, "interrupt before revert failed: ${e.message}")
        }
    }

    /**
     * The fork picker's rows: user messages of the **raw** transcript, newest
     * first, single-lined and clipped (`fork-dialog.tsx:38-56` via
     * `SessionActionsModel.forkMessages`). Read from `_state`, not from [state]:
     * upstream builds the list from `data.session.message.list(sessionID)`, so a
     * message the revert currently hides is still a legal fork point.
     */
    fun forkMessages(): List<ForkMessage> = SessionActionsModel.forkMessages(_state.value.messages)

    /**
     * 分叉会话 (`fork-dialog.tsx:71-89`): the fork is cut **before** [before]
     * (null = the whole session as it stands). v1 posts `{messageID?}`; v2
     * posts `{before?}` — the [SessionTransport] hides the rename. The new
     * session's id is published through [forkedSessionID] for the shell to
     * navigate to (`navigate(sessionHref(server.key, forked.id))`) — the
     * server records no parent link, so the id is the only route to it.
     */
    fun forkSession(before: String? = null, onResult: (String?) -> Unit = {}) {
        val transport = requireTransport()
        val id = sessionID
        if (transport == null || id.isNullOrEmpty()) {
            onResult(null)
            return
        }
        scope.launch {
            val forkedID = try {
                withContext(Dispatchers.IO) { transport.forkSession(id, before) }
            } catch (e: Exception) {
                Log.w(TAG, "fork failed: ${e.message}")
                null
            }
            if (forkedID != null) {
                _forkedSessionID.value = forkedID
                // The fork is a new row in `GET /session`; let the open lists
                // (Home, the drawer) know they are stale.
                _sessionsRevision.value += 1
            }
            onResult(forkedID)
        }
    }

    /**
     * Takes the session [forkSession] just created, clearing it in the same
     * breath so one fork navigates exactly once (`consumeSessionID` semantics).
     */
    fun consumeForkedSession(): String? = _forkedSessionID.value?.also { _forkedSessionID.value = null }

    /**
     * 分享会话 (`POST /session/:id/share`): publishes the session and reports
     * the `share.url` to copy (`session_share_copy_copyLink`). A successful share
     * also clears [shareRevoked] — see [unshareSession].
     *
     * Wave 2.4a: v1-only (`V2Capability` `session.share` is V1_ONLY — v2 has no
     * share route). On a v2 server this reports `null` without touching the
     * network; the UI hides the row on `generation == V1`
     * (`SessionActionsModel.menu`), so this guard is the backstop for palette /
     * dialog paths that bypass the menu.
     */
    fun shareSession(onResult: (String?) -> Unit = {}) {
        if (activeGeneration != ServerGeneration.V1) {
            onResult(null)
            return
        }
        val client = clientRef.get()
        val id = sessionID
        if (client == null || id.isNullOrEmpty()) {
            onResult(null)
            return
        }
        scope.launch {
            val info = try {
                withContext(Dispatchers.IO) { client.sessionShare(id, directory) }
            } catch (e: Exception) {
                Log.w(TAG, "share failed: ${e.message}")
                null
            }
            if (info != null) {
                shareRevoked = false
                _shareUrl.value = info.shareUrl
            }
            onResult(info?.shareUrl)
        }
    }

    /**
     * 取消分享 (`DELETE /session/:id/share`).
     *
     * The **response cannot be trusted**: measured live on 1.18.31, the DELETE
     * answers `200` and still carries the previous `share.url` (and so does every
     * later `GET /session/:id`). A successful delete therefore clears [_shareUrl]
     * here and remembers the revocation in [shareRevoked], which is what keeps
     * `SessionActionsModel.menu(...).shared` — and thus the ⋮ row's label —
     * truthful until the user shares again.
     */
    fun unshareSession(onResult: (Boolean) -> Unit = {}) {
        // Wave 2.4a: v1-only, same guard as [shareSession] (no unshare route on v2).
        if (activeGeneration != ServerGeneration.V1) {
            onResult(false)
            return
        }
        val client = clientRef.get()
        val id = sessionID
        if (client == null || id.isNullOrEmpty()) {
            onResult(false)
            return
        }
        scope.launch {
            val ok = try {
                withContext(Dispatchers.IO) { client.sessionUnshare(id, directory) }
                true
            } catch (e: Exception) {
                Log.w(TAG, "unshare failed: ${e.message}")
                false
            }
            if (ok) {
                shareRevoked = true
                _shareUrl.value = null
            }
            onResult(ok)
        }
    }

    /**
     * 归档会话: `PATCH /session/:id {"time":{"archived": now}}`
     * (`use-session-commands.tsx` archive). The server does **not** filter the
     * row out of `GET /session` (verified live), so the disposal is client-side —
     * `HomeSessions.isArchived` drops it from Home's list — and this call bumps
     * [sessionsRevision] so an open list re-reads.
     *
     * [id] defaults to the tracked session; the caller is expected to leave the
     * session screen (`SessionActionsHost`), like the delete flow.
     */
    fun archiveSession(id: String? = sessionID, onResult: (Boolean) -> Unit = {}) {
        // Wave 2.4a: v1-only (`V2Capability` `session.archive` is V1_ONLY —
        // v2 carries `time.archived` read-only, no archive write route).
        if (activeGeneration != ServerGeneration.V1) {
            onResult(false)
            return
        }
        val client = clientRef.get()
        val target = id
        if (client == null || target.isNullOrEmpty()) {
            onResult(false)
            return
        }
        scope.launch {
            val info = try {
                withContext(Dispatchers.IO) {
                    client.sessionArchive(target, System.currentTimeMillis(), directory)
                }
            } catch (e: Exception) {
                Log.w(TAG, "archive failed: ${e.message}")
                null
            }
            if (info != null) _sessionsRevision.value += 1
            onResult(info != null)
        }
    }

    /**
     * Creates a session and observes it.
     *     * [directory] scopes the creation (v1: `POST /session?directory=`;
     * v2: `location.directory` body) — the new-session draft's project picker;
     * null keeps the server cwd (the pre-draft behaviour).
     * [firstPrompt], when non-blank, is sent immediately after the session is
     * observed — the draft-page flow "create in project, then send" — so the
     * user lands on an already-running turn. Creation failures are reported to
     * [onError] (draft error card) in addition to the store's run status; the
     * legacy single-callback call keeps compiling unchanged.
     */
    fun createSession(
        directory: String? = null,
        firstPrompt: String? = null,
        attachments: List<Attachment> = emptyList(),
        onError: (String) -> Unit = {},
        onReady: (String) -> Unit,
    ) {
        val transport = requireTransport() ?: run {
            requireClient()
            return
        }
        scope.launch {
            try {
                val newID = withContext(Dispatchers.IO) { transport.createSession(directory, null) }
                // Directory: v2 sessions report location.directory on fetch;
                // observe optimistically with the requested directory first.
                observeSession(id = newID, directory = directory)
                if (!firstPrompt.isNullOrBlank()) send(firstPrompt, attachments)
                onReady(newID)
            } catch (e: Exception) {
                Log.w(TAG, "create session failed: ${e.message}")
                _state.value = ChatStore.reduce(
                    _state.value,
                    ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.ERROR, e.message),
                )
                onError(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /**
     * `GET /project` read for Home's projects column and the draft page's
     * project selector. Read-only against the shared REST client (no SSE, no
     * session needed — pair it with [ensureClient]). `null` = fetch failed, so
     * callers can fall back to their derived/offline presentation instead of
     * mistaking a failure for an empty server.
     */
    fun listProjects(onResult: (List<OpenCodeClient.Project>?) -> Unit) {
        val client = clientRef.get()
        if (client == null) {
            onResult(null)
            return
        }
        scope.launch {
            val projects = try {
                withContext(Dispatchers.IO) { client.projects() }
            } catch (e: Exception) {
                Log.w(TAG, "project list failed: ${e.message}")
                null
            }
            onResult(projects)
        }
    }

    /**
     * The server's cwd (`GET /path` → `directory`): the draft's default
     * location and the directory browser's initial path. `null` on failure.
     */
    fun serverDirectory(onResult: (String?) -> Unit) {
        val client = clientRef.get()
        if (client == null) {
            onResult(null)
            return
        }
        scope.launch {
            val directory = try {
                withContext(Dispatchers.IO) { client.serverPath() }
                    .optString("directory", "")
                    .takeIf { it.isNotEmpty() }
            } catch (e: Exception) {
                Log.w(TAG, "path fetch failed: ${e.message}")
                null
            }
            onResult(directory)
        }
    }

    /**
     * `GET /file?path=` for the add-project directory browser. `null` on
     * failure; the dialog keeps showing the current path and offers a retry.
     */
    fun listFiles(path: String, onResult: (List<OpenCodeClient.FileNode>?) -> Unit) {
        val client = clientRef.get()
        if (client == null) {
            onResult(null)
            return
        }
        scope.launch(Dispatchers.IO) {
            val nodes = try {
                client.listFiles(path)
            } catch (e: Exception) {
                Log.w(TAG, "file list failed: ${e.message}")
                null
            }
            withContext(Dispatchers.Main) { onResult(nodes) }
        }
    }

    // ---- review panel (changes / files side panel) --------------------------
    //
    // `session.diff` / `todo.updated` SSE events already flow through the
    // generic `ChatStore.parseEvent` → `applyEvent` path in [start]; these are
    // the REST side: an explicit pull when the panel opens (same pattern as
    // [refreshPermissions]) plus the lazy file-tree reads.

    /**
     * `GET /session/:id/diff` pull for the review panel. Failure is silent —
     * the SSE `session.diff` events keep driving the list. The result is only
     * applied while the same session is still tracked (a switch mid-flight
     * must not write another session's diff into the fresh state).
     */
    fun refreshDiff() {
        val id = sessionID ?: return
        val client = clientRef.get() ?: return
        scope.launch {
            val diff = try {
                withContext(Dispatchers.IO) { client.sessionDiff(id, directory = directory) }
            } catch (e: Exception) {
                Log.w(TAG, "session diff failed: ${e.message}")
                null
            } ?: return@launch
            if (sessionID == id) applyEvent(ChatStore.Event.SessionDiff(id, diff))
        }
    }

    /**
     * `GET /session/:id/todo` pull, same pattern as [refreshDiff].
     *
     * Wave 2.4a: v1-only (`V2Capability` `session.todo` is V1_ONLY — v2 has no
     * todo route; todos derive from todowrite tool state in the transcript).
     */
    fun refreshTodos() {
        if (activeGeneration != ServerGeneration.V1) return
        val id = sessionID ?: return
        val client = clientRef.get() ?: return
        scope.launch {
            val todos = try {
                withContext(Dispatchers.IO) { client.sessionTodos(id, directory = directory) }
            } catch (e: Exception) {
                Log.w(TAG, "session todos failed: ${e.message}")
                null
            } ?: return@launch
            if (sessionID == id) applyEvent(ChatStore.Event.TodoUpdated(id, todos))
        }
    }

    /**
     * `GET /vcs` for the review panel header (current branch). `null` on
     * failure — the header simply hides the branch chip.
     */
    fun reviewVcsInfo(onResult: (OpenCodeClient.VcsInfo?) -> Unit) {
        val client = clientRef.get()
        if (client == null) {
            onResult(null)
            return
        }
        scope.launch(Dispatchers.IO) {
            val info = try {
                client.vcsInfo(directory)
            } catch (e: Exception) {
                Log.w(TAG, "vcs info failed: ${e.message}")
                null
            }
            withContext(Dispatchers.Main) { onResult(info) }
        }
    }

    /**
     * `GET /vcs/diff?mode=` for the review panel's Git / Branch scopes
     * (upstream `vcs.diff({mode})`, `session/review/model.ts:23`). [mode] is
     * `"git"` or `"branch"` (`working` is rejected by the server with 400).
     * `null` on failure so the panel can keep the previous list.
     */
    fun reviewVcsDiff(mode: String, onResult: (List<OpenCodeClient.FileDiff>?) -> Unit) {
        val client = clientRef.get()
        if (client == null) {
            onResult(null)
            return
        }
        scope.launch(Dispatchers.IO) {
            val diffs = try {
                client.vcsDiff(mode, directory)
            } catch (e: Exception) {
                Log.w(TAG, "vcs diff failed: ${e.message}")
                null
            }
            withContext(Dispatchers.Main) { onResult(diffs) }
        }
    }

    /**
     * Review file tree read: like [listFiles] but scoped to the tracked
     * session's `directory` (the picker's variant intentionally stays
     * unscoped). `null` on failure.
     */
    fun reviewListFiles(path: String, onResult: (List<OpenCodeClient.FileNode>?) -> Unit) {
        val client = clientRef.get()
        if (client == null) {
            onResult(null)
            return
        }
        scope.launch(Dispatchers.IO) {
            val nodes = try {
                client.listFiles(path, directory)
            } catch (e: Exception) {
                Log.w(TAG, "review file list failed: ${e.message}")
                null
            }
            withContext(Dispatchers.Main) { onResult(nodes) }
        }
    }

    /**
     * `GET /file/content?path=` for the review panel's file preview. `null`
     * on failure; [OpenCodeClient.FileContent.binary] entries carry no text.
     */
    fun reviewFileContent(path: String, onResult: (OpenCodeClient.FileContent?) -> Unit) {
        val client = clientRef.get()
        if (client == null) {
            onResult(null)
            return
        }
        scope.launch(Dispatchers.IO) {
            val content = try {
                client.fileContent(path, directory)
            } catch (e: Exception) {
                Log.w(TAG, "review file content failed: ${e.message}")
                null
            }
            withContext(Dispatchers.Main) { onResult(content) }
        }
    }

    private fun refreshSnapshot() {
        if (_sessionMissing.value) return
        val id = sessionID ?: return
        val transport = requireTransport() ?: return
        lastSnapshotAt = System.currentTimeMillis()
        refreshPermissions()
        refreshQuestions()
        refreshForms()
        scope.launch {
            try {
                // Recent window only (SNAPSHOT_MESSAGE_LIMIT): the server answers
                // with the newest N messages and the store merges by upsert, so
                // the cap bounds the transfer/parse cost without losing history
                // already on screen.
                val messages = withContext(Dispatchers.IO) {
                    transport.snapshotMessages(id, SNAPSHOT_MESSAGE_LIMIT)
                }
                // Chip default-label fallback (upstream selection.tsx: scope →
                // session → agent → configured): the configured default may be
                // absent — then the latest assistant turn's own model is
                // the truth the server is actually running, so the chip shows
                // it instead of the generic「模型」placeholder.
                if (modelOverride == null && serverDefaultModel == null) {
                    messages.lastOrNull { it.role == "assistant" && it.model != null }
                        ?.let { latest ->
                            OpenCodeClient.parseModelRef(latest.model)?.let { ref ->
                                serverDefaultModel = ref
                                _selectedModel.value = ref
                                syncModelLabel()
                            }
                        }
                }
                applyEvent(ChatStore.Event.Snapshot(messages))
                // The server renames sessions in the background (title agent);
                // the snapshot is the cheap moment to pick that up — the header
                // otherwise keeps whatever title the row/draft carried in.
                val info = withContext(Dispatchers.IO) {
                    try {
                        transport.fetchSession(id)
                    } catch (_: Exception) {
                        null
                    }
                }
                if (info != null && sessionID == id) {
                    // v1 rows carry revert/share bits via applySessionInfo;
                    // v2 rows have neither (no share/archive routes).
                    if (transport.generation == ServerGeneration.V1) {
                        val client = clientRef.get()
                        if (client != null) {
                            try {
                                applySessionInfo(withContext(Dispatchers.IO) { client.session(id) })
                            } catch (_: Exception) {
                            }
                        }
                    }
                    if (!info.title.isNullOrBlank()) {
                        _currentTitle.value = info.title
                    }
                    if (!info.directory.isNullOrBlank()) {
                        directory = info.directory
                        _currentDirectory.value = info.directory
                    }
                    // Session cost; null clears a stale figure when the
                    // server stops reporting one.
                    _sessionCost.value = info.cost
                    if (info.agent != null && _selectedAgent.value == null) {
                        // Adopt the session's agent for display only when the
                        // user pinned none (v2 reports it on every fetch).
                    }
                }
                // session.idle can be missed while backgrounded; after a
                // reconnect snapshot with no part traffic in the grace window,
                // drop back to IDLE so the send button is not stuck on abort.
                // But a long foreground tool wait emits NO part traffic either
                // (measured 2026-09-22: one tool.progress at call start, then
                // silence until tool.success), so on v2 the server's own
                // active-set is the authority: a session still listed there is
                // alive — keep (or restore) RUNNING and re-arm the clock.
                val live = withContext(Dispatchers.IO) {
                    try {
                        transport.activeRunStatus(id)
                    } catch (_: Exception) {
                        null
                    }
                }
                if (live == "running" || live == "busy" || live == "retry") {
                    lastPartEventAt = System.currentTimeMillis()
                    if (_state.value.runStatus != ChatStore.RunStatus.RUNNING) {
                        applyEvent(ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.RUNNING))
                    }
                } else if (_state.value.runStatus == ChatStore.RunStatus.RUNNING) {
                    delay(IDLE_GRACE_MS)
                    if (started.get() &&
                        _state.value.runStatus == ChatStore.RunStatus.RUNNING &&
                        System.currentTimeMillis() - lastPartEventAt >= IDLE_GRACE_MS
                    ) {
                        applyEvent(ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.IDLE))
                    }
                }
            } catch (e: Exception) {
                if (isSessionGone(e)) {
                    markSessionMissing()
                } else {
                    Log.w(TAG, "snapshot failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Permission merge (additive): pending asks are session state too,
     * so the snapshot refresh carries them. Failure is silent — the SSE
     * `permission.asked` events still drive the dock. v1 reads the
     * location-scoped list; v2 reads the session view (authoritative merge).
     */
    private fun refreshPermissions() {
        if (_sessionMissing.value) return
        val transport = requireTransport() ?: return
        val id = sessionID
        scope.launch {
            val requests = try {
                withContext(Dispatchers.IO) {
                    if (id != null) {
                        transport.fetchPermissions(id)
                    } else {
                        clientRef.get()?.listPermissions(directory)
                    }
                }
            } catch (e: Exception) {
                if (isSessionGone(e)) markSessionMissing() else Log.w(TAG, "permission list failed: ${e.message}")
                null
            } ?: return@launch
            applyEvent(ChatStore.Event.PermissionSnapshot(requests))
            // Auto-approve sweep (upstream auto-approve.ts): the stream does
            // not replay asks made while disconnected, so every merged
            // still-pending request gets its bounded reply too.
            for (request in requests) autoApprove(request)
        }
    }

    /**
     * `GET /question` merge, same pattern as [refreshPermissions]. v1 only —
     * v2 has no question routes (forms replace them; see [refreshForms]).
     * Failure is silent — the SSE `question.asked` events still drive the dock.
     */
    private fun refreshQuestions() {
        if (activeGeneration == ServerGeneration.V2 || _sessionMissing.value) return
        val client = clientRef.get() ?: return
        scope.launch {
            val requests = try {
                withContext(Dispatchers.IO) { client.listQuestions(directory) }
            } catch (e: Exception) {
                if (isSessionGone(e)) markSessionMissing() else Log.w(TAG, "question list failed: ${e.message}")
                null
            } ?: return@launch
            applyEvent(ChatStore.Event.QuestionSnapshot(requests))
        }
    }

    /**
     * Wave 1 v2: `GET …/form` merge, same pattern as [refreshPermissions].
     * v2 only — v1 has no forms. Failure is silent — the SSE `form.created`
     * events still drive the dock.
     */
    private fun refreshForms() {
        if (_sessionMissing.value) return
        val transport = transportRef.get() as? V2Transport ?: return
        val id = sessionID ?: return
        scope.launch {
            val forms = try {
                withContext(Dispatchers.IO) {
                    transport.fetchForms(id).map {
                        ChatStore.FormAsk(it.id, it.sessionID, it.title, it.fields.toString())
                    }
                }
            } catch (e: Exception) {
                if (isSessionGone(e)) markSessionMissing() else Log.w(TAG, "form list failed: ${e.message}")
                null
            } ?: return@launch
            applyEvent(ChatStore.Event.FormSnapshot(forms))
        }
    }

    /**
     * Auto-approve one ask with `"once"` (setting-gated inside
     * [AutoApprover.tryMark]). No optimistic removal: the server's
     * `permission.replied` event clears the dock. A failed POST retries after
     * a short delay, bounded by the per-request attempt budget; the setting
     * being switched off mid-flight stops further retries.
     */
    private fun autoApprove(request: OpenCodeClient.PermissionRequest) {
        if (!autoApprover.tryMark(request.id)) return
        val client = clientRef.get() ?: return
        scope.launch {
            val ok = try {
                withContext(Dispatchers.IO) { client.replyPermission(request.id, "once", directory) }
                true
            } catch (e: Exception) {
                Log.w(TAG, "auto-approve failed: ${e.message}")
                false
            }
            if (!ok) {
                delay(AUTO_APPROVE_RETRY_DELAY_MS)
                autoApprove(request)
            }
        }
    }

    /** A new assistant turn bumps the session; refresh to catch tool runs. */
    private fun maybeRefreshOnSession(data: JSONObject) {
        val id = sessionID ?: return
        val tracked = ChatStore.sessionIdOf(data) ?: return
        if (tracked != id) return
        refreshSnapshotThrottled()
    }

    /**
     * Snapshot backstop shared by [maybeRefreshOnSession] and the idle settle.
     * `SNAPSHOT_THROTTLE_MS` keeps a burst of session events (or a fast
     * turn/finish cycle) from re-fetching the whole transcript repeatedly.
     */
    private fun refreshSnapshotThrottled() {
        val now = System.currentTimeMillis()
        if (now - lastSnapshotAt < SNAPSHOT_THROTTLE_MS) return
        refreshSnapshot()
    }

    // ---- E2: invalidation signals -------------------------------------------

    /**
     * What a [ChatStore.Signal] invalidates.
     *
     * Catalog signals re-read the catalog here, so the model picker / agent chip
     * update through their existing flows without re-entering the screen. The
     * rest only bumps a revision: the owning UI decides when re-reading is worth
     * it (a hidden panel must not fetch), and a stale list is refreshed by the
     * next collector rather than by this class.
     */
    private fun onServerSignal(signal: ChatStore.Event.ServerSignal) {
        when (signal.signal) {
            ChatStore.Signal.PROVIDER_UPDATED,
            ChatStore.Signal.MODEL_UPDATED,
            ChatStore.Signal.AGENT_UPDATED,
            -> catalogRefresh()

            // `config.updated` moves `config.model` (the chip's default) *and* the
            // agent list; `GET /config` is one cheap call next to the catalogs.
            ChatStore.Signal.CONFIG_UPDATED -> {
                refreshModelLabel()
                catalogRefresh()
            }

            ChatStore.Signal.PROJECT_UPDATED -> _projectsRevision.value += 1

            // `session.updated` deliberately stays out: it is a per-turn durable
            // event and already drives `maybeRefreshOnSession`'s throttled
            // snapshot, which also re-syncs the header title.
            ChatStore.Signal.SESSION_CREATED,
            ChatStore.Signal.SESSION_DELETED,
            -> _sessionsRevision.value += 1

            ChatStore.Signal.VCS_BRANCH_UPDATED -> _vcsRevision.value += 1
            ChatStore.Signal.FILE_EDITED -> _filesRevision.value += 1

            ChatStore.Signal.PTY_CREATED,
            ChatStore.Signal.PTY_UPDATED,
            ChatStore.Signal.PTY_EXITED,
            ChatStore.Signal.PTY_DELETED,
            -> _ptyRevision.value += 1
        }
    }

    /**
     * Provider + agent/command/skill catalogs, re-read at most once per
     * [CATALOG_REFRESH_THROTTLE_MS]: a config reload emits a burst of
     * `provider.updated` / `model.updated` / `agent.updated` frames and each
     * re-read costs four REST calls.
     */
    private fun catalogRefresh() {
        val now = System.currentTimeMillis()
        if (now - lastCatalogRefreshAt < CATALOG_REFRESH_THROTTLE_MS) return
        lastCatalogRefreshAt = now
        refreshProviders()
        refreshCatalogs()
    }

    // ---- E5: notifications --------------------------------------------------

    /**
     * E5 trigger mapping (`research/verify_wave4.md` §20 E5): a pending ask, a
     * failed turn, a finished turn. Fed for **any** session on the stream — the
     * feed is app-wide, while the transcript state below stays gated by
     * [ChatStore.acceptsSession]: a neighbouring session finishing is exactly
     * what the user should be told about while this screen is open.
     *
     * `session.idle` is the deprecated-but-still-emitted end of a run
     * (`session-status-event.ts:45-47`); `session.status{type:idle}` is the same
     * trigger through the newer frame. For the *tracked* session it only counts
     * when that session was actually running — the server also emits idle for
     * sessions that were already idle, and that must not raise "response ready".
     * A foreign session's run state is not tracked here, so its idle is taken at
     * face value (the per-session de-bounce absorbs a burst).
     */
    private fun notifyFor(event: ChatStore.Event, routed: String?) {
        when (event) {
            is ChatStore.Event.PermissionAsked -> raise(
                AppNotificationKind.PERMISSION,
                sessionID = event.request.sessionID.ifEmpty { routed },
                detail = event.request.permission,
            )

            is ChatStore.Event.QuestionAsked -> raise(
                AppNotificationKind.QUESTION,
                sessionID = event.request.sessionID.ifEmpty { routed },
                detail = event.request.questions.firstOrNull()?.header,
            )

            // Wave 1 v2: a pending form is the v2 question-dock carrier — same
            // notification gate as questions.
            is ChatStore.Event.FormCreated -> raise(
                AppNotificationKind.QUESTION,
                sessionID = event.form.sessionID.ifEmpty { routed },
                detail = event.form.title,
            )

            is ChatStore.Event.RunStatusChanged -> when (event.status) {
                ChatStore.RunStatus.ERROR ->
                    raise(AppNotificationKind.ERROR, sessionID = routed, detail = event.error)

                ChatStore.RunStatus.IDLE -> {
                    val foreign = routed != null && routed != sessionID
                    val finished = !foreign && _state.value.runStatus == ChatStore.RunStatus.RUNNING
                    if (foreign || finished) raise(AppNotificationKind.AGENT, sessionID = routed)
                }

                ChatStore.RunStatus.RUNNING -> Unit
            }

            else -> Unit
        }
    }

    /** One offer to [appNotifier]; the settings gate and the de-bounce live there. */
    private fun raise(kind: AppNotificationKind, sessionID: String?, detail: String? = null) {
        appNotifier.raise(
            kind = kind,
            sessionID = sessionID,
            // Only the tracked session's title is known here; a foreign session's
            // row falls back to its id, which the banner can render as-is.
            sessionTitle = sessionID?.takeIf { it == this.sessionID }?.let { _currentTitle.value },
            detail = detail,
            // `viewed`: the notification is about the session already on screen.
            viewed = sessionID != null && sessionID == this.sessionID,
        )
    }

    private suspend fun applyEvent(event: ChatStore.Event) {
        val before = _state.value.pendingPermissions
        val next = try {
            ChatStore.reduce(_state.value, event)
        } catch (_: JSONException) {
            _state.value
        }
        _state.value = next
        // Auto-approve: an ask reaching the store while the setting is on is
        // replied "once" immediately (upstream auto-approve.ts `event.on`).
        if (event is ChatStore.Event.PermissionAsked) autoApprove(event.request)
        // Attempt counters follow the pending list: ids that left it
        // (replied / rejected / merged away) are forgotten so a recycled id
        // starts with a fresh budget.
        val stillPending = next.pendingPermissions.mapTo(HashSet()) { it.id }
        for (old in before) {
            if (old.id !in stillPending) autoApprover.forget(old.id)
        }
    }

    /** Typed REST models flatten into the UI store; ids match SSE part ids. */
    private fun OpenCodeClient.Message.toUi(): ChatStore.UiMessage = ChatStore.UiMessage(
        id = id,
        role = role,
        timeCreated = timeCreated,
        parts = parts.map { part -> part.toUiPart() },
        // Additive M2 display payload for the context ring (see ChatStore).
        tokens = tokens,
        // Additive W4 display payload for the message meta line.
        agent = agent,
        model = listOfNotNull(providerID, modelID).joinToString("/").takeIf { it.isNotEmpty() },
        timeCompleted = timeCompleted,
        interrupted = interrupted,
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
            // Route C: the REST path must carry the same payload as the SSE
            // path, or parts without first-class fields (patch, subtask, agent,
            // retry, step-*, snapshot) lose their data on reload.
            rawJson = raw.takeIf { type in ChatStore.UiPart.RAW_JSON_TYPES }?.toString(),
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

        /** Silent second attempt for the provider catalog (`refreshProviders`). */
        private const val PROVIDERS_RETRY_DELAY_MS = 1_500L

        /**
         * E2 burst guard for the catalog signals (`provider.updated` /
         * `model.updated` / `agent.updated` / `config.updated`): one config
         * reload emits several frames, each re-read costs four REST calls.
         */
        private const val CATALOG_REFRESH_THROTTLE_MS = 2_000L

        /** Delay between bounded auto-approve retries (upstream 1 s backoff). */
        private const val AUTO_APPROVE_RETRY_DELAY_MS = 1_000L

        /**
         * Upper bound on the messages pulled by [refreshSnapshot]. The transcript
         * is re-fetched on every reconnect and on each throttled `session.*`
         * event, so a long session used to transfer, parse and diff its whole
         * history each time (`GET /session/:id/message` `limit` — verified on
         * opencode 1.18.31: the server answers with the **most recent** N). State
         * merging is an upsert, so messages already on screen are never dropped;
         * the cap only bounds the response.
         */
        private const val SNAPSHOT_MESSAGE_LIMIT = 200

        /**
         * Pure core of [contextLimit]: the selected model's `limit.context` in
         * the catalog, `null` while nothing is selected or the catalog has not
         * arrived. Split out so the mapping is JVM-testable without an Android
         * `ChatViewModel` instance.
         */
        fun contextLimitOf(
            providers: OpenCodeClient.Providers?,
            selected: Pair<String, String>?,
        ): Int? {
            val (providerID, modelID) = selected ?: return null
            return providers?.model(providerID, modelID)?.limitContext
        }
    }
}
