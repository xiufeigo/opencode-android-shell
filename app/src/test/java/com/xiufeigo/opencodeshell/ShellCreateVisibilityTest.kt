package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.terminal.mergeShellRows
import com.xiufeigo.opencodeshell.ui.terminal.unresolvedShellPins
import com.xiufeigo.opencodeshell.ui.terminal.share.applyShellResolutions
import com.xiufeigo.opencodeshell.v2.ShellCommandInfo
import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.shellCreate
import com.xiufeigo.opencodeshell.v2.shellGet
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P1: a shell created from the panel's inline form never showed up — the device
 * pass saw the form close, the list stay empty and no error. Three code-level
 * causes are pinned here:
 *
 * 1. the poll snapshots `pinned` before its IO round trip and wrote the snapshot
 *    back afterwards, so a create landing in between was overwritten by the
 *    older map ([applyShellResolutions]);
 * 2. the pin map was re-keyed on the `V2Api` **instance**, which is rebuilt when
 *    the session's directory arrives — every pin was dropped again;
 * 3. the create's request ran on the composition scope, so a panel that left the
 *    composition mid-request cancelled it: nothing was sent, nothing ran.
 */
class ShellCreateVisibilityTest {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }?.readText() ?: error("missing $relative")

    private fun panel() = source("src/main/java/com/xiufeigo/opencodeshell/ui/terminal/BackgroundShellPanel.kt")

    private fun shell(
        id: String,
        status: String = ShellCommandInfo.STATUS_RUNNING,
        started: Long = 1_000L,
    ) = ShellCommandInfo(
        id = id,
        status = status,
        command = "echo bg",
        cwd = "/w",
        shell = "/bin/sh",
        file = "/tmp/out",
        pid = 7,
        exit = if (status == ShellCommandInfo.STATUS_EXITED) 0 else null,
        metadata = JSONObject(),
        timeStarted = started,
        timeCompleted = null,
        raw = JSONObject(),
    )

    // ----------------------------------------------------------- state machine

    @Test
    fun `a pin created while the fetch was in flight survives the poll`() {
        val fresh = shell("sh_new", started = 5_000L)
        // The poll started with nothing pinned, the create landed meanwhile and
        // the server list (running-only) does not carry the exited command.
        val next = applyShellResolutions(
            snapshot = emptyMap(),
            resolutions = emptyMap(),
            addedSince = mapOf(fresh.id to fresh),
        )
        assertEquals(listOf("sh_new"), next.keys.toList())
    }

    @Test
    fun `a 404 resolution still drops the pin it resolved`() {
        val ghost = shell("sh_ghost")
        val next = applyShellResolutions(
            snapshot = mapOf(ghost.id to ghost),
            resolutions = mapOf(ghost.id to null),
            addedSince = emptyMap(),
        )
        assertTrue(next.isEmpty())
    }

    @Test
    fun `a resolution replaces the running pin with its terminal state`() {
        val running = shell("sh_1")
        val exited = shell("sh_1", ShellCommandInfo.STATUS_EXITED)
        val next = applyShellResolutions(
            snapshot = mapOf(running.id to running),
            resolutions = mapOf(running.id to exited),
            addedSince = emptyMap(),
        )
        assertEquals(ShellCommandInfo.STATUS_EXITED, next.getValue("sh_1").status)
    }

    // ------------------------------------------------- wire flow (MockWebServer)

    @Test
    fun `create then poll keeps the row the server never lists`() {
        MockWebServer().use { server ->
            // 1. `POST /api/shell` — the create the form performs.
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"location":{"directory":"/w"},"data":{"id":"sh_1","status":"running",""" +
                        """"command":"echo bg","cwd":"/w","shell":"bash","file":"/tmp/sh_1.out",""" +
                        """"pid":42,"metadata":{},"time":{"started":1789969243559}}}""",
                ),
            )
            // 2. `GET /api/shell` — running commands only, `echo bg` already out.
            server.enqueue(
                MockResponse().setResponseCode(200).setBody("""{"location":{"directory":"/w"},"data":[]}"""),
            )
            // 3. `GET /api/shell/sh_1` — the pin's terminal state, exit code included.
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"location":{"directory":"/w"},"data":{"id":"sh_1","status":"exited",""" +
                        """"command":"echo bg","cwd":"/w","shell":"bash","file":"/tmp/sh_1.out",""" +
                        """"pid":42,"exit":0,"metadata":{},""" +
                        """"time":{"started":1789969243559,"completed":1789969243600}}}""",
                ),
            )
            val api = api(server, directory = "/w")

            val created = api.shellCreate(command = "echo bg", locationDirectory = "/w")
            assertEquals("sh_1", created.id)
            assertEquals("running", created.status)

            // The panel pins the create response…
            val pinned = mapOf(created.id to created)
            // …then the poll it triggers runs.
            val listed = api.shells("/w").map(ShellCommandInfo::fromJson)
            val resolutions = HashMap<String, ShellCommandInfo?>()
            for (pin in unresolvedShellPins(pinned, listed.map { it.id }.toSet())) {
                resolutions[pin.id] = api.shellGet(pin.id, "/w")
            }
            val next = applyShellResolutions(pinned, resolutions, addedSince = emptyMap())
            val rows = mergeShellRows(listed, next.values)

            assertEquals(listOf("sh_1"), rows.map { it.id })
            assertEquals(0, rows.single().exit)

            // Wire shapes: the create is a POST with the location scope the list
            // polls (one scope by construction), the probe is a GET by id.
            val create = server.takeRequest()
            assertEquals("POST", create.method)
            assertEquals("/api/shell", create.requestUrl!!.encodedPath)
            assertEquals("/w", create.requestUrl!!.queryParameter("location[directory]"))
            assertEquals("""{"command":"echo bg"}""", create.body.readUtf8())

            val list = server.takeRequest()
            assertEquals("GET", list.method)
            assertEquals("/api/shell", list.requestUrl!!.encodedPath)

            val probe = server.takeRequest()
            assertEquals("GET", probe.method)
            assertEquals("/api/shell/sh_1", probe.requestUrl!!.encodedPath)
            assertEquals("/w", probe.requestUrl!!.queryParameter("location[directory]"))
        }
    }

    // ------------------------------------------------------------ source pins

    @Test
    fun `the pin map is keyed on the scope, not on the client instance`() {
        val src = panel()
        assertTrue(
            src.contains("remember(locationDirectory) { mutableStateOf(emptyMap<String, ShellCommandInfo>()) }"),
        )
        assertTrue(
            "pins added during the fetch survive",
            src.contains("applyShellResolutions(snapshot, fetched.second, addedSince)"),
        )
    }

    @Test
    fun `run is committed so the create request is not cancellable`() {
        val src = panel()
        // The request leaves inside the tap: `UNDISPATCHED` starts the body
        // before the handler returns and `NonCancellable` keeps the call alive
        // even if the panel leaves the composition a frame later.
        assertTrue(src.contains("scope.launch(start = CoroutineStart.UNDISPATCHED)"))
        assertTrue(src.contains("withContext(NonCancellable + Dispatchers.IO)"))
        assertTrue(src.contains("api.shellCreate("))
        // The form cannot be dismissed mid-request either: that is what closed
        // it silently while the request was still out.
        assertTrue(
            src.contains(
                "modifier = Modifier.clickable(enabled = !busy, role = Role.Button, onClick = onCancel)",
            ),
        )
    }

    private fun api(server: MockWebServer, directory: String? = null) = V2Api(
        baseHttpUrl = server.url("/").toString().toHttpUrlOrNull()!!,
        username = null,
        password = null,
        http = V2Api.defaultClient(),
        directory = directory,
    )
}
