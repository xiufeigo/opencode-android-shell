package com.xiufeigo.opencodeshell.web.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 视口底带表面色绊线：**露出的条带跟页面主题走**，不能透出跟系统模式的窗口底色。
 *
 * ### 缺陷（MIUI 平板实测）
 *
 * resize 模式把 WebView 压矮手势视觉 inset 后，下面那条带子是
 * `viewportHost` 的 padding 区 —— 它没有自己的背景，透出 decor 的
 * `oc_host_bg`（DayNight 资源，跟**系统**深浅走）。系统深色 + 页面浅色时
 * 带子是 #080808 一整块黑，"小白条所在位置直接全黑"；之前所有验证都跑
 * 深色种子所以没暴露。
 *
 * 修法：页面把 `getComputedStyle` 解析出的表面底色经
 * `reportSurfaceColor` 推上来，宿主刷在露出层上。
 */
class SurfaceColorTest {

    @Test
    fun `rgb 与 rgba 解析成不透明 ARGB`() {
        assertEquals(0xFFFAFAFA.toInt(), SurfaceColor.parse("rgb(250, 250, 250)"))
        assertEquals(0xFF080808.toInt(), SurfaceColor.parse("rgba(8, 8, 8, 1)"))
        assertEquals(0xFF101418.toInt(), SurfaceColor.parse("rgb(16,20,24)"))
    }

    @Test
    fun `hex 走系统解析`() {
        assertEquals(0xFF112233.toInt(), SurfaceColor.parse("#112233"))
        assertEquals(0xFFFAFAFA.toInt(), SurfaceColor.parse(" #FAFAFA "))
    }

    @Test
    fun `透明与无效输入返回 null 留在窗口底色兜底`() {
        assertNull(SurfaceColor.parse("transparent"))
        assertNull(SurfaceColor.parse("rgba(0, 0, 0, 0)"))
        assertNull(SurfaceColor.parse("rgba(8, 8, 8, 0)"))
        assertNull(SurfaceColor.parse(""))
        assertNull(SurfaceColor.parse(null))
        assertNull(SurfaceColor.parse("not-a-color"))
        assertNull(SurfaceColor.parse("rgb(1, 2)"))
        assertNull(SurfaceColor.parse("rgba(1, 2, 3, abc)"))
    }

    // ---- 宿主接线：露出层必须吃页面色，而不是透出跟系统模式的窗口底色 ----

    private fun read(relative: String): String {
        val f = File(relative)
        assertTrue("找不到 $relative（单测工作目录应为 webapp/ 模块目录）", f.isFile)
        return f.readText()
    }

    private val insetsHost: String by lazy { read("src/main/java/com/xiufeigo/opencodeshell/web/host/InsetsHost.kt") }
    private val bridge: String by lazy { read("src/main/java/com/xiufeigo/opencodeshell/web/host/WebHostBridge.kt") }
    private val activity: String by lazy { read("src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt") }
    private val facade: String by lazy { read("src/main/assets/www/oc-host.js").replace("\r\n", "\n") }

    @Test
    fun `页面表面色经桥接到露出层`() {
        assertTrue(
            "桥必须暴露 reportSurfaceColor",
            bridge.contains("fun reportSurfaceColor(cssColor: String?)"),
        )
        assertTrue(
            "Activity 必须解析 CSS 颜色并交给 InsetsHost",
            activity.contains("SurfaceColor.parse(css)") && activity.contains("insets.setSurfaceColor(color)"),
        )
        assertTrue(
            "InsetsHost 必须把色刷在真正被压矮的那一层（resizeTarget），不是别处",
            insetsHost.contains("resizeTarget().setBackgroundColor(color)"),
        )
    }

    @Test
    fun `页面必须在主题变化与首帧都把表面色推上来`() {
        assertTrue(
            "推送必须取页面解析后的背景色（getComputedStyle），不能靠猜",
            facade.contains("getComputedStyle(el).backgroundColor"),
        )
        assertTrue(
            "主题切换不改 insets，必须另挂 MutationObserver 盯主题属性",
            facade.contains("MutationObserver") && facade.contains("data-color-scheme"),
        )
        assertTrue(
            "首帧（load/rAF）要补推一次：从没调过 reportTheme 的页面也要着色",
            facade.contains("requestAnimationFrame(pushSurfaceColor)"),
        )
        assertTrue(
            "insets 应用路径也要推（replay 后主题已稳定）",
            facade.contains("pushSurfaceColor();"),
        )
    }
}
