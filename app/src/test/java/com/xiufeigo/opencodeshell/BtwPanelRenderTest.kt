package com.xiufeigo.opencodeshell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source pins for the `/btw` side-question panel (upstream
 * `packages/app/src/session/btw/{model,panel}.ts`, v2 head `8ce629b`): the three
 * body states of `panel.tsx`, the generate wire call of `model.ts`, the
 * header/shell entry stand-in for the hidden `/btw` slash command, and the
 * intent channel the palette's `session.aside` row reaches the host through.
 *
 * Device v32 added the presentation pins at the bottom: upstream shows the
 * answer in a **centered large modal dialog** (`btw.tsx:71-75`), so the panel is
 * a layer of the app window with the safe-area insets applied — not a window
 * anchored to the entry button, which on this shell (whose session header is the
 * bottom toolbar) opened off the bottom of the display and dismissed itself on
 * the first touch outside it.
 */
class BtwPanelRenderTest {

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText()
    }

    private fun panel() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/btw/SessionBtwPanel.kt")

    private fun host() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/btw/SessionBtwHost.kt")

    private fun header() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/header/SessionHeader.kt")

    private fun app() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/App.kt")

    @Test
    fun panelKeepsUpstreamsThreeBodyStates() {
        val body = panel()
        // `panel.tsx` `Match when={pending}` — the `session-working` shimmer row.
        assertTrue(body.contains("TextShimmer("))
        assertTrue(body.contains("R.string.session_timeline_working"))
        // `Match when={error}` — the error copy + outline Retry.
        assertTrue(body.contains("R.string.oc_btw_error"))
        assertTrue(body.contains("R.string.oc_btw_retry"))
        // `Match when={answer}` — the answer renders as Markdown, 14px upstream.
        assertTrue(body.contains("OcMarkdown("))
    }

    @Test
    fun questionHeaderOnlyOffersCopyOnceAnAnswerExists() {
        val body = panel().substringAfter("question header + copy").substringBefore("body states")
        assertTrue(body.contains("R.string.oc_btw_copy"))
        assertTrue(body.contains("R.string.common_copied"))
        assertTrue("copy is gated on the answer", body.contains("if (answer.isNotEmpty())"))
        assertTrue(body.contains("ic_oc_check"))
        assertTrue(body.contains("ic_oc_copy"))
    }

    @Test
    fun thePanelCarriesTheQuestionFieldThatStandsInForTheBtwSlash() {
        val body = panel()
        assertTrue(body.contains("BtwQuestionField("))
        assertTrue(body.contains("SessionBtwModel.normalize(value)"))
        assertTrue(body.contains("onAsk(question)"))
    }

    @Test
    fun hostGeneratesThroughTheSessionGenerateRouteWithUpstreamPrompt() {
        val body = host()
        // `model.ts:70` — `prompt: [instructions, question].join("\n\n")` →
        // `POST /api/session/{id}/generate` via `ChatViewModel.generateFromContext`.
        assertTrue(body.contains("SessionBtwModel.prompt(question)"))
        assertTrue(body.contains("viewModel.generateFromContext("))
        // `model.ts:50-53` — blank input toasts `session.btw.questionRequired`.
        assertTrue(body.contains("R.string.oc_btw_question_required"))
        // Per-session slots + the `onCleanup` abandon (`model.ts:32-41`).
        assertTrue(body.contains("model.activate(sessionKey)"))
        assertTrue(body.contains("model.abandon()"))
    }

    @Test
    fun theErrorBodyNamesTheNoModelRefusalWhenTheAskerKnowsIt() {
        val body = panel()
        // Device v14: the local no-model refusal (`Reason.NO_MODEL`) adds the
        // "pick a model" line under the upstream error copy; the null/FAILED
        // cases keep upstream's single line + Retry.
        assertTrue(body.contains("reason == SessionBtwModel.Reason.NO_MODEL"))
        assertTrue(body.contains("R.string.oc_btw_no_model"))
        assertTrue(host().contains("SessionBtwModel.Reason.of(failure)"))
    }

    @Test
    fun thePaletteRowReachesTheHostThroughTheIntentChannel() {
        val body = panel()
        // The palette layer is composed above `SessionScreen`, so it cannot see
        // `LocalSessionBtwHost`: the entry collects the channel instead.
        assertTrue(body.contains("LocalSessionCommandIntents.current"))
        assertTrue(body.contains("channel.asideRequest.collect"))
        assertTrue("bare request opens the panel", body.contains("host.openPanel()"))
        assertTrue(body.contains("host.ask(question)"))
        assertTrue(body.contains("channel.consumeAside(request)"))
        // The shell supplies the action, so the palette row exists at all.
        assertTrue(app().contains("onAskAside = { question -> intents.askAside(question) }"))
    }

    @Test
    fun headerRendersTheEntryAndTheShellPublishesTheHost() {
        // v49 BUG-1: the header sizes the entry's touch target from the row
        // budget (`sessionHeaderMetrics`), so the call carries a modifier.
        assertTrue(header().contains("SessionBtwEntry(modifier ="))
        val shell = app()
        assertTrue(shell.contains("SessionBtwHost("))
        assertTrue(shell.contains("LocalSessionBtwHost provides btwHost"))
        assertTrue(shell.contains("btwHost.activate(id)"))
        // The panel is a modal layer now, so it is one of the surfaces that must
        // not follow the user into another session (`actionsHost.dismiss()`).
        assertTrue(shell.contains("btwHost.dismiss()"))
    }

    // ------------------------------------------- presentation (device v32)

    @Test
    fun thePanelIsALayerOfTheAppWindowNotAWindowAnchoredToTheEntry() {
        val body = panel()
        // The anchored pop-up opened downwards from the entry — and this shell's
        // session header is the *bottom* toolbar (`SessionScreen` stacks
        // timeline → composer → header → tab strip). So the platform clamped the
        // window back against the bottom edge: a card hugging the bottom, over
        // the composer, the header and the navigation bar, with the IME on top of
        // it (screenshots `42_btw_kbclosed`, `44_btw_bottom`, `46_btw2_sent`).
        assertFalse(
            "the panel must not open a second window",
            body.contains("import androidx.compose.ui.window.Popup"),
        )
        assertFalse(body.contains("PopupPositionProvider"))
        assertFalse(body.contains("popupPositionProvider"))
        assertFalse(
            "outside-touch dismissal is what made the answer vanish on a scroll",
            body.contains("onDismissRequest"),
        )
        // Upstream `dialog.show(...)` + `dialog.set({size:"large", centered:true})`.
        assertTrue(body.contains("LocalDialogController.current"))
        assertTrue(body.contains("controller.show(DialogSize.LARGE)"))
    }

    @Test
    fun theLayerKeepsTheCardInsideTheSafeArea() {
        val body = panel()
        // A `DialogHost` layer is a sibling of the navigation tree, so it
        // inherits none of the per-screen `windowInsetsPadding` the destinations
        // apply — it has to ask for the insets itself. `safeDrawing` includes the
        // IME, which is what lifts the card off the keyboard.
        assertTrue(body.contains("windowInsetsPadding(WindowInsets.safeDrawing)"))
        assertTrue(body.contains("import androidx.compose.foundation.layout.safeDrawing"))
        // Width is viewport-derived like the shell's other dialogs; the old
        // 320 dp was the review side panel's width, not a dialog's.
        assertTrue(body.contains("minOf(viewportWidth - 24.dp, BTW_DIALOG_MAX_WIDTH)"))
        assertFalse(body.contains("BTW_PANEL_WIDTH"))
    }

    @Test
    fun theHostsOpenFlagFollowsTheLayerHoweverItIsDismissed() {
        val body = panel()
        // The flag opens the layer (from an effect — `show` mutates the stack),
        // and the layer's removal is the single close signal: scrim tap, BACK,
        // Escape, or a `show()` that replaced it. So the entry's active tint can
        // never claim a panel that is not on screen, and a dismissed panel can
        // always be reopened.
        assertTrue(body.contains("LaunchedEffect(host.open)"))
        assertTrue(body.contains("DisposableEffect(host) { onDispose { host.dismiss() } }"))
        assertTrue("the entry button only opens", body.contains("onClick = { host.openPanel() }"))
        val host = host()
        assertTrue(host.contains("fun dismiss()"))
        assertFalse(
            "a modal panel cannot be toggled from the button behind its scrim",
            host.contains("fun toggleOpen()"),
        )
    }
}
