package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.settings.PairingModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The text pairing-code half of `servers/connect/pairing.ts` (v2 head):
 * `serverAddress` / `decodePairingCode` / `decodePairingUrl` / `decodePairingScan`
 * and `pairingUrl` (base64url, unpadded — `@opencode/util/encode.ts`).
 */
class PairingModelTest {

    /** `base64Encode(JSON.stringify({username, password}))` for `secret`. */
    private val secretCode = "eyJ1c2VybmFtZSI6Im9wZW5jb2RlIiwicGFzc3dvcmQiOiJzZWNyZXQifQ"

    @Test
    fun serverAddressAcceptsHttpBareHostsAndRejectsCredentialsOrSchemes() {
        assertEquals("http://10.0.2.2:4096", PairingModel.serverAddress("10.0.2.2:4096"))
        assertEquals("https://example.com", PairingModel.serverAddress("https://example.com/"))
        assertNull(PairingModel.serverAddress("ssh://example.com"))
        assertNull(PairingModel.serverAddress("http://user:pass@example.com"))
        assertNull(PairingModel.serverAddress("http://example.com?x=1"))
        assertNull(PairingModel.serverAddress("http://example.com#frag"))
        assertNull(PairingModel.serverAddress("   "))
    }

    @Test
    fun decodePairingCodeReadsTheSchemaAndDeduplicatesUrls() {
        val decoded = PairingModel.decodePairingCode(
            """{"username":"opencode","password":"secret","urls":["http://a:1","http://a:1/","ftp://nope","http://u:p@x"]}""",
        )
        assertEquals("secret", decoded?.password)
        assertEquals(listOf("http://a:1"), decoded?.urls)
    }

    @Test
    fun decodePairingCodeDefaultsUrlsToTheOriginWhenOmitted() {
        // v2 made `urls` optional (`pairing.ts` `Schema.optional`).
        val decoded = PairingModel.decodePairingCode(
            """{"username":"opencode","password":"secret"}""",
            origin = "http://host:4096",
        )
        assertEquals(listOf("http://host:4096"), decoded?.urls)
        assertNull(PairingModel.decodePairingCode("""{"username":"opencode","password":"secret"}"""))
    }

    @Test
    fun decodePairingCodeRejectsForeignUsernamesAndMissingPasswords() {
        assertNull(PairingModel.decodePairingCode("""{"username":"root","password":"x","urls":["http://a:1"]}"""))
        assertNull(PairingModel.decodePairingCode("""{"username":"opencode","urls":["http://a:1"]}"""))
        assertNull(PairingModel.decodePairingCode("not json"))
    }

    @Test
    fun decodePairingUrlParsesTheBase64Fragment() {
        // The code carries no `urls`, so an origin is required (upstream
        // `decodePairingCode(value, origin)` fills `urls` from it and refuses
        // to guess otherwise).
        assertNull(PairingModel.decodePairingUrl("#$secretCode"))
        val decoded = PairingModel.decodePairingUrl("#$secretCode", "http://host:1")
        assertEquals("secret", decoded?.password)
        assertEquals(listOf("http://host:1"), decoded?.urls)
    }

    @Test
    fun decodePairingUrlParsesTheDataQueryParamAndRawJson() {
        val json = """{"username":"opencode","password":"s","urls":["http://a:1"]}"""
        assertEquals("s", PairingModel.decodePairingUrl("?data=${java.net.URLEncoder.encode(json, "UTF-8")}")?.password)
        assertEquals("s", PairingModel.decodePairingUrl(json)?.password)
    }

    @Test
    fun decodePairingUrlRejectsGarbageAndBadPadding() {
        assertNull(PairingModel.decodePairingUrl("#not_base64!"))
        assertNull(PairingModel.decodePairingUrl("#abcde")) // length % 4 == 1
        assertNull(PairingModel.decodePairingUrl("#"))
    }

    @Test
    fun pairingUrlRoundTripsThroughDecodePairingScan() {
        val url = PairingModel.pairingUrl("secret", "http://10.0.2.2:4096")!!
        assertEquals("http://10.0.2.2:4096/connect#$secretCode", url)
        val scanned = PairingModel.decodePairingScan(url)
        assertEquals("secret", scanned?.password)
        assertEquals(listOf("http://10.0.2.2:4096"), scanned?.urls)
    }

    @Test
    fun decodePairingScanAlsoAcceptsTheRawCode() {
        assertEquals(
            "secret",
            PairingModel.decodePairingScan("""{"username":"opencode","password":"secret","urls":["http://a:1"]}""")?.password,
        )
        // A bare code without `urls` has no origin to fall back on — upstream's
        // `decodePairingScan` refuses to invent one.
        assertNull(PairingModel.decodePairingScan(secretCode))
    }

    @Test
    fun pairingUrlEscapesPasswordsWithQuotesAndBackslashes() {
        val url = PairingModel.pairingUrl("""p\w"x""", "http://h:1")!!
        assertEquals("""p\w"x""", PairingModel.decodePairingScan(url)?.password)
    }

    @Test
    fun pairingOriginIsTheHostWithoutTheConnectPath() {
        assertEquals(
            "http://10.0.2.2:4096",
            PairingModel.pairingOrigin("http://10.0.2.2:4096/connect#$secretCode"),
        )
    }
}
