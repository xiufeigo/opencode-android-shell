package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.terminal.share.ShellOutputWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ShellOutputWindow] — the `DialogShellOutput` tail window (64 KiB, omitted
 * flag, absolute byte cursor). Pure state-machine assertions.
 */
class ShellOutputWindowTest {

    @Test
    fun `a small capture loads from byte zero and never omits`() {
        val window = ShellOutputWindow(pageBytes = 16)
        assertEquals(0L, window.prime(size = 10L))
        assertFalse(window.omitted)

        assertTrue(window.append("hello", cursor = 5L, size = 10L))
        assertEquals("hello", window.text)
        assertEquals(5L, window.cursor)
    }

    @Test
    fun `a long capture starts one page before the end and flags the omission`() {
        val window = ShellOutputWindow(pageBytes = 16)
        // 40 bytes captured → start at 24, and the head is known to be missing.
        assertEquals(24L, window.prime(size = 40L))
        assertTrue(window.omitted)
        assertEquals(24L, window.cursor)
    }

    @Test
    fun `appended pages keep only the last page of text`() {
        val window = ShellOutputWindow(pageBytes = 8)
        window.prime(size = 100L)
        window.append("abcd", cursor = 4L, size = 100L)
        window.append("efgh", cursor = 8L, size = 100L)
        window.append("ijkl", cursor = 12L, size = 100L)
        assertEquals("efghijkl", window.text)
        assertTrue(window.omitted)
        assertEquals(12L, window.cursor)
    }

    @Test
    fun `hasMore follows cursor versus size so the poller can catch up first`() {
        val window = ShellOutputWindow(pageBytes = 64)
        window.prime(size = 10L)
        assertTrue(window.append("12345", cursor = 5L, size = 10L))
        assertFalse(window.append("67890", cursor = 10L, size = 10L))
    }

    @Test
    fun `the catchup cursor is upstream MAX_SAFE_INTEGER`() {
        assertEquals(9007199254740991L, ShellOutputWindow.CATCHUP_CURSOR)
    }
}
