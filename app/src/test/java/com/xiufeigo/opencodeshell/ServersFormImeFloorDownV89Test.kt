package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.settings.SERVER_DIALOG_FORM_MIN_DP
import com.xiufeigo.opencodeshell.ui.settings.SERVER_DIALOG_PINNED_FOOTER_DP
import com.xiufeigo.opencodeshell.ui.settings.SERVER_DIALOG_WANTED_BAND_DP
import com.xiufeigo.opencodeshell.ui.settings.serverDialogBandDp
import com.xiufeigo.opencodeshell.ui.settings.serverDialogBandLiftDp
import com.xiufeigo.opencodeshell.ui.settings.serverDialogFloorDp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v89 「键盘落下态 footer（Cancel/Save、Cancel/Add server）无像素渲染」 — the same
 * floor as v88's, taken with the keyboard **down**.
 *
 * Device evidence (`research/v88_verify/5556/`, 1366x768 @160dpi, 1 px = 1 dp — the
 * `06_edit_kbddown.png` / `ui_15_edit_kbddown.xml` pair; `05c_add_kbddown.png` is the
 * Add SSH card's, byte-identical in every number below):
 *
 * | what | measured |
 * |---|---|
 * | the panel's own box (`PanelSlot`, the a11y `ScrollView`) | y=133…635.5 (`[434,133][1173,636]`) |
 * | the `Edit server` card's flow top (the probe's border row) | y=341 |
 * | the card's drawn bottom border | **absent** — the only non-background row in y=620…700 is the settings card's own bottom border at y=683 |
 * | the footer's a11y nodes (`View`, no label) | y=643…660 — past the panel's clip, 0 rendered pixels |
 * | the hint line under the last field (a11y) | y[458,633][890,636] — a 3 px box, clipped at the panel's edge |
 * | the card's own content height (`ui_kbdown_scrolled.xml`) | 442.5 dp (y[193,635.5]) |
 *
 * v88's floor was the keyboard's top edge *only* while the keyboard was up; with it
 * down that operand is the **window's** bottom (y=768), 132.5 dp below the line the
 * panel's scroller clips at, so the card — `heightIn(max = band)` with band = 427 —
 * laid itself out y=341…768 and drew its 80.5 dp footer at y=687.5…768: outside the
 * panel, with the a11y nodes in the tree and no pixel of them on screen. Save was
 * reachable only by scrolling the panel those 132 dp (the v87 tester: 「上滑面板外层
 * 148px 后 footer 露出 y575..591 可点」) or by tapping a field to raise the keyboard —
 * the save path v88 shipped without.
 *
 * v89 ([serverDialogFloorDp]): the panel's own bottom edge is the floor in *both*
 * keyboard states, so band = 635.5 − 341 = **294.5** and the card's max-only cap
 * clamps its content to exactly that. The pinned footer then occupies y=555…635.5,
 * its 32 dp button row y=567…599, entirely inside the panel — and the panel's own
 * content is 341 − 133 + 294.5 = 502.5 dp in a 503 dp viewport, i.e. the card needs
 * **no scroll** to reach, which is the same reachability the keyboard-up state has
 * had since v88 and the whole point of the fix.
 *
 * Where the v88 card's content already fitted above the panel's bottom edge the two
 * disagree by nothing: the max-only cap draws a content-sized card, so the state the
 * tester could scroll to (`ui_kbdown_scrolled.xml`, the card at y[193,635.5] with its
 * footer on the panel's own bottom edge) is byte-identical, and so is every shorter
 * panel flow position. The keyboard-up branch — floor, band, lift, the card's box and
 * the drawn border rows `112 / 167 / 311 / 335` — is untouched (the v88 pins in
 * `ServersFormImeFloorV88Test`).
 */
class ServersFormImeFloorDownV89Test {

    /** The desktop emulator's window (`wm size` 1366x768, `wm density` 160). */
    private val windowDp = 768f

    /** `ui_15_edit_kbddown.xml`: the panel's own box tops out at y=133. */
    private val panelTopDp = 133f

    /**
     * …and ends on y=635.5: the a11y `ScrollView` reports 636 (the box's exclusive
     * edge, rounded up) and the pixel probe puts the panel's last row at 635, the
     * same half-pixel reading the v88 keyboard-up numbers use (335.5 under an a11y
     * 336). V85's `panelDownDp = 503` is this box: 635.5 − 132.5 = 503.
     */
    private val panelHeightDp = 502.5f

    /** The floor: the panel's own bottom edge. */
    private val floorDp = 635.5f

    /** The `Edit server` card's flow top in the panel's scroll content (the probes' border row 341). */
    private val editCardTopDp = 341f

    /** `ui_kbdown_scrolled.xml`: the card's own content — form scroller plus pinned footer. */
    private val cardContentDp = 442.5f

    /** The first keyboard pixel is y=342 with the keyboard *up* (`r1_edit_kbd.png`). */
    private val drawnKeyboardTopDp = 342f

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

    /** The keyboard-down band of the v89 device's Edit card. */
    private fun downBand(topDp: Float) =
        serverDialogBandDp(windowDp, 0f, topDp, panelHeightDp, panelTopDp)

    private fun downLift(bandDp: Float, topDp: Float) =
        serverDialogBandLiftDp(bandDp, windowDp, 0f, topDp, panelTopDp, panelHeightDp)

    /** What the caller's max-only cap draws: the content, clamped to the band. */
    private fun drawnHeight(bandDp: Float) = minOf(cardContentDp, bandDp)

    // ------------------------------------------------- the floor is the panel, down too

    @Test
    fun theKeyboardDownFloorIsThePanelsOwnBottomEdge() {
        assertEquals(502.5f, panelHeightDp)
        assertEquals(635.5f, floorDp)
        assertEquals(floorDp, serverDialogFloorDp(windowDp, 0f, panelTopDp, panelHeightDp))
        // The window's bottom is no longer the answer for a caller that measured the
        // panel: it is 132.5 dp below the line the panel's scroller clips at, and that
        // gap is exactly what the v88 card buried its footer in.
        assertEquals(windowDp, 768f)
        assertEquals(132.5f, windowDp - floorDp)
        // …and the empty-handed caller keeps the v87 arithmetic to the pixel.
        assertEquals(windowDp, serverDialogFloorDp(windowDp, 0f, -1f, panelHeightDp))
        assertEquals(windowDp, serverDialogFloorDp(windowDp, 0f, panelTopDp, 0f))
    }

    @Test
    fun theBandIsTheSpaceDownToThePanelSoTheCardStopsOnTheClip() {
        val band = downBand(editCardTopDp)
        assertEquals(294.5f, band)
        assertEquals(floorDp - editCardTopDp, band)
        // Nothing to lift: the flow leaves the band exactly.
        assertEquals(0f, downLift(band, editCardTopDp))
        assertEquals(editCardTopDp, editCardTopDp - downLift(band, editCardTopDp))
        // The drawn card, by the caller's own max-only cap: content clamped to band.
        val drawn = drawnHeight(band)
        assertEquals(294.5f, drawn)
        assertEquals(editCardTopDp + drawn, floorDp)
        assertEquals(floorDp, editCardTopDp + band)
        // …what v88 drew instead: the band was the window minus the flow top (427),
        // the card ran to the window's bottom and its footer sat at 687.5…768 —
        // behind the panel's scroller.
        val v88Band = windowDp - editCardTopDp
        assertEquals(427f, v88Band)
        val v88Bottom = editCardTopDp + v88Band
        assertEquals(768f, v88Bottom)
        assertEquals(687.5f, v88Bottom - SERVER_DIALOG_PINNED_FOOTER_DP)
        assertTrue(
            "v88's footer began below the panel's clip",
            v88Bottom - SERVER_DIALOG_PINNED_FOOTER_DP > floorDp,
        )
        assertEquals(52f, v88Bottom - SERVER_DIALOG_PINNED_FOOTER_DP - floorDp)
    }

    @Test
    fun thePinnedFooterAndItsButtonsAreInsideThePanelsClip() {
        val band = downBand(editCardTopDp)
        val drawnBottom = editCardTopDp + drawnHeight(band)
        // The pinned footer's own 80.5 dp, on the card's bottom edge == the panel's.
        val footerTop = drawnBottom - SERVER_DIALOG_PINNED_FOOTER_DP
        assertEquals(555f, footerTop)
        assertEquals(floorDp, drawnBottom)
        assertTrue("the footer's top is inside the panel", footerTop >= panelTopDp)
        assertEquals(panelTopDp + panelHeightDp, drawnBottom)
        // The button row: 12 dp above the hairline-side padding, a 32 dp `OcButton`.
        val buttonTop = drawnBottom - 24f - 0.5f - 12f - 32f
        val buttonBottom = drawnBottom - 24f - 0.5f - 12f
        assertEquals(567f, buttonTop)
        assertEquals(599f, buttonBottom)
        assertEquals(32f, buttonBottom - buttonTop)
        assertTrue("the whole button row is on screen", buttonTop >= panelTopDp)
        assertTrue("…and above the clip", buttonBottom <= floorDp)
        // The form keeps a whole labelled field above it (the v89 band is above the
        // 224 dp floor, so this is not the v86 squeeze).
        assertTrue(band >= SERVER_DIALOG_WANTED_BAND_DP)
        val formDp = band - SERVER_DIALOG_PINNED_FOOTER_DP
        assertEquals(214f, formDp)
        assertTrue(formDp >= SERVER_DIALOG_FORM_MIN_DP)
        // The rows v88's a11y dump reported (643…660, no pixels) are the ones this
        // fix pulls back above the clip: the footer's own box now ends *on* it.
        assertTrue("the v88 footer nodes were past the clip", 643f > floorDp)
        assertTrue(drawnBottom <= floorDp)
    }

    @Test
    fun theCardNeedsNoScrollOfThePanelToBeReached() {
        val band = downBand(editCardTopDp)
        // The panel's own scroll content: the flow above the card plus the card.
        val contentDp = (editCardTopDp - panelTopDp) + drawnHeight(band)
        assertEquals(502.5f, contentDp)
        assertEquals(panelHeightDp, contentDp)
        assertTrue("the card fits the panel it is drawn in", contentDp <= panelHeightDp)
        // v88: the same content was the card's 427 dp box, 132 dp past the viewport —
        // the scroll the tester had to perform to see Cancel/Save at all.
        val v88ContentDp = (editCardTopDp - panelTopDp) + (windowDp - editCardTopDp)
        assertEquals(635f, v88ContentDp)
        assertEquals(132.5f, v88ContentDp - panelHeightDp)
    }

    @Test
    fun aCardWhoseContentFitsIsDrawnExactlyWhereV88LeftIt() {
        // The state the tester could already reach by dragging the panel: scrolled to
        // its end the card's flow top is y=193 and its 442.5 dp of content fit above
        // the panel's bottom edge — the same box `ui_kbdown_scrolled.xml` records,
        // and the same one v88 drew there. v89 changes nothing where the footer was
        // already visible.
        val band = downBand(193f)
        assertEquals(cardContentDp, band)
        assertEquals(442.5f, drawnHeight(band))
        assertEquals(0f, downLift(band, 193f))
        assertEquals(635.5f, 193f + drawnHeight(band))
        // …and every flow position whose whole content fits above the panel's bottom edge
        // gets the identical treatment: the cap does not bind, so the drawn height *is*
        // the content and the card hangs from the flow exactly as it did in v85/v88.
        for (top in listOf(133f, 150f, 190f, 193f)) {
            val b = downBand(top)
            assertEquals("top=$top band", floorDp - top, b)
            assertEquals("top=$top drawn", cardContentDp, drawnHeight(b))
            assertEquals("top=$top lift", 0f, downLift(b, top))
            assertEquals("top=$top bottom", top + cardContentDp, top + drawnHeight(b))
            assertTrue("top=$top bottom above the floor", top + cardContentDp <= floorDp)
            assertTrue(
                "top=$top footer inside",
                top + cardContentDp - SERVER_DIALOG_PINNED_FOOTER_DP >= panelTopDp,
            )
        }
    }

    @Test
    fun everyFlowPositionKeepsTheFooterInsideThePanel() {
        // Whatever the panel's flow gives the card — its flow top, and the flow of any
        // longer server list above it — the drawn card's bottom edge never passes the
        // floor, its head never rises above the panel's own top edge, and its pinned
        // footer is always inside the panel. Where the content is taller than the band
        // the bottom edge *is* the floor; where the panel's flow leaves the card the
        // whole panel (space > panelHeight cannot happen: the card is its own last
        // child), the content-sized card ends above it, footer still inside.
        var top = panelTopDp
        while (top <= 700f) {
            val band = downBand(top)
            val lift = downLift(band, top)
            val drawnTop = top - lift
            val drawnBottom = drawnTop + drawnHeight(band)
            val footerTop = drawnBottom - SERVER_DIALOG_PINNED_FOOTER_DP
            assertTrue("top=$top band", band >= SERVER_DIALOG_PINNED_FOOTER_DP)
            assertTrue("top=$top inside the panel's top", drawnTop >= panelTopDp)
            assertTrue("top=$top footer inside the panel", footerTop >= panelTopDp)
            assertTrue("top=$top bottom not past the floor", drawnBottom <= floorDp + 0.001f)
            assertTrue("top=$top lift", lift >= 0f)
            if (band <= cardContentDp) {
                assertEquals("top=$top bottom on the floor", floorDp, drawnBottom)
            } else {
                // The space is wider than the card's content: the max-only cap does
                // not bind and the card is drawn content-sized, hanging from the flow.
                assertEquals("top=$top content-sized", cardContentDp, drawnHeight(band))
                assertEquals("top=$top no lift", 0f, lift)
            }
            top += 7f
        }
        // A flow that leaves the card less than the band (a long server list): the card
        // is drawn from the floor up — footer inside the panel — by the same lift the
        // keyboard-up state uses.
        val tight = 700f
        val band = downBand(tight)
        val lift = downLift(band, tight)
        assertEquals(SERVER_DIALOG_WANTED_BAND_DP, band)
        assertEquals(band - (floorDp - tight), lift)
        assertEquals(288.5f, lift)
        assertEquals(floorDp - band, tight - lift)
        assertEquals(411.5f, tight - lift)
        assertTrue(tight - lift >= panelTopDp)
        assertEquals(floorDp, tight - lift + band)
    }

    @Test
    fun thePanelsOwnHeightIsNotTheCapsOperandWithTheKeyboardDown() {
        // v89 leaves the band's *room* expression exactly as v87/v88 shaped it: the
        // panel's own height caps the band in the keyboard-up branch only (the branch
        // whose band *is* the card's box), and the keyboard's top edge bounds it with
        // the keyboard down — so a caller that hands in a panel shorter than the floor
        // gets the floor's own band, not the panel.
        assertEquals(
            SERVER_DIALOG_WANTED_BAND_DP,
            serverDialogBandDp(windowDp, 0f, editCardTopDp, 60f, panelTopDp),
        )
        // That is not a state a keyboard-down settings layer produces (the panel's box
        // there is the settings card's own height — 502.5 dp on the v88 device), and on
        // any panel that can hold the floor the space down to its bottom edge is at
        // most its own height for every flow position its scroller can reach, so the
        // cap cannot bind (`everyFlowPositionKeepsTheFooterInsideThePanel`). A panel too
        // short for the footer keeps v86's last floor: the band is the footer itself.
        assertEquals(
            SERVER_DIALOG_PINNED_FOOTER_DP,
            serverDialogBandDp(windowDp, 432f, editCardTopDp, 60f, panelTopDp),
        )
        // A nonsense panel (no height, no top) is "not measured": the window floor and
        // v86's band, unchanged.
        assertEquals(427f, serverDialogBandDp(windowDp, 0f, editCardTopDp, 0f, panelTopDp))
        assertEquals(427f, serverDialogBandDp(windowDp, 0f, editCardTopDp, panelHeightDp, -1f))
        // Never negative, whatever the pairing.
        for (panel in listOf(0f, 60f, 157f, panelHeightDp, 900f)) {
            for (top in listOf(0f, 133f, 341f, 800f)) {
                val band = serverDialogBandDp(windowDp, 0f, top, panel, panelTopDp)
                assertTrue("panel=$panel top=$top band=$band", band >= SERVER_DIALOG_PINNED_FOOTER_DP)
                assertTrue(
                    "panel=$panel top=$top lift",
                    serverDialogBandLiftDp(band, windowDp, 0f, top, panelTopDp, panel) >= 0f,
                )
            }
        }
    }

    // ------------------------------------------------------- the keyboard-up baseline

    @Test
    fun theKeyboardUpGeometryIsTheV88OneToThePixel() {
        // `r1_edit_kbd.png` / `01_edit_kbd.png`: the panel's box y=91…335.5, the IME
        // frame's composition read 474.5 dp, the Edit card's flow top y=267.
        val upPanelTopDp = 91f
        val upPanelHeightDp = 244.5f
        val upImeDp = 474.5f
        val upFloorDp = 335.5f
        val editTopDp = 267f
        assertEquals(upFloorDp, serverDialogFloorDp(windowDp, upImeDp, upPanelTopDp, upPanelHeightDp))
        val band = serverDialogBandDp(windowDp, upImeDp, editTopDp, upPanelHeightDp, upPanelTopDp)
        val lift = serverDialogBandLiftDp(band, windowDp, upImeDp, editTopDp, upPanelTopDp, upPanelHeightDp)
        assertEquals(SERVER_DIALOG_WANTED_BAND_DP, band)
        assertEquals(224f, band)
        assertEquals(68.5f, upFloorDp - editTopDp)
        assertEquals(155.5f, lift)
        // The card's drawn box — `[111.5, 335.5]` — and the probe's border rows
        // 112 / 167 / 311 / 335 with the 6 px gap to the first keyboard pixel (y=342)
        // that v88 shipped: the head's hairline 55.5 dp below the card's top edge, the
        // footer's hairline 24.5 dp above its bottom edge.
        val drawnTop = editTopDp - lift
        assertEquals(111.5f, drawnTop)
        assertEquals(upFloorDp, drawnTop + band)
        // The head: 24 dp padding + the 19 dp title + the 12 dp gap + the 0.5 dp hairline.
        assertEquals(166.5f, drawnTop + 24f + 19f + 12f)
        assertEquals(167f, drawnTop + 24f + 19f + 12f + 0.5f)
        assertEquals(311f, upFloorDp - 24.5f)
        assertEquals(335f, upFloorDp - 0.5f)
        assertEquals(6.5f, drawnKeyboardTopDp - upFloorDp)
        // The form keeps its floor and the footer its own height, as in v88: the button
        // row is the last 32 dp before the footer's hairline and bottom padding.
        assertEquals(SERVER_DIALOG_FORM_MIN_DP, band - SERVER_DIALOG_PINNED_FOOTER_DP)
        assertEquals(143.5f, band - SERVER_DIALOG_PINNED_FOOTER_DP)
        assertEquals(255f, upFloorDp - SERVER_DIALOG_PINNED_FOOTER_DP)
        assertEquals(267f, upFloorDp - SERVER_DIALOG_PINNED_FOOTER_DP + 12f)
        assertEquals(299f, upFloorDp - 24.5f - 12f)
        // The same keyboard state on the v86 device's screen (the panel's top edge not
        // given: the V83/V85/V87 call shape) is still the v87 arithmetic.
        assertEquals(224f, serverDialogBandDp(windowDp, 432f, 267f, 245f))
        assertEquals(155f, serverDialogBandLiftDp(224f, windowDp, 432f, 267f))
        assertEquals(112f, 267f - serverDialogBandLiftDp(224f, windowDp, 432f, 267f))
        assertEquals(336f, serverDialogBandDp(windowDp, 432f, 0f, 0f))
    }

    // --------------------------------------------------------------- where it comes from

    @Test
    fun theOneFloorFeedsBothTheBandAndTheLiftAndTheCardsFeedItTheirPanel() {
        val components = settingsComponents()
        val floor = components
            .substringAfter("internal fun serverDialogFloorDp(")
            .substringBefore("\n/**")
        // One condition for both keyboard states: the panel's own box, when the caller
        // measured it. The keyboard is only the *unmeasured* fallback.
        assertTrue(floor.contains("panelHeightDp > 0f && panelTopDp >= 0f"))
        assertTrue(floor.contains("panelTopDp + panelHeightDp"))
        assertTrue(floor.contains("windowHeightDp - imeBottomDp.coerceAtLeast(0f)"))
        for ((which, body) in listOf(
            "band" to components.substringAfter("internal fun serverDialogBandDp(").substringBefore("\n/**"),
            "lift" to components.substringAfter("internal fun serverDialogBandLiftDp(").substringBefore("\n/**"),
        )) {
            assertTrue(
                "$which: the floor comes from the one helper",
                body.contains("serverDialogFloorDp(windowHeightDp, imeBottomDp, panelTopDp, panelHeightDp)"),
            )
            assertTrue("$which: the space is measured to it", body.contains("floorDp - topInWindowDp.coerceAtLeast(0f)"))
        }
        // Both cards hand the floor that measurement, in the band *and* in the lift,
        // and the conditional fill keeps its keyboard-up source — the keyboard-down
        // branch stays the max-only cap with the form at `fill = false`.
        val condition = "imeBottomDp > 0.dp && panelHeightDp > 0f"
        for ((which, card) in cards()) {
            assertTrue("$which: the panel's top edge", card.contains("panel?.topDp ?: -1f"))
            assertTrue("$which: the band gets it", card.contains("panelTopDp = panelTopDp"))
            assertEquals(
                "$which: the condition is what both the cap and the fill read",
                2,
                Regex(Regex.escape(condition)).findAll(card).count(),
            )
            assertTrue("$which: the fixed box while the keyboard is up", card.contains("Modifier.height(band)"))
            assertTrue("$which: …with the form filling it", card.contains("Modifier.weight(1f)\n"))
            assertTrue("$which: the max-only cap otherwise", card.contains("Modifier.heightIn(max = band)"))
            assertTrue("$which: …with the form wrapping", card.contains("Modifier.weight(1f, fill = false)"))
            val offset = card.indexOf(".offset(y = -lift)")
            val fixed = card.indexOf("Modifier.height(band)")
            val capped = card.indexOf("Modifier.heightIn(max = band)")
            val background = card.indexOf(".background(c.bgBase")
            assertTrue("$which: lifted then sized", offset in 1 until fixed)
            assertTrue("$which: the cap follows it", fixed < capped && capped < background)
        }
    }
}
