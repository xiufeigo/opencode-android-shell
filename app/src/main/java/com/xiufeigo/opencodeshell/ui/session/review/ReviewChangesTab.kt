package com.xiufeigo.opencodeshell.ui.session.review

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.OpenCodeClient
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.theme.OcColors
import com.xiufeigo.opencodeshell.ui.theme.OcMotion
import com.xiufeigo.opencodeshell.ui.theme.OcTheme

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
 */
@Composable
fun ReviewChangesTab(
    diffs: List<OpenCodeClient.FileDiff>,
    modifier: Modifier = Modifier,
    noGit: Boolean = false,
    onInitGit: (() -> Unit)? = null,
    gitPending: Boolean = false,
    scope: ReviewChangeScope = ReviewChangeScope.LAST_TURN,
) {
    // Row expansion survives list recycling, like the timeline's `toolOpen`.
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    var diffStyle by rememberSaveable { mutableStateOf(ReviewDiffStyle.UNIFIED) }
    var expandMode by rememberSaveable { mutableStateOf(ReviewExpandMode.EXPAND) }
    // One horizontal offset shared by every patch row, so the diff scrolls as
    // one block even though each row owns its own `horizontalScroll`.
    val horizontal = rememberScrollState()

    if (noGit) {
        NoGitEmpty(onInitGit = onInitGit, pending = gitPending, modifier = modifier)
        return
    }
    if (diffs.isEmpty()) {
        EmptyHint(text = stringResource(ReviewScopes.emptyRes(scope)), modifier = modifier)
        return
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

    LazyColumn(modifier = modifier) {
        item(key = "toolbar") {
            DiffToolbar(
                diffStyle = diffStyle,
                expandMode = expandMode,
                onDiffStyleChange = { diffStyle = it },
                onExpandModeChange = { expandMode = it },
            )
        }
        item(key = "summary") {
            // `DiffChanges` summary: totals across the whole scope diff.
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
        entries.forEach { (file, diff) ->
            val open = expanded[file] == true
            item(key = "file:$file") {
                DiffFileRow(diff = diff, open = open, onClick = { expanded[file] = !open })
            }
            if (!open) return@forEach
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
                    is PatchRow.Line -> DiffLineRow(row = row.row, horizontal = horizontal, modifier = itemModifier)
                    is PatchRow.Columns -> SplitLineRow(cells = row, modifier = itemModifier)
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
 */
@Composable
private fun DiffLineRow(
    row: DiffRenderRow,
    horizontal: ScrollState,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    val annotated = rememberHighlight(row, c)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(DIFF_ROW_HEIGHT)
            .background(diffBackground(row.kind, c)),
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
        SplitCell(row = cells.left, side = SplitSide.OLD, modifier = Modifier.weight(1f).fillMaxHeight())
        Box(Modifier.width(0.5.dp).fillMaxHeight().background(c.borderMuted))
        SplitCell(row = cells.right, side = SplitSide.NEW, modifier = Modifier.weight(1f).fillMaxHeight())
    }
}

/** Which number a split column shows. */
private enum class SplitSide { OLD, NEW }

@Composable
private fun SplitCell(
    row: DiffRenderRow?,
    side: SplitSide,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    if (row == null) {
        // The pairing partner is missing (unbalanced add/delete run).
        Box(modifier.background(c.layer01))
        return
    }
    val annotated = rememberHighlight(row, c)
    Row(
        modifier = modifier.background(diffBackground(row.kind, c)),
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
 * the "Create Git repository" button. The button has no action yet
 * (`onInitGit == null`) so it stays disabled; wiring it means calling
 * `POST /vcs/init` from the host.
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
