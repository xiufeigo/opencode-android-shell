package com.xiufeigo.opencodeshell

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `GET /provider` parsing (`{all, default, connected}`, `research/04` §3.1),
 * the `model` field on `prompt_async`, and the `"providerID/modelID"`
 * reference codec backing the picker's persisted override.
 */
class OpenCodeClientProvidersTest {

    private fun client(server: MockWebServer) =
        OpenCodeClient(server.url("/").toString().toHttpUrlOrNull()!!, null, null)

    // ------------------------------------------------------------- parsing

    @Test
    fun providersParsesAllConnectedAndDefault() {
        val body = """
            {
              "all": [
                {
                  "id": "anthropic",
                  "name": "Anthropic",
                  "models": {
                    "claude-sonnet-4": {
                      "id": "claude-sonnet-4",
                      "name": "Claude Sonnet 4",
                      "capabilities": {"reasoning": true, "attachment": true, "toolcall": true}
                    },
                    "claude-haiku-3.5": {"id": "claude-haiku-3.5", "name": "Claude Haiku 3.5"}
                  }
                },
                {
                  "id": "openai",
                  "name": "OpenAI",
                  "models": {
                    "gpt-5": {"id": "gpt-5", "name": "GPT-5"}
                  }
                }
              ],
              "connected": ["anthropic"],
              "default": {"anthropic": "claude-sonnet-4"}
            }
        """.trimIndent()
        val providers = OpenCodeClient.Providers.fromJson(JSONObject(body))

        assertEquals(2, providers.all.size)
        assertEquals(listOf("anthropic"), providers.connected)
        assertEquals(mapOf("anthropic" to "claude-sonnet-4"), providers.default)

        val anthropic = providers.all[0]
        assertEquals("anthropic", anthropic.id)
        assertEquals("Anthropic", anthropic.name)
        assertEquals(2, anthropic.models.size)

        val sonnet = providers.model("anthropic", "claude-sonnet-4")!!
        assertEquals("Claude Sonnet 4", sonnet.name)
        assertEquals(true, sonnet.capabilities?.reasoning)
        assertEquals(true, sonnet.capabilities?.toolCall)

        val haiku = providers.model("anthropic", "claude-haiku-3.5")!!
        assertNull("capabilities is optional", haiku.capabilities)

        assertNull(providers.model("openai", "nope"))
        assertNull(providers.model("nope", "gpt-5"))
    }

    @Test
    fun providersTolerateModelsAsArray() {
        val body = """
            {
              "all": [
                {
                  "id": "openai",
                  "name": "OpenAI",
                  "models": [
                    {"id": "gpt-5", "name": "GPT-5"},
                    {"id": "gpt-4o", "name": "GPT-4o", "capabilities": {"tools": false}}
                  ]
                }
              ],
              "connected": ["openai"],
              "default": {}
            }
        """.trimIndent()
        val providers = OpenCodeClient.Providers.fromJson(JSONObject(body))
        val models = providers.all.single().models
        assertEquals(listOf("gpt-5", "gpt-4o"), models.map { it.id })
        // v2 capability shape (`tools`) maps onto toolCall.
        assertEquals(false, models[1].capabilities?.toolCall)
        assertNull(models[1].capabilities?.reasoning)
    }

    @Test
    fun providersTolerateMissingFieldsAndJunkEntries() {
        val body = """
            {
              "all": [
                {"id": "bare"},
                {"name": "no id"},
                "junk",
                {
                  "id": "mapped",
                  "models": {"orphan": {"name": "No id field"}}
                }
              ],
              "connected": ["bare", ""],
              "default": {"bare": "", "": "x"}
            }
        """.trimIndent()
        val providers = OpenCodeClient.Providers.fromJson(JSONObject(body))

        // Provider without a name falls back to its id; entries without an id
        // and non-object entries are dropped.
        assertEquals(listOf("bare", "mapped"), providers.all.map { it.id })
        assertEquals("bare", providers.all[0].name)
        assertEquals(emptyList<OpenCodeClient.Providers.Model>(), providers.all[0].models)

        // Map-key is the fallback model id; name falls back to that id.
        val orphan = providers.model("mapped", "orphan")!!
        assertEquals("orphan", orphan.id)
        assertEquals("No id field", orphan.name)

        assertEquals(listOf("bare"), providers.connected)
        assertEquals(emptyMap<String, String>(), providers.default)
    }

    @Test
    fun providersEmptyWhenSectionsMissing() {
        val providers = OpenCodeClient.Providers.fromJson(JSONObject("{}"))
        assertTrue(providers.all.isEmpty())
        assertTrue(providers.connected.isEmpty())
        assertTrue(providers.default.isEmpty())
        assertTrue(providers.connectedProviders().isEmpty())
    }

    @Test
    fun connectedProvidersFiltersInCatalogOrder() {
        val body = """
            {
              "all": [
                {"id": "openai", "name": "OpenAI", "models": {}},
                {"id": "anthropic", "name": "Anthropic", "models": {}},
                {"id": "google", "name": "Google", "models": {}}
              ],
              "connected": ["google", "openai"],
              "default": {}
            }
        """.trimIndent()
        val providers = OpenCodeClient.Providers.fromJson(JSONObject(body))
        assertEquals(listOf("openai", "google"), providers.connectedProviders().map { it.id })
    }

    @Test
    fun providersEndpointIsGetProviderWithDirectory() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"all":[],"connected":[],"default":{}}"""),
            )
            val c = client(server)
            val providers = c.providers(directory = "/w")
            assertTrue(providers.all.isEmpty())
            val recorded = server.takeRequest()
            assertEquals("GET", recorded.method)
            assertEquals("/provider", recorded.requestUrl!!.encodedPath)
            assertEquals("/w", recorded.requestUrl!!.queryParameter("directory"))
            c.close()
        }
    }

    // ------------------------------------------------------- prompt payload

    @Test
    fun promptAsyncIncludesModelObjectWhenProvided() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            val c = client(server)
            c.promptAsync("ses_1", "hi", model = "anthropic" to "claude-sonnet-4")
            val body = JSONObject(server.takeRequest().body.readUtf8())
            val model = body.getJSONObject("model")
            assertEquals("anthropic", model.getString("providerID"))
            assertEquals("claude-sonnet-4", model.getString("modelID"))
            assertEquals("hi", body.getJSONArray("parts").getJSONObject(0).getString("text"))
            c.close()
        }
    }

    @Test
    fun promptAsyncOmitsModelWhenNull() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            val c = client(server)
            c.promptAsync("ses_1", "hi")
            val body = JSONObject(server.takeRequest().body.readUtf8())
            assertFalse(body.has("model"))
            c.close()
        }
    }

    // ------------------------------------------------- model ref codec

    @Test
    fun modelRefRoundTrips() {
        val ref = "anthropic" to "claude-sonnet-4"
        assertEquals("anthropic/claude-sonnet-4", OpenCodeClient.formatModelRef(ref))
        assertEquals(ref, OpenCodeClient.parseModelRef("anthropic/claude-sonnet-4"))
    }

    @Test
    fun modelRefSplitsOnTheFirstSlash() {
        // Upstream `Model.Ref.parse`: provider ids never contain '/', model
        // ids may (e.g. OpenRouter's "openai/gpt-5").
        assertEquals(
            "openrouter" to "openai/gpt-5",
            OpenCodeClient.parseModelRef("openrouter/openai/gpt-5"),
        )
    }

    @Test
    fun modelRefRejectsMalformedValues() {
        assertNull(OpenCodeClient.parseModelRef(null))
        assertNull(OpenCodeClient.parseModelRef(""))
        assertNull(OpenCodeClient.parseModelRef("   "))
        assertNull(OpenCodeClient.parseModelRef("noslash"))
        assertNull(OpenCodeClient.parseModelRef("/model"))
        assertNull(OpenCodeClient.parseModelRef("provider/"))

        assertNull(OpenCodeClient.formatModelRef(null))
        assertNull(OpenCodeClient.formatModelRef("" to "m"))
        assertNull(OpenCodeClient.formatModelRef("p" to "  "))
    }
}
