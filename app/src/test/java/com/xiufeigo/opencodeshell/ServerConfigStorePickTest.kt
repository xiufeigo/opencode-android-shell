package com.xiufeigo.opencodeshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Active-row pick contract for [ServerConfigStore.pickActive] — the rule that
 * decides which credentials `restore()`/`freshServer()` hand to the generation
 * probe.
 *
 * Root cause pinned here (3-device P0, 2026-09-24): upstream's `StoredServer`
 * union allows a bare URL string next to `{type:"http",http:{url,password}}`,
 * so one address can appear twice. The old first-match-then-break pick took the
 * bare row and dropped the password — the probe then sent no `Authorization`,
 * got `401`, and decided `UNKNOWN`, while `listServers()` (all rows) still
 * showed the credential-bearing twin as healthy in the Servers panel.
 */
class ServerConfigStorePickTest {

    private val bare = ServerConfigStore.Server("http://127.0.0.1:4095", null, null)
    private val withSecret = ServerConfigStore.Server("http://127.0.0.1:4095", "opencode", "s3cret")
    private val other = ServerConfigStore.Server("http://10.0.0.2:4095", null, null)

    private fun pick(entries: List<ServerConfigStore.Server>, activeKey: String) =
        ServerConfigStore.pickActive(entries, activeKey)

    @Test
    fun `credential-bearing twin wins when the active url appears twice`() {
        // The exact measured shape: bare string row first, secret-bearing row second.
        val picked = pick(listOf(bare, withSecret), "http://127.0.0.1:4095")
        assertEquals("s3cret", picked!!.password)
        assertEquals("opencode", picked.username)
    }

    @Test
    fun `order of the twins does not matter`() {
        val picked = pick(listOf(withSecret, bare), "http://127.0.0.1:4095")
        assertEquals("s3cret", picked!!.password)
    }

    @Test
    fun `active key still selects the url - first usable wins the url choice`() {
        // No active key: the first usable row's URL is chosen, never a later one.
        assertEquals(
            "http://127.0.0.1:4095",
            pick(listOf(bare, other), "")!!.url,
        )
        assertEquals(
            "http://10.0.0.2:4095",
            pick(listOf(bare, other), "http://10.0.0.2:4095")!!.url,
        )
    }

    @Test
    fun `credential preference never jumps to a different url`() {
        // `other` has no secret and `withSecret` is a different address — the
        // active pick must stay on `other` and accept its missing password.
        val picked = pick(listOf(other, withSecret), "http://10.0.0.2:4095")
        assertEquals("http://10.0.0.2:4095", picked!!.url)
        assertNull(picked.password)
    }

    @Test
    fun `unknown active key falls back to the first usable entry`() {
        assertEquals(
            "http://127.0.0.1:4095",
            pick(listOf(bare, other), "http://gone:1")!!.url,
        )
    }

    @Test
    fun `empty list picks nothing`() {
        assertNull(pick(emptyList(), "http://127.0.0.1:4095"))
    }
}
