package com.xiufeigo.opencodeshell.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * OC-SETTINGS 绊线：**"终端字号"进设置页**（手机端自定义终端列数）。
 *
 * ## 为什么是"注入"而不是改上游
 *
 * 手机端上游**没有**终端字号设置；这里沿用宿主注入，不修改上游设置组件
 * （Appearance 只有字体族 "Terminal Font"，字号在 `new Terminal({… fontSize: 14 …})` 里写死）。
 * 所以宿主在 **设置 → 外观** 里注入一行，值存本地，由当前 xterm 适配层读取。
 * 本类只检查设置注入的源码契约；设备表现另由完整 SPA 与 Android 验收覆盖。
 *
 * ## 设计约束（本类钉住的不变量）
 *
 * 1. **锚点用上游自己的 `data-action="settings-terminal-font"`，整行 `cloneNode(true)`**
 *    ⇒ 排版/配色/分隔线天然与上游一致，也不怕上游改类名（类名改了克隆体跟着改）。
 *    设备实测（411×914）：注入行与 "Terminal Font" 行像素级同款。
 * 2. **克隆体必须摘掉 `data-action`**：否则等于给上游塞了第二个同名字段（设置搜索会命中两次、
 *    上游的输入框逻辑可能误绑）。设备实测 `staleActions = 0`。
 * 3. **幂等**：靠 `data-oc-term-size-row` 标记复用，不重复插入；已存在则只同步选中态/文案。
 * 4. **只在 `/settings` 路径上检查**：会话页流式输出时 DOM 每秒在动，别跟着凑热闹。
 * 5. **选完整页重载**：上游 URL 自带 `?tab=appearance`，重载后仍停在同一面板（设备实测确认）；
 *    否则已挂载的终端要等重新创建才会换字号，用户会以为"没生效"。
 * 6. **列数提示必须用"未缩放"的原生 measureText**：拿被自己补丁缩放过的宽度去算，
 *    会算出 43 列（真实 63 列）—— 这个坑本轮真踩过。
 */
class TerminalSizeSettingTest {

    private val js: String by lazy {
        val f = File("src/main/assets/www/oc-host.js")
        assertTrue("找不到 src/main/assets/www/oc-host.js（单测工作目录应为 webapp/ 模块目录）", f.isFile)
        f.readText()
    }

    private val settings: String by lazy {
        val idx = js.indexOf("OC-SETTINGS")
        assertTrue("oc-host.js 里找不到 OC-SETTINGS 段（终端字号设置项）", idx >= 0)
        js.substring(idx)
    }

    @Test
    fun settingIsStoredClampedAndDefaultsToNarrowScreenValue() {
        assertTrue("存储键必须走宿主自己的命名空间", settings.contains("oc.settings.terminalFontScale"))
        assertTrue("必须提供读取函数", settings.contains("function ocTerminalScale()"))
        assertTrue(
            "越界值必须夹回 [0.5, 1]（用户手改 localStorage 也不能画出格）",
            settings.contains("Math.min(1, Math.max(0.5, v))"),
        )
        assertTrue("读不到时手机使用缩小字号、宽屏使用原字号", settings.contains(".matches ? OC_TERM_SCALE_DEFAULT : 1"))
        assertTrue(
            "绘制与度量必须共同使用本页缓存的设置值",
            js.contains("ocAppliedTerminalScale = ocTerminalScale()") &&
                js.contains("var z = ocTerminalRenderScale();") &&
                js.contains("ocScaleFontPx(String(value), ocTerminalRenderScale())"),
        )
    }

    @Test
    fun rowIsClonedFromUpstreamFontRowAndKeepsItsStyling() {
        assertTrue(
            "锚点必须是上游自己的终端字体行",
            settings.contains("[data-action=\"settings-terminal-font\"]"),
        )
        assertTrue(
            "必须整行克隆（样式靠上游自己）",
            settings.contains("row.cloneNode(true)") && settings.contains("[data-component=\"settings-row\"]"),
        )
        assertTrue("克隆体必须打上自己的标记", settings.contains("OC_TERM_SIZE_ATTR") && settings.contains("data-oc-term-size-row"))
        assertTrue(
            "必须插在终端字体行之后",
            settings.contains("row.parentElement.insertBefore(row_, row.nextSibling)"),
        )
    }

    @Test
    fun cloneDropsUpstreamActionAndStaysIdempotent() {
        assertTrue(
            "克隆体必须摘掉上游 data-action（否则成了第二个同名字段）",
            settings.contains("querySelectorAll(\"[data-action]\")") && settings.contains("removeAttribute(\"data-action\")"),
        )
        assertTrue(
            "必须先查有没有插过（幂等）",
            settings.contains("row.parentElement.querySelector(\"[\" + OC_TERM_SIZE_ATTR + \"]\")"),
        )
        assertTrue(
            "已存在时只同步状态，不再插入",
            settings.contains("ocSyncTermSizeRow(row_)"),
        )
        assertTrue(
            "只在设置页做检查（会话页不参与）",
            settings.contains("window.location.pathname") && settings.contains("\"/settings\""),
        )
    }

    @Test
    fun choicesComeFromOneListAndApplyWithAReloadThatKeepsThePanel() {
        assertTrue("选项必须来自一处常量", settings.contains("OC_TERM_SCALE_CHOICES"))
        assertTrue(
            "每一档都要有 data-oc-size（点击时读它写库）",
            settings.contains("b.setAttribute(\"data-oc-size\"") && settings.contains("OC_TERM_SCALE_KEY"),
        )
        assertTrue(
            "选中态必须打标（样式靠它）",
            settings.contains("data-oc-active") && settings.contains("removeAttribute(\"data-oc-active\")"),
        )
        assertTrue(
            "选完必须整页重载（上游 URL 带 ?tab=appearance ⇒ 回来还在同一面板）",
            settings.contains("window.location.reload()"),
        )
    }

    @Test
    fun columnHintUsesUnscaledMetrics() {
        assertTrue(
            "必须保留一份原生 measureText",
            js.contains("ocRawMeasureText = origMeasure") && js.contains("ocRawMeasureText.call(ctx, \"M\")"),
        )
        assertFalse(
            "估算列数不得用被自己缩放过的 ctx.measureText（会算出 43 列 / 真实 63 列）",
            settings.contains("ctx.measureText(\"M\")") && !settings.contains("ocRawMeasureText ?"),
        )
        assertTrue(
            "列数提示要按设备实测口径给（去掉 padding 与滚动条余量）",
            settings.contains("panel.clientWidth - 47") && settings.contains("window.innerWidth - 65"),
        )
    }

    @Test
    fun labelsFollowTheUpstreamLanguageSetting() {
        assertTrue(
            "语言取上游自己的键（存的是带 locale 字段的编码对象）",
            settings.contains("opencode.global.dat:language"),
        )
        assertTrue("必须解析 locale 字段", settings.contains("\"locale\"") && settings.contains("ocUiLang()"))
        assertTrue("没存过时跟随系统语言", settings.contains("navigator.language"))
        assertTrue("中英两套文案", settings.contains("终端字号") && settings.contains("Terminal font size"))
    }
}
