package com.xiufeigo.opencodeshell.web.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 底部留白绊线：**会话面板要贴住手势小白条**，但 3 键导航不能被压住。
 *
 * ### 两轮缺陷（Pixel Tablet 实测，density 2）
 *
 * | 版本 | `--oc-safe-bottom` | 视口高 | 面板底边 | 手柄上沿 | 空隙 |
 * | --- | --- | --- | --- | --- | --- |
 * | 2.0.31（整条让出） | 32 CSS px | 768 | 759 | 782 | **23** |
 * | 2.0.32（+2px 空隙） | 12 CSS px | 788 | 779 | 782 | 3 |
 * | 2.0.33（对齐手柄） | 10 CSS px | 790 | **782** | 782 | **0** |
 *
 * 2.0.32 之后用户仍觉得"留的空隙太大"：面板本身贴住了，但会话输入框在面板里
 * 还有 12px 的 dock 内边距 ⇒ 输入框底边离手柄 15px。所以 2.0.33 同时做两件事：
 *
 * 1. [NavigationBarVisual] 不再为手柄保留额外空隙，面板底边直接对齐手柄上沿；
 * 2. `oc-host.js` 在手势导航下把 dock 的底内边距收到 4px（仅手势，3 键导航不动）。
 */
class NavigationBarVisualTest {

    // Pixel Tablet 读数：density 2 ⇒ 1 CSS px = 2 px。
    private val density = 2f
    private val navPx = 64
    private val noTappable = 0

    @Test
    fun `手势导航让到面板底边正好对齐手柄上沿`() {
        val inset = NavigationBarVisual.insetPx(navPx, noTappable, density)
        assertEquals("手柄上沿 36px − 面板外边距 16px = 20px", 20, inset)
        assertEquals("换算成 CSS px 是 10", 10, inset / 2)
        assertTrue("必须小于整条导航带（否则又回到 23px 的空隙）", inset < navPx)
    }

    @Test
    fun `面板底边与手柄上沿完全重合 不再留空隙`() {
        val screenCss = 800
        val insetCss = NavigationBarVisual.insetPx(navPx, noTappable, density) / 2
        val panelBottom = screenCss - insetCss - 8
        val handleTop = screenCss - 18 // (32 + 4) / 2 屏幕坐标
        assertEquals("面板底边应与手柄上沿重合（2.0.31 是 23px、2.0.32 是 3px）", 0, handleTop - panelBottom)
    }

    @Test
    fun `三键导航整条让出 按钮不能被盖住`() {
        // 3 键导航：tappableElement 底部 inset 等于导航栏高度。
        assertEquals("有可点按钮时必须整条让出", navPx, NavigationBarVisual.insetPx(navPx, navPx, density))
        assertEquals(96, NavigationBarVisual.insetPx(96, 72, density))
        assertFalse("3 键导航没有手势手柄", NavigationBarVisual.hasGestureHandle(navPx))
        assertTrue("手势导航才有手柄", NavigationBarVisual.hasGestureHandle(0))
    }

    @Test
    fun `没有导航栏时不产生留白`() {
        assertEquals(0, NavigationBarVisual.insetPx(0, 0, density))
        assertEquals("负值（异常读数）按 0 处理", 0, NavigationBarVisual.insetPx(-1, 0, density))
    }

    @Test
    fun `让出量永远不超过整条导航带 也永远不为负`() {
        // 极窄导航带（某些 OEM 手势区 8px）：手柄上沿 + 外壳外边距会算出负数，必须夹到 0。
        assertEquals(0, NavigationBarVisual.insetPx(8, 0, density))
        listOf(2, 8, 16, 24, 32, 48, 64, 96, 128).forEach { nav ->
            val inset = NavigationBarVisual.insetPx(nav, 0, density)
            assertTrue("nav=$nav ⇒ inset=$inset 必须在 [0, nav] 内", inset in 0..nav)
        }
    }

    @Test
    fun `密度换算不会因为取整把面板压到手柄上`() {
        // 2.625 是手机常见密度：手柄上沿 (63+10)/2=36、外边距 21 ⇒ 15px。
        val phone = NavigationBarVisual.insetPx(63, 0, 2.625f)
        assertEquals(15, phone)
        assertTrue("面板底边不得越过手柄上沿", 63 / 2.625f - phone / 2.625f - 8 > -0.01)
    }

    // ---- 宿主接线：必须以这套判据喂给页面，而不是直接上报整条导航带 ----

    private fun read(relative: String): String {
        val f = File(relative)
        assertTrue("找不到 $relative（单测工作目录应为 webapp/ 模块目录）", f.isFile)
        return f.readText()
    }

    private val insetsHost: String by lazy { read("src/main/java/com/xiufeigo/opencodeshell/web/host/InsetsHost.kt") }
    private val facade: String by lazy { read("src/main/assets/www/oc-host.js").replace("\r\n", "\n") }

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
    fun `手势导航标志透给页面 且只在手势导航收 dock 内边距`() {
        assertTrue(
            "InsetsHost 必须把 hasGestureHandle 结果放进 payload",
            insetsHost.contains("put(\"gestureHandle\", NavigationBarVisual.hasGestureHandle(tappableBottom))"),
        )
        assertTrue(
            "页面必须消费这个标志（写成 html 属性供 CSS 用）",
            facade.contains("setAttribute(\"data-oc-gesture-handle\""),
        )
        assertTrue(
            "dock 内边距必须在手势导航下才收（3 键导航收会被按钮压住）",
            facade.contains("html[data-oc-gesture-handle=\"1\"] [data-component=\"session-composer-dock\"]"),
        )
        assertTrue("收内边距必须在文档开始时安装", facade.contains("installBottomInsetStyle();"))
    }

    @Test
    fun `键盘弹起时仍然按 max(ime, 导航让位量) 压矮`() {
        // 键盘比导航带高得多，max 之后仍等于 ime ⇒ 本条只钉住"用了 navBottom 这个量"。
        assertEquals(883, ImeResize.bottomPadPx(2400, 883, 20))
    }
}
