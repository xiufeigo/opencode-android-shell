package com.xiufeigo.opencodeshell

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire codec contract for the [ServerConfigStore] SSH redial recipes
 * ([ServerConfigStore.encodeSshEntries] /
 * [ServerConfigStore.decodeSshEntries]): `kind:"ssh"` + target/authKind/key
 * path round-trip, corrupt rows never brick the list, and no secret material
 * ever lands on the wire.
 */
class ServerConfigStoreSshTest {

    private val passwordEntry = ServerConfigStore.SshEntry(
        id = "home-lab",
        target = "ssh dev@192.168.1.10",
        name = "Home lab",
        authKind = ServerConfigStore.SshAuthKind.PASSWORD,
        lastLocalUrl = "http://127.0.0.1:41234",
    )
    private val keyEntry = ServerConfigStore.SshEntry(
        id = "cloud",
        target = "ssh -p 2222 admin@example.com",
        name = "",
        authKind = ServerConfigStore.SshAuthKind.KEY,
        privateKeyPath = "~/.ssh/id_ed25519",
    )

    private fun decode(json: String?) = ServerConfigStore.decodeSshEntries(json)

    @Test
    fun `entries round-trip through the wire`() {
        val decoded = decode(ServerConfigStore.encodeSshEntries(listOf(passwordEntry, keyEntry)))
        assertEquals(listOf(passwordEntry, keyEntry), decoded)
    }

    @Test
    fun `wire carries kind ssh and the authKind literals`() {
        val obj = JSONObject(ServerConfigStore.encodeSshEntries(listOf(passwordEntry, keyEntry)))
        val rows = obj.getJSONArray("entries")
        assertEquals("ssh", rows.getJSONObject(0).getString("kind"))
        assertEquals("password", rows.getJSONObject(0).getString("authKind"))
        assertEquals("key", rows.getJSONObject(1).getString("authKind"))
        assertEquals("~/.ssh/id_ed25519", rows.getJSONObject(1).getString("privateKeyPath"))
        assertEquals("http://127.0.0.1:41234", rows.getJSONObject(0).getString("lastLocalUrl"))
    }

    @Test
    fun `no secret material is ever encoded`() {
        val rows = JSONObject(ServerConfigStore.encodeSshEntries(listOf(passwordEntry, keyEntry)))
            .getJSONArray("entries")
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            assertFalse(row.has("password"))
            assertFalse(row.has("passphrase"))
        }
    }

    @Test
    fun `optional fields are omitted when absent`() {
        val minimal = passwordEntry.copy(lastLocalUrl = null)
        val row = JSONObject(ServerConfigStore.encodeSshEntries(listOf(minimal)))
            .getJSONArray("entries").getJSONObject(0)
        assertFalse(row.has("privateKeyPath"))
        assertFalse(row.has("lastLocalUrl"))
        assertNull(decode(ServerConfigStore.encodeSshEntries(listOf(minimal)))[0].lastLocalUrl)
    }

    @Test
    fun `corrupt rows are skipped while valid ones survive`() {
        val json = """{"entries":[
            {"kind":"ssh","id":"ok","target":"ssh a@b","name":"","authKind":"password"},
            {"kind":"ssh","id":"bad-auth","target":"ssh a@b","name":"","authKind":"token"},
            {"kind":"ssh","id":"","target":"ssh a@b","name":"","authKind":"password"},
            {"kind":"ssh","id":"no-target","target":"","name":"","authKind":"key"},
            {"kind":"vnc","id":"wrong-kind","target":"ssh a@b","name":"","authKind":"password"},
            "just-a-string",
            {"kind":"ssh","id":"ok-key","target":"ssh c@d","name":"n","authKind":"key","privateKeyPath":"~/.ssh/k"}
        ]}"""
        val decoded = decode(json)
        assertEquals(listOf("ok", "ok-key"), decoded.map { it.id })
        assertEquals("~/.ssh/k", decoded[1].privateKeyPath)
    }

    @Test
    fun `missing kind is tolerated as ssh`() {
        val decoded = decode("""{"entries":[{"id":"k","target":"ssh a@b","name":"","authKind":"key"}]}""")
        assertEquals(1, decoded.size)
        assertEquals(ServerConfigStore.SshAuthKind.KEY, decoded[0].authKind)
    }

    @Test
    fun `empty and malformed payloads decode to no entries`() {
        assertTrue(decode(null).isEmpty())
        assertTrue(decode("").isEmpty())
        assertTrue(decode("not json").isEmpty())
        assertTrue(decode("""{"entries":[]}""").isEmpty())
        assertTrue(decode("""{"other":[]}""").isEmpty())
    }

    @Test
    fun `empty round-trip stays empty`() {
        assertTrue(decode(ServerConfigStore.encodeSshEntries(emptyList())).isEmpty())
    }
}
