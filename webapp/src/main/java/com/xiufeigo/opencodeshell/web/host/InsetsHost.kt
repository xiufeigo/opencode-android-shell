package com.xiufeigo.opencodeshell.web.host

import android.content.res.Configuration
import android.util.Log
import android.view.View
import android.view.Window
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject
import java.util.Locale

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
 * ### `imeBottom` 会不会骗人
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

    var imeMode: ImeMode = ImeMode.DEFAULT
        private set

    private var last: JSONObject? = null

    /** 视口 resize 执行器；InsetsHost 只负责把归一后的 insets 喂给它。 */
    private val viewportResize = ImeViewportResizeHost(root) { pushViewHeight() }

    /**
     * 系统导航形态的运行期快照：设置值在 Activity 存活期不变，读到一次即缓存；
     * 一直 UNKNOWN（读不到系统设置的设备）时靠 [gesturalObserved] 锁存判定。
     */
    private var navMode = NavigationBarMode.UNKNOWN

    /**
     * UNKNOWN 设备的手势证据锁存：一旦某次 insets 给出 `nav>0 && tappable<=0`，
     * 说明设备确实是手势导航；此后部分交付帧瞬态抄过来的 tappable>0 不再翻案
     * 成 3 键（否则 ime==0 的即时提交会把视口压矮整条导航带，且没有下一次
     * insets 就一直悬空 —— 切会话时实测形态）。
     */
    private var gesturalObserved = false

    /** 上一条原始读数日志（去重）：两次误判都卡在日志里没有平台原始值。 */
    private var lastReading = ""

    init {
        // 边到边：让内容自己铺到系统栏下面，padding 由页面按变量加。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets -> publish(insets) }
        ViewCompat.requestApplyInsets(root)
        // 首帧 layout 前 decor 高度经常是 0，tappableElement 也常把整条 navigationBars
        // 抄过来。等这一帧量完再补一次，避免「先按 3 键整条让位、过一会才降下来」。
        val firstLayoutRefresh = object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View, left: Int, top: Int, right: Int, bottom: Int,
                oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int,
            ) {
                if (v.height <= 0) return
                v.removeOnLayoutChangeListener(this)
                refresh()
            }
        }
        root.addOnLayoutChangeListener(firstLayoutRefresh)
        if (root.height > 0) root.post { refresh() }
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
     * 视口压矮后露出的那条带子 = resize 目标层的 padding 区，显示的是它自己的
     * 背景。它默认没有背景 → 透出 decor 的 `windowBackground`（`oc_host_bg`，
     * DayNight **跟系统模式**走）—— 页面主题与系统不一致时（系统深色 + 页面
     * 浅色）那条带子就是一整块黑色，"小白条所在位置直接全黑"（MIUI 平板浅色
     * 主题实测）。
     *
     * 页面把解析后的表面色通过 `reportSurfaceColor` 推上来，由这里钉在露出层上：
     * 条带跟**页面主题**走，与系统模式彻底脱钩；手势小白条浮在同色带上，
     * 观感 = 页面延伸到底（Chrome 等边到边应用同款做法）。
     */
    fun setSurfaceColor(color: Int) {
        viewportResize.setSurfaceColor(color)
    }

    private fun publish(insets: WindowInsetsCompat): WindowInsetsCompat {
        val d = root.resources.displayMetrics.density.coerceAtLeast(0.01f)

        // 系统栏 / 刘海：左右下取"可见 inset"，statusBar 与 cutout 取最大。
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.statusBars() or
                WindowInsetsCompat.Type.navigationBars() or
                WindowInsetsCompat.Type.displayCutout()
        )
        val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
        val imeRawBottom = if (insets.isVisible(WindowInsetsCompat.Type.ime())) imeInsets.bottom else 0
        // IME 窗口可见 ≠ 屏幕上有键盘：只占系统导航带的读数（实体键盘）不算键盘，
        // 在源头归一，payload 与视口压矮共用同一个值（见 ImeResize.occludingImePx）。
        val imeBottom = ImeResize.occludingImePx(imeRawBottom, bars.bottom)
        val imeVisible = imeBottom > 0
        // 手势导航上报的是整条上滑热区，只有中间那根小白条可见；
        // 整条让出会在面板下方留一条纯背景带（见 NavigationBarVisual）。
        val tappableBottom = insets.getInsetsIgnoringVisibility(
            WindowInsetsCompat.Type.tappableElement(),
        ).bottom
        // 形态缓存 + 手势证据锁存：系统设置读不到时，靠已观察到的
        // `nav>0 && tappable<=0` 钉住手势判定，挡住生命周期中段的瞬态假 3 键。
        if (navMode == NavigationBarMode.UNKNOWN) navMode = NavigationBarMode.read(root.context)
        if (NavigationBarVisual.isGesturalEvidence(bars.bottom, tappableBottom)) gesturalObserved = true
        val effectiveMode = when {
            navMode != NavigationBarMode.UNKNOWN -> navMode
            gesturalObserved -> NavigationBarMode.GESTURAL
            else -> NavigationBarMode.UNKNOWN
        }
        val navBottom = NavigationBarVisual.insetPx(bars.bottom, tappableBottom, d, effectiveMode)
        val reading = "nav=${bars.bottom} tappable=$tappableBottom imeRaw=$imeRawBottom mode=$navMode latched=$gesturalObserved -> safe=$navBottom ime=$imeBottom"
        if (reading != lastReading) {
            lastReading = reading
            Log.i(TAG, "insets $reading")
        }

        val dark = systemIsDark()
        val payload = JSONObject().apply {
            // 1:1 px→CSS px（=dp），见类注释。
            put("safeTop", (bars.top / d).css())
            put("safeBottom", (navBottom / d).css())
            put("safeLeft", (bars.left / d).css())
            put("safeRight", (bars.right / d).css())
            put("imeBottom", (imeBottom / d).css())
            // 平台在"未被 resize 的窗口"上按 窗口底−IME窗顶 上报，ime 高度可能远大于
            // 屏幕上那块键盘（v77 实测 2195 vs 766）。给页面一个保守提示：
            // ime 盖到窗口一半以上时，height 只可能是"僵死读数"，不是键盘。
            put("imeHeight", (imeBottom / d).css())
            put("imeVisible", imeVisible)
            // **整窗**高（不是 WebView 当前内容高）：resize 模式下 WebView 本来就被压矮了，
            // 拿它当基准会让"僵死读数守卫"（占比 ≥85%）被二次触发、也会让页面算错
            // IME 上沿（屏幕坐标 = 整窗高 − ime）。所以取 decor（rootView）高。
            put("windowHeight", (viewportResize.windowHeightPx() / d).css())
            // WebView 控件实高（父层 padding 之后）。官方 #root 是 h-dvh，
            // Android WebView 的 100dvh 在控件被压矮后经常不更新，要点击才重算；
            // 把实高交给页面钉死根节点，点进会话就能贴住底。
            if (root.height > 0) put("viewHeight", (root.height / d).css())
            put("imeMode", imeMode.name.lowercase(Locale.ROOT))
            put("dark", dark)
            put("scheme", if (dark) "dark" else "light")
        }

        viewportResize.apply(imeMode, imeBottom, navBottom)

        if (payload.toString() != last?.toString()) {
            last = payload
            runCatching { onInsets(payload) }
                .onFailure { Log.w(TAG, "onInsets consumer threw", it) }
        }
        // 不 consume：Activity 侧的 IME 逻辑（resize 模式）自己还要读。
        return insets
    }

    /** 把 WebView 当前高度写成 viewHeight，供页面覆盖过期的 100dvh。 */
    private fun pushViewHeight() {
        val h = root.height
        if (h <= 0) return
        val payload = last ?: return
        val density = root.resources.displayMetrics.density.coerceAtLeast(0.01f)
        val css = (h / density).css()
        if (payload.optString("viewHeight") == css) return
        payload.put("viewHeight", css)
        runCatching { onInsets(payload) }
            .onFailure { Log.w(TAG, "onInsets consumer threw", it) }
    }

    /** px→dp（=CSS px）并格式化成两位小数，避免浮点尾巴进 CSS。 */
    private fun Float.css(): String = String.format(Locale.US, "%.2f", this)
}
