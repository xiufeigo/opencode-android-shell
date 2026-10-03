package com.xiufeigo.opencodeshell

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiufeigo.opencodeshell.notify.NotificationNavigation
import com.xiufeigo.opencodeshell.notify.NotificationPrefs
import com.xiufeigo.opencodeshell.notify.SystemNotificationPolicy
import com.xiufeigo.opencodeshell.notify.SystemNotifier
import com.xiufeigo.opencodeshell.ui.OcApp
import com.xiufeigo.opencodeshell.ui.nav.AppearancePrefs
import com.xiufeigo.opencodeshell.ui.nav.LastRouteStore
import com.xiufeigo.opencodeshell.ui.nav.NavRestore
import com.xiufeigo.opencodeshell.ui.theme.OcSystemBars
import kotlinx.coroutines.awaitCancellation

/**
 * The launcher: the app boots straight into the native Compose frontend
 * (`OcApp`), and [MainActivity] — the View/WebView shell — stays behind only as
 * the WebView-terminal fallback.
 *
 * M1: the Activity owns the [ChatViewModel] (Compose remembers it across
 * recompositions, `DisposableEffect` tears the SSE stream down when the
 * composition leaves). The session screen itself starts/stops the stream, so
 * sitting on Home costs no connection.
 *
 * B-2/B-3/B-4 (notifications): the activity is also where the notification
 * pipeline's Android halves are created — [NotificationPrefs] (the feed's disk
 * home), [SystemNotifier] (channel + `NotificationManager` + sound), the
 * `POST_NOTIFICATIONS` prompt, and the launch-intent read that turns a tapped
 * system notification into [NotificationNavigation].
 */
class OcActivity : AppCompatActivity() {
    /**
     * The live [SystemNotifier], for the `onWindowFocusChanged` forward below.
     * Assigned where the notifier is remembered (once per composition
     * creation); cleared with it, so a stale instance never receives focus.
     */
    private var systemNotifier: SystemNotifier? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A tapped system notification starts this activity with the session id
        // (`SystemNotifier`'s PendingIntent). The nav graph lives inside `OcApp`,
        // so the id goes through the process-wide channel the scaffold collects —
        // offered *before* the first composition, so a cold start lands on the
        // session instead of the last route.
        NotificationNavigation.request(intent?.getStringExtra(SystemNotificationPolicy.EXTRA_SESSION_ID))
        enableEdgeToEdge()
        setContent {
            // M5: the same config store feeds the chat ViewModel *and* the
            // terminal panel (`PtyApi` needs the server URL + Basic auth), so it
            // is created once here instead of inside the ViewModel.
            val serverStore = remember { ServerConfigStore(applicationContext).also { it.restore() } }
            // One SettingsStore for the theme/nav state *and* the chat
            // ViewModel's model-picker override (`opencode.android.selectedModel`).
            val settingsStore = remember { SettingsStore(applicationContext) }
            // B-2/B-3/B-4: the feed's disk home and the system-notification half.
            // Both read the live settings (`sound*` are per-kind and can change
            // while the app runs), so they are wired to the store, not to a
            // snapshot.
            val notificationPrefs = remember { NotificationPrefs(applicationContext) }
            val notifications = remember {
                // `owner` seeds the foreground state from this activity's
                // lifecycle: the composition runs after the first
                // `onActivityResumed`, so the callback alone would see a
                // backgrounded app all launch long (`SystemNotifier`'s class doc).
                SystemNotifier(
                    applicationContext,
                    settings = { settingsStore.state.value },
                    owner = this@OcActivity,
                ).also { systemNotifier = it }
            }
            val chatViewModel = remember {
                ChatViewModel(
                    serverStore,
                    settingsStore,
                    notificationPersistence = notificationPrefs,
                    notificationDelivery = notifications,
                ) { /* screen shows an inline setup hint */ }
            }
            DisposableEffect(chatViewModel, notifications) {
                onDispose {
                    chatViewModel.destroy()
                    notifications.close()
                    if (systemNotifier === notifications) systemNotifier = null
                }
            }
            // Flap recovery: a network return re-dials the streams / re-probes
            // through ChatViewModel.onNetworkAvailable.
            //
            // v97 ④-b (`research/v97_fix/STARTUP_DEFER_FIX.md`): this used to be a
            // `remember` + `DisposableEffect` pair, and both halves run inside the
            // *first* composition — a `remember` initializer is part of composition
            // and a `DisposableEffect` block is applied before the frame is drawn.
            // `start()` registers a default-network callback, i.e. a **binder call
            // into the connectivity service**, so it was on the critical path ahead of
            // the first frame for a recovery that can never be needed that early.
            //
            // A `LaunchedEffect` body is *dispatched* after the composition is
            // applied, so the same `start()` now lands after the first frame. The
            // effect owns both halves (`awaitCancellation` + `finally`), which is
            // what keeps the pairing leak-proof: a leave can no longer run `stop()`
            // against a watch whose `start()` never happened, and one that happens
            // mid-registration is still stopped. The keys are the two stable values
            // it captures — an unstable key would cancel and re-register the callback
            // on every recomposition, which is the behaviour change to avoid.
            LaunchedEffect(applicationContext, chatViewModel) {
                val networkWatch =
                    NetworkRecoveryWatch(applicationContext) { chatViewModel.onNetworkAvailable() }
                networkWatch.start()
                try {
                    awaitCancellation()
                } finally {
                    networkWatch.stop()
                }
            }
            // B-2: ask for `POST_NOTIFICATIONS` while the app is in the foreground.
            NotificationPermissionRequest(settingsStore)
            // v40 tD_20_dark: the Compose surface follows `opencode-color-scheme`,
            // not the platform night flag, so the status bar icon appearance
            // must follow the same `Settings.isDark` resolution — `enableEdgeToEdge`
            // leaves `isAppearanceLightStatusBars` where the theme started it.
            SystemBarIconAppearance(settingsStore)
            // M3: last-page restore + the optional bottom title bar. Both are read
            // once per launch (SharedPreferences), which is exactly how often they
            // are meant to change: `appearance.tabLayout` is written by the M4
            // settings panel and applied on the next start.
            val lastRoute = remember { LastRouteStore(applicationContext) }
            val appearance = remember { AppearancePrefs(applicationContext) }
            val serverKey = remember(serverStore.server) {
                NavRestore.serverKeyOf(serverStore.server?.url)
            }
            // `isDark` is intentionally not passed: the manifest declares
            // `configChanges=uiMode`, so this Activity is not recreated on a
            // dark⇄light switch and a value captured here would freeze the theme.
            // `OcApp` reads the live configuration instead.
            OcApp(
                chatViewModel = chatViewModel,
                settingsStore = settingsStore,
                serverStore = serverStore,
                serverKey = serverKey,
                lastRoute = lastRoute,
                bottomTabStrip = appearance.bottomTabStrip(),
            )
        }
    }

    /**
     * B-2: a tapped notification while the app is alive. The `PendingIntent` is
     * built with `SINGLE_TOP | CLEAR_TOP` and the manifest declares
     * `launchMode="singleTop"`, so the running instance is reused — the
     * [ChatViewModel] (and its SSE stream) survives instead of a cold start.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        NotificationNavigation.request(intent.getStringExtra(SystemNotificationPolicy.EXTRA_SESSION_ID))
    }

    /**
     * B-2 (v68 5554): forwards the window focus to [SystemNotifier]. The
     * lifecycle callbacks cannot see a freeform window being covered — the
     * activity stays resumed under multi-resume — so without this the delivery
     * gate reads foreground for a user who cannot see the content, and a turn
     * that finishes there is never posted.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        systemNotifier?.onWindowFocusChanged(hasFocus)
    }
}

/**
 * v40 tD_20_dark: keeps the system status bar icons readable across the
 * Appearance → Color scheme row.
 *
 * The app is edge-to-edge, so the status bar shows the Compose surface behind
 * it: dark content needs light (white) icons, light content dark icons. The
 * `Settings.isDark(systemDark)` resolution already encodes the `system|light|
 * dark` row (`oc-theme-preload.js` semantics); this effect applies its live
 * value to the window controller, including a runtime switch from the settings
 * dialog (the manifest declares `configChanges=uiMode`, so the activity is not
 * recreated on a system dark⇄light flip either — the value must be observed,
 * never captured).
 */
@Composable
private fun SystemBarIconAppearance(settingsStore: SettingsStore) {
    val systemDark = isSystemInDarkTheme()
    val settings by settingsStore.state.collectAsStateWithLifecycle()
    val dark = settings.isDark(systemDark)
    val view = LocalView.current
    SideEffect {
        val window = view.context.findActivityWindow() ?: return@SideEffect
        WindowInsetsControllerCompat(window, view).isAppearanceLightStatusBars =
            OcSystemBars.lightIconsForDarkContent(dark)
    }
}

private fun android.content.Context.findActivityWindow(): android.view.Window? {
    var ctx: android.content.Context? = this
    while (ctx != null) {
        if (ctx is android.app.Activity) return ctx.window
        ctx = (ctx as? android.content.ContextWrapper)?.baseContext
    }
    return null
}

/**
 * B-2: the runtime `POST_NOTIFICATIONS` prompt (API 33+).
 *
 * Asked on the first composition — not at the first notification — because the
 * interesting notification is the one raised while the app is **backgrounded**,
 * and Android can only show a permission dialog from a foreground activity
 * (upstream's lazy `Notification.requestPermission()` in `web.ts:23-27` has no
 * Android equivalent). The three notify switches gate it, so a user who turned
 * every channel off is never asked for a permission the app would not use; the
 * system itself shows the dialog at most twice, so a denial is not re-asked.
 */
@Composable
private fun NotificationPermissionRequest(settingsStore: SettingsStore) {
    val context = LocalContext.current
    val settings by settingsStore.state.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val enabled = SystemNotificationPolicy.anyChannelEnabled(
        settings.notifyAgent,
        settings.notifyPermissions,
        settings.notifyErrors,
    )
    LaunchedEffect(enabled) {
        val granted = Build.VERSION.SDK_INT < SystemNotificationPolicy.PERMISSION_SDK ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        val ask = SystemNotificationPolicy.shouldRequestPermission(Build.VERSION.SDK_INT, granted, enabled)
        Log.d(
            SystemNotifier.TAG,
            "permission: sdk=${Build.VERSION.SDK_INT} granted=$granted channelsEnabled=$enabled ask=$ask",
        )
        if (ask) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
