package com.xiufeigo.opencodeshell

import android.graphics.Color

/**
 * Maps the official OpenCode web color-scheme onto Android system bars.
 *
 * The Activity still pads [statusBars]/[navigationBars]/[displayCutout]/[ime],
 * so bar chrome sits on the window/root background rather than WebView pixels.
 * That background (and icon appearance) must follow the embedded UI theme.
 */
object SystemUiAppearance {
    const val LIGHT_BACKGROUND = 0xFFFAFAFA.toInt()
    const val DARK_BACKGROUND = 0xFF080808.toInt()

    fun darkFromWebScheme(scheme: String?): Boolean? =
        when (scheme?.trim()?.lowercase()) {
            "dark" -> true
            "light" -> false
            else -> null
        }

    fun backgroundColor(dark: Boolean): Int = if (dark) DARK_BACKGROUND else LIGHT_BACKGROUND

    /** `WindowInsetsControllerCompat.isAppearanceLightStatusBars`: true → dark icons. */
    fun appearanceLightBars(darkContent: Boolean): Boolean = !darkContent

    fun parseCssColor(raw: String?): Int? {
        val value = raw?.trim()?.lowercase() ?: return null
        if (value == "transparent") return Color.TRANSPARENT
        val hex = if (value.startsWith("#")) value.substring(1) else return null
        val packed = when (hex.length) {
            3 -> hex.map { "$it$it" }.joinToString("")
            6 -> hex
            8 -> hex
            else -> return null
        }
        return try {
            if (packed.length == 8) Color.parseColor("#$packed") else Color.parseColor("#$packed")
        } catch (_: Exception) {
            null
        }
    }

    fun darkFromThemeColor(css: String?): Boolean? {
        val color = parseCssColor(css) ?: return null
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        val luminance = (0.299 * r + 0.587 * g + 0.114 * b)
        return luminance < 140
    }
}
