package com.xiufeigo.opencodeshell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source pins for the 3-device P0 (2026-09-24): the generation probe sent no
 * `Authorization` (credentials were still in the `ensureClient` snapshot's
 * future), decided `UNKNOWN` off a `401`, and `ChatViewModel` then fell back to
 * the v1 unprefixed paths — SPA `200 text/html` on a 2.x backend — exploding as
 * `Value <!doctype … cannot be converted to JSONObject`. Same technique as
 * [SessionRaceGuardTest] (the ViewModel needs an Android `Context` + Main
 * dispatcher). Each test names the failure it forbids.
 */
class ProbeCredentialRaceTest {

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

    private val store by lazy {
        source("src/main/java/com/xiufeigo/opencodeshell/ServerConfigStore.kt")
    }

    private val probe by lazy {
        source("src/main/java/com/xiufeigo/opencodeshell/v2/V2Capability.kt")
    }

    // -------------------------------- 1. probe uses ServerHealth's credential source

    @Test
    fun probeResolvesCredentialsAtRequestTimeNotFromTheEnsureClientSnapshot() {
        val body = window(vm, "private fun ensureTransport(", "/** Install the transport")
        assertTrue(
            "the probe must re-read the store at request time (ServerHealth's listServers() path)",
            body.contains("serverConfig.freshServer()"),
        )
        val fresh = body.indexOf("serverConfig.freshServer()")
        val request = body.indexOf("V2Capability.probeUrl(")
        assertTrue("freshServer must be resolved before the request is built", fresh in 0 until request)
    }

    @Test
    fun theStoreExposesAFreshPrefsReadForTheProbe() {
        assertTrue("freshServer must exist for the probe/health credential parity", store.contains("fun freshServer(): Server?"))
        assertTrue("freshServer must go through restore() (the prefs read)", store.contains("restore()\n        return server"))
        assertTrue("pickActive must prefer the credential-bearing twin", store.contains("fun pickActive("))
        assertTrue(store.contains("firstOrNull { it.password != null }"))
    }

    // --------------------------------------- 2. auth wall ≠ "not a 2.x server"

    @Test
    fun authWallRetriesOnceWithFresherCredentials() {
        val body = window(vm, "private fun ensureTransport(", "/** Install the transport")
        assertTrue("an auth wall must retry against a fresh credential read", body.contains("result.authWall"))
        assertTrue(body.contains("retry.password != server.password"))
    }

    @Test
    fun probeTagsTheAuthWallSoTheUnknownErrorCanSayAuthentication() {
        assertTrue(probe.contains("authWall: Boolean = false"))
        assertTrue(probe.contains("statusCode == 401 || statusCode == 403"))
        val install = window(vm, "private fun setGeneration(", "private fun transport():")
        assertTrue("UNKNOWN must not claim 「requires 2.x」 for a 401", install.contains("probe?.authWall == true"))
        assertTrue(install.contains("needs a username/password"))
    }

    // ------------------------------------- 3. no silent v1 fallback on UNKNOWN

    @Test
    fun listSessionsNeverFallsBackToTheV1UnprefixedRoute() {
        val body = window(vm, "fun listSessions(onResult", "M3 addition")
        assertFalse(
            "v1 GET /session is the SPA catch-all on 2.x — the <!doctype parse bomb",
            body.contains("client.listSessions()"),
        )
        assertTrue("the list must ride the v2 /api/session read", body.contains("api.sessions("))
    }

    @Test
    fun listSessionsWaitsOutTheProbeAndRetriesOnceOnAStartupNetworkBlip() {
        val body = window(vm, "fun listSessions(onResult", "M3 addition")
        assertTrue("a cold start must not race the probe", body.contains("awaitProbeSettled()"))
        assertTrue("transient startup failure retries once", body.contains("delay(STARTUP_RETRY_DELAY_MS)"))
        assertTrue(body.contains("_sessionsLoadFailed.value = sessions == null"))
    }

    @Test
    fun v2LandingReDrivesTheSessionList() {
        val install = window(vm, "private fun setGeneration(", "private fun transport():")
        val v2Branch = window(install, "if (generation == ServerGeneration.V2) {", "} else {")
        assertTrue(
            "Home's sessionsRevision collector must re-read after the transport lands",
            v2Branch.contains("_sessionsRevision.value += 1"),
        )
    }

    // --------------------------- 4. browse reads report a distinguishable reason

    @Test
    fun browseReadsCarryATransportFailureInsteadOfASilentNull() {
        for (start in listOf(
            "fun listFiles(",
            "fun searchDirectories(",
            "fun reviewListFiles(",
            "fun listProjects(",
        )) {
            val body = window(vm, start, "\n    /**")
            assertTrue("$start must report the no-transport reason", body.contains("reportBrowseFailure(noTransportFailure()"))
        }
        val seam = source("src/main/java/com/xiufeigo/opencodeshell/v2/SessionTransport.kt")
        assertTrue(seam.contains("sealed interface TransportFailure"))
    }

    @Test
    fun createSessionFailsFastWithAReasonInsteadOfReturningWithoutCallbacks() {
        val body = window(vm, "fun createSession(", "scope.launch {")
        assertTrue(
            "the silent return left NewSessionScreen on 「Creating a session…」 forever",
            body.contains("onError("),
        )
        assertFalse("the old silent requireClient() escape is gone", body.contains("requireClient()\n            return"))
    }
}
