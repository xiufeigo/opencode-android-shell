package com.xiufeigo.opencodeshell.ui.session.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v40: 「移动到…」 started at the **project** directory for a session living
 * in its own (`…/frontend`) — `research/v40_verify/tA/52_moveto.png` shows the
 * picker on the shell repo while the session's
 * `GET /api/session/{id}` reports `location.directory` for `frontend`.
 *
 * The start is therefore the session's own server-confirmed directory, and a
 * blank must not be mistaken for "no directory": `observeSession` republishes
 * it from a route that never carried one, and a null start lets the picker's
 * probes answer with the project location.
 */
class MoveSurfaceStartTest {

    @Test
    fun theSessionDirectoryIsTheStart() {
        assertEquals(
            "/home/xiufeigo/shell/frontend",
            movePickerStart("/home/xiufeigo/shell/frontend", null),
        )
    }

    @Test
    fun theFreshAnswerOutranksTheRememberedOne() {
        assertEquals(
            "/home/xiufeigo/shell/frontend",
            movePickerStart("/home/xiufeigo/shell/frontend", "/home/xiufeigo/shell"),
        )
    }

    @Test
    fun aBlankRepublishDoesNotDropTheSessionDirectory() {
        // The observed v40 shape: a re-`observeSession` from a directory-less
        // route nulls the flow, and the picker used to start on the project.
        assertEquals(
            "/home/xiufeigo/shell/frontend",
            movePickerStart(null, "/home/xiufeigo/shell/frontend"),
        )
        assertEquals(
            "/home/xiufeigo/shell/frontend",
            movePickerStart("   ", "/home/xiufeigo/shell/frontend"),
        )
    }

    @Test
    fun noAnswerAtAllLeavesTheProbesToDecide() {
        assertNull(movePickerStart(null, null))
        assertNull(movePickerStart("  ", ""))
    }
}
