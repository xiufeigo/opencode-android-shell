package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.v2.V2Api
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v63 P2 「500 错误展示裸 HTTP 500 丢服务端 ref 定位码」
 * (`research/v63_verify/5560/06_err_detail.png`: the card read
 * `Something went wrong  HTTP 500` with nothing to hand the server).
 *
 * Upstream's own answer to an unexpected server failure is deliberately opaque:
 * `UnknownError` carries the human sentence 「Unexpected server error. Check
 * server logs for details.」 and puts the **only** handle on the log line in
 * `ref` — `err_{uuid4[0:8]}`
 * (`_upstream/packages/server/src/handlers/session-error.ts:22-28`, and
 * `UnknownErrorEncoded` in `protocol/openapi.json`). Upstream's TUI prints that
 * ref verbatim (`tui/src/util/error-details.ts:6`,
 * `dialog-error-details.tsx:114`) precisely so the user can quote it; the shell
 * read `_tag` / `type` / `message` / `name` and dropped `ref`, so a 500 that
 * *did* carry a ref still reached the card without the one thing that locates
 * it.
 */
class V2ApiErrorRefTest {

    private fun api() = V2Api("http://127.0.0.1:1/".toHttpUrlOrNull()!!)

    private fun failing(server: MockWebServer, code: Int, body: String): V2Api.ApiException {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
        try {
            V2Api(
                baseHttpUrl = server.url("/").toString().toHttpUrlOrNull()!!,
                http = V2Api.defaultClient(),
            ).health()
        } catch (e: V2Api.ApiException) {
            return e
        }
        throw AssertionError("expected ApiException")
    }

    @Test
    fun theServerLogReferenceSurvives() {
        // The real shape: an `UnknownError` whose message says nothing useful.
        val message = api().errorText(
            500,
            """{"_tag":"UnknownError","message":"Unexpected server error. Check server logs for details.","ref":"err_a1b2c3d4"}""",
        )
        assertEquals(
            "HTTP 500 UnknownError: Unexpected server error. Check server logs for details. " +
                "Reference: err_a1b2c3d4",
            message,
        )
        // Upstream's label, so a user can paste it into a log search verbatim.
        assertTrue(message, message.contains("err_a1b2c3d4"))
    }

    @Test
    fun aRefOnlyBodyIsNotAnEmptyLine() {
        // Nothing readable but the ref: the card must still say something the
        // user can act on rather than collapsing to a bare status.
        assertEquals(
            "HTTP 500 Reference: err_deadbeef",
            api().errorText(500, """{"ref":"err_deadbeef"}"""),
        )
        // The `data` envelope the v2 server wraps some errors in.
        assertEquals(
            "HTTP 500 Reference: err_0badc0de",
            api().errorText(500, """{"data":{"ref":"err_0badc0de"}}"""),
        )
    }

    @Test
    fun theRefStaysATailSoThePrefixKeepsClassifying() {
        // `ChatStore.classifyStoredError` reads the status back off the
        // `HTTP {code}` prefix, so the ref is appended and never in front of it.
        val message = api().errorText(
            429,
            """{"_tag":"UnknownError","message":"boom","ref":"err_11112222"}""",
        )
        assertTrue(message, message.startsWith("HTTP 429 "))
        assertEquals(429, ChatStore.classifyStoredError(message, null).status)
    }

    @Test
    fun aBodyWithoutARefIsUntouched() {
        // Every existing shape keeps its exact text — the ref is additive, and
        // these strings are pinned by `V2ApiErrorTypeTest` /
        // `JsonNullStringTrapTest` / `ProviderErrorTest`.
        assertEquals(
            "HTTP 429 [rate_limit_error] Rate limit exceeded, top up",
            api().errorText(429, """{"type":"rate_limit_error","message":"Rate limit exceeded, top up"}"""),
        )
        assertEquals("HTTP 500", api().errorText(500, ""))
        assertEquals(
            """HTTP 429 {"_tag":null,"message":null,"name":null}""",
            api().errorText(429, """{"_tag":null,"message":null,"name":null}"""),
        )
        assertNull(api().errorRefOf("""{"ref":null}"""))
    }

    @Test
    fun onlyAServerIssuedRefIsShown() {
        // A provider body may have a `ref` field of its own; putting a foreign
        // id on the card would send the user to the wrong log.
        assertNull(api().errorRefOf("""{"ref":"cmpl_abc"}"""))
        assertNull(api().errorRefOf("""{"ref":""}"""))
        assertNull(api().errorRefOf("not json"))
        assertNull(api().errorRefOf("""{"data":{"ref":"cmpl_abc"}}"""))
        assertEquals("err_a1b2c3d4", api().errorRefOf("""{"data":{"ref":" err_a1b2c3d4 "}}"""))
    }

    @Test
    fun theExceptionCarriesItToo() {
        MockWebServer().use { server ->
            val e = failing(
                server,
                500,
                """{"_tag":"UnknownError","message":"Unexpected server error. Check server logs for details.","ref":"err_a1b2c3d4"}""",
            )
            assertEquals(500, e.code)
            assertTrue(e.message!!, e.message!!.contains("Reference: err_a1b2c3d4"))
            assertTrue(e.rawBody!!, e.rawBody!!.contains("err_a1b2c3d4"))
        }
    }
}
