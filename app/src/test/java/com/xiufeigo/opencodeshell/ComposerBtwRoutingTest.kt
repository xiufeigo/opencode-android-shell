package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.composer.ComposerSuggestions
import com.xiufeigo.opencodeshell.ui.session.composer.ComposerSuggestions.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The composer's `/btw` route (device v12: a typed `/btw xxx` was queued as an
 * ordinary follow-up prompt and showed up in the queue panel, while the product
 * path is the side-question panel — `ui/session/btw`, reachable until now only
 * through the header's `(?)` button).
 *
 * Upstream registers the command with a slash field
 * (`packages/tui/src/feature-plugins/prompt/btw.tsx:52,57` — `id: "session.aside"`,
 * `slash: { name: "btw", arguments: true }`) and its `run(input)` answers from the
 * session's own context through `session.generate`: the question and the answer
 * never enter the conversation. The composer therefore resolves a `/btw` line to
 * the panel — [ComposerSuggestions.btwQuestion] is the pure half, the source pins
 * below hold the wiring half (the ask must not fall through to a prompt send).
 */
class ComposerBtwRoutingTest {

    private fun command(name: String, description: String = "") =
        OpenCodeClient.CommandInfo(name = name, description = description, source = "command", template = "")

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        // Line endings are not part of any of the pins below, and a multi-line
        // guard literal has to survive an editor rewriting the file as CRLF.
        return file.readText().replace("\r\n", "\n")
    }

    private fun composer() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt")

    // ------------------------------------------------------- the slash table

    @Test
    fun theSlashTableCarriesBtw() {
        // The trigger the composer routes and the id upstream registers it under.
        assertEquals("btw", ComposerSuggestions.BTW_TRIGGER)
        assertEquals("session.aside", ComposerSuggestions.BTW_COMMAND_ID)
        // …and the `/` menu lists it, like upstream's `slashCommands` does for
        // every registered command carrying a `slash` field.
        val row = ComposerSuggestions.commandItems("", emptyList()).single { it.label == "/btw" }
        assertEquals(Category.COMMAND, row.category)
        assertTrue("`/btw` runs locally, it is never sent", row.builtIn)
        assertNull("the description comes from `oc_btw_description`", row.description)
    }

    @Test
    fun btwRowsAreListedAfterTheServerCatalogAndFilterByPrefix() {
        val items = ComposerSuggestions.commandItems("", listOf(command("init", "setup")))
        assertEquals(listOf("/model", "/agent", "/init", "/btw"), items.map { it.label })
        assertEquals(listOf("/btw"), ComposerSuggestions.commandItems("btw", emptyList()).map { it.label })
        assertEquals(listOf("/btw"), ComposerSuggestions.commandItems("BT", emptyList()).map { it.label })
        assertTrue(ComposerSuggestions.commandItems("zzz", emptyList()).isEmpty())
    }

    @Test
    fun aServerCustomNamedBtwKeepsItsRow() {
        // The menu's `distinctBy` keeps the first row, and the server catalog is
        // listed before `/btw` — the same precedence `submit()` gives `invocation`
        // over the btw route.
        val items = ComposerSuggestions.commandItems("", listOf(command("btw", "project-specific side note")))
        val btw = items.single { it.label == "/btw" }
        assertFalse("the surviving row is the server command", btw.builtIn)
        assertEquals("project-specific side note", btw.description)
    }

    // -------------------------------------------------- the pure resolution

    @Test
    fun btwLinesResolveToTheSideQuestion() {
        // The bare form: no question, the panel opens with its own field
        // (upstream's `input?.trim() || dialog.prompt(…)`, `btw.tsx:64-66`).
        assertEquals("", ComposerSuggestions.btwQuestion("/btw"))
        assertEquals("", ComposerSuggestions.btwQuestion("/btw "))
        assertEquals("", ComposerSuggestions.btwQuestion("  /btw  "))
        // With a question: everything after the trigger, trimmed.
        assertEquals("why is the sky blue", ComposerSuggestions.btwQuestion("/btw why is the sky blue"))
        assertEquals("spaced", ComposerSuggestions.btwQuestion("/btw   spaced   "))
        // W5-F: the fold applies here too — a Chinese IME's full-width slash and
        // its ideographic space route exactly like the ASCII ones.
        assertEquals("你好", ComposerSuggestions.btwQuestion("／ｂｔｗ　你好"))
    }

    @Test
    fun anythingElseStaysAPrompt() {
        assertNull(ComposerSuggestions.btwQuestion("/btwx"))
        assertNull(ComposerSuggestions.btwQuestion("/btw-extra"))
        assertNull(ComposerSuggestions.btwQuestion("/bt"))
        assertNull(ComposerSuggestions.btwQuestion("btw why"))
        assertNull(ComposerSuggestions.btwQuestion("ask /btw why"))
        assertNull(ComposerSuggestions.btwQuestion("/"))
        assertNull(ComposerSuggestions.btwQuestion(""))
        assertNull(ComposerSuggestions.btwQuestion("hello"))
    }

    // ------------------------------------------------------- the wiring pins

    @Test
    fun theComposerRoutesBtwToThePanelInsteadOfSendingAPrompt() {
        val body = composer()
        // The host the shell publishes around the session screen.
        assertTrue(body.contains("val btwHost = LocalSessionBtwHost.current"))
        // Submit-time routing, resolved from the pure table… (v75: the parsers
        // read the trimmed shape; the payload is the editor's verbatim text.)
        assertTrue(body.contains("ComposerSuggestions.btwQuestion(trimmed)"))
        // …and executed on the panel, never through the payload paths: the bare
        // form opens the panel, a question asks straight away.
        assertTrue(body.contains("btwHost?.openPanel()"))
        assertTrue(body.contains("btwHost?.ask(btw)"))
        // The route is decided before any payload is built, and its branch
        // returns — so `/btw …` can never reach `submitStructured` /
        // `submitPayload` / `onSend` (the v12 queue-panel bug).
        val route = body.indexOf("ComposerSuggestions.btwQuestion(trimmed)")
        assertTrue("the btw route runs before the payload is built", route in 1 until body.indexOf("ComposerSubmission.build("))
        val branch = body.substringAfter("if (btw != null) {").substringBefore("if (aliasTarget != null) {")
        assertTrue("the btw branch returns instead of sending", branch.contains("return"))
        // Shell mode and a queue-edit confirm never route (the same guard the
        // alias path uses).
        assertTrue(body.contains("if (shellMode || invocation != null || editing != null) {\n            null\n        } else {\n            ComposerSuggestions.btwQuestion(trimmed)"))
    }

    @Test
    fun thePanelRowOpensWithoutAskingAndTheHostExposesThatEntry() {
        val body = composer()
        // Picking `/btw` from the menu runs it locally with no question.
        assertTrue(body.contains("ComposerSuggestions.BTW_TRIGGER -> btwHost?.openPanel()"))
        val host = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/btw/SessionBtwHost.kt")
        assertTrue("the host opens the panel without an ask", host.contains("fun openPanel()"))
        // The ask still goes through the transient generate route, so the answer
        // never pollutes the main timeline.
        assertTrue(host.contains("viewModel.generateFromContext(SessionBtwModel.prompt(question))"))
    }
}
