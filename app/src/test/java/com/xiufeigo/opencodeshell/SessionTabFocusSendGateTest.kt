package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.SessionComposerGate
import com.xiufeigo.opencodeshell.ui.session.drawer.MobileForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source pins + pure gate for the silent-prompt fix (P1): typing with the
 * Terminal tab up and pressing Enter once submitted a session prompt from the
 * hidden composer (server-default model). Three layers are pinned here —
 * tab switches detach IME focus, hidden tabs keep no input subtree, and every
 * composer send path short-circuits while the composer is not visible.
 */
class SessionTabFocusSendGateTest {

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        // CRLF sources (`SessionScreen.kt`) would break `\n` pins otherwise.
        return file.readText().replace("\r\n", "\n")
    }

    private fun sessionScreen() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")

    private fun mobileSessionTabs() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/review/MobileSessionTabs.kt")

    @Test
    fun switchingTabsDetachesImeFocusFromThePreviousTabsInput() {
        val tabs = mobileSessionTabs()
        assertTrue(tabs.contains("LocalFocusManager"))
        assertTrue(tabs.contains("focusManager.clearFocus()"))
        // focus leaves *before* the new surface is selected
        assertTrue(tabs.contains("focusManager.clearFocus()\n        onSelect(tab)"))
        val screen = sessionScreen()
        assertTrue(screen.contains("val focusManager = LocalFocusManager.current"))
        // focus is dropped before the switch is written, unconditionally
        assertTrue(screen.contains("focusManager.clearFocus()\n        mobileTabName = tab.name"))
        // `switchMobileTab` is the single writer of `mobileTabName`, so no
        // programmatic switch (palette, ring, terminal close) can bypass the
        // clear — the only assignment left must be inside the helper.
        assertEquals(1, Regex("""mobileTabName =""").findAll(screen).count())
        assertTrue(
            screen.substringAfter("val switchMobileTab")
                .contains("mobileTabName = tab.name"),
        )
    }

    @Test
    fun hiddenTabsKeepNoInputSubtreeThatCouldReceiveKeys() {
        val screen = sessionScreen()
        assertTrue(screen.contains("androidx.compose.runtime.key(mobileTab)"))
        // The composer mounts exactly once, inside `timelinePane`, which the
        // mobile surface `when` only composes for the SESSION branch. v76: the
        // mount point is the single `composer = { composerSlot(composerRegion) }`
        // argument of `SessionPaneArrangement` — one invocation of the content,
        // whatever the pane's arrangement.
        assertEquals(1, Regex("""composerSlot\(composerRegion\)""").findAll(screen).count())
        assertEquals(1, Regex("""movableContentOf<""").findAll(screen).count())
        val hiddenBranches = screen
            .substringAfter("androidx.compose.runtime.key(mobileTab)")
            .substringBefore("else -> timelinePane")
        assertFalse(hiddenBranches.contains("composerRegion("))
        assertFalse(hiddenBranches.contains("timelinePane("))
    }

    @Test
    fun everyComposerSendPathShortCircuitsWhenTheComposerIsHidden() {
        val screen = sessionScreen()
        assertTrue(screen.contains("val composerSendAllowed = SessionComposerGate.acceptsInput("))
        val region = screen.substringAfter("val composerRegion").substringBefore("val timelinePane")
        // onSend / onSubmit / onStructuredSubmit / onQueueSteer / onQueueConfirm
        // all admit prompts; each must sit behind the gate. onQueueRemove is a
        // cancel and stays open.
        assertEquals(5, Regex("""if \(composerSendAllowed\)""").findAll(region).count())
        assertTrue(region.contains("chatViewModel.send("))
        assertTrue(region.contains("chatViewModel.submit("))
    }

    @Test
    fun gateRejectsEveryHiddenComposerSurface() {
        assertFalse(
            SessionComposerGate.acceptsInput(
                desktop = false,
                tab = MobileForm.Tab.TERMINAL,
                usageOpen = false,
                summaryOpen = false,
            ),
        )
        assertFalse(
            SessionComposerGate.acceptsInput(
                desktop = false,
                tab = MobileForm.Tab.CHANGES,
                usageOpen = false,
                summaryOpen = false,
            ),
        )
        assertFalse(
            SessionComposerGate.acceptsInput(
                desktop = false,
                tab = MobileForm.Tab.FILES,
                usageOpen = false,
                summaryOpen = false,
            ),
        )
        assertFalse(
            SessionComposerGate.acceptsInput(
                desktop = false,
                tab = MobileForm.Tab.SESSION,
                usageOpen = true,
                summaryOpen = false,
            ),
        )
        assertFalse(
            SessionComposerGate.acceptsInput(
                desktop = false,
                tab = MobileForm.Tab.SESSION,
                usageOpen = false,
                summaryOpen = true,
            ),
        )
    }

    @Test
    fun gateStaysOpenForTheSurfacesThatActuallyShowTheComposer() {
        assertTrue(
            SessionComposerGate.acceptsInput(
                desktop = false,
                tab = MobileForm.Tab.SESSION,
                usageOpen = false,
                summaryOpen = false,
            ),
        )
        // Desktop overlays the composer on the timeline in every mobile-tab
        // state (the tab strip does not exist there).
        assertTrue(
            SessionComposerGate.acceptsInput(
                desktop = true,
                tab = MobileForm.Tab.TERMINAL,
                usageOpen = true,
                summaryOpen = true,
            ),
        )
    }
}
