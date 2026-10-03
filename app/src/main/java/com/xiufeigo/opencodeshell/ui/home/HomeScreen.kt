package com.xiufeigo.opencodeshell.ui.home

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiufeigo.opencodeshell.ChatViewModel
import com.xiufeigo.opencodeshell.ChatStore
import com.xiufeigo.opencodeshell.OpenCodeClient
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.SessionAsks
import com.xiufeigo.opencodeshell.SessionUnread
import com.xiufeigo.opencodeshell.data.HomeProjectRecords
import com.xiufeigo.opencodeshell.data.HomeProjectStore
import com.xiufeigo.opencodeshell.data.ProjectPaths
import com.xiufeigo.opencodeshell.notify.AppNotification
import com.xiufeigo.opencodeshell.v2.SessionRenameOutcome
import com.xiufeigo.opencodeshell.v2.TransportFailure
import com.xiufeigo.opencodeshell.ui.nav.SessionDotSources
import com.xiufeigo.opencodeshell.ui.overlay.DialogSize
import com.xiufeigo.opencodeshell.ui.overlay.requireDialogController
import com.xiufeigo.opencodeshell.ui.renameFailureText
import com.xiufeigo.opencodeshell.ui.renameFailureToast
import com.xiufeigo.opencodeshell.ui.session.drawer.RenameFailureDialog
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import com.xiufeigo.opencodeshell.ui.theme.ocRaisedShadow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 * (display `name`, `vcs`, `icon.color`, `sandboxes`) and the session-derived supplement
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
    // v59: the ids this screen has deleted itself (see [dropDeletedSessions]). A
    // read that was already in flight when the `DELETE` went out answers with
    // the row still on it, and adopting that read would resurrect the row for a
    // whole tick — measured as 「删除会话后列表行滞留数秒」
    // (`research/v59_verify/5560`).
    var deletedSessions by remember { mutableStateOf<Set<String>>(emptySet()) }
    // v45 P0 「改名落库延迟」: the titles a rename through this screen has
    // already had accepted by the server, keyed by session id. Upstream drops
    // its `session.update` straight into the query cache
    // (`home/sessions/controller.tsx:141-167`: `invalidateQueries(["home-
    // sessions"])`), so the row re-reads the new name in the same frame; here
    // the only read is the catalogue poll, so without a local mirror the row
    // kept printing the old title after the editor had closed — the rename
    // looked like it had not landed, and re-opening the editor offered the
    // *old* name again (`research/v45_verify/tC/v45_16`, and the 90 s of
    // polling behind it).
    //
    // Only an **accepted** title enters this map (the server's own answer, the
    // same answer the editor closes on), and every catalogue read prunes the
    // entries the server now reports itself — so another client's rename, or a
    // title the server normalised, wins as soon as a read carries it.
    var renamedTitles by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    // True once a list read has confirmed rows (or a genuine zero): before
    // that the initial empty list is "no data yet", never the empty state.
    var firstReadOk by remember { mutableStateOf(false) }
    // The probe verdict behind the latest failed read: ProbePending means a
    // verdict is still in flight, so the empty state would be premature.
    val browseFailure by (chatViewModel?.browseFailure ?: NO_FAILURE_FLOW).collectAsState()
    // The last catalogue read failed ([ChatViewModel.sessionsLoadFailed]): with
    // no rows the column renders its error/retry state instead of the skeleton
    // or the empty state (see [HomeSessions.shouldShowLoadError]).
    val sessionsFailed by (chatViewModel?.sessionsLoadFailed ?: NO_SESSIONS_FAILED_FLOW).collectAsState()
    var loading by remember { mutableStateOf(chatViewModel != null) }
    // `GET /project`; null = the fetch failed, the column then has no catalogue
    // to enrich its rows from (they keep their derived name and no tint).
    var serverProjects by remember { mutableStateOf<List<OpenCodeClient.Project>?>(null) }
    // The catalogue's display names by match key (`name` half of upstream
    // `displayName`): the v1 read above drops the server's `name`, so these
    // come from the bare v2 catalogue instead ([HomeProjectApi.loadNames],
    // best-effort — a failed read keeps the folder-name fallback).
    var serverNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
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
    // v54 P1 「行标题停留在 Untitled」: the title of the session **this device is
    // tracking**, which the server rewrites (opencode titles a session off its
    // first message) while the tab strip already shows the new one. Home's rows
    // only re-read on the two revisions and the 45 s freshness tick, so a title
    // that changed in between printed 「Untitled」 for up to a full tick — the
    // measured device had been sitting on Home for minutes
    // (`research/v54_verify/tE/v54_16…22`: the row still read `Untitled` while
    // the tab above it read `E2ECHK5554_v54_msgs`).
    //
    // It is only a **key** on the freshness loop below, never a source of truth:
    // the rows still come from `listSessions`, so a title this client has not
    // confirmed can only make Home ask again sooner.
    val trackedTitle by (chatViewModel?.currentTitle ?: NO_TITLE_FLOW).collectAsState()
    // Declared before the reads below: the effect fetches the display names
    // through this seam (see `serverNames`).
    //
    // The **same** [HomeProjectStore] the column observes is handed in, not a
    // second one: the editor's local-meta write (`sync.project.meta`,
    // `project-model.ts:41-46`) has to publish through the instance the column
    // collects, or a save would land in a store nobody is watching and the row
    // would not move until the next cold start. `App.kt` owns the instance
    // (`remember(context, serverKey)`) and this parameter is that handle.
    val projectApi = rememberHomeProjectApi(homeProjectStore)

    // A-3: the clock the session rows' relative labels (and the Today / Yesterday
    // / Older grouping) are measured against. A row is composed once and then
    // skipped — its only inputs are its own fields, which do not move as time
    // passes — so a label composed while a session was「just now」would stay「just
    // now」for as long as the row lived, and a session touched at 23:59 would stay
    // in Today past midnight. The tick is the palette layer's
    // (`PaletteDialogLayer`'s `paletteNow`, the drawer's `drawerNow`), and its
    // lifetime *is* the visibility gate: Home is composed exactly while Home is on
    // screen, so leaving the route cancels the loop.
    var homeNow by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            homeNow = System.currentTimeMillis()
            delay(HOME_CLOCK_TICK_MS)
        }
    }

    // The catalogue read the entry effect runs on every revision — and the
    // error state's retry re-runs on demand. One function so the two cannot
    // drift apart (the rename-mirror adoption, the confirmed-read mark, the
    // spinner reset). It is a plain local function rather than a new effect
    // key: revision + configMissing flows re-drive through the collector, not
    // new keys ([HomeColdStartTest.effectKeysStayOnTheTwoRevisions]).
    fun refreshSessionList() {
        val viewModel = chatViewModel ?: return
        // Home only needs the REST client (`GET /session`, `GET /project`);
        // `ensureClient` is additive and deliberately does not open the SSE stream.
        viewModel.ensureClient()
        // A revision bump is not a first load: only the very first pass (and a
        // manual re-entry with an empty list) shows the spinner, so a
        // `session.created` from another window does not flash the whole column.
        if (sessions.isEmpty()) loading = true
        viewModel.listSessions { list ->
            // The callback carries the freshest rows the ViewModel knows: a
            // failed read answers the last list it did read, never a spurious
            // empty (`ChatViewModel.listSessions`). So the list is adopted
            // unconditionally — the `adb reverse` outage that re-created this
            // screen's state mid-outage no longer leaves the column on
            // 「Nothing here yet」 over a project that has sessions — while only
            // a *confirmed* read marks the state as "genuinely zero", which is
            // what keeps that empty state from latching over a live list.
            // v47 P1: the adoption keeps a mirrored-but-missing row
            // ([mergeCatalogueRead]) — a read that lands between the accepted
            // `PATCH` and the server echoing it must not drop the renamed row
            // for a cycle — and the prune below runs against the merged list,
            // so the retained row keeps its mirror until a read confirms it.
            val merged = mergeCatalogueRead(sessions, list, renamedTitles)
            sessions = merged
            // A read that already carries a renamed title retires the local
            // mirror for it; one that does not keeps it, so a slow read cannot
            // un-rename a row the server accepted — and one that no longer
            // carries the id at all drops its dead mirror ([pruneRenamedTitles]).
            if (renamedTitles.isNotEmpty()) {
                renamedTitles = pruneRenamedTitles(renamedTitles, merged.associate { it.id to it.title })
            }
            if (!viewModel.sessionsLoadFailed.value) firstReadOk = true
            loading = false
        }
    }
    // The error state's retry: the read flips `loading` back to the skeleton
    // while it is in flight — never the error over a live read.
    val retrySessions: () -> Unit = { refreshSessionList() }

    LaunchedEffect(chatViewModel, projectsRevision, sessionsRevision) {
        val viewModel = chatViewModel
        if (viewModel == null) {
            loading = false
            return@LaunchedEffect
        }
        refreshSessionList()
        viewModel.listProjects(onResult = { serverProjects = it })
        // The server-set names (`displayName`'s `project.name`): read from the
        // bare v2 catalogue because the v1 read above drops them. Best-effort
        // (empty map on failure) and keyed by match key, so an MSYS
        // `/mnt/c/…` canonical still names a `C:\…` row.
        serverNames = withContext(Dispatchers.IO) { projectApi.loadNames() }
    }

    /**
     * The session list's freshness tick — the read that makes a **silent**
     * connection loss heal without a route change.
     *
     * Every other re-read on this screen is event-driven (the two revisions
     * above) or failure-driven (`ChatViewModel.scheduleSessionListRetry`, armed
     * by a read that failed). Both need something to happen first, and the
     * measured v36 tB report is the case where nothing did: `adb reverse` was
     * removed while Home sat idle — no stream is open here (M1 contract), no
     * frame arrived, and no read was in flight — so nothing failed and no retry
     * was ever armed. The mapping came back, a session created in the meantime
     * never appeared (a pull-down does nothing either: Home has no
     * pull-to-refresh path at all), and only leaving and re-entering Home
     * re-read the list.
     *
     * The tick is the missing "ask again anyway": one `GET /api/session` per
     * [HOME_SESSIONS_FRESHNESS_MS] while Home is composed, so a recovery is
     * picked up within that window instead of at the next route change. Its
     * lifetime *is* the visibility gate (leaving Home cancels the loop), and a
     * read that fails while the connection is still down arms the ViewModel's
     * own backoff chain, so the two together keep asking until the list is
     * real again. The revision keys are deliberately **not** part of this
     * effect: a bump already re-reads through the effect above, and restarting
     * this timer with it would only delay the next silent check.
     *
     * v54 P1: the **tracked title** is the one key that does belong here, and
     * it reads straight away instead of re-arming the wait — the server renames
     * a session off its first message while another tab of this shell is
     * tracking it, and Home's rows printed the stale 「Untitled」 for a whole
     * 45 s tick after the tab strip above had already moved
     * (`research/v54_verify/tE/v54_16…22`). Cancelling and restarting the loop
     * on a title change would have postponed that read instead of making it.
     */
    LaunchedEffect(chatViewModel, trackedTitle) {
        val viewModel = chatViewModel ?: return@LaunchedEffect
        // The entry read above already ran, so the first pass waits like every
        // other one; only a **restart** (a tracked title that moved) skips the
        // wait, and `primed` is what tells the two apart.
        var lastTitle: String? = null
        var primed = false
        while (true) {
            val titleChanged = primed && trackedTitle != lastTitle
            lastTitle = trackedTitle
            primed = true
            if (!titleChanged) delay(HOME_SESSIONS_FRESHNESS_MS)
            viewModel.listSessions { list ->
                // Same contract as the entry read: a failed read answers the
                // last rows this client read, never a spurious empty, and only
                // a confirmed read marks the state as genuinely zero.
                // v47 P1: same merged adoption as the entry read, or a tick
                // that lands inside the rename window drops the renamed row
                // for a cycle ([mergeCatalogueRead]).
                val tickMerged = mergeCatalogueRead(sessions, list, renamedTitles)
                sessions = tickMerged
                // Same mirror hygiene as the entry read, or a rename accepted
                // between ticks keeps a stale mirror ([pruneRenamedTitles]).
                if (renamedTitles.isNotEmpty()) {
                    renamedTitles = pruneRenamedTitles(renamedTitles, tickMerged.associate { it.id to it.title })
                }
                if (!viewModel.sessionsLoadFailed.value) firstReadOk = true
            }
        }
    }

    // The 项目 column: the client-local opened projects, in store order, with the
    // server catalogue used as a lookup only. The store's recently-closed stack
    // rides along as a tombstone (`home/projects/controller.tsx` close/remove
    // semantics): `GET /project` is persistent and a stale spelling of a closed
    // directory can linger in the record, but 「Close」 must stay closed —
    // nothing re-renders it until `open` takes it out of 最近关闭 again.
    // Archived sessions must not bump a count nor appear anywhere on Home
    // (`session/controller.tsx:193` filters them out of the upstream session
    // list).
    // The titles the server has confirmed sit on top of the last read's rows.
    val titledSessions = remember(sessions, renamedTitles) {
        // The accepted titles on top of the last read's rows — ids, times and
        // directories untouched, so a rename cannot move or blank a sibling row
        // ([applyRenamedTitles]).
        applyRenamedTitles(sessions, renamedTitles)
    }
    val visibleSessions = remember(titledSessions, deletedSessions) {
        // v59: 删除后行滞留数秒（`research/v59_verify/5560`）——本地那次
        // `filterNot` 只挡得住「删除之后才发起的读取」。删除按钮点下去时早有一读
        // 在路上（入场读 / 30 s 的 tick），它的应答里带着那一行，落在删除之后
        // 就把行又请回来了；下一次读取（或者任何一次交互）才把它读走，于是行
        // 留在列表里几秒。改名有 [mergeCatalogueRead] 的镜像兜底，删除原来没有
        // 同款的一层。墓碑记在 [deletedSessions]：id 不会被复用，所以它活到
        // 这个页面为止就够了，不必再去某次读取上退休（读取路径一个字没动）。
        dropDeletedSessions(titledSessions.filterNot(HomeSessions::isArchived), deletedSessions)
    }
    val projects = remember(openedProjects.list, openedProjects.recentlyClosed, serverProjects, visibleSessions, serverNames) {
        HomeProjects.openRows(
            opened = openedProjects.list,
            known = HomeProjects.merge(serverProjects.orEmpty(), visibleSessions, names = serverNames),
            closed = openedProjects.recentlyClosed,
            names = serverNames,
        )
    }
    val rows = remember(titledSessions, selectedProject, openedProjects.list, projects, serverNames) {
        // The selected project scopes the list to its directories — the
        // worktree plus its sandboxes — and a session *below* one of them
        // belongs to the project too (`directories(project)` →
        // `isProjectDirectory`); `null`/empty keeps every project's sessions.
        // The sandboxes come from the column's own rows, which is where
        // `GET /project`'s `sandboxes` reach the filter (`directoriesFor`).
        HomeSessions.rows(
            titledSessions,
            directories = HomeProjects.directoriesFor(selectedProject, openedProjects.list, known = projects),
            projectNames = serverNames,
            // B-14: the row's project icon (`icon.color` / `icon.override`), from
            // the same column rows the label is resolved against.
            projects = projects,
        )
    }
    val recentlyClosed = remember(openedProjects.recentlyClosed, serverNames) {
        HomeProjects.closedRows(openedProjects.recentlyClosed, names = serverNames)
    }

    // `projects.open(directory)` + `projects.touch(directory)`
    // (`home/model.ts:49-52,56-66`): every directory the user picks or opens a
    // session in is opened — which also takes it out of 最近关闭 — and touched,
    // which is the MRU marker `newSessionProject()` reads. Only an **add** moves
    // the selection: upstream `openProjectSession` registers the directory
    // without selecting it (`home/model.ts:101-120` ends with `setSelection`).
    fun registerProject(picked: String) {
        // v52 tD: the directory reaches this store under the spelling it was
        // picked in (`/mnt/c/…`, `//host/share/…`), while a registered project
        // is known by the spelling `GET /project` catalogued it under. Adopt the
        // registered one first ([HomeProjects.canonicalFor]'s rule), so an entry
        // created after a 「移动到…」 merges onto the existing project row
        // instead of adding a second row for one project.
        val directory = serverProjects.orEmpty()
            .firstOrNull { ProjectPaths.sameDirectory(it.worktree, picked) }
            ?.worktree
            ?.takeIf { it.isNotBlank() }
            ?: picked
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
        // Spellings drift (`C:\repo` vs the `C:/repo` the picker wrote), so
        // the selection clears on identity, not on the verbatim string —
        // otherwise closing the selected project leaves a scope with no row.
        if (selectedProject?.let { ProjectPaths.matchKey(it) == ProjectPaths.matchKey(directory) } == true) {
            selectedProject = null
        }
    }

    // `openProjectSession` (`home/model.ts:56-66`) registers the directory the
    // opened session lives in, so a session opened from Home appears in the
    // sidebar. The rows carry the session id, so the lookup happens here, over
    // every listed session — a scoped list is a subset of it.
    val directoryById = remember(titledSessions) {
        titledSessions.mapNotNull { session ->
            // v54 P1: the subpath is part of where the session lives
            // ([HomeSessions.sessionDirectory]), so opening it registers the
            // subpath project the window was bound to — and the 项目 row's unseen
            // count attributes its notifications the same way.
            HomeSessions.sessionDirectory(session)
                ?.takeIf { it.isNotBlank() }
                ?.let { session.id to it }
        }.toMap()
    }
    val onOpenSessionRow: (String) -> Unit = { id ->
        directoryById[id]?.let { registerProject(it) }
        onOpenSession(id)
    }

    // ---- A-3: the project row's unseen count and its two new actions --------
    // 「清除通知」 (`sidebar.project.clearNotifications`,
    // `home/projects/controller.tsx:145-150`) is per project, so the feed has to
    // be attributed to one: a notification belongs to a project when its
    // session's directory is that project's worktree, one of its sandboxes, or
    // nested inside either — the same rule the sessions column filters by
    // (`HomeSessions.containsAny` over `HomeProjects.directoriesFor`). A
    // notification whose session has no directory belongs to no project and is
    // never counted (nor cleared) by a row.
    val notifications by (chatViewModel?.notifications ?: NO_NOTIFICATIONS_FLOW)
        .collectAsStateWithLifecycle()

    // ---- A-2: the session rows' dot / marquee ---------------------------------
    // The v28 device run: Home's rows were the one session list with no
    // indicator slot, so a permission the dock was showing lit the strip (or
    // nothing at all, on a phone) and never the row the user was looking at —
    // upstream's `HomeSessionRowUI` carries `SessionTabAvatarView`
    // (`home/sessions/view.tsx:219-228`), the same unread dot / marquee the tab
    // strip draws.
    //
    // One `SessionDotSources` for every row, assembled from the same flows the
    // strip and the drawer read (`ui/App.kt`), so the three surfaces cannot
    // disagree: the location-wide pending asks, the in-app feed, the server
    // row's watermark, and the tracked session's own dock list / run status.
    val sessionUnread by (chatViewModel?.sessionUnread ?: NO_SESSION_UNREAD_FLOW)
        .collectAsStateWithLifecycle()
    val sessionAsks by (chatViewModel?.sessionAsks ?: NO_SESSION_ASKS_FLOW)
        .collectAsStateWithLifecycle()
    val pendingPermission by (chatViewModel?.pendingPermission ?: NO_PENDING_PERMISSION_FLOW)
        .collectAsStateWithLifecycle()
    val pendingForm by (chatViewModel?.pendingForm ?: NO_PENDING_FORM_FLOW)
        .collectAsStateWithLifecycle()
    val autoApprovePermissions by (chatViewModel?.autoApprovePermissions ?: NO_AUTO_APPROVE_FLOW)
        .collectAsStateWithLifecycle()
    // Home has no session screen mounted, so the run status comes from the
    // ViewModel's own combined state; mapped down and de-duplicated like the
    // strip's (`ui/App.kt`), or every streamed token would recompose the list.
    val trackedRunStatusFlow = remember(chatViewModel) {
        chatViewModel?.state?.map { it.runStatus }?.distinctUntilChanged() ?: NO_RUN_STATUS_FLOW
    }
    val trackedRunStatus by trackedRunStatusFlow.collectAsStateWithLifecycle(ChatStore.RunStatus.IDLE)
    val dotSources = SessionDotSources(
        trackedSessionID = chatViewModel?.sessionID,
        trackedPermission = pendingPermission != null,
        trackedForm = pendingForm != null,
        asks = sessionAsks,
        autoApprove = autoApprovePermissions,
        notifications = notifications,
        unread = sessionUnread,
        trackedRunning = trackedRunStatus == ChatStore.RunStatus.RUNNING,
    )
    val rowUnread: (String) -> Boolean = { id -> dotSources.unread(id) }
    // `badge.loading` is already `!needsAttention && running`, so a row waiting on
    // the user shows the dot and never the marquee — upstream's precedence.
    val rowLoading: (String) -> Boolean = { id -> dotSources.badge(id).loading }
    val projectUnseen: (HomeProjects.Project) -> Int = { project ->
        projectUnseenNotifications(
            notifications = notifications,
            directoryBySession = directoryById,
            directories = HomeProjects.directoriesFor(
                project.path,
                openedProjects.list,
                known = projects,
            ),
        ).size
    }
    val onClearProjectNotifications: (HomeProjects.Project) -> Unit = { project ->
        val viewModel = chatViewModel
        if (viewModel != null) {
            // Marking the owning sessions viewed is what the count above reads,
            // so the row goes disabled in the same frame the toast would have
            // said it did. `markNotificationsViewed` acks a whole session (it is
            // the same call the session screen makes on entry), which is exactly
            // "this project has been seen".
            projectUnseenNotifications(
                notifications = notifications,
                directoryBySession = directoryById,
                directories = HomeProjects.directoriesFor(
                    project.path,
                    openedProjects.list,
                    known = projects,
                ),
            ).mapNotNull { it.sessionID }.distinct().forEach(viewModel::markNotificationsViewed)
        }
    }
    // 「导入会话」 (`controller.tsx:109-135`) ends in
    // `home.project.openProjectSession(conn, project.worktree, imported)`
    // (`home/model.ts:56-66`): register the directory the imported session lives
    // in, then open it. The picker/SAF read/`POST …/session/import` are
    // self-serve (`rememberHomeProjectImport`), so all this host adds is that
    // final pair. The callback carries only the id, and the import route has
    // already committed the session server-side, so the directory is read back
    // from the session list; a session that is not listed yet still opens
    // (registering is best-effort, opening is not).
    val onSessionImported: (String) -> Unit = { id ->
        val viewModel = chatViewModel
        if (viewModel == null) {
            onOpenSession(id)
        } else {
            viewModel.listSessions { list ->
                // Same contract as the revision read above: a failed read
                // answers the last good rows, never a spurious empty, so
                // adopting it cannot wipe the column.
                // v47 P1: merged like every other adoption, so an import that
                // lands inside the rename window keeps the renamed row too.
                sessions = mergeCatalogueRead(sessions, list, renamedTitles)
                list.firstOrNull { it.id == id }?.let { HomeSessions.sessionDirectory(it) }
                    ?.takeIf { it.isNotBlank() }
                    ?.let { registerProject(it) }
                onOpenSession(id)
            }
        }
    }

    // `home.project.choose` (`home/projects/controller.tsx:49-56`) →
    // `pickDirectory({ server })`: the browser is the **server's** filesystem
    // (`GET /file?path=`, `GET /path` for the starting point), which is what
    // [DirectoryPickerDialog] renders — a device-side SAF tree picker is the
    // wrong picker for a remote server. Null client (previews, isolated hosts):
    // there is no server to browse, so the entry has nothing to open.
    //
    // Declared before the add entry because the add's confirmation launches on
    // it (see `onSelect`): a local `val` is only visible to the lambdas below it.
    val projectActionScope = rememberCoroutineScope()
    val onAddProject: () -> Unit = {
        val viewModel = chatViewModel
        if (viewModel != null) {
            dialog.show(DialogSize.FIT) { controller ->
                DirectoryPickerDialog(
                    chatViewModel = viewModel,
                    // Re-open where the user left off: the MRU record every
                    // pick / open / session-open touches (never a merely
                    // browsed directory — see `onNavigate` below). Null (fresh
                    // install or a refused system start) keeps the probe
                    // behaviour.
                    initialPath = pickerRememberedStart(openedProjects.lastProject),
                    onSelect = { directory ->
                        controller.close()
                        openProject(directory)
                        // The registration half of upstream `home.project.add`
                        // (`home/model.ts:101-120`): the directory is already a
                        // row (the client-local store above is what renders it),
                        // and the probe that follows is what makes the *server*
                        // know it too — `GET /api/project` then carries its
                        // vcs/name/icon, and `NewSessionScreen.pickProject` can
                        // match a draft scoped to it instead of silently
                        // falling back to the most recently updated project.
                        // Best-effort and off-main (upstream's
                        // `.catch(() => undefined)`), then the two catalogue
                        // reads the new row is decorated from.
                        projectActionScope.launch {
                            withContext(Dispatchers.IO) { projectApi.register(directory) }
                            serverNames = withContext(Dispatchers.IO) { projectApi.loadNames() }
                            // A failed re-read answers null: keep the rows the
                            // column already has instead of stripping every
                            // row's enrichment (the sessions guard's rule).
                            viewModel.listProjects { list -> if (list != null) serverProjects = list }
                        }
                    },
                    onClose = { controller.close() },
                    onOpenServers = {
                        controller.close()
                        onOpenSettings()
                    },
                    // Deliberately no `onNavigate`: browsing is not a project
                    // fact. Upstream's picker only ever *reads* a start
                    // directory (`dialog.tsx:82-89`) and its
                    // `projects.touch` runs from the committed actions alone
                    // (`home/model.ts:49-52,101-120`). Wiring navigation into
                    // the MRU made a cancelled picker re-scope the next draft
                    // (device run: the new-session page opened on the last
                    // *browsed* directory, rendered 「本地 / 无 Git」 with no
                    // worktrees, instead of the project the user had been in).
                    // The picker's remembered start is the MRU
                    // ([pickerRememberedStart]) — set by a pick, an open or a
                    // session open, never by a directory the user merely looked
                    // at.
                )
            }
        }
    }

    // Row-menu 「Edit project…」 (`home/projects/controller.tsx:135-138`) and
    // 「Refresh worktrees」 (`POST /api/worktree/refresh`, `worktree.ts:64-76`):
    // both ride the self-serve catalog-write seam ([HomeProjectApi] /
    // `V2ApiCatalogExtras.updateProject` + `refreshWorktrees`) — no host
    // callback needed. Failures toast the fault's real reason ([projectFaultText]);
    // the edit dialog owns its own load/save toasts.
    val context = LocalContext.current
    val onEditProject: (HomeProjects.Project) -> Unit = { project ->
        dialog.show(DialogSize.FIT) { controller ->
            HomeProjectEditDialog(
                api = projectApi,
                project = project,
                onClose = { controller.close() },
                onSaved = { name ->
                    // The row renames immediately: the saved name lands in the
                    // local record (offline/restart cover) and in the catalogue
                    // names (this frame's rows) — `null` means the name field
                    // was unchanged. The scoped re-read behind it converges
                    // with the server's truth without touching the effect keys
                    // (cold-start pin: `HomeColdStartTest`).
                    if (name != null) {
                        homeProjectStore?.setName(project.path, name)
                        val key = ProjectPaths.matchKey(project.path)
                        if (key != null) {
                            val trimmed = name.trim()
                            serverNames =
                                if (trimmed.isEmpty()) serverNames - key
                                else serverNames + (key to trimmed)
                        }
                    }
                    projectActionScope.launch {
                        serverNames = withContext(Dispatchers.IO) { projectApi.loadNames() }
                        // B-14: the row's **avatar** has no client-local mirror
                        // (unlike the name above) — it is drawn from the
                        // catalogue's `icon.override` / `icon.color` alone
                        // ([HomeProjects.openRows]), and Home opens no SSE, so
                        // the `project.updated` revision that would re-drive the
                        // read never arrives here. A saved icon image therefore
                        // stayed invisible until the next Home entry (device
                        // run tB_14k/14m: only a force-stop showed it). Re-read
                        // the rows the save just changed; a failed read answers
                        // null and keeps the rows on screen.
                        chatViewModel?.listProjects { list -> if (list != null) serverProjects = list }
                    }
                },
            )
        }
    }
    val onRefreshWorktrees: (HomeProjects.Project) -> Unit = { project ->
        projectActionScope.launch {
            val result = withContext(Dispatchers.IO) { projectApi.refreshWorktrees(project.path) }
            // Upstream's refresh is a best-effort side effect of opening the
            // session workspace menu and swallows every failure:
            // `.catch(() => undefined)` (`session-workspace-menu.tsx:53-57`),
            // no toast for any of them. It is also only reachable from a menu
            // typed on a **server** project (`:18`), so 「no server project for
            // this directory」 is not a state upstream can even be in — the
            // action is simply not offered there, which the row menu enforces
            // through [HomeProjectEdit.refreshApplies].
            //
            // So [ProjectWrite.NotRegistered] is a quiet no-op here, not a
            // refusal: it used to toast `oc_home_project_edit_missing`
            // (「该项目未在服务器注册」), a key the audit found has no upstream
            // counterpart at all (Q3) and which the panel had already stopped
            // using. A row that still reaches this branch (the menu item was
            // built before the catalogue re-read that changed the row) does
            // nothing, exactly as upstream's `.catch` does.
            if (result is ProjectWrite.NotRegistered) return@launch
            val message = when (result) {
                ProjectWrite.Ok -> context.getString(R.string.oc_home_project_worktrees_refreshed)
                is ProjectWrite.Failed -> context.getString(
                    R.string.oc_home_project_worktrees_refresh_failed,
                    projectFaultText(context, result.fault),
                )
                ProjectWrite.NotRegistered -> return@launch
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
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
    // The scope label is the row's display name (`displayName(project)`), so a
    // renamed project renames the placeholder in the same frame — never the
    // bare folder name.
    val searchScope = remember(selectedDirectory, projects) {
        selectedDirectory?.let { directory ->
            val key = ProjectPaths.matchKey(directory)
            projects.firstOrNull { ProjectPaths.matchKey(it.path) == key }?.name
        } ?: HomeSessions.placeholderScope(selectedDirectory)
    }
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
    // v44 tC: the delete's own answer, held while the dialog is up. A failed
    // delete leaves both set, so the dialog keeps explaining that the row is
    // still there and the same 删除 retries it; a successful one clears them.
    var deleteBusy by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    // v83 P1 「改名失败仍 4s toast」: the rename's refusal, parked where Home can
    // keep showing it. The toast is the transient half (v81's per-shape copy) and
    // a `Toast.LENGTH_LONG` window is what the v82 BUG-B device log measured
    // dying 0.9 s before the screenshot — a sentence the user must act on cannot
    // live only there. The session route already parks the same text on
    // [RenameFailureDialog] (v82); this is the Home row editor's half, which the
    // report's `11_rename_t2.png` → `13_rename_t34.png` pair shows missing.
    var renameFailureMessage by remember { mutableStateOf<String?>(null) }
    val onRenameRow: ((String, String, (Boolean) -> Unit) -> Unit)? =
        rowActions?.let { actions ->
            { id, title, onResult ->
                actions.rename(id, title) { outcome ->
                    val renamed = outcome == SessionRenameOutcome.RENAMED
                    // v45 P0: the row takes the new name the moment the server
                    // accepts it, exactly as upstream's invalidated query does
                    // (see [renamedTitles]) — not on the next catalogue poll,
                    // which is up to `HOME_SESSIONS_FRESHNESS_MS` away and made
                    // a successful rename look like a no-op.
                    if (renamed) renamedTitles = renamedTitles + (id to title)
                    // v81 P1/P2: a `404` means the server no longer has this
                    // session — the row is a ghost, and the same tombstone the
                    // delete uses keeps every read already in flight from putting
                    // it back (the view model drops it from the catalogue cache
                    // and re-reads; this is the half that survives that read).
                    if (outcome == SessionRenameOutcome.GONE) deletedSessions = deletedSessions + id
                    // v84 P1: the row does not stay in the editor — the answer
                    // ends the edit (`HomeSessionsList`'s commit →
                    // `HomeRowMenu.resolveSave`), so the row shows the catalogue's
                    // own title again and its menu is reachable for a retry. The
                    // sentence above is what explains *why* it failed — and since
                    // v81 it says which shape it was instead of blaming the
                    // connection for a server that answered (`renameFailureRes`).
                    renameFailureToast(context, outcome)
                    // v83 P1: and the same sentence is **parked** on a card the
                    // reader has to dismiss, so the answer is still on screen
                    // after the toast's 3.5 s (the v82 BUG-B measurement, whose
                    // fix the session route got and this row editor did not).
                    renameFailureMessage = renameFailureText(context, outcome)
                    onResult(renamed)
                }
            }
        }
    val onExportRow: ((HomeSessions.Row) -> Unit)? =
        rowActions?.let { actions ->
            { row -> exportSaver.export(row) { actions.export(row) } }
        }
    val onDeleteRow: ((HomeSessions.Row) -> Unit)? =
        rowActions?.let { { row -> pendingDelete = row } }
    // v40: the session-row menu's 「导入会话」 — self-serve like the project row's
    // (`rememberHomeSessionImport` owns the picker, the SAF read and the import
    // route), so all this host adds is the step that needs the nav controller:
    // `onSessionImported` registers the directory and opens the created session.
    val importSessionRow = rememberHomeSessionImport(onSessionImported)
    // v46 P2: whether a session-row rename editor is open (reported by the
    // sessions column). While true the mobile column yields the editor the
    // projects panel's and the utility nav's shares, so the editing row, its
    // header and a sibling still fit a keyboard-up card.
    var sessionsEditing by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(c.bgDeep)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(CARD_MARGIN)) {
            val wide = maxWidth >= LG_BREAKPOINT
            // Captured here, before the columns below: their scopes shadow
            // `BoxWithConstraintsScope` and its `maxHeight`.
            val cardHeight = maxHeight
            // v45 P1 「极窄下列表塌空」: one budget, derived from the card the host
            // actually measured — the sessions list's floor
            // ([HomeRowLayout.listMinHeight]) and the projects ceiling that has
            // to leave it. The v43/v44 pair of constants could not be paid for
            // on a 640x1500 @420 dpi card with the keyboard up (185 dp of card
            // against a 188 dp budget), so the column overflowed and the utility
            // nav was laid out over the editing row the floor exists to keep
            // visible (`research/v45_verify/tC/v45_46/47`, against v44_33/34).
            //
            // v47 P0 「窄屏编辑态分组头不渲染」: the floor follows the editing
            // state — while an editor is open the nav steps aside, so a floor
            // derived as if it still stood drops to the row-only 40 dp on a
            // card that really fits header + row (185 − 48 = 137 dp), and the
            // siblings and the header the floor exists to keep are virtualized
            // out (`research/v47_verify/tC/v47_30/31`).
            val listMinHeight = HomeRowLayout.listMinHeight(cardHeight, sessionsEditing)
            // v52 P0 「矮窗会话列表整片空白」: on a card shorter than the whole
            // mobile budget the two chrome bands give way instead of the rows —
            // the search block drops its 24 dp `topInset`, and the 设置 / 帮助 nav
            // (72 dp of chrome *below* the rows) steps aside entirely, so the
            // list viewport is never a hard 0 dp (`research/v52_verify/tE/
            // v52_32_home`, `v52_33_listscroll`, against `v52_38_big`).
            val searchTopInset = HomeRowLayout.searchTopInset(cardHeight)
            // v54 P1 「矮窗会话列表只渲染一行且被截断」: the list viewport's own
            // top step is the host's now — it drops to 0 on a card that cannot
            // also afford the rows' floor, so the one row a short window can
            // hold is a whole one (`HomeRowLayout.listTopPadding`,
            // `research/v54_verify/tE/v54_16…22`).
            val listTopPadding = HomeRowLayout.listTopPadding(cardHeight)
            val utilityNavMaxHeight =
                HomeRowLayout.utilityNavMaxHeight(cardHeight, listMinHeight)
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
                                onEditProject = onEditProject,
                                onRefreshWorktrees = onRefreshWorktrees,
                                onSessionImported = onSessionImported,
                                projectUnseen = projectUnseen,
                                onClearProjectNotifications = onClearProjectNotifications,
                                modifier = Modifier.width(PROJECTS_WIDTH),
                            )
                            Spacer(Modifier.width(COLUMN_GAP))
                            HomeSessionsColumn(
                                rows = rows,
                                loading = HomeSessions.isColumnLoading(
                                    localLoading = loading,
                                    loadFailed = sessionsFailed,
                                    firstReadOk = firstReadOk,
                                    rowCount = sessions.size,
                                    probePending = browseFailure == TransportFailure.ProbePending,
                                ),
                                configMissing = configMissing,
                                loadFailed = sessionsFailed,
                                onRetrySessions = retrySessions,
                                searchScope = searchScope,
                                showProjectName = showProjectName,
                                openSessionIds = openSessionIds,
                                onOpenSession = onOpenSessionRow,
                                onNewSession = onNewSessionScoped,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                // A-2: the rows' dot / marquee (see above).
                                unread = rowUnread,
                                running = rowLoading,
                                topInset = HomeRowLayout.desktopSearchTopInset(
                                    cardHeight,
                                    DESKTOP_TOP_INSET,
                                ),
                                listMinHeight = listMinHeight,
                                listTopPadding = listTopPadding,
                                searchSessions = titledSessions,
                                projectNames = serverNames,
                                // B-14: the icon each row's avatar draws — the
                                // same project rows the 项目 column renders.
                                projects = projects,
                                searchFocusRequest = searchFocusRequest,
                                onRenameSession = onRenameRow,
                                onExportSession = onExportRow,
                                // v40: the row menu's 「导入会话」 (see
                                // `importSessionRow` above).
                                onImportSession = importSessionRow,
                                onDeleteSession = onDeleteRow,
                                // A-3: the ticking clock the rows' labels and the
                                // group headers are measured against.
                                now = homeNow,
                                // v46 P2: the mobile column yields its chrome
                                // while a rename editor is open.
                                onEditingChanged = { sessionsEditing = it },
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
                                onEditProject = onEditProject,
                                onRefreshWorktrees = onRefreshWorktrees,
                                onSessionImported = onSessionImported,
                                projectUnseen = projectUnseen,
                                onClearProjectNotifications = onClearProjectNotifications,
                                // v43 P1: cap the panel so it can never starve
                                // the sessions list below it (the keyboard +
                                // narrow-phone case that collapsed it to 0 dp),
                                // and scroll it so the projects that no longer
                                // fit stay reachable.
                                //
                                // v46 P2: while a rename editor is open the panel
                                // collapses entirely — on a keyboard-up card its
                                // share is what the editing row, its header and
                                // a sibling are missing
                                // ([HomeRowLayout.mobileRowsViewport]).
                                modifier = Modifier
                                    .heightIn(
                                        max = if (sessionsEditing) {
                                            0.dp
                                        } else {
                                            HomeRowLayout.projectsDropdownMaxHeight(cardHeight, listMinHeight)
                                        },
                                    )
                                    .verticalScroll(rememberScrollState())
                                    .padding(bottom = 4.dp),
                            )
                            HomeSessionsColumn(
                                rows = rows,
                                loading = HomeSessions.isColumnLoading(
                                    localLoading = loading,
                                    loadFailed = sessionsFailed,
                                    firstReadOk = firstReadOk,
                                    rowCount = sessions.size,
                                    probePending = browseFailure == TransportFailure.ProbePending,
                                ),
                                configMissing = configMissing,
                                loadFailed = sessionsFailed,
                                onRetrySessions = retrySessions,
                                searchScope = searchScope,
                                showProjectName = showProjectName,
                                openSessionIds = openSessionIds,
                                onOpenSession = onOpenSessionRow,
                                onNewSession = onNewSessionScoped,
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                // A-2: the rows' dot / marquee (see above).
                                unread = rowUnread,
                                running = rowLoading,
                                searchSessions = titledSessions,
                                projectNames = serverNames,
                                // B-14: the icon each row's avatar draws — the
                                // same project rows the 项目 column renders.
                                projects = projects,
                                searchFocusRequest = searchFocusRequest,
                                listMinHeight = listMinHeight,
                                listTopPadding = listTopPadding,
                                onRenameSession = onRenameRow,
                                onExportSession = onExportRow,
                                // v40: the same import host as the desktop column.
                                onImportSession = importSessionRow,
                                onDeleteSession = onDeleteRow,
                                // A-3: the same clock as the desktop column.
                                now = homeNow,
                                // v46 P2: the mobile column yields its chrome
                                // while a rename editor is open.
                                onEditingChanged = { sessionsEditing = it },
                                // v52 P0: the search block's own elastic part
                                // (`topInset`) shrinks on a card that cannot pay
                                // for it.
                                topInset = searchTopInset,
                            )
                            // `HomeUtilityNav class="flex lg:hidden"` — the
                            // mobile single column's last grid row.
                            //
                            // v46 P2: hidden while a rename editor is open. Its
                            // 72 dp is what the list viewport is missing on a
                            // keyboard-up card, and the editor's own Enter/✓/blur
                            // chain never needs it
                            // ([HomeRowLayout.utilityNavReserve]).
                            //
                            // v52 P0: also hidden on a card too short to pay for
                            // it *and* the rows' floor — the utility nav sat below
                            // the list and overflowed the column, which took the
                            // list viewport to 0 dp on the 1440x810 @420dpi window
                            // (设置 / 帮助 rendered, not one session row:
                            // `research/v52_verify/tE/v52_32_home`).
                            if (!sessionsEditing) {
                                // v52 P0: and only while the card affords it
                                // alongside the rows' floor
                                // ([HomeRowLayout.utilityNavMaxHeight]).
                                if (utilityNavMaxHeight > 0.dp) {
                                    HomeUtilityNav(
                                        onOpenSettings = onOpenSettings,
                                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            // WAVE 2.3b P1 (A2 #4): the delete danger dialog
            // (`controller.tsx:219-243`): title + `session.delete.confirm`
            // + Cancel / danger Delete. Confirm deletes through [HomeRowActions]
            // and drops the row on the next read; cancel just closes.
            //
            // v44 tC (P0 「删除静默无效」): the dialog used to close on the tap
            // whether or not a `DELETE` ever left the device, so a failed delete
            // read as a deleted one (`research/v44_verify/tC/v44_49_delete_confirm.png`).
            // It now closes on the server's answer only; a false keeps it up with
            // the reason and the same 删除 retries.
            pendingDelete?.let { row ->
                HomeDeleteDialog(
                    title = row.title,
                    busy = deleteBusy,
                    errorText = deleteError,
                    onDismiss = {
                        pendingDelete = null
                        deleteError = null
                        deleteBusy = false
                    },
                    onConfirm = {
                        val target = pendingDelete ?: return@HomeDeleteDialog
                        if (deleteBusy) return@HomeDeleteDialog
                        deleteBusy = true
                        deleteError = null
                        val actions = rowActions
                        if (actions == null) {
                            deleteBusy = false
                            deleteError = context.getString(R.string.oc_delete_session_failed)
                            return@HomeDeleteDialog
                        }
                        actions.delete(target) { deleted ->
                            deleteBusy = false
                            if (deleted) {
                                pendingDelete = null
                                deleteError = null
                                // v47 P1: the confirmed delete drops its own row
                                // (and its rename mirror, if any) on the answer
                                // instead of the next catalogue read — a later
                                // read can no longer resurrect it through
                                // [mergeCatalogueRead], and the row is gone in
                                // the same frame the dialog closes.
                                sessions = sessions.filterNot { it.id == target.id }
                                renamedTitles = renamedTitles - target.id
                                // v59: and the row stays gone for the reads
                                // that were already in flight when the
                                // `DELETE` went out — those still answer with
                                // it, and adopting one used to bring the row
                                // straight back for a tick.
                                deletedSessions = deletedSessions + target.id
                            } else {
                                deleteError =
                                    context.getString(R.string.oc_delete_session_failed)
                            }
                        }
                    },
                )
            }
            // v83 P1: the failed rename's sentence, parked until dismissed —
            // painted above the delete dialog like the session route paints it
            // (`SessionScreen`), and reusing the same card so the two rename
            // surfaces cannot drift. `oc_error_title` + the outcome's own line,
            // one `Close` button: there is nothing to retry *in* the card, the
            // row editor is the retry.
            renameFailureMessage?.let { message ->
                RenameFailureDialog(
                    message = message,
                    onDismiss = { renameFailureMessage = null },
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
 * A-3: the sessions column's relative-label tick — 30 s, the same cadence the
 * palette's clock and the session drawer's use (`PALETTE_CLOCK_TICK_MS` /
 * `DRAWER_CLOCK_TICK_MS`). One minute is the finest bucket any of them renders,
 * so a shorter tick would only recompose rows to print the same literal.
 */
private const val HOME_CLOCK_TICK_MS = 30_000L

/**
 * How often Home re-reads the session list while it is on screen — the bound on
 * "the connection came back and Home did not notice" (see the freshness tick in
 * [HomeScreen]). 45 s is inside the ≤1 min recovery the v36 tB report asks for
 * and well above the one `GET /api/session` round trip it costs, so a healthy
 * connection sees one extra read per tick and never a queue of them.
 */
internal const val HOME_SESSIONS_FRESHNESS_MS = 45_000L

/**
 * Stand-in revision source for callers without a [ChatViewModel] (previews,
 * isolated tests): a constant flow, so the `collectAsState` above stays
 * unconditional and the effect keys keep their identity.
 */
private val NO_REVISION_FLOW = MutableStateFlow(0)

/**
 * Stand-in for a host without a tracked session (previews, isolated hosts): no
 * title to key the freshness loop's extra read on (see the `trackedTitle` note).
 */
private val NO_TITLE_FLOW = MutableStateFlow<String?>(null)

/**
 * Stand-in failure source for callers without a [ChatViewModel] (previews,
 * isolated tests): null, so the sessions column never mistakes "no client"
 * for a pending probe.
 */
private val NO_FAILURE_FLOW = MutableStateFlow<TransportFailure?>(null)

/**
 * Stand-in catalogue-read outcome for callers without a [ChatViewModel]:
 * no failure, so the sessions column never renders its error state for a
 * client that cannot fail a read.
 */
private val NO_SESSIONS_FAILED_FLOW = MutableStateFlow(false)

/**
 * Stand-in notification feed for callers without a [ChatViewModel] (previews,
 * isolated tests): empty, so every project's unseen count is 0 and 「清除通知」
 * stays disabled — the same thing a fresh install shows.
 */
private val NO_NOTIFICATIONS_FLOW = MutableStateFlow<List<AppNotification>>(emptyList())

/**
 * A-2: stand-in dot inputs for callers without a [ChatViewModel] (previews,
 * isolated tests) — empty, so every row renders the avatar and the list looks
 * exactly like it did before the indicator existed.
 */
private val NO_SESSION_UNREAD_FLOW = MutableStateFlow<Map<String, SessionUnread>>(emptyMap())
private val NO_SESSION_ASKS_FLOW = MutableStateFlow<Map<String, SessionAsks>>(emptyMap())
private val NO_PENDING_PERMISSION_FLOW = MutableStateFlow<OpenCodeClient.PermissionRequest?>(null)
private val NO_PENDING_FORM_FLOW = MutableStateFlow<ChatStore.FormAsk?>(null)
private val NO_AUTO_APPROVE_FLOW = MutableStateFlow(false)
private val NO_RUN_STATUS_FLOW = MutableStateFlow(ChatStore.RunStatus.IDLE)

/**
 * A-3: the un-viewed notifications that belong to one project — the `unseen`
 * count behind 「清除通知」's `disabled={unseen === 0}`
 * (`home/projects/view.tsx:752`) and the set that row clears.
 *
 * Attribution is the sessions column's own rule
 * ([HomeSessions.containsAny] over the project's directories), fed by the
 * session→directory map that column is built from: a notification whose session
 * has no known directory is unattributable and belongs to no project. Pure, so
 * the attribution is pinned without a device.
 */
internal fun projectUnseenNotifications(
    notifications: List<AppNotification>,
    directoryBySession: Map<String, String>,
    directories: List<String>,
): List<AppNotification> = notifications.filter { notification ->
    if (notification.viewed) return@filter false
    val directory = notification.sessionID?.let { directoryBySession[it] }
    HomeSessions.containsAny(directories, directory)
}

/**
 * v47 P1 「1080 瞬态」/ P0 「窄屏改名提交后『今天』整组消失」: what a catalogue
 * read adopts while a rename mirror is open.
 *
 * A read that lands between the server accepting a `PATCH` and echoing it can
 * miss the renamed session (or carry it with the old title): adopting it
 * blindly drops the row — and [pruneRenamedTitles] then retires the mirror as
 * dead — so the group loses a row for one read cycle on 1080 (`v47_08/09`,
 * `v47_24/25`, self-healed by the next read) and until cold start on narrow,
 * where no second read arrives in time (`v47_34/35/36/37`, against `v46_22`).
 *
 * The adoption therefore keeps the previous rows the incoming read is missing
 * **while their rename is still mirrored**: the row stays on screen, and the
 * prune below keeps the mirror (the retained id is present with a title that
 * is not the accepted one), so [applyRenamedTitles] keeps printing the
 * accepted title through the window. A confirming read retires the mirror as
 * before; an id with no mirror is never retained, so deletes (local and
 * remote) still drop the row on the read that stops carrying it.
 */
internal fun mergeCatalogueRead(
    prev: List<OpenCodeClient.Session>,
    next: List<OpenCodeClient.Session>,
    mirror: Map<String, String>,
): List<OpenCodeClient.Session> {
    if (mirror.isEmpty() || prev.isEmpty()) return next
    val incoming = next.map { it.id }.toHashSet()
    val retained = prev.filter { it.id in mirror && it.id !in incoming }
    if (retained.isEmpty()) return next
    return (next + retained).sortedByDescending { it.updatedAt }
}

/**
 * v46 P0 「改名提交后行消失」: which [renamedTitles] mirrors a catalogue read
 * retires. A mirror lives only while the server still reports the *old* title:
 * a read that already carries the accepted title makes it redundant, and a read
 * that no longer carries the id at all (deleted, expired) makes it dead. The
 * previous inline predicate kept exactly the wrong two — the redundant one
 * (server agrees) and the dead one (id gone) — while a slow read that had not
 * seen the `PATCH` yet (server still old) dropped the accepted title and the
 * row flashed back to its pre-rename label.
 */
internal fun pruneRenamedTitles(
    mirror: Map<String, String>,
    serverTitles: Map<String, String?>,
): Map<String, String> = mirror.filter { (id, title) ->
    val server = serverTitles[id]
    server != null && server != title
}

/**
 * v59: the rows a read brought back minus the ones this screen deleted itself.
 *
 * The delete's own answer already drops the row from local state, but a read
 * issued **before** the `DELETE` reached the server answers after it and still
 * carries the row; adopting that read resurrected it, so the row sat in the list
 * until the next read (measured 「删除会话后列表行滞留数秒」). Ids are never
 * reused, so a tombstone is kept for the life of the screen — no read-side
 * bookkeeping, and a session that comes back by the same id is the same
 * deletion's aftermath, not a resurrection worth showing.
 */
internal fun dropDeletedSessions(
    sessions: List<OpenCodeClient.Session>,
    deleted: Set<String>,
): List<OpenCodeClient.Session> {
    if (deleted.isEmpty()) return sessions
    return sessions.filterNot { it.id in deleted }
}

/**
 * v46 P0: the accepted titles on top of a catalogue read, without touching
 * anything else about the rows — same ids, same `updatedAt` (so the same day
 * group), same directories (so the same scope and project attribution). A
 * rename therefore cannot move, drop or blank a sibling row: only the renamed
 * row's label differs.
 */
internal fun applyRenamedTitles(
    sessions: List<OpenCodeClient.Session>,
    mirror: Map<String, String>,
): List<OpenCodeClient.Session> {
    if (mirror.isEmpty()) return sessions
    return sessions.map { session ->
        val title = mirror[session.id]
        if (title != null && title != session.title) session.copy(title = title) else session
    }
}

/**
 * The record an absent [HomeProjectStore] reads as (previews, isolated hosts):
 * no opened projects and nothing in 最近关闭, so the column shows its empty state
 * — the same thing a freshly installed app shows.
 */
private val EMPTY_PROJECTS = HomeProjectRecords.State()
