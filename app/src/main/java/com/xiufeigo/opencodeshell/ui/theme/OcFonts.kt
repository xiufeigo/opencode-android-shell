package com.xiufeigo.opencodeshell.ui.theme

import android.graphics.Typeface
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.DeviceFontFamilyName
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
 *
 * Both roles are user-overridable — `settings-ui-font` → [uiFontFamily],
 * `settings-code-font` → [codeFontFamily], wired in `ui/App.kt` through
 * `OcTheme(uiFont, codeFont)`. The stops behind those rows (and the gap vs
 * upstream's free-text family fields) are [OcSystemFonts].
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

/**
 * Stored `opencode-ui-font` stop for the system sans stack
 * (`settings-ui-font`, upstream `settings.appearance.uiFont`).
 */
const val UI_FONT_SYSTEM: String = "system"

/**
 * Stored `opencode-code-font` stop that follows the UI font
 * (`settings-code-font`, upstream `settings.appearance.font`).
 */
const val CODE_FONT_FOLLOW_UI: String = "ui"

/**
 * Android's system font catalog, as far as a picker can honestly use it.
 *
 * Upstream's Appearance rows are **free-text** family inputs
 * (`settings/appearance/appearance.tsx:36-60` renders an `Input`; the value is
 * spliced into `sansFontFamily`/`monoFontFamily` as `"<name>", <base stack>`,
 * `settings/model.tsx:20-65`), so the browser resolves any installed family and
 * walks down the CSS stack when it does not exist. Android can do neither half
 * for an arbitrary name: a family that is not in the platform font config is
 * silently replaced by the **default sans** (`Typeface.create`; the terminal
 * panel probes exactly that, `ui/terminal/TerminalPanel.kt:1250-1265`), and
 * nothing can be downloaded without a font provider + certificates
 * (`FontsContract` = a new dependency, out of scope). The shell therefore offers
 * a list instead of a text field, in two tiers:
 *
 * * [SANS_ALIASES] / [MONO_ALIASES] — the generic family names AOSP's
 *   `fonts.xml` has declared since API 21. Every device resolves them, so they
 *   are always offered and always mapped.
 * * [SANS_CANDIDATES] / [MONO_CANDIDATES] — real family names (the common mono
 *   faces plus a few sans/serif families). A device has only some of them, so
 *   they are offered **only when [resolves] says the platform really has that
 *   family**: the picker never lists a name that would silently render as the
 *   default sans, which for a code font means proportional text.
 *
 * Remaining gap vs upstream, by design: a family the device does not have cannot
 * be named (no download), and enumeration is a fixed candidate list rather than
 * `SystemFonts.getAvailableFonts()` (API 29+, still only the *default* families
 * on most builds). Values are stored verbatim as the Android family name, so a
 * hand-written value resolves on a device that has that family.
 */
object OcSystemFonts {

    /** Always-resolvable sans stops, in picker order (labels in `SettingsModel`). */
    val SANS_ALIASES: List<String> = listOf(
        "serif",
        "sans-serif-light",
        "sans-serif-medium",
        "sans-serif-condensed",
        "sans-serif-black",
        "sans-serif-smallcaps",
    )

    /** Always-resolvable mono stop (Cutive Mono through the AOSP alias). */
    val MONO_ALIASES: List<String> = listOf("serif-monospace")

    /** Named sans/serif families, offered only when the device has them. */
    val SANS_CANDIDATES: List<String> = listOf(
        "Noto Sans",
        "Noto Serif",
        "Noto Sans Display",
        "Roboto Condensed",
        "Roboto Slab",
        "Open Sans",
        "Lato",
        "Montserrat",
        "Source Sans Pro",
        "Fira Sans",
        "Ubuntu",
        "Cantarell",
        "DejaVu Sans",
        "Liberation Sans",
        "Arial",
        "Helvetica",
    )

    /**
     * Named mono families, offered only when the device has them. `IBM Plex Mono`
     * leads because it is upstream's own `monoDefault` (`model.tsx:19`) — a user
     * who installed it gets upstream's default exactly.
     */
    val MONO_CANDIDATES: List<String> = listOf(
        "IBM Plex Mono",
        "Roboto Mono",
        "Droid Sans Mono",
        "Noto Sans Mono",
        "Noto Mono",
        "Cutive Mono",
        "JetBrains Mono",
        "Fira Code",
        "Source Code Pro",
        "Cascadia Mono",
        "Ubuntu Mono",
        "Space Mono",
        "Inconsolata",
        "Hack",
        "DejaVu Sans Mono",
        "Liberation Mono",
        "Courier New",
        "Consolas",
        "Menlo",
        "Monaco",
    )

    /** Families whose resolution to the default face is genuinely intended. */
    private val DEFAULT_FAMILY_NAMES = setOf("default", "sans-serif", "sans", "serif", "normal")

    private val probes = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val resolvedFamilies = java.util.concurrent.ConcurrentHashMap<String, FontFamily>()

    /**
     * Whether the platform font config really has [family]. `Typeface.create`
     * answers with the **default** family for an unknown name, so a name that
     * resolves to the default (and is not one of the generic aliases) is a miss.
     * Cached — this is called from composition. False off-device: the JVM unit
     * tests run against the unimplemented framework stubs, so the pickers fall
     * back to the always-available stops.
     */
    fun resolves(family: String): Boolean = probes.getOrPut(family) {
        runCatching {
            val resolved = Typeface.create(family, Typeface.NORMAL) ?: return@runCatching false
            val fallback = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                ?: return@runCatching false
            resolved != fallback || family.lowercase() in DEFAULT_FAMILY_NAMES
        }.getOrDefault(false)
    }

    /** The named sans families this device actually has. */
    fun availableSans(): List<String> = SANS_CANDIDATES.filter(::resolves)

    /** The named mono families this device actually has. */
    fun availableMono(): List<String> = MONO_CANDIDATES.filter(::resolves)

    /**
     * [SANS_ALIASES] + the subset of [available] drawn from [SANS_CANDIDATES],
     * in candidate order. Pure: the pickers pass [availableSans] on device and
     * tests pass a synthetic list.
     */
    fun sansStops(available: List<String>): List<String> =
        SANS_ALIASES + SANS_CANDIDATES.filter { it in available }

    /** [MONO_ALIASES] + the subset of [available] drawn from [MONO_CANDIDATES]. */
    fun monoStops(available: List<String>): List<String> =
        MONO_ALIASES + MONO_CANDIDATES.filter { it in available }

    /** Is [stored] a family this file can map as a UI font (alias or candidate)? */
    fun isSansStop(stored: String): Boolean = stored in SANS_ALIASES || stored in SANS_CANDIDATES

    /** Is [stored] a family this file can map as a code font? */
    fun isMonoStop(stored: String): Boolean = stored in MONO_ALIASES || stored in MONO_CANDIDATES

    /**
     * [name] as a Compose family, or [fallback] when the device does not have it
     * (never the other kind of family: a missing code font must stay monospace).
     * Normal + bold are declared so bold text resolves to the real bold face
     * instead of being flattened onto 400.
     */
    fun family(name: String, fallback: FontFamily): FontFamily {
        resolvedFamilies[name]?.let { return it }
        if (!resolves(name)) return fallback
        val family = runCatching {
            FontFamily(
                Font(DeviceFontFamilyName(name)),
                Font(DeviceFontFamilyName(name), FontWeight.Bold),
            )
        }.getOrNull() ?: return fallback
        resolvedFamilies[name] = family
        return family
    }
}

/**
 * `settings-ui-font` → family (upstream `sansFontFamily`, `model.tsx:59-65`).
 *
 * Blank = the bundled Inter (upstream's `sansDefault`), [UI_FONT_SYSTEM] = the
 * platform sans, and a family named by [OcSystemFonts] = that system family,
 * falling back to Inter when the device lacks it — upstream's own behaviour when
 * a named family is missing, since the CSS stack continues with `"Inter", …`.
 * Any other stored value (written by a future build, or by the web shell's
 * free-text field) stays on Inter rather than dropping the UI to the platform
 * default.
 */
fun uiFontFamily(stored: String): FontFamily = when {
    stored == UI_FONT_SYSTEM -> FontFamily.SansSerif
    stored.isBlank() -> InterFontFamily
    OcSystemFonts.isSansStop(stored) -> OcSystemFonts.family(stored, InterFontFamily)
    else -> InterFontFamily
}

/**
 * `settings-code-font` → family (upstream `monoFontFamily`, `model.tsx:55-57`).
 *
 * Upstream's default is `IBM Plex Mono`; the shell bundles no mono font, so
 * blank stays the system mono stack (the faithful port, see [OcMonoFontFamily])
 * and [CODE_FONT_FOLLOW_UI] renders code in the UI family. A family named by
 * [OcSystemFonts] (the `serif-monospace` alias, or a named mono the device has)
 * wins over the stack, falling back to the **system mono** when the device lacks
 * it — never to the default sans, which would silently un-monospace the code.
 */
fun codeFontFamily(code: String, ui: String): FontFamily = when {
    code == CODE_FONT_FOLLOW_UI -> uiFontFamily(ui)
    code.isBlank() -> OcMonoFontFamily
    OcSystemFonts.isMonoStop(code) -> OcSystemFonts.family(code, OcMonoFontFamily)
    else -> OcMonoFontFamily
}
