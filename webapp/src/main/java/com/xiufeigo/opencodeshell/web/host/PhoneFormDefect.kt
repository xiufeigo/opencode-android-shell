package com.xiufeigo.opencodeshell.web.host

/**
 * F18 纯几何件：把「顶部内容是否避开状态栏/挖孔」写成可在
 * JVM 单测里跑的纯函数。**不进生产路径**（宿主一行都没引用它），只给绊线用。
 */
object PhoneFormDefect {

    data class Rect(val top: Double, val bottom: Double)

    /**
     * 顶部遮挡量：交集 `[max(top, 0), min(bottom, insetTop)]` 的高度（CSS px）。
     *
     * 视口顶端就是 y=0，状态栏+挖孔占 `[0, safeTopCss]`。
     * 返回 0 = 顶部内容完全在安全区之下（修好了）。
     */
    fun topOverlapPx(content: Rect, safeTopCss: Double): Double {
        val lo = maxOf(content.top, 0.0)
        val hi = minOf(content.bottom, safeTopCss)
        return (hi - lo).coerceAtLeast(0.0)
    }

    /** 顶部内容是否完全避开状态栏/挖孔。 */
    fun topClear(content: Rect, safeTopCss: Double): Boolean = topOverlapPx(content, safeTopCss) == 0.0

    /** 给根容器垫了 [safeTopCss] 顶部内边距后，内容下移多少。 */
    fun shiftedByTopInset(content: Rect, safeTopCss: Double): Rect =
        Rect(content.top + safeTopCss, content.bottom + safeTopCss)

}
