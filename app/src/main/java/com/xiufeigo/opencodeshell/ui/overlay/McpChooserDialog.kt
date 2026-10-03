package com.xiufeigo.opencodeshell.ui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.settings.LocalMcpRevision
import com.xiufeigo.opencodeshell.ui.settings.McpPageRow
import com.xiufeigo.opencodeshell.ui.settings.OcSwitch
import com.xiufeigo.opencodeshell.ui.settings.ProviderWriteResult
import com.xiufeigo.opencodeshell.ui.settings.SettingsPageLoad
import com.xiufeigo.opencodeshell.ui.settings.SettingsServerData
import com.xiufeigo.opencodeshell.ui.settings.rememberMcpBurstKey
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import kotlinx.coroutines.launch

/**
 * `DialogSelectMcp` (`providers/connect/mcp-dialog.tsx`): the searchable MCP
 * server list with a per-row connect/disconnect switch.
 *
 * Upstream's `mcp.toggle` opens this chooser (`use-session-commands.tsx:430-440`
 * `chooseMcp`); the native `mcp.toggle` palette row and the composer's `/mcp`
 * alias both land here. Data and writes ride [SettingsServerData] — the same
 * seam the settings Extensions panel uses (`mcpServers()` /
 * `setMcpEnabled()`) — so no second HTTP path exists. Full management (add /
 * remove / resource browse) stays in that panel; this chooser is list + toggle
 * only, like upstream.
 *
 * A bare card on purpose: [DialogHost] paints the scrim and owns dismissal,
 * and its measured-viewport overflow fallback caps + scrolls this content.
 *
 * A-5: the list also refreshes off the live `mcp.*` events — the settled
 * revision published through [LocalMcpRevision] joins the read's key, so a
 * server settling from `pending` to `connected`/`failed` (or another client
 * toggling a server) lands here while the chooser is open.
 */
@Composable
fun McpChooserDialog(
    source: SettingsServerData?,
    onDismiss: () -> Unit,
) {
    val c = OcTheme.colors
    var query by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<SettingsPageLoad<List<McpPageRow>>?>(null) }
    var reload by remember { mutableStateOf(0) }
    var pending by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    // A-5: the settled `mcp.*` burst joins the refresh key, so a server that
    // reports `pending → connected/failed` (or another client toggling a
    // server) lands here without reopening the chooser — one refetch per burst,
    // never one per event (`_upstream` `client/src/solid/data.ts:1284-1290`).
    val mcpBurst = rememberMcpBurstKey(LocalMcpRevision.current)
    LaunchedEffect(source, reload, mcpBurst) {
        state = null
        state = source?.mcpServers() ?: SettingsPageLoad.NoServer
    }
    // Upstream autofocuses the list search (`mcp-dialog.tsx` `search.autofocus`).
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    val unsupportedText = stringResource(R.string.oc_settings_mcp_unsupported)
    val pageFailedFormat = stringResource(R.string.oc_settings_page_failed, "")

    Column(
        Modifier
            .fillMaxWidth()
            .background(c.bgBase)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = stringResource(R.string.oc_mcp_title),
            color = c.textBase,
            fontSize = 14.sp,
        )
        val rows = (state as? SettingsPageLoad.Loaded)?.rows
        if (rows != null) {
            val (enabled, total) = mcpChooserCounts(rows)
            Text(
                text = stringResource(R.string.oc_mcp_description, enabled, total),
                color = c.textMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        BasicTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            textStyle = TextStyle(color = c.textBase, fontSize = 14.sp),
            cursorBrush = SolidColor(c.textBase),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .clip(OcTheme.shapes.md)
                .background(c.bgDeep)
                .focusRequester(focus)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    Text(
                        text = stringResource(R.string.oc_mcp_search),
                        color = c.textFaint,
                        fontSize = 14.sp,
                    )
                }
                inner()
            },
        )
        when (state) {
            null -> Text(
                text = stringResource(R.string.oc_common_loading),
                color = c.textMuted,
                fontSize = 13.sp,
                modifier = Modifier.padding(vertical = 20.dp),
            )
            SettingsPageLoad.NoServer -> Text(
                text = stringResource(R.string.oc_settings_page_no_server),
                color = c.textMuted,
                fontSize = 13.sp,
                modifier = Modifier.padding(vertical = 20.dp),
            )
            is SettingsPageLoad.Failed -> Text(
                text = stringResource(
                    R.string.oc_settings_page_failed,
                    (state as SettingsPageLoad.Failed).reason,
                ),
                color = c.textMuted,
                fontSize = 13.sp,
                modifier = Modifier.padding(vertical = 20.dp),
            )
            is SettingsPageLoad.Loaded -> {
                val visible = mcpChooserFilter(rows.orEmpty(), query)
                failure?.let {
                    Text(
                        text = it,
                        color = c.textMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (visible.isEmpty()) {
                    Text(
                        text = stringResource(R.string.oc_mcp_empty),
                        color = c.textMuted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 20.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .padding(top = 4.dp),
                    ) {
                        items(visible, key = { it.name }) { row ->
                            McpChooserRow(
                                row = row,
                                toggleEnabled = source != null && pending == null,
                                busy = pending == row.name,
                                onToggle = { checked ->
                                    if (pending != null || source == null) return@McpChooserRow
                                    pending = row.name
                                    failure = null
                                    scope.launch {
                                        when (val result = source.setMcpEnabled(row.name, checked)) {
                                            ProviderWriteResult.Done -> reload++
                                            ProviderWriteResult.Unsupported -> failure = unsupportedText
                                            is ProviderWriteResult.Failed ->
                                                failure = pageFailedFormat.replace("%1\$s", result.reason)
                                        }
                                        pending = null
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
        Text(
            text = stringResource(R.string.oc_common_close),
            color = c.textMuted,
            fontSize = 13.sp,
            modifier = Modifier
                .clip(OcTheme.shapes.md)
                .clickable(role = Role.Button, onClick = onDismiss)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun McpChooserRow(
    row: McpPageRow,
    toggleEnabled: Boolean,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val c = OcTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.name,
                    color = c.textBase,
                    fontSize = 13.sp,
                    maxLines = 1,
                )
                mcpStatusLabelResId(row.status)?.let { resId ->
                    Text(
                        text = stringResource(resId),
                        color = c.textMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            if (row.failed && !row.error.isNullOrBlank()) {
                Text(
                    text = row.error,
                    color = c.textMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            }
        }
        // The themed switch, not the bare M3 `Switch`: its `SwitchDefaults`
        // track/thumb are light-tinted (`surfaceContainer*`) and the shell never
        // re-themes them, so in the dark theme the row's knob read as a white
        // blob on the card — the same root cause `SettingsRow`'s SWITCH control
        // and `SessionSummaryPanel`'s MCP rows already route through `OcSwitch`.
        OcSwitch(
            checked = row.connected,
            enabled = toggleEnabled && !busy && !row.status.equals("pending", ignoreCase = true),
            onCheckedChange = onToggle,
        )
    }
}

/**
 * Upstream `dialog.mcp.description` (`en.ts:383`): `{{enabled}} of {{total}}
 * enabled` — connected rows over all rows.
 */
fun mcpChooserCounts(rows: List<McpPageRow>): Pair<Int, Int> =
    rows.count { it.connected } to rows.size

/**
 * Upstream list `filterKeys: ["name", "status"]` (`mcp-dialog.tsx`): a blank
 * query keeps every row, otherwise a case-insensitive substring match on name
 * or status. Row order is the source's (`mcpPageRows` already sorts).
 */
fun mcpChooserFilter(rows: List<McpPageRow>, query: String): List<McpPageRow> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return rows
    return rows.filter { it.name.lowercase().contains(q) || it.status.lowercase().contains(q) }
}

/**
 * Upstream `statusLabels` (`mcp-dialog.tsx:9-14`): only the four known states
 * get a label — anything else (`pending`, `unknown`) shows none.
 */
private fun mcpStatusLabelResId(status: String): Int? = when (status.lowercase()) {
    "connected" -> R.string.oc_mcp_status_connected
    "failed" -> R.string.oc_mcp_status_failed
    "needs_auth" -> R.string.oc_mcp_status_needs_auth
    "disabled" -> R.string.oc_mcp_status_disabled
    else -> null
}
