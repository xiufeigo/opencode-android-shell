package com.xiufeigo.opencodeshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Device v28: **the session's own model must survive being opened.**
 *
 * A session created by another client with `model: {providerID: "opencode-go",
 * id: "space-bunny-free"}` (`POST /api/session`) rendered `LongCat 2.5 Preview
 * Free` in the composer chip for its whole life, and ran as it — while the
 * server still had `space-bunny-free` on the session. Two defects, one visible
 * and one destructive:
 *
 *  - the chip resolved to the **server default** (`GET /api/model/default`) and
 *    ignored the session's own `model`, which the snapshot had already read
 *    (`Session.Info.model` → `_sessionModel`, used by the context ring only);
 *  - the steer wrote that derived label back onto the session
 *    (`POST /api/session/{id}/model`), so the default replaced the session's
 *    model server-side — exactly what `selectedModelPin`'s own doc forbids
 *    ("a derived model … must never be written back as if the user had chosen
 *    it").
 *
 * Upstream resolves the composer's model in `selection.current()`
 * (`_upstream/packages/app/src/providers/models/selection.tsx:234-249`):
 * the pick made **for this session** (`saved.session[sessionID]`) → the
 * session's own `model` (`durable()`) → the configured default → the recent
 * picks. A global "last pick" never outranks a session's model, and a session's
 * model is never relabelled by a default. [ChatViewModel.effectiveModelRef]
 * pins that order and the wiring tests below pin that every derived write goes
 * through it, and that only a real pick is written to the server.
 */
class SessionModelScopeTest {

    private val bunny = "opencode-go" to "space-bunny-free"
    private val longcat = "opencode-go" to "longcat-2.5-preview-free"

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    private fun vm() = source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")

    // ------------------------------------------------ (a) 谁是这个会话的模型

    @Test
    fun aCurlCreatedSessionKeepsItsModelOnOpen() {
        // The measured frame: nothing picked in this composer, the session holds
        // space-bunny-free, the server's default is LongCat. The session wins.
        assertEquals(bunny, ChatViewModel.effectiveModelRef(null, bunny, longcat, longcat))
    }

    @Test
    fun theSessionsOwnModelOutranksTheConfiguredDefault() {
        assertEquals(bunny, ChatViewModel.effectiveModelRef(null, bunny, longcat, null))
        // …and the default answers only when the session names none.
        assertEquals(longcat, ChatViewModel.effectiveModelRef(null, null, longcat, null))
    }

    @Test
    fun onlyAPickMadeForThisContextBeatsTheSessionsModel() {
        // Upstream `scope().model` (this session's saved pick, or the draft's)
        // is the one term above `durable()` — and the only one written back.
        assertEquals(longcat, ChatViewModel.effectiveModelRef(longcat, bunny, longcat, bunny))
    }

    @Test
    fun thePersistedMemoryNeverOutranksASessionsModel() {
        // The picker memory (`models.recent`, the shell's persisted
        // `selectedModelOverride`) is the *last* term: a pick made in some other
        // session must not relabel this one (the other half of the v28 report —
        // "另一条会话" showing the model it was actually created with).
        assertEquals(bunny, ChatViewModel.effectiveModelRef(null, bunny, null, longcat))
        assertEquals(longcat, ChatViewModel.effectiveModelRef(null, null, longcat, bunny))
        // Alone, it is still the last resort (a returning user sees their pick).
        assertEquals(longcat, ChatViewModel.effectiveModelRef(null, null, null, longcat))
    }

    @Test
    fun nothingNamesAModelAtAll() {
        assertNull(ChatViewModel.effectiveModelRef(null, null, null, null))
    }

    // --------------------------------------------- (b) 接线：写点与解析顺序

    @Test
    fun everyDerivedWriteGoesThroughTheResolution() {
        val body = vm()
        // One write point for a *derived* label…
        assertTrue(body.contains("_selectedModel.value = effectiveModelRef("))
        // …so no refresh can drop one source straight onto the chip (which is
        // how the server default ended up over a session's own model).
        assertFalse(body.contains("_selectedModel.value = serverDefaultModel"))
        assertFalse(body.contains("_selectedModel.value = _sessionModel"))
    }

    @Test
    fun theSessionModelIsAppliedTheMomentTheSnapshotReadsIt() {
        val body = vm()
        val read = body.indexOf("_sessionModel.value = sessionModelRef(info)")
        assertTrue("the snapshot still reads the session's model", read > 0)
        val applied = body.indexOf("applyEffectiveModel()", read)
        assertTrue("the session's model must reach the chip", applied > read)
        // v72: the durable variant adopt sits between the two (cold-restart
        // label restore — SessionVariantRestoreV72Test), so the apply is near
        // the read but no longer adjacent.
        assertTrue(
            "…promptly, before anything else can overwrite the label",
            applied - read < 1200,
        )
    }

    @Test
    fun theServerDefaultIsAppliedThroughTheSameResolution() {
        val body = vm()
        val read = body.indexOf("serverDefaultModel = entry.providerID to entry.id")
        assertTrue(read > 0)
        val applied = body.indexOf("applyEffectiveModel()", read)
        assertTrue(applied > read)
        assertTrue(applied - read < 200)
    }

    @Test
    fun openingASessionTakesThePickFromThatSessionOnly() {
        val body = vm()
            .substringAfter("fun observeSession(id: String, title: String? = null, directory: String? = null)")
            .substringBefore("fun newSession()")
        // A pick belongs to the session it was made in (upstream
        // `saved.session[sessionID]`)…
        assertTrue(body.contains("modelOverride = sessionPicks[id]"))
        // …never to the persisted memory, which is what relabelled the session.
        assertFalse(body.contains("modelOverride = recentModel"))
        // The previous session's model is dropped with it, and the resolution
        // re-runs (the session's own model lands with the snapshot).
        assertTrue(body.contains("_sessionModel.value = null"))
        assertTrue(body.contains("applyEffectiveModel()"))
        assertTrue(body.contains("_modelOverrideActive.value = modelOverride != null"))
    }

    @Test
    fun aDraftStartsFromTheMemoryAgain() {
        val body = vm()
            .substringAfter("fun newSession()")
            .substringBefore("/** True when [e] is the server's")
        assertTrue(body.contains("modelOverride = recentModel"))
        assertTrue(body.contains("applyEffectiveModel()"))
    }

    @Test
    fun thePickMadeInASessionIsRecordedForThatSession() {
        val body = vm()
            .substringAfter("fun selectModel(providerID: String, modelID: String)")
            .substringBefore("/**\n     * The composer's **explicit** pick")
        assertTrue(body.contains("sessionID?.let { sessionPicks[it] = ref }"))
        assertTrue("the draft/memory keeps it too", body.contains("recentModel = ref"))
        assertTrue(body.contains("settingsStore?.selectedModelOverride"))
        assertTrue("and it lands on the session at once", body.contains("pinSelectedModel()"))
    }

    @Test
    fun theDraftPickIsPromotedToTheSessionItCreates() {
        val body = vm()
            .substringAfter("fun createSession(")
            .substringBefore("/**\n     * `GET /api/project` read")
        assertTrue(
            "upstream `session.promote()` hands the draft's pick to the session",
            body.contains("recentModel?.let { sessionPicks[newID] = it }"),
        )
        assertTrue(
            "promoted before the session is observed, so it is still the pin",
            body.indexOf("sessionPicks[newID] = it") < body.indexOf("observeSession(id = newID"),
        )
        assertTrue(body.contains("selectedModelPin()?.let { pin ->"))
    }

    @Test
    fun aSteerWritesOnlyThePickedModel() {
        val body = vm()
            .substringAfter("fun submit(")
            .substringBefore("private fun filesOf(")
        assertTrue(body.contains("val pin = selectedModelPin()"))
        assertTrue(body.contains("if (pin != null) {"))
        assertTrue(body.contains("t.switchModel(id, pin.providerID, pin.modelID, pin.variant)"))
        // The derived label never reaches the wire — this is the write that
        // silently replaced the session's model.
        assertFalse(body.contains("_selectedModel"))
        // …and a session that names no model anywhere is still refused locally
        // instead of spending a doomed round trip.
        assertTrue(body.contains("resolveModelPlan(t, id) == SessionModelSync.Plan.NoModel"))
    }

    @Test
    fun theQueueEditSteerObeysTheSameRule() {
        val body = vm()
            .substringAfter("private fun sendStructured(")
            .substringBefore("/**\n     * F-4: the comment notes")
        assertTrue(body.contains("selectedModelPin()?.let { pin ->"))
        assertTrue(body.contains("transport.switchModel(sessionID, pin.providerID, pin.modelID, pin.variant)"))
        assertFalse(body.contains("_selectedModel"))
    }

    @Test
    fun useServerDefaultFallsBackToTheSessionBeforeTheDefault() {
        val body = vm()
            .substringAfter("fun clearModelOverride()")
            .substringBefore("/**\n     * A plain prompt")
        assertTrue("this context's pick is dropped", body.contains("modelOverride = null"))
        assertTrue(body.contains("sessionID?.let { sessionPicks.remove(it) }"))
        assertTrue(body.contains("applyEffectiveModel()"))
        // Upstream `model.set(undefined)` → `current()` resolves the session's
        // own model before the configured default.
        assertFalse(body.contains("_selectedModel.value = serverDefaultModel"))
    }
}
