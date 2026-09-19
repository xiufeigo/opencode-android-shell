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
    )

    data class UiMessage(
        val id: String,
        val role: String,
        val timeCreated: Long,
        val parts: List<UiPart>,
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
        )
    }

    fun parsePart(obj: JSONObject): UiPart = UiPart(
        id = obj.optString("id", "part:${obj.hashCode()}"),
        type = obj.optString("type", "unknown"),
        text = obj.optString("text", ""),
        toolName = obj.optString("tool", "").takeIf { it.isNotEmpty() },
        toolStatus = obj.optJSONObject("state")?.optString("status", "")?.takeIf { it.isNotEmpty() },
        mime = obj.optString("mime", "").takeIf { it.isNotEmpty() },
        filename = obj.optString("filename", "").takeIf { it.isNotEmpty() },
        url = obj.optString("url", "").takeIf { it.isNotEmpty() },
    )

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

            "message.removed" -> p.optString("messageID", "")
                .takeIf { it.isNotEmpty() }
                ?.let { Event.MessageRemoved(it) }

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
