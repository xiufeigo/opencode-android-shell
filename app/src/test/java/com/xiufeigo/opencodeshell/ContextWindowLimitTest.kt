package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.context.SessionContextModel
import com.xiufeigo.opencodeshell.ui.session.context.SessionContextModel.Kind
import com.xiufeigo.opencodeshell.ui.session.header.ContextUsage
import com.xiufeigo.opencodeshell.ui.settings.formatContextLimit
import com.xiufeigo.opencodeshell.ui.settings.modelRows
import com.xiufeigo.opencodeshell.v2.V2Api
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * The ring's denominator after the v20 device finding (P1,
 * `research/v20_verify/panel_compact/`): `Context Limit 1,048,576` / `Usage 59 %`
 * on `opencode-go/space-bunny-free`, while the server compacts on
 * `limit.input - buffer` = **504,288** and had already done so at 619,741 tokens
 * (`v20_panel_619k.png`).
 *
 * Measured live (`GET /api/model`, 2026-09-26, dumped to
 * `research/v20_verify/regress/models_dir.json`; re-confirmed with
 * `curl -u opencode:opencode http://127.0.0.1:4095/api/model` → `200`,
 * 113,037 bytes):
 *
 * ```json
 * {"id":"space-bunny-free","modelID":"space-bunny-free","providerID":"opencode-go",
 *  "limit":{"context":1048576,"input":524288,"output":524288}}
 * ```
 *
 * 45 of that catalog's 129 entries carry `input < context`; 74 omit `limit.input`
 * entirely; 4 report `{"context":0,"input":0,"output":0}`. Upstream's own trigger
 * (`_upstream/packages/core/src/session/compaction.ts:742-763`) reads the input
 * half first:
 *
 * ```ts
 * const promptCeiling = Math.min(limit.input - config.buffer, context - Math.max(output, config.buffer))
 * ```
 *
 * So the effective window is `min(limit.input, limit.context)`, and the ring, the
 * header percent, the context tab's `Context Limit` / `Usage` rows and the
 * client's pre-emptive compaction all read it from one place
 * ([ContextUsage.windowOf]).
 */
class ContextWindowLimitTest {

    /** The v20 model's advertised halves — the numbers the device showed. */
    private val bunnyContext = 1_048_576
    private val bunnyInput = 524_288
    private val bunnyOutput = 524_288

    /** The `GET /api/model` row as the wire sent it (the `limit` object verbatim). */
    private val bunnyRow = """
        {"id":"space-bunny-free","modelID":"space-bunny-free","providerID":"opencode-go",
         "name":"Space Bunny Free","limit":{"context":1048576,"input":524288,"output":524288}}
    """.trimIndent()

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    // ------------------------------------------------------- the pure min rule

    @Test
    fun theWindowIsTheSmallerOfTheTwoHalves() {
        // The v20 case: 524,288, not 1,048,576.
        assertEquals(524_288, ContextUsage.windowOf(bunnyContext, bunnyInput))
        // Never *wider* than the context: an input ceiling above it cannot raise
        // the window (`min`, not "prefer input").
        assertEquals(200_000, ContextUsage.windowOf(200_000, 900_000))
        // Equal halves (measured on 10 live entries) stay that value.
        assertEquals(256_000, ContextUsage.windowOf(256_000, 256_000))
    }

    @Test
    fun anAbsentInputHalfKeepsTheRawContext() {
        // 74 of 129 live entries omit `limit.input` — the field is optional
        // (`Model.limit.input?: number`), and upstream's `Infinity` branch is
        // exactly this reading.
        assertEquals(bunnyContext, ContextUsage.windowOf(bunnyContext, null))
        // A `0`/negative input is the wire's placeholder, not a window
        // (`{"context":0,"input":0,"output":0}` is measured on 4 live entries).
        assertEquals(bunnyContext, ContextUsage.windowOf(bunnyContext, 0))
        assertEquals(bunnyContext, ContextUsage.windowOf(bunnyContext, -1))
    }

    @Test
    fun anUnusableContextHasNoWindowAtAll() {
        // No fake denominator: the ring keeps its documented empty track.
        assertNull(ContextUsage.windowOf(null, bunnyInput))
        assertNull(ContextUsage.windowOf(0, bunnyInput))
        assertNull(ContextUsage.windowOf(-1, bunnyInput))
    }

    // --------------------------------------------------- the catalog lookup

    /**
     * One provider with the given entries, folded exactly like the VM does for
     * the v2 backend (`Model::fromV2` over `GET /api/model` rows — the wire the
     * live evidence came from).
     */
    private fun catalog(vararg rows: String): OpenCodeClient.Providers =
        OpenCodeClient.Providers(
            all = listOf(
                OpenCodeClient.Providers.Provider(
                    id = "opencode-go",
                    name = "OpenCode Go",
                    models = rows.map { OpenCodeClient.Providers.Model.fromV2(V2Api.ModelInfo.fromJson(JSONObject(it))) },
                ),
            ),
            connected = listOf("opencode-go"),
            default = emptyMap(),
        )

    /** The same shape off a **v1** document (`GET /provider`, `Model::fromJson`). */
    private fun v1Catalog(vararg rows: String): OpenCodeClient.Providers =
        OpenCodeClient.Providers(
            all = listOf(
                OpenCodeClient.Providers.Provider(
                    id = "opencode-go",
                    name = "OpenCode Go",
                    models = rows.mapNotNull { OpenCodeClient.Providers.Model.fromJson(JSONObject(it)) },
                ),
            ),
            connected = listOf("opencode-go"),
            default = emptyMap(),
        )

    /** The `limit.input` half as it lands on the DTO — the side table is gone. */
    private fun modelOf(row: String): OpenCodeClient.Providers.Model =
        OpenCodeClient.Providers.Model.fromV2(V2Api.ModelInfo.fromJson(JSONObject(row)))

    @Test
    fun theFoldKeepsTheInputHalfOfEveryRowThatHasOne() {
        assertEquals(524_288, modelOf(bunnyRow).limitInput)
        // A row without `limit.input` carries `null` — the lookup then falls back
        // to `limit.context` instead of a fabricated 0.
        val noInput = modelOf("""{"id":"mimo-v2.6-pro","modelID":"mimo-v2.6-pro","providerID":"xiaomi",
            "limit":{"context":1048576,"output":131072}}""")
        assertNull(noInput.limitInput)
        assertEquals(1_048_576, noInput.limitContext)
        // …and the v1 parse reads the same optional half.
        assertNull(OpenCodeClient.Providers.Model.fromJson(JSONObject("""{"id":"m","limit":{"context":200000}}"""))!!.limitInput)
        assertEquals(
            100_000,
            OpenCodeClient.Providers.Model.fromJson(JSONObject("""{"id":"m","limit":{"context":200000,"input":100000}}"""))!!.limitInput,
        )
    }

    @Test
    fun aJsonNullOrZeroInputIsNoCeiling() {
        // The wire is optional *and* nullable (`limit.input?: number`), and `0`
        // is a measured placeholder (`{"context":0,"input":0,"output":0}` on 4
        // live rows) — none of them may become a window.
        assertNull(modelOf("""{"id":"m","providerID":"p","limit":{"context":1048576,"input":null}}""").limitInput)
        assertNull(modelOf("""{"id":"m","providerID":"p","limit":{"context":1048576,"input":0}}""").limitInput)
        assertNull(modelOf("""{"id":"m","providerID":"p","limit":{"context":1048576,"input":-1}}""").limitInput)
        assertNull(OpenCodeClient.Providers.Model.fromJson(JSONObject("""{"id":"m","limit":{"context":1048576,"input":null}}"""))!!.limitInput)
        assertNull(OpenCodeClient.Providers.Model.fromJson(JSONObject("""{"id":"m","limit":{"context":1048576,"input":0}}"""))!!.limitInput)
        // A `limit` object that is absent entirely is the same story.
        assertNull(OpenCodeClient.Providers.Model.fromJson(JSONObject("""{"id":"m"}"""))!!.limitInput)
        // …and a JSON-null input still leaves the raw context as the window.
        val nullableInput = modelOf("""{"id":"m","providerID":"p","limit":{"context":1048576,"input":null}}""")
        assertEquals(1_048_576, ContextUsage.windowOf(nullableInput.limitContext, nullableInput.limitInput))
    }

    @Test
    fun theEntryCarriesItsOwnInputHalfUnderBothIdsARefMayCarry() {
        // `openai` lists one entry per service tier, all reporting the same
        // `modelID` — a message / session ref may name either id, and both must
        // resolve to *this* entry's pair, not a sibling tier's.
        val providers = catalog("""{"id":"gpt-6-sol-fast","modelID":"gpt-6-sol","providerID":"opencode-go",
            "limit":{"context":1050000,"input":922000,"output":128000}}""")
        assertEquals(922_000, ContextUsage.windowOf(providers, "opencode-go", "gpt-6-sol-fast"))
        assertEquals(922_000, ContextUsage.windowOf(providers, "opencode-go", "gpt-6-sol"))
    }

    @Test
    fun theLookupTakesBothHalvesOfTheOneEntryItResolved() {
        val providers = catalog(bunnyRow)
        assertEquals(524_288, ContextUsage.windowOf(providers, "opencode-go", "space-bunny-free"))
        // An entry without `limit.input` (v1, or the 74 live rows that omit it)
        // answers with its raw context — never a crash, never `null`.
        assertEquals(
            bunnyContext,
            ContextUsage.windowOf(v1Catalog("""{"id":"space-bunny-free","limit":{"context":1048576}}"""),
                "opencode-go", "space-bunny-free"),
        )
        // An unknown ref resolves to no entry ⇒ no window.
        assertNull(ContextUsage.windowOf(providers, "opencode-go", "gone"))
        assertNull(ContextUsage.windowOf(null, "opencode-go", "space-bunny-free"))
    }

    // --------------------------------------------------- the ring / header

    @Test
    fun theRingDividesByTheEffectiveWindow() {
        val providers = catalog(bunnyRow)
        val limit = ChatViewModel.contextLimitFrom(
            providers = providers,
            transcriptModel = "opencode-go" to "space-bunny-free",
            sessionModel = null,
            selectedModel = null,
        )
        assertEquals(524_288, limit)
        val limitLong = limit?.toLong()
        // The v20 panel: 619,741 tokens. The ring's own percent (upstream
        // `session-context-metrics.ts`, clamped) goes to 100 %; the tab's row
        // (upstream `session-context-tab.tsx`, deliberately *unclamped*) reads
        // 118 % — the same asymmetry upstream has, and the honest one here: the
        // session is past the ceiling.
        assertEquals(100, ContextUsage.percentOf(619_741L, limitLong))
        assertEquals(59, ContextUsage.percentOf(619_741L, bunnyContext.toLong()))
        assertEquals(1f, ContextUsage.progress(ContextUsage.percentOf(619_741L, limitLong)), 0.0001f)
        assertEquals(80, ContextUsage.percentOf(419_430L, limitLong))
    }

    @Test
    fun theSingleSourceCoreReadsTheSameCatalogField() {
        val providers = catalog(bunnyRow)
        assertEquals(524_288, ChatViewModel.contextLimitOf(providers, "opencode-go" to "space-bunny-free"))
        // A v1 catalog (no `limit.input`) keeps the raw context.
        assertEquals(
            bunnyContext,
            ChatViewModel.contextLimitOf(
                v1Catalog("""{"id":"space-bunny-free","limit":{"context":1048576}}"""),
                "opencode-go" to "space-bunny-free",
            ),
        )
    }

    // ------------------------------------------------------- the compaction

    @Test
    fun theServersOwnCeilingIsTheInputBranch() {
        // `min(limit.input - 20_000, context - max(min(524288, 32_000), 20_000))`
        // = min(504_288, 1_016_576) — the input branch binds, and it is the
        // trigger the device's 442K–620K auto-compactions came from.
        assertEquals(
            504_288L,
            ContextCompactionPolicy.upstreamCeiling(bunnyContext, bunnyOutput, bunnyInput),
        )
        // Without the input half the context branch stands alone (pre-v20
        // reading, and the 74 entries that omit the field).
        assertEquals(1_016_576L, ContextCompactionPolicy.upstreamCeiling(bunnyContext, bunnyOutput))
    }

    @Test
    fun theClientTriggerFiresBeforeTheServerWould() {
        val effective = ContextUsage.windowOf(bunnyContext, bunnyInput)!!.toLong()
        val client = (effective * ContextCompactionPolicy.DEFAULT_RATIO).toLong()
        val server = ContextCompactionPolicy.upstreamCeiling(bunnyContext, bunnyOutput, bunnyInput)!!
        assertEquals(419_430L, client)
        assertTrue("client $client must sit below the server's $server", client < server)
        // And the pre-v20 denominator would have waited for 838,861 — past the
        // server's own 504,288, i.e. the server always compacted first.
        assertEquals(838_860L, (bunnyContext * ContextCompactionPolicy.DEFAULT_RATIO).toLong())
        assertTrue((bunnyContext * ContextCompactionPolicy.DEFAULT_RATIO).toLong() > server)
        // The trigger's own gates read the same two halves.
        assertTrue(ContextCompactionPolicy.overThreshold(420_000L, effective.toInt()))
        assertTrue(ContextCompactionPolicy.hasHeadroom(420_000L, effective.toInt()))
        // Past the effective window a compaction could only be doomed.
        assertFalse(ContextCompactionPolicy.hasHeadroom(600_000L, effective.toInt()))
    }

    // ------------------------------------------------------------ the panel

    /** `GET /session/:id/message` row in this server's v1 `{info, parts}` shape. */
    private fun assistantRow(tokens: Long, providerID: String, modelID: String): JSONObject {
        val info = JSONObject()
            .put("id", "msg_1")
            .put("sessionID", "ses_1")
            .put("role", "assistant")
            .put("providerID", providerID)
            .put("modelID", modelID)
            .put(
                "tokens",
                JSONObject()
                    .put("input", tokens)
                    .put("output", 0)
                    .put("reasoning", 0)
                    .put("cache", JSONObject().put("read", 0).put("write", 0)),
            )
        return JSONObject().put("info", info).put("parts", JSONArray())
    }

    @Test
    fun theContextTabRowsFollowTheEffectiveWindow() {
        val row = assistantRow(619_741L, "opencode-go", "space-bunny-free")
        val snapshot = SessionContextModel.project(
            session = JSONObject().put("id", "ses_1"),
            messages = listOf(row),
            providers = catalog(bunnyRow),
            locale = Locale.US,
        )
        fun value(kind: Kind) = snapshot.stats.first { it.kind == kind }.value
        // The device read `1,048,576` / `59%`; both rows now carry the window the
        // server actually compacts on (the tab's percent is upstream's unclamped
        // `Math.round(total / limit * 100)`, hence 118 %).
        assertEquals("524,288", value(Kind.LIMIT))
        assertEquals("118%", value(Kind.USAGE))
        // A catalog whose entry omits `limit.input` (v1, or the 74 live rows
        // without it) keeps the upstream reading.
        val upstream = SessionContextModel.project(
            session = JSONObject().put("id", "ses_1"),
            messages = listOf(row),
            providers = v1Catalog("""{"id":"space-bunny-free","limit":{"context":1048576}}"""),
            locale = Locale.US,
        )
        assertEquals("1,048,576", upstream.stats.first { it.kind == Kind.LIMIT }.value)
        assertEquals("59%", upstream.stats.first { it.kind == Kind.USAGE }.value)
    }

    @Test
    fun theScreenFallbackIsStillTheHeaderRingValue() {
        // No catalog row for the message's model: the tab keeps the screen's own
        // effective window rather than `—` next to a ring that shows a number.
        val snapshot = SessionContextModel.project(
            session = JSONObject().put("id", "ses_1"),
            messages = listOf(assistantRow(262_144L, "opencode-go", "space-bunny-free")),
            providers = null,
            locale = Locale.US,
            contextLimit = 524_288,
        )
        assertEquals("524,288", snapshot.stats.first { it.kind == Kind.LIMIT }.value)
        assertEquals("50%", snapshot.stats.first { it.kind == Kind.USAGE }.value)
    }

    // --------------------------------------------------- the live catalog

    @Test
    fun theLiveCatalogSweepReproducesTheWindowRule() {
        // The measured `GET /api/model` answer (2026-09-26, 129 rows) replayed
        // through the production fold + lookup: every row's `limit` halves must
        // land on the DTO as sent, every row's window must be
        // `min(limit.input, limit.context)` with the documented fallbacks, and
        // the device's own model must land on 524,288. Skipped when the evidence
        // dump is not shipped alongside the sources.
        val dump = listOf(
            File("research/v20_verify/regress/models_dir.json"),
            File("../research/v20_verify/regress/models_dir.json"),
        ).firstOrNull { it.isFile }
        Assume.assumeTrue("v20 catalog dump not present", dump != null)
        val rows = JSONObject(dump!!.readText()).optJSONArray("data")
        Assume.assumeTrue("dump has no data array", rows != null)

        var carrying = 0
        var narrowed = 0
        var bunny: Int? = null
        for (i in 0 until rows!!.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val limit = row.optJSONObject("limit") ?: continue
            val context = limit.optInt("context", 0).takeIf { it > 0 }
            val input = limit.optInt("input", 0).takeIf { it > 0 }
            val where = "row $i (${row.optString("providerID")}/${row.optString("id")})"
            // The fold this change introduced: `Model::fromV2` reads the row's
            // own `raw` document, so all 129 real rows must carry the wire's
            // halves — no side table, no key to miss.
            val model = OpenCodeClient.Providers.Model.fromV2(V2Api.ModelInfo.fromJson(row))
            assertEquals("$where limit.context", context, model.limitContext)
            assertEquals("$where limit.input", input, model.limitInput)
            val expected = context?.let { minOf(it, input ?: it) }
            assertEquals(where, expected, ContextUsage.windowOf(context, input))
            assertEquals("$where window via the DTO", expected, ContextUsage.windowOf(model.limitContext, model.limitInput))
            if (input != null) carrying++
            if (input != null && context != null && input < context) narrowed++
            if (row.optString("providerID") == "opencode-go" && row.optString("id") == "space-bunny-free") {
                bunny = ContextUsage.windowOf(context, input)
            }
        }
        assertEquals("space-bunny-free's effective window", 524_288, bunny)
        // The finding's scale, not one unlucky model: most of the catalog carries
        // the field, and most of those are narrowed by it.
        assertTrue("rows carrying limit.input: $carrying", carrying >= 40)
        assertTrue("rows narrowed by it: $narrowed", narrowed >= 40)
    }

    // --------------------------------------------------------- flow wiring

    @Test
    fun everyConsumerReadsTheSameNumber() {
        val vm = source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")
        // v20 → DTO: there is no `limit.input` side table left. The catalog flow
        // is the single source of both halves of the ring's denominator, so
        // nothing can be read half-applied.
        assertFalse(vm.contains("modelInputLimits"))
        val flow = vm.substringAfter("val contextLimit:").substringBefore("stateIn(scope")
        for (flowSource in listOf("_availableProviders", "_transcriptModel", "_sessionModel", "_selectedModel")) {
            assertTrue("$flowSource is combined", flow.contains(flowSource))
        }
        assertTrue(flow.contains("contextLimitFrom(providers, transcript, session, selected)"))
        // The compaction collector hangs off the same flow, so the trigger's
        // denominator is the ring's denominator.
        assertTrue(vm.substringAfter("private val autoCompaction").contains("contextLimit,"))

        // The screen passes the catalog and the screen-owned scalars only — the
        // panel re-resolves the input half off the same entry itself.
        val screen = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")
        assertFalse(screen.contains("modelInputLimits"))
        val context = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/context/SessionContextModel.kt")
        assertFalse(context.contains("modelInputLimits"))
        assertTrue(context.contains("ContextUsage.windowOf(providers, providerID, modelID)"))
    }

    // ------------------------------------------------------- the settings row

    @Test
    fun theModelsTabMetaIsTheEffectiveWindow() {
        // The settings list prints the same denominator as the ring, not the raw
        // `limit.context` the server advertises: on the v20 model that is 512k,
        // not 1M (`SettingsPanels.ModelVisibilityRow`).
        val rows = modelRows(catalog(bunnyRow), selected = null)
        assertEquals(1_048_576, rows.single().limitContext)
        assertEquals(524_288, rows.single().limitInput)
        assertEquals(524_288, rows.single().window)
        // An entry without `limit.input` keeps its context.
        val v1 = modelRows(v1Catalog("""{"id":"space-bunny-free","limit":{"context":1048576}}"""), selected = null)
        assertNull(v1.single().limitInput)
        assertEquals(1_048_576, v1.single().window)
        // …and the panel's own formatter renders exactly that number.
        assertEquals("512k", formatContextLimit(rows.single().window))
        assertEquals("1M", formatContextLimit(v1.single().window))

        val panels = source("src/main/java/com/xiufeigo/opencodeshell/ui/settings/SettingsPanels.kt")
        assertTrue(panels.contains("formatContextLimit(row.window)"))
        assertFalse(panels.contains("formatContextLimit(row.limitContext)"))
    }
}
