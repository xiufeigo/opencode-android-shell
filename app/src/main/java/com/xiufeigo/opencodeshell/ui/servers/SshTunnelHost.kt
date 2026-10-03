package com.xiufeigo.opencodeshell.ui.servers

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ServerConfigStore
import com.xiufeigo.opencodeshell.ui.settings.OcBodyText
import com.xiufeigo.opencodeshell.ui.settings.OcButton
import com.xiufeigo.opencodeshell.ui.settings.ServerListItem
import com.xiufeigo.opencodeshell.ui.settings.ServerRowMenu
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Submit-side host for [SshDialog]: owns the live [SshConnection]s the form
 * hands over (`dialog.tsx:198-215` keeps the ready tunnel for the route), and
 * folds each tunnel's [SshConnectionStatus] into one list the settings Servers
 * page and the session route can both render.
 *
 * Wiring contract of `SshDialog.onSubmit` → here:
 * 1. **persist** the [SshConnectionConfig] (process-scoped — durable storage
 *    belongs with the server rows, see the handoff note on `ServerConfigStore`);
 * 2. **observe** `connection.state`;
 * 3. on [SshStage.READY] hand `status.localUrl` (`http://127.0.0.1:<port>`) to
 *    [onReady] exactly once per entry into READY — the host writes it into
 *    `ServerConfigStore` and activates it, after which the session dials
 *    `localhost:<port>` like upstream.
 *
 * Reconnect/disconnect are the [SshConnectionPanel] buttons: `reconnect` is a
 * fresh dial over the stored config (`connection-panel.tsx:41`), `disconnect`
 * releases the forward + session ("the remote server is left running").
 */
class SshTunnelHost(private val scope: CoroutineScope) {

    private val _tunnels = MutableStateFlow<List<SshTunnel>>(emptyList())
    val tunnels: StateFlow<List<SshTunnel>> = _tunnels.asStateFlow()

    private val watchJobs = mutableMapOf<String, Job>()

    fun submit(
        config: SshConnectionConfig,
        connection: SshConnection,
        onReady: (SshConnectionConfig, String) -> Unit,
    ) {
        _tunnels.update { list ->
            list.filterNot { it.config.id == config.id } + SshTunnel(config, connection, connection.state.value)
        }
        watchJobs.remove(config.id)?.cancel()
        watchJobs[config.id] = scope.launch {
            var lastStage = connection.state.value.stage
            connection.state.collect { status ->
                _tunnels.update { list ->
                    list.map { if (it.config.id == config.id) it.copy(status = status) else it }
                }
                val localUrl = sshReadyLocalUrl(status)
                if (localUrl != null && lastStage != SshStage.READY) onReady(config, localUrl)
                lastStage = status.stage
            }
        }
    }

    fun reconnect(id: String) {
        val tunnel = _tunnels.value.firstOrNull { it.config.id == id } ?: return
        scope.launch { tunnel.connection.reconnect() }
    }

    /**
     * v92 — seed the persisted `ssh_json` recipes ([mergeSeededSshTunnels]).
     *
     * The settings panel's host is composition-scoped: leaving Settings (or a
     * locale switch, which recreates the composition) dropped every SSH row
     * while the durable records sat unreachable in the encrypted prefs
     * (`research/v91_verify/5554+5556/`, P1). The panel calls this once per
     * fresh composition; the merge is idempotent (live entries win, so a
     * re-seed never clobbers connection state) and a row removed through
     * [forget] + the persisted remover cannot resurrect.
     */
    fun seed(persisted: List<ServerConfigStore.SshEntry>) {
        _tunnels.update { live -> mergeSeededSshTunnels(live, persisted) }
    }

    fun disconnect(id: String) {
        _tunnels.value.firstOrNull { it.config.id == id }?.connection?.disconnect()
    }

    /**
     * Disconnect and drop the entry (the dialog's cancel of a finished attempt).
     *
     * v91: this is the SSH row's ⋮ → Delete target and has exactly one call
     * site (`SshTunnelCard`'s confirm), so the list can shrink for the first
     * time — a failed dial used to leave its row behind forever
     * (`research/v90_verify/5556/`). The release comes **before** the drop:
     * `disconnect` tears the forward/session down (and clears a pending prompt),
     * the status watcher is cancelled so no late emission can repaint the row,
     * and only then does [sshTunnelsWithout] take the entry out. Deleting is
     * allowed in every stage — a row stuck in CONNECTING or FAILED is the one
     * that needs the exit most.
     */
    fun forget(id: String) {
        val tunnel = _tunnels.value.firstOrNull { it.config.id == id }
        // v97 D3 — the release order below (disconnect → cancel the watcher →
        // drop) is v91's and stays exactly as it was; what was missing is that
        // the release did not reach the dial itself. A row parked at
        // `Authentication required` has a coroutine sitting in
        // `answers.poll(600 s)`, and tearing the session down left it free to
        // publish another `Fail` or re-offer another card for an entry the user
        // had just deleted — which is what the v97 device saw as a confirm bar
        // that answered nothing until the user Disconnected first. `abort()`
        // invalidates that attempt first, so nothing it raises is published
        // again no matter what the release below does.
        tunnel?.connection?.abort()
        disconnect(id)
        watchJobs.remove(id)?.cancel()
        _tunnels.update { list -> sshTunnelsWithout(list, id) }
    }
}

/** One submitted SSH config plus its live tunnel. */
data class SshTunnel(
    val config: SshConnectionConfig,
    val connection: SshConnection,
    val status: SshConnectionStatus,
)

/**
 * `status.localUrl` once — and only while — the forward is up: the
 * `ServerConfigStore` url to persist and activate on [SshStage.READY].
 */
fun sshReadyLocalUrl(status: SshConnectionStatus): String? =
    status.localUrl?.takeIf { status.stage == SshStage.READY }

/**
 * The `ServerConfigStore` row a ready tunnel writes (and activates): the
 * forwarded loopback url, the dialog's display name (`sshName`), and the SSH
 * target's user as the server username (null for a bare host).
 */
fun sshServerEntry(config: SshConnectionConfig, localUrl: String): ServerListItem = ServerListItem(
    url = localUrl,
    name = sshName(config.name, config.target),
    username = parseSshTarget(config.target)?.user,
)

/**
 * The session route's SSH fallback surface: full-screen [SshConnectionPanel]
 * for one host tunnel (reconnect / disconnect included). The route should call
 * this instead of its content while `sshReadyLocalUrl(tunnel.status) == null`.
 */
@Composable
fun SshTunnelPanel(
    host: SshTunnelHost,
    configId: String,
    modifier: Modifier = Modifier,
) {
    val tunnels by host.tunnels.collectAsState()
    val tunnel = tunnels.firstOrNull { it.config.id == configId } ?: return
    SshConnectionPanel(
        name = sshName(tunnel.config.name, tunnel.config.target),
        status = tunnel.status,
        onReconnect = { host.reconnect(configId) },
        onDisconnect = { host.disconnect(configId) },
        modifier = modifier,
    )
}

/**
 * Compact tunnel row for the settings Servers list — stage + reconnect/disconnect,
 * plus the WebSocket row's own remove entry (⋮ → Delete → confirm, v91).
 *
 * The ⋮ trigger, its menu surface and the danger item are `ServerRow`'s
 * (`ServerRowMenu`, the `oc_servers_menu_delete` item, the `layer01` /
 * `borderMuted` popup) so both halves of the Servers list are removed the same
 * way. What `ServerRow` has not got is the confirmation step; here the tap only
 * *arms* the row ([SshForgetState.ask]) and the in-card confirm bar
 * ([SshTunnelForgetConfirm]) is the single path to [SshTunnelHost.forget] —
 * a mis-tap on the ⋮ cannot delete a tunnel.
 *
 * v93 — narrow-window readable identity: the primary line used to be a single
 * `Row` — the name/stage column at `weight(1f)` beside Reconnect + Disconnect +
 * ⋮. A Compose `Row` measures its unweighted children first and hands the
 * weighted column whatever is left, so on a row narrower than the three
 * trailing controls the identity column measured to **zero width**: no tunnel
 * name, no stage text, "Disconnect" hard-wrapping mid-word, and on the phone
 * the status line at ~2 characters per line
 * (`research/v91_verify/5558/s13_failedclean.png`; the wide shape that still
 * fits is `research/v91_verify/5554/ui_46_row3.xml`).
 *
 * The row therefore measures itself ([BoxWithConstraints]): at or above
 * [SshTunnelRowStackedWidth] it draws the same one-line `Row` as always; below
 * it the identity column moves to its own full-width line and the three
 * controls share the line below ([FlowRow], which flows an overflowing control
 * to the next line instead of squeezing it), so the name is always composed on
 * a line whose width the buttons cannot take away. The threshold is pure
 * ([sshTunnelRowStacked]) so the JVM suite pins it without a Compose runtime.
 * The name never wraps (`maxLines = 1`, `softWrap = false`, ellipsized) and
 * the buttons keep their intrinsic width ([SshTunnelButtonWidth]) at any row
 * width — ellipsizing the tunnel name on a cramped row is acceptable, losing
 * it or wrapping a button label mid-word is not.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SshTunnelCard(
    tunnel: SshTunnel,
    host: SshTunnelHost,
    modifier: Modifier = Modifier,
    /**
     * v92 — the persisted-record remover for the confirmed delete
     * ([confirmSshForget] runs it **before** `host.forget`, so a seeded row
     * cannot resurrect on the next seed). The card stays store-free: the
     * panel hands in its `SshEntryStore` seam (plus the loopback-row
     * cascade). The default keeps the card usable without a store.
     */
    removePersisted: (String) -> Boolean = { false },
) {
    var forget by remember { mutableStateOf(SshForgetState()) }
    var menu by remember { mutableStateOf(false) }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val stacked = sshTunnelRowStacked(maxWidth)
        Column(Modifier.fillMaxWidth()) {
            if (stacked) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SshTunnelRowIdentity(
                        tunnel = tunnel,
                        modifier = Modifier.fillMaxWidth().widthIn(min = SshTunnelRowIdentityMin),
                    )
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        SshTunnelRowButtons(tunnel = tunnel, host = host)
                        Spacer(Modifier.width(4.dp))
                        Box {
                            ServerRowMenu(onClick = { menu = true })
                            SshTunnelRowMenu(
                                stage = tunnel.status.stage,
                                expanded = menu,
                                onDismiss = { menu = false },
                                onConnect = {
                                    menu = false
                                    host.reconnect(tunnel.config.id)
                                },
                                onDelete = {
                                    menu = false
                                    forget = forget.ask(tunnel.config.id)
                                },
                            )
                        }
                    }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SshTunnelRowIdentity(
                        tunnel = tunnel,
                        modifier = Modifier.weight(1f).widthIn(min = SshTunnelRowIdentityMin),
                    )
                    Spacer(Modifier.width(8.dp))
                    SshTunnelRowButtons(tunnel = tunnel, host = host)
                    Spacer(Modifier.width(4.dp))
                    Box {
                        ServerRowMenu(onClick = { menu = true })
                        SshTunnelRowMenu(
                            stage = tunnel.status.stage,
                            expanded = menu,
                            onDismiss = { menu = false },
                            onConnect = {
                                menu = false
                                host.reconnect(tunnel.config.id)
                            },
                            onDelete = {
                                menu = false
                                forget = forget.ask(tunnel.config.id)
                            },
                        )
                    }
                }
            }
            if (forget.armed == tunnel.config.id) {
                SshTunnelForgetConfirm(
                    name = sshName(tunnel.config.name, tunnel.config.target),
                    releaseFirst = sshForgetReleasesTunnel(tunnel.status.stage),
                    onCancel = { forget = forget.cancel() },
                    onConfirm = {
                        // v92: one call path — persisted removal first, then
                        // the live drop (removal-before-forget), so the row
                        // cannot resurrect on the next seed.
                        confirmSshForget(forget, tunnel.config.id, host, removePersisted)
                        forget = forget.cancel()
                    },
                )
            }
        }
    }
}

/**
 * The width below which [SshTunnelCard] stacks its identity above its controls.
 *
 * Pure dp rule on the **row's own width**, not the window: the row lives inside
 * `SettingsList` (20 dp a side) inside `SettingsPanelBody` (40 dp a side), so
 * below the sidebar the row is the dialog (`min(window − 32, 980)`) minus
 * 120 dp ([sshTunnelRowDp]).
 *
 * The arithmetic is the desktop line's own fixed cost plus the identity floor:
 * worst case the CONNECTING button ("Connecting to SSH server", ~25 glyphs at
 * 13 sp/500 ≈ 175 dp — the v91 5554 dump scales EN "Reconnect" (9 glyphs) to a
 * ~70 dp label, i.e. ~7.8 dp/glyph) with its 28 dp of chrome ≈ 200 dp, the
 * Disconnect button ≈ 104 dp chrome-included (103.5 dp on the same dump), the
 * 28 dp ⋮ hit target and 20 dp of spacers (8 + 8 + 4) — ≈ 352 dp of controls —
 * plus [SshTunnelRowIdentityMin]: 352 + 96 = 448, rounded to **450 dp**. Below
 * that the CONNECTING line cannot hold the floor and the row stacks; every
 * other stage's labels are shorter, so the one rule covers all of them. The
 * 350 dp freeform window (5556: dialog 318, row 198) and the phone (5558: 411
 * dp window, dialog 379, row 259) both stack; the 852 dp foldable inner
 * (dialog 820, sidebar 240, row 460) and the tablet (dialog 980, row 620) keep
 * the one-line shape. (CJK CONNECTING copies run ~30 dp longer and may spill a
 * few dp past a 460 dp row instead of collapsing the name — the floor wins by
 * design; the labels stay whole either way.)
 */
internal val SshTunnelRowStackedWidth: Dp = 450.dp

/**
 * The identity (name + stage) column's floor: the least width the row keeps
 * for *which* tunnel a row belongs to, in either shape.
 *
 * 96 dp is ~13 average 13 sp glyphs — the `10.255.255.1:22`-class loopback
 * targets the list actually carries (≈ 98 dp) stay legible instead of
 * ellipsizing to nothing, and a longer name ellipsizes rather than vanishing
 * (the v93 defect). It also guards locales whose button labels are narrower
 * than the English ones the threshold above is derived from: the weighted
 * column can never reach zero without tripping the stack rule first.
 */
internal val SshTunnelRowIdentityMin: Dp = 96.dp

/**
 * [SshTunnelCard]'s shape decision, pure so a unit test can pin it: the panels
 * have no JVM-renderable surface in this project (see `SettingsPanelScrollTest`
 * and the `HomeRowLayout.narrow` / `settingsRowStacked` precedents), and the
 * branch is a *width* rule, not a source fact. `true` while the measured
 * [rowWidth] is below [SshTunnelRowStackedWidth]. A zero (or negative) width —
 * a collapsed host, a preview — is **not** narrow: no measurement, no
 * behaviour change (`SettingsPanelLayout.compactHeader`'s rule).
 */
internal fun sshTunnelRowStacked(rowWidth: Dp): Boolean =
    rowWidth > 0.dp && rowWidth < SshTunnelRowStackedWidth

/**
 * The settings dialog's own width for a window: `min(window − 32, 980)` —
 * [SettingsScreen]'s `BoxWithCappedSize` (`width = minOf(screenWidthDp − 32,
 * 980)`). Pure so the window → row derivation below is JVM-testable.
 */
internal fun sshTunnelDialogDp(windowDp: Float): Float = minOf(windowDp - 32f, 980f)

/**
 * The SSH row's own width for a dialog: the dialog minus the 120 dp of
 * list/body insets between them (`SettingsList`'s 20 dp a side plus
 * `SettingsPanelBody`'s 40 dp a side), and minus the 240 dp sidebar wherever
 * the wide nav shape shows it ([sidebarDp], 0 below the 640 dp nav
 * breakpoint). Pure so the threshold's device coverage is JVM-testable.
 */
internal fun sshTunnelRowDp(dialogDp: Float, sidebarDp: Float = 0f): Float =
    dialogDp - sidebarDp - 120f

/**
 * Whether a window of [windowDp] stacks the SSH row: the dialog → row
 * derivation above read through [sshTunnelRowStacked]. Pure so the suite pins
 * the two reporting windows (stacked) and the two fitting ones (one line)
 * by their window widths alone.
 */
internal fun sshTunnelStackedForWindow(windowDp: Float, sidebarDp: Float = 0f): Boolean =
    sshTunnelRowStacked(sshTunnelRowDp(sshTunnelDialogDp(windowDp), sidebarDp).dp)

/**
 * The row's two actions — Reconnect (/ its CONNECTING copy) + Disconnect —
 * both [SshTunnelCard] shapes share, with the spacer between them.
 *
 * Each button keeps its intrinsic width ([SshTunnelButtonWidth]): the label is
 * measured whole wherever the row puts the pair — the wide line where it fits,
 * the stacked [FlowRow] which flows an overflowing control to the next line
 * instead of squeezing it — so no label can wrap mid-word at any row width.
 * `OcButton` itself is shared with every other settings surface and is not
 * this row's to change; the width modifier is the row-local equivalent of the
 * `maxLines = 1` / `softWrap = false` the row's own name text carries.
 */
@Composable
private fun SshTunnelRowButtons(tunnel: SshTunnel, host: SshTunnelHost) {
    val connecting = isSshConnecting(tunnel.status.stage)
    OcButton(
        label = stringResource(
            if (connecting) R.string.ssh_session_connecting else R.string.ssh_session_reconnect,
        ),
        primary = false,
        enabled = !connecting,
        onClick = { host.reconnect(tunnel.config.id) },
        modifier = SshTunnelButtonWidth,
    )
    Spacer(Modifier.width(8.dp))
    OcButton(
        label = stringResource(R.string.ssh_disconnect),
        enabled = tunnel.status.stage != SshStage.DISCONNECTED,
        onClick = { host.disconnect(tunnel.config.id) },
        modifier = SshTunnelButtonWidth,
    )
}

/**
 * The intrinsic-width modifier both row buttons are measured with: the button
 * takes its label's whole single-line width, so the text cannot wrap even when
 * the row is narrower than the pair (the stacked [FlowRow] then moves the
 * second button down instead). A no-op wherever the row used to fit — the
 * loose measurement already landed on the intrinsic width there — which is
 * what keeps the wide geometry pixel-identical.
 */
private val SshTunnelButtonWidth: Modifier = Modifier.width(IntrinsicSize.Max)

/**
 * The name + stage (+ error) column both [SshTunnelCard] shapes share.
 *
 * The name is `maxLines = 1` + `softWrap = false` + ellipsis: ellipsizing the
 * tunnel name on a cramped row is acceptable, losing it (the v93 defect) is
 * not. The stage/error lines keep their natural wrap on the stacked
 * full-width line, where no button competes for the width.
 */
@Composable
private fun SshTunnelRowIdentity(tunnel: SshTunnel, modifier: Modifier = Modifier) {
    val c = OcTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = sshName(tunnel.config.name, tunnel.config.target),
            color = c.textBase,
            fontSize = 13.sp,
            fontWeight = FontWeight(530),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        OcBodyText(
            text = stringResource(sshStageResId(tunnel.status.stage)),
            muted = true,
            size = 11.sp,
        )
        tunnel.status.error?.let {
            OcBodyText(text = stringResource(sshErrorResId(it)), muted = true, size = 11.sp)
        }
    }
}

/**
 * **v99 — the row menu's entries, ported from `servers/ssh/menu.tsx`.**
 *
 * Upstream draws three things off one `<Show when={item()}>`, and the show is
 * the whole story: it is gated on the *item existing* and on nothing else, so
 * the `⋮` trigger is present in every stage — including `connecting` — and is
 * never a `busy` question. The gating that upstream *does* apply is narrower,
 * and each part is a different rule:
 *
 * | upstream | rule |
 * |---|---|
 * | `<Show when={item()}>` around the whole `Menu` | [sshRowMenuVisible] — stage-independent |
 * | `<Show when={item().stage !== "ready"}>` around Connect | [sshRowMenuShowsConnect] |
 * | `<Menu.Item disabled={ssh.pending(props.id)}>` | [sshRowMenuConnectEnabled] — the **only** `pending` gate upstream has |
 * | `<Menu.Item onSelect={…remove(key())}>` on Delete | [sshRowMenuDeleteEnabled] — no gate at all |
 *
 * So "a `connecting` row hides its menu" is not upstream's rule, and the shell
 * never implemented it: the `⋮` in [SshTunnelCard] is emitted in both row
 * shapes regardless of stage. What the shell was missing is the *Connect*
 * entry — the menu carried Delete only — so a row parked at `authentication`
 * (upstream: "Authenticate" in the menu) or `failed`/`disconnected`
 * (upstream: "Connect") had no way back from the row itself.
 *
 * Kept pure and separate from the composable for the same reason the rest of
 * this package is: the JVM suite pins the rules, the row just reads them.
 */
internal fun sshRowMenuVisible(itemExists: Boolean): Boolean = itemExists

/** `menu.tsx:30` — Connect/Authenticate is drawn for every stage but `ready`. */
internal fun sshRowMenuShowsConnect(stage: SshStage): Boolean = stage != SshStage.READY

/**
 * `menu.tsx:31` — `disabled={ssh.pending(props.id)}`.
 *
 * The shell's [isSshConnecting] is the same fact as upstream's `pending`: the
 * in-flight stages, which is precisely the window where a second dial would be
 * the v97 **D2** shape (a parked attempt outliving a settled success and
 * publishing `Fail` over it). So this is the one entry that *is* disabled
 * mid-flight — and note what it does not imply: the row menu around it stays
 * open and usable.
 */
internal fun sshRowMenuConnectEnabled(stage: SshStage, pending: Boolean = false): Boolean =
    !pending && !isSshConnecting(stage)

/**
 * `menu.tsx:46` — Delete carries **no** disabled gate in any stage, `connecting`
 * included. This is the entry the v98 device round needed and could not reach,
 * so the rule is stated as a function rather than left as the absence of one:
 * "nothing gates Delete" has to be something a test can fail on, otherwise the
 * next person to add a `busy` guard around the menu loses it silently.
 */
internal fun sshRowMenuDeleteEnabled(stage: SshStage): Boolean =
    stage == stage // upstream has no gate; the parameter is the assertion's hook

/**
 * The rows `menu.tsx:28-49` draws, in the order it draws them.
 *
 * Upstream's `SshMenu` is a `Menu.Group` whose children are, in order: a
 * [GroupLabel] (`menu.tsx:29`), the gated Connect/Authenticate entry
 * (`:30-33`), exactly one of Set-as-default / Remove-default (`:35` and `:40`,
 * mutually exclusive by `defaults.key() !== key()`), a [Separator] (`:45`),
 * and finally the ungated Delete entry (`:46`).
 *
 * Modelled as a list rather than four booleans so the **order** is part of the
 * assertion: the label leads the popup, and the separator splits the
 * "drive this connection" group from the destructive one. A future edit that
 * reorders them changes a list comparison and fails.
 */
internal enum class SshRowMenuEntry { GroupLabel, Connect, SetDefault, RemoveDefault, Separator, Delete }

/**
 * `menu.tsx:28-49` — the popup's children, in order.
 *
 * [stage] decides [SshRowMenuEntry.Connect] via [sshRowMenuShowsConnect] and
 * whether it is usable via [sshRowMenuConnectEnabled].
 *
 * [defaultsAvailable] and [isDefault] mirror upstream `defaults.available()`
 * (`registry/controller.ts:45`) and `defaults.key() === key()` (`menu.tsx:40`).
 * **The shell has no equivalent concept to feed them** — its `isDefault` is an
 * HTTP-server identity check (`ui/settings/SettingsServerBridge.kt:137`) over a
 * URL an SSH entry does not have, and the store it mutates
 * (`SettingsServerBridge.setDefault`, `:205`) lives in the `ui.settings`
 * package — outside this file's write scope — and switches the *active* server
 * rather than a launch-time default. So every call site passes the
 * upstream-absent branch (`available() === false`) and both default entries are
 * therefore **never** drawn. They are modelled anyway so the ordering the
 * upstream file asks for is pinned here rather than living only in a report;
 * see `research/v97_fix/SSH_MENU_PARITY2.md` §2.
 *
 * [separator] is the upstream `menu.tsx:45` rule: unconditional, immediately
 * before Delete — *not* inside the `Connect` `Show`, which is why a `ready`
 * row still shows it above an otherwise single-entry menu.
 */
internal fun sshRowMenuEntries(
    stage: SshStage,
    defaultsAvailable: Boolean = false,
    isDefault: Boolean = false,
): List<SshRowMenuEntry> = buildList {
    add(SshRowMenuEntry.GroupLabel)
    if (sshRowMenuShowsConnect(stage)) add(SshRowMenuEntry.Connect)
    if (defaultsAvailable && !isDefault) add(SshRowMenuEntry.SetDefault)
    if (defaultsAvailable && isDefault) add(SshRowMenuEntry.RemoveDefault)
    add(SshRowMenuEntry.Separator)
    add(SshRowMenuEntry.Delete)
}

/**
 * The ⋮ popup both [SshTunnelCard] shapes share: `ServerRow`'s own surface
 * (v11 — the shell's opaque layer token + zero tonal elevation, so the row's
 * `textBase` never lands on M3's unthemed light `surfaceContainer`).
 *
 * The tap on Delete only *arms* the row ([SshForgetState.ask]); the in-card
 * confirm bar below the row is what calls `forget`. Connect re-enters
 * [SshTunnelHost.reconnect] — the same call the row's own Reconnect button
 * makes — and is disabled mid-flight per [sshRowMenuConnectEnabled].
 */
@Composable
private fun SshTunnelRowMenu(
    stage: SshStage,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onConnect: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = OcTheme.colors
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        // ServerRow's own surface (v11): the shell's opaque layer
        // token + zero tonal elevation, so the row's `textBase` never
        // lands on M3's unthemed light `surfaceContainer`.
        shape = RoundedCornerShape(6.dp),
        containerColor = c.layer01,
        tonalElevation = 0.dp,
        border = BorderStroke(0.5.dp, c.borderMuted),
    ) {
        // `menu.tsx:28-49` in upstream order, from the one rule the whole
        // popup is derived from. `sshRowMenuEntries` also carries the two
        // never-drawn default entries (no such concept in the shell), so the
        // loop simply never sees them.
        sshRowMenuEntries(stage).forEach { entry ->
            when (entry) {
                // `menu.tsx:29` — `<Menu.GroupLabel>{language.t("ssh.server.menu.label")}</Menu.GroupLabel>`.
                // Upstream's own i18n key, already carried in all three locales
                // (`ssh_server_menu_label` = "SSH server" / "SSH 服务器" / "SSH 伺服器"),
                // so this adds no new user-visible string.
                SshRowMenuEntry.GroupLabel -> SshRowMenuGroupLabel()

                // `menu.tsx:30-33` — every stage but `ready`; disabled while pending.
                SshRowMenuEntry.Connect -> DropdownMenuItem(
                    text = {
                        Text(
                            // `menu.tsx:32` — "authenticate" on the auth wall, else connect.
                            text = stringResource(
                                if (stage == SshStage.AUTHENTICATION) {
                                    R.string.ssh_action_authenticate
                                } else {
                                    R.string.ssh_session_reconnect
                                },
                            ),
                            color = if (sshRowMenuConnectEnabled(stage)) c.textBase else c.textFaint,
                            fontSize = 13.sp,
                        )
                    },
                    // `disabled={ssh.pending(props.id)}` — the menu itself stays
                    // open and Delete stays live; only this entry is muted.
                    enabled = sshRowMenuConnectEnabled(stage),
                    onClick = onConnect,
                )

                // `menu.tsx:35` / `:40` — unreachable: the shell has no
                // default-server concept (see [sshRowMenuEntries]). Left in
                // the enumeration so the upstream slot is pinned rather than
                // quietly forgotten; a draw branch here would be a lie.
                SshRowMenuEntry.SetDefault,
                SshRowMenuEntry.RemoveDefault,
                -> Unit

                // `menu.tsx:45` — `<Menu.Separator />`, above Delete and
                // outside the Connect `Show`, so it survives a `ready` row too.
                SshRowMenuEntry.Separator -> HorizontalDivider(thickness = 0.5.dp, color = c.borderMuted)

                SshRowMenuEntry.Delete -> DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(R.string.oc_servers_menu_delete),
                            color = c.stateDangerFg,
                            fontSize = 13.sp,
                        )
                    },
                    // Arms only — the tap cannot remove anything. The confirm
                    // bar below the row is what calls `forget`. No stage gates
                    // this entry (`menu.tsx:46`), `connecting` included.
                    enabled = sshRowMenuDeleteEnabled(stage),
                    onClick = onDelete,
                )
            }
        }
    }
}

/**
 * `menu.tsx:29`'s `Menu.GroupLabel`, in the shell's established idiom — the same
 * muted 11 sp / 530 label and 12 dp insets `NewSessionScreen`'s
 * `MenuGroupLabel` (`:1917-1927`) uses inside its own dropdown, so the two
 * popups do not read as two different design systems.
 */
@Composable
private fun SshRowMenuGroupLabel() {
    val c = OcTheme.colors
    Text(
        text = stringResource(R.string.ssh_server_menu_label),
        color = c.textMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight(530),
        lineHeight = 16.sp,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 2.dp),
    )
}
