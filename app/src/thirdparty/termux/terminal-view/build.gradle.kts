// Vendored from termux/termux-app `v0.117` (commit 9272a757affc0dae4aa044248800f3987e06c52b),
// module `terminal-view`. Apache-2.0 — see ../LICENSE.md.
//
// M5 phase 2, A′ form (`research/06_terminal_selection.md` §1.6 step 1). Only patch on top of the
// vendored sources: `TerminalView.attachIo(TerminalIo)` replaces the `attachSession(TerminalSession)`
// loading path, so a *remote* PTY can drive the view (see ../PATCHES.md).
plugins {
    id("com.android.library")
}

android {
    namespace = "com.termux.view"
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
}

dependencies {
    api(project(":terminal-emulator"))
    implementation("androidx.annotation:annotation:1.9.1")
}
