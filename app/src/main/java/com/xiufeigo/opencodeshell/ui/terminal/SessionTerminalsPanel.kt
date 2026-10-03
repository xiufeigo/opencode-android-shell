package com.xiufeigo.opencodeshell.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.terminal.share.PersistentPtyClient
import com.xiufeigo.opencodeshell.ui.terminal.share.SessionTerminalCreateOutcome
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import com.xiufeigo.opencodeshell.v2.SessionTerminalInfo
import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.createSessionTerminal
import com.xiufeigo.opencodeshell.v2.sessionTerminals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Session-bound terminals (`GET|POST /api/experimental/session/{id}/terminal`)
 * for one session — upstream `tui/src/context/session-terminals.tsx`: list,
 * create, select. Tapping a row attaches it as a tab of the terminal panel via
 * [PersistentPtyClient] in its session mode (screen restore through
 * `readSessionTerminal`, reconnect at `output.tail`).
 *
 * Deleting/renaming a terminal would need `PUT`/`DELETE
 * /api/experimental/persistent-pty/{id}` — those verbs belong to the
 * missing-verbs surface and are **not** wired here (see the delivery note), so
 * closing a tab only detaches. The footer's deliberate
 * [PersistentPtyClient.shutdownDaemon] action is the one teardown that *is*
 * available: it stops the daemon and every terminal it owns.
 */
@Composable
fun SessionTerminalsPanel(
    api: V2Api,
    sessionID: String,
    directory: String?,
    onAttach: (SessionTerminalInfo) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    var rows by remember { mutableStateOf<List<SessionTerminalInfo>>(emptyList()) }
    var listError by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    var shutdownArmed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val createFailedText = stringResource(R.string.oc_terminal_session_create_failed)

    LaunchedEffect(api, sessionID, reload) {
        listError = null
        rows = try {
            withContext(Dispatchers.IO) { api.sessionTerminals(sessionID) }
        } catch (e: Exception) {
            listError = e.message ?: createFailedText
            emptyList()
        }
    }

    Column(modifier.fillMaxSize().background(c.bgBase)) {
        OverlayHeader(
            title = stringResource(R.string.oc_terminal_session_terminals),
            onBack = onBack,
            trailing = {
                IconActionBox(
                    iconRes = R.drawable.ic_oc_plus,
                    contentDescription = stringResource(R.string.oc_terminal_session_terminal_new),
                    enabled = true,
                    onClick = {
                        // Upstream `newTerminal`: title "Terminal", no args/env,
                        // cwd = the session's location directory (`session-terminals.tsx:74-86`).
                        // The blocking create runs on IO; every state write below
                        // stays on the main thread, so the `reload` bump reliably
                        // restarts the list fetch and the `LazyColumn` recomposes.
                        scope.launch {
                            val created = withContext(Dispatchers.IO) {
                                runCatching {
                                    api.createSessionTerminal(
                                        sessionID = sessionID,
                                        title = "Terminal",
                                        args = emptyList(),
                                        env = emptyMap(),
                                        cwd = directory,
                                    )
                                }
                            }
                            val info = created.getOrNull()
                            for (effect in SessionTerminalCreateOutcome.effects(info?.id)) {
                                when (effect) {
                                    is SessionTerminalCreateOutcome.Effect.Attach -> info?.let(onAttach)
                                    SessionTerminalCreateOutcome.Effect.Refetch -> reload++
                                    SessionTerminalCreateOutcome.Effect.ShowCreateError ->
                                        listError = createFailedText
                                }
                            }
                        }
                    },
                )
            },
        )
        when {
            listError != null -> Text(
                text = listError!!,
                color = c.stateDangerFg,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
            rows.isEmpty() -> Text(
                text = stringResource(R.string.oc_terminal_session_terminals_empty),
                color = c.textFaint,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
            else -> LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(rows, key = { it.id }) { terminal ->
                    SessionTerminalRow(terminal = terminal, onAttach = { onAttach(terminal) })
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) {
                    if (!shutdownArmed) {
                        shutdownArmed = true
                    } else {
                        shutdownArmed = false
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                runCatching { PersistentPtyClient.shutdownDaemon(api) }
                            }
                            reload++
                        }
                    }
                }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.oc_terminal_session_shutdown),
                color = if (shutdownArmed) c.stateDangerFg else c.textMuted,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun SessionTerminalRow(
    terminal: SessionTerminalInfo,
    onAttach: () -> Unit,
) {
    val c = OcTheme.colors
    val running = terminal.status == "running"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onAttach)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = terminal.title.ifEmpty { terminal.id },
                color = c.textBase,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val runningLabel = stringResource(R.string.oc_terminal_shell_status_running)
            val exitedLabel = stringResource(R.string.oc_terminal_shell_status_exited)
            Text(
                text = buildString {
                    append(if (running) runningLabel else exitedLabel)
                    terminal.foregroundProcess?.let { append(" · ").append(it) }
                    if (terminal.pid > 0) append(" · pid ").append(terminal.pid)
                },
                color = if (running) c.stateSuccessFg else c.textMuted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
