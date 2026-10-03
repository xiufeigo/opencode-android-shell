package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.v2.ServerGeneration
import com.xiufeigo.opencodeshell.v2.V2Capability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `401`/`403` is an **auth wall**, not a generation verdict (3-device P0,
 * 2026-09-24: the probe sent no `Authorization`, curl with the stored Basic
 * secret got `200 {"version":"2.0.11"}` on the same URL, and the app still
 * claimed 「requires OpenCode 2.x server」). [V2Capability.decideInfo] must tag
 * the wall so `ChatViewModel.setGeneration` can say 「需要认证」 instead.
 */
class V2CapabilityAuthWallTest {

    @Test
    fun `bare 401 is an auth wall with no credential on the wire`() {
        val result = V2Capability.decideInfo(401, "application/json", "{}", credentialsSent = false)
        assertEquals(ServerGeneration.UNKNOWN, result.generation)
        assertTrue(result.authWall)
        assertFalse(result.credentialsSent)
        assertEquals(401, result.httpStatus)
    }

    @Test
    fun `rejected credential is still an auth wall but records the attempt`() {
        val result = V2Capability.decideInfo(401, "application/json", "{}", credentialsSent = true)
        assertEquals(ServerGeneration.UNKNOWN, result.generation)
        assertTrue(result.authWall)
        assertTrue(result.credentialsSent)
    }

    @Test
    fun `403 rides the same wall as 401`() {
        assertTrue(V2Capability.decideInfo(403, null, "", credentialsSent = false).authWall)
    }

    @Test
    fun `non-auth failures never claim an auth wall`() {
        for (result in listOf(
            V2Capability.decideInfo(500, "text/html", "boom"),
            V2Capability.decideInfo(404, "", ""),
            V2Capability.decideInfo(200, "text/html", "<!doctype html>"),
            V2Capability.decideInfo(200, "application/json", """{"version":"1.18.31"}"""),
        )) {
            assertEquals(ServerGeneration.UNKNOWN, result.generation)
            assertFalse("HTTP ${result.httpStatus} must not read as an auth wall", result.authWall)
        }
    }

    @Test
    fun `a confirmed v2 answer is never an auth wall`() {
        val v2 = V2Capability.decideInfo(
            200,
            "application/json",
            """{"version":"2.0.11"}""",
            credentialsSent = true,
        )
        assertEquals(ServerGeneration.V2, v2.generation)
        assertFalse(v2.authWall)
    }
}
