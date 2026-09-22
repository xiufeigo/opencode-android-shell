package com.xiufeigo.opencodeshell.ui.session.review

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import kotlinx.coroutines.delay

/** Review side panel tabs, upstream `SessionReviewV2` sidebar modes. */
enum class ReviewPanelTab { CHANGES, CONTEXT, FILES }

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
 *   `session-review-empty-no-git-v2.tsx`.
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
 * @param hasVcs project VCS flag (`project.vcs`); null = unknown, treated as
 *   "a repo" so the panel does not claim no-git before `GET /vcs` answered.
 * @param defaultBranch `vcs.branch.default`, needed before the Branch scope is
 *   offered (`review/model.ts:59-66`).
 * @param vcsDiffs diffs of the Git / Branch scopes.
 * @param onRequestVcsDiff asks the host for the VCS diff of a scope; while it
 *   is null the two VCS scopes stay disabled (see `research/wave5_review.md`).
 * @param onInitGit "Create Git repository" action; null keeps the button
 *   disabled (nothing creates a repository yet).
 * @param contextSnapshot B2 projection of the context tab
 *   ([com.xiufeigo.opencodeshell.ui.session.context.SessionContextModel]);
 *   `null` renders the sixteen rows with every value `—`, so a host that has not
 *   loaded the payloads yet still shows the tab. The panel never fetches it:
 *   the screen owns that one-shot read.
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
    onInitGit: (() -> Unit)? = null,
    gitPending: Boolean = false,
    contextSnapshot: SessionContextModel.Snapshot? = null,
) {
    val c = OcTheme.colors

    // ---------------------------------------------------------- width (C1)
    // Hoisted *into* the panel: upstream owns the sidebar width itself
    // (`session-review-v2.tsx:82 style={{ width }}`) and clamps every resize.
    // `rememberSaveable` keeps it across configuration changes and process
    // recreation; the key in `SessionScreen` already scopes it per session.
    var widthDp by rememberSaveable { mutableStateOf(ReviewPanelWidth.restore(initialWidth.value)) }

    // --------------------------------------------------------- scope (C4)
    var scopeName by rememberSaveable { mutableStateOf(ReviewChangeScope.LAST_TURN.name) }
    val scope = ReviewChangeScope.values().firstOrNull { it.name == scopeName } ?: ReviewChangeScope.LAST_TURN
    val vcsWired = onRequestVcsDiff != null
    val available = remember(hasVcs, branch, defaultBranch) {
        ReviewScopes.available(hasVcs = hasVcs ?: true, currentBranch = branch, defaultBranch = defaultBranch)
    }
    val visibleDiffs = remember(scope, diffs, vcsDiffs) { ReviewScopes.diffsFor(scope, diffs, vcsDiffs) }
    val requestVcsDiff = rememberUpdatedState(onRequestVcsDiff)
    LaunchedEffect(scope) {
        if (scope == ReviewChangeScope.LAST_TURN) return@LaunchedEffect
        requestVcsDiff.value?.invoke(scope)
    }

    // --------------------------------------------------------- no git (C5)
    // `GET /vcs` is asynchronous: a null branch right after the panel opens is
    // "unknown", not "no repository". The verdict is only drawn once the
    // request had a chance to answer, and it is dropped again as soon as either
    // signal arrives. A host that already knows (`hasVcs = false`) skips the wait.
    var noGitSettled by remember { mutableStateOf(false) }
    LaunchedEffect(branch, hasVcs, diffs.isEmpty()) {
        if (hasVcs == false) {
            noGitSettled = true
            return@LaunchedEffect
        }
        if (branch != null || diffs.isNotEmpty() || hasVcs == true) {
            noGitSettled = false
            return@LaunchedEffect
        }
        delay(NO_GIT_SETTLE_MS)
        noGitSettled = branch == null && diffs.isEmpty()
    }
    val noGit = noGitSettled && visibleDiffs.isEmpty()

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
                    .padding(start = 12.dp, end = 4.dp),
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
                ReviewTabButton(
                    label = stringResource(R.string.oc_review_tab_changes),
                    selected = tab == ReviewPanelTab.CHANGES,
                    onClick = { onTabChange(ReviewPanelTab.CHANGES) },
                    modifier = Modifier.weight(1f),
                )
                ReviewTabButton(
                    label = stringResource(R.string.oc_review_tab_context),
                    selected = tab == ReviewPanelTab.CONTEXT,
                    onClick = { onTabChange(ReviewPanelTab.CONTEXT) },
                    modifier = Modifier.weight(1f),
                )
                ReviewTabButton(
                    label = stringResource(R.string.oc_review_tab_files),
                    selected = tab == ReviewPanelTab.FILES,
                    onClick = { onTabChange(ReviewPanelTab.FILES) },
                    modifier = Modifier.weight(1f),
                )
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

/** Tab strip entry: 13/500 label with a 2 dp accent underline while active. */
@Composable
private fun ReviewTabButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    Column(
        modifier = modifier
            .clip(OcTheme.shapes.sm)
            .clickable(role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            color = if (selected) c.textBase else c.textMuted,
            fontSize = 13.sp,
            fontWeight = FontWeight.W500,
            maxLines = 1,
            modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
        )
        Box(
            Modifier
                .width(24.dp)
                .height(2.dp)
                .background(if (selected) c.accent else androidx.compose.ui.graphics.Color.Transparent),
        )
    }
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
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
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

/** How long `GET /vcs` gets to answer before the panel claims "no git". */
private const val NO_GIT_SETTLE_MS = 700L

/** Dim factor of a chip whose data source is not wired yet. */
private const val DISABLED_ALPHA = 0.4f
