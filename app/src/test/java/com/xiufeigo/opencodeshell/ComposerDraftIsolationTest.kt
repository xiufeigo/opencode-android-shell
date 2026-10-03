package com.xiufeigo.opencodeshell

import androidx.compose.runtime.saveable.SaveableStateRegistry
import com.xiufeigo.opencodeshell.ui.session.composer.ComposerDrafts
import com.xiufeigo.opencodeshell.ui.session.composer.ComposerDrafts.DraftAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * v16: a freshly created session must start empty — text **and** attachments — and
 * only an explicit handoff may carry a draft across sessions.
 *
 * Measured (`research/v16_verify/misc/v16_draft_before_switch.png` /
 * `v16_draft_after_switch.png`): session B held `DRAFT_V16 keepme` plus an
 * `untitled.json` attachment; creating session C showed **B's text in C's editor**
 * while C's attachment strip stayed empty.
 *
 * Root cause — the text never came from C's draft. [ComposerDrafts] keys drafts by
 * session and held nothing for C; what leaked was the *editor's saveable slot*:
 *
 * - `ui/App.kt` navigates to a created session with `launchSingleTop`, and
 *   `NavController.launchSingleTopInternal` rebuilds the entry through
 *   `NavBackStackEntry(oldEntry, args)`, which **keeps `entry.id`**
 *   (`navigation-common-2.8.5` `NavBackStackEntry.kt`);
 * - `NavHost` wraps each destination in `SaveableStateHolder.SaveableStateProvider`
 *   keyed by that same id (`navigation-compose` `NavBackStackEntryProvider.kt` →
 *   `BackStackEntryIdViewModel.id`), so leaving B for the new-session route saved
 *   B's whole saveable map and re-entering handed it to C;
 * - `rememberSaveable` resolves as `restored ?: init()`
 *   (`runtime-saveable-1.7.6` `RememberSaveable.kt`) and looks the restore up by the
 *   slot key alone, so with the default key (the call site's composite hash) B's
 *   `TextFieldValue` was restored into C's editor — the draft key passed as an
 *   *input* only decides when `init()` re-runs, never whether a restored value is
 *   preferred. Attachments are plain screen state (`SessionScreen.attachments`),
 *   not saveable, which is why only the text followed.
 *
 * The fix is [ComposerDrafts.saveableSlot]: the editor's saveable slot is named
 * after the draft key, so a restore can only ever refill the slot of the session it
 * was saved for, and a new session's editor is initialized from *its own* draft.
 * The intentional handoff paths ([ComposerDrafts.stash] / [restore] /
 * [restoreHandoff] / [move]) are untouched: they write the draft, they never lean
 * on a stale editor slot.
 *
 * Layers, since the JVM test source set has no Compose runtime: the real
 * `SaveableStateRegistry` (the class `rememberSaveable` restores from) for the
 * mechanism, [ComposerDrafts] for the store contract, and source guards on the two
 * files whose wiring is the fix.
 */
class ComposerDraftIsolationTest {

    private fun attachment(name: String = "untitled.json") =
        DraftAttachment(mime = "application/json", filename = name, dataUrl = "data:application/json;base64,e30=")

    @Before
    fun reset() {
        ComposerDrafts.reset()
    }

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?.replace("\r\n", "\n")
            ?: error("missing $relative")

    // --------------------------------------------------- the store contract

    @Test
    fun aFreshSessionReadsNeitherHalfWhileTheTypingSessionKeepsBoth() {
        ComposerDrafts.set("ses_b", "DRAFT_V16 keepme")
        ComposerDrafts.setAttachments("ses_b", listOf(attachment()))

        // B is intact: what the user typed is still there, attachment included.
        assertEquals("DRAFT_V16 keepme", ComposerDrafts.text("ses_b"))
        assertEquals(listOf(attachment()), ComposerDrafts.attachments("ses_b"))

        // The newly created C inherits nothing, and both halves answer the same
        // way — so the composer's mount-adopt seats neither. (The v16 leak showed
        // the text and not the attachment: one half came from a stale editor slot,
        // the other from the draft store. This is the rule that keeps them equal.)
        assertEquals("", ComposerDrafts.text("ses_c"))
        assertTrue(ComposerDrafts.attachments("ses_c").isEmpty())
        assertNull(ComposerDrafts.messageRef("ses_c"))
        assertEquals("", ComposerDrafts.text(ComposerDrafts.key(null)))
        assertTrue(ComposerDrafts.attachments(ComposerDrafts.key(null)).isEmpty())
    }

    @Test
    fun theExplicitHandoffStillCarriesBothHalves() {
        // Upstream `session/handoff.ts`: a flow that *hands the draft over* moves
        // the files and the message with the text. `move` is that path, and it is
        // the only cross-session carrier in the store.
        ComposerDrafts.set("ses_b", "hand me over")
        ComposerDrafts.setAttachments("ses_b", listOf(attachment()))

        ComposerDrafts.move("ses_b", "ses_c")

        assertEquals("hand me over", ComposerDrafts.text("ses_c"))
        assertEquals(listOf(attachment()), ComposerDrafts.attachments("ses_c"))
        assertEquals("", ComposerDrafts.text("ses_b"))
        assertTrue(ComposerDrafts.attachments("ses_b").isEmpty())
    }

    @Test
    fun aFailedSendHandsThePayloadBackToItsOwnSessionOnly() {
        // The other intentional path: a failed send returns text + attachments to
        // the session that sent them, and nowhere else.
        ComposerDrafts.restoreHandoff("ses_b", "try again", listOf(attachment()))

        assertTrue(ComposerDrafts.pendingRestore("ses_b"))
        assertEquals("try again", ComposerDrafts.takeRestore("ses_b", ""))
        assertEquals(listOf(attachment()), ComposerDrafts.attachments("ses_b"))
        assertEquals("", ComposerDrafts.text("ses_c"))
        assertTrue(ComposerDrafts.attachments("ses_c").isEmpty())
    }

    // --------------------------------------------------- the saveable slot

    @Test
    fun theSaveableSlotIsScopedByTheDraftKeyAndByTheSlotName() {
        val value = ComposerDrafts.saveableSlot("ses_b", ComposerDrafts.SLOT_EDITOR_VALUE)
        // Stable for one session (a rotation must still restore it)…
        assertEquals(value, ComposerDrafts.saveableSlot("ses_b", ComposerDrafts.SLOT_EDITOR_VALUE))
        // …and distinct for every other session and for every other slot.
        assertNotEquals(value, ComposerDrafts.saveableSlot("ses_c", ComposerDrafts.SLOT_EDITOR_VALUE))
        assertNotEquals(value, ComposerDrafts.saveableSlot("ses_b", ComposerDrafts.SLOT_SHELL_MODE))
        assertTrue("the slot names the session it belongs to", value.contains("ses_b"))
        // The blank-id fallback is a session of its own (the pre-session draft).
        assertEquals(
            ComposerDrafts.saveableSlot(ComposerDrafts.DEFAULT_KEY, ComposerDrafts.SLOT_EDITOR_VALUE),
            ComposerDrafts.saveableSlot(ComposerDrafts.key(null), ComposerDrafts.SLOT_EDITOR_VALUE),
        )
    }

    @Test
    fun aRestoredEditorSlotFromAnotherSessionCannotFeedAFreshSession() {
        // Session B's editor slot, saved as the screen leaves for the new-session
        // route (`SaveableStateHolder` saves every registered provider).
        val bSlots = SaveableStateRegistry(restoredValues = null) { true }
        bSlots.registerProvider(
            ComposerDrafts.saveableSlot("ses_b", ComposerDrafts.SLOT_EDITOR_VALUE),
        ) { "DRAFT_V16 keepme" }
        val saved = bSlots.performSave()

        // The same saveable map handed to the next session (the entry id is reused)
        // — exactly what C's composition sees.
        val cSlots = SaveableStateRegistry(restoredValues = saved) { true }
        assertNull(
            "C's editor slot must not be refilled from B's",
            cSlots.consumeRestored(
                ComposerDrafts.saveableSlot("ses_c", ComposerDrafts.SLOT_EDITOR_VALUE),
            ),
        )
        // B's own slot still restores for B (returning to B keeps its editor text
        // even before the draft store is consulted).
        assertEquals(
            "DRAFT_V16 keepme",
            cSlots.consumeRestored(
                ComposerDrafts.saveableSlot("ses_b", ComposerDrafts.SLOT_EDITOR_VALUE),
            ),
        )
    }

    @Test
    fun oneSlotPerCallSiteIsExactlyWhatLeakedTheText() {
        // The counter-example the fix is aimed at: with the slot named after the
        // call site alone, the restored value is handed to whichever session
        // composes next, and `rememberSaveable`'s `restored ?: init()` never runs
        // the draft read — session C's editor drew B's text.
        val bSlots = SaveableStateRegistry(restoredValues = null) { true }
        bSlots.registerProvider("composer-editor-value") { "DRAFT_V16 keepme" }

        val cSlots = SaveableStateRegistry(restoredValues = bSlots.performSave()) { true }
        assertEquals("DRAFT_V16 keepme", cSlots.consumeRestored("composer-editor-value"))
    }

    // ------------------------------------------------------ the wiring pins

    @Test
    fun theComposerNamesEverySaveableSlotAfterTheDraftKey() {
        val composer = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt")
            .replace(Regex("\\s+"), " ")
        val calls = Regex("""rememberSaveable\(""").findAll(composer).count()
        val scoped = Regex("""key = ComposerDrafts\.saveableSlot\(""").findAll(composer).count()
        assertTrue("the composer owns saveable editor states", calls >= 2)
        assertEquals(
            "every saveable editor state must be scoped to the draft key: a slot named after " +
                "its call site alone is refilled with the previous session's value when the nav " +
                "entry is reused: $composer",
            calls,
            scoped,
        )
        assertTrue(
            "the editor value's saveable slot must be scoped to the draft key, or a reused " +
                "nav entry restores the previous session's text into a new one",
            composer.contains(
                "key = ComposerDrafts.saveableSlot(draftKey, ComposerDrafts.SLOT_EDITOR_VALUE)",
            ),
        )
        assertTrue(
            "shell mode is per session too",
            composer.contains(
                "key = ComposerDrafts.saveableSlot(draftKey, ComposerDrafts.SLOT_SHELL_MODE)",
            ),
        )
    }

    @Test
    fun theOrdinaryCreatePathHandsNothingOver() {
        // The new-session route creates the session and hands off to
        // `Route.SESSION`; it must not touch the draft store — no `move`, no
        // `stash` — so "Create session" is isolation by construction, not by the
        // editor's restore behaviour.
        val newSession = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/NewSessionScreen.kt")
        assertTrue(
            "the ordinary create path must not carry a draft into the new session",
            !newSession.contains("ComposerDrafts"),
        )
    }

    @Test
    fun noProductionCallSiteMovesADraftAcrossSessions() {        // `move` is the upstream handoff shape (`handoff.ts`: a new session inherits
        // the files + message) and stays available for an *explicit* flow, but no
        // ordinary path may call it: a caller added here must come with the flow
        // that justifies it, which is why this test fails on one.
        val root = listOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull { it.isDirectory }
            ?: error("missing src/main/java")
        val callers = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("ComposerDrafts.move(") }
            .map { it.path }
            .toList()
        assertEquals(
            "only an explicit handoff flow may inherit a draft across sessions",
            emptyList<String>(),
            callers,
        )
    }

    // ------------------------------------------- v58: the leave-flush is key-bound

    @Test
    fun theLeaveFlushWritesOnlyWhileTheEditorIsStillBoundToItsOwnKey() {
        ComposerDrafts.set("ses_a", "say hello")
        ComposerDrafts.set("ses_b", "ping")

        // Still the same session (a top-tab leave, a home round-trip): the flush
        // saves what is in the editor.
        assertTrue(ComposerDrafts.flush("ses_a", "ses_a", "say hello"))
        assertEquals("say hello", ComposerDrafts.text("ses_a"))

        // The switch case (v58, emulator-5556): the outgoing effect is disposed
        // after the incoming session is bound. Its write is refused, so the text
        // sitting in the editor can never be filed under the other session.
        assertFalse(ComposerDrafts.flush("ses_b", "ses_c", "pingsay hello"))
        assertEquals("ping", ComposerDrafts.text("ses_b"))
        assertEquals("", ComposerDrafts.text("ses_c"))
    }

    @Test
    fun theLeaveFlushNeverResurrectsAnEmptyDraft() {
        // A send clears the draft; an empty flush would only put map junk back.
        assertFalse(ComposerDrafts.flush("ses_a", "ses_a", ""))
        assertEquals("", ComposerDrafts.text("ses_a"))
        assertTrue(ComposerDrafts.state.value.isEmpty())
    }

    @Test
    fun theComposerFlushesThroughTheKeyBoundGuard() {
        val composer = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt")
            .replace(Regex("\\s+"), " ")
        assertTrue(
            "the leave-flush must go through the key-bound guard: a session switch " +
                "disposes the outgoing effect after the incoming key is bound, so an " +
                "unconditional write files one session's text under another",
            composer.contains("ComposerDrafts.flush(draftKey, boundKey, value.text)"),
        )
        assertTrue(
            "the composer must know the key it is currently bound to",
            composer.contains("rememberUpdatedState(draftKey)"),
        )
    }
}
