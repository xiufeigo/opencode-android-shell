package com.xiufeigo.opencodeshell

import org.json.JSONObject
import com.xiufeigo.opencodeshell.ui.overlay.UsageUpsellGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Token Plan / Go usage-exceeded upsell: `session.status` retry `action`
 * (`free_tier_limit` / `account_rate_limit`) and the 429/402 quota bucket
 * raise [ChatStore.ChatState.usageUpsell]; idle clears it, unrelated events
 * keep a dismissal, and the dialog source stays pinned to its contract.
 */
class UsageUpsellTest {

    private fun retryAction(
        reason: String,
        message: String = "server copy",
        title: String = "server title",
    ) = ChatStore.RetryInfo(
        attempt = 2,
        next = 0L,
        message = "retrying",
        action = ChatStore.RetryInfo.Action(
            reason = reason,
            provider = "opencode-go",
            title = title,
            message = message,
            label = "Subscribe",
            link = "https://example.invalid/sub",
        ),
    )

    // ---- fromRetry: Go actions ------------------------------------------------

    @Test
    fun `free tier action raises upsell with server copy`() {
        val upsell = ChatStore.UsageUpsell.fromRetry(retryAction("free_tier_limit"), nowMillis = 1_000L)
        assertNotNull(upsell)
        assertEquals("free_tier_limit", upsell!!.reason)
        assertEquals("server copy", upsell.message)
        assertNull(upsell.retryAfter)
    }

    @Test
    fun `account rate limit action raises upsell`() {
        val upsell = ChatStore.UsageUpsell.fromRetry(retryAction("account_rate_limit"), nowMillis = 1_000L)
        assertEquals("account_rate_limit", upsell!!.reason)
    }

    @Test
    fun `retry countdown formats as retryAfter`() {
        val upsell = ChatStore.UsageUpsell.fromRetry(
            retryAction("free_tier_limit").copy(next = 61_000L),
            nowMillis = 1_000L,
        )
        assertEquals("60s", upsell!!.retryAfter)
    }

    // ---- the two units `next` may carry --------------------------------------

    /**
     * The schema types `session.status{type:"retry"}.next` as a bare
     * `NonNegativeInt` with no unit (`session-status-event.ts:13-28`, `next` at
     * `:27`), while the
     * durable `session.retry.scheduled.at` (`session-event.ts:563-572`) is epoch
     * millis — and both land in `RetryInfo.next`. These pin the countdown for
     * either reading, so a server answering a relative delay cannot make the
     * naive subtraction negative and silently drop the countdown.
     */
    @Test
    fun `an epoch-millis next counts down from now`() {
        val now = 1_790_435_600_000L
        val upsell = ChatStore.UsageUpsell.fromRetry(
            retryAction("free_tier_limit").copy(next = now + 60_000L),
            nowMillis = now,
        )
        assertEquals("60s", upsell!!.retryAfter)
    }

    @Test
    fun `a relative next is counted from now instead of being dropped`() {
        val now = 1_790_435_600_000L
        assertTrue(60_000L < ChatStore.RetryInfo.RELATIVE_NEXT_MAX_MILLIS)
        val upsell = ChatStore.UsageUpsell.fromRetry(
            retryAction("free_tier_limit").copy(next = 60_000L),
            nowMillis = now,
        )
        assertEquals("60s", upsell!!.retryAfter)
    }

    @Test
    fun `an omitted or replayed-past next yields no countdown`() {
        val now = 1_790_435_600_000L
        // 0 = the server omitted it (`RetryInfo.next`).
        assertNull(
            ChatStore.UsageUpsell.fromRetry(
                retryAction("free_tier_limit").copy(next = 0L),
                nowMillis = now,
            )!!.retryAfter,
        )
        // A durable `at` replayed after its deadline is an instant, not a
        // delay: it must not be re-read as `now + 1.79e12`.
        assertNull(
            ChatStore.UsageUpsell.fromRetry(
                retryAction("free_tier_limit").copy(next = now - 5_000L),
                nowMillis = now,
            )!!.retryAfter,
        )
    }

    @Test
    fun `empty action message falls back to action title`() {
        val upsell = ChatStore.UsageUpsell.fromRetry(
            retryAction("free_tier_limit", message = "", title = "title copy"),
            nowMillis = 1_000L,
        )
        assertEquals("title copy", upsell!!.message)
    }

    @Test
    fun `plain retry without quota signal raises nothing`() {
        val retry = ChatStore.RetryInfo(attempt = 1, next = 0L, message = "connection reset", action = null)
        assertNull(ChatStore.UsageUpsell.fromRetry(retry, nowMillis = 1_000L))
    }

    @Test
    fun `action-less Token Plan quota line falls back to generic bucket`() {
        val retry = ChatStore.RetryInfo(
            attempt = 1,
            next = 0L,
            message = "已达到 Token Plan 用量上限：请升级 Token Plan 套餐或购买积分补充用量。",
            action = null,
        )
        val upsell = ChatStore.UsageUpsell.fromRetry(retry, nowMillis = 1_000L)
        assertEquals(ChatStore.UsageUpsell.REASON_QUOTA_EXHAUSTED, upsell!!.reason)
    }

    // ---- fromError: 429/402 quota bucket ---------------------------------------

    @Test
    fun `http 429 quota line raises upsell`() {
        val upsell = ChatStore.UsageUpsell.fromError(
            "HTTP 429 已达到 Token Plan 用量上限：请升级 Token Plan 套餐或购买积分补充用量。",
            null,
        )
        assertNotNull(upsell)
        assertNull(upsell!!.retryAfter)
    }

    @Test
    fun `session error detail with 429 raises upsell`() {
        val detail = JSONObject()
            .put("type", "rate_limit_error")
            .put("message", "quota exploded")
            .put("status", 429)
            .toString()
        val upsell = ChatStore.UsageUpsell.fromError("quota exploded", detail)
        assertEquals("rate_limit_error", upsell!!.reason)
    }

    @Test
    fun `non-quota error raises nothing`() {
        assertNull(ChatStore.UsageUpsell.fromError("connection reset by peer", null))
    }

    @Test
    fun `quota message without 429 or 402 status raises nothing`() {
        // The error card owns status-less quota lines; the dialog is the
        // 429/402 bucket's.
        assertNull(ChatStore.UsageUpsell.fromError("Token Plan 用量上限", null))
    }

    // ---- go-upsell gate: 24 h dedup + don't-show-again -----------------------------

    @Test
    fun `gate shows a never-seen reason`() {
        assertTrue(UsageUpsellGate.shouldShow(null, null, nowMillis = 1_000L))
    }

    @Test
    fun `gate suppresses a reason seen within 24h`() {
        assertTrue(!UsageUpsellGate.shouldShow(1_000L, null, nowMillis = 1_000L + 1_000L))
    }

    @Test
    fun `gate re-shows after the 24h window`() {
        assertTrue(
            UsageUpsellGate.shouldShow(
                1_000L,
                null,
                nowMillis = 1_000L + UsageUpsellGate.WINDOW_MILLIS,
            ),
        )
    }

    @Test
    fun `gate suppresses a dont-show reason forever`() {
        assertTrue(!UsageUpsellGate.shouldShow(1_000L, 2_000L, nowMillis = 1_000L + 10 * UsageUpsellGate.WINDOW_MILLIS))
    }

    @Test
    fun `gate names every upsell reason`() {
        assertNotNull(UsageUpsellGate.keysFor(ChatStore.UsageUpsell.REASON_FREE_TIER_LIMIT))
        assertNotNull(UsageUpsellGate.keysFor(ChatStore.UsageUpsell.REASON_ACCOUNT_RATE_LIMIT))
        assertNotNull(UsageUpsellGate.keysFor(ChatStore.UsageUpsell.REASON_QUOTA_EXHAUSTED))
        assertNull(UsageUpsellGate.keysFor("mystery_reason"))
    }

    @Test
    fun `gate keys are stable per reason`() {
        assertEquals(
            UsageUpsellGate.keysFor(ChatStore.UsageUpsell.REASON_FREE_TIER_LIMIT),
            UsageUpsellGate.keysFor(ChatStore.UsageUpsell.REASON_FREE_TIER_LIMIT),
        )
        assertTrue(
            UsageUpsellGate.keysFor(ChatStore.UsageUpsell.REASON_FREE_TIER_LIMIT) !=
                UsageUpsellGate.keysFor(ChatStore.UsageUpsell.REASON_ACCOUNT_RATE_LIMIT),
        )
    }

    // ---- reduce: set / keep / clear --------------------------------------------

    @Test
    fun `retry frame with Go action sets usageUpsell`() {
        val next = ChatStore.reduce(
            ChatStore.ChatState(),
            ChatStore.Event.RunStatusChanged(
                ChatStore.RunStatus.RUNNING,
                retry = retryAction("free_tier_limit"),
            ),
        )
        assertEquals("free_tier_limit", next.usageUpsell!!.reason)
        assertNotNull(next.retry)
    }

    @Test
    fun `unrelated event keeps a dismissal`() {
        val dismissed = ChatStore.ChatState(
            runStatus = ChatStore.RunStatus.RUNNING,
            usageUpsell = null,
        )
        val next = ChatStore.reduce(dismissed, ChatStore.Event.PermissionSnapshot(emptyList()))
        assertNull(next.usageUpsell)
    }

    @Test
    fun `unrelated event keeps a showing upsell`() {
        val showing = ChatStore.ChatState(
            runStatus = ChatStore.RunStatus.RUNNING,
            usageUpsell = ChatStore.UsageUpsell("free_tier_limit", "m", null),
        )
        val next = ChatStore.reduce(showing, ChatStore.Event.PermissionSnapshot(emptyList()))
        assertEquals("free_tier_limit", next.usageUpsell!!.reason)
    }

    @Test
    fun `idle clears the upsell`() {
        val showing = ChatStore.ChatState(
            runStatus = ChatStore.RunStatus.RUNNING,
            usageUpsell = ChatStore.UsageUpsell("free_tier_limit", "m", null),
        )
        val next = ChatStore.reduce(showing, ChatStore.Event.RunStatusChanged(ChatStore.RunStatus.IDLE))
        assertNull(next.usageUpsell)
    }

    @Test
    fun `error frame with 429 sets the upsell`() {
        val next = ChatStore.reduce(
            ChatStore.ChatState(),
            ChatStore.Event.RunStatusChanged(
                ChatStore.RunStatus.ERROR,
                error = "HTTP 429 已达到 Token Plan 用量上限",
            ),
        )
        assertNotNull(next.usageUpsell)
    }

    @Test
    fun `bracket quota line raises upsell with the bracket reason`() {
        val upsell = ChatStore.UsageUpsell.fromError(
            "HTTP 429 [rate_limit_error] 已达到 Token Plan 用量上限：请升级 Token Plan 套餐或购买积分补充用量。 (2056)",
            null,
        )
        assertNotNull(upsell)
        assertEquals("rate_limit_error", upsell!!.reason)
    }

    // ---- link passthrough -------------------------------------------------------

    @Test
    fun `retry action link rides the upsell`() {
        val upsell = ChatStore.UsageUpsell.fromRetry(retryAction("free_tier_limit"), nowMillis = 1_000L)
        assertEquals("https://example.invalid/sub", upsell!!.link)
    }

    @Test
    fun `generic quota bucket carries no link`() {
        val retry = ChatStore.RetryInfo(
            attempt = 1,
            next = 0L,
            message = "已达到 Token Plan 用量上限：请升级 Token Plan 套餐或购买积分补充用量。",
            action = null,
        )
        val upsell = ChatStore.UsageUpsell.fromRetry(retry, nowMillis = 1_000L)
        assertNull(upsell!!.link)
    }

    // ---- source pins ------------------------------------------------------------

    private fun source(relative: String): String =
        listOf(
            File("src/main/java/com/xiufeigo/opencodeshell/$relative"),
            File("app/src/main/java/com/xiufeigo/opencodeshell/$relative"),
        ).firstOrNull { it.isFile }?.readText() ?: error("missing $relative")

    private fun res(relative: String): String =
        listOf(
            File("src/main/res/$relative"),
            File("app/src/main/res/$relative"),
        ).firstOrNull { it.isFile }?.readText() ?: error("missing res/$relative")

    @Test
    fun `dialog keeps its contract`() {
        val dialog = source("ui/overlay/UsageExceededDialog.kt")
        assertTrue("composable", dialog.contains("fun UsageExceededDialog("))
        assertTrue("upsell param", dialog.contains("upsell: ChatStore.UsageUpsell"))
        assertTrue("dismiss param", dialog.contains("onDismiss: () -> Unit"))
        assertTrue("free tier title", dialog.contains("oc_usage_exceeded_free_tier_title"))
        assertTrue("rate limit title", dialog.contains("oc_usage_exceeded_rate_limit_title"))
        assertTrue("quota title", dialog.contains("oc_usage_exceeded_quota_title"))
        assertTrue("dont show again", dialog.contains("oc_usage_exceeded_dont_show_again"))
        assertTrue("no SessionScreen coupling", !dialog.contains("SessionScreen"))
    }

    @Test
    fun `dialog persists and acts before dismissing`() {
        // `go-upsell` parity: first show stamps lastSeen (24 h snooze),
        // "don't show again" stamps dontShow, and the primary button opens
        // the Go action link in the system browser — all before `onDismiss`.
        val dialog = source("ui/overlay/UsageExceededDialog.kt")
        assertTrue("stamps shown", dialog.contains("UsageUpsellGate.markShown"))
        assertTrue("stamps dont-show", dialog.contains("UsageUpsellGate.markDontShow"))
        assertTrue("opens link", dialog.contains("uriHandler.openUri"))
        assertTrue("reads link", dialog.contains("upsell.link"))
    }

    @Test
    fun `viewmodel exposes dismissUsageUpsell`() {
        val vm = source("ChatViewModel.kt")
        assertTrue("dismiss", vm.contains("fun dismissUsageUpsell()"))
        assertTrue("clears", vm.contains("copy(usageUpsell = null)"))
    }

    @Test
    fun `all three locales carry the dialog copy`() {
        val keys = listOf(
            "oc_usage_exceeded_free_tier_title",
            "oc_usage_exceeded_free_tier_description",
            "oc_usage_exceeded_free_tier_action",
            "oc_usage_exceeded_rate_limit_title",
            "oc_usage_exceeded_rate_limit_description",
            "oc_usage_exceeded_rate_limit_action",
            "oc_usage_exceeded_quota_title",
            "oc_usage_exceeded_quota_description",
            "oc_usage_exceeded_close",
            "oc_usage_exceeded_dont_show_again",
            "oc_usage_exceeded_retry_after",
        )
        for (file in listOf("values/strings.xml", "values-zh-rCN/strings.xml", "values-zh-rTW/strings.xml")) {
            val text = res(file)
            for (key in keys) assertTrue("$file:$key", text.contains("name=\"$key\""))
        }
    }
}
