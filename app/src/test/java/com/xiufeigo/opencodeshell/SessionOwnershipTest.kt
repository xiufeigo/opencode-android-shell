package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.notify.SessionOwnership
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v49 tC: the shared-backend ownership gate. Four emulators ran four shells
 * against one server, and every stream carries every device's `session.idle`,
 * so the raise has to ask "is this session mine?" before anything is shown.
 */
class SessionOwnershipTest {

    @Test
    fun aSessionThisDeviceNeverOpenedIsNotOwned() {
        val ownership = SessionOwnership()
        assertFalse(ownership.owns("ses_other_device"))
    }

    @Test
    fun learningASessionMakesItOwned() {
        val ownership = SessionOwnership()
        assertTrue(ownership.learn("ses_mine"))
        assertTrue(ownership.owns("ses_mine"))
        assertFalse(ownership.owns("ses_theirs"))
    }

    @Test
    fun learningTheSameSessionTwiceChangesNothing() {
        val ownership = SessionOwnership()
        assertTrue(ownership.learn("ses_mine"))
        assertFalse(ownership.learn("ses_mine"))
        assertEquals(setOf("ses_mine"), ownership.snapshot())
    }

    @Test
    fun blankAndMissingIdsAreNotLearned() {
        val ownership = SessionOwnership()
        assertFalse(ownership.learn(null))
        assertFalse(ownership.learn(""))
        assertFalse(ownership.learn("   "))
        assertEquals(emptySet<String>(), ownership.snapshot())
    }

    @Test
    fun anEntryWithoutASessionIdIsKept() {
        // A frame whose payload carried no session cannot be attributed to
        // anybody; dropping it would lose the user's own error/ask entries.
        val ownership = SessionOwnership()
        assertTrue(ownership.owns(null))
        assertTrue(ownership.owns(""))
    }

    @Test
    fun theSetIsBoundedAndDropsTheOldestFirst() {
        val ownership = SessionOwnership(maxIds = 3)
        listOf("a", "b", "c", "d").forEach { ownership.learn(it) }
        assertEquals(setOf("b", "c", "d"), ownership.snapshot())
        assertFalse(ownership.owns("a"))
    }

    @Test
    fun theDefaultCapIsGenerous() {
        assertEquals(256, SessionOwnership.MAX_IDS)
    }

    @Test
    fun theRaiseIsGatedAndTheGateIsFedWhereSessionsArrive() {
        val source = { relative: String ->
            val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
            file.readText()
        }
        val viewModel = source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")
        // The gate sits in the raise itself, so the feed, the banner and the
        // system notification all agree on what belongs to this device.
        assertTrue(viewModel.contains("if (!sessionOwnership.owns(sessionID))"))
        assertTrue(viewModel.contains("private val sessionOwnership = SessionOwnership()"))
        // Fed by the one place a session becomes this device's: the route it
        // observed (which also covers a session this app created and promoted).
        assertTrue(viewModel.contains("sessionOwnership.learn(id)"))
    }
}
