package com.xiufeigo.opencodeshell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the IME-jitter + long-press fix.
 *
 * Chromium text selection / ActionMode is entered from
 * View.performLongClick(): any OnLongClickListener that returns true, and
 * isLongClickable == false, both kill it. The shell must do neither.
 */
class LongPressSelectionTest {
    private fun activitySrc(): String =
        listOf(
            File("src/main/java/com/xiufeigo/opencodeshell/MainActivity.kt"),
            File("app/src/main/java/com/xiufeigo/opencodeshell/MainActivity.kt"),
        ).firstOrNull { it.isFile }?.readText()
            ?: error("missing MainActivity.kt")

    @Test
    fun longPressIsNotConsumedByShell() {
        val src = activitySrc()
        assertFalse(
            "setOnLongClickListener { true } swallows performLongClick; selection can never start",
            src.contains("setOnLongClickListener { true }"),
        )
        assertTrue(src.contains("isLongClickable = true"))
    }

    @Test
    fun touchListenerKeepsLongPressDetectionAlive() {
        val src = activitySrc()
        // ACTION_DOWN must reach onTouchEvent (return false) or the framework
        // never posts the long-press check; focus is only requested when lost.
        assertTrue(src.contains("MotionEvent.ACTION_DOWN && !view.hasFocus()"))
    }

    @Test
    fun touchFixCssKeepsContentSelectable() {
        val src = activitySrc()
        assertFalse(
            "-webkit-touch-callout is an iOS-only no-op on Android",
            src.contains("-webkit-touch-callout"),
        )
        for (selector in listOf(
            "[data-component=\"text-part\"]",
            "[data-slot=\"text-part-body\"]",
            "[data-slot=\"user-message-body\"]",
            "[data-slot=\"user-message-text\"]",
            "[data-component=\"tool-output\"]",
            "[data-slot*=\"markdown\"]",
            "[data-slot=\"message-part-todo-content\"]",
            "[data-slot=\"session-review-diff-wrapper\"]",
        )) {
            assertTrue("selection whitelist must cover $selector", src.contains(selector))
        }
        assertTrue(src.contains("user-select:text"))
    }

    @Test
    fun imeScrollReactSettlesInsteadOfChasingEveryFrame() {
        val src = activitySrc()
        assertFalse(
            "window resize fires every animation frame; scrolling per-frame is the jitter",
            src.contains("window.addEventListener('resize'"),
        )
        assertTrue(src.contains("visualViewport.addEventListener('resize'"))
        assertTrue(src.contains("SETTLE_MS"))
        assertTrue(src.contains("block: 'nearest'"))
        // Focusin double tick: early position + settled re-check.
        assertTrue(src.contains("setTimeout(scrollFocused, 320)"))
        // Occlusion guard: fully visible fields must not move.
        assertTrue(src.contains("viewportHeight() - 8"))
    }

    @Test
    fun imeHiddenNotificationIsEdgeTriggered() {
        val src = activitySrc()
        assertTrue(src.contains("handleImeVisibility"))
        assertTrue(src.contains("imeWasVisible"))
    }

    @Test
    fun imePaddingSkipsIdenticalInsetsOnAllSides() {
        val src = activitySrc()
        assertTrue(src.contains("lastPaddingLeft"))
        assertTrue(src.contains("lastPaddingBottom"))
    }

    @Test
    fun imeRestoreSyntheticResizeIsKeptForTerminalRefit() {
        val src = activitySrc()
        assertTrue(src.contains("IME_RESTORE_JS"))
        assertTrue(src.contains("dispatchEvent(new Event('resize'))"))
    }
}
