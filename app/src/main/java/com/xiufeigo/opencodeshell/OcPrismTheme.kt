package com.xiufeigo.opencodeshell

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import io.noties.markwon.syntax.Prism4jTheme
import io.noties.prism4j.Prism4j

/**
 * Prism4j theme driven by the oc_syntax_* color resources (values / values-night),
 * mapping prism token types onto the official v2 syntax palette.
 */
class OcPrismTheme(private val context: Context) : Prism4jTheme {

    private val bg = context.getColor(R.color.oc_layer_02)
    private val fg = context.getColor(R.color.oc_text_base)

    private val typeColors: Map<String, Int> = mapOf(
        "property" to R.color.oc_syntax_property,
        "attr-name" to R.color.oc_syntax_property,
        "key" to R.color.oc_syntax_property,
        "type" to R.color.oc_syntax_type,
        "class-name" to R.color.oc_syntax_type,
        "function" to R.color.oc_syntax_type,
        "tag" to R.color.oc_syntax_type,
        "atrule" to R.color.oc_syntax_type,
        "builtin" to R.color.oc_syntax_primitive,
        "annotation" to R.color.oc_syntax_primitive,
        "string" to R.color.oc_syntax_string,
        "char" to R.color.oc_syntax_string,
        "triple-quoted-string" to R.color.oc_syntax_string,
        "scalar" to R.color.oc_syntax_string,
        "attr-value" to R.color.oc_syntax_string,
        "url" to R.color.oc_syntax_string,
        "regex" to R.color.oc_syntax_string,
        "number" to R.color.oc_syntax_constant,
        "boolean" to R.color.oc_syntax_constant,
        "null" to R.color.oc_syntax_constant,
        "constant" to R.color.oc_syntax_constant,
        "keyword" to R.color.oc_syntax_keyword,
        "comment" to R.color.oc_syntax_comment,
        "prolog" to R.color.oc_syntax_keyword,
        "doctype" to R.color.oc_syntax_keyword,
        "cdata" to R.color.oc_syntax_keyword,
        "important" to R.color.oc_syntax_primitive,
        "variable" to R.color.oc_syntax_variable,
        "interpolation" to R.color.oc_syntax_variable,
        "bold" to R.color.oc_syntax_variable,
        "italic" to R.color.oc_syntax_variable,
        "operator" to R.color.oc_syntax_punctuation,
        "punctuation" to R.color.oc_syntax_punctuation,
        "namespace" to R.color.oc_syntax_punctuation,
        "entity" to R.color.oc_syntax_punctuation,
    )

    override fun background(): Int = bg

    override fun textColor(): Int = fg

    override fun apply(
        language: String,
        syntax: Prism4j.Syntax,
        builder: SpannableStringBuilder,
        start: Int,
        end: Int,
    ) {
        val resId = typeColors[syntax.type()]
            ?: syntax.alias()?.let { typeColors[it] }
            ?: return
        builder.setSpan(
            ForegroundColorSpan(context.getColor(resId)),
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }
}
