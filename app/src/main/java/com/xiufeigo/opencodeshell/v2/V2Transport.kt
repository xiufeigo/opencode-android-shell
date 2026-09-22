package com.xiufeigo.opencodeshell.v2

import com.xiufeigo.opencodeshell.ChatStore
import com.xiufeigo.opencodeshell.OpenCodeClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Wave 1 v2 transport: [V2Api] plus the v2 streams, converging on the same
 * [ChatStore] representations the UI already renders (`UiMessage`/`UiPart`/
 * `RunStatus`), so the UI layer needs zero changes.
 *
 * REST mapping (A5 §2, verified live on 2.0.11 + openapi.json, 113 paths):
 * catalogs unwrap `{location, data}`, session/message unwrap `{data}`, project
 * and config are bare arrays, message pages carry an opaque cursor. Query
 * params: catalogs use `location[directory]`, session list uses bare
 * `directory`, session-scoped reads take no location.
 *
 * Prompt semantics (A5 §2): v2 has no `prompt_async` parts array. Sends are
 * flat `{text, files?, agents?, skills?, metadata?, delivery?, resume?}`;
 * model/agent/variant are switched via their own routes *before* the send
 * (steer only — queue must not reconfigure the running turn), matching
 * upstream `composer/submit.ts`.
 *
 * Blocking calls (like both clients): call from an IO dispatcher.
 */
class V2Transport(
    private val api: V2Api,
) : SessionTransport {

    override val generation: ServerGeneration = ServerGeneration.V2

    override fun close() {
        api.close()
    }

    // ------------------------------------------------------------ snapshot

    override fun snapshotMessages(sessionID: String, limit: Int): List<ChatStore.UiMessage> =
        api.messages(sessionID, limit = limit, order = "desc").data.mapNotNull { it.toUi() }

    override fun fetchSession(sessionID: String): SessionRow? = try {
        api.session(sessionID).toRow()
    } catch (_: Exception) {
        null
    }

    override fun fetchPermissions(sessionID: String): List<OpenCodeClient.PermissionRequest> =
        // Two views exist (`/permission/request` location-wide + session list);
        // the merge is keyed by session, so the session view is authoritative.
        api.sessionPermissions(sessionID).map { it.toV1() }

    override fun activeRunStatus(sessionID: String): String? = try {
        api.activeSessions()[sessionID]?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    // --------------------------------------------------------------- writes

    override fun sendPrompt(
        sessionID: String,
        text: String,
        files: List<V2Api.PromptFile>,
        messageID: String?,
        delivery: String?,
        resume: Boolean?,
    ): String? = sendStructuredPrompt(
        sessionID = sessionID,
        text = text,
        files = files,
        messageID = messageID,
        delivery = delivery,
        resume = resume,
    )

    /**
     * WAVE 2.4b: the full flat prompt body (`request.ts`
     * `buildPromptRequest`) — `files` mentions-first, `agents[]`/`skills[]`,
     * `metadata.displayText`, `delivery`. Returns the admitted user-inbox id
     * (`msg_…`) or null when blank (never measured, but tolerated).
     */
    fun sendStructuredPrompt(
        sessionID: String,
        text: String,
        files: List<V2Api.PromptFile>,
        messageID: String?,
        delivery: String?,
        resume: Boolean?,
        agents: List<JSONObject>? = null,
        skills: List<JSONObject>? = null,
        metadata: JSONObject? = null,
    ): String? {
        val admitted = api.prompt(
            sessionID = sessionID,
            text = text,
            files = files,
            agents = agents,
            skills = skills,
            metadata = metadata,
            delivery = delivery,
            resume = resume,
            messageID = messageID,
        )
        // WAVE 2.4b: the admitted user-inbox id (`msg_…`) keys the optimistic
        // queue row; blank (never measured, but tolerated) means "no key".
        return admitted.id.takeIf { it.isNotBlank() }
    }

    override fun switchAgent(sessionID: String, agent: String) {
        api.switchAgent(sessionID, agent)
    }

    override fun switchModel(sessionID: String, providerID: String, modelID: String, variant: String?) {
        api.switchModel(sessionID, V2Api.ModelRef(modelID, providerID, variant))
    }

    override fun sendShell(sessionID: String, command: String, messageID: String?) {
        api.shell(sessionID, command, messageID)
    }

    override fun sendCommand(
        sessionID: String,
        name: String,
        text: String,
        files: List<V2Api.PromptFile>,
        delivery: String?,
    ) {
        api.command(sessionID, name, text, files, delivery = delivery)
    }

    override fun interrupt(sessionID: String, resume: Boolean?) {
        api.interrupt(sessionID, resume)
    }

    override fun replyPermission(sessionID: String, requestID: String, decision: String, message: String?) {
        api.replyPermission(sessionID, requestID, decision, message)
    }

    // ------------------------------------------------- forms (v2 replaces question REST)

    /**
     * WAVE 2.4b: `GET /api/session/{id}/inbox` items (raw JSON; the VM maps
     * them to [ComposerQueue.Row][com.xiufeigo.opencodeshell.ui.session.composer.ComposerQueue.Row]).
     */
    fun fetchInbox(sessionID: String): List<JSONObject> = api.sessionInbox(sessionID)

    /**
     * WAVE 2.4b: promote a queued follow-up to the running turn
     * (`PATCH …/inbox/{inboxID}{delivery:"steer"}`, `queue.ts` steer).
     */
    fun updateInboxDelivery(sessionID: String, inboxID: String, delivery: String) {
        api.updateInboxDelivery(sessionID, inboxID, delivery)
    }

    /**
     * WAVE 2.4b: drop a queued follow-up (`DELETE …/inbox/{inboxID}`,
     * `queue.ts` remove).
     */
    fun cancelInbox(sessionID: String, inboxID: String) {
        api.cancelInbox(sessionID, inboxID)
    }

    /** `GET /api/session/{id}/form` → pending forms for the session. */
    fun fetchForms(sessionID: String): List<V2Api.FormInfo> = api.sessionForms(sessionID)

    /** `POST …/form/{formID}/reply{answer}` → 204. */
    fun replyForm(sessionID: String, formID: String, answer: Map<String, Any?>) {
        api.replyForm(sessionID, formID, answer)
    }

    /** No v2 route deletes a pending form once answered server-side; the
     * `form.replied` / `form.cancelled` events retire it. `DELETE …/form/{id}`
     * answered 409 live ("form already replied"), so dismissal is local-only. */
    fun dismissForm(sessionID: String, formID: String) {
        // Intentionally no network: v2 has no dismiss route (A5 §2).
    }

    // -------------------------------------------------------------- sessions

    override fun createSession(directory: String?, title: String?): String =
        api.createSession(agent = null, model = null, id = null, title = title, directory = directory).id

    override fun renameSession(sessionID: String, title: String): String? = try {
        api.renameSession(sessionID, title)
        // `PATCH` answers 204 with no body — confirm via re-read.
        api.session(sessionID).title
    } catch (_: Exception) {
        null
    }

    override fun deleteSession(sessionID: String): Boolean = try {
        api.deleteSession(sessionID)
        true
    } catch (_: Exception) {
        false
    }

    override fun stageRevert(sessionID: String, messageID: String): String? = try {
        api.revertStage(sessionID, messageID).messageID
    } catch (_: Exception) {
        null
    }

    override fun clearRevert(sessionID: String) {
        api.revertClear(sessionID)
    }

    override fun commitRevert(sessionID: String) {
        api.revertCommit(sessionID)
    }

    override fun forkSession(sessionID: String, before: String?): String? = try {
        api.fork(sessionID, before).id
    } catch (_: Exception) {
        null
    }

    // ---------------------------------------------------------------- streams

    override fun streamRequests(sessionID: String?, afterSeq: Long?): List<Request> = buildList {
        add(api.globalEventRequest())
        if (sessionID != null) add(api.sessionLogRequest(sessionID, afterSeq, follow = true))
    }

    override fun parseFrame(type: String, data: JSONObject): List<ChatStore.Event> =
        V2EventAdapter.adapt(type, data)

    override fun frameSessionID(data: JSONObject): String? = V2EventAdapter.sessionID(data)

    // ---------------------------------------------------------------- mapping

    private fun V2Api.Message.toUi(): ChatStore.UiMessage? {
        if (id.isEmpty()) return null
        return when (type) {
            V2Api.Message.TYPE_USER -> ChatStore.UiMessage(
                id = id,
                role = "user",
                timeCreated = timeCreated,
                parts = listOf(ChatStore.UiPart(id = "$id:text", type = "text", text = text.orEmpty())),
                tokens = null,
                agent = null,
                model = null,
                timeCompleted = timeCompleted,
                interrupted = false,
            )
            V2Api.Message.TYPE_ASSISTANT -> ChatStore.UiMessage(
                id = id,
                role = "assistant",
                timeCreated = timeCreated,
                parts = stableParts(id, content),
                tokens = OpenCodeClient.tokenTotal(
                    JSONObject()
                        .put("input", tokensInput)
                        .put("output", tokensOutput),
                ),
                agent = agent,
                model = model?.let { "${it.providerID}/${it.id}" },
                timeCompleted = timeCompleted,
                interrupted = ChatStore.isInterrupted(raw.optJSONObject("error")),
            )
            // Notice rows (shell/synthetic/system/skill/agent-switched/
            // model-switched/location-switched/compaction/idle): one text part
            // carrying the human line; types the timeline does not know stay
            // renderable as plain rows instead of vanishing.
            else -> ChatStore.UiMessage(
                id = id,
                role = "assistant",
                timeCreated = timeCreated,
                parts = listOf(
                    ChatStore.UiPart(
                        id = "$id:notice",
                        type = type,
                        text = noticeText(),
                    ),
                ),
                tokens = null,
                agent = agent,
                model = model?.let { "${it.providerID}/${it.id}" },
                timeCompleted = timeCompleted,
                interrupted = false,
            )
        }
    }

    private fun V2Api.Message.noticeText(): String = when (type) {
        V2Api.Message.TYPE_SHELL -> listOfNotNull(command, output).joinToString("\n")
        V2Api.Message.TYPE_COMPACTION -> compactionSummary.orEmpty()
        else -> text.orEmpty()
    }

    /**
     * Snapshot part ids must agree with the live-stream ids the
     * [V2EventAdapter] synthesizes, or `ChatStore.mergeParts` keeps BOTH
     * copies of every part: the server's `content[]` entries carry no `id`
     * for text/reasoning (measured 2026-09-22 on 2.0.11), so a content-hash
     * fallback never matches the stream's `msg_…:text:0` keys — and worse,
     * it changes whenever the part's raw JSON gains a field, stacking a new
     * duplicate on every snapshot. The adapter keys text/reasoning parts by
     * per-kind ordinal and tool parts by their `call_` id (which the server
     * DOES carry), so the same scheme is reproduced here positionally.
     */
    private fun stableParts(messageID: String, content: List<V2Api.Part>): List<ChatStore.UiPart> {
        val ordinals = HashMap<String, Int>()
        return content.map { part ->
            val stableId = when {
                part.type == "text" || part.type == "reasoning" -> {
                    val ordinal = ordinals.getOrDefault(part.type, 0)
                    ordinals[part.type] = ordinal + 1
                    "$messageID:${part.type}:$ordinal"
                }
                part.id.isNotEmpty() -> part.id
                else -> {
                    val ordinal = ordinals.getOrDefault(part.type, 0)
                    ordinals[part.type] = ordinal + 1
                    "$messageID:${part.type}:$ordinal"
                }
            }
            part.toUiPart(stableId)
        }
    }

    private fun V2Api.Part.toUiPart(stableId: String): ChatStore.UiPart = when (type) {
        "reasoning" -> ChatStore.UiPart(id = stableId, type = type, text = text)
        "tool" -> toolPart(stableId)
        else -> ChatStore.UiPart(
            id = stableId,
            type = type.ifEmpty { "text" },
            text = text,
            rawJson = raw.takeIf { type in ChatStore.UiPart.RAW_JSON_TYPES }?.toString(),
        )
    }

    private fun V2Api.Part.toolPart(stableId: String): ChatStore.UiPart {
        val state = raw.optJSONObject("state")
        val status = state?.optString("status", "")?.takeIf { it.isNotEmpty() }
        val input = state?.opt("input")?.let {
            when (it) {
                is JSONObject -> it.toString()
                is String -> it
                else -> it.toString()
            }
        }
        val content = state?.optJSONArray("content")
        val output = content?.let { V2EventAdapter.toolContentText(it) }
            ?: state?.opt("output")?.let {
                when (it) {
                    is String -> it
                    JSONObject.NULL -> null
                    else -> it.toString()
                }
            }
        val error = state?.optJSONObject("error")?.optString("message", "")?.takeIf { it.isNotEmpty() }
            ?: state?.optString("error", "")?.takeIf { it.isNotEmpty() }
        return ChatStore.UiPart(
            id = stableId,
            type = "tool",
            text = "",
            toolName = raw.optString("name", "").takeIf { it.isNotEmpty() },
            toolStatus = status,
            toolInput = input,
            toolOutput = output,
            toolError = error,
        )
    }

    private fun V2Api.SessionInfo.toRow(): SessionRow = SessionRow(
        id = id,
        title = title,
        directory = directory,
        cost = cost,
        revertMessageID = null,
        agent = agent,
        providerID = model?.providerID,
        modelID = model?.id,
    )

    private fun V2Api.PermissionRequest.toV1(): OpenCodeClient.PermissionRequest =
        OpenCodeClient.PermissionRequest(
            id = id,
            sessionID = sessionID,
            permission = action,
            patterns = resources,
            always = save,
            tool = if (messageID != null && callID != null) {
                OpenCodeClient.PermissionRequest.Tool(messageID, callID)
            } else {
                null
            },
            metadata = raw.optJSONObject("metadata"),
        )
}
