package com.xiufeigo.opencodeshell.ui.session.timeline

import com.xiufeigo.opencodeshell.ChatStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Pure (JVM-testable) readers for the part types that `ChatStore.UiPart` keeps
 * only as [ChatStore.UiPart.rawJson]:
 *
 * | type | upstream shape (`_upstream/packages/schema/src/v1/session.ts`) |
 * |---|---|
 * | `patch` | `:94-100` `{ hash, files: string[] }` |
 * | `subtask` | `:204-218` `{ prompt, description, agent, model?, command? }` |
 * | `agent` | `:181-193` `{ name, source? }` |
 * | `retry` | `:220-231` `{ attempt, error: APIError, time.created }` |
 * | `step-finish` | `:240-257` `{ reason, cost, tokens{...}, snapshot? }` |
 *
 * Upstream never renders these as timeline cards: the normalised content model
 * carries tool / reasoning / text / notices instead, and `patch` shows up as the
 * `patch` **tool** card (`tool-renderer.tsx:375-383`: title `ui.tool.patch`,
 * subtitle `{n} files`), `subtask`/`agent` as notices
 * (`timeline/session-timeline-row.tsx:308-353`), `retry` as the retry card
 * (`components/session-retry.tsx`). These readers keep the payloads from being
 * silently dropped on the Android side.
 *
 * Every reader is tolerant: a missing field yields `null` / an empty list and
 * never throws, because the payload is server-owned and may grow new shapes.
 */
object RawParts {

    /** `ui.tool.patch` card data (`tool-renderer.tsx:375-383`). */
    data class Patch(val hash: String?, val files: List<String>)

    /** `subtask` part — the parent's delegation record. */
    data class Subtask(
        val agent: String?,
        val description: String?,
        val prompt: String?,
        val model: String?,
    )

    /** `agent` part — the agent mention / switch carried by the message. */
    data class Agent(val name: String?)

    /** `retry` part — attempt counter + the error the provider returned. */
    data class Retry(val attempt: Int, val errorName: String?, val message: String?)

    /** `step-finish` part — the per-step usage record. */
    data class StepFinish(val reason: String?, val cost: Double?, val tokens: Long?)

    /**
     * `agent-switched` / `model-switched` / `location-switched` notice payload
     * (`schema/src/session-message.ts:40-75`): the announced value only.
     */
    data class Switch(val value: String?)

    fun patch(rawJson: String?): Patch? {
        val obj = obj(rawJson) ?: return null
        return Patch(
            hash = obj.optStringOrNull("hash"),
            files = obj.optJSONArray("files").strings(),
        )
    }

    fun subtask(rawJson: String?): Subtask? {
        val obj = obj(rawJson) ?: return null
        val model = obj.optJSONObject("model")
        return Subtask(
            agent = obj.optStringOrNull("agent"),
            description = obj.optStringOrNull("description"),
            prompt = obj.optStringOrNull("prompt"),
            model = model?.optStringOrNull("modelID"),
        )
    }

    /**
     * `agent` part reader. A payload without a name has nothing to announce, so
     * it is treated as absent (the caller then renders no row at all).
     */
    fun agent(rawJson: String?): Agent? {
        val obj = obj(rawJson) ?: return null
        val name = obj.optStringOrNull("name") ?: return null
        return Agent(name = name)
    }

    /**
     * `agent-switched` / `model-switched` / `location-switched` notices
     * (`V2EventAdapter.notice`, payload = the source event): the announced
     * value, most specific field first. `null` when the payload carries none.
     *
     * v46 tD: a `session.moved` payload always nests the directory under
     * `location` (`MovePayload`, `schema/src/session-inbox.ts:31-35`), but a
     * hand-built submit shape may carry it top-level (`{directory}`) — read
     * that too, so a notice never renders a bare label when the directory is
     * right there. Every read is null-safe: an explicit JSON null must not
     * become the literal word "null" on the row. A directory with no basename
     * (the filesystem root `/`) falls back to the directory itself
     * ([displayName]), so the row reads `Location changed /`, never a bare
     * label.
     */
    fun switch(rawJson: String?): Switch {
        val obj = obj(rawJson) ?: return Switch(null)
        obj.optStringOrNull("agent")?.let { return Switch(it) }
        obj.optJSONObject("model")?.let { model ->
            val id = model.optStringOrNull("modelID") ?: return@let
            val provider = model.optStringOrNull("providerID")
            return Switch(if (provider == null) id else "$provider/$id")
        }
        obj.optStringOrNull("subpath")?.let { return Switch(it) }
        obj.optJSONObject("location")?.optStringOrNull("directory")?.let { return Switch(displayName(it)) }
        obj.optStringOrNull("directory")?.let { return Switch(displayName(it)) }
        return Switch(null)
    }

    /** `system` / `synthetic` summary line (`description`, upstream `notice()`). */
    fun description(rawJson: String?): String? = obj(rawJson)?.optStringOrNull("description")

    fun retry(rawJson: String?): Retry? {
        val obj = obj(rawJson) ?: return null
        val error = obj.optJSONObject("error")
        val data = error?.optJSONObject("data")
        return Retry(
            attempt = obj.optInt("attempt", 0),
            errorName = error?.optStringOrNull("name"),
            // `APIError` is `{ name, data: { message, ... } }` (`schema/src/v1/session.ts:48-56`).
            message = data?.optStringOrNull("message") ?: error?.optStringOrNull("message"),
        )
    }

    /** `step-finish`: `tokens.total ?? input + output` (`session.ts:240-257`). */
    fun stepFinish(rawJson: String?): StepFinish? {
        val obj = obj(rawJson) ?: return null
        val tokens = obj.optJSONObject("tokens")
        val total = tokens?.optDoubleOrNull("total")
            ?: tokens?.let {
                it.optDoubleOrNull("input")?.plus(it.optDoubleOrNull("output") ?: 0.0)
            }
        return StepFinish(
            reason = obj.optStringOrNull("reason"),
            cost = obj.optDoubleOrNull("cost"),
            tokens = total?.let { Math.round(it) },
        )
    }

    /**
     * `retry` card message: upstream truncates to 80 chars
     * (`components/session-retry.tsx:30-38`).
     */
    const val RETRY_MESSAGE_MAX = 80

    fun retryMessage(message: String?): String? {
        val text = message?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (text.length > RETRY_MESSAGE_MAX) {
            ToolPresentation.capChars(text, RETRY_MESSAGE_MAX) + "…"
        } else {
            text
        }
    }

    /** `capitalizeAgent()` — `build` → `Build` (`session-timeline-row.tsx:313`). */
    fun capitalize(value: String?): String? = value?.takeIf { it.isNotEmpty() }
        ?.replaceFirstChar { it.uppercase(Locale.US) }

    /** Last path segment of a `patch` file entry (`getFilename`). */
    fun basename(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')

    /**
     * Directory display name for `location-switched` rows: the basename, falling
     * back to the directory itself when it has no basename. The filesystem root
     * (`/`, and the same for a `C:/` anchor whose trailing separator leaves an
     * empty last segment) would otherwise render as a bare
     * 「Location changed」 label with no value (v47 tD_53: the move to `/`
     * sat between two named rows as a naked label).
     */
    fun displayName(directory: String): String = basename(directory).ifEmpty { directory }

    // ------------------------------------------------------------------ utils

    private fun obj(rawJson: String?): JSONObject? {
        if (rawJson.isNullOrBlank()) return null
        return try {
            JSONObject(rawJson)
        } catch (_: Exception) {
            null
        }
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key) else null

    private fun JSONArray?.strings(): List<String> {
        if (this == null) return emptyList()
        val out = ArrayList<String>(length())
        for (i in 0 until length()) {
            // `opt(i)` + type check, not `optString(i, "")`: a JSON null element
            // would otherwise join the list as the literal string "null" (see
            // [optStringOrNull] for the org.json split behind that).
            val value = opt(i) as? String ?: continue
            if (value.isNotEmpty() && value != "null") out += value
        }
        return out
    }
}
