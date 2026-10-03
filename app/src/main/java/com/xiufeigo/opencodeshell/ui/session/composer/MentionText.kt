package com.xiufeigo.opencodeshell.ui.session.composer

/**
 * `@`-mention model for the composer (trigger detection, completion insert,
 * colour classification, `@token` scanning for the sent-message highlighting).
 *
 * Pure Kotlin: every rule below is asserted by `MentionTextTest` on the JVM.
 * The trigger/caret rules mirror `ChatView.onInputChanged` /
 * `ChatView.showMention` L345-411 (the shipped View implementation) with two
 * deliberate widenings, marked below.
 */
object MentionText {

    const val TRIGGER = '@'

    /** `ChatView.MENTION_DEBOUNCE_MS` — one `/find/file` per 200 ms pause. */
    const val DEBOUNCE_MS = 200L

    /** `ChatView.MENTION_LIMIT` — the popover never renders more than 20 rows. */
    const val LIMIT = 20

    /**
     * A `@token` in a body: `@` plus a run of non-whitespace, non-`@` chars.
     * Wider than `ChatView.MENTION_REGEX` (identifier start + `/.-` tail) on
     * purpose — labels may be CJK (`@中文.md`), which the identifier class
     * never matched, so those chips never coloured. [classify] still decides
     * the colour; labels containing spaces colour up to the first space (no
     * regex can bound them against the following prose — [MentionTokens]
     * matches those by literal label instead).
     */
    val TOKEN_REGEX = Regex("@[^\\s@]+")

    /** Which colour family a mention token belongs to (`research/02` §4.2). */
    enum class Kind { FILE, AGENT, REFERENCE }

    /** An open mention: `@` at [start], [query] = text between it and the caret. */
    data class Anchor(val start: Int, val query: String)

    /** Text after accepting a candidate plus the caret it should move to. */
    data class Insertion(val text: String, val caret: Int)

    /** A coloured `@token` inside a message body. */
    data class Span(val start: Int, val end: Int, val kind: Kind)

    /** One popover row. [description] is the trailing muted column (unused for files). */
    data class Candidate(val kind: Kind, val label: String, val description: String? = null)

    /**
     * The mention the caret is currently inside, or null when there is none.
     *
     * Rules (ChatView parity): a caret at 0 has nothing to complete; the `@` is
     * the *last* one before the caret; the text between `@` and the caret must
     * not contain whitespace.
     *
     * Widening vs. ChatView: it only rejected a space; any whitespace (newline,
     * tab) also closes the mention here — otherwise pressing Enter would leave
     * the popover open over the next line.
     */
    fun anchorAt(text: String, caret: Int): Anchor? {
        if (caret <= 0 || caret > text.length) return null
        val at = text.lastIndexOf(TRIGGER, caret - 1)
        if (at < 0) return null
        val query = text.substring(at + 1, caret)
        if (query.any { it.isWhitespace() }) return null
        return Anchor(at, query)
    }

    /**
     * v66 BUG-1: the mention anchor for a caret the editor cannot be trusted with.
     *
     * [anchorAt] answers for the caret, and a caret at 0 answers null — which is
     * the whole `@` surface. That is correct while the caret is real, and wrong
     * the moment it is not: on `emulator-5556` / `emulator-5558` the composer's
     * value came back from a session switch with a **non-empty text and the
     * caret at offset 0**, every later keystroke was applied there, and `@`
     * never opened a panel — `research/v65_verify/5558/17_at_clean.png` /
     * `18_at_wait.png` show `v@` and the caret sitting in front of the `v`, with
     * no card, exactly the two failures the tester filed.
     *
     * Upstream's own rule is **not** caret-anchored: the open shape is
     * `/(?:^|\s)@([^\s@]*)$/` (`composer/suggestions/machine.ts:98-105`) — the
     * `@` query is whatever follows the last `@` up to the **end of the text**.
     * So when the caret yields nothing, the text's own tail is the anchor
     * upstream would have used, and reading it there is both more faithful and
     * immune to a caret the app lost. A caret that *is* inside an anchor still
     * wins, so a mention being completed mid-line keeps its exact span.
     */
    fun anchorAtEndOfLine(text: String): Anchor? {
        if (text.isEmpty()) return null
        val at = text.lastIndexOf(TRIGGER)
        if (at < 0) return null
        val query = text.substring(at + 1)
        if (query.any { it.isWhitespace() }) return null
        return Anchor(at, query)
    }

    /**
     * Replaces `[anchorStart, caret)` with `@path ` — exactly what ChatView does
     * on selection (`editable.replace(at, caret, "@$path ")`).
     *
     * Returns null when the result is stale: the `@` is gone, the caret moved
     * before the anchor, or the query grew a whitespace/space while the search
     * was in flight. Callers must not touch the field in that case.
     */
    fun insert(text: String, anchorStart: Int, caret: Int, path: String): Insertion? {
        if (anchorStart < 0 || caret < anchorStart || caret > text.length) return null
        if (text.getOrNull(anchorStart) != TRIGGER) return null
        if (text.substring(anchorStart + 1, caret).any { it.isWhitespace() }) return null
        val inserted = "$TRIGGER$path "
        val next = text.replaceRange(anchorStart, caret, inserted)
        return Insertion(next, anchorStart + inserted.length)
    }

    /**
     * `@file` vs `@agent` vs `@reference` (`research/02` §4.2):
     * - a file name (a `.` in the last path segment) → [Kind.FILE]
     * - a directory path (slashes, no extension) → [Kind.REFERENCE]
     * - a bare name → [Kind.AGENT]
     *
     * ChatView's rule (`contains('/') || contains('.')` → file, else agent) can
     * never produce the reference colour, which the spec requires.
     */
    fun classify(token: String): Kind {
        val lastSegment = token.substringAfterLast('/')
        return when {
            lastSegment.contains('.') -> Kind.FILE
            token.contains('/') -> Kind.REFERENCE
            else -> Kind.AGENT
        }
    }

    /** Every mention token in a body, in order (sent-message highlighting). */
    fun spans(text: String): List<Span> = TOKEN_REGEX.findAll(text).map { match ->
        Span(
            start = match.range.first,
            end = match.range.last + 1,
            kind = classify(match.value.substring(1)),
        )
    }.toList()

    /**
     * v15: does [path] name a **directory** rather than a file? `/find/file`
     * answers with bare path strings for both kinds — the v1 shape marks a
     * directory only with a trailing separator, v2 does not mark it at all
     * (`v2/SessionTransport.searchFiles`) — so the `@` menu classifies a row by
     * its shape, the same rule [classify] uses for the mention colours:
     *
     * - a trailing `/` or `\` → directory (v1's explicit marker, and the
     *   `/` root itself),
     * - a last segment of `.` / `..` → directory,
     * - otherwise: an extension in the last segment → file, no extension →
     *   directory (`/home/xiufeigo/v11diff` → directory, `…/a.txt` → file).
     *
     * A directory whose own name carries a dot (`v1.2`) is therefore read as a
     * file, and an extension-less file (`Dockerfile`) as a directory — the same
     * trade-off [classify] makes, and the wire as the composer receives it
     * carries nothing better (v2's `{path, type}` rows drop the `type` in
     * `searchFiles`).
     */
    fun isDirectoryPath(path: String): Boolean {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.endsWith("/") || trimmed.endsWith("\\")) return true
        val last = trimmed.substringAfterLast('/').substringAfterLast('\\')
        if (last.isEmpty() || last == "." || last == "..") return true
        return !last.contains('.')
    }

    /**
     * `/find/file` paths → popover rows: blanks dropped, duplicates removed,
     * capped at [limit]. Candidates are always [Kind.FILE]; the other `@`
     * sources (agents / commands / skills / references / MCP resources) are
     * enumerated by `ComposerSuggestions.mentionItems` from their own catalogs.
     */
    fun candidates(paths: List<String>, limit: Int = LIMIT): List<Candidate> = paths
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(limit)
        .map { Candidate(kind = Kind.FILE, label = it) }
        .toList()
}
