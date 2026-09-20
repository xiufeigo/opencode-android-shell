package com.xiufeigo.opencodeshell.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.R

/**
 * OC2 font stack, ported from the vendored official build.
 *
 * Authority: `research/07_motion_copy_p2.md` §2.4 ("字体栈最终答案表") and
 * `PLAN-android-native-refactor.md` §1.3 / §9-g,h:
 *
 * | role                | web value                                   | here                      |
 * |---------------------|---------------------------------------------|---------------------------|
 * | body / UI           | `"Inter", sans-serif` (wght 100–900)        | [inter]                   |
 * | generic monospace   | `ui-monospace, SFMono-Regular, Menlo, …`    | [mono] (platform default) |
 * | terminal            | `"JetBrainsMono Nerd Font Mono", ui-monospace, …` | [terminal] (see TODO) |
 *
 * The font file is the official `assets/Inter.ttf` copied verbatim to
 * `res/font/inter_variable.ttf` (sha256 0be2399e…, 874 708 bytes). Its `fvar`
 * table was parsed to confirm the two axes the web build relies on:
 * `wght 100…900 (default 400)` and `opsz 14…32 (default 14)`, with 9 named
 * instances and `avar`/`STAT` present — i.e. `FontWeight(440/530/610)` is real,
 * not a synthetic weight (research/07 §2.4 left this unverified; it is now).
 *
 * Weight application: a single variable file covers every weight (no per-weight
 * TTFs, so no APK growth). Compose resolves the declared weight for
 * `Font(resId, weight)` on **API 28+** (`Typeface.create(typeface, weight,
 * style)` honours the `wght` axis); on API 26/27 the platform cannot vary a
 * variable font and the default 400 instance is used, so 440/530/610 look like
 * 400 there. minSdk is 26 → acceptable, documented instead of shipping 6
 * static subsets (that would add ~2.5 MB).
 *
 * `opsz` is left at its default 14 (≈ our 13–15sp body sizes); Compose has no
 * per-Text optical-size axis without a hand-built `Typeface`.
 */
object OcFontWeight {
    /** `text-[13px] font-[440]` — body text (38 sites in the vendored build). */
    val Body = FontWeight(440)

    /** `font-[530]` — emphasised body / section titles (40 sites). */
    val Emphasis = FontWeight(530)

    /** `font-[500]` — component labels / button-v2 `font-variation-settings "wght" 530` body (6 sites). */
    val Component = FontWeight(500)

    /** markdown h1 / h2. */
    val Heading = FontWeight(600)

    /** the single `610` site (`.font-[610]`). */
    val HeadingStrong = FontWeight(610)

    /** Regular 400 — only for places the web build really uses `font-normal`. */
    val Regular = FontWeight.Normal
}

/**
 * `"Inter", sans-serif` — the body/UI family. One variable file, one `Font`
 * entry per weight we resolve (same resource id, no extra bytes).
 */
val InterFontFamily: FontFamily = FontFamily(
    Font(R.font.inter_variable, FontWeight(100)),
    Font(R.font.inter_variable, FontWeight(200)),
    Font(R.font.inter_variable, FontWeight(300)),
    Font(R.font.inter_variable, FontWeight(400)),
    Font(R.font.inter_variable, OcFontWeight.Body),
    Font(R.font.inter_variable, OcFontWeight.Component),
    Font(R.font.inter_variable, OcFontWeight.Emphasis),
    Font(R.font.inter_variable, OcFontWeight.Heading),
    Font(R.font.inter_variable, OcFontWeight.HeadingStrong),
    Font(R.font.inter_variable, FontWeight(700)),
    Font(R.font.inter_variable, FontWeight(900)),
)

/**
 * Generic monospace = the **system** mono stack: the vendored build has no
 * IBM Plex Mono (research/07 §2.4, PLAN §9-h), so `FontFamily.Monospace`
 * (Roboto Mono / Noto Sans Mono per device) is the faithful port.
 */
val OcMonoFontFamily: FontFamily = FontFamily.Monospace

/**
 * Terminal family. The web build uses JetBrainsMono Nerd Font Mono, but the
 * vendored asset is `JetBrainsMonoNerdFontMono-Regular.woff2` and Android cannot
 * load woff2 — until a TTF is dropped in, the terminal falls back to the system
 * mono stack. TODO(M5): add the JetBrains Nerd Font TTF and point this at it.
 */
val OcTerminalFontFamily: FontFamily = FontFamily.Monospace

/** Entry point used by [OcTheme]; kept as an object so the API is discoverable. */
object OcFonts {
    /** Body/UI family (`"Inter", sans-serif`). */
    val inter: FontFamily = InterFontFamily

    /** Generic monospace (system mono stack). */
    val mono: FontFamily = OcMonoFontFamily

    /** Terminal monospace (see [OcTerminalFontFamily]). */
    val terminal: FontFamily = OcTerminalFontFamily

    /** What `Text` should use when the caller says nothing. */
    val default: FontFamily = InterFontFamily

    /**
     * OC2 default text style: Inter at the body weight. Deliberately carries no
     * `fontSize`/`lineHeight` — callers keep sizing from [OcType] so adopting
     * Inter does not silently re-layout screens written before this change.
     */
    val defaultTextStyle = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = OcFontWeight.Body,
    )

    /**
     * Material3 `Typography` with every slot on Inter at the OC2 ramp. Opt-in:
     * `MaterialTheme(typography = OcFonts.materialTypography()) { … }` — this
     * exists so M3 components (TextField, Button, …) stop using Roboto on the
     * screens that use them; [OcTheme] itself stays M3-colour-free.
     */
    fun materialTypography(type: OcType = OcType.Default): Typography {
        val body = TextStyle(
            fontFamily = InterFontFamily,
            fontWeight = OcFontWeight.Body,
            fontSize = type.base,
            lineHeight = type.lineHeightBase,
        )
        return Typography(
            displayLarge = body.copy(fontSize = type.xl4, fontWeight = OcFontWeight.Heading),
            displayMedium = body.copy(fontSize = type.xl2, fontWeight = OcFontWeight.Heading),
            displaySmall = body.copy(fontSize = type.xl, fontWeight = OcFontWeight.Heading),
            headlineLarge = body.copy(fontSize = type.xl2, fontWeight = OcFontWeight.Heading),
            headlineMedium = body.copy(fontSize = type.xl, fontWeight = OcFontWeight.Heading),
            headlineSmall = body.copy(fontSize = type.lg, fontWeight = OcFontWeight.Heading),
            titleLarge = body.copy(fontSize = type.xl, fontWeight = OcFontWeight.Emphasis),
            titleMedium = body.copy(fontSize = type.lg, fontWeight = OcFontWeight.Emphasis),
            titleSmall = body.copy(fontSize = type.base, fontWeight = OcFontWeight.Emphasis),
            bodyLarge = body,
            bodyMedium = body.copy(fontSize = type.sm),
            bodySmall = body.copy(fontSize = type.xs),
            labelLarge = body.copy(fontSize = type.base, fontWeight = OcFontWeight.Component),
            labelMedium = body.copy(fontSize = type.sm, fontWeight = OcFontWeight.Component),
            labelSmall = body.copy(fontSize = type.xs, fontWeight = OcFontWeight.Component),
        )
    }

    /** 13px/440 — the OC2 body default, for callers that build their own styles. */
    fun body(type: OcType = OcType.Default): TextStyle = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = OcFontWeight.Body,
        fontSize = type.base,
        lineHeight = type.lineHeightBase,
    )

    /** Emphasis helper: Inter 530 at the given size. */
    fun emphasis(
        size: androidx.compose.ui.unit.TextUnit,
        lineHeight: androidx.compose.ui.unit.TextUnit = 20.sp,
    ): TextStyle = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = OcFontWeight.Emphasis,
        fontSize = size,
        lineHeight = lineHeight,
    )
}
