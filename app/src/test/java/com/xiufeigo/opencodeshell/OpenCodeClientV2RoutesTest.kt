package com.xiufeigo.opencodeshell

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V2 wire coverage for the routes the v1 sweep repointed at `/api/…`: every
 * test asserts the request line lives under the `/api/` namespace (the
 * unprefixed v1 paths answer SPA HTML on a v2 server), plus the envelope
 * parsing and the V2 body shapes.
 */
class OpenCodeClientV2RoutesTest {

    private fun client(server: MockWebServer) =
        OpenCodeClient(server.url("/").toString().toHttpUrlOrNull()!!, null, null)

    @Test
    fun configOverlaysTheModelDefault() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """[{"type":"document","path":"/cfg/opencode.json","info":{"theme":"dark"}}]""",
                ),
            )
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"location":{},"data":{"id":"gpt-5","modelID":"gpt-5","providerID":"openai"}}""",
                ),
            )
            val c = client(server)
            val config = c.config()
            assertEquals("dark", config.optString("theme"))
            assertEquals("openai/gpt-5", config.optString("model"))
            assertEquals("/api/config", server.takeRequest().requestUrl!!.encodedPath)
            assertEquals("/api/model/default", server.takeRequest().requestUrl!!.encodedPath)
            c.close()
        }
    }

    @Test
    fun configDegradesWhenNoModelDefaultIsNamed() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """[{"type":"document","path":"/cfg/opencode.json","info":{"theme":"dark"}}]""",
                ),
            )
            server.enqueue(MockResponse().setResponseCode(404).setBody("{}"))
            val c = client(server)
            val config = c.config()
            assertEquals("dark", config.optString("theme"))
            assertFalse(config.has("model"))
            c.close()
        }
    }

    @Test
    fun createSessionPostsTitleAndLocation() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"data":{"id":"ses_1","title":"T","location":{"directory":"/w"},""" +
                        """"time":{"updated":9}}}""",
                ),
            )
            val c = client(server)
            val session = c.createSession("/w", "T")
            assertEquals("ses_1", session.id)
            assertEquals("/w", session.directory)
            val recorded = server.takeRequest()
            assertEquals("POST", recorded.method)
            assertEquals("/api/session", recorded.requestUrl!!.encodedPath)
            val body = JSONObject(recorded.body.readUtf8())
            assertEquals("T", body.getString("title"))
            assertEquals("/w", body.getJSONObject("location").getString("directory"))
            c.close()
        }
    }

    @Test
    fun sessionForkSendsBeforeInsteadOfMessageId() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"data":{"id":"ses_2","title":"Fork","time":{"updated":3}}}""",
                ),
            )
            val c = client(server)
            assertEquals("ses_2", c.sessionFork("ses_1", "msg_1").id)
            val recorded = server.takeRequest()
            assertEquals("POST", recorded.method)
            assertEquals("/api/session/ses_1/fork", recorded.requestUrl!!.encodedPath)
            val body = JSONObject(recorded.body.readUtf8())
            assertEquals("msg_1", body.getString("before"))
            assertFalse(body.has("messageID"))
            c.close()
        }
    }

    @Test
    fun forkPayloadUsesTheV2BeforeKey() {
        assertEquals("msg_1", OpenCodeClient.forkPayload("msg_1").getString("before"))
        assertEquals(0, OpenCodeClient.forkPayload(null).length())
        assertEquals("m", OpenCodeClient.revertPayload("m").getString("messageID"))
    }

    @Test
    fun sessionRevertStagesCommitsThenReReads() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"data":{"id":"ses_1","title":"T","time":{"updated":5}}}""",
                ),
            )
            val c = client(server)
            assertEquals("ses_1", c.sessionRevert("ses_1", "msg_7").id)
            val stage = server.takeRequest()
            assertEquals("POST", stage.method)
            assertEquals("/api/session/ses_1/revert/stage", stage.requestUrl!!.encodedPath)
            assertEquals("msg_7", JSONObject(stage.body.readUtf8()).getString("messageID"))
            val commit = server.takeRequest()
            assertEquals("POST", commit.method)
            assertEquals("/api/session/ses_1/revert/commit", commit.requestUrl!!.encodedPath)
            val reread = server.takeRequest()
            assertEquals("GET", reread.method)
            assertEquals("/api/session/ses_1", reread.requestUrl!!.encodedPath)
            c.close()
        }
    }

    @Test
    fun sessionUnrevertDeletesTheStagedRevert() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"data":{"id":"ses_1","title":"T","time":{"updated":6}}}""",
                ),
            )
            val c = client(server)
            assertEquals("ses_1", c.sessionUnrevert("ses_1").id)
            val delete = server.takeRequest()
            assertEquals("DELETE", delete.method)
            assertEquals("/api/session/ses_1/revert", delete.requestUrl!!.encodedPath)
            assertEquals("/api/session/ses_1", server.takeRequest().requestUrl!!.encodedPath)
            c.close()
        }
    }

    @Test
    fun agentsCommandsAndSkillsReadLocationScopedEnvelopes() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"location":{"directory":"/w"},"data":[
                        {"id":"build","name":"Build","description":"d","mode":"primary"}]}""",
                ),
            )
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"location":{"directory":"/w"},"data":[
                        {"name":"init","description":"setup"}]}""",
                ),
            )
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"location":{"directory":"/w"},"data":[
                        {"id":"s1","name":"review","description":"r","path":"/skills/review"}]}""",
                ),
            )
            val c = client(server)
            val agents = c.agents("/w")
            assertEquals(listOf("Build"), agents.map { it.name })
            assertTrue(agents[0].primary)
            val commands = c.commands("/w")
            assertEquals(listOf("init"), commands.map { it.name })
            val skills = c.skills("/w")
            assertEquals("/skills/review", skills.single().location)
            repeat(3) {
                val recorded = server.takeRequest()
                assertTrue(recorded.requestUrl!!.encodedPath.startsWith("/api/"))
                assertEquals("/w", recorded.requestUrl!!.queryParameter("location[directory]"))
            }
            c.close()
        }
    }

    @Test
    fun listPermissionsMapsTheV2RequestShape() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"location":{"directory":"/w"},"data":[
                        {"id":"per_1","sessionID":"ses_1","action":"bash",
                         "resources":["ls"],"save":["ls"],
                         "source":{"type":"tool","messageID":"msg_1","callID":"call_1"}},
                        {"sessionID":"ses_1","action":"edit"}]}""",
                ),
            )
            val c = client(server)
            val list = c.listPermissions("/w")
            assertEquals(1, list.size)
            assertEquals("per_1", list[0].id)
            assertEquals("bash", list[0].permission)
            assertEquals(listOf("ls"), list[0].patterns)
            assertEquals(listOf("ls"), list[0].always)
            assertEquals("msg_1", list[0].tool?.messageID)
            assertEquals("call_1", list[0].tool?.callID)
            val recorded = server.takeRequest()
            assertEquals("/api/permission/request", recorded.requestUrl!!.encodedPath)
            assertEquals("/w", recorded.requestUrl!!.queryParameter("location[directory]"))
            c.close()
        }
    }

    @Test
    fun replyPermissionThrowsInsteadOfPostingToADeadRoute() {
        MockWebServer().use { server ->
            val c = client(server)
            assertThrows(UnsupportedOperationException::class.java) {
                c.replyPermission("per_1", "always", "/w")
            }
            assertEquals(0, server.requestCount)
            c.close()
        }
    }

    @Test
    fun vcsDiffTranslatesGitToWorking() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"location":{},"data":[]}"""))
            val c = client(server)
            assertTrue(c.vcsDiff("git").isEmpty())
            val recorded = server.takeRequest()
            assertEquals("/api/vcs/diff", recorded.requestUrl!!.encodedPath)
            assertEquals("working", recorded.requestUrl!!.queryParameter("mode"))
            c.close()
        }
    }

    @Test
    fun sessionJsonAndMessagesJsonReadEnvelopes() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"data":{"id":"ses_1","title":"T"}}""",
                ),
            )
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"data":[
                        {"id":"msg_2","time":{"created":2},"type":"user","text":"b"},
                        {"id":"msg_1","time":{"created":1},"type":"user","text":"a"}]}""",
                ),
            )
            val c = client(server)
            assertEquals("ses_1", c.sessionJson("ses_1").optString("id"))
            assertEquals("/api/session/ses_1", server.takeRequest().requestUrl!!.encodedPath)
            val rows = c.messagesJson("ses_1")
            assertEquals(listOf("msg_1", "msg_2"), rows.map { it.optString("id") })
            assertEquals("/api/session/ses_1/message", server.takeRequest().requestUrl!!.encodedPath)
            c.close()
        }
    }

    @Test
    fun v2AgentAllModeIsSelectable() {
        val agent = OpenCodeClient.Agent.fromJson(
            JSONObject("""{"id":"oracle","name":"Oracle","mode":"all"}"""),
        )
        assertTrue(agent.primary)
        assertEquals(listOf("Oracle"), OpenCodeClient.Agent.selectable(listOf(agent)).map { it.name })
        // Nameless-but-identified entries fall back to their id.
        val unnamed = OpenCodeClient.Agent.fromJson(JSONObject("""{"id":"x","mode":"primary"}"""))
        assertEquals("x", unnamed.name)
        assertNull(OpenCodeClient.VcsInfo.fromJson(JSONObject("{}")).branch)
    }
}
