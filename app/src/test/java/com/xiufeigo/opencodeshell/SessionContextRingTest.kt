package com.xiufeigo.opencodeshell

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SessionContextRingTest {
    @Test
    fun patchOpensContextPanelFromHeaderRingOnPhoneWidth() {
        val patch =
            listOf(
                File("../frontend/patches/android-webview-z-ime-session-context.patch"),
                File("../../frontend/patches/android-webview-z-ime-session-context.patch"),
                File("frontend/patches/android-webview-z-ime-session-context.patch"),
            ).firstOrNull { it.isFile } ?: error("missing session context patch")
        val text = patch.readText()
        assertTrue(text.contains("session-open-context"))
        assertTrue(text.contains("android-session-context"))
        assertTrue(text.contains("SessionContextTab"))
        assertTrue(text.contains("mobileContextOpen"))
    }

    @Test
    fun vendoredUiKeepsHeaderRingClickTarget() {
        val html = File("src/main/assets/opencode-app/index.html").readText()
        val jsName =
            Regex("""src="\./assets/(index-[^"]+\.js)"""").find(html)?.groupValues?.get(1)
                ?: error("missing entry script in index.html")
        val js = File("src/main/assets/opencode-app/assets/$jsName").readText()
        assertTrue("header ring must expose data-action", js.contains("session-open-context"))
        assertTrue("phone context panel must mount SessionContextTab", js.contains("android-session-context"))
        val cssName =
            Regex("""href="\./assets/(index-[^"]+\.css)"""").find(html)?.groupValues?.get(1)
                ?: error("missing entry stylesheet in index.html")
        val css = File("src/main/assets/opencode-app/assets/$cssName").readText()
        assertTrue(css.contains("session-open-context"))
        assertTrue(css.contains("android-session-context"))
    }

    @Test
    fun androidBackTreatsContextOverlayAsDismissible() {
        val js = AndroidBackJs.INSTALL
        assertTrue(js.contains("android-session-context"))
        assertTrue(js.contains("role=\"dialog\""))
    }
}
