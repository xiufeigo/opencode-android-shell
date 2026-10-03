package com.xiufeigo.opencodeshell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level pins for the embedded browser pane (inside `@Composable`
 * functions and `AndroidView` callbacks, not reachable from a plain JVM
 * call): the wiring contract signature, the system-`WebView` lifecycle, and
 * the zero-new-dependency rule (no `androidx.browser` / Custom Tabs).
 */
class SessionBrowserPaneTest {

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText()
    }

    private fun pane() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/browser/SessionBrowserPane.kt")

    private fun logic() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/browser/SessionBrowserState.kt")

    @Test
    fun paneKeepsTheWiringContractSignature() {
        val body = pane()
        assertTrue(
            "SessionBrowserPane(url, onClose, onOpenExternal) is owned by the parallel wiring agent",
            body.contains("fun SessionBrowserPane(url: String, onClose: () -> Unit, onOpenExternal: () -> Unit)"),
        )
        assertTrue(body.contains("package com.xiufeigo.opencodeshell.ui.session.browser"))
    }

    @Test
    fun paneUsesSystemWebViewWithClientAndDestroy() {
        val body = pane()
        assertTrue(body.contains("import android.webkit.WebView"))
        assertTrue(body.contains("import android.webkit.WebViewClient"))
        assertTrue(body.contains("AndroidView("))
        assertTrue(body.contains("WebView(context)"))
        assertTrue(body.contains("webViewClient = "))
        assertTrue(body.contains("onPageStarted("))
        assertTrue(body.contains("onPageFinished("))
        assertTrue(body.contains("onReceivedError("))
        // Enter composition creates, leaving composition destroys (leak guard).
        assertTrue(body.contains("DisposableEffect("))
        assertTrue(body.contains(".destroy()"))
        assertTrue(body.contains("removeView("))
    }

    @Test
    fun paneHasToolbarLoadingErrorAndRetryChrome() {
        val body = pane()
        // Upstream toolbar: close + external-open entry points owned by the caller.
        assertTrue(body.contains("onClose"))
        assertTrue(body.contains("onOpenExternal"))
        assertTrue(body.contains("R.string.oc_browser_close"))
        assertTrue(body.contains("R.string.oc_browser_open_external"))
        // Address / security indicator.
        assertTrue(body.contains("R.string.oc_browser_secure"))
        assertTrue(body.contains("R.string.oc_browser_insecure"))
        // Loading + failed + retry.
        assertTrue(body.contains("LinearProgressIndicator("))
        assertTrue(body.contains("R.string.oc_browser_failed_title"))
        assertTrue(body.contains("R.string.oc_browser_retry"))
        assertTrue(body.contains(".reload()"))
        assertTrue(body.contains("R.string.oc_browser_empty_title"))
    }

    @Test
    fun paneAddsNoNewDependency() {
        val body = pane()
        assertFalse("no androidx.browser / Custom Tabs — system WebView only", body.contains("androidx.browser"))
        assertFalse(body.contains("CustomTab"))
        assertFalse(body.contains("customtabs"))
        val imports = body.lines().filter { it.startsWith("import ") }
        assertTrue(imports.none { it.contains("androidx.browser") })
    }

    @Test
    fun logicStaysJvmTestable() {
        val body = logic()
        assertTrue(body.contains("object SessionBrowserUrls"))
        assertTrue(body.contains("class SessionBrowserState"))
        assertTrue(body.contains("enum class BrowserLoadState"))
        assertFalse(
            "pure logic must not import Android framework types",
            body.lines().any { it.startsWith("import android.") },
        )
        assertFalse(body.contains("androidx."))
    }

    @Test
    fun paneConsumesTheSafeAreaSoTheToolbarStaysReachable() {
        val body = pane()
        // v30 BUG-1: the pane is an opaque full-body overlay painted from
        // `y = 0` (its host box in `SessionScreen` applies no insets), so it
        // clears the system bars itself — otherwise Close sits inside the
        // status bar's touch region and the panel cannot be dismissed at all.
        assertTrue(
            "the pane clears status bars / nav bars / cutout / IME itself",
            body.contains("windowInsetsPadding(WindowInsets.safeDrawing)"),
        )
        val entry = body.indexOf("fun SessionBrowserPane(")
        val insets = body.indexOf("windowInsetsPadding(WindowInsets.safeDrawing)", entry)
        val toolbar = body.indexOf("R.string.oc_browser_close", entry)
        assertTrue("the inset padding is on the pane's root", insets > entry)
        assertTrue("the toolbar (Close included) is placed inside it", insets < toolbar)
        // Background before the padding: the node keeps the full window, so the
        // pane still paints `bg-base` behind the bars instead of leaving the
        // session content visible there.
        val background = body.indexOf(".background(c.bgBase)", entry)
        assertTrue("the pane paints behind the bars", background in entry..<insets)
    }

    @Test
    fun theWiringOwnerKeepsASecondExitForTheFullBodyOverlay() {
        // The fallback: BACK / the back gesture closes the pane while it is up,
        // so an unreachable toolbar can never wedge the session again. Owned by
        // the wiring owner (the pane's three-argument contract is unchanged).
        //
        // v31 BUG-1: through `OverlayBackHandler`, i.e. a direct
        // `OnBackPressedDispatcher.addCallback` — `androidx.activity.compose
        // .BackHandler` registers with `addCallback(lifecycleOwner, …)`, which
        // stays inert until that lifecycle reaches STARTED, and on the device
        // the overlay was left with no handler at all (BACK fell through to
        // `finish()`). The IME is collapsed with the overlay so the first BACK
        // is not eaten by the composer's keyboard.
        val screen = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")
        assertTrue(
            "BACK closes the browser overlay",
            screen.contains("OverlayBackHandler { browserUrl = null }"),
        )
        assertTrue(screen.contains("import androidx.activity.compose.LocalOnBackPressedDispatcherOwner"))
        assertTrue(screen.contains("dispatcher.addCallback(callback)"))
        assertTrue(
            "the overlay's keyboard is collapsed on entry",
            screen.contains("LaunchedEffect(url) { keyboardController?.hide() }"),
        )
        // The pane itself still never intercepts back (its contract is three
        // parameters); the owner is the only place a back handler exists.
        assertFalse(pane().contains("androidx.activity"))
    }

    @Test
    fun browserStringsExistInAllThreeLocales() {
        val names = listOf(
            "oc_browser_close",
            "oc_browser_open_external",
            "oc_browser_reload",
            "oc_browser_stop",
            "oc_browser_go_back",
            "oc_browser_go_forward",
            "oc_browser_address",
            "oc_browser_secure",
            "oc_browser_insecure",
            "oc_browser_empty_title",
            "oc_browser_empty_description",
            "oc_browser_failed_title",
            "oc_browser_failed_description",
            "oc_browser_retry",
        )
        val files = listOf(
            "src/main/res/values/strings.xml",
            "src/main/res/values-zh-rCN/strings.xml",
            "src/main/res/values-zh-rTW/strings.xml",
        )
        for (file in files) {
            val body = source(file)
            for (name in names) {
                assertTrue("$file must define $name", body.contains("name=\"$name\""))
            }
        }
    }
}
