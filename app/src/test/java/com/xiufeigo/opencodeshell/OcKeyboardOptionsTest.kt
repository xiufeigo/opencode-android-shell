package com.xiufeigo.opencodeshell

import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.xiufeigo.opencodeshell.ui.components.OcKeyboard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Gboard rewrote what the rename / title / identifier fields were handed:
 * `:` came out as the full-width `：`, the pinyin IME swallowed ASCII while it
 * was composing, and autocorrect replaced identifiers with dictionary words.
 * The value of these fields is not prose — it is a name or a command the app
 * sends verbatim — so the IME must not touch it.
 *
 * The decision is [OcKeyboard]'s (pure, pinned as behaviour below); the fields
 * live inside `@Composable` functions a plain JVM test cannot call, so that half
 * stays a source-level pin — the same split `TabRenameFieldSelectionTest` and
 * `SessionTitleInlineEditTest` use.
 */
class OcKeyboardOptionsTest {

    private val literalDone = "keyboardOptions = OcKeyboard.literal(imeAction = ImeAction.Done)"

    // ------------------------------------------------------------- behaviour

    @Test
    fun literalKeepsTheLayoutAndSwitchesAutocorrectOff() {
        val options = OcKeyboard.literal()
        // `null` means "unspecified", and the IME default is autocorrect **on**
        // (`foundation 1.7.6` `KeyboardOptions.getAutoCorrectOrDefault`), so the
        // flag has to be an explicit `false`, not just an absent one.
        assertFalse("autocorrect must be switched off explicitly", options.autoCorrectEnabled ?: true)
        assertEquals(KeyboardType.Text, options.keyboardType)
        assertEquals(ImeAction.Default, options.imeAction)
    }

    @Test
    fun literalCarriesTheImeActionTheFieldAlreadyHad() {
        assertEquals(ImeAction.Done, OcKeyboard.literal(ImeAction.Done).imeAction)
        assertEquals(ImeAction.Search, OcKeyboard.literal(ImeAction.Search).imeAction)
    }

    @Test
    fun identifierAlsoAsksForTheAsciiLayout() {
        val options = OcKeyboard.identifier()
        assertFalse(options.autoCorrectEnabled ?: true)
        assertEquals(KeyboardType.Ascii, options.keyboardType)
        assertEquals(ImeAction.Default, options.imeAction)
        assertEquals(ImeAction.Done, OcKeyboard.identifier(ImeAction.Done).imeAction)
    }

    // ---------------------------------------------------------------- wiring

    @Test
    fun everyTitleEditorBuildsItsOptionsThroughOcKeyboard() {
        val editors = mapOf(
            "SessionRowDialogs.kt" to window(
                source("ui/session/drawer/SessionRowDialogs.kt"),
                "fun SessionRenameDialog(",
                "fun SessionDeleteDialog(",
            ),
            "SessionHeader.kt" to window(
                source("ui/session/header/SessionHeader.kt"),
                "private fun InlineTitleInput(",
                "private fun SessionOverflowMenu(",
            ),
            "BottomTabStrip.kt" to window(
                source("ui/nav/BottomTabStrip.kt"),
                "internal fun TabRenameField(",
                "internal fun tabRenameEntrySelection(",
            ),
            "HomeSessionsList.kt" to window(
                source("ui/home/HomeSessionsList.kt"),
                "private fun HomeSessionRenameEditor(",
                "private fun HomeSessionLeading(",
            ),
        )
        for ((name, field) in editors) {
            assertTrue("$name: the rename editor must carry OcKeyboard.literal(Done)", field.contains(literalDone))
            // Hand-built options are what left autocorrect on.
            assertFalse("$name: still builds KeyboardOptions by hand", field.contains("KeyboardOptions("))
        }
    }

    @Test
    fun theTerminalAndWorkspaceIdentifiersGetTheAsciiLayout() {
        // A terminal name, a branch name and a workspace name are ASCII
        // identifiers the app matches/persists verbatim.
        val identifierFields = mapOf(
            "TerminalPanel.kt" to window(
                source("ui/terminal/TerminalPanel.kt"),
                "private fun OcTerminalRenameField(",
                "/** Ghost icon button of the strip",
            ),
            "WorktreeCreateDialog.kt" to window(
                source("ui/workspace/WorktreeCreateDialog.kt"),
                "private fun BranchSearchField(",
                "private fun BranchRow(",
            ),
            "NewSessionScreen.kt" to window(
                source("ui/home/NewSessionScreen.kt"),
                "private fun WorktreeSearchField(",
                "private const val SLOW_MS",
            ),
        )
        for ((name, field) in identifierFields) {
            // `identifier` is the ASCII builder (pinned as behaviour above); the
            // fields differ only in the IME action they ask for, so match the
            // call with its arguments rather than the bare no-arg form.
            assertTrue(
                "$name: the identifier field must ask for the ASCII layout",
                Regex("""keyboardOptions = OcKeyboard\.identifier\([^)]*\)""").containsMatchIn(field),
            )
        }
    }

    @Test
    fun theShellCommandFieldStopsTheRewriteWithoutForcingAscii() {
        // A command line may name a non-ASCII path, so the layout stays free —
        // only the rewrite goes away.
        val field = window(
            source("ui/terminal/BackgroundShellPanel.kt"),
            "private fun ShellCreateRow(",
            "private fun ShellRow(",
        )
        assertTrue(field.contains("keyboardOptions = OcKeyboard.literal(),"))
        assertFalse(field.contains("OcKeyboard.identifier("))
    }

    @Test
    fun theProjectDialogCoversBothOfItsLiterals() {
        // The name and the startup command are both persisted and replayed
        // verbatim, and both were rewritten while being typed.
        val fields = window(
            source("ui/home/HomeProjectEditDialog.kt"),
            "private fun EditFields(",
            "private fun FieldLabel(",
        )
        assertEquals(2, count(fields, "keyboardOptions = OcKeyboard.literal(),"))
    }

    @Test
    fun theSettingsRenameFieldThreadsTheFlagThroughOcTextField() {
        val component = window(
            source("ui/settings/SettingsComponents.kt"),
            "fun OcTextField(",
            "fun OcSwitch(",
        )
        assertTrue("OcTextField must default the flag off", component.contains("autoCorrect: Boolean = false,"))
        assertTrue("and use it", component.contains("autoCorrectEnabled = autoCorrect,"))

        val rename = window(
            source("ui/settings/SettingsConnectUi.kt"),
            "internal fun CredentialRenameDialog(",
            "fun IntegrationConnectSheet(",
        )
        assertTrue("the credential rename label is a literal", rename.contains("autoCorrect = false,"))
    }

    // ----------------------------------------------------------- invariants

    @Test
    fun everyFieldInTheRenameAndIdentifierFilesIsGuarded() {
        // In these files **every** `BasicTextField` is a rename / title /
        // identifier field, so the count of guarded fields must match the count
        // of fields: a new one cannot slip in with autocorrect on.
        //
        // `NewSessionScreen.kt` and `HomeSessionsList.kt` are left out on
        // purpose: both also carry a *search* field, which is prose the IME may
        // correct. Their rename/identifier fields are pinned individually above.
        val files = listOf(
            "ui/terminal/TerminalPanel.kt",
            "ui/terminal/BackgroundShellPanel.kt",
            "ui/workspace/WorktreeCreateDialog.kt",
            "ui/home/HomeProjectEditDialog.kt",
            "ui/session/drawer/SessionRowDialogs.kt",
            "ui/session/header/SessionHeader.kt",
            "ui/nav/BottomTabStrip.kt",
        )
        for (file in files) {
            val text = source(file)
            assertEquals(
                "$file: every field must carry OcKeyboard options",
                count(text, "BasicTextField("),
                count(text, "keyboardOptions = OcKeyboard."),
            )
        }
    }

    @Test
    fun theNoAutocorrectFlagIsBuiltInOnePlace() {
        val helper = source("ui/components/OcKeyboard.kt")
        // Every builder in the helper switches autocorrect off explicitly —
        // `>=` so a new builder (another agent's field) does not have to be
        // counted here, only to be explicit.
        assertTrue(
            "every OcKeyboard builder must switch autocorrect off",
            count(helper, "autoCorrectEnabled = false,") >= 2,
        )
        assertEquals(1, count(helper, "fun literal("))
        assertEquals(1, count(helper, "fun identifier("))
        assertTrue("the identifier builder asks for the ASCII layout", helper.contains("KeyboardType.Ascii"))
    }

    // ---------------------------------------------------------------- helpers

    private fun source(relative: String): String {
        val path = "src/main/java/com/xiufeigo/opencodeshell/$relative"
        val file = File(path).takeIf { it.isFile } ?: File("app/$path")
        assertTrue("missing source: $relative", file.isFile)
        return file.readText()
    }

    private fun window(src: String, from: String, to: String): String {
        val start = src.indexOf(from)
        assertTrue("missing window start: $from", start >= 0)
        val end = src.indexOf(to, start + from.length)
        assertTrue("missing window end: $to", end > start)
        return src.substring(start, end)
    }

    private fun count(src: String, needle: String): Int =
        Regex(Regex.escape(needle)).findAll(src).count()
}
