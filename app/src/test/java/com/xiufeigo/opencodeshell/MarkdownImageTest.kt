package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.markdown.MarkdownImages
import com.xiufeigo.opencodeshell.ui.markdown.MarkdownParser
import com.xiufeigo.opencodeshell.ui.markdown.MdBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A-1: `![alt](src)` — the source classification (`markdown-image.ts:3-23`
 * `localImagePath`, `runtime/server/image.ts:4-14`'s extension table) and the
 * token → render decision (`MarkdownParser` emits [MdBlock.Image] for a
 * paragraph of images, and every rejected source keeps its alt text).
 *
 * The last section is the P3 retry policy (`MarkdownImages.Retry`): a failed
 * read used to be final for the life of the row's composition — the image only
 * came back by leaving and re-entering the session — so the automatic schedule
 * and the tap-to-retry gate are pinned here.
 */
class MarkdownImageTest {

    // ------------------------------------------------------- source decisions

    @Test
    fun aRelativePathIsALocalImage() {
        assertEquals("chart.png", MarkdownImages.localImagePath("chart.png"))
        assertEquals("docs/chart.png", MarkdownImages.localImagePath("docs/chart.png"))
        // Backslashes fold, like `localImagePath`'s `replaceAll("\\", "/")`.
        assertEquals("docs/chart.png", MarkdownImages.localImagePath("docs\\chart.png"))
    }

    @Test
    fun anAbsoluteAndAFileUrlPathAreLocal() {
        assertEquals("/tmp/chart.png", MarkdownImages.localImagePath("/tmp/chart.png"))
        assertEquals("C:/proj/chart.png", MarkdownImages.localImagePath("C:/proj/chart.png"))
        assertEquals("C:/proj/chart.png", MarkdownImages.localImagePath("file:///C:/proj/chart.png"))
        assertEquals("/tmp/chart.png", MarkdownImages.localImagePath("file://localhost/tmp/chart.png"))
    }

    @Test
    fun percentEscapesDecodeOnceAndPlusStaysLiteral() {
        assertEquals("my dir/a+b.png", MarkdownImages.localImagePath("my%20dir/a+b.png"))
        assertEquals("中文/图.png", MarkdownImages.localImagePath("%E4%B8%AD%E6%96%87/%E5%9B%BE.png"))
    }

    @Test
    fun everyUnsafeSourceIsRejectedAsALocalPath() {
        assertNull(MarkdownImages.localImagePath("https://example.com/a.png"))
        assertNull(MarkdownImages.localImagePath("http://example.com/a.png"))
        assertNull(MarkdownImages.localImagePath("javascript:alert(1)"))
        assertNull(MarkdownImages.localImagePath("ftp://host/a.png"))
        assertNull(MarkdownImages.localImagePath("//host/a.png"))
        assertNull(MarkdownImages.localImagePath("file://host/a.png"))
        assertNull(MarkdownImages.localImagePath("a\u0000b.png"))
        assertNull(MarkdownImages.localImagePath(""))
    }

    @Test
    fun decideSplitsRemoteFromLocalFromRejected() {
        val remote = MarkdownImages.decide("https://images.example/chart.png")
        assertTrue(remote is MarkdownImages.Decision.Remote)
        assertEquals("https://images.example/chart.png", (remote as MarkdownImages.Decision.Remote).url)

        val local = MarkdownImages.decide("docs/chart.png")
        assertTrue(local is MarkdownImages.Decision.Local)
        assertEquals("docs/chart.png", (local as MarkdownImages.Decision.Local).path)
        assertEquals("image/png", local.mime)

        assertEquals(MarkdownImages.Decision.Rejected, MarkdownImages.decide("javascript:alert(1)"))
        assertEquals(MarkdownImages.Decision.Rejected, MarkdownImages.decide("notes.txt"))
        // `readLocalImage`'s table has `svg`, but Android has no SVG decoder —
        // the row degrades to the alt text instead of an empty box.
        assertEquals(MarkdownImages.Decision.Rejected, MarkdownImages.decide("icon.svg"))
        assertTrue(MarkdownImages.unsupportedFormat("icon.svg"))
        // A half-typed URL mid-stream must not throw and must not render.
        assertEquals(MarkdownImages.Decision.Rejected, MarkdownImages.decide("https://"))
    }

    @Test
    fun theExtensionTableMatchesTheDecodableFormats() {
        assertEquals("image/jpeg", MarkdownImages.imageMime("a.jpg"))
        assertEquals("image/jpeg", MarkdownImages.imageMime("a.JPEG"))
        assertEquals("image/webp", MarkdownImages.imageMime("a.webp"))
        assertEquals("image/gif", MarkdownImages.imageMime("a.gif"))
        assertNull(MarkdownImages.imageMime("a.txt"))
        assertNull(MarkdownImages.imageMime("noextension"))
    }

    // ---------------------------------------------------------- token → block

    @Test
    fun aParagraphOfOneImageBecomesAnImageBlock() {
        val blocks = MarkdownParser.parse("![chart](docs/chart.png)")
        assertEquals(1, blocks.size)
        val image = blocks[0] as MdBlock.Image
        assertEquals("chart", image.alt)
        assertEquals("docs/chart.png", image.src)
    }

    @Test
    fun twoImagesOnOneLineBecomeTwoImageBlocks() {
        val blocks = MarkdownParser.parse("![a](a.png) ![b](b.png)")
        assertEquals(listOf("a", "b"), blocks.filterIsInstance<MdBlock.Image>().map { it.alt })
    }

    @Test
    fun anImageInsideAStreamingParagraphStaysAnAltTextSpan() {
        // Mixed text: the Compose body has no inline-image slot yet, so the
        // paragraph keeps the pre-A-1 alt text (documented gap).
        val blocks = MarkdownParser.parse("see ![chart](chart.png) for the numbers")
        assertEquals(1, blocks.size)
        val paragraph = blocks[0] as MdBlock.Paragraph
        assertEquals("see chart for the numbers", paragraph.spans.joinToString("") { it.text })
        // The span still carries its target, so a future inline renderer can
        // pick it up without a parser change.
        assertEquals("chart.png", paragraph.spans.first { it.image != null }.image?.src)
    }

    @Test
    fun aParagraphWithoutImagesIsUnaffected() {
        val blocks = MarkdownParser.parse("plain text")
        assertTrue(blocks[0] is MdBlock.Paragraph)
        assertTrue((blocks[0] as MdBlock.Paragraph).spans.all { it.image == null })
    }

    @Test
    fun anEmptyAltKeepsTheTargetAndAnEmptySourceFallsBackToLiteralText() {
        val image = MarkdownParser.parse("![](a.png)")[0] as MdBlock.Image
        assertEquals("", image.alt)
        assertEquals("a.png", image.src)

        // `parseLink` rejects an empty destination, so `![alt]()` stays literal.
        val blocks = MarkdownParser.parse("![alt]()")
        assertEquals("![alt]()", blocks.filterIsInstance<MdBlock.Paragraph>().joinToString("") {
            it.spans.joinToString("") { span -> span.text }
        })
    }

    // ------------------------------------------------------------ retry policy

    @Test
    fun aFailedReadGetsABoundedNumberOfAutomaticReAttempts() {
        // Each failure grants the next attempt index — and once the budget is
        // spent the body waits for a tap instead of re-reading forever.
        assertEquals(1, MarkdownImages.Retry.nextAttempt(1))
        assertEquals(2, MarkdownImages.Retry.nextAttempt(2))
        assertEquals(3, MarkdownImages.Retry.nextAttempt(3))
        assertNull(MarkdownImages.Retry.nextAttempt(MarkdownImages.Retry.AUTO_ATTEMPTS + 1))
        // A read that never failed (or a negative index) schedules nothing.
        assertNull(MarkdownImages.Retry.nextAttempt(0))
        assertNull(MarkdownImages.Retry.nextAttempt(-1))
    }

    @Test
    fun theAutomaticReAttemptWaitsLongerEachTimeAndStaysBounded() {
        assertEquals(1_000L, MarkdownImages.Retry.delayMillis(1))
        assertEquals(2_000L, MarkdownImages.Retry.delayMillis(2))
        assertEquals(4_000L, MarkdownImages.Retry.delayMillis(3))
        assertEquals(4_000L, MarkdownImages.Retry.delayMillis(99))
        assertEquals(0L, MarkdownImages.Retry.delayMillis(0))
    }

    @Test
    fun onlyAReadableSourceWithAReaderOffersTapToRetry() {
        val reader = MarkdownImages.Reader { null }
        // The two fetchable shapes: a remote URL (no credentials) and a local
        // path through `GET /api/fs/read`.
        assertTrue(MarkdownImages.Retry.canRetry(reader, "https://images.example/chart.png"))
        assertTrue(MarkdownImages.Retry.canRetry(reader, "docs/chart.png"))
        // No reader wired: nothing to re-read, the alt text stays inert.
        assertFalse(MarkdownImages.Retry.canRetry(null, "docs/chart.png"))
        assertFalse(MarkdownImages.Retry.canRetry(null, "https://images.example/chart.png"))
        // Rejected by decision, not by failure — a retry could never succeed
        // (unsafe scheme, unknown extension, a format Android cannot decode, a
        // half-typed URL mid-stream).
        assertFalse(MarkdownImages.Retry.canRetry(reader, "javascript:alert(1)"))
        assertFalse(MarkdownImages.Retry.canRetry(reader, "ftp://host/a.png"))
        assertFalse(MarkdownImages.Retry.canRetry(reader, "notes.txt"))
        assertFalse(MarkdownImages.Retry.canRetry(reader, "icon.svg"))
        assertFalse(MarkdownImages.Retry.canRetry(reader, "https://"))
    }
}
