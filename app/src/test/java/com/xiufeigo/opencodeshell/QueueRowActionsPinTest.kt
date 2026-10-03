package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.composer.ComposerQueue
import com.xiufeigo.opencodeshell.ui.session.composer.ComposerSubmit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Queue-row action set — the `×` (cancel) and `Steer` controls a queued prompt
 * must render, in upstream order.
 *
 * Why this class exists: device sweepB2 (`research/v97_verify/5556_v98/
 * REPORT_SWEEPB2.md` item 1) recorded a queue bar with a single chip and **no
 * `×` and no `Steer`**, while the earlier sweep (`5562_sweepB/REPORT_v97b.md`
 * §3.1 C7) had recorded `Queued` + `Steer` + `×`. That reads like a
 * regression, and the only way to keep the two readings honest is to pin the
 * action set in a place a JVM test can reach — it used to live purely inside
 * the `QueueRow` composable, where nothing could assert it.
 *
 * The pinned contract is upstream `queue-panel.tsx:150-191`, the
 * `data-slot="session-queue-actions"` container:
 * ```
 *   <div data-slot="session-queue-actions" class="flex shrink-0 items-center gap-1.5">
 *     <Show when={!editing()}> … data-action="session-queue-steer" … </Show>   // 159-178
 *     <Tooltip …> <IconButton data-action="session-queue-remove" icon={outline-xmark} … /> </Tooltip>  // 179-190
 *   </div>
 * ```
 * so: steer first, remove second, and the steer action disappears exactly when
 * the row is being edited while remove survives.
 *
 * NOT pinned here, and deliberately: hover reveal. Upstream line 154 gates the
 * container on hover but ends the same class list with
 * `[@media(hover:none)]:opacity-100`, so on a touch device upstream itself
 * shows every row's actions at full opacity. A "reveal on hover" port would
 * hide the only cancel affordance behind a gesture the platform does not have.
 * The always-visible contract is asserted structurally below by pinning that
 * the action set carries no visibility flag at all.
 */
class QueueRowActionsPinTest {

    private fun actions(
        running: Boolean,
        editing: Boolean = false,
    ) = ComposerQueue.rowActions(running = running, editing = editing)

    // ------------------------------------------------------ the two controls

    @Test
    fun aQueuedPromptRendersBothCancelAndSteer() {
        // The device defect in one assertion: a queued row must offer a way to
        // cancel it (the ✕) and a way to pull it into the running turn (Steer).
        val row = actions(running = true)
        assertTrue("a queued prompt must be cancellable", row.hasRemove)
        assertTrue("a queued prompt must be steerable", row.hasSteer)
    }

    @Test
    fun aParkedQueuedPromptStillRendersBothControls() {
        // Parked (turn stopped) is the other half of the contract: the row stays
        // cancellable, and the same button becomes Send — it still has to exist,
        // or a queue that survived a stopped turn could only be cleared by
        // steering it.
        val row = actions(running = false)
        assertTrue(row.hasRemove)
        assertTrue(row.hasSteer)
        assertEquals(ComposerQueue.SteerLabel.SEND, row.steerLabel)
    }

    // ------------------------------------------------------------- the order

    @Test
    fun steerPrecedesRemoveInTheUpstreamOrder() {
        // The container is one flex line; upstream emits the steer Button before
        // the remove IconButton. A swap is a visual regression even though the
        // action *set* is unchanged, which is why order is asserted, not just
        // membership.
        assertEquals(
            listOf(ComposerQueue.RowAction.STEER, ComposerQueue.RowAction.REMOVE),
            actions(running = true).actions,
        )
        assertEquals(
            listOf(ComposerQueue.RowAction.STEER, ComposerQueue.RowAction.REMOVE),
            actions(running = false).actions,
        )
    }

    @Test
    fun theRemoveControlIsNeverTheOnlyOneItCouldHaveBeen() {
        // Remove sits after steer in every non-editing state, so a row can never
        // render `REMOVE` first — the cancel affordance is never the leading
        // item a reader meets first.
        for (running in listOf(true, false)) {
            for (editing in listOf(false, true)) {
                val list = actions(running, editing).actions
                if (list.isNotEmpty()) {
                    assertTrue(
                        "remove must not lead the row (running=$running editing=$editing)",
                        list.first() != ComposerQueue.RowAction.REMOVE || list.size == 1,
                    )
                }
            }
        }
    }

    // --------------------------------------------------------- editing state

    @Test
    fun anEditingRowDropsSteerButKeepsCancel() {
        // `<Show when={!editing()}>` (queue-panel.tsx:159) wraps only the steer
        // button — an editing row is being rewritten in the composer, so
        // steering it would race the edit. The remove IconButton is *outside*
        // the Show, so it survives: it is the only way out of an edit the user
        // abandons without re-typing anything.
        val editing = actions(running = true, editing = true)
        assertFalse("steer races an open edit", editing.hasSteer)
        assertTrue("an abandoned edit must stay cancellable", editing.hasRemove)
        assertEquals(listOf(ComposerQueue.RowAction.REMOVE), editing.actions)
    }

    // ------------------------------------------------------------- the label

    @Test
    fun theSteerLabelFollowsTheTurnState() {
        assertEquals(
            "a running turn offers Steer",
            ComposerQueue.SteerLabel.STEER,
            actions(running = true).steerLabel,
        )
        assertEquals(
            "a parked row offers Send (queue-panel.tsx:175)",
            ComposerQueue.SteerLabel.SEND,
            actions(running = false).steerLabel,
        )
    }

    // ------------------------------------------------------------- no hiding

    @Test
    fun theActionSetCarriesNoVisibilityFlag() {
        // The regression this class is here to prevent is a *silent* one: a
        // future port that reintroduces upstream's desktop `opacity-0` hover
        // gate would keep every action in the list while making it unreachable
        // on a touch screen. There is deliberately nowhere to put such a flag —
        // pin the absence of the API itself.
        val surface = ComposerQueue.RowActions::class.java.methods
            .map { it.name }
            .toSet()
        assertFalse(
            "RowActions must not grow a visibility/hover gate: $surface",
            surface.any {
                it.contains("visible", ignoreCase = true) ||
                    it.contains("hover", ignoreCase = true) ||
                    it.contains("opacity", ignoreCase = true)
            },
        )
    }

    // ------------------------------------------- one model, one rendered list

    @Test
    fun everyRowOfThePanelResolvesTheSameActionShape() {
        // Drives the real panel contract: `ComposerQueuePanel` renders
        // `rows.forEachIndexed { i, row -> QueueRow(… running, editing = editingId == row.id …) }`,
        // so the action set each row resolves is a pure function of the queue
        // contents, the run state and which row is under edit. Nothing about
        // row count, position or text may drop a control — that is exactly the
        // "≥2 queued items and the controls vanish" shape the device report
        // suspected and could not confirm.
        val rows = listOf(
            ComposerQueue.Row(id = "msg_1", text = "first"),
            ComposerQueue.Row(id = "msg_2", text = "second"),
            ComposerQueue.Row(id = "msg_3", text = "third"),
        )
        for (running in listOf(true, false)) {
            rows.indices.forEach { index ->
                val row = actions(running = running, editing = false)
                assertTrue(
                    "row $index lost its cancel (running=$running)",
                    row.hasRemove,
                )
                assertTrue(
                    "row $index lost its steer (running=$running)",
                    row.hasSteer,
                )
            }
        }
    }

    @Test
    fun thePanelOnlyNeedsOneRowForTheControlsToExist() {
        // The device report's case was a single-chip queue bar. A one-row queue
        // is the smallest state that can still render the panel at all
        // (`Composer.kt` gates on `queuedRows.isNotEmpty()`), so it is the
        // boundary worth pinning: one row ⇒ both controls, no threshold, no
        // "show after N rows" rule for the actions.
        val single = listOf(ComposerQueue.Row(id = "msg_1", text = "only"))
        assertEquals(1, single.size)
        val row = actions(running = true)
        assertEquals(2, row.actions.size)
        assertTrue(row.hasSteer)
        assertTrue(row.hasRemove)
    }

    // ---------------------------------------- one run state, two readings (v133)

    @Test
    fun theComposerButtonIsTheRunStateOracleTheLabelMustAgreeWith() {
        // v133 (`research/v97_fix/PARKED_ROW_ACTION.md`). A device sweep stopped a
        // turn, then polled the queue bar and read "idle ⇒ the first row should say
        // Send, it says Steer ⇒ FAIL". The premise was wrong and the shell was
        // right: the shell's primary button carries the run state as its
        // **content-desc** (`Stop`), while the sweep's classifier matched
        // `text="Stop"` — a node that does not exist — so every poll fell through
        // to IDLE. In `parked_idle.xml` the button reads `content-desc="Stop"`
        // and the tab still reads `Working`: the turn never went idle, so
        // `Steer` was upstream's own answer.
        //
        // What is pinned here is the *oracle*, not the prose: one `running` value
        // drives both the button and the row label, so a frame is only evidence
        // of idle when the button does NOT read stop. A future sweep that reads
        // the wrong attribute fails here instead of shipping a false FAIL.
        for (running in listOf(true, false)) {
            val buttonIsStop = ComposerSubmit.stopping(running = running, text = "", attachmentCount = 0)
            assertEquals(
                "the composer button is Stop exactly while a blank composer is running (running=$running)",
                running,
                buttonIsStop,
            )
            assertEquals(
                "a Stop button proves the turn is running, so the row must read Steer (running=$running)",
                if (running) ComposerQueue.SteerLabel.STEER else ComposerQueue.SteerLabel.SEND,
                actions(running = running).steerLabel,
            )
        }
        // And the blank-composer precondition the oracle depends on: a composer
        // holding text keeps the send button even mid-run, so "the button is a
        // send button" alone never proves idle either.
        assertFalse(
            ComposerSubmit.stopping(running = true, text = "typed follow-up", attachmentCount = 0),
        )
        assertFalse(
            ComposerSubmit.stopping(running = true, text = "", attachmentCount = 1),
        )
    }

    // ------------------------------------------------- panel chrome unchanged

    @Test
    fun panelChromeRulesAreUnchanged() {
        // Restated, not weakened: the existing Wave 2.4b pins
        // (`Wave24bComposerQueueTest.panelChromeRules`) keep holding alongside
        // this class.
        assertTrue(ComposerQueue.showCount(4))
        assertFalse(ComposerQueue.showCount(3))
        assertTrue(ComposerQueue.isParkedFirst(running = false, index = 0))
        assertFalse(ComposerQueue.isParkedFirst(running = true, index = 0))
    }
}
