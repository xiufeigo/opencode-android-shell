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
 * v85 「二级弹层键盘弹起时 Save 只露 6 px / Add server 只露 10 px」 — the band's
 * **ceiling**, the two cards that apply it, and the ime value they feed it.
 *
 * Device evidence (`research/v85_verify/5556/`, 1366x768 @160dpi so 1 px = 1 dp:
 * `19_edit_kbd_up.png`, `19z_footer_zoom.png`, `24_edit_kbd_up_repro.png`,
 * `27_addssh_kbd_up.png`, plus the `u4.xml` (Edit) and `u5.xml` (Add SSH)
 * hierarchy dumps of that exact state):
 *
 * | what | Edit server | Add SSH server |
 * |---|---|---|
 * | the card's top edge (its form scroller) | y=231 (`u4.xml`) | y=227 (`u5.xml`) |
 * | the card's height | 160 dp (the v83 floor) | 160 dp (the same floor) |
 * | the keyboard's top edge | y=336 (a 432 dp inset) | y=336 |
 * | the space left to the card | 105 dp | 109 dp |
 * | the pinned footer's visible slice | 25.5 dp (labels: 6 px) | 29.5 dp (labels: 10 px) |
 *
 * The v83 band was `max(space, 160)`, and the v84 panel shape appends these cards
 * to the panel's **own scroll content** (header, list, then the card), so the
 * card's top edge is no longer the slot's y=91 that the v83 arithmetic assumed:
 * the space fell *below* the 160 dp floor, the floor won, and the card's bottom
 * edge ended 55 dp behind the keyboard — the last 55 dp of that card being
 * exactly the pinned footer the v83 fix had just moved there (`u4.xml`: the 48 dp
 * touch targets y[314,360] around a 32 dp row at y=322, `Cancel` clipped to
 * y[330,336]). The band is the space now, so the card's bottom edge lands **on**
 * the keyboard's top edge, where its footer is on screen.
 *
 * The ime input is pinned here too, because the fix is *not* a change of source:
 * the cards read `WindowInsets.ime.getBottom` at composition level, which is the
 * platform's own `WindowInsetsCompat.getInsets(IME).bottom` (Compose 1.7.6
 * `AndroidWindowInsets.getBottom` — `insets.bottom` of the compat value the
 * window dispatched, with the exclusion an ancestor's inset padding performs
 * belonging to *modifier* reads through `ModifierLocalConsumedWindowInsets`).
 * The 105 dp above is that read's own proof: it was computed with 432 fed in, not
 * with a consumed 0 (a 0 would have made the band `768 − 0 − 231 = 537`, the card
 * its full ~375 dp of content, and the footer 55 dp *lower* still).
 *
 * v87 (`ServersFormImeBandV87Test`, `research/v86_verify/5556/`) is what the
 * v85 band got wrong one level down: making the band the *space* kept the footer
 * on screen and left the **form** `space − footer` — 24.5 dp at this file's
 * 105 dp measurement and 0 dp at the 86 dp one the same device produced one
 * scroll position lower (`ui_edit_pre_type.xml` / `ui_edit_kbdup.xml` /
 * `ui_now.xml`: Cancel/Save in the a11y tree, **no field node at all**). The
 * arithmetic below is therefore the band **floored** at
 * [SERVER_DIALOG_WANTED_BAND_DP] (the pinned footer plus
 * [SERVER_DIALOG_FORM_MIN_DP], one labelled field measured from the card's own
 * top edge) and the card's **own top edge** is what gives way for it
 * (`serverDialogBandLiftDp`), so the card's bottom edge still lands on the
 * keyboard's top edge and the form is never squeezed. The panel region's own
 * height (y=91…336 = 245 dp with the keyboard up, `PanelSlot`'s box) caps the
 * band, so the lifted card can never rise above the panel it is drawn in.
 */
class ServersFormImeBandV85Test {

    /** The desktop emulator's window (`wm size` 1366x768, `wm density` 160). */
    private val windowDp = 768f

    /** The keyboard's frame (`u4.xml`/`u5.xml`: every app node stops at y=336). */
    private val imeDp = 432f

    /** …so the keyboard's top edge is here. */
    private val keyboardTopDp = windowDp - imeDp

    /** `u4.xml`: the Edit card's top edge (where its form scroller starts). */
    private val editCardTopDp = 231f

    /** `u5.xml`: the Add SSH card's top edge. */
    private val sshCardTopDp = 227f

    /**
     * The panel region the card is drawn in — `PanelSlot`'s own box, y=91…336
     * with the keyboard up (`ui_edit_pre_type.xml`'s ScrollView), reported to the
     * card through `LocalOcSelectContainerBounds`.
     */
    private val panelDp = 245f

    /**
     * …and with the keyboard down (`ui_edit_fresh_kbdown.xml` / `u3.xml`): the
     * slot's box is y=133…636, 503 dp.
     */
    private val panelDownDp = 503f

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

    // -------------------------------------------- the band stops at the keyboard

    @Test
    fun theEditCardEndsOnTheKeyboardInsteadOfBehindIt() {
        // The panel's flow gives the card 105 dp above the keyboard at y=231 …
        val space = windowDp - imeDp - editCardTopDp
        assertEquals(105f, space)
        // … which cannot pay for the footer *and* the form, so the band is the
        // form's floor plus the footer (v87) …
        val band = serverDialogBandDp(windowDp, imeDp, editCardTopDp, panelDp)
        assertEquals(SERVER_DIALOG_WANTED_BAND_DP, band)
        assertEquals(224f, band)
        assertTrue("the floor must clear the v83 preferred band", band >= SERVER_DIALOG_MIN_BAND_DP)
        // … and the card's own top edge is what moves for it: the lift is exactly
        // the deficit, so the card's bottom edge is still the keyboard's top edge…
        val lift = serverDialogBandLiftDp(band, windowDp, imeDp, editCardTopDp)
        assertEquals(band - space, lift)
        assertEquals(119f, lift)
        assertEquals(keyboardTopDp, editCardTopDp - lift + band)
        // …with the card drawn from y=112, inside the panel (its top edge is 91).
        assertEquals(keyboardTopDp - band, editCardTopDp - lift)
        assertEquals(112f, editCardTopDp - lift)
        assertTrue("the lifted card must stay inside the panel", editCardTopDp - lift > keyboardTopDp - panelDp)
        // The form's scroller is the floor — never the 24.5 dp the space left over
        // (the v86 `ui_edit_pre_type.xml` state, which had no field node at all).
        assertEquals(SERVER_DIALOG_FORM_MIN_DP, band - SERVER_DIALOG_PINNED_FOOTER_DP)
        assertEquals(24.5f, space - SERVER_DIALOG_PINNED_FOOTER_DP)
        assertTrue(
            "the form must keep a whole labelled field: ${band - SERVER_DIALOG_PINNED_FOOTER_DP}",
            band - SERVER_DIALOG_PINNED_FOOTER_DP >= 88f,
        )
        // …and the pinned footer always fits, so Save is on screen with the
        // keyboard up instead of 197 dp below the fold.
        assertTrue(band >= SERVER_DIALOG_PINNED_FOOTER_DP)
    }

    @Test
    fun theAddSshCardEndsOnTheKeyboardToo() {
        val space = windowDp - imeDp - sshCardTopDp
        assertEquals(109f, space)
        val band = serverDialogBandDp(windowDp, imeDp, sshCardTopDp, panelDp)
        assertEquals(SERVER_DIALOG_WANTED_BAND_DP, band)
        val lift = serverDialogBandLiftDp(band, windowDp, imeDp, sshCardTopDp)
        assertEquals(band - space, lift)
        assertEquals(115f, lift)
        assertEquals(keyboardTopDp, sshCardTopDp - lift + band)
        assertEquals(SERVER_DIALOG_FORM_MIN_DP, band - SERVER_DIALOG_PINNED_FOOTER_DP)
        // Both cards come out in the *same* drawn box whatever the flow above them
        // gives each one (v87): the card's own top edge absorbs the difference.
        assertEquals(
            editCardTopDp - serverDialogBandLiftDp(band, windowDp, imeDp, editCardTopDp),
            sshCardTopDp - lift,
        )
        assertEquals(112f, sshCardTopDp - lift)
    }

    @Test
    fun theV83FloorIsWhatPutTheFooterBehindTheKeyboard() {
        // What the floor produced: 160 dp into a 105 dp space, so the card's
        // bottom edge fell 55 dp behind the keyboard …
        val floored = maxOf(windowDp - imeDp - editCardTopDp, SERVER_DIALOG_MIN_BAND_DP)
        assertEquals(160f, floored)
        val flooredBottom = editCardTopDp + floored
        assertEquals(391f, flooredBottom)
        assertEquals(55f, flooredBottom - keyboardTopDp)
        // …which cuts the pinned footer (the card's last 80.5 dp) down to its top
        // 25.5 dp: the 32 dp button row keeps 13.5 dp and its 16 dp label 6 px —
        // exactly what `u4.xml` shows (`Cancel` at y[330,336], touch targets
        // y[314,360]) and the 6 px / 10 px the tester reported for the pair.
        val footerTop = flooredBottom - SERVER_DIALOG_PINNED_FOOTER_DP
        assertEquals(310.5f, footerTop)
        assertEquals(25.5f, keyboardTopDp - footerTop)
        assertEquals(13.5f, keyboardTopDp - (footerTop + 12f))
        // …and the floor must not be paid for out of the space any more: the band
        // is now above what the flow leaves, which is exactly why the card's own
        // top edge has to move (v87). The old "the space wins" reading is dead:
        // `ui_now.xml` shows where it left the form (a 0 dp scroller).
        assertTrue(
            "the floor must exceed the space: ${serverDialogBandDp(windowDp, imeDp, editCardTopDp, panelDp)}",
            serverDialogBandDp(windowDp, imeDp, editCardTopDp, panelDp) > windowDp - imeDp - editCardTopDp,
        )
        assertTrue(
            "the card's top edge is what moves",
            serverDialogBandLiftDp(
                serverDialogBandDp(windowDp, imeDp, editCardTopDp, panelDp),
                windowDp, imeDp, editCardTopDp,
            ) > 0f,
        )
        // …while the v85 invariant is kept: the drawn bottom edge is the keyboard's.
        val band = serverDialogBandDp(windowDp, imeDp, editCardTopDp, panelDp)
        val lift = serverDialogBandLiftDp(band, windowDp, imeDp, editCardTopDp)
        assertEquals(keyboardTopDp, editCardTopDp - lift + band)
    }

    @Test
    fun theSpaceIsTheBandWhileItCanPayForOne() {
        // 112 dp below the window's top edge is the last card top edge the space
        // still pays the floor for: `keyboard 336 − 224`.
        assertEquals(112f, keyboardTopDp - SERVER_DIALOG_WANTED_BAND_DP)
        var top = 112f
        while (top <= 200f) {
            val band = serverDialogBandDp(windowDp, imeDp, top, panelDp)
            val lift = serverDialogBandLiftDp(band, windowDp, imeDp, top)
            assertEquals("top=$top band", SERVER_DIALOG_WANTED_BAND_DP, band)
            // The deficit, exactly: the drawn top is 112 for every one of them.
            assertEquals("top=$top lift", top - 112f, lift)
            assertEquals("top=$top drawn top", 112f, top - lift)
            assertEquals("top=$top drawn bottom", keyboardTopDp, top - lift + band)
            // The form keeps the floor and never less.
            assertEquals("top=$top form", SERVER_DIALOG_FORM_MIN_DP, band - SERVER_DIALOG_PINNED_FOOTER_DP)
            top += 8f
        }
        // …and above that edge (the panel's own top is 91 with the keyboard up) the
        // space *is* the band and there is nothing to lift: the card is drawn where
        // the panel's flow puts it, which is the v85/v86 keyboard-down shape too.
        top = 91f
        while (top < 112f) {
            val band = serverDialogBandDp(windowDp, imeDp, top, panelDp)
            val lift = serverDialogBandLiftDp(band, windowDp, imeDp, top)
            assertEquals("top=$top band", keyboardTopDp - top, band)
            assertEquals("top=$top lift", 0f, lift)
            assertEquals("top=$top bottom", keyboardTopDp, top + band)
            assertEquals("top=$top form", keyboardTopDp - top - SERVER_DIALOG_PINNED_FOOTER_DP, band - SERVER_DIALOG_PINNED_FOOTER_DP)
            top += 4f
        }
        // …and the panel region caps it: the card is never taller than the box it
        // is drawn in, so its top edge can never rise above the panel's.
        assertEquals(panelDp, serverDialogBandDp(windowDp, imeDp, 91f, panelDp))
        assertEquals(0f, serverDialogBandLiftDp(panelDp, windowDp, imeDp, 91f))
    }

    @Test
    fun theKeyboardDownGeometryIsUntouched() {
        // `u3.xml` (the same panel, keyboard down): the card's top edge is y=341
        // and the band is the rest of the window (427) — it does not bind, because
        // the card's own content is 442.5 dp (`ui_kbdown_scrolled.xml`: the card at
        // y[193,635.5] with its footer on the panel's bottom edge once the panel is
        // scrolled to its end) …
        val band = serverDialogBandDp(windowDp, 0f, 341f, panelDownDp)
        assertEquals(427f, band)
        // …and that is byte-for-byte the value the v86 formula produced (the floor
        // only binds where the space is under 224 dp), so nothing about the
        // keyboard-down geometry — `heightIn(max = band)` included — moves.
        assertEquals(maxOf(427f, SERVER_DIALOG_PINNED_FOOTER_DP), band)
        assertEquals(
            maxOf(windowDp - 0f - 341f, SERVER_DIALOG_PINNED_FOOTER_DP),
            band,
        )
        // …and the card is *not* lifted there: the v85/v86 geometry, to the pixel.
        assertEquals(0f, serverDialogBandLiftDp(band, windowDp, 0f, 341f))
        // The panel's own box with the keyboard down is y=133…636 (503 dp): bigger
        // than the band, so the cap does not bind and the card scrolls in the panel
        // exactly as before.
        assertEquals(503f, panelDownDp)
        assertTrue(band < panelDownDp)
    }

    // ------------------------------------------------- where the ime comes from

    @Test
    fun bothCardsTakeTheWindowsOwnImeBottomForTheBand() {
        for (card in listOf(editCard(), addSshCard())) {
            assertTrue("the band must come from the pure helper", card.contains("serverDialogBandDp("))
            assertTrue(
                "the keyboard's own height is the band's ime input",
                card.contains("imeBottomDp = imeBottomDp.value"),
            )
            assertTrue(
                "…read through the one window-inset helper",
                card.contains("val imeBottomDp = windowImeBottomDp()"),
            )
            assertTrue(
                "the window height is the display height the layer measures in",
                card.contains("windowHeightDp = config.screenHeightDp.toFloat()"),
            )
            assertTrue(
                "the card's own top edge comes from the layout",
                card.contains("topInWindowDp = topInWindowDp") &&
                    card.contains("positionInWindow().y"),
            )
            assertTrue("the card is capped to the band", card.contains(".heightIn(max = band)"))
            // The shapes that *are* consumption-aware must not feed the band: a
            // nested inset padding adds 0 for a descendant
            // (`ModifierLocalConsumedWindowInsets` excludes what an ancestor
            // spent), and that 0 — 537 dp of band into a 105 dp space — is
            // precisely the failure this class pins against.
            assertFalse("no nested inset padding may feed the band", card.contains("imePadding"))
            assertFalse(
                "the raw inset read lives in one place, not in each card",
                card.contains("WindowInsets.ime.getBottom"),
            )
            // v87: the panel region the card is drawn in bounds the band too, and
            // it comes from the slot's own box rather than from a second
            // measurement taken inside the card.
            assertTrue(
                "the panel region must be the slot's own reported box",
                card.contains("LocalOcSelectContainerBounds.current") &&
                    card.contains("panelHeightDp = panelHeightDp"),
            )
        }
    }

    @Test
    fun theReadHelperIsTheWindowsOwnPlatformInset() {
        val helper = settingsComponents()
            .substringAfter("internal fun windowImeBottomDp()")
            .substringBefore("\n\n")
        assertTrue(
            "the helper must read the composition-level window inset: $helper",
            helper.contains("WindowInsets.ime.getBottom(this)"),
        )
        // Compose 1.7.6's `AndroidWindowInsets.getBottom` is `insets.bottom` of the
        // `WindowInsetsCompat` the window dispatched — the exclusion an ancestor's
        // inset padding performs never reaches this value — so the helper is the
        // raw keyboard height (432 dp in the state above), and it is the same read
        // the settings layer's own IME-aware height uses.
        assertTrue(
            "the helper must be the composition-level read of the window's ime",
            helper.contains("toDp()"),
        )
    }
}
