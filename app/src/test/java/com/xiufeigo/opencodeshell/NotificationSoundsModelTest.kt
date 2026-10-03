package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.settings.SOUNDS_PANEL
import com.xiufeigo.opencodeshell.ui.settings.SOUND_OPTIONS
import com.xiufeigo.opencodeshell.ui.settings.SettingsControl
import com.xiufeigo.opencodeshell.ui.settings.stringValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Notifications page's `Sound effects` section
 * (`settings/notifications/notifications.tsx:108-126` `SoundSetting` × 3):
 * structure, persistence contract and the source pins that keep the panel
 * rendering both upstream sections.
 */
class NotificationSoundsModelTest {

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText()
    }

    private val actions = listOf(
        "settings-sounds-agent",
        "settings-sounds-permissions",
        "settings-sounds-errors",
    )

    @Test
    fun soundsPanelIsUpstreamsThreeSoundSelects() {
        val section = SOUNDS_PANEL.single()
        assertEquals(R.string.oc_setting_section_sounds, section.titleResId)
        assertEquals(actions, section.rows.map { it.action })
        assertTrue(section.rows.all { it.control == SettingsControl.SELECT })
    }

    @Test
    fun everySoundRowOffersThePersistableStops() {
        assertEquals(
            listOf("none", "default"),
            SOUND_OPTIONS.map { it.value },
        )
        SOUNDS_PANEL.flatMap { it.rows }.forEach { row ->
            assertEquals(SOUND_OPTIONS, row.options)
            // A stop the store whitelist drops would snap back on the next read
            // (`SettingsStore.readEnum` + `update`), so the option list must be
            // a subset of the accepted set.
            assertTrue(
                "${row.action} offers unpersistable stops",
                row.options.all { it.value in SettingsStore.SOUNDS },
            )
        }
    }

    @Test
    fun stringValueReadsTheThreeChannelsFromTheStore() {
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
            soundPermissions = "none",
            soundErrors = "default",
            showFileTree = true,
            showSearch = true,
            showStatus = true,
            showCustomAgents = true,
        )
        assertEquals("default", stringValue(settings, "settings-sounds-agent"))
        assertEquals("none", stringValue(settings, "settings-sounds-permissions"))
        assertEquals("default", stringValue(settings, "settings-sounds-errors"))
    }

    @Test
    fun notificationsPanelPaintsBothUpstreamSections() {
        val body = source("src/main/java/com/xiufeigo/opencodeshell/ui/settings/SettingsPanels.kt")
        assertTrue(body.contains("NOTIFICATIONS_PANEL + SOUNDS_PANEL"))
    }

    @Test
    fun playbackPreviewStaysAnExplicitTodo() {
        val body = source("src/main/java/com/xiufeigo/opencodeshell/ui/settings/SettingsModel.kt")
        assertTrue(body.contains("TODO(sound/preview)"))
    }
}
