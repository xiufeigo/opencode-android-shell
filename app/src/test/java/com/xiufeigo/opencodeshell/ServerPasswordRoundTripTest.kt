package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.servers.probeTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

/**
 * P1 contract: the secret that goes in is the secret that comes out, and the
 * probe sends the same credential the save path keeps.
 *
 * The password chain is `AddEditServerDialog` → `SettingsServerBridge.replace` →
 * `ServerConfigStore.encodeStoreValue` → prefs → `decodeStoreValue` →
 * `basicAuthorizationHeader`. Every leg is pinned here so a truncation, an
 * escaping asymmetry or a probe/storage divergence fails the build instead of
 * showing up as a `401` in the field.
 */
class ServerPasswordRoundTripTest {

    private fun roundTrip(password: String?): ServerConfigStore.Server {
        val original = ServerConfigStore.Server(
            url = "http://10.0.2.2:4095",
            username = "opencode",
            password = password,
        )
        val decoded = ServerConfigStore.decodeStoreValue(
            ServerConfigStore.encodeStoreValue(listOf(original), activeUrl = original.url),
        )
        return requireNotNull(decoded?.active) { "round trip dropped the server" }
    }

    @Test
    fun passwordSurvivesTheWireRoundTripUnchanged() {
        for (password in listOf(
            "opencode",
            "p@ss w/rd:and;meta",
            "quote\"and\\backslash",
            "中文密码🔐",
            "line\nbreak\ttab",
            "a".repeat(512),
        )) {
            val decoded = roundTrip(password)
            assertEquals(password, decoded.password)
            assertEquals("opencode", decoded.username)
            assertEquals("http://10.0.2.2:4095", decoded.url)
        }
    }

    @Test
    fun emptyAndNullPasswordsBothReadAsNoCredential() {
        assertNull(roundTrip(null).password)
        assertNull(roundTrip("").password)
    }

    @Test
    fun basicHeaderEncodesExactlyUsernameColonPassword() {
        val header = requireNotNull(basicAuthorizationHeader("opencode", "opencode"))
        assertEquals("Basic b3BlbmNvZGU6b3BlbmNvZGU=", header)
        val decoded = String(
            Base64.getDecoder().decode(header.removePrefix("Basic ")),
            Charsets.UTF_8,
        )
        assertEquals("opencode:opencode", decoded)
        // Empty/null password ⇒ no header at all (upstream `health.ts:83`).
        assertNull(basicAuthorizationHeader("opencode", null))
        assertNull(basicAuthorizationHeader("opencode", ""))
    }

    @Test
    fun probeSendsTheStoredPasswordWhenTheFormDoesNotRetypeIt() {
        // Edit dialog: password box is blank, the stored secret must still be
        // probed — exactly the `typed ?: existing` rule `replace` saves with.
        val kept = probeTarget(
            url = "http://10.0.2.2:4095",
            username = "opencode",
            typedPassword = "",
            storedPassword = "opencode",
        )
        assertEquals("opencode", kept.password)

        // A typed password wins over the stored one.
        val retyped = probeTarget(
            url = "http://10.0.2.2:4095",
            username = "opencode",
            typedPassword = "new-secret",
            storedPassword = "opencode",
        )
        assertEquals("new-secret", retyped.password)

        // Nothing anywhere ⇒ no credential, which is what gets saved too.
        val anonymous = probeTarget(
            url = "http://10.0.2.2:4095",
            username = "  ",
            typedPassword = null,
            storedPassword = "",
        )
        assertNull(anonymous.password)
        assertNull(anonymous.username)
    }
}
