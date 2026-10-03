package com.xiufeigo.opencodeshell.ui.terminal

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalEmulator
// Only referenced by `TerminalViewClient`'s upstream signatures; it is always `null` in the
// remote-PTY form (see the vendored `TerminalViewClient` javadoc).
import com.termux.terminal.TerminalSession
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ServerConfigStore
import com.xiufeigo.opencodeshell.ui.components.OcKeyboard
import com.xiufeigo.opencodeshell.ui.components.TabReorder
import com.xiufeigo.opencodeshell.ui.palette.LocalSessionCommandIntents
import com.xiufeigo.opencodeshell.ui.session.LocalTerminalImeFocus
import com.xiufeigo.opencodeshell.ui.session.TerminalImeFocus
import com.xiufeigo.opencodeshell.ui.settings.LocalTimelineDisplayPrefs
import com.xiufeigo.opencodeshell.ui.terminal.share.HandlerSurface
import com.xiufeigo.opencodeshell.ui.terminal.share.LocalTerminalRestore
import com.xiufeigo.opencodeshell.ui.terminal.share.NoopSessionClient
import com.xiufeigo.opencodeshell.ui.terminal.share.PersistentPtyClient
import com.xiufeigo.opencodeshell.ui.terminal.share.PtyApi
import com.xiufeigo.opencodeshell.ui.terminal.share.PtyClient
import com.xiufeigo.opencodeshell.ui.terminal.share.PtyProtocol
import com.xiufeigo.opencodeshell.ui.terminal.share.PtyScreenEngine
import com.xiufeigo.opencodeshell.ui.terminal.share.TerminalCwd
import com.xiufeigo.opencodeshell.ui.terminal.share.TerminalLink
import com.xiufeigo.opencodeshell.ui.terminal.share.TerminalRevivePolicy
import com.xiufeigo.opencodeshell.ui.terminal.share.TerminalStateStore
import com.xiufeigo.opencodeshell.ui.terminal.share.TerminalTabs
import com.xiufeigo.opencodeshell.ui.terminal.share.TermuxPtyScreenEngine
import com.xiufeigo.opencodeshell.ui.terminal.share.resolveLocalRestore
import com.xiufeigo.opencodeshell.ui.theme.OcColors
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import com.xiufeigo.opencodeshell.v2.SessionTerminalInfo
import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.persistentPtySnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * M5 — terminal panel chrome (`research/02_ui_screens.md` §9.3) plus the PTY data link.
 *
 * ```
 * id="terminal-panel"  bg-v2-background-bg-base
 * ├─ tab strip   h-[52px] (v2; legacy was h-10)
 * │   ├─ back            → leaves the panel route (Android addition: a route
 * │   │                    host needs an exit; upstream closes it with the last tab)
 * │   ├─ Tabs.Trigger    "Terminal {{number}}" + close icon (h-5 w-5)
 * │   └─ IconButton plus → terminal.new()
 * └─ slot (weight 1f)
 *     └─ AndroidView → com.termux.view.TerminalView   ← the VT kernel (M5 phase 2)
 * ```
 *
 * **Scope decision (recorded in `research/_M5_terminal_status.md`):** phase 1
 * hosts the panel as its own route (`Route.TERMINAL`) instead of the desktop
 * side-panel slot, because the review/context side panel is the other half of
 * M5; phase 2 moves this composable into that panel unchanged (it is already
 * one self-contained `Column`).
 *
 * **VT kernel (phase 2, A′):** the slot's `AndroidView.factory` creates the vendored
 * `TerminalView` and calls the `attachIo(TerminalIo)` patch, so no `TerminalSession` — and
 * therefore no local shell, no `JNI`, no `libtermux.so` — is ever involved
 * (`research/06` §1.4/§1.5). The emulator is [TermuxPtyScreenEngine]'s, the same object the
 * data link appends to, so what the renderer draws *is* the PTY's screen.
 */
/**
 * Embedded entry point for the terminal (Route C, mobile four-tab view).
 *
 * [TerminalPanelHost] expects an already-resolved [ServerConfigStore.Server];
 * the route host (`ui/App.kt`) gets it from the activity's singleton store. A
 * session-scoped tab has no such handle, so this wrapper resolves the same
 * value the way `OcActivity` does (`ServerConfigStore(context).restore()`) and
 * hands it over unchanged — the panel itself stays the single implementation.
 *
 * @param onClose what the header's close affordance does when embedded: the
 * mobile host switches back to another tab instead of popping a route.
 */
@Composable
fun EmbeddedTerminalPanel(
    directory: String? = null,
    /** Session this panel is docked for — enables session-bound terminals. */
    sessionID: String? = null,
    /**
     * `settings.terminalFont` for the session-hosted terminals.
     *
     * Defaults to the stored value on
     * [com.xiufeigo.opencodeshell.ui.settings.LocalTimelineDisplayPrefs]: every
     * in-session host (the docked pane and the mobile terminal tab) renders
     * through here, and the parameter only exists for a host that already has
     * the setting in hand. Leaving it at the default used to mean `""` on every
     * one of those paths, i.e. the system mono no matter what Appearance said.
     */
    terminalFont: String = LocalTimelineDisplayPrefs.current.terminalFont,
    /** B-5: `terminal.new` — see [TerminalPanelHost.newTabRequest]. */
    newTabRequest: Int = 0,
    onClose: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val store = remember(context) { ServerConfigStore(context).also { it.restore() } }
    TerminalPanelHost(
        server = store.server,
        directory = directory,
        sessionID = sessionID,
        terminalFont = terminalFont,
        newTabRequest = newTabRequest,
        onClose = onClose,
        modifier = modifier,
    )
}

@Composable
fun TerminalPanelHost(
    server: ServerConfigStore.Server?,
    directory: String? = null,
    /**
     * Session this panel is docked for (`TerminalDock` passes its `sessionId`).
     * `null` (the standalone terminal route) = plain PTY tabs only.
     */
    sessionID: String? = null,
    /** `settings.terminalFont` (upstream `JetBrainsMono Nerd Font Mono`). */
    terminalFont: String = "",
    /**
     * B-5: the `terminal.new` chord. A value change creates a tab; the counter
     * starts at 0 so a panel mounted after a chord press does not replay it.
     */
    newTabRequest: Int = 0,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    val tabs = remember { TerminalTabs() }
    val runtimes = remember { mutableStateMapOf<String, TerminalRuntime>() }
    // v50: the directory each PTY was **created** in, so a session move can
    // mark the tabs whose shell still sits in the old one ([TerminalCwd]).
    // Only tabs this process created have an entry; a revived one is left
    // unmarked rather than warned about on a guess.
    val ptyCwd = remember { mutableStateMapOf<String, String?>() }
    val scope = rememberCoroutineScope()
    val main = remember { Handler(Looper.getMainLooper()) }

    // One HTTP client for the REST half and one for the sockets; both are freed
    // when the panel unmounts (close = unmount, `research/02` §9.4 for terminals
    // and §9.3 for the panel itself).
    val httpClient = remember { PtyApi.defaultHttp() }
    val socketClient = remember { PtyApi.defaultSocketClient() }
    // v2 face: the PTY lifecycle goes through `V2Api.pty*` (`ptyCreate` →
    // `ptyConnectTicket` → `ptyConnect` → `ptyUpdate` → `ptyRemove`).
    val api = remember(server?.url, server?.username, server?.password, directory) {
        server?.let { PtyApi.forServer(it, directory, httpClient, v2 = true) }
    }
    // The persistent-pty / session-terminal / shell families speak plain `V2Api`.
    val v2api = remember(server?.url, server?.username, server?.password, directory) {
        server?.let { V2Api.forServer(it.url, it.username, it.password, directory, httpClient) }
    }

    // The tab model is plain Kotlin (unit-tested); Compose mirrors it.
    var tabList by remember { mutableStateOf<List<TerminalTabs.Tab>>(emptyList()) }
    var activeId by remember { mutableStateOf<String?>(null) }
    var autoCreated by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var panelError by remember { mutableStateOf<String?>(null) }
    /** Which overlay (background shells / session terminals) covers the slot. */
    var overlay by remember { mutableStateOf(TerminalOverlay.NONE) }
    val createFailedText = stringResource(R.string.oc_terminal_create_failed)

    val context = LocalContext.current
    val tabPrefs = remember(context) {
        context.getSharedPreferences(TERMINAL_TABS_PREFS, Context.MODE_PRIVATE)
    }

    fun persistTabs() {
        val key = TerminalStateStore.workspaceKey(directory)
        val all = TerminalStateStore.decodeAll(tabPrefs.getString(TERMINAL_TABS_KEY, null))
            .toMutableMap()
        all[key] = TerminalStateStore.snapshot(tabs)
        runCatching {
            // Synchronous: `apply()` flushes to disk off-thread, so a
            // force-stop between a tab change and the flush loses the numbering
            // state and the next process restarts at "Terminal 1". Payload is a
            // few tabs — the blocking write is sub-millisecond.
            tabPrefs.edit()
                .putString(TERMINAL_TABS_KEY, TerminalStateStore.encodeAll(TerminalStateStore.trim(all, key)))
                .commit()
        }
    }

    fun syncTabs() {
        tabList = tabs.tabs
        activeId = tabs.activeId
        persistTabs()
    }

    // v73 P2: this host composing *is* the pane coming on screen, and upstream
    // asks for the keyboard exactly then (`openTerminal` /
    // `terminal.toggle`, `use-session-commands.tsx:198,366`) — so the ask is the
    // state's initial value rather than an effect: the first slot's `AndroidView`
    // factory runs during this frame's layout, before any effect body would.
    val focusRequest = remember { TerminalFocusRequest().apply { ask() } }
    // Read here, not in the disposal below: a `CompositionLocal.current` outside a
    // composition is an error, and the caret hand-back runs from `onDispose`.
    val intents = LocalSessionCommandIntents.current
    // v74: the pane's answer to the ask (see [TerminalImeFocus]); read once here
    // for the same reason — the disposal reports through it.
    val imeFocus = LocalTerminalImeFocus.current

    fun readPersistedTabs(): TerminalStateStore.PersistedState =
        runCatching {
            TerminalStateStore.decodeAll(tabPrefs.getString(TERMINAL_TABS_KEY, null))[
                TerminalStateStore.workspaceKey(directory),
            ]
        }.getOrNull() ?: TerminalStateStore.PersistedState(activeId = null, tabs = emptyList())

    /**
     * `terminal.rename` (the chip's inline editor / its ⋮ menu row).
     *
     * The tab model and its workspace snapshot are written first — the chip's
     * label is the user's immediate feedback, and that snapshot is what a
     * revive reads. Then the same write upstream makes: `pty.update` with the
     * new title (`session/terminal/context.tsx:182-201`), so the server's own
     * `GET /api/pty` carries the rename instead of the auto-generated name it
     * answered `POST /api/pty` with. Without it the title lived only in this
     * process, which is why the server kept answering `Terminal IQch` and a
     * fresh panel that could not adopt the local entry came back numbered.
     *
     * A blank title clears the custom name (back to `terminal.title.numbered`):
     * there is no "unset" on the wire (`Pty.UpdateInput.title` is a plain
     * string), so that edit stays local rather than sending an empty title.
     * A failed write is not rolled back — upstream keeps its optimistic value
     * too (`context.tsx:196-201` rolls back only its store, and this panel's
     * store is the local one that already succeeded).
     */
    fun renameTab(id: String, title: String) {
        if (!tabs.rename(id, title)) return
        syncTabs()
        val activeApi = api ?: return
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        // Session-bound terminals are persistent PTYs: `PersistentPty.UpdateInput`
        // is `{attachmentID?, size}` with **no** title (`schema/src/persistent-pty.ts`),
        // so their rename has no route to write to (see the delivery note).
        if (runtimes[id]?.kind == TerminalKind.SESSION) return
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { activeApi.updateTitle(id, trimmed) } }
        }
    }

    fun moveTab(fromIndex: Int, toIndex: Int) {
        if (tabs.move(fromIndex, toIndex)) syncTabs()
    }

    fun post(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    fun closeTab(id: String) {
        ptyCwd.remove(id)
        val runtime = runtimes.remove(id)
        runtime?.link?.close()
        val keepOpen = tabs.close(id)
        syncTabs()
        val activeApi = api
        // Ephemeral PTY tabs are unmounted locally *and* removed server-side:
        // leaving a live shell behind for every closed tab leaks processes (v1
        // keeps only running sessions, so a stale PTY is also invisible to the
        // WebView shell afterwards). Session terminals have **no** server-side
        // delete here — `DELETE /api/experimental/persistent-pty/{id}` belongs
        // to the missing-verbs surface (see delivery note), so they detach only.
        if (activeApi != null && runtime?.kind == TerminalKind.PTY) {
            scope.launch {
                withContext(Dispatchers.IO) { runCatching { activeApi.remove(id) } }
            }
        }
        // §9.3: closing the last terminal closes the panel itself.
        if (!keepOpen) onClose()
    }

    /**
     * Wires a tab to its socket. [restore] is [LocalTerminalRestore.Fresh] for
     * a tab created in this process; a revived tab replays its screen — the
     * checkpoint is painted before `start()` so the socket replay can only
     * arrive afterwards, and the full-replay cursor makes [PtyClient] reset
     * the engine on connect.
     */
    fun attachRuntime(tab: TerminalTabs.Tab, restore: LocalTerminalRestore = LocalTerminalRestore.Fresh) {
        val activeApi = api ?: return
        val tickScheduled = AtomicBoolean(false)
        // M5 phase 2: the real VT kernel. `termux` is the vendored TerminalEmulator holding the
        // screen, `engine` the PtyScreenEngine the data link drives (answersQueries = true, so
        // the emulator answers DSR/DA/OSC 52 itself and answers leave through its TerminalOutput
        // → `PtyClient.sendBytes`, `research/06` §1.4).
        val termux = TermuxPtyScreenEngine(
            cols = TERMINAL_INITIAL_COLS,
            rows = TERMINAL_INITIAL_ROWS,
            sessionClient = OcTerminalSessionClient(),
            surface = HandlerSurface(main),
        )
        val engine: PtyScreenEngine = termux
        lateinit var runtime: TerminalRuntime
        val client = PtyClient(
            api = activeApi,
            ptyId = tab.id,
            engine = engine,
            initialCursor = restore.connectCursor,
            listener = object : PtyClient.Listener {
                override fun onState(state: PtyClient.State) {
                    post { runtime.state = state }
                }

                override fun onCursor(cursor: Long) {
                    post { runtime.cursor = cursor }
                }

                override fun onOutput(text: String) {
                    // Coalesce: a chatty shell must not queue one recomposition
                    // per WebSocket frame. The VT kernel owns the screen now, so
                    // this only refreshes the diagnostics line.
                    if (tickScheduled.compareAndSet(false, true)) {
                        post {
                            tickScheduled.set(false)
                            runtime.syncFromEngine()
                            runtime.terminalView?.let { view ->
                                if (activeId == tab.id) view.invalidate()
                            }
                        }
                    }
                }

                override fun onError(message: String, code: Int?) {
                    post {
                        runtime.error = message
                        runtime.errorCode = code
                    }
                }
            },
            socketClient = socketClient,
        )
        runtime = TerminalRuntime(id = tab.id, kind = TerminalKind.PTY, termux = termux, link = client)
        // The view's transport: input → `PtyClient.sendBytes` (text frame), grid → `PtyClient.resize`
        // (PtySizeGate: first immediate, then 100 ms debounce → `PUT /pty/:id {size}`).
        val io = termux.RemoteTerminalIo(
            writeBytes = { bytes -> client.sendBytes(bytes) },
            resizePty = { cols, rows -> client.resize(cols, rows) },
        )
        runtime.io = io
        runtimes[tab.id] = runtime
        (restore as? LocalTerminalRestore.Snapshot)?.let { snapshot ->
            engine.onReset()
            engine.onOutput(snapshot.checkpoint)
            if (snapshot.cols > 0 && snapshot.rows > 0) {
                engine.onResize(snapshot.cols, snapshot.rows, 0f, 0f)
            }
        }
        client.start()
    }

    /**
     * Session-bound terminal tab: [PersistentPtyClient] in session mode — the
     * reconnect sequence restores the screen from `readSessionTerminal`
     * (screen text + cursor) and resumes at `output.tail`, falling back to the
     * `persistentPtySnapshot` checkpoint when the session has no current
     * terminal (`terminal-pane.tsx:175-188`).
     */
    fun attachSessionRuntime(tab: TerminalTabs.Tab) {
        val activeApi = v2api ?: return
        val tickScheduled = AtomicBoolean(false)
        val termux = TermuxPtyScreenEngine(
            cols = TERMINAL_INITIAL_COLS,
            rows = TERMINAL_INITIAL_ROWS,
            sessionClient = OcTerminalSessionClient(),
            surface = HandlerSurface(main),
        )
        val engine: PtyScreenEngine = termux
        lateinit var runtime: TerminalRuntime
        val client = PersistentPtyClient(
            api = activeApi,
            ptyId = tab.id,
            engine = engine,
            sessionID = sessionID,
            listener = object : PersistentPtyClient.Listener {
                override fun onState(state: PtyClient.State) {
                    post { runtime.state = state }
                }

                override fun onOutput(text: String) {
                    if (tickScheduled.compareAndSet(false, true)) {
                        post {
                            tickScheduled.set(false)
                            runtime.syncFromEngine()
                            runtime.terminalView?.let { view ->
                                if (activeId == tab.id) view.invalidate()
                            }
                        }
                    }
                }

                override fun onError(message: String, code: Int?) {
                    post {
                        runtime.error = message
                        runtime.errorCode = code
                    }
                }
            },
            socketClient = socketClient,
        )
        runtime = TerminalRuntime(
            id = tab.id,
            kind = TerminalKind.SESSION,
            termux = termux,
            link = client,
        )
        // Input/up-link and grid resize travel in band (`input_protocol=1`
        // frames), never over REST.
        val io = termux.RemoteTerminalIo(
            writeBytes = { bytes -> client.sendBytes(bytes) },
            resizePty = { cols, rows -> client.resize(cols, rows) },
        )
        runtime.io = io
        runtimes[tab.id] = runtime
        client.start()
    }

    /** Adopts one session terminal (existing or just created) as a tab. */
    fun openSessionTerminal(info: SessionTerminalInfo) {
        if (info.id.isEmpty() || runtimes.containsKey(info.id)) return
        val tab = tabs.add(info.id, title = info.title.ifBlank { null }, focus = true) ?: return
        syncTabs()
        overlay = TerminalOverlay.NONE
        // The terminal the user just picked out of the picker takes the keyboard.
        focusRequest.ask(info.id)
        attachSessionRuntime(tab)
    }

    /** Best-effort snapshot for one revived id; anything missing → socket replay. */
    fun resolveReviveRestore(id: String): LocalTerminalRestore {
        val snapshot = runCatching { v2api?.persistentPtySnapshot(id) }.getOrNull()
            ?: return LocalTerminalRestore.FullReplay
        val checkpoint = snapshot.checkpoint.takeIf { it.isNotEmpty() }?.let {
            runCatching { snapshot.checkpointBytes() }.getOrNull()
        }
        return resolveLocalRestore(
            checkpoint = checkpoint,
            cols = snapshot.info.cols,
            rows = snapshot.info.rows,
            outputTail = snapshot.info.outputTail,
        )
    }

    fun attachRevivedRuntime(tab: TerminalTabs.Tab) {
        scope.launch {
            val restore = withContext(Dispatchers.IO) { resolveReviveRestore(tab.id) }
            if (tabs.find(tab.id) != null && !runtimes.containsKey(tab.id)) {
                attachRuntime(tab, restore = restore)
                syncTabs()
            }
        }
    }

    /**
     * Re-adopts tabs the previous process left behind: ids the server still
     * knows are re-registered (numbers/titles kept, counter re-seeded) and
     * attached with screen restore; dead ids are dropped. Session tabs are
     * left for the overlay picker — only the ephemeral namespace is probed.
     * Returns `true` when at least one tab was revived.
     *
     * The counter is re-seeded even when the persisted tab list is **empty**
     * (every tab closed before the panel went away): that payload is the only
     * record of the numbers handed out, and returning early on it made the next
     * panel start over at "Terminal 1" — the same reset the total-drop path
     * below guards against, reached through the empty list instead.
     */
    suspend fun reviveTerminals(): Boolean {
        val persisted = readPersistedTabs()
        // Before the `api == null` gate as well: the numbering is the local
        // store's business, and a server-less panel still shows what it hands
        // out next (the auto-create path is gated separately).
        tabs.seedNumber(persisted.nextNumber)
        if (persisted.tabs.isEmpty()) return false
        val activeApi = api ?: return false
        busy = true
        try {
            val live = withContext(Dispatchers.IO) {
                persisted.tabs.filter { tab ->
                    tab.id.isNotBlank() &&
                        runCatching { activeApi.exists(tab.id) }.getOrDefault(false)
                }
            }
            var adopted = false
            for (tab in live) {
                if (tabs.isFull) break
                val added = tabs.add(
                    tab.id,
                    title = tab.title,
                    number = tab.number.takeIf { it > 0 },
                    focus = true,
                ) ?: continue
                tabs.seedNumber(added.number)
                adopted = true
                attachRevivedRuntime(added)
            }
            // The counter survives even a total-drop restart (every persisted id
            // dead) — the early seed above is what the fallback auto-create then
            // continues from instead of reissuing "Terminal 1".
            persisted.activeId?.takeIf { id -> tabs.find(id) != null }?.let { tabs.activate(it) }
            syncTabs()
            return adopted
        } finally {
            busy = false
        }
    }

    fun createTerminal() {
        val activeApi = api ?: return
        if (busy || tabs.isFull) return
        busy = true
        panelError = null
        scope.launch {
            // Upstream labels a tab from its **own** terminal title and falls back
            // to `terminal.title.numbered` when it is unset/default
            // (`session/terminal/tab.tsx:33-43`); the server's pty title is not
            // that value — this server answers every `POST /pty` with a random
            // code like "Terminal His6" (verified against 1.18.31), which the web
            // UI never shows ("Terminal 1"). Passing `null` keeps the numbered
            // default so the two frontends read the same.
            val result = createPtyAdopted(activeApi, directory) { id ->
                tabs.add(id, title = null, focus = true)
            }
            busy = false
            when (result) {
                is CreatePtyResult.Adopted -> {
                    // The shell's `cwd` is fixed at `POST /pty` — record the
                    // directory it was asked for, so a later move can mark it.
                    ptyCwd[result.value.id] = directory
                    syncTabs()
                    // `terminal.new()` asks for the terminal it created
                    // (`session/terminal/context.tsx:249` → `autoFocus`): the `+`,
                    // the `terminal.new` chord and the pane's own first terminal all
                    // arrive here, so this is where the keyboard follows.
                    focusRequest.ask(result.value.id)
                    attachRuntime(result.value)
                }
                CreatePtyResult.Rejected -> Unit
                CreatePtyResult.Failed -> panelError = createFailedText
            }
        }
    }

    // §9.3: opening the panel with no terminal creates one (`autoCreated`).
    // After process death the persisted tabs are re-adopted first (with screen
    // restore); only a panel with nothing to revive creates a fresh terminal.
    // `autoCreated` is deliberately NOT an effect key: it is set before the
    // suspending revive, and as a key that write would restart this effect and
    // cancel the in-flight revive — leaving an empty panel ("No terminal open")
    // with the `createTerminal()` fallback never running.
    LaunchedEffect(api) {
        if (api != null && !autoCreated && tabs.isEmpty && !busy) {
            autoCreated = true
            val revived = reviveTerminals()
            if (TerminalRevivePolicy.shouldAutoCreate(revived, tabs.size)) createTerminal()
        }
    }

    // B-5: the `terminal.new` chord (`use-session-commands.tsx:402-409`
    // `openTerminal` → `terminal.new()`). The host bumps the counter; this panel
    // owns the tab model, so it is the only place that can add one. The
    // auto-create effect above runs first (it is keyed on `api`, not on the
    // counter), so a `terminal.new` on a freshly opened pane adds a *second* tab
    // only when the user asked for one.
    LaunchedEffect(newTabRequest) {
        if (newTabRequest > 0) createTerminal()
    }

    DisposableEffect(Unit) {
        onDispose {
            // v73 P2 — the pane is going away, and if it was the thing holding the
            // keyboard (the view is still attached and focused here; the
            // `AndroidView` nodes are released after the effects) the window is
            // left with **no** input target at all: detaching a focused editor
            // takes the composer's caret with it, and typing would go nowhere until
            // the user taps a field. Closing the terminal hands the caret back the
            // same way every other close does — the shell's own `input.focus`
            // request (B-5), which the session screen already maps onto the
            // composer (`SessionScreen` → `Composer.externalFocus`).
            if (runtimes.values.any { it.terminalView?.hasFocus() == true }) {
                intents?.requestCommand("input.focus")
            }
            // v74: the pane is the keyboard's owner for as long as it is up —
            // nothing under it holds the view focus after the views are dropped,
            // so the composer's next mount is free to take the caret again.
            imeFocus?.report(false)
            runtimes.values.forEach { runtime ->
                // Stop the cursor-blink handler *before* dropping the client: a pending
                // `postDelayed` on a detached TerminalView keeps invalidating a dead view.
                runtime.terminalView?.setTerminalCursorBlinkerState(false, false)
                runtime.link.close()
            }
            runtimes.clear()
            api?.close()
            socketClient.dispatcher.executorService.shutdown()
            socketClient.connectionPool.evictAll()
            httpClient.connectionPool.evictAll()
        }
    }

    Column(modifier.fillMaxSize().background(c.bgBase)) {
        TerminalTabStrip(
            tabs = tabList,
            activeId = activeId,
            // v50: chips whose shell was launched before the session was moved
            // to its current directory. The shell is never re-rooted (it may be
            // running a build) — the chip is marked instead.
            staleTabIds = TerminalCwd.staleIds(ptyCwd, directory),
            busy = busy,
            canCreate = api != null && !tabs.isFull,
            onSelect = { id ->
                if (tabs.activate(id)) {
                    syncTabs()
                    // `terminal/tab.tsx:58` — the chip the user turned to takes the
                    // keyboard. The slot for the new active id is rebuilt, so without
                    // this the caret goes nowhere: the previous view is gone and the
                    // IME is left on the compose root with no field focused.
                    focusRequest.ask(id)
                }
            },
            onCreate = { createTerminal() },
            onCloseTab = { id -> closeTab(id) },
            onClosePanel = onClose,
            onRenameTab = { id, title -> renameTab(id, title) },
            onMoveTab = { from, to -> moveTab(from, to) },
            onShowShells = if (v2api != null) {
                { overlay = if (overlay == TerminalOverlay.SHELLS) TerminalOverlay.NONE else TerminalOverlay.SHELLS }
            } else {
                null
            },
            onShowSessionTerminals = if (v2api != null && sessionID != null) {
                {
                    overlay = if (overlay == TerminalOverlay.SESSION_TERMINALS) {
                        TerminalOverlay.NONE
                    } else {
                        TerminalOverlay.SESSION_TERMINALS
                    }
                }
            } else {
                null
            },
        )
        val activeOverlay = overlay
        val activeV2 = v2api
        if (activeOverlay != TerminalOverlay.NONE && activeV2 != null) {
            when (activeOverlay) {
                TerminalOverlay.SHELLS -> BackgroundShellPanel(
                    api = activeV2,
                    locationDirectory = directory,
                    onBack = { overlay = TerminalOverlay.NONE },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                TerminalOverlay.SESSION_TERMINALS -> {
                    val sid = sessionID
                    if (sid != null) {
                        SessionTerminalsPanel(
                            api = activeV2,
                            sessionID = sid,
                            directory = directory,
                            onAttach = { openSessionTerminal(it) },
                            onBack = { overlay = TerminalOverlay.NONE },
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                    }
                }
                TerminalOverlay.NONE -> Unit
            }
            return@Column
        }
        val activeRuntime = activeId?.let { runtimes[it] }
        val abnormalCloseHint = activeRuntime?.error?.takeIf {
            activeRuntime.state == PtyClient.State.RECONNECTING ||
                activeRuntime.state == PtyClient.State.FAILED
        }
        val hint = when {
            api == null -> stringResource(R.string.oc_terminal_server_missing)
            panelError != null -> panelError
            abnormalCloseHint != null -> stringResource(
                R.string.terminal_connectionLost_abnormalClose,
                abnormalCloseHint,
            )
            tabList.isEmpty() && busy -> stringResource(R.string.oc_terminal_loading)
            tabList.isEmpty() -> stringResource(R.string.oc_terminal_empty)
            tabs.isFull -> stringResource(R.string.oc_terminal_full, PtyProtocol.MAX_TERMINAL_SESSIONS)
            else -> null
        }
        if (hint != null) {
            Text(
                text = hint,
                color = c.textMuted,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val runtime = activeId?.let { runtimes[it] }
            if (runtime != null) {
                // Keyed per tab: the factory is the only `attachIo` call site, so
                // without this a switch kept rendering and feeding tab 1's PTY.
                key(runtime.id) {
                    TerminalSlot(
                        runtime = runtime,
                        fontFamily = terminalFont,
                        focusRequest = focusRequest,
                        imeFocus = imeFocus,
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.oc_terminal_empty),
                    color = c.textFaint,
                    fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

/**
 * `POST /pty` → hand the id to [adopt]. The id is captured *inside* the IO
 * block, so a scope cancelled between "server created the PTY" and "tab
 * registered" still runs `DELETE` under [NonCancellable] — otherwise the
 * coroutine drops the create result at its first suspension after the POST
 * and the server keeps an orphan shell nobody will ever release.
 */
internal suspend fun <T> createPtyAdopted(
    api: PtyApi,
    directory: String?,
    adopt: suspend (id: String) -> T?,
): CreatePtyResult<T> {
    val created = AtomicReference<String?>(null)
    try {
        val info = withContext(Dispatchers.IO) {
            runCatching { api.create(cwd = directory) }
                .getOrNull()
                ?.also { created.set(it.id) }
        } ?: return CreatePtyResult.Failed
        val value = adopt(info.id) ?: return CreatePtyResult.Rejected
        created.set(null)
        return CreatePtyResult.Adopted(value)
    } finally {
        created.getAndSet(null)?.let { orphan ->
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { api.remove(orphan) }
            }
        }
    }
}

/** Who owns the server-side PTY after [createPtyAdopted] returns. */
internal sealed interface CreatePtyResult<out T> {
    /** The adopt lambda took the id (the tab owns it; no DELETE). */
    data class Adopted<T>(val value: T) : CreatePtyResult<T>

    /** `POST /pty` failed — nothing exists server-side. */
    data object Failed : CreatePtyResult<Nothing>

    /** Created but refused (tab full/duplicate) — already DELETEd. */
    data object Rejected : CreatePtyResult<Nothing>
}

/** Which server family a tab's data link speaks. */
internal enum class TerminalKind { PTY, SESSION }

/** Overlay covering the render slot: background shells or session terminals. */
internal enum class TerminalOverlay { NONE, SHELLS, SESSION_TERMINALS }

/**
 * v73 P2 — the pane's **outstanding focus request**: the keyboard has to follow
 * the terminal whenever the terminal is what the user just turned to.
 *
 * Device report (5556, `research/v73_repro2`, same build as `v73_verify/5556`):
 * opening the dock left the keyboard on the session composer. `dumpsys
 * input_method` served `AndroidComposeView` while the terminal was on screen with
 * its prompt and cursor, and both `adb input text` and the on-screen keyboard
 * typed into the composer's draft — `echo OK` could not be run at all. Nothing
 * ever asked the [com.termux.view.TerminalView] for the focus; only a *tap on the
 * terminal surface* did (`TerminalView`'s own `requestFocusFromTouch` patch), so
 * a terminal the user had just opened received nothing.
 *
 * Upstream asks explicitly, at exactly these moments: `terminal.toggle` and
 * `openTerminal` request the focus when the panel opens
 * (`session/commands/use-session-commands.tsx:198,366`), a tab chip click does the
 * same (`session/terminal/tab.tsx:58`), `terminal.new()` requests it for the
 * terminal it is about to create (`session/terminal/context.tsx:249`), and the
 * surface that satisfies the request consumes it (`panel.tsx:356-361`
 * `autoFocus` → `onAutoFocus` → `consumeFocus`). This is that state without the
 * DOM: an ask names a terminal id, or passes none at all — upstream's `pending`
 * request, "whichever terminal turns out to be the active one", which is all the
 * panel knows while it is still restoring its tabs — and the **view being
 * created** is what satisfies it.
 *
 * The ask is deliberately *not* Compose state: it is read while the `AndroidView`
 * factory runs (during layout) and settled there, and a consumed ask must never
 * come back as a recomposition dependency. Being one-shot is what keeps a later
 * remount (a workspace switch, a revive after process death, a rotation) from
 * stealing the caret back from the composer — upstream's `restoreFocus` branch,
 * `terminal.tsx:466-474`.
 */
internal class TerminalFocusRequest {

    /** Asks so far; `0` means nothing was ever asked. */
    private var asked = 0

    /** Asks already satisfied — an ask fires exactly once. */
    private var served = 0

    /** The terminal the last ask named; `null` = "whichever is active". */
    private var target: String? = null

    /** Asks for the keyboard: [id] names one terminal, `null` the active one. */
    fun ask(id: String? = null) {
        target = id
        asked += 1
    }

    /** `true` while an unpaid ask is for [id]'s view. */
    fun pending(id: String): Boolean =
        asked > served && (target == null || target == id)

    /** Satisfied: [pending] stays false for every id until the next [ask]. */
    fun consume() {
        served = asked
    }
}

/**
 * Per-terminal runtime: the connection, the **VT kernel** and the state Compose observes.
 * Compose state is only ever written on the main thread (the listener callbacks arrive
 * on OkHttp's reader thread and are posted).
 *
 * Phase 2 swap: the phase-1 `PlaceholderPtyScreenEngine` became [TermuxPtyScreenEngine], which owns
 * the screen. The Compose state here is therefore only what the *chrome* needs (tab title, error
 * line, cursor for the diagnostics) — the rendered content comes from the emulator through
 * `TerminalView`.
 *
 * [link] is the data link seam ([TerminalLink]): a [PtyClient] for ephemeral PTY
 * tabs, a [PersistentPtyClient] for session-bound terminals.
 */
class TerminalRuntime internal constructor(
    val id: String,
    internal val kind: TerminalKind,
    val termux: TermuxPtyScreenEngine,
    internal val link: TerminalLink,
) {
    /** The seam the data link drives (always the termux kernel in phase 2). */
    internal val engine: PtyScreenEngine get() = termux

    /** Set by `attachRuntime`; attached to the view by `AndroidView.factory` (`attachIo`). */
    internal var io: com.termux.terminal.TerminalIo? = null

    /** The live renderer, so a UI tick can invalidate exactly the right view. */
    internal var terminalView: com.termux.view.TerminalView? = null

    /** GONE/EXITED keep the last screen but stop the cursor (the shell is not there any more). */
    internal val isLive: Boolean
        get() = state == PtyClient.State.OPEN || state == PtyClient.State.CONNECTING ||
            state == PtyClient.State.RECONNECTING

    var state by mutableStateOf(PtyClient.State.IDLE)
    var cursor by mutableStateOf(PtyProtocol.CURSOR_NEW_OUTPUT_ONLY)
    var cols by mutableStateOf(0)
    var rows by mutableStateOf(0)
    var bytes by mutableStateOf(0L)
    var error by mutableStateOf<String?>(null)
    var errorCode by mutableStateOf<Int?>(null)

    /** Diagnostics only (dev builds); the screen itself is the emulator's. */
    internal fun syncFromEngine() {
        bytes = termux.bytesReceived
        cols = termux.gridCols
        rows = termux.gridRows
    }
}

/**
 * `Tabs` strip of §9.3: **52 dp** in v2, close per tab, `+` = new terminal.
 *
 * The chips overflow the strip on every pane narrow enough to show one of them,
 * so the active chip is scrolled into view whenever the tab set or the active
 * tab changes ([TerminalTabScroll]) — a newly created tab is focused by
 * [TerminalTabs.add], and without that scroll its chip stayed outside the
 * viewport entirely.
 *
 * D-15b: the long-press drag outlives the touch slop without the scroll having
 * to stand down — the claim detector reads the travel itself and drops the press
 * when it was already a scroll, see [awaitLongPressClaim] and
 * [TerminalTabGesture]. The reveal below is what keeps every chip reachable
 * without the user having to drag.
 */
@Composable
private fun TerminalTabStrip(
    tabs: List<TerminalTabs.Tab>,
    activeId: String?,
    busy: Boolean,
    canCreate: Boolean,
    /** Chips whose PTY `cwd` predates the session's current directory (v50). */
    staleTabIds: Set<String> = emptySet(),
    onSelect: (String) -> Unit,
    onCreate: () -> Unit,
    onCloseTab: (String) -> Unit,
    onClosePanel: () -> Unit,
    onRenameTab: (String, String) -> Unit = { _, _ -> },
    onMoveTab: (Int, Int) -> Unit = { _, _ -> },
    /** Opens the background-shell overlay; `null` hides the affordance. */
    onShowShells: (() -> Unit)? = null,
    /** Opens the session-terminals overlay (only when docked for a session). */
    onShowSessionTerminals: (() -> Unit)? = null,
) {
    val c = OcTheme.colors
    // D-15 drag state, the terminal twin of the session strip's (`B-20`): the
    // chips are keyed by PTY id, so a reorder moves a chip instead of rebinding
    // it and the gesture is never restarted mid-drag.
    val chipWidths = remember { mutableStateMapOf<String, Float>() }
    var dragId by remember { mutableStateOf<String?>(null) }
    var dragDx by remember { mutableFloatStateOf(0f) }
    var dragMoved by remember { mutableStateOf(false) }
    // D-15b: which of the two long-press detectors owns the drag in progress
    // ([TerminalDragOwner]). v40: the strip's scroll is never suspended any
    // more — the chips are flush, so a finger on a chip is the only place to
    // drag from, and suspending it left the overflow unscrollable. The claim
    // detector reads the travel up to the deadline instead ([TerminalTabGesture]).
    var dragOwner by remember { mutableStateOf<TerminalDragOwner?>(null) }
    // The chip's ⋮ menu, hoisted so a long press that never moved can open the
    // same menu (the session strips behave alike).
    var menuFor by remember { mutableStateOf<String?>(null) }
    val liveTabs = rememberUpdatedState(tabs)
    val liveMove = rememberUpdatedState(onMoveTab)
    val touchSlop = LocalViewConfiguration.current.touchSlop

    // One writer per gesture: `owner` is the detector that claimed it and every
    // callback below is a no-op for the other one. Both detectors watch the same
    // press — a still hold resolves on both at the long-press deadline — so the
    // claim (a state write, with no suspension between the check and the write)
    // decides which of them drives the reorder.
    fun claimDrag(owner: TerminalDragOwner, id: String): Boolean {
        if (dragOwner != null) return false
        dragOwner = owner
        dragId = id
        dragDx = 0f
        dragMoved = false
        return true
    }

    fun dragBy(owner: TerminalDragOwner, id: String, dx: Float) {
        if (dragOwner != owner || dragId != id) return
        dragDx += dx
        if (abs(dragDx) > touchSlop) dragMoved = true
        val list = liveTabs.value
        val from = list.indexOfFirst { it.id == id }
        // The chips sit flush against each other (no arrangement gap), so the
        // chip's own width is the whole pitch.
        val pitch = chipWidths[id] ?: 0f
        val to = TabReorder.dropIndex(from, dragDx, pitch, list.size)
        if (to >= 0 && to != from) {
            liveMove.value(from, to)
            // The chip just moved under the finger: re-base the travel on its
            // new home so the next step counts from there.
            dragDx -= (to - from) * pitch
        }
    }

    fun endDrag(owner: TerminalDragOwner, id: String, cancelled: Boolean) {
        if (dragOwner != owner || dragId != id) return
        // A long press that never moved opens the menu instead of reordering;
        // the gesture consumes the up, so this is not also a select.
        if (!cancelled && !dragMoved) menuFor = id
        dragOwner = null
        dragId = null
        dragDx = 0f
    }
    // The strip's scroll position, hoisted out of the `horizontalScroll`: the
    // effect below keeps the **active** chip inside the viewport, which is what
    // makes a tab the strip's `+` just created (and focused) visible at all —
    // the chip is ≈160 dp wide and the pane often shows ≈155 dp of chip space.
    val scroll = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TAB_STRIP_HEIGHT)
            .background(c.bgBase),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconAction(
            iconRes = R.drawable.ic_oc_chevron_down,
            contentDescription = stringResource(R.string.oc_terminal_close_panel),
            iconModifier = Modifier.rotate(90f),
            onClick = onClosePanel,
        )
        // v47: the chips viewport. The edge fades ride *on top of* it (the two
        // overlay boxes below), never inside the scroll content — see
        // [TerminalStripEdgeFade] for why a mask drawn inside `horizontalScroll`
        // cannot work. The overlays carry no input modifiers, so drags, flings
        // and taps fall through to the scrolling row beneath.
        Box(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // v40: always scrollable. The chips sit flush, so there is no
                    // "free area" of the strip to drag from — suspending the scroll
                    // for the length of a chip press (the price D-15b paid to keep
                    // the long press alive) left the whole overflow unreachable.
                    // The claim detector now reads the travel itself
                    // ([TerminalTabGesture]) and never becomes a reorder from a
                    // press that was already a scroll.
                    .horizontalScroll(scroll),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for ((index, tab) in tabs.withIndex()) {
                    // `key(tab.id)`: a reorder moves this group rather than
                    // rebinding the chip in place, which is what lets the drag
                    // survive its own reorder.
                    key(tab.id) {
                        val dragging = dragId == tab.id
                        // The label comes from the terminal's own counter, never
                        // from its position (v40: a move renamed the neighbours).
                        val defaultTitle = stringResource(R.string.oc_terminal_title_numbered, tab.number)
                        TerminalTabChip(
                            title = TerminalTabTitle.resolve(tab.title, tab.number) { defaultTitle },
                            active = tab.id == activeId,
                            stale = tab.id in staleTabIds,
                            defaultTitle = defaultTitle,
                            onSelect = { onSelect(tab.id) },
                            onClose = { onCloseTab(tab.id) },
                            onRename = { title -> onRenameTab(tab.id, title) },
                            onMoveLeft = if (TerminalTabMenu.canMoveLeft(index, tabs.size)) {
                                { onMoveTab(index, index - 1) }
                            } else {
                                null
                            },
                            onMoveRight = if (TerminalTabMenu.canMoveRight(index, tabs.size)) {
                                { onMoveTab(index, index + 1) }
                            } else {
                                null
                            },
                            menuOpen = menuFor == tab.id,
                            onOpenMenu = { menuFor = tab.id },
                            onDismissMenu = { menuFor = null },
                            // D-15: long-press drag reorder. The chips sit flush
                            // against each other (no arrangement gap), so the chip's
                            // own width is the whole pitch.
                            //
                            // Two detectors watch the press: the strict
                            // `detectDragGesturesAfterLongPress` (a hold that stays
                            // inside the chip and consumes nothing) and the re-armed
                            // [awaitLongPressClaim] (the same deadline, indifferent
                            // to the movement the strip's scroll already took). Only
                            // the first to claim drives the reorder.
                            dragModifier = Modifier
                                .onSizeChanged { chipWidths[tab.id] = it.width.toFloat() }
                                .graphicsLayer {
                                    if (dragging) {
                                        translationX = dragDx
                                        alpha = 0.85f
                                    }
                                }
                                .zIndex(if (dragging) 1f else 0f)
                                .pointerInput(tab.id) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { claimDrag(TerminalDragOwner.HOLD, tab.id) },
                                        onDragEnd = {
                                            endDrag(TerminalDragOwner.HOLD, tab.id, cancelled = false)
                                        },
                                        onDragCancel = {
                                            endDrag(TerminalDragOwner.HOLD, tab.id, cancelled = true)
                                        },
                                        onDrag = { _, amount ->
                                            dragBy(TerminalDragOwner.HOLD, tab.id, amount.x)
                                        },
                                    )
                                }
                                .pointerInput(tab.id) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(
                                            requireUnconsumed = false,
                                            pass = PointerEventPass.Initial,
                                        )
                                        try {
                                            awaitLongPressClaim(
                                                down = down,
                                                onClaim = {
                                                    claimDrag(TerminalDragOwner.CLAIM, tab.id)
                                                },
                                                onDrag = { dx ->
                                                    dragBy(TerminalDragOwner.CLAIM, tab.id, dx)
                                                },
                                                onEnd = {
                                                    endDrag(TerminalDragOwner.CLAIM, tab.id, cancelled = false)
                                                },
                                                onCancel = {
                                                    endDrag(TerminalDragOwner.CLAIM, tab.id, cancelled = true)
                                                },
                                            )
                                        } finally {
                                            // A cancelled gesture (the panel went away
                                            // mid-press) must not leave the drag state
                                            // claimed: every later drag would be a
                                            // no-op. The drag's own end clears it in
                                            // the normal paths, so this is a no-op
                                            // there.
                                            endDrag(TerminalDragOwner.CLAIM, tab.id, cancelled = true)
                                        }
                                    }
                                },
                        )
                    }
                }
            }
            // The fades ride the same scroll position the row scrolls with
            // ([TerminalStripChrome.fadeEdges]): an edge with nothing beyond it
            // stays sharp, and a strip that fits altogether draws no overlay.
            // Read in composition (not in a draw phase), so a fling recomposes
            // just these two small boxes.
            //
            // v49 tD_65 verdict: the ~49 px blank block at the leading edge at
            // end-scroll is this leading overlay itself (12 dp
            // [STRIP_EDGE_FADE]), not a clip margin and not a layout bug — the
            // scroll content is the chips alone (no padding, no trailing slot
            // since v47), the overlay is pinned to the viewport's own edge,
            // and the crop shows it mid-work (the cut glyph fading under it).
            // It carries no input modifiers, so taps fall through to the chip
            // beneath (selecting it); it only *reads* as a dead block because
            // its opaque end covers the chip's own blank start padding. Kept
            // as is: it is the shared fade recipe (composer attachment row,
            // [com.xiufeigo.opencodeshell.ui.nav.DesktopTabStrip]), and
            // removing it would bring back the mid-glyph hard cuts v47 fixed.
            val edges = TerminalStripChrome.fadeEdges(scroll.value, scroll.maxValue)
            if (edges.leading) {
                TerminalStripEdgeFade(leading = true, modifier = Modifier.align(Alignment.CenterStart))
            }
            if (edges.trailing) {
                TerminalStripEdgeFade(leading = false, modifier = Modifier.align(Alignment.CenterEnd))
            }
        }
        if (onShowSessionTerminals != null) {
            IconAction(
                iconRes = R.drawable.ic_oc_console,
                contentDescription = stringResource(R.string.oc_terminal_session_terminals),
                onClick = onShowSessionTerminals,
            )
        }
        if (onShowShells != null) {
            IconAction(
                iconRes = R.drawable.ic_oc_task,
                contentDescription = stringResource(R.string.oc_terminal_shells_title),
                onClick = onShowShells,
            )
        }
        IconAction(
            iconRes = R.drawable.ic_oc_plus,
            contentDescription = stringResource(R.string.oc_terminal_new),
            enabled = canCreate && !busy,
            onClick = onCreate,
        )
    }
    // Keep the active chip visible: `+` (and every other way a tab can arrive)
    // focuses the tab it adds, and a chip that is scrolled out of the strip
    // reads as "the `+` did nothing" while the pane has already switched to the
    // new shell. The chips sit flush (no arrangement gap), so the widths the
    // drag already measures are the whole pitch and the offsets are their
    // running sum. `withFrameNanos` waits for the frame that measured them, and
    // the reorder's own drag is left alone (it re-bases on the chip's pitch, so
    // scrolling under the finger would only fight it) — keyed on `dragId` so the
    // reveal still runs once the drag settles.
    LaunchedEffect(activeId, tabs.map { it.id }, chipWidths.toMap(), dragId) {
        withFrameNanos { }
        if (dragId != null) return@LaunchedEffect
        val id = activeId ?: return@LaunchedEffect
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0) return@LaunchedEffect
        var start = 0
        for (i in 0 until index) {
            start += (chipWidths[tabs[i].id] ?: return@LaunchedEffect).toInt()
        }
        val width = (chipWidths[id] ?: return@LaunchedEffect).toInt()
        val viewport = scroll.viewportSize
        if (viewport <= 0) return@LaunchedEffect
        // v47: the reveal ends at the chip's own end — there is no trailing
        // slot inside the scroll content any more (it scrolled as dead travel
        // past the last chip, `tD_35_right2/39`), so the last chip parks with
        // its end flush at the viewport end, fully visible next to the fixed
        // action group. [TerminalTabScroll.reveal] still clamps to the strip's
        // own `[0, maxScroll]`.
        val target = TerminalTabScroll.reveal(start, width, viewport, scroll.value, scroll.maxValue)
        if (target != scroll.value) scroll.animateScrollTo(target)
    }
}

/**
 * v47: one edge fade of the terminal tab strip's chips viewport — a plain
 * strip-colour → transparent gradient box the strip lays *over* the scrolled
 * row's leading / trailing edge (only while content hides beyond that edge,
 * see [TerminalStripChrome.fadeEdges]). The same overlay recipe the composer's
 * attachment row already ships (`AttachmentEdgeFade`,
 * `AttachmentPreviewRow.kt`): no blend modes, no offscreen, nothing a real
 * GPU driver can silently drop.
 *
 * Why not a `DstIn` mask (the v46 approach this replaces): the mask was drawn
 * by a modifier *inside* `horizontalScroll`, but `horizontalScroll` offsets its
 * content with a placement-layer transform (`ScrollingLayoutNode.measure` →
 * `placeRelativeWithLayer`, foundation 1.7.6 `Scroll.kt`) — so the mask was
 * drawn in *content* coordinates and translated away with the scroll. Past the
 * first [STRIP_EDGE_FADE] of travel both masks sat off-viewport (the leading
 * one fully clipped, the trailing one shifted onto empty row space, where it
 * erases nothing because the strip background lives outside the mask's
 * layer) — which is exactly the v47 evidence: `tD_37_mid2` hard-cuts
 * `erminal 27` / `Terminal 28` on both edges with no fade anywhere and no
 * erased slit either. An overlay in the strip's own (unscrolled) `Box` stays
 * pinned to the viewport by construction.
 *
 * A mid-scroll chip would otherwise hard-cut mid-glyph at the clip edge
 * (`erminal 14`, a chip decayed to bare `× ⋯` — `tD_28/29/47`), which reads as
 * broken rendering rather than "there is more to scroll to".
 */
@Composable
private fun TerminalStripEdgeFade(
    leading: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    Box(
        modifier = modifier
            .width(STRIP_EDGE_FADE)
            .fillMaxHeight()
            .background(
                Brush.horizontalGradient(
                    if (leading) listOf(c.bgBase, Color.Transparent)
                    else listOf(Color.Transparent, c.bgBase),
                ),
            ),
    )
}

/**
 * D-15b: which of the terminal strip's two long-press detectors owns the drag in
 * progress. [TerminalTabStrip] runs both on the same press — the strict
 * `detectDragGesturesAfterLongPress` and the re-armed [awaitLongPressClaim] — so
 * the first to claim it drives the reorder and the other's callbacks stay
 * no-ops (a still hold reaches both deadlines on the same frame).
 */
private enum class TerminalDragOwner { HOLD, CLAIM }

/**
 * D-15b: the long press the strip's own scroll cannot kill.
 *
 * `detectDragGesturesAfterLongPress` gives its hold up the moment *anything*
 * consumes a pointer change — `awaitLongPressOrCancellation` re-checks the Final
 * pass of every event (`DragGestureDetector.kt:915-921`) — and this strip
 * scrolls horizontally, so its `horizontalScroll` consumed the movement as soon
 * as the finger passed the touch slop, i.e. well inside the long-press timeout.
 * A press that moved at all therefore never became a reorder while a *still* one
 * did: exactly the "dragging a tab only scrolls the strip" report. The strict
 * detector's other limit is the chip's own bounds (`it.isOutOfBounds`), which a
 * reorder-sized travel leaves.
 *
 * This detector waits out the same timeout and asks only that the pointer still
 * be down, then claims the gesture and consumes from that point on — which is
 * what stops the strip's scroll and cancels the chip's tap. [down] is the
 * already-awaited first down. v40: a press that had already passed the slop by
 * then was the strip's own scroll (the chips are flush, so there is nowhere else
 * to drag from and suspending the scroll made the overflow unreachable), so the
 * travel decides — see [TerminalTabGesture.press].
 *
 * @param onClaim returns `false` when the strict detector already owns the
 *   gesture — the claim is then dropped without touching the pointer.
 * @param onDrag the x travel since the press, then each further step.
 */
private suspend fun AwaitPointerEventScope.awaitLongPressClaim(
    down: PointerInputChange,
    onClaim: () -> Boolean,
    onDrag: (Float) -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit,
) {
    var travel = 0f
    var last = down.position
    // The hold window: read the movement without consuming it, so the strict
    // detector still reaches its own deadline and a flick keeps scrolling.
    val liftedBeforeDeadline = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
        var lifted = false
        while (!lifted) {
            val change = awaitPointerEvent(PointerEventPass.Initial)
                .changes.firstOrNull { it.id == down.id }
            if (change == null || !change.pressed) {
                lifted = true
            } else {
                travel += change.position.x - last.x
                last = change.position
            }
        }
        true
    } ?: false
    if (liftedBeforeDeadline) return
    // v40: the strip scrolls under a moving finger again, so a press that is
    // already a scroll must not be stolen at the deadline — the finger would
    // jump back into a reorder halfway through the drag.
    if (TerminalTabGesture.press(travel, viewConfiguration.touchSlop) == TerminalTabGesture.Press.SCROLL) {
        return
    }
    if (!onClaim()) return
    onDrag(travel)
    while (true) {
        val change = awaitPointerEvent(PointerEventPass.Initial)
            .changes.firstOrNull { it.id == down.id } ?: break
        change.consume()
        val dx = change.position.x - last.x
        last = change.position
        if (!change.pressed) {
            onEnd()
            return
        }
        onDrag(dx)
    }
    onCancel()
}

/**
 * WAVE 2.3a (A4 #20): the chip's ⋮ menu holds rename/close/move
 * (`session/terminal/tab.tsx:66-177`); D-15 adds long-press drag reorder.
 *
 * Desktop gestures map to touch as: double-tap = the chip's `onDoubleClick`
 * path is unavailable in Compose `clickable`, so rename starts from the menu —
 * the menu's Rename row is the discoverable path and double-click on a hardware
 * mouse still lands here through the menu. A **long press that does not move**
 * opens that same menu (the ⋮ target is 16 dp), and a **long press that moves**
 * reorders the strip: [dragModifier] carries the gesture the strip composed,
 * because the strip owns the drag state that has to outlive the reorder
 * ([TerminalTabs.move] through `onMoveTab`). Middle-click close still has no
 * touch equivalent; the menu's move-left/move-right rows remain for
 * one-handed/keyboard precision on top of the drag.
 *
 * D-15b: the strip's own scroll no longer stands down for a chip press, so a
 * plain drag across the chips scrolls the strip and a hold that never moved
 * reorders (or opens this menu) — see [TerminalTabGesture]. The strip scrolls
 * from anywhere on it, and every chip also stays reachable through the
 * active-chip reveal.
 */
@Composable
private fun TerminalTabChip(
    title: String,
    active: Boolean,
    /** v50: the PTY's `cwd` predates the session's current directory. */
    stale: Boolean = false,
    defaultTitle: String,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    onRename: (String) -> Unit,
    onMoveLeft: (() -> Unit)?,
    onMoveRight: (() -> Unit)?,
    /** D-15: the strip's drag gesture for this chip (see [TerminalTabStrip]). */
    dragModifier: Modifier = Modifier,
    /** Whether this chip's menu is open — the strip hoists it so a long press
     * that never moved can open it too. */
    menuOpen: Boolean = false,
    onOpenMenu: () -> Unit = {},
    onDismissMenu: () -> Unit = {},
) {
    val c = OcTheme.colors
    var editing by remember { mutableStateOf(false) }
    // v47: the draft opens prefilled with the chip's *displayed* title — the
    // custom name when set, else the numbered default — so typing edits the
    // current name instead of replacing an empty value (`tD_45/46`: the field
    // opened on the placeholder alone and one keystroke renamed the tab to
    // just that stroke). The session strips prefill the same way (`renameDraft
    // = tab.title…`, and a session always carries a title); a numbered
    // terminal tab has no custom title, so the prefill is the resolved label.
    var draft by remember(title, editing) { mutableStateOf(title) }
    val staleLabel = stringResource(R.string.oc_terminal_cwd_stale)
    Row(
        modifier = dragModifier
            .height(TAB_HEIGHT)
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) c.layer02 else Color.Transparent)
            .clickable(role = Role.Tab, onClick = onSelect)
            // v51: the chip's own label is truncated to one 96 dp line, so the
            // full「旧目录」wording — the part the visible text shortens — rides
            // the content description instead of being lost to the ellipsis.
            .semantics {
                contentDescription =
                    if (stale) TerminalTabStaleLabel.semantics(staleLabel, title) else title
            }
            .padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (editing) {
            OcTerminalRenameField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = defaultTitle,
                onConfirm = {
                    editing = false
                    onRename(draft)
                },
                onDismiss = { editing = false },
            )
        } else {
            Text(
                // v50: a chip whose shell was launched before the session was
                // moved keeps its old `pwd` (re-rooting a live build is worse),
                // so the label says so instead of silently showing a stale
                // directory.
                // v51: the marker itself used up the line — two stale chips both
                // read「Old dir · Termi…」and the number was what told them apart
                // (`v51_62_term_after_move.png`). Shortened prefix on the chip,
                // full wording in the content description below.
                text = if (stale) TerminalTabStaleLabel.chip(staleLabel, title) else title,
                color = if (active) c.textBase else c.textMuted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(96.dp),
            )
        }
        Box {
            Icon(
                painter = painterResource(R.drawable.ic_oc_close),
                contentDescription = stringResource(R.string.oc_terminal_close),
                tint = c.iconMuted,
                modifier = Modifier
                    .size(TAB_CLOSE_SIZE)
                    .alpha(if (active) 1f else 0.7f)
                    .clickable(role = Role.Button, onClick = onClose),
            )
        }
        Box {
            IconAction(
                iconRes = R.drawable.ic_oc_dots,
                contentDescription = defaultTitle,
                onClick = onOpenMenu,
            )
            androidx.compose.material3.DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = onDismissMenu,
                // v11: M3's default menu surface (`surfaceContainer`) is a light
                // translucent tint the shell's palette never re-themes, so the
                // dark theme showed the rows on a near-white panel. Opaque layer
                // token + zero tonal elevation, like the other shell menus
                // (`ui/session/context/SessionContextPanel.kt:419`).
                shape = RoundedCornerShape(6.dp),
                containerColor = c.layer01,
                tonalElevation = 0.dp,
                border = BorderStroke(0.5.dp, c.borderMuted),
            ) {
                androidx.compose.material3.DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.common_rename),
                            fontSize = 13.sp,
                            color = c.textBase,
                        )
                    },
                    onClick = {
                        onDismissMenu()
                        draft = title
                        editing = true
                    },
                )
                if (onMoveLeft != null) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.oc_terminal_move_left),
                                fontSize = 13.sp,
                                color = c.textBase,
                            )
                        },
                        onClick = {
                            onDismissMenu()
                            onMoveLeft()
                        },
                    )
                }
                if (onMoveRight != null) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.oc_terminal_move_right),
                                fontSize = 13.sp,
                                color = c.textBase,
                            )
                        },
                        onClick = {
                            onDismissMenu()
                            onMoveRight()
                        },
                    )
                }
                androidx.compose.material3.DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.oc_terminal_close),
                            fontSize = 13.sp,
                            color = c.stateDangerFg,
                        )
                    },
                    onClick = {
                        onDismissMenu()
                        onClose()
                    },
                )
            }
        }
    }
}

/**
 * WAVE 2.3a (A4 #20): the inline rename field (upstream `tab.tsx` Enter =
 * save, Escape = cancel). Plain `BasicTextField` — the terminal file scope
 * must not depend on the settings components.
 *
 * v33 BUG-2: it opens with the **whole** name selected, like the session strip's
 * `TabRenameField` (`tabRenameEntrySelection`, `BottomTabStrip.kt`) and the
 * header's editor (`InlineTitleEdit.entrySelection`). A `String`-valued
 * `BasicTextField` seeds `TextFieldValue(text = value)` — `TextRange.Zero` — so
 * the cursor sat in front of the old name and the first keystroke spliced into
 * it; upstream selects the title instead.
 *
 * v40 BUG-3: nothing focused this field when the menu's Rename row opened it, so
 * it never got the IME and every keystroke went to whatever held focus instead
 * (the root keybind focus: a letter came back as a global chord, and the rename
 * could not be completed at all). The chip asks for focus and the keyboard the
 * moment it is composed, the way the session strip's editor does.
 */
@Composable
private fun OcTerminalRenameField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = OcTheme.colors
    // The value overload, so the entry selection can be expressed at all; the
    // draft itself still lives in the caller (every edit is echoed back through
    // `onValueChange`). The selection is the whole title — the same entry
    // contract as the session strip's field ([TerminalTabRename.entrySelection],
    // mirroring `tabRenameEntrySelection`).
    var edit by remember { mutableStateOf(TextFieldValue(value, TerminalTabRename.entrySelection(value))) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // The menu row that opened this is gone by now, so nothing else holds focus:
    // this is what raises the soft keyboard (and it has to be the editor that
    // does it — the field is the only focused node in the panel while editing).
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }
    Box(
        Modifier
            .width(96.dp)
            .background(c.bgDeep, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        if (value.isEmpty()) {
            Text(placeholder, color = c.textFaint, fontSize = 12.sp, maxLines = 1)
        }
        androidx.compose.foundation.text.BasicTextField(
            value = edit,
            onValueChange = {
                edit = it
                onValueChange(it.text)
            },
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = c.textBase, fontSize = 12.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(c.textAccent),
            // The terminal name is an identifier the tab strip shows and the
            // store persists verbatim: Gboard's autocorrect rewrote it (and the
            // pinyin IME composed over it) before this.
            keyboardOptions = OcKeyboard.identifier(imeAction = ImeAction.Done),
            // Hardware Enter arrives as a key event; the phone's IME sends the
            // Done action, so both paths confirm.
            keyboardActions = KeyboardActions(onDone = { onConfirm() }),
            modifier = Modifier
                .width(96.dp)
                .focusRequester(focus)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Enter, Key.NumPadEnter -> {
                            onConfirm()
                            true
                        }
                        Key.Escape -> {
                            onDismiss()
                            true
                        }
                        else -> false
                    }
                },
        )
    }
}

/** Ghost icon button of the strip (unlike M3 `IconButton`, it has no ripple box). */
@Composable
private fun IconAction(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconModifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = OcTheme.colors
    Box(
        modifier = modifier
            .size(ICON_TARGET)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = if (enabled) c.iconBase else c.iconMuted,
            modifier = iconModifier.size(20.dp).alpha(if (enabled) 1f else 0.5f),
        )
    }
}

/**
 * The render slot — **phase 2: the vendored termux `TerminalView` itself**
 * (`research/06` §1.6 step 6).
 *
 * ```
 * TerminalView(ctx, null).apply {
 *     setTerminalViewClient(client)
 *     attachIo(runtime.io)                    // ← the A′ patch: no TerminalSession, no JNI
 *     setTerminalColors(palette)              // ← emulator.mColors from OcTheme.colors
 *     setTypeface(resolveTerminalTypeface())  // ← JetBrains Mono, else system mono
 *     setTextSize(textSizePx)
 *     setTerminalCursorBlinkerRate(blinkRate) // ← cursor blink, stopped on release
 *     setTerminalCursorBlinkerState(true, true)
 * }
 * ```
 *
 * Sizing is no longer computed here: `TerminalView.updateSize()` reads its own
 * `TerminalRenderer` metrics (`getFontWidth()` / `getFontLineSpacing()` /
 * `getFontLineSpacingAndAscent()`, `research/06` §1.6 step 5) and calls
 * `TerminalIo.updateSize(cols, rows, cellW, cellH)`, which is what pushes the grid through
 * [PtySizeGate]. The outer `Box` keeps the Compose-side size as the single source of truth for
 * *layout*; the view turns it into cells.
 *
 * Text-size changes (`setTextSize`) rebuild the renderer, so the update block re-applies the
 * typeface/size and calls `updateSize()` again — required, otherwise the grid keeps the old cell
 * count (`research/06` §1.6 step 6).
 */
@Composable
private fun TerminalSlot(
    runtime: TerminalRuntime,
    fontFamily: String,
    /** v73 P2 — the outstanding ask for the keyboard; see [TerminalFocusRequest]. */
    focusRequest: TerminalFocusRequest? = null,
    /** v74 — where the *answer* to that ask is reported; see [TerminalImeFocus]. */
    imeFocus: TerminalImeFocus? = null,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    val density = LocalDensity.current
    var textSizeSp by remember(runtime.id) { mutableStateOf(TERMINAL_FONT_SIZE.value) }
    val textSizePx = remember(textSizeSp, density) {
        with(density) { textSizeSp.sp.toPx() }.toInt().coerceAtLeast(1)
    }
    val typeface = remember(fontFamily) { resolveTerminalTypeface(fontFamily) }
    val palette = remember(c) { terminalPalette(c) }
    val viewClient = remember(runtime.id) {
        OcTerminalViewClient(
            onPinch = { factor ->
                textSizeSp = textSizeSp
                    .times(factor)
                    .coerceIn(TERMINAL_MIN_FONT_SP, TERMINAL_MAX_FONT_SP)
                true
            },
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(c.bgDeep),
    ) {
        AndroidView(
            factory = { context ->
                val view = TerminalView(context, null)
                view.setTerminalViewClient(viewClient)
                // A′ patch: install the remote transport (emulator + input sink + size sink).
                runtime.io?.let { io -> view.attachIo(io) }
                applyPalette(runtime.termux.emulator, palette)
                // Order matters: the vendored `setTextSize` is what *creates* the
                // `TerminalRenderer` (`TerminalView.java:511-513`), while
                // `setTypeface` dereferences `mRenderer.mTextSize` with no null
                // check (`:516-517`). Calling setTypeface first throws
                // NPE("Attempt to read from field 'int TerminalRenderer.mTextSize'")
                // inside the AndroidView factory — reproduced on both entry points
                // (terminal route chip and the embedded terminal tab).
                view.setTextSize(textSizePx)
                view.setTypeface(typeface)
                view.isFocusable = true
                view.isFocusableInTouchMode = true
                runtime.terminalView = view
                // v74 — the pane's answer to the ask, and the ground truth the v73
                // port was missing: the IME follows the window's **view** focus, so
                // this is what says whether the terminal really owns the keyboard.
                // The composer reads it before its own mount-time focus, which is
                // the effect that used to take the keyboard back
                // ([com.xiufeigo.opencodeshell.ui.session.TerminalImeFocus]).
                view.setOnFocusChangeListener { _, hasFocus -> imeFocus?.report(hasFocus) }
                // v73 P2 — upstream's `autoFocus` (`terminal/panel.tsx:356-361`,
                // satisfied by the view being created for a terminal the user just
                // turned to, see [TerminalFocusRequest]): the terminal takes the
                // keyboard as soon as it is attached. `post` is what makes this
                // reliable — the factory runs before the view has a window, where a
                // direct `requestFocusFromTouch()` is a silent no-op — and the
                // `post` queue is drained by the attach itself, so it lands on the
                // frame the pane really appears. Consuming the ask here is what
                // keeps it one-shot: a later re-mount (workspace switch, revive,
                // rotation) must not steal the caret back from the composer.
                //
                // v74: one request is still not enough — see [claimTerminalFocus],
                // which re-asks over the next frames until the focus really lands.
                if (focusRequest?.pending(runtime.id) == true) {
                    focusRequest.consume()
                    view.post { claimTerminalFocus(view, attempt = 0) }
                }
                view
            },
            update = { view ->
                // Defence behind the call-site key: a live view must never keep
                // another tab's transport — input would land in that tab's PTY.
                val io = runtime.io
                if (io != null && view.mIo !== io) view.attachIo(io)
                // Same ordering rule as the factory (renderer creation is owned
                // by setTextSize); this path runs on recomposition, where the
                // renderer already exists, but keeping one order avoids the trap
                // resurfacing if the factory path ever changes.
                view.setTextSize(textSizePx)
                view.setTypeface(typeface)
                applyPalette(runtime.termux.emulator, palette)
                // A font/size change rebuilds the renderer: the grid must be recomputed and
                // re-pushed (`research/06` §1.6 step 6).
                view.updateSize()
                view.setTerminalCursorBlinkerRate(CURSOR_BLINK_RATE_MS)
                view.setTerminalCursorBlinkerState(runtime.isLive, true)
                if (!runtime.isLive) view.invalidate()
            },
            onRelease = { view ->
                // Called when the view leaves the composition *and* when Compose disposes it
                // (Compose 1.7 semantics). Patch `TerminalView.detachFromTerminalHost()` stops the
                // cursor-blink handler (upstream `onDetachedFromWindow()` leaves it scheduled),
                // runs the upstream detach cleanup and makes further input a no-op.
                if (runtime.terminalView === view) runtime.terminalView = null
                // v74: the view that answered the ask is going away — the pane holds
                // no keyboard through it any more. (`setOnFocusChangeListener` does
                // not fire on detach, so this is the report the pane would miss.)
                imeFocus?.report(false)
                view.detachFromTerminalHost()
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * How many times a freshly created terminal re-asks for the focus before it lets
 * the user decide; see [claimTerminalFocus].
 */
private const val FOCUS_CLAIM_ATTEMPTS = 3

/**
 * v74 — the pane's claim on the keyboard, re-asserted until it really lands.
 *
 * A single `requestFocusFromTouch()` from the `AndroidView` factory is not enough
 * on device, which is why the v73 port of upstream's `autoFocus` left the terminal
 * with no input target at all: the factory runs before the view is laid out (and,
 * on the frame that opens the pane, in the same pass that re-lays the timeline the
 * composer lives in), so the one request could be spent before the view was able
 * to take the focus — and nothing asked again. Measured on `emulator-5556`
 * (`research/v74_verify/5556/08_term_type_notap.png`, `13_term_tap2.png`): the pane
 * was up with its prompt and block cursor while the keyboard's Enter key still wore
 * the composer's Send action, so the IME was never handed over and every keystroke
 * went to the composer — or, with the pane squeezing the composer to nothing,
 * nowhere at all.
 *
 * Re-asking covers exactly those frames, one **frame** apart (`postOnAnimation`,
 * not `post`: three posts would drain in the same message-loop pass, before the
 * view could have been laid out at all). It stops by itself: after
 * [FOCUS_CLAIM_ATTEMPTS] the routing belongs to whoever the user turns to next
 * (`TerminalView`'s own `requestFocusFromTouch` on tap, the composer's field on
 * tap), and a terminal that still could not take the focus must not keep fighting
 * a tap on the composer.
 */
private fun claimTerminalFocus(view: TerminalView, attempt: Int) {
    if (view.hasFocus()) return
    runCatching { view.requestFocusFromTouch() }
    if (!view.hasFocus() && attempt < FOCUS_CLAIM_ATTEMPTS) {
        view.postOnAnimation { claimTerminalFocus(view, attempt + 1) }
    }
}

/**
 * `TerminalViewClient` for the panel: upstream defaults, plus pinch-to-zoom for the font size
 * (`onScale` is the one gesture hook the view offers). Nothing here synthesises keys — `onKeyDown`
 * / `onCodePoint` return `false`, which is what lets `TerminalView`'s own `handleKeyCode` +
 * `InputConnection` path send real escape sequences.
 */
private class OcTerminalViewClient(
    private val onPinch: (Float) -> Boolean,
) : TerminalViewClient {

    override fun onScale(scale: Float): Float = if (onPinch(scale)) 1f else 1f

    override fun onSingleTapUp(e: MotionEvent?) {
        // Upstream shows/hides the extra-keys row here; the panel has no such row yet.
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    /** `TYPE_NULL` (the most correct input type) rather than the Samsung workaround. */
    override fun shouldEnforceCharBasedInput(): Boolean = false

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun copyModeChanged(copyMode: Boolean) {
        // Text selection is active: nothing for the Compose layer to do (the View handles it).
    }

    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false

    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false

    override fun onLongPress(event: MotionEvent?): Boolean = false

    override fun readControlKey(): Boolean = false

    override fun readAltKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false

    override fun onEmulatorSet() {
        // The emulator exists now; `attachIo`/`updateSize` already pushed the first grid.
    }

    override fun logError(tag: String?, message: String?) = Unit

    override fun logWarn(tag: String?, message: String?) = Unit

    override fun logInfo(tag: String?, message: String?) = Unit

    override fun logDebug(tag: String?, message: String?) = Unit

    override fun logVerbose(tag: String?, message: String?) = Unit

    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit

    override fun logStackTrace(tag: String?, e: Exception?) = Unit
}

/** The emulator's own callbacks (title/bell/clipboard); the panel only needs them to be non-null. */
private class OcTerminalSessionClient : NoopSessionClient()

/**
 * `{ansi 0..15, foreground, background, cursor}` from `OcTheme.colors`
 * (`research/06` §1.6 step 6: "主题色（OcTheme.colors）"). termux defaults are used for the
 * 216-colour cube and the grey ramp, which have no OC2 equivalent.
 *
 * Applied to the *emulator*, which owns the palette the renderer reads
 * (`TerminalEmulator.mColors.mCurrentColors` — the view has no colour API of its own).
 */
private fun terminalPalette(c: OcColors): IntArray {
    val colors = IntArray(TextStyle.NUM_INDEXED_COLORS)
    TerminalColors.COLOR_SCHEME.mDefaultColors.copyInto(colors)
    fun put(index: Int, color: Color) {
        colors[index] = color.toArgb()
    }
    put(0, c.bgDeep)                     // black = the terminal surface itself
    put(1, c.stateDangerFg)
    put(2, c.stateSuccessFg)
    put(3, c.stateWarningFg)
    put(4, c.textAccent)
    put(5, c.syntaxKeyword)
    put(6, c.syntaxString)
    put(7, c.textMuted)
    put(8, c.textFaint)
    put(9, c.stateDangerFg)
    put(10, c.stateSuccessFg)
    put(11, c.stateWarningFg)
    put(12, c.textAccent)
    put(13, c.syntaxKeyword)
    put(14, c.syntaxString)
    put(15, c.textBase)
    put(TextStyle.COLOR_INDEX_FOREGROUND, c.textBase)
    put(TextStyle.COLOR_INDEX_BACKGROUND, c.bgDeep)
    put(TextStyle.COLOR_INDEX_CURSOR, c.borderFocus)
    return colors
}

/** Copies [palette] into the emulator's live palette (`TerminalEmulator.mColors`). */
private fun applyPalette(emulator: TerminalEmulator, palette: IntArray) {
    val colors = emulator.mColors.mCurrentColors
    for (index in colors.indices) {
        if (index < palette.size) colors[index] = palette[index]
    }
}

/**
 * JetBrains Mono when the platform can resolve `settings.terminalFont`, otherwise the system
 * monospace stack — `research/06` §1.6 step 6 says "没有 TTF 则回退 mono", and the repository
 * currently ships no JetBrains Mono TTF (only the web `woff2`, which Android cannot load:
 * `ui/theme/OcFonts.kt` `OcTerminalFontFamily` TODO(M5)).
 */
internal fun resolveTerminalTypeface(fontFamily: String?): Typeface {
    val requested = fontFamily?.trim().orEmpty()
    if (requested.isEmpty()) return Typeface.MONOSPACE
    val resolved = Typeface.create(requested, Typeface.NORMAL) ?: return Typeface.MONOSPACE
    // `Typeface.create` silently returns the default family for unknown names; detect that by
    // comparing against the default we would otherwise use, and prefer an explicit mono fallback.
    return if (resolved == Typeface.create(Typeface.DEFAULT, Typeface.NORMAL) &&
        requested.lowercase() !in DEFAULT_FAMILY_NAMES
    ) {
        Typeface.MONOSPACE
    } else {
        resolved
    }
}

/** Font families whose resolution to `Typeface.DEFAULT` is genuinely intended. */
private val DEFAULT_FAMILY_NAMES = setOf("default", "sans-serif", "sans", "serif", "normal")

/**
 * Entry affordance for the session route (`App.kt`): 28 dp ghost square.
 *
 * [active] highlights it while the docked pane is open, so the chip reads as a
 * toggle rather than a "go somewhere" button (upstream's side panel toggle wears
 * the same accent treatment).
 */
@Composable
fun TerminalEntryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
) {
    val c = OcTheme.colors
    // Hoisted: `semantics {}` is not a composable scope. The label rides the
    // clickable Box, not the `Icon` inside it.
    val openLabel = stringResource(R.string.oc_terminal_open)
    Box(
        modifier = modifier
            .size(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) c.layer02 else c.layer01)
            .border(0.5.dp, if (active) c.borderStrong else c.borderMuted, RoundedCornerShape(6.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = openLabel },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_oc_console),
            contentDescription = null,
            tint = if (active) c.iconBase else c.iconMuted,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** `Tabs` strip height in v2 (`!h-[52px]`; legacy `h-10`). */
private val TAB_STRIP_HEIGHT = 52.dp

/** `Tabs.Trigger` height inside the 52 dp strip. */
private val TAB_HEIGHT = 36.dp

/** `close-small` hit box (`h-5 w-5` = 20 dp; the touch target stays 20 dp). */
private val TAB_CLOSE_SIZE = 20.dp

/** Ghost icon hit box, matching the 40 dp rows of the M2 header. */
private val ICON_TARGET = 40.dp

/**
 * v46: the strip's edge-fade width — wide enough to read as "scrollable" on a
 * mid-scroll chip, narrow enough to leave a ≈155 dp viewport legible.
 */
private val STRIP_EDGE_FADE = 12.dp

/** Upstream terminal font size: `fontSize: 14` (`research/02` §9.1). */
private val TERMINAL_FONT_SIZE = 14.sp

/** Pinch-to-zoom bounds; termux itself only supports whole-text-size steps. */
private const val TERMINAL_MIN_FONT_SP = 8f
private const val TERMINAL_MAX_FONT_SP = 32f

/**
 * Grid the emulator starts with, before the view is measured. 80×24 is the
 * classic terminal default and what `TerminalView.updateSize()` would fall back
 * to; the real size arrives from `TerminalRenderer` metrics on the first layout.
 */
private const val TERMINAL_INITIAL_COLS = 80
private const val TERMINAL_INITIAL_ROWS = 24

/**
 * Cursor blink rate (`setTerminalCursorBlinkerRate`, 100–2000 ms). 600 ms is
 * termux's own default and matches the reference terminal's feel.
 */
private const val CURSOR_BLINK_RATE_MS = 600

/** Tab-chrome persistence for process-death revival (`TerminalStateStore`). */
private const val TERMINAL_TABS_PREFS = "oc_terminal_tabs"
private const val TERMINAL_TABS_KEY = "tabs"
