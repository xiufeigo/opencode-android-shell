package com.xiufeigo.opencodeshell.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
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
import com.xiufeigo.opencodeshell.ui.servers.SshDialog
import com.xiufeigo.opencodeshell.ui.servers.SshAuthenticate
import com.xiufeigo.opencodeshell.ui.servers.SshPromptOffer
import com.xiufeigo.opencodeshell.ui.servers.SshTunnelCard
import com.xiufeigo.opencodeshell.ui.servers.SshTunnelHost
import com.xiufeigo.opencodeshell.ui.servers.healthFailureMessage
import com.xiufeigo.opencodeshell.ui.servers.probeTarget
import com.xiufeigo.opencodeshell.ui.servers.sshPromptCandidate
import com.xiufeigo.opencodeshell.ui.servers.sshPromptFor
import com.xiufeigo.opencodeshell.ui.servers.sshPromptOffer
import com.xiufeigo.opencodeshell.ui.servers.sshPromptOffered
import com.xiufeigo.opencodeshell.ui.servers.sshServerEntry
import com.xiufeigo.opencodeshell.ui.servers.toSshEntry
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
    var addingSsh by remember { mutableStateOf(false) }
    val sshScope = rememberCoroutineScope()
    val sshHost = remember(sshScope) { SshTunnelHost(sshScope) }
    val sshTunnels by sshHost.tunnels.collectAsState()

    // v92 — the SSH list is persisted (`ServerConfigStore.sshEntries()`,
    // `ssh_json`); this host is composition-scoped, so leaving Settings or a
    // locale switch (which recreates the composition) used to drop every SSH
    // row while the durable records sat unreachable (`research/v91_verify/
    // 5554+5556/`, P1). Every fresh composition re-seeds from the store; the
    // merge keeps live connection state and a confirmed delete drops the
    // record first, so a removed row cannot resurrect.
    val sshStore = bridge as? SshEntryStore
    LaunchedEffect(sshStore) {
        val persisted = sshStore?.sshEntries().orEmpty()
        if (persisted.isNotEmpty()) sshHost.seed(persisted)
    }

    // v94 — the row-level prompt exit: a challenge raised by a **row's** dial
    // (Reconnect, or a seeded row with no stored secret) has to reach a card.
    // Before this the only [SshAuthenticate] in the app was the config dialog's
    // own prompt branch (`SshDialog.kt:228-245`), so a prompt from a row was
    // published to `SshConnection.state.prompt` and never composed — the user
    // watched `Connecting over SSH…` until `PROMPT_TIMEOUT_SECONDS` (600 s)
    // gave the challenge up (`research/v94_verify/5558/
    // E2ECHK5558_39_reprompts.png`, `_40_prompt3.png`).
    //
    // This is upstream's `SshDialogs` (`context.tsx:58-69`) with the panel's
    // seams substituted: the same row list the rows render is the effect's
    // input, `addingSsh` is `dialog.active` (the config dialog answers its own
    // prompt, so nothing is pushed behind it), and the mark written before the
    // card is composed is `dialog.opened(id)` — keyed by promptId so the mark
    // is `authentication-state.ts:4-5`'s "offered once" and a *second* jsch
    // challenge of the same dial is still answerable. The rule itself is the
    // pure `sshPromptOffer` (`controller.ts:88-99`), so the suite pins it
    // without a Compose runtime.
    var sshOffered by remember { mutableStateOf(emptyMap<String, String>()) }
    var sshOffer by remember { mutableStateOf<SshPromptOffer?>(null) }
    LaunchedEffect(sshTunnels, addingSsh) {
        val next = sshPromptOffer(
            candidates = sshTunnels.map { it.sshPromptCandidate() },
            offered = sshOffered,
            dialogActive = addingSsh,
        ) ?: return@LaunchedEffect
        // Marked *before* the card is drawn (`dialog.opened`), so the same
        // promptId can never be pushed twice — an unrelated status emission
        // re-running this effect re-offers nothing and leaves the open card be.
        sshOffered = sshPromptOffered(sshOffered, next)
        sshOffer = next
    }
    // The offered card's own prompt: id-matched against the live row, so an
    // answer (which clears `status.prompt`), a failure or a fresh dial takes
    // the card down without a close handler.
    val sshOfferedTunnel = sshTunnels.firstOrNull { it.config.id == sshOffer?.configId }
    val sshPrompt = sshOffer?.let { offer -> sshOfferedTunnel?.sshPromptFor(offer) }
    // v97 D3 — a confirmed delete must also retire the row's offer. Without
    // this the table kept an id the store had just dropped, so a row that came
    // back under the same id could inherit a stale "already offered" mark (and
    // the card the user dismissed with Delete could re-appear over the list).
    // Ids are minted per entry, so this only ever retires what the list no
    // longer has.
    LaunchedEffect(sshTunnels) {
        val live = sshTunnels.map { it.config.id }.toSet()
        val stale = sshOffered.filterKeys { it !in live }
        if (stale.isNotEmpty()) {
            sshOffered = sshOffered - stale.keys
            if (sshOffer?.configId in stale) sshOffer = null
        }
    }
    // The card's band inputs — the same window IME bottom and panel slot box
    // `SshDialog`'s own prompt branch feeds its card (`SshDialog.kt:218-243`),
    // read here so a row-raised prompt is measured against the same floor.
    val sshPromptConfig = LocalConfiguration.current
    val sshPromptImeBottomDp = windowImeBottomDp()
    val sshPromptPanel = LocalOcSelectContainerBounds.current
    val sshPromptPanelTopDp = sshPromptPanel?.topDp ?: -1f
    val sshPromptPanelHeightDp = sshPromptPanel?.let { (it.bottomDp - it.topDp).coerceAtLeast(0f) } ?: 0f
    val sshPromptWindowHeightDp = sshPromptConfig.screenHeightDp.toFloat()

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

    // One outer scroll for the header, the list and the appended add/edit
    // cards: the cards used to be siblings of this column in the slot's Box,
    // so on a short-window + keyboard viewport they were clipped with no
    // scroller to reach them. The body owns no second scroller below (the v32
    // single-scroll shape): with the keyboard down everything fits and the
    // geometry is unchanged.
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsPanelHeader(
            titleResId = R.string.oc_setting_tab_servers,
            trailing = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OcButton(
                        label = androidx.compose.ui.res.stringResource(R.string.oc_servers_add_ssh),
                        primary = false,
                        onClick = { addingSsh = true },
                    )
                    AddServerButton { adding = true }
                }
            },
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
        SettingsPanelBody(scroll = false) {
            if (sshTunnels.isNotEmpty()) {
                SettingsSectionBlock(R.string.oc_servers_ssh_section) {
                    SettingsList {
                        sshTunnels.forEachIndexed { index, tunnel ->
                            SshTunnelCard(
                                tunnel = tunnel,
                                host = sshHost,
                                // v92: the confirmed delete also drops the
                                // persisted recipe (else the next seed
                                // resurrects the row) and cascades to the
                                // loopback server row a READY tunnel wrote
                                // (the F8 follow-up noted on handoff).
                                removePersisted = { id ->
                                    val localUrl = sshStore?.sshEntry(id)?.lastLocalUrl
                                    val removed = sshStore?.removeSshEntry(id) ?: false
                                    if (localUrl != null) bridge?.delete(localUrl)
                                    removed
                                },
                            )
                            if (index != sshTunnels.lastIndex) RowSeparator()
                        }
                    }
                }
            }
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
        if (adding || editing != null) {
            AddEditServerDialog(
                initial = editing,
                // The edit form resets the password box to blank; the probe has to
                // fall back to the stored secret exactly like `replace` does.
                storedPassword = editing?.url?.let { url ->
                    source?.healthTargets()?.firstOrNull { it.url == url }?.password
                },
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

        if (addingSsh) {
            SshDialog(
                onSubmit = { config, connection ->
                    // v92: persist the redial recipe first — the durable list
                    // (`ssh_json`) survives this panel's composition; the row
                    // re-appears as a resting DISCONNECTED entry on the next
                    // seed. No secret is persisted (SshEntry has no field for
                    // it; the next dial re-prompts).
                    sshStore?.putSshEntry(config.toSshEntry())
                    // Persist the config, observe the tunnel, and on READY write
                    // `status.localUrl` into `ServerConfigStore` + activate it —
                    // the session then dials `localhost:<port>` like upstream.
                    sshHost.submit(config, connection) { submitted, localUrl ->
                        val item = sshServerEntry(submitted, localUrl)
                        bridge?.upsert(item, password = null)
                        bridge?.setDefault(item.url)
                        // Remember which loopback row this tunnel fed, so a
                        // later confirmed delete cascades to it.
                        sshStore?.putSshEntry(submitted.toSshEntry(lastLocalUrl = localUrl))
                    }
                },
                onDismiss = { addingSsh = false },
            )
        }
        // v94: the row-level prompt card, in the same appended slot as the
        // config dialog (upstream's `promptOnly` push, `context.tsx:66`) — the
        // one renderer a challenge from a row ever had a path to. Each callback
        // records the offered promptId *before* it answers, so neither answering
        // nor cancelling can bring the same prompt back
        // (`authentication-state.ts:4-5`).
        if (sshPrompt != null && sshOfferedTunnel != null) {
            SshAuthenticate(
                prompt = sshPrompt,
                hostKey = sshOfferedTunnel.status.hostKey,
                onTrust = {
                    sshOffered = sshPromptOffered(
                        sshOffered,
                        SshPromptOffer(sshOfferedTunnel.config.id, sshPrompt.id),
                    )
                    sshOffer = null
                    sshOfferedTunnel.connection.respondTrust(true)
                },
                onContinue = { value ->
                    sshOffered = sshPromptOffered(
                        sshOffered,
                        SshPromptOffer(sshOfferedTunnel.config.id, sshPrompt.id),
                    )
                    sshOffer = null
                    sshOfferedTunnel.connection.respondPrompt(sshPrompt.id, value)
                },
                onCancel = {
                    // Cancel releases the challenge only: the row keeps whatever
                    // state the dial left it in for the user to act on, which is
                    // upstream's cancel on a row that is still saved.
                    sshOffered = sshPromptOffered(
                        sshOffered,
                        SshPromptOffer(sshOfferedTunnel.config.id, sshPrompt.id),
                    )
                    sshOffer = null
                    sshOfferedTunnel.connection.cancelPrompt()
                },
                windowHeightDp = sshPromptWindowHeightDp,
                imeBottomDp = sshPromptImeBottomDp,
                panelTopDp = sshPromptPanelTopDp,
                panelHeightDp = sshPromptPanelHeightDp,
            )
        }
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
                DropdownMenu(
                    expanded = menu,
                    onDismissRequest = { menu = false },
                    // v11: M3's default menu surface (`surfaceContainer`) is a
                    // light translucent tint the shell's own palette never
                    // re-themes, so in the dark theme the rows' `textBase` landed
                    // on a near-white panel. Opaque layer token + zero tonal
                    // elevation is the pairing the other shell menus use
                    // (`ui/session/context/SessionContextPanel.kt:419`), and the
                    // hairline keeps the edge readable against `bgBase`.
                    shape = RoundedCornerShape(6.dp),
                    containerColor = c.layer01,
                    tonalElevation = 0.dp,
                    border = BorderStroke(0.5.dp, c.borderMuted),
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                androidx.compose.ui.res.stringResource(R.string.oc_servers_menu_edit),
                                fontSize = 13.sp,
                                color = c.textBase,
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
                                color = c.textBase,
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
 * `http://10.0.2.2:4095` — see [DEFAULT_SERVER_URL_PLACEHOLDER]) → name →
 * username (default `opencode`) / password.
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
    storedPassword: String?,
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
                    probeTarget(
                        url = trimmed,
                        username = username,
                        typedPassword = password,
                        storedPassword = storedPassword,
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

    // v83: the second-level form gets the *band* it may take rather than the
    // whole window — see [serverDialogBandDp]. The settings layer already gives
    // way to the IME (v82), so this card's box is the space above the keyboard;
    // what it did not do was keep its own footer inside that box. One scrolling
    // column made the form a 244 dp viewport whose Save sat 197 dp below the fold
    // (`research/v83_verify/5556/26_edit_imeup.png` … `29_edit_kbdown.png`: y=523
    // with the keyboard down, nowhere on screen with it up).
    //
    // v86: this card rides the panel's own scroll content (header, list, then the
    // card — `ServersPanel`), so its top edge is not the slot's top. With the
    // keyboard up the space above it is smaller than the 160 dp the form prefers
    // (measured: card top y=231, keyboard top y=336 → 105 dp) and
    // [serverDialogBandDp] handed it that space instead, which landed the pinned
    // footer's bottom edge on the keyboard while the *form* got `space − footer`
    // — 24.5 dp at that measurement and 0 dp one scroll position lower
    // (`research/v86_verify/5556/ui_edit_pre_type.xml` / `ui_edit_kbdup.xml` /
    // `ui_now.xml`: Cancel/Save on screen, **no field node at all** in the a11y
    // tree, `04z_wide_kbd_up.png` / `12z_panel_crop.png` / `14z_card_crop.png` the
    // empty card). The inset fed below is the window's own keyboard height rather
    // than a value an ancestor's inset padding already spent
    // ([windowImeBottomDp] documents why Compose excludes consumed insets for
    // modifier reads but not for this composition read): 432 dp on that device.
    val density = LocalDensity.current
    val imeBottomDp = windowImeBottomDp()
    // The card's own top edge, so the band starts where the form does — in the
    // panel's flow (header, list, then this card; v85) rather than at the window's
    // top or at the slot's, both of which start above the card.
    var topInWindowDp by remember { mutableStateOf(0f) }
    // …and the panel region this card is drawn in: `PanelSlot`'s own visible box,
    // the same measurement the panel's selects flip their lists against. The card
    // may never be taller than it, so the form's floor is paid for out of the
    // panel the user can see rather than out of `space − footer` (v87) — and that
    // box's **bottom edge** is where the card's own bottom edge is drawn, in *both*
    // keyboard states (v88 up, v89 down, [serverDialogFloorDp]). With the keyboard
    // up the whole card then sits inside the region the user sees, its pinned footer
    // on the same line as the settings card's own bottom edge, instead of 21.5 dp of
    // its head above the panel's top edge (clipped away by the panel's scroller) and
    // its footer 48 px above the keyboard
    // (`research/v87_verify/5556/r1_edit_kbd.png`).
    //
    // v89 (`research/v88_verify/5556/06_edit_kbddown.png`,
    // `ui_15_edit_kbddown.xml`): with the keyboard *down* the floor used to be the
    // window's bottom — 132 dp past the line the panel's scroller clips at (y=635.5
    // on that device) — so the card was laid out from this same y=341 flow top to
    // y=768 and its footer drew outside the panel: the footer's a11y nodes were in
    // the tree at y=643…660 with no pixel of them on screen, and Cancel/Save could
    // only be reached by scrolling the panel or by raising the keyboard. On the
    // panel's own bottom edge the same state ends the card at y=635.5 with the
    // footer inside the panel.
    // 0 until the slot's first measurement lands; the band then reads the
    // keyboard alone, which is the safe branch of [serverDialogBandDp].
    val panel = LocalOcSelectContainerBounds.current
    val panelTopDp = panel?.topDp ?: -1f
    val panelHeightDp = panel?.let { (it.bottomDp - it.topDp).coerceAtLeast(0f) } ?: 0f
    val windowHeightDp = config.screenHeightDp.toFloat()
    val band = serverDialogBandDp(
        windowHeightDp = windowHeightDp,
        imeBottomDp = imeBottomDp.value,
        topInWindowDp = topInWindowDp,
        panelHeightDp = panelHeightDp,
        panelTopDp = panelTopDp,
    ).dp
    // What the card's own top edge gives way for: with the keyboard up, the flow
    // above this card (header + list) leaves less than the footer plus the form's
    // floor, so the card moves **up** until its pinned footer sits on the floor
    // (the panel's own bottom edge, v88) instead of the form being squeezed to
    // nothing (v87). With the keyboard down it is 0 wherever the panel's flow
    // leaves the floor at least the band (the v85 geometry at this card's flow top,
    // to the pixel) and non-zero only where it leaves less — then the card is drawn
    // from the floor up, footer inside the panel, exactly as it is with the
    // keyboard up (v89).
    val lift = serverDialogBandLiftDp(
        bandDp = band.value,
        windowHeightDp = windowHeightDp,
        imeBottomDp = imeBottomDp.value,
        topInWindowDp = topInWindowDp,
        panelTopDp = panelTopDp,
        panelHeightDp = panelHeightDp,
    ).dp

    Column(
        Modifier
            .width(width)
            // Measured *outside* the lift: the band reads the position the
            // panel's flow gives the card, never the lifted one, which would feed
            // the measurement back into the band and let a stale or scrolled
            // position walk the geometry (the v86 `ui_now.xml` state, where the
            // same card came out with a 57 px footer and a 0 dp form).
            .onGloballyPositioned { coords ->
                topInWindowDp = coords.positionInWindow().y / density.density
            }
            .offset(y = -lift)
            // …and a lifted card is drawn over the panel's header and list, whose
            // rows keep their own hit areas underneath: the shield takes the
            // touches that land on the card but on none of its controls, so a
            // hidden `⋮` / `Add SSH server` cannot fire under it. Absent while the
            // card is not lifted (keyboard down included), where the panel's own
            // scroll must keep receiving a drag over the card as before.
            .then(if (lift > 0.dp) Modifier.serverDialogLiftShield() else Modifier)
            // v88: with the keyboard up and the panel measured, the card's own box is
            // the **fixed** band, so its bottom edge lands on the floor the band was
            // measured against (`[serverDialogFloorDp]`: the panel's bottom edge) and
            // its height does not depend on how tall the form's content happens to be.
            // The keyboard-down shape keeps the max-only cap: there the card is the
            // height of its own content while the band has room for it (v89: the
            // band is the space down to the panel's own bottom edge, so a card whose
            // content fits above that line is drawn exactly where the v85/v88 wrap
            // put it — `ui_kbdown_scrolled.xml`, the card at y[193,635.5] with its
            // footer on the panel's bottom edge, is that same box to the pixel —
            // while one whose content does not is clamped to that edge, which is
            // what puts the footer inside the panel instead of past its clip).
            .then(
                if (imeBottomDp > 0.dp && panelHeightDp > 0f) {
                    Modifier.height(band)
                } else {
                    Modifier.heightIn(max = band)
                },
            )
            .background(c.bgBase, RoundedCornerShape(8.dp))
            .border(0.5.dp, c.borderBase, RoundedCornerShape(8.dp)),
    ) {
        // The form is the block that gives way: it takes its own height while the
        // band has room for it, and turns into a scroller once it has not
        // (`fill = false` — the v82 lesson that a band belongs *outside* the
        // height, not inside it, holds for this weight the same way). With the
        // keyboard up — where the band *is* the card's box — the same weight is
        // `fill = true`, so the form takes the whole box above the pinned footer
        // whatever its content measures. Header / body padding from §5.3; the
        // footer's 12 dp gap below is its own padding.
        Column(
            Modifier
                .then(
                    if (imeBottomDp > 0.dp && panelHeightDp > 0f) {
                        Modifier.weight(1f)
                    } else {
                        Modifier.weight(1f, fill = false)
                    },
                )
                .verticalScroll(rememberScrollState())
                .padding(PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp)),
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
        }
        // …and Cancel / Save never scroll out of the card: the footer is pinned to
        // the card's bottom edge, which sits inside the band, so the primary
        // action stays tappable while the keyboard is up — and, since v89, with it
        // down as well: the card's own bottom edge is then the panel's, so the
        // buttons are inside the region the panel paints and Save needs no scroll.
        // The 12 dp top padding is the gap the single column's `spacedBy(12.dp)`
        // used to leave above the row, so the footer's own box is the same one it
        // has always had.
        Column(
            Modifier.padding(PaddingValues(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 24.dp)),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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
}
