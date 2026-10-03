package com.xiufeigo.opencodeshell.ui.terminal.share

/**
 * The data link a terminal tab drives, independent of which server family is
 * behind it: the ephemeral PTY ([PtyClient], `POST /pty` → `connect-token` →
 * WebSocket) or a persistent / session-bound terminal
 * ([PersistentPtyClient], `…/experimental/persistent-pty` +
 * `…/session/{id}/terminal`). The view layer (`ui/terminal/TerminalPanel`)
 * only ever sees this seam — input in, grid size out, close on unmount.
 */
interface TerminalLink {
    /** Starts (and keeps alive) the connection. Idempotent while already running. */
    fun start()

    /** Text up-link (typed keys, paste). */
    fun send(text: String)

    /** Byte up-link — the VT kernel's answers (DSR/DA/OSC 52) and mouse reports. */
    fun sendBytes(bytes: ByteArray)

    /** Grid size changed; the link forwards it to the server PTY. */
    fun resize(cols: Int, rows: Int)

    /** Close = unmount: no reconnect, socket released, engine released. */
    fun close()
}
