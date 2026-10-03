package com.xiufeigo.opencodeshell

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderErrorTest {

    // Real report ①: prompt POST answered
    // HTTP 429 {"type":"rate_limit_error","message":"已达到 Token Plan 用量上限：请升级 Token Plan 套餐或购买积分补充用量。 (2056)"}
    private val quotaMessage = "已达到 Token Plan 用量上限：请升级 Token Plan 套餐或购买积分补充用量。 (2056)"

    // Real report ②: the model answers with a region block.
    private val regionMessage = "This model is not available in your country."

    @Test
    fun http429BodyClassifiesAsQuota() {
        val error = ChatStore.classifyProviderError(
            type = "rate_limit_error",
            message = quotaMessage,
            status = 429,
        )
        assertEquals(ChatStore.ProviderErrorKind.QUOTA_EXHAUSTED, error.kind)
        assertEquals(429, error.status)
        assertEquals("rate_limit_error", error.type)
    }

    @Test
    fun http429LineWithoutTypeStillClassifiesAsQuota() {
        // The prompt-POST path only keeps V2Api's `HTTP {code} {message}` line —
        // the JSON `type` never reaches the store, so the message must be enough.
        val error = ChatStore.classifyStoredError("HTTP 429 $quotaMessage", null)
        assertEquals(ChatStore.ProviderErrorKind.QUOTA_EXHAUSTED, error.kind)
        assertEquals(429, error.status)
    }

    @Test
    fun sessionErrorDetailCarriesTypeAndStatus() {
        val detail = JSONObject()
            .put("type", "rate_limit_error")
            .put("message", quotaMessage)
            .put("status", 429)
            .toString()
        val error = ChatStore.classifyStoredError(quotaMessage, detail)
        assertEquals(ChatStore.ProviderErrorKind.QUOTA_EXHAUSTED, error.kind)
        assertEquals(429, error.status)
        assertEquals("rate_limit_error", error.type)
    }

    @Test
    fun regionMessageClassifiesAsRegion() {
        val error = ChatStore.classifyProviderError(type = null, message = regionMessage, status = null)
        assertEquals(ChatStore.ProviderErrorKind.REGION_RESTRICTED, error.kind)
    }

    @Test
    fun regionMessageInsideHttpLineClassifiesAsRegion() {
        val error = ChatStore.classifyStoredError("HTTP 400 $regionMessage", null)
        assertEquals(ChatStore.ProviderErrorKind.REGION_RESTRICTED, error.kind)
        // The original code survives for troubleshooting.
        assertEquals(400, error.status)
    }

    @Test
    fun genericServerErrorKeepsCodeAndType() {
        val error = ChatStore.classifyStoredError(
            "HTTP 500 boom",
            JSONObject().put("type", "internal_error").put("message", "boom").toString(),
        )
        assertEquals(ChatStore.ProviderErrorKind.OTHER, error.kind)
        assertEquals(500, error.status)
        assertEquals("internal_error", error.type)
        assertEquals("boom", error.message)
    }

    @Test
    fun nullInputsNeverThrowAndStayOther() {
        val error = ChatStore.classifyStoredError(null, null)
        assertEquals(ChatStore.ProviderErrorKind.OTHER, error.kind)
        assertNull(error.status)
        assertNull(error.type)
        assertNull(error.message)
    }

    @Test
    fun blankErrorFallsBackToDetailMessage() {
        val detail = JSONObject().put("type", "rate_limit_error").put("message", quotaMessage).toString()
        val error = ChatStore.classifyStoredError("   ", detail)
        assertEquals(ChatStore.ProviderErrorKind.QUOTA_EXHAUSTED, error.kind)
        assertEquals(quotaMessage, error.message)
    }

    // Real report ③: `V2Api.errorText` renders the discriminator as
    // `HTTP {code} [{type}] {human}` — the prompt-POST path's only structured
    // `type` source. The classifier must read the bracket before keywords.
    @Test
    fun bracketTypeClassifiesQuota() {
        val line = "HTTP 429 [rate_limit_error] 已达到 Token Plan 用量上限：请升级 Token Plan 套餐或购买积分补充用量。 (2056)"
        val error = ChatStore.classifyStoredError(line, null)
        assertEquals(ChatStore.ProviderErrorKind.QUOTA_EXHAUSTED, error.kind)
        assertEquals(429, error.status)
        assertEquals("rate_limit_error", error.type)
    }

    @Test
    fun bracketTypeBeatsKeywords() {
        // No quota keyword in the human sentence — the bracket alone decides.
        val error = ChatStore.classifyStoredError("HTTP 429 [rate_limit_error] boom", null)
        assertEquals(ChatStore.ProviderErrorKind.QUOTA_EXHAUSTED, error.kind)
        assertEquals("rate_limit_error", error.type)
    }

    @Test
    fun bracketRegionTypeClassifiesAsRegion() {
        val error = ChatStore.classifyStoredError(
            "HTTP 403 [unsupported_country] $regionMessage",
            null,
        )
        assertEquals(ChatStore.ProviderErrorKind.REGION_RESTRICTED, error.kind)
        assertEquals(403, error.status)
        assertEquals("unsupported_country", error.type)
    }

    @Test
    fun detailJsonTypeWinsOverBracket() {
        val detail = JSONObject()
            .put("type", "internal_error")
            .put("message", "boom")
            .put("status", 500)
            .toString()
        val error = ChatStore.classifyStoredError("HTTP 500 [rate_limit_error] boom", detail)
        assertEquals(ChatStore.ProviderErrorKind.OTHER, error.kind)
        assertEquals("internal_error", error.type)
    }

    @Test
    fun nonTypeBracketsAreIgnored() {
        // A bracketed aside that is not a type token never becomes one.
        val error = ChatStore.classifyStoredError("HTTP 500 [see docs] boom", null)
        assertEquals(ChatStore.ProviderErrorKind.OTHER, error.kind)
        assertEquals(500, error.status)
        assertNull(error.type)
    }

    // ------------------------------------------------------- v31 BUG-3

    /**
     * Real report ④: the session's project directory was deleted, so the prompt
     * POST answered
     * `HTTP 500 {"_tag":"NotFound","message":"PlatformError: NotFound: FileSystem.realPath"}`
     * — rendered by `V2Api.errorText` as the line below. The card used to say
     * 「HTTP 500」 and nothing else, which reads as "the server is broken"
     * instead of "the directory is gone".
     */
    private val locationMissingLine =
        "HTTP 500 [NotFound] PlatformError: NotFound: FileSystem.realPath"

    @Test
    fun deletedProjectDirectoryClassifiesAsLocationMissing() {
        val error = ChatStore.classifyStoredError(locationMissingLine, null)
        assertEquals(ChatStore.ProviderErrorKind.LOCATION_MISSING, error.kind)
        assertEquals(500, error.status)
        assertEquals("NotFound", error.type)
    }

    @Test
    fun locationMissingSurvivesTheDetailJsonShape() {
        // The SSE path carries the raw payload instead of the rendered line.
        val detail = JSONObject()
            .put("type", "NotFound")
            .put("message", "PlatformError: NotFound: FileSystem.realPath")
            .put("status", 500)
            .toString()
        val error = ChatStore.classifyStoredError(null, detail)
        assertEquals(ChatStore.ProviderErrorKind.LOCATION_MISSING, error.kind)
    }

    @Test
    fun enoentWordingIsTheSameFailure() {
        // The same server failure can surface as the node error text.
        val error = ChatStore.classifyStoredError(
            "HTTP 500 ENOENT: no such file or directory, realpath '/home/x/gone'",
            null,
        )
        assertEquals(ChatStore.ProviderErrorKind.LOCATION_MISSING, error.kind)
    }

    @Test
    fun otherNotFoundFailuresKeepTheirGenericCard() {
        // A missing *file* / model / route is not a missing project directory:
        // a 404 keeps its generic card (the rule needs 500 for the
        // discriminator-only shape, or the `realPath`/filesystem token
        // anywhere), and a 500 without either token stays generic too.
        val missingRoute = ChatStore.classifyStoredError("HTTP 404 [NotFound] session not found", null)
        assertEquals(ChatStore.ProviderErrorKind.OTHER, missingRoute.kind)
        val generic500 = ChatStore.classifyStoredError("HTTP 500 boom", null)
        assertEquals(ChatStore.ProviderErrorKind.OTHER, generic500.kind)
        // …while the bare 500 + `NotFound` the server really sends for a
        // vanished directory is caught without the filesystem wording.
        assertEquals(
            ChatStore.ProviderErrorKind.LOCATION_MISSING,
            ChatStore.classifyStoredError("HTTP 500 [NotFound] boom", null).kind,
        )
        // A 500 + `NotFound` that names another missing thing is *that* failure,
        // not a vanished directory.
        assertEquals(
            ChatStore.ProviderErrorKind.OTHER,
            ChatStore.classifyStoredError("HTTP 500 [NotFound] Provider not found", null).kind,
        )
    }
}
