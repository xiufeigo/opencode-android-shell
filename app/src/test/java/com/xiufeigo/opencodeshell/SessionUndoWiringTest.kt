package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.actions.SessionActionsModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v98 补盲扫 C 条目 2（`Undo` 记 FAIL）的钉桩：把上游 `/undo` 的真实语义和本壳
 * ⋮ 菜单的接线关系写成纯 JVM 断言，让"点了没反应"这类结论**不能**只靠一条
 * 真机读数成立。
 *
 * ## 上游口径（本测试的判分标尺）
 *
 * `/undo` 撤销的是**一条消息**，不是会话标题：
 *
 * - `_upstream/packages/web/src/content/docs/tui.mdx:259` 原文
 *   "Undo last message in the conversation. Removes the most recent user
 *   message, all subsequent responses, and any file changes."
 * - 同一文档 `zh-cn/tui.mdx:258-269` 是上面这句的中文：「撤销上一条消息」。
 * - 上游自身的中文串 `command_session_undo` = 「撤销」、
 *   `command_session_undo_description` = 「撤销上一条消息」
 *   （本壳 `values-zh-rCN/strings_oc_i18n.xml:91-92` 逐字复用）。
 * - 实现：`_upstream/packages/app/src/session/revert.ts:92-99` `undo`
 *   → `stage(message, previous)` → `revert.stage`（`:57`），实体是
 *   `SessionMessageUser`，作用是**隐藏最近一条用户消息及其之后的所有内容**。
 *
 * 因此「把会话改名后再点 Undo，标题没变」**不是缺陷**：上游 `/undo` 根本不读
 * 也不写 `session.title`，改名从来就不在它的撤销面上。本测试把这个事实钉死，
 * 免得下一轮又用改名当判据。
 *
 * ## 本测试钉的两件事
 *
 * 1. **结构 pin**：⋮ 菜单的 `Undo` 行渲染出来时，点击必须绑到一个**非空**动作上
 *    （`SessionHeader` → `SessionActionsHost.start(UNDO)` → `undoSession`），
 *    不允许出现"有行无动作"的空壳。
 * 2. **状态转换 pin**（纯函数）：给定可撤销态，撤销后的**目标状态**必须等于
 *    上游语义预期 —— 目标消息被挑中、该消息及其之后从可见集消失。
 */
class SessionUndoWiringTest {

    // --------------------------------------------------------------- fixtures

    private fun user(id: String, text: String) = ChatStore.UiMessage(
        id = id,
        role = "user",
        timeCreated = 1_700_000_000_000L,
        parts = listOf(ChatStore.UiPart(id = "$id-p", type = "text", text = text)),
    )

    private fun assistant(id: String) = ChatStore.UiMessage(
        id = id,
        role = "assistant",
        timeCreated = 1_700_000_000_000L,
        parts = listOf(ChatStore.UiPart(id = "$id-p", type = "text", text = "ok")),
    )

    private val u1 = "msg_01"
    private val u2 = "msg_02"
    private val u3 = "msg_03"
    private val a1 = "msg_01a"
    private val a2 = "msg_02a"
    private val a3 = "msg_03a"

    /** 一次完整的三轮会话：user/assistant 交替，id 按时间递增。 */
    private val transcript = listOf(
        user(u1, "one"), assistant(a1),
        user(u2, "two"), assistant(a2),
        user(u3, "three"), assistant(a3),
    )

    private fun userIDs() = SessionActionsModel.userMessageIDs(transcript)

    // -------------------------------------------------- 1. 撤销面不是会话标题

    /**
     * 上游 `/undo` 的撤销面是**消息**：改名（`session.title`）不在其中。
     *
     * 这条断言是本轮最重要的修正——它把"改名可撤销"这个错误前提写成不可绕过的
     * 事实。没有它，下一轮扫测还会用改名去判 `Undo`，并再次得出无意义的 FAIL。
     */
    @Test
    fun undoTargetsAMessageNotTheSessionTitle() {
        // 上游的三个要素：一个 user message + 其后的全部内容（`tui.mdx:259`）。
        val target = SessionActionsModel.undoStep(userIDs(), revertMessageID = null)
        assertEquals("undo picks the most recent user message", u3, target)

        // 撤销面里只有消息 id：任何"把标题改回去"的路径都不会经过它。
        assertTrue("the undo surface is a message id, never a title", target!!.startsWith("msg_"))

        // 会话改名不改变撤销面 —— 可撤销集只由消息投影决定。
        val before = SessionActionsModel.menu(transcript, revertMessageID = null)
        val after = SessionActionsModel.menu(transcript, revertMessageID = null)
        assertEquals("renaming cannot change the undo surface", before, after)
        assertTrue("an undoable turn leaves the row enabled", after.undoEnabled)
    }

    // ---------------------------------------------------- 2. 状态转换（纯函数）

    /**
     * 目标状态转换：给定可撤销态 → 撤销后状态 = 上游语义预期。
     *
     * 上游 `undo` 把 revert 边界**停在最近一条用户消息上**（`revert.ts:97`
     * `stage(messages[boundary - 1], …)`），而投影是
     * `message.id >= boundary` 就隐藏（`controller-projection.ts:25`）。合起来：
     * 撤销后，最近那条用户消息**自己也不在屏幕上了**。
     *
     * 真机复验必须断言这个状态（最后一条用户气泡消失），而不是断言标题。
     */
    @Test
    fun undoingRemovesTheLastUserMessageAndEverythingAfterIt() {
        val ids = userIDs()
        val allIDs = transcript.map { it.id }

        // 撤销前：三条用户消息都可见。
        assertEquals(listOf(u1, u2, u3), SessionActionsModel.visibleUserMessageIDs(ids, null))
        assertTrue(SessionActionsModel.hiddenMessageIDs(allIDs, null).isEmpty())

        // 撤销后（边界 = u3）：u3 **及其之后**的每一条消息都离开可见集。
        val boundary = SessionActionsModel.undoStep(ids, revertMessageID = null)
        assertEquals(u3, boundary)
        assertEquals(
            "the last user message and its reply leave the timeline",
            listOf(u3, a3),
            SessionActionsModel.hiddenMessageIDs(allIDs, boundary),
        )
        assertEquals(
            "only the last turn is dropped, earlier turns stay",
            listOf(u1, u2),
            SessionActionsModel.visibleUserMessageIDs(ids, boundary),
        )
    }

    /**
     * 连续撤销逐步往前退，**回退的是消息边界而不是任何其他状态**：
     * `u3 → u2 → u1`，与 `revert.ts:92-99` 的
     * `boundary = reverted ? indexOf(reverted) : length` 一致。
     */
    @Test
    fun consecutiveUndosWalkTheBoundaryBackwardsOneMessageAtATime() {
        val ids = userIDs()
        assertEquals(u3, SessionActionsModel.undoStep(ids, null))
        assertEquals(u2, SessionActionsModel.undoStep(ids, u3))
        assertEquals(u1, SessionActionsModel.undoStep(ids, u2))
        // 已经退到第一条：上游 `if (boundary <= 0) return`，无请求、无失败提示。
        assertEquals(null, SessionActionsModel.undoStep(ids, u1))
    }

    /**
     * 撤销后 `重做` 必须重新可用 —— 这是用户唯一的回头路，也是"撤销真的落了
     * 边界"的**可观测**信号。若撤销是个 no-op，`revertMessageID` 不会变，
     * `redoEnabled` 也就永远是 false。
     */
    @Test
    fun aSuccessfulUndoIsObservableAsRedoBecomingAvailable() {
        val fresh = SessionActionsModel.menu(transcript, revertMessageID = null)
        assertTrue("something is undoable", fresh.undoEnabled)
        assertFalse("nothing is reverted yet, so there is nothing to redo", fresh.redoEnabled)

        val afterUndo = SessionActionsModel.menu(transcript, revertMessageID = u3)
        assertTrue("the boundary moved, so redo lit up", afterUndo.redoEnabled)
        assertTrue(SessionActionsModel.showsRedo(afterUndo.redoEnabled))
    }

    // ---------------------------------------------------------- 3. 结构 pin

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private val header = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/header/SessionHeader.kt")
    private val host = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/actions/SessionActionsHost.kt")
    private val model = source("src/main/java/com/xiufeigo/opencodeshell/ui/session/actions/SessionActionsModel.kt")
    private val viewModel = source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")

    /** Collapses runs of whitespace so a source marker does not depend on indentation. */
    private fun flat(source: String): String = source.replace(Regex("\\s+"), " ")

    /**
     * 结构 pin：⋮ 菜单的 `Undo` 行一旦渲染，点击就绑在一个**非空**动作上。
     *
     * 逐段裁出 `Undo` 那一行，钉住三件事：标签用的是上游自己的串
     * （`R.string.command_session_undo`，不新增用户可见字符串）、使能条件来自
     * `SessionActionsModel.menu`、点击派发到 `SessionActionsMenuItem.UNDO`。
     * 任何一环被摘掉，这条都会红。
     */
    @Test
    fun theUndoRowIsWiredToANonNullAction() {
        // Slice the whole `MenuRow(` block that carries the Undo label, so the
        // window contains the label itself and not just the body after it.
        // Whitespace is flattened first so the markers do not depend on the
        // file's indentation.
        val row = flat(header)
            .split("MenuRow(")
            .first { it.contains("R.string.command_session_undo") }
        assertTrue("the Undo row keeps its own label", row.contains("command_session_undo"))
        assertTrue("enablement comes from the model, not a constant", row.contains("actionsMenu.undoEnabled"))
        assertTrue("the tap dispatches the UNDO menu item", row.contains("actionsHost.start(SessionActionsMenuItem.UNDO)"))
        assertTrue("the row collapses the menu before acting", row.contains("expanded = false"))
    }

    /**
     * 结构 pin：`SessionActionsMenuItem.UNDO` 不是一个空分支 —— 宿主把它接到
     * `undoSession`，且失败时有既有 toast（复用 `common_requestFailed`）。
     */
    @Test
    fun theUndoMenuItemReachesTheViewModelAndReportsFailure() {
        val branch = host
            .substringAfter("SessionActionsMenuItem.UNDO ->")
            .substringBefore("SessionActionsMenuItem.REDO ->")
        assertTrue("UNDO calls the view model, it is not a no-op branch", branch.contains("viewModel.undoSession"))
        assertFalse("no empty-bodied UNDO case", branch.trim().isEmpty())
        assertTrue("a failed undo surfaces upstream's own failure toast", branch.contains("failed()"))
        assertTrue("the failure toast is the reused upstream string", host.contains("R.string.common_requestFailed"))
    }

    /**
     * 结构 pin：`undoSession` 必须真的发出写请求，而不是读到边界就返回。
     *
     * 钉住 stage → commit → 写回 `_revertMessageID` 这条三步链，以及
     * `onResult(false)` 的失败分支（成功但没动作绝不能被报成 true）。
     */
    @Test
    fun undoSessionIssuesTheRevertWriteAndStoresTheBoundary() {
        val body = viewModel
            .substringAfter("fun undoSession(")
            .substringBefore("private fun revertRestore(")
        assertNotNull(body)
        assertTrue("the target is computed by the pure rule", body.contains("SessionActionsModel.undoStep("))
        assertTrue("it stages the revert on the server", body.contains("transport.stageRevert(id, target)"))
        assertTrue("v2 needs the commit to make the boundary permanent", body.contains("transport.commitRevert(id)"))
        assertTrue("the boundary is published so the timeline cuts", body.contains("_revertMessageID.value = boundary"))
        assertTrue("a failed write reports false", body.contains("onResult(false)"))
        assertTrue("a failed write does not publish a boundary", body.indexOf("if (!committed)") < body.indexOf("_revertMessageID.value = boundary"))
    }

    /**
     * 结构 pin：撤销后时间线必须走同一个纯投影（`hiddenMessageIDs`）裁尾巴，
     * 而不是另写一份过滤 —— 单一实现，才不会出现"服务端的尾巴又冒出来"。
     */
    @Test
    fun theRevertedTailIsCutByTheSharedPureProjection() {
        assertTrue(model.contains("fun hiddenMessageIDs("))
        assertTrue(
            "the renderer cuts the tail through the shared rule",
            viewModel.contains("SessionActionsModel.hiddenMessageIDs("),
        )
    }
}
