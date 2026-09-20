package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.terminal.share.PtyProtocol
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Wire rules of the PTY/WebSocket protocol (`research/04_data_api.md` §6.2,
 * `research/02_ui_screens.md` §9.2). No Android, no sockets — pure functions.
 */
class PtyProtocolTest {

    // ----------------------------------------------------------------- backoff

    @Test
    fun backoffFollowsUpstreamFormula() {
        // min(250 * 2^min(tries,4), 4000) with tries = consecutive failures.
        assertEquals(250L, PtyProtocol.backoffDelayMs(0))
        assertEquals(500L, PtyProtocol.backoffDelayMs(1))
        assertEquals(1_000L, PtyProtocol.backoffDelayMs(2))
        assertEquals(2_000L, PtyProtocol.backoffDelayMs(3))
        assertEquals(4_000L, PtyProtocol.backoffDelayMs(4))
        // Exponent is capped at 4, and so is the delay.
        assertEquals(4_000L, PtyProtocol.backoffDelayMs(5))
        assertEquals(4_000L, PtyProtocol.backoffDelayMs(40))
        // Defensive: a negative counter must not shift left.
        assertEquals(250L, PtyProtocol.backoffDelayMs(-2))
    }

    // ---------------------------------------------------------------- framing

    @Test
    fun metaFrameCarriesTheServerCursor() {
        val frame = byteArrayOf(PtyProtocol.META_LEADING_BYTE) + """{"cursor":42}""".toByteArray()
        assertEquals(PtyProtocol.DownFrame.Meta(42L), PtyProtocol.classifyBinaryFrame(frame))
    }

    @Test
    fun metaFrameAcceptsNumberShapesAndRejectsJunk() {
        val leading = byteArrayOf(PtyProtocol.META_LEADING_BYTE)
        assertEquals(
            PtyProtocol.DownFrame.Meta(7L),
            PtyProtocol.classifyBinaryFrame(leading + """{"cursor":7.0}""".toByteArray()),
        )
        // Malformed payload: the frame is still metadata (never terminal bytes),
        // but there is no cursor to adopt — the caller keeps the previous one.
        assertEquals(
            PtyProtocol.DownFrame.Meta(null),
            PtyProtocol.classifyBinaryFrame(leading + """{"cursor":"7"}""".toByteArray()),
        )
        assertEquals(
            PtyProtocol.DownFrame.Meta(null),
            PtyProtocol.classifyBinaryFrame(leading + "{oops".toByteArray()),
        )
        assertEquals(
            PtyProtocol.DownFrame.Meta(null),
            PtyProtocol.classifyBinaryFrame(leading),
        )
    }

    @Test
    fun binaryFrameWithoutLeadingZeroIsTerminalOutput() {
        val bytes = "hello".toByteArray()
        assertEquals(PtyProtocol.DownFrame.Output("hello"), PtyProtocol.classifyBinaryFrame(bytes))
    }

    @Test
    fun emptyFrameIsIgnored() {
        assertEquals(PtyProtocol.DownFrame.Empty, PtyProtocol.classifyBinaryFrame(ByteArray(0)))
    }

    @Test
    fun outputDecodesUtf8NotUtf16() {
        // A CJK glyph is 3 UTF-8 bytes: the down-link is a byte stream, which is
        // exactly why the cursor may never be derived from Kotlin String.length.
        val text = "中"
        val frame = PtyProtocol.classifyBinaryFrame(text.toByteArray(Charsets.UTF_8))
        assertEquals(PtyProtocol.DownFrame.Output("中"), frame)
    }

    // ----------------------------------------------------------- close codes

    @Test
    fun closeDispositionDistinguishesExitGoneAndAbnormal() {
        assertEquals(
            PtyProtocol.CloseDisposition.EXITED,
            PtyProtocol.closeDisposition(PtyProtocol.CLOSE_NORMAL, null),
        )
        assertEquals(
            PtyProtocol.CloseDisposition.GONE,
            PtyProtocol.closeDisposition(PtyProtocol.CLOSE_SESSION_NOT_FOUND, "session not found"),
        )
        // The reason string wins even if a proxy rewrites the code.
        assertEquals(
            PtyProtocol.CloseDisposition.GONE,
            PtyProtocol.closeDisposition(1006, "session not found"),
        )
        assertEquals(
            PtyProtocol.CloseDisposition.RETRY,
            PtyProtocol.closeDisposition(1011, "server restart"),
        )
    }

    // ---------------------------------------------------------- path helpers

    @Test
    fun queryNamesAndPathsMatchBothApiFaces() {
        assertEquals("directory", PtyProtocol.directoryQueryName(v2 = false))
        assertEquals("location[directory]", PtyProtocol.directoryQueryName(v2 = true))
        assertEquals(listOf("pty"), PtyProtocol.ptySegments(v2 = false))
        assertEquals(listOf("api", "pty"), PtyProtocol.ptySegments(v2 = true))
        // §9.2's session cap is the constant the tab model uses.
        assertEquals(20, PtyProtocol.MAX_TERMINAL_SESSIONS)
        assertEquals(100L, PtyProtocol.RESIZE_DEBOUNCE_MS)
    }
}
