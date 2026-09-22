package com.xiufeigo.opencodeshell.ui.session.composer

/**
 * W5-G: the suggestion row's **text** policy, split out of [MentionPopover] so the
 * one rule the tablet broke is assertable on the JVM.
 *
 * The measured bug (`research/wave5_shots/v6-slash-tablet.png`, `/` over 75
 * `GET /command` rows): the `customize-opencode` and `jira-SCB-Collector` rows drew
 * **no `/name` label at all** — only the category icon and the description. Those
 * are exactly the two commands whose server description is long enough to fill the
 * row, and the label used to be the row's `weight(1f)` child: Compose's `Row`
 * measures the weighted children *last*, so the label was handed whatever the
 * (unweighted, measured-first) description had left — zero.
 *
 * The layout half of the fix lives in `MentionPopover.SuggestionRow` and mirrors
 * upstream (`composer/editor/editor.tsx:799-816`): the label is measured at its
 * natural width — `shrink-0` on upstream's `<bdi>` — and the description is the
 * row's only weighted child, `min-w-0 truncate`, so it can only ever take the
 * remainder. This object owns the strings those two `Text`s are handed:
 *
 * - [label] is **never blank** (trim → the row's category → [UNKNOWN_LABEL]), so no
 *   call path can give the row an empty label to measure at zero width;
 * - [description] drops blank / whitespace-only server text to null;
 * - both are bounded by a generous character budget ([LABEL_MAX_CHARS] /
 *   [DESCRIPTION_MAX_CHARS]). The *visible* truncation stays with the layout
 *   (`maxLines = 1` + [androidx.compose.ui.text.style.TextOverflow.Ellipsis]); the
 *   clamp only bounds what a verbose `GET /command` description can push through
 *   text measurement, and it sits far above any width a phone or tablet can show,
 *   so it never changes what the user sees.
 *
 * Asserted by `Wave5ComposerRowLayoutTest`.
 */
object SuggestionRowText {

    /** Drawn when both the item's label and its category fallback are blank. */
    const val UNKNOWN_LABEL = "?"

    /** Label budget — well past any real `/name`, `@name` or file path. */
    const val LABEL_MAX_CHARS = 96

    /** Description budget — well past one line on any screen. */
    const val DESCRIPTION_MAX_CHARS = 512

    /**
     * The label the row draws for [label]: trimmed, clamped, and never blank —
     * [fallback] (the row's category name) stands in for a blank server name, and
     * [UNKNOWN_LABEL] covers the unreachable case where that is blank too.
     */
    fun label(label: String, fallback: String = ""): String {
        val primary = clamp(label.trim(), LABEL_MAX_CHARS)
        if (primary.isNotEmpty()) return primary
        val category = clamp(fallback.trim(), LABEL_MAX_CHARS)
        return category.ifEmpty { UNKNOWN_LABEL }
    }

    /** The description the row draws, or null when there is nothing to show. */
    fun description(description: String?): String? {
        val text = clamp(description?.trim().orEmpty(), DESCRIPTION_MAX_CHARS)
        return text.ifEmpty { null }
    }

    /**
     * [text] cut to at most [max] characters, with the cut marked by an extra `…`
     * (so a truncated result is `max + 1` characters long). A cut that would land
     * between the two halves of a surrogate pair backs off one unit, so an emoji in
     * a description can never be rendered as a replacement character.
     */
    fun clamp(text: String, max: Int): String {
        if (max <= 0) return ""
        if (text.length <= max) return text
        val end = if (text[max - 1].isHighSurrogate()) max - 1 else max
        return text.substring(0, end.coerceAtLeast(0)) + ELLIPSIS
    }

    /** `…` — the same character `TextOverflow.Ellipsis` draws. */
    private const val ELLIPSIS = "\u2026"
}
