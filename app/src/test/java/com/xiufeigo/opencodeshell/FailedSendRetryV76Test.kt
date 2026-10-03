package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.composer.ComposerDrafts
import com.xiufeigo.opencodeshell.ui.session.composer.ComposerHistory
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v76 (emulator-5558, `research/v76_verify/5558/40_error.png`): a prompt sent
 * while the app has **no connection** vanished.
 *
 * The measured run: the connection dropped mid-session, the composer reported
 * 「Not connected — open the Terminal tab once to load the server config.」 on
 * the error card, and the message the user had just sent was gone — no user
 * bubble in the timeline, an empty editor, nothing to retry. Every *other*
 * failure path pays the write-back (`failSubmission`,
 * `composer/submit.ts:407-418`: the payload returns to the composer and the
 * history entry recorded for it is dropped). The one path that did not was
 * `ChatViewModel.submit`'s own early return for a missing transport, and the
 * composer had already handed its draft slot over by then (`Composer.submit`),
 * so nothing was left to hand the text back through.
 *
 * Two contracts are pinned here:
 *  - the draft slot's hand-over runs **before** the send is dispatched, so a
 *    write-back that happens *during* the call survives it (behavioral part);
 *  - the refusal path writes the payload back and names the failure (source
 *    pins — `ChatViewModel` needs a live server config to construct, and the
 *    composer is a composable, so the wiring is pinned the way the rest of this
 *    codebase pins VM/UI wiring: by reading the function).
 */
class FailedSendRetryV76Test {

    // ------------------------------------------------ the hand-back contract

    @Test
    fun aRejectedSendComesBackWithTextAttachmentsAndNoHistoryEntry() {
        try {
            val key = ComposerDrafts.key("ses_fail")
            val attachments = listOf(
                ComposerDrafts.DraftAttachment("image/png", "shot.png", "data:image/png;base64,AA"),
            )
            ComposerDrafts.set(key, "hello server")
            ComposerHistory.of(key).add("hello server")
            assertEquals("hello server", ComposerDrafts.text(key))
            assertEquals(listOf("hello server"), ComposerHistory.of(key).entries())

            // The composer hands the slot over as it dispatches the send.
            ComposerDrafts.clear(key)
            assertEquals("", ComposerDrafts.text(key))

            // The rejection is answered synchronously: `restoreDraft` = the
            // P1-C handoff write-back + the history entry the submit recorded.
            ComposerDrafts.restoreHandoff(key, "hello server", attachments)
            ComposerHistory.of(key).remove("hello server")

            assertEquals("hello server", ComposerDrafts.text(key))
            assertTrue("the composer must adopt it", ComposerDrafts.pendingRestore(key))
            assertEquals(attachments, ComposerDrafts.attachments(key))
            assertTrue("the failed prompt is not a history entry", ComposerHistory.of(key).entries().isEmpty())

            // The mounted composer takes it back through the one-shot handshake
            // (`Composer`'s restore effect), and only once — into the editor the
            // send cleared (v84 P2's second argument).
            assertEquals("hello server", ComposerDrafts.takeRestore(key, ""))
            assertFalse(ComposerDrafts.pendingRestore(key))
        } finally {
            ComposerDrafts.reset()
            ComposerHistory.reset()
        }
    }

    @Test
    fun anAttachmentOnlyRejectionStillComesBack() {
        try {
            val key = ComposerDrafts.key("ses_fail")
            val attachments = listOf(
                ComposerDrafts.DraftAttachment("text/plain", "notes.txt", "data:text/plain;aGk="),
            )
            ComposerDrafts.clear(key)
            ComposerDrafts.restoreHandoff(key, "", attachments)
            assertEquals("", ComposerDrafts.text(key))
            assertEquals(attachments, ComposerDrafts.attachments(key))
            assertTrue(ComposerDrafts.pendingRestore(key))
        } finally {
            ComposerDrafts.reset()
        }
    }

    @Test
    fun aBlankRejectionWritesNothing() {
        // A payload with no text and no attachments has nothing to hand back —
        // the refusal must not leave an empty draft (or a restore flag) behind.
        try {
            val key = ComposerDrafts.key("ses_fail")
            ComposerDrafts.clear(key)
            ComposerDrafts.restoreHandoff(key, "")
            assertEquals("", ComposerDrafts.text(key))
            assertFalse(ComposerDrafts.pendingRestore(key))
        } finally {
            ComposerDrafts.reset()
        }
    }

    // ------------------------------------------------------------- the wiring

    private fun source(relative: String): String = listOf(
        File("src/main/java/com/xiufeigo/opencodeshell/$relative"),
        File("app/src/main/java/com/xiufeigo/opencodeshell/$relative"),
    ).firstOrNull { it.isFile }?.readText() ?: error("missing $relative")

    @Test
    fun aSendWithNoTransportHandsThePayloadBack() {
        val vm = source("ChatViewModel.kt")
        val refusal = vm.substringAfter("fun submit(")
            .substringBefore("if (text.isBlank() && attachments.isEmpty()) return")
        assertTrue(
            "a submit that cannot reach a server must return the text and attachments",
            refusal.contains("restoreDraft(id, text, shell, attachments)"),
        )
        assertTrue(
            "the failure has to be named, not just undone",
            refusal.contains("ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.ERROR"),
        )
        assertTrue(
            "a live client with no transport still needs the line",
            refusal.contains("requireClient()"),
        )
        // One sentence, three locales' worth of the same card vocabulary: the
        // literal exists in exactly one place (the named constant).
        assertEquals(
            "the not-connected line must live in one place only",
            1,
            vm.split("Not connected — open the Terminal tab once to load the server config.").size - 1,
        )
        assertTrue(vm.split("NOT_CONNECTED_LINE").size - 1 >= 3)
    }

    @Test
    fun aQueueEditThatCannotReachAServerHandsTheReplacementBack() {
        // The same shape one function over: the composer handed its draft over
        // for the edit, so a confirm with no transport owes it back instead of
        // eating the replacement.
        val vm = source("ChatViewModel.kt")
        val refusal = vm.substringAfter("fun confirmQueueEdit(").substringBefore("scope.launch {")
        assertTrue(refusal.contains("ComposerDrafts.restoreHandoff("))
        assertTrue(refusal.contains("replacement.files.mapNotNull { it.toDraftAttachment() }"))
    }

    @Test
    fun theComposerHandsTheDraftOverBeforeItDispatchesTheSend() {
        val composer = source("ui/session/composer/Composer.kt")
        val body = composer.substringAfter("fun submit() {").substringBefore("fun pasteFromClipboard()")
        val clear = "ComposerDrafts.clear(draftKey)"
        val dispatch = body.indexOf("val structured = submitStructured")
        assertTrue("the dispatch must be found", dispatch > 0)
        assertTrue(
            "the clear must run before the send is dispatched: a rejection answered " +
                "during that call restores into the slot and would be wiped after it",
            body.indexOf(clear) in 0 until dispatch,
        )
        assertTrue(
            "no clear may run after the dispatch (the btw/alias branches are before it)",
            body.lastIndexOf(clear) < dispatch,
        )
    }
}
