package com.xiufeigo.opencodeshell.ui.terminal.share

/**
 * Down-link stream post-processing for the PTY WebSocket (M5 phase 1).
 *
 * Everything here is pure Kotlin so it can be JVM-tested; the runtime wiring is
 * [PtyClient.kt], the VT kernel seam is [PtyScreenEngine].
 *
 * Two jobs:
 *
 * 1. **Answer routing** (`TerminalAnswers` + [QuerySplitter]). The VT kernel
 *    owns DSR / DA / OSC 52 / mouse reports: termux's `TerminalOutput.write()`
 *    is the single up-link outlet for them (`research/06` §1.4). Until that
 *    kernel is vendored (phase 2) the placeholder engine cannot answer, so the
 *    query sequences that require a reply are **split out of the stream** and
 *    answered by [TerminalAnswers.replyFor] when the reply needs no screen
 *    state (`CSI 5n` → `CSI 0n`); every stateful query is handed to the engine
 *    unchanged. [PtyScreenEngine.answersQueries] switches the split off once a
 *    real kernel is attached.
 *
 *    Note the split is *not* about forwarding bytes: sequences that need no
 *    reply stay in the output path, because the renderer needs them (colors,
 *    cursor moves, alt-screen switches).
 *
 * 2. **Dev preview** ([AnsiStripper] / [PtyOutputPreview]): the phase-1
 *    placeholder slot shows a sanitized tail of the raw stream so the data link
 *    is verifiable on device before any VT kernel exists.
 */
object TerminalAnswers {

    /**
     * `CSI 5n` ("status report") is the one query whose answer carries no
     * screen state, so the placeholder can answer it correctly. Reply is
     * `CSI 0n` = "OK" (no malfunction).
     */
    const val DSR_OK_REPLY: String = "\u001B[0n"

    private val WINDOW_REPLY_OPS = setOf("13", "14", "15", "16", "18", "19", "20", "21", "22", "23")

    /**
     * True when the terminal must answer this sequence. Covers CSI `n` (DSR,
     * incl. `6n` cursor position), CSI `c` (`>c`/`=c` device attributes), the
     * xterm window-reporting subset of CSI `t`, and OSC 52 queries.
     *
     * Mouse **reports** are up-link, not down-link: the kernel sends them via
     * [PtyScreenEngine] → `TerminalOutput.write` (`research/06` §1.4), so they
     * never appear here.
     */
    fun queryNeedsReply(sequence: String): Boolean {
        if (sequence.length < 3 || sequence[0] != ESC) return false
        return when (sequence[1]) {
            '[' -> when (sequence.last()) {
                // DSR (5n/6n/?6n…) and DA (c/>c/=c) always expect a reply.
                'n', 'c' -> true
                't' -> windowOp(sequence.substring(2, sequence.length - 1)) in WINDOW_REPLY_OPS
                else -> false
            }
            ']' -> isOsc52Query(sequence)
            else -> false
        }
    }

    /**
     * Reply for sequences the placeholder can answer without a screen model, or
     * `null` when the answer needs kernel state (cursor position, device
     * attributes, clipboard contents, window geometry). `null` means "hand it
     * to the engine" — phase 2 answers them from `TerminalEmulator`.
     */
    fun replyFor(sequence: String): String? {
        if (sequence == "\u001B[5n") return DSR_OK_REPLY
        return null
    }

    /** `CSI {params} t` → the first numeric parameter (window ops). */
    private fun windowOp(body: String): String {
        val digits = body.takeWhile { it.isDigit() }
        return digits
    }

    /** `ESC ] 52 ; <clipboard> ; ? <BEL|ST>` — an OSC 52 clipboard *query*. */
    private fun isOsc52Query(sequence: String): Boolean {
        val body = sequence.substring(2).trimEnd(BEL, '\\', ESC)
        val parts = body.split(';')
        return parts.size >= 3 && parts[0] == "52" && parts.last().trim() == "?"
    }
}

/** One classified slice of a down-link chunk. */
sealed interface RoutedSegment {
    /** Terminal bytes: feed the VT kernel (or the placeholder preview). */
    data class Output(val text: String) : RoutedSegment

    /** A sequence the terminal must answer (see [TerminalAnswers]). */
    data class Query(val sequence: String) : RoutedSegment
}

/**
 * Splits a down-link chunk into output/query segments. **Stateful on purpose:**
 * an escape sequence may straddle two WebSocket frames, so the incomplete tail
 * is carried into the next [split] call and any sequence that never terminates
 * is flushed as output once it exceeds [MAX_PENDING_CHARS] characters
 * (pathological data must not grow memory without bound).
 */
class QuerySplitter {

    private val scanner = AnsiScanner()

    fun split(chunk: String): List<RoutedSegment> {
        val segments = ArrayList<RoutedSegment>(4)
        scanner.feed(
            chunk = chunk,
            onText = { if (it.isNotEmpty()) segments += RoutedSegment.Output(it) },
            onEscape = { sequence ->
                if (TerminalAnswers.queryNeedsReply(sequence)) {
                    segments += RoutedSegment.Query(sequence)
                } else {
                    segments += RoutedSegment.Output(sequence)
                }
            },
        )
        return segments
    }

    /** Drops carried state (reconnect / terminal reset). */
    fun reset() = scanner.reset()

    companion object {
        /** Same bound as [AnsiScanner.MAX_PENDING_CHARS]. */
        const val MAX_PENDING_CHARS = AnsiScanner.MAX_PENDING_CHARS
    }
}

/**
 * Strips escape sequences / control characters for the placeholder preview.
 * Stateful for the same cross-frame reason as [QuerySplitter]; `\r\n` collapses
 * to `\n` and a lone `\r` becomes `\n` (a preview line, not a repaint).
 */
class AnsiStripper {

    private val scanner = AnsiScanner()
    private var pendingCarriageReturn = false

    fun strip(chunk: String): String {
        val out = StringBuilder(chunk.length)
        scanner.feed(
            chunk = chunk,
            onText = { out.append(clean(it)) },
            onEscape = { /* dropped: the preview shows text only */ },
        )
        return out.toString()
    }

    fun reset() {
        scanner.reset()
        pendingCarriageReturn = false
    }

    private fun clean(text: String): String {
        val out = StringBuilder(text.length)
        for (ch in text) {
            when {
                ch == '\r' -> pendingCarriageReturn = true
                ch == '\n' -> {
                    pendingCarriageReturn = false
                    out.append('\n')
                }
                pendingCarriageReturn -> {
                    pendingCarriageReturn = false
                    out.append('\n')
                    if (ch.isPreviewable()) out.append(ch)
                }
                ch.isPreviewable() -> out.append(ch)
                else -> Unit // other C0 controls / DEL are invisible in a preview
            }
        }
        return out.toString()
    }

    private fun Char.isPreviewable(): Boolean =
        this == '\t' || (code >= 0x20 && code != 0x7F)
}

/**
 * Bounded tail buffer for the placeholder preview (last [DEFAULT_LIMIT] chars,
 * never splitting a surrogate pair).
 */
object PtyOutputPreview {

    const val DEFAULT_LIMIT = 4_096

    fun append(current: String, chunk: String, limit: Int = DEFAULT_LIMIT): String {
        if (limit <= 0) return ""
        if (chunk.isEmpty()) return current
        val joined = current + chunk
        if (joined.length <= limit) return joined
        var start = joined.length - limit
        // Never start a preview on the low half of a surrogate pair.
        if (Character.isLowSurrogate(joined[start])) start++
        return joined.substring(start)
    }
}

/**
 * Stateless convenience wrapper: sanitize and trim in one call. The runtime
 * preview uses [AnsiStripper] + [PtyOutputPreview] directly so escape sequences
 * can straddle frames.
 */
fun sanitizeForPreview(text: String, limit: Int = PtyOutputPreview.DEFAULT_LIMIT): String {
    if (text.isEmpty()) return ""
    // Bound the work for a 2 MiB replay frame before stripping.
    val window = if (text.length > limit * 4) text.takeLast(limit * 4) else text
    return PtyOutputPreview.append("", AnsiStripper().strip(window), limit)
}

/**
 * Walks a string and reports complete escape sequences separately from text,
 * carrying an incomplete tail (an escape sequence split across frames) into the
 * next call. Shared by [QuerySplitter] and [AnsiStripper].
 */
internal class AnsiScanner(private val maxPendingChars: Int = MAX_PENDING_CHARS) {

    private var pending = ""

    fun reset() {
        pending = ""
    }

    fun feed(chunk: String, onText: (String) -> Unit, onEscape: (String) -> Unit) {
        if (pending.isEmpty() && chunk.indexOf(ESC) < 0) {
            if (chunk.isNotEmpty()) onText(chunk)
            return
        }
        val text = pending + chunk
        pending = ""
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (text[i] != ESC) {
                out.append(text[i])
                i++
                continue
            }
            val end = sequenceEnd(text, i)
            if (end < 0) {
                val tail = text.substring(i)
                if (tail.length > maxPendingChars) {
                    // Unterminated OSC/CSI: stop holding it, hand it over as text.
                    out.append(tail)
                } else {
                    pending = tail
                }
                break
            }
            if (out.isNotEmpty()) {
                onText(out.toString())
                out.setLength(0)
            }
            onEscape(text.substring(i, end))
            i = end
        }
        if (out.isNotEmpty()) onText(out.toString())
    }

    /** Exclusive end index of the escape sequence at [start], or -1 when truncated. */
    private fun sequenceEnd(s: String, start: Int): Int {
        val n = s.length
        if (start + 1 >= n) return -1
        return when (s[start + 1]) {
            '[' -> {
                var i = start + 2
                while (i < n) {
                    val code = s[i].code
                    when {
                        code in 0x40..0x7E -> return i + 1 // final byte
                        code in 0x20..0x3F -> i++ // parameter / intermediate
                        else -> return i // malformed: end the sequence here
                    }
                }
                -1
            }
            ']' -> {
                var i = start + 2
                while (i < n) {
                    when {
                        s[i] == BEL -> return i + 1
                        s[i] == ESC && i + 1 >= n -> return -1
                        s[i] == ESC && s[i + 1] == '\\' -> return i + 2 // ST
                        else -> i++
                    }
                }
                -1
            }
            // Character-set / keypad designators: ESC ( <final>, ESC # <final>, ...
            '(', ')', '*', '+', '#', '%', '.' -> if (start + 2 < n) start + 3 else -1
            else -> start + 2
        }
    }

    companion object {
        const val ESC = '\u001B'
        const val BEL = '\u0007'

        /** An escape sequence longer than this is not an escape sequence any more. */
        const val MAX_PENDING_CHARS = 512
    }
}

private const val ESC = AnsiScanner.ESC
private const val BEL = AnsiScanner.BEL
