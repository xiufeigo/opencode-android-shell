package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.header.ContextUsage
import com.xiufeigo.opencodeshell.v2.SessionRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The context ring's denominator (`ChatViewModel.contextLimit`) after the v16
 * device finding: the ring stayed an **empty track** for a whole session while
 * the context tab still printed `Context Limit: 1,048,576`.
 *
 * Root cause: the ring had exactly **one** source — the picker's effective
 * model (`_selectedModel`, fed by `GET /api/model/default`) — while the tab
 * resolves the model of the newest assistant message
 * (`SessionContextModel.project` → `model?.limitContext ?: contextLimit`). On a
 * server where the default-model read does not answer, the single source stayed
 * `null` forever and `ContextUsage.percentOf(total, null)` kept returning `null`
 * ⇒ `progress(null)` = `0f` ⇒ track only.
 *
 * The fix is the multi-source resolution in [ChatViewModel.contextLimitFrom]:
 * the transcript row that owns the numerator first (upstream
 * `session-context-usage.tsx:52-58` reads the limit off that same message), then
 * the session's own `Model.Ref` (`GET /api/session/{id}`), then the picker.
 *
 * v20 (P1, `research/v20_verify/`): what those sources *answer* changed — the
 * denominator is now the entry's effective window `min(limit.input,
 * limit.context)` ([ContextUsage.windowOf]), not the bare `limit.context`. The
 * priority order and the empty-ring rule are untouched; `ContextWindowLimitTest`
 * owns the rule and its live evidence. Both halves are fields of the resolved
 * catalog entry (`OpenCodeClient.Providers.Model.limitInput`), so a source only
 * chooses *which* entry answers — never which half of it.
 */
class ContextLimitSourcesTest {

    private fun vm() = source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText()
    }

    private fun model(id: String, limitContext: Int? = null, limitInput: Int? = null) =
        OpenCodeClient.Providers.Model(
            id = id,
            name = id,
            capabilities = null,
            limitContext = limitContext,
            limitInput = limitInput,
        )

    /** One provider carrying three models with three different windows. */
    private val catalog = OpenCodeClient.Providers(
        all = listOf(
            OpenCodeClient.Providers.Provider(
                id = "openai",
                name = "OpenAI",
                models = listOf(
                    model("gpt-5.6", limitContext = 1_048_576),
                    model("gpt-5.6-fast", limitContext = 200_000),
                    model("no-limit"),
                ),
            ),
        ),
        connected = listOf("openai"),
        default = emptyMap(),
    )

    /**
     * The same three entries with their `limit.input` halves attached — the
     * `min(limit.input, limit.context)` cases (`ContextWindowLimitTest` owns the
     * rule; this one owns "a ref must not change it").
     */
    private val narrowedCatalog = OpenCodeClient.Providers(
        all = listOf(
            OpenCodeClient.Providers.Provider(
                id = "openai",
                name = "OpenAI",
                models = listOf(
                    model("gpt-5.6", limitContext = 1_048_576, limitInput = 524_288),
                    model("gpt-5.6-fast", limitContext = 200_000, limitInput = 900_000),
                    model("no-limit"),
                ),
            ),
        ),
        connected = listOf("openai"),
        default = emptyMap(),
    )

    // -------------------------------------------------------- source priority

    @Test
    fun transcriptModelOutranksTheSessionAndThePicker() {
        // The transcript row owns the numerator, so its model owns the
        // denominator — upstream's own rule and the context tab's source.
        assertEquals(
            1_048_576,
            ChatViewModel.contextLimitFrom(
                providers = catalog,
                transcriptModel = "openai" to "gpt-5.6",
                sessionModel = "openai" to "gpt-5.6-fast",
                selectedModel = "openai" to "gpt-5.6-fast",
            ),
        )
    }

    @Test
    fun sessionModelIsTheFallbackWhenTheTranscriptHasNoUsageRow() {
        // A fresh session (no assistant usage yet) or a trimmed transcript:
        // `GET /api/session/{id}` still knows which model the session runs.
        assertEquals(
            200_000,
            ChatViewModel.contextLimitFrom(
                providers = catalog,
                transcriptModel = null,
                sessionModel = "openai" to "gpt-5.6-fast",
                selectedModel = "openai" to "gpt-5.6",
            ),
        )
    }

    @Test
    fun pickerIsTheLastResort() {
        // The pre-v16 single source, kept so a brand-new session shows a limit
        // as soon as a model is chosen.
        assertEquals(
            1_048_576,
            ChatViewModel.contextLimitFrom(
                providers = catalog,
                transcriptModel = null,
                sessionModel = null,
                selectedModel = "openai" to "gpt-5.6",
            ),
        )
    }

    @Test
    fun anUnresolvableSourceFallsThroughToTheNext() {
        // The transcript's ref is stale (model gone from the catalog): the
        // session ref must still answer instead of leaving the ring empty.
        assertEquals(
            200_000,
            ChatViewModel.contextLimitFrom(
                providers = catalog,
                transcriptModel = "openai" to "retired-model",
                sessionModel = "openai" to "gpt-5.6-fast",
                selectedModel = null,
            ),
        )
        // …and a ref that resolves to a model without `limit.context` is not an
        // answer either (never a fake denominator).
        assertEquals(
            1_048_576,
            ChatViewModel.contextLimitFrom(
                providers = catalog,
                transcriptModel = "openai" to "no-limit",
                sessionModel = "openai" to "gpt-5.6",
                selectedModel = null,
            ),
        )
    }

    // ------------------------------------------------------------- empty ring

    @Test
    fun noSourceAtAllKeepsTheDocumentedEmptyRing() {
        assertNull(
            ChatViewModel.contextLimitFrom(
                providers = catalog,
                transcriptModel = null,
                sessionModel = null,
                selectedModel = null,
            ),
        )
        assertNull(
            "catalog not loaded yet",
            ChatViewModel.contextLimitFrom(
                providers = null,
                transcriptModel = "openai" to "gpt-5.6",
                sessionModel = "openai" to "gpt-5.6",
                selectedModel = "openai" to "gpt-5.6",
            ),
        )
        assertNull(
            "unknown refs",
            ChatViewModel.contextLimitFrom(
                providers = catalog,
                transcriptModel = "missing" to "gpt-5.6",
                sessionModel = "openai" to "missing",
                selectedModel = null,
            ),
        )
        // No limit ⇒ no arc: `progress` reads `null` as 0 %.
        val messages = listOf(usage("m1", tokens = 40_000L, model = "openai/gpt-5.6"))
        assertEquals(
            0f,
            ContextUsage.progressOf(messages, ChatViewModel.contextLimitFrom(catalog, null, null, null)?.toLong()),
            0f,
        )
    }

    // -------------------------------------------------------- ring arithmetic

    @Test
    fun theArcIsTheUsedOverLimitRatio() {
        // The device's own numbers: a 1,048,576 window (the context tab's
        // `Context Limit`) must produce an arc as soon as tokens are used.
        val limit = ChatViewModel.contextLimitFrom(
            providers = catalog,
            transcriptModel = "openai" to "gpt-5.6",
            sessionModel = null,
            selectedModel = null,
        )?.toLong()
        assertEquals(1_048_576L, limit)
        assertEquals(25, ContextUsage.percentOf(total = 262_144L, limit = limit))
        assertEquals(1, ContextUsage.percentOf(total = 10_000L, limit = limit))
        assertEquals(0.25f, ContextUsage.progress(ContextUsage.percentOf(262_144L, limit)), 0.0001f)
    }

    @Test
    fun theSingleSourceCoreStillResolvesThePickerModel() {
        // `contextLimitOf` keeps its pre-v16 shape for existing callers/tests.
        assertEquals(1_048_576, ChatViewModel.contextLimitOf(catalog, "openai" to "gpt-5.6"))
        assertNull(ChatViewModel.contextLimitOf(catalog, "openai" to "no-limit"))
        assertNull(ChatViewModel.contextLimitOf(catalog, null))
        assertNull(ChatViewModel.contextLimitOf(null, "openai" to "gpt-5.6"))
    }

    // ------------------------------------------------- the effective window

    @Test
    fun everySourceNarrowsToTheEffectiveWindow() {
        // v20: the denominator is `min(limit.input, limit.context)` for whichever
        // source answers — a ref must not change the *rule*, only which model it
        // is applied to (see `ContextWindowLimitTest` for the rule itself). The
        // input half rides on the catalog entry itself, so every source resolves
        // it from the same row it took `limit.context` from.
        // Transcript row: 1,048,576 → 524,288.
        assertEquals(
            524_288,
            ChatViewModel.contextLimitFrom(narrowedCatalog, "openai" to "gpt-5.6", null, null),
        )
        // Session ref: an input ceiling *above* the context cannot widen it.
        assertEquals(
            200_000,
            ChatViewModel.contextLimitFrom(narrowedCatalog, null, "openai" to "gpt-5.6-fast", null),
        )
        // Picker: same rule, and the single-source core carries it too.
        assertEquals(
            200_000,
            ChatViewModel.contextLimitOf(narrowedCatalog, "openai" to "gpt-5.6-fast"),
        )
        assertEquals(
            524_288,
            ChatViewModel.contextLimitOf(narrowedCatalog, "openai" to "gpt-5.6"),
        )
    }

    @Test
    fun aModelWithoutAnInputCeilingKeepsTheRawContext() {
        // 74 of the 129 live v20 entries omit `limit.input`: the entry carries
        // `null` and the pre-v20 reading stands, whichever source answers.
        assertEquals(
            1_048_576,
            ChatViewModel.contextLimitFrom(
                catalog,
                "openai" to "gpt-5.6",
                null,
                null,
            ),
        )
        assertEquals(
            1_048_576,
            ChatViewModel.contextLimitOf(catalog, "openai" to "gpt-5.6"),
        )
        // …and the entry that advertises neither half is still no denominator.
        assertNull(ChatViewModel.contextLimitOf(narrowedCatalog, "openai" to "no-limit"))
    }

    // ------------------------------------------------------ source extraction

    @Test
    fun theTranscriptSourceIsTheMessageThatOwnsTheTokenTotal() {
        // Same row as the numerator (`ContextUsage.lastTokenTotal`), so the two
        // halves of the ratio can never describe different turns.
        val messages = listOf(
            usage("m1", tokens = 1_000L, model = "openai/gpt-5.6-fast"),
            usage("m2", tokens = 4_000L, model = "openai/gpt-5.6"),
            usage("m3", tokens = null, model = "openai/gpt-5.6-pro"),
            user("m4", model = "openai/gpt-5.6-pro"),
        )
        assertEquals("openai/gpt-5.6", ContextUsage.lastModelRef(messages))
        assertEquals(4_000L, ContextUsage.lastTokenTotal(messages))
        assertEquals("openai", OpenCodeClient.parseModelRef(ContextUsage.lastModelRef(messages))?.first)
        assertEquals("gpt-5.6", OpenCodeClient.parseModelRef(ContextUsage.lastModelRef(messages))?.second)
        // Nothing to read before the first usage lands.
        assertNull(ContextUsage.lastModelRef(emptyList()))
        assertNull(ContextUsage.lastModelRef(listOf(usage("m1", tokens = null, model = "openai/gpt-5.6"))))
        assertNull(ContextUsage.lastModelRef(listOf(user("m1", model = "openai/gpt-5.6"))))
    }

    @Test
    fun theSessionSourceNeedsBothHalvesOfTheRef() {
        assertEquals(
            "openai" to "gpt-5.6",
            ChatViewModel.sessionModelRef(row(providerID = "openai", modelID = "gpt-5.6")),
        )
        assertNull(ChatViewModel.sessionModelRef(row(providerID = null, modelID = "gpt-5.6")))
        assertNull(ChatViewModel.sessionModelRef(row(providerID = "openai", modelID = null)))
        assertNull(ChatViewModel.sessionModelRef(row(providerID = " ", modelID = "gpt-5.6")))
        assertNull(ChatViewModel.sessionModelRef(null))
    }

    // ----------------------------------------------------------- flow wiring

    @Test
    fun theFlowConsultsEverySourceAndTheSessionReadFeedsIt() {
        val body = vm()
        // All four flows reach the resolver — dropping one silently re-creates
        // the empty-ring bug.
        val flow = body.substringAfter("val contextLimit:").substringBefore("stateIn(scope")
        for (flowSource in listOf("_availableProviders", "_transcriptModel", "_sessionModel", "_selectedModel")) {
            assertTrue("$flowSource is combined", flow.contains(flowSource))
        }
        assertTrue(flow.contains("contextLimitFrom(providers, transcript, session, selected)"))
        // v20 → DTO: the `limit.input` half rides on the catalog entry, so the
        // flow needs no fifth source and no side table.
        assertFalse("the side table is gone", flow.contains("_modelInputLimits"))
        assertFalse("the side table is gone", body.contains("_modelInputLimits"))

        // The transcript source is derived from the *rendered* message store
        // (reverted tail hidden) through the ring's own row helper.
        val transcript = body.substringAfter("private val _transcriptModel").substringBefore("private val _sessionModel")
        assertTrue(transcript.contains("state.map { ContextUsage.lastModelRef(it.messages)"))
        assertTrue(transcript.contains("OpenCodeClient::parseModelRef"))
        assertTrue(transcript.contains("distinctUntilChanged()"))

        // The session source is written by the snapshot's session-info read and
        // cleared with the other session-scoped state.
        assertTrue(body.contains("_sessionModel.value = sessionModelRef(info)"))
        assertEquals(
            "cleared on both session transitions (observeSession + newSession)",
            2,
            Regex("_sessionModel\\.value = null").findAll(body).count(),
        )
    }

    // ------------------------------------------------------------------ utils

    private fun usage(id: String, tokens: Long?, model: String?) = ChatStore.UiMessage(
        id = id,
        role = "assistant",
        timeCreated = 0L,
        parts = emptyList(),
        tokens = tokens,
        model = model,
    )

    private fun user(id: String, model: String?) = ChatStore.UiMessage(
        id = id,
        role = "user",
        timeCreated = 0L,
        parts = emptyList(),
        model = model,
    )

    private fun row(providerID: String?, modelID: String?) = SessionRow(
        id = "ses_1",
        title = null,
        directory = null,
        cost = null,
        revertMessageID = null,
        agent = null,
        providerID = providerID,
        modelID = modelID,
    )
}
