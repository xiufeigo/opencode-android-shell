package com.xiufeigo.opencodeshell

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.xiufeigo.opencodeshell.ui.overlay.DialogScrimState
import com.xiufeigo.opencodeshell.ui.overlay.scrimTapDismisses
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v71 — 「点主 composer 关不掉 `/btw` 卡」(emulator-5558,
 * `research/v71_verify/5558/55-66`): with the card up, a tap on the main composer
 * behind it neither closed the card nor reached the composer — the card's field
 * kept the focus, so what was typed next landed in the card — while a tap on the
 * upper scrim (y=200) closed it in one hit and handed the composer its focus
 * back (the v69 C2 path, `MentionFirstWaveV69Test`).
 *
 * Root cause: the scrim's tap-outside test read the box **the host measured**
 * around the layer's content, and for `/btw` that box is not the card. The panel
 * pads its root by `WindowInsets.safeDrawing` so the IME lifts it off the
 * keyboard (device v32), which makes the measured box the card **plus the whole
 * keyboard strip**: the composer behind the card sat inside it, the scrim
 * consumed the tap and `scrimTapDismisses` said "inside the card", so nothing
 * happened.
 *
 * The fix is the marker the hand-rolled modals already use: the layer reports
 * the card itself ([com.xiufeigo.opencodeshell.ui.overlay.dialogCardBounds],
 * fed by `LocalDialogScrimState`), and the reported card outranks the host's own
 * measurement ([DialogScrimState.guardedBounds]). Every layer whose root *is*
 * its card reports nothing and keeps the host's box, byte for byte.
 *
 * The geometry below is the 5558 run measured off `61_after_main_tap.png`
 * (1080×2424): the card's own box is y 624…1059, the safe-area wrapper the host
 * measures is y 491…1983 (status/cutout inset 133, IME inset 874 with the
 * keyboard's top edge at y=1550), and the tester's taps were y 1100…1314.
 */
class BtwScrimCardBoundsV71Test {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    private fun host() = source("src/main/java/com/xiufeigo/opencodeshell/ui/overlay/DialogHost.kt")

    private fun scrim() = source("src/main/java/com/xiufeigo/opencodeshell/ui/overlay/DialogScrim.kt")

    private fun panel() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/btw/SessionBtwPanel.kt")

    // ------------------------------------------------------- the device geometry

    private val viewportPx = 2424f
    private val topInsetPx = 133f
    private val bottomInsetPx = 874f
    private val cardPx = 435f

    /** The box `DialogHost` measures: the layer's root, safe-area padding included. */
    private fun wrapperBounds(): Rect {
        val height = topInsetPx + cardPx + bottomInsetPx
        val top = (viewportPx - height) / 2f
        return Rect(0f, top, 1080f, top + height)
    }

    /** The card's own box inside it — the top of the padded content area. */
    private fun cardBounds(): Rect {
        val top = wrapperBounds().top + topInsetPx
        return Rect(0f, top, 1080f, top + cardPx)
    }

    @Test
    fun theWrapperTheHostMeasuredIsWhatSwallowedTheComposerTap() {
        // The model has to reproduce the run it explains, or the numbers below
        // are a story: the screenshot puts the card at y 624…1059.
        assertEquals(624f, cardBounds().top, 1f)
        assertEquals(1059f, cardBounds().bottom, 1f)
        assertEquals(491f, wrapperBounds().top, 1f)

        // The card alone: the composer strip behind it (the tester's taps) is the
        // backdrop's, and so is the upper scrim they saw work.
        assertTrue(scrimTapDismisses(cardBounds(), Offset(540f, 1100f)))
        assertTrue(scrimTapDismisses(cardBounds(), Offset(540f, 1314f)))
        assertTrue(scrimTapDismisses(cardBounds(), Offset(540f, 200f)))
        // …while the card's own whitespace stays the card's.
        assertFalse(scrimTapDismisses(cardBounds(), Offset(540f, 700f)))

        // The wrapper the host measured without a report: the same two composer
        // taps read as "inside the card", which is why they closed nothing.
        assertFalse(scrimTapDismisses(wrapperBounds(), Offset(540f, 1100f)))
        assertFalse(scrimTapDismisses(wrapperBounds(), Offset(540f, 1314f)))
    }

    // --------------------------------------------------------- the decision itself

    @Test
    fun theScrimReadsTheCardsOwnBoundsOnceTheLayerReportsThem() {
        val state = DialogScrimState()
        // Before anything is measured nothing dismisses — a dismissal cannot be
        // guessed on the layer's first frame.
        assertFalse(state.dismisses(Offset(540f, 1100f)))

        // The host's own measurement of the layer's root (v71's trap).
        state.contentBounds = wrapperBounds()
        assertFalse(state.dismisses(Offset(540f, 1100f)))

        // The card's report wins: the same tap is now the backdrop's, and the
        // card itself is still guarded.
        state.cardBounds = cardBounds()
        assertTrue(state.dismisses(Offset(540f, 1100f)))
        assertTrue(state.dismisses(Offset(540f, 1314f)))
        assertFalse(state.dismisses(Offset(540f, 700f)))
    }

    // ------------------------------------------------------------- the wiring

    @Test
    fun theBtwLayerReportsTheCardAndNotTheSafeAreaWrapper() {
        val body = panel()
        assertTrue(body.contains("val scrim = LocalDialogScrimState.current"))
        assertTrue(body.contains("Modifier.dialogCardBounds(scrim)"))
        // The marker rides the card *inside* the padded root: on the root itself
        // it would measure the card plus the safe-area padding again.
        assertTrue(
            body.indexOf("windowInsetsPadding(WindowInsets.safeDrawing)") <
                body.indexOf(".dialogCardBounds(scrim)"),
        )
        // The v32 guarantee is untouched — the card is still lifted off the IME.
        assertTrue(body.contains("windowInsetsPadding(WindowInsets.safeDrawing)"))
    }

    @Test
    fun theHostHandsTheLayerItsScrimStateAndKeepsItsOwnBoxAsTheFallback() {
        val body = host()
        assertTrue(body.contains("val cardState = remember(entry.id) { DialogScrimState() }"))
        assertTrue(body.contains("CompositionLocalProvider(LocalDialogScrimState provides cardState)"))
        // The host's own measurement stays the default for every layer whose
        // root is its card (all of them but `/btw`).
        assertTrue(body.contains("cardState.contentBounds = it.boundsInRoot()"))
        assertTrue(body.contains("cardState.scrimOrigin = it.positionInRoot()"))
        assertTrue(body.contains("if (cardState.dismisses(local))"))
        assertTrue(body.contains("val LocalDialogScrimState = compositionLocalOf<DialogScrimState?> { null }"))
        // The precedence is the state's, so the host and the hand-rolled modals
        // cannot drift apart.
        val state = scrim()
        assertTrue(state.contains("internal val guardedBounds: Rect? get() = cardBounds ?: contentBounds"))
        // v73: the same call carries the backdrop override (the composer's box,
        // `backdropBounds`) — one call site, so the host and the hand-rolled modals
        // still cannot drift apart.
        assertTrue(state.contains("scrimTapDismisses(guardedBounds, tap + scrimOrigin, backdropBounds)"))
    }

    @Test
    fun theTapThatDismissesIsTheOneTheComposerHears() {
        // The restored path is the v69 C2 hand-back: the scrim's tap closes
        // through the controller, the stack empties, and the composer — which
        // reads `LocalDialogActive` — takes the caret back. That is the same
        // close the upper-scrim tap (y=200) always took; the fix is only that
        // the composer tap now takes it too.
        assertTrue(host().contains("controller.close()"))
        assertTrue(host().contains("LocalDialogActive provides stack.isNotEmpty()"))
        val composer = source(
            "src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt",
        )
        assertTrue(composer.contains("val dialogActive = LocalDialogActive.current"))
        assertTrue(composer.contains("runCatching { focusRequester.requestFocus() }"))
    }
}
