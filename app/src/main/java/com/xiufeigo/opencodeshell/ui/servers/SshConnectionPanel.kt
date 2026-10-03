package com.xiufeigo.opencodeshell.ui.servers

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.settings.OcBodyText
import com.xiufeigo.opencodeshell.ui.settings.OcButton
import com.xiufeigo.opencodeshell.ui.theme.OcTheme

/**
 * `servers/ssh/connection-panel.tsx` (v2 head): the full-screen "SSH
 * connection inactive" surface a session route falls back to — lock icon, the
 * connection's display name, the reconnect pitch, the `ssh.error.<literal>`
 * line and the one action button (Reconnect / Authenticate / connecting).
 *
 * **Android extensions** the upstream panel does not carry (and the brief asks
 * for): the current [SshStage] sentence, the latency / last-handshake stats of
 * [SshConnectionStatus], and a `ssh.disconnect` button next to reconnect —
 * `connection-panel.tsx` only reconnects (disconnect/forget live in
 * `menu.tsx`), but the tunnel is app-owned here so the panel is where it is
 * released.
 */
@Composable
fun SshConnectionPanel(
    name: String,
    status: SshConnectionStatus,
    onReconnect: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
    pending: Boolean = false,
    now: Long = System.currentTimeMillis(),
) {
    val c = OcTheme.colors
    val connecting = pending || isSshConnecting(status.stage)
    Column(
        modifier
            .fillMaxSize()
            .background(c.bgBase)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        SshLockIcon(size = 28.dp, tint = c.iconMuted)
        Column(
            Modifier.width(320.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // `connection-panel.tsx:23` — fixed title, name below in `bdi`.
            Text(
                text = stringResource(R.string.ssh_session_disconnected),
                color = c.textBase,
                fontSize = 16.sp,
                fontWeight = FontWeight(530),
                textAlign = TextAlign.Center,
            )
            Text(
                text = name,
                color = c.textMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight(440),
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(sshStageResId(status.stage)),
                color = c.textMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight(440),
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.ssh_session_reconnectDescription),
                color = c.textMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight(440),
                textAlign = TextAlign.Center,
            )
        }
        if (status.latencyMs != null || status.lastHandshakeAt != null) {
            Text(
                text = sshStatsLine(status, now),
                color = c.textFaint,
                fontSize = 11.sp,
                fontWeight = FontWeight(440),
                textAlign = TextAlign.Center,
            )
        }
        status.error?.let { error ->
            Text(
                text = stringResource(sshErrorResId(error)),
                color = c.textMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight(440),
                textAlign = TextAlign.Center,
                modifier = Modifier.width(320.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OcButton(
                label = stringResource(
                    when {
                        // `connection-panel.tsx:37-39`.
                        connecting -> R.string.ssh_session_connecting
                        status.stage == SshStage.AUTHENTICATION -> R.string.ssh_action_authenticate
                        else -> R.string.ssh_session_reconnect
                    },
                ),
                primary = false,
                enabled = !connecting,
                onClick = onReconnect,
            )
            OcButton(
                label = stringResource(R.string.ssh_disconnect),
                enabled = status.stage != SshStage.DISCONNECTED,
                onClick = onDisconnect,
            )
        }
    }
}

/** `Latency: 42 ms · Last handshake: 12s` — stats column of the panel. */
@Composable
private fun sshStatsLine(status: SshConnectionStatus, now: Long): String {
    val latency = stringResource(R.string.oc_ssh_latency_label) + ": " + formatSshLatency(status.latencyMs)
    val handshake = stringResource(R.string.oc_ssh_last_handshake_label) + ": " +
        (status.lastHandshakeAt?.let { formatSshHandshakeAge(now - it) } ?: "—")
    return "$latency · $handshake"
}

/** `Icon name="lock"` (`connection-panel.tsx:18`) — shackle + body. */
@Composable
private fun SshLockIcon(size: Dp, tint: androidx.compose.ui.graphics.Color) {
    Canvas(Modifier.size(size)) {
        val stroke = this.size.minDimension / 9f
        val bodyWidth = this.size.width * 0.62f
        val bodyHeight = this.size.height * 0.42f
        val left = (this.size.width - bodyWidth) / 2f
        val top = this.size.height * 0.42f
        drawRoundRect(
            color = tint,
            topLeft = Offset(left, top),
            size = Size(bodyWidth, bodyHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(stroke),
        )
        val shackleWidth = bodyWidth * 0.56f
        drawArc(
            color = tint,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset((this.size.width - shackleWidth) / 2f, this.size.height * 0.12f),
            size = Size(shackleWidth, this.size.height * 0.42f),
            style = Stroke(width = stroke),
        )
    }
}
