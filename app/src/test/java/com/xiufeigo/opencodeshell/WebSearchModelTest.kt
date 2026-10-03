package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.dock.FormField
import com.xiufeigo.opencodeshell.ui.session.dock.FormOption
import com.xiufeigo.opencodeshell.ui.session.dock.WebSearchBridge
import com.xiufeigo.opencodeshell.ui.session.dock.WebSearchKind
import com.xiufeigo.opencodeshell.ui.session.dock.WebSearchModel
import com.xiufeigo.opencodeshell.ui.session.dock.WebSearchOption
import com.xiufeigo.opencodeshell.ui.session.dock.WebSearchStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The pure rules of `_upstream/packages/app/src/session/requests/websearch.ts`
 * + `session-websearch-dock.tsx`, and the two wire shapes of `websearch.test.ts`:
 *
 * - consent: one `{key:"choice", type:"string", custom:false}` field,
 * - provider: a `provider` string field with options.
 *
 * [WebSearchBridge] is the cross-form half of `replyWebSearch`'s `"choose"`
 * handoff and of `replyForm`'s optimistic-removal rollback (a submit that comes
 * back is a failure).
 */
class WebSearchModelTest {

    @Before
    fun resetBridge() {
        WebSearchBridge.reset()
    }

    // -------------------------------------------------------------- fixtures

    private val exa = WebSearchOption("exa", "Exa")
    private val parallel = WebSearchOption("parallel", "Parallel")

    private val consentFields = listOf(
        FormField.Text(key = "choice", title = "", description = "", required = true, custom = false),
    )
    private val providerFields = listOf(
        FormField.Text(
            key = "provider",
            title = "",
            description = "",
            required = true,
            custom = false,
            options = listOf(FormOption("exa", "Exa", null), FormOption("parallel", "Parallel", null)),
        ),
    )

    // ------------------------------------------------------------- detection

    @Test
    fun detectsTheTwoWebsearchShapesAndNothingElse() {
        assertEquals(WebSearchKind.CONSENT, WebSearchModel.detect(consentFields))
        assertEquals(WebSearchKind.PROVIDER, WebSearchModel.detect(providerFields))
        assertNull(WebSearchModel.detect(emptyList()))
        // A general form with several fields is not a websearch ask.
        assertNull(
            WebSearchModel.detect(
                listOf(
                    FormField.Text(key = "choice", title = "", description = "", custom = false),
                    FormField.Text(key = "other", title = "", description = ""),
                ),
            ),
        )
        // A `choice` field that still allows a custom answer is a normal question.
        assertNull(
            WebSearchModel.detect(
                listOf(FormField.Text(key = "choice", title = "", description = "", custom = true)),
            ),
        )
    }

    @Test
    fun detectsFromRawFieldsJson() {
        val consent = """[{"key":"choice","type":"string","required":true,"custom":false}]"""
        val provider = """
            [{"key":"provider","type":"string","required":true,"custom":false,
              "options":[{"value":"exa","label":"Exa"}]}]
        """.trimIndent()
        assertEquals(WebSearchKind.CONSENT, WebSearchModel.detect(consent))
        assertEquals(WebSearchKind.PROVIDER, WebSearchModel.detect(provider))
        assertNull(WebSearchModel.detect("[]"))
        assertNull(WebSearchModel.detect("not json"))
    }

    @Test
    fun formOptionsReadTheProviderFieldOnly() {
        assertEquals(listOf(exa, parallel), WebSearchModel.formOptions(WebSearchKind.PROVIDER, providerFields))
        assertTrue(WebSearchModel.formOptions(WebSearchKind.CONSENT, providerFields).isEmpty())
    }

    // ------------------------------------------------- selection + enablement

    @Test
    fun theConsentSelectOffersAnyOnTopTheCatalog() {
        val options = WebSearchModel.selectOptions(WebSearchKind.CONSENT, listOf(exa))
        assertEquals(listOf(WebSearchModel.ANY_VALUE, "exa"), options.map { it.value })
        assertEquals(listOf("exa"), WebSearchModel.selectOptions(WebSearchKind.PROVIDER, listOf(exa)).map { it.value })
    }

    @Test
    fun selectedFollowsUpstreamMemos() {
        // Consent keeps the pick, defaulting to "Any".
        assertEquals(WebSearchModel.ANY_VALUE, WebSearchModel.selected(WebSearchKind.CONSENT, listOf(exa), null))
        assertEquals("exa", WebSearchModel.selected(WebSearchKind.CONSENT, listOf(exa), "exa"))
        // Provider falls back to the first option when the pick is gone.
        assertEquals("exa", WebSearchModel.selected(WebSearchKind.PROVIDER, listOf(exa, parallel), null))
        assertEquals("parallel", WebSearchModel.selected(WebSearchKind.PROVIDER, listOf(exa, parallel), "parallel"))
        assertEquals("exa", WebSearchModel.selected(WebSearchKind.PROVIDER, listOf(exa, parallel), "removed"))
        assertNull(WebSearchModel.selected(WebSearchKind.PROVIDER, emptyList(), null))
    }

    @Test
    fun unavailableAndSubmitMatchTheDockButtons() {
        assertTrue(WebSearchModel.unavailable(emptyList(), loading = false, loadFailed = false))
        assertTrue(WebSearchModel.unavailable(listOf(exa), loading = true, loadFailed = false))
        assertTrue(WebSearchModel.unavailable(listOf(exa), loading = false, loadFailed = true))
        assertFalse(WebSearchModel.unavailable(listOf(exa), loading = false, loadFailed = false))

        assertTrue(
            WebSearchModel.canSubmit(
                WebSearchKind.CONSENT, listOf(exa), WebSearchModel.ANY_VALUE,
                loading = false, loadFailed = false, sending = false,
            ),
        )
        assertFalse(
            WebSearchModel.canSubmit(
                WebSearchKind.CONSENT, listOf(exa), WebSearchModel.ANY_VALUE,
                loading = false, loadFailed = false, sending = true,
            ),
        )
        assertFalse(
            WebSearchModel.canSubmit(
                WebSearchKind.CONSENT, emptyList(), WebSearchModel.ANY_VALUE,
                loading = false, loadFailed = false, sending = false,
            ),
        )
    }

    @Test
    fun statusMirrorsUpstreamStatusLine() {
        assertEquals(WebSearchStatus.LOADING, WebSearchModel.status(emptyList(), loading = true, failed = false))
        assertEquals(WebSearchStatus.FAILED, WebSearchModel.status(listOf(exa), loading = false, failed = true))
        assertEquals(WebSearchStatus.EMPTY, WebSearchModel.status(emptyList(), loading = false, failed = false))
        assertNull(WebSearchModel.status(listOf(exa), loading = false, failed = false))
        assertTrue(WebSearchModel.offersDisable(WebSearchKind.CONSENT))
        assertFalse(WebSearchModel.offersDisable(WebSearchKind.PROVIDER))
    }

    // ---------------------------------------------------------------- replies

    @Test
    fun consentAnswersAllowDisableChoose() {
        assertEquals(mapOf("choice" to "allow"), WebSearchModel.answer(WebSearchKind.CONSENT, "random"))
        assertEquals(mapOf("choice" to "disable"), WebSearchModel.answer(WebSearchKind.CONSENT, null))
        assertEquals(mapOf("choice" to "choose"), WebSearchModel.answer(WebSearchKind.CONSENT, "exa"))
        assertTrue(WebSearchModel.armsHandoff(WebSearchKind.CONSENT, "exa"))
        assertFalse(WebSearchModel.armsHandoff(WebSearchKind.CONSENT, "random"))
        assertFalse(WebSearchModel.armsHandoff(WebSearchKind.CONSENT, null))
    }

    @Test
    fun providerAnswersDirectlyAndNeverDisables() {
        assertEquals(mapOf("provider" to "exa"), WebSearchModel.answer(WebSearchKind.PROVIDER, "exa"))
        // `websearch.test.ts` "does not misrepresent cancelling the provider form".
        assertNull(WebSearchModel.answer(WebSearchKind.PROVIDER, null))
        assertNull(WebSearchModel.answer(WebSearchKind.PROVIDER, "random"))
    }

    @Test
    fun handoffAnswerNeedsThePickToStillBeOffered() {
        assertEquals(mapOf("provider" to "parallel"), WebSearchModel.handoffAnswer(listOf(exa, parallel), "parallel"))
        assertNull(WebSearchModel.handoffAnswer(listOf(exa), "removed"))
        assertNull(WebSearchModel.handoffAnswer(listOf(exa), null))
    }

    // ----------------------------------------------------------------- bridge

    @Test
    fun bridgeHandsThePickToTheProviderFormOnce() {
        WebSearchBridge.armHandoff("parallel")
        assertEquals("parallel", WebSearchBridge.takeHandoff(listOf("exa", "parallel")))
        // Second take finds nothing armed ("ends a pending handoff").
        assertNull(WebSearchBridge.takeHandoff(listOf("exa", "parallel")))
    }

    @Test
    fun bridgeDropsAPickTheChangedCatalogNoLongerOffers() {
        WebSearchBridge.armHandoff("removed")
        assertNull(WebSearchBridge.takeHandoff(listOf("exa")))
    }

    @Test
    fun bridgeReadsARolledBackSubmitAsAFailure() {
        WebSearchBridge.markSubmitted("frm_1")
        assertTrue(WebSearchBridge.consumeSubmitFailure("frm_1"))
        // The mark is one-shot: a re-render of another form is not a failure.
        assertFalse(WebSearchBridge.consumeSubmitFailure("frm_1"))
        WebSearchBridge.markSubmitted("frm_2")
        assertFalse(WebSearchBridge.consumeSubmitFailure("frm_3"))
    }
}
