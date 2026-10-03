package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.markdown.MdBlock
import com.xiufeigo.opencodeshell.ui.markdown.MdSpan
import com.xiufeigo.opencodeshell.ui.markdown.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GFM extended autolinks in `MarkdownParser`: a bare `http(s)://` URL, a bare
 * `www.` domain (destination `https://` + text) and an email address
 * (destination `mailto:` + text, with a literal `mailto:` / `xmpp:` prefix
 * absorbed into the link the way cmark-gfm does).
 *
 * An autolink in body text resolves to an [MdSpan] with `link` set, so the
 * renderer carries it as `LinkAnnotation.Url` — tappable through
 * `LocalUriHandler` into the in-app browser panel (previously only
 * `[label](url)` linked, and bare URLs sat dead). `ftp:` and other schemes
 * never autolink; code spans and fenced blocks are terminal, so a link inside
 * back-ticks stays literal. JVM-only: the parser has no Android/Compose
 * imports.
 */
class MarkdownAutolinkTest {

    private fun inline(text: String) = MarkdownParser.parseInline(text)
    private fun parse(text: String) = MarkdownParser.parse(text)

    @Test
    fun aBareHttpsUrlBecomesALink() {
        assertEquals(
            listOf(
                MdSpan("see "),
                MdSpan("https://opencode.ai", link = "https://opencode.ai"),
                MdSpan(" docs"),
            ),
            inline("see https://opencode.ai docs"),
        )
    }

    @Test
    fun aBareHttpUrlBecomesALink() {
        assertEquals(
            listOf(MdSpan("http://127.0.0.1:4095", link = "http://127.0.0.1:4095")),
            inline("http://127.0.0.1:4095"),
        )
    }

    @Test
    fun trailingPunctuationStaysOutsideTheLink() {
        assertEquals(
            listOf(
                MdSpan("visit "),
                MdSpan("https://example.com", link = "https://example.com"),
                MdSpan("."),
            ),
            inline("visit https://example.com."),
        )
        assertEquals(
            listOf(
                MdSpan("a ", link = null),
                MdSpan("https://example.com/x", link = "https://example.com/x"),
                MdSpan(", b!", link = null),
            ),
            inline("a https://example.com/x, b!"),
        )
    }

    @Test
    fun anUnbalancedTrailingParenIsExcludedButABalancedOneIsKept() {
        assertEquals(
            listOf(
                MdSpan("(see "),
                MdSpan("https://example.com/a", link = "https://example.com/a"),
                MdSpan(")"),
            ),
            inline("(see https://example.com/a)"),
        )
        assertEquals(
            listOf(
                MdSpan(
                    "https://en.wikipedia.org/wiki/Foo_(bar)",
                    link = "https://en.wikipedia.org/wiki/Foo_(bar)",
                ),
            ),
            inline("https://en.wikipedia.org/wiki/Foo_(bar)"),
        )
    }

    @Test
    fun queryFragmentAndPortSurvive() {
        val url = "https://x.com/s?q=a&b=c#frag"
        assertEquals(listOf(MdSpan(url, link = url)), inline(url))
    }

    @Test
    fun anUppercaseSchemeLinks() {
        assertEquals(
            listOf(MdSpan("HTTPS://example.com", link = "HTTPS://example.com")),
            inline("HTTPS://example.com"),
        )
    }

    @Test
    fun multipleUrlsInOneRunEachLink() {
        assertEquals(
            listOf(
                MdSpan("a "),
                MdSpan("https://one.com", link = "https://one.com"),
                MdSpan(" and "),
                MdSpan("https://two.com", link = "https://two.com"),
            ),
            inline("a https://one.com and https://two.com"),
        )
    }

    @Test
    fun autolinkKeepsEmphasis() {
        assertEquals(
            listOf(
                MdSpan("see ", bold = true),
                MdSpan("https://example.com", bold = true, link = "https://example.com"),
            ),
            inline("**see https://example.com**"),
        )
    }

    @Test
    fun otherSchemesAndBareColonsStayPlain() {
        // `ftp:` is a scheme GFM autolinks; this parser deliberately keeps only
        // the three shapes the timeline renders (see the class doc).
        assertNull(inline("get ftp://files.example.com/x").firstOrNull { it.link != null })
        // `mailto:` with no address behind it is not an email.
        assertNull(inline("send it to mailto: please").firstOrNull { it.link != null })
    }

    // ------------------------------------------------------------------- www

    @Test
    fun aBareWwwDomainBecomesALink() {
        assertEquals(
            listOf(
                MdSpan("see "),
                MdSpan("www.example.com", link = "https://www.example.com"),
                MdSpan(" docs"),
            ),
            inline("see www.example.com docs"),
        )
    }

    @Test
    fun aWwwAutolinkKeepsItsPathQueryAndFragment() {
        val text = "www.example.com/a.b?q=1#frag"
        assertEquals(listOf(MdSpan(text, link = "https://$text")), inline(text))
    }

    @Test
    fun wwwTrailingPunctuationStaysOutsideTheLink() {
        assertEquals(
            listOf(
                MdSpan("Visit "),
                MdSpan("www.example.com", link = "https://www.example.com"),
                MdSpan("."),
            ),
            inline("Visit www.example.com."),
        )
        assertEquals(
            listOf(
                MdSpan("("),
                MdSpan("www.example.com", link = "https://www.example.com"),
                MdSpan(")"),
            ),
            inline("(www.example.com)"),
        )
    }

    @Test
    fun aWwwAutolinkNeedsAValidDomain() {
        // A `www.` with nothing behind it is not a domain.
        assertEquals(listOf(MdSpan("see www.")), inline("see www."))
        // No underscore in the last two segments (`gfm` §Autolinks (extension)).
        assertNull(inline("see www.xxx.yyy._zzz").firstOrNull { it.link != null })
        assertNull(inline("see www.xxx._yyy.zzz").firstOrNull { it.link != null })
        assertEquals(
            listOf(MdSpan("www._xxx.yyy.zzz", link = "https://www._xxx.yyy.zzz")),
            inline("www._xxx.yyy.zzz"),
        )
    }

    @Test
    fun aWwwAutolinkOnlyStartsAfterWhitespaceOrADelimiter() {
        assertNull(inline("see xwww.example.com").firstOrNull { it.link != null })
        assertEquals(
            listOf(MdSpan("www.example.com", bold = true, link = "https://www.example.com")),
            inline("**www.example.com**"),
        )
    }

    // ----------------------------------------------------------------- email

    @Test
    fun aBareEmailBecomesAMailtoLink() {
        assertEquals(
            listOf(
                MdSpan("mail "),
                MdSpan("foo@bar.baz", link = "mailto:foo@bar.baz"),
                MdSpan(" please"),
            ),
            inline("mail foo@bar.baz please"),
        )
    }

    @Test
    fun anEmailLocalPartAndDomainFollowTheGfmRules() {
        assertEquals(
            listOf(MdSpan("a.b-c_d@a.b", link = "mailto:a.b-c_d@a.b")),
            inline("a.b-c_d@a.b"),
        )
        // `+` may precede the `@`, not follow it.
        assertEquals(
            listOf(MdSpan("hello+xyz@mail.example", link = "mailto:hello+xyz@mail.example")),
            inline("hello+xyz@mail.example"),
        )
        assertNull(inline("hello@mail+xyz.example").firstOrNull { it.link != null })
        // A domain needs a period, and may not end in `-` or `_`.
        assertNull(inline("n@host").firstOrNull { it.link != null })
        assertNull(inline("a.b-c_d@a.b-").firstOrNull { it.link != null })
        assertNull(inline("a.b-c_d@a.b_").firstOrNull { it.link != null })
        // No local part, and no domain behind the `@`.
        assertNull(inline("@a.b.c@.").firstOrNull { it.link != null })
    }

    @Test
    fun anEmailTrailingPunctuationStaysOutsideTheLink() {
        assertEquals(
            listOf(
                MdSpan("foo@bar.baz", link = "mailto:foo@bar.baz"),
                MdSpan("."),
            ),
            inline("foo@bar.baz."),
        )
        assertEquals(
            listOf(
                MdSpan("write to "),
                MdSpan("foo@bar.baz", link = "mailto:foo@bar.baz"),
                MdSpan(", or not"),
            ),
            inline("write to foo@bar.baz, or not"),
        )
    }

    @Test
    fun aLiteralMailtoPrefixIsAbsorbedIntoTheLink() {
        assertEquals(
            listOf(MdSpan("mailto:foo@bar.baz", link = "mailto:foo@bar.baz")),
            inline("mailto:foo@bar.baz"),
        )
        assertEquals(
            listOf(
                MdSpan("This is a "),
                MdSpan("mailto:foo@bar.baz", link = "mailto:foo@bar.baz"),
            ),
            inline("This is a mailto:foo@bar.baz"),
        )
        // The prefix must follow a non-alphanumeric: `mmmmailto:` is not one.
        assertEquals(
            listOf(
                MdSpan("mmmmailto:"),
                MdSpan("foo@bar.baz", link = "mailto:foo@bar.baz"),
            ),
            inline("mmmmailto:foo@bar.baz"),
        )
    }

    @Test
    fun aLiteralXmppPrefixKeepsItsPath() {
        val text = "xmpp:foo@bar.baz/message"
        assertEquals(listOf(MdSpan(text, link = text)), inline(text))
    }

    @Test
    fun wwwAndEmailsInsideCodeSpansAreNotLinked() {
        assertEquals(listOf(MdSpan("foo@bar.baz", code = true)), inline("`foo@bar.baz`"))
        assertEquals(listOf(MdSpan("www.example.com", code = true)), inline("`www.example.com`"))
    }

    @Test
    fun wwwAndEmailsInsideFencedBlocksAreNotLinked() {
        val blocks = parse("```\nwww.example.com foo@bar.baz\n```")
        assertEquals(1, blocks.size)
        val code = blocks[0] as MdBlock.CodeBlock
        assertEquals("www.example.com foo@bar.baz", code.code)
    }

    @Test
    fun aBareSchemeWithNoTargetStaysLiteral() {
        assertEquals(listOf(MdSpan("go https:// home")), inline("go https:// home"))
    }

    @Test
    fun urlsInsideCodeSpansAreNotLinked() {
        assertEquals(
            listOf(MdSpan("https://example.com", code = true)),
            inline("`https://example.com`"),
        )
    }

    @Test
    fun urlsInsideFencedBlocksAreNotLinked() {
        val blocks = parse("```\nsee https://example.com\n```")
        assertEquals(1, blocks.size)
        val code = blocks[0] as MdBlock.CodeBlock
        assertEquals("see https://example.com", code.code)
    }

    @Test
    fun explicitLinkSyntaxIsUnchanged() {
        assertEquals(
            listOf(MdSpan("docs", link = "https://example.com")),
            inline("[docs](https://example.com)"),
        )
    }

    @Test
    fun aUrlInsideALinkLabelKeepsTheOuterDestination() {
        // No nested links: the label inherits the explicit destination.
        assertEquals(
            listOf(MdSpan("see https://a.com", link = "https://b.com")),
            inline("[see https://a.com](https://b.com)"),
        )
    }

    @Test
    fun autolinkedUrlsMergeBackAcrossStyleBoundaries() {
        // A URL split by emphasis still links on both sides of the split.
        val spans = inline("https://a.com **https://b.com**")
        assertTrue(spans.any { it.link == "https://a.com" })
        assertTrue(spans.any { it.text == "https://b.com" && it.bold && it.link == "https://b.com" })
    }
}
