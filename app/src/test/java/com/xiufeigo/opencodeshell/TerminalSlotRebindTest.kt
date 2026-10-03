package com.xiufeigo.opencodeshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tab-switch rebinding (P1): `AndroidView` only calls `attachIo` from its
 * factory, so without a per-tab key the first tab's `mIo`/`mEmulator` stuck for
 * the panel's lifetime — tab 2 rendered tab 1's screen, every IME byte went
 * into tab 1's PTY, and after tab 1 closed those bytes were silently dropped.
 */
class TerminalSlotRebindTest {

    private fun terminalPanel(): String =
        listOf(
            File("src/main/java/com/xiufeigo/opencodeshell/ui/terminal/TerminalPanel.kt"),
            File("app/src/main/java/com/xiufeigo/opencodeshell/ui/terminal/TerminalPanel.kt"),
        ).firstOrNull { it.isFile }?.readText() ?: error("missing TerminalPanel.kt")

    @Test
    fun `the render slot is keyed per tab so the factory rebinds on switch`() {
        assertTrue(
            "key(runtime.id) must recreate the view (and re-run attachIo) per tab",
            terminalPanel().contains("key(runtime.id)"),
        )
    }

    @Test
    fun `both the factory and the update block install the transport`() {
        val src = terminalPanel()
        // Exactly two live call sites: AndroidView.factory and the update
        // guard (the kdoc snippet `attachIo(runtime.io)` has no `view.` receiver).
        assertEquals(2, Regex("""view\.attachIo\(""").findAll(src).count())
        assertTrue("the update guard compares the installed transport", src.contains("view.mIo !== io"))
    }
}
