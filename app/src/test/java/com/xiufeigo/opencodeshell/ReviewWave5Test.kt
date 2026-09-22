package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.review.DiffLineKind
import com.xiufeigo.opencodeshell.ui.session.review.PatchRow
import com.xiufeigo.opencodeshell.ui.session.review.ReviewChangeBadges
import com.xiufeigo.opencodeshell.ui.session.review.ReviewChangeScope
import com.xiufeigo.opencodeshell.ui.session.review.ReviewDiffStyle
import com.xiufeigo.opencodeshell.ui.session.review.ReviewDiffView
import com.xiufeigo.opencodeshell.ui.session.review.ReviewExpandMode
import com.xiufeigo.opencodeshell.ui.session.review.ReviewFileFilter
import com.xiufeigo.opencodeshell.ui.session.review.ReviewFileTree
import com.xiufeigo.opencodeshell.ui.session.review.ReviewPanelWidth
import com.xiufeigo.opencodeshell.ui.session.review.ReviewScopes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W5-R review panel logic — the JVM-testable half of C1–C5:
 * the sidebar width policy (upstream `session-review-v2.tsx:17-19,135-146`),
 * the file filter (`review-diff-kinds.ts:57-61` + `matchesModelSearch`), the
 * diff view model (line numbers, word-level ranges, split pairing, collapse
 * mode, large-patch policy — `pierre/index.ts:205-231`), the Git/Branch/
 * Last-turn scope rules (`review/view.tsx:228-247`, `review/model.ts:55-68`)
 * and the Files-tree A/M/D badges (`file-tree-v2.tsx:114-122`).
 */
class ReviewWave5Test {

    // ------------------------------------------------- C1: sidebar width

    @Test
    fun panelWidthDefaultsMatchUpstreamConstants() {
        assertEquals(240f, ReviewPanelWidth.DEFAULT.toFloat())
        assertEquals(200f, ReviewPanelWidth.MIN.toFloat())
        assertEquals(480f, ReviewPanelWidth.MAX.toFloat())
    }

    @Test
    fun panelWidthClampsToUpstreamRange() {
        assertEquals(200f, ReviewPanelWidth.clamp(120f))
        assertEquals(480f, ReviewPanelWidth.clamp(900f))
        assertEquals(300f, ReviewPanelWidth.clamp(300f))
    }

    @Test
    fun panelWidthDragGrowsTowardsTheTimeline() {
        // The panel is docked at the inline end: dragging left (negative delta)
        // grows it, dragging right shrinks it.
        assertEquals(260f, ReviewPanelWidth.resize(240f, -20f))
        assertEquals(220f, ReviewPanelWidth.resize(240f, 20f))
        assertEquals(ReviewPanelWidth.MIN.toFloat(), ReviewPanelWidth.resize(200f, 40f))
        assertEquals(ReviewPanelWidth.MAX.toFloat(), ReviewPanelWidth.resize(480f, -40f))
    }

    @Test
    fun panelWidthRestoresPersistedValueAndFallsBackToDefault() {
        assertEquals(312f, ReviewPanelWidth.restore(312f))
        assertEquals(240f, ReviewPanelWidth.restore(null))
        assertEquals(200f, ReviewPanelWidth.restore(60f))
        assertEquals(480f, ReviewPanelWidth.restore(1_000f))
        assertEquals(240f, ReviewPanelWidth.restore(Float.NaN))
        // Double-click target (session-review-v2.tsx:143).
        assertEquals(240f, ReviewPanelWidth.reset())
    }

    // -------------------------------------------------- C2: filter files

    @Test
    fun filterMatchesCaseInsensitiveSubstring() {
        assertTrue(ReviewFileFilter.matches("app", "src/App.kt"))
        assertTrue(ReviewFileFilter.matches("APP", "src/app.kt"))
        assertFalse(ReviewFileFilter.matches("zzz", "src/App.kt"))
    }

    @Test
    fun blankFilterIsInactiveAndKeepsEveryFile() {
        assertFalse(ReviewFileFilter.isActive("   "))
        assertTrue(ReviewFileFilter.isActive("a"))
        val files = listOf("src/App.kt", "src/Other.kt")
        assertEquals(files, ReviewFileFilter.filter(files, "  "))
        assertEquals(listOf("src/App.kt"), ReviewFileFilter.filter(files, "App"))
    }

    @Test
    fun filterFollowsTheModelSearchNormalisation() {
        // `matchesModelSearch` (providers/models/search.ts:1-18) collapses the
        // separators, so `filter-files` finds `FilterFiles.kt`.
        assertEquals("filter files kt", ReviewFileFilter.normalize("Filter-Files.kt"))
        assertEquals("filterfileskt", ReviewFileFilter.compact("Filter-Files.kt"))
        assertTrue(ReviewFileFilter.matches("filter-files", "src/FilterFiles.kt"))
        assertTrue(ReviewFileFilter.matches("filter files", "src/FilterFiles.kt"))
        assertTrue(ReviewFileFilter.matches("src/ui", "src/ui/App.kt"))
    }

    @Test
    fun filterRowsKeepsAncestorsOfAMatch() {
        val rows = listOf(
            Row("src", dir = true, level = 0),
            Row("ui", dir = true, level = 1),
            Row("App.kt", dir = false, level = 2),
            Row("Other.kt", dir = false, level = 2),
            Row("build", dir = true, level = 0),
            Row("out.txt", dir = false, level = 1),
        )
        val filtered = ReviewFileFilter.filterRows(rows, "app.kt")
        assertEquals(listOf("src", "ui", "App.kt"), filtered.map { it.node.name })
        // A directory-only match keeps just that row (its children do not match).
        assertEquals(listOf("src"), ReviewFileFilter.filterRows(rows, "src").map { it.node.name })
        assertEquals(rows, ReviewFileFilter.filterRows(rows, ""))
        assertTrue(ReviewFileFilter.filterRows(rows, "nope").isEmpty())
    }

    @Test
    fun filterRowsAlsoMatchesDirectoryNames() {
        val rows = listOf(
            Row("src", dir = true, level = 0),
            Row("App.kt", dir = false, level = 1),
            Row("docs", dir = true, level = 0),
        )
        assertEquals(listOf("docs"), ReviewFileFilter.filterRows(rows, "doc").map { it.node.name })
    }

    // ------------------------------ C3: line numbers + word-level emphasis

    private val patch = listOf(
        "diff --git a/src/App.kt b/src/App.kt",
        "index 1111111..2222222 100644",
        "--- a/src/App.kt",
        "+++ b/src/App.kt",
        "@@ -1,4 +1,5 @@",
        " package a",
        "-val count = 1",
        "+val count = 2",
        "+val extra = 3",
        " fun main() {}",
    ).joinToString("\n")

    @Test
    fun rowsNumberBothSidesFromTheHunkHeader() {
        // 4 raw file-header lines (`diff --git`/`index`/`---`/`+++`) + hunk +
        // 5 content lines.
        val rows = ReviewDiffView.rows(patch)
        assertEquals(10, rows.size)
        val hunk = rows.first { it.kind == DiffLineKind.HUNK }
        assertNull(hunk.oldLine)
        assertNull(hunk.newLine)
        // ` package a` exists on both sides.
        val opening = rows.first { it.kind == DiffLineKind.CONTEXT }
        assertEquals(1, opening.oldLine)
        assertEquals(1, opening.newLine)
        // A deletion only has an old number, an addition only a new one.
        val deletion = rows.first { it.kind == DiffLineKind.DELETION }
        assertEquals(2, deletion.oldLine)
        assertNull(deletion.newLine)
        val additions = rows.filter { it.kind == DiffLineKind.ADDITION }
        assertEquals(2, additions[0].newLine)
        assertNull(additions[0].oldLine)
        assertEquals(3, additions[1].newLine)
        // The trailing context advances both cursors independently.
        val trailing = rows.last { it.kind == DiffLineKind.CONTEXT }
        assertEquals(3, trailing.oldLine)
        assertEquals(4, trailing.newLine)
    }

    @Test
    fun rowsWithoutAHunkCarryNoNumbers() {
        val rows = ReviewDiffView.rows("-orphan\n+line\n")
        assertEquals(2, rows.size)
        assertNull(rows[0].oldLine)
        assertNull(rows[1].newLine)
    }

    @Test
    fun contentStripsTheSignColumnOnly() {
        val rows = ReviewDiffView.rows(patch)
        assertEquals("package a", rows.first { it.kind == DiffLineKind.CONTEXT }.content)
        assertEquals("val count = 1", rows.first { it.kind == DiffLineKind.DELETION }.content)
        assertEquals("val count = 2", rows.filter { it.kind == DiffLineKind.ADDITION }[0].content)
        // Headers keep their text verbatim.
        val header = rows.first { it.kind == DiffLineKind.FILE_HEADER && it.text.startsWith("---") }
        assertEquals("--- a/src/App.kt", header.text)
        assertEquals("--- a/src/App.kt", header.content)
    }

    @Test
    fun wordRangesMarkTheDifferingMiddle() {
        val rows = ReviewDiffView.rows(patch)
        val deletion = rows.first { it.kind == DiffLineKind.DELETION }
        val additions = rows.filter { it.kind == DiffLineKind.ADDITION }
        assertEquals(listOf(12..12), deletion.changed)
        assertEquals(listOf(12..12), additions[0].changed)
        // The unpaired second addition has nothing to compare against.
        assertTrue(additions[1].changed.isEmpty())
    }

    @Test
    fun changedRangesHandleInsertionsAndEqualLines() {
        assertEquals(null to null, ReviewDiffView.changedRanges("same", "same"))
        assertEquals(2..2 to 2..2, ReviewDiffView.changedRanges("abc", "abd"))
        // Pure append: nothing changes on the old side.
        assertEquals(null to 3..3, ReviewDiffView.changedRanges("abc", "abcd"))
        assertEquals(0..0 to null, ReviewDiffView.changedRanges("xabc", "abc"))
    }

    @Test
    fun longLinesSkipWordHighlighting() {
        val longBefore = "a".repeat(ReviewDiffView.MAX_HIGHLIGHT_CHARS + 1)
        val longAfter = "b".repeat(ReviewDiffView.MAX_HIGHLIGHT_CHARS + 1)
        val rows = ReviewDiffView.rows(
            listOf("@@ -1,1 +1,1 @@", "-$longBefore", "+$longAfter").joinToString("\n"),
        )
        assertEquals(3, rows.size)
        assertTrue(rows[1].changed.isEmpty())
        assertTrue(rows[2].changed.isEmpty())
    }

    @Test
    fun collapseModeHidesUnchangedContextLines() {
        val expanded = ReviewDiffView.rows(patch, ReviewExpandMode.EXPAND)
        val collapsed = ReviewDiffView.rows(patch, ReviewExpandMode.COLLAPSE)
        assertEquals(10, expanded.size)
        assertEquals(2, expanded.count { it.kind == DiffLineKind.CONTEXT })
        // Only the two context lines go away.
        assertEquals(8, collapsed.size)
        assertTrue(collapsed.none { it.kind == DiffLineKind.CONTEXT })
        // Hunk header, the raw file header and every changed line survive.
        assertEquals(4, collapsed.count { it.kind == DiffLineKind.FILE_HEADER })
        assertTrue(collapsed.any { it.kind == DiffLineKind.HUNK })
        assertEquals(3, collapsed.count { it.kind == DiffLineKind.ADDITION || it.kind == DiffLineKind.DELETION })
    }

    @Test
    fun splitViewPairsDeletionRunsWithAdditionRuns() {
        val columns = ReviewDiffView.split(ReviewDiffView.rows(patch)).map { it as PatchRow.Columns }
        // 4 raw file headers + 1 hunk (full rows), 2 context rows, 2 paired rows.
        assertEquals(9, columns.size)
        val fulls = columns.filter { it.full != null }
        assertEquals(5, fulls.size)
        assertEquals(DiffLineKind.HUNK, fulls.last().full?.kind)
        assertTrue(fulls.dropLast(1).all { it.full?.kind == DiffLineKind.FILE_HEADER })
        assertTrue(fulls.all { it.left == null && it.right == null })
        // Context sits on both sides.
        val context = columns.first { it.left?.kind == DiffLineKind.CONTEXT }
        assertEquals(context.left, context.right)
        // One deletion + two additions → two rows, the second with an empty left.
        val pairs = columns.filter { it.full == null && it.left?.kind != DiffLineKind.CONTEXT }
        assertEquals(2, pairs.size)
        assertEquals(DiffLineKind.DELETION, pairs[0].left?.kind)
        assertEquals(DiffLineKind.ADDITION, pairs[0].right?.kind)
        assertNull(pairs[1].left)
        assertEquals(DiffLineKind.ADDITION, pairs[1].right?.kind)
    }

    @Test
    fun splitPairsPerColumnNumbersForTheTwoSides() {
        val columns = ReviewDiffView.split(ReviewDiffView.rows(patch)).map { it as PatchRow.Columns }
        val pair = columns.first { it.left?.kind == DiffLineKind.DELETION }
        assertEquals(2, pair.left?.oldLine)
        assertNull(pair.left?.newLine)
        assertEquals(2, pair.right?.newLine)
        assertNull(pair.right?.oldLine)
    }

    @Test
    fun viewCapsLargePatchesAndReportsTruncation() {
        val view = ReviewDiffView.view(patch, ReviewExpandMode.EXPAND, ReviewDiffStyle.UNIFIED, maxRows = 2)
        assertEquals(10, view.lineCount)
        assertEquals(2, view.rows.size)
        assertTrue(view.truncated)
        val whole = ReviewDiffView.view(patch, ReviewExpandMode.EXPAND, ReviewDiffStyle.UNIFIED)
        assertFalse(whole.truncated)
        assertEquals(10, whole.rows.size)
    }

    @Test
    fun viewShapesRowsAccordingToTheDiffStyle() {
        val unified = ReviewDiffView.view(patch, ReviewExpandMode.EXPAND, ReviewDiffStyle.UNIFIED)
        assertTrue(unified.rows.all { it is PatchRow.Line })
        val split = ReviewDiffView.view(patch, ReviewExpandMode.EXPAND, ReviewDiffStyle.SPLIT)
        assertTrue(split.rows.all { it is PatchRow.Columns })
    }

    @Test
    fun hugePatchesOpenCollapsed() {
        assertEquals(
            ReviewExpandMode.COLLAPSE,
            ReviewDiffView.autoCollapse(ReviewDiffView.AUTO_COLLAPSE_ROWS, ReviewExpandMode.EXPAND),
        )
        assertEquals(
            ReviewExpandMode.EXPAND,
            ReviewDiffView.autoCollapse(ReviewDiffView.AUTO_COLLAPSE_ROWS - 1, ReviewExpandMode.EXPAND),
        )
        // An explicit collapse is never overridden.
        assertEquals(
            ReviewExpandMode.COLLAPSE,
            ReviewDiffView.autoCollapse(10, ReviewExpandMode.COLLAPSE),
        )
    }

    @Test
    fun lineCountCountsRawPatchLines() {
        assertEquals(10, ReviewDiffView.lineCount(patch))
        assertEquals(0, ReviewDiffView.lineCount(null))
    }

    // ---------------------------------------------------- C4: change scope

    @Test
    fun scopesOfferGitAndBranchOnlyWhenUpstreamWould() {
        // No repository → only the session (last turn) scope.
        assertEquals(
            listOf(ReviewChangeScope.LAST_TURN),
            ReviewScopes.available(hasVcs = false, currentBranch = null, defaultBranch = null),
        )
        // Repository on its default branch → git only (review/model.ts:59-66).
        assertEquals(
            listOf(ReviewChangeScope.GIT, ReviewChangeScope.LAST_TURN),
            ReviewScopes.available(hasVcs = true, currentBranch = "main", defaultBranch = "main"),
        )
        assertEquals(
            listOf(ReviewChangeScope.GIT, ReviewChangeScope.LAST_TURN),
            ReviewScopes.available(hasVcs = true, currentBranch = "main", defaultBranch = null),
        )
        // Feature branch → git + branch.
        assertEquals(
            listOf(ReviewChangeScope.GIT, ReviewChangeScope.BRANCH, ReviewChangeScope.LAST_TURN),
            ReviewScopes.available(hasVcs = true, currentBranch = "feat", defaultBranch = "main"),
        )
    }

    @Test
    fun vcsScopesStayDisabledUntilTheirDataSourceIsWired() {
        val available = ReviewScopes.available(hasVcs = true, currentBranch = "feat", defaultBranch = "main")
        assertFalse(ReviewScopes.enabled(ReviewChangeScope.GIT, available, vcsWired = false))
        assertFalse(ReviewScopes.enabled(ReviewChangeScope.BRANCH, available, vcsWired = false))
        assertTrue(ReviewScopes.enabled(ReviewChangeScope.LAST_TURN, available, vcsWired = false))
        assertTrue(ReviewScopes.enabled(ReviewChangeScope.GIT, available, vcsWired = true))
        // Not offered → never enabled, even with a loader.
        val noVcs = ReviewScopes.available(hasVcs = false, currentBranch = null, defaultBranch = null)
        assertFalse(ReviewScopes.enabled(ReviewChangeScope.GIT, noVcs, vcsWired = true))
    }

    @Test
    fun scopeSelectsItsOwnDiffSource() {
        val session = listOf(diff("src/App.kt", "modified"))
        val vcs = listOf(diff("src/Other.kt", "added"))
        assertEquals(session, ReviewScopes.diffsFor(ReviewChangeScope.LAST_TURN, session, vcs))
        assertEquals(vcs, ReviewScopes.diffsFor(ReviewChangeScope.GIT, session, vcs))
        assertEquals(vcs, ReviewScopes.diffsFor(ReviewChangeScope.BRANCH, session, vcs))
    }

    @Test
    fun scopeLabelsAndEmptyCopyAreDistinct() {
        val labels = ReviewChangeScope.values().map { ReviewScopes.labelRes(it) }
        assertEquals(3, labels.toSet().size)
        val empty = ReviewChangeScope.values().map { ReviewScopes.emptyRes(it) }
        assertEquals(3, empty.toSet().size)
        // The session scope keeps the pre-existing "no changes yet" copy.
        assertEquals(
            R.string.oc_review_changes_empty,
            ReviewScopes.emptyRes(ReviewChangeScope.LAST_TURN),
        )
        assertNotEquals(
            ReviewScopes.labelRes(ReviewChangeScope.BRANCH),
            ReviewScopes.labelRes(ReviewChangeScope.LAST_TURN),
        )
    }

    // ------------------------------------------------- C5: Files tree badges

    @Test
    fun badgesMapFileStatusToTheTreeLetter() {
        val badges = ReviewChangeBadges.badges(
            listOf(
                diff("src/App.kt", "added"),
                diff("src/Gone.kt", "deleted"),
                diff("src/Edit.kt", "modified"),
                diff("src/Unknown.kt", null),
                pathlessDiff(),
            ),
        )
        assertEquals("A", badges["src/App.kt"])
        assertEquals("D", badges["src/Gone.kt"])
        assertEquals("M", badges["src/Edit.kt"])
        assertEquals("M", badges["src/Unknown.kt"])
        assertEquals(4, badges.size)
    }

    @Test
    fun badgesResolveAnAbsoluteTreeRowThroughTheRoot() {
        val badges = ReviewChangeBadges.badges(listOf(diff("src/App.kt", "added")))
        assertEquals(
            "A",
            ReviewChangeBadges.forPath("C:\\work\\proj\\src\\App.kt", badges, root = "C:\\work\\proj"),
        )
        // Suffix fallback when the root is unknown.
        assertEquals("A", ReviewChangeBadges.forPath("/work/proj/src/App.kt", badges))
        // Boundary-safe: a different file with the same suffix must not match.
        assertNull(ReviewChangeBadges.forPath("/work/other/BarApp.kt", badges))
        assertNull(ReviewChangeBadges.forPath("/work/proj/src/Other.kt", badges))
        assertTrue(ReviewChangeBadges.matches("/w/src/App.kt", "src/App.kt"))
        assertFalse(ReviewChangeBadges.matches("/w/src/FooApp.kt", "App.kt"))
    }

    @Test
    fun badgesAreEmptyWithoutDiffs() {
        assertTrue(ReviewChangeBadges.badges(emptyList()).isEmpty())
        assertNull(ReviewChangeBadges.forPath("/w/src/App.kt", emptyMap()))
    }

    // ------------------------------------------------------------- fixtures

    private fun Row(name: String, dir: Boolean, level: Int): ReviewFileTree.Row = ReviewFileTree.Row(
        node = OpenCodeClient.FileNode(
            name = name,
            path = name,
            absolute = "/w/$name",
            directory = dir,
            ignored = false,
        ),
        level = level,
    )

    private fun diff(file: String, status: String?) = OpenCodeClient.FileDiff(
        file = file,
        patch = null,
        additions = 1,
        deletions = 0,
        status = status,
    )

    /** A diff entry without a path (`filterRenderableDiff` drops those upstream). */
    private fun pathlessDiff() = OpenCodeClient.FileDiff(
        file = null,
        patch = null,
        additions = 0,
        deletions = 0,
        status = "modified",
    )
}
