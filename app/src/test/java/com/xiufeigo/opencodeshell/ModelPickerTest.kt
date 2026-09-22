package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.composer.ModelPickerItems
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Model picker data logic (`ModelPickerItems`): connected-only grouping with
 * the upstream popular-first order, the upstream `matchesModelSearch` filter
 * semantics, and the override-vs-server-default resolution — plus the
 * source-level guard for the wiring that the JVM cannot see.
 */
class ModelPickerTest {

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
            },
            {
              "id": "zzz-local",
              "name": "Local LLM",
              "models": {"llama": {"id": "llama", "name": "Llama 4"}}
            },
            {"id": "offline", "name": "Offline Co", "models": {"x": {"id": "x", "name": "X"}}}
          ],
          "connected": ["zzz-local", "openai", "anthropic"],
          "default": {"anthropic": "claude-sonnet-4"}
        }
        """.trimIndent(),
    )

    // ------------------------------------------------------------- grouping

    @Test
    fun groupsOnlyConnectedProvidersPopularFirst() {
        val groups = ModelPickerItems.groups(catalog)
        // "offline" is not connected; anthropic/openai outrank the plain
        // alphabetical tail via the upstream popularProviders order.
        assertEquals(listOf("anthropic", "openai", "zzz-local"), groups.map { it.providerID })
        assertEquals("Anthropic", groups[0].providerName)
        // Models inside a group are sorted by display name.
        assertEquals(listOf("GPT-4o", "GPT-5.5"), groups[1].models.map { it.name })
    }

    @Test
    fun groupsEmptyWithoutCatalogOrConnections() {
        assertTrue(ModelPickerItems.groups(null).isEmpty())
        assertTrue(
            ModelPickerItems.groups(providers("""{"all":[],"connected":[],"default":{}}""")).isEmpty(),
        )
    }

    // -------------------------------------------------------------- search

    @Test
    fun filterMatchesUpstreamSearchSemantics() {
        // Ported expectations of `matchesModelSearch` (`search.test.ts`).
        assertTrue(ModelPickerItems.matches("gpt 5", "GPT-5.5"))
        assertTrue(ModelPickerItems.matches("gpt-5", "GPT-5.5"))
        assertTrue(ModelPickerItems.matches("gpt5", "GPT-5.5"))
        assertTrue(ModelPickerItems.matches("open ai", "GPT-5.5", "gpt-5.5", "OpenAI"))
        assertTrue(ModelPickerItems.matches("gpt 5", "GPT-5.5", "gpt-5.5", "OpenAI"))
        assertFalse(ModelPickerItems.matches("claude", "GPT-5.5", "gpt-5.5", "OpenAI"))
        assertTrue("blank query matches everything", ModelPickerItems.matches("  ", "whatever"))
    }

    @Test
    fun filterKeepsOnlyMatchingModelsAndNonEmptyGroups() {
        val filtered = ModelPickerItems.filter(ModelPickerItems.groups(catalog), "gpt")
        assertEquals(listOf("openai"), filtered.map { it.providerID })
        assertEquals(listOf("GPT-4o", "GPT-5.5"), filtered.single().models.map { it.name })

        // Provider name is searched too (upstream passes provider.name).
        val byProvider = ModelPickerItems.filter(ModelPickerItems.groups(catalog), "anthropic")
        assertEquals(listOf("anthropic"), byProvider.map { it.providerID })

        assertTrue(ModelPickerItems.filter(ModelPickerItems.groups(catalog), "nomatch").isEmpty())
        assertEquals(
            ModelPickerItems.groups(catalog),
            ModelPickerItems.filter(ModelPickerItems.groups(catalog), ""),
        )
    }

    // --------------------------------------------- selection resolution

    @Test
    fun resolveSelectedPrefersTheOverride() {
        val override = "openai" to "gpt-5"
        val server = "anthropic" to "claude-sonnet-4"
        assertEquals(override, ModelPickerItems.resolveSelected(override, server))
    }

    @Test
    fun resolveSelectedFollowsTheServerDefaultWithoutAnOverride() {
        val server = "anthropic" to "claude-sonnet-4"
        assertEquals(server, ModelPickerItems.resolveSelected(null, server))
        assertNull(ModelPickerItems.resolveSelected(null, null))
    }

    // ----------------------------------------------- wiring source guards

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    @Test
    fun pickerStringsExistInEveryLocale() {
        val keys = listOf(
            "oc_model_picker_title",
            "oc_model_picker_search",
            "oc_model_picker_empty",
            "oc_model_picker_use_default",
            "oc_model_picker_unavailable",
        )
        for (dir in listOf("values", "values-zh-rCN", "values-zh-rTW")) {
            val xml = source("src/main/res/$dir/strings.xml")
            for (key in keys) {
                assertTrue("$dir missing $key", xml.contains("name=\"$key\""))
            }
        }
    }

    @Test
    fun chipOpensTheCatalogPicker() {
        val chip = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/ModelChip.kt")
        assertTrue(chip.contains("ModelPickerContent("))
        assertTrue(chip.contains("onUseServerDefault"))

        val picker =
            source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/ModelPicker.kt")
        assertTrue(picker.contains("ModelPickerItems.groups("))
        assertTrue(picker.contains("ModelPickerItems.filter("))
        assertTrue("search field", picker.contains("oc_model_picker_search"))
        assertTrue("empty state", picker.contains("oc_model_picker_empty"))
        assertTrue("server default row", picker.contains("oc_model_picker_use_default"))
    }

    @Test
    fun viewModelPersistsAndSendsTheSelection() {
        val vm = source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")
        assertTrue(vm.contains("settingsStore?.selectedModelOverride"))
        assertTrue(vm.contains("model = _selectedModel.value"))
        assertTrue(vm.contains("fun selectModel(providerID: String, modelID: String)"))
        assertTrue(vm.contains("fun clearModelOverride()"))
    }
}
