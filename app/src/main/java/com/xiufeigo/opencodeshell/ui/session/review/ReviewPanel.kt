package com.xiufeigo.opencodeshell.ui.session.review

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.OpenCodeClient
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.session.context.SessionContextModel
import com.xiufeigo.opencodeshell.ui.session.context.SessionContextPanel
import com.xiufeigo.opencodeshell.ui.session.header.ContextRing
import com.xiufeigo.opencodeshell.ui.session.header.ContextUsage
import com.xiufeigo.opencodeshell.ui.theme.OcTheme

/** Review side panel tabs, upstream `SessionReviewV2` sidebar modes. */
enum class ReviewPanelTab { CHANGES, CONTEXT, FILES }

/**
 * The tab strip the panel renders, upstream's own split
 * (`_upstream/packages/app/src/session/files/session-side-panel.tsx:333-374`):
 * a **fixed pair** -- `review` (`:333-343`) then `context` (`:344-374`) -- and
 * then `<For each={panelTabs()}>` (`:375-386`), the file tabs that exist only
 * because a file has been opened.
 *
 * v161: this shell used to show all three at all times, which put a third
 * always-on trigger in a band upstream keeps to two (and made the `file.open`
 * target look like a mode rather than an opened file). The file tab is now
 * **dynamic** like upstream's, keyed on the [openPath] the hoisted
 * `file.open` command carries -- so the default strip is exactly two tabs, and
 * `file.open` still lands on a file tab that is actually there.
 *
 * Pure and JVM-tested on purpose ([com.xiufeigo.opencodeshell
 * .RightPanelParityTest]): a tab strip is the one place where "which tabs
 * exist" and "which tab is selected" have to be separable, and the selected
 * tab is hoisted (`SessionScreen.kt:420`) while this list is not.
 */
internal fun visibleReviewTabs(openPath: String?): List<ReviewPanelTab> =
    if (openPath.isNullOrBlank()) {
        listOf(ReviewPanelTab.CHANGES, ReviewPanelTab.CONTEXT)
    } else {
        listOf(ReviewPanelTab.CHANGES, ReviewPanelTab.CONTEXT, ReviewPanelTab.FILES)
    }

/** This shell's labels for the upstream tab order; no new strings are introduced. */
private fun ReviewPanelTab.labelRes(): Int = when (this) {
    ReviewPanelTab.CHANGES -> R.string.oc_review_tab_changes
    ReviewPanelTab.CONTEXT -> R.string.oc_review_tab_context
    ReviewPanelTab.FILES -> R.string.oc_review_tab_files
}

/**
 * Review (changes) side panel, the Android port of the upstream v2 right-hand
 * panel (`_upstream/packages/app/src/session/review/panel.tsx` +
 * `session-ui/v2/session-review-v2`): a resizable panel docked at the end of
 * the session screen with a 「变更 / 上下文 / 文件」 tab strip.
 *
 * - **变更 (Changes)**: the session's `Snapshot.FileDiff[]` (REST
 *   `GET /session/:id/diff` + SSE `session.diff`), rendered by
 *   [ReviewChangesTab] with per-file expandable patch hunks.
 * - **上下文 (Context)**: the session context tab (B2) — the sixteen stat rows,
 *   the system prompt and the raw messages, upstream `session.tab.context`
 *   (`session-side-panel.tsx:345-373`) → `SessionContextTab`, rendered by
 *   [com.xiufeigo.opencodeshell.ui.session.context.SessionContextPanel]. Upstream
 *   orders it between the review and file tabs.
 * - **文件 (Files)**: the project file tree (`GET /file?path=`, lazily loaded
 *   per directory) with a text preview (`GET /file/content?path=`), rendered
 *   by [ReviewFilesTab].
 *
 * The panel is a pure view: data pulls are delegated through the callbacks so
 * the composable stays previewable and free of `ChatViewModel` coupling.
 *
 * **W5-R additions (all parameters optional, so every existing call site keeps
 * compiling unchanged):**
 * - the panel owns its **width** ([initialWidth] only seeds it) with a drag
 *   handle and a double-tap reset — upstream `session-review-v2.tsx:135-146`;
 * - the header carries the **Git / Branch / Last-turn scope switch**
 *   ([hasVcs], [defaultBranch], [vcsDiffs], [onRequestVcsDiff]) — upstream
 *   `review/view.tsx:228-247`;
 * - the **no-git empty state** ([onInitGit]) — upstream
 *   `session-review-empty-no-git-v2.tsx`. It is gated on a *known* project
 *   verdict ([ReviewScopes.noGit]: the host's [hasVcs], else
 *   `rememberReviewProjectVcs`'s `GET /api/project` read); a `/vcs` read that
 *   answered nothing is "unknown", not "no repository".
 *
 * @param diffs current session diff from `ChatStore.ChatState.diff`
 *   (`GET /session/:id/diff`; the "last turn" scope).
 * @param branch vcs branch shown in the header when known (`GET /vcs`).
 * @param rootPath workspace root the file tree lists first; null resolves via
 *   [onResolveRoot] (session without a known directory).
 * @param initialWidth width (dp) the panel starts at. Defaults to the legacy
 *   340 dp so the current call site is unchanged; once the caller stops pinning
 *   the width in its own modifier, pass
 *   `ReviewPanelWidth.DEFAULT.dp` (240) for the upstream default.
 * @param hasVcs project VCS flag (`project.vcs`); null = unknown, and then
 *   `GET /api/project` decides (unknown still counts as "a repo" so the panel
 *   never claims no-git on a missing answer).
 * @param defaultBranch `vcs.branch.default`, needed before the Branch scope is
 *   offered (`review/model.ts:59-66`).
 * @param vcsDiffs diffs of the Git / Branch scopes.
 * @param onRequestVcsDiff asks the host for the VCS diff of a scope; while it
 *   is null the two VCS scopes stay disabled (see `research/wave5_review.md`).
 *   The failure body's retry uses the same callback — one request seam.
 * @param vcsDiffFailed the host's last VCS-scope pull timed out or failed
 *   (`ReviewVcsDiffResult.Failed`). Rendered as the failure body instead of the
 *   「no uncommitted changes」 copy, so a read that never came back is not
 *   reported as an empty repository (see `ReviewScopes.changesBody`).
 * @param vcsDiffPending the host's VCS-scope pull is still out. The body then
 *   waits rather than claiming 「no changes」 — the third of the three states
 *   the empty copy used to swallow.
 * @param onInitGit "Create Git repository" action; null keeps the button
 *   disabled (the host wires `ReviewGitInit` — `git init` over
 *   `POST /api/shell`, then the location evict).
 * @param contextSnapshot B2 projection of the context tab
 *   ([com.xiufeigo.opencodeshell.ui.session.context.SessionContextModel]);
 *   `null` renders the sixteen rows with every value `—`, so a host that has not
 *   loaded the payloads yet still shows the tab. The panel never fetches it:
 *   the screen owns that one-shot read.
 * @param commentStore F-2 line-comment store (hoisted by the host, keyed by
 *   session), forwarded to [ReviewChangesTab]. Null disables the comment
 *   affordances.
 * @param onQuoteLine F-2 S-step: tap-a-diff-line → composer text, forwarded to
 *   [ReviewChangesTab]. The host routes it to `ChatViewModel.appendComposerText`.
 * @param onSendComments F-2: per-file aggregated comment notes → composer
 *   text, forwarded to [ReviewChangesTab].
 * @param onRefreshVcs 「刷新」: the host evicts the directory's cached location
 *   and re-reads its own review payloads (see
 *   [com.xiufeigo.opencodeshell.ChatViewModel.reviewReprobeLocation]). Null
 *   (every call site that has not wired it) hides the action. **Only the user
 *   click reaches it** — the evict cancels in-flight permissions/forms of that
 *   location, so it is never automatic, and the copy says so
 *   (`oc_review_refresh_done`).
 * @param reprobeKey bumped by the host once that refresh settled. Changing it
 *   re-reads the two self-serve probes below (`GET /api/project`, `/api/vcs/base`)
 *   and re-requests the active scope's diff, and clears the button's busy state.
 */
@Composable
fun SessionReviewPanel(
    diffs: List<OpenCodeClient.FileDiff>,
    branch: String?,
    rootPath: String?,
    onLoadChildren: (String, (List<OpenCodeClient.FileNode>?) -> Unit) -> Unit,
    onLoadFile: (String, (OpenCodeClient.FileContent?) -> Unit) -> Unit,
    onResolveRoot: ((String?) -> Unit) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Selected tab — **hoisted** so the `file.open` command can switch the panel
     * to Files from outside (`ui/palette/AppCommands.kt`).
     */
    tab: ReviewPanelTab = ReviewPanelTab.CHANGES,
    onTabChange: (ReviewPanelTab) -> Unit = {},
    /** Forwarded to [ReviewFilesTab.openPath] (`file.open`). */
    openPath: String? = null,
    initialWidth: Dp = LEGACY_PANEL_WIDTH,
    hasVcs: Boolean? = null,
    defaultBranch: String? = null,
    vcsDiffs: List<OpenCodeClient.FileDiff> = emptyList(),
    onRequestVcsDiff: ((ReviewChangeScope) -> Unit)? = null,
    vcsDiffFailed: Boolean = false,
    vcsDiffPending: Boolean = false,
    onInitGit: (() -> Unit)? = null,
    gitPending: Boolean = false,
    onRefreshVcs: (() -> Unit)? = null,
    reprobeKey: Int = 0,
    contextSnapshot: SessionContextModel.Snapshot? = null,
    commentStore: ReviewCommentStore? = null,
    onQuoteLine: ((String) -> Unit)? = null,
    onSendComments: ((String) -> Unit)? = null,
) {
    val c = OcTheme.colors

    // ---------------------------------------------------------- width (C1)
    // Hoisted *into* the panel: upstream owns the sidebar width itself
    // (`session-review-v2.tsx:82 style={{ width }}`) and clamps every resize.
    // `rememberSaveable` keeps it across configuration changes and process
    // recreation; the key in `SessionScreen` already scopes it per session.
    var widthDp by rememberSaveable { mutableStateOf(ReviewPanelWidth.restore(initialWidth.value)) }

    // --------------------------------------------------------- scope (C4)
    // Upstream opens on `"git"` (`model.ts:40 mode() ?? "git"`) and rewrites the
    // mode only when the switch does not offer it (`model.ts:341-350`); the
    // session snapshot is the extra scope this port adds, not the default.
    var scopeName by rememberSaveable { mutableStateOf(ReviewChangeScope.GIT.name) }
    val scope = ReviewChangeScope.values().firstOrNull { it.name == scopeName } ?: ReviewChangeScope.GIT
    val vcsWired = onRequestVcsDiff != null

    // ------------------------------------------------------- vcs verdict (C5)
    // Upstream asks the *project* (`project().vcs`, `view.tsx:252`) — a known
    // fact, never a failed `/vcs` read. The host's flag is authoritative when it
    // has one (`hasVcs = false` for a project without a repository); otherwise a
    // fresh `GET /api/project` read answers, because the location-scoped `/vcs`
    // read cannot: it reports "no provider" both for a directory without a
    // repository and for a location graph built before `git init`
    // (`ReviewVcsReprobe`).
    val projectVcs = rememberReviewProjectVcs(rootPath, reprobeKey)
    val vcsKnown = hasVcs ?: projectVcs
    val available = remember(vcsKnown, branch, defaultBranch) {
        ReviewScopes.available(
            hasVcs = vcsKnown ?: true,
            currentBranch = branch,
            defaultBranch = defaultBranch,
        )
    }
    val visibleDiffs = remember(scope, diffs, vcsDiffs) { ReviewScopes.diffsFor(scope, diffs, vcsDiffs) }
    val requestVcsDiff = rememberUpdatedState(onRequestVcsDiff)
    LaunchedEffect(scope, reprobeKey) {
        if (scope == ReviewChangeScope.LAST_TURN) return@LaunchedEffect
        requestVcsDiff.value?.invoke(scope)
    }
    // The switch must follow the project: a scope that is no longer offered
    // falls back to the first one that is (upstream `model.ts:341-350`).
    LaunchedEffect(available) {
        if (available.isNotEmpty() && scope !in available) scopeName = available.first().name
    }

    val noGit = ReviewScopes.noGit(hasVcs, projectVcs, visibleDiffs.isNotEmpty())

    // Diff base line (`GET /api/vcs/base` → `name@ref`, `ReviewVcsBase`):
    // fetched per panel mount and shown on the changes tab only.
    val vcsBase = rememberVcsBase(rootPath, reprobeKey)
    val vcsBaseLabel = ReviewVcsBase.label(vcsBase)

    // 「刷新」 busy state. The host owns the evict and bumps [reprobeKey] once it
    // settled (success or failure), which is also the signal that clears this —
    // the panel never decides on its own that the refresh is over.
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(reprobeKey) { refreshing = false }

    Row(
        modifier = Modifier
            .width(widthDp.dp)
            .fillMaxHeight()
            // The caller's width modifier (the legacy 340 dp) is coerced into
            // this one, so the drag applies even before the call site is
            // updated — see `research/wave5_review.md` §C1.
            .then(modifier),
    ) {
        PanelResizeHandle(
            onDelta = { deltaX -> widthDp = ReviewPanelWidth.resize(widthDp, deltaX) },
            onReset = { widthDp = ReviewPanelWidth.reset() },
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(c.bgBase)
                // The timeline side of the panel gets a 1 px hairline, same as the
                // upstream sidebar's `border-inline-start`.
                .border(0.5.dp, c.borderMuted, OcTheme.shapes.xs),
        ) {
            // ---------------------------------------------------------- header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .padding(start = 12.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.oc_review_title),
                    color = c.textBase,
                    fontSize = 13.sp,
                    fontWeight = FontWeight(530),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!branch.isNullOrEmpty()) {
                    // vcs branch chip: mono, muted, on layer02 — mirrors the
                    // upstream header's repo/branch meta label.
                    Text(
                        text = branch,
                        color = c.textMuted,
                        fontFamily = OcTheme.monoFont,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .widthIn(max = 140.dp)
                            .clip(OcTheme.shapes.sm)
                            .background(c.layer02)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                // 「刷新」 (evict this directory's cached location + re-probe):
                // only shown when the host wired it, and only ever on this tap.
                if (onRefreshVcs != null) {
                    ReviewRefreshAction(
                        pending = refreshing,
                        onClick = {
                            if (!refreshing) {
                                refreshing = true
                                onRefreshVcs()
                            }
                        },
                    )
                }
                PanelIconButton(
                    iconRes = R.drawable.ic_oc_close,
                    contentDescription = stringResource(R.string.oc_common_close),
                    onClick = onClose,
                )
            }

            // ----------------------------------------------------- scope switch
            // Upstream puts the change-mode `Select` in the review toolbar
            // (`review/view.tsx:228-247`); Android shows the three modes as a
            // segmented row under the panel title. It belongs to the review tab,
            // so the B2 context tab (which has no change scope) hides it — the
            // 变更 / 文件 rows keep rendering exactly as before.
            if (tab != ReviewPanelTab.CONTEXT) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ReviewChangeScope.values().forEach { option ->
                        val enabled = ReviewScopes.enabled(option, available, vcsWired)
                        ReviewChip(
                            label = stringResource(ReviewScopes.labelRes(option)),
                            selected = scope == option,
                            enabled = enabled,
                            onClick = { scopeName = option.name },
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                }
            }

            // -------------------------------------------------------------- tabs
            // Upstream tab order: review → context → file tabs
            // (`session-side-panel.tsx:345-373`).
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                visibleReviewTabs(openPath).forEach { entry ->
                    ReviewTabButton(
                        label = stringResource(entry.labelRes()),
                        selected = tab == entry,
                        onClick = { onTabChange(entry) },
                        // Upstream's context trigger carries its usage indicator in
                        // front of the label (`session-side-panel.tsx:369-372`).
                        leading = if (entry == ReviewPanelTab.CONTEXT) {
                            { ContextTabUsageIndicator(contextSnapshot) }
                        } else {
                            null
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.borderMuted))

            // ----------------------------------------------------------- content
            Box(modifier = Modifier.fillMaxSize()) {
                when (tab) {
                    ReviewPanelTab.FILES -> ReviewFilesTab(
                        rootPath = rootPath,
                        onLoadChildren = onLoadChildren,
                        onLoadFile = onLoadFile,
                        onResolveRoot = onResolveRoot,
                        modifier = Modifier.fillMaxSize(),
                        openPath = openPath,
                        // C5 badges: the tree marks the files the active scope changed.
                        diffs = visibleDiffs,
                        // U11: the panel's 「刷新」 settles by bumping
                        // `reprobeKey`; the file tree re-reads on the same
                        // signal, or the tap leaves the last listing — and the
                        // last filter's subset of it — on screen
                        // (`REPORT_SWEEPD2.md` §3.11).
                        refreshKey = reprobeKey,
                    )

                    ReviewPanelTab.CONTEXT -> SessionContextPanel(
                        snapshot = contextSnapshot,
                        modifier = Modifier.fillMaxSize(),
                    )

                    else -> ReviewChangesTab(
                        diffs = visibleDiffs,
                        modifier = Modifier.fillMaxSize(),
                        noGit = noGit,
                        onInitGit = onInitGit,
                        gitPending = gitPending,
                        scope = scope,
                        baseLabel = vcsBaseLabel,
                        loadFailed = vcsDiffFailed,
                        loadPending = vcsDiffPending,
                        // Retry = the same request the scope switch makes, so the
                        // panel needs no second seam into the host.
                        onRetryDiff = { requestVcsDiff.value?.invoke(scope) },
                        commentStore = commentStore,
                        onQuoteLine = onQuoteLine,
                        onSendComments = onSendComments,
                    )
                }
            }
        }
    }
}

/**
 * Drag handle of the panel's leading edge (upstream
 * `session-review-v2-sidebar-resize`, `session-review-v2.tsx:135-146` +
 * `session-review-v2.css:48-59`): `detectDragGestures` reports the raw x delta
 * (the panel clamps), and a double tap writes the upstream default width back
 * (`:143 onDblClick`).
 */
@Composable
private fun PanelResizeHandle(
    onDelta: (Float) -> Unit,
    onReset: () -> Unit,
) {
    val c = OcTheme.colors
    val description = stringResource(R.string.oc_review_resize_handle)
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(RESIZE_HANDLE_WIDTH)
            .background(c.bgBase)
            .semantics { contentDescription = description }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onReset() }) }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDelta(dragAmount.x)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // The visible 1 px grab line from `resize-handle::after`.
        Box(Modifier.width(1.dp).fillMaxHeight().background(c.borderBase))
    }
}

/**
 * 「刷新」 text action of the review chrome (`oc_review_refresh`). It evicts the
 * directory's cached location and re-probes the review reads, so it belongs to
 * the panel header (desktop) and to the mobile Changes header — both label it
 * from the same string and dim it while the host's evict is out.
 *
 * No icon: the review panel has no refresh glyph in `res/drawable`, and the
 * header already carries a text-sized branch chip; a text action keeps the
 * touch target legible at both widths.
 */
@Composable
internal fun ReviewRefreshAction(
    pending: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    Box(
        modifier = modifier
            .clip(OcTheme.shapes.sm)
            .alpha(if (pending) DISABLED_ALPHA else 1f)
            .clickable(enabled = !pending, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Text(
            text = stringResource(R.string.oc_review_refresh),
            color = c.textMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight(530),
            maxLines = 1,
        )
    }
}

/** Tab strip entry: 13/500 label with a 2 dp accent underline while active. */
@Composable
private fun ReviewTabButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Optional affordance drawn **in front of** the label, upstream's
     * `<div class="flex items-center gap-2">` wrapper
     * (`session-side-panel.tsx:369-372`). Only the context trigger passes one.
     */
    leading: (@Composable () -> Unit)? = null,
) {
    val c = OcTheme.colors
    Column(
        modifier = modifier
            .clip(OcTheme.shapes.sm)
            .clickable(role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val text: @Composable () -> Unit = {
            Text(
                text = label,
                color = if (selected) c.textBase else c.textMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight.W500,
                maxLines = 1,
            )
        }
        if (leading == null) {
            Box(Modifier.padding(top = 8.dp, bottom = 6.dp)) { text() }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
            ) {
                leading()
                text()
            }
        }
        Box(
            Modifier
                .width(24.dp)
                .height(2.dp)
                .background(if (selected) c.accent else androidx.compose.ui.graphics.Color.Transparent),
        )
    }
}

/**
 * The context tab's usage indicator, upstream's
 * `<SessionContextUsage variant="indicator" />`
 * (`session-side-panel.tsx:370`).
 *
 * Upstream draws [ContextRing] -- its own `ProgressCircleV2` at `size = 14`,
 * `strokeWidth = 1.5` (`session-context-usage.tsx`) -- so the shell reuses the
 * very same component the session header's ring is made of, at upstream's own
 * size, and paints the same number: the context tab's `Usage` cell, i.e.
 * upstream's `ctx()?.usage` (`session-context-tab.tsx:133`).
 *
 * Nothing is drawn while the usage is unknown (no cell, upstream's `DASH`
 * placeholder, or no limit at all) rather than an empty track: the tab stays a
 * plain label, which is exactly what upstream renders when `ctx()?.usage` is
 * `null` (`model.limit.context` missing -> `usage: null`, `:133`).
 */
@Composable
private fun ContextTabUsageIndicator(
    snapshot: SessionContextModel.Snapshot?,
    modifier: Modifier = Modifier,
) {
    val progress = ContextUsage.indicatorProgress(
        snapshot?.stats?.firstOrNull { it.kind == SessionContextModel.Kind.USAGE }?.value
    ) ?: return
    ContextRing(progress = progress, modifier = modifier)
}

/**
 * Small segmented-control chip shared by the scope switch (this file) and the
 * diff toolbar ([ReviewChangesTab]): `layer02` capsule, `layer03` while
 * selected, dimmed while disabled.
 */
@Composable
internal fun ReviewChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    Box(
        modifier = modifier
            .clip(OcTheme.shapes.sm)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .background(if (selected) c.layer03 else c.layer02.copy(alpha = 0.6f))
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = label,
            color = if (selected) c.textBase else c.textMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight(530),
            maxLines = 1,
        )
    }
}

/** 32 dp circular touch target around a 14 dp icon (panel chrome density). */
@Composable
internal fun PanelIconButton(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconModifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    Box(
        modifier = modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            // The description rides the clickable Box, not the `Icon` inside it.
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = c.iconBase,
            modifier = iconModifier.size(14.dp),
        )
    }
}

/**
 * Legacy panel width (`SessionScreen.kt` `REVIEW_PANEL_WIDTH`). It is the
 * default of [SessionReviewPanel]'s `initialWidth`, so the existing call site
 * renders exactly as before the drag work landed.
 */
private val LEGACY_PANEL_WIDTH = 340.dp

/** Touch width of the drag handle: a 1 dp line with a comfortable grab area. */
private val RESIZE_HANDLE_WIDTH = 14.dp

/** Dim factor of a chip whose data source is not wired yet. */
private const val DISABLED_ALPHA = 0.4f
