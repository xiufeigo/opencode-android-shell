package com.xiufeigo.opencodeshell.ui.nav

import android.content.Context
import com.xiufeigo.opencodeshell.SettingsStore

/**
 * `appearance.tabLayout` (`research/02` §9b + §12): the stored **placement** of
 * the session tab strip — `vertical` puts the title bar and the session tabs at
 * the bottom of the screen on mobile (`settings.general.row.mobileTitlebarBottom`,
 * `titlebar.tsx:64-65`), `horizontal` keeps them at the top.
 *
 * The nav layer only *reads* it; [bottomStrip] is the decoder both the strip
 * decision (`ui/App.kt`'s `topTitlebar`/`bottomTitlebar`) and the two settings
 * rows are asserted against.
 *
 * B-11: the Experimental page's Tabs row writes it, through [SettingsStore]'s
 * action table (`settings-tab-layout` → [SettingsStore.KEY_TAB_LAYOUT]) — so the
 * value lives in that store's prefs file and the two halves share one key.
 * B-16: the General page's「底部导航」switch is the second view of the same value
 * (`settings-mobile-titlebar-bottom`), which is why this one key and this one
 * decoder are all either row needs.
 */
object TabLayout {
    /** Upstream storage key, kept verbatim so both halves spell it the same. */
    const val KEY = "appearance.tabLayout"
    const val VERTICAL = "vertical"
    const val HORIZONTAL = "horizontal"

    fun bottomStrip(stored: String?): Boolean = stored?.trim() == VERTICAL
}

/**
 * Reads [TabLayout.KEY] from [SettingsStore]'s preference file — one read per
 * launch, which is exactly how often the nav layer's `bottomTabStrip` input can
 * change ([com.xiufeigo.opencodeshell.OcActivity]).
 */
class AppearancePrefs(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(SettingsStore.PREFS_NAME, Context.MODE_PRIVATE)

    fun bottomTabStrip(): Boolean =
        TabLayout.bottomStrip(prefs.getString(SettingsStore.KEY_TAB_LAYOUT, null))

    companion object {
        /** The file the value lives in (`oc_appearance` before B-11). */
        const val PREFS = SettingsStore.PREFS_NAME
    }
}
