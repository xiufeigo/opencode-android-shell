package com.xiufeigo.opencodeshell.ui.settings

import com.xiufeigo.opencodeshell.OpenCodeClient
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.terminal.TerminalPlacement
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
 * (`settings/shell.tsx:39-57`, `settings/pages.ts:5-18`). The two upstream tabs
 * that still have no honest implementation (notifications, experimental) keep
 * their disabled nav row, see [SETTINGS_NAV].
 */
enum class SettingsTab(val value: String) {
    GENERAL("general"),
    APPEARANCE("appearance"),
    NOTIFICATIONS("notifications"),
    SHORTCUTS("shortcuts"),
    PROJECTS("projects"),
    WORKSPACES("workspaces"),
    PROVIDERS("providers"),
    MODELS("models"),
    EXTENSIONS("extensions"),
    SERVERS("servers"),
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
private fun disabledItem(resId: Int) = SettingsNavItem(resId)

/**
 * The left navigation, in render order. Mirrors `settings/shell.tsx:39-68,218-246`:
 * root-client group (general, appearance, notifications, shortcuts), the server
 * group (projects, workspaces, providers, models, extensions, servers) and the
 * two trailing single-item groups (experimental, about).
 *
 * W5-S: appearance / notifications / projects / workspaces / extensions / about
 * are real pages now. The one remaining disabled row carries a reason in
 * `research/wave5_settings.md` §D1:
 *
 * * **experimental** — upstream's 4 rows are `tabLayout` (needs a writer for
 *   `AppearancePrefs.TabLayout`, read by `ui/nav` and `OcActivity`),
 *   `browserPane` (desktop-only) and `showProjectName` / `showProjectIcon`
 *   (title-bar project display the native shell does not render). None of them
 *   has a consumer in this wave's scope.
 *
 * **Notifications is live again (W5-E5/W5-S):** `notify/AppNotifier.kt`'s feed is
 * gated by exactly `notifyAgent` / `notifyPermissions` / `notifyErrors`
 * (`ChatViewModel.notificationEnabled`), so the three switches have a real
 * consumer and moved back out of [HIDDEN_SETTINGS]. The three **sound** selects
 * stay hidden — nothing in the shell plays a sound.
 */
val SETTINGS_NAV: List<SettingsNavSection> = listOf(
    SettingsNavSection(
        titleResId = null,
        items = listOf(
            tabItem(SettingsTab.GENERAL),
            tabItem(SettingsTab.APPEARANCE),
            tabItem(SettingsTab.NOTIFICATIONS),
            tabItem(SettingsTab.SHORTCUTS),
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
        ),
    ),
    SettingsNavSection(titleResId = null, items = listOf(disabledItem(R.string.oc_setting_tab_experimental))),
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
    SettingsTab.SERVERS -> SettingsIcon.SERVER
    SettingsTab.PROVIDERS -> SettingsIcon.PROVIDERS
    SettingsTab.MODELS -> SettingsIcon.MODELS
    SettingsTab.APPEARANCE,
    SettingsTab.NOTIFICATIONS,
    SettingsTab.PROJECTS,
    SettingsTab.WORKSPACES,
    SettingsTab.EXTENSIONS,
    SettingsTab.ABOUT,
    -> null
}

fun SettingsTab.titleResId(): Int = when (this) {
    SettingsTab.GENERAL -> R.string.oc_setting_tab_general
    SettingsTab.APPEARANCE -> R.string.oc_setting_tab_appearance
    SettingsTab.NOTIFICATIONS -> R.string.oc_setting_tab_notifications
    SettingsTab.SHORTCUTS -> R.string.oc_setting_tab_shortcuts
    SettingsTab.PROJECTS -> R.string.oc_setting_tab_projects
    // Upstream's nav label is "Worktrees" (`settings.tab.workspaces`), not
    // "Workspaces": the page lists `GET /experimental/worktree` entries.
    SettingsTab.WORKSPACES -> R.string.oc_settings_tab_worktrees
    SettingsTab.PROVIDERS -> R.string.oc_setting_tab_providers
    SettingsTab.MODELS -> R.string.oc_setting_tab_models
    SettingsTab.EXTENSIONS -> R.string.oc_setting_tab_extensions
    SettingsTab.SERVERS -> R.string.oc_setting_tab_servers
    SettingsTab.ABOUT -> R.string.oc_setting_tab_about
}

fun SettingsTab.headerTitleResId(): Int = when (this) {
    SettingsTab.GENERAL -> R.string.oc_setting_tab_general
    // Verified: `h2.settings-v2-tab-title` reads "Keyboard shortcuts" (§5.6).
    SettingsTab.SHORTCUTS -> R.string.oc_setting_shortcuts_title
    else -> titleResId()
}

/**
 * The muted second line of `settings-tab-header` upstream renders under the page
 * title (`appearance.tsx:74`, `projects.tsx`, `workspaces.tsx`,
 * `extensions.tsx`). `null` where upstream has none.
 */
fun SettingsTab.pageDescriptionResId(): Int? = when (this) {
    SettingsTab.APPEARANCE -> R.string.oc_settings_appearance_description
    SettingsTab.NOTIFICATIONS -> R.string.oc_settings_notifications_description
    SettingsTab.PROJECTS -> R.string.oc_settings_projects_description
    SettingsTab.WORKSPACES -> R.string.oc_settings_workspaces_description
    SettingsTab.EXTENSIONS -> R.string.oc_settings_extensions_description
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
)

/** One [SettingsControl.SELECT] option. */
data class SettingsOption(val value: String, val labelResId: Int)

/** A titled block: `settings-v2-section-title` + a `settings-v2-list` of rows. */
data class SettingsSection(
    /** Null for the first (untitled) General block, per §5.2. */
    val titleResId: Int?,
    val rows: List<SettingsRow>,
)

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
            // The native default is "" (unset → follow the preset triple), so no
            // option is pre-selected until the user picks one.
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
 * `SettingsAppearance` (`settings/appearance/appearance.tsx:64-131`), reduced to
 * the rows with a **real consumer** on Android:
 *
 * * `settings-color-scheme` — one untitled `SettingsList`,
 *   `ui/App.kt` → `Settings.isDark` → `OcTheme(isDark)`;
 * * `settings-terminal-font` — `ui/App.kt` → `TerminalPanelHost.terminalFont`.
 *
 * `settings-theme` (one theme ships), `settings-ui-font` and
 * `settings-code-font` are **not** rendered: their consumers
 * (`LocalOcFontFamily` at the `OcTheme` call site, the markdown mono family)
 * live outside this wave's file scope, and a text field that changes nothing is
 * exactly the kind of dead control W4-D removed. They stay in [HIDDEN_SETTINGS].
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
                action = "settings-terminal-font",
                titleResId = R.string.oc_setting_terminal_font,
                descriptionResId = R.string.oc_setting_terminal_font_description,
                control = SettingsControl.TEXT,
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
 * `SettingsNotifications` (`settings/notifications/notifications.tsx:56-127`),
 * reduced to the **live** half.
 *
 * The three switches are real consumers: `ChatViewModel` builds the W5-E5
 * in-app feed as `AppNotifier(enabled = ::notificationEnabled)` and answers
 * exactly `settings.notifyAgent` / `notifyPermissions` / `notifyErrors`
 * (`notify/AppNotifier.kt:70-76`). Upstream's default for `errors` is **false**
 * (`settings/model.tsx:261`), which the store now matches.
 *
 * The three **sound** selects are **not** rendered: `shell/notifications/sound.ts`
 * has no native counterpart (no player, no bundled audio in the native shell),
 * so a picker would again write a pref nobody reads. They stay in
 * [HIDDEN_SETTINGS] with their store keys intact.
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
 * `settings-theme` / `settings-ui-font` / `settings-code-font` are upstream rows
 * whose Android consumers (theme registry, `LocalOcFontFamily`, markdown mono
 * font) are not implemented — `ui/theme/OcTheme.kt` ships exactly one theme and
 * one font family. `settings-shell` writes a local pref while upstream writes the
 * *server's* `config.shell` (`settings/general/controllers.ts:26-69`). The sound
 * selects need a player the shell does not have. `settings-show-search` and
 * `settings-show-status` (Android-only key) target a title bar the native shell
 * does not have. `settings-show-custom-agents` needs the composer agent picker.
 * `settings-shell` stays hidden on purpose with a corrected meaning: upstream's
 * `ShellSetting` is the **server's** `config.shell` (`controllers.ts:26-69`),
 * not a local pref, so the dead local row was removed and replaced by the
 * read-only server value [ServerShellInfo] the Servers page renders (A4 #9 fix).
 *
 * The three **notification** rows left this list in W5-S: W5-E5 gave them a real
 * consumer ([NOTIFICATIONS_PANEL]).
 */
val HIDDEN_SETTINGS: List<String> = listOf(
    "settings-theme",
    "settings-ui-font",
    "settings-code-font",
    "settings-shell",
    "settings-sounds-agent",
    "settings-sounds-permissions",
    "settings-sounds-errors",
    "settings-show-search",
    "settings-show-status",
    "settings-show-custom-agents",
)

/** Flat list of every `data-action` in the General panel (test/QA anchor). */
fun generalPanelActions(): List<String> = GENERAL_PANEL.flatMap { it.rows }.map { it.action }

/** Flat list of every `data-action` in the Appearance panel (test/QA anchor). */
fun appearancePanelActions(): List<String> = APPEARANCE_PANEL.flatMap { it.rows }.map { it.action }

/** Flat list of every `data-action` in the Notifications panel (test/QA anchor). */
fun notificationsPanelActions(): List<String> = NOTIFICATIONS_PANEL.flatMap { it.rows }.map { it.action }

/**
 * The disabled placeholders of [SETTINGS_NAV] — the root tabs upstream has
 * (`settings/pages.ts:5-18`, 12 entries) and this shell still cannot implement
 * without a dead control. Keeping them in the model (rather than inline in the
 * renderer) is what lets the fidelity test assert that the navigation *shape*
 * matches upstream.
 */
val SETTINGS_NAV_DISABLED: List<SettingsNavItem> = SETTINGS_NAV
    .flatMap { it.items }
    .filterNot { it.enabled }

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
 */
enum class TimelineDetailPreset(
    val id: String,
    val shellExpanded: Boolean,
    val editExpanded: Boolean,
    val reasoningShown: Boolean,
    val labelResId: Int,
) {
    EVERYTHING("everything", shellExpanded = true, editExpanded = true, reasoningShown = true, labelResId = R.string.oc_settings_timeline_preset_everything),
    COMPACT("compact", shellExpanded = false, editExpanded = false, reasoningShown = true, labelResId = R.string.oc_settings_timeline_preset_compact),
    QUIET("quiet", shellExpanded = false, editExpanded = false, reasoningShown = false, labelResId = R.string.oc_settings_timeline_preset_quiet),
    ;

    /** The store's triple, in `SettingsStore`'s write order. */
    val values: List<Boolean> get() = listOf(shellExpanded, editExpanded, reasoningShown)

    companion object {
        /** The value that matches `shell`/`edit`/`reasoning`, or null (upstream `timelinePreset(value)`). */
        fun of(shellExpanded: Boolean, editExpanded: Boolean, reasoningShown: Boolean): TimelineDetailPreset? =
            entries.firstOrNull {
                it.shellExpanded == shellExpanded &&
                    it.editExpanded == editExpanded &&
                    it.reasoningShown == reasoningShown
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
fun searchSettings(query: String, titleOf: (Int) -> String): List<SettingsSearchHit> {
    val catalog = buildList {
        for (tab in SettingsTab.entries) {
            add(SettingsSearchHit(tab = tab, action = null, titleResId = tab.titleResId()))
        }
        for ((tab, panel) in ROW_PANELS) {
            for (section in panel) {
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
 * The keybind table — WAVE 2.3a registers the **full v2 registry**
 * (`settings/keybinds/keybinds.tsx:120-143` `listFor` over the command
 * catalog + `keybinds.tsx:217-226` `effective`), not just the global chords.
 *
 * Two tiers, exactly like upstream's `list`:
 *
 * - **Wired rows** (defaults from `ui/App.kt`'s `globalChord` + the palette
 *   registry, so the panel never advertises a key the shell never processes):
 *   `command.palette`, `settings.open`, `file.open`, `session.new`,
 *   `tab.new`, `terminal.toggle`, `review.toggle`, `fileTree.toggle`,
 *   `session.summary.toggle` (A1, `popover.tsx:17-34`), `session.search`
 *   (`Ctrl+F`, no settings row upstream either — W5-T), `prompt.submit`.
 * - **Registry rows** (upstream title + keybind, recorded overrides persist
 *   via the same table; runtime wiring is `ui/App.kt`'s job — see the wiring
 *   note in `research/v2_audit/wave2.3a-notes.md` §3): every session-route id
 *   from `use-session-commands.tsx`, the composer/new-session ids, and the
 *   tab/browser/steps rows.
 *
 * W5-S (D4) layers a **persisted override table** on top of these defaults
 * ([applyKeybindOverrides]); recording is stored + displayed, and `ui/App.kt`
 * honours it for the [WIRED_SHORTCUT_IDS] chords.
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
    ShortcutRow("prompt.submit", R.string.oc_shortcut_prompt_submit, "Enter"),
    // --- session route (`use-session-commands.tsx:250-463`) ---
    ShortcutRow("session.undo", R.string.command_session_undo, ""),
    ShortcutRow("session.redo", R.string.command_session_redo, ""),
    ShortcutRow("session.compact", R.string.command_session_compact, ""),
    ShortcutRow("session.background", R.string.command_session_background, "Ctrl+B"),
    ShortcutRow("session.fork", R.string.command_session_fork, ""),
    ShortcutRow("session.export", R.string.command_session_export, ""),
    ShortcutRow("session.copyID", R.string.command_session_copyID, ""),
    ShortcutRow("session.archive", R.string.command_session_archive, ""),
    ShortcutRow("session.import", R.string.command_session_import, ""),
    ShortcutRow("message.previous", R.string.command_message_previous, "Ctrl+Alt+["),
    ShortcutRow("message.next", R.string.command_message_next, "Ctrl+Alt+]"),
    ShortcutRow("mcp.toggle", R.string.command_mcp_toggle, "Ctrl+;"),
    ShortcutRow("permissions.autoaccept", R.string.command_permissions_autoaccept_enable, "Ctrl+Shift+A"),
    ShortcutRow("input.focus", R.string.command_input_focus, "Ctrl+L"),
    ShortcutRow("terminal.new", R.string.command_terminal_new, "Ctrl+Alt+T"),
    ShortcutRow("terminal.close", R.string.terminal_close, "Ctrl+W"),
    ShortcutRow("file.close", R.string.command_tab_close, "Ctrl+W"),
    ShortcutRow("context.addSelection", R.string.command_context_addSelection, "Ctrl+Shift+L"),
    ShortcutRow("project.copyID", R.string.command_project_copyID, ""),
    ShortcutRow("project.select", R.string.session_new_project_search, "Ctrl+Shift+O"),
    ShortcutRow("session.location.cycle", R.string.command_session_location_cycle, "Ctrl+Alt+L"),
    // --- composer (`composer/commands.tsx:51-75`) + new-session (`new-session/commands.tsx:24-48`) ---
    ShortcutRow("model.choose", R.string.command_model_choose, "Ctrl+'"),
    ShortcutRow("model.variant.cycle", R.string.command_model_variant_cycle, "Shift+Ctrl+D"),
    ShortcutRow("agent.cycle", R.string.command_agent_cycle, "Ctrl+."),
    ShortcutRow("agent.cycle.reverse", R.string.command_agent_cycle_reverse, "Shift+Ctrl+."),
    // --- shell chrome (titlebar tabs, timeline search, steps, browser, debug) ---
    ShortcutRow("tab.reopenClosed", R.string.command_tab_reopenClosed, "Ctrl+Shift+T"),
    ShortcutRow("session.search", R.string.session_search_placeholder, "Ctrl+F"),
    ShortcutRow("steps.toggle", R.string.command_steps_toggle, ""),
    ShortcutRow("browser.open", R.string.command_browser_open, ""),
    ShortcutRow("browser.reload", R.string.command_browser_reload, ""),
    ShortcutRow("debugBar.toggle", R.string.command_debugBar_toggle, ""),
)

/**
 * WAVE 2.3a: ids with a **runtime** chord in `ui/App.kt` (`globalChord`).
 * Overrides recorded for other ids persist and display but do not fire yet —
 * the wiring note in `research/v2_audit/wave2.3a-notes.md` §3 lists the
 * callback each action id needs.
 */
val WIRED_SHORTCUT_IDS: Set<String> = setOf(
    "command.palette",
    "settings.open",
    "file.open",
    "session.new",
    "tab.new",
    "terminal.toggle",
    "session.search",
    "session.summary.toggle",
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
 * One `GET /project` entry rendered by the Projects page
 * (`settings/workspaces/projects.tsx`). The v1 payload is
 * `{id, worktree, vcs?, icon?, time{created,updated}, sandboxes[]}`
 * (verified against the local server, W5-S).
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

/** Pure parse so the Projects page can be asserted on a JVM without a socket. */
fun projectPageRows(json: String?): List<ProjectPageRow> {
    val array = jsonArrayOrNull(json) ?: return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        val obj = array.optJSONObject(index) ?: return@mapNotNull null
        val worktree = obj.optString("worktree", "").trim()
        if (worktree.isEmpty()) return@mapNotNull null
        ProjectPageRow(
            id = obj.optString("id", worktree),
            worktree = worktree,
            name = worktreeDisplayName(worktree),
            vcs = obj.optString("vcs", "").takeIf { it.isNotBlank() },
            updatedAt = obj.optJSONObject("time")?.optLong("updated", 0L)?.takeIf { it > 0L },
        )
    }.sortedByDescending { it.updatedAt ?: 0L }
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

/** One `GET /api/plugin` entry (`extensions.tsx`, `pluginLabels`). */
data class PluginPageRow(val name: String)

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
 * `http://localhost:4096`: on an emulator `localhost` is the device itself;
 * `10.0.2.2` is the host machine where the OpenCode server usually runs.
 */
const val DEFAULT_SERVER_URL_PLACEHOLDER: String = "http://10.0.2.2:4096"

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
 * One model row of the Models tab. Upstream groups by provider and lists every
 * model of every **connected** provider (`providers/models/models.tsx:24-31`,
 * `useModels()`), which is exactly what [modelRows] does.
 */
data class ModelRow(
    val providerID: String,
    val providerName: String,
    val id: String,
    val name: String,
    /** `limit.context` — the value the context ring needs as denominator. */
    val limitContext: Int?,
    /** The effective selection (`ChatViewModel.selectedModel`). */
    val current: Boolean,
    /**
     * WAVE 2.3a: upstream `models.visible({providerID, modelID})`
     * (`providers/models/models.tsx:95-113`) — the Switch the Models tab exists
     * to drive. Defaults true until [SettingsStore.modelVisibility] says `hide`.
     */
    val visible: Boolean = true,
) {
    /** `"providerID/modelID"` — the same encoding as the picker's override. */
    val ref: String get() = "$providerID/$id"
}

fun modelRows(
    providers: OpenCodeClient.Providers?,
    selected: Pair<String, String>?,
    /**
     * WAVE 2.3a: the persisted visibility overrides
     * (`SettingsStore.modelVisibility()`); absent key = visible (upstream's
     * unset rule for models without a parseable release date).
     */
    visibility: Map<String, String> = emptyMap(),
): List<ModelRow> {
    if (providers == null) return emptyList()
    return providers.connectedProviders().flatMap { provider ->
        provider.models.map { model ->
            ModelRow(
                providerID = provider.id,
                providerName = provider.name,
                id = model.id,
                name = model.name,
                limitContext = model.limitContext,
                current = selected != null && selected.first == provider.id && selected.second == model.id,
                visible = ModelVisibility.isVisible(provider.id, model.id, visibility),
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

/** Total model count across the connected providers (panel header). */
fun connectedModelCount(providers: OpenCodeClient.Providers?): Int =
    providers?.connectedProviders()?.sumOf { it.models.size } ?: 0

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
