package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.settings.ProviderWriteResult
import com.xiufeigo.opencodeshell.ui.settings.ProvidersCatalogState
import com.xiufeigo.opencodeshell.ui.settings.integrationConnectionTypes
import com.xiufeigo.opencodeshell.ui.settings.providersCatalogState
import com.xiufeigo.opencodeshell.ui.settings.writeFailureReason
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Providers tab's terminal-state rules: `Loading` expires (or is skipped)
 * instead of spinning forever when the catalog flow never emits, and the
 * integration snapshot feeds `canDisconnect` real **connection** types —
 * `methods[].type` is a different union and used to hide every Disconnect.
 */
class SettingsProvidersTerminalStateTest {

    private fun providers(vararg ids: String) = OpenCodeClient.Providers(
        all = ids.map {
            OpenCodeClient.Providers.Provider(id = it, name = it, models = emptyList())
        },
        connected = ids.toList(),
        default = emptyMap(),
    )

    @Test
    fun missingFlowIsTerminalNotLoading() {
        assertEquals(
            ProvidersCatalogState.Missing,
            providersCatalogState(hasCatalogFlow = false, providers = null, settled = false),
        )
    }

    @Test
    fun openDeadlineWithSilentFlowIsStillLoading() {
        assertEquals(
            ProvidersCatalogState.Loading,
            providersCatalogState(hasCatalogFlow = true, providers = null, settled = false),
        )
    }

    @Test
    fun pastTheDeadlineLoadingExpiresIntoTimedOut() {
        assertEquals(
            ProvidersCatalogState.TimedOut,
            providersCatalogState(hasCatalogFlow = true, providers = null, settled = true),
        )
    }

    @Test
    fun arrivedEmptyCatalogIsTerminalEvenBeforeTheDeadline() {
        assertEquals(
            ProvidersCatalogState.Empty,
            providersCatalogState(hasCatalogFlow = true, providers = providers(), settled = false),
        )
    }

    @Test
    fun arrivedCatalogReportsConnectedAndPopularCounts() {
        val state = providersCatalogState(
            hasCatalogFlow = true,
            providers = OpenCodeClient.Providers(
                all = listOf(
                    OpenCodeClient.Providers.Provider(id = "a", name = "a", models = emptyList()),
                    OpenCodeClient.Providers.Provider(id = "b", name = "b", models = emptyList()),
                    OpenCodeClient.Providers.Provider(id = "c", name = "c", models = emptyList()),
                ),
                connected = listOf("a", "b"),
                default = emptyMap(),
            ),
            settled = true,
        )
        assertEquals(ProvidersCatalogState.Content(connected = 2, popular = 1), state)
    }

    @Test
    fun connectionTypesComeFromConnectionsNotMethods() {
        val raw = JSONObject(
            """
            {
              "id": "anthropic",
              "methods": [{"type": "key", "label": "API key"}],
              "connections": [
                {"type": "credential", "id": "cred_1", "label": "API key"},
                {"type": "env", "name": "ANTHROPIC_API_KEY"}
              ]
            }
            """.trimIndent(),
        )
        assertEquals(listOf("credential", "env"), integrationConnectionTypes(raw))
    }

    @Test
    fun connectionlessIntegrationReportsNoConnectionTypes() {
        val raw = JSONObject("""{"id": "opencode", "methods": [{"type": "env"}], "connections": []}""")
        assertEquals(emptyList<String>(), integrationConnectionTypes(raw))
    }

    @Test
    fun writeFailureReasonMapsEveryOutcome() {
        assertNull(writeFailureReason(ProviderWriteResult.Done))
        assertEquals("unsupported", writeFailureReason(ProviderWriteResult.Unsupported))
        assertEquals("no-credentials", writeFailureReason(ProviderWriteResult.Failed("no-credentials")))
        assertEquals("http-404", writeFailureReason(ProviderWriteResult.Failed("http-404")))
    }
}
