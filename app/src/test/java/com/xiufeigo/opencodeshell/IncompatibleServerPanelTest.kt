package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.v2.ServerGeneration
import com.xiufeigo.opencodeshell.v2.V2Capability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B-7: the incompatible-server verdict.
 *
 * Upstream's health row carries `{ healthy: false, incompatible: true }`
 * (`runtime/server/health.ts:8,165-166`) and the session route renders the
 * full-screen `IncompatibleServerPanel` from it
 * (`session/route-error.tsx:26-28`). The Android probe must therefore tell "a
 * real OpenCode server with the wrong version" apart from every other negative
 * answer — that is the whole point of
 * [V2Capability.ProbeResult.incompatibleVersion].
 */
class IncompatibleServerPanelTest {

    @Test
    fun nonV2VersionIsIncompatibleAndNamesTheVersion() {
        val result = V2Capability.decideInfo(200, "application/json", """{"version":"1.18.31"}""")
        assertEquals(ServerGeneration.UNKNOWN, result.generation)
        assertEquals("1.18.31", result.version)
        assertEquals("1.18.31", result.incompatibleVersion)
        assertTrue(result.incompatible)
        // A definitive negative: the host answered, so the panel is not a
        // retryable "we could not ask".
        assertFalse(result.isRetryable)
    }

    @Test
    fun v2AndEveryUnmeasuredAnswerAreNotIncompatible() {
        val v2 = V2Capability.decideInfo(200, "application/json", """{"version":"2.0.11"}""")
        assertEquals(ServerGeneration.V2, v2.generation)
        assertNull(v2.incompatibleVersion)
        assertFalse(v2.incompatible)

        // The SPA shell (a v1 server's `/api/info`), an auth wall, non-JSON and
        // a network error all stay plain UNKNOWN — none of them identified a
        // version, so none of them may claim incompatibility.
        val html = V2Capability.decideInfo(200, "text/html", "<html></html>")
        assertFalse(html.incompatible)
        assertNull(html.incompatibleVersion)

        val authWall = V2Capability.decideInfo(401, "application/json", "", credentialsSent = true)
        assertTrue(authWall.authWall)
        assertFalse(authWall.incompatible)
        assertTrue(authWall.isRetryable)

        val notJson = V2Capability.decideInfo(200, "application/json", "not json")
        assertFalse(notJson.incompatible)

        val noVersion = V2Capability.decideInfo(200, "application/json", """{"healthy":true}""")
        assertFalse(noVersion.incompatible)

        val http500 = V2Capability.decideInfo(500, "application/json", "")
        assertFalse(http500.incompatible)
    }

    @Test
    fun thePanelReadsTheVersionItFound() {
        val panel = java.io.File("src/main/java/com/xiufeigo/opencodeshell/ui/session/IncompatibleServerPanel.kt")
            .takeIf { it.isFile }
            ?: java.io.File("app/src/main/java/com/xiufeigo/opencodeshell/ui/session/IncompatibleServerPanel.kt")
        val body = panel.readText()
        // Upstream's two lines + the close-tab button, all on existing keys.
        assertTrue(body.contains("R.string.session_error_incompatible"))
        assertTrue(body.contains("R.string.session_error_incompatible_description"))
        assertTrue(body.contains("R.string.oc_back"))
        assertTrue(body.contains("R.string.oc_session_not_found_retry"))

        // …and the session route mounts it before the not-found branch, like
        // upstream's `activeServer.health?.incompatible` fallback.
        val screen = java.io.File("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")
            .takeIf { it.isFile }
            ?: java.io.File("app/src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")
        val screenBody = screen.readText()
        val panelAt = screenBody.indexOf("IncompatibleServerPanel(")
        val missingAt = screenBody.indexOf("if (sessionMissing)")
        assertTrue(panelAt > 0 && missingAt > 0 && panelAt < missingAt)
        assertTrue(screenBody.contains("chatViewModel.incompatibleServerVersion"))
    }

    @Test
    fun theVersionFieldIsDeclaredBeforeTheInitThatResetsIt() {
        // `init` collects `serverConfig.revision`; the synchronous first
        // emission runs `adoptActiveServer` mid-construction, which resets this
        // field. A declaration below `init` is still null there and NPEs on
        // cold start with a saved server (device-verified crash).
        val vm = java.io.File("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")
            .takeIf { it.isFile }
            ?: java.io.File("app/src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")
        val body = vm.readText()
        val fieldAt = body.indexOf("private val _incompatibleServerVersion")
        val initAt = body.indexOf("    init {")
        assertTrue(fieldAt > 0 && initAt > 0 && fieldAt < initAt)
        assertTrue(body.contains("_incompatibleServerVersion.value = null"))
    }
}
