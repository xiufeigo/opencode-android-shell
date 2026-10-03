package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.composer.ModelPickerItems
import com.xiufeigo.opencodeshell.ui.settings.filterModelRows
import com.xiufeigo.opencodeshell.ui.settings.modelRows
import com.xiufeigo.opencodeshell.ui.settings.sortedModelGroups
import com.xiufeigo.opencodeshell.v2.V2Api
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v7 follow-up: the live v2 backend (measured on 4095: `GET /api/provider` 10
 * providers, `GET /api/model` 132 models, `GET /api/integration`
 * `connections: []` everywhere) must surface **every** provider in the
 * composer's default picker view and on the Models tab — including the six
 * providers a device pass reported missing (xiaomi, stepfun, zai-coding-plan,
 * minimax-cn-coding-plan, antigravity, zz1cc).
 *
 * The fixture mirrors the real wire: the exact 10 provider ids and the exact
 * per-provider model counts (2 + 5 + 7 + 55 + 8 + 32 + 9 + 6 + 7 + 1 = 132),
 * parsed through the real [V2Api] companions and joined exactly like
 * `OpenCodeClient.providers` (`models.filter { it.providerID == p.id }`), with
 * an empty connected set. Any future filter/join change that drops a provider
 * fails here instead of shipping a partial picker.
 */
class ModelPickerFullCatalogTest {

    /** provider id → (display name, live model count). */
    private val providers = listOf(
        "antigravity" to ("Antigravity" to 2),
        "commandcodegoat" to ("Command Code" to 5),
        "minimax-cn-coding-plan" to ("MiniMax Token Plan" to 7),
        "openai" to ("OpenAI" to 55),
        "opencode" to ("OpenCode Zen" to 8),
        "opencode-go" to ("OpenCode Go" to 32),
        "stepfun" to ("StepFun" to 9),
        "xiaomi" to ("Xiaomi" to 6),
        "zai-coding-plan" to ("Z.AI Coding Plan" to 7),
        "zz1cc" to ("ZZ1CC Relay" to 1),
    )

    /** The catalog exactly as the v2 refresh folds it, with nobody connected. */
    private fun fullCatalog(): OpenCodeClient.Providers {
        val providerInfos = providers.map { (id, named) ->
            V2Api.ProviderInfo.fromJson(
                JSONObject(
                    """{"id":"$id","integrationID":"$id","name":"${named.first}","activation":"enabled"}""",
                ),
            )
        }
        val modelInfos = providers.flatMap { (id, named) ->
            (0 until named.second).map { index ->
                V2Api.ModelInfo.fromJson(
                    JSONObject(
                        """{"id":"$id-model-$index","modelID":"$id-model-$index","providerID":"$id","name":"${named.first} Model $index","status":"active","enabled":true}""",
                    ),
                )
            }
        }
        val all = providerInfos.map { p ->
            OpenCodeClient.Providers.Provider(
                id = p.id,
                name = p.name,
                models = modelInfos.filter { it.providerID == p.id }
                    .map(OpenCodeClient.Providers.Model::fromV2),
            )
        }
        // `connections: []` everywhere on the live backend.
        return OpenCodeClient.Providers(all = all, connected = emptyList(), default = emptyMap())
    }

    @Test
    fun pickerDefaultViewCoversEveryCatalogProvider() {
        val catalog = fullCatalog()
        assertTrue(catalog.connected.isEmpty())
        // The production call: full list, `connected` only marks rows.
        val groups = ModelPickerItems.groups(catalog, emptyMap(), includeUnconnected = true)
        assertEquals(
            listOf(
                "opencode-go",
                "opencode",
                "openai",
                "antigravity",
                "commandcodegoat",
                "minimax-cn-coding-plan",
                "stepfun",
                "xiaomi",
                "zai-coding-plan",
                "zz1cc",
            ),
            groups.map { it.providerID },
        )
        assertEquals(132, groups.sumOf { it.models.size })
        assertEquals(
            mapOf(
                "antigravity" to 2,
                "commandcodegoat" to 5,
                "minimax-cn-coding-plan" to 7,
                "openai" to 55,
                "opencode" to 8,
                "opencode-go" to 32,
                "stepfun" to 9,
                "xiaomi" to 6,
                "zai-coding-plan" to 7,
                "zz1cc" to 1,
            ),
            groups.associate { it.providerID to it.models.size },
        )
        // Empty connected set flags every group without hiding any of them.
        assertTrue(groups.none { it.connected })
    }

    @Test
    fun modelsTabGroupsCoverEveryCatalogProvider() {
        val catalog = fullCatalog()
        val rows = modelRows(catalog, selected = null)
        assertEquals(132, rows.size)
        assertTrue(rows.all { it.visible })
        val groups = sortedModelGroups(rows)
        assertEquals(10, groups.size)
        assertEquals(
            setOf(
                "antigravity",
                "commandcodegoat",
                "minimax-cn-coding-plan",
                "openai",
                "opencode",
                "opencode-go",
                "stepfun",
                "xiaomi",
                "zai-coding-plan",
                "zz1cc",
            ),
            groups.map { it.providerID }.toSet(),
        )
        assertEquals(132, groups.sumOf { it.rows.size })
    }

    @Test
    fun searchReachesTheReportedMissingProviders() {
        val catalog = fullCatalog()
        val groups = ModelPickerItems.groups(catalog, emptyMap(), includeUnconnected = true)
        for (id in listOf("xiaomi", "stepfun", "zai-coding-plan", "minimax-cn-coding-plan", "antigravity", "zz1cc")) {
            assertTrue(
                "picker search misses $id",
                ModelPickerItems.filter(groups, id).any { it.providerID == id },
            )
        }
        val rows = modelRows(catalog, selected = null)
        for (id in listOf("xiaomi", "stepfun", "antigravity", "zz1cc")) {
            assertTrue(
                "models-tab search misses $id",
                filterModelRows(rows, id).any { it.providerID == id },
            )
        }
    }
}
