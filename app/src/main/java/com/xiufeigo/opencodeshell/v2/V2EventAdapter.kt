package com.xiufeigo.opencodeshell.v2

import com.xiufeigo.opencodeshell.ChatStore
import com.xiufeigo.opencodeshell.OpenCodeClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * Wave 1 v2 event adapter: converts v2 `{id, type, created, data, durable?,
 * location?}` frames (`_upstream/packages/schema/src/event.ts:60-71`) into
 * [ChatStore.Event] values.
 *
 * Mapping contract (A5 §1, upstream `_upstream/packages/schema/src/`):
 *
 * - execution/status → `RunStatusChanged` (+ `RetryInfo`); v2 errors are
 *   `{type, message, status?}` (`session-error.ts`) vs legacy
 *   `{name, data:{message}}` — [ChatStore.errorMessageOf] prefers the readable
 *   `message` either way, so both shapes fold through one path.
 * - text/reasoning/tool `started` → empty-part `PartUpdated` (pre-creates the
 *   slot; the synthetic-envelope rule stays as the reorder backstop);
 *   `delta` (ephemeral, no seq) → `PartDelta` string append; `ended`/`called`/
 *   `success`/`failed` (durable) → full-value `PartUpdated` that wins over any
 *   accumulated ephemeral text on replay.
 * - step events ensure the assistant envelope exists and settle run state;
 *   `step.streamed` is a liveness frame (`session-event.ts:337-345`) → RUNNING.
 * - `session.created/deleted/forked/inbox` → `ServerSignal` for list freshness;
 *   `renamed`/`moved`/`viewed` additionally (or instead) carry their own
 *   effect: title on `SessionRenamed`, a `location-switched` notice row, and
 *   the unread-marker list re-read respectively (upstream
 *   `client/src/solid/data.ts:645-725,1063-1067`).
 * - usage / permissions / selection / revert / compaction /
 *   `message.content.updated` map to their own store events (session cost &
 *   tokens, ruleset, selection, revert boundary, the compaction row, and the
 *   full-value content replacement); `instructions.updated` / `synthetic` /
 *   `shell.*` / `skill.activated` insert the transcript notice rows upstream
 *   inserts (`data.ts:791-837`).
 * - `permission.asked/replied` → the existing dock events (field renames
 *   `action/resources/save/source{id}` handled here); question REST/events do
 *   **not** exist in v2 — the carrier is `form.created/replied/cancelled`
 *   (`FormCreated`/`FormResolved`, Wave 2 renders the dock).
 * - catalog/toolchain domains (`credential.*`, `integration.*`, `plugin.*`,
 *   `worktree.*`, `shell.*`, `persistent-pty.*`, `websearch.*`,
 *   `reference.*`, `skill/command.*`, `lsp.*`, `location.*`, `mcp.*`) fall
 *   through `Signal.ofWireType` as [ChatStore.Signal] invalidations, so nothing
 *   on the schema surface is silently swallowed. The three `mcp.*` frames are
 *   matched explicitly because each carries the server *name* as its subject
 *   (`{server}`, `mcp-event.ts:5-26`), which `subjectOf` needs to map.
 *
 * Frames this adapter deliberately drops (return empty): `server.*` beyond
 * `server.connected` (stream state), tool input-delta/progress (no store field
 * — the durable frames that follow carry the values), `instructions.updated`
 * without `text` (schema: no transcript row, `session-event.ts:263-266`),
 * unknown future types.
 */
object V2EventAdapter {

    /** A v2 event frame already parsed from its `data:` line. */
    data class Frame(
        val id: String,
        val type: String,
        val data: JSONObject,
        val aggregateID: String?,
        val seq: Long?,
        val raw: JSONObject,
    )

    fun frameOf(obj: JSONObject): Frame {
        val durable = obj.optJSONObject("durable")
        return Frame(
            id = obj.optString("id", ""),
            type = obj.optString("type", "unknown"),
            data = obj.optJSONObject("data") ?: JSONObject(),
            aggregateID = durable?.optString("aggregateID", "")?.takeIf { it.isNotEmpty() },
            seq = if (durable?.has("seq") == true) durable.optLong("seq") else null,
            raw = obj,
        )
    }

    /** Owning session of an (unwrapped) v2 frame: `data.sessionID`, the durable aggregate, or the form id. */
    fun sessionID(data: JSONObject): String? {
        // `data` here is the *envelope* (`{…, data:{…}}`), not the payload.
        val payload = data.optJSONObject("data") ?: data
        return payload.optString("sessionID", "")
            .takeIf { it.isNotEmpty() }
            ?: data.optJSONObject("durable")?.optString("aggregateID", "")?.takeIf { it.isNotEmpty() }
            ?: payload.optJSONObject("form")?.optString("sessionID", "")?.takeIf { it.isNotEmpty() }
    }

    /** Adapt one v2 frame into zero or more store events (usually one). */
    fun adapt(type: String, envelope: JSONObject): List<ChatStore.Event> {
        val frame = frameOf(envelope).copy(type = type.ifEmpty { envelope.optString("type", "unknown") })
        return adaptFrame(frame)
    }

    internal fun adaptFrame(frame: Frame): List<ChatStore.Event> {
        val d = frame.data
        return when (frame.type) {
            // -------------------------------------------------- run state
            "session.status" -> {
                val status = d.optJSONObject("status") ?: d
                when (status.optString("type", "")) {
                    "busy" -> listOf(ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.RUNNING))
                    "retry" -> listOf(
                        ChatStore.Event.RunStatusChanged(
                            ChatStore.RunStatus.RUNNING,
                            retry = ChatStore.RetryInfo.fromJson(status),
                        ),
                    )
                    "idle" -> listOf(ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.IDLE))
                    else -> emptyList()
                }
            }
            "session.idle" -> listOf(ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.IDLE))
            "session.execution.started" ->
                listOf(ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.RUNNING))
            "session.execution.succeeded" ->
                listOf(ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.IDLE))
            "session.execution.failed" -> {
                val raw = d.opt("error")
                listOf(
                    ChatStore.Event.RunStatusChanged(
                        ChatStore.RunStatus.ERROR,
                        ChatStore.errorMessageOf(raw) ?: "unknown error",
                        errorDetail = ChatStore.errorRawJsonOf(raw),
                    ),
                )
            }
            "session.execution.interrupted" ->
                // user/superseded end the turn visibly; shutdown resumes the
                // same turn server-side, so keep RUNNING until frames say more.
                when (d.optString("reason", "")) {
                    "shutdown", "inactivity" ->
                        listOf(ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.RUNNING))
                    // The Stop carrier that carries no id. The server writes no
                    // assistant row at all when the abort lands before the
                    // provider produced anything (measured 2.0.18/4095 on a
                    // hanging provider: `session.execution.interrupted` alone,
                    // then the durable `idle{outcome:"interrupted"}` row), so
                    // v73's `step.failed{aborted}` path — which keys on
                    // `assistantMessageID` — never fires and the turn left no
                    // trace on screen (emulator-5556 v74). [Event.TurnInterrupted]
                    // resolves the turn from the transcript instead.
                    else -> listOf(
                        ChatStore.Event.TurnInterrupted(
                            timeCompleted = frame.raw.optLong("created", 0L).takeIf { it > 0L },
                        ),
                        ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.IDLE),
                    )
                }
            "session.retry.scheduled" -> {
                val error = d.optJSONObject("error")
                val message = error?.optString("message", "")?.takeIf { it.isNotBlank() }
                listOf(
                    ChatStore.Event.RunStatusChanged(
                        ChatStore.RunStatus.RUNNING,
                        retry = ChatStore.RetryInfo(
                            attempt = d.optInt("attempt", 0),
                            next = d.optLong("at", 0L),
                            message = message,
                            action = null,
                        ),
                    ),
                )
            }
            // session-level spend (`Money.USD` + `TokenUsage.Info`,
            // session-event.ts:148-168) — `source` on `recorded` is
            // accounting-only and dropped.
            "session.usage.updated", "session.usage.recorded" -> listOf(
                ChatStore.Event.SessionUsageUpdated(
                    sessionID = d.optString("sessionID", ""),
                    cost = d.optDouble("cost", 0.0),
                    tokens = OpenCodeClient.tokenTotal(d.optJSONObject("tokens")),
                ),
            )

            // -------------------------------------------------- envelopes
            "session.step.started" -> {
                val assistantID = d.optString("assistantMessageID", "")
                if (assistantID.isEmpty()) {
                    emptyList()
                } else {
                    listOf(
                        ChatStore.Event.PartUpdated(
                            assistantID,
                            // Steps carry no ordinal (`session.step.started`
                            // schema); the dispatch `started` ms timestamp is
                            // the only per-step unique key — one marker per
                            // step instead of every step on a single id.
                            ChatStore.UiPart(
                                id = "$assistantID:step:${d.optLong("started", 0L)}",
                                type = "step-start",
                            ),
                        ),
                        ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.RUNNING),
                    )
                }
            }
            // The provider response-body boundary (`session-event.ts:337-345`):
            // the step is alive and streaming — same liveness as `step.started`.
            "session.step.streamed" -> listOf(
                ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.RUNNING),
            )
            "session.step.ended", "session.step.failed" -> {
                val assistantID = d.optString("assistantMessageID", "")
                if (assistantID.isEmpty()) {
                    emptyList()
                } else if (frame.type == "session.step.failed") {
                    val raw = d.opt("error")
                    // Terminal tool errors surface on the failed step when no
                    // per-tool frame carried them; interrupts settle to IDLE.
                    if (ChatStore.isInterrupted(raw as? JSONObject)) {
                        // ...and settle the row itself: upstream writes the
                        // abort onto the assistant message (`message-updater`
                        // `step.failed` sets `draft.error`), which is what its
                        // `Interrupted` divider and meta fragment key on. This
                        // shell used to leave that to the next snapshot — a
                        // race the abort's own re-read usually lost, so a
                        // stopped turn kept neither its marker nor, when the
                        // snapshot predated the error write, its flag at all.
                        listOf(
                            ChatStore.Event.AssistantInterrupted(
                                assistantID,
                                error = ChatStore.messageErrorOf(raw),
                                timeCompleted = frame.raw.optLong("created", 0L).takeIf { it > 0L },
                            ),
                            ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.IDLE),
                        )
                    } else {
                        listOf(
                            ChatStore.Event.RunStatusChanged(
                                ChatStore.RunStatus.ERROR,
                                ChatStore.errorMessageOf(raw) ?: "unknown error",
                                errorDetail = ChatStore.errorRawJsonOf(raw),
                            ),
                        )
                    }
                } else {
                    // Cost/tokens land on the assistant row via the next message
                    // snapshot; the step end itself only confirms liveness.
                    listOf(ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.RUNNING))
                }
            }

            // -------------------------------------------------- text deltas
            "session.text.started" -> partSlot(d, "text")
            "session.reasoning.started" -> partSlot(d, "reasoning")
            "session.text.delta" -> partDelta(d, "text")
            "session.reasoning.delta" -> partDelta(d, "reasoning")
            "session.text.ended" -> partFull(d, "text")
            "session.reasoning.ended" -> partFull(d, "reasoning")

            // -------------------------------------------------- tool deltas
            "session.tool.input.started" -> {
                val assistantID = d.optString("assistantMessageID", "")
                val callID = d.optString("id", "")
                if (assistantID.isEmpty() || callID.isEmpty()) {
                    emptyList()
                } else {
                    listOf(
                        ChatStore.Event.PartUpdated(
                            assistantID,
                            ChatStore.UiPart(
                                id = callID,
                                type = "tool",
                                toolName = d.optString("name", "").takeIf { it.isNotEmpty() },
                                toolStatus = "streaming",
                            ),
                        ),
                    )
                }
            }
            "session.tool.input.delta" -> {
                val assistantID = d.optString("assistantMessageID", "")
                val callID = d.optString("id", "")
                val delta = d.optString("delta", "")
                if (assistantID.isEmpty() || callID.isEmpty() || delta.isEmpty()) {
                    emptyList()
                } else {
                    // Input streams as JSON text; the store has no input-delta
                    // field, so only the durable `ended`/`called` frames below
                    // carry input.
                    emptyList()
                }
            }
            "session.tool.input.ended" -> {
                val assistantID = d.optString("assistantMessageID", "")
                val callID = d.optString("id", "")
                if (assistantID.isEmpty() || callID.isEmpty()) {
                    emptyList()
                } else {
                    val text = d.optString("text", "")
                    listOfNotNull(
                        // Full-value cover for the ephemeral input appends.
                        ChatStore.Event.PartUpdated(
                            assistantID,
                            ChatStore.UiPart(
                                id = callID,
                                type = "tool",
                                toolStatus = "streaming",
                                toolInput = text.takeIf { it.isNotEmpty() },
                            ),
                        ),
                    )
                }
            }
            "session.tool.called" -> {
                val assistantID = d.optString("assistantMessageID", "")
                val callID = d.optString("id", "")
                if (assistantID.isEmpty() || callID.isEmpty()) {
                    emptyList()
                } else {
                    listOf(
                        ChatStore.Event.PartUpdated(
                            assistantID,
                            ChatStore.UiPart(
                                id = callID,
                                type = "tool",
                                toolStatus = "running",
                                toolInput = d.optJSONObject("input")?.toString(),
                            ),
                        ),
                    )
                }
            }
            "session.tool.progress" -> {
                // Ephemeral live metadata: the store has no progress field —
                // the durable `success`/`failed` frames carry the outcome.
                emptyList()
            }
            "session.tool.success" -> toolTerminal(d, status = "completed")
            "session.tool.failed" -> toolTerminal(d, status = "error")

            // -------------------------------------------------- notices
            // Transcript notice rows (upstream inserts `SessionMessage` rows,
            // `data.ts:791-837`): instructions, synthetic, skill, shell.
            // Baseline / empty-rendering instruction deltas carry no `text`
            // and produce no row (`session-event.ts:263-266`).
            "session.instructions.updated" -> {
                val text = d.optString("text", "").takeIf { it.isNotEmpty() }
                if (text == null) {
                    emptyList()
                } else {
                    listOf(notice(frame, type = "system", text = text, rawJson = d.toString()))
                }
            }
            "session.synthetic" -> listOf(
                notice(
                    frame,
                    type = "synthetic",
                    text = d.optString("text", ""),
                    toolName = d.optString("description", "").takeIf { it.isNotEmpty() },
                    rawJson = d.toString(),
                ),
            )
            "session.skill.activated" -> listOf(
                // `SessionMessage.Skill{skill, name, text}`
                // (`session-message.ts:104-110`).
                notice(
                    frame,
                    type = "skill",
                    text = d.optString("text", ""),
                    toolName = d.optString("name", ""),
                    rawJson = d.toString(),
                ),
            )
            "session.shell.started" -> listOf(shellNotice(frame, d))
            "session.shell.ended" -> {
                val shell = d.optJSONObject("shell")
                val shellID = shell?.optString("id", "") ?: ""
                if (shellID.isEmpty()) {
                    emptyList()
                } else {
                    listOf(
                        ChatStore.Event.ShellNoticeEnded(
                            sessionID = d.optString("sessionID", ""),
                            shellID = shellID,
                            text = shellText(shell, d.optJSONObject("output")),
                            toolStatus = shell.optString("status", ""),
                            toolOutput = d.optJSONObject("output")?.optString("output", "")?.takeIf { it.isNotEmpty() },
                            rawJson = d.toString(),
                        ),
                    )
                }
            }
            "session.message.content.updated" -> {
                val messageID = d.optString("messageID", "")
                if (messageID.isEmpty()) {
                    emptyList()
                } else {
                    listOf(
                        ChatStore.Event.MessageContentUpdated(
                            messageID,
                            contentParts(messageID, d.optJSONArray("content")),
                        ),
                    )
                }
            }

            // One running compaction row (`CompactionProgress`). Phases without
            // a row id of their own resolve against it at reduce time, like
            // upstream's running-row lookup (`data.ts:1093-1158`).
            "session.compaction.started" -> listOf(
                ChatStore.Event.CompactionProgress(
                    sessionID = d.optString("sessionID", ""),
                    phase = "started",
                    rowID = d.optString("inputID", "").takeIf { it.isNotEmpty() } ?: noticeID(frame.id),
                    rawJson = d.toString(),
                ),
            )
            "session.compaction.delta" -> listOf(
                ChatStore.Event.CompactionProgress(
                    sessionID = d.optString("sessionID", ""),
                    phase = "delta",
                    text = d.optString("text", "").takeIf { it.isNotEmpty() },
                    rawJson = d.toString(),
                ),
            )
            "session.compaction.ended" -> listOf(
                ChatStore.Event.CompactionProgress(
                    sessionID = d.optString("sessionID", ""),
                    phase = "ended",
                    rowID = noticeID(frame.id),
                    text = d.optString("text", ""),
                    rawJson = d.toString(),
                ),
            )
            "session.compaction.failed" -> listOf(
                ChatStore.Event.CompactionProgress(
                    sessionID = d.optString("sessionID", ""),
                    phase = "failed",
                    rowID = d.optString("inputID", "").takeIf { it.isNotEmpty() } ?: noticeID(frame.id),
                    text = ChatStore.errorMessageOf(d.opt("error")),
                    rawJson = d.toString(),
                ),
            )
            "session.compacted" -> listOf(
                ChatStore.Event.CompactionProgress(
                    sessionID = d.optString("sessionID", ""),
                    phase = "compacted",
                ),
            )

            // -------------------------------------------------- session meta
            "session.created" -> signal(ChatStore.Signal.SESSION_CREATED, d)
            "session.deleted" -> signal(ChatStore.Signal.SESSION_DELETED, d)
            // Fork / inbox change the session list (`session.forked`'s
            // `sessionID` is the child, `session-event.ts:180-194`).
            "session.forked",
            "session.inbox.enqueued", "session.inbox.delivered",
            "session.inbox.cancelled", "session.inbox.delivery.changed",
            -> signal(ChatStore.Signal.SESSION_CREATED, d)
            // Title follows immediately; the signal re-reads list rows too.
            "session.renamed" -> listOf(
                ChatStore.Event.SessionRenamed(
                    sessionID = d.optString("sessionID", ""),
                    title = d.optString("title", ""),
                ),
            ) + signal(ChatStore.Signal.SESSION_CREATED, d)
            // Directory/project change (`session-inbox.ts:31-35` MovePayload):
            // a `location-switched` notice row (upstream `data.ts:704-725`)
            // plus the list re-read that regroups the session.
            "session.moved" -> listOf(
                notice(frame, type = "location-switched", rawJson = d.toString()),
            ) + signal(ChatStore.Signal.SESSION_CREATED, d)
            // Unread marker lives on the session list row — same re-read as
            // upstream's `session.invalidate + sync` (`data.ts:1063-1067`).
            "session.viewed" -> signal(ChatStore.Signal.SESSION_CREATED, d)
            // The six events below all write the session **row** server-side:
            // each one sets `time_updated: event.created`
            // (`core/src/session/projector.ts:551,562,579,716,726,761`), which is
            // the key the session list is ordered by — so each also stales the
            // *list* ([ChatStore.Signal.SESSION_UPDATED]). Without this a model /
            // agent switch, a permissions write or a revert left the drawer's
            // order on its pre-action snapshot until the screen was re-entered
            // (the v27 device run: the active session's server `updated` was the
            // newest, the drawer still listed it below an older row).
            "session.agent.selected" -> listOf(
                notice(
                    frame,
                    type = "agent-switched",
                    agent = d.optString("agent", "").takeIf { it.isNotEmpty() },
                    rawJson = d.toString(),
                ),
                ChatStore.Event.SessionSelectionChanged(
                    sessionID = d.optString("sessionID", ""),
                    agent = d.optString("agent", ""),
                ),
            ) + signal(ChatStore.Signal.SESSION_UPDATED, d)
            "session.model.selected" -> {
                val payload = d.optJSONObject("model")
                val model = modelRef(payload)
                val variant = payload?.optString("variant", "")?.takeIf { it.isNotEmpty() && it != "default" }
                val noticeRow = if (isInitialModelSelection(d.opt("previous"))) {
                    emptyList()
                } else {
                    listOf(
                        notice(frame, type = "model-switched", model = model, rawJson = d.toString()),
                    )
                }
                noticeRow + ChatStore.Event.SessionSelectionChanged(
                    sessionID = d.optString("sessionID", ""),
                    model = model,
                    variant = variant,
                ) + signal(ChatStore.Signal.SESSION_UPDATED, d)
            }
            // The row write rides *outside* the payload parse: `permissions` is a
            // `Permission.Ruleset` **array** (`schema/src/permission.ts:65`), so
            // the `optJSONObject` read never matched a real frame — but the
            // server wrote the row either way, and that is what the list cares
            // about.
            "session.permissions" -> {
                val permissions = rulesetJson(d)
                val updated = if (permissions == null) {
                    emptyList()
                } else {
                    listOf(
                        ChatStore.Event.SessionPermissionsUpdated(
                            sessionID = d.optString("sessionID", ""),
                            permissionsJson = permissions,
                        ),
                    )
                }
                updated + signal(ChatStore.Signal.SESSION_UPDATED, d)
            }
            "session.revert.staged" -> {
                val revert = d.optJSONObject("revert")
                val messageID = revert?.optString("messageID", "")?.takeIf { it.isNotEmpty() }
                if (revert == null || messageID == null) {
                    emptyList()
                } else {
                    listOf(
                        ChatStore.Event.SessionRevertStaged(
                            sessionID = d.optString("sessionID", ""),
                            messageID = messageID,
                            rawJson = revert.toString(),
                        ),
                    ) + signal(ChatStore.Signal.SESSION_UPDATED, d)
                }
            }
            "session.revert.cleared" -> listOf(
                ChatStore.Event.SessionRevertCleared(d.optString("sessionID", "")),
            ) + signal(ChatStore.Signal.SESSION_UPDATED, d)
            "session.revert.committed" -> d.optString("to", "").takeIf { it.isNotEmpty() }?.let { to ->
                listOf(
                    ChatStore.Event.SessionRevertCommitted(
                        sessionID = d.optString("sessionID", ""),
                        to = to,
                    ),
                ) + signal(ChatStore.Signal.SESSION_UPDATED, d)
            } ?: emptyList()

            // -------------------------------------------------- dock asks
            "permission.asked" -> {
                v2Permission(d)?.let { listOf(ChatStore.Event.PermissionAsked(it)) } ?: emptyList()
            }
            "permission.replied" -> {
                val requestID = d.optString("requestID", "")
                if (requestID.isEmpty()) {
                    emptyList()
                } else {
                    listOf(ChatStore.Event.PermissionReplied(requestID, d.optString("reply", "")))
                }
            }
            "form.created" -> {
                formInfo(d.optJSONObject("form"))?.let { listOf(ChatStore.Event.FormCreated(it)) }
                    ?: emptyList()
            }
            "form.replied" -> {
                val id = d.optString("id", "")
                if (id.isEmpty()) {
                    emptyList()
                } else {
                    listOf(ChatStore.Event.FormResolved(id))
                }
            }
            "form.cancelled" -> {
                val id = d.optString("id", "")
                if (id.isEmpty()) {
                    emptyList()
                } else {
                    listOf(ChatStore.Event.FormResolved(id))
                }
            }

            // -------------------------------------------------- catalogs
            "provider.updated" -> signal(ChatStore.Signal.PROVIDER_UPDATED, d)
            "model.updated" -> signal(ChatStore.Signal.MODEL_UPDATED, d)
            "agent.updated" -> signal(ChatStore.Signal.AGENT_UPDATED, d)
            "config.updated" -> signal(ChatStore.Signal.CONFIG_UPDATED, d)
            "project.updated" -> signal(ChatStore.Signal.PROJECT_UPDATED, d)
            "vcs.branch.updated" -> signal(ChatStore.Signal.VCS_BRANCH_UPDATED, d)
            // v2 counterpart of v1 `file.edited{file}` (`filesystem.ts:6-13`).
            "filesystem.changed" -> signal(ChatStore.Signal.FILE_EDITED, d)
            "pty.created" -> signal(ChatStore.Signal.PTY_CREATED, d)
            "pty.updated" -> signal(ChatStore.Signal.PTY_UPDATED, d)
            "pty.exited" -> signal(ChatStore.Signal.PTY_EXITED, d)
            "pty.deleted" -> signal(ChatStore.Signal.PTY_DELETED, d)
            // `mcp-event.ts:5-26` — one event per MCP server as its status /
            // tool list / resource list settles (`core/src/mcp/index.ts:362-508`).
            // Upstream invalidates the MCP list on status and the resource
            // catalog on resources (`client/src/solid/data.ts:1284-1294`); the
            // server emits one `status.changed` per server on boot, so a burst
            // must settle into one refetch rather than N.
            "mcp.status.changed" -> signal(ChatStore.Signal.MCP_STATUS_CHANGED, d)
            "mcp.tools.changed" -> signal(ChatStore.Signal.MCP_TOOLS_CHANGED, d)
            "mcp.resources.changed" -> signal(ChatStore.Signal.MCP_RESOURCES_CHANGED, d)

            // `server.connected` is stream state, not store state.
            "server.connected", "log.synced" -> emptyList()

            else -> ChatStore.Signal.ofWireType(frame.type)?.let {
                signal(it, d)
            } ?: emptyList()
        }
    }

    // ------------------------------------------------------------ parts

    private fun partID(assistantID: String, ordinal: Int, kind: String): String =
        "$assistantID:$kind:$ordinal"

    private fun partSlot(d: JSONObject, kind: String): List<ChatStore.Event> {
        val assistantID = d.optString("assistantMessageID", "")
        if (assistantID.isEmpty()) return emptyList()
        val ordinal = d.optInt("ordinal", 0)
        return listOf(
            ChatStore.Event.PartUpdated(
                assistantID,
                ChatStore.UiPart(id = partID(assistantID, ordinal, kind), type = kind),
            ),
        )
    }

    private fun partDelta(d: JSONObject, kind: String): List<ChatStore.Event> {
        val assistantID = d.optString("assistantMessageID", "")
        val delta = d.optString("delta", "")
        if (assistantID.isEmpty() || delta.isEmpty()) return emptyList()
        val ordinal = d.optInt("ordinal", 0)
        return listOf(ChatStore.Event.PartDelta(assistantID, partID(assistantID, ordinal, kind), "text", delta))
    }

    private fun partFull(d: JSONObject, kind: String): List<ChatStore.Event> {
        val assistantID = d.optString("assistantMessageID", "")
        if (assistantID.isEmpty()) return emptyList()
        val ordinal = d.optInt("ordinal", 0)
        return listOf(
            ChatStore.Event.PartUpdated(
                assistantID,
                ChatStore.UiPart(
                    id = partID(assistantID, ordinal, kind),
                    type = kind,
                    text = d.optString("text", ""),
                ),
            ),
        )
    }

    private fun toolTerminal(d: JSONObject, status: String): List<ChatStore.Event> {
        val assistantID = d.optString("assistantMessageID", "")
        val callID = d.optString("id", "")
        if (assistantID.isEmpty() || callID.isEmpty()) return emptyList()
        val content = d.optJSONArray("content")
        val output = content?.let { toolContentText(it) }
        val error = d.optJSONObject("error")?.optString("message", "")?.takeIf { it.isNotEmpty() }
        val input = d.optJSONObject("input")?.toString()
        return listOf(
            ChatStore.Event.PartUpdated(
                assistantID,
                ChatStore.UiPart(
                    id = callID,
                    type = "tool",
                    toolStatus = status,
                    toolInput = input,
                    toolOutput = output,
                    toolError = if (status == "error") error else null,
                ),
            ),
        )
    }

    /** `tool.content[]`: text blocks concatenate; file blocks become reference lines. */
    fun toolContentText(content: JSONArray): String? {
        if (content.length() == 0) return null
        val out = StringBuilder()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            when (block.optString("type", "")) {
                "text" -> out.append(block.optString("text", ""))
                "file" -> {
                    val uri = block.optString("uri", "").takeIf { it.isNotEmpty() }
                    val mime = block.optString("mime", "").takeIf { it.isNotEmpty() }
                    out.append(if (uri != null) "[file: $uri]" else "[file]")
                    if (mime != null) out.append(" ($mime)")
                }
                else -> out.append(block.optString("text", ""))
            }
        }
        return out.toString().takeIf { it.isNotEmpty() }
    }

    // ------------------------------------------------------------ notices

    /**
     * Row id of an event-built transcript row: upstream's `messageIDFromEvent`
     * mapping (`client/src/solid/data.ts:76`) — `evt_…` → `msg_…`, keeping the
     * ascending suffix so id order matches message order for revert truncation.
     */
    private fun noticeID(eventID: String): String = eventID.replaceFirst("evt_", "msg_")

    /**
     * A transcript notice row (upstream message types `agent-switched` /
     * `model-switched` / `location-switched` / `system` / `synthetic` / `skill`
     * / `shell`, `session-message.ts:40-125`): one `system`-role message with a
     * single part carrying the visible text plus the untouched payload.
     * [messageID] overrides the event-derived id when the wire names the row
     * (the shell id) so later frames upsert the same row.
     */
    private fun notice(
        frame: Frame,
        type: String,
        text: String = "",
        toolName: String? = null,
        toolStatus: String? = null,
        toolOutput: String? = null,
        agent: String? = null,
        model: String? = null,
        messageID: String? = null,
        rawJson: String? = null,
    ): ChatStore.Event.MessageUpdated {
        val id = messageID?.takeIf { it.isNotEmpty() } ?: noticeID(frame.id)
        return ChatStore.Event.MessageUpdated(
            ChatStore.UiMessage(
                id = id,
                // Snapshot rows for these types arrive `role = "assistant"` with
                // one `"$id:notice"` part (`V2Transport.toUi`) — live rows must
                // use the same keys or `mergeParts` shows them twice.
                role = "assistant",
                timeCreated = frame.raw.optLong("created", 0L),
                agent = agent,
                model = model,
                parts = listOf(
                    ChatStore.UiPart(
                        id = "$id:notice",
                        type = type,
                        text = text,
                        toolName = toolName?.takeIf { it.isNotEmpty() },
                        toolStatus = toolStatus,
                        toolOutput = toolOutput,
                        rawJson = rawJson,
                    ),
                ),
            ),
        )
    }

    /**
     * `session.shell.started{shell}` (`session-event.ts:299-307`,
     * `shell.ts:20-43` Info): the row id is `ID.fromEvent(event.id)`
     * (`session-message.ts:27`), which the projector also uses
     * (`message-updater.ts:185-198`) so a later snapshot folds onto it. The
     * untouched payload keeps `shell.id` for [ChatStore.Event.ShellNoticeEnded].
     */
    private fun shellNotice(frame: Frame, d: JSONObject): ChatStore.Event.MessageUpdated {
        val shell = d.optJSONObject("shell") ?: JSONObject()
        return notice(
            frame,
            type = "shell",
            text = shellText(shell, d.optJSONObject("output")),
            toolName = shell.optString("command", "").takeIf { it.isNotEmpty() },
            toolStatus = shell.optString("status", "").takeIf { it.isNotEmpty() },
            toolOutput = d.optJSONObject("output")?.optString("output", "")?.takeIf { it.isNotEmpty() },
            rawJson = d.toString(),
        )
    }

    /** Upstream `noticeText` for shell rows: `command` + `output` (`data.ts` fold). */
    private fun shellText(shell: JSONObject, output: JSONObject?): String =
        listOfNotNull(
            shell.optString("command", "").takeIf { it.isNotEmpty() },
            output?.optString("output", "")?.takeIf { it.isNotEmpty() },
        ).joinToString("\n")

    /**
     * `session.message.content.updated`'s `content[]` is
     * `AssistantContentEncoded` (`session-message.ts:194-202`): text/reasoning
     * blocks become per-kind-ordinal-keyed parts (the live `*.started` formula
     * `messageID:kind:ordinal`); tool blocks keep their call id and `ToolState`
     * fields (`:120-158`).
     */
    private fun contentParts(messageID: String, content: JSONArray?): List<ChatStore.UiPart> {
        if (content == null) return emptyList()
        // Same stable keys as `V2Transport.stableParts`: text/reasoning count a
        // per-kind ordinal (the blocks carry no id), tools keep their call id.
        val ordinals = HashMap<String, Int>()
        return (0 until content.length()).mapNotNull { i ->
            val block = content.optJSONObject(i) ?: return@mapNotNull null
            val type = block.optString("type", "")
            fun ordinalID(): String {
                val ordinal = ordinals.getOrDefault(type, 0)
                ordinals[type] = ordinal + 1
                return partID(messageID, ordinal, type)
            }
            when (type) {
                "text" -> ChatStore.UiPart(
                    id = ordinalID(),
                    type = "text",
                    text = block.optString("text", ""),
                )

                "reasoning" -> ChatStore.UiPart(
                    id = ordinalID(),
                    type = "reasoning",
                    text = block.optString("text", ""),
                )

                "tool" -> {
                    val state = block.optJSONObject("state") ?: JSONObject()
                    val input = state.opt("input")
                    ChatStore.UiPart(
                        id = block.optString("id", "").takeIf { it.isNotEmpty() } ?: ordinalID(),
                        type = "tool",
                        toolName = block.optString("name", "").takeIf { it.isNotEmpty() },
                        toolStatus = state.optString("status", "").takeIf { it.isNotEmpty() },
                        toolInput = when (input) {
                            null, JSONObject.NULL -> null
                            is String -> input.takeIf { it.isNotEmpty() }
                            else -> input.toString()
                        },
                        toolOutput = state.optJSONArray("content")?.let { toolContentText(it) },
                        toolError = ChatStore.errorMessageOf(state.opt("error")),
                        rawJson = block.toString(),
                    )
                }

                else -> null
            }
        }
    }

    /** `Model.Ref` (`model.ts:18-23`) as the `"providerID/modelID"` display pair. */
    private fun modelRef(model: JSONObject?): String? {
        if (model == null) return null
        val modelID = model.optString("id", "")
        if (modelID.isEmpty()) return null
        val providerID = model.optString("providerID", "")
        return if (providerID.isEmpty()) modelID else "$providerID/$modelID"
    }

    // ------------------------------------------------------------ dock asks

    /**
     * v2 `Request{id:per_, sessionID, action, resources[], save?, metadata?,
     * source?{type:tool, messageID, id}}` → the v1 dock row. Field renames
     * (`permission→action`, `patterns→resources`, `always→save`,
     * `tool:{messageID,callID}` → `source:{messageID,id}`) happen here so the
     * dock, optimistic reply, and E3 records keep working unchanged.
     */
    fun v2Permission(obj: JSONObject): OpenCodeClient.PermissionRequest? {
        val id = obj.optString("id", "")
        if (id.isEmpty()) return null
        val source = obj.optJSONObject("source")
        return OpenCodeClient.PermissionRequest(
            id = id,
            sessionID = obj.optString("sessionID", ""),
            permission = obj.optString("action", ""),
            patterns = v2Strings(obj.optJSONArray("resources")),
            always = v2Strings(obj.optJSONArray("save")),
            tool = if (source != null) {
                OpenCodeClient.PermissionRequest.Tool(
                    messageID = source.optString("messageID", ""),
                    callID = source.optString("id", "").takeIf { it.isNotEmpty() }
                        ?: source.optString("callID", ""),
                )
            } else {
                null
            },
            metadata = obj.optJSONObject("metadata"),
        )
    }

    /** Minimal v2 form row for the Wave 2 form dock (fields stay raw JSON). */
    fun formInfo(obj: JSONObject?): ChatStore.FormAsk? {
        if (obj == null) return null
        val id = obj.optString("id", "")
        if (id.isEmpty()) return null
        return ChatStore.FormAsk(
            id = id,
            sessionID = obj.optString("sessionID", ""),
            title = obj.optString("title", ""),
            fieldsJson = obj.optJSONArray("fields")?.toString() ?: "[]",
        )
    }

    // ------------------------------------------------------------ signals

    private fun signal(signal: ChatStore.Signal, d: JSONObject): List<ChatStore.Event> =
        listOf(ChatStore.Event.ServerSignal(signal, sessionOf(d), subjectOf(signal, d)))

    private fun sessionOf(d: JSONObject): String? =
        d.optString("sessionID", "").takeIf { it.isNotEmpty() }

    private fun subjectOf(signal: ChatStore.Signal, d: JSONObject): String? {
        val raw = when (signal) {
            ChatStore.Signal.PROJECT_UPDATED -> d.optString("id", "")
            ChatStore.Signal.PTY_CREATED, ChatStore.Signal.PTY_UPDATED,
            ChatStore.Signal.PERSISTENT_PTY_ADDED,
            ->
                d.optJSONObject("info")?.optString("id", "").orEmpty()
                    .ifEmpty { d.optJSONObject("terminal")?.optString("id", "").orEmpty() }
                    .ifEmpty { d.optString("id", "") }

            ChatStore.Signal.PTY_EXITED, ChatStore.Signal.PTY_DELETED -> d.optString("id", "")
            ChatStore.Signal.PERSISTENT_PTY_REMOVED ->
                d.optString("ptyID", "").ifEmpty { d.optString("id", "") }

            ChatStore.Signal.SHELL_CREATED ->
                d.optJSONObject("info")?.optString("id", "").orEmpty().ifEmpty { d.optString("id", "") }

            ChatStore.Signal.SHELL_EXITED, ChatStore.Signal.SHELL_DELETED -> d.optString("id", "")
            // `{server}` is the MCP server *name* (`mcp-event.ts:5-26`) — the
            // only key that identifies a row in `GET /api/mcp`.
            ChatStore.Signal.MCP_STATUS_CHANGED,
            ChatStore.Signal.MCP_TOOLS_CHANGED,
            ChatStore.Signal.MCP_RESOURCES_CHANGED,
            -> d.optString("server", "")
            ChatStore.Signal.WORKTREE_UPDATED, ChatStore.Signal.WORKTREE_RESOLVED -> d.optString("projectID", "")
            ChatStore.Signal.WORKTREE_READY -> d.optString("name", "")
            ChatStore.Signal.FILE_EDITED -> d.optString("file", "")
            else -> ""
        }
        return raw.takeIf { it.isNotEmpty() }
    }

    private fun v2Strings(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i -> array.optString(i, "").takeIf { it.isNotEmpty() } }
    }

    // ------------------------------------------------------------ payloads

    /**
     * `session.permissions.permissions` as verbatim JSON text — the
     * `Permission.Ruleset` **array** the schema declares
     * (`schema/src/permission.ts:59-66`: `Rule{action, resource, effect}[]`;
     * v1's `{permission, pattern, action}[]` is an array too,
     * `v1/permission.ts:19-24`) that the projector writes wholesale
     * (`core/src/session/projector.ts:576-583` — `set`, not a merge), which is
     * why [ChatStore.Event.SessionPermissionsUpdated] carries the whole
     * replacement. `[]` is a legal frame (every rule cleared) and still emits.
     *
     * The array read is the only shape either protocol ever sent; an object is
     * read the same way anyway, because the field is opaque verbatim JSON and
     * dropping it would silently blank the store's ruleset on a server-side
     * surprise. Scalars, `null` and a missing key are no ruleset at all →
     * `null` (the caller then emits only the list-invalidation signal).
     */
    private fun rulesetJson(d: JSONObject): String? =
        d.optJSONArray("permissions")?.toString() ?: d.optJSONObject("permissions")?.toString()
}

/**
 * Whether a `model-switched` payload names the session's **initial** model
 * rather than a switch, read off its `previous` ref — the model it changed
 * *from* (`SessionEvent.ModelSelected{sessionID, model, previous}`,
 * `core/src/session/session.ts:89-101`).
 *
 * The server publishes that event only when the model actually changed
 * (`switchModel` returns early on an identical ref), so a payload with no
 * `previous` says the session carried no model at all. That is exactly what a
 * session created by this shell looks like: `ChatViewModel.createSession` posts
 * `model = null` and then pins the composer's pick through `switchModel`, so
 * the *first* model a new session ever gets arrives as a `ModelSelected` frame
 * — over the first user turn, as a 「Model changed」 row. Upstream's own gate
 * (`client/src/solid/data.ts:668`, `if (!store.session.message[sessionID])
 * return`) does not catch it: a synced empty session stores `[]`, which is
 * truthy, and the first user message can land before the frame — both of which
 * is what kept the row reproducing on 5558 after the empty-transcript gate.
 *
 * The selection itself is unaffected — the adapter still emits
 * [ChatStore.Event.SessionSelectionChanged] (→ `sessionModel`, the chip) — and a
 * real switch, which always carries a `previous`, still lands. The same rule
 * governs the durable row on the snapshot path
 * ([V2Transport.toUi][V2Transport]), so a reopened session does not grow the
 * row back.
 */
internal fun isInitialModelSelection(previous: Any?): Boolean =
    previous == null || previous == JSONObject.NULL
