package com.termux.terminal;

/**
 * M5 phase 2 patch (A′, `research/06_terminal_selection.md` §4.2) — the minimal contract
 * {@code com.termux.view.TerminalView} needs from its transport, replacing the concrete
 * {@link TerminalSession} that {@code attachSession(TerminalSession)} insisted on.
 *
 * <p>Upstream, {@code TerminalView} read/wrote through {@link TerminalSession}:
 * {@code mTermSession.write(...)} / {@code writeCodePoint(...)} for input and
 * {@code mTermSession.updateSize(cols, rows)} for the grid, while
 * {@code mTermSession.getEmulator()} supplied the {@link TerminalEmulator} the renderer draws.
 * {@code TerminalSession}'s constructor spawns a local {@code sh} through {@code JNI} — which is
 * exactly what a *remote* PTY must avoid ({@code research/06} §1.5 A-1/A-4). This interface keeps
 * the three calls the view makes and drops the process.
 *
 * <p>Implementations are supplied by the host app (see
 * {@code ui/terminal/share/TermuxPtyScreenEngine.kt}):
 * <ul>
 *   <li>{@link #write(byte[])} → the WebSocket up-link ({@code PtyClient.sendBytes});</li>
 *   <li>{@link #updateSize(int, int, int, int)} → {@code PtySizeGate} → {@code PUT /pty/:id {size}};</li>
 *   <li>{@link #getEmulator()} → the {@link TerminalEmulator} whose {@link TerminalOutput}
 *       writes the terminal's own answers (DSR/DA/OSC 52) and mouse reports back to the PTY.</li>
 * </ul>
 *
 * <p>The interface deliberately mirrors {@code TerminalSession}'s signatures so the patch inside
 * {@code TerminalView} is a mechanical {@code mTermSession.write(x)} → {@code mIo.write(x)}
 * substitution; {@code TerminalSession} itself also implements it, which keeps the upstream
 * {@code TerminalViewClient}/{@code TerminalSessionClient} callback signatures source-compatible
 * for anyone porting code from upstream master.
 */
public interface TerminalIo {

    /**
     * Write raw bytes to the PTY (already UTF-8 encoded by the caller).
     *
     * <p>{@code TerminalSession.write(byte[], int, int)} is the upstream shape; the view only ever
     * produced the whole array, so this variant drops the offset/length pair.
     */
    void write(byte[] data);

    /**
     * The terminal was resized. Upstream this both re-flowed the emulator and called
     * {@code JNI.setPtyWindowSize(fd, rows, columns)}; here the emulator re-flows and the size
     * travels to the server over HTTP (first size immediate, then 100 ms debounce).
     *
     * @param columns      new grid width in cells
     * @param rows         new grid height in cells
     * @param cellWidthPx  measured cell width (`TerminalRenderer#getFontWidth()`)
     * @param cellHeightPx measured cell height (`TerminalRenderer#getFontLineSpacing()`)
     */
    void updateSize(int columns, int rows, int cellWidthPx, int cellHeightPx);

    /** The emulator backing the view; never {@code null} once attached. */
    TerminalEmulator getEmulator();

    /**
     * Wipe the screen. Called when the transport reconnects and the server replays history, so a
     * repeated tail is not appended twice (`PtyScreenEngine.onReset`).
     */
    void reset();
}
