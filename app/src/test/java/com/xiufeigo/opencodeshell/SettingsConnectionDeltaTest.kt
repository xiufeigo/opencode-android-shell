package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.settings.ProviderRow
import com.xiufeigo.opencodeshell.ui.settings.applyProviderConnectionDelta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsConnectionDeltaTest {

    private fun row(id: String, connected: Boolean) =
        ProviderRow(id = id, name = id, connected = connected, modelCount = 3)

    @Test
    fun emptyDeltaKeepsEveryRow() {
        val rows = listOf(row("a", true), row("b", false))
        assertEquals(rows, applyProviderConnectionDelta(rows, emptyMap()))
    }

    @Test
    fun disconnectMarksOnlyThatRow() {
        val rows = applyProviderConnectionDelta(
            listOf(row("a", true), row("b", true)),
            mapOf("a" to false),
        )
        assertFalse(rows.first { it.id == "a" }.connected)
        assertTrue(rows.first { it.id == "b" }.connected)
    }

    @Test
    fun connectMarksPopularRowWithoutTouchingTheRest() {
        val rows = applyProviderConnectionDelta(
            listOf(row("a", true), row("b", false)),
            mapOf("b" to true),
        )
        assertTrue(rows.first { it.id == "a" }.connected)
        assertTrue(rows.first { it.id == "b" }.connected)
    }

    @Test
    fun unknownIdsInDeltaAreIgnored() {
        val rows = listOf(row("a", true))
        assertEquals(rows, applyProviderConnectionDelta(rows, mapOf("zzz" to false)))
    }

    @Test
    fun headerCountFollowsTheDelta() {
        val rows = applyProviderConnectionDelta(
            listOf(row("a", true), row("b", true)),
            mapOf("b" to false),
        )
        assertEquals(1, rows.count { it.connected })
    }
}
