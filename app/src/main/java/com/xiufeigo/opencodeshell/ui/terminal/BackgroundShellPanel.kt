package com.xiufeigo.opencodeshell.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.components.OcKeyboard
import com.xiufeigo.opencodeshell.ui.terminal.share.SHELL_LIST_POLL_MS
import com.xiufeigo.opencodeshell.ui.terminal.share.ShellCreateOutcome
import com.xiufeigo.opencodeshell.ui.terminal.share.ShellOutputWindow
import com.xiufeigo.opencodeshell.ui.terminal.share.applyShellResolutions
import com.xiufeigo.opencodeshell.ui.terminal.share.sortShellRows
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import com.xiufeigo.opencodeshell.v2.ShellCommandInfo
import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.shellCreate
import com.xiufeigo.opencodeshell.v2.shellGet
import com.xiufeigo.opencodeshell.v2.shellOutput
import com.xiufeigo.opencodeshell.v2.shellRemove
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Background shell commands (`GET /api/shell` + `shellGet`/`shellOutput`/
 * `shellRemove`), the Compose twin of upstream `tui`'s `ShellTab` +
 * `DialogShellOutput`: a running-first list of captured commands, and a detail
 * pane that pages the combined stdout/stderr capture
 * ([ShellOutputWindow], 64 KiB tail, poll-while-open).
 *
 * The **create** affordance posts `POST /api/shell` ([shellCreate]) with the
 * inline form's command; `cwd` is omitted so the server runs it in the
 * location directory — the same directory the list polls, so both sides share
 * one scope by construction. Kill = `DELETE /api/shell/{id}`
 * ([shellRemove], 204).
 *
 * The created row is pinned into the list immediately: upstream lists
 * **running** shells only, so a command that exits before the next poll would
 * otherwise never appear (a silent success). Pins missing from the list are
 * resolved once via [shellGet] to their terminal status (exit code included);
 * a 404 drops the pin (removed, or spawned outside this scope), and pins a
 * create added while a poll's fetch was in flight survive that fetch
 * ([applyShellResolutions]). A failed create stays on the form as a visible
 * error carrying the server's reason, and the request itself is made under
 * `NonCancellable` — `Run` is committed, so leaving the panel must not silently
 * drop it.
 */
@Composable
fun BackgroundShellPanel(
    api: V2Api,
    locationDirectory: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    var rows by remember { mutableStateOf<List<ShellCommandInfo>>(emptyList()) }
    var listError by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf<ShellCommandInfo?>(null) }
    var creating by remember { mutableStateOf(false) }
    var command by remember { mutableStateOf("") }
    var createBusy by remember { mutableStateOf(false) }
    var createError by remember { mutableStateOf<String?>(null) }
    // Rows confirmed by a create response but (possibly) absent from the
    // running-only list: an instantly-exited shell must still appear with its
    // terminal status instead of vanishing silently. Keyed by shell id.
    //
    // Keyed on the **scope** rather than the `V2Api` instance: the client object
    // is rebuilt whenever the panel's server/directory pair is (the session's
    // directory arrives with the session list), and re-keying on that identity
    // dropped every pin — the row the user had just created disappeared again.
    var pinned by remember(locationDirectory) { mutableStateOf(emptyMap<String, ShellCommandInfo>()) }
    val scope = rememberCoroutineScope()
    val loadFailedText = stringResource(R.string.oc_terminal_shell_load_failed)
    val createFailedText = stringResource(R.string.oc_terminal_shell_create_failed)

    // Polls while the list is visible: shells are created outside this panel
    // (`POST /api/shell` belongs to another surface), so a load-once effect
    // never sees them. Paused on the detail view — it polls `shellOutput`
    // itself every second. A kill/detail-back still bumps `reload` for an
    // immediate reload (the loop restarts and fetches without waiting).
    val listVisible by rememberUpdatedState(selected == null)
    LaunchedEffect(api, locationDirectory, reload) {
        while (isActive) {
            if (listVisible) {
                listError = null
                // Snapshot the pins on the main thread; the fetch + the
                // one-shot terminal-state resolution run on IO, every state
                // write below stays on the main thread.
                val snapshot = pinned
                try {
                    val fetched = withContext(Dispatchers.IO) {
                        val listed = api.shells(locationDirectory).map(ShellCommandInfo::fromJson)
                        val listedIds = listed.map { it.id }.toSet()
                        // Pins missing from the list are either already exited
                        // (the list carries running shells only) or landed
                        // outside this scope: resolve each once via `shellGet`
                        // so the row shows its terminal status + exit code.
                        val resolutions = HashMap<String, ShellCommandInfo?>()
                        for (pin in unresolvedShellPins(snapshot, listedIds)) {
                            resolutions[pin.id] = runCatching {
                                api.shellGet(pin.id, locationDirectory)
                            }.fold(
                                onSuccess = { it },
                                onFailure = { e ->
                                    // Unknown here (removed, or spawned outside
                                    // this scope): drop the pin so no ghost row
                                    // lingers. Any other transport failure keeps
                                    // the pin for the next poll.
                                    if (e is V2Api.ApiException && e.code == 404) null else pin
                                },
                            )
                        }
                        listed to resolutions
                    }
                    // The pins added *while the fetch was out* are not part of
                    // `snapshot`: writing the snapshot back alone would drop the
                    // row a create landed in the meantime.
                    val addedSince = pinned.filterKeys { it !in snapshot }
                    pinned = applyShellResolutions(snapshot, fetched.second, addedSince)
                    rows = mergeShellRows(fetched.first, pinned.values)
                } catch (e: Exception) {
                    // Keep the last rows (including pins): a flaky poll must
                    // not wipe a just-created row; the error line shows instead
                    // of the list until the next poll succeeds.
                    listError = createErrorText(e, loadFailedText)
                }
            }
            delay(SHELL_LIST_POLL_MS)
        }
    }

    Column(modifier.fillMaxSize().background(c.bgBase)) {
        OverlayHeader(
            title = stringResource(R.string.oc_terminal_shells_title),
            onBack = onBack,
            trailing = {
                IconActionBox(
                    iconRes = R.drawable.ic_oc_plus,
                    contentDescription = stringResource(R.string.oc_terminal_shell_create),
                    enabled = !createBusy,
                    onClick = { creating = true; createError = null },
                )
            },
        )
        if (creating) {
            ShellCreateRow(
                command = command,
                onCommandChange = { command = it; createError = null },
                busy = createBusy,
                error = createError,
                onCancel = { creating = false; command = ""; createError = null },
                onConfirm = {
                    val cleaned = ShellCreateOutcome.cleanCommand(command)
                    if (cleaned == null) {
                        createError = createFailedText
                        return@ShellCreateRow
                    }
                    createBusy = true
                    createError = null
                    // The blocking create runs on IO; every state write below
                    // stays on the main thread, so the `reload` bump reliably
                    // restarts the list fetch and the `LazyColumn` recomposes.
                    // `cwd` is omitted: the server runs the command in the
                    // location directory, the same scope the list polls.
                    //
                    // `UNDISPATCHED` + [NonCancellable]: `Run` is a committed
                    // action, so the request leaves *now* and cannot be dropped
                    // by the panel leaving the composition (a session switch, a
                    // remount) — a cancelled create is what ran nothing and left
                    // the list empty with no error to show for it.
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        val created = withContext(NonCancellable + Dispatchers.IO) {
                            runCatching {
                                api.shellCreate(
                                    command = cleaned,
                                    locationDirectory = locationDirectory,
                                )
                            }
                        }
                        createBusy = false
                        // Pin the created row immediately: the running-only
                        // list may never contain it (instant exit), and a bare
                        // refetch would then look like nothing happened.
                        val info = created.getOrNull()?.takeIf { it.id.isNotBlank() }
                        if (info != null) {
                            pinned = pinned + (info.id to info)
                        } else {
                            createError = createErrorText(created.exceptionOrNull(), createFailedText)
                        }
                        for (effect in ShellCreateOutcome.effects(info?.id)) {
                            when (effect) {
                                is ShellCreateOutcome.Effect.CloseForm -> {
                                    if (info != null) {
                                        creating = false
                                        command = ""
                                        createError = null
                                    }
                                }
                                is ShellCreateOutcome.Effect.Refetch -> reload++
                                is ShellCreateOutcome.Effect.ShowCreateError -> {
                                    if (createError == null) createError = createFailedText
                                }
                            }
                        }
                    }
                },
            )
        }
        val open = selected
        if (open != null) {
            ShellOutputDetail(
                shell = open,
                locationDirectory = locationDirectory,
                api = api,
                onBackToList = { selected = null; reload++ },
                onKilled = { selected = null; reload++ },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        } else {
            when {
                listError != null -> Text(
                    text = listError!!,
                    color = c.stateDangerFg,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
                rows.isEmpty() -> Text(
                    text = stringResource(R.string.oc_terminal_shells_empty),
                    color = c.textFaint,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
                else -> LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(rows, key = { it.id }) { shell ->
                        ShellRow(
                            shell = shell,
                            onOpen = { selected = shell },
                            onKill = {
                                reload++
                            },
                            api = api,
                            locationDirectory = locationDirectory,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The inline `POST /api/shell` form: one command line plus Run/Cancel. Shown
 * while the header `+` action's `creating` flag is set; errors stay on this
 * row so a failed create does not wipe the list behind it.
 */
@Composable
private fun ShellCreateRow(
    command: String,
    onCommandChange: (String) -> Unit,
    busy: Boolean,
    error: String?,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    Column(modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                if (command.isEmpty()) {
                    Text(
                        text = stringResource(R.string.oc_terminal_shell_command_hint),
                        color = c.textFaint,
                        fontSize = 13.sp,
                        maxLines = 1,
                    )
                }
                BasicTextField(
                    value = command,
                    onValueChange = onCommandChange,
                    singleLine = true,
                    enabled = !busy,
                    textStyle = TextStyle(color = c.textBase, fontSize = 13.sp),
                    cursorBrush = SolidColor(c.textAccent),
                    // A shell command is executed verbatim; autocorrect rewrote
                    // `:` to `：` and "fixed" flags before this.
                    keyboardOptions = OcKeyboard.literal(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                text = stringResource(R.string.oc_common_cancel),
                color = c.textMuted,
                fontSize = 13.sp,
                // Disabled while the create is in flight, like `Run`: closing
                // the form mid-request discarded the command silently (the
                // request kept running, the row could never be pinned).
                modifier = Modifier.clickable(enabled = !busy, role = Role.Button, onClick = onCancel)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
            Text(
                text = stringResource(R.string.oc_terminal_shell_run),
                color = if (busy) c.textFaint else c.textAccent,
                fontSize = 13.sp,
                modifier = Modifier.clickable(
                    enabled = !busy,
                    role = Role.Button,
                    onClick = onConfirm,
                ).padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
        if (error != null) {
            Text(
                text = error,
                color = c.stateDangerFg,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun ShellRow(
    shell: ShellCommandInfo,
    api: V2Api,
    locationDirectory: String?,
    onOpen: () -> Unit,
    onKill: () -> Unit,
) {
    val c = OcTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                // `shell-tab.tsx`: the first line of the command is the row label.
                text = shell.command.substringBefore('\n').ifEmpty { shell.id },
                color = c.textBase,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = shellStatusText(shell),
                color = if (shell.running) c.stateSuccessFg else c.textMuted,
                fontSize = 11.sp,
                maxLines = 1,
            )
        }
        if (shell.running) {
            IconActionBox(
                iconRes = R.drawable.ic_oc_stop,
                contentDescription = stringResource(R.string.oc_terminal_shell_kill),
                enabled = true,
                onClick = {
                    // `DELETE /api/shell/{id}` → 204: terminate + drop output.
                    Thread {
                        runCatching { api.shellRemove(shell.id, locationDirectory) }
                    }.start()
                    onKill()
                },
            )
        }
    }
}

/**
 * The output viewer (`tui/src/component/dialog-shell-output.tsx`): pages the
 * capture into a [ShellOutputWindow] tail, polls while open (immediately while
 * more remains, 1 s once caught up — including after exit, so the final file
 * flush is observed) and shows status/command above the text.
 */
@Composable
private fun ShellOutputDetail(
    shell: ShellCommandInfo,
    api: V2Api,
    locationDirectory: String?,
    onBackToList: () -> Unit,
    onKilled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    var info by remember(shell.id) { mutableStateOf(shell) }
    var output by remember(shell.id) { mutableStateOf<String?>(null) }
    var omitted by remember(shell.id) { mutableStateOf(false) }
    var error by remember(shell.id) { mutableStateOf<String?>(null) }
    val loadFailedText = stringResource(R.string.oc_terminal_shell_load_failed)
    val emptyOutputText = stringResource(R.string.oc_terminal_shell_empty_output)

    LaunchedEffect(shell.id, api, locationDirectory) {
        val window = ShellOutputWindow()
        var primed = false
        while (isActive) {
            try {
                val more = withContext(Dispatchers.IO) {
                    if (info.status == ShellCommandInfo.STATUS_RUNNING) {
                        runCatching { api.shellGet(shell.id, locationDirectory) }.getOrNull()
                            ?.let { info = it }
                    }
                    if (!primed) {
                        val head = api.shellOutput(
                            shell.id,
                            cursor = ShellOutputWindow.CATCHUP_CURSOR,
                            limit = null,
                            locationDirectory = locationDirectory,
                        )
                        window.prime(head.size)
                        primed = true
                    }
                    val page = api.shellOutput(
                        shell.id,
                        cursor = window.cursor,
                        limit = ShellOutputWindow.PAGE_BYTES,
                        locationDirectory = locationDirectory,
                    )
                    window.append(page.output, page.cursor, page.size)
                }
                output = window.text
                omitted = window.omitted
                error = null
                delay(if (more) 0L else 1_000L)
            } catch (e: Exception) {
                error = e.message ?: loadFailedText
                delay(1_000L)
            }
        }
    }

    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.oc_terminal_shell_output_title),
                color = c.textBase,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = shellStatusText(info),
                color = c.textMuted,
                fontSize = 11.sp,
            )
            IconActionBox(
                iconRes = R.drawable.ic_oc_chevron_down,
                contentDescription = stringResource(R.string.oc_terminal_close_panel),
                iconModifier = Modifier.rotate(90f),
                onClick = onBackToList,
            )
        }
        Text(
            text = info.command,
            color = c.textMuted,
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
        )
        if (omitted) {
            Text(
                text = stringResource(R.string.oc_terminal_shell_omitted),
                color = c.textFaint,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 6.dp),
        ) {
            Text(
                text = output ?: stringResource(R.string.oc_common_loading),
                color = c.textBase,
                fontSize = 12.sp,
            )
            if (output?.isEmpty() == true) {
                Text(text = emptyOutputText, color = c.textFaint, fontSize = 12.sp)
            }
        }
        if (error != null) {
            Text(
                text = error!!,
                color = c.stateDangerFg,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
            )
        }
        if (info.running) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) {
                        Thread {
                            runCatching { api.shellRemove(shell.id, locationDirectory) }
                        }.start()
                        onKilled()
                    }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = stringResource(R.string.oc_terminal_shell_kill),
                    color = c.stateDangerFg,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
internal fun shellStatusText(shell: ShellCommandInfo): String = when (shell.status) {
    ShellCommandInfo.STATUS_RUNNING -> stringResource(R.string.oc_terminal_shell_status_running)
    ShellCommandInfo.STATUS_TIMEOUT -> stringResource(R.string.oc_terminal_shell_status_timeout)
    ShellCommandInfo.STATUS_KILLED -> stringResource(R.string.oc_terminal_shell_status_killed)
    else -> if (shell.exit != null) {
        stringResource(R.string.oc_terminal_shell_exit_code, shell.exit)
    } else {
        stringResource(R.string.oc_terminal_shell_status_exited)
    }
}

/**
 * Shared header for the terminal overlays: back chevron + title + trailing
 * actions. Deliberately plain Compose — the terminal scope does not depend on
 * the settings components (same rule as `OcTerminalRenameField`).
 */
@Composable
internal fun OverlayHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    val c = OcTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(c.bgBase)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconActionBox(
            iconRes = R.drawable.ic_oc_chevron_down,
            contentDescription = stringResource(R.string.oc_terminal_close_panel),
            iconModifier = Modifier.rotate(90f),
            onClick = onBack,
        )
        Text(
            text = title,
            color = c.textBase,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
        trailing()
    }
}

/** Ghost icon button, same metrics as `TerminalPanel`'s `IconAction`. */
@Composable
internal fun IconActionBox(
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
            .size(40.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            // The description rides the clickable Box, not the `Icon` inside it:
            // only the node carrying ACTION_CLICK is announced as a button, and
            // Compose reads `contentDescription` from a node's own config.
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
 * Render model of [BackgroundShellPanel]'s list: the polled (running-only)
 * rows plus the pinned create confirmations. List rows win on id conflict —
 * the server is fresher — and the union keeps the panel's running-first,
 * newest-first order ([sortShellRows]).
 */
internal fun mergeShellRows(
    listed: List<ShellCommandInfo>,
    pins: Collection<ShellCommandInfo>,
): List<ShellCommandInfo> {
    if (pins.isEmpty()) return sortShellRows(listed)
    val listedIds = listed.map { it.id }.toHashSet()
    return sortShellRows(listed + pins.filter { it.id.isNotBlank() && it.id !in listedIds })
}

/**
 * Pinned rows still needing their terminal state: running pins the list does
 * not contain (already exited, or landed outside this scope). The panel
 * resolves each once via `shellGet`; terminal pins just render.
 */
internal fun unresolvedShellPins(
    pins: Map<String, ShellCommandInfo>,
    listedIds: Set<String>,
): List<ShellCommandInfo> = pins.values.filter { it.running && it.id !in listedIds }

/**
 * Visible failure text for the create form and the list error line: the
 * cause's message carries the server's reason (`HTTP {code} [{type}]
 * {human}`), but a blank/missing message must fall back instead of rendering
 * an empty — seemingly silent — error line.
 */
internal fun createErrorText(cause: Throwable?, fallback: String): String =
    cause?.message?.takeIf { it.isNotBlank() } ?: fallback
