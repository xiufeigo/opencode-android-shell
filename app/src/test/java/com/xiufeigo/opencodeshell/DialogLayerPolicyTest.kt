package com.xiufeigo.opencodeshell

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.xiufeigo.opencodeshell.ui.overlay.DialogEscape
import com.xiufeigo.opencodeshell.ui.overlay.DialogLayerPolicy
import com.xiufeigo.opencodeshell.ui.overlay.scrimTapDismisses
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Layer input policy for the [com.xiufeigo.opencodeshell.ui.overlay.DialogHost]
 * family (真机: the layer vanished on Escape-to-hide-IME and on incidental taps
 * on the dialog body; the first click after every close was swallowed by the
 * dying scrim; Escape never dismissed a dialog that had no text field).
 *
 * The decisions are pure ([DialogEscape], [DialogLayerPolicy],
 * [scrimTapDismisses]); the Compose wiring is pinned with the repo's established
 * source guard (`AndroidBackJsTest`, `Wave5IntegrationWiringTest`) because key
 * and pointer dispatch are not reachable from a unit test.
 */
class DialogLayerPolicyTest {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    @Test
    fun escapeDismissesTheLayerUnlessTheImeOwnsTheKey() {
        assertTrue(DialogEscape.dismissesLayerOnEscape())
        // IME up: the key collapses the keyboard, nothing else — an inner handler
        // (the palette's own Escape, the keybind recorder's cancel) keeps it.
        assertTrue(DialogEscape.consumesToCollapseIme(imeVisible = true))
        assertFalse(DialogEscape.escapeDismissesLayer(imeVisible = true))
        // IME down: the key is the layer's dismissal.
        assertFalse(DialogEscape.consumesToCollapseIme(imeVisible = false))
        assertTrue(DialogEscape.escapeDismissesLayer(imeVisible = false))
    }

    @Test
    fun onlyATapOutsideTheContentBoxDismisses() {
        val content = Rect(10f, 20f, 110f, 220f)
        assertFalse(scrimTapDismisses(content, Offset(50f, 100f)))
        assertFalse(scrimTapDismisses(content, Offset(10f, 20f)))
        assertTrue(scrimTapDismisses(content, Offset(5f, 100f)))
        assertTrue(scrimTapDismisses(content, Offset(50f, 300f)))
        assertTrue(scrimTapDismisses(content, Offset(200f, 100f)))
        // Bounds not measured yet must never dismiss.
        assertFalse(scrimTapDismisses(null, Offset(50f, 100f)))
    }

    @Test
    fun aClosingLayerStopsTakingInput() {
        // Open: the scrim owns the outside tap and may dismiss.
        assertTrue(DialogLayerPolicy.acceptsPointerInput(closing = false))
        // Closing: still mounted for its exit animation, but inert — otherwise
        // its `fillMaxSize` scrim eats the first tap after the close.
        assertFalse(DialogLayerPolicy.acceptsPointerInput(closing = true))
    }

    @Test
    fun dialogHostWiresTheDismissalAndTheExitHoldGuard() {
        val host = source("src/main/java/com/xiufeigo/opencodeshell/ui/overlay/DialogHost.kt")
        val app = source("src/main/java/com/xiufeigo/opencodeshell/ui/App.kt")

        assertTrue(host.contains("collapseImeOnEscape"))
        assertTrue(host.contains("scrimTapDismisses"))
        // Exit-gated BACK: `stack.isNotEmpty()` stayed true through the exit
        // transition (~100 ms after the last close) and swallowed one press on
        // the screen behind; `hasOpenLayer` releases it the moment the last
        // layer starts closing (`DialogExitResidueTest` pins the policy).
        assertTrue(host.contains("DialogLayerPolicy.hasOpenLayer"))
        assertFalse(
            "a bare-stack BACK gate re-arms on the exit window and eats one press on the screen behind",
            host.contains("BackHandler(enabled = stack.isNotEmpty())"),
        )
        // Escape dismissal, on the box every focus path shares, on the bubble
        // pass so an inner owner runs first.
        assertTrue(host.contains("DialogEscape.escapeDismissesLayer"))
        assertTrue(host.contains("onKeyEvent"))
        assertTrue(host.contains("Key.Escape"))
        // The scrim's tap detector is gated on the layer being open.
        assertTrue(host.contains("DialogLayerPolicy.acceptsPointerInput(entry.closing)"))
        // The layer reads as one group in the accessibility tree.
        assertTrue(host.contains("isTraversalGroup"))

        assertTrue(app.contains("collapseImeOnEscape"))
        assertFalse(
            "the global key hook must not dismiss layers on Escape — the host owns that",
            app.contains("dialogController?.close()"),
        )
    }
}
