package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.review.payloadBytes
import com.xiufeigo.opencodeshell.ui.session.review.stagedFileName
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pure halves of 「Open in app」's FileProvider hand-off
 * (`OpenInAppLaunch.kt`): the staged file name must stay inside the cache
 * staging root whatever the artifact path looks like, and `payloadBytes` is the
 * base64 / UTF-8 split `FileContent.encoding` defines.
 */
class OpenInAppStagingTest {

    private fun content(
        text: String,
        encoding: String? = null,
    ) = OpenCodeClient.FileContent(
        binary = encoding == "base64",
        content = text,
        diff = null,
        patch = null,
        encoding = encoding,
        mimeType = null,
    )

    @Test
    fun stagedFileNameIsTheBasenameForEveryPathFlavour() {
        assertEquals("a.kt", stagedFileName("src/a.kt"))
        assertEquals("a.kt", stagedFileName("C:\\w\\src\\a.kt"))
        assertEquals("a.kt", stagedFileName("/home/u/repo/a.kt"))
        assertEquals("b", stagedFileName("/home/u/repo/b/"))
    }

    @Test
    fun stagedFileNameFlattensSeparatorsAndFatIllegalCharacters() {
        // Separators inside the last segment become underscores (`b:c` → `b_c`).
        assertEquals("b_c", stagedFileName("x/a\\b:c"))
        assertEquals("q______", stagedFileName("q?*\"<>|"))
        // Only the basename survives, so `..` segments can never escape the root.
        assertEquals("artifact", stagedFileName(".."))
        assertEquals("artifact", stagedFileName("../.."))
        assertEquals("passwd", stagedFileName("foo/../../etc/passwd"))
        assertEquals("artifact", stagedFileName(""))
        assertEquals("artifact", stagedFileName("   "))
        assertEquals("artifact", stagedFileName("/"))
    }

    @Test
    fun payloadBytesDecodesBase64WhenTagged() {
        // "hi" base64.
        assertArrayEquals(byteArrayOf(104, 105), payloadBytes(content("aGk=", encoding = "base64")))
    }

    @Test
    fun payloadBytesIsUtf8ForEverythingElse() {
        assertArrayEquals("héllo".toByteArray(Charsets.UTF_8), payloadBytes(content("héllo")))
        assertArrayEquals("plain".toByteArray(Charsets.UTF_8), payloadBytes(content("plain", encoding = "utf-8")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun payloadBytesRejectsMalformedBase64() {
        payloadBytes(content("not base64!!", encoding = "base64"))
    }
}
