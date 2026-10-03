package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.servers.prefillUrl
import com.xiufeigo.opencodeshell.ui.servers.requiresConnectScreen
import com.xiufeigo.opencodeshell.ui.servers.serversMissingCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * P1 fresh-install discovery: a synced URL with an empty password must be
 * flaggable as "needs a credential" instead of failing every protected call
 * with a silent 401 behind an empty Home.
 *
 * `requiresConnectScreen` deliberately stays false for a password-less
 * complete URL (a no-auth local server is legal upstream) — pinned here so the
 * gate is never "fixed" by hijacking those servers into the connect page. The
 * explicit prompt path is [serversMissingCredentials] + [prefillUrl], wired by
 * the `App.kt` gate / Home / overlay owners.
 */
class ServerConnectCredentialTest {

    private fun server(url: String, password: String?) =
        ServerConfigStore.Server(url, "opencode".takeIf { password != null }, password)

    @Test
    fun freshInstallPayloadDecodesToACredentialLessServer() {
        // The exact fresh-install shape: WebView syncs a bare URL, no secret.
        val active = ServerConfigStore.decodeStoreValue(
            """{"list":[{"type":"http","http":{"url":"http://10.0.2.2:4095"}}],"active":"http://10.0.2.2:4095"}""",
        )!!.active!!
        assertNull(active.password)
        assertEquals(listOf(active), serversMissingCredentials(listOf(active)))
    }

    @Test
    fun onlyCompleteUrlWithoutPasswordIsFlagged() {
        val missing = server("http://10.0.2.2:4095", null)
        val incomplete = ServerConfigStore.Server("localhost:4095", null, null)
        val healthy = server("http://10.0.2.2:4095", "opencode")
        assertEquals(listOf(missing), serversMissingCredentials(listOf(missing, incomplete, healthy)))
        assertEquals(emptyList<ServerConfigStore.Server>(), serversMissingCredentials(listOf(incomplete, healthy)))
        assertEquals(emptyList<ServerConfigStore.Server>(), serversMissingCredentials(emptyList()))
    }

    @Test
    fun prefillOffersTheFirstCredentialLessAddress() {
        val first = server("http://10.0.2.2:4095", null)
        val second = server("http://10.0.2.3:4095", null)
        assertEquals("http://10.0.2.2:4095", prefillUrl(listOf(first, second)))
        assertNull(prefillUrl(listOf(server("http://10.0.2.2:4095", "opencode"))))
        assertNull(prefillUrl(emptyList()))
    }

    @Test
    fun connectGateStillIgnoresThePassword() {
        // Password-less but complete ⇒ no gate: upstream servers without auth
        // are legal, and the credential prompt is a separate signal.
        assertFalse(requiresConnectScreen(listOf(server("http://a", null))))
    }
}
