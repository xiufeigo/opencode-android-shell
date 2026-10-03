package com.xiufeigo.opencodeshell.ui.theme

/**
 * The Appearance → Color scheme row (`system|light|dark`, exposed through
 * `Settings.isDark(systemDark)`) mapped onto the platform's own light/dark
 * appearance flag.
 *
 * `WindowInsetsControllerCompat.isAppearanceLightStatusBars = true` means the
 * status *bar* is light, so the OS draws dark icons in it. That is the wrong
 * half in edge-to-edge mode when the app content behind the bar is dark: dark
 * icons over the dark dialog surface are exactly the `research/v40_verify/tD`
 * `tD_20_dark` defect — clock / signal / battery unreadable in Dark while Light
 * is fine (`tD_76_light`).
 *
 * The mapping is therefore inverted: `dark content → light icons (false)`,
 * `light content → dark icons (true)`. Kept in `ui/theme` instead of beside
 * `SystemUiAppearance` (which belongs to the legacy WebView shell's
 * `data-color-scheme` bridge) so [OcActivity]'s native Compose surface reads
 * its own setting, and kept pure so a plain JVM test can pin it.
 */
object OcSystemBars {

    /** `true` while the app content the status bar sits on is dark. */
    fun lightIconsForDarkContent(dark: Boolean): Boolean = !dark

    /**
     * `true` when the OS must draw light (white) status bar icons — i.e. the
     * value `OcActivity` assigns to
     * `WindowInsetsControllerCompat.isAppearanceLightStatusBars`.
     */
    fun statusBarIconsLight(darkContent: Boolean): Boolean = darkContent
}
