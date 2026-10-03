package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.summary.SessionSummaryModel.ServiceItem
import com.xiufeigo.opencodeshell.ui.session.summary.SummaryCatalogs
import com.xiufeigo.opencodeshell.ui.session.summary.SummaryServiceSource
import com.xiufeigo.opencodeshell.ui.settings.McpPageRow
import com.xiufeigo.opencodeshell.ui.settings.PluginPageRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure projections behind the summary server card's four submenus
 * (`session/summary/server-panel.tsx` `ServiceCatalog` over `GET /api/mcp`,
 * `/api/plugin`, `/api/skill`, `/api/config`).
 */
class SummaryServiceSourceTest {

    @Test
    fun mcpItemsSortByNameAndCarryStatusTokens() {
        val rows = listOf(
            McpPageRow("zeta", "connected", null),
            McpPageRow("alpha", "failed", "boom"),
            McpPageRow("Beta", "pending", null),
        )
        val items = SummaryServiceSource.mcpItems(rows)
        assertEquals(listOf("alpha", "Beta", "zeta"), items.map { it.name })
        assertEquals(listOf("failed", "connecting", null), items.map { it.status })
    }

    @Test
    fun statusTokenMapsOnlyTheUpstreamStatusLabelStates() {
        assertEquals("failed", SummaryServiceSource.statusToken("failed"))
        assertEquals("connecting", SummaryServiceSource.statusToken("pending"))
        assertEquals("connecting", SummaryServiceSource.statusToken("Connecting"))
        assertEquals("auth", SummaryServiceSource.statusToken("needs-auth"))
        assertEquals("auth", SummaryServiceSource.statusToken("unauthorized"))
        // Connected/quiet rows show no label (upstream paints a Switch instead).
        assertNull(SummaryServiceSource.statusToken("connected"))
        assertNull(SummaryServiceSource.statusToken("disabled"))
        assertNull(SummaryServiceSource.statusToken(null))
        assertNull(SummaryServiceSource.statusToken("something-else"))
    }

    @Test
    fun pluginNamesProjectRowsToPlainNames() {
        assertEquals(
            listOf("prettier", "typescript-eslint"),
            SummaryServiceSource.pluginNames(listOf(PluginPageRow("prettier"), PluginPageRow("typescript-eslint"))),
        )
        assertEquals(emptyList<String>(), SummaryServiceSource.pluginNames(emptyList()))
    }

    @Test
    fun loadWithoutAnApiIsTheHonestUnavailableState() {
        assertEquals(SummaryCatalogs.Unavailable, SummaryServiceSource.load(null, "/w"))
        assertNull(SummaryCatalogs.Unavailable.mcp)
        assertNull(SummaryCatalogs.Unavailable.plugins)
        assertNull(SummaryCatalogs.Unavailable.skills)
        assertNull(SummaryCatalogs.Unavailable.configJson)
    }

    @Test
    fun mcpItemsKeepUpstreamSortAndDropNothing() {
        val items = SummaryServiceSource.mcpItems(listOf(McpPageRow("b", "connected", null), McpPageRow("A", "connected", null)))
        // W4/F-3: the row also carries the verbatim status — the MCP `Switch`'s
        // position — while the localized token stays null for a connected server.
        assertEquals(
            listOf(ServiceItem("A", rawStatus = "connected"), ServiceItem("b", rawStatus = "connected")),
            items,
        )
    }
}
