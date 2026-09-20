package com.termux.terminal;

/**
 * M5 phase 2 patch (A′) — deliberately emptied.
 *
 * <p>Upstream this class held the {@code native} entry points of {@code src/main/jni/termux.c}
 * ({@code createSubprocess} / {@code setPtyWindowSize} / {@code waitFor} / {@code close}) and its
 * static initialiser ran {@code System.loadLibrary("termux")}. A remote PTY needs none of them,
 * and the shipped {@code libtermux.so} is 4 KB-page aligned, i.e. unloadable on 16 KB-page devices
 * ({@code research/06_terminal_selection.md} §1.5 A-4). The whole native side — this class body,
 * {@code src/main/jni/} and the {@code externalNativeBuild} block — was therefore removed.
 *
 * <p>The type is kept as a {@code final} empty class only so the vendored file list still mirrors
 * upstream (making the diff reviewable against tag {@code v0.117}); nothing references it.
 */
final class JNI {

    private JNI() {
    }

}
