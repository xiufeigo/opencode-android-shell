package com.xiufeigo.opencodeshell

import android.util.Log
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocketFactory

/**
 * Loopback reverse proxy. The WebView origin stays
 * `https://appassets.androidplatform.net`; fetch goes to
 * `http://127.0.0.1:<port>/opencode-proxy/<token>/<encoded-backend>/...`
 * so the remote OpenCode server never has to allow the WebView CORS origin.
 * OkHttp performs the real HTTPS (Basic Auth, redirects, SSE streams).
 *
 * Official v1 `OpenCode.make` joins with `new URL('/api/...', baseUrl)`, which
 * drops any path prefix. [originFor] therefore also binds a **dedicated**
 * loopback port per backend so `baseUrl` can be origin-only
 * (`http://127.0.0.1:<dedicated>`). Absolute `/api`, `/global`, `/event`,
 * `/file`, `/find`, `/path`, `/pty`, `/mcp`, `/permission`, `/session`, `/vcs`
 * (and every other serve path the official app fetch/XHR/SSE/WebSocket uses)
 * then stay on the proxy. There is no path allowlist.
 */
class LocalGateway(
    private val httpClient: OkHttpClient = defaultClient(),
    private val sslSocketFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
    private val sseIdleReadTimeoutMs: Long = SSE_IDLE_READ_TIMEOUT_MS,
) : Closeable {

    val token: String = UUID.randomUUID().toString()
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService? = null
    var port: Int = -1
        private set
    private val dedicatedLock = Any()
    private val backendPorts = ConcurrentHashMap<String, Int>()
    private val portBackends = ConcurrentHashMap<Int, String>()
    private val extraSockets = CopyOnWriteArrayList<ServerSocket>()
    private val activeClients = CopyOnWriteArrayList<Socket>()

    /**
     * SSE half-open guard. opencode heartbeats every 10 s; a socket read that
     * stays silent for [sseIdleReadTimeoutMs] means the long-lived stream was
     * blackholed without FIN/RST (carrier NAT / middlebox, common on long-haul
     * routes). OkHttp cannot detect that without a read timeout, so a stalled
     * stream would hang the WebView page forever ("reply only appears after
     * reload"). The timeout throws SocketTimeoutException, [copyStreaming]
     * breaks, the loopback socket closes and the page's EventSource reconnects
     * — the reconcile patch then re-syncs events missed during the gap.
     */
    private val sseClient: OkHttpClient by lazy {
        if (sseIdleReadTimeoutMs <= 0L) {
            httpClient
        } else {
            httpClient.newBuilder().readTimeout(sseIdleReadTimeoutMs, TimeUnit.MILLISECONDS).build()
        }
    }

    fun publicOrigin(): String = "http://127.0.0.1:$port"

    fun publicPrefix(): String = ProxyPath.publicPrefix(token)

    fun isBoundPort(candidate: Int): Boolean =
        candidate == port || portBackends.containsKey(candidate)

    /**
     * Origin-only loopback URL bound to [backendUrl]. Safe as SDK `baseUrl` for
     * WHATWG `new URL('/api/provider', base)` used by the v1 OpenCode client.
     */
    fun originFor(backendUrl: String): String {
        if (!running.get()) start()
        val backend = backendUrl.trim().trimEnd('/')
        backendPorts[backend]?.let { return "http://127.0.0.1:$it" }
        synchronized(dedicatedLock) {
            backendPorts[backend]?.let { return "http://127.0.0.1:$it" }
            val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            extraSockets.add(socket)
            val dedicatedPort = socket.localPort
            backendPorts[backend] = dedicatedPort
            portBackends[dedicatedPort] = backend
            val pool = executor
            val accept = Runnable {
                while (running.get()) {
                    try {
                        val client = socket.accept()
                        pool?.execute { handleClient(client, backend) }
                    } catch (ioe: IOException) {
                        if (running.get()) Log.w(TAG, "dedicated accept failed: ${ioe.message}")
                    }
                }
            }
            if (pool != null) pool.execute(accept) else Thread(accept, "oc-gateway-dedicated").apply { isDaemon = true }.start()
            Log.i(TAG, "dedicated origin http://127.0.0.1:$dedicatedPort -> $backend")
            return "http://127.0.0.1:$dedicatedPort"
        }
    }

    fun start() {
        if (running.get()) return
        val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        port = socket.localPort
        running.set(true)
        val pool = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "oc-gateway").apply { isDaemon = true }
        }
        executor = pool
        pool.execute {
            while (running.get()) {
                try {
                    val client = socket.accept()
                    pool.execute { handleClient(client, boundBackend = null) }
                } catch (ioe: IOException) {
                    if (running.get()) Log.w(TAG, "accept failed: ${ioe.message}")
                }
            }
        }
        Log.i(TAG, "listening on 127.0.0.1:$port prefix=${publicPrefix()}")
    }

    override fun close() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        for (extra in extraSockets) {
            try {
                extra.close()
            } catch (_: Exception) {
            }
        }
        extraSockets.clear()
        for (client in activeClients) {
            try {
                client.close()
            } catch (_: Exception) {
            }
        }
        activeClients.clear()
        backendPorts.clear()
        portBackends.clear()
        executor?.shutdownNow()
        executor = null
    }

    private fun handleClient(socket: Socket, boundBackend: String?) {
        socket.soTimeout = 0
        socket.tcpNoDelay = true
        activeClients.add(socket)
        try {
            socket.use { sock ->
                val input = BufferedInputStream(sock.getInputStream())
                val output = sock.getOutputStream()
                val parsed = readHttpRequest(input) ?: return
                val origin = parsed.headers.entries.firstOrNull { it.key.equals("origin", true) }?.value
                val proxyPath = ProxyPath.isProxyPath(parsed.path)
                if (parsed.method == "OPTIONS" && (boundBackend != null || proxyPath)) {
                    writeStatus(
                        output,
                        204,
                        "No Content",
                        corsHeaders(origin, parsed.headers) + mapOf("Content-Length" to "0"),
                    )
                    return
                }
                val target = if (boundBackend != null) {
                    val upstreamPath = parsed.path.ifEmpty { "/" }
                    ProxyPath.Target(backendBase = boundBackend, upstreamPath = upstreamPath, query = parsed.query)
                } else {
                    ProxyPath.parse(parsed.path, parsed.query, token)
                }
                if (target == null) {
                    val body = """{"error":"unknown proxy path"}"""
                    writeStatus(
                        output,
                        404,
                        "Not Found",
                        corsHeaders(origin, parsed.headers) + mapOf(
                            "Content-Type" to "application/json",
                            "Content-Length" to body.toByteArray().size.toString(),
                        ),
                        body.toByteArray(),
                    )
                    return
                }
                if (isWebSocketUpgrade(parsed)) {
                    tunnelWebSocket(sock, input, output, parsed, target)
                    return
                }
                proxyHttp(output, parsed, target, origin)
            }
        } catch (e: Exception) {
            Log.w(TAG, "client error: ${e.message}")
        } finally {
            activeClients.remove(socket)
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun proxyHttp(
        output: OutputStream,
        parsed: ParsedRequest,
        target: ProxyPath.Target,
        origin: String?,
    ) {
        val upstream = target.upstreamUrl()
        Log.i(TAG, "${parsed.method} $upstream")
        val builder = Request.Builder().url(upstream)
        for ((name, value) in parsed.headers) {
            if (shouldDropRequestHeader(name)) continue
            try {
                builder.addHeader(name, value)
            } catch (_: Exception) {
            }
        }
        val method = parsed.method.uppercase(Locale.US)
        val request = when (method) {
            "GET", "HEAD" -> builder.method(method, null).build()
            "DELETE" -> builder.method("DELETE", if (parsed.body.isNotEmpty()) body(parsed) else null).build()
            else -> builder.method(method, body(parsed)).build()
        }
        val response: Response = try {
            val callClient = if (isEventStreamRequest(parsed)) sseClient else httpClient
            callClient.newCall(request).execute()
        } catch (e: Exception) {
            Log.e(TAG, "upstream ${parsed.method} $upstream failed: ${e.message}")
            val msg = """{"error":${jsonString(e.message ?: "upstream failed")}}"""
            writeStatus(
                output,
                502,
                "Bad Gateway",
                corsHeaders(origin, parsed.headers) + mapOf(
                    "Content-Type" to "application/json",
                    "Content-Length" to msg.toByteArray().size.toString(),
                    "Cache-Control" to "no-store",
                ),
                msg.toByteArray(),
            )
            return
        }
        response.use { resp ->
            val headers = LinkedHashMap<String, String>()
            headers.putAll(corsHeaders(origin, parsed.headers))
            for (i in 0 until resp.headers.size) {
                val name = resp.headers.name(i)
                if (shouldDropResponseHeader(name)) continue
                if (name.equals("Access-Control-Allow-Origin", true)) continue
                headers[name] = resp.headers.value(i)
            }
            headers.remove("Content-Length")
            headers["Connection"] = "close"
            val reason = resp.message.ifBlank { "OK" }
            val statusLine = "HTTP/1.1 ${resp.code} $reason\r\n"
            output.write(statusLine.toByteArray())
            for ((name, value) in headers) {
                output.write("$name: $value\r\n".toByteArray())
            }
            output.write("\r\n".toByteArray())
            output.flush()
            if (method != "HEAD") {
                val stream = resp.body?.byteStream()
                if (stream != null) {
                    copyStreaming(stream, output)
                }
            }
            output.flush()
        }
    }

    private fun tunnelWebSocket(
        client: Socket,
        alreadyBuffered: BufferedInputStream,
        output: OutputStream,
        parsed: ParsedRequest,
        target: ProxyPath.Target,
    ) {
        val uri = URI(target.upstreamUrl())
        val ssl = uri.scheme.equals("https", true) || uri.scheme.equals("wss", true)
        val port = when {
            uri.port > 0 -> uri.port
            ssl -> 443
            else -> 80
        }
        val host = uri.host ?: return
        Log.i(TAG, "websocket tunnel -> $host:$port ${uri.path}")
        val remote = if (ssl) {
            sslSocketFactory.createSocket(host, port)
        } else {
            Socket(host, port)
        }
        remote.soTimeout = 0
        remote.tcpNoDelay = true
        try {
            val remoteOut = remote.getOutputStream()
            val path = (uri.rawPath ?: "/") + if (!uri.rawQuery.isNullOrEmpty()) "?${uri.rawQuery}" else ""
            remoteOut.write("${parsed.method} $path HTTP/1.1\r\n".toByteArray())
            remoteOut.write("Host: $host${if (port != 80 && port != 443) ":$port" else ""}\r\n".toByteArray())
            for ((name, value) in parsed.headers) {
                val lower = name.lowercase(Locale.US)
                if (lower == "host" || lower == "origin" || lower == "referer") continue
                remoteOut.write("$name: $value\r\n".toByteArray())
            }
            remoteOut.write("\r\n".toByteArray())
            remoteOut.flush()
            val remoteIn = remote.getInputStream()
            val t1 = Thread({ copyStreaming(alreadyBuffered, remoteOut) }, "oc-ws-up").apply { isDaemon = true }
            val t2 = Thread({ copyStreaming(remoteIn, output) }, "oc-ws-down").apply { isDaemon = true }
            t1.start()
            t2.start()
            t1.join()
            t2.join()
        } finally {
            try {
                remote.close()
            } catch (_: Exception) {
            }
            try {
                client.close()
            } catch (_: Exception) {
            }
        }
    }

    private data class ParsedRequest(
        val method: String,
        val path: String,
        val query: String?,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    private fun readHttpRequest(input: BufferedInputStream): ParsedRequest? {
        val headerBytes = readUntilHeadersEnd(input) ?: return null
        val headerText = String(headerBytes, Charsets.ISO_8859_1)
        val lines = headerText.split("\r\n")
        if (lines.isEmpty()) return null
        val requestLine = lines[0].split(" ")
        if (requestLine.size < 2) return null
        val method = requestLine[0]
        val rawTarget = requestLine[1]
        val q = rawTarget.indexOf('?')
        val path = if (q >= 0) rawTarget.substring(0, q) else rawTarget
        val query = if (q >= 0) rawTarget.substring(q + 1) else null
        val headers = LinkedHashMap<String, String>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isEmpty()) continue
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim()
            val value = line.substring(colon + 1).trim()
            headers[name] = value
        }
        val body = readBody(input, headers, method)
        return ParsedRequest(method, path, query, headers, body)
    }

    private fun readBody(input: BufferedInputStream, headers: Map<String, String>, method: String): ByteArray {
        if (method.equals("GET", true) || method.equals("HEAD", true)) return ByteArray(0)
        val te = header(headers, "Transfer-Encoding")
        if (te != null && te.contains("chunked", true)) return readChunked(input)
        val length = header(headers, "Content-Length")?.toIntOrNull() ?: 0
        if (length <= 0) return ByteArray(0)
        return readFully(input, length)
    }

    companion object {
        private const val TAG = "OpenCodeShell"
        private const val DEFAULT_ALLOW_HEADERS =
            "Authorization, Content-Type, Accept, Last-Event-ID, x-opencode-directory, x-opencode-workspace, x-opencode-ticket"

        /**
         * opencode `/event` heartbeats every 10 s. 35 s of silence means at
         * least three heartbeats were lost, i.e. the stream is dead even though
         * no FIN/RST arrived. Keep this well above 10 s and well below the
         * carrier NAT idle window that blackholes it.
         */
        internal const val SSE_IDLE_READ_TIMEOUT_MS = 35_000L

        /**
         * SSE paths used by the official client: `/event` (v1 subscribe),
         * `/global/event` (v2 global), `/api/event`, and session streams
         * `/api/session/{id}/event`. The SDK's `sse.get` sets no
         * `Accept: text/event-stream` request header (only the response is
         * content-checked), so the path is the reliable marker.
         */
        internal fun isEventStreamRequestPath(path: String): Boolean =
            path == "/event" || path.endsWith("/event")

        private fun isEventStreamRequest(parsed: ParsedRequest): Boolean {
            val accept = header(parsed.headers, "Accept")
            if (accept != null && accept.contains("text/event-stream", ignoreCase = true)) return true
            return isEventStreamRequestPath(parsed.path)
        }

        internal fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()

        internal fun allowedWebOrigin(origin: String?): String? {
            if (origin.isNullOrBlank()) return "*"
            return try {
                val host = URI(origin).host ?: return null
                when (host) {
                    MainActivity.ASSET_HOST, "127.0.0.1", "localhost" -> origin
                    else -> null
                }
            } catch (_: Exception) {
                null
            }
        }

        internal fun corsHeaders(
            origin: String?,
            requestHeaders: Map<String, String> = emptyMap(),
        ): Map<String, String> {
            val allow = allowedWebOrigin(origin) ?: return emptyMap()
            val requested = requestHeaders.entries
                .firstOrNull { it.key.equals("Access-Control-Request-Headers", true) }
                ?.value
            val allowHeaders = requested?.takeIf { it.isNotBlank() }
                ?: DEFAULT_ALLOW_HEADERS
            val headers = LinkedHashMap<String, String>()
            headers["Access-Control-Allow-Origin"] = allow
            if (allow != "*") {
                headers["Access-Control-Allow-Credentials"] = "true"
            }
            headers["Access-Control-Allow-Headers"] = allowHeaders
            headers["Access-Control-Allow-Methods"] = "GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS"
            headers["Access-Control-Allow-Private-Network"] = "true"
            headers["Access-Control-Expose-Headers"] = "Last-Event-ID, Content-Type"
            headers["Access-Control-Max-Age"] = "86400"
            headers["Vary"] = "Origin"
            return headers
        }

        private fun shouldDropRequestHeader(name: String): Boolean {
            return when (name.lowercase(Locale.US)) {
                "host", "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
                "te", "trailer", "transfer-encoding", "upgrade", "origin", "referer",
                "content-length", "accept-encoding",
                -> true
                else -> false
            }
        }

        private fun shouldDropResponseHeader(name: String): Boolean {
            return when (name.lowercase(Locale.US)) {
                "connection", "keep-alive", "proxy-authenticate", "transfer-encoding", "trailer" -> true
                else -> false
            }
        }

        private fun isWebSocketUpgrade(parsed: ParsedRequest): Boolean {
            val upgrade = header(parsed.headers, "Upgrade") ?: return false
            val connection = header(parsed.headers, "Connection") ?: return false
            return upgrade.contains("websocket", true) && connection.contains("upgrade", true)
        }

        private fun header(headers: Map<String, String>, name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, true) }?.value

        private fun body(parsed: ParsedRequest) =
            parsed.body.toRequestBody(
                header(parsed.headers, "Content-Type")?.toMediaTypeOrNull(),
            )

        private fun copyStreaming(input: InputStream, output: OutputStream) {
            val buf = ByteArray(8192)
            while (true) {
                val n = try {
                    input.read(buf)
                } catch (e: SocketTimeoutException) {
                    // SSE idle watchdog (see sseClient): the upstream went silent
                    // longer than any opencode heartbeat gap, so the stream is
                    // presumed half-open. Tear down so the page reconnects
                    // instead of hanging forever.
                    Log.w(TAG, "upstream stream idle timeout: ${e.message}")
                    break
                } catch (_: Exception) {
                    break
                }
                if (n < 0) break
                if (n == 0) continue
                try {
                    output.write(buf, 0, n)
                    output.flush()
                } catch (_: Exception) {
                    break
                }
            }
        }

        private fun readUntilHeadersEnd(input: BufferedInputStream): ByteArray? {
            val out = ByteArrayOutputStream()
            var last4 = 0
            while (true) {
                val b = input.read()
                if (b < 0) return if (out.size() == 0) null else out.toByteArray()
                out.write(b)
                last4 = ((last4 shl 8) or (b and 0xff))
                if (last4 == 0x0d0a0d0a) break
                if (out.size() > 64 * 1024) return null
            }
            return out.toByteArray()
        }

        private fun readFully(input: InputStream, length: Int): ByteArray {
            val bytes = ByteArray(length)
            var off = 0
            while (off < length) {
                val n = input.read(bytes, off, length - off)
                if (n < 0) break
                off += n
            }
            return if (off == length) bytes else bytes.copyOf(off)
        }

        private fun readChunked(input: InputStream): ByteArray {
            val out = ByteArrayOutputStream()
            while (true) {
                val line = readLineAscii(input) ?: break
                val size = line.substringBefore(';').trim().toIntOrNull(16) ?: break
                if (size == 0) {
                    readLineAscii(input)
                    break
                }
                out.write(readFully(input, size))
                readLineAscii(input)
            }
            return out.toByteArray()
        }

        private fun readLineAscii(input: InputStream): String? {
            val out = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) break
                if (b == '\n'.code) break
                if (b != '\r'.code) out.write(b)
            }
            return if (out.size() == 0) null else out.toString(Charsets.US_ASCII.name())
        }

        private fun writeStatus(
            output: OutputStream,
            code: Int,
            reason: String,
            headers: Map<String, String>,
            body: ByteArray? = null,
        ) {
            output.write("HTTP/1.1 $code $reason\r\n".toByteArray())
            for ((name, value) in headers) {
                output.write("$name: $value\r\n".toByteArray())
            }
            output.write("\r\n".toByteArray())
            if (body != null) output.write(body)
            output.flush()
        }

        private fun jsonString(value: String): String =
            buildString {
                append('"')
                for (ch in value) {
                    when (ch) {
                        '\\' -> append("\\\\")
                        '"' -> append("\\\"")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        else -> append(ch)
                    }
                }
                append('"')
            }
    }
}
