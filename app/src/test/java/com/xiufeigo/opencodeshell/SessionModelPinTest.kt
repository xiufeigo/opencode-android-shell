package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.btw.SessionBtwModel
import com.xiufeigo.opencodeshell.v2.GenerateFailure
import com.xiufeigo.opencodeshell.v2.NoModelException
import com.xiufeigo.opencodeshell.v2.SessionModelSync
import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.V2Transport
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Device v14, findings 1 + 2 — the model a session-scoped call runs under.
 *
 * `session.generate` is `Schema.Struct({ prompt })` and `session.prompt` is the
 * flat `{text, …}` body (`_upstream/packages/protocol/src/groups/session.ts`),
 * so **no call carries a model**: it has to be on the session
 * (`POST /api/session/{id}/model`) before the call, or a session whose `model`
 * is null answers **503 `Missing API key`** — the measured `/btw` failure, with
 * `session.model` still null after the composer had picked Space Bunny Free.
 *
 * Three halves, pinned here:
 *  - the wire: [V2Transport.generateFromContext] pins, then generates;
 *  - the timing: a picker tap lands on `session.model` immediately (and a new
 *    session is born with the pick already on it);
 *  - the refusal: nothing names a model → the call is not made and the failure
 *    is visible (`SessionModelSync.NO_MODEL_MESSAGE` on the error card,
 *    `oc_btw_no_model` in the `/btw` panel).
 */
class SessionModelPinTest {

    private fun RecordedRequest.json(): JSONObject = JSONObject(body.readUtf8())

    private fun transport(server: MockWebServer) = V2Transport(
        V2Api(
            baseHttpUrl = server.url("/").toString().toHttpUrlOrNull()!!,
            username = "opencode",
            password = "opencode",
            http = V2Api.defaultClient(),
            directory = null,
        ),
    )

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    private fun vm() = source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")

    private fun pin(providerID: String, modelID: String, variant: String? = null) =
        SessionModelSync.Pin(providerID, modelID, variant)

    // ------------------------------------------------------ (a) generate 带 model

    @Test
    fun generatePinsTheComposerPickOnTheSessionBeforeAsking() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":{"text":"42"}}"""))

            val text = transport(server).generateFromContext(
                "ses_1",
                "why is the sky blue",
                pin("opencode", "space-bunny-free", "high"),
            )

            assertEquals("42", text)
            // `POST /api/session/{id}/model` first — the entry id + the owning
            // provider + the pinned effort, the same shape `switchModel` sends.
            val switch = server.takeRequest()
            assertEquals("POST", switch.method)
            assertEquals("/api/session/ses_1/model", switch.requestUrl!!.encodedPath)
            val model = switch.json().getJSONObject("model")
            assertEquals("space-bunny-free", model.getString("id"))
            assertEquals("opencode", model.getString("providerID"))
            assertEquals("high", model.getString("variant"))
            // …then the generate, which carries the prompt alone.
            val generate = server.takeRequest()
            assertEquals("POST", generate.method)
            assertEquals("/api/session/ses_1/generate", generate.requestUrl!!.encodedPath)
            assertEquals("why is the sky blue", generate.json().getString("prompt"))
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun anUnpinnedGenerateLeavesTheSessionsOwnSelectionAlone() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":{"text":"ok"}}"""))

            val text = transport(server).generateFromContext("ses_1", "why")

            assertEquals("ok", text)
            val only = server.takeRequest()
            assertEquals("/api/session/ses_1/generate", only.requestUrl!!.encodedPath)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun aBlankVariantIsOmittedFromThePin() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":{"text":"ok"}}"""))

            transport(server).generateFromContext("ses_1", "why", pin("opencode", "space-bunny-free"))

            val model = server.takeRequest().json().getJSONObject("model")
            assertFalse("the server keeps its own default", model.has("variant"))
        }
    }

    @Test
    fun theTransportPinsBeforeItGenerates() {
        val transport = source("src/main/java/com/xiufeigo/opencodeshell/v2/V2Transport.kt")
        val body = transport
            .substringAfter("override fun generateFromContext(")
            .substringBefore("override fun moveSession(")
        assertTrue(body.contains("api.switchModel(sessionID, V2Api.ModelRef(model.modelID, model.providerID, model.variant))"))
        assertTrue(body.contains("api.generate(sessionID, prompt)"))
        assertTrue(
            "the pin must come first",
            body.indexOf("api.switchModel(") < body.indexOf("api.generate("),
        )
    }

    // ------------------------------------------------- (b) 选模写回时机

    @Test
    fun thePickerTapIsWrittenBackToTheSession() {
        val body = vm()
        val select = body.substringAfter("fun selectModel(providerID: String, modelID: String)")
            .substringBefore("/** True when the catalog advertises")
        assertTrue("the tap must land on session.model", select.contains("pinSelectedModel()"))

        val pinBody = body.substringAfter("private fun pinSelectedModel()")
            .substringBefore("/** True when the catalog advertises")
        assertTrue(pinBody.contains("val id = sessionID ?: return"))
        assertTrue(pinBody.contains("transport.switchModel(id, pin.providerID, pin.modelID, pin.variant)"))
    }

    @Test
    fun onlyAnExplicitPickIsWrittenBack() {
        val body = vm().substringAfter("private fun selectedModelPin()")
            .substringBefore("private fun pinSelectedModel()")
        // A *derived* selection (the server default, or the last assistant
        // turn's own model adopted by `refreshSnapshot`) must never be written
        // back as if the user had chosen it.
        assertTrue(body.contains("val ref = modelOverride ?: return null"))
        assertTrue(body.contains("SessionModelSync.Pin(ref.first, ref.second, effectiveVariant())"))
    }

    @Test
    fun aRunningTurnDefersTheWriteBackInsteadOfReconfiguringIt() {
        val body = vm().substringAfter("private fun pinSelectedModel()")
            .substringBefore("/** True when the catalog advertises")
        assertTrue(
            "a mid-turn tap must not reconfigure the running turn",
            body.contains("if (_state.value.runStatus == ChatStore.RunStatus.RUNNING) return"),
        )
    }

    @Test
    fun aNewSessionIsBornWithThePicksModelAlreadyOnIt() {
        val body = vm().substringAfter("fun createSession(")
            .substringBefore("/**\n     * `GET /api/project` read")
        assertTrue(body.contains("selectedModelPin()?.let { pin ->"))
        assertTrue(body.contains("transport.switchModel(newID, pin.providerID, pin.modelID, pin.variant)"))
        assertTrue(
            "the pin lands before the first prompt runs",
            body.indexOf("selectedModelPin()?.let") < body.indexOf("if (!firstPrompt.isNullOrBlank())"),
        )
    }

    @Test
    fun aSteerPinsTheModelTheUserPicked() {
        // The "send carries the model" chain keeps its shape for a pick the user
        // actually made (ModelPickerTest / Wave5ComposerWiringTest assert the
        // same string), but the value written is now the *pin* rather than the
        // derived `_selectedModel` — writing that default is the v28 bug
        // (`SessionModelScopeTest`).
        val body = vm()
        assertTrue(body.contains("t.switchModel(id, pin.providerID, pin.modelID, pin.variant)"))
        assertTrue(body.contains("if (pin == null && resolveModelPlan(t, id) == SessionModelSync.Plan.NoModel)"))
    }

    // --------------------------------------------- (c) 无模型可见错误

    @Test
    fun thePlanPrefersThePickOverAStaleSessionPin() {
        assertEquals(
            SessionModelSync.Plan.PinFirst(pin("opencode", "space-bunny-free")),
            SessionModelSync.plan(
                selected = pin("opencode", "space-bunny-free"),
                serverDefault = null,
                sessionProviderID = "opencode",
                sessionModelID = "muse-spark-1.3-contributor-free",
            ),
        )
    }

    @Test
    fun aSessionOrServerModelNeedsNoWrite() {
        assertEquals(
            SessionModelSync.Plan.SessionModel,
            SessionModelSync.plan(null, null, "openai", "gpt-5.6"),
        )
        assertEquals(
            SessionModelSync.Plan.SessionModel,
            SessionModelSync.plan(null, pin("opencode", "space-bunny-free"), null, null),
        )
        // A half-filled session ref is not a model.
        assertEquals(SessionModelSync.Plan.NoModel, SessionModelSync.plan(null, null, "openai", null))
        assertEquals(SessionModelSync.Plan.NoModel, SessionModelSync.plan(null, null, null, "gpt-5.6"))
    }

    @Test
    fun nothingAnywhereIsARefusalNotARequest() {
        assertEquals(SessionModelSync.Plan.NoModel, SessionModelSync.plan(null, null, null, null))
    }

    @Test
    fun theRefusalCarriesTheLineTheErrorCardRenders() {
        val line = SessionModelSync.NO_MODEL_MESSAGE
        assertTrue(line.contains("No model available"))
        assertEquals(line, NoModelException().message)
        // The store error channel the card reads is the same one every other
        // failure uses, so the refusal is classified and shown like the rest.
        val body = vm().substringAfter("fun generateFromContext(prompt: String")
            .substringBefore("private suspend fun resolveModelPlan")
        assertTrue(body.contains("SessionModelSync.NO_MODEL_MESSAGE"))
        assertTrue(body.contains("ChatStore.Event.RunStatusChanged("))
        assertTrue(body.contains("ChatStore.RunStatus.ERROR"))
    }

    @Test
    fun theViewModelRefusesLocallyInsteadOfSpendingADoomedRequest() {
        val body = vm().substringAfter("fun generateFromContext(prompt: String")
            .substringBefore("private suspend fun resolveModelPlan")
        // The refusal branch never reaches the transport…
        val refusal = body.substringAfter("SessionModelSync.Plan.NoModel ->")
            .substringBefore("} catch (e: Exception)")
        assertFalse(refusal.contains("transport.generateFromContext("))
        assertTrue(refusal.contains("GenerateFailure.NO_MODEL"))
        // …and the pin branch is the only one that writes a model.
        val pinned = body.substringAfter("is SessionModelSync.Plan.PinFirst ->")
            .substringBefore("SessionModelSync.Plan.SessionModel ->")
        assertTrue(pinned.contains("transport.generateFromContext(id, prompt, plan.pin)"))
    }

    @Test
    fun theFirstPromptOfAModelLessSessionIsRefusedToo() {
        val body = vm().substringAfter("fun submit(")
            .substringBefore("private fun filesOf(")
        assertTrue(body.contains("throw NoModelException()"))
        assertTrue(
            "the refusal sits on the steer branch, before the POST",
            body.indexOf("throw NoModelException()") < body.indexOf("sendStructuredPrompt("),
        )
    }

    @Test
    fun theBtwPanelNamesTheNoModelRefusal() {
        val panel = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/btw/SessionBtwPanel.kt")
        assertTrue(panel.contains("R.string.oc_btw_no_model"))
        assertTrue(panel.contains("SessionBtwModel.Reason.NO_MODEL"))
        // The upstream error copy + retry survive next to it.
        assertTrue(panel.contains("R.string.oc_btw_error"))
        assertTrue(panel.contains("R.string.oc_btw_retry"))

        val host = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/btw/SessionBtwHost.kt")
        assertTrue(host.contains("model.fail(handle, SessionBtwModel.Reason.of(failure))"))

        val model = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/btw/SessionBtwModel.kt")
        assertTrue(model.contains("enum class Reason {"))
        assertTrue(model.contains("fun of(failure: GenerateFailure?): Reason"))
        assertTrue(model.contains("fun fail(ask: Ask, reason: Reason = Reason.FAILED)"))
        // The mapping itself: only the local refusal is named.
        assertEquals(SessionBtwModel.Reason.NO_MODEL, SessionBtwModel.Reason.of(GenerateFailure.NO_MODEL))
        assertEquals(SessionBtwModel.Reason.FAILED, SessionBtwModel.Reason.of(GenerateFailure.REQUEST_FAILED))
        assertEquals(SessionBtwModel.Reason.FAILED, SessionBtwModel.Reason.of(null))
    }

    @Test
    fun theNoModelCopyExistsInEveryLocale() {
        for (dir in listOf("values", "values-zh-rCN", "values-zh-rTW")) {
            val xml = source("src/main/res/$dir/strings.xml")
            assertTrue("$dir missing oc_btw_no_model", xml.contains("name=\"oc_btw_no_model\""))
        }
    }

    @Test
    fun theGenerateCallbackNamesTheFailure() {
        // The panel's error state is driven by this enum, so a null text alone
        // can never be mistaken for "no reason to show".
        assertEquals(
            listOf("NO_MODEL", "REQUEST_FAILED"),
            GenerateFailure.values().map { it.name },
        )
    }
}
