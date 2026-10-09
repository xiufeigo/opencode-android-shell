package com.xiufeigo.opencodeshell.web.host

import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Java interfaces reach every frame. A capability given only to our main document confines the legacy sync API. */
class LegacyBridgeChannel(private val bridge: WebHostBridge) {
    private fun capability() = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
    private val token = capability()
    private val diagnosticToken = capability()
    private val diagnosticMethods = setOf("retry")
    @Volatile private var active = true
    private val methods = mapOf<String, (JSONArray) -> Any?>(
        "hostInfo" to { bridge.hostInfo() },
        "proxyOrigin" to { bridge.proxyOrigin() },
        "proxyPrefix" to { bridge.proxyPrefix() },
        "hasNotificationPermission" to { bridge.hasNotificationPermission() },
        "requestNotificationPermission" to { bridge.requestNotificationPermission(); null },
        "postMessage" to { args -> bridge.postMessage(args.text(0), args.text(1), args.text(2)) },
        "pickFile" to { args -> bridge.pickFile(args.text(0), args.getBoolean(1)); null },
        "readPickedFile" to { bridge.readPickedFile() },
        "clearPickedFile" to { bridge.clearPickedFile(); null },
        "saveContent" to { args -> bridge.saveContent(args.text(0), args.text(1), args.text(2)); null },
        "share" to { args -> bridge.share(args.text(0), args.text(1)) },
        "getSecret" to { args -> bridge.getSecret(args.text(0)) },
        "setSecret" to { args -> bridge.setSecret(args.text(0), args.text(1)) },
        "setKeepScreenOn" to { args -> bridge.setKeepScreenOn(args.getBoolean(0)); null },
        "setImeMode" to { args -> bridge.setImeMode(args.text(0)); null },
        "getImeMode" to { bridge.getImeMode() },
        "reportTheme" to { args -> bridge.reportTheme(args.getBoolean(0)); null },
        "onFirstContent" to { args -> bridge.onFirstContent(args.text(0), if (args.isNull(1)) null else args.getLong(1)); null },
        "retry" to { bridge.retry(); null },
    )

    @JavascriptInterface
    fun invoke(capability: String, method: String, arguments: String): String {
        val diagnostic = MessageDigest.isEqual(diagnosticToken.toByteArray(), capability.toByteArray())
        check(active && (diagnostic || MessageDigest.isEqual(token.toByteArray(), capability.toByteArray()))) { "Untrusted native bridge caller" }
        require(!diagnostic || method in diagnosticMethods) { "Diagnostic pages can only retry startup" }
        val operation = methods[method] ?: throw IllegalArgumentException("Unknown legacy bridge method: $method")
        val value = operation(JSONArray(arguments))
        return JSONObject().put("value", value ?: JSONObject.NULL).toString()
    }

    fun script(origins: Set<String>, diagnostic: Boolean = false): String {
        return """
            (() => {
              if (window !== window.top || !${JSONArray(origins.toList())}.includes(location.origin)) return;
              const raw = window.${WebHostBridge.INJECT_NAME};
              const token = ${JSONObject.quote(if (diagnostic) diagnosticToken else token)};
              const facade = {};
              for (const method of ${JSONArray((if (diagnostic) diagnosticMethods else methods.keys).toList())}) {
                facade[method] = (...args) => JSON.parse(raw.invoke(token, method, JSON.stringify(args))).value;
              }
              window.${WebHostBridge.INJECT_NAME} = Object.freeze(facade);
            })();
        """.trimIndent()
    }

    fun close() { active = false }
    private fun JSONArray.text(index: Int): String? = if (isNull(index)) null else getString(index)
}
