@file:OptIn(ExperimentalFoundationApi::class)

package com.xiufeigo.opencodeshell.ui.session.review

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.OpenCodeClient
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.overlay.DialogScrim
import com.xiufeigo.opencodeshell.ui.overlay.dialogCardBounds
import com.xiufeigo.opencodeshell.ui.overlay.rememberDialogScrimState
import com.xiufeigo.opencodeshell.ui.session.drawer.MobileColors
import com.xiufeigo.opencodeshell.ui.theme.OcColors
import com.xiufeigo.opencodeshell.ui.theme.OcMotion
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import com.xiufeigo.opencodeshell.ui.theme.ocShadow
import kotlinx.coroutines.delay

/**
 * 「变更」 tab of the review panel, the Android counterpart of the upstream
 * `SessionReviewV2Sidebar` change list: one row per `Snapshot.FileDiff`
 * (status letter + basename + muted directory + `+N`/`-N` counters), tapping
 * a row expands its patch inline.
 *
 * Diff colours follow the upstream review (`+additions` green / `-deletions`
 * red; status A/D/M in success/danger/info), all through `OcTheme.colors`.
 *
 * **W5-R additions:**
 * - a **toolbar** with the `unified`/`split` switch and the
 *   `expand`/`collapse` mode switch (upstream `session-review-v2.tsx:266-309`);
 * - every patch line renders in a **line-number gutter**
 *   (`--diffs-min-number-column-width: 3ch`, muted on `layer01`) with a 2 dp
 *   **change bar** (`diffIndicators: "bars"`) and **word-level emphasis** of
 *   the changed range (`lineDiffType: "word-alt"`), see [ReviewDiffView];
 * - the patch rows are individual `LazyColumn` items, so a large diff only
 *   composes its visible rows, and a huge file opens with the non-diff lines
 *   hidden ([ReviewDiffView.autoCollapse]);
 * - the **no-git empty state** (`SessionReviewEmptyNoGitV2`,
 *   `session-review-empty-no-git-v2.tsx:11-27`), and a scope-aware empty copy
 *   (`review/view.tsx:253-257`).
 *
 * The previous per-file `AnimatedVisibility` expand animation is replaced by
 * `Modifier.animateItem()` on the row items: a `LazyColumn` cannot animate a
 * group of items, and virtualization was the point of the change.
 *
 * @param diffs changed files of the active scope.
 * @param noGit no repository at all → the create-repository empty state.
 * @param onInitGit "Create Git repository"; null keeps the action disabled.
 * @param scope which change scope [diffs] came from — selects the empty copy.
 * @param baseLabel `name@ref` of the diff base ([ReviewVcsBase.label], from
 *   `GET /api/vcs/base`) — rendered as the list's first line 「相对 …」, the
 *   upstream review's base row. `null` (no base, or not loaded) hides it.
 * @param branch vcs branch chip (`review/view.tsx` repo/branch meta). The
 *   desktop panel renders it in its own header; the mobile Changes page has
 *   no header of its own, so the chip lives here once [onScopeChange] wires
 *   the mobile wiring.
 * @param onScopeChange wires the **Git / Branch / Last-turn** switch
 *   (`review/view.tsx:228-247`) into this tab for the mobile form, which
 *   mounts [ReviewChangesTab] directly instead of [SessionReviewPanel]. When
 *   it is null (every desktop call site) this tab renders no scope chrome —
 *   the panel header already owns it and must not see a second copy.
 * @param hasVcs project VCS flag (`project.vcs`); null = unknown, treated as
 *   "a repo" (same rule as [SessionReviewPanel]).
 * @param defaultBranch `vcs.branch.default`, needed before the Branch scope
 *   is offered (`review/model.ts:59-66`).
 * @param vcsWired whether the host answers scope diff requests — without it
 *   the Git / Branch chips stay disabled ([ReviewScopes.enabled]).
 * @param onRefresh 「刷新」 of the mobile Changes header: the host evicts the
 *   directory's cached location and re-reads its review payloads (see
 *   `ChatViewModel.reviewReprobeLocation`). Only rendered when the host wired
 *   it (`onScopeChange != null` form), and only ever reached by the user's tap.
 * @param refreshPending the host's evict is still out; the action dims and
 *   stops accepting taps.
 * @param loadFailed the active VCS scope's own read did not come back (timeout
 *   or failure — `ReviewVcsDiffResult.Failed`). The body then says so and
 *   offers [onRetryDiff] instead of the 「no uncommitted changes」 copy, which
 *   is a claim about the repository and not about a request: on v10 a hung
 *   `vcs/diff?mode=working` showed 「No uncommitted changes yet」 for a
 *   repository with plenty. Neither state relabels `LAST_TURN` (its source is
 *   the session snapshot) — see `ReviewScopes.changesBody`.
 * @param loadPending the host's read for this scope is still out. The body then
 *   waits instead of claiming anything: 「no changes」 is as wrong while a read
 *   is in flight as it is after one failed.
 * @param onRetryDiff re-runs the active scope's diff read (null keeps the
 *   failure copy without a button). Not to be confused with [onRefresh]: the
 *   retry is one request, the refresh evicts the directory's cached location.
 * @param commentStore F-2 line-comment store, hoisted by the host
 *   (`SessionScreen`, keyed by session) so the desktop panel and the mobile
 *   Changes page share one list. Null disables every comment affordance;
 *   quoting still works through [onQuoteLine].
 * @param onQuoteLine F-2 S-step: tapping a content diff line appends
 *   [ReviewLineQuote.quoteLine] to the composer. The host routes it to
 *   `ChatViewModel.appendComposerText`. Null leaves lines untappable.
 * @param onSendComments F-2: a file's 「加入输入框」 button hands the host the
 *   aggregated prompt text ([ReviewCommentPrompt.buildPrompt] over that
 *   file's comments, oldest first). The host appends it to the composer via
 *   `ChatViewModel.appendComposerText`.
 */
@Composable
fun ReviewChangesTab(
    diffs: List<OpenCodeClient.FileDiff>,
    modifier: Modifier = Modifier,
    noGit: Boolean = false,
    onInitGit: (() -> Unit)? = null,
    gitPending: Boolean = false,
    scope: ReviewChangeScope = ReviewChangeScope.LAST_TURN,
    baseLabel: String? = null,
    branch: String? = null,
    onScopeChange: ((ReviewChangeScope) -> Unit)? = null,
    hasVcs: Boolean? = null,
    defaultBranch: String? = null,
    vcsWired: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    refreshPending: Boolean = false,
    loadFailed: Boolean = false,
    loadPending: Boolean = false,
    onRetryDiff: (() -> Unit)? = null,
    commentStore: ReviewCommentStore? = null,
    onQuoteLine: ((String) -> Unit)? = null,
    onSendComments: ((String) -> Unit)? = null,
) {
    // Row expansion survives list recycling, like the timeline's `toolOpen`.
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    var diffStyle by rememberSaveable { mutableStateOf(ReviewDiffStyle.UNIFIED) }
    var expandMode by rememberSaveable { mutableStateOf(ReviewExpandMode.EXPAND) }
    // One horizontal offset shared by every patch row, so the diff scrolls as
    // one block even though each row owns its own `horizontalScroll`.
    val horizontal = rememberScrollState()

    // Mobile wiring (`onScopeChange != null`): the branch chip + scope switch
    // the desktop panel renders in its own header. They sit above *every*
    // body state — an empty scope is exactly when the user needs the switch.
    val available = remember(hasVcs, branch, defaultBranch) {
        ReviewScopes.available(hasVcs = hasVcs ?: true, currentBranch = branch, defaultBranch = defaultBranch)
    }
    val scopeHeader: @Composable (Modifier) -> Unit = { headerModifier ->
        if (onScopeChange != null) {
            ReviewScopeHeader(
                branch = branch,
                scope = scope,
                onScopeChange = onScopeChange,
                available = available,
                vcsWired = vcsWired,
                onRefresh = onRefresh,
                refreshPending = refreshPending,
                modifier = headerModifier,
            )
        }
    }

    // v49 (`research/v49_verify/tD/v49_63_changes3.png`, `v49_68`): a page on
    // a directory with no repository stayed on 「加载中」 for as long as the
    // tester watched, because the VCS-scope read it waits for has nothing to
    // answer and only gives up at the client deadline (20 s). The watchdog
    // does not cancel that read — a late answer still lands and replaces this
    // body — it only stops the page from *only* ever saying 「加载中」.
    var loadStalled by remember { mutableStateOf(false) }
    LaunchedEffect(loadPending) {
        if (!loadPending) {
            loadStalled = false
            return@LaunchedEffect
        }
        delay(ReviewScopes.REVIEW_LOAD_STALL_MS)
        loadStalled = ReviewScopes.stalled(loadPending, ReviewScopes.REVIEW_LOAD_STALL_MS)
    }

    // Which body this scope renders — the pure rule, so the failure state, the
    // in-flight state and the empty copy can never drift apart (see
    // `ReviewScopes.changesBody`).
    val body = ReviewScopes.changesBody(
        scope = scope,
        noGit = noGit,
        loadFailed = loadFailed,
        loadPending = loadPending,
        hasDiffs = diffs.isNotEmpty(),
        loadStalled = loadStalled,
    )
    when (body) {
        ReviewScopes.ChangesBody.NO_GIT -> {
            Column(modifier = modifier) {
                scopeHeader(Modifier.fillMaxWidth())
                NoGitEmpty(
                    onInitGit = onInitGit,
                    pending = gitPending,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
            return
        }
        ReviewScopes.ChangesBody.EMPTY,
        ReviewScopes.ChangesBody.LOADING,
        ReviewScopes.ChangesBody.LOAD_SLOW,
        ReviewScopes.ChangesBody.LOAD_FAILED,
        -> {
            Column(modifier = modifier) {
                scopeHeader(Modifier.fillMaxWidth())
                when (body) {
                    ReviewScopes.ChangesBody.LOAD_FAILED -> LoadFailedEmpty(
                        onRetry = onRetryDiff,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                    // A read that has not answered yet must not borrow the
                    // empty copy: 「静默等」 is honest, 「没有改动」 is not.
                    // Past [REVIEW_LOAD_STALL_MS] even 「静默等」 stops being the
                    // whole answer, so the page names the wait instead (v49).
                    ReviewScopes.ChangesBody.LOAD_SLOW -> EmptyHint(
                        // The failure hint is this state's copy as well: it
                        // already says the read did not come back and names
                        // 「刷新」 as the one request to re-issue, which is
                        // exactly what a non-Git directory needs.
                        text = stringResource(R.string.oc_review_changes_failed_hint),
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                    ReviewScopes.ChangesBody.LOADING -> EmptyHint(
                        text = stringResource(R.string.oc_common_loading),
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                    else -> EmptyHint(
                        text = stringResource(ReviewScopes.emptyRes(scope)),
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
            }
            return
        }
        ReviewScopes.ChangesBody.LIST -> Unit
    }

    val entries = remember(diffs) { diffs.mapNotNull { diff -> diff.file?.let { it to diff } } }
    val (additions, deletions) = remember(diffs) { ReviewDiff.totals(diffs) }
    val openFiles = entries.filter { expanded[it.first] == true }.map { it.first }
    // Only the open files build a patch model (a session can carry dozens of
    // files and a huge patch each).
    val views = remember(entries, openFiles, expandMode, diffStyle) {
        val byFile = entries.toMap()
        openFiles.associateWith { file ->
            val patch = byFile[file]?.patch
            val mode = ReviewDiffView.autoCollapse(ReviewDiffView.lineCount(patch), expandMode)
            ReviewDiffView.view(patch, mode, diffStyle)
        }
    }

    // F-2 line comments: the hoisted store observed as chronological state
    // (upstream `comments.all()`), grouped per file for the cards. The store
    // identity is stable (the host `remember`s it per session), so the
    // conditional collection below never changes shape across recompositions.
    val allReviewComments: List<ReviewLineComment> =
        if (commentStore == null) emptyList() else commentStore.comments.collectAsState().value
    val commentsByFile = remember(allReviewComments) { allReviewComments.groupBy { it.file } }
    // The line the comment dialog is open for (null = closed).
    var commentTarget by remember { mutableStateOf<LineCommentTarget?>(null) }

    Column(modifier = modifier) {
        scopeHeader(Modifier.fillMaxWidth())
        // The scope's own read failed, but `ReviewScopes.diffsFor` still has the
        // session snapshot to show (「有啥显示啥」). Keep those files visible and
        // say why the scope's own list is missing: a silent fallback reads as
        // 「these are the git scope's changes」, which is the misreport this
        // state exists to kill.
        if (loadFailed) {
            LoadFailedBanner(onRetry = onRetryDiff, modifier = Modifier.fillMaxWidth())
        }
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
        if (baseLabel != null) {
            item(key = "vcs-base") {
                Text(
                    text = stringResource(R.string.oc_review_diff_base, baseLabel),
                    color = OcTheme.colors.textMuted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 2.dp),
                )
            }
        }
        item(key = "toolbar") {
            DiffToolbar(
                diffStyle = diffStyle,
                expandMode = expandMode,
                onDiffStyleChange = { diffStyle = it },
                onExpandModeChange = { expandMode = it },
            )
        }
        item(key = "summary") {
            // `DiffChanges` summary: totals across the whole scope diff. v97
            // fix: when the scope's own read failed, the list below is the
            // session-snapshot fallback (`ReviewScopes.diffsFor`) and the server
            // may have truncated the real one on a huge working tree — showing
            // `+N -N` for a list that is neither this scope's answer nor known
            // to be complete is the 「short list presented as a complete one」
            // the message list already refuses to do (`V2Api.kt:578-580`).
            val summaryPartial = ReviewScopes.changesSummaryPartial(scope, loadFailed)
            if (!summaryPartial) {
                Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text(
                        text = "+$additions",
                        color = OcTheme.colors.stateSuccessFg,
                        fontFamily = OcTheme.monoFont,
                        fontSize = 11.sp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "-$deletions",
                        color = OcTheme.colors.stateDangerFg,
                        fontFamily = OcTheme.monoFont,
                        fontSize = 11.sp,
                    )
                }
            }
        }
        entries.forEach { (file, diff) ->
            val open = expanded[file] == true
            val fileComments = commentsByFile[file].orEmpty()
            item(key = "file:$file") {
                DiffFileRow(
                    diff = diff,
                    open = open,
                    onClick = { expanded[file] = !open },
                    commentCount = fileComments.size,
                )
            }
            if (!open) return@forEach
            if (fileComments.isNotEmpty()) {
                item(key = "comments:$file") {
                    FileCommentsSection(
                        file = file,
                        comments = fileComments,
                        onEdit = { comment ->
                            commentTarget = LineCommentTarget(
                                file = comment.file,
                                line = comment.startLine,
                                preview = comment.preview.orEmpty(),
                                existingId = comment.id,
                                initialText = comment.comment,
                            )
                        },
                        onDelete = { comment -> commentStore?.remove(comment.file, comment.id) },
                        onSend = if (onSendComments == null) null else {
                            { onSendComments(ReviewCommentPrompt.buildPrompt("", fileComments)) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            val view = views[file]
            if (view == null || view.lineCount == 0) {
                // Nothing to draw: either the patch carries no hunk at all, or
                // `collapse` hid every line of a context-only patch.
                if (!ReviewDiff.hasHunks(diff.patch)) {
                    item(key = "nopatch:$file") { NoPatchHint() }
                }
                return@forEach
            }
            itemsIndexed(
                items = view.rows,
                key = { index, _ -> "line:$file:$index" },
            ) { _, row ->
                val itemModifier = Modifier
                    .fillMaxWidth()
                    .animateItem(
                        fadeInSpec = OcMotion.fade,
                        placementSpec = null,
                        fadeOutSpec = OcMotion.fade,
                    )
                when (row) {
                    is PatchRow.Line -> {
                        val line = row.row
                        val lineNo = line.newLine ?: line.oldLine
                        val quotable = lineNo != null && isContentLine(line.kind)
                        DiffLineRow(
                            row = line,
                            horizontal = horizontal,
                            modifier = itemModifier,
                            onTap = if (onQuoteLine != null && quotable) {
                                {
                                    ReviewLineQuote.quoteLine(file, line.oldLine, line.newLine, line.content)
                                        ?.let(onQuoteLine)
                                }
                            } else null,
                            onLongPress = if (commentStore != null && quotable) {
                                {
                                    commentTarget = LineCommentTarget(
                                        file = file,
                                        line = lineNo ?: return@DiffLineRow,
                                        preview = line.content,
                                    )
                                }
                            } else null,
                        )
                    }
                    is PatchRow.Columns -> SplitLineRow(
                        cells = row,
                        modifier = itemModifier,
                        onTap = if (onQuoteLine == null) null else { tapped ->
                            ReviewLineQuote.quoteLine(file, tapped.oldLine, tapped.newLine, tapped.content)
                                ?.let(onQuoteLine)
                        },
                        onLongPress = if (commentStore == null) null else { tapped, tappedLine ->
                            commentTarget = LineCommentTarget(
                                file = file,
                                line = tappedLine,
                                preview = tapped.content,
                            )
                        },
                    )
                }
            }
            if (view.truncated) {
                item(key = "truncated:$file") {
                    Text(
                        text = stringResource(
                            R.string.oc_review_truncated_rows,
                            ReviewDiffView.MAX_ROWS,
                            view.lineCount,
                        ),
                        color = OcTheme.colors.textFaint,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(start = 28.dp, top = 2.dp, bottom = 6.dp),
                    )
                }
            }
        }
        }
        // F-2 comment editor: opened by long-pressing a content diff line or by
        // a comment card's edit action; saves into the hoisted store.
        val target = commentTarget
        val store = commentStore
        if (target != null && store != null) {
            LineCommentDialog(
                target = target,
                onDismiss = { commentTarget = null },
                onSave = { text ->
                    if (target.existingId != null) {
                        store.update(target.file, target.existingId, text)
                    } else {
                        store.add(
                            NewReviewComment(
                                file = target.file,
                                startLine = target.line,
                                endLine = target.line,
                                comment = text,
                                preview = target.preview.takeIf { it.isNotBlank() },
                            ),
                        )
                    }
                    commentTarget = null
                },
            )
        }
    }
}

/** A content diff line can be quoted and commented; headers/hunks/meta cannot. */
private fun isContentLine(kind: DiffLineKind): Boolean =
    kind == DiffLineKind.ADDITION || kind == DiffLineKind.DELETION || kind == DiffLineKind.CONTEXT

/**
 * The comment dialog's target: either a fresh anchor from a long-pressed line
 * ([existingId] null) or an edit of [existingId] with [initialText] prefilled.
 */
private data class LineCommentTarget(
    val file: String,
    val line: Int,
    val preview: String,
    val existingId: String? = null,
    val initialText: String = "",
)

/**
 * Mobile Changes header: the vcs **branch chip** (the desktop panel's
 * `review/view.tsx` repo/branch meta label) and the **Git / Branch / Last-turn**
 * scope switch (`review/view.tsx:228-247`), which the desktop
 * [SessionReviewPanel] renders around this tab and the mobile form — mounting
 * [ReviewChangesTab] directly — would otherwise never see. Chips reuse the
 * panel's [ReviewChip] and the same [ReviewScopes.enabled] availability rules,
 * so the two forms cannot drift.
 */
@Composable
private fun ReviewScopeHeader(
    branch: String?,
    scope: ReviewChangeScope,
    onScopeChange: (ReviewChangeScope) -> Unit,
    available: List<ReviewChangeScope>,
    vcsWired: Boolean,
    onRefresh: (() -> Unit)?,
    refreshPending: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    Column(modifier = modifier) {
        if (!branch.isNullOrEmpty()) {
            Text(
                text = branch,
                color = c.textMuted,
                fontFamily = OcTheme.monoFont,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(start = 12.dp, top = 8.dp, end = 12.dp)
                    .widthIn(max = 140.dp)
                    .clip(OcTheme.shapes.sm)
                    .background(c.layer02)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                // Only the chips scroll: the 「刷新」 action is pinned to the
                // trailing edge, where a fixed-width row can weight it, instead
                // of hiding after three chips on a narrow phone.
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ReviewChangeScope.values().forEach { option ->
                    ReviewChip(
                        label = stringResource(ReviewScopes.labelRes(option)),
                        selected = scope == option,
                        enabled = ReviewScopes.enabled(option, available, vcsWired),
                        onClick = { onScopeChange(option) },
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
            }
            if (onRefresh != null) {
                // The mobile form's only 「刷新」 — the desktop panel owns the one
                // in its own header.
                ReviewRefreshAction(pending = refreshPending, onClick = onRefresh)
            }
        }
    }
}

/**
 * Diff toolbar: expand/collapse and unified/split, upstream
 * `session-review-v2.tsx:266-309` (`showAllLines`, `hideNonDiffLines`,
 * `unifiedDiff`, `splitDiff`). Horizontally scrollable so the four labels never
 * squeeze into a 200 dp panel.
 */
@Composable
private fun DiffToolbar(
    diffStyle: ReviewDiffStyle,
    expandMode: ReviewExpandMode,
    onDiffStyleChange: (ReviewDiffStyle) -> Unit,
    onExpandModeChange: (ReviewExpandMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = 12.dp, end = 8.dp, top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ReviewChip(
            label = stringResource(R.string.oc_review_expand_all),
            selected = expandMode == ReviewExpandMode.EXPAND,
            enabled = true,
            onClick = { onExpandModeChange(ReviewExpandMode.EXPAND) },
        )
        Spacer(Modifier.width(4.dp))
        ReviewChip(
            label = stringResource(R.string.oc_review_collapse_all),
            selected = expandMode == ReviewExpandMode.COLLAPSE,
            enabled = true,
            onClick = { onExpandModeChange(ReviewExpandMode.COLLAPSE) },
        )
        Spacer(Modifier.width(12.dp))
        ReviewChip(
            label = stringResource(R.string.oc_review_diff_unified),
            selected = diffStyle == ReviewDiffStyle.UNIFIED,
            enabled = true,
            onClick = { onDiffStyleChange(ReviewDiffStyle.UNIFIED) },
        )
        Spacer(Modifier.width(4.dp))
        ReviewChip(
            label = stringResource(R.string.oc_review_diff_split),
            selected = diffStyle == ReviewDiffStyle.SPLIT,
            enabled = true,
            onClick = { onDiffStyleChange(ReviewDiffStyle.SPLIT) },
        )
    }
}

/**
 * One unified diff line: 2 dp change bar, line-number gutter and the body.
 * Geometry follows the Pierre variables (`pierre/index.ts:221-231`):
 * `--diffs-font-size` 13 sp, `--diffs-line-height: 24px`, and the 3ch number
 * column approximated as [NUMBER_COLUMN_WIDTH] because Compose has no `ch`.
 *
 * F-2: [onTap] quotes the line into the composer, [onLongPress] opens the
 * comment editor. Both are null for non-content rows (headers/hunks/meta) and
 * whenever the host did not wire the feature.
 */
@Composable
private fun DiffLineRow(
    row: DiffRenderRow,
    horizontal: ScrollState,
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
) {
    val c = OcTheme.colors
    val annotated = rememberHighlight(row, c)
    val gesture = if (onTap != null || onLongPress != null) {
        Modifier.combinedClickable(
            role = Role.Button,
            onClick = { onTap?.invoke() },
            onLongClick = { onLongPress?.invoke() },
        )
    } else {
        Modifier
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(DIFF_ROW_HEIGHT)
            .background(diffBackground(row.kind, c))
            .then(gesture),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(CHANGE_BAR_WIDTH).fillMaxHeight().background(changeBar(row.kind, c)))
        Box(
            modifier = Modifier
                .width(NUMBER_COLUMN_WIDTH)
                .fillMaxHeight()
                .background(c.layer01),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Text(
                text = (row.oldLine ?: row.newLine)?.toString().orEmpty(),
                color = c.textFaint,
                fontFamily = OcTheme.monoFont,
                fontSize = 11.sp,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .horizontalScroll(horizontal),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = annotated,
                color = diffForeground(row.kind, c),
                fontFamily = OcTheme.monoFont,
                fontSize = 13.sp,
                lineHeight = DIFF_LINE_HEIGHT,
                softWrap = false,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

/**
 * One split row: old on the left, new on the right, sharing the 24 dp row
 * height. Rows that span both columns (hunks/headers) are drawn full width.
 * Long lines are ellipsised here instead of horizontally scrolling — the two
 * columns would otherwise scroll independently.
 */
@Composable
private fun SplitLineRow(
    cells: PatchRow.Columns,
    modifier: Modifier = Modifier,
    onTap: ((DiffRenderRow) -> Unit)? = null,
    onLongPress: ((DiffRenderRow, Int) -> Unit)? = null,
) {
    val c = OcTheme.colors
    val full = cells.full
    if (full != null) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .height(DIFF_ROW_HEIGHT)
                .background(diffBackground(full.kind, c)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = full.text,
                color = diffForeground(full.kind, c),
                fontFamily = OcTheme.monoFont,
                fontSize = 13.sp,
                lineHeight = DIFF_LINE_HEIGHT,
                softWrap = false,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clipToBounds()
                    .padding(horizontal = 8.dp),
            )
        }
        return
    }
    Row(modifier = modifier.fillMaxWidth().height(DIFF_ROW_HEIGHT)) {
        SplitCell(
            row = cells.left,
            side = SplitSide.OLD,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            onTap = onTap,
            onLongPress = onLongPress,
        )
        Box(Modifier.width(0.5.dp).fillMaxHeight().background(c.borderMuted))
        SplitCell(
            row = cells.right,
            side = SplitSide.NEW,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            onTap = onTap,
            onLongPress = onLongPress,
        )
    }
}

/** Which number a split column shows. */
private enum class SplitSide { OLD, NEW }

@Composable
private fun SplitCell(
    row: DiffRenderRow?,
    side: SplitSide,
    modifier: Modifier = Modifier,
    onTap: ((DiffRenderRow) -> Unit)? = null,
    onLongPress: ((DiffRenderRow, Int) -> Unit)? = null,
) {
    val c = OcTheme.colors
    if (row == null) {
        // The pairing partner is missing (unbalanced add/delete run).
        Box(modifier.background(c.layer01))
        return
    }
    val annotated = rememberHighlight(row, c)
    // F-2: the tapped side's own number (old on the left, new on the right);
    // gestures only attach to numbered content cells.
    val lineNo = if (side == SplitSide.OLD) row.oldLine else row.newLine
    val gesture = if (lineNo != null && isContentLine(row.kind) && (onTap != null || onLongPress != null)) {
        Modifier.combinedClickable(
            role = Role.Button,
            onClick = { onTap?.invoke(row) },
            onLongClick = { onLongPress?.invoke(row, lineNo) },
        )
    } else {
        Modifier
    }
    Row(
        modifier = modifier.background(diffBackground(row.kind, c)).then(gesture),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(CHANGE_BAR_WIDTH).fillMaxHeight().background(changeBar(row.kind, c)))
        Box(
            modifier = Modifier
                .width(NUMBER_COLUMN_WIDTH)
                .fillMaxHeight()
                .background(c.layer01),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Text(
                text = (if (side == SplitSide.OLD) row.oldLine else row.newLine)?.toString().orEmpty(),
                color = c.textFaint,
                fontFamily = OcTheme.monoFont,
                fontSize = 11.sp,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        Text(
            text = annotated,
            color = diffForeground(row.kind, c),
            fontFamily = OcTheme.monoFont,
            fontSize = 13.sp,
            lineHeight = DIFF_LINE_HEIGHT,
            softWrap = false,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .clipToBounds()
                .padding(horizontal = 6.dp),
        )
    }
}

/** One changed file: status letter, name over muted path, `+N`/`-N`, chevron. */
@Composable
private fun DiffFileRow(
    diff: OpenCodeClient.FileDiff,
    open: Boolean,
    onClick: () -> Unit,
    commentCount: Int = 0,
) {
    val c = OcTheme.colors
    val file = diff.file.orEmpty()
    val dir = ReviewDiff.dirname(file)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(OcTheme.shapes.sm)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Upstream `kindLabel` ("A"/"D"/"M") with the file-tree change colours.
        Text(
            text = ReviewDiff.statusLabel(diff.status),
            color = when (diff.status) {
                "added" -> c.stateSuccessFg
                "deleted" -> c.stateDangerFg
                else -> c.stateInfoFg
            },
            fontSize = 11.sp,
            fontWeight = FontWeight(530),
            modifier = Modifier.width(16.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = ReviewDiff.basename(file),
                color = c.textBase,
                fontSize = 13.sp,
                fontWeight = FontWeight.W500,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (dir.isNotEmpty()) {
                Text(
                    text = dir,
                    color = c.textFaint,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (diff.additions > 0) {
            Text(
                text = "+${diff.additions}",
                color = c.stateSuccessFg,
                fontFamily = OcTheme.monoFont,
                fontSize = 11.sp,
            )
            Spacer(Modifier.width(6.dp))
        }
        if (diff.deletions > 0) {
            Text(
                text = "-${diff.deletions}",
                color = c.stateDangerFg,
                fontFamily = OcTheme.monoFont,
                fontSize = 11.sp,
            )
            Spacer(Modifier.width(6.dp))
        }
        // F-2: how many line comments anchor to this file (0 hides the badge).
        if (commentCount > 0) {
            Box(
                modifier = Modifier
                    .clip(OcTheme.shapes.sm)
                    .background(c.layer02)
                    .padding(horizontal = 6.dp, vertical = 1.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = commentCount.toString(),
                    color = c.textMuted,
                    fontFamily = OcTheme.monoFont,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(6.dp))
        }
        Icon(
            painter = painterResource(R.drawable.ic_oc_chevron_down),
            contentDescription = null,
            tint = c.iconMuted,
            modifier = Modifier
                .size(14.dp)
                .rotate(if (open) 0f else -90f),
        )
    }
}

/** A diff entry without a textual patch (binary, or the server sent no hunks). */
@Composable
private fun NoPatchHint() {
    Text(
        text = stringResource(R.string.oc_review_no_patch),
        color = OcTheme.colors.textFaint,
        fontSize = 12.sp,
        modifier = Modifier.padding(start = 28.dp, bottom = 4.dp),
    )
}

/**
 * No repository at all: upstream `SessionReviewEmptyNoGitV2`
 * (`session-review-empty-no-git-v2.tsx:11-27`) — icon, title, description and
 * the "Create Git repository" button. The host wires the action
 * (`ReviewGitInit`: `git init` over `POST /api/shell`, then the location
 * evict); a null `onInitGit` keeps the button disabled, and `pending` shows
 * 「正在创建 Git 仓库…」.
 */
@Composable
private fun NoGitEmpty(
    onInitGit: (() -> Unit)?,
    pending: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_oc_file),
            contentDescription = null,
            tint = c.iconMuted,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = stringResource(R.string.oc_review_no_git_title),
            color = c.textBase,
            fontSize = 14.sp,
            fontWeight = FontWeight(530),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            text = stringResource(R.string.oc_review_no_git_description),
            color = c.textMuted,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
        Box(
            modifier = Modifier
                .padding(top = 16.dp)
                .clip(OcTheme.shapes.sm)
                .background(if (onInitGit == null) c.layer02.copy(alpha = 0.4f) else c.layer03)
                .clickable(
                    enabled = onInitGit != null && !pending,
                    role = Role.Button,
                ) { onInitGit?.invoke() }
                .padding(horizontal = 12.dp, vertical = 7.dp),
        ) {
            Text(
                text = stringResource(
                    if (pending) R.string.oc_review_no_git_pending else R.string.oc_review_no_git_action,
                ),
                color = if (onInitGit == null) c.textFaint else c.textBase,
                fontSize = 13.sp,
                fontWeight = FontWeight.W500,
            )
        }
    }
}

/** Centred empty copy of the active scope. */
@Composable
private fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = text,
            color = OcTheme.colors.textFaint,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

/**
 * The read failed (timeout / transport error), so the list is empty for a
 * reason the empty copy would misname. Same shape as [NoGitEmpty] — icon, title,
 * description, one action — with 「刷新」 (the retry) as the action, because the
 * copy tells the user to retry it. [onRetry] is null when the host wired no diff
 * request: the state still shows, just without a button.
 */
@Composable
private fun LoadFailedEmpty(
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_oc_file),
            contentDescription = null,
            tint = c.iconMuted,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = stringResource(R.string.oc_review_changes_failed),
            color = c.textBase,
            fontSize = 14.sp,
            fontWeight = FontWeight(530),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            text = stringResource(R.string.oc_review_changes_failed_hint),
            color = c.textMuted,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (onRetry != null) {
            Box(
                modifier = Modifier
                    .padding(top = 16.dp)
                    .clip(OcTheme.shapes.sm)
                    .background(c.layer03)
                    .clickable(role = Role.Button, onClick = onRetry)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Text(
                    text = stringResource(R.string.oc_review_refresh),
                    color = c.textBase,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.W500,
                )
            }
        }
    }
}

/**
 * The list is showing files (the session snapshot fallback of
 * `ReviewScopes.diffsFor`) while the active scope's own read failed: one muted
 * line above the list, so the fallback cannot read as the scope's own answer.
 * [onRetry] is null when the host wired no diff request (label only).
 */
@Composable
private fun LoadFailedBanner(onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    val c = OcTheme.colors
    Row(
        modifier = modifier.padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.oc_review_changes_failed_hint),
            color = c.textMuted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            modifier = Modifier.weight(1f),
        )
        if (onRetry != null) {
            Text(
                text = stringResource(R.string.oc_review_refresh),
                color = c.textBase,
                fontSize = 11.sp,
                fontWeight = FontWeight(530),
                maxLines = 1,
                modifier = Modifier
                    .clip(OcTheme.shapes.sm)
                    .background(c.layer03)
                    .clickable(role = Role.Button, onClick = onRetry)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * F-2: one open file's line comments — a card per comment (anchor ref, text,
 * optional quoted line, edit/delete) plus the 「加入输入框」 button that hands
 * the host the aggregated prompt text. Upstream renders comments inline on the
 * diff (`line-comment-annotations`); the file section keeps them reachable on
 * a phone without gutter affordances.
 */
@Composable
private fun FileCommentsSection(
    file: String,
    comments: List<ReviewLineComment>,
    onEdit: (ReviewLineComment) -> Unit,
    onDelete: (ReviewLineComment) -> Unit,
    onSend: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    Column(
        modifier = modifier.padding(start = 28.dp, end = 12.dp, top = 2.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        comments.forEach { comment ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(OcTheme.shapes.sm)
                    .background(c.layer01)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "${ReviewDiff.basename(file)}:${comment.startLine}",
                    color = c.textFaint,
                    fontFamily = OcTheme.monoFont,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!comment.preview.isNullOrBlank()) {
                    Text(
                        text = comment.preview,
                        color = c.textFaint,
                        fontFamily = OcTheme.monoFont,
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Text(
                    text = comment.comment,
                    color = c.textBase,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CommentTextAction(
                        label = stringResource(R.string.oc_review_comment_edit),
                        onClick = { onEdit(comment) },
                    )
                    CommentTextAction(
                        label = stringResource(R.string.oc_review_comment_delete),
                        onClick = { onDelete(comment) },
                    )
                }
            }
        }
        if (onSend != null) {
            ReviewChip(
                label = stringResource(R.string.oc_review_comment_send, comments.size),
                selected = false,
                enabled = true,
                onClick = onSend,
            )
        }
    }
}

/** Quiet text action for the comment cards (edit/delete). */
@Composable
private fun CommentTextAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(OcTheme.shapes.sm)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = OcTheme.colors.textMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.W500,
        )
    }
}

/**
 * F-2 comment editor, opened from a long-pressed diff line or a card's edit
 * action. The card follows the repo's dialog idiom (`SessionRowDialogs`:
 * scrim + `bgBase` card, `BasicTextField`, save/cancel buttons) instead of
 * Material `AlertDialog`, so the OC surface tokens stay consistent.
 */
@Composable
private fun LineCommentDialog(
    target: LineCommentTarget,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val c = OcTheme.colors
    val editing = target.existingId != null
    var value by remember(target) {
        mutableStateOf(
            if (editing) {
                TextFieldValue(text = target.initialText, selection = TextRange(target.initialText.length))
            } else {
                TextFieldValue()
            },
        )
    }
    val trimmed = value.text.trim()
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    CommentModalCard(onDismiss = onDismiss) {
        Text(
            text = stringResource(
                if (editing) R.string.oc_review_comment_edit_title else R.string.oc_review_comment_add_title,
            ),
            color = c.textBase,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 21.sp,
        )
        Text(
            text = "${ReviewDiff.basename(target.file)}:${target.line}",
            color = c.textFaint,
            fontFamily = OcTheme.monoFont,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
        BasicTextField(
            value = value,
            onValueChange = { value = it },
            textStyle = TextStyle(color = c.textBase, fontSize = 13.sp, lineHeight = 20.sp),
            cursorBrush = SolidColor(c.textAccent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = { if (trimmed.isNotEmpty()) onSave(trimmed) },
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .clip(OcTheme.shapes.md)
                .background(c.layer02)
                .border(0.5.dp, c.borderBase, OcTheme.shapes.md)
                .focusRequester(focusRequester)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            decorationBox = { inner ->
                if (value.text.isEmpty()) {
                    Text(
                        text = stringResource(R.string.oc_review_comment_hint),
                        color = c.textFaint,
                        fontSize = 13.sp,
                    )
                }
                inner()
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            CommentModalButton(
                label = stringResource(R.string.oc_common_cancel),
                onClick = onDismiss,
            )
            Spacer(Modifier.width(8.dp))
            CommentModalButton(
                label = stringResource(R.string.oc_common_save),
                enabled = trimmed.isNotEmpty(),
                onClick = { onSave(trimmed) },
            )
        }
    }
}

/** Centered card on a tap-through-blocking scrim (same idiom as `SessionRowDialogs`). */
@Composable
private fun CommentModalCard(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = OcTheme.colors
    val shape = OcTheme.shapes.xl
    val scrim = rememberDialogScrimState()
    Box(modifier = Modifier.fillMaxSize()) {
        DialogScrim(scrim, onDismiss, color = MobileColors.scrim)
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 32.dp)
                .fillMaxWidth()
                .widthIn(max = 320.dp)
                .clip(shape)
                .background(c.bgBase)
                .border(0.5.dp, c.borderBase, shape)
                .ocShadow(OcTheme.elevation.floating, shape)
                .dialogCardBounds(scrim)
                .padding(16.dp),
            content = content,
        )
    }
}

/** `button-v2` metrics of the dock trays: h 28, radius 6, padding-x 12, 13/530. */
@Composable
private fun CommentModalButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val c = OcTheme.colors
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.5f)
            .height(28.dp)
            .clip(shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = c.textBase,
            fontSize = 13.sp,
            fontWeight = FontWeight(530),
            lineHeight = 20.sp,
        )
    }
}

/** Builds the line body with the word-level ranges emphasised. */
@Composable
private fun rememberHighlight(row: DiffRenderRow, c: OcColors): AnnotatedString {
    val background = emphasisBackground(row.kind, c)
    return remember(row, background) { buildHighlight(row.content, row.changed, background) }
}

/** Approximate `word-alt` emphasis: bold + a tint on the changed character range. */
private fun buildHighlight(content: String, ranges: List<IntRange>, background: Color): AnnotatedString =
    buildAnnotatedString {
        if (ranges.isEmpty() || content.isEmpty()) {
            append(content)
            return@buildAnnotatedString
        }
        var cursor = 0
        ranges.forEach { range ->
            val start = range.first.coerceIn(cursor, content.length)
            val end = (range.last + 1).coerceIn(start, content.length)
            if (start > cursor) append(content.substring(cursor, start))
            if (end > start) {
                withStyle(SpanStyle(fontWeight = FontWeight(600), background = background)) {
                    append(content.substring(start, end))
                }
            }
            cursor = end
        }
        if (cursor < content.length) append(content.substring(cursor))
    }

/** Colour rule (W4-B): the `+`/`-` state is carried by the background only. */
private fun diffForeground(kind: DiffLineKind, c: OcColors): Color = when (kind) {
    DiffLineKind.ADDITION, DiffLineKind.DELETION -> c.textBase
    DiffLineKind.HUNK, DiffLineKind.FILE_HEADER, DiffLineKind.META -> c.textFaint
    DiffLineKind.CONTEXT -> c.textMuted
}

private fun diffBackground(kind: DiffLineKind, c: OcColors): Color = when (kind) {
    DiffLineKind.ADDITION -> c.stateSuccessBg
    DiffLineKind.DELETION -> c.stateDangerBg
    DiffLineKind.HUNK -> c.layer01
    DiffLineKind.FILE_HEADER, DiffLineKind.META, DiffLineKind.CONTEXT -> Color.Transparent
}

/** `diffIndicators: "bars"` — the 2 dp bar in the add/delete accent. */
private fun changeBar(kind: DiffLineKind, c: OcColors): Color = when (kind) {
    DiffLineKind.ADDITION -> c.stateSuccessFg
    DiffLineKind.DELETION -> c.stateDangerFg
    else -> Color.Transparent
}

/** Word-level emphasis tint, slightly stronger than the line background. */
private fun emphasisBackground(kind: DiffLineKind, c: OcColors): Color = when (kind) {
    DiffLineKind.ADDITION -> c.stateSuccessBorder.copy(alpha = 0.45f)
    DiffLineKind.DELETION -> c.stateDangerBorder.copy(alpha = 0.45f)
    else -> Color.Transparent
}

/** `--diffs-line-height: 24px` (`pierre/index.ts:224`), the fixed row height. */
private val DIFF_ROW_HEIGHT = 24.dp

/** `--diffs-min-number-column-width: 3ch` ≈ 3 × 7.8 dp of 13 sp mono + padding. */
private val NUMBER_COLUMN_WIDTH = 24.dp

/** The 2 px gutter bar of `diffIndicators: "bars"`. */
private val CHANGE_BAR_WIDTH = 2.dp

/** 13 sp text on the 24 dp row. */
private val DIFF_LINE_HEIGHT = 24.sp
