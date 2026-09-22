package com.xiufeigo.opencodeshell.ui.session.review

/**
 * Presentation model behind [ReviewChangesTab] — the JVM-testable half of the
 * W5-R diff work (C2 diff style + expand mode, C3 line numbers, word-level
 * emphasis, split view and the large-patch policy).
 *
 * Upstream anchors:
 * - `_upstream/packages/session-ui/src/v2/components/session-review-v2.tsx:21`
 *   (`SessionReviewExpandMode = "expand" | "collapse"`, the toolbar segmented
 *   controls at `:266-309`) and `:288-309` (`diffStyle` unified/split).
 * - `_upstream/packages/session-ui/src/pierre/index.ts:205-231`: the diff is
 *   rendered by Pierre with `disableLineNumbers: false` (`:205`),
 *   `diffIndicators: "bars"` (`:208`), `lineDiffType: "word-alt"` (`:213`),
 *   `maxLineDiffLength: 1000` (`:214`), `--diffs-font-size` 13 px,
 *   `--diffs-line-height: 24px`, `--diffs-min-number-column-width: 3ch`
 *   (`:223-230`).
 * - `_upstream/packages/app/src/session/review/view.tsx:253-257`: the empty
 *   copy follows the change scope.
 *
 * The word-level emphasis here is an **approximation** of Pierre's
 * `word-alt` line diff: the common prefix/suffix of a deletion/addition pair
 * is trimmed and the remaining middle range is emphasised on both sides. It is
 * not a token-level (Myers) diff; see `research/wave5_review.md` §C3.
 */

/** Diff presentation, upstream `SessionReviewDiffStyle`. */
enum class ReviewDiffStyle {
    /** One column, `+`/`-` sign column (upstream default). */
    UNIFIED,

    /** Two columns: old on the left, new on the right (simplified port). */
    SPLIT,
}

/**
 * Line-expansion mode, upstream `SessionReviewExpandMode`
 * (`session-review-v2.tsx:21`): `expand` shows every patch line
 * (`ui.sessionReviewV2.showAllLines`), `collapse` hides the unchanged context
 * lines (`ui.sessionReviewV2.hideNonDiffLines`, `session-review-v2.tsx:277-286`).
 */
enum class ReviewExpandMode { EXPAND, COLLAPSE }

/**
 * One rendered diff line: the raw patch [text] plus its gutter numbers.
 *
 * [oldLine]/[newLine] are null on the side a line does not exist on (an
 * addition has no old number) and on every non-content line (headers/hunks).
 */
data class DiffRenderRow(
    val kind: DiffLineKind,
    val text: String,
    val oldLine: Int? = null,
    val newLine: Int? = null,
    /** Character ranges (inside [content]) emphasised as word-level changes. */
    val changed: List<IntRange> = emptyList(),
) {
    /**
     * The line body: the one-character sign column (`+`, `-`, space) is
     * stripped for content lines, headers/hunks/meta stay verbatim. A lenient
     * classified line without a sign (see `ReviewDiff.parsePatchLines`) keeps
     * its first character.
     */
    val content: String
        get() {
            val contentLine = kind == DiffLineKind.ADDITION ||
                kind == DiffLineKind.DELETION ||
                kind == DiffLineKind.CONTEXT
            if (!contentLine || text.isEmpty()) return text
            val signed = text[0] == '+' || text[0] == '-' || text[0] == ' '
            return if (signed) text.substring(1) else text
        }
}

/** One flattened row of the panel list (one LazyColumn item per row). */
sealed interface PatchRow {
    /** Unified style: a single full-width line. */
    data class Line(val row: DiffRenderRow) : PatchRow

    /**
     * Split style: left = old, right = new, or [full] for a row that spans both
     * columns (hunk/file headers, `\ No newline …`).
     */
    data class Columns(
        val left: DiffRenderRow?,
        val right: DiffRenderRow?,
        val full: DiffRenderRow? = null,
    ) : PatchRow
}

/** The renderable patch of one file: the rows plus the truncation verdict. */
data class PatchView(val rows: List<PatchRow>, val lineCount: Int, val truncated: Boolean)

object ReviewDiffView {

    /**
     * Per-file row cap. Everything at or below it is emitted as individual
     * `LazyColumn` items, so only the visible rows are composed — the Android
     * stand-in for upstream's Pierre virtualizer. A patch above this cap is cut
     * with an explicit truncated footer instead of building an unbounded model.
     */
    const val MAX_ROWS = 4000

    /**
     * Word-level emphasis is skipped for longer lines, upstream
     * `maxLineDiffLength: 1000` (`pierre/index.ts:214`).
     */
    const val MAX_HIGHLIGHT_CHARS = 1000

    /**
     * At or above this many patch rows a file opens in [ReviewExpandMode.COLLAPSE]
     * when the user asked for [ReviewExpandMode.EXPAND]: a 10k-line patch would
     * otherwise paint every context row on open. The mode toggle still applies
     * to every other file, and the header shows the effective mode.
     */
    const val AUTO_COLLAPSE_ROWS = 400

    /** `@@ -12,7 +12,9 @@ optional section heading`. */
    private val HUNK_START = Regex("""^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@""")

    /** Patch line count without building the rows (used for the open policy). */
    fun lineCount(patch: String?): Int = ReviewDiff.parsePatchLines(patch.orEmpty()).size

    /**
     * Effective mode for a file: [expandMode] unless a huge patch must open
     * collapsed (see [AUTO_COLLAPSE_ROWS]).
     */
    fun autoCollapse(patchLineCount: Int, expandMode: ReviewExpandMode): ReviewExpandMode =
        if (expandMode == ReviewExpandMode.EXPAND && patchLineCount >= AUTO_COLLAPSE_ROWS) {
            ReviewExpandMode.COLLAPSE
        } else {
            expandMode
        }

    /**
     * Classifies, numbers and word-emphasises a patch under [expandMode].
     * Lenient like [ReviewDiff.parsePatchLines]; an empty/failed patch yields
     * an empty list.
     */
    fun rows(patch: String?, expandMode: ReviewExpandMode = ReviewExpandMode.EXPAND): List<DiffRenderRow> {
        val lines = ReviewDiff.parsePatchLines(patch.orEmpty()).filter { visible(it.kind, expandMode) }
        return emphasize(number(lines))
    }

    /**
     * The full render view of one file: [style]-shaped rows, capped at
     * [maxRows].
     */
    fun view(
        patch: String?,
        expandMode: ReviewExpandMode,
        style: ReviewDiffStyle,
        maxRows: Int = MAX_ROWS,
    ): PatchView {
        val rows = rows(patch, expandMode)
        val kept = if (rows.size > maxRows) rows.subList(0, maxRows) else rows
        val display = when (style) {
            ReviewDiffStyle.UNIFIED -> kept.map { PatchRow.Line(it) }
            ReviewDiffStyle.SPLIT -> split(kept)
        }
        return PatchView(display, rows.size, rows.size > kept.size)
    }

    /**
     * Pairs deletion/addition runs into two columns (same-index pairing, like
     * Pierre's split view): a run of `n` deletions followed by `m` additions
     * becomes `max(n, m)` rows, the shorter side keeping an empty cell.
     */
    fun split(rows: List<DiffRenderRow>): List<PatchRow> {
        val out = ArrayList<PatchRow>(rows.size)
        var index = 0
        while (index < rows.size) {
            when (rows[index].kind) {
                DiffLineKind.DELETION, DiffLineKind.ADDITION -> {
                    val deletions = ArrayList<DiffRenderRow>()
                    while (index < rows.size && rows[index].kind == DiffLineKind.DELETION) {
                        deletions += rows[index++]
                    }
                    val additions = ArrayList<DiffRenderRow>()
                    while (index < rows.size && rows[index].kind == DiffLineKind.ADDITION) {
                        additions += rows[index++]
                    }
                    repeat(maxOf(deletions.size, additions.size)) { position ->
                        out += PatchRow.Columns(deletions.getOrNull(position), additions.getOrNull(position))
                    }
                }

                // Context exists on both sides; each column shows its own number.
                DiffLineKind.CONTEXT -> out += PatchRow.Columns(rows[index], rows[index]).also { index++ }

                // Hunks, file headers and meta span the whole row.
                else -> out += PatchRow.Columns(null, null, full = rows[index++])
            }
        }
        return out
    }

    /**
     * Character ranges that differ between a deletion and its paired addition,
     * as `(rangeInBefore, rangeInAfter)` — common prefix/suffix trimmed, the
     * remaining middle emphasised. `null` on a side means "nothing changed
     * there" (e.g. a pure insertion).
     *
     * Approximation of `lineDiffType: "word-alt"` (`pierre/index.ts:213`).
     */
    fun changedRanges(before: String, after: String): Pair<IntRange?, IntRange?> {
        if (before == after) return null to null
        val shared = minOf(before.length, after.length)
        var prefix = 0
        while (prefix < shared && before[prefix] == after[prefix]) prefix++
        var suffix = 0
        while (suffix < shared - prefix && before[before.length - 1 - suffix] == after[after.length - 1 - suffix]) {
            suffix++
        }
        val beforeEnd = before.length - 1 - suffix
        val afterEnd = after.length - 1 - suffix
        return (if (prefix <= beforeEnd) prefix..beforeEnd else null) to
            (if (prefix <= afterEnd) prefix..afterEnd else null)
    }

    /** Collapse hides the unchanged context lines only (`ui.sessionReviewV2.hideNonDiffLines`). */
    private fun visible(kind: DiffLineKind, mode: ReviewExpandMode): Boolean =
        mode == ReviewExpandMode.EXPAND || kind != DiffLineKind.CONTEXT

    /**
     * Numbers every content line from its hunk header (`@@ -old, +new @@`).
     * Lines before the first parsed hunk keep null numbers.
     */
    private fun number(lines: List<DiffLine>): List<DiffRenderRow> {
        val out = ArrayList<DiffRenderRow>(lines.size)
        var oldLine = 0
        var newLine = 0
        var inHunk = false
        for (line in lines) {
            when (line.kind) {
                DiffLineKind.HUNK -> {
                    val match = HUNK_START.find(line.text)
                    if (match != null) {
                        inHunk = true
                        oldLine = match.groupValues[1].toIntOrNull() ?: 0
                        newLine = match.groupValues[2].toIntOrNull() ?: 0
                    }
                    out += DiffRenderRow(line.kind, line.text)
                }

                DiffLineKind.ADDITION ->
                    out += DiffRenderRow(line.kind, line.text, newLine = if (inHunk) newLine++ else null)

                DiffLineKind.DELETION ->
                    out += DiffRenderRow(line.kind, line.text, oldLine = if (inHunk) oldLine++ else null)

                DiffLineKind.CONTEXT -> {
                    val old = if (inHunk) oldLine++ else null
                    val new = if (inHunk) newLine++ else null
                    out += DiffRenderRow(line.kind, line.text, old, new)
                }

                else -> out += DiffRenderRow(line.kind, line.text)
            }
        }
        return out
    }

    /**
     * Word-level emphasis: each deletion run is paired by index with the
     * addition run that follows it (the same pairing [split] uses), and both
     * rows get the differing middle of the pair.
     */
    private fun emphasize(rows: List<DiffRenderRow>): List<DiffRenderRow> {
        val out = rows.toMutableList()
        var index = 0
        while (index < out.size) {
            if (out[index].kind != DiffLineKind.DELETION) {
                index++
                continue
            }
            val deletions = index
            while (index < out.size && out[index].kind == DiffLineKind.DELETION) index++
            val additions = index
            while (index < out.size && out[index].kind == DiffLineKind.ADDITION) index++
            val pairs = minOf(additions - deletions, index - additions)
            repeat(pairs) { offset ->
                val deletion = out[deletions + offset]
                val addition = out[additions + offset]
                val before = deletion.content
                val after = addition.content
                if (before.length > MAX_HIGHLIGHT_CHARS || after.length > MAX_HIGHLIGHT_CHARS) return@repeat
                val (beforeRange, afterRange) = changedRanges(before, after)
                out[deletions + offset] = deletion.copy(changed = listOfNotNull(beforeRange))
                out[additions + offset] = addition.copy(changed = listOfNotNull(afterRange))
            }
        }
        return out
    }
}
