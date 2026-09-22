package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.composer.ModelPickerItems
import com.xiufeigo.opencodeshell.ui.settings.ModelVisibility
import com.xiufeigo.opencodeshell.ui.settings.visibleModelCount
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * INTEGRATION WIRING: the ModelPicker honors the Models-tab visibility Switch
 * (`SettingsStore.modelVisibility()`), per wave2.3a notes §2 — the 3-line
 * consumer change in `ModelPicker.kt` (`ModelPickerItems.groups(providers,
 * visibility)` + the store read in `ModelPickerContent`).
 *
 * The unset rule lives in `ModelVisibility.isVisible` (visible unless hidden),
 * so an empty map keeps the old behavior; `visibleModelCount` is the same map
 * applied through `modelRows`, so the picker's offer and the panel's count
 * agree by construction (asserted below).
 */
class ModelPickerVisibilityTest {

    private fun providers(body: String) = OpenCodeClient.Providers.fromJson(JSONObject(body))

    private val catalog = providers(
        """
        {
          "all": [
            {
              "id": "openai",
              "name": "OpenAI",
              "models": {
                "gpt-5": {"id": "gpt-5", "name": "GPT-5.5"},
                "gpt-4o": {"id": "gpt-4o", "name": "GPT-4o"}
              }
            },
            {
              "id": "anthropic",
              "name": "Anthropic",
              "models": {
                "claude-sonnet-4": {"id": "claude-sonnet-4", "name": "Claude Sonnet 4"}
              }
            }
          ],
          "connected": ["openai", "anthropic"],
          "default": {"anthropic": "claude-sonnet-4"}
        }
        """.trimIndent(),
    )

    @Test
    fun emptyVisibilityKeepsOldBehavior() {
        val groups = ModelPickerItems.groups(catalog)
        assertEquals(3, groups.sumOf { it.models.size })
    }

    @Test
    fun hiddenModelNeverReachesPicker() {
        val visibility = ModelVisibility.setVisible(emptyMap(), "openai", "gpt-5", false)
        val groups = ModelPickerItems.groups(catalog, visibility)
        val openai = groups.first { it.providerID == "openai" }
        assertEquals(listOf("GPT-4o"), openai.models.map { it.name })
        // The untouched provider still offers everything.
        assertEquals(1, groups.first { it.providerID == "anthropic" }.models.size)
    }

    @Test
    fun fullyHiddenProviderDropsItsGroup() {
        var visibility = emptyMap<String, String>()
        visibility = ModelVisibility.setVisible(visibility, "openai", "gpt-5", false)
        visibility = ModelVisibility.setVisible(visibility, "openai", "gpt-4o", false)
        val groups = ModelPickerItems.groups(catalog, visibility)
        assertEquals(listOf("anthropic"), groups.map { it.providerID })
    }

    @Test
    fun explicitShowBeatsNothingToBeat() {
        val visibility = ModelVisibility.setVisible(emptyMap(), "anthropic", "claude-sonnet-4", true)
        val groups = ModelPickerItems.groups(catalog, visibility)
        assertEquals(3, groups.sumOf { it.models.size })
    }

    @Test
    fun pickerOfferMatchesVisibleModelCount() {
        val visibility = ModelVisibility.setVisible(emptyMap(), "openai", "gpt-5", false)
        val offered = ModelPickerItems.groups(catalog, visibility).sumOf { it.models.size }
        assertEquals(visibleModelCount(catalog, visibility), offered)
    }

    @Test
    fun malformedEntriesDoNotHide() {
        // Unknown values decode to "no override" — the picker stays complete.
        val groups = ModelPickerItems.groups(catalog, mapOf("openai:gpt-5" to "bogus"))
        assertTrue(groups.sumOf { it.models.size } == 3)
    }
}
