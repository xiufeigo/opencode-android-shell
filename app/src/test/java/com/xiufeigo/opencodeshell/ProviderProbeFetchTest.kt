package com.xiufeigo.opencodeshell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Connect-window fetch guards for the model-picker P0 and the server-switch
 * P1, pinned against the source function-window like [SessionRaceGuardTest]
 * (the ViewModel needs an Android `Context` + Main dispatcher).
 *
 * 1. The generation probe is async; before its verdict there is no transport
 *    and the v1 catalog routes must not run — a 2.x server answers them with
 *    SPA HTML and the JSON parse crashes (`providers fetch failed: <!doctype`).
 * 2. Once V2 lands, [ChatViewModel.setGeneration] must pull the provider
 *    catalog — otherwise `_availableProviders` stays null and the model chip
 *    toasts「模型列表不可用」forever.
 */
class ProviderProbeFetchTest {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private fun window(src: String, from: String, to: String): String {
        val start = src.indexOf(from)
        assertTrue("missing window start: $from", start >= 0)
        val end = src.indexOf(to, start + from.length)
        assertTrue("missing window end: $to", end > start)
        return src.substring(start, end)
    }

    private val vm by lazy {
        source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")
    }

    // --------------------------- 1. no v1 catalog route before the probe lands

    @Test
    fun ensureClientDoesNotFetchLabelOrCatalogsBeforeTheProbeLands() {
        val ensure = window(vm, "fun ensureClient(): Boolean {", "private fun ensureTransport(")
        assertFalse("label fetch must wait for the verdict", ensure.contains("refreshModelLabel()"))
        assertFalse("provider fetch must wait for the verdict", ensure.contains("refreshProviders()"))
        assertFalse("catalog fetch must wait for the verdict", ensure.contains("refreshCatalogs()"))
    }

    @Test
    fun everyV1FetchIsGuardedByTransportReadiness() {
        val bodies = listOf(
            window(vm, "private fun refreshModelLabel() {", "private fun refreshProviders("),
            window(vm, "private fun refreshProviders() {", "private suspend fun fetchProviders("),
            window(vm, "private fun refreshCatalogs() {", "private fun refreshV2Catalogs("),
        )
        for (body in bodies) {
            val guard = body.indexOf("if (requireTransport() == null) return")
            assertTrue("v1 fetch needs a transport-readiness guard", guard >= 0)
            val fetch = body.indexOf("val client = clientRef.get()")
            assertTrue("v1 client take must stay behind the guard", fetch > guard)
        }
    }

    // ------------------------------- 2. V2 verdict pulls the provider catalog

    @Test
    fun v2VerdictPullsProvidersAndCatalogs() {
        val install = window(vm, "private fun setGeneration(", "private fun transport():")
        assertTrue(install.contains("V2Transport(V2Api("))
        val v2Branch = window(install, "if (generation == ServerGeneration.V2) {", "} else {")
        assertTrue("V2 landing must pull providers", v2Branch.contains("refreshV2Providers()"))
        assertTrue("V2 landing must pull catalogs + default model", v2Branch.contains("refreshV2Catalogs()"))
    }

    // --------------------------------------- 3. server switch hot-reconnects

    @Test
    fun serverConfigChangesRebuildTheConnection() {
        assertTrue("the store revision must be observed", vm.contains("serverConfig.revision"))
        val adopt = window(vm, "private fun adoptActiveServer() {", "fun start() {")
        assertTrue("the old connection must be dropped", adopt.contains("closeConnections()"))
        assertTrue("an in-flight probe must be invalidated", adopt.contains("probeEpoch.incrementAndGet()"))
        assertTrue("connect + probe must re-run", adopt.contains("ensureClient()"))
    }
}
