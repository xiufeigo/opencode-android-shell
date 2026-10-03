package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.palette.AgentDetails
import com.xiufeigo.opencodeshell.v2.V2Api
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `AgentDetails.rows` — the `GET /api/agent` catalog projection the picker renders. */
class AgentDetailsModelTest {

    private fun agent(json: String): V2Api.AgentInfo = V2Api.AgentInfo.fromJson(JSONObject(json))

    @Test
    fun rowsKeepServerOrderAndFlattenTheCatalogShape() {
        val rows = AgentDetails.rows(
            listOf(
                agent("""{"id":"build","name":"Build","description":"Build it","mode":"primary","system":"You build"}"""),
                agent("""{"id":"plan","name":"Plan","description":"Plan it","mode":"primary","hidden":true}"""),
            ),
        )
        assertEquals(listOf("build", "plan"), rows.map { it.id })
        assertEquals("Build", rows[0].name)
        assertEquals("primary", rows[0].mode)
        assertEquals("Build it", rows[0].description)
        assertTrue(rows[1].hidden)
        assertEquals("Plan it", rows[1].description)
    }

    @Test
    fun rowsTolerateMissingNameAndDescription() {
        val row = AgentDetails.rows(listOf(agent("""{"id":"solo"}"""))).single()
        assertEquals("solo", row.id)
        // `AgentInfo.fromJson` falls back to the id when `name` is absent.
        assertEquals("solo", row.name)
        assertEquals("", row.description)
        assertEquals("", row.mode)
        assertTrue(!row.hidden)
    }
}
