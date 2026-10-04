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

// W1 — WebView 宿主模块（骨架）。第二个 APK：applicationId
// `com.xiufeigo.opencodeshell.web.debug`，与原生壳 `:app` 并存做 A/B 对比。
// 静态资源走 WebViewAssetLoader 托管在 https://appassets.androidplatform.net。
include(":webapp")

// M5 phase 2 — vendored termux VT kernel (`research/06_terminal_selection.md` §1.6 A′).
// Source copies (not JitPack artifacts) so the remote-PTY patch lives with the app; the
// projects sit under `app/src/thirdparty/termux/` so they travel with the app module
// (including the CI `%TEMP%` workspace copies used for isolated verification).
include(":terminal-emulator")
project(":terminal-emulator").projectDir = file("app/src/thirdparty/termux/terminal-emulator")
include(":terminal-view")
project(":terminal-view").projectDir = file("app/src/thirdparty/termux/terminal-view")
