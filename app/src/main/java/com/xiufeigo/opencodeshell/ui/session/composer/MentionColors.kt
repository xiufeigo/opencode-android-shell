package com.xiufeigo.opencodeshell.ui.session.composer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.xiufeigo.opencodeshell.ui.theme.OcColors

/**
 * Mention colours, exactly the upstream rules (`research/02` §4.2/§4.7):
 * `data-mention=file` → `--syntax-property`, `agent` → `--syntax-type`,
 * `reference` → `--syntax-keyword`.
 *
 * Shared by the composer editor ([MentionColorTransformation]) and the sent
 * user bubble, so both use one classification ([MentionText.classify]) and one
 * palette.
 */
internal fun mentionColor(kind: MentionText.Kind, colors: OcColors): Color = when (kind) {
    MentionText.Kind.FILE -> colors.syntaxProperty
    MentionText.Kind.AGENT -> colors.syntaxType
    MentionText.Kind.REFERENCE -> colors.syntaxKeyword
}

/** The body with every `@token` coloured; returns the input unchanged when there is none. */
internal fun mentionAnnotated(
    text: String,
    colors: OcColors,
    /** F-1: labels whose kind is known from the accepted tokens (`@docs` → reference). */
    kinds: Map<String, MentionText.Kind> = emptyMap(),
): AnnotatedString {
    val spans = MentionText.spans(text)
    if (spans.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        for (span in spans) {
            val label = text.substring(span.start + 1, span.end)
            val kind = kinds[label] ?: span.kind
            addStyle(SpanStyle(color = mentionColor(kind, colors)), span.start, span.end)
        }
    }
}

/**
 * Colours the mentions inside the composer's `BasicTextField`.
 *
 * Offsets are untouched ([OffsetMapping.Identity]), so the caret/selection
 * mapping stays exact — only `SpanStyle`s are added.
 *
 * F-1: [kinds] carries the kinds of the accepted tokens
 * ([MentionTokens.colors]) so a reference/resource mention colours by its
 * *kind* and not by [MentionText.classify]'s text heuristic.
 */
internal class MentionColorTransformation(
    private val colors: OcColors,
    private val kinds: Map<String, MentionText.Kind> = emptyMap(),
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(mentionAnnotated(text.text, colors, kinds), OffsetMapping.Identity)
}
