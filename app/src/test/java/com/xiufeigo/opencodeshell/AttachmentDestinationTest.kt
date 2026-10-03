package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.composer.AttachmentDestination
import com.xiufeigo.opencodeshell.ui.session.composer.AttachmentDestination.Input
import com.xiufeigo.opencodeshell.ui.session.composer.AttachmentDestination.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 附件目的地三分支（上游 `composer/attachments/destination.ts:11-31` +
 * `attachments.ts:65-70` `add()` / `:208-211` `native()`）的纯 JVM 钉。
 */
class AttachmentDestinationTest {

    private val vision = Input(image = true, pdf = false)
    private val reader = Input(image = true, pdf = true)
    private val textOnly = Input(image = false, pdf = false)

    @Test
    fun nativeMirrorsUpstreamsFourImageTypesAndPdf() {
        for (mime in listOf("image/png", "image/jpeg", "image/gif", "image/webp")) {
            assertTrue(mime, AttachmentDestination.isNative(mime, vision))
            assertFalse(mime, AttachmentDestination.isNative(mime, textOnly))
        }
        assertTrue(AttachmentDestination.isNative("application/pdf", reader))
        assertFalse(AttachmentDestination.isNative("application/pdf", vision))
        assertFalse("image/svg is not a native input", AttachmentDestination.isNative("image/svg+xml", reader))
        assertFalse(AttachmentDestination.isNative("text/plain", reader))
        assertFalse(AttachmentDestination.isNative("application/octet-stream", reader))
    }

    @Test
    fun inlineWinsForNativeMediaWithinTheInlineCap() {
        assertEquals(
            Route.INLINE,
            AttachmentDestination.route(
                mime = "image/png",
                size = 1024,
                input = vision,
                local = true,
                hasSourcePath = true,
            ),
        )
    }

    @Test
    fun nativeMediaPastTheInlineCapFallsThroughToThePathRoutes() {
        assertEquals(
            Route.LOCAL_PATH,
            AttachmentDestination.route(
                mime = "image/png",
                size = AttachmentDestination.MAX_INLINE_BYTES + 1,
                input = vision,
                local = true,
                hasSourcePath = true,
            ),
        )
        assertEquals(
            Route.UPLOAD,
            AttachmentDestination.route(
                mime = "image/png",
                size = AttachmentDestination.MAX_INLINE_BYTES + 1,
                input = vision,
                local = false,
                hasSourcePath = true,
            ),
        )
    }

    @Test
    fun localPathNeedsBothServerLocalityAndASourcePath() {
        assertEquals(
            Route.LOCAL_PATH,
            AttachmentDestination.route(
                mime = "text/plain",
                size = 10,
                input = textOnly,
                local = true,
                hasSourcePath = true,
            ),
        )
        assertEquals(
            "content:// picks have no resolvable path",
            Route.UPLOAD,
            AttachmentDestination.route(
                mime = "text/plain",
                size = 10,
                input = textOnly,
                local = true,
                hasSourcePath = false,
            ),
        )
        assertEquals(
            Route.UPLOAD,
            AttachmentDestination.route(
                mime = "text/plain",
                size = 10,
                input = textOnly,
                local = false,
                hasSourcePath = true,
            ),
        )
    }

    @Test
    fun nonNativeSmallFilesStillUploadWhenNoPathIsAvailable() {
        assertEquals(
            Route.UPLOAD,
            AttachmentDestination.route(
                mime = "application/zip",
                size = 1,
                input = reader,
                local = false,
                hasSourcePath = false,
            ),
        )
    }

    @Test
    fun inlineCapIsUpstreamsTwentyMegabytes() {
        assertEquals(20L * 1024 * 1024, AttachmentDestination.MAX_INLINE_BYTES)
    }
}
