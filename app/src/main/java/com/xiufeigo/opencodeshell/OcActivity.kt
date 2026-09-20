package com.xiufeigo.opencodeshell

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.xiufeigo.opencodeshell.ui.OcApp
import com.xiufeigo.opencodeshell.ui.nav.AppearancePrefs
import com.xiufeigo.opencodeshell.ui.nav.LastRouteStore
import com.xiufeigo.opencodeshell.ui.nav.NavRestore

/**
 * Dev entry for the native Compose frontend (M0 foundation). Kept separate from
 * [MainActivity] (the View/WebView shell) so Compose can be exercised without
 * disturbing the working WebView chat/terminal. It is reached from a debug
 * deep link / future nav switch, not the launcher, until M3 wires the real Home.
 *
 * M1: the Activity owns the [ChatViewModel] (Compose remembers it across
 * recompositions, `DisposableEffect` tears the SSE stream down when the
 * composition leaves). The session screen itself starts/stops the stream, so
 * sitting on Home costs no connection.
 */
class OcActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // M5: the same config store feeds the chat ViewModel *and* the
            // terminal panel (`PtyApi` needs the server URL + Basic auth), so it
            // is created once here instead of inside the ViewModel.
            val serverStore = remember { ServerConfigStore(applicationContext).also { it.restore() } }
            val chatViewModel = remember {
                ChatViewModel(serverStore) { /* screen shows an inline setup hint */ }
            }
            DisposableEffect(chatViewModel) {
                onDispose { chatViewModel.destroy() }
            }
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
                serverStore = serverStore,
                serverKey = serverKey,
                lastRoute = lastRoute,
                bottomTabStrip = appearance.bottomTabStrip(),
            )
        }
    }
}
