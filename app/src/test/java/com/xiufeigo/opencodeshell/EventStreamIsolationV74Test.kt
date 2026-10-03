package com.xiufeigo.opencodeshell

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v74: after a Stop the app's data layer dragged — `snapshot failed: timeout`,
 * `list sessions failed: timeout`, `project list failed: timeout` — while the
 * emulator held **no** TCP connection to the server at all, and only a
 * force-stop brought the lists back.
 *
 * Both SSE streams ([EventStream], the global `/api/event` and the per-session
 * log) used to be built as `sharedClient.newBuilder()`, which copies the shared
 * `Dispatcher` **by reference**. OkHttp keeps an async call in that dispatcher
 * (against `maxRequestsPerHost = 5`) until its `onResponse` returns, which for a
 * stream is "never": two streams held two of the five slots for the whole
 * process, and every REST read shared the remaining three. Timeouts apply only
 * to calls that are *executing*, so a fully-queued read has no socket, no
 * deadline and no error — the measured shape above.
 *
 * The client returned by [EventStream.sseClient] therefore owns its dispatcher
 * and connection pool, and it must also honour the idle watchdog the class
 * documents (its heartbeat measurement: `handlers/event.ts:22` ticks every
 * 15 s; a 62 s `curl -N /api/event` on 2.0.18/4095 saw 5 heartbeats).
 */
class EventStreamIsolationV74Test {

    private val shared = OkHttpClient()

    @Test
    fun `the stream client never shares the REST dispatcher`() {
        val client = EventStream.sseClient(shared)
        assertNotSame(
            "a stream holds a dispatcher slot for its whole life — it must not be the REST one",
            shared.dispatcher,
            client.dispatcher,
        )
        assertNotSame(
            "nor the REST connection pool, which every reconnect heal evicts wholesale",
            shared.connectionPool,
            client.connectionPool,
        )
    }

    @Test
    fun `the REST dispatcher keeps its own budget and pool`() {
        val client = EventStream.sseClient(shared)
        assertEquals(5, shared.dispatcher.maxRequestsPerHost)
        assertEquals(5, client.dispatcher.maxRequestsPerHost)
        assertFalse(
            "a fresh dispatcher starts empty, so the stream's slot is its own",
            shared.dispatcher === client.dispatcher,
        )
    }

    @Test
    fun `two streams do not share a dispatcher either`() {
        assertNotSame(
            EventStream.sseClient(shared).dispatcher,
            EventStream.sseClient(shared).dispatcher,
        )
    }

    @Test
    fun `the rest of the client settings are inherited`() {
        val tuned = shared.newBuilder().connectTimeout(7, java.util.concurrent.TimeUnit.SECONDS).build()
        val client = EventStream.sseClient(tuned)
        assertEquals(7_000L, client.connectTimeoutMillis.toLong())
        assertTrue(client.retryOnConnectionFailure)
    }

    @Test
    fun `the idle read timeout is the documented watchdog, not an infinite wait`() {
        val client = EventStream.sseClient(shared)
        assertEquals(EventStream.IDLE_READ_TIMEOUT_MS, client.readTimeoutMillis.toLong())
        assertNotEquals(
            "an infinite read is what keeps a blackholed stream (and its dispatcher slot) forever",
            0L,
            client.readTimeoutMillis.toLong(),
        )
        assertTrue(
            "more than two missed 15 s heartbeats, so a live stream is never dropped by it",
            client.readTimeoutMillis.toLong() > 2 * 15_000L,
        )
    }
}
