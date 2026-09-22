package com.xiufeigo.opencodeshell.ui.markdown

import com.xiufeigo.opencodeshell.OcGrammarLocator
import io.noties.prism4j.Prism4j

/**
 * Prism4j tokenizer for fenced code blocks in the Compose-native timeline body.
 *
 * Upstream highlights code through Shiki (`OpenCode` theme) inside a worker with
 * incremental stable/unstable tokens (`markdown.worker.ts`, `markdown-code-state.ts`:
 * only the changed tail is re-rendered, so streaming never flickers). Prism4j has
 * no incremental API, so this tokenizer re-tokenizes the whole block
 * deterministically from the full text: the same prefix always yields the same
 * colors, which is what makes streaming flicker-free here. The call rate is
 * bounded by the existing 80 ms `coalesceLatest` window in [OcMarkdown]
 * (`MarkdownStream.kt`) and every result is memoized per `(code, language)`
 * keys plus the palette in composition, so a delta re-render costs at most one
 * tokenize per window — never per-frame `parse()+render()` (the O(n²) trap
 * `MarkdownStream.kt` documents).
 *
 * Kind mapping mirrors `OcPrismTheme.typeColors` (the View-era `ChatView`
 * pipeline) so both bodies agree; the five kinds below resolve to
 * `OcColors.syntax*` fields (theme-wave landed the v2 ramp per the wave2.1
 * handoff §1: light pink-800/green-800/orange-800/purple-800, dark
 * pink-400/green-400/orange-400/purple-400, constant teal both schemes).
 * `variable`/`operator`/`punctuation`/`namespace`/`entity`/`bold`/`italic` and
 * anything unknown stay plain (unlike `OcPrismTheme`, which maps them to the
 * `oc_syntax_variable`/`oc_syntax_punctuation` XML resources): those two
 * resources are legacy `--text-strong`/`--text-base` aliases (`#161616` /
 * `#5C5C5C` light) that the wave2.1 handoff §1 says Wave 2.3 may still re-point,
 * so depending on them would couple the Compose body to an undecided mapping.
 * Plain spans render in the caller's body color, which matches upstream's
 * variable/punctuation treatment closely enough (upstream paints them in
 * near-body tones, never in a syntax-ramp hue).
 *
 * Deliberate deviations (also in `research/v2_audit/wave2.2a-notes.md`):
 * - shell languages (`bash`, `sh`, … — the exact `shellLanguages` set from
 *   `markdown.tsx:203`) are returned un-highlighted **by design**: upstream
 *   forces every token span to `currentColor` for `data-code-kind="shell"`
 *   (`markdown.css:225-237`), i.e. shell blocks are visually plain there too.
 * - languages without a vendored Prism4j grammar (`rust`, `typescript`, `tsx`,
 *   `diff`, … — `OcGrammarLocator` covers 10 grammars) render plain instead of
 *   mis-highlighted; upstream Shiki covers them.
 * - blocks longer than [MAX_CHARS] render plain (regex-tokenizer blowup guard).
 *
 * Pure Kotlin/JVM-testable: no Android/Compose imports (`CodeHighlightTest`).
 */
object CodeHighlight {

    /** Token kinds that carry a v2 syntax color; everything else is body text. */
    enum class Kind {
        KEYWORD,
        STRING,
        CONSTANT,
        PRIMITIVE,
        PROPERTY,
        TYPE,
        COMMENT,
    }

    /** One colored range: `[start, end)` char offsets into the code. */
    data class Span(val start: Int, val end: Int, val kind: Kind)

    /**
     * Tokenize [code] with the Prism4j grammar for [language].
     * Returns the non-plain spans (merged, ordered, partitioning the input);
     * empty means "render the whole block in the body color".
     */
    fun tokenize(code: String, language: String?): List<Span> {
        if (code.isEmpty() || code.length > MAX_CHARS) return emptyList()
        val grammar = grammarFor(language) ?: return emptyList()
        val nodes = try {
            prism4j.tokenize(code, grammar)
        } catch (_: Exception) {
            return emptyList()
        }
        val out = ArrayList<Span>()
        var cursor = 0

        fun visit(node: Prism4j.Node, inherited: Kind?) {
            if (!node.isSyntax) {
                val end = cursor + node.textLength()
                if (inherited != null && end > cursor) out += Span(cursor, end, inherited)
                cursor = end
                return
            }
            val syntax = node as Prism4j.Syntax
            val own = kindOf(syntax.type(), syntax.alias())
            val children = if (syntax.tokenized()) syntax.children() else emptyList()
            if (children.isEmpty()) {
                val end = cursor + node.textLength()
                if (own != null && end > cursor) out += Span(cursor, end, own)
                cursor = end
                return
            }
            for (child in children) visit(child, own)
        }

        for (node in nodes) visit(node, null)
        // `tokenize()` must partition the input; if a grammar mis-reports a
        // length, bail to plain rather than mis-coloring a range.
        if (cursor != code.length) return emptyList()
        return mergeAdjacent(out)
    }

    /**
     * Upstream `markdown.tsx:203` `shellLanguages`: rendered un-highlighted by
     * design (see the class doc).
     */
    fun isShell(language: String?): Boolean =
        language?.trim()?.lowercase() in SHELL_LANGUAGES

    // ------------------------------------------------------------- internals

    private val prism4j by lazy { Prism4j(OcGrammarLocator()) }

    /** Grammar cache: `OcGrammarLocator` caches the grammars too; this avoids re-probing. */
    private val grammarCache = HashMap<String, Prism4j.Grammar?>()

    @Synchronized
    private fun grammarFor(language: String?): Prism4j.Grammar? {
        val key = language?.trim()?.lowercase().orEmpty()
        if (key.isEmpty() || key == "text" || key == "plain" || key in SHELL_LANGUAGES) return null
        return grammarCache.getOrPut(key) {
            try {
                prism4j.grammar(key)
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Prism token type → [Kind]. Mirrors the syntax-ramp subset of
     * `OcPrismTheme.typeColors` (property/type/primitive/string/constant/
     * keyword/comment); `variable`, `interpolation`, `bold`, `italic`,
     * `operator`, `punctuation`, `namespace`, `entity` and anything unknown stay
     * plain (see the class doc).
     */
    private fun kindOf(type: String, alias: String?): Kind? {
        KIND_BY_TYPE[type]?.let { return it }
        if (alias != null) KIND_BY_TYPE[alias]?.let { return it }
        return null
    }

    private val KIND_BY_TYPE: Map<String, Kind> = mapOf(
        "property" to Kind.PROPERTY,
        "attr-name" to Kind.PROPERTY,
        "key" to Kind.PROPERTY,
        "type" to Kind.TYPE,
        "class-name" to Kind.TYPE,
        "function" to Kind.TYPE,
        "tag" to Kind.TYPE,
        "atrule" to Kind.TYPE,
        "builtin" to Kind.PRIMITIVE,
        "annotation" to Kind.PRIMITIVE,
        "important" to Kind.PRIMITIVE,
        "string" to Kind.STRING,
        "char" to Kind.STRING,
        "triple-quoted-string" to Kind.STRING,
        "scalar" to Kind.STRING,
        "attr-value" to Kind.STRING,
        "url" to Kind.STRING,
        "regex" to Kind.STRING,
        "number" to Kind.CONSTANT,
        "boolean" to Kind.CONSTANT,
        "null" to Kind.CONSTANT,
        "constant" to Kind.CONSTANT,
        "keyword" to Kind.KEYWORD,
        "prolog" to Kind.KEYWORD,
        "doctype" to Kind.KEYWORD,
        "cdata" to Kind.KEYWORD,
        "comment" to Kind.COMMENT,
    )

    private fun mergeAdjacent(spans: List<Span>): List<Span> {
        if (spans.size < 2) return spans
        val merged = ArrayList<Span>(spans.size)
        for (span in spans) {
            val last = merged.lastOrNull()
            if (last != null && last.kind == span.kind && last.end == span.start) {
                merged[merged.lastIndex] = last.copy(end = span.end)
            } else {
                merged += span
            }
        }
        return merged
    }

    private val SHELL_LANGUAGES = setOf("bash", "sh", "shell", "zsh", "fish", "console", "terminal")

    /** Regex-tokenizer blowup guard (see the class doc). */
    const val MAX_CHARS = 32_768
}
