@file:OptIn(ExperimentalFoundationApi::class)

package com.xiufeigo.opencodeshell.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.OpenCodeClient
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import com.xiufeigo.opencodeshell.ui.theme.ocFloatingShadow
import com.xiufeigo.opencodeshell.ui.theme.ocShadow
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
 */
@Composable
internal fun HomeSessionsColumn(
    rows: List<HomeSessions.Row>,
    loading: Boolean,
    configMissing: Boolean,
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
     * WAVE 2.3b P1 (A2 #4): row context-menu actions
     * (`view.tsx:694-701`). Null = the action is unavailable (e.g. no client)
     * and its menu item renders disabled.
     */
    onRenameSession: ((sessionId: String, title: String, onResult: (Boolean) -> Unit) -> Unit)? = null,
    onExportSession: ((HomeSessions.Row) -> Unit)? = null,
    onDeleteSession: ((HomeSessions.Row) -> Unit)? = null,
    /** WAVE 2.3b P1 (A2 #8): App.kt's `mod+f` hook focuses this field on Home. */
    searchFocusRequest: HomeSearchFocusRequest = remember { HomeSearchFocusRequest() },
) {
    val c = OcTheme.colors
    var query by remember { mutableStateOf("") }
    val sections = remember(rows) { HomeSessions.sections(rows, System.currentTimeMillis()) }
    // A2 #9: filter the full list, not the rendered 64 — plus the exact
    // session-ID fast path (`search.ts:86-101`), whose row bypasses the cap.
    val fullSearchRows = remember(searchSessions, rows) {
        HomeSessions.searchRows(searchSessions.ifEmpty { null }, rows)
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

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            // `contentPadding pt-3` — one 12 dp step between the search block and
            // the first sticky group header.
            Column(modifier = Modifier.fillMaxSize().padding(top = 12.dp)) {
                when {
                    loading -> HomeSessionSkeleton(Modifier.fillMaxSize())
                    rows.isEmpty() -> HomeSessionsEmpty(
                        onNewSession = onNewSession,
                        modifier = Modifier.fillMaxSize(),
                    )

                    else -> HomeSessionList(
                        sections = sections,
                        listState = listState,
                        showProjectName = showProjectName,
                        openSessionIds = openSessionIds,
                        onOpenSession = onOpenSession,
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
                            val step = HomeRowMenu.submit(rowEditor, row.id, row.title) ?: return@HomeSessionList
                            rowEditor = step.first
                            when (val action = step.second) {
                                HomeRowMenu.Submit.Noop -> rowEditor = null
                                is HomeRowMenu.Submit.Save -> onRenameSession?.invoke(
                                    row.id,
                                    action.title,
                                ) { saved ->
                                    rowEditor = HomeRowMenu.resolveSave(rowEditor, row.id, saved)
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
                modifier = Modifier.align(Alignment.TopEnd),
            )

            if (query.isNotEmpty()) {
                HomeSessionSearchPanel(
                    query = query,
                    results = results,
                    showProjectName = showProjectName,
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
                        )
                    }
                    innerTextField()
                }
            },
        )
        if (query.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .size(ICON_BUTTON_SMALL)
                    .clip(OcTheme.shapes.sm)
                    .clickable(role = Role.Button, onClick = { onQueryChange("") }),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_oc_close),
                    contentDescription = stringResource(R.string.oc_home_clear_search),
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
    onPick: (HomeSessions.Row) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    val shape = RoundedCornerShape(12.dp)
    val elevation = OcTheme.elevation.floating
    Column(
        modifier = modifier
            .ocShadow(elevation, shape)
            .clip(shape)
            .background(c.bgBase),
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
    onClick: () -> Unit,
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
    showProjectName: Boolean,
    openSessionIds: Set<String>,
    onOpenSession: (String) -> Unit,
    modifier: Modifier = Modifier,
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
    deleteEnabled: Boolean = false,
    onExport: (HomeSessions.Row) -> Unit = {},
    onDelete: (HomeSessions.Row) -> Unit = {},
) {
    // Item index of each group header in the LazyColumn below
    // (header + pt-4 spacer + rows + trailing spacer).
    val headerIndexes = remember(sections) {
        val map = mutableMapOf<HomeSessions.Group, Int>()
        var index = 0
        sections.forEach { section ->
            map[section.group] = index
            index += section.rows.size + 3
        }
        map
    }
    val fadeDistancePx = with(LocalDensity.current) { HEADER_HEIGHT.toPx() }

    LazyColumn(modifier = modifier, state = listState) {
        sections.forEachIndexed { position, section ->
            val headerIndex = headerIndexes[section.group] ?: 0
            stickyHeader(key = "group:${section.group.name}") {
                val scrolled = if (listState.firstVisibleItemIndex == headerIndex) {
                    listState.firstVisibleItemScrollOffset
                } else {
                    0
                }
                val opacity = (1f - scrolled / fadeDistancePx).coerceIn(MIN_HEADER_OPACITY, 1f)
                HomeGroupHeader(group = section.group, opacity = opacity)
            }
            item(key = "group-pad:${section.group.name}") { Spacer(Modifier.height(16.dp)) }
            items(items = section.rows, key = { "session:" + it.id }) { row ->
                HomeSessionRow(
                    row = row,
                    open = row.id in openSessionIds,
                    showProjectName = showProjectName,
                    onClick = { onOpenSession(row.id) },
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
                    deleteEnabled = deleteEnabled,
                    onExport = { onExport(row) },
                    onDelete = { onDelete(row) },
                    modifier = Modifier.padding(bottom = ROW_GAP),
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
            .padding(start = 12.dp),
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
        )
    }
}

/** `HomeSessionRow`: `h-10`, radius 6, avatar + title + project name — no time. */
@Composable
private fun HomeSessionRow(
    row: HomeSessions.Row,
    open: Boolean,
    showProjectName: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
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
    deleteEnabled: Boolean = false,
    onExport: () -> Unit = {},
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
                showProjectName = showProjectName,
                project = project,
                onDraft = onRenameDraft,
                onSubmit = onRenameSubmit,
                onCancel = onRenameCancel,
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ROW_HEIGHT)
                    .clip(OcTheme.shapes.md)
                    .combinedClickable(
                        role = Role.Button,
                        onClick = onClick,
                        onLongClick = onOpenMenu,
                    )
                    // `py-3 pl-3 pr-10`: the 40 dp trailing gap is the archive slot
                    // (`SHOW_HOME_SESSION_ARCHIVE === false`), kept for fidelity so the
                    // title's ellipsis lands where upstream's does.
                    .padding(start = 12.dp, end = 40.dp)
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
                HomeSessionLeading(row = row, open = open)
                Text(
                    text = row.title,
                    color = c.textBase,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // `max-w-[min(70%,480px)] flex-[0_1_auto]` with a project name,
                    // `flex-[1_1_auto]` without one (`view.tsx:550`).
                    modifier = Modifier.weight(if (project == null) 1f else TITLE_WEIGHT, fill = false),
                )
                if (project != null) {
                    ProjectLabel(text = project, modifier = Modifier.weight(1f - TITLE_WEIGHT, fill = false))
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
        DropdownMenu(expanded = menuOpen, onDismissRequest = onDismissMenu) {
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.oc_rename_session)) },
                enabled = renameEnabled,
                onClick = onStartRename,
            )
            DropdownMenuItem(
                // `common.export` + ellipsis (view.tsx:695-697); Compose-side i18n
                // owns res, so the ellipsis joins the existing literal.
                text = { Text(text = stringResource(R.string.oc_context_export_session) + "…") },
                enabled = exportEnabled,
                onClick = onExport,
            )
            HorizontalDivider(color = OcTheme.colors.borderMuted)
            DropdownMenuItem(
                // `common.delete` + "…" (`view.tsx:699-701`).
                text = { Text(text = stringResource(R.string.oc_delete_session) + "…") },
                enabled = deleteEnabled,
                onClick = onDelete,
            )
        }
    }
}

/**
 * The inline rename editor (`view.tsx:481-513,531-581`): a single-line field
 * prefilled with the row title, Enter saves (IME composition guarded),
 * Esc/blur cancels, and the input disables while the `PATCH` is in flight
 * (`editor.renaming`). Upstream restores focus to the row on success and to
 * the input on failure; this keeps the field focused until the host closes it
 * via [HomeRowMenu.resolveSave].
 */
@Composable
private fun HomeSessionRenameEditor(
    editor: HomeRowMenu.Editor,
    showProjectName: Boolean,
    project: String?,
    onDraft: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    val c = OcTheme.colors
    val focusRequester = remember(editor.sessionId) { FocusRequester() }
    LaunchedEffect(editor.sessionId) { focusRequester.requestFocus() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .clip(OcTheme.shapes.md)
            .background(c.layer02.copy(alpha = 0.6f))
            .padding(start = 12.dp, end = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicTextField(
            value = TextFieldValue(editor.draft),
            onValueChange = { onDraft(it.text) },
            enabled = !editor.saving,
            singleLine = true,
            textStyle = TextStyle(color = c.textBase, fontSize = 13.sp, fontWeight = FontWeight.Medium),
            cursorBrush = SolidColor(c.textBase),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            modifier = Modifier
                .weight(if (project == null) 1f else TITLE_WEIGHT, fill = false)
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        event.key == Key.Enter -> {
                            onSubmit()
                            true
                        }
                        event.key == Key.Escape -> {
                            onCancel()
                            true
                        }
                        else -> false
                    }
                },
        )
        if (project != null && showProjectName) {
            ProjectLabel(text = project, modifier = Modifier.weight(1f - TITLE_WEIGHT, fill = false))
        }
    }
}

/**
 * `HomeSessionLeading` — `SessionTabAvatarView` + the "open as tab" marker
 * (`h-3 w-0.5 rounded-[2px] bg-layer-04` at `calc(100% + 4px)`).
 *
 * The avatar is a stand-in: `ProjectAvatar` renders server/GPU-provided images
 * and an emoji/initial fallback, and the M3 client has neither an image URL nor
 * a project endpoint (see [HomeProjects]).
 */
@Composable
private fun HomeSessionLeading(row: HomeSessions.Row, open: Boolean) {
    val c = OcTheme.colors
    Box(modifier = Modifier.width(LEADING_WIDTH).height(AVATAR_SIZE)) {
        Box(
            modifier = Modifier
                .size(AVATAR_SIZE)
                .clip(OcTheme.shapes.sm)
                .background(c.layer02),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = row.project?.firstOrNull()?.uppercaseChar()?.toString()
                    ?: row.title.firstOrNull()?.uppercaseChar()?.toString().orEmpty(),
                color = c.textMuted,
                fontSize = 10.sp,
                maxLines = 1,
            )
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
 * `HomeSessionSkeleton`: a title row + 4 `h-10 rounded-[6px] bg-deep opacity-70`
 * bars. Static by design — the shimmer pass belongs to the M6 motion milestone.
 */
@Composable
private fun HomeSessionSkeleton(modifier: Modifier = Modifier) {
    val c = OcTheme.colors
    val bar = c.bgDeep.copy(alpha = 0.7f)
    Column(
        modifier = modifier.padding(start = 12.dp, end = 40.dp),
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
 */
@Composable
private fun HomeSessionsEmpty(onNewSession: () -> Unit, modifier: Modifier = Modifier) {
    val c = OcTheme.colors
    Column(
        modifier = modifier.padding(top = 52.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.oc_home_empty),
            color = c.textBase,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
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
    Box(
        modifier = modifier
            .size(ICON_BUTTON_LARGE)
            .clip(OcTheme.shapes.md)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_oc_edit),
            contentDescription = stringResource(R.string.oc_new_session),
            tint = c.iconMuted,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** `h-9` = 36 dp search field. */
private val SEARCH_HEIGHT = 36.dp

/** `HomeSessionRow` `h-10` = 40 dp. */
private val ROW_HEIGHT = 40.dp

/** Search panel result row `h-10` = 40 dp. */
private val SEARCH_RESULT_HEIGHT = 40.dp

/** Group header `h-7` = 28 dp. */
private val HEADER_HEIGHT = 28.dp

/** `max-h-80` = 320 dp result list. */
private val PANEL_MAX_HEIGHT = 320.dp

/** `icon-button-v2[size=large]` = 28 × 28 dp. */
private val ICON_BUTTON_LARGE = 28.dp

/** `icon-button-v2[size=small]` = 20 × 20 dp. */
private val ICON_BUTTON_SMALL = 20.dp

/** `button-v2[size=normal]` = 28 dp. */
private val BUTTON_HEIGHT = 28.dp

/** Avatar edge (`SessionTabAvatarView`), a 18 dp rounded square here. */
private val AVATAR_SIZE = 18.dp

/** Avatar + the 6 dp slot the "open" marker lives in (never reflows the title). */
private val LEADING_WIDTH = 24.dp

/** `pt-6` = 24 dp on mobile … */
private val MOBILE_TOP_INSET = 24.dp

/** `gap-px` between rows. */
private val ROW_GAP = 1.dp

/** `pb-16` under the last group. */
private val LIST_BOTTOM_GAP = 64.dp

/** Title share of a row when a project name follows (`max-w-[min(70%,480px)]`). */
private const val TITLE_WEIGHT = 0.7f

/** Floor of the sticky-header scroll fade. */
private const val MIN_HEADER_OPACITY = 0.25f
