package com.xiufeigo.opencodeshell

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * W4-A data layer, wire side: the provider catalog's `limit.context` (the usage
 * ring's denominator), the v1 `limit` query on the two list endpoints, the
 * `DELETE /session/:id` call, and the Basic-auth fix that used to send the
 * literal `"null"` credential.
 */
class OpenCodeClientWave4Test {

    private fun client(
        server: MockWebServer,
        username: String? = null,
        password: String? = null,
    ) = OpenCodeClient(server.url("/").toString().toHttpUrlOrNull()!!, username, password)

    // ------------------------------------------- 3. `limit.context` (ring)

    @Test
    fun providerCatalogCarriesLimitContextForTheUsageRing() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"location":{},"data":[
                        {"id":"anthropic","name":"Anthropic","integrationID":"int-a"}]}""",
                ),
            )
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"location":{},"data":[
                        {"id":"claude-sonnet-4","modelID":"claude-sonnet-4",
                         "providerID":"anthropic","name":"Claude Sonnet 4",
                         "limit":{"context":200000,"output":8192}},
                        {"id":"claude-haiku-3.5","modelID":"claude-haiku-3.5",
                         "providerID":"anthropic","name":"Claude Haiku 3.5"}]}""",
                ),
            )
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"location":{},"data":[
                        {"id":"int-a","name":"A","methods":[],"connections":[{}]}]}""",
                ),
            )
            val c = client(server)
            val providers = c.providers()
            assertEquals(
                listOf("/api/provider", "/api/model", "/api/integration"),
                listOf(server.takeRequest(), server.takeRequest(), server.takeRequest())
                    .map { it.requestUrl!!.encodedPath },
            )

            val sonnet = providers.model("anthropic", "claude-sonnet-4")!!
            assertEquals(200_000, sonnet.limitContext)
            assertEquals(8_192, sonnet.limitOutput)

            // A model without `limit` reports unknown, never a fake 0.
            val haiku = providers.model("anthropic", "claude-haiku-3.5")!!
            assertNull(haiku.limitContext)
            assertNull(haiku.limitOutput)

            // `ChatViewModel.contextLimit` is this lookup; its pure core is what
            // the flow evaluates, so assert it against the real server payload.
            assertEquals(200_000, ChatViewModel.contextLimitOf(providers, "anthropic" to "claude-sonnet-4"))
            assertNull(ChatViewModel.contextLimitOf(providers, "anthropic" to "claude-haiku-3.5"))
            assertNull(ChatViewModel.contextLimitOf(providers, "anthropic" to "missing"))
            assertNull(ChatViewModel.contextLimitOf(providers, "missing" to "claude-sonnet-4"))
            assertNull(ChatViewModel.contextLimitOf(providers, null))
            assertNull("catalog not loaded yet", ChatViewModel.contextLimitOf(null, "anthropic" to "claude-sonnet-4"))

            c.close()
        }
    }

    @Test
    fun limitContextIsNullWhenAbsentOrNonPositive() {
        val providers = OpenCodeClient.Providers.fromJson(
            JSONObject(
                """
                {"all":[{"id":"p","name":"P","models":{
                  "a":{"id":"a","name":"A"},
                  "b":{"id":"b","name":"B","limit":{"context":0,"output":-1}},
                  "c":{"id":"c","name":"C","limit":{"context":128000}}}}],
                 "connected":["p"],"default":{}}
                """.trimIndent(),
            ),
        )
        assertNull(providers.model("p", "a")!!.limitContext)
        assertNull(providers.model("p", "b")!!.limitContext)
        assertNull(providers.model("p", "b")!!.limitOutput)
        assertEquals(128_000, providers.model("p", "c")!!.limitContext)
        assertNull(providers.model("p", "c")!!.limitOutput)
    }

    @Test
    fun providerCatalogToleratesAllAsArrayOrObject() {
        // The shape this server really sends (verified against opencode 1.18.31).
        val array = OpenCodeClient.Providers.fromJson(
            JSONObject(
                """{"all":[{"id":"a","name":"A","models":{}}],"connected":["a"],"default":{"a":"m"}}""",
            ),
        )
        assertEquals(listOf("a"), array.all.map { it.id })
        assertEquals(mapOf("a" to "m"), array.default)

        // …and an id→provider map, mirroring the `models` field's shape.
        val objectMap = OpenCodeClient.Providers.fromJson(
            JSONObject(
                """{"all":{"a":{"name":"A"},"b":{"id":"b","name":"B"}},"connected":["a","b"],"default":{}}""",
            ),
        )
        assertEquals(listOf("a", "b"), objectMap.all.map { it.id })
        assertEquals("A", objectMap.all[0].name)
        assertEquals("B", objectMap.all[1].name)
        assertEquals(listOf("a", "b"), objectMap.connectedProviders().map { it.id })
    }

    // ------------------------------------------------------ 4. `limit` query

    @Test
    fun messageListSendsTheSnapshotLimit() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[]}"""))
            val c = client(server)
            assertTrue(c.messages("ses_1", limit = 200).isEmpty())
            val url = server.takeRequest().requestUrl!!
            assertEquals("/api/session/ses_1/message", url.encodedPath)
            assertEquals("200", url.queryParameter("limit"))
            c.close()
        }
    }

    @Test
    fun limitQueryIsOmittedForNullOrNonPositiveValues() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[]}"""))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[]}"""))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[]}"""))
            val c = client(server)

            // Default arguments keep every existing caller on the full-history
            // path they had before.
            c.messages("ses_1")
            assertNull(server.takeRequest().requestUrl!!.queryParameter("limit"))
            c.messages("ses_1", limit = 0)
            assertNull(server.takeRequest().requestUrl!!.queryParameter("limit"))
            c.listSessions()
            assertNull(server.takeRequest().requestUrl!!.queryParameter("limit"))

            c.close()
        }
    }

    @Test
    fun sessionListSendsLimitNextToDirectory() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[]}"""))
            val c = client(server)
            assertTrue(c.listSessions(directory = "/w", limit = 50).isEmpty())
            val url = server.takeRequest().requestUrl!!
            assertEquals("/api/session", url.encodedPath)
            assertEquals("50", url.queryParameter("limit"))
            assertEquals("/w", url.queryParameter("directory"))
            c.close()
        }
    }

    // ------------------------------------------------------- 5. Basic auth

    /** Issues one `GET /session` with these credentials and returns its header. */
    private fun recordedAuth(server: MockWebServer, username: String?, password: String?): String? {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
        val c = client(server, username, password)
        c.listSessions()
        val auth = server.takeRequest().getHeader("Authorization")
        c.close()
        return auth
    }

    private fun expectedBasic(user: String, password: String): String =
        "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray(Charsets.UTF_8))

    private fun decoded(header: String): String =
        String(Base64.getDecoder().decode(header.removePrefix("Basic ")), Charsets.UTF_8)

    @Test
    fun authorizationIsOnlySentWhenAPasswordExists() {
        MockWebServer().use { server ->
            // Upstream sends the header only for a password
            // (`_upstream/packages/app/src/runtime/server/health.test.ts:29`).
            assertNull("no credentials at all", recordedAuth(server, null, null))
            assertNull("username without a password", recordedAuth(server, "opencode", null))
            assertNull("blank password", recordedAuth(server, "opencode", ""))
        }
    }

    @Test
    fun authorizationUsesTheUpstreamOpencodeUsername() {
        MockWebServer().use { server ->
            // `btoa("opencode:" + password)` — the username is not configurable
            // upstream (`app/src/runtime/server/api.ts:6`).
            assertEquals(expectedBasic("opencode", "s3cret"), recordedAuth(server, null, "s3cret"))
            // A server entry carrying its own non-empty username still works.
            assertEquals(expectedBasic("custom", "s3cret"), recordedAuth(server, "custom", "s3cret"))
        }
    }

    @Test
    fun authorizationNeverSpellsOutTheStringNull() {
        MockWebServer().use { server ->
            val headers = listOf(
                recordedAuth(server, null, null),
                recordedAuth(server, "opencode", null),
                recordedAuth(server, null, "s3cret"),
                recordedAuth(server, "custom", "s3cret"),
            )
            for (header in headers) {
                if (header != null) {
                    // The old `"$username:$password"` built "null:null" / ":null"
                    // and handed it to unauthenticated servers.
                    assertTrue("credential must not contain 'null': $header", !decoded(header).contains("null"))
                }
            }
        }
    }

    // ------------------------------------------------------------ delete call

    @Test
    fun deleteSessionSendsDeleteWithoutLocationScope() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            val c = client(server)
            assertTrue(c.deleteSession("ses_1", directory = "/w"))
            val recorded = server.takeRequest()
            assertEquals("DELETE", recorded.method)
            assertEquals("/api/session/ses_1", recorded.requestUrl!!.encodedPath)
            // Session-scoped routes take no location: the directory is ignored.
            assertNull(recorded.requestUrl!!.queryParameter("directory"))
            assertNull(recorded.requestUrl!!.queryParameter("location[directory]"))
            c.close()
        }
    }

    @Test
    fun deleteSessionThrowsOnServerError() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
            val c = client(server)
            assertThrows(OpenCodeClient.ApiException::class.java) { c.deleteSession("ses_1") }
            c.close()
        }
    }

    // ------------------------------------------- session/message info fields

    @Test
    fun sessionInfoCarriesCostForTheContextTooltip() {
        val session = OpenCodeClient.Session.fromJson(
            JSONObject("""{"id":"ses_1","title":"T","directory":"/w","cost":0.7695648,"time":{"updated":5}}"""),
        )
        assertEquals(0.7695648, session.cost!!, 1e-9)
        assertEquals(5L, session.updatedAt)
        // Unknown cost stays unknown rather than becoming 0.0.
        assertNull(OpenCodeClient.Session.fromJson(JSONObject("""{"id":"ses_1"}""")).cost)
    }

    @Test
    fun messageInfoCarriesAgentModelDurationAndInterrupted() {
        val assistant = OpenCodeClient.Message.fromJson(
            JSONObject(
                """{"info":{"id":"m1","role":"assistant","agent":"build","providerID":"openai",
                   "modelID":"gpt-5","time":{"created":10,"completed":20},
                   "error":{"name":"MessageAbortedError","data":{"message":"Stopped"}}},"parts":[]}""",
            ),
        )
        assertEquals("build", assistant.agent)
        assertEquals("openai", assistant.providerID)
        assertEquals("gpt-5", assistant.modelID)
        assertEquals(10L, assistant.timeCreated)
        assertEquals(20L, assistant.timeCompleted)
        assertTrue(assistant.interrupted)

        val running = OpenCodeClient.Message.fromJson(
            JSONObject("""{"info":{"id":"m2","role":"assistant","time":{"created":1}},"parts":[]}"""),
        )
        assertNull(running.timeCompleted)
        assertNull(running.agent)

        val user = OpenCodeClient.Message.fromJson(
            JSONObject(
                """{"info":{"id":"m3","role":"user","agent":"build",
                   "model":{"providerID":"anthropic","modelID":"claude-4"}},"parts":[]}""",
            ),
        )
        assertEquals("anthropic", user.providerID)
        assertEquals("claude-4", user.modelID)
    }
}
