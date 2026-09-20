package com.xiufeigo.opencodeshell.ui.settings

import com.xiufeigo.opencodeshell.R

/**
 * Pure (JVM-testable) description of the opencode v2 Settings dialog.
 *
 * Source of truth: `research/02_ui_screens.md` §5 (settings-v2) and
 * `research/05_palette_host_session.md` §5.1/§5.2/§5.3 (verified DOM + sizes).
 *
 * Nothing here touches Compose: [SettingsScreen] only paints these values, so
 * the tab/section/row *structure* can be asserted in a plain JVM unit test
 * (the milestone gate requires "纯逻辑可测").
 */

/** The five left-nav tabs. `value` matches the `TabsV2.Trigger value=` in the web DOM. */
enum class SettingsTab(val value: String) {
    GENERAL("general"),
    SHORTCUTS("shortcuts"),
    SERVERS("servers"),
    PROVIDERS("providers"),
    MODELS("models"),
    ;

    companion object {
        val Default: SettingsTab = GENERAL

        fun fromValue(value: String?): SettingsTab =
            entries.firstOrNull { it.value == value } ?: Default
    }
}

/** A `TabsV2.SectionTitle` group. Exactly two exist: Desktop and Server. */
data class SettingsNavSection(
    val titleResId: Int,
    val tabs: List<SettingsTab>,
)

/**
 * The left navigation, in render order. Mirrors `dialog-settings-v2.tsx:44-113`:
 * two `gap-1.5` groups inside one `gap-3` column, then `settings-v2-nav-footer`.
 */
val SETTINGS_NAV: List<SettingsNavSection> = listOf(
    SettingsNavSection(R.string.oc_setting_section_desktop, listOf(SettingsTab.GENERAL, SettingsTab.SHORTCUTS)),
    SettingsNavSection(
        R.string.oc_setting_section_server,
        listOf(SettingsTab.SERVERS, SettingsTab.PROVIDERS, SettingsTab.MODELS),
    ),
)

/** Icon name from the web sprite (`Icon name="sliders"` etc.) — see [SettingsIcon]. */
fun SettingsTab.icon(): SettingsIcon = when (this) {
    SettingsTab.GENERAL -> SettingsIcon.SLIDERS
    SettingsTab.SHORTCUTS -> SettingsIcon.KEYBOARD
    SettingsTab.SERVERS -> SettingsIcon.SERVER
    SettingsTab.PROVIDERS -> SettingsIcon.PROVIDERS
    SettingsTab.MODELS -> SettingsIcon.MODELS
}

fun SettingsTab.titleResId(): Int = when (this) {
    SettingsTab.GENERAL -> R.string.oc_setting_tab_general
    SettingsTab.SHORTCUTS -> R.string.oc_setting_tab_shortcuts
    SettingsTab.SERVERS -> R.string.oc_setting_tab_servers
    SettingsTab.PROVIDERS -> R.string.oc_setting_tab_providers
    SettingsTab.MODELS -> R.string.oc_setting_tab_models
}

fun SettingsTab.headerTitleResId(): Int = when (this) {
    SettingsTab.GENERAL -> R.string.oc_setting_tab_general
    // Verified: `h2.settings-v2-tab-title` reads "Keyboard shortcuts" (§5.6).
    SettingsTab.SHORTCUTS -> R.string.oc_setting_shortcuts_title
    SettingsTab.SERVERS -> R.string.oc_setting_tab_servers
    SettingsTab.PROVIDERS -> R.string.oc_setting_tab_providers
    SettingsTab.MODELS -> R.string.oc_setting_tab_models
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

/** `soundOptions` order from `general.tsx` — declared before [GENERAL_PANEL]. */
private val SOUND_OPTIONS = listOf(
    SettingsOption("none", R.string.oc_setting_sound_none),
    SettingsOption("default", R.string.oc_setting_sound_default),
)

/**
 * General panel render order (`research/02` §5.2):
 * InterfaceNotice → General (untitled) → Appearance → Notifications → Sounds →
 * Updates (desktop only) → Display (desktop only) → Advanced.
 *
 * Android is not a desktop build, so Updates/Display are dropped, and the
 * Android-only CORS/notice row replaces `InterfaceNotice`.
 */
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
            SettingsRow(
                action = "settings-shell",
                titleResId = R.string.oc_setting_shell,
                descriptionResId = R.string.oc_setting_shell_description,
                control = SettingsControl.SELECT,
                options = listOf(
                    SettingsOption("auto", R.string.oc_setting_shell_auto),
                    SettingsOption("sh", R.string.oc_setting_shell_sh),
                    SettingsOption("bash", R.string.oc_setting_shell_bash),
                    SettingsOption("zsh", R.string.oc_setting_shell_zsh),
                ),
            ),
            SettingsRow(
                action = "settings-feed-reasoning-summaries",
                titleResId = R.string.oc_setting_reasoning_summaries,
                control = SettingsControl.SWITCH,
            ),
            SettingsRow(
                action = "settings-feed-shell-tool-parts-expanded",
                titleResId = R.string.oc_setting_shell_expand,
                control = SettingsControl.SWITCH,
            ),
            SettingsRow(
                action = "settings-feed-edit-tool-parts-expanded",
                titleResId = R.string.oc_setting_edit_expand,
                control = SettingsControl.SWITCH,
            ),
        ),
    ),
    SettingsSection(
        titleResId = R.string.oc_setting_section_appearance,
        rows = listOf(
            SettingsRow(
                action = "settings-color-scheme",
                titleResId = R.string.oc_setting_color_scheme,
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
                control = SettingsControl.SELECT,
                options = listOf(SettingsOption("oc-2", R.string.oc_setting_theme_oc2)),
            ),
            SettingsRow(
                action = "settings-ui-font",
                titleResId = R.string.oc_setting_ui_font,
                control = SettingsControl.TEXT,
            ),
            SettingsRow(
                action = "settings-code-font",
                titleResId = R.string.oc_setting_code_font,
                control = SettingsControl.TEXT,
            ),
            SettingsRow(
                action = "settings-terminal-font",
                titleResId = R.string.oc_setting_terminal_font,
                control = SettingsControl.TEXT,
            ),
        ),
    ),
    SettingsSection(
        titleResId = R.string.oc_setting_section_notifications,
        rows = listOf(
            SettingsRow(
                action = "settings-notifications-agent",
                titleResId = R.string.oc_setting_notify_agent,
                control = SettingsControl.SWITCH,
            ),
            SettingsRow(
                action = "settings-notifications-permissions",
                titleResId = R.string.oc_setting_notify_permissions,
                control = SettingsControl.SWITCH,
            ),
            SettingsRow(
                action = "settings-notifications-errors",
                titleResId = R.string.oc_setting_notify_errors,
                control = SettingsControl.SWITCH,
            ),
        ),
    ),
    // §5.2: the Sounds section is three SelectV2 rows (`soundOptions`).
    SettingsSection(
        titleResId = R.string.oc_setting_section_sounds,
        rows = listOf(
            SettingsRow(
                action = "settings-sounds-agent",
                titleResId = R.string.oc_setting_notify_agent,
                control = SettingsControl.SELECT,
                options = SOUND_OPTIONS,
            ),
            SettingsRow(
                action = "settings-sounds-permissions",
                titleResId = R.string.oc_setting_notify_permissions,
                control = SettingsControl.SELECT,
                options = SOUND_OPTIONS,
            ),
            SettingsRow(
                action = "settings-sounds-errors",
                titleResId = R.string.oc_setting_notify_errors,
                control = SettingsControl.SELECT,
                options = SOUND_OPTIONS,
            ),
        ),
    ),
    SettingsSection(
        titleResId = R.string.oc_setting_section_advanced,
        rows = listOf(
            SettingsRow(
                action = "settings-show-file-tree",
                titleResId = R.string.oc_setting_show_file_tree,
                control = SettingsControl.SWITCH,
            ),
            SettingsRow(
                action = "settings-show-search",
                titleResId = R.string.oc_setting_show_search,
                control = SettingsControl.SWITCH,
            ),
            SettingsRow(
                action = "settings-show-status",
                titleResId = R.string.oc_setting_show_status,
                control = SettingsControl.SWITCH,
            ),
            SettingsRow(
                action = "settings-show-custom-agents",
                titleResId = R.string.oc_setting_show_custom_agents,
                control = SettingsControl.SWITCH,
            ),
        ),
    ),
)

/** Flat list of every `data-action` in the General panel (test/QA anchor). */
fun generalPanelActions(): List<String> = GENERAL_PANEL.flatMap { it.rows }.map { it.action }

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
 * `groupFor(id)` from `settings-keybinds.tsx` (§5.6), transcribed literally:
 * `terminal.*`→Terminal, `model./agent./mcp.`→Model and agent,
 * `file./fileTree.`→Navigation, `prompt.`→Prompt,
 * `session./message./permissions./steps./review.`→Session, else General.
 */
fun groupForCommandId(id: String): ShortcutGroup = when {
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
 * The read-only keybind table. M4 shows the bindings verbatim (record/replay is
 * a placeholder), so this is the render order + the grouping contract only.
 */
val SHORTCUT_ROWS: List<ShortcutRow> = listOf(
    ShortcutRow("command.palette", R.string.oc_shortcut_palette, "Ctrl+K / Ctrl+Shift+P"),
    ShortcutRow("settings.open", R.string.oc_shortcut_settings_open, "Ctrl+,"),
    ShortcutRow("file.open", R.string.oc_shortcut_file_open, "Ctrl+P"),
    ShortcutRow("session.new", R.string.oc_shortcut_session_new, "Ctrl+N"),
    ShortcutRow("terminal.toggle", R.string.oc_shortcut_terminal_toggle, "Ctrl+`"),
    ShortcutRow("prompt.submit", R.string.oc_shortcut_prompt_submit, "Enter"),
)

fun shortcutsByGroup(rows: List<ShortcutRow> = SHORTCUT_ROWS): List<Pair<ShortcutGroup, List<ShortcutRow>>> =
    ShortcutGroup.Order.map { group -> group to rows.filter { groupForCommandId(it.id) == group } }
        .filter { it.second.isNotEmpty() }

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

/** Default URL placeholder (`dialog.server.add.placeholder`). */
const val DEFAULT_SERVER_URL_PLACEHOLDER: String = "http://localhost:4096"
