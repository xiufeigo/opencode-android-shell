package com.xiufeigo.opencodeshell.ui.session.header

import androidx.compose.runtime.compositionLocalOf
import com.xiufeigo.opencodeshell.ui.servers.SshTunnel
import com.xiufeigo.opencodeshell.ui.servers.SshTunnelHost
import com.xiufeigo.opencodeshell.ui.servers.sshReadyLocalUrl

/**
 * Session-route handle on the app-owned [SshTunnelHost] (`ui/App.kt` builds it
 * next to `SessionActionsHost` / `SessionBtwHost` and publishes it on the
 * session route; `SessionScreen` consumes it). Null outside the session route.
 */
val LocalSshTunnelHost = compositionLocalOf<SshTunnelHost?> { null }

/**
 * The tunnel the session route falls back to `SshTunnelPanel` for (upstream
 * `connection-panel.tsx`: the full-screen reconnect surface while the SSH
 * connection is inactive). Null unless the active server is SSH-backed **and**
 * its tunnel is not ready.
 *
 * The link is the forwarded loopback url: a ready tunnel's
 * `status.localUrl`, else the last one seen for that config
 * (`reduceSshConnection` clears `localUrl` on disconnect/fail, so the caller
 * remembers it). A server no tunnel ever forwarded to is not SSH-backed and
 * never matches.
 */
fun sshPanelTunnel(
    tunnels: List<SshTunnel>,
    serverUrl: String?,
    lastLocalUrls: Map<String, String> = emptyMap(),
): SshTunnel? {
    if (serverUrl.isNullOrBlank()) return null
    val linked = tunnels.firstOrNull { tunnel ->
        tunnel.status.localUrl == serverUrl || lastLocalUrls[tunnel.config.id] == serverUrl
    } ?: return null
    return linked.takeIf { sshReadyLocalUrl(it.status) == null }
}
