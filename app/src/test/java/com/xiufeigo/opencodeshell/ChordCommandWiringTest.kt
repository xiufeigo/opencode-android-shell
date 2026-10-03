package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.palette.SessionCommandIntents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B-5: the screen-local half of the chord wiring.
 *
 * Two things are pinned here:
 *
 * 1. [SessionCommandIntents.requestCommand] — the channel a global chord uses to
 *    reach an action whose state lives inside a screen (one request at a time,
 *    each consumable exactly once);
 * 2. the consumer side by source scan, because these handlers live inside
 *    `@Composable` bodies that no JVM test can invoke. A handler that is deleted
 *    or renamed fails here instead of silently turning its chord into a no-op.
 */
class ChordCommandWiringTest {

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText()
    }

    @Test
    fun theCommandChannelCarriesOneRequestAtATime() {
        val intents = SessionCommandIntents()
        assertNull(intents.commandRequest.value)
        intents.requestCommand("session.export")
        val first = intents.commandRequest.value
        assertEquals("session.export", first?.commandId)
        // A second press replaces the pending one and gets a new id, so a screen
        // that missed the first still sees the latest.
        intents.requestCommand("model.choose")
        val second = intents.commandRequest.value
        assertEquals("model.choose", second?.commandId)
        assertTrue((second?.id ?: 0L) > (first?.id ?: 0L))
        // Consuming a stale id is a no-op; consuming the live one clears it.
        intents.consumeCommand(first!!)
        assertEquals("model.choose", intents.commandRequest.value?.commandId)
        intents.consumeCommand(second!!)
        assertNull(intents.commandRequest.value)
    }

    @Test
    fun aBlankCommandIdIsIgnored() {
        val intents = SessionCommandIntents()
        intents.requestCommand("   ")
        assertNull(intents.commandRequest.value)
    }

    @Test
    fun theSessionScreenServesTheFiveScreenLocalIds() {
        val screen = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")
        for (id in listOf(
            "session.export",
            "input.focus",
            "model.choose",
            "terminal.new",
            "message.previous",
            "message.next",
        )) {
            assertTrue("SessionScreen must serve $id", screen.contains("\"$id\""))
        }
        // It reads the channel, guards against a request that predates its mount,
        // and consumes what it ran.
        assertTrue(screen.contains("intents?.commandRequest"))
        assertTrue(screen.contains("intents?.consumeCommand(request)"))
        assertTrue(screen.contains("request.id <= lastCommandRequestId"))
        // The two composer-side hooks the ids drive.
        assertTrue(screen.contains("externalModelOpen = modelChooseRequest"))
        assertTrue(screen.contains("externalFocus = inputFocusRequest"))
        // …and the terminal pane's new-tab hook.
        assertTrue(screen.contains("newTabRequest = newTerminalRequest"))
    }

    @Test
    fun theDraftScreenServesTheLocationCycle() {
        val screen = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/NewSessionScreen.kt")
        assertTrue(screen.contains("\"session.location.cycle\""))
        assertTrue(screen.contains("NewSessionWorkspaceModel.cycleLocation"))
        // The pick is remembered so the cycle has a worktree stop to return to.
        assertTrue(screen.contains("lastWorktree = picked"))
    }

    @Test
    fun theComposerAndTerminalPanesAcceptTheExternalRequests() {
        val composer = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt")
        assertTrue(composer.contains("externalModelOpen: Int = 0"))
        assertTrue(composer.contains("externalFocus: Int = 0"))
        assertTrue(composer.contains("LaunchedEffect(externalFocus)"))

        val terminal = source("src/main/java/com/xiufeigo/opencodeshell/ui/terminal/TerminalPanel.kt")
        assertTrue(terminal.contains("newTabRequest: Int = 0"))
        assertTrue(terminal.contains("LaunchedEffect(newTabRequest)"))

        val dock = source("src/main/java/com/xiufeigo/opencodeshell/ui/terminal/TerminalDock.kt")
        assertTrue(dock.contains("newTabRequest = newTabRequest"))
    }

    @Test
    fun theMobileDrawerCarriesTheReopenRow() {
        val drawer = source("src/main/java/com/xiufeigo/opencodeshell/ui/nav/MobileTabsDrawer.kt")
        // B-6: the phone has no keyboard chord, so the drawer row is the entry.
        assertTrue(drawer.contains("R.string.command_tab_reopenClosed"))
        assertTrue(drawer.contains("if (closedTab != null)"))
        val strip = source("src/main/java/com/xiufeigo/opencodeshell/ui/nav/BottomTabStrip.kt")
        assertTrue(strip.contains("closedTab = closedTab"))
        assertTrue(strip.contains("onReopenClosed = {"))
    }
}
