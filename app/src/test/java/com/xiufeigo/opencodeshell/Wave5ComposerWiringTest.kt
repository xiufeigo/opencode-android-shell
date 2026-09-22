package com.xiufeigo.opencodeshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * W5-C wiring guards: the pieces that cannot be asserted on the JVM (Compose
 * trees, keyboard focus, popups) get the repo's established source-level check
 * (`ComposerM1_5Test`, `ShellWiringTest`) — the composer must actually *call* the
 * new plumbing, and every new user-visible string must exist in all three locales.
 *
 * It also pins the one coupling that would otherwise silently rot: the view
 * model's failed-send rollback writes the composer's draft store, so the two
 * sides must reference the very same object.
 */
class Wave5ComposerWiringTest {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    private fun composer() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt")

    private fun viewModel() = source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")

    private fun client() = source("src/main/java/com/xiufeigo/opencodeshell/OpenCodeClient.kt")

    // ------------------------------------------------------------- composer

    @Test
    fun composerWiresTheSlashMenuAndTheAddMenu() {
        val composer = composer()
        assertTrue("command detection", composer.contains("ComposerSuggestions.commandQuery("))
        assertTrue("command candidates", composer.contains("ComposerSuggestions.commandItems("))
        assertTrue("submitted server command", composer.contains("ComposerSuggestions.invocation("))
        assertTrue("the `+` menu is a real menu", composer.contains("AddMenuRow("))
        assertTrue("its rows insert the triggers", composer.contains("insertText(\"/\")"))
        assertTrue("…and the context trigger", composer.contains("insertText(\"@\")"))
        assertTrue("built-in /model opens the picker", composer.contains("modelOpenRequest += 1"))
        assertTrue("built-in /agent opens the agent menu", composer.contains("agentOpenRequest += 1"))
    }

    @Test
    fun composerWiresShellModeAndItsRoute() {
        val composer = composer()
        // W5-F moved the literal `updated.text == "!"` into
        // `HalfWidthText.isShellTrigger`, so a Chinese IME's `！` opens the mode
        // too; the rule itself is pinned by `Wave5HalfWidthInputTest`.
        assertTrue("`!` (half- or full-width) enters shell mode", composer.contains("HalfWidthText.isShellTrigger(updated.text)"))
        assertTrue("shell placeholder", composer.contains("R.string.oc_composer_placeholder_shell"))
        assertTrue("monospace editor", composer.contains("FontFamily.Monospace"))
        assertTrue("escape leaves the mode", composer.contains("shellMode = false"))
        assertTrue("shell submits through the payload", composer.contains("shell = shellMode"))
    }

    @Test
    fun composerWiresAgentChipVariantFocusHistoryAndDrafts() {
        val composer = composer()
        assertTrue("agent chip", composer.contains("AgentChip("))
        assertTrue("variant reaches the picker", composer.contains("onSelectVariant = onSelectVariant"))
        assertTrue("auto focus", composer.contains("FocusRequester"))
        assertTrue("focus is requested", composer.contains("focusRequester.requestFocus()"))
        assertTrue("history up", composer.contains("history.up("))
        assertTrue("history down", composer.contains("history.down("))
        assertTrue("history records submits", composer.contains("history.add(outgoing, shellMode)"))
        assertTrue("drafts are written per keystroke", composer.contains("ComposerDrafts.set(draftKey"))
        assertTrue("a failed send is adopted", composer.contains("ComposerDrafts.takeRestore("))
        assertTrue("draft key follows the session", composer.contains("ComposerDrafts.key(sessionID)"))
        assertTrue("esc stops a run", composer.contains("onStop()"))
    }

    @Test
    fun composerWiresMentionCategoriesPasteAndUploadProgress() {
        val composer = composer()
        assertTrue("category candidates", composer.contains("ComposerSuggestions.mentionItems("))
        assertTrue("files still come from the find bridge", composer.contains("MentionText.candidates(files)"))
        assertTrue("paste detection", composer.contains("AttachmentPolicy.pasteKind("))
        assertTrue("paste is reachable without a DOM event", composer.contains("pasteFromClipboard()"))
        assertTrue("upload progress row", composer.contains("UploadProgressRow("))
        assertTrue("upload cancel", composer.contains("AttachmentUploads.cancel()"))
    }

    @Test
    fun theComposerKeepsItsM15Contract() {
        // The M1.5 rules the earlier suites pin must survive the rewrite.
        val composer = composer()
        assertTrue(composer.contains("onSend(outgoing, attachments)"))
        assertTrue(composer.contains("MentionText.insert("))
        assertTrue(composer.contains("MentionText.anchorAt("))
        assertTrue(composer.contains("AttachmentPreviewRow("))
    }

    // ----------------------------------------------------------- view model

    @Test
    fun viewModelOwnsTheCatalogsAndTheThreeWriteRoutes() {
        val vm = viewModel()
        assertTrue(vm.contains("fun refreshCatalogs()"))
        assertTrue(vm.contains("client.agents(directory)"))
        assertTrue(vm.contains("client.commands(directory)"))
        assertTrue(vm.contains("client.skills(directory)"))
        assertTrue(vm.contains("fun selectAgent(name: String?)"))
        assertTrue(vm.contains("fun selectVariant(label: String?)"))
        assertTrue(vm.contains("fun effectiveAgent(): String"))
        assertTrue(vm.contains("fun effectiveVariant(): String?"))
        assertTrue(vm.contains("fun submit("))
        assertTrue(vm.contains("fun sendShell(text: String)"))
        assertTrue(vm.contains("fun runCommand(name: String, arguments: String)"))
        assertTrue("the shell route", vm.contains("sendShellAs("))
        assertTrue("the command route", vm.contains("sendCommandAs("))
        assertTrue("agent + variant are pinned on the prompt", vm.contains("variant = variant,"))
    }

    @Test
    fun aFailedSendRestoresTheComposerDraftAndDropsItsHistoryEntry() {
        val vm = viewModel()
        assertTrue(vm.contains("ComposerDrafts.restore(key, text)"))
        assertTrue(vm.contains("ComposerHistory.of(key).remove(text, shell)"))
    }

    // ---------------------------------------------------------------- client

    @Test
    fun clientExposesTheW5Endpoints() {
        val client = client()
        assertTrue(client.contains("fun shellCommand("))
        assertTrue(client.contains("fun runCommand("))
        assertTrue(client.contains("fun agents("))
        assertTrue(client.contains("fun commands("))
        assertTrue(client.contains("fun skills("))
        assertTrue(client.contains("fun health()"))
        assertTrue(client.contains("/session/\$sessionID/shell"))
        assertTrue(client.contains("/session/\$sessionID/command"))
        assertTrue(client.contains("/global/health"))
    }

    // ------------------------------------------------------ attachments (B6)

    @Test
    fun thePickerReadsThroughTheUploadQueueSoProgressAndCancelExist() {
        val picker =
            source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/AttachmentPicker.kt")
        assertTrue(picker.contains("AttachmentUploads.start("))
        assertTrue(
            source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/AttachmentReader.kt")
                .contains("onProgress: (index: Int, total: Int, label: String?) -> Unit"),
        )
        val row =
            source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/AttachmentPreviewRow.kt")
        assertTrue("edge fades", row.contains("AttachmentEdgeFade("))
        assertTrue("24 dp masks", row.contains("FADE_WIDTH = 24.dp"))
        assertTrue("in-app preview", row.contains("AttachmentPreviewOverlay("))
    }

    // ------------------------------------------------------------- strings

    private val newKeys = listOf(
        "oc_composer_placeholder_shell",
        "oc_composer_add",
        "oc_composer_add_attachments",
        "oc_composer_add_commands",
        "oc_composer_add_context",
        "oc_composer_add_shell",
        "oc_composer_add_paste",
        "oc_composer_shell_mode",
        "oc_composer_agent",
        "oc_composer_agent_default",
        "oc_composer_choose_agent",
        "oc_composer_variant_group",
        "oc_composer_variant_default",
        "oc_composer_mention_agent",
        "oc_composer_mention_command",
        "oc_composer_mention_skill",
        "oc_composer_mention_file",
        "oc_slash_model",
        "oc_slash_agent",
        "oc_attach_preview_close",
        "oc_attach_uploading",
        "oc_attach_cancel",
    )

    @Test
    fun everyNewStringExistsInAllThreeLocales() {
        val locales = listOf(
            "values",
            "values-zh-rCN",
            "values-zh-rTW",
        )
        for (locale in locales) {
            val xml = source("src/main/res/$locale/strings.xml")
            val missing = newKeys.filterNot { xml.contains("name=\"$it\"") }
            assertEquals("$locale is missing $missing", emptyList<String>(), missing)
        }
    }

    @Test
    fun localizedComposerStringsAreNotLeftInEnglish() {
        // The two locales are translations of the English source, so a copied
        // English value (except the intentional "Shell" loanword) is a bug.
        val english = source("src/main/res/values/strings.xml")
        for (locale in listOf("values-zh-rCN", "values-zh-rTW")) {
            val xml = source("src/main/res/$locale/strings.xml")
            for (key in newKeys) {
                if (key == "oc_composer_add_shell" || key == "oc_composer_shell_mode") continue
                val value = xml.lineSequence()
                    .firstOrNull { it.contains("name=\"$key\"") }
                    ?.substringAfter('>')
                    ?.substringBefore('<')
                val source = english.lineSequence()
                    .firstOrNull { it.contains("name=\"$key\"") }
                    ?.substringAfter('>')
                    ?.substringBefore('<')
                assertTrue("$locale/$key is still the English copy", value != source)
            }
        }
    }
}
