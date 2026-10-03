package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.integrationCommandCancel
import com.xiufeigo.opencodeshell.v2.integrationCommandStatus
import com.xiufeigo.opencodeshell.v2.integrationConnectCommand
import com.xiufeigo.opencodeshell.v2.integrationConnectKey
import com.xiufeigo.opencodeshell.v2.integrationDetail
import com.xiufeigo.opencodeshell.v2.integrationWellknownAdd
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
import java.util.Base64

/**
 * Integration detail + key/command connect routes (`protocol/src/groups/
 * integration.ts:29-44,62-81,155-205`) and `POST /api/experimental/
 * integration/wellknown` (`integration.ts:45-61`) — asserted on the wire
 * against [MockWebServer], same harness as [V2ApiFsWriteTest]. Detail payloads
 * echo the shapes measured live 2026-09-23 on 2.0.11 (`GET /api/integration/
 * openai`, `…/minimax-cn-coding-plan`, `404 …/github`); the `command` method
 * tag and the write routes are the openapi/group contract and are never aimed
 * at a real server.
 */
class V2ApiIntegrationTest {

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

    // ------------------------------------------------------ GET detail

    @Test
    fun integrationDetailFetchesOneIntegrationWithTheLocationQuery() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(INTEGRATION_OPENAI))
            val detail = api(server, directory = "/w").integrationDetail("openai")

            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/integration/openai", request.requestUrl!!.encodedPath)
            assertEquals("/w", request.requestUrl!!.queryParameter("location[directory]"))
            assertEquals("openai", detail.id)
            assertEquals("OpenAI", detail.name)
            assertNull("metadata is optional", detail.metadata)
            assertEquals(listOf("key", "env", "oauth", "oauth"), detail.methods.map { it.type })
            assertTrue(detail.connections.isEmpty())
        }
    }

    @Test
    fun integrationDetailParsesEveryMethodAndConnectionTag() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(INTEGRATION_AZURE))
            val detail = api(server).integrationDetail("azure")

            val (key, env, oauth, command) = detail.methods
            assertEquals("API key", key.label)
            assertEquals("resourceName", key.form!!.getJSONObject(0).getString("key"))
            assertFalse("key methods carry no methodID", key.raw.has("id"))
            assertEquals(listOf("AZURE_API_KEY", "AZURE_COGNITIVE_SERVICES_API_KEY"), env.names)
            assertEquals("device", oauth.id)
            assertEquals("Login with Azure", oauth.label)
            assertEquals(listOf("az", "login"), command.command)
            assertEquals("cli", command.id)

            val (credential, envConnection) = detail.connections
            assertEquals("credential", credential.type)
            assertEquals("cred_0c2eb11930038Ey4QB9Y0cktWp", credential.id)
            assertEquals("API key", credential.label)
            assertEquals("env", envConnection.type)
            assertEquals("AZURE_API_KEY", envConnection.name)
            assertEquals("my-models", detail.metadata!!.getString("tier"))
        }
    }

    @Test
    fun integrationDetailWithoutAConfiguredDirectorySendsNoLocationParameter() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(INTEGRATION_OPENAI))
            api(server).integrationDetail("openai", locationDirectory = "/w")

            val request = server.takeRequest()
            assertEquals("/w", request.requestUrl!!.queryParameter("location[directory]"))
            assertEquals(1, request.requestUrl!!.querySize)
        }

        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(INTEGRATION_OPENAI))
            api(server).integrationDetail("openai")

            assertNull(server.takeRequest().requestUrl!!.query)
        }
    }

    // ------------------------------------------------- POST connect/key

    @Test
    fun connectKeyPostsKeyAnswerAndLabelAndExpects204() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            api(server, directory = "/w", password = "secret").integrationConnectKey(
                integrationID = "azure",
                key = "sk-test",
                answer = mapOf("resourceName" to "my-models"),
                label = "work",
            )

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/integration/azure/connect/key", request.requestUrl!!.encodedPath)
            assertEquals("/w", request.requestUrl!!.queryParameter("location[directory]"))
            assertEquals(
                "Basic " + Base64.getEncoder().encodeToString("opencode:secret".toByteArray()),
                request.getHeader("Authorization"),
            )
            assertEquals("application/json", request.headers["Content-Type"]?.substringBefore(";"))
            val body = JSONObject(request.body.readUtf8())
            assertEquals("sk-test", body.getString("key"))
            assertEquals("work", body.getString("label"))
            assertEquals("my-models", body.getJSONObject("answer").getString("resourceName"))
            assertEquals("connect.key is additionalProperties:false", 3, body.length())
        }
    }

    @Test
    fun connectKeyOmitsAbsentOptionalFieldsAndEncodesListAnswersAsArrays() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            api(server).integrationConnectKey(integrationID = "openai", key = "sk-bare")

            val bare = JSONObject(server.takeRequest().body.readUtf8())
            assertFalse("answer is optional", bare.has("answer"))
            assertFalse("label is optional", bare.has("label"))
            assertEquals(1, bare.length())
        }

        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            api(server).integrationConnectKey(
                integrationID = "openai",
                key = "sk",
                answer = mapOf("scopes" to listOf("read", "write"), "agree" to true, "n" to 2),
            )

            val answer = JSONObject(server.takeRequest().body.readUtf8()).getJSONObject("answer")
            assertEquals(listOf("read", "write"), (0 until answer.getJSONArray("scopes").length()).map {
                answer.getJSONArray("scopes").getString(it)
            })
            assertTrue(answer.getBoolean("agree"))
            assertEquals(2, answer.getInt("n"))
        }
    }

    // -------------------------------------------- POST connect/command

    @Test
    fun connectCommandPostsMethodIdAndLabelAndReadsTheAttempt() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(COMMAND_ATTEMPT))
            val attempt = api(server, directory = "/w").integrationConnectCommand(
                integrationID = "azure",
                methodID = "cli",
                label = "work",
            )

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/integration/azure/connect/command", request.requestUrl!!.encodedPath)
            assertEquals("/w", request.requestUrl!!.queryParameter("location[directory]"))
            val body = JSONObject(request.body.readUtf8())
            assertEquals("cli", body.getString("methodID"))
            assertEquals("work", body.getString("label"))
            assertEquals("command.connect is additionalProperties:false", 2, body.length())
            assertEquals("con_0c2eb11930040AbCdEfGhIjKl", attempt.attemptID)
            assertEquals(1789969237710L, attempt.timeCreated)
            assertEquals(1789970137710L, attempt.timeExpires)
        }
    }

    @Test
    fun connectCommandOmitsTheLabelWhenBlank() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(COMMAND_ATTEMPT))
            api(server).integrationConnectCommand(integrationID = "azure", methodID = "cli")

            val body = JSONObject(server.takeRequest().body.readUtf8())
            assertFalse(body.has("label"))
            assertEquals(1, body.length())
        }
    }

    // --------------------------- GET|DELETE connect/command/{attemptID}

    @Test
    fun commandStatusParsesEveryVariantOfTheStatusUnion() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(COMMAND_STATUS_PENDING))
            server.enqueue(MockResponse().setResponseCode(200).setBody(COMMAND_STATUS_COMPLETE))
            server.enqueue(MockResponse().setResponseCode(200).setBody(COMMAND_STATUS_FAILED))
            server.enqueue(MockResponse().setResponseCode(200).setBody(COMMAND_STATUS_EXPIRED))
            val client = api(server, directory = "/w")

            val pending = client.integrationCommandStatus("azure", "con_1")
            assertEquals("/api/integration/azure/connect/command/con_1", server.takeRequest().requestUrl!!.encodedPath)
            assertTrue(pending.pending)
            assertEquals("Waiting for browser…", pending.message)
            assertEquals(1789969237710L, pending.timeCreated)

            val complete = client.integrationCommandStatus("azure", "con_1")
            server.takeRequest()
            assertTrue(complete.complete)
            assertNull(complete.message)

            val failed = client.integrationCommandStatus("azure", "con_1")
            server.takeRequest()
            assertTrue(failed.failed)
            assertEquals("exit 1", failed.message)

            val expired = client.integrationCommandStatus("azure", "con_1")
            val last = server.takeRequest()
            assertTrue(expired.expired)
            assertEquals("GET", last.method)
            assertEquals("/w", last.requestUrl!!.queryParameter("location[directory]"))
        }
    }

    @Test
    fun commandCancelDeletesTheAttemptAndExpects204() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            api(server, directory = "/w").integrationCommandCancel("azure", "con_1")

            val request = server.takeRequest()
            assertEquals("DELETE", request.method)
            assertEquals("/api/integration/azure/connect/command/con_1", request.requestUrl!!.encodedPath)
            assertEquals("/w", request.requestUrl!!.queryParameter("location[directory]"))
        }
    }

    // ------------------------------------- POST integration/wellknown

    @Test
    fun wellknownAddPostsUrlWithTheLocationQueryAndExpects204() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            api(server, directory = "/w").integrationWellknownAdd("https://example.com/.well-known/opencode")

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/experimental/integration/wellknown", request.requestUrl!!.encodedPath)
            assertEquals("/w", request.requestUrl!!.queryParameter("location[directory]"))
            val body = JSONObject(request.body.readUtf8())
            assertEquals("https://example.com/.well-known/opencode", body.getString("url"))
            assertEquals("wellknown.add is additionalProperties:false", 1, body.length())
        }
    }

    // ------------------------------------------------------------ errors

    @Test
    fun non2xxThrowsApiExceptionKeepingTheErrorTag() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404).setBody(INTEGRATION_NOT_FOUND))
            try {
                api(server).integrationDetail("github")
                fail("expected ApiException")
            } catch (e: V2Api.ApiException) {
                assertEquals(404, e.code)
                assertTrue(e.message!!.contains("IntegrationNotFoundError"))
            }

            server.enqueue(MockResponse().setResponseCode(400).setBody(V2ApiFixtures.MISSING_QUERY_ERROR))
            try {
                api(server).integrationConnectKey(integrationID = "openai", key = "sk")
                fail("expected ApiException")
            } catch (e: V2Api.ApiException) {
                assertEquals(400, e.code)
                assertTrue(e.message!!.contains("InvalidRequestError"))
            }
        }
    }

    @Test
    fun aJsonBodyIsMandatoryOnTheBodyReadingRoutes() {
        MockWebServer().use { server ->
            // `connect/key` is 204 via `callUnit` and never reads the body, so
            // the SPA fallback can only be detected on the parsing routes.
            server.enqueue(MockResponse().setResponseCode(200).setBody(V2ApiFixtures.SPA_HTML))
            try {
                api(server).integrationDetail("openai")
                fail("expected ApiException")
            } catch (e: V2Api.ApiException) {
                assertEquals(
                    "unimplemented paths answer the SPA shell as 200 html",
                    V2Api.JSON_BODY_CODE,
                    e.code,
                )
            }

            server.enqueue(MockResponse().setResponseCode(200).setBody(V2ApiFixtures.SPA_HTML))
            try {
                api(server).integrationCommandStatus("azure", "con_1")
                fail("expected ApiException")
            } catch (e: V2Api.ApiException) {
                assertEquals(V2Api.JSON_BODY_CODE, e.code)
            }
        }
    }

    private companion object {
        /** `GET /api/integration/openai` — measured live 2026-09-23 on 2.0.11. */
        const val INTEGRATION_OPENAI =
            """{"location":{"directory":"/home/xiufeigo"},"data":{"id":"openai","name":"OpenAI","methods":[{"type":"key"},{"type":"env","names":["OPENAI_API_KEY"]},{"id":"chatgpt-browser","type":"oauth","label":"ChatGPT Pro/Plus (browser)"},{"id":"chatgpt-headless","type":"oauth","label":"ChatGPT Pro/Plus (headless)"}],"connections":[]}}"""

        /**
         * `GET /api/integration/azure` composite — `key`+`form` and `env`
         * measured live (azure/minimax-cn-coding-plan); `oauth`+`form`,
         * `command` and the `env` connection are the schema shapes
         * (`schema/src/integration.ts:18-49`, `connection.ts:7-25`) because the
         * live catalog exposes no `command` method or `env` connection.
         */
        const val INTEGRATION_AZURE =
            """{"location":{"directory":"/home/xiufeigo"},"data":{"id":"azure","name":"Azure","metadata":{"tier":"my-models"},"methods":[{"type":"key","label":"API key","form":[{"key":"resourceName","title":"Enter Azure Resource Name","required":true,"type":"string","placeholder":"e.g. my-models"}]},{"type":"env","names":["AZURE_API_KEY","AZURE_COGNITIVE_SERVICES_API_KEY"]},{"id":"device","type":"oauth","label":"Login with Azure","form":[{"key":"tenant","type":"string"}]},{"id":"cli","type":"command","label":"az login","command":["az","login"]}],"connections":[{"type":"credential","id":"cred_0c2eb11930038Ey4QB9Y0cktWp","label":"API key"},{"type":"env","name":"AZURE_API_KEY"}]}}"""

        /** `Integration.CommandAttempt` — `{attemptID, time:{created, expires}}` (`integration.ts:155-174`). */
        const val COMMAND_ATTEMPT =
            """{"location":{"directory":"/home/xiufeigo"},"data":{"attemptID":"con_0c2eb11930040AbCdEfGhIjKl","time":{"created":1789969237710,"expires":1789970137710}}}"""

        /** `CommandAttemptStatus` variants (`schema/src/integration.ts:109-117`). */
        const val COMMAND_STATUS_PENDING =
            """{"location":{"directory":"/home/xiufeigo"},"data":{"status":"pending","message":"Waiting for browser…","time":{"created":1789969237710,"expires":1789970137710}}}"""

        const val COMMAND_STATUS_COMPLETE =
            """{"location":{"directory":"/home/xiufeigo"},"data":{"status":"complete","time":{"created":1789969237710,"expires":1789970137710}}}"""

        const val COMMAND_STATUS_FAILED =
            """{"location":{"directory":"/home/xiufeigo"},"data":{"status":"failed","message":"exit 1","time":{"created":1789969237710,"expires":1789970137710}}}"""

        const val COMMAND_STATUS_EXPIRED =
            """{"location":{"directory":"/home/xiufeigo"},"data":{"status":"expired","time":{"created":1789969237710,"expires":1789970137710}}}"""

        /** `GET /api/integration/github` — measured live 404 on 2.0.11. */
        const val INTEGRATION_NOT_FOUND =
            """{"_tag":"IntegrationNotFoundError","integrationID":"github","message":"Integration not found: github"}"""
    }
}
