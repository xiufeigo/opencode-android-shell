package com.xiufeigo.opencodeshell.ui.session.dock

import com.xiufeigo.opencodeshell.basicAuthorizationHeader
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure rules behind the v2 form dock (`SessionFormDock`), transcribed from the
 * upstream v2 dock (`_upstream/packages/app/src/session/requests/
 * session-question-dock.tsx`) and the form schema
 * (`_upstream/packages/schema/src/form.ts`).
 *
 * No Compose, no Android, no network — the composable only renders what this
 * file decides, so every rule here is a plain JVM unit test.
 *
 * Upstream renders a pending `FormInfo` in the question dock: only the
 * `string` / `multiselect` fields become question pages (`questionField`,
 * `:21-23`), one page at a time with Back/Next→Submit (`:433-462`), and submit
 * posts `{key: value | values}` for the answered fields only (`:275-284`) —
 * single-select posts the single value, multiselect the array. `custom`
 * answers live alongside the option picks (`customUpdate`, `:124-143`).
 *
 * The native dock additionally renders the other four field types Wave 1 left
 * raw (`ChatStore.FormAsk.fieldsJson`): `number` / `integer` as a numeric
 * input, `boolean` as a yes/no pick, `external` as a non-answerable info page
 * (an out-of-band URL the user follows; it never enters the answer). `when`
 * linkage (`form.ts` `When`: AND of conditions, unanswered reference ⇒ false,
 * `eq` against a multiselect answer means "selection includes value") decides
 * which fields are active; inactive fields are neither rendered nor answered.
 */
data class FormOption(
    val value: String,
    val label: String,
    val description: String?,
)

/** One `when` condition (`form.ts` `When{key, op: eq|neq, value}`). */
data class FormWhen(
    val key: String,
    val op: String,
    val value: Any?,
)

sealed interface FormField {
    val key: String
    val title: String
    val description: String
    val required: Boolean
    val hidden: Boolean
    val whenClauses: List<FormWhen>

    /**
     * `string` (`form.ts` `StringField`): free text, optionally with an option
     * list that turns it into a single-select question. Upstream always offers
     * the custom-answer row next to the options (`:580-656`); [custom] refines
     * that to the schema flag (absent ⇒ allowed, mirroring upstream).
     */
    data class Text(
        override val key: String,
        override val title: String,
        override val description: String,
        override val required: Boolean = false,
        override val hidden: Boolean = false,
        override val whenClauses: List<FormWhen> = emptyList(),
        val options: List<FormOption> = emptyList(),
        val custom: Boolean = true,
        val placeholder: String? = null,
        val default: String? = null,
    ) : FormField

    /** `multiselect` (`form.ts` `MultiselectField`): zero or more option values. */
    data class Multi(
        override val key: String,
        override val title: String,
        override val description: String,
        override val required: Boolean = false,
        override val hidden: Boolean = false,
        override val whenClauses: List<FormWhen> = emptyList(),
        val options: List<FormOption> = emptyList(),
        val custom: Boolean = true,
        val default: List<String> = emptyList(),
    ) : FormField

    /** `number` / `integer` (`form.ts`): a numeric input; [integer] picks `Long` answers. */
    data class NumberInput(
        override val key: String,
        override val title: String,
        override val description: String,
        override val required: Boolean = false,
        override val hidden: Boolean = false,
        override val whenClauses: List<FormWhen> = emptyList(),
        val integer: Boolean = false,
        val default: Double? = null,
    ) : FormField

    /** `boolean` (`form.ts`): a yes/no pick answering a JSON boolean. */
    data class BooleanInput(
        override val key: String,
        override val title: String,
        override val description: String,
        override val required: Boolean = false,
        override val hidden: Boolean = false,
        override val whenClauses: List<FormWhen> = emptyList(),
        val default: Boolean? = null,
    ) : FormField

    /**
     * `external` (`form.ts` `ExternalField{key, url, title?, description?}`):
     * an out-of-band jump (docs, OAuth, MCP elicitation link). The schema gives
     * it no `required` / `hidden` / `when` / answer shape, so it renders as an
     * info page and never enters the answer.
     */
    data class External(
        override val key: String,
        override val title: String,
        override val description: String,
        val url: String,
    ) : FormField {
        override val required: Boolean = false
        override val hidden: Boolean = false
        override val whenClauses: List<FormWhen> = emptyList()
    }
}

object SessionFormModel {

    /**
     * Parses `Form.Info.fields` (`ChatStore.FormAsk.fieldsJson`, raw `JSONArray`
     * text). A malformed array yields no fields; a malformed *entry* (no key,
     * unknown type) is skipped rather than failing the whole form, so one bad
     * field can never strand the dock.
     */
    fun parseFormFields(fieldsJson: String): List<FormField> {
        val array = try {
            JSONArray(fieldsJson)
        } catch (_: Exception) {
            return emptyList()
        }
        return (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.let(::parseField)
        }
    }

    private fun parseField(obj: JSONObject): FormField? {
        val key = obj.optString("key", "").takeIf { it.isNotEmpty() } ?: return null
        val title = obj.optString("title", "")
        // Upstream `questions()`: `question = description ?? title` (`:79`).
        val description = obj.optString("description", "").ifEmpty { title }
        val required = obj.optBoolean("required", false)
        val hidden = obj.optBoolean("hidden", false)
        val whenClauses = obj.optJSONArray("when")?.let(::parseWhen) ?: emptyList()
        return when (obj.optString("type", "")) {
            "string" -> FormField.Text(
                key = key,
                title = title,
                description = description,
                required = required,
                hidden = hidden,
                whenClauses = whenClauses,
                options = parseOptions(obj.optJSONArray("options")),
                custom = obj.optBoolean("custom", true),
                placeholder = obj.optString("placeholder", "").takeIf { it.isNotEmpty() },
                default = obj.optString("default", "").takeIf { it.isNotEmpty() },
            )

            "multiselect" -> FormField.Multi(
                key = key,
                title = title,
                description = description,
                required = required,
                hidden = hidden,
                whenClauses = whenClauses,
                options = parseOptions(obj.optJSONArray("options")),
                custom = obj.optBoolean("custom", true),
                default = (obj.optJSONArray("default") ?: JSONArray()).let { arr ->
                    (0 until arr.length()).mapNotNull { j ->
                        arr.optString(j, "").takeIf { it.isNotEmpty() }
                    }
                },
            )

            "number", "integer" -> FormField.NumberInput(
                key = key,
                title = title,
                description = description,
                required = required,
                hidden = hidden,
                whenClauses = whenClauses,
                integer = obj.optString("type", "") == "integer",
                default = if (obj.has("default")) {
                    obj.optDouble("default").takeIf { !it.isNaN() }
                } else {
                    null
                },
            )

            "boolean" -> FormField.BooleanInput(
                key = key,
                title = title,
                description = description,
                required = required,
                hidden = hidden,
                whenClauses = whenClauses,
                default = obj.takeIf { it.has("default") }?.optBoolean("default"),
            )

            "external" -> {
                val url = obj.optString("url", "").takeIf { it.isNotEmpty() } ?: return null
                FormField.External(key = key, title = title, description = description, url = url)
            }

            else -> null
        }
    }

    private fun parseOptions(array: JSONArray?): List<FormOption> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val opt = array.optJSONObject(i) ?: return@mapNotNull null
            val value = opt.optString("value", "").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            FormOption(
                value = value,
                label = opt.optString("label", "").ifEmpty { value },
                description = opt.optString("description", "").takeIf { it.isNotEmpty() },
            )
        }
    }

    private fun parseWhen(array: JSONArray): List<FormWhen> =
        (0 until array.length()).mapNotNull { i ->
            val cond = array.optJSONObject(i) ?: return@mapNotNull null
            val key = cond.optString("key", "").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val op = cond.optString("op", "").takeIf { it == "eq" || it == "neq" } ?: return@mapNotNull null
            FormWhen(key = key, op = op, value = cond.opt("value").takeIf { it != JSONObject.NULL })
        }

    /**
     * `form.ts` `When` semantics: every condition must hold (AND) for the field
     * to be active. An unanswered referenced field makes its condition false
     * for both ops; against a multiselect answer `eq` means "selection includes
     * value". Inactive fields are neither rendered nor answered. A field with
     * no conditions is always active.
     */
    fun isFieldActive(field: FormField, answers: Map<String, Any?>): Boolean =
        field.whenClauses.all { cond -> holds(cond, answers[cond.key]) }

    private fun holds(cond: FormWhen, answer: Any?): Boolean {
        if (answer == null) return false
        val hit = if (answer is List<*>) {
            answer.any { scalarEquals(it, cond.value) }
        } else {
            scalarEquals(answer, cond.value)
        }
        return if (cond.op == "eq") hit else !hit
    }

    private fun scalarEquals(answer: Any?, expected: Any?): Boolean {
        if (answer == null || expected == null || expected == JSONObject.NULL) return false
        if (answer is Boolean || expected is Boolean) {
            return toBoolean(answer) == toBoolean(expected)
        }
        val answerNumber = toDouble(answer)
        val expectedNumber = toDouble(expected)
        if (answerNumber != null && expectedNumber != null) {
            return answerNumber == expectedNumber
        }
        return answer.toString() == expected.toString()
    }

    private fun toBoolean(value: Any?): Boolean? = when (value) {
        is Boolean -> value
        is String -> when (value.lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }

        else -> null
    }

    private fun toDouble(value: Any?): Double? = when (value) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull()
        else -> null
    }

    /**
     * The dock's pages: every non-hidden field that is active against the
     * current [answers], in form order. Upstream's question filter
     * (`questionField`, `:21-23`) is the string/multiselect subset of this —
     * the scalar inputs and external info pages are the native dock's
     * extension to the six field types.
     */
    fun visiblePages(fields: List<FormField>, answers: Map<String, Any?>): List<FormField> =
        fields.filter { !it.hidden && isFieldActive(it, answers) }

    /**
     * A page counts as answered for the progress segments: any non-empty value.
     * External pages carry no answer and always read as done. Mirrors upstream
     * `answered(i)` (`:286-289`) for the question kinds.
     */
    fun pageAnswered(field: FormField, value: Any?): Boolean = when (field) {
        is FormField.External -> true
        is FormField.Multi -> (value as? List<*>)?.isNotEmpty() == true
        else -> when (value) {
            null -> false
            is String -> value.isNotBlank()
            else -> true
        }
    }

    /**
     * The `FormAnswer` (`{key: value}`) the reply posts: upstream `submit()`
     * (`:275-284`) — answered fields only, single-select posts the single
     * value, multiselect the array. Hidden and `when`-inactive fields are
     * dropped even when they carry a stale value (the user answered them
     * before a condition flipped), and external pages never enter the answer.
     * Unanswered fields are skipped, never posted as empty.
     */
    fun buildAnswer(fields: List<FormField>, values: Map<String, Any?>): Map<String, Any?> {
        val answer = linkedMapOf<String, Any?>()
        for (field in fields) {
            if (field.hidden || !isFieldActive(field, values)) continue
            val value = values[field.key] ?: continue
            when (field) {
                is FormField.Text -> (value as? String)?.takeIf { it.isNotBlank() }
                    ?.let { answer[field.key] = it }

                is FormField.Multi -> (value as? List<*>)
                    ?.mapNotNull { it?.toString()?.takeIf { s -> s.isNotEmpty() } }
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { answer[field.key] = it }

                is FormField.NumberInput -> toDouble(value)?.let { number ->
                    answer[field.key] = if (field.integer && number == kotlin.math.floor(number)) {
                        number.toLong()
                    } else {
                        number
                    }
                }

                is FormField.BooleanInput -> toBoolean(value)?.let { answer[field.key] = it }
                is FormField.External -> Unit
            }
        }
        return answer
    }

    /**
     * `DELETE /api/session/{sessionID}/form/{formID}` — the documented cancel
     * route (`protocol/src/groups/session.ts` `session.form.cancel`, "Cancel a
     * pending form"; the generated client exposes it as
     * `session.form.cancel`). `null` when the base URL does not parse, so the
     * caller can fall back to a local-only dismiss.
     */
    fun formCancelRequest(
        baseUrl: String,
        sessionID: String,
        formID: String,
        username: String?,
        password: String?,
    ): Request? {
        val url = baseUrl.toHttpUrlOrNull()?.newBuilder()
            ?.addPathSegment("api")
            ?.addPathSegment("session")
            ?.addPathSegment(sessionID)
            ?.addPathSegment("form")
            ?.addPathSegment(formID)
            ?.build() ?: return null
        val builder = Request.Builder().url(url).delete()
        basicAuthorizationHeader(username, password)?.let { builder.header("Authorization", it) }
        return builder.build()
    }
}
