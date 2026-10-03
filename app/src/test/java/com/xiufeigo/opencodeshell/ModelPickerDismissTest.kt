package com.xiufeigo.opencodeshell

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v48 tD_34/35/36 → v49 tD_04/05 — the model picker popup would not go away:
 * ESC did nothing, leaving BACK as the only way out while open.
 *
 * v48 pinned a per-site `ModelPickerDismiss.onEscape` + a bare
 * `onPreviewKeyEvent` on the popup content. That handler never ran on device:
 * `Popup` does not give its content focus (unlike `Dialog`), so there was no
 * focus owner for `onPreviewKeyEvent` to be dispatched along. v49 moves the
 * whole contract to the shared `Modifier.ocPopoverDismiss` (focus ownership +
 * ESC + outside-tap pass-through, see `OcPopoverDismissTest`) and deletes the
 * picker-local policy object; this class keeps pinning the host popup's
 * half — outside tap and BACK still ride `onDismissRequest`.
 */
class ModelPickerDismissTest {

    @Test
    fun modelChipHostsTheFullDismissContract() {
        val chip = listOf(
            File("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/ModelChip.kt"),
            File("app/src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/ModelChip.kt"),
        ).firstOrNull { it.isFile }?.readText() ?: error("missing ModelChip.kt")
        // Outside tap / BACK dismiss through the host request, pinned explicit
        // so a later edit cannot silently drop them back to relying on defaults.
        // v58: the request routes through closePicker so the close reason is
        // known (focus restore must NOT fire for outside taps) — the outside
        // dismiss contract itself is unchanged.
        assertTrue(
            "outside-tap request",
            chip.contains("onDismissRequest = { closePicker(OcPopoverCloseReason.OUTSIDE) }"),
        )
        assertTrue("outside flag", chip.contains("dismissOnClickOutside = true"))
        assertTrue("back flag", chip.contains("dismissOnBackPress = true"))
        assertTrue("focusable flag", chip.contains("focusable = true"))
        // ESC and the pass-through tap are answered by the shared modifier on
        // the popup's root node — the composer's own key handling cannot see the
        // key while the focusable window is open, and the Popup window is
        // touch-modal so the outside tap never reaches the screen behind it.
        assertTrue(
            "shared modifier",
            // v51: the call carries a third argument (`requestInitialFocus =
            // false`) so the popup's own search field keeps the initial focus.
            // v75 P3: it rides the same `OcEnterOnMount` modifier chain as the
            // panel's own surface (so the surface stays outside the fade layer),
            // hence the chain form `.ocPopoverDismiss(…` rather than `Modifier.…`.
            chip.contains(".ocPopoverDismiss(popoverDismiss"),
        )
        assertTrue("recorded tap is replayed", chip.contains("popoverDismiss.flushPendingTap()"))
    }
}
