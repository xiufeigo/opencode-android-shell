package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.nav.TabLayout
import com.xiufeigo.opencodeshell.ui.settings.GENERAL_PANEL
import com.xiufeigo.opencodeshell.ui.settings.SettingsControl
import com.xiufeigo.opencodeshell.ui.settings.stringValue
import com.xiufeigo.opencodeshell.ui.settings.visibleRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B-16: the General page's「底部导航」switch
 * (`settings.general.row.mobileTitlebarBottom`, `general.tsx:369-381`) and the
 * placement it drives.
 *
 * Upstream keeps the titlebar mounted on every form and only moves it down when
 * the stored position says so — `bottom = mobile() && mobileTitlebarPosition()
 * === "bottom"` (`titlebar.tsx:64-65`, `shell/shell.tsx:33`) — and the row itself
 * only exists on the mobile form. Android stores that placement as the
 * `appearance.tabLayout` value the nav layer already reads (`vertical` = bottom),
 * so the switch and the Experimental Tabs select are two views of one setting and
 * `ui/App.kt`'s `topTitlebar`/`bottomTitlebar` are its two consumers.
 */
class MobileTitlebarRowTest {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    private fun settings(tabLayout: String) = SettingsStore.Settings(
        colorScheme = "system",
        themeId = "oc-2",
        uiFont = "",
        codeFont = "",
        terminalFont = "",
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
        tabLayout = tabLayout,
    )

    private val row = GENERAL_PANEL
        .flatMap { it.rows }
        .first { it.action == "settings-mobile-titlebar-bottom" }

    @Test
    fun `the row is upstream's switch, after the diff-wrap row`() {
        assertEquals(SettingsControl.SWITCH, row.control)
        assertEquals(R.string.settings_general_row_mobileTitlebarBottom_title, row.titleResId)
        assertEquals(R.string.settings_general_row_mobileTitlebarBottom_description, row.descriptionResId)
        val actions = GENERAL_PANEL.flatMap { it.rows }.map { it.action }
        assertEquals(
            "upstream order: … mobileDiffWrap → mobileTitlebarBottom",
            actions.indexOf("settings-mobile-diff-wrap") + 1,
            actions.indexOf("settings-mobile-titlebar-bottom"),
        )
    }

    @Test
    fun `the row exists only on the mobile form`() {
        assertTrue("declared mobile-only", row.mobileOnly)
        val actions = { mobile: Boolean ->
            visibleRows(GENERAL_PANEL, mobile = mobile).flatMap { it.rows }.map { it.action }
        }
        assertTrue("mobile keeps it", "settings-mobile-titlebar-bottom" in actions(true))
        assertFalse("desktop drops it", "settings-mobile-titlebar-bottom" in actions(false))
        // Nothing else changes with the form: the same rows minus that one.
        assertEquals(actions(true).filterNot { it == "settings-mobile-titlebar-bottom" }, actions(false))
    }

    @Test
    fun `the switch reads and writes the placement the nav layer already consumes`() {
        // Checked while the strip sits at the bottom (`vertical`), unchecked at the top.
        assertEquals("true", stringValue(settings("vertical"), "settings-mobile-titlebar-bottom"))
        assertEquals("false", stringValue(settings("horizontal"), "settings-mobile-titlebar-bottom"))
        assertEquals("false", stringValue(settings(TabLayout.HORIZONTAL), row.action))
        assertTrue(TabLayout.bottomStrip("vertical"))
        assertFalse(TabLayout.bottomStrip("horizontal"))

        // The boolean table must not also own this action: its write is the enum,
        // not a boolean pref (two writers on one key would drift).
        assertNull(SettingsStore.BOOLEAN_KEYS[row.action])
    }

    @Test
    fun `the store writes the shared key through the dedicated branch`() {
        val store = source("src/main/java/com/xiufeigo/opencodeshell/SettingsStore.kt")
        assertTrue("dedicated branch", store.contains("\"settings-mobile-titlebar-bottom\" ->"))
        assertTrue("writes the shared key", store.contains("KEY_TAB_LAYOUT,"))
        assertTrue("bottom = vertical", store.contains("TAB_LAYOUT_VERTICAL"))
        assertTrue("top = horizontal", store.contains("TAB_LAYOUT_HORIZONTAL"))
        assertEquals("appearance.tabLayout", SettingsStore.KEY_TAB_LAYOUT)
        assertEquals(TabLayout.KEY, SettingsStore.KEY_TAB_LAYOUT)
        assertEquals(SettingsStore.TAB_LAYOUTS, setOf("horizontal", "vertical"))
    }

    @Test
    fun `the app mounts the top strip or the bottom strip, never both`() {
        val app = source("src/main/java/com/xiufeigo/opencodeshell/ui/App.kt")
        assertTrue(
            "one decision for the two placements",
            app.contains("val bottomTitlebar = !desktopTabs && bottomTabStrip"),
        )
        assertTrue("its complement", app.contains("val topTitlebar = !bottomTitlebar"))
        // v57 P0: on a short window the top strip stands down while the soft
        // keyboard owns the window — it is duplicated chrome that came straight
        // out of the composer's row (`research/v57_verify/tE/v57_4x_kbd_*.png`,
        // `ShellChromeBudget.yieldsToIme`). Still exactly one strip, never both.
        assertTrue("top strip", app.contains("if (topTitlebar && !yieldsToIme) {"))
        assertTrue("bottom strip", app.contains("if (bottomTitlebar) {"))
        assertTrue("and the inset follows the strip", app.contains("if (ShellChromeBudget.topStripOwnsStatusBar(topTitlebar, yieldsToIme))"))
        assertTrue(
            "consumption mirrors the render condition",
            app.contains("Modifier.consumeWindowInsets(WindowInsets.statusBars)"),
        )
        // The bottom bar never renders on a desktop width — the placement is
        // mobile-only upstream (`titlebar.tsx:64-65`).
        assertFalse(app.contains("if (bottomTabStrip && !desktopTabs) {"))
    }

    @Test
    fun `the general panel filters by the shared mobile breakpoint`() {
        val panels = source("src/main/java/com/xiufeigo/opencodeshell/ui/settings/SettingsPanels.kt")
        assertTrue("form test", panels.contains("MobileForm.isMobile(LocalConfiguration.current.screenWidthDp)"))
        // v48: the panel reads the *rendered* rows through the one shared filter
        // (`renderedPanel` = `visibleRows(panelsFor(tab))`), the same entry the
        // settings search reads — so a hidden row cannot be a search hit either.
        assertTrue("filtered rows", panels.contains("renderedPanel(SettingsTab.GENERAL, mobile = mobile)"))
        val model = source("src/main/java/com/xiufeigo/opencodeshell/ui/settings/SettingsModel.kt")
        assertTrue(
            "one filter chain, shared by the renderer and the search",
            model.contains("fun renderedPanel(tab: SettingsTab, mobile: Boolean)") &&
                model.contains("visibleRows(panelsFor(tab), mobile = mobile)"),
        )
        assertTrue("boolean bridge", panels.contains("\"settings-mobile-titlebar-bottom\" -> TabLayout.bottomStrip"))
    }

    /**
     * B-16 follow-up (v30): the switch moved the session tab strip and left the
     * header row — back, ☰, the session name, search — at the top, so half the
     * row's own copy ("标题栏和会话标签页") was untrue. Upstream moves the whole
     * `<header data-slot="titlebar-v2">` (`titlebar.tsx:150-164`, `order-last`
     * when `bottom()`), whose mobile row is the tab trigger plus the trailing
     * actions; the shell renders those as the strip (`ui/App.kt`) and the header
     * row (`SessionHeader`), so both have to follow the one flag.
     */
    @Test
    fun `the bottom stop carries the header row, not only the strip`() {
        val app = source("src/main/java/com/xiufeigo/opencodeshell/ui/App.kt")
        assertTrue(
            "the placement reaches the session body",
            app.contains("titlebarBottom = bottomTitlebar,"),
        )
        val session = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")
        assertTrue(
            "the header is the mobile row that moves",
            session.contains("if (!titlebarBottom) headerSlot()") &&
                session.contains("if (titlebarBottom) headerSlot()"),
        )
        assertTrue(
            "the view tabs stay at the top (they are not titlebar chrome)",
            session.contains("if (!titlebarBottom) headerSlot()\n                MobileSessionTabs("),
        )
        assertTrue(
            "the strip below owns the bottom inset once the header is on it",
            session.contains("WindowInsetsSides.Top + WindowInsetsSides.Horizontal"),
        )
    }

    @Test
    fun `the copy ships translated in all three locales`() {
        val en = source("src/main/res/values/strings_oc_i18n.xml")
        val zh = source("src/main/res/values-zh-rCN/strings_oc_i18n.xml")
        val zht = source("src/main/res/values-zh-rTW/strings_oc_i18n.xml")
        for (name in listOf(
            "settings_general_row_mobileTitlebarBottom_title",
            "settings_general_row_mobileTitlebarBottom_description",
        )) {
            val xml = Regex("""<string name="$name">(.*?)</string>""")
            val enText = xml.find(en)?.groupValues?.get(1)
            assertNotNull("en has $name", enText)
            for ((dir, text) in listOf("zh" to zh, "zht" to zht)) {
                val value = xml.find(text)?.groupValues?.get(1)
                assertNotNull("$dir has $name", value)
                assertTrue("$dir/$name is translated", value != enText)
            }
        }
    }
}
