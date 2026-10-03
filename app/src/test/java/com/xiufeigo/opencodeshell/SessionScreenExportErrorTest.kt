package com.xiufeigo.opencodeshell

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source pins for the SessionScreen context-tab wiring:
 *
 * 1. `exportError` — the context payload `LaunchedEffect` reads the export
 *    through `ChatViewModel.fetchExportFrameResult`, so a failed fetch
 *    arrives as `Result.failure` and the effect records the transport
 *    message in `contextExportError` (falling back to `oc_export_failed`
 *    only when the message is blank); success clears it. The `project(…)`
 *    call carries it to `SessionContextModel.Snapshot.exportError`, which
 *    drives the panel's FAILED hint (`oc_context_export_load_failed`).
 * 2. `usageUpsell` — the usage-exceeded overlay
 *    (`usage-exceeded-dialogs.tsx`) is mounted live: the VM-raised upsell is
 *    adopted through the 24 h / "don't show again" gate and rendered with
 *    `UsageExceededDialog`, dismissed through `dismissUsageUpsell`; the
 *    screen owns no upsell state itself.
 */
class SessionScreenExportErrorTest {

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        // CRLF sources would break `\n`-sensitive windows otherwise.
        return file.readText().replace("\r\n", "\n")
    }

    private fun sessionScreen() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")

    @Test
    fun exportFailureRecordsReasonInsteadOfSwallowing() {
        val body = sessionScreen()
        // the failure reason lives in screen state, reset per session/server
        assertTrue(body.contains("var contextExportError by remember(activeSession.id, serverRevision)"))
        // the fetch goes through the Result seam, not the swallowing nullable call …
        assertTrue(body.contains("chatViewModel.fetchExportFrameResult(activeSession.id, reviewRootPath)"))
        assertTrue(!body.contains("runCatching { chatViewModel.fetchExportFrame(activeSession.id, reviewRootPath) }"))
        // … and the effect records the transport message (fallback: export copy)
        assertTrue(body.contains("result.exceptionOrNull()?.message"))
        assertTrue(body.contains("context.getString(R.string.oc_export_failed)"))
        assertTrue(body.contains("contextExportError = result.exceptionOrNull()?.message"))
        // success clears a stale failure before marking loaded
        assertTrue(body.contains("contextExportError = null"))
    }

    @Test
    fun exportResultSeamPreservesTheCause() {
        // `fetchExportFrame` stays nullable for the Home/palette callers; the
        // Result twin carries the transport exception for the context tab.
        val vm = source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")
        assertTrue(vm.contains("fun fetchExportFrameResult("))
        assertTrue(vm.contains("Result<SessionExport.Frame?>"))
        // The read goes through the v32 BUG-1 retry seam (a dropped socket is
        // re-tried with backoff) while the seam itself stays `runCatching` →
        // `Result`: whatever the last attempt threw is still the cause the
        // context tab reports.
        assertTrue(vm.contains("readTransport { (requireTransport() ?: transport).fetchExportFrame(sessionID, directory) }"))
        assertTrue(vm.contains("runCatching {"))
        assertTrue(vm.contains("fetchExportFrameResult(sessionID, directory).getOrNull()"))
    }

    @Test
    fun projectionCarriesExportError() {
        val body = sessionScreen()
        assertTrue(body.contains("contextExportError,"))
        assertTrue(body.contains("exportError = contextExportError,"))
    }

    @Test
    fun usageUpsellIsMountedLive() {
        // The `UsageUpsell` contract (`ChatStore.UsageUpsell`,
        // `ChatState.usageUpsell`, `dismissUsageUpsell`,
        // `ui.overlay.UsageExceededDialog`) has landed: the screen mounts the
        // dialog live through the 24 h / "don't show again" gate instead of
        // pinning the reserved-hook comment.
        val body = sessionScreen()
        assertTrue(!body.contains("usage-exceeded upsell hook"))
        assertTrue(body.contains("UsageExceededDialog("))
        assertTrue(body.contains("upsell = upsell,"))
        assertTrue(body.contains("onDismiss = { chatViewModel.dismissUsageUpsell() },"))
        assertTrue(body.contains("UsageUpsellGate.shouldShow"))
        assertTrue(body.contains("upsellVisible"))
    }
}
