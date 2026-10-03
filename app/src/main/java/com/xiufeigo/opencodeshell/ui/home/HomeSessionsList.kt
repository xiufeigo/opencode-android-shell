@file:OptIn(ExperimentalFoundationApi::class)

package com.xiufeigo.opencodeshell.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import com.xiufeigo.opencodeshell.OpenCodeClient
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.components.OcKeyboard
import com.xiufeigo.opencodeshell.ui.components.SessionProgressIndicator
import com.xiufeigo.opencodeshell.ui.components.ocHitTargetAtLeast
import com.xiufeigo.opencodeshell.ui.components.ocMinTouchTarget
import com.xiufeigo.opencodeshell.ui.components.ocOverlayEscape
import com.xiufeigo.opencodeshell.ui.nav.SessionAvatarUnreadDot
import com.xiufeigo.opencodeshell.ui.session.composer.ComposerImeCommit
import com.xiufeigo.opencodeshell.ui.session.composer.HalfWidthText
import com.xiufeigo.opencodeshell.ui.session.drawer.SessionTimeLabel
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import com.xiufeigo.opencodeshell.ui.theme.ocFloatingShadow
import com.xiufeigo.opencodeshell.ui.theme.ocShadow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * WAVE 2.3b P1 (A2 #8) — the `home.sessions.search.focus` command (`mod+f`,
 * `search.ts:65-73`) as a Compose-side handle. The keybind itself lives in
 * App.kt's `GlobalKeybindFocus` (outside this wave's files); App.kt calls
 * [focus] when `mod+f` fires while Home is on screen, and
 * [HomeSessionsColumn] collects [requests] to focus the search field.
 */
class HomeSearchFocusRequest {
    private val _requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val requests: kotlinx.coroutines.flow.SharedFlow<Unit> = _requests

    /** App.kt's `mod+f` branch calls this; the field focuses on collection. */
    fun focus() {
        _requests.tryEmit(Unit)
    }
}

/**
 * Home's sessions column (`home-sessions-view.tsx`, `research/02` §1.3):
 * sticky search field, a floating "new session" ✎, the Today / Yesterday /
 * Older group list (sticky headers with the scroll fade), the skeleton and the
 * empty state.
 *
 * Verified geometry: search field `h-9` = 36 dp / radius 6 / `layer-02` at 60 %
 * opacity; session row `h-10` = 40 dp / radius 6 / `overlay-hover` on press;
 * group header `h-7` = 28 dp; the ✎ is absolutely positioned at
 * `right-0; top-[84px]` (`lg: top-[108px]`), i.e. on the first group header's
 * line — which is exactly where it lands here: the list starts `pt-3` (12 dp)
 * below the search block and the button is pinned to the list's top-right.
 *
 * The column renders whatever [rows] it is handed and owns no project state: the
 * selected-project scope arrives as already-filtered rows plus [searchScope] /
 * [showProjectName] ([HomeScreen] calls [HomeSessions.rows] with the selection).
 *
 * A-3: every row carries the `common.time.*` label of its `time.updated`, at the
 * palette's trailing cell ([PaletteRow]'s `command-palette-meta`), and both that
 * label and the Today / Yesterday / Older grouping are measured against the
 * caller's [now] — the host passes a value that ticks while Home is on screen
 * ([HomeScreen] drives it from a 30 s loop). A wall-clock read in here is
 * invisible to Compose: a row's only inputs are its own fields, so the label
 * composed while a session was「just now」would stay「just now」for as long as the
 * row lived (the drawer's `RelativeTimeText` froze exactly that way, and the
 * grouping would hold a session in Today past midnight).
 */
@Composable
internal fun HomeSessionsColumn(
    rows: List<HomeSessions.Row>,
    loading: Boolean,
    configMissing: Boolean,
    /**
     * The last catalogue read failed ([ChatViewModel.sessionsLoadFailed]). With
     * [rows] empty the column renders the load-error state instead of the empty
     * state, so a failed first read after a force-stop cannot sit on the
     * skeleton (or masquerade as "no sessions") with no way back.
     */
    loadFailed: Boolean = false,
    /**
     * Retry affordance of the load-error state. Null hides the button (a
     * preview / isolated host has no read to re-run).
     */
    onRetrySessions: (() -> Unit)? = null,
    /**
     * `home.sessions.search.placeholder.scoped`'s `{{scope}}` while a project is
     * selected, `null` for the unscoped field ([HomeSessions.placeholderScope]).
     */
    searchScope: String? = null,
    /**
     * `session.showProjectName` (`session/controller.tsx:256`): the per-row
     * project label is hidden while a project is selected, because every visible
     * row is then that project's.
     */
    showProjectName: Boolean = true,
    openSessionIds: Set<String>,
    onOpenSession: (String) -> Unit,
    onNewSession: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * A-2 (the v28 device run): the per-row dot and marquee, keyed by session id.
     * Upstream's Home rows carry `SessionTabAvatarView`
     * (`home/sessions/view.tsx:219-228`) — the same unread dot / 25-dot marquee
     * the tab strip and the session drawer draw — and this shell's rows were the
     * one session list without them, so a pending permission lit nothing on the
     * page the user was actually looking at.
     *
     * The host computes both from **one** `SessionDotSources` (`HomeScreen`), so
     * Home cannot answer differently from the strip or the drawer. Defaulted to
     * "nothing" so a preview / isolated host renders the rows it always did.
     */
    unread: (String) -> Boolean = { false },
    /** A-2: the same badge's `loading` half (`SessionTabIndicator.badge`). */
    running: (String) -> Boolean = { false },
    /** `pt-6` (24 dp) on mobile, `lg:pt-12` (48 dp) on desktop. */
    topInset: Dp = MOBILE_TOP_INSET,
    /**
     * WAVE 2.3b P1 (A2 #9): the **untruncated** session list the search filters
     * (`controller.tsx:253-254` `searchRecords: allRecords`). Defaults to the
     * visible rows (pre-search behaviour); hosts pass the full list so sessions
     * past the 64-row cap stay findable.
     */
    searchSessions: List<OpenCodeClient.Session> = emptyList(),
    /**
     * The catalogue's display names by match key: the search source's per-row
     * project labels print them (`records.ts:30`), so a renamed project reads
     * renamed in results too. Defaults to none (bare folder names).
     */
    projectNames: Map<String, String> = emptyMap(),
    /**
     * B-14: the project rows each session's avatar is attributed from
     * ([HomeProjects.forSession]) — the same list the 项目 column renders, so a
     * session row and its project row cannot disagree about the icon. Defaults
     * to none: a preview / isolated host then renders the neutral avatar it
     * always did, exactly as the [unread] / [running] defaults do.
     *
     * Needed here as well as on [rows] because the search source builds its own
     * rows out of the untruncated [searchSessions] ([HomeSessions.searchRows]).
     */
    projects: List<HomeProjects.Project> = emptyList(),
    /**
     * WAVE 2.3b P1 (A2 #4): row context-menu actions
     * (`view.tsx:694-701`). Null = the action is unavailable (e.g. no client)
     * and its menu item renders disabled.
     */
    onRenameSession: ((sessionId: String, title: String, onResult: (Boolean) -> Unit) -> Unit)? = null,
    onExportSession: ((HomeSessions.Row) -> Unit)? = null,
    onDeleteSession: ((HomeSessions.Row) -> Unit)? = null,
    /**
     * v40: the row menu's 「导入会话」 — the third entry point for the flow the
     * project row's ⋯ and the chat header's ⋮ already carry
     * (`rememberHomeSessionImport`, which `HomeScreen` passes in). The import
     * target is the row's own `directory`, i.e. the project the session belongs
     * to. Null (a preview, a host without a client) leaves the row **out** of the
     * menu rather than dead, the same gate the other two entries use
     * (`canImportSession`).
     */
    onImportSession: ((HomeSessions.Row) -> Unit)? = null,
    /** WAVE 2.3b P1 (A2 #8): App.kt's `mod+f` hook focuses this field on Home. */
    searchFocusRequest: HomeSearchFocusRequest = remember { HomeSearchFocusRequest() },
    /**
     * v45 P1: the floor this column's list viewport keeps, from
     * [HomeRowLayout.listMinHeight] at the host's card height. It is a
     * parameter because only the host knows the card: the v43/v44 fixed floor
     * (68 dp) is more than a 185 dp card can pay for once the search block and
     * the utility nav are paid too, and an over-budget floor does not keep the
     * editing row on screen — it overflows the column and hides the row under
     * the nav (`research/v45_verify/tC/v45_46/47`).
     */
    listMinHeight: Dp = HomeRowLayout.LIST_MIN_HEIGHT,
    /**
     * v54 P1: the list viewport's top step, the host's
     * [HomeRowLayout.listTopPadding] — 12 dp while the card affords it and 0
     * while it cannot, which is what leaves a short window's one row whole
     * instead of half under the card's edge
     * (`research/v54_verify/tE/v54_16…22`).
     */
    listTopPadding: Dp = HomeRowLayout.LIST_TOP_PAD,
    /**
     * A-3: the clock the rows' relative labels and the group headers are measured
     * against (see the class note). It is a parameter rather than a wall-clock
     * read inside this file so a host cannot freeze a label by accident, and so
     * the value can be driven by a tick the host owns.
     */
    now: Long,
    /**
     * v46 P2: reports whether a rename editor is open. The host yields the
     * mobile column's chrome (projects panel, utility nav) while one is, so the
     * editing row, its header and a sibling still fit a keyboard-up card.
     */
    onEditingChanged: (Boolean) -> Unit = {},
) {
    val c = OcTheme.colors
    var query by remember { mutableStateOf("") }
    // The injected clock, not `System.currentTimeMillis()`: a session created at
    // 23:59 belongs to Yesterday once the clock moves past midnight, and only a
    // value Compose can observe makes the header follow it.
    val sections = remember(rows, now) { HomeSessions.sections(rows, now) }
    // A2 #9: filter the full list, not the rendered 64 — plus the exact
    // session-ID fast path (`search.ts:86-101`), whose row bypasses the cap.
    val fullSearchRows = remember(searchSessions, rows, projectNames, projects) {
        HomeSessions.searchRows(
            searchSessions.ifEmpty { null },
            rows,
            projectNames = projectNames,
            projects = projects,
        )
    }
    val results = remember(fullSearchRows, query) { HomeSessions.filter(fullSearchRows, query) }
    val listState = rememberLazyListState()
    // `search.ts:45-53` picks the key, this picks the literal.
    val placeholder = if (searchScope == null) {
        stringResource(R.string.oc_home_search)
    } else {
        stringResource(R.string.oc_home_search_scoped, searchScope)
    }
    // A2 #8: `home.sessions.search.focus` — the field owns a focus requester
    // the global `mod+f` chord (App.kt) can fire through [searchFocusRequest].
    val searchFieldFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(searchFocusRequest, searchFieldFocus) {
        searchFocusRequest.requests.collect { searchFieldFocus.requestFocus() }
    }

    // The search overlay is Home's top-most layer, so system back dismisses it
    // before the nav graph gets a chance to pop (PLAN §3: 先关弹层，再返回).
    BackHandler(enabled = query.isNotEmpty()) { query = "" }

    // A2 #4: one menu + one rename editor open at a time, keyed by session id
    // at this root (`view.tsx:87-90`).
    var rowMenu by remember { mutableStateOf<HomeRowMenu.Menu?>(null) }
    var rowEditor by remember { mutableStateOf<HomeRowMenu.Editor?>(null) }
    // v46 P2 (see [onEditingChanged]): the host owns the chrome, this owns the
    // state — report the transition outside composition.
    val editingOpen = rowEditor != null
    LaunchedEffect(editingOpen) { onEditingChanged(editingOpen) }
    // v84 P1: the editor is keyed by session id at this root, so it outlives a
    // row that leaves the list (a `GONE` tombstone, a delete from another
    // surface, a scope change) — and a non-null editor is what keeps the host's
    // chrome yielded ([onEditingChanged]) for a row nobody can see. The rows'
    // membership signature is the trigger; an unchanged list is a no-op (the
    // pruned value is equal, so no recomposition is scheduled).
    val rowIds = remember(rows) { rows.mapTo(HashSet()) { it.id } }
    LaunchedEffect(rowIds) { rowEditor = HomeRowMenu.pruneEditor(rowEditor, rowIds) }

    Column(modifier) {
        // Sticky header block: `pt-6 pb-3` around the search field
        // (`lg:pt-12 pb-3`). It never scrolls away, which is why the list below
        // needs no pinned copy of the field.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = topInset, bottom = 12.dp),
        ) {
            HomeSearchField(
                query = query,
                onQueryChange = { query = it },
                placeholder = placeholder,
                focusRequester = searchFieldFocus,
                modifier = Modifier.fillMaxWidth(),
            )
            if (configMissing) {
                Text(
                    text = stringResource(R.string.oc_setup_hint),
                    color = c.textMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                // v43 P1: the rename editor lives inside this viewport, so a
                // viewport that measures to 0 dp is a touch dead end — the row
                // that opened it is gone, nothing scrolls, and a phone user has
                // no Esc (`research/v43_verify/tC/v43_G3/G8/G9/G6`). Keep the
                // floor one header + one row (one `Column` is a *hard* 0 dp when
                // the space above it is already gone, so the host's column is
                // what has to leave the room; see [HomeRowLayout.LIST_MIN_HEIGHT]).
                // v45 P1: the floor is the host's [listMinHeight] — a fixed 68 dp
                // on a card that cannot pay for it overflows the column and lets
                // the utility nav cover the row the floor exists to keep visible
                // (`research/v45_verify/tC/v45_46/47`).
                .heightIn(min = listMinHeight),
        ) {
            // The rows' own width picks the compact shape ([HomeRowLayout]): the
            // column *is* the row's width — nothing horizontal sits between them —
            // so one measurement here replaces a per-row one.
            val narrow = HomeRowLayout.narrow(maxWidth)
            // v45 P1: the same measurement decides the header's stickiness — a
            // sticky header is drawn over the rows, so a viewport that cannot
            // hold header + row can only ever show part of one
            // ([HomeRowLayout.groupHeaderSticky]).
            //
            // v46 P0 「宽屏改名输入框文字不可见」: while a rename editor is open
            // the headers give up stickiness at *every* viewport, not just a
            // short one. The editor opens scrolled to the viewport's top and a
            // pinned header is drawn over it — 28 dp of opaque `bgBase` over a
            // 40 dp row, so the draft sits underneath it with only a 12 dp stub
            // showing (`research/v46_verify/tC/v46_05/06/14`, uiautomator reads
            // the text off an invisible node). The narrow phone never hit this
            // because its keyboard-up viewport is already below the stickiness
            // floor and its headers are plain items (`v46_20`).
            val stickyHeader = HomeRowLayout.groupHeaderSticky(maxHeight) && rowEditor == null
            // v47 P0 「窄屏编辑态分组头不渲染」: the editor-open scroll lands on
            // the editing row's group header while the rows viewport fits
            // header + pad + row ([HomeRowLayout.editorScrollToHeader]) — the
            // header is a plain item while an editor is open, so landing on the
            // row itself parks the header just above the viewport
            // (`research/v47_verify/tC/v47_30/31`). The 12 dp top pad is the
            // list's own chrome, not rows, so it does not count.
            val landOnGroupHeader = HomeRowLayout.editorScrollToHeader(
                (maxHeight - listTopPadding).coerceAtLeast(0.dp),
            )
            // `session.showProjectName` and the width floor both hide the row's
            // project label; the row model still carries it either way.
            val showRowProject = showProjectName && HomeRowLayout.showProjectLabel(narrow)
            // `contentPadding pt-3` — one 12 dp step between the search block and
            // the first sticky group header. v54 P1: the step is the host's
            // ([listTopPadding]), because on a card that cannot also afford the
            // rows' floor it is the difference between a whole row and a sliced
            // one (`HomeRowLayout.listTopPadding`).
            Column(modifier = Modifier.fillMaxSize().padding(top = listTopPadding)) {
                when {
                    loading -> HomeSessionSkeleton(Modifier.fillMaxSize(), narrow)
                    HomeSessions.shouldShowLoadError(loading, loadFailed, rows.size) ->
                        HomeSessionsLoadError(
                            onRetry = onRetrySessions,
                            modifier = Modifier.fillMaxSize(),
                        )
                    rows.isEmpty() -> HomeSessionsEmpty(
                        onNewSession = onNewSession,
                        modifier = Modifier.fillMaxSize(),
                    )

                    else -> HomeSessionList(
                        sections = sections,
                        now = now,
                        listState = listState,
                        stickyHeader = stickyHeader,
                        landOnGroupHeader = landOnGroupHeader,
                        showProjectName = showRowProject,
                        // 极窄 rows take the compact shape (see [HomeRowLayout]).
                        narrow = narrow,
                        openSessionIds = openSessionIds,
                        onOpenSession = onOpenSession,
                        // A-2: the rows' dot / marquee inputs (see the class note).
                        unread = unread,
                        running = running,
                        rowMenu = rowMenu,
                        rowEditor = rowEditor,
                        onOpenMenu = { id -> rowMenu = HomeRowMenu.openMenu(rowMenu, id) },
                        onDismissMenu = { id -> rowMenu = HomeRowMenu.closeMenu(rowMenu, id) },
                        onStartRename = { row ->
                            rowMenu = HomeRowMenu.closeMenu(rowMenu, row.id)
                            rowEditor = HomeRowMenu.openEditor(row.title, row.id)
                        },
                        onRenameDraft = { id, draft -> rowEditor = HomeRowMenu.editDraft(rowEditor, id, draft) },
                        onRenameSubmit = { row ->
                            // v44 P0: the editor — not the reporting row — says
                            // which session is being renamed, and `row.title` is
                            // only the label the draft is compared against. The
                            // id used to be compared here too, and a mismatch
                            // returned `null` that this lambda dropped, so a
                            // commit gesture could be eaten with no request
                            // ever leaving the device (`research/v44_verify/tC/`
                            // v44_10/12/14/18).
                            val step = HomeRowMenu.submit(rowEditor, row.title) ?: return@HomeSessionList
                            val sessionId = step.first?.sessionId ?: return@HomeSessionList
                            rowEditor = step.first
                            when (val action = step.second) {
                                HomeRowMenu.Submit.Noop -> rowEditor = null
                                is HomeRowMenu.Submit.Save -> onRenameSession?.invoke(
                                    sessionId,
                                    action.title,
                                ) { saved ->
                                    // v84 P1: either answer ends the edit — the
                                    // row shows `row.title` again on a failure
                                    // (the catalogue's own label: the host mirrors
                                    // a rename only on success) and the new name
                                    // on a success. v83's rollback left the editor
                                    // open, which is the state the device run
                                    // could not leave — see
                                    // `HomeRowMenu.resolveSave`'s note.
                                    rowEditor = HomeRowMenu.resolveSave(
                                        rowEditor,
                                        sessionId,
                                        saved,
                                    )
                                }
                            }
                        },
                        onRenameCancel = { id -> rowEditor = HomeRowMenu.closeEditor(rowEditor, id) },
                        renameEnabled = onRenameSession != null,
                        exportEnabled = onExportSession != null,
                        deleteEnabled = onDeleteSession != null,
                        onExport = { row ->
                            rowMenu = HomeRowMenu.closeMenu(rowMenu, row.id)
                            onExportSession?.invoke(row)
                        },
                        // v40: 「导入会话」. The menu closes first: the SAF picker
                        // would otherwise open under it (the header ⋮ row's rule).
                        importEnabled = onImportSession != null,
                        onImport = { row ->
                            rowMenu = HomeRowMenu.closeMenu(rowMenu, row.id)
                            onImportSession?.invoke(row)
                        },
                        onDelete = { row ->
                            rowMenu = HomeRowMenu.closeMenu(rowMenu, row.id)
                            onDeleteSession?.invoke(row)
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // `data-action="home-new-session" variant="ghost-muted"` ✎
            FloatingNewSessionButton(
                onClick = onNewSession,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = NEW_SESSION_TOP),
            )

            if (query.isNotEmpty()) {
                HomeSessionSearchPanel(
                    query = query,
                    results = results,
                    showProjectName = showRowProject,
                    narrow = narrow,
                    now = now,
                    onPick = { row ->
                        query = ""
                        onOpenSession(row.id)
                    },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        // Inline style of the panel: `top:-6px; left:-6px`.
                        .offset(x = (-6).dp, y = (-6).dp)
                        .fillMaxWidth(),
                )
            }
        }
    }
}

/** `HomeSessionSearch` closed state: `h-9 rounded-[6px] bg-layer-02/60`. */
@Composable
private fun HomeSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
) {
    val c = OcTheme.colors
    Row(
        modifier = modifier
            .height(SEARCH_HEIGHT)
            .clip(OcTheme.shapes.md)
            .background(c.layer02.copy(alpha = 0.6f))
            .padding(start = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_oc_search),
            contentDescription = null,
            tint = c.iconMuted,
            modifier = Modifier.size(16.dp),
        )
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = TextStyle(color = c.textBase, fontSize = 13.sp),
            cursorBrush = SolidColor(c.textBase),
            modifier = Modifier.weight(1f).then(
                if (focusRequester == null) Modifier else Modifier.focusRequester(focusRequester),
            ),
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            text = placeholder,
                            color = c.textFaint,
                            fontSize = 13.sp,
                            maxLines = 1,
                            // A scoped placeholder can name a long project; at 极窄
                            // widths it ellipsizes instead of clipping mid-glyph.
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
            },
        )
        if (query.isNotEmpty()) {
            // The label rides the clickable Box, not the `Icon` below it — only
            // the node carrying ACTION_CLICK is announced as a button, and
            // Compose reads `contentDescription` from a node's own config.
            val clearLabel = stringResource(R.string.oc_home_clear_search)
            Box(
                modifier = Modifier
                    .size(ICON_BUTTON_SMALL)
                    .clip(OcTheme.shapes.sm)
                    .clickable(role = Role.Button, onClick = { onQueryChange("") })
                    .semantics { contentDescription = clearLabel },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_oc_close),
                    contentDescription = null,
                    tint = c.iconMuted,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

/**
 * The open-state search panel (`home-session-search-panel`): radius 12,
 * `bg-base`, `elevation-floating`, results capped at `max-h-80` = 320 dp,每行
 * `h-10` with `pl-[18px] pr-6`. Upstream ranks with fuzzysort and offsets the
 * panel by -6 px on all four sides; M3 filters by substring
 * ([HomeSessions.filter]) and only offsets the top-left (see the class doc).
 */
@Composable
private fun HomeSessionSearchPanel(
    query: String,
    results: List<HomeSessions.Row>,
    showProjectName: Boolean,
    /** 极窄 (below [HomeRowLayout.NARROW_WIDTH]): the rows take the compact shape. */
    narrow: Boolean = false,
    onPick: (HomeSessions.Row) -> Unit,
    modifier: Modifier = Modifier,
    /** A-3: the host's clock, so a result's relative time matches the list's. */
    now: Long,
) {
    val c = OcTheme.colors
    val shape = RoundedCornerShape(12.dp)
    val elevation = OcTheme.elevation.floating
    Column(
        modifier = modifier
            .ocShadow(elevation, shape)
            .clip(shape)
            .background(c.bgBase)
            // v97 fix (research/v97_fix/REVIEW_HOME_SEARCH_DIAG.md §N4, 派生缺陷):
            // the panel is a floating layer over a main list that is never
            // cleared by the query (`sections` derives from `rows` only), and it
            // shrinks to fit its content — so the empty state, which is header +
            // one line, left the session rows below it showing through. That read
            // as 「no sessions found」 next to a list of sessions
            // (`v97b_052_homesearch.png`). Upstream's
            // `home-session-search-panel` is `absolute` + `overflow-hidden` with
            // an opaque `bg-v2-background-bg-base` and covers the region it sits
            // on; a blank filter must cover too, not just a populated one. The
            // min height only applies to the empty state, so a real result list
            // keeps its exact content height.
            .heightIn(min = if (results.isEmpty()) PANEL_MIN_COVER_HEIGHT else 0.dp)
    ) {
        Text(
            text = stringResource(R.string.oc_home_search_group),
            color = c.textMuted,
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 18.dp, end = 24.dp, top = 6.dp, bottom = 6.dp),
        )
        if (results.isEmpty()) {
            Text(
                text = stringResource(R.string.oc_home_search_no_results, query.trim()),
                color = c.textMuted,
                fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = PANEL_MAX_HEIGHT)) {
                items(items = results, key = { "q:" + it.id }) { row ->
                    SearchResultRow(
                        row = row,
                        showProjectName = showProjectName,
                        now = now,
                        narrow = narrow,
                        onClick = { onPick(row) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(
    row: HomeSessions.Row,
    showProjectName: Boolean,
    now: Long,
    onClick: () -> Unit,
    narrow: Boolean = false,
) {
    val c = OcTheme.colors
    val project = if (showProjectName) row.project else null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(SEARCH_RESULT_HEIGHT)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 18.dp, end = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = row.title,
                color = c.textBase,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (project != null) {
                ProjectLabel(text = project, modifier = Modifier.weight(1f, fill = false))
            }
        }
        // A-3: the palette's `command-palette-meta` cell — a session result
        // carries the same relative time there (`sessionSearchEntries`' `meta`).
        // Same 极窄 cap as the list rows: this panel is the *narrowest* place a
        // session row is drawn (it is inset 18/24 dp on both sides).
        Box(modifier = Modifier.widthIn(max = HomeRowLayout.timeMax(narrow))) {
            SessionRowTime(updatedAt = row.updatedAt, now = now)
        }
    }
}

/**
 * The grouped session list. Header order is fixed
 * (Today → Yesterday → Older) and each group carries a trailing spacer:
 * `mb-6` = 24 dp between groups, `pb-16` = 64 dp under the last one.
 *
 * **Sticky + scroll fade**: upstream drives `titleOpacity` from
 * `home-scroll-controller`; here each header reads how far its own row has been
 * scrolled past (`firstVisibleItemIndex`/`firstVisibleItemScrollOffset`) and
 * fades over one header height. Compose's sticky header stays pinned, so the
 * title dims out just before the next group's header pushes it away.
 */
@Composable
private fun HomeSessionList(
    sections: List<HomeSessions.Section>,
    listState: LazyListState,
    /** v45 P1: false on a viewport too short to hold a header *and* a row. */
    stickyHeader: Boolean = true,
    /**
     * v47 P0 「窄屏编辑态分组头不渲染」: true lands the editor-open scroll on
     * the editing row's group header instead of the row itself (the caller
     * derives it from [HomeRowLayout.editorScrollToHeader]). The headers are
     * plain items while an editor is open, so landing on the row parks its
     * header just above the viewport.
     */
    landOnGroupHeader: Boolean = false,
    showProjectName: Boolean,
    /** 极窄 (below [HomeRowLayout.NARROW_WIDTH]): the rows take the compact shape. */
    narrow: Boolean = false,
    openSessionIds: Set<String>,
    onOpenSession: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** A-2: the rows' dot / marquee inputs, threaded down from the host. */
    unread: (String) -> Boolean = { false },
    running: (String) -> Boolean = { false },
    // WAVE 2.3b P1 (A2 #4): list-root menu + inline rename state.
    rowMenu: HomeRowMenu.Menu? = null,
    rowEditor: HomeRowMenu.Editor? = null,
    onOpenMenu: (String) -> Unit = {},
    onDismissMenu: (String) -> Unit = {},
    onStartRename: (HomeSessions.Row) -> Unit = {},
    onRenameDraft: (String, String) -> Unit = { _, _ -> },
    onRenameSubmit: (HomeSessions.Row) -> Unit = {},
    onRenameCancel: (String) -> Unit = {},
    renameEnabled: Boolean = false,
    exportEnabled: Boolean = false,
    /** v40: the menu's 「导入会话」 row, present only while a host is wired. */
    importEnabled: Boolean = false,
    deleteEnabled: Boolean = false,
    onExport: (HomeSessions.Row) -> Unit = {},
    onImport: (HomeSessions.Row) -> Unit = {},
    onDelete: (HomeSessions.Row) -> Unit = {},
    /** A-3: the host's clock, threaded down to each row's relative label. */
    now: Long,
) {
    // Item index of each group header in the LazyColumn below
    // (header + pt-4 spacer + rows + trailing spacer).
    val headerIndexes = remember(sections) {
        val map = mutableMapOf<HomeSessions.Group, Int>()
        var index = 0
        sections.forEach { section ->
            map[section.group] = index
            index += section.rows.size + SECTION_SLOTS
        }
        map
    }
    val fadeDistancePx = with(LocalDensity.current) { HEADER_HEIGHT.toPx() }

    // v44 P1: the editor replaces its row **in place**, and the row it replaced
    // can be off screen — the keyboard shrinks this viewport by a few hundred dp
    // and the 项目 panel above it takes its share, so on a short card the row the
    // user just tapped is no longer in view and the field being edited is
    // invisible with it (`research/v44_verify/tC/v44_33/34`). Opening an editor
    // therefore scrolls its own row back into the viewport; the identity is the
    // editor's own session id, which is the one key the list is built from.
    val editing = rowEditor?.sessionId
    // v46 P0: the scroll follows the membership signature, not the sections
    // instance — a content-identical recompute (the 30 s clock tick, the rename
    // commit's own title-only frame) must not yank the viewport while the user
    // reads a sibling or after a mutation settled ([homeSectionsSignature]).
    val sectionsSig = remember(sections) { homeSectionsSignature(sections) }
    LaunchedEffect(editing, sectionsSig) {
        if (editing != null) {
            // v47 P0: land on the group header while [landOnGroupHeader] — the
            // header is a plain item while an editor is open, so the row-top
            // landing parked it just above the viewport and the editing row
            // read as headerless. A viewport that cannot hold header + row
            // keeps the row-top landing: it can only ever show one of them.
            val rowIndex = homeSessionItemIndex(sections, editing)
            val target = if (landOnGroupHeader) {
                sections.firstOrNull { section -> section.rows.any { it.id == editing } }
                    ?.let { headerIndexes[it.group] } ?: rowIndex
            } else {
                rowIndex
            }
            target?.let { listState.animateScrollToItem(it) }
        }
    }

    LazyColumn(modifier = modifier, state = listState) {
        sections.forEachIndexed { position, section ->
            val headerIndex = headerIndexes[section.group] ?: 0
            if (stickyHeader) {
                stickyHeader(key = "group:${section.group.name}") {
                    val scrolled = if (listState.firstVisibleItemIndex == headerIndex) {
                        listState.firstVisibleItemScrollOffset
                    } else {
                        0
                    }
                    val opacity = (1f - scrolled / fadeDistancePx).coerceIn(MIN_HEADER_OPACITY, 1f)
                    HomeGroupHeader(group = section.group, opacity = opacity)
                }
            } else {
                item(key = "group:${section.group.name}") {
                    HomeGroupHeader(group = section.group, opacity = 1f)
                }
            }
            item(key = "group-pad:${section.group.name}") { Spacer(Modifier.height(HomeRowLayout.GROUP_PAD_HEIGHT)) }
            items(items = section.rows, key = { "session:" + it.id }) { row ->
                HomeSessionRow(
                    row = row,
                    open = row.id in openSessionIds,
                    showProjectName = showProjectName,
                    narrow = narrow,
                    now = now,
                    onClick = { onOpenSession(row.id) },
                    // A-2: one badge per row, from the host's single derivation.
                    unread = unread(row.id),
                    loading = running(row.id),
                    menuOpen = rowMenu?.sessionId == row.id,
                    onOpenMenu = { onOpenMenu(row.id) },
                    onDismissMenu = { onDismissMenu(row.id) },
                    editor = rowEditor?.takeIf { it.sessionId == row.id },
                    onStartRename = { onStartRename(row) },
                    onRenameDraft = { draft -> onRenameDraft(row.id, draft) },
                    onRenameSubmit = { onRenameSubmit(row) },
                    onRenameCancel = { onRenameCancel(row.id) },
                    renameEnabled = renameEnabled,
                    exportEnabled = exportEnabled,
                    importEnabled = importEnabled,
                    deleteEnabled = deleteEnabled,
                    onExport = { onExport(row) },
                    onImport = { onImport(row) },
                    onDelete = { onDelete(row) },
                    modifier = Modifier,
                )
            }
            item(key = "group-end:${section.group.name}") {
                Spacer(Modifier.height(if (position == sections.lastIndex) LIST_BOTTOM_GAP else 24.dp))
            }
        }
    }
}

/** `HomeSessionGroupHeader`: `h-7`, 13 px `text-muted`, fades while scrolling. */
@Composable
private fun HomeGroupHeader(group: HomeSessions.Group, opacity: Float) {
    val c = OcTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.bgBase)
            .height(HEADER_HEIGHT)
            // The floating ✎ sits on the *first* header's line
            // (`right-0; top-[84px]`), so the title ellipsizes before that slot
            // instead of running under the button.
            .padding(start = HomeRowLayout.SESSION_START, end = HomeRowLayout.SESSION_END),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = when (group) {
                HomeSessions.Group.TODAY -> stringResource(R.string.oc_home_group_today)
                HomeSessions.Group.YESTERDAY -> stringResource(R.string.oc_home_group_yesterday)
                HomeSessions.Group.OLDER -> stringResource(R.string.oc_home_group_older)
            },
            color = c.textMuted.copy(alpha = c.textMuted.alpha * opacity),
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * `HomeSessionRow`: `h-10`, radius 6, avatar + title + project name — plus the
 * trailing relative-time cell of A-3, the one piece upstream's row does not
 * carry (`view.tsx:518-660` stops at the project name; its `h-10` row has no
 * time slot at all, which is why a device run that only knew the palette's
 * `command-palette-meta` read the list as「完全没有相对时间标签」). The cell sits
 * where the palette puts its own meta value — the row's trailing edge, inside
 * the 40 dp end padding that the archive slot reserves — so it never fights the
 * title's ellipsis or the project label.
 *
 * 极窄 ([narrow], below [HomeRowLayout.NARROW_WIDTH]): the desktop numbers above
 * add up to more than the row is wide, and a `Row` measures its unweighted
 * children (the time cell) *first* — so the labels were the ones squeezed to
 * nothing. The compact shape drops the archive slot to upstream's own mobile
 * `pe-3` (12 dp), caps the time cell ([HomeRowLayout.timeMax]) so it ellipsizes
 * instead of eating the title, and lets the secondary project label go
 * ([HomeRowLayout.showProjectLabel]); the title is what must survive.
 */
@Composable
private fun HomeSessionRow(
    row: HomeSessions.Row,
    open: Boolean,
    showProjectName: Boolean,
    /** 极窄 (below [HomeRowLayout.NARROW_WIDTH]): the compact shape. */
    narrow: Boolean = false,
    now: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * A-2: the row's unread dot — `SessionTabIndicator.badge`'s `unread` for
     * this session, computed by the host through one `SessionDotSources` (see
     * `HomeSessionsColumn`'s note). Defaulted to "nothing unseen" so a preview /
     * isolated host renders the row it always did.
     */
    unread: Boolean = false,
    /** A-2: the row's marquee — the same badge's `loading` half. */
    loading: Boolean = false,
    // WAVE 2.3b P1 (A2 #4): `view.tsx:443-730` — right-click / long-press /
    // ContextMenu-key menu (Rename / Export… / Delete…) + the inline rename
    // editor (Enter saves, Esc cancels, IME composition guarded).
    menuOpen: Boolean = false,
    onOpenMenu: () -> Unit = {},
    onDismissMenu: () -> Unit = {},
    editor: HomeRowMenu.Editor? = null,
    onStartRename: () -> Unit = {},
    onRenameDraft: (String) -> Unit = {},
    onRenameSubmit: () -> Unit = {},
    onRenameCancel: () -> Unit = {},
    renameEnabled: Boolean = false,
    exportEnabled: Boolean = false,
    /** v40: the menu's 「导入会话」 row — absent while no host is wired. */
    importEnabled: Boolean = false,
    deleteEnabled: Boolean = false,
    onExport: () -> Unit = {},
    onImport: () -> Unit = {},
    onDelete: () -> Unit = {},
) {
    val c = OcTheme.colors
    // `const showProjectName = () => props.showProjectName && props.record.projectName`
    // (`view.tsx:451`) — the scope hides it, and so does an unknown directory.
    val project = if (showProjectName) row.project else null
    // While renaming, keep the native menu so paste works: the custom menu is
    // suppressed and the editor takes the row's place (`view.tsx:521-523`).
    val renaming = editor != null
    Box(modifier = modifier) {
        if (renaming) {
            HomeSessionRenameEditor(
                editor = editor,
                // v47 P3: the avatar the row it replaces draws — without it the
                // draft starts one leading slot + one gap (32 dp) left of every
                // other title (`research/v47_verify/tC/v47_07`).
                row = row,
                open = open,
                unread = unread,
                loading = loading,
                showProjectName = showProjectName,
                project = project,
                narrow = narrow,
                onDraft = onRenameDraft,
                onSubmit = onRenameSubmit,
                onCancel = onRenameCancel,
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // The 48 dp floor for the clickable node, before any `.height`
                    // / `.clip` / `.clickable` in this chain (see
                    // `ocMinTouchTarget` for the same ordering trap).
                    .ocHitTargetAtLeast(HomeRowLayout.MIN_HIT_TARGET)
                    .heightIn(min = ROW_HEIGHT)
                    .clip(OcTheme.shapes.md)
                    .combinedClickable(
                        role = Role.Button,
                        onClick = onClick,
                        onLongClick = onOpenMenu,
                    )
                    // v58 P0: the `gap-px` between two cards is the row's own
                    // pixel — as an outer padding it was dead list background.
                    .padding(bottom = ROW_GAP)
                    // `py-3 pl-3 pr-10`: the 40 dp trailing gap is the archive slot
                    // (`SHOW_HOME_SESSION_ARCHIVE === false`), kept for fidelity so the
                    // title's ellipsis lands where upstream's does — and reduced to
                    // upstream's own `pe-3` (12 dp) below the row's width floor, since
                    // `md:pe-10` is the desktop half of that same class list.
                    .padding(start = HomeRowLayout.SESSION_START, end = HomeRowLayout.sessionEnd(narrow))
                    .onPreviewKeyEvent { event ->
                        // `ContextMenu` key / `Shift+F10` opens the menu
                        // (`view.tsx:622-627`).
                        if (
                            event.type == KeyEventType.KeyDown &&
                            HomeRowMenu.menuKeyOpens(event.key == Key.Menu, event.key == Key.F10 && event.isShiftPressed)
                        ) {
                            onOpenMenu()
                            true
                        } else {
                            false
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
                // Upstream button is `gap-2`: 8 dp between avatar / title / project.
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // The editor cannot observe IME composition through
                // `onPreviewKeyEvent` (no `isComposing` on the native event), so
                // Enter/Escape here commit to save/cancel directly; the pure
                // [HomeRowMenu.isCompositionKey] guard documents the upstream
                // rule (`view.tsx:557-563`) for hosts that can see composition.
                HomeSessionLeading(row = row, open = open, unread = unread, loading = loading)
                // The labels keep upstream's own sizing inside a weighted box, so
                // the time cell below can own the row's trailing edge (the
                // palette's `command-palette-meta` position) instead of trailing
                // the project name.
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = row.title,
                        color = c.textBase,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // `max-w-[min(70%,480px)] flex-[0_1_auto]` with a project name,
                        // `flex-[1_1_auto]` without one (`view.tsx:550`).
                        modifier = Modifier.weight(
                            if (project == null) 1f else HomeRowLayout.TITLE_WEIGHT,
                            fill = false,
                        ),
                    )
                    if (project != null) {
                        ProjectLabel(
                            text = project,
                            modifier = Modifier.weight(1f - HomeRowLayout.TITLE_WEIGHT, fill = false),
                        )
                    }
                }
                // A `Row` measures its unweighted children *first*, so this cell
                // used to take its natural width before the weighted labels above
                // got a pixel — the 极窄 defect. The cap makes it ellipsize
                // instead ([HomeRowLayout.timeMax]) while it keeps owning the
                // row's trailing edge.
                Box(modifier = Modifier.widthIn(max = HomeRowLayout.timeMax(narrow))) {
                    SessionRowTime(updatedAt = row.updatedAt, now = now)
                }
            }
        }
        // Right-click opens the same menu (`view.tsx:521-526`); plain
        // `combinedClickable` has no secondary-button hook. Desktop mouse
        // right-click stays deferred (no verified pointer-button API on this
        // Compose BOM - see notes); touch long-press above is the wired path.
        if (!renaming) {
            // Reserved slot for a future right-click modifier once the
            // pointer-button API is verified against the pinned BOM.
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = onDismissMenu,
            shape = RoundedCornerShape(6.dp),
            containerColor = OcTheme.colors.layer01,
            tonalElevation = 0.dp,
            border = BorderStroke(0.5.dp, OcTheme.colors.borderMuted),
            // v59: ESC 关不掉这个菜单（`research/v59_verify/5558`）——同一个
            // `DropdownMenu` 缺陷，与 `WorkspaceSelector`（v58）完全一致：
            // `Popup` 默认不是 focusable，弹层窗口里没有焦点归属，ESC 进了窗口
            // 却没人接（外点关闭是 `PopupLayout` 自己判的，所以点空白正常）。
            //
            // v61 真机（`research/v61_verify/5558`）：v59 这版在手机上一颗没生效。
            // 根因是 `DropdownMenu` 的 `modifier` 挂在**宿主树**上一个零尺寸 `Box`
            // 上（不在 `Popup` 里），ESC 只到宿主窗口；`ocOverlayEscape` 改成往
            // 宿主窗口上的 [OcEscapeRouter] 注册（一个 View 只挂一个监听），`open`
            // 门控住「关着时那个 Box 也在组合里」——不然它会把焦点从 composer 抢走。
            properties = PopupProperties(
                focusable = true,
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
            ),
            modifier = Modifier.ocOverlayEscape(open = menuOpen) { onDismissMenu() },
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(R.string.oc_rename_session),
                        color = OcTheme.colors.textBase,
                    )
                },
                enabled = renameEnabled,
                onClick = onStartRename,
            )
            DropdownMenuItem(
                // `common.export` + ellipsis (view.tsx:695-697); Compose-side i18n
                // owns res, so the ellipsis joins the existing literal.
                text = {
                    Text(
                        text = stringResource(R.string.oc_context_export_session) + "…",
                        color = OcTheme.colors.textBase,
                    )
                },
                enabled = exportEnabled,
                onClick = onExport,
            )
            // v40: 「导入会话」 — the file an export wrote is what an import reads,
            // so it sits directly under 「导出会话」, exactly where the chat
            // header's ⋮ row places it. Absent without a host (upstream's
            // `Show when={canImportSession}`) and labelled with upstream's own
            // `command.session.import` — the key the project row's menu uses, so
            // the three entries for the one flow read alike.
            if (importEnabled) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(R.string.command_session_import),
                            color = OcTheme.colors.textBase,
                        )
                    },
                    onClick = onImport,
                )
            }
            HorizontalDivider(color = OcTheme.colors.borderMuted)
            DropdownMenuItem(
                // `common.delete` + "…" (`view.tsx:699-701`).
                text = {
                    Text(
                        text = stringResource(R.string.oc_delete_session) + "…",
                        color = OcTheme.colors.textBase,
                    )
                },
                enabled = deleteEnabled,
                onClick = onDelete,
            )
        }
    }
}

/**
 * The inline rename editor (`view.tsx:481-513,531-581`): a single-line field
 * prefilled with the row title, Enter saves (IME composition guarded), **blur
 * commits** ([HomeRowMenu.CommitTrigger.BLUR] — a field that held focus and lost
 * it asks the host to save: the `.onFocusChanged` chain below, v43 P0/v85
 * device check) while Esc cancels. The input disables while the `PATCH` is in
 * flight (`editor.renaming`). Upstream restores focus to the row on success and
 * to the input on failure; this host closes the editor on **both** answers
 * ([HomeRowMenu.resolveSave], v84 P1 — a failure must not leave the row in a
 * state a phone cannot leave) and BACK discards it as well.
 *
 * [narrow] is the row's width floor ([HomeRowLayout]): the editor takes the
 * same `pe-3` (12 dp) as the row it replaces, so switching into rename does not
 * move the text it is editing.
 *
 * v43 P0: the field now carries the **full** commit chain the chat header's
 * inline title has (`SessionHeader.kt`: Enter / NumPadEnter, the IME's Done, and
 * a blur once focused) plus the keyboard it needs to answer any of them. Opening
 * the editor took Enter, the IME's ✓ and a tap-away and did nothing at all — the
 * draft never reached `saving`, so `onSubmit` was never called and Esc was the
 * only way out (`research/v43_verify/tC/v43_C1/C2/C5`).
 *
 * v43 P1: opening now really takes focus and raises the keyboard, and the
 * whole-title selection is re-asserted once the field *has* focus — the IME
 * connection restores Android's own caret at the end of the text, which turned
 * the first keystroke into an append (`abcabc`) instead of upstream's
 * `input.select()` replacement (`research/v43_verify/tC/v43_C3/C4/C5`).
 *
 * v45 P1/P2: two field-level defects came back out of this composable. The
 * field was a bare weighted child with no surface of its own, so the editor
 * drew an empty grey bar and the draft was nowhere on screen although
 * uiautomator could read it (`research/v45_verify/tC/v45_15/22/25/31`); and
 * the focus ask raced its own composition frame, so the **first** open of a
 * cold list came up unfocused and the first characters went to the search
 * field (`research/v45_verify/tC/v45_08/10`). The field now carries its own
 * surface and height, and the ask waits for a settled frame ([FOCUS_TRIES]).
 *
 * v77 P0 「Home 内联重命名永久失灵」: the entry sequence could end in a state
 * with **no way back**. Two things stack up. The field is seeded once
 * (`remember(editor.sessionId)`) and then only ever written by the IME, so a
 * single empty write — the IME re-initialising against the whole-title
 * selection when the app re-attaches it, and committing a stale, empty
 * composing region over it — leaves an empty box, and because the write is
 * echoed to the host the draft is wiped with it: `HomeRowMenu.submit` reads a
 * blank draft, answers `Noop`, and the editor closes without a `PATCH`
 * ([homeRenameEntryRestore] takes the entry value back while the user has not
 * typed, and the seed falls back to the row's title). And the focus ask is a
 * one-shot budget over two frames, so a row the host places later spends it
 * before the field has a focus node at all — after that, typing, Enter, the IME's
 * ✓ and Esc all have nowhere to land, and a phone has no other way out
 * (`research/v73_verify/5554/34_rename` works, `36_rename2`/`38_ren4` are empty,
 * `38_ren4`/`39_tapkey` are byte-identical). The asks are now spread over time
 * ([FOCUS_LATE_RETRIES]) and issued at most once per attempt ([askIssued]), so
 * neither a layout pass nor a spent frame can leave the editor without a live
 * `InputConnection`.
 */
@Composable
private fun HomeSessionRenameEditor(
    editor: HomeRowMenu.Editor,
    /**
     * v47 P3: the row being renamed — the editor draws its leading avatar, so
     * the draft starts exactly where the row's own title does instead of one
     * avatar slot + one gap to its left. The dot / marquee ride along for the
     * same reason: the row they belong to is still the row on screen.
     */
    row: HomeSessions.Row,
    open: Boolean = false,
    unread: Boolean = false,
    loading: Boolean = false,
    showProjectName: Boolean,
    project: String?,
    narrow: Boolean = false,
    onDraft: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    val c = OcTheme.colors
    val focusRequester = remember(editor.sessionId) { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // v40 BUG-1: the field must own a `TextFieldValue`. A rebuilt
    // `TextFieldValue(editor.draft)` seeds the selection with `TextRange.Zero`
    // on every recomposition, so the caret jumped back in front of the draft:
    // characters spliced in reverse order, DEL / Ctrl+A dead
    // (`research/v40_verify/tC/B2_rename_reverse.png`). The draft still lives in
    // [editor] — [onDraft] echoes every edit — and the entry selection is the
    // whole title, like the tab strip's and the header's.
    // v77 BUG 「Home 内联重命名打开即空」: the seed is the draft the editor was
    // opened with ([HomeRowMenu.openEditor] prefills the row's title) — and a
    // draft that comes back blank is the IME's own doing
    // ([homeRenameEntryRestore]), never a title this shell has: a session
    // without one is named 「Untitled」 before it reaches a row
    // (`V2Api.optStringOrNull` reads a blank `title` as absent, `OpenCodeClient`
    // maps an empty one to the same fallback). The seed therefore falls back to
    // the row's own name instead of opening a blank box, and the field never
    // starts from nothing while the row it replaced has a label.
    var value by remember(editor.sessionId) {
        val draft = HalfWidthText.normalizeName(editor.draft.ifBlank { row.title })
        mutableStateOf(TextFieldValue(draft, homeRenameEntrySelection(draft)))
    }
    // v43 P0/P1: the commit chain ([HomeRowMenu.shouldCommit]) and whether the
    // field has actually held focus — the whole-title selection is re-asserted
    // once it does, because the IME connection restores Android's own caret at
    // the end of the text and typing then appended (`abcabc`,
    // `research/v43_verify/tC/v43_C4/C5`) instead of replacing the selection.
    var focused by remember(editor.sessionId) { mutableStateOf(false) }
    // v77 BUG 「打开即空」: has the user produced a character yet? An empty write
    // before that is the IME's attach artifact, not an edit — see
    // [homeRenameEntryRestore] and [restoreEntryValue] below.
    var touched by remember(editor.sessionId) { mutableStateOf(false) }
    // v64 BUG-3: the draft as the editor opened with it — the re-assert below
    // compares against it so it can only ever fire before the first edit.
    val entryText = remember(editor.sessionId) { value.text }
    val gate = remember(editor.sessionId) { HomeRenameCommitGate() }
    fun commit(trigger: HomeRowMenu.CommitTrigger) {
        if (gate.commit(trigger, editor.saving, focused)) onSubmit()
    }
    fun cancel() {
        if (gate.cancel()) onCancel()
    }
    // v84 P1 「Home 行卡死编辑态」: BACK is the one exit a phone always has, and
    // the editor — which *replaces* its row — is the only Home state that can
    // swallow every other one (long-press gives the platform's text-selection
    // bar, a tap lands in the field, and there is no Esc on a soft keyboard).
    // Enabled by being composed: this handler only exists while an editor is
    // open, and it discards (never commits) — the same decision Esc takes. While
    // a `PATCH` is in flight the answer closes the editor ([HomeRowMenu.resolveSave]),
    // and [HomeRowMenu.closeEditor] refuses to discard under it.
    BackHandler { cancel() }
    // v77 BUG 「Home 内联重命名打开即空」: the field can be blanked *after* it was
    // seeded. The IME re-initialises its editor against the entry selection
    // whenever the app re-attaches it (`requestFocus()` + `keyboard.show()` is a
    // fresh `restartInput`, the v66 mechanism) and commits its stale composing
    // region over that selection — with nothing in it, the whole title goes. The
    // write arrives through `onValueChange`, so the host's draft is wiped with
    // it, and `HomeRowMenu.submit` then reads a blank draft and answers `Noop`:
    // the editor closes without a `PATCH`, the tab keeps the old name, and the
    // rename reads as 「完全不可用」 (`research/v73_verify/5554/36_rename2`,
    // `38_ren4`: an empty grey bar with no text and no caret, where
    // `34_rename`/`35_renamed` had shown the title and the draft).
    //
    // An empty write before the user's first character is never an edit, so the
    // field takes the entry value back — the row's title, whole-title selected,
    // exactly as it opened — and the host's draft with it, so the two cannot
    // disagree about what the field holds.
    //
    // One repair per editor ([touched] is armed by it): the rule exists for the
    // IME's attach race, and the very next thing it could mistake for one is a
    // user clearing the field with DEL before typing anything. Once it has run,
    // that is their own edit and the field stays as they left it.
    fun restoreEntryValue() {
        val restored = homeRenameEntryRestore(value, entryText, touched)
        if (restored !== value) {
            value = restored
            touched = true
            onDraft(restored.text)
        }
    }
    // `requestFocus()` alone left the field with no live `InputConnection` on a
    // device: the first keystrokes went nowhere and Enter / the IME's ✓ had
    // nowhere to land, so the editor was only ever escapable with Esc
    // (`research/v43_verify/tC/v43_C1/C2/C5`). A row inside a `LazyColumn`
    // composes before its focus node is attached, so the first ask can be a
    // no-op — ask again once the row is placed ([FOCUS_TRIES] is all this can
    // cost, and a redundant ask on an already-focused field does nothing) and
    // raise the keyboard ourselves.
    //
    // v45 P2 「首开不聚焦」: neither timing was reliable. The `LaunchedEffect` ask
    // runs on the frame the editor is *created* — before the LazyColumn has
    // placed the row and before the focus node is attached — and the
    // `onGloballyPositioned` retry only fires when the row is laid out, which on
    // the first open of a cold list happens in the same frame the effect already
    // spent its budget on (`research/v45_verify/tC/v45_08/10`: the first open
    // left `focused=false` and the first characters went to the search field,
    // while every later open worked). The fix is to ask on a *settled* frame:
    // `withFrameNanos` returns after the frame that attached and placed the
    // node, which is the first moment `requestFocus()` can land.
    var focusTries by remember(editor.sessionId) { mutableIntStateOf(0) }
    // v77 BUG 「打开即空」: one ask per attempt. `requestFocus()` made during
    // layout is applied a frame later, so a row that relayouts again inside that
    // window used to fire the placement retry again — and every repeat is
    // `requestFocus()` + `keyboard.show()`, i.e. a fresh `restartInput` for the
    // IME (the v66 mechanism, here on a field that has *not* focused yet, which
    // the `focused` guard cannot catch). The IME re-initialising against the
    // entry selection is what commits its stale composing region over the whole
    // title and leaves the box empty. The flag is cleared when focus lands or is
    // lost, and before each scheduled retry below, so it bounds one attempt
    // rather than the editor's whole life.
    var askIssued by remember(editor.sessionId) { mutableStateOf(false) }
    fun askForFocus() {
        // v66 BUG-4: the ask is for the **first** focus only. It used to run on
        // every `onGloballyPositioned` until the try budget ran out — and a
        // rename row relayouts on every keystroke, because the title it draws
        // grows with the draft. Each repeat is a fresh `requestFocus()` plus
        // `keyboard.show()`, i.e. a re-attach of the editor's `InputConnection`,
        // and an IME re-initialised mid-segment drops the character it was
        // holding: `E2ECHK5` + `558-` + `ent` landed as `E2ECHK5558-nt`
        // (`research/v65_verify/5558/36_rename_ent_typed.png`). The plain
        // composer has no such ask and never lost a character, which is the
        // shape of the difference. Once the field holds focus there is nothing
        // left to ask for, so the loop stops here as well as in its caller.
        if (focused) return
        // v77: …and only once per attempt (see [askIssued]) — the same re-attach
        // hazard, one step earlier: a layout pass can call this several times
        // before the focus event lands.
        if (askIssued) return
        if (focusTries >= FOCUS_TRIES) return
        // v69 C3: a **failed** ask must not spend a try. The counter used to
        // advance before the call, so an ask made while the `LazyColumn` had
        // not attached the focus node yet (`requestFocus()` throws
        // `FocusRequester is not initialized`, the exact no-op v45 measured) was
        // paid for out of the budget that the settled-frame retry and the
        // `onGloballyPositioned` retry both draw on — and with the budget gone
        // neither could ask again. The field then kept the typed draft with no
        // live `InputConnection`, which is the v43 failure verbatim: the first
        // Enter after a rename did nothing and the second one worked
        // (`research/v68_verify/5558/07_rename.png` → `10_rename2.png`).
        // Only an ask that actually reached a focus node costs a try, so the
        // v66 rule (a repeat ask once the field holds focus re-attaches the IME
        // and drops the character in flight) is untouched — that repeat is
        // still stopped by the `focused` guard above.
        if (runCatching { focusRequester.requestFocus() }.isSuccess) {
            askIssued = true
            focusTries += 1
            keyboard?.show()
        }
    }
    LaunchedEffect(editor.sessionId) {
        // The node is attached and placed by the time this frame returns; the
        // plain first-dispatch ask above was the version that lost the race.
        withFrameNanos { }
        askForFocus()
        // …and once more on the frame after, for the host that places the row a
        // frame late (the same retry `onGloballyPositioned` covers, but without
        // depending on the row being measured at all).
        askIssued = false
        withFrameNanos { }
        askForFocus()
        // v77 BUG 「打开即空且永不恢复」: both asks above land inside two frames,
        // and a card that places its row later — a keyboard-up phone column, a
        // list the editor scrolls back into view — can spend them before the
        // field has a focus node at all. The field was then left holding the
        // draft with no `InputConnection`: typing, Enter, the IME's ✓ and even
        // Esc had nowhere to land, and a phone has no other way out
        // (`research/v73_verify/5554/38_ren4` and `39_tapkey` are byte-identical
        // — the key taps did nothing at all). The tail is spread over time
        // instead of frames, so a layout pass cannot spend it, and it still
        // answers to the v66 guard (`focused`) and the v69 budget
        // ([FOCUS_TRIES]).
        repeat(FOCUS_LATE_RETRIES) {
            if (focused) return@LaunchedEffect
            delay(FOCUS_LATE_RETRY_MS)
            askIssued = false
            askForFocus()
        }
    }
    LaunchedEffect(focused) {
        if (focused) {
            value = homeRenameEntryReassert(value, entryText)
            restoreEntryValue()
        }
    }
    // v77 BUG: the blanking arrives as an IME write, not as a focus transition
    // (the re-attach can blank the field while it already holds focus), so the
    // repair hangs off the value itself as well.
    LaunchedEffect(value.text, touched) {
        restoreEntryValue()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .clip(OcTheme.shapes.md)
            .onGloballyPositioned { if (!focused) askForFocus() }
            .padding(bottom = ROW_GAP)
            .padding(start = HomeRowLayout.SESSION_START, end = HomeRowLayout.sessionEnd(narrow)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // v47 P3 (see the `row` note): the same leading the row it replaces
        // draws — avatar, open marker, dot / marquee — so the draft aligns
        // with every other title in the list.
        HomeSessionLeading(row = row, open = open, unread = unread, loading = loading)
        BasicTextField(
            value = value,
            onValueChange = {
                // v64 BUG-3: the caret guard the composer's editor folds every
                // write through (`ComposerImeCommit.adopt`) — a value whose
                // selection / composition reaches past its own text parks the
                // caret at 0 and makes DEL / select-all+delete no-ops, which is
                // how the field lost characters while being typed into.
                val folded = ComposerImeCommit.adopt(HalfWidthText.normalizeName(it))
                // v77 BUG: only a write that carries a character is the user
                // typing. An empty one is the IME's attach artifact (see
                // [homeRenameEntryRestore]) — arming the repair's `touched`
                // guard with it is exactly how the blank box became permanent.
                if (folded.text.isNotEmpty()) touched = true
                value = folded
                onDraft(folded.text)
            },
            enabled = !editor.saving,
            singleLine = true,
            textStyle = TextStyle(color = c.textBase, fontSize = 13.sp, fontWeight = FontWeight.Medium),
            cursorBrush = SolidColor(c.textBase),
            keyboardOptions = OcKeyboard.literal(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit(HomeRowMenu.CommitTrigger.IME_DONE) }),
            modifier = Modifier
                // v45 P1 「改名输入框文字不可见」: the editor rendered as an empty
                // grey bar — the draft was in the node (uiautomator saw it) and
                // nothing was on the screen. The field was a bare `weight(fill =
                // false)` child with no surface of its own, so everything about
                // it depended on how the weighted slot measured: a field that
                // measures to its intrinsic width leaves the row's own
                // background showing and nothing else, and a slot that comes
                // out empty leaves a bar the user cannot tell from a dead row
                // (`research/v45_verify/tC/v45_15/22/25/31`).
                //
                // It is now laid out the way the chat header's inline title — the
                // same write, the same commit chain, and the one field on this
                // app that verifiably shows its text on a device
                // (`SessionHeader.kt`) — is: the surface and the padding ride on
                // the **field**, the field fills its slot (`fill = true`) and
                // keeps a row's height of its own, so the text has a body to be
                // drawn into no matter what the slot measures.
                .weight(
                    if (project == null) 1f else HomeRowLayout.TITLE_WEIGHT,
                    fill = true,
                )
                .defaultMinSize(minHeight = ROW_HEIGHT)
                .clip(OcTheme.shapes.md)
                .background(c.layer02.copy(alpha = 0.6f))
                .focusRequester(focusRequester)
                // v43 P0: the missing half of the commit chain — the chat
                // header's inline title has it (`SessionHeader.kt`: commit on
                // blur once focused), this field had only Enter + the IME's Done,
                // and a commit that arrived before the field held focus had
                // nowhere to land. A field that never took focus does not commit
                // ([HomeRowMenu.shouldCommit]).
                .onFocusChanged { state ->
                    if (state.isFocused) {
                        // v77: the attempt is over — the next one (the layout
                        // retry, or a later re-ask) may ask again.
                        askIssued = false
                        focused = true
                    } else if (focused) {
                        // Commit *before* clearing the flag: a blur only commits
                        // from a field that held focus ([HomeRowMenu.shouldCommit]),
                        // so the order here is the whole mechanism.
                        commit(HomeRowMenu.CommitTrigger.BLUR)
                        focused = false
                        // v77: a field that lost focus can be asked for it again
                        // (`askIssued` bounds one attempt, not the editor).
                        askIssued = false
                    }
                }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        event.key == Key.Enter -> {
                            commit(HomeRowMenu.CommitTrigger.ENTER)
                            true
                        }
                        // `Key.NumPadEnter` too: Compose never turns it into
                        // `onDone`, and a hardware / Bluetooth keyboard's Enter is
                        // exactly the path that did nothing on the device.
                        event.key == Key.NumPadEnter -> {
                            commit(HomeRowMenu.CommitTrigger.ENTER)
                            true
                        }
                        event.key == Key.Escape -> {
                            cancel()
                            true
                        }
                        else -> false
                    }
                },
        )
        if (project != null && showProjectName) {
            ProjectLabel(
                text = project,
                modifier = Modifier.weight(1f - HomeRowLayout.TITLE_WEIGHT, fill = false),
            )
        }
    }
}

/**
 * v44 P1: the `LazyColumn` item index of the row with [sessionId] — the same
 * arithmetic [HomeSessionList]'s sticky-header fade uses, kept as one function
 * so the fade's header index and the editor's scroll target cannot drift apart.
 *
 * A group contributes [SECTION_SLOTS] items beyond its rows: the sticky header,
 * the 16 dp pad under it, and the trailing spacer. A session the list does not
 * render (a different scope, a search result) has no index — `null`, and the
 * caller leaves the scroll position alone.
 */
internal fun homeSessionItemIndex(
    sections: List<HomeSessions.Section>,
    sessionId: String,
): Int? {
    var index = 0
    for (section in sections) {
        val row = section.rows.indexOfFirst { it.id == sessionId }
        if (row >= 0) return index + ROWS_OFFSET + row
        index += section.rows.size + SECTION_SLOTS
    }
    return null
}

/**
 * v46 P0: the editor scroll's stable key — the groups and their member ids, and
 * nothing else.
 *
 * `HomeSessionList` scrolls the editing row into view, but `sections` is a new
 * list instance on every recompute with identical content (the 30 s `homeNow`
 * tick rebuilds it through `remember(rows, now)`), and keying the scroll effect
 * on the instance re-fires `animateScrollToItem` while the user is typing or
 * trying to look at a sibling row. A title-only change (the rename commit
 * itself) keeps the signature, so the commit no longer yanks the viewport; a
 * membership change still re-seats the editor.
 */
internal fun homeSectionsSignature(sections: List<HomeSessions.Section>): String =
    sections.joinToString("|") { section ->
        section.group.name + ":" + section.rows.joinToString(",") { it.id }
    }

/**
 * v40 BUG-1: the selection the Home row's rename editor opens with — the **whole**
 * title, upstream's `input.select()` (`home/sessions/view.tsx:481-489`), so the
 * first keystroke replaces the old name instead of splicing into it. Same shape as
 * `tabRenameEntrySelection` (`BottomTabStrip.kt`) and
 * `InlineTitleEdit.entrySelection` (`SessionHeader.kt`).
 */
internal fun homeRenameEntrySelection(title: String): TextRange = TextRange(0, title.length)

/**
 * v77 BUG 「Home 内联重命名打开即空」: the value the editor takes back when the
 * field has been blanked before the user's first character.
 *
 * The field is seeded with the row's title and the whole-title selection
 * ([homeRenameEntrySelection]) — and then the IME re-initialises its own editor
 * against that selection whenever the app re-attaches it (`requestFocus()` +
 * `keyboard.show()` is a fresh `restartInput`, the mechanism v66 named) and
 * commits its stale composing region over it. With nothing in that region, the
 * whole title goes, the write reaches `onValueChange`, and the host's draft is
 * wiped with it — so `HomeRowMenu.submit` reads a blank draft, answers `Noop`,
 * and closes the editor without a `PATCH`: the rename is silently gone and the
 * tab keeps the old name. Measured on `emulator-5554`
 * (`research/v73_verify/5554/34_rename` → `35_renamed` → `36_rename2`,
 * `38_ren4`): the bar is empty — no text, no caret — where the first open had
 * shown the title selected, and every later open lands in the same state.
 *
 * An empty write **before the user's first character** is never an edit, so the
 * field goes back to the entry value: the title, whole-title selected, exactly
 * as it opened. Once the user has typed ([touched]) the rule is out of the way
 * for the rest of the editor's life, so a deliberate select-all + delete is
 * never undone; and a title-less row (which this shell does not produce — a
 * session without a name is 「Untitled」 before it reaches a row) keeps its
 * blank field rather than inventing one.
 *
 * Reference-preserving: an untouched field that already holds text comes back as
 * the very same instance, so the effect that calls this schedules no
 * recomposition.
 */
internal fun homeRenameEntryRestore(
    current: TextFieldValue,
    title: String,
    touched: Boolean,
): TextFieldValue {
    if (touched) return current
    if (current.text.isNotEmpty()) return current
    if (title.isEmpty()) return current
    return TextFieldValue(title, homeRenameEntrySelection(title))
}

/**
 * v64 BUG-3: the v43 re-assert of the whole-title selection, bounded to the one
 * moment it exists for — the field has taken focus and the user has not typed
 * yet.
 *
 * Unbounded it was a hazard rather than a fix: the re-assert selects whatever
 * the field holds at the moment it runs, so a field that had already received
 * the first segment of a segmented IME commit had that segment selected, and
 * the next segment replaced it (`cursor` → `urso` on `emulator-5558`,
 * `research/v64_verify/5558/10c`). A live composing region is the same hazard
 * one step earlier, so it is left alone too.
 *
 * The draft is the entry text for exactly the v43 case the rule was written for
 * (`abcabc`, `research/v43_verify/tC/v43_C4/C5`): the IME connection restores
 * Android's caret at the end of the text, and the first keystroke must replace
 * the title rather than append to it. Reference-preserving, so an untouched
 * field schedules no recomposition.
 */
internal fun homeRenameEntryReassert(current: TextFieldValue, entryText: String): TextFieldValue =
    if (current.text == entryText && current.composition == null) {
        current.copy(selection = homeRenameEntrySelection(current.text))
    } else {
        current
    }

/**
 * v64 BUG-3: the rename editor's one-shot decision latch.
 *
 * A single gesture reaches this field more than once. The IME's ✓ arrives both
 * as `KeyboardActions.onDone` and, from a hardware / Bluetooth keyboard, as a
 * `Key.Enter` key event the preview handler also commits on; and *closing* the
 * editor detaches the field, which force-clears focus with a focus-event
 * refresh (`FocusTargetNode.kt:83-104`), so the blur that closing produces
 * arrives after the commit or the cancel that caused it. Without a latch the
 * second one re-entered `onSubmit` and the rename was driven twice — measured
 * as an IME ✓ that deleted a character instead of saving, and a segmented edit
 * that lost one (`research/v64_verify/5558/10c`–`10f`).
 *
 * Whichever decision runs first settles the editor; the trailing one decides
 * nothing. This is the header's inline-title latch
 * ([com.xiufeigo.opencodeshell.ui.session.header.InlineTitleEdit.settled]),
 * applied to the row editor.
 */
internal class HomeRenameCommitGate {

    var settled: Boolean = false
        private set

    /** True for the one call that may submit. */
    fun commit(trigger: HomeRowMenu.CommitTrigger, saving: Boolean, focused: Boolean): Boolean {
        if (settled) return false
        if (!HomeRowMenu.shouldCommit(trigger, saving, focused)) return false
        settled = true
        return true
    }

    /** Escape, or a host that closed the editor. Never writes — the blur it causes is swallowed. */
    fun cancel(): Boolean {
        if (settled) return false
        settled = true
        return true
    }
}

/**
 * `HomeSessionLeading` — `SessionTabAvatarView` + the "open as tab" marker
 * (`h-3 w-0.5 rounded-[2px] bg-layer-04` at `calc(100% + 4px)`).
 *
 * The avatar is `ProjectAvatar` (`home/sessions/view.tsx:219-228`
 * `SessionTabAvatarView` → `ProjectAvatar`): the project's own custom icon image
 * when it has one (`icon.override`), else its `icon.color` variant, else the
 * neutral surface with the name's initial — the same `override → color →
 * initial` chain the 项目 column and the editor's preview draw, through the one
 * [ProjectIconView]. B-14 landed the image half of that chain (the SAF pick, the
 * `data:` URL on `icon.override`, the decode); this row was the last surface
 * still painting the name-initial stand-in, so a project with an uploaded icon
 * read as its icon in the projects column and as a letter in the session list
 * directly beside it.
 *
 * The name the initial falls back to is upstream's `displayName(project ??
 * { worktree: directory })` — i.e. the row's project label. A session the server
 * gave no directory for has no label, and keeps the pre-B-14 title initial so
 * the avatar is never blank.
 *
 * A-2 (the v28 device run): the slot also carries the **unread dot** and the
 * **marquee**, exactly as upstream's does — `HomeSessionRowUI` renders
 * `SessionTabAvatarView` (`home/sessions/view.tsx:219-228`), whose
 * `loading ? SessionProgressIndicatorV2 : ProjectAvatar(unread)` is the same
 * pair the tab strip draws. Without them a permission the dock was showing lit
 * the strip (or nothing at all, on a phone) and never the row the user was
 * looking at: Home's rows were the one session list with no indicator slot, so
 * both the tracked session's ask and a foreign session's ask were invisible
 * there.
 *
 * The precedence is upstream's too: the marquee is the badge's `loading` half,
 * which `SessionTabIndicator.badge` computes as `!needsAttention && running` — a
 * session waiting on the user therefore draws the dot and never the marquee —
 * and the dot rides the avatar's corner ([SessionAvatarUnreadDot]).
 */
@Composable
private fun HomeSessionLeading(
    row: HomeSessions.Row,
    open: Boolean,
    unread: Boolean = false,
    loading: Boolean = false,
) {
    val c = OcTheme.colors
    Box(modifier = Modifier.width(HomeRowLayout.SESSION_LEADING).height(AVATAR_SIZE)) {
        if (loading) {
            // Upstream mounts the 25-dot marquee *instead of* the avatar.
            SessionProgressIndicator(modifier = Modifier.align(Alignment.TopStart), size = AVATAR_SIZE)
        } else {
            ProjectIconView(
                name = row.project ?: row.title,
                iconColor = row.iconColor,
                override = row.iconOverride,
                size = AVATAR_SIZE,
            )
            // Outside the avatar's clipped box on purpose: the dot overhangs its
            // corner by 3 dp (`project-avatar.css`), which the avatar's own clip
            // would cut off.
            if (unread) SessionAvatarUnreadDot(avatarSize = AVATAR_SIZE)
        }
        if (open) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(2.dp)
                    .height(12.dp)
                    .clip(OcTheme.shapes.xs)
                    .background(c.layer04),
            )
        }
    }
}

@Composable
private fun ProjectLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = OcTheme.colors.textMuted,
        fontSize = 13.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * A-3: the row's `common.time.*` label (`SessionTimeLabel`'s buckets — the same
 * ones the palette's `PaletteRelativeTime` and the session drawer resolve, so
 * the three surfaces can never round an age differently), rendered in the
 * palette's meta cell shape: 12 sp `text-muted`, one line, nothing for an
 * unknown `time.updated` (`updatedAt <= 0`).
 *
 * [now] is a parameter and never a `System.currentTimeMillis()` read inside this
 * composable: such a read is invisible to Compose, so a row — whose only inputs
 * are its own fields — is skipped once composed and the label freezes at the
 * bucket it was composed with. That is the freeze the palette and the drawer
 * both had to fix; [HomeSessionsColumn]'s `now` carries the host's ticking value
 * here (see its class note).
 */
@Composable
private fun SessionRowTime(updatedAt: Long, now: Long) {
    val relative = SessionTimeLabel.relative(updatedAt, now) ?: return
    Text(
        text = when (relative.unit) {
            SessionTimeLabel.Unit.JUST_NOW -> stringResource(R.string.oc_time_just_now)
            SessionTimeLabel.Unit.MINUTES ->
                pluralStringResource(R.plurals.oc_time_minutes_ago, relative.count, relative.count)

            SessionTimeLabel.Unit.HOURS ->
                pluralStringResource(R.plurals.oc_time_hours_ago, relative.count, relative.count)

            SessionTimeLabel.Unit.DAYS ->
                pluralStringResource(R.plurals.oc_time_days_ago, relative.count, relative.count)
        },
        color = OcTheme.colors.textMuted,
        fontSize = 12.sp,
        maxLines = 1,
        // One line that ellipsizes: without this a 极窄 row hard-clipped the
        // label mid-glyph, and the caller's `widthIn` cap had nothing to show.
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * `HomeSessionSkeleton`: a title row + 4 `h-10 rounded-[6px] bg-deep opacity-70`
 * bars. Static by design — the shimmer pass belongs to the M6 motion milestone.
 *
 * [narrow] follows the rows it stands in for ([HomeRowLayout]), so the bars do
 * not jump sideways when the real list lands.
 */
@Composable
private fun HomeSessionSkeleton(modifier: Modifier = Modifier, narrow: Boolean = false) {
    val c = OcTheme.colors
    val bar = c.bgDeep.copy(alpha = 0.7f)
    Column(
        modifier = modifier.padding(
            start = HomeRowLayout.SESSION_START,
            end = HomeRowLayout.sessionEnd(narrow),
        ),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Box(
            Modifier
                .padding(top = 4.dp, bottom = 12.dp)
                .width(72.dp)
                .height(12.dp)
                .clip(OcTheme.shapes.xs)
                .background(bar),
        )
        repeat(HomeSessions.SKELETON_ROWS) {
            Box(Modifier.fillMaxWidth().height(ROW_HEIGHT).clip(OcTheme.shapes.md).background(bar))
        }
    }
}

/**
 * `HomeSessionsEmpty`: `pt-[52px]`, centred "Nothing here yet" + description +
 * a `ButtonV2 variant="neutral"` "New session".
 *
 * v40 BUG-3: the block is 52 + 16 + 8 + 16 + 20 + 28 dp tall, and it used to be
 * laid out in a `fillMaxSize()` Column. A Column hands its children the full
 * height, so when the phone's list area was a few dp short of the block the
 * **last** child — the "New session" button — was clipped away to a ~14 px
 * sliver: present and tappable, but invisible
 * (`research/v40_verify/tA/47_settled.png`). Measuring the block at its
 * intrinsic height inside a scrolling viewport instead keeps the whole button
 * on screen on a short viewport and scrolls it into reach on a very short one.
 */
@Composable
private fun HomeSessionsEmpty(onNewSession: () -> Unit, modifier: Modifier = Modifier) {
    val c = OcTheme.colors
    Box(modifier = modifier, contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(top = 52.dp, start = 24.dp, end = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.oc_home_empty),
                color = c.textBase,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.oc_home_empty_description),
                color = c.textMuted,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(20.dp))
            NeutralButton(
                label = stringResource(R.string.oc_new_session),
                iconRes = R.drawable.ic_oc_edit,
                onClick = onNewSession,
            )
        }
    }
}

/**
 * The failed-read state: a failed catalogue read with no rows to show is a
 * retryable error, not the empty state. Same block shape as
 * [HomeSessionsEmpty] (intrinsic height in a scrolling viewport, so the button
 * survives a short card the same way that fix describes).
 */
@Composable
private fun HomeSessionsLoadError(onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    val c = OcTheme.colors
    Box(modifier = modifier, contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(top = 52.dp, start = 24.dp, end = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.oc_home_sessions_load_failed),
                color = c.textBase,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (onRetry != null) {
                Spacer(Modifier.height(20.dp))
                NeutralButton(
                    label = stringResource(R.string.retry),
                    iconRes = R.drawable.ic_oc_reopen,
                    onClick = onRetry,
                )
            }
        }
    }
}

/**
 * `ButtonV2 variant="neutral" size="normal"`: `h-7` = 28 dp, padding 11 dp,
 * radius 6, 13 px/530, `gap: 6px`, surface
 * `--v2-background-bg-button-neutral` (+ a 0.5 dp `borderMuted` hairline, since
 * Compose cannot port `--v2-elevation-button-neutral`'s two-layer box-shadow).
 */
@Composable
internal fun NeutralButton(
    label: String,
    iconRes: Int,
    onClick: () -> Unit,
) {
    val c = OcTheme.colors
    val shape = OcTheme.shapes.md
    Row(
        modifier = Modifier
            .height(BUTTON_HEIGHT)
            .clip(shape)
            .background(c.buttonNeutral)
            .border(0.5.dp, c.borderMuted, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = c.iconBase,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = label,
            color = c.textBase,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * `IconButtonV2 variant="ghost-muted" size="large" icon="edit"` — the floating
 * new-session button (`h-7 px-2`, absolutely positioned by the caller).
 */
@Composable
private fun FloatingNewSessionButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = OcTheme.colors
    // Hoisted: `semantics {}` is not a composable scope (see `HomeSearchField`).
    val newSessionLabel = stringResource(R.string.oc_new_session)
    Box(
        modifier = modifier
            // 28 dp of pixels, 48 dp of finger (`ocMinTouchTarget`). The button
            // is the column's only top-right affordance, so the ring has nothing
            // to overlap.
            .ocMinTouchTarget(visual = ICON_BUTTON_LARGE)
            .clip(OcTheme.shapes.md)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = newSessionLabel },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_oc_edit),
            contentDescription = null,
            tint = c.iconMuted,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * v44 P1: the `LazyColumn` items a group contributes besides its rows — the
 * sticky header, the `pt-4` pad under it and the trailing spacer. The item
 * index of a session's first row is therefore its group's header index plus
 * [ROWS_OFFSET] (see [homeSessionItemIndex]).
 */
private const val SECTION_SLOTS = 3

/**
 * v58 P0: where the floating ✎ sits. Upstream pins it to the **first group
 * header's line** (`right-0; top-[84px]`, and the header's own end padding is
 * sized to clear it), i.e. one [HomeRowLayout.GROUP_PAD_HEIGHT] below the
 * rows' top edge. `align(TopEnd)` alone left it in the pad *above* that header,
 * where its 48 dp finger ring ([ocMinTouchTarget]) covered the blank strip
 * between the search block and the first row: a tap in the gap the rows leave
 * there opened a draft session instead of doing nothing
 * (`research/v57_verify/E2ECHK5560/38_gaptap.png`).
 */
internal val NEW_SESSION_TOP: Dp = HomeRowLayout.GROUP_PAD_HEIGHT

/** Items between a group's sticky header and its first row. */
private const val ROWS_OFFSET = 2

/** `h-9` = 36 dp search field. */
private val SEARCH_HEIGHT = 36.dp

/** `HomeSessionRow` `h-10` = 40 dp ([HomeRowLayout.SESSION_ROW_HEIGHT]). */
private val ROW_HEIGHT = HomeRowLayout.SESSION_ROW_HEIGHT

/**
 * v43 P0: how many times the rename editor asks for focus / the keyboard. One
 * ask lands while the row is still being composed and is lost with no
 * `InputConnection`; the second, on first placement, is the one that sticks.
 *
 * v45 P2 「首开不聚焦」: the frame the row is placed on can be the frame the
 * first ask already spent, so two asks were one short on a cold list — the
 * editor came up unfocused and the first characters went to the search field
 * (`research/v45_verify/tC/v45_08/10`). Three covers the composition ask, the
 * first settled frame and the `onGloballyPositioned` placement; a redundant
 * ask on an already-focused field is a no-op, so the budget is the only cost.
 */
private const val FOCUS_TRIES = 3

/**
 * v77 BUG 「重命名打开即空且永不恢复」: how many *late* asks the editor makes
 * after the two settled frames, and the gap between them. The two frame-based
 * asks can both be spent before a late-placed row has a focus node at all
 * (a keyboard-up phone column, a list the editor scrolls back into view), and
 * the field was then left holding the draft with no `InputConnection` — typing,
 * Enter, the IME's ✓ and Esc all had nowhere to land and the editor never
 * recovered (`research/v73_verify/5554/38_ren4`/`39_tapkey` are byte-identical).
 * Spreading the asks over time instead of frames keeps them out of a layout
 * pass's reach; [FOCUS_TRIES] still bounds how many can land, and
 * `askForFocus()`'s `focused` guard still stops every one of them the moment the
 * field holds focus (the v66 rule — a repeat ask re-attaches the IME and drops
 * the character in flight).
 */
private const val FOCUS_LATE_RETRIES = 4

/** The gap between two late asks ([FOCUS_LATE_RETRIES]). */
private const val FOCUS_LATE_RETRY_MS = 120L

/** Search panel result row `h-10` = 40 dp. */
private val SEARCH_RESULT_HEIGHT = 40.dp

/** Group header `h-7` = 28 dp. */
private val HEADER_HEIGHT = 28.dp

/** `max-h-80` = 320 dp result list. */
private val PANEL_MAX_HEIGHT = 320.dp

/**
 * Minimum height the search panel takes while it shows 「no results」, so the
 * floating layer actually covers the session rows underneath it instead of
 * ending right below its own one-line copy (v97 fix, `REVIEW_HOME_SEARCH_DIAG.md`
 * §N4). 160 dp ≈ the header plus several `HomeRow` heights, which is the point
 * where the list below stops reading as part of the panel's answer.
 */
private val PANEL_MIN_COVER_HEIGHT = 160.dp

/** `icon-button-v2[size=large]` = 28 × 28 dp. */
private val ICON_BUTTON_LARGE = 28.dp

/** `icon-button-v2[size=small]` = 20 × 20 dp. */
private val ICON_BUTTON_SMALL = 20.dp

/** `button-v2[size=normal]` = 28 dp. */
private val BUTTON_HEIGHT = 28.dp

/** Avatar edge (`SessionTabAvatarView`), a 18 dp rounded square here. */
private val AVATAR_SIZE = 18.dp

/** `pt-6` = 24 dp on mobile … */
private val MOBILE_TOP_INSET = 24.dp

/** `gap-px` between rows. */
private val ROW_GAP = 1.dp

/** `pb-16` under the last group. */
private val LIST_BOTTOM_GAP = 64.dp

/** Floor of the sticky-header scroll fade. */
private const val MIN_HEADER_OPACITY = 0.25f
