package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.timeline.ErrorCardExpansion
import com.xiufeigo.opencodeshell.ui.session.timeline.errorCardBody
import com.xiufeigo.opencodeshell.ui.session.timeline.errorCardStateKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The session error card's expandable detail must always show something when
 * the card is expandable: the human line plus the raw `session.error` JSON
 * (the `type` / `status` the line drops). Regression pin for the v7 verify
 * finding where one card expanded to its JSON while the next showed nothing.
 *
 * The second half pins the **expand state identity** (device v11): both failure
 * cards came back collapsed after leaving and reopening the session, because
 * the state lived only inside the timeline's composition
 * (`rememberSaveable(message, detail)`) and the session destination — with its
 * `SaveableStateHolder` — is disposed on the way out.
 */
class SessionErrorCardTest {

    @Before
    fun resetExpansionMemory() {
        ErrorCardExpansion.clear()
    }

    @Test
    fun messageOnlyShowsMessage() {
        assertEquals("Missing API key.", errorCardBody("Missing API key.", null))
    }

    @Test
    fun messagePlusDetailJoinedWithBlankLine() {
        val body = errorCardBody(
            "Missing API key.",
            """{"type":"provider.auth","message":"Missing API key.","status":401}""",
        )
        assertEquals(
            "Missing API key.\n\n{\"type\":\"provider.auth\",\"message\":\"Missing API key.\",\"status\":401}",
            body,
        )
    }

    @Test
    fun duplicateDetailDeduped() {
        val payload = """{"type":"provider.auth","status":401}"""
        assertEquals(payload, errorCardBody(payload, payload))
    }

    @Test
    fun blankMessageFallsBackToDetail() {
        val detail = """{"type":"provider.auth","status":403}"""
        assertEquals(detail, errorCardBody("   ", detail))
    }

    @Test
    fun bothBlankIsNotExpandable() {
        assertEquals("", errorCardBody("  ", "  "))
        assertEquals("", errorCardBody("", null))
    }

    @Test
    fun halvesAreTrimmed() {
        assertEquals("boom\n\n{}", errorCardBody("  boom\n", "\n{}  "))
    }

    // ------------------------------------------------------- expansion identity

    @Test
    fun aRowKeyIsTheIdentityWhenTheCallerHasOne() {
        // The timeline hands in `TimelineRows.Row.Error.key` (`error:<messageID>`),
        // which the store rebuilds identically from the same server message: the
        // row id is the anchor plus the failure's content fingerprint, so it does
        // not move with the anchor's clock (device v12 — the live envelope is
        // client-stamped, the rebuilt one carries the server's `time.created`).
        assertEquals(
            "error:msg_1:error:1a2b3c4d",
            errorCardStateKey("error:msg_1:error:1a2b3c4d", "boom", "{}"),
        )
        // Two failures, same text: different rows, so independent toggles.
        assertNotEquals(
            errorCardStateKey("error:a", "boom", "{}"),
            errorCardStateKey("error:b", "boom", "{}"),
        )
        // The key wins over the content, so a row that gains detail keeps its state.
        assertEquals(
            errorCardStateKey("error:a", "boom", "{}"),
            errorCardStateKey("error:a", "boom", """{"type":"x"}"""),
        )
    }

    @Test
    fun anUnkeyedCardFallsBackToItsContent() {
        // The trailing status mirror renders `ChatState.error` itself and has no
        // row key: the same failure text must resolve to the same identity (so a
        // re-entry keeps the state) and a new text to a different one (so a fresh
        // failure starts collapsed, as it did before).
        assertEquals(errorCardStateKey(null, "boom", "{}"), errorCardStateKey(null, "boom", "{}"))
        assertNotEquals(errorCardStateKey(null, "boom", "{}"), errorCardStateKey(null, "boom2", "{}"))
        assertNotEquals(errorCardStateKey(null, "boom", "{}"), errorCardStateKey(null, "boom", null))
        // Length-prefixed so a message/detail split cannot collide.
        assertNotEquals(errorCardStateKey(null, "ab", "c"), errorCardStateKey(null, "a", "bc"))
    }

    // --------------------------------------------------------- the memory

    @Test
    fun expansionIsRememberedAndDroppedOnCollapse() {
        val key = errorCardStateKey("error:a", "boom", null)
        assertFalse("a failure the user never opened starts collapsed", ErrorCardExpansion.isExpanded(key))
        ErrorCardExpansion.setExpanded(key, true)
        assertTrue(ErrorCardExpansion.isExpanded(key))
        ErrorCardExpansion.setExpanded(key, false)
        assertFalse(ErrorCardExpansion.isExpanded(key))
    }

    @Test
    fun onlyExpandedCardsAreHeld() {
        repeat(ErrorCardExpansion.MAX_ENTRIES + 4) { index ->
            ErrorCardExpansion.setExpanded(errorCardStateKey("error:$index", "boom", null), true)
        }
        // The oldest entries fall out, so a long-lived process cannot accumulate
        // one entry per failure it has ever seen…
        assertFalse(ErrorCardExpansion.isExpanded(errorCardStateKey("error:0", "boom", null)))
        assertFalse(ErrorCardExpansion.isExpanded(errorCardStateKey("error:3", "boom", null)))
        // …while the newest are still there.
        val newest = errorCardStateKey("error:${ErrorCardExpansion.MAX_ENTRIES + 3}", "boom", null)
        assertTrue(ErrorCardExpansion.isExpanded(newest))
        // Re-expanding an old card moves it to the recent end instead of
        // duplicating it.
        ErrorCardExpansion.setExpanded(newest, true)
        assertTrue(ErrorCardExpansion.isExpanded(newest))
    }
}

