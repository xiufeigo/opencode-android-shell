package com.xiufeigo.opencodeshell.ui.session.composer

/**
 * v16: which half of a suggestion row gives way when the row is too narrow.
 *
 * The row has two text halves — the label (a `/name`, an agent name, or a whole
 * **file path**) and the trailing column — and a Compose `Row` measures its
 * weighted children *last*, so whichever half is weighted is the half that gets
 * ellipsized while the other keeps its natural width. Exactly one of them may be
 * the weighted one; the policy here decides which.
 *
 * The measured bug (`research/v16_verify/misc/v16_mention_dir_file.png`, `@` menu
 * over `/find/file`): every file row drew the icon and the path only — the
 * "Directory" / "File" tag the a11y `contentDescription` still announced was
 * squeezed to 0 dp. The tag is the row's *fallback* trailing column
 * (`description ?: category`, see [SuggestionRowText.description]) and the path is
 * the label, so the W5-G arrangement (label unweighted, trailing weighted) handed
 * the whole row to the path and left the tag nothing.
 *
 * The rule, therefore:
 *
 * - **a row with a server description** (a `/` command row, an agent row) keeps
 *   the W5-G arrangement — [Flexible.TRAILING]. The label is a short name that
 *   must never be squeezed by a long description (`editor.tsx:799-816` `shrink-0`
 *   on the label, `min-w-0 truncate` on the description);
 * - **a row whose trailing column is the category tag** (no description: every
 *   `@` file/directory row) protects the tag instead — [Flexible.LABEL]. The path
 *   is the half that ellipsizes, so the tag always keeps its natural width and
 *   the row still fits a phone: icon 16 + gaps 16 + paddings 16 + a ~60 dp tag
 *   leaves the path well over 200 dp at 360 dp wide.
 *
 * Asserted by `Wave5ComposerRowLayoutTest` (both branches) and
 * `MentionRowTagVisibilityTest` (the tag's own guarantee).
 */
object SuggestionRowLayout {

    /** The half of the row that may be ellipsized; the other keeps its width. */
    enum class Flexible { LABEL, TRAILING }

    /**
     * [hasDescription] is whether the row's trailing column draws a server (or
     * built-in) description rather than falling back to the category tag — i.e.
     * `SuggestionRowText.description(...) != null` at the call site.
     */
    fun flexible(hasDescription: Boolean): Flexible =
        if (hasDescription) Flexible.TRAILING else Flexible.LABEL
}
