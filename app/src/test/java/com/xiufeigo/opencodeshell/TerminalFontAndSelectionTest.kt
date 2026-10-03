package com.xiufeigo.opencodeshell

import com.termux.view.textselection.TerminalSelectionPolicy
import com.termux.view.textselection.TerminalSelectionPolicy.LineAt
import com.xiufeigo.opencodeshell.ui.settings.TimelineDisplayPrefs
import com.xiufeigo.opencodeshell.ui.settings.timelineDisplayPrefs
import com.xiufeigo.opencodeshell.ui.terminal.TerminalPlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v63 tB: the terminal pane could not select anything, and the terminal font
 * setting never reached the pane that is actually on screen.
 *
 * **Font.** `settings.appearance.terminalFont` was only ever read by the
 * standalone `Route.TERMINAL` host (`ui/App.kt` → `TerminalPanelHost`). The
 * session-hosted panes — the docked side/bottom pane (`TerminalDock`) and the
 * mobile terminal tab — both go through `EmbeddedTerminalPanel`, which defaulted
 * the parameter to `""`, so the family resolved to the system mono no matter
 * what the Appearance row said (`research/v63_verify/5554/32_serif.png` →
 * `33_serif_applied.png`: the setting echoed back and the glyphs did not move).
 * The value now rides the same `LocalTimelineDisplayPrefs` channel the docked
 * placement already used.
 *
 * **Selection.** The vendored long press selected the word under the finger and
 * gave up when that cell was blank — which, in a 240 dp pane, is nearly always
 * the case. The result was a one-cell selection: the toolbar came up, nothing
 * highlighted, `Copy` wrote an empty string. The policy below keeps the upstream
 * word selection and adds the line / nearest-line fallbacks, and drops the
 * upstream `-40 px` finger offset (about a row at termux's metrics, more than
 * one row at the shell's 14 sp) for one *rendered* row.
 */
class TerminalFontAndSelectionTest {

    private fun screen(vararg lines: String): LineAt {
        val buffer = lines.toList()
        return LineAt { row -> buffer.getOrElse(row) { "" } }
    }

    private fun settings(terminalFont: String) = SettingsStore.Settings(
        colorScheme = "system",
        themeId = "oc-2",
        uiFont = "",
        codeFont = "",
        terminalFont = terminalFont,
        language = "en",
        autoAcceptPermissions = false,
        shell = "auto",
        reasoningSummaries = true,
        shellToolPartsExpanded = false,
        editToolPartsExpanded = false,
        notifyAgent = true,
        notifyPermissions = true,
        notifyErrors = false,
        soundAgent = "default",
        soundPermissions = "default",
        soundErrors = "default",
        showFileTree = true,
        showSearch = true,
        showStatus = true,
        showCustomAgents = false,
    )

    // ------------------------------------------------------------- the font

    @Test
    fun `terminal font rides the session display-prefs channel`() {
        val prefs: TimelineDisplayPrefs = settings("serif-monospace").timelineDisplayPrefs()
        assertEquals("serif-monospace", prefs.terminalFont)
        // The channel's other members must be untouched by the addition.
        assertEquals(TerminalPlacement.Default, prefs.terminalPlacement)
        assertEquals(TimelineDisplayPrefs.Default.shellToolPartsExpanded, prefs.shellToolPartsExpanded)
    }

    @Test
    fun `an unset terminal font is the empty family, not a hard-coded mono`() {
        assertEquals("", settings("").timelineDisplayPrefs().terminalFont)
        assertEquals("", TimelineDisplayPrefs.Default.terminalFont)
    }

    // ------------------------------------------------------- the selection

    @Test
    fun `a press on a word still selects that word`() {
        val span = TerminalSelectionPolicy.initial(screen("echo hello world"), 0, 6, 20, 1)
        assertEquals(5, span.x1)
        assertEquals(9, span.x2)
    }

    @Test
    fun `a press past the end of the line selects the line's content`() {
        // The v63 shape: a 2-digit number in the first columns, the finger well
        // to the right of it on the same row.
        val span = TerminalSelectionPolicy.initial(screen("42        "), 0, 12, 20, 1)
        assertEquals(0, span.x1)
        assertEquals(1, span.x2)
    }

    @Test
    fun `a press inside a blank row selects that row's content`() {
        val span = TerminalSelectionPolicy.initial(screen("first", "     ", "third"), 1, 2, 20, 3)
        assertEquals(0, span.x1)
        assertEquals(4, span.x2)
    }

    @Test
    fun `a press on the blank tail of the screen reaches the nearest line`() {
        val lines = arrayOf("output", "", "", "")
        val down = TerminalSelectionPolicy.initial(screen(*lines), 1, 3, 20, 4)
        assertEquals(0, down.x1)
        assertEquals(5, down.x2)
        val up = TerminalSelectionPolicy.initial(screen(*lines), 3, 3, 20, 4)
        assertEquals(0, up.x1)
        assertEquals(5, up.x2)
    }

    @Test
    fun `an empty screen still yields a drawable selection`() {
        val span = TerminalSelectionPolicy.initial(screen("", ""), 0, 0, 20, 2)
        assertEquals(0, span.x1)
        assertEquals(19, span.x2)
    }

    @Test
    fun `presses outside the screen are clamped, not dropped`() {
        val span = TerminalSelectionPolicy.initial(screen("abc"), 99, -5, 20, 1)
        assertEquals(0, span.x1)
        assertEquals(2, span.x2)
    }

    @Test
    fun `a blank cell has no word`() {
        assertNull(TerminalSelectionPolicy.wordAt("ab  cd", 2))
        assertNull(TerminalSelectionPolicy.wordAt("", 0))
        assertTrue(TerminalSelectionPolicy.isWordChar('x'))
    }
}
