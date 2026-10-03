package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.settings.ModelVisibility
import com.xiufeigo.opencodeshell.ui.settings.connectedModelCount
import com.xiufeigo.opencodeshell.ui.settings.filterModelRows
import com.xiufeigo.opencodeshell.ui.settings.modelRows
import com.xiufeigo.opencodeshell.ui.settings.visibleModelCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Models tab v2 divergence (see `ModelRow`): the v2 backend reports
 * `connections: []` for every provider (`GET /api/integration`), so the old
 * connected-only filter emptied the whole tab. `modelRows` lists every
 * cataloged model with `connected` as a display-only flag, and the count uses
 * the full caliber.
 */
class SettingsModelsUnconnectedTest {

    private fun model(id: String, name: String = id) =
        OpenCodeClient.Providers.Model(
            id = id,
            name = name,
            capabilities = null,
        )

    private fun catalog(connected: List<String>) = OpenCodeClient.Providers(
        all = listOf(
            OpenCodeClient.Providers.Provider(
                id = "zeta",
                name = "Zeta",
                models = listOf(model("z1"), model("z2")),
            ),
            OpenCodeClient.Providers.Provider(
                id = "alpha",
                name = "Alpha",
                models = listOf(model("a1")),
            ),
        ),
        connected = connected,
        default = emptyMap(),
    )

    @Test
    fun emptyConnectedStillListsEveryModelAsUnconnected() {
        val rows = modelRows(catalog(connected = emptyList()), selected = null)
        assertEquals(listOf("zeta/z1", "zeta/z2", "alpha/a1"), rows.map { it.ref })
        assertTrue(rows.none { it.connected })
        // Visibility defaults stay on: the switches have something to drive.
        assertTrue(rows.all { it.visible })
    }

    @Test
    fun mixedConnectedFlagsRowsWithoutHiding() {
        val rows = modelRows(catalog(connected = listOf("alpha")), selected = null)
        assertEquals(3, rows.size)
        assertEquals(listOf(false, false, true), rows.map { it.connected })
    }

    @Test
    fun countUsesFullCaliberAndNullIsZero() {
        assertEquals(3, connectedModelCount(catalog(connected = emptyList())))
        assertEquals(3, connectedModelCount(catalog(connected = listOf("zeta"))))
        assertEquals(0, connectedModelCount(null))
        assertTrue(modelRows(null, selected = null).isEmpty())
    }

    @Test
    fun visibilitySwitchesApplyToUnconnectedRows() {
        val providers = catalog(connected = emptyList())
        val hidden = ModelVisibility.setVisible(emptyMap(), "zeta", "z1", false)
        val rows = modelRows(providers, selected = null, visibility = hidden)
        assertEquals(3, rows.size)
        assertFalse(rows.first { it.id == "z1" }.visible)
        assertEquals(2, visibleModelCount(providers, hidden))
        assertEquals(3, visibleModelCount(providers, emptyMap()))
    }

    @Test
    fun searchCoversUnconnectedRows() {
        val rows = modelRows(catalog(connected = emptyList()), selected = null)
        assertEquals(listOf("alpha/a1"), filterModelRows(rows, "alpha").map { it.ref })
        assertEquals(3, filterModelRows(rows, "").size)
    }
}
