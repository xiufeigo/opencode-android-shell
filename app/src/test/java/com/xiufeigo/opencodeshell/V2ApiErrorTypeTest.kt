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
 * The HTTP error body's `type` (e.g. `rate_limit_error`) must survive
 * `V2Api.errorText` — it used to be dropped, leaving only
 * `HTTP {code} {message}`, so the only classifier input was keyword-guessing
 * on the human sentence. Contract now:
 * `HTTP {code} [{type}] {human}` on the message, plus [V2Api.ApiException.errorType]
 * / [V2Api.ApiException.rawBody] fields for structured consumers.
 *
 * Alignment pins (read-only, [ChatStore] is owned elsewhere):
 * - the `HTTP {code}` prefix stays (its `HTTP_CODE_REGEX` is the only status
 *   source on the prompt-POST path, which has no error detail);
 * - every message here still classifies through
 *   `ChatStore.classifyStoredError(message, null)` — mostly via the
 *   message-substring rules, since `ERROR_TYPE_REGEX` only matches a
 *   `"type":"…"` JSON fragment the POST path never had.
 */
class V2ApiErrorTypeTest {

    private fun api(server: MockWebServer) = V2Api(
        baseHttpUrl = server.url("/").toString().toHttpUrlOrNull()!!,
        http = V2Api.defaultClient(),
    )

    private fun get429(server: MockWebServer, body: String): V2Api.ApiException {
        server.enqueue(MockResponse().setResponseCode(429).setBody(body))
        try {
            api(server).health()
        } catch (e: V2Api.ApiException) {
            return e
        }
        throw AssertionError("expected ApiException")
    }

    private fun getWithCode(server: MockWebServer, code: Int, body: String): V2Api.ApiException {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
        try {
            api(server).health()
        } catch (e: V2Api.ApiException) {
            return e
        }
        throw AssertionError("expected ApiException")
    }

    @Test
    fun rateLimitTypeSurvivesInMessageAndField() {
        MockWebServer().use { server ->
            val e = get429(
                server,
                """{"type":"rate_limit_error","message":"Rate limit exceeded, top up","status":429}""",
            )
            assertEquals(429, e.code)
            assertEquals("HTTP 429 [rate_limit_error] Rate limit exceeded, top up", e.message)
            assertEquals("rate_limit_error", e.errorType)
            assertTrue(e.rawBody!!, e.rawBody!!.contains("rate_limit_error"))
            val classified = ChatStore.classifyStoredError(e.message, null)
            assertEquals(ChatStore.ProviderErrorKind.QUOTA_EXHAUSTED, classified.kind)
            assertEquals(429, classified.status)
        }
    }

    @Test
    fun tagAndTypeAreBothKept() {
        MockWebServer().use { server ->
            val e = get429(
                server,
                """{"_tag":"UpstreamError","type":"overloaded_error","message":"try again"}""",
            )
            assertEquals("HTTP 429 [overloaded_error] UpstreamError: try again", e.message)
            assertEquals("overloaded_error", e.errorType)
        }
    }

    @Test
    fun dataEnvelopeTypeIsRead() {
        MockWebServer().use { server ->
            // 403 (not 429: 429 always wins the quota rule by status) so the
            // region rule fires off the message-substring fallback.
            val e = getWithCode(
                server,
                403,
                """{"data":{"type":"country_blocked","message":"not available in your country"}}""",
            )
            assertEquals(
                "HTTP 403 [country_blocked] not available in your country",
                e.message,
            )
            assertEquals("country_blocked", e.errorType)
            assertEquals(
                ChatStore.ProviderErrorKind.REGION_RESTRICTED,
                ChatStore.classifyStoredError(e.message, null).kind,
            )
        }
    }

    @Test
    fun tagOnlyShapeIsUnchanged() {
        MockWebServer().use { server ->
            val e = get429(
                server,
                """{"_tag":"InvalidCursorError","message":"Cursor cannot be combined with order"}""",
            )
            assertEquals("HTTP 429 InvalidCursorError: Cursor cannot be combined with order", e.message)
            assertNull(e.errorType)
        }
    }

    @Test
    fun nonJsonFallbackIsUnchanged() {
        MockWebServer().use { server ->
            val e = get429(server, "boom")
            assertEquals("HTTP 429 boom", e.message)
            assertNull(e.errorType)
            assertEquals("boom", e.rawBody)
        }
    }

    @Test
    fun rawBodyIsCappedButMessageDetailStaysShort() {
        MockWebServer().use { server ->
            val big = "x".repeat(9000)
            val e = get429(server, big)
            assertEquals(4096, e.rawBody!!.length)
            // Message detail cap (200) predates this change — still holds.
            assertTrue("message=" + e.message, e.message!!.length <= "HTTP 429 ".length + 200)
            assertNull(e.errorType)
        }
    }
}
