package com.xiufeigo.opencodeshell.notify

import android.content.Context
import androidx.core.content.edit

/**
 * B-3 on device: the feed's `SharedPreferences` home.
 *
 * Same shape as the other Android-only stores in this app
 * (`LastRouteStore`/`SettingsStore`): a private prefs file, one JSON blob under
 * one key, `apply()` writes. It gets **its own file** (`oc_notifications`) rather
 * than a key in `oc_settings` because the two have different write rates — the
 * feed is rewritten on every raise/dismiss, while a settings write re-emits
 * `SettingsStore.state` — and mixing them would make a settings read parse the
 * notification list.
 *
 * The blob is capped and TTL-pruned by [AppNotifier] *before* it reaches
 * [save], so what is on disk is always the same list the UI shows.
 */
class NotificationPrefs(context: Context) : NotificationPersistence {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): List<AppNotification> = NotificationCodec.decode(prefs.getString(KEY_LIST, null))

    override fun save(notifications: List<AppNotification>) {
        prefs.edit { putString(KEY_LIST, NotificationCodec.encode(notifications)) }
    }

    companion object {
        const val PREFS_NAME = "oc_notifications"

        /** Android-only key, named like the rest of the `opencode.android.*` block. */
        const val KEY_LIST = "opencode.android.notifications"
    }
}
