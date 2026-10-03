package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.v2.ServerGeneration
import com.xiufeigo.opencodeshell.v2.TransportFailure
import com.xiufeigo.opencodeshell.v2.V2Capability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v21 P3, 334 §bug2 (`research/v21_verify/picker/`): closing the add-project
 * picker and reopening it occasionally showed 「Server is reachable but has no
 * directory API」 ([TransportFailure.NotConnected], `oc_picker_error_unusable`)
 * instead of the directory list, and only its Retry / 「根目录」 button brought
 * the list back.
 *
 * Root cause: the reopen is a **fresh composition** (the dialog layer is popped
 * from `DialogHost`'s stack), so its `LaunchedEffect`s do re-run — but
 * `listFiles` answered from the *latched* probe verdict. `requireTransport()`
 * walks into `maybeReprobe`, which deliberately never re-probes a definitive
 * negative ("up, but not 2.x"), and `noTransportFailure()` then reports that
 * stale verdict. The Retry button healed it because `retryProbe()` drops
 * `lastProbe` first — i.e. the *gesture* outranked the latch, and only the
 * gesture.
 *
 * The fix gives the picker's own open the same precedence: `listFiles(fresh =
 * true)` / `browseConstraints(fresh = true)` drop the verdict, start one probe
 * round and answer from **that** round. The hot-read gate is untouched (a read
 * that is not a gesture still skips a definitive verdict — see
 * `ProbeSelfHealTest`).
 */
class DirectoryPickerFreshProbeTest {

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

    private val vm by lazy { source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt") }
    private val picker by lazy {
        source("src/main/java/com/xiufeigo/opencodeshell/ui/home/DirectoryPickerDialog.kt")
    }

    // -------------------------------- 1. the stale verdict does not block

    /** The verdict the v21 reopen read: the host answered, but not with a v2 API. */
    private fun definitiveNegative() = V2Capability.ProbeResult(
        generation = ServerGeneration.UNKNOWN,
        httpStatus = 404,
        contentType = "text/html",
        detail = "the SPA shell, so /api/info is not served",
    )

    @Test
    fun aFreshRoundNeverReportsTheStaleVerdict() {
        // With the round in flight the picker reads 「connecting」 and re-reads on
        // the generation change — never the latched negative.
        assertEquals(
            TransportFailure.ProbePending,
            transportFailureFor(probing = true, lastProbe = definitiveNegative(), serverUsable = true),
        )
    }

    @Test
    fun theSameVerdictStillAnswersOnceTheRoundIsOver() {
        // The fix is precedence, not a blanket lie: once nothing is in flight the
        // verdict is the truth again.
        assertEquals(
            TransportFailure.NotConnected,
            transportFailureFor(probing = false, lastProbe = definitiveNegative(), serverUsable = true),
        )
    }

    // ------------------------------------------ 2. the gesture's precedence

    @Test
    fun listFilesDropsTheVerdictAndAnswersFromItsOwnRound() {
        val body = window(vm, "fun listFiles(", "/** One `listDirectory` read")
        assertTrue("the picker's open read is marked as a gesture", body.contains("fresh: Boolean = false"))
        // A live transport is not a latch: it short-circuits before any probe.
        val live = body.indexOf("val ready = requireTransport()")
        assertTrue(live >= 0)
        assertTrue(live < body.indexOf("retryProbe()"))
        // The gesture path: drop the verdict, run one round, answer after it.
        val drop = body.indexOf("retryProbe()")
        val wait = body.indexOf("awaitProbeSettled()")
        val reread = body.indexOf("val transport = requireTransport()", drop)
        assertTrue("the verdict is dropped first", drop >= 0)
        assertTrue("the answer waits for the round the gesture started", wait > drop)
        assertTrue("the read then uses that round's transport", reread > wait)
        // A non-gesture read keeps the old behaviour exactly.
        assertTrue(body.contains("if (!fresh) {"))
        assertTrue(body.contains("reportBrowseFailure(noTransportFailure(), onFailure)"))
    }

    @Test
    fun browseConstraintsTakeTheSameGesturePath() {
        // The constraints are the picker's *starting path*: answered from a
        // latched verdict they parked the browser on `/` with no home.
        val body = window(vm, "fun browseConstraints(", "// ---- review panel")
        assertTrue(body.contains("fresh: Boolean = false"))
        assertTrue(body.contains("retryProbe()"))
        assertTrue(body.contains("awaitProbeSettled()"))
        assertTrue(body.indexOf("retryProbe()") < body.indexOf("val transport = requireTransport()"))
    }

    @Test
    fun theProbeWaitEndsWhenTheRoundEndsWhateverItConcluded() {
        // A re-probe that lands the same negative changes no StateFlow value, so
        // a waiter keyed on `generation` slept out the whole timeout before
        // answering. The round counter is the signal.
        val wait = window(vm, "private suspend fun awaitProbeSettled()", "private suspend fun fetchSessionRows()")
        assertTrue(wait.contains("probeSettled.first {"))
        assertFalse("the generation value cannot wake a same-verdict round", wait.contains("generation.first {"))
        val probe = window(vm, "private fun ensureTransport(", "/** Install the transport")
        assertTrue("every round end bumps the counter", probe.contains("probeSettled.value += 1"))
    }

    @Test
    fun theHotReadGateIsNotWeakened() {
        // A read that is not a gesture still shares one round and still skips a
        // definitive verdict.
        val gate = window(vm, "private fun maybeReprobe() {", "private fun requireTransport()")
        assertTrue(gate.contains("lastProbe?.isRetryable == false"))
        assertTrue(gate.contains("PROBE_LAZY_RETRY_MS"))
        assertTrue(gate.contains("probing.get()"))
    }

    // ------------------------------------------------- 3. the picker wiring

    @Test
    fun bothPickerOpenReadsAreGestures() {
        assertTrue(picker.contains("chatViewModel.browseConstraints(fresh = true)"))
        // The listing read names the path on screen through the token guard
        // (`requested`, v54 P1), so the assertion keys on the read's own
        // gesture flag rather than on the first argument's spelling.
        val listing = window(picker, "val requested = currentPath", "onFailure = { if (pickerReadIsCurrent")
        assertTrue(
            "the listing must not read a latched verdict either",
            listing.contains("fresh = true"),
        )
        // The Retry button keeps working the way it did (it is the same
        // precedence, applied by hand).
        assertTrue(picker.contains("chatViewModel.retryProbe()"))
    }

    // --------------------------------------- 4. the `~` button's target (BUG-2)

    @Test
    fun theTildeButtonNavigatesToTheDerivedHome() {
        // v32 BUG-2 (`research/v32_verify/tB/18_picker_home.png`): the button
        // navigated to `browseHome`, which on v2 is the *location* — the project
        // directory the browser was already on — so the tap re-assigned the same
        // path and nothing moved. The target is derived instead
        // ([pickerHomeTarget]); the pure half is covered by
        // DirectoryPickerRootsTest.
        val nav = window(picker, "// `~` / root / parent ghost buttons", "GhostNavButton(\n                // True filesystem root")
        assertTrue("the label is still the tilde", nav.contains("label = \"~\""))
        // v57: the enabled check also sees the path on screen, so a `~` that is
        // already at home greys out instead of being a live-looking dead tap.
        assertTrue(
            "the target is derived, not the raw location",
            nav.contains("enabled = pickerHomeEnabled(homeTarget, currentPath)"),
        )
        // ...and that predicate is the blank-check on the *derived* target, not on
        // the location (extracted to `pickerHomeEnabled` when the button grew its
        // own pressed feedback).
        val enabled = window(picker, "internal fun pickerHomeEnabled(", "internal fun pickerTrueRoot(")
        assertTrue(enabled.contains("!homeTarget.isNullOrBlank()"))
        assertFalse("the location must not gate the button either", enabled.contains("browseHome"))
        assertTrue(nav.contains("onClick = { homeTarget?.let { navigate(it) } }"))
        assertFalse("the location must not be navigated to directly", nav.contains("browseHome?.let { navigate(it) }"))
        // The same value feeds `~`-prefixed typed input and the scoped search,
        // or the button and the text box would disagree about what `~` means.
        // v51: the typed path goes through `pickerResolveInput`, which is
        // `FsBrowse.resolveInput` plus the drive letter typed without its colon.
        assertTrue(picker.contains("pickerResolveInput(raw, homeTarget, base)"))
        assertTrue(picker.contains("scopedSearch(input, homeTarget, base)"))
        // v33 BUG-1: the target is derived from the path on screen *first* — the
        // location is the server process cwd on v2, which names no home at all
        // (measured `/mnt/c/...`), so keying it on `browseHome` alone sent `~` to
        // that cwd. The pure precedence is DirectoryPickerRootsTest's.
        // v68 refines that verdict without overturning it: the raw location may
        // answer only as the *last* resort — a server that names no home
        // anywhere left `~` grey and dispatching nothing
        // (`research/v68_verify/5560/75_tilde.png`), so the button jumps back
        // to the session's base instead of staying a dead zone. A derived home
        // always outranks it (see `pickerHomeTarget`'s body order below), and
        // the same-path grey-out keeps it from ever being the v32 dead tap.
        assertTrue(
            picker.contains(
                "pickerHomeTarget(currentPath, browseHome, knownHome, fallback = browseHome)",
            ),
        )
        val targetBody = window(picker, "internal fun pickerHomeTarget(", "internal fun pickerHomeEnabled(")
        assertTrue(
            "a derived home outranks the raw fallback",
            targetBody.indexOf("FsBrowse.homeOf(current)") < targetBody.indexOf("fallback?.takeIf"),
        )
    }
}
