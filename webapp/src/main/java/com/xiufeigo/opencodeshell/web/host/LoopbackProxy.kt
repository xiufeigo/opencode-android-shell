package com.xiufeigo.opencodeshell.web.host

import android.util.Base64
import android.util.Log
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * R1 · 应用内回环反向代理（路线 A 的心脏）。
 *
 * ## 为什么是"代理"而不是"页面直连后端"
 *
 * 官方 UI 由服务端自托管（G1）：路由是根 `/`，资源前缀 `/_assets/`，`GET /` 免认证返回
 * 200 text/html；只有 `/api/…` 要 Basic 认证（无认证 401、Basic 200）。
 * 如果 WebView 直接 `loadUrl("http://127.0.0.1:4095/")`，那么：
 *
 *  1. **页面必须自己处理 Basic 认证** —— 密码就得进 JS 可见范围（window / 存储），
 *     任何 XSS 或第三方脚本都能读走。实测 `/api/info` 无认证就是 401。
 *  2. **CORS 悬空** —— 一旦页面 origin 变了（资产托管、或走代理），API 就成了跨源。
 *
 * 所以本类让**文档本身也从代理 origin 提供**：
 *
 * ```
 *   WebView  ──http://127.0.0.1:<随机端口>/──▶  本类（回环 HTTP 服务）
 *                                            ├─ /            → 上游 /            （200 免认证 HTML）
 *                                            ├─ /_assets/…   → 上游 /_assets/…   （静态资源）
 *                                            └─ /api/…       → 上游 /api/…  + 注入 Basic 头
 * ```
 *
 * 页面 origin == API origin == `http://127.0.0.1:<port>`，**完全不依赖后端 CORS**，
 * 且**页面永远拿不到密码** —— 认证在转发时由原生侧注入。
 *
 * ## 与 legacy `:app` 的 LocalGateway 的关系
 *
 * 做法直接搬自 `app/src/main/java/com/xiufeigo/opencodeshell/LocalGateway.kt`
 * （ServerSocket + 手写 HTTP/1.1 解析 + OkHttp 转发 + 剥 Origin + SSE 空转看门狗 +
 * WebSocket 隧道）。差异见本文件 `onForward` 的注释。
 *
 * 选它而不是 `shouldInterceptRequest` 的理由写在 `research/web/ROUTE_A_WIRING.md` §2，
 * 一句话版本：intercept 拿不到**文档本身**的同源，也**根本不接管 WebSocket**。
 */
class LoopbackProxy(
    /** 上游基址，例如 `http://127.0.0.1:4095`。末尾斜杠会被去掉。 */
    private val upstreamBase: String,
    /**
     * Basic 密码提供者。**每次转发都重新调用**，不在代理里缓存明文；
     * 实现方必须从 `SecretStore`（EncryptedSharedPreferences）读。
     * 返回 null/空 ⇒ 不注入 Authorization（后端会 401，属预期）。
     */
    private val passwordProvider: () -> String?,
    private val httpClient: OkHttpClient = defaultClient(),
    private val sseIdleReadTimeoutMs: Long = SSE_IDLE_READ_TIMEOUT_MS,
    /**
     * P2 性能修复开关（同一个 APK 内 baseline/fixed 对照，见 `WebHostActivity.EXTRA_PERF_MODE`）。
     *
     * 打开后做两件事，都只影响**传输**，不改语义：
     *  1. **透传压缩**：`Accept-Encoding: gzip` 转发给上游，拿回 gzip 字节。
     *     （默认路径把它 drop 掉了 ⇒ 252 个 `_assets/` 下的 js/css 全是明文体积。）
     *  2. **连接复用**：不再对每个响应强制 `Connection: close`，改成按
     *     `Content-Length` 定界并在同一 socket 上连续服务多个请求。
     *
     * 认证、同源、CORS 语义、SSE/WS 行为均**不变**。
     */
    private val fixedMode: Boolean = false,
    /**
     * P3：gzip 透传**已合入为默认行为**（P2 §3.6 判定"保留并合入"）。
     * 与 [fixedMode] 解耦：后者是 P2 已判"不该合入"的整体缓冲定界，只留给对照复现。
     */
    private val gzipMode: Boolean = true,
    /**
     * P3 帧时归因：文档渲染档位（只对 `GET /` 生效，见 [rewriteDocument]）。
     *  - `full`  ：原样转发官方 UI
     *  - `noanim`：注入关掉 animation/transition/box-shadow 的 CSS
     *  - `min`   ：返回一份极小 DOM 的合成页面（隔离"内容复杂度"）
     *  - `flat`  ：`min` + `noanim`（帧时地板值）
     */
    private val renderMode: String = "full",
) : Closeable {

    companion object {
        private const val TAG = "OcWebHost/Proxy"

        /** 用户名固定（与 opencode 服务端约定一致）；只有密码是秘密。 */
        const val BASIC_USER = "opencode"

        internal const val SSE_IDLE_READ_TIMEOUT_MS = 35_000L

/**
 * F4: how many trailing characters of a streaming read stay in the round-signal
 * scan window, so an upstream round-end marker split across two reads is still
 * matched. Bounded on purpose: the window must not grow with the stream.
 */
internal const val SSE_SCAN_WINDOW_CHARS = 2048

        internal fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            // 0 = 不给普通响应设读超时；SSE 走下面那个专用的 sseClient。
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val running = AtomicBoolean(false)
    private val base = upstreamBase.trim().trimEnd('/')
    private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService? = null
    private val activeClients = java.util.concurrent.CopyOnWriteArrayList<Socket>()

    // ---- P6 · 省电：后台断开流式连接 --------------------------------------
    // 只记**流式**（SSE）那条 socket。普通请求的 socket 留在 [activeClients] 里，
    // 否则回前台第一次点击会 502（页面正在导航、图标/字体等资源还在路上）。
    private val streamingClients = java.util.concurrent.CopyOnWriteArrayList<Socket>()

    /** 后台期间为 `true`：此时**任何**新的 SSE 请求都直接 503 拒绝，不回上游。 */
    private val streamingSuspended = AtomicBoolean(false)

    /** 被 [suspendStreaming] 掐断的流式连接数（诊断用，`Log.i` 也会打）。 */
    private val streamingClosed = java.util.concurrent.atomic.AtomicInteger(0)

    /** 被后台拒绝掉的流式请求数（诊断用）。 */
    private val streamingRefused = java.util.concurrent.atomic.AtomicInteger(0)

    // ---- D1 · 回合活跃跟踪（喂给宿主的"回合进行中"信号） ----------------
    /**
     * 回合活跃跟踪器。代理是所有页面流量的中枢，**看得见**回合的起止：
     * 页面 POST 消息 = 回合开始；回合的流式通道 EOF/静默 = 回合结束。
     *
     * 为什么非要在代理上看：`KEEP_SCREEN_ON` 死代码的根因就是
     * **上游官方 UI 不会调 `setKeepScreenOn`** ⇒ 页面那条路永远不来。
     * 详见 [RoundActivityTracker] 类注释与 `research/web/FIX_D1.md` §1。
     */
    val roundActivity = RoundActivityTracker()

    /**
     * 回合状态变化时回调（**只在变化时**触发，宿主据此置位/清除 flag）。
     * 由 `WebHostActivity` 在起代理后装上。
     */
    @Volatile
    var onRoundActiveChanged: ((Boolean) -> Unit)? = null

    /** 回合状态变化时统一走这里：判定 + 通知 + 打诊断日志。 */
    private fun publishRoundActive() {
        val active = roundActivity.isRoundActive(System.currentTimeMillis())
        val cb = onRoundActiveChanged
        if (cb != null) {
            runCatching { cb(active) }.onFailure { Log.w(TAG, "round callback failed", it) }
        }
        Log.i(TAG, "D1 roundActive=$active ageMs=${roundActivity.roundAgeMs(System.currentTimeMillis())}")
    }

    var port: Int = -1
        private set

    /** 页面与 API 的**共同** origin。 */
    fun publicOrigin(): String = "http://127.0.0.1:$port"

    /** opencode `/event` 每 10 s 心跳；35 s 静默即认定半开，主动断开让页面重连。 */
    private val sseClient: OkHttpClient by lazy {
        if (sseIdleReadTimeoutMs <= 0L) httpClient
        else httpClient.newBuilder().readTimeout(sseIdleReadTimeoutMs, TimeUnit.MILLISECONDS).build()
    }

    fun start() {
        if (running.get()) return
        val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        port = socket.localPort
        running.set(true)
        val pool = Executors.newCachedThreadPool { r ->
            Thread(r, "oc-web-proxy").apply { isDaemon = true }
        }
        executor = pool
        pool.execute {
            while (running.get()) {
                try {
                    val client = socket.accept()
                    activeClients.add(client)
                    pool.execute { handleClient(client) }
                } catch (ioe: IOException) {
                    if (running.get()) Log.w(TAG, "accept failed: ${ioe.message}")
                }
            }
        }
        Log.i(TAG, "listening on ${publicOrigin()} -> $base")
    }

    fun isBoundPort(candidate: Int): Boolean = candidate == port

    // ---- P6 · 后台省电开关 -------------------------------------------------
    /**
     * **进入后台：掐断所有流式连接。**
     *
     * 决策在 [PowerPolicy]（纯 Kotlin、可单测），这里只做接线。
     *
     * ## 为什么必须"关 + 拒绝"两步
     *
     * 只"关"是**假省电**：页面的 `EventSource` 会自动重连，重连又打回代理，
     * 于是变成"关一条、再连一条"的拉锯 —— 上游 `/event` 每 10 s 的心跳
     * （`LoopbackProxy.kt:129-133` 的注释）照旧把射频唤醒，一分没省。
     * 所以关掉之后必须**继续拒绝**新的流式请求（见 [onForward] 里的
     * [PowerPolicy.StreamingAction.REFUSE_WHILE_BACKGROUND]），
     * 让重连只打回环、碰不到上游。
     *
     * ## 为什么不直接 [close]
     *
     * [close] 会 `running.set(false)` + 关 `ServerSocket` + `shutdownNow()`，
     * 于是 `port` 留在一个已死的代理上，`onResume` 再也恢复不了。
     * 本方法**只碰流式 socket**，`ServerSocket` / `port` / 线程池原封不动。
     *
     * @return 被掐断的流式连接数（0 表示本来就没有 —— 这本身是有用信息）
     */
    fun suspendStreaming(): Int {
        streamingSuspended.set(true)
        val victims = streamingClients.toList()
        for (s in victims) runCatching { s.close() }
        streamingClients.clear()
        streamingClosed.addAndGet(victims.size)
        Log.i(
            TAG,
            "P6 suspendStreaming: closed=${victims.size} remainingActive=${activeClients.size} " +
                "portStillBound=${port > 0}",
        )
        return victims.size
    }

    /**
     * **回到前台：解除拒绝，让页面重连。**
     *
     * 不主动重连（代理是服务端，推不出"页面该重建哪条 EventSource"）；
     * 页面下一次重试就恢复，与 T1 §7.2 说的"35 s 静默判半开让页面重连"同一条路径。
     */
    fun resumeStreaming() {
        if (streamingSuspended.compareAndSet(true, false)) {
            Log.i(TAG, "P6 resumeStreaming: closed=${streamingClosed.get()} refused=${streamingRefused.get()}")
        }
    }

    /** 诊断读数：后台是否处于拒绝态。 */
    fun isStreamingSuspended(): Boolean = streamingSuspended.get()

    /** 诊断读数：被掐断 + 被拒绝的流式事件累计数。 */
    fun streamingPruned(): Int = streamingClosed.get() + streamingRefused.get()

    override fun close() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        for (c in activeClients) runCatching { c.close() }
        activeClients.clear()
        streamingClients.clear()
        executor?.shutdownNow()
        executor = null
    }

    // ---- 连接处理 --------------------------------------------------------
    private fun handleClient(socket: Socket) {
        socket.soTimeout = 0
        socket.tcpNoDelay = true
        try {
            socket.use { sock ->
                val input = BufferedInputStream(sock.getInputStream())
                val output = sock.getOutputStream()
                // P2：默认一 socket 一请求。`fixedMode` 下在同一 socket 上连续服务多个请求
                // （HTTP/1.1 keep-alive），把 252 次 TCP 握手压到 WebView 的并发连接数。
                while (true) {
                    val parsed = readHttpRequest(input) ?: return
                    val target = base + (parsed.path.ifEmpty { "/" }) +
                        (parsed.query?.let { "?$it" } ?: "")
                    if (isWebSocketUpgrade(parsed)) {
                        tunnelWebSocket(sock, input, output, parsed, target)
                        return
                    }
                    // P6：登记"这条 socket 正在跑流式通道"，后台只掐这些。
                    // 判定用 onForward 同一套 [isEventStreamRequest]，不另立标准。
                    if (isEventStreamRequest(parsed)) streamingClients.add(socket)
                    val reusable = onForward(output, parsed, target)
                    if (!fixedMode || !reusable) return
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "client error: ${e.message}")
        } finally {
            activeClients.remove(socket)
            streamingClients.remove(socket)
            runCatching { socket.close() }
        }
    }

    /**
     * HTTP 转发。与 legacy `LocalGateway.proxyHttp` 的三处**实质**差异：
     *
     *  1. **注入 Basic 头**（legacy 那层页面在 `https://appassets` 上、代理只做 CORS
     *     换源，认证由页面自己出；本轮文档就在代理 origin 上，认证必须原生做，
     *     否则密码要么进 JS、要么根本没人填）。
     *  2. **不回灌 CORS 头**：同源了，不需要 `Access-Control-Allow-*`；反而把上游
     *     自己的 ACAO 丢掉，避免"代理以为自己是 CORS 边界"的误读。
     *  3. 目标地址是**逐字拼接**（不再有 token/prefix 改写），因为页面与 API 同源。
     */
    private fun onForward(output: OutputStream, parsed: ParsedRequest, target: String): Boolean {
        Log.i(TAG, "${parsed.method} $target")
        // D1：回合开始信号。页面 POST 一条"发消息"请求 = 用户按了发送。
        // 放在最前面（连后台拒绝判定之前）⇒ 即便这条请求随后被 503 拒掉，
        // 回合也已经开始了，常亮该置位；否则会漏掉"发送后立刻切后台"这种时序。
        if (roundActivity.isRoundStartRequest(parsed.method, parsed.path)) {
            roundActivity.onRoundStart(System.currentTimeMillis())
            publishRoundActive()
        }
        // P6 · 后台省电：流式连接在后台**不回上游**。
        // 放在这里（建上游请求之前）⇒ 连 socket 都不建，更谈不上射频唤醒。
        // 决策见纯逻辑 [PowerPolicy]；这里只接线。
        if (isEventStreamRequest(parsed) &&
            PowerPolicy.streamingAction(foreground = !streamingSuspended.get()) ==
            PowerPolicy.StreamingAction.REFUSE_WHILE_BACKGROUND
        ) {
            streamingRefused.incrementAndGet()
            val msg = """{"error":${jsonString("oc: backgrounded, streaming refused")}}"""
            Log.i(
                TAG,
                "P6 refuse SSE while backgrounded: ${parsed.path} " +
                    "(no upstream call; page EventSource retries -- measured 2s cadence, " +
                    "loopback only so no radio wakeup)",
            )
            writeStatus(
                output, PowerPolicy.BACKGROUND_STREAM_REFUSE_STATUS, "Service Unavailable",
                mapOf(
                    "Content-Type" to "application/json",
                    "Content-Length" to msg.toByteArray().size.toString(),
                    "Cache-Control" to "no-store",
                    // 显式 close：让 WebView 立刻判定这条 EventSource 失败并进入重连退避
                    "Connection" to "close",
                ),
                msg.toByteArray(),
            )
            return false
        }
        val tStart = System.nanoTime()
        val builder = Request.Builder().url(target)
        for ((name, value) in parsed.headers) {
            if (shouldDropRequestHeader(name)) continue
            runCatching { builder.addHeader(name, value) }
        }
        // P2 · 修复 1（P3 已合入）：透传压缩。默认路径把 accept-encoding 一起 drop 了，
        // 于是上游按 identity 回明文 —— js/css 全是未压缩体积。
        // 显式写而不是"原样转发客户端的"：WebView 发的 `br, gzip, deflate` 里
        // br 只有在 OkHttp 装了 brotli 依赖时才会被上游回，且解码不了 ⇒ 只承诺 gzip。
        // ⚠️ 显式设置后 OkHttp **不会自动解压** ⇒ 代理线上原样搬运的就是 gzip 字节，
        //    Content-Length 与 Content-Encoding 语义不变，SSE / WebSocket 不受影响。
        val rewriteDoc = renderMode != "full" && parsed.method.equals("GET", true) &&
            (parsed.path == "/" || parsed.path.isEmpty())
        if (gzipMode || fixedMode) {
            // 文档要被改写（注入 CSS / 换页）时必须拿明文，否则注入点落在 gzip 字节里。
            runCatching { builder.header("Accept-Encoding", if (rewriteDoc) "identity" else "gzip") }
        }
        val password = runCatching { passwordProvider() }.getOrNull()
        var authSent = false
        if (!password.isNullOrEmpty()) {
            val token = Base64.encodeToString(
                "$BASIC_USER:$password".toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP,
            )
            // 页面永远不会看到这个头 —— 浏览器只在同源请求里发，代理在建上游请求时补。
            builder.header("Authorization", "Basic $token")
            authSent = true
        } else {
            Log.w(TAG, "no password in SecretStore -> ${parsed.path} will 401 upstream")
        }

        val method = parsed.method.uppercase(Locale.US)
        val request = when (method) {
            "GET", "HEAD" -> builder.method(method, null).build()
            "DELETE" -> builder.method("DELETE", if (parsed.body.isNotEmpty()) body(parsed) else null).build()
            else -> builder.method(method, body(parsed)).build()
        }

        val response = try {
            val client = if (isEventStreamRequest(parsed)) sseClient else httpClient
            client.newCall(request).execute()
        } catch (e: Exception) {
            Log.e(TAG, "upstream ${parsed.method} $target failed: ${e.message}")
            val msg = """{"error":${jsonString(e.message ?: "upstream failed")}}"""
            writeStatus(
                output, 502, "Bad Gateway",
                mapOf(
                    "Content-Type" to "application/json",
                    "Content-Length" to msg.toByteArray().size.toString(),
                    "Cache-Control" to "no-store",
                ),
                msg.toByteArray(),
            )
            return false
        }

        var reusable = false
        response.use { resp ->
            if (resp.code == 401 || resp.code == 403) {
                // 只在认证失败时打，且**只打长度/是否为空**，绝不回显口令本身。
                Log.w(
                    TAG,
                    "auth rejected: ${parsed.method} $target -> ${resp.code}; " +
                        "user=$BASIC_USER passLen=${password?.length ?: -1} authHeaderSent=${authSent}",
                )
            }
            val headers = LinkedHashMap<String, String>()
            for (i in 0 until resp.headers.size) {
                val name = resp.headers.name(i)
                if (shouldDropResponseHeader(name)) continue
                // 同源了，CORS 头一律不要。
                if (name.equals("Access-Control-Allow-Origin", true)) continue
                if (name.equals("Access-Control-Allow-Credentials", true)) continue
                headers[name] = resp.headers.value(i)
            }
            // 由我们自己按实际字节数写，避免上游 Content-Length 与传输不一致。
            //
            // P2 · 修复 2：默认路径丢掉 Content-Length 并强制 `Connection: close`
            // —— 客户端无法定界，只能"读到一个连接关掉为止"，于是 252 个资源
            // = 252 次 TCP 握手。`fixedMode` 下改为**用真实 body 长度定界**，
            // 客户端读完就知道可以复用这条连接发下一个请求。
            val isHead = method == "HEAD"
            val streaming = isEventStreamRequest(parsed)
            // P2 · 修复 2：让响应**有 Content-Length**，从而能被定界、连接能被复用。
            //
            // 上一轮只信任 `body.contentLength()`，结果 417 个响应里只有 12 个拿得到：
            // 上游对大部分资源用 `Transfer-Encoding: chunked`，**没有** Content-Length，
            // 于是 `contentLength()` = -1 → 我们只能 `Connection: close` →
            // 客户端必须等 socket 关闭才知道 body 结束，每个请求白等 ~44ms。
            //
            // 所以这里在 fixedMode 下把**非流式**响应体读干净、自己算长度。
            // SSE 绝不能这么做（无限流），走原路径。
            var buffered: ByteArray? = null
            if (rewriteDoc) {
                // 帧时归因专用：文档要改写 ⇒ 必须整体缓冲（拿到明文 body），并重算 Content-Length。
                val raw = runCatching { resp.body?.bytes() }.getOrNull()
                if (raw != null) {
                    val html = String(raw, Charsets.UTF_8)
                    val out = rewriteDocument(html)
                    val bytes = out.toByteArray(Charsets.UTF_8)
                    buffered = bytes
                    headers["Content-Length"] = bytes.size.toString()
                    // 改写后不可能还是上游那份编码；统一声明无编码。
                    headers.remove("Content-Encoding")
                    headers.remove("Transfer-Encoding")
                    headers["Connection"] = "close"
                    reusable = false
                    PerfMarks.markIfEnabled(
                        "Q.docRewrite",
                        "renderMode=$renderMode inBytes=${raw.size} outBytes=${bytes.size}",
                    )
                }
            }
            if (buffered == null && fixedMode && !streaming) {
                if (resp.body?.contentLength() ?: -1L >= 0) {
                    val len = resp.body?.contentLength() ?: -1L
                    headers["Content-Length"] = len.toString()
                    reusable = true
                } else {
                    val bytes = runCatching { resp.body?.bytes() }.getOrNull()
                    if (bytes != null) {
                        buffered = bytes
                        headers["Content-Length"] = bytes.size.toString()
                        reusable = true
                    }
                }
                headers["Connection"] = if (reusable) "keep-alive" else "close"
            } else {
                headers.remove("Content-Length")
                headers["Connection"] = "close"
            }
            // 缓冲过就不能再把流写出去（body 已被消费）。
            if (buffered != null) {
                headers.remove("Transfer-Encoding")
            }
            if (streaming) {
                Log.i(
                    TAG,
                    "sse response ${resp.code} ctype=${resp.headers["Content-Type"]} " +
                        "te=${resp.headers["Transfer-Encoding"]} cl=${resp.headers["Content-Length"]} " +
                        "ce=${resp.headers["Content-Encoding"]}",
                )
            }
            val tHdr = System.nanoTime()
            output.write("HTTP/1.1 ${resp.code} ${resp.message.ifBlank { "OK" }}\r\n".toByteArray())
            for ((name, value) in headers) output.write("$name: $value\r\n".toByteArray())
            output.write("\r\n".toByteArray())
            output.flush()
            if (!isHead) {
                val b = buffered
                if (b != null) {
                    output.write(b)
                } else {
                    resp.body?.byteStream()?.let { copyStreaming(it, output) }
                }
            }
            output.flush()
            val tEnd = System.nanoTime()
            PerfMarks.markIfEnabled(
                "P.fwd",
                "path=${parsed.path.take(58)} code=${resp.code} " +
                    "upstreamMs=${(tHdr - tStart) / 1_000_000} totalMs=${(tEnd - tStart) / 1_000_000} " +
                    "len=${headers["Content-Length"] ?: -1} ce=${resp.headers["Content-Encoding"]} " +
                    "reusable=$reusable mode=$fixedMode",
            )
        }
        return reusable
    }

    /**
     * P3 帧时归因用：把官方 UI 的文档改成"更轻"的样子，用来分离
     * **「官方 UI 本身更重」** 与 **「WebView 合成更慢」**。
     *
     * 只在 `renderMode != full` 时被调用，且只对 `GET /` 生效（见 [onForward]）。
     * 返回值是**要写给 WebView 的完整 HTML**；调用方负责重算 Content-Length。
     */
    private fun rewriteDocument(html: String): String {
        // 极小 DOM：纯静态文字，**零 script / 零资源请求**，但**足够长可滚动**。
        // （第一版只有 12 行 ⇒ 不可滚动 ⇒ 滑动只产出 39 帧，样本量不足；
        //   加长到 400 行后滑动能产出与官方 UI 同量级的帧数，地板值才可比。）
        val minimal = """<!doctype html><html lang="en"><head><meta charset="utf-8">""" +
            """<title>min</title><style>html,body{margin:0;padding:0;background:#080808;color:#ddd;""" +
            """font:16px/1.5 monospace}div{padding:8px;border-bottom:1px solid #222}""" +
            """*{box-shadow:none!important}</style></head><body>""" +
            (1..400).joinToString("") { "<div>row $it</div>" } +
            "</body></html>"

        if (renderMode == "min" || renderMode == "flat") return minimal

        // noanim / full-路径之外的 noanim：注入关停动画与阴影的 CSS。
        val kill = """<style id="oc-perf-noanim">*,*::before,*::after{""" +
            """animation:none!important;animation-duration:0s!important;""" +
            """transition:none!important;transition-duration:0s!important;""" +
            """box-shadow:none!important;text-shadow:none!important;""" +
            """filter:none!important;backdrop-filter:none!important;background-image:none!important}""" +
            """</style>"""
        val at = html.indexOf("</head>")
        return if (at >= 0) html.substring(0, at) + kill + html.substring(at) else kill + html
    }

    /** WebSocket 隧道（PTY 终端）。抄 legacy `tunnelWebSocket`，逐字握手 + 双向 copy。 */
    private fun tunnelWebSocket(
        client: Socket,
        alreadyBuffered: BufferedInputStream,
        output: OutputStream,
        parsed: ParsedRequest,
        target: String,
    ) {
        val uri = URI(target)
        val ssl = uri.scheme.equals("https", true) || uri.scheme.equals("wss", true)
        val port = when {
            uri.port > 0 -> uri.port
            ssl -> 443
            else -> 80
        }
        val host = uri.host ?: return
        Log.i(TAG, "websocket tunnel -> $host:$port ${uri.path}")
        val remote = if (ssl) Socket(host, port) else Socket(host, port)
        remote.soTimeout = 0
        remote.tcpNoDelay = true
        try {
            val remoteOut = remote.getOutputStream()
            val path = (uri.rawPath ?: "/") +
                (if (!uri.rawQuery.isNullOrEmpty()) "?${uri.rawQuery}" else "")
            remoteOut.write("${parsed.method} $path HTTP/1.1\r\n".toByteArray())
            remoteOut.write("Host: $host${if (port != 80 && port != 443) ":$port" else ""}\r\n".toByteArray())
            val password = runCatching { passwordProvider() }.getOrNull()
            if (!password.isNullOrEmpty()) {
                val token = Base64.encodeToString(
                    "$BASIC_USER:$password".toByteArray(Charsets.UTF_8),
                    Base64.NO_WRAP,
                )
                remoteOut.write("Authorization: Basic $token\r\n".toByteArray())
            }
            for ((name, value) in parsed.headers) {
                val lower = name.lowercase(Locale.US)
                if (lower in setOf("host", "origin", "referer", "authorization")) continue
                remoteOut.write("$name: $value\r\n".toByteArray())
            }
            remoteOut.write("\r\n".toByteArray())
            remoteOut.flush()
            val remoteIn = remote.getInputStream()
            val t1 = Thread({ copyStreaming(alreadyBuffered, remoteOut) }, "oc-ws-up").apply { isDaemon = true }
            val t2 = Thread({ copyStreaming(remoteIn, output) }, "oc-ws-down").apply { isDaemon = true }
            t1.start(); t2.start(); t1.join(); t2.join()
        } finally {
            runCatching { remote.close() }
            runCatching { client.close() }
        }
    }

    // ---- HTTP/1.1 解析（照搬 legacy 语义） --------------------------------
    private data class ParsedRequest(
        val method: String,
        val path: String,
        val query: String?,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    private fun readHttpRequest(input: BufferedInputStream): ParsedRequest? {
        val headerBytes = readUntilHeadersEnd(input) ?: return null
        val lines = String(headerBytes, Charsets.ISO_8859_1).split("\r\n")
        if (lines.isEmpty()) return null
        val requestLine = lines[0].split(" ")
        if (requestLine.size < 2) return null
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
            headers[line.substring(0, colon).trim()] = line.substring(colon + 1).trim()
        }
        return ParsedRequest(requestLine[0], path, query, headers, readBody(input, headers, requestLine[0]))
    }

    private fun readBody(input: BufferedInputStream, headers: Map<String, String>, method: String): ByteArray {
        if (method.equals("GET", true) || method.equals("HEAD", true)) return ByteArray(0)
        val te = header(headers, "Transfer-Encoding")
        if (te != null && te.contains("chunked", true)) return readChunked(input)
        val length = header(headers, "Content-Length")?.toIntOrNull() ?: 0
        if (length <= 0) return ByteArray(0)
        return readFully(input, length)
    }

    private fun header(headers: Map<String, String>, name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, true) }?.value

    private fun body(parsed: ParsedRequest) =
        parsed.body.toRequestBody(header(parsed.headers, "Content-Type")?.toMediaTypeOrNull())

    private fun isEventStreamRequest(parsed: ParsedRequest): Boolean {
        val accept = header(parsed.headers, "Accept")
        if (accept != null && accept.contains("text/event-stream", ignoreCase = true)) return true
        val path = parsed.path
        return path == "/event" || path.endsWith("/event")
    }

    private fun isWebSocketUpgrade(parsed: ParsedRequest): Boolean {
        val upgrade = header(parsed.headers, "Upgrade") ?: return false
        val connection = header(parsed.headers, "Connection") ?: return false
        return upgrade.contains("websocket", true) && connection.contains("upgrade", true)
    }

    private fun shouldDropRequestHeader(name: String): Boolean = when (name.lowercase(Locale.US)) {
        // origin / referer 必须剥 —— 这正是免 CORS 的关键：上游看到的是"无 Origin 的服务端请求"。
        "host", "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
        "te", "trailer", "transfer-encoding", "upgrade", "origin", "referer",
        "content-length", "accept-encoding", "authorization",
        -> true
        else -> false
    }

    private fun shouldDropResponseHeader(name: String): Boolean = when (name.lowercase(Locale.US)) {
        "connection", "keep-alive", "proxy-authenticate", "transfer-encoding", "trailer" -> true
        else -> false
    }

    /**
     * F4: classify the bytes of one streaming read into a round-lifecycle signal.
     *
     * Why this exists: the official UI keeps ONE long-lived `GET /event`
     * subscription whose ~10 s heartbeat used to refresh
     * [RoundActivityTracker.onRoundActivity] unconditionally, so `idleMs` never
     * reached `SSE_IDLE_READ_TIMEOUT_MS` and the round-end branch in
     * [copyStreaming] could not fire. V5 device log: the flag stayed set and was
     * re-set at +90/+180/+270 s after the turn had already finished.
     *
     * Fix: heartbeat frames no longer keep the round alive, and the upstream
     * `session.status`->`idle` / `session.idle` frame ends the round immediately.
     *
     * The scan window keeps the previous tail so a marker split across two reads
     * is still seen. It is cleared as soon as a payload is classified, so an old
     * marker cannot be re-matched on the next read.
     */
    private fun scanRoundStream(
        window: StringBuilder,
        bytes: ByteArray,
        n: Int,
    ): RoundActivityTracker.RoundStreamSignal {
        window.append(String(bytes, 0, n, Charsets.UTF_8))
        val signal = roundActivity.classifyRoundStream(window.toString())
        if (signal != RoundActivityTracker.RoundStreamSignal.HEARTBEAT) {
            window.setLength(0)
        } else if (window.length > SSE_SCAN_WINDOW_CHARS) {
            window.delete(0, window.length - SSE_SCAN_WINDOW_CHARS)
        }
        return signal
    }

    private fun copyStreaming(input: InputStream, output: OutputStream) {
        val buf = ByteArray(8192)
        val scanWindow = StringBuilder()
        var total = 0L
        var exit = "eof"
        while (true) {
            val n = try {
                input.read(buf)
            } catch (e: SocketTimeoutException) {
                Log.w(TAG, "upstream stream idle timeout: ${e.message}")
                exit = "idle-timeout"
                break
            } catch (e: Exception) {
                Log.w(TAG, "upstream stream read failed after $total bytes: ${e.javaClass.simpleName}: ${e.message}")
                exit = "read-error"
                break
            }
            if (n < 0) break
            if (n == 0) continue
            // D1：搬到了字节 = 回合还活着，续命（防 90s 兜底超时把活跃回合判死）。
            if (roundActivity.onRoundStreamSignal(
                    System.currentTimeMillis(),
                    scanRoundStream(scanWindow, buf, n),
                )
            ) {
                publishRoundActive()
            }
            total += n
            try {
                output.write(buf, 0, n)
                output.flush()
            } catch (e: Exception) {
                Log.w(TAG, "downstream write failed after $total bytes: ${e.message}")
                exit = "write-error"
                break
            }
        }
        if (total == 0L || exit != "eof") {
            Log.w(TAG, "stream ended: exit=$exit bytes=$total")
        }
        // D1：回合结束信号。**只认"静默够久"这一种关闭**：
        // 页面 EventSource 会按退避节奏重连（实测 2s，V4 §2 记过），
        // 若"socket 一关就算回合结束"，重连窗口里 flag 会被反复置起/清除抖动。
        // 上游 `/event` 每 10 s 一次心跳（T1 §7.2）⇒ 静默超过一个 SSE 读超时
        // （[SSE_IDLE_READ_TIMEOUT_MS]）才认定回合真的停了。
        if (roundActivity.isRoundActive(System.currentTimeMillis()) &&
            roundActivity.idleMs(System.currentTimeMillis()) >= SSE_IDLE_READ_TIMEOUT_MS
        ) {
            if (roundActivity.onRoundEnd()) publishRoundActive()
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
            if (size == 0) { readLineAscii(input); break }
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
        for ((name, value) in headers) output.write("$name: $value\r\n".toByteArray())
        output.write("\r\n".toByteArray())
        if (body != null) output.write(body)
        output.flush()
    }

    private fun jsonString(value: String): String = buildString {
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
