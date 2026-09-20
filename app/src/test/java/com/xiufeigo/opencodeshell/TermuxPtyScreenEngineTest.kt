package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.terminal.share.DirectSurface
import com.xiufeigo.opencodeshell.ui.terminal.share.TermuxPtyScreenEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M5 phase 2 — the termux VT kernel seam (`research/06_terminal_selection.md` §1.6 A′).
 *
 * Everything here runs on the JVM without Robolectric: the vendored `terminal-emulator` module
 * touches no Android class except `android.util.Base64` (only on an OSC 52 *query*, and
 * `testOptions.unitTests.isReturnDefaultValues = true` turns that stub into `null` rather than
 * `RuntimeException`). The engine is created with [DirectSurface] so appends run inline.
 *
 * Coverage:
 * - [answersQueries] is `true`, i.e. [PtyClient] stops splitting queries out of the stream and the
 *   emulator answers them (`CSI 6n` is the case phase 1 deliberately refused to fake);
 * - down-link bytes reach the VT screen (`CSI`, UTF-8, wrap);
 * - up-link answers leave through `TerminalOutput.write` → the sink, and are buffered while no
 *   socket is attached, flushed *before* the next input byte;
 * - `TerminalIo.updateSize` re-flows the emulator **and** pushes the grid to the REST resize sink;
 * - `TerminalIo.write` is the PTY input path; `getEmulator()` supplies the view's emulator;
 * - `onReset` clears the screen, not just the parser state (reconnect-with-replay safety).
 */
class TermuxPtyScreenEngineTest {

    // ------------------------------------------------------------------ VT down-link

    @Test
    fun `emulator is created with the requested grid and answersQueries is true`() {
        val engine = newEngine(cols = 80, rows = 24)
        assertEquals(true, engine.answersQueries)
        assertEquals(80, engine.emulator.mColumns)
        assertEquals(24, engine.emulator.mRows)
        // T20: the emulator itself is the object the view renders (attachIo(io).emulator).
        assertNotNull(engine.emulator.screen)
    }

    @Test
    fun `output bytes are parsed into the VT screen`() {
        val engine = newEngine(cols = 20, rows = 5)
        engine.onOutput("hello\r\nworld".toByteArray(Charsets.UTF_8))
        val text = engine.screenText()
        assertTrue("expected the VT screen to contain both lines, was:\n$text", text.contains("hello"))
        assertTrue("expected the VT screen to contain both lines, was:\n$text", text.contains("world"))
        assertEquals("hello\r\nworld".toByteArray(Charsets.UTF_8).size.toLong(), engine.bytesReceived)
    }

    @Test
    fun `control sequences move the cursor instead of being printed`() {
        val engine = newEngine(cols = 20, rows = 4)
        // CSI 2;3H = row 2, column 3 (1-based) → "X" lands at external column index 2 of row 1.
        engine.onOutput("\u001b[2;3HX".toByteArray(Charsets.US_ASCII))
        val lines = engine.screenText().split('\n')
        assertTrue("expected at least two rows, was:\n${engine.screenText()}", lines.size >= 2)
        assertEquals("  X", lines[1].trimEnd())
        assertTrue("the escape bytes themselves must never be printed", !engine.screenText().contains("[2;3H"))
    }

    @Test
    fun `utf8 split across two frames still decodes`() {
        val engine = newEngine(cols = 10, rows = 2)
        val bytes = "中".toByteArray(Charsets.UTF_8)
        engine.onOutput(bytes.copyOfRange(0, 1))
        engine.onOutput(bytes.copyOfRange(1, bytes.size))
        assertTrue("expected 中 on the screen, was:\n${engine.screenText()}", engine.screenText().contains("中"))
    }

    // --------------------------------------------------------------------- up-link

    @Test
    fun `dsr dsr6n and da1 answers all leave through the sink`() {
        val engine = newEngine()
        val out = StringBuilder()
        engine.attachUplink { out.append(String(it, Charsets.UTF_8)) }

        engine.onOutput("\u001b[5n".toByteArray(Charsets.US_ASCII))
        engine.onOutput("\u001b[6n".toByteArray(Charsets.US_ASCII))
        engine.onOutput("\u001b[c".toByteArray(Charsets.US_ASCII))

        // `CSI 5n` = "OK", `CSI 6n` = cursor position report, `CSI c` = device attributes.
        // Phase 1 answered only the stateless first one (TerminalAnswers); the VT kernel answers
        // all three, which is exactly what answersQueries=true turns on.
        assertTrue("expected ESC[0n, got: ${escape(out)}", out.toString().contains("\u001b[0n"))
        assertTrue("expected a cursor-position report, got: ${escape(out)}", out.contains("\u001b["))
        assertTrue("expected a device-attributes report, got: ${escape(out)}", out.contains("\u001b[?"))
        assertEquals(3, out.toString().split('\u001b').size - 1)
    }

    @Test
    fun `answers produced with no socket are buffered and flushed before the next key`() {
        val engine = newEngine()
        // No uplink yet (the WS is still connecting): the emulator's answer is buffered.
        engine.onOutput("\u001b[5n".toByteArray(Charsets.US_ASCII))

        val out = StringBuilder()
        engine.attachUplink { out.append(String(it, Charsets.UTF_8)) }
        assertEquals("nothing may be flushed until a key arrives", "", out.toString())

        val io = engine.remoteIo(writeBytes = { out.append(String(it, Charsets.UTF_8)) })
        io.write("a".toByteArray(Charsets.US_ASCII))

        // Buffer first, then the input byte — never the other way round, otherwise the answer
        // would be sent backwards in the PTY stream.
        assertEquals("\u001b[0na", out.toString())
    }

    @Test
    fun `unsolicited output while no socket is open is capped instead of growing forever`() {
        val engine = newEngine()
        // `CSI 6n` answered 5000 times with no sink: ~7 bytes each, well past the 64 KiB cap.
        val report = "\u001b[1;1R".toByteArray(Charsets.US_ASCII)
        repeat(5000) { engine.onOutput("\u001b[6n".toByteArray(Charsets.US_ASCII)) }

        val out = StringBuilder()
        engine.attachUplink { out.append(String(it, Charsets.UTF_8)) }
        val io = engine.remoteIo(writeBytes = { out.append(String(it, Charsets.UTF_8)) })
        io.write("k".toByteArray(Charsets.US_ASCII))

        assertEquals("k", out.substring(out.length - 1))
        assertTrue(
            "buffer must stay bounded (report=${report.size}B), got ${out.length}B",
            out.length <= TermuxPtyScreenEngine.PENDING_UPLINK_LIMIT + 1,
        )
    }

    // ---------------------------------------------------------------------- resize

    @Test
    fun `onResize reflows the emulator and pushes the grid to the REST sink`() {
        val engine = newEngine(cols = 80, rows = 24)
        var pushedCols = -1
        var pushedRows = -1
        val io = engine.remoteIo(resizePty = { cols, rows ->
            pushedCols = cols
            pushedRows = rows
        })

        // This is what `TerminalView.updateSize()` calls (measured from TerminalRenderer metrics).
        io.updateSize(100, 40, cellWidthPx = 9, cellHeightPx = 20)

        assertEquals(100, engine.emulator.mColumns)
        assertEquals(40, engine.emulator.mRows)
        assertEquals(100, engine.gridCols)
        assertEquals(40, engine.gridRows)
        assertEquals(100, pushedCols)
        assertEquals(40, pushedRows)
        assertEquals(9f, engine.cellWidthPx)
        assertEquals(20f, engine.cellHeightPx)
    }

    @Test
    fun `resize clamps below the emulator minimum before touching it`() {
        val engine = newEngine(cols = 80, rows = 24)
        val io = engine.remoteIo()
        // `TerminalEmulator.resize` throws for columns < 2; the view clamps at 4, this is the
        // defensive floor for a view measured at zero width.
        io.updateSize(1, 1, 0, 0)
        assertEquals(TermuxPtyScreenEngine.MIN_DIMENSION, engine.emulator.mColumns)
        assertEquals(TermuxPtyScreenEngine.MIN_DIMENSION, engine.emulator.mRows)
    }

    @Test
    fun `onResize is a no-op-safe duplicate`() {
        val engine = newEngine(cols = 80, rows = 24)
        val io = engine.remoteIo()
        io.updateSize(80, 24, 10, 20)
        io.updateSize(80, 24, 10, 20)
        assertEquals(80, engine.emulator.mColumns)
        assertEquals(24, engine.emulator.mRows)
    }

    // ------------------------------------------------------------------ transport io

    @Test
    fun `io exposes the engine emulator and writes input bytes verbatim`() {
        val engine = newEngine()
        val written = ArrayList<ByteArray>()
        val io = engine.remoteIo(writeBytes = { written.add(it) })

        assertTrue("attachIo() installs io.emulator", io.emulator === engine.emulator)
        io.write(byteArrayOf(0x03)) // Ctrl-C
        assertEquals(1, written.size)
        assertEquals(listOf(0x03.toByte()), written[0].toList())
        io.write(ByteArray(0))
        assertEquals("empty writes are dropped, not sent", 1, written.size)
    }

    @Test
    fun `detachUplink stops delivery and wakes nothing`() {
        val engine = newEngine()
        val out = StringBuilder()
        engine.attachUplink { out.append(String(it, Charsets.UTF_8)) }
        engine.detachUplink()
        engine.onOutput("\u001b[5n".toByteArray(Charsets.US_ASCII))
        assertEquals("", out.toString())
    }

    @Test
    fun `release drops the uplink and stops appending`() {
        val engine = newEngine(cols = 10, rows = 3)
        engine.release()
        engine.onOutput("ignored".toByteArray(Charsets.UTF_8))
        assertEquals(0L, engine.bytesReceived)
    }

    // ----------------------------------------------------------------------- reset
    @Test
    fun `onReset clears the screen not just the parser state`() {
        val engine = newEngine(cols = 10, rows = 3)
        engine.onOutput("stale".toByteArray(Charsets.UTF_8))
        assertTrue(engine.screenText().contains("stale"))

        engine.onReset()

        // `TerminalEmulator.reset()` alone does NOT clear the buffer (verified against the
        // vendored source), so the engine injects `CSI 2J` + `CSI H` — a full replay after a
        // reconnect must not append on top of the previous screen.
        assertTrue(
            "screen must be empty after onReset, was:\n${engine.screenText()}",
            engine.screenText().trim().isEmpty(),
        )
    }

    // --------------------------------------------------------------------- palette

    @Test
    fun `themed palette replaces the palette slots the kernel exposes`() {
        val palette = IntArray(com.termux.terminal.TextStyle.NUM_INDEXED_COLORS) { 0 }
        palette[0] = 0xFF010203.toInt()
        palette[com.termux.terminal.TextStyle.COLOR_INDEX_FOREGROUND] = 0xFFAABBCC.toInt()
        palette[com.termux.terminal.TextStyle.COLOR_INDEX_BACKGROUND] = 0xFF112233.toInt()
        palette[com.termux.terminal.TextStyle.COLOR_INDEX_CURSOR] = 0xFF445566.toInt()

        val engine = newEngine(cols = 8, rows = 3, palette = palette)

        assertEquals(0xFF010203.toInt(), engine.colorAt(0))
        assertEquals(
            0xFFAABBCC.toInt(),
            engine.colorAt(com.termux.terminal.TextStyle.COLOR_INDEX_FOREGROUND),
        )
        assertEquals(
            0xFF112233.toInt(),
            engine.colorAt(com.termux.terminal.TextStyle.COLOR_INDEX_BACKGROUND),
        )
    }

    // --------------------------------------------------------------------- helpers

    private fun newEngine(
        cols: Int = 80,
        rows: Int = 24,
        palette: IntArray? = null,
    ) = TermuxPtyScreenEngine(
        cols = cols,
        rows = rows,
        palette = palette,
        surface = DirectSurface(),
    )

    /** `RemoteTerminalIo` with recording sinks that default to no-ops. */
    private fun TermuxPtyScreenEngine.remoteIo(
        writeBytes: (ByteArray) -> Unit = {},
        resizePty: (Int, Int) -> Unit = { _, _ -> },
    ) = RemoteTerminalIo(writeBytes, resizePty)

    private fun escape(text: CharSequence) = text.toString().replace("\u001b", "ESC")
}

/**
 * Guard for the vendored kernel's own invariants that the phase-2 wiring depends on. If a future
 * vendored update changes one of these, the whole seam has to be re-checked — better to fail here
 * with a named test than on a device.
 */
class VendoredTermuxApiTest {

    @Test
    fun `terminal emulator ctor and output outlet keep their v0_117 shape`() {
        val emulator = com.termux.terminal.TerminalEmulator(
            object : com.termux.terminal.TerminalOutput() {
                override fun write(data: ByteArray, offset: Int, count: Int) = Unit
                override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
                override fun clipboardText(text: String?) = Unit
                override fun onBell() = Unit
                override fun onColorsChanged() = Unit
            },
            80,
            24,
            Integer.valueOf(2000),
            null,
        )
        assertEquals(80, emulator.mColumns)
        assertEquals(24, emulator.mRows)
        assertEquals(
            "DEFAULT_TERMINAL_TRANSCRIPT_ROWS",
            2000,
            com.termux.terminal.TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS,
        )
        assertEquals(
            "indexed-colour array must cover ansi 0..15, fg, bg and cursor",
            259,
            com.termux.terminal.TextStyle.NUM_INDEXED_COLORS,
        )
    }

    @Test
    fun `remote_io interface has exactly the four members the view patch needs`() {
        val members = com.termux.terminal.TerminalIo::class.java.declaredMethods
            .map { it.name }
            .toSortedSet()
        assertEquals(setOf("getEmulator", "reset", "updateSize", "write"), members)
    }

    @Test
    fun `terminal session never starts a local process`() {
        // The A′ form: `TerminalSession` is a compile-safe shell. No JNI, no `/dev/ptmx`, and
        // therefore no local shell and no 16 KB-page `.so` (`research/06` §1.5 A-4).
        val sessionClass = Class.forName("com.termux.terminal.TerminalSession")
        assertTrue(
            "JNI must stay a class with no native entry points",
            Class.forName("com.termux.terminal.JNI").declaredMethods.none { it.name.contains("native") },
        )
        assertNotNull(sessionClass)
        assertNotSame(0, sessionClass.declaredFields.size)
    }
}
