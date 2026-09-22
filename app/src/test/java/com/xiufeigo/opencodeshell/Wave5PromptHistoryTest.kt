package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.composer.ComposerHistory
import com.xiufeigo.opencodeshell.ui.session.composer.PromptHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * W5-C B7: the composer's `↑`/`↓` prompt history.
 *
 * Upstream anchors: `composer/editor/interaction.ts:261-298` (the `savedHistory`
 * slot and the walk direction), `:468-473` (`canNavigateHistory`), and
 * `composer/history/entry.ts:8,28-61` (newest first, blank dropped, repeat
 * dropped, cap 100, remove-on-failed-send).
 */
class Wave5PromptHistoryTest {

    @Before
    fun reset() {
        ComposerHistory.reset()
    }

    @Test
    fun newestPromptComesBackFirst() {
        val history = PromptHistory()
        history.add("first")
        history.add("second")
        assertEquals(listOf("second", "first"), history.entries())
        assertEquals("second", history.up(text = "", caret = 0))
        assertEquals("first", history.up(text = "second", caret = 0))
    }

    @Test
    fun walkingDownRestoresTheDraftThatWasThereBefore() {
        val history = PromptHistory()
        history.add("older")
        history.add("newer")

        // `↑` from an empty editor remembers what was in it (here: nothing).
        assertEquals("newer", history.up(text = "", caret = 0))
        assertEquals("older", history.up(text = "newer", caret = 0))
        assertEquals("newer", history.down(text = "older", caret = 0))
        assertEquals("", history.down(text = "newer", caret = 0))
        assertFalse("back at the draft", history.navigating)
        assertNull("nothing left below the draft", history.down(text = "", caret = 0))
    }

    @Test
    fun walkingPastTheOldestEntryRestoresAnEmptyEditor() {
        val history = PromptHistory()
        history.add("sent once")
        // `↑` is only reachable from an empty editor, so the "saved" draft is the
        // empty one (`interaction.ts:276-280,294-297`).
        assertEquals("sent once", history.up(text = "", caret = 0))
        assertEquals("", history.down(text = "sent once", caret = 0))
        assertFalse(history.navigating)
    }

    @Test
    fun navigationIsGatedByTheCaretLikeUpstream() {
        // Not in history: `↑` only from an empty editor at position 0.
        assertFalse(PromptHistory.canNavigate(up = true, text = "typed", caret = 2, navigating = false))
        assertFalse(PromptHistory.canNavigate(up = true, text = "typed", caret = 0, navigating = false))
        assertTrue(PromptHistory.canNavigate(up = true, text = "", caret = 0, navigating = false))
        // `↓` only from the end of the text.
        assertFalse(PromptHistory.canNavigate(up = false, text = "typed", caret = 1, navigating = false))
        assertTrue(PromptHistory.canNavigate(up = false, text = "typed", caret = 5, navigating = false))
        // In history: either end is fine.
        assertTrue(PromptHistory.canNavigate(up = true, text = "recalled", caret = 0, navigating = true))
        assertTrue(PromptHistory.canNavigate(up = false, text = "recalled", caret = 8, navigating = true))
        assertFalse(PromptHistory.canNavigate(up = true, text = "recalled", caret = 4, navigating = true))

        val history = PromptHistory()
        history.add("recalled")
        assertNull("a non-empty editor cannot start the walk", history.up(text = "typed", caret = 2))
    }

    @Test
    fun blankAndRepeatedPromptsAreNotRecorded() {
        val history = PromptHistory()
        history.add("same")
        history.add("same")
        history.add("   ")
        assertEquals(listOf("same"), history.entries())
        history.add("other")
        assertEquals(listOf("other", "same"), history.entries())
    }

    @Test
    fun shellAndNormalPromptsKeepSeparateStacks() {
        val history = PromptHistory()
        history.add("ask something", shell = false)
        history.add("git status", shell = true)

        assertEquals(listOf("ask something"), history.entries(shell = false))
        assertEquals(listOf("git status"), history.entries(shell = true))

        // Each stack answers its own head; the other stays untouched.
        assertEquals("git status", history.up(text = "", caret = 0, shell = true))
        assertEquals(listOf("ask something"), history.entries(shell = false))
    }

    @Test
    fun theStackIsCappedAtTheUpstreamHundred() {
        val history = PromptHistory()
        for (index in 1..(PromptHistory.MAX_ENTRIES + 5)) history.add("prompt $index")
        assertEquals(PromptHistory.MAX_ENTRIES, history.entries().size)
        assertEquals("prompt ${PromptHistory.MAX_ENTRIES + 5}", history.entries().first())
        assertEquals("prompt 6", history.entries().last())
    }

    @Test
    fun aFailedSendRemovesItsHistoryEntry() {
        val history = PromptHistory()
        history.add("failed")
        history.add("kept")
        history.remove("failed")
        assertEquals(listOf("kept"), history.entries())
    }

    @Test
    fun addingResetsTheWalkPosition() {
        val history = PromptHistory()
        history.add("one")
        assertEquals("one", history.up(text = "", caret = 0))
        assertTrue(history.navigating)
        history.add("two")
        assertFalse(history.navigating)
        assertEquals("two", history.up(text = "", caret = 0))
    }

    @Test
    fun oneHistoryInstancePerComposerKey() {
        val a = ComposerHistory.of("ses_a")
        ComposerHistory.of("ses_b").add("b prompt")
        a.add("a prompt")
        assertEquals(listOf("a prompt"), ComposerHistory.of("ses_a").entries())
        assertEquals(listOf("b prompt"), ComposerHistory.of("ses_b").entries())
    }
}
