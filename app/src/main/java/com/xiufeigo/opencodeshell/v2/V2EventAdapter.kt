package com.xiufeigo.opencodeshell.v2

import com.xiufeigo.opencodeshell.ChatStore
import com.xiufeigo.opencodeshell.OpenCodeClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * Wave 1 v2 event adapter: converts v2 `{id, type, created, data, durable?,
 * location?}` frames into the **same** [ChatStore.Event] values the v1 path
 * produces, so the UI layer needs zero changes.
 *
 * Mapping contract (A5 §1, upstream `_upstream/packages/schema/src/`):
 *
 * - execution/status → `RunStatusChanged` (+ `RetryInfo`); v2 errors are
 *   `{type, message, status?}` (`session-error.ts`) vs v1's
 *   `{name, data:{message}}` — [ChatStore.errorMessageOf] already prefers
 *   `type` and `message`, so both shapes fold through one path.
 * - text/reasoning/tool `started` → empty-part `PartUpdated` (pre-creates the
 *   slot; the synthetic-envelope rule stays as the reorder backstop);
 *   `delta` (ephemeral, no seq) → `PartDelta` string append; `ended`/`called`/
 *   `success`/`failed` (durable) → full-value `PartUpdated` that wins over any
 *   accumulated ephemeral text on replay.
 * - step events ensure the assistant envelope exists and settle run state.
 * - `session.created/deleted` (+ renamed/moved/forked/inbox, which reuse the
 *   created row for list freshness) → `ServerSignal` — the owner maps them
 *   onto revisions exactly like the v1 signals. All other v2-only session
 *   meta/toolchain types are dropped until Wave 2 adds `Signal` rows (the
 *   13-entry pin test forbids silent enum growth).
 * - `permission.asked/replied` → the existing dock events (field renames
 *   `action/resources/save/source{id}` handled here); question REST/events do
 *   **not** exist in v2 — the carrier is `form.created/replied/cancelled`
 *   (`FormCreated`/`FormResolved`, Wave 2 renders the dock).
 * - usage/compaction/shell/synthetic/instruction notices reconcile via the
 *   owner's throttled snapshot — the adapter emits nothing for them.
 *
 * Frames this adapter deliberately drops (return empty): `server.*` beyond
 * `server.connected` (stream state), `session.usage.*` (internal accounting),
 * tool input-delta/progress (no store field — the durable frames that follow
 * carry the values), unknown future types.
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
                    else -> listOf(ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.IDLE))
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
            "session.usage.updated" -> emptyList()
            "session.usage.recorded" -> emptyList()

            // -------------------------------------------------- envelopes
            "session.step.started" -> {
                val assistantID = d.optString("assistantMessageID", "")
                if (assistantID.isEmpty()) {
                    emptyList()
                } else {
                    listOf(
                        ChatStore.Event.PartUpdated(
                            assistantID,
                            ChatStore.UiPart(id = "$assistantID:step", type = "step-start"),
                        ),
                        ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.RUNNING),
                    )
                }
            }
            "session.step.streamed" -> emptyList()
            "session.step.ended", "session.step.failed" -> {
                val assistantID = d.optString("assistantMessageID", "")
                if (assistantID.isEmpty()) {
                    emptyList()
                } else if (frame.type == "session.step.failed") {
                    val raw = d.opt("error")
                    // Terminal tool errors surface on the failed step when no
                    // per-tool frame carried them; interrupts settle to IDLE.
                    if (ChatStore.isInterrupted(raw as? JSONObject)) {
                        listOf(ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.IDLE))
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
                    // field (ChatStoreTest pins the drop), so only the durable
                    // `ended`/`called` frames below carry input.
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
            // Session-scoped notices reconcile via the throttled snapshot in
            // the owner; the adapter emits nothing directly (no new Signal
            // rows — ChatStore.Signal keeps its exact 13 entries so the
            // Wave5 pin test stays green).
            "session.shell.started", "session.shell.ended",
            "session.synthetic", "session.skill.activated",
            "session.instructions.updated",
            "session.message.content.updated",
            "session.compaction.started", "session.compaction.delta",
            "session.compaction.ended", "session.compaction.failed",
            "session.compacted",
            -> emptyList()

            // -------------------------------------------------- session meta
            "session.created" -> signal(ChatStore.Signal.SESSION_CREATED, d)
            "session.deleted" -> signal(ChatStore.Signal.SESSION_DELETED, d)
            // Remaining v2 session-meta/inbox types reuse the created/deleted
            // rows for list freshness (Wave 2 splits them as needed).
            "session.renamed",
            "session.moved",
            "session.forked",
            "session.inbox.enqueued", "session.inbox.delivered",
            "session.inbox.cancelled", "session.inbox.delivery.changed",
            -> signal(ChatStore.Signal.SESSION_CREATED, d)

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
            // v2-only catalog/toolchain types have no Signal row yet (Wave 2);
            // they fall through to the generic branch below (null = dropped).
            "mcp.status.changed", "mcp.tools.changed", "mcp.resources.changed",
            "worktree.updated", "worktree.ready", "worktree.failed",
            "location.shutdown",
            "plugin.updated",
            "shell.created", "shell.exited", "shell.deleted",
            "credential.updated", "credential.switched",
            "session.viewed",
            "session.agent.selected",
            "session.model.selected",
            "session.permissions",
            "session.revert.staged",
            "session.revert.cleared",
            "session.revert.committed",
            -> emptyList()
            "pty.created" -> signal(ChatStore.Signal.PTY_CREATED, d)
            "pty.updated" -> signal(ChatStore.Signal.PTY_UPDATED, d)
            "pty.exited" -> signal(ChatStore.Signal.PTY_EXITED, d)
            "pty.deleted" -> signal(ChatStore.Signal.PTY_DELETED, d)
            "session.viewed" -> emptyList()

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
            ChatStore.Signal.PTY_CREATED, ChatStore.Signal.PTY_UPDATED ->
                d.optJSONObject("info")?.optString("id", "").orEmpty().ifEmpty { d.optString("id", "") }
            ChatStore.Signal.PTY_EXITED, ChatStore.Signal.PTY_DELETED -> d.optString("id", "")
            ChatStore.Signal.FILE_EDITED -> d.optString("file", "")
            else -> ""
        }
        return raw.takeIf { it.isNotEmpty() }
    }

    private fun v2Strings(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i -> array.optString(i, "").takeIf { it.isNotEmpty() } }
    }
}
