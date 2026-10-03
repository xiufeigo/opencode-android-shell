package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.composer.ComposerDrafts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * W5-C B8: one draft per session key, and the "the send failed, give the text
 * back" handshake (`composer/submit.ts:407-418` `failSubmission` / `restore()`).
 *
 * The composer writes [ComposerDrafts.set] on every keystroke and reads the text
 * back when it (re)mounts, so a screen switch — or a whole process of editing
 * another session — cannot lose the draft.
 */
class Wave5ComposerDraftsTest {

    @Before
    fun reset() {
        ComposerDrafts.reset()
    }

    @Test
    fun draftsAreKeptPerSessionKey() {
        ComposerDrafts.set("ses_a", "for a")
        ComposerDrafts.set("ses_b", "for b")

        assertEquals("for a", ComposerDrafts.text("ses_a"))
        assertEquals("for b", ComposerDrafts.text("ses_b"))
        // A key that was never written reads as empty, never null-poisoned.
        assertEquals("", ComposerDrafts.text("ses_c"))
    }

    @Test
    fun aBlankSessionIdFallsBackToTheSharedSlot() {
        assertEquals(ComposerDrafts.DEFAULT_KEY, ComposerDrafts.key(null))
        assertEquals(ComposerDrafts.DEFAULT_KEY, ComposerDrafts.key(""))
        assertEquals(ComposerDrafts.DEFAULT_KEY, ComposerDrafts.key("   "))
        assertEquals("ses_x", ComposerDrafts.key("ses_x"))
    }

    @Test
    fun writingTheSameTextTwiceIsANoOp() {
        ComposerDrafts.set("ses_a", "hello")
        val first = ComposerDrafts.state.value
        ComposerDrafts.set("ses_a", "hello")
        assertEquals(first, ComposerDrafts.state.value)
    }

    @Test
    fun aFailedSendHandsTheTextBackExactlyOnce() {
        ComposerDrafts.set("ses_a", "")
        ComposerDrafts.restore("ses_a", "keep me")

        assertTrue(ComposerDrafts.pendingRestore("ses_a"))
        assertEquals("keep me", ComposerDrafts.text("ses_a"))
        // The composer consumes it; a second read must not resurrect the text.
        // The second argument is the editor's own text — the failed send cleared
        // it, so the handback lands (v84 P2).
        assertEquals("keep me", ComposerDrafts.takeRestore("ses_a", ""))
        assertFalse(ComposerDrafts.pendingRestore("ses_a"))
        assertNull(ComposerDrafts.takeRestore("ses_a", ""))
        // …but the draft itself stays, so the editor still shows it.
        assertEquals("keep me", ComposerDrafts.text("ses_a"))
    }

    @Test
    fun restoringBlankTextIsIgnored() {
        ComposerDrafts.restore("ses_a", "")
        assertFalse(ComposerDrafts.pendingRestore("ses_a"))
        assertNull(ComposerDrafts.takeRestore("ses_a", ""))
    }

    @Test
    fun typingAfterAFailureClearsTheRestoreFlag() {
        ComposerDrafts.restore("ses_a", "failed")
        ComposerDrafts.set("ses_a", "failed plus more")
        assertFalse("a live edit wins over the restore", ComposerDrafts.pendingRestore("ses_a"))
        assertEquals("failed plus more", ComposerDrafts.text("ses_a"))
    }

    @Test
    fun aSentPromptDropsItsDraft() {
        ComposerDrafts.set("ses_a", "sent")
        ComposerDrafts.clear("ses_a")
        assertEquals("", ComposerDrafts.text("ses_a"))
        assertNull(ComposerDrafts.takeRestore("ses_a", ""))
    }

    @Test
    fun oneSessionsRestoreNeverLeaksIntoAnother() {
        ComposerDrafts.set("ses_a", "a draft")
        ComposerDrafts.restore("ses_b", "b failed")
        assertFalse(ComposerDrafts.pendingRestore("ses_a"))
        assertTrue(ComposerDrafts.pendingRestore("ses_b"))
        assertEquals("a draft", ComposerDrafts.text("ses_a"))
    }
}
