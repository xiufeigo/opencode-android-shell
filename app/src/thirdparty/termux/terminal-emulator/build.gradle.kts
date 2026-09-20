// Vendored from termux/termux-app `v0.117` (commit 9272a757affc0dae4aa044248800f3987e06c52b),
// module `terminal-emulator`. Apache-2.0 (repository-wide GPLv3-only licence grants an explicit
// exception for `terminal-view` / `terminal-emulator` — see ../LICENSE.md).
//
// M5 phase 2, A′ form (`research/06_terminal_selection.md` §1.6 step 1):
//   * Java sources only — the VT parser/screen model needs no JNI for a *remote* PTY.
//   * **No `externalNativeBuild`**: `src/main/jni/` was deleted and `JNI.java` /
//     `TerminalSession.java` were reduced to compile-safe shells with zero JNI entry points
//     (`research/06` §1.5 A-4: the packaged `libtermux.so` is 4 KB-aligned and would break on
//     16 KB-page devices; not building it removes the debt entirely).
plugins {
    id("com.android.library")
}

android {
    namespace = "com.termux.terminal"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }

    lint {
        abortOnError = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // The final JUnit suite in the upstream module covers the VT parser (`ByteQueueTest`,
    // `TerminalTest`, `CursorAndScreenTest`, …). It is unused here: M5 keeps the *unmodified*
    // upstream behaviour and covers the seam in `:app`'s own JVM tests instead.
    testImplementation("junit:junit:4.13.2")
}
