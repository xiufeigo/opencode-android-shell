package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.applyTabRename
import com.xiufeigo.opencodeshell.ui.nav.SessionTabRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * A Home rename must land on the open tab that owns the session.
 *
 * `sessionTitleSync` only fires while a session route is up, so a rename made
 * on Home left every open tab on the old name (5560: Home renamed, the tab
 * still read the old title). `HomeRowActions.rename` mirrors through
 * [applyTabRename] instead of waiting for the next catalogue read.
 */
class TabRenameMirrorTest {

    private fun tab(id: String, title: String?) = SessionTabRef(id = id, title = title)

    @Test
    fun renameWritesTheNewTitleOntoTheOwningTab() {
        val next = applyTabRename(
            tabs = listOf(tab("a", "Untitled"), tab("b", "Other")),
            sessionId = "a",
            title = "E2ECHK5560-Stream-Stop",
        )
        assertEquals(listOf(tab("a", "E2ECHK5560-Stream-Stop"), tab("b", "Other")), next)
    }

    @Test
    fun renameOfAnUnopenedSessionChangesNothing() {
        val tabs = listOf(tab("a", "Untitled"))
        assertSame(tabs, applyTabRename(tabs, "zzz", "New name"))
    }

    @Test
    fun anUnchangedTitleSkipsTheWrite() {
        val tabs = listOf(tab("a", "Same"))
        assertSame(tabs, applyTabRename(tabs, "a", "Same"))
    }
}
