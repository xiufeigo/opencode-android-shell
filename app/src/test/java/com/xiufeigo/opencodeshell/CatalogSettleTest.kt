package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.composer.ModelPickerItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * WAVE 2.4d — the v2 catalog must not be destroyed by one short read (device
 * v11): `GET /api/model` briefly answered with 8 entries of several hundred
 * while the picker was open, the picker collapsed to those rows, the chip lost
 * the model name (raw ref id instead) and nothing re-read the catalog, so only
 * leaving and reopening the session helped.
 *
 * [ModelPickerItems.plausibleCatalog] is the pure gate the view model now puts
 * in front of the catalog write; the source pins below are the repo's
 * established guard for wiring that a JVM test cannot reach (the view model
 * needs an Android `ServerConfigStore`).
 */
class CatalogSettleTest {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private fun viewModel() = source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")

    private fun model(id: String) = OpenCodeClient.Providers.Model(
        id = id,
        name = id,
        capabilities = null,
    )

    /** One provider carrying [count] models, plus [otherProviders] empty ones. */
    private fun catalog(
        count: Int,
        providerID: String = "opencode",
        otherProviders: Int = 0,
    ): OpenCodeClient.Providers = OpenCodeClient.Providers(
        all = listOf(
            OpenCodeClient.Providers.Provider(
                id = providerID,
                name = providerID,
                models = (1..count).map { model("m$it") },
            ),
        ) + (1..otherProviders).map {
            OpenCodeClient.Providers.Provider(id = "p$it", name = "p$it", models = emptyList())
        },
        connected = listOf(providerID),
        default = emptyMap(),
    )

    // ------------------------------------------------------------- the gate

    @Test
    fun firstCatalogIsAlwaysAccepted() {
        // Nothing on screen to lose.
        assertTrue(ModelPickerItems.plausibleCatalog(null, catalog(0)))
        assertTrue(ModelPickerItems.plausibleCatalog(null, catalog(300)))
    }

    @Test
    fun shortAnswerOfTheSameCatalogIsRejected() {
        // The measured device shape: 8 rows of several hundred.
        assertFalse(ModelPickerItems.plausibleCatalog(catalog(300), catalog(8, providerID = "zen")))
        // …and the milder version, just under half.
        assertFalse(ModelPickerItems.plausibleCatalog(catalog(300), catalog(149)))
    }

    @Test
    fun emptyAnswerNeverReplacesANonEmptyCatalog() {
        // With no models the picker renders nothing at all, which is strictly
        // worse than the catalog already loaded.
        assertFalse(ModelPickerItems.plausibleCatalog(catalog(300), catalog(0)))
        assertFalse(ModelPickerItems.plausibleCatalog(catalog(2), catalog(0)))
        // Two empty catalogs are just two empty catalogs.
        assertTrue(ModelPickerItems.plausibleCatalog(catalog(0), catalog(0)))
    }

    @Test
    fun halfOrMoreIsAccepted() {
        // Losing half exactly is the boundary and passes (`after * 2 >= before`).
        assertTrue(ModelPickerItems.plausibleCatalog(catalog(300), catalog(150)))
        assertTrue(ModelPickerItems.plausibleCatalog(catalog(300), catalog(301)))
    }

    @Test
    fun smallCatalogsAreNotPoliced() {
        // Below `PLAUSIBLE_BASELINE` a smaller answer may simply be the truth
        // (a trimmed test catalog, one provider dropped), so the rule stays out
        // of the way — but emptiness is still never accepted.
        assertTrue(ModelPickerItems.plausibleCatalog(catalog(4), catalog(1)))
        assertTrue(ModelPickerItems.plausibleCatalog(catalog(15), catalog(2)))
        assertFalse(ModelPickerItems.plausibleCatalog(catalog(4), catalog(0)))
        assertEquals(16, ModelPickerItems.PLAUSIBLE_BASELINE)
    }

    @Test
    fun modelCountSumsEveryProvider() {
        assertEquals(0, ModelPickerItems.modelCount(null))
        assertEquals(0, ModelPickerItems.modelCount(catalog(0, otherProviders = 3)))
        assertEquals(12, ModelPickerItems.modelCount(catalog(12, otherProviders = 3)))
    }

    @Test
    fun aRejectedAnswerLeavesTheSelectionAndTheGroupsIntact() {
        // The point of rejecting: everything the picker shows keeps reading the
        // catalog that stayed loaded — the check mark, the chip's name and the
        // provider groups.
        val good = catalog(20, providerID = "opencode")
        val selected = "opencode" to "m7"
        assertFalse(ModelPickerItems.plausibleCatalog(good, catalog(2, providerID = "zen")))

        val groups = ModelPickerItems.groups(good, includeUnconnected = true)
        val group = groups.single()
        assertEquals("opencode", group.providerID)
        assertEquals(20, group.models.size)
        assertTrue(ModelPickerItems.isSelected(group, group.models.single { it.entryID == "m7" }, selected))
        // The name resolution behind `syncModelLabel` needs the entry too.
        assertEquals("m7", good.model("opencode", "m7")?.name)
    }

    // ------------------------------------------------------------ the wiring

    @Test
    fun theCatalogWriteIsGatedOnTheGate() {
        val vm = viewModel()
        val write = vm.substringAfter("private fun refreshV2Providers()")
            .substringBefore("private fun syncModelLabel()")
        assertTrue(
            "the v2 catalog write must go through the plausibility gate",
            write.contains("ModelPickerItems.plausibleCatalog(previous, next)"),
        )
        assertTrue("…and re-read when it fails", write.contains("settleV2Providers()"))
        // The gate must sit *before* the write, or the short answer wins anyway.
        assertTrue(
            write.indexOf("plausibleCatalog(") < write.indexOf("_availableProviders.value = next"),
        )
    }

    @Test
    fun theSettleReReadIsBoundedAndBackedOff() {
        val vm = viewModel()
        val settle = vm.substringAfter("private fun settleV2Providers()")
            .substringBefore("private fun syncModelLabel()")
        assertTrue("the retry budget is capped", settle.contains("attempt >= CATALOG_SETTLE_ATTEMPTS"))
        assertTrue("the delay grows per attempt", settle.contains("CATALOG_SETTLE_RETRY_DELAY_MS * (attempt + 1)"))
        assertTrue("…and it re-reads the catalog", settle.contains("refreshV2Providers()"))
        assertTrue(
            "an accepted catalog resets the budget",
            vm.contains("catalogSettleRetries = 0\n                _availableProviders.value = next"),
        )
        assertTrue(
            "a server switch drops the budget with the catalog",
            vm.contains("catalogSettleRetries = 0\n        serverDefaultModel = null"),
        )
    }

    @Test
    fun theEmptyProviderListAlsoSettlesInsteadOfSilentlyReturning() {
        // The pre-existing `if (providers.isEmpty()) return@launch` dropped the
        // whole refresh on the floor: no retry, and the catalog stayed empty.
        val vm = viewModel()
        val write = vm.substringAfter("private fun refreshV2Providers()")
            .substringBefore("private fun syncModelLabel()")
        val guard = write.substringAfter("if (providers.isEmpty())")
            .substringBefore("val connectedIds")
        assertTrue(
            "the empty provider list must trigger a settle re-read",
            guard.contains("settleV2Providers()"),
        )
        assertTrue("…and still bail out of this round", guard.contains("return@launch"))
    }
}
