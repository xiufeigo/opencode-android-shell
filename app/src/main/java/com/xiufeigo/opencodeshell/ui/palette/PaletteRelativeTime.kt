package com.xiufeigo.opencodeshell.ui.palette

import com.xiufeigo.opencodeshell.ui.session.drawer.SessionTimeLabel

/**
 * `getRelativeTime` (`shell/commands/dialog.tsx:267-271`) for the palette row's
 * `command-palette-meta` cell: the drawer's shared bucketing
 * ([SessionTimeLabel.relative]) resolved to the localized `common.time.*`
 * literal.
 *
 * Compose-free on purpose — the literals arrive as parameters so they stay in
 * `strings.xml` and this mapping stays a plain JVM function.
 */
object PaletteRelativeTime {

    /**
     * @param updatedAt epoch millis of `session.time.updated` (≤0 = unknown).
     * @param now the clock to measure against, injected by the host. It is a
     *   parameter and never a wall-clock read in here: such a read is invisible
     *   to Compose, so a palette row — whose only inputs are `updatedAt` and the
     *   search key — is skipped once composed and the label freezes at the
     *   bucket it was composed with (the drawer's `RelativeTimeText` froze the
     *   same way; see `PaletteDialogLayer`'s `paletteNow`).
     * @return `null` when [updatedAt] is unknown; otherwise the newest bucket.
     */
    fun label(
        updatedAt: Long,
        now: Long,
        justNow: String,
        minutesAgo: (Int) -> String,
        hoursAgo: (Int) -> String,
        daysAgo: (Int) -> String,
    ): String? = SessionTimeLabel.relative(updatedAt, now)?.let { relative ->
        when (relative.unit) {
            SessionTimeLabel.Unit.JUST_NOW -> justNow
            SessionTimeLabel.Unit.MINUTES -> minutesAgo(relative.count)
            SessionTimeLabel.Unit.HOURS -> hoursAgo(relative.count)
            SessionTimeLabel.Unit.DAYS -> daysAgo(relative.count)
        }
    }
}
