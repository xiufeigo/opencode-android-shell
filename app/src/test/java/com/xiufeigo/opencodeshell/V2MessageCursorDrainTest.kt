package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.V2Transport
import com.xiufeigo.opencodeshell.v2.opaqueCursorValue
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The message-drain cursor contract, pinned against the **2026-09-25 device
 * P0**: every ⋮ Export / context-tab payload fetch on a real phone answered
 * `HTTP 400 InvalidCursorError: Invalid cursor`, while every JVM test passed.
 *
 * The divergence was `org.json`, not the wire. `GET
 * /api/session/{id}/message` ends a transcript with
 * `{"data":[],"cursor":{"previous":null,"next":null}}` (byte-exact live
 * capture below), and the drain reads `cursor.next`:
 *
 * - Android's `JSONObject.optString(name, fallback)` runs the value through
 *   `JSON.toString`, and `JSONObject.NULL.toString()` is the literal `"null"` —
 *   only a *missing* key reaches the fallback. So the device read the terminal
 *   page as `next == "null"`, asked for
 *   `/api/session/…/message?cursor=null`, and the server answered the 400 above
 *   (measured: `cursor=null` is exactly
 *   `{"_tag":"InvalidCursorError","message":"Invalid cursor"}`).
 * - The JVM `org.json:json:20240303` the unit tests run on returns the fallback
 *   for `NULL`, so the drain stopped cleanly and the suite stayed green.
 *
 * A test can therefore never observe Android's `optString` here, but it can pin
 * its **output**: the body `{"next":"null"}` and the body `{"next":null}` must
 * both mean "no cursor" — the first is what Android derived from the second.
 * Every test that feeds `"null"` below fails against the pre-fix
 * `optString`-only parsing on this JVM harness, which is what makes it a real
 * regression pin rather than a restatement of the runtime that already worked.
 *
 * Neither test touches a real server: bodies are live captures replayed through
 * [MockWebServer] (the harness `V2ApiTest`/`PtyApiTest` already use).
 */
class V2MessageCursorDrainTest {

    private fun api(server: MockWebServer) = V2Api(
        baseHttpUrl = server.url("/").toString().toHttpUrlOrNull()!!,
        username = "opencode",
        password = "opencode",
        http = V2Api.defaultClient(),
    )

    // ------------------------------------------------------- live-captured wire

    /**
     * Page 1's `cursor` object, captured byte-exactly from `GET
     * /api/session/ses_0ed9691f6ffeZUnCfPBxATar34/message?order=asc` on 4095
     * (36 rows, 2026-09-25). `previous`/`next` decode to
     * `{"id":"msg_f12696e65001V0Zo…","order":"asc","direction":"previous"}` and
     * `{"id":"msg_0c38cc7e2001LsBZ0s4B7D5kIM","order":"asc","direction":"next"}`.
     */
    private val liveCursor = """
        "cursor":{"previous":"eyJpZCI6Im1zZ19mMTI2OTZlNjUwMDFWMFpvb2pPMGM4VmhPTiIsIm9yZGVyIjoiYXNjIiwiZGlyZWN0aW9uIjoicHJldmlvdXMifQ","next":"eyJpZCI6Im1zZ18wYzM4Y2M3ZTIwMDFMc0JaMHM0QjdENWtJTSIsIm9yZGVyIjoiYXNjIiwiZGlyZWN0aW9uIjoibmV4dCJ9"}
    """.trimIndent()

    /** The same capture's `next`, the value the follow-up must echo byte-identically. */
    private val liveNext =
        "eyJpZCI6Im1zZ18wYzM4Y2M3ZTIwMDFMc0JaMHM0QjdENWtJTSIsIm9yZGVyIjoiYXNjIiwiZGlyZWN0aW9uIjoibmV4dCJ9"

    /** The last message row of that capture, byte-exact. */
    private val liveLastRow =
        """{"id":"msg_0c38cc7e2001LsBZ0s4B7D5kIM","time":{"created":1789987001779},"text":"ok","type":"user"}"""

    /**
     * The terminal page `GET …/message?cursor=<liveNext>` answers with —
     * reproduced byte-for-byte (50 bytes) from 4095.
     */
    private val liveTerminalBody = """{"data":[],"cursor":{"previous":null,"next":null}}"""

    /**
     * The same terminal page as **Android's `optString` sees it**: `NULL`
     * stringified. `{"next":"null"}` here is literally what the device's drain
     * had in hand when it built `?cursor=null`.
     */
    private val androidTerminalBody = """{"data":[],"cursor":{"previous":"null","next":"null"}}"""

    /** `?cursor=null` on 4095, byte-exact — the device's export error. */
    private val invalidCursorBody = """{"_tag":"InvalidCursorError","message":"Invalid cursor"}"""

    private val sessionBody =
        """{"data":{"id":"ses_1","projectID":"p","time":{"created":1,"updated":2},"title":"t"}}"""

    // -------------------------------------------------- the runtime split itself

    /**
     * `Cursor.fromJson` must read the wire's JSON `null` as "no cursor" and must
     * not be fooled by the literal `"null"`/`"undefined"` tokens that Android's
     * `optString` manufactures — the exact value that 400'd every device export.
     */
    @Test
    fun cursorFromJsonTreatsNullUndefinedAndTheStringifiedSentinelAsAbsent() {
        assertNull(opaqueCursorValue(JSONObject("""{"next":null}"""), "next"))
        assertNull(opaqueCursorValue(JSONObject("""{"next":"null"}"""), "next"))
        assertNull(opaqueCursorValue(JSONObject("""{"next":"undefined"}"""), "next"))
        assertNull(opaqueCursorValue(JSONObject("""{}"""), "next"))
        assertNull(opaqueCursorValue(JSONObject("""{"next":""}"""), "next"))
        assertEquals(liveNext, opaqueCursorValue(JSONObject("""{"next":"$liveNext"}"""), "next"))

        val cursor = V2Api.Cursor.fromJson(JSONObject(androidTerminalBody))!!
        assertNull("Android's stringified NULL is not a cursor", cursor.previous)
        assertNull("Android's stringified NULL is not a cursor", cursor.next)

        val real = V2Api.Cursor.fromJson(JSONObject("""{$liveCursor}""").getJSONObject("cursor"))!!
        assertEquals(liveNext, real.next)
        assertTrue(real.previous!!.isNotEmpty())
    }

    // ------------------------------------------------------------ the drain itself

    /**
     * The device P0 in one drain: page 1 hands out a real cursor, the follow-up
     * page is the terminal one (read here the way Android reads it), and the
     * drain must **stop there** — exactly two message requests, no
     * `?cursor=null`, no 400.
     *
     * The third queued response is the live 400 body: before the fix the drain
     * sent `?cursor=null`, consumed it, and threw; now it is never taken.
     */
    @Test
    fun aTerminalCursorReadAsAndroidReadsItEndsTheDrainInsteadOfSendingNull() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(sessionBody))
            server.enqueue(
                MockResponse().setResponseCode(200).setBody("""{"data":[$liveLastRow],$liveCursor}"""),
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody(androidTerminalBody))
            server.enqueue(MockResponse().setResponseCode(400).setBody(invalidCursorBody))

            val transport = V2Transport(api(server))
            val frame = transport.fetchExportFrame("ses_1", null)

            assertEquals(listOf("msg_0c38cc7e2001LsBZ0s4B7D5kIM"), frame.messages.map { it.getString("id") })
            assertFalse(frame.truncated)
            assertEquals("session + 2 message pages, and no cursor=null probe", 3, server.requestCount)

            server.takeRequest()
            val first = server.takeRequest()
            assertEquals("asc", first.requestUrl!!.queryParameter("order"))
            assertNull(first.requestUrl!!.queryParameter("cursor"))
            val second = server.takeRequest()
            assertEquals(liveNext, second.requestUrl!!.queryParameter("cursor"))
            assertNull("cursor never rides with order", second.requestUrl!!.queryParameter("order"))
        }
    }

    /**
     * The same drain against the byte-exact live capture: page 1's cursor is
     * echoed verbatim (so the value is the server's own `cursor.next`, never a
     * re-encoded one), and the terminal page is the last request.
     */
    @Test
    fun liveCapturedPagesEchoTheServerCursorVerbatimAndStopAtTheTerminalPage() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[$liveLastRow],$liveCursor}"""))
            server.enqueue(MockResponse().setResponseCode(200).setBody(liveTerminalBody))
            server.enqueue(MockResponse().setResponseCode(400).setBody(invalidCursorBody))

            val drain = api(server).messagesRawPage("ses_1")

            assertEquals(2, drain.pages)
            assertEquals(1, drain.messages.size)
            assertFalse(drain.truncated)
            assertEquals("the terminal page is the last request", 2, server.requestCount)

            val first = server.takeRequest()
            assertEquals("asc", first.requestUrl!!.queryParameter("order"))
            assertNull(first.requestUrl!!.queryParameter("cursor"))
            val follow = server.takeRequest()
            assertEquals(liveNext, follow.requestUrl!!.queryParameter("cursor"))
            assertEquals(1, follow.requestUrl!!.querySize)
        }
    }

    /**
     * Two drains of the same session do not share a cursor: the second starts
     * from page 1 (`order=asc`, no `cursor`), because [V2Api.messagesRawPage]
     * keeps the cursor local to one call — a cursor retained across reads would
     * skip every row before it.
     */
    @Test
    fun everyDrainStartsFromPageOneAndNeverReusesACursor() {
        MockWebServer().use { server ->
            repeat(2) {
                server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[$liveLastRow],$liveCursor}"""))
                server.enqueue(MockResponse().setResponseCode(200).setBody(liveTerminalBody))
            }
            val api = api(server)
            api.messagesRawPage("ses_1")
            api.messagesRawPage("ses_1")

            assertEquals(4, server.requestCount)
            val urls = (0 until 4).map { server.takeRequest().requestUrl!! }
            for (start in listOf(0, 2)) {
                assertEquals("asc", urls[start].queryParameter("order"))
                assertNull("a later drain must restart, not resume", urls[start].queryParameter("cursor"))
                assertEquals(liveNext, urls[start + 1].queryParameter("cursor"))
            }
        }
    }

    /**
     * A truncated drain keeps the same rules: it stops at the [V2Api.messagesRawPage]
     * cap with the server still offering a cursor, and — since a real cursor is
     * well-formed — never treats it as a malformed one.
     */
    @Test
    fun thePageCapStillReportsTruncationWithAWellFormedCursorLeftOver() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[$liveLastRow],$liveCursor}"""))
            val drain = api(server).messagesRawPage("ses_1", maxPages = 1)

            assertTrue(drain.truncated)
            assertEquals(1, drain.pages)
            assertEquals(1, server.requestCount)
        }
    }

    // ------------------------------------------------- no garbage reaches the wire

    /**
     * The cursor the drain echoes is the server's own `cursor.next` and nothing
     * else, so the only value it must ever refuse is one the server did not
     * issue. `"null"` is that value — the exact string the Android build derived
     * from the terminal page's JSON `null`, measured live on 4095 as
     * `400 InvalidCursorError` (see [invalidCursorBody]). It never reaches the
     * wire: the page below carries it as its `next`, and the drain stops with
     * the queued 400 response still untouched.
     */
    @Test
    fun theStringifiedNullSentinelNeverReachesTheWire() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"data":[$liveLastRow],"cursor":{"previous":null,"next":"null"}}""",
                ),
            )
            server.enqueue(MockResponse().setResponseCode(400).setBody(invalidCursorBody))

            val drain = api(server).messagesRawPage("ses_1")

            assertEquals(1, drain.pages)
            assertEquals(1, drain.messages.size)
            assertFalse("a dropped cursor is not a page-cap truncation", drain.truncated)
            assertEquals("no follow-up request for an unissued cursor", 1, server.requestCount)
        }
    }

    /**
     * `InvalidCursorError` is **not retried**: one request, one failure carrying
     * the server's own words, and the marker callers branch on. A cursor is
     * client-side state, so repeating the identical request can only reproduce
     * the identical 400.
     */
    @Test
    fun invalidCursorIsSurfacedOnceAndNeverRetried() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setBody(invalidCursorBody))
            try {
                api(server).messagesRawPage("ses_1")
                fail("expected ApiException")
            } catch (e: V2Api.ApiException) {
                assertEquals(400, e.code)
                assertEquals("HTTP 400 InvalidCursorError: Invalid cursor", e.message)
                assertTrue("the retry-averse marker must be set", e.isInvalidCursor)
            }
            assertEquals("no retry of a rejected cursor", 1, server.requestCount)
        }
    }

    /** A 400 that is *not* a cursor rejection does not claim to be one. */
    @Test
    fun otherErrorsAreNotMarkedAsCursorRejections() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(400).setBody("""{"_tag":"InvalidRequestError","message":"Missing key"}"""),
            )
            try {
                api(server).messagesRawPage("ses_1")
                fail("expected ApiException")
            } catch (e: V2Api.ApiException) {
                assertFalse(e.isInvalidCursor)
            }
        }
    }

    // ------------------------------------------------------------------ single flight

    /**
     * ⋮ Export and the context tab can request the same frame in the same
     * moment; they must share one drain rather than each paging the transcript.
     * Three requests (session + two message pages) for two concurrent readers.
     */
    @Test
    fun concurrentExportReadsShareASingleDrain() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(sessionBody))
            server.enqueue(
                MockResponse().setResponseCode(200).setBody("""{"data":[$liveLastRow],$liveCursor}""")
                    .setBodyDelay(200, TimeUnit.MILLISECONDS),
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody(liveTerminalBody))

            val transport = V2Transport(api(server))
            val start = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            val readers = List(2) {
                Thread {
                    try {
                        start.await()
                        val frame = transport.fetchExportFrame("ses_1", null)
                        assertEquals(1, frame.messages.size)
                        assertFalse(frame.truncated)
                    } catch (t: Throwable) {
                        failure.compareAndSet(null, t)
                    }
                }.also { it.start() }
            }
            // Both readers are in flight before either drains: the 200 ms body
            // delay holds the leader's page 1 open while the follower arrives.
            Thread.sleep(50)
            start.countDown()
            readers.forEach { it.join(10_000) }

            failure.get()?.let { throw it }
            assertEquals("session + one shared 2-page drain", 3, server.requestCount)
        }
    }

    /**
     * Followers see the leader's failure as the leader's own exception type, not
     * a generic `IOException` wrapper, and a failed drain is shared too: two
     * readers, one session read and one rejected page.
     */
    @Test
    fun aFailedDrainIsSharedAndKeepsItsCursorRejectionOnTheFollower() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(sessionBody))
            server.enqueue(
                MockResponse().setResponseCode(400).setBody(invalidCursorBody)
                    .setBodyDelay(200, TimeUnit.MILLISECONDS),
            )

            val transport = V2Transport(api(server))
            val start = CountDownLatch(1)
            val failures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
            val readers = List(2) {
                Thread {
                    try {
                        start.await()
                        transport.fetchExportFrame("ses_1", null)
                        failures.add(AssertionError("the rejected drain must not return a frame"))
                    } catch (t: Throwable) {
                        failures.add(t)
                    }
                }.also { it.start() }
            }
            Thread.sleep(50)
            start.countDown()
            readers.forEach { it.join(10_000) }

            assertEquals(2, failures.size)
            for (t in failures) {
                val api = t as? V2Api.ApiException ?: throw AssertionError("expected ApiException, got $t")
                assertEquals(400, api.code)
                assertEquals("HTTP 400 InvalidCursorError: Invalid cursor", api.message)
                assertTrue(api.isInvalidCursor)
            }
            assertEquals("session + one shared failing page", 2, server.requestCount)
        }
    }
}
