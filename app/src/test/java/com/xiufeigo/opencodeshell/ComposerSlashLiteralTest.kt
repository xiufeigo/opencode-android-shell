package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.composer.ComposerSuggestions
import com.xiufeigo.opencodeshell.ui.session.composer.MentionText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v48 tE v48_39/40/41 — typing `/` or `/foobarbaz` must open the command menu
 * (or its empty state) and keep the literal text; the editor must never be
 * rewritten with an outside path.
 *
 * Verdict of that investigation: the `C:/Program` on screen was the harness's
 * own Git Bash mangling the typed argument (`/foobarbaz` → `C:/Program
 * Files/Git/foobarbaz`, and `adb shell input text` only consumes the first
 * space-delimited token), not the app — no composer path fabricates host
 * paths (the slash chain is pure menu-building, `@` file hits are
 * display-only and project-root-scoped). These tests pin the app side of that
 * contract so a real regression here fails loudly: `/export`, `/mcp` and
 * friends stay usable, and an unknown `/word` stays an ordinary prompt.
 */
class ComposerSlashLiteralTest {

    private fun command(name: String, description: String = "", source: String = "command") =
        OpenCodeClient.CommandInfo(
            name = name,
            description = description,
            source = source,
            template = "",
        )

    private val catalog = listOf(
        command("export", "export the session"),
        command("mcp", "manage MCP servers"),
        command("init", "guided AGENTS.md setup"),
    )

    @Test
    fun slashAndUnknownSlashWordOpenTheMenuAndKeepTheLiteral() {
        // `/` lists everything; `/foobarbaz` filters (to the empty state) —
        // both are non-null queries, so the menu opens and the editor text is
        // never touched by the chain (these functions return data only).
        assertEquals("", ComposerSuggestions.commandQuery("/"))
        assertEquals("foobarbaz", ComposerSuggestions.commandQuery("/foobarbaz"))
    }

    @Test
    fun slashMenuRowsAreAlwaysSlashLabelsNeverHostPaths() {
        // Whatever the query — including hostile ones — the only text `accept()`
        // can ever write is a `/label ` built by `slashLabel`, never a bare
        // outside path.
        val queries = listOf("", "e", "ex", "exp", "export", "m", "mc", "mcp", "foo", "foobarbaz", "C:/Program")
        for (query in queries) {
            val items = ComposerSuggestions.commandItems(query, catalog)
            for (item in items) {
                assertTrue("query '$query' produced '${item.label}'", item.label.startsWith("/"))
            }
            assertTrue(
                "query '$query' leaked a host path row",
                items.none { it.label.contains(":/") || it.label == "C:/Program" },
            )
        }
    }

    @Test
    fun exportAndMcpStayUsable() {
        val all = ComposerSuggestions.commandItems("", catalog).map { it.label }
        assertTrue(all.contains("/export"))
        assertTrue(all.contains("/mcp"))
        assertEquals(listOf("/export"), ComposerSuggestions.commandItems("ex", catalog).map { it.label })
        assertEquals(listOf("/mcp"), ComposerSuggestions.commandItems("mcp", catalog).map { it.label })
        val known = catalog.map { it.name }.toSet()
        assertEquals(
            ComposerSuggestions.Invocation("export", ""),
            ComposerSuggestions.invocation("/export", known),
        )
        assertEquals(
            ComposerSuggestions.Invocation("mcp", "list"),
            ComposerSuggestions.invocation("/mcp list", known),
        )
    }

    @Test
    fun unknownSlashWordStaysAnOrdinaryPrompt() {
        val known = catalog.map { it.name }.toSet()
        assertNull(ComposerSuggestions.invocation("/foobarbaz", known))
        assertNull(ComposerSuggestions.invocation("/", known))
    }

    @Test
    fun slashNeverArmsTheFileCompletionChain() {
        // `/find/file` only fires behind an `@` anchor, so a `/`-led or hostile
        // input can neither start a file search nor be replaced by one of its
        // answers — there is no trigger for the rewrite to come from.
        assertNull(MentionText.anchorAt("/", 1))
        assertNull(MentionText.anchorAt("/foobarbaz", 10))
        assertNull(MentionText.anchorAt("C:/Program", 10))
    }
}
