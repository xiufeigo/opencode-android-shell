package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.summary.SessionSummaryModel
import com.xiufeigo.opencodeshell.ui.session.summary.SessionSummaryModel.BackgroundTask
import com.xiufeigo.opencodeshell.ui.session.summary.SessionSummaryModel.ServiceItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The summary sub-panels' pure rules: `session/summary/background.tsx`
 * (task rows), `session/summary/configured-lsp.ts` (the LSP fold over
 * `GET /api/config`) and `session/summary/server-panel.tsx:4-9` (service
 * submenu rows).
 */
class SummaryServicesTest {

    // ------------------------------------------------------------- background

    @Test
    fun backgroundTasksCapAtTenAndKeepOrder() {
        val tasks = (1..12).map { BackgroundTask("shell", "cmd $it") }
        val capped = SessionSummaryModel.backgroundTasks(tasks)
        assertEquals(10, capped.size)
        assertEquals("cmd 1", capped.first().label)
        assertEquals("cmd 10", capped.last().label)
        // `null` = no task model; `emptyList()` = honestly none. Both render the
        // empty line, neither invents rows.
        assertTrue(SessionSummaryModel.backgroundTasks(null).isEmpty())
        assertTrue(SessionSummaryModel.backgroundTasks(emptyList()).isEmpty())
    }

    @Test
    fun backgroundTypeLabelsMatchUpstream() {
        assertEquals("Shell", BackgroundTask("shell", "ls").typeLabel)
        assertEquals("Agent", BackgroundTask("subagent", "x").typeLabel)
        assertEquals("Agent", BackgroundTask("subagent", "x", agent = "").typeLabel)
        assertEquals("Plan", BackgroundTask("subagent", "x", agent = "plan").typeLabel)
        assertEquals("Explorer", BackgroundTask("subagent", "x", agent = "explorer").typeLabel)
    }

    // ------------------------------------------------------- service catalogs

    @Test
    fun serviceItemsSortByNameIgnoringCase() {
        val rows = SessionSummaryModel.serviceItems(
            listOf(ServiceItem("zeta"), ServiceItem("alpha"), ServiceItem("Beta")),
        )
        assertEquals(listOf("alpha", "Beta", "zeta"), rows.map { it.name })
        assertTrue(SessionSummaryModel.serviceItems(null).isEmpty())
    }

    @Test
    fun nameItemsWrapPlainNames() {
        val rows = SessionSummaryModel.nameItems(listOf("typescript-eslint", "prettier"))
        assertEquals(listOf("prettier", "typescript-eslint"), rows.map { it.name })
        assertTrue(SessionSummaryModel.nameItems(null).all { it.status == null })
    }

    // -------------------------------------------------------- configured LSPs

    @Test
    fun configuredLspsCollectsEnabledServersAcrossEntries() {
        val config = """
            [
              {"type":"document","info":{"lsp":{"typescript":{"command":["tsserver"]}}}},
              {"type":"directory","path":"C:\\work"},
              {"type":"document","info":{"lsp":{"pyright":{},"ruff":{"disabled":true}}}}
            ]
        """.trimIndent()
        // `ruff` is configured but disabled; the sort is case-insensitive.
        assertEquals(listOf("pyright", "typescript"), SessionSummaryModel.configuredLsps(config))
    }

    @Test
    fun aBooleanLspResetsTheAccumulatedNames() {
        val config = """
            [
              {"type":"document","info":{"lsp":{"typescript":{}}}},
              {"type":"document","info":{"lsp":false}},
              {"type":"document","info":{"lsp":{"gopls":{}}}}
            ]
        """.trimIndent()
        assertEquals(listOf("gopls"), SessionSummaryModel.configuredLsps(config))
    }

    @Test
    fun aMapEntryOverridesOnlyTheNamesItMentions() {
        val config = """
            [
              {"type":"document","info":{"lsp":{"a":{},"b":{}}}},
              {"type":"document","info":{"lsp":{"b":{"disabled":true},"c":{}}}}
            ]
        """.trimIndent()
        assertEquals(listOf("a", "c"), SessionSummaryModel.configuredLsps(config))
    }

    @Test
    fun configuredLspsToleratesMissingAndMalformedConfig() {
        assertTrue(SessionSummaryModel.configuredLsps(null).isEmpty())
        assertTrue(SessionSummaryModel.configuredLsps("").isEmpty())
        assertTrue(SessionSummaryModel.configuredLsps("not json").isEmpty())
        // The `{entries}` envelope works like `serverShellInfo` accepts.
        val wrapped = """{"entries":[{"type":"document","info":{"lsp":{"gopls":{}}}}]}"""
        assertEquals(listOf("gopls"), SessionSummaryModel.configuredLsps(wrapped))
    }
}
