package com.xiufeigo.opencodeshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ConnectCorsTest {
    @Test
    fun androidWebViewOriginIsAssetLoaderHttps() {
        assertEquals("appassets.androidplatform.net", MainActivity.ASSET_HOST)
        assertEquals("https://appassets.androidplatform.net", MainActivity.ANDROID_WEBVIEW_ORIGIN)
    }

    @Test
    fun offlineStubIsOnlyLoopbackPortZero() {
        assertTrue(MainActivity.isOfflineDisconnectStub("127.0.0.1", 0))
        assertTrue(MainActivity.isOfflineDisconnectStub("localhost", 0))
        assertTrue(MainActivity.isOfflineDisconnectStub("::1", 0))
        assertFalse(MainActivity.isOfflineDisconnectStub("127.0.0.1", 4096))
        assertFalse(MainActivity.isOfflineDisconnectStub("localhost", -1))
        assertFalse(MainActivity.isOfflineDisconnectStub("backend.opencode.xiufeigo.xyz", 443))
        assertFalse(MainActivity.isOfflineDisconnectStub("backend.opencode.xiufeigo.xyz", -1))
        assertFalse(MainActivity.isOfflineDisconnectStub("backend.opencode.xiufeigo.xyz", 0))
        assertFalse(MainActivity.isOfflineDisconnectStub(null, 0))
    }

    @Test
    fun networkSecurityTrustsSystemAndUserCas() {
        val xml = File("src/main/res/xml/network_security_config.xml").readText()
        assertTrue(xml.contains("cleartextTrafficPermitted=\"true\""))
        assertTrue(xml.contains("certificates src=\"system\""))
        assertTrue("user-installed CAs must be trusted for private HTTPS", xml.contains("certificates src=\"user\""))
    }

    @Test
    fun interceptLetsLoopbackGatewayThrough() {
        val src = File("src/main/java/com/xiufeigo/opencodeshell/MainActivity.kt").readText()
        assertTrue(src.contains("disconnectedStub(request.url)"))
        assertTrue(src.contains("assetLoader.shouldInterceptRequest(request.url)"))
        assertTrue(src.contains("isLocalGateway"))
        assertTrue(src.contains("OpenCodeAndroid"))
        val gateway = File("src/main/java/com/xiufeigo/opencodeshell/LocalGateway.kt").readText()
        val bridge = File("src/main/java/com/xiufeigo/opencodeshell/OpenCodeAndroidBridge.kt").readText()
        assertTrue("JS must be able to bind a dedicated origin per backend", bridge.contains("proxyBaseUrl"))
        assertTrue(gateway.contains("originFor"))
        assertTrue(gateway.contains("OkHttpClient"))
        assertTrue(gateway.contains("copyStreaming"))
        assertTrue(src.contains("MIXED_CONTENT_ALWAYS_ALLOW"))
        assertTrue(src.contains("setAcceptThirdPartyCookies"))
    }

    @Test
    fun vendoredBundleUsesNativeProxyNotRemoteCors() {
        val html = File("src/main/assets/opencode-app/index.html").readText()
        val jsName =
            Regex("""src="\./assets/(index-[^"]+\.js)"""").find(html)?.groupValues?.get(1)
                ?: error("missing entry script")
        val assets = File("src/main/assets/opencode-app/assets")
        val js = File(assets, jsName).readText()
        assertTrue(js.contains("https://appassets.androidplatform.net") || js.contains("appassets.androidplatform.net"))
        val allJs = assets.listFiles { _, name -> name.endsWith(".js") }!!.joinToString("\n") { it.readText() }
        assertTrue("health check must use official /api/health", allJs.contains("/api/health"))
        assertTrue("health check must fall back to /global/health", allJs.contains("/global/health"))
        assertTrue(allJs.contains("opencode-proxy") || allJs.contains("androidProxyBaseUrl") || allJs.contains("proxyPrefix"))
        assertTrue("SDK must rewrite onto the native proxy", allJs.contains("androidProxyBaseUrl") || allJs.contains("opencode-proxy"))
        assertTrue("v1 new URL join needs dedicated origin or prefix restore", allJs.contains("proxyBaseUrl"))
        assertTrue("PTY websocket URL must go through the Android proxy", allJs.contains("/pty/") && allJs.contains("connect"))
        assertTrue("remote file APIs must be in the bundle", allJs.contains("/file") && allJs.contains("/find"))
        assertTrue("v2 filesystem APIs must be in the bundle", allJs.contains("/api/fs/list") && allJs.contains("/api/fs/find"))
        assertTrue("v2 PTY connect must be in the bundle", allJs.contains("/api/pty/") && allJs.contains("connect"))
        assertTrue("event streams must be in the bundle", allJs.contains("/api/event") && allJs.contains("/global/event"))
        assertTrue("MCP and permission APIs must be in the bundle", allJs.contains("/api/mcp") && allJs.contains("/permission"))
        val zh = assets.listFiles { _, name -> name.startsWith("zh-") && name.endsWith(".js") }
            ?.joinToString("\n") { it.readText() }
            ?: ""
        assertTrue(
            "Chinese add-server copy must mention native proxy, not required CORS",
            zh.contains("反向代理") || zh.contains("无需") || zh.contains("代理"),
        )
    }

    @Test
    fun readmeDocumentsNativeProxyDefault() {
    val readme =
        listOf(File("../README.md"), File("README.md"), File("../../README.md")).firstOrNull { it.isFile }
            ?: error("README.md not found")
    val text = readme.readText()
    assertTrue(text.contains("opencode-proxy") || text.contains("reverse-prox"))
    assertTrue(text.contains("127.0.0.1"))
    assertTrue(text.contains("NOT required") || text.contains("does not need"))
    assertFalse(text.contains("If you skip CORS, the app shows"))
    }
}
