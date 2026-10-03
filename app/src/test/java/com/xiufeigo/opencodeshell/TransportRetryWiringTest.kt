package com.xiufeigo.opencodeshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Wiring pins for v32 BUG-1 (the dropped connection that never healed).
 *
 * `TransportRetryTest` proves the policy; this file proves the *seams*: the
 * idempotent reads that failed on device go through it, the recovery drops the
 * dead pooled connections and kicks the throttled re-probe, and no write path
 * ever reaches the retry runner (a replayed POST can duplicate a turn — the
 * reason the prompt/`session.create` writes keep their single attempt).
 */
class TransportRetryWiringTest {

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText()
    }

    /**
     * Same, plus the repo root: the i18n dictionaries (`research/_i18n_v2/`) sit
     * one level above the module directory the tests run in.
     */
    private fun repoSource(relative: String): String =
        listOf(File(relative), File("app/$relative"), File("../$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    private fun viewModel() =
        source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")

    /** The text between [start] and the first line beginning with [end] after it. */
    private fun window(text: String, start: String, end: String): String {
        val from = text.indexOf(start)
        assertTrue("missing anchor: $start", from >= 0)
        val to = text.indexOf("\n$end", from)
        assertTrue("missing end anchor: $end", to > from)
        return text.substring(from, to)
    }

    @Test
    fun theExportReadRetriesThroughTheSharedPolicy() {
        val body = window(viewModel(), "suspend fun fetchExportFrameResult(", "    // ----")
        assertTrue("reads go through the shared seam", body.contains("readTransport {"))
        // Re-resolved per attempt: a retry that ran past a re-probe must not
        // reuse the transport that probe replaced.
        assertTrue(body.contains("requireTransport() ?: transport"))
    }

    @Test
    fun theOtherIdempotentReadsShareTheSameSeam() {
        val vm = viewModel()
        assertTrue(
            "the timeline snapshot is the read that heals a stream that died",
            vm.contains("readTransport { transport.snapshotMessages(id, SNAPSHOT_MESSAGE_LIMIT) }"),
        )
        assertTrue(
            "Home's projects column",
            vm.contains("readTransport { (requireTransport() ?: transport).listProjects() }"),
        )
    }

    @Test
    fun theSeamIsTheBackoffRunnerAndNothingElse() {
        val vm = viewModel()
        assertTrue(vm.contains("private suspend fun <T> readTransport(block: suspend () -> T): T ="))
        assertTrue(vm.contains("TransportRetry.runRead(onFailure = { noteTransientTransportFailure() }"))
    }

    @Test
    fun recoveryEvictsTheDeadPoolAndKicksTheProbe() {
        val body = window(viewModel(), "private fun noteTransientTransportFailure()", "    // ----")
        // Pool-only eviction: `OpenCodeClient.close` never shuts the shared
        // dispatcher the SSE clients run on.
        assertTrue(body.contains("clientRef.get()?.close()"))
        assertTrue("the missing-transport half of the reconnect", body.contains("maybeReprobe()"))
    }

    @Test
    fun aTransientWriteFailureOnlyDropsThePoolNeverReplays() {
        val submit = window(viewModel(), "fun submit(", "    /** v2 file attachments")
        assertTrue(submit.contains("if (TransportRetry.isTransient(e)) noteTransientTransportFailure()"))
        assertFalse("a write must never use the retry runner", submit.contains("readTransport"))
        assertFalse(submit.contains("TransportRetry.runRead"))
    }

    @Test
    fun aConnectionFailureGetsItsOwnCardCopy() {
        val store = source("src/main/java/com/xiufeigo/opencodeshell/ChatStore.kt")
        assertTrue(store.contains("TRANSPORT,"))
        assertTrue(store.contains("if (TransportRetry.isConnectionFailureText(message))"))
        val card = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/timeline/SessionErrorCard.kt")
        assertTrue(card.contains("ChatStore.ProviderErrorKind.TRANSPORT ->"))
        assertTrue(card.contains("R.string.session_error_serverConnection"))
        assertTrue("the retry the write path deliberately leaves to the user", card.contains("R.string.session_error_transient_advice"))
    }

    /**
     * The new advice copy ships in all three locales (the parity gate reads the
     * same dictionaries), and the generator's dotted→underscore expansion is
     * what `errorCopy` references.
     */
    @Test
    fun theRetryAdviceExistsInEveryLocale() {
        for (dir in listOf("values", "values-zh-rCN", "values-zh-rTW")) {
            val xml = source("src/main/res/$dir/strings_oc_i18n.xml")
            assertTrue("$dir is missing the advice", xml.contains("""name="session_error_transient_advice""""))
        }
        for (locale in listOf("en", "zh", "zht")) {
            val json = repoSource("research/_i18n_v2/$locale.json")
            assertTrue("$locale dictionary is missing the key", json.contains("\"session.error.transient.advice\""))
        }
    }

    /** The export toast keeps naming its own stage, not the card's copy. */
    @Test
    fun theExportToastStillNamesItsStage() {
        val actions = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeRowActions.kt")
        assertTrue(actions.contains("R.string.oc_export_fetch_failed"))
        assertEquals(
            1,
            Regex("R\\.string\\.oc_export_fetch_failed").findAll(actions).count(),
        )
    }
}
