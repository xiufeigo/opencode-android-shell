package com.xiufeigo.opencodeshell.ui.terminal.share

import android.os.Handler
import android.os.Looper
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalIo
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSessionClient
import com.termux.terminal.TextStyle

/**
 * **M5 phase 2 — the real VT kernel** (`research/06_terminal_selection.md` §1.6 A′).
 *
 * This is the termux implementation of the phase-1 seam [PtyScreenEngine]:
 *
 * | [PtyScreenEngine] | here |
 * |---|---|
 * | [onOutput] | `TerminalEmulator.append(bytes, len)` — the raw WS bytes, untouched |
 * | [onResize] | `TerminalEmulator.resize(cols, rows)` |
 * | [onReset] | `TerminalEmulator.reset()` |
 * | [attachUplink] | the same sink the emulator's [TerminalOutput] writes to |
 * | [answersQueries] | **`true`** — the emulator answers DSR/DA/OSC 52 itself |
 *
 * **Up-link** — [SocketOutput] is the *only* [TerminalOutput] of the emulator, so every byte the
 * terminal wants written back (DSR/DA answers, OSC 52 clipboard replies, mouse reports, bracketed
 * paste) leaves through `write(byte[], int, int)` → [uplink] → `PtyClient.sendBytes` → the PTY.
 * Bytes produced while no socket is open are buffered (bounded by [PENDING_UPLINK_LIMIT]) and
 * flushed by [RemoteTerminalIo.write], because that call is what makes the emulator emit its
 * answers — see the ordering note in `research/_M5b_vt_kernel_status.md` §3.
 *
 * **Threading** — the emulator is a single-threaded state machine: `append()` mutates the screen
 * that `TerminalView.onDraw` reads. Everything is therefore funnelled through one [Handler] turn
 * queue via [SurfaceRenderer], so appends, resizes and drawing are serialized on the main thread.
 * In JVM tests [DirectSurface] runs inline.
 */
class TermuxPtyScreenEngine(
    val cols: Int,
    val rows: Int,
    /** `DEFAULT_TERMINAL_TRANSCRIPT_ROWS` = 2000 (`research/06` §1.5 A-5). */
    transcriptRows: Int = DEFAULT_TRANSCRIPT_ROWS,
    /** Optional terminal client hooks (cursor style, title, clipboard, logging). */
    private val sessionClient: TerminalSessionClient = NoopSessionClient(),
    /** Themed palette: `{ansi 0..15, foreground, background, cursor}`. `null` = termux defaults. */
    palette: IntArray? = null,
    private val surface: SurfaceRenderer = DirectSurface(),
) : PtyScreenEngine {

    /** The termux emulator — the object `TerminalView` renders and this engine drives. */
    val emulator: TerminalEmulator = TerminalEmulator(
        SocketOutput(),
        cols.coerceAtLeast(MIN_DIMENSION),
        rows.coerceAtLeast(MIN_DIMENSION),
        transcriptRows,
        sessionClient,
    )

    init {
        // Themed palette before any byte is appended: `{ansi 0..15, foreground, background, cursor}`.
        // An OSC 4/10/11/12 sequence from the shell still wins afterwards (upstream behaviour).
        if (palette != null) {
            val colors = emulator.mColors.mCurrentColors
            for (index in colors.indices) {
                if (index < palette.size) colors[index] = palette[index]
            }
        }
    }

    /** The VT kernel consumes the stream and answers its own queries. */
    override val answersQueries: Boolean get() = true

    /** Every down-link chunk seen, for the panel's diagnostics line. */
    @Volatile
    var bytesReceived: Long = 0L
        private set

    /** Set once the socket is open; see the upload buffer note on the class. */
    @Volatile
    private var uplink: ((ByteArray) -> Unit)? = null

    private val pendingUplink = ArrayDeque<ByteArray>()

    /** Reused scratch buffer for the `onReset` erase sequence (single-threaded by construction). */
    private val clearBuffer = ByteArray(CLEAR_SCREEN.size)

    @Volatile
    private var pendingUplinkBytes = 0

    /** Cell metrics of the last [onResize]; `0` until the view has been measured. */
    @Volatile
    var cellWidthPx: Float = 0f
        private set

    @Volatile
    var cellHeightPx: Float = 0f
        private set

    @Volatile
    var cellHeightAndAscentPx: Float = 0f
        private set

    @Volatile
    var gridCols: Int = cols
        private set

    @Volatile
    var gridRows: Int = rows
        private set

    @Volatile
    private var released = false

    override fun onOutput(bytes: ByteArray) {
        if (released || bytes.isEmpty()) return
        bytesReceived += bytes.size
        // Copy: OkHttp reuses nothing, but the byte array we get is a defensive copy of a
        // ByteString and the handler turn runs later, so ownership is taken explicitly.
        val chunk = ByteArray(bytes.size).also { bytes.copyInto(it) }
        surface.run { emulator.append(chunk, chunk.size) }
    }

    override fun onResize(cols: Int, rows: Int, cellWidthPx: Float, cellHeightPx: Float) {
        this.cellWidthPx = cellWidthPx
        this.cellHeightPx = cellHeightPx
        val safeCols = cols.coerceAtLeast(MIN_DIMENSION)
        val safeRows = rows.coerceAtLeast(MIN_DIMENSION)
        gridCols = safeCols
        gridRows = safeRows
        surface.run {
            // `TerminalEmulator.resize` throws below 2 columns/rows and no-ops on equal sizes.
            emulator.resize(safeCols, safeRows)
        }
    }

    /**
     * Wipe the terminal. `TerminalEmulator.reset()` only resets parser/style/margins state —
     * it does **not** clear the visible screen (neither does upstream `TerminalSession.reset()`,
     * which merely forwards) — so the classic `CSI 2J` (erase display) + `CSI H` (cursor home) are
     * injected afterwards. Without that, a reconnect that deliberately replays history
     * (`PtyClient.initialCursor = null`) would append on top of the old screen.
     */
    override fun onReset() {
        surface.run {
            emulator.reset()
            CLEAR_SCREEN.copyInto(clearBuffer)
            emulator.append(clearBuffer, CLEAR_SCREEN.size)
        }
    }

    override fun attachUplink(sink: (ByteArray) -> Unit) {
        uplink = sink
    }

    /** Called by [RemoteTerminalIo] when the socket is gone, so nothing is written into the void. */
    fun detachUplink() {
        uplink = null
    }

    override fun release() {
        released = true
        detachUplink()
        synchronized(pendingUplink) {
            pendingUplink.clear()
            pendingUplinkBytes = 0
        }
    }

    /**
     * Read-only screen text, for diagnostics and tests (never used for cursor math).
     *
     * Deliberately **not** `TerminalBuffer.getTranscriptText()`: that helper `.trim()`s, which
     * hides leading blank rows. This reads the live screen (row 0 through the last screen row, no
     * scrollback) and keeps the row structure, with trailing blanks trimmed per line.
     */
    fun screenText(): String {
        val raw = emulator.screen.getSelectedText(0, 0, emulator.mColumns - 1, emulator.mRows - 1)
        return raw.split('\n').joinToString("\n") { it.trimEnd() }
    }

    /** ANSI palette index → ARGB, e.g. [TextStyle.COLOR_INDEX_FOREGROUND]. */
    fun colorAt(index: Int): Int = emulator.mColors.mCurrentColors[index]

    private fun emit(bytes: ByteArray, offset: Int, count: Int) {
        if (count <= 0) return
        val chunk = ByteArray(count).also { bytes.copyInto(it, 0, offset, offset + count) }
        val sink = uplink
        if (sink != null) {
            sink(chunk)
            return
        }
        synchronized(pendingUplink) {
            if (pendingUplinkBytes + chunk.size > PENDING_UPLINK_LIMIT) return@synchronized
            pendingUplink.addLast(chunk)
            pendingUplinkBytes += chunk.size
        }
    }

    /**
     * Flush everything the emulator queued while the socket was down. Called immediately before a
     * key/IME byte goes out, so an answer produced by *this* input always follows it.
     */
    private fun flushPending() {
        val sink = uplink ?: return
        while (true) {
            val next = synchronized(pendingUplink) {
                pendingUplink.removeFirstOrNull()?.also { pendingUplinkBytes -= it.size }
            } ?: return
            sink(next)
        }
    }

    /** `TerminalOutput` → uplink. The emulator's single up-link outlet (`research/06` §1.4). */
    private inner class SocketOutput : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) = emit(data, offset, count)

        override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit

        override fun clipboardText(text: String?) = Unit

        override fun onBell() = Unit

        override fun onColorsChanged() = Unit
    }

    /**
     * The transport `TerminalView` talks to: key/IME bytes in, grid size out, emulator supplied.
     * See the vendored `com.termux.terminal.TerminalIo` and `TerminalView.attachIo`.
     */
    inner class RemoteTerminalIo(
        /** `PtyClient.sendBytes` — the PTY up-link. */
        private val writeBytes: (ByteArray) -> Unit,
        /** `PtyClient.resize` — `PtySizeGate` → `PUT /pty/:id {size}`. */
        private val resizePty: (cols: Int, rows: Int) -> Unit,
    ) : TerminalIo {

        override fun write(data: ByteArray) {
            if (data.isEmpty()) return
            flushPending()
            writeBytes(data)
        }

        override fun updateSize(columns: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) {
            cellHeightAndAscentPx = cellHeightPx.toFloat()
            onResize(columns, rows, cellWidthPx.toFloat(), cellHeightPx.toFloat())
            resizePty(gridCols, gridRows)
        }

        override fun getEmulator(): TerminalEmulator = this@TermuxPtyScreenEngine.emulator

        override fun reset() = onReset()
    }

    companion object {
        /** `TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS`. */
        const val DEFAULT_TRANSCRIPT_ROWS = 2000

        /** `TerminalEmulator.resize` requires ≥2; the view already clamps to 4. */
        const val MIN_DIMENSION = 2

        /** Bound on answers queued while the socket is down (≈ a few screens of mouse reports). */
        const val PENDING_UPLINK_LIMIT = 64 * 1024

        /** `CSI 2 J` (erase display) + `CSI H` (cursor home), appended by [onReset]. */
        val CLEAR_SCREEN: ByteArray = "\u001b[2J\u001b[H".toByteArray(Charsets.US_ASCII)
    }
}

/**
 * Where emulator mutations run. Production uses [HandlerSurface] (one main-thread turn queue, so
 * `append`/`resize` can never interleave with `TerminalView.onDraw`); JVM tests use
 * [DirectSurface] because there is no looper there.
 */
fun interface SurfaceRenderer {
    fun run(block: () -> Unit)
}

/** Inline execution — the JVM-test path ([PtyClientTest] parity) and unit tests. */
class DirectSurface : SurfaceRenderer {
    override fun run(block: () -> Unit) = block()
}

/** Main-thread turn queue: preserves order and serializes against drawing. */
class HandlerSurface(
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : SurfaceRenderer {
    override fun run(block: () -> Unit) {
        if (Looper.myLooper() == handler.looper) block() else handler.post(block)
    }
}

/**
 * Default [TerminalSessionClient]. The vendored interface has no default implementations (its 14
 * `log*` methods mirror upstream `v0.117`), so this base class supplies the no-ops and lets the
 * panel override only what it cares about (title / bell / clipboard).
 *
 * Note there is no `onTextChanged` host callback in the remote form: the *view* invalidates itself
 * (`TerminalView.onScreenUpdated()`) and the panel recomposes from the [PtyClient.Listener] tick.
 */
open class NoopSessionClient : TerminalSessionClient {

    override fun onTextChanged(session: com.termux.terminal.TerminalSession?) = Unit

    override fun onTitleChanged(session: com.termux.terminal.TerminalSession?) = Unit

    override fun onSessionFinished(session: com.termux.terminal.TerminalSession?) = Unit

    override fun onClipboardText(session: com.termux.terminal.TerminalSession?, text: String?) = Unit

    override fun onBell(session: com.termux.terminal.TerminalSession?) = Unit

    override fun onColorsChanged(session: com.termux.terminal.TerminalSession?) = Unit

    override fun onTerminalCursorStateChange(state: Boolean) = Unit

    /** `null` → `TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE` (block). */
    override fun getTerminalCursorStyle(): Int? = null

    override fun logError(tag: String?, message: String?) = Unit

    override fun logWarn(tag: String?, message: String?) = Unit

    override fun logInfo(tag: String?, message: String?) = Unit

    override fun logDebug(tag: String?, message: String?) = Unit

    override fun logVerbose(tag: String?, message: String?) = Unit

    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit

    override fun logStackTrace(tag: String?, e: Exception?) = Unit
}
