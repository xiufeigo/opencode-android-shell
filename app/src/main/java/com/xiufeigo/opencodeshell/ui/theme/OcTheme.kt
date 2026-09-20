package com.xiufeigo.opencodeshell.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * OpenCode v2 (OC-2) design tokens, ported 1:1 from the vendored official
 * build (`index-D_rl_FNw.css` `[data-color-scheme=light|dark]`) + upstream
 * `packages/ui/src/theme/v2/default-primitives.ts`. These are the SEMANTIC
 * tokens the official UI actually uses — NOT raw Material3. Compose primitives
 * (ButtonV2, DialogV2, ...) read [OcTheme.colors] exclusively.
 *
 * Values cross-checked against `research/03_motion_tokens.md` and
 * `research/_raw/files/...default-primitives.ts`.
 */
@Immutable
data class OcColors(
    // Background layers
    val bgBase: Color,          // --v2-background-bg-base
    val bgDeep: Color,          // --v2-background-bg-deep
    val layer01: Color,         // --v2-background-bg-layer-01
    val layer02: Color,         // --v2-background-bg-layer-02
    val layer03: Color,         // --v2-background-bg-layer-03
    val layer04: Color,         // --v2-background-bg-layer-04
    val inverse: Color,         // --v2-background-bg-inverse
    val contrast: Color,        // --v2-background-bg-contrast
    val accent: Color,          // --v2-background-bg-accent (solid primary)
    /**
     * `--v2-background-bg-button-neutral` (M3 additive token): the surface of
     * `ButtonV2 variant="neutral"` — light `#fff`, dark `#ffffff0f`
     * (`research/03` §3.2). Upstream pairs it with
     * `--v2-elevation-button-neutral`; Compose adds a 0.5 dp `borderMuted`
     * hairline instead (see `OcNeutralButton`).
     */
    val buttonNeutral: Color,
    // Text
    val textBase: Color,        // --v2-text-text-base
    val textMuted: Color,       // --v2-text-text-muted
    val textFaint: Color,       // --v2-text-text-faint
    val textInverse: Color,     // --v2-text-text-inverse
    val textAccent: Color,      // --v2-text-text-accent
    val textAccentHover: Color, // --v2-text-text-accent-hover
    val textCodeAccent: Color,  // --v2-text-text-code-accent
    // Icons
    val iconBase: Color,        // --v2-icon-icon-base
    val iconMuted: Color,       // --v2-icon-icon-muted
    val iconInverse: Color,     // --v2-icon-icon-inverse
    val iconAccent: Color,      // --v2-icon-icon-accent
    // Borders
    val borderBase: Color,      // --v2-border-border-base
    val borderMuted: Color,     // --v2-border-border-muted
    val borderStrong: Color,    // --v2-border-border-strong
    val borderFocus: Color,     // --v2-border-border-focus
    // Overlays (hover/pressed/scrim)
    val overlayHover: Color,    // --v2-overlay-simple-overlay-hover
    val overlayPressed: Color,  // --v2-overlay-simple-overlay-pressed
    val overlayScrim: Color,    // --v2-overlay-simple-overlay-scrim
    // State (success / warning / danger / info)
    val stateSuccessBg: Color,
    val stateSuccessFg: Color,
    val stateSuccessBorder: Color,
    val stateWarningBg: Color,
    val stateWarningFg: Color,
    val stateWarningBorder: Color,
    val stateDangerBg: Color,
    val stateDangerFg: Color,
    val stateDangerBorder: Color,
    val stateInfoBg: Color,
    val stateInfoFg: Color,
    val stateInfoBorder: Color,
    // Agent accent solids (tool/task cards)
    val agentPlan: Color,
    val agentBuild: Color,
    val agentExplore: Color,
    val agentReview: Color,
    val agentWriter: Color,
    // Syntax highlight (`--syntax-*`, NO `--v2-syntax-*` prefix in the build)
    val syntaxKeyword: Color,
    val syntaxProperty: Color,
    val syntaxType: Color,
    val syntaxString: Color,
    val syntaxConstant: Color,
    val syntaxPrimitive: Color,
    val syntaxComment: Color,
    val syntaxWeak: Color,
) {
    companion object {
        private fun c(hex: String) = Color(android.graphics.Color.parseColor(hex))

        /** [data-color-scheme="dark"] semantic values. */
        val Dark = OcColors(
            bgBase = c("#161616"),
            bgDeep = c("#080808"),
            layer01 = c("#242424"),
            layer02 = c("#2e2e2e"),
            layer03 = c("#3a3a3a"),
            layer04 = c("#5c5c5c"),
            inverse = c("#ffffff"),
            contrast = c("#242424"),
            accent = c("#3b5cf6"),
            buttonNeutral = c("#ffffff0f"),
            textBase = c("#fafafa"),
            textMuted = c("#aeaeae"),
            textFaint = c("#808080"),
            textInverse = c("#161616"),
            textAccent = c("#a2bcff"),
            textAccentHover = c("#c3d4fd"),
            textCodeAccent = c("#a2bcff"),
            iconBase = c("#dbdbdb"),
            iconMuted = c("#808080"),
            iconInverse = c("#161616"),
            iconAccent = c("#a2bcff"),
            borderBase = c("#ffffff1a"),
            borderMuted = c("#ffffff14"),
            borderStrong = c("#ffffff33"),
            borderFocus = c("#7698fd"),
            overlayHover = c("#ffffff0f"),
            overlayPressed = c("#ffffff1a"),
            overlayScrim = c("#00000099"),
            stateSuccessBg = c("#14361d"),
            stateSuccessFg = c("#6bd586"),
            stateSuccessBorder = c("#1d783c"),
            stateWarningBg = c("#4b4025"),
            stateWarningFg = c("#f2cf76"),
            stateWarningBorder = c("#ac8833"),
            stateDangerBg = c("#461516"),
            stateDangerFg = c("#f17471"),
            stateDangerBorder = c("#97252b"),
            stateInfoBg = c("#1b2852"),
            stateInfoFg = c("#7698fd"),
            stateInfoBorder = c("#2c47c8"),
            agentPlan = c("#f799c6"),
            agentBuild = c("#c3d4fd"),
            agentExplore = c("#f7e5b5"),
            agentReview = c("#b8e9c1"),
            agentWriter = c("#9e99f7"),
            syntaxKeyword = c("#edb2f1"),
            syntaxProperty = c("#fab283"),
            syntaxType = c("#fcd53a"),
            syntaxString = c("#00ceb9"),
            syntaxConstant = c("#93e9f6"),
            syntaxPrimitive = c("#8cb0ff"),
            syntaxComment = c("#8f8f8f"),
            syntaxWeak = c("#8f8f8f"),
        )

        /** [data-color-scheme="light"] semantic values. */
        val Light = OcColors(
            bgBase = c("#ffffff"),
            bgDeep = c("#fafafa"),
            layer01 = c("#fafafa"),
            layer02 = c("#f2f2f2"),
            layer03 = c("#eeeeee"),
            layer04 = c("#dbdbdb"),
            inverse = c("#161616"),
            contrast = c("#242424"),
            accent = c("#3b5cf6"),
            buttonNeutral = c("#ffffff"),
            textBase = c("#161616"),
            textMuted = c("#5c5c5c"),
            textFaint = c("#808080"),
            textInverse = c("#ffffff"),
            textAccent = c("#3b5cf6"),
            textAccentHover = c("#3250df"),
            textCodeAccent = c("#263fa9"),
            iconBase = c("#3a3a3a"),
            iconMuted = c("#808080"),
            iconInverse = c("#ffffff"),
            iconAccent = c("#3b5cf6"),
            borderBase = c("#0000001a"),
            borderMuted = c("#00000014"),
            borderStrong = c("#00000033"),
            borderFocus = c("#7698fd"),
            overlayHover = c("#0000000a"),
            overlayPressed = c("#00000014"),
            overlayScrim = c("#00000099"),
            stateSuccessBg = c("#e7f9ea"),
            stateSuccessFg = c("#198b43"),
            stateSuccessBorder = c("#b8e9c1"),
            stateWarningBg = c("#fefaec"),
            stateWarningFg = c("#cb9f34"),
            stateWarningBorder = c("#f7e5b5"),
            stateDangerBg = c("#fceceb"),
            stateDangerFg = c("#b82d35"),
            stateDangerBorder = c("#f2bbb7"),
            stateInfoBg = c("#ecf1fe"),
            stateInfoFg = c("#2c47c8"),
            stateInfoBorder = c("#c3d4fd"),
            agentPlan = c("#c83d8b"),
            agentBuild = c("#2c47c8"),
            agentExplore = c("#ac8833"),
            agentReview = c("#198b43"),
            agentWriter = c("#623be2"),
            syntaxKeyword = c("#a753ae"),
            syntaxProperty = c("#ed6dc8"),
            syntaxType = c("#596600"),
            syntaxString = c("#006656"),
            syntaxConstant = c("#007b80"),
            syntaxPrimitive = c("#fb4804"),
            syntaxComment = c("#5c5c5c"),
            syntaxWeak = c("#5c5c5c"),
        )
    }
}

/** Elevation shadows, ported from `--v2-elevation-*` multi-layer box-shadows. */
@Immutable
data class OcElevation(
    val raised: OcShadow,
    val floating: OcShadow,
    val overlay: OcShadow,
) {
    companion object {
        // light/dark values from 02 §0.5; alpha scaled to the base elevation color.
        val Dark = OcElevation(
            raised = OcShadow(2f, 4f, Color(0x1E000000)),
            floating = OcShadow(8f, 16f, Color(0x1E000000)),
            overlay = OcShadow(16f, 32f, Color(0x1E000000)),
        )
        val Light = OcElevation(
            raised = OcShadow(2f, 4f, Color(0x0A000000)),
            floating = OcShadow(8f, 16f, Color(0x0A000000)),
            overlay = OcShadow(16f, 32f, Color(0x0A000000)),
        )
    }
}

@Immutable
data class OcShadow(
    val yOffset: Float,
    val blur: Float,
    val color: Color,
)

/**
 * OC2 type ramp. Body text is 13px/440 (`text-[13px] font-[440]`), emphasis
 * 530, components 500, markdown h1/h2 600. 440/530/600 need the Inter variable
 * font (wght 100–900); `FontWeight(440)` maps straight onto that axis.
 *
 * [fontFamily] / [monoFontFamily] carry the OC2 family decision (PLAN §1.3,
 * research/07 §2.4) into the ramp: body/UI = `"Inter", sans-serif`, generic
 * monospace = the system mono stack. Both are trailing parameters with
 * defaults, so existing positional construction keeps compiling.
 */
@Immutable
data class OcType(
    val xs: androidx.compose.ui.unit.TextUnit, // ~11px meta/keycaps
    val sm: androidx.compose.ui.unit.TextUnit, // 12px
    val base: androidx.compose.ui.unit.TextUnit, // 13px body (default)
    val lg: androidx.compose.ui.unit.TextUnit, // 14px (tool cards)
    val xl: androidx.compose.ui.unit.TextUnit, // 15px section titles
    val xl2: androidx.compose.ui.unit.TextUnit, // 16px
    val xl4: androidx.compose.ui.unit.TextUnit, // 20px
    val lineHeightCompact: androidx.compose.ui.unit.TextUnit, // 16px (leading-4)
    val lineHeightBase: androidx.compose.ui.unit.TextUnit, // 20px (leading-5)
    val fontFamily: androidx.compose.ui.text.font.FontFamily = OcFonts.default,
    val monoFontFamily: androidx.compose.ui.text.font.FontFamily = OcFonts.mono,
) {
    companion object {
        val Default = OcType(
            xs = 11f.sp,
            sm = 12f.sp,
            base = 13f.sp,
            lg = 14f.sp,
            xl = 15f.sp,
            xl2 = 16f.sp,
            xl4 = 20f.sp,
            lineHeightCompact = 16f.sp,
            lineHeightBase = 20f.sp,
        )
    }
}

/** `--radius-*`: xs2 / sm4 / md6 / lg8 / xl10 (rounded-xl = 10px). */
@Immutable
data class OcShapes(
    val xs: androidx.compose.foundation.shape.RoundedCornerShape,
    val sm: androidx.compose.foundation.shape.RoundedCornerShape,
    val md: androidx.compose.foundation.shape.RoundedCornerShape,
    val lg: androidx.compose.foundation.shape.RoundedCornerShape,
    val xl: androidx.compose.foundation.shape.RoundedCornerShape,
) {
    companion object {
        val Default = OcShapes(
            xs = androidx.compose.foundation.shape.RoundedCornerShape(2.dp),
            sm = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
            md = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
            lg = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            xl = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
        )
    }
}

internal val LocalOcColors = staticCompositionLocalOf { OcColors.Dark }
internal val LocalOcElevation = staticCompositionLocalOf { OcElevation.Dark }
internal val LocalOcType = staticCompositionLocalOf { OcType.Default }
internal val LocalOcShapes = staticCompositionLocalOf { OcShapes.Default }

/** Body/UI family, `"Inter", sans-serif`. Overridable for M4's font picker. */
internal val LocalOcFontFamily = staticCompositionLocalOf { OcFonts.default }

/** Generic monospace family (system mono stack). */
internal val LocalOcMonoFontFamily = staticCompositionLocalOf { OcFonts.mono }

/**
 * OC2 theme. Provides [OcColors] (dark/light), [OcType], [OcShapes],
 * [OcElevation] and the OC2 font families as CompositionLocals. Intentionally
 * NOT MaterialTheme colors — Compose components read [OcTheme.colors] directly
 * so the app is pixel-exact to opencode's tokens and never pinned to a M3
 * palette.
 *
 * [androidx.compose.material3.LocalTextStyle] is set to Inter at the body
 * weight (440) so a bare `Text("…")` is already OC2-correct — Material3's `Text`
 * reads that CompositionLocal. Only family + weight are supplied: font sizes and
 * line heights stay with the caller (or [OcType]) so adopting Inter cannot
 * silently re-layout screens written before this change.
 */
@androidx.compose.runtime.Composable
fun OcTheme(
    isDark: Boolean,
    content: @androidx.compose.runtime.Composable () -> Unit,
) {
    val colors = if (isDark) OcColors.Dark else OcColors.Light
    val elevation = if (isDark) OcElevation.Dark else OcElevation.Light
    val fontFamily = LocalOcFontFamily.current
    val textStyle = androidx.compose.material3.LocalTextStyle.current.merge(
        androidx.compose.ui.text.TextStyle(
            fontFamily = fontFamily,
            fontWeight = OcFontWeight.Body,
        ),
    )
    androidx.compose.runtime.CompositionLocalProvider(
        LocalOcColors provides colors,
        LocalOcElevation provides elevation,
        LocalOcType provides OcType.Default,
        LocalOcShapes provides OcShapes.Default,
        LocalOcFontFamily provides fontFamily,
        LocalOcMonoFontFamily provides LocalOcMonoFontFamily.current,
        androidx.compose.material3.LocalTextStyle provides textStyle,
    ) {
        content()
    }
}

object OcTheme {
    val colors: OcColors @Composable get() = LocalOcColors.current
    val elevation: OcElevation @Composable get() = LocalOcElevation.current
    val type: OcType @Composable get() = LocalOcType.current
    val shapes: OcShapes @Composable get() = LocalOcShapes.current

    /** `"Inter", sans-serif` — use for text that must not inherit the ambient style. */
    val font: androidx.compose.ui.text.font.FontFamily @Composable get() = LocalOcFontFamily.current

    /** System monospace stack (code blocks, diff, terminal fallback). */
    val monoFont: androidx.compose.ui.text.font.FontFamily @Composable get() = LocalOcMonoFontFamily.current
}
