package com.xiufeigo.opencodeshell

import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SystemUiAppearanceTest {
    @Test
    fun darkSchemeUsesLightIconsAndDarkBackground() {
        assertEquals(true, SystemUiAppearance.darkFromWebScheme("dark"))
        assertEquals(true, SystemUiAppearance.darkFromWebScheme("DARK"))
        assertEquals(false, SystemUiAppearance.darkFromWebScheme("light"))
        assertNull(SystemUiAppearance.darkFromWebScheme("system"))
        assertNull(SystemUiAppearance.darkFromWebScheme(null))
        assertEquals(SystemUiAppearance.DARK_BACKGROUND, SystemUiAppearance.backgroundColor(true))
        assertEquals(SystemUiAppearance.LIGHT_BACKGROUND, SystemUiAppearance.backgroundColor(false))
        assertFalse("dark UI → light (white) status icons", SystemUiAppearance.appearanceLightBars(true))
        assertTrue("light UI → dark status icons", SystemUiAppearance.appearanceLightBars(false))
    }

    @Test
    fun themeColorLuminanceDetectsOfficialOpenCodeBackgrounds() {
        assertEquals(true, SystemUiAppearance.darkFromThemeColor("#080808"))
        assertEquals(false, SystemUiAppearance.darkFromThemeColor("#fafafa"))
        assertEquals(SystemUiAppearance.DARK_BACKGROUND, SystemUiAppearance.parseCssColor("#080808"))
        assertEquals(SystemUiAppearance.LIGHT_BACKGROUND, SystemUiAppearance.parseCssColor("#FAFAFA"))
        assertEquals(Color.TRANSPARENT, SystemUiAppearance.parseCssColor("transparent"))
    }

    @Test
    fun activityFollowsWebViewColorSchemeWithoutHidingBars() {
        val src = File("src/main/java/com/xiufeigo/opencodeshell/MainActivity.kt").readText()
        val bridge = File("src/main/java/com/xiufeigo/opencodeshell/OpenCodeAndroidBridge.kt").readText()
        assertTrue(src.contains("SystemBarsJs.INSTALL"))
        assertTrue(src.contains("applySystemUi"))
        assertTrue(src.contains("isStatusBarContrastEnforced = false"))
        assertTrue(src.contains("isNavigationBarContrastEnforced = false"))
        assertTrue(src.contains("isAppearanceLightStatusBars"))
        assertTrue(src.contains("isAppearanceLightNavigationBars"))
        assertTrue(bridge.contains("fun setColorScheme"))
        assertFalse(src.contains("BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE"))
        assertFalse(
            "do not hide the status bar (HyperOS immersive hide swallows touches)",
            src.contains(".hide(") && src.contains("statusBars"),
        )
    }

    @Test
    fun immersiveThemeUsesTransparentSystemBars() {
        val light = File("src/main/res/values/themes.xml").readText()
        val night = File("src/main/res/values-night/themes.xml").readText()
        val v29 = File("src/main/res/values-v29/themes.xml").readText()
        assertTrue(light.contains("android:statusBarColor\">@android:color/transparent"))
        assertTrue(light.contains("android:navigationBarColor\">@android:color/transparent"))
        assertTrue(night.contains("android:windowLightStatusBar\">false"))
        assertTrue(v29.contains("enforceNavigationBarContrast\">false"))
        val nightColors = File("src/main/res/values-night/colors.xml").readText()
        assertTrue(nightColors.contains("#FF080808") || nightColors.contains("#080808"))
    }
}
