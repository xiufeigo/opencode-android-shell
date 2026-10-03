package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.persistentPtyConnectToken
import com.xiufeigo.opencodeshell.v2.persistentPtyGet
import com.xiufeigo.opencodeshell.v2.persistentPtyHandoff
import com.xiufeigo.opencodeshell.v2.persistentPtyShutdown
import com.xiufeigo.opencodeshell.v2.persistentPtySocketRequest
import com.xiufeigo.opencodeshell.v2.persistentPtySnapshot
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

/**
 * `/api/experimental/persistent-pty/…` (upstream
 * `protocol/src/groups/persistent-pty.ts:54-124`, shapes
 * `schema/src/persistent-pty.ts:9-48`) — asserted on the wire against
 * [MockWebServer], same harness as [V2ApiTest]. Instance payloads are openapi
 * (`UNVERIFIED` live — the server had no persistent terminal open); the error
 * envelopes match the live 404s measured 2026-09-23.
 */
class V2ApiPersistentPtyTest {

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

    // `PersistentPty.Info` (openapi; UNVERIFIED live) — running with no
    // foreground process, then exited with one and `exitCode: 0`.
    private val infoRunning =
        """{"id":"pty_persistent_7","title":"shell","command":"bash","args":["-l"],"cwd":"/work","status":"running","pid":4242,"sessionID":"ses_1","foregroundProcess":null,"size":{"cols":80,"rows":24},"output":{"head":0,"tail":1234}}"""

    private val infoExited =
        """{"id":"pty_persistent_7","title":"shell","command":"bash","args":[],"cwd":"/work","status":"exited","pid":4242,"exitCode":0,"sessionID":"ses_1","foregroundProcess":"vim","size":{"cols":80,"rows":24},"output":{"head":10,"tail":900}}"""

    /** `PersistentPty.Snapshot` (openapi; UNVERIFIED live). */
    private val snapshot =
        """{"data":{"info":$infoRunning,"text":"line1\nline2","checkpoint":"aGVsbG8=","cursor":{"x":3,"y":21}}}"""

    /** `PersistentPty.Handoff` (openapi; UNVERIFIED live). */
    private val handoff =
        """{"handoff":{"directory":"C:\\tmp\\oc","instanceID":"inst_1","ticket":"h-ticket","expiresAt":1789969243559}}"""

    /** Unknown id — byte-shaped like the live `404 PtyNotFoundError` (get & snapshot). */
    private val ptyNotFound =
        """{"_tag":"PtyNotFoundError","ptyID":"pty_persistent_1","message":"PTY session not found: pty_persistent_1"}"""

    @Test
    fun getFetchesOneInfoWithoutALocationParameter() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":$infoRunning}"""))
            val info = api(server, directory = "/w").persistentPtyGet("pty_persistent_7")

            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/experimental/persistent-pty/pty_persistent_7", request.requestUrl!!.encodedPath)
            // This family takes no `location` (openapi `parameters: [ptyID]` only).
            assertNull(request.requestUrl!!.queryParameter("location[directory]"))
            assertEquals(0, request.requestUrl!!.querySize)
            assertEquals("pty_persistent_7", info.id)
            assertEquals("ses_1", info.sessionID)
            assertEquals("running", info.status)
            assertTrue(info.running)
            assertEquals(4242L, info.pid)
            assertNull(info.exitCode)
            assertNull(info.foregroundProcess)
            assertEquals(80, info.cols)
            assertEquals(24, info.rows)
            assertEquals(0L, info.outputHead)
            assertEquals(1234L, info.outputTail)
        }
    }

    @Test
    fun getParsesTheExitedShapeWithExitCodeAndForegroundProcess() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":$infoExited}"""))
            val info = api(server).persistentPtyGet("pty_persistent_7")

            assertEquals("exited", info.status)
            // `0` is a real exit code — absence must stay distinct from `0`.
            assertEquals(0L, info.exitCode)
            assertEquals("vim", info.foregroundProcess)
            assertEquals(listOf<String>(), info.args)
        }
    }

    @Test
    fun connectTokenPostsTheMandatoryTicketHeaderAndEmptyBody() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"data":{"ticket":"08b2d9f5-c399-4213-bd40-564d93e649c4","expires_in":60}}"""),
            )
            val ticket = api(server, directory = "/w", password = "secret")
                .persistentPtyConnectToken("pty_persistent_7")

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals(
                "/api/experimental/persistent-pty/pty_persistent_7/connect-token",
                request.requestUrl!!.encodedPath,
            )
            // Value must be exactly `1` or the server answers 403 (pty.ts:10-11).
            assertEquals("1", request.headers[V2Api.TICKET_HEADER])
            val expected = "Basic " + Base64.getEncoder().encodeToString("opencode:secret".toByteArray())
            assertEquals(expected, request.headers["Authorization"])
            assertEquals(0L, request.bodySize)
            assertNull(request.requestUrl!!.queryParameter("location[directory]"))
            assertEquals("08b2d9f5-c399-4213-bd40-564d93e649c4", ticket.ticket)
            assertEquals(60, ticket.expiresIn)
        }
    }

    @Test
    fun socketRequestIsAGetUpgradeWithTheSixConnectQueries() {
        MockWebServer().use { server ->
            val request = api(server, directory = "/w").persistentPtySocketRequest(
                ptyID = "pty_persistent_7",
                ticket = "tk",
                cursor = 1234L,
                role = "observer",
                attachmentID = "att-1",
                takeover = true,
                inputProtocol = 1,
            )

            // OkHttp upgrades this GET via `newWebSocket` (x-websocket).
            assertEquals("GET", request.method)
            assertEquals("/api/experimental/persistent-pty/pty_persistent_7/connect", request.url.encodedPath)
            val url = request.url
            assertEquals("tk", url.queryParameter("ticket"))
            assertEquals("1234", url.queryParameter("cursor"))
            assertEquals("observer", url.queryParameter("role"))
            assertEquals("att-1", url.queryParameter("attachment_id"))
            assertEquals("true", url.queryParameter("takeover"))
            assertEquals("1", url.queryParameter("input_protocol"))
            assertNull(url.queryParameter("location[directory]"))
        }
    }

    @Test
    fun socketRequestOmitsEveryAbsentQueryAndKeepsBasicAuth() {
        MockWebServer().use { server ->
            val request = api(server, password = "secret").persistentPtySocketRequest(ptyID = "pty_persistent_7")

            assertEquals(0, request.url.querySize)
            val expected = "Basic " + Base64.getEncoder().encodeToString("opencode:secret".toByteArray())
            assertEquals(expected, request.header("Authorization"))
        }
    }

    @Test
    fun snapshotParsesInfoTextBase64CheckpointAndCursor() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(snapshot))
            val snap = api(server).persistentPtySnapshot("pty_persistent_7")

            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals(
                "/api/experimental/persistent-pty/pty_persistent_7/snapshot",
                request.requestUrl!!.encodedPath,
            )
            assertEquals(0, request.requestUrl!!.querySize)
            assertEquals("pty_persistent_7", snap.info.id)
            assertEquals("line1\nline2", snap.text)
            // Wire keeps base64 (`contentEncoding: base64`); bytes are the decode.
            assertEquals("aGVsbG8=", snap.checkpoint)
            assertTrue("hello".toByteArray().contentEquals(snap.checkpointBytes()))
            assertEquals(3, snap.cursorX)
            assertEquals(21, snap.cursorY)
        }
    }

    @Test
    fun handoffPostsEmptyBodyAndReadsTheHandoffEnvelope() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(handoff))
            val value = api(server, directory = "/w").persistentPtyHandoff()

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/experimental/persistent-pty/handoff", request.requestUrl!!.encodedPath)
            assertEquals(0L, request.bodySize)
            assertNull(request.requestUrl!!.queryParameter("location[directory]"))
            assertEquals("C:\\tmp\\oc", value!!.directory)
            assertEquals("inst_1", value.instanceID)
            assertEquals("h-ticket", value.ticket)
            assertEquals(1789969243559L, value.expiresAt)
        }
    }

    @Test
    fun handoffIsNullWhenTheServerHandsNothingOff() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"handoff":null}"""))
            assertNull(api(server).persistentPtyHandoff())
        }
    }

    @Test
    fun shutdownPostsEmptyBody() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            api(server, directory = "/w").persistentPtyShutdown()

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/experimental/persistent-pty/shutdown", request.requestUrl!!.encodedPath)
            assertEquals(0L, request.bodySize)
            assertNull(request.requestUrl!!.queryParameter("location[directory]"))
        }
    }

    @Test
    fun unknownPtyIsAnApiExceptionKeepingTheNotFoundTag() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404).setBody(ptyNotFound))
            try {
                api(server).persistentPtyGet("pty_persistent_1")
                fail("expected ApiException")
            } catch (e: V2Api.ApiException) {
                assertEquals(404, e.code)
                assertTrue(e.message!!.contains("PtyNotFoundError"))
            }

            server.enqueue(MockResponse().setResponseCode(404).setBody(ptyNotFound))
            try {
                api(server).persistentPtySnapshot("pty_persistent_1")
                fail("expected ApiException")
            } catch (e: V2Api.ApiException) {
                assertEquals(404, e.code)
            }
        }
    }

    @Test
    fun aSpaShellBodyBecomesAParseErrorNotAJsonCrash() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(V2ApiFixtures.SPA_HTML))
            try {
                api(server).persistentPtyGet("pty_persistent_7")
                fail("expected ApiException")
            } catch (e: V2Api.ApiException) {
                assertEquals(V2Api.JSON_BODY_CODE, e.code)
            }
        }
    }
}
