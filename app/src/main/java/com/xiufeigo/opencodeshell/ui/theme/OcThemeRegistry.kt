package com.xiufeigo.opencodeshell.ui.theme

/**
 * The upstream built-in theme collection, resolved to [OcColors].
 *
 * Source of truth: `_upstream/packages/ui/src/theme/themes/` (36 JSON files,
 * `default-themes.ts`) run through the real upstream pipelines at v2 head
 * (`theme/resolve.ts resolveThemeVariant` for `--syntax-*`/`--text-*`,
 * `theme/v2/resolve.ts resolveThemeVariantV2` for `--v2-*`, alpha ramps from
 * `ui/src/styles/tokens/colors.css`) with `var(--...)` refs chased to hex.
 * Values below are machine-generated from that output (lowercased, CSS
 * `#RRGGBBAA` order, which `OcColors.c` parses) -- do not hand-edit; re-run the
 * generator instead (`/tmp/dump-oc-themes.ts`, kept out of the repo on purpose).
 *
 * Two deliberate deviations for the default theme, both favouring the values
 * already shipped (transcribed 1:1 from the official build CSS, see [OcColors]):
 * [colors] returns [OcColors.Dark]/[OcColors.Light] verbatim for `oc-2`
 * instead of the re-resolved dump, because the snapshot pipeline drifted on two
 * stops since the vendored build was cut (dark `text-weak`/`text-strong`
 * `#707070`/`#EDEDED` vs shipped shimmer `#6b6b6b`/`#efefef`; light
 * `state-fg-info` `v2-blue-800` `#3250df` vs shipped `#2c47c8`). Every other
 * theme id uses the snapshot pipeline output exactly.
 *
 * Elevation stays mode-static ([OcElevation.Dark]/[OcElevation.Light]): upstream
 * elevations reference only the static alpha ramps, so they are
 * theme-independent.
 */
object OcThemeRegistry {
    /** The id the store falls back to; also the only theme with no data row below. */
    const val DEFAULT_ID = "oc-2"

    /**
     * Picker order: the default first, then the built-ins alphabetically by
     * display name (upstream lists `theme.ids()` glob order, which is
     * filesystem-dependent -- a stable sort is the honest mobile mapping).
     */
    val ORDERED_IDS: List<String> = listOf(
        DEFAULT_ID,
        "amoled",
        "aura",
        "ayu",
        "carbonfox",
        "catppuccin",
        "catppuccin-frappe",
        "catppuccin-macchiato",
        "cobalt2",
        "cursor",
        "dracula",
        "everforest",
        "flexoki",
        "github",
        "gruvbox",
        "kanagawa",
        "lucent-orng",
        "material",
        "matrix",
        "mercury",
        "monokai",
        "nightowl",
        "nord",
        "one-dark",
        "onedarkpro",
        "orng",
        "osaka-jade",
        "palenight",
        "rosepine",
        "shadesofpurple",
        "solarized",
        "synthwave84",
        "tokyonight",
        "vercel",
        "vesper",
        "zenburn",
    )

    /** Display name for the picker (`DesktopTheme.name`). */
    fun displayName(id: String): String =
        if (id == DEFAULT_ID) "OpenCode" else BY_ID[id]?.name ?: id

    /**
     * Upstream `resolveStoredTheme`: an unknown stored id falls back to the
     * default instead of rendering half a theme.
     */
    fun resolveId(raw: String?): String =
        if (raw != null && (raw == DEFAULT_ID || BY_ID.containsKey(raw))) raw else DEFAULT_ID

    /** Number of themes behind the picker (test anchor). */
    val themeCount: Int get() = ORDERED_IDS.size

    /**
     * The palette for [themeId] in [isDark] mode. Unknown ids (the store accepts
     * any non-blank string) resolve to the default, never to null.
     */
    fun colors(themeId: String?, isDark: Boolean): OcColors {
        if (themeId == null || themeId == DEFAULT_ID) return if (isDark) OcColors.Dark else OcColors.Light
        val entry = BY_ID[themeId] ?: return if (isDark) OcColors.Dark else OcColors.Light
        return build(if (isDark) entry.dark else entry.light)
    }

    /**
     * Positional build: index `i` of [hex] is the i-th [OcColors] field in
     * declaration order (bgBase .. shimmerPeak, 63 total). The `oc-2` identity
     * test (`ThemeRegistryTest`) pins this order -- a column slip fails loudly
     * instead of tinting the app wrong.
     */
    private fun build(hex: List<String>): OcColors {
        require(hex.size == 63) { "theme data must carry 63 stops, got " + hex.size }
        return OcColors(
            bgBase = OcColors.c(hex[0]),
            bgDeep = OcColors.c(hex[1]),
            layer01 = OcColors.c(hex[2]),
            layer02 = OcColors.c(hex[3]),
            layer03 = OcColors.c(hex[4]),
            layer04 = OcColors.c(hex[5]),
            inverse = OcColors.c(hex[6]),
            contrast = OcColors.c(hex[7]),
            accent = OcColors.c(hex[8]),
            bgCodePath = OcColors.c(hex[9]),
            bgIconButtonContrast = OcColors.c(hex[10]),
            buttonNeutral = OcColors.c(hex[11]),
            textBase = OcColors.c(hex[12]),
            textMuted = OcColors.c(hex[13]),
            textFaint = OcColors.c(hex[14]),
            textInverse = OcColors.c(hex[15]),
            textContrast = OcColors.c(hex[16]),
            textAccent = OcColors.c(hex[17]),
            textAccentHover = OcColors.c(hex[18]),
            textCodeAccent = OcColors.c(hex[19]),
            textCodePath = OcColors.c(hex[20]),
            iconBase = OcColors.c(hex[21]),
            iconMuted = OcColors.c(hex[22]),
            iconFaint = OcColors.c(hex[23]),
            iconInverse = OcColors.c(hex[24]),
            iconContrast = OcColors.c(hex[25]),
            iconAccent = OcColors.c(hex[26]),
            iconAccentHover = OcColors.c(hex[27]),
            borderBase = OcColors.c(hex[28]),
            borderMuted = OcColors.c(hex[29]),
            borderStrong = OcColors.c(hex[30]),
            borderInverse = OcColors.c(hex[31]),
            borderFocus = OcColors.c(hex[32]),
            overlayHover = OcColors.c(hex[33]),
            overlayPressed = OcColors.c(hex[34]),
            overlayScrim = OcColors.c(hex[35]),
            stateSuccessBg = OcColors.c(hex[36]),
            stateSuccessFg = OcColors.c(hex[37]),
            stateSuccessBorder = OcColors.c(hex[38]),
            stateWarningBg = OcColors.c(hex[39]),
            stateWarningFg = OcColors.c(hex[40]),
            stateWarningBorder = OcColors.c(hex[41]),
            stateDangerBg = OcColors.c(hex[42]),
            stateDangerFg = OcColors.c(hex[43]),
            stateDangerBorder = OcColors.c(hex[44]),
            stateInfoBg = OcColors.c(hex[45]),
            stateInfoFg = OcColors.c(hex[46]),
            stateInfoBorder = OcColors.c(hex[47]),
            agentPlan = OcColors.c(hex[48]),
            agentBuild = OcColors.c(hex[49]),
            agentExplore = OcColors.c(hex[50]),
            agentReview = OcColors.c(hex[51]),
            agentWriter = OcColors.c(hex[52]),
            syntaxKeyword = OcColors.c(hex[53]),
            syntaxProperty = OcColors.c(hex[54]),
            syntaxType = OcColors.c(hex[55]),
            syntaxString = OcColors.c(hex[56]),
            syntaxConstant = OcColors.c(hex[57]),
            syntaxPrimitive = OcColors.c(hex[58]),
            syntaxComment = OcColors.c(hex[59]),
            syntaxWeak = OcColors.c(hex[60]),
            shimmerBase = OcColors.c(hex[61]),
            shimmerPeak = OcColors.c(hex[62]),
        )
    }

    private data class ThemeHex(
        val id: String,
        val name: String,
        val light: List<String>,
        val dark: List<String>,
    )

    private val THEMES: List<ThemeHex> = listOf(
        ThemeHex(
            id = "amoled",
            name = "AMOLED",
            light = listOf(
                "#f1f1f1", "#ebebeb", "#e6e6e6", "#dfdfdf", "#d6d6d6", "#cacaca", "#585858", "#828282",
                "#610efc", "#f8fdff", "#828282", "#f1f1f1", "#010101", "#232323", "#3c3c3c", "#f1f1f1",
                "#f1f1f1", "#610efc", "#4e0fcc", "#2a0b74", "#2a0b74", "#2e2e2e", "#828282", "#bababa",
                "#ffffffff", "#f1f1f1", "#610efc", "#4e0fcc", "#0000001a", "#00000014", "#00000033", "#585858",
                "#c2c3fc", "#0000000a", "#00000014", "#00000066", "#f5fff7", "#178746", "#affec3", "#fffbf6",
                "#976615", "#fee3c2", "#fcfcfc", "#b8062d", "#fde0de", "#f2f2fe", "#3c02a4", "#e5e6fd",
                "#1879ae", "#3c02a4", "#764f0f", "#178746", "#d6206e", "#d500f9", "#ff9100", "#785110",
                "#00e676", "#6200ff", "#00b0ff", "#757575", "#757575", "#232323", "#010101"
            ),
            dark = listOf(
                "#000000", "#000000", "#030303", "#191919", "#303030", "#585858", "#fafafa", "#0a0a0a",
                "#b28bf9", "#1b1b1b", "#585858", "#ffffff0f", "#ffffff", "#dbdbdb", "#bebebe", "#000000",
                "#fafafa", "#ded1fe", "#ebe3fe", "#ded1fe", "#ebe3fe", "#cccccc", "#8f8f8f", "#303030",
                "#000000", "#fafafa", "#ded1fe", "#ebe3fe", "#ffffff1a", "#ffffff14", "#ffffff33", "#fafafa",
                "#d1bcfe", "#ffffff0f", "#ffffff1a", "#00000099", "#00220d", "#27ee82", "#0a6a36", "#1e1b02",
                "#ddcc36", "#615a11", "#390208", "#feb2b0", "#911627", "#230445", "#d1bcfe", "#6b2cb6",
                "#53f4f4", "#ebe3fe", "#f8ec7e", "#adfec4", "#fec9d3", "#ff00ff", "#ffea00", "#fef5a2",
                "#00ff88", "#b388ff", "#18ffff", "#555555", "#555555", "#dbdbdb", "#ffffff"
            ),
        ),
        ThemeHex(
            id = "aura",
            name = "Aura",
            light = listOf(
                "#f6f3fe", "#f1eef9", "#ede9f5", "#e7e3f0", "#dfdce9", "#d6d2e0", "#756f85", "#9894a7",
                "#a278fd", "#f6fdff", "#9894a7", "#f6f3fe", "#170e28", "#423b55", "#5d576d", "#f6f3fe",
                "#f6f3fe", "#a278fd", "#9058f4", "#6a13cb", "#6a13cb", "#4e4861", "#756f85", "#c8c4d3",
                "#4e4861", "#f6f3fe", "#a278fd", "#9058f4", "#0000001a", "#00000014", "#00000033", "#756f85",
                "#cdbefe", "#0000000a", "#00000014", "#00000066", "#f5fff8", "#09874e", "#acfec9", "#fffbf6",
                "#956814", "#fee4be", "#fffbfa", "#ad0620", "#ffdfdd", "#f4f1ff", "#802bef", "#e9e4fe",
                "#167d9c", "#802bef", "#74500f", "#09874e", "#d20128", "#7b5ae0", "#a96a22", "#2b8a57",
                "#2b8a57", "#d94f4f", "#2f78b8", "#8d88a3", "#8d88a3", "#423b55", "#170e28"
            ),
            dark = listOf(
                "#121117", "#0e0d13", "#1c1b21", "#333137", "#47454b", "#68666c", "#e9e8ea", "#25242a",
                "#a17af8", "#1b1b1b", "#68666c", "#ffffff0f", "#fdfdfd", "#c9c8ca", "#acacad", "#121117",
                "#e9e8ea", "#dbd2fc", "#e9e3ff", "#dbd2fc", "#e9e3ff", "#c5c4c7", "#68666c", "#47454b",
                "#0e0d13", "#e9e8ea", "#dbd2fc", "#e9e3ff", "#ffffff1a", "#ffffff14", "#ffffff33", "#e9e8ea",
                "#cdbefb", "#ffffff0f", "#ffffff1a", "#00000099", "#022116", "#3de9b4", "#15674e", "#271701",
                "#f4be78", "#764f10", "#3a0106", "#ffb2ad", "#a40f23", "#210447", "#cdbefb", "#681ec6",
                "#9fe6fc", "#e9e3ff", "#fee3c2", "#a0ffda", "#fccbc8", "#a277ff", "#ffca85", "#61ffca",
                "#61ffca", "#ff6767", "#82e2ff", "#6d6a7e", "#6d6a7e", "#c9c8ca", "#fdfdfd"
            ),
        ),
        ThemeHex(
            id = "ayu",
            name = "Ayu",
            light = listOf(
                "#fdfbf5", "#f9f7f1", "#f5f4ed", "#f0efe9", "#eaeae3", "#e1e2db", "#899796", "#aab4ae",
                "#26aad1", "#f7fdff", "#aab4ae", "#fdfbf5", "#2b3540", "#5c6570", "#7a828b", "#fdfbf5",
                "#fdfbf5", "#26aad1", "#0d93b7", "#106179", "#106179", "#c4cac2", "#68777c", "#c4cac2",
                "#68777c", "#fdfbf5", "#26aad1", "#0d93b7", "#0000001a", "#00000014", "#00000033", "#899796",
                "#73d8fc", "#0000000a", "#00000014", "#00000066", "#f5fff7", "#178844", "#bbfac9", "#fffbf7",
                "#9b6419", "#fee3c7", "#fffbfa", "#c21f38", "#fde0df", "#e5f7fe", "#157d9b", "#cbeffd",
                "#02709a", "#157d9b", "#794d12", "#178844", "#ed4b42", "#c76a1a", "#2f86b7", "#227fc0",
                "#6f8f00", "#a37acc", "#b87500", "#6e7681", "#6e7681", "#5c6570", "#2b3540"
            ),
            dark = listOf(
                "#0b1015", "#080d12", "#151a1f", "#292e34", "#3c4147", "#5b5f66", "#d2d6dc", "#1d2227",
                "#32b4e1", "#021f26", "#5b5f66", "#ffffff0f", "#fbfbfc", "#b3b7bc", "#989b9f", "#0b1015",
                "#d2d6dc", "#a9e3fc", "#caefff", "#a9e3fc", "#caefff", "#b1b5bb", "#84888e", "#3c4147",
                "#080d12", "#d2d6dc", "#a9e3fc", "#caefff", "#ffffff1a", "#ffffff14", "#ffffff33", "#d2d6dc",
                "#7dd6fc", "#ffffff0f", "#ffffff1a", "#00000099", "#072101", "#89e46d", "#29690e", "#271602",
                "#fabb6e", "#784e13", "#380401", "#fbb5a8", "#a02010", "#011e29", "#7dd6fc", "#0d617c",
                "#abe2fe", "#caefff", "#fde3c7", "#bffbae", "#fcccc2", "#ff8f40", "#39bae6", "#59c2ff",
                "#aad94c", "#d2a6ff", "#ffb454", "#5a6673", "#5a6673", "#b3b7bc", "#fbfbfc"
            ),
        ),
        ThemeHex(
            id = "carbonfox",
            name = "Carbonfox",
            light = listOf(
                "#eeeeee", "#e9e9e9", "#e4e4e4", "#dddddd", "#d5d5d5", "#cacaca", "#616161", "#888888",
                "#1363fb", "#f9fcff", "#888888", "#eeeeee", "#070707", "#2f2f2f", "#494949", "#eeeeee",
                "#eeeeee", "#1363fb", "#1353cf", "#073183", "#073183", "#383838", "#888888", "#bbbbbb",
                "#ffffffff", "#eeeeee", "#1363fb", "#1353cf", "#0000001a", "#00000014", "#00000033", "#616161",
                "#accaff", "#0000000a", "#00000014", "#00000066", "#f6fff6", "#105223", "#bafbc3", "#fffcf3",
                "#896e13", "#fee6a5", "#fcfcfc", "#980313", "#fde0dd", "#eef4fe", "#0c42a8", "#dce9fe",
                "#052979", "#0c42a8", "#6b550e", "#105223", "#bb051a", "#8a3ffc", "#0043ce", "#8a5f00",
                "#198038", "#da1e28", "#0f62fe", "#6f6f6f", "#6f6f6f", "#2f2f2f", "#070707"
            ),
            dark = listOf(
                "#292929", "#252525", "#333333", "#484948", "#5b5c5b", "#797b7a", "#eef0f4", "#3b3c3b",
                "#4b8af8", "#011c34", "#797b7a", "#ffffff0f", "#fefefe", "#ced0d3", "#b2b3b6", "#292929",
                "#eef0f4", "#c6dafc", "#dce9fe", "#c6dafc", "#dce9fe", "#cdd0d2", "#797b7a", "#5b5c5b",
                "#252525", "#eef0f4", "#c6dafc", "#dce9fe", "#ffffff1a", "#ffffff14", "#ffffff33", "#eef0f4",
                "#adcafa", "#ffffff0f", "#ffffff1a", "#00000099", "#02220b", "#6ce78a", "#176931", "#221a02",
                "#f3c30e", "#6c5501", "#39010a", "#ffb1b2", "#a40d2f", "#011742", "#adcafa", "#0348b2",
                "#c5daff", "#dce9fe", "#fde6a9", "#b1febf", "#fccbcb", "#be95ff", "#78a9ff", "#f1c21b",
                "#42be65", "#ff8389", "#33b1ff", "#6f6f6f", "#6f6f6f", "#ced0d3", "#fefefe"
            ),
        ),
        ThemeHex(
            id = "catppuccin",
            name = "Catppuccin",
            light = listOf(
                "#f7e6e2", "#f4e2df", "#f1dfdc", "#eddad8", "#e8d4d3", "#e1cdcd", "#948395", "#b39ea9",
                "#7387fb", "#f7fdff", "#b39ea9", "#f7e6e2", "#2a2c46", "#5a5c75", "#777a8e", "#f7e6e2",
                "#f7e6e2", "#7387fb", "#5b6df3", "#383acb", "#383acb", "#cab4b9", "#70677f", "#cab4b9",
                "#70677f", "#f7e6e2", "#7387fb", "#5b6df3", "#0000001a", "#00000014", "#00000033", "#948395",
                "#b7c6fe", "#0000000a", "#00000014", "#00000066", "#f6fff4", "#1d7303", "#bafdac", "#fffbf7",
                "#9e6207", "#fde3c8", "#fcfcfc", "#8b1025", "#ffdfde", "#f0f3ff", "#474dee", "#e1e7fe",
                "#0378a8", "#474dee", "#7b4c06", "#1d7303", "#ac1831", "#8839ef", "#096188", "#7e4d07",
                "#1f6c09", "#ca6702", "#1e66f5", "#6c7086", "#6c7086", "#5a5c75", "#2a2c46"
            ),
            dark = listOf(
                "#151524", "#121220", "#1e1f2e", "#313244", "#424458", "#5e6177", "#cad2f0", "#252637",
                "#939ded", "#081f20", "#5e6177", "#ffffff0f", "#fafbfe", "#abb3ce", "#9197ae", "#151524",
                "#cad2f0", "#d0d7fc", "#e2e6fe", "#d0d7fc", "#e2e6fe", "#acb3cf", "#8388a1", "#424458",
                "#121220", "#cad2f0", "#d0d7fc", "#e2e6fe", "#ffffff1a", "#ffffff14", "#ffffff33", "#cad2f0",
                "#bcc5fa", "#ffffff0f", "#ffffff1a", "#00000099", "#0e2000", "#add98f", "#396608", "#2f0728",
                "#f0b3df", "#7c3f6e", "#370217", "#ffafc3", "#972550", "#13133e", "#bcc5fa", "#4a5093",
                "#a1e7f4", "#e2e6fe", "#fedcf4", "#d5f3c2", "#fccad5", "#cba6f7", "#7deafd", "#fcc5ed",
                "#9efd49", "#fab387", "#89b4fa", "#6c7086", "#6c7086", "#abb3ce", "#fafbfe"
            ),
        ),
        ThemeHex(
            id = "catppuccin-frappe",
            name = "Catppuccin Frappe",
            light = listOf(
                "#dfe0e3", "#dee0e3", "#dedfe4", "#dddfe4", "#dcdee5", "#dbdee6", "#cfd6ee", "#d4d9eb",
                "#83a1f4", "#f4fffb", "#d4d9eb", "#dfe0e3", "#7e87ab", "#b5bfe2", "#d8dffb", "#dfe0e3",
                "#dfe0e3", "#83a1f4", "#6a87dd", "#3d5399", "#3d5399", "#d7dbe9", "#dbdee6", "#dcdee5",
                "#cbd3f2", "#dfe0e3", "#83a1f4", "#6a87dd", "#0000001a", "#00000014", "#00000033", "#cfd6ee",
                "#b5c8fa", "#0000000a", "#00000014", "#00000066", "#f7fff2", "#4d8219", "#d4f4c0", "#fffbf5",
                "#8f6b13", "#fae5bd", "#fffbfa", "#bf3c48", "#fedfdf", "#eff3fe", "#526cbb", "#dfe8fe",
                "#208278", "#526cbb", "#70530e", "#4d8219", "#bb6da9", "#ca9ee6", "#99d1db", "#e5c890",
                "#a6d189", "#ef9f76", "#8da4e2", "#949cb8", "#949cb8", "#b5bfe2", "#7e87ab"
            ),
            dark = listOf(
                "#212435", "#1e2131", "#292d3f", "#3b3f53", "#4a4f66", "#636983", "#c3cdf1", "#303447",
                "#85a1f1", "#032019", "#636983", "#ffffff0f", "#fafbfe", "#b5bfe2", "#8c93b0", "#212435",
                "#c3cdf1", "#cad9fd", "#dfe8fd", "#cad9fd", "#dfe8fd", "#c3cdf1", "#848ca9", "#4a4f66",
                "#1e2131", "#c3cdf1", "#cad9fd", "#dfe8fd", "#ffffff1a", "#ffffff14", "#ffffff33", "#c3cdf1",
                "#b3c8fd", "#ffffff0f", "#ffffff1a", "#00000099", "#0e2000", "#add98f", "#396608", "#241902",
                "#e2c58c", "#715202", "#37040a", "#fbb4b3", "#992935", "#0b1540", "#b3c8fd", "#3e5396",
                "#abe7de", "#dfe8fd", "#f9e5c0", "#d5f3c2", "#fbc6ec", "#ca9ee6", "#99d1db", "#e5c890",
                "#a6d189", "#ef9f76", "#8da4e2", "#949cb8", "#949cb8", "#b5bfe2", "#fafbfe"
            ),
        ),
        ThemeHex(
            id = "catppuccin-macchiato",
            name = "Catppuccin Macchiato",
            light = listOf(
                "#dddde1", "#dddde1", "#dcdde2", "#dcdce2", "#dcdce3", "#dbdbe4", "#d3d6ed", "#d6d8ea",
                "#79a2f8", "#f3fffb", "#d6d8ea", "#dddde1", "#8189aa", "#b8c0e0", "#dbe2fb", "#dddde1",
                "#dddde1", "#79a2f8", "#5a88e9", "#2f53a3", "#2f53a3", "#d9dae7", "#dbdbe4", "#dcdce3",
                "#cfd5f1", "#dddde1", "#79a2f8", "#5a88e9", "#0000001a", "#00000014", "#00000033", "#d3d6ed",
                "#afc9fc", "#0000000a", "#00000014", "#00000066", "#f6fff4", "#418426", "#cff5c3", "#fffbf5",
                "#8c6c1f", "#f8e6c0", "#fffbfb", "#be3a58", "#fedfe2", "#eef4fe", "#436dc7", "#dee8fd",
                "#198378", "#436dc7", "#705305", "#418426", "#b86fa7", "#c6a0f6", "#91d7e3", "#eed49f",
                "#a6da95", "#f5a97f", "#8aadf4", "#939ab7", "#939ab7", "#b8c0e0", "#8189aa"
            ),
            dark = listOf(
                "#191b2d", "#161829", "#222437", "#34374c", "#45495f", "#5f657e", "#c7d0f1", "#292c40",
                "#77a2fd", "#022018", "#5f657e", "#ffffff0f", "#fafbfe", "#b8c0e0", "#8f95b0", "#191b2d",
                "#c7d0f1", "#c8dafb", "#dde8fe", "#c8dafb", "#dde8fe", "#aab2d1", "#8389a6", "#45495f",
                "#161829", "#c7d0f1", "#c8dafb", "#dde8fe", "#ffffff1a", "#ffffff14", "#ffffff33", "#c7d0f1",
                "#b1c9fa", "#ffffff0f", "#ffffff1a", "#00000099", "#0a2102", "#a5da94", "#2f6816", "#241901",
                "#e0c690", "#6f530f", "#370311", "#ffb0ba", "#982843", "#031446", "#b1c9fa", "#3153a0",
                "#a9e8de", "#dde8fe", "#f8e6c2", "#d0f4c5", "#f8c8eb", "#c6a0f6", "#91d7e3", "#eed49f",
                "#a6da95", "#f5a97f", "#8aadf4", "#939ab7", "#939ab7", "#b8c0e0", "#fafbfe"
            ),
        ),
        ThemeHex(
            id = "cobalt2",
            name = "Cobalt2",
            light = listOf(
                "#ffffff", "#fafaf9", "#f6f5f4", "#f0f0ed", "#e8e8e4", "#dedfd9", "#68817d", "#96a49a",
                "#0766ca", "#fffbfa", "#96a49a", "#ffffff", "#021c2d", "#5c6b7d", "#506575", "#ffffff",
                "#ffffff", "#0766ca", "#0953a5", "#012f64", "#012f64", "#395a62", "#68817d", "#cfd1ca",
                "#395a62", "#ffffff", "#0766ca", "#0953a5", "#0000001a", "#00000014", "#00000033", "#68817d",
                "#a6ccfe", "#0000000a", "#00000014", "#00000066", "#f5fff5", "#1a8325", "#b8fdb7", "#fffbf7",
                "#9e611c", "#fde2cb", "#fcfcfc", "#a60843", "#fddfe3", "#ecf4ff", "#034184", "#dae9fe",
                "#bc3e16", "#034184", "#7f4902", "#1a8325", "#2293a4", "#ff5722", "#00acc1", "#00acc1",
                "#4caf50", "#e91e63", "#ff9800", "#5c6b7d", "#5c6b7d", "#5c6b7d", "#021c2d"
            ),
            dark = listOf(
                "#092738", "#062335", "#133341", "#2c4c52", "#446160", "#6c817a", "#fbfbfa", "#1d3d47",
                "#1e89f7", "#2b1304", "#6c817a", "#ffffff0f", "#ffffff", "#adb7c9", "#bebebe", "#092738",
                "#fbfbfa", "#c1dcfd", "#daeafd", "#c1dcfd", "#daeafd", "#d7d8d3", "#6c817a", "#446160",
                "#062335", "#fbfbfa", "#c1dcfd", "#daeafd", "#ffffff1a", "#ffffff14", "#ffffff33", "#fbfbfa",
                "#a5ccfc", "#ffffff0f", "#ffffff1a", "#00000099", "#082102", "#83e564", "#2c6814", "#231901",
                "#f5c12d", "#6d540d", "#350419", "#fcb0c6", "#93134e", "#011a39", "#a5ccfc", "#0b4d90",
                "#fdd0a2", "#daeafd", "#fee6ac", "#bcfda9", "#5bf6da", "#ff9d00", "#2affdf", "#2affdf",
                "#9eff80", "#ff628c", "#ffc600", "#0088ff", "#0088ff", "#adb7c9", "#ffffff"
            ),
        ),
        ThemeHex(
            id = "cursor",
            name = "Cursor",
            light = listOf(
                "#fcfcfc", "#f6f6f6", "#f1f1f1", "#eaeaea", "#e1e1e1", "#d5d5d5", "#646464", "#8e8e8e",
                "#13659a", "#f8fcff", "#8e8e8e", "#fcfcfc", "#050505", "#141414ad", "#474747", "#fcfcfc",
                "#fcfcfc", "#13659a", "#0f517c", "#012b47", "#012b47", "#393939", "#8e8e8e", "#c5c5c5",
                "#ffffffff", "#fcfcfc", "#13659a", "#0f517c", "#0000001a", "#00000014", "#00000033", "#646464",
                "#96d0ff", "#0000000a", "#00000014", "#00000066", "#f4fff9", "#075d42", "#b7f9da", "#fffbf9",
                "#b04011", "#fee0d6", "#fffbfb", "#901437", "#ffdfe1", "#eaf5ff", "#063d61", "#d5ebfe",
                "#0e537e", "#063d61", "#8f3512", "#075d42", "#478998", "#b3003f", "#141414ad", "#206595",
                "#9e94d5", "#b8448b", "#db704b", "#141414ad", "#141414ad", "#141414ad", "#050505"
            ),
            dark = listOf(
                "#141414", "#111111", "#1e1e1e", "#343434", "#474747", "#666666", "#e0e0e0", "#272727",
                "#48b9b4", "#001d2d", "#666666", "#ffffff0f", "#fcfcfc", "#e4e4e45e", "#a4a4a4", "#141414",
                "#e0e0e0", "#a4e8e4", "#bff4f1", "#a4e8e4", "#bff4f1", "#bebebe", "#909090", "#474747",
                "#111111", "#e0e0e0", "#a4e8e4", "#bff4f1", "#ffffff1a", "#ffffff14", "#ffffff33", "#e0e0e0",
                "#88dad6", "#ffffff0f", "#ffffff1a", "#00000099", "#01220e", "#7fe2a1", "#115f34", "#271601",
                "#fabb6c", "#774e10", "#370313", "#fab2be", "#90003a", "#01201f", "#88dad6", "#0d6563",
                "#c0ddfa", "#bff4f1", "#ffe3c2", "#bafacd", "#b2e2f1", "#82d2ce", "#81a1c1", "#efb080",
                "#e394dc", "#f8c762", "#efb080", "#e4e4e45e", "#e4e4e45e", "#e4e4e45e", "#fcfcfc"
            ),
        ),
        ThemeHex(
            id = "dracula",
            name = "Dracula",
            light = listOf(
                "#f9f9f4", "#f3f4ee", "#eeefe9", "#e7e9e3", "#dee1db", "#d3d7d0", "#5f7074", "#899794",
                "#7c67ff", "#f8fcff", "#899794", "#f9f9f4", "#0c0b1a", "#363646", "#51515f", "#f9f9f4",
                "#f9f9f4", "#7c67ff", "#6b42f9", "#4815b8", "#4815b8", "#3a4552", "#5f7074", "#c2c9c2",
                "#ffffffff", "#f9f9f4", "#7c67ff", "#6b42f9", "#0000001a", "#00000014", "#00000033", "#5f7074",
                "#c2c2fd", "#0000000a", "#00000014", "#00000066", "#f5fff8", "#18874d", "#affdc8", "#fffbf8",
                "#a45e04", "#fee2cb", "#fffafb", "#ad1446", "#fedfe2", "#f2f2ff", "#5b15e8", "#e5e5fd",
                "#07568a", "#5b15e8", "#804804", "#18874d", "#cf247d", "#d16090", "#1d7fc5", "#824a05",
                "#596600", "#7c6bf5", "#2f8f57", "#7d7f97", "#7d7f97", "#363646", "#0c0b1a"
            ),
            dark = listOf(
                "#151720", "#12131c", "#1e232b", "#323b42", "#465055", "#677373", "#f3f4ee", "#262c34",
                "#b987fd", "#1b1b1b", "#677373", "#ffffff0f", "#fefefe", "#d4d4ce", "#b7b7b2", "#151720",
                "#f3f4ee", "#e0d0fc", "#ece2fe", "#e0d0fc", "#ece2fe", "#ccd0ca", "#677373", "#465055",
                "#12131c", "#f3f4ee", "#e0d0fc", "#ece2fe", "#ffffff1a", "#ffffff14", "#ffffff33", "#f3f4ee",
                "#d3bcfa", "#ffffff0f", "#ffffff1a", "#00000099", "#022209", "#38ee6d", "#136a2c", "#291501",
                "#fbba76", "#7b4c0d", "#3a0104", "#ffb2ac", "#a4101f", "#250442", "#d3bcfa", "#6c35a7",
                "#99e8f9", "#ece2fe", "#fde3c9", "#afffba", "#fcc8e2", "#ff79c6", "#8be9fd", "#fed7b1",
                "#f1fa8c", "#bd93f9", "#50fa7b", "#6272a4", "#6272a4", "#d4d4ce", "#fefefe"
            ),
        ),
        ThemeHex(
            id = "everforest",
            name = "Everforest",
            light = listOf(
                "#fdf8e8", "#f9f5e5", "#f5f2e2", "#f0eede", "#eaead9", "#e1e3d3", "#90a29d", "#adbbb1",
                "#8da10f", "#f5fff7", "#adbbb1", "#fdf8e8", "#334048", "#a6b0a0", "#858f95", "#fdf8e8",
                "#fdf8e8", "#8da10f", "#798a1c", "#535f0a", "#535f0a", "#c5cec0", "#738587", "#c5cec0",
                "#738587", "#fdf8e8", "#8da10f", "#798a1c", "#0000001a", "#00000014", "#00000033", "#90a29d",
                "#bfd747", "#0000000a", "#00000014", "#00000066", "#faffea", "#66741b", "#e1f39c", "#fffbf9",
                "#ae5510", "#fee1d1", "#fffbfa", "#c4222a", "#fde0dd", "#eefcc0", "#66741b", "#e1f39c",
                "#0c7a56", "#66741b", "#88410c", "#66741b", "#dd28b0", "#df69ba", "#35a77c", "#dfa000",
                "#8da101", "#f57d26", "#8da101", "#a6b0a0", "#a6b0a0", "#a6b0a0", "#334048"
            ),
            dark = listOf(
                "#1c252b", "#1a2228", "#232e33", "#314144", "#3e5151", "#576b64", "#cfc3a7", "#28353a",
                "#93b25e", "#072102", "#576b64", "#ffffff0f", "#fbfaf7", "#7a8478", "#988e79", "#1c252b",
                "#cfc3a7", "#cce3ab", "#def0c4", "#cce3ab", "#def0c4", "#cfc3a7", "#7c8c7a", "#3e5151",
                "#1a2228", "#cfc3a7", "#cce3ab", "#def0c4", "#ffffff1a", "#ffffff14", "#ffffff33", "#cfc3a7",
                "#bad492", "#ffffff0f", "#ffffff1a", "#00000099", "#151f02", "#bad492", "#496212", "#310f01",
                "#ffb594", "#8e3b0c", "#38030a", "#fcb3b2", "#9b2633", "#151f02", "#bad492", "#496212",
                "#b3e9bf", "#def0c4", "#fde1d5", "#def0c4", "#fec7e0", "#d699b6", "#83c092", "#dbbc7f",
                "#a7c080", "#e69875", "#a7c080", "#7a8478", "#7a8478", "#7a8478", "#fbfaf7"
            ),
        ),
        ThemeHex(
            id = "flexoki",
            name = "Flexoki",
            light = listOf(
                "#fffcf3", "#f9f6ec", "#f4f0e7", "#ede9e0", "#e4e0d7", "#d8d4cc", "#645f5d", "#8f8a86",
                "#165dac", "#f4fffb", "#8f8a86", "#fffcf3", "#040303", "#6f6e69", "#434242", "#fffcf3",
                "#fffcf3", "#165dac", "#104a8b", "#012650", "#012650", "#363433", "#8f8a86", "#c8c3bc",
                "#ffffffff", "#fffcf3", "#165dac", "#104a8b", "#0000001a", "#00000014", "#00000033", "#645f5d",
                "#a4ccff", "#0000000a", "#00000014", "#00000066", "#f9ffee", "#43550d", "#dcf3ac", "#fffbf9",
                "#823301", "#ffe1d3", "#fffbfa", "#790407", "#fee0dc", "#edf4fe", "#07386d", "#daeafe",
                "#0c5752", "#07386d", "#652602", "#43550d", "#a04103", "#66800b", "#24837b", "#ad8301",
                "#24837b", "#5e409d", "#bc5215", "#6f6e69", "#6f6e69", "#6f6e69", "#040303"
            ),
            dark = listOf(
                "#0f0e0e", "#0c0a0a", "#181716", "#2d2a29", "#3f3b3a", "#5c5855", "#cbc9bf", "#201e1e",
                "#2585cf", "#032019", "#5c5855", "#ffffff0f", "#fbfaf9", "#6f6e69", "#93928b", "#0f0e0e",
                "#cbc9bf", "#bcddfd", "#d7ebfd", "#bcddfd", "#d7ebfd", "#aca9a1", "#837e79", "#3f3b3a",
                "#0c0a0a", "#cbc9bf", "#bcddfd", "#d7ebfd", "#ffffff1a", "#ffffff14", "#ffffff33", "#cbc9bf",
                "#9ecffc", "#ffffff0f", "#ffffff1a", "#00000099", "#181d02", "#bfd572", "#4d5a0f", "#2f1000",
                "#fbb793", "#873c01", "#310101", "#fab5ab", "#80110e", "#00172c", "#9ecffc", "#0b4874",
                "#94ece2", "#d7ebfd", "#fde1d3", "#e1f1b1", "#d8d3fc", "#879a39", "#3aa99f", "#d0a215",
                "#3aa99f", "#8b7ec8", "#da702c", "#6f6e69", "#6f6e69", "#6f6e69", "#fbfaf9"
            ),
        ),
        ThemeHex(
            id = "github",
            name = "GitHub",
            light = listOf(
                "#ffffff", "#fafaf9", "#f5f5f4", "#efefee", "#e7e7e6", "#dcdcdb", "#6f7675", "#989c9a",
                "#0f69d8", "#fffbfa", "#989c9a", "#ffffff", "#0e1319", "#57606a", "#55595f", "#ffffff",
                "#ffffff", "#0f69d8", "#1057b1", "#05336e", "#05336e", "#464d50", "#6f7675", "#cdcecc",
                "#464d50", "#ffffff", "#0f69d8", "#1057b1", "#0000001a", "#00000014", "#00000033", "#6f7675",
                "#a9cbfd", "#0000000a", "#00000014", "#00000066", "#f5fff6", "#105122", "#bafbc2", "#fffbf6",
                "#664407", "#fee3c0", "#fcfcfc", "#8f0617", "#fee0dd", "#edf4ff", "#09448f", "#dbe9fd",
                "#7d3308", "#09448f", "#4e3304", "#105122", "#06676d", "#cf222e", "#1b7c83", "#bc4c00",
                "#0969da", "#1b7c83", "#8250df", "#57606a", "#57606a", "#57606a", "#0e1319"
            ),
            dark = listOf(
                "#0a0e14", "#070b11", "#13171e", "#262b32", "#373d44", "#545a62", "#c5cdd5", "#1a1f26",
                "#5ea6f9", "#281602", "#545a62", "#ffffff0f", "#fafbfc", "#8b949e", "#8f959b", "#0a0e14",
                "#c5cdd5", "#c0dcfe", "#d9eafe", "#c0dcfe", "#d9eafe", "#a6adb5", "#7b828a", "#373d44",
                "#070b11", "#c5cdd5", "#c0dcfe", "#d9eafe", "#ffffff1a", "#ffffff14", "#ffffff33", "#c5cdd5",
                "#a3cdfe", "#ffffff0f", "#ffffff1a", "#00000099", "#032207", "#6ee87a", "#046b1e", "#241901",
                "#f3c14e", "#6e5411", "#390203", "#feb3a9", "#a21116", "#021b37", "#a3cdfe", "#13589e",
                "#ffd184", "#d9eafe", "#fde5b4", "#b2feb6", "#82edf5", "#ff7b72", "#39c5cf", "#d29922",
                "#39c5cf", "#58a6ff", "#bc8cff", "#8b949e", "#8b949e", "#8b949e", "#fafbfc"
            ),
        ),
        ThemeHex(
            id = "gruvbox",
            name = "Gruvbox",
            light = listOf(
                "#fcf5d7", "#f8f0d3", "#f4eccf", "#efe6cb", "#e8dfc5", "#e0d6bd", "#857a70", "#a79c8d",
                "#12697a", "#fffafe", "#a79c8d", "#fcf5d7", "#211d1b", "#4f4b49", "#6a6765", "#fcf5d7",
                "#fcf5d7", "#12697a", "#09515f", "#032932", "#032932", "#423e3b", "#857a70", "#c2b7a3",
                "#5f5752", "#fcf5d7", "#12697a", "#09515f", "#0000001a", "#00000014", "#00000033", "#857a70",
                "#83d8ed", "#0000000a", "#00000014", "#00000066", "#fefee4", "#4f4c0c", "#eeecae", "#fffbf7",
                "#805104", "#fde3c6", "#fffbfa", "#650304", "#fde0dc", "#e2f8fe", "#013d48", "#c1f1fe",
                "#660e4b", "#013d48", "#664107", "#4f4c0c", "#861410", "#9d0006", "#961f70", "#7a4f0e",
                "#605c10", "#8f3f71", "#076678", "#928374", "#928374", "#4f4b49", "#211d1b"
            ),
            dark = listOf(
                "#1f1e1d", "#1b1b1a", "#292825", "#3e3b35", "#514d43", "#706a59", "#e7d7af", "#312f2b",
                "#7ba797", "#350220", "#706a59", "#ffffff0f", "#fdfbf7", "#c7b892", "#a89c7c", "#1f1e1d",
                "#e7d7af", "#c3e1d5", "#d6eee5", "#c3e1d5", "#d6eee5", "#c6b997", "#999077", "#514d43",
                "#1b1b1a", "#e7d7af", "#c3e1d5", "#d6eee5", "#ffffff1a", "#ffffff14", "#ffffff33", "#e7d7af",
                "#aed2c4", "#ffffff0f", "#ffffff1a", "#00000099", "#1c1c03", "#ced241", "#5b5c04", "#241801",
                "#fcbd29", "#70530b", "#380401", "#fcb4a8", "#9d190b", "#052018", "#aed2c4", "#3b6154",
                "#fdc9d6", "#d6eee5", "#fde5b8", "#ebef9a", "#feccc2", "#fb4934", "#fed1dc", "#fdd995",
                "#e6ea0d", "#d3869b", "#83a598", "#928374", "#928374", "#c7b892", "#fdfbf7"
            ),
        ),
        ThemeHex(
            id = "kanagawa",
            name = "Kanagawa",
            light = listOf(
                "#f4ede3", "#f0e9df", "#ede5db", "#e8e0d6", "#e2dad0", "#dbd2c8", "#8f8177", "#ab9f95",
                "#326384", "#f8ffee", "#ab9f95", "#f4ede3", "#34231a", "#9e9389", "#7e7069", "#f4ede3",
                "#f4ede3", "#326384", "#164c6e", "#032235", "#032235", "#59483f", "#8f8177", "#c2b7ad",
                "#706056", "#f4ede3", "#326384", "#164c6e", "#0000001a", "#00000014", "#00000033", "#8f8177",
                "#a6cfed", "#0000000a", "#00000014", "#00000066", "#f7fff0", "#598015", "#d8f3ba", "#fffbf6",
                "#95680e", "#fee4bd", "#fcfcfc", "#a40d11", "#fee0dc", "#eaf5fe", "#073652", "#d3ecfe",
                "#476d36", "#073652", "#74500a", "#598015", "#cc5983", "#957fb8", "#76946a", "#c38d9d",
                "#98bb6c", "#d7a657", "#2d4f67", "#9e9389", "#9e9389", "#9e9389", "#34231a"
            ),
            dark = listOf(
                "#17161e", "#13131b", "#221e28", "#39313b", "#4f414a", "#715d61", "#dad3b6", "#2b2630",
                "#759be8", "#161e02", "#715d61", "#ffffff0f", "#fcfbf8", "#727169", "#9c9982", "#17161e",
                "#dad3b6", "#c7dafe", "#dde8fd", "#c7dafe", "#dde8fd", "#c2b09b", "#9b837c", "#4f414a",
                "#13131b", "#dad3b6", "#c7dafe", "#dde8fd", "#ffffff1a", "#ffffff14", "#ffffff33", "#dad3b6",
                "#afc9fd", "#ffffff0f", "#ffffff1a", "#00000099", "#131f02", "#b3d886", "#446403", "#251802",
                "#f2bf6f", "#735113", "#300102", "#feb3a9", "#850007", "#061641", "#afc9fd", "#375596",
                "#c7e3bc", "#dde8fd", "#fde4bf", "#d9f2bc", "#fdc9d8", "#957fb8", "#76946a", "#c38d9d",
                "#98bb6c", "#d7a657", "#7e9cd8", "#727169", "#727169", "#727169", "#fcfbf8"
            ),
        ),
        ThemeHex(
            id = "lucent-orng",
            name = "Lucent Orng",
            light = listOf(
                "#fef7f3", "#f8f1ed", "#f3ece8", "#ece6e2", "#e3deda", "#d8d3cf", "#696765", "#928f8c",
                "#f35317", "#f2ffff", "#928f8c", "#fef7f3", "#090909", "#8a8a8a", "#4d4d4d", "#fef7f3",
                "#fef7f3", "#f35317", "#cd4b1e", "#902e0b", "#902e0b", "#3e3e3d", "#696765", "#c8c4c0",
                "#ffffffff", "#fef7f3", "#f35317", "#cd4b1e", "#0000001a", "#00000014", "#00000033", "#696765",
                "#fbb6a0", "#0000000a", "#00000014", "#00000066", "#fafcff", "#033e87", "#dbe9fd", "#fffbf9",
                "#b23705", "#fee1d8", "#fffbfa", "#980e1e", "#fee0dd", "#fff0eb", "#b23705", "#fee1d8",
                "#095d68", "#b23705", "#902e0b", "#033e87", "#ac3c16", "#ec5b2b", "#318795", "#b0851f",
                "#0062d1", "#ec5b2b", "#c94d24", "#8a8a8a", "#8a8a8a", "#8a8a8a", "#090909"
            ),
            dark = listOf(
                "#21130e", "#1d0f0b", "#2b1e18", "#41342e", "#544943", "#726a64", "#eaeaea", "#342721",
                "#ef5824", "#02201f", "#726a64", "#ffffff0f", "#fdfdfd", "#808080", "#adadad", "#21130e",
                "#eaeaea", "#fccdbf", "#ffe0d7", "#fccdbf", "#ffe0d7", "#c8c7c4", "#726a64", "#544943",
                "#1d0f0b", "#eaeaea", "#fccdbf", "#ffe0d7", "#ffffff1a", "#ffffff14", "#ffffff33", "#eaeaea",
                "#feb49e", "#ffffff0f", "#ffffff1a", "#00000099", "#021a39", "#a7ccfc", "#1357a3", "#340a00",
                "#feb49e", "#8d3112", "#38020c", "#fdb2b5", "#a11634", "#340a00", "#feb49e", "#8d3112",
                "#99e9f3", "#ffe0d7", "#ffe0d7", "#dbe9fd", "#ded8d3", "#ec5b2b", "#56b6c2", "#e5c07b",
                "#6ba1e6", "#fff7f1", "#ee7948", "#808080", "#808080", "#808080", "#fdfdfd"
            ),
        ),
        ThemeHex(
            id = "material",
            name = "Material",
            light = listOf(
                "#fafafa", "#f5f5f5", "#f1f1f0", "#ebebea", "#e3e3e2", "#d9dad7", "#717a78", "#999e99",
                "#24aeb7", "#fffbfa", "#999e99", "#fafafa", "#0d191f", "#90a4ae", "#586166", "#fafafa",
                "#fafafa", "#24aeb7", "#0897a0", "#0f6469", "#0f6469", "#485456", "#717a78", "#cbccc9",
                "#485456", "#fafafa", "#24aeb7", "#0897a0", "#0000001a", "#00000014", "#00000033", "#717a78",
                "#65dee7", "#0000000a", "#00000014", "#00000066", "#f8fff0", "#598002", "#d6f4b1", "#fffbf6",
                "#946810", "#fee4bd", "#fcfcfc", "#aa0d15", "#fee0dc", "#d8fcfe", "#138087", "#aef7fc",
                "#b23811", "#138087", "#74510c", "#598002", "#0897a0", "#7c4dff", "#7c4dff", "#ffb300",
                "#91b859", "#f4511e", "#6182b8", "#90a4ae", "#90a4ae", "#90a4ae", "#0d191f"
            ),
            dark = listOf(
                "#1a252a", "#162126", "#243035", "#3a474c", "#4e5b60", "#6e7c80", "#eafbfb", "#2d393e",
                "#3ab4de", "#1b1b1b", "#6e7c80", "#ffffff0f", "#fdffff", "#546e7a", "#b1bdbd", "#1a252a",
                "#eafbfb", "#a5e4fe", "#cbeefe", "#a5e4fe", "#cbeefe", "#c7d8d9", "#6e7c80", "#4e5b60",
                "#162126", "#eafbfb", "#a5e4fe", "#cbeefe", "#ffffff1a", "#ffffff14", "#ffffff33", "#eafbfb",
                "#80d6f9", "#ffffff0f", "#ffffff1a", "#00000099", "#141f02", "#b3d87c", "#466300", "#251801",
                "#f4c05d", "#71520e", "#38030b", "#fcb3b3", "#a01b31", "#021e29", "#80d6f9", "#14617a",
                "#fed282", "#cbeefe", "#fee4b8", "#d9f3b6", "#a5e4fe", "#c792ea", "#c792ea", "#ffcb6b",
                "#c3e88d", "#ffcb6b", "#82aaff", "#546e7a", "#546e7a", "#546e7a", "#fdffff"
            ),
        ),
        ThemeHex(
            id = "matrix",
            name = "Matrix",
            light = listOf(
                "#f0f5ed", "#ebf0e8", "#e6ece3", "#e0e6dd", "#d9dfd6", "#cfd5cc", "#6a7669", "#909a8d",
                "#25c14d", "#f8fdff", "#909a8d", "#f0f5ed", "#353535", "#748476", "#748476", "#f0f5ed",
                "#f0f5ed", "#25c14d", "#07a93d", "#0c5e8b", "#0c5e8b", "#435143", "#6a7669", "#c1c8be",
                "#435143", "#f0f5ed", "#25c14d", "#07a93d", "#0000001a", "#00000014", "#00000033", "#6a7669",
                "#48ed6b", "#0000000a", "#00000014", "#00000066", "#f5fff6", "#048930", "#b2feb9", "#faffe9",
                "#6d7a12", "#e1f488", "#fcfcfc", "#ca0821", "#ffdfdc", "#e9f5ff", "#0f79b1", "#d4ecfd",
                "#0f79b1", "#0f79b1", "#545f0d", "#048930", "#bb3fff", "#c770ff", "#24f6d9", "#e6ff57",
                "#1cc24b", "#ffa83d", "#30b3ff", "#748476", "#748476", "#748476", "#08180c"
            ),
            dark = listOf(
                "#070e07", "#050a05", "#0c190d", "#18311a", "#224727", "#316b3b", "#61fa91", "#112212",
                "#25c364", "#001e2a", "#316b3b", "#ffffff0f", "#ececec", "#8ca391", "#8ca391", "#070e07",
                "#61fa91", "#79f89d", "#b2fdc2", "#b8dffc", "#d3ecfe", "#55d278", "#449c58", "#224727",
                "#050a05", "#61fa91", "#79f89d", "#b2fdc2", "#ffffff1a", "#ffffff14", "#ffffff33", "#61fa91",
                "#45ec80", "#ffffff0f", "#ffffff1a", "#00000099", "#03210c", "#45ec80", "#016a31", "#191d02",
                "#c0d809", "#545f00", "#380305", "#fcb4ad", "#a70118", "#021d2e", "#97d1fa", "#155e88",
                "#b8dffc", "#d3ecfe", "#e1f48e", "#b2fdc2", "#e8cdfd", "#c770ff", "#24f6d9", "#e6ff57",
                "#1cc24b", "#ffa83d", "#30b3ff", "#8ca391", "#8ca391", "#8ca391", "#f1fff5"
            ),
        ),
        ThemeHex(
            id = "mercury",
            name = "Mercury",
            light = listOf(
                "#ffffff", "#fafafa", "#f6f6f5", "#f1f0ef", "#eae8e7", "#e1dfdd", "#877c80", "#a9a0a0",
                "#3f4cf3", "#f4feff", "#a9a0a0", "#ffffff", "#1b1b29", "#70707d", "#646570", "#ffffff",
                "#ffffff", "#3f4cf3", "#312ae1", "#1a128b", "#1a128b", "#3d3c49", "#877c80", "#d4d1cf",
                "#5e5661", "#ffffff", "#3f4cf3", "#312ae1", "#0000001a", "#00000014", "#00000033", "#877c80",
                "#b6c7fb", "#0000000a", "#00000014", "#00000066", "#f5fff8", "#0b4128", "#bcf9d4", "#fffbf9",
                "#682801", "#fee1d5", "#fffafc", "#700c3a", "#ffdee7", "#eff3fe", "#2606c2", "#dfe7ff",
                "#095362", "#2606c2", "#4a1d05", "#0b4128", "#6882f2", "#465bd1", "#5266eb", "#007f95",
                "#036e43", "#a44200", "#5266eb", "#70707d", "#70707d", "#70707d", "#1b1b29"
            ),
            dark = listOf(
                "#11111b", "#0e0e17", "#1b1b25", "#30303a", "#43434d", "#61616b", "#d9d9e1", "#23232d",
                "#859efb", "#021f22", "#61616b", "#ffffff0f", "#fcfcfc", "#9d9da8", "#9e9ea3", "#11111b",
                "#d9d9e1", "#cbd8ff", "#e0e7fe", "#cbd8ff", "#e0e7fe", "#b8b8c0", "#8a8b94", "#43434d",
                "#0e0e17", "#d9d9e1", "#cbd8ff", "#e0e7fe", "#ffffff1a", "#ffffff14", "#ffffff33", "#d9d9e1",
                "#b5c7fe", "#ffffff0f", "#ffffff1a", "#00000099", "#032112", "#8ddeb0", "#016940", "#310f01",
                "#ffb594", "#8e3c0c", "#370219", "#ffaec6", "#982354", "#0c104a", "#b5c7fe", "#3c4ea6",
                "#a8e5f4", "#e0e7fe", "#fde1d5", "#c2f6d7", "#cbd8ff", "#8da4f5", "#a7b6f8", "#77becf",
                "#77c599", "#fc9b6f", "#8da4f5", "#9d9da8", "#9d9da8", "#9d9da8", "#fcfcfc"
            ),
        ),
        ThemeHex(
            id = "monokai",
            name = "Monokai",
            light = listOf(
                "#fdf9ef", "#f8f4ea", "#f3efe5", "#ede9de", "#e5e0d6", "#dad6cc", "#746e63", "#9a958a",
                "#be7cfd", "#f8fdff", "#9a958a", "#fdf9ef", "#140e04", "#3f392f", "#59544b", "#fdf9ef",
                "#fdf9ef", "#be7cfd", "#b152fe", "#771cb4", "#771cb4", "#4c463b", "#746e63", "#ccc8bd",
                "#ffffffff", "#fdf9ef", "#be7cfd", "#b152fe", "#0000001a", "#00000014", "#00000033", "#746e63",
                "#d9b9fd", "#0000000a", "#00000014", "#00000066", "#f6fff5", "#1e881d", "#b7feb1", "#fffbf7",
                "#996516", "#ffe3c2", "#fffbfa", "#b21523", "#ffe0dd", "#f7f0ff", "#9825e6", "#eee2fd",
                "#1c6e9c", "#9825e6", "#784e10", "#1e881d", "#c91666", "#d9487c", "#1f88c8", "#795011",
                "#8a6500", "#9b5fe0", "#3c8d2f", "#8a816f", "#8a816f", "#3f392f", "#140e04"
            ),
            dark = listOf(
                "#1d1e19", "#191a15", "#282923", "#3e3f3a", "#53544e", "#74756f", "#f4f4ee", "#31322c",
                "#ad84f9", "#002022", "#74756f", "#ffffff0f", "#fefefe", "#d4d4ce", "#b7b7b2", "#1d1e19",
                "#f4f4ee", "#ddd1fd", "#eae3fd", "#ddd1fd", "#eae3fd", "#d0d0ca", "#74756f", "#53544e",
                "#191a15", "#f4f4ee", "#ddd1fd", "#eae3fd", "#ffffff1a", "#ffffff14", "#ffffff33", "#f4f4ee",
                "#d0bdfc", "#ffffff0f", "#ffffff1a", "#00000099", "#131f00", "#a3e021", "#466307", "#2a1502",
                "#feb87a", "#7d4a14", "#370314", "#fab2bf", "#940a3f", "#240148", "#d0bdfc", "#6b26bc",
                "#8ceafd", "#eae3fd", "#fee2ca", "#cef991", "#fccad2", "#f92672", "#66d9ef", "#fcd7b7",
                "#e6db74", "#ae81ff", "#a6e22e", "#75715e", "#75715e", "#d4d4ce", "#fefefe"
            ),
        ),
        ThemeHex(
            id = "nightowl",
            name = "Night Owl",
            light = listOf(
                "#f1f1f1", "#ededec", "#e9e9e8", "#e5e4e2", "#dfdddb", "#d8d5d2", "#8c7c83", "#aa9d9d",
                "#336ff4", "#fafcff", "#aa9d9d", "#f1f1f1", "#232234", "#515063", "#6d6d7c", "#f1f1f1",
                "#f1f1f1", "#336ff4", "#1b57e1", "#07329a", "#07329a", "#474457", "#8c7c83", "#bfb7b4",
                "#665b6a", "#f1f1f1", "#336ff4", "#1b57e1", "#0000001a", "#00000014", "#00000033", "#8c7c83",
                "#aec9fe", "#0000000a", "#00000014", "#00000066", "#f3fffd", "#19746c", "#aff8f0", "#fffbfa",
                "#ab2630", "#ffdfdd", "#fffbfa", "#a4151c", "#fde0dd", "#eef4ff", "#0941c4", "#dde8fe",
                "#0941c4", "#0941c4", "#8e1421", "#19746c", "#890e68", "#994cc3", "#0d48d0", "#a61627",
                "#136761", "#c96765", "#4876d6", "#7a8181", "#7a8181", "#515063", "#232234"
            ),
            dark = listOf(
                "#04101c", "#020d18", "#0d1a26", "#232f3c", "#36424f", "#56616f", "#d2dae7", "#16232f",
                "#79a2fa", "#051b33", "#56616f", "#ffffff0f", "#fbfcfd", "#b4bac7", "#989ea9", "#04101c",
                "#d2dae7", "#c7dafe", "#dee8fd", "#c7dafe", "#dee8fd", "#afb8c6", "#808b98", "#36424f",
                "#020d18", "#d2dae7", "#c7dafe", "#dee8fd", "#ffffff1a", "#ffffff14", "#ffffff33", "#d2dae7",
                "#afc9fd", "#ffffff0f", "#ffffff1a", "#00000099", "#161e00", "#b9d869", "#4e6109", "#261701",
                "#eac189", "#755010", "#380405", "#fbb4ad", "#9b161e", "#01104f", "#afc9fd", "#2a50ad",
                "#c7dafe", "#dee8fd", "#ffe3be", "#dcf3ac", "#fccdbf", "#c792ea", "#d0dffe", "#fcce8f",
                "#ecc48d", "#f78c6c", "#82aaff", "#637777", "#637777", "#b4bac7", "#fbfcfd"
            ),
        ),
        ThemeHex(
            id = "nord",
            name = "Nord",
            light = listOf(
                "#eef1f5", "#e9ecf1", "#e5e8ed", "#dfe3e8", "#d8dce1", "#cfd3d8", "#727882", "#949aa2",
                "#5181bb", "#f8fcff", "#949aa2", "#eef1f5", "#141a26", "#414753", "#5e636d", "#eef1f5",
                "#eef1f5", "#5181bb", "#3a6ca7", "#194475", "#194475", "#343a46", "#727882", "#c2c6cc",
                "#4e545f", "#eef1f5", "#5181bb", "#3a6ca7", "#0000001a", "#00000014", "#00000033", "#727882",
                "#a4ccfe", "#0000000a", "#00000014", "#00000066", "#f2ffff", "#3f7d7d", "#cff0ef", "#fffbf9",
                "#af5234", "#ffe0d7", "#fffafb", "#a0203b", "#fedfe0", "#ecf4ff", "#29588f", "#d9eafe",
                "#4b759e", "#29588f", "#8e3b20", "#3f7d7d", "#bb354c", "#5e81ac", "#325d85", "#953716",
                "#6f8758", "#8d6886", "#5e81ac", "#6b7282", "#6b7282", "#414753", "#141a26"
            ),
            dark = listOf(
                "#1f2530", "#1c212c", "#292f3a", "#3e434e", "#505661", "#6e737e", "#e1e5ec", "#313742",
                "#67b1c6", "#001d2d", "#6e737e", "#ffffff0f", "#fcfdfe", "#c2c5cb", "#a6a9ae", "#1f2530",
                "#e1e5ec", "#b2e2f1", "#c9effb", "#b2e2f1", "#c9effb", "#c1c6ce", "#6e737e", "#505661",
                "#1c212c", "#e1e5ec", "#b2e2f1", "#c9effb", "#ffffff1a", "#ffffff14", "#ffffff33", "#e1e5ec",
                "#9ad4e4", "#ffffff0f", "#ffffff1a", "#00000099", "#102000", "#b8d4a0", "#46622a", "#340b01",
                "#fcb59f", "#8b3d24", "#32020b", "#feb1b6", "#82142c", "#031f26", "#9ad4e4", "#196273",
                "#c0ddfa", "#c9effb", "#fee1d7", "#dcefcc", "#fecacd", "#81a1c1", "#c7e2fe", "#ffd4c6",
                "#b9f481", "#b48ead", "#88c0d0", "#616e88", "#616e88", "#c2c5cb", "#fcfdfe"
            ),
        ),
        ThemeHex(
            id = "one-dark",
            name = "One Dark",
            light = listOf(
                "#fafafa", "#f5f5f5", "#f1f1f1", "#ececeb", "#e5e4e3", "#dcdbda", "#837d81", "#a5a0a0",
                "#3976fc", "#fffbf7", "#a5a0a0", "#fafafa", "#1d1f27", "#a0a1a7", "#67696f", "#fafafa",
                "#fafafa", "#3976fc", "#1558f6", "#0334ab", "#0334ab", "#3f4048", "#837d81", "#bfbcbb",
                "#5d5960", "#fafafa", "#3976fc", "#1558f6", "#0000001a", "#00000014", "#00000033", "#837d81",
                "#aec9fe", "#0000000a", "#00000014", "#00000066", "#f6fff5", "#14771a", "#c0fabd", "#fffbf6",
                "#886019", "#ffe3bd", "#fffbfa", "#b81312", "#fde0dc", "#eef4ff", "#0342d7", "#dde8fe",
                "#644408", "#0342d7", "#714d07", "#14771a", "#136e9b", "#a626a4", "#0184bc", "#c18401",
                "#50a14f", "#986801", "#4078f2", "#a0a1a7", "#a0a1a7", "#a0a1a7", "#1d1f27"
            ),
            dark = listOf(
                "#1b1e25", "#181b22", "#22262d", "#31353e", "#3f434c", "#555a64", "#a8afbc", "#282c34",
                "#3eacff", "#2e1101", "#555a64", "#ffffff0f", "#f8f9fa", "#5c6370", "#7e838d", "#1b1e25",
                "#a8afbc", "#bcdefc", "#d5ebff", "#bcdefc", "#d5ebff", "#555a64", "#727782", "#3f434c",
                "#181b22", "#a8afbc", "#bcdefc", "#d5ebff", "#ffffff1a", "#ffffff14", "#ffffff33", "#a8afbc",
                "#9dcffb", "#ffffff0f", "#ffffff1a", "#00000099", "#0f2002", "#acd98c", "#3c6514", "#241803",
                "#e9c37c", "#715205", "#38020c", "#fdb2b5", "#a11634", "#021c31", "#9dcffb", "#015c94",
                "#fcd0a7", "#d5ebff", "#fee4b6", "#d5f3c0", "#99e9f3", "#c678dd", "#56b6c2", "#e5c07b",
                "#98c379", "#d19a66", "#61afef", "#5c6370", "#5c6370", "#5c6370", "#f8f9fa"
            ),
        ),
        ThemeHex(
            id = "onedarkpro",
            name = "One Dark Pro",
            light = listOf(
                "#f6f7f9", "#f1f2f4", "#eceef0", "#e7e8eb", "#dfe0e4", "#d5d7db", "#737780", "#979ba2",
                "#538bfd", "#f8fcff", "#979ba2", "#f6f7f9", "#131722", "#3f444f", "#5b6069", "#f6f7f9",
                "#f6f7f9", "#538bfd", "#3371f5", "#0a44c2", "#0a44c2", "#4d515b", "#737780", "#c7cace",
                "#4d515b", "#f6f7f9", "#538bfd", "#3371f5", "#0000001a", "#00000014", "#00000033", "#737780",
                "#afcafb", "#0000000a", "#00000014", "#00000066", "#f5fff7", "#077c41", "#bcf9cd", "#fffbf7",
                "#9f6118", "#fee2ca", "#fffbfb", "#c32041", "#fde0e0", "#eef4fe", "#0753f1", "#dce8ff",
                "#1577b9", "#0753f1", "#7c4b12", "#077c41", "#d01943", "#a626a4", "#105e93", "#7e4c13",
                "#176a3a", "#986801", "#4078f2", "#6a717d", "#6a717d", "#3f444f", "#131722"
            ),
            dark = listOf(
                "#14181f", "#11151c", "#1b2028", "#2b3038", "#393e48", "#505660", "#a8afbc", "#22262e",
                "#3eacff", "#02201f", "#505660", "#ffffff0f", "#f8f9fa", "#989eab", "#7e838d", "#14181f",
                "#a8afbc", "#bcdefc", "#d5ebff", "#bcdefc", "#d5ebff", "#a8afbc", "#6f7580", "#393e48",
                "#11151c", "#a8afbc", "#bcdefc", "#d5ebff", "#ffffff1a", "#ffffff14", "#ffffff33", "#a8afbc",
                "#9dcffb", "#ffffff0f", "#ffffff1a", "#00000099", "#0f2002", "#acd98c", "#3c6514", "#241803",
                "#e9c37c", "#715205", "#38020c", "#fdb2b5", "#a11634", "#021c31", "#9dcffb", "#015c94",
                "#99e9f3", "#d5ebff", "#fee4b6", "#d5f3c0", "#fecacb", "#c678dd", "#91f1fd", "#ffd993",
                "#9efd3e", "#d19a66", "#61afef", "#5c6370", "#5c6370", "#989eab", "#f8f9fa"
            ),
        ),
        ThemeHex(
            id = "orng",
            name = "Orng",
            light = listOf(
                "#ffffff", "#f9f9f9", "#f4f4f4", "#ededed", "#e4e4e4", "#d9d9d9", "#6a6a6a", "#939393",
                "#f35317", "#f2ffff", "#939393", "#ffffff", "#090909", "#8a8a8a", "#4d4d4d", "#ffffff",
                "#ffffff", "#f35317", "#cd4b1e", "#902e0b", "#902e0b", "#3f3f3f", "#939393", "#c9c9c9",
                "#ffffffff", "#ffffff", "#f35317", "#cd4b1e", "#0000001a", "#00000014", "#00000033", "#6a6a6a",
                "#fbb6a0", "#0000000a", "#00000014", "#00000066", "#fafcff", "#033e87", "#dbe9fd", "#fffbf9",
                "#b23705", "#fee1d8", "#fffbfa", "#980e1e", "#fee0dd", "#fff0eb", "#b23705", "#fee1d8",
                "#095d68", "#b23705", "#902e0b", "#033e87", "#ac3c16", "#ec5b2b", "#318795", "#b0851f",
                "#0062d1", "#ec5b2b", "#c94d24", "#8a8a8a", "#8a8a8a", "#8a8a8a", "#090909"
            ),
            dark = listOf(
                "#0b0b0b", "#070707", "#151515", "#2c2c2c", "#414141", "#636363", "#eaeaea", "#1e1e1e",
                "#ef5824", "#02201f", "#636363", "#ffffff0f", "#fdfdfd", "#808080", "#adadad", "#0b0b0b",
                "#eaeaea", "#fccdbf", "#ffe0d7", "#fccdbf", "#ffe0d7", "#c4c4c4", "#636363", "#414141",
                "#070707", "#eaeaea", "#fccdbf", "#ffe0d7", "#ffffff1a", "#ffffff14", "#ffffff33", "#eaeaea",
                "#feb49e", "#ffffff0f", "#ffffff1a", "#00000099", "#021a39", "#a7ccfc", "#1357a3", "#340a00",
                "#feb49e", "#8d3112", "#38020c", "#fdb2b5", "#a11634", "#340a00", "#feb49e", "#8d3112",
                "#99e9f3", "#ffe0d7", "#ffe0d7", "#dbe9fd", "#ded8d3", "#ec5b2b", "#56b6c2", "#e5c07b",
                "#6ba1e6", "#fff7f1", "#ee7948", "#808080", "#808080", "#808080", "#fdfdfd"
            ),
        ),
        ThemeHex(
            id = "osaka-jade",
            name = "Osaka Jade",
            light = listOf(
                "#f8f7e4", "#f2f2df", "#ededda", "#e6e6d4", "#dcdecc", "#d1d3c2", "#5f695f", "#889183",
                "#26aa90", "#f4fff9", "#889183", "#f8f7e4", "#020b07", "#53685b", "#454e4b", "#f8f7e4",
                "#f8f7e4", "#26aa90", "#0c937b", "#156454", "#156454", "#354039", "#5f695f", "#c1c5b4",
                "#ffffffff", "#f8f7e4", "#26aa90", "#0c937b", "#0000001a", "#00000014", "#00000033", "#5f695f",
                "#5ce4c6", "#0000000a", "#00000014", "#00000066", "#f5fff7", "#02532a", "#c8f5d4", "#fffcee",
                "#827210", "#f8ea9c", "#fffbfa", "#8f110b", "#fde0db", "#d3fff2", "#127b67", "#aafbe6",
                "#127b67", "#127b67", "#65580c", "#02532a", "#076937", "#1faa90", "#3d7a52", "#3d7a52",
                "#3d7a52", "#a8527a", "#3d7560", "#53685b", "#53685b", "#53685b", "#020b07"
            ),
            dark = listOf(
                "#0a1611", "#08130e", "#121f18", "#223128", "#324235", "#4c5d4a", "#bdc195", "#18261e",
                "#57b7ae", "#032115", "#4c5d4a", "#ffffff0f", "#fafaf5", "#53685b", "#8c8e6b", "#0a1611",
                "#bdc195", "#abe7e0", "#c4f3ed", "#abe7e0", "#c4f3ed", "#bdc195", "#718064", "#324235",
                "#08130e", "#bdc195", "#abe7e0", "#c4f3ed", "#ffffff1a", "#ffffff14", "#ffffff33", "#bdc195",
                "#90d9d1", "#ffffff0f", "#ffffff1a", "#00000099", "#01220d", "#91dea5", "#125e31", "#201a01",
                "#e7c831", "#66580f", "#3a0101", "#fbb5aa", "#a60e0d", "#01201d", "#90d9d1", "#0e665f",
                "#78f3d7", "#c4f3ed", "#fce994", "#c4f7d0", "#abebbb", "#2dd5b7", "#549e6a", "#549e6a",
                "#63b07a", "#d2689c", "#509475", "#53685b", "#53685b", "#53685b", "#fafaf5"
            ),
        ),
        ThemeHex(
            id = "palenight",
            name = "Palenight",
            light = listOf(
                "#fafafa", "#f5f5f4", "#f1f0ef", "#ebeae9", "#e4e3e1", "#dbd8d6", "#7f727a", "#a39899",
                "#4474f4", "#fffbfa", "#a39899", "#fafafa", "#121526", "#8796b0", "#5a5e6c", "#fafafa",
                "#fafafa", "#4474f4", "#2150fc", "#0b24ba", "#0b24ba", "#544d5b", "#7f727a", "#cfcac7",
                "#544d5b", "#fafafa", "#4474f4", "#2150fc", "#0000001a", "#00000014", "#00000033", "#7f727a",
                "#b1c9fc", "#0000000a", "#00000014", "#00000066", "#f8fff0", "#598002", "#d6f4b1", "#fffbf6",
                "#946810", "#fee4bd", "#fcfcfc", "#aa0d15", "#fee0dc", "#eef4fe", "#1439dd", "#dee8fd",
                "#b23811", "#1439dd", "#74510c", "#598002", "#2293a4", "#a854f2", "#00acc1", "#ffb300",
                "#91b859", "#f4511e", "#4976eb", "#8796b0", "#8796b0", "#8796b0", "#121526"
            ),
            dark = listOf(
                "#1b1e2f", "#181b2c", "#222537", "#303448", "#3e4257", "#53576f", "#a3a9ca", "#282b3e",
                "#79a2fa", "#2f0f0d", "#53576f", "#ffffff0f", "#f8f8fc", "#676e95", "#7b7f98", "#1b1e2f",
                "#a3a9ca", "#c7dafe", "#dee8fd", "#c7dafe", "#dee8fd", "#53576f", "#6f748f", "#3e4257",
                "#181b2c", "#a3a9ca", "#c7dafe", "#dee8fd", "#ffffff1a", "#ffffff14", "#ffffff33", "#a3a9ca",
                "#afc9fd", "#ffffff0f", "#ffffff1a", "#00000099", "#141f02", "#b3d87c", "#466300", "#251801",
                "#f4c05d", "#71520e", "#38030b", "#fcb3b3", "#a01b31", "#01104f", "#afc9fd", "#2a50ad",
                "#fccdbf", "#dee8fd", "#fee4b8", "#d9f3b6", "#a5e4fe", "#c792ea", "#89ddff", "#ffcb6b",
                "#c3e88d", "#f78c6c", "#82aaff", "#676e95", "#676e95", "#676e95", "#f8f8fc"
            ),
        ),
        ThemeHex(
            id = "rosepine",
            name = "Rose Pine",
            light = listOf(
                "#fbf5ef", "#f8f1eb", "#f5ede7", "#f2e9e2", "#eee2db", "#e8dad3", "#a68899", "#c2a6aa",
                "#177596", "#f2ffff", "#c2a6aa", "#fbf5ef", "#332d53", "#9893a5", "#807d9c", "#fbf5ef",
                "#fbf5ef", "#177596", "#15607b", "#063849", "#063849", "#d5bfbc", "#806a89", "#d5bfbc",
                "#806a89", "#fbf5ef", "#177596", "#15607b", "#0000001a", "#00000014", "#00000033", "#a68899",
                "#8cd4f4", "#0000000a", "#00000014", "#00000066", "#f8fdff", "#014156", "#cdeefd", "#fffbf7",
                "#9c630b", "#fee3c5", "#fffafb", "#942a50", "#fedee5", "#e6f7ff", "#0c4b62", "#cceefe",
                "#126c79", "#0c4b62", "#7a4d08", "#014156", "#d35d5c", "#286983", "#d7827e", "#56949f",
                "#ea9d34", "#907aa9", "#d7827e", "#9893a5", "#9893a5", "#9893a5", "#332d53"
            ),
            dark = listOf(
                "#12101d", "#0f0d19", "#1c1a28", "#312f3e", "#444252", "#636173", "#dcdaf0", "#242230",
                "#6eb1bc", "#021f20", "#636173", "#ffffff0f", "#fcfcfe", "#6e6a86", "#a09fb0", "#12101d",
                "#dcdaf0", "#b6e2ea", "#cdeff5", "#b6e2ea", "#cdeff5", "#bab8cd", "#8d8b9e", "#444252",
                "#0f0d19", "#dcdaf0", "#b6e2ea", "#cdeff5", "#ffffff1a", "#ffffff14", "#ffffff33", "#dcdaf0",
                "#9fd3dd", "#ffffff0f", "#ffffff1a", "#00000099", "#00080e", "#90d4f2", "#01384b", "#271700",
                "#f4be72", "#774f06", "#360316", "#feafc1", "#a3014c", "#031f23", "#9fd3dd", "#24626c",
                "#b6e2ea", "#cdeff5", "#fee3c0", "#ceeefd", "#f7cecc", "#31748f", "#ebbcba", "#9ccfd8",
                "#f6c177", "#c4a7e7", "#ebbcba", "#6e6a86", "#6e6a86", "#6e6a86", "#fcfcfe"
            ),
        ),
        ThemeHex(
            id = "shadesofpurple",
            name = "Shades of Purple",
            light = listOf(
                "#f8effe", "#f4eafa", "#f0e6f6", "#eae0f2", "#e4d9ec", "#dbd0e4", "#817296", "#a395b3",
                "#7a5bf6", "#f7fdff", "#a395b3", "#f8effe", "#210f3d", "#4e406b", "#695e82", "#f8effe",
                "#f8effe", "#7a5bf6", "#6c1efe", "#4414a5", "#4414a5", "#41325e", "#817296", "#cec3d9",
                "#5c4d76", "#f8effe", "#7a5bf6", "#6c1efe", "#0000001a", "#00000014", "#00000033", "#817296",
                "#c4c1fd", "#0000000a", "#00000014", "#00000066", "#f4fff9", "#20855d", "#abfdd3", "#fffcf3",
                "#8b6d11", "#fde6ac", "#fffafd", "#c0109a", "#fdddf1", "#f2f2ff", "#5718d1", "#e6e5fe",
                "#217c9c", "#5718d1", "#6c550c", "#20855d", "#eb16be", "#c45f00", "#008fb8", "#9d7a00",
                "#2f8b32", "#e04d7a", "#a13bd6", "#8e4be3", "#8e4be3", "#4e406b", "#210f3d"
            ),
            dark = listOf(
                "#150b24", "#110820", "#1f1730", "#362e47", "#4b435c", "#6d657d", "#f1ecfb", "#282039",
                "#bd86f7", "#0e1d22", "#6d657d", "#ffffff0f", "#fefeff", "#d1ccd8", "#b4b0ba", "#150b24",
                "#f1ecfb", "#e3cffd", "#eee2fd", "#e3cffd", "#eee2fd", "#ccc6d8", "#6d657d", "#4b435c",
                "#110820", "#f1ecfb", "#e3cffd", "#eee2fd", "#ffffff1a", "#ffffff14", "#ffffff33", "#f1ecfb",
                "#d8b9fc", "#ffffff0f", "#ffffff1a", "#00000099", "#032114", "#7ae1b0", "#186748", "#241801",
                "#edc26c", "#6f530f", "#340322", "#feacd7", "#9c026c", "#280043", "#d8b9fc", "#722da9",
                "#aee2fd", "#eee2fd", "#fee5b3", "#b8f9d7", "#fcc8e2", "#ff9d00", "#9effff", "#fad000",
                "#a5ff90", "#ff628c", "#fb94ff", "#b362ff", "#b362ff", "#d1ccd8", "#fefeff"
            ),
        ),
        ThemeHex(
            id = "solarized",
            name = "Solarized",
            light = listOf(
                "#fdf8e8", "#f9f5e5", "#f5f2e2", "#f1eede", "#eaead9", "#e2e4d3", "#90a49c", "#aebdaf",
                "#0c8bd9", "#f3fffb", "#aebdaf", "#fdf8e8", "#2e444b", "#61767d", "#829398", "#fdf8e8",
                "#fdf8e8", "#0c8bd9", "#1676b5", "#114d77", "#114d77", "#c7cfbf", "#718888", "#c7cfbf",
                "#718888", "#fdf8e8", "#0c8bd9", "#1676b5", "#0000001a", "#00000014", "#00000033", "#90a49c",
                "#98d0ff", "#0000000a", "#00000014", "#00000066", "#f9ffe9", "#5f6c17", "#e0f29f", "#fffbf4",
                "#806318", "#fde5b3", "#fcfcfc", "#9b1a1a", "#fee0dc", "#ebf5fe", "#136196", "#d5ebfe",
                "#19736c", "#136196", "#6a4f06", "#5f6c17", "#bc0e6e", "#728600", "#268bd2", "#70560f",
                "#1f8f88", "#d33682", "#268bd2", "#657b83", "#657b83", "#61767d", "#2e444b"
            ),
            dark = listOf(
                "#041d24", "#021a22", "#0c242b", "#1b3238", "#293f44", "#3e5357", "#909f9f", "#122930",
                "#696cd8", "#02201a", "#3e5357", "#ffffff0f", "#f6f8f8", "#889595", "#6f7a7a", "#041d24",
                "#909f9f", "#d1d6fc", "#e3e6ff", "#d1d6fc", "#e3e6ff", "#3e5357", "#7a8a8b", "#3e5357",
                "#021a22", "#909f9f", "#d1d6fc", "#e3e6ff", "#ffffff1a", "#ffffff14", "#ffffff33", "#909f9f",
                "#bec4fa", "#ffffff0f", "#ffffff1a", "#00000099", "#181e00", "#bfd657", "#4c580e", "#231902",
                "#f1c254", "#69500f", "#2b0202", "#ffb3a9", "#7c0d0f", "#0e0041", "#bec4fa", "#342f8c",
                "#92ece3", "#e3e6ff", "#fee5b0", "#e1f2a3", "#ffc7db", "#859900", "#268bd2", "#ffda87",
                "#2aa198", "#d33682", "#268bd2", "#586e75", "#586e75", "#889595", "#f6f8f8"
            ),
        ),
        ThemeHex(
            id = "synthwave84",
            name = "Synthwave '84",
            light = listOf(
                "#fafafa", "#f5f5f4", "#f0f0ef", "#ebeae8", "#e3e1df", "#dad7d4", "#7b6c72", "#a09493",
                "#09b9d0", "#fffbfa", "#a09493", "#fafafa", "#110e1f", "#5c5c8a", "#575564", "#fafafa",
                "#fafafa", "#09b9d0", "#1e9db0", "#116370", "#116370", "#504452", "#7b6c72", "#cdc8c5",
                "#ffffffff", "#fafafa", "#09b9d0", "#1e9db0", "#0000001a", "#00000014", "#00000033", "#7b6c72",
                "#34e0fa", "#0000000a", "#00000014", "#00000066", "#f5fff5", "#1a8325", "#b8fdb7", "#fffbf7",
                "#9e611c", "#fde2cb", "#fcfcfc", "#b81c16", "#ffe0db", "#dff9fe", "#167f8f", "#bbf3fe",
                "#bc3e16", "#167f8f", "#7f4902", "#1a8325", "#821494", "#e91e63", "#9c27b0", "#00bcd4",
                "#ff9800", "#9c27b0", "#ff5722", "#5c5c8a", "#5c5c8a", "#5c5c8a", "#110e1f"
            ),
            dark = listOf(
                "#1e1929", "#191525", "#2b2332", "#463a46", "#5d4f57", "#7f7275", "#fbfbfa", "#352c3a",
                "#12bcba", "#300f04", "#7f7275", "#ffffff0f", "#ffffff", "#848bbd", "#bebebe", "#1e1929",
                "#fbfbfa", "#61f3f0", "#94fdfa", "#61f3f0", "#94fdfa", "#d8d5d2", "#7f7275", "#5d4f57",
                "#191525", "#fbfbfa", "#61f3f0", "#94fdfa", "#ffffff1a", "#ffffff14", "#ffffff33", "#fbfbfa",
                "#38e4e1", "#ffffff0f", "#ffffff1a", "#00000099", "#002114", "#62e6ac", "#0a6847", "#211a02",
                "#e8c73e", "#685702", "#390207", "#fdb3af", "#a01425", "#02201f", "#38e4e1", "#136563",
                "#fbceb4", "#94fdfa", "#fde898", "#adfcd5", "#e1d0fc", "#ff7edb", "#b084eb", "#36f9f6",
                "#fede5d", "#b084eb", "#ff8b39", "#848bbd", "#848bbd", "#848bbd", "#ffffff"
            ),
        ),
        ThemeHex(
            id = "tokyonight",
            name = "Tokyonight",
            light = listOf(
                "#e5e6ea", "#e0e2e6", "#dcdee3", "#d7d8df", "#d0d2da", "#c7c9d3", "#6b738c", "#8d93a7",
                "#217bf2", "#f7fdff", "#8d93a7", "#e5e6ea", "#0f1638", "#3b4566", "#59617d", "#e5e6ea",
                "#e5e6ea", "#217bf2", "#0d66d4", "#09428a", "#09428a", "#2d3758", "#6b738c", "#a8acbb",
                "#47506e", "#e5e6ea", "#217bf2", "#0d66d4", "#0000001a", "#00000014", "#00000033", "#6b738c",
                "#abcbfa", "#0000000a", "#00000014", "#00000066", "#f7fff1", "#314e04", "#d9f2c3", "#fffbf6",
                "#65440a", "#fde4c1", "#fffbfb", "#97073a", "#fedfe2", "#edf4fe", "#0a54ae", "#dbe9fe",
                "#034760", "#0a54ae", "#4d3306", "#314e04", "#924d0e", "#9854f1", "#007197", "#77510a",
                "#436612", "#b15c00", "#1f6fd4", "#6b6f7a", "#6b6f7a", "#3b4566", "#0f1638"
            ),
            dark = listOf(
                "#13141e", "#10111a", "#1b1d28", "#2d2f3f", "#3d4053", "#585c73", "#bdc7f1", "#222431",
                "#78a2fa", "#0a1d25", "#585c73", "#ffffff0f", "#f9faff", "#a3abd2", "#8990b1", "#13141e",
                "#bdc7f1", "#c7dafe", "#dde8fe", "#c7dafe", "#dde8fe", "#a1a9ce", "#7b809f", "#3d4053",
                "#10111a", "#bdc7f1", "#c7dafe", "#dde8fe", "#ffffff1a", "#ffffff14", "#ffffff33", "#bdc7f1",
                "#afc9fe", "#ffffff0f", "#ffffff1a", "#00000099", "#112000", "#aadb75", "#416408", "#261701",
                "#f2bf76", "#75500e", "#370211", "#fbb2bc", "#9f1842", "#01104f", "#afc9fe", "#2a50ae",
                "#b4e0fb", "#dde8fe", "#fde4c2", "#d3f5b2", "#fcceb4", "#bb9af7", "#7dcfff", "#fdd8a5",
                "#a8fc1c", "#ff9e64", "#7aa2f7", "#565f89", "#565f89", "#a3abd2", "#f9faff"
            ),
        ),
        ThemeHex(
            id = "vercel",
            name = "Vercel",
            light = listOf(
                "#ffffff", "#f9f9f9", "#f4f4f4", "#ededed", "#e4e4e4", "#d9d9d9", "#676767", "#919191",
                "#0871f1", "#f9fcff", "#919191", "#ffffff", "#070707", "#666666", "#4a4a4a", "#ffffff",
                "#ffffff", "#0871f1", "#0f5ec7", "#073a7f", "#073a7f", "#3c3c3c", "#919191", "#c9c9c9",
                "#ffffffff", "#ffffff", "#0871f1", "#0f5ec7", "#0000001a", "#00000014", "#00000033", "#676767",
                "#a9cbfd", "#0000000a", "#00000014", "#00000066", "#f5fff5", "#14621b", "#befbbd", "#fffbf8",
                "#a45e02", "#fee2cb", "#fffbfa", "#a10725", "#fde0de", "#edf4ff", "#0a4ca2", "#dbe9fe",
                "#0a4ca2", "#0a4ca2", "#804903", "#14621b", "#801cc3", "#e93d82", "#12a594", "#12a594",
                "#46a758", "#ffb224", "#8e4ec6", "#888888", "#888888", "#666666", "#070707"
            ),
            dark = listOf(
                "#000000", "#000000", "#030303", "#161616", "#2c2c2c", "#515151", "#e8e8e8", "#080808",
                "#59a8f9", "#011d2e", "#515151", "#ffffff0f", "#fdfdfd", "#878787", "#adadad", "#000000",
                "#e8e8e8", "#beddfe", "#d8eafe", "#beddfe", "#d8eafe", "#bdbdbd", "#848484", "#515151",
                "#000000", "#e8e8e8", "#beddfe", "#d8eafe", "#ffffff1a", "#ffffff14", "#ffffff33", "#e8e8e8",
                "#a1cdfe", "#ffffff0f", "#ffffff1a", "#00000099", "#012208", "#81e38f", "#076424", "#261701",
                "#fbbc5a", "#75500a", "#380307", "#fcb4af", "#91021b", "#031b34", "#a1cdfe", "#05599e",
                "#beddfe", "#d8eafe", "#fde4c1", "#bbfbc2", "#e4cefd", "#f75590", "#0ac7ac", "#0ac7ac",
                "#63c46d", "#f2a700", "#bf7af0", "#878787", "#878787", "#878787", "#fdfdfd"
            ),
        ),
        ThemeHex(
            id = "vesper",
            name = "Vesper",
            light = listOf(
                "#f1f1f1", "#ebebeb", "#e6e6e6", "#dfdfdf", "#d7d7d7", "#cbcbcb", "#5d5d5d", "#868686",
                "#da9253", "#fcfcfc", "#868686", "#f1f1f1", "#040404", "#2a2a2a", "#434343", "#f1f1f1",
                "#f1f1f1", "#da9253", "#c27833", "#7f4912", "#7f4912", "#333333", "#868686", "#bcbcbc",
                "#ffffffff", "#f1f1f1", "#da9253", "#c27833", "#0000001a", "#00000014", "#00000033", "#5d5d5d",
                "#f6bb8a", "#0000000a", "#00000014", "#00000066", "#f3fffb", "#0b846e", "#b4f9e5", "#fffbf8",
                "#a25f19", "#fde2cd", "#fffbfa", "#cf1c36", "#ffdfde", "#fff1e6", "#a25f19", "#fde2cd",
                "#a25f19", "#a25f19", "#7f4912", "#0b846e", "#8f0804", "#6e6e6e", "#101010", "#8d541c",
                "#117e69", "#8d541c", "#8d541c", "#7a7a7a", "#7a7a7a", "#2a2a2a", "#040404"
            ),
            dark = listOf(
                "#0f0f0f", "#0b0b0b", "#1a1a1a", "#323232", "#484848", "#6c6c6c", "#fafafa", "#232323",
                "#d89358", "#1b1b1b", "#6c6c6c", "#ffffff0f", "#ffffff", "#dbdbdb", "#bebebe", "#0f0f0f",
                "#fafafa", "#ffcea7", "#fee2cb", "#ffcea7", "#fee2cb", "#d3d3d3", "#6c6c6c", "#484848",
                "#0b0b0b", "#fafafa", "#ffcea7", "#fee2cb", "#ffffff1a", "#ffffff14", "#ffffff33", "#fafafa",
                "#f4bc8d", "#ffffff0f", "#ffffff1a", "#00000099", "#02201a", "#77e0c5", "#146655", "#2b1400",
                "#f4bc8d", "#814808", "#380408", "#fbb4b1", "#a60525", "#2b1400", "#f4bc8d", "#814808",
                "#ffcea7", "#fee2cb", "#fee2cb", "#b6f8e5", "#fdcbc8", "#a0a0a0", "#ffffff", "#ffc799",
                "#99ffe4", "#ffc799", "#ffc799", "#8b8b8b", "#8b8b8b", "#dbdbdb", "#ffffff"
            ),
        ),
        ThemeHex(
            id = "zenburn",
            name = "Zenburn",
            light = listOf(
                "#fffff4", "#fafaef", "#f6f6eb", "#f1f1e5", "#eae9df", "#e1e0d6", "#858480", "#a7a6a0",
                "#548196", "#fffbf7", "#a7a6a0", "#fffff4", "#222222", "#6f6f6f", "#6c6c6c", "#fffff4",
                "#fffff4", "#548196", "#3e6c82", "#1c4456", "#1c4456", "#454544", "#858480", "#c2c1b9",
                "#60605e", "#fffff4", "#548196", "#3e6c82", "#0000001a", "#00000014", "#00000033", "#858480",
                "#abcfe2", "#0000000a", "#00000014", "#00000066", "#f6fff5", "#256929", "#cff4ce", "#fdfdee",
                "#686621", "#eaebc7", "#fffbfa", "#6d3234", "#fde0df", "#e7f6ff", "#2c586c", "#d4ecf9",
                "#6a562a", "#2c586c", "#545212", "#256929", "#337e7e", "#8f8f5f", "#5f8f8f", "#5f8f8f",
                "#8f5f5f", "#5f8f5f", "#5f7f8f", "#5f7f5f", "#5f7f5f", "#6f6f6f", "#222222"
            ),
            dark = listOf(
                "#2c2c2b", "#292929", "#353534", "#484845", "#595854", "#74736d", "#d9d9c9", "#3d3c3b",
                "#59b5ba", "#29150c", "#74736d", "#ffffff0f", "#fcfcfa", "#9f9f9f", "#9c9c90", "#2c2c2b",
                "#d9d9c9", "#ace6e8", "#c5f2f4", "#ace6e8", "#c5f2f4", "#d9d9c9", "#97968d", "#595854",
                "#292929", "#d9d9c9", "#ace6e8", "#c5f2f4", "#ffffff1a", "#ffffff14", "#ffffff33", "#d9d9c9",
                "#92d7da", "#ffffff0f", "#ffffff1a", "#00000099", "#09210a", "#b2d4b1", "#3e623f", "#221a02",
                "#d9c898", "#68561f", "#310c0f", "#f4b7b7", "#7f4446", "#021f21", "#92d7da", "#136468",
                "#facfb3", "#c5f2f4", "#f3e7c7", "#d8efd8", "#a6e7e9", "#f0dfaf", "#93e0e3", "#93e0e3",
                "#cc9393", "#8fb28f", "#8cd0d3", "#7f9f7f", "#7f9f7f", "#9f9f9f", "#fcfcfa"
            ),
        ),
    )

    private val BY_ID: Map<String, ThemeHex> = THEMES.associateBy { it.id }
}
