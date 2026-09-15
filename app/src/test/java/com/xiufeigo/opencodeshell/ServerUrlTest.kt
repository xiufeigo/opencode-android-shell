package com.xiufeigo.opencodeshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUrlTest {
    @Test
    fun parsesLanHttpUrl() {
        val result = ServerUrl.parse(" http://192.168.1.10:4096/ ")
        assertEquals("http://192.168.1.10:4096", result.getOrThrow().value)
    }

    @Test
    fun rejectsMissingScheme() {
        val result = ServerUrl.parse("192.168.1.10:4096")
        assertTrue(result.isFailure)
    }

    @Test
    fun rejectsBlank() {
        val result = ServerUrl.parse("   ")
        assertTrue(result.isFailure)
    }

    @Test
    fun acceptsHttps() {
        val result = ServerUrl.parse("https://opencode.example.com")
        assertEquals("https://opencode.example.com", result.getOrThrow().value)
    }
}
