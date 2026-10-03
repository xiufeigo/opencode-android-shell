package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.composer.ComposerDrafts
import com.xiufeigo.opencodeshell.ui.session.composer.ComposerDrafts.DraftAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * v78 OBS-1 (emulator-5558): a composer draft leaks into a newly created
 * session.
 *
 * Unsent text sat in session A's composer; after tapping `+` the new session
 * B opened with A's text (`round2 cold`) already in its editor. Drafts are
 * per-session: every store write is keyed by the session it belongs to (v16
 * slots, v58 key-bound flush, v68 isolation) and B holds nothing of its own —
 * so on the draft-key switch the composer must not keep showing text, and
 * [ComposerDrafts.dropForeignSeed] is that rule: text on screen with nothing
 * in the store and no restore armed is another session's, and a new session
 * opens empty.
 *
 * Untouched on purpose: a failed send arms `pendingRestore` and still adopts
 * through its own effect, a returning session still finds its own draft, and
 * after a process death the empty store keeps (rather than wipes) the
 * session's own slot restore.
 */
class ComposerDraftForeignSeedV78Test {

    private fun attachment(name: String = "shot.png") =
        DraftAttachment(mime = "image/png", filename = name, dataUrl = "data:image/png;base64,iVBORw0=")

    @Before
    fun reset() {
        ComposerDrafts.reset()
    }

    @Test
    fun theLeakShapeIsDropped() {
        // Session A holds its unsent text; B holds nothing — yet B's editor
        // shows A's line. That is the OBS-1 frame, and it must reset.
        ComposerDrafts.set("ses_a", "round2 cold")

        assertTrue(ComposerDrafts.dropForeignSeed("ses_b", "round2 cold"))
    }

    @Test
    fun anAlreadyEmptyEditorNeedsNoReset() {
        // The common mount: nothing to drop, and no write that could restart
        // the input connection.
        ComposerDrafts.set("ses_a", "round2 cold")

        assertFalse(ComposerDrafts.dropForeignSeed("ses_b", ""))
    }

    @Test
    fun aFailedSendStillAdoptsThroughItsOwnEffect() {
        // The intentional path: B's own send failed and handed the payload
        // back. The restore effect (not the switch reset) owns this frame.
        ComposerDrafts.set("ses_a", "round2 cold")
        ComposerDrafts.restoreHandoff("ses_b", "try again", listOf(attachment()))

        assertFalse(ComposerDrafts.dropForeignSeed("ses_b", "try again"))
        // …and the armed payload survives the question untouched (the editor is
        // the empty one a failed send leaves behind — v84 P2's second argument).
        assertEquals("try again", ComposerDrafts.takeRestore("ses_b", ""))
    }

    @Test
    fun aReturningSessionKeepsItsOwnDraft() {
        // B was typed in before and still holds its text: switching back must
        // not blank it.
        ComposerDrafts.set("ses_a", "round2 cold")
        ComposerDrafts.set("ses_b", "b keeps this")

        assertFalse(ComposerDrafts.dropForeignSeed("ses_b", "b keeps this"))
        assertEquals("b keeps this", ComposerDrafts.text("ses_b"))
    }

    @Test
    fun aColdStartSlotRestoreIsKept() {
        // Drafts live in this process: after a process death the store is
        // empty while the editor slot still restores the session's own text.
        // With nothing anywhere else, that restore is kept, not wiped.
        assertFalse(ComposerDrafts.dropForeignSeed("ses_b", "round2 cold"))
    }

    @Test
    fun anAttachmentOnlyStashLeavesTheEmptyEditorAlone() {
        // A switch stashed chips but no text under B: the editor is already
        // empty (nothing to drop) and the halves still adopt through their
        // own bridges.
        ComposerDrafts.set("ses_a", "round2 cold")
        ComposerDrafts.setAttachments("ses_b", listOf(attachment()))

        assertFalse(ComposerDrafts.dropForeignSeed("ses_b", ""))
        assertEquals(listOf(attachment()), ComposerDrafts.attachments("ses_b"))
    }

    @Test
    fun theComposerResetsOnEveryDraftKeySwitch() {
        // The wiring itself: the v67 satellite reset is where a switch sheds
        // session-scoped editor state, so the foreign-seed drop lives there —
        // next to the token/popover resets, ahead of the restore adopt.
        val composer = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt")
            .replace(Regex("\\s+"), " ")
        assertTrue(
            "the draft-key switch must drop a foreign seed: a new session with no " +
                "draft of its own opens with the previous session's text otherwise",
            composer.contains("if (ComposerDrafts.dropForeignSeed(draftKey, value.text))"),
        )
    }

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?.replace("\r\n", "\n")
            ?: error("missing $relative")
}
