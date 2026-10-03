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

    /**
     * Payload root: `{ info, messages }` (no cursors) plus the optional
     * [truncated] note. [truncated] is never part of upstream's payload, so it
     * is only present when a message drain actually hit its page cap — a
     * complete export stays byte-identical to the upstream shape
     * (`SessionExport.serialize`).
     */
    data class Frame(
        val info: JSONObject,
        val messages: List<JSONObject>,
        /**
         * The message read stopped at its page cap with the server still
         * offering a cursor (`V2Api.MessageDrain.truncated`): the payload holds
         * the first N pages of the transcript, not all of it. Serialized as
         * `"truncated": {"messages": true, "pages": N}` so a consumer of the
         * file can tell — the truncation used to be invisible in the export.
         */
        val truncated: Boolean = false,
        /** Pages the drain read before stopping (`V2Api.MessageDrain.pages`). */
        val truncatedPages: Int = 0,
    )

    /** Root key of the export's own metadata (only written when [Frame.truncated]). */
    const val TRUNCATED_KEY = "truncated"

    /** Two-space indent, exactly what upstream's `JSON.stringify(…, null, 2)` writes. */
    const val INDENT = 2

    /**
     * `sessionExportFilename` (`export.ts:36-43`): `title || slug || id`, every
     * run of characters outside `[A-Za-z0-9_-]` collapsed to a single `-`,
     * leading/trailing `-` trimmed, `.json` appended. A name that normalizes to
     * nothing falls back to the raw id (upstream: `clean || id`).
     *
     * v63 BUG-3 (`research/v63_verify/5556/34_export2.png`): upstream's
     * `.toLowerCase()` is **not** reproduced here. It turned the device-tagged
     * title `E2ECHK5556-base` into a pre-filled `e2echk5556-base.json`, so the
     * saved file no longer carried the title the tester could see on screen —
     * and on the shared backend those tags are how one device's sessions are
     * told apart. Everything else (the character class, the run collapsing, the
     * edge trimming, the id fallback) is upstream's line for line; only the
     * lower-casing step is dropped, which is what makes the class `A-Za-z`.
     */
    fun filename(id: String, title: String? = null, slug: String? = null): String {        val name = title?.takeIf { it.isNotBlank() }
            ?: slug?.takeIf { it.isNotBlank() }
            ?: id
        val clean = buildString(name.length) {
            var pendingDash = false
            for (ch in name) {
                val keep = ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '_' || ch == '-'
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
     * The session title a just-fetched [Frame] carries — `info` is the
     * server's own session object (`V2Transport.readExportFrame` reads
     * `sessionRaw`), so this is the title as renamed, not as any list, tab
     * or nav argument cached it. Null-safe: a missing key, a JSON null and
     * the literal `"null"` org.json's `optString` coerces a JSON null into
     * all count as absent (a blank counts too), so callers fall back to
     * their UI title instead of writing `null.json` / `Untitled.json` over
     * a renamed session.
     */
    fun frameTitle(frame: Frame): String? {
        val info = frame.info
        if (info.isNull("title")) return null
        return info.optString("title", "").takeIf { it.isNotBlank() && it != "null" }
    }

    /**
     * Flattens the pages fetched by the caller into one message list, preserving
     * page order (`fetchSessionExport` does `pages.flatMap { it.data }` and
     * deliberately drops each page's `cursor`).
     */
    fun joinPages(pages: List<List<JSONObject>>): List<JSONObject> = pages.flatten()

    /**
     * `{ "info": …, "messages": [ … ] }`, two-space indented — plus the
     * truncation note when [Frame.truncated] (see [Frame]).
     */
    fun serialize(frame: Frame): String = serialize(frame.info, frame.messages, frame.truncated, frame.truncatedPages)

    fun serialize(
        info: JSONObject,
        messages: List<JSONObject>,
        truncated: Boolean = false,
        truncatedPages: Int = 0,
    ): String {
        val root = JSONObject()
        root.put("info", info)
        root.put("messages", JSONArray().apply { messages.forEach(::put) })
        if (truncated) {
            root.put(
                TRUNCATED_KEY,
                JSONObject()
                    .put("messages", true)
                    .put("pages", truncatedPages),
            )
        }
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
