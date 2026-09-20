package com.xiufeigo.opencodeshell.ui.nav

import android.content.Context

/**
 * `appearance.tabLayout` (`research/02` §9b + §12): the desktop setting that
 * moves the title bar and the session tabs to the bottom of the screen on
 * mobile (`settings.general.row.mobileTitlebarBottom`).
 *
 * M3 only *reads* it — the toggle itself is part of the M4 settings panel — and
 * defaults to the horizontal layout, i.e. **no bottom bar** unless the stored
 * value says otherwise.
 */
object TabLayout {
    /** Upstream storage key, kept verbatim so M4 can write the same value. */
    const val KEY = "appearance.tabLayout"
    const val VERTICAL = "vertical"
    const val HORIZONTAL = "horizontal"

    fun bottomStrip(stored: String?): Boolean = stored?.trim() == VERTICAL
}

/** Reads [TabLayout.KEY] from the app's plain preference file. */
class AppearancePrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun bottomTabStrip(): Boolean = TabLayout.bottomStrip(prefs.getString(TabLayout.KEY, null))

    companion object {
        const val PREFS = "oc_appearance"
    }
}
