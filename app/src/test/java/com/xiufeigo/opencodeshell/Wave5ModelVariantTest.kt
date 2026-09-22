package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.composer.ModelPickerItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W5-C B4: the model variant (reasoning effort) sub-selection.
 *
 * `_upstream/packages/app/src/providers/models/variant.ts`: the option list is the
 * keys of `Model.variants`, `"default"` means "no override", a selection only
 * counts while the model advertises it, and cycling wraps around. The wire shape
 * (`variants` = `Record<label, object>`) is asserted by
 * [Wave5OpenCodeClientTest.modelVariantsComeFromTheCatalogKeys].
 */
class Wave5ModelVariantTest {

    private fun model(id: String = "gpt-5.6-sol", variants: List<String> = emptyList()) =
        OpenCodeClient.Providers.Model(
            id = id,
            name = id,
            capabilities = null,
            limitContext = 200_000,
            limitOutput = 8_192,
            variants = variants,
        )

    @Test
    fun theOptionListIsDefaultPlusTheAdvertisedVariants() {
        assertEquals(
            listOf("default", "low", "high"),
            ModelPickerItems.variantOptions(model(variants = listOf("low", "high"))),
        )
        // No variants on the wire → no control at all (`options().length > 1`).
        assertTrue(ModelPickerItems.variantOptions(model()).isEmpty())
        assertTrue(ModelPickerItems.variantOptions(null).isEmpty())
        assertFalse(ModelPickerItems.variantVisible(model()))
        assertTrue(ModelPickerItems.variantVisible(model(variants = listOf("high"))))
    }

    @Test
    fun aSelectionOnlyCountsWhenTheModelAdvertisesIt() {
        val variants = listOf("low", "medium", "high")
        assertEquals("high", ModelPickerItems.resolveVariant("high", variants))
        assertNull("not advertised", ModelPickerItems.resolveVariant("xhigh", variants))
        assertNull("`default` means no override", ModelPickerItems.resolveVariant("default", variants))
        assertNull("nothing pinned", ModelPickerItems.resolveVariant(null, variants))
        assertNull(ModelPickerItems.resolveVariant("low", emptyList()))
    }

    @Test
    fun anEmptySelectionFallsBackToThePreferredThenConfiguredValue() {
        val variants = listOf("low", "high")
        // `""` is the "the user explicitly cleared it" value upstream maps onto
        // `preferred`/`configured` (`variant.ts:31-35`).
        assertEquals("high", ModelPickerItems.resolveVariant("", variants, preferred = "high"))
        assertEquals("low", ModelPickerItems.resolveVariant("", variants, configured = "low"))
        assertNull(ModelPickerItems.resolveVariant("", variants, configured = "xhigh"))
    }

    @Test
    fun cyclingWalksTheListAndWrapsAround() {
        val variants = listOf("low", "medium", "high")
        assertEquals("low", ModelPickerItems.cycleVariant(null, variants))
        assertEquals("medium", ModelPickerItems.cycleVariant("low", variants))
        assertEquals("high", ModelPickerItems.cycleVariant("medium", variants))
        assertEquals("low", ModelPickerItems.cycleVariant("high", variants))
        // An unknown current value restarts the walk.
        assertEquals("low", ModelPickerItems.cycleVariant("xhigh", variants))
        assertNull(ModelPickerItems.cycleVariant("low", emptyList()))
    }
}
