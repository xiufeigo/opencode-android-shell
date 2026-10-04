package com.xiufeigo.opencodeshell.web.host

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.WindowManager
import android.webkit.JavascriptInterface
import androidx.core.content.ContextCompat
import com.xiufeigo.opencodeshell.web.WebHostActivity
import org.json.JSONObject

/**
 * 原生桥。注入名 `__ocAndroidNative`，页面侧请用 `window.__ocAndroid`（facade）。
 *
 * ⚠ 线程：`@JavascriptInterface` 方法**不在主线程**，在 WebView 的 JavaBridge 线程上。
 * 所有碰 Activity / Window / Intent 的都必须 `runOnUiThread`；纯文件/偏好读写的可以留在这里。
 * 返回值必须是 JSON 可序列化的（String / boolean / number）——我们全部返回 String 或 Int。
 */
class WebHostBridge(
    private val activity: WebHostActivity,
    /**
     * R1 回环代理的 origin 提供者。**延迟查询**，所以宿主可以先建桥、后起代理
     * （首次启动要先问密码，代理在那之后才起）。起好就返回 `http://127.0.0.1:<port>`。
     */
    private val proxyOriginProvider: () -> String? = { null },
) {

    companion object {
        const val INJECT_NAME = "__ocAndroidNative"
        private const val TAG = "OcWebHost/Bridge"
    }

    private val ctx get() = activity

    // ---- 元信息 ---------------------------------------------------------
    @JavascriptInterface
    fun hostInfo(): String = JSONObject().apply {
        put("appId", ctx.packageName)
        put("assetHost", WebHostActivity.ASSET_DOMAIN)
        put("homeUrl", WebHostActivity.HOME_URL)
        put("scheme", "WebHostSkeleton/1")
        put("proxyOrigin", PROXY_ORIGIN)
        put("proxyPrefix", PROXY_PREFIX)
        put("maxInlineBytes", FileChooserHost.MAX_INLINE_BYTES)
    }.toString()

    // 上游 android-proxy.ts 约定：把真实后端 URL 改写到应用内回环代理上。
    //
    // R1：**已实现**。页面本身就从 `PROXY_ORIGIN` 提供（见 WebHostActivity 的 homeUrl），
    // 所以 `origin` 就是页面自己的 origin —— 页面与 API 同源，后端 CORS 完全不参与。
    // `prefix` 保持空串：本代理不做路径改写，/api、/_assets、/event 逐字透传。
    private val PROXY_ORIGIN: String
        get() = runCatching { proxyOriginProvider().orEmpty() }.getOrDefault("")
    private val PROXY_PREFIX = ""

    @JavascriptInterface
    fun proxyOrigin(): String = PROXY_ORIGIN

    @JavascriptInterface
    fun proxyPrefix(): String = PROXY_PREFIX

    // ---- 1. 通知 ---------------------------------------------------------
    /** @return 1=已授权 0=未授权 -1=Android 13+ 不可用 */
    @JavascriptInterface
    fun hasNotificationPermission(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return 1
        return if (
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) 1 else 0
    }

    @JavascriptInterface
    fun requestNotificationPermission() {
        ctx.runOnUiThread { ctx.requestNotificationPermission() }
    }

    /** @return 0 ok / -1 no permission / -2 error */
    @JavascriptInterface
    fun postMessage(title: String?, body: String?, sessionId: String?): Int {
        val t = title.orEmpty().take(200)
        val b = body.orEmpty().take(2000)
        val s = sessionId?.takeIf { it.isNotBlank() }?.take(200)
        return NotifierHost.postTurnFinished(ctx, t, b, s)
    }

    // ---- 2. 返回（宿主用 evaluateJavascript 调 __ocAndroid.onBack()）------
    // 这里不放 onBack：它是"问页面"的回调，facade 里定义。见 oc-host.js。

    // ---- 3. 文件 ---------------------------------------------------------
    @JavascriptInterface
    fun pickFile(accept: String?, multiple: Boolean) {
        ctx.runOnUiThread { ctx.launchOpenFile(accept, multiple) }
    }

    /** @return JSON：{uri,name,size,base64,truncated} 或 {error} */
    @JavascriptInterface
    fun readPickedFile(): String = ctx.readPickedInline()

    @JavascriptInterface
    fun clearPickedFile() {
        ctx.clearPickedUri()
    }

    @JavascriptInterface
    fun saveContent(name: String?, mime: String?, base64: String?) {
        ctx.runOnUiThread { ctx.launchSave(name, mime, base64) }
    }

    // ---- 4. 分享 ---------------------------------------------------------
    @JavascriptInterface
    fun share(text: String?, title: String?): Boolean {
        val t = text.orEmpty()
        if (t.isBlank()) return false
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, t)
            title?.takeIf { it.isNotBlank() }?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(send, title?.takeIf { it.isNotBlank() } ?: "分享").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { ctx.startActivity(chooser); true }
            .onFailure { Log.w(TAG, "share failed", it) }
            .getOrDefault(false)
    }

    // ---- 5. 凭据 ---------------------------------------------------------
    // ⚠ 宿主专用键（serverPassword）**拒绝**经桥访问：回环代理自己在 Kotlin 侧补
    // Authorization，页面不需要、也不该知道口令。挡住它不影响代理工作。
    @JavascriptInterface
    fun getSecret(key: String?): String? {
        val k = key.orEmpty()
        if (SecretStore.isHostOnly(k)) {
            Log.w(TAG, "getSecret denied for host-only key")
            return null
        }
        return SecretStore.getSecret(ctx, k)
    }

    @JavascriptInterface
    fun setSecret(key: String?, value: String?): Boolean {
        if (key.isNullOrBlank()) return false
        if (SecretStore.isHostOnly(key)) {
            Log.w(TAG, "setSecret denied for host-only key")
            return false
        }
        SecretStore.setSecret(ctx, key, value)
        return true
    }

    // ---- 7. 常亮 ---------------------------------------------------------
    /** 回合进行中传 true 置 FLAG_KEEP_SCREEN_ON，结束传 false 清掉。 */
    @JavascriptInterface
    fun setKeepScreenOn(on: Boolean) {
        ctx.runOnUiThread {
            if (on) {
                ctx.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                ctx.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    // ---- 6. IME 策略 -----------------------------------------------------
    @JavascriptInterface
    fun setImeMode(mode: String?) {
        val m = when (mode?.lowercase()) {
            "resize" -> ImeMode.RESIZE
            else -> ImeMode.VARS
        }
        ctx.runOnUiThread { ctx.setImeMode(m) }
    }

    @JavascriptInterface
    fun getImeMode(): String = ctx.imeModeName

    // ---- 8. 主题 ---------------------------------------------------------
    @JavascriptInterface
    fun reportTheme(dark: Boolean) {
        ctx.runOnUiThread { ctx.applyPageTheme(dark) }
    }

    // ---- 9. 诊断页重试 + 首屏内容信号（G1）--------------------------------
    /**
     * `assets/www/oc-preflight.js` 回报「首屏真的有内容了」⇒ 解除宿主看门狗。
     *
     * 走的是**内容**信号而不是 `onPageFinished`：实测 WebView 83 上资源全部 200、
     * `onPageFinished` 照常回调，可页面是纯白的 —— 拿「加载完成」当信号永远抓不到它。
     */
    @JavascriptInterface
    fun onFirstContent(kind: String?, atMs: Long?) {
        ctx.runOnUiThread { ctx.onFirstContent(kind, atMs) }
    }

    /**
     * 诊断页的「重试」按钮调它：**重新走一遍完整的启动链**（重新做 G1 前置检查 →
     * 重新 loadUrl 官方 UI），而不是简单地 `webView.reload()`。
     *
     * 为什么不用 `reload()`：用户在诊断页上看到的 URL 是 `assets/www/index.html`，
     * reload 只会把诊断页自己再加载一遍，永远回不到官方 UI。
     *
     * 这条同时闭环了 `CLEANUP_C1.md` §4 第 4 条的未闭环（"兜底页没有重试按钮"）。
     */
    @JavascriptInterface
    fun retry() {
        Log.i(TAG, "G1 retry requested from diagnostic page")
        ctx.runOnUiThread { ctx.retryFromDiagnosticPage() }
    }

    // ---- 通知点击回会话 ---------------------------------------------------
    /**
     * 把通知带回来的 sessionId 交给页面。
     *
     * 🔴 N1 修掉的 bug：原先这里把 sessionId **再包一层 JSON** 才发给页面——
     * `JSONObject().put("sessionId", id).toString()` 得到 `'{"sessionId":"n1-x"}'`，
     * 于是 `onOpenSession()` 收到的入参是这个**字符串**而不是会话 id 本身。
     * facade 的 `onOpenSession(sessionId)` 把入参当裸 id 用
     * （`oc-host.js:282-291`），页面最终拿到
     * `e.detail.sessionId === '{"sessionId":"n1-session-777"}'`，
     * 拿它当 session id 去查会话必然查不到 ⇒ **"点击回到会话"名存实亡**。
     *
     * 真机证据见 `research/web/NOTIFICATION_CLICK_CLOSE.md` §4。
     * 现在直接发 sessionId 本身；`pageArg()` 是本仓唯一的取值口径。
     */
    fun deliverOpenSession(sessionId: String?) {
        val arg = NotifierHost.clickPlan(sessionId).pageArg()
        ctx.runOnUiThread {
            ctx.evaluateJs("window.__ocAndroid && window.__ocAndroid.onOpenSession(${quote(arg)});")
        }
    }

    fun emitFilePicked(payload: JSONObject) {
        val p = payload.toString()
        ctx.runOnUiThread {
            ctx.evaluateJs("window.__ocAndroid && window.__ocAndroid._emitFilePicked(${quote(p)});")
        }
    }

    fun emitFileSaved(payload: JSONObject) {
        val p = payload.toString()
        ctx.runOnUiThread {
            ctx.evaluateJs("window.__ocAndroid && window.__ocAndroid._emitFileSaved(${quote(p)});")
        }
    }

    /** 把一段 JSON 变成一个 JS 字符串字面量（转义引号/反斜杠/换行）。 */
    private fun quote(s: String): String = JSONObject.quote(s)
}
