package com.xiufeigo.opencodeshell.web.host

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebOriginsTest {
    @Test
    fun sameOriginIgnoresPathAndTrailingSlash() {
        assertTrue(WebOrigins.sameOrigin("http://127.0.0.1:4173/", "http://127.0.0.1:4173/settings/server"))
    }

    @Test
    fun defaultPortsAreExplicitPortEquivalents() {
        assertTrue(WebOrigins.sameOrigin("http://example.test/", "http://example.test:80/a"))
        assertTrue(WebOrigins.sameOrigin("https://example.test/", "https://example.test:443/a"))
    }

    @Test
    fun schemeHostAndPortMustMatch() {
        assertFalse(WebOrigins.sameOrigin("http://example.test/", "https://example.test/"))
        assertFalse(WebOrigins.sameOrigin("http://example.test/", "http://evil.test/"))
        assertFalse(WebOrigins.sameOrigin("http://example.test:4173/", "http://example.test:4174/"))
    }

    @Test
    fun malformedAndSubstringOriginsAreNotTrusted() {
        assertFalse(WebOrigins.sameOrigin("not a url", "http://127.0.0.1:4173"))
        assertFalse(WebOrigins.sameOrigin("https://evil.test/?x=127.0.0.1", "http://127.0.0.1:4173"))
    }
}
