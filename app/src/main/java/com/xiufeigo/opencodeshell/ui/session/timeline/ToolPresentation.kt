package com.xiufeigo.opencodeshell.ui.session.timeline

import androidx.annotation.StringRes
import com.xiufeigo.opencodeshell.ChatStore
import com.xiufeigo.opencodeshell.R
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Pure (JVM-testable) presentation logic for `tool` / `reasoning` parts.
 *
 * Ported from the View-era reference — `ChatView.titleForTool` /
 * `iconForTool` / `subtitleForTool` / `toolBodyText` (ChatView.kt L768–888) —
 * so the Compose timeline renders the same layout without owning any View.
 * Keeping it free of `Composable`/`Context` is what makes the mapping
 * unit-testable (`ToolPresentationTest`).
 *
 * One deliberate deviation from the port: tool **titles** follow the
 * authoritative `research/02` §3.8 / `_i18n` literals (Glob / Grep / Webfetch)
 * rather than the View shell's Find / Search / Fetch — see [titleRes].
 */
object ToolPresentation {

    /** Subtitle keys probed in order; the first non-empty one wins. */
    val INPUT_KEYS = listOf(
        "command", "filePath", "pattern", "query", "url", "path", "description", "name",
    )

    /** `ChatView.TOOL_SUBTITLE_MAX` — subtitle is a single ellipsized line. */
    const val SUBTITLE_MAX = 120

    /** Fallback label when a failed tool carries no error text (`oc_tool_error`). */
    const val ERROR_LABEL = "Error"

    /** Generic label for an unknown tool (`oc_tool_generic`). */
    const val GENERIC_LABEL = "Tool"

    /** Icon slots available to the tool card (maps to the `ic_oc_*` drawables). */
    enum class Icon { SHELL, CODE, GLASSES, SEARCH, GLOBE, TASK, CHECK, FILE }

    /** How a single body line is coloured by the Composable. */
    enum class LineKind {
        /** Raw text (shell output, generic output). */
        PLAIN,

        /** Pretty-printed JSON — string literals get syntax colouring. */
        JSON,

        /** `$ command` header line of a shell tool. */
        COMMAND,

        /** `+ line` of an edit/write diff. */
        ADD,

        /** `- line` of an edit/write diff. */
        DELETE,

        /** `state.error` of a failed tool — first line of the expanded body. */
        ERROR,
    }

    data class BodyLine(val kind: LineKind, val text: String)

    // ---------------------------------------------------------------- title

    /**
     * `ui.tool.*` key for a tool name, or `null` when the name has no dedicated
     * key (the caller then capitalizes the raw name / falls back to
     * [GENERIC_LABEL]).
     *
     * The res ids are the View-era names (`oc_tool_find` / `oc_tool_search` /
     * `oc_tool_fetch` are also referenced by `ChatView.titleForTool`), but the
     * **literals** are the authoritative `research/02` §3.8 / `_i18n` ones —
     * Glob / Grep / Webfetch, not the Find / Search / Fetch of the old View shell
     * (M1-review ruling B). `strings.xml` carries the same literals, so both
     * shells now agree.
     */
    @StringRes
    fun titleRes(toolName: String?): Int? = when (toolName) {
        "bash", "shell" -> R.string.oc_tool_shell
        "edit" -> R.string.oc_tool_edit
        "write" -> R.string.oc_tool_write
        "read" -> R.string.oc_tool_read
        "glob" -> R.string.oc_tool_find
        "grep" -> R.string.oc_tool_search
        "task" -> R.string.oc_tool_task
        "webfetch" -> R.string.oc_tool_fetch
        "todowrite" -> R.string.oc_tool_todos
        else -> null
    }

    /** English literal for [titleRes] — the fallback used off-Android + in tests. */
    fun title(toolName: String?): String = when (toolName) {
        "bash", "shell" -> "Shell"
        "edit" -> "Edit"
        "write" -> "Write"
        "read" -> "Read"
        "glob" -> "Glob"
        "grep" -> "Grep"
        "task" -> "Task"
        "webfetch" -> "Webfetch"
        "todowrite" -> "To-dos"
        else -> toolName?.takeIf { it.isNotEmpty() }
            ?.replaceFirstChar { it.uppercase(Locale.US) }
            ?: GENERIC_LABEL
    }

    /** Mirrors `ChatView.iconForTool` (drawable picked by the Composable). */
    fun icon(toolName: String?): Icon = when (toolName) {
        "bash", "shell" -> Icon.SHELL
        "edit", "write", "patch", "apply_patch" -> Icon.CODE
        "read" -> Icon.GLASSES
        "glob", "grep" -> Icon.SEARCH
        "webfetch", "websearch" -> Icon.GLOBE
        "task" -> Icon.TASK
        "todowrite" -> Icon.CHECK
        else -> Icon.FILE
    }

    // ------------------------------------------------------------- subtitle

    /**
     * Subtitle = first meaningful input key, or the error summary on failure
     * (`ChatView.subtitleForTool`). `errorLabel` is injected so the Composable
     * can pass the localized `oc_tool_error` string.
     */
    fun subtitle(part: ChatStore.UiPart, errorLabel: String = ERROR_LABEL): String? {
        if (part.toolStatus == "error") {
            val summary = splitLines((part.toolError ?: part.toolOutput).orEmpty())
                .firstOrNull { it.isNotBlank() }
            return (summary ?: errorLabel).take(SUBTITLE_MAX)
        }
        val input = parseObject(part.toolInput) ?: return null
        for (key in INPUT_KEYS) {
            val value = input.optString(key, "")
            if (value.isNotEmpty()) return value.take(SUBTITLE_MAX)
        }
        return null
    }

    // ----------------------------------------------------------------- body

    /**
     * Expanded body (`ChatView.toolBodyText`) as coloured lines:
     * - bash/shell → `$ cmd` + blank + raw output
     * - edit (and write with `content`) → `- old` / `+ new` diff lines
     * - anything else → pretty JSON input + raw output, separated by a blank
     */
    fun bodyLines(part: ChatStore.UiPart): List<BodyLine> {
        val name = part.toolName.orEmpty()
        val input = parseObject(part.toolInput)
        // §3.3: the error card's body is the failure text; the generic JSON /
        // output body below is what the *successful* card shows. Prepending the
        // error keeps a failed tool's message readable without a second card.
        val errorLines = if (part.toolStatus == "error") {
            splitLines(normalize(part.toolError.orEmpty())).map { BodyLine(LineKind.ERROR, it) }
        } else {
            emptyList()
        }

        if (name == "bash" || name == "shell") {
            val command = input?.optString("command", "").orEmpty()
            val output = normalize(part.toolOutput.orEmpty())
            val lines = mutableListOf<BodyLine>()
            lines += errorLines
            lines += BodyLine(LineKind.COMMAND, "$ $command")
            if (output.isNotEmpty()) {
                lines += BodyLine(LineKind.PLAIN, "")
                splitLines(output).forEach { lines += BodyLine(LineKind.PLAIN, it) }
            }
            return lines
        }

        if (isEditLike(name)) {
            val oldText = input?.optString("oldString", "").orEmpty()
            // `write` ships the new file in `content` (no oldString/newString pair).
            val newText = input?.optString("newString", "").orEmpty()
                .ifEmpty { input?.optString("content", "").orEmpty() }
            if (oldText.isNotEmpty() || newText.isNotEmpty()) {
                return errorLines + diffLines(oldText, newText)
            }
        }

        val lines = errorLines.toMutableList()
        part.toolInput?.takeIf { it.isNotBlank() }?.let { raw ->
            splitLines(pretty(raw)).forEach { lines += BodyLine(LineKind.JSON, it) }
        }
        part.toolOutput?.takeIf { it.isNotBlank() }?.let { output ->
            if (lines.isNotEmpty()) lines += BodyLine(LineKind.PLAIN, "")
            splitLines(normalize(output)).forEach { lines += BodyLine(LineKind.PLAIN, it) }
        }
        return lines
    }

    /** True for the tools whose body is a diff rather than JSON. */
    fun isEditLike(toolName: String?): Boolean = toolName == "edit" || toolName == "write" ||
        toolName == "patch" || toolName == "apply_patch"

    /** `- old` / `+ new` lines; an absent side contributes nothing (not `"- "`). */
    fun diffLines(oldText: String, newText: String): List<BodyLine> {
        val lines = mutableListOf<BodyLine>()
        splitLines(normalize(oldText)).forEach { lines += BodyLine(LineKind.DELETE, "- $it") }
        splitLines(normalize(newText)).forEach { lines += BodyLine(LineKind.ADD, "+ $it") }
        return lines
    }

    // ---------------------------------------------------------------- json

    fun parseObject(raw: String?): JSONObject? {
        if (raw.isNullOrBlank()) return null
        return try {
            JSONObject(raw)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Pretty JSON (`ChatView.jsonSpannable`); unparseable input is returned
     * as-is. Note: Android's `JSONObject.toString(2)` indents, the JVM
     * `org.json:json` reference implementation returns a compact string — the
     * card only cares that the result is normalised, valid JSON.
     */
    fun pretty(raw: String): String = try {
        parseObject(raw)?.toString(2) ?: JSONArray(raw).toString(2)
    } catch (_: Exception) {
        raw
    }

    // --------------------------------------------------------------- utils

    /**
     * Deterministic line split: `""` → no lines, `"a\n"` → `["a"]`.
     * (`String.lineSequence()` changed behaviour across Kotlin versions; the
     * rendering of `- ` / `+ ` prefixes must not depend on that.)
     */
    fun splitLines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val parts = text.split('\n')
        return if (parts.isNotEmpty() && parts.last().isEmpty()) parts.dropLast(1) else parts
    }

    private fun normalize(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n')
}
