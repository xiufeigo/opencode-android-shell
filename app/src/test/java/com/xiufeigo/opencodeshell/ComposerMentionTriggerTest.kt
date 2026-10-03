package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.composer.ComposerMention
import com.xiufeigo.opencodeshell.ui.session.composer.MentionText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ComposerMention] — the `@` popover opens on the **trigger**, not on a
 * non-empty row list (`editor.tsx:790-792`), and a bare `@` asks for its rows
 * without waiting out the debounce.
 *
 * The device run that motivated it (`research/v62_verify/5560/17_at.png`): a `@`
 * typed into a fresh session left no card above the editor at all, and the menu
 * only became reachable after another character (or the 「+」 menu's 上下文 row,
 * which inserts the very same `@`).
 */
class ComposerMentionTriggerTest {

    @Test
    fun bareMentionAnchorOpensThePopoverWithoutRows() {
        assertTrue(
            ComposerMention.popoverVisible(
                hasRows = false,
                slashQuery = null,
                trigger = MentionText.Anchor(0, ""),
            ),
        )
    }

    @Test
    fun noTriggerAndNoRowsKeepsThePopoverClosed() {
        assertFalse(
            ComposerMention.popoverVisible(
                hasRows = false,
                slashQuery = null,
                trigger = null,
            ),
        )
    }

    @Test
    fun aSlashQueryOpensThePopoverOnItsOwn() {
        assertTrue(ComposerMention.popoverVisible(hasRows = false, slashQuery = "", trigger = null))
    }

    @Test
    fun rowsAloneKeepThePopoverOpen() {
        assertTrue(ComposerMention.popoverVisible(hasRows = true, slashQuery = null, trigger = null))
    }

    @Test
    fun bareMentionAsksForItsRowsAtOnce() {
        assertEquals(0L, ComposerMention.searchDelayMs(""))
    }

    @Test
    fun aTypedQueryWaitsOutTheDebounce() {
        assertEquals(MentionText.DEBOUNCE_MS, ComposerMention.searchDelayMs("sr"))
    }
}
