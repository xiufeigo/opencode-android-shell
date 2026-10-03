package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.markdown.MdBlock
import com.xiufeigo.opencodeshell.ui.markdown.MdImage
import com.xiufeigo.opencodeshell.ui.markdown.MdSpan
import com.xiufeigo.opencodeshell.ui.markdown.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Timeline fidelity — the Compose-native markdown parser (`ui/markdown/`).
 *
 * Every case pins a contract the upstream renderer
 * (`_upstream/packages/session-ui/src/components/markdown.css` +
 * `research/02` §3.5b) relies on: fenced code is terminal (markdown inside a
 * fence or a code span is never parsed), headings/lists/quotes/hr are blocks,
 * and inline emphasis/links resolve to flat [MdSpan] runs. An unclosed fence
 * (streaming) yields `complete = false` instead of raw back-ticks.
 */
class TimelineFidelityTest {

    private fun parse(text: String) = MarkdownParser.parse(text)
    private fun inline(text: String) = MarkdownParser.parseInline(text)

    // ------------------------------------------------------------- paragraphs

    @Test
    fun plainTextIsOneParagraphOfPlainSpans() {
        assertEquals(
            listOf(MdBlock.Paragraph(listOf(MdSpan("hello world")))),
            parse("hello world"),
        )
    }

    @Test
    fun softLineBreaksInsideAParagraphCollapseToASpace() {
        // Upstream runs CommonMark (`marked`): a single \n is a soft wrap.
        assertEquals(
            listOf(MdBlock.Paragraph(listOf(MdSpan("one two")))),
            parse("one\ntwo"),
        )
    }

    @Test
    fun blankLinesSplitParagraphs() {
        val blocks = parse("first\n\nsecond")
        assertEquals(2, blocks.size)
        assertEquals(MdBlock.Paragraph(listOf(MdSpan("first"))), blocks[0])
        assertEquals(MdBlock.Paragraph(listOf(MdSpan("second"))), blocks[1])
    }

    // ---------------------------------------------------------------- fences

    @Test
    fun fencedCodeKeepsItsLanguageAndBody() {
        val blocks = parse("```kotlin\nval x = 1\n```")
        assertEquals(listOf(MdBlock.CodeBlock("kotlin", "val x = 1", complete = true)), blocks)
    }

    @Test
    fun fenceWithoutLanguageHasNullLanguage() {
        val blocks = parse("```\nraw\n```")
        assertEquals(listOf(MdBlock.CodeBlock(null, "raw", complete = true)), blocks)
    }

    @Test
    fun markdownInsideAFenceIsNeverParsed() {
        val blocks = parse("```\n# not a heading\n**not bold**\n- not a list\n```")
        assertEquals(1, blocks.size)
        val fence = blocks[0] as MdBlock.CodeBlock
        assertEquals("# not a heading\n**not bold**\n- not a list", fence.code)
    }

    @Test
    fun anUnclosedFenceYieldsAnIncompleteCodeBlock() {
        // Streaming mid-fence: upstream `markdown-stream.ts` reports
        // `mode: "code", complete: false`; the partial body still renders as code.
        val blocks = parse("before\n\n```bash\nls -la")
        assertEquals(
            listOf(
                MdBlock.Paragraph(listOf(MdSpan("before"))),
                MdBlock.CodeBlock("bash", "ls -la", complete = false),
            ),
            blocks,
        )
    }

    @Test
    fun tildeFencesWorkToo() {
        val blocks = parse("~~~\ncode\n~~~")
        assertEquals(listOf(MdBlock.CodeBlock(null, "code", complete = true)), blocks)
    }

    // --------------------------------------------------------------- headings

    @Test
    fun atxHeadingsKeepTheirLevelAndStripClosingHashes() {
        assertEquals(
            listOf(MdBlock.Heading(1, listOf(MdSpan("Title")))),
            parse("# Title"),
        )
        assertEquals(
            listOf(MdBlock.Heading(2, listOf(MdSpan("Sub")))),
            parse("## Sub ##"),
        )
        assertEquals(3, (parse("### deep")[0] as MdBlock.Heading).level)
    }

    @Test
    fun aTrailingHashWithoutASpaceIsNotAClosingSequence() {
        // CommonMark: `## C#` keeps the hash (language names must survive).
        val heading = parse("## C#")[0] as MdBlock.Heading
        assertEquals(listOf(MdSpan("C#")), heading.spans)
    }

    @Test
    fun aHashWithoutASpaceIsAParagraph() {
        assertEquals(
            listOf(MdBlock.Paragraph(listOf(MdSpan("#hashtag")))),
            parse("#hashtag"),
        )
    }

    // ------------------------------------------------------------------ lists

    @Test
    fun unorderedListCollectsConsecutiveItems() {
        val blocks = parse("- one\n- two\n- three")
        assertEquals(
            listOf(
                MdBlock.BulletList(
                    ordered = false,
                    items = listOf(listOf(MdSpan("one")), listOf(MdSpan("two")), listOf(MdSpan("three"))),
                ),
            ),
            blocks,
        )
    }

    @Test
    fun orderedListIsDecimal() {
        val blocks = parse("1. first\n2. second")
        val list = blocks[0] as MdBlock.BulletList
        assertTrue(list.ordered)
        assertEquals(2, list.items.size)
    }

    @Test
    fun listItemsKeepTheirInlineStyling() {
        val blocks = parse("- **bold** item\n- `code` item")
        val list = blocks[0] as MdBlock.BulletList
        assertEquals(listOf(MdSpan("bold", bold = true), MdSpan(" item")), list.items[0])
        assertEquals(listOf(MdSpan("code", code = true), MdSpan(" item")), list.items[1])
    }

    @Test
    fun indentedContinuationFoldsIntoTheCurrentItem() {
        // Documented simplification: nested content joins the parent text.
        val list = (parse("- parent\n  continuation\n- next")[0]) as MdBlock.BulletList
        assertEquals(2, list.items.size)
        assertEquals(listOf(MdSpan("parent continuation")), list.items[0])
    }

    @Test
    fun aParagraphAfterAListIsASeparateBlock() {
        val blocks = parse("- item\n\ntail")
        assertEquals(2, blocks.size)
        assertTrue(blocks[1] is MdBlock.Paragraph)
    }

    // ----------------------------------------------------------------- quotes

    @Test
    fun blockQuoteParsesItsChildrenRecursively() {
        val blocks = parse("> quoted **text**\n> second line")
        val quote = blocks[0] as MdBlock.BlockQuote
        assertEquals(
            listOf(MdBlock.Paragraph(listOf(MdSpan("quoted "), MdSpan("text", bold = true), MdSpan(" second line")))),
            quote.children,
        )
    }

    @Test
    fun nestedQuotesNest() {
        val quote = parse("> > deep")[0] as MdBlock.BlockQuote
        val inner = quote.children[0] as MdBlock.BlockQuote
        assertEquals(listOf(MdBlock.Paragraph(listOf(MdSpan("deep")))), inner.children)
    }

    @Test
    fun quoteNestingWithinTheCapIsUnchanged() {
        var blocks = parse(">".repeat(5) + " x")
        var nesting = 0
        while (blocks.size == 1 && blocks[0] is MdBlock.BlockQuote) {
            blocks = (blocks[0] as MdBlock.BlockQuote).children
            nesting++
        }
        assertEquals(5, nesting)
        assertEquals(listOf(MdBlock.Paragraph(listOf(MdSpan("x")))), blocks)
    }

    @Test
    fun aPathologicalQuoteLineIsCappedInsteadOfOverflowingTheStack() {
        // One line with thousands of `>` used to peel one recursion level per
        // marker until StackOverflowError on the render thread.
        var blocks = parse(">".repeat(10_000) + " x")
        var nesting = 0
        while (blocks.size == 1 && blocks[0] is MdBlock.BlockQuote) {
            blocks = (blocks[0] as MdBlock.BlockQuote).children
            nesting++
        }
        assertEquals(MarkdownParser.MAX_QUOTE_DEPTH, nesting)
        assertEquals(1, blocks.size)
        val text = (blocks[0] as MdBlock.Paragraph).spans.joinToString("") { it.text }
        assertTrue("unread `>` markers stay as text", text.startsWith(">"))
        assertTrue("the line tail survives", text.endsWith(" x"))
    }

    // --------------------------------------------------------- thematic break

    @Test
    fun thematicBreaksAreTheirOwnBlock() {
        assertEquals(listOf(MdBlock.ThematicBreak), parse("---"))
        assertEquals(listOf(MdBlock.ThematicBreak), parse("***"))
        assertEquals(listOf(MdBlock.ThematicBreak), parse("___"))
    }

    @Test
    fun aDashLineIsNotAListItem() {
        assertEquals(listOf(MdBlock.ThematicBreak), parse("-----"))
    }

    // ----------------------------------------------------------------- inline

    @Test
    fun boldItalicAndBoldItalicResolve() {
        assertEquals(listOf(MdSpan("b", bold = true)), inline("**b**"))
        assertEquals(listOf(MdSpan("i", italic = true)), inline("*i*"))
        assertEquals(listOf(MdSpan("bi", bold = true, italic = true)), inline("***bi***"))
        assertEquals(listOf(MdSpan("u", bold = true)), inline("__u__"))
        assertEquals(listOf(MdSpan("u", italic = true)), inline("_u_"))
    }

    @Test
    fun emphasisKeepsSurroundingText() {
        assertEquals(
            listOf(MdSpan("a "), MdSpan("b", bold = true), MdSpan(" c")),
            inline("a **b** c"),
        )
    }

    @Test
    fun inlineCodeIsOneTerminalSpan() {
        assertEquals(
            listOf(MdSpan("use "), MdSpan("foo()", code = true), MdSpan(" here")),
            inline("use `foo()` here"),
        )
    }

    @Test
    fun markdownInsideACodeSpanIsNotParsed() {
        assertEquals(
            listOf(MdSpan("**not bold**", code = true)),
            inline("`**not bold**`"),
        )
    }

    @Test
    fun doubleBacktickSpansHoldSingleBackticks() {
        assertEquals(listOf(MdSpan("a `tick` b", code = true)), inline("``a `tick` b``"))
    }

    @Test
    fun linksResolveUrlAndKeepLabelStyling() {
        assertEquals(
            listOf(MdSpan("docs", link = "https://example.com")),
            inline("[docs](https://example.com)"),
        )
        assertEquals(
            listOf(MdSpan("site", bold = true, link = "https://example.com")),
            inline("[**site**](https://example.com)"),
        )
    }

    @Test
    fun aMalformedLinkStaysLiteral() {
        assertEquals(listOf(MdSpan("[docs]")), inline("[docs]"))
        assertEquals(listOf(MdSpan("[a](b")), inline("[a](b"))
    }

    @Test
    fun intraWordUnderscoresStayLiteral() {
        // CommonMark '_' flanking: snake_case must not italicise.
        assertEquals(listOf(MdSpan("foo_bar_baz")), inline("foo_bar_baz"))
    }

    @Test
    fun spacedAsterisksStayLiteral() {
        assertEquals(listOf(MdSpan("2 * 3 * 4")), inline("2 * 3 * 4"))
    }

    @Test
    fun backslashEscapesPunctuation() {
        assertEquals(listOf(MdSpan("a *b*")), inline("a \\*b*"))
    }

    @Test
    fun unterminatedEmphasisStaysLiteral() {
        assertEquals(listOf(MdSpan("a **b")), inline("a **b"))
    }

    @Test
    fun adjacentSameStyleSpansMerge() {
        // `**a****b**` parses as two bold runs but renders as one span.
        assertEquals(listOf(MdSpan("ab", bold = true)), inline("**a****b**"))
    }

    @Test
    fun anInlineImageSpanKeepsItsTargetBesideTheAltText() {
        // A-1: the span still carries the target so a renderer can draw it; the
        // paragraph itself (mixed text) keeps the alt text.
        assertEquals(
            listOf(MdSpan("logo", image = MdImage("logo", "https://example.com/l.png"))),
            inline("![logo](https://example.com/l.png)"),
        )
    }

    @Test
    fun bareHttpUrlsAreAutoLinked() {
        // GFM autolink: a bare http(s) URL taps through `LinkAnnotation.Url`
        // into the in-app browser; only [label](url) used to link here.
        assertEquals(
            listOf(
                MdSpan("see "),
                MdSpan("https://example.com", link = "https://example.com"),
            ),
            inline("see https://example.com"),
        )
    }

    // -------------------------------------------------------------- structure

    @Test
    fun aFullAssistantAnswerKeepsItsBlockOrder() {
        val blocks = parse(
            """
            ## Plan
            - first
            - second

            ```bash
            gradlew build
            ```

            Done **now**.
            """.trimIndent(),
        )
        assertEquals(4, blocks.size)
        assertTrue(blocks[0] is MdBlock.Heading)
        assertTrue(blocks[1] is MdBlock.BulletList)
        assertEquals(MdBlock.CodeBlock("bash", "gradlew build", complete = true), blocks[2])
        assertEquals(
            MdBlock.Paragraph(listOf(MdSpan("Done "), MdSpan("now", bold = true), MdSpan("."))),
            blocks[3],
        )
    }

    @Test
    fun blankInputParsesToNothing() {
        assertTrue(parse("").isEmpty())
        assertTrue(parse("   \n \n").isEmpty())
    }

    @Test
    fun carriageReturnsAreNormalised() {
        assertEquals(
            listOf(MdBlock.CodeBlock(null, "a", complete = true)),
            parse("```\r\na\r\n```"),
        )
    }

    @Test
    fun inlineParserNeverProducesEmptySpans() {
        assertFalse(inline("**a** `b` [c](d)").any { it.text.isEmpty() })
    }
}
