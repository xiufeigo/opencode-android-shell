package com.xiufeigo.opencodeshell.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.theme.OcTheme

/**
 * `SettingsServersV2` (`settings-v2/servers.tsx`, `research/02` §5.4) plus the
 * dedicated `dialog-server-v2` add/edit dialog (§5.5).
 *
 * The list is **state driven** from [ServerBridge] (which reads
 * `ServerConfigStore`, i.e. the same DataStore/prefs the WebView mirrors), so an
 * edit round-trips and the row re-renders without a manual refresh.
 */
@Composable
fun ServersPanel(bridge: ServerBridge?) {
    // `revision` makes the list state-driven: every mutation bumps it, so the
    // row list re-reads the store instead of showing a stale snapshot.
    var revision by remember { mutableStateOf(0) }
    val servers = remember(bridge, revision) { bridge?.servers.orEmpty() }
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<ServerListItem?>(null) }
    var adding by remember { mutableStateOf(false) }

    val filtered = filterServers(servers, query)
    val sorted = sortServers(filtered)

    Column(Modifier.fillMaxWidth()) {
        SettingsPanelHeader(
            titleResId = R.string.oc_setting_tab_servers,
            trailing = { AddServerButton { adding = true } },
            below = if (showsServerSearch(servers.size)) {
                {
                    OcTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = androidx.compose.ui.res.stringResource(R.string.oc_servers_search),
                    )
                }
            } else {
                null
            },
        )
        SettingsPanelBody {
            when {
                servers.isEmpty() -> OcBodyText(
                    androidx.compose.ui.res.stringResource(R.string.oc_servers_empty),
                    muted = true,
                )

                sorted.isEmpty() -> OcBodyText(
                    androidx.compose.ui.res.stringResource(R.string.oc_servers_no_results),
                    muted = true,
                )

                else -> SettingsList {
                    sorted.forEachIndexed { index, server ->
                        ServerRow(
                            server = server,
                            last = index == sorted.lastIndex,
                            onEdit = { editing = server },
                            onDelete = {
                                bridge?.delete(server.url)
                                revision++
                            },
                            onMakeDefault = {
                                bridge?.setDefault(server.url)
                                revision++
                            },
                        )
                    }
                }
            }
        }
    }

    if (adding || editing != null) {
        AddEditServerDialog(
            initial = editing,
            onDismiss = {
                adding = false
                editing = null
            },
            onSave = { item, password ->
                bridge?.upsert(item, password)
                revision++
                adding = false
                editing = null
            },
        )
    }
}

/**
 * `settings-v2-servers-row`: health dot + `name` + `v{ver} • username` meta on
 * the left, `Default` tag + `⋮` menu on the right; non-last rows carry the
 * `0.5px` bottom border (§5.3).
 */
@Composable
private fun ServerRow(
    server: ServerListItem,
    last: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMakeDefault: () -> Unit,
) {
    val c = OcTheme.colors
    var menu by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(healthColor(server.health), RoundedCornerShape(4.dp)),
                )
                Spacer(Modifier.width(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(server.name, color = c.textBase, fontSize = 13.sp, fontWeight = FontWeight(530))
                    Text(
                        serverMeta(server),
                        color = c.textFaint,
                        fontSize = 11.sp,
                        fontWeight = FontWeight(440),
                    )
                }
            }
            if (server.isDefault) {
                OcTag(androidx.compose.ui.res.stringResource(R.string.oc_servers_default_tag))
                Spacer(Modifier.width(8.dp))
            }
            Box {
                ServerRowMenu(onClick = { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                androidx.compose.ui.res.stringResource(R.string.oc_servers_menu_edit),
                                fontSize = 13.sp,
                            )
                        },
                        onClick = {
                            menu = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                androidx.compose.ui.res.stringResource(R.string.oc_servers_menu_default),
                                fontSize = 13.sp,
                            )
                        },
                        onClick = {
                            menu = false
                            onMakeDefault()
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                androidx.compose.ui.res.stringResource(R.string.oc_servers_menu_delete),
                                color = c.stateDangerFg,
                                fontSize = 13.sp,
                            )
                        },
                        onClick = {
                            menu = false
                            onDelete()
                        },
                    )
                }
            }
        }
        if (!last) {
            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.borderBase))
        }
    }
}

@Composable
private fun healthColor(health: ServerHealth) = when (health) {
    ServerHealth.HEALTHY -> OcTheme.colors.stateSuccessFg
    ServerHealth.UNKNOWN -> OcTheme.colors.textFaint
    ServerHealth.UNHEALTHY -> OcTheme.colors.stateDangerFg
}

/**
 * `dialog-server-v2` (`research/02` §5.5): width 480 (capped to
 * `100vw-32`), radius 8, header `24px 24px 16px`, footer `24px`.
 *
 * Field order is verbatim: address (autofocus, placeholder
 * `http://localhost:4096`) → name → username (default `opencode`) / password.
 * Enter submits; the error line is `11px` in `--v2-state-fg-danger`, and the
 * Android-only CORS hint/error copy is preserved.
 */
@Composable
private fun AddEditServerDialog(
    initial: ServerListItem?,
    onDismiss: () -> Unit,
    onSave: (ServerListItem, String?) -> Unit,
) {
    val c = OcTheme.colors
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val width = minOf(config.screenWidthDp.dp - 32.dp, 480.dp)

    // `startAdd()` / `startEdit(server)` seeds the form, exactly like the web
    // `opened` signal + createEffect.
    var url by remember(initial) { mutableStateOf(initial?.url.orEmpty()) }
    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
    var username by remember(initial) {
        mutableStateOf(initial?.username ?: DEFAULT_SERVER_USERNAME)
    }
    var password by remember(initial) { mutableStateOf("") }
    var error by remember { mutableStateOf<Int?>(null) }

    fun submit() {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            error = R.string.oc_server_error_connect
            return
        }
        error = null
        val resolvedName = name.trim().ifEmpty { trimmed.removePrefix("http://").removePrefix("https://") }
        onSave(
            ServerListItem(
                url = trimmed,
                name = resolvedName,
                username = username.trim().ifEmpty { null },
                version = initial?.version,
                health = initial?.health ?: ServerHealth.UNKNOWN,
                isDefault = initial?.isDefault ?: false,
            ),
            password.takeIf { it.isNotEmpty() },
        )
    }

    Column(
        Modifier
            .width(width)
            .background(c.bgBase, RoundedCornerShape(8.dp))
            .border(0.5.dp, c.borderBase, RoundedCornerShape(8.dp))
            // Header / body / footer padding from §5.3.
            .padding(PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 24.dp))
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = androidx.compose.ui.res.stringResource(
                if (initial == null) R.string.oc_servers_add else R.string.oc_servers_edit,
            ),
            color = c.textBase,
            fontSize = 15.sp,
            fontWeight = FontWeight(640),
        )
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.borderBase))

        OcBodyText(androidx.compose.ui.res.stringResource(R.string.oc_server_address), muted = true)
        OcTextField(
            value = url,
            onValueChange = {
                url = it
                error = null
            },
            placeholder = DEFAULT_SERVER_URL_PLACEHOLDER,
            imeAction = ImeAction.Next,
        )
        OcBodyText(androidx.compose.ui.res.stringResource(R.string.oc_server_name), muted = true)
        OcTextField(
            value = name,
            onValueChange = { name = it },
            placeholder = androidx.compose.ui.res.stringResource(R.string.oc_server_name_placeholder),
            imeAction = ImeAction.Next,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OcBodyText(androidx.compose.ui.res.stringResource(R.string.oc_server_username), muted = true)
                OcTextField(
                    value = username,
                    onValueChange = { username = it },
                    imeAction = ImeAction.Next,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OcBodyText(androidx.compose.ui.res.stringResource(R.string.oc_server_password), muted = true)
                OcTextField(
                    value = password,
                    onValueChange = { password = it },
                    password = true,
                    onImeAction = { submit() },
                )
            }
        }
        // Android-only hint: the shell reverse-proxies natively, so CORS is not
        // required (documented deviation kept from the WebView dialog).
        Text(
            text = androidx.compose.ui.res.stringResource(R.string.oc_server_cors_hint),
            color = c.textFaint,
            fontSize = 11.sp,
            fontWeight = FontWeight(440),
        )
        error?.let { errorRes ->
            Text(
                text = androidx.compose.ui.res.stringResource(errorRes),
                color = c.stateDangerFg,
                fontSize = 11.sp,
                fontWeight = FontWeight(440),
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            OcButton(
                label = androidx.compose.ui.res.stringResource(R.string.oc_common_cancel),
                primary = false,
                onClick = onDismiss,
            )
            OcButton(
                label = androidx.compose.ui.res.stringResource(
                    if (initial == null) R.string.oc_server_add_button else R.string.oc_server_save_button,
                ),
                onClick = { submit() },
            )
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.borderBase))
    }
}
