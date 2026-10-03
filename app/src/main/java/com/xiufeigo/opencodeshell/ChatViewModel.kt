package com.xiufeigo.opencodeshell

import android.util.Log
import com.xiufeigo.opencodeshell.data.ProjectPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import com.xiufeigo.opencodeshell.notify.AppNotification
import com.xiufeigo.opencodeshell.notify.AppNotificationKind
import com.xiufeigo.opencodeshell.notify.AppNotifier
import com.xiufeigo.opencodeshell.notify.NotificationDelivery
import com.xiufeigo.opencodeshell.notify.NotificationPersistence
import com.xiufeigo.opencodeshell.notify.SessionOwnership
import com.xiufeigo.opencodeshell.notify.TurnRunStatus
import com.xiufeigo.opencodeshell.v2.DirEntry
import com.xiufeigo.opencodeshell.v2.GenerateFailure
import com.xiufeigo.opencodeshell.v2.NoModelException
import com.xiufeigo.opencodeshell.v2.RenameResult
import com.xiufeigo.opencodeshell.v2.ServerGeneration
import com.xiufeigo.opencodeshell.v2.SessionModelSync
import com.xiufeigo.opencodeshell.v2.SessionOpenFailure
import com.xiufeigo.opencodeshell.v2.SessionRenameOutcome
import com.xiufeigo.opencodeshell.v2.SessionRow
import com.xiufeigo.opencodeshell.v2.SessionTransport
import com.xiufeigo.opencodeshell.v2.TransportFailure
import com.xiufeigo.opencodeshell.v2.TransportRetry
import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.V2Capability
import com.xiufeigo.opencodeshell.v2.V2EventAdapter
import com.xiufeigo.opencodeshell.v2.V2SeqTracker
import com.xiufeigo.opencodeshell.v2.V2Transport
import com.xiufeigo.opencodeshell.v2.evictDebugLocation
import com.xiufeigo.opencodeshell.v2.mcpResourceCatalog
import com.xiufeigo.opencodeshell.v2.optStringOrNull
import com.xiufeigo.opencodeshell.v2.sessionOpenFailure
import com.xiufeigo.opencodeshell.ui.debug.ProviderMetricAccumulator
import com.xiufeigo.opencodeshell.ui.debug.ProviderMetrics
import com.xiufeigo.opencodeshell.ui.debug.providerMetricEventOf
import com.xiufeigo.opencodeshell.ui.session.actions.ForkMessage
import com.xiufeigo.opencodeshell.ui.session.actions.RevertStep
import com.xiufeigo.opencodeshell.ui.session.actions.SessionActionsModel
import com.xiufeigo.opencodeshell.ui.session.composer.ComposerDrafts
import com.xiufeigo.opencodeshell.ui.session.composer.ComposerHistory
import com.xiufeigo.opencodeshell.ui.session.composer.ComposerQueue
import com.xiufeigo.opencodeshell.ui.session.composer.ComposerSubmission
import com.xiufeigo.opencodeshell.ui.session.composer.MentionSources
import com.xiufeigo.opencodeshell.ui.session.composer.ModelPickerItems
import com.xiufeigo.opencodeshell.ui.session.composer.ComposerSubmission.mimeOfDataUrl
import com.xiufeigo.opencodeshell.ui.session.composer.ComposerSubmission.toDraftAttachment
import com.xiufeigo.opencodeshell.ui.session.composer.toDraft
import com.xiufeigo.opencodeshell.ui.session.context.SessionContextModel
import com.xiufeigo.opencodeshell.ui.session.review.ReviewCommentMetadata
import com.xiufeigo.opencodeshell.ui.session.review.ReviewNote
import com.xiufeigo.opencodeshell.ui.session.timeline.TimelineComments
import com.xiufeigo.opencodeshell.ui.session.dock.WebSearchOption
import com.xiufeigo.opencodeshell.ui.session.export.SessionExport
import com.xiufeigo.opencodeshell.ui.session.header.ContextUsage
import com.xiufeigo.opencodeshell.ui.session.review.ReviewVcsDiffResult
import com.xiufeigo.opencodeshell.ui.session.review.ReviewVcsProbePlan
import com.xiufeigo.opencodeshell.ui.session.review.ReviewVcsReprobe
import com.xiufeigo.opencodeshell.ui.session.summary.SessionSummaryModel
import com.xiufeigo.opencodeshell.v2.v2VcsDiffMode
import com.xiufeigo.opencodeshell.v2.v2VcsInfoRow
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * A transport-scoped read that never reached the wire: the server is not
 * configured, or its probe has not confirmed v2 yet ([ChatViewModel] answers
 * those with `null` from every seam).
 *
 * The **message is deliberately null**. Callers that show `cause.message`
 * verbatim (the session screen's export report, the context tab's
 * `contextExportError`) fall back to their own copy for a message-less cause,
 * exactly as they did when this was the reason-less `Result.success(null)` —
 * so naming the failure by type keeps every existing fallback intact while
 * letting the callers that care (`HomeRowExportSaver`) report it precisely.
 */
class TransportUnavailableException : IllegalStateException()

/**
 * The v2 frame router's session gate: a frame may only drive **this** session's
 * state when its owner matches the tracked session.
 *
 * [routed] is `V2EventAdapter.sessionID` — `data.sessionID`, else the durable
 * aggregate, else `form.sessionID`. Every session-scoped v2 event names its
 * owner that way (`_upstream/packages/schema/src/session-event.ts:41-43`,
 * `Base = {sessionID}` with `durable.aggregate = "sessionID"`), so a `null`
 * [routed] is not a global frame about the tracked session: it is a frame the
 * shell cannot attribute, on a global stream that carries every session.
 *
 * Treating it as a pass (the old `if (routed == null) return true`) let another
 * session's unattributed `session.status` / `session.idle` frame settle the
 * tracked session's RUNNING turn as IDLE — the same cross-talk the routed
 * comparison already prevents, just through the one field that has no value.
 * Genuinely global frames are unaffected: they arrive as
 * `ChatStore.Event.ServerSignal`, which [ChatViewModel.onV2Frame] consumes
 * *before* this gate, and none of them adapts to a session-scoped event.
 *
 * Pure, so it is covered by a JVM test.
 */
internal fun acceptsV2SessionFrame(currentID: String?, routed: String?): Boolean =
    currentID != null && currentID == routed

/**
 * What a network-return signal should do about the connection. Pure, so it is
 * covered by a JVM test.
 */
internal enum class NetworkHealAction {
    /** Leave everything alone (no server, or a definitive negative verdict). */
    NOTHING,

    /** The transport is alive: re-dial both SSE streams now. */
    POKE_STREAMS,

    /** No transport and no definitive verdict: run the generation probe now. */
    REPROBE,
}

/**
 * The network is back ([NetworkHealAction]): [serverComplete] is whether a
 * usable server row exists, [transportPresent] whether the probe installed one,
 * [lastProbeRetryable] the last verdict's [V2Capability.ProbeResult.isRetryable]
 * (null = no verdict yet).
 *
 * A definitive "up, but not 2.x" verdict never re-probes — the host answered,
 * and only an explicit Retry may revisit that judgement ([ChatViewModel.retryProbe]).
 */
internal fun networkHealAction(
    serverComplete: Boolean,
    transportPresent: Boolean,
    lastProbeRetryable: Boolean?,
): NetworkHealAction = when {
    !serverComplete -> NetworkHealAction.NOTHING
    transportPresent -> NetworkHealAction.POKE_STREAMS
    lastProbeRetryable == false -> NetworkHealAction.NOTHING
    else -> NetworkHealAction.REPROBE
}

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
    /**
     * B-3: where the in-app notification feed survives a restart. `null` keeps it
     * in memory (previews, the legacy WebView shell) — the data-only default.
     */
    private val notificationPersistence: NotificationPersistence? = null,
    /**
     * B-2: the Android half that posts a system notification (and plays the
     * sound) when the app is not showing the session. `null` = feed only.
     */
    private val notificationDelivery: NotificationDelivery? = null,
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

    /**
     * The one client both SSE streams ride ([EventStream.sseClient]): its own
     * dispatcher and connection pool, deliberately **not** the REST
     * [sharedClient]'s. A stream occupies a dispatcher slot for its whole life,
     * and every REST read competes for the same `maxRequestsPerHost` budget —
     * two permanently-running streams were enough to starve the reads (v74:
     * Stop, then every read timing out with no socket opened at all; see
     * `EventStream.sseClient`). One instance for the view model's life, like
     * [sharedClient].
     */
    private val sseClient: OkHttpClient by lazy { EventStream.sseClient(sharedClient) }
    @Volatile
    private var lastSnapshotAt = 0L
    @Volatile
    private var lastPartEventAt = 0L
    /** The foreground idle watchdog ([startIdleWatchdog]); `null` while stopped. */
    @Volatile
    private var idleWatchdog: TurnIdleWatchdog? = null
    /** Per-session durable watermark for the v2 log stream (`?after=` replay). */
    private val seqTracker = V2SeqTracker()
    /** Generation chosen for the current connection (probe result). */
    @Volatile
    private var activeGeneration: ServerGeneration = ServerGeneration.UNKNOWN
    /** True while a generation probe is in flight (dedupes concurrent connects). */
    private val probing = AtomicBoolean(false)
    /** Bumped when the active server changes so an in-flight probe cannot land. */
    private val probeEpoch = AtomicLong(0)
    /** The server the live client was built for; drives [adoptActiveServer]. */
    @Volatile
    private var boundServer: ServerConfigStore.Server? = null

    /**
     * Last generation-probe verdict. [setGeneration] reads it on the UNKNOWN
     * branch so 「需要认证」 and 「不是 2.x」 never share a sentence (the 3-device
     * false 「requires OpenCode 2.x server」 was a 401 auth wall).
     */
    @Volatile
    private var lastProbe: V2Capability.ProbeResult? = null
    /**
     * Last wall-clock probe attempt (any [ensureTransport] start). Gates
     * [maybeReprobe] so hot reads cannot start a probe storm.
     */
    @Volatile
    private var lastProbeAttemptAt = 0L
    /**
     * True once the constructor finished: [adoptActiveServer]'s first
     * (synchronous) emission fires before the later fields exist.
     */
    private var adoptReady = false

    /**
     * Why the most recent browse / list read could not run. Sticky until the
     * next successful read; see [TransportFailure]. Companion channel to the
     * per-call `onFailure` callbacks for UIs that only hold the legacy shape.
     */
    private val _browseFailure = MutableStateFlow<TransportFailure?>(null)
    val browseFailure: StateFlow<TransportFailure?> = _browseFailure.asStateFlow()

    /**
     * True while [listSessions] has no rows to show because the fetch failed
     * after its one startup retry — a **retryable** empty, not a permanent one.
     */
    private val _sessionsLoadFailed = MutableStateFlow(false)
    val sessionsLoadFailed: StateFlow<Boolean> = _sessionsLoadFailed.asStateFlow()

    /**
     * The last **successful** `GET /api/session` rows. [listSessions] answers
     * these when a read fails instead of an empty list: a caller with no cache
     * of its own (`HomeScreen`'s column is `remember`ed, so a route change
     * during an outage hands it a fresh empty list) would otherwise latch the
     * empty state, which is the measured `adb reverse` report — Home showed
     * 「Nothing here yet」 over a project that had sessions until the app was
     * restarted. [sessionsLoadFailed] still reports the failure, so a caller
     * can tell the answer apart from a confirmed read.
     */
    private var lastSessionRows: List<OpenCodeClient.Session> = emptyList()

    /**
     * The pending self-heal re-read of the session list ([scheduleSessionListRetry])
     * and how many rounds it has already spent failing, for its backoff.
     */
    private var sessionListRetry: Job? = null
    private var sessionListRetryRound = 0

    /**
     * A `delivery:"steer"` admission the server has **not delivered yet**, keyed
     * by the admission id (the id the durable message will carry).
     *
     * A prompt's 200 answer means "admitted into the session inbox", not "in the
     * transcript": the server promotes the row into a durable message at a step
     * boundary. A row the server never promotes — the measured v36 tB report,
     * where a session's first message stayed 「Queued」 forever with a 200
     * `delivery:"steer"` and `GET …/message/{id}` answering `MessageNotFoundError`
     * — was invisible to this client: the timeline showed the muted parked-steer
     * row, the queue panel deliberately lists only `delivery:"queue"` rows, and
     * nothing ever timed out. [watchPendingDelivery] is that missing half; the
     * map holds what it has to judge, and [retryPendingDelivery] is what the user
     * can do about it.
     *
     * Main thread only (armed from [submit]'s continuation, read by the watch).
     */
    private class PendingDelivery(var admittedAt: Long) {
        /** Set once the stall was reported, so one admission reports once. */
        var stalled: Boolean = false
    }

    private val pendingDeliveries = HashMap<String, PendingDelivery>()

    /**
     * Admissions whose optimistic transcript row this submit created
     * ([admitHandoffRow]) and which the server has not published as a message
     * yet. [refreshInbox] keeps those rows across an inbox read that no longer
     * lists the admission; the set is what tells the two apart. Per-session
     * state, like [pendingDeliveries], and cleared with it.
     */
    private val handoffPrompts = HashSet<String>()

    /** The one loop judging [pendingDeliveries]; exits when nothing is left. */
    private var pendingDeliveryWatch: Job? = null

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

    /**
     * Session id → the last title this client learned for it, from every source
     * that can know one: a catalogue read ([listSessions],
     * [scheduleSessionListRetry]), the per-session read the snapshot already
     * makes, a [renameSession] this device made, and the `session.renamed` frame
     * ([sessionTitleFrames]).
     *
     * It exists because a title read at *navigation* time is a snapshot of
     * whatever the caller happened to be holding, and it is the wrong answer
     * the moment anything renames the session afterwards — the row cache a Home
     * list or a drawer was drawn from, or the draft-time name a route was built
     * with. [observeSession] used to publish that value straight into
     * [_currentTitle], so opening a renamed session put the old name on the
     * header, the export filename and the tab label until a read came back
     * (v68: the export side had to fall back to `frameTitle` because the title
     * chain itself was dirty).
     *
     * Only titles the server actually sent are filed: the client's `Untitled`
     * fallback is display-only, and learning it would give every untitled
     * session a name it never had.
     *
     * Keyed by id, so a read can never file one session's name under another —
     * which is what [sessionTitle] lets the header and the tab strip assume.
     *
     * Concurrent: [fetchSessionRows] records from `Dispatchers.IO` while the UI
     * reads it on the main thread.
     */
    private val knownTitles = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Session id → the title the user set by hand ([renameSession]), in the
     * spelling the server echoed back.
     *
     * The server renames sessions in the background (title agent), and every
     * prompt this client sent arms another in-flight read that picks the new
     * name up ([refreshSnapshot]'s `fetchSession`, the catalogue rows): those
     * reads answer after the rename and each one would publish the auto-title
     * the user just replaced — the A→B→C progressive rewrites emulator-5558
     * measured after renaming to `E2ECHK5558-T1` (v78). [publishCurrentTitle]
     * drops a server title that disagrees with the one recorded here, so a
     * manual rename sticks; the rename's own write carries the same spelling,
     * so it still lands, and renaming again simply re-records.
     *
     * Concurrent for the same reason as [knownTitles], and never cleared for
     * the same reason too (a tab switched away from keeps its name).
     */
    private val manualTitles = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * The last title learned for [id], or `null` when this client has never
     * read one. Read by the surfaces that must name a session the view model has
     * not adopted yet (the tab strip on a route change, the header on the frame
     * a tab switch lands on) — a plain read, not a flow: every update to a
     * tracked session is also published on [currentTitle], which is what makes
     * those surfaces recompose.
     */
    fun sessionTitle(id: String): String? = knownTitles[id]?.takeIf { it.isNotBlank() }

    /**
     * Records [title] as [id]'s name and, when [id] is the tracked session,
     * publishes it as [currentTitle].
     *
     * The single writer of both, so a name can only be filed under the session
     * it was read for. A write computed for a session that is no longer tracked
     * — an in-flight catalogue read or snapshot that answers after the user
     * switched away — records the name for *its* session and leaves the live
     * title alone, instead of putting the outgoing session's name on the
     * incoming one.
     *
     * A server title that disagrees with a name the user set by hand
     * ([manualTitles]) is dropped outright — filed nowhere and published
     * nowhere — so a late auto-title read can never put the renamed-away name
     * back on the header, the tab or the export filename.
     */
    private fun publishCurrentTitle(forID: String?, title: String?) {
        if (!forID.isNullOrEmpty() && !title.isNullOrBlank()) {
            if (!adoptsServerTitle(manualTitles[forID], title)) return
            knownTitles[forID] = title
        }
        if (forID != null && forID != sessionID) return
        _currentTitle.value = title?.takeIf { it.isNotBlank() }
    }

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

    /**
     * The model the **session on screen** runs under, in upstream
     * `selection.current()` order ([effectiveModelRef]): the pick made in this
     * session, else the session's own model, else the server default.
     *
     * `first` = owning providerID, `second` = the catalog **ref id** — the
     * entry-level id ([OpenCodeClient.Providers.Model.entryID]) that
     * `POST /api/session/{id}/model` takes as `model.id`. It equals the wire
     * `modelID` for every entry without siblings, and stays distinct for the
     * `openai` models the catalog lists once per service tier (`gpt-5.6` vs
     * `gpt-5.6-fast`). The reasoning-effort [selectedVariant] is a separate
     * axis, never part of this ref.
     */
    private val _selectedModel =
        kotlinx.coroutines.flow.MutableStateFlow<Pair<String, String>?>(null)
    val selectedModel: kotlinx.coroutines.flow.StateFlow<Pair<String, String>?> = _selectedModel

    /** True while the user pinned a model instead of following the server. */
    private val _modelOverrideActive = kotlinx.coroutines.flow.MutableStateFlow(false)
    val modelOverrideActive: kotlinx.coroutines.flow.StateFlow<Boolean> = _modelOverrideActive

    /**
     * The picker pick **in effect for the context on screen**: the pick made in
     * this session, or — with no session tracked — the draft's. Null = follow
     * the session / the server default.
     *
     * Upstream keys the pick by session
     * (`providers/models/selection.tsx:139-143,286-299`: `saved.session[sessionID]`
     * for a session, `store.draft` without one), so a pick made in one session
     * is *not* the next session's model. [sessionPicks] holds the picks made in
     * this process; [recentModel] is only what a *draft* starts from.
     *
     * Only this value may be written back onto a session ([selectedModelPin]):
     * a *derived* label — the server default, or the session's own model
     * adopted for display — is not a user decision.
     */
    @Volatile
    private var modelOverride: Pair<String, String>? = null

    /**
     * The persisted picker memory ([SettingsStore.selectedModelOverride]):
     * the model a new/draft session starts from, and the last-resort label when
     * nothing else names one (upstream's `models.recent` fallback,
     * `selection.tsx:163-178`). It never outranks a session's own model — the
     * measured v28 bug was a session created with `space-bunny-free` rendering,
     * and then running as, this stale memory.
     */
    @Volatile
    private var recentModel: Pair<String, String>? = null

    /** Picks made in a session this process ([modelOverride] is the tracked one's). */
    private val sessionPicks = mutableMapOf<String, Pair<String, String>>()

    /**
     * `GET /config`'s `model` (v1) / `GET /api/model/default`'s entry id (v2),
     * parsed by [OpenCodeClient.parseModelRef].
     */
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

    private val _availableReferences =
        kotlinx.coroutines.flow.MutableStateFlow<List<MentionSources.Reference>>(emptyList())

    /**
     * F-1: `GET /api/reference` (`hidden` rows included here — the menu drops
     * them, upstream `composer/model.ts:148`) — the `@` menu's reference
     * category. The session screen feeds it to `Composer(references = …)`.
     */
    val availableReferences: kotlinx.coroutines.flow.StateFlow<List<MentionSources.Reference>> =
        _availableReferences

    private val _availableResources =
        kotlinx.coroutines.flow.MutableStateFlow<List<MentionSources.Resource>>(emptyList())

    /**
     * F-1: `GET /api/mcp/resource`'s `resources[]` — the `@` menu's MCP-resource
     * category. Refreshed with the other catalogs, i.e. also on
     * `mcp.resources.changed` / `reference.updated` ([catalogRefresh]).
     */
    val availableResources: kotlinx.coroutines.flow.StateFlow<List<MentionSources.Resource>> =
        _availableResources

    /**
     * The agent the user pinned on the composer chip, or null to follow the
     * server's own default (`primary` agents only, see
     * [OpenCodeClient.Agent.defaultName]).
     */
    private val _selectedAgent = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val selectedAgent: kotlinx.coroutines.flow.StateFlow<String?> = _selectedAgent

    /**
     * B-9: upstream `agentsVisible` (`providers/models/selection.tsx:94`) — the
     * composer's agent control exists while the user keeps custom agents on
     * ([SettingsStore.Settings.showCustomAgents], upstream default `false`) or
     * the server really has one ([OpenCodeClient.Agent.hasCustomAgent]).
     *
     * Eager, like the composer's other settings-backed flows: the screen reads
     * it in the same frame it collects [availableAgents], and a cold flow would
     * report `false` for that frame and flash the chip off.
     */
    val agentPickerVisible: kotlinx.coroutines.flow.StateFlow<Boolean> = combine(
        settingsStore?.state ?: kotlinx.coroutines.flow.flowOf(null),
        _availableAgents,
    ) { settings, agents ->
        OpenCodeClient.Agent.visible(agents, settings?.showCustomAgents == true)
    }.stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * The model variant (reasoning effort) the user pinned in the model picker,
     * or null to follow the server/agent default
     * (`providers/models/variant.ts:31-35`).
     *
     * **Scoped like the model pick, not globally** (v69): upstream stores the
     * variant in the *same* saved state as the model —
     * `write({ agent, model, variant })` under `saved.session[sessionID]`, or
     * `store.draft` without one (`providers/models/selection.tsx:288-299`) — so
     * an effort pinned in one session is not another session's effort. This
     * used to be one process-wide flow, so a `/model` → `low` in session B
     * renamed session A's chip as well
     * (`research/v68_verify/5558/45_b_low.png` → `46_A_model.png`).
     * [sessionVariants] holds the per-session pins, [draftVariant] the
     * pre-session one; [pinVariant] is the only writer.
     */
    private val _selectedVariant = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val selectedVariant: kotlinx.coroutines.flow.StateFlow<String?> = _selectedVariant

    /** `saved.session[id].variant` — the effort pinned in that session. */
    private val sessionVariants = mutableMapOf<String, String>()

    /** `store.draft.variant` — the effort pinned before a session existed. */
    @Volatile
    private var draftVariant: String? = null

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

    /**
     * Bumped on `session.created` / `session.deleted` **and** on every frame that
     * moves a session row's `time.updated` ([ChatStore.Signal.SESSION_UPDATED] —
     * `session.model.selected` / `session.agent.selected` / `session.permissions`
     * / `session.revert.*` on v2, `session.updated` itself on v1): the session
     * list is stale, and so is its **order**.
     *
     * A row write is the only thing that reorders the list server-side, and the
     * shell has no local session-row store to re-sort from (unlike upstream's
     * `data.session.list()` + `Math.max(time.updated, event.created)`), so the
     * consumers re-read `GET /api/session` — the drawer while it is open, Home
     * while it is mounted. That is what keeps the drawer's order on the server's
     * `updated` instead of on whatever snapshot it was opened with.
     */
    val sessionsRevision: StateFlow<Int> = _sessionsRevision.asStateFlow()

    private val _sessionUnread = MutableStateFlow<Map<String, SessionUnread>>(emptyMap())

    /**
     * Per-session unread watermark from the newest session-list read ([SessionUnread]):
     * the tab strips' and the session drawer's dot source. Published together
     * with the rows, so the two halves of one read can never disagree.
     *
     * This is the **server's** ledger, not the in-app notification feed's: the
     * feed only exists while a stream is open (this shell opens none off the
     * session route, `OcActivity`'s documented design), while `time.idle` vs
     * `time.viewed` survives restarts and is shared by every client — which is
     * why a turn that settled while the app was away still reads as unread.
     */
    val sessionUnread: StateFlow<Map<String, SessionUnread>> = _sessionUnread.asStateFlow()

    private val _askLedger = MutableStateFlow(SessionAskLedger())

    /**
     * The **location-wide** pending asks, by session ([SessionAskLedger]) — the
     * `hasPermission` / `hasForm` halves of the dot
     * (`shell/layout/project-avatar-state.ts:26-35`).
     *
     * This is the counterpart of [sessionUnread] for the ask half: `ChatStore`'s
     * `pendingPermissions` is the **tracked** session's dock list (every ask
     * event is gated by `tracksSession`), so on its own it can only ever answer
     * for the session on screen. Upstream's lists are keyed by session, and the
     * dot is asked about a *row* — a foreign session waiting on a permission has
     * to light its own tab / row, which is exactly the case the tracked list
     * cannot report.
     *
     * Fed by [recordAsk] from the global v2 frame path (every ask frame, whatever
     * session it names), by the tracked session's own REST snapshots
     * ([refreshPermissions] / [refreshForms]) and by the location-wide
     * `GET /api/permission/request` / `GET /api/form` rows [fetchSessionRows]
     * unions in.
     */
    val sessionAsks: StateFlow<Map<String, SessionAsks>> =
        _askLedger.map { it.bySession }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /**
     * The session whose route the user is looking at — upstream's
     * `viewedInCurrentSession` (`shell/notifications/notification.tsx:221-223`:
     * `location.pathname === sessionHref(key, sessionID)`), i.e. what decides
     * whether a notification is raised already-seen. Set by [observeSession]
     * (that session's screen opened) and cleared by the shell's destination
     * listener (`ui/App.kt`) as soon as the route is no longer that session, so
     * a turn settling while the user is on Home stays unread.
     *
     * Deliberately *not* [sessionID]: the tracked session outlives its screen —
     * navigating Home leaves `sessionID` pointing at the session the user just
     * left, and treating that as "on screen" is what used to mark a background
     * completion as seen.
     */
    @Volatile
    private var visibleSessionID: String? = null

    /** The shell's route report for [visibleSessionID]; see its KDoc. */
    fun setSessionVisible(id: String?) {
        visibleSessionID = id
    }

    private val _vcsRevision = MutableStateFlow(0)

    /** Bumped on `vcs.branch.updated`; `GET /vcs` is stale (branch chip). */
    val vcsRevision: StateFlow<Int> = _vcsRevision.asStateFlow()

    private val _filesRevision = MutableStateFlow(0)

    /** Bumped on `file.edited`; the review panel's file view is invalid. */
    val filesRevision: StateFlow<Int> = _filesRevision.asStateFlow()

    private val _ptyRevision = MutableStateFlow(0)

    /** Bumped on `pty.*`; another client created / changed / closed a terminal. */
    val ptyRevision: StateFlow<Int> = _ptyRevision.asStateFlow()

    private val _mcpRevision = MutableStateFlow(0)

    /**
     * Bumped on `mcp.status.changed` / `mcp.tools.changed` /
     * `mcp.resources.changed`; `GET /api/mcp` and the MCP resource catalog are
     * stale (settings Extensions page, session MCP chooser, `@` resource
     * suggestions). The server emits one `status.changed` per server as it
     * settles, so a boot burst bumps this once per server — a collector that
     * fetches should settle/debounce like upstream
     * (`client/src/solid/data.ts:1287-1290`), not fetch per event.
     */
    val mcpRevision: StateFlow<Int> = _mcpRevision.asStateFlow()

    /** Last catalog re-read; a burst of `provider.updated` must not hammer `GET /provider`. */
    @Volatile
    private var lastCatalogRefreshAt = 0L

    // ---- in-app notifications (W5-E5) ---------------------------------------

    /**
     * Feed of E5 notifications, gated by `SettingsStore.notifyAgent` /
     * `notifyPermissions` / `notifyErrors` — the three settings keys that had no
     * consumer before this wave. Presentation is the UI layer's job; this class
     * only decides *what* happened (kind / session / detail) and de-bounces it.
     *
     * B-2/B-3: the two Android halves are injected ([notificationPersistence]
     * keeps the feed across restarts, [notificationDelivery] posts it as a system
     * notification); both are optional, so the class stays JVM-testable and the
     * legacy WebView shell keeps its in-memory feed.
     */
    private val appNotifier = AppNotifier(
        enabled = ::notificationEnabled,
        persistence = notificationPersistence,
        delivery = notificationDelivery,
    )

    /**
     * v49: which sessions this device has had open — the raise gate for a
     * shared backend, where the stream carries every other device's turns
     * ([SessionOwnership]). Fed by [observeSession]; read by [raise].
     */
    private val sessionOwnership = SessionOwnership()

    /**
     * E5/B-2: the run status the **wire** reported for the tracked session, kept
     * synchronously next to the trigger ([TurnRunStatus]). The turn-complete
     * notification may not be decided from `_state.value.runStatus`: that field
     * is filled by `scope.launch { applyEvent(…) }` (a frame read on the SSE
     * thread is applied later, on `Dispatchers.Main`), so a read that carries a
     * whole turn — a durable replay after a reconnect, which is what a
     * backgrounded stream comes back with — reaches the terminal frame while the
     * store is still on the previous status, and the reconciliation path
     * ([refreshSnapshot]) writes IDLE silently before the frame arrives. Both
     * used to drop the notification with the transcript intact.
     */
    private val turnRunStatus = TurnRunStatus()

    /**
     * The Stop this client confirmed, held against the server's live set
     * ([StoppedTurn]): a live-set reading may confirm a run, never re-open one
     * the wire already ended — the composer stayed on Stop after the
     * `Interrupted` divider had landed, and the next submit went out as a steer
     * (`research/v76_verify/5556/37_third_sent.png`, `…/38_sent.png`).
     */
    private val stoppedTurn = StoppedTurn()

    /** Newest-last feed of raised notifications; see [AppNotifier]. */
    val notifications: StateFlow<List<AppNotification>> = appNotifier.notifications

    /** Drops one entry of [notifications]; `true` when it was present. */
    fun dismissNotification(id: String): Boolean = appNotifier.dismiss(id)

    /**
     * The banner's 「知道了」 ([AppNotifier.acknowledge]): drops the shown
     * entry and stops counting its session's queued news as unseen, so one
     * tap hides the banner instead of revealing an identical twin queued
     * behind it (v47 tE).
     */
    fun acknowledgeNotification(id: String): Boolean = appNotifier.acknowledge(id)

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
        val enabled = when (kind) {
            AppNotificationKind.AGENT -> settings.notifyAgent
            AppNotificationKind.PERMISSION, AppNotificationKind.QUESTION -> settings.notifyPermissions
            AppNotificationKind.ERROR -> settings.notifyErrors
        }
        Log.d(NOTIFY_TAG, "gate settings: kind=$kind enabled=$enabled")
        return enabled
    }

    /**
     * B-7: the version of an **incompatible** server the probe reached — a real
     * OpenCode server that answers `/api/info` with something other than a 2.x
     * version. `null` for every other outcome (v2, auth wall, SPA HTML,
     * unreachable), which is exactly the split
     * [V2Capability.ProbeResult.incompatibleVersion] makes.
     *
     * The session route renders the full-screen
     * [com.xiufeigo.opencodeshell.ui.session.IncompatibleServerPanel] from it
     * (`session/route-error.tsx:26-28` does the same off
     * `activeServer.health?.incompatible`).
     *
     * Declared **before** [init] on purpose: `init` collects
     * `serverConfig.revision`, whose synchronous first emission runs
     * `adoptActiveServer` mid-construction and resets this field, so a
     * declaration below `init` would still be null there and NPE. The
     * construction-time emission may only touch fields declared above `init`.
     */
    private val _incompatibleServerVersion = MutableStateFlow<String?>(null)
    val incompatibleServerVersion: StateFlow<String?> = _incompatibleServerVersion.asStateFlow()

    init {
        // Restored before the first config fetch, so a returning user never
        // flashes the server default on the chip. This is the *draft*'s pick
        // (upstream `store.draft`): the pre-draft composer has no session yet,
        // and a session opened later starts from its own model instead.
        recentModel = settingsStore?.selectedModelOverride?.let(OpenCodeClient::parseModelRef)
        modelOverride = recentModel
        _selectedModel.value = modelOverride
        _modelOverrideActive.value = modelOverride != null
        scope.launch {
            serverConfig.revision.collect { adoptActiveServer() }
        }
        adoptReady = true
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

    /** Generation of the active connection (UNKNOWN = probe not yet conclusive). */
    private val _generation = MutableStateFlow(ServerGeneration.UNKNOWN)
    val generation: StateFlow<ServerGeneration> = _generation.asStateFlow()

    /**
     * Round counter for the generation probe: bumped when a round **ends**
     * ([ensureTransport]'s `finally`), whatever it concluded.
     *
     * `probing` is an AtomicBoolean, so a round that lands the same verdict as
     * the previous one changes no flow at all — a waiter keyed on [generation]
     * alone would sleep out its whole timeout on exactly the re-probe that
     * matters ([awaitProbeSettled]).
     */
    private val probeSettled = MutableStateFlow(0)

    // WAVE 2.4b (queue/steer VM half): the session's queued follow-ups. The
    // composer renders these rows and forwards row ids back; every inbox
    // round-trip stays here. In-memory per session — cleared on session
    // switch like history, never persisted.
    private val queueHolder = ComposerQueueHolder()
    val queuedRows: StateFlow<List<ComposerQueue.Row>> = queueHolder.rows
    val queueBusy: StateFlow<Boolean> = queueHolder.busy

    /**
     * WAVE 2.4d: settle attempts already spent on a short v2 catalog answer
     * ([refreshV2Providers]). Main-thread only, like the catalog write itself.
     */
    private var catalogSettleRetries = 0

    /**
     * WAVE 2.4b: the `followUpBehavior` setting (`steer` | `queue`) the
     * composer reads to default the Steer/Queue toggle. Read-only here — the
     * composer never writes it (upstream keeps it in settings).
     */
    val followUpBehavior: StateFlow<String> =
        settingsStore?.state?.map { it.followUpBehavior }
            ?.stateIn(scope, SharingStarted.Eagerly, settingsStore.state.value.followUpBehavior)
            ?: MutableStateFlow("steer")

    /**
     * `settings.permissions.autoApprove` (`SettingsStore.autoAcceptPermissions`)
     * as the dot sources need it: upstream gates the *permission* half of "needs
     * attention" on it (`project-avatar-state.ts:27`), and this shell's feed
     * carries permission entries upstream's ledger never holds, so
     * `SessionTabIndicator.unseenCount` has to know the setting too. Off with no
     * store — the permission half then counts, which is the pre-setting default.
     */
    val autoApprovePermissions: StateFlow<Boolean> =
        settingsStore?.state?.map { it.autoAcceptPermissions }
            ?.stateIn(scope, SharingStarted.Eagerly, settingsStore.state.value.autoAcceptPermissions)
            ?: MutableStateFlow(false)

    // Id of the permission request currently being answered (upstream
    // `permissionResponding`); the dock disables its buttons while set.
    private val _permissionResponding = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val permissionResponding: kotlinx.coroutines.flow.StateFlow<String?> = _permissionResponding

    // Id of the form request currently being answered; the dock disables
    // its buttons while set.
    private val _formResponding = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val formResponding: kotlinx.coroutines.flow.StateFlow<String?> = _formResponding

    // Auto-approve (`SettingsStore.autoAcceptPermissions`, upstream
    // `requests/auto-approve.ts`): permission asks are replied "once" as they
    // arrive, with a bounded per-request retry budget. The counter lives
    // outside ChatState so a reduce round-trip never resets it.
    private val autoApprover = AutoApprover(
        enabled = { settingsStore?.state?.value?.autoAcceptPermissions == true },
    )

    var sessionID: String? = null
        private set

    /**
     * [sessionID] as an observable, and the only thing a reader outside this
     * class may use to decide **which session the live [currentTitle] belongs
     * to**.
     *
     * `sessionID` itself is a plain field, so a composition that read it during
     * a route change kept the *previous* session's value until something else
     * invalidated it — which is how the tab strip ended up labelling a freshly
     * opened tab with the session the user had just left (the v64 report:
     * 「tab 显示 Quick check-in 而内容是新建会话」). The two are written together in
     * [observeSession]/[newSession], so a reader that pairs this id with
     * [currentTitle] can never mix two sessions' facts.
     */
    private val _trackedSessionID = MutableStateFlow<String?>(null)
    val trackedSessionID: StateFlow<String?> = _trackedSessionID.asStateFlow()

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

    // True while `revertToMessage` is in flight — the per-message「撤销到此」
    // button disables on it (upstream `state.reverting`).
    private val _reverting = MutableStateFlow(false)
    val reverting: StateFlow<Boolean> = _reverting.asStateFlow()

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
     * The session `POST /session/:id/fork` just created — the id the UI has to
     * navigate to (`session/commands/fork-dialog.tsx:88`
     * `navigate(sessionHref(server.key, forked.id))`). Sticky until
     * [consumeForkedSession] takes it, so a navigation that happens one
     * recomposition later still sees it.
     */
    private val _forkedSessionID = MutableStateFlow<String?>(null)
    val forkedSessionID: StateFlow<String?> = _forkedSessionID.asStateFlow()

    /**
     * The tracked session's route is not usable: the server either no longer has
     * it (`404`) or refuses it (`5xx` — the missing-project-directory signature,
     * v82 BUG-A). Set once, reset by [observeSession]/[newSession]; the session
     * screen shows its route-level failure page on it and all session-scoped
     * refreshes stop, so a stale route cannot spin a failure loop.
     */
    private val _sessionMissing = MutableStateFlow(false)
    val sessionMissing: StateFlow<Boolean> = _sessionMissing.asStateFlow()

    /**
     * **Why** [sessionMissing] latched ([SessionOpenFailure]): `GONE` for a `404`,
     * `REFUSED` for a `5xx`. The page says which one — 「找不到该会话」 and 「服务
     * 器拒绝该会话」 are different problems for the reader (one is a ghost entry
     * to remove, the other names a server-side cause), and only the reason tells
     * the copy apart. `null` while nothing latched.
     */
    private val _sessionMissingReason = MutableStateFlow<SessionOpenFailure?>(null)
    val sessionMissingReason: StateFlow<SessionOpenFailure?> = _sessionMissingReason.asStateFlow()

    /**
     * B-17: the debug bar's **live** provider metrics — the fold over the v2
     * frame stream ([providerMetricsFold]), published for `ui/App.kt`'s
     * `DebugBar`.
     *
     * The desktop bar folds the same events (`shell/debug/provider-metrics.ts`),
     * and this is the Android frame path's equivalent: `session.step.started`
     * carries the dispatch time, `session.text.started` /
     * `session.reasoning.started` / `session.tool.input.started` carry the first
     * output, `session.step.streamed` the stream end, and `session.step.ended`
     * the `TokenUsage.Info` the four cards are computed from. The values are
     * cleared when the tracked session changes and when the event stream is not
     * established — upstream drops `live` on a disconnect for the same reason
     * (events missed during an outage are never replayed, so the history
     * projection must win rather than a stale live figure).
     */
    private val providerMetricsFold = ProviderMetricAccumulator()
    private val _providerMetrics = MutableStateFlow<ProviderMetrics?>(null)
    val providerMetrics: StateFlow<ProviderMetrics?> = _providerMetrics.asStateFlow()

    private fun resetProviderMetrics() {
        providerMetricsFold.reset()
        _providerMetrics.value = null
    }

    fun observeSession(id: String, title: String? = null, directory: String? = null) {
        sessionID = id
        _trackedSessionID.value = id
        // This device has the session open: its turns are its business
        // ([sessionOwnership], and the raise gate below).
        sessionOwnership.learn(id)
        // The notification trigger mirrors *this* session's run status from the
        // wire; the session it replaced must not lend it a status (a terminal
        // frame of the new session's first turn would otherwise look like the
        // end of a turn this client never saw start).
        turnRunStatus.watch(id)
        // Same rule for the Stop on record: a Stop this client confirmed for the
        // session it was on says nothing about the one it just opened.
        stoppedTurn.watch(id)
        // Opening a session is what makes it "the one being looked at"
        // (`visibleSessionID`): from here until the route leaves it, its
        // notifications are raised already-seen and its unread watermark is
        // acknowledged.
        visibleSessionID = id
        this.directory = directory
        _sessionMissing.value = false
        _sessionMissingReason.value = null
        _currentDirectory.value = directory
        _state.value = ChatStore.ChatState(sessionID = id)
        // A name learned about *this* session — a catalogue row, a
        // `session.renamed` frame, a rename made here — outranks the one the
        // caller was holding when it navigated: that one is a snapshot of
        // whatever the caller was drawn from (the row cache, the draft), so it
        // is exactly the value that goes stale once the session is renamed
        // (v68). It is still the last resort — a preview or a test has no
        // learned name to offer, and the shell must not need one to open a
        // session.
        publishCurrentTitle(id, sessionTitle(id) ?: title?.takeIf { it.isNotBlank() })
        _sessionCost.value = null
        _sessionModel.value = null
        _activeContextTokens.value = null
        _activeContextRows.value = null
        _revertMessageID.value = null
        _forkedSessionID.value = null
        _backgroundTasks.value = emptyList()
        // B-17: the metrics belong to the session they were measured in — the
        // bar must not show the previous session's request figures.
        resetProviderMetrics()
        // WAVE 2.4b: the queue is per-session in-memory state, like history.
        queueHolder.clear()
        // BUG-2: an undelivered steer belongs to the session it was admitted in
        // — the new one must not inherit its watchdog (the id would never
        // appear here, so it could only ever report a stall that is not this
        // session's).
        pendingDeliveries.clear()
        handoffPrompts.clear()
        // The pick belongs to the session it was made in (upstream
        // `saved.session[sessionID]`), and the previous session's model must
        // not label this one: with no pick of its own, this session shows —
        // and keeps — *its* model, which the snapshot below reads
        // (`Session.Info.model`). Until then the server default is the honest
        // answer; the persisted memory is not (the v28 bug).
        modelOverride = sessionPicks[id]
        _modelOverrideActive.value = modelOverride != null
        // v69: the effort travels with the session, the same way the model
        // pick does — `saved.session[id].variant`, restored on entry and null
        // for a session that never pinned one (upstream `selected()` returns
        // that session's own variant, never the one the user set elsewhere).
        // v73: the in-memory table is empty after a cold start, so the local
        // durable copy seeds it first — the chip is then right before the
        // snapshot below even answers, and the server row still has the last
        // word whenever it names an effort of its own.
        if (sessionVariants[id] == null) {
            settingsStore?.sessionVariant(id)?.let { sessionVariants[id] = it }
        }
        pinVariant(sessionVariants[id])
        applyEffectiveModel()
        if (started.get()) {
            refreshSnapshot()
            refreshBackgroundTasks()
        }
    }

    /**
     * Drops back to the pre-session state: the next send creates the session
     * server-side (existing createSession flow). Nothing is created eagerly.
     */
    fun newSession() {
        sessionID = null
        _trackedSessionID.value = null
        turnRunStatus.watch(null)
        stoppedTurn.watch(null)
        visibleSessionID = null
        directory = null
        _state.value = ChatStore.ChatState()
        publishCurrentTitle(null, null)
        _sessionCost.value = null
        _sessionModel.value = null
        _activeContextTokens.value = null
        _activeContextRows.value = null
        _revertMessageID.value = null
        _forkedSessionID.value = null
        _sessionMissing.value = false
        _sessionMissingReason.value = null
        _backgroundTasks.value = emptyList()
        // WAVE 2.4b: no tracked session, no queue.
        queueHolder.clear()
        // BUG-2: no tracked session, no pending steer to watch.
        pendingDeliveries.clear()
        handoffPrompts.clear()
        // A draft starts from the picker memory again (upstream `store.draft`
        // after `session.reset()`); the session that was on screen keeps its
        // own model.
        modelOverride = recentModel
        _modelOverrideActive.value = modelOverride != null
        // v69: and from its own effort — the draft slot, never the session that
        // was on screen a moment ago.
        pinVariant(draftVariant)
        applyEffectiveModel()
    }

    /**
     * The route-level verdict of one failed session-scoped read, on either
     * protocol: `404` is the session being gone, `5xx` is the server refusing
     * it ([SessionOpenFailure]). A socket failure or a timeout answers `null`
     * on purpose — that is the link, not the session.
     */
    private fun sessionOpenFailureOf(e: Exception): SessionOpenFailure? = when (e) {
        is V2Api.ApiException -> sessionOpenFailure(e.code)
        is OpenCodeClient.ApiException -> sessionOpenFailure(e.code)
        else -> null
    }

    /**
     * Latch [sessionMissing] (with its [sessionMissingReason]) and drop the
     * per-session log stream — the global stream stays up (other sessions still
     * live), but nothing session-scoped should keep polling a session the server
     * forgot or refuses.
     *
     * [forID] is the session the failure was observed for: a stale response from
     * the previously tracked session must not mark the session now on screen as
     * gone (that freezes every session-scoped refresh behind this latch). The
     * same guard covers the [reason] — a 5xx is only ever filed for the session
     * it was measured on.
     *
     * v82 BUG-A: the reason is what makes the page honest for the second shape.
     * A `500` on a session's own reads used to be logged and dropped, leaving a
     * perfectly normal-looking **empty session** whose every route failed
     * (`research/v82_verify/5558/33_ghost_open.png`); the page it now reaches
     * carries the entry-level exit the user needs ([SessionNotFoundPanel]'s
     * 「删除该条目」).
     */
    private fun markSessionMissing(forID: String?, reason: SessionOpenFailure = SessionOpenFailure.GONE) {
        if (sessionID != forID) return
        if (_sessionMissing.value) return
        _sessionMissingReason.value = reason
        _sessionMissing.value = true
        sessionLogStream?.close()
        sessionLogStream = null
    }

    /**
     * Session rows for Home / the drawer, newest-first; also re-syncs
     * [currentTitle] with the tracked session.
     *
     * Routes through the **v2** `GET /api/session` once the probe has landed.
     * It must never fall back to `OpenCodeClient.listSessions` (the unprefixed
     * v1 `GET /session`): on a 2.x server that path is the SPA catch-all
     * (`200 text/html`) and the JSON parse surfaces the misleading
     * `Value <!doctype … cannot be converted to JSONObject` (3-device logcat,
     * 2026-09-24). While the probe is still in flight the call waits briefly
     * for the verdict instead of racing it. A startup network failure retries
     * once after [STARTUP_RETRY_DELAY_MS] — `adb reverse` settling used to
     * leave Home on a permanent empty list with no retry hint — and only then
     * reports [sessionsLoadFailed].
     *
     * The answer is **never a spurious empty list**: a failed read answers
     * [lastSessionRows], the last list this client did read, and schedules the
     * self-heal re-read that turns it fresh again once the connection is back
     * ([scheduleSessionListRetry]). `[]` therefore means "the server says there
     * are none", while [sessionsLoadFailed] means "this is the last known list,
     * not a confirmed one".
     */
    fun listSessions(onResult: (List<OpenCodeClient.Session>) -> Unit) {
        scope.launch {
            awaitProbeSettled()
            if (v2Api() == null) {
                // No transport (probe pending / auth wall / not 2.x): a retry
                // cannot help until the server row is fixed — say so instead of
                // returning a silent empty list. A retryable verdict also kicks
                // one lazy re-probe so the next read can heal without a restart.
                maybeReprobe()
                _sessionsLoadFailed.value = true
                reportBrowseFailure(noTransportFailure())
                scheduleSessionListRetry()
                onResult(lastSessionRows)
                return@launch
            }
            // The bound below must also hold against a **wedged** socket, not
            // just a slow one: the passes are blocking OkHttp `execute()`
            // calls, which never reach a suspension point, so a bare
            // `withTimeoutOrNull` around them cannot fire and the first load
            // sat on its skeleton with no error and no retry. Racing them in
            // a child `async` moves the wait onto `await`, which *is*
            // cancellable: the bound still reports at 20 s while the zombie's
            // late answer is discarded (`runCatching` also keeps it from
            // surfacing as an unhandled failure after nobody awaits it).
            var sessions = withTimeoutOrNull(SESSION_LIST_TIMEOUT_MS) {
                val fetch = async(Dispatchers.IO) {
                    runCatching {
                        var first = fetchSessionRows()
                        if (first == null) {
                            delay(STARTUP_RETRY_DELAY_MS)
                            first = fetchSessionRows()
                        }
                        first
                    }.getOrNull()
                }
                fetch.await()
            }
            _sessionsLoadFailed.value = sessions == null
            if (sessions == null) {
                reportBrowseFailure(TransportFailure.RequestFailed("session list fetch failed"))
                // Same contract as the no-transport branch above: keep the rows
                // the client already knows and come back for the rest.
                scheduleSessionListRetry()
                onResult(lastSessionRows)
                return@launch
            }
            val rows = sessions.sortedByDescending { it.updatedAt }
            _browseFailure.value = null
            // A landed read is what the retry was waiting for; the cadence
            // restarts from the first step on the next outage.
            sessionListRetry?.cancel()
            sessionListRetry = null
            sessionListRetryRound = 0
            lastSessionRows = rows
            if (rows.isNotEmpty()) {
                publishCurrentTitle(
                    sessionID,
                    rows.firstOrNull { it.id == sessionID }?.title ?: _currentTitle.value,
                )
            }
            onResult(rows)
        }
    }

    /**
     * The re-read a failed session-list fetch schedules for itself.
     *
     * Home opens no stream (M1 contract) and the drawer reads only while it is
     * open, so after a failed read nothing re-reads the list: the measured
     * `adb reverse` report had Home showing 「Nothing here yet」 over a project
     * with sessions, and only a process restart brought the rows back — the
     * connection was fine again, but no read ever asked.
     *
     * The round **performs the read itself** ([fetchSessionRows]) instead of only
     * bumping the channel: a bump is a no-op with no list surface mounted (Home
     * opens none, the drawer reads only while open), and it was also the whole
     * repair for a surface whose own read then answered the cached rows. The
     * measured v36 tB report is exactly that shape — an `adb reverse` outage
     * with no read in flight, so nothing failed, nothing armed this chain, and
     * Home kept its pre-outage rows for minutes after the mapping was back;
     * only re-entering Home re-read. The landed read is now published here
     * (cache + [sessionsRevision], so every mounted surface adopts the fresh
     * rows) and re-kicks the probe ([maybeReprobe]) and the transport heal
     * ([readTransport]) on the way.
     *
     * One retry round is pending at a time and only a read that actually landed
     * ends the chain — never the cached answer [listSessions] hands a failed
     * caller — so a healthy connection never sees one: this is a repair for a
     * failure, not a poll. Its cadence grows per failed round
     * ([sessionListRetryDelayMs]) so a server that stays down costs one
     * `GET /api/session` a minute rather than a spin.
     */
    private fun scheduleSessionListRetry() {
        if (sessionListRetry?.isActive == true) return
        val round = sessionListRetryRound
        sessionListRetryRound = round + 1
        sessionListRetry = scope.launch {
            delay(sessionListRetryDelayMs(round))
            if (v2Api() == null) maybeReprobe()
            val rows = fetchSessionRows()
            if (rows != null) {
                val fresh = rows.sortedByDescending { it.updatedAt }
                lastSessionRows = fresh
                _sessionsLoadFailed.value = false
                _browseFailure.value = null
                sessionListRetryRound = 0
                if (fresh.isNotEmpty()) {
                    publishCurrentTitle(
                        sessionID,
                        fresh.firstOrNull { it.id == sessionID }?.title ?: _currentTitle.value,
                    )
                }
                _sessionsRevision.value += 1
                return@launch
            }
            // Still unreachable: this round is spent, hand the next one over.
            // Cleared first so the guard above admits a fresh job (this body
            // suspended at `delay` before the field was assigned, so it is
            // clearing its own entry, never a successor's).
            sessionListRetry = null
            scheduleSessionListRetry()
        }
    }

    /**
     * Blocks (bounded) until the generation probe has installed a verdict.
     * `listSessions` is routinely called in the same frame as `ensureClient`,
     * i.e. while `GET /api/info` is still in flight — reading then is exactly
     * the race the 3 devices hit.
     *
     * The signal is [probeSettled], not the `generation` value: a round that
     * concludes the *same* verdict as the previous one (the common
     * definitive-negative re-probe) leaves `_generation` unchanged, so a StateFlow
     * of equal values emits nothing and a waiter keyed on it slept out the whole
     * timeout before answering.
     */
    private suspend fun awaitProbeSettled() {
        if (transportRef.get() != null || !probing.get()) return
        withTimeoutOrNull(PROBE_SETTLE_WAIT_MS) {
            probeSettled.first { transportRef.get() != null || !probing.get() }
        }
    }

    /**
     * One v2 `GET /api/session` read (all directories, paged). `null` = the
     * fetch failed — as distinct from an empty server, which answers `[]`.
     *
     * The read rides [readTransport] like the timeline snapshot and the project
     * column: a dropped socket (the `adb reverse` blip the retry policy was
     * written for) is retried with backoff, and each transient failure evicts
     * the dead pooled connections and kicks the throttled re-probe
     * ([noteTransientTransportFailure]) — without that the list was the one read
     * that surfaced a single blip as a permanent failure.
     */
    private suspend fun fetchSessionRows(): List<OpenCodeClient.Session>? {
        val api = v2Api() ?: return null
        return try {
            withContext(Dispatchers.IO) {
                val rows = ArrayList<OpenCodeClient.Session>()
                val unread = HashMap<String, SessionUnread>()
                val acknowledged = _sessionUnread.value
                var cursor: String? = null
                val seenCursors = HashSet<String>()
                do {
                    val page = readTransport { api.sessions(cursor = cursor, directoryScoped = false) }
                    for (row in page.data) {
                        val session = row.toUiSession()
                        rows += session
                        // Every catalogue read teaches this client a name
                        // ([knownTitles]); a session the user opens next must
                        // find it here rather than trust whatever the caller
                        // was holding at navigation time. The client's
                        // `Untitled` fallback is not a name: filing it would
                        // make every untitled session report one, and the
                        // rename seed would offer it.
                        if (!optStringOrNull(row.raw, "title").isNullOrBlank()) {
                            publishCurrentTitle(row.id, session.title)
                        }
                        // The acknowledgement this client already recorded is a
                        // floor: `time_viewed` is monotone server-side, so a read
                        // that raced the `POST …/view` of a session the user just
                        // opened must not un-read that transition.
                        unread[row.id] = row.toUnread().withAcknowledged(acknowledged[row.id])
                    }
                    cursor = page.nextCursor
                    // A cursor that repeats is no progress (a server echoing the
                    // same page): without this the loop fetches forever and Home
                    // never leaves its skeleton.
                    if (cursor != null && !seenCursors.add(cursor)) cursor = null
                } while (cursor != null && rows.size < SESSION_LIST_LIMIT)
                // Rows and their unread watermarks are one read: publishing both
                // here keeps the dot from ever describing a different list than
                // the one on screen.
                _sessionUnread.value = unread
                // …and the ask half of the dot rides the same read: the
                // location-wide pending lists are what makes a session this
                // client is *not* observing light its row (upstream
                // `session.permission.list` / `session.form.list`). Unioned, not
                // replaced — the read is scoped to one location, so an entry it
                // does not name is not evidence that the ask is gone; the reply
                // frames are the only thing that clears one. A v1 server has
                // neither route, so the failure keeps the stream-fed ledger.
                mergeLocationAsks(api)
                rows
            }
        } catch (e: Exception) {
            // The list timeout ([SESSION_LIST_TIMEOUT_MS]) cancels this read;
            // cancellation must propagate so the timeout (and scope teardown)
            // ends the wait instead of being reported as a plain fetch failure.
            if (e is CancellationException) throw e
            Log.w(TAG, "list sessions failed: ${e.message}")
            null
        }
    }

    /**
     * The location-wide pending asks, unioned into the dot's ledger
     * ([SessionAskLedger.pendingRows]) — `GET /api/permission/request` +
     * `GET /api/form` (upstream's `session.permission.list` / `session.form.list`
     * are exactly these two reads kept live).
     *
     * This is the half the stream cannot cover: an ask that landed while no
     * stream was open is invisible to `permission.asked` and to the in-app feed
     * alike, and the session rows carry no ask field. Called from the session
     * list read ([fetchSessionRows]) because that is the read every list surface
     * performs when it opens (Home entry, drawer open, foreground) — the dot and
     * the rows then describe the same moment.
     *
     * Best-effort: a v1 server has neither route and a failure must leave the
     * stream-fed ledger alone.
     */
    private suspend fun mergeLocationAsks(api: V2Api) {
        val permissions = try {
            api.pendingPermissions(directory).map { it.id to it.sessionID }
        } catch (e: Exception) {
            Log.w(TAG, "pending permissions fetch failed: ${e.message}")
            null
        }
        val forms = try {
            api.pendingForms(directory).map { it.id to it.sessionID }
        } catch (e: Exception) {
            Log.w(TAG, "pending forms fetch failed: ${e.message}")
            null
        }
        if (permissions == null && forms == null) return
        _askLedger.update { it.pendingRows(permissions.orEmpty(), forms.orEmpty()) }
    }

    /**
     * M3 addition (purely additive; [start] routes through it, so its behaviour
     * is unchanged): builds the REST client **without opening the SSE stream**.
     *
     * Wave 1: also resolves the server generation. The probe runs on IO
     * (`GET /api/info`, then `GET /global/health`) and its verdict lands
     * asynchronously through [setGeneration]. Callers must not assume the
     * transport exists synchronously after this returns — [transportRef] is
     * set on the IO thread; generation-sensitive reads use [requireTransport],
     * and the label / catalog pulls wait for the verdict ([setGeneration]).
     *
     * Home lists sessions through [listSessions], which needs a client, and the
     * M1 contract is explicit that sitting on Home costs no connection — calling
     * [start] there would open a stream the home page never reads. Returns
     * `false` (and raises [configMissing]) when no usable server is configured,
     * exactly like the corresponding checks in [start].
     */
    fun ensureClient(): Boolean {
        if (clientRef.get() != null) return true
        // Fresh prefs read (ServerHealth's source) rather than a possibly stale
        // in-memory snapshot — same rule as [ensureTransport].
        val server = serverConfig.freshServer() ?: serverConfig.server
        if (server == null || !server.isComplete) {
            _configMissing.value = true
            return false
        }
        val client = OpenCodeClient.forServer(server, sharedClient) ?: run {
            _configMissing.value = true
            return false
        }
        clientRef.set(client)
        boundServer = server
        // Label + catalog pulls wait for the probe verdict ([setGeneration]):
        // before it lands the v1 routes would hit a v2 server's SPA HTML.
        ensureTransport(server)
        return true
    }

    /**
     * Generation resolution: probe `GET /api/info`; on a confirmed v2 verdict
     * install the [V2Transport], otherwise surface an honest error. The probe
     * runs on IO and the transport lands asynchronously — callers use
     * [requireTransport], which is null during the probe window.
     *
     * Credential rule (the 3-device P0, 2026-09-24): the probe must see the
     * same Basic secret `ServerHealthProbe` sees. That probe resolves its
     * target through `healthTargets()` → `ServerConfigStore.listServers()`, a
     * **prefs read taken at probe time** — while this function used to send the
     * `ServerConfigStore.Server` snapshot `ensureClient` captured frames (or
     * WebView-sync seconds) earlier. A snapshot without `password` sends no
     * `Authorization` at all (`basicAuthorizationHeader` is null) → `401` →
     * `UNKNOWN` → the v1 fallback exploded on SPA HTML, even though curl with
     * the stored secret got `200 {"version":"2.0.11"}` and the Servers panel
     * showed the same row healthy. So credentials are re-resolved through
     * [ServerConfigStore.freshServer] **inside the IO block, immediately
     * before the request**, and an auth-wall verdict retries once against a
     * still-fresher read. A network error (adb reverse not up yet) retries a
     * few rounds with growing backoff instead of latching UNKNOWN.
     */
    private fun ensureTransport(requested: ServerConfigStore.Server) {
        if (!probing.compareAndSet(false, true)) return
        lastProbeAttemptAt = System.currentTimeMillis()
        val epoch = probeEpoch.get()
        scope.launch(Dispatchers.IO) {
            // ServerHealth's source: prefs re-read at request time, never the
            // ensureClient snapshot.
            var server = serverConfig.freshServer() ?: requested
            try {
                var result = V2Capability.probeUrl(
                    server.url,
                    username = server.username,
                    password = server.password,
                    http = sharedClient,
                )
                if (result.authWall) {
                    val retry = serverConfig.freshServer()
                    if (retry != null && retry.password != server.password) {
                        server = retry
                        result = V2Capability.probeUrl(
                            server.url,
                            username = server.username,
                            password = server.password,
                            http = sharedClient,
                        )
                    }
                } else if (result.httpStatus == null) {
                    // Unreachable is retryable (reverse mapping / backend still
                    // coming up): re-resolve credentials each round, back off
                    // between rounds, and only then latch UNKNOWN. A single
                    // try used to brick the transport for the rest of the
                    // process on a transient boot blip.
                    var networkAttempt = 0
                    while (result.httpStatus == null && networkAttempt < PROBE_NETWORK_ATTEMPTS) {
                        delay(STARTUP_RETRY_DELAY_MS * (networkAttempt + 1))
                        server = serverConfig.freshServer() ?: server
                        result = V2Capability.probeUrl(
                            server.url,
                            username = server.username,
                            password = server.password,
                            http = sharedClient,
                        )
                        networkAttempt++
                    }
                }
                val generation = result.generation
                // The server may have switched under us; only adopt when it
                // is still the active one.
                if (epoch == probeEpoch.get() && serverConfig.server?.serverKey == server.serverKey) {
                    lastProbe = result
                    withContext(Dispatchers.Main) {
                        // B-7: the verdict is what the session route renders the
                        // full-screen panel from; every other UNKNOWN (auth wall,
                        // HTML, unreachable) clears it.
                        _incompatibleServerVersion.value = result.incompatibleVersion
                        setGeneration(generation, server)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "generation probe failed: ${e.message}")
                if (epoch == probeEpoch.get() && serverConfig.server?.serverKey == server.serverKey) {
                    withContext(Dispatchers.Main) { setGeneration(ServerGeneration.UNKNOWN, server) }
                }
            } finally {
                if (epoch == probeEpoch.get()) {
                    probing.set(false)
                    // Wake every waiter keyed on the round, not on its verdict
                    // ([awaitProbeSettled]): a re-probe that concludes the same
                    // negative leaves `_generation` unchanged and emits nothing.
                    probeSettled.value += 1
                }
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
            ServerGeneration.UNKNOWN -> null
        }
        transportRef.get()?.close()
        transportRef.set(transport)
        if (generation == ServerGeneration.V2) {
            // v82 BUG-C: a fresh V2 verdict **is** the proof that the server
            // answers (`probeUrl` just read `GET /api/info`), so it retires the
            // shell's own 「Not connected — …」 cards exactly like a stream
            // re-establishing does ([ChatStore.Event.TransportRecovered]).
            //
            // That second carrier is not a nicety: while `transportRef` is null
            // there are **no streams at all** — `closeConnections` tore them down
            // or they were never opened — so the streams' own `ESTABLISHED`
            // (the v81 BUG-01 carrier) can never fire, and with it the card that
            // the failed send raised has no way off the screen: `refreshSnapshot`
            // and the idle watchdog both return early without a transport, and
            // every list surface keeps drawing the same failure row. Measured
            // emulator-5558 v82 (`research/v82_verify/5558/45_reg_sent.png` →
            // `50_reg_wait120.png`: the card outlived the whole outage while REST
            // answered again; only a manual Stop/whatever-the-user-did moved the
            // screen).
            scope.launch { applyEvent(ChatStore.Event.TransportRecovered) }
            // The probe window just closed: pull the provider catalog and the
            // agent/command/skill catalogs + default model that parked without
            // a transport, then the session-open work (streams, unseen ack,
            // background-work pull).
            refreshV2Providers()
            refreshV2Catalogs()
            if (started.get()) openV2Streams(server)
            if (sessionID != null) refreshBackgroundTasks()
            // WAVE 2.4d (device v11): the same session-open window reads the
            // inbox once. Both inbox surfaces — the composer's queue panel and
            // the timeline's parked-steer rows — are otherwise *event-driven*
            // (`session.inbox.*`), and `observeSession` clears both, so a
            // session reopened while an item still waits in the inbox showed
            // nothing at all until the next frame. Delivery changes after this
            // read keep arriving through the events.
            if (sessionID != null) refreshInbox()
            if (pendingViewAck) {
                pendingViewAck = false
                ackSessionViewed()
            }
            // A session list read during the probe window came back empty (or
            // failed); the transport exists now, so Home's sessionsRevision
            // collector re-runs the read through v2.
            _sessionsRevision.value += 1
        } else {
            // A read that landed during the probe window failed with no
            // transport; re-drive it now that the verdict is in. Without this
            // an UNKNOWN latch freezes Home on its failed empty list until a
            // process restart. Loop-free: the re-run only probes past
            // [PROBE_LAZY_RETRY_MS], so a dead server settles here quietly.
            // UNKNOWN is three different stories (auth wall / unreachable /
            // not 2.x) — the 3 devices showed 「requires OpenCode 2.x server」
            // for what curl proved was a 401 auth wall on a healthy 2.0.11.
            val probe = lastProbe
            val message = when {
                probe?.authWall == true && !probe.credentialsSent ->
                    "Not connected — this server needs a username/password (save it in Servers)."
                probe?.authWall == true ->
                    "Not connected — the server rejected the saved credentials."
                probe != null && probe.httpStatus == null ->
                    "Not connected — the server did not answer."
                else ->
                    "Not connected — this build requires an OpenCode 2.x server."
            }
            _state.value = ChatStore.reduce(
                _state.value,
                ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.ERROR, message),
            )
            _sessionsRevision.value += 1
        }
    }

    /** Active transport, or null when it has not been chosen yet (probe in flight). */
    private fun transport(): SessionTransport? = transportRef.get()

    /**
     * True when the probe has **settled a negative verdict** and no transport is
     * installed: the host is unreachable, behind an auth wall, or answered as
     * something other than a 2.x server. Nothing will change until a new probe
     * runs, so a caller that would otherwise poll for a transport
     * (`NewSessionScreen`'s Create gate) can stop waiting and report the reason
     * now instead of holding 「Creating a session…」 to the end of its window.
     *
     * The verdict must be an UNKNOWN: a settled *V2* verdict with no transport
     * is the recoverable window a `stop()` leaves behind (the generation flow
     * still says V2 over a cleared transport), which the next
     * [ensureClient]/[retryProbe] rebuilds — never a reason to give up.
     */
    fun transportSettledNegative(): Boolean =
        transportRef.get() == null && !probing.get() && lastProbe?.generation == ServerGeneration.UNKNOWN

    /**
     * Throttled self-heal for a latched probe: kicks one [ensureTransport]
     * round when no transport exists, none is in flight, and the last verdict
     * is retryable ([V2Capability.ProbeResult.isRetryable] — unreachable or
     * auth wall) or absent entirely. A definitive "up, but not 2.x" never
     * re-probes. Safe from any thread; concurrent kicks dedupe on [probing].
     */
    private fun maybeReprobe() {
        if (transportRef.get() != null || probing.get()) return
        if (lastProbe?.isRetryable == false) return
        val now = System.currentTimeMillis()
        if (now - lastProbeAttemptAt < PROBE_LAZY_RETRY_MS) return
        val server = serverConfig.server
        if (server == null || !server.isComplete) return
        lastProbeAttemptAt = now
        ensureTransport(server)
    }

    /**
     * The v2 transport, or null when the probe has not confirmed V2 yet. A
     * null answer kicks [maybeReprobe] so a dead probe heals on demand instead
     * of failing every later call for the rest of the process.
     */
    private fun requireTransport(): SessionTransport? {
        if (transport() == null) maybeReprobe()
        return transport()
    }

    /**
     * The active server changed (`设置→服务器→设为默认`, the connect screen, or a
     * WebView config sync). Drop the old connection and re-run connect + probe
     * so the new server is usable immediately — without this the stale client
     * survives until a process restart.
     */
    private fun adoptActiveServer() {
        val next = serverConfig.server
        if (next == boundServer) return
        boundServer = next
        // A stale verdict must not label the new server — and the throttle
        // must not suppress its first probe.
        lastProbe = null
        lastProbeAttemptAt = 0L
        // B-7: same for the incompatible-server panel — it describes the server
        // that was probed, never the one just selected.
        _incompatibleServerVersion.value = null
        // Nothing connected yet — also the synchronous first revision
        // emission during construction, before the later fields exist (see
        // [adoptReady]). The next ensureClient/start binds the new server —
        // unless a usable row landed late (cold-start config sync while Home
        // never starts): then bind now so Home's failed first read heals
        // through the probe redrive instead of sticking until a restart.
        if (clientRef.get() == null && !started.get()) {
            if (adoptReady && next != null && next.isComplete) ensureClient()
            return
        }
        closeConnections()
        probeEpoch.incrementAndGet()
        probing.set(false)
        activeGeneration = ServerGeneration.UNKNOWN
        _generation.value = ServerGeneration.UNKNOWN
        _availableProviders.value = null
        // A dropped catalog leaves nothing to defend, so the settle budget of
        // the previous server must not carry over to the next one.
        catalogSettleRetries = 0
        serverDefaultModel = null
        if (next == null || !next.isComplete) {
            _configMissing.value = true
            return
        }
        _configMissing.value = false
        ensureClient()
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
        if (activeGeneration == ServerGeneration.V2) openV2Streams(server)
        refreshSnapshot()
        startIdleWatchdog()
    }

    /**
     * v2 streams: `GET /api/event` (global) + per-session
     * `GET /api/experimental/session/{id}/log?after=&follow=true` (durable
     * replay + live). Both share the backoff/heartbeat rules via
     * [EventStream]; only the request builder and the frame adapter differ.
     *
     * Called when V2 is confirmed — either synchronously from `start()`
     * (generation already known) or from [setGeneration] when the probe lands.
     */
    private fun openV2Streams(server: ServerConfigStore.Server) {
        val parsed = server.url.toHttpUrlOrNull() ?: return
        val transport = transportRef.get() as? V2Transport ?: return
        eventStream?.close()
        eventStream = null
        // The ask ledger is fed by the frames this stream carries, and this shell
        // closes it whenever no session screen is mounted (`SessionScreen`'s
        // dispose). A reply that lands in that window is a frame nobody hears, so
        // the ledger's knowledge is only trustworthy for as long as the stream
        // stayed up: restart it here rather than let an answered ask keep a row
        // lit for the life of the process. What is *still* pending is re-learned
        // immediately — the frames that follow, the tracked session's own
        // `…/permission` / `…/form` snapshot ([refreshSnapshot] →
        // [refreshPermissions]), and the location-wide rows every list read
        // unions in ([mergeLocationAsks]) — and each list surface reads before it
        // draws its rows, so no row is rendered from an empty ledger.
        _askLedger.update { SessionAskLedger() }
        val global = EventStream(
            baseHttpUrl = parsed,
            username = server.username,
            password = server.password,
            client = sseClient,
            listener = object : EventStream.Listener {
                override fun onEvent(type: String, data: JSONObject) {
                    onV2Frame(transport, type, data)
                }

                override fun onState(state: EventStream.State) {
                    if (state == EventStream.State.ESTABLISHED) {
                        refreshSnapshot()
                        // v81 BUG-01: the link being back is the proof the
                        // transport cards were waiting for — retire them (and the
                        // live error mirror the timeline's trailing card renders)
                        // instead of leaving the session looking dead until the
                        // user sends into it again. The store write hops to Main
                        // like every other applier; the event itself is narrow
                        // ([ChatStore.Event.TransportRecovered]).
                        scope.launch { applyEvent(ChatStore.Event.TransportRecovered) }
                    } else {
                        // B-17: upstream drops the live metrics the moment the
                        // connection is not `connected` (`debug-bar.tsx:231-239`)
                        // — frames missed during an outage are never replayed, so
                        // a live figure would describe a request this client can
                        // no longer vouch for. The history projection takes over.
                        resetProviderMetrics()
                    }
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
            client = sseClient,
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
                    //
                    // v82 BUG-A: the same route answers `5xx` for a session the
                    // server *refuses* (the missing-project-directory shape), and
                    // that is a route-level failure too — the log is this
                    // session's own stream, so its refusal says nothing about any
                    // other session and cannot be waited out. Both shapes reach
                    // the same page, with the reason telling the copy apart.
                    sessionOpenFailure(code)?.let { failure ->
                        scope.launch { markSessionMissing(id, failure) }
                    }
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
            // `aggregate` = "this frame's seq belongs to the session the
            // watermark is for": the global stream carries every session and
            // each counts its own seq, so a foreign frame is only remembered
            // (its id can replay on this session's log) and never moves the
            // `?after=` this session's log replay depends on.
            seqTracker.observe(frame.id, frame.seq, aggregate = frame.aggregateID == sessionID)
        } else {
            true
        }
        if (!isNew) return
        val routed = V2EventAdapter.sessionID(data)
        val eventType = frame.type.ifEmpty { type }
        // B-17: the debug bar's live provider metrics. Folded from the **raw**
        // frame rather than the store events, because the two facts the cards
        // need are exactly the two the adapter drops: `session.step.ended`'s
        // `TokenUsage.Info` (cost/tokens reach the store only via the next
        // message snapshot) and the envelope's `created` timestamp (TTFT/TTFA/
        // stream-end are all measured against it). Only the tracked session's
        // frames contribute — the global stream carries every session.
        if (routed != null && routed == sessionID) {
            providerMetricEventOf(eventType, frame.raw.optLong("created", 0L), frame.data)
                ?.let { metricEvent ->
                    if (providerMetricsFold.apply(metricEvent)) {
                        _providerMetrics.value = providerMetricsFold.latest
                    }
                }
        }
        // Same liveness rule as the v1 path: any session.* frame routed to the
        // tracked session means the turn is alive — including the ones the
        // adapter maps to no store event (tool.progress, step.streamed), which
        // are the only traffic a tool-heavy turn produces.
        if (eventType.startsWith("session.") && acceptsV2SessionFrame(sessionID, routed)) {
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
            // v78 P3: a RUNNING frame may not re-open a turn this client has
            // already stopped ([StoppedTurn.allowsRunAssert]). The record alone
            // could not do it: the adapter maps seven frame types onto RUNNING
            // and every one of them reached the store, so the dying step's
            // liveness (`session.step.streamed` / `session.step.ended`, emitted
            // while the interrupted execution's cleanup settles) put the
            // composer back on Stop within a second of a `{interrupted:true}`
            // answer — and only the idle watchdog's grace ended it, ~10 s later,
            // with the delivery chip reading 「Steer」 in the meantime
            // (`research/v78_verify/5556/27-29`). Dropped *before* the
            // notification/ask mirrors so a stopped turn cannot be announced as
            // running again either; the frame's liveness already stamped
            // `lastPartEventAt` above, and a run that really starts announces
            // itself with `session.execution.started` / `session.step.started`,
            // which clear the record.
            if (event is ChatStore.Event.RunStatusChanged &&
                event.status == ChatStore.RunStatus.RUNNING &&
                !stoppedTurn.allowsRunAssert(routed, eventType)
            ) {
                continue
            }
            notifyFor(event, routed)
            recordAsk(event, routed)
            if (!acceptsV2Session(sessionID, routed)) continue
            if (event is ChatStore.Event.PartUpdated || event is ChatStore.Event.MessageUpdated) {
                lastPartEventAt = System.currentTimeMillis()
            }
            // A part frame is the only thing on the wire that means "a turn is
            // producing output" (`session.step.started` carries one). It is what
            // separates a turn from a session *update* — a 「移动到…」 move, a
            // rename, a location change — which the server also frames as
            // busy → idle and which used to be announced as 「回复已完成」 for a
            // session that had never been prompted (v50 tD: three Moves, three
            // banners/notifications). A `MessageUpdated` is deliberately not
            // counted: moving a turn rewrites exactly those rows.
            if (event is ChatStore.Event.PartUpdated) turnRunStatus.step(routed)
            if (event is ChatStore.Event.RunStatusChanged && event.status == ChatStore.RunStatus.RUNNING) {
                lastPartEventAt = System.currentTimeMillis()
                // A run the wire *starts* is a run again: whatever Stop this
                // client confirmed is history, and the live set may assert
                // RUNNING over the transcript again ([StoppedTurn]). A liveness
                // frame of the run that was stopped is not a start, so it leaves
                // the record alone.
                if (StoppedTurn.startsRun(eventType)) stoppedTurn.newRun(routed)
            }
            scope.launch { applyEvent(event) }
            // `session.compacted` replaces the history server-side; the local
            // rows cannot self-heal, so reconcile via snapshot.
            if (event is ChatStore.Event.CompactionProgress && event.phase == "compacted") {
                refreshSnapshotThrottled()
            }
            if (event is ChatStore.Event.RunStatusChanged &&
                event.status == ChatStore.RunStatus.IDLE
            ) {
                refreshSnapshotThrottled()
                // WAVE 2.4b: the turn settled with queued follow-ups — drain
                // the parked first row as steer.
                drainQueueOnIdle()
                // The idle boundary the viewer just observed: ack it and let
                // the summary's background-work list reflect the settled turn.
                ackSessionViewed()
                refreshBackgroundTasks()
            }
        }
    }

    /** v2 session gate: only a frame whose `data.sessionID` (or durable aggregate) IS the tracked session. */
    private fun acceptsV2Session(currentID: String?, routed: String?): Boolean =
        acceptsV2SessionFrame(currentID, routed)

    /**
     * Tears down streams + client + transport. Only the SSE streams are torn
     * down. The shared OkHttpClient (also used by the SSE client via
     * newBuilder) must NEVER be shut down here: OkHttp's Dispatcher executor
     * does not rebuild after shutdown() and every later enqueue would be
     * rejected, leaving the stream dead for the rest of the process. A prompt
     * POST already in flight keeps running to completion (review MUST-FIX #3
     * semantics).
     */
    private fun closeConnections() {
        eventStream?.close()
        eventStream = null
        sessionLogStream?.close()
        sessionLogStream = null
        clientRef.set(null)
        transportRef.get()?.close()
        transportRef.set(null)
        seqTracker.reset()
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        // The watchdog loop is scope-owned and re-checks `started` every tick.
        idleWatchdog = null
        closeConnections()
    }

    /**
     * The foreground idle watchdog ([TurnIdleWatchdog]): the second carrier for
     * "this turn is over", for the case the terminal frame never arrives.
     *
     * Every other path that settles a turn is *event-driven* — a frame, or the
     * snapshot backstop, which itself only runs when a frame or a reconnect
     * triggers it. v54 tB showed what is left over: the assistant's last
     * message was `completed`, the server had been idle for 44 s+, the app was
     * in the foreground on the session, and the composer still offered Stop,
     * because nothing was pending that could trigger the backstop. This loop
     * is what is always pending: while the tracked session believes a turn is
     * running and the client has heard nothing for [IDLE_GRACE_MS], it asks
     * the server's live set and settles on its answer.
     *
     * One instance for the view model's life; the loop is the only moving part
     * and it exits with [stop] (or [destroy]'s scope cancel).
     *
     * The loop runs on `Dispatchers.Default`, not the view-model scope's
     * `Dispatchers.Main.immediate`: a backgrounded process has its main looper
     * suspended, and a `delay` chained to that dispatcher simply never fires
     * there — which is exactly the situation the settle branch exists for,
     * since its [raise] is the only carrier left that can announce a
     * backgrounded turn whose terminal frame was lost. Only the store write
     * hops back to Main ([applyEvent]'s compound update stays single-threaded
     * like every other applier); the notification is raised *before* that hop
     * so a frozen main thread can never take the announcement down with it.
     *
     * v82 BUG-C: a tick with **no transport** is not skipped. That state (a
     * dropped transport, the very one that raises the 「Not connected」 card)
     * leaves nothing that can answer for the turn, and this class's own policy
     * for an unanswerable probe is to settle — a locked composer is the worse
     * outcome. That branch stays quiet (no [raise]): nothing proved the reply
     * landed, so all it does is unlock the composer.
     */
    private fun startIdleWatchdog() {
        if (idleWatchdog != null) return
        val watchdog = TurnIdleWatchdog(IDLE_GRACE_MS) { lastPartEventAt }
        idleWatchdog = watchdog
        scope.launch(Dispatchers.Default) {
            while (started.get()) {
                delay(IDLE_WATCHDOG_TICK_MS)
                val id = sessionID ?: continue
                val transport = transportRef.get() as? V2Transport
                // v82 BUG-C: **no transport is an unanswerable probe**, not a
                // reason to skip the tick. The class's own policy for an answer
                // this client cannot vouch for (`null`, "[the turn is] either
                // over or unreachable, and in both cases a locked composer is
                // the worse outcome") applies verbatim here — and this is
                // precisely the state that raises the 「Not connected」 card (a
                // send with no transport, v76), so skipping meant a turn that
                // was RUNNING when the link died stayed on Stop for as long as
                // the user left it alone: the watchdog and the snapshot backstop
                // *both* return early without a transport, so the only exit was
                // the manual Stop the v82 report measured
                // (`research/v82_verify/5558/50_reg_wait120.png`: still Stop,
                // still the card, 120 s after the reply had landed).
                //
                // Deliberately quiet: no notification. The settle branch below
                // announces 「the reply landed」 because a snapshot proved the
                // turn over; here nothing proved anything, so this only unlocks
                // the composer (and a live turn's next frame restores RUNNING).
                if (transport == null) {
                    if (watchdog.probeDue(_state.value.runStatus)) {
                        withContext(Dispatchers.Main) {
                            applyEvent(ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.IDLE))
                        }
                    }
                    continue
                }
                if (!watchdog.probeDue(_state.value.runStatus)) continue
                val live = try {
                    withContext(Dispatchers.IO) { transport.activeRunStatus(id) }
                } catch (_: Exception) {
                    null
                }
                if (sessionID != id) continue
                when (watchdog.onProbe(_state.value.runStatus, live, liveSetTrusted = stoppedTurn.allowsLiveAssert(id))) {
                    TurnIdleAction.NONE -> Unit
                    // The server still lists the session: it is working, and the
                    // silence is a long tool wait (or a turn the client missed
                    // the start of). Re-arm so the next probe is a full grace
                    // away instead of hammering the endpoint.
                    TurnIdleAction.REARM -> lastPartEventAt = System.currentTimeMillis()
                    TurnIdleAction.SETTLE_IDLE -> {
                        // The same conclusion, and the same notification, the
                        // backstop reaches ([refreshSnapshot]'s reconcile-settle):
                        // the terminal frame is not coming, so *this* client is
                        // the only thing that can both unlock the composer and
                        // say the reply landed. `viewed`/focus still decide
                        // whether the notification is delivered. The raise comes
                        // before the Main-hopped store write: NotificationManager
                        // is thread-agnostic, and it must not wait on a looper
                        // that may be the very thing that is frozen.
                        val since = turnRunStatus.since(id) ?: lastPartEventAt
                        Log.d(NOTIFY_TAG, "watchdog settle: session=$id since=$since live=$live")
                        turnRunStatus.settled(id)
                        raise(AppNotificationKind.AGENT, sessionID = id, since = since)
                        withContext(Dispatchers.Main) {
                            applyEvent(ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.IDLE))
                        }
                        // Unthrottled: the transcript is what the user is looking
                        // at, and the backstop that would normally fetch it here
                        // is exactly what the lost frame took with it.
                        refreshSnapshot()
                    }
                }
            }
        }
    }

    private fun requireClient(): OpenCodeClient? {
        clientRef.get()?.let { return it }
        // A missing client is not a missing config: the object can be gone
        // while the server row is intact (a flap tore the base down before the
        // probe rebuilt it). Rebind first; only a server that is really absent
        // may raise the setup hint.
        if (ensureClient()) return clientRef.get()
        onConfigMissing()
        _configMissing.value = true
        _state.value = ChatStore.reduce(
            _state.value,
            ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.ERROR, NOT_CONNECTED_LINE),
        )
        return null
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
        // No transport yet (probe running or verdict not v2): setGeneration
        // pulls this. The v1 route must not run — a v2 server answers
        // `GET /config` with SPA HTML and the parse crashes.
        if (requireTransport() == null) return
        if (activeGeneration == ServerGeneration.V2) return
        val client = clientRef.get() ?: return
        scope.launch {
            try {
                val config = withContext(Dispatchers.IO) { client.config() }
                serverDefaultModel = OpenCodeClient.parseModelRef(optStringOrNull(config, "model").orEmpty())
                // Only ever *adopt* a configured default — never clear the chip
                // with it. This server leaves `/config.model` empty, so the
                // session-derived model (`refreshSnapshot`: the latest assistant
                // turn's own `providerID`/`modelID`) is the only truth available;
                // unconditionally assigning the (null) default here wiped it, and
                // the chip fell back to the generic「模型」placeholder whenever the
                // config refresh landed after the snapshot.
                if (modelOverride == null && serverDefaultModel != null) {
                    applyEffectiveModel()
                }
                syncModelLabel()
            } catch (e: Exception) {
                Log.w(TAG, "config fetch failed: ${e.message}")
            }
        }
    }

    /**
     * Public re-pull hook for **settings-side writes** (`disconnect` / `connect` /
     * wellknown / credential rename). Those change provider membership without
     * re-emitting the catalog stream, so the "Connected providers: N" count
     * would otherwise survive a tab switch carrying its stale value. No
     * throttling here — the throttle lives in `catalogRefresh`.
     */
    fun requestProvidersRefresh() {
        refreshProviders()
    }

    /** Provider catalog for the picker; one silent retry, then the chip toasts. v1 only (v2: model list). */
    private fun refreshProviders() {
        // No transport yet (probe running or verdict not v2): setGeneration
        // pulls this. The v1 route must not run — a v2 server answers
        // `GET /provider` with SPA HTML and the parse crashes.
        if (requireTransport() == null) return
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
     * already renders (`connected` = providers whose linked integration
     * reports a stored connection — one `GET /api/integration` list read,
     * no per-integration detail fan-out; model entries keep `limit.context` /
     * `limit.input` for the context ring, variant ids for the effort picker and
     * the entry-level id for row identity — the live catalog holds several
     * entries per `modelID` on `openai`, see
     * [OpenCodeClient.Providers.Model.fromV2]). The ring's denominator is
     * `min(limit.input, limit.context)` off that same fold — see
     * [ContextUsage.windowOf].
     *
     * WAVE 2.4d (device v11): the read is a full replacement, so a **short**
     * answer must not be allowed to win. The catalog flow is
     * `GET /api/provider` then `GET /api/model`; when the second call answered
     * with a handful of entries (8 of several hundred, all for one provider)
     * the picker collapsed to those rows, the chip's label fell back to the
     * raw ref id and the check mark vanished — and nothing ever re-read the
     * catalog, so it stayed broken until the session was left and reopened.
     * [ModelPickerItems.plausibleCatalog] now gates the write: an implausible
     * answer leaves the loaded catalog alone and re-reads after a short
     * settle delay, up to [CATALOG_SETTLE_ATTEMPTS] times. Only the *last*
     * attempt may land a non-empty short catalog (the server is then the
     * truth); an empty one never lands while a catalog is on screen.
     */
    private fun refreshV2Providers() {
        val transport = transportRef.get() as? V2Transport ?: return
        scope.launch {
            try {
                val api = transportApi(transport) ?: return@launch
                val providers = withContext(Dispatchers.IO) { api.providers(directory) }
                val models = withContext(Dispatchers.IO) { api.models(directory) }
                if (providers.isEmpty()) {
                    // Same jitter one level up: an empty provider list would
                    // map to a catalog with no models at all.
                    settleV2Providers()
                    return@launch
                }
                val connectedIds = withContext(Dispatchers.IO) {
                    v2ConnectedProviderIds(providers, api.integrations(directory))
                }
                val all = providers.map { p ->
                    OpenCodeClient.Providers.Provider(
                        id = p.id,
                        name = p.name,
                        models = models.filter { it.providerID == p.id }
                            .map(OpenCodeClient.Providers.Model::fromV2),
                    )
                }
                val next = OpenCodeClient.Providers(
                    all = all,
                    connected = all.filter { it.id in connectedIds }.map { it.id },
                    default = emptyMap(),
                )
                val previous = _availableProviders.value
                if (!ModelPickerItems.plausibleCatalog(previous, next)) {
                    val retriesLeft = catalogSettleRetries < CATALOG_SETTLE_ATTEMPTS
                    Log.w(
                        TAG,
                        "v2 catalog short read (${ModelPickerItems.modelCount(next)} of " +
                            "${ModelPickerItems.modelCount(previous)} models, retriesLeft=$retriesLeft)",
                    )
                    if (retriesLeft) {
                        settleV2Providers()
                        return@launch
                    }
                    // Settled on a short answer: accept it — unless it is empty,
                    // which would blank the whole picker.
                    if (ModelPickerItems.modelCount(next) == 0) return@launch
                }
                catalogSettleRetries = 0
                _availableProviders.value = next
                syncModelLabel()
            } catch (e: Exception) {
                Log.w(TAG, "v2 providers fetch failed: ${e.message}")
            }
        }
    }

    /**
     * WAVE 2.4d: one bounded re-read after a short catalog answer, backing off
     * by attempt. The counter is reset by the next accepted catalog (and by
     * [adoptActiveServer], where the catalog is dropped outright).
     */
    private fun settleV2Providers() {
        val attempt = catalogSettleRetries
        if (attempt >= CATALOG_SETTLE_ATTEMPTS) return
        catalogSettleRetries = attempt + 1
        scope.launch {
            delay(CATALOG_SETTLE_RETRY_DELAY_MS * (attempt + 1))
            refreshV2Providers()
        }
    }

    /**
     * Chip label = the selected **entry's** catalog name (upstream chip), so
     * tapping `gpt-5.6-fast` shows「GPT-5.6 Fast」and not the base entry's name;
     * falls back to the raw ref id when the catalog has not loaded it yet.
     */
    private fun syncModelLabel() {
        val selected = _selectedModel.value
        _modelLabel.value = selected?.let { (providerID, modelID) ->
            _availableProviders.value?.model(providerID, modelID)?.name ?: modelID
        }
    }

    /**
     * Re-derives [selectedModel] from its sources — the pick for this context,
     * the tracked session's own model, the server default, the picker memory
     * ([effectiveModelRef]) — and republishes the chip label.
     *
     * Every *derived* write goes through here, so the order can never be
     * bypassed by a refresh that lands later: opening a session created with a
     * model of its own shows that model, not the server default the read
     * happened to return first (the measured v28 bug), and a stale label from
     * the previous session is dropped the moment the new one is observed
     * (passing a null resolution clears the chip — nothing here names a model).
     */
    private fun applyEffectiveModel() {
        _selectedModel.value = effectiveModelRef(
            picked = modelOverride,
            session = _sessionModel.value,
            serverDefault = serverDefaultModel,
            recent = recentModel,
        )
        syncModelLabel()
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
        // No transport yet (probe running or verdict not v2): setGeneration
        // pulls the v2 catalogs. The v1 routes must not run against a v2 SPA.
        if (requireTransport() == null) return
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
            val api = transportApi(transport) ?: return@launch
            try {
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
                // F-1: the two `@` sources that had no UI caller. Both are
                // location-scoped reads; an empty answer is a real answer (a
                // project without references, no connected MCP server), so the
                // lists are assigned unconditionally — a removed reference must
                // disappear from the menu. Each read is isolated: a failing
                // `/reference` must not cost the model-label refresh below.
                runCatching { MentionSources.references(api.references(directory)) }
                    .onSuccess { _availableReferences.value = it }
                runCatching { MentionSources.resources(api.mcpResourceCatalog(directory)) }
                    .onSuccess { _availableResources.value = it }
            } catch (e: Exception) {
                Log.w(TAG, "v2 catalog fetch failed: ${e.message}")
            }
            // V2's model-label refresh (`refreshModelLabel` early-returns on
            // v2); runs even when a catalog endpoint failed.
            refreshV2DefaultModel(api)
        }
    }

    /**
     * `GET /api/model/default` → the server default (v1 reads `GET /config.model`).
     * The endpoint answers a full `Model.Info`, so the ref takes its **entry**
     * id (`Model.Info.id`, the value a session's `Model.Ref.id` is matched
     * against) rather than `modelID`, which several `openai` entries share.
     *
     * Stored unconditionally (it is also what 「使用服务器默认」 falls back to)
     * but *applied* through [applyEffectiveModel]: a session with a model of its
     * own keeps showing — and running — that model, and the default only decides
     * a session that names none (upstream `fallback`,
     * `providers/models/selection.tsx:234-249`, sits behind `durable()`).
     */
    private suspend fun refreshV2DefaultModel(api: V2Api) {
        try {
            val entry = withContext(Dispatchers.IO) { api.defaultModel(directory) } ?: return
            serverDefaultModel = entry.providerID to entry.id.ifEmpty { entry.modelID }
            applyEffectiveModel()
        } catch (e: Exception) {
            Log.w(TAG, "v2 default model fetch failed: ${e.message}")
        }
    }

    /**
     * V2Api handle for catalog reads (same instance the transport owns).
     *
     * [location] is the instance's default `location[directory]`; pass `null`
     * for a route that carries its own (`DELETE /api/debug/location`, whose
     * override would otherwise be sent **twice** — the builder appends the
     * default and `located` appends the override).
     */
    private fun transportApi(
        transport: V2Transport,
        location: String? = this.directory,
        http: OkHttpClient = sharedClient,
    ): V2Api? {
        // Same credential source as the probe ([ServerConfigStore.freshServer]):
        // the in-memory snapshot can predate the WebView config sync.
        val server = serverConfig.freshServer() ?: serverConfig.server ?: return null
        val parsed = server.url.toHttpUrlOrNull() ?: return null
        return V2Api(parsed, server.username, server.password, http, location)
    }

    private fun V2Api.AgentInfo.toV1(): OpenCodeClient.Agent = OpenCodeClient.Agent(
        name = id,
        description = description,
        mode = mode,
        // B-9: read the wire's `native` **presence-strictly**. The row used to
        // hardcode `false`, which under upstream's `hasCustomAgent`
        // (`native === false`) would mark every v2 agent as custom and keep the
        // composer's agent chip on no matter what the setting says.
        native = if (raw.has("native")) raw.optBoolean("native") else null,
        hidden = hidden,
    )

    /**
     * v2 `GET /api/session` row → the [OpenCodeClient.Session] shape Home, the
     * drawer and `HomeSessions` already consume. `cost`/`revert` are read off
     * [V2Api.SessionInfo.raw] so an absent `cost` stays null (rendered as no
     * figure) instead of a fake `$0`.
     */
    private fun V2Api.SessionInfo.toUiSession(): OpenCodeClient.Session = OpenCodeClient.Session(
        id = id,
        title = title,
        directory = directory,
        updatedAt = timeUpdated,
        cost = raw.optDouble("cost", Double.NaN).takeIf { !it.isNaN() },
        shareUrl = null,
        revertMessageID = null,
        archivedAt = timeArchived,
        // v54 P1: kept, not dropped — a session created in a window bound to a
        // subpath answers with the project root as `location.directory` and the
        // subpath here, so dropping it made Home lose the row (and its search
        // hit) against the subpath project the user actually opened
        // ([HomeSessions.sessionDirectory]).
        subpath = subpath,
    )

    /**
     * The same row's unread watermark ([SessionUnread]). `time.idle`/`time.viewed`
     * are read off [V2Api.SessionInfo.raw] like `cost` above: the promoted
     * [V2Api.SessionInfo] fields stop at `archived`, and the shared
     * [OpenCodeClient.Session] shape (Home/drawer/`HomeSessions`) has no slot for
     * them — the two fields exist only on v2
     * (`_upstream/packages/schema/src/session.ts:44-49`), where they are written
     * by the projector (`core/src/session/projector.ts:419-425,583-593`).
     */
    private fun V2Api.SessionInfo.toUnread(): SessionUnread {
        val time = raw.optJSONObject("time")
        return SessionUnread(
            // A `0` is "no transition recorded" on this transport's wire format,
            // not the epoch — same rule `timeArchived` uses.
            idle = time?.optLong("idle", 0L)?.takeIf { it > 0L },
            viewed = time?.optLong("viewed", 0L)?.takeIf { it > 0L },
        )
    }

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
        // B-9: upstream disables the `agent.cycle` commands while the control is
        // hidden (`composer/commands.tsx:71,79` — `disabled: !local.agent.visible()`),
        // so a chord must not pin an agent the user cannot see. Clearing the pin
        // (above) stays allowed: it is how the setting change itself resolves.
        if (!agentPickerVisible.value) return
        val known = OpenCodeClient.Agent.selectable(_availableAgents.value).any { it.name == trimmed }
        if (known) _selectedAgent.value = trimmed
    }

    /**
     * Pin the model variant ([OpenCodeClient.Providers.Model.variants] label).
     * Null (or `"default"`) means "follow the server/agent default".
     *
     * The pin belongs to the context it was made in (v69): the tracked session,
     * or the draft when none is tracked — the same split [selectModel] makes.
     */
    fun selectVariant(label: String?) {
        val trimmed = label?.trim().orEmpty()
        pinVariant(
            trimmed.takeIf { it.isNotEmpty() && it != ModelPickerItems.VARIANT_DEFAULT },
        )
        // v73: and onto the session, the same way the model tap lands there.
        // The chip alone was never enough — the effort lived in this process
        // (and, since the local copy below, on this device), so the server kept
        // running the effort it was last *told*, and a reopen on another
        // client disagreed with the label.
        pinSelectedVariant()
    }

    /**
     * The one writer of [selectedVariant] **and** of the slot it belongs to.
     *
     * Publishing the flow without recording the slot is what leaked: the chip
     * read one process-wide value, so a pin made in the session on screen was
     * still the pin the next session opened with. `null` clears the slot
     * rather than storing an empty label, so 「默认」 is a real absence.
     *
     * The two server-side restores (v72: the snapshot's `SessionRow.variant`
     * adopt, the `session.model.selected` frame reconcile) publish the flow
     * directly instead: they write the slot the value already belongs to at
     * the call site, so routing them through here would only re-file the same
     * value under the same session.
     *
     * v73: the write also reaches the durable per-session copy
     * ([SettingsStore.setSessionVariant]) — upstream keeps this half in
     * `saved.session[id]` (`selection.tsx:286-299`), which is what reopens a
     * session with the effort its chip showed even when the server never heard
     * about it.
     */
    private fun pinVariant(value: String?) {
        _selectedVariant.value = value
        val id = sessionID
        if (id == null) {
            draftVariant = value
            return
        }
        if (value == null) sessionVariants.remove(id) else sessionVariants[id] = value
        settingsStore?.setSessionVariant(id, value)
    }

    /**
     * The agent a write actually runs as: the pinned one, else the server's first
     * selectable primary agent (`"build"` as the last resort — the value
     * `POST /session/:id/shell` requires).
     */
    fun effectiveAgent(): String =
        pinnedAgent() ?: OpenCodeClient.Agent.defaultName(_availableAgents.value)

    /**
     * B-9: the pinned agent **as a write may use it** — ignored while the
     * composer hides the agent control, which is upstream's own rule
     * (`providers/models/selection.tsx:188`: the current selection is only
     * consulted when `agentsVisible()`, otherwise `"build"`). A pin recorded
     * before the setting was switched off must not keep steering turns from a
     * control the user can no longer see.
     */
    private fun pinnedAgent(): String? = if (agentPickerVisible.value) _selectedAgent.value else null

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
            .firstOrNull { it.name == pinnedAgent() }
            ?.takeIf { it.providerID == selected.first && it.modelID == selected.second }
            ?.variant
        return ModelPickerItems.resolveVariant(
            selected = _selectedVariant.value ?: agentVariant,
            variants = variants,
            configured = agentVariant,
        )
    }

    /**
     * The model that produced the ring's numerator: `UiMessage.model` of the
     * newest assistant message with token usage ([ContextUsage.lastModelRef]),
     * parsed into a catalog ref. Upstream reads its limit off exactly this
     * message (`session/timeline/session-context-usage.tsx:52-54`), and so does
     * the context tab (`SessionContextModel.modelRef`), so the ring's
     * denominator and its numerator always describe the same turn.
     *
     * Derived from [state] (the *rendered* store, reverted tail hidden), not
     * from `_state`: the numerator the screen falls back to is
     * `ContextUsage.lastTokenTotal(state.messages)`, and a reverted turn must
     * not keep lending the ring its window.
     */
    private val _transcriptModel: StateFlow<Pair<String, String>?> =
        state.map { ContextUsage.lastModelRef(it.messages)?.let(OpenCodeClient::parseModelRef) }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * The tracked session's own model ref (`GET /api/session/{id}` →
     * `SessionRow.providerID/modelID`), refreshed with every snapshot. The
     * second source of [contextLimit]: a session whose transcript window was
     * trimmed (or whose newest usage row is still in flight) still knows which
     * model it is running.
     */
    private val _sessionModel = MutableStateFlow<Pair<String, String>?>(null)

    /**
     * The same session model, off the **wire**: `session.model.selected{model,
     * previous?}` (`session-event.ts:72-92`) is the value another client just
     * made durable on this session, and the composer chip must not wait for the
     * next snapshot to show it (upstream reconciles the selection reactively —
     * `providers/models/selection.tsx:139-143,286-299`).
     *
     * It lands in [_sessionModel], the slot the snapshot read writes: this is
     * the *session's* model, **not** a local pick, so it never touches
     * [sessionPicks] / [recentModel] (a pick made in this session still
     * outranks it, [effectiveModelRef]) and can never be written back to the
     * server ([selectedModelPin]). Only the tracked session's frames arrive —
     * [ChatStore.reduce] gates [ChatStore.Event.SessionSelectionChanged] by
     * `tracksSession` — and a `null` (the store resetting on a session switch)
     * says nothing about any model, so it is ignored: clearing is
     * [observeSession]'s and the snapshot read's own job.
     *
     * The frame's variant rides the same path: the session's durable effort
     * (`ChatStore.ChatState.sessionVariant`) is adopted into this session's
     * slot ([sessionVariants]), so a cold restart still reads the `· max`
     * suffix back off the server instead of showing the model alone. Only a
     * real switch frame moves it — a model's own pick stays authoritative
     * ([selectedModelPin]) and an agent frame never clears it ([ChatStore]
     * keeps the effort on the model side only).
     */
    private val sessionModelFrames = scope.launch {
        _state.map { it.sessionModel to it.sessionVariant }.distinctUntilChanged().collect { (frame, frameVariant) ->
            val ref = frame?.let(OpenCodeClient::parseModelRef) ?: return@collect
            val id = sessionID
            var changed = false
            if (ref != _sessionModel.value) {
                _sessionModel.value = ref
                changed = true
            }
            val variant = frameVariant?.takeIf { it.isNotBlank() && it != ModelPickerItems.VARIANT_DEFAULT }
            val pinned = if (id == null) draftVariant else sessionVariants[id]
            if (variant != pinned) {
                if (id == null) {
                    draftVariant = variant
                } else if (variant == null) {
                    sessionVariants.remove(id)
                } else {
                    sessionVariants[id] = variant
                }
                changed = true
            }
            if (changed) {
                _selectedVariant.value = variant
                applyEffectiveModel()
            }
        }
    }

    /**
     * `session.renamed{title}` off the wire, into [knownTitles] — so a rename
     * this device did not make is learned the moment it happens, and a session
     * opened afterwards is named by it rather than by whatever the caller was
     * holding at navigation time.
     *
     * [ChatStore.reduce] gates [ChatStore.Event.SessionRenamed] on
     * `tracksSession`, so the store only carries the frame for the session on
     * screen; the pair below is that store value. A `null` title is the store
     * resetting on a session switch, not a name, so it records nothing — the
     * registry is never cleared (a tab the user switched away from keeps its
     * name, which is the whole point of the strip's per-tab titles).
     */
    private val sessionTitleFrames = scope.launch {
        _state.map { it.sessionID to it.sessionTitle }
            .distinctUntilChanged()
            .collect { (id, title) ->
                if (!id.isNullOrEmpty() && !title.isNullOrBlank()) publishCurrentTitle(id, title)
            }
    }

    /**
     * Context window of the currently selected model, straight from the
     * provider catalog (`GET /provider`; v2 folds `GET /api/provider` +
     * `GET /api/model`) — the entry's **effective** window, `min(limit.input,
     * limit.context)`, see the v20 note below. `null` when no source resolves
     * against the catalog — the ring then renders its documented empty track
     * instead of a fake denominator.
     *
     * WAVE (v16): **multi-source** ([contextLimitFrom]). The old single source
     * (the picker's effective model) left the ring permanently empty on a
     * server whose default-model read is unavailable — the context tab kept
     * showing `Context Limit` because it resolves the *message's* model
     * instead. The transcript row now leads, with the session ref and the
     * picker behind it.
     *
     * WAVE (v20): the number is the entry's **effective window**
     * `min(limit.input, limit.context)` ([ContextUsage.windowOf]) and not the
     * bare `limit.context` — the server compacts on the input half, so the raw
     * context is a denominator nothing on the server uses. Both halves are
     * fields of the catalog entry itself
     * ([OpenCodeClient.Providers.Model.limitInput], folded from the same
     * `GET /api/model` document as `limit.context`), so the four flows below
     * are the whole input: a catalog write can never be read half-applied.
     */
    val contextLimit: kotlinx.coroutines.flow.StateFlow<Int?> =
        kotlinx.coroutines.flow.combine(
            _availableProviders,
            _transcriptModel,
            _sessionModel,
            _selectedModel,
        ) { providers: OpenCodeClient.Providers?, transcript: Pair<String, String>?,
            session: Pair<String, String>?, selected: Pair<String, String>? ->
            contextLimitFrom(providers, transcript, session, selected)
        }.stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * Session spend for the context-ring tooltip (`Session.Info.cost`,
     * upstream `context.usage.cost`). Refreshed with the snapshot's session-info
     * read; `null` = not reported (the tooltip then hides the row).
     */
    private val _sessionCost = MutableStateFlow<Double?>(null)
    val sessionCost: StateFlow<Double?> = _sessionCost.asStateFlow()

    /**
     * Context-ring numerator from `GET /api/session/{id}/context` — the active
     * context (messages after the last compaction), newest assistant row's full
     * usage total ([SessionContextModel.lastAssistantTotal], every bucket as
     * upstream `session-context-metrics.ts`). `null` until the first read lands;
     * the screen then falls back to the live transcript total.
     */
    private val _activeContextTokens = MutableStateFlow<Long?>(null)
    val activeContextTokens: StateFlow<Long?> = _activeContextTokens.asStateFlow()

    /**
     * The raw rows behind [activeContextTokens] — **the same** read, kept whole
     * instead of reduced to its total. The context tab's `ctx()` half
     * (`SessionContextModel.project(activeMessages = …)`) needs the newest
     * assistant row's provider / model / buckets / timestamp, not only their sum.
     *
     * One read feeds both, and [SessionContextModel.lastAssistantTotal] is the one
     * rule both apply, so the tab's `Total Tokens` / `Usage` rows and the ring's
     * numerator are the same message of the same fetch and move together on every
     * snapshot. The v17-23 device finding was the tab reading this route through a
     * **second**, one-shot fetch of its own that froze at the tab's first open:
     * the ring showed 42 % of the live total while the tab still printed the first
     * turn's `9,154` / `1 %`.
     *
     * `null` until the first read lands, on v1 where the route does not exist, and
     * after a failed read — [SessionContextModel.project] then keeps its
     * full-list fallback.
     */
    private val _activeContextRows = MutableStateFlow<List<JSONObject>?>(null)
    val activeContextRows: StateFlow<List<JSONObject>?> = _activeContextRows.asStateFlow()

    /** The once-per-turn throttle behind [maybeAutoCompact] (see the class note). */
    private val compactionTrigger = ContextCompactionTrigger()

    /**
     * Pre-emptive automatic compaction — the v19 P2 finding, and the client half
     * of upstream's `SessionCompaction.required`
     * (`_upstream/packages/core/src/session/compaction.ts:742-763`; the whole
     * rule, with the measured numbers, is documented on
     * [ContextCompactionPolicy]).
     *
     * Upstream's own trigger sits at `context - max(min(output, 32k), buffer)` —
     * 96.9 % of a 1,048,576-token window — and it is only evaluated at a step
     * boundary against a *completed* turn's usage. A turn that adds more than the
     * margin (v19: `input=643K` + `cache_read=1.3M`) therefore walks straight
     * past it and the provider answers `InvalidRequest`; the session is stuck,
     * and even the manual compaction fails because its own summary request is
     * over the window. This collector is the earlier trigger that keeps the
     * session out of that zone.
     *
     * Why it hangs off these flows: [activeContextTokens] is refreshed from
     * `GET /api/session/{id}/context` by every snapshot, i.e. once per session
     * advance, [contextLimit] is the same window the ratio's denominator comes
     * from — the two halves the ring paints — and the run status is the
     * progress gate above. Reading them (never writing them) keeps this policy
     * out of the context-loading chain, which the context panel owns.
     *
     * Nothing fires while the session has no id, while either half is unknown,
     * while the window is already full (above the limit the attempt could only
     * fail — `ContextCompactionPolicy.hasHeadroom`; that state is the error
     * card's, not the trigger's), while no turn is in flight
     * (`ContextCompactionPolicy.isAdvancing` — an automatic compaction spends a
     * full-history model call, so it is part of session progress and not of
     * opening a session), or while the same turn has already asked
     * ([ContextCompactionTrigger]); the request itself is [compactSession]'s plain
     * `POST /api/session/{id}/compact`, the route upstream's own palette command
     * sends (`use-session-commands.tsx:236`) — "steers by default: it runs at the
     * next step boundary instead of waiting behind queued prompts"
     * (`protocol/src/groups/session.ts:510`), so it is safe mid-turn.
     *
     * The run status is a third input rather than a read inside the callback: the
     * context read repeats the same total for every snapshot of one step, so a
     * two-flow `combine` would not re-evaluate when the turn actually starts and
     * the gate would be missed for the whole turn.
     */
    private val autoCompaction = scope.launch {
        combine(
            _activeContextTokens,
            contextLimit,
            _state.map { it.runStatus }.distinctUntilChanged(),
        ) { tokens, limit, runStatus -> Triple(tokens, limit, runStatus) }
            .collect { (tokens, limit, runStatus) -> maybeAutoCompact(tokens, limit, runStatus) }
    }

    /**
     * One evaluation of the automatic-compaction policy (see [autoCompaction]).
     * The turn anchor is the newest assistant row that reported usage
     * ([ContextUsage.lastUsage]) — the row the ring reads its numerator from, so
     * "one compaction per turn" means the same turn the ratio describes.
     */
    private fun maybeAutoCompact(tokens: Long?, limitContext: Int?, runStatus: ChatStore.RunStatus) {
        if (!ContextCompactionPolicy.isAdvancing(runStatus)) return
        val id = sessionID
        val turnKey = ContextUsage.lastUsage(_state.value.messages)?.id
        if (!compactionTrigger.shouldFire(id, turnKey, tokens, limitContext, System.currentTimeMillis())) {
            return
        }
        Log.i(TAG, "context $tokens/$limitContext over ${ContextCompactionPolicy.DEFAULT_RATIO} — compacting")
        // A failed request keeps the attempt spent: the trigger's `shouldFire`
        // armed it already, so the session is not hammered on every snapshot.
        compactSession()
    }

    /**
     * Picker selection. Recorded as the pick **of the context it was made in** —
     * this session ([sessionPicks]), or the draft when no session is tracked —
     * and persisted as `"providerID/refID"` ([SettingsStore.selectedModelOverride])
     * as the draft's starting point ([recentModel]).
     *
     * Upstream keys it the same way (`providers/models/selection.tsx:286-299`:
     * `saved.session[sessionID]` with a session, `store.draft` without one), so a
     * tap in one session never becomes another session's model — and the value
     * written back to the server ([pinSelectedModel]) is always a tap the user
     * actually made.
     *
     * `modelID` is the tapped row's catalog **ref id**
     * ([ModelPickerItems.selectionRef] → [OpenCodeClient.Providers.Model.entryID]):
     * the id half of the session's `Model.Ref`, so two entries that share a wire
     * `modelID` (`gpt-5.6` / `gpt-5.6-fast`) stay independently selectable and
     * the switch sends the tapped one. The entry's own `variants` are validated
     * below, so a pinned effort follows the entry it actually belongs to.
     */
    fun selectModel(providerID: String, modelID: String) {
        val ref = providerID to modelID
        modelOverride = ref
        recentModel = ref
        sessionID?.let { sessionPicks[it] = ref }
        settingsStore?.selectedModelOverride = OpenCodeClient.formatModelRef(ref)
        _selectedModel.value = ref
        _modelOverrideActive.value = true
        // W5-C B4: variant labels belong to one model's catalog entry, so a model
        // switch drops the pin instead of carrying an unrelated effort over.
        // v69: through [pinVariant], so the drop reaches the session's slot too —
        // otherwise switching sessions brought the dropped label back.
        val pinnedVariant = _selectedVariant.value
        if (hasVariants(ref)) {
            if (pinnedVariant != null && !hasVariant(ref, pinnedVariant)) {
                pinVariant(null)
            }
        } else {
            pinVariant(null)
        }
        syncModelLabel()
        pinSelectedModel()
    }

    /**
     * The composer's **explicit** pick as a wire pin, or null while the user
     * follows the session / the server (`clearModelOverride`, nothing chosen in
     * this session, or a session whose pick belongs to another one). Only
     * [modelOverride] counts: `_selectedModel` also holds *derived* models (the
     * session's own, the server default, the picker memory) which must never be
     * written back as if the user had chosen them — the v28 bug wrote the
     * derived default onto a session that had its own model.
     */
    private fun selectedModelPin(): SessionModelSync.Pin? {
        val ref = modelOverride ?: return null
        return SessionModelSync.Pin(ref.first, ref.second, effectiveVariant())
    }

    /**
     * Requirement (device v14, finding 2): a picker tap is **session** state,
     * not a composer decoration — land it on `session.model`
     * (`POST /api/session/{id}/model`) as soon as it is made, so every call
     * that carries no model of its own (the `/btw` generate, a session reopened
     * later, the next `GET /api/session/{id}`) sees it.
     *
     * Deferred while a turn is running: switching the model reconfigures the
     * remainder of the live turn (`composer/submit.ts:337-341`, the same rule
     * that keeps a queued follow-up from applying the composer's selection), and
     * a mid-turn tap must not reach back into the running turn. The submit path
     * pins it on steer, and [generateFromContext] pins it before asking, so a
     * deferred pick still lands before the next session call.
     *
     * No tracked session (the pre-draft composer) has nothing to write to —
     * [createSession] pins the pick the moment the session exists.
     */
    private fun pinSelectedModel() {
        val id = sessionID ?: return
        if (_state.value.runStatus == ChatStore.RunStatus.RUNNING) return
        val transport = transport() ?: return
        val pin = selectedModelPin() ?: return
        scope.launch(Dispatchers.IO) {
            try {
                transport.switchModel(id, pin.providerID, pin.modelID, pin.variant)
            } catch (e: Exception) {
                // Best-effort: the write is repeated before the next session
                // call (submit / generate), so a failure here is not fatal.
                Log.w(TAG, "model pin failed: ${e.message}")
            }
        }
    }

    /**
     * v73: the effort half of the same write-back. A variant tap is session
     * state like the model tap above, but it travels on its own because the user
     * may have pinned no model at all: upstream's `variant.set` writes the
     * *current* selection's ref together with the label
     * (`providers/models/selection.tsx:402`), so the ref here is the composer's
     * pick, else the session's own model ([_sessionModel]) — never a derived
     * default (the v28 bug). The server keeps both halves on `session.model`
     * (`Session.switchModel` compares the variant too, `session/session.ts:96-101`),
     * so the next `GET /api/session/{id}` reads the effort back
     * ([SessionRow.variant]).
     *
     * 「Default」 is a write too — the ref goes out with no variant, which is
     * what takes the effort back off `session.model` — so the tap that *drops*
     * an effort is landed exactly like the one that pins it.
     *
     * Deferred while a turn runs, exactly like the model pin: the pick is
     * already durable locally, and the steer that follows lands it (see
     * [submit]) instead of reconfiguring the running turn.
     */
    private fun pinSelectedVariant() {
        val id = sessionID ?: return
        if (_state.value.runStatus == ChatStore.RunStatus.RUNNING) return
        val transport = transport() ?: return
        val ref = variantWriteRef() ?: return
        val variant = _selectedVariant.value
        scope.launch(Dispatchers.IO) {
            try {
                transport.switchModel(id, ref.first, ref.second, variant)
            } catch (e: Exception) {
                // Best-effort like the model pin: the durable copy already
                // holds the label, and the next steer repeats the write.
                Log.w(TAG, "variant pin failed: ${e.message}")
            }
        }
    }

    /**
     * The ref a variant write belongs to: the composer's own pick, else the
     * tracked session's own model. Null while neither is known — an effort
     * belongs to *some* model, and writing a guessed one is the v28 bug.
     */
    private fun variantWriteRef(): Pair<String, String>? = modelOverride ?: _sessionModel.value

    /** True when the catalog advertises at least one variant for [ref]. */
    private fun hasVariants(ref: Pair<String, String>): Boolean =
        _availableProviders.value?.model(ref.first, ref.second)?.variants?.isNotEmpty() == true

    /** True when [label] is one of [ref]'s advertised variants. */
    private fun hasVariant(ref: Pair<String, String>, label: String): Boolean =
        _availableProviders.value?.model(ref.first, ref.second)?.variants?.contains(label) == true

    /**
     * 「使用服务器默认」: drops this context's pick — and the picker memory, so a
     * draft follows the default too — leaving the session's own model, else
     * `config.model` / `GET /api/model/default`
     * (`providers/models/selection.tsx:361-374`: `model.set(undefined)` clears
     * the selection, and `current()` then resolves `durable()` before `fallback`).
     */
    fun clearModelOverride() {
        modelOverride = null
        sessionID?.let { sessionPicks.remove(it) }
        recentModel = null
        settingsStore?.selectedModelOverride = null
        _modelOverrideActive.value = false
        // v69: model and effort are one saved state upstream
        // (`selection.tsx:361-374` clears both), so 「使用服务器默认」 drops the
        // session's variant pin as well.
        pinVariant(null)
        applyEffectiveModel()
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
        val transport = requireTransport()
        if (transport == null) {
            // v76 (emulator-5558, `research/v76_verify/5558/40_error.png`): a
            // send that finds no transport returned right here and the payload
            // simply vanished — the composer had already cleared its editor and
            // handed its draft slot over (`Composer.submit`), so the user got a
            // failure card, no bubble, no draft and nothing to retry. Every
            // other failure path pays the write-back (`failSubmission`,
            // `composer/submit.ts:407-418`); this one owes it too.
            //
            // [requireClient] writes the card itself when there is no client at
            // all; a live client with no transport (the probe has not confirmed
            // 2.x, or the verdict is a non-2.x server) must still name the
            // failure, because the card is only rendered for a non-null line
            // (`MessageTimeline`). Same sentence either way — it is the one
            // `error_server_notConnectedNoConfig` carries in all three locales.
            if (requireClient() != null) {
                _state.value = ChatStore.reduce(
                    _state.value,
                    ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.ERROR, NOT_CONNECTED_LINE),
                )
            }
            restoreDraft(id, text, shell, attachments)
            return
        }
        if (text.isBlank() && attachments.isEmpty()) return
        val wireDelivery = delivery
        // Captured at submit time, on this thread: the pick the user made in this
        // session (or the draft), never a derived label.
        val pin = selectedModelPin()
        val agent = pinnedAgent()
        scope.launch {
            // This client's own submit is a new run too, and the optimistic
            // RUNNING below is the first thing the composer hears about it: the
            // Stop on record is history from here ([StoppedTurn]).
            stoppedTurn.newRun(id)
            _state.value = ChatStore.reduce(_state.value, ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.RUNNING))
            try {
                val admittedID: String? = withContext(Dispatchers.IO) {
                    val t = transport as V2Transport
                    // Steer reconfigures the (possibly running) turn;
                    // queue leaves the session selection alone.
                    if (wireDelivery == "steer") {
                        if (agent != null) t.switchAgent(id, agent)
                        // Only the user's own pick is written: upstream
                        // `applySelection` sends `selection.current()`, which for
                        // an opened session *is* that session's own model — so
                        // its switch is a no-op there — while the shell's derived
                        // label is the server default, and writing that is the
                        // measured v28 bug (a session created with
                        // `space-bunny-free` silently re-pinned to the default and
                        // run as it). A session with a model of its own keeps it;
                        // one without is answered by the server's own default.
                        if (pin != null) {
                            t.switchModel(id, pin.providerID, pin.modelID, pin.variant)
                        }
                        // v73: an effort the user pinned without picking a model
                        // still belongs to this session (upstream `variant.set`
                        // writes the current selection's ref with the label), so
                        // the steer lands it too — the picker tap may have been
                        // deferred by the running turn this steer is ending.
                        if (pin == null && _selectedVariant.value != null) {
                            variantWriteRef()?.let { ref ->
                                t.switchModel(id, ref.first, ref.second, _selectedVariant.value)
                            }
                        }
                        // Device v14 (finding 1/3): a session with no model
                        // anywhere answers the prompt 503 `Missing API key`.
                        // Refuse locally instead of spending the doomed round
                        // trip — the catch below restores the draft and puts
                        // the classified line on the error card. Only reached
                        // when nothing local names a model, so the extra
                        // `GET /api/session/{id}` is off the common path.
                        if (pin == null && resolveModelPlan(t, id) == SessionModelSync.Plan.NoModel) {
                            throw NoModelException()
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
                            // F-4: the draft's review-comment note lines
                            // (`ReviewCommentPrompt.formatNote`) ride BOTH ways,
                            // exactly like upstream: the sentence stays in the
                            // model-visible `text` (unchanged above) and the
                            // structured rows go into `metadata.comments`
                            // (`composer/submit.ts:169-177`), which the timeline
                            // renders as comment cards.
                            metadata = ReviewCommentMetadata.metadata(text),
                        )
                    }
                }
                // WAVE 2.4b §3: a `delivery=queue` admit joins the queue
                // optimistically (the `session.inbox.*` refresh confirms it).
                // A switch mid-flight clears the queue (`observeSession`), so
                // the row may only join while `id` is still tracked.
                if (sessionID == id && wireDelivery == "queue" && !admittedID.isNullOrBlank() &&
                    command == null && !shell
                ) {
                    queueHolder.add(
                        ComposerQueue.Row(
                            id = admittedID,
                            // F-4: the queue panel shows the user's own words —
                            // upstream reads `metadata.displayText`
                            // (`queue.ts` via `readPromptPresentation`), which is
                            // the note-free text for a commented prompt.
                            text = ReviewCommentMetadata.presentation(text).displayText,
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
                // A `delivery=steer` admit is **not** delivered yet either: the
                // server promotes the row at a step boundary, and a session
                // whose turn is wedged never does. Watch it, so a message that
                // never becomes durable is reported instead of sitting as a
                // 「Queued」 row forever (see [watchPendingDelivery]).
                if (sessionID == id && wireDelivery == "steer" && !admittedID.isNullOrBlank() &&
                    command == null && !shell
                ) {
                    watchPendingDelivery(admittedID)
                    admitHandoffRow(id, admittedID, text, promptFilesFor(files, attachments).size)
                }
                // v59: 发送后转录区不立即显示消息（`research/v59_verify/5558`）。
                // v2 线上**没有** user message 帧（`session-event.ts` 全部
                // `session.*` 事件都带 `data.sessionID`，没有一条创建用户行），
                // 而上游是在 `data.session.prompt()` 里就地把这条 user 行放进
                // sync store 的（`composer/submit.ts:386`）。shell 没有那一层，
                // 这条行原本只能等下一次快照 —— 而快照只由 `session.idle` 之类
                // 的信号触发，于是「已发送」的那几秒里转录区仍是空态，切 tab 或
                // 重进才看见。admit 成功就是快照新鲜的第一个确证，这里补一次读取
                // （绕开 [refreshSnapshotThrottled]：那一档的节流是给信号去重用的，
                // 一次发送只该读一次，且不能被上一条信号顺带吃掉）。
                if (sessionID == id && !admittedID.isNullOrBlank()) refreshSnapshot()
            } catch (e: Exception) {
                Log.w(TAG, "prompt failed: ${e.message}")
                // Draft restore is keyed by `id`, safe after a switch; the ERROR
                // banner however must not land in the new session's store.
                restoreDraft(id, text, shell, attachments)
                // BUG-1 (v32 tB): a write is **never** replayed automatically
                // — a POST that timed out may already have been admitted, so a
                // blind retry can duplicate the turn. What the user's own retry
                // needs is a live connection: drop the dead pooled sockets now.
                // The failure line itself carries the connection vocabulary, so
                // the error card names it (`ProviderErrorKind.TRANSPORT`) and
                // tells the user to try again instead of leaving a bare
                // 「Failed to connect to …」.
                if (TransportRetry.isTransient(e)) noteTransientTransportFailure()
                if (sessionID == id) {
                    // Never swallow the failure: `e.message` is null for some
                    // transports, and a null error hides the card entirely
                    // (`MessageTimeline` only renders it for non-null).
                    val line = e.message?.takeIf { it.isNotBlank() }
                        ?: "${e.javaClass.simpleName}: prompt failed"
                    _state.value = ChatStore.reduce(
                        _state.value,
                        ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.ERROR, line),
                    )
                }
            }
        }
    }

    /** v2 file attachments (`PromptInput.FileAttachment{uri,…}`): data URLs pass through. */
    private fun filesOf(attachments: List<Attachment>): List<V2Api.PromptFile> =
        attachments.map { V2Api.PromptFile(uri = it.dataUrl, name = it.filename) }

    /**
     * WAVE 2.4b: v1 file parts for `@file` mentions (`{type:file, mime,
     * filename, url}` with `url = file.uri` — the server resolves `file://`),
     * appended AFTER the attachment parts.
     */
    private fun mentionFileParts(files: List<ComposerSubmission.FileEntry>): List<ChatStore.UiPart> =
        files.filter { it.uri.startsWith("file://") }.map { entry ->
            ChatStore.UiPart(
                id = "att:${entry.name ?: entry.uri}:${entry.uri.hashCode()}",
                type = "file",
                mime = entry.mime ?: mimeOfDataUrl(entry.uri),
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
     *
     * F-1: a mention is anything that is **not** an inline `data:` URL — an MCP
     * resource mention carries the resource's own uri verbatim
     * (`composer/model.ts:180` `url: resource.uri`), so filtering on `file://`
     * alone silently dropped it.
     */
    private fun promptFilesFor(
        files: List<ComposerSubmission.FileEntry>,
        attachments: List<Attachment>,
    ): List<V2Api.PromptFile> {
        val chipUris = attachments.map { it.dataUrl }.toHashSet()
        val mentions = files.filterNot { it.uri.startsWith("data:") }
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
     * F-2 (review line comments; minimal contract implementation owned by the
     * review agent — see the task handoff): appends [text] to this session's
     * composer draft. The write goes through [ComposerDrafts.appendLine] so a
     * mounted composer adopts it (cursor to end) and an unmounted one picks it
     * up on mount — the same handshake a failed send uses ([restoreDraft]).
     * Blank input is a no-op; existing draft text is kept above the new line.
     * P1-C: staged attachments and the message ref survive the append (a plain
     * restore would wipe the handoff halves). The signature is the shared
     * contract the review domain calls — it never changes.
     */
    fun appendComposerText(text: String) {
        if (text.isBlank()) return
        ComposerDrafts.appendLine(ComposerDrafts.key(sessionID), text)
    }

    /**
     * Failed-send rollback: put the text back in the composer's draft for this
     * session and forget the history entry the submit recorded — exactly the two
     * things upstream's `failSubmission` does (`submit.ts:407-418`).
     *
     * P1-C: the full handoff comes back, not just the text — the failed
     * payload's attachments ride along ([ComposerDrafts.restoreHandoff], which
     * also preserves a stashed message ref the view model never knew), so an
     * attachment-only send (blank text) is restored too.
     */
    private fun restoreDraft(
        sessionID: String,
        text: String,
        shell: Boolean,
        attachments: List<Attachment> = emptyList(),
    ) {
        if (text.isBlank() && attachments.isEmpty()) return
        val key = ComposerDrafts.key(sessionID)
        ComposerDrafts.restoreHandoff(key, text, attachments.map { it.toDraft() })
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
                // Only put the row back into the session it came from.
                if (sessionID == id) queueHolder.add(row)
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
                // Only put the row back into the session it came from.
                if (sessionID == id) queueHolder.add(row)
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
        val transport = transport() as? V2Transport
        if (transport == null) {
            // v76: the same write-back the failure below pays. The composer has
            // already cleared the slot it handed this edit over in, so returning
            // here ate the replacement silently; the original row is untouched
            // (it never left the queue), which is why no card is raised — the
            // edited text simply comes back.
            ComposerDrafts.restoreHandoff(
                ComposerDrafts.key(id),
                replacement.text,
                replacement.files.mapNotNull { it.toDraftAttachment() },
            )
            return
        }
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
                // F-4: the original prompt's comment notes survive the edit
                // (`editedPromptInput`, `queue.ts:315-316`: the replacement text
                // is the newly typed value **plus** the original's notes tail).
                // The queue row only carries the note-free `displayText`, so the
                // original item is re-read from the inbox; a failed or empty read
                // leaves the edit exactly as it was.
                val edited = preservedNotes(id, originalID)
                    .takeIf { it.isNotEmpty() }
                    ?.let { notes -> replacement.copy(text = replacement.text + notes) }
                    ?: replacement
                val admittedID = withContext(Dispatchers.IO) {
                    sendStructured(transport, id, edited)
                }
                // Local queue writes only while `id` is still tracked — the
                // switch's clear() already emptied the holder for the new one.
                if (sessionID == id) {
                    queueHolder.remove(originalID)
                    if (replacementDelivery == "queue" && !admittedID.isNullOrBlank()) {
                        queueHolder.add(
                            ComposerQueue.Row(
                                id = admittedID,
                                text = ReviewCommentMetadata.presentation(edited.text).displayText,
                                attachmentCount = promptFilesFor(edited.files, emptyList()).size,
                                submission = edited,
                            ),
                        )
                    }
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
                // P1-C: the replacement comes back as a handoff (its embeddable
                // `data:` files ride along; `file://` refs stay text-only).
                ComposerDrafts.restoreHandoff(
                    ComposerDrafts.key(id),
                    replacement.text,
                    replacement.files.mapNotNull { it.toDraftAttachment() },
                )
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
            val agent = pinnedAgent()
            if (agent != null) transport.switchAgent(sessionID, agent)
            // Same rule as [submit]'s steer: only the user's own pick is
            // written, never a derived label (the session keeps its own model).
            selectedModelPin()?.let { pin ->
                transport.switchModel(sessionID, pin.providerID, pin.modelID, pin.variant)
            }
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
            metadata = ReviewCommentMetadata.metadata(replacement.text),
        )
    }

    /**
     * F-4: the comment notes of the queued prompt a queue edit replaces.
     *
     * `editedPromptInput` (`session/composer/queue.ts:315-316`) keeps them by
     * splitting the original's **model-visible** text on its `displayText`:
     *
     * ```ts
     * const notes = payload && display && payload.text.startsWith(display)
     *   ? payload.text.slice(display.length) : ""
     * ```
     *
     * The Android queue row carries the note-free `displayText` only
     * ([ComposerQueueHolder.rowFromJson]), so the original item is re-read from
     * the inbox here. Blank (nothing to preserve, or the read failed) leaves the
     * edit exactly as it was — the notes are then simply gone, like a prompt
     * whose notes the composer never held.
     */
    private suspend fun preservedNotes(sessionID: String, inboxID: String): String {
        val transport = transport() as? V2Transport ?: return ""
        return try {
            val item = withContext(Dispatchers.IO) { transport.fetchInbox(sessionID) }
                .firstOrNull { optStringOrNull(it, "id") == inboxID }
                ?: return ""
            val payload = item.optJSONObject("payload") ?: return ""
            val text = optStringOrNull(payload, "text").orEmpty()
            val display = optStringOrNull(payload.optJSONObject("metadata"), "displayText").orEmpty()
            if (display.isEmpty() || !text.startsWith(display)) return ""
            text.removePrefix(display)
        } catch (e: Exception) {
            Log.w(TAG, "queue edit: could not read the original's notes: ${e.message}")
            ""
        }
    }

    /** Client-generated prompt id (`msg_…`, mirroring the measured 27-char shape). */    private fun newMessageID(): String =
        "msg_" + UUID.randomUUID().toString().filter { it.isLetterOrDigit() }.take(27)

    /**
     * WAVE 2.4b: server truth for the queue — `GET …/inbox` mapped to rows,
     * wholesale replace. Driven by `session.inbox.*` events ([onServerSignal]),
     * once per session open ([setGeneration]'s v2 branch) and after mutations
     * settle; event-driven otherwise, no polling.
     *
     * WAVE 2.4c (device P1, 2026-09-25): the same read also carries the
     * **parked steers** into the timeline. The queue holder deliberately shows
     * only `delivery:"queue"` rows (`ComposerQueueHolder.rowFromJson`), so a
     * steer that the server parked behind a hung turn — a `/btw`-style side
     * question, or a queued row the user promoted with Steer — had no surface
     * at all: not in the queue panel, not in the transcript (the server publishes
     * the message only at delivery). Now every parked steer gets a
     * [ChatStore.PENDING_ROLE] row, and it disappears on its own the moment the
     * delivery frame makes the server drop it from this list.
     *
     * WAVE 2.4d (device v11): the one-shot read on session open is what keeps
     * both halves alive across a re-entry. `observeSession` starts a fresh
     * [ChatStore.ChatState] and clears the queue holder, and the server
     * announces *changes* only, so neither surface could come back on its own.
     */
    private fun refreshInbox() {
        val id = sessionID ?: return
        val transport = transport() as? V2Transport ?: return
        scope.launch {
            try {
                val items = withContext(Dispatchers.IO) { transport.fetchInbox(id) }
                if (sessionID != id) return@launch
                queueHolder.replace(items.mapNotNull { ComposerQueueHolder.rowFromJson(it) })
                // A handoff row outlives the inbox read that no longer lists its
                // admission ([admitHandoffRow]): a turn the server aborted before
                // writing anything leaves the prompt nowhere else on screen, and
                // the inbox — server truth about *undelivered* input — is exactly
                // where it stops being listed. A handoff the server did publish
                // is retired first, so this can never resurrect a delivered
                // message's placeholder.
                handoffPrompts.removeAll { isMessageDurable(it) }
                val handedOff = _state.value.messages.filter {
                    ChatStore.isPendingRow(it) && it.id in handoffPrompts
                }
                applyEvent(
                    ChatStore.Event.InboxPendingSteers(
                        items.mapNotNull { pendingSteerRow(it) } + handedOff,
                    ),
                )
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

    // ------------------------------- WAVE 2.4b: steer delivery (v2-only, BUG-2)

    /**
     * The submit-time landing point for one admitted prompt: the user's own
     * message on screen the moment the server accepts it.
     *
     * Upstream writes that row **locally**, in `prompt()` itself
     * (`composer/submit.ts:97` — `session.handoff?.set(handoffMessage(value))`),
     * before the request that creates the message; everything after is a
     * hand-over onto the server's copy. The shell had only the server's half: a
     * prompt reached the transcript when a later snapshot carried the message,
     * and a turn the server aborts before it writes anything never produces one.
     * So the v58 report — send a prompt, press Stop right after, and the session
     * shows no trace of either: no user row, no `Interrupted` divider (upstream's
     * divider is a `message.error` on an assistant row, so there is nothing to
     * anchor it to when no message exists), and no error to read. The turn looks
     * like it never happened at all.
     *
     * The fix is upstream's, not an invention: show the prompt. The row is the
     * ordinary [ChatStore.PENDING_ROLE] placeholder carrying the **admission
     * id** the answer just gave, so it is byte-for-byte the row [pendingSteerRow]
     * builds from the inbox and behaves like it — the delivered message replaces
     * it in place ([ChatStore.upsert]'s client-row hand-over), it renders muted
     * and parked at the tail, and it never counts as a turn.
     *
     * Its lifetime is upstream's too, which is why it is **not** simply left to
     * the inbox read: [refreshInbox] replaces the parked rows with the server's
     * own list, and a prompt whose turn was aborted is no longer in that list,
     * so the row would be swept away exactly when it is the only evidence that
     * the turn existed. [handoffPrompts] is what holds it — the row is retired by
     * the delivered message ([isMessageDurable]) or by leaving the session, the
     * two things upstream's `clearSessionMessageHandoff` also ends a handoff on.
     */
    private suspend fun admitHandoffRow(id: String, admissionID: String, text: String, fileCount: Int) {
        if (sessionID != id) return
        val parked = _state.value.messages.filter { ChatStore.isPendingRow(it) }
        if (parked.any { it.id == admissionID }) return
        handoffPrompts.add(admissionID)
        val row = handoffPromptRow(
            id = admissionID,
            text = text,
            fileCount = fileCount,
            notes = ReviewCommentMetadata.presentation(text).notes,
        )
        applyEvent(ChatStore.Event.InboxPendingSteers(parked + row))
    }

    /**
     * Watch one admitted steer until it becomes a durable message, and say so
     * out loud when it never does.
     *
     * The v36 tB report: a session's message answered `200 delivery:"steer"` and
     * was never persisted (`GET …/message/{id}` → `MessageNotFoundError`), while
     * the transcript showed the muted 「Queued」 parked row and nothing else —
     * no error, no timeout, and no action, so the session looked unusable
     * forever. The server's half is a turn it never finishes (a wedged step, an
     * ask nobody answered), which this client cannot fix from here; its own half
     * is that it treated "admitted" as "sent" and then waited silently.
     *
     * The judgement is deliberately conservative — it fires only when the
     * message is still not durable **and** this client has seen nothing from the
     * session in the window: a steer parked behind a *running* turn is normal
     * (that is what steer means) and the run's own frames keep
     * [lastPartEventAt] fresh, so the watchdog stays quiet. What it reports is
     * the measured shape: an idle-looking session, no traffic, and a message the
     * server never delivered.
     */
    private fun watchPendingDelivery(admissionID: String) {
        pendingDeliveries[admissionID] = PendingDelivery(System.currentTimeMillis())
        if (pendingDeliveryWatch?.isActive == true) return
        pendingDeliveryWatch = scope.launch {
            while (true) {
                delay(PENDING_DELIVERY_POLL_MS)
                val now = System.currentTimeMillis()
                val waiting = pendingDeliveries.filterValues { !it.stalled }
                if (waiting.isEmpty()) return@launch
                for ((id, watch) in waiting) {
                    if (isMessageDurable(id)) {
                        pendingDeliveries.remove(id)
                        continue
                    }
                    // Not observing this session (no screen mounted) and a
                    // busy turn are both "no verdict yet": re-base the window
                    // rather than judging a state nobody is looking at.
                    if (!started.get() || _state.value.runStatus == ChatStore.RunStatus.RUNNING) {
                        watch.admittedAt = now
                        continue
                    }
                    if (!pendingDeliveryStalled(now, watch.admittedAt, lastPartEventAt)) continue
                    watch.stalled = true
                    reportStalledDelivery(id)
                }
            }
        }
    }

    /** True when the admission id has a **durable** row (a parked-steer row is not one). */
    private fun isMessageDurable(admissionID: String): Boolean =
        _state.value.messages.any { it.id == admissionID && !ChatStore.isPendingRow(it) }

    /**
     * The explicit report for an admission the server never delivered: one ERROR
     * row on the session (the same surface a failed prompt POST uses), naming the
     * message and the way out — [retryPendingDelivery], which the parked row's
     * own action calls.
     */
    private fun reportStalledDelivery(admissionID: String) {
        Log.w(TAG, "steer not delivered within ${PENDING_DELIVERY_STALL_MS}ms: $admissionID")
        if (sessionID == null) return
        _state.value = ChatStore.reduce(
            _state.value,
            ChatStore.Event.RunStatusChanged(
                ChatStore.RunStatus.ERROR,
                "Message not delivered: the server did not admit it into the session " +
                    "within ${PENDING_DELIVERY_STALL_MS / 1000}s (the session may be stuck). " +
                    "Use Retry on the queued message to deliver it again.",
            ),
        )
    }

    /**
     * Deliver a parked message again — the retry the stalled report points at.
     *
     * `PATCH …/inbox/{id}{delivery:"steer"}` is the server's own "send this
     * now": `Session.steerInbox` re-delivers the row **and rings the run
     * doorbell** (`execution.wake`, `session.ts:133-137`), which is exactly what
     * a row parked behind a missed wake needs. Nothing is optimistically
     * removed: the row is the server's, and the next `session.inbox.*` frame (or
     * [refreshInbox]) is what takes it away.
     *
     * The watch is re-armed for the row, so a second stall reports again instead
     * of leaving the user with a silent row once more.
     */
    fun retryPendingDelivery(inboxID: String) {
        val id = sessionID ?: return
        val transport = transport() as? V2Transport ?: return
        // Re-armed, not just recorded: the watch that reported the stall has
        // exited by then, so a bare map write would never be judged again.
        watchPendingDelivery(inboxID)
        scope.launch {
            try {
                withContext(Dispatchers.IO) { transport.updateInboxDelivery(id, inboxID, "steer") }
                if (sessionID == id) {
                    // The server re-delivers on its own wake; reading the inbox
                    // back is what turns the row into the message (or proves it
                    // is still parked).
                    refreshInbox()
                }
            } catch (e: Exception) {
                Log.w(TAG, "retry delivery failed: ${e.message}")
                if (sessionID != id) return@launch
                val line = e.message?.takeIf { it.isNotBlank() }
                    ?: "${e.javaClass.simpleName}: retry failed"
                _state.value = ChatStore.reduce(
                    _state.value,
                    ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.ERROR, line),
                )
            }
        }
    }

    /**
     * Stop.
     *
     * Upstream routes Stop through the session adapter's `interrupt`
     * (`app/src/composer/submit.ts:149` → `app/src/session/composer/adapter.ts:38-42`),
     * which is `session.interrupt({sessionID, resume: true})`. The `resume` flag
     * is not cosmetic: `Session.interrupt` uses it to promote a steer / control
     * row that was already parked when the turn died
     * (`_upstream/packages/core/src/session/execution.ts:156-172`), so without
     * it a follow-up prompt stays parked against the aborted turn and the next
     * send is merged into it instead of opening a new one.
     *
     * **The answer is the whole point of this method.** It used to be
     * fire-and-forget: `V2Api.interrupt` already returns `{interrupted:bool}`
     * (measured `{"interrupted":false}` for the idle no-op) and the transport
     * threw the flag away, while any exception was swallowed into a `Log.w`. A
     * Stop that never reached the backend — an unreachable server, a
     * `session.interrupt` that found no execution owned by this process, or a
     * transport that was not installed at all — therefore looked like a
     * successful stop: the composer went idle and the next prompt was steered
     * into the turn that was still running (emulator-5556 tStop).
     *
     * So every one of those three outcomes is reported instead of absorbed:
     * the server's live set decides whether the turn really died, and only that
     * decides between "re-read the transcript" (the abort landed — this is what
     * paints the `Interrupted` separator and settles the half-streamed bubble)
     * and an error the user can see.
     *
     * **v78 closed the two ways that promise still leaked** (emulator-5554,
     * `research/v78_verify/5554/37-41`; one tap on Stop while the agent sat in a
     * pending question, reproduced 1/1):
     *
     * - the acknowledgment is now **bounded**. `interrupt` is a process-local
     *   pre-emption answered in milliseconds, so a Stop with no answer after
     *   [STOP_ACK_TIMEOUT_MS] is a wedged request, not a slow one — and the tap
     *   used to sit inside it for the whole REST read timeout (30 s, measured:
     *   the composer stayed on Stop, `Working` stayed up, the turn kept
     *   producing) with nothing on screen to explain it. [STOP_NO_ANSWER] names
     *   that outcome.
     * - "not active" is now an **answer**, not a shrug. The old tail called
     *   `refreshSnapshot()` and returned, which reached the reconcile half of
     *   [refreshSnapshot] — and that half waits out `IDLE_GRACE_MS` before it
     *   will settle a RUNNING turn, so a Stop the server had already honoured
     *   (or a refusal against a session the map no longer lists) left the
     *   composer on Stop for another ten seconds, with the delivery chip
     *   reading 「Steer」 and a typed follow-up one 409 away. The same branch
     *   settles the composer here and re-reads the transcript unthrottled, which
     *   is the difference between the two trials the steward measured (< 1 s at
     *   the bottom, ~10 s scrolled away — the 10 s was this grace, not a layout
     *   callback).
     *
     * What it deliberately does *not* do is put this Stop on record
     * ([StoppedTurn]): only `{interrupted:true}` is the server saying a turn
     * died, so only that outcome may keep a live-looking wire from re-opening
     * one. A refusal settles the client's *view* of a turn the map no longer
     * lists and leaves the wire free to correct it — the next
     * `session.step.started` / liveness frame brings the Stop affordance back.
     */
    fun abort() {
        val id = sessionID ?: return
        val transport = requireTransport()
        if (transport == null) {
            scope.launch { reportStopUndelivered(id, "not connected to the server") }
            return
        }
        val believedRunning = _state.value.runStatus == ChatStore.RunStatus.RUNNING
        scope.launch {
            val failure: String? = try {
                // Bounded on purpose: see the note above. `withTimeoutOrNull`
                // rather than `withTimeout` so a wedged answer is a *named*
                // outcome the live-set probe below can act on, not an exception
                // dressed up as a transport failure. The abandoned socket is
                // OkHttp's to reap — this call is blocking, so cancellation
                // releases the *caller* and the request dies at its own read
                // timeout, which is exactly what must not hold the UI.
                val interrupted = withTimeoutOrNull(STOP_ACK_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) { transport.interrupt(id, resume = true) }
                }
                when (interrupted) {
                    true -> null
                    false -> STOP_NOT_INTERRUPTED
                    null -> STOP_NO_ANSWER
                }
            } catch (e: Exception) {
                Log.w(TAG, "abort failed: ${e.message}")
                e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            }
            if (sessionID != id) return@launch
            if (failure == null) {
                // The abort landed, and `{"interrupted":true}` is the *whole*
                // answer: the execution this process was running has been
                // interrupted, so the tracked turn is over — and it is the only
                // carrier left, because the terminal frame is exactly what this
                // client never got (v76 logcat: the tracked session's last
                // `trigger idle` before the user's second tap was an *update's*
                // busy→idle a minute earlier). Settle the composer here instead
                // of waiting for that frame, and put the Stop on record so the
                // live set — which still answered `running` for a session with
                // nothing left to interrupt — cannot undo it ([StoppedTurn]).
                //
                // A `resume` that really does start something (a steer parked
                // when the turn died, `execution.ts:156-172`) announces itself:
                // `session.execution.started` / `session.step.started` sets
                // RUNNING again and clears the record.
                stoppedTurn.confirmed(id)
                turnRunStatus.settled(id)
                applyEvent(ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.IDLE))
                // The `Interrupted` separator and the settled (no longer
                // half-streamed) assistant row are server facts, so read them
                // back unthrottled: the `session.execution.interrupted` frame
                // may already have been deduped away, and the throttled snapshot
                // may be minutes behind.
                refreshSnapshot()
                return@launch
            }
            if (!believedRunning) {
                // The client was already idle, so a Stop with no confirmation
                // has nothing of its own to reconcile — the turn it would have
                // stopped is not one the UI was tracking.
                return@launch
            }
            // The Stop was refused, unanswered, or never delivered. Ask the
            // server the same question the watchdog does before deciding where
            // this ends: only a session the live set still lists may raise the
            // error, otherwise the turn genuinely finished on its own and the
            // transcript is the truth.
            val live = try {
                withContext(Dispatchers.IO) { transport.activeRunStatus(id) }
            } catch (_: Exception) {
                null
            }
            if (sessionID != id) return@launch
            if (live == "running" || live == "busy" || live == "retry") {
                reportStopUndelivered(id, failure)
            } else {
                // The map lists no execution for this session, so there is
                // nothing left for the Stop to end: the turn the client was
                // tracking is over whatever the wire did with the answer, which
                // is the conclusion the idle watchdog reaches — reached here and
                // now rather than one `IDLE_GRACE_MS` later (v78 P3: the
                // composer sat on Stop for ten seconds after an already-honoured
                // Stop, its delivery chip reading 「Steer」).
                //
                // Deliberately **not** on record ([StoppedTurn]): a refusal is
                // not the server confirming a turn died, so the wire keeps the
                // right to re-open this one — the next liveness frame brings the
                // Stop affordance straight back if the turn is still there. Nor
                // is `turnRunStatus` settled here, for the same reason: a turn
                // that does end later must still announce its own idle.
                applyEvent(ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.IDLE))
                refreshSnapshot()
            }
        }
    }

    /**
     * A Stop that did not stop anything, said out loud.
     *
     * `RunStatus.ERROR` is the only channel the composer has for "this did not
     * work" — it puts the send button back and shows the line, which is the
     * opposite of the silent-idle failure this replaces. The wording follows the
     * other transport failures raised from here (`requireClient`, the stalled
     * delivery report).
     */
    private suspend fun reportStopUndelivered(id: String, reason: String) {
        if (sessionID != id) return
        Log.w(TAG, "stop did not interrupt the turn: $reason")
        applyEvent(
            ChatStore.Event.RunStatusChanged(
                ChatStore.RunStatus.ERROR,
                "Stop did not reach the server ($reason) — the turn may still be running.",
            ),
        )
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
        val id = sessionID ?: return
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
                    transport.replyPermission(id, requestID, reply)
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
        if (_formResponding.value == formID) return
        val removed = _state.value.pendingForms.firstOrNull { it.id == formID } ?: return
        _formResponding.value = formID
        _state.value = ChatStore.reduce(_state.value, ChatStore.Event.FormResolved(formID))
        scope.launch {
            try {
                withContext(Dispatchers.IO) { transport.replyForm(id, formID, answer) }
            } catch (e: Exception) {
                Log.w(TAG, "form reply failed: ${e.message}")
                applyEvent(ChatStore.Event.FormCreated(removed))
            } finally {
                _formResponding.value = null
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
        if (_formResponding.value == formID) return
        val removed = _state.value.pendingForms.firstOrNull { it.id == formID } ?: return
        _formResponding.value = formID
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
                _formResponding.value = null
            }
        }
    }

    fun searchFiles(query: String, onResult: (List<String>) -> Unit) {
        searchFiles(query, {}, onResult)
    }

    fun searchFiles(query: String, onFailure: (TransportFailure) -> Unit, onResult: (List<String>) -> Unit) {
        val transport = requireTransport()
        if (transport == null) {
            reportBrowseFailure(noTransportFailure(), onFailure)
            onResult(emptyList())
            return
        }
        // No scope of its own (the legacy `ChatView` mention path): anchor on
        // the tracked session's workspace when one is known, so the ask never
        // runs server-globally (v2 `browseRoot` fallback) and out-of-project
        // hits leak into the popover. Null stays null — same as before.
        // Launch straight on IO: while a turn streams, 3 s snapshot merges can
        // saturate the main thread (huge message re-layout), and a Main-launched
        // coroutine queues behind them — the mention search then fires minutes
        // late or dies to the composer's 4 s latch timeout. The result hop stays
        // on Main: callers build Views (PopupWindow) or write Compose state.
        scope.launch(Dispatchers.IO) {
            val results = try {
                val found = transport.searchFiles(query, directory = directory)
                _browseFailure.value = null
                found
            } catch (e: Exception) {
                reportBrowseFailure(TransportFailure.RequestFailed(e.message), onFailure)
                emptyList()
            }
            withContext(Dispatchers.Main) { onResult(results) }
        }
    }

    /**
     * Directory-scoped variant for the new-session draft: before the session
     * exists the tracked directory is null, so the draft passes its picked
     * project explicitly and the `@` mention search hits the right workspace.
     */
    fun searchFiles(query: String, directory: String?, onFailure: (TransportFailure) -> Unit = {}, onResult: (List<String>) -> Unit) {
        val transport = requireTransport()
        if (transport == null) {
            reportBrowseFailure(noTransportFailure(), onFailure)
            onResult(emptyList())
            return
        }
        scope.launch(Dispatchers.IO) {
            val results = try {
                val found = transport.searchFiles(query, directory = directory)
                _browseFailure.value = null
                found
            } catch (e: Exception) {
                reportBrowseFailure(TransportFailure.RequestFailed(e.message), onFailure)
                emptyList()
            }
            withContext(Dispatchers.Main) { onResult(results) }
        }
    }

    /**
     * Fuzzy directory search for the picker's path suggestions. Empty list on
     * failure — a suggestion dropdown is never worth a visible error — but the
     * reason still travels through [onFailure] / [browseFailure].
     */
    fun searchDirectories(query: String, onFailure: (TransportFailure) -> Unit = {}, onResult: (List<DirEntry>) -> Unit) {
        val transport = requireTransport()
        if (transport == null) {
            reportBrowseFailure(noTransportFailure(), onFailure)
            onResult(emptyList())
            return
        }
        scope.launch(Dispatchers.IO) {
            val results = try {
                val found = transport.searchDirectories(query)
                _browseFailure.value = null
                found
            } catch (e: Exception) {
                reportBrowseFailure(TransportFailure.RequestFailed(e.message), onFailure)
                emptyList()
            }
            withContext(Dispatchers.Main) { onResult(results) }
        }
    }

    /**
     * M2 addition: rename the tracked (or any) session and, on success, mirror
     * it into [currentTitle] so the sticky header updates without a
     * `listSessions()` round-trip. Wave 1: routes through the transport
     * (`PATCH /session/:id` v1, `PATCH /api/session/:id` v2 — both 204/200).
     * Read-only with respect to every other method: no state machine, no
     * stream touched.
     *
     * v44 tC: the transport is acquired through [awaitSessionTransport] inside
     * the coroutine, not sampled with [requireTransport] at tap time, and no
     * branch is silent any more. The old early return reported `false` with no
     * log and no request — the same hole that swallowed every Home delete, and
     * a rename that only left the editor open read as a dead key.
     *
     * v81 P1 (5556 `24_rename_dlg.png` → `25_renamed.png`, server log
     * `07:04:47.171 PATCH … http.status=500`): the answer is a
     * [SessionRenameOutcome], not a boolean, because the two shapes a `false`
     * used to hide need different words and different repairs. The measured
     * device failure was a server **refusal** on a session whose project
     * directory does not exist (`FileSystem.realPath (/mnt/c/x) ENOENT`), which
     * the toast reported as 「check the connection and try again」 — a diagnosis
     * the tester disproved with one `curl`, and a retry that could never work.
     * A `404` is the other half: the server no longer has the session, so its
     * entry is dropped everywhere ([forgetSession]) instead of being left for
     * the user to retry into.
     */
    fun renameSession(id: String, title: String, onResult: (SessionRenameOutcome) -> Unit = {}) {
        val trimmed = title.trim()
        if (id.isEmpty() || trimmed.isEmpty()) {
            Log.w(TAG, "rename session not sent: id='$id' title='$trimmed'")
            onResult(SessionRenameOutcome.NOT_SENT)
            return
        }
        scope.launch {
            val transport = awaitSessionTransport()
            if (transport == null) {
                Log.w(TAG, "rename session $id not sent: no transport")
                onResult(SessionRenameOutcome.UNREACHABLE)
                return@launch
            }
            val result = try {
                withContext(Dispatchers.IO) { transport.renameSession(id, trimmed) }
            } catch (e: Exception) {
                Log.w(TAG, "rename session failed: ${e.message}")
                RenameResult.Unreachable(e.message)
            }
            if (result is RenameResult.Renamed) {
                manualTitles[id] = result.title
                publishCurrentTitle(id, result.title)
                // The `session.renamed` frame that normally stales the catalogue
                // cannot arrive here: a rename is made on **Home**, which opens no
                // stream by contract (M1), so the row every list surface draws
                // from — Home's rows, the drawer's, the tab seed — would keep the
                // name this device has just replaced. [deleteSession] bumps for
                // exactly the same reason.
                _sessionsRevision.value += 1
            }
            if (result is RenameResult.Gone) {
                Log.w(TAG, "rename session $id: the server no longer has it")
                forgetSession(id)
            }
            if (result is RenameResult.Refused) {
                Log.w(TAG, "rename session $id refused by the server: HTTP ${result.status}")
            }
            onResult(result.toOutcome())
        }
    }

    /** The UI-facing half of a [RenameResult] (one enum, so callers share the wording). */
    private fun RenameResult.toOutcome(): SessionRenameOutcome = when (this) {
        is RenameResult.Renamed -> SessionRenameOutcome.RENAMED
        RenameResult.Gone -> SessionRenameOutcome.GONE
        is RenameResult.Refused -> SessionRenameOutcome.REFUSED
        RenameResult.Unconfirmed -> SessionRenameOutcome.NOT_CONFIRMED
        is RenameResult.Unreachable -> SessionRenameOutcome.UNREACHABLE
    }

    /**
     * v81 P2 「幽灵会话死胡同」 (`research/v81_verify/5558/t4_s02_empty_session.png`):
     * an entry for a session the server no longer has is dropped **everywhere**
     * — Home's column, the drawer, the tab strip (through [sessionsRevision]'s
     * prune) and this client's own catalogue cache.
     *
     * The measured chain: a session created in project `/mnt/c/x` (a directory
     * the server host does not have) answered `500` on its session-scoped reads
     * and then, from `07:07:54`, `404` on every one of them — while the app still
     * listed the row and held it in a restored tab. Opening it latched
     * [sessionMissing] (the not-found page), whose only exits were Back and a
     * Retry that re-ran the same `404`s.
     *
     * So the entry is forgotten locally, and the catalogue read that follows is
     * what makes Home and the drawer agree (the revision bump drives it; Home
     * opens no stream of its own).
     *
     * v83 P1 (`research/v83_verify/5558/26_list_after_del.png`): this is now the
     * **local half** of the not-found page's 「删除该条目」, reached only after the
     * server agreed — `SessionDeleteHost.deleteEntry` writes the `DELETE` first
     * and calls this on `204`/`404`. v81 reached it alone, on the premise that a
     * dead entry has nothing left to delete server-side; the measured `REFUSED`
     * entry (the missing-project-directory session) answers `204`, so the
     * local-only version was a delete that deleted nothing — the row stayed in
     * Home and the session stayed on the server. The `renameSession` `GONE` half
     * ([RenameResult.Gone]) still comes straight here: that read *is* the server's
     * verdict that the session is gone.
     *
     * The tracked session latches as missing rather than being reset: the route
     * explains it, and [com.xiufeigo.opencodeshell.ui.session.SessionNotFoundPanel]
     * offers the entry-level exit.
     */
    fun forgetSession(id: String) {
        if (id.isEmpty()) return
        lastSessionRows = lastSessionRows.filterNot { it.id == id }
        _sessionUnread.value = _sessionUnread.value.filterKeys { it != id }
        _askLedger.update { it.forget(id) }
        if (id == sessionID) markSessionMissing(id)
        _sessionsRevision.value += 1
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
     *
     * The successful delete is **applied locally** — the row's unread watermark
     * is dropped and [sessionsRevision] is bumped — instead of waiting for the
     * server's `session.deleted` frame. Upstream does the same on its own delete
     * path (`home/sessions/controller.tsx:196-215`: `notifySessionTabsRemoved` +
     * `queryClient.invalidateQueries(["home-sessions"])`), and here it is the
     * only way the drawer, Home's column and the tab strips can drop the row at
     * all: the shell opens no stream off the session route, so that frame is
     * never delivered while the user is on Home (`OcActivity`'s design).
     *
     * v44 tC — **the P0 「删除静默无效」** (`research/v44_verify/tC/v44_49/50/51`):
     * Home 长按 → 删除… → 删除 closed the dialog, left the row in the list, and
     * put **no** `DELETE` on the wire (logcat confirmed; a direct `curl DELETE`
     * on the same session answered 204). The chain above this method was wired
     * correctly; the break was here. The write sampled [requireTransport] at tap
     * time, and Home never calls `start` (M1) while the session route's `stop`
     * drops the transport on the way out — a long-press right after leaving a
     * session therefore lands inside the rebind/probe window, answers `null`,
     * and the early return reported `false` **with no log and no request** while
     * the caller's `onResult` was `{ _ -> }`. To the user that reads as "deleted"
     * (the dialog closed) when nothing happened at all — worse than a failure
     * that says so.
     *
     * So the transport is acquired through [awaitSessionTransport] — the same
     * sequence [fetchExportFrameResult] uses after the v43 export fix, which
     * was this same window — inside the coroutine, and every branch that leaves
     * without a delete is logged so the gap is visible in logcat.
     */
    fun deleteSession(id: String, directory: String? = null, onResult: (Boolean) -> Unit = {}) {
        if (id.isEmpty()) {
            Log.w(TAG, "delete session not sent: empty id")
            onResult(false)
            return
        }
        scope.launch {
            val transport = awaitSessionTransport()
            if (transport == null) {
                Log.w(TAG, "delete session $id not sent: no transport")
                onResult(false)
                return@launch
            }
            val deleted = try {
                withContext(Dispatchers.IO) { transport.deleteSession(id) }
            } catch (e: Exception) {
                Log.w(TAG, "delete session failed: ${e.message}")
                false
            }
            if (deleted) {
                _sessionUnread.value = _sessionUnread.value.filterKeys { it != id }
                // A deleted session's pending ask dies with its row — otherwise a
                // permission it was waiting on would keep a tab / Home row lit
                // for a session that no longer exists.
                _askLedger.update { it.forget(id) }
                if (id == sessionID) newSession()
                _sessionsRevision.value += 1
            }
            onResult(deleted)
        }
    }

    // ------------------------------------------------------- W6 session actions
    //
    // The writes behind the ⋮ menu's session rows and the fork picker. Each
    // is "run one session write in the same shape as every other mutation here"
    // (coroutine on [scope], state refreshed from the response, failure logged
    // and reported through `onResult` — never thrown), and each one refreshes
    // `_revertMessageID` from the returned `Session.Info`, which the server
    // documents as authoritative for that write.

    /**
     * Adopt the `Session.Info` bits the ⋮ menu acts on from a payload the
     * server just answered with (every session write and [refreshSnapshot]'s
     * `GET /session/:id`).
     *
     * Revert is taken verbatim: the server stores `revert.messageID` and answers
     * `POST /revert` with it set, `POST /unrevert` with it gone (verified live on
     * 1.18.31) — including the case where 撤销 named a message the server does
     * not know, which answers `200` **without** `revert` and is therefore a
     * no-op that must not leave a phantom boundary on screen.
     *
     * The `OpenCodeClient.Session` shape has no in-tree caller since v1 was
     * dropped; [refreshSnapshot] adopts the same field off the v2
     * [com.xiufeigo.opencodeshell.v2.SessionRow] (`fetchSession`), which carries
     * `revertMessageID` out of `V2Api.SessionInfo.raw` — the one restore path a
     * reopened session takes.
     */
    fun applySessionInfo(info: OpenCodeClient.Session) {
        _revertMessageID.value = info.revertMessageID
    }

    /**
     * 撤销本轮变更 (`session/revert.ts:92-99`): rewind the revert boundary one
     * user message, computed by `SessionActionsModel.undoStep` over the **raw**
     * user-message list (the boundary is an index into that list, so the visible
     * projection must never be fed to it). Nothing to rewind → no request, and
     * `onResult(false)`; the menu row is disabled in exactly that state anyway
     * (`SessionActionsModel.menu`).
     *
     * The write is the same three steps as [revertToMessage]: interrupt →
     * `stageRevert` → `commitRevert`. **Staging alone is not a revert** — it
     * only records the boundary (`core/src/session/revert.ts:23-59` publishes
     * `session.revert.staged`, which the projector writes to
     * `session.revert`); the truncation happens on `commit`
     * (`core/src/session/revert.ts:78-84` → `projector.ts:731-767` deletes every
     * `session_message` row from the boundary on and clears `revert`). A
     * stage-only 撤销 therefore hid the tail by *projection* while
     * `GET /api/session/:id/message` still answered every reverted row — the
     * next read (reopen / reconnect) brought the hidden tail back, which is the
     * device report. [OpenCodeClient.sessionRevert] states the same contract for
     * the single-call v1 shape: "staging alone changes nothing visible, so the
     * commit is part of this call".
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
        val users = SessionActionsModel.userMessageIDs(_state.value.messages)
        val target = SessionActionsModel.undoStep(users, _revertMessageID.value)
        // v99 runtime trace (keep — this is the hop-by-hop evidence the ⋮ Undo
        // no-op was diagnosed with): the four silent-return branches below all
        // used to be indistinguishable from a successful revert, because the
        // only logs in this function were the two failure ones.
        Log.d(
            TAG,
            "undo: dispatch session=$id users=${users.size} boundary=${_revertMessageID.value} " +
                "target=$target transport=${transport != null}",
        )
        if (transport == null || id.isNullOrEmpty()) {
            Log.w(TAG, "undo: dropped — no transport or no session id")
            onResult(false)
            return
        }
        if (target == null) {
            Log.w(TAG, "undo: dropped — undoStep found nothing to rewind")
            onResult(true)
            return
        }
        val restore = revertRestore(id, target)
        scope.launch {
            interruptIfRunning(id)
            val boundary = try {
                withContext(Dispatchers.IO) { transport.stageRevert(id, target) }
            } catch (e: Exception) {
                Log.w(TAG, "revert failed: ${e.message}")
                null
            }
            // v1 applies a revert immediately (no commit step); v2 needs the
            // commit to make the staged boundary permanent — see the KDoc.
            val committed = when {
                boundary == null -> false
                transport.generation != ServerGeneration.V2 -> true
                else -> try {
                    withContext(Dispatchers.IO) { transport.commitRevert(id) }
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "revert commit failed: ${e.message}")
                    false
                }
            }
            Log.d(
                TAG,
                "undo: settle session=$id target=$target stagedBoundary=$boundary committed=$committed",
            )
            if (!committed) {
                onResult(false)
                return@launch
            }
            // Boundary writes are session-scoped: landing after a switch would
            // crop the new session's timeline through the revert projection.
            if (boundary != null && sessionID == id) _revertMessageID.value = boundary
            // v99: the commit **deleted** those rows server-side and cleared
            // `revert`, so the projection alone cannot carry the result — see
            // [dropRevertedTail] for why the timeline used to snap back.
            if (boundary != null && sessionID == id) dropRevertedTail(boundary)
            applyRevertRestore(id, restore)
            onResult(true)
        }
    }

    /**
     * Make the commit's truncation durable in the client: drop every row at or
     * after [boundary] from the **raw** [_state], not just from the rendered
     * projection.
     *
     * v99 — the ⋮ `Undo` no-op. `commit` does not merely hide the tail, it
     * **deletes** those `session_message` rows and clears `Session.Info.revert`
     * (`core/src/session/revert.ts:78-84` → `projector.ts:731-767`; confirmed
     * live on 5556: `GET /message` 12 rows → 3, `revert` still `null` after the
     * write). Two client facts then put the turn straight back on screen:
     *
     * 1. the transcript read ([refreshSnapshot]) merges by **upsert**, so a row
     *    the server no longer returns is never removed from [_state]; and
     * 2. that same read adopts `_revertMessageID = info.revertMessageID`, which
     *    the commit just cleared — so the projection stops hiding the tail too.
     *
     * Net effect: the write succeeded, and the very next snapshot made the UI
     * look exactly as before — a successful `Undo` indistinguishable from a
     * no-op, with no failure log to show for it.
     *
     * Dropping the rows here is what upstream's projector does, and it is
     * idempotent: the next snapshot cannot resurrect a row the server stopped
     * returning. Ordering stays `_revertMessageID` first, so the projection
     * hides the tail in the same frame the store drops it.
     */
    private fun dropRevertedTail(boundary: String) {
        var dropped = 0
        _state.update { current ->
            val hidden = SessionActionsModel.hiddenMessageIDs(current.messages.map { it.id }, boundary)
            if (hidden.isEmpty()) return@update current
            val hiddenIDs = hidden.toSet()
            dropped = hiddenIDs.size
            current.copy(messages = current.messages.filterNot { it.id in hiddenIDs })
        }
        Log.d(TAG, "undo: dropped $dropped reverted row(s) from the store")
    }

    /**
     * The composer handoff upstream performs on **every** revert that moves the
     * boundary forward (`_upstream/packages/app/src/session/revert.ts:31-57`
     * `restore`): the user message the boundary lands on is about to leave the
     * transcript, so its prompt goes back into the composer, where it can be
     * edited and re-sent. Upstream's `stage` also repoints the composer at the
     * preceding user message and sweeps the pending inbox; this app's boundary
     * writes carry neither.
     *
     * Without the handoff the row and its text are simply gone — the v60 report
     * (`research/v60_verify/5558/10_reply_done.png` → `11_retry.png`: tapping
     * the ↺ on 「say hello」 left only the 「模型已切换」 divider, with nothing to
     * re-send). The disappearance itself is upstream-faithful
     * (`revert.to` drops the message *and* everything after it); the loss is
     * the missing restore.
     *
     * Read from the **raw** store ([_state]) before the write: the boundary is
     * what hides the row, so once it lands the message is gone from the
     * rendered projection. The extraction itself is
     * [SessionActionsModel.revertRestore] — pure, and JVM-tested there.
     */
    private fun revertRestore(sessionID: String, messageID: String): SessionActionsModel.RevertRestore? =
        SessionActionsModel.revertRestore(_state.value.messages, messageID)

    private fun applyRevertRestore(sessionID: String, restore: SessionActionsModel.RevertRestore?) {
        if (restore == null) return
        ComposerDrafts.restoreHandoff(
            ComposerDrafts.key(sessionID),
            restore.text,
            restore.files.map { ComposerDrafts.DraftAttachment(it.mime, it.filename, it.dataUrl) },
        )
    }

    /**
     * Message-level「撤销到此」(`session/revert.ts:85-119` `to`): stage the
     * boundary at [messageID], a **user** message id, dropping it and
     * everything after. `SessionActionsModel.revertToStep` owns the id check
     * (upstream looks it up in `history.userMessages()` and no-ops when
     * missing — a legitimate no-op reported as `true`, see [undoSession]).
     *
     * Body follows [redoSession]'s `RevertStep.RevertTo` branch: interrupt →
     * `stageRevert` → the v2 `commitRevert` that makes the staged boundary
     * permanent (v1 applies it immediately), then [applyRevertRestore] — the
     * dropped prompt's text comes back into the composer, as upstream's
     * `stage` does.
     */
    fun revertToMessage(messageID: String, onResult: (Boolean) -> Unit = {}) {
        val transport = requireTransport()
        val id = sessionID
        val step = SessionActionsModel.revertToStep(
            SessionActionsModel.userMessageIDs(_state.value.messages),
            messageID,
        )
        if (transport == null || id.isNullOrEmpty()) {
            onResult(false)
            return
        }
        if (step == null) {
            onResult(true)
            return
        }
        val restore = revertRestore(id, step.messageID)
        scope.launch {
            _reverting.value = true
            try {
                interruptIfRunning(id)
                val boundary = try {
                    withContext(Dispatchers.IO) { transport.stageRevert(id, step.messageID) }
                } catch (e: Exception) {
                    Log.w(TAG, "revert to message failed: ${e.message}")
                    null
                }
                if (boundary == null) {
                    onResult(false)
                    return@launch
                }
                if (transport.generation == ServerGeneration.V2) {
                    try {
                        withContext(Dispatchers.IO) { transport.commitRevert(id) }
                    } catch (e: Exception) {
                        Log.w(TAG, "revert commit failed: ${e.message}")
                        onResult(false)
                        return@launch
                    }
                }
                if (sessionID == id) _revertMessageID.value = boundary
                applyRevertRestore(id, restore)
                onResult(true)
            } finally {
                _reverting.value = false
            }
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
        // Upstream's `redo` stages the next prompt, so it restores it into the
        // composer the same way `to` / `undo` do. The `Unrevert` branch is the
        // one exception: it clears the boundary, which brings the hidden rows
        // **back** into the transcript, so there is nothing to hand back (and
        // upstream's `target.reset()` would only throw away a draft the user
        // typed since).
        val restore = (step as? RevertStep.RevertTo)?.let { revertRestore(id, it.messageID) }
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
                            true
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "redo failed: ${e.message}")
                false
            }
            // Every boundary write below is gated on `id` still being tracked —
            // a switch mid-flight would hide the new session's messages through
            // the `message.id >= boundary` projection.
            if (ok && step is RevertStep.Unrevert && sessionID == id) {
                _revertMessageID.value = null
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
                if (sessionID == id) _revertMessageID.value = step.messageID
            } else if (ok && step is RevertStep.RevertTo && sessionID == id) {
                _revertMessageID.value = transport.let {
                    // v1 returns the boundary from the write; re-read cheaply
                    // via stageRevert's return (already the messageID).
                    step.messageID
                }
            }
            if (ok) applyRevertRestore(id, restore)
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
            // Silent return here was the measured hang: NewSessionScreen sat on
            // 「Creating a session…」 with neither callback ever firing. Fail
            // fast with the real reason (probe pending vs. auth wall vs. not 2.x).
            val probe = lastProbe
            onError(
                when {
                    probing.get() -> "Still checking the server — try again in a moment."
                    probe?.authWall == true -> "Server needs a valid username/password (check Servers)."
                    probe != null && probe.httpStatus == null -> "Server did not answer."
                    else -> "Not connected to an OpenCode 2.x server."
                },
            )
            return
        }
        scope.launch {
            try {
                // v58 P0: the location is written, not merely remembered. A
                // directory that already carries a repeated segment
                // (`<repo>/app/src/app/src`, one more per session the loop added)
                // names nothing on disk, and the server answers every later
                // request of such a session `500 FileSystem.realPath … ENOENT` —
                // see `ProjectPaths.normalizeLocation` and
                // `research/v58_verify/5556_b/REPORT.md` §0.
                val location = ProjectPaths.normalizeLocation(directory)
                val newID = withContext(Dispatchers.IO) { transport.createSession(location, null) }
                // Upstream `session.promote()` hands the draft's selection to the
                // session it just created (`selection.tsx:429-454`): the pick
                // made before the session existed becomes *this* session's pick,
                // which is what keeps it the value written back below (and what
                // keeps the chip on it after the promotion).
                recentModel?.let { sessionPicks[newID] = it }
                // v69: the draft's effort is promoted with its model — the pair
                // is one saved state upstream (`selection.tsx:429-454`).
                draftVariant?.let { sessionVariants[newID] = it }
                // Directory: v2 sessions report location.directory on fetch;
                // observe optimistically with the requested directory first.
                observeSession(id = newID, directory = location)
                // Device v14 (finding 2): the composer's pick may predate the
                // session (draft page), and the session is born with `model`
                // null. Land the pick before anything runs against it — the
                // first prompt's steer switch would do it too, but a session
                // created without one (then used for a `/btw`) would not.
                selectedModelPin()?.let { pin ->
                    withContext(Dispatchers.IO) {
                        try {
                            transport.switchModel(newID, pin.providerID, pin.modelID, pin.variant)
                        } catch (e: Exception) {
                            Log.w(TAG, "model pin after create failed: ${e.message}")
                        }
                    }
                }
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
     * `GET /api/project` read for Home's projects column and the draft page's
     * project selector. Read-only (no SSE, no session needed). `null` = fetch
     * failed, so callers can fall back to their derived/offline presentation
     * instead of mistaking a failure for an empty server. The reason lands on
     * [onFailure] / [browseFailure]. Idempotent: transient connection failures
     * retry through [readTransport].
     */
    fun listProjects(onFailure: (TransportFailure) -> Unit = {}, onResult: (List<OpenCodeClient.Project>?) -> Unit) {
        val transport = requireTransport()
        if (transport == null) {
            reportBrowseFailure(noTransportFailure(), onFailure)
            onResult(null)
            return
        }
        scope.launch {
            val projects = try {
                // Re-resolve per attempt: a retry that ran past a re-probe must
                // use the transport that probe installed, not the dead one.
                val fetched = withContext(Dispatchers.IO) {
                    readTransport { (requireTransport() ?: transport).listProjects() }
                }
                _browseFailure.value = null
                fetched
            } catch (e: Exception) {
                Log.w(TAG, "project list failed: ${e.message}")
                reportBrowseFailure(TransportFailure.RequestFailed(e.message), onFailure)
                null
            }
            onResult(projects)
        }
    }

    /**
     * The server's cwd ([SessionTransport.browseCwd]): the draft's default
     * location and the review tree's root. `null` on failure.
     */
    fun serverDirectory(onResult: (String?) -> Unit) {
        val transport = requireTransport()
        if (transport == null) {
            onResult(null)
            return
        }
        scope.launch {
            val directory = try {
                // Through the transport, never the v1 client: on v2 `GET /path`
                // is the SPA catch-all (`200 text/html`), so the parse would
                // throw and the review tree would never resolve a root.
                withContext(Dispatchers.IO) { transport.browseCwd }
            } catch (e: Exception) {
                Log.w(TAG, "path fetch failed: ${e.message}")
                null
            }
            onResult(directory)
        }
    }

    /**
     * `GET /file?path=` / `GET /api/fs/list` for the add-project directory
     * browser. `null` on failure with a distinguishable [TransportFailure] via
     * [onFailure] / [browseFailure] — the picker used to render every failure
     * as 「无法加载此目录」 with no way to tell a dead server from a missing
     * transport (3-device repro: the probe's 401 left `transport == null` and
     * this answered `onResult(null)` while `curl /api/fs/list?directory=/` was
     * 200). The dialog keeps showing the current path and offers a retry (v1
     * outside the server cwd is a measured 500 — see
     * [SessionTransport.listDirectory]).
     *
     * @param fresh a **gesture** read (the picker's open / navigation), which
     *   outranks a latched probe verdict the way its Retry button does: when no
     *   transport is installed the verdict is dropped, one probe round is
     *   started, and the answer waits for that round (bounded, like every other
     *   probe wait) instead of reporting the stale verdict. Without it a picker
     *   closed and reopened while `lastProbe` held a definitive negative
     *   answered [TransportFailure.NotConnected] — 「Server is reachable but has
     *   no directory API」 — for a server the very next probe found healthy, and
     *   only the Retry button could clear it (v21 P3, 334 §bug2). A live
     *   transport makes this a no-op ([retryProbe]'s own first guard).
     */
    fun listFiles(
        path: String,
        fresh: Boolean = false,
        onFailure: (TransportFailure) -> Unit = {},
        onResult: (List<DirEntry>?) -> Unit,
    ) {
        val ready = requireTransport()
        if (ready != null) {
            scope.launch(Dispatchers.IO) { readDirectory(ready, path, onFailure, onResult) }
            return
        }
        if (!fresh) {
            reportBrowseFailure(noTransportFailure(), onFailure)
            onResult(null)
            return
        }
        scope.launch(Dispatchers.IO) {
            // Same precedence as the Retry tap, and for the same reason: a
            // gesture must not walk into `maybeReprobe`'s "a definitive negative
            // never re-probes" gate (that gate stays — hot reads still skip a
            // latched verdict; see `ProbeSelfHealTest`).
            retryProbe()
            // …then answer from *this* round. Waiting is what makes the read
            // fresh: the verdict `retryProbe` just dropped is the one that used
            // to be reported, and `ProbePending` while the round is in flight
            // beats a stale negative.
            awaitProbeSettled()
            val transport = requireTransport()
            if (transport == null) {
                reportBrowseFailure(noTransportFailure(), onFailure)
                withContext(Dispatchers.Main) { onResult(null) }
                return@launch
            }
            readDirectory(transport, path, onFailure, onResult)
        }
    }

    /** One `listDirectory` read of an installed [transport], reported on Main. */
    private suspend fun readDirectory(
        transport: SessionTransport,
        path: String,
        onFailure: (TransportFailure) -> Unit,
        onResult: (List<DirEntry>?) -> Unit,
    ) {
        val nodes = try {
            val listed = transport.listDirectory(path)
            _browseFailure.value = null
            listed
        } catch (e: Exception) {
            Log.w(TAG, "file list failed: ${e.message}")
            reportBrowseFailure(TransportFailure.RequestFailed(e.message), onFailure)
            null
        }
        withContext(Dispatchers.Main) { onResult(nodes) }
    }

    /** [TransportFailure] for a read that found no transport — split by probe state. */
    private fun noTransportFailure(): TransportFailure {
        val server = serverConfig.server
        return transportFailureFor(probing.get(), lastProbe, server != null && server.isComplete)
    }

    /**
     * Manual re-probe kick for the directory picker / palette retry buttons —
     * **the explicit tap outranks every latch**, which is the v20 finding
     * (`research/v20_verify/panel_compact/v20_picker.png`: the add-project
     * browser on a first launch after install answered 「Not connected to a
     * server」 and its Retry did nothing at all, while a process restart healed
     * it).
     *
     * Two latches made that tap a no-op, and both are cleared here:
     *
     * 1. **the verdict** — [maybeReprobe] deliberately never re-probes a
     *    definitive negative (`lastProbe.isRetryable == false`, i.e. the host
     *    answered and it is not 2.x). Correct for a hot read, wrong for a
     *    gesture: the cold-start probe can run before the reverse mapping /
     *    the stack is up and answer e.g. a 404, and no later read would ever
     *    revisit that judgement. [lastProbe] is therefore dropped first, so the
     *    next round *is* the new truth — including for the picker's
     *    [TransportFailure] split, which reads [lastProbe] on the same tap.
     * 2. **the config window** — [maybeReprobe] bails while no complete server
     *    is configured, and the launch-after-install window is exactly that:
     *    the WebView's `syncServerConfig` lands seconds after the process
     *    starts, so the first `ensureClient()` found nothing and no probe ever
     *    ran. [ServerConfigStore.freshServer] re-reads prefs at the tap, and a
     *    server that appeared since is bound here ([ensureClient] runs the
     *    probe itself) instead of waiting for the next restart.
     *
     * Still deduped: an in-flight probe is a round already running
     * ([ensureTransport]'s `probing` CAS), and the picker re-reads on the
     * `generation` change that round lands. And a **live** transport short-circuits
     * the whole thing: it is the one state that is not a latch, and re-probing it
     * could only risk `setGeneration(UNKNOWN)` closing a working connection over a
     * one-round network blip.
     */
    fun retryProbe() {
        // A live transport is not a latch: there is nothing to heal, and
        // re-probing one could only risk `setGeneration(UNKNOWN)` closing a
        // working connection because a single round saw a network blip. The
        // picker's own re-read is its `attempt` key, not this.
        if (transportRef.get() != null) return
        lastProbe = null
        lastProbeAttemptAt = 0L
        // B-7: the retry is the way back from the incompatible panel, so the
        // verdict must not survive the round it asked for.
        _incompatibleServerVersion.value = null
        if (clientRef.get() == null && ensureClient()) {
            // A server that arrived after the first launch: `ensureClient` has
            // just bound it and started the probe itself (and it sets
            // [configMissing] on the way out when there is nothing to bind), so
            // the sticky 「configure a server」 state must go with it.
            _configMissing.value = false
            return
        }
        val server = serverConfig.freshServer() ?: serverConfig.server
        if (server == null || !server.isComplete) return
        ensureTransport(server)
    }

    /**
     * The OS reports the network is back ([NetworkRecoveryWatch]): heal a
     * flap-stranded connection now instead of waiting out the stream idle
     * watchdog, the grown reconnect backoff and the lazy re-probe throttle.
     * Safe from any thread; the OS may fire on every network switch, and every
     * branch below dedupes (in-flight probe, single-flight dial, verdict
     * gates).
     *
     * v81 BUG-01 (emulator-5558): the heal has to end in **live streams**, not
     * only in a poke — the branch below rebuilds them when the flap outlived
     * them ([openV2Streams]) and [EventStream.reconnectNow] itself covers every
     * state a live stream can be in, including a backoff chain that stopped.
     * The store half of the recovery rides the streams' own `ESTABLISHED`
     * (`ChatStore.Event.TransportRecovered`), so a healed connection also takes
     * the stale transport card off the screen.
     *
     * The Terminal-tab bootstrap is untouched: with no usable server row this
     * returns before writing anything, so the intentional "server config not
     * loaded" state is never auto-cleared. It only clears the setup hint when
     * a server *is* configured (the hint then contradicts prefs — a stale mark
     * left by a client that died before it could bind).
     */
    fun onNetworkAvailable() {
        val server = serverConfig.freshServer() ?: serverConfig.server
        if (server == null || !server.isComplete) return
        _configMissing.value = false
        when (networkHealAction(server.isComplete, transportRef.get() != null, lastProbe?.isRetryable)) {
            NetworkHealAction.NOTHING -> Unit
            NetworkHealAction.POKE_STREAMS -> {
                // A poke is only worth anything against a stream that exists. The
                // flap can outlive them — a transport rebuilt under a `stop()` /
                // `start()` pair, a stream the client-error breaker stopped and
                // dropped — and the old branch poked `null` and reported success
                // (v81 BUG-01, emulator-5558: the connection healed and nothing
                // in the app moved). A live stream is poked; a missing pair is
                // rebuilt through the one opener ([openV2Streams]), and only while
                // this view model is started (otherwise the screen's own `start()`
                // owns the streams and a heal must not create them behind it).
                val global = eventStream
                if (global != null) {
                    // [EventStream.reconnectNow] covers every state a live stream
                    // can be in, including a stopped backoff chain.
                    global.reconnectNow()
                    sessionLogStream?.reconnectNow()
                } else if (started.get()) {
                    openV2Streams(server)
                }
            }
            NetworkHealAction.REPROBE -> {
                lastProbeAttemptAt = 0L
                retryProbe()
            }
        }
    }

    /** Publishes [failure] on [browseFailure]; [onFailure] always lands on Main. */
    private fun reportBrowseFailure(
        failure: TransportFailure,
        onFailure: (TransportFailure) -> Unit = {},
    ) {
        _browseFailure.value = failure
        scope.launch { onFailure(failure) }
    }

    /**
     * Browse constraints of the active transport for the directory picker:
     * the 「根目录」 target, the `~` expansion, and whether the tree may leave
     * the root (v1 cannot — see [SessionTransport.browseEscapeAllowed]).
     * Both path getters block on `GET /path` (v1) or `GET /api/location` (v2);
     * a missing transport answers empty/false so the dialog just disables the
     * optional affordances.
     *
     * @param fresh the picker's open read — see [listFiles]'s `fresh`: the
     *   constraints are the picker's *starting path*, so answering them from a
     *   latched verdict parked the browser on `/` with no home (v21 P3, 334
     *   §bug2). A gesture drops the verdict and waits for the round it starts.
     */
    fun browseConstraints(
        fresh: Boolean = false,
        onResult: (root: String, home: String?, escapeAllowed: Boolean) -> Unit,
    ) {
        scope.launch(Dispatchers.IO) {
            if (fresh && requireTransport() == null) {
                retryProbe()
                awaitProbeSettled()
            }
            val transport = requireTransport()
            val root = try {
                transport?.browseRoot.orEmpty()
            } catch (e: Exception) {
                Log.w(TAG, "browse root failed: ${e.message}")
                ""
            }
            val home = try {
                transport?.browseHome
            } catch (e: Exception) {
                Log.w(TAG, "browse home failed: ${e.message}")
                null
            }
            val escapeAllowed = transport?.browseEscapeAllowed ?: true
            withContext(Dispatchers.Main) {
                onResult(root.ifEmpty { "/" }, home, escapeAllowed)
            }
        }
    }

    // ---- review panel (changes / files side panel) --------------------------
    //
    // `session.diff` / `todo.updated` SSE events already flow through the
    // generic `ChatStore.parseEvent` → `applyEvent` path in [start]; these are
    // the REST side: an explicit pull when the panel opens (same pattern as
    // [refreshPermissions]) plus the lazy file-tree reads.

    /**
     * Location ref a review VCS read last got an answer from, keyed by the
     * tracked session's directory ([ReviewVcsReprobe]): the canonical ref for a
     * healthy location, the fresh-graph variant for one whose cached services
     * predate a `git init`. Written from IO coroutines; a lost race costs one
     * extra request, never a wrong answer.
     */
    private val reviewVcsRefs = ConcurrentHashMap<String, String>()

    /** Directories already re-probed, so a refresh cannot re-issue the probe. */
    private val reviewVcsProbed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Monotonic id of the newest [reviewVcsDiff] request (v97 D11-b,
     * `research/v97_fix/REVIEW_DIFF_HONESTY_FIX2.md`).
     *
     * **Why a superseded walk must not publish.** The review's VCS read has
     * exactly one slot on the other side (`SessionScreen.vcsDiffResult`), and a
     * new attempt clears that slot to `null` (→ the loading/unknown state) the
     * moment it starts. But a read launched in the *old* attempt's
     * [scope] outlives it: `LaunchedEffect` cancellation does not cancel a
     * coroutine the ViewModel owns, so on a 1191-file tree the 20 s-deadline
     * walk from the previous attempt is still running when the retry has
     * already started, and it used to land its verdict in the retry's slot when
     * it finished. Measured on emulator 5556 that is the `T+20s` failure banner
     * painting over the `T+3s` frame, and with the two verdicts disagreeing it
     * is exactly the window where 「clean」 and 「failed」 both get a turn. Only
     * the newest attempt may speak; a superseded one publishes nothing and the
     * panel keeps showing the unknown state, which is true of both.
     */
    private val reviewVcsDiffAttempt = AtomicLong(0L)

    /**
     * Client of the review's VCS reads: the shared client plus an explicit
     * [REVIEW_VCS_TIMEOUT_MS] deadline.
     *
     * `vcs/diff?mode=working` builds a whole-tree patch server-side, and the
     * route can simply hang on a large working tree (measured 2026-09-25 on
     * 2.0.11: no response in 25 s from a location with thousands of uncommitted
     * files, while a small one answers `{data:[]}` instantly). The shared
     * client's own 30 s read timeout only covers one socket read, so the review
     * read gets a wall-clock `callTimeout` instead — the panel then reports a
     * failure the user can retry rather than a request that never returns.
     *
     * `newBuilder()` keeps the shared connection pool and dispatcher, so this
     * is one per-call deadline and no extra threads; it is never closed (that
     * would evict the shared pool's connections).
     */
    private val reviewVcsHttp: OkHttpClient by lazy {
        sharedClient.newBuilder()
            .callTimeout(REVIEW_VCS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    /**
     * Session diff pull for the review panel's Last-turn scope — v1
     * `GET /session/:id/diff`, v2 `GET /api/session/{id}/diff` (measured HTML
     * on v2 for the unprefixed route, so this goes through the transport).
     * Failure is silent — the SSE `session.diff` events keep driving the list.
     * The result is only applied while the same session is still tracked (a
     * switch mid-flight must not write another session's diff into the fresh
     * state).
     */
    fun refreshDiff() {
        val id = sessionID ?: return
        val transport = requireTransport() ?: return
        scope.launch {
            val diff = try {
                withContext(Dispatchers.IO) { transport.sessionDiff(id, directory = directory) }
            } catch (e: Exception) {
                Log.w(TAG, "session diff failed: ${e.message}")
                null
            } ?: return@launch
            if (sessionID == id) applyEvent(ChatStore.Event.SessionDiff(id, diff))
        }
    }

    /**
     * `GET /vcs` (v1) / `GET /api/vcs` (v2) for the review panel header
     * (current branch) — the shapes converge on [OpenCodeClient.VcsInfo]
     * inside the transport. `null` on failure — the header simply hides the
     * branch chip.
     *
     * Runs over [reviewVcsCandidates]: a location whose services predate its
     * `git init` has no VCS provider and answers `{"branch":{}}` for the
     * canonical directory, while the same directory through a fresh location
     * graph answers with the real branch — see [ReviewVcsReprobe].
     *
     * The read goes through [reviewVcsApi] so the candidate is the **only**
     * `location[directory]` (see there).
     */
    fun reviewVcsInfo(onResult: (OpenCodeClient.VcsInfo?) -> Unit) {
        val transport = requireTransport()
        if (transport == null) {
            onResult(null)
            return
        }
        scope.launch(Dispatchers.IO) {
            val api = reviewVcsApi(transport)
            var answered: OpenCodeClient.VcsInfo? = null
            var last: OpenCodeClient.VcsInfo? = null
            // v97 D11-b (`research/v97_fix/REVIEW_DIFF_HONESTY_FIX2.md`): this
            // walk deliberately remembers **nothing**. It used to write the ref
            // that answered `vcs/status` into the two bookkeeping sets
            // ([reviewVcsRefs] / [reviewVcsProbed]) that [reviewVcsCandidates]
            // hands the *diff* walk as `remembered` / `probed` — so a
            // `vcs/status` answer was silently promoting a ref to 「the only ref
            // worth asking」. A location whose cached graph predates the
            // repository answers **both** routes, and its `vcs/diff` is `[]` no
            // matter what the tree holds ([ReviewVcsReprobe]'s measurement), so
            // the promotion collapsed the diff walk to that one ref: it answered
            // `[]` in milliseconds, the verdict said EMPTY, and the Changes tab
            // claimed 「No uncommitted changes yet」 for a tree whose host
            // `git diff --shortstat HEAD` read 1191 files changed. The
            // fresh-graph ref — the one that could have disproved it — was never
            // even asked. 记住一个 ref 的资格只能来自它自己答出过真实的 diff
            // (that is still what [reviewVcsDiff] does, on a non-empty list).
            // The cost is one extra cheap `vcs/status` per read; the benefit is
            // that a `[]` from an uncorroborated ref can no longer end a walk.
            for (ref in reviewVcsCandidates()) {
                val info = try {
                    if (api != null) v2VcsInfoRow(api.vcs(locationDirectory = ref))
                    else transport.vcsInfo(ref)
                } catch (e: Exception) {
                    Log.w(TAG, "vcs info failed for ${ref ?: directory}: ${e.message}")
                    null
                } ?: continue
                last = info
                if (ReviewVcsReprobe.answered(info.branch, 0)) {
                    answered = info
                    break
                }
            }
            withContext(Dispatchers.Main) { onResult(answered ?: last) }
        }
    }

    /**
     * `vcs/diff?mode=` for the review panel's Git / Branch scopes (upstream
     * `vcs.diff({mode})`, `session/review/model.ts:23`). [mode] is `"git"` or
     * `"branch"`; the transport maps it to the generation's enum (v1 rejects
     * `working`, v2 rejects `git` — see [SessionTransport.vcsDiff]).
     *
     * [ReviewVcsDiffResult.Failed] when no candidate answered — **not** an
     * empty [ReviewVcsDiffResult.Loaded], because 「this scope has no changes」
     * and 「the read never came back」 are different claims. The read is bounded
     * by [REVIEW_VCS_TIMEOUT_MS]: `mode=working` builds a whole-tree patch
     * server-side, and on 2.0.11 that hangs for tens of seconds on a large
     * working tree (measured 2026-09-25 against `/home/xiufeigo` and against
     * this repo's own directory: no response in 25 s, while a small directory
     * answers `{data:[]}` instantly) — the panel used to call that hang
     * 「No uncommitted changes yet」.
     *
     * Like [reviewVcsInfo] it walks [reviewVcsCandidates], so a repository the
     * server's cached location predates still answers through the fresh graph,
     * and every read carries its candidate as the only `location[directory]`
     * (see [reviewVcsApi]). The first non-empty list wins; a directory that
     * never answers is only probed once per tracked session
     * (`reviewVcsProbed`), because a home-directory working diff is expensive
     * to produce.
     */
    fun reviewVcsDiff(mode: String, onResult: (ReviewVcsDiffResult) -> Unit) {
        val transport = requireTransport()
        if (transport == null) {
            onResult(ReviewVcsDiffResult.Failed)
            return
        }
        // v97 D11-b: this read claims the panel's single slot from the moment it
        // starts, so a walk left over from the previous attempt cannot land a
        // stale verdict in a newer attempt's state (see [reviewVcsDiffAttempt]).
        val attemptId = reviewVcsDiffAttempt.incrementAndGet()
        scope.launch(Dispatchers.IO) {
            val api = reviewVcsApi(transport)
            val candidates = reviewVcsCandidates()
            var diffs: List<OpenCodeClient.FileDiff>? = null
            // What every candidate's read actually produced, in order — the input
            // of the one aggregation rule ([ReviewVcsProbePlan.verdict]).
            val attempts = ArrayList<ReviewVcsProbePlan.Attempt>(candidates.size)
            // 未知 ≠ 空 (v97 D11-b). The walk starts with one **in flight**
            // placeholder per candidate and each iteration retires exactly its
            // own, so a partially-walked list is a state the rule can *see*
            // ([ReviewVcsProbePlan.readPhase] → IN_FLIGHT) rather than an absence
            // it has to infer. Round one had no such dimension: `Attempt` was
            // only Answered/Failed, so 「nobody has come back yet」 and
            // 「everybody said nothing changed」 were told apart only by *when* the
            // caller looked — and a caller that looked early, or raced a shorter
            // walk over the same slot, read a stale `[]` as the whole truth. The
            // list the rule finally sees is unchanged: each iteration drops its
            // own placeholder before appending that ref's outcome.
            repeat(candidates.size) { attempts += ReviewVcsProbePlan.Attempt.Pending }
            // Worst failure seen across the candidates, or null while every ref
            // either answered or has not been tried yet. A read that got *any*
            // answer is a settled fact about the location (see below), so this
            // only decides what an all-threw attempt may forget.
            var failure: ReviewVcsProbePlan.Failure? = null
            for (ref in candidates) {
                // This ref is no longer in flight: its own outcome is about to
                // land, so its placeholder is retired first.
                if (attempts.isNotEmpty() && attempts[0] is ReviewVcsProbePlan.Attempt.Pending) {
                    attempts.removeAt(0)
                }
                val list = try {
                    if (api != null) api.vcsDiff(v2VcsDiffMode(mode), locationDirectory = ref)
                    else transport.vcsDiff(mode, ref)
                } catch (e: Exception) {
                    Log.w(TAG, "vcs diff failed for ${ref ?: directory}: ${e.message}")
                    val kind = ReviewVcsProbePlan.failureOf(reviewVcsFailureCode(e))
                    attempts += ReviewVcsProbePlan.Attempt.Failed(kind)
                    if (failure == null || kind == ReviewVcsProbePlan.Failure.PERMANENT) failure = kind
                    null
                }
                if (list == null) continue
                attempts += ReviewVcsProbePlan.Attempt.Answered(list.size)
                diffs = list
                if (list.isNotEmpty()) {
                    rememberReviewVcsRef(ref)
                    break
                }
            }
            // v97 fix (research/v97_fix/REVIEW_HOME_SEARCH_DIAG.md §N6-②): a
            // ref that *answered* — even with an empty list — is a settled fact
            // about this location, so the next attempt still starts from the
            // canonical ref alone. An attempt where **nothing** answered is
            // different: a 20 s timeout on a huge working tree is exactly what
            // `mode=working` does under load, and retiring the fresh-graph
            // candidate there made every later retry strictly weaker than the
            // first (「重试比首次更弱」). Only a deterministic refusal may retire
            // it; a transient failure must not burn the recovery path.
            val settled = attempts.any { it is ReviewVcsProbePlan.Attempt.Answered }
            if (!settled && ReviewVcsProbePlan.shouldMarkProbed(failure ?: ReviewVcsProbePlan.Failure.TRANSIENT)) {
                markReviewVcsProbed()
            }
            // D11 (`research/v97_fix/REVIEW_DIFF_HONESTY_FIX.md`): the verdict is
            // the **walk's**, not the last ref's. An empty list from the stale
            // canonical ref — a location with no VCS provider answers `[]` no
            // matter what the tree holds — used to survive a sibling ref that
            // timed out, and the panel then read it as a clean working tree
            // (`No uncommitted changes yet`) for a repository with 207 changed
            // files. FILES still wins outright, so the recovery path (N6-a) is
            // untouched; only the mixed walk now reports what happened.
            val result: ReviewVcsDiffResult? = when (ReviewVcsProbePlan.verdict(attempts)) {
                // 未知 ≠ 空 (v97 D11-b). A walk that is still in flight has not
                // settled, so it publishes **nothing**: the panel keeps showing
                // the loading/unknown state, which is what is actually true of a
                // read that has not come back. This arm is the structural
                // difference from round one, where the three states were the only
                // three the caller could be handed — 「still reading」 had to be
                // spelled as one of them, and the empty one was the cheapest lie.
                ReviewVcsProbePlan.Verdict.IN_FLIGHT -> null
                ReviewVcsProbePlan.Verdict.FILES ->
                    diffs?.let(ReviewVcsDiffResult::Loaded) ?: ReviewVcsDiffResult.Failed
                // Every candidate answered, and every one of them said 「nothing
                // changed」 — the only shape where an empty list is the truth, and
                // the only one [ReviewVcsProbePlan.mayRenderClean] licenses.
                ReviewVcsProbePlan.Verdict.EMPTY -> ReviewVcsDiffResult.Loaded(emptyList())
                ReviewVcsProbePlan.Verdict.FAILED -> ReviewVcsDiffResult.Failed
            }
            // A walk a newer attempt has already superseded stays silent as well:
            // its verdict describes a read the user has moved on from, and
            // landing it would paint over the newer attempt's state (the
            // T+3s/T+20s disagreement measured on emulator 5556).
            if (result != null && attemptId == reviewVcsDiffAttempt.get()) {
                withContext(Dispatchers.Main) { onResult(result) }
            }
        }
    }

    /**
     * The location-scoped [V2Api] the review's VCS reads use, or null when the
     * transport is not v2 (then its own call is the generation's contract).
     *
     * Built with **no instance default** location and the candidate passed as
     * the `location[directory]` override, because [V2Api.builder] appends an
     * instance default *before* the override, and a repeated query key is
     * resolved to its **first** occurrence server-side (measured 2026-09-25 on
     * 2.0.11: `GET /api/vcs?location[directory]=/tmp&location[directory]=/home/xiufeigo`
     * answers `{"location":{"directory":"/tmp"}}`). The transport's own handle
     * carries the tracked session's directory as that default, so going through
     * it dropped every candidate that differed from it — the fresh-graph
     * variant `ReviewVcsReprobe` needs (`<dir>/.`) never reached the server,
     * and a session opened after the transport was built asked about the
     * *previous* session's directory. Only the override leaves exactly one
     * `location[directory]`, with the value the caller meant.
     *
     * The handle also carries [reviewVcsHttp], the timeout client: this is the
     * read that can hang, so it gets a deadline even though [transportApi]'s
     * other (catalog) reads keep the shared client.
     */
    private fun reviewVcsApi(transport: SessionTransport): V2Api? {
        if (transport !is V2Transport) return null
        return transportApi(transport, location = null, http = reviewVcsHttp)
    }

    /**
     * Location refs the review's VCS reads try, in order: the directory's
     * remembered ref once it answered, else the canonical directory followed by
     * the fresh-graph variants of [ReviewVcsReprobe.refs]. A directory only
     * keeps the canonical ref alone after a **permanent** probe rejection
     * ([ReviewVcsProbePlan]) — a transient one (the 20 s timeout a huge working
     * tree produces) leaves the candidate list intact, so a retry is never
     * weaker than the attempt before it. Null (unscoped read, as before) when
     * the tracked session has no directory yet.
     */
    private fun reviewVcsCandidates(): List<String?> =
        ReviewVcsProbePlan.candidates(
            directory = directory,
            remembered = directory?.let { reviewVcsRefs[it] },
            probed = directory != null && directory in reviewVcsProbed,
        )

    /** Remembers the ref that answered for the tracked session's directory. */
    private fun rememberReviewVcsRef(ref: String?) {
        val dir = directory ?: return
        if (ref.isNullOrBlank()) return
        reviewVcsRefs[dir] = ref
        reviewVcsProbed += dir
    }

    /** Marks the tracked session's directory as probed (no VCS answer found). */
    private fun markReviewVcsProbed() {
        directory?.let { reviewVcsProbed += it }
    }

    /**
     * 「刷新」 of the review panel: **evict** the tracked directory's cached
     * location (`DELETE /api/debug/location?location[directory]=<dir>`,
     * `debug.ts:19-31` → [V2Api.evictDebugLocation]) and let the review's reads
     * re-probe, which is the write half of a problem the read-only workaround
     * in [ReviewVcsReprobe] can only dodge: the server builds a location's
     * services once per process (`LayerMap`, `idleTimeToLive: Duration.infinity`)
     * from a `Location` value that captured `project.vcs` at build time, so a
     * directory that became a Git repository afterwards keeps `vcs: undefined`
     * for the life of the process — `GET /api/vcs*` answers no provider and
     * `Snapshot.capture()` is off, which is why both review diffs stay empty.
     * Evicting one location disposes exactly that cached graph; the next read
     * rebuilds it from the current project table and answers correctly.
     *
     * **Only on an explicit user action** (the panel's refresh): the evict
     * cancels in-flight permissions and forms of that location, which is not a
     * cost to pay behind the user's back. [directory] falls back to the tracked
     * session directory; `null` only when neither is known.
     *
     * A successful evict also forgets the directory's re-probe state, so the
     * following reads start from the canonical ref again instead of the
     * `<dir>/.` fresh-graph variant the read-only path needed.
     *
     * The handle is built **without** an instance default location: the route
     * takes its cached ref from `location[directory]`, and an instance default
     * beside the override would send the key twice (`V2Api.url` appends the
     * default, `V2Api.located` the override), asking the server to pick.
     *
     * `onResult(true)` = the server disposed the location (204). It runs on the
     * Main thread, like every callback of this seam.
     */
    fun reviewReprobeLocation(directory: String?, onResult: (Boolean) -> Unit) {
        val dir = directory?.takeIf { it.isNotBlank() } ?: this.directory?.takeIf { it.isNotBlank() }
        val api = (transportRef.get() as? V2Transport)?.let { transportApi(it, location = null) }
        if (dir == null || api == null) {
            onResult(false)
            return
        }
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { api.evictDebugLocation(dir) }
                    .onFailure { Log.w(TAG, "location evict failed for $dir: ${it.message}") }
                    .isSuccess
            }
            if (ok) forgetReviewVcsProbe(dir)
            onResult(ok)
        }
    }

    /**
     * The HTTP status one failed review read carried, or null when it never got
     * one (socket timeout, cancellation, any other crash). Both API layers raise
     * their own `ApiException`, so the two are read by name here rather than by a
     * shared type; anything else is a no-status failure.
     */
    private fun reviewVcsFailureCode(e: Exception): Int? = when (e) {
        is V2Api.ApiException -> e.code
        is OpenCodeClient.ApiException -> e.code
        else -> null
    }

    /**
     * Drops the re-probe bookkeeping of [dir] ([reviewVcsRefs] /
     * [reviewVcsProbed]) so the next review read walks the candidates from the
     * canonical directory again. Called after an evict rebuilt the location.
     */
    private fun forgetReviewVcsProbe(dir: String) {
        reviewVcsRefs.remove(dir)
        reviewVcsProbed.remove(dir)
    }

    /**
     * Review file tree read: like [listFiles] but scoped to the tracked
     * session's `directory` (the picker's variant intentionally stays
     * unscoped) and mapped back to [OpenCodeClient.FileNode] so the review
     * tree keeps its `ignored` dimming. `null` on failure with the reason on
     * [onFailure] / [browseFailure].
     *
     * Two overloads on purpose: `SessionScreen` adapts this as
     * `{ path, onResult -> reviewListFiles(path, onResult) }`, so a plain
     * 2-arg positional call must keep compiling exactly as before.
     */
    fun reviewListFiles(path: String, onResult: (List<OpenCodeClient.FileNode>?) -> Unit) {
        reviewListFiles(path, {}, onResult)
    }

    fun reviewListFiles(path: String, onFailure: (TransportFailure) -> Unit, onResult: (List<OpenCodeClient.FileNode>?) -> Unit) {
        val transport = requireTransport()
        if (transport == null) {
            reportBrowseFailure(noTransportFailure(), onFailure)
            onResult(null)
            return
        }
        scope.launch(Dispatchers.IO) {
            val nodes = try {
                val listed = transport.listDirectory(path).map {
                    OpenCodeClient.FileNode(
                        name = it.name,
                        path = it.absolute,
                        absolute = it.absolute,
                        directory = it.isDirectory,
                        ignored = it.ignored,
                    )
                }
                _browseFailure.value = null
                listed
            } catch (e: Exception) {
                Log.w(TAG, "review file list failed: ${e.message}")
                reportBrowseFailure(TransportFailure.RequestFailed(e.message), onFailure)
                null
            }
            withContext(Dispatchers.Main) { onResult(nodes) }
        }
    }

    /**
     * Review panel file preview — v1 `GET /file/content?path=` JSON, v2
     * `GET /api/fs/read/<rel>` bytes (converted inside the transport; see
     * [SessionTransport.fileContent]). `null` on failure;
     * [OpenCodeClient.FileContent.binary] entries carry no text.
     */
    fun reviewFileContent(path: String, onResult: (OpenCodeClient.FileContent?) -> Unit) {
        val transport = requireTransport()
        if (transport == null) {
            onResult(null)
            return
        }
        scope.launch(Dispatchers.IO) {
            val content = try {
                transport.fileContent(path, directory)
            } catch (e: Exception) {
                Log.w(TAG, "review file content failed: ${e.message}")
                null
            }
            withContext(Dispatchers.Main) { onResult(content) }
        }
    }

    /**
     * Raw `{info, messages}` frame for the session export (Home row menu and
     * SessionScreen ⋮ → 导出会话) and for the context tab's one-shot read.
     * Routes through [awaitSessionTransport] — on v2 the unprefixed
     * `GET /session/:id[/message]` answer SPA HTML (measured 2.0.11) and the
     * `JSONObject` parse throws; [SessionTransport.fetchExportFrame] returns
     * the wire objects verbatim on both generations. IO-bound; `null` = fetch
     * failed (callers report `oc_export_failed` / skip the tab load).
     */
    suspend fun fetchExportFrame(sessionID: String, directory: String?): SessionExport.Frame? =
        fetchExportFrameResult(sessionID, directory).getOrNull()

    /**
     * [fetchExportFrame] with the failure reason preserved: `success(null)`
     * means the read produced nothing the caller can use; `failure` carries the
     * transport exception so the context tab can record its message in
     * `contextExportError` instead of falling back to the generic
     * `oc_export_failed` copy. Additive seam — the nullable call above keeps
     * serving the palette paths that only need presence.
     *
     * BUG-1 (v32 tB): the read runs through [readTransport], so a dropped
     * socket retries with backoff instead of surfacing as a permanent
     * 「Failed to connect to /127.0.0.1:4095」. A definitive rejection
     * (invalid cursor, 404) still comes back as-is on the first answer.
     *
     * v40 tC: "there is no transport" used to be `Result.success(null)` — the
     * same `null` a genuine missing frame answers with, so every Home export
     * with no confirmed transport showed the bare「导出会话失败」and the SAF
     * picker never opened. It is a [TransportUnavailableException] now, which
     * `HomeRowExportSaver` reports as its own line (`oc_export_no_transport`).
     *
     * v43 tC/tD: that line then turned out to be a **false** diagnosis on a
     * connected shell — Home taps sampled the transport before the rebind
     * probe settled. [awaitSessionTransport] owns the acquisition now; the
     * exception is reported only when no round is running and none could be
     * started.
     */
    suspend fun fetchExportFrameResult(
        sessionID: String,
        directory: String?,
    ): Result<SessionExport.Frame?> {
        val transport = awaitSessionTransport()
            ?: return Result.failure(TransportUnavailableException())
        return withContext(Dispatchers.IO) {
            runCatching {
                // Re-resolve per attempt: a retry that ran past a re-probe must
                // use the transport that probe installed, not the dead one.
                readTransport { (requireTransport() ?: transport).fetchExportFrame(sessionID, directory) }
            }
        }
    }

    /**
     * v43 tC/tD: the transport for one **Home-initiated** write, **acquired the
     * way every other read on this class acquires it** — [listSessions]'
     * sequence, not a bare [requireTransport] sampled at tap time.
     *
     * The bug: Home's 导出会话 reported 「尚未连接到服务器」 on a shell whose
     * Home list was live. Home never calls [start] (M1: sitting on Home costs
     * no connection) and the session route's [stop] drops both the client and
     * the transport on the way out, so a long-press right after leaving a
     * session can land while the rebind/probe is still in flight — a
     * [requireTransport] sampled in that window answers `null`, and the read
     * failed with a diagnosis the tester could disprove (chat ⋮ on the very
     * same view model exported fine, because the session route had already paid
     * for a settled transport).
     *
     * So: bind the client if it is gone, wait for the probe round that binding
     * kicked, and only report a missing transport once no round is running.
     * A stale positive verdict (a V2 probe answered before [stop] cleared the
     * transport) is not retryable, so [maybeReprobe] would never heal it — the
     * explicit [retryProbe] is the one caller-initiated way back, the same
     * escape the Servers panel's reconnect uses.
     *
     * v44 tC: the export read was not the only Home path that sampled the
     * transport at tap time — Home's 删除 and 重命名 were, and the delete half
     * of that is the P0 the device run recorded
     * (`research/v44_verify/tC/v44_49_delete_confirm.png`). This helper is
     * therefore shared by all three (`fetchExportFrameResult`, [deleteSession],
     * [renameSession]) rather than export-only, which is what its old name
     * said and what the fix needed it to stop saying.
     */
    private suspend fun awaitSessionTransport(): SessionTransport? {
        requireTransport()?.let { return it }
        ensureClient()
        awaitProbeSettled()
        if (requireTransport() == null) retryProbe()
        return requireTransport()
    }

    /**
     * One idempotent transport read through the shared retry policy
     * ([TransportRetry]): transient connection failures retry with backoff,
     * definitive answers and writes never do (writes do not call this).
     *
     * Every transient failure first drops the connections the failed attempt
     * left behind ([noteTransientTransportFailure]) — that eviction is what
     * makes the *next* attempt build a fresh socket instead of reusing the
     * broken one, and it is the on-demand half of the reconnect story the
     * probe cannot cover (the transport here is alive; only its pool is stale).
     */
    private suspend fun <T> readTransport(block: suspend () -> T): T =
        TransportRetry.runRead(onFailure = { noteTransientTransportFailure() }, block = block)

    /**
     * Recovery after a transient transport failure: evict the pooled
     * connections (only the pool — `OpenCodeClient.close` never shuts the
     * shared dispatcher the SSE clients use) and kick the throttled re-probe so
     * a transport that is genuinely gone is rebuilt on the next read.
     */
    private fun noteTransientTransportFailure() {
        clientRef.get()?.close()
        maybeReprobe()
    }

    // ----------------------------------------------- v2 extras (V2Api surface)
    //
    // The `V2Api` methods that had no UI caller yet, exposed here as the one
    // seam future panels talk to. Reads take `onResult` (null = failure, see
    // [listFiles]); writes report `Boolean`/id the same way. Every call is
    // IO-bound and returns to the Main thread before the callback — UI
    // consumers build Views / write Compose state there.
    //
    // Consumption status (see the delivery notes for the UI TODO list):
    // - websearchProviders → wired: `SessionFormDock` via `SessionScreen`.
    // - compact → wired: palette `session.compact` (`ui/App.kt`).
    // - context → wired: header context ring (`activeContextTokens`) +
    //   `SessionContextModel.project(activeMessages = activeContextRows)` via
    //   `SessionScreen` (one read feeds both).
    // - background (below) → wired: palette `session.background` (`ui/App.kt`).
    // - wait / markSessionViewed → wired: [ackSessionViewed] (session open +
    //   idle transitions).
    // - pty* / savedPermissions* / forms* / references / vcsBranches /
    //   vcsStatus / provider / pageSessions / health and the remaining five
    //   `session.*` control routes below → exposed; UI entry TODO.

    /** Provider catalog for the websearch consent dock (`GET /api/websearch/provider`). */
    private val _websearchProviders = MutableStateFlow<List<WebSearchOption>>(emptyList())
    val websearchProviders: StateFlow<List<WebSearchOption>> = _websearchProviders.asStateFlow()

    private val _websearchProvidersLoading = MutableStateFlow(false)
    val websearchProvidersLoading: StateFlow<Boolean> = _websearchProvidersLoading.asStateFlow()

    private val _websearchProvidersLoadFailed = MutableStateFlow(false)
    val websearchProvidersLoadFailed: StateFlow<Boolean> =
        _websearchProvidersLoadFailed.asStateFlow()

    /**
     * (Re)load [websearchProviders]. Upstream maps the rows to
     * `{value: id, label: name}` (`session/requests/model.ts:50-53`); the dock
     * renders the empty list as `session.websearch.empty` and a failed load as
     * its retry row, so both flags travel with the rows.
     */
    fun refreshWebsearchProviders() {
        if (_websearchProvidersLoading.value) return
        val api = v2Api() ?: return
        _websearchProvidersLoading.value = true
        _websearchProvidersLoadFailed.value = false
        scope.launch(Dispatchers.IO) {
            val rows = try {
                api.websearchProviders(directory).map { WebSearchOption(it.id, it.name) }
            } catch (e: Exception) {
                Log.w(TAG, "websearch providers fetch failed: ${e.message}")
                withContext(Dispatchers.Main) { _websearchProvidersLoadFailed.value = true }
                null
            }
            withContext(Dispatchers.Main) {
                if (rows != null) _websearchProviders.value = rows
                _websearchProvidersLoading.value = false
            }
        }
    }

    /**
     * `POST /api/session/{id}/compact` (`session.compact`) — durably admit a
     * compaction; it steers by default and runs at the next step boundary.
     * [messageID]/[delivery] are the route's optional body fields; upstream's
     * palette command sends neither (`use-session-commands.tsx:236`).
     */
    fun compactSession(
        messageID: String? = null,
        delivery: String? = null,
        onResult: (Boolean) -> Unit = {},
    ) {
        val api = v2Api() ?: return
        val id = sessionID ?: return
        scope.launch(Dispatchers.IO) {
            val ok = try {
                api.compact(id, messageID, delivery)
                true
            } catch (e: Exception) {
                Log.w(TAG, "compact failed: ${e.message}")
                false
            }
            withContext(Dispatchers.Main) { onResult(ok) }
        }
    }

    /**
     * Snapshot-companion read of the same route that feeds [activeContextTokens]
     * and [activeContextRows] (see their docs). Silent on failure — the screen
     * keeps the live-transcript fallback until the next snapshot.
     *
     * Public because the context tab's 「刷新」 is exactly this read on demand: the
     * review panel's refresh used to evict the review location and re-probe the
     * diff while leaving these payloads alone, so on the context tab the button
     * visibly did nothing (v17-23).
     */
    fun refreshActiveContext() {
        if (_sessionMissing.value) return
        val api = v2Api() ?: return
        val id = sessionID ?: return
        scope.launch {
            val rows = try {
                withContext(Dispatchers.IO) { api.context(id).map { it.raw } }
            } catch (e: Exception) {
                Log.w(TAG, "active context fetch failed: ${e.message}")
                null
            }
            // Switched sessions while the read was in flight: the rows belong to
            // `id`, and adopting them would feed the new session's tab (and ring)
            // the old session's context.
            if (sessionID != id || rows == null) return@launch
            _activeContextRows.value = rows
            // Same rule the tab applies to the same rows, so the two halves of
            // the ratio can never describe different turns.
            SessionContextModel.lastAssistantTotal(rows)?.let { _activeContextTokens.value = it }
        }
    }

    /**
     * `POST /api/experimental/session/{id}/wait` — blocks until the session's
     * agent loop is idle ("Wait for a session agent loop to become idle",
     * `experimental.session.wait`). The wait/unread seam: a caller that must
     * observe a settled session (e.g. before marking it viewed) awaits this
     * first. Call from a background scope — it parks.
     */
    fun waitSession(onResult: (Boolean) -> Unit = {}) {
        val id = sessionID ?: return
        scope.launch(Dispatchers.IO) {
            val ok = awaitSessionIdle(id)
            withContext(Dispatchers.Main) { onResult(ok) }
        }
    }

    /**
     * [waitSession]'s body, shared with [ackSessionViewed] — which has to park
     * *inside* its own sequence, since the row is read after the settle, not
     * before it. `false` = the wait could not be issued (no v2 API yet) or
     * failed; the caller acks anyway.
     */
    private suspend fun awaitSessionIdle(id: String): Boolean {
        val api = v2Api() ?: return false
        return try {
            api.wait(id)
            true
        } catch (e: Exception) {
            Log.w(TAG, "session wait failed: ${e.message}")
            false
        }
    }

    // ------------------------------------------------------------- pty (v2)
    //
    // `V2Api.pty*` exposed for the terminal panel. `ui/terminal/share/PtyApi.kt`
    // still builds its own HTTP today (owned elsewhere) — a future port swaps
    // its calls onto these without new wire code. TODO(ui): terminal panel.

    fun listPtys(onResult: (List<V2Api.PtyInfo>?) -> Unit) {
        val api = v2Api() ?: return
        scope.launch(Dispatchers.IO) {
            val rows = try {
                api.ptyList(directory)
            } catch (e: Exception) {
                Log.w(TAG, "pty list failed: ${e.message}")
                null
            }
            withContext(Dispatchers.Main) { onResult(rows) }
        }
    }

    fun createPty(
        command: String? = null,
        args: List<String> = emptyList(),
        cwd: String? = null,
        title: String? = null,
        env: Map<String, String> = emptyMap(),
        onResult: (V2Api.PtyInfo?) -> Unit = {},
    ) {
        val api = v2Api() ?: return
        scope.launch(Dispatchers.IO) {
            val row = try {
                // v50: `location[directory]` alone is not the shell's `pwd` —
                // with no `cwd` in the body the server falls back to its own
                // workspace. Default it to the session's **current** directory
                // ([directory], refreshed from `GET /session/:id` after a move)
                // so a new PTY lands where the session lives.
                api.ptyCreate(command, args, cwd?.takeIf { it.isNotBlank() } ?: directory, title, env, directory)
            } catch (e: Exception) {
                Log.w(TAG, "pty create failed: ${e.message}")
                null
            }
            withContext(Dispatchers.Main) { onResult(row) }
        }
    }

    fun getPty(ptyID: String, onResult: (V2Api.PtyInfo?) -> Unit) {
        val api = v2Api() ?: return
        scope.launch(Dispatchers.IO) {
            val row = try {
                api.ptyGet(ptyID, directory)
            } catch (e: Exception) {
                Log.w(TAG, "pty get failed: ${e.message}")
                null
            }
            withContext(Dispatchers.Main) { onResult(row) }
        }
    }

    fun updatePty(
        ptyID: String,
        title: String? = null,
        cols: Int? = null,
        rows: Int? = null,
        onResult: (V2Api.PtyInfo?) -> Unit = {},
    ) {
        val api = v2Api() ?: return
        scope.launch(Dispatchers.IO) {
            val row = try {
                api.ptyUpdate(ptyID, title, cols, rows, directory)
            } catch (e: Exception) {
                Log.w(TAG, "pty update failed: ${e.message}")
                null
            }
            withContext(Dispatchers.Main) { onResult(row) }
        }
    }

    fun removePty(ptyID: String, onResult: (Boolean) -> Unit = {}) {
        val api = v2Api() ?: return
        scope.launch(Dispatchers.IO) {
            val ok = try {
                api.ptyRemove(ptyID, directory)
                true
            } catch (e: Exception) {
                Log.w(TAG, "pty remove failed: ${e.message}")
                false
            }
            withContext(Dispatchers.Main) { onResult(ok) }
        }
    }

    fun ptyConnectTicket(ptyID: String, onResult: (V2Api.PtyTicket?) -> Unit) {
        val api = v2Api() ?: return
        scope.launch(Dispatchers.IO) {
            val ticket = try {
                api.ptyConnectTicket(ptyID, directory)
            } catch (e: Exception) {
                Log.w(TAG, "pty ticket failed: ${e.message}")
                null
            }
            withContext(Dispatchers.Main) { onResult(ticket) }
        }
    }

    /**
     * The PTY WebSocket upgrade request (`/api/pty/{id}/connect`), already
     * carrying Basic auth (and `ticket`/`cursor` when given). Pure URL work —
     * safe on the main thread; the caller owns the socket.
     */
    fun ptySocketRequest(ptyID: String, ticket: String? = null, cursor: Long? = null): Request? {
        val api = v2Api() ?: return null
        return try {
            api.ptySocketRequest(ptyID, ticket, cursor, directory)
        } catch (e: Exception) {
            Log.w(TAG, "pty socket request failed: ${e.message}")
            null
        }
    }

    // -------------------------------------------------- permissions (v2 reads)
    //
    // TODO(ui): settings/permissions panel — the 「始终允许」 saved-rules list
    // (`savedPermissions` / `removeSavedPermission`) and the ask helpers below.

    /** `GET /api/permission/saved` — the 「始终允许」 rules (`PermissionSaved.Info`). */
    fun listSavedPermissions(onResult: (List<V2Api.SavedPermission>?) -> Unit) {
        val api = v2Api() ?: return
        scope.launch(Dispatchers.IO) {
            val rows = try {
                api.savedPermissions()
            } catch (e: Exception) {
                Log.w(TAG, "saved permissions fetch failed: ${e.message}")
                null
            }
            withContext(Dispatchers.Main) { onResult(rows) }
        }
    }

    /** `DELETE /api/permission/saved/{id}` — drop one saved rule. */
    fun removeSavedPermission(id: String, onResult: (Boolean) -> Unit = {}) {
        val api = v2Api() ?: return
        scope.launch(Dispatchers.IO) {
            val ok = try {
                api.removeSavedPermission(id)
                true
            } catch (e: Exception) {
                Log.w(TAG, "saved permission delete failed: ${e.message}")
                false
            }
            withContext(Dispatchers.Main) { onResult(ok) }
        }
    }

    /** `POST /api/session/{id}/permission` → `{id, effect}` (self-created asks answer `effect:allow`). */
    fun createPermission(
        action: String,
        resources: List<String>,
        id: String? = null,
        save: List<String> = emptyList(),
        onResult: (JSONObject?) -> Unit = {},
    ) {
        val api = v2Api() ?: return
        val sid = sessionID ?: return
        scope.launch(Dispatchers.IO) {
            val row = try {
                api.createPermission(sid, action, resources, id, save)
            } catch (e: Exception) {
                Log.w(TAG, "permission create failed: ${e.message}")
                null
            }
            withContext(Dispatchers.Main) { onResult(row) }
        }
    }

    /** `GET /api/permission/request` — location-wide pending asks. */
    fun pendingPermissions(onResult: (List<V2Api.PermissionRequest>?) -> Unit) {
        val api = v2Api() ?: return
        scope.launch(Dispatchers.IO) {
            val rows = try {
                api.pendingPermissions(directory)
            } catch (e: Exception) {
                Log.w(TAG, "pending permissions fetch failed: ${e.message}")
                null
            }
            withContext(Dispatchers.Main) { onResult(rows) }
        }
    }

    /** `GET /api/session/{id}/permission/{requestID}` — one ask. */
    fun permission(requestID: String, onResult: (V2Api.PermissionRequest?) -> Unit) {
        val api = v2Api() ?: return
        val sid = sessionID ?: return
        scope.launch(Dispatchers.IO) {
            val row = try {
                api.permission(sid, requestID)
            } catch (e: Exception) {
                Log.w(TAG, "permission fetch failed: ${e.message}")
                null
            }
            withContext(Dispatchers.Main) { onResult(row) }
        }
    }

    // ------------------------------------------------------- forms (v2 reads)
    //
    // The dock already consumes the session-scoped list through
    // [V2Transport.fetchForms]; these are the location-wide / single-form /
    // create counterparts. TODO(ui): form management entry points.

    /** `GET /api/form` — location-wide pending forms. */
    fun pendingForms(onResult: (List<V2Api.FormInfo>?) -> Unit) {
        val api = v2Api() ?: return
        scope.launch(Dispatchers.IO) {
            val rows = try {
                api.pendingForms(directory)
            } catch (e: Exception) {
                Log.w(TAG, "pending forms fetch failed: ${e.message}")
                null
            }
            withContext(Dispatchers.Main) { onResult(rows) }
        }
    }

    /** `POST /api/session/{id}/form` body `{title, fields[, id]}` → the created form. */
    fun createForm(
        title: String,
        fields: JSONArray,
        id: String? = null,
        onResult: (V2Api.FormInfo?) -> Unit = {},
    ) {
        val api = v2Api() ?: return
        val sid = sessionID ?: return
        scope.launch(Dispatchers.IO) {
            val row = try {
                api.createForm(sid, title, fields, id)
            } catch (e: Exception) {
                Log.w(TAG, "form create failed: ${e.message}")
                null
            }
            withContext(Dispatchers.Main) { onResult(row) }
        }
    }

    /** `GET /api/session/{id}/form/{formID}` — one form and its current state. */
    fun form(formID: String, onResult: (V2Api.FormInfo?) -> Unit) {
        val api = v2Api() ?: return
        val sid = sessionID ?: return
        scope.launch(Dispatchers.IO) {
            val row = try {
                api.form(sid, formID)
            } catch (e: Exception) {
                Log.w(TAG, "form fetch failed: ${e.message}")
                null
            }
            withContext(Dispatchers.Main) { onResult(row) }
        }
    }

    // -------------------------------------------------------- misc v2 reads

    /** `GET /api/reference` — raw reference rows (element shape UNVERIFIED; measured `[]`). */
    fun references(onResult: (List<JSONObject>?) -> Unit) {
        val api = v2Api() ?: return
        scope.launch(Dispatchers.IO) {
            val rows = try {
                api.references(directory)
            } catch (e: Exception) {
                Log.w(TAG, "references fetch failed: ${e.message}")
                null
            }
            withContext(Dispatchers.Main) { onResult(rows) }
        }
    }

    /** `GET /api/vcs/branches` — branch names (review panel / move picker). */
    fun vcsBranches(
        search: String? = null,
        limit: Int? = null,
        onResult: (List<String>?) -> Unit = {},
    ) {
        val api = v2Api() ?: return
        scope.launch(Dispatchers.IO) {
            val rows = try {
                api.vcsBranches(search, limit, directory)
            } catch (e: Exception) {
                Log.w(TAG, "vcs branches fetch failed: ${e.message}")
                null
            }
            withContext(Dispatchers.Main) { onResult(rows) }
        }
    }

    /** `GET /api/vcs/status` — raw status rows (shape owned by the review panel). */
    fun vcsStatus(onResult: (List<JSONObject>?) -> Unit) {
        val api = v2Api() ?: return
        scope.launch(Dispatchers.IO) {
            val rows = try {
                api.vcsStatus(directory)
            } catch (e: Exception) {
                Log.w(TAG, "vcs status fetch failed: ${e.message}")
                null
            }
            withContext(Dispatchers.Main) { onResult(rows) }
        }
    }

    /** `GET /api/provider/{providerID}` — one provider row. */
    fun provider(providerID: String, onResult: (V2Api.ProviderInfo?) -> Unit) {
        val api = v2Api() ?: return
        scope.launch(Dispatchers.IO) {
            val row = try {
                api.provider(providerID)
            } catch (e: Exception) {
                Log.w(TAG, "provider fetch failed: ${e.message}")
                null
            }
            withContext(Dispatchers.Main) { onResult(row) }
        }
    }

    /** `GET /api/session` page (`cursor`/`order`/`search`) — the paged list [listSessions] flattens. */
    fun pageSessions(
        limit: Int? = null,
        order: String? = null,
        search: String? = null,
        cursor: String? = null,
        onResult: (V2Api.Page<V2Api.SessionInfo>?) -> Unit = {},
    ) {
        val api = v2Api() ?: return
        scope.launch(Dispatchers.IO) {
            val page = try {
                api.sessions(limit, order, search, cursor)
            } catch (e: Exception) {
                Log.w(TAG, "session page fetch failed: ${e.message}")
                null
            }
            withContext(Dispatchers.Main) { onResult(page) }
        }
    }

    /**
     * Liveness only — never the generation probe: `GET /api/info` on 2.0.11
     * (`GET /api/health` answers `404` there; a 1.18.x server is the mirror
     * image and the client falls back to it — see [V2Api.health]).
     */
    fun health(onResult: (V2Api.Health?) -> Unit) {
        val api = v2Api() ?: return
        scope.launch(Dispatchers.IO) {
            val row = try {
                api.health()
            } catch (e: Exception) {
                Log.w(TAG, "health fetch failed: ${e.message}")
                null
            }
            withContext(Dispatchers.Main) { onResult(row) }
        }
    }

    // ------------------------------------- session control (P1 `session.*` seams)

    /** `POST /api/session/{id}/background` — background blocking tools; idle is a no-op. */
    fun backgroundSessionTools(onResult: (Boolean) -> Unit = {}) {
        val transport = requireTransport() ?: return
        val id = sessionID ?: return
        scope.launch(Dispatchers.IO) {
            val ok = try {
                transport.backgroundSession(id)
                true
            } catch (e: Exception) {
                Log.w(TAG, "session background failed: ${e.message}")
                false
            }
            withContext(Dispatchers.Main) { onResult(ok) }
        }
    }

    /** `PUT /api/session/{id}/environment` body `{variables}` — replace the session shell env. */
    fun setSessionEnvironment(
        variables: Map<String, String>,
        onResult: (Boolean) -> Unit = {},
    ) {
        val transport = requireTransport() ?: return
        val id = sessionID ?: return
        scope.launch(Dispatchers.IO) {
            val ok = try {
                transport.setSessionEnvironment(id, variables)
                true
            } catch (e: Exception) {
                Log.w(TAG, "session environment failed: ${e.message}")
                false
            }
            withContext(Dispatchers.Main) { onResult(ok) }
        }
    }

    /**
     * `POST /api/session/{id}/generate` body `{prompt}` → transient text
     * (history untouched). The `/btw` side-question path.
     *
     * Device v14: the route carries **no model**, so a session whose `model` is
     * null answered 503 `Missing API key` and the panel showed a bare failure.
     * The composer's pick is therefore pinned on the session first
     * ([SessionModelSync.plan] → [SessionTransport.generateFromContext]); when
     * nothing names a model at all the call is refused locally and [onResult]
     * gets `(null, NO_MODEL)` — a visible, classified error instead of a doomed
     * round trip. A transport failure reports `(null, REQUEST_FAILED)`.
     */
    fun generateFromContext(prompt: String, onResult: (String?, GenerateFailure?) -> Unit) {
        val transport = requireTransport() ?: return
        val id = sessionID ?: return
        scope.launch {
            var failure: GenerateFailure? = null
            val text = try {
                when (val plan = resolveModelPlan(transport, id)) {
                    is SessionModelSync.Plan.PinFirst ->
                        withContext(Dispatchers.IO) { transport.generateFromContext(id, prompt, plan.pin) }

                    // A model already answers for this session: no write, and
                    // the session's own selection stays untouched.
                    SessionModelSync.Plan.SessionModel ->
                        withContext(Dispatchers.IO) { transport.generateFromContext(id, prompt, null) }

                    // Nothing to run under — the server would answer 503. The
                    // reason rides back to the caller (the panel's error state)
                    // and the session error card carries the same line, so the
                    // failure is never silent. The coroutine is already back on
                    // Main here (`scope` is `Main.immediate`).
                    SessionModelSync.Plan.NoModel -> {
                        Log.w(TAG, "session generate refused: no model for $id")
                        if (sessionID == id) {
                            _state.value = ChatStore.reduce(
                                _state.value,
                                ChatStore.Event.RunStatusChanged(
                                    ChatStore.RunStatus.ERROR,
                                    SessionModelSync.NO_MODEL_MESSAGE,
                                ),
                            )
                        }
                        failure = GenerateFailure.NO_MODEL
                        null
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "session generate failed: ${e.message}")
                failure = GenerateFailure.REQUEST_FAILED
                null
            }
            onResult(text, failure)
        }
    }

    /**
     * Which model the session-scoped call must run under
     * ([SessionModelSync.plan]). The composer's explicit pick is free; the
     * session's own `model` costs one `GET /api/session/{id}`, so it is read
     * only when neither the pick nor a known server default names one.
     */
    private suspend fun resolveModelPlan(
        transport: SessionTransport,
        id: String,
    ): SessionModelSync.Plan {
        val selected = selectedModelPin()
        val serverDefault = serverDefaultModel?.let { SessionModelSync.Pin(it.first, it.second) }
        val row = if (selected == null && serverDefault == null) {
            try {
                withContext(Dispatchers.IO) { transport.fetchSession(id) }
            } catch (e: Exception) {
                Log.w(TAG, "session model read failed: ${e.message}")
                null
            }
        } else {
            null
        }
        return SessionModelSync.plan(
            selected = selected,
            serverDefault = serverDefault,
            sessionProviderID = row?.providerID,
            sessionModelID = row?.modelID,
        )
    }

    /**
     * In-flight 「移动到…」 submit, if any: the session it targeted, the
     * optimistic timeline row it inserted ([buildMoveOptimisticMessage]) and
     * when it was submitted. The row is transient by construction — the
     * server's own `session.moved` event (or a snapshot carrying its durable
     * twin) retires it, a TTL retires it when neither arrives, and a failed
     * submit rolls it back — so a move can never leave the permanent orphan
     * row v46 tD measured (UI 3 rows vs 2 durable).
     */
    private var pendingMove: PendingMove? = null

    /** Drops the optimistic row of [submitted] if it is still the tracked one. */
    private suspend fun retirePendingMove(submitted: PendingMove) {
        if (pendingMove != submitted) return
        pendingMove = null
        if (sessionID == submitted.sessionID) {
            applyEvent(ChatStore.Event.MessageRemoved(submitted.rowID))
        }
    }

    /**
     * `POST /api/session/{id}/move` body `{directory, delivery?}` — move to another project directory.
     *
     * The promise always settles: the old shape sampled [requireTransport] at
     * tap time and silently returned while the probe was still in flight, so
     * the submit neither sent nor reported — the v46 「移动到…」 hang (the
     * same bug class as the create-session hang [createSession] documents).
     * The transport is now acquired inside the coroutine through
     * [awaitSessionTransport], and every path without a request still answers
     * `onResult(false)` so the caller shows the failure instead of hanging.
     *
     * The submit inserts an optimistic `location-switched` row first (tracked
     * on [pendingMove], rendering with the project name immediately);
     * success lets the server's event / snapshot retire it, failure rolls it
     * back — a failed move leaves no row behind.
     */
    fun moveSessionTo(
        directory: String,
        delivery: String? = null,
        onResult: (Boolean) -> Unit = {},
    ) {
        val id = sessionID
        if (id == null) {
            scope.launch { onResult(false) }
            return
        }
        scope.launch {
            val transport = awaitSessionTransport()
            if (sessionID != id || transport == null) {
                onResult(false)
                return@launch
            }
            // A superseded submit's row must not outlive it: retire whatever
            // the previous tap left before tracking the new one (the host's
            // busy guard makes this nearly unreachable — belt and braces).
            pendingMove?.let { retirePendingMove(it) }
            val submitted = PendingMove(
                sessionID = id,
                rowID = "pending-move-" + UUID.randomUUID().toString(),
                startedAt = System.currentTimeMillis(),
            )
            pendingMove = submitted
            _state.value = ChatStore.reduce(
                _state.value,
                ChatStore.Event.MessageUpdated(
                    buildMoveOptimisticMessage(submitted.rowID, directory, submitted.startedAt),
                ),
            )
            // Backstop: an event missed on a reconnecting stream must not park
            // the optimistic row forever — the TTL retires it (the durable
            // twin, if any, still arrives through the snapshot either way).
            scope.launch {
                delay(MOVE_OPTIMISTIC_TTL_MS)
                retirePendingMove(submitted)
            }
            val ok = try {
                withContext(Dispatchers.IO) { transport.moveSession(id, directory, delivery) }
                true
            } catch (e: Exception) {
                Log.w(TAG, "session move failed: ${e.message}")
                false
            }
            if (pendingMove == submitted) {
                if (ok && sessionID == id) {
                    // F-4: a move re-scopes every REST read to the new
                    // directory — re-pull the snapshot on success, or the
                    // transcript keeps answering from the old one until the
                    // next event settles. The optimistic row stays until the
                    // event / confirming snapshot / TTL retires it.
                    refreshSnapshotThrottled()
                } else {
                    // Failure rolls the optimistic row back: a failed move
                    // leaves no `Location changed` behind (v46 tD).
                    retirePendingMove(submitted)
                }
            }
            onResult(ok)
        }
    }

    /**
     * `POST /api/session/{id}/synthetic` — durably admit synthetic input;
     * `resume=false` admits without running. Returns the inbox row's id.
     */
    fun sendSyntheticMessage(
        text: String,
        description: String? = null,
        delivery: String? = null,
        resume: Boolean? = null,
        onResult: (String?) -> Unit = {},
    ) {
        val transport = requireTransport() ?: return
        val id = sessionID ?: return
        scope.launch(Dispatchers.IO) {
            val inboxID = try {
                transport.sendSynthetic(id, text, description, null, delivery, resume, null)
            } catch (e: Exception) {
                Log.w(TAG, "session synthetic failed: ${e.message}")
                null
            }
            withContext(Dispatchers.Main) { onResult(inboxID) }
        }
    }

    /**
     * `POST /api/session/{id}/view` body `{idle: <epoch millis>}` — the unread
     * acknowledgement of one idle transition. Pair with [waitSession] when the
     * caller must observe idle first.
     */
    fun markSessionViewed(idle: Long, onResult: (Boolean) -> Unit = {}) {
        val transport = requireTransport() ?: return
        val id = sessionID ?: return
        scope.launch(Dispatchers.IO) {
            val ok = try {
                transport.markSessionViewed(id, idle)
                true
            } catch (e: Exception) {
                Log.w(TAG, "session view failed: ${e.message}")
                false
            }
            withContext(Dispatchers.Main) { onResult(ok) }
        }
    }

    /**
     * Unread ack at the two moments the viewer observes an idle boundary —
     * session open and the idle transition. The literal upstream pairing: wait
     * for the agent loop to settle first (`POST /api/experimental/session/{id}/
     * wait`), then `POST …/view` with the boundary this viewer saw
     * ([markSessionViewed], `session.view` — "Mark the idle transition observed
     * by the viewer as viewed"). [markNotificationsViewed] clears the in-app feed
     * in the same breath — upstream's `MarkSessionNotificationsViewed`
     * (`session/route.tsx:186-195`). Parked while the generation probe is
     * still in flight ([pendingViewAck], flushed from [setGeneration]); a
     * session switch mid-wait drops the ack. The wait is **best-effort**: one
     * that cannot be issued (no v2 API) or fails does not skip the ack —
     * upstream never gates `session.view` on it, and a skipped ack leaves the
     * row unread for every other client.
     *
     * The body is the **server's** idle watermark ([SessionUnread.idle]) — the TUI
     * posts exactly that (`session-tabs.tsx:210-238`
     * `client.api.session.view({sessionID, idle})`, off the store's *current*
     * `time.idle`). The server stores `time_viewed = max(idle, …)` and decides
     * unread by `time.idle > time.viewed` (`core/src/session/projector.ts:583-593`),
     * so a *client* clock ahead of the server's would push the watermark past
     * every later idle transition and the row could never light up again. The
     * local clock stays as the fallback for a session no read has described yet
     * (a cold deep link).
     *
     * The watermark has to be the row's **current** idle, not the one the last
     * list read carried: that read happened before this turn settled, so its
     * `time.idle` is structurally one terminal transition behind the boundary
     * being acknowledged here, and posting it leaves `time.idle > time.viewed`
     * true — the row re-lights on every later read and the dot never clears
     * until a read lands that the ack's stale value happens to cover (the v17
     * `switch_perm` report: an auto-approved turn's dot survived 60 s+ and only
     * a restart cleared it). Upstream reads a store the event stream keeps live;
     * this shell has no per-session time store, so it asks the row
     * ([currentIdle], `GET /api/session/{id}`) — the same `idle` value upstream
     * posts, obtained the same way the dot's own read obtains it. The read
     * belongs **after** the settle the wait resolves on ([awaitSessionIdle]):
     * the row's `time.idle` is committed by the same terminal event the wait
     * returns on, so a read issued ahead of it hands back the previous
     * transition — the stale watermark this whole path exists to avoid.
     *
     * Deliberately scoped to the session that is **on screen**: the ack means "the
     * viewer observed this", so a boundary the user never looked at (the tracked
     * session settling while they are on Home) must stay unread — otherwise the
     * idle path would acknowledge away the very dot this ack's ledger feeds.
     */
    fun ackSessionViewed() {
        val id = sessionID ?: return
        if (visibleSessionID != id) return
        markNotificationsViewed(id)
        // The row's own watermark is mirrored locally in the same breath: the
        // server stores `time_viewed = max(idle, …)` (monotone), so this is
        // exactly the value a later list read will report — and without it the
        // dot would linger on the row the user just opened until some *other*
        // read happened to land after the POST (the session screen re-reads its
        // list immediately on entry, i.e. before this round-trip).
        val known = _sessionUnread.value[id]
        val knownIdle = known?.idle
        if (knownIdle != null) {
            _sessionUnread.value = _sessionUnread.value + (id to known.ackedAt(knownIdle))
        }
        if (transport() == null) {
            pendingViewAck = true
            return
        }
        scope.launch {
            // Park until the loop is idle *before* reading the row: the boundary
            // being acknowledged is the settle this wait resolves on, so a read
            // taken ahead of it is one terminal transition behind and posting
            // that value leaves `time.idle > time.viewed` true.
            awaitSessionIdle(id)
            val fresh = currentIdle(id) ?: knownIdle
            // A switch mid-wait (or mid-read) drops the ack: the boundary
            // belongs to a session the viewer is no longer on.
            if (sessionID != id || visibleSessionID != id) return@launch
            if (fresh != null) {
                val prev = _sessionUnread.value[id] ?: SessionUnread()
                _sessionUnread.value = _sessionUnread.value + (id to prev.ackedAt(fresh))
            }
            val idle = fresh ?: System.currentTimeMillis()
            markSessionViewed(idle)
        }
    }

    /**
     * The session row's **current** `time.idle` (`GET /api/session/{id}`), or
     * `null` when the read fails or the server is not v2 — [ackSessionViewed]
     * then falls back to the value its own last list read carried. Read-only and
     * bounded to the ack, which only fires on a session open or an idle
     * transition.
     */
    private suspend fun currentIdle(id: String): Long? {
        val api = v2Api() ?: return null
        return try {
            withContext(Dispatchers.IO) { api.session(id).toUnread().idle }
        } catch (e: Exception) {
            Log.w(TAG, "session idle read failed: ${e.message}")
            null
        }
    }

    /** Open-time ack held until the generation probe installs the v2 transport. */
    private var pendingViewAck = false

    // ------------------------------------------ summary background-work list

    private val _backgroundTasks =
        MutableStateFlow<List<SessionSummaryModel.BackgroundTask>>(emptyList())

    /**
     * The summary panel's background-work rows — upstream
     * `createSessionBackground().tasks` (`session/requests/background.ts:85-115`,
     * v2 head `8ce629b`), derived by [SessionBackgroundModel] from the raw
     * message rows' shell/subagent tool parts plus child sessions, `GET
     * /api/shell` and `GET /api/session/active`. Empty until a refresh lands
     * (the panel renders upstream's empty copy).
     */
    val backgroundTasks: StateFlow<List<SessionSummaryModel.BackgroundTask>> =
        _backgroundTasks.asStateFlow()

    /**
     * Re-derive [backgroundTasks] from the four upstream sources in one IO
     * burst. v2-only and silent on failure (the panel keeps the previous list).
     * Triggered on session open, idle transitions, `shell.*` signals and when
     * the summary panel opens — event-driven, never polled.
     */
    fun refreshBackgroundTasks() {
        val api = v2Api() ?: return
        val id = sessionID ?: return
        scope.launch(Dispatchers.IO) {
            val tasks = try {
                val messages = api.messagesRaw(id)
                val children = api.sessions(limit = BACKGROUND_SESSION_LIMIT, directoryScoped = false)
                    .data.map { SessionBackgroundModel.Child(it.id, it.parentID, it.title) }
                val shells = api.shells(directory).mapNotNull { row ->
                    val shellID = optStringOrNull(row, "id") ?: return@mapNotNull null
                    SessionBackgroundModel.Shell(
                        id = shellID,
                        command = optStringOrNull(row, "command"),
                        sessionID = optStringOrNull(row.optJSONObject("metadata"), "sessionID"),
                        status = optStringOrNull(row, "status"),
                    )
                }
                // `GET /api/session/active` absent entries are idle
                // (`background.ts:91`); `running`/`busy`/`retry` are the live
                // set the snapshot liveness probe already trusts.
                val active = api.activeSessions().mapValues { (_, type) ->
                    if (type == "running" || type == "busy" || type == "retry") "running" else "idle"
                }
                SessionBackgroundModel.tasks(id, messages, children, shells, active)
            } catch (e: Exception) {
                Log.w(TAG, "background tasks fetch failed: ${e.message}")
                null
            }
            withContext(Dispatchers.Main) {
                if (tasks != null && sessionID == id) _backgroundTasks.value = tasks
            }
        }
    }

    /** [V2Api] handle for the v2-only extras above (same construction as [transportApi]). */
    private fun v2Api(): V2Api? {
        val transport = transportRef.get() as? V2Transport ?: return null
        return transportApi(transport)
    }

    private fun refreshSnapshot() {
        if (_sessionMissing.value) return
        val id = sessionID ?: return
        val transport = requireTransport() ?: return
        lastSnapshotAt = System.currentTimeMillis()
        refreshPermissions()
        refreshForms()
        refreshActiveContext()
        scope.launch {
            try {
                // Recent window only (SNAPSHOT_MESSAGE_LIMIT): the server answers
                // with the newest N messages and the store merges by upsert, so
                // the cap bounds the transfer/parse cost without losing history
                // already on screen.
                //
                // Idempotent, so a dropped socket retries ([readTransport]) —
                // this is the read that heals a timeline whose stream died while
                // the connection was down.
                val messages = withContext(Dispatchers.IO) {
                    readTransport { transport.snapshotMessages(id, SNAPSHOT_MESSAGE_LIMIT) }
                }
                // Switched sessions while the fetch was in flight: the snapshot
                // belongs to `id`, and `ChatStore.reduce` gates Snapshot by no
                // session — applying it would merge the old transcript into the
                // new session's message stream for good.
                if (sessionID != id) return@launch
                // Chip label fallback (upstream `selection.tsx:234-249`: scope →
                // session → agent → configured → recent): the configured default
                // may be absent — then the latest assistant turn's own model is
                // the truth the server is actually running, so the chip shows it
                // instead of the generic「模型」placeholder. It lands in
                // [serverDefaultModel] (the last *derived* source) and only when
                // neither this session's pick nor a configured default names one;
                // the session's own model below outranks both.
                if (modelOverride == null && serverDefaultModel == null) {
                    messages.lastOrNull { it.role == "assistant" && it.model != null }
                        ?.let { latest ->
                            OpenCodeClient.parseModelRef(latest.model)?.let { ref ->
                                serverDefaultModel = ref
                                applyEffectiveModel()
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
                    if (info.hasTitle && !info.title.isNullOrBlank()) {
                        publishCurrentTitle(id, info.title)
                    }
                    if (!info.directory.isNullOrBlank()) {
                        directory = info.directory
                        _currentDirectory.value = info.directory
                    }
                    // Session cost; null clears a stale figure when the
                    // server stops reporting one.
                    _sessionCost.value = info.cost
                    // The session's own model ref — the ring's second source
                    // (`contextLimitFrom`) and the composer's, ahead of the
                    // server default ([effectiveModelRef]). Written
                    // unconditionally: the row is the server's answer for *this*
                    // session, so a server that stopped reporting one must clear
                    // the stale ref.
                    _sessionModel.value = sessionModelRef(info)
                    // v72: the session's durable effort rides the same row. A
                    // cold restart empties [sessionVariants], and without this
                    // the chip lost the `· max` suffix while the server kept
                    // running the variant. Only the row moves the slot — a
                    // pick made on this device still outranks it, never the
                    // other way round (upstream `selected()`), and v73's local
                    // durable copy (`saved.session[id]`) is that pick's, so a
                    // row naming *no* effort leaves the slot alone.
                    val rowVariant = info.variant?.takeIf {
                        it.isNotBlank() && it != ModelPickerItems.VARIANT_DEFAULT
                    }
                    if (rowVariant != null && rowVariant != sessionVariants[id]) {
                        sessionVariants[id] = rowVariant
                        _selectedVariant.value = rowVariant
                        settingsStore?.setSessionVariant(id, rowVariant)
                    }
                    applyEffectiveModel()
                    // Revert boundary: the session-level 「撤销」 state lives on
                    // the server (`Session.Info.revert`), and this read is the
                    // only place a *reopened* session learns it — without it a
                    // session reverted in an earlier visit rendered its hidden
                    // tail again (`SessionActionsModel.hiddenMessageIDs`).
                    // Unconditional like the two above: an answer with no
                    // `revert` clears a boundary the server no longer holds.
                    _revertMessageID.value = info.revertMessageID
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
                if (sessionID != id) return@launch
                if (live == "running" || live == "busy" || live == "retry") {
                    lastPartEventAt = System.currentTimeMillis()
                    // The server's live set is the second authority that *learns*
                    // a status, so the notification trigger's mirror hears it too
                    // ([turnRunStatus]): a session that is running here is one the
                    // client has watched run, whatever the stream managed to
                    // deliver.
                    turnRunStatus.running(id)
                    // …but it may only *learn* it, never resurrect a turn this
                    // client has already stopped: the same map that answers
                    // `running` here kept answering it a minute past a Stop the
                    // server had confirmed, and re-asserting RUNNING from it left
                    // the composer on Stop over an `Interrupted` transcript for
                    // good ([StoppedTurn]).
                    if (_state.value.runStatus != ChatStore.RunStatus.RUNNING &&
                        stoppedTurn.allowsLiveAssert(id)
                    ) {
                        applyEvent(ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.RUNNING))
                    }
                } else if (_state.value.runStatus == ChatStore.RunStatus.RUNNING) {
                    delay(IDLE_GRACE_MS)
                    if (started.get() &&
                        sessionID == id &&
                        _state.value.runStatus == ChatStore.RunStatus.RUNNING &&
                        System.currentTimeMillis() - lastPartEventAt >= IDLE_GRACE_MS
                    ) {
                        // B-2: this conclusion *is* the end of the turn, and it is
                        // the only carrier left when the live frame never made it
                        // (the reason this branch exists at all). Raising here is
                        // what makes "the turn finished while the app was
                        // backgrounded" reach the system notification: the
                        // transcript heals through the snapshot either way, so
                        // without it the user is never told. `viewed`/focus decide
                        // whether it is *delivered* (`SystemNotificationPolicy`),
                        // so a user looking at the session is not disturbed, and
                        // the per-session de-bounce absorbs a frame that lands
                        // right after this.
                        //
                        // `since` is the turn's own start on the client clock
                        // (`turnRunStatus.since`, else the last moment the turn
                        // was seen alive): this branch runs while the app is in
                        // the foreground — it is triggered by the stream coming
                        // back — so a raise judged by the *current* foreground
                        // state would be suppressed by construction. Dating it
                        // from the turn is what lets it be delivered when the user
                        // left while the turn was running.
                        val since = turnRunStatus.since(id) ?: lastPartEventAt
                        Log.d(NOTIFY_TAG, "trigger reconcile-settle: session=$id since=$since")
                        raise(AppNotificationKind.AGENT, sessionID = id, since = since)
                        turnRunStatus.settled(id)
                        applyEvent(ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.IDLE))
                    }
                }
            } catch (e: Exception) {
                val failure = sessionOpenFailureOf(e)
                if (failure != null) {
                    markSessionMissing(id, failure)
                } else {
                    Log.w(TAG, "snapshot failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Permission merge (additive): pending asks are session state too,
     * so the snapshot refresh carries them. Failure is silent — the SSE
     * `permission.asked` events still drive the dock.
     */
    private fun refreshPermissions() {
        if (_sessionMissing.value) return
        val transport = requireTransport() ?: return
        val id = sessionID ?: return
        scope.launch {
            val requests = try {
                withContext(Dispatchers.IO) { transport.fetchPermissions(id) }
            } catch (e: Exception) {
                val failure = sessionOpenFailureOf(e)
                if (failure != null) {
                    markSessionMissing(id, failure)
                } else {
                    Log.w(TAG, "permission list failed: ${e.message}")
                }
                null
            } ?: return@launch
            if (sessionID != id) return@launch
            applyEvent(ChatStore.Event.PermissionSnapshot(requests))
            // The same list feeds the dot's location-wide ledger: for **this**
            // session the REST snapshot is the truth (an ask that left the list
            // was answered), so it replaces that session's entry instead of
            // unioning into it ([SessionAskLedger.permissionSnapshot]).
            _askLedger.update { it.permissionSnapshot(id, requests.map { request -> request.id }) }
            // Auto-approve sweep (upstream auto-approve.ts): the stream does
            // not replay asks made while disconnected, so every merged
            // still-pending request gets its bounded reply too.
            for (request in requests) autoApprove(request)
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
                val failure = sessionOpenFailureOf(e)
                if (failure != null) {
                    markSessionMissing(id, failure)
                } else {
                    Log.w(TAG, "form list failed: ${e.message}")
                }
                null
            } ?: return@launch
            if (sessionID != id) return@launch
            applyEvent(ChatStore.Event.FormSnapshot(forms))
            // Same rule as `refreshPermissions`: authoritative for this session.
            _askLedger.update { it.formSnapshot(id, forms.map { form -> form.id }) }
        }
    }

    /**
     * Auto-approve one ask with `"once"` (setting-gated inside
     * [AutoApprover.tryMark]). No optimistic removal: the server's
     * `permission.replied` event clears the dock. A failed POST retries after
     * a short delay, bounded by the per-request attempt budget; the setting
     * being switched off mid-flight stops further retries.
     *
     * The ledger entry the ask raised is marked viewed here. Upstream's
     * auto-approver only answers the server (`requests/auto-approve.ts`), and it
     * can afford to: its in-app ledger holds no permission row at all
     * (`shell/notifications/notification.tsx:19-31`), while this shell's feed
     * does, and an entry raised while the user was not on that session's route
     * (`viewed = false`) would otherwise stay "unseen" for the whole process —
     * the dot the v17 `switch_perm` report saw stay lit after an auto-approved
     * ask. Answering without the user is exactly the case where nothing needs
     * the user's attention, so the entry is closed here. The tab/drawer sources
     * gate the same kind on the setting as well
     * (`SessionTabIndicator.unseenCount`), so the invariant holds whichever of
     * the two lands first.
     */
    private fun autoApprove(request: OpenCodeClient.PermissionRequest) {
        if (!autoApprover.tryMark(request.id)) return
        appNotifier.markKindViewed(
            sessionID = request.sessionID.ifEmpty { sessionID.orEmpty() },
            kind = AppNotificationKind.PERMISSION,
        )
        val transport = requireTransport() ?: return
        val id = sessionID ?: return
        scope.launch {
            val ok = try {
                withContext(Dispatchers.IO) { transport.replyPermission(id, request.id, "once") }
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
        val tracked = ChatStore.eventSessionId(data) ?: return
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

            // A row write only stales the **list** (and its order). The
            // transcript is already driven by the durable message frames, and
            // `session.updated`'s v1 payload carries no transcript — so nothing
            // here re-reads messages or the catalogs.
            ChatStore.Signal.SESSION_CREATED,
            ChatStore.Signal.SESSION_DELETED,
            ChatStore.Signal.SESSION_UPDATED,
            -> _sessionsRevision.value += 1

            ChatStore.Signal.VCS_BRANCH_UPDATED -> _vcsRevision.value += 1
            ChatStore.Signal.FILE_EDITED -> _filesRevision.value += 1

            ChatStore.Signal.PTY_CREATED,
            ChatStore.Signal.PTY_UPDATED,
            ChatStore.Signal.PTY_EXITED,
            ChatStore.Signal.PTY_DELETED,
            -> _ptyRevision.value += 1

            // MCP servers settle one event at a time (`mcp-event.ts:5-26`), and
            // the list lives on a settings/chooser page that owns its own
            // fetch — a revision, never a fetch from here (a hidden page must
            // not hit `GET /api/mcp`).
            ChatStore.Signal.MCP_STATUS_CHANGED,
            ChatStore.Signal.MCP_TOOLS_CHANGED,
            ChatStore.Signal.MCP_RESOURCES_CHANGED,
            -> _mcpRevision.value += 1

            // Credential / integration / plugin moves all stale the provider,
            // model and agent catalogs (upstream reloads them from those
            // event handlers), so they share the throttled catalog re-read.
            ChatStore.Signal.CREDENTIAL_UPDATED,
            ChatStore.Signal.CREDENTIAL_SWITCHED,
            ChatStore.Signal.INTEGRATION_UPDATED,
            ChatStore.Signal.PLUGIN_UPDATED,
            ChatStore.Signal.WEBSEARCH_UPDATED,
            ChatStore.Signal.REFERENCE_UPDATED,
            ChatStore.Signal.SKILL_UPDATED,
            ChatStore.Signal.COMMAND_UPDATED,
            ChatStore.Signal.LSP_UPDATED,
            -> catalogRefresh()

            // Worktree layout is the project list's shape (`project.updated`'s
            // sibling); Home re-reads on the next collector tick.
            ChatStore.Signal.WORKTREE_UPDATED,
            ChatStore.Signal.WORKTREE_RESOLVED,
            ChatStore.Signal.WORKTREE_READY,
            ChatStore.Signal.WORKTREE_FAILED,
            -> _projectsRevision.value += 1

            // Session terminals and background shells feed the same terminal
            // surfaces as `pty.*` — bump that revision so they are never
            // silently swallowed.
            ChatStore.Signal.PERSISTENT_PTY_ADDED,
            ChatStore.Signal.PERSISTENT_PTY_REMOVED,
            ChatStore.Signal.SHELL_CREATED,
            ChatStore.Signal.SHELL_EXITED,
            ChatStore.Signal.SHELL_DELETED,
            -> {
                _ptyRevision.value += 1
                refreshBackgroundTasks()
            }

            // A location shutting down invalidates everything scoped to it.
            ChatStore.Signal.LOCATION_SHUTDOWN -> {
                catalogRefresh()
                _projectsRevision.value += 1
                _sessionsRevision.value += 1
            }

            else -> Unit
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
     *
     * "Was it running?" is [turnRunStatus], the status **this loop** has seen on
     * the wire, not `_state.value.runStatus`: the store is applied through
     * `scope.launch` (see [turnRunStatus]), so it is still on the previous turn
     * while a burst is being read — and the reconciliation path clears it before
     * a late frame arrives. Both would silently drop the turn-complete
     * notification. The answer is [TurnRunStatus.ranTurn] rather than "running":
     * a session update (the 「移动到…」 move, a rename) is a busy/idle pair on the
     * wire too, and only a run that produced a step is a turn.
     */
    private fun notifyFor(event: ChatStore.Event, routed: String?) {
        when (event) {
            is ChatStore.Event.PermissionAsked -> raise(
                AppNotificationKind.PERMISSION,
                sessionID = event.request.sessionID.ifEmpty { routed },
                detail = event.request.permission,
            )

            // A pending form is the v2 question-dock carrier — same
            // notification gate as questions.
            is ChatStore.Event.FormCreated -> raise(
                AppNotificationKind.QUESTION,
                sessionID = event.form.sessionID.ifEmpty { routed },
                detail = event.form.title,
            )

            is ChatStore.Event.RunStatusChanged -> when (event.status) {
                ChatStore.RunStatus.ERROR -> {
                    // The raise's own date, read before `settled` clears it.
                    val since = turnRunStatus.since(routed)
                    turnRunStatus.settled(routed)
                    raise(AppNotificationKind.ERROR, sessionID = routed, detail = event.error, since = since)
                }

                ChatStore.RunStatus.IDLE -> {
                    val foreign = routed != null && routed != sessionID
                    // A session *update* (a Move, a rename, a location change) is
                    // a busy → idle pair on the wire as well, so "was it running?"
                    // is not enough: the run has to have produced a step
                    // ([TurnRunStatus.ranTurn]) for this idle to be the end of a
                    // turn — v50 tD raised 「回复已完成」 for every Move of a session
                    // that had never been prompted.
                    val finished = !foreign && turnRunStatus.ranTurn(routed)
                    // Read *before* `settled`: the reference is this turn's own
                    // start, and the raise below is what needs it.
                    val since = turnRunStatus.since(routed)
                    turnRunStatus.settled(routed)
                    Log.d(
                        NOTIFY_TAG,
                        "trigger idle: routed=$routed tracked=$sessionID foreign=$foreign " +
                            "finished=$finished since=$since",
                    )
                    if (foreign || finished) {
                        raise(AppNotificationKind.AGENT, sessionID = routed, since = since)
                    }
                }

                ChatStore.RunStatus.RUNNING -> turnRunStatus.running(routed)
            }

            else -> Unit
        }
    }

    /**
     * The ask half of the dot, for **any** session on the stream
     * ([SessionAskLedger], upstream's `session.permission.list` /
     * `session.form.list`).
     *
     * Sits next to [notifyFor] because it reads the same frames and is fed for
     * the same reason: the transcript state is gated by
     * `ChatStore.acceptsSession`/`tracksSession` (one session's dock), while a
     * row's dot has to answer for a session this client is not observing. The
     * session id falls back to the frame's route ([routed]) exactly like
     * [notifyFor]'s, so a frame whose payload omits it still lands on the right
     * row.
     */
    private fun recordAsk(event: ChatStore.Event, routed: String?) {
        // `update`, not read-then-write: this runs on the SSE reader thread while
        // the REST reads below run on IO, and a lost fold is a dot that never
        // lights (or never clears).
        _askLedger.update { current ->
            when (event) {
                is ChatStore.Event.PermissionAsked ->
                    current.asked(event.request.id, event.request.sessionID.ifEmpty { routed.orEmpty() })

                is ChatStore.Event.PermissionReplied -> current.replied(event.requestID)
                is ChatStore.Event.FormCreated ->
                    current.formCreated(event.form.id, event.form.sessionID.ifEmpty { routed.orEmpty() })

                is ChatStore.Event.FormResolved -> current.formResolved(event.formID)
                else -> current
            }
        }
    }

    /**
     * One offer to [appNotifier]; the settings gate and the de-bounce live there.
     *
     * `viewed` is upstream's `viewedInCurrentSession` (`notification.tsx:221-223`),
     * i.e. "is the user *looking at* that session right now" — the route, not the
     * tracked session ([visibleSessionID]). Using the tracked id made a turn that
     * settled while the user was on Home count as seen, so nothing ever lit up
     * for it.
     *
     * On Android the route is only half the answer. Upstream evaluates the flag
     * when the event arrives and its window is either focused or not; here the
     * frame that ends a turn is routinely read **after** the user is back on that
     * session (a cached process is frozen, a dropped socket replays on reconnect,
     * and the reconciliation carrier only runs on the way back), so a route-only
     * flag would mark a turn the user never saw as "seen" and suppress both the
     * banner and the system notification — the measured v30 failure
     * (`research/v30_verify/tA_notify_ws`). [since] dates the turn on the
     * **client's** clock and `NotificationDelivery.watchedThrough` answers
     * whether the app stayed in the foreground for it; only then is the entry
     * "viewed".
     *
     * [since] is `null` for the ask kinds (a permission/form has no turn to date)
     * and for a foreign session, which keeps upstream's route-only answer there.
     *
     * `viewed` no longer keeps an entry off the in-app banner (v49 — that
     * suppression is what made the foreground 「回复已完成」 banner invisible); it
     * is the tab-dot watermark and the system-notification focus answer, which
     * `BannerPolicy`/`SystemNotificationPolicy` apply on their own.
     */
    private fun raise(
        kind: AppNotificationKind,
        sessionID: String?,
        detail: String? = null,
        since: Long? = null,
    ) {
        // v49: the server is shared, so the stream carries other devices' turns
        // too. A completion for a session this device never had open is not its
        // news — raising it put a 「回复已完成」 banner over the wrong person's
        // work (v49 tC: four banners, all for the 5554 tester's sessions). The
        // gate is here, above [AppNotifier], so the feed, the banner and the
        // system notification all agree on what belongs to this device.
        if (!sessionOwnership.owns(sessionID)) {
            Log.d(NOTIFY_TAG, "raise: skipped foreign session=$sessionID kind=$kind")
            return
        }
        val watched = since == null || (notificationDelivery?.watchedThrough(since) ?: true)
        val viewed = sessionID != null && sessionID == visibleSessionID && watched
        Log.d(
            NOTIFY_TAG,
            "raise: kind=$kind session=$sessionID since=$since watched=$watched " +
                "visible=$visibleSessionID viewed=$viewed",
        )
        appNotifier.raise(
            kind = kind,
            sessionID = sessionID,
            // Only the tracked session's title is known here; a foreign session's
            // row falls back to its id, which the banner can render as-is.
            sessionTitle = sessionID?.takeIf { it == this.sessionID }?.let { _currentTitle.value },
            detail = detail,
            viewed = viewed,
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
        // 「移动到…」 optimism: a live `location-switched` row (the server's
        // own `session.moved` event) or a snapshot carrying its durable twin
        // confirms the submit and retires the optimistic row. Anything else
        // leaves it to the TTL — and the insert itself never flows through
        // here (it reduces directly), so it cannot retire itself.
        val pending = pendingMove
        if (pending != null && sessionID == pending.sessionID) {
            val confirmed = when (event) {
                is ChatStore.Event.MessageUpdated -> eventConfirmsMove(event, pending.rowID)
                is ChatStore.Event.Snapshot -> snapshotConfirmsMove(event.messages, pending.startedAt)
                else -> false
            }
            if (confirmed) retirePendingMove(pending)
        }
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
            toolError = optStringOrNull(state, "error"),
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

    /**
     * Dismisses the usage-exceeded upsell (`state.usageUpsell`, rendered by
     * `UsageExceededDialog` in the session screen). A plain copy — not a
     * [ChatStore.Event] — so `reduce` never resurrects it: only a new
     * qualifying retry/error frame re-shows the dialog. (The VM's public
     * `state` is [ChatStore.ChatState]; that is the `State` this clears.)
     */
    fun dismissUsageUpsell() {
        if (_state.value.usageUpsell != null) {
            _state.value = _state.value.copy(usageUpsell = null)
        }
    }

    fun destroy() {
        stop()
        // Explicit, though `scope.cancel()` below covers it: the automatic
        // compaction watcher is a session-lifetime observer like the streams.
        autoCompaction.cancel()
        scope.cancel()
    }

    companion object {
        private const val TAG = "ChatViewModel"

        /**
         * The line a session-scoped action that has **no transport** reports:
         * the same sentence [`requireClient`] puts on the error card when there
         * is no client at all, and the same one
         * `R.string.error_server_notConnectedNoConfig` carries in all three
         * locales (the card's own vocabulary is the `Not connected — …` family,
         * `ui/session/timeline/ToolRenderers.kt:280-296`). Named rather than
         * repeated so the two paths can never drift apart.
         */
        private const val NOT_CONNECTED_LINE =
            "Not connected — open the Terminal tab once to load the server config."

        /**
         * The notification chain's own tag (`SystemNotifier.TAG`): the trigger
         * and its gates, `AppNotifier`'s gates and the Android half's delivery
         * gates all log under it, so `logcat -s OcNotify` prints the chain in
         * order — the whole point, because every failure mode here is silence.
         */
        private const val NOTIFY_TAG = "OcNotify"
        private const val SNAPSHOT_THROTTLE_MS = 3_000L
        private const val IDLE_GRACE_MS = 10_000L

        /**
         * How often the foreground idle watchdog wakes to ask about a turn that
         * has gone quiet. Shorter than [IDLE_GRACE_MS] would only re-check the
         * same conclusion; longer delays the unlock after a lost terminal
         * frame, which is the bug (v54 tB measured 44 s of a Stop button that
         * would not clear).
         */
        private const val IDLE_WATCHDOG_TICK_MS = 4_000L

        /**
         * The `{interrupted:false}` answer: the server took the call and
         * pre-empted nothing, because no execution owned by this process was
         * running (`protocol/src/groups/session.ts:747-748`). Distinguished from
         * a thrown failure only so the report names the right cause.
         */
        private const val STOP_NOT_INTERRUPTED = "the server reports no execution to interrupt"

        /**
         * How long the Stop's own acknowledgment may take before this client
         * stops waiting for it (`session.interrupt` → `POST
         * /api/session/{id}/interrupt`).
         *
         * The route is `coordinator.interrupt`, an `Effect.sync` that returns as
         * soon as the execution fiber has been interrupted
         * (`run-coordinator.ts:150-172`) — a healthy answer is milliseconds. So
         * a Stop that has no answer after this long is not "being accepted
         * slowly": the request is wedged, and the tap must not sit inside it
         * forever. 5 s is an order of magnitude above a healthy round trip and
         * inside [IDLE_GRACE_MS], so the *Stop* decides what happened rather
         * than the idle watchdog outliving it. Measured v78 (emulator-5554): a
         * Stop during a pending question stayed inside the 30 s REST read
         * timeout, and the composer sat on Stop for that whole window with no
         * explanation on screen.
         */
        private const val STOP_ACK_TIMEOUT_MS = 5_000L

        /**
         * The Stop that got **no answer at all** inside [STOP_ACK_TIMEOUT_MS].
         * Named apart from [STOP_NOT_INTERRUPTED] because the two say different
         * things to the reader ("we were told there was nothing to stop" vs "we
         * were never told"), and both are interpolated into the same localised
         * sentence ([ToolRenderers.SERVER_ERROR_PHRASES], entry
         * `error_server_stopUndeliveredNoAnswer`).
         */
        private const val STOP_NO_ANSWER = "the server did not answer"

        /**
         * Startup backoff base: the session list retries once after this, and
         * the generation probe backs off per round, when the first attempt
         * failed outright (`adb reverse` / backend not accepting yet — measured
         * `failed to connect to /127.0.0.1 (port 4095) … after 10000ms` while
         * `curl` from the host was already 200).
         */
        private const val STARTUP_RETRY_DELAY_MS = 1_500L

        /**
         * Extra probe rounds after the first one answered nothing outright
         * (`httpStatus == null`): the backoff grows per round
         * (`STARTUP_RETRY_DELAY_MS * (round + 1)`).
         */
        private const val PROBE_NETWORK_ATTEMPTS = 2

        /**
         * Minimum gap between [maybeReprobe] kicks: hot reads share one probe
         * instead of starting a storm.
         */
        private const val PROBE_LAZY_RETRY_MS = 10_000L

        /**
         * How long [listSessions] waits for the generation probe before
         * deciding there is no transport — bounded so a dead server cannot
         * hold Home's spinner open forever.
         */
        private const val PROBE_SETTLE_WAIT_MS = 4_000L

        /** Hard cap on paged `GET /api/session` rows pulled by [listSessions]. */
        private const val SESSION_LIST_LIMIT = 200

        /**
         * Overall bound on one [listSessions] read (both fetch passes together).
         * One pass stacks per-attempt socket timeouts × retry attempts × pages,
         * so a hung read used to hold Home's skeleton for minutes with no
         * terminal state (emulator-5554 v74: skeleton past 45 s after a
         * force-stop + relaunch, Projects fine from the local store). Expiry
         * answers the cached rows and reports [sessionsLoadFailed], which is the
         * state the error/retry UI reads — a healthy LAN read lands in ~1 s, so
         * this never hurries a real load.
         */
        private const val SESSION_LIST_TIMEOUT_MS = 20_000L

        /** How often [watchPendingDelivery] re-checks its pending admissions. */
        private const val PENDING_DELIVERY_POLL_MS = 5_000L

        /** Silent second attempt for the provider catalog (`refreshProviders`). */
        private const val PROVIDERS_RETRY_DELAY_MS = 1_500L

        /**
         * WAVE 2.4d: how many times a **short** v2 catalog answer is re-read
         * before it is taken as the truth (`refreshV2Providers` /
         * [ModelPickerItems.plausibleCatalog]). The settle delay grows per
         * attempt (`CATALOG_SETTLE_RETRY_DELAY_MS * (attempt + 1)`).
         */
        private const val CATALOG_SETTLE_ATTEMPTS = 3

        /** First settle delay of the catalog re-read above. */
        private const val CATALOG_SETTLE_RETRY_DELAY_MS = 1_500L

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
         * Session rows pulled per [refreshBackgroundTasks] re-derive: enough
         * for every live child session of the tracked one without paging.
         */
        private const val BACKGROUND_SESSION_LIMIT = 100

        /**
         * Wall-clock deadline of one review VCS read ([reviewVcsHttp]).
         *
         * Well under the shared client's 30 s read timeout and far under the
         * multi-minute hang `vcs/diff?mode=working` reaches on a large working
         * tree: the Changes panel has to answer 「取不到」 inside one user
         * gesture, not after the user has given up.
         */
        private const val REVIEW_VCS_TIMEOUT_MS = 20_000L

        /**
         * Pure core of [contextLimit]: the ring's denominator, resolved from the
         * **first source the catalog can answer for**. Split out so the mapping
         * is JVM-testable without an Android `ChatViewModel` instance.
         *
         * Priority — upstream reads the limit off the message that owns the
         * token total (`session-context-usage.tsx:52-58`), so the transcript row
         * leads; the session's own ref and the picker widen it for a session
         * with no (or a trimmed) usage row:
         *
         * 1. [transcriptModel] — `Model.Ref` of the newest assistant message with
         *    token usage: the same row the numerator comes from, and the same ref
         *    the context tab resolves (`SessionContextModel.modelRef`).
         * 2. [sessionModel] — the tracked session's `Model.Ref`
         *    (`SessionRow.providerID/modelID`): what the server is running before
         *    the first usage lands.
         * 3. [selectedModel] — the picker's effective model (the pre-v16 single
         *    source), kept so a brand-new session still shows a limit as soon as
         *    a model is chosen.
         *
         * The value is the entry's **effective window**
         * ([ContextUsage.windowOf]): `min(limit.input, limit.context)`, both
         * halves off the resolved catalog entry itself (its `limit.input` is
         * [OpenCodeClient.Providers.Model.limitInput]). The server compacts on
         * the input half, so the raw `limit.context` is a denominator nothing
         * server-side uses — the v20 device read 59 % (619,741 tokens of
         * 1,048,576) on a model whose real ceiling is 504,288.
         *
         * Every source unknown, or a catalog that has not landed / advertises no
         * `limit.context` ⇒ `null`: the ring keeps its documented empty track
         * instead of a fake denominator.
         */
        fun contextLimitFrom(
            providers: OpenCodeClient.Providers?,
            transcriptModel: Pair<String, String>?,
            sessionModel: Pair<String, String>?,
            selectedModel: Pair<String, String>?,
        ): Int? = sequenceOf(transcriptModel, sessionModel, selectedModel)
            .firstNotNullOfOrNull { ref ->
                ref?.let { (providerID, modelID) ->
                    ContextUsage.windowOf(providers, providerID, modelID)
                }
            }

        /**
         * Single-source [contextLimitFrom] — the picker's effective model, the
         * shape `ChatViewModel` used before the multi-source resolution.
         */
        fun contextLimitOf(
            providers: OpenCodeClient.Providers?,
            selected: Pair<String, String>?,
        ): Int? = contextLimitFrom(
            providers,
            transcriptModel = null,
            sessionModel = null,
            selectedModel = selected,
        )

        /**
         * The tracked session's model ref as `ChatViewModel` stores it: both
         * halves of `SessionRow`'s `providerID`/`modelID` must be present, since
         * a half-ref would silently resolve to some other model's limit.
         */
        fun sessionModelRef(row: SessionRow?): Pair<String, String>? {
            val providerID = row?.providerID?.takeIf { it.isNotBlank() } ?: return null
            val modelID = row.modelID?.takeIf { it.isNotBlank() } ?: return null
            return providerID to modelID
        }

        /**
         * The model the context on screen runs under, in upstream
         * `selection.current()` order
         * (`_upstream/packages/app/src/providers/models/selection.tsx:234-249`):
         *
         * 1. [picked] — the pick the user made **for this context** (`scope().model`:
         *    `saved.session[sessionID]`, or `store.draft` without a session);
         * 2. [session] — the tracked session's own `model` (`durable()`,
         *    `Session.Info.model`): what this session was created with, here or by
         *    another client, and what it must keep running;
         * 3. [serverDefault] — the configured default (`fallback`, first term);
         * 4. [recent] — the picker memory (`fallback`, `models.recent`).
         *
         * The order is the whole point: a session with a model of its own outranks
         * every *derived* label, so opening it shows — and keeps — that model
         * instead of the server default the app happened to read first (the
         * measured v28 bug: a session created with `space-bunny-free` rendered,
         * and then ran as, `LongCat 2.5 Preview Free`). Only a pick made in *this*
         * context sits above it; the persisted memory never does.
         *
         * Null when nothing names a model at all — a session whose `model` is null
         * on a server with no configured default: the composer then refuses the
         * call locally ([SessionModelSync.Plan.NoModel]) instead of spending the
         * doomed round trip.
         */
        fun effectiveModelRef(
            picked: Pair<String, String>?,
            session: Pair<String, String>?,
            serverDefault: Pair<String, String>?,
            recent: Pair<String, String>?,
        ): Pair<String, String>? = picked ?: session ?: serverDefault ?: recent
    }
}

/**
 * One session row's server-side unread watermark — `Session.Info.time.idle` /
 * `time.viewed` (`_upstream/packages/schema/src/session.ts:44-49`, written by
 * `core/src/session/projector.ts:419-425,583-593`).
 *
 * [unread] is the TUI client's rule verbatim
 * (`_upstream/packages/tui/src/context/session-tabs.tsx:120-123`):
 *
 * ```ts
 * info?.time.idle !== undefined && (info.time.viewed === undefined || info.time.idle > info.time.viewed)
 * ```
 *
 * i.e. "a run reached a terminal transition that no client has reported as
 * observed". The acknowledgement is `POST /api/session/{id}/view {idle}`
 * ([ChatViewModel.markSessionViewed]) and the watermark is monotone server-side,
 * so a client that posts a *newer* idle keeps a later transition unread.
 *
 * Why the dot reads this instead of the in-app notification feed alone: the feed
 * only exists while a stream is open, and this shell opens none off the session
 * route (`OcActivity`'s documented design) — a turn that settled while the user
 * was on Home would be invisible to it forever. `time.idle`/`time.viewed` live on
 * the session row itself, so the next list read (Home entry, drawer open) already
 * carries the answer.
 */
data class SessionUnread(
    /** `time.idle`: the last terminal transition; `null` while the session never settled. */
    val idle: Long? = null,
    /** `time.viewed`: the newest idle transition some client acknowledged. */
    val viewed: Long? = null,
) {
    /** Upstream `isUnread`: settled and not yet acknowledged anywhere. */
    val unread: Boolean get() = idle != null && (viewed == null || idle > viewed)

    /**
     * [other]'s acknowledgement folded in as a floor. `time_viewed` is monotone
     * server-side (`core/src/session/projector.ts:583-593`: `max(idle,
     * coalesce(time_viewed, idle))`), so a value this client already recorded can
     * never be *higher* than the truth — taking the max of the two is therefore
     * sound, and it is what keeps a list read that raced the acknowledgement's
     * `POST …/view` from re-lighting the row the user just opened.
     */
    fun withAcknowledged(other: SessionUnread?): SessionUnread {
        val floor = other?.viewed ?: return this
        return if (viewed != null && viewed >= floor) this else copy(viewed = floor)
    }

    /**
     * This row as [ChatViewModel.ackSessionViewed] may record it after
     * acknowledging the boundary [idle] — the transition and the watermark set to
     * the same value, so the row reads as observed locally even if the
     * `POST …/view` fails or a read races it.
     *
     * Neither half regresses: `time_viewed` is monotone server-side, and a
     * transition recorded here that is *newer* than the one acknowledged stays
     * unread (only `viewed` moves up, never `idle` down) — a second terminal
     * event landing between the read and the ack is still something the viewer
     * has not seen.
     */
    fun ackedAt(idle: Long): SessionUnread = SessionUnread(
        idle = maxOf(idle, this.idle ?: 0L),
        viewed = maxOf(idle, viewed ?: 0L),
    )
}

/**
 * Pure derivation of the summary panel's background-work rows — the v2
 * `createSessionBackground` memos
 * (`_upstream/packages/app/src/session/requests/background.ts:1-115`, v2 head
 * `8ce629b`). [ChatViewModel.refreshBackgroundTasks] fetches the inputs; this
 * object only decides membership, labels and order.
 *
 * Wire inputs (the four upstream sources):
 * - raw `GET /api/session/{id}/message` rows — assistant `tool` parts named
 *   `shell`/`subagent` whose `state.status` is `completed` while
 *   `state.metadata.status` is still `running` are backgrounded work
 *   (`:32-84`); `synthetic` rows carry the completion notices that retire
 *   them (`metadata.source` + `childID`/`shellID`/`jobID`);
 * - child sessions ([Child.parentID]) and `GET /api/shell` rows, gated by the
 *   `GET /api/session/active` map whose absent entries are idle (`:91`).
 *
 * Both the v2 flat message shape and the v1 `{info, parts}` rows
 * (`OpenCodeClient.Message`) are read, same leniency as `SessionContextModel`.
 * The display cap (`SessionSummaryModel.backgroundTasks`) is applied by the
 * panel, not here.
 */
object SessionBackgroundModel {

    /** One child session row (`SessionV2Info.parentID` / `title`). */
    data class Child(val id: String, val parentID: String?, val title: String?)

    /** One `GET /api/shell` row (`background.ts:98-111`). */
    data class Shell(val id: String, val command: String?, val sessionID: String?, val status: String?)

    /** `background.ts:1-4`: `{id, type, label, agent?}` before the UI row mapping. */
    private data class Task(val id: String, val type: String, val label: String, val agent: String? = null)

    /** `background.ts:105-115`: one running tool call of the unfinished turn. */
    private data class Blocking(val type: String, val id: String?, val label: String?)

    /**
     * `background.ts:85-115` (`tasks`): history half (completed tool parts with
     * `metadata.status: running`, minus the ids later synthetic notices
     * completed) + the live halves (non-idle child sessions, running shells),
     * each filtered against the unfinished turn's blocking calls, deduped by
     * id — first position wins, last label wins (JS `Map` semantics).
     */
    fun tasks(
        sessionID: String?,
        messages: List<JSONObject>,
        children: List<Child>,
        shells: List<Shell>,
        active: Map<String, String> = emptyMap(),
    ): List<SessionSummaryModel.BackgroundTask> {
        if (sessionID.isNullOrEmpty()) return emptyList()
        val completed = HashSet<String>()
        val historySubagents = ArrayList<Task>()
        val historyShells = ArrayList<Pair<String, Task>>()
        var unfinished: JSONObject? = null

        for (message in messages) {
            when (messageType(message)) {
                "synthetic" -> {
                    val meta = metadataOf(message) ?: continue
                    // Ids read null-safely: a `{"childID":null}` would otherwise
                    // register the literal string "null" as a finished task id (and
                    // `"null"` never matches a real child, so the row would simply
                    // never retire). Every `optString` in this projection is on a
                    // field the wire may set to null; the helper is the one reader.
                    when (optStringOrNull(meta, "source")) {
                        "subagent" -> optStringOrNull(meta, "childID")?.let(completed::add)
                        "shell" -> {
                            optStringOrNull(meta, "shellID")?.let(completed::add)
                            optStringOrNull(meta, "jobID")?.let(completed::add)
                        }
                    }
                    continue
                }

                "assistant" -> {}
                else -> continue
            }
            for (part in contentOf(message)) {
                val name = partName(part)
                if (partType(part) != "tool" || (name != "subagent" && name != "shell")) continue
                val state = part.optJSONObject("state") ?: continue
                if (optStringOrNull(state, "status") != "completed") continue
                val meta = state.optJSONObject("metadata") ?: continue
                if (optStringOrNull(meta, "status") != "running") continue
                val input = state.optJSONObject("input")
                if (name == "subagent") {
                    val childID = optStringOrNull(meta, "sessionID") ?: continue
                    historySubagents += Task(
                        id = childID,
                        type = "subagent",
                        label = optStringOrNull(input, "description") ?: childID,
                        agent = optStringOrNull(input, "agent"),
                    )
                } else {
                    val partID = optStringOrNull(part, "id").orEmpty()
                    val shellID = optStringOrNull(meta, "shellID")
                    val command = optStringOrNull(input, "command")
                    historyShells += partID to Task(
                        id = shellID ?: partID,
                        type = "shell",
                        label = command ?: partID,
                    )
                }
            }
            if (timeCompleted(message) == null) unfinished = message
        }

        val blocking = unfinished?.let { message ->
            contentOf(message).mapNotNull { part ->
                val name = partName(part)
                if (partType(part) != "tool" || (name != "subagent" && name != "shell")) return@mapNotNull null
                val state = part.optJSONObject("state") ?: return@mapNotNull null
                if (optStringOrNull(state, "status") != "running") return@mapNotNull null
                val meta = state.optJSONObject("metadata")
                val input = state.optJSONObject("input")
                Blocking(
                    type = name,
                    id = if (name == "shell") {
                        optStringOrNull(meta, "shellID")
                    } else {
                        optStringOrNull(meta, "sessionID")
                    },
                    label = if (name == "shell") {
                        optStringOrNull(input, "command")
                    } else {
                        optStringOrNull(input, "description")
                    },
                )
            }
        }.orEmpty()

        fun blocked(type: String, id: String, label: String?): Boolean =
            blocking.any { item ->
                item.type == type && (item.id == id || (!item.label.isNullOrEmpty() && item.label == label))
            }

        val merged = LinkedHashMap<String, Task>()
        for (task in historySubagents) if (task.id !in completed) merged[task.id] = task
        for (child in children) {
            if (child.parentID != sessionID) continue
            if ((active[child.id] ?: "idle") == "idle") continue
            val label = child.title?.takeIf { it.isNotEmpty() } ?: child.id
            if (blocked("subagent", child.id, child.title)) continue
            merged[child.id] = Task(child.id, "subagent", label)
        }
        for ((partID, task) in historyShells) {
            if (partID in completed || task.id in completed) continue
            merged[task.id] = task
        }
        for (shell in shells) {
            if (shell.status != "running" || shell.sessionID != sessionID) continue
            val label = shell.command?.takeIf { it.isNotEmpty() } ?: shell.id
            if (blocked("shell", shell.id, shell.command)) continue
            merged[shell.id] = Task(shell.id, "shell", label)
        }
        return merged.values.map { SessionSummaryModel.BackgroundTask(it.type, it.label, it.agent) }
    }

    /** v2 flat `type`, else v1 `info.role` (same rule as `SessionContextModel`). */
    private fun messageType(row: JSONObject): String {
        val info = row.optJSONObject("info") ?: row
        // A JSON null on either key must not produce a role named "null".
        return optStringOrNull(info, "type") ?: optStringOrNull(info, "role").orEmpty()
    }

    private fun partType(part: JSONObject): String = optStringOrNull(part, "type").orEmpty()

    /** v2 tool parts name themselves `name`, v1 ones `tool` (`OpenCodeClient.Part`). */
    private fun partName(part: JSONObject): String =
        optStringOrNull(part, "name") ?: optStringOrNull(part, "tool").orEmpty()

    private fun contentOf(row: JSONObject): List<JSONObject> {
        val array = row.optJSONArray("content")
            ?: row.optJSONArray("parts")
            ?: row.optJSONObject("info")?.optJSONArray("parts")
            ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) array.optJSONObject(i)?.let(::add)
        }
    }

    private fun metadataOf(row: JSONObject): JSONObject? =
        row.optJSONObject("metadata") ?: row.optJSONObject("info")?.optJSONObject("metadata")

    private fun timeCompleted(row: JSONObject): Long? {
        val info = row.optJSONObject("info") ?: row
        return info.optJSONObject("time")?.optLong("completed", 0L)?.takeIf { it > 0L }
    }
}

/**
 * Backoff of the session list's self-heal re-read
 * ([ChatViewModel.listSessions]'s `scheduleSessionListRetry`), by failed round.
 *
 * The first re-read lands one `SESSION_LIST_RETRY_DELAY_MS` after the failure —
 * long enough for an `adb reverse` mapping or a restarted backend to come back,
 * short enough that Home heals while the user is still looking at it — and each
 * further failure doubles the gap up to `SESSION_LIST_RETRY_MAX_DELAY_MS`, so a
 * server that stays down costs one `GET /api/session` a minute instead of a
 * spin. Pure, so the cadence is JVM-testable.
 */
internal fun sessionListRetryDelayMs(round: Int): Long =
    (SESSION_LIST_RETRY_DELAY_MS shl round.coerceIn(0, 4)).coerceAtMost(SESSION_LIST_RETRY_MAX_DELAY_MS)

/** First gap between a failed session-list read and its self-heal re-read. */
internal const val SESSION_LIST_RETRY_DELAY_MS = 5_000L

/** Ceiling of the same backoff — the cadence of a list that stays unreachable. */
internal const val SESSION_LIST_RETRY_MAX_DELAY_MS = 60_000L

/**
 * BUG-2 (v36 tB): whether a `delivery:"steer"` admission has been undelivered
 * long enough — with the session silent and not running — to be reported as
 * stuck rather than simply parked.
 *
 * The silence is measured from the **later** of the admission and the last
 * event this client saw for the session ([ChatViewModel.lastPartEventAt]): a
 * steer parked behind a working turn is normal and its frames keep pushing the
 * deadline out, so only a session that went quiet *with* the message still
 * pending reaches the threshold. Pure, so the boundary is JVM-testable.
 */
internal fun pendingDeliveryStalled(
    now: Long,
    admittedAt: Long,
    lastSessionEventAt: Long,
    stallMs: Long = PENDING_DELIVERY_STALL_MS,
): Boolean = now - maxOf(admittedAt, lastSessionEventAt) >= stallMs

/**
 * How long a still-pending steer admission may stay undelivered on a quiet,
 * idle-looking session before it is reported (see [pendingDeliveryStalled]).
 */
internal const val PENDING_DELIVERY_STALL_MS = 60_000L

/**
 * Pure four-state split for a browse read that found no transport. A missing
 * verdict with a usable server row is still [TransportFailure.ProbePending] —
 * the probe may simply not have started yet (the picker opens in the same
 * frame as `ensureClient`) — never [TransportFailure.NotConnected], which is
 * reserved for a definitive "up, but not 2.x" answer or no usable server row.
 */
internal fun transportFailureFor(
    probing: Boolean,
    lastProbe: V2Capability.ProbeResult?,
    serverUsable: Boolean,
): TransportFailure = when {
    probing -> TransportFailure.ProbePending
    lastProbe?.authWall == true -> TransportFailure.AuthRequired
    lastProbe == null -> if (serverUsable) TransportFailure.ProbePending else TransportFailure.NotConnected
    lastProbe.httpStatus == null -> TransportFailure.Unreachable
    else -> TransportFailure.NotConnected
}

/**
 * v2 connected-provider predicate: a provider counts as connected only when
 * its linked integration reports at least one stored connection
 * (`GET /api/integration` rows carry the `connections` count, so one list
 * read decides the whole catalog). Providers without an `integrationID`, or
 * whose integration is absent / reports zero connections, are unconnected.
 */
fun v2ConnectedProviderIds(
    providers: List<V2Api.ProviderInfo>,
    integrations: List<V2Api.IntegrationInfo>,
): Set<String> {
    val live = integrations.filter { it.connections > 0 }.map { it.id }.toSet()
    return providers
        .filter { it.integrationID != null && it.integrationID in live }
        .map { it.id }
        .toSet()
}

/**
 * One `GET …/inbox` item → the timeline's parked-steer row, or `null` when the
 * item is not one (`Session.Inbox.Info` is a union: only
 * `type:"user" + delivery:"steer"` is a steer the user is waiting to see —
 * `synthetic` / `compaction` / `move` items are control traffic, and
 * `delivery:"queue"` items belong to the composer's queue panel, which owns its
 * own rows).
 *
 * Shape (openapi.json, `Session.Inbox.User`): `{id, sessionID, time.created,
 * type, payload:{text, files?, agents?, skills?, metadata?}, delivery}`. The
 * display text prefers `payload.metadata.displayText` — the composer sends the
 * user's own typed text there (mentions expanded in `payload.text`) — and falls
 * back to `payload.text`.
 *
 * The row carries the **admission id**, i.e. the id the server will publish for
 * the message it delivers, so `ChatStore.upsert` replaces the placeholder with
 * the real message instead of adding a second row. `files` become `file` parts
 * with no `url`: the inbox payload embeds attachment **content** (base64 in
 * `data`), which the timeline must not re-render as a chip, but the count is
 * what the pending row shows.
 *
 * Every string is read through [optStringOrNull]: `id`/`type`/`delivery`/`text`
 * are required fields, but a JSON `null` on the wire must never arrive as the
 * literal `"null"` (the two `org.json` builds disagree — see the helper).
 * Pure, so it is covered by a JVM test.
 */

/**
 * The transcript row a prompt gets the instant the server admits it, before any
 * message exists for it — upstream's `handoffMessage` (`composer/submit.ts:97`),
 * which the shell reaches through its own parked-steer vocabulary (see
 * [admitHandoffRow] for why the row is the fix and what retires it).
 *
 * Built from the submission rather than an inbox item, because at this point
 * the server has answered with the admission id and nothing else: the row is the
 * local copy of the prompt the user just sent. The parts match [pendingSteerRow]
 * exactly — the user's own words (`metadata.displayText`, so a prompt carrying
 * review-comment note lines shows the note-free text plus one comment card per
 * note), one `file` part per attachment with **no** `url` (the content is
 * embedded on the wire, so a chip must not try to re-render it) and the comment
 * cards verbatim in `rawJson`, the carrier [ChatStore.UiPart] reserves for
 * payloads without first-class fields.
 *
 * `timeCreated` is "now": the row belongs at the tail, and the server's own
 * `time.created` is not available until it publishes the message.
 */
internal fun handoffPromptRow(
    id: String,
    text: String,
    fileCount: Int,
    notes: List<ReviewNote>,
): ChatStore.UiMessage {
    val presentation = ReviewCommentMetadata.presentation(text)
    val parts = ArrayList<ChatStore.UiPart>(2 + fileCount + notes.size)
    if (presentation.displayText.isNotBlank()) {
        parts += ChatStore.UiPart(id = "$id:pending:text", type = "text", text = presentation.displayText)
    }
    repeat(fileCount.coerceAtLeast(0)) { index ->
        parts += ChatStore.UiPart(id = "$id:pending:attachment:$index", type = "file")
    }
    for (note in notes) {
        val entry = ReviewCommentMetadata.noteJson(note)
        parts += ChatStore.UiPart(
            id = "$id:pending:comment:${entry.optString("path")}:${note.startLine}",
            type = TimelineComments.PART_TYPE,
            text = note.comment,
            filename = note.path,
            rawJson = entry.toString(),
        )
    }
    return ChatStore.UiMessage(
        id = id,
        role = ChatStore.PENDING_ROLE,
        timeCreated = System.currentTimeMillis(),
        parts = parts,
    )
}
internal fun pendingSteerRow(item: JSONObject): ChatStore.UiMessage? {
    if (optStringOrNull(item, "type") != "user") return null
    if (optStringOrNull(item, "delivery") != "steer") return null
    val id = optStringOrNull(item, "id") ?: return null
    val payload = item.optJSONObject("payload")
    val metadata = payload?.optJSONObject("metadata")
    val text = optStringOrNull(metadata, "displayText")
        ?: payload?.let { optStringOrNull(it, "text") }
        ?: ""
    val parts = ArrayList<ChatStore.UiPart>(2)
    if (text.isNotBlank()) {
        parts += ChatStore.UiPart(id = "$id:pending:text", type = "text", text = text)
    }
    val files = payload?.optJSONArray("files")
    for (index in 0 until (files?.length() ?: 0)) {
        val file = files?.optJSONObject(index) ?: continue
        parts += ChatStore.UiPart(
            id = "$id:pending:file:$index",
            type = "file",
            mime = optStringOrNull(file, "mime"),
            filename = optStringOrNull(file, "name"),
        )
    }
    // `metadata.attachments` counts too — the same rule the composer's queue rows
    // use (`ComposerQueueHolder.rowFromJson`: `payload.files` + the metadata
    // list, `queue-panel.tsx`), so a steer carrying either kind reports one
    // number. Only the count is kept: the payload embeds attachment content.
    val attachments = metadata?.optJSONArray("attachments")?.length() ?: 0
    repeat(attachments) { index ->
        parts += ChatStore.UiPart(id = "$id:pending:attachment:$index", type = "file")
    }
    // F-4: the parked steer's structured line comments (`metadata.comments`,
    // `composer/submit.ts:169-177`). Upstream renders them on the pending row
    // too (`session/route.tsx:73` passes `readPromptPresentation(…).comments`
    // into the same user-message body), and the row's own `text` is the
    // note-free `displayText` — so without these parts a queued comment would
    // be invisible until the message is delivered.
    //
    // The entry is kept verbatim in `rawJson` (the carrier `ChatStore.UiPart`
    // reserves for payloads without first-class fields) and read back through
    // [ReviewCommentMetadata.noteFromJson], so the wire shape stays the single
    // source of truth for the card.
    val comments = metadata?.optJSONArray("comments")
    for (index in 0 until (comments?.length() ?: 0)) {
        val entry = comments?.optJSONObject(index) ?: continue
        if (ReviewCommentMetadata.noteFromJson(entry) == null) continue
        parts += ChatStore.UiPart(
            id = "$id:pending:comment:$index",
            type = TimelineComments.PART_TYPE,
            text = optStringOrNull(entry, "comment").orEmpty(),
            filename = optStringOrNull(entry, "path"),
            rawJson = entry.toString(),
        )
    }
    val created = item.optJSONObject("time")?.optLong("created", 0L) ?: 0L
    return ChatStore.UiMessage(
        id = id,
        role = ChatStore.PENDING_ROLE,
        // The server's own admission time keeps the row at the tail of the
        // transcript (`sortedBy { timeCreated }`); a frame without one falls
        // back to "now", which sorts there too.
        timeCreated = created.takeIf { it > 0L } ?: System.currentTimeMillis(),
        parts = parts,
    )
}

/**
 * The client half of the automatic-compaction threshold — the v19 device
 * finding (P2, `research/v19_verify/b/`).
 *
 * Upstream compacts **before** the request that would cross the window, never
 * after it fails. The rule is `SessionCompaction.required`
 * (`_upstream/packages/core/src/session/compaction.ts:742-763`):
 *
 * ```ts
 * const output = Math.min(limit.output, OUTPUT_TOKEN_MAX)                   // 32_000
 * const promptCeiling = Math.min(
 *   limit.input === undefined ? Infinity : limit.input - config.buffer,     // 20_000
 *   context - Math.max(output, config.buffer),
 * )
 * return estimateTokens(input) >= promptCeiling
 * ```
 *
 * On a 1,048,576-token window that puts the server's own trigger at
 * `1_048_576 - 32_000 = 1_016_576`, i.e. **96.9 %** of the window
 * ([upstreamCeiling]) — *when the catalog carries no `limit.input`*. When it does
 * (the v20 finding) the input branch binds instead, and it is a much earlier
 * trigger than the context branch: for `opencode-go/space-bunny-free`
 * (`{"context":1048576,"input":524288,"output":524288}`) the server's ceiling is
 * `min(524288 - 20_000, 1_048_576 - 32_000)` = **504,288**, i.e. 48 % of the
 * advertised context — measured as the server auto-compacting somewhere in the
 * 442K–620K range on the device. [ChatViewModel.contextLimit] therefore hands
 * this policy the **effective window** `min(limit.input, limit.context)`
 * ([ContextUsage.windowOf]) rather than the raw context: the same denominator
 * the ring paints, so the client's 80 % lands at 419,430 — below the server's
 * own 504,288 and far below the 1,048,576 the pre-v20 denominator would have
 * waited for (838,861, i.e. never: the server had always compacted first).
 * `estimateTokens` (`compaction.ts:174-208`) is the newest
 * assistant row's `input + cache.read + cache.write + output + reasoning` plus
 * whatever that row does not already cover — the same quantity
 * [ChatViewModel.activeContextTokens] reduces out of
 * `GET /api/session/{id}/context` (`SessionContextModel.lastAssistantTotal`),
 * which is why the two ratios are comparable at all.
 *
 * Why this client needs an **earlier** trigger of its own: the server evaluates
 * `required()` only at a step boundary, against the usage anchor of an already
 * completed turn. One admitted turn can therefore add more than the whole safety
 * margin — v19 measured `input=643K` + `cache_read=1.3M` against a 1M window —
 * and the provider then rejects the request outright. That failure reaches the
 * client as a bare `InvalidRequest`: `to-session-error.ts:26-27` keeps only
 * `provider.invalid-request` and drops the `classification: "context-overflow"`
 * that `provider-error.ts:143-150` had attached, so nothing in the stored error
 * distinguishes it from any other 4xx. Staying out of that dead zone is
 * therefore the client's job.
 *
 * [DEFAULT_RATIO] is that earlier margin, and it is deliberately *not* upstream's
 * ceiling: at 80 % of the window the next turn still has a fifth of the window
 * to grow into, which is what one over-long tool result or attachment needs.
 */
internal object ContextCompactionPolicy {

    /**
     * Fraction of the **effective window** ([ChatViewModel.contextLimit], i.e.
     * `min(limit.input, limit.context)`) at which this client asks for a
     * compaction. On the v20 catalog that is 419,430 of `space-bunny-free`'s
     * 524,288 — below the server's own 504,288, which is what makes the client
     * half fire first.
     */
    const val DEFAULT_RATIO = 0.8

    /** Upstream `OUTPUT_TOKEN_MAX` (`compaction.ts:42`) — the reserved output half. */
    const val OUTPUT_TOKEN_MAX = 32_000

    /** Upstream `DEFAULT_BUFFER` (`compaction.ts:40`). */
    const val DEFAULT_BUFFER = 20_000

    /**
     * `tokens >= limit * ratio`, i.e. the numerator [ChatViewModel.activeContextTokens]
     * against the denominator [ChatViewModel.contextLimit]. Either half unknown
     * ⇒ `false`: an unknown usage never fabricates a trigger, exactly like the
     * ring renders its empty track instead of a fake ratio.
     */
    fun overThreshold(
        tokens: Long?,
        limitContext: Int?,
        ratio: Double = DEFAULT_RATIO,
    ): Boolean {
        if (tokens == null || limitContext == null) return false
        if (tokens < 0L || limitContext <= 0 || ratio <= 0.0) return false
        return tokens.toDouble() >= limitContext.toDouble() * ratio
    }

    /**
     * `tokens < limit`, i.e. the window is **not yet full**, so a compaction can
     * still be carried out.
     *
     * This is the half of the rule that keeps the automatic trigger *preventive*.
     * A compaction summarises the history it is given, so its own request carries
     * that history: once the window is full the summary request is over the
     * window too and the provider rejects it the same way (measured v19 — the
     * manual `POST …/compact` wrote its `type=compaction` row and left the token
     * count untouched). Upstream can recover from that state, but only through
     * the *internal* `compact({overflow: true})` path, which re-expands the
     * durable local history (`compaction.ts:497-500,736-741`) — the HTTP route
     * carries no such flag (`protocol/src/groups/session.ts:495-513` accepts only
     * `id` / `delivery`), so an automatic attempt above the limit could only add a
     * doomed compaction row to a session that is already stuck. That state
     * belongs to the user instead: it is what the error card's recovery rows are
     * for ([com.xiufeigo.opencodeshell.ui.session.timeline.contextOverflowActions]).
     */
    fun hasHeadroom(tokens: Long?, limitContext: Int?): Boolean {
        if (tokens == null || limitContext == null) return false
        if (tokens < 0L || limitContext <= 0) return false
        return tokens < limitContext.toLong()
    }

    /**
     * True only while a turn is in flight — the one state in which this client
     * compacts without being asked.
     *
     * Upstream compacts inside the drain, i.e. as part of session progress, and
     * this client keeps that boundary for a second reason: a compaction is not
     * free. Its summary request carries the very history it is summarising, so on
     * a 900K-token window it is a 900K-token model call. Merely *opening* a
     * session whose ring already reads 85 % must not spend that; the moment the
     * session advances (a step boundary is coming, and the next request is built
     * from the context) it may.
     *
     * A turn that is opening its **first** request cannot be saved this way —
     * that request is already on the wire before any client write could land —
     * and that is the case the error card's recovery rows own
     * ([com.xiufeigo.opencodeshell.ui.session.timeline.contextOverflowActions]).
     * What this covers is the step *after* the one that grew the context: the
     * v19 shape, where a turn added `input=643K` + `cache_read=1.3M` at once.
     */
    fun isAdvancing(runStatus: ChatStore.RunStatus): Boolean = runStatus == ChatStore.RunStatus.RUNNING

    /**
     * Upstream's own `promptCeiling` for the same window (`compaction.ts:757-761`),
     * kept as the reference point [DEFAULT_RATIO] is earlier than — and as what
     * the tests pin the server's real trigger to.
     *
     * The rule is the full `min` of both branches:
     *
     * - `limit.input - buffer` ([limitInput], `null` = the wire omits it ⇒
     *   upstream's `Infinity`). This is the branch that binds on the v20 catalog:
     *   `space-bunny-free` answers `min(524_288 - 20_000, …) = 504_288`.
     * - `context - max(min(output, OUTPUT_TOKEN_MAX), buffer)`. [limitContext] is
     *   the **raw** `limit.context` here, not the effective window
     *   [ChatViewModel.contextLimit] paints: this helper reproduces the server's
     *   own arithmetic, whose two branches are computed independently before the
     *   `min` (see the class doc). [limitOutput] is the catalog's
     *   `model.limit.output` when known; `min(limitOutput, OUTPUT_TOKEN_MAX)`
     *   reproduces the server's clamp, and an unknown output falls back to
     *   `OUTPUT_TOKEN_MAX` — the *lower* ceiling, i.e. the earlier trigger, so
     *   the approximation never makes this client optimistic.
     *
     * `null` for a window that cannot be used (`context <= 0` returns `false`
     * upstream too).
     */
    fun upstreamCeiling(
        limitContext: Int?,
        limitOutput: Int? = null,
        limitInput: Int? = null,
    ): Long? {
        if (limitContext == null || limitContext <= 0) return null
        val output = minOf(limitOutput ?: OUTPUT_TOKEN_MAX, OUTPUT_TOKEN_MAX)
        val fromContext = limitContext - maxOf(output, DEFAULT_BUFFER)
        val fromInput = limitInput?.takeIf { it > 0 }?.let { it - DEFAULT_BUFFER } ?: Int.MAX_VALUE
        return minOf(fromInput, fromContext).toLong()
    }
}

/**
 * The once-per-turn throttle in front of [ContextCompactionPolicy]: the session
 * must be **near** the window and still have headroom, and the *same* turn must
 * not ask twice.
 *
 * Why a turn key rather than a timer alone: the numerator is refreshed by
 * `GET /api/session/{id}/context` on **every** snapshot, so a threshold test
 * alone would re-ask on each one. The anchor is the newest assistant message
 * that reported usage ([ContextUsage.lastUsage] — the row the ring reads both
 * halves from), which is exactly the row the compaction would replace: a
 * compaction that completes moves the anchor on, and one that fails leaves it
 * alone, so a failed attempt is never retried against the same turn.
 *
 * [DEFAULT_MIN_INTERVAL_MS] is the second guard, for the state a turn key cannot
 * cover: switching away from a session and back re-presents the same anchor, and
 * the interval bounds that (and any other path that re-arms the key) to one
 * request per minute per view model. Repeated asks are cheap server-side while
 * one is pending — `SessionInbox.admitCompaction` returns the pending item
 * instead of admitting a second (`_upstream/packages/core/src/session/inbox.ts:214-216`)
 * — but a *new* compaction after a failed one re-runs a summary request that is
 * itself over the window, which is the one thing worth throttling. The headroom
 * gate is what stops that case from being attempted automatically at all; see
 * [ContextCompactionPolicy.hasHeadroom].
 *
 * `now` is a parameter (not a clock read) so the whole policy is deterministic
 * under a JVM test.
 */
internal class ContextCompactionTrigger(
    private val ratio: Double = ContextCompactionPolicy.DEFAULT_RATIO,
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
) {

    companion object {
        /** At most one automatic compaction request per minute. */
        const val DEFAULT_MIN_INTERVAL_MS = 60_000L
    }

    /** The `sessionID\u0000turnKey` already asked for; null before the first ask. */
    private var firedKey: String? = null

    /** [Long.MIN_VALUE] = nothing asked yet, so the first ask is never delayed. */
    private var lastAttemptAt: Long = Long.MIN_VALUE

    /**
     * True when this call should issue the compaction request. A `true` answer
     * arms the throttle (the caller does not have to report back), which is what
     * makes the failed-request case safe: a request that errors still consumed
     * its one attempt for that turn.
     *
     * Two gates before the throttle: [ContextCompactionPolicy.overThreshold] (the
     * session is near the window) **and** [ContextCompactionPolicy.hasHeadroom]
     * (the window is not full yet — above it the request would only be a doomed
     * repeat of the v19 failure, which the error card's recovery rows own).
     */
    fun shouldFire(
        sessionID: String?,
        turnKey: String?,
        tokens: Long?,
        limitContext: Int?,
        now: Long,
    ): Boolean {
        if (sessionID.isNullOrBlank() || turnKey.isNullOrBlank()) return false
        if (!ContextCompactionPolicy.overThreshold(tokens, limitContext, ratio)) return false
        if (!ContextCompactionPolicy.hasHeadroom(tokens, limitContext)) return false
        val key = sessionID + "\u0000" + turnKey
        if (key == firedKey) return false
        if (lastAttemptAt != Long.MIN_VALUE && now - lastAttemptAt < minIntervalMs) return false
        firedKey = key
        lastAttemptAt = now
        return true
    }

    /**
     * Forget the last attempt. Not needed on a session switch — the key already
     * carries the session id, so another session's turn fires on its own — but
     * it is what a test (or a future "retry compaction" affordance) uses to
     * re-arm.
     */
    fun reset() {
        firedKey = null
        lastAttemptAt = Long.MIN_VALUE
    }
}

/**
 * An in-flight 「移动到…」 submit tracked by [ChatViewModel.pendingMove]: the
 * session it targeted, the optimistic timeline row's id, and the submit time
 * the confirming snapshot is measured against.
 */
internal data class PendingMove(
    val sessionID: String,
    val rowID: String,
    val startedAt: Long,
)

/** TTL for the optimistic move row: an event missed on a reconnecting stream must not park it forever. */
internal const val MOVE_OPTIMISTIC_TTL_MS = 15_000L

/**
 * Clock-skew allowance when matching the durable twin: the snapshot row's
 * `timeCreated` is stamped by the server, [PendingMove.startedAt] by this
 * device.
 */
internal const val MOVE_SNAPSHOT_SKEW_MS = 5_000L

/**
 * The untouched payload of the optimistic move row: the server-identical
 * nested shape (`MovePayload.location`, `schema/src/session-inbox.ts:31-35`),
 * so `RawParts.switch` renders the project name exactly like a live
 * `session.moved` row does.
 */
internal fun moveOptimisticRawJson(directory: String): String =
    JSONObject().put("location", JSONObject().put("directory", directory)).toString()

/**
 * The transient timeline row a 「移动到…」 submit inserts before the POST
 * answers: one `assistant` message with a single `location-switched` part, in
 * the live event row's exact shape (`$id:notice` part key, so the durable
 * twin merges rather than dupes). Pure so the shape is pinned by unit test
 * without a view model.
 */
internal fun buildMoveOptimisticMessage(
    rowID: String,
    directory: String,
    nowMillis: Long,
): ChatStore.UiMessage = ChatStore.UiMessage(
    id = rowID,
    role = "assistant",
    timeCreated = nowMillis,
    parts = listOf(
        ChatStore.UiPart(
            id = "$rowID:notice",
            type = "location-switched",
            rawJson = moveOptimisticRawJson(directory),
        ),
    ),
)

/**
 * Whether a live store event confirms the tracked submit: a
 * `location-switched` row that is not the optimistic row itself (the insert
 * never flows through `applyEvent`, but the id check keeps the rule total).
 */
internal fun eventConfirmsMove(event: ChatStore.Event, rowID: String): Boolean {
    val updated = event as? ChatStore.Event.MessageUpdated ?: return false
    if (updated.message.id == rowID) return false
    return updated.message.parts.any { it.type == "location-switched" }
}

/**
 * Whether a snapshot confirms the tracked submit: it carries a
 * `location-switched` row at least as new as the submit (minus skew). An
 * older row is a previous move, not this one's durable twin.
 */
internal fun snapshotConfirmsMove(
    messages: List<ChatStore.UiMessage>,
    startedAt: Long,
): Boolean = messages.any { message ->
    message.timeCreated >= startedAt - MOVE_SNAPSHOT_SKEW_MS &&
        message.parts.any { it.type == "location-switched" }
}

/**
 * Whether a server-read title may be adopted for a session the user renamed by
 * hand (v78, emulator-5558): a manual rename outranks every auto-title that
 * answers late. Pure so the rule is pinned by unit test without a view model;
 * [ChatViewModel.publishCurrentTitle] is the only caller.
 */
internal fun adoptsServerTitle(manualTitle: String?, incoming: String): Boolean =
    manualTitle == null || incoming == manualTitle
