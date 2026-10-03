package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.settings.SERVER_DIALOG_FORM_MIN_DP
import com.xiufeigo.opencodeshell.ui.settings.SERVER_DIALOG_MIN_BAND_DP
import com.xiufeigo.opencodeshell.ui.settings.SERVER_DIALOG_PINNED_FOOTER_DP
import com.xiufeigo.opencodeshell.ui.settings.SERVER_DIALOG_WANTED_BAND_DP
import com.xiufeigo.opencodeshell.ui.settings.serverDialogBandDp
import com.xiufeigo.opencodeshell.ui.settings.serverDialogBandLiftDp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v87 「键盘弹起时表单体塌缩为 0 px 完全不渲染」 — the band's **floor**, the card's
 * own top edge as the quantity that gives way for it, and the two cards that
 * apply both.
 *
 * Device evidence (`research/v86_verify/5556/`, 1366x768 @160dpi so 1 px = 1 dp,
 * the same window v83/v85 measured). The card's top edge in the panel's flow
 * moves with the panel's scroll position, and the a11y dump's button row is the
 * only handle on it (the footer's 48 dp touch target is the visual 32 dp row
 * plus 8 dp a side, so the card's top edge is that box's top minus the footer's
 * 12 dp gap):
 *
 * | dump | the card's flow top | the space above the keyboard | v86's band (the space, floored at the footer) | what the form got |
 * |---|---|---|---|---|
 * | `u4.xml` (v85) | y=231 | 105 dp | 105 dp | 24.5 dp |
 * | `ui_edit_kbdup.xml` | y=267 | 69 dp | 80.5 dp | **0 dp** |
 * | `ui_edit_pre_type.xml` / `ui_now.xml` | y=258 | 78 dp | 80.5 dp | **0 dp** |
 * | `ui_add_kbdup.xml` | y=283 | 53 dp | 80.5 dp | **0 dp** |
 *
 * Those dumps have Cancel/Save in the tree and **no field node at all** while
 * typing still reached the address box — an invisible form (`04z_wide_kbd_up.png`,
 * `12z_panel_crop.png`, `14z_card_crop.png` show the empty card) — and the card
 * itself dropped its last 2.5…11.5 dp of footer behind the keyboard. One
 * position-dependent quantity (the card's flow top) was both the form's budget
 * and the footer's anchor, which is also bug B: opening the card from a
 * different scroll position changed how much form the user got.
 *
 * The v87 band separates the two:
 *
 *  - its **height** is floored at [SERVER_DIALOG_WANTED_BAND_DP] =
 *    [SERVER_DIALOG_PINNED_FOOTER_DP] + [SERVER_DIALOG_FORM_MIN_DP] (224 dp), so
 *    the form's scroller is never `space − footer` again — it keeps a whole
 *    labelled field whatever the panel's flow gave the card;
 *  - the **card's own top edge** is what moves for that floor
 *    ([serverDialogBandLiftDp] = the deficit, applied as a real `Modifier.offset`),
 *    which lands the card's bottom edge on the keyboard's top edge with the
 *    footer inside the panel;
 *  - the panel region's own height (`PanelSlot`'s box through
 *    `LocalOcSelectContainerBounds`: y=91…336 = 245 dp with the keyboard up) caps
 *    the band, so the lifted card can never rise above the panel it is drawn in —
 *    and the lift is the **only** thing the measured flow position still shapes,
 *    so a stale (`topInWindowDp = 0`) or a scrolled position can no longer zero
 *    the form.
 *
 * The keyboard-down state is out of all of it: `space` there is the window minus
 * the card's top edge (427 dp at `u3.xml`'s y=341), above the floor, so the band
 * and the lift are v86's by construction (`ui_kbdown_scrolled.xml` still has the
 * 442.5 dp card with its footer on the panel's bottom edge once the panel is
 * scrolled to its end).
 */
class ServersFormImeBandV87Test {

    /** The desktop emulator's window (`wm size` 1366x768, `wm density` 160). */
    private val windowDp = 768f

    /** `ui_edit_pre_type.xml`: every app node stops at y=336 → a 432 dp inset. */
    private val imeDp = 432f

    /** …so the keyboard's top edge is here. */
    private val keyboardTopDp = windowDp - imeDp

    /** `ui_edit_pre_type.xml`: the panel's own box tops out at y=91. */
    private val panelTopDp = 91f

    /** …and its box is y=91…336 — the region the card is drawn in. */
    private val panelDp = keyboardTopDp - panelTopDp

    /** `ui_edit_kbdup.xml`: the Edit card's flow top, freshly opened. */
    private val editCardTopDp = 267f

    /** `ui_edit_pre_type.xml` / `ui_now.xml`: the same card, one scroll away. */
    private val scrolledCardTopDp = 258f

    /** `u4.xml` (the v85 build): the roomiest flow top the card was seen at. */
    private val roomyCardTopDp = 231f

    /** `ui_add_kbdup.xml`: the Add SSH card's flow top. */
    private val sshCardTopDp = 283f

    /** `ui_edit_fresh_kbdown.xml`: the slot's box with the keyboard down. */
    private val panelDownDp = 503f

    /** What the v86 band (`max(space, footer)`, no floor) left the form. */
    private fun v86FormDp(cardTopDp: Float): Float =
        maxOf(keyboardTopDp - cardTopDp, SERVER_DIALOG_PINNED_FOOTER_DP) - SERVER_DIALOG_PINNED_FOOTER_DP

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

    // -------------------------------------------------- the form keeps its floor

    @Test
    fun theFormKeepsAWholeLabelledFieldWhereV86HadNone() {
        val band = serverDialogBandDp(windowDp, imeDp, editCardTopDp, panelDp)
        // The band is the form's floor plus the pinned footer, not the space: the
        // card's flow top is y=267 on the v86 device, 69 dp above the keyboard.
        assertEquals(69f, windowDp - imeDp - editCardTopDp)
        assertEquals(SERVER_DIALOG_WANTED_BAND_DP, band)
        assertEquals(224f, band)
        assertEquals(SERVER_DIALOG_FORM_MIN_DP, band - SERVER_DIALOG_PINNED_FOOTER_DP)
        // …where v86's band (the space, floored at the footer) left the form 0 dp
        // there and 24.5 dp one scroll position further up (`u4.xml`'s y=231) —
        // the a11y state with Cancel/Save and no field node at all.
        assertEquals(0f, v86FormDp(editCardTopDp))
        assertEquals(0f, v86FormDp(scrolledCardTopDp))
        assertEquals(0f, v86FormDp(sshCardTopDp))
        assertEquals(24.5f, v86FormDp(roomyCardTopDp))
        assertTrue(
            "the floor must be more than the space ever left the form",
            band - SERVER_DIALOG_PINNED_FOOTER_DP > v86FormDp(roomyCardTopDp),
        )
    }

    @Test
    fun theFormFloorIsTheCardsOwnHeadPlusOneLabelledField() {
        // The floor, from the card's own tokens at its own top edge: the form
        // column's 24 dp top padding, the 15 sp title (19 dp on device), the
        // hairline and the two `spacedBy(12.dp)` around it (24.5), the 13 sp label
        // (16 dp), another 12 dp gap and the 48 dp text field.
        val cardHead = 24f + 19f + (12f + 0.5f + 12f)
        assertEquals(67.5f, cardHead)
        val labelledField = 16f + 12f + 48f
        assertEquals(76f, labelledField)
        assertEquals(143.5f, cardHead + labelledField)
        assertEquals(SERVER_DIALOG_FORM_MIN_DP, cardHead + labelledField)
        // The device agrees: `ui_kbdown_scrolled.xml` has the card at y=193 and its
        // first `EditText` ending at y=332 — 139 dp, the 4.5 dp difference being the
        // a11y box's own inset around the input.
        val measured = 332f - 193f
        assertEquals(139f, measured)
        assertTrue("the floor must cover the measured field: $measured", measured <= SERVER_DIALOG_FORM_MIN_DP)
        // …and it is above v83's one-labelled-field account (160 − 80.5 = 79.5),
        // which measured from the *label* down and left the card's head out: a
        // 88…96 dp form body — the range that arithmetic suggests — still shows the
        // title and the label while the input itself stays 0…10 px tall.
        assertEquals(79.5f, SERVER_DIALOG_MIN_BAND_DP - SERVER_DIALOG_PINNED_FOOTER_DP)
        assertTrue(SERVER_DIALOG_FORM_MIN_DP > SERVER_DIALOG_MIN_BAND_DP - SERVER_DIALOG_PINNED_FOOTER_DP)
        assertTrue("the floor must still clear one labelled field", SERVER_DIALOG_FORM_MIN_DP >= 88f)
        assertEquals(
            SERVER_DIALOG_FORM_MIN_DP,
            SERVER_DIALOG_WANTED_BAND_DP - SERVER_DIALOG_PINNED_FOOTER_DP,
        )
    }

    @Test
    fun theBandIsNeverBelowTheFooterPlusTheFloorWhileThePanelCanPay() {
        var top = 0f
        while (top <= 400f) {
            // Every room that can hold the floor: the band is at least the floor and
            // the form keeps a whole labelled field, whatever the flow position is —
            // 0 (the measurement's first frame), 91 (the panel's own top edge) or
            // 400.
            for (room in listOf(0f, panelDp, keyboardTopDp, 900f)) {
                val band = serverDialogBandDp(windowDp, imeDp, top, room)
                assertTrue("top=$top room=$room band=$band", band >= SERVER_DIALOG_WANTED_BAND_DP)
                assertTrue(
                    "top=$top room=$room form=${band - SERVER_DIALOG_PINNED_FOOTER_DP}",
                    band - SERVER_DIALOG_PINNED_FOOTER_DP >= SERVER_DIALOG_FORM_MIN_DP,
                )
                // …and it *is* the floor wherever the space cannot pay for more: the
                // 0 dp / 24.5 dp the v86 band left.
                if (keyboardTopDp - top <= SERVER_DIALOG_WANTED_BAND_DP) {
                    assertEquals("top=$top room=$room", SERVER_DIALOG_WANTED_BAND_DP, band)
                    assertEquals(
                        "top=$top room=$room",
                        SERVER_DIALOG_FORM_MIN_DP,
                        band - SERVER_DIALOG_PINNED_FOOTER_DP,
                    )
                }
            }
            top += 25f
        }
        // A panel too short for the floor pays what it can and never below the
        // footer: the form shrinks, it does not vanish with the buttons.
        val short = serverDialogBandDp(windowDp, imeDp, editCardTopDp, 157f)
        assertEquals(157f, short)
        assertEquals(76.5f, short - SERVER_DIALOG_PINNED_FOOTER_DP)
        assertTrue(short - SERVER_DIALOG_PINNED_FOOTER_DP > 0f)
    }

    // ------------------------------------------------- the card's top gives way

    @Test
    fun theCardTopMovesByTheDeficitSoTheBottomStaysOnTheKeyboard() {
        val band = serverDialogBandDp(windowDp, imeDp, editCardTopDp, panelDp)
        val space = windowDp - imeDp - editCardTopDp
        val lift = serverDialogBandLiftDp(band, windowDp, imeDp, editCardTopDp)
        assertEquals(band - space, lift)
        assertEquals(155f, lift)
        // The card is drawn from the keyboard's top edge minus its own band …
        assertEquals(keyboardTopDp - band, editCardTopDp - lift)
        assertEquals(112f, editCardTopDp - lift)
        // …which is inside the panel it is drawn in (its top edge is y=91), with the
        // whole 80.5 dp footer above the keyboard: Cancel/Save on screen.
        assertTrue(editCardTopDp - lift >= panelTopDp)
        assertEquals(keyboardTopDp, editCardTopDp - lift + band)
        assertEquals(255.5f, keyboardTopDp - SERVER_DIALOG_PINNED_FOOTER_DP)
        // The Add SSH card's 53 dp of space is the same story with a different
        // deficit: both cards come out in the same drawn box, and the same box the
        // roomiest v85 position produced (231 − 119.5 = 112).
        val sshBand = serverDialogBandDp(windowDp, imeDp, sshCardTopDp, panelDp)
        val sshLift = serverDialogBandLiftDp(sshBand, windowDp, imeDp, sshCardTopDp)
        assertEquals(SERVER_DIALOG_WANTED_BAND_DP, sshBand)
        assertEquals(171f, sshLift)
        assertEquals(editCardTopDp - lift, sshCardTopDp - sshLift)
        assertEquals(keyboardTopDp, sshCardTopDp - sshLift + sshBand)
        assertEquals(
            112f,
            roomyCardTopDp - serverDialogBandLiftDp(
                serverDialogBandDp(windowDp, imeDp, roomyCardTopDp, panelDp),
                windowDp, imeDp, roomyCardTopDp,
            ),
        )
    }

    @Test
    fun theSpaceIsTheBandOnceTheFlowLeavesRoomForOne() {
        // 112 dp is the last flow position the space pays the floor for; at or above
        // it the band is the space and the card is drawn where the flow put it.
        assertEquals(112f, keyboardTopDp - SERVER_DIALOG_WANTED_BAND_DP)
        var top = 112f
        while (top <= 200f) {
            val band = serverDialogBandDp(windowDp, imeDp, top, panelDp)
            assertEquals("top=$top", SERVER_DIALOG_WANTED_BAND_DP, band)
            assertEquals("top=$top", top - 112f, serverDialogBandLiftDp(band, windowDp, imeDp, top))
            assertEquals("top=$top drawn bottom", keyboardTopDp, top + band - (top - 112f))
            top += 8f
        }
        top = panelTopDp
        while (top < 112f) {
            val band = serverDialogBandDp(windowDp, imeDp, top, panelDp)
            assertEquals("top=$top", keyboardTopDp - top, band)
            assertEquals("top=$top", 0f, serverDialogBandLiftDp(band, windowDp, imeDp, top))
            assertEquals("top=$top bottom", keyboardTopDp, top + band)
            // The form is at least its floor, and more wherever the space pays more:
            // this whole range is the space, which is above the floor here.
            assertTrue(
                "top=$top form=${band - SERVER_DIALOG_PINNED_FOOTER_DP}",
                band - SERVER_DIALOG_PINNED_FOOTER_DP >= SERVER_DIALOG_FORM_MIN_DP,
            )
            assertTrue("top=$top the space is above the floor", keyboardTopDp - top > SERVER_DIALOG_WANTED_BAND_DP)
            top += 4f
        }
    }

    @Test
    fun noFlowPositionCanZeroTheForm() {
        // Bug B's shape: the card's flow top moves with the panel's scroll position
        // (231 / 258 / 267 on the same device), and in v86 only that difference
        // decided how much form the user got. Every position the panel can reach now
        // draws the card in the *same* box, with the form at its floor:
        for (top in listOf(112f, 150f, roomyCardTopDp, scrolledCardTopDp, editCardTopDp, sshCardTopDp, keyboardTopDp, 400f)) {
            val band = serverDialogBandDp(windowDp, imeDp, top, panelDp)
            val lift = serverDialogBandLiftDp(band, windowDp, imeDp, top)
            assertEquals("top=$top band", SERVER_DIALOG_WANTED_BAND_DP, band)
            assertEquals("top=$top form", SERVER_DIALOG_FORM_MIN_DP, band - SERVER_DIALOG_PINNED_FOOTER_DP)
            assertEquals("top=$top drawn top", 112f, top - lift)
            assertEquals("top=$top drawn bottom", keyboardTopDp, top - lift + band)
            assertTrue("top=$top inside the panel", top - lift >= panelTopDp)
        }
        // …and the positions the flow cannot reach — above the panel's own top edge,
        // the first frame's stale 0 among them — band as the panel's height instead,
        // which caps the card's *top edge* rather than the form.
        for (top in listOf(0f, 50f, panelTopDp)) {
            val band = serverDialogBandDp(windowDp, imeDp, top, panelDp)
            assertEquals("top=$top band", panelDp, band)
            assertEquals("top=$top lift", 0f, serverDialogBandLiftDp(band, windowDp, imeDp, top))
            assertTrue("top=$top form", band - SERVER_DIALOG_PINNED_FOOTER_DP >= SERVER_DIALOG_FORM_MIN_DP)
            assertTrue("top=$top form > 0", band - SERVER_DIALOG_PINNED_FOOTER_DP > 0f)
        }
    }

    @Test
    fun theFirstFrameBeforeThePanelIsMeasuredIsSafe() {
        // `topInWindowDp` starts at 0 and the slot's box lands a frame later: the
        // band reads the keyboard alone, which is still above the floor, and no lift
        // is applied (there is nothing measured to lift for) — so the transition
        // frame cannot zero the form either.
        val firstFrame = serverDialogBandDp(windowDp, imeDp, 0f, 0f)
        assertEquals(keyboardTopDp, firstFrame)
        assertTrue(firstFrame - SERVER_DIALOG_PINNED_FOOTER_DP >= SERVER_DIALOG_FORM_MIN_DP)
        assertEquals(0f, serverDialogBandLiftDp(firstFrame, windowDp, imeDp, 0f))
        val withPanel = serverDialogBandDp(windowDp, imeDp, 0f, panelDp)
        assertEquals(panelDp, withPanel)
        assertTrue(withPanel - SERVER_DIALOG_PINNED_FOOTER_DP >= SERVER_DIALOG_FORM_MIN_DP)
        assertEquals(0f, serverDialogBandLiftDp(withPanel, windowDp, imeDp, 0f))
    }

    @Test
    fun theKeyboardDownGeometryIsTheV86One() {
        // `u3.xml`: the card's top edge is y=341 and the band is the window minus it
        // — 427 dp, exactly the value the v86 formula produced, and the one the
        // 442.5 dp card (`ui_kbdown_scrolled.xml`) is capped by, as before.
        val band = serverDialogBandDp(windowDp, 0f, 341f, panelDownDp)
        assertEquals(427f, band)
        assertEquals(maxOf(windowDp - 341f, SERVER_DIALOG_PINNED_FOOTER_DP), band)
        assertEquals(0f, serverDialogBandLiftDp(band, windowDp, 0f, 341f))
        // Scrolled to the panel's end the card is at y=193 and the band is 575 —
        // also unchanged, also no lift.
        assertEquals(575f, serverDialogBandDp(windowDp, 0f, 193f, panelDownDp))
        assertEquals(0f, serverDialogBandLiftDp(575f, windowDp, 0f, 193f))
        // The panel region is not the cap while the keyboard is down: the card is
        // meant to run past its clip there (the panel scrolls to reach the footer),
        // so the keyboard-down band is v86's for *any* measured panel height.
        for (room in listOf(0f, 157f, panelDownDp, 900f)) {
            assertEquals(
                "room=$room",
                427f,
                serverDialogBandDp(windowDp, 0f, 341f, room),
            )
        }
    }

    @Test
    fun aNonsenseInsetLeavesTheFooterAndNothingNegative() {
        // The keyboard ate the window: no room at all, and a card shorter than its
        // own footer is the one thing never drawn (the 32 dp buttons would be
        // squeezed rather than hidden) — v86's last floor, unchanged.
        assertEquals(
            SERVER_DIALOG_PINNED_FOOTER_DP,
            serverDialogBandDp(windowDp, 2000f, editCardTopDp, panelDp),
        )
        assertEquals(
            SERVER_DIALOG_PINNED_FOOTER_DP,
            serverDialogBandDp(windowDp, 2000f, editCardTopDp, 0f),
        )
        // A negative inset is "no keyboard", and no band or lift is ever negative.
        assertEquals(
            serverDialogBandDp(windowDp, 0f, editCardTopDp, panelDp),
            serverDialogBandDp(windowDp, -50f, editCardTopDp, panelDp),
        )
        for (ime in listOf(-50f, 0f, 100f, 432f, 900f)) {
            for (top in listOf(0f, 200f, 400f)) {
                val band = serverDialogBandDp(windowDp, ime, top, panelDp)
                assertTrue("ime=$ime top=$top band=$band", band >= SERVER_DIALOG_PINNED_FOOTER_DP)
                assertTrue(
                    "ime=$ime top=$top lift",
                    serverDialogBandLiftDp(band, windowDp, ime, top) >= 0f,
                )
            }
        }
    }

    // ------------------------------------------------------------ where it comes from

    @Test
    fun theFloorAndTheLiftComeFromTheOnePureHelper() {
        val components = settingsComponents()
        assertTrue(components.contains("internal const val SERVER_DIALOG_FORM_MIN_DP = 143.5f"))
        assertTrue(
            components.contains(
                "SERVER_DIALOG_WANTED_BAND_DP =\n" +
                    "    SERVER_DIALOG_PINNED_FOOTER_DP + SERVER_DIALOG_FORM_MIN_DP",
            ),
        )
        val band = components
            .substringAfter("internal fun serverDialogBandDp(")
            .substringBefore("\n/**")
        assertTrue(
            "the floor is what the space can only add to",
            band.contains("maxOf(spaceDp, SERVER_DIALOG_WANTED_BAND_DP)"),
        )
        assertTrue("the panel region caps the band", band.contains("minOf(keyboardTopDp, panelHeightDp)"))
        assertTrue("the footer's own height is the last floor", band.contains("SERVER_DIALOG_PINNED_FOOTER_DP,"))
        assertTrue("only the keyboard-up branch is capped", band.contains("keyboardUp && panelHeightDp > 0f"))
        val lift = components
            .substringAfter("internal fun serverDialogBandLiftDp(")
            .substringBefore("\n/**")
        assertTrue(lift.contains("(bandDp - spaceDp).coerceAtLeast(0f)"))
        // v88: the keyboard's top edge — the window minus the raw inset — moved into
        // [serverDialogFloorDp], the one floor both the band and the lift are drawn
        // against; the lift reads it rather than recomputing it.
        assertTrue("the lift's floor comes from the one helper", lift.contains("serverDialogFloorDp("))
        val floor = components
            .substringAfter("internal fun serverDialogFloorDp(")
            .substringBefore("\n/**")
        assertTrue(
            "the keyboard's top is the window minus the raw inset",
            floor.contains("windowHeightDp - imeBottomDp.coerceAtLeast(0f)"),
        )
    }

    @Test
    fun bothCardsFeedTheFloorTheLiftAndTheirOwnPanel() {
        for ((which, card) in cards()) {
            assertTrue("$which: the band", card.contains("serverDialogBandDp("))
            assertTrue("$which: the lift", card.contains("serverDialogBandLiftDp("))
            assertTrue("$which: the panel region caps the band", card.contains("panelHeightDp = panelHeightDp"))
            assertTrue(
                "$which: …from the slot's own box",
                card.contains("LocalOcSelectContainerBounds.current") &&
                    card.contains("it.bottomDp - it.topDp"),
            )
            assertTrue("$which: the keyboard", card.contains("windowImeBottomDp()"))
            assertTrue("$which: the card is capped to the band", card.contains(".heightIn(max = band)"))
            assertTrue("$which: the form is the block that gives way", card.contains(".weight(1f, fill = false)"))
        }
    }

    @Test
    fun theFlowPositionIsMeasuredOutsideTheLift() {
        // The one thing a stale (or scrolled) position could still do is ask for the
        // wrong lift, so the measurement has to read the position the panel's flow
        // gives the card: it sits **before** the offset in the chain, and the card
        // carries no other one.
        for ((which, card) in cards()) {
            val measured = card.indexOf(".onGloballyPositioned")
            val offset = card.indexOf(".offset(y = -lift)")
            val capped = card.indexOf(".heightIn(max = band)")
            val background = card.indexOf(".background(c.bgBase")
            assertTrue("$which: the chain must be measured then lifted", measured in 1 until offset)
            assertTrue("$which: the band caps the lifted card", offset < capped)
            assertTrue("$which: the card's surface comes last", capped < background)
            assertTrue(
                "$which: the measured position is the flow one",
                card.contains("topInWindowDp = coords.positionInWindow().y / density.density"),
            )
            assertEquals("$which: exactly one measurement", 1, Regex("\\.onGloballyPositioned").findAll(card).count())
            assertEquals("$which: exactly one lift", 1, Regex("\\.offset\\(").findAll(card).count())
        }
    }

    @Test
    fun theFooterStaysTheCardsLastChildUnderTheCap() {
        for ((which, card) in cards()) {
            // The scroller (the form) is the only one, and the footer follows it —
            // the card's own last child, so the card's bottom edge is the footer's.
            assertEquals("$which: one scroller", 1, Regex("verticalScroll\\(").findAll(card).count())
            val scroll = card.indexOf(".verticalScroll(rememberScrollState())")
            val footer = card.indexOf("top = 12.dp, bottom = 24.dp)")
            assertTrue("$which: the footer must follow the scroller", scroll in 1 until footer)
            assertTrue(
                "$which: the pinned footer's own 80.5 dp",
                card.contains("PaddingValues(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 24.dp)"),
            )
        }
    }

    @Test
    fun theLiftedCardSwallowsOnlyWhatNothingInsideItWants() {
        val components = settingsComponents()
        val shield = components
            .substringAfter("internal fun Modifier.serverDialogLiftShield()")
            .substringBefore("\n/**")
        assertTrue(shield.contains("pointerInput(Unit)"))
        assertTrue(
            "the shield must consume the events that reach the card itself",
            shield.contains("awaitPointerEvent().changes.forEach { it.consume() }"),
        )
        // …and both cards apply it **only while lifted**, so the keyboard-down panel
        // keeps receiving a drag over the card exactly as before.
        for ((which, card) in cards()) {
            assertTrue(
                "$which: the shield is the lifted branch only",
                card.contains(".then(if (lift > 0.dp) Modifier.serverDialogLiftShield() else Modifier)"),
            )
            assertTrue(
                "$which: the shield wraps the card's own surface, under the cap",
                card.indexOf(".offset(y = -lift)") < card.indexOf("serverDialogLiftShield()"),
            )
            assertFalse(
                "$which: a clickable shield would add a target of its own",
                card.contains("serverDialogLiftShield().clickable"),
            )
        }
    }
}
