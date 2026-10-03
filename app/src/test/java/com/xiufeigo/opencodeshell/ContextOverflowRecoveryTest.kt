package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.header.ContextUsage
import com.xiufeigo.opencodeshell.ui.session.timeline.ContextOverflowAction
import com.xiufeigo.opencodeshell.ui.session.timeline.TimelineRows
import com.xiufeigo.opencodeshell.ui.session.timeline.contextOverflowActions
import com.xiufeigo.opencodeshell.ui.session.timeline.errorCopy
import com.xiufeigo.opencodeshell.ui.session.timeline.isContextOverflowText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The actionable recovery of an over-limit failure (device v19 P2,
 * `research/v19_verify/b/`): the session was stuck with
 * 「Something went wrong — The request contains…」 and nothing the user could do
 * about it, and the manual `POST /api/session/{id}/compact` that followed did not
 * bring the token count down either (its own summary request carried the same
 * over-long history).
 *
 * Why the diagnosis needs two inputs: the provider's rejection is stored as a
 * bare `provider.invalid-request` — `to-session-error.ts:26-27` keeps only that
 * type and drops the `classification: "context-overflow"` that
 * `provider-error.ts:143-150` had attached — so the stored error alone cannot be
 * told apart from any other malformed request. What identifies the v19 failure is
 * the **ratio** the context ring paints: `input=643K` + `cache_read=1.3M` against
 * a 1,048,576 window.
 */
class ContextOverflowRecoveryTest {

    private val limit = 1_048_576

    /** The v19 numerator, as `GET /api/session/{id}/context` reports it. */
    private val v19Tokens = 1_943_000L

    /** The v19 human line, verbatim from the screenshot. */
    private val v19Message =
        "The request contains invalid parameters. Check the request body for any errors or inconsistencies."

    /** The stored `session.error` payload behind it (type + status survive the strip). */
    private val v19Detail =
        """{"type":"provider.invalid-request","message":"$v19Message","status":400}"""

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    /**
     * Same, plus the repo root: the i18n dictionaries (`research/_i18n_v2/`) sit
     * one level above the module directory the tests run in.
     */
    private fun repoSource(relative: String): String =
        listOf(File(relative), File("app/$relative"), File("../$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    // ------------------------------------------------------------- diagnosis

    @Test
    fun theV19FailureIsDiagnosedAsAnOverflow() {
        assertEquals(
            listOf(ContextOverflowAction.COMPACT, ContextOverflowAction.REVERT),
            contextOverflowActions(v19Message, v19Detail, v19Tokens, limit),
        )
    }

    @Test
    fun theActionsAreOrderedCompactionFirstThenTheEscape() {
        // The order is the presentation: a compaction is the cheap attempt, the
        // revert is the escape that still works once the window is full.
        val actions = contextOverflowActions(v19Message, v19Detail, v19Tokens, limit)
        assertEquals(ContextOverflowAction.COMPACT, actions[0])
        assertEquals(ContextOverflowAction.REVERT, actions[1])
    }

    @Test
    fun theSameInvalidRequestBelowTheThresholdIsNotAnOverflow() {
        // A malformed request against a half-empty window is just that: the card
        // keeps its generic title and offers nothing it cannot deliver.
        assertTrue(contextOverflowActions(v19Message, v19Detail, 100_000L, limit).isEmpty())
        assertTrue(contextOverflowActions(v19Message, v19Detail, 838_860L, limit).isEmpty())
    }

    @Test
    fun anUnknownRatioCannotDiagnoseTheFailure() {
        // Either half unknown ⇒ no diagnosis, exactly like the automatic trigger
        // (`ContextCompactionPolicy.overThreshold`): an unknown usage never
        // fabricates one.
        assertTrue(contextOverflowActions(v19Message, v19Detail, null, limit).isEmpty())
        assertTrue(contextOverflowActions(v19Message, v19Detail, v19Tokens, null).isEmpty())
        assertTrue(contextOverflowActions(v19Message, v19Detail, null, null).isEmpty())
    }

    @Test
    fun aProvidersOwnOverflowPhraseNeedsNoRatio() {
        // Upstream's own patterns (`provider-error.ts:16-44`): the provider has
        // named the problem, so the ratio is not needed.
        assertTrue(
            contextOverflowActions(
                "prompt is too long: 1300000 tokens > 1000000 maximum",
                null,
                null,
                null,
            ).isNotEmpty(),
        )
        assertTrue(
            contextOverflowActions(
                "This model's maximum context length is 1048576 tokens. However, you requested 1300000 tokens.",
                null,
                null,
                null,
            ).isNotEmpty(),
        )
        assertTrue(
            contextOverflowActions("400 [context_length_exceeded] reduce the length of the messages", null, null, null)
                .isNotEmpty(),
        )
    }

    @Test
    fun aThrottleThatMentionsTokensIsNotAnOverflow() {
        // `provider-error.ts:48`'s exclusions: throttling shares the vocabulary
        // but not the remedy.
        assertFalse(isContextOverflowText("Throttling error: too many tokens"))
        assertTrue(contextOverflowActions("Throttling error: too many tokens", null, v19Tokens, limit).isEmpty())
    }

    @Test
    fun quotaAndRegionKeepTheirOwnAdvice() {
        // A 429 that fires while the window is full is still a 429.
        val quota = """{"type":"provider.quota","message":"Plan quota exhausted","status":429}"""
        assertTrue(contextOverflowActions("Plan quota exhausted", quota, v19Tokens, limit).isEmpty())
        val region = """{"type":"provider.region","message":"Not available in your country","status":403}"""
        assertTrue(contextOverflowActions("Not available in your country", region, v19Tokens, limit).isEmpty())
    }

    @Test
    fun anUnrelatedFailureOffersNothing() {
        assertTrue(contextOverflowActions("Connection reset", null, v19Tokens, limit).isEmpty())
        assertTrue(contextOverflowActions(null, null, v19Tokens, limit).isEmpty())
    }

    // ------------------------------------------------- the legacy banner (v21 P3)

    /**
     * The two surfaces of one failure must wear the same copy. The legacy
     * `ChatView` banner only knew the quota/region buckets, so an over-limit
     * rejection — which the server stores as a bare `provider.invalid-request`,
     * i.e. `OTHER` — fell through to 「出错了」 + the raw provider line while the
     * Compose card beside it said 「上下文超限」 and told the user what to do.
     * Both now read [errorCopy], so the ladder cannot drift again.
     */
    @Test
    fun theLegacyBannerTitlesAnOverLimitFailureLikeTheCard() {
        val classified = ChatStore.classifyStoredError(v19Message, v19Detail)
        val overflow = errorCopy(classified, overflow = true)
        assertEquals(R.string.oc_error_overflow_title, overflow.titleRes)
        assertEquals(R.string.oc_error_overflow_advice, overflow.adviceRes)
        // …and the same failure below the threshold keeps the generic copy with
        // the raw line and no advice (the pre-P3 behaviour, now explicit).
        val generic = errorCopy(classified, overflow = false)
        assertEquals(R.string.oc_error_title, generic.titleRes)
        assertNull(generic.adviceRes)
    }

    @Test
    fun quotaAndRegionKeepTheirOwnCopy() {
        // An over-limit verdict can never describe either kind
        // (`contextOverflowActions` returns nothing for them), and their own
        // advice is the actionable one.
        val quota = errorCopy(
            ChatStore.classifyStoredError("Plan quota exhausted", """{"type":"provider.quota","status":429}"""),
            overflow = false,
        )
        assertEquals(R.string.oc_error_quota_title, quota.titleRes)
        assertEquals(R.string.oc_error_quota_advice, quota.adviceRes)
        val region = errorCopy(
            ChatStore.classifyStoredError("Not available in your country", """{"type":"provider.region","status":403}"""),
            overflow = false,
        )
        assertEquals(R.string.oc_error_region_title, region.titleRes)
        assertEquals(R.string.oc_error_region_advice, region.adviceRes)
    }

    @Test
    fun theLocationMissingCopyExistsInAllThreeLocales() {
        // The v2 dictionaries are the source (`scripts/gen_i18n.ps1` expands
        // them into `strings_oc_i18n.xml` per locale) — a hand-written resource
        // would be overwritten by the next `gen` run, so the pair has to be in
        // the JSON *and* in every generated file.
        listOf("en", "zh", "zht").forEach { locale ->
            val json = repoSource("research/_i18n_v2/$locale.json")
            assertTrue("$locale json title", json.contains("\"error.locationMissing.title\""))
            assertTrue("$locale json advice", json.contains("\"error.locationMissing.advice\""))
        }
        listOf("values", "values-zh-rCN", "values-zh-rTW").forEach { dir ->
            val xml = repoSource("app/src/main/res/$dir/strings_oc_i18n.xml")
            assertTrue("$dir title", xml.contains("""<string name="error_locationMissing_title">"""))
            assertTrue("$dir advice", xml.contains("""<string name="error_locationMissing_advice">"""))
        }
    }

    @Test
    fun aDeletedProjectDirectoryGetsItsOwnCopy() {
        // v31 BUG-3: `500 PlatformError: NotFound: FileSystem.realPath` is not a
        // provider failure at all — the session's directory is gone. It used to
        // reach the card as a bare 「HTTP 500」; it now says what is missing and
        // what to do about it, on both surfaces (`errorCopy` is shared with the
        // legacy `ChatView` banner). Upstream names the same failure with
        // `session.location.unavailable` / `.description`
        // (`tui/…/session/location-missing.tsx`).
        val copy = errorCopy(
            ChatStore.classifyStoredError(
                "HTTP 500 [NotFound] PlatformError: NotFound: FileSystem.realPath",
                null,
            ),
            overflow = false,
        )
        assertEquals(R.string.error_locationMissing_title, copy.titleRes)
        assertEquals(R.string.error_locationMissing_advice, copy.adviceRes)
    }

    /**
     * The denominator is the **effective window** `min(limit.input,
     * limit.context)` ([ContextUsage.windowOf]) on the legacy surface too: the
     * v20 reading (619,741 tokens on `opencode-go/space-bunny-free`) is over the
     * 524,288 the server compacts on, but *below* the 80 % of the advertised
     * 1,048,576 — the raw `limit.context` would keep the banner's generic title.
     */
    @Test
    fun theLegacyBannerDiagnosesOnTheEffectiveWindow() {
        val rawContext = 1_048_576
        val effective = ContextUsage.windowOf(rawContext, 524_288)!!
        assertEquals(524_288, effective)
        assertTrue(contextOverflowActions(v19Message, v19Detail, v19Tokens, effective).isNotEmpty())
        assertTrue(contextOverflowActions(v19Message, v19Detail, 619_741L, effective).isNotEmpty())
        assertTrue(contextOverflowActions(v19Message, v19Detail, 619_741L, rawContext).isEmpty())
    }

    @Test
    fun theLegacyBannerReadsTheViewModelsWindowAndTheCardsCopy() {
        val view = source("src/main/java/com/xiufeigo/opencodeshell/ChatView.kt")
        assertTrue(view.contains("errorCopy(classified, overflow)"))
        assertTrue(view.contains("contextOverflowActions("))
        // Both halves are the view model's own — `contextLimit` is the catalog's
        // effective window (`ContextUsage.windowOf`), and the legacy view never
        // reaches for a raw `limitContext`/`limit.context` of its own.
        assertTrue(view.contains("viewModel.activeContextTokens.value"))
        assertTrue(view.contains("viewModel.contextLimit.value"))
        assertFalse(view.contains("limitContext"))
        // The bucket ladder is gone from the legacy view — one chooser, not two.
        assertFalse(view.contains("oc_error_quota_title"))
        assertFalse(view.contains("oc_error_region_title"))
        // The halves land asynchronously, so the banner re-evaluates on them.
        assertTrue(view.contains("viewModel.activeContextTokens.collect { renderErrorBanner(latestState) }"))
        assertTrue(view.contains("viewModel.contextLimit.collect { renderErrorBanner(latestState) }"))

        val card = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/timeline/SessionErrorCard.kt")
        assertTrue(card.contains("errorCopy(classified, overflowActions.isNotEmpty())"))
    }

    // ------------------------------------------------------------ the copy

    @Test
    fun everyLocaleCarriesTheRecoveryCopy() {
        fun value(dir: String, name: String): String =
            Regex("""<string name="$name">(.*?)</string>""")
                .find(source("src/main/res/$dir/strings.xml"))
                ?.groupValues
                ?.get(1)
                ?: error("$dir missing $name")

        val locales = listOf("values", "values-zh-rCN", "values-zh-rTW")
        for (dir in locales) {
            assertTrue("$dir title", value(dir, "oc_error_overflow_title").isNotBlank())
            assertTrue("$dir advice", value(dir, "oc_error_overflow_advice").isNotBlank())
            assertTrue("$dir compact row", value(dir, "oc_error_overflow_compact").isNotBlank())
            assertTrue("$dir revert row", value(dir, "oc_error_overflow_revert").isNotBlank())
            // The two rows must stay distinguishable — they are different writes.
            assertNotEquals(
                "$dir: the two recovery rows must not read the same",
                value(dir, "oc_error_overflow_compact"),
                value(dir, "oc_error_overflow_revert"),
            )
        }
        assertEquals("Context window exceeded", value("values", "oc_error_overflow_title"))
        assertEquals("上下文超限", value("values-zh-rCN", "oc_error_overflow_title"))
        assertEquals("上下文超限", value("values-zh-rTW", "oc_error_overflow_title"))
    }

    @Test
    fun everyLocaleSeparatesAFailedCompactionFromACompletedOne() {
        fun value(dir: String, name: String): String =
            Regex("""<string name="$name">(.*?)</string>""")
                .find(source("src/main/res/$dir/strings.xml"))
                ?.groupValues
                ?.get(1)
                ?: error("$dir missing $name")

        for (dir in listOf("values", "values-zh-rCN", "values-zh-rTW")) {
            // The v19 screenshot showed "Session compacted" for a compaction whose
            // own summary request had just been rejected.
            assertNotEquals(
                "$dir: a failed compaction must not read as a completed one",
                value(dir, "oc_compaction"),
                value(dir, "oc_compaction_failed"),
            )
        }
        assertEquals("Session compaction failed", value("values", "oc_compaction_failed"))
        assertEquals("会话压缩失败", value("values-zh-rCN", "oc_compaction_failed"))
        assertEquals("工作階段壓縮失敗", value("values-zh-rTW", "oc_compaction_failed"))
    }

    // ------------------------------------------------------- the presentation

    @Test
    fun theCardTakesItsActionsFromTheSessionActionsHost() {
        // `SessionScreen` calls `MessageTimeline` with a fixed argument list
        // (owned elsewhere), so the host published by `ui/App.kt` around the whole
        // session route is the only seam that can reach a card inside it — the
        // same one the session ⋮ menu uses.
        val card = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/timeline/SessionErrorCard.kt")
        assertTrue(card.contains("LocalSessionActionsHost.current"))
        // Both recovery rows are existing session writes, not new wire surface.
        assertTrue(card.contains("viewModel?.compactSession(onResult"))
        assertTrue(card.contains("viewModel?.undoSession()"))
        // …and the card must not offer them without a host to carry them out.
        assertTrue(card.contains("if (host == null)"))
    }

    @Test
    fun aFailedCompactionPartIsSettledAsAnErrorRow() {
        // The store keeps the settled status (`ChatStore.reduceCompaction` writes
        // `toolStatus = "error"` for the `failed` phase, and `compactionRow` does
        // the same for a durable `status: "failed"` row), and both places that
        // show the outcome read it through the store's one predicate — so the
        // part body and the `COMPACTION_END` divider cannot disagree (v21 P3:
        // the message-row path still said 「Session compacted」 after the v19
        // tool-part fix).
        val store = source("src/main/java/com/xiufeigo/opencodeshell/ChatStore.kt")
        assertTrue(store.contains("""fun compactionFailed(part: UiPart)"""))
        assertTrue(store.contains(""""error", COMPACTION_FAILED -> COMPACTION_FAILED"""))
        val renderer = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/timeline/PartRenderer.kt")
        assertTrue(renderer.contains("ChatStore.compactionErrorOf(part)"))
        assertTrue(renderer.contains("ChatStore.compactionRunning(part)"))
        val rows = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/timeline/TimelineRows.kt")
        assertTrue(rows.contains("ChatStore.compactionFailed(it)"))
        // …and one label chooser, shared by the part body and the divider.
        val renderers = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/timeline/ToolRenderers.kt")
        assertTrue(renderers.contains("internal fun compactionLabelRes(part: ChatStore.UiPart)"))
        assertTrue(renderers.contains("internal fun compactionLabelRes(failed: Boolean)"))
        val separator = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/timeline/TimelineSeparator.kt")
        assertTrue(separator.contains("stringResource(compactionLabelRes(separator.failed))"))
    }

    @Test
    fun theTimelineMarksTheFailedCompactionSeparator() {
        // The `COMPACTION_END` divider that closed the v19 failure said "Session
        // compacted"; the row it is built from knows better.
        val failed = TimelineRows.build(
            listOf(
                ChatStore.UiMessage(
                    id = "msg_1",
                    role = "assistant",
                    timeCreated = 1L,
                    parts = listOf(
                        ChatStore.UiPart(
                            id = "msg_1:notice",
                            type = "compaction",
                            text = "The request contains invalid parameters.",
                            toolStatus = "error",
                        ),
                    ),
                ),
            ),
        )
        val end = failed.last { it is TimelineRows.Row.Separator } as TimelineRows.Row.Separator
        assertEquals(TimelineRows.SeparatorKind.COMPACTION_END, end.kind)
        assertTrue("a rejected compaction must not read as a completed one", end.failed)

        // A settled (or still running) compaction keeps the upstream label.
        for (status in listOf("completed", "running")) {
            val settled = TimelineRows.build(
                listOf(
                    ChatStore.UiMessage(
                        id = "msg_1",
                        role = "assistant",
                        timeCreated = 1L,
                        parts = listOf(
                            ChatStore.UiPart(id = "p", type = "compaction", text = "summary", toolStatus = status),
                        ),
                    ),
                ),
            )
            val settledEnd = settled.last { it is TimelineRows.Row.Separator } as TimelineRows.Row.Separator
            assertFalse("$status must keep the completed label", settledEnd.failed)
        }
    }
}
