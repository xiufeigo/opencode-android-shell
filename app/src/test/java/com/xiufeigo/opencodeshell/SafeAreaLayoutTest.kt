package com.xiufeigo.opencodeshell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SafeAreaLayoutTest {
    @Test
    fun mainActivityPadsRootWithStatusAndNavigationInsets() {
        val src = File("src/main/java/com/xiufeigo/opencodeshell/MainActivity.kt").readText()
        assertTrue(
            "insets listener must be on the root, not the WebView",
            src.contains("setOnApplyWindowInsetsListener(binding.root)"),
        )
        assertTrue(src.contains("WindowInsetsCompat.Type.statusBars()"))
        assertTrue(src.contains("WindowInsetsCompat.Type.navigationBars()"))
        assertTrue(src.contains("WindowInsetsCompat.Type.displayCutout()"))
        assertTrue(src.contains("WindowInsetsCompat.CONSUMED"))
        assertTrue(src.contains("enableEdgeToEdge()"))
        assertTrue(src.contains("view.updatePadding"))
        assertFalse(
            "do not hide the status bar (HyperOS immersive hide swallows touches)",
            src.contains("Type.statusBars())") && src.contains(".hide("),
        )
        assertFalse(src.contains("BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE"))
    }

    @Test
    fun layoutClipsRootPaddingAndHasNoOverlaySibling() {
        val xml = File("src/main/res/layout/activity_main.xml").readText()
        assertTrue(xml.contains("android:id=\"@+id/root\""))
        assertTrue(xml.contains("android:clipToPadding=\"true\""))
        assertFalse("overlayBar sibling intercepts WebView hits", xml.contains("overlayBar"))
    }

    @Test
    fun embeddedViewportDeclaresSafeAreaFit() {
        val html = File("src/main/assets/opencode-app/index.html").readText()
        assertTrue(
            "viewport-fit=cover is required for CSS env(safe-area-inset-*)",
            html.contains("viewport-fit=cover"),
        )
    }

    @Test
    fun immersiveThemeDoesNotHideSystemBars() {
        val themes = File("src/main/res/values/themes.xml").readText()
        assertTrue(themes.contains("windowLightStatusBar"))
        assertTrue(themes.contains("@color/window_background"))
        assertFalse(themes.contains("android:windowFullscreen\">true"))
        assertFalse(themes.contains("windowTranslucentStatus\">true"))
    }
}
