package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.summary.SessionStatsModel
import com.xiufeigo.opencodeshell.v2.SessionStatsInfo
import com.xiufeigo.opencodeshell.v2.V2Api
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-logic coverage for the `SessionStats.Info` projection
 * (`experimental/session/stats` → the summary card's rows, tools totals and
 * per-model list).
 */
class SessionStatsModelTest {

    private fun stats(
        sessions: Int = 2,
        subagents: Int = 1,
        prompts: Int = 10,
        steps: Int = 40,
        cost: Double = 1.25,
        activeDays: Int = 3,
        streak: Int = 2,
        totals: SessionStatsInfo.ToolsTotals? = SessionStatsInfo.ToolsTotals(
            calls = 12,
            succeeded = 10,
            failed = 1,
            unfinished = 1,
        ),
        usage: List<SessionStatsInfo.ToolUsage> = emptyList(),
        models: List<SessionStatsInfo.ModelUsage> = emptyList(),
    ) = SessionStatsInfo(
        rangeFrom = 1L,
        rangeTo = 2L,
        sessions = sessions,
        subagents = subagents,
        prompts = prompts,
        steps = steps,
        tokens = SessionStatsInfo.Tokens(
            input = 1_000,
            output = 2_000,
            reasoning = 300,
            cacheRead = 4_000,
            cacheWrite = 500,
        ),
        cost = cost,
        toolsMode = "summary",
        toolsTotals = totals,
        toolsUsage = usage,
        activeDays = activeDays,
        streak = streak,
        activity = emptyList(),
        models = models,
        raw = JSONObject(),
    )

    @Test
    fun rowsCarryEveryCounterGroupedWithUsSeparators() {
        val snapshot = SessionStatsModel.snapshot(stats())
        val byKind = snapshot.rows.associate { it.kind to it.value }
        assertEquals("2", byKind[SessionStatsModel.Kind.SESSIONS])
        assertEquals("1", byKind[SessionStatsModel.Kind.SUBAGENTS])
        assertEquals("10", byKind[SessionStatsModel.Kind.PROMPTS])
        assertEquals("40", byKind[SessionStatsModel.Kind.STEPS])
        assertEquals("1,000", byKind[SessionStatsModel.Kind.INPUT_TOKENS])
        assertEquals("2,000", byKind[SessionStatsModel.Kind.OUTPUT_TOKENS])
        assertEquals("300", byKind[SessionStatsModel.Kind.REASONING_TOKENS])
        assertEquals("4,000 / 500", byKind[SessionStatsModel.Kind.CACHE_TOKENS])
        assertEquals("$1.2500", byKind[SessionStatsModel.Kind.COST])
        assertEquals("3", byKind[SessionStatsModel.Kind.ACTIVE_DAYS])
        assertEquals("2", byKind[SessionStatsModel.Kind.STREAK])
    }

    @Test
    fun toolsTotalsPassThroughAndVanishWithModeNone() {
        val withTotals = SessionStatsModel.snapshot(stats()).tools
        assertNotNull(withTotals)
        assertEquals("12", withTotals?.calls)
        assertEquals("1", withTotals?.failed)
        assertNull(SessionStatsModel.snapshot(stats(totals = null)).tools)
    }

    @Test
    fun modelsLabelFallsBackToTheProviderIdAndFormatsStepsAndCost() {
        val snapshot = SessionStatsModel.snapshot(
            stats(
                models = listOf(
                    SessionStatsInfo.ModelUsage(
                        model = V2Api.ModelRef(id = "gpt-5", providerID = "openai"),
                        steps = 1200,
                        tokens = SessionStatsInfo.Tokens(0, 0, 0, 0, 0),
                        cost = 0.5,
                    ),
                    SessionStatsInfo.ModelUsage(
                        model = V2Api.ModelRef(id = "", providerID = "anthropic"),
                        steps = 3,
                        tokens = SessionStatsInfo.Tokens(0, 0, 0, 0, 0),
                        cost = 0.0,
                    ),
                    SessionStatsInfo.ModelUsage(
                        model = null,
                        steps = 1,
                        tokens = SessionStatsInfo.Tokens(0, 0, 0, 0, 0),
                        cost = 0.0,
                    ),
                ),
            ),
        )
        assertEquals(listOf("gpt-5", "anthropic", SessionStatsModel.DASH), snapshot.models.map { it.label })
        assertEquals(listOf("1,200", "3", "1"), snapshot.models.map { it.steps })
        assertEquals("$0.5000", snapshot.models.first().cost)
    }

    @Test
    fun numberGroupsAndDashesAbsentValues() {
        assertEquals("1,234", SessionStatsModel.number(1234L))
        assertEquals("0", SessionStatsModel.number(0L))
        assertEquals(SessionStatsModel.DASH, SessionStatsModel.number(null))
    }
}
