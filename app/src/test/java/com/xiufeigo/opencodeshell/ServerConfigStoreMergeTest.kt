package com.xiufeigo.opencodeshell

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Credential-retain merge for [ServerConfigStore.mergeCredentials] /
 * [ServerConfigStore.mergeJsonRetainingCredentials] — the WebView sync
 * direction (`syncServerConfig`) must not wipe a persisted secret when the
 * echoed payload carries the same URL without one, while genuinely new
 * secrets and deletions still apply.
 */
class ServerConfigStoreMergeTest {

    private val persisted = ServerConfigStore.encodeStoreValue(
        listOf(
            ServerConfigStore.Server("http://127.0.0.1:4095", "opencode", "s3cret"),
            ServerConfigStore.Server("http://10.0.0.2:4095", "legacy", "old"),
        ),
        activeUrl = "http://127.0.0.1:4095",
    )

    private fun merge(previousRaw: String?, json: String) =
        ServerConfigStore.mergeJsonRetainingCredentials(previousRaw, json)

    private fun activeOf(json: String) = ServerConfigStore.decodeStoreValue(json)!!.active!!

    @Test
    fun `stale secret-less echo inherits the persisted password and username`() {
        val echo = """{"list":[{"type":"http","http":{"url":"http://127.0.0.1:4095","password":""}}],"active":"http://127.0.0.1:4095"}"""
        val active = activeOf(merge(persisted, echo))
        assertEquals("s3cret", active.password)
        assertEquals("opencode", active.username)
    }

    @Test
    fun `custom username is retained alongside the password`() {
        val echo = """{"list":[{"type":"http","http":{"url":"http://10.0.0.2:4095"}}],"active":"http://10.0.0.2:4095"}"""
        val active = activeOf(merge(persisted, echo))
        assertEquals("old", active.password)
        assertEquals("legacy", active.username)
    }

    @Test
    fun `a genuinely new password in the sync payload wins`() {
        val rotated = """{"list":[{"type":"http","http":{"url":"http://127.0.0.1:4095","password":"fresh"}}],"active":"http://127.0.0.1:4095"}"""
        val active = activeOf(merge(persisted, rotated))
        assertEquals("fresh", active.password)
    }

    @Test
    fun `a url deleted from the sync payload stays deleted`() {
        val echo = """{"list":[{"type":"http","http":{"url":"http://10.0.0.2:4095"}}],"active":"http://10.0.0.2:4095"}"""
        val merged = ServerConfigStore.parseAll(merge(persisted, echo))
        assertEquals(1, merged.size)
        assertEquals("http://10.0.0.2:4095", merged[0].url)
        assertEquals("old", merged[0].password)
    }

    @Test
    fun `bare-string entry is rescued with the persisted secret`() {
        val echo = """{"list":["http://127.0.0.1:4095"],"active":"http://127.0.0.1:4095"}"""
        val merged = merge(persisted, echo)
        assertTrue(merged.contains("\"type\":\"http\""))
        assertEquals("s3cret", activeOf(merged).password)
    }

    @Test
    fun `unknown keys survive the merge untouched`() {
        val echo = """{"list":[{"type":"http","http":{"url":"http://127.0.0.1:4095"},"displayName":"local","authToken":true}],"active":"http://127.0.0.1:4095","hidden":{"http://127.0.0.1:4095":false},"projects":{}}"""
        val obj = JSONObject(merge(persisted, echo))
        assertEquals("s3cret", obj.getJSONArray("list").getJSONObject(0).getJSONObject("http").getString("password"))
        assertEquals("local", obj.getJSONArray("list").getJSONObject(0).getString("displayName"))
        assertTrue(obj.getJSONArray("list").getJSONObject(0).getBoolean("authToken"))
        assertTrue(obj.has("hidden"))
        assertTrue(obj.has("projects"))
    }

    @Test
    fun `active key of the sync payload is preserved`() {
        val echo = """{"list":[{"type":"http","http":{"url":"http://10.0.0.2:4095"}},{"type":"http","http":{"url":"http://127.0.0.1:4095"}}],"active":"http://10.0.0.2:4095"}"""
        assertEquals("http://10.0.0.2:4095", activeOf(merge(persisted, echo)).url)
    }

    @Test
    fun `legacy top-level object inherits the persisted secret`() {
        val echo = """{"url":"http://127.0.0.1:4095"}"""
        val active = activeOf(merge(persisted, echo))
        assertEquals("s3cret", active.password)
        assertEquals("opencode", active.username)
    }

    @Test
    fun `nothing to inherit returns the identical string`() {
        val fresh = """{"list":[{"type":"http","http":{"url":"http://127.0.0.1:4095","username":"opencode","password":"s3cret"}}],"active":"http://127.0.0.1:4095"}"""
        assertSame(fresh, merge(persisted, fresh))
    }

    @Test
    fun `missing or corrupt previous payload disables the merge`() {
        val echo = """{"list":[{"type":"http","http":{"url":"http://127.0.0.1:4095"}}],"active":"http://127.0.0.1:4095"}"""
        assertSame(echo, merge(null, echo))
        assertSame(echo, merge("not json", echo))
        assertNull(activeOf(merge(null, echo)).password)
    }

    @Test
    fun `pure merge prefers the credential-bearing donor twin`() {
        val bare = ServerConfigStore.Server("http://127.0.0.1:4095", null, null)
        val secret = ServerConfigStore.Server("http://127.0.0.1:4095", "opencode", "s3cret")
        val merged = ServerConfigStore.mergeCredentials(listOf(bare, secret), listOf(bare))
        assertEquals("s3cret", merged[0].password)
    }

    @Test
    fun `pure merge leaves unknown urls alone`() {
        val stranger = ServerConfigStore.Server("http://new:4095", null, null)
        val merged = ServerConfigStore.mergeCredentials(
            listOf(ServerConfigStore.Server("http://127.0.0.1:4095", "opencode", "s3cret")),
            listOf(stranger),
        )
        assertEquals(stranger, merged[0])
    }
}
