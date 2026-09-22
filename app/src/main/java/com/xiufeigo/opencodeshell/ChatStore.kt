package com.xiufeigo.opencodeshell

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Pure-Kotlin chat state store (JVM-testable). Merges a REST snapshot with
 * SSE deltas using idempotent upserts: snapshot entries never clobber newer
 * streamed parts received during the reconnect window.
 */
object ChatStore {

    private const val MAX_TOOL_TEXT_CHARS = 16_000

    /**
     * How many E3 permission-reply / question-resolution records a state keeps
     * (newest win). They are display history for asks this screen answered, not
     * session state, so a long-running session must not grow them forever.
     */
    private const val MAX_RESOLVED_REQUESTS = 20

    enum class RunStatus { IDLE, RUNNING, ERROR }

    data class UiPart(
        val id: String,
        val type: String,
        val text: String = "",
        val toolName: String? = null,
        val toolStatus: String? = null,
        val mime: String? = null,
        val filename: String? = null,
        val url: String? = null,
        // Tool-part enrichment for the v2 tool cards. Purely additive display
        // payloads (raw state.input JSON, state.output, state.error); they do
        // not participate in any reduce/merge semantics.
        val toolInput: String? = null,
        val toolOutput: String? = null,
        val toolError: String? = null,
        /**
         * Route-C contract: the untouched part payload as JSON text, kept **only**
         * for the part types without first-class fields below.
         *
         * The v1 `Part` union has 12 members (`_upstream/packages/schema/src/v1/
         * session.ts:357-383`): text, subtask, reasoning, file, tool, step-start,
         * step-finish, snapshot, patch, agent, retry, compaction. Text / tool /
         * reasoning / file / compaction are fully modelled by the fields above;
         * the other seven are not, and dropping their payload made them
         * unrecoverable in the UI layer. Only those seven carry [rawJson].
         *
         * Stored as `String` (not `JSONObject`) on purpose: this data class is
         * compared for equality on every stream tick, and `JSONObject` has no
         * `equals`, so an object-typed field would make every comparison false.
         */
        val rawJson: String? = null,
    ) {
        companion object {
            /**
             * Part types whose payload has no first-class field in [UiPart]
             * (`v1/session.ts:357-383`); these and only these keep [rawJson].
             */
            val RAW_JSON_TYPES = setOf(
                "subtask",
                "step-start",
                "step-finish",
                "snapshot",
                "patch",
                "agent",
                "retry",
            )
        }
    }

    data class UiMessage(
        val id: String,
        val role: String,
        val timeCreated: Long,
        val parts: List<UiPart>,
        /**
         * M2 additive display payload: `info.tokens` flattened to one total
         * (`OpenCodeClient.tokenTotal`). Read only by the context ring
         * (`ContextUsage`); it takes no part in [reduce] / upsert semantics.
         */
        val tokens: Long? = null,
        /**
         * W4 additive display fields for the message meta line (upstream
         * `message-content.tsx:311-354,511-554`): `Agent · Model · 时长 ·
         * Interrupted`. Purely presentational — like [tokens] they never take
         * part in [reduce] / upsert semantics, and a missing field simply
         * drops that fragment from the line.
         */
        val agent: String? = null,
        /** `"providerID/modelID"` (`info.model` on user messages, the flat pair on assistant ones). */
        val model: String? = null,
        /** `info.time.completed` in epoch millis; `null` while the turn is unfinished. */
        val timeCompleted: Long? = null,
        /** `isInterrupted(info.error)`: an abort/interrupt error ended this turn. */
        val interrupted: Boolean = false,
    )

    /**
     * `session.status`'s retry detail
     * (`_upstream/packages/schema/src/session-status-event.ts:13-28`):
     * `{type:"retry", attempt, message, next, action?}`. The server is waiting
     * before re-running the failed step, so the UI can show a countdown —
     * upstream renders `Attempt {attempt} - retrying in {round((next-now)/1000)}s`
     * (`session-ui/src/components/session-retry.tsx:16-48`).
     *
     * Only ever non-null while [ChatState.runStatus] is [RunStatus.RUNNING]: a
     * `busy`/`idle` frame (or any locally produced status change) clears it.
     */
    data class RetryInfo(
        val attempt: Int,
        /** Epoch millis of the next attempt (`next`), 0 when the server omitted it. */
        val next: Long,
        /** Human-readable failure line (`message`); already readable, unlike `session.error`. */
        val message: String?,
        /**
         * Optional provider upsell/action (`status.action`), e.g. a free-tier or
         * rate-limit prompt upstream turns into a dialog
         * (`app/src/session/usage-exceeded-dialogs.tsx:25-41`).
         */
        val action: Action?,
    ) {
        data class Action(
            val reason: String,
            val provider: String,
            val title: String,
            val message: String,
            val label: String,
            val link: String?,
        ) {
            companion object {
                fun fromJson(obj: JSONObject): Action = Action(
                    reason = obj.optString("reason", ""),
                    provider = obj.optString("provider", ""),
                    title = obj.optString("title", ""),
                    message = obj.optString("message", ""),
                    label = obj.optString("label", ""),
                    // org.json maps a JSON null to the literal "null".
                    link = obj.optString("link", "").takeIf { it.isNotEmpty() && it != "null" },
                )
            }
        }

        companion object {
            fun fromJson(status: JSONObject): RetryInfo = RetryInfo(
                attempt = status.optInt("attempt", 0),
                next = status.optLong("next", 0L),
                message = status.optString("message", "").takeIf { it.isNotBlank() },
                action = status.optJSONObject("action")?.let(Action::fromJson),
            )
        }
    }

    data class ChatState(
        val messages: List<UiMessage> = emptyList(),
        val runStatus: RunStatus = RunStatus.IDLE,
        val error: String? = null,
        /**
         * Raw JSON of the last `session.error` payload ([error] is its
         * human-readable line). Kept so the error card can offer the full
         * provider payload behind its expander without the summary having to be
         * a wall of JSON. `null` when the failure was a plain string (local
         * failures, older servers).
         */
        val errorDetail: String? = null,
        /** Pending auto-retry detail; non-null only while [runStatus] is RUNNING. */
        val retry: RetryInfo? = null,
        /**
         * Session this state belongs to (set by `ChatViewModel.observeSession`).
         * Permission events for other sessions are ignored; `null` accepts all
         * (pre-session default state).
         */
        val sessionID: String? = null,
        /**
         * Pending permission asks for [sessionID] (`PermissionV1.Request`),
         * ordered by arrival; the dock shows the first one (upstream
         * `sessionPermissionRequest` returns a single request per session
         * tree).
         */
        val pendingPermissions: List<OpenCodeClient.PermissionRequest> = emptyList(),
        /**
         * Pending question asks for [sessionID] (`QuestionV1.Request`),
         * ordered by arrival; the dock shows the first one (mirrors
         * [pendingPermissions]).
         */
        val pendingQuestions: List<OpenCodeClient.QuestionRequest> = emptyList(),
        /**
         * `GET /session/:id/diff` + SSE `session.diff` payload
         * (`Snapshot.FileDiff[]`): the review side panel's Changes list.
         * Replaced wholesale on every event — the server always sends the full
         * per-session diff, never deltas.
         */
        val diff: List<OpenCodeClient.FileDiff> = emptyList(),
        /**
         * SSE `todo.updated` payload (`Todo.Info[]`). Exposed read-only for
         * the todo workflow; the review panel does not render it.
         */
        val todos: List<OpenCodeClient.TodoInfo> = emptyList(),
        /**
         * E3: answered permission asks, oldest first — `permission.replied`'s
         * `{requestID, reply}` (`v1/permission.ts:62-65`). The dock's own list is
         * [pendingPermissions]; this is the record of *how* an ask was answered,
         * kept so the timeline can mark the call it unblocked with
         * "allowed once / always / denied" after the request is gone. Bounded by
         * [MAX_RESOLVED_REQUESTS].
         */
        val permissionReplies: List<PermissionReply> = emptyList(),
        /**
         * E3: resolved question asks, oldest first — `question.replied`'s
         * `answers: string[][]` and `question.rejected`'s dismiss
         * (`v1/question.ts:47-60`). Bounded like [permissionReplies].
         */
        val questionResolutions: List<QuestionResolution> = emptyList(),
        /**
         * Wave 1 v2: pending form asks for [sessionID] (`Form.Info`), ordered
         * by arrival; the Wave 2 form dock shows the first one. v2 has no
         * question REST/events — forms are the carrier (A5 §1.5).
         */
        val pendingForms: List<FormAsk> = emptyList(),
    ) {
        /** Answer recorded for [requestID], or `null` when this screen never had it pending. */
        fun permissionReply(requestID: String): PermissionReply? =
            permissionReplies.lastOrNull { it.requestID == requestID }

        /** Resolution recorded for [requestID], or `null`. */
        fun questionResolution(requestID: String): QuestionResolution? =
            questionResolutions.lastOrNull { it.requestID == requestID }
    }

    /**
     * One answered permission ask: `permission.replied`'s `{requestID, reply}`
     * (`_upstream/packages/schema/src/v1/permission.ts:62-65`), where [reply] is
     * `once` | `always` | `reject` (empty when an older server omits the field).
     */
    data class PermissionReply(val requestID: String, val reply: String)

    /**
     * One resolved question ask: `question.replied`'s `{requestID, answers}` or
     * `question.rejected`'s `{requestID}` (`v1/question.ts:47-60`). [answers]
     * carries one label array per question, in question order; [rejected] marks
     * the dismiss path, which carries no answers at all.
     */
    data class QuestionResolution(
        val requestID: String,
        val answers: List<List<String>> = emptyList(),
        val rejected: Boolean = false,
    )

    /**
     * Wave 1 v2 form ask (`Form.Info{id:frm_, sessionID, title, fields[]}`).
     * Fields stay raw JSON: Wave 2 owns the 6 field types + `when` linkage.
     * `sessionID` may be `"global"` (MCP elicitation sentinel) — the dock must
     * not assume every form belongs to the tracked session.
     */
    data class FormAsk(
        val id: String,
        val sessionID: String,
        val title: String,
        val fieldsJson: String = "[]",
    )

    // ------------------------------------------------------------------ events

    /**
     * Server-level **invalidation signals** (E2): wire events that carry no chat
     * state of their own but tell the client one of its read models went stale.
     *
     * Before this enum `parseEvent` dropped them in its `else` branch
     * (`research/audit_api_events.md` §B-1 #1/#3/#16/#26/#27/#36), so nothing
     * outside the store could react: the model/agent pickers needed a re-entry
     * to pick up a catalog change, Home never learned a project or session list
     * changed, the branch chip and the review panel never invalidated.
     *
     * [Event.ServerSignal] is the transport. `reduce` leaves [ChatState]
     * untouched; the owner (`ChatViewModel`) maps each entry onto a revision
     * flow or a catalog re-read.
     */
    enum class Signal(val wireType: String) {
        /** `provider.ts:26` — the provider catalog changed. */
        PROVIDER_UPDATED("provider.updated"),

        /** `model.ts:12` — the model catalog changed. */
        MODEL_UPDATED("model.updated"),

        /** `agent.ts:11` — the agent catalog changed. */
        AGENT_UPDATED("agent.updated"),

        /** `config.ts:134` — `GET /config` (default model, agent config) changed. */
        CONFIG_UPDATED("config.updated"),

        /** `project.ts:59` — the project changed (payload = `Project.Info`). */
        PROJECT_UPDATED("project.updated"),

        /** `vcs-event.ts:8` — the current branch changed. */
        VCS_BRANCH_UPDATED("vcs.branch.updated"),

        /** `v1/filesystem.ts:7` — `{file}` was written. */
        FILE_EDITED("file.edited"),

        /** `v1/session.ts:573` — a session was created (any directory). */
        SESSION_CREATED("session.created"),

        /** `v1/session.ts:589` — a session was deleted. */
        SESSION_DELETED("session.deleted"),

        /** `pty.ts:34-37` — a PTY appeared / changed / exited / was removed. */
        PTY_CREATED("pty.created"),
        PTY_UPDATED("pty.updated"),
        PTY_EXITED("pty.exited"),
        PTY_DELETED("pty.deleted"),
        ;

        companion object {
            private val BY_WIRE_TYPE = entries.associateBy { it.wireType }

            /** `null` for a type this store does not model (keeps `else -> null`). */
            fun ofWireType(type: String): Signal? = BY_WIRE_TYPE[type]
        }
    }

    sealed interface Event {
        data class Snapshot(val messages: List<UiMessage>) : Event
        data class MessageUpdated(val message: UiMessage) : Event
        data class PartUpdated(val messageID: String, val part: UiPart) : Event
        data class PartDelta(val messageID: String, val partID: String, val field: String, val delta: String) : Event
        data class MessageRemoved(val messageID: String) : Event

        /** `message.part.removed` — the server retracted one part of a message. */
        data class PartRemoved(val messageID: String, val partID: String) : Event

        /**
         * `session.status` / `session.idle` / `session.error` mapped onto the
         * run state. [errorDetail] carries the raw `session.error` JSON and
         * [retry] the `session.status` retry fields; both default to `null`, so
         * a locally produced status change (send, abort, idle backstop) also
         * **clears** them.
         */
        data class RunStatusChanged(
            val status: RunStatus,
            val error: String? = null,
            val errorDetail: String? = null,
            val retry: RetryInfo? = null,
        ) : Event

        /** `permission.asked` — a new (or updated) pending permission request. */
        data class PermissionAsked(val request: OpenCodeClient.PermissionRequest) : Event

        /**
         * `permission.replied` — drop the answered request from the pending list
         * and remember *how* it was answered ([reply]: `once` | `always` |
         * `reject`; empty when the server omits the field).
         */
        data class PermissionReplied(val requestID: String, val reply: String = "") : Event

        /** `GET /permission` merge: the server's authoritative pending list. */
        data class PermissionSnapshot(val requests: List<OpenCodeClient.PermissionRequest>) : Event

        /** `question.asked` — a new (or updated) pending question request. */
        data class QuestionAsked(val request: OpenCodeClient.QuestionRequest) : Event

        /**
         * `question.replied` / `question.rejected` — both remove the request
         * (`{sessionID, requestID}`; replied also carries `answers: string[][]`,
         * rejected sets [rejected] instead).
         */
        data class QuestionResolved(
            val requestID: String,
            val answers: List<List<String>> = emptyList(),
            val rejected: Boolean = false,
        ) : Event

        /** `GET /question` merge: the server's authoritative pending list. */
        data class QuestionSnapshot(val requests: List<OpenCodeClient.QuestionRequest>) : Event

        /**
         * Wave 1 v2: `form.created{form}` — a new (or updated) pending form.
         * Wave 2 renders the dock; the store merge mirrors `QuestionAsked`
         * (session gate + re-ask retires nothing — forms carry no answer yet).
         */
        data class FormCreated(val form: FormAsk) : Event

        /**
         * Wave 1 v2: `form.replied{id}` / `form.cancelled{id}` — both retire
         * the form (replied also records the answer server-side; the store
         * keeps no answer matrix — Wave 2 owns form values).
         */
        data class FormResolved(val formID: String) : Event

        /** `GET …/form` merge: the server's authoritative pending form list. */
        data class FormSnapshot(val forms: List<FormAsk>) : Event

        /**
         * `session.diff` — full replacement of the session's changed-files
         * list (`{sessionID, diff: FileDiff[]}`, `research/04` §2.4). Also
         * emitted locally after a `GET /session/:id/diff` refresh.
         */
        data class SessionDiff(val sessionID: String, val diff: List<OpenCodeClient.FileDiff>) : Event

        /** `todo.updated` — full replacement of the session's todo list. */
        data class TodoUpdated(val sessionID: String, val todos: List<OpenCodeClient.TodoInfo>) : Event

        /**
         * E2: a server-level invalidation signal ([Signal]). Consumed by the
         * owner, never by [reduce] — the chat state is returned unchanged, so
         * this event can travel through `parseEvent` without a second parser.
         */
        data class ServerSignal(
            val signal: Signal,
            /** Session the signal is about; `null` when the payload has none. */
            val sessionID: String? = null,
            /**
             * The event's own subject when it has one: the project id
             * (`project.updated`), the PTY id (`pty.*`) or the written path
             * (`file.edited`). `null` when the payload carries none.
             */
            val subject: String? = null,
        ) : Event
    }

    fun reduce(state: ChatState, event: Event): ChatState = when (event) {
        is Event.Snapshot -> {
            val merged = state.messages.toMutableList()
            for (incoming in event.messages) upsert(merged, incoming)
            state.copy(messages = merged.sortedBy { it.timeCreated })
        }

        is Event.MessageUpdated -> {
            val merged = state.messages.toMutableList()
            upsert(merged, event.message)
            state.copy(messages = merged.sortedBy { it.timeCreated })
        }

        is Event.PartUpdated -> {
            val merged = state.messages.toMutableList()
            val index = merged.indexOfFirst { it.id == event.messageID }
            if (index >= 0) {
                val existing = merged[index]
                val parts = existing.parts.toMutableList()
                val partIndex = parts.indexOfFirst { it.id == event.part.id }
                if (partIndex >= 0) parts[partIndex] = event.part else parts.add(event.part)
                merged[index] = existing.copy(parts = parts)
            } else {
                // Deltas may arrive before the message envelope; hold the part
                // under a synthetic envelope so nothing is dropped.
                merged.add(
                    UiMessage(
                        id = event.messageID,
                        role = "assistant",
                        timeCreated = System.currentTimeMillis(),
                        parts = listOf(event.part),
                    ),
                )
            }
            state.copy(messages = merged.sortedBy { it.timeCreated })
        }

        is Event.PartDelta -> applyPartDelta(state, event)

        is Event.MessageRemoved -> state.copy(
            messages = state.messages.filterNot { it.id == event.messageID },
        )

        // Drop the retracted part; the enclosing message keeps its other parts
        // and is removed entirely only when it had this one (upstream drops
        // empty assistant messages the same way).
        is Event.PartRemoved -> state.copy(
            messages = state.messages.mapNotNull { message ->
                if (message.id != event.messageID) {
                    message
                } else {
                    val parts = message.parts.filterNot { it.id == event.partID }
                    if (parts.isEmpty() && parts.size != message.parts.size) null
                    else message.copy(parts = parts)
                }
            },
        )

        // A retry is a *running* state; an idle/busy/error status change (or one
        // produced locally) must clear the countdown or it would outlive the
        // retry it describes.
        is Event.RunStatusChanged -> state.copy(
            runStatus = event.status,
            error = event.error,
            errorDetail = event.errorDetail,
            retry = event.retry.takeIf { event.status == RunStatus.RUNNING },
        )

        is Event.PermissionAsked ->
            if (event.request.id.isEmpty() || !state.tracksSession(event.request.sessionID)) {
                state
            } else {
                val merged = state.pendingPermissions.toMutableList()
                val index = merged.indexOfFirst { it.id == event.request.id }
                if (index >= 0) merged[index] = event.request else merged.add(event.request)
                state.copy(
                    pendingPermissions = merged,
                    // An ask that is pending again retires its old answer: the
                    // only way this happens is a re-ask or the rollback of a
                    // failed reply (`ChatViewModel.replyPermission`), and in both
                    // cases the recorded reply no longer describes the request.
                    permissionReplies = state.permissionReplies.filterNot { it.requestID == event.request.id },
                )
            }

        // Removal keys on the globally unique requestID alone; a request from
        // another session never entered this list in the first place.
        //
        // E3: the answer is recorded **only** for a request this screen actually
        // had pending. That keeps the record bounded to its own asks, makes a
        // replayed frame a no-op (idempotent reduce, same rule as before) and
        // leaves a foreign session's reply invisible here — exactly like its ask.
        is Event.PermissionReplied ->
            if (state.pendingPermissions.none { it.id == event.requestID }) {
                state
            } else {
                state.copy(
                    pendingPermissions = state.pendingPermissions.filterNot { it.id == event.requestID },
                    permissionReplies = state.permissionReplies.boundedAppend(
                        PermissionReply(event.requestID, event.reply),
                    ),
                )
            }

        is Event.PermissionSnapshot -> state.copy(
            pendingPermissions = event.requests.filter {
                it.id.isNotEmpty() && state.tracksSession(it.sessionID)
            },
        )

        is Event.QuestionAsked ->
            if (event.request.id.isEmpty() || !state.tracksSession(event.request.sessionID)) {
                state
            } else {
                val merged = state.pendingQuestions.toMutableList()
                val index = merged.indexOfFirst { it.id == event.request.id }
                if (index >= 0) merged[index] = event.request else merged.add(event.request)
                state.copy(
                    pendingQuestions = merged,
                    // Same rule as PermissionAsked: pending again ⇒ the previous
                    // resolution is stale (re-ask, or a failed reply rolled back).
                    questionResolutions = state.questionResolutions.filterNot { it.requestID == event.request.id },
                )
            }

        // Same removal rule as PermissionReplied: requestID alone is globally
        // unique, and foreign sessions never entered this list. E3 records the
        // answers / dismissal for the asks that *were* pending here.
        is Event.QuestionResolved ->
            if (state.pendingQuestions.none { it.id == event.requestID }) {
                state
            } else {
                state.copy(
                    pendingQuestions = state.pendingQuestions.filterNot { it.id == event.requestID },
                    questionResolutions = state.questionResolutions.boundedAppend(
                        QuestionResolution(event.requestID, event.answers, event.rejected),
                    ),
                )
            }

        is Event.QuestionSnapshot -> state.copy(
            pendingQuestions = event.requests.filter {
                it.id.isNotEmpty() && state.tracksSession(it.sessionID)
            },
        )

        // Wave 1 v2 forms: same session gate as the question dock. A `"global"`
        // form (MCP elicitation sentinel) never matches a tracked session, so
        // it stays out of this screen's list — Wave 2 surfaces those globally.
        is Event.FormCreated ->
            if (event.form.id.isEmpty() || !state.tracksSession(event.form.sessionID)) {
                state
            } else {
                val merged = state.pendingForms.toMutableList()
                val index = merged.indexOfFirst { it.id == event.form.id }
                if (index >= 0) merged[index] = event.form else merged.add(event.form)
                state.copy(pendingForms = merged)
            }

        is Event.FormResolved ->
            if (state.pendingForms.none { it.id == event.formID }) {
                state
            } else {
                state.copy(pendingForms = state.pendingForms.filterNot { it.id == event.formID })
            }

        is Event.FormSnapshot -> state.copy(
            pendingForms = event.forms.filter {
                it.id.isNotEmpty() && state.tracksSession(it.sessionID)
            },
        )

        // Full-replacement lists, gated like PermissionAsked: an event whose
        // session differs from the tracked one must not touch this state
        // (global stream). An empty event sessionID carries no routing info,
        // so it passes — same rule as `acceptsSession`.
        is Event.SessionDiff ->
            if (event.sessionID.isNotEmpty() && !state.tracksSession(event.sessionID)) {
                state
            } else {
                state.copy(diff = event.diff)
            }

        is Event.TodoUpdated ->
            if (event.sessionID.isNotEmpty() && !state.tracksSession(event.sessionID)) {
                state
            } else {
                state.copy(todos = event.todos)
            }

        // E2: an invalidation signal is not chat state — the owner reads the
        // mapped revision flow / catalog re-read instead, so the transcript is
        // returned untouched. (Kept here so `reduce` stays exhaustive.)
        is Event.ServerSignal -> state
    }

    /**
     * Appends [item] and keeps only the newest [MAX_RESOLVED_REQUESTS] entries —
     * the E3 reply/answer records are display history with no replay value, so
     * a long session must not grow them without bound.
     */
    private fun <T> List<T>.boundedAppend(item: T): List<T> =
        (this + item).takeLast(MAX_RESOLVED_REQUESTS)

    /** `sessionID == null` means "no session tracked yet" → accept everything. */
    private fun ChatState.tracksSession(id: String): Boolean = sessionID == null || sessionID == id

    private fun upsert(target: MutableList<UiMessage>, message: UiMessage) {
        val index = target.indexOfFirst { it.id == message.id }
        if (index >= 0) {
            val existing = target[index]
            target[index] = message.copy(parts = mergeParts(existing.parts, message.parts))
        } else {
            target.add(message)
        }
    }

    /**
     * `message.part.delta` is a **string append**: `part[field] += delta`
     * (`v1/session.ts:632-641`, `field: Schema.String`). The server only emits
     * deltas for a part it has already announced via `*-start`/`part.updated`,
     * so a delta whose message or part is missing is dropped (matches the server
     * dropping orphans). Keeps the current message order; appends never re-sort.
     *
     * E3: every string field [UiPart] models is addressable, not just `text` —
     * `text` and `reasoning` (a reasoning part carries its body in `text`,
     * `v1/session.ts:118-128`), a tool part's `state.output` / `state.error`, and
     * the file part's `mime` / `filename` / `url`. Any other field (the old
     * `toolInput` case, an object-valued field, a future field) is dropped, so an
     * unknown field can never corrupt a part.
     */
    private fun applyPartDelta(state: ChatState, event: Event.PartDelta): ChatState {
        val merged = state.messages.toMutableList()
        val messageIndex = merged.indexOfFirst { it.id == event.messageID }
        if (messageIndex < 0) return state
        val message = merged[messageIndex]
        val parts = message.parts.toMutableList()
        val partIndex = parts.indexOfFirst { it.id == event.partID }
        if (partIndex < 0) return state
        parts[partIndex] = withFieldDelta(parts[partIndex], event.field, event.delta) ?: return state
        merged[messageIndex] = message.copy(parts = parts)
        return state.copy(messages = merged)
    }

    /**
     * `part[field] += delta` on the [UiPart] field that models [field];
     * `null` when the store has no string field for it (the caller then leaves
     * the whole state untouched).
     */
    private fun withFieldDelta(part: UiPart, field: String, delta: String): UiPart? = when (field) {
        "text" -> part.copy(text = part.text + delta)
        "reasoning" -> part.copy(text = part.text + delta)
        "state.output", "output" -> part.copy(toolOutput = appendToolText(part.toolOutput, delta))
        "state.error", "error" -> part.copy(toolError = appendToolText(part.toolError, delta))
        "mime" -> part.copy(mime = (part.mime.orEmpty() + delta).takeIf { it.isNotEmpty() })
        "filename" -> part.copy(filename = (part.filename.orEmpty() + delta).takeIf { it.isNotEmpty() })
        "url" -> part.copy(url = (part.url.orEmpty() + delta).takeIf { it.isNotEmpty() })
        else -> null
    }

    /**
     * Tool text is capped ([capToolText]) because it feeds DiffUtil on every
     * stream tick; an output *delta stream* must not grow past that cap either,
     * so once it is reached further deltas are ignored — their content could
     * never be displayed anyway.
     */
    private fun appendToolText(current: String?, delta: String): String {
        val base = current.orEmpty()
        if (base.length >= MAX_TOOL_TEXT_CHARS) return base
        return capToolText(base + delta)
    }

    private fun mergeParts(existing: List<UiPart>, incoming: List<UiPart>): List<UiPart> {
        val merged = existing.toMutableList()
        for (part in incoming) {
            val index = merged.indexOfFirst { it.id == part.id }
            if (index >= 0) merged[index] = part else merged.add(part)
        }
        return merged
    }

    // ------------------------------------------------------------------ json

    fun parseSnapshot(body: String): List<UiMessage> = try {
        OpenCodeClient.parseJsonArray(body).mapNotNull { parseMessage(it) }
    } catch (_: JSONException) {
        emptyList()
    }

    fun parseMessage(obj: JSONObject): UiMessage? {
        val info = obj.optJSONObject("info") ?: obj
        val id = info.optString("id", "")
        if (id.isEmpty()) return null
        val partsJson = obj.optJSONArray("parts") ?: info.optJSONArray("parts") ?: JSONArray()
        val parts = (0 until partsJson.length()).mapNotNull { i ->
            partsJson.optJSONObject(i)?.let { parsePart(it) }
        }
        val time = info.optJSONObject("time")
        return UiMessage(
            id = id,
            role = info.optString("role", "user"),
            timeCreated = time?.optLong("created", 0L) ?: 0L,
            parts = parts,
            tokens = OpenCodeClient.tokenTotal(info.optJSONObject("tokens")),
            agent = info.optString("agent", "").takeIf { it.isNotEmpty() },
            model = messageModel(info),
            timeCompleted = time?.optLong("completed", 0L)?.takeIf { it > 0L },
            interrupted = isInterrupted(info.optJSONObject("error")),
        )
    }

    /**
     * `"providerID/modelID"` of a message: the assistant carries the pair flat
     * (`v1/session.ts:462-463`), the user carries `model:{providerID,modelID}`
     * (`:347-351`). A lone `modelID` is still better than nothing.
     */
    private fun messageModel(info: JSONObject): String? {
        val nested = info.optJSONObject("model")
        val providerID = info.optString("providerID", "")
            .ifEmpty { nested?.optString("providerID", "").orEmpty() }
        val modelID = info.optString("modelID", "")
            .ifEmpty { nested?.optString("modelID", "").orEmpty() }
        if (modelID.isEmpty()) return null
        return if (providerID.isEmpty()) modelID else "$providerID/$modelID"
    }

    /**
     * `isInterrupted` of upstream `timeline/projection.ts:408-410`: an
     * abort/interrupt error marks the turn as user-interrupted. Matches on
     * `name` (v1 `AssistantError` discriminator) and `type` (v2/legacy shape).
     */
    fun isInterrupted(error: JSONObject?): Boolean {
        if (error == null) return false
        val discriminator = error.optString("name", "")
            .ifEmpty { error.optString("type", "") }
            .lowercase()
        return discriminator.contains("abort") || discriminator.contains("interrupt")
    }

    // ------------------------------------------------------------ error payload

    /**
     * Human-readable line of a `session.error` payload.
     *
     * Upstream `AssistantError` (`_upstream/packages/schema/src/v1/session.ts:385-395`)
     * is a discriminated union `{name, data:{…}}` — `ProviderAuthError`,
     * `APIError`, `MessageOutputLengthError`, `MessageAbortedError`,
     * `StructuredOutputError`, `ContextOverflowError`, `ContentFilterError`,
     * `UnknownError` — and the human text lives in `data.message` (`:38-63`).
     * Reading the payload with `optString("error")` stringified the whole
     * object, which is what made the error card a wall of JSON.
     *
     * Order: `data.message` → `name` (a field-less member such as
     * `MessageOutputLengthError` would otherwise show nothing) → `type` (the
     * v2 `SessionError` discriminator — a field-less v2 error carries only
     * `type`) → a bare top-level `message` (tolerates an unwrapped payload) →
     * the string itself when the server sent a plain string. `null` when
     * nothing is readable; the caller substitutes its own fallback.
     */
    fun errorMessageOf(raw: Any?): String? = when (raw) {
        null, JSONObject.NULL -> null
        is String -> raw.takeIf { it.isNotBlank() }
        is JSONObject -> raw.optJSONObject("data")
            ?.optString("message", "")
            ?.takeIf { it.isNotBlank() }
            ?: raw.optString("name", "").takeIf { it.isNotBlank() }
            ?: raw.optString("type", "").takeIf { it.isNotBlank() }
            ?: raw.optString("message", "").takeIf { it.isNotBlank() }

        else -> null
    }

    /**
     * The untouched `session.error` payload as JSON text, so unwrapping the
     * message never loses provider detail (`statusCode`, `responseBody`,
     * `isRetryable`, …). `null` for a plain-string error, whose text is already
     * the message.
     */
    fun errorRawJsonOf(raw: Any?): String? = (raw as? JSONObject)?.toString()

    fun parsePart(obj: JSONObject): UiPart {
        val state = obj.optJSONObject("state")
        val output = state?.opt("output")
        return UiPart(
            id = obj.optString("id", "part:${obj.hashCode()}"),
            type = obj.optString("type", "unknown"),
            text = obj.optString("text", ""),
            toolName = obj.optString("tool", "").takeIf { it.isNotEmpty() },
            toolStatus = state?.optString("status", "")?.takeIf { it.isNotEmpty() },
            mime = obj.optString("mime", "").takeIf { it.isNotEmpty() },
            filename = obj.optString("filename", "").takeIf { it.isNotEmpty() },
            url = obj.optString("url", "").takeIf { it.isNotEmpty() },
            toolInput = state?.optJSONObject("input")?.toString()?.let(::capToolText),
            toolOutput = when (output) {
                null, JSONObject.NULL -> null
                is String -> output.takeIf { it.isNotEmpty() }?.let(::capToolText)
                else -> output.toString().takeIf { it.isNotEmpty() }?.let(::capToolText)
            },
            toolError = state?.optString("error", "")?.takeIf { it.isNotEmpty() },
            // Route C: keep the payload for the part types that have no
            // first-class field (see [UiPart.rawJson]).
            rawJson = obj.takeIf { it.optString("type") in UiPart.RAW_JSON_TYPES }?.toString(),
        )
    }

    /**
     * Tool bodies feed DiffUtil comparisons every stream tick; huge outputs
     * would make every 80ms rebind compare megabytes. The card scrolls, so a
     * cap only trims what the user cannot reasonably read anyway.
     */
    private fun capToolText(value: String): String =
        if (value.length <= MAX_TOOL_TEXT_CHARS) value else value.take(MAX_TOOL_TEXT_CHARS) + "\n… (truncated)"

    fun parseEvent(type: String, data: JSONObject): Event? {
        // Wire format is `{"type":"...","properties":{...}}` (vendored SDK
        // reads t.properties.part / t.properties.info); tolerate flat payloads.
        val p = data.optJSONObject("properties") ?: data
        return when (type) {
            "message.updated", "message.updated.v2" ->
                p.optJSONObject("info")?.let { info ->
                    parseMessage(JSONObject().put("info", info))?.let { Event.MessageUpdated(it) }
                }

            "message.part.updated" -> {
                val part = p.optJSONObject("part") ?: return null
                val messageID = p.optString("messageID", "").ifEmpty { part.optString("messageID", "") }
                if (messageID.isEmpty()) null else Event.PartUpdated(messageID, parsePart(part))
            }

            "message.part.delta" -> {
                val messageID = p.optString("messageID", "").ifEmpty { p.optString("sessionID", "") }
                val partID = p.optString("partID", "")
                if (messageID.isEmpty() || partID.isEmpty()) null
                else Event.PartDelta(
                    messageID,
                    partID,
                    p.optString("field", "text").ifEmpty { "text" },
                    p.optString("delta", ""),
                )
            }

            "message.removed" -> p.optString("messageID", "")
                .takeIf { it.isNotEmpty() }
                ?.let { Event.MessageRemoved(it) }

            // `message.part.removed` `{sessionID, messageID, partID}`: the
            // server retracts a part (e.g. a superseded delta/step). Without
            // this branch the stale part stays on screen forever.
            "message.part.removed" -> {
                val messageID = p.optString("messageID", "")
                val partID = p.optString("partID", "")
                if (messageID.isEmpty() || partID.isEmpty()) null
                else Event.PartRemoved(messageID, partID)
            }

            // `session.status` supersedes the deprecated `session.idle`; busy /
            // retry mean the run is still alive, idle means it finished.
            //   `{type:"idle"} | {type:"busy"} | {type:"retry", attempt,
            //    message, next, action?}`
            // (`_upstream/packages/schema/src/session-status-event.ts:9-32`).
            // The retry frame's `message` is already human-readable prose, so it
            // rides on [RetryInfo] instead of `error`: `error != null` makes the
            // timeline mount the red error card, and a retry is not a failure
            // the user has to act on.
            "session.status" -> {
                val status = p.optJSONObject("status") ?: return null
                when (status.optString("type", "")) {
                    "busy" -> Event.RunStatusChanged(RunStatus.RUNNING)
                    "retry" -> Event.RunStatusChanged(RunStatus.RUNNING, retry = RetryInfo.fromJson(status))
                    "idle" -> Event.RunStatusChanged(RunStatus.IDLE)
                    else -> null
                }
            }

            // `session.error` `{sessionID?, error: AssistantError}`
            // (`v1/session.ts:651-657`). `error` is a discriminated union object
            // `{name, data:{…}}` (`:385-395`), **not** a string: reading it with
            // `optString` stringified the whole object and the error card showed
            // raw JSON. [errorMessageOf] unwraps it; the payload itself is kept
            // verbatim in `errorDetail`.
            "session.error" -> {
                val raw = p.opt("error")
                Event.RunStatusChanged(
                    RunStatus.ERROR,
                    errorMessageOf(raw) ?: "unknown error",
                    errorDetail = errorRawJsonOf(raw),
                )
            }

            "session.idle" -> Event.RunStatusChanged(RunStatus.IDLE)

            // `permission.asked` properties ARE the `PermissionV1.Request`
            // fields (`packages/schema/src/v1/permission.ts:61`); replied
            // carries `{sessionID, requestID, reply}` (`:62-65`).
            "permission.asked" -> OpenCodeClient.PermissionRequest.fromJson(p)
                .takeIf { it.id.isNotEmpty() }
                ?.let { Event.PermissionAsked(it) }

            "permission.replied" -> p.optString("requestID", "")
                .takeIf { it.isNotEmpty() }
                ?.let { Event.PermissionReplied(it, p.optString("reply", "")) }

            // `question.asked` properties ARE the `QuestionV1.Request` fields
            // (`packages/schema/src/v1/question.ts:35-40`); replied carries
            // `{sessionID, requestID, answers: string[][]}`, rejected only
            // `{sessionID, requestID}` (`:47-60`).
            "question.asked" -> OpenCodeClient.QuestionRequest.fromJson(p)
                .takeIf { it.id.isNotEmpty() }
                ?.let { Event.QuestionAsked(it) }

            "question.replied" -> p.optString("requestID", "")
                .takeIf { it.isNotEmpty() }
                ?.let { Event.QuestionResolved(it, answers = questionAnswers(p)) }

            "question.rejected" -> p.optString("requestID", "")
                .takeIf { it.isNotEmpty() }
                ?.let { Event.QuestionResolved(it, rejected = true) }

            // `session.diff` properties `{sessionID, diff: FileDiff[]}`
            // (`packages/schema/src/v1/session.ts:643-649`), `todo.updated`
            // `{sessionID, todos[]}` (`packages/schema/src/session-todo.ts`).
            "session.diff" -> p.optJSONArray("diff")
                ?.let { Event.SessionDiff(p.optString("sessionID", ""), OpenCodeClient.FileDiff.listFromJson(it)) }

            "todo.updated" -> p.optJSONArray("todos")
                ?.let { Event.TodoUpdated(p.optString("sessionID", ""), OpenCodeClient.TodoInfo.listFromJson(it)) }

            // E2: the server-level invalidation signals (`provider.updated` /
            // `model.updated` / `agent.updated` / `config.updated`,
            // `project.updated`, `vcs.branch.updated`, `file.edited`,
            // `session.created` / `session.deleted`, `pty.*`). They used to fall
            // through this `else` and vanish; `parseEvent` now hands them to the
            // owner as [Event.ServerSignal] (chat state untouched) so catalogs,
            // list revisions and notifications can react without a re-entry.
            else -> Signal.ofWireType(type)?.let { signal ->
                Event.ServerSignal(signal, eventSessionId(data), signalSubject(signal, p))
            }
        }
    }

    /**
     * `question.replied`'s `answers: string[][]` (`v1/question.ts:47-53`): one
     * label array per question, in question order. A malformed entry is skipped
     * rather than failing the whole frame — the dock answers with the same shape.
     */
    private fun questionAnswers(p: JSONObject): List<List<String>> {
        val outer = p.optJSONArray("answers") ?: return emptyList()
        return (0 until outer.length()).mapNotNull { i ->
            val inner = outer.optJSONArray(i) ?: return@mapNotNull null
            (0 until inner.length()).mapNotNull { j ->
                inner.optString(j, "").takeIf { it.isNotEmpty() }
            }
        }
    }

    /**
     * Subject id of a [Signal], when its payload carries one: the project id
     * (`project.updated` = `Project.Info` fields, `project.ts:38-59`), the PTY id
     * (`pty.ts:34-37`: `{info}` on created/updated, `{id}` on exited/deleted) or
     * the written path (`file.edited` = `{file}`, `v1/filesystem.ts:7`).
     */
    private fun signalSubject(signal: Signal, p: JSONObject): String? {
        val raw = when (signal) {
            Signal.PROJECT_UPDATED -> p.optString("id", "")
            Signal.PTY_CREATED, Signal.PTY_UPDATED ->
                p.optJSONObject("info")?.optString("id", "").orEmpty().ifEmpty { p.optString("id", "") }

            Signal.PTY_EXITED, Signal.PTY_DELETED -> p.optString("id", "")
            Signal.FILE_EDITED -> p.optString("file", "")
            else -> ""
        }
        return raw.takeIf { it.isNotEmpty() }
    }

    /**
     * Session id of an event envelope, whichever shape it uses.
     *
     * v1 bodies only carry the owning session as `properties.sessionID` /
     * `properties.info.sessionID` / `properties.part.sessionID`; `properties.id`
     * is the *session* id only on `session.*` events whose body is a session
     * object (`{sessionID, info:{id…}}`) — reading `id` first (as this used to)
     * returned null for every `session.status` / `session.idle` / `message.*`
     * frame, so the snapshot backstop in `maybeRefreshOnSession` never fired.
     * Delegates to [eventSessionId] and keeps the `properties.id` probe last so
     * `session.created`-style payloads still resolve.
     */
    fun sessionIdOf(data: JSONObject): String? {
        val p = data.optJSONObject("properties") ?: data
        return eventSessionId(data)
            ?: p.optJSONObject("info")?.optString("id", "")?.takeIf { it.isNotEmpty() }
            ?: p.optString("id", "").takeIf { it.isNotEmpty() }
    }

    // ------------------------------------------------------------ global stream

    /**
     * Unwraps a `/global/event` frame (`{directory, payload:{id,type,properties}}`,
     * research/04 §2.2) to its inner event envelope. Returns `null` when the
     * frame is already instance-shaped (`{id,type,properties}`, `/event`).
     */
    fun unwrapGlobal(data: JSONObject): JSONObject? =
        data.optJSONObject("payload")?.takeIf { !it.optString("type", "").isEmpty() }

    /**
     * Session that owns this (already unwrapped) event, whichever envelope shape
     * it uses: `properties.sessionID` (part.delta / session.status / permission.*),
     * `properties.info.sessionID` (message.updated), `properties.part.sessionID`
     * (message.part.updated). `null` = the event carries no session routing
     * (server.heartbeat, session.updated's `{sessionID, info}`… wait, that one
     * does — the point stands for genuinely session-less frames).
     */
    fun eventSessionId(data: JSONObject): String? {
        val p = data.optJSONObject("properties") ?: data
        return p.optString("sessionID", "")
            .ifEmpty { p.optJSONObject("info")?.optString("sessionID", "").orEmpty() }
            .ifEmpty { p.optJSONObject("part")?.optString("sessionID", "").orEmpty() }
            .ifEmpty { null }
    }

    /**
     * Gate for the global stream: with a tracked [currentID], an event addressed
     * to another session must never touch this screen's state (message parts,
     * run status). Events without routing info pass — they are server-level.
     */
    fun acceptsSession(currentID: String?, data: JSONObject): Boolean {
        val owner = eventSessionId(data) ?: return true
        return currentID == null || currentID == owner
    }
}
