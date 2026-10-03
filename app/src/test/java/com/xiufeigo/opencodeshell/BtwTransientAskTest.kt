package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.btw.SessionBtwModel
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
 * The **persistence path** of the `/btw` side question — the ruling device v32
 * asked for, in test form.
 *
 * Upstream `/btw` is a one-shot side question, not a durable side panel: the
 * plugin that owns it calls `context.client.session.generate({sessionID,
 * prompt})` (`_upstream/packages/tui/src/feature-plugins/prompt/btw.tsx:68-69`)
 * and shows the reply in a centered modal dialog (`:71-75`), and the route it
 * calls is documented "Generate transient text from the current session context
 * **without mutating session history**"
 * (`_upstream/packages/protocol/src/groups/session.ts:705-721`). The Q&A is
 * therefore absent from `GET /api/session/{id}/message` **by design**, and losing
 * it on a reload is an intentional deviation from "the session is durable", not
 * a shell gap. [SessionModelPinTest] already pins the *shape* of that call (pin
 * then generate); what is pinned here is that the ask is **only** that call —
 * nothing in the path can append a transcript row.
 */
class BtwTransientAskTest {

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

    @Test
    fun theAskIsTheTransientGenerateBodyAndNotAPromptBody() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":{"text":"FOUR"}}"""))

            val text = transport(server)
                .generateFromContext("ses_1", SessionBtwModel.prompt("reply with the single word FOUR"))

            assertEquals("FOUR", text)
            // One request, and it is the generate — not `POST …/prompt`.
            assertEquals(1, server.requestCount)
            val ask = server.takeRequest()
            assertEquals("POST", ask.method)
            assertEquals("/api/session/ses_1/generate", ask.requestUrl!!.encodedPath)
            val body = ask.json()
            // `session.generate` is `Schema.Struct({ prompt })`; the prompt route
            // is the flat `{text, files?, delivery?, …}` body. The single key is
            // what makes the call unable to write a turn.
            assertEquals(setOf("prompt"), body.keys().asSequence().toSet())
            assertEquals(
                SessionBtwModel.prompt("reply with the single word FOUR"),
                body.getString("prompt"),
            )
        }
    }

    @Test
    fun anUnpinnedAskIsOneRequestAndWritesNothingElse() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":{"text":"ok"}}"""))

            transport(server).generateFromContext("ses_1", "why", null)

            // No `/model` write, no message write, no turn — the pin is the only
            // thing that may precede the generate, and only when a model is
            // missing (`SessionModelPinTest`).
            assertEquals(1, server.requestCount)
            val ask = server.takeRequest()
            assertFalse("the generate carries no model of its own", ask.json().has("model"))
        }
    }

    @Test
    fun nothingInTheBtwPackageCanAppendATranscriptRow() {
        // The mechanism that would make the Q&A durable is a prompt send; the
        // package's whole surface is the transient ask, so it has none.
        for (file in listOf("SessionBtwPanel.kt", "SessionBtwHost.kt", "SessionBtwModel.kt")) {
            val src = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/btw/$file")
            for (send in listOf("sendStructuredPrompt", "sendSynthetic", "sendShell", "sendCommand")) {
                assertFalse("$file must not send a turn ($send)", src.contains(send))
            }
        }
        val transport = source("src/main/java/com/xiufeigo/opencodeshell/v2/V2Transport.kt")
            .substringAfter("override fun generateFromContext(")
            .substringBefore("override fun moveSession(")
        assertTrue(transport.contains("api.generate(sessionID, prompt)"))
        assertFalse("the ask never routes through the prompt body", transport.contains("api.prompt("))
    }

    @Test
    fun theReloadLossIsUpstreamsOwnSemanticsAndTheHostRecordsIt() {
        // Guarded as prose on purpose: the ruling is what stops the next reader
        // from "fixing" the missing transcript row by writing one — which would
        // make `/btw` a normal turn and contradict upstream's own route doc.
        val host = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/btw/SessionBtwHost.kt")
        assertTrue(host.contains("without mutating session history"))
        assertTrue(host.contains("by design"))
        assertTrue(host.contains("intentional deviation"))
    }

    @Test
    fun theShellKeepsTheAnswerForTheProcessBeyondUpstreamsDialog() {
        // Upstream's dialog is gone the moment it is cleared; the shell keeps the
        // slot per session key, so a dismiss/reopen does not throw the answer
        // away. That is the *only* persistence `/btw` gets, and it is in memory.
        val model = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/btw/SessionBtwModel.kt")
        assertTrue(model.contains("private val states = mutableMapOf<String, State>()"))
        assertFalse("no disk: the slot is in-memory state", model.contains("SharedPreferences"))
        assertFalse(model.contains("File("))
    }
}
