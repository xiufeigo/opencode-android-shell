package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.VcsBase
import com.xiufeigo.opencodeshell.v2.locationReload
import com.xiufeigo.opencodeshell.v2.vcsBase
import com.xiufeigo.opencodeshell.v2.websearchQuery
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
 * `POST /api/websearch` (`protocol/src/groups/websearch.ts:25-29`,
 * `schema/src/websearch.ts:17-38`), `GET /api/vcs/base`
 * (`protocol/src/groups/vcs.ts:39-42`, `schema/src/vcs.ts:21-25`) and
 * `POST /api/location/reload` (`protocol/src/groups/location.ts:51-53`) —
 * asserted on the wire against [MockWebServer], same harness as [V2ApiTest].
 *
 * `vcs/base`'s null envelope was `Measured live` 2026-09-23 on 2.0.11
 * (`{"location":{"directory":"…"},"data":null}`); everything else is
 * openapi/schema-derived and `UNVERIFIED` live (write routes stay off the real
 * server by policy).
 */
class V2ApiWebsearchVcsTest {

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
    fun websearchPostsQueryAndProviderAndParsesTheResults() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"location":{"directory":"/w"},"data":{"providerID":"brave","results":[{"url":"https://example.com/a","title":"Alpha","content":"first hit","time":{"published":1789918264159}},{"url":"https://example.com/b","time":{}}]}}""",
                ),
            )
            val response = api(server, directory = "/w").websearchQuery(
                query = "opencode v2",
                providerID = "brave",
            )

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/websearch", request.requestUrl!!.encodedPath)
            assertEquals("/w", request.requestUrl!!.queryParameter("location[directory]"))
            val body = JSONObject(request.body.readUtf8())
            assertEquals("opencode v2", body.getString("query"))
            assertEquals("brave", body.getString("providerID"))
            assertEquals("WebSearch.Input is additionalProperties:false", 2, body.length())

            assertEquals("brave", response.providerID)
            assertEquals(2, response.results.size)
            val first = response.results[0]
            assertEquals("https://example.com/a", first.url)
            assertEquals("Alpha", first.title)
            assertEquals("first hit", first.content)
            assertEquals(1789918264159L, first.timePublished)
            val second = response.results[1]
            assertEquals("https://example.com/b", second.url)
            assertNull(second.title)
            assertNull(second.content)
            assertNull("absent time.published is null, not 0", second.timePublished)
        }
    }

    @Test
    fun websearchWithoutAProviderOmitsTheField() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"location":{"directory":"/w"},"data":{"providerID":"default-engine","results":[]}}"""),
            )
            val response = api(server, directory = "/w").websearchQuery("kimi code")

            val body = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals("kimi code", body.getString("query"))
            assertFalse("optional providerID is omitted when blank", body.has("providerID"))
            assertEquals("default-engine", response.providerID)
            assertTrue(response.results.isEmpty())
        }
    }

    @Test
    fun websearchOverrideSuppliesTheOnlyLocationParameter() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"location":{"directory":"/repo"},"data":{"providerID":"brave","results":[]}}"""),
            )
            api(server).websearchQuery("q", providerID = "brave", locationDirectory = "/repo")

            val request = server.takeRequest()
            assertEquals("/repo", request.requestUrl!!.queryParameter("location[directory]"))
            assertEquals(1, request.requestUrl!!.querySize)
        }
    }

    @Test
    fun vcsBaseParsesNameRefAndSource() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"location":{"directory":"/w"},"data":{"name":"v2","ref":"refs/remotes/origin/v2","source":"reflog"}}""",
                ),
            )
            val base = api(server, directory = "/w").vcsBase()

            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/vcs/base", request.requestUrl!!.encodedPath)
            assertEquals("/w", request.requestUrl!!.queryParameter("location[directory]"))
            assertEquals(VcsBase("v2", "refs/remotes/origin/v2", VcsBase.SOURCE_REFLOG), base)
        }
    }

    @Test
    fun vcsBaseReadsJsonNullAndMissingDataAsNoBase() {
        MockWebServer().use { server ->
            // `data:null` — the shape Measured live 2026-09-23 on 2.0.11.
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"location":{"directory":"/home/xiufeigo"},"data":null}"""),
            )
            assertNull(api(server).vcsBase())

            server.enqueue(
                MockResponse().setResponseCode(200).setBody("""{"location":{"directory":"/home/xiufeigo"}}"""),
            )
            assertNull("a missing data key is not an empty Vcs.Base", api(server).vcsBase())
        }
    }

    @Test
    fun locationReloadPostsNoQueryAndAccepts204() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            api(server, directory = "/w", password = "secret").locationReload()

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/location/reload", request.requestUrl!!.encodedPath)
            assertNull("reload is not location-scoped (location.ts:51-53)", request.requestUrl!!.query)
            assertEquals("{}", request.body.readUtf8())
            assertTrue(request.headers["Authorization"]!!.startsWith("Basic "))
        }
    }

    @Test
    fun non2xxThrowsApiExceptionAndAJsonBodyBecomesAParseError() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(400).setBody(
                    """{"_tag":"InvalidRequestError","message":"Web search provider is required","kind":"websearch_provider_required","field":"providerID"}""",
                ),
            )
            try {
                api(server).websearchQuery("q")
                fail("expected ApiException")
            } catch (e: V2Api.ApiException) {
                assertEquals(400, e.code)
                assertTrue(e.message!!.contains("InvalidRequestError"))
            }

            server.enqueue(MockResponse().setResponseCode(503).setBody(""))
            try {
                api(server).locationReload()
                fail("expected ApiException")
            } catch (e: V2Api.ApiException) {
                assertEquals(503, e.code)
            }

            server.enqueue(MockResponse().setResponseCode(200).setBody(V2ApiFixtures.SPA_HTML))
            try {
                api(server).vcsBase()
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
