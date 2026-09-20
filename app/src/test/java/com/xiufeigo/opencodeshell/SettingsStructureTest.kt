package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.settings.DEFAULT_SERVER_URL_PLACEHOLDER
import com.xiufeigo.opencodeshell.ui.settings.DEFAULT_SERVER_USERNAME
import com.xiufeigo.opencodeshell.ui.settings.GENERAL_PANEL
import com.xiufeigo.opencodeshell.ui.settings.SETTINGS_NAV
import com.xiufeigo.opencodeshell.ui.settings.SHORTCUT_ROWS
import com.xiufeigo.opencodeshell.ui.settings.ServerHealth
import com.xiufeigo.opencodeshell.ui.settings.ServerListItem
import com.xiufeigo.opencodeshell.ui.settings.SettingsControl
import com.xiufeigo.opencodeshell.ui.settings.SettingsTab
import com.xiufeigo.opencodeshell.ui.settings.ShortcutGroup
import com.xiufeigo.opencodeshell.ui.settings.filterServers
import com.xiufeigo.opencodeshell.ui.settings.generalPanelActions
import com.xiufeigo.opencodeshell.ui.settings.groupForCommandId
import com.xiufeigo.opencodeshell.ui.settings.headerTitleResId
import com.xiufeigo.opencodeshell.ui.settings.icon
import com.xiufeigo.opencodeshell.ui.settings.serverMeta
import com.xiufeigo.opencodeshell.ui.settings.showsServerSearch
import com.xiufeigo.opencodeshell.ui.settings.shortcutsByGroup
import com.xiufeigo.opencodeshell.ui.settings.sortServers
import com.xiufeigo.opencodeshell.ui.settings.titleResId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic gate for `research/02` §5 (settings-v2) + `research/05` §5.1/§5.2.
 *
 * The left navigation, the General panel render order and the servers ordering
 * are transcribed from the verified DOM, so these assertions are the executable
 * form of that spec.
 */
class SettingsStructureTest {

    // ---------------------------------------------------------- left navigation

    @Test
    fun leftNavHasExactlyTwoSectionsAndFiveTabs() {
        assertEquals(2, SETTINGS_NAV.size)
        assertEquals(
            listOf(R.string.oc_setting_section_desktop, R.string.oc_setting_section_server),
            SETTINGS_NAV.map { it.titleResId },
        )
        assertEquals(
            listOf(
                SettingsTab.GENERAL,
                SettingsTab.SHORTCUTS,
                SettingsTab.SERVERS,
                SettingsTab.PROVIDERS,
                SettingsTab.MODELS,
            ),
            SETTINGS_NAV.flatMap { it.tabs },
        )
        assertEquals(listOf(SettingsTab.GENERAL, SettingsTab.SHORTCUTS), SETTINGS_NAV[0].tabs)
        assertEquals(listOf(SettingsTab.SERVERS, SettingsTab.PROVIDERS, SettingsTab.MODELS), SETTINGS_NAV[1].tabs)
    }

    @Test
    fun tabValuesMatchTheWebTriggers() {
        assertEquals(
            listOf("general", "shortcuts", "servers", "providers", "models"),
            SettingsTab.entries.map { it.value },
        )
        assertEquals(SettingsTab.SERVERS, SettingsTab.fromValue("servers"))
        // Unknown / missing values fall back to General (the dialog's defaultValue).
        assertEquals(SettingsTab.GENERAL, SettingsTab.fromValue("nope"))
        assertEquals(SettingsTab.GENERAL, SettingsTab.fromValue(null))
    }

    @Test
    fun tabIconsFollowTheSpriteNames() {
        assertEquals(com.xiufeigo.opencodeshell.ui.settings.SettingsIcon.SLIDERS, SettingsTab.GENERAL.icon())
        assertEquals(com.xiufeigo.opencodeshell.ui.settings.SettingsIcon.KEYBOARD, SettingsTab.SHORTCUTS.icon())
        assertEquals(com.xiufeigo.opencodeshell.ui.settings.SettingsIcon.SERVER, SettingsTab.SERVERS.icon())
        assertEquals(com.xiufeigo.opencodeshell.ui.settings.SettingsIcon.PROVIDERS, SettingsTab.PROVIDERS.icon())
        assertEquals(com.xiufeigo.opencodeshell.ui.settings.SettingsIcon.MODELS, SettingsTab.MODELS.icon())
    }

    @Test
    fun shortcutsHeaderIsTheLongLabelNotTheNavLabel() {
        // Nav says "Shortcuts"; the panel `h2` says "Keyboard shortcuts" (§5.6).
        assertEquals(R.string.oc_setting_tab_shortcuts, SettingsTab.SHORTCUTS.titleResId())
        assertEquals(R.string.oc_setting_shortcuts_title, SettingsTab.SHORTCUTS.headerTitleResId())
        assertNotEquals(SettingsTab.SHORTCUTS.titleResId(), SettingsTab.SHORTCUTS.headerTitleResId())
    }

    // ------------------------------------------------------- General panel order

    @Test
    fun generalPanelSectionOrderIsAppearanceNotificationsSoundsAdvanced() {
        assertEquals(
            listOf(
                null, // General — untitled in the web DOM
                R.string.oc_setting_section_appearance,
                R.string.oc_setting_section_notifications,
                R.string.oc_setting_section_sounds,
                R.string.oc_setting_section_advanced,
            ),
            GENERAL_PANEL.map { it.titleResId },
        )
    }

    /**
     * Exact `data-action` order from `research/02` §5.2. Updates / Display are
     * desktop-only and intentionally absent on Android.
     */
    @Test
    fun generalPanelActionOrderIsExact() {
        assertEquals(
            listOf(
                // untitled General block
                "settings-language",
                "settings-auto-accept-permissions",
                "settings-shell",
                "settings-feed-reasoning-summaries",
                "settings-feed-shell-tool-parts-expanded",
                "settings-feed-edit-tool-parts-expanded",
                // Appearance
                "settings-color-scheme",
                "settings-theme",
                "settings-ui-font",
                "settings-code-font",
                "settings-terminal-font",
                // System notifications
                "settings-notifications-agent",
                "settings-notifications-permissions",
                "settings-notifications-errors",
                // Sound effects
                "settings-sounds-agent",
                "settings-sounds-permissions",
                "settings-sounds-errors",
                // Advanced
                "settings-show-file-tree",
                "settings-show-search",
                "settings-show-status",
                "settings-show-custom-agents",
            ),
            generalPanelActions(),
        )
    }

    @Test
    fun desktopOnlySectionsAreNotRenderedOnAndroid() {
        val actions = generalPanelActions()
        assertFalse(actions.any { it.startsWith("settings-release-notes") })
        assertFalse(actions.any { it.startsWith("settings-pinch-zoom") })
        assertFalse(GENERAL_PANEL.any { it.titleResId == null && it.rows.any { row -> row.action == "settings-pinch-zoom" } })
    }

    @Test
    fun appearanceFontsAreTextInputsAndSchemeIsASelect() {
        val appearance = GENERAL_PANEL.first { it.titleResId == R.string.oc_setting_section_appearance }
        appearance.rows.first { it.action == "settings-color-scheme" }.let { row ->
            assertEquals(SettingsControl.SELECT, row.control)
            assertEquals(
                listOf("system", "light", "dark"),
                row.options.map { it.value },
            )
        }
        listOf("settings-ui-font", "settings-code-font", "settings-terminal-font").forEach { action ->
            assertEquals(SettingsControl.TEXT, appearance.rows.first { it.action == action }.control)
        }
    }

    @Test
    fun shellSelectOffersAutoDefaultFirst() {
        val shell = GENERAL_PANEL.flatMap { it.rows }.first { it.action == "settings-shell" }
        assertEquals(SettingsControl.SELECT, shell.control)
        assertEquals("auto", shell.options.first().value)
        assertEquals(R.string.oc_setting_shell_auto, shell.options.first().labelResId)
        assertEquals(listOf("auto", "sh", "bash", "zsh"), shell.options.map { it.value })
    }

    @Test
    fun everySelectRowCarriesAtLeastOneOption() {
        GENERAL_PANEL.flatMap { it.rows }.filter { it.control == SettingsControl.SELECT }.forEach { row ->
            assertTrue("${row.action} must have options", row.options.isNotEmpty())
        }
    }

    // --------------------------------------------------------------- shortcuts

    @Test
    fun shortcutGroupOrderMatchesTheKeybindsGroupsConstant() {
        assertEquals(
            listOf(
                "general",
                "session",
                "navigation",
                "modelAndAgent",
                "terminal",
                "prompt",
            ),
            ShortcutGroup.Order.map { it.key },
        )
    }

    @Test
    fun groupForCommandIdImplementsThePrefixRules() {
        assertEquals(ShortcutGroup.GENERAL, groupForCommandId("command.palette"))
        assertEquals(ShortcutGroup.GENERAL, groupForCommandId("settings.open"))
        assertEquals(ShortcutGroup.TERMINAL, groupForCommandId("terminal.toggle"))
        assertEquals(ShortcutGroup.TERMINAL, groupForCommandId("terminal.new"))
        assertEquals(ShortcutGroup.MODEL_AND_AGENT, groupForCommandId("model.choose"))
        assertEquals(ShortcutGroup.MODEL_AND_AGENT, groupForCommandId("agent.switch"))
        assertEquals(ShortcutGroup.MODEL_AND_AGENT, groupForCommandId("mcp.toggle"))
        assertEquals(ShortcutGroup.NAVIGATION, groupForCommandId("file.open"))
        assertEquals(ShortcutGroup.NAVIGATION, groupForCommandId("fileTree.toggle"))
        assertEquals(ShortcutGroup.PROMPT, groupForCommandId("prompt.submit"))
        assertEquals(ShortcutGroup.SESSION, groupForCommandId("session.new"))
        assertEquals(ShortcutGroup.SESSION, groupForCommandId("message.copy"))
        assertEquals(ShortcutGroup.SESSION, groupForCommandId("permissions.autoaccept"))
        assertEquals(ShortcutGroup.SESSION, groupForCommandId("review.toggle"))
        assertEquals(ShortcutGroup.GENERAL, groupForCommandId("something.else"))
    }

    @Test
    fun shortcutsGroupingKeepsDeclarationOrderAndDropsEmptyGroups() {
        val grouped = shortcutsByGroup()
        // GROUPS order is General / Session / Navigation / Model and agent /
        // Terminal / Prompt (`settings-keybinds.tsx`), and only non-empty groups
        // survive — `Model and agent` has no M4 row.
        assertEquals(
            listOf(
                ShortcutGroup.GENERAL,
                ShortcutGroup.SESSION,
                ShortcutGroup.NAVIGATION,
                ShortcutGroup.TERMINAL,
                ShortcutGroup.PROMPT,
            ),
            grouped.map { it.first },
        )
        assertTrue(grouped.none { it.second.isEmpty() })
        // Rows come out grouped, not in declaration order: General holds the
        // palette + settings rows, then Session, then Navigation, …
        assertEquals(
            listOf("command.palette", "settings.open", "session.new", "file.open", "terminal.toggle", "prompt.submit"),
            grouped.flatMap { it.second }.map { it.id },
        )
        // A subset keeps only the non-empty groups, in GROUPS order.
        assertEquals(
            listOf(ShortcutGroup.SESSION, ShortcutGroup.PROMPT),
            shortcutsByGroup(SHORTCUT_ROWS.filter { it.id == "prompt.submit" || it.id == "session.new" })
                .map { it.first },
        )
        assertTrue(shortcutsByGroup(emptyList()).isEmpty())
    }

    @Test
    fun everyShortcutDeclaresANonEmptyKeybind() {
        SHORTCUT_ROWS.forEach { row -> assertTrue(row.id, row.keybind.isNotBlank()) }
        assertEquals("Ctrl+K / Ctrl+Shift+P", SHORTCUT_ROWS.first { it.id == "command.palette" }.keybind)
        assertEquals("Ctrl+,", SHORTCUT_ROWS.first { it.id == "settings.open" }.keybind)
        assertEquals("Ctrl+P", SHORTCUT_ROWS.first { it.id == "file.open" }.keybind)
    }

    // ----------------------------------------------------------------- servers

    @Test
    fun serverSortingPinsDefaultThenRanksByHealthAndKeepsInputOrder() {
        val healthy = ServerListItem("http://a", "a", null, health = ServerHealth.HEALTHY)
        val unknown = ServerListItem("http://b", "b", null, health = ServerHealth.UNKNOWN)
        val unhealthy = ServerListItem("http://c", "c", null, health = ServerHealth.UNHEALTHY)
        val defaultUnhealthy = ServerListItem("http://d", "d", null, health = ServerHealth.UNHEALTHY, isDefault = true)

        assertEquals(
            listOf("http://d", "http://a", "http://b", "http://c"),
            sortServers(listOf(unhealthy, unknown, healthy, defaultUnhealthy)).map { it.url },
        )
        // Same rank keeps the input order (stable).
        val secondHealthy = ServerListItem("http://e", "e", null, health = ServerHealth.HEALTHY)
        assertEquals(
            listOf("http://a", "http://e"),
            sortServers(listOf(healthy, secondHealthy)).map { it.url },
        )
    }

    @Test
    fun serverSearchOnlyAppearsForMoreThanOneServer() {
        assertFalse(showsServerSearch(0))
        assertFalse(showsServerSearch(1))
        assertTrue(showsServerSearch(2))
    }

    @Test
    fun serverFilterMatchesNameOrUrl() {
        val servers = listOf(
            ServerListItem("http://localhost:4096", "Localhost", "opencode"),
            ServerListItem("https://backend.example.com", "Prod", "opencode"),
        )
        assertEquals(servers, filterServers(servers, ""))
        assertEquals(listOf("https://backend.example.com"), filterServers(servers, "prod").map { it.url })
        assertEquals(listOf("http://localhost:4096"), filterServers(servers, "4096").map { it.url })
        assertTrue(filterServers(servers, "nope").isEmpty())
    }

    @Test
    fun serverMetaIsVersionBulletUsernameWithFallback() {
        assertEquals(
            "v1.2.3 • opencode",
            serverMeta(ServerListItem("http://a", "a", "opencode", version = "1.2.3")),
        )
        assertEquals("no username", serverMeta(ServerListItem("http://a", "a", null)))
        assertEquals("no username", serverMeta(ServerListItem("http://a", "a", "   ")))
        assertEquals("no username", serverMeta(ServerListItem("http://a", "a", null, version = "")))
    }

    @Test
    fun addServerDialogDefaultsMatchUpstream() {
        assertEquals("opencode", DEFAULT_SERVER_USERNAME)
        assertEquals("http://localhost:4096", DEFAULT_SERVER_URL_PLACEHOLDER)
    }

    // ------------------------------------------------------------ persistence

    @Test
    fun settingsPersistenceKeysStayWebCompatible() {
        // Upstream `packages/ui/src/theme/context.tsx`: THEME_ID / COLOR_SCHEME.
        assertEquals("opencode-theme-id", SettingsStore.KEY_THEME_ID)
        assertEquals("opencode-color-scheme", SettingsStore.KEY_COLOR_SCHEME)
        assertEquals(setOf("system", "light", "dark"), SettingsStore.SCHEMES)
        assertEquals("system", SettingsStore.DEFAULT_SCHEME)
        assertEquals("oc-2", SettingsStore.DEFAULT_THEME_ID)
    }

    @Test
    fun colorSchemeResolvesSystemLightAndDarkLikeThePreloadScript() {
        val base = SettingsStore.Settings(
            colorScheme = "system",
            themeId = "oc-2",
            uiFont = "",
            codeFont = "",
            terminalFont = "",
            language = "en",
            autoAcceptPermissions = false,
            shell = "auto",
            reasoningSummaries = true,
            shellToolPartsExpanded = false,
            editToolPartsExpanded = false,
            notifyAgent = true,
            notifyPermissions = true,
            notifyErrors = true,
            soundAgent = "default",
            soundPermissions = "default",
            soundErrors = "default",
            showFileTree = true,
            showSearch = true,
            showStatus = true,
            showCustomAgents = true,
        )
        assertTrue(base.isDark(systemDark = true))
        assertFalse(base.isDark(systemDark = false))
        assertTrue(base.copy(colorScheme = "dark").isDark(systemDark = false))
        assertFalse(base.copy(colorScheme = "light").isDark(systemDark = true))
    }

    @Test
    fun booleanPrefKeysCoverEverySwitchRowAndOnlyThose() {
        val switchActions = GENERAL_PANEL.flatMap { it.rows }
            .filter { it.control == SettingsControl.SWITCH }
            .map { it.action }
        assertEquals(switchActions.toSet(), SettingsStore.BOOLEAN_KEYS.keys)
        assertFalse(SettingsStore.BOOLEAN_KEYS.containsKey("settings-color-scheme"))
        assertFalse(SettingsStore.BOOLEAN_KEYS.containsKey("settings-sounds-agent"))
    }

    @Test
    fun serverListRoundTripsThroughTheWireSchema() {
        val json = SettingsStore.encodeServerList(
            listOf(
                ServerConfigStore.Server("http://localhost:4096", "opencode", "secret"),
                ServerConfigStore.Server("https://prod.example.com", null, null),
            ),
            activeUrl = "https://prod.example.com",
        )
        val obj = org.json.JSONObject(json)
        val list = obj.getJSONArray("list")
        assertEquals(2, list.length())
        assertEquals("http", list.getJSONObject(0).getString("type"))
        assertEquals("http://localhost:4096", list.getJSONObject(0).getJSONObject("http").getString("url"))
        assertEquals("opencode", list.getJSONObject(0).getJSONObject("http").getString("username"))
        assertFalse(list.getJSONObject(1).getJSONObject("http").has("username"))
        assertEquals("https://prod.example.com", obj.getString("active"))

        // The active key is omitted when there is no selection.
        val noActive = org.json.JSONObject(SettingsStore.encodeServerList(emptyList(), activeUrl = null))
        assertEquals(0, noActive.getJSONArray("list").length())
        assertFalse(noActive.has("active"))
    }

    @Test
    fun pureSettingsStoreHelpersDoNotNeedAContextForWrites() {
        // The `data-action` → pref-key map is what `update(action, value)` uses;
        // asserting coverage here keeps the writer and the paint loop in sync.
        assertTrue(SettingsStore.BOOLEAN_KEYS.values.all { it.startsWith("opencode") })
        assertTrue(SettingsStore.LANGUAGES.containsAll(setOf("en", "zh", "zht")))
        assertTrue(SettingsStore.SOUNDS.containsAll(setOf("none", "default")))
    }
}
