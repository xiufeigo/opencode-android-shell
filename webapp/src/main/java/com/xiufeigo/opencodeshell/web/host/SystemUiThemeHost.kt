package com.xiufeigo.opencodeshell.web.host

/**
 * Owns the page-reported dark-mode snapshot and system-bar fallback policy.
 *
 * The Activity supplies access to the current insets host and platform night mode;
 * this module decides when a page report is fresh, when it must be discarded after
 * a uiMode change, and which theme should be applied to system bars.
 */
class SystemUiThemeHost(
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
        val insets = insetsProvider() ?: return
        insets.applyBarsTheme(pageReportedDark ?: systemDark())
    }

    fun onConfigurationChanged() {
        val insets = insetsProvider() ?: return
        pageReportedDark = null
        insets.refresh()
        applySystemBarTheme()
    }
}
