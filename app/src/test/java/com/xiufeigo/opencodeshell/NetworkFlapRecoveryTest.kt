package com.xiufeigo.opencodeshell

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Airplane-mode flap (emulator-5558 v79 OBS-2): network down, back 12 s later,
 * and the app sat on "Not connected" until a force-stop. Nothing observed the
 * OS network returning — recovery depended on the SSE loop's own timers (up to
 * a 35 s idle watchdog plus a grown 30 s backoff on a half-open socket) and on
 * reads the user happened to trigger — and one poison frame (or listener bug)
 * could silence the reconnect loop permanently, because only `IOException`
 * fell through to `scheduleReconnect()`.
 *
 * The first half below tests the pure heal decision for real; the stream half
 * runs [EventStream] against a [MockWebServer]; the wiring half pins the
 * OS-callback plumbing the JVM cannot execute.
 */
class NetworkFlapRecoveryTest {

    // ------------------------------------------------- 1. the pure decision

    @Test
    fun noServerRowNeverHeals() {
        for (transport in listOf(false, true)) {
            for (retryable in listOf<Boolean?>(null, true, false)) {
                assertEquals(
                    "with no server the Terminal-tab bootstrap owns the state, not the flap",
                    NetworkHealAction.NOTHING,
                    networkHealAction(
                        serverComplete = false,
                        transportPresent = transport,
                        lastProbeRetryable = retryable,
                    ),
                )
            }
        }
    }

    @Test
    fun liveTransportPokesTheStreams() {
        for (retryable in listOf<Boolean?>(null, true, false)) {
            assertEquals(
                "a working transport is never re-probed over one network blip",
                NetworkHealAction.POKE_STREAMS,
                networkHealAction(
                    serverComplete = true,
                    transportPresent = true,
                    lastProbeRetryable = retryable,
                ),
            )
        }
    }

    @Test
    fun missingTransportReprobesUnlessTheVerdictIsDefinitive() {
        assertEquals(
            NetworkHealAction.REPROBE,
            networkHealAction(serverComplete = true, transportPresent = false, lastProbeRetryable = null),
        )
        assertEquals(
            NetworkHealAction.REPROBE,
            networkHealAction(serverComplete = true, transportPresent = false, lastProbeRetryable = true),
        )
        assertEquals(
            "the host answered and it is not 2.x — only an explicit Retry revisits that",
            NetworkHealAction.NOTHING,
            networkHealAction(serverComplete = true, transportPresent = false, lastProbeRetryable = false),
        )
    }

    // -------------------------------------------- 2. the stream survives this

    private class Listener(
        private val onEventThrow: Boolean = false,
        private val onStateThrow: Boolean = false,
    ) : EventStream.Listener {
        val states = CopyOnWriteArrayList<EventStream.State>()
        val established = CountDownLatch(1)

        override fun onEvent(type: String, data: JSONObject) {
            if (onEventThrow) throw RuntimeException("poison frame")
        }

        override fun onState(state: EventStream.State) {
            states.add(state)
            if (state == EventStream.State.ESTABLISHED) {
                if (onStateThrow) throw RuntimeException("poison state")
                established.countDown()
            }
        }
    }

    private fun stream(server: MockWebServer, listener: EventStream.Listener): EventStream = EventStream(
        baseHttpUrl = server.url("/"),
        username = null,
        password = null,
        client = EventStream.sseClient(OkHttpClient()),
        listener = listener,
    )

    private fun finiteSse(payload: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "text/event-stream")
        .setBody(payload)

    private fun waitForRequests(server: MockWebServer, count: Int, timeoutSecs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutSecs * 1_000L
        while (System.currentTimeMillis() < deadline) {
            if (server.requestCount >= count) return true
            Thread.sleep(100)
        }
        return server.requestCount >= count
    }

    @Test
    fun throwingOnEventEndsTheAttemptNeverTheLoop() {
        val server = MockWebServer()
        val stream: EventStream
        try {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    finiteSse("data: {\"type\":\"x\"}\n\n")
            }
            server.start()
            val listener = Listener(onEventThrow = true)
            stream = stream(server, listener)
            stream.start()
            assertTrue(
                "one poison frame must not silence the loop: a second dial follows the reset backoff",
                waitForRequests(server, 2, 15),
            )
            assertFalse("the loop never gave up", listener.states.contains(EventStream.State.CLOSED))
            stream.close()
        } finally {
            try {
                server.shutdown()
            } catch (_: Exception) {
            }
        }
    }

    @Test
    fun throwingOnEstablishedEndsNothingAtAll() {
        val server = MockWebServer()
        val stream: EventStream
        try {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    finiteSse("data: {\"type\":\"x\"}\n\n")
            }
            server.start()
            val listener = Listener(onStateThrow = true)
            stream = stream(server, listener)
            stream.start()
            assertTrue(
                "a listener bug on the state path must not silence the loop either",
                waitForRequests(server, 2, 15),
            )
            assertFalse("the loop never gave up", listener.states.contains(EventStream.State.CLOSED))
            stream.close()
        } finally {
            try {
                server.shutdown()
            } catch (_: Exception) {
            }
        }
    }

    @Test
    fun reconnectNowCutsShortAHungEstablishedStream() {
        val server = MockWebServer()
        val stream: EventStream
        try {
            // A stream that never ends: the idle watchdog alone would sit out
            // its 35 s before the first re-dial, far past the flap budget.
            val drip = StringBuilder()
            repeat(900) { drip.append(":hb\n") }
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(drip.toString())
                    .throttleBody(1, 1, TimeUnit.SECONDS)
            }
            server.start()
            val listener = Listener()
            stream = stream(server, listener)
            stream.start()
            assertTrue("the drip keeps the stream established", listener.established.await(15, TimeUnit.SECONDS))
            Thread.sleep(1_500)
            assertEquals("no re-dial while the drip flows", 1, server.requestCount)
            stream.reconnectNow()
            assertTrue(
                "the poke re-dials in ~1 s instead of waiting out the watchdog",
                waitForRequests(server, 2, 10),
            )
            stream.close()
        } finally {
            try {
                server.shutdown()
            } catch (_: Exception) {
            }
        }
    }

    @Test
    fun reconnectNowOnAStoppedStreamDialsNothing() {
        val server = MockWebServer()
        try {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    finiteSse("data: {\"type\":\"x\"}\n\n")
            }
            server.start()
            val stream = stream(server, Listener())
            stream.reconnectNow()
            Thread.sleep(1_500)
            assertEquals("a closed stream stays closed", 0, server.requestCount)
        } finally {
            try {
                server.shutdown()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * v81 BUG-01 (emulator-5558, blocking): the network comes back while the
     * reconnect loop is **parked** on its grown backoff, and the poke has to dial
     * *now* rather than wait the sleep out — the reported shape was 40 s+ with
     * nothing moving.
     *
     * The server answers 5xx (retryable, so the backoff grows exactly like an
     * outage), which parks the loop on a multi-second sleeper after a few
     * dials. The poke then has to produce the next dial well inside that sleep.
     */
    @Test
    fun reconnectNowDialsWhenTheLoopIsParkedOnTheGrownBackoff() {
        val server = MockWebServer()
        val stream: EventStream
        try {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse().setResponseCode(503)
            }
            server.start()
            val listener = Listener()
            stream = stream(server, listener)
            stream.start()
            assertTrue(
                "the outage backoff has to build up before the poke means anything",
                waitForRequests(server, 4, 20),
            )
            // Let the fourth failure land, so its sleeper is the one parked: the
            // delays are 1 s, 2 s, 4 s, 8 s — the next dial is 8 s away.
            Thread.sleep(300)
            stream.reconnectNow()
            assertTrue(
                "the poke dials immediately instead of waiting the parked 8 s out",
                waitForRequests(server, 5, 2),
            )
            stream.close()
        } finally {
            try {
                server.shutdown()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * A stream the client-error breaker stopped ([EventStream]'s 4xx budget) is
     * a stopped chain too: `running` is false and the old poke returned on it.
     * The network coming back is new information about the very verdict the
     * breaker acted on, so [EventStream.reconnectNow] has to revive it.
     *
     * The pokes between the 4xx answers keep the backoff at its first step, so
     * the budget is spent in a couple of seconds instead of the ~31 s the
     * untended 1+2+4+8+16 s ladder would take.
     */
    @Test
    fun reconnectNowRevivesAStreamTheClientErrorBreakerStopped() {
        val server = MockWebServer()
        val stream: EventStream
        try {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse().setResponseCode(404)
            }
            server.start()
            val listener = Listener()
            stream = stream(server, listener)
            stream.start()
            val deadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < deadline && !listener.states.contains(EventStream.State.CLOSED)) {
                stream.reconnectNow()
                Thread.sleep(150)
            }
            assertTrue(
                "the breaker has to trip for this test to mean anything",
                listener.states.contains(EventStream.State.CLOSED),
            )
            val dials = server.requestCount
            stream.reconnectNow()
            assertTrue(
                "a poke revives the stopped stream and it dials again",
                waitForRequests(server, dials + 1, 5),
            )

            // …and with the host answering again, that is what heals it.
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    finiteSse("data: {\"type\":\"x\"}\n\n")
            }
            val established = listener.states.count { it == EventStream.State.ESTABLISHED }
            val reviveDeadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < reviveDeadline &&
                listener.states.count { it == EventStream.State.ESTABLISHED } == established
            ) {
                Thread.sleep(100)
            }
            assertTrue(
                "the revived stream reaches ESTABLISHED once the host answers",
                listener.states.count { it == EventStream.State.ESTABLISHED } > established,
            )
            stream.close()
        } finally {
            try {
                server.shutdown()
            } catch (_: Exception) {
            }
        }
    }

    // ------------------------------------------------------- 3. wiring pins

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

    private val streamSrc by lazy {
        source("src/main/java/com/xiufeigo/opencodeshell/EventStream.kt")
    }

    @Test
    fun networkReturnHealsThroughOneDecisionPoint() {
        val body = window(vm, "    fun onNetworkAvailable() {", "    /** Publishes [failure]")
        assertTrue("a prefs re-read, never a stale snapshot", body.contains("serverConfig.freshServer()"))
        val guard = "if (server == null || !server.isComplete) return"
        assertTrue("no usable server row: hands off, the bootstrap owns the state", body.contains(guard))
        assertTrue(
            "the no-server return precedes every config-missing write",
            body.indexOf(guard) < body.indexOf("_configMissing.value = false"),
        )
        assertTrue("one pure decision, tested above", body.contains("networkHealAction("))
        assertTrue("a live transport only re-dials its streams", body.contains("reconnectNow()"))
        assertTrue("a missing transport re-probes past the lazy throttle", body.contains("retryProbe()"))
        assertTrue("the throttle is bypassed, not waited out", body.contains("lastProbeAttemptAt = 0L"))
    }

    @Test
    fun missingClientRebindsBeforeCryingNoConfig() {
        val body = window(vm, "    private fun requireClient()", "    /**")
        assertTrue(
            "a missing client is not a missing config: rebind while the row is intact",
            body.contains("if (ensureClient()) return clientRef.get()"),
        )
        assertTrue("a really absent server still raises the setup hint", body.contains("onConfigMissing()"))
        assertTrue(body.contains("_configMissing.value = true"))
    }

    @Test
    fun streamKeepsItsBackoffCeilingAndSingleFlight() {
        assertTrue("the ceiling is unchanged: a dead server still parks at 30 s", streamSrc.contains("MAX_BACKOFF_MS = 30_000L"))
        assertTrue(streamSrc.contains("fun reconnectNow()"))
        // v81 BUG-01: the single-flight token is the live call itself now — the
        // old `connectAttempt` claim could outlive its attempt (nothing dialing,
        // the poke cancelling a finished call) and a poke could not tell "a dial
        // is in flight" from "the chain is parked". Same rule, one object.
        assertTrue(
            "overlapping pokes collapse into one dial",
            streamSrc.contains("if (!running.get() || call != null) return"),
        )
        assertTrue(
            "…and the token is released on every terminal path",
            streamSrc.contains("private fun finishAttempt(attempt: Call)"),
        )
        assertTrue(
            "a stopped chain is dialable again (the poke may revive the breaker)",
            streamSrc.contains("gaveUp.compareAndSet(true, false)"),
        )
        assertTrue("a listener bug can no longer skip the reschedule", streamSrc.contains("catch (t: Exception)"))
    }

    @Test
    fun bothHostsObserveNetworkReturns() {
        val oc = source("src/main/java/com/xiufeigo/opencodeshell/OcActivity.kt")
        assertTrue(oc.contains("NetworkRecoveryWatch(applicationContext)"))
        assertTrue(oc.contains("chatViewModel.onNetworkAvailable()"))
        val main = source("src/main/java/com/xiufeigo/opencodeshell/MainActivity.kt")
        assertTrue(main.contains("NetworkRecoveryWatch(this)"))
        assertTrue(main.contains("networkWatch?.start()"))
        assertTrue(main.contains("networkWatch?.stop()"))
        val manifest = source("src/main/AndroidManifest.xml")
        assertTrue(manifest.contains("android.permission.ACCESS_NETWORK_STATE"))
    }
}
