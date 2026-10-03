package com.xiufeigo.opencodeshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * WAVE 2.4b wiring guards: the pieces that cannot be asserted on the JVM
 * (Compose trees, popups) get the repo's established source-level check
 * (`Wave5ComposerWiringTest`, `ComposerSubmitTest`) — the composer must
 * actually *call* the new plumbing, and the generated i18n keys it renders
 * must exist in the default bundle (runtime fallback covers zh).
 *
 * New user-visible strings: none — the toggle/chip/panel reuse the generated
 * `ui_promptInput_*` / `session_queue_*` keys already in
 * `app/src/main/res/values/strings_oc_i18n.xml`.
 */
class Wave24bComposerWiringTest {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    private fun composer() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt")

    @Test
    fun composerRecordsStructuredMentions() {
        val composer = composer()
        assertTrue("popover pick inserts the chip text", composer.contains("MentionText.insert("))
        assertTrue("popover pick records a token", composer.contains("MentionTokens.tokenFor("))
        assertTrue("text edits drop dead tokens", composer.contains("MentionTokens.retokenize("))
        assertTrue("tokens clear on submit", composer.contains("tokens = emptyList()"))
    }

    @Test
    fun composerBuildsAStructuredPayloadWithDelivery() {
        val composer = composer()
        assertTrue(composer.contains("ComposerSubmission.build("))
        assertTrue("structured submit entry point", composer.contains("onStructuredSubmit"))
        assertTrue("toggle arms the offered delivery", composer.contains("alternateNext = true"))
        assertTrue("delivery defaults to the setting", composer.contains("ComposerDelivery.delivery(running, followUpBehavior)"))
        assertTrue("hint visibility rule", composer.contains("ComposerDelivery.alternate("))
    }

    @Test
    fun composerShowsTheQueueSurfaceGenerationGated() {
        val composer = composer()
        assertTrue("v1 keeps send-and-wait", composer.contains("ComposerQueue.isQueueSupported(generation)"))
        assertTrue("toggle chip", composer.contains("DeliveryToggleChip("))
        assertTrue("queue panel", composer.contains("ComposerQueuePanel("))
        assertTrue("queue edit loads the row", composer.contains("editQueued("))
        assertTrue("edit confirm goes to the VM", composer.contains("onQueueConfirm"))
        assertTrue("steer/remove go to the VM", composer.contains("onQueueSteer"))
    }

    @Test
    fun everyRenderedQueueStringExistsInTheDefaultBundle() {
        val xml = source("src/main/res/values/strings_oc_i18n.xml")
        val keys = listOf(
            "ui_promptInput_steer",
            "ui_promptInput_queue",
            "ui_promptInput_steerHint",
            "session_queue_steer",
            "session_queue_send",
            "session_queue_remove",
            "session_queue_count_one",
            "session_queue_count_other",
            "session_queue_attachments_one",
            "session_queue_attachments_other",
        )
        val missing = keys.filterNot { xml.contains("name=\"$it\"") }
        assertEquals("default bundle is missing $missing", emptyList<String>(), missing)
    }

    @Test
    fun legacySubmitPathsSurviveUntouched() {
        val composer = composer()
        assertTrue("onSubmit fallback", composer.contains("submitPayload"))
        assertTrue("onSend fallback", composer.contains("onSend(outgoing, attachments)"))
        assertTrue("shell/command routing", composer.contains("ComposerSuggestions.invocation(trimmed, known)"))
        assertTrue("history records plain submits", composer.contains("history.add(outgoing, shellMode)"))
    }
}
