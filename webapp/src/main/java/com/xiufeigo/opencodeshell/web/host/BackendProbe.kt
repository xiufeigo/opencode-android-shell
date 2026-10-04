package com.xiufeigo.opencodeshell.web.host

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * F5 · 接入前的**主动探测**：在加载官方 UI 之前，先自己探一次后端，把"能不能连上"和
 * **"是不是口令不对"**这两件用户最关心的事**变成宿主自己的判定**，而不是让页面去猜。
 *
 * ## 为什么必须宿主主动探（V7 实测的根因）
 *
 * V7 播种错口令后，代理层**每 ~1 s 都在报**认证失败（原�� logcat）：
 * ```
 * W OcWebHost/Proxy: auth rejected: GET http://127.0.0.1:4095/api/event -> 401; user=opencode passLen=12 authHeaderSent=true
 * W OcWebHost/Proxy: auth rejected: GET http://127.0.0.1:4095/api/info  -> 401; user=opencode passLen=12 authHeaderSent=true
 * ```
 * 但页面**永远停在 "Loading" 骨架屏**（`research/env/v7/v7_n5_wrongpw.png`，
 * `uiautomator` 树仅 2,248 B、无任何错误文案节点）⇒ **用户无从自救**：
 * 既看不到"认证失败"，也没有"重试/重新输口令"的入口（口令框已被一次性对话框消费掉）。
 *
 * 页面自己**永远不会**给出这个提示：错口令时上游对 `/api/…` 一律 401，
 * 官方 SPA 的骨架屏只是"等不到数据"（`origin/v2` 侧无任何 401 提示分支，
 * 见 `research/web/V7_CLOSEOUT.md` §2.2）。所以**只能在原生侧判定**。
 *
 * ## 探哪个端点、为什么是 `/api/info`
 *
 * 探 `/api/info`（**GET + Basic**）。理由有三，都能在本仓里查到：
 *
 *  1. 它是**免认证 401、Basic 200** 的那一档端点 —— [LoopbackProxy] 的类注释
 *     （`LoopbackProxy.kt:31-32`）已把这条约定写明："只有 `/api/…` 要 Basic 认证
 *     （无认证 401、Basic 200）"。
 *  2. 它**足够轻**：不订阅、不开流，探完即弃，不会留下需要清理的长连接。
 *  3. 它**语义就是"服务端是否接受这组凭据"**，与"页面能不能加载"解耦：
 *     页面 200 但 API 全 401 正是本缺陷的形态。
 *
 * ## 判定规则（[classify]，纯函数、可单测）
 *
 * | 观察 | 判定 | 界面上给用户的话 |
 * |---|---|---|
 * | **带凭据** `2xx` / `3xx` | [Result.OK] | 不打扰，**直接 `launch()` 进官方 UI** |
 * | **带凭据** `401` / `403` | [Result.AuthFailed] | **用户名或口令不对**（不说哪个，因为分不清），可重试 |
 * | 连不上 / 超时 / `5xx` | [Result.Unreachable] | 连不上服务器，请检查地址与网络 |
 * | 其它 | [Result.Unknown] | 出了点问题，可重试 |
 *
 * ### F6：三态必须是"带凭据"下的三态（这一条曾经是坏的）
 *
 * 关键在于**上面每一行都预设了请求带着 Basic 凭据**。F5 引发的缺陷正是这一点被打破：
 * 那时每一次探测都是**匿名**请求（见 [buildProbeRequest] 的注释与
 * `research/web/V8_VERIFY.md` §0），于是后端一律回 401，**口令正确的用户也被判成
 * [Result.AuthFailed]** —— 明明输对了，却被告知"用户名或口令不对"。
 * 也就是说：**"判 AuthFailed"这个动作本身没错，错的是判它所依据的那次请求根本没带凭据。**
 *
 * 修好后的三态（这是本类现在守住的不变量）：
 * **带凭据 200 ⇒ [Result.OK] ⇒ 正常 `launch()` 进官方 UI**；
 * **带凭据 401 ⇒ [Result.AuthFailed]**（"用户名或口令不对" + 可重试）；
 * **连不上/超时 ⇒ [Result.Unreachable]**（与认证失败**严格分开**：前者引导查地址网络，
 * 后者引导改口令）。
 *
 * ## 超时值与依据
 *
 * **8 000 ms**，依据三条，全部可核：
 *
 *  1. **上界必须小于代理的连接超时**：[LoopbackProxy.defaultClient] 的
 *     `connectTimeout` 是 **15 s**（`LoopbackProxy.kt:116`）。若探测定得比它还长，
 *     探���就失去意义 —— 页面早就自己 502 了。所以取 15 s 的一半再留余量。
 *  2. **必须远大于真实耗时**：本机回环 + `adb reverse` 下 `/api/info` 的实测是
 *     **毫秒级**（设备侧实测见 `research/web/FIX_F5.md` §3），8 s 是它的三个数量级以上，
 *     在冷启动、首次 TLS-free 建连、模拟器 CPU 争抢下都不会误杀健康请求。
 *  3. **必须短到用户能接受**：这是**挡在加载前面的串行一步**，超过 ~10 s 就会开始
 *     被感知为"应用卡住"。8 s 落在"用户还愿意等、但不该再等更久"的区间。
 *
 * 探测**整体**还有一层上界：宿主用后台线程跑（`probeAsync`），**主线程不阻塞**，
 * 所以即使最坏情况也只是多等 8 s 才给提示，不会 ANR。
 *
 * ## 口令纪律
 *
 * **口令绝不进日志**。[probe] 只打印 `passLen`（长度）这种已有的安全记法
 * （与 `LoopbackProxy.kt:421` 同款），`[Result]` 里也**不含**口令；
 * 错误文案由 [ConnectErrorPage.Kind.AUTH_FAILED] 提供，只说"用户名或口令不对"。
 */
class BackendProbe(
    private val httpClient: OkHttpClient = defaultClient(),
) {

    companion object {
        private const val TAG = "OcWebHost/Probe"

        /**
         * 探测超时。见类注释"超时值与依据"三条。
         * 15 s（代理 connectTimeout）的一半量级，且远大于实测毫秒级耗时。
         */
        const val PROBE_TIMEOUT_MS = 8_000L

        /** 与 [LoopbackProxy.BASIC_USER] 同值：用户名是固定约定，只有口令是秘密。 */
        const val BASIC_USER = LoopbackProxy.BASIC_USER

        /** 探测端点：免认证 401 / Basic 200（[LoopbackProxy] 类注释 `:31-32`）。 */
        const val PROBE_PATH = "/api/info"

        internal fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    }

    /** 探测结论。[message] 只放**可安全展示**的分类文案，不含口令、不含内部 URL。 */
    sealed class Result {
        /** 后端接受了这组凭据。 */
        data class OK(val code: Int) : Result()

        /** 后端明确拒绝凭据（401/403）⇒ **"用户名或口令不对"**，用户改口令即可自救。 */
        data class AuthFailed(val code: Int) : Result()

        /** 连不上 / 超时 / 5xx ⇒ 引导用户检查服务器地址与网络。 */
        data class Unreachable(val reason: String) : Result()

        /** 其它未归类（如 404、协议异常）⇒ 仍要给可重试界面。 */
        data class Unknown(val reason: String) : Result()
    }

    /**
     * 纯判定：把"HTTP 状态码 或 异常"映射成 [Result]。
     *
     * 单独抽成纯函数是为了能被 `:webapp:testDebugUnitTest` 覆盖 ——
     * 真实 HTTP 往返在单测里既慢又需要设备，而**判定规则本身**才是要守护的东西。
     *
     * @param code HTTP 状态码；null 表示"根本没拿到响应"（连不上 / 超时 / 异常）
     */
    fun classify(code: Int?, reason: String?): Result = when {
        code == null -> Result.Unreachable(reason ?: "no response")
        code == 401 || code == 403 -> Result.AuthFailed(code)
        code in 200..399 -> Result.OK(code)
        code >= 500 -> Result.Unreachable("upstream $code")
        else -> Result.Unknown("upstream $code")
    }

    /**
     * F6：**唯一**的探测请求构造点 —— 每次探测都从这里出，**必然带 Basic 凭据**。
     *
     * ## 为什么必须只有这一个构造点（V8 缺陷的根因）
     *
     * F5 之前有**两处**各造一次请求：[probe] 造带 `Authorization` 的 `builder`，
     * [probeAsync] 为了让 [cancel] 能拿到在途 `Call`，**又造了一个不带 `Authorization`
     * 的 `Request`**。而 [probe] 里的 `call ?: httpClient.newCall(builder.build())`
     * 会**优先采用那个匿名 `call`**，于是那份带凭据的 `builder` 被整份丢弃。
     *
     * 后果（V8 §0 实测）：**每一次探测都是匿名请求** ⇒ 后端一律回 **401**
     * ⇒ [classify] 判成 [Result.AuthFailed] ⇒ **口令正确的用户也被告知
     * "用户名或口令不对"**（`V8_VERIFY.md` L74 / L309-310 / L453）。
     *
     * **修法不是"再补一个 header"，而是让这条路径在结构上不可能匿名**：
     * 认证头写死在这里，[probeAsync] 不再自己造 Request，而是调用本方法。
     * 这样 `call` 与 `builder` 永远来自同一份带凭据的请求，匿名分支不复存在。
     *
     * 顺带把 `Base64` 从 `android.util` 换成 `java.util`：
     * 前者是 Android framework 的桩实现，**在 JVM 单测里会抛
     * `RuntimeException("Stub!")`**，所以"探测请求是否带 Basic"这条断言
     * 只能在设备上验证。`java.util.Base64` 自 **API 26** 起可用（本模块
     * `minSdk = 26`），于是本方法**可以在 `:webapp:testDebugUnitTest` 里直接
     * 构造请求并断言请求头**（见 `ConnectRobustnessTest`）。
     *
     * @param password 口令；空/null ⇒ **不发** `Authorization`（此时后端回 401，
     *   判定为 [Result.AuthFailed]，与"口令错"同归一 —— 反正都要用户去输口令）。
     */
    internal fun buildProbeRequest(backend: String, password: String?): Request {
        val builder = Request.Builder()
            .url(backend.trim().trimEnd('/') + PROBE_PATH)
            .get()
        if (!password.isNullOrEmpty()) {
            val token = java.util.Base64.getEncoder().encodeToString(
                "$BASIC_USER:$password".toByteArray(Charsets.UTF_8),
            )
            builder.header("Authorization", "Basic $token")
        }
        return builder.build()
    }

    /**
     * 同步探一次。**必须在后台线程调用**（[probeAsync] 负责这件事）。
     *
     * @param backend 后端基址，例如 `http://127.0.0.1:4095`
     * @param password 口令；空 ⇒ 不发 Authorization（后端应回 401，判为 [Result.AuthFailed]）
     * @param call 可选的在途 `Call`（供 [probeAsync] 登记以便 [cancel]）。
     *   **F6**：它若非 null，必须是由 [buildProbeRequest] 造出来的（[probeAsync]
     *   就是这么做的）⇒ 一定带凭据。留这个参数是为了"取消"能力，不是为了
     *   "换一条不带凭据的请求去探"。
     */
    fun probe(backend: String, password: String?, call: okhttp3.Call? = null): Result {
        val passLen = password?.length ?: -1
        return try {
            val call0 = call ?: httpClient.newCall(buildProbeRequest(backend, password))
            call0.execute().use { resp ->
                // 只打"有没有/多长"，与 LoopbackProxy 的既有记法一致：**绝不回显口令**。
                // 这里的 authHeaderSent 是 F6 的"实际线上行为"证据位：读的是
                // **真的发出去的那条请求**（call0.request()），不是某个局部变量。
                val sentAuth = call0.request().header("Authorization") != null
                Log.i(
                    TAG,
                    "probe $PROBE_PATH -> ${resp.code}; user=$BASIC_USER passLen=$passLen " +
                        "authHeaderSent=$sentAuth",
                )
                classify(resp.code, null)
            }
        } catch (e: Exception) {
            // 异常原文**只进日志**（排错需要），不进界面 —— 界面由 ConnectErrorPage 生成，
            // 它会 redact。所以这里不需要在这里手动 redact。
            Log.w(TAG, "probe $PROBE_PATH failed: ${e.javaClass.simpleName}: ${e.message}")
            classify(null, e.javaClass.simpleName)
        }
    }

    /** 探完在主线程回调。[onResult] 一定被调用（成功或失败都回调），便于界面收敛。 */
    fun probeAsync(backend: String, password: String?, onResult: (Result) -> Unit) {
        // F6：**用带凭据的请求**建 `Call`（此前这里造的是匿名请求，
        // 并且在 probe() 里顶替掉了带凭据的 builder —— 见 buildProbeRequest 的注释）。
        val call = httpClient.newCall(buildProbeRequest(backend, password))
        inflight.add(call)
        Thread({
            val result = probe(backend, password, call)
            inflight.remove(call)
            android.os.Handler(android.os.Looper.getMainLooper()).post { onResult(result) }
        }, "oc-backend-probe").apply { isDaemon = true }.start()
    }

    /**
     * F5：取消在途探测。点"重试"时用 —— 上一轮的结果不该再把界面带走。
     *
     * 取消后 [probe] 会把异常归为 [Result.Unreachable]，所以被取消的那轮
     * **不会**误判成"认证失败"（那才是会误导用户的那条分支）。
     */
    fun cancel() {
        for (c in inflight) runCatching { c.cancel() }
        inflight.clear()
    }

    private val inflight = java.util.concurrent.CopyOnWriteArrayList<okhttp3.Call>()
}
