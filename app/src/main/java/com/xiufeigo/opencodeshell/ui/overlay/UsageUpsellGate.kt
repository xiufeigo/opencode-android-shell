package com.xiufeigo.opencodeshell.ui.overlay

import android.content.Context
import android.content.SharedPreferences
import com.xiufeigo.opencodeshell.ChatStore

/**
 * `go-upsell` persistence for the usage-exceeded dialog (upstream
 * `_upstream/packages/app/src/session/usage-exceeded-dialogs.tsx:14-27`):
 * per-reason `lastSeenAt` (24 h dedup window) + `dontShow` ("don't show
 * again") timestamps, one pair per dialog reason.
 *
 * Upstream keeps two pairs (`free_tier_limit`, `account_rate_limit`) in a
 * global persisted store; the generic 429/402 quota bucket
 * ([ChatStore.UsageUpsell.REASON_QUOTA_EXHAUSTED]) has no upstream key, so it
 * gets a third pair in the same shape. Unknown reasons fail open (show):
 * the gate never hides a dialog it cannot name.
 *
 * Storage follows the `TerminalPanel` tab prefs (plain `SharedPreferences`,
 * synchronous `commit()` so a re-read in the same frame sees the write);
 * the pure [shouldShow] overload stays JVM-testable.
 */
object UsageUpsellGate {

    /** Upstream `GO_UPSELL_WINDOW`: the same reason does not re-pop within 24 h. */
    const val WINDOW_MILLIS = 86_400_000L

    const val PREFS_NAME = "oc_usage_upsell"

    private const val FREE_TIER_LAST_SEEN = "go_upsell_last_seen_at"
    private const val FREE_TIER_DONT_SHOW = "go_upsell_dont_show"
    private const val RATE_LIMIT_LAST_SEEN = "go_upsell_account_rate_limit_last_seen_at"
    private const val RATE_LIMIT_DONT_SHOW = "go_upsell_account_rate_limit_dont_show"
    private const val QUOTA_LAST_SEEN = "go_upsell_quota_last_seen_at"
    private const val QUOTA_DONT_SHOW = "go_upsell_quota_dont_show"

    data class Keys(val lastSeenAt: String, val dontShow: String)

    fun keysFor(reason: String?): Keys? = when (reason) {
        ChatStore.UsageUpsell.REASON_FREE_TIER_LIMIT ->
            Keys(FREE_TIER_LAST_SEEN, FREE_TIER_DONT_SHOW)
        ChatStore.UsageUpsell.REASON_ACCOUNT_RATE_LIMIT ->
            Keys(RATE_LIMIT_LAST_SEEN, RATE_LIMIT_DONT_SHOW)
        ChatStore.UsageUpsell.REASON_QUOTA_EXHAUSTED ->
            Keys(QUOTA_LAST_SEEN, QUOTA_DONT_SHOW)
        else -> null
    }

    /**
     * Pure gate: `dontShow` suppresses forever, `lastSeenAt` suppresses for
     * [WINDOW_MILLIS]. `null` = never recorded. Mirrors upstream's
     * `seen && Date.now() - seen < GO_UPSELL_WINDOW → return` +
     * `if (goUpsellState[keys.dontShow]) return`.
     */
    fun shouldShow(
        lastSeenAt: Long?,
        dontShowAt: Long?,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        if (dontShowAt != null) return false
        if (lastSeenAt != null && nowMillis - lastSeenAt < WINDOW_MILLIS) return false
        return true
    }

    fun prefsOf(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun shouldShow(
        prefs: SharedPreferences,
        reason: String?,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        val keys = keysFor(reason) ?: return true
        val lastSeen = prefs.getLong(keys.lastSeenAt, -1L).takeIf { it >= 0L }
        val dontShow = prefs.getLong(keys.dontShow, -1L).takeIf { it >= 0L }
        return shouldShow(lastSeen, dontShow, nowMillis)
    }

    /** Records that [reason]'s dialog was shown at [nowMillis] (24 h snooze). */
    fun markShown(prefs: SharedPreferences, reason: String?, nowMillis: Long = System.currentTimeMillis()) {
        val keys = keysFor(reason) ?: return
        prefs.edit().putLong(keys.lastSeenAt, nowMillis).commit()
    }

    /** Records "don't show again": suppresses [reason] until the prefs are cleared. */
    fun markDontShow(prefs: SharedPreferences, reason: String?, nowMillis: Long = System.currentTimeMillis()) {
        val keys = keysFor(reason) ?: return
        prefs.edit()
            .putLong(keys.lastSeenAt, nowMillis)
            .putLong(keys.dontShow, nowMillis)
            .commit()
    }
}
