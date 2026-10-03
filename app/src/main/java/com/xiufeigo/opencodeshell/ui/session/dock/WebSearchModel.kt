package com.xiufeigo.opencodeshell.ui.session.dock

/**
 * Pure rules behind the websearch request dock ([SessionWebSearchDock]),
 * transcribed from upstream `_upstream/packages/app/src/session/requests/
 * websearch.ts` + `session-websearch-dock.tsx`.
 *
 * Upstream keys the dock off `FormInfo.metadata.kind === "websearch.provider"`
 * (`model.ts:41-59`); [ChatStore.FormAsk] keeps only `fieldsJson`, so the two
 * measured wire shapes of `websearch.test.ts` identify the ask instead:
 *
 * - **consent** — one `{key:"choice", type:"string", required, custom:false}`
 *   field: the "use search / don't / pick a provider" prompt. Its answer is
 *   `{choice: "allow"|"disable"|"choose"}`; `"choose"` is the first half of the
 *   two-step handoff (`replyWebSearch`), after which the server opens the
 *   provider form and the pick is completed there.
 * - **provider** — a `provider` string field with `options` (`webSearchProviderField`,
 *   `websearch.ts:8-12`): the specific-pick form. Its answer is
 *   `{provider: value}`; there is no disable path from this form (upstream
 *   `submit` bails on `selection === false && webSearchProviderField(form)`).
 *
 * No Compose, no network — the dock renders only what this file decides, and
 * every rule here is plain JVM unit test. The provider catalog of the consent
 * form is **not** fetched here (request construction belongs to
 * `ChatViewModel`/`V2Api`); the dock takes the options as a parameter and
 * honestly shows upstream's empty state when they never arrive.
 */
data class WebSearchOption(val value: String, val label: String)

/** Which websearch ask a pending form is (upstream `metadata.kind`). */
enum class WebSearchKind { CONSENT, PROVIDER }

/** What the dock's status line renders (upstream `status()` + the `loadFailed` override). */
enum class WebSearchStatus { LOADING, FAILED, EMPTY, LOAD_FAILED }

/**
 * Cross-form state of one websearch ask. A plain holder rather than Compose
 * state: `ChatViewModel.replyForm` optimistically drops the form (so the dock
 * leaves composition) and the follow-up provider form arrives as a *new*
 * [ChatStore.FormAsk], so `remember` cannot bridge the two halves.
 */
object WebSearchBridge {
    @Volatile
    private var handoffSelection: String? = null

    @Volatile
    private var submittedFormID: String? = null

    /** The consent form answered `choice: "choose"`; keep the pick for the provider form. */
    @Synchronized
    fun armHandoff(selection: String) {
        handoffSelection = selection
    }

    /**
     * Consume an armed handoff for a provider form. Returns `null` when nothing
     * is armed or the pick is no longer offered — upstream then leaves the new
     * form for explicit confirmation (`websearch.test.ts` "leaves a changed
     * provider list for explicit confirmation").
     */
    @Synchronized
    fun takeHandoff(optionValues: List<String>): String? {
        val selection = handoffSelection ?: return null
        handoffSelection = null
        return selection.takeIf { it in optionValues }
    }

    /** Remember which form was just answered, so a rollback reads as a failure. */
    @Synchronized
    fun markSubmitted(formID: String) {
        submittedFormID = formID
    }

    /**
     * True when [formID] is the form we just submitted — which can only be on
     * screen again because `replyForm` rolled its optimistic removal back after
     * a failed POST. Clears the mark either way.
     */
    @Synchronized
    fun consumeSubmitFailure(formID: String): Boolean {
        val failed = submittedFormID == formID
        submittedFormID = null
        return failed
    }

    /** Test seam: forget everything (a new ask starts clean, upstream `createEffect` reset). */
    @Synchronized
    fun reset() {
        handoffSelection = null
        submittedFormID = null
    }
}

object WebSearchModel {

    /** `value: "random"` — upstream's "Any" option (`session-websearch-dock.tsx:14`). */
    const val ANY_VALUE = "random"

    /**
     * [WebSearchKind.PROVIDER] when a `provider` string field with options is
     * present ([SessionFormModel] parsed fields), else [WebSearchKind.CONSENT]
     * for the single `choice` shape, else `null` (a normal form).
     */
    fun detect(fields: List<FormField>): WebSearchKind? {
        val specific = fields.any {
            it is FormField.Text && it.key == "provider" && it.options.isNotEmpty()
        }
        if (specific) return WebSearchKind.PROVIDER
        val single = fields.singleOrNull() as? FormField.Text ?: return null
        if (single.key == "choice" && !single.custom && single.options.isEmpty()) return WebSearchKind.CONSENT
        return null
    }

    /** [detect] over the raw `FormAsk.fieldsJson`. */
    fun detect(fieldsJson: String): WebSearchKind? =
        detect(SessionFormModel.parseFormFields(fieldsJson))

    /** The `provider` field's options (`websearch.ts:8-12`); empty unless [WebSearchKind.PROVIDER]. */
    fun formOptions(kind: WebSearchKind?, fields: List<FormField>): List<WebSearchOption> {
        if (kind != WebSearchKind.PROVIDER) return emptyList()
        return fields.filterIsInstance<FormField.Text>()
            .firstOrNull { it.key == "provider" }
            ?.options
            ?.map { WebSearchOption(it.value, it.label) }
            .orEmpty()
    }

    /**
     * Upstream `options` memo (`session-websearch-dock.tsx:15-19`): the "Any"
     * row only on the consent form, then the catalog (form options or the
     * fetched provider list).
     */
    fun selectOptions(kind: WebSearchKind, providers: List<WebSearchOption>): List<WebSearchOption> =
        if (kind == WebSearchKind.PROVIDER) {
            providers
        } else {
            listOf(WebSearchOption(ANY_VALUE, "")) + providers
        }

    /**
     * Upstream `selected` memo (`websearch.ts:42-45`): the consent form keeps
     * the user's pick (default "Any"); the provider form falls back to the
     * first option when the remembered pick is gone.
     */
    fun selected(kind: WebSearchKind, providers: List<WebSearchOption>, requested: String?): String? {
        if (kind == WebSearchKind.CONSENT) return requested?.takeIf { it.isNotBlank() } ?: ANY_VALUE
        if (providers.isEmpty()) return null
        return requested?.takeIf { pick -> providers.any { it.value == pick } } ?: providers.first().value
    }

    /**
     * Upstream `unavailable` (`session-websearch-dock.tsx:25`): the Select and
     * the Enable button die while loading, on a failed load, or when the
     * catalog is empty (an empty catalog also owns the `session.websearch.empty`
     * status line).
     */
    fun unavailable(providers: List<WebSearchOption>, loading: Boolean, loadFailed: Boolean): Boolean =
        loading || loadFailed || providers.isEmpty()

    /** Upstream `busy` = `sending || !connected`; Android answers through one dock, so only the in-flight flag. */
    fun canSubmit(
        kind: WebSearchKind,
        providers: List<WebSearchOption>,
        selection: String?,
        loading: Boolean,
        loadFailed: Boolean,
        sending: Boolean,
    ): Boolean {
        if (sending) return false
        if (unavailable(providers, loading, loadFailed)) return false
        val options = selectOptions(kind, providers)
        return selection != null && options.any { it.value == selection }
    }

    /** Upstream `status()` (`session-websearch-dock.tsx:21-27`); `null` = no status line. */
    fun status(providers: List<WebSearchOption>, loading: Boolean, failed: Boolean): WebSearchStatus? = when {
        loading -> WebSearchStatus.LOADING
        failed -> WebSearchStatus.FAILED
        providers.isEmpty() -> WebSearchStatus.EMPTY
        else -> null
    }

    /**
     * Disable is only offered on the consent form (`Show when={!specific}`,
     * `session-websearch-dock.tsx:56-60`).
     */
    fun offersDisable(kind: WebSearchKind): Boolean = kind == WebSearchKind.CONSENT

    /**
     * The reply payload of one submit (`replyWebSearch`, `websearch.ts:88-145`).
     * `selection == null` is the Disable button. `null` = a no-op: disabling
     * from the provider form (upstream returns before replying).
     *
     * A consent pick other than "Any" must additionally run the handoff — call
     * [WebSearchBridge.armHandoff] and then [handoffAnswer] when the provider
     * form arrives (the dock does both).
     */
    fun answer(kind: WebSearchKind, selection: String?): Map<String, Any?>? = when (kind) {
        WebSearchKind.PROVIDER -> when {
            selection == null || selection == ANY_VALUE -> null
            else -> mapOf("provider" to selection)
        }

        WebSearchKind.CONSENT -> when (selection) {
            null -> mapOf("choice" to "disable")
            ANY_VALUE -> mapOf("choice" to "allow")
            else -> mapOf("choice" to "choose")
        }
    }

    /**
     * True when a consent answer is the `"choose"` half of the handoff: the
     * pick must survive into the provider form (`websearch.test.ts`
     * "subscribes before the first reply and answers the owning session's
     * provider form").
     */
    fun armsHandoff(kind: WebSearchKind, selection: String?): Boolean =
        kind == WebSearchKind.CONSENT && selection != null && selection != ANY_VALUE

    /**
     * The auto-answer of the provider form a handoff is waiting for: only when
     * the pick is still offered (`websearch.test.ts` "leaves a changed provider
     * list for explicit confirmation").
     */
    fun handoffAnswer(providers: List<WebSearchOption>, selection: String?): Map<String, Any?>? {
        if (selection == null) return null
        if (providers.none { it.value == selection }) return null
        return mapOf("provider" to selection)
    }
}
