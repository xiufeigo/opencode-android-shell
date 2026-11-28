package com.xiufeigo.opencodeshell.web.host

import android.graphics.Color
import android.view.Window
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Owns the page-reported dark-mode snapshot and system-bar fallback policy.
 *
 * The Activity supplies the window, current insets host, and platform night mode;
 * this module decides when a page report is fresh, when it must be discarded after
 * a uiMode change, and applies the resulting theme directly to system bars.
 */
class SystemUiThemeHost(
    private val window: Window,
    private val insetsProvider: () -> InsetsHost?,
    private val systemDark: () -> Boolean,
) {
    private var pageReportedDark: Boolean? = null

    fun applyPageTheme(dark: Boolean) {
        if (pageReportedDark == dark) return
        pageReportedDark = dark
        applySystemBarTheme()
    }

    fun applySystemBarTheme() {
        val dark = pageReportedDark ?: systemDark()
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
        // API 29+ 的对比度遮罩会把导航手势条画成一条白杠，关掉。
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
    }

    fun onConfigurationChanged() {
        val insets = insetsProvider() ?: return
        pageReportedDark = null
        insets.refresh()
        applySystemBarTheme()
    }
}
