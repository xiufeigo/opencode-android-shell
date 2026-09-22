package com.xiufeigo.opencodeshell.ui.session.export

import com.xiufeigo.opencodeshell.OpenCodeClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * Session export — the pure half (A8, upstream `session/commands/export.ts`).
 *
 * Upstream builds `{ info, messages }` from `session.get` + every
 * `message.list({limit: 200, order: "asc"})` page, names the file from the
 * session identity, and serializes with `JSON.stringify(data, null, 2)`
 * (`_upstream/packages/app/src/session/commands/export.ts:10-43`).
 *
 * Nothing here touches Android APIs, so the name rules, the payload shape and
 * the page flattening are all JVM-testable; the SAF write lives in
 * [SessionExportSaver].
 */
object SessionExport {

    /** Payload root: `{ info, messages }` and nothing else (no cursors). */
    data class Frame(val info: JSONObject, val messages: List<JSONObject>)

    /** Two-space indent, exactly what upstream's `JSON.stringify(…, null, 2)` writes. */
    const val INDENT = 2

    /**
     * `sessionExportFilename` (`export.ts:36-43`): `title || slug || id`,
     * lower-cased, every run of characters outside `[a-z0-9_-]` collapsed to a
     * single `-`, leading/trailing `-` trimmed, `.json` appended. A name that
     * normalizes to nothing falls back to the raw id (upstream: `clean || id`).
     */
    fun filename(id: String, title: String? = null, slug: String? = null): String {
        val name = title?.takeIf { it.isNotBlank() }
            ?: slug?.takeIf { it.isNotBlank() }
            ?: id
        val clean = buildString(name.length) {
            var pendingDash = false
            for (ch in name.lowercase()) {
                val keep = ch in 'a'..'z' || ch in '0'..'9' || ch == '_' || ch == '-'
                if (keep) {
                    if (pendingDash && isNotEmpty()) append('-')
                    pendingDash = false
                    append(ch)
                } else {
                    pendingDash = true
                }
            }
        }.trim('-')
        return (clean.ifEmpty { id }) + ".json"
    }

    /**
     * Flattens the pages fetched by the caller into one message list, preserving
     * page order (`fetchSessionExport` does `pages.flatMap { it.data }` and
     * deliberately drops each page's `cursor`).
     */
    fun joinPages(pages: List<List<JSONObject>>): List<JSONObject> = pages.flatten()

    /** `{ "info": …, "messages": [ … ] }`, two-space indented. */
    fun serialize(frame: Frame): String = serialize(frame.info, frame.messages)

    fun serialize(info: JSONObject, messages: List<JSONObject>): String {
        val root = JSONObject()
        root.put("info", info)
        root.put("messages", JSONArray().apply { messages.forEach(::put) })
        return root.toString(INDENT)
    }

    /**
     * Builds the export frame from the models the app already has.
     *
     * **Fidelity note** (matters for a 1:1 claim): a message's `parts` are
     * exported as the *server's own* payload — `OpenCodeClient.Part.raw` keeps
     * the untouched object (`OpenCodeClient.kt`, used by the W4 contract for
     * `rawJson`). The `info` half is **rebuilt** from the fields the parser
     * keeps (`id`, `sessionID`, `role`, `time.created`, plus `tokens`,
     * `providerID`, `modelID`, `agent`, `time.completed` when present), because
     * `OpenCodeClient.Message` deliberately does not retain the raw info object.
     * An export produced this way is complete for reading a session back, but is
     * not byte-identical to upstream's raw dump; passing a raw `info` JSONObject
     * to [Frame] (once the client exposes one) upgrades it to exact.
     */
    fun frameFrom(sessionInfo: JSONObject, messages: List<OpenCodeClient.Message>): Frame =
        Frame(sessionInfo, messages.map(::messageJson))

    /** One `{ info, parts }` row; see [frameFrom] for the fidelity note. */
    fun messageJson(message: OpenCodeClient.Message): JSONObject {
        val info = JSONObject()
        info.put("id", message.id)
        info.put("sessionID", message.sessionID)
        info.put("role", message.role)
        val time = JSONObject().put("created", message.timeCreated)
        message.timeCompleted?.let { time.put("completed", it) }
        info.put("time", time)
        message.tokens?.let { info.put("tokens", JSONObject().put("total", it)) }
        message.providerID?.let { info.put("providerID", it) }
        message.modelID?.let { info.put("modelID", it) }
        message.agent?.let { info.put("agent", it) }
        return JSONObject()
            .put("info", info)
            .put("parts", JSONArray().apply { message.parts.forEach { put(it.raw) } })
    }
}
