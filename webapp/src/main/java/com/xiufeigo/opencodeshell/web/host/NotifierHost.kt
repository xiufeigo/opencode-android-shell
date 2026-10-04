package com.xiufeigo.opencodeshell.web.host

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.xiufeigo.opencodeshell.web.WebHostActivity
import java.util.Base64

/**
 * 通知：渠道 + postMessage(title, body, sessionId)，点击回会话。
 *
 * 点击回会话靠 [PendingIntent] 打到 [WebHostActivity]：
 * - `launchMode=singleTask` + `FLAG_ACTIVITY_SINGLE_TOP|FLAG_ACTIVITY_CLEAR_TOP`
 *   ⇒ 已在栈里就复用同一实例并回调 `onNewIntent`，**不会**叠一层新的；
 *   这点对"从通知直接落到某个 sessionId"是必需的，否则每点一次通知多一个 Activity。
 * - 目标 sessionId 放在 extra `EXTRA_SESSION_ID`，由 `onNewIntent`/`onCreate` 取出
 *   后交给页面（`window.__ocAndroid.onOpenSession(sessionId)`）。
 */
object NotifierHost {
    private const val TAG = "OcWebHost/Notify"

    const val CHANNEL_TURNS = "oc_turns"
    const val EXTRA_SESSION_ID = "oc_session_id"
    const val EXTRA_FROM_NOTIFICATION = "oc_from_notification"

    /**
     * N1：点击通知 ⇒ 回到会话 的**纯函数**契约。
     *
     * 抽出来是为了让「通知点击意图里带着正确的会话标识」变成**单测可断言的量**
     * （`webapp/src/test/.../NotificationClickPlanTest.kt`），
     * 而不是只能靠人读 [postTurnFinished] 的 20 行代码去确认。
     *
     * 三个量必须同时成立，缺一个「点击回到会话」就不成立：
     *  1. [sessionId] —— 目标会话；`null`/空白 ⇒ 通知不带会话（无从回起）。
     *  2. [flags] —— `SINGLE_TOP|CLEAR_TOP`：已在栈里就复用同一实例走 `onNewIntent`，
     *     **不叠第二个 Activity**。
     *  3. [notifyId] / [requestCode] —— 按 sessionId 分桶：同一会话覆盖同一条通知，
     *     不同会话互不覆盖。
     */
    data class ClickPlan(
        val flags: Int,
        val fromNotification: Boolean,
        val sessionId: String?,
        val notifyId: Int,
        val requestCode: Int,
    ) {
        /**
         * 交给页面的 `window.__ocAndroid.onOpenSession(…)` 的**参数原文**。
         *
         * 必须是 sessionId **本身**，不是 `{"sessionId":"…"}` 这种再包一层的 JSON 串：
         * facade 的 `onOpenSession(sessionId)` 把入参当**裸字符串**用
         * （`oc-host.js:282-291`：`sessionHandler(sessionId)` +
         * `new CustomEvent("oc:openSession", { detail: { sessionId: sessionId } })`），
         * 页面侧拿到的因此应当直接就是 `n1-session-777`。
         * 多包一层 ⇒ 页面拿到 `'{"sessionId":"n1-session-777"}'`，
         * 拿它去当 session id 用必然查不到会话。
         */
        fun pageArg(): String = sessionId.orEmpty()
    }

    /** [ClickPlan] 的唯一构造点。`postTurnFinished` 与单测都走它，避免两处各算一套。 */
    fun clickPlan(sessionId: String?): ClickPlan {
        val sid = sessionId?.takeIf { it.isNotBlank() }
        return ClickPlan(
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP,
            fromNotification = true,
            sessionId = sid,
            // 同一会话 ⇒ 同一 id ⇒ 覆盖同一条通知（而不是每回合多一条）。
            notifyId = sid?.hashCode() ?: NO_SESSION_NOTIFY_ID,
            requestCode = sid?.hashCode() ?: 0,
        )
    }

    /** 没有会话标识时的通知 id / PendingIntent requestCode 兜底值。 */
    const val NO_SESSION_NOTIFY_ID = 1

    // ======================================================================
    // N2：把"点击回会话"从**空信号**变成**真的路由跳转**
    //
    // 根因（真机 + 上游源码双向取证，见 research/web/NOTIFICATION_CLICK_ROOTCAUSE.md）：
    //  N1 修完之后，宿主把 sessionId 交给了 `window.__ocAndroid.onOpenSession(id)`，
    //  facade 转手 `dispatchEvent(new CustomEvent("oc:openSession", …))`。
    //  但**上游 SPA 里没有任何一处监听这个事件**：
    //    - `_upstream/packages/app` 全文没有 `oc:openSession` / `__ocAndroid` /
    //      `setOpenSessionHandler`（连上游自己的 `opencode:deep-link` 都没人监听）；
    //    - 上游唯一支持的"打开某个会话"入口是**路由**
    //      `/server/<base64url(serverKey)>/session/<id>`
    //      （`shell/routes/routes.tsx:39-40`、`shell/routes/session.ts:5-7`）。
    //  ⇒ 原实现把 sessionId 派发进了虚空：**通知点了，页面永远不会切会话。**
    //
    // 所以宿主必须自己**驱动路由**。serverKey = base64url(后端 URL)，上游在
    // `shell/state/layout.tsx:116-119` 解析它；后端 URL 宿主本来就知道
    // （`SecretStore.KEY_BACKEND_URL`，默认 `http://127.0.0.1:4095`）。
    //
    // 真机实测（emulator-5880 / 1080x2400@420）：
    //  `http://127.0.0.1:4095` → `aHR0cDovLzEyNy4wLjAuMTo0MDk1`，
    //  导航到 `/server/aHR0cDovLzEyNy4wLjAuMTo0MDk1/session/ses_efc722bf…` 路由成立。
    // ======================================================================

    /**
     * 后端 URL → 上游路由里的 `serverKey`。
     *
     * 口径与上游 `packages/util/src/encode.ts` 的 `base64Encode` 逐字一致：
     * 标准 base64，`+`→`-`、`/`→`_`、去掉 `=` 填充。
     *
     * 用 [Base64]（java.util，minSdk 26 起可用）而不是 `android.util.Base64`：
     * 后者在纯 JVM 单测里是空壳（直接抛/返回 0），会逼这条逻辑只能靠真机验证。
     */
    fun serverKeyOf(serverUrl: String?): String? {
        val raw = serverUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return null
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(raw.toByteArray(Charsets.UTF_8))
    }

    /**
     * 点击通知时页面应当去到的**路径**（纯函数，可在单测里断言）。
     *
     * @return null 表示"造不出可导航的路由"（缺后端 URL 或缺会话 id）⇒ 宿主保持旧行为
     *         （仍然派发 `oc:openSession` 事件），不硬造一条必然 404 的路径。
     */
    fun sessionRoutePath(serverUrl: String?, sessionId: String?): String? {
        val key = serverKeyOf(serverUrl) ?: return null
        val sid = sessionId?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return "/server/$key/session/${encodePathSegment(sid)}"
    }

    /**
     * 单个路径段的百分号编码。
     *
     * 不用 `Uri.encode`（android 依赖 ⇒ 纯 JVM 单测里不可用），
     * 口径对齐 RFC 3986 的 unreserved：`ALPHA / DIGIT / "-" / "." / "_" / "~"`。
     */
    fun encodePathSegment(raw: String): String {
        val hex = "0123456789ABCDEF"
        return buildString(raw.length) {
            for (b in raw.toByteArray(Charsets.UTF_8)) {
                val u = b.toInt() and 0xFF
                val c = u.toChar()
                val safe = (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') ||
                    c == '-' || c == '.' || c == '_' || c == '~'
                if (safe) append(c) else { append('%'); append(hex[u shr 4]); append(hex[u and 0xF]) }
            }
        }
    }

    /** Android 8.0+ 必须先建渠道才能 post。 */
    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_TURNS,
            "回合完成",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "回合结束时提醒"
            enableLights(true)
            setShowBadge(true)
        }
        runCatching { nm.createNotificationChannel(channel) }
            .onFailure { Log.w(TAG, "createNotificationChannel failed", it) }
    }

    /** @return 0=成功，-1=没权限（Android 13+），-2=其它失败。 */
    fun postTurnFinished(context: Context, title: String?, body: String?, sessionId: String?): Int {
        ensureChannels(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return -1
        }

        val plan = clickPlan(sessionId)
        val intent = Intent(context, WebHostActivity::class.java).apply {
            flags = plan.flags
            putExtra(EXTRA_FROM_NOTIFICATION, plan.fromNotification)
            plan.sessionId?.let { putExtra(EXTRA_SESSION_ID, it) }
        }
        val pending = PendingIntent.getActivity(
            context,
            plan.requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_TURNS)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title?.takeIf { it.isNotBlank() } ?: "回合完成")
            .setContentText(body.orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body.orEmpty()))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        return try {
            NotificationManagerCompat.from(context).notify(plan.notifyId, notification)
            0
        } catch (t: SecurityException) {
            Log.w(TAG, "notify denied", t)
            -1
        } catch (t: Throwable) {
            Log.w(TAG, "notify failed", t)
            -2
        }
    }
}
