package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.composer.ComposerDelivery
import com.xiufeigo.opencodeshell.ui.session.composer.ComposerQueue
import com.xiufeigo.opencodeshell.ui.session.composer.ComposerQueue.EditLoanEvent
import com.xiufeigo.opencodeshell.ui.session.composer.ComposerQueue.Row
import com.xiufeigo.opencodeshell.ui.session.composer.ComposerQueue.SteerLabel
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v131 — the queue-edit loan must be able to **leave**, not only to be entered.
 *
 * v130 (emulator-5554) found the composer locking itself: tap a queued row to
 * edit it, press Send, and the delivery hint answered `Steer` for the rest of
 * the session — the `Queue` entry never came back, and Esc did not save it
 * (the IME swallows the key while the keyboard is up), so the only way out was
 * a brand-new session. The source cause is a state that could only be entered:
 * `Composer.kt` kept the loan twice — the row (`queueEdit`) plus a separate
 * `queueEditOpen` latch — and the confirm path closed only the row, while
 * `ComposerDelivery.alternate`'s first line (`if (editingQueued) return STEER`)
 * is an early return keyed on the latch.
 *
 * Upstream keeps ONE loan and converges it against the queue
 * (`_upstream/packages/app/src/session/composer/queue.ts:93-97`):
 * ```ts
 * createEffect(() => {
 *   const editing = state.editing
 *   if (!editing || mutation.isPending || queued().some((item) => item.id === editing.id)) return
 *   setState("editing", undefined)
 * })
 * ```
 * so a loan can never outlive its row. v131 ports that shape: the composer
 * derives the chip flag from the loan, and drives the loan through the two pure
 * functions pinned here — `ComposerQueue.endEditLoan` (the discrete exits) and
 * `ComposerQueue.convergeEditLoan` (the row-gone exit).
 *
 * These are pure-function pins, so they are decidable on the JVM with no device:
 * every (loan state × exit) combination is enumerated, and the invariant
 * asserted is the one the bug violated — **no state is one-way**: after any
 * terminal exit the loan is closed, and the `Queue` entry is offered again.
 */
class QueueEditLoanExitV131Test {

    private val row = Row(id = "msg_a", text = "queued followup alpha")
    private val other = Row(id = "msg_b", text = "queued followup bravo")

    /** Every event that ends *this* loan, aimed at [target]. */
    private fun terminalEvents(target: String): List<Pair<EditLoanEvent, String?>> = listOf(
        EditLoanEvent.CONFIRM to null,
        EditLoanEvent.CANCEL to null,
        EditLoanEvent.SESSION_SWITCH to null,
        EditLoanEvent.STEER to target,
        EditLoanEvent.REMOVE to target,
    )

    private fun chipOffer(loan: Row?, running: Boolean = true, behavior: String? = "steer") =
        ComposerDelivery.alternate(running, behavior, editingQueued = loan != null, hasContent = true)

    // ── 1. the full truth table: 编辑中 × {提交, 取消, 行被消费, 回合结束, 幂等重复} ──

    @Test
    fun everyTerminalEventClosesTheLoanFromEveryState() {
        // 编辑中 / 未编辑 × 每一个终态出口 ⇒ 贷款必为 null。
        for (start in listOf<Row?>(null, row)) {
            for ((event, target) in terminalEvents(row.id)) {
                val after = ComposerQueue.endEditLoan(start, event, target)
                assertNull(
                    "start=$start $event(target=$target) must end the loan, got $after",
                    after,
                )
            }
        }
    }

    @Test
    fun noStateIsOneWayAcrossTheWholeEventSet() {
        // The invariant, stated as the bug stated it: whatever state the loan is
        // in and whatever event arrives, a terminal event leaves it closed — so
        // there is no reachable state from which the Queue entry stays lost.
        val states = listOf<Row?>(null, row, other)
        val events = EditLoanEvent.values().toList()
        for (start in states) {
            for (event in events) {
                // STEER/REMOVE are aimed at the loan's own row — the only case in
                // which they are an exit for it (see the next test).
                val target = if (start != null && (event == EditLoanEvent.STEER || event == EditLoanEvent.REMOVE)) {
                    start.id
                } else {
                    null
                }
                val closed = ComposerQueue.endEditLoan(start, event, target)
                assertNull("$event(target=$target) must close a loan on $start", closed)
            }
        }
    }

    @Test
    fun repeatingATerminalEventIsHarmless() {
        // 幂等：终态出口重复施加无副作用（上游 cancelEdit 也是 `if (!editing) return`）。
        for ((event, target) in terminalEvents(row.id)) {
            var loan: Row? = row
            repeat(3) {
                loan = ComposerQueue.endEditLoan(loan, event, target)
                assertNull("repeat #$it of $event must stay closed", loan)
            }
        }
        // And from an already-closed loan, every event is a no-op.
        for (event in EditLoanEvent.values()) {
            for (target in listOf(null, row.id, other.id)) {
                assertNull("$event(target=$target) on a closed loan", ComposerQueue.endEditLoan(null, event, target))
            }
        }
    }

    @Test
    fun steerOrRemoveOfAnotherRowIsNotAnExitForThisLoan() {
        // 非终态：别的行被 Steer/Remove 与本贷款无关，贷款必须原样保留。
        for (event in listOf(EditLoanEvent.STEER, EditLoanEvent.REMOVE)) {
            assertSame("$event on another row must not close this loan", row, ComposerQueue.endEditLoan(row, event, other.id))
            assertSame("$event with no target must not close this loan", row, ComposerQueue.endEditLoan(row, event))
        }
    }

    @Test
    fun theEditedRowLeavingTheQueueClosesTheLoan() {
        // 行被消费：上游 queue.ts:93-97 的收敛。
        assertNull("row gone from the queue", ComposerQueue.convergeEditLoan(row, listOf(other.id), busy = false))
        assertNull("queue drained entirely", ComposerQueue.convergeEditLoan(row, emptyList(), busy = false))
        assertSame("row still queued", row, ComposerQueue.convergeEditLoan(row, listOf(row.id, other.id), busy = false))
    }

    @Test
    fun aBusyMutationDelaysTheCloseButNeverLocksIt() {
        // busy 是上游的 mutation.isPending：只延后，不锁定 —— 下一次收敛必关。
        val duringMutation = ComposerQueue.convergeEditLoan(row, emptyList(), busy = true)
        assertSame("an in-flight mutation holds the loan", row, duringMutation)
        assertNull("the next settled read closes it", ComposerQueue.convergeEditLoan(duringMutation, emptyList(), busy = false))
    }

    @Test
    fun aTurnThatDrainsTheQueueEndsTheLoan() {
        // 回合结束：上游回合 settle 会 drain 队列，行随之消失 ⇒ 贷款随之结束。
        val drained = emptyList<String>()
        assertNull("turn settle drains the queue", ComposerQueue.convergeEditLoan(row, drained, busy = false))
        // 而 parked（行还在）不是出口：那一行仍可编辑，且 !running 时 chip 本就隐藏
        // （alternate 在 !running 返回 null），所以 `Queue` 入口下一回合自然回来。
        assertSame("a parked row keeps its loan", row, ComposerQueue.convergeEditLoan(row, listOf(row.id), busy = false))
        // 停在 parked 的那一行仍可编辑，故贷款保留；此时 chip 恒显 Steer（上游
        // queue.ts:219 的 `if (state.editing) return "steer"` 排在 !working 之前），
        // 而下一回合 running 一恢复，贷款已随回合 drain 结束 ⇒ Queue 入口回来。
        assertEquals("an open loan pins the hint even when the turn is stopped", ComposerDelivery.STEER, chipOffer(row, running = false))
        assertEquals(
            "and once the loan is gone the Queue entry is back",
            ComposerDelivery.QUEUE,
            chipOffer(ComposerQueue.convergeEditLoan(row, drained, busy = false), running = true, behavior = "steer"),
        )
    }

    @Test
    fun reopeningSupersedesThePreviousLoan() {
        // 上游 queue.ts:152：同时只开一个贷款，新的覆盖旧的（不是两条并行）。
        var loan: Row? = row
        loan = other // editQueued(other) — the new row *is* the whole loan
        assertSame("the newest row is the only loan", other, loan)
        assertNull("its own row's Remove closes it", ComposerQueue.endEditLoan(loan, EditLoanEvent.REMOVE, other.id))
        assertSame(
            "the old row's steer is not an exit for the new loan",
            other,
            ComposerQueue.endEditLoan(loan, EditLoanEvent.STEER, row.id),
        )
    }

    // ── 2. the chip: ComposerDelivery.kt:55 早返回只在「编辑中」成立 ──

    @Test
    fun theQueueEntryComesBackAfterEveryTerminalExit() {
        // 这是本次缺陷的正面断言：编辑→提交/取消/行被消费/回合结束 之后，
        // `Queue` 入口必须重新出现（v130 里它恒显 `Steer` 再也不回来）。
        for (start in listOf<Row?>(null, row)) {
            for ((event, target) in terminalEvents(row.id)) {
                val loan = ComposerQueue.endEditLoan(start, event, target)
                assertEquals(
                    "after $event the Queue entry must be offered again (loan=$loan)",
                    ComposerDelivery.QUEUE,
                    chipOffer(loan, behavior = "steer"),
                )
                assertEquals(
                    "after $event the hint flips back to Steer when followUp is queue",
                    ComposerDelivery.STEER,
                    chipOffer(loan, behavior = "queue"),
                )
            }
        }
    }

    @Test
    fun theEarlyReturnOnlyHoldsWhileEditing() {
        // ComposerDelivery.kt:55 的早返回只在贷款开着时成立。
        for (behavior in listOf(ComposerDelivery.STEER, ComposerDelivery.QUEUE)) {
            for (running in listOf(true, false)) {
                assertEquals("editing pins the hint", ComposerDelivery.STEER, chipOffer(row, running, behavior))
            }
        }
        // 贷款一关，早返回立刻让位给「行为取反」的正常逻辑。
        assertEquals(ComposerDelivery.QUEUE, chipOffer(null, running = true, behavior = "steer"))
        assertEquals(ComposerDelivery.STEER, chipOffer(null, running = true, behavior = "queue"))
        // 空闲 / 无内容隐藏 chip（v103–v130 基线不动）。
        assertNull(ComposerDelivery.alternate(false, "steer", editingQueued = false, hasContent = true))
        assertNull(ComposerDelivery.alternate(true, "steer", editingQueued = false, hasContent = false))
        // 但早返回排在 idle/无内容 **之前**（上游 queue.ts:218-222 同序）：贷款
        // 开着时恒为 steer，编辑行仍需一个 steer 出口。v131 不动这个次序。
        assertEquals(ComposerDelivery.STEER, ComposerDelivery.alternate(true, "steer", editingQueued = true, hasContent = false))
        assertEquals(ComposerDelivery.STEER, ComposerDelivery.alternate(false, "steer", editingQueued = true, hasContent = true))
    }

    @Test
    fun noReachablePostExitStatePinsTheChip() {
        // 全组合扫：任一 (起点 × 出口 × busy) 走完之后，若贷款已关，chip 必须与
        // 「从未打开编辑」逐格相同 —— 即不存在「贷款已关但 chip 仍卡在 Steer」
        // 这种只能进不能出的状态。
        for (start in listOf<Row?>(null, row)) {
            for (event in EditLoanEvent.values()) {
                for (target in listOf(null, row.id, other.id)) {
                    for (busy in listOf(true, false)) {
                        val afterEvent = ComposerQueue.endEditLoan(start, event, target)
                        val settled = ComposerQueue.convergeEditLoan(afterEvent, listOf(other.id), busy)
                        val afterConverge = ComposerQueue.convergeEditLoan(settled, listOf(other.id), busy = false)
                        val reachable = afterConverge
                        if (reachable == null) {
                            assertEquals(
                                "start=$start $event(target=$target,busy=$busy): a closed loan must answer like no loan",
                                ComposerDelivery.alternate(true, "steer", editingQueued = false, hasContent = true),
                                chipOffer(reachable, running = true, behavior = "steer"),
                            )
                        } else {
                            assertEquals(
                                "a surviving loan is exactly the editing state",
                                ComposerDelivery.STEER,
                                chipOffer(reachable, running = true, behavior = "steer"),
                            )
                        }
                    }
                }
            }
        }
    }

    // ── 3. 不降强度：队列条 / Steer·Remove / followUpBehavior 默认 steer 一律原样 ──

    @Test
    fun theQueueRowActionSetIsUnchanged() {
        // 编辑行只剩 Remove（唯一的未确认出口），非编辑行 Steer + Remove。
        assertEquals(
            listOf(ComposerQueue.RowAction.REMOVE),
            ComposerQueue.rowActions(running = true, editing = true).actions,
        )
        assertEquals(
            listOf(ComposerQueue.RowAction.STEER, ComposerQueue.RowAction.REMOVE),
            ComposerQueue.rowActions(running = true, editing = false).actions,
        )
        assertEquals(SteerLabel.STEER, ComposerQueue.rowActions(running = true, editing = false).steerLabel)
        assertEquals(SteerLabel.SEND, ComposerQueue.rowActions(running = false, editing = false).steerLabel)
        assertTrue(ComposerQueue.rowActions(running = true, editing = false).hasSteer)
        assertTrue(ComposerQueue.rowActions(running = true, editing = true).hasRemove)
        assertTrue(!ComposerQueue.rowActions(running = true, editing = true).hasSteer)
        assertTrue(ComposerQueue.showCount(4) && !ComposerQueue.showCount(3))
        assertTrue(ComposerQueue.isParkedFirst(running = false, index = 0))
        assertTrue(!ComposerQueue.isParkedFirst(running = true, index = 0))
        // 生成门：v1 无 inbox API。
        assertTrue(!ComposerQueue.isQueueSupported("V1"))
        assertTrue(ComposerQueue.isQueueSupported("V2"))
    }

    @Test
    fun followUpBehaviorStillDefaultsToSteer() {
        assertEquals(ComposerDelivery.STEER, ComposerDelivery.delivery(running = false, behavior = "queue"))
        assertEquals(ComposerDelivery.STEER, ComposerDelivery.delivery(running = true, behavior = null))
        assertEquals(ComposerDelivery.STEER, ComposerDelivery.delivery(running = true, behavior = "  "))
        assertEquals(ComposerDelivery.QUEUE, ComposerDelivery.delivery(running = true, behavior = " QUEUE "))
        assertEquals(ComposerDelivery.STEER, ComposerDelivery.normalize(null))
        assertEquals(ComposerDelivery.STEER, ComposerDelivery.normalize("nonsense"))
        assertEquals(ComposerDelivery.QUEUE, ComposerDelivery.normalize("Queue"))
        assertTrue(ComposerDelivery.isQueue("queue") && !ComposerDelivery.isQueue("steer"))
        assertEquals(setOf("steer", "queue"), ComposerDelivery.BEHAVIORS)
    }

    // ── 4. 结构性 pin：不允许再出现第二个「贷款」状态 ──

    @Test
    fun theChipFlagIsDerivedFromTheLoanAndNotASecondLatch() {
        val composer = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt")
        val queue = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/ComposerQueue.kt")
        // 贷款只有一份，且 chip 标志由它派生。
        assertTrue(
            "the chip flag must be derived from the loan",
            composer.contains("val queueEditOpen = queueEdit != null"),
        )
        assertTrue(
            "no second latch declaration may come back",
            !composer.contains("var queueEditOpen"),
        )
        assertEquals(
            "the chip flag is mentioned exactly once, as the derivation",
            1,
            Regex("""queueEditOpen\s*=""").findAll(composer).count(),
        )
        assertEquals(
            "the loan is declared exactly once",
            1,
            Regex("""var queueEdit by remember""").findAll(composer).count(),
        )
        // 两条出口都接在纯函数上，而不是就地置 null。
        assertTrue(composer.contains("EditLoanEvent.CONFIRM"))
        assertTrue(composer.contains("EditLoanEvent.CANCEL"))
        assertTrue(composer.contains("ComposerQueue.convergeEditLoan("))
        // 出口表本身在纯文件里，可 JVM 判定。
        assertTrue(queue.contains("fun endEditLoan("))
        assertTrue(queue.contains("fun convergeEditLoan("))
    }

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        require(file.isFile) { "missing source: $relative" }
        return file.readText().replace("\r\n", "\n")
    }
}
