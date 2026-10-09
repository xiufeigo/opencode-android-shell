package com.xiufeigo.opencodeshell.web.host

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.util.concurrent.Executors

/** Official UI platform operations, restricted to our trusted main document. */
class AndroidPlatformHost(
    private val activity: AppCompatActivity,
    private val files: FileChooserHost,
) : AutoCloseable {
    private val worker = Executors.newSingleThreadExecutor()
    private val pairing = BrowserPairing(activity)
    @Volatile
    private var closed = false

    companion object {
        // Matches the official composer's inline attachment budget; larger files use its file picker/upload flow.
        const val MAX_CLIPBOARD_IMAGE_BYTES = 20 * 1024 * 1024
    }

    fun install(view: WebView) {
        check(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            "This Android WebView does not support the platform message channel"
        }
        WebViewCompat.addWebMessageListener(view, "__ocPlatform", setOf(LocalUiOriginServer.origin())) {
                _, message, _, isMainFrame, reply ->
            if (!isMainFrame || closed) return@addWebMessageListener
            val request = try {
                JSONObject(requireNotNull(message.data)).also { require(it.optString("id").isNotBlank()) { "Missing request id" } }
            } catch (error: Exception) {
                Log.e("OcAndroidPlatform", "Invalid platform request", error)
                return@addWebMessageListener
            }
            val id = request.getString("id")
            fun respond(value: Any? = null, error: Exception? = null) {
                val response = JSONObject().put("id", id)
                if (error != null) response.put("error", "${error.javaClass.simpleName}: ${error.message}")
                else response.put("value", value ?: JSONObject.NULL)
                activity.runOnUiThread { if (!closed) reply.postMessage(response.toString()) }
            }
            try {
                val payload = request.getJSONObject("payload")
                when (request.getString("method")) {
                    "browserConfig" -> respond(pairing.read())
                    "configureBrowser" -> pairing.configure { value, error -> respond(value, error) }
                    "openExternal" -> {
                        openExternal(payload.getString("url"))
                        respond(true)
                    }
                    "writeClipboardText" -> {
                        clipboard().setPrimaryClip(ClipData.newPlainText("OpenCode", payload.getString("text")))
                        respond(true)
                    }
                    "readClipboardImage" -> {
                        // Clipboard access must happen while the Activity has focus.
                        val uri = clipboard().primaryClip?.let { clip ->
                            if (clip.itemCount == 0) null else clip.getItemAt(0).uri
                        }
                        worker.execute {
                            try { respond(readImage(uri)) } catch (error: Exception) { respond(error = error) }
                        }
                    }
                    "saveFile" -> files.launchSave(
                        payload.getString("name"), "text/markdown",
                        Base64.encodeToString(payload.getString("content").toByteArray(Charsets.UTF_8), Base64.NO_WRAP),
                    ) { result ->
                        when {
                            result.optBoolean("cancelled") -> respond(false)
                            result.optBoolean("ok") -> respond(true)
                            else -> respond(error = IllegalStateException(result.getString("error")))
                        }
                    }
                    else -> throw IllegalArgumentException("Unknown platform method: ${request.getString("method")}")
                }
            } catch (error: Exception) {
                respond(error = error)
            }
        }
    }

    fun openExternal(url: String) {
        val uri = Uri.parse(url)
        require(uri.scheme?.lowercase() in setOf("https", "http", "mailto")) { "Unsupported external URL scheme" }
        if (uri.scheme != "mailto") require(!uri.host.isNullOrBlank()) { "External URL has no host" }
        activity.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
    }

    private fun clipboard() = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private fun readImage(uri: Uri?): JSONObject? {
        if (uri == null) return null
        require(uri.scheme == "content") { "Clipboard image must be a content URI" }
        val mime = activity.contentResolver.getType(uri) ?: return null
        if (!mime.startsWith("image/")) return null
        val bytes = activity.contentResolver.openInputStream(uri)?.use { ContentBytes.read(it, MAX_CLIPBOARD_IMAGE_BYTES) }
            ?: throw IllegalStateException("Cannot open clipboard image")
        return JSONObject().put("mime", mime).put("name", "clipboard.${mime.substringAfter('/')}")
            .put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
    }

    override fun close() {
        closed = true
        pairing.close()
        worker.shutdown()
    }
}
