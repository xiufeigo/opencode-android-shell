package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.timeline.RawParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v46 tD 「移动到…」批：
 *
 * 1. 提交卡死 — `moveSessionTo` 在 probe 未落定时静默返回，请求没发出、
 *    回调没触发（与 `createSession` 当年修过的同类 hang 同源）。修法是
 *    promise 必达 + 乐观行即时反馈，这里 pin 住乐观行的形状与回滚语义：
 *    失败的提交不能在时间线留下 `Location changed` 孤儿行。
 * 2. `Location changed` 丢失项目名 — 行渲染只读 `rawJson`，快照行没有
 *    `rawJson` 就只剩裸 label。乐观行沿用服务端嵌套形态
 *    (`MovePayload.location`)，渲染与 live 行一致。
 *
 * transport/回调本身需要 Android（`ChatViewModel` 无 JVM 实例），所以这里
 * 只 pin 纯规则：行形状、回滚、事件/快照确认判定。`ChatViewModel` 内的装配
 * （插入→事件/快照/TTL 退役→失败回滚）直接复用这些规则。
 */
class MoveOptimisticRowTest {

    private val directory = "/home/xiufeigo/v36projB"
    private val rowID = "pending-move-test"
    private val now = 1_786_000_000_000L

    private fun optimistic() = buildMoveOptimisticMessage(rowID, directory, now)

    private fun durable(
        id: String,
        created: Long,
        dir: String = directory,
    ) = ChatStore.UiMessage(
        id = id,
        role = "assistant",
        timeCreated = created,
        parts = listOf(
            ChatStore.UiPart(
                id = "$id:notice",
                type = "location-switched",
                text = RawParts.basename(dir),
            ),
        ),
    )

    private fun textMessage(id: String, created: Long) = ChatStore.UiMessage(
        id = id,
        role = "user",
        timeCreated = created,
        parts = listOf(ChatStore.UiPart(id = "$id:text", type = "text", text = "hi")),
    )

    // ------------------------------------------------------------ row shape

    @Test
    fun optimisticRowRendersWithTheProjectName() {
        val row = optimistic()
        assertEquals(rowID, row.id)
        assertEquals("assistant", row.role)
        assertEquals(1, row.parts.size)
        val part = row.parts.single()
        assertEquals("location-switched", part.type)
        assertEquals("$rowID:notice", part.id)
        // The live-row reader resolves the project name off the nested shape.
        assertEquals("v36projB", RawParts.switch(part.rawJson).value)
    }

    @Test
    fun optimisticPayloadUsesTheServerNestedShape() {
        // `MovePayload.location` (`schema/src/session-inbox.ts:31-35`), not a
        // top-level directory — so every reader that understands the server
        // row understands this one.
        assertEquals(
            "v36projB",
            RawParts.switch(moveOptimisticRawJson(directory)).value,
        )
    }

    // ---------------------------------------------------------------- rollback

    @Test
    fun failedSubmitRollsTheOptimisticRowBack() {
        var state = ChatStore.ChatState(sessionID = "ses_1")
        state = ChatStore.reduce(state, ChatStore.Event.MessageUpdated(optimistic()))
        assertTrue(state.messages.any { it.id == rowID })

        // The failure path: retire = drop the row, keep everything else.
        state = ChatStore.reduce(state, ChatStore.Event.MessageRemoved(rowID))
        assertFalse(state.messages.any { it.id == rowID })
    }

    @Test
    fun rollbackKeepsUnrelatedRows() {
        var state = ChatStore.ChatState(sessionID = "ses_1")
        state = ChatStore.reduce(state, ChatStore.Event.MessageUpdated(textMessage("msg_a", now - 2)))
        state = ChatStore.reduce(state, ChatStore.Event.MessageUpdated(durable("msg_b", now - 1)))
        state = ChatStore.reduce(state, ChatStore.Event.MessageUpdated(optimistic()))
        assertEquals(3, state.messages.size)

        state = ChatStore.reduce(state, ChatStore.Event.MessageRemoved(rowID))
        assertEquals(listOf("msg_a", "msg_b"), state.messages.map { it.id })
    }

    @Test
    fun failedSubmitWithoutARowChangesNothing() {
        // No transport at tap time: no row is ever inserted, so there is
        // nothing to roll back — the state must be untouched.
        val state = ChatStore.ChatState(sessionID = "ses_1")
        val next = ChatStore.reduce(state, ChatStore.Event.MessageRemoved("pending-move-missing"))
        assertTrue(next.messages.isEmpty())
    }

    // ------------------------------------------------------ confirm detection

    @Test
    fun liveMovedEventConfirmsButOwnRowDoesNot() {
        val live = ChatStore.Event.MessageUpdated(durable("msg_evt", now + 1))
        assertTrue(eventConfirmsMove(live, rowID))

        val own = ChatStore.Event.MessageUpdated(optimistic())
        assertFalse(eventConfirmsMove(own, rowID))

        val text = ChatStore.Event.MessageUpdated(textMessage("msg_t", now + 1))
        assertFalse(eventConfirmsMove(text, rowID))

        assertFalse(eventConfirmsMove(ChatStore.Event.MessageRemoved(rowID), rowID))
    }

    @Test
    fun snapshotConfirmsOnlyOnANewerLocationRow() {
        val twin = durable("msg_moved", now + 500)
        assertTrue(snapshotConfirmsMove(listOf(textMessage("msg_t", now), twin), now))

        // An older row is a previous move, not this submit's durable twin.
        val old = durable("msg_old", now - 60_000)
        assertFalse(snapshotConfirmsMove(listOf(old), now))

        // Skew allowance: a server clock slightly behind still confirms.
        val skewed = durable("msg_skew", now - MOVE_SNAPSHOT_SKEW_MS + 1_000)
        assertTrue(snapshotConfirmsMove(listOf(skewed), now))

        // No location row at all: not confirmed.
        assertFalse(snapshotConfirmsMove(listOf(textMessage("msg_t", now + 1)), now))
        assertFalse(snapshotConfirmsMove(emptyList(), now))
    }

    @Test
    fun constantsStaySane() {
        assertTrue(MOVE_OPTIMISTIC_TTL_MS > 0)
        assertTrue(MOVE_SNAPSHOT_SKEW_MS >= 0)
        assertNull(RawParts.switch(null).value)
    }
}
