package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.composer.ComposerMention
import com.xiufeigo.opencodeshell.ui.session.composer.ComposerSuggestions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v67 B1/B2/B4 — the composer-panel focus and lifecycle batch filed against
 * `emulator-5558` (`research/v67_verify/5558/`).
 *
 * Device evidence this pins:
 * - `21_slash_mo2.png` / `31_midcursor.png` — the moment the `/` card opened,
 *   the editor lost the caret and every further key went nowhere (`hellXo`
 *   with no caret); only `Ctrl+A`+Delete brought the composer back.
 * - `s31` (the cross-session stretch of the run) — session B's `/` card was
 *   still standing in session A, and a pick landed on the wrong session.
 * - `s48_token_deleted.png` — with the editor emptied, the mention card
 *   re-expanded to the full list.
 *
 * Root causes and repairs (see the sources for the full comments):
 * - B1: a stacked popover counted toward the measured composer height and the
 *   pane's cover/shared decision flapped on every open (repaired by
 *   [ComposerPopoverOverlay], pinned in `ComposerPopoverOverlayV67Test`), and
 *   `Modifier.clickable`'s `focusableInNonTouchMode` made every row a focus
 *   target on a hardware-keyboard window, so a focus search landing in the
 *   card parked the keys there. The card now opts the whole subtree out of
 *   the focus chain (`canFocus = false`) — focus stays in the editor and
 *   every key keeps routing into the query, like upstream's textarea.
 * - B2: the editor's satellites (mention tokens, queue edit, file rows) are
 *   plain `remember` state that survived a session switch; they are reset on
 *   every draft-key change, and the `/find/file` effect re-asks in the new
 *   session's scope.
 * - B4: the open shape is re-read from the editor's own text on every
 *   composition ([ComposerSuggestions.triggerAt]) — the emptied editor is
 *   `IDLE_TRIGGER` and the panel collapses on the keystroke that deletes the
 *   last `@` — and the search answer's staleness guard reads that same live
 *   anchor through a latest-state bridge instead of a mirrored copy.
 */
class ComposerPopoverLifecycleV67Test {

    // --------------------------------------------------------- B4: the empty edge

    @Test
    fun theEmptyEditorIsIdleWhateverTheCaretSays() {
        assertEquals(ComposerSuggestions.IDLE_TRIGGER, ComposerSuggestions.triggerAt("", 0))
        // A stale caret offset on an emptied line must not invent a trigger.
        assertEquals(ComposerSuggestions.IDLE_TRIGGER, ComposerSuggestions.triggerAt("", 6))
    }

    @Test
    fun whitespaceOnlyTextIsIdle() {
        assertEquals(ComposerSuggestions.IDLE_TRIGGER, ComposerSuggestions.triggerAt(" ", 1))
        assertEquals(ComposerSuggestions.IDLE_TRIGGER, ComposerSuggestions.triggerAt("   ", 3))
    }

    @Test
    fun plainTextWithoutAnAtNeverMentions() {
        assertNull(ComposerSuggestions.triggerAt("build", 5).mention)
        // The caret-first read answers null at 0; the tail fallback must not
        // invent an anchor the text does not contain.
        assertNull(ComposerSuggestions.triggerAt("hello world", 0).mention)
        assertNull(ComposerSuggestions.triggerAt("mo", 0).slashQuery)
    }

    @Test
    fun deletingTheMentionTokenClosesThePanelOnTheLastAt() {
        // The full deletion edge: every prefix down to the bare `@` keeps the
        // panel live (upstream renders on the trigger), and the keystroke that
        // removes the `@` — the field is empty — collapses it. What the device
        // run read as "empty composer, full list" (`s48`) was the card plus an
        // editor the pane flip had starved; this pins the logic half.
        val prefixes = listOf("@build", "@buil", "@bui", "@bu", "@b", "@")
        for (text in prefixes) {
            val trigger = ComposerSuggestions.triggerAt(text, text.length)
            assertTrue("«$text» is a live mention", trigger.mention != null)
            assertTrue(
                "«$text» keeps the card open",
                ComposerMention.popoverVisible(hasRows = false, slashQuery = null, trigger = trigger.mention),
            )
        }
        val emptied = ComposerSuggestions.triggerAt("", 0)
        assertEquals(ComposerSuggestions.IDLE_TRIGGER, emptied)
        assertFalse(
            "the emptied editor closes the card",
            ComposerMention.popoverVisible(hasRows = false, slashQuery = null, trigger = null),
        )
    }

    @Test
    fun deletingTheSlashTokenClosesThePanelOnTheLastChar() {
        assertEquals("", ComposerSuggestions.triggerAt("/", 1).slashQuery)
        assertTrue(
            ComposerMention.popoverVisible(
                hasRows = false,
                slashQuery = ComposerSuggestions.triggerAt("/", 1).slashQuery,
                trigger = null,
            ),
        )
        assertNull(ComposerSuggestions.triggerAt("", 0).slashQuery)
    }

    // ------------------------------------------------------ B1: focus never moves

    @Test
    fun thePopoverCanNeverOwnFocus() {
        val popover = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/MentionPopover.kt")
        // `Modifier.clickable` chains `focusableInNonTouchMode` — on a window
        // with a hardware keyboard the rows used to be focus targets, and a
        // focus search landing in the card took the keys away from the editor.
        assertTrue(popover.contains(".focusProperties { canFocus = false }"))
        // The card requests no focus of its own, anywhere.
        assertFalse(popover.contains("FocusRequester"))
        assertFalse(popover.contains("requestFocus("))
    }

    @Test
    fun openingThePopoverChangesNoMeasurementThePaneReads() {
        // The v67 overlay contract (zero stack height) is what keeps opening
        // the card from re-parenting the editor — the wiring must route the
        // popover through it, never stack it directly in the column again.
        val composer = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt")
        assertTrue(composer.contains("layout(popover.width, ComposerPopoverOverlay.STACK_HEIGHT)"))
    }

    // ------------------------------------------------------ B2: switch convergence

    @Test
    fun sessionSwitchResetsTheEditorSatellites() {
        val composer = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt")
        val converge = composer.indexOf("LaunchedEffect(draftKey) {", composer.indexOf("fun cancelQueueEdit()"))
        assertTrue("convergence effect follows the queue-edit helper", converge >= 0)
        val window = composer.substring(converge, composer.indexOf("}", converge) + 1)
        assertTrue(window.contains("closePopovers()"))
        assertTrue("accepted mention tokens do not cross sessions", window.contains("tokens = emptyList()"))
        assertTrue("an open queue edit does not cross sessions", window.contains("cancelQueueEdit()"))
    }

    @Test
    fun theFileSearchIsKeyedToTheSession() {
        val composer = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt")
        assertTrue(composer.contains("LaunchedEffect(live.mention, draftKey)"))
    }

    // ------------------------------------------------------------- the no-mirror rule

    @Test
    fun theOpenShapeIsNeverMirrored() {
        // v66's split-brain class: a `remember` copy of the trigger the rows
        // render from. After v67 there is no `trigger`/`slashQuery` state to
        // drift from the editor's own text and caret.
        val composer = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt")
        assertFalse(composer.contains("var trigger by remember"))
        assertFalse(composer.contains("var slashQuery by remember"))
        assertTrue(
            composer.contains("val live = ComposerSuggestions.triggerAt(value.text, value.selection.end)"),
        )
        assertTrue(composer.contains("val liveMention by rememberUpdatedState(live.mention)"))
    }

    // ------------------------------------------------------------- helpers

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")
}
