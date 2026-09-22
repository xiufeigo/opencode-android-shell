package com.xiufeigo.opencodeshell.ui.session

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.ChatStore
import com.xiufeigo.opencodeshell.ChatViewModel
import com.xiufeigo.opencodeshell.OpenCodeClient
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ServerConfigStore
import com.xiufeigo.opencodeshell.ui.palette.LocalSessionCommandIntents
import com.xiufeigo.opencodeshell.ui.palette.OpenFileRequest
import com.xiufeigo.opencodeshell.ui.session.composer.Composer
import com.xiufeigo.opencodeshell.ui.session.composer.rememberAttachmentPicker
import com.xiufeigo.opencodeshell.ui.session.context.SessionContextModel
import com.xiufeigo.opencodeshell.ui.session.dock.SessionFormDock
import com.xiufeigo.opencodeshell.ui.session.dock.SessionPermissionDock
import com.xiufeigo.opencodeshell.ui.session.dock.SessionQuestionDock
import com.xiufeigo.opencodeshell.ui.session.drawer.MobileForm
import com.xiufeigo.opencodeshell.ui.session.drawer.SessionBottomSheet
import com.xiufeigo.opencodeshell.ui.session.drawer.SessionDeleteDialog
import com.xiufeigo.opencodeshell.ui.session.drawer.SessionDrawerContent
import com.xiufeigo.opencodeshell.ui.session.drawer.SessionRenameDialog
import com.xiufeigo.opencodeshell.ui.session.export.SessionExport
import com.xiufeigo.opencodeshell.ui.session.export.rememberSessionExportSaver
import com.xiufeigo.opencodeshell.ui.session.header.ContextUsage
import com.xiufeigo.opencodeshell.ui.session.header.SessionHeader
import com.xiufeigo.opencodeshell.ui.session.header.SessionMenuAction
import com.xiufeigo.opencodeshell.ui.session.header.SessionSummaryPopover
import com.xiufeigo.opencodeshell.ui.session.review.MobileSessionTabs
import com.xiufeigo.opencodeshell.ui.session.review.MobileUsagePanel
import com.xiufeigo.opencodeshell.ui.session.review.ReviewChangesTab
import com.xiufeigo.opencodeshell.ui.session.review.ReviewFilesTab
import com.xiufeigo.opencodeshell.ui.session.review.ReviewPanelTab
import com.xiufeigo.opencodeshell.ui.session.review.ReviewPanelWidth
import com.xiufeigo.opencodeshell.ui.session.review.SessionReviewPanel
import com.xiufeigo.opencodeshell.ui.session.summary.SessionSummaryPanel
import com.xiufeigo.opencodeshell.ui.session.timeline.MessageTimeline
import com.xiufeigo.opencodeshell.ui.session.timeline.TimelineSearch
import com.xiufeigo.opencodeshell.ui.terminal.EmbeddedTerminalPanel
import com.xiufeigo.opencodeshell.ui.terminal.TerminalDock
import com.xiufeigo.opencodeshell.ui.terminal.TerminalPlacement
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Session screen (M1 stage 1 → M1.5 → M2 → W4-C): sticky header + message
 * timeline + composer, wrapped in the session drawer.
 *
 * Lifecycle: the screen is the sole owner of the session subscription. It calls
 * [ChatViewModel.observeSession] then [ChatViewModel.start] on entry, and
 * [ChatViewModel.stop] on dispose — the SSE socket is only held while the chat
 * is actually on screen (same policy as the View shell's chat tab).
 *
 * M2 additions:
 * - the **sticky header** (`research/02` §2.2) now carries the title
 *   (clickable → inline rename), the context ring and the ⋮ menu, and the M1
 *   `SessionTopBar` is gone — one title surface, no duplication (§6 of the M2
 *   brief, resolved and recorded in `research/_M2_session_status.md`);
 * - the **session drawer** (§7) via `ModalNavigationDrawer`, listing
 *   [ChatViewModel.listSessions] with relative times and a "new session" entry;
 * - the screen (not the nav graph) owns **which session is on screen**:
 *   switching or creating one just re-points [ChatViewModel.observeSession], so
 *   no navigation round-trip is needed for a phone-sized shell;
 * - tool expansion state lives here (`toolOpen`, upstream
 *   `timelineCache.toolOpen`) so it survives list recycling.
 *
 * W4-C (mobile form, task-3) — one screen, two shells selected by
 * [MobileForm.isDesktop] (upstream `isDesktop()`, `session/model.ts:35`):
 * - **≥768 dp (desktop)**: unchanged — `ModalNavigationDrawer` on the leading
 *   edge, timeline + review sidebar in a `Row`, and the header's review toggle;
 * - **<768 dp (mobile)**: [SessionBottomSheet] (upstream
 *   `shell/mobile-drawer.css`: 16 dp top radius, 28 dp handle, scrim,
 *   swipe-down) instead of the left drawer, and [MobileSessionTabs] — the four
 *   full-width Session / Changes / Files / Terminal tabs of
 *   `session/screen.tsx:191-242,290-317` — instead of the sidebar. The header's
 *   review toggle is desktop-only upstream (`session/screen.tsx:338-348`), so
 *   it is not rendered on mobile.
 * - Drawer rows answer the upstream 500 ms long press
 *   (`home/sessions/view.tsx:30,597-610,694-701`) with Rename / Delete; both
 *   actions are confirmed by screen-level dialogs and go through
 *   `ChatViewModel.renameSession` / `ChatViewModel.deleteSession`.
 *
 * The composer overlays the timeline (upstream: `paddingEnd 64` on the virtual
 * list gives the floating composer room), so its measured height feeds the
 * timeline's bottom content padding instead of a magic constant.
 */
@Composable
fun SessionScreen(
    chatViewModel: ChatViewModel,
    sessionId: String,
    modifier: Modifier = Modifier,
    /** Title known at navigation time; `ChatViewModel.currentTitle` still wins. */
    sessionTitle: String? = null,
    onBack: () -> Unit = {},
    /** Drawer「新建会话」→ draft page (upstream v2: composer first, create on submit). */
    onNewSessionDraft: () -> Unit = {},
    /**
     * A1/A8: the active server entry, for the two features that read the server
     * **outside** `ChatViewModel` — the summary's server row and the export's raw
     * JSON (`GET /session/:id` + `/message`). Null (previews, tests) degrades
     * both gracefully: no server row, export reports a failure toast.
     */
    serverStore: ServerConfigStore? = null,
    /**
     * A1: bumped by `session.summary.toggle` (palette row + `Ctrl+Shift+Y`). The
     * popover's open state is screen-local, so the command reaches it through a
     * revision instead of a direct callback — same channel shape as
     * `SessionCommandIntents.reviewRevision`. The value present at first
     * composition is adopted as "nothing happened yet", so entering a session
     * never auto-opens the popover.
     */
    summaryToggleRevision: Int = 0,
    /**
     * Upstream docks the terminal in one of two places (`session/screen-layout.ts:
     * 19-20`) — a **pane of the right side region** (`settings.general
     * .terminalPlacement == "side"`, the upstream default) or the **full-width
     * bottom panel** of the session body (`"bottom"`, `session/screen.tsx:507-540`,
     * height `DEFAULT_TERMINAL_HEIGHT = 280` capped at 60% of the viewport) — both
     * with their own tab strip. The route owns this flag (the floating chip and the
     * `Ctrl+`` chord toggle the same value), this screen only mounts the pane, and
     * `TerminalDock` picks the slot from the stored setting.
     * Narrow layouts never dock — there the terminal is a full view tab.
     */
    terminalDocked: Boolean = false,
    onCloseTerminalDock: () -> Unit = {},
) {
    val c = OcTheme.colors

    // Pending attachments live here (not inside the composer) so the SAF picker,
    // the preview strip and `send` all read one owner; `rememberAttachmentPicker`
    // registers the launcher and does the IO read.
    var attachments by remember { mutableStateOf<List<ChatViewModel.Attachment>>(emptyList()) }
    val attachmentPicker = rememberAttachmentPicker(attachments) { attachments = it }

    // The session this screen currently shows. `sessionId` is the entry point
    // (nav argument); switching through the drawer only re-points this state.
    var activeSession by remember(sessionId) {
        mutableStateOf(ActiveSession(id = sessionId, title = sessionTitle, directory = null))
    }
    var sessions by remember { mutableStateOf<List<OpenCodeClient.Session>>(emptyList()) }
    // Optimistic rename: the header shows the new title immediately and rolls
    // back if `PATCH /session/:id` fails.
    var pendingTitle by remember(sessionId) { mutableStateOf<String?>(null) }
    val toolOpen = remember { mutableStateMapOf<String, Boolean>() }
    // Review side panel (changes/files). The switch lives here, screen-local
    // and rememberSaveable; opening the panel triggers the REST pull of the
    // diff, todos and vcs branch — SSE keeps them live afterwards.
    var reviewOpen by rememberSaveable { mutableStateOf(false) }
    var reviewTabName by rememberSaveable { mutableStateOf(ReviewPanelTab.CHANGES.name) }
    // `file.open` target (palette) — see the intents effect below.
    var openFilePath by remember { mutableStateOf<String?>(null) }
    var reviewBranch by remember(activeSession.id) { mutableStateOf<String?>(null) }
    var reviewDefaultBranch by remember(activeSession.id) { mutableStateOf<String?>(null) }
    // W5-R: the review panel's Git / Branch scopes read `GET /vcs/diff?mode=`
    // (verified against opencode 1.18.31 — `mode=git|branch` returns JSON).
    var vcsDiffs by remember(activeSession.id) {
        mutableStateOf<List<OpenCodeClient.FileDiff>>(emptyList())
    }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Form switch (upstream `isDesktop()`, `session/model.ts:35`) and the state
    // that only exists on one side of it.
    val configuration = LocalConfiguration.current
    val desktop = MobileForm.isDesktop(configuration.screenWidthDp)
    var mobileTabName by rememberSaveable { mutableStateOf(MobileForm.Tab.SESSION.name) }
    val mobileTab = MobileForm.tabFromName(mobileTabName)
    // `⋮` → Usage is a view, not a tab (upstream `view.tsx:94`).
    var usageOpen by rememberSaveable { mutableStateOf(false) }
    // A1: the summary panel. Wide screens host it as a header popover
    // (`SessionSummaryPopover`), the phone form as a full-content view — upstream's
    // two mounting points (`popover.tsx:17-34` vs `screen.tsx:191-240`).
    var summaryOpen by rememberSaveable { mutableStateOf(false) }
    // Mobile bottom-sheet drawer visibility (desktop keeps `drawerState`).
    var drawerOpen by rememberSaveable { mutableStateOf(false) }
    // Row-menu confirmations (both forms).
    var renameTarget by remember { mutableStateOf<OpenCodeClient.Session?>(null) }
    var deleteTarget by remember { mutableStateOf<OpenCodeClient.Session?>(null) }
    // Header ⋮ → Delete (W4-E F-4): the session on screen is deleted through the
    // same destructive dialog as the drawer rows, then the screen backs out.
    var headerDeleteOpen by remember { mutableStateOf(false) }

    val reviewTab = ReviewPanelTab.values()
        .firstOrNull { it.name == reviewTabName } ?: ReviewPanelTab.CHANGES
    val drawerWidth = remember(configuration.screenWidthDp) {
        minOf(DRAWER_WIDTH_MAX, (configuration.screenWidthDp * DRAWER_WIDTH_RATIO).dp)
    }

    val closeDrawer: () -> Unit = {
        if (desktop) scope.launch { drawerState.close() } else drawerOpen = false
    }
    val openDrawer: () -> Unit = {
        if (desktop) scope.launch { drawerState.open() } else drawerOpen = true
    }
    val switchSession: (OpenCodeClient.Session) -> Unit = { session ->
        closeDrawer()
        if (session.id != activeSession.id) {
            activeSession = ActiveSession(
                id = session.id,
                title = session.title,
                directory = session.directory,
            )
        }
    }

    // ------------------------------------------------- palette command intents
    // `review.toggle` / `fileTree.toggle` / `file.open` side effects
    // (`ui/palette/AppCommands.kt` → `SessionCommandIntents`). The fallback flows
    // keep the `remember` calls unconditional when no host provides them (previews,
    // tests).
    val intents = LocalSessionCommandIntents.current
    val noReviewRevision = remember { MutableStateFlow(0L) }
    val noFileTreeRevision = remember { MutableStateFlow(0L) }
    val noFileRequest = remember { MutableStateFlow<OpenFileRequest?>(null) }
    val reviewRevision by (intents?.reviewRevision ?: noReviewRevision).collectAsState()
    val fileTreeRevision by (intents?.fileTreeRevision ?: noFileTreeRevision).collectAsState()
    val fileRequest by (intents?.fileRequest ?: noFileRequest).collectAsState()

    LaunchedEffect(reviewRevision) {
        if (reviewRevision <= 0L) return@LaunchedEffect
        // Toggle semantics: desktop flips the sidebar, mobile flips between the
        // session view and the review view (upstream `review.toggle`).
        if (desktop) {
            reviewOpen = !reviewOpen
        } else {
            mobileTabName = if (mobileTab == MobileForm.Tab.CHANGES) {
                MobileForm.Tab.SESSION.name
            } else {
                MobileForm.Tab.CHANGES.name
            }
        }
    }
    // `fileTree.toggle` and `file.open` both *ensure* the review view is open on
    // the Files tab (`file.open` additionally selects the file) — never a toggle.
    LaunchedEffect(fileTreeRevision, fileRequest?.id) {
        val requested = fileRequest
        if (fileTreeRevision <= 0L && requested == null) return@LaunchedEffect
        if (desktop) {
            reviewOpen = true
            reviewTabName = ReviewPanelTab.FILES.name
        } else {
            mobileTabName = MobileForm.Tab.FILES.name
        }
        if (requested != null) {
            openFilePath = requested.path
            intents?.consume(requested)
        }
    }

    LaunchedEffect(activeSession.id) {
        attachments = emptyList()
        toolOpen.clear()
        pendingTitle = null        // `EventStream` bakes `?directory=` in at `start()` and a session switch
        // may cross directories, so the stream is rebuilt around the new
        // session. `stop()` is a no-op when the socket is not up yet (screen
        // entry), which keeps the entry path identical to M1.
        chatViewModel.stop()
        chatViewModel.observeSession(
            id = activeSession.id,
            title = activeSession.title,
            directory = activeSession.directory,
        )
        chatViewModel.start()
        // Backfill the header title + directory (the Compose shell has no other
        // `listSessions` caller) and hand the drawer its data (M1 review #3).
        chatViewModel.listSessions { list ->
            sessions = list
            val self = list.firstOrNull { it.id == activeSession.id }
            if (self != null &&
                (self.title != activeSession.title || self.directory != activeSession.directory)
            ) {
                activeSession = activeSession.copy(title = self.title, directory = self.directory)
            }
        }
    }
    // Only the nav entry can dispose the screen; an in-screen switch must not
    // tear the socket down (it would race the `LaunchedEffect` above).
    DisposableEffect(sessionId) {
        onDispose { chatViewModel.stop() }
    }
    // ----------------------------------------------------- W5-E event revisions
    // The five sticky revision flows `ChatViewModel` bumps on the SSE signals
    // (`research/wave5_events.md` §5). This screen is the one that owns the
    // stream, but the events also arrive while it is *visible*: `project.updated`
    // is Home's business, `session.created`/`session.deleted` the drawer's,
    // `vcs.branch.updated` the branch chip's and `file.edited` the review panel's.
    val sessionsRevision by chatViewModel.sessionsRevision.collectAsState()
    // The tracked session was deleted server-side (another client, expiry):
    // nothing remains to show, so leave exactly like the header-delete flow.
    val sessionMissing by chatViewModel.sessionMissing.collectAsState()
    LaunchedEffect(sessionMissing) {
        if (sessionMissing) onBack()
    }
    val vcsRevision by chatViewModel.vcsRevision.collectAsState()
    val filesRevision by chatViewModel.filesRevision.collectAsState()

    // `session.created` / `session.deleted` → the drawer's list. This used to
    // refresh only on `drawerState.isOpen`, so a session created in another window
    // (or deleted on the phone while the drawer was open) left the list stale
    // until it was closed and reopened.
    LaunchedEffect(drawerState.isOpen, drawerOpen, sessionsRevision) {
        if (drawerState.isOpen || drawerOpen) chatViewModel.listSessions { sessions = it }
    }
    // The review data pull is needed by the desktop sidebar, by the mobile
    // Changes / Files tabs (upstream mounts the same review model in both) and by
    // the A1 summary (`state.diff` supplies its changes row, `reviewVcsInfo` its
    // branch row). `vcsRevision` (`vcs.branch.updated`, e.g. an external
    // `git checkout`) re-runs the whole pull, which is why `reviewBranch` is
    // reset here rather than only on entry.
    val mobileReview = !desktop &&
        (mobileTab == MobileForm.Tab.CHANGES || mobileTab == MobileForm.Tab.FILES)
    LaunchedEffect(activeSession.id, reviewOpen, mobileReview, summaryOpen, vcsRevision) {
        if (!reviewOpen && !mobileReview && !summaryOpen) return@LaunchedEffect
        reviewBranch = null
        chatViewModel.refreshDiff()
        chatViewModel.refreshTodos()
        chatViewModel.reviewVcsInfo { info ->
            reviewBranch = info?.branch
            // W5-R: the Branch scope needs `branch.current != default` to decide
            // whether it is selectable (upstream `review/model.ts:59-66`).
            reviewDefaultBranch = info?.defaultBranch
        }
    }
    // `file.edited` → the panels above are stale, but one tool call can emit a
    // burst of frames, so the re-pull waits for 300 ms of quiet (§5.4). The
    // revision is *not* added to the effect above: that one clears `reviewBranch`
    // and would make the branch chip blink on every file write.
    var refreshedFilesRevision by remember(activeSession.id) { mutableStateOf(filesRevision) }
    LaunchedEffect(activeSession.id, reviewOpen, mobileReview, filesRevision) {
        if (filesRevision == refreshedFilesRevision) return@LaunchedEffect
        if (!reviewOpen && !mobileReview) return@LaunchedEffect
        delay(FILE_EDITED_DEBOUNCE_MS)
        refreshedFilesRevision = filesRevision
        chatViewModel.refreshDiff()
        chatViewModel.refreshTodos()
    }

    // `session.summary.toggle` (palette row / `Ctrl+Shift+Y`). The revision at
    // first composition is adopted as the baseline, so a bump that happened while
    // another session was on screen does not open the popover here.
    var lastSummaryToggle by remember(activeSession.id) { mutableStateOf(summaryToggleRevision) }
    LaunchedEffect(summaryToggleRevision) {
        if (summaryToggleRevision == lastSummaryToggle) return@LaunchedEffect
        lastSummaryToggle = summaryToggleRevision
        summaryOpen = !summaryOpen
        if (summaryOpen) usageOpen = false
    }

    val state by chatViewModel.state.collectAsState()
    val currentTitle by chatViewModel.currentTitle.collectAsState()
    val currentDirectory by chatViewModel.currentDirectory.collectAsState()
    val modelLabel by chatViewModel.modelLabel.collectAsState()
    val availableProviders by chatViewModel.availableProviders.collectAsState()
    val selectedModel by chatViewModel.selectedModel.collectAsState()
    val modelOverrideActive by chatViewModel.modelOverrideActive.collectAsState()
    val configMissing by chatViewModel.configMissing.collectAsState()
    val pendingPermission by chatViewModel.pendingPermission.collectAsState()
    val permissionResponding by chatViewModel.permissionResponding.collectAsState()
    val pendingQuestion by chatViewModel.pendingQuestion.collectAsState()
    val questionResponding by chatViewModel.questionResponding.collectAsState()
    // Wave 2.4a: the v2 form dock. v1 servers produce question asks, v2 servers
    // produce forms — both docks may exist, the form dock renders below the
    // question dock. `questionResponding` doubles as the form busy flag (Wave 1).
    val pendingForm by chatViewModel.pendingForm.collectAsState()
    // item8 (W4-B): the real context window from `GET /config/providers`
    // `limit.context`, and the session cost for the ring's tooltip panel.
    val contextLimit by chatViewModel.contextLimit.collectAsState()
    val sessionCost by chatViewModel.sessionCost.collectAsState()
    // W5-C: catalogs + per-session selections the composer's agent chip, slash
    // menu and `@` categories read (`GET /agent`, `/command`, `/skill`).
    val availableAgents by chatViewModel.availableAgents.collectAsState()
    val availableCommands by chatViewModel.availableCommands.collectAsState()
    val availableSkills by chatViewModel.availableSkills.collectAsState()
    val selectedAgent by chatViewModel.selectedAgent.collectAsState()
    val selectedVariant by chatViewModel.selectedVariant.collectAsState()
    // WAVE 2.4b: the queue/steer wiring (`wave2.4b-notes.md` §VM 2) — the
    // follow-up behavior (read-only), the generation gate, and the VM-owned
    // inbox rows + mutation flag the panel renders.
    val followUpBehavior by chatViewModel.followUpBehavior.collectAsState()
    val generation by chatViewModel.generation.collectAsState()
    val inboxRows by chatViewModel.queuedRows.collectAsState()
    val inboxMutationInFlight by chatViewModel.queueBusy.collectAsState()

    // The server title wins as soon as it lands; the optimisation only bridges
    // the `PATCH` round-trip.
    LaunchedEffect(currentTitle) {
        if (currentTitle != null && currentTitle == pendingTitle) pendingTitle = null
    }
    val title = pendingTitle ?: currentTitle ?: activeSession.title
        ?: stringResource(R.string.oc_new_session)
    // Total tokens the ring reports (and its tooltip shows).
    val contextTokens = ContextUsage.lastTokenTotal(state.messages)

    var composerHeight by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current

    // Review data callbacks, shared by the desktop sidebar and the mobile tabs.
    val reviewRootPath = currentDirectory ?: activeSession.directory
    val loadChildren: (String, (List<OpenCodeClient.FileNode>?) -> Unit) -> Unit =
        { path, onResult -> chatViewModel.reviewListFiles(path, onResult) }
    val loadFile: (String, (OpenCodeClient.FileContent?) -> Unit) -> Unit =
        { path, onResult -> chatViewModel.reviewFileContent(path, onResult) }
    val resolveRoot: ((String?) -> Unit) -> Unit = { onResult ->
        chatViewModel.serverDirectory(onResult)
    }

    // ------------------------------------------------------- A1 summary values
    // `research/wave6_summary.md` §2.2: every value is handed in by the call site;
    // the panel never reads a ViewModel. `changedFiles`/`additions`/`deletions`
    // come from `ChatState.diff` (the `session.diff` read the review pull above
    // refreshes), the branch from `reviewVcsInfo`, the server row from the
    // `ServerConfigStore` entry `ui/App.kt` already passes to the terminal.
    //
    // The server row shows the URL: `ServerConfigStore.Server` carries no label
    // (the settings page derives one by stripping the scheme), and
    // `SessionSummaryModel.serverRows` prefers a label when both are given — so
    // passing `label = null` is what makes the row show the address.
    val summaryChangedFiles = state.diff.size
    val summaryAdditions = state.diff.sumOf { it.additions }
    val summaryDeletions = state.diff.sumOf { it.deletions }
    val summaryServerUrl = serverStore?.server?.url

    // ------------------------------------------------------------ A8 export
    val context = LocalContext.current
    val exportSaver = rememberSessionExportSaver()
    val serverRevision by (serverStore?.revision ?: NO_SERVER_REVISION).collectAsState()
    // Export needs the **raw** `{info, messages}` JSON, and `ChatViewModel`
    // exposes parsed models only (`OpenCodeClient.session`/`messages`) — this wave
    // must not touch the VM. The screen therefore reads through a thin client
    // built from the same store the terminal panel uses: two `GET`s per export,
    // no shared state, and no change to any parse path.
    val exportClient = remember(serverStore, serverRevision) {
        serverStore?.server?.let { OpenCodeClient.forServer(it) }
    }

    // `⋮ → 导出会话` (`session/commands/export.ts` + `controller.tsx:169-190`):
    // fetch the two raw payloads off the main thread, serialize with the pure
    // `SessionExport`, then hand the string to the SAF saver. A cancelled save
    // dialog calls nothing (upstream shows no toast either); a write failure and
    // an unreachable server both report `oc_export_failed`.
    fun exportSession() {
        val client = exportClient
        if (client == null) {
            Toast.makeText(context, R.string.oc_export_failed, Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            val json = withContext(Dispatchers.IO) {
                runCatching {
                    SessionExport.serialize(
                        info = client.sessionJson(activeSession.id, reviewRootPath),
                        messages = client.messagesJson(activeSession.id, reviewRootPath),
                    )
                }.getOrNull()
            }
            val filename = SessionExport.filename(activeSession.id, title)
            if (json == null) {
                Toast.makeText(context, R.string.oc_export_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            exportSaver.save(filename, json) { ok ->
                val text = if (ok) {
                    context.getString(R.string.oc_export_success, filename)
                } else {
                    context.getString(R.string.oc_export_failed)
                }
                Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ------------------------------------------------- B2 context tab payload
    // The context tab (desktop sidebar, `session.tab.context`) and the mobile
    // usage view both render upstream's `SessionContextTab`, which needs the
    // **raw** payloads: `ChatViewModel`'s parsed messages flatten `info.tokens` to
    // one total, so the per-bucket rows cannot be derived from them. This reuses
    // the client the export already builds and the exact same two GETs
    // (`sessionJson` / `messagesJson` from `OpenCodeClient`), fetched **once** the
    // first time either mounting point becomes visible for this session / server
    // revision — never per recomposition, and no polling loop.
    var contextSession by remember(activeSession.id, serverRevision) { mutableStateOf<JSONObject?>(null) }
    var contextMessages by remember(activeSession.id, serverRevision) {
        mutableStateOf<List<JSONObject>>(emptyList())
    }
    var contextLoaded by remember(activeSession.id, serverRevision) { mutableStateOf(false) }
    val contextVisible = if (desktop) {
        reviewOpen && reviewTab == ReviewPanelTab.CONTEXT
    } else {
        usageOpen
    }
    LaunchedEffect(activeSession.id, serverRevision, contextVisible) {
        if (!contextVisible || contextLoaded) return@LaunchedEffect
        val client = exportClient ?: return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                client.sessionJson(activeSession.id, reviewRootPath) to
                    client.messagesJson(activeSession.id, reviewRootPath)
            }.getOrNull()
        } ?: return@LaunchedEffect
        contextSession = loaded.first
        contextMessages = loaded.second
        contextLoaded = true
    }
    // Pure projection (`SessionContextModel`); recomputed only when the payloads,
    // the provider catalog or the two screen-owned scalars change, so the panel
    // renders a plain object. `contextLimit` / `sessionCost` are passed in so the
    // 上限 / 使用率 / 总成本 rows can never disagree with the header's ring and
    // 总成本, which read the same two values.
    val contextSnapshot =
        remember(contextSession, contextMessages, availableProviders, contextLimit, sessionCost) {
            SessionContextModel.project(
                session = contextSession,
                messages = contextMessages,
                providers = availableProviders,
                contextLimit = contextLimit,
                sessionCost = sessionCost,
            )
        }

    // The header's own trailing cluster, in 40 dp touch targets: the search entry
    // (only while a timeline is mounted, `TimelineSearch.available`), the A1
    // summary button (desktop only), the context ring and the ⋮ menu. The review
    // toggle floats to the *left* of all of them, so its inset has to follow the
    // cluster size (it used to assume ring + ⋮ = 80 dp).
    val headerTrailingInset = HEADER_ICON_SLOT * 2 +
        (if (desktop) HEADER_ICON_SLOT else 0.dp) +
        (if (TimelineSearch.available) HEADER_ICON_SLOT else 0.dp)

    // ------------------------------------------------------------- header row
    val headerSlot: @Composable () -> Unit = {
        Box(modifier = Modifier.fillMaxWidth()) {
            SessionHeader(
                title = title,
                onRename = { newTitle ->
                    pendingTitle = newTitle
                    chatViewModel.renameSession(activeSession.id, newTitle) { ok ->
                        if (!ok) pendingTitle = null
                    }
                },
                onBack = onBack,
                onOpenDrawer = openDrawer,
                // item8 (W4-B): the ring gets the real denominator
                // (`ChatViewModel.contextLimit` ← `GET /config/providers`
                // `limit.context`) instead of the M4 placeholder `limit = null`,
                // plus the tooltip figures. Ring drawing / long-press panel live
                // in `ui/session/header/**`.
                contextPercent = ContextUsage.percentOf(contextTokens, contextLimit?.toLong()),
                contextTokens = contextTokens,
                contextCost = sessionCost,
                // Tap on the ring: desktop opens the review sidebar, the mobile
                // form switches to the review view (Android has no dedicated
                // Context/Usage view — W4-C report §4.2).
                onOpenContext = {
                    if (desktop) {
                        reviewOpen = true
                    } else {
                        mobileTabName = MobileForm.Tab.CHANGES.name
                    }
                },
                // Header menu (W4-E F-4 + A8): *Rename* is edited inline by the
                // header itself; *Delete* runs the drawer rows' destructive flow
                // (`home/sessions/controller.tsx:222-243`); *Export…* is the
                // session export this wave wired (the old "needs server
                // share/export" note is gone).
                onMenuAction = { action ->
                    when (action) {
                        SessionMenuAction.RENAME -> Unit // handled inside the header
                        SessionMenuAction.DELETE -> headerDeleteOpen = true
                        SessionMenuAction.EXPORT -> exportSession()
                    }
                },
                // A1: the wide-screen entry (upstream's header icon button). The
                // phone form reaches the same panel from `MobileSessionTabs`' ⋯.
                onToggleSummary = if (desktop) {
                    {
                        summaryOpen = !summaryOpen
                        if (summaryOpen) usageOpen = false
                    }
                } else {
                    null
                },
                summaryOpen = summaryOpen,
            )
            // Review panel toggle at the header's end, left of the trailing
            // cluster. Desktop only: upstream renders it under `isDesktop()` and
            // the mobile form uses the view tabs instead
            // (`session/screen.tsx:338-348`).
            if (desktop) {
                ReviewToggleButton(
                    active = reviewOpen,
                    onClick = { reviewOpen = !reviewOpen },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = headerTrailingInset),
                )
            }
            // A1 popover: a child of this Box, so the popup's `anchorBounds` *is*
            // the header band and the panel lands at its bottom-end.
            if (desktop) {
                SessionSummaryPopover(open = summaryOpen, onDismiss = { summaryOpen = false }) {
                    SessionSummaryPanel(
                        directory = reviewRootPath,
                        branch = reviewBranch,
                        changedFiles = summaryChangedFiles,
                        additions = summaryAdditions,
                        deletions = summaryDeletions,
                        serverLabel = null,
                        serverUrl = summaryServerUrl,
                        onClose = { summaryOpen = false },
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------ composer
    val composerRegion: @Composable BoxScope.() -> Unit = {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(c.bgBase)
                .onSizeChanged { composerHeight = with(density) { it.height.toDp() } }
                .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
        ) {
            if (configMissing) {
                Banner(text = stringResource(R.string.oc_setup_hint), color = c.textMuted)
            }
            // Question dock (upstream `session-composer-region`:
            // questionDock → permissionDock → composer). Shows the session's
            // first pending question; answers go through
            // `POST /question/:id/reply`, dismissal via `/reject`.
            pendingQuestion?.let { request ->
                SessionQuestionDock(
                    request = request,
                    responding = questionResponding == request.id,
                    onSubmit = { answers -> chatViewModel.replyQuestion(request.id, answers) },
                    onReject = { chatViewModel.rejectQuestion(request.id) },
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            // Form dock (v2; upstream `session-composer-region` renders the
            // pending form where v1 renders the question dock). Answers go
            // through `POST …/form/{formID}/reply{answer}`, dismissal via the
            // cancel route (`DELETE …/form/{formID}`).
            pendingForm?.let { form ->
                SessionFormDock(
                    form = form,
                    responding = questionResponding == form.id,
                    onSubmit = { answer -> chatViewModel.replyForm(form.id, answer) },
                    onDismiss = { chatViewModel.dismissForm(form.id) },
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            // Permission dock (upstream `session-composer-region`:
            // questionDock → permissionDock → composer). Shows the session's
            // first pending ask; replies go through
            // `POST /permission/:id/reply`.
            pendingPermission?.let { request ->
                SessionPermissionDock(
                    request = request,
                    responding = permissionResponding == request.id,
                    onDecide = { reply -> chatViewModel.replyPermission(request.id, reply) },
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            Composer(
                runStatus = state.runStatus,
                modelLabel = modelLabel,
                attachments = attachments,
                providers = availableProviders,
                selectedModel = selectedModel,
                modelOverrideActive = modelOverrideActive,
                onAttachClick = attachmentPicker::pick,
                onRemoveAttachment = { attachment -> attachments = attachments - attachment },
                onSelectModel = { providerID, modelID ->
                    chatViewModel.selectModel(providerID, modelID)
                },
                onUseServerDefaultModel = { chatViewModel.clearModelOverride() },
                onSearchFiles = { query, onResult -> chatViewModel.searchFiles(query, onResult) },
                onSend = { text, files ->
                    chatViewModel.send(text, files)
                    attachments = emptyList()
                },
                onStop = { chatViewModel.abort() },
                // W5-C wiring (report §4): drafts/history are keyed by session;
                // the agent chip, slash menu and `@` categories read the catalogs
                // above; `onSubmit` routes slash commands and shell mode.
                sessionID = activeSession.id,
                agents = availableAgents,
                selectedAgent = selectedAgent,
                agentOverrideActive = selectedAgent != null,
                onSelectAgent = { chatViewModel.selectAgent(it) },
                onUseDefaultAgent = { chatViewModel.selectAgent(null) },
                commands = availableCommands,
                skills = availableSkills,
                selectedVariant = selectedVariant,
                onSelectVariant = { chatViewModel.selectVariant(it) },
                onSubmit = { submission ->
                    chatViewModel.submit(
                        submission.text,
                        submission.attachments,
                        submission.shell,
                        submission.command,
                        submission.arguments,
                    )
                },
                // WAVE 2.4b: the structured submit (text + mention files/agents/
                // skills + delivery); the legacy `onSubmit`/`onSend` above stay
                // as the fallback chain. Attachments clear as today.
                followUpBehavior = followUpBehavior,
                generation = generation.name,
                queuedRows = inboxRows,
                queueBusy = inboxMutationInFlight,
                onQueueSteer = { id -> chatViewModel.steerQueued(id) },
                onQueueRemove = { id -> chatViewModel.cancelQueued(id) },
                onQueueConfirm = { id, replacement -> chatViewModel.confirmQueueEdit(id, replacement) },
                onStructuredSubmit = { m ->
                    chatViewModel.submit(
                        m.text,
                        attachments,
                        m.shell,
                        m.command,
                        m.arguments,
                        files = m.files,
                        agents = m.agents,
                        skills = m.skills,
                        delivery = m.delivery.wire,
                    )
                    attachments = emptyList()
                },
                onPasteAttachments = { pasted -> attachments = pasted },
            )
        }
    }

    // -------------------------------------------------------------- timeline
    val timelinePane: @Composable (Modifier, (@Composable () -> Unit)?) -> Unit =
        { timelineModifier, header ->
            Box(modifier = timelineModifier) {
                MessageTimeline(
                    messages = state.messages,
                    runStatus = state.runStatus,
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 16.dp,
                        bottom = composerHeight + TIMELINE_BOTTOM_GAP,
                    ),
                    stickyHeader = header,
                    toolOpen = toolOpen,
                    onToggleTool = { key, open -> toolOpen[key] = !open },
                    // Upstream suppresses the "Working" row while a permission /
                    // question dock is waiting (`project-avatar-state.ts:41-50`).
                    hasPendingRequest = state.pendingPermissions.isNotEmpty() ||
                        state.pendingQuestions.isNotEmpty() ||
                        state.pendingForms.isNotEmpty(),
                    // §3.3 card replaces the M1.5 composer error banner; the
                    // setup hint below still has its own banner (it is a
                    // configuration problem, not a run failure).
                    errorMessage = state.error,
                    modifier = Modifier.fillMaxSize(),
                )
                // Empty session: never a blank screen (M1 review "空态兜底").
                if (state.messages.isEmpty()) {
                    Text(
                        text = stringResource(R.string.oc_timeline_empty),
                        color = c.textFaint,
                        fontSize = 13.sp,
                        modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
                    )
                }
                composerRegion()
            }
        }

    // ------------------------------------------------------- drawer contents
    val drawerContent: @Composable () -> Unit = {
        SessionDrawerContent(
            sessions = sessions,
            currentSessionId = activeSession.id,
            // M6: the working session's row carries the 25-dot marquee
            // (`session-progress-indicator-v2`).
            runningSessionId = activeSession.id
                .takeIf { state.runStatus == ChatStore.RunStatus.RUNNING },
            sheet = !desktop,
            onNewSession = {
                closeDrawer()
                onNewSessionDraft()
            },
            onSelect = switchSession,
            // W4-C row menu (long press 500 ms): Rename / Delete, confirmed by
            // the screen-level dialogs below.
            onRenameSession = { session -> renameTarget = session },
            onDeleteSession = { session -> deleteTarget = session },
        )
    }

    // ------------------------------------------------------------ body column
    val bodyContent: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(c.bgBase)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            if (desktop) {
                // Upstream's desktop body is a **column**: the timeline row on
                // top, the terminal as a full-width bottom panel below
                // (`session/screen.tsx:507-540` mounts `TerminalPanel stacked`
                // under `class="relative min-h-0 shrink-0"` with a vertical
                // resize handle, min 100 / max 60% of the viewport). The terminal
                // is *not* a weighted member of the row: an unweighted `Row` child
                // is measured *before* the weighted one, so a weighted timeline
                // used to end up zero-width. B3 adds the upstream `side`
                // placement as a **fixed-width** last child of that row instead
                // (upstream's right side region, `screen-layout.ts:19-20`).
                Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
                Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    timelinePane(Modifier.weight(1f).fillMaxHeight(), headerSlot)
                    // Review side panel (upstream v2 review/files sidebar),
                    // docked at the end of the timeline row while `reviewOpen`.
                    if (reviewOpen) {
                        // Keyed by session: tree/expansion/preview/tab state must
                        // not leak across a drawer switch.
                        androidx.compose.runtime.key(activeSession.id) {
                            SessionReviewPanel(
                                diffs = state.diff,
                                branch = reviewBranch,
                                // The server-confirmed workspace wins (sessions
                                // outside the server's own cwd never appear in the
                                // `/session` list backfill, so the local copy can
                                // stay null).
                                rootPath = reviewRootPath,
                                onLoadChildren = loadChildren,
                                onLoadFile = loadFile,
                                onResolveRoot = resolveRoot,
                                onClose = { reviewOpen = false },
                                // W5-R: the panel owns its width now (drag +
                                // double-tap reset + `rememberSaveable`); seed it
                                // with upstream's default instead of the legacy
                                // fixed 340 dp.
                                modifier = Modifier.fillMaxHeight(),
                                initialWidth = ReviewPanelWidth.DEFAULT.dp,
                                hasVcs = if (reviewBranch != null) true else null,
                                defaultBranch = reviewDefaultBranch,
                                vcsDiffs = vcsDiffs,
                                onRequestVcsDiff = { scope ->
                                    chatViewModel.reviewVcsDiff(scope.name.lowercase()) { list ->
                                        vcsDiffs = list ?: emptyList()
                                    }
                                },
                                tab = reviewTab,
                                onTabChange = { reviewTabName = it.name },
                                openPath = openFilePath,
                                // B2: the context tab's projection of the raw
                                // payloads read above (empty until they land).
                                contextSnapshot = contextSnapshot,
                            )
                            }
                        }
                        // B3: **side** placement — the same pane as the last
                        // member of the timeline row (upstream mounts it in the
                        // right side region, `session/screen.tsx:460-540`), so it
                        // shares the row with the review panel. `TerminalDock`
                        // renders only for the placement the stored setting
                        // names, so this slot and the `bottom` one below can both
                        // be called unconditionally.
                        if (terminalDocked) {
                            TerminalDock(
                                placement = TerminalPlacement.SIDE,
                                sessionId = activeSession.id,
                                directory = activeSession.directory,
                                onClose = onCloseTerminalDock,
                                modifier = Modifier.fillMaxHeight(),
                            )
                        }
                    }
                    // B3: **bottom** placement — full width, upstream's
                    // `DEFAULT_TERMINAL_HEIGHT = 280` (`layout.tsx:30`), pinned to
                    // the bottom of the column under the timeline row and capped
                    // at 60% of the viewport exactly like `panel.tsx:75-76`
                    // (`Math.min(height, view * 0.6)`), so a short window or an
                    // open IME can never swallow the timeline. Both the default
                    // and the drag bounds live in `TerminalPaneSize` now.
                    if (terminalDocked) {
                        TerminalDock(
                            placement = TerminalPlacement.BOTTOM,
                            sessionId = activeSession.id,
                            directory = activeSession.directory,
                            onClose = onCloseTerminalDock,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            } else {                // Mobile: header (with ☰ → bottom sheet) then the four
                // full-width view tabs, then the selected view. The timeline
                // header moves out of `MessageTimeline`'s sticky slot so the
                // tab strip sits between it and the content.
                headerSlot()
                MobileSessionTabs(
                    current = mobileTab,
                    onSelect = { tab ->
                        usageOpen = false
                        summaryOpen = false
                        mobileTabName = tab.name
                    },
                    // `⋮` → Usage (`view.tsx:94`).
                    onOpenUsage = {
                        summaryOpen = false
                        usageOpen = true
                    },
                    // `⋮` → Session details (A1): the phone form's mounting point
                    // for `SessionSummaryPanel` (`screen.tsx:191-240`), which
                    // upstream renders as the whole content area rather than as a
                    // popover. This row used to be the disabled placeholder of
                    // W4-C §4.3.
                    onOpenSummary = {
                        usageOpen = false
                        summaryOpen = true
                    },
                )
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    if (usageOpen) {
                        MobileUsagePanel(
                            percent = ContextUsage.percentOf(contextTokens, contextLimit?.toLong()),
                            tokens = contextTokens,
                            limit = contextLimit,
                            cost = sessionCost,
                            onClose = { usageOpen = false },
                            modifier = Modifier.fillMaxSize(),
                            // B2: upstream's mobile usage view *is* the context
                            // tab (`screen.tsx:307-309`) — the three headline
                            // figures above plus the full tab body.
                            snapshot = contextSnapshot,
                        )
                    } else if (summaryOpen) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            SessionSummaryPanel(
                                directory = reviewRootPath,
                                branch = reviewBranch,
                                changedFiles = summaryChangedFiles,
                                additions = summaryAdditions,
                                deletions = summaryDeletions,
                                serverLabel = null,
                                serverUrl = summaryServerUrl,
                                onClose = { summaryOpen = false },
                                // 44 dp rows + the full-width card, upstream's
                                // phone variant (`summary.css:299-307`).
                                mobile = true,
                                modifier = Modifier.align(Alignment.TopCenter),
                            )
                        }
                    } else when (mobileTab) {
                        MobileForm.Tab.CHANGES -> ReviewChangesTab(
                            diffs = state.diff,
                            modifier = Modifier.fillMaxSize(),
                        )

                        MobileForm.Tab.FILES -> ReviewFilesTab(
                            rootPath = reviewRootPath,
                            onLoadChildren = loadChildren,
                            onLoadFile = loadFile,
                            onResolveRoot = resolveRoot,
                            modifier = Modifier.fillMaxSize(),
                            openPath = openFilePath,
                            // W5-R C5: mobile file tree finally shows the A/M/D
                            // badges (the desktop call site already passes them).
                            diffs = state.diff,
                        )

                        // Inline terminal (upstream: the mobile tab opens the
                        // bottom-docked terminal panel; `screen.tsx:298-305`).
                        MobileForm.Tab.TERMINAL -> EmbeddedTerminalPanel(
                            directory = activeSession.directory,
                            onClose = { mobileTabName = MobileForm.Tab.SESSION.name },
                            modifier = Modifier.fillMaxSize(),
                        )

                        else -> timelinePane(Modifier.fillMaxSize(), null)
                    }
                }
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (desktop) {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ModalDrawerSheet(
                        modifier = Modifier.width(drawerWidth),
                        // The View shell's drawer is a square `bg-base` panel
                        // with a 1 px end border (`bg_session_drawer.xml`); the
                        // M3 default rounds the end edge and tints it with the
                        // Material surface, which fights the OC2 tokens. Square
                        // + the sheet's own elevation it is.
                        drawerShape = RectangleShape,
                        drawerContainerColor = c.bgBase,
                        drawerContentColor = c.textBase,
                    ) {
                        drawerContent()
                    }
                },
            ) {
                bodyContent()
            }
        } else {
            bodyContent()
            SessionBottomSheet(
                open = drawerOpen,
                onDismissRequest = { drawerOpen = false },
            ) {
                drawerContent()
            }
        }

        // Row-menu confirmations paint above both shells (the drawer sheet is
        // only as tall as its list, so a dialog inside it would be misplaced).
        renameTarget?.let { session ->
            SessionRenameDialog(
                initialTitle = session.title,
                onDismiss = { renameTarget = null },
                onConfirm = { newTitle ->
                    renameTarget = null
                    chatViewModel.renameSession(session.id, newTitle) { ok ->
                        if (ok) {
                            sessions = sessions.map { row ->
                                if (row.id == session.id) row.copy(title = newTitle) else row
                            }
                            if (session.id == activeSession.id) pendingTitle = newTitle
                        }
                    }
                },
            )
        }
        deleteTarget?.let { session ->
            SessionDeleteDialog(
                sessionTitle = session.title,
                onDismiss = { deleteTarget = null },
                onConfirm = {
                    deleteTarget = null
                    val wasActive = session.id == activeSession.id
                    chatViewModel.deleteSession(session.id) { ok ->
                        if (ok) {
                            sessions = sessions.filterNot { it.id == session.id }
                            // Deleting the session on screen leaves nothing to
                            // show: back out to the previous destination.
                            if (wasActive) {
                                closeDrawer()
                                onBack()
                            }
                        }
                    }
                },
            )
        }
        // Header ⋮ → Delete (W4-E F-4). Deleting the session on screen leaves
        // nothing to show, so the screen backs out exactly like the drawer path.
        if (headerDeleteOpen) {
            SessionDeleteDialog(
                sessionTitle = title,
                onDismiss = { headerDeleteOpen = false },
                onConfirm = {
                    headerDeleteOpen = false
                    chatViewModel.deleteSession(activeSession.id) { ok ->
                        if (ok) onBack()
                    }
                },
            )
        }
    }
}

/** The session currently on screen (nav entry point or a drawer switch). */
private data class ActiveSession(
    val id: String,
    val title: String?,
    val directory: String?,
)

/** Inline hint above the composer (config missing). */
@Composable
private fun Banner(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        fontSize = 12.sp,
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
    )
}

/**
 * Header toggle for the review side panel (upstream `Toggle review panel`
 * command, `oc_command_review_toggle`): the established 40 dp circular target
 * with a 16 dp icon; active = accent icon on a `layer02` disc.
 */
@Composable
private fun ReviewToggleButton(
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (active) c.layer02 else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_oc_review),
            contentDescription = stringResource(R.string.oc_command_review_toggle),
            tint = if (active) c.iconAccent else c.iconBase,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** Review panel width, upstream `SESSION_REVIEW_V2_SIDEBAR_WIDTH` range (~320–360 px). */
private val REVIEW_PANEL_WIDTH = 340.dp

/**
 * `file.edited` debounce (`research/wave5_events.md` §5.4): a single tool call can
 * emit a burst of frames and every one of them invalidates the diff/todos read.
 */
private const val FILE_EDITED_DEBOUNCE_MS = 300L

/**
 * Stand-in for `ServerConfigStore.revision` when the screen was built without a
 * store (previews, isolated tests): constant, so the `collectAsState` above stays
 * unconditional.
 */
private val NO_SERVER_REVISION = MutableStateFlow(0)

/** One header touch target (`IconButtonV2 size="large"`): the trailing-cluster unit. */
private val HEADER_ICON_SLOT = 40.dp

/** Breathing room between the last message and the floating composer. */
private val TIMELINE_BOTTOM_GAP = 12.dp

/** `ChatView.DRAWER_WIDTH_RATIO` — 78 % of the screen … */
private const val DRAWER_WIDTH_RATIO = 0.78f

/** … capped at 320 dp (`ChatView.DRAWER_WIDTH_MAX_DP`). */
private val DRAWER_WIDTH_MAX = 320.dp
