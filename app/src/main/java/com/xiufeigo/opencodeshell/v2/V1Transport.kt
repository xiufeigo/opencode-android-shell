package com.xiufeigo.opencodeshell.v2

import com.xiufeigo.opencodeshell.ChatStore
import com.xiufeigo.opencodeshell.OpenCodeClient
import okhttp3.HttpUrl
import okhttp3.Request
import org.json.JSONObject

/**
 * Wave 1 v1 transport: wraps the existing [OpenCodeClient] with
 * byte-identical behavior. Every method delegates to the exact v1 call the
 * ViewModel made before the seam existed, so v1 servers (e.g. 1.18.31)
 * observe zero behavior change.
 *
 * Frame parsing is the existing [ChatStore.parseEvent] path unchanged.
 */
class V1Transport(
    private val client: OpenCodeClient,
    private val baseHttpUrl: HttpUrl,
    private val directory: String? = null,
) : SessionTransport {

    override val generation: ServerGeneration = ServerGeneration.V1

    override fun close() {
        client.close()
    }

    // ------------------------------------------------------------ snapshot

    override fun snapshotMessages(sessionID: String, limit: Int): List<ChatStore.UiMessage> =
        client.messages(sessionID, directory = directory, limit = limit).map { it.toUi() }

    override fun fetchSession(sessionID: String): SessionRow? = try {
        client.session(sessionID, directory).toRow()
    } catch (_: Exception) {
        null
    }

    override fun fetchPermissions(sessionID: String): List<OpenCodeClient.PermissionRequest> =
        // v1 has one location-scoped list; the store gates by session on merge.
        client.listPermissions(directory)

    // --------------------------------------------------------------- writes

    override fun sendPrompt(
        sessionID: String,
        text: String,
        files: List<V2Api.PromptFile>,
        messageID: String?,
        delivery: String?,
        resume: Boolean?,
    ): String? {
        // v1 `prompt_async` has no delivery/resume/messageID knobs: parts only.
        // `V2Api.PromptFile` is `{uri,…}` while v1 file parts need
        // `{mime,filename,url}` — the data-URL uri carries mime+bytes, so reuse
        // the existing file-part mapping via UiPart shims.
        val parts = files.map { file ->
            ChatStore.UiPart(
                id = "att:${file.name}:${file.uri.hashCode()}",
                type = "file",
                mime = mimeOf(file.uri),
                filename = file.name ?: "file",
                url = file.uri,
            )
        }
        client.promptAsync(sessionID, text, parts, directory = directory)
        // The v1 ack carries no usable message id — the queue is v2-only.
        return null
    }

    override fun switchAgent(sessionID: String, agent: String) {
        // v1 pins the agent inside the next `prompt_async` body — there is no
        // standalone switch route. No-op here; `submit` already threads the
        // pinned agent into every v1 write.
    }

    override fun switchModel(sessionID: String, providerID: String, modelID: String, variant: String?) {
        // Same as agent: v1 carries the model in the next write body. No-op.
    }

    override fun sendShell(sessionID: String, command: String, messageID: String?) {
        // v1 `shellCommand` requires an agent; the caller (`submit`) resolves
        // the effective agent itself, so this overload is unused on v1 — the
        // ViewModel keeps calling `client.shellCommand` via [sendShellAs].
        throw UnsupportedOperationException("v1 shell goes through sendShellAs(agent)")
    }

    /** v1 shell turn with the caller's resolved agent (mirrors the old `submit` branch). */
    fun sendShellAs(sessionID: String, command: String, agent: String, model: Pair<String, String>?) {
        client.shellCommand(sessionID, command, agent, directory = directory, model = model)
    }

    /** v1 slash command with the full legacy body (mirrors the old `submit` branch). */
    fun sendCommandAs(
        sessionID: String,
        command: String,
        arguments: String,
        agent: String?,
        model: Pair<String, String>?,
        variant: String?,
    ) {
        client.runCommand(sessionID, command, arguments, directory, agent, model, variant)
    }

    override fun sendCommand(
        sessionID: String,
        name: String,
        text: String,
        files: List<V2Api.PromptFile>,
        delivery: String?,
    ) {
        throw UnsupportedOperationException("v1 command goes through sendCommandAs(...)")
    }

    override fun interrupt(sessionID: String, resume: Boolean?) {
        client.abort(sessionID, directory)
    }

    override fun replyPermission(sessionID: String, requestID: String, decision: String, message: String?) {
        client.replyPermission(requestID, decision, directory)
    }

    // -------------------------------------------------------------- sessions

    override fun createSession(directory: String?, title: String?): String =
        client.createSession(directory, title).id

    override fun renameSession(sessionID: String, title: String): String? = try {
        client.renameSession(sessionID, title, directory).title
    } catch (_: Exception) {
        null
    }

    override fun deleteSession(sessionID: String): Boolean = try {
        client.deleteSession(sessionID, directory)
    } catch (_: Exception) {
        false
    }

    override fun stageRevert(sessionID: String, messageID: String): String? = try {
        client.sessionRevert(sessionID, messageID, directory = directory).revertMessageID
    } catch (_: Exception) {
        null
    }

    override fun clearRevert(sessionID: String) {
        client.sessionUnrevert(sessionID, directory)
    }

    override fun commitRevert(sessionID: String) {
        // v1 has no commit step: `sessionRevert` applies immediately.
    }

    override fun forkSession(sessionID: String, before: String?): String? = try {
        client.sessionFork(sessionID, before, directory).id
    } catch (_: Exception) {
        null
    }

    // ---------------------------------------------------------------- streams

    override fun streamRequests(sessionID: String?, afterSeq: Long?): List<Request> =
        listOf(
            Request.Builder()
                .url(
                    baseHttpUrl.newBuilder()
                        .addPathSegment("global")
                        .addPathSegment("event")
                        .build(),
                )
                .header("Accept", "text/event-stream")
                .build(),
        )

    override fun parseFrame(type: String, data: JSONObject): List<ChatStore.Event> =
        listOfNotNull(ChatStore.parseEvent(type, data))

    override fun frameSessionID(data: JSONObject): String? = ChatStore.eventSessionId(data)

    // ---------------------------------------------------------------- mapping

    private fun OpenCodeClient.Message.toUi(): ChatStore.UiMessage = ChatStore.UiMessage(
        id = id,
        role = role,
        timeCreated = timeCreated,
        parts = parts.map { it.toUiPart() },
        tokens = tokens,
        agent = agent,
        model = listOfNotNull(providerID, modelID).joinToString("/").takeIf { it.isNotEmpty() },
        timeCompleted = timeCompleted,
        interrupted = interrupted,
    )

    private fun OpenCodeClient.Part.toUiPart(): ChatStore.UiPart = ChatStore.UiPart(
        id = id,
        type = type,
        text = text.orEmpty(),
        toolName = toolName,
        toolStatus = toolStatus,
        mime = mime,
        filename = filename,
        url = url,
        toolInput = raw.optJSONObject("state")?.optJSONObject("input")?.toString(),
        toolOutput = when (val output = raw.optJSONObject("state")?.opt("output")) {
            null, JSONObject.NULL -> null
            is String -> output.takeIf { it.isNotEmpty() }
            else -> output.toString().takeIf { it.isNotEmpty() }
        },
        toolError = raw.optJSONObject("state")?.optString("error", "")?.takeIf { it.isNotEmpty() },
        rawJson = raw.takeIf { type in ChatStore.UiPart.RAW_JSON_TYPES }?.toString(),
    )

    private fun OpenCodeClient.Session.toRow(): SessionRow = SessionRow(
        id = id,
        title = title,
        directory = directory,
        cost = cost,
        revertMessageID = revertMessageID,
        agent = null,
        providerID = null,
        modelID = null,
    )

    companion object {
        /** `data:<mime>;base64,…` → mime; anything else → octet-stream (v1 file-part rule). */
        fun mimeOf(dataUrl: String): String {
            if (!dataUrl.startsWith("data:")) return "application/octet-stream"
            val end = dataUrl.indexOf(';').takeIf { it > 5 } ?: dataUrl.indexOf(',').takeIf { it > 5 }
            return end?.let { dataUrl.substring(5, it) }?.takeIf { it.contains('/') }
                ?: "application/octet-stream"
        }
    }
}
