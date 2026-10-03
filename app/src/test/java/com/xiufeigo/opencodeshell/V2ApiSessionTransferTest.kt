package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.v2.SessionStatsInfo
import com.xiufeigo.opencodeshell.v2.SessionTransferData
import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.sessionExport
import com.xiufeigo.opencodeshell.v2.sessionImport
import com.xiufeigo.opencodeshell.v2.sessionStats
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
 * `experimental/session` transfer + stats (`protocol/src/groups/session.ts:201-273`)
 * asserted on the wire against [MockWebServer], same harness as [V2ApiTest].
 * Bodies are shape-faithful to the live 2.0.11 probes recorded in
 * [V2ApiSessionTransfer] / this file; only ids and free text are shortened.
 */
class V2ApiSessionTransferTest {

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

    /** Shaped from a measured 2.0.11 export: public `Session.Info` + tagged messages. */
    private val transferBody = """
        {"data":{"info":{"id":"ses_a1","projectID":"p1","cost":0.25,
         "tokens":{"input":8234,"output":6,"reasoning":0,"cache":{"read":113,"write":0}},
         "time":{"created":1790068013215,"updated":1790068016441},
         "title":"T","location":{"directory":"/w"}},
         "messages":[
          {"id":"msg_u1","time":{"created":1790068013215},"text":"hi","type":"user"},
          {"id":"msg_a1","time":{"created":1790068013300,"completed":1790068016441},"type":"assistant",
           "agent":"build","model":{"id":"m","providerID":"p","variant":"default"},
           "content":[{"type":"text","id":"pt1","text":"yo"}],"cost":0,"tokens":{"input":1,"output":2,"reasoning":0,"cache":{"read":0,"write":0}}}]}}
    """.trimIndent()

    /** Measured default (`tools` omitted) — `summary` + totals, no `usage`. */
    private val statsSummaryBody = """
        {"data":{"range":{"from":1782720255087,"to":1790156274807},
         "sessions":609,"subagents":3,"prompts":640,"steps":559,
         "tokens":{"input":985872,"output":228490,"reasoning":35717,"cache":{"read":5097101,"write":0}},
         "cost":0.2396801036,
         "tools":{"mode":"summary","totals":{"calls":114,"succeeded":87,"failed":27,"unfinished":0}},
         "activeDays":5,"streak":2,
         "activity":[{"date":"2026-07-15","steps":286}],
         "models":[{"model":{"id":"mimo-v2.5","providerID":"opencode-go","variant":"default"},"steps":212,
          "tokens":{"input":521378,"output":160661,"reasoning":20831,"cache":{"read":3244672,"write":0}},
          "cost":0.13289576159999988}]}}
    """.trimIndent()

    /** Measured `tools=detail` — `usage[]` with an optional `durationP50`. */
    private val statsDetailBody = """
        {"data":{"range":{"from":1,"to":2},"sessions":1,"subagents":0,"prompts":2,"steps":3,
         "tokens":{"input":1,"output":2,"reasoning":3,"cache":{"read":4,"write":5}},
         "cost":0.5,
         "tools":{"mode":"detail","totals":{"calls":2,"succeeded":1,"failed":1,"unfinished":0},
          "usage":[{"name":"shell","calls":2,"succeeded":1,"failed":1,"unfinished":0,"durationP50":120044},
                   {"name":"read","calls":0,"succeeded":0,"failed":0,"unfinished":0}]},
         "activeDays":1,"streak":1,
         "activity":[{"date":"2026-06-29","steps":175}],
         "models":[{"model":{"id":"m","providerID":"p"},"steps":1,
          "tokens":{"input":1,"output":2,"reasoning":3,"cache":{"read":4,"write":5}},"cost":0.1}]}}
    """.trimIndent()

    /** Measured `tools=none` — the union member has no `totals` at all. */
    private val statsNoneBody = """
        {"data":{"range":{"from":0,"to":0},"sessions":0,"subagents":0,"prompts":0,"steps":0,
         "tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}},
         "cost":0,"tools":{"mode":"none"},"activeDays":0,"streak":0,"activity":[],"models":[]}}
    """.trimIndent()

    // ----------------------------------------------------------------- export

    @Test
    fun exportFetchesTheCompleteTransferEnvelopeInOneGet() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(transferBody))
            val transfer = api(server, directory = "/w").sessionExport("ses_a1")

            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/experimental/session/ses_a1/export", request.requestUrl!!.encodedPath)
            assertNull("export is session-scoped, never location[directory]", request.requestUrl!!.queryParameter("location[directory]"))
            assertNull("sanitize omitted means the server's unsanitized default", request.requestUrl!!.queryParameter("sanitize"))

            assertEquals("ses_a1", transfer.info.optString("id"))
            assertEquals("/w", transfer.info.optJSONObject("location")?.optString("directory"))
            assertEquals(2, transfer.messages.size)
            assertEquals("user", transfer.messages[0].optString("type"))
            assertEquals("hi", transfer.messages[0].optString("text"))
            assertEquals("assistant", transfer.messages[1].optString("type"))
            assertEquals("yo", transfer.messages[1].optJSONArray("content")?.optJSONObject(0)?.optString("text"))
        }
    }

    @Test
    fun exportSendsSanitizeAsBooleanFromStringLiterals() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(transferBody))
            api(server).sessionExport("ses_a1", sanitize = true)
            assertEquals("true", server.takeRequest().requestUrl!!.queryParameter("sanitize"))

            server.enqueue(MockResponse().setResponseCode(200).setBody(transferBody))
            api(server).sessionExport("ses_a1", sanitize = false)
            assertEquals("false", server.takeRequest().requestUrl!!.queryParameter("sanitize"))
        }
    }

    @Test
    fun exportSendsBasicAuthAndKeepsASanitizedTitleVerbatim() {
        MockWebServer().use { server ->
            val redacted = """{"data":{"info":{"id":"ses_a1","title":"[redacted:session-title:ses_a1]",
                "location":{"directory":"[redacted:session-directory:ses_a1]"}},
                "messages":[{"id":"msg_u1","type":"user","text":"[redacted:text:msg_u1]",
                "metadata":{"redacted":"message-metadata:msg_u1"}}]}}"""
            server.enqueue(MockResponse().setResponseCode(200).setBody(redacted))
            val transfer = api(server, password = "secret").sessionExport("ses_a1", sanitize = true)

            val request = server.takeRequest()
            val expected = "Basic " + Base64.getEncoder().encodeToString("opencode:secret".toByteArray())
            assertEquals(expected, request.getHeader("Authorization"))
            assertEquals("[redacted:session-title:ses_a1]", transfer.info.optString("title"))
            assertEquals("[redacted:text:msg_u1]", transfer.messages[0].optString("text"))
        }
    }

    @Test
    fun exportPropagatesSessionNotFound() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404).setBody(V2ApiFixtures.SESSION_NOT_FOUND))
            try {
                api(server).sessionExport("ses_doesnotexist")
                fail("expected ApiException")
            } catch (e: V2Api.ApiException) {
                assertEquals(404, e.code)
                assertTrue(e.message!!.contains("SessionNotFoundError"))
            }
        }
    }

    // ----------------------------------------------------------------- import

    @Test
    fun importPostsInfoAndMessagesWithoutALocationByDefault() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(V2ApiFixtures.SESSION_CREATED))
            val created = api(server, directory = "/w").sessionImport(
                SessionTransferData(
                    info = JSONObject("""{"id":"ses_a1","projectID":"p1"}"""),
                    messages = listOf(
                        JSONObject("""{"id":"msg_u1","type":"user","text":"hi"}"""),
                        JSONObject("""{"id":"msg_a1","type":"assistant","content":[]}"""),
                    ),
                ),
            )

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/experimental/session/import", request.requestUrl!!.encodedPath)
            assertNull("import carries location in the body, never location[directory]", request.requestUrl!!.queryParameter("location[directory]"))
            assertEquals("application/json", request.headers["Content-Type"]?.substringBefore(";"))

            val body = JSONObject(request.body.readUtf8())
            assertFalse(body.has("location"))
            assertEquals("ses_a1", body.getJSONObject("info").optString("id"))
            assertEquals(2, body.getJSONArray("messages").length())
            assertEquals("user", body.getJSONArray("messages").getJSONObject(0).optString("type"))
            assertEquals("ses_f3d84f9d4ffeoLtv5OmiMrU6yO", created.id)
        }
    }

    @Test
    fun importPutsTheTargetLocationInTheBody() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(V2ApiFixtures.SESSION_CREATED))
            api(server).sessionImport(
                SessionTransferData(info = JSONObject("""{"id":"ses_a1"}"""), messages = emptyList()),
                locationDirectory = """/home/u/proj""",
            )

            val body = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals("""{"directory":"/home/u/proj"}""", body.getJSONObject("location").toString())
            assertTrue(body.has("info"))
            assertEquals(0, body.getJSONArray("messages").length())
        }
    }

    @Test
    fun importPropagatesConflictAndMissingParentErrors() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(409).setBody("""{"_tag":"ConflictError","message":"Session already exists: ses_a1"}"""),
            )
            try {
                api(server).sessionImport(SessionTransferData(JSONObject("""{"id":"ses_a1"}"""), emptyList()))
                fail("expected ApiException")
            } catch (e: V2Api.ApiException) {
                assertEquals(409, e.code)
                assertTrue(e.message!!.contains("ConflictError"))
            }

            server.enqueue(MockResponse().setResponseCode(404).setBody(V2ApiFixtures.SESSION_NOT_FOUND))
            try {
                api(server).sessionImport(SessionTransferData(JSONObject("""{"id":"ses_kid","parentID":"ses_missing"}"""), emptyList()))
                fail("expected ApiException")
            } catch (e: V2Api.ApiException) {
                assertEquals(404, e.code)
            }
        }
    }

    // ------------------------------------------------------------------ stats

    @Test
    fun statsSendsRangeProjectTimezoneAndToolsAsQueryStrings() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(statsSummaryBody))
            api(server, directory = "/w").sessionStats(
                from = 1782720255087,
                to = 1790156274807,
                project = "p1",
                timezone = "America/Los_Angeles",
                tools = "detail",
            )

            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/experimental/session/stats", request.requestUrl!!.encodedPath)
            assertNull(request.requestUrl!!.queryParameter("location[directory]"))
            assertEquals("1782720255087", request.requestUrl!!.queryParameter("from"))
            assertEquals("1790156274807", request.requestUrl!!.queryParameter("to"))
            assertEquals("p1", request.requestUrl!!.queryParameter("project"))
            assertEquals("America/Los_Angeles", request.requestUrl!!.queryParameter("timezone"))
            assertEquals("detail", request.requestUrl!!.queryParameter("tools"))
        }
    }

    @Test
    fun statsOmitsEveryBlankFilter() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(statsNoneBody))
            api(server).sessionStats()

            val request = server.takeRequest()
            assertEquals(0, request.requestUrl!!.querySize)
        }
    }

    @Test
    fun statsParsesTheMeasuredSummaryShape() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(statsSummaryBody))
            val stats = api(server).sessionStats()

            assertEquals(1782720255087, stats.rangeFrom)
            assertEquals(1790156274807, stats.rangeTo)
            assertEquals(609, stats.sessions)
            assertEquals(3, stats.subagents)
            assertEquals(640, stats.prompts)
            assertEquals(559, stats.steps)
            assertEquals(985872, stats.tokens.input)
            assertEquals(228490, stats.tokens.output)
            assertEquals(35717, stats.tokens.reasoning)
            assertEquals(5097101, stats.tokens.cacheRead)
            assertEquals(0, stats.tokens.cacheWrite)
            assertEquals(0.2396801036, stats.cost, 1e-9)
            assertEquals("summary", stats.toolsMode)
            assertEquals(114, stats.toolsTotals!!.calls)
            assertEquals(87, stats.toolsTotals!!.succeeded)
            assertEquals(27, stats.toolsTotals!!.failed)
            assertEquals(0, stats.toolsTotals!!.unfinished)
            assertTrue(stats.toolsUsage.isEmpty())
            assertEquals(5, stats.activeDays)
            assertEquals(2, stats.streak)
            assertEquals(listOf(SessionStatsInfo.Activity("2026-07-15", 286)), stats.activity)
            assertEquals(1, stats.models.size)
            assertEquals("mimo-v2.5", stats.models[0].model!!.id)
            assertEquals("opencode-go", stats.models[0].model!!.providerID)
            assertEquals("default", stats.models[0].model!!.variant)
            assertEquals(212, stats.models[0].steps)
            assertEquals(3244672, stats.models[0].tokens.cacheRead)
            assertEquals(0.13289576159999988, stats.models[0].cost, 1e-9)
        }
    }

    @Test
    fun statsDetailToolsCariesUsageAndANullableDurationP50() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(statsDetailBody))
            val stats = api(server).sessionStats(tools = "detail")

            assertEquals("detail", stats.toolsMode)
            assertEquals(2, stats.toolsTotals!!.calls)
            assertEquals(2, stats.toolsUsage.size)
            val shell = stats.toolsUsage[0]
            assertEquals("shell", shell.name)
            assertEquals(2, shell.calls)
            assertEquals(1, shell.succeeded)
            assertEquals(1, shell.failed)
            assertEquals(120044.0, shell.durationP50!!, 0.0)
            assertEquals("read", stats.toolsUsage[1].name)
            assertNull("durationP50 is optional (session-stats.ts:29)", stats.toolsUsage[1].durationP50)
            assertNull("variant is optional in Model.Ref", stats.models[0].model!!.variant)
        }
    }

    @Test
    fun statsNoneToolsHasNeitherTotalsNorUsage() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(statsNoneBody))
            val stats = api(server).sessionStats(tools = "none")

            assertEquals("none", stats.toolsMode)
            assertNull(stats.toolsTotals)
            assertTrue(stats.toolsUsage.isEmpty())
            assertTrue(stats.activity.isEmpty())
            assertTrue(stats.models.isEmpty())
        }
    }

    @Test
    fun statsPropagatesInvalidRequest() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setBody(V2ApiFixtures.MISSING_QUERY_ERROR))
            try {
                api(server).sessionStats()
                fail("expected ApiException")
            } catch (e: V2Api.ApiException) {
                assertEquals(400, e.code)
                assertTrue(e.message!!.contains("InvalidRequestError"))
            }
        }
    }
}
