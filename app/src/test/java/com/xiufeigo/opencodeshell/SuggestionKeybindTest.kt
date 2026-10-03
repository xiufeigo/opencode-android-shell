package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.composer.ComposerSuggestions
import com.xiufeigo.opencodeshell.ui.session.composer.ComposerSuggestions.Category
import com.xiufeigo.opencodeshell.ui.session.composer.SuggestionKeybind
import com.xiufeigo.opencodeshell.ui.settings.SHORTCUT_ROWS
import com.xiufeigo.opencodeshell.ui.settings.WIRED_SHORTCUT_IDS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B-12: the popover's keybind column and its empty state.
 *
 * Upstream anchors: every `/` command row carries
 * `keybind: command.keybindParts(item.id)` (`composer/model.ts:249-260`) and
 * draws it as the trailing `span.shrink-0` (`editor.tsx:813-815`); the `@`
 * context rows carry none (`model.ts:195-225`); a query with no matches keeps the
 * popover open with one muted line (`editor.tsx:790-792`).
 *
 * The chord table is `SHORTCUT_ROWS` (with the user's overrides applied), so the
 * column can only ever advertise a key the shell actually runs.
 */
class SuggestionKeybindTest {

    private fun slash(
        label: String,
        builtIn: Boolean = false,
        commandId: String? = null,
    ) = ComposerSuggestions.Item(
        category = Category.COMMAND,
        label = label,
        builtIn = builtIn,
        commandId = commandId,
    )

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    // -------------------------------------------------------------- the ids

    @Test
    fun `the three local built-ins map to their registry ids`() {
        assertEquals("model.choose", SuggestionKeybind.commandId(slash("/model", builtIn = true)))
        assertEquals("agent.cycle", SuggestionKeybind.commandId(slash("/agent", builtIn = true)))
        assertEquals(
            ComposerSuggestions.BTW_COMMAND_ID,
            SuggestionKeybind.commandId(slash("/btw", builtIn = true)),
        )
    }

    @Test
    fun `alias rows use the id the palette resolved for them`() {
        // F-1: the composer already resolved `/undo` → `session.undo`; the column
        // reads that id instead of re-deriving it from the trigger.
        assertEquals("session.undo", SuggestionKeybind.commandId(slash("/undo", commandId = "session.undo")))
        assertEquals("file.open", SuggestionKeybind.commandId(slash("/open", commandId = "file.open")))
    }

    @Test
    fun `context rows and server commands carry no keybind`() {
        // A server `GET /command` custom has no registry entry…
        assertNull(SuggestionKeybind.commandId(slash("/init")))
        assertNull(SuggestionKeybind.keybind(slash("/init")))
        // …and the `@` menu's rows never have one at all (upstream's context memo
        // builds no `keybind` field).
        for (label in listOf("build", "@build", "/home/x/a.txt")) {
            assertNull(
                "context row $label",
                SuggestionKeybind.commandId(ComposerSuggestions.Item(Category.AGENT, label)),
            )
        }
    }

    // ------------------------------------------------------------ the chords

    @Test
    fun `the column draws the table's own chord`() {
        assertEquals("Ctrl+'", SuggestionKeybind.keybind(slash("/model", builtIn = true)))
        assertEquals("Ctrl+.", SuggestionKeybind.keybind(slash("/agent", builtIn = true)))
        assertEquals("Ctrl+Shift+S", SuggestionKeybind.keybind(slash("/new", commandId = "session.new")))
        assertEquals("Ctrl+P", SuggestionKeybind.keybind(slash("/open", commandId = "file.open")))
        assertEquals("Ctrl+`", SuggestionKeybind.keybind(slash("/terminal", commandId = "terminal.toggle")))
        assertEquals("Ctrl+;", SuggestionKeybind.keybind(slash("/mcp", commandId = "mcp.toggle")))
    }

    @Test
    fun `an unassigned binding draws nothing`() {
        // `session.undo` / `session.redo` / `session.aside` are listed with an
        // empty chord: the settings panel says「未分配」, the popover draws no
        // column at all (`<Show when={item.keybind?.length}>`).
        assertNull(SuggestionKeybind.keybind(slash("/undo", commandId = "session.undo")))
        assertNull(SuggestionKeybind.keybind(slash("/btw", builtIn = true)))
        assertNull(SuggestionKeybind.keybind(slash("/x", commandId = "not.a.command")))
    }

    @Test
    fun `every assigned chord in the table is what the row would draw`() {
        for (row in SHORTCUT_ROWS) {
            val expected = row.keybind
                .takeIf { it.isNotBlank() && row.id in WIRED_SHORTCUT_IDS }
            assertEquals(
                row.id,
                expected,
                SuggestionKeybind.keybind(slash("/x", commandId = row.id)),
            )
        }
    }

    @Test
    fun `a recorded override is what the row shows`() {
        val rows = SuggestionKeybind.effectiveRows(mapOf("model.choose" to "Ctrl+M"))
        assertEquals("Ctrl+M", SuggestionKeybind.keybind(slash("/model", builtIn = true), rows))
        // A cleared binding (`none`) draws nothing, like an unassigned one.
        val cleared = SuggestionKeybind.effectiveRows(mapOf("model.choose" to "none"))
        assertNull(SuggestionKeybind.keybind(slash("/model", builtIn = true), cleared))
        // …and an id the user never touched keeps its default.
        assertEquals("Ctrl+.", SuggestionKeybind.keybind(slash("/agent", builtIn = true), rows))
    }

    // -------------------------------------------------------- the empty state

    @Test
    fun `the empty state is the two popover strings`() {
        assertEquals(R.string.prompt_popover_emptyCommands, SuggestionKeybind.emptyLabelRes(slash = true))
        assertEquals(R.string.prompt_popover_emptyResults, SuggestionKeybind.emptyLabelRes(slash = false))
        // Both ship in all three locales (the generated resources carry them).
        for (dir in listOf("values", "values-zh-rCN", "values-zh-rTW")) {
            val xml = source("src/main/res/$dir/strings_oc_i18n.xml")
            assertTrue("$dir emptyCommands", xml.contains("name=\"prompt_popover_emptyCommands\""))
            assertTrue("$dir emptyResults", xml.contains("name=\"prompt_popover_emptyResults\""))
        }
    }

    // ------------------------------------------------------------- the wiring

    @Test
    fun `the popover renders both columns and the composer keeps it open when empty`() {
        val popover = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/MentionPopover.kt")
        assertTrue("keybind column", popover.contains("SuggestionKeybind.keybind(item, keybindRows)"))
        assertTrue("empty state row", popover.contains("emptyLabelRes?.let { stringResource(it) }"))
        assertTrue("empty row is drawn when the list is empty", popover.contains("if (items.isEmpty())"))

        val composer = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt")
        assertTrue(
            "the card stays mounted while a trigger is live",
            composer.contains("ComposerMention.popoverVisible(") &&
                composer.contains("items.isNotEmpty(),") &&
                composer.contains("live.slashQuery,") &&
                composer.contains("live.mention,"),
        )
        assertTrue(
            "the rule itself is the trigger, never the row list",
            composer.contains("): Boolean = hasRows || slashQuery != null || trigger != null"),
        )
        assertTrue("visible drives the card", composer.contains("visible = popoverVisible"))
        assertTrue("the two labels are chosen per menu", composer.contains("SuggestionKeybind.emptyLabelRes(slash = live.slashQuery != null)"))
        assertTrue("overrides reach the table", composer.contains("SuggestionKeybind.effectiveRows(keybindOverrides)"))
        assertTrue("empty list cannot divide by zero", composer.contains("if (items.isEmpty())"))
        assertTrue("keybindOverrides parameter", composer.contains("keybindOverrides: Map<String, String> = emptyMap()"))

        val screen = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")
        assertTrue("screen forwards the overrides", screen.contains("keybindOverrides = keybindOverrides"))
        val app = source("src/main/java/com/xiufeigo/opencodeshell/ui/App.kt")
        assertTrue("app feeds the recorded table", app.contains("keybindOverrides = settings.keybinds"))
    }
}
