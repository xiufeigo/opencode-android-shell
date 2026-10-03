package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.settings.SERVER_DIALOG_PINNED_FOOTER_DP
import com.xiufeigo.opencodeshell.ui.settings.SERVER_DIALOG_WANTED_BAND_DP
import com.xiufeigo.opencodeshell.ui.settings.serverDialogBandDp
import com.xiufeigo.opencodeshell.ui.settings.serverDialogBandLiftDp
import com.xiufeigo.opencodeshell.ui.settings.settingsModalOverlayMaxDp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v92 「设置层两张短窗卡片（SSH 认证 prompt / `SettingsModalOverlay`）的键盘
 * 遮挡」 — the same band + scroller treatment the server forms got in
 * v83…v89, without restructuring either card's call sites.
 *
 *  1. `SshAuthenticate` used to be one scrolling column (title … buttons), so
 *     on a short window with the keyboard up its Cancel / Trust / Continue row
 *     sat below the fold of the visible band — and its host (`SshDialog`)
 *     rendered it through an early `return` *before* the dialog's
 *     band/floor measurement, so it had no band at all. It now has the form
 *     card's own treatment: the body scrolls, the footer is pinned at the
 *     card's bottom edge outside the scroller, and the host feeds it the same
 *     geometry inputs the form branch measures (window height, the window's
 *     own IME bottom, the panel slot's box).
 *  2. `SettingsModalOverlay` used to cap at a fixed 480 dp with the whole card
 *     (including the call site's own buttons, which live inside the generic
 *     `content()` slot) as one scroller. The cap is now the visible band above
 *     the keyboard — `min(480, window − ime)` — so the scroller can always
 *     reach those buttons. No per-call-site footer restructuring.
 *
 * Arithmetic below pins the 1366x768 @160dpi desktop (1 px = 1 dp, the
 * keyboard's top edge at y=342 with it up) the v83…v89 suites measure in.
 */
class SettingsModalImeBandV92Test {

    /** The desktop emulator's window (`wm size` 1366x768, `wm density` 160). */
    private val windowDp = 768f

    /** `768 − 342`: the keyboard's own height in the v83 (`29_edit_kbdown`) state. */
    private val imeDp = 426f

    /**
     * The v88 keyboard-up state (`r1_edit_kbd.png` / `01_edit_kbd.png`, pinned by
     * `ServersFormImeFloorDownV89Test.theKeyboardUpGeometryIsTheV88OneToThePixel`):
     * the IME frame's composition read is 474.5 dp and the panel slot's box is
     * y=91…335.5. The prompt card replaces the form card in the same slot, so
     * its band is measured against the same box.
     *
     * **v127 复核：记录性 fixture（IME *frame* 轴，含 48.5 的 Gboard 透明顶带），
     * 不是 session 判据轴的 `426`，也不该改成 `426`。** 判别标准同 v126：
     * 该值**从不**进入 `believedImeBottomPx(...)`，只喂 `serverDialog*Dp(...)`。
     * 改它会把本类逐位钉住的 `band 224f` / `lift 155.5` / `floor 335.5f` 集体位移 48.5。
     * 详见 `research/v97_fix/RESIDUAL_LEDGER.md` §2。
     */
    private val upImeDp = 474.5f
    private val upPanelTopDp = 91f
    private val upPanelHeightDp = 244.5f
    private val upFloorDp = 335.5f

    /** The prompt card's flow top, beside the form's (the same appended card). */
    private val promptTopDp = 267f

    /** The panel slot's box with the keyboard down (the v89 `06_edit_kbddown` box). */
    private val panelTopDp = 133f
    private val panelHeightDp = 502.5f

    private fun source(relative: String): String =
        listOf("app/$relative", relative)
            .map { File(it) }
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private fun authenticate() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/servers/SshAuthenticate.kt")

    private fun dialog() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/servers/SshDialog.kt")

    private fun screen() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/settings/SettingsScreen.kt")

    private fun overlay(): String {
        val body = screen()
        val start = body.indexOf("internal fun SettingsModalOverlay(")
        assertTrue("SettingsModalOverlay must exist", start >= 0)
        val end = body.indexOf("/** Small circular accent button", start)
        assertTrue("missing the footer after the overlay", end > start)
        return body.substring(start, end)
    }

    private fun promptCard(): String {
        val body = authenticate()
        val start = body.indexOf("fun SshAuthenticate(")
        assertTrue("SshAuthenticate must exist", start >= 0)
        return body.substring(start)
    }

    // ------------------------------------------------- the modal overlay's cap

    @Test
    fun withTheKeyboardDownTheOverlayCapIsThe480ItAlwaysWas() {
        assertEquals(480f, settingsModalOverlayMaxDp(windowDp, 0f))
        assertEquals(480f, settingsModalOverlayMaxDp(windowDp))
        // …on every window, tall or short: the cap is fixed, it is the content
        // that binds, so the keyboard-down card is byte-identical.
        for (window in listOf(200f, 400f, 768f, 1400f)) {
            assertEquals("window=$window", 480f, settingsModalOverlayMaxDp(window, 0f))
        }
        // A nonsense inset is "no keyboard", like the band helpers' guards.
        assertEquals(
            settingsModalOverlayMaxDp(windowDp, 0f),
            settingsModalOverlayMaxDp(windowDp, -50f),
        )
    }

    @Test
    fun withTheKeyboardUpTheOverlayCapIsTheVisibleBand() {
        // The measured state: 768 − 426 = 342 < 480, so the card is capped to
        // the 342 dp above the keyboard instead of 480 into it.
        assertEquals(342f, settingsModalOverlayMaxDp(windowDp, imeDp))
        // A band that still clears 480 keeps the old cap — nothing moves there.
        assertEquals(480f, settingsModalOverlayMaxDp(1000f, 426f))
        assertEquals(480f, settingsModalOverlayMaxDp(windowDp, 200f))
        assertEquals(480f, settingsModalOverlayMaxDp(windowDp, 288f))
        // …and one that does not binds exactly: the card's bottom edge lands on
        // the keyboard's top edge.
        assertEquals(287f, settingsModalOverlayMaxDp(windowDp, 481f))
        // An inset that ate the window leaves no room, not a negative cap.
        assertEquals(0f, settingsModalOverlayMaxDp(windowDp, 2000f))
        for (ime in listOf(0f, 120f, 200f, 426f, 700f)) {
            val cap = settingsModalOverlayMaxDp(windowDp, ime)
            assertTrue("ime=$ime: $cap", cap <= 480f)
            assertTrue("ime=$ime: $cap", cap <= windowDp - ime || cap == 0f)
        }
    }

    @Test
    fun theOverlayKeepsItsWholeCardScrollerAndItsScrim() {
        val card = overlay()
        // The generic scroller stays: the call sites' own buttons live inside
        // `content()`, so pinning a footer here would mean restructuring every
        // one of them — out of scope by design.
        assertEquals(1, Regex("verticalScroll\\(").findAll(card).count())
        assertTrue(card.contains(".verticalScroll(rememberScrollState())"))
        assertTrue(card.contains("content()"))
        assertFalse("the overlay must not pad the IME a second time", card.contains("imePadding"))
        assertFalse(
            "the overlay reads the window inset for its cap, not a nested one",
            card.contains("consumeWindowInsets"),
        )
        // The scrim layer is untouched: still centred, still dismissible, still
        // reporting its bounds.
        assertTrue(card.contains("Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center)"))
        assertTrue(card.contains("DialogScrim(scrim, onDismiss)"))
        assertTrue(card.contains(".dialogCardBounds(scrim)"))
        // …and the cap is the helper, not the old fixed literal.
        assertTrue(card.contains("settingsModalOverlayMaxDp("))
        assertTrue(card.contains("WindowInsets.ime.getBottom(this)"))
        assertTrue(card.contains(".heightIn(max = overlayMaxDp)"))
        assertFalse("the fixed 480 literal must not remain a height cap", card.contains(".heightIn(max = 480.dp)"))
    }

    // ------------------------------------------- the prompt card's band math

    @Test
    fun withTheKeyboardUpThePromptBandIsTheFormsOne() {
        // The v88 keyboard-up state the down suite pins (floor = the panel's
        // own bottom edge, not the keyboard's top edge): the prompt replaces
        // the form in the same slot, so its band is the same 224 dp floor.
        val band = serverDialogBandDp(windowDp, upImeDp, promptTopDp, upPanelHeightDp, upPanelTopDp)
        assertEquals(SERVER_DIALOG_WANTED_BAND_DP, band)
        assertEquals(224f, band)
        val lift = serverDialogBandLiftDp(band, windowDp, upImeDp, promptTopDp, upPanelTopDp, upPanelHeightDp)
        assertEquals(band - (upFloorDp - promptTopDp), lift)
        assertEquals(155.5f, lift)
        // The drawn bottom edge lands on the floor — the panel's own bottom
        // edge — with the pinned footer inside it.
        assertEquals(upFloorDp, promptTopDp - lift + band)
        assertTrue(band >= SERVER_DIALOG_PINNED_FOOTER_DP)
    }

    @Test
    fun theDesktopKeyboardUpStateMatchesTheFormCallShape() {
        // The brief's 1366x768 reading (top y=267, ime 426, panel slot
        // 133…635.5): the space to the panel's bottom edge (368.5 dp) clears
        // the 224 dp floor, so the band is the room above the keyboard (342) —
        // above the floor, like any roomy flow position, and exactly what the
        // form branch takes for the same inputs. Nothing to lift: the flow
        // leaves the band.
        val band = serverDialogBandDp(windowDp, imeDp, promptTopDp, panelHeightDp, panelTopDp)
        assertEquals(342f, band)
        assertEquals(
            band,
            serverDialogBandDp(windowDp, imeDp, promptTopDp, panelHeightDp, panelTopDp),
        )
        assertEquals(0f, serverDialogBandLiftDp(band, windowDp, imeDp, promptTopDp, panelTopDp, panelHeightDp))
    }

    @Test
    fun withTheKeyboardDownThePromptBandNeverBinds() {
        // The prompt's own content (a head, a field at most, the footer) is far
        // below the 442.5 dp the config form measures: the max-only cap cannot
        // shape it, so the drawn card is byte-identical.
        val band = serverDialogBandDp(windowDp, 0f, promptTopDp, panelHeightDp, panelTopDp)
        assertEquals(panelTopDp + panelHeightDp - promptTopDp, band)
        assertEquals(368.5f, band)
        assertEquals(0f, serverDialogBandLiftDp(band, windowDp, 0f, promptTopDp, panelTopDp, panelHeightDp))
    }

    // ---------------------------------- the prompt card applies it like a form

    @Test
    fun thePromptBodyScrollsWhileItsFooterStaysPinned() {
        val card = promptCard()
        assertTrue("the band must come from the pure helper", card.contains("serverDialogBandDp("))
        assertTrue("…and the lift from its own", card.contains("serverDialogBandLiftDp("))
        assertTrue(
            "the window's own IME bottom is what the band is measured against",
            card.contains("imeBottomDp: Dp"),
        )
        assertTrue(
            "the card's own top edge must be read from the layout",
            card.contains("onGloballyPositioned"),
        )
        assertTrue(
            "the measured position is the flow one",
            card.contains("topInWindowDp = coords.positionInWindow().y / density.density"),
        )
        // Exactly one scroller: the footer must not be inside it.
        assertEquals(1, Regex("verticalScroll\\(").findAll(card).count())
        assertTrue(card.contains(".verticalScroll(rememberScrollState())"))
        val scroll = card.indexOf(".verticalScroll(rememberScrollState())")
        val footer = card.indexOf("R.string.common_cancel")
        assertTrue("the footer must follow the scroller, not sit in it", scroll in 1 until footer)
        assertTrue(
            "the pinned footer carries the primary action",
            card.substring(footer).contains("ssh_trust"),
        )
        assertTrue(
            "…and the continue branch",
            card.substring(footer).contains("ssh_continue"),
        )
        assertTrue(
            "the pinned footer's box keeps the form's 12/24 dp geometry",
            card.contains(
                "PaddingValues(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 24.dp)",
            ),
        )
        // The prompt half re-uses the form's existing strings only.
        assertFalse("no new copy", card.contains("oc_ssh_authenticate_"))
        // No second IME padding: the settings layer's own `imePadding` consumes
        // the inset for its descendants, so a nested one would add nothing.
        assertFalse(
            "the prompt card must not pad the IME a second time",
            card.contains("imePadding"),
        )
    }

    @Test
    fun thePromptCardKeepsTheFormsCapAndFillShape() {
        val card = promptCard()
        val condition = "imeBottomDp > 0.dp && panelHeightDp > 0f"
        assertEquals(
            "the condition is what both the cap and the fill read",
            2,
            Regex(Regex.escape(condition)).findAll(card).count(),
        )
        assertTrue("the fixed box while the keyboard is up", card.contains("Modifier.height(band)"))
        assertTrue("…with the body filling it", card.contains("Modifier.weight(1f)\n"))
        assertTrue("the max-only cap otherwise", card.contains("Modifier.heightIn(max = band)"))
        assertTrue("…with the body wrapping", card.contains("Modifier.weight(1f, fill = false)"))
        assertTrue(
            "the shield is the lifted branch only",
            card.contains(".then(if (lift > 0.dp) Modifier.serverDialogLiftShield() else Modifier)"),
        )
        val offset = card.indexOf(".offset(y = -lift)")
        val fixed = card.indexOf("Modifier.height(band)")
        val capped = card.indexOf("Modifier.heightIn(max = band)")
        val background = card.indexOf(".background(c.bgBase")
        assertTrue("lifted then sized", offset in 1 until fixed)
        assertTrue("the wrap branch follows the fixed box", fixed < capped)
        assertTrue("the surface comes last", capped < background)
    }

    @Test
    fun theHostFeedsThePromptBranchTheFormsOwnInputs() {
        // The prompt used to return *before* any band measurement: the branch
        // now measures the window IME bottom and the panel slot's box first
        // and hands them to the card, and the card's signature carries them.
        val body = dialog()
        val dialogStart = body.indexOf("fun SshDialog(")
        assertTrue("SshDialog must exist", dialogStart >= 0)
        val busyAt = body.indexOf("val busy = isSshConnecting(status.stage)", dialogStart)
        assertTrue("the form branch must exist", busyAt > dialogStart)
        val promptAt = body.indexOf("val prompt = status.prompt", dialogStart)
        assertTrue("the prompt branch must exist", promptAt in (dialogStart + 1) until busyAt)
        // The measurement precedes the prompt read, so the returned card has
        // geometry — the early `return` no longer skips it.
        val measureAt = body.indexOf("val promptImeBottomDp = windowImeBottomDp()", dialogStart)
        assertTrue("the prompt branch measures the window IME bottom", measureAt in (dialogStart + 1) until promptAt)
        assertTrue(body.indexOf("LocalOcSelectContainerBounds.current", dialogStart) in (dialogStart + 1) until promptAt)
        assertTrue(body.indexOf("promptPanel?.topDp ?: -1f", dialogStart) in (dialogStart + 1) until promptAt)
        val promptBlock = body.substring(promptAt, busyAt)
        assertTrue(promptBlock.contains("SshAuthenticate("))
        assertTrue(promptBlock.contains("windowHeightDp = promptWindowHeightDp"))
        assertTrue(promptBlock.contains("imeBottomDp = promptImeBottomDp"))
        assertTrue(promptBlock.contains("panelTopDp = promptPanelTopDp"))
        assertTrue(promptBlock.contains("panelHeightDp = promptPanelHeightDp"))
        // The measurement precedes the early return, so the returned card has
        // geometry — and the form branch below is untouched (its own block
        // still feeds the same names).
        val card = promptCard()
        assertTrue(card.contains("windowHeightDp: Float"))
        assertTrue(card.contains("panelTopDp: Float = -1f"))
        assertTrue(card.contains("panelHeightDp: Float = 0f"))
        val form = body.substringAfter("val busy = isSshConnecting(status.stage)")
        assertTrue(form.contains("windowHeightDp = config.screenHeightDp.toFloat()"))
        assertTrue(form.contains("panelTopDp = panelTopDp"))
        assertTrue(form.contains("serverDialogBandDp("))
        // The pure hosts both suites exercise still agree on this state.
        val roomy = serverDialogBandDp(windowDp, imeDp, promptTopDp, panelHeightDp, panelTopDp)
        assertEquals(342f, roomy)
        assertTrue(
            "the band the host feeds the prompt is the band the form takes",
            roomy == serverDialogBandDp(windowDp, imeDp, promptTopDp, panelHeightDp, panelTopDp),
        )
        assertEquals(
            SERVER_DIALOG_WANTED_BAND_DP,
            serverDialogBandDp(windowDp, upImeDp, promptTopDp, upPanelHeightDp, upPanelTopDp),
        )
    }
}
