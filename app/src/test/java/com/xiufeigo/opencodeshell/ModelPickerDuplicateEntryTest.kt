package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.composer.ModelPickerItems
import com.xiufeigo.opencodeshell.v2.V2Api
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Catalog entries that share one wire `modelID`, measured live on the v2
 * backend: `GET /api/model` answers one row per catalog **entry**, and a single
 * `(providerID, modelID)` can be shared by 2 or 3 of them — 10 such pairs, all
 * under `openai` (3 doubled, 7 tripled), e.g. `gpt-5.4`/`gpt-5.4-fast` and
 * `gpt-5.6`/`gpt-5.6-fast`/`gpt-5.6-pro`, every row reporting
 * `modelID: "gpt-5.6"` and differing only in the entry `id` (plus
 * `body.serviceTier` / `body.reasoning.mode`) and the display name.
 *
 * Two failures followed from folding those rows on `modelID` alone:
 *  - the picker keyed its rows `model:<providerID>/<modelID>`, so a duplicated
 *    group handed Compose repeated keys
 *    (`Key "model:openai/gpt-5.6" was already used`);
 *  - the session ref kept only the `modelID`, so tapping「GPT-5.6 Fast」
 *    switched to the same ref as the base row.
 *
 * The fix keeps the entry id (`Providers.Model.entryID`, wire `Model.Info.id`) —
 * the id the server matches a session's `Model.Ref.id` against
 * (`_upstream/packages/core/src/session/runner/model.ts`) and the one upstream's
 * picker keys rows by (`providers/models/selection.tsx`).
 */
class ModelPickerDuplicateEntryTest {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    // ------------------------------------------------------------- fixture

    /** Live `gpt-5.4` variant list (5) — identical across that model's entries. */
    private val variants5 = listOf("none", "low", "medium", "high", "xhigh")

    /** Live `gpt-5.6` variant list (6) — identical across that model's entries. */
    private val variants6 = variants5 + "max"

    /** One `GET /api/model` row, folded through the real `Model.fromV2`. */
    private fun entry(
        entryID: String,
        modelID: String,
        name: String,
        variants: List<String> = variants5,
    ): OpenCodeClient.Providers.Model {
        val variantsJson = JSONArray()
        for (variant in variants) variantsJson.put(JSONObject().put("id", variant))
        return OpenCodeClient.Providers.Model.fromV2(
            V2Api.ModelInfo.fromJson(
                JSONObject()
                    .put("id", entryID)
                    .put("modelID", modelID)
                    .put("providerID", "openai")
                    .put("name", name)
                    .put("status", "active")
                    .put("enabled", true)
                    .put("capabilities", JSONObject().put("tools", true))
                    .put("limit", JSONObject().put("context", 1_050_000).put("output", 131_072))
                    .put("variants", variantsJson),
            ),
        )
    }

    private fun openaiModels(variants6Models: Boolean = false): List<OpenCodeClient.Providers.Model> {
        val six = if (variants6Models) variants6 else variants5
        return listOf(
            entry("gpt-5.4", "gpt-5.4", "GPT-5.4"),
            entry("gpt-5.4-fast", "gpt-5.4", "GPT-5.4 Fast"),
            entry("gpt-5.6", "gpt-5.6", "GPT-5.6", six),
            entry("gpt-5.6-fast", "gpt-5.6", "GPT-5.6 Fast", six),
            entry("gpt-5.6-pro", "gpt-5.6", "GPT-5.6 Pro", six),
        )
    }

    private fun catalog(
        models: List<OpenCodeClient.Providers.Model> = openaiModels(),
    ): OpenCodeClient.Providers = OpenCodeClient.Providers(
        all = listOf(OpenCodeClient.Providers.Provider("openai", "OpenAI", models)),
        connected = emptyList(),
        default = emptyMap(),
    )

    private fun groups(
        models: List<OpenCodeClient.Providers.Model> = openaiModels(),
    ): List<ModelPickerItems.Group> =
        ModelPickerItems.groups(catalog(models), emptyMap(), includeUnconnected = true)

    // ---------------------------------------------------------- row identity

    @Test
    fun everyCatalogEntryGetsItsOwnPickerRowKey() {
        val group = groups().single()
        val keys = group.models.map { ModelPickerItems.rowKey(group, it) }
        // Five entries, five keys — the entry id separates the siblings.
        assertEquals(
            listOf(
                "model:openai/gpt-5.4",
                "model:openai/gpt-5.4-fast",
                "model:openai/gpt-5.6",
                "model:openai/gpt-5.6-fast",
                "model:openai/gpt-5.6-pro",
            ),
            keys,
        )
        assertEquals(keys.size, keys.distinct().size)

        // The pre-fix key (the wire modelID) repeats inside this one group:
        // this is the `Key … was already used` crash, pinned as the mechanism
        // the entry id removes.
        val legacyKeys = group.models.map { "model:${group.providerID}/${it.id}" }
        assertEquals(2, legacyKeys.distinct().size)
        assertEquals(legacyKeys.size, keys.size)
    }

    @Test
    fun siblingEntriesWithOneModelIdAreIndependentlySelectable() {
        val group = groups().single()
        val base = group.models.first { it.entryID == "gpt-5.4" }
        val fast = group.models.first { it.entryID == "gpt-5.4-fast" }
        // One wire modelID, two catalog entries.
        assertEquals("gpt-5.4", base.id)
        assertEquals(base.id, fast.id)
        assertNotEquals(base.entryID, fast.entryID)

        // Each row hands back its own ref: same provider, different id half.
        assertEquals(
            "openai" to "gpt-5.4",
            ModelPickerItems.selectionRef(group, base),
        )
        assertEquals(
            "openai" to "gpt-5.4-fast",
            ModelPickerItems.selectionRef(group, fast),
        )
        // …and each ref resolves back to its own entry, which is what the chip
        // label and the variant group read.
        val providers = catalog()
        assertEquals("GPT-5.4", providers.model("openai", "gpt-5.4")!!.name)
        assertEquals("GPT-5.4 Fast", providers.model("openai", "gpt-5.4-fast")!!.name)
    }

    @Test
    fun threeSiblingsStayThreeRowsAndAreSearchableIndividually() {
        val group = groups().single()
        val sol = group.models.filter { it.id == "gpt-5.6" }
        assertEquals(3, sol.size)
        assertEquals(listOf("gpt-5.6", "gpt-5.6-fast", "gpt-5.6-pro"), sol.map { it.entryID })

        // The entry id is searchable, so a query naming one tier reaches only it.
        assertEquals(
            listOf("GPT-5.6 Pro"),
            ModelPickerItems.filter(groups(), "gpt-5.6-pro").single().models.map { it.name },
        )
        assertEquals(
            listOf("GPT-5.6", "GPT-5.6 Fast", "GPT-5.6 Pro"),
            ModelPickerItems.filter(groups(), "gpt-5.6").single().models.map { it.name },
        )
    }

    @Test
    fun onlyTheSelectedEntryIsChecked() {
        val group = groups().single()
        for (entryID in listOf("gpt-5.4", "gpt-5.4-fast", "gpt-5.6-pro")) {
            val selected = "openai" to entryID
            assertEquals(
                entryID,
                group.models.filter { ModelPickerItems.isSelected(group, it, selected) }
                    .single().entryID,
            )
        }
        // Another provider, no selection, or an unknown id checks nothing.
        assertTrue(group.models.none { ModelPickerItems.isSelected(group, it, "opencode-go" to "gpt-5.4") })
        assertTrue(group.models.none { ModelPickerItems.isSelected(group, it, null) })
        assertTrue(group.models.none { ModelPickerItems.isSelected(group, it, "openai" to "gpt-9") })
    }

    @Test
    fun aRefNamingAModelIdResolvesToTheBaseEntry() {
        // Legacy overrides persisted before the entry id was kept (and the
        // message/config strings that carry a bare modelID) still resolve — to
        // the catalog's first entry for that modelID, which is the base one.
        val providers = catalog()
        val resolved = providers.model("openai", "gpt-5.6")!!
        assertEquals("gpt-5.6", resolved.entryID)
        assertEquals("GPT-5.6", resolved.name)
        // Unknown halves stay null instead of guessing.
        assertNull(providers.model("openai", "gpt-9"))
        assertNull(providers.model("anthropic", "gpt-5.6"))
    }

    // ---------------------------------------------------- variant per entry

    @Test
    fun variantsResolveAgainstTheTappedEntry() {
        // The live siblings share one variant list, but nothing may resolve
        // "the model's" variants by modelID: the entry the ref names owns them.
        val split = listOf(
            entry("gpt-5.6", "gpt-5.6", "GPT-5.6", variants5),
            entry("gpt-5.6-fast", "gpt-5.6", "GPT-5.6 Fast", listOf("low", "high")),
        )
        val providers = catalog(split)
        assertEquals(
            listOf("default", "none", "low", "medium", "high", "xhigh"),
            ModelPickerItems.variantOptions(providers.model("openai", "gpt-5.6")),
        )
        assertEquals(
            listOf("default", "low", "high"),
            ModelPickerItems.variantOptions(providers.model("openai", "gpt-5.6-fast")),
        )
        // A pinned effort only counts on the entry that advertises it.
        assertEquals(
            "xhigh",
            ModelPickerItems.resolveVariant("xhigh", providers.model("openai", "gpt-5.6")!!.variants),
        )
        assertNull(
            ModelPickerItems.resolveVariant("xhigh", providers.model("openai", "gpt-5.6-fast")!!.variants),
        )
        // The picker offers exactly the effective entry's options.
        val group = ModelPickerItems.groups(providers, emptyMap(), includeUnconnected = true).single()
        val tapped = group.models.first { it.entryID == "gpt-5.6-fast" }
        assertEquals(
            listOf("default", "low", "high"),
            ModelPickerItems.variantOptions(
                providers.model(group.providerID, ModelPickerItems.selectionRef(group, tapped).second),
            ),
        )
    }

    // ------------------------------------------------------- wiring pins

    @Test
    fun theFoldKeepsTheEntryId() {
        // The live row's two ids survive the fold; a fold on `modelID` alone is
        // the bug this suite exists for.
        val row = entry("gpt-5.6-pro", "gpt-5.6", "GPT-5.6 Pro")
        assertEquals("gpt-5.6", row.id)
        assertEquals("gpt-5.6-pro", row.entryID)

        val vm = source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")
        assertTrue(vm.contains("OpenCodeClient.Providers.Model::fromV2"))
        // The v2 chip default takes the entry id too (`GET /api/model/default`
        // answers a full `Model.Info`).
        assertTrue(vm.contains("entry.providerID to entry.id.ifEmpty { entry.modelID }"))
    }

    @Test
    fun pickerRowsAreKeyedAndCheckedByTheEntryId() {
        val picker =
            source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/ModelPicker.kt")
        assertTrue(picker.contains("ModelPickerItems.rowKey(group, model)"))
        assertTrue(picker.contains("ModelPickerItems.isSelected(group, model, selectedModel)"))
        assertTrue(picker.contains("group.providerID to model.entryID"))
        // A future edit must not rebuild either from the wire modelID again.
        assertFalse(picker.contains("key = { model -> \"model:\${group.providerID}/\${model.id}\" }"))
        assertFalse(picker.contains("selectedModel.second == model.id"))
    }
}
