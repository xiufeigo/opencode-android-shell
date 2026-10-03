package com.xiufeigo.opencodeshell.ui.session.composer

/**
 * WAVE 2.4b (P0-8): structured `@` mention tokens for the composer.
 *
 * Upstream the editor is `contenteditable` with non-editable mention spans
 * (`composer/editor/editor.tsx:351-471` `renderComposerEditor` /
 * `parseComposerEditor`): accepting a suggestion inserts a **typed part**
 * (`schema.ts`: `FileAttachmentPart{path,selection?,mime?,filename?,url?,source?}`,
 * `AgentPart{name}`, `SkillPart{id,name}`) whose `content` is the visible
 * `@label` chip text, and [buildPromptRequest] (`composer/request.ts`) maps
 * those parts to the prompt payload's structured `files[] / agents[] /
 * skills[]` fields — the submission is never just inline text.
 *
 * The native composer is a plain-text `BasicTextField` ([MentionText] inserts
 * `@label ` and forgets the kind), so mention knowledge would be lost before
 * submit. This object is the composer-side record of that knowledge: pure
 * Kotlin, JVM-testable, no Android/Compose imports.
 *
 * Lifecycle (owned by the composer, not the VM):
 * - [accept] — on popover pick, record the [Token] and answer the plain-text
 *   [MentionText.Insertion] the editor needs (chip rendering stays
 *   [MentionColorTransformation], which colours from the text alone).
 * - [retokenize] — after any text edit, drop tokens whose `@label` no longer
 *   occurs in the text (upstream keeps parts only while their span survives;
 *   here the span is the label text) and shift offsets past the edit.
 * - [clear] — after a successful submit, like the editor clearing.
 *
 * Payload mapping (what the VM integrator sends — see
 * `research/v2_audit/wave2.4b-notes.md` §VM):
 * - file token → v2 `files[]` entry `{uri, mime?, name?, mention?}` where `uri`
 *   is `file://…` for a workspace path ([fileUri]) or the `data:` URL from
 *   [AttachmentPolicy] when bytes were attached; v1 → a `{type:file,…}` part
 *   in `prompt_async{parts[]}` (`OpenCodeClient.promptPayload`).
 * - reference token (F-1) → `files[]` entry for `file://reference.path` with
 *   `mime: "application/x-directory"` (`composer/model.ts:156-160`).
 * - resource token (F-1) → `files[]` entry whose `uri` is the MCP resource uri
 *   verbatim (`composer/model.ts:172,180`).
 * - agent token → v2 `agents[]` entry `{name, mention?}`.
 * - skill token → v2 `skills[]` entry `{id, name, mention?}` (v2 commands have
 *   no templates, so a `/name` line stays a command invocation, never a skill).
 */
object MentionTokens {

    /** One accepted `@` mention. [label] is the bare name/path (no `@`). */
    data class Token(
        val kind: ComposerSuggestions.Category,
        /** Bare label as inserted (`@` stripped, trailing space stripped). */
        val label: String,
        /** Agent name (`AgentPart.name`) for [ComposerSuggestions.Category.AGENT]. */
        val agent: String? = null,
        /** Skill id (`SkillPart.id`) for [ComposerSuggestions.Category.SKILL]. */
        val skillId: String? = null,
        /** Workspace-relative file path for [ComposerSuggestions.Category.FILE]. */
        val path: String? = null,
        /**
         * F-1: the reference's `path` (`model.ts:156`) or the MCP resource's
         * `uri` (`model.ts:172`) — the target the prompt's `files[]` entry
         * carries, since neither label is the target itself.
         */
        val target: String? = null,
        /** F-1: the MCP server of a resource mention (`source.clientName`). */
        val server: String? = null,
    )

    /**
     * Records the accepted popover [item] and answers the editor insertion.
     * [anchorStart]/[caret] are the [MentionText.anchorAt]/caret positions the
     * caller already holds; [tokens] is the composer's current token list.
     *
     * COMMAND items are not mentions (upstream `findCommand` treats `/name` as
     * a server command invocation, never a `skills[]`/`agents[]` entry): they
     * still insert their plain-text label but record no token.
     */
    fun accept(
        item: ComposerSuggestions.Item,
        text: String,
        anchorStart: Int,
        caret: Int,
        tokens: List<Token>,
    ): Pair<MentionText.Insertion, List<Token>>? {
        val insertion = MentionText.insert(text, anchorStart, caret, item.label) ?: return null
        val token = tokenFor(item) ?: return insertion to tokens
        // One label occurrence = one token: re-accepting the same row after the
        // user typed it twice records both chips.
        return insertion to (tokens + token)
    }

    /** Builds the [Token] for a popover item, or null when it is not a mention. */
    fun tokenFor(item: ComposerSuggestions.Item): Token? = when (item.category) {
        ComposerSuggestions.Category.REFERENCE -> Token(
            kind = ComposerSuggestions.Category.REFERENCE,
            label = item.label,
            // `model.ts:156-160`: the target is `reference.path` while the
            // visible/filename half is `reference.name`.
            target = item.path ?: item.label,
        )
        ComposerSuggestions.Category.RESOURCE -> Token(
            kind = ComposerSuggestions.Category.RESOURCE,
            label = item.label,
            // `model.ts:172-188`: the uri IS the prompt target.
            target = item.path ?: item.label,
            server = item.server,
        )
        ComposerSuggestions.Category.AGENT -> Token(
            kind = ComposerSuggestions.Category.AGENT,
            label = item.label,
            agent = item.label,
        )
        ComposerSuggestions.Category.SKILL -> Token(
            kind = ComposerSuggestions.Category.SKILL,
            label = item.label,
            // The server identifies skills by name too (`SkillPart{id,name}`
            // carry the same value from `GET /skill`); the id slot stays null
            // until the catalog exposes distinct ids.
            skillId = null,
        )
        ComposerSuggestions.Category.FILE -> Token(
            kind = ComposerSuggestions.Category.FILE,
            label = item.label,
            path = item.label,
        )
        ComposerSuggestions.Category.COMMAND -> null
    }

    /**
     * F-1: the mention colours the editor must override for the accepted tokens.
     *
     * [MentionText.classify] reads the *text* only, so a reference whose name has
     * no slash (`@docs`) would colour as an agent while upstream colours it
     * `reference` (`editor.tsx:191` `[data-mention=reference]`, set from the
     * part's `mime: "application/x-directory"`, `editor.tsx:366`). The token
     * knows the kind, so it wins over the heuristic.
     *
     * A resource colours as a **file** mention — upstream's rule is
     * `part.type === "file" && mime === "application/x-directory" ? "reference"
     * : part.type`, and a resource's mime is `text/plain` unless the server says
     * otherwise (`model.ts:181`, `editor.tsx:366`).
     */
    fun colors(tokens: List<Token>): Map<String, MentionText.Kind> {
        val map = LinkedHashMap<String, MentionText.Kind>()
        for (token in tokens) {
            if (token.label.isEmpty()) continue
            val kind = when (token.kind) {
                ComposerSuggestions.Category.REFERENCE -> MentionText.Kind.REFERENCE
                ComposerSuggestions.Category.RESOURCE -> MentionText.Kind.FILE
                else -> continue
            }
            map[token.label] = kind
        }
        return map
    }

    /**
     * Drops tokens whose `@label` no longer occurs in [text] (the user edited or
     * deleted the chip) and keeps the survivors in order. Matching is
     * occurrence-based, so two identical mentions keep two tokens while both
     * labels are present and lose one when one label is deleted.
     *
     * Matching is by **literal** `@label`, not `TOKEN_REGEX`: the regex cannot
     * see labels with spaces or CJK, and re-parsing the text with it used to
     * find zero hits and drop *every* recorded token — the just-picked 中文
     * file silently left `files[]/skills[]`. At each `@` the longest remaining
     * label wins, and the char after it may not continue a bare token, so
     * `@building` still kills a `build` chip while `@build,` counts as it.
     */
    fun retokenize(text: String, tokens: List<Token>): List<Token> {
        if (tokens.isEmpty() || text.isEmpty()) return emptyList()
        val remaining = tokens.toMutableList()
        val kept = mutableListOf<Token>()
        var at = text.indexOf(MentionText.TRIGGER)
        while (at >= 0) {
            val match = remaining
                .filter { token ->
                    token.label.isNotEmpty() &&
                        text.startsWith(token.label, at + 1) &&
                        labelClosed(text, at + 1 + token.label.length)
                }
                .maxByOrNull { it.label.length }
            if (match != null) {
                kept += remaining.removeAt(remaining.indexOf(match))
                at = text.indexOf(MentionText.TRIGGER, at + 1 + match.label.length)
            } else {
                at = text.indexOf(MentionText.TRIGGER, at + 1)
            }
        }
        return kept
    }

    /** The old `TOKEN_REGEX` tail class: a bare token does not continue into it. */
    private fun labelClosed(text: String, end: Int): Boolean =
        end >= text.length || text[end].let { c ->
            c !in 'A'..'Z' && c !in 'a'..'z' && c !in '0'..'9' && c != '_' && c != '.' && c != '-' && c != '/'
        }

    /**
     * v68: one accepted mention as it occurs in [text] — the `@` plus its
     * literal [Token.label] at [[start], [endExclusive]).
     */
    data class TokenSpan(val start: Int, val endExclusive: Int, val token: Token)

    /**
     * v68: a backspace that must delete a whole chip at once,
     * [[start], [endExclusive]).
     */
    data class Deletion(val start: Int, val endExclusive: Int)

    /**
     * v68: locates the accepted tokens in [text], in order.
     *
     * The matching is [retokenize]'s own — occurrence-based, literal
     * `@label`, longest label first — so a span exists exactly where the token
     * survives, and a damaged label (`@gener` peeled off `@general`) yields no
     * span instead of a half one.
     */
    fun spans(text: String, tokens: List<Token>): List<TokenSpan> {
        if (tokens.isEmpty() || text.isEmpty()) return emptyList()
        val remaining = tokens.toMutableList()
        val out = mutableListOf<TokenSpan>()
        var at = text.indexOf(MentionText.TRIGGER)
        while (at >= 0) {
            val match = remaining
                .filter { token ->
                    token.label.isNotEmpty() &&
                        text.startsWith(token.label, at + 1) &&
                        labelClosed(text, at + 1 + token.label.length)
                }
                .maxByOrNull { it.label.length }
            if (match != null) {
                remaining.removeAt(remaining.indexOf(match))
                out += TokenSpan(at, at + 1 + match.label.length, match)
                at = text.indexOf(MentionText.TRIGGER, at + 1 + match.label.length)
            } else {
                at = text.indexOf(MentionText.TRIGGER, at + 1)
            }
        }
        return out
    }

    /** The chip's own separator: [accept] inserts `@label ` with one trailing space. */
    private fun chipEnd(text: String, end: Int): Int =
        if (text.getOrNull(end) == ' ') end + 1 else end

    /**
     * v68: the atomic backspace for a hardware key event.
     *
     * Upstream the editor is `contenteditable` with non-editable mention spans,
     * so one backspace removes the whole chip; here the chip is plain text and
     * backspace used to peel it char by char (`@general` took six presses on
     * 5554/5556/5560, each intermediate `@gener` an illegal mention that kept
     * its anchor alive and re-armed the popover). A collapsed caret at a token
     * boundary — inside the span, at its end, or just past its separator space —
     * therefore deletes the whole `@label` plus that one separator space in a
     * single step. Anything else (a range selection, a caret outside every
     * chip) answers null and the field handles the key normally.
     *
     * The `@` goes with the span, so the deletion can never leave a live
     * anchor behind: the panel closes instead of reopening mid-delete.
     */
    fun backspace(text: String, caret: Int, tokens: List<Token>): Deletion? {
        if (tokens.isEmpty()) return null
        if (caret <= 0 || caret > text.length) return null
        for (span in spans(text, tokens)) {
            if (caret > span.start && caret <= span.endExclusive) {
                return Deletion(span.start, chipEnd(text, span.endExclusive))
            }
            if (caret == span.endExclusive + 1 && text.getOrNull(span.endExclusive) == ' ') {
                return Deletion(span.start, span.endExclusive + 1)
            }
        }
        return null
    }

    /**
     * v68: the same atomicity for a soft-keyboard backspace, which never
     * reaches `onPreviewKeyEvent` — it arrives in `onValueChange` with the one
     * char already removed by the IME.
     *
     * When the change from [oldText] to [newText] is exactly one deleted char,
     * the IME parked the caret on the deletion point, and that point falls
     * inside a token span — or is the chip's own separator space right after
     * it — the deletion widens to the whole chip, exactly like [backspace].
     * Anything else (insertions, multi-char edits, a caret the IME put
     * elsewhere, plain-text deletes) answers null and the value stands as the
     * IME committed it. The widened deletion is still applied through
     * `ComposerImeCommit.adopt` by the caller, so the caret it carries stays
     * legal and no restart can park it at 0.
     */
    fun snapImeDeletion(
        oldText: String,
        newText: String,
        newCaret: Int,
        tokens: List<Token>,
    ): Deletion? {
        if (tokens.isEmpty()) return null
        if (oldText.length != newText.length + 1) return null
        val cut = oldText.commonPrefixWith(newText).length
        if (cut < 0 || cut >= oldText.length) return null
        if (newCaret != cut) return null
        if (oldText.substring(cut + 1) != newText.substring(cut)) return null
        for (span in spans(oldText, tokens)) {
            if (cut >= span.start && cut < span.endExclusive) {
                return Deletion(span.start, chipEnd(oldText, span.endExclusive))
            }
            if (cut == span.endExclusive && oldText.getOrNull(cut) == ' ') {
                return Deletion(span.start, cut + 1)
            }
        }
        return null
    }

    /**
     * Splits [tokens] into v2 payload fields, in token order:
     * - file token → `file://`-encoded path entry (workspace bytes are read
     *   server-side; `data:` URLs only come from [attachments]);
     * - reference token → `file://`-encoded `reference.path` with upstream's
     *   `application/x-directory` mime (`model.ts:156-160`);
     * - resource token → the resource **uri verbatim** (`model.ts:172,180`:
     *   `path: resource.uri`, `url: resource.uri`);
     * - agent token → `{name}`;
     * - skill token → `{id,name}` (id falls back to the name, mirroring
     *   `tokenFor` until the catalog carries distinct ids).
     */
    data class Structured(
        val files: List<FileRef> = emptyList(),
        val agents: List<String> = emptyList(),
        val skills: List<SkillRef> = emptyList(),
    )

    data class FileRef(val uri: String, val mime: String?, val name: String?)
    data class SkillRef(val id: String, val name: String)

    fun structure(tokens: List<Token>, sessionDirectory: String? = null): Structured {
        val files = mutableListOf<FileRef>()
        val agents = mutableListOf<String>()
        val skills = mutableListOf<SkillRef>()
        for (token in tokens) {
            when (token.kind) {
                ComposerSuggestions.Category.FILE -> {
                    val path = token.path ?: token.label
                    files += FileRef(
                        uri = fileUri(path, sessionDirectory),
                        mime = null,
                        name = path.substringAfterLast('/').substringAfterLast('\\')
                            .takeIf { it.isNotEmpty() },
                    )
                }
                ComposerSuggestions.Category.REFERENCE -> files += FileRef(
                    uri = fileUri(token.target ?: token.label, sessionDirectory),
                    mime = MentionSources.DIRECTORY_MIME,
                    name = token.label,
                )
                ComposerSuggestions.Category.RESOURCE -> files += FileRef(
                    // The uri is used as-is: an MCP resource is not a workspace
                    // path, and `fileUri()` would percent-encode its scheme.
                    uri = token.target ?: token.label,
                    mime = MentionSources.DEFAULT_RESOURCE_MIME,
                    name = token.label,
                )
                ComposerSuggestions.Category.AGENT ->
                    token.agent?.takeIf { it.isNotBlank() }?.let { agents += it }
                ComposerSuggestions.Category.SKILL -> skills += SkillRef(
                    id = token.skillId?.takeIf { it.isNotBlank() } ?: token.label,
                    name = token.label,
                )
                ComposerSuggestions.Category.COMMAND -> Unit
            }
        }
        return Structured(files, agents, skills)
    }

    /**
     * `request.ts:42-47` `absolute()` + `file://` + `encodeFilePath`
     * (`workspaces/files/path.ts:83-96`): absolute paths pass through,
     * relative ones resolve against the session directory, backslashes fold
     * to slashes, and each segment is percent-encoded with `/` and the drive
     * colon preserved.
     */
    fun fileUri(path: String, sessionDirectory: String? = null): String {
        val absolute = absolutize(path.trim(), sessionDirectory?.trim().orEmpty())
        return "file://" + encodePath(absolute)
    }

    private fun absolutize(path: String, directory: String): String {
        if (path.startsWith("/")) return path
        // `encodeFilePath` folds backslashes FIRST, so `C:\proj\a.kt` already
        // arrives here as `C:/proj/a.kt` — match the drive on the folded shape.
        val folded = path.replace('\\', '/')
        if (WINDOWS_DRIVE_PREFIX.matchAt(folded, 0) != null) return folded
        if (path.startsWith("\\\\") || path.startsWith("//")) return path
        if (directory.isEmpty()) return path
        return directory.trimEnd('/', '\\') + "/" + path
    }

    private val WINDOWS_DRIVE_PREFIX = Regex("^[A-Za-z]:")

    private fun encodePath(path: String): String {
        // `encodeFilePath`: backslashes fold first, then a drive letter gains
        // the leading slash a `file://` URL needs (`C:/…` → `/C:/…`).
        var normalized = path.replace('\\', '/')
        if (WINDOWS_DRIVE_PREFIX.matchAt(normalized, 0) != null) normalized = "/$normalized"
        return normalized.split('/').joinToString("/") { segment ->
            // The drive token (`C:`) keeps its colon; everything else encodes.
            if (segment.matches(Regex("^[A-Za-z]:"))) segment
            else segment.toByteArray(Charsets.UTF_8).joinToString("") { byte ->
                val c = byte.toInt() and 0xFF
                if (c in UNRESERVED) c.toChar().toString() else "%" + c.toString(16).uppercase().padStart(2, '0')
            }
        }
    }

    private val UNRESERVED: Set<Int> =
        (('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('-', '_', '.', '~', ':', '@', '!', '$', '&', '\'', '(', ')', '*', '+', ',', ';', '=')).map { it.code }.toSet()
}
