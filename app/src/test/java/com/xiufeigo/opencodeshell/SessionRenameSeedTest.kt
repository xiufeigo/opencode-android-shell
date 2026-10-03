package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.sessionRenameSeed
import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.V2Transport
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v75 P3, emulator-5556 on the shared backend: tapping a new session's title
 * opened the rename editor pre-filled with `E2ECHK5558-V1` — another device's
 * session name. The editor seeded from the *display* title, whose chain ends in
 * fallbacks (the nav-time snapshot, the placeholder) that are not this
 * session's name. The seed now keeps the own-title links only, and the
 * per-id registry no longer learns the client's `Untitled` fallback as a name.
 */
class SessionRenameSeedTest {

    private fun seed(
        pending: String? = null,
        live: String? = null,
        tracked: String? = "ses_a",
        onScreen: String = "ses_a",
        learned: String? = null,
    ): String = sessionRenameSeed(
        pendingTitle = pending,
        liveTitle = live,
        trackedSessionId = tracked,
        onScreenSessionId = onScreen,
        learnedTitle = learned,
    )

    // ------------------------------------------------------- the seed itself

    @Test
    fun `an untitled session opens a blank field, never the placeholder`() {
        assertEquals("", seed())
    }

    @Test
    fun `in a switch window the learned name wins over the outgoing live title`() {
        // The frame a tab switch lands on: the live title still belongs to the
        // session the user left (possibly another device's name on a shared
        // backend); the learned name is this session's own and must win.
        assertEquals(
            "Mine",
            seed(live = "E2ECHK5558-V1", tracked = "ses_other", onScreen = "ses_a", learned = "Mine"),
        )
    }

    @Test
    fun `the live title seeds only the session the view model adopted`() {
        assertEquals("Mine", seed(live = "Mine", tracked = "ses_a", onScreen = "ses_a"))
        assertEquals(
            "the outgoing session's name must not seed the incoming one",
            "",
            seed(live = "E2ECHK5558-V1", tracked = "ses_other", onScreen = "ses_a"),
        )
    }

    @Test
    fun `a learned name seeds the editor`() {
        assertEquals("Learned", seed(learned = "Learned"))
    }

    @Test
    fun `the optimistic rename wins`() {
        assertEquals("Typing", seed(pending = "Typing", live = "Old", learned = "Older"))
    }

    @Test
    fun `blanks fall through to an empty seed`() {
        assertEquals("", seed(pending = "  ", live = " ", learned = ""))
    }

    // ------------------------------------ the registry learns real names only

    private val transport = V2Transport(V2Api("http://127.0.0.1:1/".toHttpUrl()))

    private fun info(title: String?): JSONObject {
        val obj = JSONObject()
        obj.put("id", "ses_1")
        if (title != null) obj.put("title", title)
        return obj
    }

    private fun rowOf(title: String?) = with(transport) {
        V2Api.SessionInfo.fromJson(info(title)).toRow()
    }

    @Test
    fun `a row without a server title reports no learned name`() {
        val row = rowOf(null)
        assertEquals(
            "the row display still falls back for the list surfaces",
            V2Api.SessionInfo.UNTITLED,
            row.title,
        )
        assertFalse(
            "but the fallback must never be filed as the session's name",
            row.hasTitle,
        )
    }

    @Test
    fun `a row with a server title reports it as learned`() {
        val row = rowOf("E2ECHK5556-T1")
        assertTrue(row.hasTitle)
        assertEquals("E2ECHK5556-T1", row.title)
    }

    @Test
    fun `a blank server title is not a learned name either`() {
        val row = rowOf("  ")
        assertFalse(row.hasTitle)
    }

    // ------------------------------------------------- the wiring (pinned)

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    @Test
    fun `the header editor seeds from the rename seed, not the display title`() {
        val header = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/header/SessionHeader.kt")
        assertTrue(
            "SessionHeader must expose the own-title-only seed",
            header.contains("renameSeed: String? = null"),
        )
        assertTrue(
            "InlineTitleInput must open on the seed",
            header.contains("initial = renameSeed ?: title"),
        )
    }

    @Test
    fun `the session screen feeds the header its own-title seed`() {
        val screen = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")
        assertTrue(
            "SessionScreen must pass sessionRenameSeed into SessionHeader",
            screen.contains("renameSeed = sessionRenameSeed("),
        )
    }

    @Test
    fun `the catalogue read files only titles the server sent`() {
        val vm = source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")
        assertTrue(
            "fetchSessionRows must skip the Untitled fallback",
            vm.contains("optStringOrNull(row.raw, \"title\").isNullOrBlank()"),
        )
        assertTrue(
            "refreshSnapshot must adopt only a sent title",
            vm.contains("if (info.hasTitle && !info.title.isNullOrBlank())"),
        )
    }
}
