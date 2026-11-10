package com.xiufeigo.opencodeshell.web.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 底部留白绊线：**白色会话底框要贴住手势小白条**，但官方输入区布局不能动。
 *
 * ### 缺陷（Pixel Tablet 实测，density 2）
 *
 * | 版本 | `--oc-safe-bottom` | 视口高 | 面板底边 | 手柄上沿 | 空隙 |
 * | --- | --- | --- | --- | --- | --- |
 * | 2.0.31（整条让出） | 32 CSS px | 768 | 759 | 782 | **23** |
 * | 2.0.32（+2px 空隙） | 12 CSS px | 788 | 779 | 782 | 3 |
 * | 2.0.34（对齐手柄） | 10 CSS px | 790 | **782** | 782 | **0** |
 *
 * 2.0.35：让位量不变，但首帧改用 `navigation_mode`。
 * 2.0.36：官方 `#root.h-dvh` 在 Android WebView 里要点击才更新；改用 WebView 实高钉死。
 *
 * 只收「小白条 ↔ 白色会话底框」这一段。2.0.33 曾把 `session-composer-dock` 的
 * `pb-3`（12px）改成 4px，等于改了 OpenCode 官方输入框和会话区的缝隙；2.0.34 撤回。
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
        assertEquals(
            "系统设置是 3 键时，即使 tappable 还是 0 也要整条让出",
            navPx,
            NavigationBarVisual.insetPx(navPx, 0, density, NavigationBarMode.THREE_BUTTON),
        )
    }

    @Test
    fun `手势导航首帧 tappable 假抄整条导航带 仍按手柄让位`() {
        // 首帧 tappableElement 经常等于 navigationBars，看起来像 3 键。
        // 系统 navigation_mode=GESTURAL 必须盖过这帧假读数，否则会话面板先悬空再掉下来。
        val inset = NavigationBarVisual.insetPx(navPx, navPx, density, NavigationBarMode.GESTURAL)
        assertEquals(20, inset)
        assertTrue("不得退回整条导航带", inset < navPx)
        assertEquals(
            "2 键同样整条让出",
            navPx,
            NavigationBarVisual.insetPx(navPx, 0, density, NavigationBarMode.TWO_BUTTON),
        )
    }

    @Test
    fun `UNKNOWN 设备锁存手势证据后 瞬态 tappable 不再翻案成 3 键`() {
        // 系统设置读不到的设备：某次 insets 给出 nav>0 && tappable==0（手势的决定性证据），
        // 此后切会话时部分交付帧把 tappable 瞬态抄成整条导航带，也必须仍按手势让位。
        assertTrue(NavigationBarVisual.isGesturalEvidence(navPx, 0))
        assertFalse("3 键：tappable>0 不是手势证据", NavigationBarVisual.isGesturalEvidence(navPx, navPx))
        assertFalse("没有导航带时谈不上手势证据", NavigationBarVisual.isGesturalEvidence(0, 0))
        // 锁存后 effectiveMode=GESTURAL ⇒ 同一帧瞬态 tappable 得到手柄让位量而非整条。
        assertEquals(
            20,
            NavigationBarVisual.insetPx(navPx, navPx, density, NavigationBarMode.GESTURAL),
        )
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
            "必须读 tappableElement 才能在系统设置缺失时区分 3 键 / 手势导航",
            insetsHost.contains("WindowInsetsCompat.Type.tappableElement()"),
        )
        assertTrue(
            "必须读 navigation_mode，才能在首帧 tappable 假抄整条导航带时仍按手势让位",
            insetsHost.contains("NavigationBarMode.read(root.context)"),
        )
        assertTrue(
            "UNKNOWN 设备必须锁存手势证据，否则切会话的瞬态 tappable 会再翻案成 3 键",
            insetsHost.contains("NavigationBarVisual.isGesturalEvidence(bars.bottom, tappableBottom)") &&
                insetsHost.contains("gesturalObserved = true"),
        )
        assertTrue(
            "首帧 layout 后必须再 refresh 一次（decor 高度 / tappable 这时才稳）",
            insetsHost.contains("addOnLayoutChangeListener(firstLayoutRefresh)"),
        )
        assertTrue(
            "窗口高度还是 0 时不得把 pad=0 写成已提交",
            insetsHost.contains("imeMode == ImeMode.RESIZE && windowH <= 0"),
        )
        assertTrue(
            "视口压矮也要用同一个量（否则面板与视口不同步）",
            insetsHost.contains("applyResize(imeBottom, navBottom)"),
        )
        assertTrue("不得再直接上报整条导航带", !insetsHost.contains("put(\"safeBottom\", (bars.bottom / d).css())"))
    }

    @Test
    fun `不得改写官方 composer dock 内边距`() {
        assertFalse(
            "不得再给页面发 gestureHandle：那是 2.0.33 用来改官方 pb-3 的通道",
            insetsHost.contains("gestureHandle"),
        )
        assertFalse(
            "oc-host.js 不得按手势导航改 session-composer-dock（官方 pb-3 必须保留）",
            facade.contains("session-composer-dock"),
        )
        assertFalse("不得再安装 dock 底内边距覆盖", facade.contains("installBottomInsetStyle"))
        assertFalse("不得再写 data-oc-gesture-handle", facade.contains("data-oc-gesture-handle"))
    }

    @Test
    fun `根节点高度钉 WebView 实高 不靠要点击才更新的 100dvh`() {
        assertTrue(
            "padding 落地后必须把 WebView 高度补发给页面",
            insetsHost.contains("root.post { pushViewHeight() }"),
        )
        assertTrue(
            "WebView 高度变化时也要补发（父层 padding 后的那一帧）",
            insetsHost.contains("if (v.height > 0 && v.height != oldBottom - oldTop) pushViewHeight()"),
        )
        assertTrue("payload 必须带 viewHeight", insetsHost.contains("put(\"viewHeight\""))
        assertTrue(
            "页面必须用 --oc-view-height 盖过官方 h-dvh",
            facade.contains("height:var(--oc-view-height,100%);"),
        )
        assertTrue("必须写入 CSS 变量 --oc-view-height", facade.contains("\"--oc-view-height\""))
        assertTrue("必须给 #root 写内联高度，避免官方样式后到把 dvh 盖回来", facade.contains("rootNode.style.setProperty(\"height\", nextH)"))
        assertTrue("viewHeight 变化时要派 resize，让会话 flex 子项跟上", facade.contains("dispatchEvent(new Event(\"resize\"))"))
        assertTrue(
            "resize 模式下必须把官方 --safe-area-inset-bottom 钉成 0（WebView env 是整条导航带）",
            facade.contains("--safe-area-inset-bottom:0px"),
        )
        assertTrue(
            "必须盖掉官方 <main> 按 env() 垫的 32px 底内边距",
            facade.contains("resize\\\"] main{") &&
                facade.contains("padding-bottom:0px !important;"),
        )
        assertTrue(
            "同样钉死 --shell-safe-area-bottom，否则 <main> 仍会垫 32px",
            facade.contains("--shell-safe-area-bottom:0px"),
        )
    }

    @Test
    fun `键盘弹起时仍然按 max(ime, 导航让位量) 压矮`() {
        // 键盘比导航带高得多，max 之后仍等于 ime ⇒ 本条只钉住"用了 navBottom 这个量"。
        assertEquals(883, ImeResize.bottomPadPx(2400, 883, 20))
    }
}
