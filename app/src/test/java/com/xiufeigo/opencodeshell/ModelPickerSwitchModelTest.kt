package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.composer.ModelPickerItems
import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.V2Transport
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The switch half of the model-ref chain, on the wire: the ref the picker
 * produces must reach
 * `POST /api/session/{id}/model` as `{model:{id,providerID,variant?}}` with
 *
 *  - `id` = the tapped row's **catalog entry** id, not the `modelID` it shares
 *    with its siblings (live `openai`: `gpt-5.6`, `gpt-5.6-fast`, `gpt-5.6-pro`
 *    are three entries of `modelID: "gpt-5.6"`; the server matches
 *    `session.model.id` against `Model.Info.id`,
 *    `_upstream/packages/core/src/session/runner/model.ts`), and
 *  - `providerID` = the owning picker group — the Zen/Go rule
 *    (`space-bunny-free` under both `opencode` and `opencode-go`) that
 *    `ModelPickerTest.selectionRefCarriesTheTappedRowsOwnGroupProvider` pins at
 *    the data level, and
 *  - `variant` = the pinned reasoning-effort label, omitted when the user pinned
 *    none so the server keeps its own default.
 */
class ModelPickerSwitchModelTest {

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

    private fun entry(
        entryID: String,
        modelID: String,
        providerID: String,
        name: String,
        variants: List<String> = listOf("none", "low", "medium", "high", "xhigh"),
    ): OpenCodeClient.Providers.Model {
        val variantsJson = JSONArray()
        for (variant in variants) variantsJson.put(JSONObject().put("id", variant))
        return OpenCodeClient.Providers.Model.fromV2(
            V2Api.ModelInfo.fromJson(
                JSONObject()
                    .put("id", entryID)
                    .put("modelID", modelID)
                    .put("providerID", providerID)
                    .put("name", name)
                    .put("status", "active")
                    .put("enabled", true)
                    .put("variants", variantsJson),
            ),
        )
    }

    /** The live `openai` shape: three entries under one `modelID`. */
    private val openai = OpenCodeClient.Providers(
        all = listOf(
            OpenCodeClient.Providers.Provider(
                id = "openai",
                name = "OpenAI",
                models = listOf(
                    entry("gpt-5.6", "gpt-5.6", "openai", "GPT-5.6"),
                    entry("gpt-5.6-fast", "gpt-5.6", "openai", "GPT-5.6 Fast"),
                    entry("gpt-5.6-pro", "gpt-5.6", "openai", "GPT-5.6 Pro"),
                ),
            ),
        ),
        connected = emptyList(),
        default = emptyMap(),
    )

    /** Zen/Go overlap: one model id, two providers. */
    private val zenGo = OpenCodeClient.Providers(
        all = listOf(
            OpenCodeClient.Providers.Provider(
                id = "opencode-go",
                name = "OpenCode Go",
                models = listOf(entry("space-bunny-free", "space-bunny-free", "opencode-go", "Space Bunny Free")),
            ),
            OpenCodeClient.Providers.Provider(
                id = "opencode",
                name = "OpenCode Zen",
                models = listOf(entry("space-bunny-free", "space-bunny-free", "opencode", "Space Bunny Free")),
            ),
        ),
        connected = emptyList(),
        default = emptyMap(),
    )

    private fun group(providers: OpenCodeClient.Providers, providerID: String): ModelPickerItems.Group =
        ModelPickerItems.groups(providers, emptyMap(), includeUnconnected = true)
            .single { it.providerID == providerID }

    @Test
    fun switchModelPostsTheEntryIdProviderAndVariant() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            transport(server).switchModel(
                sessionID = "ses_1",
                providerID = "openai",
                modelID = "gpt-5.6-fast",
                variant = "max",
            )
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/session/ses_1/model", request.requestUrl!!.encodedPath)
            val model = request.json().getJSONObject("model")
            assertEquals("gpt-5.6-fast", model.getString("id"))
            assertEquals("openai", model.getString("providerID"))
            assertEquals("max", model.getString("variant"))
        }
    }

    @Test
    fun anUnpinnedVariantLeavesTheKeyOff() {
        for (variant in listOf(null, "", "  ")) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(204))
                transport(server).switchModel("ses_1", "openai", "gpt-5.6", variant)
                val model = server.takeRequest().json().getJSONObject("model")
                assertEquals("gpt-5.6", model.getString("id"))
                assertFalse("variant=\"$variant\" must not be sent", model.has("variant"))
            }
        }
    }

    @Test
    fun theTappedSiblingIsWhatGetsPosted() {
        val openaiGroup = group(openai, "openai")
        val tapped = openaiGroup.models.first { it.name == "GPT-5.6 Pro" }
        val (providerID, refID) = ModelPickerItems.selectionRef(openaiGroup, tapped)
        assertEquals("openai", providerID)
        assertEquals("gpt-5.6-pro", refID)
        // The row carries the shared wire modelID — which is exactly what must
        // NOT be posted.
        assertEquals("gpt-5.6", tapped.id)

        val variant = ModelPickerItems.resolveVariant("high", tapped.variants)
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            transport(server).switchModel("ses_1", providerID, refID, variant)
            val model = server.takeRequest().json().getJSONObject("model")
            assertEquals("gpt-5.6-pro", model.getString("id"))
            assertNotEquals("gpt-5.6", model.getString("id"))
            assertEquals("openai", model.getString("providerID"))
            assertEquals("high", model.getString("variant"))
        }
    }

    @Test
    fun eachSiblingPostsItsOwnEntryId() {
        val openaiGroup = group(openai, "openai")
        val refs = openaiGroup.models.map { model ->
            val (providerID, refID) = ModelPickerItems.selectionRef(openaiGroup, model)
            providerID to refID
        }
        assertEquals(
            listOf(
                "openai" to "gpt-5.6",
                "openai" to "gpt-5.6-fast",
                "openai" to "gpt-5.6-pro",
            ),
            refs,
        )
        MockWebServer().use { server ->
            repeat(refs.size) { server.enqueue(MockResponse().setResponseCode(204)) }
            val transport = transport(server)
            for ((providerID, refID) in refs) {
                transport.switchModel("ses_1", providerID, refID, null)
            }
            assertEquals(
                listOf("gpt-5.6", "gpt-5.6-fast", "gpt-5.6-pro"),
                refs.map { server.takeRequest().json().getJSONObject("model").getString("id") },
            )
        }
    }

    @Test
    fun theZenRowPostsZenEvenWhenGoSortsFirst() {
        // `opencode-go` heads the picker's popular order, so a ref built from a
        // bare model id would land on Go and run keyless (`Missing API key`).
        val zen = group(zenGo, "opencode")
        val go = group(zenGo, "opencode-go")
        val zenRef = ModelPickerItems.selectionRef(zen, zen.models.single())
        val goRef = ModelPickerItems.selectionRef(go, go.models.single())
        assertEquals("opencode" to "space-bunny-free", zenRef)
        assertEquals("opencode-go" to "space-bunny-free", goRef)

        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setResponseCode(204))
            val transport = transport(server)
            transport.switchModel("ses_1", zenRef.first, zenRef.second, null)
            transport.switchModel("ses_1", goRef.first, goRef.second, null)
            assertEquals(
                "opencode",
                server.takeRequest().json().getJSONObject("model").getString("providerID"),
            )
            assertEquals(
                "opencode-go",
                server.takeRequest().json().getJSONObject("model").getString("providerID"),
            )
        }
    }
}
