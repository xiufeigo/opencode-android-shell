package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.integrationOauthCancel
import com.xiufeigo.opencodeshell.v2.integrationOauthComplete
import com.xiufeigo.opencodeshell.v2.integrationOauthConnect
import com.xiufeigo.opencodeshell.v2.integrationOauthStatus
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

/**
 * `POST|GET|DELETE /api/integration/{id}/connect/oauth…` (`integration.ts:83-154`)
 * — asserted on the wire against [MockWebServer], same harness as
 * [V2ApiFsWriteTest]. `UNVERIFIED` live for success bodies (no write hits a
 * real server; shapes come from the live 2.0.11 openapi
 * `Integration.AttemptEncoded`/`Integration.AttemptStatus`); the 404 bodies are
 * Measured live 2026-09-23 and reproduced below.
 */
class V2ApiIntegrationOauthTest {

    private fun api(
        server: MockWebServer,
        directory: String? = null,
        username: String? = null,
        password: String? = null,
    ) = V2Api(
        baseHttpUrl = server.url("/").toString().toHttpUrlOrNull()!!,
        username = username,
        password = password,
        http = V2Api.defaultClient(),
        directory = directory,
    )

    /** `{location,data:Integration.Attempt}` per openapi `Integration.AttemptEncoded`. */
    private val attemptBody = """
        {"location":{"directory":"/w"},"data":{"attemptID":"con_0c1","url":"https://opencode.ai/oauth/device",
         "instructions":"Visit the URL and approve the device","mode":"code",
         "time":{"created":1789994974390,"expires":1789995574390}}}
    """.trimIndent()

    // ---------------------------------------------------------------- connect

    @Test
    fun connectPostsMethodIdAnswerAndLabelAndParsesTheAttempt() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(attemptBody))
            val attempt = api(server, directory = "/w", password = "secret").integrationOauthConnect(
                integrationID = "opencode",
                methodID = "device",
                answer = mapOf("server" to "https://opencode.ai/console", "port" to 8080),
                label = "Console",
            )

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/integration/opencode/connect/oauth", request.requestUrl!!.encodedPath)
            assertEquals("/w", request.requestUrl!!.queryParameter("location[directory]"))
            assertEquals("application/json", request.headers["Content-Type"]?.substringBefore(";"))
            val expected = "Basic " + Base64.getEncoder().encodeToString("opencode:secret".toByteArray())
            assertEquals(expected, request.getHeader("Authorization"))

            val body = JSONObject(request.body.readUtf8())
            assertEquals("device", body.getString("methodID"))
            assertEquals("Console", body.getString("label"))
            val answer = body.getJSONObject("answer")
            assertEquals("https://opencode.ai/console", answer.getString("server"))
            assertEquals(8080, answer.getInt("port"))

            assertEquals("con_0c1", attempt.attemptID)
            assertEquals("https://opencode.ai/oauth/device", attempt.url)
            assertEquals("Visit the URL and approve the device", attempt.instructions)
            assertEquals("code", attempt.mode)
            assertEquals(1789994974390.0, attempt.timeCreated, 0.0)
            assertEquals(1789995574390.0, attempt.timeExpires, 0.0)
            assertEquals("con_0c1", attempt.raw.getString("attemptID"))
        }
    }

    @Test
    fun connectWithoutAnswerOrLabelOmitsTheOptionalKeys() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(attemptBody))
            api(server).integrationOauthConnect(integrationID = "opencode", methodID = "device")

            val request = server.takeRequest()
            val body = JSONObject(request.body.readUtf8())
            assertEquals(setOf("methodID"), body.keySet())
            assertNull("absent answer/label, not null values", body.opt("answer"))
            assertNull(request.requestUrl!!.queryParameter("location[directory]"))
        }
    }

    // ----------------------------------------------------------------- status

    @Test
    fun statusGetReadsEveryAttemptStatusVariant() {
        MockWebServer().use { server ->
            val times = """"time":{"created":1,"expires":2}"""
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"location":{"directory":"/w"},"data":{"status":"pending",$times}}"""),
            )
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"data":{"status":"failed","message":"denied",$times}}"""),
            )
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"data":{"status":"expired",$times}}"""),
            )
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"data":{"status":"complete","time":{"created":1,"expires":"Infinity"}}}"""),
            )
            val a = api(server, directory = "/w")
            val pending = a.integrationOauthStatus("opencode", "con_1")
            val failed = a.integrationOauthStatus("opencode", "con_1")
            val expired = a.integrationOauthStatus("opencode", "con_1")
            val complete = a.integrationOauthStatus("opencode", "con_1")

            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/integration/opencode/connect/oauth/con_1", request.requestUrl!!.encodedPath)
            assertEquals("/w", request.requestUrl!!.queryParameter("location[directory]"))
            assertEquals(0L, request.bodySize)

            assertEquals("pending", pending.status)
            assertNull(pending.message)
            assertEquals(1.0, pending.timeCreated, 0.0)
            assertEquals("failed", failed.status)
            assertEquals("denied", failed.message)
            assertEquals("expired", expired.status)
            assertNull(expired.message)
            assertEquals("complete", complete.status)
            // `Schema.Number` widens to the `"Infinity"` string on the wire.
            assertEquals(Double.POSITIVE_INFINITY, complete.timeExpires, 0.0)
        }
    }

    // ----------------------------------------------------------------- cancel

    @Test
    fun cancelSendsDeleteWithAnEmptyBody() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            api(server, directory = "/w").integrationOauthCancel("opencode", "con_1")

            val request = server.takeRequest()
            assertEquals("DELETE", request.method)
            assertEquals("/api/integration/opencode/connect/oauth/con_1", request.requestUrl!!.encodedPath)
            assertEquals("/w", request.requestUrl!!.queryParameter("location[directory]"))
            assertEquals(0L, request.bodySize)
        }
    }

    // --------------------------------------------------------------- complete

    @Test
    fun completePostsTheCodeAndAccepts204() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            api(server, directory = "/w").integrationOauthComplete("opencode", "con_1", code = "ABCD-1234")

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals(
                "/api/integration/opencode/connect/oauth/con_1/complete",
                request.requestUrl!!.encodedPath,
            )
            assertEquals("/w", request.requestUrl!!.queryParameter("location[directory]"))
            assertEquals("application/json", request.headers["Content-Type"]?.substringBefore(";"))
            assertEquals("""{"code":"ABCD-1234"}""", request.body.readUtf8())
        }
    }

    @Test
    fun completeWithoutCodeStillPostsTheEmptyObjectBody() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            api(server).integrationOauthComplete("opencode", "con_1")

            val request = server.takeRequest()
            // openapi `requestBody` is required for this route (`integration.ts:126`
            // wraps `code` in `optional`) — the body object is present either way.
            assertEquals("{}", request.body.readUtf8())
        }
    }

    // ----------------------------------------------------------------- errors

    @Test
    fun measuredLiveErrorBodiesSurfaceAsApiException() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(404).setBody(
                    """
                    {"_tag":"IntegrationAttemptNotFoundError","integrationID":"opencode",
                     "attemptID":"con_doesnotexist","message":"OAuth attempt not found: con_doesnotexist"}
                    """.trimIndent(),
                ),
            )
            try {
                api(server).integrationOauthStatus("opencode", "con_doesnotexist")
                fail("expected ApiException")
            } catch (e: V2Api.ApiException) {
                assertEquals(404, e.code)
                assertTrue(e.message!!.contains("IntegrationAttemptNotFoundError"))
                assertTrue(e.message!!.contains("OAuth attempt not found"))
            }

            server.enqueue(
                MockResponse().setResponseCode(400).setBody(V2ApiFixtures.MISSING_QUERY_ERROR),
            )
            try {
                api(server).integrationOauthConnect("opencode", "device")
                fail("expected ApiException")
            } catch (e: V2Api.ApiException) {
                assertEquals(400, e.code)
                assertTrue(e.message!!.contains("InvalidRequestError"))
            }
        }
    }

    @Test
    fun nonJsonBodyBecomesAJsonBodyCodeParseError() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(V2ApiFixtures.SPA_HTML))
            try {
                api(server).integrationOauthStatus("opencode", "con_1")
                fail("expected ApiException")
            } catch (e: V2Api.ApiException) {
                assertEquals(
                    "unimplemented paths answer the SPA shell as 200 html",
                    V2Api.JSON_BODY_CODE,
                    e.code,
                )
            }
        }
    }
}
