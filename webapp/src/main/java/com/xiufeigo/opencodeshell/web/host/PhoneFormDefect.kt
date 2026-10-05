package com.xiufeigo.opencodeshell.web.host

/**
 * F18 纯几何件：把「顶部内容是否避开状态栏/挖孔」和「BACK 该怎么路由」写成可在
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

    /**
     * BACK 路由决策（缺陷 B 的判据，纯函数版）。
     *
     * - [panelOpen] = 页面报告有浮层/popover 打开
     * - [canGoBack] = WebView 有历史可退
     *
     * 返回值即"宿主该不该退历史"：
     * - 面板打开 ⇒ **false**（绝不能退，页面已消费这次 BACK 去关面板）
     * - 面板没开且有历史 ⇒ true
     * - 都没有 ⇒ false（既不 finish 也不退）
     */
    fun shouldGoBack(panelOpen: Boolean, canGoBack: Boolean): Boolean = !panelOpen && canGoBack

    /**
     * 按一次 BACK 后应处的界面层级（用于断言"没退会话"）。
     * 0=会话列表 1=会话视图 2=会话视图+面板打开
     */
    fun levelAfterBack(from: Int, panelOpen: Boolean, canGoBack: Boolean): Int {
        require(from == 2) { "本函数只描述『会话视图+面板打开』这一起点（from=2），实测 from=$from" }
        // 面板打开 ⇒ 停在会话视图(1) 且面板关闭；无面板且有历史 ⇒ 回列表(0)
        return if (panelOpen) 1 else 0
    }
}
