package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.markdown.CodeHighlight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WAVE 2.2a — fenced-code token highlight (`CodeHighlight`).
 *
 * Pins the Prism4j → v2-syntax-kind contract the Compose code block relies on:
 * keyword/string/type/comment kinds resolve, shell blocks and unknown languages
 * stay plain (upstream renders shell blocks in `currentColor`), offsets always
 * partition the input (the flicker-free streaming invariant), and garbage input
 * can never mis-color a range. `variable`/`operator`/`punctuation` stay plain
 * by design (see `CodeHighlight`: those map to the undecided legacy XML
 * aliases, not to the v2 ramp). JVM-testable: no Android/Compose imports.
 */
class CodeHighlightTest {

    @Test
    fun kotlinKeywordsAndStringsResolve() {
        val code = "fun main() {\n  val s = \"hi\" // note\n}"
        val spans = CodeHighlight.tokenize(code, "kotlin")
        assertTrue("expected spans, got none", spans.isNotEmpty())
        assertTrue(spans.any { it.kind == CodeHighlight.Kind.KEYWORD && code.substring(it.start, it.end) == "fun" })
        assertTrue(spans.any { it.kind == CodeHighlight.Kind.STRING })
        assertTrue(spans.any { it.kind == CodeHighlight.Kind.COMMENT })
    }

    @Test
    fun spansPartitionTheInput() {
        // Streaming invariant: identical prefixes re-tokenize identically, so a
        // per-delta re-tokenize cannot flicker already-rendered colors.
        for (language in listOf("kotlin", "python", "json", "javascript", "go", "sql", "yaml", "markdown")) {
            val code = sampleFor(language)
            val full = CodeHighlight.tokenize(code, language)
            assertOrderedPartition(code, full, language)
            val prefix = code.take(code.length / 2)
            val head = CodeHighlight.tokenize(prefix, language)
            assertOrderedPartition(prefix, head, "$language (prefix)")
            // Prefix-stable (the streaming no-flicker invariant): every head
            // span except the boundary-cut last one must reappear verbatim at
            // the start of the full tokenization.
            val stableHead = head.dropLast(1)
            val zipped = full.take(stableHead.size)
            assertEquals(
                "prefix re-tokenize diverged for $language",
                stableHead.map { code.substring(it.start, it.end) to it.kind },
                zipped.map { code.substring(it.start, it.end) to it.kind },
            )
        }
    }

    @Test
    fun shellBlocksStayPlainByDesign() {
        // Upstream `markdown.css` forces shell tokens to `currentColor`.
        for (language in listOf("bash", "sh", "shell", "zsh", "fish", "console", "terminal")) {
            assertTrue(CodeHighlight.isShell(language))
            assertEquals(emptyList<CodeHighlight.Span>(), CodeHighlight.tokenize("echo hi && ls -la", language))
        }
    }

    @Test
    fun unknownLanguagesStayPlain() {
        assertEquals(emptyList<CodeHighlight.Span>(), CodeHighlight.tokenize("fn main() {}", "rust"))
        assertEquals(emptyList<CodeHighlight.Span>(), CodeHighlight.tokenize("const x: number = 1", "typescript"))
        assertEquals(emptyList<CodeHighlight.Span>(), CodeHighlight.tokenize("x", null))
        assertEquals(emptyList<CodeHighlight.Span>(), CodeHighlight.tokenize("x", "text"))
        assertEquals(emptyList<CodeHighlight.Span>(), CodeHighlight.tokenize("", "kotlin"))
    }

    @Test
    fun oversizedBlocksStayPlain() {
        val big = "x = 1\n".repeat(CodeHighlight.MAX_CHARS / 6 + 1)
        assertTrue(big.length > CodeHighlight.MAX_CHARS)
        assertEquals(emptyList<CodeHighlight.Span>(), CodeHighlight.tokenize(big, "python"))
    }

    @Test
    fun aliasesResolve() {
        assertEquals(
            CodeHighlight.tokenize("print('hi')", "python").map { it.kind },
            CodeHighlight.tokenize("print('hi')", "py").map { it.kind },
        )
        assertEquals(
            CodeHighlight.tokenize("val x = 1", "kotlin").map { it.kind },
            CodeHighlight.tokenize("val x = 1", "kt").map { it.kind },
        )
    }

    private fun sampleFor(language: String): String = when (language) {
        "kotlin" -> "fun greet(name: String): Int {\n  // hello\n  val n = 42\n  return n\n}"
        "python" -> "def greet(name):\n    # hello\n    s = \"hi\"\n    return len(s)"
        "json" -> "{\"name\": \"ann\", \"age\": 30, \"ok\": true, \"x\": null}"
        "javascript" -> "function greet(name) {\n  // hello\n  const s = \"hi\";\n  return s.length;\n}"
        "go" -> "package main\n\nfunc main() {\n\t// hello\n\ts := \"hi\"\n\tprintln(s)\n}"
        "sql" -> "SELECT id, name FROM users WHERE age > 30 ORDER BY name;"
        "yaml" -> "name: ann\nage: 30\nenabled: true # hello"
        else -> "# Title\n\nSome **bold** text with `code` and a [link](https://example.com)."
    }

    private fun assertOrderedPartition(code: String, spans: List<CodeHighlight.Span>, label: String) {
        var cursor = 0
        var lastStart = -1
        for (span in spans) {
            assertTrue("$label: spans out of order ($span)", span.start >= lastStart)
            assertTrue("$label: spans overlap ($span)", span.start >= cursor)
            assertTrue("$label: empty span ($span)", span.end > span.start)
            assertTrue("$label: span out of range ($span)", span.end <= code.length)
            lastStart = span.start
            cursor = span.end
        }
    }
}
