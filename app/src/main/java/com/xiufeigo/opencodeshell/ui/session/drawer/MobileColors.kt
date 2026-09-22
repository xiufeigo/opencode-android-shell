package com.xiufeigo.opencodeshell.ui.session.drawer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.xiufeigo.opencodeshell.ui.theme.OcColors

/**
 * W4-C surface colours for the mobile form, in **correct ARGB order**.
 *
 * `OcColors` stores the alpha-bearing tokens as CSS-order `#RRGGBBAA`
 * (`OcTheme.kt:119-123,176-180`: `borderBase = "#0000001a"`,
 * `borderStrong = "#ffffff33"`, `overlayScrim = "#00000099"`), but
 * `android.graphics.Color.parseColor` only accepts `#AARRGGBB` for 8-digit
 * values. Consequences, measured on the foldable build:
 *
 * - light mode → every one of those tokens has **alpha 0** (the token becomes
 *   `#0000001a` = a=0x00, r=0x00, g=0x00, b=0x1a) → borders and the modal scrim
 *   render nothing (the bottom-sheet handle bar was invisible, pixel sampled
 *   `250,250,250` at its exact centre instead of ~`204,204,204`);
 * - dark mode → they keep alpha 255 and get a bogus hue (`#ffffff33` =
 *   rgb(255,255,51), an opaque yellow hairline).
 *
 * The values below are the upstream colours those tokens were meant to hold
 * (`--v2-border-border-base` 10 %, `--v2-border-border-strong` 20 %,
 * `--v2-overlay-simple-overlay-scrim` 60 % black in both schemes). They are
 * local to the mobile form so the W4-C surfaces are correct today; when the
 * theme tokens are fixed, these can be replaced by `OcColors` again. Recorded
 * as a cross-cutting finding in `research/wave4_mobile_form.md` §4.10.
 */
internal object MobileColors {
    /** `--v2-overlay-simple-overlay-scrim` = 60 % black (both schemes). */
    val scrim = Color(0x99000000)

    /** `--v2-border-border-base` = 10 % of the foreground. */
    fun hairline(dark: Boolean): Color = if (dark) Color(0x1AFFFFFF) else Color(0x1A000000)

    /** `--v2-border-border-strong` = 20 % of the foreground (sheet handle bar). */
    fun strong(dark: Boolean): Color = if (dark) Color(0x33FFFFFF) else Color(0x33000000)

    /** 8 % of the foreground — `--v2-border-border-muted`, for menu separators. */
    fun muted(dark: Boolean): Color = if (dark) Color(0x14FFFFFF) else Color(0x14000000)
}

/** Scheme probe for the colour helpers above (the theme exposes no `isDark`). */
internal fun OcColors.isDarkSurface(): Boolean = bgBase.luminance() < 0.5f
