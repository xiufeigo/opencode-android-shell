package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.composer.ModelPickerItems
import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.V2Transport
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v73 (5558 + 5556, `research/v73_verify/5558/13_variant_low.png` →
 * `15_reopen_low.png` → `16_dropdown_after_restart.png`): a session's effort was
 * picked (`GLM-5.3-Flash · low`), the chip showed it, and a force-stop +
 * relaunch + reopen lost **both** halves — the `· low` suffix *and* the
 * dropdown's tick, which fell back to 「Default」. The model itself survived, so
 * the effort was never a display bug: the pick lived in
 * `ChatViewModel.sessionVariants`, an in-memory map that a cold start empties,
 * and the picker tap never reached the server either (`selectVariant` wrote the
 * flow and the slot, nothing else), so the v72 read-back had nothing to read.
 *
 * Two writes fix it, both upstream's own (`providers/models/selection.tsx`):
 *  - the session is told (`POST /api/session/{id}/model` with the variant —
 *    `Session.switchModel` compares the effort too, `session/session.ts:96-101`,
 *    and the projector persists the whole ref);
 *  - the pick is mirrored into a durable per-session table
 *    (`saved.session[id].variant`, `selection.tsx:286-299`), which
 *    `observeSession` restores before the snapshot answers. The server row keeps
 *    the last word when it names an effort of its own.
 */
class SessionVariantDurableV73Test {

    private fun RecordedRequest.json(): JSONObject = JSONObject(body.readUtf8())

    private fun transport(server: MockWebServer) = V2Transport(
        V2Api(
            baseHttpUrl = server.url("/").toString().toHttpUrlOrNull()!!,
            username = "opencode",
            password = "opencode",
            http = V2Api.defaultClient(),
            directory = null,
        ),
    )

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    private fun vm() = source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")

    // ------------------------------------------- the durable table (cold start)

    @Test
    fun aPinnedEffortSurvivesAColdStartThroughTheDurableCopy() {
        // The write the pin makes…
        val raw = SettingsStore.encodeSessionVariants(
            SettingsStore.withSessionVariant(emptyMap(), "ses_1", "max"),
        )
        // …and the read a *new process* makes off the same string.
        assertEquals("max", SettingsStore.parseSessionVariants(raw)["ses_1"])
    }

    @Test
    fun theDefaultEffortRemovesTheEntryInsteadOfStoringALabel() {
        val stored = SettingsStore.withSessionVariant(emptyMap(), "ses_1", "max")
        // 「Default」 is the absence of a pin (`ModelPickerItems.VARIANT_DEFAULT`
        // normalizes to null before the write), never a stored label — otherwise
        // a later reopen would restore an effort the user dropped.
        assertNull(SettingsStore.withSessionVariant(stored, "ses_1", null)["ses_1"])
        assertNull(SettingsStore.withSessionVariant(stored, "ses_1", "")["ses_1"])
    }

    @Test
    fun anotherSessionsEffortIsNeverRestored() {
        val table = SettingsStore.parseSessionVariants(
            SettingsStore.encodeSessionVariants(
                SettingsStore.withSessionVariant(
                    SettingsStore.withSessionVariant(emptyMap(), "ses_1", "low"),
                    "ses_2",
                    "max",
                ),
            ),
        )
        assertEquals("low", table["ses_1"])
        assertEquals("max", table["ses_2"])
        assertNull(table["ses_3"])
    }

    @Test
    fun malformedOrAbsentRawReadsAsNoPins() {
        assertTrue(SettingsStore.parseSessionVariants(null).isEmpty())
        assertTrue(SettingsStore.parseSessionVariants("").isEmpty())
        assertTrue(SettingsStore.parseSessionVariants("{not json").isEmpty())
    }

    @Test
    fun theTableKeepsTheNewestPinsOnly() {
        var table = emptyMap<String, String>()
        for (index in 0..SettingsStore.MAX_SESSION_VARIANTS + 4) {
            table = SettingsStore.withSessionVariant(table, "ses_$index", "max")
        }
        assertEquals(SettingsStore.MAX_SESSION_VARIANTS, table.size)
        assertNull("the oldest session drops out", table["ses_0"])
        assertEquals("max", table["ses_${SettingsStore.MAX_SESSION_VARIANTS + 4}"])
    }

    @Test
    fun thePickedEffortSurvivesTheColdStartOnEverySurface() {
        // 1. the picker offers the effort and accepts the pick…
        val variants = listOf("default", "low", "high", "max")
        val picked = ModelPickerItems.resolveVariant("low", variants)
        assertEquals("low", picked)
        // 2. …the pin is written to the durable per-session table…
        val raw = SettingsStore.encodeSessionVariants(
            SettingsStore.withSessionVariant(emptyMap(), "ses_1", picked),
        )
        // 3. …a cold start reads it back…
        val restored = SettingsStore.parseSessionVariants(raw)["ses_1"]
        assertEquals("low", restored)
        // 4. …and it is the very value both the chip and the dropdown tick draw
        //    the effort from (`ModelChip.variantLabel` / the picker's `effective`).
        assertEquals(restored, ModelPickerItems.resolveVariant(restored, variants))
    }

    // -------------------------------------------------- the session is told too

    @Test
    fun theVariantLandsOnTheSessionOverTheWire() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))

            transport(server).switchModel("ses_1", "openai", "gpt-5.6-sol", "max")

            val switch = server.takeRequest()
            assertEquals("POST", switch.method)
            assertEquals("/api/session/ses_1/model", switch.requestUrl!!.encodedPath)
            val model = switch.json().getJSONObject("model")
            assertEquals("gpt-5.6-sol", model.getString("id"))
            assertEquals("openai", model.getString("providerID"))
            assertEquals("max", model.getString("variant"))
        }
    }

    @Test
    fun clearingTheEffortLeavesTheSessionItsOwnDefault() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))

            transport(server).switchModel("ses_1", "openai", "gpt-5.6-sol", null)

            val model = server.takeRequest().json().getJSONObject("model")
            // The ref replaces `session.model` wholesale (`projector.ts:557-567`),
            // so an omitted variant is what takes the effort back off the row.
            assertFalse(model.has("variant"))
        }
    }

    // ------------------------------------------------------------- the wiring

    @Test
    fun thePickerTapTellsTheSessionAsWellAsTheChip() {
        val select = vm()
            .substringAfter("fun selectVariant(label: String?)")
            .substringBefore("/**\n     * The one writer of [selectedVariant]")
        assertTrue("the effort must land on session.model", select.contains("pinSelectedVariant()"))

        val pin = vm()
            .substringAfter("private fun pinSelectedVariant()")
            .substringBefore("private fun variantWriteRef()")
        assertTrue(pin.contains("val id = sessionID ?: return"))
        assertTrue(pin.contains("transport.switchModel(id, ref.first, ref.second, variant)"))
        assertTrue(
            "a mid-turn tap must not reconfigure the running turn",
            pin.contains("if (_state.value.runStatus == ChatStore.RunStatus.RUNNING) return"),
        )
    }

    @Test
    fun droppingTheEffortIsWrittenToo() {
        // 「Default」 goes out as the same ref with no variant: `Session.switchModel`
        // compares the effort as well, and the projector replaces `session.model`
        // wholesale, so an omitted variant is what clears the row. A write gated
        // on "a variant is pinned" would leave the server running the effort the
        // user just dropped, and the next snapshot would tick it again.
        val pin = vm()
            .substringAfter("private fun pinSelectedVariant()")
            .substringBefore("private fun variantWriteRef()")
        assertTrue(pin.contains("val variant = _selectedVariant.value"))
        assertFalse(
            "no pin-gate in front of the write",
            pin.contains("_selectedVariant.value.isNullOrBlank()) return"),
        )
        val select = vm()
            .substringAfter("fun selectVariant(label: String?)")
            .substringBefore("/**\n     * The one writer of [selectedVariant]")
        assertTrue(select.contains("pinVariant("))
        assertTrue(select.contains("pinSelectedVariant()"))
    }

    @Test
    fun theWriteUsesTheSessionsOwnModelWhenNoPickExists() {
        // The v73 repro picked no model at all — the session already ran
        // `GLM-5.3-Flash` — so the effort must ride the session's own ref, never
        // a derived default (the v28 bug) and never a guessed one.
        val body = vm()
            .substringAfter("private fun variantWriteRef()")
            .substringBefore("/** True when the catalog advertises")
        assertTrue(body.contains("modelOverride ?: _sessionModel.value"))
    }

    @Test
    fun thePinIsMirroredIntoTheDurableCopy() {
        val body = vm()
            .substringAfter("private fun pinVariant(value: String?)")
            .substringBefore("\n    }")
        assertTrue(body.contains("settingsStore?.setSessionVariant(id, value)"))
    }

    @Test
    fun reopeningASessionRestoresTheDurableCopyBeforeTheSnapshot() {
        val body = vm()
            .substringAfter("fun observeSession(id: String, title: String? = null, directory: String? = null)")
            .substringBefore("fun newSession()")
        assertTrue(body.contains("settingsStore?.sessionVariant(id)"))
        assertTrue(body.contains("pinVariant(sessionVariants[id])"))
        assertTrue(
            "the local copy seeds the slot the writer then publishes",
            body.indexOf("settingsStore?.sessionVariant(id)") < body.indexOf("pinVariant(sessionVariants[id])"),
        )
    }

    @Test
    fun theServerRowOnlySetsTheEffortNeverClearsIt() {
        val body = vm()
            .substringAfter("val rowVariant = info.variant?.takeIf {")
            .substringBefore("applyEffectiveModel()")
        assertTrue(body.contains("if (rowVariant != null && rowVariant != sessionVariants[id])"))
        // A server that never heard about the pick answers no variant; that
        // silence must not erase the effort the user pinned (upstream's
        // `saved.session[id]` outranks a durable session without one).
        assertFalse(body.contains("sessionVariants.remove(id)"))
    }

    @Test
    fun aSteerLandsAnEffortPinnedWithoutAModel() {
        val body = vm()
            .substringAfter("fun submit(")
            .substringBefore("private fun filesOf(")
        assertTrue(body.contains("variantWriteRef()?.let { ref ->"))
        assertTrue(body.contains("t.switchModel(id, ref.first, ref.second, _selectedVariant.value)"))
        assertTrue(
            "the fallback is the model-less half only",
            body.indexOf("if (pin != null) {") < body.indexOf("variantWriteRef()?.let"),
        )
    }

    @Test
    fun theChipAndTheDropdownTickReadTheSameValue() {
        val picker = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/ModelPicker.kt")
        assertTrue(
            "the tick comes off the same flow the chip's suffix does",
            picker.contains("selectedVariant?.takeIf { it in variants } ?: ModelPickerItems.VARIANT_DEFAULT"),
        )
        val chip = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/ModelChip.kt")
        assertTrue(chip.contains("variantLabel?.takeIf { it.isNotBlank() }?.let"))
        val screen = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")
        assertTrue(screen.contains("chatViewModel.selectedVariant.collectAsState()"))
        assertTrue(screen.contains("selectedVariant = selectedVariant"))
    }
}
