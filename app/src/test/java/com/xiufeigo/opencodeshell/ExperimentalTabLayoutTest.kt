package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.nav.TabLayout
import com.xiufeigo.opencodeshell.ui.settings.ExperimentalRow
import com.xiufeigo.opencodeshell.ui.settings.TAB_LAYOUT_OPTIONS
import com.xiufeigo.opencodeshell.ui.settings.stringValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B-11: the Experimental page's Tabs row (`experimental.tsx:26-44`) writes
 * `appearance.tabLayout`, the one pref the nav layer already reads
 * (`AppearancePrefs` → `OcApp` → `BottomTabStrip`). The row used to be a
 * disabled placeholder even though that consumer existed.
 */
class ExperimentalTabLayoutTest {

    @Test
    fun theRowIsLiveAndItsOptionsMatchTheStore() {
        // The action the panel writes and the enum's id are the same string.
        assertEquals("settings-tab-layout", ExperimentalRow.TAB_LAYOUT.action)
        // The two stops are upstream's, in upstream's order.
        assertEquals(listOf("horizontal", "vertical"), TAB_LAYOUT_OPTIONS.map { it.value })
        // …and the store accepts exactly those two values.
        assertEquals(TAB_LAYOUT_OPTIONS.map { it.value }.toSet(), SettingsStore.TAB_LAYOUTS)
    }

    @Test
    fun theKeyAndTheDecoderAreSharedWithTheNavLayer() {
        // One key name, one decoder: the row's write is what `AppearancePrefs`
        // reads at launch.
        assertEquals(TabLayout.KEY, SettingsStore.KEY_TAB_LAYOUT)
        assertEquals("horizontal", SettingsStore.DEFAULT_TAB_LAYOUT)
        assertFalse(TabLayout.bottomStrip(null))
        assertFalse(TabLayout.bottomStrip("horizontal"))
        assertTrue(TabLayout.bottomStrip("vertical"))
        assertTrue(TabLayout.bottomStrip(" vertical "))
    }

    @Test
    fun stringValueReadsTheRowFromTheStoreSnapshot() {
        val settings = SettingsStore.Settings(
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
            tabLayout = "vertical",
        )
        assertEquals("vertical", stringValue(settings, "settings-tab-layout"))
        assertEquals("horizontal", stringValue(settings.copy(tabLayout = "horizontal"), "settings-tab-layout"))
    }

    @Test
    fun projectNameStaysTheOnlyDisabledRow() {
        // B-11 retired the tab-layout placeholder; `showProjectName` keeps its
        // disabled row (its upstream consumer is the vertical tab nav's project
        // line, `shell/titlebar/tab-nav.tsx:303`, and this shell's SessionTabRef
        // carries no project).
        assertEquals(
            listOf(ExperimentalRow.TAB_LAYOUT, ExperimentalRow.PROJECT_NAME),
            ExperimentalRow.visible(),
        )
        assertEquals(
            listOf("settings-browser-pane", "settings-show-project-icon"),
            ExperimentalRow.hiddenActions,
        )
    }
}
