package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.settings.SETTINGS_SHORT_IME_BAND_DP
import com.xiufeigo.opencodeshell.ui.settings.SERVER_DIALOG_MIN_BAND_DP
import com.xiufeigo.opencodeshell.ui.settings.settingsTopChromeCollapsed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v84 「720x1280 @420dpi + keyboard: settings content collapses to a sliver」.
 *
 * Device evidence (`research/v84_verify/5556/44_p_edit_kb2.png` … `48_p_shelltab.png`):
 * with the keyboard up the visible card above the IME is ~170dp, and the
 * narrow chrome — Close 48 + search 52 + tab strip 40 + hairlines ≈ 141dp —
 * eats all of it. The Servers `Edit server` card (a sibling of the panel
 * column in the slot's Box, so clipped with no scroller to reach it) does not
 * render at all, and the Shell page is equally empty. Keyboard down renders
 * fine.
 *
 * The fix is two halves: the narrow top chrome yields while a field below
 * holds the keyboard ([settingsTopChromeCollapsed] + the `chromeCollapsed`
 * branch of `SettingsNavTopBar`), and the Servers / Shell panels own one
 * outer scroll (the v32 single-scroll shape), so the appended add/edit cards
 * scroll with the panel instead of being clipped behind it.
 */
class SettingsShortWindowImeV84Test {

    /** The 720x1280 @420dpi window (`wm size` / `wm density`): px × 160/420. */
    private val windowDp = 1280f * 160f / 420f

    /** The keyboard's top edge at ~y=545 → `(1280 − 545) × 160/420`. */
    private val imeDp = 735f * 160f / 420f

    /** The desktop window the v82/v83 pins measure in (1366x768 @160dpi). */
    private val desktopWindowDp = 768f

    /** That window's keyboard (`research/v83_verify/5556`: `768 − 342`). */
    private val desktopImeDp = 426f

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private fun window(text: String, from: String, to: String): String {
        val start = text.indexOf(from)
        assertTrue("missing window start: $from", start >= 0)
        val end = text.indexOf(to, start + from.length)
        assertTrue("missing window end: $to", end > start)
        return text.substring(start, end)
    }

    private fun settingsScreen() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/settings/SettingsScreen.kt")

    private fun serversPanel() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/settings/ServersPanel.kt")

    private fun settingsPanels() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/settings/SettingsPanels.kt")

    // ------------------------------------------------------- the collapse rule

    @Test
    fun theShortWindowWithTheKeyboardUpCollapsesTheChrome() {
        // 487.6 − 280 = ~207.6 < 320: the measured v84 state.
        assertTrue(
            "band ${windowDp - imeDp} must collapse",
            settingsTopChromeCollapsed(windowDp, imeDp),
        )
    }

    @Test
    fun theDesktopWindowWithTheKeyboardUpKeepsItsChrome() {
        // 768 − 426 = 342 ≥ 320: the v82/v83 geometry is untouched.
        assertFalse(
            "band ${desktopWindowDp - desktopImeDp} must not collapse",
            settingsTopChromeCollapsed(desktopWindowDp, desktopImeDp),
        )
    }

    @Test
    fun withTheKeyboardDownNothingCollapses() {
        assertFalse(settingsTopChromeCollapsed(windowDp, 0f))
        assertFalse(settingsTopChromeCollapsed(200f, 0f))
        assertFalse(settingsTopChromeCollapsed(desktopWindowDp, 0f))
        // A nonsense inset is "no keyboard", like the band helpers' guards.
        assertFalse(settingsTopChromeCollapsed(windowDp, -50f))
    }

    @Test
    fun aLiveQueryOrAFocusedSearchFieldVetoesTheCollapse() {
        // The hits render in that same chrome, and unmounting a focused field
        // would drop the IME connection the user is typing on.
        assertFalse(settingsTopChromeCollapsed(windowDp, imeDp, queryBlank = false))
        assertFalse(settingsTopChromeCollapsed(windowDp, imeDp, searchFocused = true))
        assertTrue(
            settingsTopChromeCollapsed(windowDp, imeDp, queryBlank = true, searchFocused = false),
        )
    }

    @Test
    fun theThresholdIsTheChromePlusTheFormFloor() {
        assertEquals(320f, SETTINGS_SHORT_IME_BAND_DP)
        // Full narrow chrome (~141dp: Close 48 + search 52 + strip 40 +
        // hairlines) plus the second-level form's floor must fit the band that
        // keeps the chrome: below it the chrome yields instead.
        assertTrue(
            "the band that keeps the chrome must clear chrome + form floor",
            SETTINGS_SHORT_IME_BAND_DP >= 141f + SERVER_DIALOG_MIN_BAND_DP,
        )
    }

    // --------------------------------------- the dialog that applies the rule

    @Test
    fun theNarrowBranchComputesTheCollapseAndHandsItToTheTopBar() {
        val dialog = window(settingsScreen(), "fun SettingsDialogContent(", "private fun PanelSlot(")
        assertTrue(dialog.contains("settingsTopChromeCollapsed("))
        assertTrue(dialog.contains("WindowInsets.ime.getBottom(this)"))
        assertTrue(dialog.contains("chromeCollapsed = chromeCollapsed"))
        assertTrue(dialog.contains("onSearchFocusChange = { searchFocused = it }"))
    }

    @Test
    fun theCloseRowAndTheWideSidebarNeverCollapse() {
        // Only the narrow strip branch consumes the flag: the Close row is the
        // layer's only exit, and the short-window desktop keeps its sidebar.
        val screen = settingsScreen()
        val close = window(screen, "BoxWithCappedSize(modifier) {", "if (wide) {")
        assertTrue(close.contains("R.string.oc_settings_close"))
        assertFalse(
            "the Close row must not read the collapse flag",
            close.contains("chromeCollapsed"),
        )
        val sidebar = window(screen, "if (wide) {", "} else {")
        assertTrue(sidebar.contains("SettingsNavList("))
        assertFalse(
            "the wide sidebar keeps its search and column",
            sidebar.contains("chromeCollapsed"),
        )
    }

    @Test
    fun theTopBarGatesOnlyTheSearchFieldAndTheStrip() {
        val bar = window(settingsScreen(), "private fun SettingsNavTopBar(", "fun SettingsSearchField(")
        assertTrue(bar.contains("chromeCollapsed: Boolean = false"))
        assertTrue(bar.contains("onSearchFocusChange: ((Boolean) -> Unit)? = null"))
        assertEquals(
            "only the search box and the strip yield — the results stay",
            2,
            Regex("if \\(!chromeCollapsed\\)").findAll(bar).count(),
        )
        assertTrue(bar.contains("onFocusChange = onSearchFocusChange"))
        // The strip's scroll state stays hoisted: collapsing must not lose the
        // selection tracking the v7 pin covers.
        val hooks = bar.indexOf("rememberLazyListState()")
        val gate = bar.indexOf("if (!chromeCollapsed)", hooks)
        val strip = bar.indexOf("LazyRow(", gate)
        assertTrue("hooks before the gate before the strip", hooks in 1 until gate && gate < strip)
        assertTrue(bar.contains("scrollToItem"))
    }

    @Test
    fun theSearchFieldReportsFocusWithoutChangingItsGeometry() {
        val field = window(settingsScreen(), "private fun SettingsSearchField(", "fun SettingsSearchResults(")
        assertTrue(field.contains("onFocusChange: ((Boolean) -> Unit)? = null"))
        assertTrue(field.contains("Modifier.onFocusChanged"))
        // The wide sidebar passes nothing: its field renders exactly as before.
        val list = window(settingsScreen(), "private fun SettingsNavList(", "private fun SettingsNavTopBar(")
        assertTrue(list.contains("SettingsSearchField(query = query, onQueryChange = onQueryChange)"))
    }

    // --------------------------------- the panels that own the freed viewport

    @Test
    fun theServersPanelScrollsHeaderListAndCardsAsOne() {
        val panel = window(serversPanel(), "fun ServersPanel(", "private fun ServerRow(")
        assertTrue(
            panel.contains("Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()))"),
        )
        assertTrue(panel.contains("SettingsPanelBody(scroll = false)"))
        assertEquals(
            "one outer scroller for the panel — the form keeps its own inside the dialog (v83 pin)",
            1,
            Regex("verticalScroll\\(").findAll(panel).count(),
        )
        val scroller = panel.indexOf("verticalScroll(rememberScrollState())")
        val body = panel.indexOf("SettingsPanelBody(scroll = false)")
        val edit = panel.indexOf("if (adding || editing != null)")
        val ssh = panel.indexOf("if (addingSsh)")
        assertTrue("the cards ride the panel's scroll, not the slot's clip", scroller < body && body < edit && edit < ssh)
    }

    @Test
    fun theShellPanelScrollsItsHeaderWithItsPage() {
        val shell = window(settingsPanels(), "fun ShellPanel(", "/** Diagnostic token")
        assertTrue(
            shell.contains("Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()))"),
        )
        assertTrue(shell.contains("SettingsPanelBody(scroll = false)"))
        assertEquals(
            1,
            Regex("verticalScroll\\(").findAll(shell).count(),
        )
    }
}
