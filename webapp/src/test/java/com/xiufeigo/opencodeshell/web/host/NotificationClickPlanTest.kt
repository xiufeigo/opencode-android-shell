package com.xiufeigo.opencodeshell.web.host

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * N1 绊线：通知**点击回到会话**的意图契约。
 *
 * ## 守护对象
 * [NotifierHost.clickPlan] —— 「点通知 ⇒ 回会话」这条链路上，**能被单测断言的三个量**：
 *  1. 点击意图里带的**会话标识**对不对（[NotifierHost.ClickPlan.sessionId]）；
 *  2. Activity flags 是不是 `SINGLE_TOP|CLEAR_TOP`（不叠第二个 Activity）；
 *  3. 交给页面的 [NotifierHost.ClickPlan.pageArg] 是不是**会话 id 本身**。
 *
 * ## 为什么要第 3 条（本轮真机查出来的真 bug）
 * 改前 `WebHostBridge.deliverOpenSession` 把 sessionId 再包一层 JSON 才发给页面，
 * 页面 `e.detail.sessionId` 收到的是 `'{"sessionId":"n1-session-777"}'` 而不是
 * `n1-session-777` —— 也就是说**通知点了也回不到那个会话**。
 * 真机证据：`research/web/NOTIFICATION_CLICK_CLOSE.md` §4。
 *
 * ## 每条断言分别能红在哪（反向验证见该报告 §6）
 * - `clickIntentCarriesTheSessionIdVerbatim` —— `putExtra` 改键名 / 改值 / 不写 ⇒ 红。
 * - `blankOrMissingSessionIdMeansNoSession` —— 去掉 `takeIf{isNotBlank()}` ⇒ 红。
 * - `flagsKeepSingleTopAndClearTop` —— 去掉任一 flag ⇒ 红（会叠第二个 Activity）。
 * - `distinctSessionsDoNotShareNotificationId` —— 退化成固定 id ⇒ 红（不同会话互相覆盖）。
 * - `pageArgIsTheBareSessionIdNotAJsonBlob` —— 把 `pageArg()` 改回 JSON 包装 ⇒ **红**。
 * - `postTurnFinishedWiresThePlan` / `deliverOpenSessionUsesPageArg` —— 把纯函数
 *   改成"只在测试里存在"（生产代码另算一套）⇒ 红。**这两条是防"纯函数摆设"的。**
 */
class NotificationClickPlanTest {

    private fun moduleFile(relative: String): String {
        val f = File(relative)
        assertTrue("找不到 $relative（单测工作目录应为 webapp/ 模块目录）", f.isFile)
        return f.readText()
    }

    private val notifierSource: String get() =
        moduleFile("src/main/java/com/xiufeigo/opencodeshell/web/host/NotifierHost.kt")

    private val bridgeSource: String get() =
        moduleFile("src/main/java/com/xiufeigo/opencodeshell/web/host/WebHostBridge.kt")

    // ---- 1. 意图里带着正确的会话标识 -------------------------------------

    @Test
    fun clickIntentCarriesTheSessionIdVerbatim() {
        val plan = NotifierHost.clickPlan("n1-session-777")
        assertEquals(
            "N1: 点击意图里的会话标识必须与 postMessage 传进来的逐字相同。" +
                "改了它，页面就回不到那个会话。",
            "n1-session-777",
            plan.sessionId,
        )
        assertTrue("N1: 点击意图必须带 `来自通知` 标记，否则 consumeNotificationIntent 直接 return", plan.fromNotification)
        assertEquals("N1: extra 键名被改了，WebHostActivity.consumeNotificationIntent 读不到", "oc_session_id", NotifierHost.EXTRA_SESSION_ID)
        assertEquals("N1: 来自通知的 extra 键名被改了", "oc_from_notification", NotifierHost.EXTRA_FROM_NOTIFICATION)
    }

    @Test
    fun blankOrMissingSessionIdMeansNoSession() {
        // 没有会话 ⇒ 不该编一个假会话出来；extra 干脆不写。
        assertNull("N1: null sessionId 不该被兜底成一个真会话", NotifierHost.clickPlan(null).sessionId)
        assertNull("N1: 空白 sessionId 等价于没有（postMessage 里 `takeIf{isNotBlank()}`）", NotifierHost.clickPlan("   ").sessionId)
        assertNull("N1: 空串等价于没有", NotifierHost.clickPlan("").sessionId)
        // 但通知仍然要能点开（只是没有指定会话）
        assertEquals("N1: 没有会话时 pageArg 必须是空串，不能是 'null' 三个字母", "", NotifierHost.clickPlan(null).pageArg())
        assertEquals(
            "N1: 没有会话时通知 id 必须落到固定兜底值，不能是 0（0 常被系统当无效 id）",
            NotifierHost.NO_SESSION_NOTIFY_ID,
            NotifierHost.clickPlan(null).notifyId,
        )
    }

    @Test
    fun flagsKeepSingleTopAndClearTop() {
        val flags = NotifierHost.clickPlan("n1-session-777").flags
        assertTrue(
            "N1: 少了 FLAG_ACTIVITY_SINGLE_TOP —— 宿主已在栈里时每次点通知都会**叠一个新的**" +
                "WebHostActivity，会话页状态就丢了。",
            flags and Intent.FLAG_ACTIVITY_SINGLE_TOP == Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
        assertTrue(
            "N1: 少了 FLAG_ACTIVITY_CLEAR_TOP —— 栈里被别的 Activity 盖住时，点击不会回到宿主。",
            flags and Intent.FLAG_ACTIVITY_CLEAR_TOP == Intent.FLAG_ACTIVITY_CLEAR_TOP,
        )
    }

    @Test
    fun distinctSessionsDoNotShareNotificationId() {
        val a = NotifierHost.clickPlan("n1-session-aaa")
        val b = NotifierHost.clickPlan("n1-session-bbb")
        assertNotEquals(
            "N1: 两个不同会话用了同一个通知 id ⇒ 后发的会覆盖先发的，通知栏里只剩一条，" +
                "点进去回的是另一个会话。",
            a.notifyId,
            b.notifyId,
        )
        assertNotEquals(
            "N1: 两个不同会话用了同一个 PendingIntent requestCode ⇒ 系统会复用同一条 " +
                "PendingIntent，FLAG_UPDATE_CURRENT 把 extra 覆盖成后一个会话。",
            a.requestCode,
            b.requestCode,
        )
        // 同一会话必须稳定收敛到同一条通知（覆盖，而不是每次多一条）。
        assertEquals(
            "N1: 同一会话的 notifyId 不稳定 ⇒ 每回合多一条通知。",
            a.notifyId,
            NotifierHost.clickPlan("n1-session-aaa").notifyId,
        )
    }

    // ---- 2. 交给页面的参数就是会话 id 本身（本轮修掉的真 bug）--------------

    @Test
    fun pageArgIsTheBareSessionIdNotAJsonBlob() {
        val arg = NotifierHost.clickPlan("n1-session-777").pageArg()

        assertEquals(
            "N1: 页面拿到的不该是再包一层的 JSON 串。facade 的 onOpenSession(sessionId) 把入参" +
                "当**裸会话 id**用；包一层 JSON 的话页面拿到 '{\"sessionId\":\"...\"}'，" +
                "拿它当 session id 查会话必然查不到 ⇒ 点击回不到会话。" +
                "（真机证据：research/web/NOTIFICATION_CLICK_CLOSE.md §4）",
            "n1-session-777",
            arg,
        )
        assertFalse(
            "N1: pageArg 看起来还是 JSON（以 { 开头）—— 双层编码没修干净。",
            arg.trimStart().startsWith("{"),
        )
        assertFalse(
            "N1: pageArg 里还含 'sessionId' 字样 —— 说明仍在传 JSON 包装而非裸 id。",
            arg.contains("sessionId"),
        )
    }

    // ---- 3. 纯函数不许"只在测试里存在"（防摆设）--------------------------

    @Test
    fun postTurnFinishedWiresThePlan() {
        val src = notifierSource
        assertTrue(
            "N1: postTurnFinished 不再走 clickPlan() —— 纯函数与生产代码脱钩，" +
                "单测就变成了摆设。",
            src.contains("val plan = clickPlan(sessionId)"),
        )
        assertTrue(
            "N1: PendingIntent 的 requestCode 必须取自 plan（否则测试的桶与真实桶不是同一个）。",
            src.contains("plan.requestCode"),
        )
        assertTrue(
            "N1: notify() 的 id 必须取自 plan（否则测试的桶与真实桶不是同一个）。",
            src.contains("notify(plan.notifyId, notification)"),
        )
        assertTrue(
            "N1: 意图 flags 必须取自 plan。",
            src.contains("flags = plan.flags"),
        )
        assertTrue(
            "N1: 会话 extra 必须取自 plan.sessionId（且只在非空时写）。",
            src.contains("plan.sessionId?.let { putExtra(EXTRA_SESSION_ID, it) }"),
        )
    }

    @Test
    fun deliverOpenSessionUsesPageArg() {
        val src = bridgeSource
        assertTrue(
            "N1: deliverOpenSession 不再用 clickPlan(...).pageArg() —— 又退回自己拼字符串，" +
                "双层 JSON 编码会再犯。",
            src.contains("NotifierHost.clickPlan(sessionId).pageArg()"),
        )
        assertFalse(
            "N1: deliverOpenSession 里不该再出现 `put(\"sessionId\"` 这种 JSON 包装 —— " +
                "那正是本轮修掉的 bug（页面拿到 '{\"sessionId\":\"...\"}'）。",
            Regex("""JSONObject\(\)\.apply\s*\{\s*put\("sessionId"""").containsMatchIn(src),
        )
    }
}
