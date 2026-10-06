package com.xiufeigo.opencodeshell.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * UX13-P4 · **全局明文放行（选项 C）**决策绊线。
 *
 * ## 决策（2026-10-06，产品负责人拍板）
 *
 * 选择**选项 C**：全局 `usesCleartextTraffic=true`，让 App 像**官方桌面端**一样
 * 能直连**任意 http 服务器**。已知代价（公网 http 目标全部明文，含 Basic 口令）
 * 与官方桌面端浏览器的行为一致，**已被明确接受**。
 *
 * 这是**刻意的安全姿态选择**，不是疏漏 —— 本文件的作用正是把它钉成可执行断言，
 * 防止后续有人看到 `cleartextTrafficPermitted="true"` 误以为是待修的问题而"顺手改回去"。
 * 若产品日后改主意，请**连同本文件一起改**，不要只改 XML。
 *
 * ## 为什么必须钉两处（Android 语义）
 *
 * `networkSecurityConfig` 存在时会**覆盖** manifest 的 `usesCleartextTraffic`。
 * 所以真正决定行为的是 `res/xml/network_security_config.xml` 的 base-config，
 * manifest 里那个只是同一决策的镜像。**只改其一 ⇒ 静默回落到明文禁止**，
 * 而且不会有任何报错 —— 这正是需要绊线的原因。
 *
 * ## ③ 明文放行 ≠ 放宽证书信任
 *
 * 这两条是独立的。放行 http 不代表该信任自签证书：https 目标仍只认系统 CA。
 * 防止有人在"反正都明文了"的顺手改动里加上 `<certificates src="user" />`。
 *
 * ## 反向验证
 *
 * 把 manifest 回滚到 `usesCleartextTraffic="false"`，
 * `manifestEnablesCleartextGlobally` 必然**变红**（已实跑，见 UX13_P4_CLEARTEXT_GLOBAL.md）。
 */
class CleartextPolicyTest {

    private fun src(rel: String): String {
        var dir: File? = File(".").absoluteFile
        repeat(4) {
            val f = File(dir, rel)
            if (f.isFile) return f.readText(Charsets.UTF_8)
            dir = dir?.parentFile
        }
        throw AssertionError("找不到源文件: $rel（从 ${File(".").absolutePath} 向上找了 4 级）")
    }

    /**
     * 去掉 XML 注释后再做匹配。
     *
     * 注释对 Android 的解析**没有任何作用**，所以只该在真实标记上做断言：
     *  - 否则文档注释里引用了 `<certificates src="user" />` 这类字面量会让
     *    禁止类断言假红（本轮实跑踩到过）；
     *  - 更危险的是反向：注释里只要写一句 `android:usesCleartextTraffic="true"`
     *    就能让真值为 false 的配置假绿，绊线就形同虚设。
     */
    private fun stripXmlComments(raw: String): String =
        raw.replace(Regex("<!--[\\s\\S]*?-->"), "")

    private val manifest: String
        get() = stripXmlComments(src("src/main/AndroidManifest.xml"))

    private val networkSecurityConfig: String
        get() = stripXmlComments(src("src/main/res/xml/network_security_config.xml"))

    /**
     * ① manifest 侧：显式的全局明文开关。
     *
     * 断言的是 `="true"` 这个字面量，而不是"没有 false"——
     * 后者会让属性被整个删掉时假绿，而删掉意味着回到各 targetSdk 的默认值。
     */
    @Test
    fun manifestEnablesCleartextGlobally() {
        assertTrue(
            "manifest 必须显式写 android:usesCleartextTraffic=\"true\"：" +
                "2026-10-06 产品负责人拍板选项 C，App 要能像官方桌面端一样直连任意 http 服务器",
            manifest.contains("android:usesCleartextTraffic=\"true\""),
        )
        assertFalse(
            "选项 C 决策已生效，不得残留 usesCleartextTraffic=\"false\"（该属性与 true 互斥，" +
                "重复出现时行为取决于解析顺序，属于静默回退风险）",
            Regex("android:usesCleartextTraffic\\s*=\\s*\"false\"").containsMatchIn(manifest),
        )
    }

    /**
     * ② 真正生效的那一处：network-security-config 的 base-config。
     *
     * 只切 base-config 块再断言 —— 因为下方 domain-config 在"逐条放行"时代
     * 本来就是 `cleartextTrafficPermitted="true"`，对整个文件做 contains 会**假绿**。
     */
    @Test
    fun networkSecurityConfigPermitsCleartext() {
        val start = networkSecurityConfig.indexOf("<base-config")
        assertTrue(
            "res/xml/network_security_config.xml 必须存在 <base-config>",
            start >= 0,
        )
        // 只切出 base-config 的**开标签** —— 下方 domain-config 在"逐条放行"时代
        // 本来就是 cleartextTrafficPermitted="true"，对整个文件做 contains 会假绿。
        val openTagEnd = networkSecurityConfig.indexOf('>', start)
        assertTrue("base-config 必须闭合", openTagEnd >= 0)
        val openTag = networkSecurityConfig.substring(start, openTagEnd + 1)

        assertTrue(
            "base-config 必须写 cleartextTrafficPermitted=\"true\"：" +
                "Android 语义下 networkSecurityConfig 会**覆盖** manifest 的 usesCleartextTraffic，" +
                "只改 manifest 是无效的（行为仍为明文禁止）",
            Regex("cleartextTrafficPermitted\\s*=\\s*\"true\"").containsMatchIn(openTag),
        )
        assertFalse(
            "base-config 不得写 cleartextTrafficPermitted=\"false\"",
            Regex("cleartextTrafficPermitted\\s*=\\s*\"false\"").containsMatchIn(openTag),
        )
    }

    /**
     * ③ 明文放行**不等于**放宽证书信任。
     *
     * 防止"反正都明文了"式的顺手改动加入 `<certificates src="user" />` ——
     * 那会让任意自签证书被接受，与本轮决策无关且实质放宽了 https 的信任边界。
     */
    @Test
    fun systemTrustAnchorsKept() {
        assertTrue(
            "network_security_config.xml 必须保留 <certificates src=\"system\" />" +
                "（https 目标仍只认系统 CA；明文放行不等于信任自签证书）",
            networkSecurityConfig.contains("<certificates src=\"system\" />"),
        )
        assertFalse(
            "禁止加入 <certificates src=\"user\" />：那会接受任意用户/自签证书，" +
                "是超出本轮决策范围的信任边界放宽",
            Regex("<certificates\\s+src\\s*=\\s*\"user\"\\s*/>").containsMatchIn(networkSecurityConfig),
        )
    }
}