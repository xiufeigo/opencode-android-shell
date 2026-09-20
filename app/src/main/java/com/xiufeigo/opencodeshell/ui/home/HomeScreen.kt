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
import com.xiufeigo.opencodeshell.ChatViewModel
import com.xiufeigo.opencodeshell.OpenCodeClient
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import com.xiufeigo.opencodeshell.ui.theme.ocRaisedShadow

/**
 * Home (`home.tsx`, `research/02` §1, PLAN §4.1) — the app's landing page.
 *
 * ```
 * m-8 → one card: bg-base + rounded-10 + elevation-raised
 * └─ content max-w-[1080px], centered
 *    ├─ mobile    : single column  → sessions (flex-1) + HomeUtilityNav at the bottom
 *    └─ ≥1024px   : two columns    → Projects 280dp + 32dp gap + Sessions
 * ```
 * The card is the *only* surface: `bg-deep` shows through the 8 dp margin.
 *
 * Data: the sessions list comes from [ChatViewModel.listSessions] (the drawer's
 * existing read) and is refreshed every time Home re-enters composition — i.e.
 * on cold start and after coming back from a session, which is exactly when a
 * title or `updatedAt` can have changed. Projects are derived from those
 * sessions' directories ([HomeProjects]); no project API is invented.
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
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    val cardShape = OcTheme.shapes.xl

    var sessions by remember { mutableStateOf<List<OpenCodeClient.Session>>(emptyList()) }
    var loading by remember { mutableStateOf(chatViewModel != null) }
    var selectedProject by remember { mutableStateOf<String?>(null) }
    val configMissing = if (chatViewModel == null) {
        false
    } else {
        chatViewModel.configMissing.collectAsState().value
    }

    LaunchedEffect(chatViewModel) {
        val viewModel = chatViewModel
        if (viewModel == null) {
            loading = false
            return@LaunchedEffect
        }
        // Home only needs the REST client (`GET /session`); `ensureClient` is
        // additive and deliberately does not open the SSE stream.
        viewModel.ensureClient()
        loading = true
        viewModel.listSessions { list ->
            sessions = list
            loading = false
        }
    }

    val rows = remember(sessions) { HomeSessions.rows(sessions) }
    val projects = remember(sessions) { HomeProjects.fromSessions(sessions) }

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
                                recentlyClosed = HomeProjects.RECENTLY_CLOSED,
                                selected = selectedProject,
                                onSelect = { project ->
                                    // Upstream toggles the selection and scopes
                                    // the next draft to it; scoping needs
                                    // `createSession(directory)`, which the
                                    // view model does not expose yet (M4/M5).
                                    selectedProject =
                                        if (selectedProject == project.path) null else project.path
                                },
                                onNewSession = onNewSession,
                                onOpenSettings = onOpenSettings,
                                modifier = Modifier.width(PROJECTS_WIDTH),
                            )
                            Spacer(Modifier.width(COLUMN_GAP))
                            HomeSessionsColumn(
                                rows = rows,
                                loading = loading,
                                configMissing = configMissing,
                                openSessionIds = openSessionIds,
                                onOpenSession = onOpenSession,
                                onNewSession = onNewSession,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                topInset = DESKTOP_TOP_INSET,
                            )
                        }
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            HomeSessionsColumn(
                                rows = rows,
                                loading = loading,
                                configMissing = configMissing,
                                openSessionIds = openSessionIds,
                                onOpenSession = onOpenSession,
                                onNewSession = onNewSession,
                                modifier = Modifier.weight(1f).fillMaxWidth(),
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
