package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.v2.SessionTerminalSize
import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.createSessionTerminal
import com.xiufeigo.opencodeshell.v2.readSessionTerminal
import com.xiufeigo.opencodeshell.v2.sessionTerminals
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
 * Session-bound terminals (`persistent-pty.ts:23-52`) asserted on the wire
 * against [MockWebServer], same harness as [V2ApiFsWriteTest]. Envelopes
 * (`{"data":[]}` / `{"data":null}`, no `location`) are `Measured live`
 * 2026-09-23; Info/ReadResult element fixtures are `UNVERIFIED` (openapi
 * shapes — the live server had no terminal to list or read).
 */
class V2ApiSessionTerminalTest {

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
    fun listIsGetWithoutLocationAndParsesInfoFields() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(LIST_ONE))
            val terminals = api(server, directory = "/w", password = "secret").sessionTerminals("ses_1")

            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/experimental/session/ses_1/terminal", request.requestUrl!!.encodedPath)
            assertNull("these routes take no location parameter", request.requestUrl!!.queryParameter("location[directory]"))
            val expected = "Basic " + Base64.getEncoder().encodeToString("opencode:secret".toByteArray())
            assertEquals(expected, request.getHeader("Authorization"))

            assertEquals(1, terminals.size)
            val info = terminals[0]
            assertEquals("pty_1", info.id)
            assertEquals("shell", info.title)
            assertEquals("bash", info.command)
            assertEquals(listOf("-l", "--norc"), info.args)
            assertEquals("/home/u", info.cwd)
            assertEquals("running", info.status)
            assertEquals(42L, info.pid)
            assertNull("exitCode absent while running", info.exitCode)
            assertEquals("ses_1", info.sessionID)
            assertEquals("bash", info.foregroundProcess)
            assertEquals(SessionTerminalSize(80, 24), info.size)
            assertEquals(3, info.outputHead)
            assertEquals(100, info.outputTail)
        }
    }

    @Test
    fun listEmptyDataAndExitedExitCodeZero() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[]}"""))
            assertTrue(api(server).sessionTerminals("ses_gone").isEmpty())
            server.takeRequest()

            server.enqueue(MockResponse().setResponseCode(200).setBody(LIST_EXITED))
            val info = api(server).sessionTerminals("ses_1").single()
            assertEquals("exited", info.status)
            assertEquals("0 is a real exit code", 0L, info.exitCode ?: -1L)
            assertNull(info.foregroundProcess)
        }
    }

    @Test
    fun createPostsRequiredKeysPlusOptionalCommandCwdSize() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(CREATED))
            val info = api(server).createSessionTerminal(
                sessionID = "ses_1",
                title = "shell",
                args = listOf("-l"),
                env = mapOf("TERM" to "xterm-256color"),
                command = "bash",
                cwd = "/home/u",
                size = SessionTerminalSize(120, 40),
            )

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/experimental/session/ses_1/terminal", request.requestUrl!!.encodedPath)
            assertEquals("application/json", request.headers["Content-Type"]?.substringBefore(";"))
            val body = JSONObject(request.body.readUtf8())
            assertEquals("shell", body.getString("title"))
            assertEquals(1, body.getJSONArray("args").length())
            assertEquals("-l", body.getJSONArray("args").getString(0))
            assertEquals("xterm-256color", body.getJSONObject("env").getString("TERM"))
            assertEquals("bash", body.getString("command"))
            assertEquals("/home/u", body.getString("cwd"))
            assertEquals(120, body.getJSONObject("size").getInt("cols"))
            assertEquals(40, body.getJSONObject("size").getInt("rows"))
            assertEquals("exactly the six CreateInput keys", 6, body.length())

            assertEquals("pty_1", info.id)
            assertEquals("ses_1", info.sessionID)
            assertEquals(42L, info.pid)
        }
    }

    @Test
    fun createDefaultsAlwaysSendArgsTitleEnvAndOmitTheOptionals() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(CREATED))
            api(server).createSessionTerminal(sessionID = "ses_1", title = "t")

            val body = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals("t", body.getString("title"))
            assertTrue("args is wire-required even when empty", body.has("args"))
            assertEquals(0, body.getJSONArray("args").length())
            assertTrue("env is wire-required even when empty", body.has("env"))
            assertEquals(0, body.getJSONObject("env").length())
            assertFalse(body.has("command"))
            assertFalse(body.has("cwd"))
            assertFalse(body.has("size"))
            assertEquals(3, body.length())
        }
    }

    @Test
    fun readOmitsLinesWhenNullAndParsesTheScreen() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(READ_ONE))
            val read = api(server).readSessionTerminal("ses_1")

            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/experimental/session/ses_1/terminal/read", request.requestUrl!!.encodedPath)
            assertNull(request.requestUrl!!.queryParameter("lines"))

            val screen = read!!
            assertEquals("pty_1", screen.ptyID)
            assertEquals("shell", screen.title)
            assertEquals("/home/u", screen.cwd)
            assertEquals("bash", screen.foregroundProcess)
            assertEquals("line1\nline2\n", screen.text)
            assertEquals(80, screen.cols)
            assertEquals(24, screen.rows)
            assertEquals(5, screen.cursorX)
            assertEquals(2, screen.cursorY)
        }
    }

    @Test
    fun readSendsLinesQueryAndNullDataBecomesNull() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(READ_ONE))
            api(server).readSessionTerminal("ses_1", lines = 200)
            assertEquals("200", server.takeRequest().requestUrl!!.queryParameter("lines"))

            // measured: no current terminal (or unknown session) answers {"data":null}
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":null}"""))
            assertNull(api(server).readSessionTerminal("ses_1"))
            server.takeRequest()
        }
    }

    @Test
    fun non2xxThrowsApiExceptionAndAJsonBodyBecomesAParseError() {
        MockWebServer().use { server ->
            // measured live: lines=0 → 400 InvalidRequestError (ReadLines is 1..65535)
            server.enqueue(MockResponse().setResponseCode(400).setBody(LINES_OUT_OF_RANGE_ERROR))
            try {
                api(server).readSessionTerminal("ses_1", lines = 0)
                fail("expected ApiException")
            } catch (e: V2Api.ApiException) {
                assertEquals(400, e.code)
                assertTrue(e.message!!.contains("InvalidRequestError"))
            }

            server.enqueue(MockResponse().setResponseCode(200).setBody(V2ApiFixtures.SPA_HTML))
            try {
                api(server).sessionTerminals("ses_1")
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

    private companion object {

        /** `PersistentPty.Info`, openapi shape (`UNVERIFIED` live). */
        const val LIST_ONE = """{"data":[{"id":"pty_1","title":"shell","command":"bash","args":["-l","--norc"],"cwd":"/home/u","status":"running","pid":42,"sessionID":"ses_1","foregroundProcess":"bash","size":{"cols":80,"rows":24},"output":{"head":3,"tail":100}}]}"""

        /** `exitCode: 0` and `foregroundProcess: null` — the nullable corners. */
        const val LIST_EXITED = """{"data":[{"id":"pty_2","title":"t","command":"cmd.exe","args":[],"cwd":"C:\\","status":"exited","pid":7,"exitCode":0,"sessionID":"ses_1","foregroundProcess":null,"size":{"cols":40,"rows":12},"output":{"head":0,"tail":0}}]}"""

        /** `POST` echo — `{"data":PersistentPty.Info}` (`UNVERIFIED` live). */
        const val CREATED = """{"data":{"id":"pty_1","title":"shell","command":"bash","args":["-l"],"cwd":"/home/u","status":"running","pid":42,"sessionID":"ses_1","foregroundProcess":"bash","size":{"cols":120,"rows":40},"output":{"head":0,"tail":5}}}"""

        /** `PersistentPty.ReadResult` (`UNVERIFIED` live). */
        const val READ_ONE = """{"data":{"ptyID":"pty_1","title":"shell","cwd":"/home/u","foregroundProcess":"bash","screen":{"text":"line1\nline2\n","cols":80,"rows":24,"cursor":{"x":5,"y":2}}}}"""

        /** `GET …/terminal/read?lines=0` — measured 400 on 2.0.11. */
        const val LINES_OUT_OF_RANGE_ERROR = """{
  "_tag": "InvalidRequestError",
  "message": "Expected a value greater than 0\n  at [\"lines\"]",
  "kind": "Query"
}"""
    }
}
