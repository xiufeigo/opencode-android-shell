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

        /** Unchanged context line of an edit hunk (`  line`). */
        CONTEXT,

        /** `@@ … @@` hunk header of an edit/patch diff. */
        HUNK,

        /** `state.error` of a failed tool — first line of the expanded body. */
        ERROR,

        /** `write` body: plain new-file text (upstream `mode="text"`). */
        TEXT,
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
     * `toolErrorSubtitle` (`tool-renderer.tsx:1265-1289`), transcribed for the
     * failed-card subtitle so an errored row reads like its non-error
     * counterpart ("Shell sleep 30"): the per-tool **input** key, and nothing
     * else. Null when the tool names nothing (the caller renders nothing rather
     * than a blank subtitle).
     *
     * v32 BUG-1: an errored row must never fall back to the error text. The
     * card already renders that text's first `": "` segment as its head
     * ([ToolText.errorHeadDetail]), so the old `summary ?: errorLabel` fallback
     * printed the same sentence twice on the trigger — measured on a deleted
     * project directory: 「Shell Working directory does not exist:
     * /home/xiufeigo/v32projA Working directory does not exist」 (the subtitle
     * carried the whole line, the head its first segment). Upstream has no such
     * fallback either: `toolErrorSubtitle` answers `undefined` for a tool whose
     * input names nothing, and the trigger then shows the title + summary only.
     */
    fun subtitle(part: ChatStore.UiPart): String? {
        if (part.toolStatus == "error") {
            return errorSubtitle(part)?.let { capChars(it, SUBTITLE_MAX) }
        }
        val input = parseObject(part.toolInput) ?: return null
        for (key in INPUT_KEYS) {
            val value = input.optStringOrNull(key) ?: continue
            if (value.isNotEmpty()) return capChars(value, SUBTITLE_MAX)
        }
        return null
    }

    /**
     * Basename of a tool input path (`getFilename` in `toolErrorSubtitle` for
     * read/edit/write).
     */
    fun basename(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')

    private fun errorSubtitle(part: ChatStore.UiPart): String? {
        val input = parseObject(part.toolInput)
        fun text(key: String): String? = input?.optStringOrNull(key)
        return when (part.toolName) {
            "bash", "shell" -> text("command")
            "execute" -> text("code")
            "read" -> text("path")?.let(::basename)?.takeIf { it.isNotEmpty() }
            "edit", "write" -> text("path")?.let(::basename)?.takeIf { it.isNotEmpty() }
            "list", "glob", "grep" -> text("path")
            "webfetch" -> text("url")
            "websearch" -> text("query")
            "skill" -> text("id") ?: text("name")
            "question" -> questionCount(input)?.let { "$it ${if (it == 1) "question" else "questions"}" }
            else -> null
        }
    }

    private fun questionCount(input: JSONObject?): Int? {
        if (input == null || !input.has("questions")) return null
        val questions = input.optJSONArray("questions") ?: return null
        var count = 0
        for (i in 0 until questions.length()) {
            val item = questions.optJSONObject(i) ?: continue
            if (item.has("question")) count++
        }
        return count.takeIf { it > 0 }
    }

    // ----------------------------------------------------------------- body

    /**
     * Expanded body as coloured lines:
     * - bash/shell/execute → `$ cmd` + blank + stripped output
     * - edit → real hunk diff ([hunkDiffLines]): unchanged runs are collapsed
     *   to [HUNK]/[CONTEXT] context lines instead of `- old` / `+ new` pairs
     * - write → plain new-file text ([TEXT] lines, upstream `mode="text"`)
     * - apply_patch/patch → unified [patchLines]
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

        if (name == "bash" || name == "shell" || name == "execute") {
            val command = input?.optStringOrNull("command").orEmpty()
                .ifEmpty { input?.optStringOrNull("code").orEmpty() }
            val output = ToolText.shellOutput(part.toolOutput)
            val lines = mutableListOf<BodyLine>()
            lines += errorLines
            lines += BodyLine(LineKind.COMMAND, "$ $command")
            if (output.isNotEmpty()) {
                lines += BodyLine(LineKind.PLAIN, "")
                splitLines(output).forEach { lines += BodyLine(LineKind.PLAIN, it) }
            }
            return lines
        }

        if (name == "edit") {
            val oldText = input?.optStringOrNull("oldString").orEmpty()
            val newText = input?.optStringOrNull("newString").orEmpty()
            if (oldText.isNotEmpty() || newText.isNotEmpty()) {
                return errorLines + hunkDiffLines(oldText, newText)
            }
        }

        if (name == "write") {
            val content = input?.optStringOrNull("content").orEmpty()
            if (content.isNotEmpty()) {
                // Upstream renders write as a text file view (`mode="text"`),
                // not a diff: every line is new-file content.
                return errorLines + splitLines(normalize(content))
                    .map { BodyLine(LineKind.TEXT, it) }
            }
        }

        if (isEditLike(name)) {
            // `apply_patch` (the only write tool this server exposes) ships a
            // unified patch instead of an old/new pair, so the diff body has to
            // come from that text — otherwise the card falls back to JSON and
            // the `+`/`-` rendering never appears at all.
            val patchText = input?.optStringOrNull("patchText").orEmpty()
                .ifEmpty { patchFromFiles(input) }
            if (patchText.isNotEmpty()) {
                return errorLines + patchLines(patchText)
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

    /**
     * `- old` / `+ new` lines; an absent side contributes nothing (not `"- "`).
     *
     * Kept for callers that need the flat pair (and by the existing tests);
     * the `edit` card itself renders [hunkDiffLines].
     */
    fun diffLines(oldText: String, newText: String): List<BodyLine> {
        val lines = mutableListOf<BodyLine>()
        splitLines(normalize(oldText)).forEach { lines += BodyLine(LineKind.DELETE, "- $it") }
        splitLines(normalize(newText)).forEach { lines += BodyLine(LineKind.ADD, "+ $it") }
        return lines
    }

    /**
     * Real hunk diff of an `edit` (`oldString` → `newString`), matching the
     * upstream edit renderer (`tool-renderer.tsx:1877-1963` via
     * `CurrentFileToolGroup` → `createTwoFilesPatch` + the diff file view):
     * unchanged runs render as context lines; only the changed regions render
     * as `-`/`+` pairs, grouped under `@@ -a,b +c,d @@` hunk headers.
     *
     * `HUNK_CONTEXT` mirrors jsdiff's default context (`createTwoFilesPatch`
     * uses 4 lines each side); adjacent hunks merge when their context windows
     * overlap. A pure addition (empty [oldText]) or pure deletion (empty
     * [newText]) renders without a hunk header — there is no unchanged anchor
     * to name one after.
     */
    fun hunkDiffLines(oldText: String, newText: String): List<BodyLine> =
        hunkDiffLines(oldText, newText, HUNK_CONTEXT)

    /** [HUNK_CONTEXT] override for tests. */
    fun hunkDiffLines(oldText: String, newText: String, context: Int): List<BodyLine> {
        val old = splitLines(normalize(oldText))
        val new = splitLines(normalize(newText))
        if (old.isEmpty()) {
            return new.map { BodyLine(LineKind.ADD, "+ $it") }
        }
        if (new.isEmpty()) {
            return old.map { BodyLine(LineKind.DELETE, "- $it") }
        }
        val ops = diffOps(old, new)
        val changeIndexes = ops.indices.filter { ops[it] !is EditOp.Equal }
        if (changeIndexes.isEmpty()) {
            // Identical inputs: upstream's diff view would be empty (the
            // `additions == 0 && deletions == 0` guard drops the file); the
            // card keeps one context copy so the body is not blank.
            return old.map { BodyLine(LineKind.CONTEXT, "  $it") }
        }
        // Changed-line windows, widened by `context` lines each side, merged
        // where they overlap — the hunks.
        val windows = changeIndexes.map { (it - context).coerceAtLeast(0)..(it + context).coerceAtMost(ops.lastIndex) }
        val hunks = ArrayList<IntRange>()
        for (window in windows) {
            val last = hunks.lastOrNull()
            if (last != null && window.first <= last.last + 1) {
                hunks[hunks.lastIndex] = last.first..maxOf(last.last, window.last)
            } else {
                hunks += window
            }
        }
        val lines = ArrayList<BodyLine>()
        for (hunk in hunks) {
            lines += BodyLine(
                LineKind.HUNK,
                "@@ -${hunkOldStart(ops, hunk.first) + 1},${oldSpan(ops, hunk)} " +
                    "+${hunkNewStart(ops, hunk.first) + 1},${newSpan(ops, hunk)} @@",
            )
            for (i in hunk) {
                when (ops[i]) {
                    is EditOp.Equal -> {
                        val op = ops[i] as EditOp.Equal
                        lines += BodyLine(LineKind.CONTEXT, "  ${old[op.old]}")
                    }
                    is EditOp.Delete -> {
                        val op = ops[i] as EditOp.Delete
                        lines += BodyLine(LineKind.DELETE, "- ${old[op.old]}")
                    }
                    is EditOp.Insert -> {
                        val op = ops[i] as EditOp.Insert
                        lines += BodyLine(LineKind.ADD, "+ ${new[op.new]}")
                    }
                }
            }
        }
        return lines
    }

    /** jsdiff default hunk context (`createTwoFilesPatch(old, new)`). */
    const val HUNK_CONTEXT = 4

    /**
     * `apply_patch`'s `{"files":[{"patch":"@@ …\n-old\n+new"}]}` shape, joined
     * with a blank line between files.
     */
    private fun patchFromFiles(input: JSONObject?): String {
        val files = input?.optJSONArray("files") ?: return ""
        val out = StringBuilder()
        for (i in 0 until files.length()) {
            val patch = files.optJSONObject(i)?.optStringOrNull("patch").orEmpty()
            if (patch.isEmpty()) continue
            if (out.isNotEmpty()) out.append('\n')
            out.append(patch)
        }
        return out.toString()
    }

    /** jsdiff `parsePatch` hunk header (`@@ -a[,b] +c[,d] @@`). */
    private val HUNK_HEADER = Regex("""@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@""")

    /**
     * Unified patch text → diff body, classified by a hunk-count state machine
     * (jsdiff `parsePatch` semantics) rather than bare prefixes: inside a
     * counted hunk every line is content, so a deletion whose text starts with
     * `--`/`---` (CSS `--x`, SQL/Lua comments, markdown `---`) keeps
     * [LineKind.DELETE] instead of being mistaken for a file header — the old
     * prefix order checked `---`/`+++` before `+`/`-` and stole the red/green
     * tint. A bare `@@` (apply_patch section header) opens an uncounted hunk
     * that ends at the next `@@` or `*** …` control line. Outside a hunk,
     * `@@` is [LineKind.HUNK], `*** …`/`--- …`/`+++ …` stay
     * [LineKind.COMMAND], and headerless `+`/`-` fragments stay content.
     */
    fun patchLines(patchText: String): List<BodyLine> {
        val out = ArrayList<BodyLine>()
        var inHunk = false
        var counted = false
        var oldLeft = 0
        var newLeft = 0
        for (line in splitLines(normalize(patchText))) {
            if (line.startsWith("@@")) {
                out += BodyLine(LineKind.HUNK, line)
                val header = HUNK_HEADER.find(line)
                counted = header != null
                oldLeft = header?.groups?.get(2)?.value?.toIntOrNull() ?: 1
                newLeft = header?.groups?.get(4)?.value?.toIntOrNull() ?: 1
                inHunk = true
                continue
            }
            if (inHunk && line.startsWith("***")) {
                // apply_patch control line (`*** End Patch` …): a counted hunk
                // reaches one only when its header counts ran out early.
                inHunk = false
            }
            if (!inHunk) {
                out += when {
                    line.startsWith("***") || line.startsWith("---") ||
                        line.startsWith("+++") -> BodyLine(LineKind.COMMAND, line)

                    line.startsWith("-") -> BodyLine(LineKind.DELETE, line)
                    line.startsWith("+") -> BodyLine(LineKind.ADD, line)
                    else -> BodyLine(LineKind.PLAIN, line)
                }
                continue
            }
            val kind = when {
                line.startsWith("-") -> LineKind.DELETE
                line.startsWith("+") -> LineKind.ADD
                else -> LineKind.PLAIN
            }
            out += BodyLine(kind, line)
            if (counted) {
                when {
                    line.startsWith("\\") -> Unit
                    line.startsWith("-") -> oldLeft--
                    line.startsWith("+") -> newLeft--
                    else -> {
                        oldLeft--
                        newLeft--
                    }
                }
                if (oldLeft <= 0 && newLeft <= 0) inHunk = false
            }
        }
        return out
    }

    /** True when a body line is part of a diff (background-tinted in the card). */
    fun isDiffKind(kind: LineKind): Boolean = when (kind) {
        LineKind.ADD, LineKind.DELETE, LineKind.CONTEXT, LineKind.HUNK -> true
        LineKind.PLAIN, LineKind.JSON, LineKind.COMMAND, LineKind.ERROR, LineKind.TEXT -> false
    }

    // ------------------------------------------------- hunk diff internals

    private sealed interface EditOp {
        data class Equal(val old: Int, val new: Int) : EditOp
        data class Delete(val old: Int) : EditOp
        data class Insert(val new: Int) : EditOp
    }

    /**
     * Myers O(ND) edit script. Sizes here are an edit card's `oldString` /
     * `newString` (tens of lines, not megabytes), so the quadratic worst case
     * is irrelevant — and a correct minimal script is exactly what keeps the
     * changed regions (and therefore the hunks) tight.
     */
    private fun diffOps(old: List<String>, new: List<String>): List<EditOp> {
        val n = old.size
        val m = new.size
        if (n == 0) return new.indices.map { EditOp.Insert(it) }
        if (m == 0) return old.indices.map { EditOp.Delete(it) }
        // Longest common prefix/suffix fast paths: the overwhelmingly common
        // edit shape (one contiguous change) never enters the DP table.
        var prefix = 0
        while (prefix < n && prefix < m && old[prefix] == new[prefix]) prefix++
        var suffix = 0
        while (suffix < n - prefix && suffix < m - prefix && old[n - 1 - suffix] == new[m - 1 - suffix]) suffix++
        val a0 = prefix
        val a1 = n - suffix
        val b0 = prefix
        val b1 = m - suffix
        val ops = ArrayList<EditOp>(maxOf(n, m))
        for (i in 0 until prefix) ops += EditOp.Equal(i, i)
        if (a0 < a1 || b0 < b1) {
            ops += diffMiddle(old.subList(a0, a1), new.subList(b0, b1), a0, b0)
            for (i in 0 until suffix) ops += EditOp.Equal(n - suffix + i, m - suffix + i)
        }
        return ops
    }

    private fun diffMiddle(a: List<String>, b: List<String>, aBase: Int, bBase: Int): List<EditOp> {
        val n = a.size
        val m = b.size
        if (n == 0) return b.indices.map { EditOp.Insert(bBase + it) }
        if (m == 0) return a.indices.map { EditOp.Delete(aBase + it) }
        // LCS DP over the trimmed middle only.
        val table = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                table[i][j] = if (a[i] == b[j]) {
                    table[i + 1][j + 1] + 1
                } else {
                    maxOf(table[i + 1][j], table[i][j + 1])
                }
            }
        }
        val ops = ArrayList<EditOp>(n + m)
        var i = 0
        var j = 0
        while (i < n && j < m) {
            if (a[i] == b[j]) {
                ops += EditOp.Equal(aBase + i, bBase + j)
                i++; j++
            } else if (table[i + 1][j] >= table[i][j + 1]) {
                ops += EditOp.Delete(aBase + i)
                i++
            } else {
                ops += EditOp.Insert(bBase + j)
                j++
            }
        }
        while (i < n) ops += EditOp.Delete(aBase + i++)
        while (j < m) ops += EditOp.Insert(bBase + j++)
        return ops
    }

    private fun hunkOldStart(ops: List<EditOp>, from: Int): Int {
        for (i in from until ops.size) {
            when (val op = ops[i]) {
                is EditOp.Equal -> return op.old
                is EditOp.Delete -> return op.old
                is EditOp.Insert -> if (i > 0) {
                    val prev = ops[i - 1]
                    if (prev is EditOp.Equal) return prev.old + 1
                }
            }
        }
        return 0
    }

    private fun hunkNewStart(ops: List<EditOp>, from: Int): Int {
        for (i in from until ops.size) {
            when (val op = ops[i]) {
                is EditOp.Equal -> return op.new
                is EditOp.Insert -> return op.new
                is EditOp.Delete -> if (i > 0) {
                    val prev = ops[i - 1]
                    if (prev is EditOp.Equal) return prev.new + 1
                }
            }
        }
        return 0
    }

    private fun oldSpan(ops: List<EditOp>, hunk: IntRange): Int =
        hunk.count { ops[it] is EditOp.Equal || ops[it] is EditOp.Delete }

    private fun newSpan(ops: List<EditOp>, hunk: IntRange): Int =
        hunk.count { ops[it] is EditOp.Equal || ops[it] is EditOp.Insert }

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

    /**
     * [String.take] that never splits a surrogate pair: a lone high surrogate
     * renders as U+FFFD, and `ChatStore.capToolText` (which should call this)
     * hard-cut tool payloads at 16000 chars — an astral-plane character
     * straddling the cap used to corrupt the tail of every capped field.
     */
    fun capChars(text: String, max: Int): String {
        if (text.length <= max) return text
        if (max <= 0) return ""
        var end = max
        if (text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        return text.substring(0, end)
    }

    private fun normalize(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n')
}
