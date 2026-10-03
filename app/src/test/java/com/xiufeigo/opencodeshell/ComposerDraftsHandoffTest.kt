package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.composer.ComposerDrafts
import com.xiufeigo.opencodeshell.ui.session.composer.ComposerDrafts.DraftAttachment
import com.xiufeigo.opencodeshell.ui.session.composer.ComposerDrafts.MessageRef
import com.xiufeigo.opencodeshell.ui.session.composer.toDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * U-3: the draft is a handoff (text + file attachments + message ref), aligned
 * with upstream `session/handoff.ts` (`setSessionHandoff` `{files}` +
 * user message), plus the `appendComposerText` adoption path and the JSON codec
 * (old text-only payloads still decode).
 */
class ComposerDraftsHandoffTest {

    private fun attachment(name: String = "shot.png") =
        DraftAttachment(mime = "image/png", filename = name, dataUrl = "data:image/png;base64,xx")

    @Before
    fun reset() {
        ComposerDrafts.reset()
    }

    // ------------------------------------------------------- handoff fields

    @Test
    fun keystrokesNeverClobberTheHandoff() {
        ComposerDrafts.setAttachments("ses_a", listOf(attachment()))
        ComposerDrafts.setMessageRef("ses_a", MessageRef("msg_1", "hello"))
        ComposerDrafts.set("ses_a", "typed")
        ComposerDrafts.set("ses_a", "typed more")

        assertEquals("typed more", ComposerDrafts.text("ses_a"))
        assertEquals(listOf(attachment()), ComposerDrafts.attachments("ses_a"))
        assertEquals(MessageRef("msg_1", "hello"), ComposerDrafts.messageRef("ses_a"))
    }

    @Test
    fun handoffHalvesReadEmptyByDefaultAndClearExplicitly() {
        assertTrue(ComposerDrafts.attachments("ses_new").isEmpty())
        assertNull(ComposerDrafts.messageRef("ses_new"))

        ComposerDrafts.setAttachments("ses_new", listOf(attachment()))
        ComposerDrafts.setAttachments("ses_new", emptyList())
        assertTrue(ComposerDrafts.attachments("ses_new").isEmpty())

        ComposerDrafts.setMessageRef("ses_new", MessageRef("msg_1"))
        ComposerDrafts.setMessageRef("ses_new", null)
        assertNull(ComposerDrafts.messageRef("ses_new"))
    }

    @Test
    fun aFailedSendHandsBackTheFullHandoff() {
        ComposerDrafts.restore(
            key = "ses_a",
            text = "keep me",
            attachments = listOf(attachment()),
            messageRef = MessageRef("msg_9", "quoted"),
        )

        assertTrue(ComposerDrafts.pendingRestore("ses_a"))
        assertEquals("keep me", ComposerDrafts.takeRestore("ses_a", ""))
        // The handoff survives the take: the restore effect re-seats all three.
        assertEquals(listOf(attachment()), ComposerDrafts.attachments("ses_a"))
        assertEquals(MessageRef("msg_9", "quoted"), ComposerDrafts.messageRef("ses_a"))
    }

    @Test
    fun clearDropsTheWholeHandoff() {
        ComposerDrafts.set("ses_a", "sent")
        ComposerDrafts.setAttachments("ses_a", listOf(attachment()))
        ComposerDrafts.setMessageRef("ses_a", MessageRef("msg_1"))
        ComposerDrafts.clear("ses_a")

        assertEquals("", ComposerDrafts.text("ses_a"))
        assertTrue(ComposerDrafts.attachments("ses_a").isEmpty())
        assertNull(ComposerDrafts.messageRef("ses_a"))
    }

    // ------------------------------------------------------------- append

    @Test
    fun appendContractIsHonoredByTheViewModel() {
        // `ChatViewModel.appendComposerText` (F-2 review agent's minimal
        // contract) delegates to `restore`, so the composer adopts the text:
        // created when absent, never sent.
        ComposerDrafts.restore("ses_a", "hello")
        assertEquals("hello", ComposerDrafts.text("ses_a"))
        assertTrue(ComposerDrafts.pendingRestore("ses_a"))
        assertEquals("hello", ComposerDrafts.takeRestore("ses_a", ""))
    }

    // ---------------------------------------------------------------- move

    @Test
    fun moveRelocatesTheWholeHandoffToTheNewSession() {
        ComposerDrafts.set("ses_old", "draft")
        ComposerDrafts.setAttachments("ses_old", listOf(attachment()))
        ComposerDrafts.setMessageRef("ses_old", MessageRef("msg_1", "quoted"))

        ComposerDrafts.move("ses_old", "ses_new")

        assertEquals("", ComposerDrafts.text("ses_old"))
        assertEquals("draft", ComposerDrafts.text("ses_new"))
        assertEquals(listOf(attachment()), ComposerDrafts.attachments("ses_new"))
        assertEquals(MessageRef("msg_1", "quoted"), ComposerDrafts.messageRef("ses_new"))
    }

    @Test
    fun moveWithAMissingSourceIsANoOp() {
        ComposerDrafts.set("ses_target", "stays")
        ComposerDrafts.move("ses_missing", "ses_target")
        assertEquals("stays", ComposerDrafts.text("ses_target"))
    }

    // ---------------------------------------------------------------- JSON

    @Test
    fun jsonRoundTripsTheFullHandoff() {
        val draft = ComposerDrafts.Draft(
            text = "hello",
            attachments = listOf(attachment("a.png"), attachment("b.jpg")),
            messageRef = MessageRef("msg_3", "excerpt"),
        )
        val decoded = with(ComposerDrafts) { draftFromJson(draft.toJson()) }

        assertEquals(draft, decoded)
    }

    @Test
    fun jsonNeverPersistsTheTransientRestoreFlag() {
        val draft = ComposerDrafts.Draft(text = "x", pendingRestore = true)
        val decoded = with(ComposerDrafts) { draftFromJson(draft.toJson()) }

        assertEquals("x", decoded?.text)
        assertFalse(decoded?.pendingRestore ?: true)
    }

    @Test
    fun oldTextOnlyPayloadsStillDecode() {
        val decoded = with(ComposerDrafts) { draftFromJson("{\"text\":\"legacy\"}") }

        assertEquals("legacy", decoded?.text)
        assertTrue(decoded?.attachments.orEmpty().isEmpty())
        assertNull(decoded?.messageRef)
    }

    @Test
    fun blankOrCorruptPayloadsDecodeToNull() {
        with(ComposerDrafts) {
            assertNull(draftFromJson(null))
            assertNull(draftFromJson(""))
            assertNull(draftFromJson("   "))
            assertNull(draftFromJson("not json {"))
        }
    }

    // ---------------------------------------------------------- converters

    @Test
    fun attachmentConvertersRoundTrip() {
        val vm = ChatViewModel.Attachment(mime = "text/plain", filename = "n.txt", dataUrl = "data:,x")
        with(vm.toDraft()) {
            assertEquals("text/plain", mime)
            assertEquals("n.txt", filename)
            assertEquals("data:,x", dataUrl)
            assertEquals(vm, toAttachment())
        }
    }
}
