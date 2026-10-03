package com.xiufeigo.opencodeshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.xiufeigo.opencodeshell.ui.session.actions.SessionActionsModel
import java.io.File

/**
 * v99 — 运行时取证钉桩：⋮ 菜单 `Undo` 的「使能与执行不得矛盾」与撤销后的状态转换。
 *
 * 取证结论（真机 emulator-5556 + 后端 4095，见
 * `research/v97_fix/UNDO_RUNTIME_FIX.md`）：点 `Undo` 后**写入其实成功了**——
 * `GET /api/session/:id/message` 由 12 条掉到 3 条，用户消息连同其后 7 条回复被
 * `revert/commit` 真正删掉；但客户端时间线一帧未变，`Redo` 不出现，`revert` 为
 * `null`。原因是 commit **删除**行并**清空** `Session.Info.revert`，而客户端
 * ①靠投影隐藏尾巴、②靠 upsert 合并快照（永不删除）、③下一次快照又把
 * `_revertMessageID` 覆盖回 `null`——三者叠加，一次成功的撤销在 UI 上与 no-op
 * 完全无法区分。
 *
 * 本类钉住两件事：
 * 1. **`enabled=true` ⇒ 点击必然产生一次非空动作**（消除「使能与执行矛盾」）；
 * 2. 撤销的**状态转换**符合上游语义，且截断必须落到 store 而非只落在投影。
 */
class SessionUndoRuntimePinTest {

    // --------------------------------------------------------------- fixtures

    private fun user(id: String) = ChatStore.UiMessage(
        id = id,
        role = "user",
        timeCreated = 1_700_000_000_000L,
        parts = listOf(ChatStore.UiPart(id = "$id-p", type = "text", text = "q$id")),
    )

    private fun assistant(id: String) = ChatStore.UiMessage(
        id = id,
        role = "assistant",
        timeCreated = 1_700_000_000_000L,
        parts = listOf(ChatStore.UiPart(id = "$id-p", type = "text", text = "a$id")),
    )

    private val u1 = "msg_01"
    private val u2 = "msg_02"
    private val u3 = "msg_03"
    private val a1 = "msg_01a"
    private val a2 = "msg_02a"
    private val a3 = "msg_03a"

    /** 真机取证用的那一轮：1 条 user + 其后 1 条回复（上游 `/undo` 要求的最小可撤销态）。 */
    private val singleTurn = listOf(user("msg_10"), assistant("msg_10a"))

    private val threeTurns = listOf(
        user(u1), assistant(a1),
        user(u2), assistant(a2),
        user(u3), assistant(a3),
    )

    private fun users(rows: List<ChatStore.UiMessage>) = SessionActionsModel.userMessageIDs(rows)

    // ------------------------------------------- 1. enabled ⇒ 必然产生非空动作

    /**
     * **本轮最关键的一条**：`undoEnabled` 为真时，`undoStep` 必须给出非空目标。
     *
     * 取证时发现菜单项 `enabled=true` 却什么都不发生。这条断言把「可点」与
     * 「可执行」绑成一个不变量：只要 UI 判定可撤销，纯规则层就一定能算出撤销
     * 目标，`undoSession` 就不可能走到 `target == null` 那个静默 return。
     * （`enabled` 与 `undoStep` 读的都是同一份 user 消息投影，语义上必须一致。）
     */
    @Test
    fun anEnabledUndoRowAlwaysHasANonNullTarget() {
        val cases = listOf(
            "one turn" to singleTurn,
            "three turns" to threeTurns,
        )
        for ((label, rows) in cases) {
            for (boundary in listOf(null, u1, u2, u3)) {
                val menu = SessionActionsModel.menu(rows, revertMessageID = boundary)
                val target = SessionActionsModel.undoStep(users(rows), boundary)
                if (menu.undoEnabled) {
                    assertNotNull(
                        "enabled Undo ($label, boundary=$boundary) must have a target",
                        target,
                    )
                } else {
                    assertNull(
                        "a disabled Undo ($label, boundary=$boundary) must have nothing to do",
                        target,
                    )
                }
            }
        }
    }

    /**
     * 收敛方向：可撤销 ⇒ 恰好剩一条可撤销。边界退到第一条 user 时
     * `undoStep` 必为 `null`（上游 `if (boundary <= 0) return`），此时 `enabled`
     * 必须为 `false`——即**行会自己变灰**，不会出现「可点但无事可做」。
     */
    @Test
    fun aDisabledUndoRowIsExactlyTheOneWithNothingLeftToRewind() {
        val atFirst = SessionActionsModel.menu(threeTurns, revertMessageID = u1)
        assertTrue(
            "no visible user message left, so the row must be disabled",
            !atFirst.undoEnabled,
        )
        assertNull(SessionActionsModel.undoStep(users(threeTurns), u1))

        val empty = SessionActionsModel.menu(emptyList(), revertMessageID = null)
        assertTrue(!empty.undoEnabled)
        assertNull(SessionActionsModel.undoStep(users(emptyList()), null))
    }

    // ------------------------------------------------------ 2. 撤销状态转换

    /**
     * 状态转换：给定「1 user + 1 assistant」这一轮，撤销后该轮整体离开可见集，
     * 且被裁掉的是 **user 消息连同其后全部回复**（上游 `tui.mdx:259`）。
     */
    @Test
    fun undoingCutsTheLastUserMessageAndEverythingAfterIt() {
        val before = SessionActionsModel.menu(singleTurn, revertMessageID = null)
        assertTrue("a real turn is undoable", before.undoEnabled)

        val target = SessionActionsModel.undoStep(users(singleTurn), null)
        assertEquals("undo targets the last user message", "msg_10", target)

        val hidden = SessionActionsModel.hiddenMessageIDs(singleTurn.map { it.id }, target)
        assertEquals("user message + its replies all leave", listOf("msg_10", "msg_10a"), hidden)
        assertEquals("nothing survives that turn", 0, singleTurn.size - hidden.size)
    }

    /**
     * 状态转换（连撤）：三轮会话逐次回退，每次退一个 user 消息；
     * `Redo` 随之由 false 变 true（no-op 则它永远灰）。
     */
    @Test
    fun consecutiveUndosWalkTheBoundaryBackwardsOneTurnAtATime() {
        var boundary: String? = null
        val visited = mutableListOf<String>()
        repeat(3) {
            val target = SessionActionsModel.undoStep(users(threeTurns), boundary)
            assertNotNull("undo #$it has something to rewind", target)
            visited += target!!
            boundary = target
            assertTrue(
                "the boundary moved, so Redo lit up after undo #$it",
                SessionActionsModel.menu(threeTurns, boundary).redoEnabled,
            )
        }
        assertEquals(listOf(u3, u2, u1), visited)
        assertNull("back at the first user message there is nothing left", SessionActionsModel.undoStep(users(threeTurns), u1))
    }

    // ------------------------------------------------------------ 3. 结构 pin

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private val viewModel: String by lazy {
        source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")
    }

    private val transport: String by lazy {
        source("src/main/java/com/xiufeigo/opencodeshell/v2/V2Transport.kt")
    }

    /**
     * 断点就在这里：`commit` 之后必须把被截断的行**从 store 里删掉**。
     *
     * 少了这一步，成功的撤销会被下一次快照原样还原成 no-op：快照按 upsert 合并
     * （服务器不再返回的行永远删不掉），而同一读又把 `_revertMessageID` 覆盖回
     * 服务器已清空的 `null`，投影于是也不隐藏了。
     */
    @Test
    fun theCommittedTailIsDroppedFromTheStoreNotOnlyProjectedAway() {
        val undo = viewModel.substringAfter("fun undoSession(").substringBefore("fun redoSession(")
        assertTrue(
            "undoSession must drop the committed tail from _state",
            undo.contains("dropRevertedTail(boundary)"),
        )
        val drop = viewModel.substringAfter("private fun dropRevertedTail(")
        assertTrue(
            "the drop must write to the raw store",
            drop.contains("_state.update"),
        )
        assertTrue(
            "the drop must reuse the shared hidden-tail rule",
            drop.contains("SessionActionsModel.hiddenMessageIDs"),
        )
        // 失败路径仍然一个字节都不许动：既有 SessionRaceGuardTest 钉住的行必须在。
        assertTrue(
            "the boundary write is still session-scoped and unchanged",
            undo.contains("if (boundary != null && sessionID == id) _revertMessageID.value = boundary"),
        )
    }

    /**
     * 可观测性 pin：`undoSession` 的每一个**静默 return** 都必须留日志。
     *
     * 取证时最费时的一步就是「logcat 里什么都没有」——无传输、无 session id、
     * `undoStep` 为空这三条分支原本零输出，与成功路径无法区分。
     */
    @Test
    fun everySilentExitOfUndoSessionIsLogged() {
        val undo = viewModel.substringAfter("fun undoSession(").substringBefore("fun redoSession(")
        assertTrue("the dispatch hop is logged", undo.contains("undo: dispatch"))
        assertTrue("the settle hop is logged", undo.contains("undo: settle"))
        assertTrue("the no-transport exit is logged", undo.contains("undo: dropped — no transport"))
        assertTrue("the nothing-to-rewind exit is logged", undo.contains("undo: dropped — undoStep found nothing"))
        val stage = transport.substringAfter("override fun stageRevert(").substringBefore("override fun clearRevert(")
        assertTrue("the staged request and its answer are logged", stage.contains("revert/stage"))
        assertTrue("a swallowed stage failure is logged", stage.contains("revert: stage FAILED"))
        val commit = transport.substringAfter("override fun commitRevert(").substringBefore("override fun forkSession(")
        assertTrue("the commit request is logged", commit.contains("revert/commit"))
    }
}
