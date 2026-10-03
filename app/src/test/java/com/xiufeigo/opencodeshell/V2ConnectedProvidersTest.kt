package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.v2.V2Api
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V2ConnectedProvidersTest {

    private fun provider(id: String, integrationID: String?): V2Api.ProviderInfo {
        val obj = JSONObject().put("id", id).put("name", id)
        if (integrationID != null) obj.put("integrationID", integrationID)
        return V2Api.ProviderInfo.fromJson(obj)
    }

    private fun integration(id: String, connections: List<String>?): V2Api.IntegrationInfo {
        val obj = JSONObject().put("id", id).put("name", id)
        if (connections != null) {
            val array = org.json.JSONArray()
            connections.forEach { array.put(JSONObject().put("type", it)) }
            obj.put("connections", array)
        }
        return V2Api.IntegrationInfo.fromJson(obj)
    }

    @Test
    fun linkedIntegrationWithConnectionIsConnected() {
        val connected = v2ConnectedProviderIds(
            listOf(provider("openai", "openai")),
            listOf(integration("openai", listOf("credential"))),
        )
        assertEquals(setOf("openai"), connected)
    }

    @Test
    fun emptyConnectionsIsNotConnected() {
        val connected = v2ConnectedProviderIds(
            listOf(provider("openai", "openai")),
            listOf(integration("openai", emptyList())),
        )
        assertTrue(connected.isEmpty())
    }

    @Test
    fun missingConnectionsKeyIsNotConnected() {
        val connected = v2ConnectedProviderIds(
            listOf(provider("openai", "openai")),
            listOf(integration("openai", null)),
        )
        assertTrue(connected.isEmpty())
    }

    @Test
    fun providerWithoutIntegrationIsNotConnected() {
        val connected = v2ConnectedProviderIds(
            listOf(provider("custom", null)),
            listOf(integration("openai", listOf("credential"))),
        )
        assertTrue(connected.isEmpty())
    }

    @Test
    fun unknownIntegrationIsNotConnected() {
        val connected = v2ConnectedProviderIds(
            listOf(provider("openai", "openai")),
            listOf(integration("anthropic", listOf("credential"))),
        )
        assertTrue(connected.isEmpty())
    }

    @Test
    fun mixedCatalogYieldsOnlyLiveIds() {
        val connected = v2ConnectedProviderIds(
            listOf(
                provider("openai", "openai"),
                provider("anthropic", "anthropic"),
                provider("custom", null),
            ),
            listOf(
                integration("openai", listOf("credential")),
                integration("anthropic", emptyList()),
            ),
        )
        assertEquals(setOf("openai"), connected)
    }
}
