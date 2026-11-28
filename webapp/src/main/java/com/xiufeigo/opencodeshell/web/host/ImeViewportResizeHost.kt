package com.xiufeigo.opencodeshell.web.host

import android.util.Log
import android.view.View
import android.view.ViewGroup

/**
 * IME/导航带视口 resize 执行器。
 *
 * 输入是 [InsetsHost] 已归一化的 IME 与可视导航让位量；本类拥有合并窗口、
 * 首次/收起即时提交、父层 padding、WebView 像素高度与露出层背景色。
 * 页面 payload 的构造与去重仍归 [InsetsHost]，本类只在真实高度变化后回调它补发。
 */
class ImeViewportResizeHost(
    private val root: View,
    private val onViewportHeightChanged: () -> Unit,
) {
    companion object {
        private const val TAG = "OcWebHost/ImeResize"
    }

    /** 已落地的视口压矮量（px）；-1 = 还没落过（首次立即生效）。 */
    private var committedPadPx = -1

    /** 合并窗口里待落的压矮量（px）；-1 = 没有挂起的提交。 */
    private var pendingPadPx = -1
    private var pendingImePx = 0
    private var pendingNavPx = 0

    private val commitResizeRunnable = Runnable {
        val pad = pendingPadPx
        if (pad >= 0) commitResize(pad, pendingImePx, pendingNavPx, "settled")
    }

    init {
        // padding 改的是父层，WebView 高度要等下一帧 layout 才变。
        // 只靠首帧 refresh 会在压矮前就结束，页面一直拿到偏矮的 100dvh。
        root.addOnLayoutChangeListener { v, _, _, _, _, _, oldTop, _, oldBottom ->
            if (v.height > 0 && v.height != oldBottom - oldTop) onViewportHeightChanged()
        }
    }

    /** 把页面主题解析出的表面色刷到压矮后露出的那条带子上。 */
    fun setSurfaceColor(color: Int) {
        resizeTarget().setBackgroundColor(color)
    }

    /** 整窗高（px）：decor 视图高，边到边下就是包含系统栏的整块屏幕。 */
    fun windowHeightPx(): Int {
        val decor = root.rootView
        return if (decor != null && decor.height > 0) decor.height else root.height
    }

    /**
     * `ImeMode.RESIZE` 时把**视口底部**压矮 `max(nav, ime)`，让布局视口真的跟着键盘收。
     *
     * ### 改了什么（TERM1，2026-10-07）
     * 旧实现想在 `android.R.id.content` 上找宿主层（`root.findViewById(android.R.id.content)`）
     * —— content 是 WebView 的**祖先**（`setContentView(webView)` 把它塞进 content FrameLayout），
     * 往下找只会得到 null ⇒ resize 从来没生效过（旧代码里那道针对 MATCH_PARENT
     * 的 `lp.height` 守卫也只是第二层死保险，MATCH_PARENT 恒等于它排除掉的那个值）。
     * 于是视口永不 resize，只有 composer 一条让位路径，终端画布被键盘遮住 310px（实测）。
     *
     * 现在压在**真正的那一层**：WebView 的父（宿主自己包的 viewportHost）的
     * `paddingBottom`。不能压 AppCompat 的 content：它会按系统栏把 padding 改回
     * 0 / 整条导航带，WebView 仍是整屏。子 View 是 MATCH_PARENT ⇒ 内容区变矮。
     * 取 `max(nav, ime)`，并用 [ImeResize.bottomPadPx] 挡掉平台的"僵死读数"。
     */
    fun apply(mode: ImeMode, imeBottom: Int, navBottom: Int) {
        val windowH = windowHeightPx()
        // 首帧 onCreate 时 decor 高度经常是 0。ImeResize 这时会给出 pad=0；
        // 若把 0 写成已提交，后面同一份 insets 被 payload 去重，视口一直不让位，
        // 直到点进会话 / 弹键盘才突然落下。
        if (mode == ImeMode.RESIZE && windowH <= 0) return

        val want = if (mode == ImeMode.RESIZE) {
            // 基准是**整窗高**：`root.height` 在压矮之后就是"已压矮的高度"，
            // 拿它算占比会在键盘偏大时自我触发守卫（越压越像僵死读数）。
            ImeResize.bottomPadPx(windowH, imeBottom, navBottom)
        } else {
            0
        }

        // 第一次（还没有已提交值）立即生效：启动时的 inset 就是静态读数，等 80ms 没意义。
        if (committedPadPx < 0) {
            commitResize(want, imeBottom, navBottom, "first")
            return
        }
        if (imeBottom == 0) {
            root.removeCallbacks(commitResizeRunnable)
            commitResize(want, imeBottom, navBottom, "ime-hidden")
            return
        }
        if (want == committedPadPx) {
            // 回到已提交值（例如键盘动画来回抖了一下）⇒ 撤掉挂起的提交，别多压一次。
            pendingPadPx = -1
            root.removeCallbacks(commitResizeRunnable)
            return
        }
        // 键盘动画期间平台可能逐帧上报不同高度：只记最新值，等稳定 80ms 再落一次。
        // 逐帧落 = 页面里的终端反复重排（"闪 + 字体缩放"），合并后一次开关只重排一次。
        pendingPadPx = want
        pendingImePx = imeBottom
        pendingNavPx = navBottom
        root.removeCallbacks(commitResizeRunnable)
        root.postDelayed(commitResizeRunnable, ImeResize.COMMIT_DELAY_MS)
    }

    /** 真正落 padding 的那一次（合并窗口到期 / 首次）。 */
    private fun commitResize(pad: Int, imeBottom: Int, navBottom: Int, why: String) {
        committedPadPx = pad
        pendingPadPx = -1
        val layer = resizeTarget()
        if (layer.paddingBottom != pad) {
            layer.setPadding(layer.paddingLeft, layer.paddingTop, layer.paddingRight, pad)
            Log.i(TAG, "viewport bottom pad = $pad px (ime=$imeBottom nav=$navBottom window=${windowHeightPx()} target=${layer.javaClass.simpleName} why=$why)")
        }
        // Pixel Tablet 实测：AppCompat content 的 padding 留不住，WebView 仍是整屏
        // 1600px，innerHeight=800，env(safe-area-inset-bottom)=32。给 WebView 写死
        // 像素高度，布局视口才会真的矮下来。
        val lp = root.layoutParams
        if (lp is ViewGroup.LayoutParams) {
            val wantH = if (pad <= 0) {
                ViewGroup.LayoutParams.MATCH_PARENT
            } else {
                (windowHeightPx() - pad).coerceAtLeast(1)
            }
            if (lp.height != wantH || lp.width != ViewGroup.LayoutParams.MATCH_PARENT) {
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                lp.height = wantH
                root.layoutParams = lp
            }
        }
        root.requestLayout()
        // padding / 高度这一帧结束才是 WebView 的新高度；补发给页面钉 #root。
        root.post { onViewportHeightChanged() }
    }

    /**
     * 真正被压矮的那一层：WebView 的父（window 的 content FrameLayout）。
     *
     * 用 `parent` 而不是 `findViewById`：content 是祖先，往下找永远找不到（旧代码的坑）。
     * 真拿不到父（极端布局）时退化成压 WebView 自己 —— 同样能让内容区变矮。
     */
    private fun resizeTarget(): View {
        val p = root.parent
        return if (p is View) p else root
    }
}

