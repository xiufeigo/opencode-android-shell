package com.xiufeigo.opencodeshell

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source pins for the summary sub-panels inside `@Composable` functions:
 * background-tasks + the server card's MCP / Plugins / Skills / LSP submenus,
 * each with upstream's empty copy.
 */
class SummaryPanelRenderTest {

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText()
    }

    private fun panel() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/summary/SessionSummaryPanel.kt")

    @Test
    fun projectCardCarriesTheBackgroundTasksSubmenu() {
        val body = panel()
        assertTrue(body.contains("BackgroundTasksSection("))
        assertTrue(body.contains("R.string.oc_summary_background_empty"))
    }

    @Test
    fun serverCardCarriesAllFourServiceSubmenus() {
        val body = panel().substringAfter("private fun ServiceSubmenus(").substringBefore("private fun ServiceSubmenusOf(")
        for (call in listOf(
            "R.string.oc_summary_mcp",
            "R.string.oc_summary_plugins",
            "R.string.oc_summary_skills",
            "R.string.oc_summary_lsp",
        )) {
            assertTrue("$call submenu must render", body.contains(call))
        }
    }

    @Test
    fun everyServiceSubmenuHasUpstreamEmptyCopy() {
        val body = panel()
        for (empty in listOf(
            "R.string.oc_summary_mcp_empty",
            "R.string.oc_summary_plugins_empty",
            "R.string.oc_summary_skills_empty",
            "R.string.oc_summary_lsp_empty",
        )) {
            assertTrue("$empty must render", body.contains(empty))
        }
    }

    @Test
    fun lspNamesDeriveFromConfigJsonWhenNotSupplied() {
        val body = panel()
        assertTrue(body.contains("SessionSummaryModel.configuredLsps(configJson)"))
    }
}
