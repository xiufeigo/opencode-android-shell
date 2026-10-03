package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.header.sessionOverflowMenuOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v43 真机批：会话头 ⋮ 溢出菜单贴死右屏边 + 条目文案被截断
 * (`research/v43_verify/tE/v43_38_more2.png` — 菜单卡片右缘离屏幕只有 2.5 dp，
 * 「Command palette」显示成 `Command pal…`，「Toggle debug bar」被切)。
 *
 * 现象：`Popup` 的 `PopupPositionProvider` 直接返回
 * `anchorBounds.right - popupContentSize.width`，没有拿 `calculatePosition` 给的
 * `windowSize` 夹一道。⋮ 按钮是 header 最右边的控件，它的右缘就是屏幕右缘，
 * 于是菜单卡片的右缘被顶到了屏幕上；卡片本身又是 `.width(120.dp)` 定宽，
 * 而 Android 自己追加的两行（命令面板 / 调试栏）比上游 120 px 的菜单里任何一行
 * 都长，于是进了省略号。
 *
 * 修法：位置走 [sessionOverflowMenuOffset]（贴右对齐后按窗口宽度夹住，两侧各留
 * 8 dp）；宽度改成 `widthIn(min = 120.dp, max = 200.dp)`，装得下就还是 §2.2 的
 * 120 px，装不下才长。
 */
class OverflowMenuEdgeInsetTest {

    private fun header(): String {
        val file = File("src/main/java/com/xiufeigo/opencodeshell/ui/session/header/SessionHeader.kt")
            .takeIf { it.isFile }
            ?: File("app/src/main/java/com/xiufeigo/opencodeshell/ui/session/header/SessionHeader.kt")
        assertTrue("missing source: SessionHeader.kt", file.isFile)
        return file.readText()
    }

    private fun overflowMenu(): String {
        val body = header()
        val start = body.indexOf("private fun SessionOverflowMenu")
        val end = body.indexOf("private fun MenuDivider")
        assertTrue(start >= 0 && end > start)
        return body.substring(start, end)
    }

    // ---- placement ---------------------------------------------------------

    @Test
    fun menuIsPulledBackFromTheWindowsTrailingEdge() {
        // The ⋮ anchor's right edge is the window's right edge (a 1080 px window,
        // 120 px menu, 16 px inset): the unclamped answer put the card flush
        // against the screen, ~2.5 dp from it on device.
        val x = sessionOverflowMenuOffset(
            anchorRight = 1080,
            anchorBottom = 48,
            popupWidth = 120,
            windowWidth = 1080,
            gapPx = 8,
            edgeInsetPx = 16,
        ).x
        assertEquals(1080 - 120 - 16, x)
        assertTrue(
            "the menu must keep a gap to the window's trailing edge, got $x",
            x + 120 <= 1080 - 16,
        )
    }

    @Test
    fun menuStaysUnderTheAnchorWhenThereIsRoom() {
        // A ⋮ button well inside the window keeps the upstream alignment:
        // right-aligned under the anchor, 4 dp below it.
        val offset = sessionOverflowMenuOffset(
            anchorRight = 400,
            anchorBottom = 48,
            popupWidth = 120,
            windowWidth = 1080,
            gapPx = 8,
            edgeInsetPx = 16,
        )
        assertEquals(400 - 120, offset.x)
        assertEquals(48 + 8, offset.y)
    }

    @Test
    fun menuNeverRunsOffTheLeadingEdge() {
        // A window narrower than the menu: the clamp on the right would
        // otherwise produce a negative x and push half the card off-screen.
        val x = sessionOverflowMenuOffset(
            anchorRight = 60,
            anchorBottom = 10,
            popupWidth = 200,
            windowWidth = 150,
            gapPx = 8,
            edgeInsetPx = 16,
        ).x
        assertEquals(0, x)
    }

    @Test
    fun menuGapBelowTheAnchorIsUntouchedByTheClamp() {
        val offset = sessionOverflowMenuOffset(
            anchorRight = 1080,
            anchorBottom = 120,
            popupWidth = 200,
            windowWidth = 1080,
            gapPx = 8,
            edgeInsetPx = 16,
        )
        assertEquals(128, offset.y)
    }

    // ---- width -------------------------------------------------------------

    @Test
    fun menuWidthGrowsPastTheFixedUpstreamWidthForLongerRows() {
        val menu = overflowMenu()
        assertTrue(
            "the ⋮ menu must not be pinned to upstream's fixed 120 px, " +
                "or the shell's own rows ellipsise",
            menu.contains("widthIn(min = MENU_WIDTH, max = MENU_MAX_WIDTH)"),
        )
        assertTrue(!menu.contains(".width(MENU_WIDTH)"))
    }

    @Test
    fun thePositionProviderGoesThroughTheClampingHelper() {
        val menu = overflowMenu()
        assertTrue(menu.contains("sessionOverflowMenuOffset("))
        assertTrue(
            "the raw anchor arithmetic is what put the card on the screen edge",
            !menu.contains("x = anchorBounds.right - popupContentSize.width"),
        )
    }

    @Test
    fun theClampReadsTheWindowWidthThePopupHandsIt() {
        val body = header()
        val start = body.indexOf("override fun calculatePosition(")
        assertTrue(start >= 0)
        val signature = body.substring(start, body.indexOf("): IntOffset", start))
        assertTrue(signature.contains("windowSize: IntSize"))
        val helper = body.indexOf("internal fun sessionOverflowMenuOffset")
        assertTrue(helper >= 0)
        val params = body.substring(helper, body.indexOf("): IntOffset", helper))
        assertTrue("the helper must take the window width to clamp against", params.contains("windowWidth: Int"))
    }
}
