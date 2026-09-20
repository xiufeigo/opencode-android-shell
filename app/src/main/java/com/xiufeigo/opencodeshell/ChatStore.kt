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
    )

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
    )

    data class ChatState(
        val messages: List<UiMessage> = emptyList(),
        val runStatus: RunStatus = RunStatus.IDLE,
        val error: String? = null,
    )

    // ------------------------------------------------------------------ events

    sealed interface Event {
        data class Snapshot(val messages: List<UiMessage>) : Event
        data class MessageUpdated(val message: UiMessage) : Event
        data class PartUpdated(val messageID: String, val part: UiPart) : Event
        data class PartDelta(val messageID: String, val partID: String, val field: String, val delta: String) : Event
        data class MessageRemoved(val messageID: String) : Event
        data class RunStatusChanged(val status: RunStatus, val error: String? = null) : Event
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

        is Event.RunStatusChanged -> state.copy(runStatus = event.status, error = event.error)
    }

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
     * (field is usually "text"). The server only emits deltas for a part it has
     * already announced via `*-start`/`part.updated`, so a delta whose message
     * or part is missing is dropped (matches the server dropping orphans).
     * Keeps the current message order; appends never re-sort.
     */
    private fun applyPartDelta(state: ChatState, event: Event.PartDelta): ChatState {
        if (event.field != "text") return state
        val merged = state.messages.toMutableList()
        val messageIndex = merged.indexOfFirst { it.id == event.messageID }
        if (messageIndex < 0) return state
        val message = merged[messageIndex]
        val parts = message.parts.toMutableList()
        val partIndex = parts.indexOfFirst { it.id == event.partID }
        if (partIndex < 0) return state
        parts[partIndex] = parts[partIndex].copy(text = parts[partIndex].text + event.delta)
        merged[messageIndex] = message.copy(parts = parts)
        return state.copy(messages = merged)
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
        return UiMessage(
            id = id,
            role = info.optString("role", "user"),
            timeCreated = info.optJSONObject("time")?.optLong("created", 0L) ?: 0L,
            parts = parts,
            tokens = OpenCodeClient.tokenTotal(info.optJSONObject("tokens")),
        )
    }

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

            // `session.status` supersedes the deprecated `session.idle`; busy /
            // retry mean the run is still alive, idle means it finished.
            "session.status" -> {
                when (p.optJSONObject("status")?.optString("type", "") ?: "") {
                    "busy", "retry" -> Event.RunStatusChanged(RunStatus.RUNNING)
                    "idle" -> Event.RunStatusChanged(RunStatus.IDLE)
                    else -> null
                }
            }

            "session.error" -> Event.RunStatusChanged(
                RunStatus.ERROR,
                p.optString("error", "unknown error").takeIf { it.isNotEmpty() },
            )

            "session.idle" -> Event.RunStatusChanged(RunStatus.IDLE)

            else -> null
        }
    }

    /** Session id of a `session.*` event envelope (properties.info.id or id). */
    fun sessionIdOf(data: JSONObject): String? {
        val p = data.optJSONObject("properties") ?: data
        return p.optString("id", "")
            .ifEmpty { p.optJSONObject("info")?.optString("id", "").orEmpty() }
            .ifEmpty { null }
    }
}
