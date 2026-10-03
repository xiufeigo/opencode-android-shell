package com.xiufeigo.opencodeshell.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
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
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.sizeIn
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.xiufeigo.opencodeshell.ChatStore
import com.xiufeigo.opencodeshell.ChatViewModel
import com.xiufeigo.opencodeshell.OpenCodeClient
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ServerConfigStore
import com.xiufeigo.opencodeshell.SessionUnread
import com.xiufeigo.opencodeshell.SettingsStore
import com.xiufeigo.opencodeshell.data.HomeProjectStore
import com.xiufeigo.opencodeshell.notify.AppNotification
import com.xiufeigo.opencodeshell.notify.BannerPolicy
import com.xiufeigo.opencodeshell.notify.NotificationNavigation
import com.xiufeigo.opencodeshell.notify.SystemNotificationPolicy
import com.xiufeigo.opencodeshell.notify.SystemNotifier
import com.xiufeigo.opencodeshell.v2.ServerGeneration
import com.xiufeigo.opencodeshell.v2.SessionRenameOutcome
import com.xiufeigo.opencodeshell.notify.AppNotificationKind
import com.xiufeigo.opencodeshell.SessionAsks
import com.xiufeigo.opencodeshell.ui.nav.BottomTabStrip
import com.xiufeigo.opencodeshell.ui.nav.ClosedTab
import com.xiufeigo.opencodeshell.ui.nav.ClosedTabs
import com.xiufeigo.opencodeshell.ui.nav.DesktopTabStrip
import com.xiufeigo.opencodeshell.ui.nav.desktopCaptionReserve
import com.xiufeigo.opencodeshell.ui.nav.DraftTarget
import com.xiufeigo.opencodeshell.ui.nav.HomeToggle
import com.xiufeigo.opencodeshell.ui.nav.MobileDrawerMount
import com.xiufeigo.opencodeshell.ui.nav.NavMemory
import com.xiufeigo.opencodeshell.ui.nav.NavRestore
import com.xiufeigo.opencodeshell.ui.nav.SessionDotSources
import com.xiufeigo.opencodeshell.ui.nav.SessionTabBadge
import com.xiufeigo.opencodeshell.ui.nav.SessionTabRef
import com.xiufeigo.opencodeshell.ui.nav.SessionTabs
import com.xiufeigo.opencodeshell.ui.nav.TabCloseTarget
import com.xiufeigo.opencodeshell.ui.renameFailureToast
import com.xiufeigo.opencodeshell.ui.debug.DebugBar
import com.xiufeigo.opencodeshell.ui.debug.ProviderMetrics
import com.xiufeigo.opencodeshell.ui.home.HomeRowActions
import com.xiufeigo.opencodeshell.ui.home.HomeScreen
import com.xiufeigo.opencodeshell.ui.home.HomeSearchFocusRequest
import com.xiufeigo.opencodeshell.ui.home.NewSessionScreen
import com.xiufeigo.opencodeshell.ui.home.NewSessionWorktreeStore
import com.xiufeigo.opencodeshell.ui.overlay.DialogController
import com.xiufeigo.opencodeshell.ui.overlay.DialogHost
import com.xiufeigo.opencodeshell.ui.overlay.DialogSize
import com.xiufeigo.opencodeshell.ui.overlay.LocalDialogActive
import com.xiufeigo.opencodeshell.ui.overlay.McpChooserDialog
import com.xiufeigo.opencodeshell.ui.overlay.collapseImeOnEscape
import com.xiufeigo.opencodeshell.ui.overlay.isImeVisible
import com.xiufeigo.opencodeshell.ui.overlay.requireDialogController
import com.xiufeigo.opencodeshell.ui.palette.CommandPalette
import com.xiufeigo.opencodeshell.ui.palette.ChordCycles
import com.xiufeigo.opencodeshell.ui.palette.PaletteEntry
import com.xiufeigo.opencodeshell.ui.palette.PaletteEntryType
import com.xiufeigo.opencodeshell.ui.palette.PaletteHost
import com.xiufeigo.opencodeshell.ui.palette.PaletteHostKind
import com.xiufeigo.opencodeshell.ui.palette.PaletteRelativeTime
import com.xiufeigo.opencodeshell.ui.palette.LocalPaletteHost
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
import com.xiufeigo.opencodeshell.ui.servers.SshTunnelHost
import com.xiufeigo.opencodeshell.ui.servers.requiresConnectScreen
import com.xiufeigo.opencodeshell.ui.session.LocalSessionDeleteHost
import com.xiufeigo.opencodeshell.ui.session.SessionDeleteHost
import com.xiufeigo.opencodeshell.ui.session.SessionScreen
import com.xiufeigo.opencodeshell.ui.session.actions.ForkMessage
import com.xiufeigo.opencodeshell.ui.session.actions.LocalSessionActionsHost
import com.xiufeigo.opencodeshell.ui.session.actions.SessionActionsHost
import com.xiufeigo.opencodeshell.ui.session.actions.SessionActionsOverlay
import com.xiufeigo.opencodeshell.ui.session.btw.LocalSessionBtwHost
import com.xiufeigo.opencodeshell.ui.session.btw.SessionBtwHost
import com.xiufeigo.opencodeshell.ui.session.drawer.MobileForm
import com.xiufeigo.opencodeshell.ui.session.header.LocalSshTunnelHost
import com.xiufeigo.opencodeshell.ui.session.timeline.TimelineSearch
import com.xiufeigo.opencodeshell.ui.settings.LocalMcpRevision
import com.xiufeigo.opencodeshell.ui.settings.LocalTimelineDisplayPrefs
import com.xiufeigo.opencodeshell.ui.settings.SHORTCUT_ROWS
import com.xiufeigo.opencodeshell.ui.settings.SettingsDialogContent
import com.xiufeigo.opencodeshell.ui.settings.SettingsPanelExtras
import com.xiufeigo.opencodeshell.ui.settings.SettingsServerData
import com.xiufeigo.opencodeshell.ui.settings.SettingsServerBridge
import com.xiufeigo.opencodeshell.ui.settings.SettingsTab
import com.xiufeigo.opencodeshell.ui.settings.SettingsV2Delegate
import com.xiufeigo.opencodeshell.ui.settings.keybindSignatures
import com.xiufeigo.opencodeshell.ui.settings.rememberSettledRevision
import com.xiufeigo.opencodeshell.ui.settings.timelineDisplayPrefs
import com.xiufeigo.opencodeshell.ui.terminal.TerminalPanelHost
import com.xiufeigo.opencodeshell.ui.workspace.WorktreeCreateDialog
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import com.xiufeigo.opencodeshell.ui.theme.ProvideOcReducedMotion
import com.xiufeigo.opencodeshell.ui.theme.codeFontFamily
import com.xiufeigo.opencodeshell.ui.theme.uiFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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

/**
 * The title a tab opened for [openingId] may adopt at the instant the route
 * changes — `null` unless the name is known to *be* that session's.
 *
 * The nav listener runs before the destination composes, so
 * `ChatViewModel.observeSession` has not run yet and `currentTitle` still holds
 * whatever the **previous** session left there. Seeding the new tab with it is
 * what put 「Quick check-in」 on a tab whose content pane was the new-session
 * draft, and a session that never gets a snapshot of its own (offline, deleted,
 * the read failing) kept that borrowed name for the rest of the run — the
 * v64 `research/v64_verify/5558/02_newsession.png` / `04b_tabmenu.png` pair.
 * `null` is not a loss: the strip falls back to `oc_tab_session` and
 * [sessionTitleSync] fills the real name in as soon as the session reports one.
 *
 * [knownTitle] is the one source that can be taken unconditionally, because it
 * is keyed by the id it was read for ([ChatViewModel.sessionTitle]): a name
 * learned from a catalogue read, a rename or a `session.renamed` frame. It is
 * what lets a tab switch land on the right label on the first frame instead of
 * 「会话」 until the view model adopts the session (v68 C1) — the live title
 * cannot do that job, since at this instant it is the *outgoing* session's.
 */
internal fun tabSeedTitle(
    trackedSessionId: String?,
    openingId: String,
    liveTitle: String?,
    knownTitle: String? = null,
): String? = when {
    !knownTitle.isNullOrBlank() -> knownTitle
    trackedSessionId == openingId -> liveTitle?.takeIf { it.isNotBlank() }
    else -> null
}

/**
 * The title the session header renders.
 *
 * Upstream reads it from one store, so the strip and the header cannot disagree
 * (`titlebar.tsx:209-233`, `:477`); here the reads are split across a Compose
 * frame, and on the frame a **tab switch** lands on the view model has not run
 * [ChatViewModel.observeSession] yet (it fires from the destination's own
 * effect, after this composition). `liveTitle` is therefore still the outgoing
 * session's, and reading it unconditionally put the old name — or, after the
 * draft page cleared the title, the 「新建会话」 placeholder — on a session that
 * already had a name until the next read came back (v68 C1, 5558).
 *
 * The order is therefore: the optimistic rename, then the live title **only
 * once the view model has adopted the session on screen**, then a name this
 * client already learned for *this* id ([ChatViewModel.sessionTitle] — keyed,
 * so it is the session's own and is right on the first frame of a switch), then
 * the nav-time title, and only then the placeholder a session with no name
 * deserves.
 */
internal fun sessionHeaderTitle(
    pendingTitle: String?,
    liveTitle: String?,
    trackedSessionId: String?,
    onScreenSessionId: String,
    learnedTitle: String?,
    navTitle: String?,
    newSessionLabel: String,
): String = pendingTitle?.takeIf { it.isNotBlank() }
    ?: liveTitle?.takeIf { trackedSessionId == onScreenSessionId }?.takeIf { it.isNotBlank() }
    ?: learnedTitle?.takeIf { it.isNotBlank() }
    ?: navTitle?.takeIf { it.isNotBlank() }
    ?: newSessionLabel

/**
 * What the rename editor opens with for the session on screen: this session's
 * title only. The display chain ([sessionHeaderTitle]) ends in fallbacks — the
 * nav-time snapshot, then the placeholder — that are not this session's name,
 * and the editor used to seed from that display value, so tapping the title of
 * a session with no name of its own offered the placeholder (or a stale
 * snapshot from a switch window) as a name to save. The seed keeps the
 * own-title links only — the optimistic rename, the live title once adopted,
 * the learned name — and is empty when the session has none.
 */
internal fun sessionRenameSeed(
    pendingTitle: String?,
    liveTitle: String?,
    trackedSessionId: String?,
    onScreenSessionId: String,
    learnedTitle: String?,
): String = pendingTitle?.takeIf { it.isNotBlank() }
    ?: liveTitle?.takeIf { trackedSessionId == onScreenSessionId }?.takeIf { it.isNotBlank() }
    ?: learnedTitle?.takeIf { it.isNotBlank() }
    ?: ""

/**
 * Record the tracked session's live title onto the tab that owns it.
 *
 * Upstream's titlebar has one tab store and the session the route names, so
 * `currentTab()` and the header can never disagree
 * (`titlebar.tsx:209-233`, `:477`). The two reads are split across a Compose
 * frame here, so this is where the pairing is re-established: the write is
 * gated on the **tracked session id** rather than on the route's id, so the
 * window between "the route moved" and "the view model caught up" can no longer
 * file one session's name under another tab.
 *
 * A blank title **clears** the slot instead of leaving the previous value:
 * a session that reports no title has none, and keeping a name it never owned
 * is exactly the desync this replaces. Returns the input list unchanged when
 * nothing moves, so the caller can skip the write.
 */
internal fun sessionTitleSync(
    tabs: List<SessionTabRef>,
    trackedSessionId: String?,
    liveTitle: String?,
): List<SessionTabRef> {
    if (trackedSessionId == null) return tabs
    val index = tabs.indexOfFirst { it.id == trackedSessionId }
    if (index < 0) return tabs
    val next = liveTitle?.takeIf { it.isNotBlank() }
    if (tabs[index].title == next) return tabs
    val out = tabs.toMutableList()
    out[index] = out[index].copy(title = next)
    return out
}

/**
 * Mirror a rename onto the open tab that owns [sessionId].
 *
 * The Home rename path (`HomeRowActions.rename`) does not run through
 * [sessionTitleSync] — that effect only fires while a session route is up
 * (`currentSession != null`), so a rename made on Home left every open tab
 * on the old name even though the view model already knew the new one. The
 * strip rename (`onRenameSessionTab`) never had this gap because it writes
 * the tab itself; this is the same write for the Home path. Returns the
 * input list unchanged when no tab owns the session, so the caller can skip
 * the write.
 */
internal fun applyTabRename(
    tabs: List<SessionTabRef>,
    sessionId: String,
    title: String,
): List<SessionTabRef> {
    val index = tabs.indexOfFirst { it.id == sessionId }
    if (index < 0 || tabs[index].title == title) return tabs
    val out = tabs.toMutableList()
    out[index] = out[index].copy(title = title)
    return out
}

/**
 * What the shell's own startup path costs, and on which thread each step is
 * allowed to run.
 *
 * The v64 cold start on emulator-5558 (`research/v64_verify/5558/00_launch.png`,
 * the 「OpenCode isn't responding」 at 3:16:57) reported
 * `Input dispatching timed out (Application does not have a focused window)`
 * with `firstWindowDrawn=false` and `mNumDrawnWindows=0` — the window was never
 * drawn at all, so nothing on screen could be blamed. The dominant term is dex
 * class verification, which ART performs lazily **on the thread that first
 * touches a class**: the whole startup graph is loaded and verified inside the
 * first composition. That part belongs to the build (`isMinifyEnabled = false`
 * and no baseline profile, `app/build.gradle.kts:41`), not to this file.
 *
 * What *is* this file's is the disk I/O it adds to the same window: a
 * `SharedPreferences` first read is a synchronous file read plus an XML parse,
 * and the shell performed several of them before the first frame. Each step is
 * listed here so the list is the thing a test can pin, and so a new startup read
 * cannot be added silently — [onMainThread] is the assertion that no disk-backed
 * step is left behind on the main thread.
 */
internal object ColdStart {

    /** What a step of the launch path has to touch to do its job. */
    enum class Cost {
        /** No I/O: pure in-memory state the first frame can render. */
        MEMORY,

        /** A `SharedPreferences` read — a synchronous disk read plus a parse. */
        DISK,

        /** Class loading / verification. ART does it on the touching thread. */
        CLASS_LOAD,
    }

    data class Step(val id: String, val cost: Cost, val deferred: Boolean)

    /**
     * The steps this shell owns on the launch path. The two `DISK` steps this
     * file can move were moved to `Dispatchers.IO` in the change that listed
     * them; the third is the one read the theme cannot be painted without.
     */
    val steps: List<Step> = listOf(
        // `HomeProjectStore`'s record (projects + MRU + tombstones): read by
        // `OcApp`'s `LaunchedEffect`, never by the constructor.
        Step("home_projects", Cost.DISK, deferred = true),
        // `LastRouteStore.load()` — the "restore the last page" route. Read by
        // `AppScaffold`'s `LaunchedEffect`, not from a `remember`.
        Step("nav_route_restore", Cost.DISK, deferred = true),
        // `SettingsStore`'s own read plus the `LocaleManager` write in its
        // `init`, and the `AppearancePrefs` read that shares its file. The theme
        // cannot be painted without it, so it is the one disk read that stays.
        Step("settings", Cost.DISK, deferred = false),
        // Everything the first composition loads: `OcTheme`, `AppScaffold`, the
        // nav graph, the strips. Not deferrable, and not a bug on its own — it
        // is the cost the build's profile is meant to shrink.
        Step("compose_graph", Cost.CLASS_LOAD, deferred = false),
        Step("tab_state", Cost.MEMORY, deferred = false),
    )

    /** Disk-backed steps that still run on the main thread. */
    fun onMainThread(): List<Step> = steps.filter { it.cost == Cost.DISK && !it.deferred }
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
    // v64 cold start: the record is a `SharedPreferences` read plus a JSON parse,
    // and the first frame is what the ANR watchdog is already watching for
    // (`research/v64_verify/5558/00_launch.png` — 「OpenCode isn't responding」
    // with the window never drawn). It runs off the main thread; the 项目 column
    // fills in a frame later, which is the same answer a first run gives.
    LaunchedEffect(homeProjects) {
        withContext(Dispatchers.IO) { homeProjects.restore() }
    }

    OcTheme(
        isDark = dark,
        themeId = settings.themeId,
        uiFont = uiFontFamily(settings.uiFont),
        codeFont = codeFontFamily(settings.codeFont, settings.uiFont),
    ) {
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
            // A-5: the MCP event revision, settled. Both consumers are dialog
            // layers (the settings Extensions panel and the session MCP
            // chooser) that live *below* [DialogHost], so the value is
            // published here — above the host — rather than threaded through
            // two hosts that do not own the view model. Settling once, at the
            // source, is upstream's shape: `settle(key, load)` runs in the data
            // layer, and every consumer just reacts to the value
            // (`client/src/solid/data.ts:218-230,1287-1290`).
            val mcpRevisionFlow = remember(chatViewModel) {
                chatViewModel?.mcpRevision ?: NO_MCP_REVISION
            }
            val settledMcpRevision = rememberSettledRevision(
                mcpRevisionFlow.collectAsStateWithLifecycle().value,
            )
            CompositionLocalProvider(
                LocalTimelineDisplayPrefs provides settings.timelineDisplayPrefs(),
                LocalSessionCommandIntents provides intents,
                LocalMcpRevision provides settledMcpRevision,
            ) {
                // The layer host owns the stack; [AppScaffold] lives *inside* it so the
                // global keybind hook and every route share one controller.
                //
                // v46 tD_58: the in-app notification banner rides *above* the host
                // in this column instead of overlaying it — an overlay with no
                // scrim pressed straight onto the page and covered the Projects
                // title. In flow the banner takes its own band and pushes every
                // route's content down while it is up (it renders nothing when
                // the feed is empty, so the band costs zero then); it stays a
                // sibling of the dialog host — a dialog's scrim never clips it —
                // and a child of `OcTheme`, so it paints with the live tokens.
                //
                // v68 (5560 56 px jump): the push-down is therefore known and
                // accepted — the band appearing/disappearing moves the page by
                // its height, and a tap aimed in that moment can land wrong. An
                // overlay would re-cover the v46 title, and a permanently
                // reserved band would tax every short window for the v51
                // reason; the band stays transient (8 s, `BannerPolicy`) and
                // in flow.
                Column(Modifier.fillMaxSize()) {
                    NotificationBanner(
                        chatViewModel = chatViewModel,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .windowInsetsPadding(WindowInsets.statusBars)
                            // v54 (`research/v54_verify/tB/tB_69_after_undo.png`):
                            // the band clears the status bar and nothing else, so
                            // in a desktop / freeform window — which reports **no**
                            // top inset and paints the — ▭ ✕ caption over the app's
                            // own top — it landed on the caption row: the caption's
                            // ✕ sat on 「全部清除」 and its back chevron on the
                            // notification icon, both untappable. Same judgement the
                            // tab strip and the standalone terminal route already
                            // make ([desktopCaptionReserve]), applied to the one host
                            // that owns the whole top of the window and had none.
                            // The two insets are mutually exclusive by construction
                            // (the reserve is non-zero only when the status bar
                            // reports zero), so they never stack.
                            .padding(top = desktopCaptionReserve())
                            // v51: the outer's own 6 dp vertical margin counted
                            // toward the same push as the band itself (see
                            // [BannerBand]).
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                    Box(Modifier.fillMaxWidth().weight(1f)) {
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
                    }
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
    // B-6: the closed-tab stack (`shell/tabs/closed.ts`). Every *user* close
    // records the tab; the cleanup paths (session deleted, server changed) go
    // through `removeClosedTabs` instead — upstream draws the same line
    // (`tabs.tsx:363-366`: "Cleanup paths … are not recorded"). Keyed by the
    // server like upstream's per-server stack: a session id from another server
    // is not restorable here.
    val closedTabs = remember(serverKey) { mutableStateListOf<ClosedTab>() }
    val closeTab: (SessionTabRef, Int) -> Unit = { tab, index ->
        // The index a restored tab goes back to is relative to the tab list at
        // close time; `SessionTabs.nextAfterClose` already moved the route, so
        // the stack only has to remember where the tab sat.
        val next = ClosedTabs.push(closedTabs.toList(), tab, index)
        closedTabs.clear()
        closedTabs.addAll(next)
    }
    // The route on top — the palette host kind (Home has no file category) and
    // `terminal.toggle` both need it, and the nav listener is the only place that
    // knows it.
    var currentRoute by remember { mutableStateOf(Route.HOME) }
    val titleFlow = chatViewModel?.currentTitle
    val currentSessionTitle =
        if (titleFlow == null) null else titleFlow.collectAsStateWithLifecycle().value
    // Which session the live title above belongs to. Read as a flow, not off
    // the plain `ChatViewModel.sessionID` field: a composition that captured the
    // field across a session switch kept the id it had when it was created, and
    // the tab strip then wrote one session's name onto another's tab (v64).
    val trackedSessionIdFlow = chatViewModel?.trackedSessionID ?: NO_TRACKED_SESSION_ID
    val trackedSessionId by trackedSessionIdFlow.collectAsStateWithLifecycle()

    // ---- A-2: the tab strips' `unread` / `loading` badge --------------------
    // `SessionTabIndicator` owns the derivation (upstream
    // `shell/layout/project-avatar-state.ts:26-49`); this is its data source,
    // assembled once into a `SessionDotSources` so the strip, the session drawer
    // and Home's session rows read the *same* inputs.
    //
    // Three unread sources, all per session:
    // - the pending asks (`ChatViewModel.sessionAsks`, upstream's location-wide
    //   `session.permission.list` / `session.form.list`) — the only source that
    //   answers for a session this client is not observing, which is the whole
    //   point of a *row*'s dot;
    // - the notification feed the banner renders — the in-app ledger
    //   (`AppNotifier.viewed` is set by the same "the user opened that session"
    //   event upstream acks), which can report a foreign session's ask while a
    //   stream is open;
    // - the server's own row watermark (`ChatViewModel.sessionUnread`,
    //   `time.idle > time.viewed` — the TUI's rule), which is what still works
    //   for a turn that settled while this shell had no stream at all.
    //
    // The run status belongs to the **tracked** session: `ChatViewModel` observes
    // exactly one session at a time, so only that session's tab can report it,
    // and inventing a value for the others would be a lie the strip would render.
    val notifications by (chatViewModel?.notifications ?: NO_NOTIFICATIONS)
        .collectAsStateWithLifecycle()
    val sessionUnread by (chatViewModel?.sessionUnread ?: NO_SESSION_UNREAD)
        .collectAsStateWithLifecycle()
    val sessionAsks by (chatViewModel?.sessionAsks ?: NO_SESSION_ASKS)
        .collectAsStateWithLifecycle()
    val pendingPermission by (chatViewModel?.pendingPermission ?: NO_PENDING_PERMISSION)
        .collectAsStateWithLifecycle()
    val pendingForm by (chatViewModel?.pendingForm ?: NO_PENDING_FORM)
        .collectAsStateWithLifecycle()
    // `ChatViewModel.state` is a combined `ChatState` that changes on every
    // message/part event, so it is mapped down to the one field the badge reads
    // and de-duplicated: the strip recomposes when the run starts or stops, not
    // on every streamed token.
    val trackedRunStatusFlow = remember(chatViewModel) {
        chatViewModel?.state?.map { it.runStatus }?.distinctUntilChanged() ?: NO_RUN_STATUS
    }
    val trackedRunStatus by trackedRunStatusFlow.collectAsStateWithLifecycle(ChatStore.RunStatus.IDLE)
    val dotSources = SessionDotSources(
        trackedSessionID = trackedSessionId,
        trackedPermission = pendingPermission != null,
        trackedForm = pendingForm != null,
        asks = sessionAsks,
        autoApprove = settings.autoAcceptPermissions,
        notifications = notifications,
        unread = sessionUnread,
        trackedRunning = trackedRunStatus == ChatStore.RunStatus.RUNNING,
    )
    val tabBadge: (SessionTabRef) -> SessionTabBadge = { tab -> dotSources.badge(tab.id) }

    // W7: the desktop tab bar shows one label per open tab, and the only *live*
    // title the shell has is the tracked session's (`ChatViewModel.currentTitle`,
    // fed by `observeSession` and by the rename/event paths). Record it on the
    // entry that owns it, so a tab keeps its last known name after the user
    // switches away, and a rename lands on the tab that is on screen. This is
    // the same single store the bottom strip renders — no second data flow.
    //
    // The `trackedSessionId` key is what makes it safe: `currentTitle` can still
    // hold the *previous* session's title for the compositions between the route
    // change and `SessionScreen`'s `observeSession`, and that title must not be
    // written onto the newly opened tab. Keying on the id (rather than reading
    // the plain `sessionID` field) also re-runs the effect when the view model
    // finally adopts the route's session, which is the moment its own title
    // becomes trustworthy — the case that left a tab named for the session the
    // user had just left (v64).
    LaunchedEffect(chatViewModel, currentSession, trackedSessionId, currentSessionTitle) {
        if (currentSession == null) return@LaunchedEffect
        val next = sessionTitleSync(openSessions.toList(), trackedSessionId, currentSessionTitle)
        if (next == openSessions.toList()) return@LaunchedEffect
        openSessions.clear()
        openSessions.addAll(next)
    }

    // "Restore the last page" (PLAN §3, `DesktopMemoryRouter`): Home stays the
    // start destination and the stored page is pushed once on top, so Back
    // always has Home underneath. `null` (first run / page gone) → stay Home.
    //
    // v64 cold start: `load()` reads `SharedPreferences`, and calling it from a
    // `remember` puts that disk read on the main thread inside the first
    // composition — ahead of the first frame. It is read once per launch
    // (`LastRouteStore`'s own contract), so the restore is driven from an effect
    // on the IO dispatcher instead, and the one-shot `navRestoreSettled` gate
    // keeps the notification navigation below behind it so the restore still
    // lands underneath.
    //
    // v97 N-1 (`research/v97_verify/5560_sweepA/REPORT_v97b.md`): the same gate
    // has to hold the **write-back** below too, because the read is the slow
    // half of the round trip and the destination listener is not. Sequence on
    // a cold start, all inside the first frame:
    //   1. this effect is remembered — its body is *dispatched*, not run;
    //   2. `NavHost`'s body sets the graph (`NavHost.kt:502`), which navigates
    //      to `Route.HOME`;
    //   3. the effects run: the `DisposableEffect` below registers the
    //      destination listener, which is *immediately* called back with Home
    //      (`NavController.addOnDestinationChangedListener` informs the new
    //      listener of the current state) — an ungated `save("home", null)`
    //      overwrites `oc_last_route=session` here, and `apply()` has already
    //      updated the in-memory prefs;
    //   4. only then does the dispatched read run, and it finds `home` — so
    //      `target == Route.HOME`, the branch below never navigates, and the
    //      value on disk stays `home` forever after.
    // The read therefore decides the initial route, and the write-back waits for
    // it (`NavRestore.toStore(settled = …)`).
    var restoreTarget: String? by remember { mutableStateOf(null) }
    var navRestoreSettled by remember { mutableStateOf(false) }
    LaunchedEffect(lastRoute, serverKey) {
        val stored = withContext(Dispatchers.IO) { lastRoute?.load() }
        restoreTarget = NavRestore.target(stored, serverKey)
        navRestoreSettled = true
    }
    // v50 LOW-2a: the restore can land on a session the server no longer lists
    // (deleted while the process was gone) — the nav listener below gives it a
    // tab before anything has read the catalogue, so the prune runs once more
    // right after the restored destination. Assigned below (the prune reads tab
    // state declared further down), called here: a `LaunchedEffect` body runs
    // after the whole composition, so the assignment is already in place.
    var pruneRestoredTabs: () -> Unit = {}
    LaunchedEffect(restoreTarget, navRestoreSettled) {
        val target = restoreTarget
        if (navRestoreSettled && target != null && target != Route.HOME) {
            navController.navigate(target) { launchSingleTop = true }
            pruneRestoredTabs()
        }
    }
    // Every destination is persisted as it is reached. Transient pages
    // (new-session / settings / terminal) map to null and leave the stored value
    // untouched, so a cold start lands on the page *before* them.
    //
    // v97 N-1: nothing is persisted until `navRestoreSettled` — see the comment
    // on the gate above for the ordering this undoes. The rest of the listener
    // (the visible session, the tab the strip renders) runs for the first
    // destination as it always did; only the *write* waits, and by the time the
    // restore has landed the listener has already recorded the session it
    // navigated to.
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
                // A-2: the route is what decides "the user is looking at this
                // session" — upstream's `viewedInCurrentSession`
                // (`notification.tsx:221-223`, `location.pathname ===
                // sessionHref(key, sessionID)`) — so it is reported here, where
                // it is known *synchronously* before the destination composes
                // (`SessionScreen`'s own entry effect acknowledges the unread
                // watermark, and that ack is gated on this value).
                chatViewModel?.setSessionVisible(currentSession)
                if (route == Route.SESSION && !sessionId.isNullOrEmpty() &&
                    openSessions.none { it.id == sessionId }
                ) {
                    // `tabSeedTitle`, not the live title: the listener runs
                    // before the destination composes, so `currentTitle` is
                    // still the previous session's and would name this tab
                    // after it. The per-id name the view model already learned
                    // is safe here and lands the label in the same frame.
                    openSessions.add(
                        SessionTabRef(
                            sessionId,
                            tabSeedTitle(
                                trackedSessionId,
                                sessionId,
                                currentSessionTitle,
                                chatViewModel?.sessionTitle(sessionId),
                            ),
                        ),
                    )
                }
                NavRestore.toStore(navRestoreSettled, route, sessionId)
                    ?.let { lastRoute?.save(it.route, it.sessionId) }
            }
        }
        navController.addOnDestinationChangedListener(listener)
        onDispose { navController.removeOnDestinationChangedListener(listener) }
    }
    // B-2: a tapped system notification asked for a session. The `PendingIntent`
    // only starts `OcActivity`, which cannot address the NavController (it lives
    // here), so the id arrives through the process-wide `NotificationNavigation`
    // channel; the restore effect above runs first, so the notification ends up
    // on top of the stack — which is where the user asked to be.
    //
    // v64 (`research/v64_verify/5558/17b-18b`): consuming the request when it is
    // *read* loses a tap whose navigate does not survive — the app came back to
    // the page the tester had left open (the empty new-session draft) instead of
    // the session, and the tester had to pick the tab by hand. So the request is
    // kept until the session is genuinely the destination on screen: this effect
    // re-runs on every destination change, checks [NotificationNavigation.arrived]
    // first, and only then clears the request. A navigation that some other
    // effect lands on top of therefore re-issues the tap rather than dropping it.
    val pendingNotificationSession by NotificationNavigation.pending.collectAsStateWithLifecycle()
    val backStackEntry by navController.currentBackStackEntryAsState()
    LaunchedEffect(pendingNotificationSession, backStackEntry?.destination?.route, navRestoreSettled) {
        val id = pendingNotificationSession ?: return@LaunchedEffect
        // The restore is an IO read now, so it is no longer first by
        // declaration order; without this gate a cold start could navigate to
        // the notification's session first and then push the restored page on
        // top of it, undoing the ordering this effect's comment describes.
        if (!navRestoreSettled) return@LaunchedEffect
        if (NotificationNavigation.arrived(backStackEntry?.arguments?.getString(Route.ARG_ID))) {
            NotificationNavigation.consume()
            Log.d(SystemNotifier.TAG, "nav: notification session $id is on screen")
            return@LaunchedEffect
        }
        Log.d(SystemNotifier.TAG, "nav: opening notification session $id (from ${backStackEntry?.destination?.route})")
        runCatching {
            navController.navigate(NavRestore.session(serverKey, id)) { launchSingleTop = true }
        }.onFailure { error ->
            Log.w(SystemNotifier.TAG, "nav: could not open notification session $id", error)
        }
    }
    // `isDesktop()` upstream is a width test (`session/model.ts:35`): wide screens
    // dock the terminal as a bottom panel of the session body
    // (`session/screen.tsx:507-540`), narrow ones get the mobile four-tab view
    // instead, where the terminal is one of the tabs.
    val wideScreen = LocalConfiguration.current.screenWidthDp >= SESSION_CHIP_MIN_WIDTH_DP
    // W7/B-16: the session tab bar's **placement** — one decision for both
    // strips. Upstream keeps the bar mounted on every form and only moves it
    // down when the stored position says so: `bottom = mobile() &&
    // mobileTitlebarPosition() === "bottom"` (`titlebar.tsx:64-65`,
    // `shell/shell.tsx:33`). `appearance.tabLayout == "vertical"` is that stored
    // position here (the General page's「底部导航」switch and the Experimental
    // Tabs row are two views of it), so below the breakpoint the strip is at the
    // bottom while the value is `vertical` and at the top otherwise — and a
    // desktop width always takes the top placement, exactly as before. The width
    // test is the shared `MobileForm.isDesktop` (`session/model.ts:35`, 768 dp)
    // used by the session screen and the drawer, not a second copy of the number.
    val desktopTabs = MobileForm.isDesktop(LocalConfiguration.current.screenWidthDp)
    val bottomTitlebar = !desktopTabs && bottomTabStrip
    val topTitlebar = !bottomTitlebar
    // The docked/closed state belongs to the *session* and not to the route
    // stack, so it is hoisted here: the floating chip, the palette's
    // `terminal.toggle` row and the `Ctrl+`` chord now drive one flag instead of
    // two behaviours for the same command (the chord used to push the
    // full-screen route while the chip docked the pane). Keyed by session id, so
    // entering another session starts closed — same as upstream's per-session
    // terminal state.
    var terminalDocked by rememberSaveable(currentSession) { mutableStateOf(false) }
    // B-17: the debug bar's visibility — upstream `state.debugTools`
    // (`shell/shell.tsx:28,42-47`), a plain in-memory shell flag, so it is not a
    // setting and does not persist. It *does* survive a configuration change
    // (`rememberSaveable`), which a debug surface should.
    var debugBarOpen by rememberSaveable { mutableStateOf(false) }
    // The bar's two live inputs. `providerMetrics` is the fold over the frame
    // stream (`ChatViewModel`); the message list is the history projection's
    // source, read through the same `state` flow the timeline renders.
    val providerMetrics by (chatViewModel?.providerMetrics ?: NO_PROVIDER_METRICS)
        .collectAsStateWithLifecycle()
    val debugBarChatState by (chatViewModel?.state ?: NO_CHAT_STATE)
        .collectAsStateWithLifecycle()
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
    // `workspace.new` («New worktree», `palette.ts:40`): upstream's palette row
    // runs the same `createWorktree` the new-session selector's create stop does
    // (`workspaces/create.ts`), scoped to the project the palette row carries
    // (`palette.ts:35` `project`). Android's palette has no project picker, so
    // the dialog resolves it from the MRU directory — the same context the
    // draft scope uses — and the created worktree becomes the new session's
    // destination.
    //
    // The dialog is rendered here rather than through `DialogHost`: it draws its
    // own scrim and dismisses on a tap outside the card, and a layer mounted
    // inside the host would dim the screen twice (the host's scrim plus its
    // own) while filling the viewport would also defeat the host's own
    // tap-outside test (`scrimTapDismisses`).
    var worktreeCreateOpen by remember { mutableStateOf(false) }
    // v68: one-shot handle for the create record below — the new-session
    // list's backstop only admits what this device recorded.
    val recordContext = LocalContext.current
    val worktreeRecordStore = remember(recordContext) { NewSessionWorktreeStore(recordContext) }
    val onNewWorkspace: () -> Unit = {
        if (chatViewModel == null) onNewSession() else worktreeCreateOpen = true
    }
    if (worktreeCreateOpen) {
        WorktreeCreateDialog(
            sourceDirectory = homeProjects.last(),
            serverStore = serverStore,
            onCreated = { directory ->
                worktreeCreateOpen = false
                // v68: the new-session list's backstop only admits what this
                // device recorded — record the palette create too (no branch
                // is picked on this path), so the fresh worktree lists even
                // when the server inventory has not caught up yet.
                worktreeRecordStore.record(directory, null)
                // Same MRU touch as Home's add-project entry, so the next open
                // (and the new-session draft scope) sees it.
                homeProjects.touch(directory)
                navController.navigate(Route.newSession(directory)) { launchSingleTop = true }
            },
            onDismiss = { worktreeCreateOpen = false },
        )
    }
    // Opening a tab **replaces** the session entry instead of stacking one per
    // tab, which is what upstream's tab nav does (`titlebar.tsx` navigates the
    // tab href against the shell route, so Back from any tab lands on Home) and
    // what the palette's previous/next already does above.
    //
    // v51 MED-3 (`research/v51_verify/tC/tC_52→54`): the stacking made the delete
    // of the **active** session land somewhere arbitrary. The delete path backs
    // out with `popBackStack()` (SessionScreen owns the write and the back-out),
    // and with one entry per visited tab that pop returned to *a session the user
    // had been on earlier in the run* — a route the nav listener then gave a tab
    // of its own, so the deleted tab's slot came back bound to another session
    // with a title that had not arrived yet (「Untitl…」) instead of closing.
    // With a single session entry the same pop lands on Home, deterministically.
    val openSession: (String) -> Unit = { id ->
        navController.navigate(NavRestore.session(serverKey, id)) {
            popUpTo(Route.HOME)
            launchSingleTop = true
        }
    }
    // B-6: one close path for all three entry points (desktop strip, mobile
    // drawer, `file.close` chord). `removeTab` (`tabs.tsx:180-203`): the
    // neighbouring tab takes over when the closed one was on screen — right
    // first, then left, then Home. A user close also records the tab on the
    // closed stack; the cleanup paths (session deleted / server changed) call
    // `SessionTabs.close` directly and stay unrecorded, exactly like upstream.
    val onCloseSessionTab: (SessionTabRef) -> Unit = { tab ->
        val index = openSessions.indexOfFirst { it.id == tab.id }
        val closed = SessionTabs.close(openSessions.toList(), tab.id, currentSession)
        if (index >= 0) closeTab(openSessions[index], index)
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
    }
    // v49 BUG-2 (`research/v49_verify/tC/v49_42/43/44_stale_tab.png`): a deleted
    // session's tab has to close **here** — the `sessionsRevision` prune below
    // asks the server, and a server that still answers with the deleted row
    // leaves the tab up for good. v70 P2: it is one decision for every delete
    // path (Home's row menu, the session route's ⋮ and drawer cards), and the
    // v63 BUG-1 shape — `afterDeleteFromElsewhere` — so the closed stack is
    // purged with the tab (a recorded close of a deleted session must not stay
    // reopenable) and the route follows the tab that takes over, exactly like
    // upstream's `notifySessionTabsRemoved` + `nextTabAfterClose`.
    val forgetDeletedSessionTab: (String) -> Unit = { id ->
        val result = SessionTabs.afterDeleteFromElsewhere(
            tabs = openSessions.toList(),
            closed = closedTabs.toList(),
            id = id,
            activeId = currentSession,
        )
        openSessions.clear()
        openSessions.addAll(result.tabs)
        closedTabs.clear()
        closedTabs.addAll(result.closed)
        if (currentSession == id) {
            when (val target = result.target) {
                TabCloseTarget.Stay -> Unit
                // The session on screen was the last tab: back out to Home, the
                // destination its route was pushed from (`openSession` pops up to
                // it). A `navigate(HOME)` here would *stack* a second Home over
                // the deleted session's entry, so Back would walk back into a
                // session that no longer exists.
                TabCloseTarget.Home -> {
                    currentSession = null
                    navController.popBackStack()
                }
                is TabCloseTarget.Open -> openSession(target.id)
            }
        }
    }
    // v70 P2 (`research/v70_verify`: 5556 lost the card, 5560 kept it): the
    // session route's delete card and its write, owned by this scope instead of
    // `SessionScreen`'s own `remember`s. The screen's composition is not a place
    // a `DELETE`'s answer can be kept in — a recomposed or re-created screen
    // took the card (and the reason it had to show) with it, and the confirmed
    // half never reached the tab set. `SessionDeleteHost` carries the state, the
    // ⋮ and the drawer both arm the one card, and a confirmed delete comes back
    // through [forgetDeletedSessionTab] — so the tab closes even when the screen
    // that asked is gone.
    val sessionDeleteHost: SessionDeleteHost? = remember(chatViewModel, serverKey) {
        val viewModel = chatViewModel ?: return@remember null
        SessionDeleteHost(
            write = { id, directory, onResult -> viewModel.deleteSession(id, directory, onResult) },
            onConfirmed = forgetDeletedSessionTab,
        )
    }
    // B-20: one reorder path for the desktop strip and the mobile drawer. Both
    // placements render the same `openSessions`, so mutating it here is the
    // whole feature — the dragged order is the list order, and it survives
    // switching between the top strip and the drawer. `SessionTabs.reorder` is
    // upstream's `arrayMove` (`tab-strip.tsx:334-352`).
    val onMoveSessionTab: (Int, Int) -> Unit = { from, to ->
        if (from in openSessions.indices && to in openSessions.indices && from != to) {
            val next = SessionTabs.reorder(openSessions.toList(), from, to)
            openSessions.clear()
            openSessions.addAll(next)
        }
    }
    // B-20: the tab's long-press menu Rename — `session.update` through the same
    // `ChatViewModel.renameSession` the Home session rows use, mirrored onto the
    // tab so the strip shows the new name without waiting for the next
    // catalogue read (upstream `tab-nav.tsx:64-67` `onRename`). A blank title is
    // refused by the view model, so the tab keeps the name it had.
    //
    // v82 BUG-B: this was the one rename surface with **no** failure report at
    // all — `ok` and `GONE` were the only branches, so a server refusal
    // (`500` on a session whose project directory is missing) left the tab on
    // its old name and said nothing, which is the same complaint the session
    // header's inline title produced. Home's row and the session route both
    // report through the shared `renameFailureToast`; the strip does now too
    // (and `renameFailureToast` says **which** shape it was, v81 P1).
    val onRenameSessionTab: (SessionTabRef, String) -> Unit = { tab, title ->
        val viewModel = chatViewModel
        val trimmed = title.trim()
        if (viewModel != null && trimmed.isNotEmpty()) {
            viewModel.renameSession(tab.id, trimmed) { outcome ->
                val ok = outcome == SessionRenameOutcome.RENAMED
                if (ok) {
                    val index = openSessions.indexOfFirst { it.id == tab.id }
                    if (index >= 0) openSessions[index] = openSessions[index].copy(title = trimmed)
                } else if (outcome == SessionRenameOutcome.GONE) {
                    // v81 P2: the tab's session is gone server-side — the tab is
                    // what the strip would otherwise keep offering, so it closes
                    // through the same decision a confirmed delete uses.
                    forgetDeletedSessionTab(tab.id)
                }
                renameFailureToast(appContext, outcome)
            }
        }
    }
    // `tab.reopenClosed` (`tabs.tsx:374-390`): pop the newest entry that is not
    // open again, put it back at `min(entry.index, tabs.length)` and navigate to
    // it. A stack whose entries are all open again pops nothing and the route
    // does not move.
    val onReopenClosedTab: () -> Unit = {
        val result = ClosedTabs.take(closedTabs.toList(), openSessions.map { it.id }.toSet())
        closedTabs.clear()
        closedTabs.addAll(result.stack)
        val entry = result.entry
        if (entry != null) {
            val at = ClosedTabs.insertIndex(entry, openSessions.size)
            openSessions.add(at, entry.tab)
            openSession(entry.tab.id)
        }
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
        // `mcp.toggle` (`use-session-commands.tsx:209-214` `chooseMcp`): the MCP
        // chooser dialog. List + toggle ride the settings Extensions panel's
        // data seam, built exactly like `showSettingsDialog`'s bridge; full
        // management (add / remove / resource browse) stays in that panel.
        onToggleMcp = {
            val source: SettingsServerData = SettingsServerBridge(
                serverStore,
                v2 = SettingsV2Delegate.forHost(
                    generation = chatViewModel?.generation?.value ?: ServerGeneration.UNKNOWN,
                    server = serverStore?.server,
                ),
            )
            dialog.show(DialogSize.LARGE) {
                McpChooserDialog(source = source, onDismiss = { dialog.close() })
            }
        },
        // B-5: `file.close` — the active session tab, through the one close path
        // (records the closed stack, then moves the route).
        onCloseTab = {
            openSessions.firstOrNull { it.id == currentSession }?.let(onCloseSessionTab)
        },
        // B-17: the debug bar's flag is App-level (upstream keeps it in the
        // shell), so the palette row and the chord share this one flip.
        onToggleDebugBar = { debugBarOpen = !debugBarOpen },
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
    // Export rides the view model's transport seam
    // (`fetchExportFrameResult`), the same generation-dispatched raw read
    // SessionScreen uses — no separate v1-only client anymore (on v2
    // `GET /session/:id` is SPA HTML). The Result keeps the transport reason
    // (and names a never-sent read, v40 tC) so the row's toast says which of
    // the two it was instead of the bare「导出失败」.
    val homeRowActions: HomeRowActions? = remember(chatViewModel) {
        val viewModel = chatViewModel ?: return@remember null
        HomeRowActions(
            rename = { id, title, cb ->
                viewModel.renameSession(id, title) { outcome ->
                    val ok = outcome == SessionRenameOutcome.RENAMED
                    // The tab strip is not on the `sessionTitleSync` path
                    // while Home is up (`currentSession == null` skips it),
                    // so a Home rename mirrors onto the open tab here — the
                    // same write `onRenameSessionTab` does for a strip rename.
                    if (ok) {
                        val next = applyTabRename(openSessions.toList(), id, title.trim())
                        if (next != openSessions.toList()) {
                            openSessions.clear()
                            openSessions.addAll(next)
                        }
                    } else if (outcome == SessionRenameOutcome.GONE) {
                        // v81 P2: the session is gone server-side, so the strip
                        // must not keep offering its tab — the same decision a
                        // confirmed delete takes (`forgetDeletedSessionTab`).
                        forgetDeletedSessionTab(id)
                    }
                    cb(outcome)
                }
            },
            export = { row -> viewModel.fetchExportFrameResult(row.id, row.directory) },
            delete = { row, cb ->
                viewModel.deleteSession(row.id, row.directory) { ok ->
                    // v63 BUG-1: Home's delete goes through the one tab
                    // decision like every other close (see
                    // [forgetDeletedSessionTab]) — the splice, the closed-stack
                    // purge (a recorded close of a deleted session must not be
                    // reopenable) and the neighbouring target. It used to drop
                    // the tab alone, which left the dead tab up on the strip for
                    // a session the server had already forgotten, and its
                    // recorded close resurrectable.
                    if (ok) forgetDeletedSessionTab(row.id)
                    cb(ok)
                }
            },
        )
    }
    // Tabs track the server's session set: a session deleted through the header
    // ⋮ flow (or by another client) must drop its tab, not linger until ×.
    //
    // v50 LOW-2b: the sessions this run created that the catalogue has not
    // reported yet. A list read issued *before* the create landed answers
    // without the new row, and the prune below would drop the tab the new
    // session's own navigation had just added (「新建会话 tab 不出现」,
    // `research/v50_verify/tD/v50_23/24/30`). The entry is dropped as soon as
    // a read that landed does carry the id, so this only ever delays a prune —
    // it never keeps a genuinely dead session alive, which is what the restore
    // prune (a) depends on.
    val pendingCreatedSessions = remember(serverKey) { mutableStateListOf<String>() }
    // `sessionsRevision` bumps on every session created/deleted/renamed frame —
    // and on this client's own successful `DELETE /session/:id`
    // (`ChatViewModel.deleteSession`), which is the only way the frame reaches a
    // shell that opens no stream off the session route.
    //
    // v50 LOW-2a: the same prune also runs **once on entry**. A cold start
    // restores `LastRouteStore`'s route into whatever session was on screen when
    // the process died, and the nav listener gives that id a tab before anything
    // has read the catalogue — so a session deleted while the app was gone came
    // back as a tab over 「This session cannot be found」. A cold start bumps no
    // revision, so without this one-shot read the dead tab outlives the run.
    // [pruneRestoredTabs] runs the same prune once more right after the
    // restored destination is navigated to (the one-shot above can land before
    // the nav listener has given the restored id its tab).
    val pruneTabsAgainstServer: () -> Unit = prune@{
        val viewModel = chatViewModel ?: return@prune
        if (openSessions.isEmpty() && closedTabs.isEmpty()) return@prune
        viewModel.listSessions { list ->
            // A failed read answers the last good rows, not a fresh list
            // (`ChatViewModel.listSessions`): pruning against those would
            // drop a tab whose session was created since — so the prune
            // waits for a read that actually landed.
            if (viewModel.sessionsLoadFailed.value) return@listSessions
            val ids = list.map { it.id }
            pendingCreatedSessions.retainAll { id -> ids.none { it == id } }
            val result = SessionTabs.pruneAgainstServerList(
                tabs = openSessions.toList(),
                closed = closedTabs.toList(),
                serverIds = ids,
                // Only the ids this run created and the catalogue has not
                // reported yet: a read that raced the create must not take the
                // tab back.
                //
                // v51 LOW-1 (`research/v51_verify/tD/v51_57_deadtab_restart.png`):
                // the session on screen used to be protected here too, which is
                // what made the restore prune above a no-op exactly when it
                // mattered — the dead restored route *is* the current session,
                // so 「This session cannot be found」 kept its tab for the whole
                // run (`v50_00` is the same picture). A read that landed and does
                // not list the id is the server's confirmation that the session
                // is gone; the block below then backs the route out to Home.
                // Anything this run created (new session / fork / import /
                // notification) is in [pendingCreatedSessions], so a session that
                // legitimately exists but has not been reported yet is still safe.
                protectedIds = pendingCreatedSessions.toSet(),
            )
            if (result.prunedSessionIds.isEmpty()) return@listSessions
            openSessions.clear()
            openSessions.addAll(result.tabs)
            closedTabs.clear()
            closedTabs.addAll(result.closed)
            // The restored route pointed at a session the server no longer
            // lists, so its tab is gone and the screen under it can only show
            // the error. Back out to Home rather than leave the shell there.
            if (currentSession != null && currentSession in result.prunedSessionIds) {
                currentSession = null
                navController.navigate(Route.HOME) { launchSingleTop = true }
            }
        }
    }
    pruneRestoredTabs = pruneTabsAgainstServer
    LaunchedEffect(chatViewModel) {
        val viewModel = chatViewModel ?: return@LaunchedEffect
        pruneTabsAgainstServer()
        viewModel.sessionsRevision.collect { pruneTabsAgainstServer() }
    }
    // A-2: while no session screen is mounted, the **session list** is the only
    // carrier of a settled turn's unread state (`ChatViewModel.sessionUnread` —
    // the row's `time.idle` vs `time.viewed`), because this shell opens no stream
    // off that route (`OcActivity`'s documented design). So every return to the
    // foreground re-reads it once: a turn that finished while the app was in the
    // background then shows its dot on the tab strip and on the drawer's next
    // open, instead of waiting for a route change that may never come. One
    // `GET /api/session` per activation — not a poll.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(chatViewModel, lifecycleOwner) {
        val viewModel = chatViewModel
        val observer = if (viewModel == null) {
            null
        } else {
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_START) viewModel.listSessions {}
            }
        }
        if (observer != null) lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { if (observer != null) lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // SSH tunnels outlive any one session, so the host is app-scoped and only
    // published on the session route. Nothing submits to it yet: the
    // `ServerConfigStore.sshEntries()` persistence has not landed, so the
    // session fallback stays dormant until auto-reconnect wires up.
    val sshScope = rememberCoroutineScope()
    val sshHost = remember(sshScope) { SshTunnelHost(sshScope) }

    // B-5: the dispatcher behind every chord outside the seven `GlobalChord`
    // branches above. App-level ids run here (view model / store / dialog); the
    // ids whose action lives inside a screen are **gated on the route that owns
    // it** and forwarded through `SessionCommandIntents.requestCommand`, so a
    // press on the wrong screen is a no-op instead of a stale action that fires
    // when the screen is next composed.
    val onChordCommand: (String) -> Unit = { id ->
        val onSession = currentRoute == Route.SESSION
        when (id) {
            // --- App-level: the action is reachable from anywhere ---
            "review.toggle" -> intents.toggleReview()
            "fileTree.toggle" -> intents.openFileTree()
            "session.aside" -> intents.askAside(null)
            "mcp.toggle" -> actions.onToggleMcp()
            "permissions.autoaccept" -> store.setSwitch(
                "settings-auto-accept-permissions",
                !settings.autoAcceptPermissions,
            )
            "tab.reopenClosed" -> onReopenClosedTab()
            "file.close" -> actions.onCloseTab()
            // B-17: the debug bar is reachable from every route (upstream mounts
            // it in the shell), so the flip lives here rather than behind a
            // session guard.
            "debugBar.toggle" -> actions.onToggleDebugBar()

            // --- App-level session lifecycle (upstream disables these without a
            // session id: `disabled: !actions.session.identity.params.id`) ---
            "session.undo" -> if (onSession) chatViewModel?.undoSession()
            "session.redo" -> if (onSession) chatViewModel?.redoSession()
            "session.compact" -> if (onSession) chatViewModel?.compactSession()
            "session.background" -> if (onSession) chatViewModel?.backgroundSessionTools()
            "session.fork" -> if (onSession) showForkDialog(dialog, chatViewModel)
            "session.copyID" -> if (onSession) copySessionID(appContext, chatViewModel)

            // --- composer cycles: pure next-value helpers + the chips' own
            // setters, so a chord and the chip can never disagree ---
            "agent.cycle" -> if (onSession) {
                val next = ChordCycles.nextAgent(
                    agents = chatViewModel?.availableAgents?.value.orEmpty(),
                    current = chatViewModel?.selectedAgent?.value,
                    direction = 1,
                )
                chatViewModel?.selectAgent(next)
            }

            "agent.cycle.reverse" -> if (onSession) {
                val next = ChordCycles.nextAgent(
                    agents = chatViewModel?.availableAgents?.value.orEmpty(),
                    current = chatViewModel?.selectedAgent?.value,
                    direction = -1,
                )
                chatViewModel?.selectAgent(next)
            }

            "model.variant.cycle" -> if (onSession) {
                val model = chatViewModel?.selectedModel?.value
                val variants = model?.let { (providerID, modelID) ->
                    chatViewModel?.availableProviders?.value?.model(providerID, modelID)?.variants
                }.orEmpty()
                chatViewModel?.selectVariant(
                    ChordCycles.nextVariant(variants, chatViewModel?.selectedVariant?.value),
                )
            }

            // --- screen-local: the screen that owns the state runs it ---
            "session.export",
            "message.previous",
            "message.next",
            "terminal.new",
            "model.choose",
            // B-18/B-19: the browser rows (`SessionScreen` owns `browserUrl` and
            // the pane's reload request) and the composer-editor rows (the SAF
            // launcher and `shellMode` live inside `Composer`, reached through
            // the screen's external request counters).
            "browser.open",
            "browser.reload",
            "file.attach",
            "prompt.mode.shell",
            "prompt.mode.normal",
            -> if (onSession) intents.requestCommand(id)

            // `input.focus` is registered on the session route
            // (`use-session-commands.tsx:385-391`) and on the draft
            // (`new-session/commands.tsx:31-37`). Only the session half is wired:
            // this shell's draft route is a workspace picker with **no prompt
            // field** (there is no draft model — see `NewSessionScreen`'s KDoc), so
            // there is nothing on that route to focus.
            "input.focus" -> if (onSession) intents.requestCommand(id)

            // `session.location.cycle` is the draft's workspace cycle
            // (`new-session/commands.tsx:46-54`); on a session route there is no
            // location to cycle.
            "session.location.cycle" -> if (currentRoute == Route.NEW_SESSION) {
                intents.requestCommand(id)
            }

            else -> Unit
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
        // B-5: the ids outside the seven branches above.
        onCommand = onChordCommand,
        // W5-S (D4): the recorded override table is what the chords are matched
        // against now — defaults stay in `SHORTCUT_ROWS`, user values win.
        keybinds = settings.keybinds,
    ) {
        // M3: the optional bottom title bar (§9b) has to sit *under* the
        // navigation content, so the graph is wrapped in a column. W7 adds the
        // top placement at the *top* of the same column; B-16 makes the two
        // placements the two stops of one setting (`topTitlebar`/`bottomTitlebar`).
        // v98 fix2: the mobile tabs sheet is an **overlay**, so it is composed
        // as a *sibling of the shell column* inside the `Box` below — the same
        // shape as upstream's `position: fixed` sheet
        // (`_upstream/packages/app/src/shell/mobile-drawer.css:1-31`).
        //
        // Why it has to be a real sibling and not a value passed down: composed
        // inside the column, the sheet's `Box(Modifier.fillMaxSize())` host is a
        // non-weighted *flow* child, the `Column` measures it first, it swallows
        // the column's remaining height, the `weight(1f)` NavHost is left at
        // 0 px and the bottom bar is placed at y=0
        // (`research/v97_verify/5560_sweepA/REPORT_v97b.md` N-3). v97 tried to
        // dodge that with a `CompositionLocal`, which carries a value and so
        // never re-parents — the host was still composed at the consumer, and
        // unconditionally, which is what left the mobile form a 13-node shell
        // with the bar at y=0..84
        // (`research/v97_verify/5560_v98/5560_v98_drawer2/REPORT_DRAWER2.md` §2).
        // Here the strip *hands over* the sheet and this scope *invokes* it, so
        // the sheet's layout node is a genuine second child of the `Box`.
        val mobileDrawerMount = remember { MobileDrawerMount() }
        Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(OcTheme.colors.bgDeep)) {
            // v57 P0 「短窗 composer 塌成麦克风条」: with the soft keyboard up on a
            // short window the top strip is duplicated chrome — the session
            // header row below it already names the session, and the strip's
            // ~48 dp came straight out of the composer's row (measured
            // 1440x810 @420dpi: `maxHeight` 146.7 dp without the IME, 33.9 dp
            // with it; `research/v57_verify/tE/v57_2x_kbd_*.png`). The strip
            // yields while the IME owns the window, and comes back with it.
            // On a healthy window `ShellChromeBudget.yieldsToIme` is false and
            // this is inert.
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            val yieldsToIme = ShellChromeBudget.yieldsToIme(
                imeVisible = WindowInsets.isImeVisible,
                windowHeightDp = LocalConfiguration.current.screenHeightDp,
            )
            // W7 §9b.1: upstream renders `TitlebarTabStrip` first in the shell on
            // every route — Home included, where the Home button is the pressed
            // item and no tab is active (`titlebar.tsx:344,441-458`). It closes
            // through `SessionTabs`, the same decisions the bottom strip uses.
            // B-16: on a desktop width this is unconditional (the stored position
            // is mobile-only upstream); below it, it is the "top" stop.
            if (topTitlebar && !yieldsToIme) {
                DesktopTabStrip(
                    sessions = openSessions,
                    activeSessionId = currentSession,
                    onHome = { onHome() },
                    onOpenSession = { tab -> openSession(tab.id) },
                    // B-6: the shared close path — records the tab for
                    // `tab.reopenClosed` and moves the route.
                    onCloseSession = onCloseSessionTab,
                    onNewSession = onNewSession,
                    // v35 tA-terminal: the palette entry in the strip's trailing
                    // slot — upstream's `TitlebarRight` is where the "search and
                    // command palette button" is mounted, and this strip is the
                    // titlebar row on Android. Host-specific, like the chord:
                    // Home gets the Home palette, a session the session one.
                    onOpenPalette = { openPalette(paletteHost()) },
                    tabBadge = tabBadge,
                    // B-20: long-press drag reorder + the long-press Rename
                    // menu, both on the one `openSessions` the drawer shares.
                    onMoveSession = onMoveSessionTab,
                    onRenameSession = onRenameSessionTab,
                )
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    // The strip above already paints behind the status bar, so the
                    // destinations — which all apply `WindowInsets.safeDrawing`
                    // themselves (`HomeScreen.kt:144`, `SessionScreen.kt:803`) —
                    // must not pad for that same inset a second time. B-16: the
                    // condition follows the strip, which now also stands on the
                    // mobile form's top stop.
                    //
                    // v63 P0: it follows the strip's **render**, not the setting —
                    // a strip that yielded to the IME
                    // (`ShellChromeBudget.yieldsToIme`) consumes nothing, and
                    // consuming anyway left the destination with a zero top
                    // inset, so on a landscape phone with the keyboard up the
                    // session header row drew under the clock and the LTE /
                    // signal / battery icons (`research/v63_verify/5560/70_land_nokb.png`
                    // vs `72_land_ime.png`).
                    .then(
                        if (ShellChromeBudget.topStripOwnsStatusBar(topTitlebar, yieldsToIme)) {
                            Modifier.consumeWindowInsets(WindowInsets.statusBars)
                        } else {
                            Modifier
                        },
                    ),
            ) {
                NavHost(navController = navController, startDestination = Route.HOME) {
                    composable(Route.HOME) {
                        HomeScreen(
                            chatViewModel = chatViewModel,
                            openSessionIds = openSessions.map { it.id }.toSet(),
                            onOpenSession = { id -> openSession(id) },
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
                                    // A create that answers with something the
                                    // session route cannot address (blank, or a
                                    // path) must not navigate at all — the
                                    // `NavController` throws on the unmatched
                                    // destination (v65 P0 crash).
                                    val route = NavRestore.sessionOrNull(serverKey, id)
                                    if (route == null) return@NewSessionScreen
                                    // v50 LOW-2: shield the new session's tab
                                    // from a catalogue read that raced the create.
                                    if (!pendingCreatedSessions.contains(id)) {
                                        pendingCreatedSessions.add(id)
                                    }
                                    navController.navigate(route) {
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
                            // `/btw` side questions (`ui/session/btw`): same host
                            // pattern as the session actions above — this shell holds
                            // the view model and the route id, the header only renders
                            // the entry it publishes. `activate(id)` also abandons the
                            // outgoing session's in-flight ask (`session/btw/model.ts`
                            // `onCleanup`); `dismiss()` closes the panel for the same
                            // reason `actionsHost.dismiss()` does — it is a modal layer
                            // now, so left open it would sit over the *new* session
                            // showing that session's (empty) slot. The answer itself
                            // survives per session key, so reopening shows it again.
                            val btwHost = remember(viewModel, serverKey) {
                                SessionBtwHost(context = context, viewModel = viewModel)
                            }
                            LaunchedEffect(id) {
                                btwHost.activate(id)
                                btwHost.dismiss()
                            }
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
                                    // v50 LOW-2: the fork bumps
                                    // `sessionsRevision` *before* this
                                    // navigation, so the prune it triggers can
                                    // read the catalogue without the new row.
                                    pendingCreatedSessions.add(forked)
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
                                    // v35 tA-terminal: the header's ⋮ menu gets
                                    // the palette + debug-bar rows from here —
                                    // the same host-object reason as the three
                                    // below (`SessionScreen` is owned elsewhere
                                    // and calls `SessionHeader` with a fixed
                                    // argument list).
                                    LocalPaletteHost provides PaletteHost(
                                        open = { kind -> openPalette(kind) },
                                        debugBarOpen = debugBarOpen,
                                        toggleDebugBar = actions.onToggleDebugBar,
                                    ),
                                    LocalSessionActionsHost provides actionsHost,
                                    LocalSessionBtwHost provides btwHost,
                                    LocalSshTunnelHost provides sshHost,
                                    // v70 P2: the delete card's state and write
                                    // live above this destination (see
                                    // `sessionDeleteHost`), so a recomposition or
                                    // a re-created session screen cannot take an
                                    // unanswered `DELETE`'s reason with it.
                                    LocalSessionDeleteHost provides sessionDeleteHost,
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
                                        // A1/A8: the summary's server row still
                                        // reads the active server entry directly;
                                        // export goes through `ChatViewModel`
                                        // `fetchExportFrame` (generation-aware).
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
                                        // v43: the wide-screen `>_` entry chip
                                        // lives in the timeline pane so a docked
                                        // review panel cannot sit under it.
                                        onToggleTerminalDock = { terminalDocked = !terminalDocked },
                                        // P1-C: the composer's `/open` re-opens the
                                        // app palette files-only (upstream
                                        // `file.open` → `DialogCommandPalette`
                                        // files view), and `/terminal` drives the
                                        // same App-level toggle as the palette row
                                        // and `Ctrl+`` (upstream `terminal.toggle`:
                                        // close when open, open + focus otherwise).
                                        onOpenFilePicker = actions.onOpenFilePicker,
                                        onToggleTerminal = onTerminalToggle,
                                        // `/mcp` opens the same chooser dialog as
                                        // the palette's `mcp.toggle` row.
                                        onToggleMcp = actions.onToggleMcp,
                                        // B-12: the popover's keybind column draws
                                        // the effective chords — the same table the
                                        // global chord handler above matches.
                                        keybindOverrides = settings.keybinds,
                                        // B-13: the model picker's「管理模型」row —
                                        // upstream's `dialog.model.manage`
                                        // (`select-dialog.tsx:544-556`) opens the
                                        // visibility/connection manager, which on
                                        // Android is the Models page of Settings.
                                        onManageModels = {
                                            showSettingsDialog(
                                                dialog,
                                                store,
                                                serverStore,
                                                chatViewModel,
                                                initialTab = SettingsTab.MODELS,
                                            )
                                        },
                                        // B-16: the「底部导航」stop, carried into
                                        // the phone body so the **header row**
                                        // moves with the strip this scaffold
                                        // already places. Upstream moves one
                                        // element — the whole `header
                                        // [data-slot="titlebar-v2"]` gets
                                        // `order-last` (`titlebar.tsx:150-164`) —
                                        // and the strip and the header are its
                                        // two halves here, so both follow it.
                                        titlebarBottom = bottomTitlebar,
                                        // v39: the header ⋮ menu's
                                        // 「导入会话…」 row created a session —
                                        // open it, the way upstream's project
                                        // import ends in `openProjectSession`
                                        // and the Home entry here already
                                        // navigates (`HomeScreen.onSessionImported`).
                                        onSessionImported = { imported ->
                                            // v51: the imported row has not been
                                            // in a catalogue read yet, so it gets
                                            // the same shield a create gets.
                                            if (!pendingCreatedSessions.contains(imported)) {
                                                pendingCreatedSessions.add(imported)
                                            }
                                            navController.navigate(
                                                NavRestore.session(serverKey, imported),
                                            ) { launchSingleTop = true }
                                        },
                                    )
                                }
                                // M5 phase 1 entry (the 28 dp ghost `>_` chip) used
                                // to be rendered here, `Alignment.TopEnd` of the
                                // session body. v43: the docked review panel is the
                                // timeline row's trailing sibling, so the chip landed
                                // on the panel's tab row and covered the 「文件」tab
                                // (`research/v43_verify/tE/v43_32_panel_wide.png`).
                                // It is now mounted by `SessionScreen` inside the
                                // timeline pane — same 88 dp top, same 8 dp end
                                // inset, same ≥768dp gate — through
                                // `onToggleTerminalDock` below, so no panel width
                                // can put it under anything.
                                // W6: the fork picker / move surface (F-4).
                                // Mounted as the **last** sibling of
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
                        //
                        // v40 tB, the same bug in a second host: `safeDrawing` knows
                        // nothing about a **window caption**, and a desktop/freeform
                        // window reports no top inset, so its 36 dp — ▭ ✕ band kept
                        // the top of TerminalPanel's tab row: the whole row was dead
                        // while `Ctrl+K` answered in the same frame
                        // (`research/v40_verify/tB/`). The reserve and its judgement
                        // are shared with the top tab strip, not re-derived here.
                        val captionReserve = desktopCaptionReserve()
                        Box(
                            Modifier
                                .fillMaxSize()
                                .windowInsetsPadding(WindowInsets.safeDrawing)
                                .padding(top = captionReserve),
                        ) {
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
            // B-17: the debug bar. Upstream floats it over the shell
            // (`debug-bar.tsx:533` `fixed bottom-3 right-3`) and mounts it on
            // every route; the Android form is the same placement in this
            // column — a full-width bar directly above the bottom navigation (or
            // at the very bottom when the strip is at the top), so it never
            // covers the composer or the tab strip.
            if (debugBarOpen) {
                DebugBar(
                    live = providerMetrics,
                    messages = debugBarChatState.messages,
                    sessionTokens = debugBarChatState.usageTokens,
                    onClose = { debugBarOpen = false },
                )
            }
            // W7/B-16: the「底部导航」placement — the **mobile** bottom stop
            // (upstream `titlebar.tsx:64-65`: `bottom = mobile() && …`), the
            // complement of the top strip above. A desktop width always takes the
            // top placement, so this bar never renders there.
            if (bottomTitlebar) {
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
                    onCloseSession = onCloseSessionTab,
                    onNewSession = {
                        onNewSession()
                    },
                    // v35 tA-terminal: the bottom placement's palette entry —
                    // the same trailing slot as the top strip above.
                    onOpenPalette = { openPalette(paletteHost()) },
                    tabBadge = tabBadge,
                    // B-6: the mobile entry for `tab.reopenClosed` — the drawer's
                    // last row, shown only while the closed stack has an entry
                    // (upstream's chord has no phone equivalent).
                    closedTab = closedTabs.lastOrNull(),
                    onReopenClosed = {
                        onReopenClosedTab()
                    },
                    // B-20: the drawer reorders/renames the same `openSessions`
                    // the desktop strip renders.
                    onMoveSession = onMoveSessionTab,
                    onRenameSession = onRenameSessionTab,
                    // v98 fix2: the strip owns the sheet but does not compose it
                    // — the mount below is the one place it is composed, as a
                    // sibling of the shell column.
                    drawerMount = mobileDrawerMount,
                )
            }
        }
            // v98 fix2: the sheet, composed here — the **last** child of the
            // wrapping `Box`, so it stacks over the column (bar included, as
            // before) while taking no part in the column's height allocation.
            // The slot is only composed while the sheet is open, so nothing of
            // it exists in the layout when it is closed.
            val mobileSheet = mobileDrawerMount.content
            if (mobileDrawerMount.open && mobileSheet != null) {
                Box(Modifier.fillMaxSize()) { mobileSheet() }
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
    /**
     * B-5: every other wired chord, keyed by the [GlobalChord.commandId] it
     * serves. The scaffold's dispatcher runs the App-level ones directly and
     * forwards the screen-local ones to `SessionCommandIntents.requestCommand`.
     */
    onCommand: (String) -> Unit = {},
    /** `Settings.keybinds` — the recorded override table (empty = all defaults). */
    keybinds: Map<String, String>,
    content: @Composable () -> Unit,
) {
    val active = LocalDialogActive.current
    val view = LocalView.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    // Re-claim focus whenever the dialog stack drains. A single
    // `LaunchedEffect(Unit)` request is not enough: the palette autofocuses its
    // search field (`shell/commands/dialog.tsx:130`), so after closing a dialog
    // the root focusable no longer held focus and **every** chord stayed dead
    // until the app restarted (verifier F-1). Keying on `active` re-arms the
    // handler on open and on close, and stays out of the way while a dialog is
    // up (the dialog owns the keys then).
    //
    // v81 P1 「重开会话后编辑器失焦需二次点击」: …and the re-claim is a **claim**,
    // not a steal. The request above was unconditional, and the root is an
    // ancestor of the whole navigation tree: on the drain that follows a layer
    // the app navigated out of — a card dismissed as the session opens, whose
    // exit hold keeps `LocalDialogActive` true for another ~100 ms — asking the
    // root to focus made the composer's *focused* field inactive, i.e. the caret
    // disappeared while the keyboard stayed up (`research/v80_verify/5554/
    // 27_480_recheck.png` against `26_480_typed.png`), and only a fresh tap gave
    // it back (`28_480_refocus.png`). `hasFocus` is the whole subtree, so the
    // claim is made exactly when nothing in this window owns the focus — the
    // state the effect was written for (the F-1 palette case: its search field
    // is unmounted, so *nothing* is focused) — and a live caret is left alone.
    // The chords do not depend on the root's own `isFocused`: it sits in the
    // focus chain of whatever is focused below it, so its `onPreviewKeyEvent`
    // still runs first (and `rootChordYieldsToTextInput` still stands it down
    // while a text field is being typed into).
    var rootHasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(active, rootHasFocus) {
        if (rootClaimsFocus(active = active, subtreeHasFocus = rootHasFocus)) {
            runCatching { focus.requestFocus() }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .onFocusChanged { rootHasFocus = it.hasFocus }
            .onPreviewKeyEvent { event ->
                if (active) {
                    // Escape is IME-only (`DialogEscape`): it collapses the
                    // keyboard and never dismisses the layer. This hook only
                    // sees the focused-root case — a dialog with no autofocus
                    // (the settings surface) keeps focus out here — so there is
                    // no close to run; dismissal is the scrim, BACK
                    // (`DialogHost`'s `BackHandler`) or a dialog's own handler.
                    return@onPreviewKeyEvent collapseImeOnEscape(event, isImeVisible(view)) {
                        keyboard?.hide()
                    }
                }
                val chord = globalChord(event, keybinds)
                // v40 tB: the root runs before the focused field, so it stands
                // down for a bare key while one is typing — see
                // [rootChordYieldsToTextInput]. The IME is the only signal a key
                // handler can read ([isImeVisible]); a field focused with a
                // hardware keyboard alone leaves it down, which is the state that
                // was broken anyway.
                if (rootChordYieldsToTextInput(
                        chord = chord,
                        key = event.key,
                        ctrl = event.isCtrlPressed,
                        meta = event.isMetaPressed,
                        shift = event.isShiftPressed,
                        alt = event.isAltPressed,
                        textInputActive = isImeVisible(view),
                    )
                ) {
                    return@onPreviewKeyEvent false
                }
                when (chord) {
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

                    // B-5: every other wired chord. The scaffold's dispatcher
                    // owns the routing (App-level action vs. screen-local
                    // request), so this handler never grows a branch per id.
                    null -> false

                    else -> {
                        onCommand(chord.commandId)
                        true
                    }
                }
            },
    ) {
        content()
    }
}

/**
 * `command.tsx:13-14` + `use-session-commands.tsx:248-408`.
 *
 * B-5: every wired id has a member here, and [commandId] is the id whose action
 * the handler runs. Ids that share one action share a member (`session.new` and
 * `tab.new` are both [NEW_SESSION]); [commandId] names the canonical one.
 */
enum class GlobalChord(val commandId: String) {
    /** `mod+k` / `mod+shift+p` — `DEFAULT_PALETTE_KEYBIND`. */
    PALETTE("command.palette"),

    /** `mod+p` — `file.open` (`:314-321`), the files-only palette. */
    PALETTE_FILES("file.open"),

    /** `mod+comma` — `settings.open`. */
    SETTINGS("settings.open"),

    /** `mod+n` (`tab.new`) / `mod+shift+s` (`session.new`). */
    NEW_SESSION("session.new"),

    /** `ctrl+`` — `terminal.toggle` (`:354-368`). */
    TERMINAL("terminal.toggle"),

    /**
     * `session.search` (`search-controller.ts:184-192`) — `mod+f` opens the
     * timeline search bar (W5-T).
     */
    SESSION_SEARCH("session.search"),

    /** `session.summary.toggle` (`session/summary/popover.tsx:17-34`) — `mod+shift+y`. */
    SUMMARY("session.summary.toggle"),

    // --- B-5: the ids that used to be「displayed, editable, dead」---

    /** `review.toggle` — flips the review panel (`SessionCommandIntents.toggleReview`). */
    REVIEW("review.toggle"),

    /** `fileTree.toggle` — opens the review panel's Files tab. */
    FILE_TREE("fileTree.toggle"),

    /** `session.undo` — `ChatViewModel.undoSession`. */
    SESSION_UNDO("session.undo"),

    /** `session.redo` — `ChatViewModel.redoSession`. */
    SESSION_REDO("session.redo"),

    /** `session.compact` — `ChatViewModel.compactSession` (`POST …/compact`). */
    SESSION_COMPACT("session.compact"),

    /** `session.background` — `ChatViewModel.backgroundSessionTools`. */
    SESSION_BACKGROUND("session.background"),

    /** `session.fork` — the fork picker (`fork-dialog.tsx:60-95`). */
    SESSION_FORK("session.fork"),

    /** `session.export` — the session screen's export flow. */
    SESSION_EXPORT("session.export"),

    /** `session.copyID` — the tracked session id onto the clipboard. */
    SESSION_COPY_ID("session.copyID"),

    /** `session.aside` — the `/btw` side question (bare form opens the panel). */
    SESSION_ASIDE("session.aside"),

    /** `message.previous` — timeline navigation (upstream `navigateMessage(-1)`). */
    MESSAGE_PREVIOUS("message.previous"),

    /** `message.next` — timeline navigation (upstream `navigateMessage(1)`). */
    MESSAGE_NEXT("message.next"),

    /** `mcp.toggle` — the MCP chooser dialog. */
    MCP_TOGGLE("mcp.toggle"),

    /** `permissions.autoaccept` — flips `SettingsStore.autoAcceptPermissions`. */
    AUTO_ACCEPT("permissions.autoaccept"),

    /** `input.focus` — focuses the composer (session) / prompt (draft). */
    INPUT_FOCUS("input.focus"),

    /** `terminal.new` — opens the terminal pane and creates a tab. */
    TERMINAL_NEW("terminal.new"),

    /** `file.close` — closes the active session tab (upstream `tab.close`). */
    FILE_CLOSE("file.close"),

    /** `session.location.cycle` — cycles the draft's workspace. */
    LOCATION_CYCLE("session.location.cycle"),

    /** `model.choose` — opens the model picker. */
    MODEL_CHOOSE("model.choose"),

    /** `model.variant.cycle` — steps the model's reasoning-effort variant. */
    VARIANT_CYCLE("model.variant.cycle"),

    /** `agent.cycle` — `local.agent.move(1)`. */
    AGENT_CYCLE("agent.cycle"),

    /** `agent.cycle.reverse` — `local.agent.move(-1)`. */
    AGENT_CYCLE_REVERSE("agent.cycle.reverse"),

    /** `tab.reopenClosed` — restores the newest closed tab (`mod+shift+t`). */
    REOPEN_CLOSED("tab.reopenClosed"),

    // --- B-18/B-19: the browser + composer-editor commands ---

    /**
     * `browser.open` (`session/browser/model.ts:55-64`) — shows the embedded
     * browser pane (at its last URL, or empty when none).
     */
    BROWSER_OPEN("browser.open"),

    /** `browser.reload` (`session/browser/pane.tsx:45-50`) — the pane's reload. */
    BROWSER_RELOAD("browser.reload"),

    /** `file.attach` (`composer/model.ts:411-418`) — the attachment picker. */
    FILE_ATTACH("file.attach"),

    /** `prompt.mode.shell` (`composer/model.ts:419-426`) — shell mode on. */
    PROMPT_MODE_SHELL("prompt.mode.shell"),

    /** `prompt.mode.normal` (`composer/model.ts:427-434`) — back to prompt mode. */
    PROMPT_MODE_NORMAL("prompt.mode.normal"),

    /**
     * B-17: `debugBar.toggle` (`shell/shell.tsx:49-58`) — shows/hides the debug
     * bar. Upstream declares no keybind, so the default is blank and only a
     * recorded override reaches this branch.
     */
    DEBUG_BAR("debugBar.toggle"),
}

/**
 * The command ids each [GlobalChord] serves, and the **single source** of the
 * default chords: [SHORTCUT_ROWS] is the settings panel's table, so a row and
 * this handler can never advertise different keys. The ids that table does not
 * carry (`session.search`, `session.summary.toggle`, and B-18's two browser
 * rows) get theirs from [EXTRA_GLOBAL_CHORDS].
 *
 * B-5: every [WIRED_SHORTCUT_IDS] id is listed here, and every listed id has a
 * handler — the App-level ones run in `onChordCommand` below, the screen-local
 * ones ride `SessionCommandIntents.requestCommand` to the screen that owns the
 * state. `SettingsStructureTest.everyShortcutRowIsWired` pins the settings side;
 * `Wave23aSettingsPaletteTerminalTest` scans this table's source for the ids.
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
    "review.toggle" to GlobalChord.REVIEW,
    "fileTree.toggle" to GlobalChord.FILE_TREE,
    "session.undo" to GlobalChord.SESSION_UNDO,
    "session.redo" to GlobalChord.SESSION_REDO,
    "session.compact" to GlobalChord.SESSION_COMPACT,
    "session.background" to GlobalChord.SESSION_BACKGROUND,
    "session.fork" to GlobalChord.SESSION_FORK,
    "session.export" to GlobalChord.SESSION_EXPORT,
    "session.copyID" to GlobalChord.SESSION_COPY_ID,
    "session.aside" to GlobalChord.SESSION_ASIDE,
    "message.previous" to GlobalChord.MESSAGE_PREVIOUS,
    "message.next" to GlobalChord.MESSAGE_NEXT,
    "mcp.toggle" to GlobalChord.MCP_TOGGLE,
    "permissions.autoaccept" to GlobalChord.AUTO_ACCEPT,
    "input.focus" to GlobalChord.INPUT_FOCUS,
    "terminal.new" to GlobalChord.TERMINAL_NEW,
    "file.close" to GlobalChord.FILE_CLOSE,
    "session.location.cycle" to GlobalChord.LOCATION_CYCLE,
    "model.choose" to GlobalChord.MODEL_CHOOSE,
    "model.variant.cycle" to GlobalChord.VARIANT_CYCLE,
    "agent.cycle" to GlobalChord.AGENT_CYCLE,
    "agent.cycle.reverse" to GlobalChord.AGENT_CYCLE_REVERSE,
    "tab.reopenClosed" to GlobalChord.REOPEN_CLOSED,
    "browser.open" to GlobalChord.BROWSER_OPEN,
    "browser.reload" to GlobalChord.BROWSER_RELOAD,
    "file.attach" to GlobalChord.FILE_ATTACH,
    "prompt.mode.shell" to GlobalChord.PROMPT_MODE_SHELL,
    "prompt.mode.normal" to GlobalChord.PROMPT_MODE_NORMAL,
    // B-17: blank default (upstream declares none), rebindable row.
    "debugBar.toggle" to GlobalChord.DEBUG_BAR,
)

/**
 * Bindings for the ids [SHORTCUT_ROWS] does not list: `session.search` is
 * upstream's `mod+f` (never a settings row) and `session.summary.toggle` is A1 —
 * the settings wave's own files are outside this wave's file set, so its panel
 * cannot show the new row yet.
 *
 * B-18 adds the two `session.browser` rows here for the same reason: the pane is
 * a URL-driven full-body overlay rather than upstream's side-panel tab, so the
 * two stay context actions instead of rebindable settings rows
 * (`SettingsModel.SHORTCUT_ROWS` keeps them in its documented dropped set, which
 * `SettingsStructureTest.everyShortcutRowIsWired` and
 * `Wave23aSettingsPaletteTerminalTest.registryCoversTheSessionGroups` pin).
 */
private val EXTRA_GLOBAL_CHORDS: Map<String, String> = mapOf(
    "session.search" to "Ctrl+F",
    "session.summary.toggle" to "Ctrl+Shift+Y",
    "browser.open" to "Ctrl+Shift+B",
    "browser.reload" to "F5",
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
internal fun bindingForSignatures(binding: String): String =
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
 * returns null, so e.g. `F1` never matches. That is the deliberate, explainable
 * half of "override in the panel, nothing happens": see
 * `research/wave5_integration.md` §6. B-18 added the one function key a binding
 * declares — `F5`, upstream's `browser.reload` (`session/browser/pane.tsx:49`) —
 * so the rest of the function row stays outside the vocabulary.
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
    // B-18: upstream's `browser.reload` is `f5` (`session/browser/pane.tsx:49`),
    // so the one function key a binding declares joins the vocabulary.
    put(Key.F5, "f5")
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
 * Whether the root [GlobalKeybindFocus] stands down for a chord typed into a
 * text field (upstream `handleKeyDown`, `command.tsx:405-411`).
 *
 * Upstream skips a shortcut typed into an editable target only when it is none
 * of: the palette chord, an `EDITABLE_KEYBIND_IDS` id, a modified
 * (`ctrl`/`meta`/`alt`) chord, Tab. A modified chord therefore fires while
 * typing — `Ctrl+K` opens the palette from the composer, `Ctrl+,` opens
 * settings — and only a bare key a field would consume yields to it (a bare
 * `E` override, Enter, the arrows). The v40 tB rename-box complaint (`Ctrl+N`
 * opening a session mid-word) is that upstream behaviour, not a bug here: the
 * desktop fires modified chords out of inputs too.
 *
 * `textInputActive` is the IME signal because a key handler cannot read focus:
 * with no field focused every chord fires, exactly as with no dialog up.
 */
internal fun rootChordYieldsToTextInput(
    chord: GlobalChord?,
    key: Key,
    ctrl: Boolean = false,
    meta: Boolean = false,
    shift: Boolean = false,
    alt: Boolean = false,
    textInputActive: Boolean,
): Boolean {
    // Nothing matched: there is nothing to stand down from. Nothing focused:
    // the field the rule protects does not exist.
    if (chord == null || !textInputActive) return false
    // The palette chord is never skipped (`isPalette`, `command.tsx:408`).
    if (chord == GlobalChord.PALETTE) return false
    // Upstream `EDITABLE_KEYBIND_IDS` (`command.tsx:24`): these ids fire while
    // typing too.
    if (chord in EDITABLE_TARGET_CHORDS) return false
    // A modified chord is the user's explicit chord, not text (`modified`,
    // `:407` — `ctrl`/`meta`/`alt`; a bare `shift` never un-yields).
    if (ctrl || meta || alt) return false
    // Tab is never skipped (`!isTab`, `:408`).
    if (key == Key.Tab) return false
    // …and a bare key only when a field would have inserted or edited text with
    // it. A binding on a key a text field never claims (F-keys, media keys)
    // still fires while typing, so the shell keeps its non-text chords.
    return keyClaimsTextInput(key)
}

/**
 * Upstream `EDITABLE_KEYBIND_IDS` (`command.tsx:24`) as [GlobalChord]s: the ids
 * whose chords fire while a text field is focused.
 */
private val EDITABLE_TARGET_CHORDS: Set<GlobalChord> = setOf(
    GlobalChord.TERMINAL,
    GlobalChord.TERMINAL_NEW,
    GlobalChord.FILE_ATTACH,
    GlobalChord.BROWSER_RELOAD,
)

/**
 * v81 P1 「重开会话后编辑器失焦需二次点击」: whether the key-chord root may take
 * the window focus for itself.
 *
 * The root's re-claim (see [GlobalKeybindFocus]) exists for the state the F-1
 * verifier found: after a dialog closed, *nothing* in the app held the focus, so
 * every chord was dead until the app restarted. It must not fire while something
 * below the root owns the focus — the root is an ancestor of the whole
 * navigation tree, and asking it to focus makes a focused composer field
 * **inactive**, i.e. it takes the caret away (the keyboard stays up, because
 * nothing hides it) and the user has to tap the field again to type
 * (`research/v80_verify/5554/26_480_typed.png` → `27_480_recheck.png` →
 * `28_480_refocus.png`). A layer dismissed as a session opens keeps
 * `LocalDialogActive` true for another ~100 ms of exit transition, which is
 * exactly the moment that drain lands in a session's entry window.
 *
 * `subtreeHasFocus` is the root's own `FocusState.hasFocus` — true for the root
 * *or any descendant*. So the claim is made only when nothing owns the focus
 * (the F-1 case), and a live caret is left alone. The chords do not need the
 * root's own `isFocused`: the root sits in the focus chain of whatever is
 * focused below it, so its `onPreviewKeyEvent` still runs first (and
 * [rootChordYieldsToTextInput] still stands it down while a field is typed
 * into).
 */
internal fun rootClaimsFocus(active: Boolean, subtreeHasFocus: Boolean): Boolean =
    !active && !subtreeHasFocus

/**
 * Keys a focused `TextField` consumes, and which must therefore not be stolen:
 * the printable keys a binding can name ([CHORD_KEY_TOKENS] — letters, digits,
 * punctuation) plus the field's own editing keys. The one function key in that
 * vocabulary, `F5` (`browser.reload`), is nobody's typing and is left out, so
 * that binding still fires while the user types.
 */
private fun keyClaimsTextInput(key: Key): Boolean =
    (CHORD_KEY_TOKENS.containsKey(key) && key != Key.F5) ||
        key == Key.Enter ||
        key == Key.Backspace ||
        key == Key.Delete ||
        key == Key.Tab ||
        key == Key.Escape ||
        key == Key.DirectionUp ||
        key == Key.DirectionDown ||
        key == Key.DirectionLeft ||
        key == Key.DirectionRight ||
        key == Key.MoveHome ||
        key == Key.MoveEnd ||
        key == Key.PageUp ||
        key == Key.PageDown

/**
 * The palette layer (§6).
 *
 * One view, three data assemblies, exactly like upstream:
 * `DialogHomeCommandPaletteV2` (commands + sessions, **no** file category),
 * `DialogCommandPaletteV2` (session / new-session: commands + sessions +
 * `/find/file`) and the `filesOnly` variant `file.open` opens
 * (`palette.ts:92-99`).
 *
 * The two upstream hosts differ in the file category and the placeholder, and
 * so do these: the command table itself is the same one for both (see the
 * `sessionCommands` note below), because the shell has a single dialog stack and
 * no per-route command ownership — a host-dependent table made the palette's
 * blank query a subset of the registry.
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
    // WAVE 2.3a §3: the session route's command rows ride the same
    // `commandEntries`/`commandFor` path as the app rows (the hidden
    // `terminal.close` row is already excluded from listing inside
    // `SessionCommands`). The shell registers **one** command table for every
    // palette host — upstream's per-route ownership has no native equivalent
    // (`AppCommands`' KDoc: one dialog stack, one list) — so Home lists the same
    // rows a session does; only FILES_ONLY drops every command, which is
    // upstream's `filesOnly()` contract (`palette.ts:92-99`). Gating the table on
    // `kind == SESSION` made the palette the Home tab strip opens a *subset* of
    // the registry: its blank query listed 11 rows instead of 35 and every
    // `session.*` row (`session.export`, `session.import`, `terminal.new`, …) was
    // unreachable and unsearchable (`research/v37_verify/tB/40_palette_export_none.png`).
    // Each wired id calls the existing endpoint; the documented stubs stay stubs
    // (log-only — no toast copy exists for them, and `res/` is off-limits for a
    // new one). Off the session route the rows keep their listing but lose their
    // action (`enabled` below) — the state their chords already have there, so a
    // Home tap can never act on a session the user is not looking at.
    val sessionCommands = remember(kind, chatViewModel, store, dialog, intents) {
        if (kind == PaletteHostKind.FILES_ONLY) return@remember emptyList()
        SessionCommands.sessionCommands(
            actions = SessionCommands.SessionCommandActions(
                onUndo = chatViewModel?.let { viewModel -> { viewModel.undoSession() } },
                onRedo = chatViewModel?.let { viewModel -> { viewModel.redoSession() } },
                // `session.compact` → `POST /api/session/{id}/compact`
                // (`use-session-commands.tsx:236`: fire-and-forget, no toast).
                onCompact = chatViewModel?.let { viewModel -> { viewModel.compactSession() } },
                // `session.background` → `POST /api/session/{id}/background`
                // (`requests/model.ts:90`), also bound to `ctrl+b` upstream.
                onBackground = chatViewModel?.let { viewModel ->
                    { viewModel.backgroundSessionTools() }
                },
                // `mcp.toggle` → the MCP chooser dialog (upstream `chooseMcp`).
                onToggleMcp = actions.onToggleMcp,
                // `ChatViewModel.forkSession` + the fork picker (reads
                // `forkMessages()`); navigation reuses the scaffold's
                // `forkedSessionID` collector, so this layer never navigates.
                // B-5: the same helper the `session.fork` chord calls, so the row
                // and the chord open one dialog.
                onFork = chatViewModel?.let { viewModel ->
                    { showForkDialog(dialog, viewModel) }
                },
                // `session.copyID`: the tracked session id onto the clipboard.
                onCopySessionID = { copySessionID(paletteContext, chatViewModel) },
                // `session.archive` has no v2 route — the palette row stays hidden.
                onArchive = null,
                // `permissions.autoaccept`: flips the stored switch.
                onToggleAutoAccept = {
                    store.setSwitch(
                        "settings-auto-accept-permissions",
                        !store.state.value.autoAcceptPermissions,
                    )
                },
                // `session.aside` — upstream's `/btw` side question. The row
                // passes no input (`null` = the bare form), and the request rides
                // `SessionCommandIntents.askAside` to the btw host: this layer is
                // composed *above* `SessionScreen` (`ui/overlay/DialogHost.kt`),
                // so `LocalSessionBtwHost` is not visible from here (see the
                // channel's KDoc).
                onAskAside = { question -> intents.askAside(question) },
                // B-5: the five ids whose action lives inside `SessionScreen` ride
                // `SessionCommandIntents.requestCommand` — the same channel the
                // chord uses, so the row and the key can never do different
                // things. The session screen stays composed under this dialog
                // layer, so the request lands immediately.
                onExport = { intents.requestCommand("session.export") },
                onPreviousMessage = { intents.requestCommand("message.previous") },
                onNextMessage = { intents.requestCommand("message.next") },
                onFocusInput = { intents.requestCommand("input.focus") },
                onNewTerminal = { intents.requestCommand("terminal.new") },
                onChooseModel = { intents.requestCommand("model.choose") },
                // B-18/B-19: the browser and composer-editor rows ride the same
                // channel — `SessionScreen` owns the pane and forwards the
                // composer requests to its external counters, so a palette row
                // and its chord can never do different things.
                onOpenBrowser = { intents.requestCommand("browser.open") },
                onReloadBrowser = { intents.requestCommand("browser.reload") },
                onAttachFile = { intents.requestCommand("file.attach") },
                onShellMode = { intents.requestCommand("prompt.mode.shell") },
                onNormalMode = { intents.requestCommand("prompt.mode.normal") },
                // `file.close` closes the active session tab — App-level state
                // (`openSessions`), reached through `ShellActions`.
                onCloseFile = actions.onCloseTab,
            ),
            stubReporter = null,
            // The rows are listed on every host, but only the session route has a
            // session screen to run them: off it their action is inert, exactly
            // like their chord (`onChordCommand`'s `onSession` gate).
            enabled = kind == PaletteHostKind.SESSION,
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
        // B-17: `debugBar.toggle` — the palette row; the chord runs the same
        // `ShellActions.onToggleDebugBar`.
        onToggleDebugBar = actions.onToggleDebugBar,
    ) + sessionCommands
    val entries = stringResolvedEntries(commands)

    val openSessionIds = remember { mutableSetOf<String>() }
    var query by remember { mutableStateOf("") }
    var baseSessions by remember { mutableStateOf<List<OpenCodeClient.Session>>(emptyList()) }
    var exactSession by remember { mutableStateOf<OpenCodeClient.Session?>(null) }
    var fileEntries by remember { mutableStateOf<List<PaletteEntry>>(emptyList()) }
    // A-3 (palette half): the clock the palette's relative-time meta cells are
    // measured against. The label used to read `System.currentTimeMillis()` inside a
    // `remember`ed lambda, which Compose cannot see: a session row's only inputs
    // are `updatedAt` and the search key, so the row was skipped and a meta cell
    // composed while the session was「just now」kept reading「just now」for as long
    // as the palette stayed open (the same freeze the drawer's `RelativeTimeText`
    // had — see `SessionScreen`'s `drawerNow`). This layer is composed exactly
    // while the palette is on the dialog stack, so the effect's lifetime *is* the
    // 「panel visible」gate: closing the palette leaves composition and cancels the
    // tick, and a nested layer (`dialog.show` replaces the stack) takes the
    // palette off the stack with it.
    var paletteNow by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            paletteNow = System.currentTimeMillis()
            delay(PALETTE_CLOCK_TICK_MS)
        }
    }
    val relativeTime = rememberRelativeTimeLabel(paletteNow)

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
    LaunchedEffect(query, kind, chatViewModel) {
        val id = query.trim()
        if (kind == PaletteHostKind.FILES_ONLY || !looksLikeSessionID(id)) {
            exactSession = null
            return@LaunchedEffect
        }
        // Generation-aware read through the view model's transport seam: the
        // bare v1 `GET /session/:id` answers SPA HTML on a v2 server and the
        // JSON parse throws.
        val fetched = chatViewModel?.fetchExportFrame(id, null)
            ?.let { frame -> runCatching { OpenCodeClient.Session.fromJson(frame.info) }.getOrNull() }
        if (query.trim() == id) exactSession = fetched
    }
    // A-3 (palette half): `relativeTime` is a key, not just a capture — it is
    // rebuilt on every clock tick, and without it here the entries (and the meta
    // cells baked into them) would keep the label they were built with.
    val sessionEntries = remember(baseSessions, exactSession, serverKey, relativeTime) {
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
        chatViewModel.searchFiles(
            query = q,
            directory = null,
            onResult = { paths ->
                if (query.trim() == q) fileEntries = filePaletteEntries(paths)
            },
        )
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
 * drawer's shared bucketing ([PaletteRelativeTime]). `Resources.getQuantityString`
 * is a plain call, so the returned lambda is usable from non-composable code.
 *
 * [now] is a parameter rather than a `System.currentTimeMillis()` read inside
 * the lambda: a clock read there is invisible to Compose, so the session rows —
 * whose only inputs are `updatedAt` and the search key — were skipped and the
 * meta cell stayed frozen at whatever bucket it was composed with. The host
 * hands in a clock that ticks while the palette is up (see `paletteNow`), and
 * `now` is a `remember` key so a tick rebuilds the lambda and, with it, the
 * entries built from it.
 */
@Composable
private fun rememberRelativeTimeLabel(now: Long): (OpenCodeClient.Session) -> String? {
    val resources = LocalContext.current.resources
    return remember(resources, now) {
        { session: OpenCodeClient.Session ->
            PaletteRelativeTime.label(
                updatedAt = session.updatedAt,
                now = now,
                justNow = resources.getString(R.string.oc_time_just_now),
                minutesAgo = { count ->
                    resources.getQuantityString(R.plurals.oc_time_minutes_ago, count, count)
                },
                hoursAgo = { count ->
                    resources.getQuantityString(R.plurals.oc_time_hours_ago, count, count)
                },
                daysAgo = { count ->
                    resources.getQuantityString(R.plurals.oc_time_days_ago, count, count)
                },
            )
        }
    }
}

/** Debounce for the palette's `/find/file` round-trip per keystroke. */
private const val PALETTE_FILE_SEARCH_DEBOUNCE_MS = 200L

/**
 * How often the palette's relative-time clock re-measures. The same 30 s the
 * drawer's clock uses (`DRAWER_CLOCK_TICK_MS`): the finest bucket the labels
 * render is a whole minute, so a coarser tick would let a row sit on「just now」
 * past its first minute.
 */
private const val PALETTE_CLOCK_TICK_MS = 30_000L

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
    /** `mcp.toggle` — the MCP chooser dialog ([McpChooserDialog]). */
    val onToggleMcp: () -> Unit,
    /**
     * B-5: `file.close` — closes the active session tab (upstream reads the
     * `tab.close` keybind for this command, `use-session-commands.tsx:324-333`).
     * The chord and the palette row share it.
     */
    val onCloseTab: () -> Unit,
    /**
     * B-17: `debugBar.toggle` (`shell/shell.tsx:49-58`) — flips the debug bar's
     * visibility. One lambda for the palette row and the chord, so both entry
     * points drive the same flag.
     */
    val onToggleDebugBar: () -> Unit,
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
 * B-5: the one `session.fork` action — the palette row and the chord both call
 * it. Reads the forkable messages, then shows [PaletteForkDialog]; the picked id
 * rides `ChatViewModel.forkSession`, whose `forkedSessionID` the session route
 * collects and navigates with, so neither caller navigates.
 */
private fun showForkDialog(dialog: DialogController, chatViewModel: ChatViewModel?) {
    val viewModel = chatViewModel ?: return
    val rows = viewModel.forkMessages()
    dialog.show(DialogSize.FIT) { controller ->
        PaletteForkDialog(
            rows = rows,
            onPick = { id ->
                controller.close()
                viewModel.forkSession(id)
            },
            onClose = { controller.close() },
        )
    }
}

/**
 * B-5: the one `session.copyID` action (`use-session-commands.tsx:295-310`
 * `copySessionID`) — the palette row and the chord both call it. A missing id or
 * clipboard reports the same failure toast upstream shows.
 */
private fun copySessionID(context: Context, chatViewModel: ChatViewModel?) {
    val id = chatViewModel?.sessionID
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    if (id.isNullOrBlank() || clipboard == null) {
        Toast.makeText(context, context.getString(R.string.common_requestFailed), Toast.LENGTH_SHORT).show()
    } else {
        clipboard.setPrimaryClip(ClipData.newPlainText("session id", id))
        Toast.makeText(context, context.getString(R.string.common_copied), Toast.LENGTH_SHORT).show()
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
    /** B-13: the page the dialog opens on (the model picker's「管理模型」row). */
    initialTab: SettingsTab = SettingsTab.Default,
) {
    // The Projects / Workspaces / Extensions reads dispatch on the delegate:
    // present ⇒ the `/api` routes, absent ⇒ the v1 unprefixed routes
    // (byte-identical to before). The delegate is offered only while the
    // active connection speaks v2 — a v1 server keeps `v2 = null` and every
    // behaviour it had.
    val settingsV2 = SettingsV2Delegate.forHost(
        generation = chatViewModel?.generation?.value ?: ServerGeneration.UNKNOWN,
        server = serverStore?.server,
    )
    dialog.show(DialogSize.X_LARGE) {
        SettingsDialogContent(
            store = store,
            // B-13: the page the caller asked for (the picker's「管理模型」row
            // lands on Models; every other opener keeps the General default).
            initialTab = initialTab,
            extra = SettingsPanelExtras(
                providers = chatViewModel?.availableProviders,
                selectedModel = chatViewModel?.selectedModel,
                serverBridge = SettingsServerBridge(serverStore, v2 = settingsV2),
                // Settings-side writes (disconnect / connect / wellknown / credential
                // rename) change provider membership but not the catalog stream, so
                // the "Connected providers: N" count would survive a tab switch with
                // its stale value. Ask the VM to re-pull so the count is durable.
                onProvidersChanged = { chatViewModel?.requestProvidersRefresh() },
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
 * survives every route and is never clipped by a dialog's scrim. It rides
 * in-flow above the host (v46 tD_58): the band it takes pushes the route
 * content down instead of pressing onto it with no scrim, and costs nothing
 * when the feed is empty. Entries raised from the moment the band mounts surface
 * whether they are `viewed` or not — v49 removed the old `!viewed` suppression,
 * which hid exactly the rounds the banner exists for: a reply that finished while
 * the user was looking at that session (2 of 7 rounds showed anything in v49 tE;
 * the rest only produced a banner after a force-stop restart). Freshness, not
 * seen-ness, decides now, via the opening retirement set ([BannerPolicy.allIds]).
 * One entry at a time (the newest),
 * because three cards stacked over the header is worse than a queue.
 *
 * 「知道了」 acknowledges the shown entry (`acknowledgeNotification(id)`): the
 * entry is dropped and its session's remaining queued entries count as seen,
 * so one tap hides the banner even when several turns of that session queued
 * up behind it (v47 tE — a bare `dismissNotification` only popped the top
 * entry and the next identical banner rendered in the same frame, so every
 * tap looked dead). The tap additionally retires the entry from the band
 * itself, so the band hides in the same frame instead of waiting for the
 * feed round-trip — and a re-raised twin racing the tap cannot keep it on
 * screen (v48 tE). v50 tE: the retirement covers the entry's whole **session**,
 * and dismiss-all the whole feed, because `viewed` no longer steers the band —
 * retiring one id at a time handed the frame straight to the identical twin
 * behind it, and the tap measured as 8.3 s, exactly like not tapping.
 * 「全部清除」 drops the whole feed
 * (`clearNotifications()`). A null [ChatViewModel] (previews,
 * deep links) renders nothing.
 *
 * v48: the band is **transient** while the feed is not. A shown entry hides
 * itself after [BannerPolicy.AUTO_DISMISS_MS], and retired entries stay
 * unviewed in the feed — the tab dots keep the truth, only the band (and the
 * layout push it causes) goes away. A genuinely new entry surfaces and
 * re-arms the timer. The text is one line (title + detail inline), so a long
 * session id ellipsizes instead of growing the band.
 *
 * Deliberately **not** wired: tapping the banner to jump to the session. Upstream's
 * `notification.action.goToSession` needs the session route and the
 * `NavController`, both of which live in `AppScaffold` — a *child* of this banner,
 * not a parent. See `research/wave5_integration.md` §4 for the option list.
 *
 * v51 `tE` (`v51_10_enter.png`, `v51_18_b1.png`): the in-flow band is right in
 * principle — an overlay with no scrim pressed onto the page and covered the
 * Projects title (v46 tD_58) — but its **size** was not. The two 48 dp action
 * targets plus 8 dp of vertical padding reserved ~64 dp of the column, about
 * 190 px on the test device, and on a short window that was the composer: the
 * input box went below the fold and the turn could not be typed at all. The band
 * is now bounded by [BannerBand] — one compact line, hard-capped, so a banner
 * can no longer push content off screen however long its label or however many
 * actions a later round adds.
 *
 * v54 `tB` (`research/v54_verify/tB/tB_69_after_undo.png`): the band cleared the
 * status bar and nothing else. A desktop / freeform window reports **no** top
 * system-bar inset — that is exactly the signal [desktopCaptionReserve] reads —
 * and the window manager paints the — ▭ ✕ caption over the app's own top, so the
 * band sat on that row: the caption's ✕ covered 「全部清除」 and its back chevron
 * the notification icon, and the caption keeps every touch in its band, so both
 * actions were dead. The band now reserves the caption the same way the tab
 * strip and the standalone terminal route do.
 */
@Composable
private fun NotificationBanner(
    chatViewModel: ChatViewModel?,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    val notifications by (chatViewModel?.notifications ?: NO_NOTIFICATIONS)
        .collectAsStateWithLifecycle()
    // v48: ids retired from the band (auto-hidden or tap-hidden). They stay
    // unviewed in the feed — only the transient band goes quiet.
    //
    // v49: seeded with the feed as it stands when the band mounts. That is what
    // lets a `viewed` entry surface at all: the old `!viewed` filter hid every
    // turn that finished while the user was looking at that session — the one
    // round the banner exists for (2 of 7 rounds popped anything in v49 tE,
    // and only a force-stop restart produced the rest). Restoring the persisted
    // feed still opens quiet, because every id in it is retired up front.
    // The state object rather than a delegated `var`: the auto-hide below has to
    // read the retirement set **when it fires**, not the one captured when the
    // timer was armed, or a tap during the eight seconds would be overwritten by
    // the timeout's own (older) copy.
    val hiddenIds = remember { mutableStateOf(BannerPolicy.allIds(notifications)) }
    val pending = BannerPolicy.select(notifications, hiddenIds.value) ?: return
    // Auto-hide ~8s after the shown entry arrives, so the layout is back to
    // no-banner even when the feed keeps the news. Keyed on the entry id: a
    // genuinely new entry restarts the wait instead of inheriting it.
    LaunchedEffect(pending.id) {
        delay(BannerPolicy.AUTO_DISMISS_MS)
        hiddenIds.value = BannerPolicy.hiddenAfterShow(notifications, hiddenIds.value, pending.id)
    }

    Row(
        modifier = modifier
            .widthIn(max = 480.dp)
            // v51: the reserved height is capped, so a banner can never push the
            // composer's input box out of a short window again (`tE`).
            .heightIn(max = BannerBand.MAX_HEIGHT)
            .clip(OcTheme.shapes.xl)
            .background(c.layer01)
            .border(0.5.dp, c.borderBase, OcTheme.shapes.xl)
            .padding(
                start = 12.dp,
                end = 4.dp,
                top = BannerBand.VERTICAL_PADDING,
                bottom = BannerBand.VERTICAL_PADDING,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // One line: the kind title with the system-notification second line
        // inline, from the one place that decides it: the session title, else
        // the session id (a foreign session's row — the raise cannot know its
        // title), else the error text (`SystemNotificationPolicy.bodyOf`).
        val detail = SystemNotificationPolicy.bodyOf(pending)
        val title = stringResource(pending.kind.titleResId())
        Text(
            text = if (detail.isNotBlank()) "$title · $detail" else title,
            color = c.textBase,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        BannerAction(label = stringResource(R.string.oc_notify_dismiss)) {
            // Retire first: the band hides in this frame, without waiting for
            // the acknowledge round-trip — and a re-raised twin racing the tap
            // (v48 tE) cannot keep it on screen. The whole session goes with it:
            // `viewed` no longer steers the band (v49), so the twin of the same
            // session that used to be hidden as "seen" would otherwise take the
            // frame immediately and make every tap look dead (v50 tE, 8.3 s with
            // and without a tap).
            hiddenIds.value = BannerPolicy.hiddenAfterDismiss(notifications, hiddenIds.value, pending)
            chatViewModel?.acknowledgeNotification(pending.id)
        }
        BannerAction(label = stringResource(R.string.oc_notify_dismiss_all)) {
            hiddenIds.value = BannerPolicy.hiddenAfterDismissAll(notifications, hiddenIds.value)
            chatViewModel?.clearNotifications()
        }
    }
}

/**
 * A 12 sp text action inside the banner (no Material button: the OC2 tokens
 * only) — v48 tE: the old ~28 dp-tall text was easy to miss by finger and by
 * automation tap.
 *
 * v51 `tE`: the target shrank from the v48 48 dp to [BannerBand.ACTION_TARGET]
 * because the target's height *is* the band's height, and a 48 dp action made
 * the band reserve ~190 px of the column — enough to push the composer's input
 * box off a short window. 40 dp stays above the platform's 32 dp comfortable
 * minimum and the two actions keep a 48 dp minimum width.
 */
@Composable
private fun BannerAction(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        color = OcTheme.colors.textMuted,
        fontSize = BannerBand.ACTION_FONT_SIZE,
        maxLines = 1,
        modifier = Modifier
            .clip(OcTheme.shapes.md)
            .clickable(role = Role.Button, onClick = onClick)
            .sizeIn(minWidth = 48.dp, minHeight = BannerBand.ACTION_TARGET)
            .padding(horizontal = 10.dp, vertical = 2.dp),
    )
}

/**
 * v51: the vertical budget of the in-flow notification band
 * ([NotificationBanner]).
 *
 * The band is a **sibling in a `Column`**, so whatever height it takes is
 * height it takes *away from the route below it* — the session composer
 * included. The v46 shape (two 48 dp actions + 8 dp padding) reserved ~64 dp,
 * and on the v51 test window that was ~190 px: the input box dropped below the
 * fold and no turn could be typed (`tE/v51_10_enter.png`,
 * `tE/v51_18_b1.png`). The numbers below bound the band at one compact line.
 *
 * Kept as a plain object so the bound is asserted in a JVM test instead of
 * being a number buried in a `Modifier`.
 */
internal object BannerBand {

    /** Vertical padding around the row. Was 8 dp — half of the whole budget. */
    val VERTICAL_PADDING = 2.dp

    /** Minimum height of an action's touch target. Was 48 dp (v48). */
    val ACTION_TARGET = 40.dp

    /** Action label size. Was 13 sp. */
    val ACTION_FONT_SIZE = 12.sp

    /**
     * Hard cap on the band, applied as `heightIn(max = …)` on the row. A later
     * round that grows the label or adds a third action therefore cannot push
     * more than this — the layout fails (ellipsis) before it pushes the composer
     * off screen.
     */
    val MAX_HEIGHT = 44.dp

    /** The height the band reserves before any cap: actions win over padding. */
    val RESERVED_HEIGHT: Dp =
        if (ACTION_TARGET + VERTICAL_PADDING * 2 > MAX_HEIGHT) {
            ACTION_TARGET + VERTICAL_PADDING * 2
        } else {
            MAX_HEIGHT
        }
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

/** Same stand-in rule as [NO_NOTIFICATIONS], for the tab badge's ask halves. */
private val NO_PENDING_PERMISSION = MutableStateFlow<OpenCodeClient.PermissionRequest?>(null)
private val NO_PENDING_FORM = MutableStateFlow<ChatStore.FormAsk?>(null)

/** Same stand-in rule as [NO_NOTIFICATIONS], for the tab badge's `loading` half. */
private val NO_RUN_STATUS = MutableStateFlow(ChatStore.RunStatus.IDLE)

/** Same stand-in rule as [NO_NOTIFICATIONS], for the badge's server-watermark half. */
private val NO_SESSION_UNREAD = MutableStateFlow<Map<String, SessionUnread>>(emptyMap())

/** Same stand-in rule as [NO_NOTIFICATIONS], for the badge's pending-ask half. */
private val NO_SESSION_ASKS = MutableStateFlow<Map<String, SessionAsks>>(emptyMap())

/** Same stand-in rule as [NO_NOTIFICATIONS], for the settled MCP revision (A-5). */
private val NO_MCP_REVISION = MutableStateFlow(0)

/** Same stand-in rule as [NO_NOTIFICATIONS], for the tracked session id. */
private val NO_TRACKED_SESSION_ID = MutableStateFlow<String?>(null)

/** Same stand-in rule as [NO_NOTIFICATIONS], for the debug bar's live metrics (B-17). */
private val NO_PROVIDER_METRICS = MutableStateFlow<ProviderMetrics?>(null)

/** Same stand-in rule as [NO_NOTIFICATIONS], for the debug bar's history projection (B-17). */
private val NO_CHAT_STATE = MutableStateFlow(ChatStore.ChatState())

@Composable
internal fun LocalOcColors() = OcTheme.colors
