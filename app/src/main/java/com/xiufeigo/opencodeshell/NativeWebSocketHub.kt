package com.xiufeigo.opencodeshell

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.decodeBase64
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * OkHttp WebSocket client used by the injected JS polyfill. Connects to the
 * LocalGateway loopback URL the page already built (`ws://127.0.0.1:<port>/pty/...`).
 */
class NativeWebSocketHub(
    private val webView: WebView,
    private val client: OkHttpClient = defaultClient(),
) {
    private val main = Handler(Looper.getMainLooper())
    private val sockets = ConcurrentHashMap<String, WebSocket>()

    fun open(id: String, url: String) {
        val httpUrl = toHttpUrl(url)
        if (httpUrl == null || !isLoopbackWs(url)) {
            dispatch("""{"type":"error","id":${jsString(id)},"reason":"loopback websocket only"}""")
            dispatch(
                """{"type":"close","id":${jsString(id)},"code":1002,"reason":"forbidden","wasClean":false}""",
            )
            return
        }
        val request = Request.Builder().url(httpUrl).build()
        val socket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    dispatch("""{"type":"open","id":${jsString(id)}}""")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    dispatch(
                        """{"type":"message","id":${jsString(id)},"binary":false,"data":${jsString(text)}}""",
                    )
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    dispatch(
                        """{"type":"message","id":${jsString(id)},"binary":true,"data":${jsString(bytes.base64())}}""",
                    )
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    sockets.remove(id)
                    dispatch(
                        """{"type":"close","id":${jsString(id)},"code":$code,"reason":${jsString(reason)},"wasClean":true}""",
                    )
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    sockets.remove(id)
                    Log.w(TAG, "native ws $id failed: ${t.message}")
                    dispatch("""{"type":"error","id":${jsString(id)},"reason":${jsString(t.message ?: "ws failed")}}""")
                    dispatch(
                        """{"type":"close","id":${jsString(id)},"code":1006,"reason":${jsString(t.message ?: "")},"wasClean":false}""",
                    )
                }
            },
        )
        sockets[id] = socket
    }

    fun sendText(id: String, text: String) {
        sockets[id]?.send(text)
    }

    fun sendBinary(id: String, base64: String) {
        val bytes = base64.decodeBase64() ?: return
        sockets[id]?.send(bytes)
    }

    fun close(id: String, code: Int, reason: String) {
        val socket = sockets.remove(id) ?: return
        val safe = if (code in 1000..4999) code else 1000
        try {
            socket.close(safe, closeReason(reason))
        } catch (_: IllegalArgumentException) {
            // `validateCloseCode` rejects reserved codes (1004–1006, 1015–2999)
            // that the `1000..4999` guard above still passes; the entry is
            // already removed from the map, so cancel or the socket leaks.
            socket.cancel()
        }
    }

    fun closeAll() {
        for ((_, socket) in sockets) {
            try {
                socket.cancel()
            } catch (_: Exception) {
            }
        }
        sockets.clear()
    }

    private fun dispatch(json: String) {
        val script = "window.__ocNativeWS&&window.__ocNativeWS.dispatch($json)"
        main.post {
            try {
                webView.evaluateJavascript(script, null)
            } catch (e: Exception) {
                Log.w(TAG, "ws dispatch failed: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "OpenCodeShell"

        internal fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .pingInterval(20, TimeUnit.SECONDS)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()

        internal fun toHttpUrl(url: String): String? {
            val trimmed = url.trim()
            return when {
                trimmed.startsWith("ws://") -> "http://" + trimmed.removePrefix("ws://")
                trimmed.startsWith("wss://") -> "https://" + trimmed.removePrefix("wss://")
                trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
                else -> null
            }
        }

        internal fun isLoopbackWs(url: String): Boolean {
            return try {
                val parsed = URI(if (url.startsWith("ws")) toHttpUrl(url) else url)
                val host = parsed.host?.trim()?.removePrefix("[")?.removeSuffix("]") ?: return false
                host == "127.0.0.1" || host.equals("localhost", true) || host == "::1"
            } catch (_: Exception) {
                false
            }
        }

        internal fun jsString(value: String): String =
            buildString {
                append('"')
                for (ch in value) {
                    when (ch) {
                        '\\' -> append("\\\\")
                        '"' -> append("\\\"")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> if (ch.code < 0x20) {
                            append("\\u")
                            append(ch.code.toString(16).padStart(4, '0'))
                        } else {
                            append(ch)
                        }
                    }
                }
                append('"')
            }

        /** RFC 6455: the close control payload is 125 bytes, 2 of them the code. */
        internal const val CLOSE_REASON_MAX_BYTES = 123

        /**
         * Cut [reason] to [maxBytes] of UTF-8 without splitting a code point or
         * a surrogate pair. OkHttp's `WebSocket.close` requires the *byte* size
         * ≤ 123; the old char-based `take(120)` overflowed with ≥42 CJK chars
         * and threw `IllegalArgumentException` from `close()`.
         */
        internal fun closeReason(reason: String, maxBytes: Int = CLOSE_REASON_MAX_BYTES): String {
            var bytes = 0
            var end = 0
            while (end < reason.length) {
                val codePoint = reason.codePointAt(end)
                val size = when {
                    codePoint < 0x80 -> 1
                    codePoint < 0x800 -> 2
                    codePoint < 0x10000 -> 3
                    else -> 4
                }
                if (bytes + size > maxBytes) break
                bytes += size
                end += Character.charCount(codePoint)
            }
            return reason.substring(0, end)
        }
    }
}
