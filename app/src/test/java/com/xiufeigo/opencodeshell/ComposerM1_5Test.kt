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
        assertTrue("mention trigger", composer.contains("ComposerSuggestions.triggerAt(value.text, value.selection.end)"))
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
        // v62: the pause is the composer's own rule now — a typed query waits
        // `MentionText.DEBOUNCE_MS` out, a bare `@` asks at once (`ComposerMention`).
        assertTrue(composer.contains("delay(ComposerMention.searchDelayMs(active.query))"))
        assertTrue(composer.contains("MentionText.DEBOUNCE_MS"))
        // v67 B4: the staleness guard reads the anchor live (the same source the
        // rows render from), not a mirrored copy the caret can leave behind.
        assertTrue("stale results must not apply", composer.contains("if (liveMention != active)"))
        // v67 B2: a session switch re-asks in the new session's scope instead of
        // serving the outgoing session's paths.
        assertTrue(composer.contains("LaunchedEffect(live.mention, draftKey)"))
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

    @Test
    fun readerRejectsByDeclaredSizeAndStreamsTheRestUnderACap() {
        // The OOM fix: size is checked *before* any bytes are buffered, and
        // the stream read is capped so an unknown size cannot slurp a
        // hundreds-of-MB pick into the heap (an Error no catch saw).
        val reader = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/AttachmentReader.kt")
        assertTrue("declared size comes from cursor/fd", reader.contains("declaredSize("))
        assertTrue("the cap is enforced mid-stream", reader.contains("AttachmentPolicy.readCapped("))
        assertFalse("no unbounded slurp left", reader.contains(".readBytes()"))
    }

    // ------------------------------------------------------------- the screen

    @Test
    fun screenOwnsAttachmentsAndBridgesTheViewModel() {
        val screen = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/SessionScreen.kt")
        assertTrue(screen.contains("rememberAttachmentPicker("))
        assertTrue(screen.contains("chatViewModel.send(text, files)"))
        assertTrue(screen.contains("MentionSearchScope.scoped(reviewRootPath)"))
        assertTrue("directory-scoped VM overload", screen.contains("chatViewModel.searchFiles(query, directory, onResult = onResult)"))
        assertTrue("pending chips die with the session", screen.contains("attachments = emptyList()"))
    }

    // ------------------------------------------------------------ selection

    @Test
    fun readableBodiesAreWrappedInASelectionContainer() {
        val renderer = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/timeline/PartRenderer.kt")
        // U-4 folding moved the locus: the text branch routes through
        // FoldableMarkdown, which owns the SelectionContainer for both the
        // short body and the folded head / expanded body.
        assertTrue("text part", renderer.contains("FoldableMarkdown("))
        val foldUi = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/timeline/TimelineFoldUi.kt")
        assertTrue("folded/short bodies stay selectable", foldUi.contains("SelectionContainer"))
        // The expanded tool body has its own verticalScroll: never wrap it.
        val toolBody = renderer.substringAfter("fun ToolBody(").take(TOOL_BODY_WINDOW)
        assertFalse("tool body must stay unselectable", toolBody.contains("SelectableBody"))
        assertFalse("tool body must stay unselectable", toolBody.contains("SelectionContainer"))
    }

    @Test
    fun userBubbleIsSelectableAndHighlightsMentions() {
        val timeline = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/timeline/MessageTimeline.kt")
        assertTrue(timeline.contains("SelectionContainer"))
        // U-4 folding: the bubble renders the folded head until expanded, so
        // the mention classifier runs on the visible slice — highlight intact.
        assertTrue(timeline.contains("mentionAnnotated(visibleText, c)"))
    }

    private companion object {
        const val TOOL_BODY_WINDOW = 1200
    }
}
