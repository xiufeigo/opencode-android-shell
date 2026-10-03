package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.composer.ComposerSuggestions
import com.xiufeigo.opencodeshell.ui.session.composer.PopoverPick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v67 BUG-3/5: the composer's popover rows used to carry their **index** into the
 * click handler, which then looked the item up in whatever list was current when
 * the tap arrived. Device evidence:
 *
 * - `research/v67_verify/5558/s28-s47` — a tap on `explore` did nothing, and about
 *   a minute later a tap inserted `build`, the *next* list's first row.
 * - `research/v67_verify/5558/s31` — a `/model` picked in session B was finally
 *   applied to session A (`MiniMax-M3.1`).
 * - `research/v67_verify/5556/41-42` — the same dead taps on `emulator-5556`.
 */
class ComposerPopoverPickV67Test {

    private fun command(label: String) = ComposerSuggestions.Item(
        category = ComposerSuggestions.Category.COMMAND,
        label = label,
    )

    // ---------------------------------------------------------- PopoverPick.key

    @Test
    fun aRowsKeyIsItsCategoryAndLabel() {
        assertEquals(
            "${ComposerSuggestions.Category.COMMAND}:/model",
            PopoverPick.key(command("/model")),
        )
    }

    @Test
    fun twoCategoriesMayShareALabel() {
        val agent = ComposerSuggestions.Item(
            category = ComposerSuggestions.Category.AGENT,
            label = "/model",
        )
        assertEquals(false, PopoverPick.key(agent) == PopoverPick.key(command("/model")))
    }

    // --------------------------------------------------------- PopoverPick.resolve

    @Test
    fun thePickResolvesToTheRowItNames() {
        val rows = listOf(command("/build"), command("/explore"), command("/model"))
        assertEquals(
            command("/explore"),
            PopoverPick.resolve(command("/explore"), rows, "s1", "s1"),
        )
    }

    @Test
    fun aStaleRowIsDroppedRatherThanFallingBackToTheFirstRow() {
        // `build` sits at 0 now; the row that was drawn under the finger was
        // `explore`, and the pick is dropped — never re-read as position 0.
        val after = listOf(command("/build"), command("/model"))
        assertNull(PopoverPick.resolve(command("/explore"), after, "s1", "s1"))
    }

    @Test
    fun theResolvedRowIsTheOneTheCurrentListDescribes() {
        val fresh = ComposerSuggestions.Item(
            category = ComposerSuggestions.Category.COMMAND,
            label = "/model",
            description = "now described",
            builtIn = true,
        )
        assertEquals(fresh, PopoverPick.resolve(command("/model"), listOf(fresh), "s1", "s1"))
    }

    @Test
    fun aRowThatIsNoLongerListedIsDropped() {
        assertNull(PopoverPick.resolve(command("/explore"), listOf(command("/build")), "s1", "s1"))
    }

    @Test
    fun aPickLandingAfterTheSessionMovedIsDropped() {
        val rows = listOf(command("/model"))
        assertNull(PopoverPick.resolve(command("/model"), rows, "sessionA", "sessionB"))
    }

    @Test
    fun theSameSessionIsNotADrop() {
        val rows = listOf(command("/model"))
        assertEquals(command("/model"), PopoverPick.resolve(command("/model"), rows, "sessionA", "sessionA"))
    }

    @Test
    fun anUnboundPopoverCannotBeBlamedOnASwitch() {
        // The open latch has not run yet — the click is the opener's own.
        val rows = listOf(command("/model"))
        assertEquals(command("/model"), PopoverPick.resolve(command("/model"), rows, null, "sessionB"))
    }

    @Test
    fun noRowIsNoPick() {
        assertNull(PopoverPick.resolve(null, listOf(command("/model")), "s1", "s1"))
    }

    // ------------------------------------------------------------ the wiring

    @Test
    fun theRowHandsOverItsItemNotItsIndex() {
        val popover = source(
            "src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/MentionPopover.kt",
        )
        assertTrue(popover, popover.contains("onPick: (ComposerSuggestions.Item) -> Unit"))
        assertTrue(popover, popover.contains("onClick = { onPick(item) }"))
        assertFalse(popover, popover.contains("onPick(index)"))
        assertTrue(popover, popover.contains("key = { _, item -> PopoverPick.key(item) }"))
    }

    @Test
    fun theComposerResolvesThePickAndLatchesTheSession() {
        val composer = source(
            "src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt",
        )
        assertTrue(
            composer,
            composer.contains("PopoverPick.resolve(picked, items, popoverSession, draftKey) ?: return"),
        )
        assertTrue(composer.contains("popoverSession = if (popoverVisible) draftKey else null"))
        assertTrue(composer.contains("onPick = ::accept"))
        assertFalse(composer.contains("accept(selected)"))
        // `/model` raises its open request only once the pick is resolved, and
        // before the draft is rewritten under it.
        val resolve = composer.indexOf("PopoverPick.resolve(picked, items, popoverSession, draftKey)")
        val bump = composer.indexOf("BUILT_IN_MODEL -> modelOpenRequest += 1")
        val clear = composer.indexOf("setText(TextFieldValue(\"\"))", resolve)
        assertTrue("$resolve $bump $clear", resolve in 0 until bump && bump < clear)
    }

    // ------------------------------------------------------------- helpers

    private fun source(path: String): String = File(path).readText()
}
