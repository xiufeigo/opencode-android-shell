package com.xiufeigo.opencodeshell.ui.session.review

import com.xiufeigo.opencodeshell.OpenCodeClient
import com.xiufeigo.opencodeshell.R

/**
 * Pure (JVM-testable) logic behind the review side panel: unified-diff patch
 * line classification and the lazily loaded file-tree's sort/flatten. Mirrors
 * the upstream `_upstream/packages/app/src/session/files/file-tree-v2-model.ts`
 * (`flattenLiveFileTreeV2`, sibling sort "directories first, name ascending")
 * and the patch handling of `session-ui/src/components/session-diff.ts`.
 *
 * W5-R adds the panel width policy ([ReviewPanelWidth]), the file filter
 * ([ReviewFileFilter]), the change scope switch ([ReviewScopes]) and the
 * file-tree change badges ([ReviewChangeBadges]).
 */

// ------------------------------------------------------------------ diff

/** One rendered patch line. [text] keeps the sign/prefix so alignment matches the raw patch. */
enum class DiffLineKind {
    /** `diff --git …`, `index …`, `--- a/f`, `+++ b/f`, mode/rename lines. */
    FILE_HEADER,

    /** `@@ -a,b +c,d @@` hunk header. */
    HUNK,

    /** `+…` */
    ADDITION,

    /** `-…` */
    DELETION,

    /** ` …` context. */
    CONTEXT,

    /** `\ No newline at end of file` and friends. */
    META,
}

data class DiffLine(val kind: DiffLineKind, val text: String)

object ReviewDiff {

    /**
     * Splits a unified-diff patch into classified lines. Lenient like the
     * upstream `parsePatch` path: unrecognised non-empty lines degrade to
     * [DiffLineKind.CONTEXT] instead of failing the whole file.
     */
    fun parsePatchLines(patch: String): List<DiffLine> {
        val out = ArrayList<DiffLine>()
        for (raw in patch.split('\n')) {
            val line = raw.removeSuffix("\r")
            when {
                line.isEmpty() -> Unit // blank separator between hunks/files
                line.startsWith("@@") -> out += DiffLine(DiffLineKind.HUNK, line)
                line.startsWith("diff --git") || line.startsWith("index ") ||
                    line.startsWith("---") || line.startsWith("+++") ||
                    line.startsWith("new file mode") || line.startsWith("deleted file mode") ||
                    line.startsWith("old mode") || line.startsWith("new mode") ||
                    line.startsWith("similarity index") || line.startsWith("dissimilarity index") ||
                    line.startsWith("rename from") || line.startsWith("rename to") ||
                    line.startsWith("copy from") || line.startsWith("copy to") ||
                    line.startsWith("Binary files") || line.startsWith("GIT binary patch") ->
                    out += DiffLine(DiffLineKind.FILE_HEADER, line)
                // The `---`/`+++` headers are already claimed above, so a
                // leading +/- here is a real content line.
                line.startsWith("+") -> out += DiffLine(DiffLineKind.ADDITION, line)
                line.startsWith("-") -> out += DiffLine(DiffLineKind.DELETION, line)
                line.startsWith("\\") -> out += DiffLine(DiffLineKind.META, line)
                line.startsWith(" ") -> out += DiffLine(DiffLineKind.CONTEXT, line)
                else -> out += DiffLine(DiffLineKind.CONTEXT, line)
            }
        }
        return out
    }

    /**
     * A patch is renderable when it carries at least one hunk header —
     * upstream `reviewDiffNeedsLoad` uses the same `^@@ ` test
     * (`session/review/review-diff-kinds.ts:15-18`).
     */
    fun hasHunks(patch: String?): Boolean =
        patch?.lineSequence()?.any { it.startsWith("@@") } == true

    /** Total `+additions`/`-deletions` over the list (the header's DiffChanges). */
    fun totals(diffs: List<OpenCodeClient.FileDiff>): Pair<Int, Int> =
        diffs.sumOf { it.additions } to diffs.sumOf { it.deletions }

    /** `src/ui/App.kt` → `App.kt`. */
    fun basename(path: String): String =
        path.replace('\\', '/').trimEnd('/').substringAfterLast('/')

    /** `src/ui/App.kt` → `src/ui`; `App.kt` → `""`. */
    fun dirname(path: String): String {
        val normalized = path.replace('\\', '/').trimEnd('/')
        val index = normalized.lastIndexOf('/')
        return if (index < 0) "" else normalized.substring(0, index)
    }

    /**
     * Status letter for a diff row, upstream `kindLabel`
     * (`session/files/file-tree-v2.tsx:47-51`): added → "A", deleted → "D",
     * anything else → "M".
     */
    fun statusLabel(status: String?): String = when (status) {
        "added" -> "A"
        "deleted" -> "D"
        else -> "M"
    }
}

// ------------------------------------------------------------------ file tree

object ReviewFileTree {

    /** One visible tree row: the node plus its indentation level. */
    data class Row(val node: OpenCodeClient.FileNode, val level: Int)

    /**
     * Sibling order, upstream `file-tree-v2-model.ts:53-56`: directories
     * first, then name ascending (case-insensitive so `B` does not sort
     * before `a` like a raw UTF-16 compare would).
     */
    fun sortSiblings(nodes: List<OpenCodeClient.FileNode>): List<OpenCodeClient.FileNode> =
        nodes.sortedWith(compareBy({ if (it.directory) 0 else 1 }, { it.name.lowercase() }))

    /**
     * Depth-first flatten of the lazily loaded tree (upstream
     * `flattenLiveFileTreeV2`): [children] answers the already-fetched child
     * listing for a directory key ([OpenCodeClient.FileNode.absolute]);
     * [expanded] gates descent. Rows of collapsed directories are emitted
     * without their descendants.
     */
    fun flatten(
        children: (String) -> List<OpenCodeClient.FileNode>,
        root: String,
        expanded: (String) -> Boolean,
    ): List<Row> {
        val rows = ArrayList<Row>()
        val stack = ArrayDeque<Row>()
        sortSiblings(children(root)).asReversed().forEach { stack.addLast(Row(it, 0)) }
        while (stack.isNotEmpty()) {
            val row = stack.removeLast()
            rows += row
            if (!row.node.directory || !expanded(row.node.absolute)) continue
            sortSiblings(children(row.node.absolute)).asReversed()
                .forEach { stack.addLast(Row(it, row.level + 1)) }
        }
        return rows
    }
}

// --------------------------------------------------------------- panel width

/**
 * Width policy of the review side panel (W5-R C1). Upstream
 * `session-ui/src/v2/components/session-review-v2.tsx:17-19`:
 * `SESSION_REVIEW_V2_SIDEBAR_WIDTH_DEFAULT = 240`,
 * `_MIN = 200`, `_MAX = 480`; the drag handle reports through
 * `onResize` and the double click writes the default back (`:135-146`); the
 * app-side state clamps every resize (`app/src/session/review/panel-state.ts:40-44`)
 * and defaults to the same 240 (`:25`).
 *
 * All values are dp: the panel is a Compose layout, not a CSS sidebar.
 */
object ReviewPanelWidth {

    /** `SESSION_REVIEW_V2_SIDEBAR_WIDTH_DEFAULT`. */
    const val DEFAULT = 240

    /** `SESSION_REVIEW_V2_SIDEBAR_WIDTH_MIN`. */
    const val MIN = 200

    /** `SESSION_REVIEW_V2_SIDEBAR_WIDTH_MAX`. */
    const val MAX = 480

    /** Clamp one width into the allowed range (a persisted/config width may be anything). */
    fun clamp(value: Float): Float = value.coerceIn(MIN.toFloat(), MAX.toFloat())

    /**
     * One drag step: the panel is docked at the inline **end** of the timeline
     * row, so dragging towards the start (negative [deltaX]) grows it.
     */
    fun resize(current: Float, deltaX: Float): Float = clamp(current - deltaX)

    /** Persisted/initial width; an absent or out-of-range value falls back to [DEFAULT]. */
    fun restore(saved: Float?): Float = if (saved == null || saved.isNaN()) DEFAULT.toFloat() else clamp(saved)

    /** Double-click target (`session-review-v2.tsx:143`). */
    fun reset(): Float = DEFAULT.toFloat()
}

// ---------------------------------------------------------------- file filter

/**
 * `filter files` of the review sidebar (W5-R C2), upstream
 * `session-review-v2.tsx:88-123` (`TextInput type="search"` +
 * `ui.sessionReviewV2.filterFiles`) and the matcher behind it,
 * `app/src/session/review/review-diff-kinds.ts:57-61`:
 * `value = query.trim().toLowerCase(); files.filter(file => file.toLowerCase().includes(value))`.
 *
 * [matches] is a superset of that rule: the plain lowercased substring test is
 * kept, plus the `matchesModelSearch` normalisation
 * (`packages/app/src/providers/models/search.ts:1-18` — non letter/number runs
 * collapse to spaces, spaces droppable) so `filter-files` also finds
 * `FilterFiles.kt`. A blank query matches everything (inactive filter).
 */
object ReviewFileFilter {

    private val SEPARATOR_RUN = Regex("[^\\p{L}\\p{N}]+")
    private val SPACE_RUN = Regex("\\s+")

    /** `normalizeModelSearch`: lowercase, punctuation → space, trimmed, collapsed. */
    fun normalize(value: String): String =
        value.lowercase().replace(SEPARATOR_RUN, " ").trim().replace(SPACE_RUN, " ")

    /** `compactModelSearch`: [normalize] with the spaces removed. */
    fun compact(value: String): String = normalize(value).replace(" ", "")

    /** An all-whitespace query behaves like no query (`review-diff-kinds.ts:59`). */
    fun isActive(query: String): Boolean = query.isNotBlank()

    fun matches(query: String, values: List<String>): Boolean {
        if (!isActive(query)) return true
        val search = normalize(query)
        val compactSearch = compact(query)
        return values.any { value ->
            value.lowercase().contains(query.trim().lowercase()) ||
                normalize(value).contains(search) ||
                (compactSearch.isNotEmpty() && compact(value).contains(compactSearch))
        }
    }

    fun matches(query: String, value: String): Boolean = matches(query, listOf(value))

    fun filter(files: List<String>, query: String): List<String> =
        if (isActive(query)) files.filter { matches(query, it) } else files

    /**
     * Filters flattened tree rows, keeping the ancestors of a match so the tree
     * shape survives (upstream switches to a flat result list while searching —
     * the lazily loaded Android tree cannot list unloaded files, so the loaded
     * rows are filtered instead; see `research/wave5_review.md` §C2).
     *
     * Rows are in DFS order, so an ancestor is simply a preceding row that is
     * still open at a shallower level.
     */
    fun filterRows(rows: List<ReviewFileTree.Row>, query: String): List<ReviewFileTree.Row> {
        if (!isActive(query)) return rows
        val kept = BooleanArray(rows.size)
        val ancestors = ArrayList<Int>()
        for (index in rows.indices) {
            val row = rows[index]
            while (ancestors.isNotEmpty() && rows[ancestors.last()].level >= row.level) {
                ancestors.removeAt(ancestors.size - 1)
            }
            if (matches(query, listOf(row.node.name, row.node.path, row.node.absolute))) {
                kept[index] = true
                ancestors.forEach { kept[it] = true }
            } else if (row.node.directory) {
                ancestors.add(index)
            }
        }
        return rows.filterIndexed { index, _ -> kept[index] }
    }
}

// --------------------------------------------------------------- change scope

/** Upstream `ChangeMode` (`app/src/session/review/model.ts:23`). */
enum class ReviewChangeScope { GIT, BRANCH, LAST_TURN }

/**
 * The Git / Branch / Last-turn switch (W5-R C4), upstream
 * `app/src/session/review/view.tsx:228-247` (the `Select` whose labels are
 * `ui.sessionReview.title.git|branch|lastTurn`) and the availability rules of
 * `model.ts:55-68`:
 *
 * ```
 * if (project?.vcs) list.push("git")
 * if (project?.vcs && vcs?.branch.current && vcs?.branch.default &&
 *     vcs?.branch.current !== vcs?.branch.default) list.push("branch")
 * ```
 *
 * Upstream's `ChangeMode` also has `"turn"` but `options()` never lists it
 * (`model.ts:145-148` returns `[]` for it); Android exposes the session diff
 * (`GET /session/:id/diff`, `session.diff`) as that third scope instead.
 */
object ReviewScopes {

    /**
     * [hasVcs] is the project VCS flag; [currentBranch]/[defaultBranch] come
     * from `GET /vcs` (`OpenCodeClient.VcsInfo`). The last-turn scope is always
     * offered.
     */
    fun available(hasVcs: Boolean, currentBranch: String?, defaultBranch: String?): List<ReviewChangeScope> {
        val out = ArrayList<ReviewChangeScope>(3)
        if (hasVcs) out += ReviewChangeScope.GIT
        if (hasVcs &&
            !currentBranch.isNullOrEmpty() &&
            !defaultBranch.isNullOrEmpty() &&
            currentBranch != defaultBranch
        ) {
            out += ReviewChangeScope.BRANCH
        }
        out += ReviewChangeScope.LAST_TURN
        return out
    }

    /**
     * A listed scope is only selectable while its data source is wired: the
     * last-turn scope rides the `diffs` the panel already receives, while
     * Git/Branch need a VCS diff request (`onRequestVcsDiff`).
     */
    fun enabled(scope: ReviewChangeScope, available: List<ReviewChangeScope>, vcsWired: Boolean): Boolean =
        scope in available && (scope == ReviewChangeScope.LAST_TURN || vcsWired)

    /** Diffs the asked scope renders: session diff for the turn, VCS diff otherwise. */
    fun diffsFor(
        scope: ReviewChangeScope,
        sessionDiffs: List<OpenCodeClient.FileDiff>,
        vcsDiffs: List<OpenCodeClient.FileDiff>,
    ): List<OpenCodeClient.FileDiff> =
        if (scope == ReviewChangeScope.LAST_TURN) sessionDiffs else vcsDiffs

    /** `ui.sessionReview.title.git|branch|lastTurn` (`view.tsx:231-233`). */
    fun labelRes(scope: ReviewChangeScope): Int = when (scope) {
        ReviewChangeScope.GIT -> R.string.oc_review_scope_git
        ReviewChangeScope.BRANCH -> R.string.oc_review_scope_branch
        ReviewChangeScope.LAST_TURN -> R.string.oc_review_scope_turn
    }

    /**
     * Empty copy per scope (`view.tsx:253-257`):
     * `session.review.noUncommittedChanges` / `noBranchChanges` / `noChanges`.
     */
    fun emptyRes(scope: ReviewChangeScope): Int = when (scope) {
        ReviewChangeScope.GIT -> R.string.oc_review_changes_empty_git
        ReviewChangeScope.BRANCH -> R.string.oc_review_changes_empty_branch
        ReviewChangeScope.LAST_TURN -> R.string.oc_review_changes_empty
    }
}

// -------------------------------------------------------------- file badges

/**
 * A/M/D change badges of the Files tree (W5-R C5), upstream
 * `app/src/session/files/file-tree-v2.tsx:114-122` — only `type === "file"`
 * rows render a badge, the letter comes from [ReviewDiff.statusLabel]
 * (`kindLabel`, `file-tree-v2.tsx:47-51`), and the letters come from
 * `reviewDiffKinds` (`review-diff-kinds.ts:33-55`).
 *
 * Tree rows carry an absolute path (`OpenCodeClient.FileNode.absolute`) while
 * `FileDiff.file` is workspace-relative, so the lookup first removes the known
 * [root] and then falls back to a separator-boundary suffix match.
 */
object ReviewChangeBadges {

    /** `file → "A" | "M" | "D"` for the rows of [diffs] that carry a path. */
    fun badges(diffs: List<OpenCodeClient.FileDiff>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        diffs.forEach { diff ->
            val file = diff.file?.takeIf { it.isNotEmpty() } ?: return@forEach
            out[normalize(file)] = ReviewDiff.statusLabel(diff.status)
        }
        return out
    }

    /** Badge letter for one tree row, or null when the file is unchanged/not a diff entry. */
    fun forPath(path: String, badges: Map<String, String>, root: String? = null): String? {
        if (badges.isEmpty() || path.isEmpty()) return null
        val normalized = normalize(path)
        val relative = root?.takeIf { it.isNotEmpty() }?.let { base ->
            normalized.removePrefix(normalize(base) + "/").takeIf { it != normalized }
        }
        if (relative != null) badges[relative]?.let { return it }
        return badges.entries.firstOrNull { matches(normalized, it.key) }?.value
    }

    /** `src/App.kt` matches `…/src/App.kt` but never `…/OtherApp.kt`. */
    fun matches(path: String, diffPath: String): Boolean =
        path == diffPath || path.endsWith("/$diffPath")

    private fun normalize(path: String): String = path.replace('\\', '/').trim('/')
}
