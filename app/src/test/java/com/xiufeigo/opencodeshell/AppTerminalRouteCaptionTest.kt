package com.xiufeigo.opencodeshell

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v40 tB, second host: `DesktopTabStrip` learned to keep the desktop window's
 * 36 dp caption clear, but the standalone terminal route did not. It clears the
 * system bars with `WindowInsets.safeDrawing` — which a desktop/freeform window
 * reports as nothing — so the — ▭ ✕ band kept the top of `TerminalPanel`'s tab
 * row: taps on the whole row did nothing while `Ctrl+K` answered in the same
 * frame (`research/v40_verify/tB/`).
 *
 * The reserve is the one from `ui/nav/DesktopTabStrip.kt`, read through the
 * shared `@Composable` overload; the judgement ("no top system furniture ⇒ a
 * caption owns that band") must not be written a second time here.
 */
class AppTerminalRouteCaptionTest {

    private fun app(): String {
        val file = File("src/main/java/com/xiufeigo/opencodeshell/ui/App.kt")
            .takeIf { it.isFile }
            ?: File("app/src/main/java/com/xiufeigo/opencodeshell/ui/App.kt")
        return file.readText().replace("\r\n", "\n")
    }

    @Test
    fun theTerminalRouteClearsTheCaptionBand() {
        val body = app()
        assertTrue(
            "the route reads the shared reserve, not its own judgement",
            body.contains("import com.xiufeigo.opencodeshell.ui.nav.desktopCaptionReserve"),
        )
        assertTrue(
            "Route.TERMINAL pads the window by it",
            body.contains("val captionReserve = desktopCaptionReserve()") &&
                body.contains(".windowInsetsPadding(WindowInsets.safeDrawing)\n                                .padding(top = captionReserve)"),
        )
    }

    @Test
    fun theRouteDoesNotReDeriveTheCaptionHeight() {
        val body = app()
        assertTrue(
            "36.dp belongs to DesktopTabStrip only",
            !body.contains("36.dp"),
        )
    }
}
