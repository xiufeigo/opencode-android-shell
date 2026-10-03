package com.xiufeigo.opencodeshell

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.xiufeigo.opencodeshell.ui.overlay.DialogScrimState
import com.xiufeigo.opencodeshell.ui.overlay.SessionComposerBounds
import com.xiufeigo.opencodeshell.ui.overlay.scrimTapDismisses
import java.io.File
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v73 P2 — 「/btw 卡片桌面形态点 composer 不关」 (emulator-5556, the desktop half of
 * the v71 pair; the phone form, 5558, closed in one hit and handed the composer
 * the caret).
 *
 * The v71 fix made the layer report the card it guards, and that report is honest
 * — the re-run in `research/v73_repro2` measures the same box the card is drawn
 * in. What it cannot fix is a *short window with the keyboard up*: the layer
 * centers the card in the space above the IME, and that is exactly where the
 * session composer sits. Measured (1366×768, Gboard up, off
 * `11_btw_open.png`): the card's box is x 443…922 × y 124…259, the composer's is
 * x 12…1353 × y 230…334 — a 29 px overlap band across the card's whole width, and
 * the composer's strip *inside* the card's rectangle. A tap there was answered
 * "inside the card" by the scrim (`scrimTapDismisses`), so the card did not close
 * — 「获焦但卡不关」 — while the same tap below the card closed it in one hit
 * (verified: a tap on the composer at x 200 closes the card and returns the caret).
 * Without the keyboard the two boxes do not overlap at all (the card sits at the
 * viewport's center), which is why the tall phone viewport never showed this.
 *
 * The fix is the **backdrop override**: the composer's box — published by the
 * composer itself ([SessionComposerBounds], and cleared when it unmounts) and
 * handed to the scrim by the layer — reads as the backdrop *wherever the card's
 * box reaches*, so a tap on the composer closes the card exactly as it does
 * outside the card (upstream: a click outside `dialog-content` dismisses and does
 * nothing else). The card's own controls keep their taps: the field and the send
 * button consume the gesture before the scrim is ever offered it, so the
 * `/btw` field stays usable on the same short window.
 */
class BtwDesktopComposerTapV73Test {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    private fun policy() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/overlay/DialogLayerPolicy.kt")

    private fun scrim() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/overlay/DialogScrim.kt")

    private fun panel() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/btw/SessionBtwPanel.kt")

    private fun composer() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt")

    // ------------------------------------------------------- the device geometry

    /** `11_btw_open.png`: the card's own box, the one the layer reports. */
    private fun cardBounds() = Rect(443f, 124f, 922f, 259f)

    /** The composer's box in the same frame — every pixel of it dimmed by the scrim. */
    private fun composerBounds() = Rect(12f, 230f, 1353f, 334f)

    /** A tap in the overlap band, on the composer's strip *inside* the card's box. */
    private val composerTap = Offset(683f, 245f)

    @After
    fun clearPublishedComposerBox() {
        SessionComposerBounds.bounds = null
    }

    @Test
    fun theCardTheLayerReportsIsWhatSwallowedTheComposerTap() {
        // The model has to reproduce the run it explains, or the numbers below are
        // a story: the two boxes really do overlap, and the overlap is the card's.
        assertTrue(cardBounds().overlaps(composerBounds()))
        assertTrue(cardBounds().contains(composerTap))
        assertTrue(composerBounds().contains(composerTap))

        // v73 without the override: the tap the user aimed at the composer reads
        // as the card's, so the scrim closes nothing.
        assertFalse(scrimTapDismisses(cardBounds(), composerTap))
        // …and with it, that same tap is the backdrop's.
        assertTrue(scrimTapDismisses(cardBounds(), composerTap, composerBounds()))

        // The rest of the composer strip keeps working exactly as before — below
        // the card (already true in v73) and beside it.
        assertTrue(scrimTapDismisses(cardBounds(), Offset(683f, 300f), composerBounds()))
        assertTrue(scrimTapDismisses(cardBounds(), Offset(200f, 300f), composerBounds()))
    }

    @Test
    fun theCardsOwnWhitespaceStaysTheCards() {
        // The rule the override must not break: a tap inside the card, outside the
        // composer's box, is the card's own — the question header and body are
        // whitespace, not backdrop, and a dismiss there would close the panel under
        // the user's own card.
        assertFalse(scrimTapDismisses(cardBounds(), Offset(683f, 160f), composerBounds()))
        assertFalse(scrimTapDismisses(cardBounds(), Offset(683f, 160f)))
        // The card's own edge above the composer, same verdict.
        assertFalse(scrimTapDismisses(cardBounds(), Offset(443f, 228f), composerBounds()))
    }

    @Test
    fun anUnmeasuredCardStillLetsTheComposerThrough() {
        // Before the first layout the card reports nothing; a composer tap is the
        // backdrop's on the strength of the composer's own box, and with no boxes
        // at all nothing dismisses (the v71 rule).
        assertTrue(scrimTapDismisses(null, composerTap, composerBounds()))
        assertFalse(scrimTapDismisses(null, composerTap))
    }

    // --------------------------------------------------------- the state wiring

    @Test
    fun theScrimStateAsksTheSameQuestionWithTheBackdropBox() {
        val state = DialogScrimState()
        state.contentBounds = cardBounds()
        // v73's answer for the composer tap: nothing to do.
        assertFalse(state.dismisses(composerTap))

        state.backdropBounds = composerBounds()
        assertTrue(state.dismisses(composerTap))
        // The card's own whitespace is still guarded, and the composer *outside*
        // the card was already a dismissal.
        assertFalse(state.dismisses(Offset(683f, 160f)))
        assertTrue(state.dismisses(Offset(683f, 300f)))

        // The box is compared in root space, like the card's (the scrim's own
        // origin is what a local tap is offset by): the same local point flips
        // verdict with the scrim's own position.
        state.scrimOrigin = Offset(0f, 20f)
        assertTrue(state.dismisses(Offset(683f, 225f)))
        assertFalse(state.dismisses(Offset(683f, 120f)))
        assertTrue(state.dismisses(Offset(683f, 100f)))
    }

    // ------------------------------------------------------------- the wiring

    @Test
    fun theComposerPublishesItsOwnStrip() {
        val body = composer()
        // The composer's root *is* the strip on screen (`+`/model chip row
        // included), and it is the only node that knows where it is.
        assertTrue(body.contains(".onGloballyPositioned { SessionComposerBounds.bounds = it.boundsInRoot() }"))
        // Cleared on unmount, so a layer over another screen cannot read a stale box.
        assertTrue(body.contains("onDispose { SessionComposerBounds.bounds = null }"))
    }

    @Test
    fun theBtwLayerHandsTheScrimTheComposerBox() {
        val body = panel()
        assertTrue(body.contains("val composerBox = SessionComposerBounds.bounds"))
        assertTrue(body.contains("if (scrim != null) scrim.backdropBounds = composerBox"))
        // Still the card's own report that guards the card (v71) — the override is
        // an addition, not a replacement.
        assertTrue(body.contains("Modifier.dialogCardBounds(scrim)"))
    }

    @Test
    fun theDismissalTestReadsTheBackdropBoxAndStaysPure() {
        val body = policy()
        assertTrue(body.contains("backdropBounds: Rect? = null,"))
        assertTrue(body.contains("(backdropBounds != null && backdropBounds.contains(tapInRoot))"))
        assertTrue(body.contains("(contentBounds != null && !contentBounds.contains(tapInRoot))"))
        // Defaulted, so every existing caller and pin keeps its exact semantics.
        assertTrue(
            "the v71 rule is untouched when no backdrop is published",
            scrimTapDismisses(cardBounds(), composerTap).not(),
        )
        // The state calls it with all three boxes.
        assertTrue(scrim().contains("scrimTapDismisses(guardedBounds, tap + scrimOrigin, backdropBounds)"))
        assertTrue(scrim().contains("internal var backdropBounds by mutableStateOf<Rect?>(null)"))
    }
}
