package com.xiufeigo.opencodeshell.ui.terminal.share

/**
 * Screen restore for **session-bound** terminals — the `readSessionTerminal`
 * face of the reconnect story (`V2Api.readSessionTerminal` →
 * `PersistentPty.ReadResult.screen{text, cols, rows, cursor{x,y}}`, upstream
 * `schema/src/persistent-pty.ts:54-66`). Pure: the VT bytes that repaint a
 * known screen into a fresh engine, JVM-testable without sockets.
 *
 * The API gives plain text plus the 0-based cursor cell — no styles — so the
 * restore is textual: erase the screen, drop every row at its own position
 * (blank rows included, so column alignment survives) and park the cursor.
 * The byte-level twin is the xterm checkpoint `PersistentPtySnapshot` carries;
 * this one is what a session read can always provide.
 */
object SessionTerminalRestore {

    /**
     * VT bytes painting [text] (rows separated by `\n`, row 0 first) and then
     * moving the cursor to ([cursorX], [cursorY]) — 0-based cells, like the
     * wire's `cursor{x,y}` (VT positions are 1-based).
     */
    fun screenBytes(text: String, cursorX: Int, cursorY: Int): ByteArray {
        val out = StringBuilder()
        out.append("\u001b[H\u001b[2J") // home + erase display
        val rows = text.split('\n')
        for ((index, row) in rows.withIndex()) {
            if (row.isEmpty()) continue
            out.append("\u001b[").append(index + 1).append(";1H").append(row)
        }
        val row = (cursorY + 1).coerceAtLeast(1)
        val col = (cursorX + 1).coerceAtLeast(1)
        out.append("\u001b[").append(row).append(';').append(col).append('H')
        return out.toString().toByteArray(Charsets.UTF_8)
    }
}

/**
 * Screen restore for a **local embedded** terminal (the standalone `Terminal N`
 * tabs, `TerminalKind.PTY`) revived after process death. The server-side PTY
 * outlives the app process, so the panel re-adopts the same id instead of
 * creating a fresh shell; the screen is then repainted from the server:
 *
 * - [Snapshot] — the id exists in the persistent-pty namespace: paint the
 *   xterm checkpoint and resume the socket at `output.tail` (no replay, same
 *   as `terminal-pane.tsx:175-188`);
 * - [FullReplay] — anything else (ephemeral ids 404 on the snapshot route):
 *   reset the engine and reconnect with the cursor omitted, so the server
 *   resends its retained output (≤2 MiB) over the socket.
 *
 * Pure: [resolveLocalRestore] maps a fetched snapshot (or its absence) to the
 * plan; the panel performs the IO.
 */
sealed interface LocalTerminalRestore {
    /** Brand-new tab: new output only, no reset, no replay. */
    data object Fresh : LocalTerminalRestore

    /** Paint [checkpoint] (xterm bytes), resume the socket at [tail]. */
    data class Snapshot(
        val checkpoint: ByteArray,
        val cols: Int,
        val rows: Int,
        val tail: Long,
    ) : LocalTerminalRestore

    /** Reset the engine, reconnect with the cursor omitted (server replays). */
    data object FullReplay : LocalTerminalRestore

    /** `?cursor=` for the (re)connect: `-1` fresh, tail resume, `null` replay. */
    val connectCursor: Long?
        get() = when (this) {
            Fresh -> PtyProtocol.CURSOR_NEW_OUTPUT_ONLY
            is Snapshot -> tail
            FullReplay -> null
        }
}

/**
 * Maps a fetched snapshot to the restore plan. A missing or empty checkpoint
 * means the socket replay is the only source — never a blank screen.
 */
fun resolveLocalRestore(
    checkpoint: ByteArray?,
    cols: Int,
    rows: Int,
    outputTail: Long,
): LocalTerminalRestore =
    if (checkpoint != null && checkpoint.isNotEmpty()) {
        LocalTerminalRestore.Snapshot(checkpoint, cols, rows, outputTail)
    } else {
        LocalTerminalRestore.FullReplay
    }
