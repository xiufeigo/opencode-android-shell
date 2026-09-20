package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.composer.ModelOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * M1.5 composer wiring: the only model data the data layer exposes today, plus
 * source-level guards for the pieces that cannot be asserted on the JVM
 * (SAF contract, popover/chip presence, `SelectionContainer` placement).
 *
 * The repo already uses this style for UI glue (`LongPressSelectionTest`,
 * `ImeSessionLayoutTest`), because the Compose tree itself is only reachable from
 * an instrumented test.
 */
class ComposerM1_5Test {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    private fun composerSource() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt")

    // -------------------------------------------------------- model options

    @Test
    fun modelOptionsExposeTheConfiguredModelOnly() {
        val options = ModelOptions.fromLabel("claude-sonnet-4")
        assertEquals(1, options.size)
        assertEquals("claude-sonnet-4", options.first().id)
        assertEquals("claude-sonnet-4", options.first().label)
    }

    @Test
    fun modelOptionsAreEmptyWithoutAConfiguredModel() {
        assertTrue(ModelOptions.fromLabel(null).isEmpty())
        assertTrue(ModelOptions.fromLabel("").isEmpty())
        assertTrue(ModelOptions.fromLabel("   ").isEmpty())
    }

    @Test
    fun modelOptionsTrimTheLabel() {
        assertEquals(listOf("gpt-4o"), ModelOptions.fromLabel("  gpt-4o  ").map { it.id })
    }

    // -------------------------------------------------------------- composer

    @Test
    fun composerWiresAttachmentsMentionsAndModelPicker() {
        val composer = composerSource()
        assertTrue("attachment strip", composer.contains("AttachmentPreviewRow("))
        assertTrue("mention popover", composer.contains("MentionPopover("))
        assertTrue("mention trigger", composer.contains("MentionText.anchorAt("))
        assertTrue("mention insert", composer.contains("MentionText.insert("))
        assertTrue("mention colouring", composer.contains("MentionColorTransformation"))
        assertTrue("model chip", composer.contains("ModelChip("))
        assertTrue("send with attachments", composer.contains("onSend(outgoing, attachments)"))
        assertFalse("SAF read is the reader's job", composer.contains("contentResolver"))
    }

    @Test
    fun composerNoLongerCarriesM1Placeholders() {
        val composer = composerSource()
        assertFalse(composer.contains("TODO M1.5"))
        assertFalse(composer.contains("TODO M2"))
    }

    @Test
    fun composerDebouncesTheFileSearch() {
        val composer = composerSource()
        assertTrue(composer.contains("delay(MentionText.DEBOUNCE_MS)"))
        assertTrue("stale results must not apply", composer.contains("if (trigger != active)"))
    }

    @Test
    fun safePickerUsesTheMultiDocumentContractAndTheSharedPolicy() {
        val picker = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/AttachmentPicker.kt")
        assertTrue(picker.contains("ActivityResultContracts.OpenMultipleDocuments()"))
        assertTrue(picker.contains("arrayOf(\"*/*\")"))
        assertTrue(picker.contains("AttachmentPolicy.merge("))
        assertTrue(picker.contains("R.string.chat_attach_limit"))
        assertTrue(picker.contains("R.string.chat_attach_too_large"))
    }

    @Test
    fun readerPacksDataUrlsAndFollowsTheBytesForMime() {
        val reader = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/AttachmentReader.kt")
        assertTrue(reader.contains("AttachmentPolicy.attachment("))
        assertTrue("image re-encode must report jpeg", reader.contains("\"image/jpeg\""))
        assertTrue(reader.contains("MAX_IMAGE_EDGE"))
    }

    // ------------------------------------------------------------- the screen

    @Test
    fun screenOwnsAttachmentsAndBridgesTheViewModel() {
        val screen = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")
        assertTrue(screen.contains("rememberAttachmentPicker("))
        assertTrue(screen.contains("chatViewModel.send(text, files)"))
        assertTrue(screen.contains("chatViewModel.searchFiles(query, onResult)"))
        assertTrue("pending chips die with the session", screen.contains("attachments = emptyList()"))
    }

    // ------------------------------------------------------------ selection

    @Test
    fun readableBodiesAreWrappedInASelectionContainer() {
        val renderer = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/timeline/PartRenderer.kt")
        assertTrue(renderer.contains("SelectionContainer(content = content)"))
        assertTrue("text part", renderer.contains("SelectableBody {"))
        // The expanded tool body has its own verticalScroll: never wrap it.
        val toolBody = renderer.substringAfter("private fun ToolBody(").take(TOOL_BODY_WINDOW)
        assertFalse("tool body must stay unselectable", toolBody.contains("SelectableBody"))
    }

    @Test
    fun userBubbleIsSelectableAndHighlightsMentions() {
        val timeline = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/timeline/MessageTimeline.kt")
        assertTrue(timeline.contains("SelectionContainer"))
        assertTrue(timeline.contains("mentionAnnotated(text, c)"))
    }

    private companion object {
        const val TOOL_BODY_WINDOW = 1200
    }
}
