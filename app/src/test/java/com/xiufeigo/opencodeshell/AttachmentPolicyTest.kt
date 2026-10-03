package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.composer.AttachmentMime
import com.xiufeigo.opencodeshell.ui.session.composer.AttachmentPolicy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Base64

/**
 * M1.5 附件规则单测（纯 JVM）：data URL 打包、6 × 8MB 上限、mime 兜底、合并去重。
 *
 * These are the rules that used to live only inside `ChatView` (L468-569) and
 * could not be tested; the Compose composer now shares them.
 */
class AttachmentPolicyTest {

    private fun attachment(name: String, bytes: String = name) = AttachmentPolicy.attachment(
        mime = "text/plain",
        filename = name,
        bytes = bytes.toByteArray(),
    )

    @Test
    fun dataUrlUsesTheDataSchemeAndStandardBase64() {
        val url = AttachmentPolicy.dataUrl("image/png", byteArrayOf(1, 2, 3))
        assertEquals("data:image/png;base64," + Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)), url)
        assertTrue(url.startsWith("data:image/png;base64,"))
    }

    @Test
    fun dataUrlIsReadableByStandardBase64Decoders() {
        val payload = AttachmentPolicy.dataUrl("text/plain", "hi".toByteArray())
        assertEquals("data:text/plain;base64,aGk=", payload)
        val base64 = payload.substringAfter(',')
        assertEquals("hi", String(Base64.getDecoder().decode(base64)))
    }

    @Test
    fun attachmentCarriesMimeFilenameAndDataUrl() {
        val attachment = AttachmentPolicy.attachment("text/markdown", "plan.md", "x".toByteArray())
        assertEquals("text/markdown", attachment.mime)
        assertEquals("plan.md", attachment.filename)
        assertEquals("data:text/markdown;base64,eA==", attachment.dataUrl)
    }

    @Test
    fun sizeCapIsEightMegabytesAndInclusive() {
        assertEquals(8 * 1024 * 1024, AttachmentPolicy.MAX_ATTACHMENT_BYTES)
        assertFalse(AttachmentPolicy.isTooLarge(AttachmentPolicy.MAX_ATTACHMENT_BYTES))
        assertTrue(AttachmentPolicy.isTooLarge(AttachmentPolicy.MAX_ATTACHMENT_BYTES + 1))
        assertFalse(AttachmentPolicy.isTooLarge(0))
    }

    @Test
    fun declaredSizesAreLongAndFollowTheSameCap() {
        val cap = AttachmentPolicy.MAX_ATTACHMENT_BYTES.toLong()
        assertFalse(AttachmentPolicy.isTooLarge(cap))
        assertTrue(AttachmentPolicy.isTooLarge(cap + 1))
        // SAF sizes can exceed Int; a 3 GB file must not wrap into "small".
        assertTrue(AttachmentPolicy.isTooLarge(3L * 1024 * 1024 * 1024))
    }

    @Test
    fun readCappedKeepsAStreamUpToTheLimit() {
        val bytes = ByteArray(1024) { it.toByte() }
        val out = AttachmentPolicy.readCapped(bytes.inputStream())
        assertArrayEquals(bytes, out)
        // The cap itself is inclusive, exactly like isTooLarge.
        val atCap = ByteArray(AttachmentPolicy.MAX_ATTACHMENT_BYTES)
        assertEquals(AttachmentPolicy.MAX_ATTACHMENT_BYTES, AttachmentPolicy.readCapped(atCap.inputStream())?.size)
        assertEquals(0, AttachmentPolicy.readCapped(ByteArrayInputStream(ByteArray(0)))?.size)
    }

    @Test
    fun readCappedAbortsOnTheFirstBytePastTheLimit() {
        // The OOM path: an oversized stream must stop at the cap instead of
        // being slurped whole — null is the "too large" answer.
        val overCap = ByteArray(AttachmentPolicy.MAX_ATTACHMENT_BYTES + 1)
        assertNull(AttachmentPolicy.readCapped(overCap.inputStream()))
        // …and the abort must fire mid-stream, not after EOF: a stream that
        // keeps yielding past the cap is cut off at the cap.
        val endless = object : InputStream() {
            private var served = 0
            override fun read(): Int = if (served++ > AttachmentPolicy.MAX_ATTACHMENT_BYTES + 8192) -1 else 0
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = minOf(len, 64 * 1024)
                java.util.Arrays.fill(b, off, off + n, 1)
                return n
            }
        }
        assertNull(AttachmentPolicy.readCapped(endless))
        // A smaller custom limit proves the same rule without 8 MB of setup.
        assertNull(AttachmentPolicy.readCapped(ByteArray(100).inputStream(), limit = 99))
        assertEquals(99, AttachmentPolicy.readCapped(ByteArray(99).inputStream(), limit = 99)?.size)
    }

    @Test
    fun mergeAppendsAndCountsNothingDropped() {
        val merged = AttachmentPolicy.merge(listOf(attachment("a.txt")), listOf(attachment("b.txt")))
        assertEquals(listOf("a.txt", "b.txt"), merged.attachments.map { it.filename })
        assertEquals(0, merged.dropped)
    }

    @Test
    fun mergeDeduplicatesOnFilenameAndPayload() {
        val same = attachment("a.txt", "payload")
        val merged = AttachmentPolicy.merge(listOf(same), listOf(same))
        assertEquals(1, merged.attachments.size)
        assertEquals(0, merged.dropped)
    }

    @Test
    fun mergeKeepsDistinctPayloadsWithTheSameName() {
        val merged = AttachmentPolicy.merge(
            listOf(attachment("a.txt", "one")),
            listOf(attachment("a.txt", "two")),
        )
        assertEquals(2, merged.attachments.size)
    }

    @Test
    fun mergeCapsAtSixAndReportsTheDroppedCount() {
        val incoming = (1..8).map { attachment("f$it.txt") }
        val merged = AttachmentPolicy.merge(emptyList(), incoming)
        assertEquals(AttachmentPolicy.MAX_ATTACHMENTS, merged.attachments.size)
        assertEquals(listOf("f1.txt", "f2.txt", "f3.txt", "f4.txt", "f5.txt", "f6.txt"), merged.attachments.map { it.filename })
        assertEquals(2, merged.dropped)
    }

    @Test
    fun mergeCountsDroppedAgainstTheExistingChips() {
        val current = (1..5).map { attachment("c$it.txt") }
        val merged = AttachmentPolicy.merge(current, listOf(attachment("n1.txt"), attachment("n2.txt")))
        assertEquals(6, merged.attachments.size)
        assertEquals(1, merged.dropped)
    }

    @Test
    fun imagePayloadOnlyAcceptsImageDataUrls() {
        val png = AttachmentPolicy.attachment("image/png", "p.png", byteArrayOf(9, 8, 7))
        assertEquals(Base64.getEncoder().encodeToString(byteArrayOf(9, 8, 7)), AttachmentPolicy.imagePayload("image/png", png.dataUrl))
        assertNull(AttachmentPolicy.imagePayload("application/pdf", png.dataUrl))
        assertNull(AttachmentPolicy.imagePayload("image/png", "https://example.com/p.png"))
        assertNull(AttachmentPolicy.imagePayload("image/png", "data:image/png;base64,"))
        assertNull(AttachmentPolicy.imagePayload(null, png.dataUrl))
    }

    @Test
    fun mimeExtensionExtraction() {
        assertEquals("png", AttachmentMime.extensionOf("shot.PNG"))
        assertEquals("gz", AttachmentMime.extensionOf("archive.tar.gz"))
        assertEquals("md", AttachmentMime.extensionOf("a/b/c.md"))
        assertNull(AttachmentMime.extensionOf("Makefile"))
        assertNull(AttachmentMime.extensionOf(null))
        assertNull(AttachmentMime.extensionOf("trailing."))
    }

    @Test
    fun resolverTypeWinsWhenUsable() {
        var looked = false
        val mime = AttachmentMime.resolve("image/png", "shot.png") { looked = true; "image/jpeg" }
        assertEquals("image/png", mime)
        assertFalse("no fallback lookup when the resolver answered", looked)
    }

    @Test
    fun blankAndOctetStreamFallBackToTheExtension() {
        val jpeg = AttachmentMime.resolve(null, "shot.jpeg") { "image/jpeg" }
        assertEquals("image/jpeg", jpeg)
        val png = AttachmentMime.resolve(AttachmentPolicy.OCTET_STREAM, "shot.png") { "image/png" }
        assertEquals("image/png", png)
        val blank = AttachmentMime.resolve("", "shot.png") { "image/png" }
        assertEquals("image/png", blank)
    }

    @Test
    fun unknownExtensionFallsBackToOctetStream() {
        assertEquals(
            "application/octet-stream",
            AttachmentMime.resolve(null, "weird.zzz") { null },
        )
        assertEquals(
            "application/octet-stream",
            AttachmentMime.resolve(null, "Makefile") { "application/octet-stream" },
        )
        assertEquals("application/octet-stream", AttachmentMime.resolve(null, null) { null })
    }

    @Test
    fun imageDetectionIsMimePrefixBased() {
        assertTrue(AttachmentMime.isImage("image/webp"))
        assertFalse(AttachmentMime.isImage("application/pdf"))
        assertFalse(AttachmentMime.isImage(null))
    }

    @Test
    fun anImageReadThatYieldedNoBytesPacksAsBinaryInsteadOfAnEmptyImage() {
        // The SAF pick of a file that exists but was never written: an empty
        // `data:image/…` URL is neither previewable nor decodable by the server.
        assertEquals(AttachmentPolicy.OCTET_STREAM, AttachmentPolicy.packedMime("image/png", ByteArray(0)))
        assertEquals("image/png", AttachmentPolicy.packedMime("image/png", byteArrayOf(2)))
        // A zero-byte non-image file is a legitimate pick and keeps its mime.
        assertEquals("text/plain", AttachmentPolicy.packedMime("text/plain", ByteArray(0)))
        assertEquals(
            "data:text/plain;base64,",
            AttachmentPolicy.attachment("text/plain", "new.txt", ByteArray(0)).dataUrl,
        )
        // …and the degraded pick is a plain file chip, never a blank image frame.
        val empty = AttachmentPolicy.attachment(
            AttachmentPolicy.packedMime("image/png", ByteArray(0)),
            "new.png",
            ByteArray(0),
        )
        assertNull(AttachmentPolicy.imagePayload(empty.mime, empty.dataUrl))
        assertEquals(AttachmentPolicy.Chip.FILE, AttachmentPolicy.chip(empty.mime, empty.dataUrl))
    }

    @Test
    fun chipFallsBackToTheFileChipWithoutADecodablePayload() {
        assertEquals(
            AttachmentPolicy.Chip.IMAGE,
            AttachmentPolicy.chip("image/png", "data:image/png;base64,Ag=="),
        )
        // Empty read / payload-less draft: no payload means no thumbnail cache key.
        assertEquals(AttachmentPolicy.Chip.FILE, AttachmentPolicy.chip("image/png", "data:image/png;base64,"))
        assertEquals(AttachmentPolicy.Chip.FILE, AttachmentPolicy.chip("image/png", ""))
        assertEquals(AttachmentPolicy.Chip.FILE, AttachmentPolicy.chip("image/png", null))
        // Non-image mimes never render a thumbnail, decoded or not.
        assertEquals(AttachmentPolicy.Chip.FILE, AttachmentPolicy.chip("application/pdf", "data:application/pdf;base64,Ag=="))
        assertEquals(AttachmentPolicy.Chip.FILE, AttachmentPolicy.chip(null, null))
        // A decode Android refuses overrides an otherwise usable payload.
        assertEquals(
            AttachmentPolicy.Chip.FILE,
            AttachmentPolicy.chip("image/png", "data:image/png;base64,Ag==", decodeFailed = true),
        )
    }
}
