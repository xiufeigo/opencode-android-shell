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

        val intent = Intent(context, WebHostActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_FROM_NOTIFICATION, true)
            sessionId?.takeIf { it.isNotBlank() }?.let { putExtra(EXTRA_SESSION_ID, it) }
        }
        val pending = PendingIntent.getActivity(
            context,
            sessionId?.hashCode() ?: 0,
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
            NotificationManagerCompat.from(context).notify(sessionId?.hashCode() ?: 1, notification)
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
