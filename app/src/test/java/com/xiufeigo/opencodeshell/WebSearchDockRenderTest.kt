package com.xiufeigo.opencodeshell

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level pins for the websearch dock chrome (inside `@Composable`
 * functions, not reachable from a plain JVM call): the form dock routes the two
 * websearch shapes to the dedicated dock, and that dock keeps upstream's
 * provider Select + disable/retry/submit tray.
 */
class WebSearchDockRenderTest {

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText()
    }

    private fun dock() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/dock/SessionFormDock.kt")

    private fun websearch() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/dock/SessionWebSearchDock.kt")

    @Test
    fun formDockRoutesWebsearchAsksBeforeTheWizard() {
        val body = dock()
        assertTrue(body.contains("WebSearchModel.detect(fields)"))
        val routed = body.substringAfter("val websearchKind").substringBefore("// Canonical answers")
        assertTrue(routed.contains("SessionWebSearchDock("))
        assertTrue("the dedicated dock must win over the wizard", routed.contains("return"))
    }

    @Test
    fun dockKeepsUpstreamTrayAndSelect() {
        val body = websearch()
        // `session-websearch-dock.tsx:26-33` — the Select is disabled while
        // busy or unavailable.
        assertTrue(body.contains("OcSelect("))
        assertTrue(body.contains("!responding && !unavailable"))
        // `:56-68` — Disable (consent only) + Enable, primary rightmost.
        assertTrue(body.contains("WebSearchModel.offersDisable(kind)"))
        assertTrue(body.contains("WebSearchButtonVariant.Submit"))
        // `:47-52` — the load-failure override owns the Retry button.
        assertTrue(body.contains("R.string.oc_websearch_load_failed"))
        assertTrue(body.contains("onRetryProviders"))
    }

    @Test
    fun handoffAndRollbackRunThroughTheBridge() {
        val body = websearch()
        assertTrue(body.contains("WebSearchBridge.takeHandoff("))
        assertTrue(body.contains("WebSearchBridge.armHandoff("))
        assertTrue(body.contains("WebSearchBridge.markSubmitted("))
        assertTrue(body.contains("WebSearchBridge.consumeSubmitFailure("))
    }
}
