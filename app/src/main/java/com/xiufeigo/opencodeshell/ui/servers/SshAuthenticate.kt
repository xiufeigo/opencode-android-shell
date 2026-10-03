package com.xiufeigo.opencodeshell.ui.servers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.settings.OcBodyText
import com.xiufeigo.opencodeshell.ui.settings.OcButton
import com.xiufeigo.opencodeshell.ui.settings.OcTextField
import com.xiufeigo.opencodeshell.ui.settings.serverDialogBandDp
import com.xiufeigo.opencodeshell.ui.settings.serverDialogBandLiftDp
import com.xiufeigo.opencodeshell.ui.settings.serverDialogLiftShield
import com.xiufeigo.opencodeshell.ui.theme.OcTheme

/**
 * The interactive half of upstream's `servers/ssh/authenticate.tsx` + the
 * prompt block of `dialog.tsx:221-252`: one [SshPrompt] on screen until the
 * user answers.
 *
 * `confirm == true` is the host-key trust question (`ssh.trust` /
 * `ssh.continue/trust` in `dialog.tsx:244-252`) — shown with the MD5/SHA256
 * fingerprints of [SshHostKeyInfo] instead of OpenSSH's plain-text banner, and
 * answered by `SshConnection.respondTrust`, which is what appends to
 * `known_hosts`. `confirm == false` is the `ssh.continue` challenge (password /
 * passphrase / keyboard-interactive) with a secret field.
 *
 * Band + pinned footer (v92): the same treatment the config form below got in
 * v83…v89. The card used to be one scrolling column, so on a short window with
 * the keyboard up its Cancel / Trust / Continue row sat below the fold of the
 * visible band. The form body is now the scroller and the footer is pinned to
 * the card's bottom edge, outside it. The band comes from the same pure
 * helpers ([serverDialogBandDp] / [serverDialogBandLiftDp]) fed with the same
 * inputs the host feeds the form (window height, the window's own IME bottom,
 * the panel slot's box — see `SshDialog`'s prompt branch); the card measures
 * its own top edge itself, like the form does. With the keyboard down the cap
 * is max-only and the body wraps (`fill = false`), so a card whose content
 * fits is drawn exactly where it was — the band only binds where the old card
 * overflowed, which is the bug being fixed.
 */
@Composable
fun SshAuthenticate(
    prompt: SshPrompt,
    hostKey: SshHostKeyInfo?,
    waiting: Boolean = false,
    onTrust: () -> Unit,
    onContinue: (String) -> Unit,
    onCancel: () -> Unit,
    windowHeightDp: Float,
    imeBottomDp: Dp,
    panelTopDp: Float = -1f,
    panelHeightDp: Float = 0f,
) {
    val c = OcTheme.colors
    val config = LocalConfiguration.current
    val density = LocalDensity.current
    val width = minOf(config.screenWidthDp.dp - 32.dp, 480.dp)
    var response by remember(prompt.id) { mutableStateOf("") }

    // The card's own top edge in the panel's flow, read from the layout (never
    // the lifted one) — the same measurement the config form takes.
    var topInWindowDp by remember { mutableStateOf(0f) }
    val band = serverDialogBandDp(
        windowHeightDp = windowHeightDp,
        imeBottomDp = imeBottomDp.value,
        topInWindowDp = topInWindowDp,
        panelHeightDp = panelHeightDp,
        panelTopDp = panelTopDp,
    ).dp
    // The card's top edge gives way for the band, so the pinned footer below
    // lands on the floor — the same lift the config form applies.
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
            // The flow position, measured outside the lift (see the form card).
            .onGloballyPositioned { coords ->
                topInWindowDp = coords.positionInWindow().y / density.density
            }
            .offset(y = -lift)
            // The shield only while lifted: a lifted card is drawn over the
            // panel's rows and takes the touches that land on none of its own
            // controls, so no covered row can fire under it.
            .then(if (lift > 0.dp) Modifier.serverDialogLiftShield() else Modifier)
            // With the keyboard up and the panel measured the card's box is the
            // fixed band; otherwise the max-only cap is untouched, so a card
            // whose content fits is drawn where the old wrap put it.
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
        // The body gives way (`fill = false`: its own height while the band
        // has room, a scroller once it has not) — except with the keyboard up,
        // where the band *is* the card's box and the body takes all of it
        // above the footer; the footer below never gives way. The paddings
        // reproduce the old single column's box (24 dp top/sides on the body,
        // the 12 dp gap plus 24 dp sides/bottom on the footer), so the
        // keyboard-down geometry is unchanged wherever the cap does not bind.
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
                text = stringResource(
                    if (hostKey != null) R.string.oc_ssh_host_key_title else R.string.ssh_authenticate,
                ),
                color = c.textBase,
                fontSize = 15.sp,
                fontWeight = FontWeight(640),
            )
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.borderBase))

            if (hostKey != null) {
                OcBodyText(hostKey.host, muted = true)
                OcBodyText(hostKey.type, muted = true)
                SshFingerprintRow(stringResource(R.string.oc_ssh_fingerprint_md5), hostKey.md5)
                SshFingerprintRow(stringResource(R.string.oc_ssh_fingerprint_sha256), hostKey.sha256)
                OcBodyText(stringResource(R.string.oc_ssh_host_key_hint), muted = true)
                if (hostKey.changed) {
                    Text(
                        text = stringResource(R.string.ssh_error_host_key),
                        color = c.stateDangerFg,
                        fontSize = 11.sp,
                        fontWeight = FontWeight(440),
                    )
                }
            }
            if (prompt.text.isNotBlank()) {
                Text(
                    text = prompt.text,
                    color = c.textMuted,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (!prompt.confirm) {
                OcTextField(
                    value = response,
                    onValueChange = { response = it },
                    password = true,
                    keyboardType = KeyboardType.Password,
                    enabled = !waiting,
                    onImeAction = { if (response.isNotEmpty()) onContinue(response) },
                )
            }
        }
        // Cancel / Trust / Continue is pinned to the card's bottom edge, so it
        // is inside the band with the keyboard up and inside the panel with it
        // down. Its 12 dp top padding is the gap the single column's
        // `spacedBy(12.dp)` used to leave above the row, so the footer's own
        // box is the same one it has always had.
        Column(
            Modifier.padding(PaddingValues(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 24.dp)),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                OcButton(
                    label = stringResource(R.string.common_cancel),
                    primary = false,
                    enabled = !waiting,
                    onClick = onCancel,
                )
                OcButton(
                    // `dialog.tsx:250`: confirm prompts trust, the rest continue.
                    label = stringResource(if (prompt.confirm) R.string.ssh_trust else R.string.ssh_continue),
                    enabled = !waiting && (prompt.confirm || response.isNotEmpty()),
                    onClick = { if (prompt.confirm) onTrust() else onContinue(response) },
                )
            }
        }
    }
}

@Composable
private fun SshFingerprintRow(label: String, fingerprint: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        OcBodyText(label, muted = true)
        Text(
            text = fingerprint,
            color = OcTheme.colors.textBase,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}
