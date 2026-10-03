package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.v2.V2Api
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The wire shape of the compaction request the automatic threshold issues
 * (device v19 P2).
 *
 * The route is `POST /api/session/:sessionID/compact`, whose payload is
 * `{id?: SessionMessage.ID, delivery?: SessionInbox.Delivery}`
 * (`_upstream/packages/protocol/src/groups/session.ts:495-513`) and whose own
 * description says *"Steers by default: it runs at the next step boundary instead
 * of waiting behind queued prompts"* — so neither field has to be sent, and
 * upstream's own palette command sends neither
 * (`use-session-commands.tsx:236`). `ChatViewModel.maybeAutoCompact` calls
 * `compactSession()` with no arguments for exactly that reason; this test pins
 * both halves of that claim: the body the client then puts on the wire, and the
 * fact that the automatic path is the empty-body call.
 *
 * The response is `{data: Session.Inbox.Compaction}` — the admitted inbox row,
 * which is why the call is a `dataObject` read and not a 204.
 */
class CompactRequestShapeTest {

    private fun api(server: MockWebServer, directory: String? = null) = V2Api(
        baseHttpUrl = server.url("/").toString().toHttpUrlOrNull()!!,
        username = null,
        password = null,
        http = V2Api.defaultClient(),
        directory = directory,
    )

    /** `RecordedRequest.body` is consumable: read it once. */
    private fun RecordedRequest.json(): JSONObject = JSONObject(body.readUtf8())

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    private val admitted =
        """{"data":{"id":"inb_1","sessionID":"ses_1","time":{"created":1789918264159},"type":"compaction","delivery":"steer"}}"""

    @Test
    fun theEmptyBodyCallIsTheRouteAsUpstreamSendsIt() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(admitted))
            // A configured directory must not leak in: the route is not
            // location-scoped (`postJson(..., withLocation = false)`).
            val row = api(server, directory = """C:\w""").compact("ses_1", null, null)

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/session/ses_1/compact", request.requestUrl!!.encodedPath)
            assertEquals(0, request.requestUrl!!.querySize)
            val body = request.json()
            // Neither optional field is invented: `id` keys the inbox row and
            // `delivery` would override the route's own steer default.
            assertFalse(body.has("id"))
            assertFalse(body.has("delivery"))
            assertEquals(0, body.length())
            // The admitted row comes back inside `data`.
            assertEquals("inb_1", row.getString("id"))
            assertEquals("compaction", row.getString("type"))
        }
    }

    @Test
    fun theOptionalFieldsTravelFlatWhenACallerSuppliesThem() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(admitted))
            api(server).compact("ses_1", "msg_7", "queue")

            val body = server.takeRequest().json()
            assertEquals("msg_7", body.getString("id"))
            assertEquals("queue", body.getString("delivery"))
        }
    }

    @Test
    fun aBlankFieldIsOmittedRatherThanSentEmpty() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(admitted))
            api(server).compact("ses_1", "   ", "")

            val body = server.takeRequest().json()
            assertFalse(body.has("id"))
            assertFalse(body.has("delivery"))
        }
    }

    @Test
    fun theAutomaticPathIsTheEmptyBodyCall() {
        val source = source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")
        // The threshold watcher reads the two halves of the ratio the ring paints…
        assertTrue(source.contains("_activeContextTokens,"))
        assertTrue(source.contains("contextLimit,"))
        // …and re-evaluates when a turn starts, which a two-flow combine could
        // not: the context read repeats the same total for every snapshot of one
        // step, so the run-status gate would be missed for the whole turn.
        assertTrue(source.contains("_state.map { it.runStatus }.distinctUntilChanged()"))
        assertTrue(source.contains("ContextCompactionPolicy.isAdvancing(runStatus)"))
        // …and asks for the compaction through the route's own default body.
        assertTrue(source.contains("compactSession()"))
        // The once-per-turn throttle sits between the two.
        assertTrue(source.contains("compactionTrigger.shouldFire("))
    }
}
