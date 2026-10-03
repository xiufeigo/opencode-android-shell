package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.settings.ExperimentalRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Message-revert button chrome + the Experimental tab's honest row projection
 * (`settings/experimental/experimental.tsx`).
 */
class MessageRevertRenderTest {

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText()
    }

    @Test
    fun revertActionIsAGhostIconButtonWithTheUpstreamLabel() {
        val body = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/actions/MessageRevertAction.kt")
        // `message-content.tsx:328-338`: 24 dp target, `reset` glyph, labelled
        // `ui.message.revertMessage`, disabled while reverting.
        assertTrue(body.contains("R.string.oc_meta_revert_message"))
        assertTrue(body.contains(".size(24.dp)"))
        assertTrue(body.contains("messageID.isNotBlank() && !busy"))
        assertTrue(body.contains("onRevert(messageID)"))
    }

    @Test
    fun experimentalShowsTheTwoUnwiredRowsAndHidesTheGatedOnes() {
        assertEquals(listOf(ExperimentalRow.TAB_LAYOUT, ExperimentalRow.PROJECT_NAME), ExperimentalRow.visible())
        assertEquals(
            listOf("settings-browser-pane", "settings-show-project-icon"),
            ExperimentalRow.hiddenActions,
        )
    }

    @Test
    fun experimentalPanelRendersRowsDisabledWithAReason() {
        val body = source("src/main/java/com/xiufeigo/opencodeshell/ui/settings/SettingsPanels.kt")
            .substringAfter("fun ExperimentalPanel(")
        assertTrue(body.contains("ExperimentalDisabledRow("))
        assertTrue(body.contains("R.string.oc_settings_experimental_tabs"))
        assertTrue(body.contains("R.string.oc_settings_experimental_project_name"))
        assertTrue(body.contains("oc_settings_experimental_unavailable"))
    }

    @Test
    fun shellPanelConsumesAndWritesTheServerShell() {
        val body = source("src/main/java/com/xiufeigo/opencodeshell/ui/settings/SettingsPanels.kt")
            .substringAfter("fun ShellPanel(")
            .substringBefore("// ----------------------------------------------------------- experimental tab")
        assertTrue(body.contains("source.serverShell()"))
        assertTrue(body.contains("R.string.oc_settings_shell_unsupported"))
        // Write half: Save/Clear go through the server write API, and
        // `Unsupported` is surfaced instead of swallowed.
        assertTrue(body.contains("setServerShell("))
        assertTrue(body.contains("R.string.oc_settings_shell_write_unsupported"))
    }
}
