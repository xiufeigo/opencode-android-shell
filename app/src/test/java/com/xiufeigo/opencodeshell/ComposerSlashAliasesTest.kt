package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.palette.SessionCommands
import com.xiufeigo.opencodeshell.ui.session.composer.ComposerSuggestions
import com.xiufeigo.opencodeshell.ui.session.composer.ComposerSuggestions.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-1: the composer's `/` menu carries the session-level slash aliases, and a
 * typed known slash resolves to the palette command instead of a prompt.
 *
 * Upstream anchors: `composer/model.ts:230-247` (`slashCommands` = server
 * customs + every registered command with a `slash` field) and
 * `session/commands/use-session-commands.tsx:257-340` (the alias registrations).
 * The trigger → command-id table itself lives in [SessionCommands] (single
 * source); this file pins the composer's consumption of it.
 */
class ComposerSlashAliasesTest {

    private fun command(name: String, description: String = "", source: String = "command") =
        OpenCodeClient.CommandInfo(
            name = name,
            description = description,
            source = source,
            template = "",
        )

    private fun alias(trigger: String, commandId: String, title: String = trigger) =
        ComposerSuggestions.SessionSlashRow(
            trigger = trigger,
            commandId = commandId,
            title = title,
        )

    private val sessionAliases: List<ComposerSuggestions.SessionSlashRow> =
        SessionCommands.SESSION_SLASH_COMMAND_IDS.map { (trigger, id) ->
            alias(trigger, id, title = trigger.replaceFirstChar { it.uppercase() })
        }

    // ------------------------------------------------------- the slash table

    @Test
    fun slashTableCoversTheUpstreamAliases() {
        val table = SessionCommands.SLASH_COMMAND_IDS
        assertEquals("model.choose", table["model"])
        assertEquals("agent.cycle", table["agent"])
        assertEquals("session.new", table["new"])
        assertEquals("session.undo", table["undo"])
        assertEquals("session.redo", table["redo"])
        assertEquals("session.compact", table["compact"])
        assertEquals("session.fork", table["fork"])
        assertEquals("session.export", table["export"])
        assertEquals("file.open", table["open"])
        assertEquals("terminal.toggle", table["terminal"])
        assertEquals("mcp.toggle", table["mcp"])
        assertEquals(11, table.size)
    }

    @Test
    fun sessionSlashSubsetExcludesTheComposerBuiltIns() {
        val session = SessionCommands.SESSION_SLASH_COMMAND_IDS
        assertFalse("model opens the chip menu, not the palette", session.containsKey("model"))
        assertFalse("agent opens the chip menu, not the palette", session.containsKey("agent"))
        assertEquals(9, session.size)
        assertEquals("session.undo", session["undo"])
    }

    @Test
    fun slashLookupsRoundTrip() {
        assertEquals("undo", SessionCommands.slashForCommand("session.undo"))
        assertEquals("terminal", SessionCommands.slashForCommand("terminal.toggle"))
        assertEquals("session.export", SessionCommands.commandIdForSlash("export"))
        assertNull(SessionCommands.slashForCommand("session.archive"))
        assertNull(SessionCommands.commandIdForSlash("nope"))
    }

    // ------------------------------------------------------------- menu merge

    @Test
    fun aliasRowsFollowTheServerCatalog() {
        val items = ComposerSuggestions.commandItems(
            query = "",
            commands = listOf(command("init", "guided setup")),
            sessionAliases = sessionAliases,
        )
        assertEquals(
            listOf("/model", "/agent", "/init", "/btw", "/new", "/undo", "/redo", "/compact", "/fork", "/export", "/open", "/terminal", "/mcp"),
            items.map { it.label },
        )
        val undo = items.first { it.label == "/undo" }
        assertEquals(Category.COMMAND, undo.category)
        assertEquals("session.undo", undo.commandId)
        assertFalse("aliases run the palette row, not a local built-in", undo.builtIn)
    }

    @Test
    fun aliasRowsFilterOnTriggerAndTitle() {
        assertEquals(
            listOf("/undo"),
            ComposerSuggestions.commandItems("und", emptyList(), sessionAliases).map { it.label },
        )
        // Title match works too ("Compact" title above).
        assertEquals(
            listOf("/compact"),
            ComposerSuggestions.commandItems("COMPACT", emptyList(), sessionAliases).map { it.label },
        )
        assertTrue(ComposerSuggestions.commandItems("zzz", emptyList(), sessionAliases).isEmpty())
    }

    @Test
    fun aSameNamedServerCustomKeepsItsRowOverTheAlias() {
        // Menu order is built-ins → server customs → aliases, so `distinctBy`
        // label keeps the server row — the same precedence submit-time
        // resolution gives `invocation` over `aliasCommandId`.
        val items = ComposerSuggestions.commandItems(
            query = "",
            commands = listOf(command("undo", "project-specific undo")),
            sessionAliases = sessionAliases,
        )
        val undo = items.filter { it.label == "/undo" }
        assertEquals(1, undo.size)
        assertNull("the surviving row is the server command", undo.single().commandId)
        assertEquals("project-specific undo", undo.single().description)
    }

    // ----------------------------------------------------- submit-time resolve

    @Test
    fun knownSlashesResolveToPaletteCommands() {
        val aliases = SessionCommands.SESSION_SLASH_COMMAND_IDS
        assertEquals("session.undo", ComposerSuggestions.aliasCommandId("/undo", aliases))
        assertEquals("session.compact", ComposerSuggestions.aliasCommandId("/compact focus", aliases))
        assertEquals("terminal.toggle", ComposerSuggestions.aliasCommandId("/terminal", aliases))
    }

    @Test
    fun unknownSlashesFallThroughToPrompts() {
        val aliases = SessionCommands.SESSION_SLASH_COMMAND_IDS
        // The composer built-ins are not palette-routed at submit time.
        assertNull(ComposerSuggestions.aliasCommandId("/model", aliases))
        assertNull(ComposerSuggestions.aliasCommandId("/agent", aliases))
        assertNull(ComposerSuggestions.aliasCommandId("/nope", aliases))
        assertNull(ComposerSuggestions.aliasCommandId("/nope args", aliases))
        assertNull(ComposerSuggestions.aliasCommandId("undo", aliases))
        assertNull(ComposerSuggestions.aliasCommandId("/", aliases))
        assertNull(ComposerSuggestions.aliasCommandId("", aliases))
        assertNull("no registered rows means no routing", ComposerSuggestions.aliasCommandId("/undo", emptyMap()))
    }

    @Test
    fun fullWidthSlashesResolveLikeAscii() {
        val aliases = SessionCommands.SESSION_SLASH_COMMAND_IDS
        assertEquals("session.undo", ComposerSuggestions.aliasCommandId("／undo", aliases))
        assertEquals("session.redo", ComposerSuggestions.aliasCommandId("／ｒｅｄｏ", aliases))
    }

    // --------------------------------------- fuzzy-subsequence filter (upstream)

    @Test
    fun slashMenuFiltersLikeTheUpstreamFuzzyList() {
        val commands = listOf(
            command("init", "guided setup"),
            command("export", "export the transcript"),
            command("open", "open a file"),
        )
        // Upstream's `useFilteredList` runs this menu through `fuzzysort` over
        // `[trigger, title]`, so one-letter queries stay broad — exactly like
        // upstream, not narrowed to the prefix row.
        assertEquals(
            listOf("/model", "/agent", "/export", "/open", "/new", "/redo", "/terminal"),
            ComposerSuggestions.commandItems("e", commands, sessionAliases).map { it.label },
        )
        // A prefix is itself a subsequence, so the launcher narrowing keeps
        // working — `/op` still reaches `/open` (plus the subsequence `/compact`).
        assertEquals(
            listOf("/open", "/compact"),
            ComposerSuggestions.commandItems("op", commands, sessionAliases).map { it.label },
        )
    }

    @Test
    fun deviceAliasesSurfaceUnderTheirPrefixes() {
        // The v6_verify gap: `/op` `/te` `/fo` matched nothing because the
        // aliases were never registered; with the registry wired they narrow
        // to exactly one row each.
        // Alias-table order (`compact` precedes `open` there) is the menu
        // order for the alias block.
        assertEquals(
            listOf("/compact", "/open"),
            ComposerSuggestions.commandItems("op", emptyList(), sessionAliases).map { it.label },
        )
        assertEquals(
            listOf("/terminal"),
            ComposerSuggestions.commandItems("te", emptyList(), sessionAliases).map { it.label },
        )
        assertEquals(
            listOf("/fork"),
            ComposerSuggestions.commandItems("fo", emptyList(), sessionAliases).map { it.label },
        )
        assertEquals(
            listOf("/fork"),
            ComposerSuggestions.commandItems("FORK", emptyList(), sessionAliases).map { it.label },
        )
    }

    @Test
    fun slashMatchingKeepsTheBlankQueryAndFullWidthBehavior() {
        assertTrue(ComposerSuggestions.slashMatches("", "anything"))
        assertTrue(ComposerSuggestions.slashMatches("ＭＯ", "model"))
        assertTrue(ComposerSuggestions.slashMatches("mod", "model"))
        // Fuzzy subsequence now: a mid-word start still matches, like the
        // upstream `fuzzysort` list — and the `/` stays outside the trigger.
        assertTrue(ComposerSuggestions.slashMatches("odel", "model"))
        assertTrue("non-adjacent letters still match, in order", ComposerSuggestions.slashMatches("ml", "model"))
        assertTrue("`trm` reaches `/terminal` (5554)", ComposerSuggestions.slashMatches("trm", "terminal"))
        assertFalse(ComposerSuggestions.slashMatches("trm", "model"))
    }

    // ------------------------------------------- P1-C same-table registry

    @Test
    fun slashRegistryCombinesTheAppAndSessionTables() {
        val app = listOf(
            fakeCommand("session.new"),
            fakeCommand("file.open"),
            fakeCommand("terminal.toggle"),
            fakeCommand("settings.open"),
        )
        val session = listOf(
            fakeCommand("session.undo"),
            fakeCommand("session.fork"),
            fakeCommand("session.export"),
            fakeCommand("mcp.toggle"),
        )
        val combined = SessionCommands.slashRegistry(app, session)

        assertEquals(
            listOf(
                "session.new",
                "file.open",
                "terminal.toggle",
                "settings.open",
                "session.undo",
                "session.fork",
                "session.export",
                "mcp.toggle",
            ),
            combined.map { it.id },
        )
        // First registration wins on an id clash.
        val clash = SessionCommands.slashRegistry(listOf(fakeCommand("x")), listOf(fakeCommand("x")))
        assertEquals(1, clash.size)
    }

    @Test
    fun slashRegistryCommandsCoverEveryAliasTrigger() {
        val table = SessionCommands.slashRegistryCommands(
            SessionCommands.slashRegistry(
                app = listOf(
                    fakeCommand("session.new"),
                    fakeCommand("file.open"),
                    fakeCommand("terminal.toggle"),
                ),
                session = listOf(
                    fakeCommand("session.undo"),
                    fakeCommand("session.redo"),
                    fakeCommand("session.compact"),
                    fakeCommand("session.fork"),
                    fakeCommand("session.export"),
                    fakeCommand("mcp.toggle"),
                ),
            ),
        )
        val resolved = table.map { it.id }.toSet()
        for ((trigger, id) in SessionCommands.SESSION_SLASH_COMMAND_IDS) {
            assertTrue("alias /$trigger ($id) resolves against the combined table", id in resolved)
        }
        // `model`/`agent` stay composer built-ins — never palette-routed.
        assertTrue(table.none { it.id == "model.choose" || it.id == "agent.cycle" })
    }

    private fun fakeCommand(id: String) =
        com.xiufeigo.opencodeshell.ui.palette.PaletteCommand(
            id = id,
            titleResId = R.string.oc_command_terminal_toggle,
            onSelect = {},
        )
}
