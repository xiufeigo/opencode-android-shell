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
     * Drops tokens whose `@label` no longer occurs in [text] (the user edited or
     * deleted the chip) and keeps the survivors in order. Matching is
     * occurrence-based, so two identical mentions keep two tokens while both
     * labels are present and lose one when one label is deleted.
     */
    fun retokenize(text: String, tokens: List<Token>): List<Token> {
        if (tokens.isEmpty() || text.isEmpty()) return emptyList()
        val remaining = tokens.toMutableList()
        val kept = mutableListOf<Token>()
        // Consume one token per label occurrence, left to right.
        val hits = MentionText.TOKEN_REGEX.findAll(text).map { it.value.substring(1) }.toList()
        for (label in hits) {
            val index = remaining.indexOfFirst { it.label == label }
            if (index >= 0) kept += remaining.removeAt(index)
        }
        return kept
    }

    /**
     * Splits [tokens] into v2 payload fields, in token order:
     * - file token → `file://`-encoded path entry (workspace bytes are read
     *   server-side; `data:` URLs only come from [attachments]);
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
