package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.composer.ComposerMention
import com.xiufeigo.opencodeshell.ui.session.composer.ComposerSuggestions
import com.xiufeigo.opencodeshell.ui.session.composer.MentionText
import com.xiufeigo.opencodeshell.ui.session.composer.SuggestionKeybind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v64: the two popovers are a function of the editor's **own** text and caret
 * ([ComposerSuggestions.triggerAt]), and a bare trigger lists the whole catalog.
 *
 * Device evidence this pins:
 * - `research/v64_verify/5556/02_24_slash_only.png` — a bare `/` drew the
 *   *context* empty state (「No matching results」, not 「No matching
 *   commands」) with no row at all, because the row source and the empty-state
 *   copy were read from a `slashQuery` mirror the editor's own text no longer
 *   described.
 * - `research/v64_verify/5558/07b_mention_panel.png` and `08b_context_panel.png`
 *   — `@`, and `+` → Context, drew nothing: the `@` trigger was read from the
 *   same kind of mirror.
 */
class ComposerPopoverTriggerV64Test {

    private fun command(name: String, description: String = "") = OpenCodeClient.CommandInfo(
        name = name,
        description = description,
        source = "command",
        template = "",
    )

    private fun agent(name: String) = OpenCodeClient.Agent(
        name = name,
        description = "$name description",
        mode = "primary",
        native = true,
        hidden = false,
    )

    // --------------------------------------------------------- triggerAt

    @Test
    fun aBareSlashIsTheCommandPositionAndNothingElse() {
        val trigger = ComposerSuggestions.triggerAt("/", caret = 1)
        assertEquals("", trigger.slashQuery)
        assertTrue(trigger.inSlashPosition)
        assertNull(trigger.mention)
        assertFalse(trigger.inMentionPosition)
    }

    @Test
    fun aBareAtIsTheMentionPositionAndNothingElse() {
        val trigger = ComposerSuggestions.triggerAt("@", caret = 1)
        assertEquals(MentionText.Anchor(0, ""), trigger.mention)
        assertTrue(trigger.inMentionPosition)
        assertNull(trigger.slashQuery)
        assertFalse(trigger.inSlashPosition)
    }

    @Test
    fun theMentionQueryRunsFromTheAtToTheCaret() {
        val trigger = ComposerSuggestions.triggerAt("look at @src/mai", caret = 16)
        assertEquals(MentionText.Anchor(8, "src/mai"), trigger.mention)
        assertNull("a mention is not a command query", trigger.slashQuery)
    }

    @Test
    fun aWordWithNoTriggerIsIdle() {
        val trigger = ComposerSuggestions.triggerAt("hello", caret = 5)
        assertEquals(ComposerSuggestions.IDLE_TRIGGER, trigger)
        assertFalse(trigger.inSlashPosition)
        assertFalse(trigger.inMentionPosition)
    }

    @Test
    fun aFullWidthTriggerOnAChineseImeReadsTheSame() {
        assertEquals("", ComposerSuggestions.triggerAt("／", caret = 1).slashQuery)
        assertEquals(
            MentionText.Anchor(0, ""),
            ComposerSuggestions.triggerAt("＠", caret = 1).mention,
        )
    }

    // --------------------------------------------------------- row source

    @Test
    fun theSlashPositionDrawsTheCommandRowsAndTheMentionPositionTheContextRows() {
        val commandRow = ComposerSuggestions.Item(ComposerSuggestions.Category.COMMAND, "/model")
        val mentionRow = ComposerSuggestions.Item(ComposerSuggestions.Category.AGENT, "build")
        assertEquals(
            listOf(commandRow),
            ComposerSuggestions.rowsFor(
                ComposerSuggestions.triggerAt("/", 1),
                listOf(commandRow),
                listOf(mentionRow),
            ),
        )
        assertEquals(
            listOf(mentionRow),
            ComposerSuggestions.rowsFor(
                ComposerSuggestions.triggerAt("@", 1),
                listOf(commandRow),
                listOf(mentionRow),
            ),
        )
        assertEquals(
            // Neither surface is in a trigger shape, so the composer shows
            // nothing — `popoverVisible` is what gates the card on rows.
            emptyList<ComposerSuggestions.Item>(),
            ComposerSuggestions.rowsFor(
                ComposerSuggestions.IDLE_TRIGGER,
                emptyList(),
                emptyList(),
            ),
        )
    }

    // ------------------------------------------------- the empty query lists

    @Test
    fun aBareSlashListsEveryCommand() {
        val items = ComposerSuggestions.commandItems(
            query = ComposerSuggestions.triggerAt("/", 1).slashQuery!!,
            commands = listOf(command("init", "setup"), command("review", "review the diff")),
        )
        assertEquals(
            listOf("/model", "/agent", "/init", "/review", "/btw"),
            items.map { it.label },
        )
    }

    @Test
    fun aBareAtListsEveryContextRow() {
        val items = ComposerSuggestions.mentionItems(
            query = ComposerSuggestions.triggerAt("@", 1).mention!!.query,
            agents = listOf(agent("build"), agent("plan")),
            commands = listOf(command("init")),
            skills = listOf(OpenCodeClient.SkillInfo(name = "review", description = "")),
            files = listOf("/home/xiufeigo/v36projC/README.md"),
        )
        assertEquals(
            listOf("build", "plan", "init", "review", "/home/xiufeigo/v36projC/README.md"),
            items.map { it.label },
        )
    }

    // ------------------------------------------------------- the open state

    @Test
    fun aTriggerOpensTheCardEvenWithNoRows() {
        assertTrue(
            ComposerMention.popoverVisible(
                hasRows = false,
                slashQuery = ComposerSuggestions.triggerAt("/", 1).slashQuery,
                trigger = null,
            ),
        )
        assertTrue(
            ComposerMention.popoverVisible(
                hasRows = false,
                slashQuery = null,
                trigger = ComposerSuggestions.triggerAt("@", 1).mention,
            ),
        )
        assertFalse(
            ComposerMention.popoverVisible(
                hasRows = false,
                slashQuery = ComposerSuggestions.IDLE_TRIGGER.slashQuery,
                trigger = ComposerSuggestions.IDLE_TRIGGER.mention,
            ),
        )
        assertEquals(
            "the bare `/` card says 「no matching commands」, not 「no matching results」",
            R.string.prompt_popover_emptyCommands,
            SuggestionKeybind.emptyLabelRes(
                slash = ComposerSuggestions.triggerAt("/", 1).slashQuery != null,
            ),
        )
    }

    // ------------------------------------------------------------ the wiring

    @Test
    fun theComposerReadsItsTriggersOffTheEditorText() {
        val composer = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt")
        assertTrue(
            "one reading of the text drives both surfaces",
            composer.contains("ComposerSuggestions.triggerAt(value.text, value.selection.end)"),
        )
        assertTrue(
            "the row source follows that reading",
            composer.contains("ComposerSuggestions.rowsFor(live, slashItems, mentionItems)"),
        )
        assertTrue(
            "the empty-state copy follows it too",
            composer.contains("SuggestionKeybind.emptyLabelRes(slash = live.slashQuery != null)"),
        )
        assertTrue(
            "the `/find/file` ask is keyed off it — and re-asked per session",
            composer.contains("LaunchedEffect(live.mention, draftKey)"),
        )
    }

    @Test
    fun thePlusMenuContextEntryOpensTheSamePanelAsATypedAt() {
        val composer = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt")
        // `+` → Context inserts the trigger, and the trigger is now read off the
        // text — so the entry and a typed `@` are the same path.
        assertTrue(composer.contains("onContext = { insertText(\"@\") }"))
        val inserted = ComposerSuggestions.triggerAt("@", caret = 1)
        val afterTyping = ComposerSuggestions.triggerAt("x@", caret = 2)
        assertEquals(MentionText.Anchor(1, ""), afterTyping.mention)
        assertTrue(inserted.inMentionPosition && afterTyping.inMentionPosition)
    }

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")
}
