package com.xiufeigo.opencodeshell.ui.session.btw

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xiufeigo.opencodeshell.ChatViewModel
import com.xiufeigo.opencodeshell.R

/**
 * The behaviour half of the `/btw` side-question panel — upstream
 * `packages/app/src/session/btw/model.ts` (v2 head `8ce629b`) `createSessionBtw`
 * plus the glue the web app gets from its runtime.
 *
 * **Upstream source of record for the semantics** (v32): the `session/btw`
 * files cited throughout this package are from a *later* head than the checkout
 * in `_upstream/` — the only `/btw` implementation present there is the TUI
 * plugin `packages/tui/src/feature-plugins/prompt/btw.tsx` (the web-app
 * `session/btw/{model,panel}.tsx` do not exist in it). That plugin is the
 * authority for what `/btw` **is**, and it agrees with the citations: a
 * one-shot side question, `client.session.generate({ sessionID, prompt })`
 * (`btw.tsx:68-69`) — the route the server documents as "Generate transient
 * text from the current session context **without mutating session history**"
 * (`packages/protocol/src/groups/session.ts:705-721`) — shown in a **centered
 * large modal dialog** (`btw.tsx:71-75`) and never appended to the transcript.
 * So a `/btw` Q&A is absent from `GET /api/session/{id}/message` **by design**,
 * and losing it on reload is an intentional deviation from "everything in the
 * session is durable", not a gap in this shell. What the shell keeps *beyond*
 * upstream is the answer per session key for the life of the process
 * ([SessionBtwModel]'s slots), so a dismiss/reopen does not throw it away.
 *
 * Why a host object instead of parameters on `SessionHeader`: same reason as
 * [com.xiufeigo.opencodeshell.ui.session.actions.SessionActionsHost] —
 * `SessionScreen` (owned elsewhere) calls the header with a fixed argument list
 * and only the shell (`ui/App.kt`) holds both the view model and the route, so
 * this object is built there and published as [LocalSessionBtwHost]. A null host
 * (preview, legacy chat tab) simply means no entry button.
 *
 * Wire contract: the ask runs `POST /api/session/{id}/generate`
 * (`ChatViewModel.generateFromContext` → `V2Api.generate`) with
 * [SessionBtwModel.prompt] — transient text, the conversation is never mutated.
 * The route carries no model, so the composer's current pick is pinned on the
 * session first (`POST /api/session/{id}/model`), and an ask that finds no model
 * at all is refused locally with [SessionBtwModel.Reason.NO_MODEL] instead of
 * being sent to answer 503 (device v14). Upstream's entry is the hidden `/btw`
 * slash command
 * (`model.ts` `command.register("session.btw", …)`, `slash: "btw"`,
 * `slashArguments: true`); the composer routes it here
 * (`ui/session/composer/Composer`: `ComposerSuggestions.btwQuestion` →
 * [ask] / [openPanel]), and the header's `(?)` button opens the same panel.
 */
class SessionBtwHost(
    context: Context,
    val viewModel: ChatViewModel,
) {
    /** App context: like the session-action host, toasts must not pin an Activity. */
    private val appContext = context.applicationContext

    private val model = SessionBtwModel()

    /** The active slot; mirrored out of [model] because composition reads it. */
    var state by mutableStateOf(SessionBtwModel.State())
        private set

    /**
     * Panel visibility: true while the panel's dialog layer is up. Upstream keeps
     * no such flag — its `/btw` answer *is* a dialog (`btw.tsx:71-75`), pushed and
     * cleared on the dialog stack itself; here the flag opens the layer
     * (`ui/session/btw` `SessionBtwEntry`) and [dismiss] clears it again.
     */
    var open by mutableStateOf(false)

    /**
     * Points the store at the session coming on screen ([SessionBtwModel.activate],
     * upstream `sessionKey()`). `ui/App.kt` calls this on every route session id
     * change, which also abandons the outgoing session's in-flight ask.
     */
    fun activate(sessionKey: String) {
        model.activate(sessionKey)
        publish()
    }

    /**
     * Closes the panel: called by the layer when it leaves the stack, so the flag
     * follows the panel's **real** visibility however it was dismissed — the
     * scrim tap, BACK, Escape, or a `show()` that replaced it — instead of only
     * the paths this class can see. The answer itself is untouched, so reopening
     * shows it again.
     *
     * Replaces the old `toggleOpen()`: while the panel is up it is a modal layer
     * whose scrim covers the header, so the entry button can no longer be the
     * thing that closes it.
     */
    fun dismiss() {
        open = false
    }

    /**
     * Opens the panel without asking — the **bare** `/btw` form, where upstream's
     * `run(input)` falls back to its question dialog
     * (`btw.tsx:64-66` `input?.trim() || dialog.prompt(…)`). The panel's own
     * field is that dialog here, so nothing is sent until the user asks.
     */
    fun openPanel() {
        open = true
    }

    /**
     * Upstream `model.ts` `ask`: blank → `session.btw.questionRequired` toast and
     * no state change; otherwise the panel opens and the ask runs. Upstream
     * silently returns when `sessionID` is missing — here that toast is
     * `common.requestFailed`, because this panel is also the question input and a
     * silent no-op would look like a hang.
     */
    fun ask(raw: String) {
        if (SessionBtwModel.normalize(raw) == null) {
            toast(R.string.oc_btw_question_required)
            return
        }
        open = true
        if (viewModel.sessionID == null) {
            toast(R.string.common_requestFailed)
            return
        }
        val handle = model.ask(raw) ?: return
        publish()
        generate(handle, model.state().question)
    }

    /** `model.ts:116` `retry: () => ask(state().question)`. */
    fun retry() {
        ask(state.question)
    }

    /**
     * `model.ts:32-41` `onCleanup` for the session leaving the screen: the
     * in-flight ask is dropped (`pending → error`) and its late reply ignored.
     */
    fun abandon() {
        model.abandon()
        publish()
    }

    private fun generate(handle: SessionBtwModel.Ask, question: String) {
        viewModel.generateFromContext(SessionBtwModel.prompt(question)) { text, failure ->
            if (text == null) {
                model.fail(handle, SessionBtwModel.Reason.of(failure))
            } else {
                model.succeed(handle, text)
            }
            publish()
        }
    }

    private fun publish() {
        state = model.state()
    }

    private fun toast(resId: Int) {
        Toast.makeText(appContext, appContext.getString(resId), Toast.LENGTH_SHORT).show()
    }
}

/**
 * The `/btw` host of the session on screen, published by `ui/App.kt`; null
 * wherever no session view model exists (previews, the legacy chat tab).
 */
val LocalSessionBtwHost = compositionLocalOf<SessionBtwHost?> { null }
