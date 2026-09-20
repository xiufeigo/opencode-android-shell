package com.xiufeigo.opencodeshell.ui.terminal.share

/**
 * Resize policy for a remote PTY (M5 phase 1).
 *
 * Upstream (`research/02` §9.2 / `research/06` §1.6 step 5): the terminal's
 * `onResize` pushes the **first** size immediately and debounces every later
 * one by 100 ms, then `PUT /pty/:id {size:{rows,cols}}` — resize never travels
 * over the WebSocket.
 *
 * The gate is a pure state machine driven by an injected clock, so the
 * debounce/coalescing rules are unit-tested without timers; [PtyClient] only
 * executes the returned decision.
 */
class PtySizeGate(private val debounceMs: Long = PtyProtocol.RESIZE_DEBOUNCE_MS) {

    data class Size(val cols: Int, val rows: Int) {
        val isValid: Boolean get() = cols > 0 && rows > 0
    }

    sealed interface Decision {
        /** Call `PUT /pty/:id` right now. */
        data class Send(val size: Size) : Decision

        /** Arm a timer for [delayMs] and then call [onTimer]. */
        data class Wait(val delayMs: Long) : Decision

        /** Nothing to do (invalid or duplicate size). */
        data object Idle : Decision
    }

    private var firstSendArmed = true
    private var lastSent: Size? = null
    private var lastSentAtMs = 0L
    private var pending: Size? = null

    /** Next size is sent immediately again — used on (re)connect and on reset. */
    fun reset() {
        firstSendArmed = true
        lastSent = null
        lastSentAtMs = 0L
        pending = null
    }

    val hasPending: Boolean get() = pending != null

    fun onSize(size: Size, nowMs: Long): Decision {
        if (!size.isValid) return Decision.Idle
        if (firstSendArmed) {
            firstSendArmed = false
            accept(size, nowMs)
            return Decision.Send(size)
        }
        if (size == lastSent && pending == null) return Decision.Idle // no-op layout pass
        if (size == pending) return Decision.Wait(pendingDelay(nowMs)) // already scheduled
        pending = size
        val elapsed = nowMs - lastSentAtMs
        return if (elapsed >= debounceMs) {
            accept(size, nowMs)
            Decision.Send(size)
        } else {
            Decision.Wait(debounceMs - elapsed)
        }
    }

    /** Fired when a [Decision.Wait] timer elapses. */
    fun onTimer(nowMs: Long): Decision {
        val size = pending ?: return Decision.Idle
        val delay = pendingDelay(nowMs)
        if (delay > 0L) return Decision.Wait(delay)
        accept(size, nowMs)
        return Decision.Send(size)
    }

    private fun pendingDelay(nowMs: Long): Long =
        (lastSentAtMs + debounceMs - nowMs).coerceAtLeast(0L)

    private fun accept(size: Size, nowMs: Long) {
        lastSent = size
        lastSentAtMs = nowMs
        pending = null
    }
}

/**
 * Cell metrics → `(cols, rows)`, mirroring `research/06` §1.6 step 5:
 *
 * ```
 * cols = max(4, viewWidth / fontWidth)
 * rows = max(4, (viewHeight - fontLineSpacingAndAscent) / fontLineSpacing)
 * ```
 *
 * Until the VT kernel is vendored (phase 2) the renderer cannot report real
 * glyph metrics, so [cell] derives an approximation from the terminal font
 * size; phase 2 replaces the *inputs* with `TerminalRenderer.getFontWidth()` /
 * `getFontLineSpacing()` / `getFontLineSpacingAndAscent(…)` — the formulas and
 * the `max(4, …)` clamps stay.
 */
object TerminalGeometry {

    /** Monospace advance ≈ 0.6 em for the bundled mono faces. */
    const val DEFAULT_ADVANCE_RATIO = 0.6f

    /** Termux `TerminalRenderer` line spacing ≈ 1.35 em. */
    const val DEFAULT_LINE_SPACING_RATIO = 1.35f

    /** `fontLineSpacing + fontAscent` as a multiple of the line spacing. */
    const val LINE_SPACING_AND_ASCENT_RATIO = 1.23f

    const val MIN_COLS = 4
    const val MIN_ROWS = 4

    data class Cell(val widthPx: Float, val heightPx: Float)

    fun cell(
        textSizePx: Float,
        advanceRatio: Float = DEFAULT_ADVANCE_RATIO,
        lineSpacingRatio: Float = DEFAULT_LINE_SPACING_RATIO,
    ): Cell = Cell(
        widthPx = (textSizePx * advanceRatio).coerceAtLeast(1f),
        heightPx = (textSizePx * lineSpacingRatio).coerceAtLeast(1f),
    )

    /**
     * `null` when the slot has no area yet — upstream
     * `TerminalView.updateSize()` returns early for a zero-size view instead of
     * resizing to `4x4`, and so do we.
     */
    fun colsRows(
        widthPx: Int,
        heightPx: Int,
        cell: Cell,
        lineSpacingAndAscentPx: Float = cell.heightPx * LINE_SPACING_AND_ASCENT_RATIO,
    ): PtySizeGate.Size? {
        if (widthPx <= 0 || heightPx <= 0) return null
        if (cell.widthPx <= 0f || cell.heightPx <= 0f) return null
        val cols = maxOf(MIN_COLS, (widthPx / cell.widthPx).toInt())
        val rows = maxOf(MIN_ROWS, ((heightPx - lineSpacingAndAscentPx) / cell.heightPx).toInt())
        return PtySizeGate.Size(cols = cols, rows = rows)
    }
}
