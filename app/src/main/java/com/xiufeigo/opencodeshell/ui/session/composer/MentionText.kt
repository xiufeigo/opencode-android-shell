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
     * `ChatView.MENTION_REGEX`: `@` + an identifier start, then path-ish chars
     * (`/.-`). Used to colour mentions in already-sent messages
     * (`@file` → syntax-property, `@agent` → syntax-type, `@reference` →
     * syntax-keyword — `research/02` §4.2/§4.7).
     */
    val TOKEN_REGEX = Regex("@[A-Za-z0-9_][A-Za-z0-9_.\\-/]*")

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
     * `/find/file` paths → popover rows: blanks dropped, duplicates removed,
     * capped at [limit]. Candidates are always [Kind.FILE] (the only kind the
     * data layer can enumerate today; agent/reference/resource/command sources
     * land with M2/M6).
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
