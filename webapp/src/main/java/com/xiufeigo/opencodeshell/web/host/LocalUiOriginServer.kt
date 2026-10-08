package com.xiufeigo.opencodeshell.web.host

import android.content.res.AssetManager
import android.util.Log
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * UX11：**官方前端自带 + 本机 http 静态来源**。
 *
 * ## 为什么要有这个端点（机制，不是"想要"）
 *
 * 上游判据 `_upstream/packages/app/src/servers/connect/browser.ts:3-12`：
 *
 * ```ts
 * const page = new URL(location.href)
 * if (page.protocol !== "https:" && !isLoopback(page.hostname)) block()   // 混合内容红字
 * ```
 *
 * 判据只看**页面协议**。UX6 之前页面由"所配后端"提供 ⇒ 配了 `https://` 后端页面
 * 就是 `https:` ⇒ 那一页再也加不进 `http://` 服务器（UX10 §4.3 的双向死锁）。
 * 把官方 UI 作为 APK 自带资源、由本机 `http://` 来源提供之后，**页面协议恒为
 * `http:`**，与"配了哪台后端"彻底解耦 ⇒ 那道墙从根上消失（UX10 §6.1）。
 *
 * 跨源那一侧：opencode 服务端默认放行任意 `http://` 回环来源（带端口），
 * UX10 §5 实测 `http://127.0.0.1:4173` 在白名单内 ⇒ 页面直连用户配置的那台
 * 服务器天然可用，无需改动服务端。设备上另实测**带认证头的跨源健康检查也放行**
 * （CORS 预检回包允许该请求头）。
 *
 * ## 与官方桌面端同形
 *
 * 官方桌面端是 Electron：界面由**外壳自带**、不由某台服务器提供。
 * 这里是同一形态在 Android 上的落法：界面文件打进 `assets/www/ui/`，
 * 页面从本机回环 http 加载。
 *
 * ## 🔴 红线（这个类必须永远守住）
 *
 * 1. **只服务 UI 静态资源**：请求路径 → `assets/www/ui/` 下的文件，命中不了就
 *    SPA 回落或 404。**不存在任何转发逻辑**。
 * 2. **绝不碰凭据**：本文件不含也不构造任何认证信息；宿主不持有、不注入、
 *    不转发任何口令。认证完全由官方 UI 自己对后端完成。
 * 3. **绝不代理 API**：`/api/` 一律 404，由页面**直连**用户配置的那台服务器。
 *    这条是"不许把 API 代理过去"红线的代码级落点（见 [refuseApiPath]）。
 * 4. **只绑回环**：`InetAddress.getByName("127.0.0.1")`，外部网络访问不到。
 *
 * 这些性质由 `LocalUiOriginServerTest` 与 `DirectConnectAuthBoundaryTest` 钉死。
 */
class LocalUiOriginServer(
    private val assets: AssetManager,
    val port: Int = DEFAULT_PORT,
) {

    companion object {
        private const val TAG = "OcLocalUi"

        /**
         * 固定端口。避开 4095（那是后端/adb reverse 口径）。
         * 取 4173 的理由不止"顺手"：UX10 §5 实测 `http://127.0.0.1:4173`
         * 落在 opencode 服务端的 CORS 白名单内（`ACAO: http://127.0.0.1:4173`）。
         */
        const val DEFAULT_PORT = 4173

        /** assets 内的根目录（官方前端产物由 `_upstream/packages/app` 的 `vite build` 产出）。 */
        const val ASSET_ROOT = "www/ui"

        /**
         * 官方产物里 JS chunk 目录叫 `/_assets/`（vite 默认）。
         * ⚠️ 实测：AGP 的资源合并会**丢掉下划线开头的目录**（890 个 chunk 全被过滤，
         * 同级的 `assets/`、`icons/` 则正常进包）。所以磁盘上把**同一批文件**放在
         * [BUNDLE_DIR] 下，由本端点把请求路径 `/_assets/x` 映射到磁盘的 `/oa/x`。
         *
         * ⇒ **官方产物的内容、以及页面里引用的 URL，一个字节都没有改。**
         */
        const val URL_BUNDLE_PREFIX = "/_assets/"
        const val BUNDLE_DIR = "oa"

        /** 页面来源。宿主启动链加载它。 */
        fun origin(port: Int = DEFAULT_PORT): String = "http://127.0.0.1:$port"

        private fun contentTypeOf(path: String): String = when {
            path.endsWith(".html") -> "text/html; charset=utf-8"
            path.endsWith(".js") || path.endsWith(".mjs") -> "text/javascript; charset=utf-8"
            path.endsWith(".css") -> "text/css; charset=utf-8"
            path.endsWith(".json") || path.endsWith(".webmanifest") -> "application/json; charset=utf-8"
            path.endsWith(".svg") -> "image/svg+xml"
            path.endsWith(".png") -> "image/png"
            path.endsWith(".ico") -> "image/x-icon"
            path.endsWith(".webp") -> "image/webp"
            path.endsWith(".woff2") -> "font/woff2"
            path.endsWith(".woff") -> "font/woff"
            path.endsWith(".ttf") -> "font/ttf"
            path.endsWith(".wasm") -> "application/wasm"
            path.endsWith(".txt") -> "text/plain; charset=utf-8"
            path.endsWith(".xml") -> "application/xml; charset=utf-8"
            else -> "application/octet-stream"
        }
    }

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val workers = Executors.newCachedThreadPool { r ->
        Thread(r, "oc-local-ui").apply { isDaemon = true }
    }

    /** 起来了没 —— 失败时 [WebHostActivity] 要能区分"页面没起来"而不是白屏。 */
    @Volatile
    var boundPort: Int? = null
        private set

    /**
     * 启动。**幂等**：重复调用只生效一次。
     *
     * 失败（端口被占/环境不支持）不抛 —— 由 [boundPort] 保持 `null` 表示，
     * 上层据此决定是否仍要加载页面（不静默改架构）。
     */
    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return boundPort != null
        return try {
            // 🔴 只绑回环：外部网络访问不到这个端点。
            val socket = ServerSocket(port, 64, InetAddress.getByName("127.0.0.1"))
            serverSocket = socket
            boundPort = socket.localPort
            workers.execute { acceptLoop(socket) }
            Log.i(TAG, "local UI origin listening on ${origin(boundPort!!)} (static assets only)")
            true
        } catch (t: Throwable) {
            running.set(false)
            boundPort = null
            Log.e(TAG, "local UI origin failed to bind port $port: ${t.message}")
            false
        }
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        boundPort = null
        workers.shutdownNow()
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get() && !socket.isClosed) {
            val client: Socket = try {
                socket.accept()
            } catch (t: Throwable) {
                if (running.get()) Log.w(TAG, "accept failed: ${t.message}")
                break
            }
            workers.execute { handleQuietly(client) }
        }
    }

    private fun handleQuietly(client: Socket) {
        runCatching { handle(client) }
            .onFailure { Log.w(TAG, "request handling failed: ${it.message}") }
    }

    private fun handle(client: Socket) {
        client.use { sock ->
            sock.soTimeout = 15_000
            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            // 把请求头读完再回响应（避免客户端收到 RST）。
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            val out = BufferedOutputStream(sock.getOutputStream())
            val parts = requestLine.split(' ')
            val method = parts.getOrNull(0) ?: ""
            val rawTarget = parts.getOrNull(1) ?: ""
            val isHead = method.equals("HEAD", ignoreCase = true)

            if (!method.equals("GET", ignoreCase = true) && !isHead) {
                respond(out, 405, "text/plain; charset=utf-8", "method not allowed".toByteArray(), isHead)
                return
            }
            val path = URLDecoder.decode(rawTarget.substringBefore('?'), "UTF-8")

            // 🔴 红线 3：API 一律 404，宿主不做任何转发。
            if (refuseApiPath(path)) {
                respond(out, 404, "text/plain; charset=utf-8", "not found".toByteArray(), isHead)
                return
            }

            val hit = readAsset(normalize(path))
            if (hit != null) {
                respond(out, 200, contentTypeOf(hit.path), hit.bytes, isHead)
                return
            }
            // 🔴 SPA 回落：官方前端是单页应用，深链（/session/xxx、/settings）必须回到 index.html，
            // 否则页面会 404 成空白。这与服务端自托管 SPA 的行为一致。
            val index = readAsset("/index.html")
            if (index != null) {
                respond(out, 200, contentTypeOf(index.path), index.bytes, isHead)
            } else {
                respond(out, 404, "text/plain; charset=utf-8", "not found".toByteArray(), isHead)
            }
        }
    }

    /**
     * 🔴 红线 3 的代码级落点：`/api/` 前缀（以及官方 connect 用到的任何后端路由）
     * 永远不由本机端点应答。
     *
     * 静态资源目录里本来就没有这些文件，所以这实际是**双保险**：
     * 即使将来有人误把后端产物塞进 `assets/www/ui/api/`，也不会变成回环代理。
     */
    internal fun refuseApiPath(path: String): Boolean {
        val p = normalize(path)
        return p == "/api" || p.startsWith("/api/") || p == "/event" || p.startsWith("/event")
    }

    /** 归一化：绝对路径、去 `..`、去重复斜杠。 */
    internal fun normalize(path: String): String {
        var p = path.ifEmpty { "/" }
        if (!p.startsWith("/")) p = "/$p"
        val parts = ArrayList<String>()
        for (seg in p.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(seg)
            }
        }
        return "/" + parts.joinToString("/")
    }

    /** 一次资源命中：字节 + 实际命中的磁盘相对路径（Content-Type 必须按它判，不能按请求路径）。 */
    private data class Hit(val path: String, val bytes: ByteArray)

    /**
     * 读一个 assets 内的 UI 文件；不存在返回 `null`（调用方走 SPA 回落）。
     *
     * ⚠️ 返回的 [Hit.path] 是**实际命中的那个资源**，不是请求路径：根路径 `/` 命中的是
     * `index.html`，按请求路径判类型会得到 `application/octet-stream` ⇒ WebView
     * 会把首页当**文件下载**而不是渲染（实测过：5.98 KB 的"File downloaded"）。
     */
    private fun readAsset(path: String): Hit? {
        val normalized = normalize(path)
        // Replace only the terminal module; upstream assets stay reproducible.
        // Resolve by chunk prefix so an upstream hash change keeps the adapter.
        if (normalized.startsWith(URL_BUNDLE_PREFIX + "ghostty-web-") && normalized.endsWith(".js")) {
            val bytes = assets.open("www/oc-terminal.js").use { it.readBytes() }
            return Hit("/oc-terminal.js", bytes)
        }
        val clean = toDiskPath(normalized)
        val resolved = if (clean == "/") "/index.html" else clean
        val assetPath = "$ASSET_ROOT/${resolved.removePrefix("/")}"
        val bytes = runCatching {
            assets.open(assetPath).use { it.readBytes() }
        }.getOrNull() ?: return null
        return Hit(resolved, bytes)
    }

    /**
     * 请求路径 → 磁盘路径。只做一件事：把 vite 的 `/_assets/` 映射到 [BUNDLE_DIR]，
     * 绕开 AGP 丢弃下划线开头目录的限制。**不改写任何官方产物内容。**
     */
    internal fun toDiskPath(normalized: String): String =
        if (normalized.startsWith(URL_BUNDLE_PREFIX)) {
            "/$BUNDLE_DIR/" + normalized.removePrefix(URL_BUNDLE_PREFIX)
        } else {
            normalized
        }

    private fun respond(
        out: BufferedOutputStream,
        code: Int,
        type: String,
        body: ByteArray,
        isHead: Boolean,
    ) {
        val header = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(reasonOf(code)).append("\r\n")
            append("Content-Type: ").append(type).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            // 官方 UI 产物带内容哈希，但 SPA 回落/未来重建会变 ⇒ 不做强缓存。
            append("Cache-Control: no-cache\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        out.write(header.toByteArray(Charsets.UTF_8))
        if (!isHead) out.write(body)
        out.flush()
    }

    private fun reasonOf(code: Int): String = when (code) {
        200 -> "OK"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        else -> "OK"
    }
}
