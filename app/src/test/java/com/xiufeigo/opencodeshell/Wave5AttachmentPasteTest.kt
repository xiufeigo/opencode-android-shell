package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.composer.AttachmentPolicy
import com.xiufeigo.opencodeshell.ui.session.composer.AttachmentPolicy.PasteKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W5-C B6: the paste rules.
 *
 * `shouldHandlePasteAsAttachment` (`composer/editor/interaction.ts:462-466`) gives
 * the attachment-vs-text split — a clipboard file/image item becomes an attachment,
 * text stays text — and the W5-C brief adds the bulk-text threshold: ≥ 8000
 * characters or ≥ 120 line breaks is inserted as one text chunk (i.e. it is part of
 * the prompt's text part, never a file part).
 */
class Wave5AttachmentPasteTest {

    @Test
    fun aClipboardAttachmentWinsOverItsTextForm() {
        assertEquals(PasteKind.ATTACHMENT, AttachmentPolicy.pasteKind(hasAttachmentItem = true, text = "x"))
        assertEquals(
            PasteKind.ATTACHMENT,
            AttachmentPolicy.pasteKind(hasAttachmentItem = true, text = null),
        )
    }

    @Test
    fun plainTextStaysPlainText() {
        assertEquals(PasteKind.TEXT, AttachmentPolicy.pasteKind(false, "hello\nworld"))
        assertEquals(PasteKind.NONE, AttachmentPolicy.pasteKind(false, ""))
        assertEquals(PasteKind.NONE, AttachmentPolicy.pasteKind(false, null))
    }

    @Test
    fun theCharacterThresholdIsEightThousand() {
        assertEquals(
            PasteKind.TEXT,
            AttachmentPolicy.pasteKind(false, "a".repeat(AttachmentPolicy.LARGE_PASTE_CHARS - 1)),
        )
        assertEquals(
            PasteKind.LARGE_TEXT,
            AttachmentPolicy.pasteKind(false, "a".repeat(AttachmentPolicy.LARGE_PASTE_CHARS)),
        )
        assertFalse(AttachmentPolicy.isLargePaste("a".repeat(AttachmentPolicy.LARGE_PASTE_CHARS - 1)))
        assertTrue(AttachmentPolicy.isLargePaste("a".repeat(AttachmentPolicy.LARGE_PASTE_CHARS)))
    }

    @Test
    fun theLineThresholdIsOneHundredAndTwenty() {
        // The threshold counts line BREAKS, so 121 lines (120 breaks) trigger it.
        val oneHundredAndTwentyLines = (1..120).joinToString("\n") { "line" }
        assertFalse(
            "119 breaks is still an ordinary paste",
            AttachmentPolicy.isLargePaste(oneHundredAndTwentyLines),
        )
        val oneMoreLine = oneHundredAndTwentyLines + "\nline"
        assertTrue(AttachmentPolicy.isLargePaste(oneMoreLine))
        assertEquals(PasteKind.LARGE_TEXT, AttachmentPolicy.pasteKind(false, oneMoreLine))
        assertEquals(
            "a 120-break paste needs no character count",
            PasteKind.LARGE_TEXT,
            AttachmentPolicy.pasteKind(false, "\n".repeat(AttachmentPolicy.LARGE_PASTE_LINES)),
        )
    }

    @Test
    fun eitherThresholdAloneIsEnough() {
        // 120 newlines but only ~120 characters — the line count triggers.
        assertTrue(AttachmentPolicy.isLargePaste("\n".repeat(AttachmentPolicy.LARGE_PASTE_LINES)))
        // A single 8000-character line — the character count triggers.
        assertTrue(AttachmentPolicy.isLargePaste("a".repeat(AttachmentPolicy.LARGE_PASTE_CHARS)))
    }
}
