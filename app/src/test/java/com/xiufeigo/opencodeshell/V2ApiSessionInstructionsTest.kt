package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.v2.InstructionEntry
import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.sessionInstructionEntries
import com.xiufeigo.opencodeshell.v2.sessionInstructionEntryPut
import com.xiufeigo.opencodeshell.v2.sessionInstructionEntryRemove
import com.xiufeigo.opencodeshell.v2.sessionSkillActivate
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

/**
 * Session instruction entries (`GET|PUT|DELETE …/instructions/entries`,
 * `protocol/src/groups/session.ts:646-705`) and `POST …/skill`
 * (`session.ts:433-451`) — asserted on the wire against [MockWebServer], same
 * harness as [V2ApiFsWriteTest]. `UNVERIFIED` live: openapi/`session.ts` is the
 * contract; write routes are never hit on a real server.
 */
class V2ApiSessionInstructionsTest {

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
    fun listUnwrapsDataArrayAndDecodesJsonValueShapes() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"data":[
                    |  {"key":"style.guide","value":{"tone":"terse","tags":["a","b"]}},
                    |  {"key":"max_turns","value":3},
                    |  {"key":"flag","value":true},
                    |  {"key":"note","value":"plain"},
                    |  {"key":"gone","value":null}
                    |]}""".trimMargin(),
                ),
            )
            val entries = api(server).sessionInstructionEntries("ses_abc")

            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals(
                "/api/experimental/session/ses_abc/instructions/entries",
                request.requestUrl!!.encodedPath,
            )
            assertFalse("openapi declares no location query on this route", request.requestUrl!!.queryParameterNames.isNotEmpty())

            assertEquals(
                listOf("style.guide", "max_turns", "flag", "note", "gone"),
                entries.map(InstructionEntry::key),
            )
            val nested = entries[0].value as JSONObject
            assertEquals("terse", nested.optString("tone"))
            assertEquals(listOf("a", "b"), (0 until nested.getJSONArray("tags").length()).map { nested.getJSONArray("tags").getString(it) })
            assertEquals(3, (entries[1].value as Number).toInt())
            assertEquals(true, entries[2].value)
            assertEquals("plain", entries[3].value)
            assertNull("JSON null decodes to Kotlin null", entries[4].value)
        }
    }

    @Test
    fun listIsEmptyWhenDataIsMissing() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            assertTrue(api(server).sessionInstructionEntries("ses_abc").isEmpty())
        }
    }

    @Test
    fun putSendsOneKeyPathSegmentAndValueBody() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            api(server, directory = "/w").sessionInstructionEntryPut(
                sessionID = "ses_abc",
                key = "style.guide",
                value = JSONObject().put("tone", "terse"),
            )

            val request = server.takeRequest()
            assertEquals("PUT", request.method)
            assertEquals(
                "/api/experimental/session/ses_abc/instructions/entries/style.guide",
                request.requestUrl!!.encodedPath,
            )
            assertFalse("location is resolved server-side from the session", request.requestUrl!!.queryParameterNames.isNotEmpty())
            assertEquals("application/json", request.headers["Content-Type"]?.substringBefore(";"))
            val body = JSONObject(request.body.readUtf8())
            assertEquals("terse", body.getJSONObject("value").optString("tone"))
        }
    }

    @Test
    fun putEncodesNullAsPresentJsonValueAndScalarsVerbatim() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setResponseCode(204))
            val api = api(server)
            api.sessionInstructionEntryPut("ses_abc", "nulled", null)
            api.sessionInstructionEntryPut("ses_abc", "counted", 7)
            api.sessionInstructionEntryPut("ses_abc", "listed", JSONArray().put("x"))

            val nulled = JSONObject(server.takeRequest().body.readUtf8())
            assertTrue("`value` is required — present as JSON null, not omitted", nulled.has("value"))
            assertTrue(nulled.isNull("value"))
            assertEquals(1, nulled.length())

            val counted = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals(7, counted.getInt("value"))

            val listed = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals(listOf("x"), listOf(listed.getJSONArray("value").getString(0)))
        }
    }

    @Test
    fun removeDeletesTheKeyPathSegmentAndExpects204() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            api(server).sessionInstructionEntryRemove("ses_abc", "old_key-1")

            val request = server.takeRequest()
            assertEquals("DELETE", request.method)
            assertEquals(
                "/api/experimental/session/ses_abc/instructions/entries/old_key-1",
                request.requestUrl!!.encodedPath,
            )
            assertEquals(0L, request.bodySize)
        }
    }

    @Test
    fun skillActivatePostsIdAndResume() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            api(server).sessionSkillActivate("ses_abc", "sk_react", resume = true)

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/experimental/session/ses_abc/skill", request.requestUrl!!.encodedPath)
            val body = JSONObject(request.body.readUtf8())
            assertEquals("sk_react", body.getString("id"))
            assertEquals(true, body.getBoolean("resume"))
        }
    }

    @Test
    fun skillActivateOmitsResumeWhenNull() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            api(server).sessionSkillActivate("ses_abc", "sk_react")

            val body = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals("sk_react", body.getString("id"))
            assertFalse("resume is Schema.optional — omit, do not send false", body.has("resume"))
        }
    }

    @Test
    fun putValueTooLargeKeepsThe413TagAndByteCounts() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(413).setBody(
                    """{"_tag":"InstructionEntryValueTooLargeError","actualBytes":300000,"maxBytes":262144,"message":"value too large"}""",
                ),
            )
            try {
                api(server).sessionInstructionEntryPut("ses_abc", "big", "x".repeat(16))
                fail("expected ApiException")
            } catch (e: V2Api.ApiException) {
                assertEquals(413, e.code)
                assertTrue(e.message!!.contains("InstructionEntryValueTooLargeError"))
                assertTrue(e.message!!.contains("value too large"))
            }
        }
    }

    @Test
    fun skillNotFoundAndSessionNotFoundSurfaceAsApiException() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(404).setBody(
                    """{"_tag":"SkillNotFoundError","skill":"sk_missing","message":"Skill not found: sk_missing"}""",
                ),
            )
            try {
                api(server).sessionSkillActivate("ses_abc", "sk_missing")
                fail("expected ApiException")
            } catch (e: V2Api.ApiException) {
                assertEquals(404, e.code)
                assertTrue(e.message!!.contains("SkillNotFoundError"))
            }

            server.enqueue(MockResponse().setResponseCode(404).setBody(V2ApiFixtures.SESSION_NOT_FOUND))
            try {
                api(server).sessionInstructionEntries("ses_doesnotexist")
                fail("expected ApiException")
            } catch (e: V2Api.ApiException) {
                assertEquals(404, e.code)
                assertTrue(e.message!!.contains("SessionNotFoundError"))
            }
        }
    }

    @Test
    fun basicAuthHeaderIsSentOnThePutRoute() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            api(server, password = "secret").sessionInstructionEntryPut("ses_abc", "k", "v")

            val request = server.takeRequest()
            val expected = "Basic " + Base64.getEncoder().encodeToString("opencode:secret".toByteArray())
            assertEquals(expected, request.getHeader("Authorization"))
        }
    }
}
