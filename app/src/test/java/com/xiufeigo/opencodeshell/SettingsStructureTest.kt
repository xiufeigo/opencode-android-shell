package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.settings.ALL_SETTINGS_PANELS
import com.xiufeigo.opencodeshell.ui.settings.APPEARANCE_PANEL
import com.xiufeigo.opencodeshell.ui.settings.DEFAULT_SERVER_URL_PLACEHOLDER
import com.xiufeigo.opencodeshell.ui.settings.DEFAULT_SERVER_USERNAME
import com.xiufeigo.opencodeshell.ui.settings.GENERAL_PANEL
import com.xiufeigo.opencodeshell.ui.settings.HIDDEN_SETTINGS
import com.xiufeigo.opencodeshell.ui.settings.NOTIFICATIONS_PANEL
import com.xiufeigo.opencodeshell.ui.settings.SETTINGS_NAV
import com.xiufeigo.opencodeshell.ui.settings.SETTINGS_NAV_DISABLED
import com.xiufeigo.opencodeshell.ui.settings.SETTINGS_NAV_TABS
import com.xiufeigo.opencodeshell.ui.settings.SHORTCUT_ROWS
import com.xiufeigo.opencodeshell.ui.settings.ServerHealth
import com.xiufeigo.opencodeshell.ui.settings.ServerListItem
import com.xiufeigo.opencodeshell.ui.settings.SettingsControl
import com.xiufeigo.opencodeshell.ui.settings.SettingsTab
import com.xiufeigo.opencodeshell.ui.settings.ShortcutGroup
import com.xiufeigo.opencodeshell.ui.settings.appearancePanelActions
import com.xiufeigo.opencodeshell.ui.settings.filterServers
import com.xiufeigo.opencodeshell.ui.settings.generalPanelActions
import com.xiufeigo.opencodeshell.ui.settings.groupForCommandId
import com.xiufeigo.opencodeshell.ui.settings.headerTitleResId
import com.xiufeigo.opencodeshell.ui.settings.icon
import com.xiufeigo.opencodeshell.ui.settings.normalizeUrlInput
import com.xiufeigo.opencodeshell.ui.settings.notificationsPanelActions
import com.xiufeigo.opencodeshell.ui.settings.serverMeta
import com.xiufeigo.opencodeshell.ui.settings.showsServerSearch
import com.xiufeigo.opencodeshell.ui.settings.shortcutsByGroup
import com.xiufeigo.opencodeshell.ui.settings.sortServers
import com.xiufeigo.opencodeshell.ui.settings.titleResId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
    fun leftNavMatchesUpstreamRootTabShape() {
        // Upstream groups (`settings/shell.tsx:39-57,218-246`): root-client,
        // server group, then two trailing single-item groups — none of them
        // labelled, which is why every `titleResId` is null.
        assertEquals(4, SETTINGS_NAV.size)
        assertTrue(SETTINGS_NAV.all { it.titleResId == null })
        assertEquals(
            listOf(
                SettingsTab.GENERAL,
                SettingsTab.APPEARANCE,
                SettingsTab.NOTIFICATIONS,
                SettingsTab.SHORTCUTS,
                SettingsTab.PROJECTS,
                SettingsTab.WORKSPACES,
                SettingsTab.PROVIDERS,
                SettingsTab.MODELS,
                SettingsTab.EXTENSIONS,
                SettingsTab.SERVERS,
                SettingsTab.ABOUT,
            ),
            SETTINGS_NAV_TABS,
        )
        // Root-client group order: general, appearance, notifications, shortcuts.
        assertEquals(
            listOf(
                R.string.oc_setting_tab_general,
                R.string.oc_setting_tab_appearance,
                R.string.oc_setting_tab_notifications,
                R.string.oc_setting_tab_shortcuts,
            ),
            SETTINGS_NAV[0].items.map { it.titleResId },
        )
        // Server group: projects, workspaces, providers, models, extensions, servers.
        assertEquals(
            listOf(
                R.string.oc_setting_tab_projects,
                R.string.oc_settings_tab_worktrees,
                R.string.oc_setting_tab_providers,
                R.string.oc_setting_tab_models,
                R.string.oc_setting_tab_extensions,
                R.string.oc_setting_tab_servers,
            ),
            SETTINGS_NAV[1].items.map { it.titleResId },
        )
        assertEquals(
            listOf(R.string.oc_setting_tab_experimental),
            SETTINGS_NAV[2].items.map { it.titleResId },
        )
        assertEquals(
            listOf(R.string.oc_setting_tab_about),
            SETTINGS_NAV[3].items.map { it.titleResId },
        )
    }

    @Test
    fun missingRootTabsAreDisabledEntriesNotSilentGaps() {
        // `settings/pages.ts:5-18` has 12 root tabs. W5-S made every one a real
        // page except `experimental`, whose four rows still have no consumer:
        // it stays an explicitly disabled row with a reason rather than an empty
        // page or a switch that writes a pref nobody reads.
        assertEquals(
            listOf(R.string.oc_setting_tab_experimental),
            SETTINGS_NAV_DISABLED.map { it.titleResId },
        )
        assertTrue(SETTINGS_NAV_DISABLED.none { it.enabled || it.tab != null })
    }

    @Test
    fun tabValuesMatchTheWebTriggers() {
        assertEquals(
            listOf(
                "general",
                "appearance",
                "notifications",
                "shortcuts",
                "projects",
                "workspaces",
                "providers",
                "models",
                "extensions",
                "servers",
                "about",
            ),
            SettingsTab.entries.map { it.value },
        )
        assertEquals(SettingsTab.SERVERS, SettingsTab.fromValue("servers"))
        assertEquals(SettingsTab.APPEARANCE, SettingsTab.fromValue("appearance"))
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
        // The new pages keep a null icon instead of borrowing a wrong glyph
        // (`SettingsIcon` has no `appearance` / `folder` / `info` member).
        assertNull(SettingsTab.APPEARANCE.icon())
        assertNull(SettingsTab.NOTIFICATIONS.icon())
        assertNull(SettingsTab.PROJECTS.icon())
        assertNull(SettingsTab.WORKSPACES.icon())
        assertNull(SettingsTab.EXTENSIONS.icon())
        assertNull(SettingsTab.ABOUT.icon())
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
    fun generalPanelSectionOrderKeepsOnlyTheSectionsWithLiveRows() {
        // Notifications / Sounds / Appearance live on their own pages now
        // (upstream `settings/shell.tsx:273-290`); what remains on General is the
        // untitled Preferences block, the upstream Timeline section and the
        // Android Advanced block.
        assertEquals(
            listOf(
                null, // General — untitled in the web DOM
                R.string.oc_settings_timeline_title,
                R.string.oc_setting_section_advanced,
            ),
            GENERAL_PANEL.map { it.titleResId },
        )
    }

    /**
     * Exact `data-action` order of the rows that stay. Every one of them has a
     * real consumer (see `research/wave5_settings.md` §2).
     */
    @Test
    fun generalPanelActionOrderIsExact() {
        assertEquals(
            listOf(
                // untitled Preferences block
                "settings-language",
                "settings-auto-accept-permissions",
                // B3: upstream's `TerminalPlacementSetting` sits in this same
                // untitled ("General") section, after the auto-approve row
                // (`settings/general/general.tsx:327-339`).
                "settings-terminal-placement",
                // WAVE 2.3a (A4 #9): follow-up behavior, workspace destination,
                // reasoning mode, mobile diff wrap (`general.tsx:71-97,157-184`,
                // `:340-358`; `model.tsx:94,96,114,207`).
                "settings-follow-up-behavior",
                "settings-workspace-destination",
                "settings-reasoning-mode",
                "settings-mobile-diff-wrap",
                // Timeline
                "settings-timeline-detail",
                "settings-feed-reasoning-summaries",
                "settings-feed-shell-tool-parts-expanded",
                "settings-feed-edit-tool-parts-expanded",
                // Advanced
                "settings-show-file-tree",
            ),
            generalPanelActions(),
        )
    }

    @Test
    fun appearancePanelKeepsTheLiveRowsOnly() {
        // Upstream appearance.tsx:82-126 has color scheme, theme and three font
        // inputs; only these two rows have a native consumer
        // (`ui/App.kt` → `OcTheme(isDark)` and `TerminalPanelHost.terminalFont`).
        assertEquals(
            listOf("settings-color-scheme", "settings-terminal-font"),
            appearancePanelActions(),
        )
        val panel = APPEARANCE_PANEL.single()
        assertNull(panel.titleResId)
        panel.rows.first { it.action == "settings-color-scheme" }.let { row ->
            assertEquals(SettingsControl.SELECT, row.control)
            assertEquals(listOf("system", "light", "dark"), row.options.map { it.value })
        }
        assertEquals(
            SettingsControl.TEXT,
            panel.rows.first { it.action == "settings-terminal-font" }.control,
        )
        assertTrue(appearancePanelActions().none { it == "settings-theme" })
        assertTrue(appearancePanelActions().none { it == "settings-ui-font" })
        assertTrue(appearancePanelActions().none { it == "settings-code-font" })
    }

    @Test
    fun notificationsPanelExposesTheThreeLiveSwitches() {
        // W5-E5's in-app feed is `AppNotifier(enabled = ::notificationEnabled)`
        // over exactly these three settings (`ChatViewModel`), so the rows are
        // live again; the sound selects are not (no player).
        assertEquals(
            listOf(
                "settings-notifications-agent",
                "settings-notifications-permissions",
                "settings-notifications-errors",
            ),
            notificationsPanelActions(),
        )
        assertEquals(
            R.string.oc_setting_section_notifications,
            NOTIFICATIONS_PANEL.single().titleResId,
        )
        assertTrue(NOTIFICATIONS_PANEL.flatMap { it.rows }.all { it.control == SettingsControl.SWITCH })
        assertTrue(NOTIFICATIONS_PANEL.flatMap { it.rows }.all { SettingsStore.BOOLEAN_KEYS.containsKey(it.action) })
    }

    @Test
    fun hiddenSettingsAreNotRenderedAndKeepTheirPrefKeys() {
        val rendered = ALL_SETTINGS_PANELS.flatMap { it.rows }.map { it.action }.toSet()
        assertTrue(rendered.intersect(HIDDEN_SETTINGS.toSet()).isEmpty())
        // The keys stay in the store so a later milestone can re-add the row with
        // its consumer without losing the user's value…
        assertTrue(SettingsStore.BOOLEAN_KEYS.containsKey("settings-show-search"))
        // …and the hidden list covers exactly the rows upstream/the audit marks
        // consumerless.
        assertEquals(
            listOf(
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
            ),
            HIDDEN_SETTINGS,
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
    fun fileTreeRowIsTheOnlyAdvancedRowAndDrivesTheCommandRegistration() {
        val advanced = GENERAL_PANEL.first { it.titleResId == R.string.oc_setting_section_advanced }
        assertEquals(listOf("settings-show-file-tree"), advanced.rows.map { it.action })
        assertEquals(SettingsControl.SWITCH, advanced.rows.single().control)
    }

    @Test
    fun everySelectRowCarriesAtLeastOneOption() {
        ALL_SETTINGS_PANELS.flatMap { it.rows }.filter { it.control == SettingsControl.SELECT }.forEach { row ->
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
        // survive — WAVE 2.3a registers the full v2 registry, so every group is
        // now non-empty.
        assertEquals(
            listOf(
                ShortcutGroup.GENERAL,
                ShortcutGroup.SESSION,
                ShortcutGroup.NAVIGATION,
                ShortcutGroup.MODEL_AND_AGENT,
                ShortcutGroup.TERMINAL,
                ShortcutGroup.PROMPT,
            ),
            grouped.map { it.first },
        )
        assertTrue(grouped.none { it.second.isEmpty() })
        // Rows come out grouped, not in declaration order (upstream `groupedFor`
        // keeps each group's registry order): General holds the palette +
        // settings + unprefixed rows, then Session, then Navigation, …
        assertEquals(
            listOf(
                "command.palette",
                "settings.open",
                "tab.new",
                "input.focus",
                "context.addSelection",
                "project.copyID",
                "project.select",
                "tab.reopenClosed",
                "browser.open",
                "browser.reload",
                "debugBar.toggle",
                "session.new",
                "review.toggle",
                "session.summary.toggle",
                "session.undo",
                "session.redo",
                "session.compact",
                "session.background",
                "session.fork",
                "session.export",
                "session.copyID",
                "session.archive",
                "session.import",
                "message.previous",
                "message.next",
                "permissions.autoaccept",
                "session.location.cycle",
                "session.search",
                "steps.toggle",
                "file.open",
                "fileTree.toggle",
                "file.close",
                "mcp.toggle",
                "model.choose",
                "model.variant.cycle",
                "agent.cycle",
                "agent.cycle.reverse",
                "terminal.toggle",
                "terminal.new",
                "terminal.close",
                "prompt.submit",
            ),
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
    fun everyShortcutDeclaresTheBindingTheShellActuallyImplements() {
        // WAVE 2.3a: wired rows carry the runtime chord; registry rows carry the
        // upstream keybind for display/record (runtime wiring is `ui/App.kt`'s
        // job — see `wave2.3a-notes.md` §3), and rows with no upstream keybind
        // stay blank rather than inventing one.
        val wired = com.xiufeigo.opencodeshell.ui.settings.WIRED_SHORTCUT_IDS
        for (row in SHORTCUT_ROWS) {
            if (row.id in wired || row.id == "prompt.submit") {
                assertTrue(row.id, row.keybind.isNotBlank())
            }
        }
        assertEquals("Ctrl+K / Ctrl+Shift+P", SHORTCUT_ROWS.first { it.id == "command.palette" }.keybind)
        assertEquals("Ctrl+,", SHORTCUT_ROWS.first { it.id == "settings.open" }.keybind)
        assertEquals("Ctrl+P", SHORTCUT_ROWS.first { it.id == "file.open" }.keybind)
        // W4-D: the table mirrors `ui/App.kt`'s `globalChord` + `appCommands`, so
        // the palette can no longer advertise a key the shell never processes.
        assertEquals("Ctrl+Shift+S", SHORTCUT_ROWS.first { it.id == "session.new" }.keybind)
        assertEquals("Ctrl+N", SHORTCUT_ROWS.first { it.id == "tab.new" }.keybind)
        assertEquals("Ctrl+Shift+R", SHORTCUT_ROWS.first { it.id == "review.toggle" }.keybind)
        assertEquals("Ctrl+\\", SHORTCUT_ROWS.first { it.id == "fileTree.toggle" }.keybind)
        assertEquals("Ctrl+`", SHORTCUT_ROWS.first { it.id == "terminal.toggle" }.keybind)
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
        // Android deviation: emulator host loopback instead of upstream localhost.
        assertEquals("http://10.0.2.2:4096", DEFAULT_SERVER_URL_PLACEHOLDER)
    }

    @Test
    fun urlInputNormalizesCjkImeFullWidthChars() {
        // Gboard pinyin commits full-width punctuation into URL fields.
        assertEquals("http://10.0.2.2:4096", normalizeUrlInput("http：／／10。0。2。2：4096"))
        assertEquals("https://a.b:8080/x", normalizeUrlInput("ｈｔｔｐｓ：／／ａ．ｂ：８０８０／ｘ"))
        assertEquals("http://a.b", normalizeUrlInput(" http://a.b 　"))
        assertEquals("http://10.0.2.2:4096", normalizeUrlInput("http://10.0.2.2:4096"))
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
    fun booleanPrefKeysCoverEveryRenderedSwitchPlusTheHiddenOnes() {
        val switchActions = ALL_SETTINGS_PANELS.flatMap { it.rows }
            .filter { it.control == SettingsControl.SWITCH }
            .map { it.action }
        // Every rendered switch is writable…
        assertTrue(switchActions.all { SettingsStore.BOOLEAN_KEYS.containsKey(it) })
        // …and the map is exactly {rendered switches} ∪ {hidden switch rows},
        // so nothing silently writes a pref no row can reach.
        val hiddenSwitches = HIDDEN_SETTINGS.filter { SettingsStore.BOOLEAN_KEYS.containsKey(it) }
        assertEquals(switchActions.toSet() + hiddenSwitches.toSet(), SettingsStore.BOOLEAN_KEYS.keys)
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
