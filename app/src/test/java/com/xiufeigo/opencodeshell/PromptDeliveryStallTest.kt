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
 * BUG-2 (v36 tB): a session's message answered `200 delivery:"steer"` and was
 * never persisted — `GET …/message/{id}` answered `MessageNotFoundError`, the
 * transcript showed the muted 「Queued」 parked-steer row, and nothing else ever
 * happened: no error, no timeout, no action, so the session read as permanently
 * unusable while a fresh session in the same directory was fine.
 *
 * What the client got wrong is pinned here:
 *
 * - **"admitted" is not "delivered"** — the prompt POST's 200 only admits the
 *   row into the session inbox, and the durable message appears at a step
 *   boundary the server may never reach. [pendingDeliveryStalled] is the
 *   bounded judgement (silence **and** a still-pending message), so the wait can
 *   end in a report instead of lasting forever;
 * - **the report is explicit** (`reportStalledDelivery` → an ERROR row on the
 *   session) and **retryable** ([V2Api.updateInboxDelivery]'s
 *   `PATCH …/inbox/{id}{delivery:"steer"}`, which also rings the run's
 *   doorbell), asserted on the wire with [MockWebServer] like [V2ApiTest] does;
 * - **a parked row is not a durable message** ([ChatStore.isPendingRow]) — the
 *   predicate the watchdog's "never persisted" check is built on.
 *
 * No test touches a real server: the transport half runs against
 * [MockWebServer] on loopback and only asserts the request a call produces.
 */
class PromptDeliveryStallTest {

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

    private fun api(server: MockWebServer) = V2Api(
        baseHttpUrl = server.url("/").toString().toHttpUrlOrNull()!!,
        username = null,
        password = null,
        http = V2Api.defaultClient(),
        directory = null,
    )

    private fun RecordedRequest.json(): JSONObject = JSONObject(body.readUtf8())

    private val vm by lazy { source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt") }

    // ------------------------------------------- 1. the bounded stall judgement

    @Test
    fun aStalledDeliveryNeedsBothSilenceAndAStillPendingMessage() {
        val admitted = 1_000L
        assertFalse(
            "inside the window nothing is wrong yet",
            pendingDeliveryStalled(now = admitted + PENDING_DELIVERY_STALL_MS - 1, admittedAt = admitted, lastSessionEventAt = 0),
        )
        assertTrue(
            "the threshold is inclusive: a minute of silence is the first reportable moment",
            pendingDeliveryStalled(now = admitted + PENDING_DELIVERY_STALL_MS, admittedAt = admitted, lastSessionEventAt = 0),
        )
        assertTrue(
            "longer is still stalled",
            pendingDeliveryStalled(now = admitted + 30 * PENDING_DELIVERY_STALL_MS, admittedAt = admitted, lastSessionEventAt = 0),
        )
    }

    @Test
    fun aSessionThatKeepsTalkingIsNeverReported() {
        val admitted = 1_000L
        val now = admitted + PENDING_DELIVERY_STALL_MS * 3
        assertFalse(
            "a steer parked behind a working turn is normal — its frames keep the window open",
            pendingDeliveryStalled(now = now, admittedAt = admitted, lastSessionEventAt = now - 1_000L),
        )
        assertTrue(
            "…and once the session goes quiet the same admission is reportable",
            pendingDeliveryStalled(now = now, admittedAt = admitted, lastSessionEventAt = now - PENDING_DELIVERY_STALL_MS),
        )
    }

    @Test
    fun theWindowIsMeasuredFromTheLaterOfAdmissionAndTraffic() {
        // An event that predates the admission must not shorten the window: the
        // message cannot have been delivered before it was admitted.
        val admitted = 10_000L
        assertFalse(
            pendingDeliveryStalled(now = admitted + PENDING_DELIVERY_STALL_MS - 1, admittedAt = admitted, lastSessionEventAt = 0),
        )
        assertTrue(
            pendingDeliveryStalled(now = admitted + PENDING_DELIVERY_STALL_MS, admittedAt = admitted, lastSessionEventAt = 0),
        )
    }

    @Test
    fun theStallWindowIsTheOneTheReportWasWrittenFor() {
        assertTrue("a parked steer must not wait forever", PENDING_DELIVERY_STALL_MS > 0)
        assertTrue(
            "…but a minute is enough for a healthy delivery (promotion is immediate on an idle session)",
            PENDING_DELIVERY_STALL_MS >= 30_000L,
        )
    }

    // ------------------------------- 2. a parked row is not a durable message

    @Test
    fun theDurabilityCheckExcludesTheParkedRowItself() {
        // The parked row carries the admission id the durable message will have
        // (`pendingSteerRow`), so "the id is in the store" alone would read a
        // never-persisted message as delivered — the measured report's shape.
        val parked = pendingSteerRow(
            JSONObject(
                """{"id":"msg_1","sessionID":"ses_1","time":{"created":1},"type":"user",
                    "payload":{"text":"hi"},"delivery":"steer"}""",
            ),
        )
        assertTrue("the parked row exists", parked != null)
        assertTrue("…and is a pending row", ChatStore.isPendingRow(parked!!))
        assertFalse(
            "a durable user message of the same id is not",
            ChatStore.isPendingRow(parked.copy(role = "user")),
        )
    }

    // ------------------------------------------ 3. the retry, pinned on the wire

    @Test
    fun theRetrySendsTheServersOwnSteerPatch() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            api(server).updateInboxDelivery("ses_1", "msg_1", "steer")

            val request = server.takeRequest()
            assertEquals("PATCH", request.method)
            assertEquals("/api/session/ses_1/inbox/msg_1", request.requestUrl!!.encodedPath)
            assertEquals("steer", request.json().getString("delivery"))
        }
    }

    @Test
    fun aFailedRetrySurfacesAsAnExceptionTheCallerReports() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"boom"}"""))
            val failure = runCatching { api(server).updateInboxDelivery("ses_1", "msg_1", "steer") }
            assertTrue(
                "a failed re-delivery must reach the caller's error path, never a silent no-op",
                failure.isFailure,
            )
        }
    }

    @Test
    fun theSteerAdmissionCarriesItsDeliveryModeOnTheWire() {
        // The mode the report saw (`delivery:"steer"`, the default for an idle
        // session — `ComposerDelivery.delivery(running = false, …)`) and the
        // admission id the watchdog keys on.
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(V2ApiFixtures.PROMPT_ADMITTED))
            val admitted = api(server).prompt("ses_1", text = "hi", delivery = "steer")

            val body = server.takeRequest().json()
            assertEquals("steer", body.getString("delivery"))
            assertEquals("msg_0c27b1da6001O2neNc6Sg8xZ9t", admitted.id)
            assertTrue("the admission id is what the watchdog watches", admitted.id.isNotBlank())
        }
    }

    // ------------------------------------------------------- 4. the VM wiring

    @Test
    fun aSteerAdmissionIsWatchedAndReportedExplicitly() {
        val submit = window(vm, "                // A `delivery=steer` admit is **not** delivered yet either", "            } catch (e: Exception) {")
        assertTrue("the admitted id is watched", submit.contains("watchPendingDelivery(admittedID)"))
        assertTrue("…only for a steer", submit.contains("wireDelivery == \"steer\""))
        assertTrue("…and only for a plain prompt", submit.contains("command == null && !shell"))

        val report = window(vm, "private fun reportStalledDelivery(", "fun retryPendingDelivery(")
        assertTrue(
            "the failure is reported on the session, not swallowed",
            report.contains("ChatStore.Event.RunStatusChanged(") &&
                report.contains("ChatStore.RunStatus.ERROR"),
        )
        assertTrue("the line names the way out", report.contains("Retry"))
    }

    @Test
    fun theWatchJudgesOnlyAQuietIdleSession() {
        val watch = window(vm, "private fun watchPendingDelivery(", "private fun isMessageDurable(")
        assertTrue("a durable message ends the watch", watch.contains("isMessageDurable(id)"))
        assertTrue(
            "a running turn is not judged (a parked steer behind it is normal)",
            watch.contains("ChatStore.RunStatus.RUNNING"),
        )
        assertTrue("a session nobody is watching is not judged", watch.contains("started.get()"))
        assertTrue("the judgement is the pure predicate", watch.contains("pendingDeliveryStalled("))
        assertTrue("one report per admission", watch.contains("watch.stalled = true"))
    }

    @Test
    fun theRetryReKicksTheServerAndReArmsTheWatch() {
        val retry = window(vm, "fun retryPendingDelivery(", "fun abort()")
        assertTrue(
            "the server's own re-delivery route",
            retry.contains("transport.updateInboxDelivery(id, inboxID, \"steer\")"),
        )
        assertTrue("the inbox is re-read so the row can resolve", retry.contains("refreshInbox()"))
        assertTrue(
            "a second stall reports again instead of going quiet",
            retry.contains("watchPendingDelivery(inboxID)"),
        )
        assertTrue("a failed retry is reported, never silent", retry.contains("ChatStore.RunStatus.ERROR"))
    }

    @Test
    fun thePendingMapIsPerSessionState() {
        // A new session must not inherit an admission id it can never see.
        assertEquals(
            "cleared on session switch and on a new session",
            2,
            vm count "pendingDeliveries.clear()",
        )
    }
}
