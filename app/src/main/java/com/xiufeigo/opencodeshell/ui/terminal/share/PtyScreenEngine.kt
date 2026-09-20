package com.xiufeigo.opencodeshell.ui.terminal.share

/**
 * **VT kernel seam (M5 phase 1).** This is the only contract the data link
 * needs from a screen engine; phase 2 (vendoring termux `terminal-emulator` +
 * `terminal-view` with the `TerminalIo` patch, `research/06` §4.2) implements it
 * with `TerminalEmulator` and *nothing else in this package changes*:
 *
 * | [PtyScreenEngine] member | termux A′ implementation |
 * |---|---|
 * | [onOutput] | `emulator.append(bytes, length)` |
 * | [onResize] | `emulator.resize(cols, rows)` (+ `TerminalView.updateSize()` for the renderer) |
 * | [onReset] | `emulator.reset()` |
 * | [attachUplink] | `TerminalOutput.write(byte[],int,int)` → the same sink |
 * | [answersQueries] | `true` — `TerminalEmulator` answers DSR/DA/OSC 52 itself |
 *
 * Why [attachUplink] exists: `TerminalOutput.write(byte[],int,int)` is the
 * *single* up-link outlet of the termux emulator (`research/06` §1.4) — the
 * terminal's answers (DSR/DA/OSC 52) and mouse reports all leave through it.
 * Wiring it to [PtyClient.sendBytes] is what makes those answers reach the PTY.
 */
interface PtyScreenEngine {

    /**
     * `true` when the engine consumes the raw stream and answers terminal
     * queries itself. When `false` (no VT kernel yet) [PtyClient] splits queries
     * out of the stream and answers the state-free ones — see [TerminalAnswers].
     */
    val answersQueries: Boolean get() = false

    /** Terminal bytes exactly as they arrived on the wire. */
    fun onOutput(bytes: ByteArray)

    /** Grid size changed; upstream uses this for `TerminalEmulator.resize`. */
    fun onResize(cols: Int, rows: Int, cellWidthPx: Float, cellHeightPx: Float) {}

    /** Wipe terminal state (reconnect with a fresh replay, or `terminal.reset`). */
    fun onReset() {}

    /**
     * Registers the up-link sink: everything the engine wants written to the PTY
     * (query answers, mouse reports, clipboard replies, paste echo). Called once
     * per connection attempt.
     */
    fun attachUplink(sink: (ByteArray) -> Unit) {}

    /** Drop heavy resources when the terminal tab is closed (close = unmount). */
    fun release() {}
}

/**
 * Phase-1 stand-in engine: it renders nothing, but it *does* prove the data
 * link — it keeps a byte counter, the last known grid size and a sanitized tail
 * of the raw stream, which the placeholder slot in `ui/terminal/TerminalPanel`
 * draws. It deliberately reports [answersQueries] = `false`, so the state-free
 * DSR answer path ([TerminalAnswers]) is exercised on real traffic.
 */
class PlaceholderPtyScreenEngine(
    private val previewLimit: Int = PtyOutputPreview.DEFAULT_LIMIT,
) : PtyScreenEngine {

    private val stripper = AnsiStripper()

    @Volatile
    var bytesReceived: Long = 0L
        private set

    @Volatile
    var cols: Int = 0
        private set

    @Volatile
    var rows: Int = 0
        private set

    @Volatile
    var preview: String = ""
        private set

    override fun onOutput(bytes: ByteArray) {
        val text = String(bytes, Charsets.UTF_8)
        synchronized(this) {
            bytesReceived += bytes.size
            preview = PtyOutputPreview.append(preview, stripper.strip(text), previewLimit)
        }
    }

    override fun onResize(cols: Int, rows: Int, cellWidthPx: Float, cellHeightPx: Float) {
        this.cols = cols
        this.rows = rows
    }

    override fun onReset() {
        synchronized(this) {
            bytesReceived = 0L
            preview = ""
            stripper.reset()
        }
    }

    override fun release() {
        synchronized(this) { preview = "" }
    }
}
