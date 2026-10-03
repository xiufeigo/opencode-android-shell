package com.xiufeigo.opencodeshell

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import com.xiufeigo.opencodeshell.ui.overlay.DialogEscape
import com.xiufeigo.opencodeshell.ui.overlay.ModalEscapePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v50 LOW-1 (`research/v50_verify/tD/v50_46→47`): the native 删除… confirmation
 * did not close on ESC, while the three Compose layers v50 had already fixed did.
 *
 * 原因: those three ride `DialogHost`'s stack, whose ESC hook lives on the host
 * box. The 删除确认框 draws its own scrim (`ui/session/drawer/SessionRowDialogs.kt`,
 * `ui/home/HomeDeleteDialog.kt`) and is mounted straight into the screen tree, so
 * that hook — which returns early on an empty stack — never saw the key.
 *
 * [ModalEscapePolicy] is the in-tree modal's half of the same rule, and the
 * IME split has to be identical to [DialogEscape]'s: `input keyevent 111` is the
 * harness's 收键盘, so treating Escape as a dismissal while the keyboard is up
 * would eat the keystroke that is only meant to collapse it.
 */
class ModalEscapeTest {

    private fun down(key: Key) = KeyEventType.KeyDown to key
    private fun up(key: Key) = KeyEventType.KeyUp to key

    @Test
    fun `escape closes the dialog when the keyboard is down`() {
        val (type, key) = down(Key.Escape)
        assertTrue(ModalEscapePolicy.dismisses(type, key, open = true, imeVisible = false))
    }

    /** The IME exception is the host's, not this dialog's: the key only collapses it. */
    @Test
    fun `escape only collapses the keyboard while the ime is up`() {
        val (type, key) = down(Key.Escape)
        assertFalse(ModalEscapePolicy.dismisses(type, key, open = true, imeVisible = true))
        assertTrue(DialogEscape.consumesToCollapseIme(true))
    }

    /** KeyUp would fire the dismissal a second time. */
    @Test
    fun `the key release does not dismiss`() {
        val (type, key) = up(Key.Escape)
        assertFalse(ModalEscapePolicy.dismisses(type, key, open = true, imeVisible = false))
    }

    @Test
    fun `another key does not dismiss`() {
        val (type, key) = down(Key.Enter)
        assertFalse(ModalEscapePolicy.dismisses(type, key, open = true, imeVisible = false))
    }

    /**
     * The dangerous confirmations lock their buttons while the `DELETE` is out
     * (`HomeDeleteDialog.busy`); Escape is one of those buttons and stands down
     * with them.
     */
    @Test
    fun `a locked dialog does not dismiss`() {
        val (type, key) = down(Key.Escape)
        assertFalse(ModalEscapePolicy.dismisses(type, key, open = false, imeVisible = false))
    }

    /** Both halves of the split agree with the host, or the same key means two things. */
    @Test
    fun `the ime split matches the dialog host`() {
        for (imeVisible in listOf(true, false)) {
            for (key in listOf(Key.Escape, Key.Enter)) {
                val (type, pressed) = down(key)
                assertEquals(
                    "imeVisible=$imeVisible key=$key",
                    DialogEscape.escapeDismissesLayer(imeVisible) && key == Key.Escape,
                    ModalEscapePolicy.dismisses(type, pressed, open = true, imeVisible = imeVisible),
                )
            }
        }
    }
}
