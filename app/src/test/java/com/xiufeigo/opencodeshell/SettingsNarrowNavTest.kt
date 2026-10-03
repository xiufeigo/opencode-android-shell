package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.settings.SETTINGS_NAV_TABS
import com.xiufeigo.opencodeshell.ui.settings.SettingsTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v7 follow-up: every one of the 14 tabs must stay reachable at any window
 * size, in both layout branches (`SettingsDialogContent` switches at 640dp).
 *
 * The pure half pins the tab inventory (a tab dropped from `SETTINGS_NAV`
 * silently vanishes from both branches) and the shared render order both
 * branches consume; the render half pins each branch on the repo
 * `FindFilesRoutingTest` source convention.
 */
class SettingsNarrowNavTest {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    private fun window(src: String, from: String, to: String): String {
        val start = src.indexOf(from)
        assertTrue("missing window start: $from", start >= 0)
        val end = src.indexOf(to, start + from.length)
        assertTrue("missing window end: $to", end > start)
        return src.substring(start, end)
    }

    @Test
    fun everyTabIsInTheNavInventory() {
        assertEquals(
            listOf(
                SettingsTab.GENERAL,
                SettingsTab.APPEARANCE,
                SettingsTab.NOTIFICATIONS,
                SettingsTab.SHORTCUTS,
                SettingsTab.PAIRING,
                SettingsTab.PROJECTS,
                SettingsTab.WORKSPACES,
                SettingsTab.PROVIDERS,
                SettingsTab.MODELS,
                SettingsTab.EXTENSIONS,
                SettingsTab.SERVERS,
                SettingsTab.SHELL,
                SettingsTab.EXPERIMENTAL,
                SettingsTab.ABOUT,
            ),
            SETTINGS_NAV_TABS,
        )
    }

    @Test
    fun narrowTopBarScrollsTheFullTabRow() {
        val screen = source(
            "src/main/java/com/xiufeigo/opencodeshell/ui/settings/SettingsScreen.kt",
        )
        val bar = window(screen, "private fun SettingsNavTopBar", "fun SettingsSearchField")
        // Renders the whole inventory (no first-section slice)…
        assertTrue(bar.contains("SETTINGS_NAV"))
        // …in a full-width lazy strip…
        assertTrue(bar.contains("fillMaxWidth"))
        assertTrue(bar.contains("LazyRow"))
        // …that tracks the selection, so a tab picked through search
        // (Models/Pairing live mid-strip) scrolls itself into view instead
        // of leaving the strip parked at the wrong end.
        assertTrue(bar.contains("scrollToItem"))
        assertTrue(bar.contains("indexOfFirst"))
    }

    @Test
    fun wideNavListScrollsTheFullTabColumn() {
        val screen = source(
            "src/main/java/com/xiufeigo/opencodeshell/ui/settings/SettingsScreen.kt",
        )
        val list = window(screen, "private fun SettingsNavList", "private fun SettingsNavTopBar")
        // The 14-tab column (~540dp) overflows short dialogs, so it must
        // scroll vertically instead of clipping the trailing tabs.
        assertTrue(list.contains("for (section in SETTINGS_NAV)"))
        assertTrue(list.contains("verticalScroll"))
    }

    @Test
    fun everyTabHasAStripPositionInBothBranches() {
        // Both branches consume the same inventory in the same order, so each
        // tab resolves to a valid scroll target / list position.
        SettingsTab.entries.forEach { tab ->
            val index = SETTINGS_NAV_TABS.indexOf(tab)
            assertTrue("$tab has no strip position", index >= 0)
            assertEquals(tab, SETTINGS_NAV_TABS[index])
        }
    }
}
