package com.xiufeigo.opencodeshell.ui.markdown

/**
 * Lightweight, pure-Kotlin markdown parser for the session timeline.
 *
 * Upstream renders assistant text through `marked` (GFM tables on) + `remend`
 * (`research/02` §3.5b); this parser covers the subset the timeline actually
 * shows — ATX headings, fenced code, unordered/ordered lists, block quotes,
 * thematic breaks, GFM tables, paragraphs, and the inline spans bold / italic /
 * inline code / link — matching the block structure of
 * `_upstream/packages/session-ui/src/components/markdown.css`.
 *
 * Deliberate CommonMark simplifications (documented, tested):
 * - soft line breaks inside a paragraph collapse to a single space;
 * - list continuation lines are folded into the current item with a space
 *   (nested lists render as part of the parent's text);
 * - no setext headings, reference links or HTML blocks;
 * - tables are GFM pipe tables only (header row + delimiter row required,
 *   both on immediately adjacent lines; leading/trailing pipes optional;
 *   alignment markers parsed but alignment is not rendered — upstream
 *   left-aligns every cell: `text-align: start`);
 * - an **unclosed** code fence (a streaming answer mid-block) still yields a
 *   [MdBlock.CodeBlock] with `complete = false` — the same
 *   `mode: "code", complete: false` state upstream's `markdown-stream.ts`
 *   produces, so a partial fence never flashes as raw back-ticks.
 *
 * The parser is a pure function with no Android/Compose imports so the whole
 * contract is JVM-testable (`TimelineFidelityTest`).
 */
object MarkdownParser {

    fun parse(markdown: String): List<MdBlock> {
        if (markdown.isBlank()) return emptyList()
        val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val blocks = ArrayList<MdBlock>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) {
                i++
                continue
            }
            val fence = fenceStart(line)
            when {
                fence != null -> {
                    val code = StringBuilder()
                    var j = i + 1
                    var closed = false
                    while (j < lines.size) {
                        if (isFenceClose(lines[j], fence)) {
                            closed = true
                            break
                        }
                        if (j > i + 1) code.append('\n')
                        code.append(lines[j])
                        j++
                    }
                    blocks += MdBlock.CodeBlock(fence.language, code.toString(), complete = closed)
                    i = if (closed) j + 1 else lines.size
                }

                headingLevel(line) > 0 -> {
                    val level = headingLevel(line)
                    blocks += MdBlock.Heading(level, parseInline(headingText(line, level)))
                    i++
                }

                isThematicBreak(line) -> {
                    blocks += MdBlock.ThematicBreak
                    i++
                }

                line.trimStart().startsWith('>') -> {
                    val quoted = ArrayList<String>()
                    var j = i
                    while (j < lines.size && lines[j].trimStart().startsWith('>')) {
                        quoted += lines[j].trimStart().removePrefix(">").removePrefix(" ")
                        j++
                    }
                    blocks += MdBlock.BlockQuote(parse(quoted.joinToString("\n")))
                    i = j
                }

                listMarker(line) != null -> {
                    val first = listMarker(line)!!
                    val items = ArrayList<List<MdSpan>>()
                    var current = StringBuilder(first.text)
                    var j = i + 1
                    while (j < lines.size) {
                        val next = lines[j]
                        if (next.isBlank()) break
                        val marker = listMarker(next)
                        when {
                            marker != null -> {
                                items += parseInline(current.toString().trim())
                                current = StringBuilder(marker.text)
                            }
                            // Indented continuation (incl. nested list markers,
                            // which keep their literal marker text) folds into
                            // the current item — see the class doc.
                            next.startsWith(' ') || next.startsWith('\t') ->
                                current.append(' ').append(next.trim())
                            else -> break
                        }
                        j++
                    }
                    items += parseInline(current.toString().trim())
                    blocks += MdBlock.BulletList(ordered = first.ordered, items = items)
                    i = j
                }

                else -> {
                    // GFM pipe table: header row + delimiter row, inline
                    // formatting inside cells (upstream `marked` GFM tables on).
                    val table = tryParseTable(lines, i)
                    if (table != null) {
                        blocks += table.block
                        i = table.next
                        continue
                    }
                    val paragraph = StringBuilder(line.trim())
                    var j = i + 1
                    while (j < lines.size) {
                        val next = lines[j]
                        if (next.isBlank() || startsBlock(lines, j)) break
                        paragraph.append(' ').append(next.trim())
                        j++
                    }
                    blocks += MdBlock.Paragraph(parseInline(paragraph.toString()))
                    i = j
                }
            }
        }
        return blocks
    }

    /** True when `lines[index]` opens a new block (used to end a lazy paragraph). */
    private fun startsBlock(lines: List<String>, index: Int): Boolean {
        val line = lines[index]
        return fenceStart(line) != null ||
            headingLevel(line) > 0 ||
            isThematicBreak(line) ||
            line.trimStart().startsWith('>') ||
            listMarker(line) != null ||
            // A table header row looks like a paragraph line on its own; only
            // treat it as a block start when the delimiter row follows.
            (line.contains('|') && index + 1 < lines.size && isTableDelimiter(lines[index + 1], line))
    }

    // ------------------------------------------------------------ GFM tables

    private class TableResult(val block: MdBlock.Table, val next: Int)

    /**
     * GFM pipe table at `lines[start]` (the header row). Requires the next
     * *adjacent* line to be a delimiter row (`| --- | :---: | ---: |`,
     * leading/trailing pipes optional, at least one hyphen per column) —
     * upstream `marked` GFM likewise requires the delimiter immediately after
     * the header; body rows are consecutive lines containing a pipe. A candidate
     * that fails any check returns null and the caller falls back to a plain
     * paragraph.
     *
     * Escaped pipes (`\|`) stay inside a cell; alignment markers (`:`) are
     * accepted but not surfaced — upstream `markdown.css` left-aligns
     * (`text-align: start`) every cell.
     */
    private fun tryParseTable(lines: List<String>, start: Int): TableResult? {
        val headerLine = lines[start]
        if (!headerLine.contains('|')) return null
        if (start + 1 >= lines.size) return null
        val delimiterCells = splitRow(lines[start + 1]) ?: return null
        val headerCells = splitRow(headerLine) ?: return null
        if (headerCells.isEmpty() || delimiterCells.isEmpty()) return null
        if (delimiterCells.size != headerCells.size) return null
        if (!delimiterCells.all(::isDelimiterCell)) return null
        val header = headerCells.map { parseInline(it.trim()) }
        val rows = ArrayList<List<List<MdSpan>>>()
        var j = start + 2
        while (j < lines.size) {
            val line = lines[j]
            if (line.isBlank() || !line.contains('|')) break
            // Another block opener (fence/heading/quote/list/hr) ends the body.
            if (fenceStart(line) != null || headingLevel(line) > 0 ||
                isThematicBreak(line) || line.trimStart().startsWith('>') ||
                listMarker(line) != null
            ) break
            val cells = splitRow(line) ?: break
            if (cells.size != headerCells.size) break
            rows += cells.map { parseInline(it.trim()) }
            j++
        }
        return TableResult(MdBlock.Table(header, rows), j)
    }

    /**
     * Splits one table row on unescaped pipes. Returns null when the row has
     * no usable cell content (no pipe at all). Outer pipes are optional:
     * `a | b` and `| a | b |` both yield two cells.
     *
     * Escaped pipes (`\|`) are preserved verbatim through the split — the
     * inline backslash-unescaper already turns `\|` into `|` (`ESCAPABLE`
     * contains `|`), so cells reach [parseInline] untouched.
     *
     * Pipes inside code spans (`` `a|b` ``) never split — same as upstream
     * `marked` GFM tables; the span text is kept verbatim for [parseInline].
     */
    private fun splitRow(line: String): List<String>? {
        if (!line.contains('|')) return null
        val cells = ArrayList<String>()
        val current = StringBuilder()
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '`' -> {
                    val run = countRun(line, i, '`')
                    var close = i + run
                    var found = -1
                    while (close < line.length) {
                        if (line[close] == '`') {
                            val length = countRun(line, close, '`')
                            if (length == run) {
                                found = close
                                break
                            }
                            close += length
                        } else {
                            close++
                        }
                    }
                    if (found < 0) {
                        // Unterminated span: the rest is literal cell text.
                        current.append(line.substring(i))
                        i = line.length
                    } else {
                        current.append(line.substring(i, found + run))
                        i = found + run
                    }
                }
                ch == '\\' && i + 1 < line.length && line[i + 1] == '|' -> {
                    // Keep the escape for the inline unescaper (`\|` → `|`).
                    current.append('\\').append('|')
                    i += 2
                }
                ch == '|' -> {
                    cells += current.toString()
                    current.clear()
                    i++
                }
                else -> {
                    current.append(ch)
                    i++
                }
            }
        }
        cells += current.toString()
        // `| a | b |` leaves empty edge cells from the outer pipes — drop them.
        if (cells.isNotEmpty() && cells.first().isBlank()) cells.removeAt(0)
        if (cells.isNotEmpty() && cells.last().isBlank()) cells.removeAt(cells.size - 1)
        return cells
    }

    /** One delimiter cell: optional colons around 1+ hyphens (`:---:`, `---`, …). */
    private fun isDelimiterCell(cell: String): Boolean {
        val text = cell.trim()
        if (text.isEmpty()) return false
        val inner = text.trim(':')
        if (inner.isEmpty()) return false
        if (!inner.all { it == '-' }) return false
        // A colon may only appear at the edges (`-:-` is not a delimiter).
        return text.count { it == ':' } <= 2 &&
            (!text.contains(':') || text.startsWith(':') || text.endsWith(':'))
    }

    /**
     * True when `delimiter` is a valid GFM delimiter row for a header with
     * `headerCells` columns: same column count, every cell a hyphen run with
     * optional edge colons.
     */
    private fun isTableDelimiter(delimiter: String, header: String): Boolean {
        val delimiterCells = splitRow(delimiter) ?: return false
        val headerCells = splitRow(header) ?: return false
        if (headerCells.isEmpty() || delimiterCells.size != headerCells.size) return false
        return delimiterCells.all(::isDelimiterCell)
    }

    // ---------------------------------------------------------------- blocks

    private class Fence(val marker: Char, val length: Int, val language: String?)

    /** ````` ``` lang ````` / `~~~ lang` opener (up to 3 leading spaces, CommonMark). */
    private fun fenceStart(line: String): Fence? {
        val trimmed = line.trimStart()
        if (line.length - trimmed.length > 3) return null
        if (trimmed.length < 3) return null
        val marker = trimmed[0]
        if (marker != '`' && marker != '~') return null
        var length = 0
        while (length < trimmed.length && trimmed[length] == marker) length++
        if (length < 3) return null
        val info = trimmed.substring(length).trim()
        // CommonMark: an info string on a back-tick fence must not contain `.
        if (marker == '`' && info.contains('`')) return null
        return Fence(marker, length, info.ifEmpty { null })
    }

    private fun isFenceClose(line: String, fence: Fence): Boolean {
        val trimmed = line.trim()
        if (trimmed.length < fence.length) return false
        return trimmed.all { it == fence.marker }
    }

    /** ATX heading level 1..6, or 0. Requires `#` followed by space/end. */
    private fun headingLevel(line: String): Int {
        val trimmed = line.trimStart()
        if (line.length - trimmed.length > 3) return 0
        var level = 0
        while (level < trimmed.length && trimmed[level] == '#') level++
        if (level == 0 || level > 6) return 0
        if (trimmed.length > level && trimmed[level] != ' ') return 0
        return level
    }

    /**
     * Heading body with the optional closing `#` run stripped — CommonMark
     * only allows a closing sequence preceded by a space (so `## C#` keeps
     * its trailing hash).
     */
    private fun headingText(line: String, level: Int): String {
        val text = line.trimStart().substring(level).trim()
        val closing = text.takeLastWhile { it == '#' }
        if (closing.isEmpty()) return text
        val before = text.dropLast(closing.length)
        return if (before.isEmpty() || before.endsWith(' ') || before.endsWith('\t')) {
            before.trim()
        } else {
            text
        }
    }

    /** `---` / `***` / `___` (3+, spaces allowed between) — invisible spacer. */
    private fun isThematicBreak(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.length < 3) return false
        val marker = trimmed[0]
        if (marker != '-' && marker != '*' && marker != '_') return false
        var count = 0
        for (ch in trimmed) {
            if (ch == marker) count++
            else if (ch != ' ' && ch != '\t') return false
        }
        return count >= 3
    }

    private class ListItem(val ordered: Boolean, val text: String)

    /** `- item` / `* item` / `+ item` / `1. item` / `1) item`. */
    private fun listMarker(line: String): ListItem? {
        val trimmed = line.trimStart()
        if (line.length - trimmed.length > 3) return null
        if (trimmed.length >= 2 &&
            (trimmed[0] == '-' || trimmed[0] == '*' || trimmed[0] == '+') &&
            trimmed[1] == ' '
        ) {
            return ListItem(ordered = false, text = trimmed.substring(2))
        }
        val match = ORDERED_MARKER.matchEntire(trimmed) ?: return null
        return ListItem(ordered = true, text = match.groupValues[1])
    }

    private val ORDERED_MARKER = Regex("^\\d{1,9}[.)]\\s+(.*)$")

    // ---------------------------------------------------------------- inline

    /**
     * Inline spans of one text run. Code spans are terminal (their content is
     * never re-scanned, so markdown inside back-ticks stays literal); bold /
     * italic nest; link labels keep their inner styling.
     */
    fun parseInline(text: String): List<MdSpan> {
        val spans = ArrayList<MdSpan>()
        parseInto(text, spans, bold = false, italic = false, link = null)
        return mergeAdjacent(spans)
    }

    private fun parseInto(
        s: String,
        out: MutableList<MdSpan>,
        bold: Boolean,
        italic: Boolean,
        link: String?,
    ) {
        val literal = StringBuilder()

        fun flush() {
            if (literal.isNotEmpty()) {
                out += MdSpan(literal.toString(), bold, italic, code = false, link = link)
                literal.clear()
            }
        }

        var i = 0
        while (i < s.length) {
            val ch = s[i]
            when {
                ch == '\\' && i + 1 < s.length && s[i + 1] in ESCAPABLE -> {
                    literal.append(s[i + 1])
                    i += 2
                }

                ch == '`' -> {
                    val run = countRun(s, i, '`')
                    val close = findClosingRun(s, i + run, run)
                    if (close < 0) {
                        literal.append(ch)
                        i++
                    } else {
                        flush()
                        out += MdSpan(
                            normalizeCodeSpan(s.substring(i + run, close)),
                            bold,
                            italic,
                            code = true,
                            link = link,
                        )
                        i = close + run
                    }
                }

                ch == '*' || ch == '_' -> {
                    val run = countRun(s, i, ch).coerceAtMost(3)
                    val openerOk = i + run < s.length && !s[i + run].isWhitespace() &&
                        // Intra-word snake_case stays literal (CommonMark '_' rule).
                        (ch != '_' || i == 0 || !s[i - 1].isLetterOrDigit())
                    val close = if (openerOk) findDelimiter(s, i + run, ch, run) else -1
                    if (close < 0) {
                        literal.append(ch)
                        i++
                    } else {
                        flush()
                        parseInto(
                            s.substring(i + run, close),
                            out,
                            bold = bold || run >= 2,
                            italic = italic || run == 1 || run >= 3,
                            link = link,
                        )
                        i = close + run
                    }
                }

                ch == '[' && link == null -> {
                    val parsed = parseLink(s, i)
                    if (parsed == null) {
                        literal.append(ch)
                        i++
                    } else {
                        flush()
                        parseInto(parsed.label, out, bold, italic, link = parsed.url)
                        i = parsed.end
                    }
                }

                // `![alt](src)` renders as its alt text (the timeline has no
                // inline image slot; the attachment chip carries real files).
                ch == '!' && i + 1 < s.length && s[i + 1] == '[' -> {
                    val parsed = parseLink(s, i + 1)
                    if (parsed == null) {
                        literal.append(ch)
                        i++
                    } else {
                        flush()
                        if (parsed.label.isNotEmpty()) {
                            out += MdSpan(parsed.label, bold, italic, code = false, link = link)
                        }
                        i = parsed.end
                    }
                }

                else -> {
                    literal.append(ch)
                    i++
                }
            }
        }
        flush()
    }

    private class Link(val label: String, val url: String, val end: Int)

    /** `[label](url)` starting at `open` (the `[`); `null` when malformed. */
    private fun parseLink(s: String, open: Int): Link? {
        val labelEnd = s.indexOf(']', open + 1)
        if (labelEnd < 0 || labelEnd + 1 >= s.length || s[labelEnd + 1] != '(') return null
        var depth = 1
        var j = labelEnd + 2
        while (j < s.length && depth > 0) {
            when (s[j]) {
                '(' -> depth++
                ')' -> depth--
            }
            if (depth > 0) j++
        }
        if (depth != 0) return null
        val url = s.substring(labelEnd + 2, j).trim()
            .removePrefix("<").removeSuffix(">")
        if (url.isEmpty()) return null
        return Link(label = s.substring(open + 1, labelEnd), url = url, end = j + 1)
    }

    /** Closing run of exactly [run] back-ticks, or -1 (unterminated span). */
    private fun findClosingRun(s: String, from: Int, run: Int): Int {
        var i = from
        while (i < s.length) {
            if (s[i] == '`') {
                val length = countRun(s, i, '`')
                if (length == run) return i
                i += length
            } else {
                i++
            }
        }
        return -1
    }

    /**
     * Closing emphasis delimiter: same char, same run length, not preceded by
     * whitespace (a minimal flanking rule — keeps `2 * 3 * 4` literal).
     */
    private fun findDelimiter(s: String, from: Int, marker: Char, run: Int): Int {
        val needle = marker.toString().repeat(run)
        var i = from
        while (true) {
            val at = s.indexOf(needle, i)
            if (at < 0) return -1
            // Empty content (`****`) and a space-adjacent closer are not emphasis.
            if (at > from && !s[at - 1].isWhitespace()) return at
            i = at + 1
        }
    }

    private fun countRun(s: String, from: Int, ch: Char): Int {
        var run = 0
        while (from + run < s.length && s[from + run] == ch) run++
        return run
    }

    /** CommonMark code-span normalisation: newlines→spaces, one optional edge space stripped. */
    private fun normalizeCodeSpan(text: String): String {
        val flat = text.replace('\n', ' ').replace('\t', ' ')
        return if (flat.length > 2 && flat.startsWith(' ') && flat.endsWith(' ') && flat.isNotBlank()) {
            flat.substring(1, flat.length - 1)
        } else {
            flat
        }
    }

    /** Merges same-style neighbours so the renderer emits fewer spans. */
    private fun mergeAdjacent(spans: List<MdSpan>): List<MdSpan> {
        val merged = ArrayList<MdSpan>(spans.size)
        for (span in spans) {
            val last = merged.lastOrNull()
            if (last != null && last.sameStyle(span)) {
                merged[merged.lastIndex] = last.copy(text = last.text + span.text)
            } else {
                merged += span
            }
        }
        return merged
    }

    private fun MdSpan.sameStyle(other: MdSpan): Boolean =
        bold == other.bold && italic == other.italic && code == other.code && link == other.link

    /** Punctuation that `\` escapes in CommonMark (plus the escapes chat output actually uses). */
    private val ESCAPABLE = "\\`*_{}[]()#+-.!~|>".toSet()
}

/** One inline run with its resolved style flags. */
data class MdSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val link: String? = null,
)

/** Block-level markdown structure (see [MarkdownParser]). */
sealed interface MdBlock {
    data class Paragraph(val spans: List<MdSpan>) : MdBlock

    /** ATX heading, [level] 1..6. */
    data class Heading(val level: Int, val spans: List<MdSpan>) : MdBlock

    /**
     * Fenced code. [complete] is false for a stream-cut unclosed fence
     * (upstream `markdown-stream.ts` `mode:"code", complete:false`).
     */
    data class CodeBlock(val language: String?, val code: String, val complete: Boolean) : MdBlock

    /** One flat list; each item is its inline spans. */
    data class BulletList(val ordered: Boolean, val items: List<List<MdSpan>>) : MdBlock

    /** `>` quote; children are parsed recursively. */
    data class BlockQuote(val children: List<MdBlock>) : MdBlock

    /**
     * GFM pipe table. [header] is one inline-span list per column, [rows] one
     * entry per body row. Column alignment markers (`:---:`) are accepted by
     * the parser but not surfaced — upstream `markdown.css` left-aligns every
     * cell (`text-align: start`).
     */
    data class Table(
        val header: List<List<MdSpan>>,
        val rows: List<List<List<MdSpan>>>,
    ) : MdBlock

    /** `---` — upstream renders an invisible 32 px vertical spacer. */
    data object ThematicBreak : MdBlock
}
