package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.composer.ComposerQueue
import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.V2Transport
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WAVE 2.4b (queue/steer VM half): the new wire bodies and the new pure logic.
 *
 * - `V2Api.updateInboxDelivery` PATCHes `…/inbox/{id}{delivery}` and
 *   `V2Api.cancelInbox` DELETEs it (both new; `sessionInbox` GET already existed);
 * - the prompt payload carries mentions-first `files`, `agents[]`, `skills[]`,
 *   `metadata.displayText` and `delivery` (and still omits `resume`);
 * - `V2Transport.sendStructuredPrompt` returns the admitted `msg_` id;
 * - [ComposerQueueHolder.rowFromJson] maps inbox items to panel rows and the
 *   holder publishes add/remove/replace/clear.
 *
 * No test touches a real server (all [MockWebServer]) or Android — same style
 * as [Wave1TransportTest]/[Wave24bComposerQueueTest].
 */
class Wave24bQueueWiringTest {

    private fun RecordedRequest.json(): JSONObject = JSONObject(body.readUtf8())

    private fun v2api(server: MockWebServer, directory: String? = null) = V2Api(
        baseHttpUrl = server.url("/").toString().toHttpUrlOrNull()!!,
        username = "opencode",
        password = "opencode",
        http = V2Api.defaultClient(),
        directory = directory,
    )

    // ------------------------------------------------------- inbox mutations

    @Test
    fun updateInboxDeliveryPatchesDeliveryOnTheInboxItem() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            v2api(server).updateInboxDelivery("ses_1", "msg_1", "steer")
            val request = server.takeRequest()
            assertEquals("PATCH", request.method)
            assertEquals("/api/session/ses_1/inbox/msg_1", request.requestUrl!!.encodedPath)
            assertEquals("steer", request.json().getString("delivery"))
        }
    }

    @Test
    fun cancelInboxDeletesTheInboxItem() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            v2api(server).cancelInbox("ses_1", "msg_1")
            val request = server.takeRequest()
            assertEquals("DELETE", request.method)
            assertEquals("/api/session/ses_1/inbox/msg_1", request.requestUrl!!.encodedPath)
        }
    }

    // ---------------------------------------------------------- prompt payload

    @Test
    fun promptCarriesStructuredMentionsDisplayTextAndDelivery() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(V2ApiFixtures.PROMPT_ADMITTED))
            v2api(server).prompt(
                "ses_1",
                text = "look at @main.kt @build",
                files = listOf(
                    V2Api.PromptFile(uri = "file:///w/main.kt", name = "main.kt"),
                    V2Api.PromptFile(uri = "data:image/png;base64,AAA", name = "shot.png"),
                ),
                agents = listOf(JSONObject().put("name", "build")),
                skills = listOf(JSONObject().put("id", "kb").put("name", "kb")),
                metadata = JSONObject().put("displayText", "look at @main.kt @build"),
                delivery = "queue",
                resume = null,
            )
            val request = server.takeRequest()
            assertEquals("/api/session/ses_1/prompt", request.requestUrl!!.encodedPath)
            val body = request.json()
            assertEquals("look at @main.kt @build", body.getString("text"))
            // Mentions first, then attachments (`request.ts` order).
            val files = body.getJSONArray("files")
            assertEquals(2, files.length())
            assertEquals("file:///w/main.kt", files.getJSONObject(0).getString("uri"))
            assertEquals("data:image/png;base64,AAA", files.getJSONObject(1).getString("uri"))
            assertEquals("build", body.getJSONArray("agents").getJSONObject(0).getString("name"))
            assertEquals("kb", body.getJSONArray("skills").getJSONObject(0).getString("id"))
            assertEquals("kb", body.getJSONArray("skills").getJSONObject(0).getString("name"))
            assertEquals("look at @main.kt @build", body.getJSONObject("metadata").getString("displayText"))
            assertEquals("queue", body.getString("delivery"))
            // `resume=false` is the admit-without-run novelty — NOT this wave.
            assertFalse(body.has("resume"))
        }
    }

    @Test
    fun structuredPromptReturnsTheAdmittedMessageId() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(V2ApiFixtures.PROMPT_ADMITTED))
            val transport = V2Transport(v2api(server))
            val admitted = transport.sendStructuredPrompt(
                "ses_1",
                "Say hi",
                files = listOf(V2Api.PromptFile(uri = "file:///w/a.kt", name = "a.kt")),
                messageID = null,
                delivery = "queue",
                resume = null,
                agents = listOf(JSONObject().put("name", "build")),
                skills = null,
                metadata = JSONObject().put("displayText", "Say hi"),
            )
            assertEquals("msg_0c27b1da6001O2neNc6Sg8xZ9t", admitted)
        }
    }

    @Test
    fun commonPromptShapeOmitsUnsetStructuredFields() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(V2ApiFixtures.PROMPT_ADMITTED))
            val transport = V2Transport(v2api(server))
            val admitted = transport.sendPrompt("ses_1", "Say hi", emptyList(), null, "steer", null)
            assertEquals("msg_0c27b1da6001O2neNc6Sg8xZ9t", admitted)
            val body = server.takeRequest().json()
            assertEquals("steer", body.getString("delivery"))
            assertFalse(body.has("agents"))
            assertFalse(body.has("skills"))
            assertFalse(body.has("metadata"))
            assertFalse(body.has("files"))
        }
    }

    @Test
    fun sendCommandThreadsDeliveryAndFiles() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            val transport = V2Transport(v2api(server))
            transport.sendCommand(
                "ses_1",
                "init",
                "init args",
                files = listOf(V2Api.PromptFile(uri = "file:///w/a.kt", name = "a.kt")),
                delivery = "queue",
            )
            val request = server.takeRequest()
            assertEquals("/api/session/ses_1/command", request.requestUrl!!.encodedPath)
            val body = request.json()
            assertEquals("queue", body.getString("delivery"))
            assertEquals("file:///w/a.kt", body.getJSONArray("files").getJSONObject(0).getString("uri"))
        }
    }

    // ---------------------------------------------------------- inbox mapping

    @Test
    fun inboxRowPrefersDisplayTextAndSumsFileCounts() {
        val row = ComposerQueueHolder.rowFromJson(
            JSONObject(
                """{"id":"msg_1","type":"user","delivery":"queue",
                   |"payload":{"text":"raw text","files":[{"uri":"file:///w/a.kt"},{"uri":"file:///w/b.kt"}],
                   |"metadata":{"displayText":"shown text","attachments":[{"filename":"s.png"}]}}}""".trimMargin(),
            ),
        ) ?: error("expected a row")
        assertEquals("msg_1", row.id)
        assertEquals("shown text", row.text)
        assertEquals(3, row.attachmentCount)
        assertNull(row.submission)
    }

    @Test
    fun inboxRowFallsBackToPayloadText() {
        val row = ComposerQueueHolder.rowFromJson(
            JSONObject("""{"id":"msg_2","type":"user","delivery":"queue","payload":{"text":"plain"}}"""),
        ) ?: error("expected a row")
        assertEquals("plain", row.text)
        assertEquals(0, row.attachmentCount)
    }

    /**
     * Upstream `queue.ts:80-84` shows only parked queue items: a steer
     * admission parked on a busy turn drains on its own and earns no row,
     * and non-user control items never render.
     */
    @Test
    fun inboxRowKeepsOnlyQueuedUserItems() {
        assertNull(
            ComposerQueueHolder.rowFromJson(
                JSONObject("""{"id":"msg_3","type":"user","delivery":"steer","payload":{"text":"x"}}"""),
            ),
        )
        assertNull(
            ComposerQueueHolder.rowFromJson(
                JSONObject("""{"id":"msg_4","type":"control","delivery":"queue","payload":{"text":"x"}}"""),
            ),
        )
    }

    @Test
    fun inboxRowWithoutAnIdIsDropped() {
        assertNull(ComposerQueueHolder.rowFromJson(JSONObject("""{"payload":{"text":"x"}}""")))
        assertNull(ComposerQueueHolder.rowFromJson(JSONObject("""{"id":"","payload":{"text":"x"}}""")))
    }

    // ---------------------------------------------------------- holder state

    @Test
    fun holderPublishesAddRemoveReplaceAndClear() {
        val holder = ComposerQueueHolder()
        assertTrue(holder.rows.value.isEmpty())

        holder.add(ComposerQueue.Row(id = "msg_1", text = "one"))
        holder.add(ComposerQueue.Row(id = "msg_2", text = "two"))
        assertEquals(listOf("msg_1", "msg_2"), holder.rows.value.map { it.id })

        // Unknown ids remove nothing and answer null.
        assertNull(holder.remove("msg_9"))
        assertEquals(2, holder.rows.value.size)

        val removed = holder.remove("msg_1")
        assertEquals("msg_1", removed?.id)
        assertEquals(listOf("msg_2"), holder.rows.value.map { it.id })

        holder.replace(listOf(ComposerQueue.Row(id = "msg_3", text = "three")))
        assertEquals(listOf("msg_3"), holder.rows.value.map { it.id })

        holder.setBusy(true)
        assertTrue(holder.busy.value)
        holder.clear()
        assertTrue(holder.rows.value.isEmpty())
        assertFalse(holder.busy.value)
    }
}
