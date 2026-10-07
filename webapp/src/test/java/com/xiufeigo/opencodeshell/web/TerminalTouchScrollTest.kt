package com.xiufeigo.opencodeshell.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * TERM2 绊线：**手机上拖动终端看历史**（产品负责人 2026-10-07 21:21 报单）。
 *
 * ## 根因（读控件产物得出，非猜测）
 *
 * 终端控件 ghostty-web 只监听 `wheel` / `mousedown` / `mousemove` / `click` ——
 * 它的产物里 `touchstart` / `touchmove` 出现 **0 次**。桌面靠滚轮、或拖右侧那条 8px 滚动条；
 * 手机上既没有滚轮、手指又落不到那条窄带上 ⇒ **屏幕中间怎么拖都不动**（设备实测：
 * 拖动前后终端内容完全一致，都是 50..80）。
 *
 * ## 修法（本类钉住的不变量）
 *
 * 宿主把"竖直拖动"翻译成控件认得的 `wheel`：
 * · 控件的 `handleWheel` 在 `deltaMode === DOM_DELTA_PIXEL` 时按
 *   `lines = deltaY / 单元格高`（实测 8 CSS px）换算 ⇒ 1px 位移 = 1px 滚动，跟手；
 * · **必须从触发点（画布）派发**：控件的监听挂在 `open(element)` 那个节点上，
 *   事件只向上冒泡 —— 派给它的祖先（面板）它收不到。这一条是本轮实际踩过的坑：
 *   计数器显示 wheel 到了面板，控件毫无反应。
 * · 位移小于 6px 一律当轻点，**不 preventDefault**，控件自己的聚焦/选择/点链接照旧
 *   （实测：轻点终端仍能弹出键盘 `mInputShown=true`）。
 * · **每帧只发一个** wheel：一次拖动几十个 pointermove 会让控件的 `smoothScrollTo`
 *   反复重启（实测画布出现"只重绘一部分"的残留）。
 * · 只加 `touch-action:none` 到终端面板，且不碰任何逐键事件（`InputPathHotspotTest` 的红线）。
 */
class TerminalTouchScrollTest {

    private val js: String by lazy {
        val f = File("src/main/assets/www/oc-host.js")
        assertTrue("找不到 src/main/assets/www/oc-host.js（单测工作目录应为 webapp/ 模块目录）", f.isFile)
        f.readText()
    }

    private val block: String by lazy {
        val idx = js.indexOf("OC-TERM-TOUCH")
        assertTrue("oc-host.js 里找不到 OC-TERM-TOUCH 段（终端触摸滚动）", idx >= 0)
        js.substring(idx)
    }

    @Test
    fun translatesTouchDragIntoWheelOnTheTerminal() {
        assertTrue("必须用 pointer 事件（不是逐键事件）", block.contains("\"pointerdown\"") && block.contains("\"pointermove\""))
        assertTrue("只在终端面板内起手", block.contains("closest('[data-component=\"terminal\"]')"))
        assertTrue("必须有轻点阈值常量", block.contains("OC_TERM_TOUCH_SLOP"))
        assertTrue("必须发 wheel", block.contains("new WheelEvent(\"wheel\""))
        assertTrue(
            "deltaMode 必须是 pixel（控件按单元格高换算成行；用 line/page 会滚过头）",
            block.contains("deltaMode: WheelEvent.DOM_DELTA_PIXEL"),
        )
        assertTrue("必须是可取消、可冒泡的事件", block.contains("bubbles: true") && block.contains("cancelable: true"))
    }

    @Test
    fun dispatchesFromTheEventTargetNotAnAncestor() {
        // 本轮真踩过的坑：控件的监听在 open(element) 那个节点上，派给祖先收不到。
        assertTrue(
            "必须从触发点本身派发（node.dispatchEvent），不能派在 closest 出来的祖先上",
            block.contains("node.dispatchEvent(wheel)"),
        )
        assertFalse(
            "禁止把事件派在 closest(...) 得到的面板上（控件不在那里监听）",
            block.contains("panel.dispatchEvent(wheel)"),
        )
    }

    @Test
    fun tapsAreLeftToTheWidgetAndDragsAreCoalescedPerFrame() {
        // 轻点：不超过阈值就 return，绝不 preventDefault（否则点终端弹不出键盘）
        val slopIdx = block.indexOf("track.moved < OC_TERM_TOUCH_SLOP")
        assertTrue("必须先判阈值", slopIdx > 0)
        val after = block.substring(slopIdx, minOf(block.length, slopIdx + 260))
        assertTrue("未超阈值必须直接 return（把事件留给控件）", after.contains("return"))
        // 合并：位移先攒，rAF 里一次发出去
        assertTrue("必须用 requestAnimationFrame 合并", block.contains("window.requestAnimationFrame(flushWheel)"))
        assertTrue("必须把这一帧的位移累加", block.contains("pendingDy += dy"))
        assertTrue("flush 之后必须清零（否则下一帧重复发）", block.contains("pendingDy = 0"))
    }

    @Test
    fun scopesTouchActionToTheTerminalOnly() {
        assertTrue(
            "只给终端面板/画布加 touch-action:none（别处页面照旧）",
            block.contains("[data-component=\"terminal\"],[data-component=\"terminal\"] canvas{touch-action:none;}"),
        )
        assertTrue("样式要走宿主自己的 style 标签且幂等", block.contains("OC_TERM_TOUCH_STYLE_ID") && block.contains("getElementById(OC_TERM_TOUCH_STYLE_ID)"))
        assertTrue("安装必须幂等", block.contains("__ocTermTouch"))
        assertTrue("文档开始就装（终端懒挂载）", block.contains("installTerminalTouchScroll();"))
    }

    // ---- 松手惯性（fling）--------------------------------------------------

    @Test
    fun flingHasBoundedConstants() {
        listOf(
            "OC_TERM_FLING_MIN",
            "OC_TERM_FLING_DECAY",
            "OC_TERM_FLING_STOP",
            "OC_TERM_FLING_WINDOW_MS",
            "OC_TERM_FLING_MAX_FRAME_MS",
            "OC_TERM_FLING_MAX_MS",
        ).forEach { k -> assertTrue("缺少惯性常量 $k", block.contains(k)) }
        assertTrue("衰减必须是 (0,1) 的比值", Regex("""OC_TERM_FLING_DECAY\s*=\s*0\.\d+""").containsMatchIn(block))
        assertTrue(
            "必须有停止阈值与最长时长两道保险（否则可能一直滚）",
            Regex("""OC_TERM_FLING_STOP\s*=\s*0\.0\d+""").containsMatchIn(block) &&
                Regex("""OC_TERM_FLING_MAX_MS\s*=\s*\d+""").containsMatchIn(block),
        )
    }

    @Test
    fun flingVelocityUsesATimeWindowNotJustTheLastTwoSamples() {
        assertTrue("必须有算速度的具名函数", block.contains("function flingVelocity("))
        assertTrue("样本必须按时间窗口裁剪", block.contains("OC_TERM_FLING_WINDOW_MS") && block.contains("samples.length > 2"))
        assertTrue("必须用真实时间差算（不是假定 16ms 一帧）", block.contains("var dt = last.t - first.t"))
        assertTrue(
            "方向必须与拖动一致（手指上滑为正 = 往最新走）",
            block.contains("return (first.y - last.y) / dt"),
        )
    }

    @Test
    fun flingOnlyStartsAfterARealDragAndStopsOnTouch() {
        assertTrue("轻点/未超阈值不得起甩", block.contains("if (t.moved < OC_TERM_TOUCH_SLOP) return;"))
        assertTrue("速度不够不得起甩", block.contains("if (Math.abs(v) >= OC_TERM_FLING_MIN) startFling(v, t.target, e)"))
        assertTrue("只认 pointerup 起甩（cancel 不起）", block.contains("e.type !== \"pointerup\""))
        assertTrue("手指一碰就停", block.contains("stopFling(); // 手一碰就停"))
        assertTrue("掉帧保护用真实帧长", block.contains("OC_TERM_FLING_MAX_FRAME_MS"))
        assertTrue("目标离开文档/终端就停", block.contains("stillInTerminal(target)"))
        assertTrue("每帧只发一个 wheel", block.contains("ocDispatchTerminalWheel(target, v * dt, pos)"))
    }
}
