package com.xiufeigo.opencodeshell.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.xiufeigo.opencodeshell.ChatViewModel
import com.xiufeigo.opencodeshell.OpenCodeClient
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ServerConfigStore
import com.xiufeigo.opencodeshell.SettingsStore
import com.xiufeigo.opencodeshell.data.HomeProjectStore
import com.xiufeigo.opencodeshell.notify.AppNotification
import com.xiufeigo.opencodeshell.v2.ServerGeneration
import com.xiufeigo.opencodeshell.notify.AppNotificationKind
import com.xiufeigo.opencodeshell.ui.nav.BottomTabStrip
import com.xiufeigo.opencodeshell.ui.nav.DesktopTabStrip
import com.xiufeigo.opencodeshell.ui.nav.DraftTarget
import com.xiufeigo.opencodeshell.ui.nav.HomeToggle
import com.xiufeigo.opencodeshell.ui.nav.NavMemory
import com.xiufeigo.opencodeshell.ui.nav.NavRestore
import com.xiufeigo.opencodeshell.ui.nav.SessionTabRef
import com.xiufeigo.opencodeshell.ui.nav.SessionTabs
import com.xiufeigo.opencodeshell.ui.nav.TabCloseTarget
import com.xiufeigo.opencodeshell.ui.home.DirectoryPickerDialog
import com.xiufeigo.opencodeshell.ui.home.HomeRowActions
import com.xiufeigo.opencodeshell.ui.home.HomeScreen
import com.xiufeigo.opencodeshell.ui.home.HomeSearchFocusRequest
import com.xiufeigo.opencodeshell.ui.home.NewSessionScreen
import com.xiufeigo.opencodeshell.ui.overlay.DialogController
import com.xiufeigo.opencodeshell.ui.overlay.DialogHost
import com.xiufeigo.opencodeshell.ui.overlay.DialogSize
import com.xiufeigo.opencodeshell.ui.overlay.LocalDialogActive
import com.xiufeigo.opencodeshell.ui.overlay.LocalDialogController
import com.xiufeigo.opencodeshell.ui.overlay.requireDialogController
import com.xiufeigo.opencodeshell.ui.palette.CommandPalette
import com.xiufeigo.opencodeshell.ui.palette.PaletteEntry
import com.xiufeigo.opencodeshell.ui.palette.PaletteEntryType
import com.xiufeigo.opencodeshell.ui.palette.PaletteHostKind
import com.xiufeigo.opencodeshell.ui.palette.LocalSessionCommandIntents
import com.xiufeigo.opencodeshell.ui.palette.SessionCommandIntents
import com.xiufeigo.opencodeshell.ui.palette.SessionCommands
import com.xiufeigo.opencodeshell.ui.palette.appCommands
import com.xiufeigo.opencodeshell.ui.palette.filePaletteEntries
import com.xiufeigo.opencodeshell.ui.palette.looksLikeSessionID
import com.xiufeigo.opencodeshell.ui.palette.sessionSearchEntries
import com.xiufeigo.opencodeshell.ui.palette.sources
import com.xiufeigo.opencodeshell.ui.palette.steppedSessionId
import com.xiufeigo.opencodeshell.ui.palette.stringResolvedEntries
import com.xiufeigo.opencodeshell.ui.servers.ConnectServerScreen
import com.xiufeigo.opencodeshell.ui.servers.requiresConnectScreen
import com.xiufeigo.opencodeshell.ui.session.SessionScreen
import com.xiufeigo.opencodeshell.ui.session.actions.ForkMessage
import com.xiufeigo.opencodeshell.ui.session.actions.LocalSessionActionsHost
import com.xiufeigo.opencodeshell.ui.session.actions.SessionActionsHost
import com.xiufeigo.opencodeshell.ui.session.actions.SessionActionsOverlay
import com.xiufeigo.opencodeshell.ui.session.export.SessionExport
import com.xiufeigo.opencodeshell.ui.session.drawer.MobileForm
import com.xiufeigo.opencodeshell.ui.session.drawer.SessionTimeLabel
import com.xiufeigo.opencodeshell.ui.session.timeline.TimelineSearch
import com.xiufeigo.opencodeshell.ui.settings.LocalTimelineDisplayPrefs
import com.xiufeigo.opencodeshell.ui.settings.SHORTCUT_ROWS
import com.xiufeigo.opencodeshell.ui.settings.SettingsDialogContent
import com.xiufeigo.opencodeshell.ui.settings.SettingsPanelExtras
import com.xiufeigo.opencodeshell.ui.settings.SettingsServerBridge
import com.xiufeigo.opencodeshell.ui.settings.keybindSignatures
import com.xiufeigo.opencodeshell.ui.settings.timelineDisplayPrefs
import com.xiufeigo.opencodeshell.ui.terminal.TerminalEntryButton
import com.xiufeigo.opencodeshell.ui.terminal.TerminalPanelHost
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import com.xiufeigo.opencodeshell.ui.theme.ProvideOcReducedMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Native Compose root — the MemoryRouter equivalent of the opencode web app.
 *
 * **Settings is a route in upstream v2, not a dialog.** `/settings` is a real
 * route (`shell/routes/routes.tsx:38` → `settings/shell.tsx:70`) that renders a
 * full-screen surface (`.settings-screen`, `settings.css:8-27`); only
 * *manage/select model* use `Dialog variant="settings"`
 * (`providers/models/manage.tsx:62`). The earlier note claiming upstream hosts
 * settings in the global dialog stack was wrong (`audit_routes.md` 表 A).
 *
 * What *is* upstream behaviour is the single **global dialog stack**
 * (`packages/ui/src/context/dialog.tsx:159`), which hosts the command palette and
 * the server dialogs. So the native shell keeps [Route.SETTINGS] as a deep-link
 * entry ("route in, surface out") and renders the surface through the shared
 * dialog host — the same layer every other overlay uses. `DialogController.show`
 * therefore has upstream's `dialog.show` semantics: **it clears the stack and
 * mounts the new layer** (`dialog.tsx:140-149`); `push` (Android: a second
 * `show` after `closeAll`) is the stacking variant.
 *
 * [isDark] defaults to the **live** system setting resolved through
 * [SettingsStore] (`opencode-color-scheme`: `system|light|dark`), matching
 * `oc-theme-preload.js`. Without a store the system value is used directly, so
 * previews keep working.
 *
 * The dialog controller is obtained **inside** [DialogHost] (see [AppScaffold]),
 * so the settings/palette openers are plain functions that take the controller
 * instead of reading a CompositionLocal from a non-composable context — that is
 * what `onPreviewKeyEvent` and `LaunchedEffect` require.
 *
 * **W5-E1:** with a [ServerConfigStore] that holds no usable server, the whole
 * screen is `ui/servers/ConnectServerScreen` instead of the navigation tree
 * (`shell/routes/routes.tsx:79`). The inline `oc_setup_hint` banners inside Home
 * / Session / NewSession stay untouched: they describe the *other* state — a
 * server exists but `ChatViewModel` could not reach it
 * (`ChatViewModel.configMissing`) — and that must not be silently swallowed by
 * this gate.
 */
object Route {
    const val HOME = "home"
    const val SESSION = "server/{serverKey}/session/{id}"
    const val SETTINGS = "settings"

    /**
     * The new-session draft (`_upstream/packages/app/src/new-session`). The
     * optional `directory` query pre-picks the project (Home row ✎ and the
     * add-project browser both navigate with it).
     */
    const val NEW_SESSION = "new-session?directory={directory}"

    /**
     * M5 phase 1 — the terminal panel. Upstream stacks it as a bottom panel of
     * the session page (`research/02` §9.3); the phone shell hosts it as its own
     * route so the PTY data link can be exercised before the review side panel
     * (the other half of M5) lands. `directory` is the optional workspace the PTY
     * should be scoped to (`?directory=` on the v1 API).
     */
    const val TERMINAL = "terminal?directory={directory}"

    /** Argument name of the session id inside [SESSION]. */
    const val ARG_ID = "id"

    /** Optional workspace directory of [TERMINAL]. */
    const val ARG_DIRECTORY = "directory"

    /** [TERMINAL] route, omitting the query when there is no directory. */
    fun terminal(directory: String? = null): String =
        if (directory.isNullOrBlank()) "terminal" else "terminal?directory=" + Uri.encode(directory)

    /** [NEW_SESSION] route with an optional pre-picked project directory. */
    fun newSession(directory: String? = null): String =
        if (directory.isNullOrBlank()) "new-session" else "new-session?directory=" + Uri.encode(directory)
}

@Composable
fun OcApp(
    isDark: Boolean = isSystemInDarkTheme(),
    chatViewModel: ChatViewModel? = null,
    settingsStore: SettingsStore? = null,
    serverStore: ServerConfigStore? = null,
    /** Server segment of the session route: `NavRestore.serverKeyOf(server URL)`. */
    serverKey: String = NavRestore.DEFAULT_SERVER_KEY,
    /** Last-page persistence (`LastRouteStore`); null keeps nav state in memory. */
    lastRoute: NavMemory? = null,
    /** `appearance.tabLayout == "vertical"` — the optional bottom title bar (§9b). */
    bottomTabStrip: Boolean = false,
) {
    val context = LocalContext.current
    val store = remember(settingsStore, context) { settingsStore ?: SettingsStore(context) }
    val settings by store.state.collectAsStateWithLifecycle()
    val dark = settings.isDark(isDark)
    // The client-local opened-project store (`home/model.ts:20-28`,
    // `runtime/server/registry.tsx:34-99`). One record per server, keyed exactly
    // like the session routes (`serverKey`), so switching servers switches the
    // 项目 list instead of mixing two servers' projects.
    val homeProjects = remember(context, serverKey) { HomeProjectStore(context, serverKey) }

    OcTheme(isDark = dark) {
        // M6: the system animation scale (`prefers-reduced-motion`) is resolved
        // once, here, and published to every motion component below.
        ProvideOcReducedMotion {
            // W4-D: the stored timeline display switches are published once for
            // the whole tree, so `MessageTimeline` / `PartRenderer` can read them
            // without `SessionScreen` threading three more parameters through.
            // The command-intent channel lives here too: the palette (a dialog
            // layer) and the session screen (inside the nav tree) are siblings
            // under [DialogHost], so neither can reach the other's state directly.
            val intents = remember { SessionCommandIntents() }
            // W5-E1: the first-run gate. Upstream never renders the shell without
            // a server — `AppLayout` falls back to `ConnectServerScreen` while
            // `servers.list.length === 0` (`shell/routes/routes.tsx:79`). The
            // native equivalent is the same predicate over the store that
            // `OpenCodeClient`/`PtyApi` read, re-evaluated on the store's
            // revision so the write performed *by* the connect page swaps this
            // page for the shell without a restart.
            //
            // A null store (previews, tests, callers that never configured one)
            // means "no gate": the shell keeps rendering exactly as before.
            val serverRevisionFlow = remember(serverStore) {
                serverStore?.revision ?: MutableStateFlow(0)
            }
            val serverRevision by serverRevisionFlow.collectAsStateWithLifecycle()
            val connectRequired = remember(serverStore, serverRevision) {
                serverStore != null && requiresConnectScreen(serverStore.listServers())
            }
            CompositionLocalProvider(
                LocalTimelineDisplayPrefs provides settings.timelineDisplayPrefs(),
                LocalSessionCommandIntents provides intents,
            ) {
                // The layer host owns the stack; [AppScaffold] lives *inside* it so the
                // global keybind hook and every route share one controller.
                Box(Modifier.fillMaxSize()) {
                    DialogHost {
                        if (connectRequired && serverStore != null) {
                            ConnectServerScreen(store = serverStore)
                        } else {
                            AppScaffold(
                                chatViewModel = chatViewModel,
                                store = store,
                                serverStore = serverStore,
                                serverKey = serverKey,
                                lastRoute = lastRoute,
                                bottomTabStrip = bottomTabStrip,
                                intents = intents,
                                homeProjects = homeProjects,
                            )
                        }
                    }
                    // W5-E §5.5: the in-app notification banner. Deliberately a
                    // sibling of the dialog host — a dialog's scrim never clips it —
                    // and a child of `OcTheme`, so it paints with the live tokens.
                    NotificationBanner(
                        chatViewModel = chatViewModel,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

/** The navigation tree + global keybind focus target, hosted inside [DialogHost]. */
@Composable
private fun AppScaffold(
    chatViewModel: ChatViewModel?,
    store: SettingsStore,
    serverStore: ServerConfigStore?,
    serverKey: String,
    lastRoute: NavMemory?,
    bottomTabStrip: Boolean,
    intents: SessionCommandIntents,
    /** Client-local opened-project record for [serverKey] (`oc_home_projects`). */
    homeProjects: HomeProjectStore,
) {
    val navController = rememberNavController()
    val dialog = requireDialogController()
    // Live settings: the terminal font is read here (its only consumer) and the
    // `showFileTree` flag decides whether `fileTree.toggle` is registered.
    val settings by store.state.collectAsStateWithLifecycle()

    // A single opener shared by the Home gear, the global ⌘, chord and the
    // palette's `settings.open` row. It carries the catalog flows so the
    // Providers / Models tabs render real data.
    val onOpenSettings: () -> Unit = { showSettingsDialog(dialog, store, serverStore, chatViewModel) }

    // WAVE 2.3b I-1/I-5: the activity context for external intents, the Help
    // target (drawer Help row → desktop-feedback, upstream `titlebar.tsx`), and
    // the hoisted Home search handle App.kt's `mod+f` branch focuses.
    val appContext = LocalContext.current
    val onOpenHelp: () -> Unit = {
        runCatching {
            appContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(FEEDBACK_URL)))
        }
    }
    val homeSearchFocusRequest = remember { HomeSearchFocusRequest() }

    // ---- M3: last-page restore + the optional bottom title bar -------------
    // The app-level tab store (`context/tabs.tsx`, §4.7) is not in M3 scope.
    // What this shell can state truthfully is which sessions it has opened in
    // this run — that drives the Home row's "open as tab" marker (§1.3) and the
    // optional bottom strip (§9b).
    val openSessions = remember { mutableStateListOf<SessionTabRef>() }
    var currentSession by remember { mutableStateOf<String?>(null) }
    // The route on top — the palette host kind (Home has no file category) and
    // `terminal.toggle` both need it, and the nav listener is the only place that
    // knows it.
    var currentRoute by remember { mutableStateOf(Route.HOME) }
    val titleFlow = chatViewModel?.currentTitle
    val currentSessionTitle =
        if (titleFlow == null) null else titleFlow.collectAsStateWithLifecycle().value

    // W7: the desktop tab bar shows one label per open tab, and the only *live*
    // title the shell has is the tracked session's (`ChatViewModel.currentTitle`,
    // fed by `observeSession` and by the rename/event paths). Record it on the
    // entry that owns it, so a tab keeps its last known name after the user
    // switches away, and a rename lands on the tab that is on screen. This is
    // the same single store the bottom strip renders — no second data flow.
    //
    // The `sessionID` guard is what makes it safe: `currentTitle` can still hold
    // the *previous* session's title for the compositions between the route
    // change and `SessionScreen`'s `observeSession`, and that title must not be
    // written onto the newly opened tab. A null/blank title is never recorded
    // over one we already have.
    LaunchedEffect(chatViewModel, currentSession, currentSessionTitle) {
        val id = currentSession
        val title = currentSessionTitle
        if (id == null || title.isNullOrBlank() || chatViewModel?.sessionID != id) return@LaunchedEffect
        val index = openSessions.indexOfFirst { it.id == id }
        if (index >= 0 && openSessions[index].title != title) {
            openSessions[index] = openSessions[index].copy(title = title)
        }
    }

    // "Restore the last page" (PLAN §3, `DesktopMemoryRouter`): Home stays the
    // start destination and the stored page is pushed once on top, so Back
    // always has Home underneath. `null` (first run / page gone) → stay Home.
    val restoreTarget = remember(lastRoute) { NavRestore.target(lastRoute?.load(), serverKey) }
    LaunchedEffect(restoreTarget) {
        if (restoreTarget != null && restoreTarget != Route.HOME) {
            navController.navigate(restoreTarget) { launchSingleTop = true }
        }
    }
    // Every destination is persisted as it is reached. Transient pages
    // (new-session / settings / terminal) map to null and leave the stored value
    // untouched, so a cold start lands on the page *before* them.
    DisposableEffect(navController, lastRoute) {
        val listener = object : NavController.OnDestinationChangedListener {
            override fun onDestinationChanged(
                controller: NavController,
                destination: NavDestination,
                arguments: Bundle?,
            ) {
                val route = destination.route
                val sessionId = arguments?.getString(Route.ARG_ID)
                currentSession = if (route == Route.SESSION) sessionId else null
                currentRoute = route.orEmpty()
                if (route == Route.SESSION && !sessionId.isNullOrEmpty() &&
                    openSessions.none { it.id == sessionId }
                ) {
                    openSessions.add(SessionTabRef(sessionId, currentSessionTitle))
                }
                NavRestore.toStore(route, sessionId)
                    ?.let { lastRoute?.save(it.route, it.sessionId) }
            }
        }
        navController.addOnDestinationChangedListener(listener)
        onDispose { navController.removeOnDestinationChangedListener(listener) }
    }
    // `isDesktop()` upstream is a width test (`session/model.ts:35`): wide screens
    // dock the terminal as a bottom panel of the session body
    // (`session/screen.tsx:507-540`), narrow ones get the mobile four-tab view
    // instead, where the terminal is one of the tabs.
    val wideScreen = LocalConfiguration.current.screenWidthDp >= SESSION_CHIP_MIN_WIDTH_DP
    // W7: the **desktop** session tab bar (`research/02` §9b.1, `titlebar.tsx:441`).
    // `appearance.tabLayout == "vertical"` only ever moves the bar to the bottom
    // on the mobile form (`titlebar.tsx:64-65`: `bottom = mobile() && …`), so the
    // top strip is the desktop placement and the stored bottom bar stays below
    // the breakpoint. The width test is the shared `MobileForm.isDesktop`
    // (`session/model.ts:35`, 768 dp) used by the session screen and the drawer,
    // not a second copy of the number.
    val desktopTabs = MobileForm.isDesktop(LocalConfiguration.current.screenWidthDp)
    // The docked/closed state belongs to the *session* and not to the route
    // stack, so it is hoisted here: the floating chip, the palette's
    // `terminal.toggle` row and the `Ctrl+`` chord now drive one flag instead of
    // two behaviours for the same command (the chord used to push the
    // full-screen route while the chip docked the pane). Keyed by session id, so
    // entering another session starts closed — same as upstream's per-session
    // terminal state.
    var terminalDocked by rememberSaveable(currentSession) { mutableStateOf(false) }
    // The terminal panel is reachable from three places, all owned here: the
    // palette's `terminal.toggle` row, the `Ctrl+`` chord and a tap target on
    // the session screen. Upstream's toggle closes the panel when it is open
    // (`use-session-commands.tsx:354-368`).
    val onTerminalToggle: () -> Unit = {
        when {
            // Wide: the pane is a full-width bottom panel of the session body
            // (`session/screen.tsx:507-540`), so the toggle opens/closes it in
            // place instead of pushing a page.
            currentRoute == Route.SESSION && wideScreen -> terminalDocked = !terminalDocked
            currentRoute == Route.TERMINAL -> navController.popBackStack()
            else -> navController.navigate(Route.terminal())
        }
    }
    val onNewSession: () -> Unit = {
        // WAVE 2.3b I-3 (`SessionTabs.draftTarget`, `titlebar.tsx:271-321`): `+`
        // on a session inherits that session's (effective) model and opens a
        // plain draft; anywhere else the draft is scoped to selected > recent >
        // first project. The per-session composer-model handoff needs a composer
        // registry that does not exist yet — `ChatViewModel.selectedModel` is
        // already global, so the inheritance is implicit in the plain draft.
        when (val target = SessionTabs.draftTarget(
            onSessionRoute = currentRoute == Route.SESSION,
            activeSessionId = currentSession,
            // The Home selection is not hoisted to this scaffold and there is no
            // project catalogue read here — both fall through to the MRU record.
            selectedDirectory = null,
            projectDirectories = emptyList(),
            mostRecentDirectory = homeProjects.last(),
        )) {
            is DraftTarget.InheritSession ->
                navController.navigate(Route.newSession()) { launchSingleTop = true }
            is DraftTarget.InheritProject ->
                navController.navigate(Route.newSession(target.directory)) { launchSingleTop = true }
            DraftTarget.DefaultDirectory ->
                navController.navigate(Route.newSession()) { launchSingleTop = true }
        }
    }
    // `session.previous` / `session.next`: step through the palette's own
    // `updatedAt`-descending session list, replacing the current session entry
    // so Back still lands on Home.
    val onStepSession: (Int) -> Unit = { delta ->
        chatViewModel?.listSessions { sessions ->
            steppedSessionId(sessions, currentSession, delta)?.let { target ->
                navController.navigate(NavRestore.session(serverKey, target)) {
                    popUpTo(Route.HOME)
                    launchSingleTop = true
                }
            }
        }
    }
    // `workspace.new` («New worktree», `palette.ts:40`): Android has no worktree
    // store, so the closest real action is "pick a directory, then draft a
    // session in it" — the same flow as Home's 「添加项目」.
    val onNewWorkspace: () -> Unit = {
        val viewModel = chatViewModel
        if (viewModel == null) {
            onNewSession()
        } else {
            dialog.show(DialogSize.FIT) { controller ->
                DirectoryPickerDialog(
                    chatViewModel = viewModel,
                    initialPath = null,
                    onSelect = { directory ->
                        controller.close()
                        navController.navigate(Route.newSession(directory)) { launchSingleTop = true }
                    },
                    onClose = { controller.close() },
                )
            }
        }
    }
    val openSession: (String) -> Unit = { id ->
        navController.navigate(NavRestore.session(serverKey, id)) { launchSingleTop = true }
    }
    // WAVE 2.3b I-4 (`SessionTabs.toggleHomeTarget`, `tabs.tsx:485-497`):
    // pressing Home while already on Home jumps back to the most recent tab
    // instead of being a no-op. `openSessions.lastOrNull()` is the run-scoped
    // recent-tab approximation (no persisted `recent.key` exists).
    val onHome: () -> Unit = {
        when (val target = SessionTabs.toggleHomeTarget(
            onHomeRoute = currentRoute == Route.HOME,
            mostRecentTabId = openSessions.lastOrNull()?.id,
        )) {
            is HomeToggle.BackToTab -> openSession(target.tabId)
            HomeToggle.GoHome -> {
                currentSession = null
                navController.navigate(Route.HOME) { launchSingleTop = true }
            }
        }
    }
    /** `palette.ts:66-80`: open the file, then make sure a session shows it. */
    val onOpenFile: (String) -> Unit = { path ->
        intents.openFile(path)
        // A file opens *inside* a session's review panel; from Home (or the
        // terminal) fall back to the most recently updated session, not only to
        // one opened in this run. Without the server fallback a file picked on a
        // cold Home screen was dropped on the floor (verifier F-3: the request
        // stayed unconsumed because no session screen ever composed).
        if (currentRoute != Route.SESSION) {
            val open = openSessions.lastOrNull()?.id
            if (open != null) {
                openSession(open)
            } else {
                chatViewModel?.listSessions { list ->
                    list.maxByOrNull { it.updatedAt }?.let { openSession(it.id) }
                }
            }
        }
    }
    // The palette host: Home renders commands + sessions only, the session host
    // adds `/find/file` results (`home/sessions/command-palette.tsx` vs
    // `shell/commands/dialog.tsx:41-49`).
    val paletteHost: () -> PaletteHostKind = {
        if (currentRoute == Route.HOME) PaletteHostKind.HOME else PaletteHostKind.SESSION
    }
    // `file.open` re-opens the palette in files-only mode, so the action table and
    // the opener reference each other; the mutable holder breaks that cycle
    // without capturing a stale `currentRoute`.
    var openPalette: (PaletteHostKind) -> Unit = {}
    // A1: `session.summary.toggle`. The popover's open state belongs to
    // `SessionScreen`, so the command (palette row, `Ctrl+Shift+Y`, header button
    // on the phone) increments a revision this scaffold hands to the session
    // route. Only bumped while a session is on screen: a bump from Home is a
    // no-op, and a stale bump would auto-open the popover on the next entry.
    var summaryRevision by remember { mutableStateOf(0) }
    val onToggleSummary: () -> Unit = {
        if (currentRoute == Route.SESSION) summaryRevision += 1
    }
    val actions = ShellActions(
        onOpenSettings = onOpenSettings,
        onNewSession = onNewSession,
        onNewWorkspace = onNewWorkspace,
        onStepSession = onStepSession,
        onTerminalToggle = onTerminalToggle,
        onToggleReview = { intents.toggleReview() },
        onToggleSummary = onToggleSummary,
        onToggleFileTree = { intents.openFileTree() },
        // `file.open` (`use-session-commands.tsx:163-168`): the same palette with
        // `filesOnly()` — only the Files group survives.
        onOpenFilePicker = { openPalette(PaletteHostKind.FILES_ONLY) },
    )
    openPalette = { kind ->
        showPaletteDialog(
            dialog = dialog,
            kind = kind,
            store = store,
            serverStore = serverStore,
            chatViewModel = chatViewModel,
            serverKey = serverKey,
            actions = actions,
            intents = intents,
            onOpenSession = openSession,
            onOpenFile = onOpenFile,
        )
    }
    // WAVE 2.3b I-2: the Home session-row context-menu host (`HomeRowActions`).
    // Null without a view model (previews) so the menu items stay disabled.
    // The export client follows SessionScreen's A8 pattern
    // (`OpenCodeClient.forServer` over the active server entry).
    val serverRevisionFlow = remember(serverStore) { serverStore?.revision ?: MutableStateFlow(0) }
    val serverRevision by serverRevisionFlow.collectAsStateWithLifecycle()
    val exportClient = remember(serverStore, serverRevision) {
        serverStore?.server?.let { OpenCodeClient.forServer(it) }
    }
    val homeRowActions: HomeRowActions? = remember(chatViewModel, exportClient) {
        val viewModel = chatViewModel ?: return@remember null
        HomeRowActions(
            rename = { id, title, cb -> viewModel.renameSession(id, title, cb) },
            export = { row ->
                runCatching {
                    val client = exportClient ?: return@runCatching null
                    SessionExport.Frame(
                        client.sessionJson(row.id, row.directory),
                        client.messagesJson(row.id, row.directory),
                    )
                }.getOrNull()
            },
            delete = { row, cb ->
                viewModel.deleteSession(row.id, row.directory) { ok ->
                    if (ok) {
                        openSessions.removeAll { it.id == row.id }
                        if (currentSession == row.id) navController.popBackStack()
                    }
                    cb(ok)
                }
            },
        )
    }
    // Tabs track the server's session set: a session deleted through the header
    // ⋮ flow (or by another client) must drop its tab, not linger until ×.
    // `sessionsRevision` bumps on every session created/deleted/renamed frame.
    LaunchedEffect(chatViewModel) {
        val viewModel = chatViewModel ?: return@LaunchedEffect
        viewModel.sessionsRevision.collect {
            if (openSessions.isEmpty()) return@collect
            viewModel.listSessions { list ->
                openSessions.removeAll { tab -> list.none { it.id == tab.id } }
            }
        }
    }

    GlobalKeybindFocus(
        onSettings = onOpenSettings,
        onTerminalToggle = onTerminalToggle,
        onNewSession = onNewSession,
        onToggleSummary = onToggleSummary,
        onPalette = openPalette,
        paletteHost = paletteHost,
        // WAVE 2.3b I-5 (`mod+f`, `search.ts:65-73`): on Home the chord focuses
        // the sessions search field; anywhere else it opens timeline search.
        onHomeSearch = {
            if (currentRoute == Route.HOME) homeSearchFocusRequest.focus()
            else TimelineSearch.open()
        },
        // W5-S (D4): the recorded override table is what the chords are matched
        // against now — defaults stay in `SHORTCUT_ROWS`, user values win.
        keybinds = settings.keybinds,
    ) {
        // M3: the optional bottom title bar (§9b) has to sit *under* the
        // navigation content, so the graph is wrapped in a column. W7 adds the
        // desktop placement at the *top* of the same column.
        Column(Modifier.fillMaxSize().background(OcTheme.colors.bgDeep)) {
            // W7 §9b.1: upstream renders `TitlebarTabStrip` first in the shell on
            // every route — Home included, where the Home button is the pressed
            // item and no tab is active (`titlebar.tsx:344,441-458`). It closes
            // through `SessionTabs`, the same decisions the bottom strip uses.
            if (desktopTabs) {
                DesktopTabStrip(
                    sessions = openSessions,
                    activeSessionId = currentSession,
                    onHome = { onHome() },
                    onOpenSession = { tab -> openSession(tab.id) },
                    onCloseSession = { tab ->
                        // `removeTab` (`tabs.tsx:180-203`): the neighbouring tab
                        // takes over when the closed one was on screen — right
                        // first, then left, then Home.
                        val closed = SessionTabs.close(openSessions.toList(), tab.id, currentSession)
                        openSessions.clear()
                        openSessions.addAll(closed.tabs)
                        when (val target = closed.target) {
                            TabCloseTarget.Stay -> Unit
                            TabCloseTarget.Home -> {
                                currentSession = null
                                navController.navigate(Route.HOME) { launchSingleTop = true }
                            }
                            is TabCloseTarget.Open -> openSession(target.id)
                        }
                    },
                    onNewSession = onNewSession,
                )
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    // The strip above already paints behind the status bar, so the
                    // destinations — which all apply `WindowInsets.safeDrawing`
                    // themselves (`HomeScreen.kt:144`, `SessionScreen.kt:803`) —
                    // must not pad for that same inset a second time.
                    .then(
                        if (desktopTabs) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier,
                    ),
            ) {
                NavHost(navController = navController, startDestination = Route.HOME) {
                    composable(Route.HOME) {
                        HomeScreen(
                            chatViewModel = chatViewModel,
                            openSessionIds = openSessions.map { it.id }.toSet(),
                            onOpenSession = { id ->
                                navController.navigate(NavRestore.session(serverKey, id))
                            },
                            onNewSession = { navController.navigate(Route.newSession()) },
                            onNewSessionInDirectory = { directory ->
                                navController.navigate(Route.newSession(directory))
                            },
                            onOpenSettings = onOpenSettings,
                            homeProjectStore = homeProjects,
                            searchFocusRequest = homeSearchFocusRequest,
                            rowActions = homeRowActions,
                        )
                    }
                    composable(Route.SETTINGS) {
                        // Deep-link entry point: open the dialog, then leave the
                        // route so Home stays the only real destination (M4).
                        LaunchedEffect(Unit) {
                            onOpenSettings()
                            navController.popBackStack()
                        }
                    }
                    composable(
                        route = Route.NEW_SESSION,
                        arguments = listOf(
                            navArgument(Route.ARG_DIRECTORY) {
                                type = NavType.StringType
                                nullable = true
                                defaultValue = null
                            },
                        ),
                    ) { entry ->
                        val viewModel = chatViewModel
                        if (viewModel == null) {
                            Box(Modifier.fillMaxSize().background(OcTheme.colors.bgDeep))
                        } else {
                            NewSessionScreen(
                                chatViewModel = viewModel,
                                preselectedDirectory = entry.arguments?.getString(Route.ARG_DIRECTORY),
                                onBack = { navController.popBackStack() },
                                onCreated = { id ->
                                    navController.navigate(NavRestore.session(serverKey, id)) {
                                        // The draft entry is replaced, so Back
                                        // from the new session lands on Home.
                                        popUpTo(Route.NEW_SESSION) { inclusive = true }
                                        launchSingleTop = true
                                    }
                                },
                            )
                        }
                    }
                    composable(Route.SESSION) { entry ->
                        val id = entry.arguments?.getString(Route.ARG_ID).orEmpty()
                        val viewModel = chatViewModel
                        val context = LocalContext.current
                        if (viewModel == null || id.isEmpty()) {
                            SessionPlaceholder()
                        } else {
                            // W6: the ⋮ menu's session actions. The host is built
                            // here because this is the only place that holds both
                            // the view model and the route helpers, and published
                            // through `LocalSessionActionsHost` for the header —
                            // `SessionScreen` (owned elsewhere) calls
                            // `SessionHeader` with a fixed argument list, so a new
                            // parameter could never reach it.
                            val actionsHost = remember(viewModel, serverKey) {
                                SessionActionsHost(
                                    context = context,
                                    viewModel = viewModel,
                                    // 归档 leaves the screen: the session is
                                    // filtered out of Home's list client-side
                                    // (`HomeSessions.isArchived`), so staying on
                                    // it would show a session the user just
                                    // removed from the list.
                                    onLeaveSession = { navController.popBackStack() },
                                )
                            }
                            // A surface left open must not follow the user into
                            // another session: the route is reused across ids
                            // (`launchSingleTop`), so the remembered host is too.
                            LaunchedEffect(id) { actionsHost.dismiss() }
                            // Fork navigation (`fork-dialog.tsx:88`
                            // `navigate(sessionHref(server.key, forked.id))`):
                            // `forkSession` parks the new id on
                            // `forkedSessionID`, which is consumed here — one
                            // fork, one navigation. The host never navigates on
                            // its own, so the route helpers stay in one file.
                            LaunchedEffect(actionsHost) {
                                viewModel.forkedSessionID.collect { forked ->
                                    if (forked.isNullOrEmpty()) return@collect
                                    viewModel.consumeForkedSession()
                                    navController.navigate(NavRestore.session(serverKey, forked)) {
                                        launchSingleTop = true
                                    }
                                }
                            }
                            // Upstream keeps the terminal as a bottom panel of
                            // the session body with its own tab strip
                            // (`session/screen.tsx:507-540`), so its open state
                            // belongs here — the floating chip and the docked pane
                            // are two views of this one flag.
                            Box(Modifier.fillMaxSize()) {
                                CompositionLocalProvider(
                                    LocalSessionActionsHost provides actionsHost,
                                ) {
                                    SessionScreen(
                                        chatViewModel = viewModel,
                                        sessionId = id,
                                        onBack = { navController.popBackStack() },
                                        // Upstream v2: the drawer's「新建会话」opens the
                                        // draft page (composer first, session created on
                                        // submit) instead of creating one eagerly.
                                        onNewSessionDraft = {
                                            navController.navigate(Route.newSession()) { launchSingleTop = true }
                                        },
                                        // A1/A8: the summary's server row and the
                                        // export's raw `GET /session/:id[/message]` both
                                        // read the active server entry directly, because
                                        // `ChatViewModel` exposes parsed models only.
                                        serverStore = serverStore,
                                        summaryToggleRevision = summaryRevision,
                                        // Upstream renders the terminal as a
                                        // **full-width bottom panel of the session
                                        // body** (`session/screen.tsx:507-540`) instead
                                        // of giving it a screen of its own, so on wide
                                        // screens the chip toggles this pane; the
                                        // standalone route stays for narrow ones.
                                        terminalDocked = terminalDocked,
                                        onCloseTerminalDock = { terminalDocked = false },
                                    )
                                }
                                // M5 phase 1 entry: a 28 dp ghost chip, kept as the
                                // **wide-screen** terminal entry (`Ctrl+`` and the
                                // palette row stay the keyboard paths).
                                //
                                // W4-D (Lead ruling): gated to ≥768dp — the same
                                // threshold as upstream's `isDesktop()`
                                // (`session/files/session-side-panel.tsx:87`). Below
                                // it the phone/fold session view already carries a
                                // 「终端」tab (mobile-form), so the chip would be a
                                // duplicate entry point.
                                //
                                // It also starts **below** the 80 dp header band: at
                                // `top = 52.dp` it sat on top of the header's ⋮ menu
                                // (`oc_more_options`, 40 dp box ending at the header's
                                // trailing edge), which made the menu button
                                // unreachable in the accessibility tree. The chip is a
                                // floating affordance over the timeline, so dropping it
                                // one row costs nothing.
                                if (wideScreen) {
                                    TerminalEntryButton(
                                        // Wide screens dock the pane at the bottom
                                        // of the session body (upstream
                                        // `session/screen.tsx:507-540`), so the chip
                                        // toggles it instead of pushing the
                                        // full-screen route.
                                        onClick = { terminalDocked = !terminalDocked },
                                        active = terminalDocked,
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(top = SESSION_CHIP_TOP_DP, end = 8.dp),
                                    )
                                }
                                // W6: the fork picker / share panel / archive
                                // confirmation. Mounted as the **last** sibling of
                                // this Box so it paints over the session body, and
                                // outside the provider above because it reads the
                                // host directly. A dialog that has to cover the
                                // window cannot be laid out inside the header's
                                // 48 dp row, which is why the header only opens it.
                                SessionActionsOverlay(actionsHost)
                            }
                        }
                    }
                    composable(
                        route = Route.TERMINAL,
                        arguments = listOf(
                            navArgument(Route.ARG_DIRECTORY) {
                                type = NavType.StringType
                                nullable = true
                                defaultValue = null
                            },
                        ),
                    ) { entry ->
                        // The standalone terminal route owns the whole window, so it
                        // must clear the system bars itself: without this the tab
                        // strip drew under the status bar and the first row was
                        // clipped (reported from a fold screenshot). The embedded
                        // mobile tab does not need it — its host already pads.
                        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                            TerminalPanelHost(
                                server = serverStore?.server,
                                directory = entry.arguments?.getString(Route.ARG_DIRECTORY),
                                // `settings.terminal-font` (`_upstream` `appearance.terminal`):
                                // `TerminalPanelHost` resolves the family and falls back to
                                // the system mono, so an empty value stays valid.
                                terminalFont = settings.terminalFont,
                                onClose = { navController.popBackStack() },
                            )
                        }
                    }
                }
            }
            // W7: the stored「底部导航」bar is the **mobile** placement (upstream
            // `titlebar.tsx:64-65`), so it stands down on a desktop width in
            // favour of the top strip above. Below the breakpoint nothing here
            // changes.
            if (bottomTabStrip && !desktopTabs) {
                val activeTab = openSessions.firstOrNull { it.id == currentSession }
                BottomTabStrip(
                    session = activeTab,
                    sessionActive = activeTab != null,
                    // WAVE 2.3b I-1: the full tab list + settings/help for the
                    // mobile drawer (`MobileTabsDrawer`).
                    sessions = openSessions,
                    activeSessionId = currentSession,
                    onOpenSettings = onOpenSettings,
                    onOpenHelp = onOpenHelp,
                    onHome = { onHome() },
                    onOpenSession = { tab ->
                        navController.navigate(NavRestore.session(serverKey, tab.id)) {
                            launchSingleTop = true
                        }
                    },
                    onCloseSession = { tab ->
                        openSessions.removeAll { it.id == tab.id }
                        if (currentSession == tab.id) navController.popBackStack()
                    },
                    onNewSession = {
                        onNewSession()
                    },
                )
            }
        }
    }
}

/**
 * Focus target that turns the global chords into their actions.
 *
 * Upstream handles these in a **document capture** listener
 * (`command.tsx:397-424`) with `preventDefault + stopPropagation`, and stands
 * down while a dialog is already active (`dialog.active` non-empty). Compose's
 * equivalent is a root `onPreviewKeyEvent` on a focused node.
 *
 * W4-D wires every binding the palette advertises, so a hardware keyboard
 * reaches the same actions the rows do:
 * `mod+k` / `mod+shift+p` (palette), `mod+p` (`file.open`), `mod+,` (settings),
 * `mod+n` / `mod+shift+s` (new session), `ctrl+`` (terminal toggle), `mod+f`
 * (timeline search) and — A1 — `mod+shift+y` (session summary).
 *
 * W5-INT: the chords are matched through [effectiveGlobalChords], i.e. the
 * **defaults** of W5-S' `SHORTCUT_ROWS` plus the user's recorded overrides
 * (`Settings.keybinds`). Recording a new key in the settings panel therefore
 * changes what this handler does, and an override for an id without a global
 * chord (`review.toggle`, `fileTree.toggle`, `prompt.submit`) is a documented
 * no-op instead of a silent one.
 */
@Composable
private fun GlobalKeybindFocus(
    onSettings: () -> Unit,
    onTerminalToggle: () -> Unit,
    onNewSession: () -> Unit,
    onToggleSummary: () -> Unit,
    onPalette: (PaletteHostKind) -> Unit,
    /** Home vs session: the same chord opens the host-specific palette. */
    paletteHost: () -> PaletteHostKind,
    /** WAVE 2.3b I-5: `mod+f` — Home focuses search, session opens timeline search. */
    onHomeSearch: () -> Unit = { TimelineSearch.open() },
    /** `Settings.keybinds` — the recorded override table (empty = all defaults). */
    keybinds: Map<String, String>,
    content: @Composable () -> Unit,
) {
    val active = LocalDialogActive.current
    val dialogController = LocalDialogController.current
    val focus = remember { FocusRequester() }
    // Re-claim focus whenever the dialog stack drains. A single
    // `LaunchedEffect(Unit)` request is not enough: the palette autofocuses its
    // search field (`shell/commands/dialog.tsx:130`), so after closing a dialog
    // the root focusable no longer held focus and **every** chord stayed dead
    // until the app restarted (verifier F-1). Keying on `active` re-arms the
    // handler on open and on close, and stays out of the way while a dialog is
    // up (the dialog owns the keys then).
    LaunchedEffect(active) {
        if (!active) runCatching { focus.requestFocus() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (active) {
                    // Upstream closes the top layer on Esc through a
                    // **document-level** capture (`packages/ui/src/context/
                    // dialog.tsx:65-76`). Layers whose content takes focus (the
                    // palette search field) handle Esc themselves, so this
                    // branch covers the focused-root case — e.g. the settings
                    // surface, which has no autofocus and otherwise ignored Esc
                    // completely (verifier F-8).
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        dialogController?.close()
                        return@onPreviewKeyEvent true
                    }
                    return@onPreviewKeyEvent false
                }
                when (globalChord(event, keybinds)) {
                    GlobalChord.PALETTE -> {
                        onPalette(paletteHost())
                        true
                    }

                    GlobalChord.PALETTE_FILES -> {
                        onPalette(PaletteHostKind.FILES_ONLY)
                        true
                    }

                    GlobalChord.SETTINGS -> {
                        onSettings()
                        true
                    }

                    GlobalChord.NEW_SESSION -> {
                        onNewSession()
                        true
                    }

                    GlobalChord.TERMINAL -> {
                        onTerminalToggle()
                        true
                    }

                    // `session.search` (`search-controller.ts:184-192`, W5-T):
                    // `mod+f` opens the timeline search bar — or focuses Home's
                    // search field when Home is on screen (WAVE 2.3b I-5). The
                    // search bar owns Esc once focused, like upstream.
                    GlobalChord.SESSION_SEARCH -> {
                        onHomeSearch()
                        true
                    }

                    // A1: `session.summary.toggle` (`popover.tsx:17-34`) —
                    // `mod+shift+y`. The scaffold bumps the revision
                    // `SessionScreen` keys on (and only while a session is up).
                    GlobalChord.SUMMARY -> {
                        onToggleSummary()
                        true
                    }

                    null -> false
                }
            },
    ) {
        content()
    }
}

/** `command.tsx:13-14` + `use-session-commands.tsx:248-408`. */
enum class GlobalChord {
    /** `mod+k` / `mod+shift+p` — `DEFAULT_PALETTE_KEYBIND`. */
    PALETTE,

    /** `mod+p` — `file.open` (`:314-321`), the files-only palette. */
    PALETTE_FILES,

    /** `mod+comma` — `settings.open`. */
    SETTINGS,

    /** `mod+n` (`tab.new`) / `mod+shift+s` (`session.new`). */
    NEW_SESSION,

    /** `ctrl+`` — `terminal.toggle` (`:354-368`). */
    TERMINAL,

    /**
     * `session.search` (`search-controller.ts:184-192`) — `mod+f` opens the
     * timeline search bar (W5-T).
     */
    SESSION_SEARCH,

    /** `session.summary.toggle` (`session/summary/popover.tsx:17-34`) — `mod+shift+y`. */
    SUMMARY,
}

/**
 * The command ids each [GlobalChord] serves, and the **single source** of the
 * default chords: [SHORTCUT_ROWS] is the settings panel's table, so a row and
 * this handler can never advertise different keys. The two ids that table does
 * not carry get theirs from [EXTRA_GLOBAL_CHORDS].
 *
 * Ids the shell implements **without** a global chord (`review.toggle`,
 * `fileTree.toggle`, `prompt.submit`) are deliberately absent: those rows are
 * reachable from the palette / the composer only, so an override recorded for
 * them can neither fire nor break another action. Documented in
 * `research/wave5_integration.md` §6.
 */
private val GLOBAL_CHORD_COMMAND_IDS: Map<String, GlobalChord> = linkedMapOf(
    "command.palette" to GlobalChord.PALETTE,
    "settings.open" to GlobalChord.SETTINGS,
    "file.open" to GlobalChord.PALETTE_FILES,
    "session.new" to GlobalChord.NEW_SESSION,
    "tab.new" to GlobalChord.NEW_SESSION,
    "terminal.toggle" to GlobalChord.TERMINAL,
    "session.search" to GlobalChord.SESSION_SEARCH,
    "session.summary.toggle" to GlobalChord.SUMMARY,
)

/**
 * Bindings for the ids [SHORTCUT_ROWS] does not list: `session.search` is
 * upstream's `mod+f` (never a settings row) and `session.summary.toggle` is A1 —
 * the settings wave's own files are outside this wave's file set, so its panel
 * cannot show the new row yet.
 */
private val EXTRA_GLOBAL_CHORDS: Map<String, String> = mapOf(
    "session.search" to "Ctrl+F",
    "session.summary.toggle" to "Ctrl+Shift+Y",
)

/** Every default chord the shell implements, keyed by command id. */
fun defaultGlobalChords(): Map<String, String> =
    SHORTCUT_ROWS.associate { it.id to it.keybind } + EXTRA_GLOBAL_CHORDS

/**
 * `signature → action` — what the key handler matches an event against.
 *
 * Built from the **effective** binding of every id in [GLOBAL_CHORD_COMMAND_IDS]:
 * the defaults of [SHORTCUT_ROWS] first, then the recorded overrides
 * (`Settings.keybinds`, written by the settings panel) so a user value always
 * wins. `none`/blank means "unassigned" and drops the action's chords entirely —
 * the same meaning the panel displays ([keybindSignatures] yields nothing for it).
 *
 * Collision policy: the settings panel refuses to **record** a chord another row
 * already uses (`keybindConflicts`), so a collision can only come from a
 * hand-edited preference file; this table resolves it deterministically —
 * overrides beat defaults, and between two overrides the later table entry wins.
 * Ties are therefore explainable instead of order-of-`HashMap` dependent.
 */
fun effectiveGlobalChords(keybinds: Map<String, String> = emptyMap()): Map<String, GlobalChord> {
    val defaults = defaultGlobalChords()
    val table = LinkedHashMap<String, GlobalChord>()
    for (overridden in listOf(false, true)) {
        for ((id, chord) in GLOBAL_CHORD_COMMAND_IDS) {
            val custom = keybinds[id]
            if ((custom != null) != overridden) continue
            val binding = custom ?: defaults[id] ?: continue
            for (signature in keybindSignatures(bindingForSignatures(binding))) {
                table[signature] = chord
            }
        }
    }
    return table
}

/**
 * Spell out a bare comma key before the binding goes through
 * [keybindSignatures].
 *
 * `,` is upstream's **list separator** (`parseKeybind`), so
 * `keybindSignatures("Ctrl+,")` reads as the bare modifier `Ctrl` — and
 * `settings.open`'s default really is `Ctrl+,`. Normalizing the trailing comma to
 * the recorded name of that key (`normalizeKeyName("COMMA")` → `,`; the vocabulary
 * spells it `Comma`, lower-cased to `comma` in the signature) keeps the matcher's
 * vocabulary identical to the settings panel's for every other key.
 */
private fun bindingForSignatures(binding: String): String =
    binding.split(" / ").joinToString(" / ") { chord ->
        if (chord.endsWith(",")) chord.dropLast(1) + "Comma" else chord
    }

/**
 * The token a key event contributes to a binding signature, in
 * [keybindSignatures]' vocabulary (lower-cased key, modifiers in the canonical
 * `ctrl,alt,shift,meta` order). `mod` folds into `ctrl` because Android has no ⌘
 * key and the recorder always writes `Ctrl`
 * (`formatKeybindChord`'s KDoc) — without the fold, `⌘+K` on a hardware keyboard
 * would stop matching the palette binding.
 *
 * Only keys a global chord can plausibly be bound to are mapped; anything else
 * returns null, so e.g. `Ctrl+F5` never matches. That is the deliberate,
 * explainable half of "override in the panel, nothing happens": see
 * `research/wave5_integration.md` §6.
 */
private val CHORD_KEY_TOKENS: Map<Key, String> = buildMap {
    val letters = listOf(
        "A" to Key.A, "B" to Key.B, "C" to Key.C, "D" to Key.D, "E" to Key.E, "F" to Key.F,
        "G" to Key.G, "H" to Key.H, "I" to Key.I, "J" to Key.J, "K" to Key.K, "L" to Key.L,
        "M" to Key.M, "N" to Key.N, "O" to Key.O, "P" to Key.P, "Q" to Key.Q, "R" to Key.R,
        "S" to Key.S, "T" to Key.T, "U" to Key.U, "V" to Key.V, "W" to Key.W, "X" to Key.X,
        "Y" to Key.Y, "Z" to Key.Z,
    )
    val digits = listOf(
        "0" to Key.Zero, "1" to Key.One, "2" to Key.Two, "3" to Key.Three, "4" to Key.Four,
        "5" to Key.Five, "6" to Key.Six, "7" to Key.Seven, "8" to Key.Eight, "9" to Key.Nine,
    )
    val punctuation = listOf(
        // The comma key is spelled `Comma` (see [bindingForSignatures]): a literal
        // `,` would be read as the chord separator by `keybindSignatures`.
        "Comma" to Key.Comma, "." to Key.Period, "/" to Key.Slash, "\\" to Key.Backslash,
        ";" to Key.Semicolon, "'" to Key.Apostrophe, "`" to Key.Grave, "-" to Key.Minus,
        "=" to Key.Equals, "[" to Key.LeftBracket, "]" to Key.RightBracket,
    )
    for ((token, key) in letters + digits + punctuation) put(key, token.lowercase())
}

/** [CHORD_KEY_TOKENS] lookup + modifier folding; null when the key cannot bind. */
fun chordSignatureToken(
    ctrl: Boolean,
    meta: Boolean,
    shift: Boolean,
    alt: Boolean,
    key: Key,
): String? {
    val name = CHORD_KEY_TOKENS[key] ?: return null
    return buildList {
        if (ctrl || meta) add("ctrl")
        if (alt) add("alt")
        if (shift) add("shift")
        add(name)
    }.joinToString("+")
}

/**
 * Pure mapping of a hard-keyboard event onto the global chords, so the keybind
 * contract is JVM-testable without a Compose runtime.
 *
 * Upstream's `matchKeybind` (`command.tsx:188-204`) compares **every** modifier
 * strictly, so `mod+shift+k` does not open the palette; the earlier Android
 * mapping ignored Shift for `k` (`audit_settings_palette.md` 附 3.1). Every
 * modifier still has to match exactly — the table only lists the signatures the
 * bindings really declare.
 *
 * [keybinds] is the recorded override table; empty means "defaults only".
 */
fun globalChord(
    type: KeyEventType,
    key: Key,
    ctrl: Boolean,
    meta: Boolean,
    shift: Boolean,
    alt: Boolean = false,
    keybinds: Map<String, String> = emptyMap(),
): GlobalChord? {
    if (type != KeyEventType.KeyDown) return null
    val token = chordSignatureToken(ctrl, meta, shift, alt, key) ?: return null
    return effectiveGlobalChords(keybinds)[token]
}

private fun globalChord(
    event: androidx.compose.ui.input.key.KeyEvent,
    keybinds: Map<String, String>,
): GlobalChord? = globalChord(
    type = event.type,
    key = event.key,
    ctrl = event.isCtrlPressed,
    meta = event.isMetaPressed,
    shift = event.isShiftPressed,
    alt = event.isAltPressed,
    keybinds = keybinds,
)

/**
 * The palette layer (§6).
 *
 * One view, three data assemblies, exactly like upstream:
 * `DialogHomeCommandPaletteV2` (commands + sessions, **no** file category),
 * `DialogCommandPaletteV2` (session / new-session: commands + sessions +
 * `/find/file`) and the `filesOnly` variant `file.open` opens
 * (`palette.ts:92-99`).
 */
@Composable
private fun PaletteDialogLayer(
    kind: PaletteHostKind,
    store: SettingsStore,
    serverStore: ServerConfigStore?,
    chatViewModel: ChatViewModel?,
    serverKey: String,
    actions: ShellActions,
    intents: SessionCommandIntents,
    onOpenSession: (String) -> Unit,
    onOpenFile: (String) -> Unit,
) {
    val dialog = requireDialogController()
    val paletteContext = LocalContext.current
    var forkRows by remember { mutableStateOf<List<ForkMessage>>(emptyList()) }
    // WAVE 2.3a §3: the session route's command rows ride the same
    // `commandEntries`/`commandFor` path as the app rows (the hidden
    // `terminal.close` row is already excluded from listing inside
    // `SessionCommands`). Home keeps app commands only
    // (`DialogHomeCommandPaletteV2`: commands + sessions, no session commands);
    // FILES_ONLY drops every command. Each wired id calls the existing
    // endpoint; the documented stubs stay stubs (log-only — no toast copy
    // exists for them, and `res/` is off-limits for a new one).
    val sessionCommands = remember(kind, chatViewModel, store, dialog) {
        if (kind != PaletteHostKind.SESSION) return@remember emptyList()
        SessionCommands.sessionCommands(
            actions = SessionCommands.SessionCommandActions(
                onUndo = chatViewModel?.let { viewModel -> { viewModel.undoSession() } },
                onRedo = chatViewModel?.let { viewModel -> { viewModel.redoSession() } },
                // `ChatViewModel.forkSession` + the fork picker (reads
                // `forkMessages()`); navigation reuses the scaffold's
                // `forkedSessionID` collector, so this layer never navigates.
                onFork = chatViewModel?.let { viewModel ->
                    {
                        forkRows = viewModel.forkMessages()
                        dialog.show(DialogSize.FIT) { controller ->
                            PaletteForkDialog(
                                rows = forkRows,
                                onPick = { id ->
                                    controller.close()
                                    viewModel.forkSession(id)
                                },
                                onClose = { controller.close() },
                            )
                        }
                    }
                },
                // `session.copyID`: the tracked session id onto the clipboard.
                onCopySessionID = chatViewModel?.let { viewModel ->
                    {
                        val id = viewModel.sessionID
                        val clipboard =
                            paletteContext.getSystemService(ClipboardManager::class.java)
                        if (id.isNullOrBlank() || clipboard == null) {
                            Toast.makeText(
                                paletteContext,
                                paletteContext.getString(R.string.common_requestFailed),
                                Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            clipboard.setPrimaryClip(ClipData.newPlainText("session id", id))
                            Toast.makeText(
                                paletteContext,
                                paletteContext.getString(R.string.common_copied),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                },
                // `session.archive` is v1-only (Wave 2.4a): null-gating hides the
                // palette row on v2 servers via SessionCommands' onArchive check.
                onArchive = chatViewModel?.let { viewModel ->
                    if (viewModel.generation.value == ServerGeneration.V1) {
                        { viewModel.archiveSession() }
                    } else {
                        null
                    }
                },
                // `permissions.autoaccept`: flips the stored switch.
                onToggleAutoAccept = {
                    store.setSwitch(
                        "settings-auto-accept-permissions",
                        !store.state.value.autoAcceptPermissions,
                    )
                },
            ),
            stubReporter = null,
        )
    }
    val commands = appCommands(
        // The palette's `settings.open` pushes the *same* settings layer as the
        // Home gear, including the servers bridge, so the Servers tab is never
        // empty when reached from the keyboard.
        onOpenSettings = actions.onOpenSettings,
        onOpenTerminal = actions.onTerminalToggle,
        onNewSession = actions.onNewSession,
        onNewWorkspace = actions.onNewWorkspace,
        onPreviousSession = { actions.onStepSession(-1) },
        onNextSession = { actions.onStepSession(1) },
        onToggleReview = actions.onToggleReview,
        onToggleSummary = actions.onToggleSummary,
        onOpenFilePicker = actions.onOpenFilePicker,
        onToggleFileTree = actions.onToggleFileTree,
        // `visibility.fileTree` (`settings/model.tsx:386-390`): upstream only
        // registers `fileTree.toggle` while the setting is on, so the row (and
        // its keybind) disappears with it.
        showFileTree = store.state.value.showFileTree,
    ) + sessionCommands
    val entries = stringResolvedEntries(commands)

    val openSessionIds = remember { mutableSetOf<String>() }
    var query by remember { mutableStateOf("") }
    var baseSessions by remember { mutableStateOf<List<OpenCodeClient.Session>>(emptyList()) }
    var exactSession by remember { mutableStateOf<OpenCodeClient.Session?>(null) }
    var fileEntries by remember { mutableStateOf<List<PaletteEntry>>(emptyList()) }
    val relativeTime = rememberRelativeTimeLabel()

    // Home lists sessions but never files (`DialogHomeCommandPaletteV2`); the
    // files-only picker has no commands and no session rows either.
    LaunchedEffect(chatViewModel, kind) {
        if (chatViewModel == null || kind == PaletteHostKind.FILES_ONLY) return@LaunchedEffect
        chatViewModel.listSessions { sessions ->
            baseSessions = sessions
        }
    }
    // WAVE 2.3a §6: exact-ID lookup alongside the list search
    // (`palette.ts:264`) — a `ses_…` query also probes `GET /session/:id`, and
    // the hit rides first via `sessionSearchEntries` (archived rows dropped,
    // capped at 50 — the same shape `sessionPaletteEntries` had, which this
    // replaces).
    val paletteClient = remember(serverStore) {
        serverStore?.server?.let { OpenCodeClient.forServer(it) }
    }
    LaunchedEffect(query, kind) {
        val id = query.trim()
        if (kind == PaletteHostKind.FILES_ONLY || !looksLikeSessionID(id)) {
            exactSession = null
            return@LaunchedEffect
        }
        val fetched = withContext(Dispatchers.IO) {
            runCatching { paletteClient?.session(id) }.getOrNull()
        }
        if (query.trim() == id) exactSession = fetched
    }
    val sessionEntries = remember(baseSessions, exactSession, serverKey) {
        sessionSearchEntries(
            listed = baseSessions,
            exact = exactSession,
            server = serverKey,
            openSessionIds = openSessionIds,
            meta = relativeTime,
        )
    }
    // Server-side `/find/file` while the query is non-empty; debounced per
    // keystroke, and a late callback is dropped when the query has moved on.
    LaunchedEffect(query, chatViewModel, kind) {
        val q = query.trim()
        if (chatViewModel == null || kind == PaletteHostKind.HOME || q.isEmpty()) {
            fileEntries = emptyList()
            return@LaunchedEffect
        }
        delay(PALETTE_FILE_SEARCH_DEBOUNCE_MS)
        chatViewModel.searchFiles(q, null) { paths ->
            if (query.trim() == q) fileEntries = filePaletteEntries(paths)
        }
    }

    val sources = remember(kind, entries, sessionEntries, fileEntries) {
        // recentFiles stays empty: no opened-files data source exists yet.
        kind.sources(entries, sessions = sessionEntries, files = fileEntries)
    }

    CommandPalette(
        kind = kind,
        sources = sources,
        onClose = { dialog.close() },
        onQueryChange = { query = it },
        onSelect = { entry: PaletteEntry ->
            dialog.close()
            when (entry.type) {
                PaletteEntryType.SESSION -> entry.sessionID?.let(onOpenSession)
                // W4-D: upstream `createCommandPaletteFileOpener` (`palette.ts:66-80`)
                // opens the file in a tab, opens the review panel and switches the
                // file tree to `all` — on Android that is the session screen's
                // FILES tab, driven through the intent channel.
                PaletteEntryType.FILE -> entry.path?.let(onOpenFile)
                PaletteEntryType.COMMAND ->
                    commands.firstOrNull { it.entryId == entry.id }?.onSelect?.invoke()
            }
        },
    )
}

/**
 * `getRelativeTime` (`shell/commands/dialog.tsx:267-271`) resolved against the
 * drawer's shared bucketing. `Resources.getQuantityString` is a plain call, so
 * the returned lambda is usable from non-composable code.
 */
@Composable
private fun rememberRelativeTimeLabel(): (OpenCodeClient.Session) -> String? {
    val resources = LocalContext.current.resources
    return remember(resources) {
        { session: OpenCodeClient.Session ->
            SessionTimeLabel.relative(session.updatedAt, System.currentTimeMillis())?.let { relative ->
                when (relative.unit) {
                    SessionTimeLabel.Unit.JUST_NOW -> resources.getString(R.string.oc_time_just_now)
                    SessionTimeLabel.Unit.MINUTES ->
                        resources.getQuantityString(R.plurals.oc_time_minutes_ago, relative.count, relative.count)

                    SessionTimeLabel.Unit.HOURS ->
                        resources.getQuantityString(R.plurals.oc_time_hours_ago, relative.count, relative.count)

                    SessionTimeLabel.Unit.DAYS ->
                        resources.getQuantityString(R.plurals.oc_time_days_ago, relative.count, relative.count)
                }
            }
        }
    }
}

/** Debounce for the palette's `/find/file` round-trip per keystroke. */
private const val PALETTE_FILE_SEARCH_DEBOUNCE_MS = 200L

/** WAVE 2.3b I-1: upstream desktop-feedback form opened by the drawer Help row. */
private const val FEEDBACK_URL = "https://opencode.ai/desktop-feedback"

/**
 * Width (dp) from which the session screen keeps the floating terminal chip.
 * 768dp is upstream's desktop breakpoint (`isDesktop()`,
 * `session/files/session-side-panel.tsx:87`); below it the mobile session view's
 * own「终端」tab owns the terminal, so the extra chip would both duplicate the
 * entry point and collide with the header's ⋮ menu.
 */
private const val SESSION_CHIP_MIN_WIDTH_DP = 768

/**
 * Top offset of that chip. The session header band occupies 0–80 dp (48 dp bar
 * plus the sticky row); the chip used to sit at 52 dp and covered the header's
 * ⋮ (`oc_more_options`), so it now starts clear of the whole header.
 */
private val SESSION_CHIP_TOP_DP = 88.dp

/**
 * The nav/panel side effects the palette rows and the global chords share.
 *
 * Built once per composition inside [AppScaffold] (not `remember`ed: several
 * members read `currentRoute` / `currentSession`, which must not be captured
 * stale), then handed to the palette layer so a row's `onSelect` and the
 * hardware chord run the *same* lambda.
 */
private class ShellActions(
    val onOpenSettings: () -> Unit,
    val onNewSession: () -> Unit,
    val onNewWorkspace: () -> Unit,
    val onStepSession: (Int) -> Unit,
    val onTerminalToggle: () -> Unit,
    val onToggleReview: () -> Unit,
    /** A1: `session.summary.toggle` — bumps the revision `SessionScreen` reads. */
    val onToggleSummary: () -> Unit,
    val onToggleFileTree: () -> Unit,
    val onOpenFilePicker: () -> Unit,
)

/**
 * Pushes the palette layer. Not `@Composable`: the lambda runs inside the
 * `onPreviewKeyEvent` handler, and `DialogController.show` only appends a layer
 * to the stack (`@Composable` content is captured, not invoked).
 */
private fun showPaletteDialog(
    dialog: DialogController,
    kind: PaletteHostKind,
    store: SettingsStore,
    serverStore: ServerConfigStore?,
    chatViewModel: ChatViewModel?,
    serverKey: String,
    actions: ShellActions,
    intents: SessionCommandIntents,
    onOpenSession: (String) -> Unit,
    onOpenFile: (String) -> Unit,
) {
    dialog.show(DialogSize.LARGE) {
        PaletteDialogLayer(
            kind = kind,
            store = store,
            serverStore = serverStore,
            chatViewModel = chatViewModel,
            serverKey = serverKey,
            actions = actions,
            intents = intents,
            onOpenSession = onOpenSession,
            onOpenFile = onOpenFile,
        )
    }
}

/**
 * WAVE 2.3a §3 (`session.fork`): the palette's fork row opens the same picker
 * the ⋮ menu uses — one row per forkable user message
 * (`fork-dialog.tsx:60-95`), forking **before** the picked message. Navigation
 * reuses the scaffold's `forkedSessionID` collector, so this layer never
 * navigates itself. A dialog layer (not an inline swap) so Esc/the scrim
 * dismisses back to the palette's caller.
 */
@Composable
private fun PaletteForkDialog(
    rows: List<ForkMessage>,
    onPick: (String) -> Unit,
    onClose: () -> Unit,
) {
    val c = OcTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.bgBase)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = stringResource(R.string.command_session_fork),
            color = c.textBase,
            fontSize = 14.sp,
        )
        if (rows.isEmpty()) {
            Text(
                text = stringResource(R.string.dialog_fork_empty),
                color = c.textMuted,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .padding(top = 4.dp),
            ) {
                items(rows, key = { it.id }) { row ->
                    Text(
                        text = row.text,
                        color = c.textBase,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(OcTheme.shapes.md)
                            .clickable(role = Role.Button) { onPick(row.id) }
                            .padding(horizontal = 6.dp, vertical = 8.dp),
                    )
                }
            }
        }
        Text(
            text = stringResource(R.string.oc_common_cancel),
            color = c.textMuted,
            fontSize = 13.sp,
            modifier = Modifier
                .clip(OcTheme.shapes.md)
                .clickable(role = Role.Button, onClick = onClose)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

/**
 * Opens the `x-large` Settings surface from anywhere (Home gear, palette, ⌘,).
 * Plain function on purpose: it is called from key handlers, never from
 * composition, so it receives the [DialogController] instead of reading the
 * CompositionLocal.
 *
 * W4-D: the Providers / Models tabs get the **live** catalog flows. Passing
 * snapshots (or nothing, as before) is what left both tabs permanently empty —
 * `GET /provider` has usually not answered yet when the dialog is pushed.
 */
private fun showSettingsDialog(
    dialog: DialogController,
    store: SettingsStore,
    serverStore: ServerConfigStore?,
    chatViewModel: ChatViewModel?,
) {
    dialog.show(DialogSize.X_LARGE) {
        SettingsDialogContent(
            store = store,
            extra = SettingsPanelExtras(
                providers = chatViewModel?.availableProviders,
                selectedModel = chatViewModel?.selectedModel,
                serverBridge = SettingsServerBridge(serverStore),
            ),
        )
    }
}

/** M0 stand-in kept for calls without a [ChatViewModel] (previews, deep links). */
@Composable
private fun SessionPlaceholder() {
    Box(Modifier.fillMaxSize().background(OcTheme.colors.bgDeep), contentAlignment = Alignment.Center) {
        Text(
            text = "session",
            color = OcTheme.colors.textFaint,
            fontSize = 13.sp,
        )
    }
}

/**
 * W5-E §5.5 — the in-app notification banner: the UI half of the notification
 * pipeline whose data half (`AppNotifier` + the three settings gates) shipped with
 * W5-E.
 *
 * Mounted by [OcApp] **inside** `OcTheme` and **outside** `DialogHost`, so it
 * survives every route and is never clipped by a dialog's scrim. Only `!viewed`
 * entries surface — that is the report's recommended suppression: a `viewed` entry
 * was raised while *this* session was already on screen, so a banner for it would
 * only repeat what the user just watched happen. One entry at a time (the newest),
 * because three cards stacked over the header is worse than a queue.
 *
 * 「知道了」 dismisses the shown entry (`dismissNotification(id)`), 「全部清除」
 * drops the whole feed (`clearNotifications()`). A null [ChatViewModel] (previews,
 * deep links) renders nothing.
 *
 * Deliberately **not** wired: tapping the banner to jump to the session. Upstream's
 * `notification.action.goToSession` needs the session route and the
 * `NavController`, both of which live in `AppScaffold` — a *child* of this banner,
 * not a parent. See `research/wave5_integration.md` §4 for the option list.
 */
@Composable
private fun NotificationBanner(
    chatViewModel: ChatViewModel?,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    val notifications by (chatViewModel?.notifications ?: NO_NOTIFICATIONS)
        .collectAsStateWithLifecycle()
    val pending = notifications.lastOrNull { !it.viewed } ?: return
    Row(
        modifier = modifier
            .widthIn(max = 480.dp)
            .clip(OcTheme.shapes.xl)
            .background(c.layer01)
            .border(0.5.dp, c.borderBase, OcTheme.shapes.xl)
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(pending.kind.titleResId()),
                color = c.textBase,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // `sessionTitle ?: detail` — the report's second line.
            val detail = pending.sessionTitle ?: pending.detail
            if (!detail.isNullOrBlank()) {
                Text(
                    text = detail,
                    color = c.textMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        BannerAction(label = stringResource(R.string.oc_notify_dismiss)) {
            chatViewModel?.dismissNotification(pending.id)
        }
        BannerAction(label = stringResource(R.string.oc_notify_dismiss_all)) {
            chatViewModel?.clearNotifications()
        }
    }
}

/** A 13 sp text action inside the banner (no Material button: the OC2 tokens only). */
@Composable
private fun BannerAction(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        color = OcTheme.colors.textMuted,
        fontSize = 13.sp,
        maxLines = 1,
        modifier = Modifier
            .clip(OcTheme.shapes.md)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/** `kind → title`; the one mapping the report asked to keep in a single place. */
private fun AppNotificationKind.titleResId(): Int = when (this) {
    AppNotificationKind.AGENT -> R.string.oc_notify_title_agent
    AppNotificationKind.PERMISSION -> R.string.oc_notify_title_permission
    AppNotificationKind.QUESTION -> R.string.oc_notify_title_question
    AppNotificationKind.ERROR -> R.string.oc_notify_title_error
}

/**
 * Stand-in for `ChatViewModel.notifications` when there is no ViewModel, so the
 * `collectAsStateWithLifecycle` above stays unconditional (a conditional
 * collection would never re-enter once the banner had early-returned).
 */
private val NO_NOTIFICATIONS = MutableStateFlow<List<AppNotification>>(emptyList())

@Composable
internal fun LocalOcColors() = OcTheme.colors
