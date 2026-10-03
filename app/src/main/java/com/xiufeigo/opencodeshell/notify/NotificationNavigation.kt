package com.xiufeigo.opencodeshell.notify

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * B-2: the one-shot channel from "a notification was tapped" to "the nav graph
 * opens that session".
 *
 * A tapped notification starts [com.xiufeigo.opencodeshell.OcActivity] with
 * [SystemNotificationPolicy.EXTRA_SESSION_ID] (`SystemNotifier`'s `PendingIntent`),
 * but the `NavController` lives *inside* `OcApp` — a composable the activity
 * mounts, not something the activity can address. Upstream has no such seam
 * (`web.ts:33` calls `window.focus()` and then the callback directly), so the
 * native equivalent is a process-wide request that the scaffold collects.
 *
 * Cold start and warm start land here the same way: `OcActivity.onCreate` offers
 * the launch intent before the first composition, `onNewIntent` offers later ones.
 * [pending] stays set until a scaffold consumes it, so a request that arrives
 * while the connect screen is up (no scaffold mounted yet) is not lost.
 *
 * **Consume-on-arrival**, not consume-on-read: [consume] is called by the
 * scaffold only once the requested session is the destination on screen
 * ([arrived]), and the scaffold re-offers the request on every destination change
 * until that happens. v64 measured the difference on emulator-5558
 * (`research/v64_verify/5558/17b-18b`): a tapped completion notification left the
 * user on whatever page the app was last showing (the empty new-session draft),
 * and a request consumed on read is gone the moment the navigation is issued —
 * so a navigate that the graph refuses, or a later navigation that lands on top
 * of it, loses the tap silently. Upstream has no such window at all: its callback
 * runs inside the router it navigates (`web.ts:33-35`).
 */
object NotificationNavigation {

    private val _pending = MutableStateFlow<String?>(null)

    /** The session id a tap asked for, or `null` when nothing is pending. */
    val pending: StateFlow<String?> = _pending.asStateFlow()

    /**
     * Records "open this session". A blank/null id is ignored: a notification
     * raised without a session (a failure with no routable session) has nothing
     * to open, and the activity's plain launch is the right behaviour there.
     */
    fun request(sessionID: String?) {
        val id = sessionID?.takeIf { it.isNotBlank() } ?: return
        _pending.value = id
    }

    /** Returns the pending session and clears it, so the nav runs once. */
    fun consume(): String? {
        val id = _pending.value
        _pending.value = null
        return id
    }

    /**
     * Whether the destination now on screen ([currentSessionID], read from the
     * nav back stack) is the session the pending request asked for. The scaffold
     * calls this before every navigate and [consume] only once it holds, so the
     * request survives until the session is actually there.
     */
    fun arrived(currentSessionID: String?): Boolean {
        val id = _pending.value ?: return false
        return !currentSessionID.isNullOrBlank() && currentSessionID == id
    }
}
