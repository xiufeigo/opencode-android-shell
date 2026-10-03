package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.components.TabReorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * BUG-2 (v38 tB, `research/v38_verify/tB/v38B_39_drag`, byte-identical to
 * `v38B_38_moved_left`): dragging a terminal chip did nothing while the chip's
 * ⋮ menu moved it fine.
 *
 * Root cause: `detectDragGesturesAfterLongPress` gives its hold up as soon as
 * anything consumes a pointer change — `awaitLongPressOrCancellation` re-checks
 * the Final pass of every event (`foundation/gestures/DragGestureDetector.kt:915-921`)
 * — and the strip's own `horizontalScroll` consumes the movement the moment the
 * finger passes the touch slop, i.e. inside the long-press timeout. A press that
 * moved at all therefore never reached `TabReorder.dropIndex`; only a perfectly
 * still hold did. (The strict detector's second limit is the chip's bounds:
 * `it.isOutOfBounds` cancels a reorder-sized travel.)
 *
 * The fix keeps the strict detector and adds a re-armed one
 * ([TerminalPanel]'s `awaitLongPressClaim`) that waits out the same deadline
 * indifferent to that consumption, so the strip's scroll can stay live; a press
 * that passed the slop before the deadline belongs to the scroll and is dropped
 * (v40). Both detectors feed the *same* single-writer drag state, so the reorder
 * rule is unchanged — which is what the `TabReorder` half below pins.
 */
class TerminalTabDragClaimTest {

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText()
    }

    private val terminal = "src/main/java/com/xiufeigo/opencodeshell/ui/terminal/TerminalPanel.kt"

    /**
     * v40: the strip's scroll no longer stands down for a chip press. The chips
     * sit flush, so a finger on a chip is the only place to drag from and the
     * suspension left the whole overflow unscrollable (`tD_45_stripleft`: the
     * drag moved the strip not one pixel). The re-armed detector now arbitrates
     * on the travel it measured before the deadline instead — see
     * `TerminalTabGesture.press` in [TerminalTabStripV40Test].
     */
    @Test
    fun theStripScrollsAndTheLongPressArbitratesOnTravel() {
        val text = source(terminal)
        // The suspension and the state it needed are gone.
        assertFalse(text.contains("horizontalScroll(scroll, enabled"))
        assertFalse(text.contains("chipDownId"))
        // The press is still read in the Initial pass, before the scrollable's
        // own slop detection could consume the same event.
        assertTrue(text.contains("pass = PointerEventPass.Initial"))
        // …and it is only claimed when it had not already become a scroll.
        assertTrue(
            text.contains("TerminalTabGesture.press(travel, viewConfiguration.touchSlop)"),
        )
        assertTrue(text.contains("TerminalTabGesture.Press.SCROLL"))
    }

    /** The re-armed detector exists, waits out the real long-press timeout, and claims. */
    @Test
    fun theReArmedDetectorWaitsTheLongPressTimeout() {
        val text = source(terminal)
        assertTrue(text.contains("awaitLongPressClaim("))
        assertTrue(text.contains("viewConfiguration.longPressTimeoutMillis"))
        assertTrue(text.contains("onClaim = {"))
        // The claim is a single writer: the second detector's callbacks are
        // no-ops once one of them owns the gesture.
        assertTrue(text.contains("fun claimDrag(owner: TerminalDragOwner, id: String): Boolean"))
        assertTrue(text.contains("if (dragOwner != null) return false"))
        assertTrue(text.contains("if (dragOwner != owner || dragId != id) return"))
        // The claim consumes from the deadline on: that is what stops the scroll
        // and cancels the chip's tap (`clickable`'s press gives up on consumption).
        assertTrue(text.contains("change.consume()"))
    }

    /** The strict D-15 path is still there and both paths drive the same rule. */
    @Test
    fun bothDetectorsDriveTheOneReorderRule() {
        val text = source(terminal)
        assertTrue(text.contains("detectDragGesturesAfterLongPress"))
        assertTrue(text.contains("claimDrag(TerminalDragOwner.HOLD, tab.id)"))
        assertTrue(text.contains("claimDrag(TerminalDragOwner.CLAIM, tab.id)"))
        assertTrue(text.contains("dragBy(TerminalDragOwner.HOLD, tab.id, amount.x)"))
        assertTrue(text.contains("dragBy(TerminalDragOwner.CLAIM, tab.id, dx)"))
        assertTrue(text.contains("TabReorder.dropIndex(from, dragDx, pitch, list.size)"))
        assertTrue(text.contains("liveMove.value(from, to)"))
        // The strip still routes the model move to the host (menu rows and drag
        // both land on `TerminalTabs.move`).
        assertTrue(text.contains("onMoveTab = { from, to -> moveTab(from, to) }"))
        assertTrue(text.contains("if (tabs.move(fromIndex, toIndex)) syncTabs()"))
    }

    /**
     * The pitch/step contract both strips share: the chip's own width is the
     * whole pitch (the chips sit flush), half a chip is a step, and the re-based
     * travel keeps counting from the chip's new home — so a drag of N chips
     * lands on N steps for the terminal strip exactly as for the session one.
     */
    @Test
    fun theChipPitchMatchesTheSharedReorderRule() {
        val chip = 160f
        assertEquals(1, TabReorder.dropIndex(fromIndex = 0, dragPx = chip / 2f, pitchPx = chip, count = 3))
        assertEquals(0, TabReorder.dropIndex(fromIndex = 0, dragPx = chip / 2f - 1f, pitchPx = chip, count = 3))
        assertEquals(2, TabReorder.dropIndex(fromIndex = 0, dragPx = chip * 2f, pitchPx = chip, count = 3))
        // The re-basing `dragBy` does after a live move: `dragDx -= (to - from) * pitch`.
        // A 1.5-chip drag that already stepped once leaves half a chip of
        // travel, and half a chip is the next step.
        val reBased = chip * 1.5f - (1 - 0) * chip
        assertEquals(2, TabReorder.dropIndex(fromIndex = 1, dragPx = reBased, pitchPx = chip, count = 3))
        // A chip whose width is not measured yet cannot move, and a stale id
        // never moves the wrong tab.
        assertEquals(1, TabReorder.dropIndex(fromIndex = 1, dragPx = 999f, pitchPx = 0f, count = 3))
        assertEquals(-1, TabReorder.dropIndex(fromIndex = 9, dragPx = 999f, pitchPx = chip, count = 3))
    }
}
