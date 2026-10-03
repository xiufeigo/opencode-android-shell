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
 *
 * P0-4: the picker's default view is the **show-all** path
 * (`groups(..., includeUnconnected = true)` — every cataloged provider,
 * connected or not, so OAuth-style providers like `opencode` are never
 * hidden); the `pickerDefaultView*` tests below pin that default against a
 * v2-shaped catalog whose `connected` is empty (the live backend reports
 * zero integrations with stored connections).
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
            "oc_model_picker_unconnected",
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
        assertTrue(vm.contains("fun selectModel(providerID: String, modelID: String)"))
        assertTrue(vm.contains("fun clearModelOverride()"))
        // The steer-before-send must switch with BOTH halves of the picked
        // ref — dropping providerID here replays the Zen-row-lands-on-Go bug
        // (same model id under two providers, Go sorts first).
        assertTrue(vm.contains("switchModel(id, pin.providerID, pin.modelID, pin.variant)"))
        // …and only the *picked* ref: `_selectedModel` also carries derived
        // labels (the session's own model, the server default), and writing one
        // of those is the v28 "session's model silently replaced" bug
        // (`SessionModelScopeTest`).
        assertFalse(vm.contains("switchModel(id, model.first, model.second"))
    }

    // --------------------------------- P0-4 default view: show-all (v2)

    /**
     * v2-shaped catalog mirroring the live backend: `connected` is empty
     * (no integration reports a stored connection), so the legacy
     * connected-only default would render an empty picker.
     */
    private val v2Catalog = providers(
        """
        {
          "all": [
            {
              "id": "opencode",
              "name": "OpenCode Zen",
              "models": {
                "muse-spark-1.3-contributor-free": {"id": "muse-spark-1.3-contributor-free", "name": "Muse Spark 1.3 Free"},
                "space-bunny-free": {"id": "space-bunny-free", "name": "Space Bunny Free"}
              }
            },
            {
              "id": "opencode-go",
              "name": "OpenCode Go",
              "models": {
                "muse-spark-1.3-contributor": {"id": "muse-spark-1.3-contributor", "name": "Muse Spark 1.3 Contributor"}
              }
            },
            {
              "id": "xiaomi",
              "name": "Xiaomi",
              "models": {"mimo": {"id": "mimo", "name": "MiMo"}}
            },
            {
              "id": "stepfun",
              "name": "StepFun (China)",
              "models": {"step-3": {"id": "step-3", "name": "Step 3"}}
            }
          ],
          "connected": [],
          "default": {}
        }
        """.trimIndent(),
    )

    /** The path `ModelPickerContent` takes for its default view. */
    private fun defaultView(
        visibility: Map<String, String> = emptyMap(),
    ) = ModelPickerItems.groups(v2Catalog, visibility, includeUnconnected = true)

    @Test
    fun pickerDefaultViewListsEveryProviderWhenNothingIsConnected() {
        // The legacy default hides everything here — the bug P0-4 fixes.
        assertTrue(ModelPickerItems.groups(v2Catalog).isEmpty())
        // The picker default keeps every provider with models, popular-first.
        val groups = defaultView()
        assertEquals(
            listOf("opencode-go", "opencode", "stepfun", "xiaomi"),
            groups.map { it.providerID },
        )
        assertEquals(5, groups.sumOf { it.models.size })
        // `connected` only marks rows — it never hides them.
        assertTrue(groups.none { it.connected })
    }

    @Test
    fun groupsFlagConnectedPerProviderWithoutHiding() {
        // Mixed catalog: the show-all path keeps every provider and flags
        // each row, so the UI can badge unconnected rows without hiding them.
        val groups = ModelPickerItems.groups(catalog, includeUnconnected = true)
        assertEquals(
            mapOf(
                "anthropic" to true,
                "openai" to true,
                "zzz-local" to true,
                "offline" to false,
            ),
            groups.associate { it.providerID to it.connected },
        )
        // The legacy connected-only default still hides unconnected rows —
        // every surviving row is flagged connected.
        val legacy = ModelPickerItems.groups(catalog)
        assertTrue(legacy.none { it.providerID == "offline" })
        assertTrue(legacy.all { it.connected })
    }

    @Test
    fun unconnectedBadgeIsWiredToTheGroupFlag() {
        val picker =
            source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/ModelPicker.kt")
        assertTrue(picker.contains("oc_model_picker_unconnected"))
        assertTrue(picker.contains("!group.connected"))
    }

    @Test
    fun pickerDefaultViewSearchFindsTargetAcrossUnconnectedProviders() {
        val found = ModelPickerItems.filter(defaultView(), "muse-spark")
        assertEquals(listOf("opencode-go", "opencode"), found.map { it.providerID })
        val exact = ModelPickerItems.filter(defaultView(), "muse-spark-1.3-contributor-free")
        assertEquals(listOf("opencode"), exact.map { it.providerID })
        assertEquals(
            listOf("muse-spark-1.3-contributor-free"),
            exact.single().models.map { it.id },
        )
    }

    @Test
    fun pickerDefaultViewStillHonorsVisibilitySwitches() {
        val hidden = com.xiufeigo.opencodeshell.ui.settings.ModelVisibility.setVisible(
            emptyMap(), "opencode", "space-bunny-free", false,
        )
        val groups = defaultView(hidden)
        assertEquals(
            listOf("opencode-go", "opencode", "stepfun", "xiaomi"),
            groups.map { it.providerID },
        )
        assertEquals(
            listOf("muse-spark-1.3-contributor-free"),
            groups.first { it.providerID == "opencode" }.models.map { it.id },
        )
    }

    @Test
    fun pickerDefaultViewWiringPassesShowAll() {
        // Nails the default view to the show-all path: a future edit must not
        // silently drop `includeUnconnected = true` from the picker call site.
        val picker =
            source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/ModelPicker.kt")
        assertTrue(picker.contains("includeUnconnected = true"))
    }

    // --------------------------------- P0: same-name models keep their group
    //
    // Live shape (`GET /api/model`, 2026-09-25): `space-bunny-free` is
    // cataloged under BOTH `opencode` (Zen, usable with the public key) and
    // `opencode-go` (no apiKey — a session pinned to it answers `Missing API
    // key`). Tapping the Zen row must yield `providerID=opencode`, tapping
    // the Go row `providerID=opencode-go`; resolving by model id alone always
    // lands on Go (`opencode-go` heads the popular order) — the live-device
    // pit this section nails down.

    /** Zen/Go overlap mirroring the live backend catalog. */
    private val zenGoCatalog = providers(
        """
        {
          "all": [
            {
              "id": "opencode",
              "name": "OpenCode Zen",
              "models": {
                "space-bunny-free": {"id": "space-bunny-free", "name": "Space Bunny Free"},
                "nemotron-3.5-lightning-free": {"id": "nemotron-3.5-lightning-free", "name": "Nemotron 3.5 Lightning Free"}
              }
            },
            {
              "id": "opencode-go",
              "name": "OpenCode Go",
              "models": {
                "space-bunny-free": {"id": "space-bunny-free", "name": "Space Bunny Free"}
              }
            }
          ],
          "connected": ["opencode", "opencode-go"],
          "default": {}
        }
        """.trimIndent(),
    )

    @Test
    fun sameNameModelStaysSelectableInEveryProviderGroup() {
        // Go sorts first but must not swallow the Zen copy: both groups keep
        // their own row, so each stays independently tappable.
        val groups = ModelPickerItems.groups(zenGoCatalog)
        assertEquals(listOf("opencode-go", "opencode"), groups.map { it.providerID })
        assertTrue(groups.single { it.providerID == "opencode" }.models.any { it.id == "space-bunny-free" })
        assertTrue(groups.single { it.providerID == "opencode-go" }.models.any { it.id == "space-bunny-free" })
    }

    @Test
    fun selectionRefCarriesTheTappedRowsOwnGroupProvider() {
        val groups = ModelPickerItems.groups(zenGoCatalog)
        val zen = groups.single { it.providerID == "opencode" }
        val go = groups.single { it.providerID == "opencode-go" }
        // Same model id, two independent rows — whoever is tapped wins.
        assertEquals(
            "opencode" to "space-bunny-free",
            ModelPickerItems.selectionRef(zen, zen.models.single { it.id == "space-bunny-free" }),
        )
        assertEquals(
            "opencode-go" to "space-bunny-free",
            ModelPickerItems.selectionRef(go, go.models.single { it.id == "space-bunny-free" }),
        )
        // A Zen-only model id never resolves to the Go provider.
        assertEquals(
            "opencode" to "nemotron-3.5-lightning-free",
            ModelPickerItems.selectionRef(
                zen,
                zen.models.single { it.id == "nemotron-3.5-lightning-free" },
            ),
        )
    }

    @Test
    fun pickerRowRoutesThroughSelectionRef() {
        // Nails the row tap to the group-scoped ref: a future edit must not
        // rebuild the ref from a bare model id at the call site.
        val picker =
            source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/ModelPicker.kt")
        assertTrue(picker.contains("ModelPickerItems.selectionRef(group, model)"))
    }
}
