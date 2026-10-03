package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.composer.ModelPickerItems
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPickerAllModelsTest {

    private fun providers(body: String) = OpenCodeClient.Providers.fromJson(JSONObject(body))

    private val v2Catalog = providers(
        """
        {
          "all": [
            {
              "id": "opencode",
              "name": "opencode",
              "models": {
                "muse-spark-1.3-contributor-free": {"id": "muse-spark-1.3-contributor-free", "name": "Muse Spark 1.3 Free"}
              }
            },
            {
              "id": "opencode-go",
              "name": "opencode-go",
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
              "name": "StepFun",
              "models": {"step-3": {"id": "step-3", "name": "Step 3"}}
            }
          ],
          "connected": ["xiaomi"],
          "default": {}
        }
        """.trimIndent(),
    )

    @Test
    fun showAllListsEveryProviderDespiteOneConnection() {
        val groups = ModelPickerItems.groups(v2Catalog, includeUnconnected = true)
        assertEquals(
            listOf("opencode-go", "opencode", "stepfun", "xiaomi"),
            groups.map { it.providerID },
        )
    }

    @Test
    fun showAllFlagsConnectedWithoutHiding() {
        val groups = ModelPickerItems.groups(v2Catalog, includeUnconnected = true)
        val byID = groups.associateBy { it.providerID }
        assertTrue(byID.getValue("xiaomi").connected)
        assertFalse(byID.getValue("opencode").connected)
        assertFalse(byID.getValue("opencode-go").connected)
    }

    @Test
    fun searchFindsUnconnectedProviderModels() {
        val groups = ModelPickerItems.groups(v2Catalog, includeUnconnected = true)
        val found = ModelPickerItems.filter(groups, "muse-spark")
        assertEquals(listOf("opencode-go", "opencode"), found.map { it.providerID })
        assertTrue(found.flatMap { it.models }.any { it.id == "muse-spark-1.3-contributor-free" })
    }

    @Test
    fun showAllStillHonorsVisibility() {
        val hidden = com.xiufeigo.opencodeshell.ui.settings.ModelVisibility.setVisible(
            emptyMap(), "opencode", "muse-spark-1.3-contributor-free", false,
        )
        val groups = ModelPickerItems.groups(v2Catalog, hidden, includeUnconnected = true)
        assertEquals(listOf("opencode-go", "stepfun", "xiaomi"), groups.map { it.providerID })
    }

    @Test
    fun legacyDefaultStaysConnectedOnly() {
        val groups = ModelPickerItems.groups(v2Catalog)
        assertEquals(listOf("xiaomi"), groups.map { it.providerID })
        assertTrue(ModelPickerItems.groups(null, includeUnconnected = true).isEmpty())
    }
}
