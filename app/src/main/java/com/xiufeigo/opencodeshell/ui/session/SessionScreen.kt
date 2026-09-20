package com.xiufeigo.opencodeshell.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material3.DrawerValue
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.ChatStore
import com.xiufeigo.opencodeshell.ChatViewModel
import com.xiufeigo.opencodeshell.OpenCodeClient
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.session.composer.Composer
import com.xiufeigo.opencodeshell.ui.session.composer.ModelOptions
import com.xiufeigo.opencodeshell.ui.session.composer.rememberAttachmentPicker
import com.xiufeigo.opencodeshell.ui.session.drawer.SessionDrawerContent
import com.xiufeigo.opencodeshell.ui.session.header.ContextUsage
import com.xiufeigo.opencodeshell.ui.session.header.SessionHeader
import com.xiufeigo.opencodeshell.ui.session.timeline.MessageTimeline
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import kotlinx.coroutines.launch

/**
 * Session screen (M1 stage 1 → M1.5 → M2): sticky header + message timeline +
 * composer, wrapped in the session drawer.
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
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    LaunchedEffect(activeSession.id) {
        attachments = emptyList()
        toolOpen.clear()
        pendingTitle = null
        // `EventStream` bakes `?directory=` in at `start()` and a session switch
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
    LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen) chatViewModel.listSessions { sessions = it }
    }

    val state by chatViewModel.state.collectAsState()
    val currentTitle by chatViewModel.currentTitle.collectAsState()
    val modelLabel by chatViewModel.modelLabel.collectAsState()
    val configMissing by chatViewModel.configMissing.collectAsState()

    // The server title wins as soon as it lands; the optimisation only bridges
    // the `PATCH` round-trip.
    LaunchedEffect(currentTitle) {
        if (currentTitle != null && currentTitle == pendingTitle) pendingTitle = null
    }
    val title = pendingTitle ?: currentTitle ?: activeSession.title
        ?: stringResource(R.string.oc_new_session)

    val configuration = LocalConfiguration.current
    val drawerWidth = remember(configuration.screenWidthDp) {
        minOf(DRAWER_WIDTH_MAX, (configuration.screenWidthDp * DRAWER_WIDTH_RATIO).dp)
    }

    var composerHeight by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(drawerWidth),
                // The View shell's drawer is a square `bg-base` panel with a 1 px
                // end border (`bg_session_drawer.xml`); the M3 default rounds the
                // end edge and tints it with the Material surface, which fights
                // the OC2 tokens. Square + the sheet's own elevation it is.
                drawerShape = RectangleShape,
                drawerContainerColor = c.bgBase,
                drawerContentColor = c.textBase,
            ) {
                SessionDrawerContent(
                    sessions = sessions,
                    currentSessionId = activeSession.id,
                    // M6: the working session's row carries the 25-dot marquee
                    // (`session-progress-indicator-v2`).
                    runningSessionId = activeSession.id
                        .takeIf { state.runStatus == ChatStore.RunStatus.RUNNING },
                    onNewSession = {
                        scope.launch { drawerState.close() }
                        chatViewModel.createSession { newId ->
                            // `createSession` already called `observeSession` with
                            // the server's directory; the screen inherits the
                            // current directory so the rebuilt stream stays
                            // scoped until the list backfill confirms it.
                            activeSession = ActiveSession(
                                id = newId,
                                title = null,
                                directory = activeSession.directory,
                            )
                        }
                    },
                    onSelect = { session ->
                        scope.launch { drawerState.close() }
                        if (session.id != activeSession.id) {
                            activeSession = ActiveSession(
                                id = session.id,
                                title = session.title,
                                directory = session.directory,
                            )
                        }
                    },
                )
            }
        },
    ) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(c.bgBase)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                MessageTimeline(
                    messages = state.messages,
                    runStatus = state.runStatus,
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 16.dp,
                        bottom = composerHeight + TIMELINE_BOTTOM_GAP,
                    ),
                    stickyHeader = {
                        SessionHeader(
                            title = title,
                            onRename = { newTitle ->
                                pendingTitle = newTitle
                                chatViewModel.renameSession(activeSession.id, newTitle) { ok ->
                                    if (!ok) pendingTitle = null
                                }
                            },
                            onBack = onBack,
                            onOpenDrawer = { scope.launch { drawerState.open() } },
                            // Context limit needs the provider/model catalog
                            // (`GET /config/providers`), which is M4 scope: the
                            // total is known, the denominator is not, so the ring
                            // renders the documented "no usage yet" empty track.
                            contextProgress = ContextUsage.progressOf(state.messages, limit = null),
                            // The ⋮ menu is a placeholder in M2: *Rename* is handled
                            // inside the header (inline input), while Delete /
                            // Share / Export / Archive need the session-list
                            // hand-off (M3) and server-side share/export (M5).
                            onMenuAction = { },
                        )
                    },
                    toolOpen = toolOpen,
                    onToggleTool = { key, open -> toolOpen[key] = !open },
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
                    Composer(
                        runStatus = state.runStatus,
                        modelLabel = modelLabel,
                        attachments = attachments,
                        modelOptions = remember(modelLabel) { ModelOptions.fromLabel(modelLabel) },
                        onAttachClick = attachmentPicker::pick,
                        onRemoveAttachment = { attachment -> attachments = attachments - attachment },
                        onSelectModel = {
                            // Single-entry menu today: the configured model is
                            // already active, and the provider/model catalog is
                            // a later milestone.
                        },
                        onSearchFiles = { query, onResult -> chatViewModel.searchFiles(query, onResult) },
                        onSend = { text, files ->
                            chatViewModel.send(text, files)
                            attachments = emptyList()
                        },
                        onStop = { chatViewModel.abort() },
                    )
                }
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

/** Breathing room between the last message and the floating composer. */
private val TIMELINE_BOTTOM_GAP = 12.dp

/** `ChatView.DRAWER_WIDTH_RATIO` — 78 % of the screen … */
private const val DRAWER_WIDTH_RATIO = 0.78f

/** … capped at 320 dp (`ChatView.DRAWER_WIDTH_MAX_DP`). */
private val DRAWER_WIDTH_MAX = 320.dp
