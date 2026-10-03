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
 * A-1 adds `![alt](src)`: a paragraph that carries nothing but images becomes one
 * [MdBlock.Image] per image (the shape a chart/screenshot answer uses) and the
 * renderer draws it through `GET /api/fs/read` / the remote URL
 * (`ui/markdown/MarkdownImages`); an image mixed into a paragraph keeps its alt
 * text for now ([MdSpan.image] carries the target so an inline renderer can land
 * without a parser change).
 *
 * Inline links come from explicit `[label](url)` syntax and the three GFM
 * extended autolinks in body text — a bare `http://` / `https://` URL, a bare
 * `www.` domain, and an email address — so a pasted link or address taps
 * through `LinkAnnotation.Url` into the in-app browser (`http(s)` and `www.`
 * keep their own destination, an email gets `mailto:`; a literal `mailto:` /
 * `xmpp:` prefix is absorbed into the link, as cmark-gfm does). `ftp:` and
 * every other scheme stays literal, and code spans / fenced blocks are
 * terminal — a link inside back-ticks stays plain text.
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
 *   produces, so a partial fence never flashes as raw back-ticks;
 * - block quotes nest at most [MAX_QUOTE_DEPTH] levels; deeper `>` markers
 *   degrade to plain text instead of recursing (each level peels one `>`, so
 *   a `>`-heavy line would otherwise recurse until StackOverflowError);
 * - a bare `www.` domain gets an `https://` destination where GFM inserts
 *   `http://`, and its domain characters are `[A-Za-z0-9._-]` plus Unicode
 *   letters/digits (GFM admits any non-punctuation character, emoji included);
 * - `ftp://` and every other scheme stays literal (GFM links `ftp://`).
 *
 * The parser is a pure function with no Android/Compose imports so the whole
 * contract is JVM-testable (`TimelineFidelityTest`).
 */
object MarkdownParser {

    /**
     * Block-quote nesting cap. Each recursion peels exactly one `>` per line,
     * so one line with thousands of `>` (pathological quoted tool/file
     * output) would recurse unbounded and crash the render with a
     * StackOverflowError; past this depth the remainder renders as plain text.
     */
    const val MAX_QUOTE_DEPTH = 32

    fun parse(markdown: String): List<MdBlock> = parse(markdown, 0)

    private fun parse(markdown: String, depth: Int): List<MdBlock> {
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
                    blocks += if (depth >= MAX_QUOTE_DEPTH) {
                        MdBlock.Paragraph(parseInline(quoted.joinToString(" ")))
                    } else {
                        MdBlock.BlockQuote(parse(quoted.joinToString("\n"), depth + 1))
                    }
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
                    val spans = parseInline(paragraph.toString())
                    // A-1: a paragraph that carries nothing but images becomes
                    // one [MdBlock.Image] per image, which is the shape an
                    // assistant answer uses for a chart/screenshot; anything
                    // mixed keeps the alt-text spans.
                    val images = imageBlocks(spans)
                    if (images != null) {
                        blocks += images
                    } else {
                        blocks += MdBlock.Paragraph(spans)
                    }
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
     * italic nest; link labels keep their inner styling; the GFM extended
     * autolinks (`http(s)://`, `www.`, email) autolink ([appendLiteral]).
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
                appendLiteral(literal.toString(), out, bold, italic, link)
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

                // `![alt](src)` — A-1: the image span keeps its target so the
                // renderer can draw it (`markdown-image.ts`); a paragraph made of
                // nothing but images is emitted as [MdBlock.Image] blocks below,
                // which is the shape an assistant answer actually uses. Mixed
                // text/images keep the alt text inline (the Compose body has no
                // inline-image slot yet — documented gap).
                ch == '!' && i + 1 < s.length && s[i + 1] == '[' -> {
                    val parsed = parseLink(s, i + 1)
                    if (parsed == null) {
                        literal.append(ch)
                        i++
                    } else {
                        flush()
                        if (parsed.label.isNotEmpty() || parsed.url.isNotEmpty()) {
                            out += MdSpan(
                                parsed.label,
                                bold,
                                italic,
                                code = false,
                                link = link,
                                image = MdImage(parsed.label, parsed.url),
                            )
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

    /**
     * Emits one literal run, splitting GFM extended autolinks into link spans
     * so the renderer carries them as `LinkAnnotation.Url` — tappable into the
     * in-app browser. Three shapes autolink ([findAutolink]): a bare
     * `http(s)://` URL, a bare `www.` domain, and an email address. Runs
     * already inside an explicit `[label](url)` (`link != null`) keep the outer
     * destination; code spans never reach here (they are terminal in
     * [parseInto]) and fenced blocks never go through inline parsing, so a link
     * inside back-ticks or a fence stays plain text. `ftp:` and every other
     * scheme is not matched and stays literal.
     */
    private fun appendLiteral(
        text: String,
        out: MutableList<MdSpan>,
        bold: Boolean,
        italic: Boolean,
        link: String?,
    ) {
        if (link != null || !mayAutolink(text)) {
            out += MdSpan(text, bold, italic, code = false, link = link)
            return
        }
        var i = 0
        while (i < text.length) {
            val match = findAutolink(text, i)
            if (match == null) {
                out += MdSpan(text.substring(i), bold, italic, code = false, link = null)
                return
            }
            if (match.start > i) {
                out += MdSpan(text.substring(i, match.start), bold, italic, code = false, link = null)
            }
            out += MdSpan(
                text.substring(match.start, match.end),
                bold,
                italic,
                code = false,
                link = match.url,
            )
            i = match.end
        }
    }

    /** Cheap gate before the per-character scan: one of the three triggers is present. */
    private fun mayAutolink(text: String): Boolean =
        text.indexOf('@') >= 0 ||
            text.indexOf("http", ignoreCase = true) >= 0 ||
            text.indexOf("www.", ignoreCase = true) >= 0

    /** One resolved autolink: the link-text range `[start, end)` and its destination. */
    private class Autolink(val start: Int, val end: Int, val url: String)

    /**
     * The next GFM extended autolink at or after [from], or null. [from] is the
     * end of the previous link (0 for the first): an email's local part never
     * reaches back past it, so a match always starts at or after [from] and the
     * caller's emit cursor never has to rewind — cmark-gfm's `postprocess_text`
     * bounds its own scan the same way.
     */
    private fun findAutolink(text: String, from: Int): Autolink? {
        var i = from
        while (i < text.length) {
            val found = when (text[i]) {
                'h', 'H' -> matchHttp(text, i)
                'w', 'W' -> matchWww(text, i)
                '@' -> matchEmail(text, i, from)
                else -> null
            }
            if (found != null) return found
            i++
        }
        return null
    }

    /** `http(s)://` + a target, trimmed by [trimAutolinkEnd]; `ftp:` and other schemes stay literal. */
    private fun matchHttp(text: String, at: Int): Autolink? {
        val scheme = urlSchemeLength(text, at)
        if (scheme == 0) return null
        val end = trimAutolinkEnd(text, at, runEnd(text, at + scheme))
        if (end <= at + scheme) return null
        return Autolink(at, end, text.substring(at, end))
    }

    /**
     * `www.` + a [valid domain][isValidDomain] (`gfm` §Autolinks (extension)),
     * then zero or more non-space characters (path, query, fragment) trimmed by
     * [trimAutolinkEnd]. Recognised only at the start of a run or after
     * whitespace / the delimiters `*` `_` `~` `(` — `xwww.example.com` stays
     * literal. The destination is [WWW_SCHEME] + the matched text.
     */
    private fun matchWww(text: String, at: Int): Autolink? {
        if (!text.regionMatches(at, "www.", 0, 4, ignoreCase = true)) return null
        if (at > 0 && !isAutolinkStart(text[at - 1])) return null
        val end = trimAutolinkEnd(text, at, runEnd(text, at + 4))
        if (end <= at + 4) return null
        // Only the leading domain-char run is validated; a path/query after it
        // is free-form (`www.github.com/a_b` links).
        if (!isValidDomain(text, at, domainEnd(text, at, end))) return null
        return Autolink(at, end, WWW_SCHEME + text.substring(at, end))
    }

    /**
     * `local@domain` (`gfm` §Autolinks (extension); cmark-gfm's
     * `postprocess_text`): a local part of alphanumerics and `. - _ +`, an `@`,
     * then a domain of alphanumerics and `- _` separated by periods, with at
     * least one period and a last character that is neither `-` nor `_`. A
     * second `@` restarts the match with the text between the two as the new
     * local part. The destination is `mailto:` + the address, unless a literal
     * `mailto:` / `xmpp:` prefix was absorbed into the link text — then the
     * text is already the destination. [lowerBound] is the caller's scan start,
     * which the local part may not reach back past.
     */
    private fun matchEmail(text: String, at: Int, lowerBound: Int): Autolink? {
        val head = emailHead(text, at, lowerBound) ?: return null
        var end = at + 1
        while (end < text.length) {
            val ch = text[end]
            if (ch == '@') return matchEmail(text, end, at + 1)
            if (ch.isLetterOrDigit() || ch == '-' || ch == '_' || (ch == '/' && head.xmpp)) {
                end++
                continue
            }
            // A `.` belongs to the domain only when an alphanumeric follows: the
            // full stop of a sentence ends the address instead.
            if (ch == '.' && end + 1 < text.length && text[end + 1].isLetterOrDigit()) {
                end++
                continue
            }
            break
        }
        if (end - at < 2 || !isEmailDomain(text, at + 1, end)) return null
        val address = text.substring(head.start, end)
        return Autolink(head.start, end, if (head.autoMailto) MAILTO_SCHEME + address else address)
    }

    private class EmailHead(val start: Int, val autoMailto: Boolean, val xmpp: Boolean)

    /**
     * The local part behind the `@` at [at]: rewinds over the allowed
     * characters, absorbing a `mailto:` / `xmpp:` prefix when one sits
     * immediately before the address — `mailto:foo@bar.com` links whole while
     * `mmmmailto:foo@bar.com` links only the address, because the prefix must
     * follow a non-alphanumeric or start the run. `null` when there is no local
     * part (an address may not start with `@`).
     */
    private fun emailHead(text: String, at: Int, lowerBound: Int): EmailHead? {
        var rewind = 0
        var autoMailto = true
        var xmpp = false
        while (at - rewind - 1 >= lowerBound) {
            val i = at - rewind - 1
            val ch = text[i]
            when {
                ch.isLetterOrDigit() || ch == '.' || ch == '+' || ch == '-' || ch == '_' -> rewind++
                ch == ':' && isSchemeAt(text, i, "mailto:", lowerBound) -> {
                    autoMailto = false
                    rewind++
                }
                ch == ':' && isSchemeAt(text, i, "xmpp:", lowerBound) -> {
                    autoMailto = false
                    xmpp = true
                    rewind++
                }
                else -> break
            }
        }
        if (rewind == 0) return null
        return EmailHead(at - rewind, autoMailto, xmpp)
    }

    /**
     * [scheme] (colon included) ends at [colon], starts at or after
     * [lowerBound], and is preceded by a non-alphanumeric — `at:foo@bar.com`
     * absorbs `xmpp:`, `mmmmailto:` does not absorb `mailto:`.
     */
    private fun isSchemeAt(text: String, colon: Int, scheme: String, lowerBound: Int): Boolean {
        val start = colon + 1 - scheme.length
        if (start < lowerBound) return false
        if (!text.regionMatches(start, scheme, 0, scheme.length, ignoreCase = true)) return false
        return start == lowerBound || !text[start - 1].isLetterOrDigit()
    }

    /**
     * GFM valid domain over `[from, end)`: at least one period, and no
     * underscore in the last two segments — `www.xxx.yyy._zzz` and
     * `www.xxx._yyy.zzz` are not domains, `www._xxx.yyy.zzz` is.
     */
    private fun isValidDomain(text: String, from: Int, end: Int): Boolean {
        var periods = 0
        var underscores = 0
        var previousUnderscores = 0
        for (i in from until end) {
            when (text[i]) {
                '.' -> {
                    periods++
                    previousUnderscores = underscores
                    underscores = 0
                }
                '_' -> underscores++
            }
        }
        if (periods == 0) return false
        return previousUnderscores == 0 && underscores == 0
    }

    /** End of the leading domain-char run in `[from, end)`; the rest is path/query. */
    private fun domainEnd(text: String, from: Int, end: Int): Int {
        var i = from
        while (i < end && isDomainChar(text[i])) i++
        return i
    }

    /** A domain character: alphanumeric, `-`, `_` or `.`. */
    private fun isDomainChar(ch: Char): Boolean =
        ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == '.'

    /**
     * GFM email domain over `[from, end)`: at least one period, and a last
     * character that is neither `-` nor `_` (`a.b-c_d@a.b` links,
     * `a.b-c_d@a.b-` and `a.b-c_d@a.b_` do not).
     */
    private fun isEmailDomain(text: String, from: Int, end: Int): Boolean {
        if (end <= from) return false
        val last = text[end - 1]
        if (last == '-' || last == '_') return false
        return text.substring(from, end).contains('.')
    }

    /** The extent a bare link can reach: up to the first [isAutolinkEnd] character. */
    private fun runEnd(text: String, from: Int): Int {
        var end = from
        while (end < text.length && !isAutolinkEnd(text[end])) end++
        return end
    }

    /** Start of a run, whitespace, or one of GFM's autolink delimiters `*` `_` `~` `(`. */
    private fun isAutolinkStart(ch: Char): Boolean =
        ch.isWhitespace() || ch == '*' || ch == '_' || ch == '~' || ch == '('

    /** 8 for `https://`, 7 for `http://`, 0 when [at] is neither. */
    private fun urlSchemeLength(text: String, at: Int): Int = when {
        text.regionMatches(at, "https://", 0, 8, ignoreCase = true) -> 8
        text.regionMatches(at, "http://", 0, 7, ignoreCase = true) -> 7
        else -> 0
    }

    /** Terminates a bare-link run: whitespace, `<>` (markup), or a back-tick. */
    private fun isAutolinkEnd(ch: Char): Boolean =
        ch.isWhitespace() || ch == '<' || ch == '>' || ch == '`'

    /**
     * Trims a bare-URL candidate to its linkable extent (GFM trailing rules):
     * a trailing `)` is dropped while `)` outnumbers `(` (so
     * `(see https://x/a)` links without the paren but
     * `https://en.wikipedia.org/wiki/Foo_(bar)` keeps its balanced one), then
     * trailing `? ! . , : ; * _ ~ ' "` are dropped.
     */
    private fun trimAutolinkEnd(text: String, start: Int, end: Int): Int {
        var e = end
        var open = 0
        var close = 0
        for (k in start until e) {
            when (text[k]) {
                '(' -> open++
                ')' -> close++
            }
        }
        while (e > start && text[e - 1] == ')' && close > open) {
            e--
            close--
        }
        while (e > start && text[e - 1] in AUTOLINK_TRAIL) e--
        return e
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

    /**
     * A-1: [spans] as image blocks when the paragraph is nothing but images and
     * whitespace (a lone `![chart](chart.png)`, or two on one line), else null.
     * Whitespace between images is dropped — it is paragraph filler, not
     * content.
     */
    private fun imageBlocks(spans: List<MdSpan>): List<MdBlock.Image>? {
        val images = ArrayList<MdBlock.Image>()
        for (span in spans) {
            val image = span.image
            if (image == null) {
                if (span.text.isBlank()) continue
                return null
            }
            images += MdBlock.Image(alt = image.alt, src = image.src)
        }
        return images.takeIf { it.isNotEmpty() }
    }

    private fun MdSpan.sameStyle(other: MdSpan): Boolean =
        bold == other.bold && italic == other.italic && code == other.code && link == other.link &&
            // A-1: an image span is never merged into a neighbouring text run.
            image == other.image

    /** Punctuation that `\` escapes in CommonMark (plus the escapes chat output actually uses). */
    private val ESCAPABLE = "\\`*_{}[]()#+-.!~|>".toSet()

    /** Trailing characters stripped from a bare-link autolink (GFM rule). */
    private val AUTOLINK_TRAIL = "?!.,:;*_~'\"".toSet()

    /**
     * Inserted before a bare `www.` domain. GFM inserts `http`; this shell
     * prefers TLS for a link the in-app browser opens.
     */
    private const val WWW_SCHEME = "https://"

    /** Inserted before a bare email address. */
    private const val MAILTO_SCHEME = "mailto:"
}

/** One inline run with its resolved style flags. */
data class MdSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val link: String? = null,
    /**
     * A-1: `![alt](src)` carries its target here; [text] is then the alt text,
     * which the renderer shows when the image cannot be read. Every other span
     * has none.
     */
    val image: MdImage? = null,
)

/** `![alt](src)`'s two halves (`markdown-image.ts` reads `src`, `alt` is the fallback). */
data class MdImage(val alt: String, val src: String)

/** Block-level markdown structure (see [MarkdownParser]). */
sealed interface MdBlock {
    data class Paragraph(val spans: List<MdSpan>) : MdBlock

    /** ATX heading, [level] 1..6. */
    data class Heading(val level: Int, val spans: List<MdSpan>) : MdBlock

    /**
     * A-1: one image of a paragraph that carries nothing else (upstream's
     * `<img>` in the markdown flow, `markdown-image.ts:26-60`).
     */
    data class Image(val alt: String, val src: String) : MdBlock

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
