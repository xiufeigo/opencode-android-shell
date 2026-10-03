package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.palette.SessionCommands
import com.xiufeigo.opencodeshell.ui.session.composer.ComposerSubmit
import com.xiufeigo.opencodeshell.ui.session.composer.ComposerSuggestions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The **submit** side of upstream's argument-taking slash commands — the
 * registrations carrying `slash: { name, arguments: true }`, which
 * `argumentSlash` (`_upstream/packages/tui/src/component/prompt/index.tsx:173-184`)
 * resolves from the submitted line *before* the server-command lookup:
 *
 * ```
 * const slash = argumentSlash(inputText, keymapCommands())
 * if (slash) { clearPrompt(); await slash.command.run(slash.input); return true }
 * ```
 *
 * Device v15 measured `/btw what is 2+2` in the composer and read it as a dead
 * send button. The gate is not shaped by the line: `ComposerSubmit.canSubmit` /
 * `stopping` decide on content alone, `commandQuery` (`^/(\S*)$`) only opens and
 * closes the **completion menu**, and the argument command is resolved from the
 * submitted text — so `/btw a b c` submits and lands on the side-question panel.
 * These tests pin that split, the upstream `arguments: true` set, and the routes
 * of every other slash that carries arguments.
 */
class ComposerSlashArgumentsTest {

    private fun composer() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt")

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    // ------------------------------------------------------ submittable + routed

    @Test
    fun btwWithArgumentsIsSubmittable() {
        val line = "/btw a b c"
        assertTrue(
            "the send button is enabled for a slash-argument line",
            ComposerSubmit.canSubmit(line, attachmentCount = 0, shell = false),
        )
        assertFalse(
            "a typed line keeps the send button — it never turns into stop",
            ComposerSubmit.stopping(running = true, text = line, attachmentCount = 0),
        )
        // The other two shapes the button can see: a bare trigger and the
        // question the device test typed.
        assertTrue(ComposerSubmit.canSubmit("/btw", 0, shell = false))
        assertTrue(ComposerSubmit.canSubmit("/btw what is 2+2", 0, shell = false))
    }

    @Test
    fun btwWithArgumentsResolvesToTheSideQuestion() {
        assertEquals("a b c", ComposerSuggestions.btwQuestion("/btw a b c"))
        assertEquals("what is 2+2", ComposerSuggestions.btwQuestion("/btw what is 2+2"))
        // The bare form stays the panel's own dialog (`btw.tsx:64-66`).
        assertEquals("", ComposerSuggestions.btwQuestion("/btw"))
        // …and the generic resolver carries the trigger with it.
        assertEquals(
            ComposerSuggestions.ArgumentInvocation("btw", "a b c"),
            ComposerSuggestions.argumentCommand("/btw a b c"),
        )
    }

    @Test
    fun theArgumentSplitIsAnyWhitespaceLikeUpstreamsParseSlashHead() {
        // `parseSlashHead(text, /\s/)`: a pasted `/btw` line separates on tab or
        // newline exactly like the typed space.
        assertEquals("why", ComposerSuggestions.btwQuestion("/btw\twhy"))
        assertEquals("why", ComposerSuggestions.btwQuestion("/btw\nwhy"))
        assertEquals("why", ComposerSuggestions.btwQuestion("/btw\r\nwhy"))
        assertEquals("why is the sky blue", ComposerSuggestions.btwQuestion("/btw   why is the sky blue"))
        assertEquals("", ComposerSuggestions.btwQuestion("/btw\n"))
        assertEquals("", ComposerSuggestions.btwQuestion("/btw \n "))
        // The full-width fold still applies to both halves.
        assertEquals("你好", ComposerSuggestions.btwQuestion("／ｂｔｗ　你好"))
    }

    @Test
    fun onlyUpstreamsArgumentCommandsQualify() {
        // Upstream's set is `cd`, `btw`, `rename`; only `/btw` has an Android row,
        // so the other two stay ordinary prompts (no cwd switch in the composer,
        // no title dialog in it either).
        assertEquals(setOf("btw"), ComposerSuggestions.ARGUMENT_SLASH_TRIGGERS)
        assertNull(ComposerSuggestions.argumentCommand("/cd /tmp"))
        assertNull(ComposerSuggestions.argumentCommand("/rename a new title"))
        assertNull(ComposerSuggestions.btwQuestion("/cd /tmp"))
        assertNull(ComposerSuggestions.btwQuestion("/rename a new title"))
    }

    @Test
    fun theMenuRuleIsNotTheSubmitRule() {
        // `/^\/(\S*)$/` (`machine.ts:107-114`) is the **completion menu**: the
        // space closes it (`machine.test.ts:20-30`), and that is all it does.
        assertEquals("btw", ComposerSuggestions.commandQuery("/btw"))
        assertNull(ComposerSuggestions.commandQuery("/btw what is 2+2"))
        assertNull(ComposerSuggestions.commandQuery("/btw "))
        // The submitted line is still recognised by the resolver above.
        assertEquals("what is 2+2", ComposerSuggestions.btwQuestion("/btw what is 2+2"))
    }

    // ------------------------------------------------- every other slash route

    @Test
    fun clientBuiltInsWithArgumentsStayPrompts() {
        val aliases = SessionCommands.SESSION_SLASH_COMMAND_IDS
        val known = setOf("init", "review")
        // `/model` and `/agent` carry no `arguments` field upstream (`slash:
        // "model"` / `"agent"`, `commands.tsx:49-82`), so `/model gpt` is not an
        // argument command: the bare form is completed from the menu, the args
        // form is an ordinary prompt — upstream sends it as one too.
        for (line in listOf("/model gpt", "/agent build")) {
            assertNull(line, ComposerSuggestions.argumentCommand(line))
            assertNull(line, ComposerSuggestions.btwQuestion(line))
            assertNull(line, ComposerSuggestions.aliasCommandId(line, aliases))
            assertNull(line, ComposerSuggestions.invocation(line, known))
        }
    }

    @Test
    fun serverCommandsKeepTakingArguments() {
        // `findCommand` (`submit.ts:300-306`) splits on a plain space and posts
        // `{command, arguments}` — unchanged by the argument-command resolver.
        assertEquals(
            ComposerSuggestions.Invocation("init", "focus on tests"),
            ComposerSuggestions.invocation("/init focus on tests", setOf("init", "review")),
        )
        assertEquals(
            ComposerSuggestions.Invocation("review", ""),
            ComposerSuggestions.invocation("/review", setOf("init", "review")),
        )
        // A server custom named `btw` still wins at submit time (the precedence
        // `commandItems` gives the server catalog over the `/btw` row).
        assertEquals(
            ComposerSuggestions.Invocation("btw", "what is 2+2"),
            ComposerSuggestions.invocation("/btw what is 2+2", setOf("btw")),
        )
    }

    @Test
    fun unknownAndNearMissSlashesStayPrompts() {
        val aliases = SessionCommands.SESSION_SLASH_COMMAND_IDS
        for (line in listOf("/nope a b", "/btwx a", "/btw-extra a", "/bt a", "btw a", "/", "")) {
            assertNull(line, ComposerSuggestions.argumentCommand(line))
            assertNull(line, ComposerSuggestions.btwQuestion(line))
            assertNull(line, ComposerSuggestions.invocation(line, setOf("init")))
            assertNull(line, ComposerSuggestions.aliasCommandId(line, aliases))
        }
    }

    @Test
    fun typedSessionAliasesStillRunThePaletteRow() {
        val aliases = SessionCommands.SESSION_SLASH_COMMAND_IDS
        // F-1 extension, deliberately wider than upstream: upstream only fires a
        // non-argument slash command through its menu, while a typed `/compact
        // focus` here runs `session.compact` and sends nothing. The argument is
        // not passed to the row.
        assertEquals("session.compact", ComposerSuggestions.aliasCommandId("/compact focus", aliases))
        assertEquals("session.undo", ComposerSuggestions.aliasCommandId("/undo", aliases))
        // …and it never shadows the argument command.
        assertNull(ComposerSuggestions.aliasCommandId("/btw what is 2+2", aliases))
    }

    // --------------------------------------------------------- the wiring pins

    @Test
    fun theSubmitGateIsContentOnlyAndBtwRoutesBeforeAnyPayload() {
        val body = composer()
        val submit = body.substringAfter("fun submit()").substringBefore("fun pasteFromClipboard")
        // The gate: the same content rule the button uses, nothing else.
        assertTrue(submit.contains("ComposerSubmit.canSubmit(trimmed, attachments.size, shellMode)"))
        assertFalse(
            "the slash shape never gates a submit — it only opens the menu",
            submit.contains("if (slashQuery"),
        )
        assertTrue(body.contains("val hasContent = ComposerSubmit.canSubmit(value.text, attachments.size, shell = shellMode)"))
        assertTrue(body.contains("val enabled = stopping || hasContent"))
        // The route, resolved from the submitted text and taken before the
        // payload exists, so `/btw …` can never be sent or queued.
        val route = submit.indexOf("ComposerSuggestions.btwQuestion(trimmed)")
        assertTrue("the argument command is resolved at submit time", route >= 0)
        assertTrue(
            "…and before any payload is built",
            route < submit.indexOf("ComposerSubmission.build("),
        )
        val branch = submit.substringAfter("if (btw != null) {").substringBefore("if (aliasTarget != null) {")
        assertTrue("the btw branch returns instead of sending", branch.contains("return"))
        assertTrue(submit.contains("btwHost?.ask(btw)"))
    }
}
