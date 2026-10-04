package com.xiufeigo.opencodeshell

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xiufeigo.opencodeshell.ui.ShellChromeBudget
import com.xiufeigo.opencodeshell.ui.session.SessionPaneLayout
import com.xiufeigo.opencodeshell.ui.session.composer.ComposerDrafts
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v74 P0, the three short-window composer defects the 5554 run reported at
 * **720x1280 @420dpi** (2.625 px/dp, a 274x487 dp window — the window the v58
 * acceptance measured):
 *
 * 1. 「消息列表塌成细条」 — the composer kept its **stacked** form on a 162.7 dp
 *    pane, so the timeline was left with the [SessionPaneLayout.TIMELINE_MIN_CONTENT]
 *    floor and its newest row under the composer's opaque rectangle (the empty
 *    state, centred in the pane, ended up half-covered by that edge);
 * 2. 「模型 chip 点不开」 — the chip's open state is local `remember`, and the
 *    picker's own focus change (the keyboard going away) rebuilds the composer,
 *    so the tap was lost while `/model` — whose request lives outside the
 *    composer — kept working;
 * 3. 「发送后 composer 不清空」 — the leave-flush read a `rememberUpdatedState`
 *    mirror of the editor text, which lags a composition: a composer rebuilt in
 *    the same frame as the send flushed the just-sent text back into the draft
 *    and the rebuilt editor re-seeded itself from it.
 *
 * The numbers below are the v58 table for this window (`ShortWindowTimelinePadV58Test`):
 * no IME body 325.7 dp, IME-up pane 162.7 dp, one-row composer 58.1 dp.
 */
class ComposerShortWindowV74Test {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private val sessionPaneLayout =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionPaneLayout.kt")

    private val sessionScreen =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")

    private val composer =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt")

    private val modelChip =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/ModelChip.kt")

    /** The pane the keyboard leaves on the reported window (v58). */
    private val measuredPane = 162.7.dp

    /** The composer that window measured in its one-row form (v58). */
    private val measuredComposer = 58.1.dp

    /** The stacked (compact) composer the form decision wrongly kept there. */
    private val stackedComposer = SessionPaneLayout.COMPOSER_COMPACT_HEIGHT

    private val gap = 12.dp

    private fun topStep(viewport: Dp, composerHeight: Dp): Dp =
        SessionPaneLayout.timelineTopPadding(viewport, composerHeight)

    // ------------------------------------------- 1. the composer's form (v74)

    @Test
    fun theReportedWindowTakesTheOneRowComposer() {
        // The display height of this window is 487 dp — *above* the shell's
        // 480 dp short-window line — while the body the composer has to fit in
        // is 162.7 dp, i.e. 13 dp short of the 164 dp the stacked composer, the
        // chrome band and a message row need together.
        assertFalse(ShellChromeBudget.isShortWindow(487))
        assertTrue(SessionPaneLayout.composerOneRow(windowIsShort = false, body = measuredPane))
        // The window term still decides on its own (the v57 measurement).
        assertTrue(SessionPaneLayout.composerOneRow(windowIsShort = true, body = 900.dp))
        // …and a body that can pay for the stacked form keeps it.
        assertFalse(SessionPaneLayout.composerOneRow(windowIsShort = false, body = 900.dp))
        // The boundary is the stacked form's own budget.
        assertTrue(SessionPaneLayout.composerOneRow(windowIsShort = false, body = 163.dp))
        assertFalse(SessionPaneLayout.composerOneRow(windowIsShort = false, body = 164.dp))
        // v154: 0 dp is the room that can least carry the stacked form, so it
        // takes the one row. This used to be the *other* way round — `available > 0.dp`
        // turned the one-row form off at exactly 0 dp, so a taller keyboard grew
        // the composer back into the full 116 dp one with nothing to put it in
        // (`research/v97_fix/REVERSE_JUMP_FIX.md`). The 163/164 boundary above is
        // the staircase and is unchanged.
        assertTrue(
            "0 dp takes the cheapest form, never the full one — the form must not grow as the " +
                "keyboard grows",
            SessionPaneLayout.composerOneRow(windowIsShort = false, body = 0.dp),
        )
    }

    @Test
    fun theOneRowComposerIsWhatKeepsTheReportedWindowABand() {
        // The one-row composer leaves the whole pane to the list, minus its own
        // height and the 12 dp gap — and the band is clear of the composer.
        for (oneRow in listOf(SessionPaneLayout.COMPOSER_MINIMAL_HEIGHT, measuredComposer)) {
            assertFalse(
                "the composer must float over a $measuredPane dp pane",
                SessionPaneLayout.composerSharesViewport(measuredPane, oneRow),
            )
            val band = SessionPaneLayout.visibleItemBand(measuredPane, oneRow, gap)
            assertTrue(
                "one-row composer $oneRow: band $band",
                band >= SessionPaneLayout.TIMELINE_MIN_CONTENT,
            )
            // The band's bottom edge (`top + band`) is above the composer's top
            // edge (`viewport − composer`).
            assertTrue(
                "one-row composer $oneRow: band $band runs under it",
                topStep(measuredPane, oneRow) + band <= measuredPane - oneRow,
            )
        }
        // The stacked form on the same pane is the reported frame: what a reader
        // sees is the floor *minus* the strip the composer covers.
        val stacked = SessionPaneLayout.visibleItemBand(measuredPane, stackedComposer, gap)
        assertTrue("stacked composer $stackedComposer: band $stacked", stacked < SessionPaneLayout.TIMELINE_MIN_CONTENT)
        assertTrue(
            "the band's size is not what a reader sees",
            SessionPaneLayout.timelineItemBand(measuredPane, stackedComposer, gap) > stacked,
        )
        // …which is the overlap the old reading hid: the reserved band's bottom
        // edge sits under the composer's opaque rectangle.
        assertTrue(
            topStep(measuredPane, stackedComposer) +
                SessionPaneLayout.timelineItemBand(measuredPane, stackedComposer, gap) >
                measuredPane - stackedComposer,
        )
    }

    @Test
    fun theVisibleBandNeverCountsAreaTheComposerCovers() {
        // A fine sweep over the window where the reserve is clamped: the band's
        // bottom edge (`viewport − top − band`) may never cross the composer's
        // top edge (`viewport − composer`).
        for (viewport in listOf(100.dp, 120.dp, 162.7.dp, 192.dp, 200.dp, 300.dp, 500.dp, 900.dp)) {
            for (composerDp in 56..220) {
                val composerHeight = composerDp.dp
                val band = SessionPaneLayout.visibleItemBand(viewport, composerHeight, gap)
                assertTrue(
                    "viewport $viewport, composer $composerHeight: band $band",
                    band >= 0.dp,
                )
                assertTrue(
                    "viewport $viewport, composer $composerHeight: band $band is past the pane",
                    band <= viewport,
                )
                if (SessionPaneLayout.composerSharesViewport(viewport, composerHeight)) continue
                val bandBottom = topStep(viewport, composerHeight) + band
                assertTrue(
                    "viewport $viewport, composer $composerHeight: band $band is covered",
                    bandBottom <= viewport - composerHeight,
                )
            }
        }
    }

    @Test
    fun theEmptyStateSitsInTheBandNotInThePane() {
        // The reported frame: 「Build anything」 is centred in the pane, which in
        // the floating form is under the composer's opaque rectangle as soon as
        // the pane is shorter than twice the composer.
        for (composer in listOf(SessionPaneLayout.COMPOSER_MINIMAL_HEIGHT, measuredComposer, stackedComposer)) {
            if (SessionPaneLayout.composerSharesViewport(measuredPane, composer)) continue
            val shift = SessionPaneLayout.emptyStateShift(
                shared = false,
                viewport = measuredPane,
                composerHeight = composer,
                gap = gap,
            )
            val top = topStep(measuredPane, composer)
            val bottom = SessionPaneLayout.timelineContentBottom(measuredPane, composer, gap)
            val centre = measuredPane / 2 + shift
            assertEquals(
                "composer $composer: the empty state must sit in the band's centre",
                (top + (measuredPane - bottom)) / 2,
                centre,
            )
            // …so a one-line (20 sp) label fits inside the band, which the
            // budget keeps at TIMELINE_MIN_CONTENT or more.
            val band = measuredPane - top - bottom
            assertTrue("composer $composer: band $band", band >= SessionPaneLayout.TIMELINE_MIN_CONTENT)
            assertTrue("composer $composer: label $centre cut", centre - 10.dp >= top)
            assertTrue("composer $composer: label $centre covered", centre + 10.dp <= measuredPane - composer)
        }
        // The shared form centres in the timeline box, which is the band itself.
        assertEquals(
            0.dp,
            SessionPaneLayout.emptyStateShift(true, measuredPane, stackedComposer, gap),
        )
        assertEquals(0.dp, SessionPaneLayout.emptyStateShift(false, 0.dp, stackedComposer, gap))
    }

    @Test
    fun theFormIsDecidedFromTheBodyAndTheWindowNotThePane() {
        val flat = sessionScreen.replace(Regex("\\s+"), " ")
        assertTrue(
            "the body's own budget must be part of the decision",
            flat.contains(
                "val bodyOneRow = SessionPaneLayout.composerOneRow( " +
                    "ShellChromeBudget.isShortWindow(windowHeightDp), bodyHeightDp, )",
            ),
        )
        // v77 P0: the body is the pane box minus the bottom padding the pane
        // applies — the same post-inset number the earlier rounds read as
        // `maxHeight`, now named (the box itself has to include the keyboard's
        // claim for the pane to be able to judge it).
        assertTrue(
            "the body is the box with the keyboard out of it",
            flat.contains(
                "val bodyHeightDp = (maxHeight - paneBottomDp).coerceAtLeast(0.dp)",
            ),
        )
        assertTrue(
            "the body reading must reach the composer",
            flat.contains("if (bodyOneRow != minimalComposer) minimalComposer = bodyOneRow"),
        )
        assertTrue(
            "the measured pane may not decide the form",
            !sessionScreen.contains("composerMinimal(paneHeight)") &&
                !sessionScreen.contains("composerOneRow(" + "\n" + "            paneHeight"),
        )
        // The pure helper lives beside the constants it derives from.
        assertTrue(
            sessionPaneLayout.contains("fun composerOneRow(windowIsShort: Boolean, body: Dp): Boolean ="),
        )
    }

    // --------------------------------------- 2. the chip's open request (v74)

    @Test
    fun theChipTapRidesTheHostsRequestCounter() {
        // The chip reports its own tap up…
        assertTrue(modelChip.contains("onRequestOpen: (() -> Unit)? = null"))
        assertTrue(modelChip.contains("onRequestOpen?.invoke()"))
        // …the composer forwards it to the host…
        assertTrue(composer.contains("onModelPickerRequest: () -> Unit = {}"))
        assertTrue(composer.contains("onRequestOpen = onModelPickerRequest,"))
        // …and the host raises the same counter `/model` does, which the chip
        // re-reads on every mount (`LaunchedEffect(openRequest)`).
        //
        // v75 P2: 重读的语义收紧了 —— 只有**新值**才开面板。v74 的"任何重建都重开"
        // 让用户点过一次之后，任何重建都会再弹一次（点 composer 文本区就会）。
        // v74 要保证的那件事改由 `Composer` 的 movable 槽位承担：形态翻转是**移动**，
        // 面板根本不会被卸载（`ComposerModelChipV75Test` 钉住这条）。
        val flat = sessionScreen.replace(Regex("\\s+"), " ")
        assertTrue(flat.contains("onModelPickerRequest = { modelChooseRequest += 1 },"))
        assertTrue(flat.contains("externalModelOpen = modelChooseRequest,"))
        assertTrue(
            modelChip.replace(Regex("\\s+"), " ").contains(
                "var seenRequest by remember { mutableStateOf(openRequest) } " +
                    "LaunchedEffect(openRequest) { " +
                    "if (modelOpenRequestArrived(seenRequest, openRequest)) openPicker() " +
                    "seenRequest = openRequest }",
            ),
        )
    }

    // ------------------------------------------ 3. the send's clear (v74)

    @Test
    fun theLeaveFlushReadsTheEditorsLiveValue() {
        val flat = composer.replace(Regex("\\s+"), " ")
        assertTrue(
            "the flush must see the clear the send just wrote",
            flat.contains("ComposerDrafts.flush(draftKey, boundKey, value.text)"),
        )
        assertFalse(
            "a composition-lagged mirror is exactly what resurrected the text",
            flat.contains("ComposerDrafts.flush(draftKey, boundKey, latestText)"),
        )
        assertTrue(
            "the composer must know the key it is currently bound to",
            composer.contains("rememberUpdatedState(draftKey)"),
        )
    }

    @Test
    fun aSentPromptIsNotResurrectedByTheRebuiltEditor() {
        // The store contract the rebuilt composer depends on: the init reads the
        // draft, so a send that cleared it comes back empty…
        ComposerDrafts.reset()
        ComposerDrafts.set("ses_v74", "hello")
        ComposerDrafts.clear("ses_v74")
        assertEquals("", ComposerDrafts.text("ses_v74"))
        // …and a blank flush (what the live read now produces) can never put the
        // old text back.
        assertFalse(ComposerDrafts.flush("ses_v74", "ses_v74", ""))
        assertEquals("", ComposerDrafts.text("ses_v74"))
        assertTrue(ComposerDrafts.state.value.isEmpty())
    }

    @Before
    fun reset() {
        ComposerDrafts.reset()
    }
}
