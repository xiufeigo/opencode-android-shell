package com.xiufeigo.opencodeshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyPathTest {
    @Test
    fun roundTripEncodesBackendAsSingleSegment() {
        val token = "abc-token"
        val backend = "https://backend.opencode.xiufeigo.xyz"
        val path = "${ProxyPath.publicPrefix(token)}${ProxyPath.encodeBackend(backend)}/api/health"
        val parsed = ProxyPath.parse(path, "x=1", token)
        assertNotNull(parsed)
        assertEquals(backend, parsed!!.backendBase)
        assertEquals("/api/health", parsed.upstreamPath)
        assertEquals("x=1", parsed.query)
        assertEquals("$backend/api/health?x=1", parsed.upstreamUrl())
    }

    @Test
    fun rejectsWrongTokenAndNonHttp() {
        val token = "tok"
        val path = "${ProxyPath.publicPrefix(token)}${ProxyPath.encodeBackend("https://example.com")}/api/health"
        assertNull(ProxyPath.parse(path, null, "other"))
        assertNull(ProxyPath.parse("/opencode-proxy/tok/ftp%3A%2F%2Fx/api", null, "tok"))
        assertNull(ProxyPath.parse("/assets/opencode-app/index.html", null, token))
    }

    @Test
    fun offlineStubNeverMatchesProxyPrefix() {
        assertTrue(!ProxyPath.isProxyPath("http://127.0.0.1:0/api/health"))
        assertTrue(ProxyPath.isProxyPath("/opencode-proxy/x/"))
    }
}
