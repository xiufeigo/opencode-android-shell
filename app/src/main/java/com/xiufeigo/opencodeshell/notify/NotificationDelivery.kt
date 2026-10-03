package com.xiufeigo.opencodeshell.notify

/**
 * B-2: the half of a raised notification that leaves the process — the Android
 * system notification (channel + `NotificationManager` + optional sound).
 *
 * Upstream splits the same decision in two: `notification.tsx:243-249` asks the
 * settings switch, then `coordinator.ts:60-69` drops the delivery when the window
 * is focused, and finally `platform.notify` posts it (`runtime/platform/web.ts:21-36`).
 * Here the settings switch is already the [AppNotifier] gate and the focus test
 * lives in [SystemNotificationPolicy], so this is the "post it" tail only.
 *
 * A `fun interface` so a caller can hand in a method reference
 * (`SystemNotifier::deliver`) without wrapping it in a lambda.
 */
fun interface NotificationDelivery {
    /**
     * Called once per **accepted** notification (the gate and the de-bounce have
     * already run), right after it is in the in-app feed. Implementations decide
     * whether the user should be disturbed; they must not throw — the raise path
     * is on the event-stream coroutine.
     */
    fun deliver(notification: AppNotification)

    /**
     * Whether the user was looking at the app from [since] until now — the
     * focus test of the raise, asked at raise time because only the Android half
     * knows the answer (`SystemNotifier`'s resumed-activity state,
     * [ForegroundTracker]; `SystemNotificationPolicy.watchedThroughout`).
     *
     * It is a *query* rather than part of [deliver]'s payload because the answer
     * belongs to the raise, not to the post: `AppNotifier`'s feed entry carries
     * the resulting `viewed` flag, and the in-app banner/dot and the system
     * notification must agree on it.
     *
     * The default answers `true` — "no Android half to ask", which is the
     * in-memory feed of previews and of the legacy WebView shell, and keeps the
     * raise's behaviour identical to the route-only `viewed` upstream has.
     */
    fun watchedThrough(since: Long?): Boolean = true
}
