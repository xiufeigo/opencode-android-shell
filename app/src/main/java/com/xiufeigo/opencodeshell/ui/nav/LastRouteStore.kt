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
    fun session(serverKey: String, sessionId: String): String = "server/$serverKey/session/$sessionId"

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

    /** The `NavController` route to re-enter, or `null` to stay on Home. */
    fun target(stored: Stored?, serverKey: String): String? = when (stored?.route) {
        ROUTE_HOME -> HOME
        ROUTE_SESSION -> stored.sessionId?.let { session(serverKey, it) }
        else -> null
    }

    /** The inverse: what to persist for the destination just reached. */
    fun toStore(destinationRoute: String?, sessionId: String?): Stored? = when (destinationRoute) {
        HOME -> Stored(ROUTE_HOME, null)
        SESSION_PATTERN -> sessionId?.takeIf { it.isNotBlank() }?.let { Stored(ROUTE_SESSION, it) }
        else -> null
    }

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
