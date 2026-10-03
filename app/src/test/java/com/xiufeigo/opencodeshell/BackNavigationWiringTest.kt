package com.xiufeigo.opencodeshell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v31 BUG-1: BACK must reach the Compose `BackHandler`s.
 *
 * The device run left every back surface dead — the embedded browser overlay
 * could not be closed with BACK and repeated presses exited the app — while
 * logcat logged `OnBackInvokedCallback is not enabled for the application` on
 * every launch: the manifest never opted into the API 33+ predictive-back
 * callback, so the platform kept the app on the legacy path.
 *
 * Source-level pins (the manifest is not a JVM-reachable object, and Compose
 * back registration is not unit-testable without a Compose runtime): the opt-in
 * itself, and the fact that every `BackHandler` call site the shell relies on is
 * still there and still enabled where it was.
 */
class BackNavigationWiringTest {

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText()
    }

    private fun manifest() = source("src/main/AndroidManifest.xml")

    @Test
    fun manifestOptsIntoPredictiveBack() {
        val manifest = manifest()
        assertTrue(
            "API 33+ back is routed through OnBackInvokedCallback (the path every BackHandler uses)",
            manifest.contains("""android:enableOnBackInvokedCallback="true""""),
        )
        // The attribute rides `<application>`, never an activity: the flag is
        // read from the application info, and `V2ParityWiringTest` pins the
        // permission order (INTERNET < CAMERA < queries) that this file must not
        // disturb.
        val application = manifest.indexOf("<application")
        val attribute = manifest.indexOf("""android:enableOnBackInvokedCallback="true"""")
        val queries = manifest.indexOf("<queries>")
        val applicationEnd = manifest.indexOf("</application>")
        assertTrue("the opt-in sits inside <application>", attribute in application..<applicationEnd)
        assertTrue(
            manifest.indexOf("android.permission.INTERNET") < manifest.indexOf("android.permission.CAMERA"),
        )
        assertTrue(manifest.indexOf("android.permission.CAMERA") < queries)
    }

    @Test
    fun everyBackHandlerSiteIsStillWired() {
        // The four surfaces the shell owns. The overlay is the one that has to
        // work while a full-body pane is up (its own pin lives in
        // `SessionBrowserPaneTest`), the other three predate this fix and must
        // keep their enablement — an always-enabled handler would swallow BACK
        // on a screen that has nothing to dismiss.
        val session = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")
        assertTrue("the browser overlay registers its own exit", session.contains("OverlayBackHandler { browserUrl = null }"))
        assertTrue(
            "…through the dispatcher, not the lifecycle-gated BackHandler",
            session.contains("dispatcher.addCallback(callback)") &&
                session.contains("object : OnBackPressedCallback(true)"),
        )
        val dialogs = source("src/main/java/com/xiufeigo/opencodeshell/ui/overlay/DialogHost.kt")
        // Exit-gated, not bare-stack-gated: a dismissed layer stays mounted for
        // its exit transition, so `stack.isNotEmpty()` stayed true ~100 ms after
        // the last close and swallowed one BACK on the screen the user closed
        // the dialog to get back to. `hasOpenLayer` releases the key the moment
        // the last layer starts closing (`DialogExitResidueTest`).
        assertTrue(dialogs.contains("BackHandler(enabled = DialogLayerPolicy.hasOpenLayer(stack.map { it.closing }))"))
        assertTrue(dialogs.contains("{ controller.close() }") || dialogs.contains("controller.close()"))
        assertFalse(
            "a bare-stack BACK gate re-arms on the exit window and eats one press on the screen behind",
            dialogs.contains("BackHandler(enabled = stack.isNotEmpty())"),
        )
        val newSession = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/NewSessionScreen.kt")
        assertTrue(newSession.contains("BackHandler { onBack() }"))
        val home = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeSessionsList.kt")
        assertTrue(home.contains("""BackHandler(enabled = query.isNotEmpty()) { query = "" }"""))
    }

    @Test
    fun theOverlayHandlerOutlivesRecompositionButNotTheOverlay() {
        // The callback is remembered across recompositions (a fresh callback per
        // recomposition would re-register at the tail of the dispatcher's
        // LIFO list on every frame) and removed when the overlay leaves the
        // composition.
        val session = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")
        val handler = session.indexOf("private fun OverlayBackHandler(")
        assertTrue(handler > 0)
        val body = session.substring(handler)
        assertTrue(body.contains("val callback = remember {"))
        assertTrue(body.contains("dispatcher.addCallback(callback)"))
        assertTrue(body.contains("onDispose { callback.remove() }"))
        assertTrue(body.contains("rememberUpdatedState(onBack)"))
    }
}
