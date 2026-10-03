package com.xiufeigo.opencodeshell

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import com.xiufeigo.opencodeshell.ui.GlobalChord
import com.xiufeigo.opencodeshell.ui.globalChord
import com.xiufeigo.opencodeshell.ui.palette.CATEGORY_COMMANDS
import com.xiufeigo.opencodeshell.ui.palette.COMMON_COMMAND_IDS
import com.xiufeigo.opencodeshell.ui.palette.PaletteEntry
import com.xiufeigo.opencodeshell.ui.palette.PaletteEntryType
import com.xiufeigo.opencodeshell.ui.palette.PaletteHostKind
import com.xiufeigo.opencodeshell.ui.palette.appCommands
import com.xiufeigo.opencodeshell.ui.palette.commandEntries
import com.xiufeigo.opencodeshell.ui.palette.placeholderResId
import com.xiufeigo.opencodeshell.ui.palette.preferredCommandEntries
import com.xiufeigo.opencodeshell.ui.palette.sessionPaletteEntries
import com.xiufeigo.opencodeshell.ui.palette.sources
import com.xiufeigo.opencodeshell.ui.palette.steppedSessionId
import com.xiufeigo.opencodeshell.ui.palette.visibleEntries
import com.xiufeigo.opencodeshell.ui.settings.ModelRow
import com.xiufeigo.opencodeshell.ui.settings.SettingsTab
import com.xiufeigo.opencodeshell.ui.settings.TimelineDisplayPrefs
import com.xiufeigo.opencodeshell.ui.settings.connectedModelCount
import com.xiufeigo.opencodeshell.ui.settings.connectedProviderCount
import com.xiufeigo.opencodeshell.ui.settings.filterModelRows
import com.xiufeigo.opencodeshell.ui.settings.formatContextLimit
import com.xiufeigo.opencodeshell.ui.settings.modelRows
import com.xiufeigo.opencodeshell.ui.settings.providerRows
import com.xiufeigo.opencodeshell.ui.settings.searchCatalogTitleIds
import com.xiufeigo.opencodeshell.ui.settings.searchSettings
import com.xiufeigo.opencodeshell.ui.settings.titleResId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W4-D pure-logic gate: the command registry, the Providers/Models projections,
 * the settings search and the palette host/step helpers — everything the shell
 * wiring added that does not need a Compose runtime.
 */
class ShellWiringTest {

    private fun model(id: String, name: String = id, context: Int? = null) =
        OpenCodeClient.Providers.Model(
            id = id,
            name = name,
            capabilities = null,
            limitContext = context,
        )

    // ------------------------------------------------------------ commands

    @Test
    fun everyCommandRowIsWiredToARealAction() {
        var called = 0
        val commands = appCommands(
            onOpenSettings = { called++ },
            onOpenTerminal = { called++ },
            onNewSession = { called++ },
            onNewWorkspace = { called++ },
            onPreviousSession = { called++ },
            onNextSession = { called++ },
            onToggleReview = { called++ },
            onOpenFilePicker = { called++ },
            onToggleFileTree = { called++ },
        )
        val ids = commands.map { it.id }
        // The five `COMMON_COMMAND_IDS` rows the empty query shows must all exist…
        assertTrue(COMMON_COMMAND_IDS.all { it in ids })
        // …plus the `file.open` picker and the file tree toggle.
        assertTrue("file.open" in ids)
        assertTrue("fileTree.toggle" in ids)
        // `tab.new` is registered but hidden (`titlebar.tsx:388-396`).
        assertTrue(commands.first { it.id == "tab.new" }.hidden)
        assertTrue(commandEntries(commands) { "" }.none { it.id == "command:tab.new" })
        // Every row executes its action.
        commands.filterNot { it.id == "terminal.toggle" }.forEach { it.onSelect() }
        assertEquals(commands.size - 1, called)
    }

    @Test
    fun emptyQueryCommandsAreAllExecutable() {
        var called = 0
        val commands = appCommands(
            onOpenSettings = {},
            onOpenTerminal = null,
            onNewSession = { called++ },
            onNewWorkspace = { called++ },
            onPreviousSession = { called++ },
            onNextSession = { called++ },
            onToggleReview = { called++ },
            onOpenFilePicker = {},
            onToggleFileTree = {},
        )
        val entry = commandEntries(commands) { "title" }
        val preferred = preferredCommandEntries(entry)
        assertEquals(
            listOf(
                "command:session.new",
                "command:workspace.new",
                "command:session.previous",
                "command:session.next",
                "command:terminal.toggle",
            ),
            preferred.map { it.id },
        )
        // Four of the five rows fire; the fifth hides behind `terminal.toggle`,
        // which the preview host (null action) deliberately leaves inert.
        preferred.filterNot { it.id == "command:terminal.toggle" }.forEach { row ->
            commands.first { it.entryId == row.id }.onSelect()
        }
        assertEquals(4, called)
    }

    @Test
    fun fileTreeRowOnlyExistsWhileTheSettingIsOn() {
        // `visibility.fileTree` → `shown()` (`use-session-commands.tsx:375`).
        val on = appCommands(onOpenSettings = {}, onToggleFileTree = {}, showFileTree = true)
        val off = appCommands(onOpenSettings = {}, onToggleFileTree = {}, showFileTree = false)
        assertTrue(on.any { it.id == "fileTree.toggle" })
        assertFalse(off.any { it.id == "fileTree.toggle" })
    }

    @Test
    fun commandKeybindsMatchUpstreamNotTheOldPlaceholders() {
        val commands = appCommands(
            onOpenSettings = {},
            onNewSession = {},
            onPreviousSession = {},
            onNextSession = {},
            onToggleReview = {},
            onOpenFilePicker = {},
            onToggleFileTree = {},
        ).associateBy { it.id }
        assertEquals("Ctrl+,", commands.getValue("settings.open").keybind)
        assertEquals("Ctrl+Shift+S", commands.getValue("session.new").keybind)
        assertEquals("Ctrl+N", commands.getValue("tab.new").keybind)
        assertEquals("Ctrl+P", commands.getValue("file.open").keybind)
        assertEquals("Ctrl+Shift+R", commands.getValue("review.toggle").keybind)
        assertEquals("Ctrl+\\", commands.getValue("fileTree.toggle").keybind)
        assertEquals(CATEGORY_COMMANDS, commands.getValue("file.open").category)
    }

    // --------------------------------------------------------- session rows

    @Test
    fun sessionRowsCarryOpenMarkerAndMeta() {
        val sessions = listOf(
            OpenCodeClient.Session(id = "ses_1", title = "First", directory = "/w/a", updatedAt = 300),
            OpenCodeClient.Session(id = "ses_2", title = "Second", directory = null, updatedAt = 200),
        )
        val entries = sessionPaletteEntries(
            sessions = sessions,
            server = "srv",
            openSessionIds = setOf("ses_2"),
            meta = { if (it.id == "ses_1") "3m ago" else null },
        )
        assertEquals("3m ago", entries[0].meta)
        assertNull(entries[1].meta)
        assertFalse(entries[0].open)
        assertTrue(entries[1].open)
        assertEquals("/w/a", entries[0].description)
        assertEquals(PaletteEntryType.SESSION, entries[0].type)
    }

    @Test
    fun steppingWrapsAroundAndStartsFromTheEnds() {
        val sessions = listOf(
            OpenCodeClient.Session(id = "a", title = "a", directory = null, updatedAt = 3),
            OpenCodeClient.Session(id = "b", title = "b", directory = null, updatedAt = 2),
            OpenCodeClient.Session(id = "c", title = "c", directory = null, updatedAt = 1),
        )
        assertEquals("b", steppedSessionId(sessions, "a", 1))
        assertEquals("c", steppedSessionId(sessions, "b", 1))
        assertEquals("a", steppedSessionId(sessions, "c", 1))
        assertEquals("c", steppedSessionId(sessions, "a", -1))
        // Not on any session yet: next starts at the top, previous at the bottom.
        assertEquals("a", steppedSessionId(sessions, null, 1))
        assertEquals("c", steppedSessionId(sessions, null, -1))
        assertEquals("a", steppedSessionId(sessions, "unknown", 1))
        // Nothing to move to.
        assertNull(steppedSessionId(emptyList(), "a", 1))
        assertNull(steppedSessionId(sessions, "a", 0))
        assertNull(steppedSessionId(listOf(sessions[0]), "a", 1))
    }

    // ------------------------------------------------------- palette hosts

    @Test
    fun filesOnlyHostDropsCommandsAndSessions() {
        // `palette.ts:96-99` `filesOnly()`: `allowedCommands` is empty.
        val sources = PaletteHostKind.FILES_ONLY.sources(
            commands = listOf(PaletteEntry.command("session.new", "New session")),
            sessions = listOf(PaletteEntry.session("srv", "ses_1", "session")),
            files = listOf(PaletteEntry.file("src/a.kt")),
        )
        assertTrue(sources.commands.isEmpty())
        assertTrue(sources.sessions.isEmpty())
        val rows = visibleEntries(sources, "a")
        assertEquals(listOf("file:src/a.kt"), rows.map { it.id })
        assertEquals(R.string.oc_palette_placeholder_files, PaletteHostKind.FILES_ONLY.placeholderResId())
    }

    @Test
    fun homeHostHasNoFileCategoryAndNoFileHits() {
        val sources = PaletteHostKind.HOME.sources(
            commands = listOf(PaletteEntry.command("settings.open", "Open settings")),
            sessions = listOf(PaletteEntry.session("srv", "ses_1", "palette hit")),
            files = listOf(PaletteEntry.file("src/palette.kt")),
        )
        assertFalse(sources.includeFiles)
        val rows = visibleEntries(sources, "palette")
        assertTrue(rows.none { it.type == PaletteEntryType.FILE })
        assertTrue(rows.any { it.sessionID == "ses_1" })
    }

    // ------------------------------------------------ providers / models

    @Test
    fun providerRowsPinConnectedFirstAndCountModels() {
        val providers = OpenCodeClient.Providers(
            all = listOf(
                OpenCodeClient.Providers.Provider(
                    id = "zeta",
                    name = "Zeta",
                    models = listOf(model("z1"), model("z2")),
                ),
                OpenCodeClient.Providers.Provider(
                    id = "alpha",
                    name = "Alpha",
                    models = listOf(model("a1", context = 131_072)),
                ),
            ),
            connected = listOf("zeta"),
            default = mapOf("zeta" to "z1"),
        )
        val rows = providerRows(providers)
        assertEquals(listOf("zeta", "alpha"), rows.map { it.id })
        assertEquals(true, rows[0].connected)
        assertEquals(false, rows[1].connected)
        assertEquals(2, rows[0].modelCount)
        assertEquals("z1", rows[0].defaultModelID)
        assertEquals(1, connectedProviderCount(providers))
        // Full-caliber count: every cataloged model, not just connected ones.
        assertEquals(3, connectedModelCount(providers))
        // Nothing fetched yet.
        assertTrue(providerRows(null).isEmpty())
        assertEquals(0, connectedProviderCount(null))
    }

    @Test
    fun modelRowsListEveryProviderAndFlagConnected() {
        val providers = OpenCodeClient.Providers(
            all = listOf(
                OpenCodeClient.Providers.Provider(
                    id = "zeta",
                    name = "Zeta",
                    models = listOf(model("z1"), model("z2", context = 200_000)),
                ),
                OpenCodeClient.Providers.Provider(
                    id = "alpha",
                    name = "Alpha",
                    models = listOf(model("a1")),
                ),
            ),
            connected = listOf("zeta"),
            default = emptyMap(),
        )
        val rows = modelRows(providers, selected = "zeta" to "z2")
        // Catalog order is preserved; unconnected providers stay listed —
        // `connected` only marks the row (v2 divergence, see ModelRow).
        assertEquals(listOf("zeta/z1", "zeta/z2", "alpha/a1"), rows.map { it.ref })
        assertEquals(listOf(false, true, false), rows.map { it.current })
        assertEquals(listOf(true, true, false), rows.map { it.connected })
        assertEquals(200_000, rows[1].limitContext)
        assertEquals(3, connectedModelCount(providers))
    }

    @Test
    fun modelSearchMatchesNameIdProviderAndRef() {
        val providers = OpenCodeClient.Providers(
            all = listOf(
                OpenCodeClient.Providers.Provider("zeta", "Zeta", listOf(model("gpt-5", name = "GPT-5"))),
                OpenCodeClient.Providers.Provider("alpha", "Alpha", listOf(model("sonnet", name = "Sonnet"))),
            ),
            connected = listOf("zeta", "alpha"),
            default = emptyMap(),
        )
        val rows = modelRows(providers, selected = null)
        assertEquals(2, filterModelRows(rows, "").size)
        assertEquals(listOf("zeta/gpt-5"), filterModelRows(rows, "gpt").map { it.ref })
        assertEquals(listOf("zeta/gpt-5"), filterModelRows(rows, "GPT-5").map { it.ref })
        assertEquals(listOf("alpha/sonnet"), filterModelRows(rows, "ALPHA").map { it.ref })
        assertTrue(filterModelRows(rows, "nothing").isEmpty())
    }

    @Test
    fun contextLimitIsFormattedForTheRowMeta() {
        assertEquals("128k", formatContextLimit(131_072))
        assertEquals("1M", formatContextLimit(1_048_576))
        assertEquals("32000", formatContextLimit(32_000))
        assertNull(formatContextLimit(null))
        assertNull(formatContextLimit(0))
    }

    // ------------------------------------------------------ settings search

    @Test
    fun searchCatalogCoversPagesRowsAndKeybinds() {
        val ids = searchCatalogTitleIds()
        assertTrue(ids.containsAll(SettingsTab.entries.map { it.titleResId() }))
        assertTrue(ids.contains(R.string.oc_setting_auto_accept_permissions))
        assertTrue(ids.contains(R.string.oc_shortcut_palette))
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun searchFindsPagesAndRowsAndFiltersOutNonMatches() {
        // `titleOf` stands in for `stringResource`, so the search stays pure.
        val titles = mapOf(
            SettingsTab.GENERAL.titleResId() to "General",
            SettingsTab.SHORTCUTS.titleResId() to "Shortcuts",
            SettingsTab.SERVERS.titleResId() to "Servers",
            SettingsTab.PROVIDERS.titleResId() to "Providers",
            SettingsTab.MODELS.titleResId() to "Models",
            R.string.oc_setting_auto_accept_permissions to "Auto-accept permissions",
            R.string.oc_setting_terminal_font to "Terminal Font",
            R.string.oc_shortcut_palette to "Command palette",
        )
        val titleOf: (Int) -> String = { titles[it].orEmpty() }

        val all = searchSettings("", titleOf)
        assertTrue(all.any { it.id == "page:general" })
        assertTrue(all.any { it.id == "row:settings-auto-accept-permissions" })

        // "term" hits the Terminal Font row (now on the Appearance page,
        // upstream `appearance.tsx:126`), the Terminal placement row B3 added to
        // General (upstream `general.tsx:339`) and the `terminal.*` keybind rows
        // (their `data-action` is searchable too). The set of pages is exact, so
        // a row moving to another page still fails here.
        val terminal = searchSettings("term", titleOf)
        assertTrue(terminal.any { it.id == "row:settings-terminal-font" })
        assertTrue(terminal.any { it.id == "row:settings-terminal-placement" })
        assertEquals(
            setOf(SettingsTab.GENERAL, SettingsTab.APPEARANCE, SettingsTab.SHORTCUTS),
            terminal.map { it.tab }.toSet(),
        )

        // A page hit keeps its own tab (`page:models`).
        val models = searchSettings("models", titleOf)
        assertEquals(SettingsTab.MODELS, models.first { it.id == "page:models" }.tab)

        // The row's `data-action` is searchable too.
        assertTrue(searchSettings("settings-terminal-font", titleOf).isNotEmpty())
        assertTrue(searchSettings("zzz-nothing", titleOf).isEmpty())
    }

    // ------------------------------------------------------- display prefs

    @Test
    fun timelinePrefsDefaultsMirrorTheStoreDefaults() {
        // A preview without the provider must render what a fresh install does.
        assertEquals(true, TimelineDisplayPrefs.Default.reasoningSummaries)
        assertEquals(false, TimelineDisplayPrefs.Default.shellToolPartsExpanded)
        assertEquals(false, TimelineDisplayPrefs.Default.editToolPartsExpanded)
    }

    // ----------------------------------------------------------- chords

    @Test
    fun globalChordsMapEveryBindingTheShellImplements() {
        // `mod+k` (no shift) / `mod+shift+p` → palette.
        assertEquals(GlobalChord.PALETTE, chord(Key.K))
        assertEquals(GlobalChord.PALETTE, chord(Key.P, shift = true))
        // `mod+p` (no shift) → `file.open`'s files-only palette.
        assertEquals(GlobalChord.PALETTE_FILES, chord(Key.P))
        // `mod+comma` → settings.
        assertEquals(GlobalChord.SETTINGS, chord(Key.Comma))
        // `mod+n` (`tab.new`) / `mod+shift+s` (`session.new`).
        assertEquals(GlobalChord.NEW_SESSION, chord(Key.N))
        assertEquals(GlobalChord.NEW_SESSION, chord(Key.S, shift = true))
        // `ctrl+`` → terminal toggle (KEYCODE_GRAVE on Android).
        assertEquals(GlobalChord.TERMINAL, chord(Key.Grave))
    }

    @Test
    fun globalChordsIgnoreWrongModifiersAndKeyUp() {
        // Upstream compares every modifier strictly (`command.tsx:188-204`).
        assertNull(globalChord(KeyEventType.KeyDown, Key.K, ctrl = false, meta = false, shift = false))
        assertNull(globalChord(KeyEventType.KeyDown, Key.K, ctrl = true, meta = false, shift = true))
        assertNull(globalChord(KeyEventType.KeyDown, Key.N, ctrl = true, meta = false, shift = true))
        assertNull(globalChord(KeyEventType.KeyDown, Key.S, ctrl = true, meta = false, shift = false))
        assertNull(globalChord(KeyEventType.KeyDown, Key.Q, ctrl = true, meta = false, shift = false))
        // Key-up never fires a chord twice.
        assertNull(globalChord(KeyEventType.KeyUp, Key.K, ctrl = true, meta = false, shift = false))
        // Meta (⌘) works as the same modifier as Ctrl.
        assertEquals(GlobalChord.PALETTE, globalChord(KeyEventType.KeyDown, Key.K, ctrl = false, meta = true, shift = false))
    }

    private fun chord(key: Key, shift: Boolean = false): GlobalChord? =
        globalChord(KeyEventType.KeyDown, key, ctrl = true, meta = false, shift = shift)
}
