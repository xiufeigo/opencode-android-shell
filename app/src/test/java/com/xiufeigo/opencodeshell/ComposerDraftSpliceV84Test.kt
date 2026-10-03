package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.composer.ComposerDrafts
import com.xiufeigo.opencodeshell.ui.session.composer.ComposerDrafts.DraftAttachment
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v84 P2 (emulator-5554 `research/v84_verify/5554/`): a draft handed back after
 * an interrupted send **glued** itself onto the next message.
 *
 * `49_bg2_state.png` shows the sent bubble as one message —
 * `E2ECHK5554 bg notifyE2ECHK5554 bg2 retry` — where the first half is the draft
 * a failed/interrupted send had restored (`47_relaunch.png` shows it sitting in
 * the composer) and the second half is what the user typed next. `55_deeplink_landing.png`
 * shows the splice shape: `E2ECHK5554 deeplink c` + `E2ECHK5554 notify deep link`
 * + `heck` — the stored payload landed *inside* the line being typed.
 *
 * The mechanism is a race, not a merge: the failed send is answered by a
 * coroutine (`ChatViewModel.restoreDraft` → `ComposerDrafts.restoreHandoff`), so
 * the handback can arrive seconds after the composer is back on screen — long
 * after the user started the next message. The composer's restore effect wrote
 * the payload straight over whatever the field held (`value =
 * TextFieldValue(restored, …)`), and the platform then folded the IME's
 * in-flight composition into that write: one message, two authors.
 *
 * The rule is now in the store, where it is JVM-testable: a handback only lands
 * in an editor the user has not written into ([ComposerDrafts.takeRestore] with
 * the editor's own text). The one exception is the review line comment
 * ([ComposerDrafts.appendLine]), which *grew* the editor's own text — that one
 * must land.
 */
class ComposerDraftSpliceV84Test {

    private fun attachment(name: String = "shot.png") =
        DraftAttachment(mime = "image/png", filename = name, dataUrl = "data:image/png;base64,xx")

    @Before
    fun reset() {
        ComposerDrafts.reset()
    }

    // ------------------------------------------------------------ the rule

    @Test
    fun `a handback lands in the editor the send cleared`() {
        // The ordinary failed send: `Composer.submit` clears the field before it
        // dispatches, so the editor is empty when the payload comes back.
        assertEquals("hello server", ComposerDrafts.adoptRestore("", "hello server", append = false))
        assertEquals("hello server", ComposerDrafts.adoptRestore("   ", "hello server", append = false))
    }

    @Test
    fun `a handback never lands on text the user has written`() {
        // The measured glue: the payload arrives while the next message is being
        // typed. Not a prefix, not a suffix, not empty — the user's text wins.
        assertNull(
            ComposerDrafts.adoptRestore(
                "E2ECHK5554 bg2 retry",
                "E2ECHK5554 bg notify",
                append = false,
            ),
        )
        // …including the spliced shape of `55_deeplink_landing.png`, and the one
        // a prefix test would have let through: a first character of the next
        // message is still the user's own text.
        assertNull(
            ComposerDrafts.adoptRestore(
                "E2ECHK5554 deeplink c",
                "E2ECHK5554 notify deep link",
                append = false,
            ),
        )
        assertNull(ComposerDrafts.adoptRestore("E", "E2ECHK5554 bg notify", append = false))
    }

    @Test
    fun `an editor that already holds the payload is left alone`() {
        // A mounted composer seeds itself from the same store, so the value is
        // already there: re-writing it would be a needless `restartInput()` for
        // the IME (the v66 mechanism) and a caret jump.
        assertNull(ComposerDrafts.adoptRestore("keep me", "keep me", append = false))
    }

    @Test
    fun `the review append still lands over the draft it grew`() {
        // `appendLine` builds `draft.trimEnd() + "\n" + line` out of the editor's
        // own text, so writing it back is the intent — the field must show the
        // appended line, and F-2 would silently lose it otherwise.
        assertEquals(
            "typed\nquoted line",
            ComposerDrafts.adoptRestore("typed", "typed\nquoted line", append = true),
        )
        // …and only while the field still holds that base text.
        assertNull(
            ComposerDrafts.adoptRestore("typed more", "typed\nquoted line", append = true),
        )
    }

    @Test
    fun `nothing to restore is nothing to write`() {
        assertNull(ComposerDrafts.adoptRestore("", "", append = false))
        assertNull(ComposerDrafts.adoptRestore("typed", "", append = true))
    }

    // ------------------------------------------------------- the store's take

    @Test
    fun `takeRestore is handed the editor's own text`() {
        ComposerDrafts.restoreHandoff("ses_a", "E2ECHK5554 bg notify", listOf(attachment()))

        // The user typed the next message first: the payload is refused…
        assertNull(ComposerDrafts.takeRestore("ses_a", "E2ECHK5554 bg2 retry"))
        // …and consumed either way: the one-shot is spent, so it cannot land on
        // a later frame either.
        assertFalse(ComposerDrafts.pendingRestore("ses_a"))
        // The handoff's other halves are untouched by the refusal.
        assertEquals(listOf(attachment()), ComposerDrafts.attachments("ses_a"))
    }

    @Test
    fun `takeRestore still answers the payload for an empty editor`() {
        ComposerDrafts.restoreHandoff("ses_a", "E2ECHK5554 bg notify", listOf(attachment()))

        assertEquals("E2ECHK5554 bg notify", ComposerDrafts.takeRestore("ses_a", ""))
        assertFalse(ComposerDrafts.pendingRestore("ses_a"))
        assertNull("and only once", ComposerDrafts.takeRestore("ses_a", ""))
    }

    @Test
    fun `a keystroke supersedes a pending restore`() {
        ComposerDrafts.restoreHandoff("ses_a", "old payload")
        assertTrue(ComposerDrafts.pendingRestore("ses_a"))

        ComposerDrafts.set("ses_a", "new message")

        assertFalse("the live edit wins over the handback", ComposerDrafts.pendingRestore("ses_a"))
        assertNull(ComposerDrafts.takeRestore("ses_a", "new message"))
        assertEquals("new message", ComposerDrafts.text("ses_a"))
    }

    @Test
    fun `a refused payload is spent, not queued for a later frame`() {
        // The one-shot is consumed by the refusal, so the payload cannot land on
        // the next empty moment either (that would be the same glue, one message
        // later).
        ComposerDrafts.restoreHandoff("ses_a", "old payload")

        assertNull(ComposerDrafts.takeRestore("ses_a", "new message"))
        assertFalse(ComposerDrafts.pendingRestore("ses_a"))
        assertNull(ComposerDrafts.takeRestore("ses_a", ""))
    }

    @Test
    fun `the folded forms are what the rule compares`() {
        // A draft written before the W5-F fold (or by an older build) holds `／`;
        // the editor only ever shows the half-width one, so a payload that is
        // already on screen must still read as "already there" instead of being
        // written again.
        ComposerDrafts.restore("ses_a", "／init")
        assertNull(ComposerDrafts.takeRestore("ses_a", "/init"))
    }

    // ------------------------------------------- the wiring that cannot run here

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private val composer = source(
        "src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt",
    )

    @Test
    fun `the restore effect asks before it writes`() {
        assertTrue(
            "the editor's own text is what the store is handed",
            composer.contains("ComposerDrafts.takeRestore(draftKey, value.text)"),
        )
        assertTrue(
            "and a refusal writes nothing at all",
            composer.contains("val restored = ComposerDrafts.takeRestore(draftKey, value.text)\n            if (restored != null) {"),
        )
    }
}
