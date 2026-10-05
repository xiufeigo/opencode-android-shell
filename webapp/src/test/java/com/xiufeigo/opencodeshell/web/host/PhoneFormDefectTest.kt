package com.xiufeigo.opencodeshell.web.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * F18 绊线：两条手机专属缺陷的可断言判据。
 *
 * ### 两层
 * 1. **几何层**（[PhoneFormDefect] 纯函数）：顶部遮挡量 / BACK 路由决策。
 * 2. **资产契约层**（读 `oc-host.js` + `WebHostActivity.kt` + `InsetsHost.kt`）：
 *    断言修法**真的在**源码里。这一层是**可反向验证**的关键 ——
 *    撤回修法 ⇒ 必红（`webapp/build.gradle.kts:126-137` 已把这三个文件声明为
 *    测试任务输入，否则 gradle 会判 UP-TO-DATE 整段跳过、绊线"假绿"）。
 *
 * ### 原始读数出处（research/web/FIX_F18_PHONE.md §2 / research/web/E2E_PHONE_V13.md）
 * 改前实测（emulator-6410，`wm size 1080x2340` + `wm density 480` ⇒ 360dp 档，dpr=3）：
 * - `window.__ocAndroid.getInsets()` === **null**（页面从未收到任何 inset payload）
 * - `--oc-safe-top` 计算值 = **""**（空串）
 * - `#root` 盒 top=0、h=780；`header` top=**1**、h=36 ⇒ 标题行压在 y∈[0,~48] 的
 *   状态栏+挖孔区内（V13 s04_top_crop.png：标题与时钟字形重叠、被挖孔条横切）。
 */
class PhoneFormDefectTest {

    // ---- 读数常量（与设备原始输出一一对应） --------------------------------
    private val phoneSafeTopCss = 48.0          // 状态栏+挖孔合计，F18 实测 --oc-safe-top
    private val headerBefore = PhoneFormDefect.Rect(top = 1.0, bottom = 37.0)

    // ---- 1. 几何：顶部遮挡 -------------------------------------------------

    @Test
    fun `改前 标题行与状态栏挖孔区完全重叠`() {
        val overlap = PhoneFormDefect.topOverlapPx(headerBefore, phoneSafeTopCss)
        assertEquals(
            "F18 改前：header [1,37] 落在安全区 [0,48] 内，重叠应等于 header 全高 36",
            36.0, overlap, 0.01,
        )
        assertFalse("改前不可能 clear", PhoneFormDefect.topClear(headerBefore, phoneSafeTopCss))
    }

    @Test
    fun `按 safeTop 下移后顶部遮挡精确归零`() {
        val moved = PhoneFormDefect.shiftedByTopInset(headerBefore, phoneSafeTopCss)
        assertEquals(49.0, moved.top, 0.01)
        assertEquals(85.0, moved.bottom, 0.01)
        assertEquals(
            "垫了 safeTop 后顶部遮挡必须为 0",
            0.0, PhoneFormDefect.topOverlapPx(moved, phoneSafeTopCss), 0.0,
        )
        assertTrue(PhoneFormDefect.topClear(moved, phoneSafeTopCss))
    }

    @Test
    fun `safeTop 为 0 的形态（平板、无状态栏层）不得凭空产生留白`() {
        // 平板档没有状态栏重叠问题：safeTop=0 ⇒ 下移 0 ⇒ 遮挡恒 0（不制造回归）。
        val moved = PhoneFormDefect.shiftedByTopInset(headerBefore, 0.0)
        assertEquals("safeTop=0 时位移必须为 0", headerBefore.top, moved.top, 0.0)
        assertEquals(0.0, PhoneFormDefect.topOverlapPx(moved, 0.0), 0.0)
    }

    // ---- 2. 几何：BACK 路由（缺陷 B 的判据） -------------------------------

    @Test
    fun `改前语义 面板打开且有历史时仍会退历史 这就是误退`() {
        // 改前逻辑 = canGoBack 优先 ⇒ 面板开着也 goBack() ⇒ 退到列表（缺陷 D4）。
        val canGoBack = true      // SPA 用 History API，进会话已压了一条历史
        val panelOpen = true
        val wouldGoBack = canGoBack // 改前行为
        assertTrue(
            "改前：canGoBack=true 时无视面板直接退历史 ⇒ 误退",
            wouldGoBack,
        )
        // 这正是 V13 步骤 8/10 观察到的：面板开 + BACK ⇒ 整会话退回列表。
        assertEquals(0, PhoneFormDefect.levelAfterBack(from = 2, panelOpen = false, canGoBack = true))
    }

    @Test
    fun `改后面板打开时 BACK 被消费 不退历史`() {
        assertFalse(
            "面板打开时宿主绝不能退历史（页面已消费去关面板）",
            PhoneFormDefect.shouldGoBack(panelOpen = true, canGoBack = true),
        )
        assertEquals(
            "按一次 BACK 后必须仍停在会话视图(1)，不能回列表(0)",
            1, PhoneFormDefect.levelAfterBack(from = 2, panelOpen = true, canGoBack = true),
        )
    }

    @Test
    fun `改后面板关掉后再按一次才回会话列表`() {
        assertTrue(
            "无面板且有历史 ⇒ 第二次 BACK 才退列表",
            PhoneFormDefect.shouldGoBack(panelOpen = false, canGoBack = true),
        )
        assertEquals(
            0, PhoneFormDefect.levelAfterBack(from = 2, panelOpen = false, canGoBack = true),
        )
    }

    @Test
    fun `两者都不成立时不退也不 finish`() {
        assertFalse(
            "无面板且无历史 ⇒ 什么都不做",
            PhoneFormDefect.shouldGoBack(panelOpen = false, canGoBack = false),
        )
    }

    // ---- 3. 资产契约层：**可反向验证**的那几条 ------------------------------

    private fun asset(rel: String): String {
        val f = File(rel)
        assertTrue("找不到文件：${f.absolutePath}（单测工作目录应为 webapp/ 模块目录）", f.isFile)
        return f.readText()
    }

    private fun facade() = asset("src/main/assets/www/oc-host.js")
    private fun activity() = asset("src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt")
    private fun insetsHost() = asset("src/main/java/com/xiufeigo/opencodeshell/web/host/InsetsHost.kt")

    /**
     * 去掉 JS 行注释与块注释后再断言。
     *
     * **为什么必须去注释**：F18 首轮反向验证真跑过一次，断言写成 `src.contains("#root")` /
     * `src.contains("\"Escape\"")` 时**撤回修法仍然绿** —— 因为这两个串在注释里也出现过
     * （"根容器是 h-dvh" 段落提到 #root，KeyboardEvent 注释提到 Escape），
     * 属于典型的「绊线假绿」。只对**可执行代码**断言，撤回才是真红。
     */
    private fun code(rel: String): String {
        var s = asset(rel)
        s = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL).replace(s, "")
        s = Regex("""^\s*//.*$""", RegexOption.MULTILINE).replace(s, "")
        return s
    }

    @Test
    fun `facade 消费 --oc-safe-top 给根容器垫顶部内边距`() {
        val src = code("src/main/assets/www/oc-host.js")
        assertTrue(
            "撤回修法 ⇒ 注入层不再注册 oc-top-inset 样式",
            src.contains("""var TOP_STYLE_ID = "oc-top-inset";"""),
        )
        assertTrue(
            "撤回修法 ⇒ 不再把顶部留白垫到应用根容器（精确匹配整条 CSS 规则，不靠注释里的 #root）",
            src.contains(""""#root,body>div:first-of-type{"""") &&
                src.contains(""""padding-top:var(--oc-safe-top-eff,0px);""""),
        )
        assertTrue(
            "顶部留白必须由宿主实测的 --oc-safe-top 驱动，不能硬编码",
            src.contains("""max(var(--oc-safe-top,0px)"""),
        )
        // 两条 readyState 路径都要装：冷启动时 readyState 就是 'loading'。
        val installCalls = Regex("""^\s*installTopInsetStyle\(\);""", RegexOption.MULTILINE)
            .findAll(src).count()
        assertTrue(
            "installTopInsetStyle() 应在 readyState 两条分支各调一次（实测 $installCalls 次）",
            installCalls >= 2,
        )
    }

    @Test
    fun `facade 注册默认 BACK 处理器 用 Escape 关浮层`() {
        val src = code("src/main/assets/www/oc-host.js")
        assertTrue(
            "撤回修法 ⇒ 不再注册默认 back 处理器",
            src.contains("""oc.setBackHandler(function () {"""),
        )
        assertTrue(
            "关浮层必须派发 Escape（精确匹配 key: \"Escape\"，不是注释里的 Escape）",
            src.contains("""key: "Escape","""),
        )
        val calls = Regex("""^\s*installDefaultBackHandler\(\);""", RegexOption.MULTILINE)
            .findAll(src).count()
        assertTrue("installDefaultBackHandler() 应在两条 readyState 分支各调一次（实测 $calls 次）", calls >= 2)
    }

    @Test
    fun `宿主 BACK 顺序是 先问页面 再退历史`() {
        val src = activity()
        val onBack = src.indexOf("window.__ocAndroid.onBack")
        val canGoBack = src.indexOf("webView.canGoBack()", onBack)
        val goBack = src.indexOf("webView.goBack()", onBack)
        assertTrue("installBackHandler 里必须问页面 onBack", onBack > 0)
        assertTrue(
            "canGoBack 判断必须排在页面 onBack **之后**（F18 改前是之前 ⇒ 误退）",
            canGoBack > onBack,
        )
        assertTrue("goBack() 必须在 canGoBack 判断之后", goBack > canGoBack)
    }

    @Test
    fun `宿主在页面就绪后补发 insets`() {
        val src = activity()
        assertTrue(
            "撤回修法 ⇒ onPageFinished 不再补发 insets",
            src.contains("insets.replay()"),
        )
        val host = insetsHost()
        assertTrue("InsetsHost 必须提供不去重的 replay()", host.contains("fun replay()"))
    }

    @Test
    fun `既有机制不得被挤掉`() {
        val src = facade()
        listOf("--oc-safe-top", "--oc-safe-bottom", "--oc-ime-bottom", "applyInsets", "oc-ime-lift")
            .forEach { key -> assertTrue("注入层丢了既有通道 $key", src.contains(key)) }
    }
}
