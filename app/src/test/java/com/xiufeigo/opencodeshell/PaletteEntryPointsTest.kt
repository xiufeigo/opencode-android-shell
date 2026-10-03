package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.palette.PaletteSources
import com.xiufeigo.opencodeshell.ui.palette.appCommands
import com.xiufeigo.opencodeshell.ui.palette.commandEntries
import com.xiufeigo.opencodeshell.ui.palette.searchEntries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v35 tA-terminal: the palette's **touch** entries.
 *
 * The tester's report (`research/v35_verify/tA_terminal/`): on the phone form
 * neither `Ctrl+K` / `Ctrl+Shift+P` (a chord injected with `input keyevent` never
 * reaches `GlobalKeybindFocus`) nor the ⋮ session menu opened the command
 * palette, so the ~40 commands behind it — `debugBar.toggle` among them — had no
 * UI entry at all.
 *
 * Upstream has exactly one visible control for it: the "search and command
 * palette button in the title bar" that `settings.general.row.showSearch`
 * toggles (`settings.general.row.showSearch.description`), mounted into the
 * titlebar's trailing slot (`TitlebarRight`, `right-slot.tsx`). Everything else
 * is a chord (`command.tsx:397-424`). This class pins the port:
 *
 * * the two titlebar strips ([DesktopTabStrip] / [BottomTabStrip]) — the
 *   Android titlebar row — carry the button,
 * * `ui/App.kt` hands both of them the host-specific palette and publishes
 *   [com.xiufeigo.opencodeshell.ui.palette.LocalPaletteHost] for the header,
 * * the ⋮ menu carries 命令面板 and 调试栏 as rows that collapse the menu first.
 *
 * Compose rows cannot run on the JVM unit-test harness, so — like
 * `SessionOverflowMenuTest` and `ChordCommandWiringTest` — the wiring is pinned
 * against the composable sources, and the one half that *is* pure data (the
 * `debugBar.toggle` row being findable by a query) is exercised directly.
 */
class PaletteEntryPointsTest {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    private fun desktopStrip() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/nav/DesktopTabStrip.kt")

    private fun bottomStrip() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/nav/BottomTabStrip.kt")

    private fun app() = source("src/main/java/com/xiufeigo/opencodeshell/ui/App.kt")

    private fun header() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/header/SessionHeader.kt")

    /** The ⋮ menu body — the same slice `SessionOverflowMenuTest` scans. */
    private fun overflowMenu(): String {
        val body = header()
        val start = body.indexOf("private fun SessionOverflowMenu")
        val end = body.indexOf("private fun MenuDivider")
        assertTrue(start >= 0 && end > start)
        return body.substring(start, end)
    }

    // ------------------------------------------------------------ the button

    @Test
    fun bothStripPlacementsCarryThePaletteButton() {
        for ((name, strip) in listOf("top" to desktopStrip(), "bottom" to bottomStrip())) {
            assertTrue("$name strip takes the callback", strip.contains("onOpenPalette: (() -> Unit)? = null"))
            assertTrue("$name strip hides the button without a host", strip.contains("if (onOpenPalette != null)"))
            assertTrue(
                "$name strip labels the button with the palette's own string",
                strip.contains("stringResource(R.string.oc_palette_title)"),
            )
            assertTrue("$name strip draws the >_ console glyph", strip.contains("R.drawable.ic_oc_console"))
            // The button is a real target, not a drawing: the shared icon button
            // carries the click role + description.
            assertTrue(strip.contains("onClick = onOpenPalette"))
        }
    }

    @Test
    fun theButtonSitsInTheTrailingSlotAfterTheNewSessionButton() {
        // Upstream's titlebar order is `[home] [tabs] [+] … [TitlebarRight]`; the
        // palette button is the trailing slot's only occupant on Android, so it
        // has to follow the `+` rather than precede the tabs.
        for (strip in listOf(desktopStrip(), bottomStrip())) {
            val plus = strip.lastIndexOf("R.string.oc_new_session")
            val palette = strip.lastIndexOf("R.string.oc_palette_title")
            assertTrue("the palette button trails the + button", palette > plus)
        }
    }

    @Test
    fun theScaffoldHandsBothPlacementsTheHostSpecificPalette() {
        val app = app()
        // One expression, both strips: the same `paletteHost()` decision the chord
        // makes (Home palette on Home, the session one inside a session).
        assertEquals(2, app.split("onOpenPalette = { openPalette(paletteHost()) }").size - 1)
    }

    // ------------------------------------------------------------- the host

    @Test
    fun theSessionRoutePublishesThePaletteHost() {
        val app = app()
        assertTrue(
            "the host is published next to the other session hosts",
            app.contains("LocalPaletteHost provides PaletteHost("),
        )
        assertTrue("the palette dialog opener", app.contains("open = { kind -> openPalette(kind) }"))
        assertTrue("the debug-bar flag is read from composition", app.contains("debugBarOpen = debugBarOpen"))
        // One flip for the palette row, the ⋮ row and `GlobalChord.DEBUG_BAR`.
        assertTrue(app.contains("toggleDebugBar = actions.onToggleDebugBar"))
    }

    @Test
    fun theHeaderReadsTheHostInsteadOfTakingANewParameter() {
        val body = header()
        assertTrue(body.contains("val paletteHost = LocalPaletteHost.current"))
        assertTrue(body.contains("paletteHost = paletteHost"))
        // …and null-safe, so a preview / a header outside the shell keeps the
        // menu it always had.
        assertTrue(overflowMenu().contains("if (paletteHost != null)"))
    }

    // --------------------------------------------------------- the ⋮ menu

    @Test
    fun theOverflowMenuCarriesThePaletteRow() {
        val menu = overflowMenu()
        val label = menu.indexOf("R.string.oc_palette_title")
        val dispatch = menu.indexOf("paletteHost.open(", label)
        assertTrue("the ⋮ menu offers 命令面板", label >= 0 && dispatch > label)
        assertTrue(
            "the row opens the session palette",
            menu.substring(dispatch).startsWith("paletteHost.open(PaletteHostKind.SESSION)"),
        )
        assertTrue(
            "the palette row must collapse the menu, or it stays open over the dialog",
            menu.substring(label, dispatch).contains("expanded = false"),
        )
    }

    @Test
    fun theOverflowMenuCarriesTheDebugBarToggle() {
        val menu = overflowMenu()
        val label = menu.indexOf("R.string.command_debugBar_toggle")
        val dispatch = menu.indexOf("paletteHost.toggleDebugBar()", label)
        assertTrue("the ⋮ menu offers 调试栏", label >= 0 && dispatch > label)
        assertTrue(
            "the row must collapse the menu, or it stays open over the bar",
            menu.substring(label, dispatch).contains("expanded = false"),
        )
        // The row reports its state the way `HeaderIconButton` does: the accent
        // tint, fed by the host's own flag.
        assertTrue(menu.contains("active = paletteHost.debugBarOpen"))
    }

    @Test
    fun theStateTintIsTheRowsOnlyStateAffordance() {
        val body = header()
        val start = body.indexOf("private fun MenuRow")
        val end = body.indexOf("fun SessionSummaryPopover")
        assertTrue(start >= 0 && end > start)
        val row = body.substring(start, end)
        assertTrue(row.contains("active: Boolean = false"))
        assertTrue(row.contains("active -> c.iconAccent"))
        // A trailing glyph would push "Toggle debug bar" past the fixed 120 px
        // `MenuV2` width (§2.2), so the tint is the whole state.
        assertFalse(row.contains("ic_oc_check"))
    }

    @Test
    fun theTwoPaletteEntriesAreTheOnlyNewRows() {
        val menu = overflowMenu()
        // The M2/A8/W6 rows stay exactly as they were: one export entry, the four
        // session actions and the worktree create.
        assertEquals(1, menu.split("R.string.command_session_export").size - 1)
        assertEquals(1, menu.split("R.string.oc_move_session").size - 1)
        assertEquals(1, menu.split("R.string.workspace_new").size - 1)
        assertEquals(1, menu.split("R.string.oc_palette_title").size - 1)
        assertEquals(1, menu.split("R.string.command_debugBar_toggle").size - 1)
    }

    // ------------------------------------------- the palette row itself (JVM)

    @Test
    fun theDebugBarRowIsFindableByAQuery() {
        val commands = appCommands(onOpenSettings = {}, onToggleDebugBar = {})
        val row = commands.first { it.id == "debugBar.toggle" }
        // The palette resolves titles through `stringResource`; this test feeds
        // the same shape with the shipped copy so the search runs for real.
        val entries = commandEntries(commands) { resId ->
            when (resId) {
                row.titleResId -> "Toggle debug bar"
                else -> "row"
            }
        }
        val sources = PaletteSources(commands = entries)
        assertTrue(
            "\"debug\" must find `debugBar.toggle`",
            searchEntries(sources, "debug").any { it.id == "command:debugBar.toggle" },
        )
        assertTrue(
            "\"bar\" must find it too (the search is a plain contains over the haystack)",
            searchEntries(sources, "bar").any { it.id == "command:debugBar.toggle" },
        )
        // The zh copy (`values-zh-rCN`) — the locale the tester ran in. The
        // title no longer carries an English word at all, so this lands on the
        // row's id words (`PaletteSearchTest` pins the English dimension).
        val zh = commandEntries(commands) { resId ->
            if (resId == row.titleResId) "切换调试栏" else "row"
        }
        assertTrue(
            "「调试」 must find the row in Chinese",
            searchEntries(PaletteSources(commands = zh), "调试").any { it.id == "command:debugBar.toggle" },
        )
    }

    @Test
    fun theStripButtonOpensThePaletteTheChordWould() {
        // `PaletteHost.open` is `ui/App.kt`'s `openPalette`, i.e. the very
        // function `GlobalChord.PALETTE` calls — one dialog layer, so the button
        // cannot drift from the chord.
        val app = app()
        assertTrue(app.contains("onPalette = openPalette"))
        assertTrue(app.contains("onOpenPalette = { openPalette(paletteHost()) }"))
        assertTrue(app.contains("private fun showPaletteDialog("))
    }
}
