package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.v2.ServerGeneration
import com.xiufeigo.opencodeshell.v2.V2Capability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P0 self-heal (emulator-5562, 2026-09-24): the generation probe failed once
 * at the network layer (`httpStatus == null` — reverse mapping / auth wall /
 * stack, while five siblings passed first try and curl from the host was 200),
 * `transportRef` stayed null for the rest of the process, and all four
 * "Create session" retries died in `requireTransport()` with
 * "Not connected to a server". Nothing ever re-probed.
 *
 * Same source-window pinning technique as [ProbeCredentialRaceTest] (the
 * ViewModel needs an Android `Context` + Main dispatcher); the retryable /
 * definitive split itself is pure ([V2Capability.ProbeResult.isRetryable]) and
 * tested for real.
 */
class ProbeSelfHealTest {

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

    private infix fun String.count(needle: String): Int = split(needle).size - 1

    private val vm by lazy {
        source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")
    }

    private val probe by lazy {
        source("src/main/java/com/xiufeigo/opencodeshell/v2/V2Capability.kt")
    }

    // --------------------------------------- 1. retryable vs. definitive split

    @Test
    fun unreachableProbeIsRetryable() {
        val result = V2Capability.ProbeResult(
            ServerGeneration.UNKNOWN,
            httpStatus = null,
            contentType = null,
            detail = "unreachable: network error",
        )
        assertTrue(result.isRetryable)
    }

    @Test
    fun authWallIsRetryable() {
        assertTrue(V2Capability.decideInfo(401, "application/json", "{}", credentialsSent = false).isRetryable)
        assertTrue(V2Capability.decideInfo(403, "application/json", "{}", credentialsSent = true).isRetryable)
    }

    @Test
    fun definitiveNegativesNeverRetry() {
        for (result in listOf(
            V2Capability.decideInfo(200, "text/html", "<!doctype html>"),
            V2Capability.decideInfo(200, "application/json", """{"version":"1.18.31"}"""),
            V2Capability.decideInfo(500, "text/html", "boom"),
            V2Capability.decideInfo(404, "", ""),
        )) {
            assertFalse("HTTP ${result.httpStatus} answered — not a 2.x server, no retry", result.isRetryable)
        }
    }

    // --------------------------------- 2. the probe itself never throws/latches

    @Test
    fun probeGetFailureIsAValueNeverAnException() {
        assertTrue("a probe must never throw into the connect flow", probe.contains("catch (_: Exception)"))
    }

    @Test
    fun networkFailureRetriesWithBackoffBeforeLatchingUnknown() {
        val body = window(vm, "private fun ensureTransport(", "/** Install the transport")
        assertTrue("unreachable must retry bounded rounds, not latch on one try", body.contains("PROBE_NETWORK_ATTEMPTS"))
        assertTrue(body.contains("while (result.httpStatus == null"))
        assertTrue("each round re-resolves credentials at request time", body.contains("serverConfig.freshServer()"))
    }

    // ------------------------------------------- 3. lazy re-probe on demand

    @Test
    fun requireTransportKicksALazyProbeInsteadOfReadingDeadNull() {
        val body = window(vm, "private fun requireTransport()", "private fun adoptActiveServer()")
        assertTrue("a latched retryable verdict must re-probe on demand", body.contains("maybeReprobe()"))
    }

    @Test
    fun maybeReprobeIsThrottledAndSkipsDefinitiveVerdicts() {
        assertTrue(vm.contains("private fun maybeReprobe()"))
        val body = window(vm, "private fun maybeReprobe() {", "private fun requireTransport()")
        assertTrue("an in-flight probe dedupes", body.contains("probing.get()"))
        assertTrue("a definitive negative never re-probes", body.contains("isRetryable"))
        assertTrue("hot reads share one probe", body.contains("PROBE_LAZY_RETRY_MS"))
    }

    @Test
    fun listSessionsKicksAReprobeWhenTheTransportIsMissing() {
        val body = window(vm, "fun listSessions(onResult", "M3 addition")
        assertTrue("Home's read must heal a latched probe, not just report it", body.contains("maybeReprobe()"))
    }

    // --------------------------------- 4. UNKNOWN settle re-drives the reads

    @Test
    fun unknownSettleRedrivesHomeReads() {
        val install = window(vm, "private fun setGeneration(", "private fun transport():")
        assertEquals(
            "V2 landing and UNKNOWN settle must both re-drive the sessionsRevision collector",
            2,
            install count "_sessionsRevision.value += 1",
        )
    }

    @Test
    fun unreachableHasItsOwnTransportFailure() {
        val body = window(vm, "private fun noTransportFailure()", "fun retryProbe()")
        assertTrue("the split lives in transportFailureFor", body.contains("transportFailureFor"))
        val split = window(vm, "internal fun transportFailureFor(", "v2 connected-provider predicate")
        assertTrue(split.contains("TransportFailure.ProbePending"))
        assertTrue(split.contains("TransportFailure.AuthRequired"))
        assertTrue(split.contains("TransportFailure.Unreachable"))
        assertTrue("unreachable (null status) splits from definitive NotConnected", split.contains("httpStatus == null"))
        assertTrue(split.contains("TransportFailure.NotConnected"))
        val seam = source("src/main/java/com/xiufeigo/opencodeshell/v2/SessionTransport.kt")
        assertTrue(seam.contains("data object Unreachable"))
    }

    // --------------------------------------- 5. server switch drops the stale

    @Test
    fun serverSwitchClearsTheStaleVerdict() {
        val adopt = window(vm, "private fun adoptActiveServer() {", "fun start() {")
        assertTrue("the old server's verdict must not label the new one", adopt.contains("lastProbe = null"))
        assertTrue("the throttle must not suppress the new server's first probe", adopt.contains("lastProbeAttemptAt = 0L"))
    }

    // ---------------------------------- 6. the explicit tap outranks the latch

    @Test
    fun anExplicitRetryBypassesTheDefinitiveVerdictLatch() {
        // Device v20 (`research/v20_verify/panel_compact/v20_picker.png`): the
        // add-project picker's Retry did nothing on a launch-after-install,
        // because it only re-armed the throttle and then walked into
        // `maybeReprobe`'s "definitive negative never re-probes" gate.
        val body = window(vm, "fun retryProbe() {", "private fun reportBrowseFailure(")
        assertTrue("the verdict must be dropped before the round", body.contains("lastProbe = null"))
        assertTrue("the throttle must not swallow the tap", body.contains("lastProbeAttemptAt = 0L"))
        assertTrue("the tap starts a round directly", body.contains("ensureTransport(server)"))
        assertFalse("the tap must not go through the skipping gate", body.contains("maybeReprobe()"))
    }

    @Test
    fun anExplicitRetryRebindsAServerThatArrivedAfterLaunch() {
        val body = window(vm, "fun retryProbe() {", "private fun reportBrowseFailure(")
        // The other half of the cold start: the first `ensureClient()` found no
        // complete server (the WebView config sync had not landed), so *no* probe
        // ever ran and `lastProbe` was null — the picker read NotConnected with
        // nothing to retry. The tap must bind the server prefs hold *now*.
        assertTrue(body.contains("clientRef.get() == null && ensureClient()"))
        assertTrue("prefs are re-read at the tap, not the snapshot", body.contains("serverConfig.freshServer()"))
        assertTrue("a bound server clears the sticky config-missing state", body.contains("_configMissing.value = false"))
    }

    @Test
    fun theHotReadGateIsUnchangedByTheRetryFix() {
        // The fix is the *gesture*'s precedence, not a weakened self-heal: a hot
        // read still skips a definitive verdict and still shares one round.
        val body = window(vm, "private fun maybeReprobe() {", "private fun requireTransport()")
        assertTrue(body.contains("lastProbe?.isRetryable == false"))
        assertTrue(body.contains("PROBE_LAZY_RETRY_MS"))
        assertTrue(body.contains("probing.get()"))
    }

    @Test
    fun anExplicitRetryNeverTearsDownALiveTransport() {
        val body = window(vm, "fun retryProbe() {", "private fun reportBrowseFailure(")
        val guard = body.indexOf("transportRef.get() != null")
        assertTrue("a live transport is not a latch and must not be re-probed", guard >= 0)
        assertTrue(
            "…and the guard runs before anything is cleared or started",
            guard < body.indexOf("lastProbe = null"),
        )
        assertTrue(guard < body.indexOf("ensureTransport(server)"))
    }

    @Test
    fun aSuccessfulRetryLandsTheTransportAndRedrivesThePicker() {
        // "Retry then transport ready": `ensureTransport` installs the verdict's
        // transport and bumps the generation flow, and the picker's own effects
        // are keyed on `generation`, so the re-read happens without a second tap.
        val install = window(vm, "private fun setGeneration(", "private fun transport():")
        assertTrue(install.contains("transportRef.set(transport)"))
        assertTrue(install.contains("_generation.value = generation"))
        val picker = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/DirectoryPickerDialog.kt")
        assertTrue("the picker's retry button is wired to the forced probe", picker.contains("chatViewModel.retryProbe()"))
        assertTrue(
            "both picker reads re-run when the verdict lands",
            picker.contains("LaunchedEffect(currentPath, attempt, generation)"),
        )
    }
}
