package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.composer.ComposerSuggestions
import com.xiufeigo.opencodeshell.ui.session.composer.ComposerSuggestions.Category
import com.xiufeigo.opencodeshell.ui.session.composer.MentionSources
import com.xiufeigo.opencodeshell.ui.session.composer.MentionText
import com.xiufeigo.opencodeshell.ui.session.composer.MentionTokens
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-1: the two `@` sources the audit found missing — **reference**
 * (`composer/model.ts:146-165`) and **MCP resource** (`model.ts:166-190`) — from
 * the wire rows through the menu to the prompt payload.
 *
 * Pure side only: the `GET /api/reference` / `GET /api/mcp/resource` reads
 * themselves are exercised by `V2ApiMcpTest` / the mock-server tests.
 */
class MentionSourcesTest {

    private fun agent(name: String, hidden: Boolean = false) =
        OpenCodeClient.Agent(name = name, description = "", mode = "subagent", native = true, hidden = hidden)

    private fun skill(name: String) = OpenCodeClient.SkillInfo(name = name, description = "")

    private fun command(name: String) =
        OpenCodeClient.CommandInfo(name = name, description = "", source = "command", template = "")

    // ------------------------------------------------------------- references

    @Test
    fun referencesMapTheWireRowAndFilterNothingThemselves() {
        val rows = listOf(
            JSONObject()
                .put("name", "docs")
                .put("path", "/repo/docs")
                .put("description", "project docs")
                .put("source", JSONObject().put("type", "local").put("path", "/repo/docs")),
            JSONObject()
                .put("name", "sdk")
                .put("path", "/repo/sdk")
                .put("hidden", true)
                .put("source", JSONObject().put("type", "git").put("repository", "github.com/acme/sdk")),
        )
        val mapped = MentionSources.references(rows)
        assertEquals(listOf("docs", "sdk"), mapped.map { it.name })
        assertEquals("/repo/docs", mapped[0].path)
        assertEquals("project docs", mapped[0].description)
        assertFalse(mapped[0].hidden)
        // `model.ts:147-148`: no description → the git repository, not the path.
        assertEquals("github.com/acme/sdk", mapped[1].description)
        assertTrue(mapped[1].hidden)
    }

    @Test
    fun aReferenceWithoutADescriptionFallsBackToItsLocalPath() {
        val row = JSONObject()
            .put("name", "docs")
            .put("path", "/repo/docs")
            .put("source", JSONObject().put("type", "local").put("path", "/repo/docs"))
        assertEquals("/repo/docs", MentionSources.referenceDescription(row))
    }

    @Test
    fun aNamelessReferenceIsDroppedAndAJsonNullNeverBecomesTheLiteralString() {
        val rows = listOf(
            JSONObject().put("name", JSONObject.NULL).put("path", "/x"),
            JSONObject().put("path", "/y"),
            JSONObject().put("name", "ok").put("path", "/z").put("description", JSONObject.NULL),
        )
        val mapped = MentionSources.references(rows)
        assertEquals(1, mapped.size)
        assertEquals("ok", mapped[0].name)
        assertNull(mapped[0].description)
    }

    // -------------------------------------------------------------- resources

    @Test
    fun resourcesMapTheCatalogAndDropTheUselessRows() {
        val rows = listOf(
            JSONObject()
                .put("server", "fs")
                .put("name", "readme")
                .put("uri", "file:///readme.md")
                .put("mimeType", "text/markdown"),
            // No uri: the uri is the prompt target, so the row cannot be used.
            JSONObject().put("server", "fs").put("name", "broken"),
            // No name: the uri stands in for the label.
            JSONObject().put("server", "git").put("uri", "git://repo/HEAD"),
        )
        val mapped = MentionSources.resources(rows)
        assertEquals(2, mapped.size)
        assertEquals("readme", mapped[0].name)
        assertEquals("file:///readme.md", mapped[0].uri)
        assertEquals("fs", mapped[0].server)
        assertEquals("text/markdown", mapped[0].mimeType)
        assertEquals("git://repo/HEAD", mapped[1].name)
    }

    @Test
    fun resourcesMapTheDecodedCatalogEnvelope() {
        val catalog = com.xiufeigo.opencodeshell.v2.McpResourceCatalog.fromJson(
            JSONObject(
                """
                {"resources":[{"server":"fs","name":"readme","uri":"file:///readme.md"}],
                 "templates":[{"server":"fs","name":"file","uriTemplate":"file:///{path}"}]}
                """.trimIndent(),
            ),
        )
        val mapped = MentionSources.resources(catalog)
        assertEquals(1, mapped.size)
        assertEquals("readme", mapped[0].name)
    }

    // ------------------------------------------------------------- menu rows

    @Test
    fun theMenuMergesBothSourcesInUpstreamOrder() {
        val items = ComposerSuggestions.mentionItems(
            query = "",
            agents = listOf(agent("build")),
            commands = listOf(command("init")),
            skills = listOf(skill("jira")),
            files = listOf("src/Main.kt"),
            references = listOf(MentionSources.Reference(name = "docs", path = "/repo/docs")),
            resources = listOf(
                MentionSources.Resource(server = "fs", name = "readme", uri = "file:///readme.md"),
            ),
        )
        assertEquals(
            listOf(
                Category.REFERENCE,
                Category.AGENT,
                Category.COMMAND,
                Category.SKILL,
                Category.RESOURCE,
                Category.FILE,
            ),
            items.map { it.category },
        )
        assertEquals(listOf("docs", "build", "init", "jira", "readme", "src/Main.kt"), items.map { it.label })
        // The two new rows carry their mention targets.
        assertEquals("/repo/docs", items[0].path)
        assertEquals("file:///readme.md", items[4].path)
        assertEquals("fs", items[4].server)
        // `model.ts:181`: no description → the server name is the column.
        assertEquals("fs", items[4].description)
    }

    @Test
    fun hiddenReferencesNeverReachTheMenuAndTheQueryFiltersBothSources() {
        val references = listOf(
            MentionSources.Reference(name = "docs", path = "/repo/docs"),
            MentionSources.Reference(name = "secret", path = "/repo/secret", hidden = true),
        )
        val resources = listOf(
            MentionSources.Resource(server = "fs", name = "readme", uri = "file:///readme.md"),
        )
        val all = ComposerSuggestions.mentionItems(
            query = "",
            agents = emptyList(),
            commands = emptyList(),
            skills = emptyList(),
            files = emptyList(),
            references = references,
            resources = resources,
        )
        assertEquals(listOf("docs", "readme"), all.map { it.label })

        val filtered = ComposerSuggestions.mentionItems(
            query = "READ",
            agents = emptyList(),
            commands = emptyList(),
            skills = emptyList(),
            files = emptyList(),
            references = references,
            resources = resources,
        )
        assertEquals(listOf("readme"), filtered.map { it.label })
    }

    // ------------------------------------------------------- mention semantics

    @Test
    fun aReferenceMentionBecomesADirectoryFileEntry() {
        val item = ComposerSuggestions.Item(
            category = Category.REFERENCE,
            label = "docs",
            path = "docs",
        )
        val token = MentionTokens.tokenFor(item)
        assertEquals(Category.REFERENCE, token?.kind)
        assertEquals("docs", token?.target)

        val structured = MentionTokens.structure(listOf(token!!), sessionDirectory = "/repo")
        assertEquals(1, structured.files.size)
        // `model.ts:156-160`: `file://<absolute path>` with the directory mime.
        assertEquals("file:///repo/docs", structured.files[0].uri)
        assertEquals(MentionSources.DIRECTORY_MIME, structured.files[0].mime)
        assertEquals("docs", structured.files[0].name)
        assertTrue(structured.agents.isEmpty())
        assertTrue(structured.skills.isEmpty())
    }

    @Test
    fun aResourceMentionKeepsItsUriVerbatim() {
        val item = ComposerSuggestions.Item(
            category = Category.RESOURCE,
            label = "readme",
            path = "file:///readme.md",
            server = "fs",
        )
        val token = MentionTokens.tokenFor(item)
        assertEquals(Category.RESOURCE, token?.kind)
        assertEquals("fs", token?.server)

        val structured = MentionTokens.structure(listOf(token!!), sessionDirectory = "/repo")
        // `model.ts:172,180`: the resource uri is the target — never re-encoded
        // through `fileUri`.
        assertEquals("file:///readme.md", structured.files[0].uri)
        assertEquals("readme", structured.files[0].name)
    }

    @Test
    fun tokenKindsOverrideTheTextOnlyColourHeuristic() {
        val tokens = listOf(
            MentionTokens.Token(kind = Category.REFERENCE, label = "docs"),
            MentionTokens.Token(kind = Category.RESOURCE, label = "readme"),
            MentionTokens.Token(kind = Category.AGENT, label = "build"),
        )
        val colors = MentionTokens.colors(tokens)
        // `editor.tsx:191` `[data-mention=reference]`: a slash-less reference
        // name colours as a reference, not as an agent.
        assertEquals(MentionText.Kind.REFERENCE, colors["docs"])
        // `editor.tsx:366`: a resource is a file mention (`text/plain` mime).
        assertEquals(MentionText.Kind.FILE, colors["readme"])
        // The other categories keep the text heuristic (no override).
        assertNull(colors["build"])
    }

    @Test
    fun theEditorColoursThePickedReferenceThroughTheTokenMap() {
        val text = "@docs and @build"
        val colors = MentionTokens.colors(
            listOf(MentionTokens.Token(kind = Category.REFERENCE, label = "docs")),
        )
        val annotated = com.xiufeigo.opencodeshell.ui.session.composer.mentionAnnotated(
            text,
            com.xiufeigo.opencodeshell.ui.theme.OcColors.Dark,
            colors,
        )
        val kinds = annotated.spanStyles.associate { text.substring(it.start, it.end) to it.item.color }
        assertEquals(
            com.xiufeigo.opencodeshell.ui.theme.OcColors.Dark.syntaxKeyword,
            kinds["@docs"],
        )
        // `@build` keeps the heuristic's agent colour (`syntax-type`).
        assertEquals(
            com.xiufeigo.opencodeshell.ui.theme.OcColors.Dark.syntaxType,
            kinds["@build"],
        )
    }
}
