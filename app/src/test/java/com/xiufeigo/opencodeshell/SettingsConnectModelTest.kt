package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.settings.CommandPollOutcome
import com.xiufeigo.opencodeshell.ui.settings.ConnectFlow
import com.xiufeigo.opencodeshell.ui.settings.IntegrationFormField
import com.xiufeigo.opencodeshell.ui.settings.IntegrationMethodRow
import com.xiufeigo.opencodeshell.ui.settings.McpAddSpec
import com.xiufeigo.opencodeshell.ui.settings.OauthPollOutcome
import com.xiufeigo.opencodeshell.ui.settings.commandAttemptRow
import com.xiufeigo.opencodeshell.ui.settings.commandPollOutcome
import com.xiufeigo.opencodeshell.ui.settings.commandStatusRow
import com.xiufeigo.opencodeshell.ui.settings.connectFlowOf
import com.xiufeigo.opencodeshell.ui.settings.connectableMethods
import com.xiufeigo.opencodeshell.ui.settings.credentialRows
import com.xiufeigo.opencodeshell.ui.settings.formAnswer
import com.xiufeigo.opencodeshell.ui.settings.formHasUnsupportedFields
import com.xiufeigo.opencodeshell.ui.settings.integrationDetailRow
import com.xiufeigo.opencodeshell.ui.settings.mcpAddSpec
import com.xiufeigo.opencodeshell.ui.settings.mcpResourceListRow
import com.xiufeigo.opencodeshell.ui.settings.methodRouteID
import com.xiufeigo.opencodeshell.ui.settings.migrationStatusRow
import com.xiufeigo.opencodeshell.ui.settings.oauthAttemptRow
import com.xiufeigo.opencodeshell.ui.settings.oauthPollOutcome
import com.xiufeigo.opencodeshell.ui.settings.oauthStatusRow
import com.xiufeigo.opencodeshell.ui.settings.pluginCheckRows
import com.xiufeigo.opencodeshell.ui.settings.pluginUpdateTargets
import com.xiufeigo.opencodeshell.ui.settings.splitCommandTokens
import com.xiufeigo.opencodeshell.ui.settings.titleGeneratePrompt
import com.xiufeigo.opencodeshell.ui.settings.validHttpUrl
import com.xiufeigo.opencodeshell.ui.settings.validateCredentialLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure rules behind the settings write surface: credential projection
 * (including the active-first inference), the three connect-flow state
 * machines, the MCP add form and the plugin update-target filter.
 */
class SettingsConnectModelTest {

    // ------------------------------------------------------------ credentials

    private val integrationJson = """
        {
          "id": "anthropic",
          "name": "Anthropic",
          "methods": [{"type": "key", "label": "API key"}],
          "connections": [
            {"type": "credential", "id": "cred_new", "label": "work"},
            {"type": "credential", "id": "cred_old", "label": "personal"},
            {"type": "env", "name": "ANTHROPIC_API_KEY"}
          ]
        }
    """.trimIndent()

    @Test
    fun `first credential connection is the active one`() {
        val rows = credentialRows("[$integrationJson]")
        assertEquals(listOf("cred_new", "cred_old"), rows.map { it.id })
        assertTrue(rows[0].active)
        assertFalse(rows[1].active)
        assertEquals("Anthropic", rows[0].integrationName)
        assertEquals("anthropic", rows[0].integrationID)
    }

    @Test
    fun `env connections never appear in the credential list`() {
        val rows = credentialRows("[$integrationJson]")
        assertTrue(rows.none { it.id == "ANTHROPIC_API_KEY" })
        assertEquals(2, rows.size)
    }

    @Test
    fun `an integration without credentials contributes nothing`() {
        val empty = """{"id":"x","name":"X","methods":[],"connections":[{"type":"env","name":"E"}]}"""
        assertTrue(credentialRows("[$empty]").isEmpty())
    }

    @Test
    fun `detail parsing keeps method ids forms and credentials`() {
        val detail = integrationDetailRow(integrationJson)!!
        assertEquals("anthropic", detail.id)
        assertEquals(listOf("key"), detail.methods.map { it.type })
        assertEquals(2, detail.credentials.size)
    }

    @Test
    fun `form fields keep key title type and required`() {
        val json = """
            {"id":"o","name":"O","methods":[{"type":"oauth","id":"device","form":[
              {"key":"team","title":"Team","type":"string","required":true},
              {"key":"n","type":"number"}
            ]}],"connections":[]}
        """.trimIndent()
        val form = integrationDetailRow(json)!!.methods.single().form
        assertEquals(
            listOf(
                IntegrationFormField("team", "Team", "string", required = true),
                IntegrationFormField("n", null, "number", required = false),
            ),
            form,
        )
    }

    // ---------------------------------------------------------- connect flows

    @Test
    fun `connect methods drop env and fall back to a bare key form`() {
        val detail = integrationDetailRow(integrationJson)
        assertEquals(listOf("key"), connectableMethods(detail).map { it.type })

        val envOnly = integrationDetailRow(
            """{"id":"e","name":"E","methods":[{"type":"env","names":["X"]}],"connections":[]}""",
        )
        assertEquals(listOf("key"), connectableMethods(envOnly).map { it.type })

        val rich = integrationDetailRow(
            """{"id":"r","name":"R","methods":[
                 {"type":"key"},
                 {"type":"oauth","id":"device"},
                 {"type":"command","id":"login"},
                 {"type":"env","names":["X"]}
               ],"connections":[]}""",
        )!!
        assertEquals(listOf("key", "oauth", "command"), connectableMethods(rich).map { it.type })
    }

    @Test
    fun `only oauth and command methods carry a route id`() {
        assertNull(methodRouteID(IntegrationMethodRow("key", id = "k", label = null)))
        assertEquals("device", methodRouteID(IntegrationMethodRow("oauth", id = "device", label = null)))
        assertEquals("login", methodRouteID(IntegrationMethodRow("command", id = "login", label = null)))
        assertNull(methodRouteID(IntegrationMethodRow("oauth", id = null, label = null)))
    }

    @Test
    fun `flow tags map to the three interactive routes`() {
        assertEquals(ConnectFlow.KEY, connectFlowOf("key"))
        assertEquals(ConnectFlow.OAUTH, connectFlowOf("oauth"))
        assertEquals(ConnectFlow.COMMAND, connectFlowOf("command"))
        assertEquals(ConnectFlow.ENV, connectFlowOf("env"))
        assertEquals(ConnectFlow.OTHER, connectFlowOf("mystery"))
    }

    @Test
    fun `non-string form fields are unsupported`() {
        assertFalse(formHasUnsupportedFields(emptyList()))
        assertFalse(
            formHasUnsupportedFields(listOf(IntegrationFormField("a", null, "string", required = false))),
        )
        assertTrue(
            formHasUnsupportedFields(listOf(IntegrationFormField("a", null, "number", required = false))),
        )
    }

    @Test
    fun `form answers omit blanks and refuse a missing required field`() {
        val fields = listOf(
            IntegrationFormField("team", null, "string", required = false),
            IntegrationFormField("token", null, "string", required = true),
        )
        assertEquals(mapOf("token" to "t"), formAnswer(fields, mapOf("token" to " t ")))
        assertNull(formAnswer(fields, mapOf("token" to " ")))
        assertNull(formAnswer(emptyList(), mapOf("token" to "t")))
    }

    @Test
    fun `oauth poll outcome follows mode`() {
        assertEquals(OauthPollOutcome.COMPLETE, oauthPollOutcome("complete", "auto"))
        assertEquals(OauthPollOutcome.COMPLETE, oauthPollOutcome("complete", "code"))
        assertEquals(OauthPollOutcome.FAILED, oauthPollOutcome("failed", "auto"))
        assertEquals(OauthPollOutcome.EXPIRED, oauthPollOutcome("expired", "code"))
        assertEquals(OauthPollOutcome.POLL, oauthPollOutcome("pending", "auto"))
        assertEquals(OauthPollOutcome.AWAIT_CODE, oauthPollOutcome("pending", "code"))
        assertEquals(OauthPollOutcome.POLL, oauthPollOutcome("something-else", "auto"))
    }

    @Test
    fun `command poll outcome stops on the terminal statuses`() {
        assertEquals(CommandPollOutcome.COMPLETE, commandPollOutcome("complete"))
        assertEquals(CommandPollOutcome.FAILED, commandPollOutcome("failed"))
        assertEquals(CommandPollOutcome.EXPIRED, commandPollOutcome("expired"))
        assertEquals(CommandPollOutcome.POLL, commandPollOutcome("pending"))
    }

    @Test
    fun `attempt rows keep ids urls modes and live messages`() {
        val attempt = oauthAttemptRow(
            """{"attemptID":"att_1","url":"https://auth","instructions":"open","mode":"code"}""",
        )!!
        assertEquals("att_1", attempt.attemptID)
        assertEquals("https://auth", attempt.url)
        assertEquals("code", attempt.mode)
        assertNull(oauthAttemptRow("""{"url":"x"}"""))

        val status = oauthStatusRow("""{"status":"pending","message":"waiting"}""")!!
        assertEquals("waiting", status.message)
        assertNull(oauthStatusRow("""{"status":"pending"}""")!!.message)

        assertEquals("att_2", commandAttemptRow("""{"attemptID":"att_2","time":{"expires":9}}""")!!.attemptID)
        assertEquals(9L, commandAttemptRow("""{"attemptID":"a","time":{"expires":9}}""")!!.timeExpires)
        assertEquals("failed", commandStatusRow("""{"status":"failed","message":"boom"}""")!!.status)
    }

    @Test
    fun `credential label is trimmed and never blank`() {
        assertEquals("work", validateCredentialLabel("  work  "))
        assertNull(validateCredentialLabel("   "))
        assertNull(validateCredentialLabel(null))
    }

    // -------------------------------------------------------------------- mcp

    @Test
    fun `command tokens split on whitespace only`() {
        assertEquals(listOf("npx", "-y", "pkg"), splitCommandTokens("  npx   -y  pkg "))
        assertTrue(splitCommandTokens("   ").isEmpty())
    }

    @Test
    fun `mcp add spec requires the name and the chosen transport`() {
        assertEquals(
            McpAddSpec.Local("gh", listOf("npx", "-y", "pkg")),
            mcpAddSpec(" gh ", remote = false, command = "npx -y pkg", url = ""),
        )
        assertEquals(
            McpAddSpec.Remote("remote", "https://mcp.example"),
            mcpAddSpec("remote", remote = true, command = "", url = " https://mcp.example "),
        )
        assertNull(mcpAddSpec("", remote = false, command = "npx", url = ""))
        assertNull(mcpAddSpec("n", remote = false, command = "  ", url = ""))
        assertNull(mcpAddSpec("n", remote = true, command = "npx", url = ""))
    }

    @Test
    fun `resource catalog separates resources and templates`() {
        val rows = mcpResourceListRow(
            """{"resources":[{"server":"gh","name":"repos","uri":"repos://all","description":"d"}],
                "templates":[{"server":"gh","name":"repo","uriTemplate":"repo://{id}"}]}""",
        )
        assertEquals("repos", rows.resources.single().name)
        assertEquals("repos://all", rows.resources.single().uri)
        assertEquals("repo://{id}", rows.templates.single().uri)
        assertTrue(mcpResourceListRow(null).resources.isEmpty())
    }

    // ----------------------------------------------------------------- plugin

    @Test
    fun `plugin check rows carry the package target and outdated flag`() {
        val rows = pluginCheckRows(
            """[
              {"id":"a","source":{"type":"package","target":"pkg-a","version":"1.0.0","outdated":true},
               "state":{"status":"loaded"}},
              {"id":"b","source":{"type":"package","target":"pkg-b","version":"2.0.0","outdated":false},
               "state":{"status":"loaded"}},
              {"source":{"type":"local","path":"/tmp/x"},"state":{"status":"failed"}}
            ]""",
        )
        assertEquals(listOf("/tmp/x", "a", "b"), rows.map { it.name })
        val a = rows.first { it.name == "a" }
        val b = rows.first { it.name == "b" }
        assertEquals("pkg-a", a.packageTarget)
        assertTrue(a.outdated)
        assertFalse(b.outdated)
        assertEquals("loaded", a.stateStatus)
    }

    @Test
    fun `update targets are the outdated package targets only`() {
        val rows = pluginCheckRows(
            """[
              {"id":"a","source":{"type":"package","target":"pkg-a","outdated":true}},
              {"id":"b","source":{"type":"package","target":"pkg-b","outdated":false}},
              {"id":"c","source":{"type":"package","target":"pkg-a","outdated":true}},
              {"id":"d","source":{"type":"local","path":"/x"}}
            ]""",
        )
        assertEquals(listOf("pkg-a"), pluginUpdateTargets(rows))
    }

    // ------------------------------------------------------------------- misc

    @Test
    fun `wellknown url must be http(s)`() {
        assertEquals("https://opencode.ai/.well-known/x", validHttpUrl(" https://opencode.ai/.well-known/x "))
        assertNull(validHttpUrl("ftp://x"))
        assertNull(validHttpUrl(""))
        assertNull(validHttpUrl(null))
    }

    @Test
    fun `migration status keeps progress and error`() {
        val running = migrationStatusRow(
            """{"status":"running","progress":{"label":"copy","numerator":1,"denominator":2}}""",
        )!!
        assertEquals("copy", running.label)
        assertEquals(1L, running.numerator)
        assertEquals(2L, running.denominator)

        val failed = migrationStatusRow("""{"status":"error","error":"disk"}""")!!
        assertEquals("disk", failed.error)
        assertNull(migrationStatusRow(null))
    }

    @Test
    fun `title prompt is built without any network`() {
        val prompt = titleGeneratePrompt("  hello world  ")
        assertTrue(prompt.contains("hello world"))
        assertTrue(prompt.contains("title"))
    }
}
