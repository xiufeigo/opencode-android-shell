package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.nav.SessionTabRef
import com.xiufeigo.opencodeshell.ui.nav.SessionTabs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v51 的 tab 生命周期一批（`research/v51_verify/`）：死 tab 不剪、中 tab 溢出
 * 时新建的 tab 看不见、删活动 tab 反而改绑。三个都是「决策 + 接线」两类，所以
 * 决策钉在纯函数上（[SessionTabs]），接线钉在源码级 —— Compose 的滚动与
 * NavHost 的返回栈都不是 JVM 能跑的。
 */
class TabLifecycleV51Test {

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText()
    }

    private fun app() = source("src/main/java/com/xiufeigo/opencodeshell/ui/App.kt")
    private fun strip() = source("src/main/java/com/xiufeigo/opencodeshell/ui/nav/DesktopTabStrip.kt")

    private val a = SessionTabRef("ses_a", "Alpha")
    private val b = SessionTabRef("ses_b", "Beta")
    private val c = SessionTabRef("ses_c", "Gamma")

    // ---------------------------------------------------------------- (1) prune

    /**
     * v51 LOW-1（`tD/v51_57_deadtab_restart.png`）:高冷启动删掉会话 → force-stop
     * → 重启,死 tab 仍恢复。恢复出来的死路由**就是** currentSession,而
     * protectedIds 里有 currentSession,于是这次修剪恒为空操作。
     */
    @Test
    fun `a dead tab is prunable even when it is the session on screen`() {
        val result = SessionTabs.pruneAgainstServerList(
            tabs = listOf(a, b),
            closed = emptyList(),
            serverIds = listOf(b.id),
            // Nothing protected: the caller shields only what it just created.
        )
        assertEquals(listOf(b), result.tabs)
        assertEquals(listOf(a.id), result.prunedSessionIds)
    }

    /** 接线上的修正:protectedIds 只剩 pendingCreated,不再拼 currentSession。 */
    @Test
    fun `the prune protects only the sessions this run created`() {
        val app = app()
        assertTrue(
            "the prune must not protect the session on screen (v51 LOW-1)",
            app.contains("protectedIds = pendingCreatedSessions.toSet()"),
        )
        assertTrue(
            "no other prune call site may re-add currentSession to the shield",
            !app.contains("pendingCreatedSessions.toSet() + listOfNotNull(currentSession)"),
        )
    }

    /** 被修剪掉的当前会话要退回 Home,而不是把死路由留在屏幕上。 */
    @Test
    fun `a pruned current session backs out to Home`() {
        val app = app()
        assertTrue(
            app.contains("currentSession in result.prunedSessionIds"),
        )
    }

    // ------------------------------------------------------------ (2) reveal

    /** 三个等宽 slot(100 px),外加两个 6 px 间距 —— 条自己量出来的范围。 */
    private val bounds = mapOf(
        a.id to 0..99,
        b.id to 106..205,
        c.id to 212..311,
    )

    /** tab 已经在视野内 → 不用滚(用户自己的 fling 不该被抢)。 */
    @Test
    fun `a visible tab is not scrolled`() {
        assertNull(SessionTabs.revealOffset(bounds, viewport = 300, current = 0, id = b.id))
    }

    /**
     * v51 MED-2（`v51_47/48/50`）:条满时新建的会话 tab 在视口右边之外,页面上
     * 已经切过去了,条上却找不到它。滚到「刚好整条露出」为止。
     */
    @Test
    fun `a tab past the right edge is scrolled into view`() {
        // c 的尾部 311 超出视口 [0,200) → 滚到 311 - 200 + 1 = 112。
        assertEquals(112, SessionTabs.revealOffset(bounds, viewport = 200, current = 0, id = c.id))
    }

    /** 用户已经往回滚过、目标 tab 在左边 → 回到它自己的起点。 */
    @Test
    fun `a tab past the left edge is scrolled back to`() {
        assertEquals(0, SessionTabs.revealOffset(bounds, viewport = 120, current = 400, id = a.id))
        assertEquals(106, SessionTabs.revealOffset(bounds, viewport = 60, current = 400, id = b.id))
    }

    /** 正好卡在视口边上不算越界(半开区间,不多滚一像素)。 */
    @Test
    fun `a tab flush with the edge is left alone`() {
        assertNull(SessionTabs.revealOffset(bounds, viewport = 100, current = 0, id = a.id))
    }

    /** 还没被这一帧摆过的 slot 没有记录 → 不滚(下一帧再算)。 */
    @Test
    fun `an unmeasured slot is not scrolled`() {
        assertNull(SessionTabs.revealOffset(bounds, viewport = 200, current = 0, id = "ses_new"))
    }

    /** 不在列表里的 id(Home 行等)不产生滚动。 */
    @Test
    fun `an id that is not a tab is not scrolled`() {
        assertNull(
            SessionTabs.revealOffset(mapOf(a.id to 0..10), viewport = 100, current = 0, id = "ses_absent"),
        )
    }

    /** 视口还没量到(0 宽,首帧)时不滚。 */
    @Test
    fun `an unmeasured viewport is not scrolled`() {
        assertNull(SessionTabs.revealOffset(mapOf(a.id to 0..10), viewport = 0, current = 0, id = a.id))
    }

    /** 接线:活动 tab 变化与 tab 数量变化都要触发露出,滚动位置被 clamp。 */
    @Test
    fun `the strip reveals the active tab on every change`() {
        val strip = strip()
        assertTrue(
            "the strip must scroll the active tab into view (v51 MED-2)",
            strip.contains("SessionTabs.revealOffset("),
        )
        assertTrue(
            strip.contains("LaunchedEffect(activeSessionId, sessions.size, viewportWidth"),
        )
        assertTrue(
            "the reveal must be clamped to the strip's own scroll range",
            strip.contains("scroll.animateScrollTo(target.coerceIn(0, scroll.maxValue))"),
        )
        assertTrue(
            "the slot ranges the reveal reads must come off the strip's own layout",
            strip.contains("onGloballyPositioned"),
        )
    }

    // ------------------------------------------------------------- (3) delete

    /**
     * 打开一个 tab 是**替换**会话条目,不是每点一个 tab 就压一层:删掉活动会话后
     * 的 `popBackStack()` 必须落在 Home,而不是用户之前逛过的那条会话上(那条
     * 路由会被 nav listener 补一个 tab,于是删掉的槽位以「另一条会话 + Untitle」
     * 的样子回来了,`tC_53/54`)。
     */
    @Test
    fun `opening a tab replaces the session entry`() {
        val app = app()
        val open = app.substringAfter("val openSession: (String) -> Unit")
        val navigate = open.substringAfter("navController.navigate(")
        assertTrue(
            "tab navigation must pop the previous session entry",
            navigate.contains("popUpTo(Route.HOME)"),
        )
    }

    /** 删会话的 tab 状态仍然按 id 收(它旁边的 Home 行删除路径同理)。 */
    @Test
    fun `the delete path still closes the tab by id`() {
        // v70 P2: every delete path — Home's row menu, the session route's ⋮ and
        // drawer cards — now reports through the one v63 decision
        // (`afterDeleteFromElsewhere`), which closes by id **and** purges the
        // closed stack / moves the route. The by-id rule this test exists for is
        // unchanged; the call site is the stronger sibling of the v49 one.
        assertTrue(app().contains("SessionTabs.afterDeleteFromElsewhere("))
        assertTrue(
            source("src/main/java/com/xiufeigo/opencodeshell/ui/nav/SessionTabs.kt")
                .contains("tabs = tabs.filterNot { it.id == id }"),
        )
    }
}
