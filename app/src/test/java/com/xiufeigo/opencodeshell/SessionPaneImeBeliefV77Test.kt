package com.xiufeigo.opencodeshell

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.xiufeigo.opencodeshell.ui.session.SessionPaneLayout
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v77 P0 「wm size/density 改后重置：会话页丢工具栏/tab 条、composer 悬浮」。
 *
 * 证据（`research/v73_verify/5554/61_session_tab.png`，`emulator-5554`，1344x2992
 * @420dpi = 512x1140dp，`wm size`/`wm density` 覆盖后 reset）：
 *
 * ```
 * window                     2992 px = 1140 dp
 * 平台上报 ime.bottom        2195 px =  836 dp   ← 屏幕上那块键盘其实是 720x766 的
 *                                               「浮窗」（左上角，顶边 y=797），并没有
 *                                               贴住窗口底边；平台把它当 docked 键盘
 *                                               上报（inset = 窗口底 − IME 窗顶）
 * body                       window − strip(101.7) − ime = 209 dp
 * chrome 上限                chromeMaxHeightForComposer(209, 96) = 32 dp
 *                            → 标题行被裁到 32 dp、视图 tab 条整条不在带内 =「丢工具栏/tab 条」
 * composer                   pane 209 < 224 ⇒ compact（96 dp），锚在 body 底边
 *                            = 上报的键盘顶 ⇒「composer 悬浮」，窗口 73% 空白
 * ```
 *
 * 这段 inset 不是 app 的状态（重启后平台照样这么报，「force-stop 后仍崩坏」），
 * 平台只在 IME 自己重新排版时才放开它（「直到某个后来事件才恢复」）。所以 app 这一
 * 侧要修的是**不要盲信它**——[SessionPaneLayout.believedImeBottomPx] 兜底：
 * 只有在「窗口本来就够宽裕」（盒子付得起 essential 形的两倍）而键盘仍声称吃掉一半以上、
 * 并让面板掉到 essential 形以下时，才把 inset 收到「面板刚好保住 essential 形」处。
 * 短窗（v51–v74 量过的 308/487 dp 窗口、横屏 414 dp 盒子、桌面 696 dp 盒子）上一律
 * 原样相信，本套件逐条钉住这些不变式。
 *
 * 第二半是 [SessionScreen] 的三处测量缓存：原来用
 * `with(density) { it.height.toDp() }` 把「回调建立那一刻的密度」写进 `Dp` 状态，
 * 而回调只在**像素**尺寸变化时触发 —— 密度变了而 px 没变时，面板预算里留着的是上一套
 * Configuration 的 dp（v76 的 `SessionPaneArrangement` 输入也吃这个值）。现在测量存
 * px，dp 在读处用当前密度换算；`minimalComposer` 的种子按窗口高度重新播种。本套件也
 * 钉住这三处 + `MessageTimeline`/`CommandPalette` 里同款缓存。
 */
class SessionPaneImeBeliefV77Test {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private val sessionScreen =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")

    private val timeline =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/timeline/MessageTimeline.kt")

    private val palette =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/palette/CommandPalette.kt")

    /** 5554's own density — the screenshots above are 2.625 px/dp. */
    private val density = 2.625f

    private fun px(dp: Float): Int = (dp * density).toInt()

    // ---------------------------------------------------- 1. when to believe

    /** name, box px, nav floor px, ime px — the shape of one measured window. */
    private data class Window(val name: String, val box: Int, val floor: Int, val ime: Int)

    @Test
    fun `a keyboard is believed whenever it can be one`() {
        // Every window the earlier rounds measured, with the keyboard actually on
        // screen: the inset is a keyboard's top edge and the pane must stop the
        // composer there. The bound must be *inert* on all of them.
        val cases = listOf(
            Window("v57 308 dp window, 253 dp keyboard", px(308f) - px(101.7f), px(24f), px(253f)),
            Window("v74 720x1280@420 body, keyboard up", px(162.7f), px(24f), px(253f)),
            Window("landscape 414 dp box", px(414f), px(24f), px(260f)),
            Window("5556 desktop 696 dp box", px(696f), px(24f), px(300f)),
            Window("pixel portrait 997 dp window", px(895f), px(24f), px(300f)),
        )
        for (case in cases) {
            assertEquals(
                "${case.name}: the keyboard must keep its full claim",
                case.ime,
                SessionPaneLayout.believedImeBottomPx(
                    visibleBoxPx = case.box,
                    floorPx = case.floor,
                    imeBottomPx = case.ime,
                    essentialPx = px(SessionPaneLayout.PANE_ESSENTIAL_HEIGHT.value),
                ),
            )
        }
    }

    @Test
    fun `nothing at all is invented when there is no keyboard`() {
        val essential = px(SessionPaneLayout.PANE_ESSENTIAL_HEIGHT.value)
        for (box in listOf(0, 1, px(1000f))) {
            for (ime in listOf(0, -40)) {
                assertEquals(
                    "box=$box ime=$ime",
                    ime,
                    SessionPaneLayout.believedImeBottomPx(
                        visibleBoxPx = box,
                        floorPx = px(24f),
                        imeBottomPx = ime,
                        essentialPx = essential,
                    ),
                )
            }
        }
        // A zero/negative essential (a degenerate caller) never divides anything
        // away: the claim is returned untouched.
        assertEquals(
            500,
            SessionPaneLayout.believedImeBottomPx(
                visibleBoxPx = 4000,
                floorPx = 0,
                imeBottomPx = 500,
                essentialPx = 0,
            ),
        )
    }

    // ------------------------------------------------- 2. the measured 5554 case

    @Test
    fun `the stale 836 dp frame is capped at the pane's essential form`() {
        // The exact numbers of `research/v73_verify/5554/61_session_tab.png`
        // (1344x2992 @420dpi), a 720x766 floating IME window at y=797 the
        // platform reported as a docked keyboard.
        val window = 2992
        val strip = 267
        val box = window - strip
        val nav = px(24f)
        val ime = window - 797
        val essential = px(SessionPaneLayout.PANE_ESSENTIAL_HEIGHT.value)

        val believed = SessionPaneLayout.believedImeBottomPx(
            visibleBoxPx = box,
            floorPx = nav,
            imeBottomPx = ime,
            essentialPx = essential,
        )

        assertTrue("the raw claim is the reported 2195 px: $ime", ime == 2195)
        assertTrue("box $box, believed $believed", believed < ime)
        // What the pane is left above the believed keyboard: exactly the floor,
        // never less — that is the whole point of the bound.
        assertEquals(essential, box - (believed - nav))
        // …and the collapse it prevents is the measured one: the raw claim left
        // this pane under its essential form, which is where the chrome's budget
        // stops paying for the view tab strip.
        assertTrue("collapsed pane ${box - (ime - nav)} px", box - (ime - nav) < essential)
    }

    @Test
    fun `the capped pane can still show its chrome and a message row`() {
        // The bound is only worth anything if the pane it leaves is a session
        // pane: the whole chrome band (header row + view tab strip) and the
        // timeline's floor, with the composer's own floor paid for. The raw
        // claim's pane fails that same test — it is the reported 「丢工具栏/tab 条」,
        // the header row clipped and the strip out of the band entirely.
        val d = Density(this.density)
        val body = SessionPaneLayout.PANE_ESSENTIAL_HEIGHT
        assertEquals(284.dp, body)
        for (composer in listOf(96.dp, 116.dp, 128.dp)) {
            val chrome = SessionPaneLayout.chromeMaxHeightForComposer(body, composer)
            assertTrue(
                "body $body, composer $composer: chrome $chrome",
                chrome >= SessionPaneLayout.CHROME_FULL_HEIGHT,
            )
            assertTrue(
                "body $body, composer $composer: timeline ${body - chrome}",
                body - chrome >= SessionPaneLayout.TIMELINE_MIN_HEIGHT,
            )
        }
        // The measured collapse, in the same units: 593 px of pane (226 dp) at
        // the reported inset cannot pay the chrome band any more.
        val collapsedBody = with(d) { 593.toDp() }
        val collapsedChrome = SessionPaneLayout.chromeMaxHeightForComposer(
            collapsedBody,
            SessionPaneLayout.COMPOSER_COMPACT_HEIGHT,
        )
        assertTrue(
            "collapsed body $collapsedBody: chrome $collapsedChrome",
            collapsedChrome < SessionPaneLayout.CHROME_FULL_HEIGHT,
        )
    }

    @Test
    fun `the bound only fires on a box with room to spare`() {
        val essential = px(SessionPaneLayout.PANE_ESSENTIAL_HEIGHT.value)
        // Just under twice the essential form the box has no room to spare: the
        // keyboard keeps its claim whatever it says (this is the gate the
        // landscape/desktop readings sit under, and the reason they are untouched
        // above).
        val atGate = essential * SessionPaneLayout.PANE_ESSENTIAL_SPARE_ROOM
        assertEquals(
            atGate - 10,
            SessionPaneLayout.believedImeBottomPx(
                visibleBoxPx = atGate - 1,
                floorPx = 0,
                imeBottomPx = atGate - 10,
                essentialPx = essential,
            ),
        )
        // At the gate itself an absurd claim is already capped.
        val box = atGate
        assertEquals(
            box - essential,
            SessionPaneLayout.believedImeBottomPx(
                visibleBoxPx = box,
                floorPx = 0,
                imeBottomPx = box - 1,
                essentialPx = essential,
            ),
        )
    }

    @Test
    fun `a claim the box can afford is never touched`() {
        val essential = px(SessionPaneLayout.PANE_ESSENTIAL_HEIGHT.value)
        var total = 0
        // A sweep: with room to spare, any claim that still leaves the essential
        // form comes back verbatim, and any claim past it comes back capped —
        // monotonically, and never above the claim (the pane never invents room
        // the platform did not report, so the composer may never be pushed down
        // into a real keyboard).
        var capped = 0
        for (ratio in 0..100) {
            val box = essential * 3 + ratio * 7
            for (owed in 0..box step 13) {
                val believed = SessionPaneLayout.believedImeBottomPx(
                    visibleBoxPx = box,
                    floorPx = 0,
                    imeBottomPx = owed,
                    essentialPx = essential,
                )
                assertTrue("box $box, claim $owed: $believed", believed <= owed)
                assertTrue("box $box, claim $owed: $believed", box - believed >= essential)
                if (believed != owed) {
                    capped++
                    assertEquals(box - essential, believed)
                }
                total++
            }
        }
        assertTrue("the sweep must actually exercise the cap: $capped/$total", capped > 0)
    }

    // -------------------------------------------- 3. the pane's own inputs (P0)

    @Test
    fun `the pane's measurements are px and its dps are derived at read time`() {
        // A `Dp` written from inside an `onSizeChanged` callback carries the
        // density that was current when that callback was *built*, and the
        // callback only runs when the node's **pixel** size changes — so a
        // density change with the window's px unchanged left the previous
        // Configuration's dp inside the pane's budget.
        assertTrue(sessionScreen.contains("var composerHeightPx by remember { mutableIntStateOf(0) }"))
        assertTrue(sessionScreen.contains("var viewportHeightPx by remember { mutableIntStateOf(0) }"))
        assertTrue(sessionScreen.contains("var paneHeightPx by remember { mutableIntStateOf(0) }"))
        assertTrue(sessionScreen.contains("val composerHeight: Dp = with(density) { composerHeightPx.toDp() }"))
        assertTrue(sessionScreen.contains("val viewportHeight: Dp = with(density) { viewportHeightPx.toDp() }"))
        assertTrue(sessionScreen.contains("val paneHeight: Dp = with(density) { paneHeightPx.toDp() }"))
        assertTrue(sessionScreen.contains(".onSizeChanged { composerHeightPx = it.height }"))
        assertTrue(sessionScreen.contains(".onSizeChanged { paneHeightPx = it.height },"))
        assertTrue(sessionScreen.contains("onViewportSize = { viewportHeightPx = it.height },"))
        // The old shape must be gone from the whole file: no measurement is
        // converted at the moment it arrives any more.
        assertFalse(sessionScreen.contains("= with(density) { it.height.toDp() }"))
        assertFalse(sessionScreen.contains("= with(density) { it.width.toDp() }"))
    }

    @Test
    fun `every input of the v76 arrangement follows the configuration`() {
        // v76's arithmetic reads `paneHeight`/`composerHeight`; both are the
        // density-derived vals above now, and the arrangement's own viewport
        // report writes the px it was measured with.
        assertTrue(
            sessionScreen.contains(
                "val sharedComposer = SessionPaneLayout.composerSharesViewport(\n" +
                    "                paneHeight,\n                composerHeight,\n            )",
            ),
        )
        assertTrue(
            sessionScreen.contains(
                "val composerCeiling = (paneHeight - SessionPaneLayout.TIMELINE_MIN_CONTENT)",
            ),
        )
        assertTrue(sessionScreen.contains("onViewportSize = { viewportHeightPx = it.height },"))
        // The composer's form still reads the window's own configuration, and its
        // seed is re-taken when that reading changes.
        assertTrue(
            sessionScreen.contains(
                "var minimalComposer by remember(windowHeightDp) {",
            ),
        )
        assertTrue(
            sessionScreen.contains(
                "SessionPaneLayout.composerOneRow(\n" +
                    "            ShellChromeBudget.isShortWindow(windowHeightDp),\n" +
                    "            bodyHeightDp,\n        )",
            ),
        )
    }

    @Test
    fun `the body believes the ime inset through the bound`() {
        // The pane's box is measured *with the keyboard put back* — the insets on
        // the `BoxWithConstraints` are the top/horizontal ones only — and the
        // bottom padding is the believed claim, floored by whatever the shell's
        // own form pays (0 on the bottom-titlebar stop: its strip pays the nav
        // bar, and B-16 forbids the empty band).
        assertTrue(
            sessionScreen.contains(
                "WindowInsets.safeDrawing\n" +
                    "                    .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),",
            ),
        )
        assertTrue(sessionScreen.contains("val paneBoxPx = with(density) { maxHeight.roundToPx() }"))
        assertTrue(sessionScreen.contains("val navBottomPx = WindowInsets.navigationBars.getBottom(density)"))
        assertTrue(
            sessionScreen.contains(
                "val paneFloorPx = if (titlebarBottom && !desktop) 0 else navBottomPx",
            ),
        )
        assertTrue(
            sessionScreen.contains(
                "val believedImeBottomPx = SessionPaneLayout.believedImeBottomPx(",
            ),
        )
        assertTrue(sessionScreen.contains("imeBottomPx = WindowInsets.ime.getBottom(density),"))
        assertTrue(
            sessionScreen.contains(
                "essentialPx = with(density) { SessionPaneLayout.PANE_ESSENTIAL_HEIGHT.roundToPx() },",
            ),
        )
        assertTrue(
            sessionScreen.contains(
                "maxOf(paneFloorPx, believedImeBottomPx).coerceAtLeast(0).toDp()",
            ),
        )
        // …and the room the pane has *with* the keyboard is what the budget reads:
        assertTrue(sessionScreen.contains("val bodyHeightDp = (maxHeight - paneBottomDp).coerceAtLeast(0.dp)"))
        assertTrue(sessionScreen.contains("val chromeMaxHeight = remember(bodyHeightDp, titlebarBottom, composerHeight)"))
        assertTrue(sessionScreen.contains("val viewportMinHeight = remember(bodyHeightDp, titlebarBottom)"))
        assertTrue(sessionScreen.contains("Modifier.fillMaxSize().padding(bottom = paneBottomDp)"))
    }

    @Test
    fun `the same cache is gone from the timeline and the palette`() {
        assertTrue(timeline.contains("var stickyHeaderPx by remember { mutableIntStateOf(0) }"))
        assertTrue(timeline.contains("SessionPaneLayout.headerInset(with(density) { stickyHeaderPx.toDp() })"))
        assertTrue(timeline.contains("stickyHeaderPx = it.height"))
        assertFalse(timeline.contains("stickyHeaderHeight"))

        assertTrue(palette.contains("var rowWidthPx by remember { mutableIntStateOf(0) }"))
        assertTrue(palette.contains("val rowWidth: Dp = with(density) { rowWidthPx.toDp() }"))
        assertTrue(palette.contains("onSizeChanged { rowWidthPx = it.width }"))
    }

    @Test
    fun `the essential form is the sum of the bands the pane cannot lose`() {
        assertEquals(
            SessionPaneLayout.CHROME_FULL_HEIGHT +
                SessionPaneLayout.TIMELINE_MIN_HEIGHT +
                SessionPaneLayout.COMPOSER_MIN_HEIGHT +
                SessionPaneLayout.COMPOSER_REGION_PADDING,
            SessionPaneLayout.PANE_ESSENTIAL_HEIGHT,
        )
        // The chrome band is the header row plus the view tab strip — both rows,
        // because the strip is the only way to reach Changes / Files / Terminal.
        assertEquals(84.dp, SessionPaneLayout.CHROME_FULL_HEIGHT)
        assertEquals(2, SessionPaneLayout.PANE_ESSENTIAL_SPARE_ROOM)
    }
}
