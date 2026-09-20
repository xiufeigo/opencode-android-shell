package com.xiufeigo.opencodeshell.ui.session.drawer

import java.util.concurrent.TimeUnit

/**
 * Pure (JVM-testable) relative-time bucketing for the session drawer rows.
 *
 * The i18n keys are the upstream `common.time.*` ones
 * (`research/02` §7): `justNow` / `minutesAgo.short` / `hoursAgo.short` /
 * `daysAgo.short`. This file only decides the **bucket + count**; the
 * Composable owns the localized literal (so the logic stays testable and the
 * strings stay in `strings.xml`).
 */
object SessionTimeLabel {

    enum class Unit { JUST_NOW, MINUTES, HOURS, DAYS }

    data class Relative(val unit: Unit, val count: Int)

    /**
     * @param updatedAt epoch millis of `session.time.updated` (≤0 = unknown).
     * @param now epoch millis to measure against (injected for testability).
     * @return `null` when [updatedAt] is unknown; otherwise the newest bucket.
     */
    fun relative(updatedAt: Long, now: Long): Relative? {
        if (updatedAt <= 0L) return null
        val elapsed = now - updatedAt
        // A clock skew between device and server must not render "-3 minutes".
        if (elapsed < MINUTE_MS) return Relative(Unit.JUST_NOW, 1)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsed)
        if (minutes < MINUTES_PER_HOUR) return Relative(Unit.MINUTES, minutes.toInt())
        val hours = TimeUnit.MILLISECONDS.toHours(elapsed)
        if (hours < HOURS_PER_DAY) return Relative(Unit.HOURS, hours.toInt())
        return Relative(Unit.DAYS, TimeUnit.MILLISECONDS.toDays(elapsed).toInt())
    }

    private const val MINUTE_MS = 60_000L
    private const val MINUTES_PER_HOUR = 60L
    private const val HOURS_PER_DAY = 24L
}
