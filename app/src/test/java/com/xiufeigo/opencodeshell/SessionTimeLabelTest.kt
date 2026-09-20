package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.drawer.SessionTimeLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Relative-time buckets of the session drawer (`research/02` §7 `common.time.*`).
 * The Composable owns the localized literal; this pins the bucket boundaries.
 */
class SessionTimeLabelTest {

    private val now = 1_700_000_000_000L

    private fun ago(millis: Long) = SessionTimeLabel.relative(now - millis, now)

    @Test
    fun underAMinuteIsJustNow() {
        assertEquals(SessionTimeLabel.Unit.JUST_NOW, ago(0L)!!.unit)
        assertEquals(SessionTimeLabel.Unit.JUST_NOW, ago(59_000L)!!.unit)
    }

    @Test
    fun minutesHoursAndDaysBuckets() {
        assertEquals(
            SessionTimeLabel.Relative(SessionTimeLabel.Unit.MINUTES, 1),
            ago(60_000L),
        )
        assertEquals(
            SessionTimeLabel.Relative(SessionTimeLabel.Unit.MINUTES, 59),
            ago(59L * 60_000L + 30_000L),
        )
        assertEquals(
            SessionTimeLabel.Relative(SessionTimeLabel.Unit.HOURS, 1),
            ago(3_600_000L),
        )
        assertEquals(
            SessionTimeLabel.Relative(SessionTimeLabel.Unit.HOURS, 23),
            ago(23L * 3_600_000L),
        )
        assertEquals(
            SessionTimeLabel.Relative(SessionTimeLabel.Unit.DAYS, 1),
            ago(24L * 3_600_000L),
        )
        assertEquals(
            SessionTimeLabel.Relative(SessionTimeLabel.Unit.DAYS, 9),
            ago(9L * 24L * 3_600_000L + 5_000L),
        )
    }

    @Test
    fun unknownTimestampRendersNothing() {
        assertNull(SessionTimeLabel.relative(0L, now))
        assertNull(SessionTimeLabel.relative(-1L, now))
    }

    @Test
    fun clockSkewNeverRendersNegativeTime() {
        val future = SessionTimeLabel.relative(now + 120_000L, now)
        assertEquals(SessionTimeLabel.Unit.JUST_NOW, future!!.unit)
    }
}
