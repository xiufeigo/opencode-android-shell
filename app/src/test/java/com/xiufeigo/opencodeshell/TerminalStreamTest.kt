package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.terminal.share.AnsiStripper
import com.xiufeigo.opencodeshell.ui.terminal.share.PlaceholderPtyScreenEngine
import com.xiufeigo.opencodeshell.ui.terminal.share.PtyOutputPreview
import com.xiufeigo.opencodeshell.ui.terminal.share.QuerySplitter
import com.xiufeigo.opencodeshell.ui.terminal.share.RoutedSegment
import com.xiufeigo.opencodeshell.ui.terminal.share.TerminalAnswers
import com.xiufeigo.opencodeshell.ui.terminal.share.sanitizeForPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Down-link stream post-processing: answer routing ([TerminalAnswers] /
 * [QuerySplitter]) and the placeholder preview ([AnsiStripper] /
 * [PtyOutputPreview]). The `TerminalOutput.write` outlet in `research/06` §1.4 is
 * what these rules stand in for until the VT kernel is vendored.
 */
class TerminalStreamTest {

    // -------------------------------------------------------- answer routing

    @Test
    fun stateFreeDsrIsAnsweredByThePlaceholder() {
        assertEquals("\u001B[0n", TerminalAnswers.replyFor("\u001B[5n"))
    }

    @Test
    fun statefulQueriesAreLeftToTheEngine() {
        // Cursor position needs a screen model, DA declares a terminal identity,
        // OSC 52 needs the clipboard: all of them are the kernel's job.
        assertNull(TerminalAnswers.replyFor("\u001B[6n"))
        assertNull(TerminalAnswers.replyFor("\u001B[?6n"))
        assertNull(TerminalAnswers.replyFor("\u001B[c"))
        assertNull(TerminalAnswers.replyFor("\u001B[>c"))
        assertNull(TerminalAnswers.replyFor("\u001B]52;c;?\u0007"))
        assertTrue(TerminalAnswers.queryNeedsReply("\u001B[6n"))
        assertTrue(TerminalAnswers.queryNeedsReply("\u001B[>c"))
        assertTrue(TerminalAnswers.queryNeedsReply("\u001B]52;c;?\u0007"))
        assertTrue(TerminalAnswers.queryNeedsReply("\u001B[18t"))
    }

    @Test
    fun ordinaryEscapeSequencesAreOutputNotQueries() {
        assertTrue(!TerminalAnswers.queryNeedsReply("\u001B[31m"))
        assertTrue(!TerminalAnswers.queryNeedsReply("\u001B[?25l"))
        assertTrue(!TerminalAnswers.queryNeedsReply("\u001B[1;1H"))
        assertTrue(!TerminalAnswers.queryNeedsReply("\u001B]0;title\u0007"))
    }

    @Test
    fun splitterSeparatesTextQueriesAndFormatting() {
        val splitter = QuerySplitter()
        val segments = splitter.split("\u001B[31mred\u001B[6nok\u001B[0m")
        assertEquals(5, segments.size)
        assertEquals(RoutedSegment.Output("\u001B[31m"), segments[0])
        assertEquals(RoutedSegment.Output("red"), segments[1])
        assertEquals(RoutedSegment.Query("\u001B[6n"), segments[2])
        assertEquals(RoutedSegment.Output("ok"), segments[3])
        assertEquals(RoutedSegment.Output("\u001B[0m"), segments[4])
    }

    @Test
    fun splitterCarriesASequenceAcrossFrames() {
        val splitter = QuerySplitter()
        // WebSocket frames are arbitrary slices: the CSI arrives in two pieces.
        assertEquals(listOf(RoutedSegment.Output("a")), splitter.split("a\u001B[6"))
        assertEquals(listOf(RoutedSegment.Query("\u001B[6n")), splitter.split("n"))
        // …and an OSC that spans three frames is still one sequence: the plain
        // text before it is emitted immediately, the sequence is not.
        assertEquals(listOf(RoutedSegment.Output("b")), splitter.split("b\u001B]52;c"))
        assertTrue(splitter.split(";?").isEmpty())
        assertEquals(listOf(RoutedSegment.Query("\u001B]52;c;?\u0007")), splitter.split("\u0007"))
    }

    @Test
    fun splitterFlushesAnUnterminatedSequence() {
        val splitter = QuerySplitter()
        val flood = "\u001B]0;" + "x".repeat(QuerySplitter.MAX_PENDING_CHARS + 8)
        val segments = splitter.split(flood)
        assertTrue(segments.isNotEmpty())
        assertTrue(segments.all { it is RoutedSegment.Output })
    }

    // -------------------------------------------------------------- preview

    @Test
    fun stripperRemovesEscapesAndNormalizesCarriageReturns() {
        val stripper = AnsiStripper()
        assertEquals("red\n", stripper.strip("\u001B[31mred\u001B[0m\r\n"))
        assertEquals("a\nb", stripper.strip("a\rb"))
        assertEquals("plain", stripper.strip("plain"))
    }

    @Test
    fun stripperKeepsStateAcrossFrames() {
        val stripper = AnsiStripper()
        assertEquals("", stripper.strip("\u001B[3"))
        assertEquals("", stripper.strip("1m"))
        assertEquals("text", stripper.strip("text"))
        // A lone CR at the end of a frame is decided by the next frame: the text
        // before it is already emitted, the CR is not.
        assertEquals("one", stripper.strip("one\r"))
        assertEquals("\ntwo", stripper.strip("\ntwo"))
    }

    @Test
    fun previewKeepsABoundedTailWithoutSplittingSurrogates() {
        val preview = PtyOutputPreview.append("", "abcdef", limit = 4)
        assertEquals("cdef", preview)
        // tail keeps the whole emoji (2 chars) instead of half a surrogate pair
        val emoji = "\uD83D\uDE00"
        val tail = PtyOutputPreview.append("", "xx$emoji", limit = 3)
        assertEquals("x$emoji", tail)
        assertEquals("", PtyOutputPreview.append("abc", "d", limit = 0))
    }

    @Test
    fun sanitizeForPreviewBoundsWorkAndStrips() {
        assertEquals("hi", sanitizeForPreview("\u001B[32mhi\u001B[0m"))
        val long = "y".repeat(10_000)
        assertEquals(PtyOutputPreview.DEFAULT_LIMIT, sanitizeForPreview(long).length)
    }

    @Test
    fun placeholderEngineCountsBytesAndKeepsTheTail() {
        val engine = PlaceholderPtyScreenEngine(previewLimit = 32)
        engine.onOutput("hello ".toByteArray())
        engine.onOutput("\u001B[31mworld\u001B[0m".toByteArray())
        assertEquals(6 + 14, engine.bytesReceived)
        assertEquals("hello world", engine.preview)
        engine.onResize(80, 24, 8f, 18f)
        assertEquals(80, engine.cols)
        assertEquals(24, engine.rows)
        // No VT kernel yet: the placeholder must NOT claim to answer queries.
        assertTrue(!engine.answersQueries)
        engine.onReset()
        assertEquals(0L, engine.bytesReceived)
        assertEquals("", engine.preview)
    }
}
