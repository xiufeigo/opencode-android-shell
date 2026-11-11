package com.xiufeigo.opencodeshell.web.host

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
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
    }.toString()

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
     * UX6：代理已删。UX8 起本方法的生产驱动方 = 宿主注入层的**回合电源传感器**
     * （`oc-host.js installRoundPowerSensor`：fetch 拦截到发消息 POST 与
     * /api/event 上的 session.execution.* 帧 ⇒ 调本方法）。请求喂给跟踪器后由
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
            // P7 · 回合开始 = 宿主首次观测到"有活要干"的真实时刻。
            // 这里主动申请一次 POST_NOTIFICATIONS，打破 P6 §D.3 判定的死锁
            // （上游闸门只挂终态帧 ⇒ 终帧不到 ⇒ 闸门从不评估 ⇒ 永不申请）。
            // 复用既有 launcher 链，未 granted 且在前台时才弹。
            ctx.runOnUiThread { ctx.requestNotificationPermissionOnRoundStart() }
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

    /**
     * 页面把自己解析后的表面底色（`getComputedStyle().backgroundColor`）报上来，
     * 宿主拿它刷视口压矮后露出的那条带子 —— 条带跟页面主题走，与系统模式脱钩。
     */
    @JavascriptInterface
    fun reportSurfaceColor(cssColor: String?) {
        ctx.runOnUiThread { ctx.applySurfaceColor(cssColor) }
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
     * 于是页面侧收到的入参是这个**字符串**而不是会话 id 本身：
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
     * 所以这里改成：**宿主自己算出目标路由，让 facade 去导航**。
     * 路径由 [NotifierHost.sessionRoutePath] 纯函数产出，可在单测里断言。
     * 真机证据见 `research/web/NOTIFICATION_CLICK_ROOTCAUSE.md`。
     */
    fun deliverOpenSession(sessionId: String?) {
        val arg = NotifierHost.clickPlan(sessionId).pageArg()
        val backendUrl = runCatching {
            SecretStore.getSecret(ctx, SecretStore.KEY_BACKEND_URL)
        }.getOrNull() ?: SecretStore.DEFAULT_BACKEND_URL
        // 造得出路由就导航（真回会话）；造不出就不硬造一条必然 404 的路径。
        val route = NotifierHost.sessionRoutePath(backendUrl, arg)
        Log.i(TAG, "deliverOpenSession session=$arg route=${route ?: "<none>"}")
        if (route == null) return
        ctx.runOnUiThread {
            ctx.evaluateJs(
                "window.__ocAndroid && window.__ocAndroid.__openSessionByRoute(" +
                    "${quote(route)}, ${quote(arg)});"
            )
        }
    }

    /** 把一段 JSON 变成一个 JS 字符串字面量（转义引号/反斜杠/换行）。 */
    private fun quote(s: String): String = JSONObject.quote(s)
}
