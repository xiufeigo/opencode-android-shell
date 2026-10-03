package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.composer.modelChipProviderBadge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The composer model chip must disambiguate same-name models: live
 * `GET /api/model` catalogs `space-bunny-free` under BOTH `opencode` (Zen)
 * and `opencode-go`, so a bare name cannot tell which backend the session
 * would run on. The badge names the owning provider, but only while the name
 * is genuinely ambiguous — an unconditional suffix would badge every chip.
 */
class ModelChipProviderBadgeTest {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    private fun providers(vararg groups: Pair<String, List<Pair<String, String>>>) =
        OpenCodeClient.Providers(
            all = groups.map { (providerID, models) ->
                OpenCodeClient.Providers.Provider(
                    id = providerID,
                    name = providerID,
                    models = models.map { (id, name) ->
                        OpenCodeClient.Providers.Model(id = id, name = name, capabilities = null)
                    },
                )
            },
            connected = groups.map { it.first },
            default = emptyMap(),
        )

    private val zenGo = providers(
        "opencode" to listOf("space-bunny-free" to "Space Bunny Free"),
        "opencode-go" to listOf("space-bunny-free" to "Space Bunny Free"),
    )

    @Test
    fun ambiguousNameBadgesItsOwningProvider() {
        assertEquals(
            "opencode",
            modelChipProviderBadge(zenGo, "opencode" to "space-bunny-free"),
        )
        assertEquals(
            "opencode-go",
            modelChipProviderBadge(zenGo, "opencode-go" to "space-bunny-free"),
        )
    }

    @Test
    fun uniqueNameRendersBare() {
        val catalog = providers(
            "opencode" to listOf("space-bunny-free" to "Space Bunny Free"),
            "xiaomi" to listOf("mimo" to "MiMo"),
        )
        assertNull(modelChipProviderBadge(catalog, "xiaomi" to "mimo"))
    }

    @Test
    fun unknownSelectionOrCatalogRendersBare() {
        assertNull(modelChipProviderBadge(zenGo, "opencode" to "nope"))
        assertNull(modelChipProviderBadge(zenGo, null))
        assertNull(modelChipProviderBadge(null, "opencode" to "space-bunny-free"))
    }

    @Test
    fun chipRendersTheBadgeBesideTheModelName() {
        // Source pin (repo `ModelPickerBadgeTest` convention): the chip reads
        // the helper and renders the provider as a faint mini-badge in the
        // picker's unconnected-badge style — not an inline label suffix.
        val chip = source(
            "src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/ModelChip.kt",
        ).substringAfter("fun ModelChip(")
        assertTrue(chip.contains("modelChipProviderBadge(providers, selectedModel)"))
        assertTrue(chip.contains("if (!compact && providerBadge != null)"))
        assertTrue(chip.contains("c.textFaint"))
        assertTrue(chip.contains("c.borderMuted"))
    }
}
