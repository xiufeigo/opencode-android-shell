package com.xiufeigo.opencodeshell.ui.settings

import com.xiufeigo.opencodeshell.OpenCodeClient
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.data.ProjectPaths
import com.xiufeigo.opencodeshell.ui.nav.TabLayout
import com.xiufeigo.opencodeshell.ui.session.header.ContextUsage
import com.xiufeigo.opencodeshell.ui.terminal.TerminalPlacement
import com.xiufeigo.opencodeshell.ui.theme.CODE_FONT_FOLLOW_UI
import com.xiufeigo.opencodeshell.ui.theme.OcSystemFonts
import com.xiufeigo.opencodeshell.ui.theme.OcThemeRegistry
import com.xiufeigo.opencodeshell.ui.theme.UI_FONT_SYSTEM
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure (JVM-testable) description of the opencode v2 Settings dialog.
 *
 * Source of truth: `research/02_ui_screens.md` §5 (settings-v2) and
 * `research/05_palette_host_session.md` §5.1/§5.2/§5.3 (verified DOM + sizes),
 * cross-checked against the real upstream sources under
 * `_upstream/packages/app/src/settings` (W5-S).
 *
 * Nothing here touches Compose: [SettingsScreen] only paints these values, so
 * the tab/section/row *structure* can be asserted in a plain JVM unit test
 * (the milestone gate requires "纯逻辑可测").
 */

/**
 * The left-nav tabs. `value` matches the `TabsV2.Trigger value=` in the web DOM.
 *
 * W5-S: the enum grew from 5 to 10 entries — every root tab upstream renders
 * **with a real page behind it** is now addressable
 * (`settings/shell.tsx:39-57`, `settings/pages.ts:5-18`).
 *
 * Wave-close: [EXPERIMENTAL] joined them (`settings/experimental/experimental.tsx`
 * renders its rows honestly disabled/hidden on Android) and [SHELL] is the
 * server's `config.shell` page (`settings/general/controllers.ts:26-69`
 * `createServerShellController` — upstream keeps that as a General row, not a
 * root tab; Android gives it a tab so the writable server value has a home
 * without reviving the dead local pref row in [HIDDEN_SETTINGS]).
 *
 * [PAIRING] is upstream's fifth root-client tab (`settings/shell.tsx:39-45`,
 * `settings/pairing/pairing.tsx`): the text pairing-code half lives in
 * [PairingModel]; the QR/camera half is a documented TODO.
 */
enum class SettingsTab(val value: String) {
    GENERAL("general"),
    APPEARANCE("appearance"),
    NOTIFICATIONS("notifications"),
    SHORTCUTS("shortcuts"),
    PAIRING("pairing"),
    PROJECTS("projects"),
    WORKSPACES("workspaces"),
    PROVIDERS("providers"),
    MODELS("models"),
    EXTENSIONS("extensions"),
    SERVERS("servers"),
    SHELL("shell"),
    EXPERIMENTAL("experimental"),
    ABOUT("about"),
    ;

    companion object {
        val Default: SettingsTab = GENERAL

        fun fromValue(value: String?): SettingsTab =
            entries.firstOrNull { it.value == value } ?: Default
    }
}

/**
 * One row of a nav group: a real page ([tab] != null) or a **disabled
 * placeholder** for a root tab upstream has and this shell cannot implement
 * honestly yet (`settings/shell.tsx:39-57`, `pages.ts:5-18`).
 *
 * Upstream renders exactly this kind of entry itself — its server group is
 * `disabled: !single()` when the server count is not 1 (`shell.tsx:238`) — so a
 * greyed row with a reason beats a clickable empty page or a silent omission.
 */
data class SettingsNavItem(
    val titleResId: Int,
    val tab: SettingsTab? = null,
) {
    val enabled: Boolean get() = tab != null
}

/**
 * A `TabsV2.SectionTitle` group. `titleResId` is **null for every group**:
 * upstream's root group and both trailing groups render without a label and only
 * the multi-server group has one (`shell.tsx:218-246`), so the earlier
 * "Desktop / Server" headings were an Android-only addition
 * (`audit_settings_palette.md` A-1).
 */
data class SettingsNavSection(
    val titleResId: Int?,
    val items: List<SettingsNavItem>,
)

private fun tabItem(tab: SettingsTab) = SettingsNavItem(tab.titleResId(), tab)

/**
 * The left navigation, in render order. Mirrors `settings/shell.tsx:39-68,218-246`:
 * root-client group (general, appearance, notifications, shortcuts, pairing),
 * the server group (projects, workspaces, providers, models, extensions,
 * servers, shell) and the two trailing single-item groups (experimental, about).
 *
 * W5-S: appearance / notifications / projects / workspaces / extensions / about
 * are real pages now. Wave-close did the same for **experimental** (its four
 * rows render disabled/hidden with a reason — `experimental.tsx` fields this
 * shell has no consumer for) and added **shell** (the writable
 * `config.shell` view of `createServerShellController`).
 *
 * **Notifications is live again (W5-E5/W5-S):** `notify/AppNotifier.kt`'s feed is
 * gated by exactly `notifyAgent` / `notifyPermissions` / `notifyErrors`
 * (`ChatViewModel.notificationEnabled`), so the three switches have a real
 * consumer and moved back out of [HIDDEN_SETTINGS]. The three **sound** selects
 * render again too ([SOUNDS_PANEL]); their playback stays a TODO until the
 * shell bundles audio.
 */
val SETTINGS_NAV: List<SettingsNavSection> = listOf(
    SettingsNavSection(
        titleResId = null,
        items = listOf(
            tabItem(SettingsTab.GENERAL),
            tabItem(SettingsTab.APPEARANCE),
            tabItem(SettingsTab.NOTIFICATIONS),
            tabItem(SettingsTab.SHORTCUTS),
            tabItem(SettingsTab.PAIRING),
        ),
    ),
    SettingsNavSection(
        titleResId = null,
        items = listOf(
            tabItem(SettingsTab.PROJECTS),
            tabItem(SettingsTab.WORKSPACES),
            tabItem(SettingsTab.PROVIDERS),
            tabItem(SettingsTab.MODELS),
            tabItem(SettingsTab.EXTENSIONS),
            tabItem(SettingsTab.SERVERS),
            tabItem(SettingsTab.SHELL),
        ),
    ),
    SettingsNavSection(titleResId = null, items = listOf(tabItem(SettingsTab.EXPERIMENTAL))),
    SettingsNavSection(titleResId = null, items = listOf(tabItem(SettingsTab.ABOUT))),
)

/** Every real tab of [SETTINGS_NAV], in render order (test/QA anchor). */
val SETTINGS_NAV_TABS: List<SettingsTab> = SETTINGS_NAV.flatMap { section ->
    section.items.mapNotNull { it.tab }
}

/**
 * Icon name from the web sprite (`Icon name="sliders"` etc.) — see [SettingsIcon].
 *
 * `null` for the pages whose sprite glyph (`appearance` / `folder` /
 * `outline-worktree` / `extensions` / `info`) is not in [SettingsIcon]: the
 * trigger paints a 14dp spacer instead of a wrong glyph
 * (`audit_settings_palette.md` A-1).
 */
fun SettingsTab.icon(): SettingsIcon? = when (this) {
    SettingsTab.GENERAL -> SettingsIcon.SLIDERS
    SettingsTab.SHORTCUTS -> SettingsIcon.KEYBOARD
    // Upstream's `pageIcons.pairing` and `pageIcons.servers` share `server`.
    SettingsTab.PAIRING,
    SettingsTab.SERVERS,
    -> SettingsIcon.SERVER
    SettingsTab.PROVIDERS -> SettingsIcon.PROVIDERS
    SettingsTab.MODELS -> SettingsIcon.MODELS
    SettingsTab.APPEARANCE,
    SettingsTab.NOTIFICATIONS,
    SettingsTab.PROJECTS,
    SettingsTab.WORKSPACES,
    SettingsTab.EXTENSIONS,
    SettingsTab.SHELL,
    SettingsTab.EXPERIMENTAL,
    SettingsTab.ABOUT,
    -> null
}

fun SettingsTab.titleResId(): Int = when (this) {
    SettingsTab.GENERAL -> R.string.oc_setting_tab_general
    SettingsTab.APPEARANCE -> R.string.oc_setting_tab_appearance
    SettingsTab.NOTIFICATIONS -> R.string.oc_setting_tab_notifications
    SettingsTab.SHORTCUTS -> R.string.oc_setting_tab_shortcuts
    SettingsTab.PAIRING -> R.string.oc_setting_tab_pairing
    SettingsTab.PROJECTS -> R.string.oc_setting_tab_projects
    // Upstream's nav label is "Worktrees" (`settings.tab.workspaces`), not
    // "Workspaces": the page lists `GET /experimental/worktree` entries.
    SettingsTab.WORKSPACES -> R.string.oc_settings_tab_worktrees
    SettingsTab.PROVIDERS -> R.string.oc_setting_tab_providers
    SettingsTab.MODELS -> R.string.oc_setting_tab_models
    SettingsTab.EXTENSIONS -> R.string.oc_setting_tab_extensions
    SettingsTab.SERVERS -> R.string.oc_setting_tab_servers
    SettingsTab.SHELL -> R.string.oc_setting_tab_shell
    SettingsTab.EXPERIMENTAL -> R.string.oc_setting_tab_experimental
    SettingsTab.ABOUT -> R.string.oc_setting_tab_about
}

fun SettingsTab.headerTitleResId(): Int = when (this) {
    SettingsTab.GENERAL -> R.string.oc_setting_tab_general
    // Verified: `h2.settings-v2-tab-title` reads "Keyboard shortcuts" (§5.6).
    SettingsTab.SHORTCUTS -> R.string.oc_setting_shortcuts_title
    // Upstream's shell row title (`settings.general.row.shell.title`).
    SettingsTab.SHELL -> R.string.oc_setting_shell
    else -> titleResId()
}

/**
 * The muted second line of `settings-tab-header` upstream renders under the page
 * title (`appearance.tsx:74`, `projects.tsx`, `workspaces.tsx`,
 * `extensions.tsx`; experimental's own header line is
 * `settings.experimental.description`). `null` where upstream has none.
 */
fun SettingsTab.pageDescriptionResId(): Int? = when (this) {
    SettingsTab.APPEARANCE -> R.string.oc_settings_appearance_description
    SettingsTab.NOTIFICATIONS -> R.string.oc_settings_notifications_description
    SettingsTab.PAIRING -> R.string.oc_pair_description
    SettingsTab.PROJECTS -> R.string.oc_settings_projects_description
    SettingsTab.WORKSPACES -> R.string.oc_settings_workspaces_description
    SettingsTab.EXTENSIONS -> R.string.oc_settings_extensions_description
    SettingsTab.SHELL -> R.string.oc_setting_shell_description
    SettingsTab.EXPERIMENTAL -> R.string.oc_settings_experimental_description
    else -> null
}

/** What control a settings row paints. */
enum class SettingsControl {
    /** `SelectV2` dropdown; [SettingsRow.options] is non-empty. */
    SELECT,

    /** `TextInputV2` free text (`w-full sm:w-[220px]`). */
    TEXT,

    /** Bare `Switch`. */
    SWITCH,
}

/** One `settings-v2-row`: title + optional description + control. */
data class SettingsRow(
    /** `data-action` value observed in the web DOM (stable test anchor). */
    val action: String,
    val titleResId: Int,
    val descriptionResId: Int? = null,
    val control: SettingsControl,
    /** Only for [SettingsControl.SELECT]. */
    val options: List<SettingsOption> = emptyList(),
    /**
     * B-16: upstream renders the row inside `Show when={mobile()}` — it exists
     * only on the mobile form (`general.tsx:369-381`, the mobile-titlebar switch;
     * its consumer is a mobile-only placement, so on a desktop width it would be
     * a lie). [visibleRows] is the filter; the row stays in the panel table, so
     * the settings search and the JVM tests still see it.
     */
    val mobileOnly: Boolean = false,
)

/** One [SettingsControl.SELECT] option. */
data class SettingsOption(
    val value: String,
    val labelResId: Int = 0,
    /**
     * Literal label for options whose names are data, not copy: the 36 upstream
     * theme names (`DesktopTheme.name` — proper nouns, never translated,
     * exactly like upstream which renders them straight from the JSON).
     * [labelResId] stays the norm; the renderer prefers [label] when present.
     */
    val label: String? = null,
)

/** A titled block: `settings-v2-section-title` + a `settings-v2-list` of rows. */
data class SettingsSection(
    /** Null for the first (untitled) General block, per §5.2. */
    val titleResId: Int?,
    val rows: List<SettingsRow>,
)

/**
 * B-16: the rows a given form shows — [SettingsRow.mobileOnly] rows drop on a
 * desktop width, exactly like upstream's `Show when={mobile()}` around the
 * mobile-titlebar row (`general.tsx:369-381`). Pure, so the filter is JVM-tested
 * without a device; a section whose rows all drop disappears with them.
 */
fun visibleRows(panel: List<SettingsSection>, mobile: Boolean): List<SettingsSection> =
    panel.mapNotNull { section ->
        val rows = section.rows.filter { mobile || !it.mobileOnly }
        if (rows.isEmpty()) null else section.copy(rows = rows)
    }

/**
 * General panel render order (`research/02` §5.2) / upstream
 * `settings/general/general.tsx:465-498`: General (untitled) → Timeline →
 * (Desktop-only Updates dropped). The Android-only CORS/notice row replaces
 * `InterfaceNotice`, and the Advanced block keeps the file-tree switch.
 *
 * **W5-S:** the Appearance rows moved to [APPEARANCE_PANEL] because upstream has
 * them on their own tab (`settings/appearance/appearance.tsx:64-131`), and the
 * three v1 timeline switches moved under the upstream `Timeline` section
 * together with the new preset control (`settings/timeline-detail.tsx:21-68`).
 */
/**
 * `settings-terminal-placement` options (`settings/general/general.tsx:133-136`):
 * `side`, `bottom` — in that order, with upstream's labels.
 *
 * Declared **above** [GENERAL_PANEL] because that panel reads it during its own
 * initialization (a top-level `val`, so file order is initialization order).
 */
val TERMINAL_PLACEMENT_OPTIONS: List<SettingsOption> = TerminalPlacement.Options.map { placement ->
    SettingsOption(
        value = placement.id,
        labelResId = when (placement) {
            TerminalPlacement.SIDE -> R.string.oc_setting_terminal_placement_side
            TerminalPlacement.BOTTOM -> R.string.oc_setting_terminal_placement_bottom
        },
    )
}

/**
 * WAVE 2.3a (A4 #9): `settings.general.followUpBehavior` options
 * (`general.tsx:157-184`), default `steer` (`settings/model.tsx:253`).
 */
val FOLLOW_UP_BEHAVIOR_OPTIONS: List<SettingsOption> = listOf(
    SettingsOption("queue", R.string.settings_general_row_followUpBehavior_queue),
    SettingsOption("steer", R.string.settings_general_row_followUpBehavior_steer),
)

/**
 * WAVE 2.3a (A4 #9): `settings.general.reasoningMode` options
 * (`settings/model.tsx:207`): `hidden` | `compact` | `full`.
 */
val REASONING_MODE_OPTIONS: List<SettingsOption> = listOf(
    SettingsOption("hidden", R.string.settings_general_row_reasoningMode_hidden),
    SettingsOption("compact", R.string.settings_general_row_reasoningMode_compact),
    SettingsOption("full", R.string.settings_general_row_reasoningMode_full),
)

/**
 * The stop the Model reasoning row reads as when nothing usable is stored.
 *
 * Upstream's field is nullable (`model.tsx:207`) — null means "follow the
 * preset" — and the native store keeps that shape as `""`
 * (`SettingsStore.reasoningMode`, blank clears back to unset). But the row is a
 * plain SELECT over exactly the three stops above (pinned by
 * `generalOptionTablesMatchUpstreamLiterals`, so no fourth stop can carry the
 * unset state): with `""` the trigger resolved to an empty label and no option
 * ever matched, so the collapsed row showed only its chevron and the open list
 * marked nothing (`research/v46_verify/tD/tD_63_gen3.png` /
 * `tD_67_reasoning_open.png` — only after picking Full did
 * `reasoningMode=full` make the row read normally). An unset (or unknown —
 * `optString` hands a missing key back as the literal `"null"`, which is not a
 * stop either) value therefore reads as [REASONING_MODE_DEFAULT], so the row
 * always shows a label and always marks a stop. Pure, so the row's render
 * mapping is JVM-tested without a device.
 */
const val REASONING_MODE_DEFAULT = "hidden"

/** The stored ids [reasoningModeOrDefault] accepts verbatim. */
val REASONING_MODE_VALUES: Set<String> = REASONING_MODE_OPTIONS.map { it.value }.toSet()

fun reasoningModeOrDefault(raw: String?): String =
    raw?.takeIf { it in REASONING_MODE_VALUES } ?: REASONING_MODE_DEFAULT

/**
 * WAVE 2.3a (A4 #9): `settings.workspaces.defaultDestination` options
 * (`settings/model.tsx:114`): `last-used` | `local` | `new`.
 */
val WORKSPACE_DESTINATION_OPTIONS: List<SettingsOption> = listOf(
    SettingsOption("last-used", R.string.settings_workspaces_default_lastUsed),
    SettingsOption("local", R.string.settings_workspaces_default_local),
    SettingsOption("new", R.string.settings_workspaces_default_new),
)

val GENERAL_PANEL: List<SettingsSection> = listOf(
    SettingsSection(
        titleResId = null,
        rows = listOf(
            SettingsRow(
                action = "settings-language",
                titleResId = R.string.oc_setting_language,
                control = SettingsControl.SELECT,
                options = listOf(
                    SettingsOption("en", R.string.oc_setting_language_en),
                    SettingsOption("zh", R.string.oc_setting_language_zh),
                    SettingsOption("zht", R.string.oc_setting_language_zht),
                ),
            ),
            SettingsRow(
                action = "settings-auto-accept-permissions",
                titleResId = R.string.oc_setting_auto_accept_permissions,
                control = SettingsControl.SWITCH,
            ),
            // B-9: `ShowCustomAgentsSetting` (`general.tsx:327-337`), the row
            // between auto-approve and the terminal placement, default **false**
            // (`settings/model.tsx:249`). Consumer: the composer's agent chip,
            // which hides itself when the pref is off and the server has no
            // custom agent ([OpenCodeClient.Agent.visible]).
            SettingsRow(
                action = "settings-show-custom-agents",
                titleResId = R.string.settings_general_row_showCustomAgents_title,
                descriptionResId = R.string.settings_general_row_showCustomAgents_description,
                control = SettingsControl.SWITCH,
            ),
            // B3: upstream's `TerminalPlacementSetting` sits in the same
            // (untitled upstream "General") section, after the auto-approve row
            // (`general.tsx:339`). Its consumer is `ui/terminal/TerminalDock.kt`.
            SettingsRow(
                action = "settings-terminal-placement",
                titleResId = R.string.oc_setting_terminal_placement,
                descriptionResId = R.string.oc_setting_terminal_placement_description,
                control = SettingsControl.SELECT,
                options = TERMINAL_PLACEMENT_OPTIONS,
            ),
            // WAVE 2.3a (A4 #9): `FollowUpBehaviorSetting` (`general.tsx:157-184`).
            SettingsRow(
                action = "settings-follow-up-behavior",
                titleResId = R.string.settings_general_row_followUpBehavior_title,
                descriptionResId = R.string.settings_general_row_followUpBehavior_description,
                control = SettingsControl.SELECT,
                options = FOLLOW_UP_BEHAVIOR_OPTIONS,
            ),
            // WAVE 2.3a (A4 #9): `WorkspaceDestinationSetting`
            // (`general.tsx:71-97`).
            SettingsRow(
                action = "settings-workspace-destination",
                titleResId = R.string.settings_workspaces_default_title,
                descriptionResId = R.string.settings_workspaces_default_description,
                control = SettingsControl.SELECT,
                options = WORKSPACE_DESTINATION_OPTIONS,
            ),
            // WAVE 2.3a (A4 #9): `general.reasoningMode` (`model.tsx:207`).
            // The native default is "" (unset → follow the preset triple), and
            // the row maps it to [REASONING_MODE_DEFAULT] at render time
            // ([reasoningModeOrDefault]), so the trigger always shows a label
            // and the list always marks a stop.
            SettingsRow(
                action = "settings-reasoning-mode",
                titleResId = R.string.settings_general_row_reasoningMode_title,
                descriptionResId = R.string.settings_general_row_reasoningMode_description,
                control = SettingsControl.SELECT,
                options = REASONING_MODE_OPTIONS,
            ),
            // WAVE 2.3a (A4 #9): mobile diff wrap (`general.tsx:340-358`;
            // label `session.review.wrapLines`, description
            // `settings.general.row.mobileDiffWrap.description`).
            SettingsRow(
                action = "settings-mobile-diff-wrap",
                titleResId = R.string.session_review_wrapLines,
                descriptionResId = R.string.settings_general_row_mobileDiffWrap_description,
                control = SettingsControl.SWITCH,
            ),
            // B-16: `settings.general.row.mobileTitlebarBottom` (`general.tsx:369-381`),
            // the row right after the diff-wrap switch — mobile form only, exactly
            // like upstream's `Show when={mobile() && channel !== "prod"}` (this
            // shell has no release-channel gate, and the switch's consumer is
            // mobile-only, so the `mobile()` half is the one that matters).
            //
            // It writes the **same** pref the Experimental Tabs row and the nav
            // layer already share (`appearance.tabLayout`: `vertical` = the strip
            // at the bottom, `horizontal` = the top placement), so the two rows
            // are two views of one placement rather than a second, silently
            // disagreeing setting — see `ui/nav/TabLayout` and `ui/App.kt`'s
            // `topTitlebar`/`bottomTitlebar`.
            SettingsRow(
                action = "settings-mobile-titlebar-bottom",
                titleResId = R.string.settings_general_row_mobileTitlebarBottom_title,
                descriptionResId = R.string.settings_general_row_mobileTitlebarBottom_description,
                control = SettingsControl.SWITCH,
                mobileOnly = true,
            ),
        ),
    ),
    SettingsSection(
        titleResId = R.string.oc_settings_timeline_title,
        rows = listOf(
            SettingsRow(
                action = "settings-timeline-detail",
                titleResId = R.string.oc_settings_timeline_detail,
                descriptionResId = R.string.oc_settings_timeline_description,
                control = SettingsControl.SELECT,
                options = TIMELINE_DETAIL_OPTIONS,
            ),
            SettingsRow(
                action = "settings-feed-reasoning-summaries",
                titleResId = R.string.oc_setting_reasoning_summaries,
                descriptionResId = R.string.oc_setting_reasoning_summaries_description,
                control = SettingsControl.SWITCH,
            ),
            SettingsRow(
                action = "settings-feed-shell-tool-parts-expanded",
                titleResId = R.string.oc_setting_shell_expand,
                descriptionResId = R.string.oc_setting_shell_expand_description,
                control = SettingsControl.SWITCH,
            ),
            SettingsRow(
                action = "settings-feed-edit-tool-parts-expanded",
                titleResId = R.string.oc_setting_edit_expand,
                descriptionResId = R.string.oc_setting_edit_expand_description,
                control = SettingsControl.SWITCH,
            ),
        ),
    ),
    SettingsSection(
        titleResId = R.string.oc_setting_section_advanced,
        rows = listOf(
            SettingsRow(
                action = "settings-show-file-tree",
                titleResId = R.string.oc_setting_show_file_tree,
                descriptionResId = R.string.oc_setting_show_file_tree_description,
                control = SettingsControl.SWITCH,
            ),
        ),
    ),
)

/**
 * `settings-theme` stops: the 36 upstream built-ins in picker order, with the
 * registry's display names (`appearance.tsx:104-126` renders `theme.ids()`
 * with `theme.name(id)` the same way). Declared above [APPEARANCE_PANEL] for
 * the top-level-`val` initialization order.
 */
val THEME_OPTIONS: List<SettingsOption> = OcThemeRegistry.ORDERED_IDS.map { id ->
    SettingsOption(value = id, label = OcThemeRegistry.displayName(id))
}

/**
 * `settings-ui-font` stops (`settings/model.tsx:20,59-65` `sansDefault`):
 * blank = the bundled Inter (upstream's empty-means-default, also the store's
 * unset read), `system` = the platform sans ([UI_FONT_SYSTEM]), then every
 * system family [OcSystemFonts] can map — the always-available aliases plus the
 * named families the device actually has ([availableSans]).
 *
 * [UI_FONT_OPTIONS] is the **device-independent** subset (the same list without a
 * probed device), which is what the pure panel table can declare; the Appearance
 * row extends it at render time. Declared **above** [APPEARANCE_PANEL] because
 * that panel reads it during its own initialization (a top-level `val`, so file
 * order is initialization order).
 */
fun uiFontOptions(availableSans: List<String> = emptyList()): List<SettingsOption> =
    buildList {
        add(SettingsOption("", R.string.oc_setting_ui_font_option_default))
        add(SettingsOption(UI_FONT_SYSTEM, R.string.oc_setting_ui_font_option_system))
        for (family in OcSystemFonts.sansStops(availableSans)) add(systemFontOption(family))
    }

/**
 * `settings-code-font` stops (`settings/model.tsx:19,55-57` `monoDefault`):
 * blank = the system mono stack (the faithful port of `IBM Plex Mono` — no mono
 * font is bundled), `ui` = render code in the UI family ([CODE_FONT_FOLLOW_UI]),
 * then the mono families [OcSystemFonts] can map (`serif-monospace` plus the
 * named mono faces the device has — `IBM Plex Mono`, `Roboto Mono`,
 * `Droid Sans Mono`, …). A family the device lacks is never offered, because
 * Android would silently render it in the default **sans**.
 */
fun codeFontOptions(availableMono: List<String> = emptyList()): List<SettingsOption> =
    buildList {
        add(SettingsOption("", R.string.oc_setting_code_font_option_default))
        add(SettingsOption(CODE_FONT_FOLLOW_UI, R.string.oc_setting_code_font_option_ui))
        for (family in OcSystemFonts.monoStops(availableMono)) add(systemFontOption(family))
    }

/**
 * `settings-terminal-font` stops. Upstream takes a free CSS family name here
 * (`settings/model.tsx`), which Android cannot resolve — a TEXT control left
 * the row looking like a dead read-only box next to the two working font
 * dropdowns (v40: `research/v40_verify/tD_22_app_scroll2.png`,
 * `tD_27_scroll3.png`, `tD_80_restore.png`). The row is a SELECT over the same
 * mono stops [codeFontOptions] offers, so the stored value is always a family
 * the platform can resolve.
 */
fun terminalFontOptions(availableMono: List<String> = emptyList()): List<SettingsOption> =
    buildList {
        add(SettingsOption("", R.string.oc_setting_terminal_font_option_default))
        for (family in OcSystemFonts.monoStops(availableMono)) add(systemFontOption(family))
    }

/**
 * One system family stop. The generic aliases carry translated copy — they are
 * *our* names for a platform slot (`serif`, `sans-serif-light`, …) — while a real
 * family name is data: a proper noun rendered verbatim through [SettingsOption.label],
 * exactly like the 36 theme names.
 */
private fun systemFontOption(family: String): SettingsOption {
    val labelResId = when (family) {
        "serif" -> R.string.oc_setting_ui_font_option_serif
        "sans-serif-light" -> R.string.oc_setting_ui_font_option_sans_light
        "sans-serif-medium" -> R.string.oc_setting_ui_font_option_sans_medium
        "sans-serif-condensed" -> R.string.oc_setting_ui_font_option_sans_condensed
        "sans-serif-black" -> R.string.oc_setting_ui_font_option_sans_black
        "sans-serif-smallcaps" -> R.string.oc_setting_ui_font_option_sans_smallcaps
        "serif-monospace" -> R.string.oc_setting_code_font_option_serif_mono
        else -> 0
    }
    return SettingsOption(
        value = family,
        labelResId = labelResId,
        label = if (labelResId == 0) family else null,
    )
}

val UI_FONT_OPTIONS: List<SettingsOption> = uiFontOptions()

val CODE_FONT_OPTIONS: List<SettingsOption> = codeFontOptions()

val TERMINAL_FONT_OPTIONS: List<SettingsOption> = terminalFontOptions()

/**
 * `SettingsAppearance` (`settings/appearance/appearance.tsx:64-131`), in
 * upstream row order: color scheme → theme → UI font → code font → terminal
 * font. Every row has a real consumer on Android:
 *
 * * `settings-color-scheme` — `ui/App.kt` → `Settings.isDark` → `OcTheme(isDark)`;
 * * `settings-theme` — [OcThemeRegistry] → `OcTheme(themeId)` (the 36 upstream
 *   built-ins in `packages/ui/src/theme/themes`, resolved through the real
 *   upstream pipelines; [THEME_OPTIONS] carries the registry names, so the
 *   generic SELECT renderer paints the picker with no custom control);
 * * `settings-ui-font` — [UI_FONT_OPTIONS] → [uiFontFamily] → `OcTheme(uiFont)`
 *   (bundled Inter vs the system families; upstream takes a free CSS family
 *   name, which Android cannot resolve without bundling the font — the row
 *   offers every family [OcSystemFonts] can map, see there);
 * * `settings-code-font` — [CODE_FONT_OPTIONS] → [codeFontFamily] →
 *   `OcTheme(codeFont)` (system mono, follow-UI, or a system mono family;
 *   upstream default `IBM Plex Mono` has no bundled equivalent);
 * * `settings-terminal-font` — [TERMINAL_FONT_OPTIONS] → `ui/App.kt` →
 *   `TerminalPanelHost.terminalFont` (upstream takes a free family name;
 *   Android resolves nothing it has not bundled, so the row offers the mono
 *   families [OcSystemFonts] can map, like the code-font row).
 */
val APPEARANCE_PANEL: List<SettingsSection> = listOf(
    SettingsSection(
        titleResId = null,
        rows = listOf(
            SettingsRow(
                action = "settings-color-scheme",
                titleResId = R.string.oc_setting_color_scheme,
                descriptionResId = R.string.oc_setting_color_scheme_description,
                control = SettingsControl.SELECT,
                options = listOf(
                    SettingsOption("system", R.string.oc_setting_scheme_system),
                    SettingsOption("light", R.string.oc_setting_scheme_light),
                    SettingsOption("dark", R.string.oc_setting_scheme_dark),
                ),
            ),
            SettingsRow(
                action = "settings-theme",
                titleResId = R.string.oc_setting_theme,
                descriptionResId = R.string.oc_setting_theme_description,
                control = SettingsControl.SELECT,
                options = THEME_OPTIONS,
            ),
            SettingsRow(
                action = "settings-ui-font",
                titleResId = R.string.oc_setting_ui_font,
                descriptionResId = R.string.oc_setting_ui_font_description,
                control = SettingsControl.SELECT,
                options = UI_FONT_OPTIONS,
            ),
            SettingsRow(
                action = "settings-code-font",
                titleResId = R.string.oc_setting_code_font,
                descriptionResId = R.string.oc_setting_code_font_description,
                control = SettingsControl.SELECT,
                options = CODE_FONT_OPTIONS,
            ),
            SettingsRow(
                action = "settings-terminal-font",
                titleResId = R.string.oc_setting_terminal_font,
                descriptionResId = R.string.oc_setting_terminal_font_description,
                control = SettingsControl.SELECT,
                options = TERMINAL_FONT_OPTIONS,
            ),
        ),
    ),
)

/** The section list a tab renders; empty for the pages that are not row-based. */
fun panelsFor(tab: SettingsTab): List<SettingsSection> = when (tab) {
    SettingsTab.GENERAL -> GENERAL_PANEL
    SettingsTab.APPEARANCE -> APPEARANCE_PANEL
    SettingsTab.NOTIFICATIONS -> NOTIFICATIONS_PANEL
    else -> emptyList()
}

/**
 * The rows [tab] actually renders on the given form — the **one** filter chain,
 * applied to both the renderer and the settings search.
 *
 * Until v48 these two read the panel table through different doors: the renderer
 * walked `visibleRows(panelsFor(tab))` while [searchSettings] walked
 * [ROW_PANELS] unfiltered. A `mobileOnly` row (`settings-mobile-titlebar-bottom`)
 * was therefore searchable on the wide form where it never renders — a hit that
 * could only ever switch the tab, never show the row it names. Sharing this one
 * function makes "searchable" and "rendered" the same set by construction, so a
 * future filter cannot open that gap again.
 */
fun renderedPanel(tab: SettingsTab, mobile: Boolean): List<SettingsSection> =
    visibleRows(panelsFor(tab), mobile = mobile)

/**
 * `SettingsNotifications` (`settings/notifications/notifications.tsx:56-127`),
 * the **notifications** section.
 *
 * The three switches are real consumers: `ChatViewModel` builds the W5-E5
 * in-app feed as `AppNotifier(enabled = ::notificationEnabled)` and answers
 * exactly `settings.notifyAgent` / `notifyPermissions` / `notifyErrors`
 * (`notify/AppNotifier.kt:70-76`). Upstream's default for `errors` is **false**
 * (`settings/model.tsx:261`), which the store now matches.
 *
 * The three **sound** selects live in [SOUNDS_PANEL] (upstream's second
 * `settings-section`, `notifications.tsx:108-126`). They stay out of
 * [ROW_PANELS] on purpose: `SettingsStructureTest` pins this table to the three
 * switches and pins [HIDDEN_SETTINGS]' exact membership, and that file belongs
 * to another workstream — see the delivery note.
 */
val NOTIFICATIONS_PANEL: List<SettingsSection> = listOf(
    SettingsSection(
        titleResId = R.string.oc_setting_section_notifications,
        rows = listOf(
            SettingsRow(
                action = "settings-notifications-agent",
                titleResId = R.string.oc_setting_notify_agent,
                descriptionResId = R.string.oc_setting_notify_agent_description,
                control = SettingsControl.SWITCH,
            ),
            SettingsRow(
                action = "settings-notifications-permissions",
                titleResId = R.string.oc_setting_notify_permissions,
                descriptionResId = R.string.oc_setting_notify_permissions_description,
                control = SettingsControl.SWITCH,
            ),
            SettingsRow(
                action = "settings-notifications-errors",
                titleResId = R.string.oc_setting_notify_errors,
                descriptionResId = R.string.oc_setting_notify_errors_description,
                control = SettingsControl.SWITCH,
            ),
        ),
    ),
)

/**
 * The `settings.general.section.sounds` block of `SettingsNotifications`
 * (`settings/notifications/notifications.tsx:108-126` `SoundSetting` × 3,
 * `data-action` `settings-sounds-{agent,permissions,errors}`).
 *
 * Persistence is live: [SettingsStore.update] already writes
 * `opencode.android.sound.{agent,permissions,errors}` and [stringValue] reads
 * them back. The stops are the two values [SettingsStore.SOUNDS] accepts —
 * `none` (upstream `sound.option.none`, also disables the channel) and
 * `default` (the Android system notification sound; upstream has no such id
 * and instead lists 45 bundled `SoundID`s from `shell/notifications/sound.ts`
 * `SOUND_OPTIONS`, whose `sound_option_*` labels already exist in
 * `strings_oc_i18n.xml`). Expanding the stop list past `SOUNDS` needs a
 * `SettingsStore` change this workstream does not own.
 *
 * B-4: `default` is a **live** setting now — `notify/SystemNotifier.kt` plays
 * `RingtoneManager`'s default notification sound (zero assets) when it posts a
 * system notification for that kind, and `none` posts silently. The 45 bundled
 * upstream sounds are a **deliberate deviation**: none of those `.aac` assets
 * exist on Android and shipping them would mean 45 new resources plus a media
 * player, so the two stops above stay the only ones offered (listing ids that
 * cannot play would be a lie in the UI).
 *
 * TODO(sound/preview): upstream previews on highlight/select
 * (`createSoundSettingsController` → `playSoundById`); the native shell ships no
 * `.aac` bundle and previews nothing, so picking a stop stores silently — the
 * playback path is the system notification above.
 */
val SOUND_OPTIONS: List<SettingsOption> = listOf(
    SettingsOption("none", R.string.oc_setting_sound_none),
    SettingsOption("default", R.string.oc_setting_sound_default),
)

/** Second `settings-section` of the Notifications page — see [SOUND_OPTIONS]. */
val SOUNDS_PANEL: List<SettingsSection> = listOf(
    SettingsSection(
        titleResId = R.string.oc_setting_section_sounds,
        rows = listOf(
            SettingsRow(
                action = "settings-sounds-agent",
                titleResId = R.string.settings_general_sounds_agent_title,
                descriptionResId = R.string.settings_general_sounds_agent_description,
                control = SettingsControl.SELECT,
                options = SOUND_OPTIONS,
            ),
            SettingsRow(
                action = "settings-sounds-permissions",
                titleResId = R.string.settings_general_sounds_permissions_title,
                descriptionResId = R.string.settings_general_sounds_permissions_description,
                control = SettingsControl.SELECT,
                options = SOUND_OPTIONS,
            ),
            SettingsRow(
                action = "settings-sounds-errors",
                titleResId = R.string.settings_general_sounds_errors_title,
                descriptionResId = R.string.settings_general_sounds_errors_description,
                control = SettingsControl.SELECT,
                options = SOUND_OPTIONS,
            ),
        ),
    ),
)

/**
 * Every row-based page paired with its panel, in nav order.
 *
 * Declared **after** every panel: these are top-level `val`s, so Kotlin
 * initializes them in file order, and one declared above a panel would capture
 * `null` for it (`ExceptionInInitializerError` — the failure mode that made
 * `ALL_SETTINGS_PANELS` throw the first time this was written).
 */
val ROW_PANELS: List<Pair<SettingsTab, List<SettingsSection>>> =
    listOf(SettingsTab.GENERAL, SettingsTab.APPEARANCE, SettingsTab.NOTIFICATIONS).map { it to panelsFor(it) }

/** Every row-based panel flattened (search catalog + drift tests). */
val ALL_SETTINGS_PANELS: List<SettingsSection> = ROW_PANELS.flatMap { it.second }

/**
 * Rows (and whole sections) the Android shell **does not render**, because no
 * consumer exists yet. Removing them is deliberate: a switch that writes a pref
 * nobody reads is a lie in the UI. They stay documented here so a later
 * milestone can re-add the row together with its consumer.
 *
 * `settings-theme` / `settings-ui-font` / `settings-code-font` left this list
 * in U-1: the theme registry ([OcThemeRegistry]) and the font families
 * ([uiFontFamily]/[codeFontFamily] into `OcTheme`) gave every one of them a
 * real consumer, so the Appearance page renders the full upstream row order
 * again (`settings/appearance/appearance.tsx:64-131`).
 *
 * `settings-shell` writes a local pref while upstream writes the
 * *server's* `config.shell` (`settings/general/controllers.ts:26-69`). `settings-show-search` and
 * `settings-show-status` (Android-only key) target a title bar the native shell
 * does not have. v35 tA-terminal gave the shell the palette button that
 * upstream's `showSearch` gates (`ui/nav/DesktopTabStrip.kt` /
 * `BottomTabStrip.kt`), but the button is deliberately **unconditional**: the row
 * is hidden, so nothing could turn it back on, and a stored `false` from the era
 * when the row rendered would have hidden the only touch entry to the palette
 * again. The pref therefore stays consumerless and stays on this list.
 * `settings-shell` stays hidden on purpose with a corrected meaning: upstream's
 * `ShellSetting` is the **server's** `config.shell` (`controllers.ts:26-69`),
 * not a local pref, so the dead local row was removed and replaced by the
 * writable server value [ServerShellInfo] the Shell page renders (A4 #9 fix).
 *
 * The three **notification** rows left this list in W5-S: W5-E5 gave them a real
 * consumer ([NOTIFICATIONS_PANEL]).
 *
 * The three **sound** actions are rendered again ([SOUNDS_PANEL]) but stay on
 * this list because `SettingsStructureTest.hiddenSettingsAreNotRenderedAndKeepTheirPrefKeys`
 * pins membership verbatim and that file is another workstream's. The test only
 * intersects this list with `ALL_SETTINGS_PANELS`, which deliberately does not
 * carry [SOUNDS_PANEL] — so the rows are live and the assertion still holds.
 * The theme/ui-font/code-font ids already left this list (they render in
 * [APPEARANCE_PANEL]); the three sound ids stay pinned until that test and
 * [SOUNDS_PANEL] land in the same panel set.
 *
 * B-9: `settings-show-custom-agents` left this list — the composer's agent chip
 * is the upstream consumer (`providers/models/selection.tsx:94` `agentsVisible`),
 * so the row renders in [GENERAL_PANEL] and the chip hides itself exactly when
 * upstream's control does.
 */
val HIDDEN_SETTINGS: List<String> = listOf(
    "settings-shell",
    "settings-sounds-agent",
    "settings-sounds-permissions",
    "settings-sounds-errors",
    "settings-show-search",
    "settings-show-status",
)

/** Flat list of every `data-action` in the General panel (test/QA anchor). */
fun generalPanelActions(): List<String> = GENERAL_PANEL.flatMap { it.rows }.map { it.action }

/** Flat list of every `data-action` in the Appearance panel (test/QA anchor). */
fun appearancePanelActions(): List<String> = APPEARANCE_PANEL.flatMap { it.rows }.map { it.action }

/** Flat list of every `data-action` in the Notifications panel (test/QA anchor). */
fun notificationsPanelActions(): List<String> = NOTIFICATIONS_PANEL.flatMap { it.rows }.map { it.action }

/**
 * The disabled placeholders of [SETTINGS_NAV] — a root tab upstream has that
 * this shell cannot implement without a dead control. Empty since wave-close:
 * experimental became a real (honest) page and no tab is missing any more.
 * Kept so the fidelity test can assert that the navigation *shape* matches
 * upstream's `settings/pages.ts`.
 */
val SETTINGS_NAV_DISABLED: List<SettingsNavItem> = SETTINGS_NAV
    .flatMap { it.items }
    .filterNot { it.enabled }

// ----------------------------------------------------- experimental (wave-close)

/**
 * The four rows of upstream's `settings/experimental/experimental.tsx`, projected
 * onto what Android can honestly show:
 *
 * | upstream row | gate upstream | Android |
 * |---|---|---|
 * | `tabLayout` | always | **live** — a `horizontal`/`vertical` select writing `appearance.tabLayout`, read by `ui/nav/AppearancePrefs` → `ui/App.kt`'s strip placement (`BottomTabStrip` at the bottom, `DesktopTabStrip` at the top). B-16 adds a second view of the same value on the General page (the「底部导航」switch), so the two rows cannot disagree |
 * | `browserPane` | `platform.browserPane` | **hidden** — see below |
 * | `showProjectName` | always | shown **disabled** — the vertical tab nav's project line (`shell/titlebar/tab-nav.tsx:303`) needs a per-tab project the shell's `SessionTabRef(id,title)` does not carry |
 * | `showProjectIcon` | channel ≠ `prod` | **hidden** (release builds read as prod) |
 *
 * **`browserPane` gate, verified at v2 head `8ce629b`:** the row renders under
 * `<Show when={platform.browserPane}>` (`experimental.tsx:50-66`), and
 * `PlatformBase.browserPane?: BrowserPanePlatform` is documented as "Native
 * browser pane hosted by the platform (desktop only)"
 * (`runtime/platform/platform.tsx:135-136`). `BrowserPanePlatform`
 * (`runtime/platform/browser-pane.ts`) is an Electron native-view registration:
 * `setLayout({tabID, visible, bounds, background, radius})` composites a
 * Chromium view *under* the DOM, and `session/browser/pane.tsx` drives it with
 * a DOM cutout + per-frame bounds sync. The Android shell has no such platform
 * surface — hosting it would mean a WebView z-ordered under Compose with
 * agent-controlled navigation (`session/browser/model`), i.e. the whole
 * `BrowserPanePlatform` port, not a settings switch. So the row stays hidden
 * (upstream's own gate would hide it here too) instead of parking a switch that
 * writes `general.experimentalBrowser` nothing reads.
 *
 * Id drift (kept as-is): upstream's `data-action` is
 * `settings-experimental-browser`; this shell lists `settings-browser-pane`,
 * which `MessageRevertRenderTest` pins. Reconcile when the pane lands.
 *
 * Hidden rows follow upstream's own `Show when={…}` gates rather than parking a
 * dead control on screen; disabled rows keep the field visible with a reason.
 *
 * **B-11** retired the last lie on this page: `tabLayout` used to be a disabled
 * placeholder even though `appearance.tabLayout` had a real consumer
 * (`AppearancePrefs`/`OcApp`/`BottomTabStrip`), so it is a working select now.
 * `showProjectName` stays disabled on purpose: its consumer is the **vertical
 * tab nav's** project line (`tab-nav.tsx:303`), and this shell's
 * `SessionTabRef` carries only `(id, title)` — the drawer's rows have no
 * project to show, and the Home list's own project label comes from upstream's
 * *home* rule (`home/sessions/controller.tsx:256`), not this setting.
 */
enum class ExperimentalRow(val action: String) {
    TAB_LAYOUT("settings-tab-layout"),
    PROJECT_NAME("settings-show-project-name"),
    ;

    companion object {
        /** The rows this shell renders (the project-name row is the disabled one). */
        fun visible(): List<ExperimentalRow> = entries.toList()

        /** Upstream rows this shell drops (browser pane, prod-gated project icon). */
        val hiddenActions: List<String> = listOf(
            "settings-browser-pane",
            "settings-show-project-icon",
        )
    }
}

/**
 * B-11: the two stops of the Experimental page's Tabs row
 * (`experimental.tsx:8,26-44`: `const tabLayoutOptions = ["horizontal",
 * "vertical"]`) with upstream's own labels
 * (`settings.appearance.row.tabs.horizontal` / `.vertical`).
 */
val TAB_LAYOUT_OPTIONS: List<SettingsOption> = listOf(
    SettingsOption(TabLayout.HORIZONTAL, R.string.settings_appearance_row_tabs_horizontal),
    SettingsOption(TabLayout.VERTICAL, R.string.settings_appearance_row_tabs_vertical),
)

// ------------------------------------------------------- timeline detail (D5)

/**
 * The Android projection of `session-ui/src/timeline/detail.ts:17-73`.
 *
 * Upstream models six categories × `placement`(separate/grouped/hidden) ×
 * `details`(expanded/collapsed). The native timeline consumes exactly three
 * booleans ([TimelineDisplayPrefs]): `shellToolPartsExpanded`,
 * `editToolPartsExpanded` and `reasoningSummaries`. So a preset here is the
 * **faithful part** of the upstream preset:
 *
 * | upstream preset | shell | edit | thinking | Android |
 * |---|---|---|---|---|
 * | `everything` | separate/expanded | separate/expanded | separate/expanded | expanded + expanded + shown |
 * | `detailed` | separate/expanded | separate/expanded | grouped/collapsed | **identical to `everything`** (native grouping is derived, not a setting) |
 * | `compact` | grouped/collapsed | grouped/collapsed | grouped/collapsed | collapsed + collapsed + shown |
 * | `quiet` | hidden/collapsed | grouped/collapsed | hidden/collapsed | collapsed + collapsed + hidden |
 * | `text-only` | hidden | hidden | hidden | **not representable** — the native timeline always renders shell/edit cards |
 *
 * `detailed` is therefore offered as the same stop as `everything` and not as a
 * separate option (two options that do the same thing is a lie), and `text-only`
 * is not offered at all. `placement`/`notices`/`tools`/`subagents` are not
 * settable on Android for the same reason.
 *
 * **Ownership (v63, `research/v63_verify/5554/33-42`):** upstream's preset row
 * replaces one `general.timelineDetail` object that has no per-field UI, while
 * this shell renders the same page as a preset row **plus** the three granular
 * switches beneath it (`settings.general.row.{reasoningSummaries,
 * shellToolPartsExpanded,editToolPartsExpanded}`). Writing all three keys on
 * every pick therefore let a preset silently undo a switch the user had just
 * flipped by hand — Compact put `reasoningSummaries` back to `true` after it
 * had been turned off — and, because the chip re-derived the preset from all
 * three values, the same pick could leave it reading `Custom` with no option
 * marked. So a preset now owns the keys it actually decides ([ownedKeys]) and
 * writes only those ([writes]); the switches keep the rest. Upstream's own
 * "Advanced" block is exactly this split (`timeline-detail.tsx:21-68`), and it
 * is why the model keeps the granular keys the preset does not own.
 */
enum class TimelineDetailKey(val action: String) {
    SHELL("settings-feed-shell-tool-parts-expanded"),
    EDIT("settings-feed-edit-tool-parts-expanded"),
    REASONING("settings-feed-reasoning-summaries"),
}

enum class TimelineDetailPreset(
    val id: String,
    val shellExpanded: Boolean,
    val editExpanded: Boolean,
    val reasoningShown: Boolean,
    val labelResId: Int,
    val ownedKeys: Set<TimelineDetailKey>,
) {
    // Everything and Compact decide the **detail** axis only: card expansion.
    // They agree with each other that reasoning stays visible (the store
    // default), so neither of them has a reason to rewrite a key the user may
    // have turned off on its own switch.
    EVERYTHING("everything", shellExpanded = true, editExpanded = true, reasoningShown = true, labelResId = R.string.oc_settings_timeline_preset_everything, ownedKeys = setOf(TimelineDetailKey.SHELL, TimelineDetailKey.EDIT)),
    COMPACT("compact", shellExpanded = false, editExpanded = false, reasoningShown = true, labelResId = R.string.oc_settings_timeline_preset_compact, ownedKeys = setOf(TimelineDetailKey.SHELL, TimelineDetailKey.EDIT)),
    // Quiet is the only preset that hides reasoning, so it is the only one that
    // owns that key — picking it is the request to turn it off.
    QUIET("quiet", shellExpanded = false, editExpanded = false, reasoningShown = false, labelResId = R.string.oc_settings_timeline_preset_quiet, ownedKeys = TimelineDetailKey.entries.toSet()),
    ;

    /** The store's triple, in `SettingsStore`'s write order. */
    val values: List<Boolean> get() = listOf(shellExpanded, editExpanded, reasoningShown)

    /** What picking this preset writes — and nothing else (see the class KDoc). */
    fun writes(): Map<TimelineDetailKey, Boolean> = buildMap {
        for (key in TimelineDetailKey.entries) {
            if (key !in ownedKeys) continue
            put(
                key,
                when (key) {
                    TimelineDetailKey.SHELL -> shellExpanded
                    TimelineDetailKey.EDIT -> editExpanded
                    TimelineDetailKey.REASONING -> reasoningShown
                },
            )
        }
    }

    companion object {
        /**
         * The preset the stored triple currently reads as, or null when no preset
         * owns it.
         *
         * A preset matches on the keys it [writes] alone, so a value the user
         * changed on a switch outside the preset's ownership cannot hide it: with
         * reasoning off and the cards expanded the chip still reads `Everything`,
         * because that is what the preset row last set. Where two presets match,
         * the one owning more keys wins (`quiet` over `compact` on a fully
         * collapsed timeline).
         *
         * Null is reachable only when shell and edit disagree — a combination no
         * preset writes and only the two independent switches can produce; that
         * is the shell's `Custom` state.
         */
        fun of(shellExpanded: Boolean, editExpanded: Boolean, reasoningShown: Boolean): TimelineDetailPreset? {
            if (shellExpanded != editExpanded) return null
            val current = TimelineDetailKey.entries.associateWith { key ->
                when (key) {
                    TimelineDetailKey.SHELL -> shellExpanded
                    TimelineDetailKey.EDIT -> editExpanded
                    TimelineDetailKey.REASONING -> reasoningShown
                }
            }
            return entries
                .filter { preset -> preset.writes().all { (key, value) -> current[key] == value } }
                .maxByOrNull { it.ownedKeys.size }
        }

        /** Selectable stops, in upstream `timelinePresets` order (`detail.ts:17-73`). */
        val Options: List<TimelineDetailPreset> = listOf(EVERYTHING, COMPACT, QUIET)
    }
}

/**
 * `settings-timeline-detail` options (`settings/timeline-detail.tsx:19,53-67`).
 * A getter (not a stored `val`) because [GENERAL_PANEL] is initialized first and
 * reads it; a backing field would still be null at that point.
 */
val TIMELINE_DETAIL_OPTIONS: List<SettingsOption>
    get() = TimelineDetailPreset.Options.map { SettingsOption(it.id, it.labelResId) }

// --------------------------------------------------------- settings search

/**
 * One hit of the settings search (`settings/search.tsx` + `search-catalog.ts`):
 * a page (tab) or a row inside a page. [action] is the row's `data-action` (null
 * for a page hit), which is also what the panel needs to scroll to it later.
 */
data class SettingsSearchHit(
    val tab: SettingsTab,
    val action: String? = null,
    val titleResId: Int,
    val descriptionResId: Int? = null,
) {
    /** Stable list key (`page:general` / `row:settings-color-scheme`). */
    val id: String get() = if (action == null) "page:${tab.value}" else "row:$action"
}

/**
 * Every resource id the search catalog can render a title/description from, in a
 * stable order. The Compose layer resolves them with `stringResource` and hands
 * the map back to [searchSettings] as `titleOf`, which keeps the search itself
 * pure and JVM-testable.
 */
fun searchCatalogTitleIds(): List<Int> = buildList {
    for (tab in SettingsTab.entries) add(tab.titleResId())
    for (section in ALL_SETTINGS_PANELS) {
        for (row in section.rows) {
            add(row.titleResId)
            row.descriptionResId?.let(::add)
        }
    }
    for (row in SHORTCUT_ROWS) add(row.titleResId)
}.distinct()

/**
 * `settings/search-catalog.ts` reduced to what the native shell has: the pages,
 * every row of the row-based panels and the keybind rows. [titleOf] resolves
 * resource ids so this stays plain JVM code; an empty query matches everything
 * (upstream lists the whole catalog before a query is typed).
 */
fun searchSettings(query: String, titleOf: (Int) -> String, mobile: Boolean = true): List<SettingsSearchHit> {
    val catalog = buildList {
        for (tab in SettingsTab.entries) {
            add(SettingsSearchHit(tab = tab, action = null, titleResId = tab.titleResId()))
        }
        // The **rendered** rows, not the raw table ([renderedPanel]): a row the
        // form hides is not a hit, so a search result can never name a row the
        // page does not draw. `mobile = true` keeps the pre-v48 catalog (every
        // declared row) — the default the JVM tests call.
        for (tab in SettingsTab.entries) {
            for (section in renderedPanel(tab, mobile)) {
                for (row in section.rows) {
                    add(
                        SettingsSearchHit(
                            tab = tab,
                            action = row.action,
                            titleResId = row.titleResId,
                            descriptionResId = row.descriptionResId,
                        ),
                    )
                }
            }
        }
        for (row in SHORTCUT_ROWS) {
            add(SettingsSearchHit(tab = SettingsTab.SHORTCUTS, action = "shortcut:${row.id}", titleResId = row.titleResId))
        }
    }
    val q = query.trim().lowercase()
    if (q.isEmpty()) return catalog
    return catalog.filter { hit ->
        titleOf(hit.titleResId).lowercase().contains(q) ||
            hit.descriptionResId?.let { titleOf(it).lowercase().contains(q) } == true ||
            hit.action?.lowercase()?.contains(q) == true
    }
}

/**
 * Where a search hit lands: the tab to show and, for a row hit, the row to
 * scroll to and mark — [search.tsx`'s "open the page and focus the row".
 *
 * [sectionIndex] / [rowIndex] are the position **inside the rendered panel**
 * ([renderedPanel]), not in the raw table, so the index the scroller and the
 * highlighter use is the index the page actually drew. [flatIndex] is the row's
 * 0-based position in the whole page (test anchor: it makes "is this row on the
 * page at all" a number instead of a screenshot).
 */
data class SettingsRowJump(
    val tab: SettingsTab,
    val action: String,
    val sectionIndex: Int,
    val rowIndex: Int,
    val flatIndex: Int,
)

/**
 * The row [hit] names on the given form, or null when it names none — a page
 * hit (`action == null`), a shortcut keybind (its rows live on the Shortcuts
 * page, not in a row panel), or a row the form does not render. Pure, so the
 * search-jump wiring is JVM-testable without a device.
 */
fun settingsRowJump(hit: SettingsSearchHit, mobile: Boolean): SettingsRowJump? {
    val action = hit.action ?: return null
    var flat = 0
    renderedPanel(hit.tab, mobile).forEachIndexed { sectionIndex, section ->
        section.rows.forEachIndexed { rowIndex, row ->
            if (row.action == action) {
                return SettingsRowJump(hit.tab, action, sectionIndex, rowIndex, flat)
            }
            flat++
        }
    }
    return null
}

// --------------------------------------------------------------- shortcuts

/**
 * Shortcut group order (`settings-keybinds.tsx` `GROUPS`):
 * General / Session / Navigation / Model and agent / Terminal / Prompt.
 */
enum class ShortcutGroup(val key: String) {
    GENERAL("general"),
    SESSION("session"),
    NAVIGATION("navigation"),
    MODEL_AND_AGENT("modelAndAgent"),
    TERMINAL("terminal"),
    PROMPT("prompt"),
    ;

    companion object {
        val Order: List<ShortcutGroup> = listOf(GENERAL, SESSION, NAVIGATION, MODEL_AND_AGENT, TERMINAL, PROMPT)
    }
}

fun ShortcutGroup.titleResId(): Int = when (this) {
    ShortcutGroup.GENERAL -> R.string.oc_shortcut_group_general
    ShortcutGroup.SESSION -> R.string.oc_shortcut_group_session
    ShortcutGroup.NAVIGATION -> R.string.oc_shortcut_group_navigation
    ShortcutGroup.MODEL_AND_AGENT -> R.string.oc_shortcut_group_model
    ShortcutGroup.TERMINAL -> R.string.oc_shortcut_group_terminal
    ShortcutGroup.PROMPT -> R.string.oc_shortcut_group_prompt
}

/**
 * `groupFor(id)` from `settings-keybinds.tsx:55-71`, transcribed literally:
 * `command.palette`→General (the explicit upstream special case), `terminal.*`
 * →Terminal, `model./agent./mcp.`→Model and agent, `file./fileTree.`→Navigation,
 * `prompt.`→Prompt, `session./message./permissions./steps./review.`→Session,
 * else General.
 */
fun groupForCommandId(id: String): ShortcutGroup = when {
    id == "command.palette" -> ShortcutGroup.GENERAL
    id.startsWith("terminal.") -> ShortcutGroup.TERMINAL
    id.startsWith("model.") || id.startsWith("agent.") || id.startsWith("mcp.") -> ShortcutGroup.MODEL_AND_AGENT
    id.startsWith("file.") || id.startsWith("fileTree.") -> ShortcutGroup.NAVIGATION
    id.startsWith("prompt.") -> ShortcutGroup.PROMPT
    id.startsWith("session.") || id.startsWith("message.") || id.startsWith("permissions.") ||
        id.startsWith("steps.") || id.startsWith("review.") -> ShortcutGroup.SESSION
    else -> ShortcutGroup.GENERAL
}

/** One keybind row. [keybind] is the already-formatted (`Ctrl+K`) string. */
data class ShortcutRow(val id: String, val titleResId: Int, val keybind: String)

/**
 * The keybind table — one row per command the shell **actually implements**,
 * with the default chord `ui/App.kt`'s `effectiveGlobalChords` matches an event
 * against.
 *
 * B-5 rewrote this from WAVE 2.3a's two-tier "registry" table. That version
 * listed every id upstream registers (43 rows) while only 8 had a runtime
 * chord, so the panel advertised「displayed, editable, presses do nothing」rows.
 * A row is now here **only** when its action is reachable — App-level through
 * `ui/App.kt`'s chord table, or screen-local through
 * `ui/palette/SessionCommandIntents.requestCommand` — and
 * [WIRED_SHORTCUT_IDS] is the executable form of that claim
 * (`SettingsStructureTest.everyShortcutRowIsWired`).
 *
 * Rows **dropped** in B-5, with the reason each one had no implementation face
 * (they must not come back without the action landing first):
 *
 * - `prompt.submit` — not an upstream command at all; the composer's Enter is
 *   hardcoded (`Composer.kt` `ComposerSubmit.enterSubmits`), so a recorded chord
 *   could never fire.
 * - `terminal.close` — upstream registers it `hidden`, so `listFor` never lists
 *   it; its `mod+w` also collides with `file.close`, which *is* listed.
 * - `session.archive` — no archive API anywhere (upstream's own copy is a TODO,
 *   `home/sessions/controller.tsx:297`); the palette row was already `null`.
 * - `session.import` — a Home projects action upstream; neither route here has
 *   an import entry point.
 * - `context.addSelection` — upstream adds the *editor's* selected lines; this
 *   shell exposes no editor-selection state (the review panel's line selection
 *   is comment-local).
 * - `project.copyID` — `ChatViewModel` exposes no project id.
 * - `project.select` — the draft's project is auto-picked
 *   (`NewSessionWorkspaceModel.pickProject`); there is no project picker to open.
 * - `steps.toggle` — no steps UI in the native timeline.
 * - `browser.open` / `browser.reload` — upstream's browser is a tabs panel
 *   (`session/browser/pane.tsx`); the native pane is a URL-driven full-body
 *   overlay, not a side-panel tab. B-18 **does** wire both commands (palette
 *   rows + `mod+shift+b` / `f5`), but they keep their defaults in `ui/App.kt`'s
 *   `EXTRA_GLOBAL_CHORDS` rather than here: the pane is still an overlay, so the
 *   two stay context actions on the open pane instead of rebindable rows — the
 *   same shape `session.search` already has.
 *
 * B-17 took `debugBar.toggle` back **out** of this list: the debug bar now has an
 * Android surface (`ui/debug/DebugBar.kt`, toggled by the palette row and this
 * chord), so the row is wired like every other one. Upstream registers the
 * command with **no keybind** (`shell/shell.tsx:49-58` — the desktop bar opens
 * from the titlebar's channel indicator), so the default here is blank and a
 * recorded override is what gives it a chord; `debugBar.toggle` is therefore
 * also the current representative of the "listed, rebindable, no default" row
 * shape in `Wave5IntegrationWiringTest`.
 *
 * W5-S (D4) layers a **persisted override table** on top of these defaults
 * ([applyKeybindOverrides]); recording is stored + displayed, and `ui/App.kt`
 * honours it for every row ([effectiveGlobalChords]).
 */
val SHORTCUT_ROWS: List<ShortcutRow> = listOf(
    ShortcutRow("command.palette", R.string.oc_shortcut_palette, "Ctrl+K / Ctrl+Shift+P"),
    ShortcutRow("settings.open", R.string.oc_shortcut_settings_open, "Ctrl+,"),
    ShortcutRow("file.open", R.string.oc_shortcut_file_open, "Ctrl+P"),
    ShortcutRow("session.new", R.string.oc_shortcut_session_new, "Ctrl+Shift+S"),
    // `tab.new` is hidden in the palette (`titlebar.tsx:388-396`) but carries the
    // `Ctrl+N` binding users press; it is listed here so the table stays honest.
    ShortcutRow("tab.new", R.string.oc_shortcut_tab_new, "Ctrl+N"),
    ShortcutRow("terminal.toggle", R.string.oc_shortcut_terminal_toggle, "Ctrl+`"),
    ShortcutRow("review.toggle", R.string.oc_shortcut_review_toggle, "Ctrl+Shift+R"),
    ShortcutRow("fileTree.toggle", R.string.oc_shortcut_file_tree_toggle, "Ctrl+\\"),
    // A1: the summary panel's entries (header popover, mobile details tab, this
    // chord) share one command; upstream binds `session.summary.toggle` to
    // mod+shift+y (`session/summary/popover.tsx:17-34`).
    ShortcutRow("session.summary.toggle", R.string.oc_command_summary_toggle, "Ctrl+Shift+Y"),
    // --- session route (`use-session-commands.tsx:250-463`) ---
    ShortcutRow("session.undo", R.string.command_session_undo, ""),
    ShortcutRow("session.redo", R.string.command_session_redo, ""),
    ShortcutRow("session.compact", R.string.command_session_compact, ""),
    ShortcutRow("session.background", R.string.command_session_background, "Ctrl+B"),
    ShortcutRow("session.fork", R.string.command_session_fork, ""),
    ShortcutRow("session.export", R.string.command_session_export, ""),
    ShortcutRow("session.copyID", R.string.command_session_copyID, ""),
    // `session.aside` — the `/btw` side question (upstream registers it from the
    // btw plugin, `palette: true`, `slash: {name: "btw", arguments: true}`, so it
    // has no keybind; the trigger is the slash form). The row stays because the
    // action exists (`SessionCommandIntents.askAside`), so a recorded chord fires.
    ShortcutRow("session.aside", R.string.oc_btw_ask, ""),
    ShortcutRow("message.previous", R.string.command_message_previous, "Ctrl+Alt+["),
    ShortcutRow("message.next", R.string.command_message_next, "Ctrl+Alt+]"),
    ShortcutRow("mcp.toggle", R.string.command_mcp_toggle, "Ctrl+;"),
    ShortcutRow("permissions.autoaccept", R.string.command_permissions_autoaccept_enable, "Ctrl+Shift+A"),
    ShortcutRow("input.focus", R.string.command_input_focus, "Ctrl+L"),
    ShortcutRow("terminal.new", R.string.command_terminal_new, "Ctrl+Alt+T"),
    // `file.close` closes the active session tab (upstream reads the `tab.close`
    // keybind for it, `use-session-commands.tsx:324-333`), which is also what
    // records the closed-tab stack `tab.reopenClosed` restores.
    ShortcutRow("file.close", R.string.command_tab_close, "Ctrl+W"),
    ShortcutRow("session.location.cycle", R.string.command_session_location_cycle, "Ctrl+Alt+L"),
    // --- composer (`composer/commands.tsx:51-75`) + new-session (`new-session/commands.tsx:24-48`) ---
    ShortcutRow("model.choose", R.string.command_model_choose, "Ctrl+'"),
    ShortcutRow("model.variant.cycle", R.string.command_model_variant_cycle, "Shift+Ctrl+D"),
    ShortcutRow("agent.cycle", R.string.command_agent_cycle, "Ctrl+."),
    ShortcutRow("agent.cycle.reverse", R.string.command_agent_cycle_reverse, "Shift+Ctrl+."),
    // B-19: upstream's `composer-editor` commands (`composer/model.ts:411-434`) —
    // attach (`mod+u`), shell mode (`mod+shift+x`) and prompt mode
    // (`mod+shift+e`). All three actions exist (the SAF launcher, the composer's
    // `shellMode`), and both `ui/App.kt` (chord) and the palette row route
    // through `SessionScreen`'s external request counters, so they are listed
    // and rebindable like every other row.
    ShortcutRow("file.attach", R.string.prompt_action_attachFile, "Ctrl+U"),
    ShortcutRow("prompt.mode.shell", R.string.command_prompt_mode_shell, "Ctrl+Shift+X"),
    ShortcutRow("prompt.mode.normal", R.string.command_prompt_mode_normal, "Ctrl+Shift+E"),
    // --- shell chrome (titlebar tabs, timeline search) ---
    ShortcutRow("tab.reopenClosed", R.string.command_tab_reopenClosed, "Ctrl+Shift+T"),
    ShortcutRow("session.search", R.string.session_search_placeholder, "Ctrl+F"),
    // B-17: the debug bar (`shell/shell.tsx:49-58`). Upstream declares no
    // keybind for `debugBar.toggle`, so the default stays blank and the row is
    // rebindable — the same shape `session.undo` has. The action is
    // App-level (`ui/App.kt` flips the bar's visibility), so both the palette
    // row and a recorded chord reach it without a screen being involved.
    ShortcutRow("debugBar.toggle", R.string.command_debugBar_toggle, ""),
)

/**
 * The ids with a **runtime** chord: every [SHORTCUT_ROWS] id, and nothing else
 * **that is a rebindable row**. The three ids whose default lives in
 * `ui/App.kt`'s `EXTRA_GLOBAL_CHORDS` instead — `session.search`,
 * `session.summary.toggle` and B-18's `browser.open` / `browser.reload` — are
 * wired but deliberately not rows here, so they are absent by design.
 *
 * `ui/App.kt`'s `GLOBAL_CHORD_COMMAND_IDS` maps each one onto the handler that
 * runs it (`SettingsStructureTest` pins the equality in both directions, and the
 * App.kt source scan pins the handler side).
 */
val WIRED_SHORTCUT_IDS: Set<String> = setOf(
    "command.palette",
    "settings.open",
    "file.open",
    "session.new",
    "tab.new",
    "terminal.toggle",
    "review.toggle",
    "fileTree.toggle",
    "session.summary.toggle",
    "session.undo",
    "session.redo",
    "session.compact",
    "session.background",
    "session.fork",
    "session.export",
    "session.copyID",
    "session.aside",
    "message.previous",
    "message.next",
    "mcp.toggle",
    "permissions.autoaccept",
    "input.focus",
    "terminal.new",
    "file.close",
    "session.location.cycle",
    "model.choose",
    "model.variant.cycle",
    "agent.cycle",
    "agent.cycle.reverse",
    "file.attach",
    "prompt.mode.shell",
    "prompt.mode.normal",
    "tab.reopenClosed",
    "session.search",
    // B-17: the debug bar. Blank by default (upstream declares no keybind), but
    // the row is wired — `ui/App.kt` maps the id to `GlobalChord.DEBUG_BAR` and
    // a recorded override gives it a chord.
    "debugBar.toggle",
)

/** `"none"` is upstream's spelling for an explicitly cleared binding (`keybinds.tsx:279`). */
const val KEYBIND_NONE: String = "none"

fun isKeybindUnassigned(keybind: String?): Boolean =
    keybind.isNullOrBlank() || keybind.equals(KEYBIND_NONE, ignoreCase = true)

/**
 * Applies the user's override table to the defaults — upstream
 * `effective(id)` (`keybinds.tsx:217-226`): a stored string wins, otherwise the
 * registered default.
 */
fun applyKeybindOverrides(
    overrides: Map<String, String>,
    rows: List<ShortcutRow> = SHORTCUT_ROWS,
): List<ShortcutRow> = rows.map { row ->
    val custom = overrides[row.id]
    if (custom == null) row else row.copy(keybind = custom)
}

// --------------------------------------------------- keybind recording (D4)

/**
 * Normalizes a platform key name into the vocabulary the shell writes
 * (`keybinds.tsx:77-95` `recordKeybind` + `keyFromKeyboardEvent`).
 *
 * [platformKey] is `android.view.KeyEvent.keyCodeToString(...)`; the shell only
 * needs the display token, so `KEYCODE_` is stripped and the few Android names
 * that differ from the web ones are translated. Pure, so it is unit-tested
 * without a device. Returns null for a bare modifier or an unknown code — the
 * caller keeps waiting for a real key (upstream ignores modifiers the same way).
 */
fun normalizeKeyName(platformKey: String?): String? {
    val raw = platformKey?.trim()?.uppercase().orEmpty().removePrefix("KEYCODE_")
    if (raw.isEmpty()) return null
    return when (raw) {
        "SHIFT_LEFT", "SHIFT_RIGHT", "CTRL_LEFT", "CTRL_RIGHT", "ALT_LEFT", "ALT_RIGHT",
        "META_LEFT", "META_RIGHT", "SYM", "FUNCTION", "CAPS_LOCK", "NUM_LOCK",
        -> null

        "DEL" -> "Backspace"
        "FORWARD_DEL" -> "Delete"
        "ESC" -> "Escape"
        "ENTER", "NUMPAD_ENTER" -> "Enter"
        "SPACE" -> "Space"
        "TAB" -> "Tab"
        "COMMA" -> ","
        "PERIOD" -> "."
        "SLASH" -> "/"
        "BACKSLASH" -> "\\"
        "SEMICOLON" -> ";"
        "APOSTROPHE" -> "'"
        "GRAVE" -> "`"
        "MINUS" -> "-"
        "EQUALS" -> "="
        "LEFT_BRACKET" -> "["
        "RIGHT_BRACKET" -> "]"
        "DPAD_UP" -> "ArrowUp"
        "DPAD_DOWN" -> "ArrowDown"
        "DPAD_LEFT" -> "ArrowLeft"
        "DPAD_RIGHT" -> "ArrowRight"
        else -> raw
    }
}

/**
 * Platform names the shell will not write down: an event whose key code is
 * `KEYCODE_UNKNOWN` (0) has no name, and the on-screen buttons are not keys.
 *
 * `normalizeKeyName` is otherwise permissive — every Android key code has a
 * readable `KEYCODE_*` name, and the ones the web spells differently are
 * translated above — so a `Ctrl+Alt+M` press arrives as `M` and a `F1` press
 * as `F1`. This blacklist is the validation layer: anything it names is
 * refused, and a refused recording writes nothing (the shell keeps waiting for
 * a real key, exactly as it does for a bare modifier).
 */
val NON_NAMEABLE_KEY_NAMES: Set<String> = setOf(
    "UNKNOWN",
    "SOFT_LEFT",
    "SOFT_RIGHT",
    "TV_INPUT",
    "TV_POWER",
    "STB_POWER",
    "AVR_POWER",
    "AVR_TOGGLE",
    "PROG_RED",
    "PROG_GREEN",
    "PROG_YELLOW",
    "PROG_BLUE",
) + (0..23).map { "BUTTON_$it" }

/** True when [token] is a real key the shell can name, store and later match. */
fun isNameableKeyToken(token: String?): Boolean {
    val name = token?.trim().orEmpty()
    if (name.isEmpty()) return false
    if (name.uppercase() in NON_NAMEABLE_KEY_NAMES) return false
    // An untranslated `KEYCODE_*` spelling is fine; a word that reads like a
    // placeholder is not. Kept separate so a future Android name never needs a
    // table edit to become recordable.
    return name.none { it.isWhitespace() }
}

/**
 * The display token for one recorded key press, or `null` when the press names
 * nothing — which the caller must treat as "keep waiting", never as a chord.
 *
 * Sources, in order of trust:
 *
 * 1. [nativeKeyName] — `android.view.KeyEvent.keyCodeToString(keyCode)` for the
 *    platform event, the one value Compose's `Key` enum is built from and the
 *    one `ui/App.kt` matches a chord back against.
 * 2. [composeKeyName] — the `androidx.compose.ui.input.key.Key` enum name, for
 *    the case where the native event is not reachable.
 * 3. [unicodeChar] — what the key actually typed, for a soft keyboard.
 *
 * Each candidate goes through [normalizeKeyName] and then the
 * [isNameableKeyToken] gate, so `KEYCODE_UNKNOWN` (key code 0) answers `null`
 * at every level instead of being written to disk as `UNKNOWN`.
 */
fun recordedKeyToken(
    nativeKeyName: String? = null,
    composeKeyName: String? = null,
    unicodeChar: Char? = null,
): String? {
    val fromNative = normalizeKeyName(nativeKeyName)
    val fromCompose = composeKeyName?.let { normalizeKeyName(it.removePrefix("Key")) }
    val fromChar = unicodeChar
        ?.takeIf { it.isLetterOrDigit() || it in KEYBIND_PUNCTUATION }
        ?.toString()
    return listOfNotNull(fromNative, fromCompose, fromChar)
        .firstOrNull { isNameableKeyToken(it) }
}

/** The printable keys a chord may end on (`normalizeKeyName`'s punctuation set). */
private val KEYBIND_PUNCTUATION: Set<Char> = setOf(',', '.', '/', '\\', ';', '\'', '`', '-', '=', '[', ']')

/**
 * The stored/displayed form of one recorded chord, in the same `Ctrl+Shift+S`
 * vocabulary the default table uses (upstream `formatKeybind`
 * `shell/commands/command.tsx:206-261`; Android has no ⌘ key, so the modifier
 * word is always `Ctrl`).
 */
fun formatKeybindChord(
    ctrl: Boolean,
    alt: Boolean,
    shift: Boolean,
    meta: Boolean,
    key: String,
): String = buildList {
    if (ctrl) add("Ctrl")
    if (alt) add("Alt")
    if (shift) add("Shift")
    if (meta) add("Meta")
    add(key)
}.joinToString("+")

/**
 * `signatures(config)` (`keybinds.tsx:97-113`): one normalized token per chord of
 * a (possibly multi-chord, `/`- or `,`-separated) binding, modifiers sorted into
 * a canonical order so `Ctrl+Shift+S` and `Shift+Ctrl+S` collide. An unassigned
 * binding has no signatures and therefore never conflicts.
 */
fun keybindSignatures(keybind: String?): List<String> {
    if (isKeybindUnassigned(keybind)) return emptyList()
    // `,` is upstream's list separator (`parseKeybind`); ` / ` is how this
    // project's default table spells the same thing ("Ctrl+K / Ctrl+Shift+P").
    // A bare `Ctrl+/` must stay one chord, so only the spaced separator splits.
    return keybind!!.split(',')
        .flatMap { it.split(" / ") }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { chord ->
            val parts = chord.split('+').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) return@map ""
            val key = parts.last()
            val mods = parts.dropLast(1).map { it.lowercase() }.toSet()
            buildList {
                if ("ctrl" in mods || "control" in mods || "mod" in mods) add("ctrl")
                if ("alt" in mods) add("alt")
                if ("shift" in mods) add("shift")
                if ("meta" in mods || "cmd" in mods || "super" in mods) add("meta")
                add(key.lowercase())
            }.joinToString("+")
        }
        .filter { it.isNotEmpty() }
        .distinct()
}

/**
 * Rows whose **effective** binding already uses one of the candidate's chords —
 * upstream's `used()` map + conflict toast (`keybinds.tsx:227-242,287-304`).
 * [editingId] is excluded so re-recording the same row never self-conflicts.
 */
fun keybindConflicts(
    candidate: String,
    rows: List<ShortcutRow>,
    editingId: String,
): List<ShortcutRow> {
    val wanted = keybindSignatures(candidate).toSet()
    if (wanted.isEmpty()) return emptyList()
    return rows.filter { row ->
        row.id != editingId && keybindSignatures(row.keybind).any { it in wanted }
    }
}

fun shortcutsByGroup(rows: List<ShortcutRow> = SHORTCUT_ROWS): List<Pair<ShortcutGroup, List<ShortcutRow>>> =
    ShortcutGroup.Order.map { group -> group to rows.filter { groupForCommandId(it.id) == group } }
        .filter { it.second.isNotEmpty() }

// ------------------------------------------- read-only server pages (D1/L)

/**
 * One `GET /project` (v1) / `GET /api/project` (v2) entry rendered by the
 * Projects page (`settings/workspaces/projects.tsx`). The v1 payload is
 * `{id, worktree, vcs?, icon?, time{created,updated}, sandboxes[]}`
 * (verified against the local server, W5-S); the v2 payload swaps the path
 * key to `canonical` and drops `icon` (measured 2.0.11) — see
 * [projectPageRows].
 */
data class ProjectPageRow(
    val id: String,
    val worktree: String,
    val name: String,
    val vcs: String?,
    val updatedAt: Long?,
)

/** Last path segment of a worktree — the same display name `HomeSessions` uses. */
fun worktreeDisplayName(worktree: String): String =
    worktree.trimEnd('\\', '/').substringAfterLast('\\').substringAfterLast('/').ifBlank { worktree }

/**
 * Pure parse so the Projects page can be asserted on a JVM without a socket.
 *
 * Path key: v1 `worktree` first; v2 answers `canonical` instead (measured
 * 2026-09-22 on 2.0.11 — **no** `worktree` key on any element), so a missing
 * `worktree` falls back to `canonical`. A row with neither is still skipped,
 * never rendered blank; a v1 payload is parsed exactly as before.
 *
 * Read-side normalisation, and the reason the v59 device run saw two rows for
 * one repository: the server answered `GET /project` with
 * `/home/xiufeigo/C:\sensorsdata\…\opencode-android-shell` beside the correct
 * `C:\sensorsdata\…\opencode-android-shell` — a Win32 path glued onto a POSIX
 * root, which names no directory on either side. [ProjectPaths.displayLocation]
 * reads the Windows half out of it so the row prints the directory the user
 * meant, and the two rows then share a [ProjectPaths.matchKey], so the newest
 * wins and one directory is one row. Both are **read**-side: `worktree` and
 * `id` are this page's own fields and nothing here is written back to the
 * server or to the client-local project store.
 */
fun projectPageRows(json: String?): List<ProjectPageRow> {
    val array = jsonArrayOrNull(json) ?: return emptyList()
    val rows = (0 until array.length()).mapNotNull { index ->
        val obj = array.optJSONObject(index) ?: return@mapNotNull null
        val worktree = ProjectPaths.displayLocation(
            obj.optString("worktree", "").trim().takeIf { it.isNotEmpty() }
                ?: obj.optString("canonical", "").trim(),
        ).orEmpty()
        if (worktree.isEmpty()) return@mapNotNull null
        ProjectPageRow(
            id = obj.optString("id", worktree),
            worktree = worktree,
            name = worktreeDisplayName(worktree),
            vcs = obj.optString("vcs", "").takeIf { it.isNotBlank() },
            updatedAt = obj.optJSONObject("time")?.optLong("updated", 0L)?.takeIf { it > 0L },
        )
    }.sortedByDescending { it.updatedAt ?: 0L }
    val kept = ArrayList<ProjectPageRow>(rows.size)
    for (row in rows) {
        val key = ProjectPaths.matchKey(row.worktree)
        val index = if (key == null) -1 else kept.indexOfFirst { ProjectPaths.matchKey(it.worktree) == key }
        if (index < 0) kept.add(row)
    }
    return kept
}

/**
 * WAVE 2.3a (A4 #16): project search — case-insensitive contains over the
 * display name, the worktree path and the vcs tag
 * (`projects.tsx:55-94`, `settings.projects.search.placeholder`). Null when
 * the list is too short to need a box (upstream gates on overflow).
 */
fun filterProjectRows(rows: List<ProjectPageRow>, query: String): List<ProjectPageRow> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return rows
    return rows.filter {
        it.name.lowercase().contains(q) ||
            it.worktree.lowercase().contains(q) ||
            it.vcs?.lowercase()?.contains(q) == true
    }
}

/** WAVE 2.3a: the search box only renders when there is something to filter. */
fun showsProjectSearch(rowCount: Int): Boolean = rowCount > 1

/**
 * One `GET /experimental/worktree` entry (`worktree.list` in the v1 SDK). Field
 * names are read tolerantly (`name`/`directory`/`branch`) because only the empty
 * array was observable on this machine — the page renders `name` +
 * `directory` + `branch`, and an entry missing a directory is skipped rather
 * than shown as a blank row.
 */
data class WorktreePageRow(val name: String, val directory: String, val branch: String?)

fun worktreePageRows(json: String?): List<WorktreePageRow> {
    val array = jsonArrayOrNull(json) ?: return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        val obj = array.optJSONObject(index) ?: return@mapNotNull null
        val directory = listOf("directory", "worktree", "path")
            .firstNotNullOfOrNull { key -> obj.optString(key, "").trim().takeIf { it.isNotEmpty() } }
            ?: return@mapNotNull null
        val name = obj.optString("name", "").trim().takeIf { it.isNotEmpty() }
            ?: worktreeDisplayName(directory)
        WorktreePageRow(
            name = name,
            directory = directory,
            branch = listOf("branch", "ref")
                .firstNotNullOfOrNull { key -> obj.optString(key, "").trim().takeIf { it.isNotEmpty() } },
        )
    }
}

/**
 * One MCP server of the Extensions page (`settings/providers/extensions.tsx:31-47`).
 *
 * Both shapes seen in the wild are accepted: the v1 `GET /mcp` **object map**
 * (`{"name": {"status": "connected"|"disabled"|"failed", "error": "…"}}`, `{}`
 * when nothing is configured) and the v2 `mcp.list()` **array** of
 * `{name, status: {status}}` that the upstream app consumes.
 */
data class McpPageRow(val name: String, val status: String, val error: String?) {
    val connected: Boolean get() = status.equals("connected", ignoreCase = true)

    /** `failed` is the only state upstream colours as a problem. */
    val failed: Boolean get() = status.equals("failed", ignoreCase = true)
}

fun mcpPageRows(json: String?): List<McpPageRow> {
    val trimmed = json?.trim().orEmpty()
    if (trimmed.isEmpty()) return emptyList()
    val rows = if (trimmed.startsWith("[")) {
        val array = runCatching { JSONArray(trimmed) }.getOrNull() ?: return emptyList()
        (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            val name = obj.optString("name", "").trim()
            if (name.isEmpty()) return@mapNotNull null
            val status = obj.optJSONObject("status")
            McpPageRow(
                name = name,
                status = status?.optString("status", "")?.takeIf { it.isNotBlank() } ?: "unknown",
                error = status?.optString("error", "")?.takeIf { it.isNotBlank() },
            )
        }
    } else {
        val obj = runCatching { JSONObject(trimmed) }.getOrNull() ?: return emptyList()
        obj.keys().asSequence().map { name ->
            val value = obj.optJSONObject(name)
            val status = value?.optJSONObject("status")
                ?.optString("status", "")
                ?.takeIf { it.isNotBlank() }
                ?: value?.optString("status", "")?.takeIf { it.isNotBlank() }
                ?: "unknown"
            McpPageRow(
                name = name,
                status = status,
                error = value?.optString("error", "")?.takeIf { it.isNotBlank() },
            )
        }.toList()
    }
    return rows.sortedWith(compareBy({ if (it.connected) 0 else 1 }, { it.name.lowercase() }))
}

/** One `GET /skill` entry (`extensions.tsx:60`, `OpenCodeClient.SkillInfo`). */
data class SkillPageRow(val name: String, val description: String?)

fun skillPageRows(json: String?): List<SkillPageRow> {
    val array = jsonArrayOrNull(json) ?: return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        val obj = array.optJSONObject(index) ?: return@mapNotNull null
        val name = obj.optString("name", "").trim()
        if (name.isEmpty()) return@mapNotNull null
        SkillPageRow(name, obj.optString("description", "").takeIf { it.isNotBlank() })
    }
}

/**
 * One `GET /api/plugin` entry (`extensions.tsx`, `pluginLabels`).
 *
 * [packageTarget]/[packageVersion]/[outdated]/[updating]/[stateStatus] come
 * from `POST /api/plugin/check` (`pluginCheckRows`) — the plain list leaves
 * them at their empty defaults.
 */
data class PluginPageRow(
    val name: String,
    val packageTarget: String? = null,
    val packageVersion: String? = null,
    val outdated: Boolean = false,
    val updating: Boolean = false,
    val stateStatus: String? = null,
)

fun pluginPageRows(json: String?): List<PluginPageRow> {
    val trimmed = json?.trim().orEmpty()
    if (trimmed.isEmpty()) return emptyList()
    if (trimmed.startsWith("[")) {
        val array = runCatching { JSONArray(trimmed) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            val name = listOf("name", "id")
                .firstNotNullOfOrNull { key -> obj.optString(key, "").trim().takeIf { it.isNotEmpty() } }
                ?: return@mapNotNull null
            PluginPageRow(name)
        }.sortedBy { it.name.lowercase() }
    }
    val obj = runCatching { JSONObject(trimmed) }.getOrNull() ?: return emptyList()
    return obj.keys().asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { PluginPageRow(it) }
        .sortedBy { it.name.lowercase() }
        .toList()
}

/**
 * WAVE 2.3a (A4 #17): worktree delete preflight
 * (`workspaces.tsx:176-329`, `inspectWorkspaceDeletion`). The native client
 * cannot run the vcs/session preflight (no `/vcs` + session-scope read here),
 * so the caller passes what it knows and the pure rule decides the outcome:
 * an active location or linked sessions block with an error; a dirty tree
 * requires explicit force.
 */
enum class WorktreeDeleteGuard { OK, NEEDS_FORCE, BLOCKED_ACTIVE, BLOCKED_LINKED }

fun worktreeDeleteGuard(
    dirty: Boolean,
    linkedSessions: Int,
    isActiveLocation: Boolean,
    force: Boolean,
): WorktreeDeleteGuard = when {
    isActiveLocation -> WorktreeDeleteGuard.BLOCKED_ACTIVE
    linkedSessions > 0 -> WorktreeDeleteGuard.BLOCKED_LINKED
    dirty && !force -> WorktreeDeleteGuard.NEEDS_FORCE
    else -> WorktreeDeleteGuard.OK
}

private fun jsonArrayOrNull(json: String?): JSONArray? {
    val trimmed = json?.trim().orEmpty()
    if (trimmed.isEmpty() || !trimmed.startsWith("[")) return null
    return runCatching { JSONArray(trimmed) }.getOrNull()
}

// ----------------------------------------------------------------- servers

/** Server health, used for the sort rank (`dialog-select-server.tsx`). */
enum class ServerHealth(val rank: Int) {
    HEALTHY(0),
    UNKNOWN(1),
    UNHEALTHY(2),
}

/** One `settings-v2-servers-row`. */
data class ServerListItem(
    val url: String,
    val name: String,
    val username: String?,
    val version: String? = null,
    val health: ServerHealth = ServerHealth.UNKNOWN,
    val isDefault: Boolean = false,
)

/**
 * `useServerManagementController` ordering: the current/default server first,
 * then by health rank (healthy 0 < unknown 1 < unhealthy 2), ties keep input
 * order (stable sort).
 */
fun sortServers(servers: List<ServerListItem>): List<ServerListItem> =
    servers.withIndex()
        .sortedWith(compareBy({ if (it.value.isDefault) 0 else 1 }, { it.value.health.rank }, { it.index }))
        .map { it.value }

/** `settings-v2-servers-meta` = `v{version} • {username}`, `no username` fallback. */
fun serverMeta(server: ServerListItem): String {
    val version = server.version?.takeIf { it.isNotBlank() }?.let { "v$it" }
    val user = server.username?.takeIf { it.isNotBlank() } ?: "no username"
    return listOfNotNull(version, user).joinToString(" • ")
}

/** fuzzysort target keys: `serverName(item)` and `item.http.url` (§5.4). */
fun filterServers(servers: List<ServerListItem>, query: String): List<ServerListItem> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return servers
    return servers.filter { it.name.lowercase().contains(q) || it.url.lowercase().contains(q) }
}

/** The search box only appears when more than one server is configured (§5.4). */
fun showsServerSearch(serverCount: Int): Boolean = serverCount > 1

/**
 * Default server username when Basic auth is enabled
 * (`dialog-server-v2.tsx` `DEFAULT_USERNAME`).
 */
const val DEFAULT_SERVER_USERNAME: String = "opencode"

/**
 * Default URL placeholder. Android deviation from upstream's
 * `http://localhost:4096`: port **4095** is the opencode 2.x default
 * (`opencode serve`), and `localhost` on an emulator/device is the device
 * itself rather than the host — so the placeholder names the host as
 * `10.0.2.2`, which needs no setup. With `adb reverse tcp:4095 tcp:4095`
 * (emulator or USB device) the same process is also reachable as
 * `http://127.0.0.1:4095`, which is the better address whenever the reverse
 * is in place.
 */
const val DEFAULT_SERVER_URL_PLACEHOLDER: String = "http://10.0.2.2:4095"

// ------------------------------------------------------- providers / models

/**
 * One `settings-v2-providers-row` (`settings/providers/providers.tsx:135-174`).
 * Upstream renders a Connected section and a Popular section; the native panel
 * keeps one list with the connected providers pinned first, which is the same
 * information in one scroll.
 */
data class ProviderRow(
    val id: String,
    val name: String,
    /** `Providers.connected` membership (`GET /provider` → `connected[]`). */
    val connected: Boolean,
    val modelCount: Int,
    /** `Providers.default[providerID]` — the model the server would pick. */
    val defaultModelID: String? = null,
    /**
     * WAVE 2.3a: upstream `ProviderSource` (`providers.tsx:15,71-80`) —
     * `ENV` = env vars detected, `API` = key/OAuth grant, `CONFIG` =
     * config-provided, `CUSTOM` = custom provider, `OTHER` = undetermined.
     * Null until the integration snapshot lands (badge then reads "other").
     */
    val source: ProviderConnectCatalog.ProviderSource? = null,
    /** WAVE 2.3a: upstream `canDisconnect` (`providers.tsx:91-96`). */
    val disconnectable: Boolean = false,
)

/**
 * `GET /provider` → panel rows, connected first (stable, then by name). The
 * earlier panel built these two lists by hand at the call site and never passed
 * them (`ui/App.kt` constructed `SettingsPanelExtras()` with defaults), which is
 * why the panel always said "No connected providers".
 */
fun providerRows(providers: OpenCodeClient.Providers?): List<ProviderRow> {
    if (providers == null) return emptyList()
    val connected = providers.connected.toSet()
    return providers.all
        .map { provider ->
            ProviderRow(
                id = provider.id,
                name = provider.name,
                connected = provider.id in connected,
                modelCount = provider.models.size,
                defaultModelID = providers.default[provider.id],
            )
        }
        .sortedWith(compareBy({ if (it.connected) 0 else 1 }, { it.name.lowercase() }, { it.id }))
}

/** `count(connected)` for the panel header. */
fun connectedProviderCount(providers: OpenCodeClient.Providers?): Int =
    providers?.all?.count { it.id in providers.connected.toSet() } ?: 0

/**
 * Local connect/disconnect wins over the catalog snapshot: the `GET /provider`
 * flow does not re-emit after a panel write, so without this the header count
 * and the Connected/Popular sections keep showing the pre-write state until
 * something else refreshes the catalog. The panel resets [delta] on every
 * fresh catalog emission, keeping the server the source of truth.
 */
fun applyProviderConnectionDelta(
    rows: List<ProviderRow>,
    delta: Map<String, Boolean>,
): List<ProviderRow> {
    if (delta.isEmpty()) return rows
    return rows.map { row ->
        val connected = delta[row.id] ?: row.connected
        if (connected == row.connected) row else row.copy(connected = connected)
    }
}

/** How long the Providers tab shows `Loading` before landing on [ProvidersCatalogState.TimedOut]. */
const val PROVIDERS_SETTLE_TIMEOUT_MS: Long = 8_000L

/**
 * Providers tab body state. `Loading` is only legal while the catalog flow has
 * not emitted **and** the settle deadline is open; past it (or on a missing
 * flow / empty catalog) the page must show a terminal state instead of
 * spinning — the catalog flow's owner can fail without ever emitting.
 */
sealed interface ProvidersCatalogState {
    /** No catalog flow at all (preview / unhosted dialog). */
    data object Missing : ProvidersCatalogState

    /** Flow present, no emission yet, deadline open. */
    data object Loading : ProvidersCatalogState

    /** Flow present, no emission before [PROVIDERS_SETTLE_TIMEOUT_MS] — error + retry. */
    data object TimedOut : ProvidersCatalogState

    /** Catalog arrived empty. */
    data object Empty : ProvidersCatalogState

    /** Catalog arrived with rows. */
    data class Content(val connected: Int, val popular: Int) : ProvidersCatalogState
}

fun providersCatalogState(
    hasCatalogFlow: Boolean,
    providers: OpenCodeClient.Providers?,
    settled: Boolean,
): ProvidersCatalogState {
    if (!hasCatalogFlow) return ProvidersCatalogState.Missing
    if (providers == null) return if (settled) ProvidersCatalogState.TimedOut else ProvidersCatalogState.Loading
    val rows = providerRows(providers)
    if (rows.isEmpty()) return ProvidersCatalogState.Empty
    return ProvidersCatalogState.Content(
        connected = rows.count { it.connected },
        popular = rows.count { !it.connected },
    )
}

/**
 * One model row of the Models tab. Upstream groups by provider and lists every
 * model of every **connected** provider (`providers/models/models.tsx:24-31`,
 * `useModels()`).
 *
 * Intentional v2 divergence (aligned with the composer's ModelPicker decision,
 * `ModelPickerItems.groups(includeUnconnected = …)`): `Providers.connected`
 * is "has credentials" (`v2ConnectedProviderIds`), not "selectable" — on the
 * v2 backend `GET /api/integration` reports `connections: []` for every
 * provider, so filtering on it emptied the whole tab and left the visibility
 * switches with nothing to drive. [modelRows] therefore lists **every**
 * cataloged model and [connected] only marks the row; the Models tab badges
 * unconnected rows instead of hiding them.
 */
data class ModelRow(
    val providerID: String,
    val providerName: String,
    val id: String,
    val name: String,
    /** `limit.context` — the window the server advertises for this entry. */
    val limitContext: Int?,
    /** The effective selection (`ChatViewModel.selectedModel`). */
    val current: Boolean,
    /**
     * WAVE 2.3a: upstream `models.visible({providerID, modelID: entry.id})`
     * (`providers/models/models.tsx:95-113`) — the Switch the Models tab exists
     * to drive. Defaults true until [SettingsStore.modelVisibility] says `hide`.
     */
    val visible: Boolean = true,
    /**
     * `Providers.connected` membership. Display-only: the tab and its
     * visibility switches cover unconnected rows exactly like connected ones.
     */
    val connected: Boolean = true,
    /**
     * The catalog entry id (`Model.Info.id` → `Model.entryID`): the row's
     * identity for the visibility override, because one provider can list
     * several entries under one wire [id] (`gpt-5.6` / `-fast` / `-pro` all
     * report `modelID: "gpt-5.6"`). Defaults to [id] for v1 catalogs, whose
     * model maps carry no separate entry id — where the two are the same string
     * anyway, so a v1 override still lands on the same key.
     */
    val entryID: String = id,
    /**
     * `limit.input` — the entry's prompt ceiling, `null` when the wire omits it
     * (`OpenCodeClient.Providers.Model.limitInput`; 74 of the 129 live v20 rows
     * omit it, 4 report `0`). Declared **after** [entryID] so the row's
     * positional construction order (`providerID`, `providerName`, `id`, `name`,
     * `limitContext`, `current`) stays as it was.
     */
    val limitInput: Int? = null,
) {
    /** `"providerID/entryID"` — the row's identity, in the picker's own terms. */
    val ref: String get() = "$providerID/$entryID"

    /**
     * The entry's **effective window** — `min(limit.input, limit.context)`
     * (`ContextUsage.windowOf`), the same denominator the header ring, the
     * context tab's `Context Limit` / `Usage` rows and the pre-emptive
     * compaction read. The Models tab prints this rather than the raw
     * [limitContext], which is a number nothing in the app (or on the server)
     * divides by.
     */
    val window: Int? get() = ContextUsage.windowOf(limitContext, limitInput)
}

fun modelRows(
    providers: OpenCodeClient.Providers?,
    selected: Pair<String, String>?,
    /**
     * WAVE 2.3a: the persisted visibility overrides
     * (`SettingsStore.modelVisibility()`); absent key = visible (upstream's
     * unset rule for models without a parseable release date). Keyed by
     * `providerID:entryID`, with the legacy `providerID:modelID` key still
     * honoured — see [ModelVisibility].
     */
    visibility: Map<String, String> = emptyMap(),
): List<ModelRow> {
    if (providers == null) return emptyList()
    // Intentional v2 divergence (see [ModelRow]): list every cataloged model —
    // `connected` only marks the row. Filtering on it emptied the tab on the
    // v2 backend, whose `GET /api/integration` reports `connections: []` for
    // every provider. Catalog order is preserved.
    val connected = providers.connected.toSet()
    return providers.all.flatMap { provider ->
        provider.models.map { model ->
            ModelRow(
                providerID = provider.id,
                providerName = provider.name,
                id = model.id,
                name = model.name,
                limitContext = model.limitContext,
                limitInput = model.limitInput,
                // `selected.second` is the entry-level ref id (fall back to
                // modelID for legacy persisted pairs) — see Model.fromV2.
                current = selected != null && selected.first == provider.id &&
                    (selected.second == model.entryID || selected.second == model.id),
                visible = ModelVisibility.isVisible(
                    provider.id,
                    model.entryID,
                    visibility,
                    modelID = model.id,
                ),
                connected = provider.id in connected,
                entryID = model.entryID,
            )
        }
    }
}

/**
 * WAVE 2.3a (A4 #15): the **server's** `config.shell`
 * (`settings/general/controllers.ts:26-69` — `createServerShellController`).
 *
 * Upstream reads the last global `document` entry's `info.shell` from
 * `GET /config` and writes with `PATCH /experimental/config {shell}`. The
 * `GET /config` document entries are `{"type":"document","info":{…}}`
 * separated from project entries by the first `{"type":"directory"}` entry.
 * Null = the server reports no shell (row falls back to "auto").
 */
data class ServerShellInfo(val current: String?, val options: List<String>)

/**
 * One row of the Shell settings page ([ui/settings/SettingsPanels.kt]
 * `ShellPanel`).
 */
sealed interface ShellPageRow {
    /** The 「当前 Shell」 summary row: title, the value, always tagged Current. */
    data class Summary(val title: String, val value: String) : ShellPageRow

    /** One selectable shell; [current] is the row that carries the 「当前」 tag. */
    data class Option(val shell: String, val current: Boolean) : ShellPageRow
}

/**
 * The Shell page's rows for a loaded [shell].
 *
 * The summary row used to be drawn unconditionally above the option list, so a
 * server whose current shell is one of its own options listed it twice — once
 * under 「当前 Shell」 and once in the list — and *both* carried the 「当前」 tag
 * (`research/v63_verify/5554/28_shell.png`: `bash` above and `bash` below, two
 * Current pills). The summary only says something the list does not when the
 * current shell is not offered as an option, so it is drawn exactly then.
 */
fun shellPageRows(shell: ServerShellInfo, summaryTitle: String, autoLabel: String): List<ShellPageRow> {
    val current = shell.current?.takeIf { it.isNotBlank() }
    val rows = buildList {
        if (current == null || current !in shell.options) {
            add(ShellPageRow.Summary(summaryTitle, current ?: autoLabel))
        }
        for (option in shell.options) {
            add(ShellPageRow.Option(option, current != null && option == current))
        }
    }
    return rows
}

/**
 * Pure parse of `GET /config` → [ServerShellInfo]. [shells] are the
 * `GET /config/shells` option ids (empty = options unknown, control disabled).
 */
fun serverShellInfo(configJson: String?, shells: List<String>): ServerShellInfo {
    val trimmed = configJson?.trim().orEmpty()
    if (trimmed.isEmpty()) return ServerShellInfo(current = null, options = shells)
    val array = runCatching { JSONArray(trimmed) }.getOrNull()
        ?: runCatching { JSONObject(trimmed).optJSONArray("entries") }.getOrNull()
        ?: return ServerShellInfo(current = null, options = shells)
    var shell: String? = null
    for (i in 0 until array.length()) {
        val entry = array.optJSONObject(i) ?: continue
        if (entry.optString("type") == "directory") break
        val info = entry.optJSONObject("info") ?: continue
        val value = info.optString("shell", "").takeIf { it.isNotEmpty() }
        if (value != null) shell = value
    }
    return ServerShellInfo(current = shell, options = shells)
}

/**
 * Total model count across **all** cataloged providers (panel header).
 *
 * Intentional v2 divergence (see [ModelRow]): the connected-only count read 0
 * on the v2 backend (`GET /api/integration` → `connections: []` everywhere),
 * so the header now uses the full-caliber count the tab actually lists. The
 * name stays so parallel call sites keep compiling.
 */
fun connectedModelCount(providers: OpenCodeClient.Providers?): Int =
    providers?.all?.sumOf { it.models.size } ?: 0

/**
 * WAVE 2.3a: visible-model count after the Models-tab Switch filter — the
 * number the composer ModelPicker actually offers. [visibility] is
 * `SettingsStore.modelVisibility()`.
 */
fun visibleModelCount(
    providers: OpenCodeClient.Providers?,
    visibility: Map<String, String>,
): Int = modelRows(providers, selected = null, visibility = visibility).count { it.visible }

/**
 * `settings/models/models.tsx` search box: case-insensitive contains over the
 * model name, the model id, the provider name and the `provider/model` ref.
 */
fun filterModelRows(rows: List<ModelRow>, query: String): List<ModelRow> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return rows
    return rows.filter {
        it.name.lowercase().contains(q) ||
            it.id.lowercase().contains(q) ||
            it.providerID.lowercase().contains(q) ||
            it.providerName.lowercase().contains(q)
    }
}

/** `limit.context` rendered for the row meta (`131072` → `131k`). */
fun formatContextLimit(limit: Int?): String? = when {
    limit == null || limit <= 0 -> null
    limit % 1024 != 0 -> "$limit"
    limit < 1024 * 1024 -> "${limit / 1024}k"
    else -> "${limit / 1024 / 1024}M"
}
