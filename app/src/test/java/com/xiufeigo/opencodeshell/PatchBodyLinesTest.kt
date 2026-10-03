package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.timeline.ToolPresentation
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Route C: the `apply_patch` diff body.
 *
 * This server exposes no `edit`/`write` tool, so `apply_patch` is the only
 * writer — and it ships a unified patch instead of an old/new pair. Without
 * [ToolPresentation.patchLines] the card fell back to a JSON body and the
 * `+`/`-` diff rendering (and its background tint) never appeared at all.
 */
class PatchBodyLinesTest {

    private fun part(input: String) = ChatStore.UiPart(
        id = "prt_1",
        type = "tool",
        toolName = "apply_patch",
        toolStatus = "completed",
        toolInput = input,
    )

    @Test
    fun patchTextBecomesAddAndRemoveLines() {
        val body = ToolPresentation.bodyLines(
            part(
                """{"patchText":"*** Begin Patch\n*** Update File: a.txt\n@@ -1,2 +1,2 @@\n-hello\n+goodbye\n context\n*** End Patch"}""",
            ),
        )
        val kinds = body.map { it.kind }
        assertTrue("hunk/header lines are structure", kinds.contains(ToolPresentation.LineKind.COMMAND))
        assertEquals(
            listOf("-hello", "+goodbye"),
            body.filter {
                it.kind == ToolPresentation.LineKind.ADD || it.kind == ToolPresentation.LineKind.DELETE
            }.map { it.text },
        )
        // Context lines stay plain; the trailing `*** End Patch` is structure.
        val context = body.first { it.text == " context" }
        assertEquals(ToolPresentation.LineKind.PLAIN, context.kind)
        assertEquals(ToolPresentation.LineKind.COMMAND, body.last().kind)
    }

    @Test
    fun filesArrayPatchShapeIsFlattened() {
        val body = ToolPresentation.bodyLines(
            part("""{"files":[{"patch":"@@ -1 +1 @@\n-a\n+b"},{"patch":"@@ -2 +2 @@\n-c\n+d"}]}"""),
        )
        val changed = body.filter {
            it.kind == ToolPresentation.LineKind.ADD || it.kind == ToolPresentation.LineKind.DELETE
        }.map { it.text }
        assertEquals(listOf("-a", "+b", "-c", "+d"), changed)
    }

    @Test
    fun oldNewPairStillWinsWhenPresentAndEmptyPatchFallsBackToJson() {
        val pair = ToolPresentation.bodyLines(
            ChatStore.UiPart(
                id = "prt_2",
                type = "tool",
                toolName = "edit",
                toolStatus = "completed",
                toolInput = """{"oldString":"one","newString":"two"}""",
            ),
        )
        // Wave 2.2b: the edit card renders a real hunk diff (upstream
        // `tool-renderer.tsx:1877-1963` `mode="diff"`), so the pair is grouped
        // under its `@@` hunk header.
        assertEquals(listOf("@@ -1,1 +1,1 @@", "- one", "+ two"), pair.map { it.text })
        assertEquals(ToolPresentation.LineKind.HUNK, pair.first().kind)

        val json = ToolPresentation.bodyLines(part("""{"unrelated":true}"""))
        assertTrue(json.any { it.kind == ToolPresentation.LineKind.JSON })
        assertTrue(json.none { it.kind == ToolPresentation.LineKind.ADD })
    }

    @Test
    fun blankFileEntriesAreSkippedWithoutStraySeparators() {
        val lines = ToolPresentation.patchLines("")
        assertTrue(lines.isEmpty())
        val one = ToolPresentation.patchLines("+only")
        assertEquals(listOf(ToolPresentation.LineKind.ADD), one.map { it.kind })

        val combined = ToolPresentation.bodyLines(
            part("""{"files":[{"patch":""},{"patch":"+kept"}]}"""),
        )
        assertEquals("+kept", combined.first().text)
    }

    @Test
    fun jsonObjectInputWithoutPatchStaysOnTheJsonPath() {
        val body = ToolPresentation.bodyLines(part(JSONObject("""{"note":"x"}""").toString()))
        assertTrue(body.any { it.kind == ToolPresentation.LineKind.JSON })
    }

    @Test
    fun dashDashContentStaysDiffInsideACountedHunk() {
        // `--x` deleted renders `---x`, `++y` added renders `+++y`: the old
        // prefix order matched `---`/`+++` first and dropped the tint.
        val body = ToolPresentation.patchLines("@@ -1,2 +1,2 @@\n---x: 1\n+++y: 2")
        assertEquals(
            listOf(
                ToolPresentation.LineKind.HUNK,
                ToolPresentation.LineKind.DELETE,
                ToolPresentation.LineKind.ADD,
            ),
            body.map { it.kind },
        )
        assertEquals("---x: 1", body[1].text)
        assertEquals("+++y: 2", body[2].text)
    }

    @Test
    fun markdownHorizontalRuleContentKeepsItsDiffKind() {
        val body = ToolPresentation.patchLines("@@ -1 +1 @@\n----\n++++")
        assertEquals(
            listOf(
                ToolPresentation.LineKind.HUNK,
                ToolPresentation.LineKind.DELETE,
                ToolPresentation.LineKind.ADD,
            ),
            body.map { it.kind },
        )
    }

    @Test
    fun fileHeadersAreStructureOnlyOutsideHunks() {
        val body = ToolPresentation.patchLines("--- a/x\n+++ b/x\n@@ -1 +1 @@\n-a\n+b\n--- a/y\n+++ b/y")
        assertEquals(
            listOf(
                ToolPresentation.LineKind.COMMAND,
                ToolPresentation.LineKind.COMMAND,
                ToolPresentation.LineKind.HUNK,
                ToolPresentation.LineKind.DELETE,
                ToolPresentation.LineKind.ADD,
                ToolPresentation.LineKind.COMMAND,
                ToolPresentation.LineKind.COMMAND,
            ),
            body.map { it.kind },
        )
    }

    @Test
    fun bareAtSectionHeaderOpensAnUncountedHunk() {
        val body = ToolPresentation.patchLines("@@\n-old\n+new\n context\n*** End Patch")
        assertEquals(
            listOf(
                ToolPresentation.LineKind.HUNK,
                ToolPresentation.LineKind.DELETE,
                ToolPresentation.LineKind.ADD,
                ToolPresentation.LineKind.PLAIN,
                ToolPresentation.LineKind.COMMAND,
            ),
            body.map { it.kind },
        )
    }
}
