package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.settings.ProviderConnectCatalog
import com.xiufeigo.opencodeshell.ui.settings.SettingsPanelExtras
import com.xiufeigo.opencodeshell.ui.settings.applyProviderConnectionDelta
import com.xiufeigo.opencodeshell.ui.settings.providerRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsProvidersRefreshTest {

    private fun providers(vararg connectedIds: String) =
        OpenCodeClient.Providers(
            all = listOf(
                OpenCodeClient.Providers.Provider("minimax", "MiniMax", emptyList()),
                OpenCodeClient.Providers.Provider("anthropic", "Anthropic", emptyList()),
                OpenCodeClient.Providers.Provider("openai", "OpenAI", emptyList()),
            ),
            connected = connectedIds.toList(),
            default = emptyMap(),
        )

    private fun headerCount(snapshot: OpenCodeClient.Providers, delta: Map<String, Boolean>): Int {
        val rows = ProviderConnectCatalog.providerSourceRows(
            applyProviderConnectionDelta(providerRows(snapshot), delta),
        )
        return rows.count { it.connected }
    }

    @Test
    fun extrasRefreshCallbackDefaultsToNull() {
        assertNull(SettingsPanelExtras().onProvidersChanged)
    }

    @Test
    fun disconnectDeltaDropsHeaderCount() {
        assertEquals(1, headerCount(providers("minimax", "anthropic"), mapOf("minimax" to false)))
    }

    @Test
    fun connectDeltaRaisesHeaderCount() {
        assertEquals(2, headerCount(providers("anthropic"), mapOf("openai" to true)))
    }

    @Test
    fun freshCatalogClearsTheDelta() {
        assertEquals(0, headerCount(providers(), emptyMap()))
    }
}
