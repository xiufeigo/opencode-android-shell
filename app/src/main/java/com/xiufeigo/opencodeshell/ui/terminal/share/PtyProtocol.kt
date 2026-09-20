package com.xiufeigo.opencodeshell.ui.terminal.share

import org.json.JSONObject

/**
 * Pure PTY/WebSocket protocol logic (M5 phase 1) — **no Android, no OkHttp**,
 * so every rule below is JVM-unit-testable.
 *
 * Spec sources: `PLAN-android-native-refactor.md` §4.6, `research/04_data_api.md`
 * §6.2 (frame protocol) and §7.3 (upstream `terminal.tsx` / `terminal-websocket-url.ts`),
 * `research/02_ui_screens.md` §9.2.
 *
 * Wire facts encoded here:
 * - down-link = **raw UTF-8 text frames**; the one control frame is a **binary**
 *   frame whose first byte is `0x00` followed by UTF-8 JSON `{"cursor":N}`
 *   (`handlers/pty.ts` `chunks(replay)` → `metaFrame(cursor)` → live);
 * - up-link = text/binary written straight to the PTY — **no JSON control frames**;
 * - resize does **not** travel over the WebSocket: it is `PUT /pty/:id {size}`;
 * - reconnect backoff = `min(250 * 2^min(tries,4), 4000)` ms;
 * - `MAX_TERMINAL_SESSIONS = 20`, closing a terminal unmounts it.
 */
object PtyProtocol {

    /** Leading byte of the single metadata frame (`0x00` + `{"cursor":N}`). */
    const val META_LEADING_BYTE: Byte = 0x00

    /** `research/02` §9.2 `MAX_TERMINAL_SESSIONS` — the 21st terminal is refused. */
    const val MAX_TERMINAL_SESSIONS = 20

    /** `research/02` §9.2: first size is pushed immediately, the rest debounce 100 ms. */
    const val RESIZE_DEBOUNCE_MS = 100L

    const val BACKOFF_START_MS = 250L
    const val BACKOFF_MAX_MS = 4_000L

    /** `Math.min(tries, 4)` in `Math.min(250 * 2 ** …, 4000)`. */
    const val BACKOFF_EXPONENT_CAP = 4

    /** Process exited (upstream `terminal.connectionLost` is *not* shown for 1000). */
    const val CLOSE_NORMAL = 1000

    /** `CloseEvent(4404, "session not found")` → the PTY is gone, do not retry. */
    const val CLOSE_SESSION_NOT_FOUND = 4404

    /** `?cursor=-1` — only new output, no replay. */
    const val CURSOR_NEW_OUTPUT_ONLY = -1L

    /**
     * `min(250 * 2^min(tries, 4), 4000)`; `tries` = number of consecutive
     * failed attempts (first failure → 500 ms), matching the upstream retry
     * counter that starts at 0 and is incremented before the delay is computed.
     */
    fun backoffDelayMs(tries: Int): Long {
        val exponent = tries.coerceAtLeast(0).coerceAtMost(BACKOFF_EXPONENT_CAP)
        return (BACKOFF_START_MS shl exponent).coerceAtMost(BACKOFF_MAX_MS)
    }

    /** What a close frame means for the connection state machine. */
    enum class CloseDisposition {
        /** 1000: the shell process exited; the tab stays, no reconnect. */
        EXITED,

        /** 4404 / "session not found": recreate the terminal, never retry. */
        GONE,

        /** Anything else: abnormal close, retry with backoff (`02` §9.2). */
        RETRY,
    }

    fun closeDisposition(code: Int, reason: String? = null): CloseDisposition = when {
        code == CLOSE_SESSION_NOT_FOUND -> CloseDisposition.GONE
        reason?.contains("not found", ignoreCase = true) == true -> CloseDisposition.GONE
        code == CLOSE_NORMAL -> CloseDisposition.EXITED
        else -> CloseDisposition.RETRY
    }

    /** Decoded shape of one down-link frame. */
    sealed interface DownFrame {
        /**
         * The single metadata frame. [cursor] is the server-authoritative
         * absolute output cursor used for `?cursor=` on the next connect;
         * `null` when the frame was malformed — in that case the previous
         * cursor must be kept (never derive one locally: upstream's cursor is
         * a JS string length, not a Kotlin `String.length` / UTF-8 byte count).
         */
        data class Meta(val cursor: Long?) : DownFrame

        /** Raw UTF-8 terminal bytes. */
        data class Output(val text: String) : DownFrame

        /** Zero-length payload — ignore. */
        data object Empty : DownFrame
    }

    /**
     * Classifies a **binary** WebSocket frame. Upstream sets
     * `binaryType = "arraybuffer"` and only routes `ArrayBuffer` frames whose
     * first byte is `0` to the control branch; a binary frame without the
     * leading `0x00` is still terminal data, so it decodes as UTF-8 output.
     */
    fun classifyBinaryFrame(bytes: ByteArray): DownFrame {
        if (bytes.isEmpty()) return DownFrame.Empty
        if (bytes[0] != META_LEADING_BYTE) {
            return DownFrame.Output(String(bytes, Charsets.UTF_8))
        }
        val json = String(bytes, 1, bytes.size - 1, Charsets.UTF_8).trim()
        return DownFrame.Meta(parseCursor(json))
    }

    /** `{"cursor":N}` → `N`; tolerant of a `Double` cursor and malformed JSON. */
    fun parseCursor(json: String): Long? {
        if (json.isEmpty()) return null
        return try {
            when (val raw = JSONObject(json).opt("cursor")) {
                is Number -> raw.toLong()
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Query parameters of the connect upgrade (`research/04` §6.2):
     * `directory` / `cursor` / `ticket`. v2 renames the directory parameter to
     * `location[directory]`; Android stays on the v1 face (`research/04` §8),
     * the v2 name is kept so the flag can be flipped without rewriting paths.
     */
    fun directoryQueryName(v2: Boolean): String = if (v2) "location[directory]" else "directory"

    /** Path segments of the PTY REST surface: `/pty/...` (v1) or `/api/pty/...` (v2). */
    fun ptySegments(v2: Boolean): List<String> = if (v2) listOf("api", "pty") else listOf("pty")
}
