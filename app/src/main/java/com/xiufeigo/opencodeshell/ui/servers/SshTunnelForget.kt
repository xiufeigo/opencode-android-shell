package com.xiufeigo.opencodeshell.ui.servers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ServerConfigStore
import com.xiufeigo.opencodeshell.ui.settings.OcBodyText
import com.xiufeigo.opencodeshell.ui.settings.OcButton
import com.xiufeigo.opencodeshell.ui.theme.OcTheme

/**
 * v91 「SSH 条目删不掉」(`research/v90_verify/5556/`) — the SSH row's delete, the
 * missing half of `SshTunnelCard`.
 *
 * The row only ever carried reconnect/disconnect, so a tunnel that failed to
 * dial (the E2ECHK5556-ssh row) stayed in `SSH tunnels` for the life of the
 * settings screen: Cancel closed the dialog, long-press did nothing and
 * Disconnect only tore the forward down — `SshTunnelHost.forget` was dead code
 * with no call site. The WebSocket half of the same list (`ServerRow`) has the
 * ⋮ → Delete entry; this file is the SSH row's answer to it, and the *only*
 * path from a tap to a removal is [SshForgetState.confirm].
 *
 * Delete is offered in **every** stage — a row that hangs in CONNECTING (or
 * sits in FAILED) is exactly the state the user needs an exit from, so the
 * entry is never gated on the stage. What changes with the stage is only the
 * *copy*: a live or in-flight tunnel is released first
 * ([sshForgetReleasesTunnel] ⇒ the confirm spells it out), which is what
 * [SshTunnelHost.forget] does — `disconnect` before the drop.
 */
data class SshForgetState(val armed: String? = null) {

    /** ⋮ → Delete: arms one row for confirmation. Nothing is removed here. */
    fun ask(id: String): SshForgetState =
        if (id.isEmpty()) this else SshForgetState(armed = id)

    /** Cancel / the row gone away: no row is armed. */
    fun cancel(): SshForgetState = SshForgetState()

    /**
     * The confirm button — the **only** transition that hands back an id to
     * forget. A tap that does not answer the armed row (a double tap, a stale
     * arm from another row) forgets nothing.
     */
    fun confirm(id: String): String? = id.takeIf { armed == it && it.isNotEmpty() }
}

/**
 * The confirm tap's one call path, in order: keep the confirmed id, drop the
 * persisted `ssh_json` record, then release + drop the live tunnel. Returns
 * the forgotten id, or null when the tap answers no arm.
 *
 * The persisted removal runs on the same path as `host.forget(id)`:
 * `SshTunnelHost` owns only the in-memory list, so without this the seeded
 * row (see [mergeSeededSshTunnels]) would resurrect on the next seed.
 *
 * `removePersisted` is the store seam (the panel's persisted `ssh_json`
 * remover at the real call site): a `(String) -> Boolean` instead of the
 * store itself so this file — the row's delete vocabulary — never imports
 * the settings layer back (the panel already bridges the two).
 *
 * The card resets its own [SshForgetState] around the call (disarm is
 * display state); this function only decides *what* is forgotten. Callers
 * that only release or redial the tunnel never reach this function, so
 * reconnect/disconnect leave persistence untouched.
 */
fun confirmSshForget(
    armed: SshForgetState,
    id: String,
    host: SshTunnelHost,
    removePersisted: (String) -> Boolean,
): String? {
    val target = armed.confirm(id) ?: return null
    removePersisted(target)
    host.forget(target)
    return target
}

/**
 * Adopts one persisted [ServerConfigStore.SshEntry] as a resting in-memory
 * tunnel: a DISCONNECTED [SshTunnel] whose [SshConnection] carries the
 * redial recipe (id/target/name/auth + key path) but no secret — the
 * password/key passphrase is re-collected interactively on the next dial
 * (`SshConnection` prompts when its config has none).
 *
 * Resting rows render exactly like a disconnected tunnel; reconnect is a
 * fresh dial over the carried config.
 */
fun ServerConfigStore.SshEntry.toRestingTunnel(): SshTunnel {
    val config = SshConnectionConfig(
        id = id,
        target = target,
        name = name,
        auth = when (authKind) {
            ServerConfigStore.SshAuthKind.PASSWORD -> SshAuthMethod.PASSWORD
            ServerConfigStore.SshAuthKind.KEY -> SshAuthMethod.KEY
        },
        privateKeyPath = privateKeyPath,
    )
    return SshTunnel(config, SshConnection(config), SshConnectionStatus(stage = SshStage.DISCONNECTED))
}

/**
 * The submit-side half of [toRestingTunnel]: the redial recipe a submitted
 * [SshConnectionConfig] persists as. **No secret material** — the password /
 * key passphrase live only for the life of the dial and are re-collected
 * interactively on the next one, so they never reach `ssh_json`
 * ([ServerConfigStore.SshEntry] has no field for them).
 *
 * [lastLocalUrl] is written by the READY callback so a later delete can
 * cascade to the loopback server row this tunnel fed (`ServersPanel`'s
 * `removePersisted`).
 */
fun SshConnectionConfig.toSshEntry(lastLocalUrl: String? = null): ServerConfigStore.SshEntry =
    ServerConfigStore.SshEntry(
        id = id,
        target = target,
        name = name,
        authKind = when (auth) {
            SshAuthMethod.PASSWORD -> ServerConfigStore.SshAuthKind.PASSWORD
            SshAuthMethod.KEY -> ServerConfigStore.SshAuthKind.KEY
        },
        privateKeyPath = privateKeyPath,
        lastLocalUrl = lastLocalUrl,
    )

/**
 * Seed/merge of the persisted `ssh_json` list into the host's live list.
 *
 * Id-keyed, order-preserving:
 * - an id present in both keeps the live [SshTunnel] (it carries connection
 *   state the store never sees);
 * - an id only in prefs is adopted via [toRestingTunnel] (a DISCONNECTED row);
 * - an id only in memory (just submitted this session, not yet persisted)
 *   stays untouched.
 *
 * Live order wins; adopted leftovers append in store order. Pure over the two
 * lists so the merge rule is JVM-testable.
 */
fun mergeSeededSshTunnels(
    live: List<SshTunnel>,
    persisted: List<ServerConfigStore.SshEntry>,
): List<SshTunnel> {
    if (persisted.isEmpty()) return live
    if (live.isEmpty()) return persisted.map { it.toRestingTunnel() }
    val byId = live.associateBy { it.config.id }
    val merged = ArrayList<SshTunnel>(live.size + persisted.size)
    merged.addAll(live)
    for (entry in persisted) {
        if (!byId.containsKey(entry.id)) merged.add(entry.toRestingTunnel())
    }
    return merged
}

/** The tunnel list without [id] — what `SshTunnelHost.forget` leaves behind. */
fun sshTunnelsWithout(tunnels: List<SshTunnel>, id: String): List<SshTunnel> =
    tunnels.filterNot { it.config.id == id }

/**
 * Whether the delete releases something first: a live forward or an in-flight
 * dial — the interactive [SshStage.AUTHENTICATION] challenge included — is
 * disconnected before the entry is dropped, while a dead row (FAILED /
 * DISCONNECTED / INCOMPATIBLE) has nothing left to release. Shapes the
 * confirm's second line only; it never gates the entry point.
 */
fun sshForgetReleasesTunnel(stage: SshStage): Boolean = when (stage) {
    SshStage.READY,
    SshStage.CONNECTING,
    SshStage.CHECKING,
    SshStage.DOWNLOADING,
    SshStage.UPLOADING,
    SshStage.STARTING,
    SshStage.AUTHENTICATION,
    -> true

    SshStage.DISCONNECTED,
    SshStage.INCOMPATIBLE,
    SshStage.FAILED,
    -> false
}

/**
 * The SSH row's delete confirmation, drawn **inside the card** it belongs to
 * (the panel's scrolling list is the card's own parent, so an in-flow block is
 * the one surface that cannot be clipped by the panel's box).
 *
 * Reuses the shell's existing danger vocabulary: `oc_common_cancel` cancels and
 * `oc_delete_session` (the same 删除 the Home/session delete dialogs use) is the
 * danger action — only its tap reaches [SshForgetState.confirm].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SshTunnelForgetConfirm(
    name: String,
    releaseFirst: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    val shape = RoundedCornerShape(6.dp)
    // v98 D3 — the bar is drawn in-flow *below* its row, but the panel's body
    // owns no scroller of its own (`ServersPanel`'s single outer
    // `Column.verticalScroll` is the only one), so a row low in the list put
    // the whole bar past the fold: on `emulator-5554` the body viewport ends
    // at y≈1937 and the second SSH row's ⋮ already sits at y 1827-1953, so
    // the bar composed at y≈1965 — off-screen, and pruned from the a11y tree
    // (`research/v97_verify/5554_v98ssh/07_D3.txt`: 5/5 attempts, the next
    // list row reporting as a 3 px sliver). Arming scrolls itself into view,
    // so the bar and both its buttons are reachable wherever the row sits.
    // The request is a no-op without a scrollable ancestor.
    val reveal = remember { BringIntoViewRequester() }
    LaunchedEffect(Unit) { runCatching { reveal.bringIntoView() } }
    Column(
        modifier
            .bringIntoViewRequester(reveal)
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .background(c.layer01, shape)
            .border(0.5.dp, c.borderMuted, shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.oc_ssh_delete_title),
            color = c.textBase,
            fontSize = 13.sp,
            fontWeight = FontWeight(530),
        )
        OcBodyText(
            text = stringResource(R.string.oc_ssh_delete_message, name),
            muted = true,
            size = 11.sp,
        )
        if (releaseFirst) {
            OcBodyText(
                text = stringResource(R.string.oc_ssh_delete_release_hint),
                muted = true,
                size = 11.sp,
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OcButton(
                label = stringResource(R.string.oc_common_cancel),
                primary = false,
                onClick = onCancel,
            )
            Spacer(Modifier.width(8.dp))
            // button-v2 metrics (h 28→32, radius 6, padding-x 14, 13/500) in the
            // danger fill the Home delete dialog's action carries, so the row's
            // delete reads the same as every other destructive confirm.
            Box(
                Modifier
                    .height(32.dp)
                    .background(c.stateDangerFg, shape)
                    .clickable(role = Role.Button, onClick = onConfirm)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.oc_delete_session),
                    color = c.textInverse,
                    fontSize = 13.sp,
                    fontWeight = FontWeight(500),
                )
            }
        }
    }
}
