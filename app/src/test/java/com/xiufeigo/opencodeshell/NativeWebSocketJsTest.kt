package com.xiufeigo.opencodeshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NativeWebSocketJsTest {
    @Test
    fun polyfillPatchesWebsocketThroughNativeBridge() {
        val js = NativeWebSocketJs.INSTALL
        assertTrue(js.contains("window.WebSocket"))
        assertTrue(js.contains("OpenCodeAndroid.wsOpen"))
        assertTrue(js.contains("wsSendText"))
        assertTrue(js.contains("wsSendBinary"))
        assertTrue(js.contains("__ocNativeWS"))
        assertTrue(js.contains("127.0.0.1"))
        assertTrue(js.contains("arraybuffer"))
    }

    @Test
    fun hubOnlyAllowsLoopback() {
        assertTrue(NativeWebSocketHub.isLoopbackWs("ws://127.0.0.1:9/pty/x/connect"))
        assertTrue(NativeWebSocketHub.isLoopbackWs("ws://localhost:9/api/pty/x/connect"))
        assertFalse(NativeWebSocketHub.isLoopbackWs("ws://evil.example/pty/x/connect"))
        assertEquals("http://127.0.0.1:9/pty/x/connect", NativeWebSocketHub.toHttpUrl("ws://127.0.0.1:9/pty/x/connect"))
        assertEquals("https://127.0.0.1:9/pty", NativeWebSocketHub.toHttpUrl("wss://127.0.0.1:9/pty"))
        assertEquals("\"a\\nb\"", NativeWebSocketHub.jsString("a\nb"))
    }

    @Test
    fun activityInstallsNativeWebsocketBeforePageScripts() {
        val src = File("src/main/java/com/xiufeigo/opencodeshell/MainActivity.kt").readText()
        assertTrue(src.contains("NativeWebSocketJs.INSTALL"))
        assertTrue(src.contains("DOCUMENT_START_SCRIPT") || src.contains("addDocumentStartJavaScript"))
        val bridge = File("src/main/java/com/xiufeigo/opencodeshell/OpenCodeAndroidBridge.kt").readText()
        assertTrue(bridge.contains("fun wsOpen"))
        assertTrue(bridge.contains("fun wsSendBinary"))
    }
}
