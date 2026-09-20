pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "OpenCode"
include(":app")

// M5 phase 2 — vendored termux VT kernel (`research/06_terminal_selection.md` §1.6 A′).
// Source copies (not JitPack artifacts) so the remote-PTY patch lives with the app; the
// projects sit under `app/src/thirdparty/termux/` so they travel with the app module
// (including the CI `%TEMP%` workspace copies used for isolated verification).
include(":terminal-emulator")
project(":terminal-emulator").projectDir = file("app/src/thirdparty/termux/terminal-emulator")
include(":terminal-view")
project(":terminal-view").projectDir = file("app/src/thirdparty/termux/terminal-view")
