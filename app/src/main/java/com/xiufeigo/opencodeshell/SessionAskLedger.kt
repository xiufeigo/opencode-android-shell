package com.xiufeigo.opencodeshell

/**
 * The ask kinds pending on one session — upstream's `sessionPermissionRequest` /
 * `sessionFormRequest` (`session/requests/session-request-tree.ts`) collapsed to
 * the two facts the dot reads. `ChatViewModel` publishes one per session from
 * [SessionAskLedger]; the surfaces only ever ask "is this session waiting on a
 * permission / a form".
 */
data class SessionAsks(
    val permission: Boolean = false,
    val form: Boolean = false,
) {
    val any: Boolean get() = permission || form

    companion object {
        val NONE = SessionAsks()
    }
}

/**
 * The **location-wide** pending-ask ledger — upstream's
 * `session.permission.list` / `session.form.list`
 * (`shell/layout/project-avatar-state.ts:26-35`), which is what makes
 * `hasPermissions` / `hasForms` answer for a session this client is not
 * observing:
 *
 * ```ts
 * hasPermissions = !autoApprove && !!sessionPermissionRequest(sessions, ctx.data.session.permission.list, sessionId)
 * hasForms       =                  !!sessionFormRequest(sessions, ctx.data.session.form.list, sessionId)
 * ```
 *
 * The two lists are keyed by **session**, not by "the session on screen": the
 * server keeps every pending ask, and any tab / row / Home session can be the
 * one waiting. This shell's `ChatStore.pendingPermissions` is the opposite view —
 * it is the *tracked* session's dock list (every ask event is gated by
 * `tracksSession`) — so a foreign session's pending ask could only ever reach the
 * dot through the in-app feed, whose `viewed` flag is a different lifecycle:
 * opening the session clears the feed row while the ask is still pending, and
 * 「Dismiss all」drops the row outright. This ledger is the missing per-session
 * fact, and it is pure so the folding is pinned by a JVM test.
 *
 * Folded from the same frames `ChatViewModel` already consumes:
 * `permission.asked` / `permission.replied` / `form.created` / `form.resolved`
 * for **any** session (the v2 event bus is global), the tracked session's own
 * `…/permission` and `…/form` snapshots (authoritative for that session) and the
 * location-wide `GET /api/permission/request` / `GET /api/form` rows (the union
 * that covers asks which landed while no stream was open).
 *
 * [requests] / [forms] carry `id → sessionID` because the reply frames name only
 * the ask (`permission.replied{requestID}`, `form.replied{id}`) — the same
 * mapping upstream keeps server-side.
 */
data class SessionAskLedger(
    /** `sessionID` → the ask kinds pending on it. */
    val bySession: Map<String, SessionAsks> = emptyMap(),
    /** `requestID` → `sessionID`, so a reply can clear the session it belongs to. */
    val requests: Map<String, String> = emptyMap(),
    /** `formID` → `sessionID`, same for `form.replied` / `form.cancelled`. */
    val forms: Map<String, String> = emptyMap(),
) {

    fun pending(sessionID: String): SessionAsks = bySession[sessionID] ?: SessionAsks.NONE

    /**
     * [bySession] with one session's flags replaced — and the entry **dropped**
     * once nothing is pending, so the published map means exactly what its KDoc
     * says ("the ask kinds pending on it") instead of accumulating answered rows.
     */
    private fun flagged(sessionID: String, asks: SessionAsks): Map<String, SessionAsks> =
        if (asks.any) bySession + (sessionID to asks) else bySession - sessionID

    /** `permission.asked` — the ask is pending on [sessionID]. */
    fun asked(requestID: String, sessionID: String): SessionAskLedger {
        // A frame with no session carries no routing info (`ChatStore`'s own
        // rule); it can belong to no row, so it is dropped rather than guessed.
        if (sessionID.isBlank()) return this
        val requests = if (requestID.isBlank()) this.requests else this.requests + (requestID to sessionID)
        return copy(
            bySession = bySession + (sessionID to pending(sessionID).copy(permission = true)),
            requests = requests,
        )
    }

    /** `permission.replied` — only the ask the reply names stops counting. */
    fun replied(requestID: String): SessionAskLedger {
        val sessionID = requests[requestID] ?: return this
        val requests = this.requests - requestID
        return copy(
            bySession = flagged(sessionID, pending(sessionID).copy(permission = requests.containsValue(sessionID))),
            requests = requests,
        )
    }

    /** `form.created` — the form is pending on [sessionID]. */
    fun formCreated(formID: String, sessionID: String): SessionAskLedger {
        if (sessionID.isBlank()) return this
        val forms = if (formID.isBlank()) this.forms else this.forms + (formID to sessionID)
        return copy(
            bySession = bySession + (sessionID to pending(sessionID).copy(form = true)),
            forms = forms,
        )
    }

    /** `form.replied` / `form.cancelled` — the form stops waiting. */
    fun formResolved(formID: String): SessionAskLedger {
        val sessionID = forms[formID] ?: return this
        val forms = this.forms - formID
        return copy(
            bySession = flagged(sessionID, pending(sessionID).copy(form = forms.containsValue(sessionID))),
            forms = forms,
        )
    }

    /**
     * The tracked session's `GET …/permission` snapshot: a full replacement of
     * **that** session's permission half (an ask that vanished from the list was
     * answered or expired), leaving every other session's entry alone.
     */
    fun permissionSnapshot(sessionID: String, requestIDs: List<String>): SessionAskLedger {
        if (sessionID.isBlank()) return this
        val ids = requestIDs.filter { it.isNotBlank() }
        val requests = this.requests.filterValues { it != sessionID } +
            ids.associateWith { sessionID }
        return copy(
            bySession = flagged(sessionID, pending(sessionID).copy(permission = ids.isNotEmpty())),
            requests = requests,
        )
    }

    /** `GET …/form` snapshot — the form half of [permissionSnapshot]. */
    fun formSnapshot(sessionID: String, formIDs: List<String>): SessionAskLedger {
        if (sessionID.isBlank()) return this
        val ids = formIDs.filter { it.isNotBlank() }
        val forms = this.forms.filterValues { it != sessionID } + ids.associateWith { sessionID }
        return copy(
            bySession = flagged(sessionID, pending(sessionID).copy(form = ids.isNotEmpty())),
            forms = forms,
        )
    }

    /**
     * Union in the location-wide `GET /api/permission/request` / `GET /api/form`
     * rows (`(id, sessionID)` pairs). Union, never replacement: the read is
     * scoped to one location, so an entry it does not name is not evidence that
     * the ask is gone — the reply frames are the only thing that clears one.
     */
    fun pendingRows(
        permissions: List<Pair<String, String>> = emptyList(),
        forms: List<Pair<String, String>> = emptyList(),
    ): SessionAskLedger {
        var next = this
        for ((id, sessionID) in permissions) next = next.asked(id, sessionID)
        for ((id, sessionID) in forms) next = next.formCreated(id, sessionID)
        return next
    }

    /** A deleted session takes its asks with it. */
    fun forget(sessionID: String): SessionAskLedger = copy(
        bySession = bySession - sessionID,
        requests = requests.filterValues { it != sessionID },
        forms = forms.filterValues { it != sessionID },
    )
}
