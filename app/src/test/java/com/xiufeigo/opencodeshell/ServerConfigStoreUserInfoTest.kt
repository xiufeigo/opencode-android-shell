package com.xiufeigo.opencodeshell

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * URL-embedded credentials (`http://user:pass@host`) for [ServerConfigStore].
 *
 * P1 fresh-install follow-up: a stored entry that carries its secret only in
 * the URL used to decode to a password-less row — every protected call then
 * sent no `Authorization` and 401d. The store now extracts the userinfo halves
 * (explicit `username`/`password` fields still win) and strips them from the
 * kept URL, so the credential travels only in the `Authorization` header.
 *
 * Deliberate deviation, documented: the connect *form* still rejects userinfo
 * URLs (`serverAddress` mirrors upstream `pairing.ts`, pinned by
 * `Wave5ConnectTest.credentialsQueryAndHashAreRejected`) — only the stored
 * shape is tolerated here.
 */
class ServerConfigStoreUserInfoTest {

    private fun activeOf(json: String) = ServerConfigStore.decodeStoreValue(json)!!.active!!

    @Test
    fun `userinfo halves are extracted and stripped from the url`() {
        val active = activeOf(
            """{"list":[{"type":"http","http":{"url":"http://ops:s3cret@10.0.2.2:4095"}}],"active":"http://10.0.2.2:4095"}""",
        )
        assertEquals("http://10.0.2.2:4095", active.url)
        assertEquals("ops", active.username)
        assertEquals("s3cret", active.password)
    }

    @Test
    fun `explicit fields win over the userinfo`() {
        val active = activeOf(
            """{"list":[{"type":"http","http":{"url":"http://ops:stale@10.0.2.2:4095","username":"opencode","password":"real"}}],"active":"http://10.0.2.2:4095"}""",
        )
        assertEquals("http://10.0.2.2:4095", active.url)
        assertEquals("opencode", active.username)
        assertEquals("real", active.password)
    }

    @Test
    fun `password-only userinfo falls back to the opencode username`() {
        val active = activeOf(
            """{"list":[{"type":"http","http":{"url":"http://:s3cret@10.0.2.2:4095"}}],"active":"http://10.0.2.2:4095"}""",
        )
        assertEquals("opencode", active.username)
        assertEquals("s3cret", active.password)
    }

    @Test
    fun `bare string entry keeps its userinfo secret`() {
        val active = activeOf(
            """{"list":["http://ops:s3cret@10.0.2.2:4095"],"active":"http://10.0.2.2:4095"}""",
        )
        assertEquals("http://10.0.2.2:4095", active.url)
        assertEquals("ops", active.username)
        assertEquals("s3cret", active.password)
    }

    @Test
    fun `legacy top-level url keeps its userinfo secret`() {
        val active = activeOf("""{"url":"http://ops:s3cret@10.0.2.2:4095"}""")!!
        assertEquals("http://10.0.2.2:4095", active.url)
        assertEquals("ops", active.username)
        assertEquals("s3cret", active.password)
    }

    @Test
    fun `splitUserInfo only looks at the authority section`() {
        assertEquals(
            Triple("http://host:4096/path@x", null, null),
            ServerConfigStore.splitUserInfo("http://host:4096/path@x"),
        )
        assertEquals(
            Triple("http://host?q=a@b", null, null),
            ServerConfigStore.splitUserInfo("http://host?q=a@b"),
        )
        assertEquals(
            Triple("http://[::1]:4096", null, null),
            ServerConfigStore.splitUserInfo("http://[::1]:4096"),
        )
        assertEquals(
            Triple("localhost:4096", null, null),
            ServerConfigStore.splitUserInfo("localhost:4096"),
        )
        assertEquals(
            Triple("http://host:4096", "alice", "s3cret"),
            ServerConfigStore.splitUserInfo("http://alice:s3cret@host:4096"),
        )
        assertEquals(
            Triple("http://host:4096/oc", "alice", "s3cret"),
            ServerConfigStore.splitUserInfo("http://alice:s3cret@host:4096/oc"),
        )
    }

    @Test
    fun `splitUserInfo percent-decodes the halves without touching plus`() {
        assertEquals(
            Triple("http://host", "a/b", "p@ss+a%zz"),
            ServerConfigStore.splitUserInfo("http://a%2Fb:p%40ss+a%zz@host"),
        )
    }

    @Test
    fun `encode round-trip never re-embeds the secret in the url`() {
        val decoded = activeOf(
            """{"list":[{"type":"http","http":{"url":"http://ops:s3cret@10.0.2.2:4095"}}],"active":"http://10.0.2.2:4095"}""",
        )
        val encoded = ServerConfigStore.encodeStoreValue(listOf(decoded), activeUrl = decoded.url)
        assertFalse("encoded wire must not carry userinfo", encoded.contains("@"))
        val roundTripped = activeOf(encoded)
        assertEquals("http://10.0.2.2:4095", roundTripped.url)
        assertEquals("ops", roundTripped.username)
        assertEquals("s3cret", roundTripped.password)
    }

    @Test
    fun `merge strips a stored userinfo url and materializes the fields`() {
        val persisted = ServerConfigStore.encodeStoreValue(
            listOf(ServerConfigStore.Server("http://other:4095", "opencode", "x")),
            activeUrl = "http://other:4095",
        )
        val echo = """{"list":[{"type":"http","http":{"url":"http://ops:s3cret@10.0.2.2:4095"}}],"active":"http://10.0.2.2:4095"}"""
        val merged = JSONObject(ServerConfigStore.mergeJsonRetainingCredentials(persisted, echo))
        val http = merged.getJSONArray("list").getJSONObject(0).getJSONObject("http")
        assertEquals("http://10.0.2.2:4095", http.getString("url"))
        assertEquals("s3cret", http.getString("password"))
        assertEquals("ops", http.getString("username"))
    }

    @Test
    fun `merge strips a bare userinfo string into the object shape`() {
        val persisted = ServerConfigStore.encodeStoreValue(
            listOf(ServerConfigStore.Server("http://other:4095", "opencode", "x")),
            activeUrl = "http://other:4095",
        )
        val echo = """{"list":["http://ops:s3cret@10.0.2.2:4095"],"active":"http://10.0.2.2:4095"}"""
        val merged = ServerConfigStore.mergeJsonRetainingCredentials(persisted, echo)
        assertTrue(merged.contains("\"type\":\"http\""))
        val active = activeOf(merged)
        assertEquals("http://10.0.2.2:4095", active.url)
        assertEquals("s3cret", active.password)
    }

    @Test
    fun `username-only userinfo keeps the name with no credential`() {
        val active = activeOf(
            """{"list":[{"type":"http","http":{"url":"http://ops@10.0.2.2:4095"}}],"active":"http://10.0.2.2:4095"}""",
        )
        assertEquals("ops", active.username)
        assertNull(active.password)
    }
}
