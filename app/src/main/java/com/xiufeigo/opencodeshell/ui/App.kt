package com.xiufeigo.opencodeshell.ui

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
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
import com.xiufeigo.opencodeshell.ServerConfigStore
import com.xiufeigo.opencodeshell.SettingsStore
import com.xiufeigo.opencodeshell.ui.nav.BottomTabStrip
import com.xiufeigo.opencodeshell.ui.nav.NavMemory
import com.xiufeigo.opencodeshell.ui.nav.NavRestore
import com.xiufeigo.opencodeshell.ui.nav.SessionTabRef
import com.xiufeigo.opencodeshell.ui.home.HomeScreen
import com.xiufeigo.opencodeshell.ui.home.NewSessionScreen
import com.xiufeigo.opencodeshell.ui.overlay.DialogController
import com.xiufeigo.opencodeshell.ui.overlay.DialogHost
import com.xiufeigo.opencodeshell.ui.overlay.DialogSize
import com.xiufeigo.opencodeshell.ui.overlay.LocalDialogActive
import com.xiufeigo.opencodeshell.ui.overlay.requireDialogController
import com.xiufeigo.opencodeshell.ui.palette.CommandPalette
import com.xiufeigo.opencodeshell.ui.palette.PaletteEntry
import com.xiufeigo.opencodeshell.ui.palette.PaletteHostKind
import com.xiufeigo.opencodeshell.ui.palette.appCommands
import com.xiufeigo.opencodeshell.ui.palette.isPaletteChord
import com.xiufeigo.opencodeshell.ui.palette.isSettingsChord
import com.xiufeigo.opencodeshell.ui.palette.sources
import com.xiufeigo.opencodeshell.ui.palette.stringResolvedEntries
import com.xiufeigo.opencodeshell.ui.session.SessionScreen
import com.xiufeigo.opencodeshell.ui.settings.SettingsDialogContent
import com.xiufeigo.opencodeshell.ui.settings.SettingsPanelExtras
import com.xiufeigo.opencodeshell.ui.settings.SettingsServerBridge
import com.xiufeigo.opencodeshell.ui.terminal.TerminalEntryButton
import com.xiufeigo.opencodeshell.ui.terminal.TerminalPanelHost
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import com.xiufeigo.opencodeshell.ui.theme.ProvideOcReducedMotion

/**
 * Native Compose root — the MemoryRouter equivalent of the opencode web app.
 *
 * **M4 change:** Settings is no longer a route destination. Upstream renders it
 * as `Dialog size="x-large" variant="settings"` pushed through the *global*
 * dialog stack (`research/05` §5.1), and the same stack hosts the command
 * palette (`research/05` §1/§2). [DialogHost] therefore wraps the nav tree, and
 * Home / session / new-session all open the dialog instead of navigating. The
 * `/settings` route is kept for deep links but immediately opens the dialog and
 * pops.
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
 */
object Route {
    const val HOME = "home"
    const val SESSION = "server/{serverKey}/session/{id}"
    const val SETTINGS = "settings"
    const val NEW_SESSION = "new-session"

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

    OcTheme(isDark = dark) {
        // M6: the system animation scale (`prefers-reduced-motion`) is resolved
        // once, here, and published to every motion component below.
        ProvideOcReducedMotion {
            // The layer host owns the stack; [AppScaffold] lives *inside* it so the
            // global keybind hook and every route share one controller.
            DialogHost {
                AppScaffold(
                    chatViewModel = chatViewModel,
                    store = store,
                    serverStore = serverStore,
                    serverKey = serverKey,
                    lastRoute = lastRoute,
                    bottomTabStrip = bottomTabStrip,
                )
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
) {
    val navController = rememberNavController()
    val dialog = requireDialogController()

    // M4: a single opener shared by the Home gear, the global ⌘, chord and the
    // palette's `settings.open` row.
    val onOpenSettings: () -> Unit = { showSettingsDialog(dialog, store, serverStore) }

    // ---- M3: last-page restore + the optional bottom title bar -------------
    // The app-level tab store (`context/tabs.tsx`, §4.7) is not in M3 scope.
    // What this shell can state truthfully is which sessions it has opened in
    // this run — that drives the Home row's "open as tab" marker (§1.3) and the
    // optional bottom strip (§9b).
    val openSessions = remember { mutableStateListOf<SessionTabRef>() }
    var currentSession by remember { mutableStateOf<String?>(null) }
    val titleFlow = chatViewModel?.currentTitle
    val currentSessionTitle =
        if (titleFlow == null) null else titleFlow.collectAsStateWithLifecycle().value

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
    // The terminal panel is reachable from two places, both owned here: the
    // palette's `terminal.toggle` command (upstream's `Ctrl+``) and a tap target
    // on the session screen (the palette needs a hardware keyboard).
    val openTerminal: () -> Unit = { navController.navigate(Route.terminal()) }

    GlobalKeybindFocus(
        onSettings = onOpenSettings,
        onOpenTerminal = openTerminal,
        serverStore = serverStore,
    ) {
        // M3: the optional bottom title bar (§9b) has to sit *under* the
        // navigation content, so the graph is wrapped in a column.
        Column(Modifier.fillMaxSize().background(OcTheme.colors.bgDeep)) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                NavHost(navController = navController, startDestination = Route.HOME) {
                    composable(Route.HOME) {
                        HomeScreen(
                            chatViewModel = chatViewModel,
                            openSessionIds = openSessions.map { it.id }.toSet(),
                            onOpenSession = { id ->
                                navController.navigate(NavRestore.session(serverKey, id))
                            },
                            onNewSession = { navController.navigate(Route.NEW_SESSION) },
                            onOpenSettings = onOpenSettings,
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
                    composable(Route.NEW_SESSION) {
                        val viewModel = chatViewModel
                        if (viewModel == null) {
                            Box(Modifier.fillMaxSize().background(OcTheme.colors.bgDeep))
                        } else {
                            NewSessionScreen(
                                chatViewModel = viewModel,
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
                        if (viewModel == null || id.isEmpty()) {
                            SessionPlaceholder()
                        } else {
                            Box(Modifier.fillMaxSize()) {
                                SessionScreen(
                                    chatViewModel = viewModel,
                                    sessionId = id,
                                    onBack = { navController.popBackStack() },
                                )
                                // M5 phase 1 entry: a 28 dp ghost chip below the
                                // 48 dp sticky header. Phase 2 replaces it with
                                // the review panel's terminal tab
                                // (`research/02` §8/§9.3) and keeps the palette
                                // command above as the keyboard path.
                                TerminalEntryButton(
                                    onClick = openTerminal,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(top = 52.dp, end = 8.dp),
                                )
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
                        TerminalPanelHost(
                            server = serverStore?.server,
                            directory = entry.arguments?.getString(Route.ARG_DIRECTORY),
                            onClose = { navController.popBackStack() },
                        )
                    }
                }
            }
            if (bottomTabStrip) {
                val activeTab = openSessions.firstOrNull { it.id == currentSession }
                BottomTabStrip(
                    session = activeTab,
                    sessionActive = activeTab != null,
                    onHome = {
                        currentSession = null
                        navController.navigate(Route.HOME) { launchSingleTop = true }
                    },
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
                        navController.navigate(Route.NEW_SESSION) { launchSingleTop = true }
                    },
                )
            }
        }
    }
}

/**
 * Focus target that turns the global palette chords into `dialog.show` calls.
 *
 * Upstream handles these in a **document capture** listener
 * (`command.tsx:397-424`) with `preventDefault + stopPropagation`, and stands
 * down while a dialog is already active (`dialog.active` non-empty). Compose's
 * equivalent is a root `onPreviewKeyEvent` on a focused node.
 */
@Composable
private fun GlobalKeybindFocus(
    onSettings: () -> Unit,
    onOpenTerminal: () -> Unit,
    serverStore: ServerConfigStore?,
    content: @Composable () -> Unit,
) {
    val dialog = requireDialogController()
    val active = LocalDialogActive.current
    val context = LocalContext.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (active) return@onPreviewKeyEvent false
                when (globalChord(event)) {
                    GlobalChord.PALETTE -> {
                        showPaletteDialog(
                            dialog,
                            context,
                            PaletteHostKind.SESSION,
                            onOpenTerminal,
                            serverStore,
                        )
                        true
                    }

                    GlobalChord.SETTINGS -> {
                        onSettings()
                        true
                    }

                    null -> false
                }
            },
    ) {
        content()
    }
}

/** Which global chord a key event represents (`command.tsx:13-14`). */
enum class GlobalChord { PALETTE, SETTINGS }

/**
 * Pure mapping of a hard-keyboard event onto the two global chords, so the
 * keybind contract is JVM-testable without a Compose runtime.
 */
fun globalChord(
    type: KeyEventType,
    key: Key,
    ctrl: Boolean,
    meta: Boolean,
    shift: Boolean,
): GlobalChord? {
    if (type != KeyEventType.KeyDown) return null
    val mod = ctrl || meta
    return when {
        isPaletteChord(mod, key == Key.K, key == Key.P, shift) -> GlobalChord.PALETTE
        isSettingsChord(mod, key == Key.Comma) -> GlobalChord.SETTINGS
        else -> null
    }
}

private fun globalChord(event: androidx.compose.ui.input.key.KeyEvent): GlobalChord? = globalChord(
    type = event.type,
    key = event.key,
    ctrl = event.isCtrlPressed,
    meta = event.isMetaPressed,
    shift = event.isShiftPressed,
)

/** The palette layer (§6) — same view for Home, session and new-session. */
@Composable
private fun PaletteDialogLayer(
    kind: PaletteHostKind,
    onOpenTerminal: () -> Unit,
    serverStore: ServerConfigStore?,
) {
    val dialog = requireDialogController()
    val context = LocalContext.current
    val settingsStore = remember(context) { SettingsStore(context) }
    val commands = appCommands(
        // The palette's `settings.open` pushes the *same* settings layer as the
        // Home gear, including the servers bridge, so the Servers tab is never
        // empty when reached from the keyboard.
        onOpenSettings = { showSettingsDialog(dialog, settingsStore, serverStore) },
        onOpenTerminal = onOpenTerminal,
    )
    val entries = stringResolvedEntries(commands)
    val sources = remember(kind, entries) { kind.sources(entries) }

    CommandPalette(
        kind = kind,
        sources = sources,
        onClose = { dialog.close() },
        onSelect = { entry: PaletteEntry ->
            dialog.close()
            commands.firstOrNull { it.entryId == entry.id }?.onSelect?.invoke()
        },
    )
}

/**
 * Pushes the palette layer. Not `@Composable`: the lambda runs inside the
 * `onPreviewKeyEvent` handler, and `DialogController.show` only appends a layer
 * to the stack (`@Composable` content is captured, not invoked).
 */
private fun showPaletteDialog(
    dialog: DialogController,
    context: Context,
    kind: PaletteHostKind,
    onOpenTerminal: () -> Unit,
    serverStore: ServerConfigStore?,
) {
    dialog.show(DialogSize.LARGE) { PaletteDialogLayer(kind, onOpenTerminal, serverStore) }
}

/**
 * Opens the `x-large` Settings dialog from anywhere (Home gear, palette, ⌘,).
 * Plain function on purpose: it is called from key handlers, never from
 * composition, so it receives the [DialogController] instead of reading the
 * CompositionLocal.
 */
private fun showSettingsDialog(
    dialog: DialogController,
    store: SettingsStore,
    serverStore: ServerConfigStore?,
) {
    dialog.show(DialogSize.X_LARGE) {
        SettingsDialogContent(
            store = store,
            extra = SettingsPanelExtras(serverBridge = SettingsServerBridge(serverStore)),
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

@Composable
internal fun LocalOcColors() = OcTheme.colors
