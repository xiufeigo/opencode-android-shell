package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.maskSessionID
import com.xiufeigo.opencodeshell.ui.session.sessionNotFoundFacts
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A-4 + the W5-INT integration: a 404 on the tracked session must not pop the
 * user back to Home with nothing said, and the context tab's export truncation
 * must actually reach the projection.
 *
 * Upstream `SessionErrorFallback` (`session/route-error.tsx:31-61`) replaces the
 * whole session view with a not-found page — title, description, the display
 * server name, the session id and a close-tab button. This shell used to run
 * `if (sessionMissing) onBack()` from an effect: a transient 404 silently
 * changed the screen and left the user no id to report.
 *
 * Source-pinned (the house style for Compose structure), plus the one pure
 * mapping the panel owns.
 */
class SessionMissingPanelTest {

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText()
    }

    private fun sessionScreen() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")

    private fun panel() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionNotFoundPanel.kt")

    // ------------------------------------------------------------ A-4 wiring

    @Test
    fun theMissingSessionNoLongerSilentlyPopsBack() {
        val body = sessionScreen()
        assertFalse(
            "the silent `onBack()` on sessionMissing must be gone",
            body.contains("if (sessionMissing) onBack()"),
        )
        assertTrue(body.contains("SessionNotFoundPanel("))
        assertTrue(body.contains("sessionID = activeSession.id"))
        // The retry re-enters the same session: it clears the 404 latch and
        // reopens the streams that latch closed.
        assertTrue(body.contains("onRetry = retryMissingSession"))
        assertTrue(body.contains("chatViewModel.observeSession("))
        assertTrue(body.contains("chatViewModel.start()"))
    }

    @Test
    fun thePanelCarriesUpstreamsThreeLinesAndTheId() {
        val body = panel()
        assertTrue(body.contains("R.string.oc_session_not_found"))
        assertTrue(body.contains("R.string.oc_session_not_found_description"))
        // The server name + the id block, only with an id (`Show when={props.sessionID}`).
        assertTrue(body.contains("facts.serverLabel"))
        assertTrue(body.contains("facts.sessionID"))
        assertTrue(body.contains("FontFamily.Monospace"))
    }

    @Test
    fun thePanelStringsExistInAllLocales() {
        val locales = listOf(
            "src/main/res/values/strings.xml",
            "src/main/res/values-zh-rCN/strings.xml",
            "src/main/res/values-zh-rTW/strings.xml",
        )
        val keys = listOf(
            "oc_session_not_found",
            "oc_session_not_found_description",
            "oc_session_not_found_retry",
        )
        locales.forEach { relative ->
            val xml = source(relative)
            keys.forEach { key -> assertTrue("$relative: $key", xml.contains("name=\"$key\"")) }
        }
    }

    // -------------------------------------------------- W5-INT truncation half

    @Test
    fun theContextProjectionReceivesTheDrainsTruncation() {
        val body = sessionScreen()
        // The frame's own report is kept instead of being dropped when the frame
        // is unwrapped into `contextSession` / `contextMessages`.
        assertTrue(body.contains("contextTruncated = frame.truncated"))
        assertTrue(body.contains("contextTruncatedPages = frame.truncatedPages"))
        assertTrue(body.contains("exportTruncated = contextTruncated"))
        assertTrue(body.contains("exportTruncatedPages = contextTruncatedPages"))
        // Both are `remember` keys, otherwise a second, truncated read of the
        // same session would never re-project.
        assertTrue(body.contains("        contextTruncated,"))
        assertTrue(body.contains("        contextTruncatedPages,"))
    }

    // ------------------------------------------------------------ pure mapping

    @Test
    fun aBlankServerUrlHidesTheServerLine() {
        assertNull(sessionNotFoundFacts("ses_1", null).serverLabel)
        assertNull(sessionNotFoundFacts("ses_1", "   ").serverLabel)
        assertEquals("http://***", sessionNotFoundFacts("ses_1", " http://127.0.0.1:4095 ").serverLabel)
    }

    /**
     * v63 BUG-1: the raw lines were the leak. The screenshot of this page is
     * what a user files, so neither the server address nor the internal session
     * id may appear on it in the clear.
     */
    @Test
    fun theServerAddressAndTheSessionIdAreMasked() {
        val facts = sessionNotFoundFacts("ses_9f3c1a77b2d4e560", "http://127.0.0.1:4095")
        assertEquals("ses_9f…e560", facts.sessionID)
        assertEquals("http://***", facts.serverLabel)
        assertFalse(facts.sessionID.contains("9f3c1a77b2d4e5"))
        assertFalse(facts.serverLabel.orEmpty().contains("127.0.0.1"))
        assertFalse(facts.serverLabel.orEmpty().contains("4095"))
    }

    /** A display *name* is not an address, so it is left readable. */
    @Test
    fun aServerNameThatIsNotAUrlIsLeftAlone() {
        assertEquals("workstation", sessionNotFoundFacts("ses_12345678", "workstation").serverLabel)
        assertEquals("vpn-gw.internal", sessionNotFoundFacts("ses_12345678", "vpn-gw.internal").serverLabel)
    }

    /** Too short to mask meaningfully: every character is a placeholder. */
    @Test
    fun aShortIdIsFullyMasked() {
        assertEquals("********", maskSessionID("ses_1234"))
        assertEquals("", maskSessionID(""))
    }

    @Test
    fun theIdLineIsTrimmedBeforeItIsMaskedAndNeverBlank() {
        // Surrounding whitespace must not eat into the kept prefix/suffix.
        assertEquals("ses_ab…efgh", sessionNotFoundFacts("  ses_abcdefgh  ", null).sessionID)
        assertEquals("", sessionNotFoundFacts("   ", null).sessionID)
    }
}
