package com.xiufeigo.opencodeshell.ui.nav

import android.content.Context

/**
 * Where the nav graph reads/writes "the page the user was last on". An
 * interface (not the concrete store) so `OcApp` can be exercised in previews and
 * tests with an in-memory fake.
 */
interface NavMemory {
    fun load(): NavRestore.Stored?

    fun save(route: String, sessionId: String?)
}

/**
 * Plain `SharedPreferences` persistence for [NavRestore] (M3 brief: "M0/M3 uses
 * simple SharedPreferences"). Deliberately NOT `EncryptedSharedPreferences`
 * like [com.xiufeigo.opencodeshell.ServerConfigStore]: a route name and a
 * session id are not secrets, and this one is read on the cold-start path where
 * the encrypted store's key derivation would only add latency.
 */
class LastRouteStore(context: Context) : NavMemory {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun load(): NavRestore.Stored? = NavRestore.stored(
        route = prefs.getString(NavRestore.KEY_ROUTE, null),
        sessionId = prefs.getString(NavRestore.KEY_SESSION_ID, null),
    )

    override fun save(route: String, sessionId: String?) {
        prefs.edit()
            .putString(NavRestore.KEY_ROUTE, route)
            // `putString(key, null)` drops the key — correct for "home".
            .putString(NavRestore.KEY_SESSION_ID, sessionId)
            .apply()
    }

    companion object {
        const val PREFS = "oc_nav_state"
    }
}

/**
 * Pure half of "restore the last page" (PLAN §3): the desktop app keeps the
 * current route in memory and re-enters it on launch, which for Android means a
 * cold start has to read it back from disk. Everything here is
 * `SharedPreferences`-free so it stays JVM-testable; [LastRouteStore] is the
 * only part that touches Android.
 *
 * Only the two *stable* pages are persisted: `home` and a concrete session.
 * `new-session` is a transient draft (upstream `/new-session?draftId=…`) and
 * `settings` is a dialog over whatever is behind it, so neither is a sensible
 * cold-start target — they leave the stored value untouched.
 *
 * The stable route strings below are the same literals as the nav graph's
 * `Route` (`ui/App.kt`); `ROUTE_HOME`/`ROUTE_SESSION` are the *stored* values,
 * which are deliberately not the pattern strings so the pattern can evolve.
 */
object NavRestore {
    const val KEY_ROUTE = "oc_last_route"
    const val KEY_SESSION_ID = "oc_last_session_id"

    /** Stored values. */
    const val ROUTE_HOME = "home"
    const val ROUTE_SESSION = "session"

    /** `Route.HOME` in `ui/App.kt`. */
    private const val HOME = "home"

    /** `Route.SESSION` pattern in `ui/App.kt` — the concrete form is built by [session]. */
    private const val SESSION_PATTERN = "server/{serverKey}/session/{id}"

    /** Single-server shell: used when no server URL is known yet. */
    const val DEFAULT_SERVER_KEY = "default"

    data class Stored(val route: String, val sessionId: String?)

    /** `Route.session(serverKey, id)` in `ui/App.kt`. */
    fun session(serverKey: String, sessionId: String): String =
        "server/$serverKey/session/${encodeSegment(sessionId)}"

    /**
     * [session] that refuses to build a route a session id cannot fill.
     *
     * The route pattern is `server/{serverKey}/session/{id}` — one path
     * segment. A blank id (or one carrying a `/`, e.g. a worktree *directory*
     * mistaken for a session id) used to land in the route verbatim and the
     * `NavController` threw `IllegalArgumentException: Navigation destination
     * that matches route … cannot be found` — the v65 P0 crash. Null is the
     * caller's cue to stay where it is.
     */
    fun sessionOrNull(serverKey: String, sessionId: String?): String? {
        val id = sessionId?.trim().orEmpty()
        if (id.isEmpty() || '/' in id) return null
        return session(serverKey, id)
    }

    /**
     * Percent-encodes one path segment (unreserved set only), so a stray
     * `/`, `?` or `#` in a value can never reshape the route around it.
     */
    fun encodeSegment(value: String): String {
        val out = StringBuilder(value.length)
        for (ch in value) {
            val safe = ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' ||
                ch == '-' || ch == '.' || ch == '_' || ch == '~'
            if (safe) out.append(ch) else {
                for (b in ch.toString().toByteArray(Charsets.UTF_8)) {
                    out.append('%').append("%02X".format(b.toInt() and 0xFF))
                }
            }
        }
        return out.toString()
    }

    /**
     * Decodes what was on disk. An unknown route — or a session whose id is
     * gone — restores to nothing (the caller then keeps Home), which is what
     * "the page no longer exists" has to mean for a cold start.
     */
    fun stored(route: String?, sessionId: String?): Stored? = when (route) {
        ROUTE_HOME -> Stored(ROUTE_HOME, null)
        ROUTE_SESSION -> sessionId?.takeIf { it.isNotBlank() }?.let { Stored(ROUTE_SESSION, it) }
        else -> null
    }

    /**
     * The `NavController` route to re-enter, or `null` to stay on Home.
     *
     * The id goes through [sessionOrNull] rather than [session]: a stored id
     * that cannot fill the route pattern's single `{id}` segment (blank, or a
     * `/`-bearing worktree *directory* written by a build older than the v65
     * fix) is not a restorable page, and navigating to the percent-encoded
     * route it would produce throws
     * `IllegalArgumentException: Navigation destination that matches route …
     * cannot be found`. This is upstream's own rule for the same value —
     * `acceptedLastActiveUrl` accepts only `/`, `/new-session` and
     * `/^\/server\/[^/]+\/session\/[^/]+$/`, and falls back to `"/"` otherwise
     * (`_upstream/packages/desktop/src/renderer/window/route-storage.ts:17-23`).
     */
    fun target(stored: Stored?, serverKey: String): String? = when (stored?.route) {
        ROUTE_HOME -> HOME
        ROUTE_SESSION -> sessionOrNull(serverKey, stored.sessionId)
        else -> null
    }

    /** The inverse: what to persist for the destination just reached. */
    fun toStore(destinationRoute: String?, sessionId: String?): Stored? = when (destinationRoute) {
        HOME -> Stored(ROUTE_HOME, null)
        SESSION_PATTERN -> sessionId?.takeIf { it.isNotBlank() }?.let { Stored(ROUTE_SESSION, it) }
        else -> null
    }

    /**
     * The write-back half, gated on the cold-start **read** having settled.
     *
     * v97 (N-1, `research/v97_verify/5560_sweepA/REPORT_v97b.md`): the restore
     * reads the store off the main thread, but a `NavController` destination
     * listener is told about the current destination *the moment it is
     * registered* (`NavController.addOnDestinationChangedListener` — "The
     * current destination, if any, will be immediately sent to your listener",
     * navigation-common 2.8.5 `NavController.kt:413-428`). On a cold start that
     * destination is the graph's start destination, Home — so the ungated
     * listener persisted `home` over a perfectly good `session` **before the
     * restore read had even started**, and the read then found `home`. Upstream
     * orders the two halves the other way round: the restore is applied to the
     * history *first* and the persistence listener is attached after it
     * (`_upstream/…/window/router.tsx:7-9`).
     *
     * `settled == false` therefore means "the app has not yet read the value this
     * write would overwrite" → nothing is persisted. Everything after the read
     * lands is unchanged, and the tab/visible-session bookkeeping the same
     * listener does is deliberately *not* gated.
     */
    fun toStore(settled: Boolean, destinationRoute: String?, sessionId: String?): Stored? =
        if (settled) toStore(destinationRoute, sessionId) else null

    /**
     * `server` half of the session route. Upstream uses the server connection
     * key; this shell has exactly one configured server, so a stable digest of
     * its URL is enough and, unlike the raw URL, is safe inside a route path.
     */
    fun serverKeyOf(url: String?): String {
        val value = url?.trim().orEmpty()
        if (value.isEmpty()) return DEFAULT_SERVER_KEY
        return "s" + (value.hashCode().toLong() and 0xFFFFFFFFL).toString(16)
    }
}
