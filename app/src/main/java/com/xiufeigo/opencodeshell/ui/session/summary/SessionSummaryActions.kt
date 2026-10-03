package com.xiufeigo.opencodeshell.ui.session.summary

import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ServerConfigStore
import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.locationReload
import com.xiufeigo.opencodeshell.v2.mcpConnect
import com.xiufeigo.opencodeshell.v2.mcpDisconnect
import com.xiufeigo.opencodeshell.v2.sessionSkillActivate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "SessionSummaryActions"

/**
 * `POST /api/location/reload` ([V2Api.locationReload], `location.reload`): a
 * manual rebuild of **every** loaded location after a config change — it
 * cancels pending permissions and forms and answers once the replacement
 * builds settle (`protocol/src/groups/location.ts:51-59`), so the summary card
 * offers it as an explicit action rather than something automatic.
 *
 * `null` = no configured server (nothing to reload against). Success and
 * failure toast (`oc_summary_reload_done` / `oc_summary_reload_failed`) —
 * there is no other visible effect.
 */
@Composable
fun rememberLocationReload(): (() -> Unit)? {
    val context = LocalContext.current
    val serverStore = remember(context) { ServerConfigStore(context).also { it.restore() } }
    val scope = rememberCoroutineScope()
    if (serverStore.server == null) return null
    return remember(serverStore) {
        {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    val server = serverStore.server ?: return@withContext false
                    val api = V2Api.forServer(server.url, server.username, server.password)
                        ?: return@withContext false
                    runCatching { api.locationReload() }.isSuccess
                }
                val text = if (ok) R.string.oc_summary_reload_done else R.string.oc_summary_reload_failed
                Toast.makeText(context, context.getString(text), Toast.LENGTH_SHORT).show()
            }
            Unit
        }
    }
}

/**
 * `POST /api/experimental/session/{id}/skill` ([V2Api.sessionSkillActivate]):
 * manually activates a skill for the session by appending a skill message and
 * resuming execution (`session.ts:446-448`).
 *
 * `null` = no session id is known yet (the panel is parameter-driven and the
 * host has not wired one). Failures toast `oc_summary_skill_failed`; a success
 * is visible as the skill message arriving in the transcript.
 */
@Composable
fun rememberSkillActivate(sessionID: String?): ((String) -> Unit)? {
    val context = LocalContext.current
    val serverStore = remember(context) { ServerConfigStore(context).also { it.restore() } }
    val scope = rememberCoroutineScope()
    if (sessionID.isNullOrBlank() || serverStore.server == null) return null
    return remember(serverStore, sessionID) {
        { skillID ->
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    val server = serverStore.server ?: return@withContext false
                    val api = V2Api.forServer(server.url, server.username, server.password)
                        ?: return@withContext false
                    runCatching { api.sessionSkillActivate(sessionID, skillID) }
                        .onFailure { Log.w(TAG, "skill activate failed: ${it.message}") }
                        .isSuccess
                }
                if (!ok) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.oc_summary_skill_failed),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            Unit
        }
    }
}

/**
 * The summary MCP submenu's write handle ([rememberMcpToggle]): the row's
 * `Switch` calls [toggle], [busy] keeps that row's Switch disabled while the
 * route is in flight, and [revision] is bumped after a successful write so the
 * card re-reads `GET /api/mcp` (`server-panel.tsx:234` `toggle.mutate({name,
 * enabled})` followed by the data store's invalidate + sync).
 */
class McpToggleHost(
    val busy: Set<String>,
    val revision: Int,
    val toggle: (String, Boolean) -> Unit,
)

/**
 * `POST /api/experimental/mcp/{server}/{connect,disconnect}`
 * ([V2Api.mcpConnect] / [V2Api.mcpDisconnect]) — the write half of the summary
 * server card's MCP submenu. `location[directory]` is left off: upstream's
 * `useMcpToggle` falls back to the location the panel reads from
 * (`providers/connect/mcp.ts:17-20`), which is the default location here.
 *
 * `null` = no configured server, so there is nothing to toggle against and the
 * panel renders no Switch (an inert control is exactly what this shell keeps
 * out). A failure toasts `oc_summary_mcp_toggle_failed` with the server's
 * message; a success bumps [McpToggleHost.revision] — the status text then
 * follows the real connection state instead of the tapped Switch.
 */
@Composable
fun rememberMcpToggle(): McpToggleHost? {
    val context = LocalContext.current
    val serverStore = remember(context) { ServerConfigStore(context).also { it.restore() } }
    val scope = rememberCoroutineScope()
    var revision by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(emptySet<String>()) }
    if (serverStore.server == null) return null
    return remember(serverStore, busy, revision) {
        McpToggleHost(
            busy = busy,
            revision = revision,
            toggle = toggle@{ name, enabled ->
                if (name.isBlank() || name in busy) return@toggle
                busy = busy + name
                scope.launch {
                    val failure = withContext(Dispatchers.IO) {
                        val server = serverStore.server ?: return@withContext "no-server"
                        val api = V2Api.forServer(server.url, server.username, server.password)
                            ?: return@withContext "no-server"
                        runCatching { if (enabled) api.mcpConnect(name) else api.mcpDisconnect(name) }
                            .exceptionOrNull()
                            ?.let { error ->
                                Log.w(TAG, "mcp toggle failed: ${error.message}")
                                error.message
                            }
                    }
                    busy = busy - name
                    if (failure == null) {
                        revision += 1
                    } else {
                        val message = context.getString(R.string.oc_summary_mcp_toggle_failed)
                        Toast.makeText(context, "$message: $failure", Toast.LENGTH_SHORT).show()
                    }
                }
                Unit
            },
        )
    }
}

/**
 * `ServiceConfigLink`'s mobile half (`server-panel.tsx:440-480`): desktop
 * reveals the configuration document in the OS file manager, the phone copies
 * its **path** to the clipboard (`platform.writeClipboardText`).
 *
 * A `null`/blank path is the `GET /api/config` page carrying no document —
 * upstream's `session.summary.configFileMissing` error toast
 * (`server-panel.tsx:449`). The copy reports itself with
 * `oc_summary_config_copied`, the phone's stand-in for upstream's two-second
 * 「Copied」 tooltip.
 */
@Composable
fun rememberConfigPathCopy(): (String?) -> Unit {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    return remember(context, clipboard) {
        { path ->
            if (path.isNullOrBlank()) {
                Toast.makeText(
                    context,
                    context.getString(R.string.oc_summary_config_missing),
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                clipboard.setText(AnnotatedString(path))
                Toast.makeText(
                    context,
                    context.getString(R.string.oc_summary_config_copied),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }
}
