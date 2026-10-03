package com.xiufeigo.opencodeshell.ui.session.review

import com.xiufeigo.opencodeshell.v2.optStringOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Line comments on the review diff (F-2), the Android port of the upstream
 * comment model:
 * - `_upstream/packages/app/src/composer/schema.ts:189-195` (`LineComment`:
 *   `id`/`file`/`selection: SelectedLineRange {start,end}`/`comment`/`time`);
 * - `_upstream/packages/app/src/composer/comments.tsx:35-87,100-148`
 *   (`add` stamps `uuid()` + `Date.now()`, `list(file)` keeps insertion order,
 *   `all()` aggregates every file sorted by `time`, `update`/`remove`/
 *   `replace`/`clear`).
 *
 * Upstream keeps one extra axis we deliberately drop: `selection.side` /
 * `endSide` (`"additions" | "deletions"`, `files/types.ts:13-18`) anchors a
 * comment to a Pierre split-view side. Our diff rows already resolve the side
 * into a concrete line number ([quoteDiffLine] prefers the new-side number),
 * so [startLine]/[endLine] are plain 1-based line numbers and a comment is a
 * single-line anchor today; [ReviewCommentPrompt.formatNote] still accepts a
 * range so multi-line anchors stay representable.
 *
 * Sending mirrors `session/review/model.ts:206-230` (`addComment` /
 * `updateComment` / `removeComment` keep the composer `prompt.context` file
 * item and the comment store in sync) and `composer/request.ts:88-133`
 * (`buildPromptRequest` joins `[text, ...attachments, ...comments.map(
 * formatCommentNote)]` with `"\n"`). The Android composer has no structured
 * `prompt.context` items, so the equivalent path is textual: [ReviewCommentPrompt]
 * renders the same note sentences and the host appends them to the composer
 * draft via `ChatViewModel.appendComposerText`.
 *
 * F-4 completes the upstream pair: at submit time `ChatViewModel.submit` splits
 * those note lines back out of the draft ([ReviewCommentMetadata.presentation])
 * and sends them as `metadata.comments` **beside** the model-visible text
 * (`submit.ts:169-177`), and the timeline renders the rows as comment cards
 * ([com.xiufeigo.opencodeshell.ui.session.timeline.CommentCard]).
 */

/** One anchored comment, upstream `LineComment` (`composer/schema.ts:189`). */
data class ReviewLineComment(
    val id: String,
    val file: String,
    val startLine: Int,
    val endLine: Int,
    val comment: String,
    val timeMs: Long,
    /** The diff line body the comment was written on (display only). */
    val preview: String? = null,
)

/** [ReviewCommentStore.add] input: everything but the stamped `id`/`timeMs`. */
data class NewReviewComment(
    val file: String,
    val startLine: Int,
    val endLine: Int,
    val comment: String,
    val preview: String? = null,
)

/**
 * In-memory comment store, upstream `createCommentSessionState`
 * (`composer/comments.tsx:73-165`). The [comments] flow (chronological, like
 * `all()`) drives the Compose UI; the query helpers stay synchronous so they
 * are directly JVM-testable.
 */
class ReviewCommentStore {

    private val _comments = MutableStateFlow<List<ReviewLineComment>>(emptyList())

    /** Every comment, oldest first (upstream `aggregate`, `comments.tsx:35-41`). */
    val comments: StateFlow<List<ReviewLineComment>> = _comments.asStateFlow()

    /**
     * Adds a comment, upstream `add` (`comments.tsx:102-116`): the id and the
     * timestamp are stamped by the store. Both default to generated values but
     * stay injectable so tests are deterministic.
     */
    fun add(
        input: NewReviewComment,
        id: String = UUID.randomUUID().toString(),
        timeMs: Long = System.currentTimeMillis(),
    ): ReviewLineComment {
        val next = ReviewLineComment(
            id = id,
            file = input.file,
            startLine = input.startLine,
            endLine = input.endLine,
            comment = input.comment,
            timeMs = timeMs,
            preview = input.preview,
        )
        _comments.update { it + next }
        return next
    }

    /** One file's comments in insertion order (upstream `list`, `:100`). */
    fun list(file: String): List<ReviewLineComment> = _comments.value.filter { it.file == file }

    /** Every comment, oldest first (upstream `all()`, `:82-87`). */
    fun all(): List<ReviewLineComment> = _comments.value.sortedBy { it.timeMs }

    /** Replaces one comment's text, upstream `update` (`:125-132`). */
    fun update(file: String, id: String, comment: String) {
        _comments.update { list ->
            list.map { if (it.file == file && it.id == id) it.copy(comment = comment) else it }
        }
    }

    /** Drops one comment, upstream `remove` (`:118-123`). */
    fun remove(file: String, id: String) {
        _comments.update { list -> list.filterNot { it.file == file && it.id == id } }
    }

    /** Replaces the whole store (history restore, upstream `replace`, `:134-140`). */
    fun replace(comments: List<ReviewLineComment>) {
        _comments.value = comments.toList()
    }

    /** Drops everything (upstream `clear`, `:142-148`). */
    fun clear() {
        _comments.value = emptyList()
    }
}

/**
 * Prompt-text rendering of line comments.
 *
 * [formatNote] is a verbatim port of upstream `formatCommentNote`
 * (`composer/comment-note.ts:103-113`): the range reads `line X`, `lines X
 * through Y` (min/max, so a reversed anchor still reads correctly) or `this
 * file`, and the sentence is `The user made the following comment regarding
 * <range> of <path>: <comment>`. The English sentence is pinned intentionally:
 * `parseCommentNote` (`comment-note.ts:115-135`) round-trips it, so any
 * rewording would fork the wire/display contract.
 *
 * [buildPrompt] mirrors the text assembly of `buildPromptRequest`
 * (`composer/request.ts:124-133`): the user text first (kept verbatim when
 * non-blank), then one note per comment. Blank comments never reach the wire
 * upstream (`request.ts:96-99` only pushes a `PromptComment` for a trimmed
 * non-empty comment), so they are filtered here too.
 */
object ReviewCommentPrompt {

    fun formatNote(path: String, startLine: Int?, endLine: Int?, comment: String): String {
        val start = if (startLine != null && endLine != null) minOf(startLine, endLine) else null
        val end = if (startLine != null && endLine != null) maxOf(startLine, endLine) else null
        val range = when {
            start == null || end == null -> "this file"
            start == end -> "line $start"
            else -> "lines $start through $end"
        }
        return "The user made the following comment regarding $range of $path: $comment"
    }

    fun buildPrompt(baseText: String, comments: List<ReviewLineComment>): String =
        ((if (baseText.trim().isEmpty()) emptyList() else listOf(baseText)) +
            comments.filter { it.comment.isNotBlank() }
                .map { formatNote(it.file, it.startLine, it.endLine, it.comment) }).joinToString("\n")
}

/**
 * F-4: one line comment as the timeline renders it — the note sentence
 * `parseCommentNote` recovered (`comment-note.ts:115-135`), or a row of the
 * prompt's `metadata.comments[]` (`submit.ts:169-177`).
 *
 * [startLine]/[endLine] are the anchor the card prints (`comment-card.tsx:47-53`
 * `:startLine` / `:startLine-endLine`); both null renders no line suffix
 * ("this file").
 */
data class ReviewNote(
    val path: String,
    val startLine: Int? = null,
    val endLine: Int? = null,
    val comment: String = "",
) {
    /** `comment-card.tsx:47-53`: `:12` for one line, `:12-18` for a range, "" for none. */
    val lineSuffix: String
        get() {
            val start = startLine ?: return ""
            val end = endLine ?: start
            return if (start == end) ":$start" else ":$start-$end"
        }
}

/**
 * F-4: the two wire/display halves of the line-comment flow.
 *
 * **Out** — the prompt's structured comments. Upstream's submit path sends both
 * shapes at once (`composer/submit.ts:169-177` + `request.ts:124-133`): the note
 * sentence inside the model-visible `text`, and the structured array in
 * `metadata.comments`:
 *
 * ```json
 * {"displayText":"…", "comments":[{"path":"a.kt",
 *   "selection":{"startLine":3,"startChar":0,"endLine":3,"endChar":0},
 *   "comment":"…","preview":"…","origin":"review"}]}
 * ```
 *
 * `readPromptPresentation` (`comment-note.ts:63-99`) is the reader the timeline
 * uses, and `parseCommentNote` is its text-only fallback for a message whose
 * metadata never made it — which is the half this shell renders, because a v2
 * user row arrives through the message snapshot without its metadata
 * (`V2Transport.toUi` maps `{id,type,time,text}` only).
 *
 * **In** — [presentation] splits a message (or the composer's outgoing draft)
 * back into the user's own words and the comment rows, line by line.
 *
 * Deliberate divergence from `parseCommentNote`: upstream matches the regex
 * against the **whole** text with a `([\s\S]+)` tail, so the first note swallows
 * every following line. The Android composer appends one note per line
 * ([ReviewCommentPrompt.formatNote] + `buildPrompt`'s `"\n"` join), so the
 * parser here is line-scoped — the same result for this shell's own output and
 * never a swallowed second comment.
 */
object ReviewCommentMetadata {

    /** Upstream `commentOrigin` for a review-panel comment (`composer/schema.ts:203`). */
    const val ORIGIN_REVIEW = "review"

    /** `comment-note.ts:115-118`, line-scoped (see the class note). */
    private val NOTE = Regex(
        "^The user made the following comment regarding " +
            "(this file|line (\\d+)|lines (\\d+) through (\\d+)) of (.+?): ([\\s\\S]+)$",
    )

    /** One note's `metadata.comments[]` entry (`comment-note.ts:63-99`'s reader shape). */
    fun noteJson(note: ReviewNote): JSONObject {
        val json = JSONObject()
            .put("path", note.path)
            .put("comment", note.comment)
        val start = note.startLine
        val end = note.endLine ?: start
        if (start != null && end != null) json.put("selection", selectionJson(start, end))
        json.put("origin", ORIGIN_REVIEW)
        return json
    }

    /**
     * `FileSelection` (`workspaces/files/model`): the shell anchors on whole
     * lines, so both character offsets are `0` — the same shape
     * `parseCommentNote` synthesises for a parsed note.
     */
    fun selectionJson(startLine: Int, endLine: Int): JSONObject = JSONObject()
        .put("startLine", minOf(startLine, endLine))
        .put("startChar", 0)
        .put("endLine", maxOf(startLine, endLine))
        .put("endChar", 0)

    /** The whole `metadata.comments` array (text order, blank comments dropped). */
    fun notesJson(notes: List<ReviewNote>): JSONArray = JSONArray().apply {
        for (note in notes) {
            if (note.comment.isBlank()) continue
            put(noteJson(note))
        }
    }

    /** A review-store comment as the note the card/wire shape needs. */
    fun note(comment: ReviewLineComment): ReviewNote = ReviewNote(
        path = comment.file,
        startLine = comment.startLine,
        endLine = comment.endLine,
        comment = comment.comment,
    )

    /**
     * One `metadata.comments[]` entry → a note, or null when it carries no
     * path/comment (`readPromptPresentation` drops exactly those,
     * `comment-note.ts:85-99`). Every string is read null-safely: a JSON `null`
     * must never arrive as the literal `"null"`.
     */
    fun noteFromJson(obj: JSONObject?): ReviewNote? {
        if (obj == null) return null
        val path = optStringOrNull(obj, "path") ?: return null
        val comment = optStringOrNull(obj, "comment") ?: return null
        val selection = obj.optJSONObject("selection")
        val start = selection?.takeIf { !it.isNull("startLine") }?.optInt("startLine")
        val end = selection?.takeIf { !it.isNull("endLine") }?.optInt("endLine")
        return ReviewNote(path = path, startLine = start, endLine = end ?: start, comment = comment)
    }

    /** The whole `metadata.comments` array; entries without a path/comment are dropped. */
    fun notesFromJson(array: JSONArray?): List<ReviewNote> {
        if (array == null) return emptyList()
        val notes = ArrayList<ReviewNote>(array.length())
        for (index in 0 until array.length()) {
            notes += noteFromJson(array.optJSONObject(index)) ?: continue
        }
        return notes
    }

    /**
     * One message text → the user's own words plus the comment rows.
     *
     * [displayText] keeps every non-note line (blank lines included, so the
     * user's own paragraphing survives); [notes] is in text order.
     */
    data class Presentation(val displayText: String, val notes: List<ReviewNote>) {
        val hasNotes: Boolean get() = notes.isNotEmpty()
    }

    fun presentation(text: String): Presentation {
        if (!text.contains(NOTE_PREFIX)) return Presentation(text, emptyList())
        val notes = ArrayList<ReviewNote>()
        val kept = ArrayList<String>()
        for (line in text.replace("\r\n", "\n").split('\n')) {
            val note = parseNote(line)
            if (note == null) kept += line else notes += note
        }
        return Presentation(kept.joinToString("\n"), notes)
    }

    /**
     * The prompt's whole `metadata` object: `displayText` is always present (the
     * composer sends it today) and `comments` only when the draft carried notes
     * — upstream writes the same two keys beside `agent`/`model`
     * (`submit.ts:169-186`); the agent/model half travels through
     * `switchAgent`/`switchModel` on this shell, not through metadata.
     */
    fun metadata(text: String): JSONObject {
        val presentation = presentation(text)
        val json = JSONObject().put("displayText", presentation.displayText)
        if (presentation.hasNotes) json.put("comments", notesJson(presentation.notes))
        return json
    }

    /** One line → a comment, or null when it is not a note sentence. */
    fun parseNote(line: String): ReviewNote? {
        val match = NOTE.matchEntire(line.trim()) ?: return null
        val start = match.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull()
            ?: match.groupValues[3].takeIf { it.isNotEmpty() }?.toIntOrNull()
        val end = match.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull()
            ?: match.groupValues[4].takeIf { it.isNotEmpty() }?.toIntOrNull()
        return ReviewNote(
            path = match.groupValues[5],
            startLine = start,
            endLine = end ?: start,
            comment = match.groupValues[6],
        )
    }

    /** Cheap gate before the per-line regex walk (`formatNote`'s fixed prefix). */
    private const val NOTE_PREFIX = "The user made the following comment regarding "
}

/**
 * Tap-to-quote (the F-2 S-step: tapping a diff line appends a reference to the
 * composer via `ChatViewModel.appendComposerText`). Upstream has no tap
 * action on diff lines — the web client anchors comments with a gutter
 * affordance instead — so this compact `` `file:line`: content `` reference
 * is an Android-local convention; only [ReviewCommentPrompt] claims upstream
 * parity.
 *
 * The quoted number prefers the new side (added/context lines live on it),
 * falling back to the old side for pure deletions — the same number the
 * gutter shows. Returns null when neither side has a number (headers, hunks,
 * meta), which the caller treats as "not quotable".
 */
object ReviewLineQuote {

    const val MAX_QUOTE_CHARS = 240

    fun quoteLine(
        file: String,
        oldLine: Int?,
        newLine: Int?,
        content: String,
        maxChars: Int = MAX_QUOTE_CHARS,
    ): String? {
        val line = newLine ?: oldLine ?: return null
        val body = content.trim().take(maxChars)
        return if (body.isEmpty()) "`$file:$line`" else "`$file:$line`: $body"
    }
}
