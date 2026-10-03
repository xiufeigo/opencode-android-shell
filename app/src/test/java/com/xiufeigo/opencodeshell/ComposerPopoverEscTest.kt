package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.composer.ComposerPopoverEsc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [ComposerPopoverEsc] — ESC with the inline `@`/`/` popover open is
 * keyboard-first: with the IME up it only dismisses the keyboard (the popover
 * stays); with the IME down it closes the popover (upstream v2 behaviour).
 */
class ComposerPopoverEscTest {

    @Test
    fun `closed popover never consumes`() {
        assertEquals(
            ComposerPopoverEsc.Decision(consumed = false, closePopover = false, hideKeyboard = false),
            ComposerPopoverEsc.decide(popoverOpen = false, imeVisible = false),
        )
        assertEquals(
            ComposerPopoverEsc.Decision(consumed = false, closePopover = false, hideKeyboard = false),
            ComposerPopoverEsc.decide(popoverOpen = false, imeVisible = true),
        )
    }

    @Test
    fun `open popover with IME up only hides the keyboard`() {
        assertEquals(
            ComposerPopoverEsc.Decision(consumed = true, closePopover = false, hideKeyboard = true),
            ComposerPopoverEsc.decide(popoverOpen = true, imeVisible = true),
        )
    }

    @Test
    fun `open popover with IME down closes the popover`() {
        assertEquals(
            ComposerPopoverEsc.Decision(consumed = true, closePopover = true, hideKeyboard = false),
            ComposerPopoverEsc.decide(popoverOpen = true, imeVisible = false),
        )
    }

    @Test
    fun `composer wires ESC through the IME-first decision`() {
        val composer = listOf(
            File("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt"),
            File("app/src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt"),
        ).firstOrNull { it.isFile }?.readText() ?: error("missing Composer.kt")
        assertTrue("decision", composer.contains("ComposerPopoverEsc.decide("))
        assertTrue("ime read", composer.contains("WindowInsets.isImeVisible"))
        assertTrue("hide call", composer.contains("keyboardController?.hide()"))
        assertFalse(
            "unconditional close is gone",
            composer.contains("Key.Escape -> {\n                                    closePopovers()\n                                    true\n                                }"),
        )
    }
}
