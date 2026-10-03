package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.notify.BannerPolicy
import com.xiufeigo.opencodeshell.ui.session.timeline.ToolPresentation
import com.xiufeigo.opencodeshell.v2.TransportRetry
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Pure-Kotlin chat state store (JVM-testable). Merges a REST snapshot with
 * SSE deltas using idempotent upserts: snapshot entries never clobber newer
 * streamed parts received during the reconnect window.
 */
object ChatStore {

    private const val MAX_TOOL_TEXT_CHARS = 16_000

    /**
     * How many E3 permission-reply records a state keeps
     * (newest win). They are display history for asks this screen answered, not
     * session state, so a long-running session must not grow them forever.
     */
    private const val MAX_RESOLVED_REQUESTS = 20

    /**
     * `role` of a client-side **failure row** (see [appendErrorRow]): one row per
     * failed turn, appended to [ChatState.messages] when a `session.error`
     * frame lands. Upstream models the same fact on the server message
     * (`SessionMessageAssistant.error`) and renders it as one `TimelineRow.Error`
     * per failed turn, keyed `error:<userMessageID>`
     * (`_upstream/packages/session-ui/src/timeline/projection.ts:357-359`,
     * `timeline/timeline-row.ts:60-63,99-100`) — so two consecutive failures are
     * **two** cards in turn order, never one card overwritten by the next.
     *
     * The row rides the message list (not a separate field) because the
     * timeline's only error channel is `messages`; a distinct [role][UiMessage.role]
     * keeps every role-filtered consumer untouched (the rail counts `user`
     * rows, the context ring sums `assistant` tokens, fork/actions list `user`
     * messages, the review/export panes read the server transfer).
     */
    const val ERROR_ROLE = "error"

    /**
     * Client-side role of a **parked steer** row: a follow-up the server accepted
     * into `/session/:id/inbox` with `delivery:"steer"` but has not delivered to
     * the transcript yet (the running turn is still draining).
     *
     * Why it rides the message list: a parked steer is invisible everywhere else
     * on the device — the composer's queue panel shows only `delivery:"queue"`
     * items (`session/composer/queue.ts:80-84`) and the transcript has no server
     * row for it at all, so the user's own question vanished until delivery
     * (device report 2026-09-25: a `/btw`-style side ask sat in the inbox with
     * zero feedback for as long as the hung turn lasted). Upstream projects the
     * same admission into the timeline as a user message and moves pending
     * steers to the **tail** of the visible list
     * (`_upstream/packages/app/src/session/timeline/controller-projection.ts:12-33`),
     * which is what this role reproduces on the flatter Android model.
     *
     * A distinct role keeps every role-filtered consumer untouched (the same
     * contract as [ERROR_ROLE]): the rail counts `user` ticks, fork / revert /
     * the ⋮ menu list `role == "user"`, the context ring sums `assistant`
     * tokens, and export/review read the server transfer rather than this list.
     * [isPendingRow] / [isClientRow] are the predicates that keep the transcript
     * rules (upsert, "newest turn", separators) from mistaking it for a message.
     *
     * The row carries the **admission id** (`msg_…`, the same id the server will
     * publish once it delivers), so the delivered message replaces it in place
     * instead of duplicating it.
     */
    const val PENDING_ROLE = "pending"

    /**
     * How many failure rows a state keeps (newest win) — the ring around the
     * client-side failure history. Past failures are display history, not
     * session state, so a session that keeps failing must not grow its
     * transcript without bound; older cards simply fall out of the timeline.
     */
    const val MAX_ERROR_ROWS = 8

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
         * reasoning / file are fully modelled by the fields above; the other
         * eight are not (compaction's `auto` / `overflow` / `tail_start_id`
         * have no field either), and dropping their payload made them
         * unrecoverable in the UI layer. Only those eight carry [rawJson].
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
                "compaction",
            )
        }
    }

    /**
     * One timeline row. Server rows carry `role = "user" | "assistant"` (v2 tags
     * its notice types as assistant); [ERROR_ROLE] marks the client-side failure
     * rows [reduce] appends, whose single part carries the failure in
     * `text` / `rawJson` (see [errorRowText] / [errorRowDetail]).
     */
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
        /**
         * The failure the **server** recorded on this row (`info.error` on v1,
         * `error` on a v2 assistant row), carried verbatim so a failure card
         * survives a session reopen. Upstream reads the same fact off the
         * message (`SessionMessageAssistant.error`) when it projects a turn
         * into a `TimelineRow.Error` (`timeline/projection.ts:355-359`), which
         * is why the client-side failure history ([ERROR_ROLE]) must be
         * rebuildable from a snapshot alone.
         *
         * `null` for a turn that did not fail, and for an interrupted one: an
         * abort is a separator plus the `Interrupted` meta fragment, never an
         * error card ([isInterrupted]).
         */
        val error: UiMessageError? = null,
    )

    /**
     * Message-level failure payload. v2 `SessionError.Error` is
     * `{type, message, status?}` (`_upstream/packages/schema/src/session-error.ts:7-13`);
     * the legacy v1 union `{name, data:{message}}` is read through the same
     * [ChatStore.errorMessageOf]. [raw] is the untouched payload JSON the card
     * shows behind its chevron, exactly what the live `session.error` path
     * carries in `errorDetail` ([ChatStore.errorRawJsonOf]).
     */
    data class UiMessageError(
        /** Human-readable failure line — the card header. */
        val message: String,
        /** Discriminator: v2 `type` (`provider.auth`, …), v1 `name`. */
        val type: String? = null,
        /** HTTP status when the payload carried one (`provider.auth` carries 403). */
        val status: Int? = null,
        /** Untouched payload JSON; `null` when the server sent a plain string. */
        val raw: String? = null,
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
        /**
         * The moment of the next attempt (`next`), 0 when the server omitted
         * it.
         *
         * **Unit caveat:** the schema types `session.status{type:"retry"}.next`
         * as a bare `NonNegativeInt` with no unit
         * (`_upstream/packages/schema/src/session-status-event.ts:13-28`), while
         * its durable sibling `session.retry.scheduled.at`
         * (`session-event.ts:563-572`) and `AssistantRetry.at`
         * (`session-message.ts:204-209`) are epoch millis — and both land in
         * this one field ([V2EventAdapter] maps `next` and `at` here). Upstream
         * renders the status frame as `round((next - now)/1000)`
         * (`session-ui/src/components/session-retry.tsx:16-48`), i.e. an
         * absolute instant, which is what every measured frame carries.
         *
         * [UsageUpsell.retryAfterOf] is therefore defensive: a value below
         * [RELATIVE_NEXT_MAX_MILLIS] that is not ahead of `now` is read as a
         * *delay* and counted from `now`, so a server that answers a relative
         * `next` still gets a countdown instead of a silently dropped one.
         */
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
                    reason = obj.optStr("reason").orEmpty(),
                    provider = obj.optStr("provider").orEmpty(),
                    title = obj.optStr("title").orEmpty(),
                    message = obj.optStr("message").orEmpty(),
                    label = obj.optStr("label").orEmpty(),
                    link = obj.optStr("link"),
                )
            }
        }

        companion object {
            /**
             * Values under this are a retry **delay in millis**, not an
             * epoch-millis instant: 10^12 ms is 2001-09-09, far below every
             * plausible clock reading and far above every plausible retry
             * delay, so the two units cannot be confused (see [RetryInfo.next]).
             */
            const val RELATIVE_NEXT_MAX_MILLIS = 1_000_000_000_000L

            fun fromJson(status: JSONObject): RetryInfo = RetryInfo(
                attempt = status.optInt("attempt", 0),
                next = status.optLong("next", 0L),
                message = status.optStr("message"),
                action = status.optJSONObject("action")?.let(Action::fromJson),
            )
        }
    }

    /**
     * Token Plan / Go upsell behind the usage-exceeded dialog
     * (`_upstream/packages/app/src/session/usage-exceeded-dialogs.tsx`): the
     * `session.status` retry `action` (`free_tier_limit` /
     * `account_rate_limit`) plus the 429/402 quota bucket
     * ([classifyProviderError]) surfaced as dialog state. The dialog shows the
     * server copy verbatim ([message]); the title / button labels come from
     * string resources branched on [reason] (`UsageExceededDialog`), because —
     * like upstream's non-English path — the server copy stays English-only.
     */
    data class UsageUpsell(
        val reason: String,
        val message: String?,
        val retryAfter: String?,
        /**
         * Go `action.link` (subscribe / usage-settings URL) when the upsell
         * came off a retry action; `null` on the generic quota bucket and the
         * error path, whose primary button is a plain close. The dialog opens
         * it in the system browser before dismissing (upstream
         * `DialogUsageExceeded.runAction` → `platform.openExternal`).
         */
        val link: String? = null,
    ) {
        companion object {
            const val REASON_FREE_TIER_LIMIT = "free_tier_limit"
            const val REASON_ACCOUNT_RATE_LIMIT = "account_rate_limit"

            /** Generic 429/402 quota bucket (no Go `action` on the frame). */
            const val REASON_QUOTA_EXHAUSTED = "quota_exhausted"

            /** 429/402 — the quota statuses that raise the dialog off the error path. */
            private val QUOTA_DIALOG_STATUSES = setOf(402, 429)

            /**
             * Upsell for a `session.status{type:retry}` frame: a Go `action`
             * wins; otherwise a quota-classified retry line falls back to the
             * generic bucket (the Token Plan 429s arrive action-less on some
             * servers). `null` = no dialog for this frame — the caller keeps
             * whatever is already showing.
             */
            fun fromRetry(retry: RetryInfo?, nowMillis: Long = System.currentTimeMillis()): UsageUpsell? {
                if (retry == null) return null
                val action = retry.action
                if (action != null && (action.reason == REASON_FREE_TIER_LIMIT || action.reason == REASON_ACCOUNT_RATE_LIMIT)) {
                    return UsageUpsell(
                        reason = action.reason,
                        message = action.message.takeIf { it.isNotBlank() }
                            ?: action.title.takeIf { it.isNotBlank() },
                        retryAfter = retryAfterOf(retry.next, nowMillis),
                        link = action.link,
                    )
                }
                if (classifyProviderError(null, retry.message, null).kind == ProviderErrorKind.QUOTA_EXHAUSTED) {
                    return UsageUpsell(
                        reason = REASON_QUOTA_EXHAUSTED,
                        message = retry.message,
                        retryAfter = retryAfterOf(retry.next, nowMillis),
                    )
                }
                return null
            }

            /**
             * Upsell for a failed turn (`session.error` / prompt-POST line):
             * only the 429/402 quota bucket raises the dialog — anything else
             * is the error card's job. `null` keeps whatever is showing.
             */
            fun fromError(error: String?, errorDetail: String?): UsageUpsell? {
                val classified = classifyStoredError(error, errorDetail)
                if (classified.kind != ProviderErrorKind.QUOTA_EXHAUSTED) return null
                if (classified.status !in QUOTA_DIALOG_STATUSES) return null
                return UsageUpsell(
                    reason = classified.type?.takeIf { it.isNotBlank() } ?: REASON_QUOTA_EXHAUSTED,
                    message = classified.message,
                    retryAfter = null,
                )
            }

            /**
             * Countdown copy for [RetryInfo.next], tolerant of both units the
             * schema leaves ambiguous (see [RetryInfo.next]).
             *
             * An absolute instant only yields a countdown when it is ahead of
             * `now`; a value that is *both* below [RetryInfo.RELATIVE_NEXT_MAX_MILLIS]
             * and not ahead of `now` can therefore only be a delay, and is
             * counted from `now`. Everything else keeps the absolute reading —
             * including a replayed past `at`, which still yields no countdown
             * rather than a two-week one.
             */
            private fun retryAfterOf(next: Long, nowMillis: Long): String? {
                val at = if (next < RetryInfo.RELATIVE_NEXT_MAX_MILLIS && next <= nowMillis) {
                    nowMillis + next
                } else {
                    next
                }
                if (at <= nowMillis) return null
                return "${(at - nowMillis + 999) / 1000}s"
            }
        }
    }

    data class ChatState(
        val messages: List<UiMessage> = emptyList(),
        val runStatus: RunStatus = RunStatus.IDLE,
        /**
         * Human line of the **newest** failure, cleared by the next run frame.
         * The failure *history* is [messages]-resident ([ERROR_ROLE] rows, see
         * [appendErrorRow]) — this pair stays as the live status mirror the
         * legacy banner, the upsell mapper and the timeline's trailing-card
         * fallback read.
         */
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
         * Usage-exceeded upsell for [retry]'s Go action (or a 429/402 quota
         * failure). The session screen shows `UsageExceededDialog` while this
         * is non-null; cleared by `ChatViewModel.dismissUsageUpsell`, a new
         * non-qualifying run end (`idle`), or the next qualifying frame
         * (which replaces it). Unrelated events never resurrect a dismissal.
         */
        val usageUpsell: UsageUpsell? = null,
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
         * Wave 1 v2: pending form asks for [sessionID] (`Form.Info`), ordered
         * by arrival; the Wave 2 form dock shows the first one. v2 has no
         * question REST/events — forms are the carrier (A5 §1.5).
         */
        val pendingForms: List<FormAsk> = emptyList(),
        /**
         * `session.renamed{title}` (`session-event.ts:104-112`) — the tracked
         * session's title follows the rename without waiting for a re-read.
         */
        val sessionTitle: String? = null,
        /**
         * `session.agent.selected{agent}` / `session.model.selected{model}`
         * (`session-event.ts:72-92`) — the session's current selection; the
         * model is `"providerID/modelID"` (`Model.Ref`,
         * `model.ts:26-40` parse). Unset fields keep their previous value
         * (each frame names exactly one of the two). A `sessionVariant` of
         * `"default"`/absent means "no override" (same as `SessionRow.variant`).
         */
        val sessionAgent: String? = null,
        val sessionModel: String? = null,
        val sessionVariant: String? = null,
        /**
         * `session.usage.updated` / `session.usage.recorded`
         * (`session-event.ts:148-168`): session cost in USD (`Money.USD`) and
         * the `TokenUsage.Info` total (`token-usage.ts:15-17`, same flattening
         * as [UiMessage.tokens]).
         */
        val usageCost: Double? = null,
        val usageTokens: Long? = null,
        /**
         * `session.permissions{permissions}` (`session-event.ts:114-122`) —
         * the session's `Permission.Ruleset` verbatim. Kept as raw JSON: the
         * dock models permission *asks*, not the ruleset that produces them.
         */
        val permissionsJson: String? = null,
        /** `session.revert.staged` — the pending revert boundary, if any. */
        val stagedRevert: StagedRevert? = null,
    ) {
        /** Answer recorded for [requestID], or `null` when this screen never had it pending. */
        fun permissionReply(requestID: String): PermissionReply? =
            permissionReplies.lastOrNull { it.requestID == requestID }
    }

    /**
     * `session.revert.staged{revert}`'s `Session.Revert`
     * (`_upstream/packages/schema/src/session-revert.ts:8-14`):
     * `{messageID, partID?, snapshot?, files?}` — the boundary message plus
     * the untouched payload (snapshot id, file diffs) behind the revert UI.
     */
    data class StagedRevert(val messageID: String, val rawJson: String)

    /**
     * One answered permission ask: `permission.replied`'s `{requestID, reply}`
     * (`_upstream/packages/schema/src/v1/permission.ts:62-65`), where [reply] is
     * `once` | `always` | `reject` (empty when an older server omits the field).
     */
    data class PermissionReply(val requestID: String, val reply: String)

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
     * Before this enum the frame parsers dropped them in their `else` branch
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

        /**
         * `v1/session.ts:581` — a session **row** changed (`{sessionID, info}`),
         * i.e. the list's `time.updated` moved.
         *
         * v2 has no such frame: its session rows are written by the events that
         * set `time_updated: event.created` — `session.model.selected` /
         * `session.agent.selected` / `session.permissions` /
         * `session.revert.staged|cleared|committed`
         * (`core/src/session/projector.ts:551,562,579,716,726,761`) — and
         * [com.xiufeigo.opencodeshell.v2.V2EventAdapter] synthesizes this signal
         * from them, so the shell's session list follows the server's ordering
         * the same way on both generations. Only the **list** is stale: the
         * transcript is driven by the durable message frames, and the writes that
         * must *not* count as activity (a turn's terminal, usage, claim
         * bookkeeping) pin `time_updated` to itself
         * (`projector.ts:426`, `session/store.ts:211-241`), so they are
         * deliberately not row updates.
         */
        SESSION_UPDATED("session.updated"),

        /** `pty.ts:34-37` — a PTY appeared / changed / exited / was removed. */
        PTY_CREATED("pty.created"),
        PTY_UPDATED("pty.updated"),
        PTY_EXITED("pty.exited"),
        PTY_DELETED("pty.deleted"),

        /** `credential.ts:18-22` — a credential was saved / became active (integrations + provider catalogs stale). */
        CREDENTIAL_UPDATED("credential.updated"),
        CREDENTIAL_SWITCHED("credential.switched"),

        /** `integration.ts:53` — an integration changed (integration/model/provider lists stale). */
        INTEGRATION_UPDATED("integration.updated"),

        /** `plugin.ts:46` — the loaded plugin set changed. */
        PLUGIN_UPDATED("plugin.updated"),

        /** `worktree.ts:56-64` / `worktree-event.ts:7-21` — project worktree layout changed. */
        WORKTREE_UPDATED("worktree.updated"),
        WORKTREE_RESOLVED("worktree.resolved"),
        WORKTREE_READY("worktree.ready"),
        WORKTREE_FAILED("worktree.failed"),

        /** `persistent-pty.ts:68-69` — a session terminal appeared / was removed. */
        PERSISTENT_PTY_ADDED("persistent-pty.added"),
        PERSISTENT_PTY_REMOVED("persistent-pty.removed"),

        /** `shell.ts:54-56` — a background shell appeared / exited / was removed. */
        SHELL_CREATED("shell.created"),
        SHELL_EXITED("shell.exited"),
        SHELL_DELETED("shell.deleted"),

        /** `websearch.ts:41` — the web-search provider list changed. */
        WEBSEARCH_UPDATED("websearch.updated"),

        /** `reference.ts:8` — the reference catalog changed. */
        REFERENCE_UPDATED("reference.updated"),

        /** `skill.ts:36` / `command.ts:7` — the skill / command catalog changed. */
        SKILL_UPDATED("skill.updated"),
        COMMAND_UPDATED("command.updated"),

        /** `lsp-event.ts:5` — LSP servers changed (diagnostics/completions surface). */
        LSP_UPDATED("lsp.updated"),

        /** `location-event.ts:6` — a location shut down (everything scoped to it is stale). */
        LOCATION_SHUTDOWN("location.shutdown"),

        /**
         * `mcp-event.ts:5-26` — an MCP server's connection status / tool list /
         * resource list settled; the payload's `{server}` (the server *name*,
         * not an id) is the subject. Upstream re-reads the server list on
         * `status.changed` and the resource catalog on `resources.changed`
         * (`client/src/solid/data.ts:1284-1294`); `tools.changed` carries no
         * separate consumer yet and only stales the same server row.
         */
        MCP_STATUS_CHANGED("mcp.status.changed"),
        MCP_TOOLS_CHANGED("mcp.tools.changed"),
        MCP_RESOURCES_CHANGED("mcp.resources.changed"),
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
        /**
         * `session.step.failed` whose error is an abort/interrupt: the live
         * stream's own record that this assistant turn was stopped, carried so
         * the row learns `interrupted` without waiting for a snapshot re-read
         * to win the race against the server's error write (emulator-5556 v73:
         * Stop mid-stream left the user bubble alone — no marker, no partial
         * output — because the flag only ever landed via snapshot).
         *
         * Only the flag fields travel ([error], [timeCompleted]); the parts
         * stay exactly as streamed. A row that streamed nothing renderable is
         * still the Stop residue ([isInterruptedShell]) and leaves instead.
         */
        data class AssistantInterrupted(
            val messageID: String,
            val error: UiMessageError? = null,
            val timeCompleted: Long? = null,
        ) : Event

        /**
         * `session.execution.interrupted{reason:"user"}` — the turn ended by a
         * **Stop**, with no message id to name it by.
         *
         * A Stop pressed before the provider produced anything leaves the
         * server with no assistant row at all (measured 2.0.18/4095: only
         * `session.execution.interrupted` + the durable `idle{outcome:
         * "interrupted"}` row), so the v73 [AssistantInterrupted] carrier —
         * which keys on `session.step.failed`'s `assistantMessageID` — has
         * nothing to name and the turn vanished without a trace
         * (emulator-5556 v74, `t5`: two turns stopped pre-stream, neither the
         * row nor the `Interrupted` divider ever appeared). This event carries
         * the fact the frame does have: the turn is over and it was interrupted.
         */
        data class TurnInterrupted(val timeCompleted: Long? = null) : Event
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

        /**
         * The **link is back**: the global SSE stream re-established
         * (`EventStream.State.ESTABLISHED`), which is this client's own proof
         * that the server is reachable again.
         *
         * v81 BUG-01 (emulator-5558): airplane mode on → a send raised the
         * transport card → airplane mode off → the connection healed, and the
         * card stayed on screen for good (`research/v81_verify/5558/
         * t2_s06_recover_2s.png`, `t2_s07_recover_8s.png`: the stream was back
         * while the session still showed 「Can't connect to this server」, and
         * the composer stayed in that posture). Nothing retired it: the card is
         * rendered from [ChatState.error] / the failure rows, and the only
         * retirement on record was v57's 「a `running` status can only come from
         * a server that answered」 — which needs a *turn* to run, i.e. the user
         * to send again into a screen that looks dead. The recovery itself is
         * the same proof, so it drives the same rule.
         *
         * Deliberately narrow, exactly like [BannerPolicy.staleTransportIds]: only
         * failures that classify as [ProviderErrorKind.TRANSPORT] are retired —
         * a quota, a region block, a provider error or a missing directory keeps
         * its card, because those describe something the link coming back does
         * not change.
         */
        data object TransportRecovered : Event

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

        /**
         * Wave 1 v2: `form.created{form}` — a new (or updated) pending form.
         * Wave 2 renders the dock (v2's question carrier; the v1 question
         * events are gone with the v1 stack) — session gate like
         * [PermissionAsked].
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
         * this event can travel through the v2 adapter without a second parser.
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

        /**
         * `session.renamed{sessionID, title}` (`session-event.ts:104-112`) —
         * the title follows immediately; the adapter also emits
         * [Signal.SESSION_CREATED] so list rows re-read their copy.
         */
        data class SessionRenamed(val sessionID: String, val title: String) : Event

        /**
         * `session.agent.selected{agent, previous?}` /
         * `session.model.selected{model, previous?}` (`session-event.ts:72-92`).
         * Exactly one of [agent] / [model] is set per frame; the unset side is
         * left untouched by [reduce]. [model] is `"providerID/modelID"`.
         * [variant] is the `model.variant` label the frame names, normalized at
         * the adapter (`"default"` → null); a model frame always names its
         * variant state, so the field is authoritative for the selection event.
         */
        data class SessionSelectionChanged(
            val sessionID: String,
            val agent: String? = null,
            val model: String? = null,
            val variant: String? = null,
        ) : Event

        /**
         * `session.usage.updated{cost, tokens}` (ephemeral,
         * `session-event.ts:160-168`) and `session.usage.recorded{source, cost,
         * tokens}` (`:148-158`) — session-level spend. `source`
         * (`title` | `compaction`) is accounting-only and dropped here.
         */
        data class SessionUsageUpdated(
            val sessionID: String,
            val cost: Double,
            val tokens: Long?,
        ) : Event

        /**
         * `session.permissions{permissions}` (`session-event.ts:114-122`) —
         * the session's `Permission.Ruleset` replaced wholesale, verbatim JSON.
         */
        data class SessionPermissionsUpdated(val sessionID: String, val permissionsJson: String) : Event

        /**
         * `session.revert.staged{revert}` (`session-event.ts:632-635`): the
         * `Session.Revert` payload's `messageID` is the boundary; [rawJson]
         * keeps `partID` / `snapshot` / `files` untouched
         * (`session-revert.ts:8-14`).
         */
        data class SessionRevertStaged(val sessionID: String, val messageID: String, val rawJson: String) : Event

        /** `session.revert.cleared` (`session-event.ts:636`) — the staged boundary is gone. */
        data class SessionRevertCleared(val sessionID: String) : Event

        /**
         * `session.revert.committed{to}` (`session-event.ts:637-642`): the
         * transcript drops the suffix from the boundary message on, mirroring
         * upstream `client/src/solid/data.ts:1076-1092` (`splice` from the
         * first row with `id >= to`).
         */
        data class SessionRevertCommitted(val sessionID: String, val to: String) : Event

        /**
         * `session.message.content.updated{messageID, content[]}`
         * (`session-event.ts:136-146`, replay-only full-value replacement) —
         * unlike [MessageUpdated] (merge, never drops parts), this **replaces**
         * the message's parts wholesale so removed content disappears.
         */
        data class MessageContentUpdated(val messageID: String, val parts: List<UiPart>) : Event

        /**
         * The session's parked steers — every `GET /session/:id/inbox` item with
         * `type:"user"` and `delivery:"steer"`, mapped to [PENDING_ROLE] rows by
         * `ChatViewModel.refreshInbox` (which re-reads the inbox on every
         * `session.inbox.*` frame). Full replacement, like the other server
         * snapshots: an empty list clears the rows.
         */
        data class InboxPendingSteers(val rows: List<UiMessage>) : Event

        /**
         * `session.compaction.started` / `.delta` / `.ended` / `.failed`
         * (`session-event.ts:575-629`) and `session.compacted`
         * (`session-compaction-event.ts:6-11`) — one running compaction row in
         * the transcript. [phase] is the wire suffix; [rowID] is the frame's
         * `inputID` when present (upstream `data.ts:1012-1023` keys the row on
         * it), else the adapter's event-derived id. Later phases settle the
         * newest running row (`data.ts:1093-1158`); `delta`'s fragment arrives
         * in [text], `ended`'s full summary likewise, `failed`'s readable line
         * too ([rawJson] always keeps the untouched payload).
         */
        data class CompactionProgress(
            val sessionID: String,
            val phase: String,
            val rowID: String? = null,
            val text: String? = null,
            val rawJson: String? = null,
        ) : Event

        /**
         * `session.shell.ended{shell, output}` (`session-event.ts:309-318`)
         * settles the row `session.shell.started` created. Keyed by the
         * `shell.id` inside that row's `rawJson`, mirroring upstream's
         * `getShell(shellID)` lookup (`core/src/session/message-updater.ts:199-214`:
         * an orphan end is dropped there too). The incoming fields replace the
         * found part's display state, keeping its id.
         */
        data class ShellNoticeEnded(
            val sessionID: String,
            val shellID: String,
            val text: String? = null,
            val toolStatus: String? = null,
            val toolOutput: String? = null,
            val rawJson: String? = null,
        ) : Event
    }

    /**
     * The exact shape [com.xiufeigo.opencodeshell.v2.V2EventAdapter] builds
     * for a `session.model.selected` frame: one assistant message with a
     * single `model-switched` part. Snapshot rows never arrive through
     * [Event.MessageUpdated], so this shape unambiguously names the live
     * selection notice [reduce] gates on an empty transcript.
     */
    private fun UiMessage.isModelSwitchedNotice(): Boolean =
        parts.size == 1 && parts.single().type == "model-switched"

    /**
     * The notice types a row can be made of and nothing else — the vocabulary
     * `PartRenderer` prints through its notice branch, minus `shell` (see
     * [isNoticeChrome]). Reached through the *single-part* shape, so a real
     * assistant turn that happens to carry a system line is never chrome.
     */
    private val NOTICE_CHROME_TYPES = setOf(
        "model-switched",
        "agent-switched",
        "location-switched",
        "system",
        "synthetic",
        "skill",
    )

    /**
     * The notice chrome a session carries **before it has any content**: the
     * switch rows a picker tap writes (model / agent / effort / location) and
     * the server's own one-liners — exactly the shapes `PartRenderer` renders
     * through its notice branch.
     *
     * `shell` is deliberately *not* one of them: upstream gives a shell row a
     * turn of its own (`timeline/projection.ts:187-190`), so it ends the
     * pre-content window like any other turn-owning row.
     */
    private fun UiMessage.isNoticeChrome(): Boolean =
        parts.size == 1 && parts.single().type in NOTICE_CHROME_TYPES

    /**
     * The empty-session gate for the snapshot path (emulator-5558 v74, extended
     * for the cold-start replay v76).
     *
     * The live `MessageUpdated` gate drops a `model-switched` notice onto an
     * empty transcript, but every variant change persists a durable row
     * server-side (`Session.switchModel` compares the variant too,
     * `session/session.ts:96-101`), and the next snapshot reads them all back:
     * several variant taps in a brand-new empty session accumulated one
     * 「Model changed」 row per tap.
     *
     * Upstream renders `model-switched` as a timeline separator
     * (`session-timeline-row.tsx` `session-timeline-notice`) and keeps every
     * notice that was written before a turn as a *leading* row
     * (`timeline/projection.ts:182,218-220`) — but a session here stays free of
     * timeline rows until its first real content (the v68/v74 product rule), so
     * a `model-switched` notice that was written while the transcript held
     * nothing but notice chrome never renders, **at any later time**. The
     * selection itself is untouched (`SessionSelectionChanged` → `sessionModel`,
     * the chip), and a switch made after the first content row is kept exactly
     * as before — the live path after the first prompt, and the durable row a
     * later snapshot projects, both render it.
     *
     * v74 only asked "is there a `user` row yet", which made the window a
     * *timing* question on the snapshot path: the durable rows were dropped
     * while the transcript had no user row (the reopened session, empty), and
     * appeared again the moment one did — so a force-stop followed by a first
     * prompt grew the three rows back above the user bubble
     * (`research/v76_verify/5558/17_stream.png`), and the count flickered
     * between renders as snapshots with and without the still-in-flight user row
     * alternated (5558: 3/1/3). The window is therefore decided by **position,
     * not by the store's current shape**: everything before the first
     * content row belongs to the empty-session window and is dropped, whether
     * the user row has landed yet or not. A snapshot that repeats those rows
     * can no longer resurrect them, and a snapshot that drops the user row
     * again (the delivery is still in the inbox — v2 has no user-message frame)
     * cannot take them away either, because they never entered the store.
     */
    private fun withoutPreContentModelNotices(messages: List<UiMessage>): List<UiMessage> {
        // The window ends at the first row that is not chrome — a prompt (the
        // user row, the parked-steer handoff), an assistant turn, a shell row, a
        // failure card. `-1` says the whole transcript is still chrome.
        val content = messages.indexOfFirst { !it.isNoticeChrome() }
        val window = if (content < 0) messages.size else content
        if (window == 0) return messages
        // Nothing of the window is dropped unless it holds a model notice: the
        // cost of the scan is on the one path that has the rows.
        if (messages.take(window).none { it.isModelSwitchedNotice() }) return messages
        return messages.filterIndexed { index, message ->
            index >= window || !message.isModelSwitchedNotice()
        }
    }

    fun reduce(state: ChatState, event: Event): ChatState = when (event) {
        is Event.Snapshot -> {
            val merged = state.messages.toMutableList()
            // A reconnect snapshot can predate parts the stream already
            // advanced: same-id entries keep what is stored (class contract).
            for (incoming in event.messages) upsert(merged, incoming, incomingWins = false)
            val ordered = merged.sortedBy { it.timeCreated }
            state.copy(messages = materializeMessageErrors(withoutPreContentModelNotices(ordered)))
        }

        is Event.MessageUpdated -> {
            // Upstream `client/src/solid/data.ts` drops a
            // `session.model.selected` marker while the session has no message
            // list yet (`if (!store.session.message[sessionID]) return`): the
            // initial selection frame names the current model, it is not a
            // switch, so an empty session must not open with a stray "Model
            // changed" row. A switch onto a transcript still lands, and the
            // selection itself is tracked separately (`SessionSelectionChanged`
            // → `sessionModel`), so picker state never depends on this row.
            if (event.message.isModelSwitchedNotice() && state.messages.isEmpty()) {
                state
            } else {
                val merged = state.messages.toMutableList()
                upsert(merged, event.message)
                state.copy(messages = materializeMessageErrors(merged.sortedBy { it.timeCreated }))
            }
        }

        is Event.PartUpdated -> {
            val merged = state.messages.toMutableList()
            val index = merged.indexOfFirst { it.id == event.messageID }
            if (index >= 0) {
                val existing = merged[index]
                val parts = existing.parts.toMutableList()
                val partIndex = parts.indexOfFirst { it.id == event.part.id }
                if (partIndex >= 0) {
                    parts[partIndex] = mergeToolPartial(parts[partIndex], event.part)
                } else {
                    parts.add(event.part)
                }
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

        // The live interrupt flag ([Event.AssistantInterrupted]): the row the
        // aborted step names keeps its streamed parts and learns `interrupted`
        // (plus the server's error and completion for the meta line). A row
        // with nothing renderable is the Stop residue ([isInterruptedShell]) —
        // dropping it here is what keeps the deviation whole on the live path,
        // the same way `upsert` keeps it on the snapshot one. Unknown id:
        // the snapshot reconciles when it lands, so this is a no-op.
        is Event.AssistantInterrupted -> {
            val merged = state.messages.toMutableList()
            val index = merged.indexOfFirst { it.id == event.messageID }
            if (index < 0) {
                state
            } else {
                val settled = merged[index].copy(
                    interrupted = true,
                    error = event.error ?: merged[index].error,
                    timeCompleted = event.timeCompleted ?: merged[index].timeCompleted,
                )
                if (isInterruptedShell(settled)) {
                    merged.removeAt(index)
                } else {
                    merged[index] = settled
                }
                state.copy(messages = materializeMessageErrors(merged.sortedBy { it.timeCreated }))
            }
        }

        // The Stop carrier that needs no id: the frame says only "this turn is
        // over, it was interrupted" (`session.execution.interrupted`, measured
        // 2.0.18/4095 `{sessionID, reason:"user"}`). [applyTurnMarker] resolves
        // the turn from the transcript itself — the row it flags, the residue it
        // drops, or the partless marker it inserts when the server wrote no
        // assistant row at all.
        is Event.TurnInterrupted -> state.copy(
            messages = applyTurnMarker(
                state.messages,
                TurnMarker(
                    outcome = TURN_OUTCOME_INTERRUPTED,
                    time = event.timeCompleted ?: 0L,
                ),
            ),
        )

        // Parked steers: the inbox snapshot replaces the previous rows wholesale
        // (see [withPendingSteers]). The session gate is the caller's: the rows
        // are built by `ChatViewModel.refreshInbox` from the *tracked* session's
        // inbox, and a refresh whose route moved on is dropped before it applies
        // (the same rule the composer queue holder uses).
        is Event.InboxPendingSteers -> state.withPendingSteers(event.rows)

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
        // retry it describes. The upsell follows the same lifecycle with one
        // asymmetry: a qualifying frame sets it, `idle` clears it (the run is
        // over), and every other frame keeps it — so a dismissal survives
        // unrelated events and only a new qualifying frame re-shows the dialog.
        is Event.RunStatusChanged -> {
            val retry = event.retry.takeIf { event.status == RunStatus.RUNNING }
            val upsell = when {
                retry != null -> UsageUpsell.fromRetry(retry) ?: state.usageUpsell
                event.status == RunStatus.ERROR -> UsageUpsell.fromError(event.error, event.errorDetail)
                    ?: state.usageUpsell
                event.status == RunStatus.IDLE -> null
                else -> state.usageUpsell
            }
            state.copy(
                runStatus = event.status,
                error = event.error,
                errorDetail = event.errorDetail,
                retry = retry,
                usageUpsell = upsell,
                // Failure history: every terminal failure keeps its own row, so
                // the timeline renders one card per failed turn in turn order
                // instead of the newest failure overwriting the one before it
                // (upstream `TimelineRow.Error`, `projection.ts:357-359`).
                // Two exceptions retire a card again:
                // - an auto-retry — upstream renders the `Retry` row *instead of*
                //   the error (`projection.ts:355-359`), so the failure that
                //   triggered it leaves no card behind;
                // - a **transport** failure, once a turn runs again (v57
                //   `E2ECHK5556-28…30`): 「无法连接到此服务器」 stopped being true
                //   the moment the server answered again, and a row nothing ever
                //   retires left that stale card on screen for good. A `running`
                //   status can only come from a server that answered, so it is
                //   the proof of recovery; every real failure keeps its card.
                messages = when {
                    event.status == RunStatus.ERROR -> appendErrorRow(
                        state.messages,
                        event.error,
                        event.errorDetail,
                        alreadyErrored = state.runStatus == RunStatus.ERROR,
                    )

                    retry != null -> dropNewestErrorRow(state.messages)
                    event.status == RunStatus.RUNNING -> dropStaleTransportRows(state.messages)
                    else -> state.messages
                },
            )
        }

        // The link came back on its own (see [Event.TransportRecovered]): the
        // same retirement v57 gave the `running` status, driven by the recovery
        // itself so the user does not have to send again to clear a card that
        // stopped being true. The live [ChatState.error] mirror goes with it —
        // that pair is what the timeline's trailing card renders, and leaving it
        // would keep the 「Can't connect to this server」 card on screen with an
        // empty failure history behind it. A **real** failure (quota, region,
        // provider, missing directory) is not touched: the link coming back says
        // nothing about it, and only a turn or a retry retires it.
        is Event.TransportRecovered -> {
            val transport = classifyStoredError(state.error, state.errorDetail).kind ==
                ProviderErrorKind.TRANSPORT
            state.copy(
                runStatus = if (transport && state.runStatus == RunStatus.ERROR) {
                    RunStatus.IDLE
                } else {
                    state.runStatus
                },
                error = state.error.takeUnless { transport },
                errorDetail = state.errorDetail.takeUnless { transport },
                messages = dropStaleTransportRows(state.messages),
            )
        }

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

        // Wave 1 v2 forms: session gate like the permission dock. A `"global"`
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

        is Event.SessionRenamed ->
            if (event.sessionID.isNotEmpty() && !state.tracksSession(event.sessionID)) {
                state
            } else {
                state.copy(sessionTitle = event.title)
            }

        is Event.SessionSelectionChanged ->
            if (event.sessionID.isNotEmpty() && !state.tracksSession(event.sessionID)) {
                state
            } else {
                state.copy(
                    sessionAgent = event.agent ?: state.sessionAgent,
                    sessionModel = event.model ?: state.sessionModel,
                    // A model frame always names the selection's variant state
                    // (absent/`"default"` normalized to null at the adapter), so
                    // the event is authoritative only when it carries the model
                    // side; an agent frame must not clear the effort.
                    sessionVariant = if (event.model != null) event.variant else state.sessionVariant,
                )
            }

        is Event.SessionUsageUpdated ->
            if (event.sessionID.isNotEmpty() && !state.tracksSession(event.sessionID)) {
                state
            } else {
                state.copy(usageCost = event.cost, usageTokens = event.tokens)
            }

        is Event.SessionPermissionsUpdated ->
            if (event.sessionID.isNotEmpty() && !state.tracksSession(event.sessionID)) {
                state
            } else {
                state.copy(permissionsJson = event.permissionsJson)
            }

        is Event.SessionRevertStaged ->
            if (event.sessionID.isNotEmpty() && !state.tracksSession(event.sessionID)) {
                state
            } else {
                state.copy(stagedRevert = StagedRevert(event.messageID, event.rawJson))
            }

        is Event.SessionRevertCleared ->
            if (event.sessionID.isNotEmpty() && !state.tracksSession(event.sessionID)) {
                state
            } else {
                state.copy(stagedRevert = null)
            }

        is Event.SessionRevertCommitted ->
            if (event.sessionID.isNotEmpty() && !state.tracksSession(event.sessionID)) {
                state
            } else {
                val cut = state.messages.indexOfFirst { it.id >= event.to }
                state.copy(
                    stagedRevert = null,
                    messages = if (cut >= 0) state.messages.take(cut) else state.messages,
                )
            }

        // Whole-message replacement (replay-only `session.message.content.updated`):
        // parts the new content no longer carries must disappear, so this is not
        // an [upsert] merge. A missing message is created assistant-side, same
        // synthetic-envelope rule as [Event.PartUpdated].
        is Event.MessageContentUpdated -> {
            val merged = state.messages.toMutableList()
            val index = merged.indexOfFirst { it.id == event.messageID }
            if (index >= 0) {
                merged[index] = merged[index].copy(parts = event.parts)
            } else {
                merged.add(
                    UiMessage(
                        id = event.messageID,
                        role = "assistant",
                        timeCreated = System.currentTimeMillis(),
                        parts = event.parts,
                    ),
                )
            }
            state.copy(messages = merged.sortedBy { it.timeCreated })
        }

        is Event.CompactionProgress -> reduceCompaction(state, event)

        is Event.ShellNoticeEnded -> settleShellRow(state, event)

        // E2: an invalidation signal is not chat state — the owner reads the
        // mapped revision flow / catalog re-read instead, so the transcript is
        // returned untouched. (Kept here so `reduce` stays exhaustive.)
        is Event.ServerSignal -> state
    }

    /**
     * Finds the shell row whose `rawJson` carries `shell.id == [Event.ShellNoticeEnded.shellID]`
     * and replaces that part's display state (id preserved). No row ⇒ no-op,
     * same as upstream `message-updater.ts:199-214`.
     */
    private fun settleShellRow(state: ChatState, event: Event.ShellNoticeEnded): ChatState {
        if (event.sessionID.isNotEmpty() && !state.tracksSession(event.sessionID)) return state
        val merged = state.messages.toMutableList()
        for ((messageIndex, message) in merged.withIndex()) {
            val parts = message.parts.toMutableList()
            for ((partIndex, part) in parts.withIndex()) {
                if (part.type != "shell" || !rawJsonShellID(part.rawJson).equals(event.shellID)) continue
                parts[partIndex] = part.copy(
                    text = event.text ?: part.text,
                    toolStatus = event.toolStatus ?: part.toolStatus,
                    toolOutput = event.toolOutput ?: part.toolOutput,
                    rawJson = event.rawJson ?: part.rawJson,
                )
                merged[messageIndex] = message.copy(parts = parts)
                return state.copy(messages = merged)
            }
        }
        return state
    }

    /** `shell.id` of a notice part's payload, or `null` when it carries none. */
    private fun rawJsonShellID(rawJson: String?): String? = rawJson
        ?.let {
            try {
                JSONObject(it)
            } catch (_: JSONException) {
                null
            }
        }
        ?.optJSONObject("shell")
        ?.optStr("id")

    /**
     * Appends [item] and keeps only the newest [MAX_RESOLVED_REQUESTS] entries —
     * the E3 reply records are display history with no replay value, so
     * a long session must not grow them without bound.
     */
    private fun <T> List<T>.boundedAppend(item: T): List<T> =
        (this + item).takeLast(MAX_RESOLVED_REQUESTS)

    /** `sessionID == null` means "no session tracked yet" → accept everything. */
    private fun ChatState.tracksSession(id: String): Boolean = sessionID == null || sessionID == id

    // ---------------------------------------------------------- failure rows

    /** True for the client-side failure rows [appendErrorRow] appends. */
    fun isErrorRow(message: UiMessage): Boolean = message.role == ERROR_ROLE

    /** The failure's human line (the row's single part carries it in `text`). */
    fun errorRowText(message: UiMessage): String = message.parts.firstOrNull()?.text.orEmpty()

    /** The raw `session.error` JSON of the failure, when the frame carried one. */
    fun errorRowDetail(message: UiMessage): String? = message.parts.firstOrNull()?.rawJson

    /**
     * Stable content fingerprint of a failure (its human line plus the raw
     * payload), used as the tail of a failure row's id
     * (`"<messageID>:error:<fingerprint>"`, [appendErrorRow]).
     *
     * Content, not the clock: the live stream and a session re-entry anchor the
     * same failure on the same message but disagree on that message's
     * `timeCreated` (client wall clock live, server `time.created` from a
     * snapshot), so a timestamp in the id made the row key — and with it the
     * card's expand state — drift across a re-entry (device v12). FNV-1a over
     * the two halves keeps it deterministic, process-independent and short.
     *
     * Pure, so it is covered by a JVM test.
     */
    fun errorFingerprint(line: String, payload: String?): String {
        var hash = FNV_OFFSET_BASIS
        for (char in line) {
            hash = (hash xor char.code) * FNV_PRIME
        }
        // A separator keeps the two halves from running together — `("ab", "c")`
        // and `("a", "bc")` must not share an id. A missing payload and a blank
        // one are the same failure ([appendErrorRow] folds both to null).
        hash = (hash xor FIELD_SEPARATOR) * FNV_PRIME
        for (char in payload.orEmpty()) {
            hash = (hash xor char.code) * FNV_PRIME
        }
        return hash.toUInt().toString(16)
    }

    private const val FNV_OFFSET_BASIS = -2128831035 // 0x811c9dc5
    private const val FNV_PRIME = 0x01000193
    private const val FIELD_SEPARATOR = 0x1f

    // ------------------------------------------------------- parked steer rows

    /** True for the client-side parked-steer rows ([PENDING_ROLE]). */
    fun isPendingRow(message: UiMessage): Boolean = message.role == PENDING_ROLE

    /**
     * True for a row the **client** owns rather than the server — a failure card
     * ([ERROR_ROLE]) or a parked steer ([PENDING_ROLE]). Every rule that means
     * "a real turn" (the newest-turn lookup, separator anchoring, the upsert
     * hand-over) asks this instead of [isErrorRow].
     */
    fun isClientRow(message: UiMessage): Boolean =
        message.role == ERROR_ROLE || message.role == PENDING_ROLE

    /** The parked steer's question text (the row's `text` parts). */
    fun pendingRowText(message: UiMessage): String =
        message.parts.filter { it.type == "text" }.joinToString("\n\n") { it.text }

    /**
     * An **interrupted** assistant turn that produced nothing to read.
     *
     * Stop pressed before the first token arrives (or before the first tool
     * call does) still leaves a server-side assistant row behind, holding only
     * the bookkeeping parts — `step-start` / `step-finish` / `agent`
     * (`v1/session.ts:357-383`, the [UiPart.RAW_JSON_TYPES] the transcript never
     * draws). Rendered, that row is an empty bubble followed by the
     * `Interrupted` divider anchored on it — a hairline and a hole where the
     * turn's text should be (emulator-5556 tStop, the 观感 note on the same
     * report). There is no content to lose, so the residue is dropped instead.
     *
     * Narrow on purpose: only an [interrupted] row that carries the
     * bookkeeping parts the server wrote around the abort, and only when
     * *nothing* renderable came out of it — a turn that produced text,
     * reasoning, a tool card or an attachment keeps its row (and therefore its
     * divider), and an un-interrupted empty row is the `Working` turn in
     * progress, which must survive. A row with no parts at all is left alone
     * too: that is a turn the server aborted before it ever wrote content, not
     * residue the user can see.
     */
    fun isInterruptedShell(message: UiMessage): Boolean =
        message.role == "assistant" &&
            message.interrupted &&
            message.parts.isNotEmpty() &&
            message.parts.none { part ->
                part.type !in UiPart.RAW_JSON_TYPES ||
                    part.text.isNotBlank() ||
                    part.toolName != null ||
                    part.filename != null ||
                    part.url != null ||
                    part.mime != null
            }

    // ------------------------------------------------------- turn markers

    /**
     * The server's own end-of-turn record: one durable `idle` row per finished
     * turn (`_upstream/packages/schema/src/session-message.ts:288-292` —
     * `{type:"idle", outcome:"succeeded"|"failed"|"interrupted"}`), written by
     * the projector's `projectIdle` for every terminal
     * (`core/src/session/projector.ts:665,672-673`). It is **not** a message
     * upstream draws — `isNotice` excludes it (`session-ui/src/timeline/
     * projection.ts:766`) — it is the marker that says "every step since the
     * previous one belongs to one turn".
     *
     * The shell used to project the row like any other unknown type: an
     * assistant-role notice part with `text = ""`, i.e. an invisible row that
     * carried nothing. For `outcome:"interrupted"` that lost the only record a
     * pre-stream Stop has.
     */
    data class TurnMarker(val outcome: String, val time: Long)

    /** `Session.Message.Idle.outcome` for a turn ended by a Stop. Measured 2.0.18/4095. */
    const val TURN_OUTCOME_INTERRUPTED = "interrupted"

    /**
     * The marker row's id: derived from the **turn's own user message**, so
     * both carriers (the durable `idle` row in a snapshot, the live
     * `session.execution.interrupted` frame) name the same row and a repeat
     * upserts instead of duplicating.
     */
    fun interruptMarkerID(userMessageID: String): String = "$userMessageID:interrupted"

    /**
     * The `Interrupted` divider's carrier when the server wrote no assistant row.
     *
     * A Stop that lands before the provider produced anything ends the turn with
     * *only* a `user` row and an `idle{outcome:"interrupted"}` marker (measured
     * 2.0.18/4095: `msg_0ef247fed…` user + `msg_0ef248c9a…` idle, three seconds
     * apart, no assistant row in between). There is no row to hang the
     * `Interrupted` divider on, so the turn drew nothing at all
     * (emulator-5556 v74: two stopped turns, no marker, no output).
     *
     * This row is deliberately **partless**: the timeline emits the divider off
     * [UiMessage.interrupted] alone (`ui/session/timeline/TimelineRows.kt:214`),
     * while its assistant row draws an empty column for an empty part list — so
     * the turn gets its divider without the empty bubble
     * ([isInterruptedShell]) the deviation exists to avoid.
     */
    fun interruptedMarkerRow(userMessageID: String, timeCompleted: Long): UiMessage = UiMessage(
        id = interruptMarkerID(userMessageID),
        role = "assistant",
        timeCreated = timeCompleted,
        parts = emptyList(),
        timeCompleted = timeCompleted,
        interrupted = true,
    )

    /**
     * Fold one [TurnMarker] into the transcript: the turn it closes is over, and
     * it was closed by a Stop.
     *
     * The rule needs no ids from the wire, exactly like the server's own ("every
     * step since the previous marker belongs to one turn"): the turn is the
     * newest `user` row at or before the marker's own instant, and the row it
     * produced is the newest assistant row after it. Four outcomes, one
     * implementation for both carriers ([applyTurnMarker] is used by the
     * snapshot projection and by [Event.TurnInterrupted]):
     *
     * - the turn has an assistant row → pin `interrupted` (and the completion
     *   stamp the meta fragment reads) on it, so the divider is drawn even when
     *   the row's own `error` write lagged behind the marker;
     * - that row is the Stop residue ([isInterruptedShell]) → drop it, as the
     *   live flag already does, and fall through to the marker below — the
     *   deviation is about the empty bubble, not about hiding the Stop;
     * - the turn has no assistant row at all → insert the partless marker;
     * - the same fact arrives twice (the live frame, then the marker row of the
     *   next snapshot) → the marker's derived id already exists, so it upserts.
     *
     * Every other outcome is the marker alone and changes nothing: `succeeded`
     * and `failed` turns already carry their own rows (and the failure its
     * card).
     */
    fun applyTurnMarker(messages: List<UiMessage>, marker: TurnMarker): List<UiMessage> {
        if (marker.outcome != TURN_OUTCOME_INTERRUPTED) return messages
        val rows = messages.toMutableList()
        // The turn the marker closes, resolved by **time**: a store can already
        // hold a later turn's rows (frames arrive out of order, and the live
        // frame carries no list position at all), so "the newest user row" would
        // hand a stopped turn's marker to whatever turn came next. The marker's
        // own instant is the server's own end-of-turn stamp.
        val at = marker.time.takeIf { it > 0L }
        val userIndex = rows.indexOfLast { it.role == "user" && (at == null || it.timeCreated <= at) }
        val user = rows.getOrNull(userIndex)
        // A row this rule already inserted is not a turn's output: without the
        // guard a second Stop would "settle" the previous marker instead of
        // marking its own turn.
        val markerIDs = rows.filter { it.role == "user" }.map { interruptMarkerID(it.id) }.toSet()
        val assistantIndex = rows.indexOfLast {
            it.role == "assistant" && it.id !in markerIDs &&
                (at == null || it.timeCreated <= at)
        }
        if (assistantIndex > userIndex) {
            val assistant = rows[assistantIndex]
            val settled = assistant.copy(
                interrupted = true,
                timeCompleted = at ?: assistant.timeCompleted,
            )
            if (!isInterruptedShell(settled)) {
                rows[assistantIndex] = settled
                return materializeMessageErrors(rows.sortedBy { it.timeCreated })
            }
            rows.removeAt(assistantIndex)
        }
        // No user row to anchor on and no assistant row either: a turn the
        // store never saw (a foreign session's frame, an already-pruned
        // window). Nothing to mark.
        if (user == null) return messages
        // An already-inserted marker: a repeat of the same fact (the live frame
        // and the marker row of the next snapshot) must not duplicate the row.
        if (rows.any { it.id == interruptMarkerID(user.id) }) {
            return materializeMessageErrors(rows.sortedBy { it.timeCreated })
        }
        rows.add(
            interruptedMarkerRow(
                userMessageID = user.id,
                timeCompleted = at ?: (user.timeCreated + 1),
            ),
        )
        return materializeMessageErrors(rows.sortedBy { it.timeCreated })
    }

    /** The parked steer's attachment chips (the row's `file` parts). */
    fun pendingRowFiles(message: UiMessage): List<UiPart> = message.parts.filter { it.type == "file" }

    /**
     * Replace the parked-steer rows with the current `GET …/inbox` truth, keeping
     * everything else in place.
     *
     * Wholesale replacement is what makes the rows self-healing: the snapshot is
     * the server's own list, so a delivery or a cancel (`session.inbox.delivered`
     * / `.cancelled` → the caller re-reads it) drops the row without any
     * client-side bookkeeping, and a reconnect that missed the events reconciles
     * on the next refresh. Rows are appended (the newest steers belong at the
     * tail, `controller-projection.ts:27-32`); the other branches' `sortedBy`
     * keeps them there because their `timeCreated` is the server's own
     * `time.created`, i.e. newer than every transcript message.
     *
     * Order matters on the very first refresh of a replayed session: the parked
     * items are appended *after* the transcript, so the tail position is exact
     * even if a clock is skewed.
     *
     * An item whose message is **already in the transcript** is dropped: the
     * inbox read trails the SSE stream by one round-trip, so a delivery can
     * arrive between the frame that triggered the refresh and its answer — and
     * appending that placeholder would print the user's text twice.
     */
    private fun ChatState.withPendingSteers(rows: List<UiMessage>): ChatState {
        val kept = messages.filterNot { isPendingRow(it) }
        if (rows.isEmpty()) return copy(messages = kept)
        val delivered = kept.mapTo(HashSet()) { it.id }
        val fresh = rows.distinctBy { it.id }.filterNot { it.id in delivered }
        if (fresh.isEmpty()) return copy(messages = kept)
        return copy(messages = kept + fresh)
    }

    /**
     * Appends a failure row for the turn that just failed and keeps the newest
     * [MAX_ERROR_ROWS] of them (the ring around the failure history).
     *
     * The row id is anchored on the newest real message
     * (`"<messageID>:error:<fingerprint>"`), so the revert rule every consumer
     * already shares — `id >= boundary` hides the reverted tail, in `reduce` and
     * in `SessionActionsModel.hiddenMessageIDs` alike — carries the failures of a
     * rewound turn away with that turn (the comparison is decided by the anchor's
     * own characters, before the `:error:` tail). A transcript with no real
     * message yet (a connect failure before the first prompt) has nothing to
     * anchor on and no boundary to be cut by, so its row keeps the bare
     * `error:<fingerprint>` form.
     *
     * The id carries the failure's [errorFingerprint], **not** the row's
     * [UiMessage.timeCreated] stamp: the two paths that produce a card disagree
     * on the anchor's clock. Live, the v2 stream builds the assistant envelope
     * client-side (v2 has no `session.message.updated` frame, so `PartUpdated` /
     * `MessageContentUpdated` fall back to `System.currentTimeMillis()`), while a
     * session re-entry rebuilds the same row from one snapshot carrying the
     * server's `time.created` ([materializeMessageErrors]). A stamp in the id
     * therefore drifts between the live session and the re-entry — measured
     * `msg_a1:error:1790357641329` against `msg_a1:error:1101` — and with it
     * `TimelineRows.Row.Error.key`, which is what `SessionErrorCard` scopes its
     * expand state to, so every expanded card came back collapsed after a
     * re-entry (device v12). The fingerprint is derived from the failure alone,
     * so both paths produce the identical id byte for byte.
     *
     * Two guards keep the cards honest:
     * - a frame re-delivered for the failure the store is **already** in ERROR
     *   for adds nothing (`session.step.failed` and `session.execution.failed`
     *   describe one terminal error), while an identical failure on a later turn —
     *   the two-model repro — arrives after that turn's `running`/`idle` frames
     *   and therefore gets its own card;
     * - a blank line is not a failure (a transport whose `e.message` is null
     *   would otherwise stack empty cards).
     *
     * [alreadyErrored] is the caller's `runStatus == ERROR` *before* this frame:
     * position in the list would not do — the transcript is re-sorted by
     * `timeCreated` on every message/snapshot event, so a row's neighbours say
     * nothing about which failure a frame belongs to.
     *
     * [anchor] is the turn the card closes; it defaults to the newest real
     * message (the live-frame case) and is passed explicitly by
     * [materializeMessageErrors], where the row is rebuilt from a server
     * message that need not be last in the list. An identical failure already
     * anchored on **that same** message is never added twice — the frame and
     * the message carry one failure, and a reconnect snapshot re-delivers
     * every message.
     */
    private fun appendErrorRow(
        messages: List<UiMessage>,
        error: String?,
        detail: String?,
        alreadyErrored: Boolean,
        anchor: UiMessage? = messages.lastOrNull { !isErrorRow(it) },
    ): List<UiMessage> {
        val line = error?.takeIf { it.isNotBlank() } ?: return messages
        val payload = detail?.takeIf { it.isNotBlank() }
        val newest = messages.lastOrNull { isErrorRow(it) }
        if (alreadyErrored && newest != null &&
            errorRowText(newest) == line && errorRowDetail(newest) == payload
        ) {
            return messages
        }
        if (anchor != null && sameFailureOnTurn(messages, anchor, line, payload)) return messages
        // One millisecond past the turn it closes: the store re-sorts the
        // transcript by `timeCreated` on every message/snapshot event, and this
        // keeps the card after its own turn through every one of those sorts
        // (a wall-clock stamp would fight an out-of-order server timestamp). It
        // orders the row only — the id below must not carry it (see the kdoc).
        val stamp = anchor?.timeCreated?.plus(1) ?: System.currentTimeMillis()
        val fingerprint = errorFingerprint(line, payload)
        val base = if (anchor == null) "error:$fingerprint" else "${anchor.id}:error:$fingerprint"
        var id = base
        var bump = 0
        while (messages.any { it.id == id }) {
            bump += 1
            id = "$base.$bump"
        }
        val appended = messages + UiMessage(
            id = id,
            role = ERROR_ROLE,
            timeCreated = stamp,
            parts = listOf(
                UiPart(id = "$id:error", type = ERROR_ROLE, text = line, rawJson = payload),
            ),
        )
        val rows = appended.filter { isErrorRow(it) }
        if (rows.size <= MAX_ERROR_ROWS) return appended
        val dropped = rows.take(rows.size - MAX_ERROR_ROWS).mapTo(HashSet()) { it.id }
        return appended.filterNot { it.id in dropped }
    }

    /**
     * Whether a card for exactly this failure already exists on [anchor]'s
     * turn. Row ids are anchored per turn (`"<messageID>:error:<fingerprint>"`,
     * [appendErrorRow]), so the anchor's id prefix *is* the turn key — two
     * identical failures on two turns stay two cards, which is the whole point
     * of the failure history.
     */
    private fun sameFailureOnTurn(
        messages: List<UiMessage>,
        anchor: UiMessage,
        line: String,
        payload: String?,
    ): Boolean {
        val prefix = "${anchor.id}:error:"
        return messages.any { row ->
            isErrorRow(row) && row.id.startsWith(prefix) &&
                errorRowText(row) == line && errorRowDetail(row) == payload
        }
    }

    /**
     * Rebuilds the failure cards a **server** transcript already carries —
     * the reopen path: a fresh [ChatState] inherits nothing from the closed
     * session, and the SSE frames that once appended the cards are long gone,
     * so a failure that is still on the server's message would silently
     * disappear (the v8-B multi-error finding, `research/v8_verify/err_review`).
     * Upstream reads the same fact off the message when it projects the turn
     * (`SessionMessageAssistant.error` → `TimelineRow.Error`,
     * `timeline/projection.ts:355-359`), so the card belongs to the message,
     * not to the session's frame history.
     *
     * Idempotent: the card is anchored on its own message ([appendErrorRow]),
     * so re-applying a reconnect snapshot, or the live frame arriving after
     * the message, adds nothing. An interrupted turn is not a failure — the
     * message carries `aborted`/`interrupt` and renders as the `Interrupted`
     * separator plus the meta fragment ([UiMessage.interrupted]).
     *
     * Returns the list re-sorted by `timeCreated`, because the rebuilt row is
     * appended (its stamp is one past its own turn's).
     */
    private fun materializeMessageErrors(messages: List<UiMessage>): List<UiMessage> {
        var out = messages
        for (message in messages) {
            val error = message.error ?: continue
            if (message.interrupted) continue
            out = appendErrorRow(
                messages = out,
                error = error.message,
                detail = error.raw,
                alreadyErrored = false,
                anchor = message,
            )
        }
        return if (out === messages) out else out.sortedBy { it.timeCreated }
    }

    /**
     * Drops the newest failure row: an auto-retry replaces the error it was
     * scheduled for (`projection.ts:355-359`), so that card disappears while the
     * retry runs — and the transcript goes back to ending on the assistant turn
     * the `Working` row expects. Keyed on the newest *failure* rather than on
     * the last element for the same reason as [appendErrorRow]: the list order is
     * the chronological one, the element order is not.
     */
    private fun dropNewestErrorRow(messages: List<UiMessage>): List<UiMessage> {
        val newest = messages.lastOrNull { isErrorRow(it) } ?: return messages
        return messages.filterNot { it.id == newest.id }
    }

    /**
     * Retires the 「无法连接到此服务器」 cards: the failures whose only claim was
     * that the link was down, once a turn proves it is back (v57, see the
     * `RunStatusChanged` branch). The verdict itself lives in
     * [com.xiufeigo.opencodeshell.notify.BannerPolicy.staleTransportIds], beside
     * the other banner rules, so the classification cannot drift from the copy
     * the card renders.
     */
    private fun dropStaleTransportRows(messages: List<UiMessage>): List<UiMessage> {
        val stale = BannerPolicy.staleTransportIds(
            messages.filter { isErrorRow(it) }.map {
                BannerPolicy.FailureRow(it.id, errorRowText(it), errorRowDetail(it))
            },
        )
        return if (stale.isEmpty()) messages else messages.filterNot { it.id in stale }
    }

    /**
     * The compaction row (`data.ts:1012-1023, 1093-1158`): `started` upserts a
     * running `compaction` part under the row id (`inputID` or the event-derived
     * id), `delta` appends its fragment to the newest running part's text,
     * `ended` / `failed` settle that part with the full summary / readable line
     * (or create the row when the start was never seen), and `compacted`
     * settles a still-running row — its `ended` can be missing on a replay gap.
     */
    private fun reduceCompaction(state: ChatState, event: Event.CompactionProgress): ChatState {
        if (event.sessionID.isNotEmpty() && !state.tracksSession(event.sessionID)) return state
        val kind = "compaction"
        val merged = state.messages.toMutableList()
        val runningAt = merged.indexOfLast { message ->
            message.parts.any { it.type == kind && it.toolStatus == "running" }
        }
        when {
            event.phase == "started" -> {
                val rowID = event.rowID?.takeIf { it.isNotEmpty() } ?: return state
                upsert(
                    merged,
                    UiMessage(
                        id = rowID,
                        role = "assistant",
                        timeCreated = merged.find { it.id == rowID }?.timeCreated ?: System.currentTimeMillis(),
                        parts = listOf(
                            UiPart(
                                id = "$rowID:notice",
                                type = kind,
                                toolStatus = "running",
                                rawJson = event.rawJson,
                            ),
                        ),
                    ),
                )
            }

            event.phase == "delta" -> {
                if (runningAt < 0 || event.text == null) return state
                val message = merged[runningAt]
                merged[runningAt] = message.copy(
                    parts = message.parts.map { part ->
                        if (part.type == kind && part.toolStatus == "running") {
                            part.copy(text = part.text + event.text)
                        } else {
                            part
                        }
                    },
                )
            }

            event.phase == "ended" || event.phase == "failed" || event.phase == "compacted" -> {
                val settled = if (event.phase == "failed") "error" else "completed"
                if (runningAt >= 0) {
                    val message = merged[runningAt]
                    merged[runningAt] = message.copy(
                        parts = message.parts.map { part ->
                            if (part.type == kind && part.toolStatus == "running") {
                                part.copy(
                                    text = event.text ?: part.text,
                                    toolStatus = settled,
                                    rawJson = event.rawJson ?: part.rawJson,
                                )
                            } else {
                                part
                            }
                        },
                    )
                } else if (event.phase != "compacted") {
                    val rowID = event.rowID?.takeIf { it.isNotEmpty() } ?: return state
                    upsert(
                        merged,
                        UiMessage(
                            id = rowID,
                            role = "assistant",
                            timeCreated = merged.find { it.id == rowID }?.timeCreated ?: System.currentTimeMillis(),
                            parts = listOf(
                                UiPart(
                                    id = "$rowID:notice",
                                    type = kind,
                                    text = event.text.orEmpty(),
                                    toolStatus = settled,
                                    rawJson = event.rawJson,
                                ),
                            ),
                        ),
                    )
                }
            }

            else -> return state
        }
        return state.copy(messages = merged.sortedBy { it.timeCreated })
    }

    // ------------------------------------------------- compaction row status
    //
    // One fact, two shapes. The **live** row [reduceCompaction] builds carries
    // it in [UiPart.toolStatus] (`"running"` / `"completed"` / `"error"` for the
    // `failed` phase); the **durable** `Session.Message.Compaction` row
    // (`_upstream/packages/schema/src/session-message.ts:238-281`) carries it on
    // the row itself — `status: running|completed|failed`, plus `error` when
    // failed — and the v2 snapshot projection keeps only that row's `summary`.
    // A rejected compaction therefore reached the timeline as an empty-text
    // part indistinguishable from a completed one and was labelled 「Session
    // compacted」, the one line that said the rescue had worked when it had not
    // (`research/v21_verify/limit/v21_92_session_compaction.png`).
    //
    // [compactionRow] rebuilds the durable row in exactly the live row's shape
    // and the readers below normalise both vocabularies into the durable one, so
    // every renderer asks one question and gets one answer.

    /** Durable `Session.Message.Compaction` statuses (`session-message.ts:246-275`). */
    const val COMPACTION_RUNNING = "running"
    const val COMPACTION_COMPLETED = "completed"
    const val COMPACTION_FAILED = "failed"

    /**
     * The outcome of a `compaction` part in the durable vocabulary, from
     * [UiPart.toolStatus] — the store's own settled state, written by
     * [reduceCompaction] and by [compactionRow] for the durable row.
     *
     * `null` when nothing settled it. An unknown status is **not** a failure:
     * upstream's completed label is the fallback, exactly as for a `completed`
     * row (`message-content.tsx:411-431`).
     */
    fun compactionStatusOf(part: UiPart): String? = when (part.toolStatus?.trim()?.lowercase()) {
        // `"error"` is the store's spelling of a rejected compaction
        // ([reduceCompaction]'s `failed` phase); the durable row says `"failed"`.
        "error", COMPACTION_FAILED -> COMPACTION_FAILED
        COMPACTION_RUNNING -> COMPACTION_RUNNING
        COMPACTION_COMPLETED -> COMPACTION_COMPLETED
        else -> null
    }

    /**
     * True when the compaction was rejected — the one case whose label must not
     * read as a completed compaction. Both paths that show it (the part body and
     * the `COMPACTION_END` divider) ask this, so they cannot disagree.
     */
    fun compactionFailed(part: UiPart): Boolean = compactionStatusOf(part) == COMPACTION_FAILED

    /** True while the compaction is still running (`message-content.tsx:446-456`). */
    fun compactionRunning(part: UiPart): Boolean = compactionStatusOf(part) == COMPACTION_RUNNING

    /**
     * The readable failure detail of a rejected compaction, `null` when the part
     * is not failed. [reduceCompaction] settles a failed row's `text` from the
     * frame's `error`; a durable row keeps that error in its payload instead
     * (`error: SessionError.Error`), so both are read here.
     */
    fun compactionErrorOf(part: UiPart): String? {
        if (!compactionFailed(part)) return null
        part.text.takeIf { it.isNotBlank() }?.let { return it }
        val raw = part.rawJson?.let { json ->
            try {
                JSONObject(json)
            } catch (_: JSONException) {
                null
            }
        } ?: return null
        return errorMessageOf(raw.opt("error"))
    }

    /**
     * One durable `type=compaction` message row → the store row the live
     * `session.compaction.*` frames build, so the two are interchangeable and
     * can never show different outcomes.
     *
     * Same message id (`inputID ?? ID.fromEvent(event.id)` upstream —
     * `message-updater.ts:411-423`, which is why `V2EventAdapter.noticeID` maps
     * `evt_` → `msg_`), same `"$id:notice"` part id, the same `toolStatus`
     * vocabulary ([reduceCompaction]'s), and the readable line in `text` where
     * `ended` / `failed` put it. The untouched row stays in [UiPart.rawJson].
     *
     * `null` when the row carries no id — there is nothing to key the row on.
     */
    fun compactionRow(row: JSONObject): UiMessage? {
        val id = row.optStr("id") ?: return null
        val status = row.optStr("status")?.trim()?.lowercase()
        val failed = status == COMPACTION_FAILED
        return UiMessage(
            id = id,
            role = "assistant",
            timeCreated = row.optJSONObject("time")?.optLong("created", 0L) ?: 0L,
            parts = listOf(
                UiPart(
                    id = "$id:notice",
                    type = "compaction",
                    text = if (failed) {
                        errorMessageOf(row.opt("error")).orEmpty()
                    } else {
                        row.optStr("summary").orEmpty()
                    },
                    // The store's own vocabulary, never a foreign string: an
                    // unrecognised status leaves the part unsettled, which the
                    // readers below treat as "keep the completed label".
                    toolStatus = when (status) {
                        COMPACTION_FAILED -> "error"
                        COMPACTION_RUNNING, COMPACTION_COMPLETED -> status
                        else -> null
                    },
                    rawJson = row.toString(),
                ),
            ),
        )
    }

    /**
     * [incomingWins]=true: a same-id part takes the incoming copy
     * (`message.part.updated` / `message.updated` are newer truth);
     * false: the stored part stays — a reconnect [Event.Snapshot] must never
     * roll back a part the stream already advanced
     * (`snapshotMergesWithoutClobberingStreamedParts`).
     */
    private fun upsert(target: MutableList<UiMessage>, message: UiMessage, incomingWins: Boolean = true) {
        val index = target.indexOfFirst { it.id == message.id }
        // The Stop residue ([isInterruptedShell]): an aborted turn that produced
        // nothing is a row with no content to draw, and drawing it leaves both an
        // empty bubble and the divider anchored on it. It takes the row out on
        // either side — arriving as one, or becoming one as the merge drops the
        // last renderable part — so the residue cannot reappear on a later
        // snapshot.
        if (isInterruptedShell(message)) {
            if (index >= 0) target.removeAt(index)
            return
        }
        if (index >= 0) {
            val existing = target[index]
            if (isInterruptedShell(existing)) {
                target[index] = message
                return
            }
            // The client-row hand-over ([isClientRow]). The two sides mean
            // different things, so neither half of the ordinary part merge is
            // right for them:
            //  - a *client* row arriving for an id the transcript already holds
            //    (a parked steer whose message was delivered while the inbox
            //    snapshot was in flight) is stale — the server's message wins;
            //  - a *server* message arriving for an id held by a client row is
            //    that row being delivered — the placeholder's parts are local
            //    (the server never saw them, and `mergeParts` would keep them
            //    side by side with the server's own text), so the server's
            //    parts replace them wholesale.
            if (isClientRow(message)) {
                if (!isClientRow(existing)) return
                target[index] = message
                return
            }
            // The server's own message replaces the placeholder wholesale: its
            // parts are the only real ones (a merge would keep the local
            // question beside the server's copy of it).
            if (isClientRow(existing)) {
                target[index] = message
                return
            }
            target[index] = message.copy(parts = mergeParts(existing.parts, message.parts, incomingWins))
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

    private fun mergeParts(existing: List<UiPart>, incoming: List<UiPart>, incomingWins: Boolean): List<UiPart> {
        val merged = existing.toMutableList()
        for (part in incoming) {
            val index = merged.indexOfFirst { it.id == part.id }
            if (index >= 0) {
                // `incomingWins = false` (a reconnect snapshot) keeps the stored
                // copy so a replay can never roll a streamed part back — but the
                // two **call identity** fields a tool part is addressed by are
                // immutable per call id and may still be missing from the stored
                // copy (the terminal frames carry neither `name` nor `input`, see
                // [mergeToolPartial]; a part created by one of them alone, on a
                // stream that joined mid-call, has neither). Filling a blank
                // cannot roll anything back, and it is the only way such a card
                // shows its title and command at all.
                val stored = merged[index]
                merged[index] = if (incomingWins) {
                    part
                } else {
                    stored.copy(
                        toolName = stored.toolName ?: part.toolName,
                        toolInput = stored.toolInput ?: part.toolInput,
                    )
                }
            } else {
                merged.add(part)
            }
        }
        return merged
    }

    /**
     * [Event.PartUpdated] is a whole-part replace, but the v2 tool frames after
     * `input.started` carry no `toolName` (upstream Tool payloads only name the
     * call on `input.started`), so a plain replace would erase the name the
     * started frame set. The **terminal** frames drop `toolInput` the same way:
     * `Tool.Success` / `Tool.Failed` are `ToolBase + content/error`
     * (`schema/src/session-event.ts:501-556`) and carry no `input`, while the
     * durable `ToolState` the snapshot reads does ([V2Transport.toolPart]).
     *
     * Both fields are the call's immutable identity — set once, never rewritten
     * — so they are carried over; every other field stays a whole-part replace,
     * because later frames *do* deliberately change it (a success frame drops
     * `toolError`, a terminal frame settles `toolStatus`).
     *
     * Blanking the input blanked every subtitle derived from it: the failed
     * shell card lost its command, so two *different* failures (`ls` and
     * `ls -la …`, the v37 tB session) rendered as two identical
     * 「Shell / 工作目录不存在」 rows that read as one duplicated row — while a
     * reopen (the snapshot) showed the commands again. Live and snapshot must
     * agree here, exactly like the notice rows ([V2Transport.noticePart]).
     */
    private fun mergeToolPartial(existing: UiPart, incoming: UiPart): UiPart =
        incoming.copy(
            toolName = incoming.toolName ?: existing.toolName,
            toolInput = incoming.toolInput ?: existing.toolInput,
        )

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
            agent = info.optStr("agent"),
            model = messageModel(info),
            timeCompleted = time?.optLong("completed", 0L)?.takeIf { it > 0L },
            interrupted = isInterrupted(info.optJSONObject("error")),
            error = messageErrorOf(info.opt("error")),
        )
    }

    /**
     * `"providerID/modelID"` of a message: the assistant carries the pair flat
     * (`v1/session.ts:462-463`), the user carries `model:{providerID,modelID}`
     * (`:347-351`). A lone `modelID` is still better than nothing.
     */
    private fun messageModel(info: JSONObject): String? {
        val nested = info.optJSONObject("model")
        // Null-safe: `{"providerID":null}` must not produce the string "null" in
        // the model chip (nor in the `providerID/modelID` pair).
        val providerID = info.optStr("providerID") ?: nested?.optStr("providerID").orEmpty()
        val modelID = info.optStr("modelID") ?: nested?.optStr("modelID").orEmpty()
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
        // `optStr`, not `optString`: `{"name":null}` would otherwise classify the
        // turn on the literal string "null" (which contains neither token, so the
        // failure mode is silent — but the v2 shape sends `type:null` on
        // message-less errors, and this is the one reader for both).
        val discriminator = (error.optStr("name") ?: error.optStr("type").orEmpty()).lowercase()
        return discriminator.contains("abort") || discriminator.contains("interrupt")
    }

    // ------------------------------------------------------------ error payload

    /**
     * NULL-safe read of an **optional** string field — the one reader for every
     * optional string this file parses (error payloads, message and part
     * envelopes alike).
     *
     * org.json's `optString` coerces a JSON null to the literal `"null"`, which
     * then sails through `takeIf { it.isNotBlank() }` and ends up on screen (it
     * once printed "null" on the error card header, and `Cursor` hit the same
     * trap twice). [JSONObject.isNull] covers a missing key and JSON null alike
     * on both `org.json` builds this app runs on; a server-sent literal `"null"`
     * counts as absent too. Named after the `optStr` precedent at the top of
     * `OpenCodeClient.kt`, which is the same narrowing.
     *
     * Required fields keep their plain `optString(key, "")`: a JSON null there is
     * a server contract violation, not a shape any route is expected to send.
     */
    private fun JSONObject.optStr(key: String): String? {
        if (isNull(key)) return null
        return optString(key, "").takeIf { it.isNotBlank() && it != "null" }
    }

    /**
     * Human-readable line of an error payload.
     *
     * v2 `SessionError.Error` is `{type, message, status?}`
     * (`_upstream/packages/schema/src/session-error.ts:7-13`) and [message] is
     * the human line. The legacy union shape `{name, data:{message}}` is still
     * tolerated (`v1/session.ts:385-395`); reading the payload with
     * `optString("error")` stringified the whole object, which is what made the
     * error card a wall of JSON.
     *
     * Order: `data.message` (legacy union) → `name` (a field-less member such
     * as `MessageOutputLengthError` would otherwise show nothing) → `message`
     * (the v2 line) → `type` (last-resort discriminator of a message-less
     * error) → the string itself when the server sent a plain string. `null`
     * when nothing is readable; the caller substitutes its own fallback.
     */
    fun errorMessageOf(raw: Any?): String? = when (raw) {
        null, JSONObject.NULL -> null
        is String -> raw.takeIf { it.isNotBlank() && it != "null" }
        is JSONObject -> raw.optJSONObject("data")
            ?.optStr("message")
            ?: raw.optStr("name")
            ?: raw.optStr("message")
            ?: raw.optStr("type")

        else -> null
    }

    /**
     * The untouched `session.error` payload as JSON text, so unwrapping the
     * message never loses provider detail (`statusCode`, `responseBody`,
     * `isRetryable`, …). `null` for a plain-string error, whose text is already
     * the message.
     */
    fun errorRawJsonOf(raw: Any?): String? = (raw as? JSONObject)?.toString()

    /**
     * The message-level error of a server row (v1 `info.error`, v2 `error`),
     * or `null` when the row carries none. Built from the same readers the
     * live `session.error` path uses, so the reconstructed card is identical
     * to the one the frame produced: [errorMessageOf] for the line,
     * [errorRawJsonOf] for the detail.
     *
     * `status` is only read when the key is actually there — `optInt` answers
     * `0` for an absent key, and a fabricated `0` would render as a status on
     * the card. A literal JSON `null` is absent too ([JSONObject.isNull]).
     */
    fun messageErrorOf(raw: Any?): UiMessageError? {
        val message = errorMessageOf(raw) ?: return null
        val obj = raw as? JSONObject
        return UiMessageError(
            message = message,
            type = obj?.optStr("type") ?: obj?.optStr("name"),
            // Same rule as [classifyStoredError]: the key must be there (a
            // missing one reads as 0) and be a real HTTP status.
            status = obj?.takeIf { it.has("status") && !it.isNull("status") }
                ?.optInt("status")
                ?.takeIf { it in 100..599 },
            raw = errorRawJsonOf(raw),
        )
    }

    private val HTTP_CODE_REGEX = Regex("""HTTP\s+(\d{3})\b""")
    private val ERROR_TYPE_REGEX = Regex(""""type"\s*:\s*"([^"]+)"""")
    /**
     * `V2Api.errorText` renders the discriminator as `HTTP {code} [{type}]
     * {human}` — the prompt-POST path's only structured `type` source. The
     * token must start with a letter so a bracketed aside never parses as a
     * type; an unknown word is harmless anyway ([classifyProviderError] only
     * matches known quota/region fragments and the message keywords still
     * vote).
     */
    private val BRACKET_TYPE_REGEX = Regex("""\[([A-Za-z][A-Za-z0-9_.-]*)\]""")

    /** Provider-failure category driving the error card's title + advice. */
    enum class ProviderErrorKind {
        QUOTA_EXHAUSTED,
        REGION_RESTRICTED,

        /**
         * v31 BUG-3: the session's **project directory is gone**. The server
         * answers the prompt with `500 PlatformError: NotFound:
         * FileSystem.realPath` (`core/src/filesystem.ts` resolves every
         * operation through `fs.realPath`, so a deleted directory fails the
         * first filesystem call of the turn), which used to reach the user as a
         * bare 「HTTP 500」 with no hint that the directory — not the server, not
         * the model — is what is missing. Upstream names this case on its own
         * (`session.location.unavailable` + `session.location.description`,
         * `tui/src/routes/session/location-missing.tsx`).
         */
        LOCATION_MISSING,

        /**
         * v32 BUG-1: the **connection** to the server is what failed — the
         * socket was dropped mid-session (`Failed to connect to
         * /127.0.0.1:4095`, `Connection reset by peer`, `unexpected end of
         * stream`), which is neither the model's nor the request's fault and is
         * usually fixed by trying again. The card says so instead of leaving the
         * bare transport line under a generic 「出错了」; reads have already
         * retried by the time a failure lands here, so this copy is about the
         * user's own retry ([TransportRetry.isConnectionFailureText] is the
         * matcher, shared with the retry policy so the two cannot drift).
         */
        TRANSPORT,

        OTHER,
    }
    /**
     * A classified provider failure. [status] is the HTTP status when known
     * (the v2 `status` field or an `HTTP {code}` line prefix); [type] the
     * server discriminator (`rate_limit_error`, …) when known. Both stay on
     * the value so the card can keep them visible for troubleshooting even
     * when the kind already names the problem.
     */
    data class ProviderError(
        val kind: ProviderErrorKind,
        val status: Int?,
        val type: String?,
        val message: String?,
    )

    /**
     * Classifies a provider failure without ever dropping it: anything that
     * matches neither the quota nor the region rules returns [OTHER] with the
     * original code/type preserved for the troubleshooter.
     *
     * Quota: HTTP 429, a `rate_limit*` / `quota*` / billing type, or a message
     * about an exhausted Token Plan (`用量上限`, `额度`, `积分`, …). Region: a
     * `country` / `region` / `geo` type, or a "not available in your country"
     * style message. Matching is case-insensitive substring on purpose: the
     * same failure arrives as a `session.error` payload on the SSE path and as
     * an `HTTP {code} {message}` line on the prompt-POST path, where the HTTP
     * layer already dropped the JSON `type`.
     *
     * v31 BUG-3: a **deleted project directory** is checked first. It reaches
     * the card as `HTTP 500 [NotFound] PlatformError: NotFound:
     * FileSystem.realPath` (the server's first `fs.realPath` of the turn — see
     * `core/src/filesystem.ts`), i.e. a 500 whose discriminator or message names
     * `NotFound` / `realPath` / `ENOENT`. The token pair is required rather than
     * `NotFound` alone so a model/file 404 keeps its generic card, and the
     * directory copy is what tells the user the workspace — not the model — is
     * what disappeared.
     */
    fun classifyProviderError(type: String?, message: String?, status: Int?): ProviderError {
        val t = type?.lowercase().orEmpty()
        val m = message?.lowercase().orEmpty()
        val notFound = t.contains("notfound") || t.contains("not_found") ||
            m.contains("notfound") || m.contains("not_found") ||
            m.contains("no such file or directory") || m.contains("enoent")
        val realPath = t.contains("realpath") || m.contains("realpath") || m.contains("real path")
        // A 500 whose discriminator is `NotFound` only means "the directory is
        // gone" when the message names no other missing thing: a provider /
        // model / session / agent that cannot be resolved is a different
        // failure and keeps its generic card.
        val otherMissing = listOf(
            "model", "provider", "session", "agent", "skill", "plugin", "mcp", "worktree",
        ).any { m.contains(it) }
        val locationMissing = realPath ||
            (notFound && (m.contains("filesystem") || m.contains("file system"))) ||
            (status == 500 && notFound && !otherMissing)
        if (locationMissing) {
            return ProviderError(ProviderErrorKind.LOCATION_MISSING, status, type, message)
        }
        val quota = status == 429 ||
            t.contains("rate_limit") || t.contains("ratelimit") ||
            t.contains("quota") || t.contains("usage_exceed") || t.contains("usage_limit") ||
            t.contains("billing") || t.contains("insufficient") ||
            t.contains("free_tier_limit") || t.contains("account_rate_limit") ||
            m.contains("rate limit") || m.contains("rate_limit") || m.contains("ratelimit") ||
            m.contains("quota") || m.contains("usage limit") || m.contains("usage exceed") ||
            m.contains("insufficient") || m.contains("billing") ||
            m.contains("top up") || m.contains("top-up") ||
            m.contains("用量上限") || m.contains("额度") || m.contains("积分") ||
            m.contains("套餐") || m.contains("token plan")
        if (quota) return ProviderError(ProviderErrorKind.QUOTA_EXHAUSTED, status, type, message)
        val region = t.contains("countr") || t.contains("region") || t.contains("geo") ||
            m.contains("not available in your country") || m.contains("not supported in your region") ||
            m.contains("country") || m.contains("region") ||
            m.contains("地域") || m.contains("地区") || m.contains("国家")
        if (region) return ProviderError(ProviderErrorKind.REGION_RESTRICTED, status, type, message)
        // v32 BUG-1: the socket, not the model. Last of the named kinds on
        // purpose — a 429 that happens to arrive while the link is flaky is
        // still a 429, and a provider's own words keep their own card.
        if (TransportRetry.isConnectionFailureText(message)) {
            return ProviderError(ProviderErrorKind.TRANSPORT, status, type, message)
        }
        return ProviderError(ProviderErrorKind.OTHER, status, type, message)
    }

    /**
     * Classifies what the store actually holds: [error] is the human line,
     * [errorDetail] the raw `session.error` JSON (its `type` / `message` /
     * `status`). Either may be null (a prompt-POST failure has no detail; an
     * `HTTP {code}` line prefix is the only status source there). The
     * discriminator prefers the detail JSON, then the line's `[type]` bracket
     * (`V2Api.errorText`), then an inline `"type":"…"` — keywords last.
     */
    fun classifyStoredError(error: String?, errorDetail: String?): ProviderError {
        var type: String? = null
        var message: String? = error?.takeIf { it.isNotBlank() }
        var status: Int? = null
        errorDetail?.let {
            runCatching { JSONObject(it) }.getOrNull()?.let { detail ->
                type = detail.optStr("type")
                detail.optStr("message")?.let { m -> message = m }
                if (detail.has("status")) status = detail.optInt("status").takeIf { c -> c in 100..599 }
            }
        }
        val line = message ?: errorDetail?.takeIf { it.isNotBlank() }
        // The HTTP line prefix is the only status source on the prompt-POST
        // path — read it from the original line too, not just the resolved
        // message (the detail payload may replace the whole line).
        status = status
            ?: error?.let { HTTP_CODE_REGEX.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
            ?: line?.let { HTTP_CODE_REGEX.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
        type = type
            ?: line?.let { BRACKET_TYPE_REGEX.find(it)?.groupValues?.getOrNull(1) }
            ?: line?.let { ERROR_TYPE_REGEX.find(it)?.groupValues?.getOrNull(1) }
        return classifyProviderError(type, message ?: line, status)
    }

    fun parsePart(obj: JSONObject): UiPart {
        val state = obj.optJSONObject("state")
        val output = state?.opt("output")
        return UiPart(
            id = obj.optString("id", "part:${obj.hashCode()}"),
            type = obj.optString("type", "unknown"),
            text = obj.optString("text", ""),
            // The optional display fields read through `optStr`: a JSON null on
            // any of them would otherwise show up as the literal "null" (on a
            // tool card's name, a file chip's name, a link's target…).
            toolName = obj.optStr("tool"),
            toolStatus = state?.optStr("status"),
            mime = obj.optStr("mime"),
            filename = obj.optStr("filename"),
            url = obj.optStr("url"),
            toolInput = state?.optJSONObject("input")?.toString()?.let(::capToolText),
            toolOutput = when (output) {
                null, JSONObject.NULL -> null
                is String -> output.takeIf { it.isNotEmpty() }?.let(::capToolText)
                else -> output.toString().takeIf { it.isNotEmpty() }?.let(::capToolText)
            },
            toolError = state?.optStr("error")?.let(::capToolText),
            // Route C: keep the payload for the part types that have no
            // first-class field (see [UiPart.rawJson]).
            rawJson = obj.takeIf { it.optString("type") in UiPart.RAW_JSON_TYPES }?.toString(),
        )
    }

    /**
     * Tool bodies feed DiffUtil comparisons every stream tick; huge outputs
     * would make every 80ms rebind compare megabytes. The card scrolls, so a
     * cap only trims what the user cannot reasonably read anyway.
     *
     * A raw UTF-16 slice would both split surrogate pairs and — for
     * `state.input` — cut the serialized JSON mid-value: `parseObject` then
     * returns null and the card silently loses its subtitle, diff, shell
     * command and patch text. JSON payloads therefore shrink by string value
     * (largest first) so the capped text still parses; anything else falls
     * back to the surrogate-safe [ToolPresentation.capChars].
     */
    private fun capToolText(value: String): String {
        if (value.length <= MAX_TOOL_TEXT_CHARS) return value
        shrinkJsonToFit(value, MAX_TOOL_TEXT_CHARS)?.let { return it }
        return ToolPresentation.capChars(value, MAX_TOOL_TEXT_CHARS) + "\n… (truncated)"
    }

    /** One string leaf of an over-cap JSON payload: current value + setter. */
    private class JsonTextSlot(val get: () -> String, val set: (String) -> Unit)

    /**
     * Shortens the string values of an over-cap JSON object/array — longest
     * first, cut surrogate-safe and marked with `…` — until the re-serialized
     * payload fits [max], so the capped text stays parseable. Returns null
     * when [value] is not JSON or the non-string structure alone exceeds
     * [max] (the caller then falls back to a plain capped slice).
     */
    private fun shrinkJsonToFit(value: String, max: Int): String? {
        val head = value.trimStart().firstOrNull()
        if (head != '{' && head != '[') return null
        val root = try {
            JSONTokener(value).nextValue()
        } catch (_: Exception) {
            return null
        }
        if (root !is JSONObject && root !is JSONArray) return null
        val slots = ArrayList<JsonTextSlot>()
        collectJsonSlots(root, slots)
        var text = root.toString()
        var guard = slots.size * 2 + 4
        while (text.length > max && guard-- > 0) {
            // Longest value first: it is almost always the field worth keeping
            // a prefix of (write `content`, edit `oldString`/`newString`).
            val victim = slots.maxByOrNull { it.get().length }?.takeIf { it.get().length > 1 }
                ?: return null
            val overflow = text.length - max
            val keep = (victim.get().length - overflow - 1).coerceAtLeast(0)
            val cut = ToolPresentation.capChars(victim.get(), keep)
            victim.set(if (cut.isEmpty()) "" else cut + "…")
            text = root.toString()
        }
        return text.takeIf { it.length <= max }
    }

    private fun collectJsonSlots(node: Any, out: MutableList<JsonTextSlot>) {
        when (node) {
            is JSONObject -> for (key in node.keys().asSequence().toList()) {
                when (val child = node.opt(key)) {
                    is String -> out += JsonTextSlot({ node.optString(key) }, { node.put(key, it) })
                    is JSONObject, is JSONArray -> collectJsonSlots(child, out)
                }
            }
            is JSONArray -> for (index in 0 until node.length()) {
                when (val child = node.opt(index)) {
                    is String -> out += JsonTextSlot({ node.optString(index) }, { node.put(index, it) })
                    is JSONObject, is JSONArray -> collectJsonSlots(child, out)
                }
            }
        }
    }

    /**
     * Session that owns this v2 frame: `data.sessionID` (every session-scoped
     * payload, `session-event.ts` `Base`), else the durable aggregate id.
     * `null` = the event carries no session routing (`server.connected`,
     * catalog signals). v1 `properties` envelopes are gone with `parseEvent`.
     */
    fun eventSessionId(data: JSONObject): String? {
        val payload = data.optJSONObject("data") ?: data
        // Routing key: a `{"sessionID":null}` frame must not route to the session
        // named "null" (it carries no routing, like a missing key).
        return payload.optStr("sessionID")
            ?: data.optJSONObject("durable")?.optStr("aggregateID")
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
