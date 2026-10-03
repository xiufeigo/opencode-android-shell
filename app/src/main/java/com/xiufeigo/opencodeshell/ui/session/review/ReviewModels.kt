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

    /** `@@ -1,3 +1,4 @@ optional section heading` — the counted-hunk header. */
    private val HUNK_HEADER = Regex("""@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@""")

    /** Operation signs every hunk body line carries. */
    private val HUNK_SIGNS = charArrayOf('+', '-', ' ', '\\')

    /**
     * Splits a unified-diff patch into classified lines. Lenient like the
     * upstream `parsePatch` path: unrecognised non-empty lines degrade to
     * [DiffLineKind.CONTEXT] instead of failing the whole file.
     *
     * Classification is a counted-hunk state machine (jsdiff `parsePatch`,
     * the same shape as `ToolPresentation.patchLines`): while a `@@` header's
     * old/new counts still owe lines, every line is content by its sign — so
     * a deleted `--x: 1` (`---x: 1`) or added `++y` (`+++y`) stays
     * DELETION/ADDITION instead of matching the `---`/`+++` file-header
     * prefixes. A line without an operation sign (blank separator,
     * `diff --git`, the next `@@`, garbage) ends the counted hunk and is
     * classified as structure.
     */
    fun parsePatchLines(patch: String): List<DiffLine> {
        val lines = patch.split('\n')
        val out = ArrayList<DiffLine>(lines.size)
        var inHunk = false
        var counted = false
        var oldLeft = 0
        var newLeft = 0
        for (index in lines.indices) {
            val line = lines[index].removeSuffix("\r")
            // jsdiff parseHunk prunes the next file's `--- `/`+++ `/`@@`
            // triple: it starts with `-` but is structure, not a deletion run.
            val nextFileHeader = inHunk && line.startsWith("--- ") &&
                lines.getOrNull(index + 1)?.startsWith("+++ ") == true &&
                lines.getOrNull(index + 2)?.startsWith("@@") == true
            // Inside a counted hunk every sign line is content; a blank line
            // still owing counts is a stripped empty context line (jsdiff
            // counts it as one), while a blank at the end of the patch is the
            // separator and anything without a sign ends the hunk.
            val body = inHunk && !nextFileHeader && (
                (line.isNotEmpty() && line[0] in HUNK_SIGNS) ||
                    (line.isEmpty() && index != lines.lastIndex)
                )
            if (inHunk && !body) inHunk = false
            when {
                nextFileHeader -> out += DiffLine(DiffLineKind.FILE_HEADER, line)
                line.isEmpty() && !body -> Unit // blank separator between hunks/files
                line.startsWith("@@") -> {
                    out += DiffLine(DiffLineKind.HUNK, line)
                    val header = HUNK_HEADER.find(line)
                    counted = header != null
                    oldLeft = header?.groups?.get(2)?.value?.toIntOrNull() ?: 1
                    newLeft = header?.groups?.get(4)?.value?.toIntOrNull() ?: 1
                    inHunk = true
                }
                body && line.startsWith("\\") -> out += DiffLine(DiffLineKind.META, line)
                body && (line.isEmpty() || line.startsWith(" ")) -> {
                    out += DiffLine(DiffLineKind.CONTEXT, line)
                    if (counted) {
                        oldLeft--
                        newLeft--
                        if (oldLeft <= 0 && newLeft <= 0) inHunk = false
                    }
                }
                body && line.startsWith("-") -> {
                    out += DiffLine(DiffLineKind.DELETION, line)
                    if (counted) {
                        oldLeft--
                        if (oldLeft <= 0 && newLeft <= 0) inHunk = false
                    }
                }
                body && line.startsWith("+") -> {
                    out += DiffLine(DiffLineKind.ADDITION, line)
                    if (counted) {
                        newLeft--
                        if (oldLeft <= 0 && newLeft <= 0) inHunk = false
                    }
                }
                line.startsWith("diff --git") || line.startsWith("index ") ||
                    line.startsWith("---") || line.startsWith("+++") ||
                    line.startsWith("new file mode") || line.startsWith("deleted file mode") ||
                    line.startsWith("old mode") || line.startsWith("new mode") ||
                    line.startsWith("similarity index") || line.startsWith("dissimilarity index") ||
                    line.startsWith("rename from") || line.startsWith("rename to") ||
                    line.startsWith("copy from") || line.startsWith("copy to") ||
                    line.startsWith("Binary files") || line.startsWith("GIT binary patch") ->
                    out += DiffLine(DiffLineKind.FILE_HEADER, line)
                // Outside a counted hunk the `---`/`+++` headers are claimed
                // above, so a leading +/- here is a real content line.
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
 * `FilterFiles.kt`. A blank query matches everything (inactive filter); a
 * punctuation-only query stays active but must decide on the raw substring
 * alone — [normalize] collapses it to nothing and `contains("")` would pass
 * every file while the filter claims to be working.
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
                (search.isNotEmpty() && normalize(value).contains(search)) ||
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

    /**
     * The rows a **search** should filter over, and the one rule that decides
     * them (v97 fix, `research/v97_fix/REVIEW_HOME_SEARCH_DIAG.md` §N5, 派生缺陷).
     *
     * The browse list flattens with `expanded = { expandedDirs.containsKey(it) }`
     * (`ReviewFilesTab.kt:233-239`), so a file inside a collapsed directory was
     * never in `rows` and no filter could reach it — searching `gradle` found no
     * `build.gradle.kts` until the tester expanded its folder by hand, with
     * nothing in the UI saying that was required. Upstream avoids the whole
     * question by switching to a flat full-result list while searching
     * (`session-review-v2.tsx:88-123`).
     *
     * The Android tree is lazily loaded, so 「all files」 can only ever mean
     * *all loaded* files: while a query is active, descent is gated on
     * [hasLoadedChildren] — every directory whose listing the app already
     * fetched — instead of [isExpanded]. Two properties make this safe:
     *
     * - it adds **no** network traffic and never mutates [isExpanded]'s backing
     *   state, so the browse tree's expansion is untouched and clearing the
     *   filter restores exactly the tree the user left;
     * - a blank query is not active, so the caller keeps the plain expanded-gated
     *   rows and this function is a no-op — the N5 clear-restores-full-list
     *   contract is preserved by construction rather than by a key choice.
     */
    fun searchRows(
        children: (String) -> List<OpenCodeClient.FileNode>,
        root: String,
        isExpanded: (String) -> Boolean,
        hasLoadedChildren: (String) -> Boolean,
        query: String,
    ): List<ReviewFileTree.Row> =
        if (!isActive(query)) {
            ReviewFileTree.flatten(children, root, isExpanded)
        } else {
            ReviewFileTree.flatten(children, root, hasLoadedChildren)
        }
}

/**
 * Read identity of one file-tree listing, and the eviction rule that goes with
 * it (v97 U11, `research/v97_verify/5562_v98/REPORT_SWEEPD2.md` §3.11/§4.3).
 *
 * The tree is a lazy per-directory walk whose `load` treats an already-listed
 * directory as answered, so a 「刷新」 that only re-ran the read would hand the
 * *previous* listing back — narrowed by whatever filter was last typed, with no
 * way for the screen to tell. 「Refresh produces a new read request」 is therefore
 * two pure statements, both pinned by `ReviewFilterClearV97Test`:
 *
 * - [readId] changes whenever the host bumps the refresh key, so two refreshes
 *   can never share a request identity;
 * - [mustEvictBeforeReload] is false for the mount-time read and true for every
 *   later key, so a refresh always drops the cached listing first. A failed
 *   re-read then lands in `failedDirs` and the body reports it, instead of
 *   re-rendering the last filtered subset as if it were the answer.
 */
object ReviewTreeRead {

    /** Stable identity of one read of [root] at [refreshKey]. */
    fun readId(root: String?, refreshKey: Int): String = "${root.orEmpty()}#r$refreshKey"

    /**
     * The mount read must not evict (nothing is cached yet); any later
     * [refreshKey] is a user 「刷新」 and must drop the cached listing first.
     */
    fun mustEvictBeforeReload(refreshKey: Int, mountRefreshKey: Int): Boolean =
        refreshKey != mountRefreshKey
}

// --------------------------------------------------------------- change scope

/** Upstream `ChangeMode` (`app/src/session/review/model.ts:23`). */
enum class ReviewChangeScope { GIT, BRANCH, LAST_TURN }

/**
 * Outcome of one VCS-scope pull (`ChatViewModel.reviewVcsDiff`). The two cases
 * are what lets the Changes list tell 「没有改动」 from 「改动没取回来」: an empty
 * list is a real answer ("this scope has nothing"), while no answer at all — a
 * timeout, a transport error, an HTTP error — is [Failed] and must be reported
 * as such rather than rendered as an empty change list (the v10 misreport: a
 * `vcs/diff?mode=working` that hung on a large working tree showed
 * 「No uncommitted changes yet」).
 */
sealed interface ReviewVcsDiffResult {

    /** A location answered: [diffs] is the whole truth (empty = really nothing changed). */
    data class Loaded(val diffs: List<OpenCodeClient.FileDiff>) : ReviewVcsDiffResult

    /** No candidate answered — every read timed out or failed. */
    data object Failed : ReviewVcsDiffResult
}

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

    /**
     * Scope a freshly mounted panel opens on. Upstream seeds the view with
     * `"git"` (`model.ts:40 mode() ?? "git"`) and only rewrites it when the
     * switch does not offer the current one (`model.ts:341-350 list[0]`), so
     * the **git working tree** — not the session snapshot — is the review's
     * first data source. The last-turn scope stays the fallback for a project
     * without a repository, where it is the only offered scope.
     */
    fun defaultScope(available: List<ReviewChangeScope>): ReviewChangeScope =
        if (ReviewChangeScope.GIT in available) ReviewChangeScope.GIT
        else available.firstOrNull() ?: ReviewChangeScope.LAST_TURN

    /**
     * Diffs the asked scope renders: the session snapshot for the last-turn
     * scope, the VCS list otherwise — with the snapshot as the **fallback**
     * when a VCS scope came back empty (「有啥显示啥」).
     *
     * The two sources fail independently: `vcs.diff?mode=working` is dead for
     * every location whose services were built before `git init` (measured
     * 2026-09-25, `research/v8_verify/err_review`), while `session.diff` is a
     * snapshot diff that is dead for exactly the same location but alive for a
     * session whose steps did record trees (any tool — the shell commands of
     * the report included). Falling back keeps the files a session
     * demonstrably changed visible instead of reporting "no uncommitted
     * changes" for both.
     */
    fun diffsFor(
        scope: ReviewChangeScope,
        sessionDiffs: List<OpenCodeClient.FileDiff>,
        vcsDiffs: List<OpenCodeClient.FileDiff>,
    ): List<OpenCodeClient.FileDiff> = when (scope) {
        ReviewChangeScope.LAST_TURN -> sessionDiffs
        else -> vcsDiffs.ifEmpty { sessionDiffs }
    }

    /**
     * The 「Create a Git repository」 empty state. Upstream gates it on
     * `mode() === "turn" && project() && !project().vcs` (`view.tsx:252`) — a
     * project *known* to have no repository.
     *
     * The app used to settle that verdict on a timer instead ("`/vcs` answered
     * no branch and the diff is empty after 700 ms"), which claimed
     * "no repository" for a location whose cached services predate a later
     * `git init` — the false CTA of the v8-B report (the project had been a Git
     * repository for over half an hour at that point, and `GET /api/project`
     * says so).
     *
     * @param hasVcs host verdict (`project.vcs`), null = unknown.
     * @param projectVcs fresh `GET /api/project` probe verdict (see
     *   `rememberReviewProjectVcs`), null = unknown.
     * @param hasChanges the active scope already renders diffs — nothing to
     *   offer, and the CTA never displaces a non-empty list.
     */
    fun noGit(hasVcs: Boolean?, projectVcs: Boolean?, hasChanges: Boolean): Boolean {
        if (hasChanges) return false
        return hasVcs == false || projectVcs == false
    }

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

    /** Body the Changes list renders for the active scope (see [changesBody]). */
    enum class ChangesBody {
        /** Files of the active scope. */
        LIST,

        /** No repository at all → the create-repository CTA. */
        NO_GIT,

        /** A read is out — neither 「no changes」 nor 「failed」 yet. */
        LOADING,

        /** The scope's own read timed out / failed → failure copy + retry. */
        LOAD_FAILED,

        /**
         * A VCS-scope read still out after [REVIEW_LOAD_STALL_MS] → the slow
         * read's own copy, which claims nothing about the repository (see
         * [changesBody]).
         */
        LOAD_SLOW,

        /** Read succeeded, nothing changed → the scope's empty copy. */
        EMPTY,
    }

    /**
     * How long a VCS-scope read may stay out before the page stops saying
     * 「加载中」 and starts naming the read as slow.
     *
     * v49 (`research/v49_verify/tD/v49_63_changes3.png`, `v49_68`): a Changes
     * page opened on a directory with no repository sat on 「加载中」 for as
     * long as the tester looked. The read it waits for — `vcs/diff?mode=git`
     * on a location the server never gave a VCS service — has nothing to
     * answer *and* takes the whole client deadline ([REVIEW_VCS_TIMEOUT_MS],
     * 20 s) to give up, so a page whose only question is 「有 Git 改动吗」 can
     * sit silent for tens of seconds with no way to read the silence as a
     * verdict. 5 s is past any answer a real repository gives and short
     * enough that the page always converges inside the timeout.
     */
    const val REVIEW_LOAD_STALL_MS = 5_000L

    /**
     * Whether a read that has been out for [pendingMs] is slow rather than
     * merely in flight. Pure so the JVM pin can read it without a Compose
     * runtime, and so both callers (the tab's watchdog, the tests) share one
     * number instead of a literal each.
     */
    fun stalled(pending: Boolean, pendingMs: Long): Boolean =
        pending && pendingMs >= REVIEW_LOAD_STALL_MS

    /**
     * Whether the `+N -N` summary may be presented as **this scope's** own
     * totals (v97 fix, `research/v97_fix/REVIEW_HOME_SEARCH_DIAG.md` §N6-①).
     *
     * The client never truncates a diff — [ReviewDiff.totals] sums every file
     * the server returned, and no cap sits on the read (`V2Api.callBody` reads
     * the whole body, `vcsDiff` sends no `limit`). What a huge working tree
     * produces is a **partial** `mode=working` answer: the header read
     * `+153 -76` where `git diff --shortstat HEAD` said 57407 insertions. This
     * repository already made 「a short list must say so」 a hard contract for
     * the message list ([MessageDrain.truncated] → `V2Api.kt:578-580`, carried
     * into the export payload by `V2Transport.kt:566-568` 「so a short transcript
     * is never presented as a complete one」). The diff summary owed the same.
     *
     * Two ways the rendered list stops being the scope's own answer, both of
     * which must suppress the numbers rather than dress a fallback up as the
     * scope's total:
     * - the scope's read [loadFailed] and [ReviewScopes.diffsFor] fell back to
     *   the session snapshot — the `+N -N` would then be *another source's*
     *   totals under this scope's chip (the misreport the `LoadFailedBanner`
     *   already calls out for the file list);
     * - the last-turn scope itself, whose numbers come from the session
     *   snapshot by definition and are complete for it, stays as it is — the
     *   rule is about the *fallback*, not about the source.
     *
     * Deliberately no new user-visible string: 「the number may be partial」
     * without knowing *why* invites the reader to distrust every diff, and the
     * failure case already has copy ([oc_review_changes_failed_hint]) plus the
     * banner. Hiding the totals is the honest half of the contract, and
     * [ReviewDiff.totals] stays the single summing rule.
     */
    fun changesSummaryPartial(scope: ReviewChangeScope, loadFailed: Boolean): Boolean =
        loadFailed && scope != ReviewChangeScope.LAST_TURN

    /**
     * Which body [ReviewChangesTab] renders. Five states, because the empty
     * copy is a claim about the repository ("no uncommitted changes"), and
     * neither a read that never answered nor one still in flight has a right to
     * make it: on v10 a `vcs/diff?mode=working` that hung on a large working
     * tree left the list empty and the panel said 「No uncommitted changes yet」
     * for a repository that had plenty.
     *
     * Order matters:
     * - a non-empty list is never displaced (`ReviewScopes.noGit`'s rule);
     * - a **proven** absent repository wins over the loading/failure copy: its
     *   VCS read has nothing to answer either way, and the CTA is the
     *   actionable state;
     * - [loadPending]/[loadFailed] are the *VCS* scopes' own verdicts, so the
     *   snapshot scope is resolved before them — its source is the session
     *   diff, and a stale VCS state must not relabel it;
     * - [loadStalled] sits next to [loadPending] because it is the *same* read,
     *   only older: it still claims nothing about the repository, but it tells
     *   the user the page is waiting on a read that is not coming rather than
     *   leaving 「加载中」 as the whole answer (v49: a non-Git directory's
     *   Changes page never converged — see [REVIEW_LOAD_STALL_MS]).
     */
    fun changesBody(
        scope: ReviewChangeScope,
        noGit: Boolean,
        loadFailed: Boolean,
        loadPending: Boolean,
        hasDiffs: Boolean,
        loadStalled: Boolean = false,
    ): ChangesBody = when {
        hasDiffs -> ChangesBody.LIST
        noGit -> ChangesBody.NO_GIT
        scope == ReviewChangeScope.LAST_TURN -> ChangesBody.EMPTY
        loadFailed -> ChangesBody.LOAD_FAILED
        loadStalled -> ChangesBody.LOAD_SLOW
        loadPending -> ChangesBody.LOADING
        else -> ChangesBody.EMPTY
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

// ------------------------------------------------------------- vcs re-probe

/**
 * The review's VCS reads are *location*-scoped, and a location's services are
 * built once per server process (`LayerMap`, `idleTimeToLive: Duration.infinity`,
 * `sdk/src/internal/instances.ts:89`) from a `Location` value that captured
 * `project.vcs` at build time. A directory that only became a Git repository
 * *after* that build therefore keeps `vcs: undefined` for the life of the
 * process, and every read hangs off it:
 *
 * - the builtin git plugin never registers its adapter
 *   (`plugin/vcs/git.ts:27 if (location.vcs?.type !== "git") return`), so
 *   `GET /api/vcs` answers `{"branch":{}}` (no `provider`), `GET /api/vcs/diff`
 *   and `/api/vcs/status` answer `[]` and `/api/vcs/base` answers `null`;
 * - `Snapshot.capture()` bails on the same flag (`snapshot.ts enabled()`), so no
 *   turn records a tree and `GET /api/session/:id/diff` — a snapshot diff — is
 *   `{"data":[]}` forever.
 *
 * Measured live on 2.0.11 (`research/v8_verify/err_review`), session directory
 * `/home/xiufeigo` after the model ran `git init` there:
 *
 * | ref | `GET /api/vcs` |
 * | --- | --- |
 * | `/home/xiufeigo` (cached graph) | `{"branch":{}}` |
 * | `/home/xiufeigo/.` (same directory, fresh graph) | `{"provider":"git","branch":{"current":"master","default":"master"}}` |
 *
 * The ref key is the raw request string (`LocationServiceMap` caches by
 * `Location.Ref`, no normalisation on POSIX), while `Project.resolve` resolves
 * it with `realpath` before looking for `.git` — so `<dir>/.` is the same
 * project through a *fresh* graph. That is the only read-only way to re-probe:
 * the server's own remedies are writes (`DELETE /api/debug/location` evicts one
 * location, `POST /api/location/reload` rebuilds every location and cancels
 * pending permissions/forms), which the app must not fire behind the user's
 * back.
 */
object ReviewVcsReprobe {

    /**
     * Location refs a review VCS read should try for [directory], canonical
     * first. Empty when [directory] is unknown — an unscoped read has no
     * variant to build, and inventing one would ask about another project.
     */
    fun refs(directory: String?): List<String> {
        val dir = directory?.trim().orEmpty()
        if (dir.isEmpty()) return emptyList()
        // A POSIX server takes `<dir>/.`; a Windows-hosted one keeps `\` but the
        // same `\.` never normalises away on a POSIX box, so both are harmless.
        val separator = if (dir.contains('/')) "/" else "\\"
        val variant =
            if (dir.endsWith("/") || dir.endsWith("\\")) "$dir."
            else "$dir$separator."
        return listOf(dir, variant).distinct()
    }

    /**
     * Whether one ref's answer proves the location has a working VCS provider:
     * a branch name, or at least one changed file. A location without a
     * provider answers `{"branch":{}}` / `[]`, which is also what a clean
     * repository answers — so this only decides *which ref to trust*, never
     * "there is no repository" (that verdict needs `GET /api/project`, see
     * [ReviewScopes.noGit]).
     */
    fun answered(branch: String?, files: Int): Boolean = !branch.isNullOrEmpty() || files > 0
}
