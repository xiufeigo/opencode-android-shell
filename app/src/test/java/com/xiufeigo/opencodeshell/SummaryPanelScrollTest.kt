package com.xiufeigo.opencodeshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The summary panel must scroll inside the host's bounds: the phone view
 * mounts it in a bounded box, so without a scroller the websearch dock body,
 * the skill activate rows and the close row below the fold are clipped with
 * no way to reach them. The collapse toggles must keep working exactly as
 * before — scrolling changes reachability, not structure.
 */
class SummaryPanelScrollTest {

    private fun panel(): String {
        val relative = "src/main/java/com/xiufeigo/opencodeshell/ui/session/summary/SessionSummaryPanel.kt"
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText()
    }

    @Test
    fun panelRootScrolls() {
        assertTrue(panel().contains("verticalScroll(rememberScrollState())"))
    }

    @Test
    fun panelRootIsTheOnlyScrollerInTheFile() {
        assertEquals(
            1,
            Regex("verticalScroll\\(").findAll(panel()).count(),
        )
    }

    @Test
    fun collapseAffordancesAreUntouched() {
        val body = panel()
        assertTrue(body.contains("projectExpanded"))
        assertTrue(body.contains("websearchExpanded"))
        assertTrue(body.contains("SessionWebSearchRunDock("))
        assertTrue(body.contains("ServiceSubmenus("))
        assertTrue(body.contains("R.string.oc_summary_skill_activate"))
        assertTrue(body.contains("R.string.oc_summary_close"))
    }
}
