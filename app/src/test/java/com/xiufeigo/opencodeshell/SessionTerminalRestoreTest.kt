package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.terminal.share.SessionTerminalRestore
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [SessionTerminalRestore] — the VT bytes that repaint a
 * `PersistentPty.ReadResult.screen` (text + 0-based cursor) into a fresh
 * engine. Pure string/bytes assertions, no sockets.
 */
class SessionTerminalRestoreTest {

    private fun bytes(text: String, cursorX: Int, cursorY: Int): String =
        String(SessionTerminalRestore.screenBytes(text, cursorX, cursorY), Charsets.UTF_8)

    @Test
    fun `the screen is erased and every non-blank row lands at its own position`() {
        val out = bytes("top\n\nbottom", cursorX = 0, cursorY = 0)
        assertEquals("\u001b[H\u001b[2J\u001b[1;1Htop\u001b[3;1Hbottom\u001b[1;1H", out)
    }

    @Test
    fun `the cursor parks at the zero-based screen cell`() {
        val out = bytes("a", cursorX = 4, cursorY = 2)
        assertEquals("\u001b[H\u001b[2J\u001b[1;1Ha\u001b[3;5H", out)
    }

    @Test
    fun `negative or missing cursor cells clamp to the home cell`() {
        val out = bytes("", cursorX = -3, cursorY = -1)
        assertEquals("\u001b[H\u001b[2J\u001b[1;1H", out)
    }

    @Test
    fun `unicode rows round-trip as utf-8`() {
        val out = bytes("中文行", cursorX = 0, cursorY = 1)
        assertEquals("\u001b[H\u001b[2J\u001b[1;1H中文行\u001b[2;1H", out)
    }
}
