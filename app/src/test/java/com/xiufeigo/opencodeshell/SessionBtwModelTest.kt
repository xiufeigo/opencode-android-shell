package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.btw.SessionBtwModel
import com.xiufeigo.opencodeshell.v2.GenerateFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `/btw` side-question state machine — upstream
 * `packages/app/src/session/btw/model.ts` (v2 head `8ce629b`) `createSessionBtw`:
 * prompt composition, per-sessionKey slots, and the request-sequence guard that
 * keeps a stale reply from overwriting the visible state. The failure *reason*
 * ([SessionBtwModel.Reason]) is the shell's own addition (device v14) and is
 * pinned here too, since it is what the panel's error body branches on.
 */
class SessionBtwModelTest {

    @Test
    fun promptIsTheUpstreamInstructionTripleThenTheQuestion() {
        // `model.ts:10-14` — three sentences joined with single spaces.
        assertEquals(
            "The user is asking a quick side question about the conversation so far. " +
                "Answer directly and concisely in markdown from what you already know. " +
                "Do not call any tools and do not take any actions.",
            SessionBtwModel.INSTRUCTIONS,
        )
        // `model.ts:70` `[instructions, question].join("\n\n")`.
        assertEquals(
            SessionBtwModel.INSTRUCTIONS + "\n\nwhy is the build red?",
            SessionBtwModel.prompt("why is the build red?"),
        )
    }

    @Test
    fun normalizeTrimsAndDropsBlankInput() {
        assertEquals("q", SessionBtwModel.normalize("  q \n"))
        assertNull(SessionBtwModel.normalize(null))
        assertNull(SessionBtwModel.normalize(""))
        assertNull(SessionBtwModel.normalize(" \n\t "))
    }

    @Test
    fun blankAskReturnsNullWithoutTouchingState() {
        val model = SessionBtwModel()
        assertNull(model.ask(null))
        assertNull(model.ask("   "))
        assertEquals(SessionBtwModel.State(), model.state())
    }

    @Test
    fun askPendsTheQuestionAndClearsThePreviousAnswer() {
        val model = SessionBtwModel()
        val first = model.ask("first")!!
        model.succeed(first, "old answer")
        val second = model.ask("  second  ")!!
        assertEquals(
            SessionBtwModel.State(question = "second", pending = true),
            model.state(),
        )
        assertNotEquals(first, second)
    }

    @Test
    fun succeedTrimsTheAnswerAndClearsPending() {
        val model = SessionBtwModel()
        val ask = model.ask("q")!!
        model.succeed(ask, "  **done**\n")
        assertEquals(
            SessionBtwModel.State(question = "q", answer = "**done**"),
            model.state(),
        )
    }

    @Test
    fun failMarksErrorAndKeepsTheQuestionForRetry() {
        val model = SessionBtwModel()
        val ask = model.ask("q")!!
        model.fail(ask)
        assertEquals(
            SessionBtwModel.State(
                question = "q",
                pending = false,
                error = true,
                // `fail` defaults to the upstream `.catch` case: the panel then
                // shows the generic copy + Retry.
                reason = SessionBtwModel.Reason.FAILED,
            ),
            model.state(),
        )
    }

    @Test
    fun theFailureReasonNamesTheLocalNoModelRefusal() {
        // `Reason.of` — device v14: a session with no model is refused locally
        // before the request leaves the device (`ChatViewModel.generateFromContext`
        // → `(null, NO_MODEL)`), so the panel can say what to do instead of
        // showing the generic failure.
        assertEquals(
            SessionBtwModel.Reason.NO_MODEL,
            SessionBtwModel.Reason.of(GenerateFailure.NO_MODEL),
        )
        assertEquals(
            SessionBtwModel.Reason.FAILED,
            SessionBtwModel.Reason.of(GenerateFailure.REQUEST_FAILED),
        )
        assertEquals(SessionBtwModel.Reason.FAILED, SessionBtwModel.Reason.of(null))

        val model = SessionBtwModel()
        val ask = model.ask("q")!!
        model.fail(ask, SessionBtwModel.Reason.NO_MODEL)
        assertEquals(SessionBtwModel.Reason.NO_MODEL, model.state().reason)
        assertTrue(model.state().error)

        // Retry re-asks with a fresh slot, which clears the reason with the
        // error flag; the answer path keeps it clear.
        val retry = model.retry()!!
        assertNull(model.state().reason)
        assertFalse(model.state().error)
        model.succeed(retry, "answer")
        assertNull(model.state().reason)
        assertFalse(model.state().error)
    }

    @Test
    fun aSupersededAskCannotOverwriteTheVisibleState() {
        val model = SessionBtwModel()
        val stale = model.ask("first")!!
        val fresh = model.ask("second")!!
        model.succeed(stale, "late answer for the first question")
        assertEquals("second", model.state().question)
        assertTrue(model.state().pending)
        model.succeed(fresh, "real answer")
        assertEquals(
            SessionBtwModel.State(question = "second", answer = "real answer"),
            model.state(),
        )
    }

    @Test
    fun retryReasksTheSameQuestionAsAFreshRequest() {
        val model = SessionBtwModel()
        val first = model.ask("q")!!
        model.fail(first)
        val second = model.retry()!!
        assertEquals(
            SessionBtwModel.State(question = "q", pending = true),
            model.state(),
        )
        assertNotEquals(first, second)
        // The failed request stays failed-slot history; only the fresh one writes.
        model.succeed(first, "too late")
        assertTrue(model.state().pending)
        model.succeed(second, "retry worked")
        assertEquals("retry worked", model.state().answer)
    }

    @Test
    fun abandonTurnsAPendingAskIntoErrorAndDropsItsLateReply() {
        val model = SessionBtwModel()
        val ask = model.ask("q")!!
        model.abandon()
        assertEquals(
            SessionBtwModel.State(question = "q", pending = false, error = true),
            model.state(),
        )
        model.succeed(ask, "arrived after the abort")
        assertEquals("", model.state().answer)
        assertTrue(model.state().error)
    }

    @Test
    fun abandonLeavesASettledSlotAlone() {
        val model = SessionBtwModel()
        val ask = model.ask("q")!!
        model.succeed(ask, "answer")
        model.abandon()
        assertEquals("answer", model.state().answer)
        assertEquals(false, model.state().error)
    }

    @Test
    fun statesArePerSessionKeyAndSurviveAVisitElsewhere() {
        val model = SessionBtwModel()
        model.activate("session-a")
        val a = model.ask("question for A")!!
        model.succeed(a, "answer for A")
        model.activate("session-b")
        assertEquals(SessionBtwModel.State(), model.state())
        val b = model.ask("question for B")!!
        model.succeed(b, "answer for B")
        // Coming back restores A's slot (upstream's `Record<sessionKey, state>`).
        model.activate("session-a")
        assertEquals(
            SessionBtwModel.State(question = "question for A", answer = "answer for A"),
            model.state(),
        )
    }

    @Test
    fun leavingASessionWithAnInFlightAskRunsTheUpstreamCleanup() {
        val model = SessionBtwModel()
        model.activate("session-a")
        val a = model.ask("question for A")!!
        model.activate("session-b")
        // `model.ts:32-41` `onCleanup`: the aborted ask's slot becomes
        // `{pending: false, error: true}` — visible again on return to A.
        model.activate("session-a")
        assertEquals(
            SessionBtwModel.State(question = "question for A", error = true),
            model.state(),
        )
        model.succeed(a, "late answer for A")
        assertEquals("", model.state().answer)
        assertTrue(model.state().error)
    }

    @Test
    fun activateOnTheSameKeyIsANoopAndKeepsThePendingAsk() {
        val model = SessionBtwModel()
        model.activate("session-a")
        model.ask("q")
        model.activate("session-a")
        assertEquals(
            SessionBtwModel.State(question = "q", pending = true),
            model.state(),
        )
    }
}
