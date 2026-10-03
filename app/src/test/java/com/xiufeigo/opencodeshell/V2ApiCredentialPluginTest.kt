package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.credentialActivate
import com.xiufeigo.opencodeshell.v2.credentialUpdate
import com.xiufeigo.opencodeshell.v2.pluginCheck
import com.xiufeigo.opencodeshell.v2.pluginUpdate
import com.xiufeigo.opencodeshell.v2.rpcCall
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
 * `PATCH/POST /api/credential/…` (`credential.ts:7-31`), `POST /api/plugin/
 * check|update` (`plugin.ts:24-54`) and `POST /api/rpc/{rpcID}/{method}`
 * (`rpc.ts:14-27`) — asserted on the wire against [MockWebServer], same
 * harness as [V2ApiFsWriteTest]. `UNVERIFIED` live: the contract is the live
 * 2.0.11 openapi document; no write hits a real server.
 */
class V2ApiCredentialPluginTest {

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

    private val pluginCheckBody = """
        {"location":{"directory":"/w","project":{"id":"global","directory":"/"}},"data":[
          {"id":"plugin-a","source":{"type":"package","target":"npm:plugin-a","version":"1.2.3","outdated":true},
           "features":{"server":true,"rpc":true},"state":{"status":"active"}},
          {"source":{"type":"local","path":"C:\\p"},"features":{"tui":true},
           "state":{"status":"failed","error":"boom","ref":"file.ts"}}
        ]}
    """.trimIndent()

    // ------------------------------------------------------------- credential

    @Test
    fun credentialUpdateSendsPatchWithTheLabelJson() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            api(server, directory = "/w", password = "secret").credentialUpdate("cred_x", "Work")

            val request = server.takeRequest()
            assertEquals("PATCH", request.method)
            assertEquals("/api/credential/cred_x", request.requestUrl!!.encodedPath)
            // No `location` query: the credential group takes none (`credential.ts:7-21`).
            assertNull(request.requestUrl!!.queryParameter("location[directory]"))
            assertEquals("application/json", request.headers["Content-Type"]?.substringBefore(";"))
            assertEquals("""{"label":"Work"}""", request.body.readUtf8())
            val expected = "Basic " + Base64.getEncoder().encodeToString("opencode:secret".toByteArray())
            assertEquals(expected, request.getHeader("Authorization"))
        }
    }

    @Test
    fun credentialActivatePostsAnEmptyBody() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            api(server).credentialActivate("cred_y")

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/credential/cred_y/activate", request.requestUrl!!.encodedPath)
            assertNull(request.requestUrl!!.queryParameter("location[directory]"))
            // `credential.ts:22-31` declares no payload — not even `{}`.
            assertEquals(0L, request.bodySize)
            assertEquals("", request.body.readUtf8())
        }
    }

    // ----------------------------------------------------------------- plugin

    @Test
    fun pluginCheckPostsTargetAndParsesTheInfoArray() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(pluginCheckBody))
            val plugins = api(server, directory = "/w").pluginCheck(target = "npm:plugin-a")

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/plugin/check", request.requestUrl!!.encodedPath)
            assertEquals("/w", request.requestUrl!!.queryParameter("location[directory]"))
            assertEquals("application/json", request.headers["Content-Type"]?.substringBefore(";"))
            assertEquals("""{"target":"npm:plugin-a"}""", request.body.readUtf8())

            assertEquals(2, plugins.size)
            val packaged = plugins[0]
            assertEquals("plugin-a", packaged.id)
            assertEquals("package", packaged.sourceType)
            assertEquals("npm:plugin-a", packaged.packageTarget)
            assertEquals("1.2.3", packaged.packageVersion)
            assertTrue("plugin.check refreshes source.outdated", packaged.outdated)
            assertFalse(packaged.updating)
            assertNull(packaged.localPath)
            assertTrue(packaged.featureServer)
            assertFalse(packaged.featureTui)
            assertTrue(packaged.featureRpc)
            assertEquals("active", packaged.stateStatus)
            assertNull(packaged.stateError)

            val local = plugins[1]
            assertNull(local.id)
            assertEquals("local", local.sourceType)
            assertEquals("""C:\p""", local.localPath)
            assertNull(local.packageTarget)
            assertFalse(local.outdated)
            assertTrue(local.featureTui)
            assertEquals("failed", local.stateStatus)
            assertEquals("boom", local.stateError)
            assertEquals("file.ts", local.stateRef)
            assertEquals("""C:\p""", local.raw.getJSONObject("source").getString("path"))
        }
    }

    @Test
    fun pluginCheckWithoutTargetSendsAnEmptyJsonObject() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(pluginCheckBody))
            assertTrue(api(server).pluginCheck().isNotEmpty())

            val request = server.takeRequest()
            val body = JSONObject(request.body.readUtf8())
            assertFalse("absent target, not a null value", body.has("target"))
            assertNull(request.requestUrl!!.queryParameter("location[directory]"))
        }
    }

    @Test
    fun pluginUpdatePostsTheTargetsArray() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            api(server, directory = "/w").pluginUpdate(listOf("npm:plugin-a", "npm:plugin-b"))

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/plugin/update", request.requestUrl!!.encodedPath)
            assertEquals("/w", request.requestUrl!!.queryParameter("location[directory]"))
            assertEquals("""{"targets":["npm:plugin-a","npm:plugin-b"]}""", request.body.readUtf8())
        }
    }

    // -------------------------------------------------------------------- rpc

    @Test
    fun rpcCallPostsInputAndReturnsTheOutputValue() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"output":{"ok":true,"count":2}}"""))
            val output = api(server, directory = "/w").rpcCall(
                rpcID = "my-plugin",
                method = "status",
                input = JSONObject().put("query", "x"),
            )

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/rpc/my-plugin/status", request.requestUrl!!.encodedPath)
            assertEquals("/w", request.requestUrl!!.queryParameter("location[directory]"))
            assertEquals("""{"input":{"query":"x"}}""", request.body.readUtf8())

            val obj = output as JSONObject
            assertTrue(obj.getBoolean("ok"))
            assertEquals(2, obj.getInt("count"))
        }
    }

    @Test
    fun rpcCallOmitsAbsentInputAndMapsMissingOutputToNull() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            val output = api(server).rpcCall("my-plugin", "ping")

            val request = server.takeRequest()
            assertEquals("/api/rpc/my-plugin/ping", request.requestUrl!!.encodedPath)
            val body = JSONObject(request.body.readUtf8())
            assertFalse("`input` is optional (`rpc.ts:7`)", body.has("input"))
            assertNull("`output` absent ⇒ the server sent {}", output)
            assertNull(request.requestUrl!!.queryParameter("location[directory]"))
        }
    }

    @Test
    fun rpcCallKeepsScalarAndJsonNullOutputs() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"output":"done"}"""))
            assertEquals("done", api(server).rpcCall("my-plugin", "run"))

            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"output":null}"""))
            assertEquals(JSONObject.NULL, api(server).rpcCall("my-plugin", "run"))
        }
    }

    // ----------------------------------------------------------------- errors

    @Test
    fun non2xxThrowsApiExceptionAndAJsonBodyBecomesAParseError() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(503).setBody(
                    """{"_tag":"ServiceUnavailableError","message":"Failed to update plugin packages: a","service":"plugin"}""",
                ),
            )
            try {
                api(server).pluginUpdate(listOf("npm:a"))
                fail("expected ApiException")
            } catch (e: V2Api.ApiException) {
                assertEquals(503, e.code)
                assertTrue(e.message!!.contains("ServiceUnavailableError"))
            }

            server.enqueue(
                MockResponse().setResponseCode(400).setBody(
                    """{"_tag":"RpcError","type":"rpc.unknown_method","message":"Unknown method"}""",
                ),
            )
            try {
                api(server).rpcCall("my-plugin", "nope")
                fail("expected ApiException")
            } catch (e: V2Api.ApiException) {
                assertEquals(400, e.code)
                assertTrue(e.message!!.contains("RpcError"))
            }

            server.enqueue(MockResponse().setResponseCode(200).setBody(V2ApiFixtures.SPA_HTML))
            try {
                api(server).pluginCheck()
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
