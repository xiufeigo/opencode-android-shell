package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.composer.ComposerSuggestions
import com.xiufeigo.opencodeshell.ui.session.composer.ComposerSuggestions.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W5-C B1 + B9, pure side: the `/` command menu's candidate list and the `@`
 * context menu's four categories.
 *
 * Upstream anchors under test: `/^\/(\S*)$/` (`composer/suggestions/machine.ts:107-114`),
 * the built-ins registered by `composer/commands.tsx:49-82`, the
 * `[...fixed, ...files]` ordering of `composer/editor/interaction.ts:107-136`, and
 * `findCommand` + `$ARGUMENTS` expansion (`composer/submit.ts:296-322`).
 */
class Wave5ComposerSuggestionsTest {

    private fun command(name: String, description: String = "", source: String = "command") =
        OpenCodeClient.CommandInfo(
            name = name,
            description = description,
            source = source,
            template = "",
        )

    private fun agent(name: String, mode: String = "primary", hidden: Boolean = false) =
        OpenCodeClient.Agent(
            name = name,
            description = "$name description",
            mode = mode,
            native = true,
            hidden = hidden,
        )

    private fun skill(name: String, description: String = "") =
        OpenCodeClient.SkillInfo(name = name, description = description)

    // ------------------------------------------------------------ `/` detection

    @Test
    fun onlyALoneSlashWordOpensTheCommandMenu() {
        assertEquals("", ComposerSuggestions.commandQuery("/"))
        assertEquals("mod", ComposerSuggestions.commandQuery("/mod"))
        assertEquals("init", ComposerSuggestions.commandQuery("/init"))
        // `/name args` is a submitted command, not a menu query.
        assertNull(ComposerSuggestions.commandQuery("/init extra"))
        assertNull(ComposerSuggestions.commandQuery("ask /x"))
        assertNull(ComposerSuggestions.commandQuery(""))
        assertNull(ComposerSuggestions.commandQuery("hi"))
    }

    // ------------------------------------------------------------- command menu

    @Test
    fun commandMenuListsTheBuiltInsFirstThenTheServerCatalog() {
        val items = ComposerSuggestions.commandItems(
            query = "",
            commands = listOf(command("init", "guided AGENTS.md setup"), command("review", "review the diff")),
        )
        // Built-ins (`/model` `/agent`), the server catalog, then `/btw` — the
        // registered command that also runs locally (`btw.tsx:57`).
        assertEquals(
            listOf("/model", "/agent", "/init", "/review", "/btw"),
            items.map { it.label },
        )
        assertTrue("built-ins run locally", items[0].builtIn && items[1].builtIn)
        assertFalse("server commands are submitted", items[2].builtIn)
        assertEquals("guided AGENTS.md setup", items[2].description)
        assertTrue("`/btw` opens the side-question panel", items[4].builtIn)
    }

    @Test
    fun commandMenuFiltersLikeTheUpstreamFilteredList() {
        val commands = listOf(
            command("init", "guided AGENTS.md setup"),
            command("review", "review the working tree diff"),
            command("gsd-verify-work", "verify a workstream"),
            command("terminal", "toggle the terminal"),
        )
        // Upstream's `useFilteredList` runs this menu through `fuzzysort` over
        // `[trigger, title]` (`packages/ui/src/hooks/use-filtered-list.tsx`),
        // so the menu answers abbreviations, not just prefixes: `trm` reaches
        // `/terminal` (5554), while every prefix narrowing (5556) keeps working
        // because a prefix is itself a subsequence.
        assertEquals(listOf("/init"), ComposerSuggestions.commandItems("ini", commands).map { it.label })
        assertEquals(listOf("/review"), ComposerSuggestions.commandItems("REVIEW", commands).map { it.label })
        assertEquals(listOf("/review"), ComposerSuggestions.commandItems("rev", commands).map { it.label })
        assertEquals(
            listOf("/gsd-verify-work"),
            ComposerSuggestions.commandItems("gsd", commands).map { it.label },
        )
        assertEquals(
            listOf("/gsd-verify-work"),
            ComposerSuggestions.commandItems("work", commands).map { it.label },
        )
        assertEquals(
            listOf("/terminal"),
            ComposerSuggestions.commandItems("trm", commands).map { it.label },
        )
        assertTrue(ComposerSuggestions.commandItems("nomatch", commands).isEmpty())
    }

    @Test
    fun skillSourcedCommandsAreCategorizedAsSkills() {
        val items = ComposerSuggestions.commandItems(
            query = "kb",
            commands = listOf(command("kb-query", "search the knowledge base", source = "skill")),
        )
        assertEquals(listOf("/kb-query"), items.map { it.label })
        assertEquals(Category.SKILL, items.single().category)
    }

    @Test
    fun commandMenuIsCapped() {
        val commands = (1..40).map { command("cmd$it") }
        assertEquals(ComposerSuggestions.LIMIT, ComposerSuggestions.commandItems("", commands).size)
    }

    // ------------------------------------------------------------- `@` menu

    @Test
    fun mentionMenuCarriesAllFourCategoriesInOrder() {
        val items = ComposerSuggestions.mentionItems(
            query = "",
            agents = listOf(agent("build"), agent("plan")),
            commands = listOf(command("init")),
            skills = listOf(skill("kb")),
            files = listOf("src/Main.kt", "README.md"),
        )
        assertEquals(
            listOf(Category.AGENT, Category.AGENT, Category.COMMAND, Category.SKILL, Category.FILE, Category.FILE),
            items.map { it.category },
        )
        assertEquals(
            listOf("build", "plan", "init", "kb", "src/Main.kt", "README.md"),
            items.map { it.label },
        )
    }

    @Test
    fun mentionMenuFiltersEachCategoryAndKeepsTheCategoryOrder() {
        val items = ComposerSuggestions.mentionItems(
            query = "pl",
            agents = listOf(agent("build"), agent("plan")),
            commands = listOf(command("plan-tasks"), command("init")),
            skills = listOf(skill("pl-skill"), skill("kb")),
            // File rows arrive already filtered by `/find/file?query=` (upstream
            // `searchContextFiles`, `interaction.ts:112-114`), so both stay listed
            // — the menu never re-filters what the server answered.
            files = listOf("pl/an/roadmap.md", "src/pl.kt"),
        )
        assertEquals(
            listOf(Category.AGENT, Category.COMMAND, Category.SKILL, Category.FILE, Category.FILE),
            items.map { it.category },
        )
        assertEquals(
            listOf("plan", "plan-tasks", "pl-skill", "pl/an/roadmap.md", "src/pl.kt"),
            items.map { it.label },
        )
    }

    @Test
    fun hiddenAgentsAndDuplicatesAreDropped() {
        val items = ComposerSuggestions.mentionItems(
            query = "",
            // `hidden` helpers (title/summary/compaction) are never offered, and a
            // prompt repeated inside one category collapses to one row.
            agents = listOf(agent("build"), agent("title", hidden = true), agent("build")),
            commands = listOf(command("build"), command("build")),
            skills = emptyList(),
            files = listOf("build", " build ", ""),
        )
        // Two rows share the label "build" — one agent, one file — because
        // upstream keys suggestions by id/kind, not by label
        // (`interaction.ts:116-118 groupBy`), and the row's category icon is what
        // tells them apart.
        assertEquals(listOf("build", "build"), items.map { it.label })
        assertEquals(listOf(Category.AGENT, Category.FILE), items.map { it.category })
    }

    @Test
    fun mentionMenuIsCapped() {
        val files = (1..50).map { "src/file$it.kt" }
        assertEquals(
            ComposerSuggestions.LIMIT,
            ComposerSuggestions.mentionItems("", emptyList(), emptyList(), emptyList(), files).size,
        )
    }

    // -------------------------------------------------- submitting a `/name`

    @Test
    fun onlyServerCommandsAreInterceptedAtSubmitTime() {
        val known = setOf("init", "review")
        assertEquals(
            ComposerSuggestions.Invocation("init", ""),
            ComposerSuggestions.invocation("/init", known),
        )
        assertEquals(
            ComposerSuggestions.Invocation("init", "focus on tests"),
            ComposerSuggestions.invocation("/init focus on tests", known),
        )
        // The built-ins are not server commands: they stay ordinary prompts.
        assertNull(ComposerSuggestions.invocation("/model", known))
        assertNull(ComposerSuggestions.invocation("/nope", known))
        assertNull(ComposerSuggestions.invocation("init", known))
        assertNull(ComposerSuggestions.invocation("/", known))
    }

    @Test
    fun slashLabelsAlwaysCarryTheSlash() {
        assertEquals("/model", ComposerSuggestions.slashLabel(ComposerSuggestions.BUILT_IN_MODEL))
        assertEquals("/agent", ComposerSuggestions.slashLabel(ComposerSuggestions.BUILT_IN_AGENT))
    }

    @Test
    fun templatesExpandArgumentsOrAppendThem() {
        assertEquals("do stuff with extra", ComposerSuggestions.expandTemplate("do stuff with \$ARGUMENTS", "extra"))
        assertEquals("do stuff with ", ComposerSuggestions.expandTemplate("do stuff with \$ARGUMENTS", ""))
        // No placeholder: the arguments are appended on their own line.
        assertEquals("plain\n\nargs here", ComposerSuggestions.expandTemplate("plain", "args here"))
        assertEquals("plain", ComposerSuggestions.expandTemplate("plain", "   "))
    }

    @Test
    fun matchingIsCaseInsensitiveAndBlankQueryMatchesEverything() {
        assertTrue(ComposerSuggestions.matches("", "anything"))
        assertTrue(ComposerSuggestions.matches("PLAN", "plan mode"))
        assertFalse(ComposerSuggestions.matches("plan", "build mode"))
    }
}
