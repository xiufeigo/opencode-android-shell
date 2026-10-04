package com.xiufeigo.opencodeshell.ui.session

import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import com.xiufeigo.opencodeshell.ui.ShellChromeBudget
import com.xiufeigo.opencodeshell.ui.renameFailureText
import com.xiufeigo.opencodeshell.ui.renameFailureToast
import com.xiufeigo.opencodeshell.ui.sessionHeaderTitle
import com.xiufeigo.opencodeshell.ui.sessionRenameSeed
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.ChatStore
import com.xiufeigo.opencodeshell.ChatViewModel
import com.xiufeigo.opencodeshell.OpenCodeClient
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ServerConfigStore
import com.xiufeigo.opencodeshell.v2.SessionOpenFailure
import com.xiufeigo.opencodeshell.v2.SessionRenameOutcome
import com.xiufeigo.opencodeshell.ui.overlay.UsageExceededDialog
import com.xiufeigo.opencodeshell.ui.overlay.UsageUpsellGate
import com.xiufeigo.opencodeshell.ui.overlay.McpChooserDialog
import com.xiufeigo.opencodeshell.ui.nav.SessionDotSources
import com.xiufeigo.opencodeshell.ui.palette.CommandRequest
import com.xiufeigo.opencodeshell.ui.palette.LocalSessionCommandIntents
import com.xiufeigo.opencodeshell.ui.palette.OpenFileRequest
import com.xiufeigo.opencodeshell.ui.palette.SessionCommands
import com.xiufeigo.opencodeshell.ui.palette.appCommands
import com.xiufeigo.opencodeshell.ui.session.actions.LocalSessionActionsHost
import com.xiufeigo.opencodeshell.ui.session.actions.SessionActionsMenuItem
import com.xiufeigo.opencodeshell.ui.session.browser.LocalBrowserReloadRequest
import com.xiufeigo.opencodeshell.ui.session.browser.SessionBrowserPane
import com.xiufeigo.opencodeshell.ui.servers.SshTunnel
import com.xiufeigo.opencodeshell.ui.servers.SshTunnelPanel
import com.xiufeigo.opencodeshell.ui.session.composer.AttachmentPolicy
import com.xiufeigo.opencodeshell.ui.session.composer.Composer
import com.xiufeigo.opencodeshell.ui.session.composer.ComposerDrafts
import com.xiufeigo.opencodeshell.ui.session.composer.MentionSearchScope
import com.xiufeigo.opencodeshell.ui.session.composer.rememberAttachmentPicker
import com.xiufeigo.opencodeshell.ui.session.composer.toDraft
import com.xiufeigo.opencodeshell.ui.session.context.SessionContextLoad
import com.xiufeigo.opencodeshell.ui.session.context.SessionContextModel
import com.xiufeigo.opencodeshell.ui.session.context.importFailureCopy
import com.xiufeigo.opencodeshell.ui.session.dock.SessionFormDock
import com.xiufeigo.opencodeshell.ui.session.dock.SessionPermissionDock
import com.xiufeigo.opencodeshell.ui.session.drawer.MobileForm
import com.xiufeigo.opencodeshell.ui.session.drawer.RenameFailureDialog
import com.xiufeigo.opencodeshell.ui.session.drawer.SessionBottomSheet
import com.xiufeigo.opencodeshell.ui.session.drawer.SessionDeleteDialog
import com.xiufeigo.opencodeshell.ui.session.drawer.SessionDrawerContent
import com.xiufeigo.opencodeshell.ui.session.drawer.SessionRenameDialog
import com.xiufeigo.opencodeshell.ui.session.export.ExportAttemptLedger
import com.xiufeigo.opencodeshell.ui.session.export.ExportFeedback
import com.xiufeigo.opencodeshell.ui.session.export.ExportFinish
import com.xiufeigo.opencodeshell.ui.session.export.ExportPhase
import com.xiufeigo.opencodeshell.ui.session.export.ExportStageResult
import com.xiufeigo.opencodeshell.ui.session.export.ImportFinish
import com.xiufeigo.opencodeshell.ui.session.export.SessionExport
import com.xiufeigo.opencodeshell.ui.session.export.EXPORT_FETCH_TIMEOUT_MS
import com.xiufeigo.opencodeshell.ui.session.export.rememberSessionExportSaver
import com.xiufeigo.opencodeshell.ui.session.export.rememberSessionImporter
import com.xiufeigo.opencodeshell.ui.session.export.withExportDeadline
import com.xiufeigo.opencodeshell.ui.session.header.HEADER_EDGE_PADDING
import com.xiufeigo.opencodeshell.ui.session.header.ContextUsage
import com.xiufeigo.opencodeshell.ui.session.header.LocalSshTunnelHost
import com.xiufeigo.opencodeshell.ui.session.header.SessionHeader
import com.xiufeigo.opencodeshell.ui.session.header.SessionMenuAction
import com.xiufeigo.opencodeshell.ui.session.header.SessionSummaryPopover
import com.xiufeigo.opencodeshell.ui.session.header.TitleEditFence
import com.xiufeigo.opencodeshell.ui.session.header.sshPanelTunnel
import com.xiufeigo.opencodeshell.ui.settings.SettingsServerBridge
import com.xiufeigo.opencodeshell.ui.settings.SettingsServerData
import com.xiufeigo.opencodeshell.ui.settings.SettingsV2Delegate
import com.xiufeigo.opencodeshell.ui.session.review.MobileSessionTabs
import com.xiufeigo.opencodeshell.ui.session.review.MobileUsagePanel
import com.xiufeigo.opencodeshell.ui.session.review.ReviewChangeScope
import com.xiufeigo.opencodeshell.ui.session.review.ReviewChangesTab
import com.xiufeigo.opencodeshell.ui.session.review.ReviewCommentStore
import com.xiufeigo.opencodeshell.ui.session.review.ReviewFilesTab
import com.xiufeigo.opencodeshell.ui.session.review.ReviewPanelTab
import com.xiufeigo.opencodeshell.ui.session.review.ReviewPanelWidth
import com.xiufeigo.opencodeshell.ui.session.review.ReviewScopes
import com.xiufeigo.opencodeshell.ui.session.review.ReviewVcsBase
import com.xiufeigo.opencodeshell.ui.session.review.ReviewVcsDiffResult
import com.xiufeigo.opencodeshell.ui.session.review.SessionReviewPanel
import com.xiufeigo.opencodeshell.ui.session.review.rememberReviewGitInit
import com.xiufeigo.opencodeshell.ui.session.review.rememberReviewProjectVcs
import com.xiufeigo.opencodeshell.ui.session.review.rememberVcsBase
import com.xiufeigo.opencodeshell.ui.session.summary.SessionSummaryPanel
import com.xiufeigo.opencodeshell.ui.session.timeline.MessageNav
import com.xiufeigo.opencodeshell.ui.session.timeline.MessageTimeline
import com.xiufeigo.opencodeshell.ui.session.timeline.TimelineSearch
import com.xiufeigo.opencodeshell.ui.terminal.EmbeddedTerminalPanel
import com.xiufeigo.opencodeshell.ui.terminal.TerminalDock
import com.xiufeigo.opencodeshell.ui.terminal.TerminalEntryButton
import com.xiufeigo.opencodeshell.ui.terminal.TerminalPlacement
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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
 *   (`home/sessions/view.tsx:30,597-610,694-701`) with Rename / Delete; Rename is
 *   confirmed by a screen-level dialog and goes through
 *   `ChatViewModel.renameSession`, while Delete arms the shell's one delete card
 *   (`SessionDeleteHost`, v70 P2) — the same card the header ⋮ opens — whose
 *   write is `ChatViewModel.deleteSession`.
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
     *
     * v43: [onToggleTerminalDock] mounts the wide-screen terminal entry chip
     * (M5 phase 1, `ui/App.kt`'s `TerminalEntryButton`) **inside the timeline
     * pane**. It used to be `Alignment.TopEnd` of the whole session body, which
     * put it on top of the docked review panel's tab row — the chip covered the
     * 「文件」tab, which then read as 「File…」
     * (`research/v43_verify/tE/v43_32_panel_wide.png`, `v43_34_resize2.png`).
     * Anchoring it to the timeline's own trailing edge keeps it clear of the
     * panel in every panel width, and moves it by exactly zero when no panel is
     * docked. Null hides it (mobile: the Terminal view tab owns the entry).
     */
    terminalDocked: Boolean = false,
    onCloseTerminalDock: () -> Unit = {},
    onToggleTerminalDock: (() -> Unit)? = null,
    /**
     * P1-C: host entry points for the composer's `/` aliases that need the app
     * layer — `file.open` re-opens the palette files-only, `terminal.toggle`
     * drives the dock/route (`ui/App.kt` owns both), `mcp.toggle` opens the
     * MCP chooser dialog. Null keeps the in-screen fallback (the Files view
     * for `/open`; the mobile Terminal tab — or the dock close on desktop —
     * for `/terminal`; an inline chooser mount for `/mcp`), so every alias
     * executes its action instead of being sent as a prompt. The host may pass
     * the real actions later to upgrade both rows; all parameters stay
     * defaulted so existing call sites compile unchanged.
     */
    onOpenFilePicker: (() -> Unit)? = null,
    onToggleTerminal: (() -> Unit)? = null,
    onToggleMcp: (() -> Unit)? = null,
    /**
     * B-12: `Settings.keybinds` — the composer popover's keybind column draws the
     * **effective** chord, the same table `ui/App.kt` matches events against.
     * Defaulted to the empty table, so a host that never read the store keeps
     * every row on its `SHORTCUT_ROWS` default.
     */
    keybindOverrides: Map<String, String> = emptyMap(),
    /**
     * B-13: the model picker's「管理模型」row (`select-dialog.tsx:544-556`) — the
     * app layer opens Settings→Models (`ui/App.kt` owns the dialog and the tab).
     * Null (previews, a screen mounted without the settings route) hides the row.
     */
    onManageModels: (() -> Unit)? = null,
    /**
     * B-16: the「底部导航」placement (`settings.general.row.mobileTitlebarBottom`,
     * `general.tsx:369-381`) as it reaches the phone body. `ui/App.kt` computes
     * it from the stored position (`bottom = mobile() && …`,
     * `titlebar.tsx:64-65`) and already places the session tab strip on the
     * matching edge; this flag moves the **header row** with it, so the mobile
     * form is the one row upstream moves rather than half of it
     * (`titlebar.tsx:150-164`: the whole `<header data-slot="titlebar-v2">` gets
     * `order-last`). The view tabs ([MobileSessionTabs]) deliberately stay at
     * the top — they are the session panel's own tab row, not titlebar chrome.
     * False (the default, and every desktop width) keeps the header above them.
     */
    titlebarBottom: Boolean = false,
    /**
     * v39: the header ⋮ menu's 「导入会话…」 row created a session — open it.
     * Upstream ends the same flow by opening what it imported
     * (`home.project.openProjectSession`, `home/projects/controller.tsx:129-131`),
     * and the Home entry in this shell already does (`HomeScreen.onSessionImported`);
     * the session route is the host's to walk (`ui/App.kt`), so this carries the
     * created id out. Defaulted to a no-op: previews and hosts without a nav
     * stack keep the toast-only outcome.
     */
    onSessionImported: (String) -> Unit = {},
) {
    val c = OcTheme.colors

    // Pending attachments live in screen state (see the owner next to
    // `activeSession` below) so the SAF picker, the preview strip and `send`
    // all read one owner.

    // U-3 draft handoff: a failed send hands text + attachments + message ref
    // back (`ComposerDrafts`). Attachments re-enter the screen state above;
    // the ref lands here and scrolls the timeline back to it when the rows
    // know its position (best-effort: a ref outside the snapshot window is
    // kept but cannot scroll).
    var restoredMessageRef by remember { mutableStateOf<String?>(null) }
    // Hoisted so the restore scroll below can drive it; the timeline keeps
    // owning follow/bottom behavior internally.
    val timelineListState = rememberLazyListState()
    var timelineRowIndex by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }

    // F-3 in-app browser: timeline links (markdown `LinkAnnotation.Url` taps
    // and the tool/websearch `LocalUriHandler.openUri` call sites) resolve
    // through this handler instead of the system browser. Only http(s) stays
    // in-app — anything else (file/custom schemes) still delegates outward,
    // and the pane's "open externally" row reuses that same outward path.
    var browserUrl by rememberSaveable { mutableStateOf<String?>(null) }
    // B-18 `browser.open`: the pane's last URL. Upstream's browser is a
    // side-panel tab whose `tabs.open` shows the sidecar's own start page; the
    // native pane is a URL-driven overlay, so "open" means "show it again at
    // what it last displayed" — the empty state (the pane's own address-less
    // screen) when a session has not opened anything yet. Saveable so it
    // survives the rotation that also restores an open pane.
    var lastBrowserUrl by rememberSaveable { mutableStateOf<String?>(null) }
    // B-18 `browser.reload`: one-shot counter forwarded to the mounted pane
    // (`LocalBrowserReloadRequest`). The pane owns the `WebView`, so the command
    // arrives as a request like every other screen-local chord.
    var browserReloadRequest by remember { mutableStateOf(0) }
    val systemUriHandler = LocalUriHandler.current
    val openExternalUrl: (String) -> Unit = { url ->
        try {
            systemUriHandler.openUri(url)
        } catch (_: Exception) {
        }
    }
    // Keyed on the system handler (stable in practice) so the provided
    // instance — and everything consuming it — survives recompositions.
    val inAppUriHandler = remember(systemUriHandler) {
        object : UriHandler {
            override fun openUri(uri: String) {
                if (uri.startsWith("http://", ignoreCase = true) ||
                    uri.startsWith("https://", ignoreCase = true)
                ) {
                    lastBrowserUrl = uri
                    browserUrl = uri
                } else {
                    openExternalUrl(uri)
                }
            }
        }
    }

    // The session this screen currently shows. `sessionId` is the entry point
    // (nav argument); switching through the drawer only re-points this state.
    var activeSession by remember(sessionId) {
        mutableStateOf(ActiveSession(id = sessionId, title = sessionTitle, directory = null))
    }
    // Pending attachments live here (not inside the composer) so the SAF
    // picker, the preview strip and `send` all read one owner;
    // `rememberAttachmentPicker` registers the launcher and does the IO read.
    var attachments by remember { mutableStateOf<List<ChatViewModel.Attachment>>(emptyList()) }
    /**
     * P1-C: the single writer of [attachments]. Every change is mirrored into
     * the session's draft handoff ([ComposerDrafts.setAttachments]), so a
     * drawer switch, a home round-trip or a session re-entry finds the chips
     * where the composer mount-adopt looks. The switch path below clears the
     * screen list *directly* (bypassing this): the outgoing key's mirror is
     * already current, and mirroring the empty list under the incoming key
     * would wipe the stash being returned to.
     */
    fun stageAttachments(next: List<ChatViewModel.Attachment>) {
        attachments = next
        ComposerDrafts.setAttachments(ComposerDrafts.key(activeSession.id), next.map { it.toDraft() })
    }
    val attachmentPicker = rememberAttachmentPicker(attachments) { stageAttachments(it) }
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
    // Mobile Changes scope switch (desktop's `SessionReviewPanel` owns its own
    // `scopeName` state): Git / Branch / Last-turn over `ReviewScopes`, with
    // the same `rememberSaveable` lifetime as the panel's copy.
    //
    // `null` until the user picks one: the resolution below is what decides the
    // default, and it depends on facts that arrive asynchronously (the project
    // verdict, the vcs branch). Seeding `LAST_TURN` here is exactly the drift
    // this fixes — the desktop panel opens on `ReviewScopes.defaultScope()`,
    // i.e. upstream's `git` whenever the project has a repository
    // (`review/model.ts:40 mode() ?? "git"`).
    var mobileReviewScopeName by rememberSaveable { mutableStateOf<String?>(null) }
    // `file.open` target (palette) — see the intents effect below.
    var openFilePath by remember { mutableStateOf<String?>(null) }
    var reviewBranch by remember(activeSession.id) { mutableStateOf<String?>(null) }
    var reviewDefaultBranch by remember(activeSession.id) { mutableStateOf<String?>(null) }
    // W5-R: the review panel's Git / Branch scopes read `GET /vcs/diff?mode=`
    // (verified against opencode 1.18.31 — `mode=git|branch` returns JSON).
    //
    // The last VCS-scope pull: null while one is out (or before this session
    // asked for one), so the three states the Changes body must tell apart are
    // one value — 「read out」 (null), 「answered, empty or not」 (Loaded) and
    // 「never came back」 (Failed). An empty list means 「no changes」 only in the
    // second case; the read that can hang on a large working tree is this one
    // (see `ChatViewModel.reviewVcsDiff`), and reporting its failure as an empty
    // change list is the v10 misreport.
    var vcsDiffResult by remember(activeSession.id) {
        mutableStateOf<ReviewVcsDiffResult?>(null)
    }
    val vcsDiffs = (vcsDiffResult as? ReviewVcsDiffResult.Loaded)?.diffs ?: emptyList()
    val vcsDiffFailed = vcsDiffResult is ReviewVcsDiffResult.Failed
    val vcsDiffPending = vcsDiffResult == null
    // One VCS-scope request for every surface that asks: the desktop panel's
    // scope switch and its failure-state retry, plus the mobile Changes page.
    // Routing them through one lambda is what makes 「a new attempt clears the
    // previous verdict」 true whichever surface asked.
    val requestVcsDiff: (ReviewChangeScope) -> Unit = { reviewScope ->
        vcsDiffResult = null
        chatViewModel.reviewVcsDiff(reviewScope.name.lowercase()) { result ->
            vcsDiffResult = result
        }
    }
    // F-2 line comments: one store per session, shared by the desktop review
    // panel and the mobile Changes page (upstream `useComments`, per-session).
    val reviewCommentStore = remember(activeSession.id) { ReviewCommentStore() }
    // F-2 S-step + comment send: diff interactions append text to the composer
    // draft (`ChatViewModel.appendComposerText`).
    val appendReviewText: (String) -> Unit = { chatViewModel.appendComposerText(it) }
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
    // v82 BUG-B: a rename that did not land parks its sentence here. The route
    // already toasts the same text (`renameFailureToast`), but a toast is a
    // 3.5 s system window — the screenshots that reported "silent rollback"
    // landed 0.9 s after the one this rename raised had been killed — and the
    // inline title's revert is the only other thing the failure leaves behind.
    // The card below keeps the sentence until the reader dismisses it, and it is
    // cleared by the next attempt or a session switch (keyed on `sessionId`).
    var renameFailureMessage by remember(sessionId) { mutableStateOf<String?>(null) }
    // v70 P2: the delete card is **not** screen state. It used to be two
    // `remember`s here (one per entry point), which is why the v69 fix held on
    // one device and not the other: the card and the `DELETE`'s answer lived in
    // this composition, so a recomposition or a re-created screen took the
    // refusal — and the success half that closes the tab — with it
    // (`research/v70_verify`: 5560 kept the card, 5556 lost it). The host owns
    // both, above this screen, and both entry points arm the one card
    // (`SessionDeleteHost`).
    val deleteHost = LocalSessionDeleteHost.current
    // v83 P1: the not-found page's own exit state, kept by the host (see
    // `SessionDeleteHost.entry`) and read back through the id on screen — a
    // refusal latched for one entry must never paint on another's page.
    val entryDelete = deleteHost?.entry?.takeIf { it.id == activeSession.id }

    // The only writer of `mobileTabName`. Every switch drops IME focus first —
    // unconditionally, so no stale comparison can skip it (the silent-prompt
    // fix: a hidden editor that keeps the keyboard keeps receiving injected
    // keys, and Enter submits a prompt). Clearing runs before recomposition,
    // so a switch *to* the session still lets the composer auto-focus (B7).
    val focusManager = LocalFocusManager.current
    val switchMobileTab: (MobileForm.Tab) -> Unit = { tab ->
        focusManager.clearFocus()
        mobileTabName = tab.name
    }
    // v31 BUG-1: the overlay's own keyboard handle — a full-body panel opened
    // while the composer held focus used to leave the IME up (and therefore
    // swallow the first BACK).
    val keyboardController = LocalSoftwareKeyboardController.current
    // v31 BUG-2: the header's inline title editor is open. Hoisted only so the
    // tap-away below can be gated on it — the draft itself stays in the field.
    var titleEditing by remember { mutableStateOf(false) }
    // v97 N3 fix2: the editor's save authorization. Armed by the tap-away handler
    // below, immediately before it clears focus, and consumed by the field's blur.
    // A blur that arrives unarmed came from outside the session — the chrome's
    // 首页/Home button, the tab strip, the back chevron, the drawer entry are all
    // `Modifier.clickable`, and a clickable takes focus — so the draft is
    // discarded there instead of being written to the server.
    val titleEditFence = remember { TitleEditFence() }

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
            // P1-C: stash the outgoing handoff before re-pointing. The text is
            // already in the store (composer keystrokes + dispose flush) and
            // the chips are mirrored by `stageAttachments`; the live message
            // ref has no other writer, so it is handed over explicitly (a null
            // live ref clears a consumed stash — consistent, nothing is live).
            ComposerDrafts.setMessageRef(
                ComposerDrafts.key(activeSession.id),
                restoredMessageRef?.let { ComposerDrafts.MessageRef(it) },
            )
            restoredMessageRef = null
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
    // B-5: the screen-local command channel's fallback (see the handler below).
    val noCommandRequest = remember { MutableStateFlow<CommandRequest?>(null) }
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
            switchMobileTab(
                if (mobileTab == MobileForm.Tab.CHANGES) {
                    MobileForm.Tab.SESSION
                } else {
                    MobileForm.Tab.CHANGES
                },
            )
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
            switchMobileTab(MobileForm.Tab.FILES)
        }
        if (requested != null) {
            openFilePath = requested.path
            intents?.consume(requested)
        }
    }

    LaunchedEffect(activeSession.id) {
        // P1-C: intentional direct write, not `stageAttachments` — the outgoing
        // key's mirror is already current (every change mirrors), and mirroring
        // this empty list under the incoming key would wipe the stash the
        // composer mount-adopt is about to return.
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
        // 会话打开 (unread + summary freshness): ack the idle boundary this
        // viewer observes (`wait` + `POST …/view`), and pull the summary
        // panel's background-work derivation.
        chatViewModel.ackSessionViewed()
        chatViewModel.refreshBackgroundTasks()
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
    // P1-C: stash the live message ref whenever the shown session changes or
    // the screen leaves for home (chips are mirrored by `stageAttachments`,
    // text by the composer — the ref is the only half with no other writer).
    // Bridges, not keys: the ref may land after this effect starts, and the
    // outgoing key must be read at dispose time, not at start time.
    val stashRefBridge by rememberUpdatedState(restoredMessageRef)
    val stashKeyBridge by rememberUpdatedState(activeSession.id)
    DisposableEffect(sessionId, activeSession.id) {
        onDispose {
            stashRefBridge?.let {
                ComposerDrafts.setMessageRef(
                    ComposerDrafts.key(stashKeyBridge),
                    ComposerDrafts.MessageRef(it),
                )
            }
        }
    }
    // ----------------------------------------------------- W5-E event revisions
    // The five sticky revision flows `ChatViewModel` bumps on the SSE signals
    // (`research/wave5_events.md` §5). This screen is the one that owns the
    // stream, but the events also arrive while it is *visible*: `project.updated`
    // is Home's business, `session.created`/`session.deleted` the drawer's,
    // `vcs.branch.updated` the branch chip's and `file.edited` the review panel's.
    val sessionsRevision by chatViewModel.sessionsRevision.collectAsState()
    // The tracked session was deleted server-side (another client, expiry):
    // nothing remains to show. This used to call `onBack()` from an effect, so a
    // transient 404 popped the user back to Home with no explanation and no
    // session id to report — upstream renders `SessionErrorFallback`'s not-found
    // page instead (`session/route-error.tsx:31-61`: title + description + server
    // name + the id + a close-tab button). The body below swaps to
    // `SessionNotFoundPanel` while this stays latched.
    val sessionMissing by chatViewModel.sessionMissing.collectAsState()
    // v82 BUG-A: *why* it latched — a 404 (a ghost entry) and a 5xx (the server
    // refusing this session, the missing-project-directory shape) reach the same
    // page with the same exits but different copy.
    val sessionMissingReason by chatViewModel.sessionMissingReason.collectAsState()
    // B-7: a probed server that answered with a non-2.x version. Non-null swaps
    // the whole route for `IncompatibleServerPanel`, like upstream's
    // `activeServer.health?.incompatible` fallback (`session/route-error.tsx:26-28`).
    val incompatibleServerVersion by chatViewModel.incompatibleServerVersion.collectAsState()
    val vcsRevision by chatViewModel.vcsRevision.collectAsState()
    val filesRevision by chatViewModel.filesRevision.collectAsState()

    // A session **row write** → the drawer's list, and its order. The three
    // triggers: the drawer opening (its content is composed while closed on the
    // desktop form, so a closed drawer must not fetch), a `session.created` /
    // `session.deleted` frame, and `sessionsRevision`'s row-write half
    // (`session.model.selected` / `agent.selected` / `permissions` /
    // `revert.*` — the events the server bumps `time.updated` for,
    // `ChatViewModel.sessionsRevision`). This used to refresh only on
    // `drawerState.isOpen`, so a session created in another window (or deleted
    // on the phone while the drawer was open) left the list stale until it was
    // closed and reopened; the row-write half is the A-3 fix — a model/agent
    // switch in the active session used to leave the drawer's order on the
    // pre-action snapshot until the screen was re-entered.
    LaunchedEffect(drawerState.isOpen, drawerOpen, sessionsRevision) {
        if (drawerState.isOpen || drawerOpen) chatViewModel.listSessions { sessions = it }
    }
    // A-3: the clock the drawer's relative labels are measured against. A row is
    // composed once and then skipped (its only input is `updatedAt`, which does
    // not move as time passes), so a label composed while a session was "just
    // now" stayed "just now" for as long as the row lived — the v27 report's
    // 「另一个旧会话标着 just now 在顶部」. The tick runs only while the drawer is
    // on screen: a closed drawer has nothing to show, and the desktop form keeps
    // its content composed even then.
    val drawerVisible = drawerState.isOpen || drawerOpen
    var drawerNow by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(drawerVisible) {
        while (drawerVisible) {
            drawerNow = System.currentTimeMillis()
            delay(DRAWER_CLOCK_TICK_MS)
        }
    }
    // The review data pull is needed by the desktop sidebar, by the mobile
    // Changes / Files tabs (upstream mounts the same review model in both) and by
    // the A1 summary (`state.diff` supplies its changes row, `reviewVcsInfo` its
    // branch row). `vcsRevision` (`vcs.branch.updated`, e.g. an external
    // `git checkout`) re-runs the whole pull, which is why `reviewBranch` is
    // reset here rather than only on entry.
    //
    // v49 (`research/v49_verify/tD/v49_63_changes3.png`, `v49_68`): the pull
    // also keys on the directory the session is pointed at — re-pointing it left
    // the *previous* repository's `main` chip on the Changes page of a directory
    // that has no repository at all. Collected here (rather than further down)
    // only because this key needs it above itself.
    val currentDirectory by chatViewModel.currentDirectory.collectAsState()
    val mobileReview = !desktop &&
        (mobileTab == MobileForm.Tab.CHANGES || mobileTab == MobileForm.Tab.FILES)
    LaunchedEffect(activeSession.id, reviewOpen, mobileReview, summaryOpen, vcsRevision, currentDirectory) {
        if (!reviewOpen && !mobileReview && !summaryOpen) return@LaunchedEffect
        reviewBranch = null
        chatViewModel.refreshDiff()
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
    // Which session the live title above belongs to. `observeSession` runs from
    // the entry effect, i.e. *after* this composition, so on the frame a tab
    // switch lands on the flow still speaks for the session the user left —
    // which is how the header came to read 「新建会话」 (the draft page's cleared
    // title) or the outgoing session's name until a read came back (v68 C1).
    val trackedSessionId by chatViewModel.trackedSessionID.collectAsState()
    // `currentDirectory` is collected above, with the review pull that keys on
    // it (see the v49 note there).
    val modelLabel by chatViewModel.modelLabel.collectAsState()
    val availableProviders by chatViewModel.availableProviders.collectAsState()
    val selectedModel by chatViewModel.selectedModel.collectAsState()
    val modelOverrideActive by chatViewModel.modelOverrideActive.collectAsState()
    val configMissing by chatViewModel.configMissing.collectAsState()
    val pendingPermission by chatViewModel.pendingPermission.collectAsState()
    val permissionResponding by chatViewModel.permissionResponding.collectAsState()
    val formResponding by chatViewModel.formResponding.collectAsState()
    // Wave 2.4a: the v2 form dock. v2 servers produce forms — the only ask
    // carrier on v2 (v1 question dock is gone).
    val pendingForm by chatViewModel.pendingForm.collectAsState()
    // The websearch consent dock's provider catalog
    // (`GET /api/websearch/provider` → `{value: id, label: name}` upstream
    // `session/requests/model.ts:50-53`): fetched once a form dock mounts and
    // re-fetched from the dock's retry row. Empty + `loadFailed` keep upstream's
    // `session.websearch.empty` / retry states instead of a dead picker.
    val websearchProviders by chatViewModel.websearchProviders.collectAsState()
    val websearchProvidersLoading by chatViewModel.websearchProvidersLoading.collectAsState()
    val websearchProvidersLoadFailed by chatViewModel.websearchProvidersLoadFailed.collectAsState()
    LaunchedEffect(pendingForm?.id) {
        if (pendingForm != null) chatViewModel.refreshWebsearchProviders()
    }
    val reverting by chatViewModel.reverting.collectAsState()
    // item8 (W4-B): the real context window from the provider catalog
    // (`GET /provider` v1; v2 folds `GET /api/provider` + `GET /api/model`), and
    // the session cost for the ring's tooltip panel.
    // v20: the window is the model's **effective** one — `min(limit.input,
    // limit.context)` off that catalog entry (`ContextUsage.windowOf`). The
    // context tab's `Context Limit` / `Usage` rows resolve the same entry off
    // their own (active) message rows, so the two need no shared side table.
    val contextLimit by chatViewModel.contextLimit.collectAsState()
    val sessionCost by chatViewModel.sessionCost.collectAsState()
    // W5-C: catalogs + per-session selections the composer's agent chip, slash
    // menu and `@` categories read (`GET /agent`, `/command`, `/skill`).
    val availableAgents by chatViewModel.availableAgents.collectAsState()
    val availableCommands by chatViewModel.availableCommands.collectAsState()
    val availableSkills by chatViewModel.availableSkills.collectAsState()
    // F-1: the two `@` sources that had no UI caller — `GET /api/reference`
    // (hidden rows included; the menu filters) and `GET /api/mcp/resource`'s
    // `resources[]`. Both are location-scoped reads the view model refreshes
    // with the other catalogs, and both reach the menu through
    // `Composer(references = …, resources = …)`.
    val availableReferences by chatViewModel.availableReferences.collectAsState()
    val availableResources by chatViewModel.availableResources.collectAsState()
    // A-2: the drawer row's unread dot rides the same three ledgers the tab
    // strips read — the location-wide pending asks
    // (`ChatViewModel.sessionAsks`), the in-app feed
    // (`SessionTabIndicator.unseenCount`) and the server row's own watermark
    // (`ChatViewModel.sessionUnread`). All three are assembled into one
    // `SessionDotSources` below, so the drawer cannot answer differently from the
    // strip.
    val notifications by chatViewModel.notifications.collectAsState()
    val sessionUnread by chatViewModel.sessionUnread.collectAsState()
    val sessionAsks by chatViewModel.sessionAsks.collectAsState()
    // The drawer row's dot reads the in-app feed too, and the feed's permission
    // entries are gated by the auto-approve setting exactly as the tab strip's
    // are (`SessionTabIndicator.unseenCount`; upstream `hasPermissions`).
    val autoApprovePermissions by chatViewModel.autoApprovePermissions.collectAsState()
    // A-2: one assembly of the dot's inputs for this screen's drawer rows — the
    // same `SessionDotSources` the tab strip (`ui/App.kt`) builds, so the two
    // surfaces answer with one derivation instead of two transcriptions of it.
    val dotSources = SessionDotSources(
        trackedSessionID = chatViewModel.sessionID,
        trackedPermission = pendingPermission != null,
        trackedForm = pendingForm != null,
        asks = sessionAsks,
        autoApprove = autoApprovePermissions,
        notifications = notifications,
        unread = sessionUnread,
        trackedRunning = state.runStatus == ChatStore.RunStatus.RUNNING,
    )
    val selectedAgent by chatViewModel.selectedAgent.collectAsState()
    val selectedVariant by chatViewModel.selectedVariant.collectAsState()
    // B-9: upstream `agentsVisible` — the agent chip is composed only while the
    // General page's "Show agent" switch is on, or the server has a custom
    // agent (`providers/models/selection.tsx:94`).
    val agentPickerVisible by chatViewModel.agentPickerVisible.collectAsState()
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
    val title = sessionHeaderTitle(
        pendingTitle = pendingTitle,
        liveTitle = currentTitle,
        trackedSessionId = trackedSessionId,
        onScreenSessionId = activeSession.id,
        learnedTitle = chatViewModel.sessionTitle(activeSession.id),
        navTitle = activeSession.title,
        newSessionLabel = stringResource(R.string.oc_new_session),
    )
    // Total tokens the ring reports (and its tooltip shows). The active-context
    // read (`GET /api/session/{id}/context` — messages after the last
    // compaction, full usage buckets) wins; the live transcript total covers
    // the gap until that read lands.
    val activeContextTokens by chatViewModel.activeContextTokens.collectAsState()
    val contextTokens = activeContextTokens ?: ContextUsage.lastTokenTotal(state.messages)

    // v77 P0: the three measured boxes are kept in **px**, and the dp the pane's
    // arithmetic reads is derived from them with this composition's density (see
    // `density` below). They used to be `mutableStateOf(0.dp)` written by
    // `with(density) { it.height.toDp() }`, which attaches the density that was
    // current when the *callback was built* to a value that only changes when the
    // node's **pixel** size does: a density change (a `wm density` override, a
    // display-size setting) that keeps the window's px the same left a dp from
    // the previous configuration in the pane's budget, and every derived form —
    // `composerSharesViewport`, `composerCeiling`, `composerCompact`, the
    // timeline's paddings and (from v76) the `SessionPaneArrangement` bounds —
    // was computed against it. Keeping the measurement and converting it at read
    // time is what makes every one of those inputs follow the Configuration.
    var composerHeightPx by remember { mutableIntStateOf(0) }
    // v52 P0: the timeline pane's own height (see `timelineContentBottom`).
    var viewportHeightPx by remember { mutableIntStateOf(0) }
    // v55 P0: the pane's height as the **column** sees it — the outer box of
    // `timelinePane`, which is the whole of the shared arrangement whether the
    // composer floats inside it (upstream's desktop form) or is laid out below
    // it. `viewportHeight` above is the `LazyColumn`'s box, which is the same
    // box in the floating form and a row shorter in the shared one; only the
    // pane height may decide which form, or the two would feed each other.
    var paneHeightPx by remember { mutableIntStateOf(0) }
    // v57 P0: the composer's **form** must not be read off the measured *pane*:
    // the pane is `body − chrome` and the chrome is derived from the composer, so
    // a form decided from it argues with itself (the v57 build measured the
    // composer alternating between 56 and 116 dp several times a second).
    //
    // v74 P0: the *display* height alone is not the answer either. On
    // emulator-5554 at 720x1280 @420dpi — the window the v58 acceptance
    // measured — the display is 487 dp (above the 480 dp threshold) while the
    // session body with the keyboard up is 162.7 dp, 13 dp short of the 164 dp
    // the stacked composer, the chrome band and a message row need. The composer
    // kept its stacked form on that pane, the pane picked the shared
    // arrangement, and the timeline was left `pane − composer` ≈ 61 dp: the
    // reported「消息列表塌成细条」with the empty state cut by the composer's
    // edge. The seed is the window reading (a genuinely short window is
    // unchanged, `ShellChromeBudget.isShortWindow`); `bodyContent` refines it
    // from the post-inset body, which exists before any composer does and so
    // cannot feed back into it.
    val windowHeightDp = LocalConfiguration.current.screenHeightDp
    // v77 P0: the seed is a reading of the *configuration*, so it is re-seeded
    // when the configuration changes the window (`wm size` override and reset,
    // a rotation, the density) instead of being pinned by the first composition.
    // It is still only the seed: the very same composition refines it from the
    // post-inset body below, so the reset costs no frame — the write is a
    // slot-state write that has already landed by the time `composerRegion` reads
    // it.
    var minimalComposer by remember(windowHeightDp) {
        mutableStateOf(ShellChromeBudget.isShortWindow(windowHeightDp))
    }
    val density = LocalDensity.current
    // v77 P0: the dp form of the three px measurements above — converted here,
    // with *this* composition's density, so every consumer below (and v76's
    // `SessionPaneArrangement`) reads a value derived from the current
    // Configuration rather than one frozen at the callback's creation.
    val composerHeight: Dp = with(density) { composerHeightPx.toDp() }
    val viewportHeight: Dp = with(density) { viewportHeightPx.toDp() }
    val paneHeight: Dp = with(density) { paneHeightPx.toDp() }

    // Review data callbacks, shared by the desktop sidebar and the mobile tabs.
    val reviewRootPath = currentDirectory ?: activeSession.directory
    // Hoisted above the review block: the 「刷新」 action toasts from there.
    val context = LocalContext.current
    // 「刷新」: bumped once the evict settled, so the panel re-reads its
    // self-serve probes and re-requests the active scope's diff.
    var reviewReprobeKey by rememberSaveable(activeSession.id) { mutableStateOf(0) }
    // v49 (`research/v49_verify/tD/v49_63_changes3.png`, `v49_68`): the review
    // pull above is keyed on the *session*, so re-pointing the session at
    // another directory (`currentDirectory`) left the previous repository's
    // `main` chip on the Changes page of a directory that has no repository at
    // all. The branch is a fact about the root its reads are made for, so a
    // root change drops it and re-asks everything through [reviewReprobeKey] —
    // the one key the project-verdict probe and the mobile scope read already
    // watch, so no read is left holding the old directory's answer.
    var reviewRootSeen by remember(activeSession.id) { mutableStateOf(reviewRootPath) }
    LaunchedEffect(reviewRootPath) {
        if (reviewRootSeen == reviewRootPath) return@LaunchedEffect
        reviewRootSeen = reviewRootPath
        reviewBranch = null
        reviewDefaultBranch = null
        reviewReprobeKey++
    }
    var reviewRefreshPending by remember(activeSession.id) { mutableStateOf(false) }
    // The context tab's own 「刷新」 half (`SessionContextLoad.Key.reload`): the
    // same tap bumps it, so the B2 payload effect below re-reads its frame and
    // the ring's live rows are re-fetched. Before v17-23 the tap only evicted the
    // review location and re-probed the diff, so on the Context tab the button
    // visibly did nothing. Keyed on the session only — the key's own
    // `serverRevision` half already re-reads on a re-pointed server.
    var contextReloadKey by remember(activeSession.id) { mutableStateOf(0) }
    // The no-git CTA's own busy flag: the button shows 「正在创建 Git 仓库…」
    // while `git init` runs (`gitPending` on both review surfaces).
    var gitInitPending by remember(activeSession.id) { mutableStateOf(false) }
    // `project.vcs` of the review root (`GET /api/project`, upstream
    // `project().vcs`, `review/view.tsx:252`) — the mobile verdict, replacing
    // the old `reviewBranch != null` heuristic, which claimed "a repository"
    // only once a branch had answered (a stale location graph answers neither).
    // Read on the narrow form only: the desktop panel runs the same probe for
    // its own `noGit` gate, and a second GET per session open buys nothing.
    val reviewProjectVcs = if (desktop) null else rememberReviewProjectVcs(reviewRootPath, reviewReprobeKey)
    // The evict-and-re-read tail shared by 「刷新」 and the no-git CTA: the evict
    // disposed the directory's cached services, so these two reads are what
    // prove the new graph sees the repository.
    fun reloadAfterEvict(ok: Boolean, doneText: Int, failedText: Int) {
        reviewRefreshPending = false
        reviewReprobeKey++
        Toast.makeText(
            context,
            context.getString(if (ok) doneText else failedText),
            Toast.LENGTH_LONG,
        ).show()
        chatViewModel.refreshDiff()
        chatViewModel.reviewVcsInfo { info ->
            reviewBranch = info?.branch
            reviewDefaultBranch = info?.defaultBranch
        }
        // …and the context tab is a re-read too, not only the review reads: the
        // frame effect below keys on `contextReloadKey`, and the ring's live rows
        // are re-fetched. Both are silent on failure (the frame keeps its
        // error hint, the rows keep the last good read) — one toast per attempt
        // is the whole report, exactly as for the diff.
        contextReloadKey++
        chatViewModel.refreshActiveContext()
    }
    // Single evict entry point for both forms (desktop panel header, mobile
    // Changes header): evict the root's cached location, report it, then let
    // every review read run again. Never called from an effect — the evict
    // cancels that directory's in-flight permissions/forms.
    val reprobeReviewVcs: () -> Unit = {
        if (!reviewRefreshPending) {
            reviewRefreshPending = true
            chatViewModel.reviewReprobeLocation(reviewRootPath) { ok ->
                reloadAfterEvict(
                    ok,
                    R.string.oc_review_refresh_done,
                    R.string.oc_review_refresh_failed,
                )
            }
        }
    }
    // 「创建 Git 仓库」 (the no-git empty state): run `git init` in the review
    // root through `POST /api/shell` (`ReviewGitInit` — upstream wires no such
    // action and the v2 server has no init route), then evict the location the
    // way 「刷新」 does, because a repository created after the location graph
    // was built stays invisible to `GET /api/vcs*` until that evict
    // (`ReviewVcsReprobe`).
    val reviewGitInit = rememberReviewGitInit()
    val initReviewGit: () -> Unit = {
        if (!gitInitPending) {
            gitInitPending = true
            reviewGitInit(reviewRootPath) { ok ->
                gitInitPending = false
                when {
                    !ok -> Toast.makeText(
                        context,
                        context.getString(R.string.oc_review_no_git_failed),
                        Toast.LENGTH_LONG,
                    ).show()

                    // A refresh is already evicting this directory: its own
                    // re-read is in flight, so only the reads need a nudge.
                    reviewRefreshPending -> reviewReprobeKey++

                    else -> {
                        reviewRefreshPending = true
                        chatViewModel.reviewReprobeLocation(reviewRootPath) { evicted ->
                            // A failed evict still created the repository — only
                            // the panel's re-read failed, so the copy says so.
                            reloadAfterEvict(
                                evicted,
                                R.string.oc_review_no_git_done,
                                R.string.oc_review_refresh_failed,
                            )
                        }
                    }
                }
            }
        }
    }
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
    // Background-work rows (`session/requests/background.ts:85-115`), derived
    // in `ChatViewModel` from the shell/subagent tool parts + child sessions.
    // Empty list renders upstream's empty copy.
    val summaryBackgroundTasks by chatViewModel.backgroundTasks.collectAsState()
    // The panel is the only consumer: re-derive at the moment it opens (plus
    // the VM's own idle / `shell.*` / session-open triggers).
    LaunchedEffect(summaryOpen) {
        if (summaryOpen) chatViewModel.refreshBackgroundTasks()
    }

    // ------------------------------------------------------------ A8 export
    val exportSaver = rememberSessionExportSaver()
    // Usage-exceeded upsell adoption (upstream `usage-exceeded-dialogs.tsx`):
    // the screen owns no upsell state — the VM raises it and clears it — but
    // the 24 h / "don't show again" gate ([UsageUpsellGate]) is evaluated
    // once, at adoption time. A mounted instance stays mounted across
    // recompositions (the dialog's own `markShown` write must not unmount it
    // mid-read); a newer frame while one is showing is ignored, like
    // upstream's `dialog.active → return`. Dismiss/idle nulls the VM state
    // and releases the adoption.
    val upsellPrefs = remember(context) { UsageUpsellGate.prefsOf(context) }
    var upsellVisible by remember(activeSession.id) { mutableStateOf<ChatStore.UsageUpsell?>(null) }
    // Server switch / credential edit ⇒ the one-shot payloads below are stale;
    // the remember keys reset so the next visible mount re-fetches.
    val serverRevision by (serverStore?.revision ?: NO_SERVER_REVISION).collectAsState()

    // SSH tunnel fallback (upstream `connection-panel.tsx`): while the active
    // server's tunnel is not ready the route shows `SshTunnelPanel`
    // (reconnect/disconnect included) instead of the session body. `localUrl`
    // is cleared on disconnect/fail, so the last forwarded url per config is
    // remembered for the link.
    val sshHost = LocalSshTunnelHost.current
    val sshTunnels by (sshHost?.tunnels ?: NO_SSH_TUNNELS).collectAsState()
    val sshLastUrls = remember { mutableMapOf<String, String>() }
    LaunchedEffect(sshTunnels) {
        sshTunnels.forEach { tunnel ->
            tunnel.status.localUrl?.let { sshLastUrls[tunnel.config.id] = it }
        }
    }
    val sshFallback = remember(sshTunnels, serverRevision) {
        if (sshHost == null) null
        else sshPanelTunnel(sshTunnels, serverStore?.server?.url, sshLastUrls.toMap())
    }

    // `⋮ → 导出会话` (`session/commands/export.ts` + `controller.tsx:169-190`):
    // fetch the two raw payloads through `ChatViewModel.fetchExportFrameResult`
    // — the generation-dispatched transport read (v1 `GET /session/:id[/message]`,
    // v2 `GET /api/session/{id}[/message]`; on v2 the unprefixed routes answer
    // SPA HTML) — serialize with the pure `SessionExport`, then hand the string
    // to the SAF saver with the Download/ bypass.
    //
    // Unlike upstream, **no exit is silent**, and that is structural rather than
    // a checklist (v9: the mask hung 12–75 s and then closed with no message):
    //
    // - [reportExport] is the **only** writer of `exportWorking`, so the overlay
    //   cannot come down without exactly one message, whatever tried to close it;
    // - [ExportAttemptLedger] drops a second, later outcome of the same attempt
    //   (Cancel tap vs. a payload answer that lands afterwards), so the funnel
    //   can never double-toast either;
    // - the payload read is bounded by `withExportDeadline` — the old code
    //   awaited it forever, so a wedged transport held the mask until the socket
    //   gave up and the user learn nothing from the wait;
    // - a scope cancellation (screen left, host cancelled it) reports instead of
    //   abandoning a busy flag, and a launch into an already-dead scope reports
    //   instead of dispatching an attempt nobody will ever hear about;
    // - the SAF saver keeps its own unconditional contract (`ExportFinish` →
    //   `ExportFeedback`, picker watchdog included), reached from the same
    //   funnel.
    var exportWorking by remember { mutableStateOf(false) }
    // Which stage the overlay is waiting on (`ExportWorkingOverlay`'s hint line):
    // without it a slow read and a hang look identical for as long as the wait.
    var exportPhase by remember { mutableStateOf(ExportPhase.FETCHING) }
    val exportLedger = remember { ExportAttemptLedger() }
    /**
     * The one terminal report of the current attempt: releases the overlay and
     * says how it ended. [reason] rides the failure copy only — the transport
     * message, or the read deadline in the user's own words.
     */
    fun reportExport(feedback: ExportFeedback, filename: String, reason: String? = null) {
        // Single-shot: this guard is what makes "one attempt, one message" true
        // no matter how many exits race to report (and it is why the late exits
        // cannot re-enable the toast path behind the user's back).
        if (!exportLedger.settle(feedback)) return
        exportWorking = false
        val text = when (feedback) {
            ExportFeedback.SAVED -> context.getString(R.string.oc_export_success, filename)
            // The picker was unusable (no handler / threw / timed out) or its
            // write failed, so the file is in Download/: say so, otherwise the
            // user hunts for a save dialog that never appeared.
            ExportFeedback.SAVED_TO_DOWNLOADS ->
                context.getString(R.string.oc_export_saved_downloads, filename)
            ExportFeedback.CANCELLED -> context.getString(R.string.oc_export_cancelled)
            // `success(null)` = no transport; a `failure` carries the transport
            // exception, so the toast names the real cause (`oc_export_failed`
            // stays for the reason-less case).
            ExportFeedback.FAILED -> reason?.takeIf { it.isNotBlank() }
                ?.let { context.getString(R.string.oc_export_fetch_failed, it) }
                ?: context.getString(R.string.oc_export_failed)
        }
        // Every outcome gets the long toast — the success save included. The
        // short one was measured too brief to read on a phone (v10 logcat:
        // `Toast already killed` as the next toast replaced it), and the
        // filename it carries is exactly what the user has to catch.
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    }
    fun exportSession() {
        if (exportWorking) {
            Toast.makeText(context, R.string.oc_export_in_progress, Toast.LENGTH_SHORT).show()
            return
        }
        exportLedger.begin()
        exportWorking = true
        exportPhase = ExportPhase.FETCHING
        // A scope that is already cancelled turns `launch` into a silent no-op:
        // the tap would raise the overlay, dispatch nothing and never report.
        // Checked on the scope's own job (not on the returned one, which may
        // have run and finished a frame later).
        val scopeJob = scope.coroutineContext[Job]
        if (scopeJob != null && !scopeJob.isActive) {
            reportExport(ExportFeedback.FAILED, SessionExport.filename(activeSession.id, title))
            return
        }
        scope.launch {
            val filename = SessionExport.filename(activeSession.id, title)
            val fetched = try {
                withExportDeadline(scope) {
                    chatViewModel.fetchExportFrameResult(activeSession.id, reviewRootPath)
                }
            } catch (_cancelled: CancellationException) {
                // The attempt died with its scope (the screen or the host
                // cancelled it). Report it like any other exit: a busy overlay
                // left behind with nothing said is the bug this section exists
                // to kill.
                reportExport(ExportFeedback.CANCELLED, filename)
                return@launch
            }
            // A Cancel tap reports for the fetch it dropped; the ledger has
            // already settled, so this late answer never reaches the saver —
            // and never becomes a second toast either.
            if (exportLedger.settled) return@launch
            val result = when (fetched) {
                ExportStageResult.TimedOut -> {
                    reportExport(
                        ExportFeedback.FAILED,
                        filename,
                        context.getString(
                            R.string.oc_export_fetch_timeout,
                            (EXPORT_FETCH_TIMEOUT_MS / 1000L).toInt(),
                        ),
                    )
                    return@launch
                }
                is ExportStageResult.Failed -> {
                    reportExport(ExportFeedback.FAILED, filename, fetched.cause.message)
                    return@launch
                }
                is ExportStageResult.Ok -> fetched.value
            }
            val frame = result.getOrNull()
            val json = frame?.let { SessionExport.serialize(it) }
            if (frame == null || json == null) {
                reportExport(ExportFeedback.FAILED, filename, result.exceptionOrNull()?.message)
                return@launch
            }
            // The fetched frame's `info` is the server-fresh session object:
            // prefer its title over the screen's, so a rename that landed
            // after this screen's title did (a Home rename of a session this
            // screen opened under its creation-time name) still pre-fills the
            // new name. The pre-fetch `filename` above stays the failure-path
            // answer — there is no frame to read one from there.
            val liveFilename =
                SessionExport.frameTitle(frame)?.let { SessionExport.filename(activeSession.id, it) } ?: filename
            exportPhase = ExportPhase.SAVING
            exportSaver.save(liveFilename, json, scope) { finish ->
                reportExport(
                    ExportFeedback.of(finish),
                    (finish as? ExportFinish.Saved)?.filename ?: liveFilename,
                )
            }
        }
    }
    fun cancelExport() {
        // A pending picker releases itself through the saver's own
        // `ExportFinish.Cancelled` report; when nothing is pending (the payload
        // read is still out) this tap is the report. Both go through
        // `reportExport`, whose ledger drops whichever one arrives second —
        // exactly one message per attempt either way.
        exportSaver.cancelPending()
        reportExport(ExportFeedback.CANCELLED, SessionExport.filename(activeSession.id, title))
    }

    // --------------------------------------------- v39 ⋮ 「导入会话…」 row
    // Android's second import entry, the row beside 「导出会话」 in the header ⋮
    // (`SessionHeader.SessionOverflowMenu`'s KDoc records why the shell adds an
    // entry upstream does not have). The picker, the SAF read, the
    // `{info, messages}` parse and `POST /api/experimental/session/import` are
    // the same self-serve importer the Context tab and the Home project row use;
    // this block adds the target directory and the two outcomes that need the
    // screen — the toast, and the navigation to the session the import created.
    //
    // `locationDirectory` is this session's own directory
    // (`activeSession.directory`), the Android counterpart of upstream's
    // `location: {directory: project.worktree}` (`controller.tsx:120-126`);
    // `rememberSessionImporter` bakes it in at construction, so keying on it is
    // what stops a switched session from importing into the previous one. Null
    // (before the directory backfills) lets the server pick its default.
    val sessionImporter = rememberSessionImporter(activeSession.directory)
    var sessionImportBusy by remember { mutableStateOf(false) }
    val importSession: () -> Unit = {
        // A second tap while a picker is out is dropped rather than stacked:
        // the importer's own one-at-a-time guard would answer it with a failure
        // for a request the user did make. (The row closes the menu first, so
        // this is the belt to that brace — the rule
        // `rememberHomeProjectImport` already runs.)
        if (!sessionImportBusy) {
            sessionImportBusy = true
            // The scope overload, not the boolean one: it bounds the picker, the
            // read and the route, and answers every one of them, so
            // `sessionImportBusy` can never stay up and leave the row dead.
            sessionImporter.importSession(scope) { finish ->
                sessionImportBusy = false
                val text: String? = when (finish) {
                    is ImportFinish.Imported ->
                        context.getString(R.string.oc_session_import_success, finish.sessionId)

                    // Upstream stays silent on a dismissed picker; the attempt
                    // is released either way.
                    ImportFinish.Cancelled -> null

                    is ImportFinish.Failed -> importFailureCopy(context, finish)
                }
                // An import's outcome is a report, not a hint — the same
                // lifetime the Context tab's import row uses.
                text?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                // Upstream's flow ends by opening what it imported
                // (`home.project.openProjectSession`, `controller.tsx:129-131`);
                // the host owns that one step.
                (finish as? ImportFinish.Imported)?.let { onSessionImported(it.sessionId) }
            }
        }
    }

    // ------------------------------------------------ B5 screen-local chords
    // The App-level chords call the view model directly; these six ids only
    // exist on this screen's own state, so they arrive through
    // `SessionCommandIntents.commandRequest` (see that channel's KDoc):
    //
    // - `session.export` → `exportSession()` below;
    // - `message.previous` / `message.next` → the timeline scroll
    //   (`navigateMessage`, `timeline/interaction.ts:133-149`);
    // - `input.focus` → the composer's editor (`externalFocus`);
    // - `model.choose` → the model chip's menu (`externalModelOpen`);
    // - `terminal.new` → a new tab in the docked pane (`newTabRequest`).
    //
    // B-18/B-19 add five more: the composer's own three (`file.attach`,
    // `prompt.mode.shell`, `prompt.mode.normal` → the composer's external
    // request counters) and the two browser rows (`browser.open` →
    // `browserUrl`, `browser.reload` → the pane's reload request).
    var inputFocusRequest by remember { mutableStateOf(0) }
    var modelChooseRequest by remember { mutableStateOf(0) }
    var newTerminalRequest by remember { mutableStateOf(0) }
    var attachRequest by remember { mutableStateOf(0) }
    var shellModeRequest by remember { mutableStateOf(0) }
    var normalModeRequest by remember { mutableStateOf(0) }
    val commandRequest by (intents?.commandRequest ?: noCommandRequest).collectAsState()
    // The id this screen was mounted at: a request that landed *before* the
    // route composed (a chord pressed during the transition, say) must not fire
    // on entry — the same guard `summaryToggleRevision` uses.
    var lastCommandRequestId by remember(activeSession.id) {
        mutableStateOf(intents?.commandRequest?.value?.id ?: 0L)
    }
    LaunchedEffect(commandRequest?.id) {
        val request = commandRequest ?: return@LaunchedEffect
        if (request.id <= lastCommandRequestId) return@LaunchedEffect
        lastCommandRequestId = request.id
        when (request.commandId) {
            "session.export" -> exportSession()
            "input.focus" -> inputFocusRequest += 1
            "model.choose" -> modelChooseRequest += 1
            // B-19: the composer-editor commands (`composer/model.ts:411-434`).
            // Their state lives inside `Composer` (the SAF launcher, `shellMode`),
            // so each one rides an external request counter — the same one-way
            // channel `input.focus` / `model.choose` use.
            "file.attach" -> attachRequest += 1
            "prompt.mode.shell" -> shellModeRequest += 1
            "prompt.mode.normal" -> normalModeRequest += 1
            // B-18 `browser.open`: show the pane at the URL it last displayed, or
            // at the pane's empty state when this session has opened none
            // (upstream `tabs.open` with no URL, `session/browser/model.ts:51`).
            // Already open = nothing to do: the pane is one overlay, not a tab
            // stack (`browser.open` upstream re-focuses its existing tab).
            "browser.open" -> if (browserUrl == null) browserUrl = lastBrowserUrl ?: ""
            // B-18 `browser.reload` (`pane.tsx:45-50`): forward to the pane's own
            // reload. A no-op while the pane is closed — upstream's row is
            // disabled then (`disabled: !props.visible || !address()`).
            "browser.reload" -> if (browserUrl != null) browserReloadRequest += 1
            // `terminal.new` (`use-session-commands.tsx:402-409` `openTerminal`):
            // `terminal.open()`, then a new tab (or focus when none exists). Wide
            // screens own the docked pane — open it first (the pane creates its
            // own first tab), or add one when it is already open; the mobile form
            // has the 「终端」tab instead of a dock.
            "terminal.new" -> if (desktop) {
                if (terminalDocked) newTerminalRequest += 1 else onToggleTerminal?.invoke()
            } else {
                usageOpen = false
                summaryOpen = false
                switchMobileTab(MobileForm.Tab.TERMINAL)
            }

            "message.previous", "message.next" -> {
                val target = MessageNav.target(
                    messages = state.messages,
                    rowIndex = timelineRowIndex,
                    firstVisibleIndex = timelineListState.firstVisibleItemIndex,
                    offset = if (request.commandId == "message.previous") -1 else 1,
                )
                when (target) {
                    // `resume()` (interaction.ts:125-131): back to the live end —
                    // the last row the timeline published.
                    MessageNav.Target.Resume -> {
                        val last = timelineRowIndex.values.maxOrNull()
                        if (last != null) scope.launch { timelineListState.animateScrollToItem(last) }
                    }

                    is MessageNav.Target.Scroll -> scope.launch {
                        timelineListState.animateScrollToItem(target.index)
                    }

                    null -> Unit
                }
            }
        }
        intents?.consumeCommand(request)
    }

    // ------------------------------------------------- B2 context tab payload
    // The context tab (desktop sidebar, `session.tab.context`) and the mobile
    // usage view both render upstream's `SessionContextTab`, which needs the
    // **raw** payloads: `ChatViewModel`'s parsed messages flatten `info.tokens` to
    // one total, so the per-bucket rows cannot be derived from them. This reuses
    // the export's generation-aware fetch (`ChatViewModel.fetchExportFrame`,
    // raw `{info, messages}` on both generations) — never per recomposition, and
    // no polling loop: the effect below runs once per `SessionContextLoad.Key`
    // (session / server revision / 「刷新」 / a settled turn), and once more when
    // the panel is re-opened.
    var contextSession by remember(activeSession.id, serverRevision) { mutableStateOf<JSONObject?>(null) }
    var contextMessages by remember(activeSession.id, serverRevision) {
        mutableStateOf<List<JSONObject>>(emptyList())
    }
    // The key the frame above was read for, or `null` when nothing is loaded —
    // including after a failed read, so a failure never latches the tab into the
    // empty projection. Replaced the v17 boolean `contextLoaded` latch, which made
    // the frame a one-shot: after the first open *nothing* re-read it, not even
    // the panel's 「刷新」 tap.
    var contextLoadedKey by remember { mutableStateOf<SessionContextLoad.Key?>(null) }
    // The v2 active context (`GET /api/session/{id}/context` — messages after
    // the last compaction) is read by `ChatViewModel` itself, because the header
    // ring's numerator comes from the very same fetch
    // (`ChatViewModel.activeContextRows` / `activeContextTokens`,
    // `SessionContextModel.lastAssistantTotal` is the rule both apply). The tab
    // therefore subscribes to that live flow instead of holding a second, one-shot
    // copy of the route: the token rows move with the ring instead of freezing at
    // the tab's first open (v17-23: ring 42 %, tab still `9,154` / `1 %`), and
    // the settle effect below re-reads them at every turn boundary so a turn that
    // ends while the panel is open shows its own numbers (v19 device P2). `null`
    // until the first read lands, on v1 where the route does not exist, and after
    // a failed read — the projection then keeps its full-list source.
    val contextActive by chatViewModel.activeContextRows.collectAsState()
    // Backing-fetch failure reason for the context tab: the fetch runs
    // through `ChatViewModel.fetchExportFrameResult`, so a transport failure
    // arrives as `Result.failure` and its message is recorded here instead
    // of the generic `oc_export_failed` copy. `null` while loading or
    // loaded; a failed load records the reason instead.
    var contextExportError by remember(activeSession.id, serverRevision) {
        mutableStateOf<String?>(null)
    }
    // W5-INT: the drain's own truncation report (`SessionExport.Frame.truncated`
    // / `truncatedPages`). The frame is unwrapped into `contextSession` +
    // `contextMessages` above, which dropped the two flags: the context tab's
    // "only the first N pages" hint and the export payload's `truncated` note
    // (`SessionExport.serialize`) therefore never fired on this mount, even
    // though the same read produced them. Held here and projected through
    // `SessionContextModel.project(exportTruncated = …)`.
    var contextTruncated by remember(activeSession.id, serverRevision) { mutableStateOf(false) }
    var contextTruncatedPages by remember(activeSession.id, serverRevision) { mutableStateOf(0) }
    val contextVisible = if (desktop) {
        reviewOpen && reviewTab == ReviewPanelTab.CONTEXT
    } else {
        usageOpen
    }
    // The turn boundary both halves of the panel follow (v19 device P2). The
    // frame effect below only re-reads for a new `SessionContextLoad.Key`, and the
    // live token rows ride `ChatViewModel`'s own snapshot, which is throttled
    // (`SNAPSHOT_THROTTLE_MS`) and drops exactly the fast turn/finish cycle that
    // settles a turn — so with the panel open a new turn's usage kept the previous
    // turn's numbers until the panel was closed and re-opened.
    // `SessionContextLoad.settleCount` owns the rule: one bump per run that ended,
    // never while one streams (a part event per token must not re-drain the frame).
    var contextSettleKey by remember(activeSession.id) { mutableStateOf(0) }
    var contextLastRunStatus by remember(activeSession.id) {
        mutableStateOf<ChatStore.RunStatus?>(null)
    }
    LaunchedEffect(state.runStatus) {
        val previous = contextLastRunStatus
        contextLastRunStatus = state.runStatus
        val next = SessionContextLoad.settleCount(previous, state.runStatus, contextSettleKey)
        if (next == contextSettleKey) return@LaunchedEffect
        contextSettleKey = next
        // The live rows are re-read here rather than left to the ViewModel's own
        // idle snapshot (throttled, see above); the frame half is the effect
        // below, which now sees the bumped key. One O(1) `GET …/context` per
        // settled turn — the ring above reads the same rows, so it is corrected
        // by this read too.
        chatViewModel.refreshActiveContext()
    }
    LaunchedEffect(activeSession.id, serverRevision, contextVisible, contextReloadKey, contextSettleKey) {
        val key = SessionContextLoad.Key(activeSession.id, serverRevision, contextReloadKey, contextSettleKey)
        if (!SessionContextLoad.needsLoad(contextLoadedKey, contextVisible, key)) return@LaunchedEffect
        val result = chatViewModel.fetchExportFrameResult(activeSession.id, reviewRootPath)
        val frame = result.getOrNull()
        if (frame == null) {
            contextExportError = result.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.oc_export_failed)
            return@LaunchedEffect
        }
        contextSession = frame.info
        contextMessages = frame.messages
        contextTruncated = frame.truncated
        contextTruncatedPages = frame.truncatedPages
        contextExportError = null
        contextLoadedKey = key
    }
    // Closing the panel drops the loaded key, so re-opening it reads again: a
    // session that advanced while the tab was closed must not come back as the
    // previous visit's numbers. Nothing is read while the panel is hidden —
    // `needsLoad` refuses for an invisible one.
    LaunchedEffect(contextVisible) {
        if (!contextVisible) contextLoadedKey = null
    }
    // Pure projection (`SessionContextModel`); recomputed only when the payloads,
    // the provider catalog or the two screen-owned scalars change, so the panel
    // renders a plain object. `contextLimit` / `sessionCost` are passed in so the
    // 上限 / 使用率 / 总成本 rows can never disagree with the header's ring and
    // 总成本, which read the same two values.
    val contextSnapshot = remember(
        contextSession,
        contextMessages,
        contextActive,
        contextExportError,
        contextTruncated,
        contextTruncatedPages,
        availableProviders,
        contextLimit,
        sessionCost,
    ) {
        SessionContextModel.project(
            session = contextSession,
            messages = contextMessages,
            providers = availableProviders,
            contextLimit = contextLimit,
            sessionCost = sessionCost,
            activeMessages = contextActive,
            exportError = contextExportError,
            exportTruncated = contextTruncated,
            exportTruncatedPages = contextTruncatedPages,
        )
    }

    // v50 BUG-1 (`research/v50_verify/tE/v50_37_search2.png`, `v50_38_search3.png`):
    // the review toggle is a sibling of the header inside the same band, and the
    // row used to *inset the toggle* by a guess at the trailing cluster's width
    // (ring + ⋮ + summary + search = 160 dp) instead of reserving that width on
    // the header. The cluster is five targets wide — 200 dp — so the toggle sat
    // squarely on the 「搜索会话」entry: a tap on its centre opened the review
    // panel, a tap on its leading sliver fell through to the title and started a
    // rename. Layout, not z-order, was the bug.
    //
    // So the header gives the toggle its own slot at the trailing edge and the
    // toggle takes exactly that slot. One 40 dp target, whatever else the cluster
    // holds and however narrow the icons shrink (`sessionHeaderMetrics` reads the
    // row width it is actually given, so the reservation is already priced in).
    val headerReviewSlot = sessionHeaderReviewSlot(desktop)

    // ------------------------------------------------------------- header row
    val headerSlot: @Composable () -> Unit = {
        Box(modifier = Modifier.fillMaxWidth()) {
            SessionHeader(
                modifier = Modifier.padding(end = headerReviewSlot),
                title = title,
                // The rename field seeds from this session's title only: never
                // the display fallbacks (the nav-time snapshot, the placeholder)
                // and never another session's name.
                renameSeed = sessionRenameSeed(
                    pendingTitle = pendingTitle,
                    liveTitle = currentTitle,
                    trackedSessionId = trackedSessionId,
                    onScreenSessionId = activeSession.id,
                    learnedTitle = chatViewModel.sessionTitle(activeSession.id),
                ),
                onRename = { newTitle ->
                    pendingTitle = newTitle
                    chatViewModel.renameSession(activeSession.id, newTitle) { outcome ->
                        val ok = outcome == SessionRenameOutcome.RENAMED
                        if (!ok) pendingTitle = null
                        // v81 P1: the inline title used to revert in silence.
                        renameFailureToast(context, outcome)
                        // v82 BUG-B: and the revert has to keep saying why
                        // (the toast above is the same sentence, but only for
                        // as long as the platform keeps its window up).
                        if (!ok) renameFailureMessage = renameFailureText(context, outcome)
                    }
                },
                onBack = onBack,
                onOpenDrawer = openDrawer,
                // v31 BUG-2: the tap-away below only arms while the inline title
                // editor is up (the editor itself keeps the draft).
                onTitleEditing = { titleEditing = it },
                titleEditFence = titleEditFence,
                // item8 (W4-B): the ring gets the real denominator
                // (`ChatViewModel.contextLimit` ← the provider/model catalog's
                // `limit.context` / `limit.input`) instead of the M4 placeholder
                // `limit = null`, plus the tooltip figures. Ring drawing /
                // long-press panel live in `ui/session/header/**`.
                contextPercent = ContextUsage.percentOf(contextTokens, contextLimit?.toLong()),
                contextTokens = contextTokens,
                contextCost = sessionCost,
                // Tap on the ring: upstream's `openSessionContext`
                // (`session-context-usage.tsx:30-37`) opens the **Context** view
                // — the review sidebar's `context` tab on desktop, and on mobile
                // the `usage` view, which *is* upstream's `SessionContextTab`
                // (`session/screen.tsx:307-309`; the same surface `⋮ → Usage`
                // opens). It used to land on Changes instead — the review panel's
                // **default** tab — so the ring read as "open the diff"
                // (v9: three taps, three Changes panels). Selecting the tab is
                // also what starts the context payload load
                // (`contextVisible`/`reviewOpen && reviewTab == CONTEXT`), so a
                // tap now always shows real numbers rather than the empty
                // projection.
                //
                // Deliberate deviation: upstream *closes* the tab again when it
                // is already the visible one (`openContext`'s `contextVisible()`
                // branch, `:93-100`). This tap always opens — the ring's other
                // affordance is the long-press tooltip, and a tap that sometimes
                // closes is a tap that reads as doing nothing.
                onOpenContext = {
                    if (desktop) {
                        reviewOpen = true
                        reviewTabName = ReviewPanelTab.CONTEXT.name
                    } else {
                        summaryOpen = false
                        usageOpen = true
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
                        SessionMenuAction.DELETE -> {
                            // W4-E F-4: the session on screen is deleted through
                            // the same card as the drawer rows — the one host
                            // both entry points arm (v70 P2).
                            deleteHost?.ask(activeSession.id, title, activeSession.directory)
                        }
                        SessionMenuAction.EXPORT -> exportSession()
                    }
                },
                // v39: 「导入会话…」, the row beside the export (see
                // `SessionOverflowMenu`'s KDoc for why this entry is Android's
                // own).
                onImportSession = importSession,
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
            // Review panel toggle in the slot the header reserved for it, past
            // the ⋮ menu. Desktop only: upstream renders it under `isDesktop()`
            // and the mobile form uses the view tabs instead
            // (`session/screen.tsx:338-348`).
            if (desktop) {
                ReviewToggleButton(
                    active = reviewOpen,
                    onClick = { reviewOpen = !reviewOpen },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = HEADER_EDGE_PADDING),
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
                        backgroundTasks = summaryBackgroundTasks,
                        onClose = { summaryOpen = false },
                    )
                }
            }
        }
    }

    // P1-C: the composer's `/` menu resolves the session slash aliases
    // (`SessionCommands.SESSION_SLASH_COMMAND_IDS`: `/new`, `/undo` … `/mcp`)
    // against the same table the app command palette lists — the full session
    // rows plus the slash-relevant app rows (`SessionCommands.slashRegistry`,
    // same builders the palette dialog uses). Picking one (or typing it)
    // invokes the row's `onSelect` instead of sending a prompt (upstream
    // `command.trigger(id, "slash")`, `composer/model.ts:339-344`). Declared
    // down here (not next to the catalog reads above) because `exportSession`
    // is a local fun below — same-scope calls must come after its declaration.
    //
    // The VM-only rows run the view model; `/fork` opens the same fork picker
    // the header ⋮ menu opens (`LocalSessionActionsHost`, published by the
    // route — null only in previews, where the row is omitted); `/export`
    // runs this screen's export flow; `/open` and `/terminal` prefer the
    // host's app-layer actions and fall back to the in-screen Files view /
    // Terminal tab (desktop dock-close) so the alias always executes. `/mcp`
    // opens the chooser (the host's dialog, else the inline mount below).
    // Rebuilt per composition: fresh closures, ~30 small objects, and the
    // composer only does id lookups against the list.
    val actionsHost = LocalSessionActionsHost.current
    // `/mcp` prefers the host's app-layer chooser dialog and falls back to an
    // inline mount below (previews / hosts without the dialog layer).
    var mcpOpen by rememberSaveable { mutableStateOf(false) }
    val mcpAction: () -> Unit = onToggleMcp ?: { mcpOpen = true }
    val openFileAction: () -> Unit =
        onOpenFilePicker ?: {
            if (desktop) {
                reviewOpen = true
                reviewTabName = ReviewPanelTab.FILES.name
            } else {
                usageOpen = false
                summaryOpen = false
                switchMobileTab(MobileForm.Tab.FILES)
            }
        }
    val terminalAction: (() -> Unit)? =
        onToggleTerminal ?: if (!desktop) {
            {
                usageOpen = false
                summaryOpen = false
                switchMobileTab(MobileForm.Tab.TERMINAL)
            }
        } else if (terminalDocked) {
            onCloseTerminalDock
        } else {
            null
        }
    val composerPaletteCommands = SessionCommands.slashRegistry(
        app = appCommands(
            // `settings.open` is required by the builder but never slash —
            // it is filtered out below with the other non-slash rows.
            onOpenSettings = {},
            onOpenTerminal = terminalAction,
            onNewSession = { onNewSessionDraft() },
            onOpenFilePicker = { openFileAction() },
        ).filter { row ->
            row.id == "session.new" || row.id == "file.open" ||
                (row.id == "terminal.toggle" && terminalAction != null)
        },
        session = SessionCommands.sessionCommands(
            actions = SessionCommands.SessionCommandActions(
                onUndo = { chatViewModel.undoSession() },
                onRedo = { chatViewModel.redoSession() },
                onCompact = { chatViewModel.compactSession() },
                onBackground = { chatViewModel.backgroundSessionTools() },
                onFork = actionsHost?.let { host ->
                    { host.start(SessionActionsMenuItem.FORK) }
                },
                onExport = { exportSession() },
                // `/mcp` runs the chooser, never the palette stub.
                onToggleMcp = { mcpAction() },
            ),
        ),
    )

    // ------------------------------------------------------------ composer
    // v55 P0: the composer's one call site. `timelinePane` mounts it in one of
    // two exclusive arrangements — floating over the timeline (upstream's
    // desktop form) or as the pane's last row when the opaque overlay would
    // cover the whole message band (`composerSharesViewport`) — and both go
    // through this so the composer is still mounted exactly once per frame,
    // inside `timelinePane` (`SessionTabFocusSendGateTest`).
    // Silent-prompt hard stop: every send path below is inert while the
    // composer is not the visible surface (a hidden editor must never admit
    // a prompt, whatever still holds the keyboard). Desktop always shows the
    // composer — it overlays the timeline — so the gate stays open there.
    val composerSendAllowed = SessionComposerGate.acceptsInput(
        desktop = desktop,
        tab = mobileTab,
        usageOpen = usageOpen,
        summaryOpen = summaryOpen,
    )
    val composerRegion: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(c.bgBase)
                .onSizeChanged { composerHeightPx = it.height }
                // v110 P0: the region's 12 dp of vertical decoration lives **below**
                // the band, never above it. It used to be `top = 4, bottom = 8`, and
                // the 4 dp on top is what pushed the band past the pane's bottom edge
                // on the 1366x600 tier: the host box is exactly `BAND_HEIGHT` (48),
                // so the band is placed at the host's top edge and any top padding
                // is a pure overflow — `hostTop 165 + 4 + 48 = 217 > paneBottom 213`.
                // Moving those same 4 dp to the bottom keeps the region's total
                // padding at [SessionPaneLayout.COMPOSER_REGION_PADDING] (12 dp — every
                // pane-budget term that sums it stays true, one pixel unchanged), keeps
                // the breathing room where it is actually seen (the edge nearest the
                // keyboard), and puts the band flush with the host box's top edge:
                // `unionBottom = hostTop + unionPx <= hostTop + hostPx = paneBottom`.
                // `BAND_HEIGHT`, `TOOL_ROW_HEIGHT` and the 48 dp touch target are
                // untouched — this only moves where the decoration sits.
                .padding(start = 12.dp, end = 12.dp, top = 0.dp, bottom = 12.dp),
        ) {
            if (configMissing) {
                Banner(text = stringResource(R.string.oc_setup_hint), color = c.textMuted)
            }
            // Form dock (v2; upstream `session-composer-region` renders the
            // pending form where v1 renders the question dock). Answers go
            // through `POST …/form/{formID}/reply{answer}`, dismissal via the
            // cancel route (`DELETE …/form/{formID}`).
            pendingForm?.let { form ->
                SessionFormDock(
                    form = form,
                    responding = formResponding == form.id,
                    onSubmit = { answer -> chatViewModel.replyForm(form.id, answer) },
                    onDismiss = { chatViewModel.dismissForm(form.id) },
                    modifier = Modifier.padding(bottom = 8.dp),
                    websearchProviders = websearchProviders,
                    websearchProvidersLoading = websearchProvidersLoading,
                    websearchProvidersLoadFailed = websearchProvidersLoadFailed,
                    onRetryWebsearchProviders = { chatViewModel.refreshWebsearchProviders() },
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
                // v55 P0: a pane too short for the full editor, the tool row,
                // the chrome band and a message row gets the compact editor
                // (`SessionPaneLayout.composerCompact`) — the 20 dp go to the
                // messages, which on that window had none at all.
                compact = SessionPaneLayout.composerCompact(paneHeight),
                // v57 P0: one editor row + one tool row → one row, so the pane's
                // last dp goes to the messages instead of to the composer's
                // editor (`SessionPaneLayout.composerMinimal`).
                minimal = minimalComposer,
                // v99 P0 「队列条把时间线压到 16px 且 100% 覆盖」: the queued bar's
                // ceiling is now a **pane budget**, not the upstream 148 dp
                // constant. The bar is the first child of the composer's strip
                // Column, so it used to be measured at its full intrinsic height
                // and the pane — which charges the whole host to the timeline
                // (`SessionPaneLayout.viewportHeightPx`, below) — paid for it out
                // of the message band: 188 px → 16 px on emulator-5562 at
                // 1366x768, 100 % of the remainder under the bar's opaque
                // rectangle (`research/v97_verify/5562_v98/REPORT_SWEEPD3.md` §2).
                //
                // `queueBarCeiling` pays the timeline's floor and the composer's
                // tool row FIRST and gives the bar the remainder, so the bar is
                // now the elastic band: past the ceiling it scrolls (it is a
                // `LazyColumn`) instead of taking the messages' dp. The pane is
                // read here, not the host — same reason as `sharedComposer` above,
                // so the bar cannot argue with the decision it trips.
                //
                // The composer floor is the form this composer is about to take,
                // and the *stacked* floor whenever it is not the one-row form: the
                // bar must never be budgeted against a cheaper card than the one
                // it shares the pane with, or the difference is exactly the dp the
                // messages would have lost.
                queueBarMaxHeight = SessionPaneLayout.queueBarCeiling(
                    paneHeight,
                    composerFloor = if (minimalComposer) {
                        SessionPaneLayout.COMPOSER_MINIMAL_HEIGHT
                    } else {
                        SessionPaneLayout.COMPOSER_MIN_HEIGHT
                    },
                ),
                runStatus = state.runStatus,
                modelLabel = modelLabel,
                attachments = attachments,
                providers = availableProviders,
                selectedModel = selectedModel,
                modelOverrideActive = modelOverrideActive,
                onAttachClick = attachmentPicker::pick,
                onRemoveAttachment = { attachment -> stageAttachments(attachments - attachment) },
                onSelectModel = { providerID, modelID ->
                    chatViewModel.selectModel(providerID, modelID)
                },
                onUseServerDefaultModel = { chatViewModel.clearModelOverride() },
                onSearchFiles = MentionSearchScope.scoped(reviewRootPath) { query, directory, onResult ->
                    chatViewModel.searchFiles(query, directory, onResult = onResult)
                },
                onSend = { text, files ->
                    if (composerSendAllowed) {
                        chatViewModel.send(text, files)
                        stageAttachments(emptyList())
                    }
                },
                onStop = { chatViewModel.abort() },
                // W5-C wiring (report §4): drafts/history are keyed by session;
                // the agent chip, slash menu and `@` categories read the catalogs
                // above; `onSubmit` routes slash commands and shell mode.
                sessionID = activeSession.id,
                agents = availableAgents,
                selectedAgent = selectedAgent,
                agentOverrideActive = selectedAgent != null,
                agentPickerVisible = agentPickerVisible,
                onSelectAgent = { chatViewModel.selectAgent(it) },
                onUseDefaultAgent = { chatViewModel.selectAgent(null) },
                commands = availableCommands,
                // F-1: the `/` menu's session-alias half (see
                // `composerPaletteCommands` above).
                paletteCommands = composerPaletteCommands,
                skills = availableSkills,
                // F-1: the `@` menu's reference + MCP-resource categories
                // (`GET /api/reference`, `GET /api/mcp/resource`). Upstream
                // merges them into `context` beside skills/agents
                // (`composer/model.ts:195-…`); the menu does that merging, this
                // is only the data.
                references = availableReferences,
                resources = availableResources,
                selectedVariant = selectedVariant,
                onSelectVariant = { chatViewModel.selectVariant(it) },
                onSubmit = { submission ->
                    if (composerSendAllowed) {
                        chatViewModel.submit(
                            submission.text,
                            submission.attachments,
                            submission.shell,
                            submission.command,
                            submission.arguments,
                        )
                    }
                },
                // WAVE 2.4b: the structured submit (text + mention files/agents/
                // skills + delivery); the legacy `onSubmit`/`onSend` above stay
                // as the fallback chain. Attachments clear as today.
                followUpBehavior = followUpBehavior,
                generation = generation.name,
                queuedRows = inboxRows,
                queueBusy = inboxMutationInFlight,
                onQueueSteer = { id -> if (composerSendAllowed) chatViewModel.steerQueued(id) },
                onQueueRemove = { id -> chatViewModel.cancelQueued(id) },
                onQueueConfirm = { id, replacement ->
                    if (composerSendAllowed) chatViewModel.confirmQueueEdit(id, replacement)
                },
                onStructuredSubmit = { m ->
                    if (composerSendAllowed) {
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
                        stageAttachments(emptyList())
                    }
                },
                // Same rule as the SAF picker: merge onto the chips already
                // staged and cap at the limit — a raw replace silently dropped
                // them (and bypassed the cap) on paste.
                onPasteAttachments = { pasted ->
                    val merged = AttachmentPolicy.merge(attachments, pasted)
                    if (merged.dropped > 0) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.chat_attach_limit, AttachmentPolicy.MAX_ATTACHMENTS),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    stageAttachments(merged.attachments)
                },
                // U-3 draft handoff: a failed send hands the full handoff back.
                // Attachments live in screen state, so they re-enter here
                // (merged under the same cap as pastes, silently — a restore
                // is a system event, not a user paste); the message ref lands
                // in `restoredMessageRef` and scrolls the timeline back to it
                // when the rows know its position.
                onRestoreAttachments = { restored ->
                    stageAttachments(AttachmentPolicy.merge(attachments, restored).attachments)
                },
                onRestoreMessageRef = { messageID ->
                    restoredMessageRef = messageID
                    timelineRowIndex[messageID]?.let { index ->
                        scope.launch { timelineListState.animateScrollToItem(index) }
                    }
                },
                // B-5: the `model.choose` / `input.focus` chords — see the
                // command handler above.
                externalModelOpen = modelChooseRequest,
                // v74 P0: the model chip's own tap rides the **same** counter.
                // The chip's open state is local `remember`, so a composer that
                // is rebuilt under it (the form/arrangement flip the picker's own
                // focus change triggers by hiding the keyboard) came back with
                // `expanded = false` and the picker never appeared — while
                // `/model` worked, because its request lives here, outside the
                // composer, and is re-read by the chip's `LaunchedEffect` on
                // every re-mount.
                onModelPickerRequest = { modelChooseRequest += 1 },
                externalFocus = inputFocusRequest,
                // B-19: the three composer-editor commands, same channel.
                externalAttachRequest = attachRequest,
                externalShellMode = shellModeRequest,
                externalNormalMode = normalModeRequest,
                // B-12: the popover's keybind column shows the effective chords.
                keybindOverrides = keybindOverrides,
                // B-13: the picker's「管理模型」row leaves for Settings→Models.
                onManageModels = onManageModels,
            )
        }
    }

    // v55 P0: the composer's single call site (see the note above
    // `composerRegion`) — `timelinePane` mounts it in one of two exclusive
    // arrangements, and both go through this.
    //
    // v74 P0: …and **one identity** across those two arrangements. They used to be
    // different parents in the composition tree, so a flip disposed the whole
    // composer and built a new one — the editor's IME session with it. A short
    // window sits close enough to `composerSharesViewport`'s threshold that its
    // own content (a wrapped line, the open `/`/`@` card, the model picker) moves
    // the verdict, so the editor was rebuilt *while the user typed*: measured on
    // `emulator-5554` (`research/v74_verify/5554/11_typed.png`) the line came back
    // as `loot` instead of `hello short window test`, and logcat carried 85×
    // Gboard's `Ignore [FetchSuggestions] diff due to stale request …
    // lastInputStateId=2043` — a connection that never advances because the app
    // keeps re-attaching a new editor instead of committing state to the one the
    // IME already owns.
    //
    // v76 P0: the two arrangements are now the **same parent** (`SessionPaneArrangement`
    // below measures one node tree; a flip only changes the viewport's
    // constraints and the host's own modifier), so the composer is composed *in
    // place* and no move happens on a flip at all. The slot is kept for the one
    // path that does still move the subtree — the mobile view-tab switch, which
    // disposes the whole pane (`key(mobileTab)`) — so the editor keeps its nodes,
    // its `remember`ed state (draft, tokens, the picker popup) and its IME
    // session there.
    //
    // The content is read through the movable lambda's **parameter** rather than a
    // `rememberUpdatedState` mirror: the parameter is part of the call site, so it
    // is always this frame's lambda, and updating it is a slot write — no state
    // write, therefore no invalidation to re-target while the subtree is in
    // flight (the crash the v75 slot could hit: a pending invalidation plus a move
    // in the same frame, `research/HANDOFF.md`).
    val composerSlot: @Composable (@Composable () -> Unit) -> Unit = remember {
        movableContentOf<@Composable () -> Unit> { region -> region() }
    }

    // -------------------------------------------------------------- timeline
    val timelinePane: @Composable (Modifier, (@Composable () -> Unit)?) -> Unit =
        { timelineModifier, header ->
            // v55 P0 「短窗消息区零渲染」: the composer is an **opaque**
            // `BottomCenter` overlay inside this pane (see `composerRegion`), so
            // on a pane shorter than `composer + a message row` the messages
            // were composed *inside* it — laid out, in the a11y tree, and
            // painted over. Every one of the v52–v54 padding repairs moved the
            // size of a band nobody could see. On that pane the composer is
            // laid out below the timeline instead, so the whole viewport is
            // visible messages (`SessionPaneLayout.composerSharesViewport`,
            // `research/v54_verify/tE/v54_38_short_clean.png`).
            //
            // The decision reads the **pane** height, not the viewport's: in the
            // shared form the viewport is the pane minus the composer, so
            // reading it would let each arrangement argue for the other.
            val sharedComposer = SessionPaneLayout.composerSharesViewport(
                paneHeight,
                composerHeight,
            )
            // v57 P0 「短窗 composer 塌成麦克风条」: the composer's own ceiling — the
            // shared form may never grow into the message band (a three-line draft
            // in an auto-growing editor would otherwise take the pane's last dp).
            // The floor is the cheapest composer the pane can lay out — the one-row
            // [SessionPaneLayout.COMPOSER_MINIMAL_HEIGHT] form on a pane too short
            // for the stacked one, the compact 96 dp above it. It rides the host's
            // own modifier inside `SessionPaneArrangement`, so a flip never changes
            // the host's *parent*, only its constraints.
            val composerCeiling = (paneHeight - SessionPaneLayout.TIMELINE_MIN_CONTENT)
                .coerceAtLeast(
                    if (minimalComposer) {
                        SessionPaneLayout.COMPOSER_MINIMAL_HEIGHT
                    } else {
                        SessionPaneLayout.COMPOSER_COMPACT_HEIGHT
                    },
                )
                .coerceAtMost(paneHeight)
            Column(
                modifier = timelineModifier
                    .fillMaxWidth()
                    .onSizeChanged { paneHeightPx = it.height },
            ) {
                SessionPaneArrangement(
                    shared = sharedComposer,
                    composerCeiling = composerCeiling,
                    onViewportSize = { viewportHeightPx = it.height },
                    timelineFloor = SessionPaneLayout.timelineViewportFloor(paneHeight),
                    composer = { composerSlot(composerRegion) },
                ) {
                    // F-3: every link inside the timeline resolves through the
                    // in-app handler (markdown taps and the tool/websearch
                    // `openUri` call sites all read `LocalUriHandler`).
                    CompositionLocalProvider(LocalUriHandler provides inAppUriHandler) {
                        MessageTimeline(
                            messages = state.messages,
                            runStatus = state.runStatus,
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            // v54 P0: the top step is part of the same budget as the
                            // composer's reserve below — on a short window it drops
                            // to the compact step so the item band is not spent on
                            // the gap above the oldest row
                            // (`SessionPaneLayout.timelineTopPadding`).
                            top = SessionPaneLayout.timelineTopPadding(
                                viewportHeight,
                                composerHeight,
                            ),
                            bottom = if (sharedComposer) {
                                // The composer is a sibling of the list here, not a
                                // cover over it: reserving its height would push the
                                // last row out of a viewport that is one row tall.
                                SessionPaneLayout.TIMELINE_BOTTOM_GAP_SHARED
                            } else {
                                SessionPaneLayout.timelineContentBottom(
                                    viewportHeight,
                                    composerHeight,
                                    TIMELINE_BOTTOM_GAP,
                                )
                            },
                        ),
                        stickyHeader = header,
                        toolOpen = toolOpen,
                        onToggleTool = { key, open -> toolOpen[key] = !open },
                        // Upstream suppresses the "Working" row while a permission /
                        // form dock is waiting (`project-avatar-state.ts:41-50`).
                        hasPendingRequest = state.pendingPermissions.isNotEmpty() ||
                            state.pendingForms.isNotEmpty(),
                        // §3.3 card replaces the M1.5 composer error banner; the
                        // setup hint below still has its own banner (it is a
                        // configuration problem, not a run failure).
                        errorMessage = state.error,
                        errorDetail = state.errorDetail,
                        // 用户消息操作行的「撤销到此」(`message-content.tsx:328-338`).
                        onRevertMessage = { messageID -> chatViewModel.revertToMessage(messageID) },
                        revertBusy = reverting,
                        // BUG-2 (v36 tB): a parked steer the server never delivered
                        // gets its way out — the row shows it while the session is
                        // idle (see `MessageTimeline`), and this re-delivers through
                        // `PATCH …/inbox/{id}{delivery:"steer"}`.
                        onRetryPending = { inboxID -> chatViewModel.retryPendingDelivery(inboxID) },
                        // U-3: hoisted so a restored draft's message ref can
                        // scroll the timeline back to it (see
                        // `onRestoreMessageRef`); the position map arrives here.
                        listState = timelineListState,
                        onMessageIndex = { timelineRowIndex = it },
                        // A-1: relative markdown images resolve against the session
                        // directory (`GET /api/fs/read`'s `location[directory]`) —
                        // the same root the review panel uses, server-confirmed
                        // when it has landed.
                        imageDirectory = reviewRootPath,
                        modifier = Modifier.fillMaxSize(),
                    )
                    }
                    // Empty session: never a blank screen (M1 review "空态兜底").
                    if (state.messages.isEmpty()) {
                        // v74 P0: centred in the **band** the budget reserves for
                        // the messages, not in the pane. In the floating form the
                        // pane's centre sits under the composer's opaque
                        // rectangle as soon as the pane is shorter than twice the
                        // composer — the reported「Build anything 被 composer
                        // 边缘切半」.
                        //
                        // v86 P0: the band is **not** ≥ TIMELINE_MIN_CONTENT in
                        // every arrangement — that v74 sentence is the premise the
                        // 720x1280 @420dpi keyboard frame falsifies. There the pane
                        // is 67.05 dp, the one-row composer takes its 56 dp, and
                        // the shared arrangement's timeline box — the box this
                        // label is `Alignment.Center` of — is **11.05 dp**
                        // (`research/v85_verify/5554/03_created.png`). A 20 dp line
                        // centred in it hangs ~4.5 dp past each end: the top slice
                        // leaves the pane and the bottom slice goes under the
                        // composer's card, and what stayed on screen was the middle
                        // of the glyphs — read as「Build anvthing」, the descender
                        // gone rather than a missing word. Nothing floors that box:
                        // `composerSharesViewport` only asks that the composer fit
                        // the pane.
                        //
                        // So the fallback renders only where one whole line fits
                        // ([SessionPaneLayout.emptyStateFits]), and below that the
                        // empty state is the empty box — which is what this pane
                        // already showed on the 480 dpi and v84 frames, where the
                        // box is 0 dp. `lineHeight` is the label's own 13 sp at
                        // *this* configuration's font scale, so large-type users
                        // get the same guarantee. No string is added or reworded:
                        // the label is either drawn exactly as before or not
                        // composed at all.
                        val emptyStateLine =
                            SessionPaneLayout.EMPTY_STATE_LINE_HEIGHT * density.fontScale
                        if (
                            SessionPaneLayout.emptyStateFits(
                                shared = sharedComposer,
                                viewport = viewportHeight,
                                composerHeight = composerHeight,
                                lineHeight = emptyStateLine,
                            )
                        ) {
                            Text(
                                text = stringResource(R.string.oc_timeline_empty),
                                color = c.textFaint,
                                fontSize = 13.sp,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .offset(
                                        y = SessionPaneLayout.emptyStateShift(
                                            shared = sharedComposer,
                                            viewport = viewportHeight,
                                            composerHeight = composerHeight,
                                            gap = TIMELINE_BOTTOM_GAP,
                                        ),
                                    )
                                    .padding(horizontal = 32.dp),
                            )
                        }
                    }
                    // v43: the wide-screen terminal entry chip, owned by the timeline
                    // pane so a docked review panel (the row's other child) can
                    // never sit under it. See [onToggleTerminalDock].
                    if (desktop && onToggleTerminalDock != null) {
                        TerminalEntryButton(
                            onClick = onToggleTerminalDock,
                            active = terminalDocked,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(
                                    top = SessionTerminalEntryPolicy.TOP_DP,
                                    end = SessionTerminalEntryPolicy.END_DP,
                                ),
                        )
                    }
                }
            }
        }

    // ------------------------------------------------------- drawer contents
    val drawerContent: @Composable () -> Unit = {
        SessionDrawerContent(
            // v70 P2: a confirmed delete's row is dropped here at once, the way
            // Home's tombstone does it — a catalogue read that was already in
            // flight must not put the row back under the finger.
            sessions = sessions.filterNot { it.id in (deleteHost?.confirmed ?: emptySet()) },
            currentSessionId = activeSession.id,
            // M6: the working session's row carries the 25-dot marquee
            // (`session-progress-indicator-v2`).
            runningSessionId = activeSession.id
                .takeIf { state.runStatus == ChatStore.RunStatus.RUNNING },
            // A-2: the row's unread dot — the strips' own derivation, through the
            // same `SessionDotSources` the tab strip assembles, so the drawer and
            // the strip can never disagree about which session is waiting. The
            // ask halves come from the **location-wide** ledger
            // (`ChatViewModel.sessionAsks`, upstream's
            // `session.permission.list` / `session.form.list`), which is what
            // makes a foreign session's pending permission / form light its own
            // row; the in-app feed and the server row's own watermark
            // (`ChatViewModel.sessionUnread`) are the other two sources, and the
            // tracked session's own dock list is the belt to the ledger's braces.
            unseen = { session -> dotSources.unread(session.id) },
            // A-3: the ticking clock the rows' relative labels are measured
            // against (see the effect above); without it a row's label freezes
            // at the bucket it was composed with.
            now = drawerNow,
            sheet = !desktop,
            onNewSession = {
                closeDrawer()
                onNewSessionDraft()
            },
            onSelect = switchSession,
            // W4-C row menu (long press 500 ms): Rename / Delete. Rename is
            // confirmed by the screen-level dialog below; Delete arms the same
            // card as the header ⋮ (v70 P2), whose host owns the write.
            onRenameSession = { session -> renameTarget = session },
            onDeleteSession = { session ->
                deleteHost?.ask(session.id, session.title, session.directory)
            },
        )
    }

    // ------------------------------------------------------------ body column
    val bodyContent: @Composable () -> Unit = {
      // v51 P0: the pane's height budget is derived here — *inside* the inset
      // padding, so it already accounts for the soft keyboard. `BoxWithConstraints`
      // is the only way to know the post-inset height the column actually has.
      //
      // v77 P0: the box now measures the pane **with the keyboard put back** —
      // the insets applied to it are the top/horizontal ones only (plus, below,
      // the platform's own navigation-bar reading is folded into the bottom
      // padding the same way `safeDrawing` did). The IME half is applied to the
      // inner column instead, through
      // [SessionPaneLayout.believedImeBottomPx]: the pane has to know the room it
      // *has* before it can judge the room a keyboard claims (see that function
      // for the measured 5554 case — a stale 836 dp IME inset on a 1140 dp
      // window collapsed the body to 209 dp and dropped the chrome).
      BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(c.bgBase)
            // B-16: on the bottom stop the header row is the last child of
            // this column, and the strip `ui/App.kt` renders below the
            // screen already pads itself by `navigationBars`
            // (`BottomTabStrip`). Keeping `safeDrawing`'s bottom here as well
            // would open an empty nav-bar-height band between the header and
            // that strip. The IME half stays: it is what lifts the composer
            // (and the header) off the keyboard — it is the inner bottom
            // padding below.
            //
            // v55 P0 「短窗 composer 被键盘盖住」: the IME half used to be
            // unioned **only** on the 底部导航 form, so with the header on top
            // (the default) the body kept `safeDrawing` alone — which carries
            // no `ime` inset at all. The composer is a `BottomCenter` overlay,
            // so the soft keyboard simply covered it: Send was unreachable and
            // the tap landed on the mic instead (`research/v54_verify/tE/v54_05_typed`,
            // `v54_06_tapsend`, `v54_08_enter`). The inner padding below is that
            // union, and it is derived from the same reading it always was
            // (`WindowInsets.ime`'s bottom).
            .windowInsetsPadding(
                WindowInsets.safeDrawing
                    .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            ),
      ) {
        // v77 P0: the keyboard's claim, judged by the box that has to live with
        // it. `maxHeight` here is the pane box *without* the IME inset — the top
        // insets and the shell's own strip are already out of it, and the
        // navigation bar is what the bottom padding pays for below (0 on the
        // bottom-titlebar form: `BottomTabStrip` pays it, and the pane must not
        // open the empty band B-16 removed).
        val paneBoxPx = with(density) { maxHeight.roundToPx() }
        val navBottomPx = WindowInsets.navigationBars.getBottom(density)
        val paneFloorPx = if (titlebarBottom && !desktop) 0 else navBottomPx
        val believedImeBottomPx = SessionPaneLayout.believedImeBottomPx(
            visibleBoxPx = paneBoxPx,
            floorPx = paneFloorPx,
            imeBottomPx = WindowInsets.ime.getBottom(density),
            essentialPx = with(density) { SessionPaneLayout.PANE_ESSENTIAL_HEIGHT.roundToPx() },
        )
        val paneBottomDp = with(density) {
            maxOf(paneFloorPx, believedImeBottomPx).coerceAtLeast(0).toDp()
        }
        // v74 P0: the composer's one-row form, decided against the **body** this
        // pane is really built on (`maxHeight` — post-inset, so the keyboard is
        // already out of it) rather than the display height alone. The write is
        // consumed by `composerRegion`/`timelinePane` below, which are invoked
        // after it, so this settles in one pass; the input is composer-free, so
        // it cannot oscillate with the composer it decides.
        //
        // v77 P0: `maxHeight` is the *pane box* now (the box with the keyboard
        // put back — see above), so the body is that box minus the bottom
        // padding the pane is about to apply. With the raw inset believed this is
        // byte-identical to the post-inset `maxHeight` the earlier rounds read.
        val bodyHeightDp = (maxHeight - paneBottomDp).coerceAtLeast(0.dp)
        val bodyOneRow = SessionPaneLayout.composerOneRow(
            ShellChromeBudget.isShortWindow(windowHeightDp),
            bodyHeightDp,
        )
        if (bodyOneRow != minimalComposer) minimalComposer = bodyOneRow
        // The chrome (header row + view tabs) yields first: the composer keeps
        // its intrinsic height and the timeline keeps a floor, so a short
        // window can no longer crush the tool row or measure the timeline to
        // 0 dp (`SessionPaneLayout`, `research/v51_verify/tE/v51_07_typed`,
        // `v51_12_msgs`, `tC/tC_55_narrow`). Inert on a healthy window.
        val chromeMaxHeight = remember(bodyHeightDp, titlebarBottom, composerHeight) {
            // v52 P0: with「底部导航」on the pane mounts the chrome **twice** (the
            // view tab strip above the content, the header row below it), so the
            // budget is split over the two bands instead of being handed to each
            // of them (`SessionPaneLayout.chromeBandMaxHeight`).
            val chromeBudget = SessionPaneLayout.chromeMaxHeight(bodyHeightDp)
            // v55 P0: against the composer this pane actually has, so a pane
            // that cannot pay the composer, the chrome and a message row gives
            // the chrome up instead of keeping a band the composer covers
            // (`chromeMaxHeightForComposer`).
            val ceiling = SessionPaneLayout.chromeMaxHeightForComposer(
                bodyHeightDp,
                if (composerHeight > 0.dp) composerHeight else SessionPaneLayout.COMPOSER_MIN_HEIGHT,
            )
            SessionPaneLayout.chromeBandMaxHeight(
                bodyHeightDp,
                ceiling,
                chromeBands = if (titlebarBottom && !desktop) 2 else 1,
            ).coerceAtMost(chromeBudget)
        }
        // v78 P0 「短窗+键盘：整个会话头顶栏消失」/ v84 P0 「720x1280 @420dpi：
        // 会话页头与视图 tab 整条不渲染」: the band the keyboard leaves the chrome
        // (see `SessionPaneLayout.chromeImeBand`). A ceiling alone can only take
        // the chrome to 0, which is what stranded the measured 128.38 dp body at
        // 420 dpi with no back button, no title and no view tabs. The keyboard is
        // up exactly when the believed inset exceeds the floor the pane already
        // pays, which is the same reading `bodyHeightDp` above is derived from.
        val imeVisible = believedImeBottomPx > paneFloorPx
        val chromeBand = SessionPaneLayout.chromeImeBand(imeVisible, bodyHeightDp)
        // The band follows the stops that mount it: the top stop carries the
        // header row and the strip by default, so it takes the whole band; the
        // bottom stop with「底部导航」carries the header row alone, and the
        // tabs-only stop takes the strip's share — nothing until the body can
        // pay the whole band, so the strip is what gives way first.
        val topChromeFloor = if (titlebarBottom && !desktop) {
            SessionPaneLayout.chromeBandStripShare(chromeBand)
        } else {
            chromeBand
        }
        val bottomChromeFloor = if (titlebarBottom && !desktop) {
            SessionPaneLayout.chromeBandHeaderShare(chromeBand)
        } else {
            0.dp
        }
        val viewportMinHeight = remember(bodyHeightDp, titlebarBottom) {
            SessionPaneLayout.timelineMinHeight(bodyHeightDp)
        }
        Box(modifier = Modifier.fillMaxSize().padding(bottom = paneBottomDp)) {
        Column(modifier = Modifier.fillMaxSize()) {
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
                                vcsDiffFailed = vcsDiffFailed,
                                vcsDiffPending = vcsDiffPending,
                                onRequestVcsDiff = requestVcsDiff,
                                // 「刷新」: evict the review root's cached location
                                // on the user's tap and re-probe (see the entry
                                // point above). The panel re-requests the active
                                // scope's diff itself when `reprobeKey` changes;
                                // this read covers the branch chip.
                                onRefreshVcs = reprobeReviewVcs,
                                // The no-git empty state's CTA — the same action
                                // the mobile Changes tab wires (`ReviewGitInit`).
                                onInitGit = initReviewGit,
                                gitPending = gitInitPending,
                                reprobeKey = reviewReprobeKey,
                                tab = reviewTab,
                                onTabChange = { reviewTabName = it.name },
                                openPath = openFilePath,
                                // B2: the context tab's projection of the raw
                                // payloads read above (empty until they land).
                                contextSnapshot = contextSnapshot,
                                // F-2 line comments: tap-to-quote + per-file
                                // aggregated notes, both appended to the
                                // composer draft.
                                commentStore = reviewCommentStore,
                                onQuoteLine = appendReviewText,
                                onSendComments = appendReviewText,
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
                                // v50: the session's **current** directory, not
                                // the row's opening one — after 「移动到…」 a new
                                // terminal must be created in the directory the
                                // session now lives in, or its `pwd` (and every
                                // build run in it) stays in the old repository.
                                directory = reviewRootPath,
                                onClose = onCloseTerminalDock,
                                modifier = Modifier.fillMaxHeight(),
                                // B-5: `terminal.new` — one new tab per press.
                                newTabRequest = newTerminalRequest,
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
                            directory = reviewRootPath,
                            onClose = onCloseTerminalDock,
                            modifier = Modifier.fillMaxWidth(),
                            // B-5: `terminal.new` — one new tab per press.
                            newTabRequest = newTerminalRequest,
                        )
                    }
                }
            } else {                // Mobile: header (with ☰ → bottom sheet) then the four
                // full-width view tabs, then the selected view. The timeline
                // header moves out of `MessageTimeline`'s sticky slot so the
                // tab strip sits between it and the content.
                //
                // B-16: with「底部导航」on, the header is the half that moves —
                // upstream puts `order-last` on the whole `<header
                // data-slot="titlebar-v2">` (`titlebar.tsx:150-164`), whose
                // mobile row is the tab trigger plus the trailing actions, while
                // the view tabs stay where they are (they are the session
                // panel's own tab row). [titlebarBottom] is that flag; the strip
                // itself is already on the bottom edge (`ui/App.kt`).
                // v51 P0: the chrome band is capped (see `chromeMaxHeight`
                // above) and scrolls instead of pushing the composer — and with
                // it the whole timeline — out of a short window. The header row
                // scrolls first (it is the elastic half of the band); both stay
                // reachable by scrolling once the ceiling binds.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = topChromeFloor, max = maxOf(chromeMaxHeight, topChromeFloor))
                        .verticalScroll(rememberScrollState()),
                ) {
                if (!titlebarBottom) headerSlot()
                MobileSessionTabs(
                    current = mobileTab,
                    onSelect = { tab ->
                        usageOpen = false
                        summaryOpen = false
                        switchMobileTab(tab)
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
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        // v51 P0: the floor the viewport keeps when the composer
                        // and the chrome ceiling are already paid for — derived
                        // so it gives way instead of overflowing the column (see
                        // `SessionPaneLayout.timelineMinHeight`).
                        .heightIn(min = viewportMinHeight),
                ) {
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
                                backgroundTasks = summaryBackgroundTasks,
                                onClose = { summaryOpen = false },
                                // 44 dp rows + the full-width card, upstream's
                                // phone variant (`summary.css:299-307`).
                                mobile = true,
                                modifier = Modifier.align(Alignment.TopCenter),
                            )
                        }
                    } else androidx.compose.runtime.key(mobileTab) {
                        // `key(mobileTab)` rebuilds the surface whole on every
                        // switch, so a hidden tab's inputs can never survive as
                        // keyboard/IME focus owners (silent-prompt fix, with
                        // `switchMobileTab`'s clearFocus and the composer gate).
                        when (mobileTab) {
                            // Mobile Changes parity (desktop's `SessionReviewPanel`
                            // call site above): scope + branch + baseLabel 下传,
                            // so the page gets the branch chip, the Git / Branch /
                            // Last-turn switch and the 「相对 name@ref」 line
                            // instead of being pinned to LAST_TURN.
                            MobileForm.Tab.CHANGES -> {
                                // Desktop parity: the page's default scope is the
                                // panel's — `ReviewScopes.defaultScope(available)`,
                                // i.e. upstream's `git` when the project has a
                                // repository (`review/model.ts:40`), the session
                                // snapshot only as the fallback. A stored choice
                                // wins while it is still offered; when the project
                                // turns out to have no repository, the scope falls
                                // back on its own (the panel does the same through
                                // its `LaunchedEffect(available)`).
                                val availableScopes = remember(reviewProjectVcs, reviewBranch, reviewDefaultBranch) {
                                    ReviewScopes.available(
                                        hasVcs = reviewProjectVcs ?: true,
                                        currentBranch = reviewBranch,
                                        defaultBranch = reviewDefaultBranch,
                                    )
                                }
                                val mobileReviewScope = ReviewChangeScope.values()
                                    .firstOrNull { it.name == mobileReviewScopeName && it in availableScopes }
                                    ?: ReviewScopes.defaultScope(availableScopes)
                                LaunchedEffect(mobileReviewScope, reviewReprobeKey) {
                                    if (mobileReviewScope == ReviewChangeScope.LAST_TURN) {
                                        return@LaunchedEffect
                                    }
                                    requestVcsDiff(mobileReviewScope)
                                }
                                val mobileVisibleDiffs = remember(mobileReviewScope, state.diff, vcsDiffs) {
                                    ReviewScopes.diffsFor(mobileReviewScope, state.diff, vcsDiffs)
                                }
                                val vcsBase = rememberVcsBase(reviewRootPath, reviewReprobeKey)
                                ReviewChangesTab(
                                    diffs = mobileVisibleDiffs,
                                    modifier = Modifier.fillMaxSize(),
                                    // Same 「Create a Git repository」 gate as the
                                    // desktop panel (`ReviewScopes.noGit(hasVcs,
                                    // projectVcs, …)`, `ReviewPanel`): a project
                                    // the table proved has no repository offers
                                    // the CTA instead of the empty-changes copy,
                                    // and a non-empty list is never displaced.
                                    noGit = ReviewScopes.noGit(
                                        hasVcs = reviewProjectVcs,
                                        projectVcs = reviewProjectVcs,
                                        hasChanges = mobileVisibleDiffs.isNotEmpty(),
                                    ),
                                    // The CTA's action: `git init` in the review
                                    // root, then the location evict (`ReviewGitInit`).
                                    onInitGit = initReviewGit,
                                    gitPending = gitInitPending,
                                    scope = mobileReviewScope,
                                    baseLabel = ReviewVcsBase.label(vcsBase),
                                    branch = reviewBranch,
                                    onScopeChange = { mobileReviewScopeName = it.name },
                                    // The project-table verdict, not "a branch
                                    // answered": the two differ exactly for a
                                    // location whose cached graph predates a
                                    // `git init` (see `ReviewVcsReprobe`).
                                    hasVcs = reviewProjectVcs,
                                    defaultBranch = reviewDefaultBranch,
                                    vcsWired = true,
                                    onRefresh = reprobeReviewVcs,
                                    refreshPending = reviewRefreshPending,
                                    // A failed or still-out scope read says so
                                    // (and the failure offers the one-request
                                    // retry) instead of showing the empty copy;
                                    // the desktop panel wires the same state.
                                    loadFailed = vcsDiffFailed,
                                    loadPending = vcsDiffPending,
                                    onRetryDiff = { requestVcsDiff(mobileReviewScope) },
                                    // F-2: same per-session store and composer
                                    // wiring as the desktop panel above.
                                    commentStore = reviewCommentStore,
                                    onQuoteLine = appendReviewText,
                                    onSendComments = appendReviewText,
                                )
                            }

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
                                // v50: same rule as the docked pane above —
                                // `reviewRootPath` (`currentDirectory` first).
                                directory = reviewRootPath,
                                // The panel's 「Session terminals」 entry needs
                                // the session (`TerminalPanelHost` gates it on
                                // `sessionID != null`); `TerminalDock` hands its
                                // `sessionId` over the same way.
                                sessionID = activeSession.id,
                                onClose = { switchMobileTab(MobileForm.Tab.SESSION) },
                                modifier = Modifier.fillMaxSize(),
                            )

                            else -> timelinePane(Modifier.fillMaxSize(), null)
                        }
                    }
                }
                // B-16: the bottom stop of the header row — under the content
                // (and its composer overlay), above the shell's bottom strip,
                // which `ui/App.kt` renders after this screen.
                //
                // v51 P0: the bottom stop carries the same ceiling as the top
                // one — this is the last band before the composer's own height,
                // so an uncapped header row is what pushed the tool row (and
                // with it the Send button) off a short window. It scrolls once
                // the ceiling binds.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = bottomChromeFloor, max = maxOf(chromeMaxHeight, bottomChromeFloor))
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (titlebarBottom) headerSlot()
                }
            }
        }
        }
        }
    }

    // 「重试」 on the not-found panel: re-enter the same session instead of
    // leaving. `observeSession` is what clears the 404 latch
    // (`ChatViewModel.markSessionMissing`) and `start()` reopens the global +
    // per-session streams that latch closed; `stop()` comes first because
    // `start()` is a no-op while the screen is still marked started.
    val retryMissingSession: () -> Unit = {
        chatViewModel.stop()
        chatViewModel.observeSession(
            id = activeSession.id,
            title = activeSession.title,
            directory = activeSession.directory,
        )
        chatViewModel.start()
    }

    // v31 BUG-2: while the header's inline title editor is up, a press that
    // landed on nothing interactive blurs it — the tap-away the device never
    // got (see [blurTitleEditorOnOutsideTap]).
    //
    // v74: the pane's keyboard owner ([TerminalImeFocus]) is provided from here —
    // the outermost node both the composer and the docked terminal are composed
    // under, so the terminal's report reaches the composer's auto-focus no matter
    // which arrangement the pane is in.
    val terminalImeFocus = remember { TerminalImeFocus() }
    Box(
        modifier = modifier
            .fillMaxSize()
            .blurTitleEditorOnOutsideTap(titleEditing, focusManager, titleEditFence),
    ) {
        CompositionLocalProvider(LocalTerminalImeFocus provides terminalImeFocus) {
        val sshFallbackHost = sshHost
        if (incompatibleServerVersion != null) {
            // B-7: upstream shows this page whenever the active server's health
            // is `incompatible` (`session/route-error.tsx:26-28`), i.e. before
            // the not-found branch — a server that cannot be talked to at all
            // explains every failure under it.
            IncompatibleServerPanel(
                version = incompatibleServerVersion.orEmpty(),
                serverLabel = serverStore?.server?.url,
                onBack = onBack,
                // The probe is the thing that produced this verdict, so the
                // retry is a fresh probe round (`ChatViewModel.retryProbe`).
                onRetry = { chatViewModel.retryProbe() },
                modifier = Modifier.fillMaxSize(),
            )
        } else if (sessionMissing) {
            // The route-level failure state, first in the stack: upstream
            // replaces the whole session view with this page, so the body,
            // the drawer and the ssh fallback stay unmounted.
            SessionNotFoundPanel(
                sessionID = activeSession.id,
                serverLabel = serverStore?.server?.url,
                onBack = onBack,
                onRetry = retryMissingSession,
                // v81 P2 「幽灵会话死胡同」 (`research/v81_verify/5558/t4_s02_empty_session.png`):
                // Back and Retry both leave the dead entry where it is — Back
                // returns with the row (and its tab) still listed, and Retry
                // re-runs the same reads for a session the server has dropped.
                // So the page can also drop the entry itself.
                //
                // v83 P1 (`research/v83_verify/5558/25_after_delete.png` →
                // `26_list_after_del.png`): it now asks the **server** first
                // (`SessionDeleteHost.deleteEntry`) and forgives the `404` of a
                // session the server never had, so both the measured `REFUSED`
                // entry (`DELETE` → `204`, really gone) and an already-lost one
                // end in the same place: the row leaves the catalogue, the tab
                // closes, the route moves. v81 stopped at the local half, which
                // closed the tab over a row that stayed in Home and a session
                // that stayed on the server.
                onDelete = {
                    val host = deleteHost
                    if (host == null) {
                        // No shell above this screen (previews, an isolated host):
                        // nothing to write through, so the v81 local exit stands.
                        chatViewModel.forgetSession(activeSession.id)
                        onBack()
                    } else {
                        host.deleteEntry(activeSession.id, activeSession.directory) { deleted ->
                            // The local catalogue drop, only once the server has
                            // agreed — the host's own `forget` closes the tab.
                            if (deleted) chatViewModel.forgetSession(activeSession.id)
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
                // v82 BUG-A: the 5xx shape says its own thing (the server
                // refuses this session — the measured missing-project-directory
                // case), the 404 default keeps the ghost-entry copy.
                reason = sessionMissingReason ?: SessionOpenFailure.GONE,
                // v83 P1: the exit's own state, read back per session id so a
                // refusal latched for one entry cannot paint on another's page.
                entryDeleteBusy = entryDelete?.busy == true,
                entryDeleteError = if (entryDelete?.failed == true) {
                    stringResource(R.string.oc_delete_session_failed)
                } else {
                    null
                },
            )
        } else if (sshFallback != null && sshFallbackHost != null) {
            SshTunnelPanel(
                host = sshFallbackHost,
                configId = sshFallback.config.id,
                modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            )
        } else if (desktop) {
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

        // F-3 embedded browser: upstream's `SessionBrowserPane`
        // (`session/browser/pane.tsx`) is a pane of the workspace panel region;
        // on Android the non-destructive mount is a full-body overlay here —
        // above the drawer/body shells, below the confirmations and dialogs
        // under it, so a row-menu or error dialog still paints on top.
        //
        // B-18: `browser.reload` reaches the pane through
        // `LocalBrowserReloadRequest` — the pane's three-argument wiring
        // contract is pinned by `SessionBrowserPaneTest`, so the request travels
        // as an ambient counter instead of a fourth parameter (the channel
        // pattern `SessionCommandIntents` uses for screen-local state).
        browserUrl?.let { url ->
            // v30 BUG-1 fallback exit: BACK / the back gesture closes the pane
            // while it is up. The pane never intercepts back itself (its wiring
            // contract is three parameters and `onClose` is the only dismissal
            // it owns), so the owner registers it here — composed *after* the
            // body and every nav-level handler, which is what makes this one win
            // on the activity's `OnBackPressedDispatcher`. Without it the only
            // way out of a full-body overlay whose toolbar is unreachable is a
            // process restart.
            //
            // v31 BUG-1: registered through [OverlayBackHandler] rather than
            // `BackHandler`, and the IME is collapsed on entry. Measured on the
            // device: the composer kept focus (and the keyboard) behind the
            // overlay, so the first BACK only hid the keyboard, and the presses
            // that did reach the app fell through to `finish()` — the overlay
            // never closed. `BackHandler` adds its callback with
            // `addCallback(lifecycleOwner, …)`, which is a **no-op until that
            // lifecycle reaches STARTED**, so a destination whose entry is not
            // started yet leaves the overlay with no handler at all.
            OverlayBackHandler { browserUrl = null }
            LaunchedEffect(url) { keyboardController?.hide() }
            CompositionLocalProvider(
                LocalBrowserReloadRequest provides browserReloadRequest,
            ) {
                SessionBrowserPane(
                    url = url,
                    onClose = { browserUrl = null },
                    onOpenExternal = { openExternalUrl(url) },
                )
            }
        }

        // v82 BUG-B: the failed rename's sentence, parked until dismissed (the
        // toast that carries it at the moment of failure lives 3.5 s). Painted
        // with the other row-menu confirmations, above both shells.
        renameFailureMessage?.let { message ->
            RenameFailureDialog(
                message = message,
                onDismiss = { renameFailureMessage = null },
            )
        }
        // Row-menu confirmations paint above both shells (the drawer sheet is
        // only as tall as its list, so a dialog inside it would be misplaced).
        renameTarget?.let { session ->
            SessionRenameDialog(
                initialTitle = session.title,
                onDismiss = { renameTarget = null },
                onConfirm = { newTitle ->
                    renameTarget = null
                    chatViewModel.renameSession(session.id, newTitle) { outcome ->
                        val ok = outcome == SessionRenameOutcome.RENAMED
                        if (ok) {
                            sessions = sessions.map { row ->
                                if (row.id == session.id) row.copy(title = newTitle) else row
                            }
                            if (session.id == activeSession.id) pendingTitle = newTitle
                        } else {
                            // Same silent branch the delete below had: the card
                            // is already gone by the time the answer lands, so
                            // the toast is the only thing that can say the rename
                            // did not land — Home's row menu reports the very same
                            // way, and since v81 it says **which** shape it was
                            // (`oc_rename_session_refused` / `_gone` / `_failed`).
                            // v82 BUG-B: and because a toast lives 3.5 s, the
                            // sentence is *also* parked on a card the reader has
                            // to dismiss (`renameFailureMessage`).
                            renameFailureToast(context, outcome)
                            renameFailureMessage = renameFailureText(context, outcome)
                        }
                    }
                },
            )
        }
        // v69 P2 (5560 `07f_delete.png` → `07m_delete2.png`) + v70 P2: the card
        // used to close on the tap and say nothing when the server refused the
        // `DELETE` — the dialog was gone, the row was still in the list, and the
        // transport banner that the dead server also raised reads as a timeline
        // problem, not "your delete did not happen". It now holds the answer the
        // way Home's dialog does (`HomeDeleteDialog`): busy while the write is
        // out, the reason on a `false`, the card closed only on `true`.
        //
        // The state and the write are the host's (`SessionDeleteHost`), not this
        // screen's: both entry points — the header ⋮ (W4-E F-4) and the drawer
        // row's long-press menu — arm the one card, and a confirmed delete goes
        // back to the shell's tab set (`forgetDeletedSessionTab`) even if this
        // screen has been recomposed or re-created since the tap. Nothing about
        // the timeline banner reaches it: the banner is a timeline row, and this
        // card's inputs are the host's `busy`/`failed` alone.
        deleteHost?.card?.let { card ->
            SessionDeleteDialog(
                sessionTitle = card.title,
                busy = card.busy,
                errorText = if (card.failed) stringResource(R.string.oc_delete_session_failed) else null,
                onDismiss = { deleteHost?.dismiss() },
                onConfirm = { deleteHost?.confirm() },
            )
        }
        // Export progress: mounted while the fetch/SAF/fallback attempt is out,
        // with a Cancel that drops the pending saver request. The screen can
        // therefore never wedge on a picker that never answers. `exportWorking`
        // is written by `reportExport` alone, so this surface comes down exactly
        // when the attempt reported how it ended — never silently.
        if (exportWorking) {
            ExportWorkingOverlay(phase = exportPhase, onCancel = ::cancelExport)
        }
        // Usage-exceeded upsell (upstream `usage-exceeded-dialogs.tsx`): adopt
        // the VM-raised upsell only when the 24 h / "don't show again" gate
        // passes, then keep the adopted instance mounted until the VM clears
        // it (dismiss / idle). The screen owns no upsell state itself; the
        // effect (not inline composition) owns the adoption so the dialog's
        // own `markShown` write can never unmount it mid-read, and a newer
        // frame while one is showing is ignored, like upstream's
        // `dialog.active → return`.
        val liveUpsell = state.usageUpsell
        LaunchedEffect(liveUpsell) {
            if (liveUpsell == null) {
                upsellVisible = null
            } else if (upsellVisible == null &&
                UsageUpsellGate.shouldShow(upsellPrefs, liveUpsell.reason)
            ) {
                upsellVisible = liveUpsell
            }
        }
        upsellVisible?.let { upsell ->
            UsageExceededDialog(
                upsell = upsell,
                onDismiss = { chatViewModel.dismissUsageUpsell() },
            )
        }
        // `/mcp` fallback (previews / hosts without the dialog layer): the same
        // chooser card the host dialog shows, mounted inline. Built from the
        // same bridge the settings Extensions panel reads.
        if (mcpOpen) {
            val mcpSource: SettingsServerData = remember(chatViewModel, serverStore) {
                SettingsServerBridge(
                    serverStore,
                    v2 = SettingsV2Delegate.forHost(
                        generation = chatViewModel.generation.value,
                        server = serverStore?.server,
                    ),
                )
            }
            McpChooserDialog(
                source = mcpSource,
                onDismiss = { mcpOpen = false },
            )
        }
        }
    }
}

/** The session currently on screen (nav entry point or a drawer switch). */
private data class ActiveSession(
    val id: String,
    val title: String?,
    val directory: String?,
)

/**
 * Hard stop for the composer's send paths (the silent-prompt fix): a submit
 * may only leave the screen while the composer is the visible surface. The
 * mobile usage / summary views and every non-Session tab render **without** a
 * composer, so a callback firing there is focus residue — injected keys kept
 * landing in the editor that auto-focused on entry — and never user intent
 * (measured: Terminal tab + adb typing + Enter → a session prompt on the
 * server-default model). Desktop always shows the composer (it overlays the
 * timeline), so the gate is open there.
 */
internal object SessionComposerGate {
    fun acceptsInput(
        desktop: Boolean,
        tab: MobileForm.Tab,
        usageOpen: Boolean,
        summaryOpen: Boolean,
    ): Boolean {
        if (desktop) return true
        return tab == MobileForm.Tab.SESSION && !usageOpen && !summaryOpen
    }
}

/**
 * v76 P0: the session pane's two arrangements — the composer as an **opaque cover**
 * over the timeline, or as the pane's **last row** under it — as one node tree.
 *
 * Before this, the arrangements were two different *parents* for the composer
 * (a `Box` inside the viewport in the floating form, a sibling `Box` after it in
 * the shared one), and the flip between them was a `movableContentOf` **move**:
 * the composer's node subtree was lifted out of its old parent and spliced into
 * the new one in the same frame. On device that crashed —
 * `IllegalStateException: Cannot insert LayoutNode@… because it already has a
 * parent`, thrown from `UiApplier.insertBottomUp` under
 * `Operation$CopyNodesToNewAnchorLocation.execute` (`runtime-android-1.7.6`,
 * `changelist/Operation.kt:695`), i.e. the move inserted a node whose
 * `_foldedParent` had not been cleared yet. Two frames are enough to hit it
 * (`research/v75_verify`: 720x1280 @420/480 on `emulator-5554` opening a session,
 * and 「Toggle terminal」 on `emulator-5556`, where the palette's close and the
 * terminal panel's insertion land in the same recomposition as the pane's height
 * change). The runtime side of the hazard is a movable content that is
 * invalidated and moved before it can recompose — its invalidation is retargeted
 * to the *old* slot-table location and the composer then recomposes "in the wrong
 * place" (`issuetracker.google.com/issues/229001114`, fixed after 1.7.6; also
 * 436858107 for the subcomposition variant) — and this app sits inside a
 * `BoxWithConstraints` (a `SubcomposeLayout`), one of the documented amplifiers.
 *
 * So the flip no longer moves anything: the pane is one `Layout` with exactly two
 * children — the viewport `Box` and the composer's host `Box` — and the
 * arrangement only decides
 *
 *  * the constraints the viewport is measured with
 *    ([SessionPaneLayout.viewportHeightPx]: the whole pane when the composer
 *    covers it, the pane minus the composer when it does not), and
 *  * where the host is put ([SessionPaneLayout.composerTopPx]: the pane's bottom
 *    edge minus the host's height).
 *
 * The host's own modifier is the only other thing the arrangement decides
 * (`fillMaxSize` for the cover form, `heightIn(max = ceiling)` for the last-row
 * form), which is exactly the "only the wrapper's constraints change, never its
 * parent" shape — and it is why the composer's `remember`ed state, its IME
 * session and an open picker survive the flip without any movable content
 * machinery in this path at all.
 *
 * The composer's host is the **only** place the composer is mounted, so the
 * "mounted exactly once per frame" invariant of `SessionTabFocusSendGateTest`
 * holds structurally, not by convention.
 *
 * `onViewportSize` is the viewport's own height, reported the same way the
 * inline `Box` used to report it: the content padding below may yield instead of
 * summing past the `LazyColumn` (a padding taller than the viewport composes no
 * item at all — see [SessionPaneLayout.timelineContentBottom]).
 *
 * One deliberate consequence of the single tree: the composer's host is now drawn
 * *after* the whole viewport, so the wide-screen terminal entry chip (a
 * `TopEnd` child of the viewport) is under it rather than over it.
 *
 * **v146 correction — the "never share a pixel" claim that stood here until now
 * was false.** On device at `1366×600` the chip's touch box `[1320,192][1366,240]`
 * and the composer's `Send` `[1316,191][1364,239]` overlapped by
 * `44 × 47 = 2068 px²`, leaving a 2 px residual sliver and **zero visible chip
 * pixels** (`Send` is `enabled="false"` there). No band, no ceiling and no
 * [SessionPaneLayout.TIMELINE_MIN_CONTENT] ever separated them: the two boxes share
 * a row. They are now separated **horizontally** instead — the chip's trailing
 * anchor [SessionTerminalEntryPolicy.END_DP] moved 8 dp → 64 dp, which is what
 * this claim should always have said. The composer still draws over the
 * transcript, which is the order that matters (`TerminalEntryButton` is a corner
 * affordance, the composer is the pane's bottom band).
 */
@Composable
private fun SessionPaneArrangement(
    shared: Boolean,
    composerCeiling: Dp,
    onViewportSize: (IntSize) -> Unit,
    timelineFloor: Dp,
    composer: @Composable () -> Unit,
    viewport: @Composable BoxScope.() -> Unit,
) {
    val arrangementDensity = LocalDensity.current
    Layout(
        content = {
            Box(Modifier.fillMaxSize().onSizeChanged(onViewportSize)) { viewport() }
            Box(
                contentAlignment = Alignment.BottomCenter,
                modifier = Modifier
                    .fillMaxWidth()
                    // v55 P0: the composer as the pane's **last row** rather than
                    // its cover — the same composable, the same opaque background,
                    // but the viewport above it is now entirely visible messages.
                    // …and it may never grow into the band: a three-line draft in
                    // an auto-growing editor would otherwise take the pane's last dp
                    // and put the messages back where the whole v51–v55 chase
                    // started. `composerCeiling` is that cap (the caller computes it
                    // from the pane, never from the composer it caps).
                    //
                    // The cover form is the host that fills the pane and
                    // bottom-aligns the composer: the same pane-wide
                    // `BottomCenter` anchor the composer used to have, so both
                    // arrangements draw over the same rectangle.
                    .let { if (shared) it.heightIn(max = composerCeiling) else it.fillMaxSize() },
            ) {
                composer()
            }
        },
    ) { measurables, constraints ->
        // v100 P0: the band is a **term of the host's own constraints**, not a
        // hope the viewport makes afterwards.
        //
        // Before v100 the host was measured against the **whole** pane with
        // `minHeight = 0`, and the viewport then took whatever was left over —
        // `viewportHeightPx` floored that remainder at **0**. So the timeline was
        // a *remainder*, not a *minimum*: an auto-growing editor plus the queue
        // bar that lives inside this host could claim the pane, and the messages
        // got the residue. That is the 16 px reading, byte-for-byte, in the same
        // window whose neighbouring frame read 140 px
        // (`research/v97_verify/5556_v98/REPORT_U1B.md` §4.1) — the two tiers
        // were the same subtraction landing on either side of zero.
        //
        // The order below is the whole priority, in one place: the host may not
        // grow past `pane − floor`, so the queue bar yields first (it is the
        // first child of that host's column and already carries its own
        // `heightIn(max = queueBarCeiling(…))` plus a one-row viewport floor, so
        // a deep queue scrolls instead of growing), then the composer card
        // yields, and only what both of them decline is left over — which the
        // floored subtraction below guarantees is still the band.
        val declaredFloorPx =
            with(arrangementDensity) { timelineFloor.roundToPx() }.coerceAtLeast(0)
        // v101 P0: the floor is re-derived against the pane that **actually
        // exists at measure time**, not only against the `paneHeight` a `Dp`
        // state read a frame earlier. v100 took the latter alone, so a pane that
        // had already grown past the band by the time we measured could still be
        // floored at the stale, smaller `paneHeight` — the band would then yield
        // for a pane that could have paid it, which is the failure shape the
        // device reported at `1366x600` (`research/v97_verify/5556_v98/
        // REPORT_U1D.md` §7.3, 51 px).
        //
        // Taking the **larger** of the two can only ever tighten the band, so the
        // two windows that already pass (≥11 queued rows and `1366x768`) are
        // untouched: at a pane of 342 both sides are 64 px and the maximum is
        // 64 px. The floor still yields below the band — but now only when the
        // measured pane itself is under it, which is a **declared** boundary
        // (`SessionPaneLayout.TIMELINE_FLOOR_YIELD_PANE`) rather than an
        // accident of a stale `Dp`.
        val measuredFloorPx = SessionPaneLayout.timelineViewportFloorPx(
            panePx = constraints.maxHeight,
            floorPx = declaredFloorPx,
        )
        // v102 P0: the band every constraint below is charged with — **clamped
        // into the space this frame can actually hand out** before it is written
        // into a `Constraints`.
        //
        // 缩窗即崩（`IllegalArgumentException: maxHeight(12) must be >=
        // minHeight(64)` @ 本文件 `SessionPaneArrangement`，真机 1366x768 ->
        // 1366x600，`research/v97_verify/5556_v98/REPORT_U1E.md` §6.2）的成因是
        // **两个数字来自两个时刻**：
        //
        //  * `declaredFloorPx` 来自组合期读到的 `paneHeight` —— 一个由
        //    `onSizeChanged { paneHeightPx = it.height }` 写入的 `Dp` state，
        //    **比测量晚一帧**；
        //  * `constraints.maxHeight` 是**这一帧真实**的 pane。
        //
        // 缩窗 / 分屏 / 旋转 / IME 开关都会产生若干帧 `maxHeight` 先塌到 64 以下、
        // 而 `paneHeight` 还停在缩窗之前那一帧的帧 ⇒ `floorPx = 64` 被写进
        // `minHeight`，而同一份 constraints 的 `maxHeight` 已经是 12 ⇒
        // `Constraints.copy` 抛异常，**进程死亡**。放窗方向不崩，因为放窗的每一帧
        // `maxHeight` 都 `>=` 终值 `>= 64`，不经过这个亚 64 的窗口。
        //
        // 注意 `maxOf(declaredFloorPx, measuredFloorPx)`（v101）**救不了这一帧**：
        // `timelineViewportFloorPx` 的返回值恒 `<= floorPx`，所以该 `maxOf`
        // **可证是恒等**（恒等于 `declaredFloorPx`），它**从不**把地板调小。
        // ⇒ 唯一合法位置是**约束落地处**。
        //
        // v101 语义零回退：pane 装得下时 `safeViewportFloorPx` 是恒等的
        // （`1366x768` 的 92px 与「≥11 条 / 2–5 条」两格逐字不变），
        // 装不下时它返回的正是 `timelineViewportFloorPx` 早已声明的让位值。
        val floorPx = SessionPaneLayout.safeViewportFloorPx(
            floorPx = maxOf(declaredFloorPx, measuredFloorPx),
            availablePx = constraints.maxHeight,
        )
        // v103 P0: the host's **admissible** height — resolved in **one** place
        // (`SessionPaneLayout.composerHostHeightPx`) that `composerTopPx` below
        // also routes through.
        //
        // 改之前「宿主能有多高」在两处各求一次值（这里求 `maxHeight`、
        // `host.place(0, composerTopPx(...))` 里求 `top`），减的数不是同一个，
        // 中间没有一步要求 `host.height <= ceiling` ⇒ 「宿主比 pane 高」时
        // `coerceAtLeast(0)` 会把 top 钉死在 0，让**底沿 = host.height 越出
        // pane**（真机 1366x600 + IME 实测越 4–5px，
        // `research/v97_verify/5556_v98/REPORT_U1E.md` §5.3）。
        // 现在 measure 与 place 共用同一个数 ⇒ `top + host <= pane` 是构造性恒等式。
        //
        // **无界高分支（`!hasBoundedHeight`）为什么必须额外补一层**：
        // 那一支 `constraints.maxHeight == Infinity`，于是
        // `composerHostCeilingPx(pane = Infinity, floorPx)` 算出的是
        // `Infinity - floorPx` —— **上限形同虚设**，宿主可以长到把 pane 本身撑大
        // （下面 `pane = viewport.height + host.height`），摆位**再也收不住它**：
        // 宿主先定下 pane，pane 再去迁就宿主。
        // ⇒ 无界支补上调用方那个**真正的、由 pane 算出的**上限
        // （`composerCeiling`，`(paneHeight - 64).coerceAtLeast(min).coerceAtMost(paneHeight)`）。
        //
        // 对既有读数**逐格恒等**：有界分支**一个表达式都没换**，`floorPx >= 0` 时
        // `composerHostHeightPx` 的 `coerceAtMost(pane)` 也是空操作
        // （1366x768 的 92、1366x600 的 51、两格队列条读数全部不动）。
        val hostHeightPx = SessionPaneLayout.composerHostHeightPx(
            panePx = if (constraints.hasBoundedHeight) constraints.maxHeight else Int.MAX_VALUE,
            ceilingPx = if (constraints.hasBoundedHeight) {
                SessionPaneLayout.composerHostCeilingPx(
                    pane = constraints.maxHeight,
                    floorPx = floorPx,
                )
            } else {
                minOf(
                    SessionPaneLayout.composerHostCeilingPx(
                        pane = constraints.maxHeight,
                        floorPx = floorPx,
                    ),
                    with(arrangementDensity) { composerCeiling.roundToPx() }.coerceAtLeast(0),
                )
            },
        )
        val host = measurables[1].measure(
            constraints.copy(
                minHeight = 0,
                maxHeight = hostHeightPx,
            ),
        )
        val viewportConstraints = constraints.copy(
            // v100 P0: the floor is a **constraint**, not a hint — a weighted
            // parent and a `heightIn(min = …)` both coerce their min down to the
            // leftover they were handed, so a floor that is only written as a
            // min constraint is not a floor. Here it is the subtraction itself.
            minHeight = floorPx,
            maxHeight = SessionPaneLayout.viewportHeightPx(
                shared = shared,
                pane = constraints.maxHeight,
                composer = host.height,
                floorPx = floorPx,
            ),
        )
        val viewport = measurables[0].measure(viewportConstraints)
        val pane = if (constraints.hasBoundedHeight) {
            constraints.maxHeight
        } else {
            viewport.height + host.height
        }
        layout(constraints.maxWidth, constraints.constrainHeight(pane)) {
            viewport.place(0, 0)
            host.place(0, SessionPaneLayout.composerTopPx(pane, host.height))
        }
    }
}

/**
 * v31 BUG-1: BACK for a full-body overlay owned by this screen.
 *
 * Deliberately **not** `androidx.activity.compose.BackHandler`: that one adds its
 * callback with `OnBackPressedDispatcher.addCallback(lifecycleOwner, callback)`,
 * which only registers once `LocalLifecycleOwner` reaches `STARTED` (and drops it
 * again on `ON_STOP`). Inside a `NavHost` destination that owner is the
 * `NavBackStackEntry`, so a back handler composed while the entry is not started
 * is silently inert — the press then falls through to `super.onBackPressed()` and
 * finishes the activity, which is exactly what the device showed for the browser
 * overlay (panel stays up, repeated BACK exits the app). Registering directly on
 * the dispatcher is unconditional; the callback is still added in composition
 * order (last one wins) and removed when the overlay leaves.
 */
@Composable
private fun OverlayBackHandler(onBack: () -> Unit) {
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher ?: return
    val current = rememberUpdatedState(onBack)
    val callback = remember {
        object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                current.value()
            }
        }
    }
    DisposableEffect(dispatcher, callback) {
        dispatcher.addCallback(callback)
        onDispose { callback.remove() }
    }
}

/**
 * v31 BUG-2: the blur an outside tap is supposed to produce.
 *
 * Compose moves focus only when something *takes* it, so tapping empty space —
 * the timeline, a message body — left the header's inline title editor focused
 * and its `onFocusChanged`-driven commit never ran; on the device the only way
 * to rename from the header was the ⋮ → Rename dialog. While the editor is up
 * ([editing]), a press that nothing consumed clears focus, which is the blur the
 * editor commits on.
 *
 * Observed on the `Final` pass and never consumed: an interactive target keeps
 * its own press, and a target that does take focus (the composer's editor) blurs
 * the field by itself. Inert whenever the editor is closed.
 *
 * v32 BUG-1: this blur is one of the two a cancel has to survive. Escape closes
 * the editor too, and the *detach* of the focused field clears focus with a
 * focus-event refresh of its own (`FocusTargetNode.onDetach`), so the editor sees
 * a blur it never asked for. `InlineTitleEdit`'s latch settles the editor first,
 * so whichever blur arrives second writes nothing.
 */
private fun Modifier.blurTitleEditorOnOutsideTap(
    editing: Boolean,
    focusManager: FocusManager,
    titleEditFence: TitleEditFence,
): Modifier = pointerInput(editing) {
    // Re-entered on every `editing` flip, so neither a closed editor nor a freshly
    // opened one can start out holding an arm that was never spent.
    titleEditFence.disarm()
    if (!editing) return@pointerInput
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Final)
            if (event.type == PointerEventType.Press && event.changes.none { it.isConsumed }) {
                // v97 N3 fix2 (`research/v97_fix/TITLE_DISCARD_FIX2.md`, device B4
                // failed twice on two builds): **this** is the only blur in the
                // shell that means 「the user tapped away inside the session」, so
                // it is the only one that authorizes a save. The arm goes in
                // synchronously, immediately before the `clearFocus()` that
                // produces the blur — not on a later frame, and not after the
                // navigation that the other blurs belong to. Everything else that
                // can take this field's focus (the chrome's 首页/Home button, the
                // tab strip, the back chevron, the drawer entry — all
                // `Modifier.clickable`, and a clickable *takes* focus) arrives with
                // nothing armed, so the half-typed draft is discarded.
                titleEditFence.arm()
                focusManager.clearFocus()
            }
        }
    }
}

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
 * Export progress surface: a centered card on the overlay scrim while the
 * fetch/SAF/fallback attempt is out, with a Cancel that drops the pending
 * saver request (`cancelExport`).
 *
 * The card names the stage ([phase]) it is waiting on — the v9 run watched it
 * spin for 12–75 s with nothing but "Export session", which is also what a
 * hang looks like — and it is mounted by `exportWorking`, the flag only
 * `reportExport` clears. So it comes down exactly when the attempt has reported
 * how it ended: a picker that never answers can neither wedge the screen nor
 * trap input behind a dead modal, and no path closes it in silence.
 */
@Composable
private fun ExportWorkingOverlay(phase: ExportPhase, onCancel: () -> Unit) {
    val c = OcTheme.colors
    Box(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().background(c.overlayScrim))
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 32.dp)
                .widthIn(max = 320.dp)
                .clip(OcTheme.shapes.xl)
                .background(c.bgBase)
                .border(0.5.dp, c.borderBase, OcTheme.shapes.xl)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.command_session_export),
                color = c.textBase,
                fontSize = 14.sp,
            )
            CircularProgressIndicator(
                modifier = Modifier.padding(vertical = 12.dp).size(24.dp),
                color = c.iconAccent,
                strokeWidth = 2.dp,
            )
            Text(
                text = stringResource(
                    when (phase) {
                        ExportPhase.FETCHING -> R.string.oc_export_phase_fetch
                        ExportPhase.SAVING -> R.string.oc_export_phase_save
                    },
                ),
                color = c.textFaint,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                textAlign = TextAlign.Center,
            )
            Box(
                modifier = Modifier
                    .height(28.dp)
                    .clip(OcTheme.shapes.md)
                    .clickable(role = Role.Button, onClick = onCancel)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.oc_common_cancel),
                    color = c.textBase,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

/**
 * The width the session header reserves at its trailing edge for the floating
 * review toggle — one 40 dp touch target, and nothing on the phone form, where
 * the toggle is not rendered at all.
 *
 * Pure, so the geometry is pinned off-device: the header's cluster ends where
 * this reservation begins, which is what keeps the toggle off the 「搜索会话」
 * entry (v50 BUG-1, `research/v50_verify/tE/v50_37_search2.png`).
 */
internal fun sessionHeaderReviewSlot(desktop: Boolean): Dp =
    if (desktop) HEADER_ICON_SLOT else 0.dp

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
    // Hoisted: `semantics {}` is not a composable scope. The label rides the
    // clickable Box, not the `Icon` inside it.
    val reviewLabel = stringResource(R.string.oc_command_review_toggle)
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (active) c.layer02 else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = reviewLabel },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_oc_review),
            contentDescription = null,
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

/**
 * Stand-in for `SshTunnelHost.tunnels` when no host is published (previews,
 * isolated tests), so the `collectAsState` above stays unconditional.
 */
private val NO_SSH_TUNNELS = MutableStateFlow<List<SshTunnel>>(emptyList())

/** One header touch target (`IconButtonV2 size="large"`): the trailing-cluster unit. */
private val HEADER_ICON_SLOT = 40.dp

/** Breathing room between the last message and the floating composer. */
private val TIMELINE_BOTTOM_GAP = 12.dp

/**
 * Placement of the wide-screen terminal entry chip (M5 phase 1), the 28 dp
 * ghost `>_` that toggles the docked terminal pane.
 *
 * Moved here from `ui/App.kt` in v43 together with the chip itself: the chip
 * used to be anchored to the **session body**, so the docked review panel — the
 * row's trailing sibling — laid its 「变更 / 上下文 / 文件」tab row underneath
 * the chip, which covered the 「文件」label (`research/v43_verify/tE/
 * v43_32_panel_wide.png`, `v43_34_resize2.png`). Anchoring it to the timeline
 * pane instead makes the overlap impossible at any panel width; the values
 * themselves are unchanged, so the chip sits exactly where it did with no
 * panel open.
 *
 * [TOP_DP] clears the whole 0–80 dp header band (48 dp bar + sticky row); at
 * 52 dp the chip covered the header's ⋮ menu, which is why it moved down in
 * the first place.
 */
internal object SessionTerminalEntryPolicy {

    /** Below the 0–80 dp session header band (see the KDoc). */
    val TOP_DP: Dp = 88.dp

    /**
     * v146: inset from the timeline pane's trailing edge — **64 dp, not 8 dp**.
     *
     * The chip's touch box is its 28 dp body grown by material3's 48 dp
     * `minimumInteractiveComponentSize` (10 dp per side), anchored by
     * `padding(end = END_DP)`. At 8 dp that box ran to `paneRight + 2` and was
     * **clipped by the screen edge to 46 dp wide**, landing on the very
     * rectangle the composer's `Send` occupies in the one-row form: overlap
     * `44 × 47 = 2068 px²`, a 2 px residual sliver, and — because `Send` is
     * drawn second (`SessionScreen.kt:3187` viewport Box first, `:3188-3208`
     * composer host second) and its 28 dp body is 0.5 px from the chip's —
     * **zero visible chip pixels**.
     *
     * 64 dp is the smallest inset that separates the two boxes *by geometry*
     * rather than by painting order: the chip's touch box ends at
     * `paneRight + 12 − END_DP` = `1312`, while `Send` starts at `1316`, so
     * `overlapArea == 0` with a 4 px gap. 56 dp would leave the boxes
     * touching (overlap 0 only by equality); 60 is the exact minimum.
     *
     * This is the **horizontal** anchor only. [TOP_DP] is untouched, so the
     * 48 dp target height, the composer's vertical budget and the timeline
     * accounting (`140 / 92 / 51 / −10 / −12`) are all bit-for-bit unchanged.
     */
    val END_DP: Dp = 64.dp
}

/**
 * v74 — who holds the keyboard in this session's pane.
 *
 * The session screen owns the arbitration and provides it through
 * [LocalTerminalImeFocus]; the two surfaces that can own the IME report into it:
 *
 * - the docked terminal's view reports every focus change of the `TerminalView`
 *   that renders the active tab (`TerminalPanel`'s `TerminalSlot`). That view
 *   focus is exactly what the IME follows, so this is the ground truth the v73
 *   port was missing: [com.xiufeigo.opencodeshell.ui.terminal.TerminalFocusRequest]
 *   said *that* the keyboard should move, and nothing said whether it ever did;
 * - the composer reads it before its own mount-time focus
 *   ([com.xiufeigo.opencodeshell.ui.session.composer.Composer]'s B7 effect), which
 *   is a session-entry courtesy rather than a claim. That effect used to be what
 *   kept the keyboard on a composer the terminal had already taken it from:
 *   measured on `emulator-5556` (v74, `research/v74_verify/5556/08_term_type_notap.png`)
 *   the pane was up with its prompt and block cursor while the keyboard's Enter
 *   key still wore the composer's Send action, and every keystroke went to the
 *   composer (or, with the composer squeezed to nothing by the open pane,
 *   nowhere at all).
 *
 * A plain holder: the value is read as snapshot state, so the composer's readers
 * see the current owner without the identity of this object ever changing.
 */
class TerminalImeFocus {

    /** `true` while a terminal view holds the window's view focus. */
    var holdsKeyboard by mutableStateOf(false)
        private set

    internal fun report(terminalFocused: Boolean) {
        holdsKeyboard = terminalFocused
    }
}

/**
 * The pane's [TerminalImeFocus]. Provided by `SessionScreen`; `null` on a host
 * with no composer to arbitrate with (the standalone terminal route, the mobile
 * terminal tab — there the composer is not mounted at all).
 */
val LocalTerminalImeFocus = staticCompositionLocalOf<TerminalImeFocus?> { null }

/** `ChatView.DRAWER_WIDTH_RATIO` — 78 % of the screen … */
private const val DRAWER_WIDTH_RATIO = 0.78f

/** … capped at 320 dp (`ChatView.DRAWER_WIDTH_MAX_DP`). */
private val DRAWER_WIDTH_MAX = 320.dp

/**
 * A-3: how often the open drawer's relative labels are re-measured. The buckets
 * are minutes, so half a minute is enough to keep "just now" honest without
 * re-composing the rows on every frame.
 */
private const val DRAWER_CLOCK_TICK_MS = 30_000L
