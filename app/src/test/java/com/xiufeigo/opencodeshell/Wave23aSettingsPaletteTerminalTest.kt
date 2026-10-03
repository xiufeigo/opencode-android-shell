package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.palette.CATEGORY_CONTEXT
import com.xiufeigo.opencodeshell.ui.palette.CATEGORY_FILE
import com.xiufeigo.opencodeshell.ui.palette.CATEGORY_MCP
import com.xiufeigo.opencodeshell.ui.palette.CATEGORY_MODEL
import com.xiufeigo.opencodeshell.ui.palette.CATEGORY_PERMISSIONS
import com.xiufeigo.opencodeshell.ui.palette.CATEGORY_PROJECT
import com.xiufeigo.opencodeshell.ui.palette.CATEGORY_SESSION
import com.xiufeigo.opencodeshell.ui.palette.CATEGORY_TERMINAL
import com.xiufeigo.opencodeshell.ui.palette.CATEGORY_VIEW
import com.xiufeigo.opencodeshell.ui.palette.CATEGORY_WORKSPACE
import com.xiufeigo.opencodeshell.ui.palette.ENTRY_LIMIT
import com.xiufeigo.opencodeshell.ui.palette.PaletteEntry
import com.xiufeigo.opencodeshell.ui.palette.SESSION_SEARCH_LIMIT
import com.xiufeigo.opencodeshell.ui.palette.SessionCommands
import com.xiufeigo.opencodeshell.ui.palette.anchorActiveIndex
import com.xiufeigo.opencodeshell.ui.palette.filePaletteEntries
import com.xiufeigo.opencodeshell.ui.palette.looksLikeSessionID
import com.xiufeigo.opencodeshell.ui.palette.recentFileEntries
import com.xiufeigo.opencodeshell.ui.palette.sessionSearchEntries
import com.xiufeigo.opencodeshell.ui.settings.FOLLOW_UP_BEHAVIOR_OPTIONS
import com.xiufeigo.opencodeshell.ui.settings.ModelGroup
import com.xiufeigo.opencodeshell.ui.settings.ModelVisibility
import com.xiufeigo.opencodeshell.ui.settings.PluginPageRow
import com.xiufeigo.opencodeshell.ui.settings.ProviderConnectCatalog
import com.xiufeigo.opencodeshell.ui.settings.ProviderConnectForm
import com.xiufeigo.opencodeshell.ui.settings.ProviderWriteResult
import com.xiufeigo.opencodeshell.ui.settings.REASONING_MODE_OPTIONS
import com.xiufeigo.opencodeshell.ui.settings.SHORTCUT_ROWS
import com.xiufeigo.opencodeshell.ui.settings.WIRED_SHORTCUT_IDS
import com.xiufeigo.opencodeshell.ui.settings.WORKSPACE_DESTINATION_OPTIONS
import com.xiufeigo.opencodeshell.ui.settings.WorktreeDeleteGuard
import com.xiufeigo.opencodeshell.ui.settings.filterModelRows
import com.xiufeigo.opencodeshell.ui.settings.filterProjectRows
import com.xiufeigo.opencodeshell.ui.settings.modelGroupOrder
import com.xiufeigo.opencodeshell.ui.settings.modelRows
import com.xiufeigo.opencodeshell.ui.settings.pluginPageRows
import com.xiufeigo.opencodeshell.ui.settings.projectPageRows
import com.xiufeigo.opencodeshell.ui.settings.serverShellInfo
import com.xiufeigo.opencodeshell.ui.settings.showsProjectSearch
import com.xiufeigo.opencodeshell.ui.settings.sortedModelGroups
import com.xiufeigo.opencodeshell.ui.settings.visibleModelCount
import com.xiufeigo.opencodeshell.ui.settings.worktreeDeleteGuard
import com.xiufeigo.opencodeshell.ui.terminal.share.TerminalStateStore
import com.xiufeigo.opencodeshell.ui.terminal.share.TerminalTabs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WAVE 2.3a — pure-logic gate for the A4 settings/palette/terminal deltas.
 *
 * Every assertion mirrors an upstream rule cited in the KDoc of the file under
 * test; nothing here touches Compose or Android.
 */
class Wave23aSettingsPaletteTerminalTest {

    private fun session(id: String, archivedAt: Long? = null) =
        OpenCodeClient.Session(id = id, title = id, directory = null, updatedAt = 1, archivedAt = archivedAt)

    // ------------------------------------------------------- providers (P0)

    @Test
    fun popularOrderFollowsUpstreamCatalogOrder() {
        assertEquals(
            listOf("opencode-go", "opencode", "anthropic", "github-copilot", "openai", "google", "openrouter", "vercel"),
            ProviderConnectCatalog.POPULAR_ORDER,
        )
        // Connected ids drop out; the rest keep catalog order.
        assertEquals(
            listOf("opencode-go", "opencode", "anthropic", "github-copilot"),
            ProviderConnectCatalog.popularOrder(setOf("openai", "google", "openrouter", "vercel")),
        )
    }

    @Test
    fun providerNotesAndRecommendedBadgeMatchUpstreamTable() {
        assertEquals("dialog.provider.opencode.note", ProviderConnectCatalog.providerNoteKey("opencode"))
        assertEquals("dialog.provider.opencodeGo.tagline", ProviderConnectCatalog.providerNoteKey("opencode-go"))
        assertEquals("dialog.provider.anthropic.note", ProviderConnectCatalog.providerNoteKey("anthropic"))
        assertEquals("dialog.provider.copilot.note", ProviderConnectCatalog.providerNoteKey("github-copilot-oauth"))
        assertEquals("dialog.provider.openai.note", ProviderConnectCatalog.providerNoteKey("openai"))
        assertEquals("dialog.provider.google.note", ProviderConnectCatalog.providerNoteKey("google"))
        assertEquals("dialog.provider.openrouter.note", ProviderConnectCatalog.providerNoteKey("openrouter"))
        assertEquals("dialog.provider.vercel.note", ProviderConnectCatalog.providerNoteKey("vercel"))
        assertNull(ProviderConnectCatalog.providerNoteKey("other-provider"))
        assertTrue(ProviderConnectCatalog.isRecommended("opencode"))
        assertTrue(ProviderConnectCatalog.isRecommended("opencode-go"))
        assertFalse(ProviderConnectCatalog.isRecommended("anthropic"))
    }

    @Test
    fun sourceBadgesFollowTheIntegrationRule() {
        assertEquals(
            ProviderConnectCatalog.ProviderSource.API,
            ProviderConnectCatalog.resolveSource(listOf("credential"), hasIntegration = true, catalogSource = null),
        )
        assertEquals(
            ProviderConnectCatalog.ProviderSource.ENV,
            ProviderConnectCatalog.resolveSource(listOf("env"), hasIntegration = true, catalogSource = null),
        )
        assertEquals(
            ProviderConnectCatalog.ProviderSource.CONFIG,
            ProviderConnectCatalog.resolveSource(emptyList(), hasIntegration = true, catalogSource = null),
        )
        assertEquals(
            ProviderConnectCatalog.ProviderSource.CUSTOM,
            ProviderConnectCatalog.resolveSource(emptyList(), hasIntegration = false, catalogSource = "custom"),
        )
        assertEquals(
            ProviderConnectCatalog.ProviderSource.OTHER,
            ProviderConnectCatalog.resolveSource(emptyList(), hasIntegration = false, catalogSource = null),
        )
        assertEquals(
            "settings.providers.tag.environment",
            ProviderConnectCatalog.sourceBadgeKey(ProviderConnectCatalog.ProviderSource.ENV),
        )
        assertEquals(
            "provider.connect.method.apiKey",
            ProviderConnectCatalog.sourceBadgeKey(ProviderConnectCatalog.ProviderSource.API),
        )
    }

    @Test
    fun disconnectNeedsACredentialConnection() {
        assertTrue(
            ProviderConnectCatalog.canDisconnect(listOf("credential"), hasIntegration = true, catalogSource = null),
        )
        assertFalse(
            ProviderConnectCatalog.canDisconnect(listOf("env"), hasIntegration = true, catalogSource = null),
        )
        assertFalse(
            ProviderConnectCatalog.canDisconnect(emptyList(), hasIntegration = true, catalogSource = null),
        )
        assertFalse(
            ProviderConnectCatalog.canDisconnect(emptyList(), hasIntegration = false, catalogSource = "env"),
        )
        assertFalse(
            ProviderConnectCatalog.canDisconnect(emptyList(), hasIntegration = false, catalogSource = "config"),
        )
        assertTrue(
            ProviderConnectCatalog.canDisconnect(emptyList(), hasIntegration = false, catalogSource = "custom"),
        )
    }

    @Test
    fun providerRowsPinConnectedFirstInPopularOrder() {
        val rows = listOf(
            com.xiufeigo.opencodeshell.ui.settings.ProviderRow("openai", "OpenAI", true, 3),
            com.xiufeigo.opencodeshell.ui.settings.ProviderRow("zzz", "Z", true, 1),
            com.xiufeigo.opencodeshell.ui.settings.ProviderRow("anthropic", "Anthropic", false, 2),
        )
        val ordered = ProviderConnectCatalog.providerSourceRows(rows)
        assertEquals("openai", ordered.first().id)
        assertTrue(ordered.any { it.id == "opencode-go" })
        assertTrue(ordered.any { it.id == "opencode" })
    }

    @Test
    fun apiKeyFormRejectsBlanks() {
        assertEquals("sk-1", ProviderConnectForm.validateApiKey("  sk-1  "))
        assertNull(ProviderConnectForm.validateApiKey(""))
        assertNull(ProviderConnectForm.validateApiKey("   "))
        assertNull(ProviderConnectForm.validateApiKey(null))
    }

    // ---------------------------------------------------------- models (P0)

    @Test
    fun visibilityDefaultsToVisibleAndRoundTrips() {
        assertTrue(ModelVisibility.isVisible("a", "m", emptyMap()))
        val hidden = ModelVisibility.setVisible(emptyMap(), "a", "m", false)
        assertFalse(ModelVisibility.isVisible("a", "m", hidden))
        assertTrue(ModelVisibility.isVisible("a", "other", hidden))
        assertEquals(hidden, ModelVisibility.decode(ModelVisibility.encode(hidden)))
        assertTrue(ModelVisibility.decode("nope").isEmpty())
        assertTrue(ModelVisibility.decode(null).isEmpty())
        assertEquals("a:m", ModelVisibility.key("a", "m"))
    }

    @Test
    fun modelRowsCarryVisibilityAndCountFilters() {
        val providers = OpenCodeClient.Providers(
            all = listOf(
                OpenCodeClient.Providers.Provider(
                    id = "a",
                    name = "A",
                    models = listOf(
                        OpenCodeClient.Providers.Model(id = "m1", name = "M1", capabilities = null),
                        OpenCodeClient.Providers.Model(id = "m2", name = "M2", capabilities = null),
                    ),
                ),
            ),
            connected = listOf("a"),
            default = emptyMap(),
        )
        assertTrue(modelRows(providers, null).all { it.visible })
        val hidden = ModelVisibility.setVisible(emptyMap(), "a", "m1", false)
        val rows = modelRows(providers, null, hidden)
        assertFalse(rows.first { it.id == "m1" }.visible)
        assertTrue(rows.first { it.id == "m2" }.visible)
        assertEquals(1, visibleModelCount(providers, hidden))
        assertEquals(2, visibleModelCount(providers, emptyMap()))
    }

    @Test
    fun modelGroupsSortPopularFirstAndCollapse() {
        // `models.tsx:57-70`: popular groups by catalog index — anthropic (2)
        // before openai (4) — then provider name.
        assertEquals(
            listOf("anthropic", "openai", "zzz"),
            modelGroupOrder(listOf("zzz", "openai", "anthropic"), emptyMap()),
        )
        val rows = listOf(
            com.xiufeigo.opencodeshell.ui.settings.ModelRow("zzz", "Z", "m", "M", null, false),
            com.xiufeigo.opencodeshell.ui.settings.ModelRow("openai", "OpenAI", "m", "M", null, false),
        )
        val groups: List<ModelGroup> = sortedModelGroups(rows, mapOf("openai" to true))
        assertEquals(listOf("openai", "zzz"), groups.map { it.providerID })
        assertTrue(groups.first { it.providerID == "openai" }.collapsed)
        assertFalse(groups.first { it.providerID == "zzz" }.collapsed)
        // An active search forces every group expanded.
        assertTrue(sortedModelGroups(rows, mapOf("openai" to true), searching = true).none { it.collapsed })
    }

    @Test
    fun collapseCodecRoundTrips() {
        val collapsed = mapOf("a" to true)
        assertEquals(collapsed, ModelVisibility.decodeCollapsed(ModelVisibility.encodeCollapsed(collapsed)))
        assertTrue(ModelVisibility.decodeCollapsed(null).isEmpty())
    }

    // -------------------------------------------------------- shortcuts (P0)

    @Test
    fun registryCoversTheSessionGroups() {
        val ids = SHORTCUT_ROWS.map { it.id }
        // B-5: the settings table is exactly the set of wired ids — a row the
        // shell cannot run must not be listed (`SettingsStructureTest`
        // `everyShortcutRowIsWired` pins the same equality from the other side).
        assertEquals(WIRED_SHORTCUT_IDS, ids.toSet())
        // Every session-route id with a real action is listed…
        for (id in listOf(
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
        )) {
            assertTrue(id, id in ids)
        }
        // …while the palette-only stubs and the dropped rows stay unlisted.
        // (`debugBar.toggle` left this list in B-17: the debug bar landed and the
        // row is now a wired, rebindable one.)
        for (dropped in listOf(
            "terminal.close",
            "context.addSelection",
            "project.copyID",
            "session.archive",
            "session.import",
            "steps.toggle",
            "browser.open",
            "browser.reload",
            "prompt.submit",
            "project.select",
        )) {
            assertFalse(dropped, dropped in ids)
        }
        assertTrue(ids.contains("tab.reopenClosed"))
        assertTrue(ids.contains("session.search"))
        // B-5 wired `session.undo`; it is no longer the inert example.
        assertTrue(WIRED_SHORTCUT_IDS.contains("session.undo"))
        assertTrue(WIRED_SHORTCUT_IDS.contains("tab.reopenClosed"))
    }

    /**
     * B-5: the settings row and the chord table live in two files, so this pins
     * the handler side of the contract by source scan — the same technique
     * `Wave5IntegrationWiringTest` uses for `GlobalChord.SUMMARY`. A row added to
     * [SHORTCUT_ROWS] without an entry in `ui/App.kt`'s
     * `GLOBAL_CHORD_COMMAND_IDS` fails here instead of silently doing nothing.
     */
    @Test
    fun everyWiredShortcutIdHasAChordTableEntry() {
        val file = java.io.File("app/src/main/java/com/xiufeigo/opencodeshell/ui/App.kt")
            .takeIf { it.isFile }
            ?: java.io.File("src/main/java/com/xiufeigo/opencodeshell/ui/App.kt")
        val app = file.readText()
        for (id in WIRED_SHORTCUT_IDS) {
            assertTrue("App.kt must map $id", app.contains("\"$id\""))
        }
        // The ids whose action lives inside a screen go through the request
        // channel instead of a direct call.
        assertTrue(app.contains("intents.requestCommand(id)"))
        assertTrue(app.contains("onCommand = onChordCommand"))
    }

    // ---------------------------------------------------------- palette (P0/P1)

    @Test
    fun sessionCommandsRegisterWithUpstreamKeybinds() {
        val reported = ArrayList<String>()
        // Null actions keep their rows out (the appCommands rule); stubs cover
        // the rest so the registry is complete even before App.kt wires them.
        val commands = SessionCommands.sessionCommands(
            SessionCommands.SessionCommandActions(
                onUndo = {},
                onRedo = {},
                onFork = {},
                onArchive = {},
                // `session.aside` is registered only when the host provides it
                // (`SessionCommands.kt:204`), and it *is* in `SESSION_COMMAND_IDS`
                // — the same rule `session.undo` follows, so a null action here
                // would leave the registry one row short.
                onAskAside = {},
                onPreviousMessage = {},
                onNextMessage = {},
                onToggleAutoAccept = {},
                onFocusInput = {},
                onNewTerminal = {},
                onSelectProject = {},
                onCycleLocation = {},
                onChooseModel = {},
                onCycleVariant = {},
                onCycleAgent = {},
                onCycleAgentReverse = {},
            ),
            stubReporter = SessionCommands.StubReporter { reported.add(it) },
        )
        assertEquals(SessionCommands.SESSION_COMMAND_IDS.sorted(), commands.map { it.id }.sorted())
        assertEquals("Ctrl+B", commands.first { it.id == "session.background" }.keybind)
        assertEquals("Ctrl+Alt+[", commands.first { it.id == "message.previous" }.keybind)
        assertEquals("Ctrl+;", commands.first { it.id == "mcp.toggle" }.keybind)
        assertEquals("Ctrl+Shift+A", commands.first { it.id == "permissions.autoaccept" }.keybind)
        assertEquals("Ctrl+L", commands.first { it.id == "input.focus" }.keybind)
        assertEquals("Ctrl+Alt+T", commands.first { it.id == "terminal.new" }.keybind)
        assertEquals("Ctrl+Shift+L", commands.first { it.id == "context.addSelection" }.keybind)
        assertEquals("Ctrl+Shift+O", commands.first { it.id == "project.select" }.keybind)
        assertEquals("Ctrl+Alt+L", commands.first { it.id == "session.location.cycle" }.keybind)
        assertEquals(CATEGORY_SESSION, commands.first { it.id == "session.undo" }.category)
        assertEquals(CATEGORY_MCP, commands.first { it.id == "mcp.toggle" }.category)
        assertEquals(CATEGORY_PERMISSIONS, commands.first { it.id == "permissions.autoaccept" }.category)
        assertEquals(CATEGORY_VIEW, commands.first { it.id == "input.focus" }.category)
        assertEquals(CATEGORY_TERMINAL, commands.first { it.id == "terminal.new" }.category)
        assertEquals(CATEGORY_FILE, commands.first { it.id == "file.close" }.category)
        assertEquals(CATEGORY_CONTEXT, commands.first { it.id == "context.addSelection" }.category)
        assertEquals(CATEGORY_PROJECT, commands.first { it.id == "project.copyID" }.category)
        assertEquals(CATEGORY_WORKSPACE, commands.first { it.id == "session.location.cycle" }.category)
        assertEquals(CATEGORY_MODEL, commands.first { it.id == "model.choose" }.category)
        // Wired actions call through; stubs report instead of going inert.
        var undone = false
        SessionCommands.sessionCommands(
            SessionCommands.SessionCommandActions(onUndo = { undone = true }),
        ).first { it.id == "session.undo" }.onSelect()
        assertTrue(undone)
        commands.first { it.id == "session.compact" }.onSelect()
        assertEquals(listOf("session.compact"), reported)
        // The hidden terminal.close row stays registered but unlisted.
        assertTrue(commands.first { it.id == "terminal.close" }.hidden)
    }

    @Test
    fun sessionSearchDedupesFiltersArchivedAndCaps() {
        assertTrue(looksLikeSessionID("ses_12345678901234567890123"))
        assertFalse(looksLikeSessionID("ses_short"))
        assertFalse(looksLikeSessionID("other_12345678901234567890123"))
        val listed = (1..60).map { session("ses_listed_%03d_xxxxxxxxxxxxxxxx".format(it)) } +
            session("ses_archived_xxxxxxxxxxxxxxxxxx", archivedAt = 5L)
        val exact = session("ses_exact_xxxxxxxxxxxxxxxxxxxx")
        val entries = sessionSearchEntries(listed, exact, server = "srv")
        assertEquals("ses_exact_xxxxxxxxxxxxxxxxxxxx", entries.first().sessionID)
        assertEquals(SESSION_SEARCH_LIMIT, entries.size)
        assertTrue(entries.none { it.sessionID == "ses_archived_xxxxxxxxxxxxxxxxxx" })
    }

    @Test
    fun activeIndexAnchorsOnId() {
        val entries = listOf(
            PaletteEntry.command("a", "A"),
            PaletteEntry.command("b", "B"),
        )
        assertEquals(1, anchorActiveIndex(entries, "command:b"))
        assertEquals(0, anchorActiveIndex(entries, "command:gone"))
        assertEquals(0, anchorActiveIndex(entries, null))
        assertEquals(0, anchorActiveIndex(emptyList(), "command:a"))
    }

    @Test
    fun recentFilesPinActiveDedupeAndCap() {
        val tabs = listOf("a.md", "b.md", "a.md", "", "c.md", "d.md", "e.md", "f.md")
        val entries = recentFileEntries(tabs, pathOf = { it }, activePath = "b.md")
        assertEquals("file:b.md", entries.first().id)
        assertEquals(ENTRY_LIMIT, entries.size)
        assertEquals(entries.map { it.id }.size, entries.map { it.id }.toSet().size)
    }

    @Test
    fun fileEntriesAreCapped() {
        assertEquals(ENTRY_LIMIT, filePaletteEntries((1..20).map { "f$it.kt" }).size)
        assertTrue(filePaletteEntries(listOf("", "  ")).isEmpty())
    }

    // ----------------------------------------------------------- general (P1)

    @Test
    fun generalOptionTablesMatchUpstreamLiterals() {
        assertEquals(listOf("queue", "steer"), FOLLOW_UP_BEHAVIOR_OPTIONS.map { it.value })
        assertEquals(listOf("hidden", "compact", "full"), REASONING_MODE_OPTIONS.map { it.value })
        assertEquals(listOf("last-used", "local", "new"), WORKSPACE_DESTINATION_OPTIONS.map { it.value })
        assertEquals("steer", SettingsStore.DEFAULT_FOLLOW_UP_BEHAVIOR)
        assertEquals("last-used", SettingsStore.DEFAULT_WORKSPACE_DESTINATION)
        assertTrue(SettingsStore.FOLLOW_UP_BEHAVIORS.containsAll(setOf("queue", "steer")))
        assertTrue(SettingsStore.REASONING_MODES.containsAll(setOf("hidden", "compact", "full")))
        assertTrue(SettingsStore.WORKSPACE_DESTINATIONS.containsAll(setOf("last-used", "local", "new")))
        assertEquals("hidden", SettingsStore.reasoningModeFallback(false, false, false))
        assertEquals("full", SettingsStore.reasoningModeFallback(true, true, true))
        assertEquals("compact", SettingsStore.reasoningModeFallback(false, false, true))
    }

    @Test
    fun serverShellParsesTheLastGlobalDocument() {
        val json = """[
          {"type":"document","info":{"shell":"bash"}},
          {"type":"document","info":{"shell":"zsh"}},
          {"type":"directory","info":{"shell":"sh"}}
        ]"""
        assertEquals("zsh", serverShellInfo(json, listOf("sh", "bash", "zsh")).current)
        assertNull(serverShellInfo(null, emptyList()).current)
        assertNull(serverShellInfo("[]", emptyList()).current)
    }

    // -------------------------------------------------- extensions/pages (P1)

    @Test
    fun pluginRowsParseArrayAndMapShapes() {
        val rows = pluginPageRows("""[{"name":"b"},{"name":"a"}]""")
        assertEquals(listOf("a", "b"), rows.map(PluginPageRow::name))
        assertTrue(pluginPageRows(null).isEmpty())
    }

    @Test
    fun projectSearchFiltersAndGates() {
        val rows = projectPageRows(
            """[{"worktree":"/w/alpha","vcs":"git"},{"worktree":"/w/beta"}]""",
        )
        assertEquals(1, filterProjectRows(rows, "alpha").size)
        assertEquals(2, filterProjectRows(rows, "").size)
        assertTrue(filterProjectRows(rows, "git").any { it.name == "alpha" })
        assertTrue(showsProjectSearch(2))
        assertFalse(showsProjectSearch(1))
    }

    @Test
    fun worktreeDeleteGuardBlocksAndForces() {
        assertEquals(WorktreeDeleteGuard.BLOCKED_ACTIVE, worktreeDeleteGuard(false, 0, true, false))
        assertEquals(WorktreeDeleteGuard.BLOCKED_LINKED, worktreeDeleteGuard(false, 2, false, true))
        assertEquals(WorktreeDeleteGuard.NEEDS_FORCE, worktreeDeleteGuard(true, 0, false, false))
        assertEquals(WorktreeDeleteGuard.OK, worktreeDeleteGuard(true, 0, false, true))
        assertEquals(WorktreeDeleteGuard.OK, worktreeDeleteGuard(false, 0, false, false))
    }

    @Test
    fun providerWriteResultHasThreeOutcomes() {
        val done: ProviderWriteResult = ProviderWriteResult.Done
        val unsupported: ProviderWriteResult = ProviderWriteResult.Unsupported
        val failed: ProviderWriteResult = ProviderWriteResult.Failed("http-500")
        assertTrue(done is ProviderWriteResult.Done)
        assertTrue(unsupported is ProviderWriteResult.Unsupported)
        assertEquals("http-500", (failed as ProviderWriteResult.Failed).reason)
    }

    // ---------------------------------------------------------- terminal (P1)

    @Test
    fun terminalStateRoundTripsTrimsAndDropsExited() {
        val tabs = TerminalTabs()
        tabs.add("pty_1", title = "web")
        tabs.add("pty_2")
        tabs.activate("pty_2")
        val snapshot = TerminalStateStore.snapshot(tabs)
        val decoded = TerminalStateStore.decode(TerminalStateStore.encode(snapshot))
        assertEquals("pty_2", decoded.activeId)
        assertEquals(listOf("pty_1", "pty_2"), decoded.tabs.map { it.id })
        assertEquals("web", decoded.tabs.first { it.id == "pty_1" }.title)
        val dropped = TerminalStateStore.withoutTab(decoded, "pty_2")
        assertEquals(listOf("pty_1"), dropped.tabs.map { it.id })
        assertEquals("pty_1", dropped.activeId)
        val many = (1..25).associate { "w$it" to TerminalStateStore.PersistedState(null, emptyList()) }
        val trimmed = TerminalStateStore.trim(many, currentKey = "w25")
        assertEquals(TerminalStateStore.MAX_WORKSPACES, trimmed.size)
        assertTrue(trimmed.containsKey("w25"))
        assertEquals("", TerminalStateStore.workspaceKey(null))
        assertEquals("/w", TerminalStateStore.workspaceKey("  /w  "))
    }

    @Test
    fun modelFilterIsCaseInsensitiveAcrossFields() {
        val rows = listOf(
            com.xiufeigo.opencodeshell.ui.settings.ModelRow("anthropic", "Anthropic", "claude", "Claude", null, false),
        )
        assertEquals(1, filterModelRows(rows, "ANTH").size)
        assertEquals(1, filterModelRows(rows, "claude").size)
        assertTrue(filterModelRows(rows, "zzz").isEmpty())
    }
}
