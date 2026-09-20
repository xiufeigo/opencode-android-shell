package com.termux.terminal;

import java.nio.charset.StandardCharsets;

/**
 * M5 phase 2 patch (A′) — a <b>compile-safe shell</b> of the upstream class.
 *
 * <p>What upstream did here and why it must not run in this app
 * ({@code research/06_terminal_selection.md} §1.5 A-1): the first {@link #updateSize(int, int, int, int)}
 * call went {@code initializeEmulator(...)} → {@code JNI.createSubprocess(...)}, opening
 * {@code /dev/ptmx} and spawning a local {@code sh} plus a reader, a writer and a {@code waitFor}
 * thread. M5's terminal is a <b>remote</b> PTY reached over WebSocket, so a local subprocess would
 * be pure waste (and a 16 KB-page hazard, §1.5 A-4). The process management, the PTY file
 * descriptor, the {@link android.os.Handler} screen pump and the three threads are therefore gone.
 *
 * <p>What remains is the part of the public surface that {@link TerminalSessionClient} and
 * {@code com.termux.view.TerminalViewClient} mention in their signatures, so callbacks ported from
 * upstream master still compile. The real transport contract is {@link TerminalIo}, which the host
 * app implements (see {@code ui/terminal/share/TermuxPtyScreenEngine.kt}); this class deliberately
 * does <b>not</b> implement it, because {@code TerminalOutput.write(byte[],int,int)} and
 * {@code TerminalIo.write(byte[])} would make the unqualified two-argument call inside
 * {@code writeCodePoint} ambiguous.
 */
public final class TerminalSession extends TerminalOutput {

    private static final String LOG_TAG = "TerminalSession";

    /** Set by the application for user identification of session, not by terminal. */
    public String mSessionName;

    private final String mShellPath;
    private final String mCwd;
    private final String[] mArgs;
    private final String[] mEnv;
    private final Integer mTranscriptRows;

    /** Callback which gets notified when a session finishes or changes title. */
    TerminalSessionClient mClient;

    TerminalEmulator mEmulator;

    /** Buffer to translate code points into utf8 before writing to the terminal. */
    private final byte[] mUtf8InputBuffer = new byte[5];

    public TerminalSession(String shellPath, String cwd, String[] args, String[] env, Integer transcriptRows, TerminalSessionClient client) {
        this.mShellPath = shellPath;
        this.mCwd = cwd;
        this.mArgs = args;
        this.mEnv = env;
        this.mTranscriptRows = transcriptRows;
        this.mClient = client;
    }

    /**
     * @param client The {@link TerminalSessionClient} interface implementation to allow
     *               for communication between {@link TerminalSession} and its client.
     */
    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;

        if (mEmulator != null)
            mEmulator.updateTerminalSessionClient(client);
    }

    /** Inform the terminal of the new size and reflow or initialize the emulator. */
    public void updateSize(int columns, int rows, int cellWidthPx, int cellHeightPx) {
        if (mEmulator == null) {
            initializeEmulator(columns, rows);
        } else {
            mEmulator.resize(columns, rows);
        }
    }

    /** Upstream signature (no cell metrics): reflow/initialize only. */
    public void updateSize(int columns, int rows) {
        updateSize(columns, rows, 0, 0);
    }

    /** The terminal title as set through escape sequences or null if none set. */
    public String getTitle() {
        return (mEmulator == null) ? null : mEmulator.getTitle();
    }

    /**
     * Create the {@link TerminalEmulator} for this session.
     * <p>Upstream this is also where the local process and its three pump threads were started —
     * see the class comment: that part was removed on purpose.
     */
    public void initializeEmulator(int columns, int rows) {
        mEmulator = new TerminalEmulator(this, columns, rows, mTranscriptRows, mClient);
    }

    /** Write data to the terminal. Upstream this queued it to the PTY input writer thread. */
    public void write(byte[] data) {
        write(data, 0, data == null ? 0 : data.length);
    }

    /**
     * Kept so the upstream override still resolves. Inert on purpose: the PTY input queue no longer
     * exists and {@link TerminalIo} is the outlet the host app implements.
     */
    @Override
    public void write(byte[] data, int offset, int count) {
    }

    /** Write the Unicode code point to the terminal encoded in UTF-8. */
    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            // 1114111 (= 2**16 + 1024**2 - 1) is the highest code point, [0xD800,0xDFFF] is the surrogate range.
            throw new IllegalArgumentException("Invalid code point: " + codePoint);
        }

        int bufferPosition = 0;
        if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 27;

        if (codePoint <= /* 7 bits */0b1111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) codePoint;
        } else if (codePoint <= /* 11 bits */0b11111111111) {
            /* 110xxxxx leading byte with leading 5 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11000000 | (codePoint >> 6));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else if (codePoint <= /* 16 bits */0b1111111111111111) {
            /* 1110xxxx leading byte with leading 4 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11100000 | (codePoint >> 12));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else { /* We have checked codePoint <= 1114111 above, so we have max 21 bits = 0b111111111111111111111 */
            /* 11110xxx leading byte with leading 3 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11110000 | (codePoint >> 18));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 12) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        }
        write(mUtf8InputBuffer, 0, bufferPosition);
    }

    /**
     * Encode a code point to UTF-8 with the same rules as {@link #writeCodePoint(boolean, int)} —
     * the helper a remote transport reuses instead of duplicating the encoder.
     */
    public static byte[] encodeCodePoint(boolean prependEscape, int codePoint) {
        StringBuilder builder = new StringBuilder(2);
        if (prependEscape) builder.append((char) 27);
        builder.appendCodePoint(codePoint);
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }

    public TerminalEmulator getEmulator() {
        return mEmulator;
    }

    /** Notify the {@link #mClient} that the screen has changed. */
    protected void notifyScreenUpdate() {
        mClient.onTextChanged(this);
    }

    /** Reset state for terminal emulator state. */
    public void reset() {
        if (mEmulator != null) mEmulator.reset();
        notifyScreenUpdate();
    }

    /** Diagnostics: the shell path the (removed) local subprocess would have used. */
    public String getShellPath() {
        return mShellPath;
    }

    /** Diagnostics: the cwd the (removed) local subprocess would have used. */
    public String getCwd() {
        return mCwd;
    }

    /** Diagnostics: the argv the (removed) local subprocess would have used. */
    public String[] getArgs() {
        return mArgs;
    }

    /** Diagnostics: the env the (removed) local subprocess would have used. */
    public String[] getEnv() {
        return mEnv;
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        mClient.onTitleChanged(this);
    }

    @Override
    public void clipboardText(String text) {
        mClient.onClipboardText(this, text);
    }

    @Override
    public void onBell() {
        mClient.onBell(this);
    }

    @Override
    public void onColorsChanged() {
        mClient.onColorsChanged(this);
    }

}
