package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.OpenCodeClient.Providers
import com.xiufeigo.opencodeshell.ui.session.composer.ModelPickerItems
import com.xiufeigo.opencodeshell.ui.settings.ModelRow
import com.xiufeigo.opencodeshell.ui.settings.ModelVisibility
import com.xiufeigo.opencodeshell.ui.settings.modelRows
import com.xiufeigo.opencodeshell.ui.settings.visibleModelCount
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Entry-level visibility keys (`ModelVisibility`): the Models-tab Switch and the
 * composer ModelPicker both key on the **catalog entry** (`Model.Info.id` →
 * `Model.entryID`), not the wire `modelID`, because one provider can list several
 * entries under one `modelID` (measured on the live v2 backend: `openai` ships
 * `gpt-5.6`, `gpt-5.6-fast` and `gpt-5.6-pro`, all reporting
 * `modelID: "gpt-5.6"`). Hiding one entry must leave its siblings alone, while a
 * map written by the pre-entry-key build (`providerID:modelID`) still applies to
 * every entry that shares that wire id.
 */
class ModelVisibilityEntryTest {

    /** `openai` as the v2 backend reports it: three entries, one wire `modelID`. */
    private fun entry(entryID: String, name: String) = Providers.Model(
        id = "gpt-5.6",
        name = name,
        capabilities = null,
        entryID = entryID,
    )

    private val catalog = Providers(
        all = listOf(
            Providers.Provider(
                id = "openai",
                name = "OpenAI",
                models = listOf(
                    entry("gpt-5.6", "GPT-5.6"),
                    entry("gpt-5.6-fast", "GPT-5.6 Fast"),
                    entry("gpt-5.6-pro", "GPT-5.6 Pro"),
                ),
            ),
        ),
        connected = listOf("openai"),
        default = emptyMap(),
    )

    /** A v1 catalog: the model map carries no separate entry id. */
    private val v1Catalog = Providers.fromJson(
        JSONObject(
            """
            {
              "all": [
                {
                  "id": "openai",
                  "name": "OpenAI",
                  "models": {"gpt-5": {"id": "gpt-5", "name": "GPT-5"}}
                }
              ],
              "connected": ["openai"]
            }
            """.trimIndent(),
        ),
    )

    private fun rows(visibility: Map<String, String>) =
        modelRows(catalog, selected = null, visibility = visibility)

    private fun visibilityByEntry(overrides: Map<String, String>) =
        rows(overrides).associate { it.entryID to it.visible }

    /** Replays what the Models tab does with one Switch flip. */
    private fun applyWrites(
        overrides: Map<String, String>,
        writes: List<ModelVisibility.EntryWrite>,
    ): Map<String, String> {
        var next = overrides
        for (write in writes) {
            next = ModelVisibility.setVisible(next, write.providerID, write.entryID, write.visible)
        }
        return next
    }

    @Test
    fun everyRowCarriesItsOwnEntryIdentity() {
        val rows = rows(emptyMap())
        assertEquals(
            listOf("openai/gpt-5.6", "openai/gpt-5.6-fast", "openai/gpt-5.6-pro"),
            rows.map { it.ref },
        )
        // …while the wire model id stays what the session ref matches on.
        assertEquals(listOf("gpt-5.6", "gpt-5.6", "gpt-5.6"), rows.map { it.id })
    }

    @Test
    fun hidingOneEntryLeavesItsSiblingsVisible() {
        val hidden = ModelVisibility.setVisible(emptyMap(), "openai", "gpt-5.6-fast", false)
        assertEquals(mapOf("openai:gpt-5.6-fast" to "hide"), hidden)

        assertEquals(
            mapOf("gpt-5.6" to true, "gpt-5.6-fast" to false, "gpt-5.6-pro" to true),
            visibilityByEntry(hidden),
        )
        assertEquals(2, visibleModelCount(catalog, hidden))
        // The picker drops exactly that entry, not the whole group.
        assertEquals(
            listOf("gpt-5.6", "gpt-5.6-pro"),
            ModelPickerItems.groups(catalog, hidden, includeUnconnected = true)
                .flatMap { group -> group.models.map { it.entryID } },
        )
    }

    @Test
    fun hidingTheBaseEntryLeavesTheNamedVariantsVisible() {
        // The base entry's key is also the legacy key, so the tab pins its siblings
        // before flipping it (`toggleWrites`) — a bare `setVisible` on the base
        // would still reach them, which is what the legacy read is for.
        val all = rows(emptyMap())
        val base = all.first { it.entryID == "gpt-5.6" }
        assertEquals(
            mapOf("gpt-5.6" to false, "gpt-5.6-fast" to true, "gpt-5.6-pro" to true),
            visibilityByEntry(applyWrites(emptyMap(), ModelVisibility.toggleWrites(all, base, false))),
        )
    }

    @Test
    fun legacyModelIdKeyStillHidesEveryEntryUnderThatModelId() {
        // Written by the pre-entry-key build: one key per wire model id, so it
        // covers every entry that reports it — old overrides keep working.
        val legacy = mapOf("openai:gpt-5.6" to "hide")
        assertEquals(
            mapOf("gpt-5.6" to false, "gpt-5.6-fast" to false, "gpt-5.6-pro" to false),
            visibilityByEntry(legacy),
        )
        assertTrue(ModelPickerItems.groups(catalog, legacy, includeUnconnected = true).isEmpty())
    }

    @Test
    fun entryKeyBeatsTheLegacyKey() {
        val overrides = mapOf(
            "openai:gpt-5.6" to "hide",
            "openai:gpt-5.6-fast" to "show",
        )
        assertEquals(
            mapOf("gpt-5.6" to false, "gpt-5.6-fast" to true, "gpt-5.6-pro" to false),
            visibilityByEntry(overrides),
        )
    }

    @Test
    fun overrideForReadsTheEntryKeyFirstThenTheLegacyKey() {
        val overrides = mapOf(
            "openai:gpt-5.6" to "hide",
            "openai:gpt-5.6-fast" to "show",
        )
        assertEquals(
            ModelVisibility.Override.SHOW,
            ModelVisibility.overrideFor("openai", "gpt-5.6-fast", overrides, modelID = "gpt-5.6"),
        )
        assertEquals(
            ModelVisibility.Override.HIDE,
            ModelVisibility.overrideFor("openai", "gpt-5.6-pro", overrides, modelID = "gpt-5.6"),
        )
        assertNull(ModelVisibility.overrideFor("openai", "gpt-5.6-pro", emptyMap(), modelID = "gpt-5.6"))
        // An unparseable value is not an override in either position.
        assertNull(
            ModelVisibility.overrideFor(
                "openai",
                "gpt-5.6-pro",
                mapOf("openai:gpt-5.6" to "bogus"),
                modelID = "gpt-5.6",
            ),
        )
    }

    @Test
    fun togglingANamedEntryWritesOnlyItsOwnKey() {
        val all = rows(emptyMap())
        val fast = all.first { it.entryID == "gpt-5.6-fast" }
        assertEquals(
            listOf(ModelVisibility.EntryWrite("openai", "gpt-5.6-fast", false)),
            ModelVisibility.toggleWrites(all, fast, false),
        )
    }

    @Test
    fun togglingTheBaseEntryPinsItsSiblingsToTheirCurrentState() {
        val all = rows(emptyMap())
        val base = all.first { it.entryID == "gpt-5.6" }
        // The base's key IS the legacy key, so its siblings have to be pinned to
        // the state they show right now, or the flip would reach them too.
        assertEquals(
            listOf(
                ModelVisibility.EntryWrite("openai", "gpt-5.6", false),
                ModelVisibility.EntryWrite("openai", "gpt-5.6-fast", true),
                ModelVisibility.EntryWrite("openai", "gpt-5.6-pro", true),
            ),
            ModelVisibility.toggleWrites(all, base, false),
        )
        assertEquals(
            mapOf("gpt-5.6" to false, "gpt-5.6-fast" to true, "gpt-5.6-pro" to true),
            visibilityByEntry(applyWrites(emptyMap(), ModelVisibility.toggleWrites(all, base, false))),
        )
    }

    @Test
    fun togglingTheBaseEntryKeepsTheSiblingsTheSearchFilteredOut() {
        // The picker's row list is filtered by the search box; the writes must come
        // from the full list or a filtered sibling would inherit the base's flip.
        val all = rows(emptyMap())
        val base = all.first { it.entryID == "gpt-5.6" }
        assertEquals(3, ModelVisibility.toggleWrites(all, base, false).size)
        assertEquals(1, ModelVisibility.toggleWrites(listOf(base), base, false).size)
    }

    @Test
    fun baseToggleUnderALegacyKeyKeepsTheLegacyStateThenPinsIt() {
        // A legacy key hides the whole group; showing the base shows *only* the base
        // and pins the siblings to the state that key gave them.
        val legacy = mapOf("openai:gpt-5.6" to "hide")
        val all = rows(legacy)
        val base = all.first { it.entryID == "gpt-5.6" }
        assertEquals(
            mapOf("gpt-5.6" to true, "gpt-5.6-fast" to false, "gpt-5.6-pro" to false),
            visibilityByEntry(applyWrites(legacy, ModelVisibility.toggleWrites(all, base, true))),
        )
    }

    @Test
    fun showingANamedEntryUnderALegacyKeyKeepsTheBaseHidden() {
        val legacy = mapOf("openai:gpt-5.6" to "hide")
        val all = rows(legacy)
        val fast = all.first { it.entryID == "gpt-5.6-fast" }
        assertEquals(
            mapOf("gpt-5.6" to false, "gpt-5.6-fast" to true, "gpt-5.6-pro" to false),
            visibilityByEntry(applyWrites(legacy, ModelVisibility.toggleWrites(all, fast, true))),
        )
    }

    @Test
    fun v1RowsFallBackToTheModelId() {
        val row = ModelRow("openai", "OpenAI", "gpt-5", "GPT-5", null, false)
        assertEquals("gpt-5", row.entryID)
        assertEquals("openai/gpt-5", row.ref)
        // A v1-shaped override (one key per model id) still lands on the row.
        assertEquals(
            listOf(false),
            modelRows(v1Catalog, selected = null, mapOf("openai:gpt-5" to "hide")).map { it.visible },
        )
    }
}
