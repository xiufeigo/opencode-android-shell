package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.v2.V1MigrationStatus
import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.configUpdate
import com.xiufeigo.opencodeshell.v2.generateText
import com.xiufeigo.opencodeshell.v2.migrationV1Status
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

/**
 * The experimental-misc routes (`protocol/src/groups/{config,generate,
 * migration}.ts`) — asserted on the wire against [MockWebServer],
 * same harness as [V2ApiFsWriteTest]. Migration's bare union is the one shape
 * measured live (2026-09-23, `{"status":"completed"}`); the write routes'
 * request shapes are the openapi/group contract and are never aimed at a real
 * server.
 */
class V2ApiExperimentalMiscTest {

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

    // ------------------------------------------------------ PATCH config

    @Test
    fun configUpdatePatchesTheShellKeyAndExpects204() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            api(server, directory = "/w").configUpdate("bash")

            val request = server.takeRequest()
            assertEquals("PATCH", request.method)
            assertEquals("/api/experimental/config", request.requestUrl!!.encodedPath)
            assertNull("config.update takes no location parameter", request.requestUrl!!.query)
            val body = JSONObject(request.body.readUtf8())
            assertEquals("bash", body.optString("shell", ""))
            assertEquals("Config.Patch is additionalProperties:false", 1, body.length())
        }
    }

    @Test
    fun configUpdateSendsAnExplicitNullShellToClear() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            api(server).configUpdate(null)

            val body = JSONObject(server.takeRequest().body.readUtf8())
            assertTrue("`shell` is a required key even when clearing", body.has("shell"))
            assertTrue(body.isNull("shell"))
        }
    }

    // --------------------------------------------------- POST generate

    @Test
    fun generateTextPostsThePromptAndReadsDataText() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":{"text":"pong"}}"""))
            val text = api(server, directory = "/w").generateText("ping")

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/experimental/generate", request.requestUrl!!.encodedPath)
            assertNull("generate.text takes no location parameter", request.requestUrl!!.query)
            val body = JSONObject(request.body.readUtf8())
            assertEquals("ping", body.getString("prompt"))
            assertFalse("model is omitted when not given", body.has("model"))
            assertEquals(1, body.length())
            assertEquals("pong", text)
        }
    }

    @Test
    fun generateTextSendsModelRefOnlyWhenGiven() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":{"text":"t"}}"""))
            api(server).generateText(
                prompt = "title this",
                model = V2Api.ModelRef(id = "gpt-5.6-sol", providerID = "openai", variant = "default"),
            )

            val body = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals("title this", body.optString("prompt", ""))
            val model = body.getJSONObject("model")
            assertEquals("gpt-5.6-sol", model.optString("id", ""))
            assertEquals("openai", model.optString("providerID", ""))
            assertEquals("default", model.optString("variant", ""))
        }
    }

    // ------------------------------------------------ GET migration/v1

    @Test
    fun migrationStatusReadsTheBareCompletedUnion() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"completed"}"""))
            val status = api(server).migrationV1Status()

            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/experimental/migration/v1", request.requestUrl!!.encodedPath)
            assertTrue(status.completed)
            assertFalse(status.running)
            assertNull(status.progress)
            assertNull(status.error)
        }
    }

    @Test
    fun migrationStatusReadsTheRunningProgressAndErrorVariants() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"status":"running","progress":{"label":"sessions","numerator":3,"denominator":10}}"""),
            )
            val running = api(server).migrationV1Status()
            assertTrue(running.running)
            assertEquals("sessions", running.progress!!.label)
            assertEquals(3L, running.progress!!.numerator!!)
            assertEquals(10L, running.progress!!.denominator!!)

            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"status":"running","progress":{"label":"parts"}}"""),
            )
            val indeterminate = api(server).migrationV1Status()
            assertNull(indeterminate.progress!!.numerator)
            assertNull(indeterminate.progress!!.denominator)

            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"error","error":"db locked"}"""))
            val failed = api(server).migrationV1Status()
            assertTrue(failed.failed)
            assertEquals("db locked", failed.error)

            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"required"}"""))
            assertTrue(api(server).migrationV1Status().required)
        }
    }

    // ------------------------------------------------------------ errors

    @Test
    fun non2xxThrowsApiExceptionKeepingTheTag() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setBody(V2ApiFixtures.MISSING_QUERY_ERROR))
            try {
                api(server).configUpdate("bash")
                fail("expected ApiException")
            } catch (e: V2Api.ApiException) {
                assertEquals(400, e.code)
                assertTrue(e.message!!.contains("InvalidRequestError"))
            }

            server.enqueue(MockResponse().setResponseCode(503).setBody("""{"_tag":"ServiceUnavailableError","message":"no model","service":"llm"}"""))
            try {
                api(server).generateText("ping")
                fail("expected ApiException")
            } catch (e: V2Api.ApiException) {
                assertEquals(503, e.code)
                assertTrue(e.message!!.contains("ServiceUnavailableError"))
            }
        }
    }
}
