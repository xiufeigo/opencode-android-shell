package com.xiufeigo.opencodeshell.ui.session.browser

import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.theme.OcTheme

/**
 * B-18: the `browser.reload` request (`session/browser/pane.tsx:45-50`) as an
 * ambient counter.
 *
 * The pane's `(url, onClose, onOpenExternal)` wiring contract is pinned
 * (`SessionBrowserPaneTest.paneKeepsTheWiringContractSignature`), so the command
 * reaches the `WebView` the same way `SessionCommandIntents` reaches
 * screen-local state: `SessionScreen` provides the value, a **newer** value
 * (greater than the one the pane mounted with) reloads. `0` = nothing pending,
 * which is also what a preview or an isolated mount sees.
 */
val LocalBrowserReloadRequest = compositionLocalOf { 0 }

/**
 * Embedded browser pane, the Android stand-in for upstream
 * `packages/app/src/session/browser/pane.tsx` (`SessionBrowserPane`, v2 head).
 *
 * Upstream renders the agent-driven desktop browser through a native Electron
 * cutout positioned over [a `surface` div][pane-measure] and driven over RPC
 * (`back`/`forward`/`reload`/`stop`/`navigate`, tab inventory mirrored into
 * the session layout, `suspended` state): none of that exists on Android, so
 * this pane hosts a local `android.webkit.WebView` (zero new dependencies — no
 * Custom Tabs helper library) over the single agent-driven [url]. Upstream's
 * command-palette `browser.reload` does have a face here — B-18 forwards it to
 * this pane's own reload control (see [LocalBrowserReloadRequest]).
 *
 * Toolbar mirrors `pane.tsx:187-283` (back / forward / reload-stop / address
 * with scheme tint / error banner slot): [Close][‹][›][reload|stop][address +
 * security][open externally]. The error banner slot becomes a full-body error
 * state with retry because `WebView.reload()` is local.
 *
 * Intentional deviations from `pane.tsx` (also noted on [SessionBrowserUrls]):
 * - not a side-panel tab: upstream's browser is a tab in the session layout
 *   (`tabs.open`, tab inventory); here it is one full-body overlay owned by
 *   `SessionScreen`, and B-18's `browser.open` / `browser.reload` reach it as
 *   commands instead of tab operations (tab-ization deliberately not done);
 * - address display is read-only: this contract exposes no `onNavigate`, so
 *   in-page link taps navigate inside the `WebView` and refresh the display
 *   (`doUpdateVisitedHistory`) instead of the upstream editable-submit flow;
 * - added `https`/`http` security indicator (upstream shows none; the desktop
 *   chrome owns it);
 * - no `suspended` state and no dialog-overlap hiding (desktop-only concepts);
 * - only main-frame errors flip to [BrowserLoadState.ERROR]; subresource
 *   failures leave the page alone (`isForMainFrame` gate);
 * - `http` follows the platform `WebView` default (no manifest change allowed)
 *   and surfaces cleartext blocks as the error state.
 *
 * Lifecycle: the `WebView` is created when the pane enters composition and
 * `destroy()`ed when it leaves it (removed from its parent first). Back-key /
 * dismiss routing stays with the wiring owner via [onClose]; this pane never
 * intercepts it.
 *
 * B-18: `browser.reload` (`pane.tsx:45-50`, `f5`) arrives through
 * [LocalBrowserReloadRequest] rather than a fourth parameter — the
 * three-argument wiring contract is pinned by
 * `SessionBrowserPaneTest.paneKeepsTheWiringContractSignature`. A newer value
 * runs the same `reload()` the toolbar's reload button does.
 *
 * **Safe area (v30 BUG-1).** The pane is an opaque full-body overlay, so it
 * consumes `WindowInsets.safeDrawing` itself (status bars / navigation bars /
 * cutout / IME) — see the root `Column` below. Before that the toolbar drew
 * from the window's top edge, Close landed inside the status bar's touch region
 * and the panel became a dead end (unclosable, covering the session). The
 * wiring owner additionally owns a `BackHandler` while the pane is up
 * (`SessionScreen`), so BACK / the back gesture closes it even if insets ever
 * go wrong again.
 */
@Composable
fun SessionBrowserPane(url: String, onClose: () -> Unit, onOpenExternal: () -> Unit) {
    val c = OcTheme.colors
    val normalized = remember(url) { SessionBrowserUrls.normalize(url) }
    val supported = remember(normalized) { SessionBrowserUrls.isSupported(normalized) }
    val state = remember(url) { SessionBrowserState(url) }
    var phase by remember(url) { mutableStateOf(state.loadState) }
    var currentUrl by remember(url) { mutableStateOf(normalized) }
    var canGoBack by remember(url) { mutableStateOf(false) }
    var canGoForward by remember(url) { mutableStateOf(false) }
    var lastLoaded by remember(url) {
        mutableStateOf(if (normalized.isNotEmpty() && supported) normalized else "")
    }
    var webViewRef by remember(url) { mutableStateOf<WebView?>(null) }
    val displayHost = remember(currentUrl) { SessionBrowserUrls.displayHost(currentUrl) }
    val secure = remember(currentUrl) { SessionBrowserUrls.isSecure(currentUrl) }

    fun syncFrom(view: WebView, visibleUrl: String?) {
        canGoBack = view.canGoBack()
        canGoForward = view.canGoForward()
        val next = SessionBrowserUrls.normalize(visibleUrl.orEmpty())
        if (next.isNotEmpty()) currentUrl = next
    }

    // B-18: the `browser.reload` command. `lastReloadRequest` is seeded from the
    // ambient value per URL, so a counter left over from a previous mount (or a
    // request that landed before the pane composed) never reloads on entry —
    // the same guard `SessionScreen` uses for its own command channel. One frame
    // of grace covers the case where the request lands while the `AndroidView`
    // factory has not built the `WebView` yet.
    val reloadRequest = LocalBrowserReloadRequest.current
    var lastReloadRequest by remember(url) { mutableStateOf(reloadRequest) }
    LaunchedEffect(reloadRequest) {
        if (reloadRequest <= lastReloadRequest) return@LaunchedEffect
        lastReloadRequest = reloadRequest
        withFrameNanos { }
        val view = webViewRef ?: return@LaunchedEffect
        state.onStart()
        view.reload()
        phase = state.loadState
        syncFrom(view, view.url)
    }

    DisposableEffect(url) {
        onDispose {
            webViewRef?.let { view ->
                view.stopLoading()
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
            }
            webViewRef = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.bgBase)
            // The pane is a full-body overlay painted from `y = 0` (its host
            // `Box` in `SessionScreen` has no insets of its own), so the system
            // bars have to be cleared *here*: without this the toolbar — Close
            // included — sat under the status bar, where the system's own
            // gesture region swallows the taps and the pane could not be
            // closed at all (device repro: `research/v30_verify/tB_ui_spot/
            // tB_18c_browser_open.png`, Close bounds `[11,11][144,115]`).
            //
            // `background` is applied before the padding on purpose: the node
            // keeps the full window and paints `bg-base` behind the bars (the
            // pane is opaque and owns the screen), only its *content* is
            // inset. `safeDrawing` = status bars + navigation bars + display
            // cutout + IME, so an in-page text field's keyboard shrinks the
            // `WebView` instead of covering it.
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(c.bgBase)
                .border(0.5.dp, c.borderMuted)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BrowserBarButton(
                label = stringResource(R.string.oc_browser_close),
                enabled = true,
                onClick = onClose,
            )
            BrowserBarButton(
                label = "‹",
                enabled = canGoBack,
                contentDescription = stringResource(R.string.oc_browser_go_back),
                onClick = { webViewRef?.goBack() },
            )
            BrowserBarButton(
                label = "›",
                enabled = canGoForward,
                contentDescription = stringResource(R.string.oc_browser_go_forward),
                onClick = { webViewRef?.goForward() },
            )
            val loading = phase == BrowserLoadState.LOADING
            BrowserBarButton(
                label = stringResource(
                    if (loading) R.string.oc_browser_stop else R.string.oc_browser_reload,
                ),
                enabled = normalized.isNotEmpty() && supported,
                onClick = {
                    val view = webViewRef ?: return@BrowserBarButton
                    if (loading) {
                        view.stopLoading()
                        state.onFinished()
                    } else {
                        state.onStart()
                        view.reload()
                    }
                    phase = state.loadState
                    syncFrom(view, view.url)
                },
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp)
                    .semantics {
                        contentDescription = currentUrl.ifEmpty { normalized }
                    },
            ) {
                Text(
                    text = stringResource(
                        if (secure) R.string.oc_browser_secure else R.string.oc_browser_insecure,
                    ),
                    color = if (secure) c.textFaint else c.stateWarningFg,
                    fontSize = 10.sp,
                    fontWeight = FontWeight(530),
                    lineHeight = 14.sp,
                    maxLines = 1,
                )
                Text(
                    text = displayHost.ifEmpty {
                        stringResource(R.string.oc_browser_address)
                    },
                    color = c.textBase,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BrowserBarButton(
                label = stringResource(R.string.oc_browser_open_external),
                enabled = normalized.isNotEmpty(),
                onClick = onOpenExternal,
            )
        }
        if (phase == BrowserLoadState.LOADING) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = c.textAccent,
                trackColor = c.borderMuted,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(c.bgBase),
            contentAlignment = Alignment.Center,
        ) {
            if (normalized.isEmpty() || !supported) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.oc_browser_empty_title),
                        color = c.textBase,
                        fontSize = 13.sp,
                        fontWeight = FontWeight(530),
                        lineHeight = 20.sp,
                    )
                    Text(
                        text = stringResource(R.string.oc_browser_empty_description),
                        color = c.textMuted,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                    )
                }
            } else {
                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(
                                    view: WebView,
                                    url: String,
                                    favicon: Bitmap?,
                                ) {
                                    state.onStart()
                                    phase = state.loadState
                                    syncFrom(view, url)
                                }

                                override fun onPageFinished(view: WebView, url: String) {
                                    state.onFinished()
                                    phase = state.loadState
                                    syncFrom(view, url)
                                }

                                override fun onReceivedError(
                                    view: WebView,
                                    request: WebResourceRequest,
                                    error: WebResourceError,
                                ) {
                                    if (request.isForMainFrame) {
                                        state.onError()
                                        phase = state.loadState
                                    }
                                    syncFrom(view, view.url)
                                }
                            }
                            loadUrl(normalized)
                            webViewRef = this
                        }
                    },
                    update = { view ->
                        if (normalized != lastLoaded) {
                            lastLoaded = normalized
                            currentUrl = normalized
                            state.onStart()
                            phase = state.loadState
                            view.loadUrl(normalized)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                if (phase == BrowserLoadState.ERROR) {
                    val retryLabel = stringResource(R.string.oc_browser_retry)
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(c.bgBase)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.oc_browser_failed_title),
                            color = c.textBase,
                            fontSize = 13.sp,
                            fontWeight = FontWeight(530),
                            lineHeight = 20.sp,
                        )
                        Text(
                            text = stringResource(R.string.oc_browser_failed_description),
                            color = c.textMuted,
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                        val retryShape = RoundedCornerShape(4.dp)
                        Box(
                            modifier = Modifier
                                .height(28.dp)
                                .clip(retryShape)
                                .background(c.inverse)
                                .clickable(
                                    role = Role.Button,
                                    onClick = {
                                        state.onRetry()
                                        phase = state.loadState
                                        webViewRef?.reload()
                                    },
                                )
                                .padding(horizontal = 12.dp)
                                .semantics { contentDescription = retryLabel },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.oc_browser_retry),
                                color = c.textInverse,
                                fontSize = 13.sp,
                                fontWeight = FontWeight(530),
                                lineHeight = 20.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowserBarButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    contentDescription: String = label,
) {
    val c = OcTheme.colors
    Box(
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = c.textBase,
            fontSize = 13.sp,
            fontWeight = FontWeight(530),
            lineHeight = 20.sp,
            maxLines = 1,
        )
    }
}
