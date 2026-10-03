package com.xiufeigo.opencodeshell.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.settings.OcButton
import com.xiufeigo.opencodeshell.ui.theme.OcTheme

/**
 * The route-level "this server is not compatible" page — the Compose port of
 * upstream `IncompatibleServerPanel`
 * (`_upstream/packages/app/src/session/incompatible-server-panel.tsx:8-35`),
 * which upstream mounts whenever `activeServer.health?.incompatible` is set
 * (`session/route-error.tsx:26-28`, `session/route.tsx:131`,
 * `new-session/route.tsx:58`).
 *
 * Upstream shape, top to bottom:
 * - a large `warning` icon in `text-icon-warning-base`;
 * - `session.error.incompatible` (16 px / medium / `text`) over
 *   `session.error.incompatible.description` (13 px / `text-weak`), both centred
 *   and capped at `max-w-md`, the description naming the **server** and the
 *   **version** it reported;
 * - one `Button variant="neutral"` labelled `session.error.notFound.closeTab`.
 *
 * Android adaptations, both deliberate and both already established by
 * [SessionNotFoundPanel]:
 * - "close tab" is the nav **back** action (this shell has no tab bar in the web
 *   sense; leaving the destination is what closing the tab does) and carries
 *   「返回」's copy from [R.string.oc_back];
 * - a second action, 「重试」, re-runs the generation probe
 *   (`ChatViewModel.retryProbe`). Upstream needs no retry because its health
 *   probe is polled continuously; here the verdict is latched until the next
 *   round, and upgrading the server (or pointing the shell at another one) is
 *   exactly the situation the user has to be able to recover from without
 *   restarting the app.
 *
 * The version always renders in its own mono chip (the not-found panel's id
 * chip, same tokens), so the panel names what it found even when no server row
 * is available to name — in that case the description sentence is omitted
 * rather than formatted with an empty server slot.
 */
@Composable
fun IncompatibleServerPanel(
    version: String,
    serverLabel: String?,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    val label = remember(serverLabel) { serverLabel?.trim()?.takeIf { it.isNotEmpty() } }
    val reported = remember(version) { version.trim().ifEmpty { "1" } }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(c.bgBase)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_oc_alert),
            contentDescription = null,
            tint = c.stateWarningFg,
            modifier = Modifier.size(32.dp),
        )
        Text(
            text = stringResource(R.string.session_error_incompatible),
            color = c.textBase,
            fontSize = 16.sp,
            fontWeight = FontWeight.W500,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp).widthIn(max = 320.dp),
        )
        if (label != null) {
            Text(
                text = stringResource(R.string.session_error_incompatible_description, label, reported),
                color = c.textMuted,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp).widthIn(max = 320.dp),
            )
        }
        Text(
            text = reported,
            color = c.textBase,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.W500,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(top = 16.dp)
                .widthIn(max = 320.dp)
                .background(c.layer02, RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp, vertical = 2.dp),
        )
        Row(
            modifier = Modifier.padding(top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // `Button variant="neutral" icon="xmark-small"` — the close-tab half,
            // wired to back (see the class doc).
            OcButton(
                label = stringResource(R.string.oc_back),
                primary = false,
                onClick = onBack,
            )
            OcButton(
                label = stringResource(R.string.oc_session_not_found_retry),
                primary = false,
                onClick = onRetry,
            )
        }
    }
}
