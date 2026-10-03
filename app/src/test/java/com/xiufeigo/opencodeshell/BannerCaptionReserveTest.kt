package com.xiufeigo.opencodeshell

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v54 真机批（桌面 tB）：通知横幅压在 freeform 窗口的 caption 上
 * (`research/v54_verify/tB/tB_69_after_undo.png`)。
 *
 * 现象：横幅整条贴着窗口顶边，而桌面/分屏窗口的 — ▭ ✕ caption 正是画在
 * 应用自己顶边上、并且**独占这一带的触摸**。于是 caption 的 ✕ 压住「全部清除」，
 * 返回箭头压住通知图标，两处都点不动。
 *
 * 原因：横幅只让了 `WindowInsets.statusBars`，而桌面窗口**没有**顶部系统栏
 * inset —— 这恰恰是 `desktopCaptionReserve()` 判定「这是桌面/分屏窗口」的那个信号。
 * 顶栏 host 里另外两处（顶 tab strip、独立终端路由）早就用了同一个 reserve，
 * 只有横幅这条没有。
 *
 * 修法：横幅的 modifier 链加上 `.padding(top = desktopCaptionReserve())`。
 * 两个 inset 互斥（reserve 只在 statusBar 为 0 时非零），不会叠加。
 */
class BannerCaptionReserveTest {

    private fun source(relative: String): String {
        val file = File("src/main/java/com/xiufeigo/opencodeshell/$relative")
            .takeIf { it.isFile }
            ?: File("app/src/main/java/com/xiufeigo/opencodeshell/$relative")
        assertTrue("missing source: $relative", file.isFile)
        return file.readText()
    }

    private fun app() = source("ui/App.kt")

    @Test
    fun theBannerClearsTheDesktopCaption() {
        val body = app()
        assertTrue(
            "通知横幅必须像顶 tab strip 一样让出 caption",
            body.contains(".padding(top = desktopCaptionReserve())"),
        )
    }

    @Test
    fun theBannerStillClearsTheStatusBarFirst() {
        // caption reserve 之前是 statusBars padding，两者是同一道「顶栏让位」的
        // 两种形态（手机有状态栏、桌面窗口有 caption），不能互相替换。
        val banner = app().substringAfter("NotificationBanner(").substringBefore("Box(Modifier.fillMaxWidth().weight(1f))")
        assertTrue(banner.contains("windowInsetsPadding(WindowInsets.statusBars)"))
    }

    @Test
    fun theReserveComesFromTheOnePlaceThatJudgesADesktopWindow() {
        // reserve 必须来自 `desktopCaptionReserve()`（读本 composition 的窗口），
        // 不能是这里新写的一个常数 —— 否则桌面判定就有第二处真相。
        assertTrue(
            app().contains("import com.xiufeigo.opencodeshell.ui.nav.desktopCaptionReserve"),
        )
    }

    @Test
    fun theBannerVerticalBudgetIsUnchanged() {
        // v51 的限高仍在，别让这次改动把 band 撑回 ~64 dp。
        assertTrue(app().contains("internal object BannerBand"))
        assertTrue(app().contains("heightIn(max = BannerBand.MAX_HEIGHT)"))
    }
}
