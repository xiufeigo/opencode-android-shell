package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.settings.SERVER_DIALOG_MIN_BAND_DP
import com.xiufeigo.opencodeshell.ui.settings.SERVER_DIALOG_PINNED_FOOTER_DP
import com.xiufeigo.opencodeshell.ui.settings.settingsDialogCardHeightDp
import com.xiufeigo.opencodeshell.ui.settings.serverDialogBandDp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v83 「二级弹层键盘弹起时表单够不到 Save」 — the band the second-level server
 * forms may take, and the footer that must never scroll out of it.
 *
 * Device evidence (`research/v83_verify/5556/`, 1366x768 @160dpi so 1 px = 1 dp):
 * with the keyboard up (`26_edit_imeup.png`) the settings layer is 42..335 and
 * the `Edit server` card inside it is 91..335 — the v82 fix works, the nested
 * card inherits the visible band. What it did not do: the form was a single
 * scrolling column, so its Save button — y=523 with the keyboard down
 * (`29_edit_kbdown.png`), measured 517..531 in the card's 127..569 box — was
 * 197 dp below the fold of that 244 dp viewport, and the only way to it was a
 * swipe inside the band. The form is now the scroller and the footer is pinned.
 *
 * v85 (`ServersFormImeBandV85Test`) then moved the *ceiling*: the v84 panel shape
 * appends these cards to the panel's own scroll content, so the card's top edge is
 * no longer the slot's y=91 this file's arithmetic starts from — with the keyboard
 * up it is measured at y=231 (Edit) / y=227 (Add SSH), which leaves only 105 /
 * 109 dp above the keyboard and made the 160 dp floor push the pinned footer back
 * under the keyboard. [serverDialogBandDp] is the space now (the floor yields to
 * the keyboard, the footer's own height being the only floor left), so the
 * keyboard-down cases pinned below are unchanged while the keyboard-up numbers
 * here describe the roomy case only.
 */
class ServersFormImeBandV83Test {

    // ------------------------------------------------------ measured geometry

    /** The desktop emulator's window (`wm size` 1366x768, `wm density` 160). */
    private val windowDp = 768f

    /** The keyboard's top edge at y=342 → `768 − 342`. */
    private val imeDp = 426f

    /** The settings layer's top edge with the keyboard up (`26_edit_imeup.png`). */
    private val cardTopDp = 42f

    /** …and with it down (`29_edit_kbdown.png`: the layer is centred at y=78). */
    private val cardTopDownDp = 78f

    /** The Close row above the panel slot: the hairline sits at y=90 (y=126 down). */
    private val closeRowDp = 48f

    /** The `Edit server` card's top edge: `42 + 48 + 1` = the 91 measured. */
    private val dialogTopDp = cardTopDp + closeRowDp + 1f

    /** …and `78 + 48 + 1` = the 127 measured with the keyboard down. */
    private val dialogTopDownDp = cardTopDownDp + closeRowDp + 1f

    /**
     * What the pinned footer spends: the 12 dp gap above the button row, the
     * 32 dp `OcButton`, the 12 dp gap below it, the 0.5 dp hairline and the
     * card's own 24 dp bottom padding (measured on `29_edit_kbdown.png`: the row
     * is 24 dp above the card's bottom edge and the hairline 24 dp below it).
     */
    private val pinnedFooterDp = 80.5f

    /** One labelled field: the 15 dp label, the 8 dp gap and the 36 dp input. */
    private val labelledFieldDp = 59f

    private fun source(relative: String): String =
        listOf("app/$relative", relative)
            .map { File(it) }
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private fun serverPanel() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/settings/ServersPanel.kt")

    private fun sshDialog() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/servers/SshDialog.kt")

    // ------------------------------------------------------- the band's math

    @Test
    fun withTheKeyboardUpTheBandIsWhatIsLeftAboveIt() {
        // The measured state: 768 − 426 − 91.
        val band = serverDialogBandDp(
            windowHeightDp = windowDp,
            imeBottomDp = imeDp,
            topInWindowDp = dialogTopDp,
        )
        assertEquals(251f, band)
        // The form keeps two labelled fields once the footer is paid for — the
        // 244 dp the card used to give the whole column could not show one.
        assertTrue(
            "the form must keep two fields below the pinned footer: $band",
            band - pinnedFooterDp >= 2 * labelledFieldDp,
        )
        // …and the footer itself always fits, so Save is on screen with the
        // keyboard up instead of 197 dp below the fold.
        assertTrue(band >= pinnedFooterDp)
    }

    @Test
    fun withTheKeyboardDownTheBandNeverBinds() {
        val band = serverDialogBandDp(
            windowHeightDp = windowDp,
            imeBottomDp = 0f,
            topInWindowDp = dialogTopDownDp,
        )
        assertEquals(641f, band)
        // The card's own content is 442 dp (127..569 measured), so the cap is not
        // what shapes the keyboard-down geometry — it is byte-identical.
        assertTrue("keyboard-down band $band must clear the 442 dp card", band > 442f)
    }

    @Test
    fun aNonsenseInsetNeverMovesTheBandTheWrongWay() {
        val band = serverDialogBandDp(windowDp, imeDp, dialogTopDp)
        assertEquals(band, serverDialogBandDp(windowDp, imeDp, dialogTopDp))
        // A negative inset is "no keyboard"…
        assertEquals(
            serverDialogBandDp(windowDp, 0f, dialogTopDp),
            serverDialogBandDp(windowDp, -50f, dialogTopDp),
        )
        // …an inset larger than the window keeps the pinned footer's height
        // instead of a negative band (a card of no height would have no footer
        // at all). v85: the floor that survives is the footer's, not the form's
        // 160 — the space the keyboard leaves is what the band is
        // (`ServersFormImeBandV85Test` has the device arithmetic).
        assertEquals(
            SERVER_DIALOG_PINNED_FOOTER_DP,
            serverDialogBandDp(windowDp, 2000f, dialogTopDp),
        )
        assertTrue(SERVER_DIALOG_MIN_BAND_DP - pinnedFooterDp >= labelledFieldDp)
        assertEquals(pinnedFooterDp, SERVER_DIALOG_PINNED_FOOTER_DP)
    }

    @Test
    fun thePinnedFooterFitsTheShortestBandTheSettingsLayerCanProduce() {
        // `settingsDialogCardHeightDp` floors the settings layer at 240 dp, so the
        // nested card's band is at worst that minus the Close row…
        assertEquals(240f, settingsDialogCardHeightDp(windowHeightDp = 200f))
        val shortest = serverDialogBandDp(windowHeightDp = 200f, imeBottomDp = 0f, topInWindowDp = 0f)
        assertEquals(200f, shortest)
        // …and on a window too short even for the form's preferred band, the
        // window itself is the limit (v85: the card may never ask for more than
        // the space it is drawn in — that is what put the footer behind the
        // keyboard), while the pinned footer's own height is what the card keeps
        // below that.
        assertEquals(
            100f,
            serverDialogBandDp(windowHeightDp = 100f, imeBottomDp = 0f, topInWindowDp = 0f),
        )
        assertTrue(
            "a window below the footer's budget still keeps the footer",
            serverDialogBandDp(windowHeightDp = 100f, imeBottomDp = 0f, topInWindowDp = 0f) >=
                SERVER_DIALOG_PINNED_FOOTER_DP,
        )
        assertEquals(
            SERVER_DIALOG_PINNED_FOOTER_DP,
            serverDialogBandDp(windowHeightDp = 60f, imeBottomDp = 0f, topInWindowDp = 0f),
        )
        assertTrue(
            "the footer plus one labelled field must fit the shortest band: $shortest",
            shortest - pinnedFooterDp >= labelledFieldDp,
        )
        assertTrue(SERVER_DIALOG_MIN_BAND_DP - pinnedFooterDp >= labelledFieldDp)
    }

    // ------------------------------------------- the two cards that apply it

    @Test
    fun theEditServerFormScrollsWhileItsFooterStaysPinned() {
        val dialog = serverPanel().substringAfter("private fun AddEditServerDialog(")
        assertTrue("the band must come from the pure helper", dialog.contains("serverDialogBandDp("))
        assertTrue(
            "the card's own top edge must be read from the layout",
            dialog.contains("onGloballyPositioned"),
        )
        assertTrue(
            "the window's own IME bottom is what the band is measured against",
            dialog.contains("windowImeBottomDp()"),
        )
        assertTrue("the card is capped to the band", dialog.contains(".heightIn(max = band)"))
        assertTrue(
            "the form is the block that gives way",
            dialog.contains(".weight(1f, fill = false)"),
        )
        assertTrue(dialog.contains(".verticalScroll(rememberScrollState())"))
        // Exactly one scroller: the footer must not be inside it.
        assertEquals(1, Regex("verticalScroll\\(").findAll(dialog).count())
        val scroll = dialog.indexOf(".verticalScroll(rememberScrollState())")
        val footer = dialog.indexOf("R.string.oc_common_cancel")
        assertTrue("the footer must follow the scroller, not sit in it", scroll in 1 until footer)
        assertTrue(
            "the pinned footer carries the primary action",
            dialog.substring(footer).contains("oc_server_save_button"),
        )
        assertTrue(
            "the pinned footer's 12 dp gap keeps the keyboard-down geometry",
            dialog.contains(
                "PaddingValues(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 24.dp)",
            ),
        )
        // No second IME padding: the settings layer's own `imePadding` consumes the
        // inset for its descendants (Compose's `InsetsPaddingModifier` excludes what
        // an ancestor spent), so a nested one would add nothing and the card would
        // only look like it handles the keyboard.
        assertFalse(
            "the nested card must not pad the IME a second time",
            dialog.contains("imePadding"),
        )
    }

    @Test
    fun theAddSshServerFormGetsTheSameTreatment() {
        val card = sshDialog().substringAfter("val busy = isSshConnecting(status.stage)")
        assertTrue(card.contains("serverDialogBandDp("))
        assertTrue(card.contains("onGloballyPositioned"))
        assertTrue(card.contains("windowImeBottomDp()"))
        assertTrue(card.contains(".heightIn(max = band)"))
        assertTrue(card.contains(".weight(1f, fill = false)"))
        assertEquals(1, Regex("verticalScroll\\(").findAll(card).count())
        val scroll = card.indexOf(".verticalScroll(rememberScrollState())")
        val footer = card.indexOf("R.string.common_cancel")
        assertTrue("the footer must follow the scroller, not sit in it", scroll in 1 until footer)
        assertTrue(
            "the pinned footer carries the primary action",
            card.substring(footer).contains("dialog_server_add_button"),
        )
        assertTrue(
            card.contains(
                "PaddingValues(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 24.dp)",
            ),
        )
        assertFalse(card.contains("imePadding"))
    }

    @Test
    fun bothCardsAreMountedInsideThePanelSlotTheSettingsLayerBounds() {
        // The band these cards take is the settings layer's own IME-aware box: the
        // forms are mounted at the Servers panel root (a sibling of the panel body
        // inside `PanelSlot`'s Box), so the layer's height cap reaches them —
        // `SettingsDialogImeInsetV82Test` pins that cap, this pins the mounting.
        val panel = serverPanel()
        assertTrue(panel.contains("AddEditServerDialog("))
        assertTrue(panel.contains("SshDialog("))
        assertTrue(
            "the dialogs are the panel root's children, not the scroll body's",
            panel.substringAfter("fun ServersPanel(").contains("if (adding || editing != null)"),
        )
    }
}
