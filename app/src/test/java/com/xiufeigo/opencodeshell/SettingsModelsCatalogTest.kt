package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.composer.ModelPickerItems
import com.xiufeigo.opencodeshell.ui.settings.ModelVisibility
import com.xiufeigo.opencodeshell.ui.settings.modelRows
import com.xiufeigo.opencodeshell.ui.settings.sortedModelGroups
import com.xiufeigo.opencodeshell.ui.settings.visibleModelCount
import com.xiufeigo.opencodeshell.v2.V2Api
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0-D: the v2 backend (`GET /api/provider` + `GET /api/model` +
 * `GET /api/integration`, measured 2026-09-24 on 4095: 10 providers, 132
 * models, `connections: []` everywhere) must reach the Models tab as the full
 * provider-grouped catalog — the same offer set the composer picker shows.
 *
 * The JSON below mirrors the real wire keys (`providerID` on models,
 * `connections` arrays on integrations); parsing runs through the real
 * [V2Api] companions, the real [v2ConnectedProviderIds] predicate and the real
 * `OpenCodeClient.Providers.Model.fromV2` fold — the join mirrors
 * `ChatViewModel.refreshV2Providers` (`models.filter { it.providerID == p.id }`).
 * A future parse/join change that drops the models again fails here instead of
 * shipping an empty tab.
 */
class SettingsModelsCatalogTest {

    private val providerJson = listOf(
        """
        {"id":"opencode-go","integrationID":"opencode-go","name":"OpenCode Go",
         "activation":"enabled","package":"@opencode/ai/providers/openai-compatible"}
        """.trimIndent(),
        """
        {"id":"opencode","integrationID":"opencode","name":"OpenCode Zen",
         "activation":"enabled","package":"@opencode/ai/providers/openai-compatible"}
        """.trimIndent(),
        """
        {"id":"xiaomi","integrationID":"xiaomi","name":"Xiaomi",
         "activation":"enabled","package":"@opencode/ai/providers/openai-compatible"}
        """.trimIndent(),
    )

    private val modelJson = listOf(
        """
        {"id":"space-bunny-free","modelID":"space-bunny-free","providerID":"opencode-go",
         "name":"Space Bunny Free","capabilities":{"tools":true,"input":["text"],"output":["text"]},
         "variants":[{"id":"high","settings":{}}],"status":"active","enabled":true,
         "limit":{"context":1048576,"input":524288,"output":524288}}
        """.trimIndent(),
        """
        {"id":"glm-5.3","modelID":"glm-5.3","providerID":"opencode-go",
         "name":"GLM-5.3","capabilities":{"tools":true},"status":"active","enabled":true,
         "limit":{"context":262144,"input":262144,"output":131072}}
        """.trimIndent(),
        """
        {"id":"space-bunny-free","modelID":"space-bunny-free","providerID":"opencode",
         "name":"Space Bunny Free","status":"active","enabled":true,
         "limit":{"context":1048576,"input":524288,"output":524288}}
        """.trimIndent(),
        """
        {"id":"kimi-k2.6","modelID":"kimi-k2.6","providerID":"xiaomi",
         "name":"Kimi K2.6","status":"active","enabled":true,
         "limit":{"context":262144,"input":262144,"output":131072}}
        """.trimIndent(),
    )

    private val integrationJson = listOf(
        """{"id":"opencode-go","name":"OpenCode Go","methods":[{"type":"oauth"}],"connections":[]}""",
        """{"id":"opencode","name":"OpenCode Zen","methods":[{"type":"oauth"}],"connections":[]}""",
        """{"id":"xiaomi","name":"Xiaomi","methods":[{"type":"apiKey"}],"connections":[]}""",
    )

    /** The catalog exactly as the v2 refresh folds it (`refreshV2Providers`). */
    private fun v2Catalog(): OpenCodeClient.Providers {
        val providers = providerJson.map { V2Api.ProviderInfo.fromJson(JSONObject(it)) }
        val models = modelJson.map { V2Api.ModelInfo.fromJson(JSONObject(it)) }
        val integrations = integrationJson.map { V2Api.IntegrationInfo.fromJson(JSONObject(it)) }
        val connectedIds = v2ConnectedProviderIds(providers, integrations)
        val all = providers.map { p ->
            OpenCodeClient.Providers.Provider(
                id = p.id,
                name = p.name,
                // The production fold: `Model.Info` → `Providers.Model`, which
                // carries `limit.context` / `limit.input` / `limit.output` and the
                // entry-level id off the same row.
                models = models.filter { it.providerID == p.id }
                    .map(OpenCodeClient.Providers.Model::fromV2),
            )
        }
        return OpenCodeClient.Providers(
            all = all,
            connected = all.filter { it.id in connectedIds }.map { it.id },
            default = emptyMap(),
        )
    }

    @Test
    fun zeroConnectionsStillYieldsEveryModel() {
        val catalog = v2Catalog()
        assertTrue(catalog.connected.isEmpty())
        val rows = modelRows(catalog, selected = null)
        // The join must not drop anything: 2 + 1 + 1, provider order kept.
        assertEquals(
            listOf(
                "opencode-go/space-bunny-free",
                "opencode-go/glm-5.3",
                "opencode/space-bunny-free",
                "xiaomi/kimi-k2.6",
            ),
            rows.map { it.ref },
        )
        assertTrue(rows.none { it.connected })
        assertTrue(rows.all { it.visible })
    }

    @Test
    fun rowsGroupByProviderWithContextLimits() {
        val rows = modelRows(v2Catalog(), selected = null)
        val groups = sortedModelGroups(rows)
        assertEquals(listOf("opencode-go", "opencode", "xiaomi"), groups.map { it.providerID })
        assertEquals(listOf(2, 1, 1), groups.map { it.rows.size })
        val bunny = rows.first { it.id == "space-bunny-free" }
        assertEquals(1048576, bunny.limitContext)
        // v20: the Models tab prints the **effective window**
        // `min(limit.input, limit.context)` — the same denominator as the ring
        // and the context tab — so the row carries both halves off the wire row.
        assertEquals(524288, bunny.limitInput)
        assertEquals(524288, bunny.window)
        // An entry whose two halves are equal is unchanged by the `min`.
        assertEquals(262144, rows.first { it.id == "glm-5.3" }.window)
    }

    @Test
    fun visibilityToggleLinksModelsTabAndPicker() {
        val catalog = v2Catalog()
        val hidden = ModelVisibility.setVisible(emptyMap(), "opencode-go", "space-bunny-free", false)
        val rows = modelRows(catalog, selected = null, visibility = hidden)
        // Same size (the tab keeps the row with its Switch off)…
        assertEquals(4, rows.size)
        assertEquals(3, visibleModelCount(catalog, hidden))
        // …and the picker offer equals exactly the tab's visible set.
        val pickerRefs = ModelPickerItems
            .groups(catalog, hidden, includeUnconnected = true)
            .flatMap { group -> group.models.map { "${group.providerID}/${it.id}" } }
            .toSet()
        assertEquals(rows.filter { it.visible }.map { it.ref }.toSet(), pickerRefs)
    }
}
