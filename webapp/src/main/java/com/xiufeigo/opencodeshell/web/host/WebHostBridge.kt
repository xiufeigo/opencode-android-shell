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
        put("homeUrl", runCatching {
            SecretStore.getSecret(ctx, SecretStore.KEY_BACKEND_URL)
        }.getOrNull() ?: SecretStore.DEFAULT_BACKEND_URL)
        put("scheme", "WebHostSkeleton/1")
        put("proxyOrigin", PROXY_ORIGIN)
        put("proxyPrefix", PROXY_PREFIX)
        put("maxInlineBytes", FileChooserHost.MAX_INLINE_BYTES)
    }.toString()

    // 上游 android-proxy.ts 约定：把真实后端 URL 改写到应用内回环代理上。
    //
    // UX6：回环代理已删，这里恒返回空串 —— 打了补丁的上游前端
    // （frontend/patches/android-webview-z-native-proxy.patch 的 readAndroidProxyConfig）
    // 要求 origin 与 prefix **都非空**才改写；两者皆空 ⇒ 前端用真实 URL 直连，
    // 正好是本架构（页面 origin == 后端 origin）的行为。
    private val PROXY_ORIGIN: String get() = ""
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

    // ---- 5. 宿主偏好 -------------------------------------------------------
    // ⚠ UX6：这里**没有**任何口令读写。回环代理已删，宿主不持有凭据；
    // 服务器/口令配置全在官方 UI「设置 → 服务器」里，存页面的 localStorage。
    // getSecret/setSecret 只服务于**非秘密**的宿主偏好（如后端地址），
    // 且仍走 EncryptedSharedPreferences（Historische 一致性 + 未来宿主秘密的存放点）。
    @JavascriptInterface
    fun getSecret(key: String?): String? = SecretStore.getSecret(ctx, key.orEmpty())

    @JavascriptInterface
    fun setSecret(key: String?, value: String?): Boolean {
        if (key.isNullOrBlank()) return false
        SecretStore.setSecret(ctx, key, value)
        return true
    }

    // ---- 7. 常亮 ---------------------------------------------------------
    /**
     * 回合进行中传 true 置 FLAG_KEEP_SCREEN_ON，结束传 false 清掉。
     *
     * P6：置位**多一道前台校验** —— 不在前台时 `on=true` 直接被拒并记日志。
     * 理由：常亮是 T1 §7.3 认定"接上之后 = 高风险"的项（耗电大户、且是看起来很
     * 合理的那类 bug）。页面若在 `visibilitychange` 时序错位（先发 `true` 后退后台），
     * 旧实现会把 flag 挂到一个不可见的窗口上。判据见 [PowerPolicy.shouldKeepScreenOn]。
     *
     * D1：这一段曾经是**死代码**（`research/web/FIX_D1.md` §1）—— 它是 flag 的
     * 唯一写入口，却只有页面能调，而上游官方 UI 从不调 `setKeepScreenOn` ⇒
     * `addFlags` 那行永远执行不到，"回合进行中保持屏幕常亮"从未生效。
     *
     * UX6：代理已删，本方法是 [RoundActivityTracker] **唯一**的生产驱动方
     * （上游官方 UI 目前仍不调它 —— 这条功能处于"页面可用、待上游接线"状态，
     * 见 WebHostActivity 的类注释）。请求喂给跟踪器后由
     * [WebHostActivity.applyKeepScreenOn] 统一落 flag。页面这条 API 语义不变。
     */
    @JavascriptInterface
    fun setKeepScreenOn(on: Boolean) {
        val foreground = ctx.isForeground()
        if (on && PowerPolicy.mustClearKeepScreenOn(foreground = foreground, roundActive = true)) {
            Log.w(TAG, "setKeepScreenOn(true) ignored: not in foreground")
            return
        }
        val tracker = ctx.roundActivityTracker()
        if (on) {
            tracker.onRoundStart(System.currentTimeMillis())
        } else {
            tracker.onRoundEnd()
        }
        // 落 flag 的动作统一走宿主，页面与宿主两条路只有一个写入口。
        ctx.applyKeepScreenOnState()
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
     * 把通知带回来的 sessionId 交给页面 —— 并且**真的让页面切到那个会话**。
     *
     * 🔴 N1 修掉的 bug：原先这里把 sessionId **再包一层 JSON** 才发给页面——
     * `JSONObject().put("sessionId", id).toString()` 得到 `'{"sessionId":"n1-x"}'`，
     * 于是 `onOpenSession()` 收到的入参是这个**字符串**而不是会话 id 本身。
     * facade 的 `onOpenSession(sessionId)` 把入参当裸 id 用
     * （`oc-host.js:282-291`），页面最终拿到
     * `e.detail.sessionId === '{"sessionId":"n1-session-777"}'`，
     * 拿它当 session id 去查会话必然查不到 ⇒ **"点击回到会话"名存实亡**。
     *
     * 🔴 N2 修掉的更深的 bug：就算入参是**裸 id**（N1 之后），这条链**依然切不了会话**，
     * 因为 `oc:openSession` 这个 CustomEvent **上游 SPA 根本没有监听者**。
     * 上游唯一支持的入口是路由 `/server/<base64url(serverKey)>/session/<id>`
     * （`_upstream/packages/app/src/shell/routes/routes.tsx:39-40`、
     *  `shell/routes/session.ts:5-7`），上游自己的
     * `openNotificationSession()`（`shell/notifications/notification.tsx:54-59`）
     * 做的也正是 `navigate(tabHref(tab))` —— **导航，而不是派发事件**。
     *
     * 所以这里改成：**宿主自己算出目标路由，让 facade 去导航**；
     * 事件仍然照发（不破坏任何可能已经监听了它的东西）。
     * 路径由 [NotifierHost.sessionRoutePath] 纯函数产出，可在单测里断言。
     * 真机证据见 `research/web/NOTIFICATION_CLICK_ROOTCAUSE.md`。
     */
    fun deliverOpenSession(sessionId: String?) {
        val arg = NotifierHost.clickPlan(sessionId).pageArg()
        val backendUrl = runCatching {
            SecretStore.getSecret(ctx, SecretStore.KEY_BACKEND_URL)
        }.getOrNull() ?: SecretStore.DEFAULT_BACKEND_URL
        // 造得出路由就导航（真回会话）；造不出就退回旧行为（只派发事件，不硬造 404 路径）。
        val route = NotifierHost.sessionRoutePath(backendUrl, arg)
        Log.i(TAG, "deliverOpenSession session=$arg route=${route ?: "<none>"}")
        ctx.runOnUiThread {
            if (route != null) {
                ctx.evaluateJs(
                    "window.__ocAndroid && window.__ocAndroid.__openSessionByRoute(" +
                        "${quote(route)}, ${quote(arg)});"
                )
            } else {
                ctx.evaluateJs(
                    "window.__ocAndroid && window.__ocAndroid.onOpenSession(${quote(arg)});"
                )
            }
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
