package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.v2.IntegrationCommandStatus
import com.xiufeigo.opencodeshell.v2.IntegrationDetail
import com.xiufeigo.opencodeshell.v2.IntegrationOauthAttemptStatus
import com.xiufeigo.opencodeshell.v2.McpResource
import com.xiufeigo.opencodeshell.v2.McpResourceTemplate
import com.xiufeigo.opencodeshell.v2.PersistentPtyInfo
import com.xiufeigo.opencodeshell.v2.SessionTerminalInfo
import com.xiufeigo.opencodeshell.v2.SessionTerminalRead
import com.xiufeigo.opencodeshell.v2.V1MigrationStatus
import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.V2PluginInfo
import com.xiufeigo.opencodeshell.v2.WebSearchResult
import com.xiufeigo.opencodeshell.v2.debugLocations
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `org.json` double-runtime trap, swept through the `V2Api*` extension files
 * — the continuation of the `V2Api.kt`/`ChatStore.kt`/`ToolText.kt` sweep.
 *
 * `JSONObject.optString(name, fallback)` diverges between the two `org.json`
 * builds this app runs on: Android funnels the value through `JSON.toString`, so
 * a JSON `null` comes back as the **literal string `"null"`** — only a *missing*
 * key reaches the fallback — while the `org.json:json` jar the unit tests run on
 * returns the fallback for the same payload. The old
 * `optString(k, "").takeIf { it.isNotEmpty() }` idiom therefore passed every JVM
 * test and still put `"null"` on a device (the `?cursor=null` drain, the card
 * header reading `null`).
 *
 * Every optional string read in those files now goes through
 * [com.xiufeigo.opencodeshell.v2.optStringOrNull], whose two halves both builds
 * agree on: [JSONObject.isNull] for the wire's JSON `null`, plus a literal
 * `"null"`/blank rejection. This test pins all three absent shapes — JSON `null`,
 * the literal string, the missing key — on every reader family that was swept,
 * and pins the array reads so `[null, "x", "null"]` can never contribute a list
 * element named `null`.
 *
 * The last test documents the sweep's deliberate boundary: the tagged unions'
 * **schema-required** discriminators (`type`, `status`) keep the plain reader,
 * because a JSON `null` there is a server contract violation rather than an
 * expected shape.
 */
class V2ApiJsonNullStringTrapTest {

    /**
     * One absent shape of `key`, or `null` for the missing-key case: `shape` `0`
     * is the wire's JSON `null`, `1` a server that serialized the token as a
     * string, `2` the field simply not present.
     */
    private fun field(key: String, shape: Int): Pair<String, Any?>? = when (shape) {
        0 -> key to JSONObject.NULL
        1 -> key to "null"
        else -> null
    }

    /** Builds an object from key/value pairs, dropping the absent ([field] → `null`) ones. */
    private fun obj(vararg fields: Pair<String, Any?>?): JSONObject = JSONObject().also { json ->
        fields.filterNotNull().forEach { (key, value) -> json.put(key, value) }
    }

    @Test
    fun theIntegrationDetailReadersDropNullStrings() {
        for (shape in 0..2) {
            val method = IntegrationDetail.Method.fromJson(
                obj(
                    "type" to "command",
                    field("id", shape),
                    field("label", shape),
                    "command" to JSONArray(listOf("login")),
                ),
            )
            assertNull("Method.id shape $shape", method.id)
            assertNull("Method.label shape $shape", method.label)

            val connection = IntegrationDetail.Connection.fromJson(
                obj("type" to "credential", field("id", shape), field("label", shape), field("name", shape)),
            )
            assertNull("Connection.id shape $shape", connection.id)
            assertNull("Connection.label shape $shape", connection.label)
            assertNull("Connection.name shape $shape", connection.name)
        }

        // A real value is still carried verbatim, fields and all.
        val method = IntegrationDetail.Method.fromJson(
            obj(
                "type" to "command",
                "id" to "device",
                "label" to "Device code",
                "command" to JSONArray(listOf("login")),
            ),
        )
        assertEquals("device", method.id)
        assertEquals("Device code", method.label)
        assertEquals(listOf("login"), method.command)

        val connection = IntegrationDetail.Connection.fromJson(
            obj("type" to "credential", "id" to "cred_1", "label" to "API key", "name" to "ANTHROPIC_API_KEY"),
        )
        assertEquals("cred_1", connection.id)
        assertEquals("API key", connection.label)
        assertEquals("ANTHROPIC_API_KEY", connection.name)
    }

    @Test
    fun theIntegrationStringListsDropNullElements() {
        // `command`/`names` go through `v2StringList`: a JSON `null` element would
        // stringify to the literal "null" and ride into the method's argparse list.
        val command = IntegrationDetail.Method.fromJson(
            obj("type" to "command", "command" to JSONArray(listOf(JSONObject.NULL, "login", "null", "", "--device"))),
        )
        assertEquals(listOf("login", "--device"), command.command)

        val env = IntegrationDetail.Method.fromJson(
            obj("type" to "env", "names" to JSONArray(listOf(JSONObject.NULL, "TOKEN"))),
        )
        assertEquals(listOf("TOKEN"), env.names)
    }

    @Test
    fun theIntegrationStatusReadersDropNullStrings() {
        for (shape in 0..2) {
            assertNull(
                "CommandStatus.message shape $shape",
                IntegrationCommandStatus.fromJson(obj("status" to "pending", field("message", shape))).message,
            )
            assertNull(
                "OauthAttemptStatus.message shape $shape",
                IntegrationOauthAttemptStatus.fromJson(obj("status" to "pending", field("message", shape))).message,
            )
        }

        // The "failed" variants carry the output the settings sheet shows.
        assertEquals(
            "Permission denied",
            IntegrationCommandStatus.fromJson(obj("status" to "failed", "message" to "Permission denied")).message,
        )
        assertEquals(
            "Code expired",
            IntegrationOauthAttemptStatus.fromJson(obj("status" to "failed", "message" to "Code expired")).message,
        )
    }

    @Test
    fun thePluginReaderDropsNullStrings() {
        for (shape in 0..2) {
            val plugin = V2PluginInfo.fromJson(
                obj(
                    field("id", shape),
                    "source" to obj("type" to "package", field("target", shape), field("version", shape)),
                    "state" to obj("status" to "failed", field("error", shape), field("ref", shape)),
                ),
            )
            assertNull("plugin id shape $shape", plugin.id)
            assertNull("packageTarget shape $shape", plugin.packageTarget)
            assertNull("packageVersion shape $shape", plugin.packageVersion)
            assertNull("stateError shape $shape", plugin.stateError)
            assertNull("stateRef shape $shape", plugin.stateRef)
        }

        // `path` exists only on the "local" source variant.
        for (shape in 0..2) {
            val local = V2PluginInfo.fromJson(obj("source" to obj("type" to "local", field("path", shape))))
            assertNull("localPath shape $shape", local.localPath)
        }

        val plugin = V2PluginInfo.fromJson(
            obj(
                "id" to "p1",
                "source" to obj("type" to "package", "target" to "@opencode/plugin", "version" to "1.2.3"),
                "state" to obj("status" to "failed", "error" to "boom", "ref" to "abc"),
            ),
        )
        assertEquals("p1", plugin.id)
        assertEquals("@opencode/plugin", plugin.packageTarget)
        assertEquals("1.2.3", plugin.packageVersion)
        assertEquals("boom", plugin.stateError)
        assertEquals("abc", plugin.stateRef)
    }

    @Test
    fun theMcpReadersDropNullStrings() {
        for (shape in 0..2) {
            val resource = McpResource.fromJson(
                obj(
                    "server" to "context7",
                    "name" to "docs",
                    "uri" to "file:///docs",
                    field("description", shape),
                    field("mimeType", shape),
                ),
            )
            assertNull("Resource.description shape $shape", resource.description)
            assertNull("Resource.mimeType shape $shape", resource.mimeType)

            val template = McpResourceTemplate.fromJson(
                obj(
                    "server" to "context7",
                    "name" to "docs",
                    "uriTemplate" to "file:///{path}",
                    field("description", shape),
                    field("mimeType", shape),
                ),
            )
            assertNull("Template.description shape $shape", template.description)
            assertNull("Template.mimeType shape $shape", template.mimeType)
        }

        val resource = McpResource.fromJson(
            obj(
                "server" to "context7",
                "name" to "docs",
                "uri" to "file:///docs",
                "description" to "Docs",
                "mimeType" to "text/markdown",
            ),
        )
        assertEquals("Docs", resource.description)
        assertEquals("text/markdown", resource.mimeType)
    }

    @Test
    fun theTerminalReadersDropNullStrings() {
        // `foregroundProcess` is `NullOr(String)` upstream: JSON null is the shape
        // "no foreground process", and "null" would render as a process named null.
        for (shape in 0..2) {
            assertNull(
                "SessionTerminalInfo.foregroundProcess shape $shape",
                SessionTerminalInfo.fromJson(terminalInfo(field("foregroundProcess", shape))).foregroundProcess,
            )
            assertNull(
                "SessionTerminalRead.foregroundProcess shape $shape",
                SessionTerminalRead.fromJson(
                    obj(
                        "ptyID" to "pty_1",
                        "title" to "shell",
                        "cwd" to "/w",
                        field("foregroundProcess", shape),
                        "screen" to obj("text" to "$"),
                    ),
                ).foregroundProcess,
            )
            assertNull(
                "PersistentPtyInfo.foregroundProcess shape $shape",
                PersistentPtyInfo.fromJson(terminalInfo(field("foregroundProcess", shape))).foregroundProcess,
            )
        }

        val info = SessionTerminalInfo.fromJson(terminalInfo("foregroundProcess" to "node"))
        assertEquals("node", info.foregroundProcess)
        assertNull(PersistentPtyInfo.fromJson(terminalInfo()).foregroundProcess)
        // …and the null-element `args` array loses only its non-strings.
        assertEquals(listOf("-l"), info.args)
    }

    /** A `Pty.Info`-shaped payload plus whichever `foregroundProcess` shape the case needs. */
    private fun terminalInfo(foreground: Pair<String, Any?>? = null): JSONObject = obj(
        "id" to "pty_1",
        "title" to "shell",
        "command" to "bash",
        "args" to JSONArray(listOf(JSONObject.NULL, "-l", "null")),
        "cwd" to "/w",
        "status" to "running",
        "pid" to 1,
        "sessionID" to "ses_1",
        foreground,
        "size" to obj("cols" to 80, "rows" to 24),
    )

    @Test
    fun theWebSearchReaderDropsNullStrings() {
        for (shape in 0..2) {
            val result = WebSearchResult.fromJson(
                obj("url" to "https://opencode.ai", field("title", shape), field("content", shape)),
            )
            assertNull("title shape $shape", result.title)
            assertNull("content shape $shape", result.content)
        }

        val result = WebSearchResult.fromJson(
            obj("url" to "https://opencode.ai", "title" to "opencode", "content" to "docs"),
        )
        assertEquals("opencode", result.title)
        assertEquals("docs", result.content)
        assertEquals("https://opencode.ai", result.url)
    }

    @Test
    fun theMigrationReaderDropsNullStrings() {
        for (shape in 0..2) {
            val status = V1MigrationStatus.fromJson(obj("status" to "error", field("error", shape)))
            assertNull("error shape $shape", status.error)
            assertTrue("status shape $shape still routes", status.failed)
        }

        assertEquals(
            "disk full",
            V1MigrationStatus.fromJson(obj("status" to "error", "error" to "disk full")).error,
        )
        // A `running` variant's progress label is a required string and stays one.
        assertEquals(
            "Scanning sessions",
            V1MigrationStatus.fromJson(
                obj("status" to "running", "progress" to obj("label" to "Scanning sessions")),
            ).progress!!.label,
        )
    }

    @Test
    fun theDebugLocationArrayDropsNullDirectories() {
        // `debugLocations()` maps an object array to strings; without the guard a
        // `{"directory":null}` row lists a location literally named "null".
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """[{"directory":"/a"},{"directory":null},{"directory":"null"},{},{"directory":""},null,"/b"]""",
                ),
            )
            val api = V2Api(
                baseHttpUrl = server.url("/").toString().toHttpUrlOrNull()!!,
                username = null,
                password = null,
                http = V2Api.defaultClient(),
                directory = null,
            )
            assertEquals(listOf("/a"), api.debugLocations())
        }
    }

    @Test
    fun requiredDiscriminatorsDeliberatelyKeepTheRawReader() {
        // The boundary of the sweep: `type`/`status` are the tagged unions'
        // schema-required discriminators, so a JSON null there is a server contract
        // violation rather than an expected shape. They keep `optString` — "" on
        // this runtime, the literal "null" on Android — and no guarded reader is
        // asked to invent a variant from it.
        val nullType = IntegrationDetail.Method.fromJson(obj("type" to JSONObject.NULL)).type
        assertTrue(nullType.isEmpty() || nullType == "null")
        val nullStatus = IntegrationCommandStatus.fromJson(obj("status" to JSONObject.NULL)).status
        assertTrue(nullStatus.isEmpty() || nullStatus == "null")

        // A real discriminator still routes the variant.
        assertEquals("command", IntegrationDetail.Method.fromJson(obj("type" to "command")).type)
        assertTrue(IntegrationCommandStatus.fromJson(obj("status" to "pending")).pending)
    }
}
