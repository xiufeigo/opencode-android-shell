package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.context.SessionContextModel
import com.xiufeigo.opencodeshell.ui.session.context.SessionContextModel.Kind
import com.xiufeigo.opencodeshell.ui.session.review.MobileUsage
import java.time.ZoneId
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B2 session context tab: the pure row/derivation rules of
 * `_upstream/packages/app/src/session/files/session-context-tab.tsx:173-193`
 * (sixteen stats), `:153-159` (system prompt) and `:54-81` (raw messages).
 */
class SessionContextModelTest {

    private val utc = ZoneId.of("UTC")
    private val en = Locale.US

    /** 2025-01-01T00:00:00Z — stable under `Locale.US` + UTC. */
    private val created = 1_735_689_600_000L

    // --------------------------------------------------------------- fixtures

    private fun session(
        id: String = "ses_1",
        title: String? = "Context work",
        cost: Double? = 1.2304,
        createdAt: Long = created,
    ): JSONObject = JSONObject().apply {
        put("id", id)
        title?.let { put("title", it) }
        cost?.let { put("cost", it) }
        put("time", JSONObject().put("created", createdAt))
    }

    /** One `GET /session/:id/message` row in this server's v1 `{info, parts}` shape. */
    private fun message(
        id: String,
        role: String,
        createdAt: Long = created,
        tokens: JSONObject? = null,
        providerID: String? = null,
        modelID: String? = null,
        texts: List<String> = emptyList(),
    ): JSONObject {
        val info = JSONObject()
            .put("id", id)
            .put("sessionID", "ses_1")
            .put("role", role)
            .put("time", JSONObject().put("created", createdAt))
        tokens?.let { info.put("tokens", it) }
        providerID?.let { info.put("providerID", it) }
        modelID?.let { info.put("modelID", it) }
        val parts = JSONArray()
        texts.forEach { parts.put(JSONObject().put("type", "text").put("text", it)) }
        return JSONObject().put("info", info).put("parts", parts)
    }

    private fun tokens(
        input: Long,
        output: Long,
        reasoning: Long = 0L,
        cacheRead: Long = 0L,
        cacheWrite: Long = 0L,
    ): JSONObject = JSONObject()
        .put("input", input)
        .put("output", output)
        .put("reasoning", reasoning)
        .put(
            "cache",
            JSONObject().put("read", cacheRead).put("write", cacheWrite),
        )

    /** `GET /provider` catalog with one `anthropic/claude-opus-4` model. */
    private fun catalog(
        contextLimit: Int? = 200_000,
        providerName: String = "Anthropic",
        modelName: String = "Opus 4",
    ): OpenCodeClient.Providers {
        val model = JSONObject().put("id", "claude-opus-4").put("name", modelName)
        if (contextLimit != null) model.put("limit", JSONObject().put("context", contextLimit))
        val provider = JSONObject()
            .put("id", "anthropic")
            .put("name", providerName)
            .put("models", JSONObject().put("claude-opus-4", model))
        return OpenCodeClient.Providers.fromJson(JSONObject().put("all", JSONArray().put(provider)))
    }

    private fun valueOf(snapshot: SessionContextModel.Snapshot, kind: Kind): String =
        snapshot.stats.first { it.kind == kind }.value

    // ------------------------------------------------------------------- rows

    @Test
    fun statsKeepTheUpstreamOrderOfAllSixteenRows() {
        val snapshot = SessionContextModel.project(session(), emptyList(), catalog(), utc, en)
        assertEquals(
            listOf(
                Kind.SESSION,
                Kind.MESSAGES,
                Kind.PROVIDER,
                Kind.MODEL,
                Kind.LIMIT,
                Kind.TOTAL_TOKENS,
                Kind.USAGE,
                Kind.INPUT_TOKENS,
                Kind.OUTPUT_TOKENS,
                Kind.REASONING_TOKENS,
                Kind.CACHE_TOKENS,
                Kind.USER_MESSAGES,
                Kind.ASSISTANT_MESSAGES,
                Kind.TOTAL_COST,
                Kind.SESSION_CREATED,
                Kind.LAST_ACTIVITY,
            ),
            snapshot.stats.map { it.kind },
        )
    }

    @Test
    fun statsReadTheLastAssistantMessageThatCarriesTokens() {
        // `findLast(item => item.type === "assistant" && !!item.tokens)` — the
        // trailing assistant message without a `tokens` object is skipped.
        val withTokens = message(
            id = "msg_1",
            role = "assistant",
            createdAt = created + 3_600_000L,
            tokens = tokens(input = 30_000, output = 5_000, reasoning = 0, cacheRead = 0, cacheWrite = 0),
            providerID = "anthropic",
            modelID = "claude-opus-4",
        )
        val without = message("msg_2", "assistant", createdAt = created + 7_200_000L)
        val snapshot = SessionContextModel.project(
            session(),
            listOf(message("msg_0", "user"), withTokens, without),
            catalog(),
            utc,
            en,
        )

        assertEquals("Anthropic", valueOf(snapshot, Kind.PROVIDER))
        assertEquals("Opus 4", valueOf(snapshot, Kind.MODEL))
        assertEquals("200,000", valueOf(snapshot, Kind.LIMIT))
        assertEquals("35,000", valueOf(snapshot, Kind.TOTAL_TOKENS))
        // 35000 / 200000 = 17.5 % → `Math.round` → 18 %.
        assertEquals("18%", valueOf(snapshot, Kind.USAGE))
        assertEquals("30,000", valueOf(snapshot, Kind.INPUT_TOKENS))
        assertEquals("5,000", valueOf(snapshot, Kind.OUTPUT_TOKENS))
        assertEquals("0", valueOf(snapshot, Kind.REASONING_TOKENS))
        assertEquals("0 / 0", valueOf(snapshot, Kind.CACHE_TOKENS))
        // Last activity is the token-bearing message, not the newer one.
        assertEquals(SessionContextModel.time(created + 3_600_000L, utc, en), valueOf(snapshot, Kind.LAST_ACTIVITY))
        assertNotEquals(SessionContextModel.time(created + 7_200_000L, utc, en), valueOf(snapshot, Kind.LAST_ACTIVITY))
    }

    @Test
    fun theModelCatalogSuppliesTheNamesAndTheWindow() {
        val assistant = message(
            "msg_1",
            "assistant",
            tokens = tokens(1, 1),
            providerID = "anthropic",
            modelID = "claude-opus-4",
        )
        // No catalog yet (`GET /provider` still in flight): raw ids, no window,
        // no percentage — exactly what upstream shows without `providers.all()`.
        val bare = SessionContextModel.project(session(), listOf(assistant), null, utc, en)
        assertEquals("anthropic", valueOf(bare, Kind.PROVIDER))
        assertEquals("claude-opus-4", valueOf(bare, Kind.MODEL))
        assertEquals(SessionContextModel.DASH, valueOf(bare, Kind.LIMIT))
        assertEquals(SessionContextModel.DASH, valueOf(bare, Kind.USAGE))
        // A catalog without `limit.context` keeps the names but no percentage.
        val noLimit = SessionContextModel.project(session(), listOf(assistant), catalog(contextLimit = null), utc, en)
        assertEquals("Opus 4", valueOf(noLimit, Kind.MODEL))
        assertEquals(SessionContextModel.DASH, valueOf(noLimit, Kind.LIMIT))
        assertEquals(SessionContextModel.DASH, valueOf(noLimit, Kind.USAGE))
    }

    @Test
    fun aMessageWithoutTokensLeavesEveryTokenRowUnavailable() {
        val snapshot = SessionContextModel.project(
            session(cost = null),
            listOf(message("msg_1", "user"), message("msg_2", "assistant")),
            null,
            utc,
            en,
        )
        listOf(
            Kind.PROVIDER,
            Kind.MODEL,
            Kind.LIMIT,
            Kind.TOTAL_TOKENS,
            Kind.USAGE,
            Kind.INPUT_TOKENS,
            Kind.OUTPUT_TOKENS,
            Kind.REASONING_TOKENS,
            Kind.LAST_ACTIVITY,
        ).forEach { kind ->
            assertEquals("$kind must render the placeholder", SessionContextModel.DASH, valueOf(snapshot, kind))
        }
        // Upstream still joins both halves: `${number(read)} / ${number(write)}`.
        assertEquals("— / —", valueOf(snapshot, Kind.CACHE_TOKENS))
        // A session the server never priced shows no price either (deviation from
        // upstream's `info()?.cost ?? 0`; see the model KDoc).
        assertEquals(SessionContextModel.DASH, valueOf(snapshot, Kind.TOTAL_COST))
    }

    @Test
    fun cacheTokensJoinTheReadAndWriteHalves() {
        val snapshot = SessionContextModel.project(
            session(),
            listOf(
                message(
                    "msg_1",
                    "assistant",
                    tokens = tokens(input = 1, output = 2, cacheRead = 1_024, cacheWrite = 2_048),
                    providerID = "anthropic",
                    modelID = "claude-opus-4",
                ),
            ),
            catalog(),
            utc,
            en,
        )
        assertEquals("1,024 / 2,048", valueOf(snapshot, Kind.CACHE_TOKENS))
        assertEquals("3,075", valueOf(snapshot, Kind.TOTAL_TOKENS))
    }

    @Test
    fun usageRoundsToTheNearestPercent() {
        assertEquals("33%", usagePercent(total = 100, limit = 300))
        assertEquals("33%", usagePercent(total = 1, limit = 3))
        assertEquals("67%", usagePercent(total = 2, limit = 3))
        // 12.5 % — `Math.round` rounds half up.
        assertEquals("13%", usagePercent(total = 1, limit = 8))
    }

    private fun usagePercent(total: Long, limit: Int): String {
        // `total` rides the input bucket; `limit` is the catalog's context window.
        val snapshot = SessionContextModel.project(
            session(),
            listOf(
                message(
                    "msg_1",
                    "assistant",
                    tokens = tokens(input = total, output = 0),
                    providerID = "anthropic",
                    modelID = "claude-opus-4",
                ),
            ),
            catalog(contextLimit = limit),
            utc,
            en,
        )
        return valueOf(snapshot, Kind.USAGE)
    }

    @Test
    fun countsSplitByRoleAndAreGrouped() {
        val messages = List(1_234) { message("m$it", "user") } +
            List(7) { message("a$it", "assistant") } +
            listOf(message("sys", "system", texts = listOf("prompt")))
        val snapshot = SessionContextModel.project(session(), messages, null, utc, en)
        assertEquals("1,242", valueOf(snapshot, Kind.MESSAGES))
        assertEquals("1,234", valueOf(snapshot, Kind.USER_MESSAGES))
        assertEquals("7", valueOf(snapshot, Kind.ASSISTANT_MESSAGES))
    }

    /**
     * P3 (v19 device): `User Messages` read `5` for a session whose prompts the
     * tester counted as `4`. The count is upstream's
     * (`session-context-tab.tsx:141-149`: `message.type === "user"`), so the two
     * shapes have to be pinned separately:
     *
     * - **v2** — the union has exactly one `user` type
     *   (`schema/src/session-message.ts:78-86`); every frame the session emits
     *   around a prompt (the `model-switched` record a switch writes, the
     *   `system` instructions row, the `idle` turn marker, `synthetic`/`shell`/
     *   `compaction`) is a *different* type and cannot be counted;
     * - **v1** — there is no `type` at all, only `role: "user" | "assistant"`
     *   (`schema/src/v1/session.ts:327-355`), and the injected records are
     *   `role: "user"` rows carrying `synthetic` text parts. Those are exactly
     *   what [SessionContextModel.isUserPrompt] refuses to count.
     */
    @Test
    fun userMessagesCountPromptsOnlyAndNeverTheNoticeFrames() {
        // ------------------------------------------------------------ v2 rows
        val v2 = listOf(
            JSONObject().put("id", "msg_u1").put("type", "user").put("text", "count the words"),
            JSONObject().put("id", "msg_a1").put("type", "assistant")
                .put("model", JSONObject().put("id", "muse").put("providerID", "opencode-go"))
                .put("content", JSONArray()),
            JSONObject().put("id", "msg_sw").put("type", "model-switched")
                .put("model", JSONObject().put("id", "muse").put("providerID", "opencode-go")),
            JSONObject().put("id", "msg_sys").put("type", "system")
                .put("metadata", JSONObject().put("notice", "instructions")).put("text", "tool catalog"),
            JSONObject().put("id", "msg_idle").put("type", "idle").put("outcome", "succeeded"),
            JSONObject().put("id", "msg_syn").put("type", "synthetic").put("text", "note"),
            JSONObject().put("id", "msg_shell").put("type", "shell").put("command", "ls"),
            JSONObject().put("id", "msg_comp").put("type", "compaction").put("summary", "…"),
            JSONObject().put("id", "msg_ag").put("type", "agent-switched").put("agent", "build"),
            JSONObject().put("id", "msg_loc").put("type", "location-switched"),
            JSONObject().put("id", "msg_skill").put("type", "skill").put("name", "pdf"),
            // The second prompt of the session — still one row, still counted.
            JSONObject().put("id", "msg_u2").put("type", "user").put("text", "and again"),
        )
        assertEquals(2, v2.count { SessionContextModel.isUserPrompt(it) })
        val snapshot = SessionContextModel.project(session(), v2, null, utc, en)
        assertEquals("12", valueOf(snapshot, Kind.MESSAGES))
        assertEquals("2", valueOf(snapshot, Kind.USER_MESSAGES))

        // ------------------------------------------------------- v1-shaped rows
        // An ordinary prompt: `role: "user"` with a real text part.
        val prompt = v1Row(
            "msg_1",
            JSONObject().put("type", "text").put("text", "count the words"),
        )
        // The same row a model switch / system reminder leaves: a synthetic text
        // part and nothing else (`v1-migration.bun.ts:326-347` re-types it
        // `synthetic`).
        val injected = v1Row(
            "msg_2",
            JSONObject().put("type", "text").put("text", "model switched").put("synthetic", true),
        )
        // A compaction envelope: v1 wraps it in a `role: "user"` row too
        // (`:277-312`).
        val compaction = v1Row("msg_3", JSONObject().put("type", "compaction"))
        // An attachment-only prompt is still a prompt (`:318-331`).
        val attachment = v1Row(
            "msg_4",
            JSONObject().put("type", "file").put("url", "data:text/plain;base64,aGk="),
            JSONObject().put("type", "text").put("text", "count the words"),
        )
        // An `@`-mention-only prompt is a prompt as well.
        val mention = v1Row("msg_5", JSONObject().put("type", "agent").put("name", "build"))
        // A row with no parts at all: nothing identifies it as a frame, so it
        // stays a prompt — the pre-existing behaviour of this count.
        val bare = v1Row("msg_6")

        val v1 = listOf(prompt, injected, compaction, attachment, mention, bare)
        assertEquals(
            listOf(true, false, false, true, true, true),
            v1.map { SessionContextModel.isUserPrompt(it) },
        )
        val v1Snapshot = SessionContextModel.project(session(), v1, null, utc, en)
        assertEquals("6", valueOf(v1Snapshot, Kind.MESSAGES))
        assertEquals("4", valueOf(v1Snapshot, Kind.USER_MESSAGES))
        // The frames are still rows in the raw list — only the count ignores them.
        assertEquals(6, v1Snapshot.messages.size)
    }

    /** One v1 `{info, parts}` row with an explicit part list (see [message]). */
    private fun v1Row(id: String, vararg parts: JSONObject): JSONObject {
        val info = JSONObject()
            .put("id", id)
            .put("sessionID", "ses_1")
            .put("role", "user")
            .put("time", JSONObject().put("created", created))
        val array = JSONArray()
        parts.forEach { array.put(it) }
        return JSONObject().put("info", info).put("parts", array)
    }

    @Test
    fun theSessionRowFallsBackToTheIdThenToNothing() {
        assertEquals(
            "Context work",
            valueOf(SessionContextModel.project(session(), emptyList(), null, utc, en), Kind.SESSION),
        )
        assertEquals(
            "ses_1",
            valueOf(
                SessionContextModel.project(session(title = null), emptyList(), null, utc, en),
                Kind.SESSION,
            ),
        )
        assertEquals(
            "ses_1",
            valueOf(
                SessionContextModel.project(session(title = "  "), emptyList(), null, utc, en),
                Kind.SESSION,
            ),
        )
        // No `info` at all (the pre-load state) — never a crash, never "null".
        assertEquals(
            SessionContextModel.DASH,
            valueOf(SessionContextModel.project(null, emptyList(), null, utc, en), Kind.SESSION),
        )
        assertEquals(
            SessionContextModel.DASH,
            valueOf(SessionContextModel.EMPTY, Kind.SESSION),
        )
        assertEquals(
            SessionContextModel.DASH,
            valueOf(SessionContextModel.project(JSONObject(), emptyList(), null, utc, en), Kind.SESSION),
        )
    }

    @Test
    fun theCostRowUsesTheSameCurrencyFormatAsTheMobileHeadline() {
        val snapshot = SessionContextModel.project(session(cost = 1.2304), emptyList(), null, utc, en)
        assertEquals("$1.2304", valueOf(snapshot, Kind.TOTAL_COST))
        assertEquals(MobileUsage.cost(1.2304), valueOf(snapshot, Kind.TOTAL_COST))
    }

    @Test
    fun sessionCreatedUsesTheLocalizedMediumDateTimePreset() {
        val snapshot = SessionContextModel.project(session(), emptyList(), null, utc, en)
        val createdRow = valueOf(snapshot, Kind.SESSION_CREATED)
        // `{year, month: short, day}` of luxon's `DATETIME_MED`.
        assertTrue("was: $createdRow", createdRow.startsWith("Jan 1, 2025"))
    }

    // ----------------------------------------------------------- raw messages

    @Test
    fun rawMessagesCarryTypeIdTimeAndTheTwoSpaceJson() {
        val rows = SessionContextModel.rawMessages(
            listOf(
                message("msg_1", "user", createdAt = created),
                message("msg_2", "assistant", createdAt = created + 60_000L, texts = listOf("hi")),
            ),
            utc,
            en,
        )
        assertEquals(listOf("msg_1", "msg_2"), rows.map { it.id })
        assertEquals("user \u2022 msg_1", rows[0].title)
        assertEquals("assistant \u2022 msg_2", rows[1].title)
        assertEquals(SessionContextModel.time(created, utc, en), rows[0].time)
        assertNotEquals(SessionContextModel.DASH, rows[0].time)
        // `JSON.stringify(message, null, 2)`: two-space indented, untouched payload.
        assertTrue(rows[1].json.startsWith("{\n  \""))
        assertTrue(rows[1].json.contains("\"id\": \"msg_2\""))
        assertTrue(rows[1].json.contains("\"text\": \"hi\""))
    }

    @Test
    fun rawMessagesOfMalformedRowsStillGetAStableUniqueIdAndPlaceholderTime() {
        val rows = SessionContextModel.rawMessages(
            listOf(JSONObject().put("junk", true), JSONObject().put("junk", 2), JSONObject()),
            utc,
            en,
        )
        assertEquals(listOf("message:0", "message:1", "message:2"), rows.map { it.id })
        assertEquals(3, rows.map { it.id }.toSet().size)
        assertEquals("unknown \u2022 message:0", rows[0].title)
        assertEquals(SessionContextModel.DASH, rows[0].time)
    }

    // ---------------------------------------------------------- system prompt

    @Test
    fun systemPromptIsTheLastNonBlankSystemMessage() {
        val v2 = JSONObject().put("id", "s1").put("type", "system").put("text", "first")
        val v1 = message("s2", "system", texts = listOf("second line", "more"))
        val snapshot = SessionContextModel.project(session(), listOf(v2, v1), null, utc, en)
        assertEquals("second line\nmore", snapshot.systemPrompt)
    }

    @Test
    fun systemPromptIsAbsentWhenMissingOrBlank() {
        assertNull(SessionContextModel.systemPrompt(listOf(message("msg_1", "user", texts = listOf("hi")))))
        assertNull(SessionContextModel.systemPrompt(listOf(message("s1", "system", texts = listOf("   ")))))
        assertNull(
            SessionContextModel.systemPrompt(
                listOf(JSONObject().put("id", "s1").put("type", "system").put("text", "\n \t")),
            ),
        )
        assertNull(SessionContextModel.systemPrompt(emptyList()))
        assertNull(SessionContextModel.project(session(), emptyList(), null, utc, en).systemPrompt)
    }

    // ----------------------------------------------------------- pure helpers

    @Test
    fun theNumberAndPercentHelpersUseTheDashPlaceholderAndUsGrouping() {
        assertEquals("—", SessionContextModel.number(null))
        assertEquals("0", SessionContextModel.number(0L))
        assertEquals("1,234", SessionContextModel.number(1_234L))
        assertEquals("—", SessionContextModel.percent(null))
        assertEquals("18%", SessionContextModel.percent(18))
    }

    @Test
    fun theTimeHelperTreatsAFalsyTimestampAsUnavailable() {
        assertEquals("—", SessionContextModel.time(null, utc, en))
        assertEquals("—", SessionContextModel.time(0L, utc, en))
        assertNotEquals("—", SessionContextModel.time(created, utc, en))
    }

    @Test
    fun theGridUsesTwoColumnsFromThirtyTwoRem() {
        assertEquals(1, SessionContextModel.columns(360))
        assertEquals(1, SessionContextModel.columns(SessionContextModel.TWO_COLUMN_MIN_WIDTH_DP - 1))
        assertEquals(2, SessionContextModel.columns(SessionContextModel.TWO_COLUMN_MIN_WIDTH_DP))
    }

    @Test
    fun anEmptyProjectionStillCarriesAllSixteenRows() {
        assertEquals(16, SessionContextModel.EMPTY.stats.size)
        assertTrue(SessionContextModel.EMPTY.messages.isEmpty())
        assertNull(SessionContextModel.EMPTY.systemPrompt)
    }

    @Test
    fun theScreenOwnLimitAndCostFillInWhenTheServerOmitsThem() {
        // v1 `GET /session/:id` carries no session-level `cost`, and the catalog
        // row for the message's model can be missing while the header ring still
        // shows a limit — both rows must then read the screen's own values rather
        // than reporting `—` next to a header that shows numbers.
        val bare = session(cost = null)
        val only = listOf(
            message(
                "msg_1",
                "assistant",
                tokens = tokens(30_000, 5_000),
                providerID = "anthropic",
                modelID = "claude-opus-4",
            ),
        )

        val without = SessionContextModel.project(bare, only, providers = null)
        assertEquals(SessionContextModel.DASH, valueOf(without, Kind.LIMIT))
        assertEquals(SessionContextModel.DASH, valueOf(without, Kind.TOTAL_COST))

        val filled = SessionContextModel.project(
            bare,
            only,
            providers = null,
            contextLimit = 100_000,
            sessionCost = 1.2304,
        )
        assertEquals("100,000", valueOf(filled, Kind.LIMIT))
        assertEquals("35%", valueOf(filled, Kind.USAGE))
        assertEquals(MobileUsage.cost(1.2304), valueOf(filled, Kind.TOTAL_COST))
    }

    @Test
    fun aPricedSessionWinsOverTheScreensCopy() {
        val priced = SessionContextModel.project(
            session(cost = 2.5),
            emptyList(),
            contextLimit = 100_000,
            sessionCost = 9.99,
        )
        assertEquals(MobileUsage.cost(2.5), valueOf(priced, Kind.TOTAL_COST))
        // The limit fallback is independent of the cost source.
        assertEquals("100,000", valueOf(priced, Kind.LIMIT))
        assertEquals(SessionContextModel.DASH, valueOf(priced, Kind.USAGE))
    }
}
