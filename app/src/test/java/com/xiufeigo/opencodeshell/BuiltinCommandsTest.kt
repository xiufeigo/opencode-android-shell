package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.palette.BuiltinCommands
import com.xiufeigo.opencodeshell.ui.palette.PaletteEntry
import com.xiufeigo.opencodeshell.ui.palette.PaletteEntryType
import com.xiufeigo.opencodeshell.ui.palette.PaletteSources
import com.xiufeigo.opencodeshell.ui.palette.visibleEntries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The palette's self-sufficient builtin rows ([BuiltinCommands]): they append
 * after the host's registered commands, never displace them, and are the ids
 * `CommandPalette` intercepts instead of forwarding to `ui/App.kt`.
 */
class BuiltinCommandsTest {

    private val builtin = PaletteEntry.command(id = BuiltinCommands.AGENT_DETAILS, title = "View agent details")

    @Test
    fun entryIdsUseTheCommandPrefix() {
        assertEquals("command:agent.details", BuiltinCommands.entryId(BuiltinCommands.AGENT_DETAILS))
        assertTrue(BuiltinCommands.isBuiltin("command:debug.locations"))
        assertTrue(BuiltinCommands.isBuiltin("command:plugin.rpc"))
        assertFalse(BuiltinCommands.isBuiltin("command:settings.open"))
        assertFalse(BuiltinCommands.isBuiltin("agent.details"))
    }

    @Test
    fun builtinsAppendAfterTheRegisteredRows() {
        val host = PaletteEntry.command(id = "settings.open", title = "Open settings")
        val merged = BuiltinCommands.withBuiltinCommands(
            PaletteSources(commands = listOf(host)),
            listOf(builtin),
        )
        assertEquals(listOf(host.id, builtin.id), merged.commands.map { it.id })
    }

    @Test
    fun aHostRegisteredIdWinsOverTheBuiltin() {
        val host = PaletteEntry.command(id = BuiltinCommands.AGENT_DETAILS, title = "Host copy")
        val merged = BuiltinCommands.withBuiltinCommands(
            PaletteSources(commands = listOf(host)),
            listOf(builtin),
        )
        assertEquals(listOf(host), merged.commands)
    }

    @Test
    fun builtinsStayOutOfTheEmptyQueryTopFive() {
        // `emptyQueryEntries` keeps `commands.take(ENTRY_LIMIT)` on Home: the
        // appended builtins surface through the query search instead of
        // displacing upstream's empty-query rows.
        val host = (1..5).map { PaletteEntry.command(id = "c$it", title = "Command $it") }
        val merged = BuiltinCommands.withBuiltinCommands(PaletteSources(commands = host), listOf(builtin))
        val empty = visibleEntries(merged.copy(includeFiles = false), "")
        assertEquals(host.map { it.id }, empty.map { it.id })
        val search = visibleEntries(merged.copy(includeFiles = false), "agent")
        assertEquals(listOf(builtin.id), search.map { it.id })
        assertEquals(PaletteEntryType.COMMAND, search.single().type)
    }
}
