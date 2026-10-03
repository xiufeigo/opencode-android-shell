package com.xiufeigo.opencodeshell

import androidx.compose.ui.unit.dp
import com.xiufeigo.opencodeshell.ui.overlay.DialogViewportFit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Viewport coordination for a `DialogHost` layer
 * ([DialogViewportFit]): the host caps an overflowing layer to the measured
 * viewport and lets it scroll (upstream `dialog-content` `max-height: 100%` +
 * `overflow: auto`), so a tall body — the Pairing settings' QR block on a
 * small window — stays reachable instead of spilling past the window edge.
 *
 * Pure JVM: caps and the engagement predicate. The Compose wiring is pinned
 * with the repo's established source guard because layer measurement is not
 * reachable from a unit test.
 */
class DialogViewportFitTest {

    @Test
    fun capsSubtractTheUpstreamMargins() {
        // x-large width `100vw − 32`; tablet host cap `100% − 16px` vertically.
        assertEquals(328.dp, DialogViewportFit.maxWidth(360.dp))
        assertEquals(624.dp, DialogViewportFit.maxHeight(640.dp))
    }

    @Test
    fun capsNeverCollapseBelowTheFloor() {
        assertEquals(160.dp, DialogViewportFit.maxWidth(100.dp))
        assertEquals(160.dp, DialogViewportFit.maxHeight(100.dp))
        assertEquals(160.dp, DialogViewportFit.maxWidth(0.dp))
        assertEquals(160.dp, DialogViewportFit.maxHeight(0.dp))
    }

    @Test
    fun theFallbackEngagesStrictlyPastThePhysicalEdge() {
        // Exactly viewport-sized (every `fillMaxSize` root): never.
        assertFalse(DialogViewportFit.overflows(640, 640))
        assertFalse(DialogViewportFit.overflows(360, 360))
        // One pixel past: engage.
        assertTrue(DialogViewportFit.overflows(641, 640))
        assertTrue(DialogViewportFit.overflows(361, 360))
        // Unmeasured viewport or content: never.
        assertFalse(DialogViewportFit.overflows(0, 0))
        assertFalse(DialogViewportFit.overflows(640, 0))
        assertFalse(DialogViewportFit.overflows(0, 640))
    }

    @Test
    fun dialogHostWiresTheViewportFallback() {
        val candidates = listOf(
            File("src/main/java/com/xiufeigo/opencodeshell/ui/overlay/DialogHost.kt"),
            File("app/src/main/java/com/xiufeigo/opencodeshell/ui/overlay/DialogHost.kt"),
        )
        val host = candidates.firstOrNull { it.isFile }?.readText()
            ?: error("missing DialogHost.kt")
        // Viewport coordination: measure, engage past the edge, cap + scroll.
        assertTrue(host.contains("DialogViewportFit"))
        assertTrue(host.contains("viewportPx"))
        assertTrue(host.contains("fullContentPx"))
        assertTrue(host.contains("verticalScroll"))
        assertTrue(host.contains("widthIn("))
        assertTrue(host.contains("heightIn("))
        // Existing layer behavior stays wired.
        assertTrue(host.contains("scrimTapDismisses"))
        // Exit-gated BACK: a bare `stack.isNotEmpty()` gate stayed armed through
        // the exit transition and swallowed one press on the screen behind;
        // `hasOpenLayer` releases it when the last layer starts closing.
        assertTrue(host.contains("DialogLayerPolicy.hasOpenLayer"))
        assertFalse(
            "a bare-stack BACK gate re-arms on the exit window and eats one press on the screen behind",
            host.contains("BackHandler(enabled = stack.isNotEmpty())"),
        )
        assertTrue(host.contains("collapseImeOnEscape"))
    }
}
