package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.V2EventAdapter
import com.xiufeigo.opencodeshell.v2.V2Transport
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v71 (5558 `research/v71_verify/5558/52_open_b.png`): a session's model
 * variant (e.g. `GLM-5.3-Flash · max`, pinned via `/model`) lost its `· max`
 * suffix after a force-stop + relaunch + reopen, while the turn still ran the
 * variant — the label disagreed with the dropdown.
 *
 * Cold restart empties the in-memory [ChatViewModel.sessionVariants], and no
 * restore path read the effort back: `observeSession` restored
 * `pinVariant(sessionVariants[id])` (empty after restart), the snapshot's
 * `GET /api/session/{id}` adopted the model's ref but dropped
 * `SessionInfo.model.variant`, and the `session.model.selected` frame's
 * variant never reached the chip either. The server held the `· max` the
 * whole time (`selectedModelPin` pins it on every steer), so only the label
 * was missing.
 *
 * The fix wires the server-side variant through all three paths: the `SessionRow`
 * carries it, the snapshot adopts it into this session's slot, and the live
 * frame reconciles the same slot — never as a local pick.
 */
class SessionVariantRestoreV72Test {

    private val transport = V2Transport(V2Api("http://127.0.0.1:1/".toHttpUrl()))

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    private fun row(json: String) =
        with(transport) { V2Api.SessionInfo.fromJson(JSONObject(json)).toRow() }

    private fun frame(model: JSONObject): ChatStore.Event.SessionSelectionChanged =
        V2EventAdapter.adapt(
            "session.model.selected",
            JSONObject(
                """{"id":"evt_v1","type":"session.model.selected","created":1790435677683,""" +
                    """"data":{"sessionID":"ses_1","model":${model},"previous":{"providerID":"p","id":"old"}}}""",
            ),
        ).filterIsInstance<ChatStore.Event.SessionSelectionChanged>().single()

    private fun state(sessionID: String? = "ses_1") = ChatStore.ChatState(sessionID = sessionID)

    // ------------------------------------------------------ the row projection

    @Test
    fun theSessionRowCarriesTheServerVariant() {
        val row = row(
            """{"id":"ses_1","title":"t","model":{"id":"glm-5.3-flash","providerID":"z","variant":"max"},""" +
                """"time":{"created":1,"updated":2}}""",
        )
        assertEquals("max", row.variant)
    }

    @Test
    fun noVariantOrTheDefaultReadsAsNoEffort() {
        assertNull(
            row(
                """{"id":"ses_1","title":"t","model":{"id":"m","providerID":"p"},""" +
                    """"time":{"created":1}}""",
            ).variant,
        )
        assertNull(
            row(
                """{"id":"ses_1","title":"t","model":{"id":"m","providerID":"p","variant":"default"},""" +
                    """"time":{"created":1}}""",
            ).variant,
        )
        assertNull(row("""{"id":"ses_1","title":"t","time":{"created":1}}""").variant)
    }

    // ------------------------------------------------------- the live frame

    @Test
    fun theFrameCarriesTheSessionsVariant() {
        val selection = frame(JSONObject("""{"providerID":"z","id":"glm-5.3-flash","variant":"max"}"""))
        assertEquals("z/glm-5.3-flash", selection.model)
        assertEquals("max", selection.variant)
    }

    @Test
    fun theDefaultVariantReadsAsNoEffort() {
        val selection = frame(JSONObject("""{"providerID":"p","id":"m","variant":"default"}"""))
        assertNull(selection.variant)
    }

    @Test
    fun theTrackedSessionsVariantLandsInTheStore() {
        val next = ChatStore.reduce(
            state(),
            frame(JSONObject("""{"providerID":"z","id":"glm-5.3-flash","variant":"max"}""")),
        )
        assertEquals("z/glm-5.3-flash", next.sessionModel)
        assertEquals("max", next.sessionVariant)
    }

    @Test
    fun anAgentFrameNeverClearsTheEffort() {
        val seeded = ChatStore.reduce(
            state(),
            frame(JSONObject("""{"providerID":"z","id":"glm-5.3-flash","variant":"max"}""")),
        )
        val agent = V2EventAdapter.adapt(
            "session.agent.selected",
            JSONObject(
                """{"id":"evt_a1","type":"session.agent.selected","created":1,""" +
                    """"data":{"sessionID":"ses_1","agent":"build"}}""",
            ),
        ).filterIsInstance<ChatStore.Event.SessionSelectionChanged>().single()
        val next = ChatStore.reduce(seeded, agent)
        assertEquals("build", next.sessionAgent)
        assertEquals("max", next.sessionVariant)
    }

    @Test
    fun anotherSessionsFrameNeverReachesTheTrackedState() {
        val foreign = V2EventAdapter.adapt(
            "session.model.selected",
            JSONObject(
                """{"id":"evt_o1","type":"session.model.selected","created":1,""" +
                    """"data":{"sessionID":"ses_other","model":{"providerID":"z","id":"m","variant":"max"},"previous":{"providerID":"z","id":"old"}}}""",
            ),
        ).filterIsInstance<ChatStore.Event.SessionSelectionChanged>().single()
        assertEquals(state(), ChatStore.reduce(state(), foreign))
    }

    // ------------------------------------------------------------- the wiring

    @Test
    fun theSnapshotReadAdoptsTheSessionVariant() {
        val vm = source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")
        val guard = vm.indexOf("if (info != null && sessionID == id) {")
        assertTrue("the session-guarded info block exists", guard >= 0)
        val adopt = vm.indexOf("sessionVariants[id] = rowVariant", guard)
        assertTrue(
            "refreshSnapshot's GET /api/session/:id must adopt the server's variant",
            adopt > guard,
        )
        assertTrue("the adopt writes the scoped flow too", vm.indexOf("_selectedVariant.value = rowVariant", guard) > guard)
        val applied = vm.indexOf("applyEffectiveModel()", adopt)
        assertTrue("the adopted variant must reach the chip", applied > adopt)
    }

    @Test
    fun theFrameReconcilesTheSameScopedSlot() {
        val vm = source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")
        val collector = vm.substringAfter("private val sessionModelFrames")
            .substringBefore("val contextLimit:")
        assertTrue(collector.contains("it.sessionModel to it.sessionVariant"))
        assertTrue(collector.contains("sessionVariants[id] = variant"))
        assertTrue(collector.contains("sessionVariants.remove(id)"))
        assertTrue(collector.contains("_selectedVariant.value = variant"))
        assertTrue(collector.contains("applyEffectiveModel()"))
    }

    @Test
    fun theServerSideVariantIsNeverALocalPick() {
        val vm = source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")
        val collector = vm.substringAfter("private val sessionModelFrames")
            .substringBefore("val contextLimit:")
        assertTrue("a server-side variant must never record a pick", !collector.contains("sessionPicks"))
        assertTrue(!collector.contains("recentModel"))
        assertTrue(!collector.contains("modelOverride ="))
        assertTrue(!collector.contains("_selectedModel.value ="))
        val guard = vm.indexOf("if (info != null && sessionID == id) {")
        val adopt = vm.indexOf("sessionVariants[id] = rowVariant", guard)
        val block = vm.substring(guard, adopt + 200)
        assertTrue("the snapshot adopt is not a pick either", !block.contains("modelOverride ="))
    }
}
