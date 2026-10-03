package com.xiufeigo.opencodeshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Wire decode contract for [ServerConfigStore] ([ServerConfigStore.decodeStoreValue]).
 * The store itself needs an Android `Context`, so the pure payload rules are
 * pinned here: a legal `{"list":[]}` — deleting the last server — must decode
 * so `updateFromJson` persists it, while illegal payloads keep rejecting.
 * The store's own write path (`replaceAll`) emits exactly the shape
 * `SettingsStore.encodeServerList` produces, which is exercised too.
 */
class ServerConfigStoreWireTest {

    private fun decode(json: String) = ServerConfigStore.decodeStoreValue(json)

    @Test
    fun `empty list store is legal with no active server`() {
        // The exact payload ServerConfigStore.replaceAll(emptyList(), null) builds.
        val decoded = decode("""{"list":[]}""")
        assertNotNull("deleting the last server must be writable", decoded)
        assertNull(decoded!!.active)

        val encoded = SettingsStore.encodeServerList(emptyList(), activeUrl = null)
        assertNotNull(decode(encoded))
        assertNull(decode(encoded)!!.active)
    }

    @Test
    fun `list with only unusable entries is still a legal store value`() {
        // Same completeness rule listServers() applies — no scheme, so no
        // active server, but the payload itself is valid.
        val decoded = decode("""{"list":["localhost:4096"]}""")
        assertNotNull(decoded)
        assertNull(decoded!!.active)
    }

    @Test
    fun `illegal payloads are rejected`() {
        assertNull(decode("not json"))
        assertNull(decode("""{"foo":1}"""))
        // Legacy shape, but the url is not http(s) — same rule as before.
        assertNull(decode("""{"url":"ftp://host"}"""))
        assertNull(decode("{}"))
    }

    @Test
    fun `active entry is picked with the upstream basic-auth defaults`() {
        val decoded = decode(
            """{"list":[{"type":"http","http":{"url":"http://a","password":"s"}}],"active":"http://a"}""",
        )
        val active = decoded!!.active
        assertNotNull(active)
        assertEquals("http://a", active!!.url)
        assertEquals("opencode", active.username)
        assertEquals("s", active.password)
    }

    @Test
    fun `without an active key the first usable entry wins`() {
        assertEquals(
            "http://a",
            decode("""{"list":["http://a","http://b"]}""")!!.active!!.url,
        )
        assertEquals(
            "http://b",
            decode("""{"list":["http://a","http://b"],"active":"http://b"}""")!!.active!!.url,
        )
    }

    @Test
    fun `legacy top-level object still decodes`() {
        val active = decode("""{"url":"http://legacy:4096","password":"pw"}""")!!.active
        assertNotNull(active)
        assertEquals("http://legacy:4096", active!!.url)
        assertEquals("opencode", active.username)
        assertEquals("pw", active.password)
    }
}
