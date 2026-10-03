package com.xiufeigo.opencodeshell.ui.session.timeline

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Pure (JVM-testable) text utilities for the tool cards — the port of the two
 * upstream text normalisations the native cards were missing:
 *
 * - shell/execute output: `stripAnsi(output).replace(/\r\n?/g, "\n")`
 *   (`tool-renderer.tsx:1726,1790-1792`);
 * - session error text: `unwrapErrorMessage`
 *   (`timeline/projection.ts:726-759`) — a nested-JSON error wall becomes
 *   `{type: message}`;
 * - failed tool text: `ToolErrorCard`'s head/detail split
 *   (`components/tool-error-card.tsx:65-83`): `Error:` prefix strip, optional
 *   `{tool} ` prefix strip, then `": "` split into summary head + detail.
 *
 * One native addition with no upstream counterpart: [localizeServerPhrases]
 * swaps the server's English failure phrases for the UI locale's words, because
 * the server composes those messages itself and never sends a key the client
 * could translate (`shell.ts:145`).
 */
object ToolText {

    /**
     * `strip-ansi` equivalent for tool output: CSI sequences (`\u001B[…m` colours,
     * cursor moves, clears) plus OSC hyperlinks (`\u001B]…\u0007`), which is what
     * a shell/execute result carries. `\r\n`/`\r` normalisation stays in
     * [ToolPresentation]'s `normalize` (upstream applies it after stripping).
     */
    fun stripAnsi(text: String): String {
        if (text.isEmpty() || '\u001B' !in text && '\u009B' !in text) return text
        var out = CSI_REGEX.replace(text, "")
        out = OSC_REGEX.replace(out, "")
        return out
    }

    /** Shell/execute output exactly as upstream renders it: stripped + normalised. */
    fun shellOutput(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        return stripAnsi(raw).replace("\r\n", "\n").replace('\r', '\n')
    }

    /**
     * `unwrapErrorMessage` (`timeline/projection.ts:730-759`), transcribed:
     * strip an `Error:` prefix, parse the (possibly string-wrapped) JSON, then
     * prefer `error.{type,message}`, `message`, a string `error` — otherwise the
     * input back untouched.
     */
    fun unwrapErrorMessage(message: String): String {
        val text = message.replaceFirst(ERROR_PREFIX_REGEX, "").trim()
        if (text.isEmpty()) return message
        var json: Any? = readJson(text)
        if (json == null) {
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start != -1 && end > start) json = readJson(text.substring(start, end + 1))
        }
        if (json !is JSONObject) return message
        val error = json.optJSONObject("error")
        if (error != null) {
            val type = error.optStringOrNull("type")
            val detail = error.optStringOrNull("message")
            if (type != null && detail != null) return "$type: $detail"
            if (detail != null) return detail
            if (type != null) return type
            error.optStringOrNull("code")?.let { return it }
        }
        json.optStringOrNull("message")?.let { return it }
        // `error` as a bare string (`{error: "boom"}`).
        if (json.has("error") && !json.isNull("error")) {
            val raw = json.opt("error")
            if (raw is String && raw.isNotBlank()) return raw
        }
        return message
    }

    /**
     * Server-authored English text with the known phrases swapped for the UI
     * locale's words — the display half of a failure the app can name in its own
     * language.
     *
     * [phrases] maps the **English** phrase as the server writes it to the
     * localized one (`Working directory does not exist` →
     * `error.server.workingDirectoryMissing`); the caller builds it with
     * `stringResource`, so this stays pure and unit-testable. Matching is
     * case-insensitive on the whole phrase — the server composes these messages
     * itself (`shell.ts:145`), so there is no id or code to key off.
     *
     * An English locale maps every phrase onto itself: those pairs are skipped,
     * so the text is returned untouched and the copy/verbatim paths cannot drift
     * through a no-op replace. Longer phrases are applied first so a short one
     * cannot eat the prefix of a longer one.
     */
    fun localizeServerPhrases(text: String, phrases: Map<String, String>): String {
        if (text.isEmpty() || phrases.isEmpty()) return text
        var out = text
        for ((from, to) in phrases.entries.sortedByDescending { it.key.length }) {
            if (from.isEmpty() || from == to) continue
            out = out.replace(from, to, ignoreCase = true)
        }
        return out
    }

    /**
     * `ToolErrorCard`'s `cleaned`/`tail`/`summary`/`detail`
     * (`tool-error-card.tsx:65-83`): `Error:` prefix strip, `{tool} ` prefix
     * strip, then the first `": "`-delimited segment (capitalised) is the head
     * and the rest is the detail. A blank head falls back to [fallback]
     * (`ui.toolErrorCard.failed` → "Failed").
     */
    fun errorHeadDetail(error: String, tool: String?, fallback: String = "Failed"): Pair<String, String> {
        val cleaned = error.replaceFirst(ERROR_PREFIX_REGEX, "").trim()
        val prefix = tool?.takeIf { it.isNotEmpty() }?.let { "$it " }.orEmpty()
        val tail = if (prefix.isNotEmpty() && cleaned.startsWith(prefix)) {
            cleaned.substring(prefix.length)
        } else {
            cleaned
        }
        val segments = tail.split(": ")
        val rawHead = (segments.firstOrNull() ?: "").trim()
        val head = if (rawHead.isEmpty()) {
            fallback
        } else {
            rawHead.replaceFirstChar { it.uppercaseChar() }
        }
        val detail = if (segments.size <= 1) "" else segments.drop(1).joinToString(": ").trim()
        return head to detail
    }

    /** First non-blank line of [text], capped at [max] chars (card subtitles). */
    fun firstLine(text: String, max: Int = ToolPresentation.SUBTITLE_MAX): String? =
        text.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() }
            ?.let { ToolPresentation.capChars(it, max) }

    // --------------------------------------------------------------- internals

    private val ERROR_PREFIX_REGEX = Regex("^Error:\\s*")

    /**
     * CSI: `ESC [ params intermediates final` (SGR colours, cursor movement,
     * erase) plus the single-char `CSI` (`\u009B`) form `strip-ansi` also strips.
     */
    private val CSI_REGEX = Regex("[\u001B\u009B]\\[[0-9;?]*[ -/]*[@-~]")

    /** OSC: `ESC ] … terminated by BEL or ST` (hyperlinks, window titles). */
    private val OSC_REGEX = Regex("\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)")

    /**
     * `decodeJson` (`effect`): any JSON value, unwrapping one string layer
     * (upstream error walls are frequently double-encoded JSON: `read` parses
     * the payload, then — only when the result is a string — parses it again).
     * `JSONTokener.nextValue()` is the JVM equivalent: unlike
     * `JSONObject(...)` it also accepts top-level string literals, which is
     * exactly the double-encoded shape.
     */
    private fun readJson(value: String): Any? {
        val first: Any? = try {
            JSONTokener(value).nextValue()
        } catch (_: Exception) {
            null
        }
        if (first !is String) return first
        return try {
            JSONTokener(first.trim()).nextValue()
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * NULL-safe read of an optional string field of a tool input / part payload —
 * the one reader for the whole timeline package ([RawParts], [ToolPresentation],
 * [ToolRenderers], [TurnChanges] and this file's own text helpers).
 *
 * `JSONObject.optString(name, fallback)` is not the trap-free form it looks
 * like: on Android it funnels the value through `JSON.toString`, so the JSON
 * `null` sentinel comes back as the **literal string `"null"`** (only a missing
 * key reaches the fallback), while the `org.json:json` jar the unit tests run on
 * returns the fallback for the same payload. A tool card would therefore render
 * the word `null` on a device — as a bash command, a `write` body, one side of
 * an old/new diff, a question line — with every JVM test green. Same divergence
 * as the cursor `400 InvalidCursorError` and the error card's `null` header.
 *
 * `isNull` is the half both builds agree on (missing key and JSON `null` alike);
 * a server-sent literal `"null"` counts as absent too, since no field this
 * package reads uses `"null"` as a value. A `null` receiver answers `null`, so
 * `input?.optStringOrNull("command")` needs no extra guard.
 */
internal fun JSONObject?.optStringOrNull(key: String): String? {
    if (this == null || !has(key) || isNull(key)) return null
    return optString(key, "").takeIf { it.isNotEmpty() && it != "null" }
}
