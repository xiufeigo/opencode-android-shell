package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.terminal.share.PtySizeGate
import com.xiufeigo.opencodeshell.ui.terminal.share.TerminalGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Resize policy: first size immediate, then 100 ms trailing debounce with
 * coalescing (`research/02` §9.2 `scheduleSize`), plus the cell→grid math of
 * `research/06` §1.6 step 5. Driven by an injected clock, so no timers.
 */
class PtySizeGateTest {

    private val size80x24 = PtySizeGate.Size(cols = 80, rows = 24)

    @Test
    fun firstSizeIsSentImmediately() {
        val gate = PtySizeGate()
        assertEquals(PtySizeGate.Decision.Send(size80x24), gate.onSize(size80x24, nowMs = 1_000))
    }

    @Test
    fun laterSizesDebounceAndCoalesce() {
        val gate = PtySizeGate(debounceMs = 100)
        gate.onSize(size80x24, nowMs = 0)

        // A layout storm inside the window: only the newest size survives.
        assertEquals(
            PtySizeGate.Decision.Wait(100),
            gate.onSize(PtySizeGate.Size(90, 30), nowMs = 0),
        )
        assertEquals(
            PtySizeGate.Decision.Wait(100),
            gate.onSize(PtySizeGate.Size(100, 40), nowMs = 0),
        )
        assertEquals(
            PtySizeGate.Decision.Wait(40),
            gate.onSize(PtySizeGate.Size(120, 50), nowMs = 60),
        )
        // Too early: the gate asks to be polled again rather than dropping it.
        assertEquals(PtySizeGate.Decision.Wait(40), gate.onTimer(nowMs = 60))
        // Timer fires at lastSent + debounce → exactly one send, of the latest.
        assertEquals(
            PtySizeGate.Decision.Send(PtySizeGate.Size(120, 50)),
            gate.onTimer(nowMs = 100),
        )
        assertEquals(PtySizeGate.Decision.Idle, gate.onTimer(nowMs = 140))
    }

    @Test
    fun outsideTheWindowTheSizeIsSentAtOnce() {
        val gate = PtySizeGate(debounceMs = 100)
        gate.onSize(size80x24, nowMs = 0)
        assertEquals(PtySizeGate.Decision.Idle, gate.onSize(size80x24, nowMs = 50)) // duplicate layout pass
        assertEquals(
            PtySizeGate.Decision.Send(PtySizeGate.Size(90, 30)),
            gate.onSize(PtySizeGate.Size(90, 30), nowMs = 150),
        )
    }

    @Test
    fun resetSendsTheNextSizeImmediatelyAgain() {
        val gate = PtySizeGate(debounceMs = 100)
        gate.onSize(size80x24, nowMs = 0)
        gate.onSize(PtySizeGate.Size(90, 30), nowMs = 10)
        assertTrue(gate.hasPending)
        // Reconnect: upstream re-pushes the current size right after `open`.
        gate.reset()
        assertTrue(!gate.hasPending)
        assertEquals(
            PtySizeGate.Decision.Send(PtySizeGate.Size(90, 30)),
            gate.onSize(PtySizeGate.Size(90, 30), nowMs = 20),
        )
    }

    @Test
    fun invalidSizesAreIgnored() {
        val gate = PtySizeGate()
        assertEquals(PtySizeGate.Decision.Idle, gate.onSize(PtySizeGate.Size(0, 24), nowMs = 0))
        assertEquals(PtySizeGate.Decision.Idle, gate.onSize(PtySizeGate.Size(80, -1), nowMs = 0))
        // …and an invalid size must not consume the "first send" slot.
        assertEquals(PtySizeGate.Decision.Send(size80x24), gate.onSize(size80x24, nowMs = 0))
    }

    // ------------------------------------------------------------- geometry

    @Test
    fun gridMathFollowsTheTermuxFormulas() {
        val cell = TerminalGeometry.cell(textSizePx = 20f, advanceRatio = 0.5f, lineSpacingRatio = 1.5f)
        assertEquals(10f, cell.widthPx, 0.001f)
        assertEquals(30f, cell.heightPx, 0.001f)
        // 1000 / 10 = 100 cols; (600 - 30) / 30 = 19 rows.
        assertEquals(
            PtySizeGate.Size(cols = 100, rows = 19),
            TerminalGeometry.colsRows(1_000, 600, cell, lineSpacingAndAscentPx = 30f),
        )
    }

    @Test
    fun gridMathClampsToFourAndIgnoresZeroSizedSlots() {
        val cell = TerminalGeometry.cell(textSizePx = 10f)
        assertEquals(
            PtySizeGate.Size(TerminalGeometry.MIN_COLS, TerminalGeometry.MIN_ROWS),
            TerminalGeometry.colsRows(5, 5, cell, lineSpacingAndAscentPx = 0f),
        )
        // A zero-size view never resizes the server grid (upstream returns early).
        assertNull(TerminalGeometry.colsRows(0, 600, cell, lineSpacingAndAscentPx = 1f))
        assertNull(TerminalGeometry.colsRows(800, 0, cell, lineSpacingAndAscentPx = 1f))
        assertNull(TerminalGeometry.colsRows(800, 600, TerminalGeometry.Cell(0f, 0f), 1f))
    }

    @Test
    fun defaultLineSpacingAndAscentDerivesFromTheCellHeight() {
        val cell = TerminalGeometry.cell(textSizePx = 10f)
        val explicit = TerminalGeometry.colsRows(
            widthPx = 400,
            heightPx = 400,
            cell = cell,
            lineSpacingAndAscentPx = cell.heightPx * TerminalGeometry.LINE_SPACING_AND_ASCENT_RATIO,
        )
        assertEquals(explicit, TerminalGeometry.colsRows(400, 400, cell))
    }
}
