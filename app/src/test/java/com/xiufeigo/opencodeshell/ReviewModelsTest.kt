package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.review.DiffLineKind
import com.xiufeigo.opencodeshell.ui.session.review.ReviewDiff
import com.xiufeigo.opencodeshell.ui.session.review.ReviewFileTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic coverage for the review panel: unified-diff line classification
 * and the lazy file tree's sort/flatten (parent-child assembly, directories
 * first + name ascending).
 */
class ReviewModelsTest {

    private fun node(name: String, dir: Boolean, ignored: Boolean = false) = OpenCodeClient.FileNode(
        name = name,
        path = name,
        absolute = "/w/$name",
        directory = dir,
        ignored = ignored,
    )

    // ------------------------------------------------------------- file tree

    @Test
    fun sortSiblingsPutsDirectoriesFirstThenNamesAscending() {
        val sorted = ReviewFileTree.sortSiblings(
            listOf(
                node("zeta.kt", dir = false),
                node("build", dir = true),
                node("Alpha.kt", dir = false),
                node("src", dir = true),
            ),
        )
        // Case-insensitive: Alpha.kt before zeta.kt; both dirs before files.
        assertEquals(listOf("build", "src", "Alpha.kt", "zeta.kt"), sorted.map { it.name })
    }

    @Test
    fun flattenAssemblesParentChildWithLevels() {
        val root = "/w"
        val children = mapOf(
            root to listOf(node("b.kt", dir = false), node("a", dir = true)),
            "/w/a" to listOf(node("inner.kt", dir = false)),
        )
        val rows = ReviewFileTree.flatten(
            children = { key -> children[key].orEmpty() },
            root = root,
            expanded = { true },
        )
        // a/ sorts before b.kt; inner.kt nests under expanded a/ at level 1.
        assertEquals(listOf("a", "inner.kt", "b.kt"), rows.map { it.node.name })
        assertEquals(listOf(0, 1, 0), rows.map { it.level })
    }

    @Test
    fun flattenHidesDescendantsOfCollapsedDirectories() {
        val root = "/w"
        val children = mapOf(
            root to listOf(node("a", dir = true)),
            "/w/a" to listOf(node("inner.kt", dir = false)),
        )
        val rows = ReviewFileTree.flatten(
            children = { key -> children[key].orEmpty() },
            root = root,
            expanded = { false },
        )
        assertEquals(listOf("a"), rows.map { it.node.name })
    }

    @Test
    fun flattenExpandsOnlyTheDirectoriesThatAreOpen() {
        val root = "/w"
        val children = mapOf(
            root to listOf(node("open", dir = true), node("shut", dir = true)),
            "/w/open" to listOf(node("o.kt", dir = false)),
            "/w/shut" to listOf(node("s.kt", dir = false)),
        )
        val rows = ReviewFileTree.flatten(
            children = { key -> children[key].orEmpty() },
            root = root,
            expanded = { it == "/w/open" },
        )
        assertEquals(listOf("open", "o.kt", "shut"), rows.map { it.node.name })
    }

    // ------------------------------------------------------------------ diff

    @Test
    fun parsePatchLinesClassifiesUnifiedDiff() {
        val patch = """
            diff --git a/src/A.kt b/src/A.kt
            index 111..222 100644
            --- a/src/A.kt
            +++ b/src/A.kt
            @@ -1,3 +1,4 @@
             context
            -old line
            +new line
            \ No newline at end of file
        """.trimIndent()
        val kinds = ReviewDiff.parsePatchLines(patch).map { it.kind }
        assertEquals(
            listOf(
                DiffLineKind.FILE_HEADER, // diff --git
                DiffLineKind.FILE_HEADER, // index
                DiffLineKind.FILE_HEADER, // ---
                DiffLineKind.FILE_HEADER, // +++
                DiffLineKind.HUNK,
                DiffLineKind.CONTEXT,
                DiffLineKind.DELETION,
                DiffLineKind.ADDITION,
                DiffLineKind.META,
            ),
            kinds,
        )
    }

    @Test
    fun parsePatchLinesKeepsTheSignPrefixForAlignment() {
        val lines = ReviewDiff.parsePatchLines("@@ -1 +1 @@\n-a\n+b\n c")
        assertEquals("-a", lines[1].text)
        assertEquals("+b", lines[2].text)
        assertEquals(" c", lines[3].text)
    }

    @Test
    fun parsePatchLinesIsLenientWithUnknownContent() {
        val lines = ReviewDiff.parsePatchLines("@@ -1 +1 @@\nsome-garbage")
        assertEquals(DiffLineKind.CONTEXT, lines[1].kind)
        assertEquals("some-garbage", lines[1].text)
    }

    @Test
    fun parsePatchLinesKeepsDashedContentInsideACountedHunk() {
        // `--x: 1` deleted renders `---x: 1`, `++y: 2` added renders
        // `+++y: 2`: a stateless prefix order claimed both as file headers.
        val patch = listOf(
            "diff --git a/styles.css b/styles.css",
            "index 111..222 100644",
            "--- a/styles.css",
            "+++ b/styles.css",
            "@@ -1,3 +1,3 @@",
            " .a {",
            "---x: 1",
            "+++y: 2",
            " }",
        ).joinToString("\n")
        val kinds = ReviewDiff.parsePatchLines(patch).map { it.kind }
        assertEquals(
            listOf(
                DiffLineKind.FILE_HEADER, // diff --git
                DiffLineKind.FILE_HEADER, // index
                DiffLineKind.FILE_HEADER, // --- a/styles.css
                DiffLineKind.FILE_HEADER, // +++ b/styles.css
                DiffLineKind.HUNK,
                DiffLineKind.CONTEXT,
                DiffLineKind.DELETION, // ---x: 1 (content, not a header)
                DiffLineKind.ADDITION, // +++y: 2 (content, not a header)
                DiffLineKind.CONTEXT,
            ),
            kinds,
        )
    }

    @Test
    fun parsePatchLinesReturnsToFileHeadersAfterTheCountedHunk() {
        // Counts exhausted → `--- a/y` is structure again; an over-counted
        // hunk still yields to the next file's `--- `/`+++ `/`@@` triple
        // (the jsdiff parseHunk prune).
        val exact = ReviewDiff.parsePatchLines("--- a/x\n+++ b/x\n@@ -1 +1 @@\n-a\n+b\n--- a/y\n+++ b/y")
        assertEquals(
            listOf(
                DiffLineKind.FILE_HEADER,
                DiffLineKind.FILE_HEADER,
                DiffLineKind.HUNK,
                DiffLineKind.DELETION,
                DiffLineKind.ADDITION,
                DiffLineKind.FILE_HEADER,
                DiffLineKind.FILE_HEADER,
            ),
            exact.map { it.kind },
        )
        val overCounted = ReviewDiff.parsePatchLines(
            "--- a/x\n+++ b/x\n@@ -1,9 +1,9 @@\n-a\n+b\n--- a/y\n+++ b/y\n@@ -1 +1 @@\n-c\n+d",
        )
        assertEquals(
            listOf(
                DiffLineKind.FILE_HEADER,
                DiffLineKind.FILE_HEADER,
                DiffLineKind.HUNK,
                DiffLineKind.DELETION,
                DiffLineKind.ADDITION,
                DiffLineKind.FILE_HEADER, // --- a/y: pruned, not a deletion
                DiffLineKind.FILE_HEADER, // +++ b/y
                DiffLineKind.HUNK,
                DiffLineKind.DELETION,
                DiffLineKind.ADDITION,
            ),
            overCounted.map { it.kind },
        )
    }

    @Test
    fun hasHunksMatchesTheUpstreamLoadTest() {
        assertTrue(ReviewDiff.hasHunks("--- a/f\n+++ b/f\n@@ -1 +1 @@\n-x\n+y"))
        assertFalse(ReviewDiff.hasHunks(""))
        assertFalse(ReviewDiff.hasHunks(null))
        assertFalse(ReviewDiff.hasHunks("no hunk markers here"))
    }

    @Test
    fun totalsSumsAdditionsAndDeletions() {
        val diffs = listOf(
            OpenCodeClient.FileDiff("a", null, 2, 1, "modified"),
            OpenCodeClient.FileDiff("b", null, 5, 3, "added"),
        )
        assertEquals(7 to 4, ReviewDiff.totals(diffs))
    }

    @Test
    fun pathHelpersAndStatusLabels() {
        assertEquals("App.kt", ReviewDiff.basename("src/ui/App.kt"))
        assertEquals("App.kt", ReviewDiff.basename("App.kt"))
        assertEquals("src/ui", ReviewDiff.dirname("src/ui/App.kt"))
        assertEquals("", ReviewDiff.dirname("App.kt"))
        assertEquals("A", ReviewDiff.statusLabel("added"))
        assertEquals("D", ReviewDiff.statusLabel("deleted"))
        assertEquals("M", ReviewDiff.statusLabel("modified"))
        assertEquals("M", ReviewDiff.statusLabel(null))
    }
}
