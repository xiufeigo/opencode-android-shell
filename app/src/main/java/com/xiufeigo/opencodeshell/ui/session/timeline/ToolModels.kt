package com.xiufeigo.opencodeshell.ui.session.timeline

import com.xiufeigo.opencodeshell.ChatStore
import com.xiufeigo.opencodeshell.OpenCodeClient
import org.json.JSONObject

/**
 * Pure (JVM-testable) models for the dedicated tool renderers — the port of the
 * upstream `tool-renderer.tsx` branches the native `ToolCard` was folding into
 * its generic JSON body:
 *
 * | tool | upstream branch | model |
 * |---|---|---|
 * | `read` (image path) | `readImagePath` (`:279-283`) | [ReadImage] |
 * | `read` (completed, `metadata.loaded`) | loaded-files row (`:1309-1334`) | [LoadedFiles] |
 * | `websearch` | `ExaOutput` link list (`:437-488`, body `:1529-1554`) | [WebLinks] |
 * | `skill` | loaded-skill inline row (`:2171-2202`) | [LoadedSkill] |
 * | `subagent` | task-tool-card + delegating pill (`:1555-1678`) | [Subagent] |
 * | `question` | answered-count + Q/A echo (`:2122-2169`) | [Question] |
 *
 * The Android [ChatStore.UiPart] carries `toolInput` (raw `state.input` JSON)
 * and `toolOutput`; `metadata` (loaded files, sub-session id, answers) never
 * reaches the store, so every model below degrades to its input/output half
 * when the metadata half is absent. This is a **data gap**, not a rendering
 * gap: the store keeps no `state.metadata`, so `metadata.loaded`,
 * `metadata.sessionID`, `metadata.answers` and `metadata.provider` are
 * unobservable here (recorded in `research/v2_audit/wave2.2b-notes.md`).
 * Where upstream reads metadata only, the row hides instead of guessing.
 */
object ToolModels {

    // ------------------------------------------------------------------ read

    /** Image extensions `ReadImage` accepts (`tool-renderer.tsx:281`). */
    private val IMAGE_EXT = Regex("\\.(png|jpe?g|gif|webp|svg|avif|bmp|ico)$", RegexOption.IGNORE_CASE)

    /** An image path (forward slashes) when `read` points at an image, else null. */
    fun readImagePath(input: JSONObject?): String? {
        val path = input?.optStringOrNull("path") ?: return null
        if (!IMAGE_EXT.containsMatchIn(path)) return null
        return path.replace('\\', '/')
    }

    /**
     * The "Loaded …" line under a completed `read` (`ui.tool.loadedFile` →
     * `ui.tool.loaded` = "Loaded"): `Loaded {paths}`. Paths are relativised
     * against [directory] and joined with `, `; null when there is nothing to
     * show (not completed, no loaded paths).
     */
    fun loadedFiles(loaded: List<String>, directory: String? = null): String? {
        if (loaded.isEmpty()) return null
        val paths = loaded.map { relativize(it, directory) }
        return "Loaded ${paths.joinToString(", ")}"
    }

    // -------------------------------------------------------------- websearch

    /** `urls()` (`tool-renderer.tsx:407-417`): unique http(s) links, max 10 shown. */
    const val WEB_LINKS_SHOWN = 10

    private val URL_REGEX = Regex("https?://[^\\s<>\"'`)\\]]+")
    private val URL_TAIL_TRIM = Regex("[),.;:!?]+$")

    /** Distinct URLs of [output], in order of first appearance. */
    fun webLinks(output: String?): List<String> {
        if (output.isNullOrEmpty()) return emptyList()
        val seen = LinkedHashSet<String>()
        for (match in URL_REGEX.findAll(output)) {
            val url = match.value.replace(URL_TAIL_TRIM, "")
            if (url.isNotEmpty()) seen += url
        }
        return seen.toList()
    }

    // ------------------------------------------------------------------ skill

    /**
     * The loaded-skill inline row (`ui.tool.loadedSkill`): `Loaded {name}`.
     * Upstream prefers `metadata.name`, then `input.id`, then `input.name`
     * (`skillToolName`, `:285-290`); the store has no metadata, so this takes
     * the input name (null = render the generic skill shimmer instead).
     */
    fun loadedSkillName(input: JSONObject?): String? =
        input?.optStringOrNull("id") ?: input?.optStringOrNull("name")

    fun loadedSkillLabel(name: String): String = "Loaded $name"

    // --------------------------------------------------------------- subagent

    /**
     * The task-tool-card subtitle (`tool-renderer.tsx:1585-1592`): the `input`
     * description when present, else the child session id; ` (background)` is
     * appended for background agents.
     */
    fun subagentSubtitle(description: String?, sessionId: String?, background: Boolean): String? {
        val base = description?.takeIf { it.isNotBlank() } ?: sessionId?.takeIf { it.isNotBlank() }
        if (base == null) return null
        return if (background) "$base (background)" else base
    }

    /**
     * Capitalised agent label (`taskAgent`: item name or raw with first letter
     * upper-cased, `ui.tool.agent.default` = "Agent" fallback is the caller's).
     */
    fun agentLabel(raw: String?): String? =
        raw?.takeIf { it.isNotEmpty() }?.replaceFirstChar { it.uppercaseChar() }

    /** `true` while the task card shows its spinner (`:1594-1601`). */
    fun subagentRunning(toolStatus: String?, childRunning: Boolean = false): Boolean =
        toolStatus == "streaming" || toolStatus == "running" || childRunning

    // --------------------------------------------------------------- question

    data class QuestionItem(val question: String)
    data class QuestionModel(
        val questions: List<QuestionItem>,
        /** One label array per question, in question order (empty = unanswered). */
        val answers: List<List<String>>,
    ) {
        val completed: Boolean get() = answers.any { it.isNotEmpty() }
    }

    /**
     * The question subtitle (`tool-renderer.tsx:2131-2135`): an answered card
     * reads `{count} answered` (`ui.question.subtitle.answered`), otherwise
     * `{count} question(s)`. Empty when there are no questions.
     */
    fun questionSubtitle(model: QuestionModel, answeredLabel: (Int) -> String): String? {
        if (model.questions.isEmpty()) return null
        val count = model.questions.size
        if (model.completed) return answeredLabel(count)
        val noun = if (count == 1) "question" else "questions"
        return "$count $noun"
    }

    /**
     * The answered Q/A echo (`question-answers`, `:2145-2156`): one
     * question+answer pair per asked question; answers join with `, ` and an
     * unanswered one reads [noneLabel] (`ui.question.answer.none`).
     */
    fun questionAnswers(model: QuestionModel, noneLabel: String): List<Pair<String, String>> =
        model.questions.mapIndexed { index, item ->
            val answer = model.answers.getOrNull(index).orEmpty()
            item.question to (answer.joinToString(", ").ifEmpty { noneLabel })
        }

    // ---------------------------------------------------- shell/execute state

    /**
     * Shell/execute failure detection the cards share (`current-tool-state.ts`):
     * a `shell` tool fails on a completed non-zero exit / timeout, an `execute`
     * tool on `metadata.error` or an errored nested call. The store carries no
     * metadata, so only the explicit `error` status is observable — the rest is
     * derived by the caller from its own metadata source (null = unknown).
     */
    fun shellFailed(toolName: String?, toolStatus: String?): Boolean =
        toolStatus == "error" && (toolName == "shell" || toolName == "execute")

    // ------------------------------------------------------------- diagnostics

    /**
     * One LSP diagnostic row (`DiagnosticsDisplay`, `:131-150`): only
     * `severity == 1` (errors), at most 3 per file. `range` is 0-based upstream,
     * so the display location is 1-based `[line:character]`.
     */
    data class Diagnostic(val line: Int, val character: Int, val message: String)

    fun diagnostics(raw: String?, filePath: String?): List<Diagnostic> {
        if (raw.isNullOrBlank() || filePath.isNullOrBlank()) return emptyList()
        val byFile = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return emptyList()
        }
        val list = byFile.optJSONArray(filePath) ?: return emptyList()
        val out = ArrayList<Diagnostic>(3)
        for (i in 0 until list.length()) {
            if (out.size >= 3) break
            val entry = list.optJSONObject(i) ?: continue
            val message = entry.optStringOrNull("message") ?: continue
            val range = entry.optJSONObject("range") ?: continue
            val start = range.optJSONObject("start") ?: continue
            if (entry.optInt("severity", -1) != 1) continue
            out += Diagnostic(
                line = start.optInt("line", 0) + 1,
                character = start.optInt("character", 0) + 1,
                message = message,
            )
        }
        return out
    }

    // --------------------------------------------------------------- compaction

    /**
     * Compaction row state (`message-content.tsx:400-473`). Upstream's
     * `SessionCompactionMessage` is a message (running/failed/completed with a
     * paced summary + outcome separator + error card); the Android store keeps a
     * `compaction` **part** whose `text` is the summary (or, for a rejected
     * compaction, the readable error line — the caller suppresses it here and
     * renders it as the failure detail instead), so this model is what the part
     * expresses on its own: a summary to render through markdown, or the static
     * fallback line when it carries nothing. The settled status lives beside it
     * ([ChatStore.compactionStatusOf]) and is not this model's business.
     */
    enum class CompactionKind { SUMMARY, STARTED_FALLBACK }

    data class CompactionModel(val kind: CompactionKind, val summary: String?)

    fun compaction(part: ChatStore.UiPart): CompactionModel {
        val summary = part.text.takeIf { it.isNotBlank() }
        return if (summary != null) {
            CompactionModel(CompactionKind.SUMMARY, summary)
        } else {
            CompactionModel(CompactionKind.STARTED_FALLBACK, null)
        }
    }

    // -------------------------------------------------------------- nav chips

    /**
     * Tool-count chips for the group header (`AnimatedCountList`,
     * `tool-count-summary.tsx`): multiplicity per distinct tool title —
     * "2 Read · Grep". The store has no per-tool match counts (upstream's
     * `matches` come from `metadata.count`/`metadata.matches`), so this is the
     * static multiplicity the header can express. Empty for a thoughts-only
     * group (the caller then keeps the plain `Thought` label).
     */
    fun countChips(parts: List<ChatStore.UiPart>): List<Pair<String, Int>> {
        val counts = LinkedHashMap<String, Int>()
        for (part in parts) {
            if (part.type != "tool") continue
            val title = TimelineGrouping.toolLabel(part.toolName)
            counts[title] = (counts[title] ?: 0) + 1
        }
        return counts.entries.map { it.key to it.value }
    }

    /**
     * Message-nav diff bars (`message-nav.tsx:109-151`). Five cells; with no
     * changes every cell is weak. Fewer than 5 total changes colour one cell per
     * non-empty side; otherwise `colored` is 4 (5 when the volume or the ratio
     * dominates) split proportionally between adds and deletes.
     */
    enum class DiffCell { ADD, DELETE, WEAK }

    fun diffBars(additions: Int, deletions: Int): List<DiffCell> {
        val added = additions.coerceAtLeast(0)
        val deleted = deletions.coerceAtLeast(0)
        if (added == 0 && deleted == 0) return List(5) { DiffCell.WEAK }
        if (added + deleted < 5) {
            return buildList {
                if (added > 0) add(DiffCell.ADD)
                if (deleted > 0) add(DiffCell.DELETE)
                repeat(5 - size) { add(DiffCell.WEAK) }
            }
        }
        val total = added + deleted
        val ratio = if (added > deleted) added.toDouble() / deleted else deleted.toDouble() / added
        val colored = if (total < 20 || ratio < 4) 4 else 5
        val addedRaw = added.toDouble() / total * colored
        val deletedRaw = deleted.toDouble() / total * colored
        fun share(count: Int, raw: Double): Int {
            if (count == 0) return 0
            val capped = when {
                count <= 5 -> 1
                count <= 10 -> 2
                else -> colored
            }
            return minOf(capped, maxOf(1, Math.round(raw).toInt()))
        }
        var addBars = share(added, addedRaw)
        var delBars = share(deleted, deletedRaw)
        val overflow = addBars + delBars - colored
        if (overflow > 0) {
            if (addedRaw > deletedRaw) addBars -= overflow else delBars -= overflow
        }
        return buildList {
            repeat(addBars) { add(DiffCell.ADD) }
            repeat(delBars) { add(DiffCell.DELETE) }
            repeat(5 - size) { add(DiffCell.WEAK) }
        }
    }

    // ------------------------------------------------------------- todos

    /**
     * The todo list a `todowrite` call carries (`state.input.todos`, the shape
     * `OpenCodeClient.TodoInfo` parses). Upstream hides the part from the
     * timeline (`projection.ts:587`) and shows the list elsewhere, so the
     * Android side folds it out of the rendered tool parts instead of the
     * v1-only `GET /session/:id/todo` route.
     */
    fun todoList(toolInput: String?): List<OpenCodeClient.TodoInfo> {
        if (toolInput.isNullOrBlank()) return emptyList()
        val input = try {
            JSONObject(toolInput)
        } catch (_: Exception) {
            return emptyList()
        }
        val todos = input.optJSONArray("todos") ?: return emptyList()
        return OpenCodeClient.TodoInfo.listFromJson(todos)
    }

    /**
     * Transcript-wide todo list: the **last** `todowrite` part wins (each call
     * replaces the list), exactly what the server's `GET /todo` used to answer.
     */
    fun todoList(messages: List<ChatStore.UiMessage>): List<OpenCodeClient.TodoInfo> {
        var out: List<OpenCodeClient.TodoInfo> = emptyList()
        for (message in messages) {
            for (part in message.parts) {
                if (part.type == "tool" && part.toolName == "todowrite") {
                    val todos = todoList(part.toolInput)
                    if (todos.isNotEmpty() || part.toolInput != null) out = todos
                }
            }
        }
        return out
    }

    // ------------------------------------------------------------------ utils

    private fun JSONObject.optStringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val value = opt(key) ?: return null
        if (value !is String) return null
        return value.takeIf { it.isNotEmpty() }
    }

    private fun relativize(path: String, directory: String?): String {
        if (path.isEmpty() || directory.isNullOrEmpty()) return path
        if (directory == "/" || directory == "\\") return path
        if (path == directory) return ""
        val separator = if ('\\' in directory) '\\' else '/'
        val prefix = if (directory.endsWith(separator)) directory else directory + separator
        if (!path.startsWith(prefix)) return path
        return path.substring(directory.length).trimStart('/', '\\')
    }
}
