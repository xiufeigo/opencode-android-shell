package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.terminal.CreatePtyResult
import com.xiufeigo.opencodeshell.ui.terminal.createPtyAdopted
import com.xiufeigo.opencodeshell.ui.terminal.share.PtyApi
import com.xiufeigo.opencodeshell.ui.terminal.share.TerminalCwd
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * v50 「移动后终端 cwd 不更新」 — after a session Move the *new* terminals must
 * be created in the session's **current** directory, and the already-open ones
 * must say so rather than silently keeping the old `pwd`
 * (`research/v50_verify/tD/v50_39_terminal_pwd.png`, `v50_38`; the Files page
 * was already right at `v50_43`).
 */
class TerminalMoveCwdTest {

    private fun api(server: MockWebServer, directory: String?, v2: Boolean = false) = PtyApi(
        baseHttpUrl = server.url("/").toString().toHttpUrlOrNull()!!,
        username = "opencode",
        password = "secret",
        directory = directory,
        http = PtyApi.defaultHttp(),
        v2 = v2,
    )

    /** v1 face: `POST /pty` answers a bare `Pty.Info`, `DELETE` a plain 200. */
    private fun MockWebServer.ptyRoutes() {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.method == "POST" && request.path?.substringBefore('?') == "/pty" ->
                    MockResponse().setBody("""{"id":"pty_9"}""")
                request.method == "DELETE" -> MockResponse().setBody("true")
                else -> MockResponse().setResponseCode(404).setBody("{}")
            }
        }
    }

    private fun MockWebServer.take(method: String): RecordedRequest {
        val request = takeRequest(2, TimeUnit.SECONDS)
        assertEquals(method, request?.method)
        return request!!
    }

    /**
     * The create must send the directory it was given as the PTY's `cwd` — the
     * body field is what makes `pwd` land there, the query only scopes the
     * route.
     */
    @Test
    fun `v1 create puts the session directory in the body cwd`() = runBlocking {
        MockWebServer().use { server ->
            server.ptyRoutes()
            val result = createPtyAdopted(api(server, "/srv/after-move"), directory = "/srv/after-move") { it }
            assertEquals("pty_9", (result as CreatePtyResult.Adopted).value)
            val body = JSONObject(server.take("POST").body.readUtf8())
            assertEquals("/srv/after-move", body.optString("cwd"))
        }
    }

    /** Same on the v2 face the panel actually talks to (`{location, data}`). */
    @Test
    fun `v2 create puts the session directory in the body cwd`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse().setBody("""{"location":"/pty/pty_9","data":{"id":"pty_9"}}""")
            }
            val result = createPtyAdopted(api(server, "/srv/after-move", v2 = true), directory = "/srv/after-move") { it }
            assertEquals("pty_9", (result as CreatePtyResult.Adopted).value)
            val post = server.take("POST")
            assertEquals("/srv/after-move", JSONObject(post.body.readUtf8()).optString("cwd"))
            // The route stays located at the same directory.
            assertTrue(post.path!!.contains("location%5Bdirectory%5D=%2Fsrv%2Fafter-move"))
        }
    }

    /** A blank directory stays off the wire rather than becoming `cwd: ""`. */
    @Test
    fun `no directory means no cwd field`() = runBlocking {
        MockWebServer().use { server ->
            server.ptyRoutes()
            createPtyAdopted(api(server, null), directory = null) { it }
            assertFalse(JSONObject(server.take("POST").body.readUtf8()).has("cwd"))
        }
    }

    // ------------------------------------------------------------ stale hint

    @Test
    fun `a pty created before the move is stale`() {
        assertTrue(TerminalCwd.isStale("/srv/before-move", "/srv/after-move"))
        assertEquals(setOf("pty_1"), TerminalCwd.staleIds(mapOf("pty_1" to "/old", "pty_2" to "/new"), "/new"))
    }

    @Test
    fun `a pty created in the current directory is not stale`() {
        assertFalse(TerminalCwd.isStale("/srv/app", "/srv/app"))
        assertFalse(TerminalCwd.isStale(" /srv/app ", "/srv/app"))
        assertTrue(TerminalCwd.staleIds(mapOf("pty_1" to "/new"), "/new").isEmpty())
    }

    /**
     * A revived tab (unknown creation directory) is never warned about: the
     * server may well have started it in the current directory, and a false
     * 「旧目录」 on every revived terminal is worse than silence.
     */
    @Test
    fun `an unknown creation directory is never stale`() {
        assertFalse(TerminalCwd.isStale(null, "/srv/app"))
        assertFalse(TerminalCwd.isStale("", "/srv/app"))
        assertFalse(TerminalCwd.isStale("/srv/app", null))
        assertTrue(TerminalCwd.staleIds(mapOf("pty_1" to null), "/srv/app").isEmpty())
    }

    // ------------------------------------------------------------ call sites

    private fun source(relative: String): String {
        val file = java.io.File(relative).takeIf { it.isFile } ?: java.io.File("app/$relative")
        return file.readText().replace("\r\n", "\n")
    }

    /**
     * The docked and mobile panel call sites must hand over
     * `reviewRootPath` (`currentDirectory ?: activeSession.directory`) — the row's
     * opening directory is what left the pty `pwd` behind after a Move.
     */
    @Test
    fun `terminal panel call sites follow the current directory`() {
        val screen = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")
        assertTrue(
            "reviewRootPath must be the server-confirmed current directory",
            screen.contains("val reviewRootPath = currentDirectory ?: activeSession.directory"),
        )
        val panelCalls = Regex("directory = reviewRootPath").findAll(screen).count()
        assertTrue(
            "both docked panes and the mobile tab must use it (saw $panelCalls)",
            panelCalls >= 3,
        )
        val lines = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")
            .split("\n")
        // Every remaining `directory = activeSession.directory` must belong to an
        // `observeSession(...)` entry call (2 sites), never to a panel call:
        // the terminal panels are the ones that must follow the move.
        val rowReaders = lines.mapIndexedNotNull { index, line ->
            index to line.trim()
        }.filter { (_, line) -> line == "directory = activeSession.directory," }
        assertEquals("only the two observeSession entry calls may read it", 2, rowReaders.size)
        rowReaders.forEach { (index, _) ->
            val call = lines.drop(index + 1).take(6).joinToString("\n")
            assertFalse(
                "the terminal panel must not be handed the row's opening directory",
                call.contains("onClose = onCloseTerminalDock") || call.contains("sessionID = activeSession.id"),
            )
        }
    }
}
