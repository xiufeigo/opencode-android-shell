package com.xiufeigo.opencodeshell

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.xiufeigo.opencodeshell.ui.overlay.DialogLayerPolicy
import com.xiufeigo.opencodeshell.ui.overlay.scrimTapDismisses
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v56 P1 — 「点 scrim 关闭面板的那一击穿透到底层」 (把 Review 的 Session 标签误切成
 * Changes, 5 次里 3 次: `research/v56_verify/tD/53_pal_passthru.png`,
 * `v51_passthru2`).
 *
 * The hit-path question this pins, because it is the only way the reported
 * symptom can happen and it has to be settled by reading rather than by
 * guessing: **while a layer is open, which node owns a tap that lands on its
 * scrim?** Three properties decide it, and all three are structural:
 *
 * 1. The scrim node covers the whole layer (`fillMaxSize`) and carries a
 *    **consuming** gesture detector. `clickable` would fire on an unconsumed
 *    tap; `detectTapGestures` consumes the down (`awaitFirstDown` →
 *    `consume`), so the gesture is claimed and nothing below it can act on it.
 * 2. The dismissal is decided on the **UP** (`onTap`), never on the press, so
 *    the node that recorded the hit is still mounted and still on the hit path
 *    for the whole gesture — the layer cannot lose its own tap mid-gesture.
 * 3. The dismissal happens *while the layer is still open*
 *    ([DialogLayerPolicy.acceptsPointerInput]), and the layer is the **last**
 *    child of the host box — Compose's hit walk stops at the first sibling that
 *    records a hit, so the navigation tree behind never enters the path.
 *
 * The one window in which the screen behind *is* reachable is the **exit**
 * ([DialogLayerPolicy.isHitTransparent]): from `close()` until the layer is
 * removed, `exitHoldMs` (~152 ms for the palette) later, a layer takes no
 * pointer at all. That is v50's accepted 「退出后第一击应当到达下层」 behaviour, not
 * the close gesture, and it is the only candidate left for a device tap that
 * both dismissed and landed below — see the handoff note for the two options.
 */
class DialogScrimTapOwnershipTest {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    private fun host() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/overlay/DialogHost.kt")

    @Test
    fun theScrimDetectorConsumesTheGestureInsteadOfFiringOnAnUnconsumedTap() {
        val host = host()
        assertFalse(
            "a `clickable` scrim fires on every *unconsumed* tap; the layer must " +
                "own the gesture with a consuming detector",
            host.contains("clickable("),
        )
        assertTrue(host.contains("detectTapGestures(onTap = { local ->"))
    }

    @Test
    fun theDismissalIsDecidedOnTheUpSoTheLayerKeepsItsOwnGesture() {
        val host = host()
        assertTrue(
            "an onPress dismissal would tear the node off the hit path while " +
                "the finger is still down, so the rest of the gesture could fall " +
                "through to the screen behind",
            !host.contains("onPress ="),
        )
        // v71: the comparison reads the layer's scrim state — the card the layer
        // reported, or the host's own measurement of a root that *is* the card.
        assertTrue(host.contains("if (cardState.dismisses(local))"))
    }

    @Test
    fun theScrimCoversTheWholeLayerAndIsWiredAfterItsOwnGeometry() {
        val host = host()
        val pointer = host.indexOf(".pointerInput(entry.id)")
        val before = host.substring(maxOf(0, pointer - 900), pointer)
        assertTrue(
            "the detector must sit on a full-bleed node, or a tap beside the " +
                "card's row would find no scrim to claim it",
            before.contains("fillMaxSize()"),
        )
        assertTrue("the detector must be phase-gated", before.contains("DialogLayerPolicy."))
    }

    @Test
    fun theLayerIsComposedAfterTheNavigationTreeItCovers() {
        val host = host()
        val content = host.indexOf("content()")
        val layer = host.indexOf("DialogLayer(")
        assertTrue(content > 0 && layer > content)
        assertTrue(
            "Compose's hit walk stops at the first sibling that records a hit, " +
                "so the layer has to be the later sibling to cover the tree",
            host.substring(layer - 200, layer).contains("key(entry.id)"),
        )
    }

    @Test
    fun onlyTheExitWindowLetsTheScreenBehindSeeATap() {
        assertTrue(DialogLayerPolicy.acceptsPointerInput(closing = false))
        assertFalse(DialogLayerPolicy.acceptsPointerInput(closing = true))
        assertFalse(DialogLayerPolicy.isHitTransparent(closing = false))
        assertTrue(DialogLayerPolicy.isHitTransparent(closing = true))
    }

    @Test
    fun aTapInsideTheCardNeverDismissesAndAnUnmeasuredOneNeverDoesEither() {
        val card = Rect(100f, 200f, 500f, 600f)
        // Inside the card: the card owns it, and the scrim must not fire — a
        // dismiss here would close the dialog under the user's own card.
        assertFalse(scrimTapDismisses(card, Offset(300f, 400f)))
        // Outside: the backdrop, and only the backdrop.
        assertTrue(scrimTapDismisses(card, Offset(40f, 400f)))
        assertTrue(scrimTapDismisses(card, Offset(300f, 900f)))
        // Before the first layout there is nothing to compare against, so a
        // dismissal can never be guessed.
        assertFalse(scrimTapDismisses(null, Offset(300f, 400f)))
    }
}
