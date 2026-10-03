package com.xiufeigo.opencodeshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The context tab scrolls as one LazyColumn (stats, prompt, instructions,
 * raw messages, export). The instructions section must not bring its own
 * capped inner scroller: on short viewports the inner box fragments the tab
 * into competing scrollers and strands the rows below it (the export button
 * with Sanitized export / Import, the late stat rows).
 */
class SessionContextPanelScrollTest {

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText()
    }

    private fun panel() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/context/SessionContextPanel.kt")

    private fun instructions() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/context/SessionInstructionsSection.kt")

    @Test
    fun tabBodyIsOneLazyColumn() {
        val body = panel()
        assertTrue(body.contains("LazyColumn("))
        assertTrue(body.contains("Modifier.fillMaxSize()"))
        assertTrue(body.contains("items(statRows)"))
        assertTrue(body.contains("ExportSessionButton"))
    }

    @Test
    fun noNestedScrollerInTheTab() {
        assertEquals(0, Regex("verticalScroll\\(").findAll(panel()).count())
        assertEquals(0, Regex("verticalScroll\\(").findAll(instructions()).count())
        assertTrue(instructions().contains("INSTRUCTIONS_MAX_HEIGHT").not())
    }

    @Test
    fun instructionsRowsStillRender() {
        val body = instructions()
        assertTrue(body.contains("InstructionEntryRow("))
        assertTrue(body.contains("InstructionAddRow("))
        assertTrue(body.contains("R.string.oc_session_instructions_empty"))
    }
}
