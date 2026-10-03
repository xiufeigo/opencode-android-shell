package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.palette.PaletteRelativeTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The palette half of the A-3 relative-time freeze: the command palette's
 * `command-palette-meta` cell (`getRelativeTime`, `shell/commands/dialog.tsx:267-271`)
 * read `System.currentTimeMillis()` **inside** its `remember`ed label lambda.
 * Compose cannot see that read: a session row's only inputs are its `updatedAt`
 * and the search key, so the row was skipped and a meta cell composed while the
 * session was「just now」kept reading「just now」for as long as the palette stayed
 * open. The drawer's `RelativeTimeText` froze the same way and was fixed by
 * handing the row a clock that ticks while the drawer is open
 * ([SessionDrawerFreshnessTest]); the palette now takes the same shape.
 *
 * Two halves again: the mapping is behavioural (it is a pure function), the
 * wiring that the JVM cannot exercise — the ticking clock and the entry rebuild —
 * is guarded at source level.
 */
class PaletteRelativeTimeTest {

    // ------------------------------------------------------- the mapping

    /** A `now` that is deliberately nowhere near the wall clock. */
    private val updatedAt = 1_700_000_000_000L

    private fun label(updatedAt: Long, now: Long): String? =
        PaletteRelativeTime.label(
            updatedAt = updatedAt,
            now = now,
            justNow = "just now",
            minutesAgo = { count -> "$count minutes ago" },
            hoursAgo = { count -> "$count hours ago" },
            daysAgo = { count -> "$count days ago" },
        )

    @Test
    fun `the same row reads its real age once the clock moves`() {
        // The label that read「just now」when the palette opened has to follow the
        // clock — that is all the ticking `now` buys.
        assertEquals("just now", label(updatedAt, updatedAt + 30_000L))
        assertEquals("5 minutes ago", label(updatedAt, updatedAt + 5L * 60_000L + 1_000L))
        assertEquals("1 hours ago", label(updatedAt, updatedAt + 3_600_000L))
        assertEquals("9 days ago", label(updatedAt, updatedAt + 9L * 24L * 3_600_000L + 5_000L))
    }

    @Test
    fun `the label is computed from the injected clock, not the wall clock`() {
        // The fixture sits years before `System.currentTimeMillis()`, so a label
        // that read the wall clock instead of its `now` parameter would bucket
        // this row as days (hundreds of them), never as minutes.
        assertTrue(System.currentTimeMillis() - updatedAt > 24L * 3_600_000L)

        assertEquals("5 minutes ago", label(updatedAt, updatedAt + 5L * 60_000L + 1_000L))
        assertEquals("3 hours ago", label(updatedAt, updatedAt + 3L * 3_600_000L))
    }

    @Test
    fun `an unknown timestamp renders no meta cell`() {
        assertNull(label(0L, updatedAt))
        assertNull(label(-1L, updatedAt))
    }

    @Test
    fun `a clock skew never renders negative time`() {
        // Device/server skew: the drawer's bucketing clamps this to just now.
        assertEquals("just now", label(updatedAt, updatedAt - 120_000L))
    }

    // ------------------------------------------------------------ the wiring

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    private fun app() = source("src/main/java/com/xiufeigo/opencodeshell/ui/App.kt")

    @Test
    fun `the palette measures its meta cells against a clock that ticks while it is up`() {
        val app = app()

        assertTrue(
            "the label takes the host's clock",
            app.contains("val relativeTime = rememberRelativeTimeLabel(paletteNow)"),
        )
        assertTrue(app.contains("var paletteNow by remember { mutableStateOf(System.currentTimeMillis()) }"))
        assertTrue(
            "and it re-measures on a loop, not once per composition",
            app.contains("while (true) {"),
        )
        assertTrue(app.contains("paletteNow = System.currentTimeMillis()"))
        assertTrue(app.contains("delay(PALETTE_CLOCK_TICK_MS)"))
    }

    @Test
    fun `the tick is bound to the palette being on screen`() {
        val app = app()

        // The tick lives inside `PaletteDialogLayer`, which is composed exactly
        // while the layer sits on the dialog stack — so closing the palette
        // leaves composition and cancels the loop, and a nested layer (whose
        // `dialog.show` replaces the stack) takes the palette off it. That is the
        // palette's spelling of the drawer's explicit `drawerVisible` gate.
        val layerAt = app.indexOf("private fun PaletteDialogLayer(")
        val clockAt = app.indexOf("var paletteNow by remember")
        val labelAt = app.indexOf("private fun rememberRelativeTimeLabel(")
        assertTrue("the palette host must declare the clock", layerAt >= 0 && clockAt > layerAt)
        assertTrue("the clock must stay inside the layer composable", labelAt > clockAt)
        // The premise: the layer content is composed from the stack, so
        // composition lifetime is the visible gate.
        val host = source("src/main/java/com/xiufeigo/opencodeshell/ui/overlay/DialogHost.kt")
        assertTrue(host.contains("entry.content(controller)"))
    }

    @Test
    fun `the label rebuilds the entries it feeds`() {
        val app = app()

        // A clock tick changes the lambda's identity; without it as a `remember`
        // key the entries — and the meta cell baked into each one — would keep
        // the label they were built with.
        assertTrue(app.contains("remember(baseSessions, exactSession, serverKey, relativeTime)"))
    }

    @Test
    fun `no clock is read inside the label itself`() {
        val app = app()
        val labelAt = app.indexOf("private fun rememberRelativeTimeLabel(")
        val labelBody = app.substring(labelAt, minOf(app.length, labelAt + 1_400))

        // The parameter is what the lambda measures against.
        assertTrue(labelBody.contains("now = now"))
        assertTrue(!labelBody.contains("System.currentTimeMillis()"))

        // …and the mapping is a pure function, so it cannot reach a clock either.
        val mapping = source("src/main/java/com/xiufeigo/opencodeshell/ui/palette/PaletteRelativeTime.kt")
        assertTrue(!mapping.contains("currentTimeMillis"))
    }
}
