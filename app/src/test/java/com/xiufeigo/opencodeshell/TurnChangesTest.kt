package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.timeline.TurnChanges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A6 — changed-file extraction for one assistant turn.
 *
 * Upstream feeds its `{n} Changed files` block from `session.diff`
 * (`FileDiffInfo`, `session-ui/src/components/apply-patch-file.ts:17-26`), which
 * `ChatStore` does not carry; the recoverable sources are the edit-family tool
 * inputs (`timeline/detail.ts:96` = edit / write / patch / apply_patch) and the
 * `patch` part (`schema/src/v1/session.ts:94-100`).
 */
class TurnChangesTest {

    private fun tool(id: String, name: String, input: String?) =
        ChatStore.UiPart(id = id, type = "tool", toolName = name, toolInput = input)

    private fun patchPart(id: String, rawJson: String) =
        ChatStore.UiPart(id = id, type = "patch", rawJson = rawJson)

    private fun message(parts: List<ChatStore.UiPart>) =
        ChatStore.UiMessage(id = "m1", role = "assistant", timeCreated = 0L, parts = parts)

    // ------------------------------------------------------------ tool paths

    @Test
    fun editReturnsItsFilePath() {
        val part = tool("p1", "edit", """{"filePath":"src/a.ts","oldString":"a","newString":"b"}""")
        assertEquals(listOf("src/a.ts"), TurnChanges.pathsOf(part))
    }

    @Test
    fun writeReturnsItsFilePath() {
        val part = tool("p1", "write", """{"filePath":"src/b.ts","content":"body"}""")
        assertEquals(listOf("src/b.ts"), TurnChanges.of(message(listOf(part))))
    }

    @Test
    fun applyPatchReturnsEveryPatchedPath() {
        val part = tool(
            "p1",
            "apply_patch",
            """{"files":[{"path":"src/c.ts","patch":"@@ -1 +1 @@"},{"path":"src/d.ts","patch":"@@ -1 +1 @@"}]}""",
        )
        assertEquals(listOf("src/c.ts", "src/d.ts"), TurnChanges.pathsOf(part))
    }

    @Test
    fun applyPatchFallsBackToItsUnifiedPatchHeaders() {
        val part = tool(
            "p1",
            "apply_patch",
            """{"patchText":"*** Begin Patch\n*** Update File: src/e.ts\n@@\n-a\n+b\n*** End Patch"}""",
        )
        assertEquals(listOf("src/e.ts"), TurnChanges.pathsOf(part))
    }

    @Test
    fun unifiedPatchHeadersAndDevNullAreHandled() {
        val part = tool(
            "p1",
            "patch",
            """{"patchText":"--- a/src/f.ts\n+++ b/src/f.ts\n@@\n-x\n+y\n--- /dev/null\n+++ b/src/g.ts"}""",
        )
        assertEquals(listOf("src/f.ts", "src/g.ts"), TurnChanges.pathsOf(part))
    }

    @Test
    fun nonEditToolsContributeNothing() {
        val part = tool("p1", "bash", """{"command":"rm src/a.ts"}""")
        assertTrue(TurnChanges.pathsOf(part).isEmpty())
        val read = tool("p2", "read", """{"filePath":"src/a.ts"}""")
        assertTrue(TurnChanges.pathsOf(read).isEmpty())
    }

    @Test
    fun unparseableInputContributesNothing() {
        assertTrue(TurnChanges.pathsOf(tool("p1", "edit", "not json")).isEmpty())
        assertTrue(TurnChanges.pathsOf(tool("p2", "edit", null)).isEmpty())
    }

    // ----------------------------------------------------------- patch part

    @Test
    fun patchPartContributionsComeFromItsFileList() {
        val part = patchPart("p1", """{"hash":"abc","files":["src/g.ts","src/h.ts"]}""")
        assertEquals(listOf("src/g.ts", "src/h.ts"), TurnChanges.pathsOf(part))
    }

    @Test
    fun malformedPatchPartContributesNothing() {
        assertTrue(TurnChanges.pathsOf(patchPart("p1", "{}")).isEmpty())
        assertTrue(TurnChanges.pathsOf(patchPart("p2", "nope")).isEmpty())
    }

    // --------------------------------------------------------------- turn

    @Test
    fun aTurnKeepsFirstAppearanceOrderAndDeduplicates() {
        val files = TurnChanges.of(
            message(
                listOf(
                    tool("p1", "edit", """{"filePath":"src/a.ts"}"""),
                    tool("p2", "write", """{"filePath":"src/b.ts"}"""),
                    tool("p3", "edit", """{"filePath":"src/a.ts"}"""),
                    patchPart("p4", """{"files":["src/b.ts","src/c.ts"]}"""),
                ),
            ),
        )
        assertEquals(listOf("src/a.ts", "src/b.ts", "src/c.ts"), files)
    }

    @Test
    fun aTurnWithoutEditsHasNoChangedFiles() {
        val files = TurnChanges.of(
            message(
                listOf(
                    ChatStore.UiPart(id = "p1", type = "text", text = "done"),
                    tool("p2", "bash", """{"command":"ls"}"""),
                ),
            ),
        )
        assertTrue(files.isEmpty())
    }

    @Test
    fun aUserNameInsideThePathIsNotSplit() {
        val part = tool("p1", "edit", """{"path":"src/nested/deep/name.ts"}""")
        assertEquals(listOf("src/nested/deep/name.ts"), TurnChanges.pathsOf(part))
    }

    @Test
    fun genericPathKeysAreAccepted() {
        assertEquals(listOf("x.ts"), TurnChanges.pathsOf(tool("p1", "edit", """{"path":"x.ts"}""")))
        assertEquals(listOf("y.ts"), TurnChanges.pathsOf(tool("p2", "write", """{"file":"y.ts"}""")))
    }

    // -------------------------------------------------------- list expansion

    @Test
    fun collapsedListStopsAtTenAndReportsTheRest() {
        // `tool-renderer.tsx:443-444`.
        assertEquals(10, TurnChanges.visibleCount(25, expanded = false))
        assertEquals(25, TurnChanges.visibleCount(25, expanded = true))
        assertEquals(15, TurnChanges.hiddenCount(25, expanded = false))
        assertEquals(0, TurnChanges.hiddenCount(25, expanded = true))
    }

    @Test
    fun shortListsAreNeverTruncated() {
        assertEquals(3, TurnChanges.visibleCount(3, expanded = false))
        assertEquals(0, TurnChanges.hiddenCount(3, expanded = false))
        assertEquals(0, TurnChanges.visibleCount(0, expanded = false))
    }
}
