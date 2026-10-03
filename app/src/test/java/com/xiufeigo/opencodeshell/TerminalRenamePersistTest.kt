package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.terminal.share.PtyApi
import com.xiufeigo.opencodeshell.ui.terminal.share.TerminalStateStore
import com.xiufeigo.opencodeshell.ui.terminal.share.TerminalTabs
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * BUG-1 (v38 tB, `research/v38_verify/tB/v38B_31` vs `v38B_35`): a terminal tab
 * rename showed up in the chip and then vanished — the server's `GET /api/pty`
 * still answered its auto-generated name (`Terminal IQch`), because the panel
 * only ever wrote the title into its own model.
 *
 * The rename now takes the same two steps upstream does
 * (`_upstream/packages/app/src/session/terminal/context.tsx:181-195`: the store,
 * then `pty.update({ptyID, title})`):
 *
 * 1. the tab model plus its per-workspace snapshot ([TerminalTabs.rename] →
 *    `TerminalStateStore`, pinned below), and
 * 2. `PUT /api/pty/{id} {title}` ([PtyApi.updateTitle], pinned on the wire).
 *
 * The panel's wiring is pinned by source (it lives inside a `@Composable` body
 * no JVM test can invoke, the same reason `TabDragWiringTest` scans sources).
 */
class TerminalRenamePersistTest {

    private fun api(
        server: MockWebServer,
        directory: String? = "/w",
        v2: Boolean = false,
    ) = PtyApi(
        baseHttpUrl = server.url("/").toString().toHttpUrlOrNull()!!,
        username = "opencode",
        password = "secret",
        directory = directory,
        http = PtyApi.defaultHttp(),
        v2 = v2,
    )

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText()
    }

    // ------------------------------------------------------------------ wire

    /** The v2 face the panel uses: `PUT /api/pty/{id}` with `location[directory]`. */
    @Test
    fun updateTitlePutsTheTitleOnTheV2Route() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"location":{"directory":"/w"},"data":{"id":"pty_1","title":"renamed-t3","status":"running"}}""",
                ),
            )
            api(server, v2 = true).updateTitle("pty_1", "renamed-t3")

            val request = server.takeRequest()
            assertEquals("PUT", request.method)
            assertEquals("/api/pty/pty_1", request.requestUrl!!.encodedPath)
            assertEquals("/w", request.requestUrl!!.queryParameter("location[directory]"))
            assertTrue(request.headers["Authorization"]!!.startsWith("Basic "))
            val body = JSONObject(request.body.readUtf8())
            assertEquals("renamed-t3", body.getString("title"))
            // The route carries `title` **or** `size` (`Pty.UpdateInput`); a
            // rename must not also resize the grid.
            assertFalse(body.has("size"))
        }
    }

    /** The legacy face keeps its bare path and its `?directory=` query. */
    @Test
    fun updateTitleUsesTheLegacyRoute() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"pty_1"}"""))
            api(server).updateTitle("pty_1", "renamed-t3")

            val request = server.takeRequest()
            assertEquals("PUT", request.method)
            assertEquals("/pty/pty_1", request.requestUrl!!.encodedPath)
            assertEquals("/w", request.requestUrl!!.queryParameter("directory"))
            assertNull(request.requestUrl!!.queryParameter("location[directory]"))
            assertEquals("renamed-t3", JSONObject(request.body.readUtf8()).getString("title"))
        }
    }

    /** A server that does not know the id surfaces its code; the panel swallows it. */
    @Test
    fun updateTitleReportsTheServerError() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(404)
                    .setBody("""{"_tag":"PtyNotFoundError","message":"PTY session not found: pty_1"}"""),
            )
            try {
                api(server, v2 = true).updateTitle("pty_1", "renamed-t3")
                fail("expected PtyApiException")
            } catch (e: PtyApi.PtyApiException) {
                assertEquals(404, e.code)
            }
        }
    }

    // ----------------------------------------------------------------- store

    /**
     * The title the chip shows is the tab model's, and the model's workspace
     * snapshot is what a revived panel reads — the rename has to be in both.
     */
    @Test
    fun theRenamedTitleSurvivesTheWorkspaceSnapshot() {
        val tabs = TerminalTabs()
        tabs.add("pty_1")
        tabs.add("pty_2")
        tabs.add("pty_3")
        assertTrue(tabs.rename("pty_3", "renamed-t3"))

        val key = TerminalStateStore.workspaceKey("/w")
        val stored = TerminalStateStore.encodeAll(
            TerminalStateStore.trim(mapOf(key to TerminalStateStore.snapshot(tabs)), key),
        )
        val restored = TerminalStateStore.decodeAll(stored)[key]!!

        assertEquals(listOf("pty_1", "pty_2", "pty_3"), restored.tabs.map { it.id })
        assertEquals("renamed-t3", restored.tabs[2].title)
        assertNull(restored.tabs[0].title)
        assertEquals(3, restored.nextNumber)
        // What the revive does with it: `TerminalTabs.add(title = tab.title)`.
        val revived = TerminalTabs()
        for (tab in restored.tabs) revived.add(tab.id, title = tab.title, number = tab.number)
        assertEquals("renamed-t3", revived.find("pty_3")!!.title)
    }

    /** A blank edit clears the custom name instead of storing an empty title. */
    @Test
    fun aBlankRenameFallsBackToTheNumberedTitle() {
        val tabs = TerminalTabs()
        tabs.add("pty_1")
        tabs.rename("pty_1", "  renamed  ")
        assertEquals("renamed", tabs.find("pty_1")!!.title)
        tabs.rename("pty_1", "   ")
        assertNull(tabs.find("pty_1")!!.title)
    }

    /**
     * Closing the last tab leaves an **empty** tab list behind — the payload the
     * panel's revive used to return on before seeding the counter, which is how a
     * re-opened panel restarted at "Terminal 1" (and looked like the rename had
     * been rolled back). The counter is in the payload; the panel must read it.
     */
    @Test
    fun anEmptiedWorkspaceStillCarriesTheCounter() {
        val tabs = TerminalTabs()
        tabs.add("pty_1")
        tabs.add("pty_2")
        tabs.add("pty_3")
        tabs.close("pty_1")
        tabs.close("pty_2")
        tabs.close("pty_3")
        assertTrue(tabs.isEmpty)

        val key = TerminalStateStore.workspaceKey("/w")
        val stored = TerminalStateStore.encodeAll(mapOf(key to TerminalStateStore.snapshot(tabs)))
        val restored = TerminalStateStore.decodeAll(stored)[key]!!

        assertTrue(restored.tabs.isEmpty())
        assertEquals(3, restored.nextNumber)
        // The revived panel continues from the stored counter, not from zero.
        val revived = TerminalTabs()
        revived.seedNumber(restored.nextNumber)
        assertEquals(4, revived.add("pty_4")!!.number)
    }

    // --------------------------------------------------------------- wiring

    /** The panel writes the rename through to the server, PTY tabs only. */
    @Test
    fun thePanelWritesTheRenameBackToTheServer() {
        val panel = source("src/main/java/com/xiufeigo/opencodeshell/ui/terminal/TerminalPanel.kt")
        assertTrue(panel.contains("activeApi.updateTitle(id, trimmed)"))
        // Session-bound terminals have no title route (`PersistentPty.UpdateInput`
        // is `{attachmentID?, size}`), so their rename stays local.
        assertTrue(panel.contains("runtimes[id]?.kind == TerminalKind.SESSION"))
        // A blank title has no wire form: it stays local too.
        assertTrue(panel.contains("if (trimmed.isEmpty()) return"))
        // And the strip still routes its editor/menu through the host.
        assertTrue(panel.contains("onRenameTab = { id, title -> renameTab(id, title) }"))
        assertTrue(panel.contains("onRename = { title -> onRenameTab(tab.id, title) }"))

        // The revive seeds the counter *before* the empty-list early return.
        val seed = panel.indexOf("tabs.seedNumber(persisted.nextNumber)")
        val emptyReturn = panel.indexOf("if (persisted.tabs.isEmpty()) return false")
        assertTrue("both markers present", seed >= 0 && emptyReturn >= 0)
        assertTrue("the counter is seeded before the early return", seed < emptyReturn)
    }

    /** The API half the panel calls exists on both faces. */
    @Test
    fun theApiCarriesTheTitleOnBothFaces() {
        val api = source("src/main/java/com/xiufeigo/opencodeshell/ui/terminal/share/PtyApi.kt")
        assertTrue(api.contains("fun updateTitle(id: String, title: String)"))
        assertTrue(api.contains("delegate.ptyUpdate(id, title = title)"))
        assertTrue(api.contains("""JSONObject().put("title", title)"""))
    }
}
