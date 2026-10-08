package com.xiufeigo.opencodeshell.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Android window theme remains synchronized with the official UI. */
class Ux4ThemeTripwireTest {
    private fun read(rel: String): String {
        val file = File(rel)
        assertTrue("找不到 $rel", file.isFile)
        return file.readText()
    }
    private val colors by lazy { read("src/main/res/values/colors.xml") }
    private val nightColors by lazy { read("src/main/res/values-night/colors.xml") }
    private val activity by lazy { read("src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt") }

    @Test
    fun `window background follows daynight mode`() {
        // 只断言 oc_host_bg 那一行 —— ic_launcher_background 是合法的恒定深色。
        val dayBg = colors.lines().firstOrNull { it.contains("""name="oc_host_bg"""") }.orEmpty()
        val nightBg = nightColors.lines().firstOrNull { it.contains("""name="oc_host_bg"""") }.orEmpty()
        assertFalse(
            "昼版 oc_host_bg 又改回恒深色 #FF080808 —— 浅色模式下 WebView 首帧" +
                "背后是一块深灰（UX4 缺陷 2 宿主侧修法被撤回）",
            dayBg.contains("#FF080808"),
        )
        assertTrue(
            "昼版 oc_host_bg 应为浅色（与上游页面浅色底色 #fafafa 协调）",
            dayBg.contains("#FFFAFAFA"),
        )
        assertTrue(
            "必须有 values-night/colors.xml（改前缺失 ⇒ DayNight 没有 night 变体）",
            nightColors.isNotBlank(),
        )
        assertTrue(
            "夜版 oc_host_bg 应为深色 #FF080808（与上游页面深色底色一致）",
            nightBg.contains("#FF080808"),
        )
    }

    @Test
    fun `activity handles uimode configuration change`() {
        assertTrue(
            "configChanges 含 uiMode 时 Activity 必须重写 onConfigurationChanged" +
                "（否则深浅色切换后宿主不重推 scheme / 系统栏兜底不复查）",
            Regex("""override\s+fun\s+onConfigurationChanged""").containsMatchIn(activity),
        )
        val body = activity.substringAfter("override fun onConfigurationChanged")
        assertTrue(
            "onConfigurationChanged 必须重推 insets（把新 scheme 发给页面）",
            Regex("""insets\.refresh\(\)""").containsMatchIn(body),
        )
        assertTrue(
            "onConfigurationChanged 必须清掉 pageReportedDark（让系统栏明暗兜底" +
                "重新读当前 uiMode，而不是停留在页面旧上报值）",
            body.contains("pageReportedDark = null"),
        )
    }

}
