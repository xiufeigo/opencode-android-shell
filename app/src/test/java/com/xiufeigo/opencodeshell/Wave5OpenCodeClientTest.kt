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
 * W5-C data layer, wire side: the three read-only catalogs (`GET /agent`,
 * `GET /command`, `GET /skill`), the `variants` keys of a catalog model, and the
 * two write bodies the composer needed — `POST /session/:id/shell` and
 * `POST /session/:id/command`.
 *
 * The response shapes asserted here are the ones measured on opencode 1.18.31
 * (see `research/wave5_composer.md` §1).
 */
class Wave5OpenCodeClientTest {

    private fun client(server: MockWebServer) =
        OpenCodeClient(server.url("/").toString().toHttpUrlOrNull()!!, null, null)

    // ---------------------------------------------------------- `GET /agent`

    @Test
    fun agentsParseModeHiddenVariantAndThePinnedModel() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    [{"name":"build","description":"The default agent.","mode":"primary","native":true,
                      "hidden":null,"variant":"high","model":{"providerID":"openai","modelID":"gpt-5.6-sol"}},
                     {"name":"plan","description":"Read-only planner.","mode":"primary","native":true},
                     {"name":"title","description":"","mode":"primary","native":true,"hidden":true},
                     {"name":"explore","description":"sub agent","mode":"subagent","native":false}]
                    """.trimIndent(),
                ),
            )
            val agents = client(server).agents()
            assertEquals("/agent", server.takeRequest().requestUrl!!.encodedPath)
            assertEquals(4, agents.size)

            val build = agents.first()
            assertEquals("build", build.name)
            assertTrue(build.primary)
            assertFalse(build.hidden)
            assertEquals("high", build.variant)
            assertEquals("openai", build.providerID)
            assertEquals("gpt-5.6-sol", build.modelID)

            // The composer's chip only offers named, primary, visible agents.
            assertEquals(listOf("build", "plan"), OpenCodeClient.Agent.selectable(agents).map { it.name })
            assertEquals("build", OpenCodeClient.Agent.defaultName(agents))
            assertEquals("build", OpenCodeClient.Agent.defaultName(emptyList()))
            assertEquals("build", OpenCodeClient.Agent.FALLBACK_AGENT)
        }
    }

    // -------------------------------------------------- `GET /command` + skill

    @Test
    fun commandsKeepTheirSourceAndTemplate() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    [{"name":"init","description":"guided AGENTS.md setup","source":"command",
                      "template":"Create or update `AGENTS.md`.\n\n${'$'}ARGUMENTS","hints":[]},
                     {"name":"kb","description":"knowledge base","source":"skill","template":"kb"},
                     {"description":"no name","source":"command","template":""}]
                    """.trimIndent(),
                ),
            )
            val commands = client(server).commands()
            assertEquals("/command", server.takeRequest().requestUrl!!.encodedPath)
            assertEquals(listOf("init", "kb"), commands.map { it.name })
            assertEquals("skill", commands[1].source)
            assertTrue(commands[0].template.contains("\$ARGUMENTS"))
        }
    }

    @Test
    fun skillsParseNameAndDescriptionOnly() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """[{"name":"customize-opencode","description":"Only for opencode config","location":"<built-in>","content":"…"}]""",
                ),
            )
            val skills = client(server).skills()
            assertEquals("/skill", server.takeRequest().requestUrl!!.encodedPath)
            assertEquals(1, skills.size)
            assertEquals("customize-opencode", skills[0].name)
            assertEquals("<built-in>", skills[0].location)
        }
    }

    @Test
    fun healthIsAJsonProbe() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"healthy":true,"version":"1.18.31"}"""))
            val health = client(server).health()
            assertEquals("/global/health", server.takeRequest().requestUrl!!.encodedPath)
            assertTrue(health.optBoolean("healthy"))
            assertEquals("1.18.31", health.optString("version"))
        }
    }

    // ------------------------------------------------------- model variants

    @Test
    fun modelVariantsComeFromTheCatalogKeys() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {"all":[{"id":"openai","name":"OpenAI","models":{
                      "gpt-5.6-sol":{"id":"gpt-5.6-sol","name":"GPT-5.6 Sol",
                        "limit":{"context":200000,"output":8192},
                        "variants":{"none":{},"low":{},"high":{}}},
                      "plain":{"id":"plain","name":"Plain"}}}],
                     "connected":["openai"],"default":{"openai":"gpt-5.6-sol"}}
                    """.trimIndent(),
                ),
            )
            val providers = client(server).providers()
            assertEquals("/provider", server.takeRequest().requestUrl!!.encodedPath)

            val variants = providers.model("openai", "gpt-5.6-sol")!!.variants
            assertEquals(setOf("none", "low", "high"), variants.toSet())
            assertTrue("a model without `variants` advertises none", providers.model("openai", "plain")!!.variants.isEmpty())
        }
    }

    // ------------------------------------------------- `prompt_async` body

    @Test
    fun promptAsyncCarriesAgentVariantAndModel() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            client(server).promptAsync(
                sessionID = "ses_1",
                text = "hello",
                model = "openai" to "gpt-5.6-sol",
                agent = "build",
                variant = "high",
            )
            val recorded = server.takeRequest()
            assertEquals("/session/ses_1/prompt_async", recorded.requestUrl!!.encodedPath)
            val body = JSONObject(recorded.body.readUtf8())
            assertEquals("hello", body.getJSONArray("parts").getJSONObject(0).getString("text"))
            assertEquals("openai", body.getJSONObject("model").getString("providerID"))
            assertEquals("gpt-5.6-sol", body.getJSONObject("model").getString("modelID"))
            assertEquals("build", body.getString("agent"))
            assertEquals("high", body.getString("variant"))
        }
    }

    @Test
    fun promptAsyncOmitsUnpinnedSelections() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            client(server).promptAsync("ses_1", "hello")
            val body = JSONObject(server.takeRequest().body.readUtf8())
            assertFalse(body.has("model"))
            assertFalse(body.has("agent"))
            assertFalse(body.has("variant"))
            assertFalse(body.has("system"))
        }
    }

    @Test
    fun blankSelectionsAreNeverPutOnTheWire() {
        val payload = OpenCodeClient.promptPayload("hi", agent = "  ", variant = "")
        assertFalse(payload.has("agent"))
        assertFalse(payload.has("variant"))
        // A non-blank one is kept verbatim.
        assertEquals("build", OpenCodeClient.promptPayload("hi", agent = "build").getString("agent"))
    }

    // ---------------------------------------------------- `POST /shell`

    @Test
    fun shellCommandUsesTheDedicatedShellRoute() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"info":{},"parts":[]}"""))
            client(server).shellCommand(
                sessionID = "ses_2",
                command = "echo hi",
                agent = "build",
                model = "openai" to "gpt-5.6-sol",
            )
            val recorded = server.takeRequest()
            assertEquals("/session/ses_2/shell", recorded.requestUrl!!.encodedPath)
            assertEquals("POST", recorded.method)
            val body = JSONObject(recorded.body.readUtf8())
            assertEquals("echo hi", body.getString("command"))
            assertEquals("build", body.getString("agent"))
            assertEquals("openai", body.getJSONObject("model").getString("providerID"))
        }
    }

    @Test
    fun shellPayloadAlwaysCarriesTheRequiredCommandAndAgent() {
        val body = OpenCodeClient.shellPayload("git status", "build")
        assertEquals("git status", body.getString("command"))
        assertEquals("build", body.getString("agent"))
        assertFalse(body.has("model"))
    }

    // -------------------------------------------------- `POST /command`

    @Test
    fun runCommandPostsNameArgumentsAndTheFlatModelRef() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"info":{},"parts":[]}"""))
            client(server).runCommand(
                sessionID = "ses_3",
                command = "init",
                arguments = "focus on tests",
                agent = "build",
                model = "openai" to "gpt-5.6-sol",
                variant = "high",
            )
            val recorded = server.takeRequest()
            assertEquals("/session/ses_3/command", recorded.requestUrl!!.encodedPath)
            val body = JSONObject(recorded.body.readUtf8())
            assertEquals("init", body.getString("command"))
            assertEquals("focus on tests", body.getString("arguments"))
            assertEquals("build", body.getString("agent"))
            // This route takes `model` as a STRING, unlike prompt_async's object.
            assertEquals("openai/gpt-5.6-sol", body.getString("model"))
            assertEquals("high", body.getString("variant"))
        }
    }

    @Test
    fun commandPayloadKeepsTheRequiredPairEvenWhenEverythingElseIsUnset() {
        val body = OpenCodeClient.commandPayload("init", "")
        assertEquals("init", body.getString("command"))
        assertEquals("", body.getString("arguments"))
        assertFalse(body.has("agent"))
        assertFalse(body.has("model"))
        assertFalse(body.has("variant"))
        // A half-filled model ref is dropped instead of sending "provider/".
        val half = OpenCodeClient.commandPayload("init", "", model = "openai" to "")
        assertFalse(half.has("model"))
        assertNull(OpenCodeClient.formatModelRef("openai" to ""))
    }
}
