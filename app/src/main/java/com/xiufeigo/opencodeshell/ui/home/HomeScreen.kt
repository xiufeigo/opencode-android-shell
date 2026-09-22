package com.xiufeigo.opencodeshell.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiufeigo.opencodeshell.ChatViewModel
import com.xiufeigo.opencodeshell.OpenCodeClient
import com.xiufeigo.opencodeshell.data.HomeProjectRecords
import com.xiufeigo.opencodeshell.data.HomeProjectStore
import com.xiufeigo.opencodeshell.ui.overlay.DialogSize
import com.xiufeigo.opencodeshell.ui.overlay.requireDialogController
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import com.xiufeigo.opencodeshell.ui.theme.ocRaisedShadow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Home (`home.tsx`, `research/02` §1, PLAN §4.1) — the app's landing page.
 *
 * ```
 * m-8 → one card: bg-base + rounded-10 + elevation-raised
 * └─ content max-w-[1080px], centered
 *    ├─ mobile    : project entry + sessions (flex-1) + HomeUtilityNav at the bottom
 *    └─ ≥1024px   : two columns    → Projects 280dp + 32dp gap + Sessions
 * ```
 * The card is the *only* surface: `bg-deep` shows through the 8 dp margin.
 *
 * Data: the sessions list comes from [ChatViewModel.listSessions] (the drawer's
 * existing read) and is refreshed every time Home re-enters composition — i.e.
 * on cold start and after coming back from a session, which is exactly when a
 * title or `updatedAt` can have changed. The 项目 column comes from the
 * **client-local** opened-project store ([HomeProjectStore] →
 * [HomeProjects.openRows]); `GET /project` is read only to enrich those rows
 * (`vcs`, `icon.color`, `sandboxes`) and the session-derived supplement
 * ([HomeProjects.merge]), never to add or reorder a row.
 *
 * 「打开项目」 asks the **server** for a directory ([DirectoryPickerDialog] over
 * `GET /path` + `GET /file`, upstream `home/projects/controller.tsx:49-56`),
 * exactly like the palette's `workspace.new`; the opened directory is persisted
 * through [HomeProjectStore] and becomes the session filter's scope.
 *
 * The screen owns no session subscription: it calls
 * [ChatViewModel.ensureClient] for the REST client and **never** `start()`, so
 * sitting on Home still holds no SSE connection (M1 contract).
 */
@Composable
fun HomeScreen(
    chatViewModel: ChatViewModel?,
    /** Session ids currently open as tabs — drives the row's 2×12 dp marker. */
    openSessionIds: Set<String> = emptySet(),
    onOpenSession: (String) -> Unit = {},
    onNewSession: () -> Unit = {},
    /** Project-row ✎ / add-project pick: open the draft scoped to a directory. */
    onNewSessionInDirectory: (String) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    /**
     * The client-local opened-project store (`oc_home_projects`). `null` in
     * previews/isolated hosts: the column then renders its empty state and the
     * 打开项目 entry has nowhere to persist (there is no server context either).
     */
    homeProjectStore: HomeProjectStore? = null,
    /**
     * WAVE 2.3b P1 (A2 #8): App.kt's `mod+f` hook focuses the sessions search
     * field. Hoisted so the keybind (owned by App.kt) can reach the field
     * (owned by `HomeSessionsColumn`); `remember` a fresh one per Home entry.
     */
    searchFocusRequest: HomeSearchFocusRequest = remember { HomeSearchFocusRequest() },
    /**
     * WAVE 2.3b P1 (A2 #4): delete confirmation + export wiring live here —
     * upstream shows the danger dialog from the sessions controller
     * (`controller.tsx:219-243,309-310`) and exports through
     * `fetchSessionExport` + `saveSessionExport` (`:169-190`).
     *
     * Both default to null (menu items render disabled) so previews and App.kt
     * hosts without a client keep compiling; the real host wires them to
     * `ChatViewModel.deleteSession` + the export chain (see `HomeRowActions`).
     */
    rowActions: HomeRowActions? = null,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    val cardShape = OcTheme.shapes.xl
    // The add-project browser is a layer of the global dialog stack
    // (`home/projects/controller.tsx:49-56` → `dialog-select-directory`), the
    // same host `workspace.new` and the mobile project panel use.
    val dialog = requireDialogController()

    var sessions by remember { mutableStateOf<List<OpenCodeClient.Session>>(emptyList()) }
    // `GET /project`; null = the fetch failed, the column then has no catalogue
    // to enrich its rows from (they keep their derived name and no tint).
    var serverProjects by remember { mutableStateOf<List<OpenCodeClient.Project>?>(null) }
    var loading by remember { mutableStateOf(chatViewModel != null) }
    var selectedProject by remember { mutableStateOf<String?>(null) }
    // The opened-project record: `home/model.ts:20-21` reads
    // `projects.list()` / `projects.recentlyClosed()` from the per-server store.
    val openedProjects = homeProjectStore?.state?.collectAsStateWithLifecycle()?.value ?: EMPTY_PROJECTS
    val configMissing = if (chatViewModel == null) {
        false
    } else {
        chatViewModel.configMissing.collectAsState().value
    }
    // W5-E §5.1/§5.2: the two event-driven revisions Home has to react to.
    // `project.updated` / `session.created` / `session.deleted` arrive on the SSE
    // stream the **session** screen owns (Home deliberately opens none, M1
    // contract) and the revisions are sticky, so re-entering Home — or staying on
    // it while a session runs in another window — re-runs the reads below
    // without a second connection.
    val projectsRevision by (chatViewModel?.projectsRevision ?: NO_REVISION_FLOW).collectAsState()
    val sessionsRevision by (chatViewModel?.sessionsRevision ?: NO_REVISION_FLOW).collectAsState()

    LaunchedEffect(chatViewModel, projectsRevision, sessionsRevision) {
        val viewModel = chatViewModel
        if (viewModel == null) {
            loading = false
            return@LaunchedEffect
        }
        // Home only needs the REST client (`GET /session`, `GET /project`);
        // `ensureClient` is additive and deliberately does not open the SSE stream.
        viewModel.ensureClient()
        // A revision bump is not a first load: only the very first pass (and a
        // manual re-entry with an empty list) shows the spinner, so a
        // `session.created` from another window does not flash the whole column.
        if (sessions.isEmpty()) loading = true
        viewModel.listSessions { list ->
            sessions = list
            loading = false
        }
        viewModel.listProjects { serverProjects = it }
    }

    // The 项目 column: the client-local opened projects, in store order, with the
    // server catalogue used as a lookup only. Archived sessions must not bump a
    // count nor appear anywhere on Home (`session/controller.tsx:193` filters
    // them out of the upstream session list).
    val visibleSessions = remember(sessions) { sessions.filterNot(HomeSessions::isArchived) }
    val projects = remember(openedProjects.list, serverProjects, visibleSessions) {
        HomeProjects.openRows(
            opened = openedProjects.list,
            known = HomeProjects.merge(serverProjects.orEmpty(), visibleSessions),
        )
    }
    val rows = remember(sessions, selectedProject, openedProjects.list, projects) {
        // The selected project scopes the list to its directories — the
        // worktree plus its sandboxes — and a session *below* one of them
        // belongs to the project too (`directories(project)` →
        // `isProjectDirectory`); `null`/empty keeps every project's sessions.
        // The sandboxes come from the column's own rows, which is where
        // `GET /project`'s `sandboxes` reach the filter (`directoriesFor`).
        HomeSessions.rows(
            sessions,
            directories = HomeProjects.directoriesFor(selectedProject, openedProjects.list, known = projects),
        )
    }
    val recentlyClosed = remember(openedProjects.recentlyClosed) {
        HomeProjects.closedRows(openedProjects.recentlyClosed)
    }

    // `projects.open(directory)` + `projects.touch(directory)`
    // (`home/model.ts:49-52,56-66`): every directory the user picks or opens a
    // session in is opened — which also takes it out of 最近关闭 — and touched,
    // which is the MRU marker `newSessionProject()` reads. Only an **add** moves
    // the selection: upstream `openProjectSession` registers the directory
    // without selecting it (`home/model.ts:101-120` ends with `setSelection`).
    fun registerProject(directory: String) {
        homeProjectStore?.open(directory)
        homeProjectStore?.touch(directory)
    }

    // `home.project.add` (`home/model.ts:101-120`): the picked directory is
    // opened, touched and becomes the selection. `openProject` is also what a
    // 最近关闭 row does upstream (`HomeRecentlyClosedRow` → `onAddProjects`).
    fun openProject(directory: String) {
        registerProject(directory)
        selectedProject = directory
    }

    // `projects.close()` + `closeHomeProject` (`shell/layout/helpers.ts:62-71`):
    // closing the *selected* project also clears the selection, so the sessions
    // column goes back to the unscoped list instead of showing a scope with no
    // row to clear it from.
    fun closeProject(directory: String) {
        homeProjectStore?.close(directory)
        if (selectedProject == directory) selectedProject = null
    }

    // `openProjectSession` (`home/model.ts:56-66`) registers the directory the
    // opened session lives in, so a session opened from Home appears in the
    // sidebar. The rows carry the session id, so the lookup happens here, over
    // every listed session — a scoped list is a subset of it.
    val directoryById = remember(sessions) {
        sessions.mapNotNull { session ->
            session.directory?.takeIf { it.isNotBlank() }?.let { session.id to it }
        }.toMap()
    }
    val onOpenSessionRow: (String) -> Unit = { id ->
        directoryById[id]?.let { registerProject(it) }
        onOpenSession(id)
    }

    // `home.project.choose` (`home/projects/controller.tsx:49-56`) →
    // `pickDirectory({ server })`: the browser is the **server's** filesystem
    // (`GET /file?path=`, `GET /path` for the starting point), which is what
    // [DirectoryPickerDialog] renders — a device-side SAF tree picker is the
    // wrong picker for a remote server. Null client (previews, isolated hosts):
    // there is no server to browse, so the entry has nothing to open.
    val onAddProject: () -> Unit = {
        val viewModel = chatViewModel
        if (viewModel != null) {
            dialog.show(DialogSize.FIT) { controller ->
                DirectoryPickerDialog(
                    chatViewModel = viewModel,
                    initialPath = null,
                    onSelect = { directory ->
                        controller.close()
                        openProject(directory)
                    },
                    onClose = { controller.close() },
                )
            }
        }
    }

    // Every new-session entry inside the sessions column (the floating ✎ and the
    // empty state's button) drafts in the **selected** project when there is one:
    // upstream `home.project.openNewSession` (`home/model.ts:121-126`) uses
    // `newSessionProject()` = `selectedProject() ?? …`. Unscoped, it stays the
    // nav-level "new session", which is what Home did before any selection.
    val selectedDirectory = selectedProject
    val onNewSessionScoped: () -> Unit =
        if (selectedDirectory == null) onNewSession else ({ onNewSessionInDirectory(selectedDirectory) })
    // `home/sessions/search.ts:45-53`: the placeholder names the scope while a
    // project is selected; `session/controller.tsx:256` hides the (then
    // redundant) per-row project label — `showProjectName: () => !home.project.selected()`.
    val searchScope = HomeSessions.placeholderScope(selectedDirectory)
    val showProjectName = selectedDirectory == null

    // WAVE 2.3b P1 (A2 #9): the search filters the FULL list
    // (`controller.tsx:253` `searchRecords: allRecords`), not the capped rows.
    val searchDirectories = remember(selectedDirectory, openedProjects.list, projects) {
        if (selectedDirectory == null) emptyList()
        else HomeProjects.directoriesFor(selectedDirectory, openedProjects.list, known = projects)
    }
    // WAVE 2.3b P1 (A2 #4): the menu's rename/export/delete host. Null actions
    // (previews, hosts without a client) disable the matching menu items.
    // Rename/delete refresh through the SSE revision when the host bumps it
    // (delete/archive do); a host that reports success without bumping still
    // refreshes because Home re-reads on the next entry regardless.
    val exportSaver = rememberHomeRowExportSaver()
    var pendingDelete by remember { mutableStateOf<HomeSessions.Row?>(null) }
    val onRenameRow: ((String, String, (Boolean) -> Unit) -> Unit)? =
        rowActions?.let { actions -> { id, title, onResult -> actions.rename(id, title, onResult) } }
    val onExportRow: ((HomeSessions.Row) -> Unit)? =
        rowActions?.let { actions ->
            { row -> exportSaver.export(row) { actions.export(row) } }
        }
    val onDeleteRow: ((HomeSessions.Row) -> Unit)? =
        rowActions?.let { { row -> pendingDelete = row } }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(c.bgDeep)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(CARD_MARGIN)) {
            val wide = maxWidth >= LG_BREAKPOINT
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .ocRaisedShadow(cardShape)
                    .clip(cardShape)
                    .background(c.bgBase),
            ) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .widthIn(max = CONTENT_MAX_WIDTH)
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .padding(horizontal = if (wide) 24.dp else 12.dp),
                ) {
                    if (wide) {
                        Row(Modifier.fillMaxSize()) {
                            HomeProjectsColumn(
                                projects = projects,
                                recentlyClosed = recentlyClosed,
                                selected = selectedProject,
                                onSelect = { project ->
                                    // `toggleHomeProjectSelection`
                                    // (`shell/layout/helpers.ts:53-60`): selecting a
                                    // project whose directory is already selected
                                    // clears the selection; anything else selects
                                    // (there is only one server here, so the
                                    // cross-server branch has no counterpart).
                                    // The pick also `touch`es it: selection is the
                                    // strongest signal of "most recently used"
                                    // (`projects.touch` → `newSessionProject()`).
                                    homeProjectStore?.touch(project.path)
                                    selectedProject =
                                        if (selectedProject == project.path) null else project.path
                                },
                                // `home.project.openNewSession` (`home/model.ts:49-54`):
                                // the ✎ opens the row, touches it and drafts in it.
                                onNewSession = { project ->
                                    homeProjectStore?.open(project.path)
                                    homeProjectStore?.touch(project.path)
                                    onNewSessionInDirectory(project.path)
                                },
                                onCloseProject = { project -> closeProject(project.path) },
                                onReopenProject = { project -> openProject(project.path) },
                                onAddProject = onAddProject,
                                onOpenSettings = onOpenSettings,
                                modifier = Modifier.width(PROJECTS_WIDTH),
                            )
                            Spacer(Modifier.width(COLUMN_GAP))
                            HomeSessionsColumn(
                                rows = rows,
                                loading = loading,
                                configMissing = configMissing,
                                searchScope = searchScope,
                                showProjectName = showProjectName,
                                openSessionIds = openSessionIds,
                                onOpenSession = onOpenSessionRow,
                                onNewSession = onNewSessionScoped,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                topInset = DESKTOP_TOP_INSET,
                                searchSessions = sessions,
                                searchFocusRequest = searchFocusRequest,
                                onRenameSession = onRenameRow,
                                onExportSession = onExportRow,
                                onDeleteSession = onDeleteRow,
                            )
                        }
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            // `home/route.tsx:27-31`: below the two-column
                            // breakpoint the same column is hosted as a
                            // dropdown/list-panel at the top of the card, so the
                            // 项目 list and 打开项目 are reachable on a phone.
                            HomeProjectsDropdown(
                                projects = projects,
                                recentlyClosed = recentlyClosed,
                                selected = selectedProject,
                                onSelect = { project ->
                                    // Same `toggleHomeProjectSelection` + `touch`
                                    // pair as the desktop column.
                                    homeProjectStore?.touch(project.path)
                                    selectedProject =
                                        if (selectedProject == project.path) null else project.path
                                },
                                onNewSession = { project ->
                                    homeProjectStore?.open(project.path)
                                    homeProjectStore?.touch(project.path)
                                    onNewSessionInDirectory(project.path)
                                },
                                onCloseProject = { project -> closeProject(project.path) },
                                onReopenProject = { project -> openProject(project.path) },
                                onAddProject = onAddProject,
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                            HomeSessionsColumn(
                                rows = rows,
                                loading = loading,
                                configMissing = configMissing,
                                searchScope = searchScope,
                                showProjectName = showProjectName,
                                openSessionIds = openSessionIds,
                                onOpenSession = onOpenSessionRow,
                                onNewSession = onNewSessionScoped,
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                searchSessions = sessions,
                                searchFocusRequest = searchFocusRequest,
                                onRenameSession = onRenameRow,
                                onExportSession = onExportRow,
                                onDeleteSession = onDeleteRow,
                            )
                            // `HomeUtilityNav class="flex lg:hidden"` — the
                            // mobile single column's last grid row.
                            HomeUtilityNav(
                                onOpenSettings = onOpenSettings,
                                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                            )
                        }
                    }
                }
            }
            // WAVE 2.3b P1 (A2 #4): the delete danger dialog
            // (`controller.tsx:219-243`): title + `session.delete.confirm`
            // + Cancel / danger Delete. Confirm deletes through [HomeRowActions]
            // and drops the row on the next read; cancel just closes.
            pendingDelete?.let { row ->
                HomeDeleteDialog(
                    title = row.title,
                    onDismiss = { pendingDelete = null },
                    onConfirm = {
                        val target = pendingDelete
                        pendingDelete = null
                        if (target != null) rowActions?.delete(target) { _ -> }
                    },
                )
            }
        }
    }
}

/** `m-2` = 8 dp around the page card. */
private val CARD_MARGIN = 8.dp

/** `max-w-[1080px]` on the centred content grid. */
private val CONTENT_MAX_WIDTH = 1080.dp

/** `lg:` breakpoint of the nav shell (`research/02` §0.0/§1.1). */
private val LG_BREAKPOINT = 1024.dp

/** `lg:grid-cols-[280px_minmax(0,720px)]` — the projects column. */
private val PROJECTS_WIDTH = 280.dp

/** `lg:gap-8` = 32 dp between the columns. */
private val COLUMN_GAP = 32.dp

/** `lg:pt-12` = 48 dp above the sessions column on desktop. */
private val DESKTOP_TOP_INSET = 48.dp

/**
 * Stand-in revision source for callers without a [ChatViewModel] (previews,
 * isolated tests): a constant flow, so the `collectAsState` above stays
 * unconditional and the effect keys keep their identity.
 */
private val NO_REVISION_FLOW = MutableStateFlow(0)

/**
 * The record an absent [HomeProjectStore] reads as (previews, isolated hosts):
 * no opened projects and nothing in 最近关闭, so the column shows its empty state
 * — the same thing a freshly installed app shows.
 */
private val EMPTY_PROJECTS = HomeProjectRecords.State()
