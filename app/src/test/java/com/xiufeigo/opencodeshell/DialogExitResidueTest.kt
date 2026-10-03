package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.overlay.DialogLayerPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The exit-window residue bundle: after [DialogExitHitTransparencyTest] took the
 * `DialogHost` layers off the hit path, three remnants of the same window were
 * still live.
 *
 * 1. **The hand-rolled scrim's detector stayed mounted through its exit.**
 *    `SessionBottomSheet` keeps its scrim mounted via
 *    `AnimatedVisibility(visible = open)`, so for the ~200 ms exit the fading
 *    backdrop still owned the pointer — a mounted `pointerInput` records the
 *    hit even when its block does nothing, and the hit walk stops there. Same
 *    swallowed-first-tap as the host's own scrim, one layer down. The shared
 *    [com.xiufeigo.opencodeshell.ui.overlay.DialogScrim] therefore takes a
 *    `closing` gate (off the chain + out of the semantics tree while closing,
 *    via [DialogLayerPolicy]), and the one hand-rolled surface with a real
 *    exit window passes `closing = !open`. Every other caller unmounts the
 *    moment it dismisses (`?.let` / `if` drops the surface), so there is no
 *    exit window and the default (`closing = false`) preserves behavior.
 *
 * 2. **BACK stayed armed through the exit.** `BackHandler(enabled =
 *    stack.isNotEmpty())` stayed true ~100 ms after the last close (the
 *    dismissed layer lingers for its exit transition), so one BACK on the
 *    screen the user closed the dialog to get back to was swallowed by
 *    `controller.close()` — a no-op close that ate the press. The gate is now
 *    [DialogLayerPolicy.hasOpenLayer]: armed only while an *open* layer owns
 *    the key.
 *
 * 3. **The closing card stayed in the accessibility tree.** A TalkBack focus
 *    that landed mid-exit survived as a live node; double-tapping it fired the
 *    stale action after the surface was conceptually gone. The closing content
 *    (host layers) and the closing sheet / scrim (hand-rolled) hide via
 *    `clearAndSetSemantics {}` for the exit window — the BOM-pinned Compose has
 *    no `hideFromAccessibility` yet.
 *
 * The Compose dispatch itself is not reachable from a JVM test, so the policy
 * is unit-tested pure and the wiring is pinned with the repo's established
 * source guard.
 */
class DialogExitResidueTest {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    private fun host(): String =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/overlay/DialogHost.kt")

    private fun scrim(): String =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/overlay/DialogScrim.kt")

    private fun sheet(): String =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/drawer/SessionBottomSheet.kt")

    // --------------------------------------------------- (2) the BACK policy

    @Test
    fun backIsArmedOnlyWhileAnOpenLayerOwnsTheKey() {
        // No layers at all: nothing to dismiss.
        assertFalse(DialogLayerPolicy.hasOpenLayer(emptyList()))
        // One open layer: BACK dismisses it.
        assertTrue(DialogLayerPolicy.hasOpenLayer(listOf(false)))
        // The exit window: the last layer is closing but still mounted — BACK
        // must fall through to the screen behind, not re-close the dead layer.
        assertFalse(DialogLayerPolicy.hasOpenLayer(listOf(true)))
        // Mixed stack: one lingering exit plus one live layer — still armed.
        assertTrue(DialogLayerPolicy.hasOpenLayer(listOf(true, false)))
        assertTrue(DialogLayerPolicy.hasOpenLayer(listOf(false, true, true)))
        // All closing: disarmed, however many linger.
        assertFalse(DialogLayerPolicy.hasOpenLayer(listOf(true, true)))
    }

    @Test
    fun theHostGatesBackOnOpenLayersNotOnTheBareStack() {
        val host = host()
        assertTrue(
            "BACK must release the moment the last layer starts closing",
            host.contains("DialogLayerPolicy.hasOpenLayer(stack.map { it.closing })"),
        )
        assertFalse(
            "a bare-stack gate stays armed through the exit transition and eats " +
                "one BACK on the screen behind",
            host.contains("BackHandler(enabled = stack.isNotEmpty())"),
        )
    }

    // -------------------------------------------- (1) the shared scrim gate

    @Test
    fun theSharedScrimGatesItsDetectorOnTheExitPhase() {
        assertTrue(DialogLayerPolicy.acceptsPointerInput(closing = false))
        assertFalse(DialogLayerPolicy.acceptsPointerInput(closing = true))
        val scrim = scrim()
        // The gate is on the modifier itself, not an early return inside the
        // block: a mounted `pointerInput` records the hit either way.
        assertTrue(scrim.contains("closing: Boolean = false"))
        assertTrue(scrim.contains("DialogLayerPolicy.acceptsPointerInput(closing)"))
        assertTrue(scrim.contains("Modifier.pointerInput(state)"))
        assertFalse(
            "an early-returning detector is still a hit-test target",
            scrim.contains("return@pointerInput"),
        )
    }

    @Test
    fun theSheetPassesItsExitWindowIntoTheSharedScrim() {
        val sheet = sheet()
        // The only hand-rolled surface whose scrim outlives the dismissal: the
        // exit keeps it mounted, so the exit flag is the negation of `open`.
        assertTrue(sheet.contains("closing = !open"))
        assertTrue(sheet.contains("DialogLayerPolicy."))
    }

    @Test
    fun noOtherHandRolledSurfaceGrowsAnExitWindow() {
        // Every other `DialogScrim` caller unmounts on dismiss (`?.let` / `if`
        // drops the surface — verified by the absence of `AnimatedVisibility`
        // in those files), so the `closing = false` default preserves behavior
        // and only the sheet passes an explicit gate.
        val callers = listOf(
            "ui/home/HomeProjectEditDialog.kt",
            "ui/home/HomeDeleteDialog.kt",
            "ui/nav/MobileTabsDrawer.kt",
            "ui/palette/AgentDetails.kt",
            "ui/palette/DebugLocations.kt",
            "ui/palette/PluginRpc.kt",
            "ui/session/actions/SessionActionsDialogs.kt",
            "ui/session/drawer/SessionRowDialogs.kt",
            "ui/session/review/ReviewChangesTab.kt",
            "ui/settings/SettingsPanels.kt",
            "ui/settings/SettingsScreen.kt",
            "ui/workspace/WorktreeCreateDialog.kt",
        )
        for (relative in callers) {
            val body = source("src/main/java/com/xiufeigo/opencodeshell/$relative")
            assertFalse(
                "$relative: no AnimatedVisibility exit may keep the scrim mounted — " +
                    "pass closing = !open like SessionBottomSheet or unmount on dismiss",
                body.contains("AnimatedVisibility("),
            )
            assertFalse(
                "$relative: must keep riding the shared scrim (DialogScrimResidueTest " +
                    "pins the call itself)",
                body.contains("closing = "),
            )
        }
    }

    // --------------------------------------- (3) the semantics hiding

    @Test
    fun closingSurfacesHideFromTheSemanticsTree() {
        // The BOM-pinned Compose has no `hideFromAccessibility` yet; the exit
        // branch clears the node instead, and the open branch keeps the
        // traversal group so the layer still reads as one dialog while up.
        val host = host()
        assertTrue(host.contains("DialogLayerPolicy.isHitTransparent(entry.closing)"))
        assertTrue(host.contains("Modifier.clearAndSetSemantics { }"))
        assertTrue(host.contains("isTraversalGroup = true"))
        val scrim = scrim()
        assertTrue(scrim.contains("Modifier.clearAndSetSemantics { }"))
        val sheet = sheet()
        assertTrue(sheet.contains("DialogLayerPolicy.isHitTransparent(!open)"))
        assertTrue(sheet.contains("Modifier.clearAndSetSemantics { }"))
    }

    @Test
    fun theScrimStillCarriesNoClickableSemantics() {
        // The exit gate must not reintroduce the unlabeled-button residue the
        // shared scrim was built to remove (`DialogScrimResidueTest`).
        val scrim = scrim()
        assertFalse(scrim.contains(".clickable"))
        assertEquals(1, scrim.split("detectTapGestures(onTap = {").size - 1)
    }
}
