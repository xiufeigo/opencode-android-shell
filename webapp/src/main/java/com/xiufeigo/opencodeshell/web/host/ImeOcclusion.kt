package com.xiufeigo.opencodeshell.web.host

import kotlin.math.max
import kotlin.math.min

/**
 * "要压矮多少像素" —— IME 视口兜底的**纯函数**（[InsetsHost] 消费，[ImeResizeTest] 钉住）。
 *
 * ### 为什么需要它（TERM1，2026-10-07）
 *
 * 边到边窗口下布局视口不随键盘变矮（实测：IME 336.38 CSS px 时 `innerHeight` 仍 915），
 * 早先只给 composer 垫了底部留白 ⇒ **终端画布被键盘遮住 310px**。
 * 研究里已预告过这个脆弱点（`research/env/FORMFACTORS_F4.md` §6 问题 3）：
 * "一旦 composer 让位逻辑在某个宽度上失效，**不会退化成 resize 兜底，而是直接被遮挡**"。
 *
 * 所以宿主自己把视口压矮，任何靠视口高度的界面都自动跟着收 —— 不再依赖"每个界面记得让位"。
 *
 * ### 为什么要有"僵死读数"守卫
 *
 * `WindowInsetsCompat.Type.ime()` 是平台每次交付的读数，平台自己也可能是**僵死**的：
 * v77 实测上报 2195px 而真实键盘只有 766px（窗口 2400px，占比 **91%**）。
 * 照抄这个数会把视口压成一条缝、下方留一大块空白。所以占比 ≥ [STALE_RATIO] 时**不压**
 * （退化成"页面自己让位"的老行为，而不是把界面压坏）。
 */
object ImeResize {

    /** 上报的 IME 高度占窗口比例达到这个值就判定为僵死读数（实测事故 91%）。 */
    const val STALE_RATIO = 0.85

    /**
     * 视口底部要让出的 px。`0` = 不动（IME 收起 / 读数不可信 / 几何无效）。
     *
     * 取 `max(ime, nav)`：键盘一定比导航条高，两者重叠时导航条已经被键盘盖住。
     */
    fun bottomPadPx(
        windowHeightPx: Int,
        imeBottomPx: Int,
        navBottomPx: Int,
    ): Int {
        if (windowHeightPx <= 0) return 0
        val pad = max(imeBottomPx, navBottomPx)
        if (pad <= 0) return 0
        // 僵死读数：见类注释（别把一个 91% 的假读数当成键盘）。
        if (pad >= windowHeightPx * STALE_RATIO) return 0
        return min(pad, windowHeightPx - 1)
    }
}

/**
 * IME 遮挡几何 —— "composer 有多少像素落在键盘下面" 的**纯函数**。
 *
 * 存在的理由：这条判据是本轮修复的守护对象（IME1：composer 被键盘完全遮挡 ⇒ 等于没法打字），
 * 但它的输入（insets + composer bounds）**只有设备上才取得到**。于是把判据本身抽成
 * 不依赖 Android/JS 的纯计算，放进 JVM 单测里跑：
 *
 * - 设备侧用 [ime_repro_e7.ps1](research/env/ime_repro_e7.ps1) 取真实读数喂进来；
 * - 单测侧用固定输入覆盖边界，**修法被撤回时 [ImeOcclusionTest] 必须变红**。
 *
 * ### 坐标系（与 E6/IME1 实测一致，别混）
 * 全部是 **CSS px**，不是设备 px、不是 dp。WebView 在 `width=device-width` +
 * `initial-scale=1` 下 1 CSS px == 1 dp；设备 px 还要再乘 dpr。
 * `--oc-ime-bottom`（= `InsetsHost` 交出来的 `ime.bottom / density`）也是 CSS px。
 */
object ImeOcclusion {

    /** 单次读数：composer 在视口里的位置 + 宿主给的 IME inset。 */
    data class Rect(val top: Double, val bottom: Double) {
        init {
            require(bottom >= top) { "bottom($bottom) < top($top)" }
        }
    }

    /**
     * IME 上沿（CSS px）。IME 始终贴在视口底部，所以只需知道它多高。
     *
     * [imeBottom] <= 0（IME 收起）时返回 [viewportHeight]，即"不遮挡任何东西"。
     */
    fun imeTopCss(imeBottom: Double, viewportHeight: Double): Double =
        max(0.0, viewportHeight - max(0.0, imeBottom))

    /**
     * **重叠像素量**：composer 与 IME 区域相交的高度（CSS px）。
     *
     * 这是本轮缺陷的量化口径。IME1 改前手机侧实测 **60**（composer 全高被吞，
     * 因为 composer 高 60 而整条都落在键盘带内）⇒ 目标 **0**。
     */
    fun overlapPx(composer: Rect, imeTopCss: Double): Double {
        val overlapTop = max(composer.top, imeTopCss)
        val overlapBottom = min(composer.bottom, Double.MAX_VALUE)
        return max(0.0, overlapBottom - overlapTop)
    }

    /**
     * IME 弹起时，为让重叠归零，composer 至少要上移多少（CSS px）。
     *
     * `0` 表示**当前就不可达 0**——composer 底沿已经高于 IME 上沿（键盘比它矮）。
     * IME 收起（[imeBottom] <= 0）恒返回 0，因为这时根本不需要留白。
     */
    fun requiredLiftPx(composer: Rect, imeBottom: Double, viewportHeight: Double): Double {
        if (imeBottom <= 0.0) return 0.0
        val top = imeTopCss(imeBottom, viewportHeight)
        // composer 底沿已经在键盘上沿之上 ⇒ 无需移动。
        if (composer.bottom <= top) return 0.0
        return composer.bottom - top
    }

    /**
     * 判据本体：IME 弹起时 composer 与 IME 的重叠面积（此处高度）是否为 0。
     *
     * 注意 `imeVisible` 用 `imeBottom > 0` 判定，与 [InsetsHost] 的
     * `imeVisible = ime.bottom > 0` 保持同源，避免两处口径漂移。
     */
    fun isFullyVisible(
        composer: Rect,
        imeBottom: Double,
        viewportHeight: Double,
    ): Boolean = imeBottom <= 0.0 || overlapPx(composer, imeTopCss(imeBottom, viewportHeight)) == 0.0
}
