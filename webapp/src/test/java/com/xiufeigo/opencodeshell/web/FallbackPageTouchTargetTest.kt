package com.xiufeigo.opencodeshell.web

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * C1（任务 1）绊线：代理起不来时加载的兜底页 `assets/www/index.html` 上，
 * **每一个**可交互控件的触摸靶必须 ≥ 48dp。
 *
 * ## 为什么需要它
 * F2（`research/web/TOUCH_TARGET_FIX.md:427-436`）把首启配置框的 45dp 抬到了 48dp，
 * 但同一份报告 §6 记下了一条**未闭环**：兜底页 4 个 `<button>` 仍是
 * `21(行盒) + 12(padding) + 2(border) = 35px`，**静态推导，未上设备复核**。
 *
 * ## 怎么算（CSS 盒模型，单测工作目录 = `webapp/`）
 * - 内容高 = `body` 的 `font: 14px/1.5` ⇒ 14 × 1.5 = 21px（`button` 是 `font: inherit`）
 * - 盒高   = max(`min-height`, 内容高 + padding 上/下 + border 上/下)
 * - 页面里 `<meta viewport>` 明确写了「1 CSS px == 1 dp」，故 **px 即 dp**，可直接比 48。
 *
 * ## 反向验证
 * 把 `index.html` 的 `min-height: 48px` 删掉 ⇒ 盒高回落 35 < 48 ⇒ 本类**必须红**。
 */
class FallbackPageTouchTargetTest {

    private val minTargetPx = 48

    private fun page(): String {
        val f = File("src/main/assets/www/index.html")
        assertTrue(
            "找不到兜底页资产：${f.absolutePath}（单测工作目录应为 webapp/ 模块目录）",
            f.isFile,
        )
        return f.readText()
    }

    private fun rule(html: String, selector: String): String {
        val re = Regex("(?s)\\b" + Regex.escape(selector) + "\\s*\\{(.*?)\\}")
        val m = re.find(html)
        assertTrue(
            "兜底页里找不到 CSS 规则 `$selector { … }` —— 页面结构变了，" +
                "请同步复核 48dp 触摸靶还是否成立",
            m != null,
        )
        return m!!.groupValues[1]
    }

    /** `body` 的 `font: 14px/1.5 …` ⇒ (字号, 行高倍数)。 */
    private fun bodyFont(html: String): Pair<Double, Double> {
        val body = rule(html, "body")
        val m = Regex("""font:\s*([\d.]+)px\s*/\s*([\d.]+)""").find(body)
        assertTrue("兜底页 `body` 规则里读不到 `font: <size>px/<lineHeight>`，实测：\n$body", m != null)
        return m!!.groupValues[1].toDouble() to m!!.groupValues[2].toDouble()
    }

    private fun px(value: String): Double =
        Regex("""([\d.]+)px""").find(value)?.groupValues?.get(1)?.toDouble() ?: 0.0

    @Test
    fun everyInteractiveControlOnTheFallbackPageClears48dp() {
        val html = page()
        val (fontSize, lineHeight) = bodyFont(html)
        val btn = rule(html, "button")

        val contentH = fontSize * lineHeight
        val padV = px(Regex("""padding:\s*([\d.]+px[^;]*)""").find(btn)?.groupValues?.get(1) ?: "0px") * 2
        val borderV = px(Regex("""border:\s*([\d.]+px)""").find(btn)?.groupValues?.get(1) ?: "0px") * 2
        val declaredMin = px(Regex("""min-height:\s*([\d.]+px)""").find(btn)?.groupValues?.get(1) ?: "0px")

        val intrinsic = contentH + padV + borderV
        val effective = maxOf(intrinsic, declaredMin)

        val buttons = Regex("<button\\b").findAll(html).count()
        assertTrue("兜底页里一个 `<button>` 都没有（buttons=0）—— 断言会空转，请复核页面", buttons > 0)

        assertTrue(
            "C1: 兜底页可交互控件的触摸靶只有 $effective px(=dp)，必须 ≥ $minTargetPx px(=dp)。" +
                "固有盒高 = 内容 $contentH + padding $padV + border $borderV = $intrinsic，" +
                "声明的 min-height = $declaredMin。页内 `<button>` 个数 = $buttons。",
            effective >= minTargetPx,
        )
    }

    @Test
    fun theFallbackPageIsStillReachableFromTheHost() {
        // 兜底页不是死代码：代理起不来时 WebHostActivity.kt:244 会 loadUrl 它，
        // WebHostActivity.kt:611 拿它当 webView.url 为 null 时的存档回退，
        // WebHostBridge.kt:43 还把它以 `homeUrl` 暴露给官方 UI。
        // 这条断言把「它还在兜底路径上」钉住 —— 哪天真要删，先改这里。
        val url = WebHostActivity.LEGACY_PLACEHOLDER_URL
        assertTrue(
            "兜底页 URL 不再指向 assets/www/index.html 了（实测 $url）—— " +
                "CLEANUP_C1.md §1 的「保留」理由已经不成立，请复核",
            url.contains(WebHostActivity.ASSET_DOMAIN) && url.endsWith("/www/index.html"),
        )
    }
}
