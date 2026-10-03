package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.dock.WebSearchModel
import com.xiufeigo.opencodeshell.ui.session.dock.WebSearchRunModel
import com.xiufeigo.opencodeshell.ui.session.dock.WebSearchOption
import com.xiufeigo.opencodeshell.v2.WebSearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

/**
 * Pure-logic coverage for the manual websearch run (`POST /api/websearch`
 * execution): the run state machine, the provider argument (the consent dock's
 * "Any" vocabulary), the error-kind mapping of `server/src/handlers/websearch.ts`
 * and the `WebSearch.Result` row projection.
 */
class WebSearchRunModelTest {

    private val utc: ZoneId = ZoneId.of("UTC")

    private fun result(
        url: String = "https://example.com/a",
        title: String? = "Title",
        content: String? = "Snippet",
        published: Long? = null,
    ) = WebSearchResult(url = url, title = title, content = content, timePublished = published)

    // ------------------------------------------------------------- run gate

    @Test
    fun blankQueryIsNotRunnableAndARunningDockStaysBlocked() {
        assertFalse(WebSearchRunModel.canRun("   ", running = false))
        assertFalse(WebSearchRunModel.canRun("opencode", running = true))
        assertTrue(WebSearchRunModel.canRun("opencode", running = false))
    }

    // ------------------------------------------------------- provider arg

    @Test
    fun anyValueAndBlankBothOmitTheProviderSoTheServerDefaultRuns() {
        assertNull(WebSearchRunModel.providerArg(WebSearchModel.ANY_VALUE))
        assertNull(WebSearchRunModel.providerArg(null))
        assertNull(WebSearchRunModel.providerArg(""))
        assertEquals("brave", WebSearchRunModel.providerArg("brave"))
    }

    // ------------------------------------------------------------ status

    @Test
    fun statusSeparatesNeverRanFromEmptyResultsAndFailures() {
        assertEquals(WebSearchRunModel.Status.LOADING, WebSearchRunModel.status(true, false, true, 3))
        assertEquals(WebSearchRunModel.Status.FAILED, WebSearchRunModel.status(false, true, true, 0))
        assertEquals(WebSearchRunModel.Status.IDLE, WebSearchRunModel.status(false, false, false, 0))
        assertEquals(WebSearchRunModel.Status.EMPTY, WebSearchRunModel.status(false, false, true, 0))
        assertEquals(WebSearchRunModel.Status.DONE, WebSearchRunModel.status(false, false, true, 2))
    }

    // --------------------------------------------------------- error kinds

    @Test
    fun errorKindsReadTheWireTagOutOfTheApiMessage() {
        assertEquals(
            WebSearchRunModel.ErrorKind.DISABLED,
            WebSearchRunModel.errorKind(400, "HTTP 400 websearch_disabled: disabled"),
        )
        assertEquals(
            WebSearchRunModel.ErrorKind.PROVIDER_NOT_FOUND,
            WebSearchRunModel.errorKind(400, "HTTP 400 websearch_provider_not_found: no"),
        )
        assertEquals(
            WebSearchRunModel.ErrorKind.PROVIDER_REQUIRED,
            WebSearchRunModel.errorKind(400, "HTTP 400 websearch_provider_required: pick"),
        )
        assertEquals(WebSearchRunModel.ErrorKind.OTHER, WebSearchRunModel.errorKind(503, "HTTP 503 boom"))
    }

    // ------------------------------------------------------------- rows

    @Test
    fun titleFallsBackToTheUrlAndHostComesFromTheUrl() {
        val unnamed = result(url = "https://docs.example.com/x?y=1", title = null)
        assertEquals("https://docs.example.com/x?y=1", WebSearchRunModel.titleOf(unnamed))
        assertEquals("docs.example.com", WebSearchRunModel.hostOf(unnamed.url))
        assertEquals("Title", WebSearchRunModel.titleOf(result()))
        // A url the URI parser rejects shows itself whole rather than blanking.
        assertEquals("not a url", WebSearchRunModel.hostOf("not a url"))
    }

    @Test
    fun rowsKeepServerOrderAndFormatThePublishedDate() {
        val rows = WebSearchRunModel.rows(
            listOf(
                result(url = "https://a.example/1", title = "A", published = 0L),
                result(url = "https://b.example/2", title = null, content = null, published = null),
            ),
            zone = utc,
            locale = Locale.US,
        )
        assertEquals(listOf("A", "https://b.example/2"), rows.map { it.title })
        assertEquals(listOf("a.example", "b.example"), rows.map { it.host })
        assertEquals("Snippet", rows[0].snippet)
        assertEquals("", rows[1].snippet)
        assertNull(rows[0].published) // epoch 0 is "no time", like the context tab
        assertNull(rows[1].published)
        val dated = WebSearchRunModel.rows(
            listOf(result(published = 1_760_000_000_000L)),
            zone = utc,
            locale = Locale.US,
        )
        // 2025-10-09 in UTC, MEDIUM format for en_US is "Oct 9, 2025".
        assertEquals("Oct 9, 2025", dated.single().published)
    }

    @Test
    fun anyValueIsTheConsentDocksAnyOption() {
        // The run dock reuses the consent vocabulary; "random" must stay the
        // sentinel `WebSearchModel.ANY_VALUE` so providerArg can drop it.
        assertEquals("random", WebSearchModel.ANY_VALUE)
        val options = WebSearchModel.selectOptions(
            com.xiufeigo.opencodeshell.ui.session.dock.WebSearchKind.CONSENT,
            listOf(WebSearchOption("brave", "Brave")),
        )
        assertEquals(WebSearchModel.ANY_VALUE, options.first().value)
    }
}
