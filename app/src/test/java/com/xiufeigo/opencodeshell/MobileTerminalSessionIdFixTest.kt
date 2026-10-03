package com.xiufeigo.opencodeshell

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source pins for the mobile Terminal tab's `sessionId` fix (P1): the panel's
 * 「Session terminals」 entry is gated on `sessionID != null`
 * (`TerminalPanelHost`), but the mobile four-tab call site only passed
 * `directory`/`onClose` — docked desktop (`TerminalDock`) already passed its
 * `sessionId`, so the session-bound terminals were unreachable on mobile only.
 */
class MobileTerminalSessionIdFixTest {

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        // CRLF sources would break `\n`-sensitive windows otherwise.
        return file.readText().replace("\r\n", "\n")
    }

    private fun sessionScreen() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")

    private fun terminalPanel() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/terminal/TerminalPanel.kt")

    private fun terminalDock() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/terminal/TerminalDock.kt")

    @Test
    fun mobileTerminalTabHandsTheSessionToThePanel() {
        val call = sessionScreen()
            .substringAfter("MobileForm.Tab.TERMINAL -> EmbeddedTerminalPanel(")
            .substringBefore("else -> timelinePane")
        // v50: the session's current directory (`currentDirectory` first), so a
        // new terminal's `pwd` follows a 「移动到…」.
        assertTrue(call.contains("directory = reviewRootPath"))
        assertTrue(
            "the panel must receive the session like TerminalDock does",
            call.contains("sessionID = activeSession.id"),
        )
    }

    @Test
    fun dockedTerminalKeepsPassingItsSessionToo() {
        val call = terminalDock()
            .substringAfter("EmbeddedTerminalPanel(")
        assertTrue(call.contains("sessionID = sessionId"))
    }

    @Test
    fun sessionTerminalsEntryStaysGatedOnTheSession() {
        val panel = terminalPanel()
        assertTrue(panel.contains("onShowSessionTerminals = if (v2api != null && sessionID != null)"))
    }
}
