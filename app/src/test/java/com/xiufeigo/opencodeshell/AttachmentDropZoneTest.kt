package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.composer.AttachmentDropPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 拖放上传 dropzone（上游 `composer/dropzone.tsx` + `attachments.ts` `handleDrop`）：
 * 纯规则 + 源码钉，保证「拖入文件 → `AttachmentPolicy.merge`（不替换、不超 6）」。
 */
class AttachmentDropZoneTest {

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText()
    }

    // ------------------------------------------------------------- pure rules

    @Test
    fun acceptsUriListAndWildcardDrags() {
        assertTrue(AttachmentDropPolicy.accepts(setOf("text/uri-list")))
        assertTrue(AttachmentDropPolicy.accepts(setOf("TEXT/URI-LIST")))
        assertTrue(AttachmentDropPolicy.accepts(setOf("*/*")))
        assertTrue(AttachmentDropPolicy.accepts(setOf("image/png", "text/uri-list")))
    }

    @Test
    fun rejectsPlainTextAndBareFileMimes() {
        assertFalse(AttachmentDropPolicy.accepts(emptySet()))
        assertFalse(AttachmentDropPolicy.accepts(setOf("text/plain")))
        assertFalse(AttachmentDropPolicy.accepts(setOf("image/png")))
        assertFalse(AttachmentDropPolicy.accepts(setOf("application/pdf")))
    }

    @Test
    fun onlyContentAndFileUrisAreAttachments() {
        assertTrue(AttachmentDropPolicy.isAttachmentUri("content://com.android.providers.downloads/a"))
        assertTrue(AttachmentDropPolicy.isAttachmentUri("file:///sdcard/a.png"))
        assertFalse(AttachmentDropPolicy.isAttachmentUri("https://example.com/a.png"))
        assertFalse(AttachmentDropPolicy.isAttachmentUri("data:image/png;base64,AAAA"))
        assertFalse(AttachmentDropPolicy.isAttachmentUri(""))
    }

    // ------------------------------------------------------------- source pins

    @Test
    fun dropZoneAttachesAComposableDragAndDropTargetNode() {
        val zone = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/AttachmentDropZone.kt")
        assertTrue(zone.contains("DragAndDropModifierNode("))
        assertTrue(zone.contains("delegate("))
        assertTrue("gate on the URI-list flavour", zone.contains("AttachmentDropPolicy.accepts(event.mimeTypes())"))
        assertTrue("drop reads ClipData URIs", zone.contains("toAndroidDragEvent().clipData"))
        assertTrue("overlay is keyed off the shared string", zone.contains("R.string.oc_composer_drop_files"))
    }

    @Test
    fun composerDropIntakeGoesThroughTheSharedMergePolicy() {
        val composer = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt")
        val drop = composer
            .substringAfter("fun dropUris(")
            .substringBefore("\n    }\n")
        assertTrue("reads via the shared reader", drop.contains("AttachmentUploads.start("))
        assertTrue("must merge, never replace", drop.contains("AttachmentPolicy.merge(attachments, result.attachments)"))
        assertTrue("cap breach is toasted", drop.contains("R.string.chat_attach_limit"))
        assertTrue("oversized picks are toasted", drop.contains("R.string.chat_attach_too_large"))
        assertTrue("hands the post-merge list to the screen", drop.contains("pasteAttachments(merged.attachments)"))
        assertFalse("no list replacement", drop.contains("attachments = result.attachments"))
    }

    @Test
    fun theComposerCardIsTheDropSurface() {
        val composer = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt")
        assertTrue(composer.contains("AttachmentDropZone("))
        assertTrue(composer.contains("onUris = ::dropUris"))
    }
}
