package com.xiufeigo.opencodeshell.web.host

import android.content.res.Configuration
import android.graphics.Color
import android.util.Log
import android.view.View
import android.view.Window
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONObject
import java.util.Locale

/**
 * IME 呈现策略。
 *
 * - [VARS]：只把 insets 发成 CSS 变量 / JS 事件，WebView 铺满整窗。默认。
 * - [RESIZE]：额外把 WebView 的**固定高度**压成 `窗口高 − max(nav, ime)`，
 *   让页面里的 `100dvh` / `position:fixed` 跟着一起缩（上游对话框按 `dvh` 布局）。
 *
 * 放成包级 enum（而不是塞在 companion 里）——跨文件引用更省心。
 */
enum class ImeMode {
    /** 只发变量。 */
    VARS,

    /** 发变量 **且** 压矮 WebView。 */
    RESIZE,
}

/**
 * 边到边 + insets → CSS 变量 / JS 事件。
 *
 * ### 两条路，本模块同时给
 * 1. **CSS 变量**（默认，`:root` 上）：`--oc-safe-top` / `--oc-safe-bottom` /
 *    `--oc-safe-right` / `--oc-safe-left` / `--oc-ime-bottom`，单位 **CSS px**。
 * 2. **JS 事件**：`window.addEventListener('oc:insets', e => e.detail)`，
 *    detail 与变量同构，另带 `imeVisible` / `dark`。
 *
 * ### px → CSS px 的换算是 1:1 dp
 * WebView 在默认缩放（`initialScale=1`、无固定视口宽度）下 **1 CSS px == 1 dp**，
 * 所以这里用 `TypedValue.applyDimension(COMPLEX_UNIT_DIP, px, metrics)` 换，
 * 不要除以 `density`（那是 px→dp，恰好反过来就对了：px / density = dp）。
 * 页面若声明 `<meta viewport>` 里的 `user-scalable`/固定宽度，这条 1:1 才成立。
 *
 * ### `ime.bottom` 会不会骗人
 * 会。`WindowInsetsCompat.Type.ime()` 是**平台每次交付的读数**，不是本进程缓存，
 * 但平台自己也可能报一个"僵死"的值：`wm size`/`wm density` 覆盖后显示长回去了、
 * IME 窗口没重新排版，平台按 `inset = 窗口底 − IME 窗顶` 上报，读数远大于屏幕上
 * 那块键盘的真实高度（v77 实测 2195px 上报 vs 766px 实际浮窗）。
 * 对策不是"不信 ime"，而是**同时给页面 nav 与 ime 两个变量**，让页面自己
 * `max(--oc-safe-bottom, --oc-ime-bottom)`；并在事件里给 `imeVisible`，
 * 使"隐藏动画期间 ime 短暂非零"这类瞬态不会把布局永久压矮。
 */
class InsetsHost(
    private val window: Window,
    private val root: View,
    private val onInsets: (JSONObject) -> Unit,
) {
    companion object {
        private const val TAG = "OcWebHost/Insets"
    }

    var imeMode: ImeMode = ImeMode.RESIZE
        private set

    private var last: JSONObject? = null

    init {
        // 边到边：让内容自己铺到系统栏下面，padding 由页面按变量加。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets -> publish(insets) }
        ViewCompat.requestApplyInsets(root)
    }

    /** 在宿主自己改了 imeMode / 拿到新 config 后重新算一次（读的是当前 insets）。 */
    fun refresh() {
        val insets = ViewCompat.getRootWindowInsets(root) ?: return
        publish(insets)
    }

    /**
     * F18：**把最近一次 payload 重新推一遍**，不做去重。
     *
     * ### 为什么必须有这个方法（缺陷 A 的根因就在这一段）
     * [publish] 只在 `payload != last` 时才通知消费者（去重）。而 insets 的
     * **第一次**投递发生在 [init] 的 `requestApplyInsets()`（Activity onCreate 阶段），
     * 那时**页面还没加载**，`pushInsets` 里的 `evaluateJs` 打在一个没有
     * `window.__ocAndroid` 的文档上 ⇒ payload 静默丢失；随后 insets 再也不变
     * （顶部状态栏/挖孔是**静态**的，不像 IME 那样会 0↔非0 抖动触发重发）
     * ⇒ 去重把这条静态读数**永久挡在门外**。
     *
     * 实测（改前，`research/web/FIX_F18_PHONE.md` §2）：
     * `window.__ocAndroid.getInsets() === null` 且 `--oc-safe-top` 为空串，
     * 手动调 `setImeMode()` 触发 [refresh] 也**仍然**收不到（payload 相同、被去重）。
     *
     * 修法：页面就绪（`onPageFinished`）时由宿主显式补发一次，不依赖"值变了"。
     */
    fun replay() {
        val payload = last ?: run { refresh(); return }
        runCatching { onInsets(payload) }
            .onFailure { Log.w(TAG, "onInsets consumer threw", it) }
    }

    fun setImeMode(mode: ImeMode) {
        imeMode = mode
        Log.i(TAG, "imeMode = $mode")
        refresh()
    }

    /** 系统深浅色 → 页面（`prefers-color-scheme` 之外的显式事件）。 */
    fun systemIsDark(): Boolean =
        (root.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /**
     * 让系统栏图标跟着页面的 `data-color-scheme` 走：深色 UI → 浅色图标。
     * 只在 window 真正创建后调。
     */
    fun applyBarsTheme(dark: Boolean) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
        // API 29+ 的对比度遮罩会把导航手势条画成一条白杠，关掉。
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
    }

    private fun publish(insets: WindowInsetsCompat): WindowInsetsCompat {
        val d = root.resources.displayMetrics.density.coerceAtLeast(0.01f)

        // 系统栏 / 刘海：左右下取"可见 inset"，statusBar 与 cutout 取最大。
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.statusBars() or
                WindowInsetsCompat.Type.navigationBars() or
                WindowInsetsCompat.Type.displayCutout()
        )
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())

        val dark = systemIsDark()
        val payload = JSONObject().apply {
            // 1:1 px→CSS px（=dp），见类注释。
            put("safeTop", (bars.top / d).css())
            put("safeBottom", (bars.bottom / d).css())
            put("safeLeft", (bars.left / d).css())
            put("safeRight", (bars.right / d).css())
            put("imeBottom", (ime.bottom / d).css())
            // 平台在"未被 resize 的窗口"上按 窗口底−IME窗顶 上报，ime 高度可能远大于
            // 屏幕上那块键盘（v77 实测 2195 vs 766）。给页面一个保守提示：
            // ime 盖到窗口一半以上时，height 只可能是"僵死读数"，不是键盘。
            put("imeHeight", (ime.bottom / d).css())
            put("imeVisible", ime.bottom > 0)
            // **整窗**高（不是 WebView 当前内容高）：resize 模式下 WebView 本来就被压矮了，
            // 拿它当基准会让"僵死读数守卫"（占比 ≥85%）被二次触发、也会让页面算错
            // IME 上沿（屏幕坐标 = 整窗高 − ime）。所以取 decor（rootView）高。
            put("windowHeight", (windowHeightPx() / d).css())
            put("imeMode", imeMode.name.lowercase(Locale.ROOT))
            put("dark", dark)
            put("scheme", if (dark) "dark" else "light")
        }

        applyResize(ime.bottom, bars.bottom)

        if (payload.toString() != last?.toString()) {
            last = payload
            runCatching { onInsets(payload) }
                .onFailure { Log.w(TAG, "onInsets consumer threw", it) }
        }
        // 不 consume：Activity 侧的 IME 逻辑（resize 模式）自己还要读。
        return insets
    }

    /**
     * `ImeMode.RESIZE` 时把**视口底部**压矮 `max(nav, ime)`，让布局视口真的跟着键盘收。
     *
     * ### 改了什么（TERM1，2026-10-07）
     * 旧实现想在 `android.R.id.content` 上找宿主层（`root.findViewById(android.R.id.content)`）
     * —— content 是 WebView 的**祖先**（`setContentView(webView)` 把它塞进 content FrameLayout），
     * 往下找只会得到 null ⇒ `applyResize` 从来没生效过（旧代码里那道针对 MATCH_PARENT
     * 的 `lp.height` 守卫也只是第二层死保险，MATCH_PARENT 恒等于它排除掉的那个值）。
     * 于是视口永不 resize，只有 composer 一条让位路径，终端画布被键盘遮住 310px（实测）。
     *
     * 现在压在**真正的那一层**：WebView 的父（= content 层）的 `paddingBottom`。
     * 子 View 是 MATCH_PARENT ⇒ 内容区直接变矮 ⇒ 页面 `innerHeight` 跟着变 ✅。
     * 取 `max(nav, ime)`，并用 [ImeResize.bottomPadPx] 挡掉平台的"僵死读数"。
     */
    private fun applyResize(imeBottom: Int, navBottom: Int) {
        val want = if (imeMode == ImeMode.RESIZE) {
            // 基准是**整窗高**：`root.height` 在压矮之后就是"已压矮的高度"，
            // 拿它算占比会在键盘偏大时自我触发守卫（越压越像僵死读数）。
            ImeResize.bottomPadPx(windowHeightPx(), imeBottom, navBottom)
        } else {
            0
        }
        val layer = resizeTarget()
        if (layer.paddingBottom == want) return
        layer.setPadding(layer.paddingLeft, layer.paddingTop, layer.paddingRight, want)
        Log.i(TAG, "viewport bottom pad = $want px (ime=$imeBottom nav=$navBottom window=${windowHeightPx()} target=${layer.javaClass.simpleName})")
    }

    /** 整窗高（px）：decor 视图高，边到边下就是包含系统栏的整块屏幕。 */
    private fun windowHeightPx(): Int {
        val decor = root.rootView
        return if (decor != null && decor.height > 0) decor.height else root.height
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

    /** px→dp（=CSS px）并格式化成两位小数，避免浮点尾巴进 CSS。 */
    private fun Float.css(): String = String.format(Locale.US, "%.2f", this)
}
