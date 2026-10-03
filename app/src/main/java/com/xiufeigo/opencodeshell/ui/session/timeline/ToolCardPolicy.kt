package com.xiufeigo.opencodeshell.ui.session.timeline

import com.xiufeigo.opencodeshell.ChatStore

/**
 * Pure (JVM-testable) policy for the tool card's collapse/expand behaviour —
 * the port of upstream `packages/session-ui/src/components/part-default-open.ts`
 * plus the chevron visibility rule of `basic-tool.tsx`
 * (`research/02` §3.1 / §3.2 / §3.2b).
 *
 * Three questions are answered here, and nowhere else:
 * 1. **May this card be expanded at all?** — `pending`/`running` are locked
 *    (`handleOpenChange` returns early), with `bash`/`shell` as the documented
 *    exception (`allowOpenWhilePending`).
 * 2. **What is the default when the user has not touched it?** —
 *    `partDefaultOpen(part, shell, edit)`.
 * 3. **What wins?** — an explicit user toggle always wins over the default,
 *    which is what lets the screen hold one `Map<partId, Boolean>` (`toolOpen`)
 *    exactly like the upstream timeline store.
 *
 * Deliberate deviation, documented in `research/_M2_session_status.md`:
 * `deletionOnly()` is decided from the **retained tool input/diff** rather than
 * `part.metadata.files` / `metadata.filediff`, because `ChatStore.UiPart`
 * keeps only `state.input`, `state.output` and `state.error`. The observable
 * result (a pure deletion opens collapsed) is identical.
 */
object ToolCardPolicy {

    /** Lifecycle collapsed to the values the card renders. */
    enum class State { PENDING, RUNNING, COMPLETED, ERROR }

    /** `settings.general.row.shellToolPartsExpanded` default — M4 wires the switch. */
    const val DEFAULT_SHELL_EXPANDED = false

    /**
     * `settings.general.row.editToolPartsExpanded` default.
     *
     * W4-B: **false**, matching the upstream default preset. Upstream ships
     * `compact` (`_upstream/packages/app/src/settings/model.tsx:248` →
     * `session-ui/src/timeline/detail.ts:41-49`) where `edit` is
     * `grouped + collapsed`, so an edit body never starts expanded. The M2
     * default of `true` predates the preset port.
     */
    const val DEFAULT_EDIT_EXPANDED = false

    fun stateOf(part: ChatStore.UiPart): State = when (part.toolStatus) {
        "pending" -> State.PENDING
        "running" -> State.RUNNING
        "error" -> State.ERROR
        else -> State.COMPLETED
    }

    /** `pending() || running()` upstream — one branch, two statuses. */
    fun isPendingOrRunning(part: ChatStore.UiPart): Boolean = when (stateOf(part)) {
        State.PENDING, State.RUNNING -> true
        State.COMPLETED, State.ERROR -> false
    }

    fun isShell(toolName: String?): Boolean = toolName == "bash" || toolName == "shell"

    /** True for the tools whose expanded body is a diff (`ToolPresentation.diffLines`). */
    fun isEditLike(toolName: String?): Boolean = ToolPresentation.isEditLike(toolName)

    /**
     * `deletionOnly(part)`: an edit-like part whose body has deletions and no
     * addition. A body-less part is **not** deletion-only (upstream returns
     * `undefined` too, which the caller treats as "no default expansion").
     *
     * [lines] carries a precomputed [ToolPresentation.bodyLines] so a card that
     * already remembered its body does not re-parse the JSON and re-run Myers
     * for every policy question in the same recomposition.
     */
    fun deletionOnly(
        part: ChatStore.UiPart,
        lines: List<ToolPresentation.BodyLine>? = null,
    ): Boolean {
        if (!isEditLike(part.toolName)) return false
        val body = lines ?: ToolPresentation.bodyLines(part)
        if (body.isEmpty()) return false
        return body.none { it.kind == ToolPresentation.LineKind.ADD } &&
            body.any { it.kind == ToolPresentation.LineKind.DELETE }
    }

    /**
     * `part-default-open.ts`, transcribed:
     * ```
     * if (tool == bash|shell)   return shell              // settings.shellToolPartsExpanded
     * if (tool == edit|write|patch|apply_patch)
     *                           return edit && !deletionOnly(part)
     * return undefined                                    // everything else folds
     * ```
     */
    fun defaultOpen(
        part: ChatStore.UiPart,
        shellExpanded: Boolean = DEFAULT_SHELL_EXPANDED,
        editExpanded: Boolean = DEFAULT_EDIT_EXPANDED,
        lines: List<ToolPresentation.BodyLine>? = null,
    ): Boolean {
        if (isShell(part.toolName)) return shellExpanded
        if (isEditLike(part.toolName)) return editExpanded && !deletionOnly(part, lines)
        return false
    }

    /**
     * `hasChildren() && !hideDetails && !locked && (!pending() || allowOpenWhilePending)`
     * (`research/02` §3.1). `hasChildren` is approximated by "the expanded body
     * would render something" — a part with no input, no output and no error has
     * no body, so it gets no chevron (the upstream `hideDetails` gate).
     */
    fun canExpand(part: ChatStore.UiPart, lines: List<ToolPresentation.BodyLine>? = null): Boolean {
        if ((lines ?: ToolPresentation.bodyLines(part)).isEmpty()) return false
        if (!isPendingOrRunning(part)) return true
        return isShell(part.toolName)
    }

    /**
     * Resolved open state for the screen-level `toolOpen` map:
     * an explicit toggle wins, otherwise the default — and a locked card always
     * collapses (a card that was expanded when its run started must not stay
     * expanded once it flips back to `running`, e.g. after an edit completes and
     * the run continues).
     */
    fun resolveOpen(
        part: ChatStore.UiPart,
        userOpen: Boolean?,
        shellExpanded: Boolean = DEFAULT_SHELL_EXPANDED,
        editExpanded: Boolean = DEFAULT_EDIT_EXPANDED,
        lines: List<ToolPresentation.BodyLine>? = null,
    ): Boolean {
        if (!canExpand(part, lines)) return false
        return userOpen ?: defaultOpen(part, shellExpanded, editExpanded, lines)
    }
}
