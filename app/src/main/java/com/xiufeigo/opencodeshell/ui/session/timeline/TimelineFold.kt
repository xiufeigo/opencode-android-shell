package com.xiufeigo.opencodeshell.ui.session.timeline

/**
 * Pure (JVM-testable) folding policy for long timeline content — the U-4
 * "fold threshold" step of the parity audit (`research/parity-audit-2026-09-24.md`
 * U-4: upstream `session/timeline/virtualizer.tsx` has a real virtualizer,
 * we only have a `LazyColumn` with one heavy item per message).
 *
 * Upstream reference: there is **no** line-count folding upstream — long tool
 * output is capped at 240 px with an inner scroll
 * (`session-ui/src/components/message-part.css:323-464`, `bash-output` /
 * `tool-output` `max-height: 240px`) and cards/groups start collapsed
 * (`timeline/detail.ts` compact preset: everything `details: "collapsed"`).
 * The thresholds below are therefore our own reasonable values (task brief:
 * "output > 15 lines folds"):
 *
 * - [OUTPUT_FOLD_LINES]: an *expanded* tool body longer than this renders only
 *   the head plus a "show N more lines" row, so a huge output no longer
 *   inflates the measure/layout pass even after the user opened the card;
 * - [TEXT_FOLD_LINES]/[TEXT_FOLD_CHARS]: a prose/code message longer than this
 *   renders its head plus an expander instead of the full `OcMarkdown` parse;
 * - consecutive same-type tool calls need no new rule: [TimelineGrouping]
 *   already folds every run of consecutive tools/reasoning into one collapsible
 *   `Context` row (default collapsed — `MessageTimeline` passes
 *   `toolOpen[row.key] == true`), pinned by `TimelineFoldTest`.
 *
 * Folding never applies to live content: a `running`/`streaming` tool keeps
 * rendering its tail (the Composable gates on the status, not here).
 */
object TimelineFold {

    /**
     * Expanded tool body line budget (`bash` output, diffs, JSON dumps).
     * Matches the brief's example (`输出>15 行折叠`).
     */
    const val OUTPUT_FOLD_LINES = 15

    /** Prose/code message line budget before the head+expander treatment. */
    const val TEXT_FOLD_LINES = 30

    /**
     * Prose/code message char budget: a single giant line (minified JSON,
     * base64) folds even when it fits [TEXT_FOLD_LINES].
     */
    const val TEXT_FOLD_CHARS = 3000

    /**
     * Head/tail split of a line list. [hiddenLines] is 0 exactly when nothing
     * folds ([folded] is false).
     */
    data class Fold(val head: List<String>, val hiddenLines: Int) {
        val folded: Boolean get() = hiddenLines > 0
    }

    /**
     * Generic head/tail split: lists at or under [max] lines pass through
     * untouched; longer ones keep the first [max] lines.
     */
    fun foldLines(all: List<String>, max: Int = OUTPUT_FOLD_LINES): Fold {
        if (max <= 0 || all.size <= max) return Fold(all, 0)
        return Fold(all.subList(0, max), all.size - max)
    }

    /**
     * Long-message split: folds when the text exceeds [maxLines] lines **or**
     * [maxChars] chars. [hiddenLines] is at least 1 whenever folded, so the
     * "show N more lines" row always has a positive count even for a
     * char-capped single giant.
     */
    fun foldText(
        text: String,
        maxLines: Int = TEXT_FOLD_LINES,
        maxChars: Int = TEXT_FOLD_CHARS,
    ): Fold {
        val lines = ToolPresentation.splitLines(normalize(text))
        if (lines.size <= maxLines && text.length <= maxChars) return Fold(lines, 0)
        val head = lines.take(maxLines.coerceAtLeast(1))
        return Fold(head, (lines.size - head.size).coerceAtLeast(1))
    }

    /** `foldText(text).folded` — the Composable-side gate. */
    fun isLongText(
        text: String,
        maxLines: Int = TEXT_FOLD_LINES,
        maxChars: Int = TEXT_FOLD_CHARS,
    ): Boolean = foldText(text, maxLines, maxChars).folded

    /** The Composable-side gate for expanded tool bodies. */
    fun isLongBody(lineCount: Int, max: Int = OUTPUT_FOLD_LINES): Boolean = lineCount > max

    /**
     * Fence-safe head of a markdown body: the first [maxLines] lines, with a
     * closing fence appended when the cut lands inside a ` ``` ` block
     * (otherwise the collapsed `OcMarkdown` would swallow the expander row
     * into an unclosed code block). Non-fence constructs may still degrade in
     * the collapsed preview; the expanded body always renders the full text.
     */
    fun markdownHead(markdown: String, maxLines: Int = TEXT_FOLD_LINES): String {
        val lines = ToolPresentation.splitLines(normalize(markdown))
        if (lines.size <= maxLines) return normalize(markdown)
        val head = lines.take(maxLines.coerceAtLeast(1))
        val insideFence = head.count { it.trimStart().startsWith(FENCE) } % 2 == 1
        return if (insideFence) (head + FENCE).joinToString("\n") else head.joinToString("\n")
    }

    private const val FENCE = "```"

    private fun normalize(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n')
}
