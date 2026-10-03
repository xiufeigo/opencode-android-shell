package com.xiufeigo.opencodeshell.ui.session.context

import com.xiufeigo.opencodeshell.ui.session.export.SessionExport
import com.xiufeigo.opencodeshell.v2.InstructionEntry
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.math.BigDecimal

/**
 * Pure rules behind the session **instructions** editor (the `api/<key>`
 * entries of `GET/PUT/DELETE /api/experimental/session/{id}/instructions/
 * entries` — `V2Api.sessionInstructionEntries` / `…EntryPut` / `…EntryRemove`).
 *
 * Upstream has no screen for these routes (`protocol/src/groups/session.ts:
 * 646-705` is the whole surface), so the presentation is this shell's own: one
 * list plus per-entry edit/delete, keyed by the schema rules the routes enforce
 * (`schema/src/instruction-entry.ts:5-10`):
 *
 * - the server namespaces a key as `api/<key>`;
 * - `key` must match `^[a-z0-9][a-z0-9._-]*$`;
 * - `value` is arbitrary JSON (`JSONObject` / `JSONArray` / `String` / `Number` /
 *   `Boolean` / `null`), capped at 256 KiB (`413` beyond that).
 *
 * No Compose, no network — every rule here is plain JVM unit test.
 */
object SessionInstructionsModel {

    /** `instruction-entry.ts:10` — the key grammar the PUT path accepts. */
    private val KEY_PATTERN = Regex("^[a-z0-9][a-z0-9._-]*$")

    fun isValidKey(key: String): Boolean = KEY_PATTERN.matches(key)

    /** Save is offered for a well-formed key; an empty value is legal JSON `""`. */
    fun canSave(key: String): Boolean = isValidKey(key.trim())

    /**
     * Editor text of one value: strings verbatim (so a plain sentence is edited
     * as a plain sentence), containers pretty-printed with [SessionExport.
     * INDENT] spaces, `null` as the literal `null`.
     */
    fun valueText(value: Any?): String = when (value) {
        null -> "null"
        is String -> value
        is JSONObject -> value.toString(SessionExport.INDENT)
        is JSONArray -> value.toString(SessionExport.INDENT)
        else -> value.toString()
    }

    /**
     * Inverse of [valueText] for the editor: JSON when the text *is* JSON
     * (object / array / number / boolean / `null` / quoted string), otherwise
     * the raw text as a string value. A bare `123` therefore round-trips as the
     * number 123 — the ambiguity of an arbitrary-JSON value edited as text.
     * Decimal/integer tokens normalize to [Double]/[Long] (never the tokener's
     * `BigDecimal`), so a saved entry writes plain JSON numbers.
     */
    fun parseValue(text: String): Any? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        val parsed = runCatching { JSONTokener(trimmed).nextValue() }.getOrNull()
        return when (parsed) {
            null, JSONObject.NULL -> null
            // `JSONTokener` types numbers loosely (Integer / Long / BigDecimal
            // depending on version and magnitude); normalize integral values to
            // [Long] and decimals to [Double] so a saved entry writes plain
            // JSON numbers either way.
            is BigDecimal -> if (parsed.scale() <= 0) parsed.toLong() else parsed.toDouble()
            is Byte, is Short, is Int, is Long -> (parsed as Number).toLong()
            is Float, is Double -> (parsed as Number).toDouble()
            is JSONObject, is JSONArray, is Boolean, is Number -> parsed
            // `JSONTokener` accepts bare words as strings; only quoted strings
            // and structured values are JSON here, anything else stays as typed.
            is String -> if (trimmed.first() == '"') parsed else text
            else -> text
        }
    }

    /** One editor row. [valueText] is the field's initial content. */
    data class Row(val key: String, val value: Any?) {
        val text: String get() = valueText(value)
    }

    /** List projection, in server order (the routes return insertion order). */
    fun rows(entries: List<InstructionEntry>): List<Row> =
        entries.map { Row(key = it.key, value = it.value) }
}
