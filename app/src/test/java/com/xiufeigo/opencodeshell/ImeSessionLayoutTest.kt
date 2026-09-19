package com.xiufeigo.opencodeshell

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ImeSessionLayoutTest {
    private fun patch(): File =
        listOf(
            File("../frontend/patches/android-webview-z-ime-session-layout.patch"),
            File("../../frontend/patches/android-webview-z-ime-session-layout.patch"),
            File("frontend/patches/android-webview-z-ime-session-layout.patch"),
        ).firstOrNull { it.isFile } ?: error("missing ime session layout patch")

    @Test
    fun patchPinsRootToWebViewAndDocksTerminalWithContextTab() {
        val text = patch().readText()
        assertTrue(text.contains("html[data-android-webview] #root"))
        assertTrue(text.contains("session-terminal-dock"))
        assertTrue(text.contains("session-mobile-tab-context"))
        assertTrue(text.contains("document.documentElement.clientHeight"))
        assertTrue(text.contains("h-full shrink-0"))
        // F1 single source (final ownership: tablet-dialog; layout carries same value pre-replace)
        assertTrue(text.contains("max-height: calc(100% - 16px)"))
    }

    @Test
    fun vendoredCssFillsWebViewInsteadOfStuckDvh() {
        val html = File("src/main/assets/opencode-app/index.html").readText()
        val cssName =
            Regex("""href="\./assets/(index-[^"]+\.css)"""").find(html)?.groupValues?.get(1)
                ?: error("missing entry stylesheet in index.html")
        val css = File("src/main/assets/opencode-app/assets/$cssName").readText()
        assertTrue(css.contains("data-android-webview") && css.contains("#root"))
        val assets = File("src/main/assets/opencode-app/assets")
        val jsFiles = assets.listFiles { _, name -> name.endsWith(".js") }?.toList().orEmpty()
        assertTrue(
            "mobile context tab must ship",
            jsFiles.any { it.readText().contains("session-mobile-tab-context") },
        )
        assertTrue(
            "terminal dock must use layout viewport height",
            jsFiles.any { it.readText().contains("session-terminal-dock") },
        )
    }
}
