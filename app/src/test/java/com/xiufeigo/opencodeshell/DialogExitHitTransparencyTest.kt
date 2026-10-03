package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.overlay.DialogLayerPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The exit-animation half of the layer input policy: a layer that is playing
 * its exit must be **off the hit path**, not merely inert.
 *
 * 真机 `research/HANDOFF.md` 低优残留 「弹层退出动画卡片可吞一击」. The previous
 * shape — a `pointerInput` whose block returned early while `closing` — changed
 * nothing, because Compose's hit walk stops at the first sibling that records a
 * hit and a mounted `pointerInput` node records one whether or not its coroutine
 * still runs (`HitTestResult.hit` → `shouldSharePointerInputWithSiblings`, and a
 * plain `pointerInput` never shares). The scrim (`fillMaxSize`) then starved the
 * screen behind, and the card's own nodes (a scroll body, a row button) starved
 * it wherever the tap landed on the card.
 *
 * The decisions are pure ([DialogLayerPolicy]): the phase a mounted layer is in,
 * and the displacement that clears the viewport for it. The Compose wiring is
 * pinned with the repo's established source guard (`DialogLayerPolicyTest`,
 * `DialogViewportFitTest`), because pointer dispatch and layer transforms are
 * not reachable from a JVM unit test.
 */
class DialogExitHitTransparencyTest {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    private fun host(): String =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/overlay/DialogHost.kt")

    // ------------------------------------------------------ the phase machine

    @Test
    fun aMountedLayerIsOpenUntilItIsClosed() {
        assertEquals(DialogLayerPolicy.Phase.OPEN, DialogLayerPolicy.phaseOf(closing = false))
        assertEquals(DialogLayerPolicy.Phase.EXITING, DialogLayerPolicy.phaseOf(closing = true))
    }

    @Test
    fun onlyTheOpenPhaseTakesPointerInput() {
        assertTrue(DialogLayerPolicy.acceptsPointerInput(closing = false))
        assertFalse(DialogLayerPolicy.acceptsPointerInput(closing = true))
        // …and the exit phase is its exact complement: one predicate for the
        // scrim's detector, one for the content, so the two cannot drift.
        assertFalse(DialogLayerPolicy.isHitTransparent(closing = false))
        assertTrue(DialogLayerPolicy.isHitTransparent(closing = true))
    }

    // ------------------------------------------------------- the displacement

    /** Where the card's top edge ends up: centred, then displaced by [offset]. */
    private fun topAfterDisplacement(viewportPx: Int, contentPx: Int, offset: Int): Int =
        (viewportPx - contentPx) / 2 + offset

    @Test
    fun anOpenLayerIsNotDisplacedAtAll() {
        assertEquals(0, DialogLayerPolicy.ghostOffsetPx(closing = false, viewportPx = 2400, contentPx = 640))
        assertEquals(0, DialogLayerPolicy.ghostOffsetPx(closing = false, viewportPx = 0, contentPx = 0))
    }

    @Test
    fun aClosingLayerIsDisplacedClearOfTheWholeViewport() {
        val viewports = listOf(2400, 1600, 640, 360)
        val contents = listOf(0, 120, 368, 640, 1600, 3000)
        for (viewport in viewports) {
            for (content in contents) {
                val offset = DialogLayerPolicy.ghostOffsetPx(
                    closing = true,
                    viewportPx = viewport,
                    contentPx = content,
                )
                val top = topAfterDisplacement(viewport, content, offset)
                assertTrue(
                    "viewport=$viewport content=$content: the card's top edge ($top) must clear " +
                        "the viewport bottom ($viewport)",
                    top >= viewport,
                )
            }
        }
    }

    @Test
    fun anUnmeasuredLayerIsAlreadyOffTheHitPath() {
        // Before the first layout there is no viewport to clear — the layer must
        // still be displaced, so the very first frame of a close cannot swallow
        // a tap either.
        assertTrue(DialogLayerPolicy.ghostOffsetPx(closing = true, viewportPx = 0, contentPx = 0) > 0)
        // Negative/absurd measurements (a degenerate window) never displace less.
        assertEquals(
            DialogLayerPolicy.GHOST_SLACK_PX,
            DialogLayerPolicy.ghostOffsetPx(closing = true, viewportPx = -100, contentPx = -100),
        )
    }

    // ------------------------------------------------------------ the wiring

    @Test
    fun theScrimDetectorIsRemovedFromTheChainInsteadOfLeftInert() {
        val host = host()
        // The idiom that does not work: a `pointerInput` node stays mounted and
        // returns early, so it keeps taking the hit for the whole exit window.
        assertFalse(
            "an early-returning detector is still a hit-test target — the modifier " +
                "itself has to be dropped while closing",
            host.contains("return@pointerInput"),
        )
        // Every `pointerInput` in the host sits behind a `DialogLayerPolicy`
        // phase gate.
        var found = 0
        var index = host.indexOf(".pointerInput(")
        while (index >= 0) {
            found++
            val line = host.take(index).count { it == '\n' } + 1
            val before = host.substring(maxOf(0, index - 400), index)
            assertTrue(
                "DialogHost.kt:$line: a `pointerInput` outside the phase gate keeps taking hits " +
                    "while the layer plays its exit",
                before.contains("DialogLayerPolicy."),
            )
            index = host.indexOf(".pointerInput(", index + 1)
        }
        assertTrue("the scrim's own detector must still be wired", found >= 1)
    }

    @Test
    fun theClosingContentIsDisplacedAndDrawnBackIntoPlace() {
        val host = host()
        // The displacement…
        assertTrue(host.contains("DialogLayerPolicy.ghostOffsetPx("))
        assertTrue(host.contains("DialogLayerPolicy.isHitTransparent(entry.closing)"))
        assertTrue(host.contains("graphicsLayer { translationY = ghostPx.toFloat() }"))
        // …and the draw that cancels it, so the exit transition is unchanged. A
        // half-applied ghost would draw the card off-screen for its whole exit.
        assertTrue(host.contains("drawWithContent"))
        assertTrue(host.contains("translate(top = -ghostPx.toFloat())"))
        assertTrue(host.contains("drawn.drawContent()"))
    }

    @Test
    fun theExitHoldAndTheScrimRuleAreUnchanged() {
        val host = host()
        // The layer still stays mounted for its exit, and the scrim still
        // dismisses only outside the content box — this fix only decides who
        // owns the pointer during that window. v71 moved the decision into the
        // shared scrim state (the box compared is now the card the layer
        // reported, falling back to the host's own measurement); the rule
        // itself is still `scrimTapDismisses` (`DialogScrimState`, pinned by
        // `DialogScrimResidueTest`).
        assertTrue(host.contains("cardState.dismisses(local)"))
        assertTrue(host.contains("val cardState = remember(entry.id) { DialogScrimState() }"))
        assertTrue(host.contains("DialogLayerPolicy.acceptsPointerInput(entry.closing)"))
        assertTrue(host.contains("OcMotion.exitHoldMs"))
    }
}
