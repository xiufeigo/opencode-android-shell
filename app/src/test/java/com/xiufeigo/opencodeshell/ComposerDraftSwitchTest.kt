package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.composer.ComposerDrafts
import com.xiufeigo.opencodeshell.ui.session.composer.ComposerDrafts.DraftAttachment
import com.xiufeigo.opencodeshell.ui.session.composer.ComposerDrafts.MessageRef
import com.xiufeigo.opencodeshell.ui.session.composer.ComposerSubmission.toDraftAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P1-C: the draft survives every switch path (top-tab leave/return, drawer
 * session switch, home round-trip, session re-entry) as a full handoff —
 * text + attachments + message ref — and a failed send hands the same handoff
 * back. The screen/composer effects cannot run on the JVM, so this pins the
 * store contract those effects rely on: [ComposerDrafts.stash] on the way
 * out, plain readers + [ComposerDrafts.takeMessageRef] on the way back in,
 * [ComposerDrafts.restoreHandoff] on a failed send, [ComposerDrafts.appendLine]
 * on a review append.
 */
class ComposerDraftSwitchTest {

    private fun attachment(name: String = "shot.png") =
        DraftAttachment(mime = "image/png", filename = name, dataUrl = "data:image/png;base64,xx")

    @Before
    fun reset() {
        ComposerDrafts.reset()
    }

    // ------------------------------------------------------- switch out/in

    @Test
    fun stashRecordsTheWholeHandoffForTheOutgoingSession() {
        ComposerDrafts.stash(
            key = "ses_a",
            text = "half typed",
            attachments = listOf(attachment()),
            messageRef = MessageRef("msg_1", "quoted"),
        )

        assertEquals("half typed", ComposerDrafts.text("ses_a"))
        assertEquals(listOf(attachment()), ComposerDrafts.attachments("ses_a"))
        assertEquals(MessageRef("msg_1", "quoted"), ComposerDrafts.messageRef("ses_a"))
    }

    @Test
    fun aReturningSessionReadsItsStashBackWhileTheOtherSessionReadsEmpty() {
        ComposerDrafts.stash("ses_a", "a text", listOf(attachment("a.png")), MessageRef("msg_a"))
        ComposerDrafts.stash("ses_b", "b text")

        // Mount-adopt for ses_b: text + no attachments, no ref.
        assertEquals("b text", ComposerDrafts.text("ses_b"))
        assertTrue(ComposerDrafts.attachments("ses_b").isEmpty())
        assertNull(ComposerDrafts.messageRef("ses_b"))
        // ses_a's handoff sits untouched until it mounts again.
        assertEquals("a text", ComposerDrafts.text("ses_a"))
        assertEquals(listOf(attachment("a.png")), ComposerDrafts.attachments("ses_a"))
    }

    @Test
    fun stashingAnEmptyHandoffDropsTheKey() {
        ComposerDrafts.stash("ses_a", "typed")
        ComposerDrafts.stash("ses_a", "")

        assertEquals("", ComposerDrafts.text("ses_a"))
        assertTrue(ComposerDrafts.attachments("ses_a").isEmpty())
        assertNull(ComposerDrafts.messageRef("ses_a"))
    }

    @Test
    fun takeMessageRefConsumesSoATabReturnDoesNotReScroll() {
        ComposerDrafts.stash("ses_a", "typed", messageRef = MessageRef("msg_1"))

        assertEquals(MessageRef("msg_1"), ComposerDrafts.takeMessageRef("ses_a"))
        // Consumed: the text and attachments stay for later mounts, the ref is gone.
        assertNull(ComposerDrafts.takeMessageRef("ses_a"))
        assertNull(ComposerDrafts.messageRef("ses_a"))
        assertEquals("typed", ComposerDrafts.text("ses_a"))
    }

    @Test
    fun takeMessageRefOnAnUnknownKeyIsNull() {
        assertNull(ComposerDrafts.takeMessageRef("ses_missing"))
    }

    // ------------------------------------------------------- failed send

    @Test
    fun aFailedSendHandsBackAttachmentsToo() {
        ComposerDrafts.restoreHandoff("ses_a", "keep me", listOf(attachment()))

        assertTrue(ComposerDrafts.pendingRestore("ses_a"))
        assertEquals("keep me", ComposerDrafts.takeRestore("ses_a", ""))
        assertEquals(listOf(attachment()), ComposerDrafts.attachments("ses_a"))
    }

    @Test
    fun anAttachmentOnlySendIsRestoredDespiteBlankText() {
        ComposerDrafts.restoreHandoff("ses_a", "", listOf(attachment()))

        assertTrue(ComposerDrafts.pendingRestore("ses_a"))
        assertEquals(listOf(attachment()), ComposerDrafts.attachments("ses_a"))
    }

    @Test
    fun aFailedSendPreservesTheStashedMessageRefTheViewModelNeverKnew() {
        ComposerDrafts.setMessageRef("ses_a", MessageRef("msg_9", "quoted"))
        ComposerDrafts.restoreHandoff("ses_a", "keep me", listOf(attachment()))

        assertEquals(MessageRef("msg_9", "quoted"), ComposerDrafts.messageRef("ses_a"))
    }

    @Test
    fun restoreHandoffOfNothingIsANoOp() {
        ComposerDrafts.restoreHandoff("ses_a", "")
        assertEquals("", ComposerDrafts.text("ses_a"))
    }

    // ------------------------------------------------------- review append

    @Test
    fun aReviewAppendKeepsTheStagedAttachmentsAndRef() {
        ComposerDrafts.set("ses_a", "typed")
        ComposerDrafts.setAttachments("ses_a", listOf(attachment()))
        ComposerDrafts.setMessageRef("ses_a", MessageRef("msg_1"))

        ComposerDrafts.appendLine("ses_a", "quoted line")

        assertEquals("typed\nquoted line", ComposerDrafts.text("ses_a"))
        assertEquals(listOf(attachment()), ComposerDrafts.attachments("ses_a"))
        assertEquals(MessageRef("msg_1"), ComposerDrafts.messageRef("ses_a"))
        assertTrue(ComposerDrafts.pendingRestore("ses_a"))
    }

    @Test
    fun aReviewAppendOnAnEmptyDraftPlantsTheLine() {
        ComposerDrafts.appendLine("ses_a", "quoted line")

        assertEquals("quoted line", ComposerDrafts.text("ses_a"))
        assertTrue(ComposerDrafts.pendingRestore("ses_a"))
    }

    @Test
    fun aBlankReviewAppendIsANoOp() {
        ComposerDrafts.set("ses_a", "typed")
        ComposerDrafts.appendLine("ses_a", "   ")

        assertEquals("typed", ComposerDrafts.text("ses_a"))
    }

    // ------------------------------------------- failed queue-edit conversion

    @Test
    fun dataEntriesConvertToDraftAttachmentsButFileRefsStayTextOnly() {
        val embedded = com.xiufeigo.opencodeshell.ui.session.composer.ComposerSubmission.FileEntry(
            uri = "data:image/png;base64,xx",
            mime = "image/png",
            name = "shot.png",
        ).toDraftAttachment()
        assertEquals("image/png", embedded?.mime)
        assertEquals("shot.png", embedded?.filename)

        val untyped = com.xiufeigo.opencodeshell.ui.session.composer.ComposerSubmission.FileEntry(
            uri = "data:,hello",
        ).toDraftAttachment()
        assertEquals("file", untyped?.filename)

        assertNull(
            "a file:// mention carries no bytes — the failed confirm restores it as text only",
            com.xiufeigo.opencodeshell.ui.session.composer.ComposerSubmission.FileEntry(
                uri = "file:///work/main.kt",
                name = "main.kt",
            ).toDraftAttachment(),
        )
    }
}
