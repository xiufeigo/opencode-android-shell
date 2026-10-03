package com.xiufeigo.opencodeshell.ui.session.btw

import com.xiufeigo.opencodeshell.v2.GenerateFailure

/**
 * Pure state machine of the `/btw` side-question panel — upstream
 * `packages/app/src/session/btw/model.ts` (v2 head `8ce629b`) `createSessionBtw`,
 * without the Solid signals, the `AbortController`s or the SDK call.
 *
 * Wire contract (unchanged from upstream `model.ts` `ask`):
 * - the ask is `POST /api/session/{id}/generate` body `{prompt}` → transient
 *   `{text}` (history untouched), with
 *   `prompt = [INSTRUCTIONS, question].join("\n\n")` (`model.ts` `instructions` +
 *   the `[instructions, question].join("\n\n")` call site);
 * - the prompt's preamble is the upstream sentence triple verbatim — the answer
 *   must not call tools or mutate the session.
 *
 * Android addition (device v14): the route carries no model, so the session has
 * to hold one before the ask — `ChatViewModel.generateFromContext` pins the
 * composer's pick first, and an ask that finds no model anywhere fails locally
 * with [Reason.NO_MODEL] (the panel then says what to do) instead of spending a
 * round trip the server answers 503.
 *
 * State is keyed by `session.identity.sessionKey()` upstream (`model.ts:27-30`),
 * so a superseded or cross-session reply can never overwrite the visible slot:
 * every write carries the [Ask] handle of the request that produced it and is
 * dropped when the key's sequence has moved on. The cleanup
 * (`model.ts:32-41` `onCleanup`) aborts the in-flight ask and turns a still
 * pending slot into `{pending: false, error: true}` — [abandon] does the same,
 * and invalidates the sequence so a late reply cannot resurrect it (upstream
 * aborts the fetch instead).
 */
class SessionBtwModel {

    companion object {
        /**
         * `model.ts:10-14` `instructions` — joined with **one** space, then the
         * question is appended under a blank line (`join("\n\n")`).
         */
        const val INSTRUCTIONS: String =
            "The user is asking a quick side question about the conversation so far. " +
                "Answer directly and concisely in markdown from what you already know. " +
                "Do not call any tools and do not take any actions."

        /** `model.ts:49` `value?.trim()` — blank questions never reach the store. */
        fun normalize(input: String?): String? = input?.trim()?.takeIf { it.isNotEmpty() }

        /** The `{prompt}` payload of `POST /api/session/{id}/generate`. */
        fun prompt(question: String): String = "$INSTRUCTIONS\n\n$question"
    }

    /** `model.ts` `empty` + the pending ask: the fields the panel reads, plus the failure [Reason]. */
    data class State(
        val question: String = "",
        val answer: String = "",
        val pending: Boolean = false,
        val error: Boolean = false,
        /**
         * Why the last ask failed, for the panel's error body. Null on the
         * abandon path (`onCleanup`), where there is no reason to name.
         */
        val reason: Reason? = null,
    )

    /**
     * The two failures the panel can name. [NO_MODEL] is decided **before** any
     * request leaves the device (device v14: a session with no model answered
     * `POST /api/session/{id}/generate` 503 `Missing API key`); [FAILED] is
     * everything else, the upstream `.catch` case.
     */
    enum class Reason {
        NO_MODEL,
        FAILED;

        companion object {
            /**
             * The panel's reason for the ViewModel's failure
             * ([com.xiufeigo.opencodeshell.v2.GenerateFailure]); anything that is
             * not the local no-model refusal is the generic [FAILED].
             */
            fun of(failure: GenerateFailure?): Reason =
                if (failure == GenerateFailure.NO_MODEL) NO_MODEL else FAILED
        }
    }

    /**
     * One in-flight ask: the session slot it belongs to and that slot's request
     * sequence (`model.ts:58-60` `requests` map). Results only land while the
     * sequence still matches.
     */
    data class Ask(val key: String, val id: Int)

    private val states = mutableMapOf<String, State>()
    private val requests = mutableMapOf<String, Int>()
    private var key: String = ""

    /**
     * Points the visible slot at [sessionKey] (upstream `sessionKey()`; the local
     * shell passes the tracked session id). Switching runs the outgoing key's
     * [abandon] — `model.ts:32-41` cleanup semantics. States are kept per key, so
     * returning to a session restores its question/answer like upstream's store.
     */
    fun activate(sessionKey: String) {
        if (sessionKey == key) return
        abandon()
        key = sessionKey
    }

    /** The active slot (`model.ts:30` `states[sessionKey] ?? empty`). */
    fun state(): State = states[key] ?: State()

    /**
     * `model.ts:48-89` `ask`. Blank input → `null` and no state change (the
     * caller toasts `session.btw.questionRequired`). Otherwise bumps the key's
     * request sequence — a previous in-flight ask can no longer write — and sets
     * `{question, answer: "", error: false, pending: true}`.
     */
    fun ask(input: String?): Ask? {
        val question = normalize(input) ?: return null
        val id = (requests[key] ?: 0) + 1
        requests[key] = id
        states[key] = State(question = question, pending = true)
        return Ask(key, id)
    }

    /** `model.ts:74-78` `.then`: `result.text.trim()`; stale asks stay dropped. */
    fun succeed(ask: Ask, text: String) {
        if (requests[ask.key] != ask.id) return
        val current = states[ask.key] ?: return
        states[ask.key] = current.copy(answer = text.trim(), pending = false)
    }

    /**
     * `model.ts:80-84` `.catch` (non-abort): `{error: true, pending: false}`; the
     * question survives for retry. [reason] lets the panel name the failure —
     * [Reason.NO_MODEL] is the local refusal of a session with no model, which
     * the ask never sent.
     */
    fun fail(ask: Ask, reason: Reason = Reason.FAILED) {
        if (requests[ask.key] != ask.id) return
        val current = states[ask.key] ?: return
        states[ask.key] = current.copy(pending = false, error = true, reason = reason)
    }

    /** `model.ts:116` `retry: () => ask(state().question)` — same words, fresh request. */
    fun retry(): Ask? = ask(state().question)

    /**
     * `model.ts:32-41` `onCleanup`: a still-pending ask becomes
     * `{pending: false, error: true}`, and its sequence is invalidated so the
     * aborted request's late reply cannot overwrite that outcome (upstream
     * `controller.abort()` makes the `.catch` early-return instead).
     */
    fun abandon() {
        val current = state()
        if (!current.pending) return
        requests[key] = (requests[key] ?: 0) + 1
        states[key] = current.copy(pending = false, error = true)
    }
}
