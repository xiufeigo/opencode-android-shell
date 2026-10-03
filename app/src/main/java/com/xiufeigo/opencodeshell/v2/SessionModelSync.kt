package com.xiufeigo.opencodeshell.v2

/**
 * Which model a session-scoped call with **no model field of its own** has to
 * run under, decided without a socket (JVM-testable, like the other pure
 * mappings in this package).
 *
 * `session.generate` is `Schema.Struct({ prompt })` and `session.prompt` is the
 * flat `{text, files?, …}` body — neither carries a model
 * (`_upstream/packages/protocol/src/groups/session.ts:599-620,705-721`), so the
 * model has to be *on the session* (`POST /api/session/{id}/model`) before the
 * call. A session whose `model` is null while the server has no configured
 * default answers **503 `ServiceUnavailableError`** (`Missing API key`) — the
 * measured device-v14 `/btw` failure, and the same wall a brand-new session's
 * first prompt hits. [Plan.NoModel] is therefore a refusal the caller must
 * surface, never a request to make.
 */
object SessionModelSync {

    /**
     * The `{model:{id,providerID,variant?}}` payload half of
     * `POST /api/session/{id}/model` ([V2Api.ModelRef]). [modelID] is the
     * catalog **entry** id the picker hands over
     * ([com.xiufeigo.opencodeshell.ui.session.composer.ModelPickerItems.selectionRef]);
     * [variant] is the reasoning-effort label, omitted from the wire when null.
     */
    data class Pin(val providerID: String, val modelID: String, val variant: String? = null)

    sealed interface Plan {
        /** Pin [pin] on the session first (`POST /model`), then run the call. */
        data class PinFirst(val pin: Pin) : Plan

        /**
         * A model already answers for this session — the session's own `model`
         * or the server's configured default — so the call runs as-is.
         */
        data object SessionModel : Plan

        /** Nothing names a model: the call would answer 503. Refuse locally. */
        data object NoModel : Plan
    }

    /**
     * [selected] is the composer's **explicit** pick, [serverDefault] the
     * server's configured default (`GET /api/model/default`), and
     * [sessionProviderID]/[sessionModelID] the tracked session's own `model`
     * (`GET /api/session/{id}`) — the last two are worth a read only when both
     * local sources are empty, which is why the caller may pass nulls for them.
     */
    fun plan(
        selected: Pin?,
        serverDefault: Pin?,
        sessionProviderID: String?,
        sessionModelID: String?,
    ): Plan = when {
        // The pick is the user's current intent: it wins over whatever the
        // session was pinned to earlier (the measured stale `muse-spark` pin
        // answered a region 503).
        selected != null -> Plan.PinFirst(selected)
        sessionProviderID.hasText() && sessionModelID.hasText() -> Plan.SessionModel
        serverDefault != null -> Plan.SessionModel
        else -> Plan.NoModel
    }

    /**
     * The line the store's error card carries for [Plan.NoModel] — the same
     * channel every other failure uses (`RunStatusChanged(ERROR, …)` →
     * `SessionErrorCard`, which classifies and renders it), so the refusal is
     * visible and reads like the rest of the error copy instead of a bare 503
     * from the server.
     */
    const val NO_MODEL_MESSAGE: String =
        "No model available — no model is selected and the server has no default model. Pick one in the composer."

    private fun String?.hasText(): Boolean = !this.isNullOrBlank()
}

/**
 * Thrown when a session-scoped call is refused locally for having no model at
 * all ([SessionModelSync.Plan.NoModel]). Its [message] is
 * [SessionModelSync.NO_MODEL_MESSAGE], so a caller that already funnels
 * exceptions into the error card needs no extra branch.
 */
class NoModelException : IllegalStateException(SessionModelSync.NO_MODEL_MESSAGE)

/** Why a transient `session.generate` produced no text. */
enum class GenerateFailure {
    /** Refused locally: nothing names a model, so the call was never sent. */
    NO_MODEL,

    /** The call went out and the transport failed (or the server answered an error). */
    REQUEST_FAILED,
}
