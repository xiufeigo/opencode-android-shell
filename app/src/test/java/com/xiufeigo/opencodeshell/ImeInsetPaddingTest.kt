package com.xiufeigo.opencodeshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ImeInsetPaddingTest {
    @Test
    fun bottomPaddingUsesImeWhenTallerThanNavBar() {
        assertEquals(48, MainActivity.imeAwareBottomPadding(48, 0))
        assertEquals(48, MainActivity.imeAwareBottomPadding(48, 24))
        assertEquals(400, MainActivity.imeAwareBottomPadding(48, 400))
        assertEquals(0, MainActivity.imeAwareBottomPadding(0, 0))
        assertEquals(48, MainActivity.imeAwareBottomPadding(48, 400, false))
        assertEquals(400, MainActivity.imeAwareBottomPadding(48, 400, true))
    }

    @Test
    fun activityAppliesImeInsetsWithTrailingSettledScrollAndForce() {
        val src = File("src/main/java/com/xiufeigo/opencodeshell/MainActivity.kt").readText()
        assertTrue(src.contains("WindowInsetsCompat.Type.ime()"))
        assertTrue(src.contains("imeAwareBottomPadding"))
        assertTrue(src.contains("isVisible(WindowInsetsCompat.Type.ime())"))
        assertTrue(src.contains("IME_SCROLL_JS"))
        assertTrue(src.contains("IME_RESTORE_JS"))
        assertTrue(src.contains("WindowInsetsAnimationCompat"))
        // F2 trailing settled: single timer, no double tick, stable layoutH
        assertTrue(src.contains("SETTLE_MS = 250"))
        assertTrue(src.contains("FALLBACK_MS = 350"))
        assertTrue(src.contains("MAX_RETRY = 3"))
        assertTrue(src.contains("pendingScroll"))
        assertTrue(src.contains("var arm = function"))
        assertTrue(src.contains("var settled = function"))
        assertTrue(src.contains("layoutH()"))
        assertTrue(!src.contains("setTimeout(scrollFocused, 60)"))
        // F3 force bypass: threshold only for onProgress
        assertTrue(src.contains("force: Boolean = false"))
        assertTrue(src.contains("applyImeAwarePadding(insets, force = true)"))
        assertTrue(src.contains("applyImeAwarePadding(insets, force = false)"))
        assertTrue(src.contains("force = true"))
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:windowSoftInputMode=\"adjustResize\""))
        val html = File("src/main/assets/opencode-app/index.html").readText()
        assertTrue(html.contains("interactive-widget=resizes-content"))
    }

    @Test
    fun androidDialogCssCapsHeightForKeyboard() {
        val css = File("src/main/assets/opencode-app/assets").listFiles { _, name ->
            name.startsWith("index-") && name.endsWith(".css")
        } ?: emptyArray()
        val joined = css.joinToString("\n") { it.readText() }
        assertTrue(
            "IME CSS must cap Android dialogs to the remaining viewport",
            joined.contains("data-android-webview") && joined.contains("100dvh"),
        )
    }
}
