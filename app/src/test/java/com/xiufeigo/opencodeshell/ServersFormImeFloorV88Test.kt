package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.settings.SERVER_DIALOG_FORM_MIN_DP
import com.xiufeigo.opencodeshell.ui.settings.SERVER_DIALOG_PINNED_FOOTER_DP
import com.xiufeigo.opencodeshell.ui.settings.SERVER_DIALOG_WANTED_BAND_DP
import com.xiufeigo.opencodeshell.ui.settings.serverDialogBandDp
import com.xiufeigo.opencodeshell.ui.settings.serverDialogBandLiftDp
import com.xiufeigo.opencodeshell.ui.settings.serverDialogFloorDp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v88 「键盘弹起时卡片只画到面板底边之上 48 px、卡头被面板裁掉」 — the second-level
 * cards' **floor**: the y their bottom edge is drawn against.
 *
 * Device evidence (`research/v87_verify/5556/`, 1366x768 @160dpi, 1 px = 1 dp; the same
 * window v83/v85/v86/v87 measured, with the keyboard up and the Edit form focused):
 *
 * | what | measured |
 * |---|---|
 * | the panel's own box (`PanelSlot`, the a11y `ScrollView`) | y=91…336 (`ui_edit_kbd.xml`) |
 * | the panel's bottom edge (the pixel probe at x=450/700/1160) | y=335.5 |
 * | the settings card itself (`BoxWithCappedSize`) | y=42…335.5 — it ends on the panel's bottom edge |
 * | the composition read of `WindowInsets.ime` | 474.5 dp (the IME *frame*) |
 * | …i.e. the keyboard's top edge as the app reads it | y=293.5 |
 * | the first keyboard pixel (the probe) | y=342 |
 * | the Edit card, drawn | y=90…293 (h=204 as drawn *inside* the panel) |
 * | its form scroller (a11y) | y[91,213] = 122 dp visible |
 * | Cancel / Save's 48 dp targets (a11y) | y[217,265] |
 *
 * The card was 224 dp — the band's floor, [SERVER_DIALOG_WANTED_BAND_DP] — drawn on the
 * **keyboard's top edge the inset reports** (293.5), i.e. at y=69.5…293.5. The panel's
 * own box starts at y=91, so the 21.5 dp above that line (the card's top border and its
 * 24 dp top padding) were clipped away by the panel's scroller: the visible form was
 * 122 dp instead of the 143.5 dp floor, and the footer's bottom sat 48 px above the
 * keyboard. The layer's own card *does* end on the panel's bottom edge (335.5, 6 px
 * above the first keyboard pixel), because the IME frame the platform reports carries a
 * transparent top band (the Gboard strip, y=293.5…341.5) that the layer's 42 dp of top
 * insets happen to cancel — the floor a card takes from the inset alone does not.
 *
 * The v88 floor is therefore the panel's **own bottom edge** (`panelTop + panelHeight`)
 * whenever the keyboard is up and the panel is measured, and the keyboard's top edge
 * otherwise ([serverDialogFloorDp]). With the band capped at the panel's own height —
 * which it already was — that gives one provable frame for every flow position the
 * panel's scroller can reach: the drawn box is `[min(flow, floor − band), floor]`, so
 * the card is always **inside** the panel's box and its bottom edge always **on** the
 * floor. Every caller that cannot say where the panel begins keeps v87's arithmetic to
 * the pixel.
 *
 * v89 (`ServersFormImeFloorDownV89Test`): "the panel is measured" is no longer
 * qualified by the keyboard being up — with it **down** the same floor is what puts
 * the pinned footer inside the panel instead of 132 dp past its clip
 * (`research/v88_verify/5556/06_edit_kbddown.png` and `ui_15_edit_kbddown.xml`). The
 * keyboard's top edge is the floor for the callers that leave [panelTopDp] at its
 * unmeasured default, which is every V83/V85/V87 pin's call shape — those keep v87's
 * numbers, and the keyboard-up branch of the band is untouched everywhere.
 */
class ServersFormImeFloorV88Test {

    /** The desktop emulator's window (`wm size` 1366x768, `wm density` 160). */
    private val windowDp = 768f

    /**
     * The composition read of `WindowInsets.ime` on that window (`r1_edit_kbd.png`):
     * the IME *frame*, 48 dp taller than the drawn keyboard.
     */
    private val imeFrameDp = 474.5f

    /** …so the keyboard's top edge, as the app reads it, is here. */
    private val keyboardTopDp = windowDp - imeFrameDp

    /** `ui_edit_kbd.xml`: the panel's own box tops out at y=91. */
    private val panelTopDp = 91f

    /** …and ends on y=335.5 (the probe; the settings card's own bottom border). */
    private val panelDp = 335.5f - panelTopDp

    /** The floor the layer's own card ends on — 6 px above the first keyboard pixel. */
    private val floorDp = panelTopDp + panelDp

    /** `r1_edit_kbd.png`: the first keyboard pixel at x=1250 is y=342. */
    private val drawnKeyboardTopDp = 342f

    /** The Edit card's flow top in the panel's scroll content (the v86 dumps' y=267). */
    private val editCardTopDp = 267f

    /** `ui_add_kbd.xml`: the Add SSH card's flow top. */
    private val sshCardTopDp = 283f

    private fun source(relative: String): String =
        listOf("app/$relative", relative)
            .map { File(it) }
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private fun settingsComponents() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/settings/SettingsComponents.kt")

    /** The Edit card, from its own function to the end of the panel file. */
    private fun editCard() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/settings/ServersPanel.kt")
            .substringAfter("private fun AddEditServerDialog(")

    /** The Add SSH card, from its band block to the end of the dialog file. */
    private fun addSshCard() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/servers/SshDialog.kt")
            .substringAfter("val busy = isSshConnecting(status.stage)")

    private fun cards() = listOf("Edit server" to editCard(), "Add SSH server" to addSshCard())

    private fun band(topDp: Float) =
        serverDialogBandDp(windowDp, imeFrameDp, topDp, panelDp, panelTopDp)

    private fun lift(bandDp: Float, topDp: Float) =
        serverDialogBandLiftDp(bandDp, windowDp, imeFrameDp, topDp, panelTopDp, panelDp)

    // --------------------------------------------------------- the floor is the panel

    @Test
    fun theFloorIsThePanelsOwnBottomEdgeWhileTheKeyboardIsUp() {
        // The panel's box, measured: its bottom edge is 6 px above the drawn
        // keyboard — where the settings layer's own card ends.
        assertEquals(244.5f, panelDp)
        assertEquals(335.5f, floorDp)
        assertEquals(floorDp, serverDialogFloorDp(windowDp, imeFrameDp, panelTopDp, panelDp))
        assertEquals(6.5f, drawnKeyboardTopDp - floorDp)
        // …and the keyboard's own top edge is the floor for every caller that has
        // none of that: no panel measured, or no panel top given (the v83–v87
        // arithmetic, kept as the documented default). The keyboard's state is
        // *not* part of the question since v89: a caller that measured the panel
        // takes the panel's bottom edge with the keyboard down too (the down-state
        // pin lives in `ServersFormImeFloorDownV89Test`).
        assertEquals(keyboardTopDp, serverDialogFloorDp(windowDp, imeFrameDp, -1f, panelDp))
        assertEquals(keyboardTopDp, serverDialogFloorDp(windowDp, imeFrameDp, panelTopDp, 0f))
        assertEquals(floorDp, serverDialogFloorDp(windowDp, 0f, panelTopDp, panelDp))
        assertEquals(windowDp, serverDialogFloorDp(windowDp, 0f, panelTopDp, 0f))
        assertEquals(windowDp, serverDialogFloorDp(windowDp, 0f, -1f, panelDp))
        assertEquals(keyboardTopDp, serverDialogFloorDp(windowDp, imeFrameDp))
    }

    @Test
    fun theV87FrameIsWhatLeftTheCardHeadInThePanelsClip() {
        // What the v87 floor drew: the band's floor (224 dp) hung off the keyboard's
        // top edge the inset reports, 21.5 dp above the panel's own top edge …
        val v87FloorDp = keyboardTopDp
        val v87TopDp = v87FloorDp - SERVER_DIALOG_WANTED_BAND_DP
        assertEquals(69.5f, v87TopDp)
        assertEquals(21.5f, panelTopDp - v87TopDp)
        // … so the panel's scroller (clipping at y=91) left 122 dp of the form:
        assertEquals(122f, v87FloorDp - panelTopDp - SERVER_DIALOG_PINNED_FOOTER_DP)
        assertTrue(v87FloorDp - panelTopDp - SERVER_DIALOG_PINNED_FOOTER_DP < SERVER_DIALOG_FORM_MIN_DP)
        // … and put the footer's bottom 48 px above the keyboard.
        assertEquals(48.5f, drawnKeyboardTopDp - v87FloorDp)
        // The same state on the panel's own bottom edge: inside the panel, the whole
        // floor of form visible, the footer back on the drawn keyboard's line.
        val band = band(editCardTopDp)
        val lift = lift(band, editCardTopDp)
        assertEquals(SERVER_DIALOG_WANTED_BAND_DP, band)
        assertEquals(floorDp - band, editCardTopDp - lift)
        assertEquals(111.5f, editCardTopDp - lift)
        assertEquals(floorDp, editCardTopDp - lift + band)
        assertTrue("the whole card is inside the panel", editCardTopDp - lift >= panelTopDp)
        assertEquals(SERVER_DIALOG_FORM_MIN_DP, band - SERVER_DIALOG_PINNED_FOOTER_DP)
        assertEquals(6.5f, drawnKeyboardTopDp - (editCardTopDp - lift + band))
        // The Add SSH card comes out in the same box, whatever its own flow top is.
        val sshBand = band(sshCardTopDp)
        assertEquals(sshBand, band)
        assertEquals(editCardTopDp - lift, sshCardTopDp - lift(sshBand, sshCardTopDp))
        assertEquals(floorDp, sshCardTopDp - lift(sshBand, sshCardTopDp) + sshBand)
    }

    // ----------------------------------------------------------- every flow position

    @Test
    fun everyFlowPositionDrawsTheCardInsideThePanelWithItsFooterOnTheFloor() {
        var top = panelTopDp
        while (top <= 420f) {
            val band = band(top)
            val lift = lift(band, top)
            val drawnTop = top - lift
            val drawnBottom = drawnTop + band
            // The one invariant: the bottom edge is the floor, and the frame is inside
            // the panel's own box — at every scroll position the panel can reach.
            assertEquals("top=$top drawn bottom", floorDp, drawnBottom)
            assertTrue("top=$top drawn top", drawnTop >= panelTopDp)
            assertTrue("top=$top drawn bottom inside", drawnBottom <= floorDp)
            // The footer's own height survives every band (the buttons are never
            // squeezed), and the form keeps the floor wherever the panel can pay it.
            assertTrue("top=$top band", band >= SERVER_DIALOG_PINNED_FOOTER_DP)
            if (panelDp >= SERVER_DIALOG_WANTED_BAND_DP) {
                assertTrue(
                    "top=$top form=${band - SERVER_DIALOG_PINNED_FOOTER_DP}",
                    band - SERVER_DIALOG_PINNED_FOOTER_DP >= SERVER_DIALOG_FORM_MIN_DP,
                )
                // …and the card's top edge absorbs the difference wherever the floor
                // needs the room: the same box for every one of those positions.
                if (floorDp - top <= SERVER_DIALOG_WANTED_BAND_DP) {
                    assertEquals(
                        "top=$top form",
                        SERVER_DIALOG_FORM_MIN_DP,
                        band - SERVER_DIALOG_PINNED_FOOTER_DP,
                    )
                    assertEquals(
                        "top=$top drawn top",
                        floorDp - SERVER_DIALOG_WANTED_BAND_DP,
                        drawnTop,
                    )
                }
            }
            assertTrue("top=$top lift", lift >= 0f)
            top += 7f
        }
    }
    @Test
    fun theThreeSpaceBandsAllComeOutInTheSameBox() {
        // space < 224 (the panel's flow leaves less than the floor): the deficit is
        // exactly the lift, and the drawn top is the floor minus the floor's band.
        for (top in listOf(111.5f + 40f, editCardTopDp, sshCardTopDp)) {
            val band = band(top)
            val space = floorDp - top
            assertTrue("top=$top space", space < SERVER_DIALOG_WANTED_BAND_DP)
            assertEquals("top=$top band", SERVER_DIALOG_WANTED_BAND_DP, band)
            assertEquals("top=$top lift", band - space, lift(band, top))
            assertEquals("top=$top drawn top", floorDp - band, top - lift(band, top))
        }
        // space == 224: nothing to lift, the card is drawn exactly where the flow
        // leaves it — the same box, continuously.
        val edge = floorDp - SERVER_DIALOG_WANTED_BAND_DP
        assertEquals(edge, 111.5f)
        assertEquals(SERVER_DIALOG_WANTED_BAND_DP, band(edge))
        assertEquals(0f, lift(band(edge), edge))
        assertEquals(floorDp, edge + band(edge))
        // space > 224: the space *is* the band (capped at the panel's own height),
        // and still nothing to lift — the card hangs from the panel's own top edge
        // and its bottom edge is still the floor.
        val roomy = panelTopDp
        assertEquals(panelDp, floorDp - roomy)
        assertEquals(panelDp, band(roomy))
        assertEquals(0f, lift(band(roomy), roomy))
        assertEquals(floorDp, roomy + band(roomy))
        assertTrue(band(roomy) >= SERVER_DIALOG_WANTED_BAND_DP)
    }

    @Test
    fun aPanelTooShortForTheFloorPaysItsButtonsAndNeverMore() {
        // The panel itself cannot hold the floor (shorter than 224 dp): the band is
        // the panel, the form shrinks — and never below zero, because the pinned
        // footer is the one floor that survives a room that cannot hold it.
        val shortPanelDp = 157f
        val shortFloorDp = panelTopDp + shortPanelDp
        val band = serverDialogBandDp(windowDp, imeFrameDp, editCardTopDp, shortPanelDp, panelTopDp)
        val lift = serverDialogBandLiftDp(band, windowDp, imeFrameDp, editCardTopDp, panelTopDp, shortPanelDp)
        assertEquals(shortPanelDp, band)
        assertEquals(76.5f, band - SERVER_DIALOG_PINNED_FOOTER_DP)
        assertTrue(band - SERVER_DIALOG_PINNED_FOOTER_DP > 0f)
        assertEquals(shortFloorDp, editCardTopDp - lift + band)
        assertTrue("still inside the panel it is drawn in", editCardTopDp - lift >= panelTopDp)
        // A panel shorter than the footer itself has nothing left to give: the band
        // is the footer, and the card is drawn with its buttons whole (the one thing
        // a second-level form must keep reachable).
        assertEquals(
            SERVER_DIALOG_PINNED_FOOTER_DP,
            serverDialogBandDp(windowDp, imeFrameDp, editCardTopDp, 60f, panelTopDp),
        )
    }

    @Test
    fun aCallerWithoutThePanelsTopEdgeKeepsTheV87KeyboardDownGeometry() {
        // Every keyboard-down state of a caller that cannot say where the panel
        // begins (the V83/V85/V87 call shape, [panelTopDp] left at its sentinel): the
        // floor is the keyboard's top edge — the window's bottom — and no lift is
        // applied, whatever panel *height* that caller hands in.
        for (top in listOf(193f, 341f, 500f)) {
            for (panel in listOf(0f, 157f, panelDp, 900f)) {
                val band = serverDialogBandDp(windowDp, 0f, top, panelHeightDp = panel)
                assertEquals(
                    "top=$top panel=$panel",
                    maxOf(windowDp - top, SERVER_DIALOG_PINNED_FOOTER_DP),
                    band,
                )
                assertEquals(
                    "top=$top panel=$panel",
                    0f,
                    serverDialogBandLiftDp(band, windowDp, 0f, top, panelHeightDp = panel),
                )
            }
        }
        // …and the v89 reading of the same state: once the panel's top edge *is*
        // given, that panel's own bottom edge is the floor in both keyboard states
        // ([serverDialogFloorDp]). The whole keyboard-down card — its band, its
        // clamped footer and its buttons — is pinned against the v88 device's own
        // panel box in `ServersFormImeFloorDownV89Test`.
        assertEquals(floorDp, serverDialogFloorDp(windowDp, 0f, panelTopDp, panelDp))
        // The v87 device's own numbers, with the panel's top edge *not* given (the
        // v85/v86/v87 test classes' call shape): identical band, lift and box.
        assertEquals(224f, serverDialogBandDp(windowDp, 432f, 267f, 245f))
        assertEquals(155f, serverDialogBandLiftDp(224f, windowDp, 432f, 267f))
        assertEquals(112f, 267f - serverDialogBandLiftDp(224f, windowDp, 432f, 267f))
        assertEquals(245f, serverDialogBandDp(windowDp, 432f, 336f - 245f, 245f))
        // …and the first frame, before the slot's box lands: the keyboard alone, no
        // lift — the safe branch that cannot zero the form.
        assertEquals(336f, serverDialogBandDp(windowDp, 432f, 0f, 0f))
        assertEquals(0f, serverDialogBandLiftDp(336f, windowDp, 432f, 0f))
        // The same first frame with the panel measured: the band is the panel's own
        // height (the space from the stale 0 down to the panel's bottom edge, capped
        // by the room), and the card's own `heightIn(max = band)` wrap clamps it to
        // the content — so the unmeasured frame draws the card content-sized.
        assertEquals(
            panelDp,
            serverDialogBandDp(windowDp, 432f, 0f, panelDp, panelTopDp),
        )
    }

    // --------------------------------------------------------------- where it comes from

    @Test
    fun theFloorIsOnePureHelperBothTheBandAndTheLiftRead() {
        val components = settingsComponents()
        val floor = components
            .substringAfter("internal fun serverDialogFloorDp(")
            .substringBefore("\n/**")
        assertTrue("the panel's own box is the floor", floor.contains("panelTopDp + panelHeightDp"))
        assertTrue(
            "the keyboard's top is the window minus the raw inset",
            floor.contains("windowHeightDp - imeBottomDp.coerceAtLeast(0f)"),
        )
        assertTrue(
            "…and it is the floor for every caller that has no panel top",
            floor.contains("panelHeightDp > 0f && panelTopDp >= 0f"),
        )
        // v89: the keyboard's state is *not* part of that question any more — the
        // panel's own box decides, in both keyboard states. The old guard is what
        // left the keyboard-down card 132 dp past the panel's clip
        // (`ServersFormImeFloorDownV89Test`).
        assertFalse(
            "the floor must not be gated on the keyboard being up",
            floor.contains("imeBottomDp > 0f &&"),
        )
        val band = components
            .substringAfter("internal fun serverDialogBandDp(")
            .substringBefore("\n/**")
        val lift = components
            .substringAfter("internal fun serverDialogBandLiftDp(")
            .substringBefore("\n/**")
        for ((which, body) in listOf("band" to band, "lift" to lift)) {
            assertTrue(
                "$which: the floor comes from the one helper",
                body.contains("serverDialogFloorDp(windowHeightDp, imeBottomDp, panelTopDp, panelHeightDp)"),
            )
            assertTrue("$which: the space is measured to it", body.contains("floorDp - topInWindowDp.coerceAtLeast(0f)"))
        }
        // The band keeps v87's floor-of-the-form arithmetic and its panel cap.
        assertTrue(band.contains("maxOf(spaceDp, SERVER_DIALOG_WANTED_BAND_DP)"))
        assertTrue(band.contains("minOf(keyboardTopDp, panelHeightDp)"))
        assertTrue(band.contains("keyboardUp && panelHeightDp > 0f"))
        assertTrue(band.contains("SERVER_DIALOG_PINNED_FOOTER_DP,"))
        // …and the lift is still the deficit, never a negative offset.
        assertTrue(lift.contains("(bandDp - spaceDp).coerceAtLeast(0f)"))
    }

    @Test
    fun bothCardsMeasureThePanelsTopEdgeAndFeedItToTheFloor() {
        for ((which, card) in cards()) {
            assertTrue("$which: the panel box", card.contains("LocalOcSelectContainerBounds.current"))
            assertTrue("$which: its top edge", card.contains("panel?.topDp ?: -1f"))
            assertTrue("$which: …and the band gets it", card.contains("panelTopDp = panelTopDp"))
            assertTrue(
                "$which: the lift is drawn against the same floor",
                Regex("serverDialogBandLiftDp\\([\\s\\S]*?panelTopDp = panelTopDp[\\s\\S]*?\\)").containsMatchIn(card),
            )
            // …the measured edge, never a hard-coded one: the sentinel is the
            // fallback for a card with no panel, not the value a measured box feeds.
            assertFalse("$which: no hard-coded panel top", card.contains("panelTopDp = 0f"))
        }
    }

    @Test
    fun theFixedBandAndTheFillAreTheKeyboardUpBranchOnly() {
        for ((which, card) in cards()) {
            // The condition is the one state the floor needs: keyboard up *and* a
            // measured panel box. The keyboard-down word keeps v87's wrap:
            // `heightIn(max = band)` with the form at `fill = false`.
            val condition = "imeBottomDp > 0.dp && panelHeightDp > 0f"
            assertTrue("$which: one condition", card.contains(condition))
            assertEquals(
                "$which: the condition is written once per box",
                2,
                Regex(Regex.escape(condition)).findAll(card).count(),
            )
            assertTrue("$which: the card's box is the band while the keyboard is up", card.contains("Modifier.height(band)"))
            assertTrue("$which: …and the form is the box above the footer", card.contains("Modifier.weight(1f)\n"))
            assertTrue("$which: the keyboard-down cap is kept", card.contains("Modifier.heightIn(max = band)"))
            assertTrue("$which: the keyboard-down wrap is kept", card.contains("Modifier.weight(1f, fill = false)"))
            // …and the fixed height is *inside* the lifted chain, under the shield
            // and before the card's surface, exactly like the cap it replaces.
            val offset = card.indexOf(".offset(y = -lift)")
            val fixed = card.indexOf("Modifier.height(band)")
            val capped = card.indexOf("Modifier.heightIn(max = band)")
            val background = card.indexOf(".background(c.bgBase")
            assertTrue("$which: lifted then sized", offset in 1 until fixed)
            assertTrue("$which: the wrap branch follows it", fixed < capped)
            assertTrue("$which: the surface comes last", capped < background)
            assertEquals("$which: one scroller", 1, Regex("verticalScroll\\(").findAll(card).count())
        }
    }
}
