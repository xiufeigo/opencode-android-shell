package com.xiufeigo.opencodeshell.ui.terminal

import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
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
import com.xiufeigo.opencodeshell.ui.terminal.share.HandlerSurface
import com.xiufeigo.opencodeshell.ui.terminal.share.NoopSessionClient
import com.xiufeigo.opencodeshell.ui.terminal.share.PtyApi
import com.xiufeigo.opencodeshell.ui.terminal.share.PtyClient
import com.xiufeigo.opencodeshell.ui.terminal.share.PtyProtocol
import com.xiufeigo.opencodeshell.ui.terminal.share.PtyScreenEngine
import com.xiufeigo.opencodeshell.ui.terminal.share.TerminalTabs
import com.xiufeigo.opencodeshell.ui.terminal.share.TermuxPtyScreenEngine
import com.xiufeigo.opencodeshell.ui.theme.OcColors
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

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
    terminalFont: String = "",
    onClose: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val store = remember(context) { ServerConfigStore(context).also { it.restore() } }
    TerminalPanelHost(
        server = store.server,
        directory = directory,
        terminalFont = terminalFont,
        onClose = onClose,
        modifier = modifier,
    )
}

@Composable
fun TerminalPanelHost(
    server: ServerConfigStore.Server?,
    directory: String? = null,
    /** `settings.terminalFont` (upstream `JetBrainsMono Nerd Font Mono`). */
    terminalFont: String = "",
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    val tabs = remember { TerminalTabs() }
    val runtimes = remember { mutableStateMapOf<String, TerminalRuntime>() }
    val scope = rememberCoroutineScope()
    val main = remember { Handler(Looper.getMainLooper()) }

    // One HTTP client for the REST half and one for the sockets; both are freed
    // when the panel unmounts (close = unmount, `research/02` §9.4 for terminals
    // and §9.3 for the panel itself).
    val httpClient = remember { PtyApi.defaultHttp() }
    val socketClient = remember { PtyApi.defaultSocketClient() }
    val api = remember(server?.url, server?.username, server?.password, directory) {
        server?.let { PtyApi.forServer(it, directory, httpClient) }
    }

    // The tab model is plain Kotlin (unit-tested); Compose mirrors it.
    var tabList by remember { mutableStateOf<List<TerminalTabs.Tab>>(emptyList()) }
    var activeId by remember { mutableStateOf<String?>(null) }
    var autoCreated by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var panelError by remember { mutableStateOf<String?>(null) }
    val createFailedText = stringResource(R.string.oc_terminal_create_failed)

    fun syncTabs() {
        tabList = tabs.tabs
        activeId = tabs.activeId
    }

    fun renameTab(id: String, title: String) {
        if (tabs.rename(id, title)) syncTabs()
    }

    fun moveTab(fromIndex: Int, toIndex: Int) {
        if (tabs.move(fromIndex, toIndex)) syncTabs()
    }

    fun post(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    fun closeTab(id: String) {
        val runtime = runtimes.remove(id)
        runtime?.client?.close()
        val keepOpen = tabs.close(id)
        syncTabs()
        val activeApi = api
        if (activeApi != null) {
            // The tab is unmounted locally *and* removed server-side: leaving a
            // live shell behind for every closed tab leaks processes (v1 keeps
            // only running sessions, so a stale PTY is also invisible to the
            // WebView shell afterwards).
            scope.launch {
                withContext(Dispatchers.IO) { runCatching { activeApi.remove(id) } }
            }
        }
        // §9.3: closing the last terminal closes the panel itself.
        if (!keepOpen) onClose()
    }

    fun attachRuntime(tab: TerminalTabs.Tab) {
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
        runtime = TerminalRuntime(id = tab.id, termux = termux, client = client)
        // The view's transport: input → `PtyClient.sendBytes` (text frame), grid → `PtyClient.resize`
        // (PtySizeGate: first immediate, then 100 ms debounce → `PUT /pty/:id {size}`).
        val io = termux.RemoteTerminalIo(
            writeBytes = { bytes -> client.sendBytes(bytes) },
            resizePty = { cols, rows -> client.resize(cols, rows) },
        )
        runtime.io = io
        runtimes[tab.id] = runtime
        client.start()
    }

    fun createTerminal() {
        val activeApi = api ?: return
        if (busy || tabs.isFull) return
        busy = true
        panelError = null
        scope.launch {
            val info = withContext(Dispatchers.IO) {
                runCatching { activeApi.create(cwd = directory) }.getOrNull()
            }
            busy = false
            if (info == null) {
                panelError = createFailedText
                return@launch
            }
            // Upstream labels a tab from its **own** terminal title and falls back
            // to `terminal.title.numbered` when it is unset/default
            // (`session/terminal/tab.tsx:33-43`); the server's pty title is not
            // that value — this server answers every `POST /pty` with a random
            // code like "Terminal His6" (verified against 1.18.31), which the web
            // UI never shows ("Terminal 1"). Passing `null` keeps the numbered
            // default so the two frontends read the same.
            val tab = tabs.add(info.id, title = null, focus = true)
            if (tab == null) {
                withContext(Dispatchers.IO) { runCatching { activeApi.remove(info.id) } }
                return@launch
            }
            syncTabs()
            attachRuntime(tab)
        }
    }

    // §9.3: opening the panel with no terminal creates one (`autoCreated`).
    LaunchedEffect(api, autoCreated) {
        if (api != null && !autoCreated && tabs.isEmpty && !busy) {
            autoCreated = true
            createTerminal()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runtimes.values.forEach { runtime ->
                // Stop the cursor-blink handler *before* dropping the client: a pending
                // `postDelayed` on a detached TerminalView keeps invalidating a dead view.
                runtime.terminalView?.setTerminalCursorBlinkerState(false, false)
                runtime.client.close()
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
            busy = busy,
            canCreate = api != null && !tabs.isFull,
            onSelect = { id ->
                if (tabs.activate(id)) syncTabs()
            },
            onCreate = { createTerminal() },
            onCloseTab = { id -> closeTab(id) },
            onClosePanel = onClose,
            onRenameTab = { id, title -> renameTab(id, title) },
            onMoveTab = { from, to -> moveTab(from, to) },
        )
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
                TerminalSlot(runtime = runtime, fontFamily = terminalFont)
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
 * Per-terminal runtime: the connection, the **VT kernel** and the state Compose observes.
 * Compose state is only ever written on the main thread (the [PtyClient.Listener] callbacks arrive
 * on OkHttp's reader thread and are posted).
 *
 * Phase 2 swap: the phase-1 `PlaceholderPtyScreenEngine` became [TermuxPtyScreenEngine], which owns
 * the screen. The Compose state here is therefore only what the *chrome* needs (tab title, error
 * line, cursor for the diagnostics) — the rendered content comes from the emulator through
 * `TerminalView`.
 */
class TerminalRuntime internal constructor(
    val id: String,
    val termux: TermuxPtyScreenEngine,
    internal val client: PtyClient,
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

/** `Tabs` strip of §9.3: **52 dp** in v2, close per tab, `+` = new terminal. */
@Composable
private fun TerminalTabStrip(
    tabs: List<TerminalTabs.Tab>,
    activeId: String?,
    busy: Boolean,
    canCreate: Boolean,
    onSelect: (String) -> Unit,
    onCreate: () -> Unit,
    onCloseTab: (String) -> Unit,
    onClosePanel: () -> Unit,
    onRenameTab: (String, String) -> Unit = { _, _ -> },
    onMoveTab: (Int, Int) -> Unit = { _, _ -> },
) {
    val c = OcTheme.colors
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
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for ((index, tab) in tabs.withIndex()) {
                TerminalTabChip(
                    title = tab.title ?: stringResource(R.string.oc_terminal_title_numbered, tab.number),
                    active = tab.id == activeId,
                    currentTitle = tab.title,
                    defaultTitle = stringResource(R.string.oc_terminal_title_numbered, tab.number),
                    onSelect = { onSelect(tab.id) },
                    onClose = { onCloseTab(tab.id) },
                    onRename = { title -> onRenameTab(tab.id, title) },
                    onMoveLeft = if (index > 0) {
                        { onMoveTab(index, index - 1) }
                    } else {
                        null
                    },
                    onMoveRight = if (index < tabs.lastIndex) {
                        { onMoveTab(index, index + 1) }
                    } else {
                        null
                    },
                )
            }
        }
        IconAction(
            iconRes = R.drawable.ic_oc_plus,
            contentDescription = stringResource(R.string.oc_terminal_new),
            enabled = canCreate && !busy,
            onClick = onCreate,
        )
    }
}

/**
 * WAVE 2.3a (A4 #20): double-click renames inline, long-press opens the
 * rename/close/move menu (`session/terminal/tab.tsx:66-177`).
 *
 * Desktop gestures map to touch as: double-tap = the chip's `onDoubleClick`
 * path is unavailable in Compose `clickable`, so rename starts from the menu
 * plus an explicit double-tap detector is out of scope — the menu's Rename row
 * is the discoverable path and double-click on a hardware mouse still lands
 * here through the menu. Middle-click close and drag reorder have no touch
 * equivalent; the menu's move-left/move-right rows drive the existing model
 * `move()` instead.
 */
@Composable
private fun TerminalTabChip(
    title: String,
    active: Boolean,
    currentTitle: String?,
    defaultTitle: String,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    onRename: (String) -> Unit,
    onMoveLeft: (() -> Unit)?,
    onMoveRight: (() -> Unit)?,
) {
    val c = OcTheme.colors
    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var draft by remember(currentTitle, editing) { mutableStateOf(currentTitle ?: "") }
    Row(
        modifier = Modifier
            .height(TAB_HEIGHT)
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) c.layer02 else Color.Transparent)
            .clickable(role = Role.Tab, onClick = onSelect)
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
                text = title,
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
                onClick = { menu = true },
            )
            androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(stringResource(R.string.common_rename), fontSize = 13.sp) },
                    onClick = {
                        menu = false
                        draft = currentTitle ?: ""
                        editing = true
                    },
                )
                if (onMoveLeft != null) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.oc_terminal_move_left), fontSize = 13.sp) },
                        onClick = {
                            menu = false
                            onMoveLeft()
                        },
                    )
                }
                if (onMoveRight != null) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.oc_terminal_move_right), fontSize = 13.sp) },
                        onClick = {
                            menu = false
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
                        menu = false
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
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = c.textBase, fontSize = 12.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(c.textAccent),
            modifier = Modifier
                .width(96.dp)
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
                view
            },
            update = { view ->
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
                view.detachFromTerminalHost()
            },
            modifier = Modifier.fillMaxSize(),
        )
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
    Box(
        modifier = modifier
            .size(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) c.layer02 else c.layer01)
            .border(0.5.dp, if (active) c.borderStrong else c.borderMuted, RoundedCornerShape(6.dp))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_oc_console),
            contentDescription = stringResource(R.string.oc_terminal_open),
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
