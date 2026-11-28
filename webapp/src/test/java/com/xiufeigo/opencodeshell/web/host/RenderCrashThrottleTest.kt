package com.xiufeigo.opencodeshell.web.host

import org.junit.Assert.assertEquals
import org.junit.Test

/** Direct behavior tests for the pure renderer-crash incident counter. */
class RenderCrashThrottleTest {

    @Test
    fun firstCrashStartsAtOne() {
        val throttle = RenderCrashThrottle(windowMs = 60_000L)
        assertEquals(1, throttle.record(10_000L))
        assertEquals(1, throttle.crashesInWindow)
    }

    @Test
    fun crashesInsideWindowAccumulate() {
        val throttle = RenderCrashThrottle(windowMs = 60_000L)
        assertEquals(1, throttle.record(10_000L))
        assertEquals(2, throttle.record(69_999L))
        assertEquals(3, throttle.record(70_000L))
    }

    @Test
    fun crashExactlyAtWindowBoundaryStartsNewIncident() {
        val throttle = RenderCrashThrottle(windowMs = 60_000L)
        assertEquals(1, throttle.record(10_000L))
        assertEquals(1, throttle.record(70_000L))
    }

    @Test
    fun crashAfterWindowStartsNewIncident() {
        val throttle = RenderCrashThrottle(windowMs = 60_000L)
        assertEquals(1, throttle.record(10_000L))
        assertEquals(2, throttle.record(20_000L))
        assertEquals(1, throttle.record(90_000L))
    }
}
