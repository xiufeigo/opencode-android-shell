package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.composer.ComposerQueue
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * WAVE 2.4d (device v11) — the queued label on both surfaces that hold an
 * undelivered prompt.
 *
 * The device report had no queue wording anywhere: the composer's queue panel
 * (`ComposerQueuePanel`) showed the row with its **Steer** / ✕ actions but no
 * label, because upstream leans on the panel header — which only appears past
 * three rows. The timeline's parked-steer row already carried
 * `ui.message.queued`; the panel row now uses the very same key, so both read
 * "排队中" / "Queued".
 *
 * The two surfaces are mutually exclusive by construction (a `delivery:"queue"`
 * item belongs to the panel, a parked `delivery:"steer"` item to the timeline),
 * which the first test pins so the label can never show twice for one prompt.
 */
class QueuedLabelTest {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private fun queuePanel() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/ComposerQueuePanel.kt")

    private fun timeline() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/timeline/MessageTimeline.kt")

    private fun viewModel() = source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")

    /** `Session.Inbox.User` as measured (`GET /session/{id}/inbox`). */
    private fun inboxItem(delivery: String) = JSONObject(
        """{"id":"msg_1","sessionID":"ses_1","time":{"created":1790000000000},"type":"user",
            "payload":{"text":"hi","metadata":{"displayText":"hi"}},"delivery":"$delivery"}""",
    )

    // ------------------------------------------------- one prompt, one surface

    @Test
    fun aQueuedItemBelongsToThePanelAndASteerToTheTimeline() {
        // Queue → the composer's dock row (its own mapping), never a timeline row.
        assertNotNull(ComposerQueueHolder.rowFromJson(inboxItem("queue")))
        assertNull(pendingSteerRow(inboxItem("queue")))

        // Steer → the timeline's parked row, never a dock row.
        assertNull(ComposerQueueHolder.rowFromJson(inboxItem("steer")))
        assertNotNull(pendingSteerRow(inboxItem("steer")))
    }

    @Test
    fun thePanelHeaderAloneWouldLeaveOneToThreeRowsUnlabelled() {
        // `showCount` keeps the header off for one, two and three rows — the
        // device case was a single queued prompt, so the wording has to be on
        // the row itself.
        assertFalse(ComposerQueue.showCount(1))
        assertFalse(ComposerQueue.showCount(3))
        assertTrue(ComposerQueue.showCount(4))
    }

    // ------------------------------------------------------------- the wiring

    @Test
    fun theDockRowCarriesTheQueuedLabel() {
        val panel = queuePanel()
        val row = panel.substringAfter("private fun QueueRow(")
        assertTrue(
            "the queue row must render the queued label",
            row.contains("stringResource(R.string.ui_message_queued)"),
        )
        // Leading the row, before the display text / the Steer action.
        assertTrue(
            row.indexOf("R.string.ui_message_queued") < row.indexOf("R.string.session_queue_steer"),
        )
    }

    @Test
    fun bothSurfacesShareTheSameStringKey() {
        // Same wording on both paths: the timeline's parked steer row and the
        // dock row must not drift into two different labels.
        assertTrue(timeline().contains("R.string.ui_message_queued"))
        assertTrue(queuePanel().contains("R.string.ui_message_queued"))
        // …and the key is not a dead one: it exists in all three bundles.
        for (bundle in listOf("values", "values-zh-rCN", "values-zh-rTW")) {
            val xml = source("src/main/res/$bundle/strings_oc_i18n.xml")
            assertTrue("ui_message_queued missing in $bundle", xml.contains("name=\"ui_message_queued\""))
        }
    }

    @Test
    fun theInboxIsReadOnceOnSessionOpen() {
        // Both surfaces are event-driven (`session.inbox.*`) and both are cleared
        // by `observeSession`, so a session reopened with an item still waiting
        // in the inbox showed neither until the next frame. The v2 transport
        // becoming ready is the session-open moment that now reads it.
        val vm = viewModel()
        val v2Branch = vm.substringAfter("if (generation == ServerGeneration.V2) {")
            .substringBefore("} else {")
        assertTrue(v2Branch.contains("if (sessionID != null) refreshInbox()"))
        assertEquals(
            "one session-open read, not a poll",
            1,
            v2Branch.split("refreshInbox()").size - 1,
        )
        // The `session.inbox.*` frame path still owns every change after that.
        assertTrue(
            vm.contains(
                "if (eventType.startsWith(\"session.inbox.\") && acceptsV2Session(sessionID, routed)) refreshInbox()",
            ),
        )
    }
}
