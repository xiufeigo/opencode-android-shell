package com.xiufeigo.opencodeshell.web.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 底部留白绊线：**手势小白条不该被整条导航带顶开**。
 *
 * ### 缺陷（Pixel Tablet 实测，2.0.31）
 *
 * 手势导航下 `navigationBars` 上报整条上滑热区（`[0,1536][2560,1600]` ⇒ 64px / 32 CSS px），
 * 但系统只在带子正中画 8px 高的手柄（屏幕 y∈[1564,1571]）。整条让出的结果：
 *
 * | 项 | 实测（CSS px） |
 * | --- | --- |
 * | 视口高 / 屏幕高 | 768 / 800（差 32 = 整条导航带） |
 * | 会话面板底边 | 759 |
 * | 手柄上沿 | 782 |
 * | **中间空隙** | **23** ← 产品负责人看到的"底栏离小白条太远" |
 *
 * ### 修法判据
 *
 * 只让出「手柄上沿 + 空隙 − 面板外边距」，面板底边就贴住手柄；3 键导航（`tappableElement`
 * 有值）仍整条让出，否则按钮会压住界面。纯函数在 [NavigationBarVisual]，宿主接线在
 * [InsetsHost]，页面消费 `--oc-safe-bottom`（`oc-host.js`）。
 */
class NavigationBarVisualTest {

    // Pixel Tablet 读数：density 2 ⇒ 1 CSS px = 2 px。
    private val density = 2f
    private val navPx = 64
    private val noTappable = 0

    @Test
    fun `手势导航只让到手柄上沿 而不是整条导航带`() {
        val inset = NavigationBarVisual.insetPx(navPx, noTappable, density)
        assertEquals("手柄上沿 36px + 空隙 4px − 面板外边距 16px = 24px", 24, inset)
        assertEquals("换算成 CSS px 正好是 12", 12, inset / 2)
        assertTrue("必须小于整条导航带（否则又回到 23px 的空隙）", inset < navPx)
    }

    @Test
    fun `面板底边落在手柄上沿之上 两个像素空隙`() {
        // 屏幕 800 CSS、视口 = 屏幕 − 让出量；面板底边 = 视口底 − 8px 外边距。
        val screenCss = 800
        val insetCss = NavigationBarVisual.insetPx(navPx, noTappable, density) / 2
        val panelBottom = screenCss - insetCss - 8
        val handleTop = screenCss - 18 // (64 + 8) / 2 / 2
        assertEquals("面板底边应贴住手柄上沿上方 2px（实测修前是 23px）", 2, handleTop - panelBottom)
    }

    @Test
    fun `三键导航整条让出 按钮不能被盖住`() {
        // 3 键导航：tappableElement 底部 inset 等于导航栏高度（48dp 常见形态）。
        assertEquals("有可点按钮时必须整条让出", navPx, NavigationBarVisual.insetPx(navPx, navPx, density))
        assertEquals(96, NavigationBarVisual.insetPx(96, 72, density))
    }

    @Test
    fun `没有导航栏时不产生留白`() {
        assertEquals(0, NavigationBarVisual.insetPx(0, 0, density))
        assertEquals("负值（异常读数）按 0 处理", 0, NavigationBarVisual.insetPx(-1, 0, density))
    }

    @Test
    fun `让出量永远不超过整条导航带 也永远不为负`() {
        // 极窄导航带（某些 OEM 手势区 8px）不能让出成负数。
        assertEquals("8px 带子：手柄上沿 6 + 空隙 4 − 外边距 16 → 夹到 0", 0, NavigationBarVisual.insetPx(8, 0, density))
        listOf(2, 8, 16, 24, 32, 48, 64, 96, 128).forEach { nav ->
            val inset = NavigationBarVisual.insetPx(nav, 0, density)
            assertTrue("nav=$nav ⇒ inset=$inset 必须在 [0, nav] 内", inset in 0..nav)
        }
    }

    @Test
    fun `密度换算不会因为取整把面板压到手柄上`() {
        // 2.625 是手机常见密度：手柄上沿 (63+10)/2=36、外边距 21、空隙 5 ⇒ 20px = 7.6 CSS。
        val phone = NavigationBarVisual.insetPx(63, 0, 2.625f)
        assertEquals(20, phone)
        assertTrue("面板底边必须仍在手柄上沿之上", 63 / 2.625f - phone / 2.625f - 8 > 0)
    }

    // ---- 宿主接线：必须以这套判据喂给页面，而不是直接上报整条导航带 ----

    private fun read(relative: String): String {
        val f = File(relative)
        assertTrue("找不到 $relative（单测工作目录应为 webapp/ 模块目录）", f.isFile)
        return f.readText()
    }

    private val insetsHost: String by lazy { read("src/main/java/com/xiufeigo/opencodeshell/web/host/InsetsHost.kt") }

    @Test
    fun `InsetsHost 用可视让位量喂 safeBottom 与视口压矮`() {
        assertTrue(
            "safeBottom 必须是 NavigationBarVisual 算出来的，而不是整条 navigationBars",
            insetsHost.contains("put(\"safeBottom\", (navBottom / d).css())"),
        )
        assertTrue(
            "必须读 tappableElement 才能区分 3 键 / 手势导航",
            insetsHost.contains("WindowInsetsCompat.Type.tappableElement()"),
        )
        assertTrue(
            "视口压矮也要用同一个量（否则面板与视口不同步）",
            insetsHost.contains("applyResize(imeBottom, navBottom)"),
        )
        assertTrue("不得再直接上报整条导航带", !insetsHost.contains("put(\"safeBottom\", (bars.bottom / d).css())"))
    }

    @Test
    fun `键盘弹起时仍然按 max(ime, 导航让位量) 压矮`() {
        // 键盘比导航带高得多，max 之后仍等于 ime ⇒ 本条只钉住"用了 navBottom 这个量"。
        assertEquals(883, ImeResize.bottomPadPx(2400, 883, 24))
    }
}
