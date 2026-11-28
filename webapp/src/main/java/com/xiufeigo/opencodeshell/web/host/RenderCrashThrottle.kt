package com.xiufeigo.opencodeshell.web.host

/**
 * Pure time-window counter for renderer-process crashes.
 *
 * The counter deliberately has no Android dependency: crash-loop suppression is a
 * small piece of time arithmetic, and keeping it pure makes the reset boundary
 * directly testable on the JVM. The Android/WebView recovery host owns the
 * effects; this class owns only the count.
 */
class RenderCrashThrottle(
    private val windowMs: Long,
) {
    private var lastCrashAtMs: Long = 0L

    /** Number of crashes in the current incident window. */
    var crashesInWindow: Int = 0
        private set

    /**
     * Record one crash and return the incident count.
     *
     * A crash exactly at `windowMs` starts a new incident: the window test is
     * strictly `<`, matching the original production implementation.
     */
    fun record(nowMs: Long): Int {
        val withinWindow = lastCrashAtMs > 0L && (nowMs - lastCrashAtMs) < windowMs
        crashesInWindow = if (withinWindow) crashesInWindow + 1 else 1
        lastCrashAtMs = nowMs
        return crashesInWindow
    }
}
