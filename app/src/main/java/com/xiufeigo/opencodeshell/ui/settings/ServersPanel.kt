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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.servers.HealthMessage
import com.xiufeigo.opencodeshell.ui.servers.HealthResult
import com.xiufeigo.opencodeshell.ui.servers.HealthTarget
import com.xiufeigo.opencodeshell.ui.servers.ServerHealthProbe
import com.xiufeigo.opencodeshell.ui.servers.ServerHealthSource
import com.xiufeigo.opencodeshell.ui.servers.healthFailureMessage
import com.xiufeigo.opencodeshell.ui.servers.withHealth
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * `SettingsServersV2` (`settings-v2/servers.tsx`, `research/02` §5.4) plus the
 * dedicated `dialog-server-v2` add/edit dialog (§5.5).
 *
 * The list is **state driven** from [ServerBridge] (which reads
 * `ServerConfigStore`, i.e. the same DataStore/prefs the WebView mirrors), so an
 * edit round-trips and the row re-renders without a manual refresh.
 *
 * **W5-D3:** opening the panel probes every configured server once
 * (`GET /global/health`, ≤3 s, off the main thread) and folds the result into
 * the row's indicator — the same three states as upstream
 * `ServerHealthIndicator` (`servers/registry/row.tsx:107-134`): green for a
 * healthy answer, red for an answer that is not healthy, weak/faint for "not
 * probed yet". Failures are silent here by design (no dialog, no snackbar); the
 * only place a probe failure is spoken is the connect page and the add-server
 * warning.
 */
@Composable
fun ServersPanel(bridge: ServerBridge?) {
    // Observe the store revision so external WebView/native updates also refresh
    // the list; local writes still trigger the same StateFlow emission.
    val revisionFlow = remember(bridge) { bridge?.revision ?: MutableStateFlow(0) }
    val bridgeRevision by revisionFlow.collectAsState()
    val rows = remember(bridge, bridgeRevision) { bridge?.servers.orEmpty() }
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<ServerListItem?>(null) }
    var adding by remember { mutableStateOf(false) }

    // The probe is re-run for every revision (an add/edit/delete changes the
    // list, and a URL edit must not keep the previous address's verdict).
    val source = bridge as? ServerHealthSource
    val probe = remember { ServerHealthProbe() }
    var health by remember(bridge) { mutableStateOf<Map<String, HealthResult>>(emptyMap()) }
    LaunchedEffect(bridge, bridgeRevision) {
        val targets = source?.healthTargets().orEmpty()
        if (targets.isEmpty()) {
            health = emptyMap()
            return@LaunchedEffect
        }
        health = withContext(Dispatchers.IO) { probe.checkAll(targets) }
    }
    val servers = remember(rows, health) { rows.map { it.withHealth(health[it.url]) } }

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
                                // Store revision updates reactively
                            },
                            onMakeDefault = {
                                bridge?.setDefault(server.url)
                                // Store revision updates reactively
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
            probe = probe,
            onDismiss = {
                adding = false
                editing = null
            },
            onSave = { item, password ->
                // `replace` carries the edited row's original URL so a URL edit
                // replaces (and keeps default) instead of duplicating the row.
                bridge?.replace(editing?.url, item, password)
                // Store revision updates reactively
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
    // The dot carries its state as a content description: upstream's indicator
    // is a bare `div` with no label, which leaves the state invisible to a
    // screen reader (and to a QA pass reading the accessibility tree).
    val healthState = stringResourceHealthLabel(server.health)
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
                        .background(healthColor(server.health), RoundedCornerShape(4.dp))
                        .semantics { contentDescription = healthState },
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

/** The `oc_health_*` label behind the indicator dot (accessibility + QA anchor). */
@Composable
private fun stringResourceHealthLabel(health: ServerHealth): String = stringResource(
    when (health) {
        ServerHealth.HEALTHY -> R.string.oc_health_healthy
        ServerHealth.UNKNOWN -> R.string.oc_health_unknown
        ServerHealth.UNHEALTHY -> R.string.oc_health_unhealthy
    },
)

/**
 * Defends the URL field against CJK IMEs: in pinyin mode Gboard commits
 * full-width variants of `:`, `/`, `.` and digits (and `。` for `.`), which
 * would otherwise be stored verbatim and produce unreachable addresses.
 * Maps full-width ASCII (U+FF01–U+FF5E) back to ASCII and `。`/`・` to `.`,
 * and drops whitespace entirely (URLs cannot contain it).
 */
internal fun normalizeUrlInput(raw: String): String = buildString(raw.length) {
    for (ch in raw) {
        when (ch) {
            '。', '・' -> append('.')
            '　' -> Unit
            else -> when {
                ch in '！'..'～' -> append(ch - 0xFEE0)
                ch.isWhitespace() -> Unit
                else -> append(ch)
            }
        }
    }
}

/**
 * `dialog-server-v2` (`research/02` §5.5): width 480 (capped to
 * `100vw-32`), radius 8, header `24px 24px 16px`, footer `24px`.
 *
 * Field order is verbatim: address (autofocus, placeholder
 * `http://localhost:4096`) → name → username (default `opencode`) / password.
 * Enter submits; the error line is `11px` in `--v2-state-fg-danger`, and the
 * Android-only CORS hint/error copy is preserved.
 *
 * **W5-D3 (optional item, documented deviation):** the save path probes the
 * address first. Upstream's add dialog *refuses* to save an unhealthy server;
 * here the probe failure is shown as a readable sentence and the primary button
 * turns into 「%s」, so a second press saves anyway. A phone user whose server has
 * no health endpoint (or is momentarily unreachable) must not be locked out of
 * configuring it — that escape hatch is the deliberate difference.
 */
@Composable
private fun AddEditServerDialog(
    initial: ServerListItem?,
    probe: ServerHealthProbe,
    onDismiss: () -> Unit,
    onSave: (ServerListItem, String?) -> Unit,
) {
    val c = OcTheme.colors
    val scope = rememberCoroutineScope()
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
    var warning by remember { mutableStateOf<HealthMessage?>(null) }
    var checking by remember { mutableStateOf(false) }
    // Set once the warning has been shown: the next press saves regardless.
    var warned by remember { mutableStateOf(false) }

    fun resetWarning() {
        warning = null
        warned = false
    }

    fun save(trimmed: String) {
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

    fun submit() {
        if (checking) return
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            error = R.string.oc_server_error_connect
            return
        }
        error = null
        // The user already saw why this address is unhealthy and pressed again.
        if (warned) {
            save(trimmed)
            return
        }
        checking = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                probe.check(
                    HealthTarget(
                        url = trimmed,
                        username = username.trim().ifEmpty { null },
                        password = password.takeIf { it.isNotEmpty() },
                    ),
                )
            }
            checking = false
            if (!result.healthy) {
                warning = healthFailureMessage(result.failure)
                warned = true
                return@launch
            }
            save(trimmed)
        }
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
                url = normalizeUrlInput(it)
                error = null
                resetWarning()
            },
            placeholder = androidx.compose.ui.res.stringResource(R.string.oc_server_url_placeholder),
            imeAction = ImeAction.Next,
            // URL fields must not let a CJK IME full-width `:`, `/` and `.`
            // (Gboard zh does exactly that), which would silently corrupt the address.
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
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
                    onValueChange = {
                        username = it
                        resetWarning()
                    },
                    imeAction = ImeAction.Next,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OcBodyText(androidx.compose.ui.res.stringResource(R.string.oc_server_password), muted = true)
                OcTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        resetWarning()
                    },
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
        // W5-D3: the probe's readable reason. A warning, not a blocker — the
        // button below only changes its label.
        warning?.let { message ->
            Text(
                text = message.formatArg
                    ?.let { stringResource(message.resId, it) }
                    ?: stringResource(message.resId),
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
                    when {
                        checking -> R.string.oc_server_checking
                        warned -> R.string.oc_health_save_anyway
                        initial == null -> R.string.oc_server_add_button
                        else -> R.string.oc_server_save_button
                    },
                ),
                enabled = !checking,
                onClick = { submit() },
            )
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.borderBase))
    }
}
