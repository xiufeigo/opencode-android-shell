package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.palette.CATEGORY_COMMANDS
import com.xiufeigo.opencodeshell.ui.palette.CATEGORY_FILES
import com.xiufeigo.opencodeshell.ui.palette.CATEGORY_SESSION
import com.xiufeigo.opencodeshell.ui.palette.COMMON_COMMAND_IDS
import com.xiufeigo.opencodeshell.ui.palette.DEFAULT_PALETTE_KEYBIND
import com.xiufeigo.opencodeshell.ui.palette.ENTRY_LIMIT
import com.xiufeigo.opencodeshell.ui.palette.PALETTE_ID
import com.xiufeigo.opencodeshell.ui.palette.PaletteEntry
import com.xiufeigo.opencodeshell.ui.palette.PaletteCommand
import com.xiufeigo.opencodeshell.ui.palette.PaletteHostKind
import com.xiufeigo.opencodeshell.ui.palette.PaletteKey
import com.xiufeigo.opencodeshell.ui.palette.PaletteSources
import com.xiufeigo.opencodeshell.ui.palette.commandEntries
import com.xiufeigo.opencodeshell.ui.palette.commandFor
import com.xiufeigo.opencodeshell.ui.palette.emptyQueryEntries
import com.xiufeigo.opencodeshell.ui.palette.flattenRows
import com.xiufeigo.opencodeshell.ui.palette.groupEntries
import com.xiufeigo.opencodeshell.ui.palette.isPaletteChord
import com.xiufeigo.opencodeshell.ui.palette.isSettingsChord
import com.xiufeigo.opencodeshell.ui.palette.matchesEntry
import com.xiufeigo.opencodeshell.ui.palette.moveIndex
import com.xiufeigo.opencodeshell.ui.palette.paletteSplitPath
import com.xiufeigo.opencodeshell.ui.palette.placeholderResId
import com.xiufeigo.opencodeshell.ui.palette.searchEntries
import com.xiufeigo.opencodeshell.ui.palette.sources
import com.xiufeigo.opencodeshell.ui.palette.toEntry
import com.xiufeigo.opencodeshell.ui.palette.uniqueEntries
import com.xiufeigo.opencodeshell.ui.palette.visibleEntries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic gate for `research/02` §6 (command palette) — the assertions mirror
 * the verified web behaviour one-for-one, so a regression in the grouping /
 * filtering / empty-query contract fails here instead of on device.
 */
class SettingsPaletteModelTest {

    private fun command(id: String, title: String = id, keybind: String? = null) =
        PaletteEntry.command(id = id, title = title, keybind = keybind)

    private fun session(id: String, title: String = id) =
        PaletteEntry.session(server = "srv", sessionID = id, title = title)

    private fun file(path: String) = PaletteEntry.file(path)

    // ------------------------------------------------------------ constants

    @Test
    fun paletteConstantsMatchUpstream() {
        assertEquals("command.palette", PALETTE_ID)
        assertEquals("mod+k,mod+shift+p", DEFAULT_PALETTE_KEYBIND)
        assertEquals(5, ENTRY_LIMIT)
        assertEquals(6, COMMON_COMMAND_IDS.size)
        assertEquals(
            listOf("session.new", "workspace.new", "session.previous", "session.next", "terminal.toggle", "review.toggle"),
            COMMON_COMMAND_IDS,
        )
    }

    @Test
    fun entryIdsFollowTheThreeDocumentedPrefixes() {
        assertEquals("command:session.new", command("session.new").id)
        assertEquals("file:a/b.md", file("a/b.md").id)
        assertEquals("session:srv:ses_1", session("ses_1").id)
    }

    // -------------------------------------------------------------- grouping

    @Test
    fun groupsPreserveCategoryOrderAndAreNonEmpty() {
        val entries = listOf(
            command("a"),
            file("x.md"),
            session("s1"),
            command("b"),
        )
        val groups = groupEntries(entries)
        assertEquals(listOf(CATEGORY_COMMANDS, CATEGORY_FILES, CATEGORY_SESSION), groups.map { it.first })
        assertEquals(listOf("command:a", "command:b"), groups[0].second.map { it.id })
        assertEquals(listOf("file:x.md"), groups[1].second.map { it.id })
        assertEquals(listOf("session:srv:s1"), groups[2].second.map { it.id })
        // Session rows hang under `command.category.session`, not `palette.group.*`.
        assertEquals("Session", groups[2].first)
    }

    @Test
    fun uniqueEntriesKeepTheFirstOccurrence() {
        val deduped = uniqueEntries(listOf(command("a", "first"), command("a", "second"), command("b")))
        assertEquals(listOf("command:a", "command:b"), deduped.map { it.id })
        assertEquals("first", deduped.first().title)
    }

    @Test
    fun flattenRowsWalksGroupsInOrder() {
        val grouped = groupEntries(listOf(command("a"), file("x.md"), session("s1")))
        assertEquals(
            listOf("command:a", "file:x.md", "session:srv:s1"),
            flattenRows(grouped).map { it.id },
        )
    }

    // ---------------------------------------------------------- empty query

    @Test
    fun emptyQueryShowsCommonCommandIdsInDeclaredOrder() {
        val shuffled = listOf(
            command("review.toggle"),
            command("terminal.toggle"),
            command("session.next"),
            command("session.previous"),
            command("workspace.new"),
            command("session.new"),
            command("settings.open"),
        )
        val entries = emptyQueryEntries(PaletteSources(commands = shuffled))
        assertEquals(
            listOf(
                "command:session.new",
                "command:workspace.new",
                "command:session.previous",
                "command:session.next",
                "command:terminal.toggle",
            ),
            entries.map { it.id },
        )
    }

    @Test
    fun emptyQueryFallsBackToFirstFiveWhenNoCommonIdsExist() {
        val commands = (1..8).map { command("cmd$it") }
        val entries = emptyQueryEntries(PaletteSources(commands = commands))
        assertEquals(5, entries.size)
        assertEquals(listOf("command:cmd1", "command:cmd2", "command:cmd3", "command:cmd4", "command:cmd5"), entries.map { it.id })
    }

    @Test
    fun emptyQueryAppendsRecentFilesForSessionHostOnly() {
        val recent = listOf(file("src/a.md"), file("src/b.md"))
        val sessionHost = PaletteSources(commands = listOf(command("session.new")), recentFiles = recent)
        assertEquals(
            listOf("command:session.new", "file:src/a.md", "file:src/b.md"),
            emptyQueryEntries(sessionHost).map { it.id },
        )

        // Home = `commandEntries().slice(0, 5)` with NO file category at all.
        val home = PaletteHostKind.HOME.sources(commands = listOf(command("session.new")), recentFiles = recent)
        assertFalse(home.includeFiles)
        assertEquals(listOf("command:session.new"), emptyQueryEntries(home).map { it.id })
    }

    @Test
    fun hostSourcesDifferOnlyInFileVisibility() {
        val commands = listOf(command("session.new"))
        val sessions = listOf(session("s1"))
        val files = listOf(file("a.md"))
        val recent = listOf(file("b.md"))

        val home = PaletteHostKind.HOME.sources(commands, sessions, files, recent)
        assertFalse(home.includeFiles)
        assertTrue(home.files.isEmpty())

        val session = PaletteHostKind.SESSION.sources(commands, sessions, files, recent)
        assertTrue(session.includeFiles)
        assertEquals(listOf("file:a.md"), session.files.map { it.id })
        assertEquals(listOf("file:b.md"), session.recentFiles.map { it.id })
    }

    // -------------------------------------------------------------- matching

    @Test
    fun matchesEntrySearchesTitleDescriptionAndCategory() {
        val entry = PaletteEntry.command(
            id = "settings.open",
            title = "Open settings",
            description = "Preferences",
            category = "Settings",
        )
        assertTrue(matchesEntry(entry, ""))
        assertTrue(matchesEntry(entry, "OPEN"))
        assertTrue(matchesEntry(entry, "prefer"))
        assertTrue(matchesEntry(entry, "setting"))
        assertFalse(matchesEntry(entry, "terminal"))
    }

    @Test
    fun nonEmptyQueryReturnsCommandsSessionsAndFiles() {
        val sources = PaletteSources(
            commands = listOf(command("session.new", "New session")),
            sessions = listOf(session("s1", "New session two")),
            files = listOf(file("src/new-thing.md")),
        )
        val hits = searchEntries(sources, "new").map { it.id }
        assertTrue(hits.contains("command:session.new"))
        assertTrue(hits.contains("session:srv:s1"))
        assertTrue(hits.contains("file:src/new-thing.md"))

        // Home never surfaces file hits.
        val homeHits = searchEntries(sources.copy(includeFiles = false), "new").map { it.id }
        assertFalse(homeHits.any { it.startsWith("file:") })
    }

    @Test
    fun visibleEntriesFallsBackToEmptyQueryWhenBlank() {
        val sources = PaletteSources(commands = listOf(command("review.toggle"), command("session.new")))
        assertEquals(listOf("command:session.new", "command:review.toggle"), visibleEntries(sources, "").map { it.id })
        assertEquals(listOf("command:review.toggle"), visibleEntries(sources, "review").map { it.id })
        assertTrue(visibleEntries(sources, "nothing-matches-this").isEmpty())
    }

    // ------------------------------------------------------------- keyboard

    @Test
    fun moveIndexWrapsBothWaysAndIsZeroOnEmpty() {
        // §6.6 `move(delta)` = `(i + delta + count) % count` — 首尾循环.
        // Cast to Long so JUnit's `assertEquals(long, long)` overload is chosen.
        assertEquals(1L, moveIndex(0, 1, 3).toLong())
        assertEquals(2L, moveIndex(1, 1, 3).toLong())
        assertEquals(0L, moveIndex(2, 1, 3).toLong())
        assertEquals(2L, moveIndex(0, -1, 3).toLong())
        assertEquals(1L, moveIndex(2, -1, 3).toLong())
        assertEquals(0L, moveIndex(0, 1, 0).toLong())
    }

    @Test
    fun paletteAndSettingsChordsMatchTheDocumentedBindings() {
        // `mod+k` (no Shift) / `mod+shift+p` open the palette; `mod+p` is
        // `file.open`, and `mod+shift+k` does NOT match (upstream
        // `matchKeybind` compares every modifier strictly, `command.tsx:188-204`).
        assertTrue(isPaletteChord(ctrlOrMeta = true, keyK = true, keyP = false, shift = false))
        assertFalse(isPaletteChord(ctrlOrMeta = true, keyK = true, keyP = false, shift = true))
        assertTrue(isPaletteChord(ctrlOrMeta = true, keyK = false, keyP = true, shift = true))
        assertFalse(isPaletteChord(ctrlOrMeta = true, keyK = false, keyP = true, shift = false))
        assertFalse(isPaletteChord(ctrlOrMeta = false, keyK = true, keyP = false, shift = false))
        assertFalse(isPaletteChord(ctrlOrMeta = false, keyK = false, keyP = true, shift = true))

        // `settings.open` = `mod+comma`.
        assertTrue(isSettingsChord(ctrlOrMeta = true, keyComma = true))
        assertFalse(isSettingsChord(ctrlOrMeta = false, keyComma = true))
    }

    @Test
    fun paletteKeyContractIsExhaustive() {
        // §6.6: ArrowDown/ArrowUp move, Enter selects, Escape closes, no Tab.
        assertEquals(
            listOf("ARROW_DOWN", "ARROW_UP", "ENTER", "ESCAPE"),
            PaletteKey.entries.map { it.name },
        )
    }

    // ------------------------------------------------------------ file rows

    @Test
    fun fileRowSplitsIntoDirectoryAndFileName() {
        assertEquals("src/ui/" to "App.kt", paletteSplitPath("src/ui/App.kt"))
        assertEquals("" to "App.kt", paletteSplitPath("App.kt"))
        assertEquals("C:/a/" to "b.md", paletteSplitPath("C:\\a\\b.md"))
    }

    @Test
    fun commandEntryProjectionKeepsMetadata() {
        val command = PaletteCommand(
            id = "settings.open",
            titleResId = R.string.oc_command_settings_open,
            keybind = "Ctrl+,",
            onSelect = {},
        )
        val entry = command.toEntry()
        assertEquals("command:settings.open", entry.id)
        assertEquals(CATEGORY_COMMANDS, entry.category)
        assertEquals("Ctrl+,", entry.keybind)
        // Title resolution needs a resource table, so the projection leaves it
        // blank and `commandEntries(resolveTitle = …)` fills it (see below).
        assertEquals("", entry.title)
        val resolved = commandEntries(listOf(command)) { "Open settings" }
        assertEquals("Open settings", resolved.single().title)
    }

    @Test
    fun hiddenCommandsAreRegisteredButNotListed() {
        val hidden = PaletteCommand(
            id = "command.palette",
            titleResId = R.string.oc_palette_title,
            hidden = true,
            onSelect = {},
        )
        assertTrue(commandEntries(listOf(hidden)) { "Command palette" }.isEmpty())
        assertEquals(
            "command:command.palette",
            commandFor(listOf(hidden), command("command.palette"))?.entryId,
        )
    }

    @Test
    fun placeholderDiffersBetweenHomeAndSessionHosts() {
        assertEquals(R.string.oc_palette_placeholder_home, PaletteHostKind.HOME.placeholderResId())
        assertEquals(R.string.oc_palette_placeholder, PaletteHostKind.SESSION.placeholderResId())
    }

    @Test
    fun unknownCommandLookupReturnsNull() {
        assertNull(commandFor(emptyList(), command("nope")))
    }
}
