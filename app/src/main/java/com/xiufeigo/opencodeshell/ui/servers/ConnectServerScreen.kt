package com.xiufeigo.opencodeshell.ui.servers

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ServerConfigStore
import com.xiufeigo.opencodeshell.ui.settings.normalizeUrlInput
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Wave 5-E1 — the first-run connect page, `ConnectServerScreen`
 * (`_upstream/packages/app/src/servers/connect/screen.tsx:18-137`) rendered as
 * the **whole screen** the way `routes.tsx:79` does
 * (`fallback={<ConnectServerScreen />}` while `servers.list.length === 0`).
 *
 * Layout is `screen.css`: a 360 dp-max column, `gap:16px`, centred with
 * `margin:auto` inside a scrolling page padded `48/24/32`; brand slot, centred
 * `h1` 20/28 `530` + `p` 13 muted, then the two labelled fields (`gap:8px`,
 * 44 dp tall, 16 px text) with a 20 px form gap, the error line in
 * `state-fg-danger`, the full-width 44 dp primary button, and the footer with
 * the `opencode pair` code block.
 *
 * **Scan QR code** (`screen.tsx:138-157`; gated `platform.platform === "web"`
 * upstream — this shell stands in for that platform): the button hands the
 * scan to a system scanner app via [QrScan], and a result fills the form and
 * submits exactly like upstream's `onScan` (`screen.tsx:75-84`). Without a
 * scanner app the button reports [R.string.oc_connect_scan_unavailable], the
 * analog of upstream's `server.connect.camera.unavailable` note. The in-app
 * camera preview (`servers/connect/scanner.tsx`) stays TODO — it needs a
 * QR/camera library.
 *
 * On success the address is written through [ServerConfigStore.replaceAll] and
 * made active, which bumps the store revision and re-evaluates the gate in
 * `ui/App.kt` — the page replaces itself with the shell.
 *
 * The address field opens prefilled ([prefillUrl]) when the store already holds
 * a password-less server: a fresh install that synced a bare URL then only
 * asks for the Basic-auth password instead of a retyped address. With no such
 * entry the form starts blank, exactly as before.
 */
@Composable
fun ConnectServerScreen(
    store: ServerConfigStore,
    modifier: Modifier = Modifier,
    /** Called after the server was persisted (the caller may animate/scroll). */
    onConnected: () -> Unit = {},
) {
    val c = OcTheme.colors
    val scope = rememberCoroutineScope()
    val probe = remember { ServerHealthProbe() }

    var url by remember { mutableStateOf(prefillUrl(store.listServers()) ?: "") }
    var password by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<HealthMessage?>(null) }
    var scanUnavailable by remember { mutableStateOf(false) }

    fun submit() {
        if (checking) return
        error = null
        val address = serverAddress(url)
        if (address == null) {
            // `server.connect.address.invalid` (`screen.tsx:35`).
            error = HealthMessage(R.string.oc_connect_address_invalid)
            return
        }
        checking = true
        scope.launch {
            // The probe blocks (≤3 s); the store write deliberately stays on the
            // caller's dispatcher because `ServerConfigStore.onChanged` may push
            // into the WebView.
            val result = withContext(Dispatchers.IO) {
                probe.check(HealthTarget(url = address, username = null, password = password))
            }
            checking = false
            if (!result.healthy) {
                // `server.connect.failed` (`screen.tsx:25-30,40-43`): never
                // silent, never an exception.
                error = healthFailureMessage(result.failure)
                return@launch
            }
            store.replaceAll(
                servers = withConnectedServer(store.listServers(), address, password),
                activeUrl = address,
            )
            onConnected()
        }
    }

    val scanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val payload = QrScan.resultFrom(result.data) ?: return@rememberLauncherForActivityResult
        val pairing = QrScan.pairingFromResult(payload)
        if (pairing == null) {
            // `server.connect.scan.invalid` (`scanner.tsx`).
            error = HealthMessage(R.string.oc_pair_invalid)
            return@rememberLauncherForActivityResult
        }
        // Upstream `onScan` (`screen.tsx:75-84`): fill the form, then connect.
        url = pairing.urls.first()
        password = pairing.password
        scanUnavailable = false
        submit()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(c.bgBase)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(PaddingValues(start = 24.dp, end = 24.dp, top = 48.dp, bottom = 32.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = CONNECT_CONTENT_WIDTH).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // `.server-connect-brand` + `Wordmark` (`screen.tsx:52-54`, 160px).
            // The shell ships no logo asset (`NewSessionScreen.kt:156-164` made
            // the same call), so the brand is the product name itself — a name,
            // not translatable copy.
            Text(
                text = "opencode",
                color = c.textBase,
                fontSize = 24.sp,
                fontWeight = FontWeight(530),
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 16.dp),
            )
            Column(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.oc_connect_title),
                    color = c.textBase,
                    fontSize = 20.sp,
                    lineHeight = 28.sp,
                    fontWeight = FontWeight(530),
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.oc_connect_description),
                    color = c.textMuted,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight(440),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                ConnectField(
                    label = stringResource(R.string.oc_connect_url_label),
                    value = url,
                    onValueChange = {
                        // Same CJK-IME guard as the add-server dialog: Gboard zh
                        // commits full-width `:`/`/`/`.`, which no console accepts.
                        url = normalizeUrlInput(it)
                        error = null
                    },
                    placeholder = stringResource(R.string.oc_connect_url_placeholder),
                    enabled = !checking,
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                )
                ConnectField(
                    label = stringResource(R.string.oc_connect_password_label),
                    value = password,
                    onValueChange = {
                        password = it
                        error = null
                    },
                    placeholder = null,
                    enabled = !checking,
                    password = true,
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Go,
                    onImeAction = { submit() },
                )
            }

            error?.let { message ->
                // `role="alert"` (`screen.tsx:130`) → an assertive live region,
                // so a screen reader announces the failure without a refocus.
                Text(
                    text = message.formatArg
                        ?.let { stringResource(message.resId, it) }
                        ?: stringResource(message.resId),
                    color = c.stateDangerFg,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight(440),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { liveRegion = LiveRegionMode.Assertive },
                )
            }

            ConnectButton(
                label = stringResource(
                    if (checking) R.string.oc_connect_checking else R.string.oc_connect_button,
                ),
                // `disabled={request.isPending || !state.url.trim()}`
                // (`screen.tsx:134`).
                enabled = !checking && url.isNotBlank(),
                onClick = { submit() },
            )

            // `screen.tsx:138-157`: the neutral `server.connect.scan` button
            // (web-only upstream) and the availability note under it.
            ConnectButton(
                label = stringResource(R.string.oc_pair_scan),
                // `disabled={request.isPending || !camera.available.latest}`
                // (`screen.tsx:143`) — availability is discovered by launching,
                // so the button stays enabled and reports on failure instead.
                enabled = !checking,
                primary = false,
                onClick = {
                    try {
                        scanLauncher.launch(QrScan.scanIntent())
                    } catch (_: ActivityNotFoundException) {
                        scanUnavailable = true
                    }
                },
            )
            if (scanUnavailable) {
                Text(
                    text = stringResource(R.string.oc_connect_scan_unavailable),
                    color = c.textMuted,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight(440),
                )
            }

            // `<footer>` (`screen.tsx:158-161`): the pairing hint and the literal
            // command — a code token, never translated.
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.oc_connect_pair_description),
                    color = c.textMuted,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight(440),
                    textAlign = TextAlign.Center,
                )
                Box(
                    Modifier
                        .padding(top = 12.dp)
                        .background(c.layer01, RoundedCornerShape(6.dp))
                        .border(0.5.dp, c.borderBase, RoundedCornerShape(6.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = "opencode pair",
                        color = c.textBase,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight(440),
                    )
                }
            }
        }
    }
}

/**
 * One `.server-connect-field`: a `13px/530` label plus a 44 dp input
 * (`screen.css:50-70`).
 *
 * Deliberately not `ui.settings.OcTextField`, which is pinned to the settings
 * rows' 36 dp/13 px `TextInputV2` size.
 */
@Composable
private fun ConnectField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String?,
    enabled: Boolean,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: (() -> Unit)? = null,
) {
    val c = OcTheme.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = c.textBase, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight(530))
        Box(
            Modifier
                .fillMaxWidth()
                .height(CONNECT_FIELD_HEIGHT)
                .background(c.layer02.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                .border(0.5.dp, c.borderBase, RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty() && placeholder != null) {
                Text(placeholder, color = c.textFaint, fontSize = 16.sp, maxLines = 1)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                textStyle = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, color = c.textBase),
                cursorBrush = SolidColor(c.textAccent),
                visualTransformation =
                if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
                keyboardActions = KeyboardActions(
                    onGo = { onImeAction?.invoke() },
                    onDone = { onImeAction?.invoke() },
                    onSend = { onImeAction?.invoke() },
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** `variant="contrast"` (primary) or `variant="neutral"` button: full width, ≥44 dp (`screen.css:72-75`). */
@Composable
private fun ConnectButton(
    label: String,
    enabled: Boolean,
    primary: Boolean = true,
    onClick: () -> Unit,
) {
    val c = OcTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = CONNECT_FIELD_HEIGHT)
            .background(
                when {
                    !primary -> androidx.compose.ui.graphics.Color.Transparent
                    enabled -> c.accent
                    else -> c.layer03
                },
                RoundedCornerShape(6.dp),
            )
            .then(
                if (primary) Modifier else Modifier.border(0.5.dp, c.borderBase, RoundedCornerShape(6.dp)),
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = when {
                !primary -> c.textBase
                enabled -> c.textInverse
                else -> c.textFaint
            },
            fontSize = 15.sp,
            fontWeight = FontWeight(530),
        )
    }
}

/** `.server-connect-content { width: min(100%, 360px) }`. */
private val CONNECT_CONTENT_WIDTH = 360.dp

/** `height: 44px` for both the inputs and the button. */
private val CONNECT_FIELD_HEIGHT = 44.dp
