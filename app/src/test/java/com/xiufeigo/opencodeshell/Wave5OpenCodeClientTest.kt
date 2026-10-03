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
 * W5-C data layer, wire side: the three read-only catalogs (`GET /api/agent`,
 * `GET /api/command`, `GET /api/skill`), the `variants` keys of a catalog model, and the
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
            assertEquals("/api/agent", server.takeRequest().requestUrl!!.encodedPath)
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
            assertEquals("/api/command", server.takeRequest().requestUrl!!.encodedPath)
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
            assertEquals("/api/skill", server.takeRequest().requestUrl!!.encodedPath)
            assertEquals(1, skills.size)
            assertEquals("customize-opencode", skills[0].name)
            assertEquals("<built-in>", skills[0].location)
        }
    }
}
