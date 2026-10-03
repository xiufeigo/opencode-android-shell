package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.palette.CATEGORY_SESSION
import com.xiufeigo.opencodeshell.ui.palette.PaletteSources
import com.xiufeigo.opencodeshell.ui.palette.SessionCommandIntents
import com.xiufeigo.opencodeshell.ui.palette.SessionCommands
import com.xiufeigo.opencodeshell.ui.palette.commandEntries
import com.xiufeigo.opencodeshell.ui.palette.visibleEntries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `session.aside` — upstream's `/btw` side question, in the **command palette**.
 *
 * Upstream registers it from the btw plugin with `palette: true` and
 * `slash: {name: "btw", arguments: true}` (`_upstream` …/prompt/btw.tsx), i.e.
 * both surfaces list it; this shell had only the composer's `/` row and the
 * header's `(?)` entry. The palette row rides [SessionCommands.sessionCommands]
 * like every other session row and runs the **same** btw host the composer
 * routes to (`ui/session/btw` `SessionBtwHost.ask`/`openPanel` → `POST
 * /api/session/{id}/generate`), so a side question never appends a turn.
 *
 * The row's action is a host callback, so these pins are what "the palette can
 * find and execute btw" means in unit-test form: registered with upstream's
 * copy and category once the host provides it, absent (not inert) when it does
 * not, and selecting it asks the **bare** form — upstream's palette call has no
 * input and falls back to its question dialog, which here is the btw panel's own
 * field.
 */
class SessionAsidePaletteTest {

    private fun rows(onAskAside: ((String?) -> Unit)? = null) =
        SessionCommands.sessionCommands(
            SessionCommands.SessionCommandActions(onAskAside = onAskAside),
        )

    // ------------------------------------------------------------- the row

    @Test
    fun theRowIsRegisteredWithUpstreamsCopyAndCategory() {
        val row = rows { }.first { it.id == SessionCommands.ASIDE_COMMAND_ID }
        assertEquals("session.aside", row.id)
        assertEquals(CATEGORY_SESSION, row.category)
        assertEquals(R.string.oc_btw_ask, row.titleResId)
        assertEquals(R.string.oc_btw_palette_description, row.descriptionResId)
        assertNull("no keybind upstream — the trigger is the slash form", row.keybind)
        assertFalse(row.hidden)
    }

    @Test
    fun aSilentHostKeepsTheRowOutInsteadOfLeavingItInert() {
        // The file's rule for every host-supplied action (`appCommands` too):
        // a null action drops the row rather than registering a dead one.
        assertTrue(rows().none { it.id == SessionCommands.ASIDE_COMMAND_ID })
    }

    @Test
    fun selectingThePaletteRowAsksTheBareForm() {
        val asked = ArrayList<String?>()
        val row = rows { asked.add(it) }.first { it.id == SessionCommands.ASIDE_COMMAND_ID }

        row.onSelect()

        // `null` = no input: the host opens the side-question panel, whose own
        // field is upstream's `dialog.prompt` (`btw.tsx:64-66`). A non-null
        // question would have sent it straight to `session.generate`.
        assertEquals(listOf<String?>(null), asked)
    }

    @Test
    fun theRowIsFindableByItsUpstreamTrigger() {
        val entries = commandEntries(rows { }) { resId ->
            when (resId) {
                R.string.oc_btw_ask -> "Ask a side question"
                R.string.oc_btw_palette_description ->
                    "One-shot answer from the session's context without adding to the conversation (/btw)"
                else -> ""
            }
        }
        val sources = PaletteSources(commands = entries)

        // `matchesEntry` reads the localized title/description/category plus the
        // row's id words and English copy, and the description carries the
        // trigger — that is what makes "btw" land.
        assertEquals(
            listOf("command:${SessionCommands.ASIDE_COMMAND_ID}"),
            visibleEntries(sources, "btw").map { it.id },
        )
        assertEquals(
            listOf("command:${SessionCommands.ASIDE_COMMAND_ID}"),
            visibleEntries(sources, "side question").map { it.id },
        )
    }

    @Test
    fun theComposerKeepsItsOwnBtwTrigger() {
        // The composer's `/btw` is a built-in row (`ComposerSuggestions.
        // BTW_TRIGGER`) routed to the same host, and its alias rows are built
        // from `SESSION_SLASH_COMMAND_IDS` — a second alias for one trigger
        // would only duplicate the row. Deliberate deviation from upstream's
        // single table, pinned so nobody "fixes" it twice.
        assertFalse(SessionCommands.SLASH_COMMAND_IDS.containsKey("btw"))
        assertNull(SessionCommands.commandIdForSlash("btw"))
    }

    // ------------------------------------------------- the intent channel

    @Test
    fun askAsideNormalizesTheQuestionAndTheBareForm() {
        val intents = SessionCommandIntents()
        assertNull("nothing pending before the first tap", intents.asideRequest.value)

        intents.askAside("  what changed?  ")
        assertEquals("what changed?", intents.asideRequest.value?.question)

        intents.askAside("   ")
        assertNull("blank = the bare form, not an empty question", intents.asideRequest.value?.question)
    }

    @Test
    fun everyRequestIsDistinctAndConsumedByItsOwnId() {
        val intents = SessionCommandIntents()
        intents.askAside("one")
        val first = intents.asideRequest.value!!
        intents.askAside("two")
        val second = intents.asideRequest.value!!

        assertTrue("a second tap is never swallowed by the first", second.id > first.id)
        intents.consumeAside(first)
        assertEquals("a stale consumer cannot clear a newer request", second, intents.asideRequest.value)
        intents.consumeAside(second)
        assertNull(intents.asideRequest.value)
    }

    @Test
    fun theAsideChannelIsIndependentOfTheFileRequest() {
        val intents = SessionCommandIntents()
        intents.openFile("a/b.kt")
        intents.askAside(null)

        assertNotNull(intents.fileRequest.value)
        assertNotNull(intents.asideRequest.value)

        // The two channels share the sequence counter, not their state.
        intents.consume(intents.fileRequest.value!!)
        assertNull(intents.fileRequest.value)
        assertNotNull(intents.asideRequest.value)
    }
}
