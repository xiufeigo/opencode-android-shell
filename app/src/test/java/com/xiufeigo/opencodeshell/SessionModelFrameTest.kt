package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.v2.V2EventAdapter
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `session.model.selected` had a store field and no consumer.
 *
 * Another client switching a session's model makes the frame durable on the
 * server (`session-event.ts:72-92`), and `ChatStore.reduce` already keeps it in
 * `ChatState.sessionModel` — but nothing read that field, so the composer chip
 * stayed on the *previous* model until the next snapshot re-read the session
 * (`GET /api/session/{id}` → `_sessionModel`). Upstream reconciles the selection
 * reactively (`providers/models/selection.tsx:139-143,286-299`): a frame for the
 * session on screen moves `current()` at once.
 *
 * The wiring below is the fix — the frame feeds the **same slot** the snapshot
 * read writes, so the chip re-derives immediately. It is the session's *durable*
 * model and never a local pick: `sessionPicks` / `recentModel` stay untouched,
 * so a pick made in this session still outranks the frame
 * ([ChatViewModel.effectiveModelRef], `SessionModelScopeTest`).
 */
class SessionModelFrameTest {

    private val bunny = "opencode-go" to "space-bunny-free"
    private val longcat = "opencode-go" to "longcat-2.5-preview-free"

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    private fun vm() = source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")

    /** The frame the server emits when another client switches `sessionID`'s model. */
    private fun selectionFrame(
        sessionID: String,
        providerID: String,
        modelID: String,
    ): ChatStore.Event.SessionSelectionChanged =
        V2EventAdapter.adapt(
            "session.model.selected",
            JSONObject(
                """{"id":"evt_m1","type":"session.model.selected","created":1700000000000,""" +
                    """"data":{"sessionID":"$sessionID","model":{"providerID":"$providerID","id":"$modelID"}}}""",
            ),
        ).filterIsInstance<ChatStore.Event.SessionSelectionChanged>().single()

    private fun state(sessionID: String? = "ses_1") = ChatStore.ChatState(sessionID = sessionID)

    // ------------------------------------------------------ the frame's value

    @Test
    fun theFrameCarriesTheSessionsNewModelRef() {
        val frame = selectionFrame("ses_1", bunny.first, bunny.second)
        assertEquals("ses_1", frame.sessionID)
        assertEquals("opencode-go/space-bunny-free", frame.model)
        assertEquals(bunny, OpenCodeClient.parseModelRef(frame.model))
    }

    @Test
    fun theTrackedSessionsFrameLandsInTheStore() {
        val next = ChatStore.reduce(state(), selectionFrame("ses_1", bunny.first, bunny.second))
        assertEquals("opencode-go/space-bunny-free", next.sessionModel)
        // The same ref the snapshot's `SessionRow` read produces, so both paths
        // write one slot ([ChatViewModel._sessionModel]).
        assertEquals(bunny, OpenCodeClient.parseModelRef(next.sessionModel))
    }

    @Test
    fun anotherSessionsFrameNeverReachesTheTrackedState() {
        // The store's own gate: a global stream carries every session's frames.
        val foreign = selectionFrame("ses_other", longcat.first, longcat.second)
        assertEquals(state(), ChatStore.reduce(state(), foreign))
    }

    // --------------------------------------------------- the resolved chip

    @Test
    fun theFrameOutranksTheServerDefaultAndTheMemory() {
        // What `applyEffectiveModel()` resolves to once the frame lands: no pick
        // in this session, so the session's own model leads.
        assertEquals(bunny, ChatViewModel.effectiveModelRef(null, bunny, longcat, longcat))
    }

    @Test
    fun aPickMadeInThisSessionStillOutranksTheFrame() {
        // A foreign switch must not undo the user's own pick for this session.
        assertEquals(longcat, ChatViewModel.effectiveModelRef(longcat, bunny, longcat, bunny))
    }

    // ------------------------------------------------------------- the wiring

    @Test
    fun theViewModelConsumesTheStoreField() {
        val body = vm()
        val collector = body.substringAfter("private val sessionModelFrames")
            .substringBefore("val contextLimit:")
        // The frame is the source…
        assertTrue(collector.contains("it.sessionModel to it.sessionVariant"))
        assertTrue(collector.contains("distinctUntilChanged()"))
        assertTrue("the wire ref is parsed, not copied raw", collector.contains("OpenCodeClient::parseModelRef"))
        // …and it is written into the *session* slot the snapshot read owns…
        assertTrue(collector.contains("_sessionModel.value = ref"))
        // …then re-derived at once, or the chip waits for the next snapshot.
        val write = collector.indexOf("_sessionModel.value = ref")
        val applied = collector.indexOf("applyEffectiveModel()", write)
        assertTrue("the frame's model must reach the chip", applied > write)
    }

    @Test
    fun theFrameIsNotALocalPick() {
        val collector = vm().substringAfter("private val sessionModelFrames")
            .substringBefore("val contextLimit:")
        // Only a real picker tap records a pick (`selectModel`); a server-side
        // model change is the session's own model, so it must never be filed as
        // one — that would make it outrank the session and get written back.
        assertFalse(collector.contains("sessionPicks"))
        assertFalse(collector.contains("recentModel"))
        assertFalse(collector.contains("modelOverride ="))
        // The derived write stays behind the one resolution point.
        assertFalse(collector.contains("_selectedModel.value ="))
    }

    @Test
    fun theCollectorSitsAfterTheSlotItWrites() {
        val body = vm()
        // Construction order (P0): a `scope.launch` property initializer runs
        // eagerly on the main dispatcher, so it must be declared after every
        // field it can touch.
        val slot = body.indexOf("private val _sessionModel = MutableStateFlow")
        val collector = body.indexOf("private val sessionModelFrames")
        assertTrue("_sessionModel first", slot in 1 until collector)
        assertTrue(body.indexOf("private val _selectedModel") < collector)
    }
}
