package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.mcpAdd
import com.xiufeigo.opencodeshell.v2.mcpConnect
import com.xiufeigo.opencodeshell.v2.mcpDisconnect
import com.xiufeigo.opencodeshell.v2.mcpLocalConfig
import com.xiufeigo.opencodeshell.v2.mcpOAuthConfig
import com.xiufeigo.opencodeshell.v2.mcpRemoteConfig
import com.xiufeigo.opencodeshell.v2.mcpRemove
import com.xiufeigo.opencodeshell.v2.mcpResourceCatalog
import com.xiufeigo.opencodeshell.v2.mcpTimeout
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
 * MCP runtime routes (`protocol/src/groups/mcp.ts:23-102`) asserted on the wire
 * against [MockWebServer], same harness as [V2ApiFsWriteTest]. `UNVERIFIED`
 * live: the contract is `mcp.ts` + openapi.json (the write routes were never
 * issued against a real server).
 */
class V2ApiMcpTest {

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

    @Test
    fun addPutsTheWrappedLocalConfigWithServerPathAndLocation() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            api(server, directory = "/w", password = "secret").mcpAdd(
                server = "context7",
                config = mcpLocalConfig(command = listOf("npx", "-y", "@modelcontextprotocol/server-everything")),
            )

            val request = server.takeRequest()
            assertEquals("PUT", request.method)
            assertEquals("/api/experimental/mcp/context7", request.requestUrl!!.encodedPath)
            assertEquals("/w", request.requestUrl!!.queryParameter("location[directory]"))
            val expected = "Basic " + Base64.getEncoder().encodeToString("opencode:secret".toByteArray())
            assertEquals(expected, request.getHeader("Authorization"))
            assertTrue(request.headers["Content-Type"]!!.startsWith("application/json"))

            val body = JSONObject(request.body.readUtf8())
            val config = body.getJSONObject("config")
            assertEquals("local", config.getString("type"))
            assertEquals("npx", config.getJSONArray("command").getString(0))
            assertFalse("schema is additionalProperties:false — omit unset optionals", config.has("cwd"))
        }
    }

    @Test
    fun addAcceptsRemoteConfigsWithOAuthObjectAndLiteralFalse() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setResponseCode(204))
            val api = api(server)

            api.mcpAdd(
                server = "linear",
                config = mcpRemoteConfig(
                    url = "https://mcp.linear.app/sse",
                    headers = mapOf("X-Team" to "acme"),
                    oauth = mcpOAuthConfig(clientId = "cid", callbackPort = 1234),
                ),
            )
            api.mcpAdd(
                server = "plain",
                config = mcpRemoteConfig(url = "https://mcp.example/sse", oauth = false),
            )

            val withOAuth = JSONObject(server.takeRequest().body.readUtf8()).getJSONObject("config")
            assertEquals("remote", withOAuth.getString("type"))
            assertEquals("https://mcp.linear.app/sse", withOAuth.getString("url"))
            assertEquals("acme", withOAuth.getJSONObject("headers").getString("X-Team"))
            val oauth = withOAuth.getJSONObject("oauth")
            assertEquals("cid", oauth.getString("client_id"))
            assertEquals(1234, oauth.getInt("callback_port"))
            assertFalse(oauth.has("scope"))

            val disabled = JSONObject(server.takeRequest().body.readUtf8()).getJSONObject("config")
            assertEquals(false, disabled.get("oauth"))
        }
    }

    @Test
    fun removeDeletesTheNamedServer() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            api(server, directory = "/w").mcpRemove("context7")

            val request = server.takeRequest()
            assertEquals("DELETE", request.method)
            assertEquals("/api/experimental/mcp/context7", request.requestUrl!!.encodedPath)
            assertEquals("/w", request.requestUrl!!.queryParameter("location[directory]"))
            assertEquals(0L, request.bodySize)
        }
    }

    @Test
    fun connectAndDisconnectPostToTheirTogglePathsWithoutABody() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setResponseCode(204))
            val api = api(server, directory = "/w")

            api.mcpConnect("context7")
            api.mcpDisconnect("context7")

            val connect = server.takeRequest()
            assertEquals("POST", connect.method)
            assertEquals("/api/experimental/mcp/context7/connect", connect.requestUrl!!.encodedPath)
            assertEquals("/w", connect.requestUrl!!.queryParameter("location[directory]"))
            assertEquals(0L, connect.bodySize)

            val disconnect = server.takeRequest()
            assertEquals("POST", disconnect.method)
            assertEquals("/api/experimental/mcp/context7/disconnect", disconnect.requestUrl!!.encodedPath)
            assertEquals(0L, disconnect.bodySize)
        }
    }

    @Test
    fun resourceCatalogParsesResourcesAndTemplates() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {"location":{"directory":"/home/xiufeigo"},"data":{
                      "resources":[
                        {"server":"context7","name":"docs","uri":"ctx7://docs/intro","description":"Intro","mimeType":"text/markdown"},
                        {"server":"fs","name":"readme","uri":"file:///readme.md"}
                      ],
                      "templates":[
                        {"server":"fs","name":"file","uriTemplate":"file:///{path}","mimeType":"text/plain"}
                      ]
                    }}
                    """.trimIndent(),
                ),
            )
            val catalog = api(server, directory = "/w").mcpResourceCatalog()

            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/mcp/resource", request.requestUrl!!.encodedPath)
            assertEquals("/w", request.requestUrl!!.queryParameter("location[directory]"))

            assertEquals(2, catalog.resources.size)
            val docs = catalog.resources[0]
            assertEquals("context7", docs.server)
            assertEquals("docs", docs.name)
            assertEquals("ctx7://docs/intro", docs.uri)
            assertEquals("Intro", docs.description)
            assertEquals("text/markdown", docs.mimeType)
            val readme = catalog.resources[1]
            assertNull(readme.description)
            assertNull(readme.mimeType)

            assertEquals(1, catalog.templates.size)
            val template = catalog.templates[0]
            assertEquals("fs", template.server)
            assertEquals("file", template.name)
            assertEquals("file:///{path}", template.uriTemplate)
            assertNull(template.description)
            assertEquals("text/plain", template.mimeType)
        }
    }

    @Test
    fun resourceCatalogToleratesMissingArrays() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"location":{"directory":"/home/xiufeigo"},"data":{"resources":[],"templates":[]}}"""),
            )
            val catalog = api(server).mcpResourceCatalog()
            assertTrue(catalog.resources.isEmpty())
            assertTrue(catalog.templates.isEmpty())
        }
    }

    @Test
    fun configBuildersEmitOnlyTheSuppliedOptionalFields() {
        val local = mcpLocalConfig(
            command = listOf("node", "server.js"),
            cwd = "/opt/mcp",
            environment = mapOf("TOKEN" to "t"),
            disabled = true,
            codemode = false,
            timeout = mcpTimeout(startupMs = 1_000, catalogMs = 2_000, executionMs = 3_000),
            protocol = "auto",
        )
        assertEquals("local", local.getString("type"))
        assertEquals(2, local.getJSONArray("command").length())
        assertEquals("/opt/mcp", local.getString("cwd"))
        assertEquals("t", local.getJSONObject("environment").getString("TOKEN"))
        assertEquals(true, local.getBoolean("disabled"))
        assertEquals(false, local.getBoolean("codemode"))
        val timeout = local.getJSONObject("timeout")
        assertEquals(1_000, timeout.getInt("startup"))
        assertEquals(2_000, timeout.getInt("catalog"))
        assertEquals(3_000, timeout.getInt("execution"))
        assertEquals("auto", local.getString("protocol"))

        val bare = mcpLocalConfig(command = listOf("npx"))
        assertEquals(setOf("type", "command"), bare.keys().asSequence().toSet())

        val remote = mcpRemoteConfig(url = "https://x/sse", timeout = mcpTimeout(executionMs = 500), protocol = "2026-07-28")
        assertEquals("remote", remote.getString("type"))
        assertEquals(500, remote.getJSONObject("timeout").getInt("execution"))
        assertFalse(remote.has("headers"))
        assertFalse(remote.has("oauth"))

        val oauth = mcpOAuthConfig(scope = "mcp.read", redirectUri = "http://127.0.0.1:1234/cb", authServerMetadataUrl = "https://id/.well-known")
        assertEquals("mcp.read", oauth.getString("scope"))
        assertEquals("http://127.0.0.1:1234/cb", oauth.getString("redirect_uri"))
        assertEquals("https://id/.well-known", oauth.getString("auth_server_metadata_url"))
        assertFalse(oauth.has("client_id"))
    }

    @Test
    fun unknownServerSurfacesTheMcpServerNotFoundTag() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(404).setBody(
                    """{"_tag":"McpServerNotFoundError","server":"nope","message":"MCP server not found: nope"}""",
                ),
            )
            try {
                api(server).mcpRemove("nope")
                fail("expected ApiException")
            } catch (e: V2Api.ApiException) {
                assertEquals(404, e.code)
                assertTrue(e.message!!.contains("McpServerNotFoundError"))
            }
        }
    }

    @Test
    fun withoutAConfiguredDirectoryNoLocationQueryIsSent() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"location":{},"data":{"resources":[],"templates":[]}}"""))
            val api = api(server)

            api.mcpConnect("context7")
            assertNull(server.takeRequest().requestUrl!!.queryParameter("location[directory]"))

            api.mcpResourceCatalog()
            assertNull(server.takeRequest().requestUrl!!.queryParameter("location[directory]"))
        }
    }

    @Test
    fun serverNamesStayOnePathSegment() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            api(server).mcpRemove("team/mcp server")

            val request = server.takeRequest()
            val url = request.requestUrl!!
            assertEquals("/api/experimental/mcp/team%2Fmcp%20server", url.encodedPath)
        }
    }

    @Test
    fun aNonJsonBodyBecomesAParseError() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(V2ApiFixtures.SPA_HTML))
            try {
                api(server).mcpResourceCatalog()
                fail("expected ApiException")
            } catch (e: V2Api.ApiException) {
                assertEquals(V2Api.JSON_BODY_CODE, e.code)
            }
        }
    }
}
