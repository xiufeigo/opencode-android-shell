package com.xiufeigo.opencodeshell

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import androidx.compose.runtime.Composition
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * v76 P0：movable 内容**回退路径**的运行时可执行证据。
 *
 * 这不是 app 结构的钉子（那条在 [SessionPaneArrangementV76Test]），而是本仓库
 * 仍然依赖的 Compose 运行时行为的钉子：移动端切 tab 时 `key(mobileTab)` 会整块
 * dispose pane，`composerSlot` 靠 movable 内容把编辑器节点、草稿与已打开的弹层
 * 搬回来（`SessionScreen.composerSlot`）。这里用手驱的 [Recomposer] + 一个把
 * "一个节点只能有一个父节点"当硬约束的假 Applier（`LayoutNode.insertAt` 的同款
 * 前置条件）复刻那条路径，钉住三件事：
 *
 * 1. 嵌套 movable 内容（外层 composer 区 + 内层工具行）**同一帧一起移动**不会
 *    双挂载 —— 也就是 `CopyNodesToNewAnchorLocation` 的
 *    "Cannot insert … because it already has a parent" 不是"嵌套"本身造成的。
 *    所以 v76 的修法不是把嵌套拆掉，而是让 pane 那条路径**不再移动**。
 * 2. 移动是**移动**：composer 的节点实例跨帧不变（`assertSame`），即"翻转不重建"
 *    这条性质在回退路径上仍然成立。
 * 3. 移动后的树形状正确（viewport / 宿主 / composer 列 / 两个形态的 Row）。
 * 4. **v81 P0**：composer 卡内部单树化（`ComposerArrangement`：两个子节点=编辑器+工具行）
 *    之后，形态翻转**连移动都不是**。第二个用例把"值"和"节点操作"分开记录
 *    （`Updater.update(value, block)` 建模 modifier 值变更），翻转后只有 4 条
 *    `update …`、**0 条 insert/remove/move**，且 editor / toolRow / ghost / controls
 *    实例全部 `assertSame` —— 即"控件也不 move"，与 v75 槽位时代（控件照旧 move）
 *    正好相反。这就是 720x1280 @420dpi 打开会话 4/4 崩的那条路径的闭合。
 *
 * 升级 Compose 时这个测试是最早的报警器：如果 movable 内容的语义变了，切 tab 会
 * 丢编辑器状态（或崩），而不是等到真机才发现。
 */
class MovableContentMoveHarnessTest {

    /** One node in the fake tree; `parent` mirrors `LayoutNode._foldedParent`. */
    private class Node(val name: String, private val ops: MutableList<String>? = null) {
        var parent: Node? = null
        val children = mutableListOf<Node>()

        /**
         * The form value this node carries — what a form flip writes when the
         * node's *values* follow the form (the arrangement's numbers, the
         * editor's chain, the tool row's modifier, the share ghost's weight).
         * The write lands in the applier's operation log, so "the flip wrote a
         * value" and "the flip moved a node" cannot be confused.
         */
        var form: String = ""
            set(value) {
                field = value
                ops?.add("update $name")
            }

        override fun toString() = name
    }

    /** `UiApplier`'s contract, enforced: `insertAt` requires a parentless node. */
    private class FakeApplier(root: Node) : AbstractApplier<Node>(root) {
        val operations = mutableListOf<String>()

        override fun insertTopDown(index: Int, instance: Node) = Unit

        override fun insertBottomUp(index: Int, instance: Node) {
            check(instance.parent == null) {
                "Cannot insert $instance because it already has a parent"
            }
            operations += "insert ${instance.name}"
            instance.parent = current
            current.children.add(index, instance)
        }

        override fun remove(index: Int, count: Int) {
            repeat(count) {
                val removed = current.children.removeAt(index)
                operations += "remove ${removed.name}"
                removed.parent = null
            }
        }

        override fun move(from: Int, to: Int, count: Int) {
            val moved = (0 until count).map { current.children.removeAt(from) }
            current.children.addAll(to, moved)
        }

        override fun onClear() {
            root.children.clear()
        }
    }

    /** Frames are delivered by the test, so each `tick` is exactly one frame. */
    private class ManualFrameClock : MonotonicFrameClock {
        private val frames = Channel<Long>(Channel.UNLIMITED)
        private var nanos = 0L
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = onFrame(frames.receive())
        fun tick() {
            nanos += 16_000_000
            frames.trySend(nanos)
        }
    }

    @OptIn(InternalComposeApi::class)
    @Composable
    private fun node(
        name: String,
        ops: MutableList<String>? = null,
        form: String = "",
        content: @Composable () -> Unit = {},
    ) {
        ComposeNode<Node, FakeApplier>(
            factory = { Node(name, ops) },
            // `Updater.update(value, block)` writes only when the value differs
            // from the previous composition — and not while inserting — which is
            // exactly a modifier value change, i.e. the half a form flip is
            // allowed to be.
            update = { update(form) { newForm -> this.form = newForm } },
        ) { content() }
    }

    @Composable
    private fun ProbeInner() {
        node("innerBody")
    }

    @Composable
    private fun ProbeOuter(minimal: Boolean, inner: @Composable () -> Unit) {
        node("composerColumn") {
            if (minimal) {
                node("rowOne") {
                    node("editor")
                    inner()
                }
            } else {
                node("editor")
                node("rowStacked") { inner() }
            }
        }
    }

    /**
     * v81: the composer card as `ComposerArrangement` lays it out — one `Layout`
     * with **two children in both forms**: the editor and the tool row (whose
     * first child is the zero-width spacer carrying the editor's share of the
     * one-row band). The form reaches those children only as *values* — the
     * numbers the arrangement measures with, the editor's chain, the tool row's
     * modifier, the ghost's weight — which is what the probe's `form` writes
     * model.
     *
     * This is the shape the v81 crash fix rests on: neither child's call site
     * depends on the form, so a flip can neither dispose the editor (v80's fix:
     * the focus, the input session and the IME connection survive) nor *move* the
     * controls (this round's: the v75 slot that did is retired — that move is
     * what threw `Cannot insert LayoutNode@… because it already has a parent` on
     * the 420 window). The probe fails loudly if a future round puts either child
     * back under a form-dependent parent.
     */
    @Composable
    private fun ProbeComposerV81(
        minimal: Boolean,
        ops: MutableList<String>,
        editor: @Composable () -> Unit,
        controls: @Composable () -> Unit,
    ) {
        val form = if (minimal) "oneRow" else "stacked"
        node("composerArrangement", ops, form) {
            node("editorField", ops, form) { editor() }
            node("toolRow", ops, form) {
                node("editorShareGhost", ops, if (minimal) "weighted" else "unweighted")
                controls()
            }
        }
    }

    /** The field's own subtree: the nodes a re-create would take the caret with. */
    @Composable
    private fun ProbeEditorBody() {
        node("editorBody")
    }

    /** The tool row's content: the model chip and the popup it owns. */
    @Composable
    private fun ProbeControlsBody() {
        node("controlsBody")
    }

    @Test
    fun aNestedMovableContentMovedInOneFrameKeepsItsNodes() = runBlocking {
        withTimeout(30_000) {
            val clock = ManualFrameClock()
            val recomposer = Recomposer(coroutineContext + clock)
            val failure = AtomicReference<Throwable?>(null)
            val job = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
            job.invokeOnCompletion { failure.set(it) }
            val root = Node("root")
            val applier = FakeApplier(root)
            val composition = Composition(applier, recomposer)

            // The session pane's two arrangements, and the composer's two forms —
            // both as exclusive branches: the shape the *pane* used to have, and
            // the shape the composer's controls had until v81 retired the slot
            // that carried them across the flip.
            var shared by mutableStateOf(false)
            var minimal by mutableStateOf(false)

            composition.setContent {
                val inner = remember { movableContentOf { ProbeInner() } }
                val outer = remember { movableContentOf { ProbeOuter(minimal, inner) } }
                node("paneColumn") {
                    node("viewportBox") {
                        if (!shared) node("wrapperFloating") { outer() }
                    }
                    if (shared) node("wrapperShared") { outer() }
                }
            }
            awaitTree(clock, root, "the first composition") {
                dump(root).trimEnd() == """
                    root
                     paneColumn
                      viewportBox
                       wrapperFloating
                        composerColumn
                         editor
                         rowStacked
                          innerBody
                """.trimIndent()
            }
            assertNull("the first composition", failure.get())
            val composerColumn = root.find("composerColumn")!!

            // One frame, both flips: the pane's arrangement and the composer's form.
            applier.operations.clear()
            Snapshot.withMutableSnapshot {
                shared = true
                minimal = true
            }
            awaitTree(clock, root, "the double flip") {
                dump(root).trimEnd() == """
                    root
                     paneColumn
                      viewportBox
                      wrapperShared
                       composerColumn
                        rowOne
                         editor
                         innerBody
                """.trimIndent()
            }
            assertNull("a nested movable content moved in one frame", failure.get())

            // The move kept the node *instance* (so the editor's `remember`ed state,
            // its IME session and an open popup survive) …
            assertSame(composerColumn, root.find("composerColumn"))
            // …and it is detach-then-insert, in that order: the runtime removes the
            // movable content's node from the old parent *before* inserting it under
            // the new one. That is exactly the step the crashing frame failed to do
            // (the node still had a parent when `CopyNodesToNewAnchorLocation` ran),
            // which is why the pane no longer relies on a move at all.
            assertEquals(
                listOf("remove composerColumn", "insert composerColumn"),
                applier.operations.filter { it.endsWith(" composerColumn") },
            )
            assertSame(
                "the moved node ends up under the new wrapper",
                root.find("wrapperShared"),
                root.find("composerColumn")!!.parent,
            )
            recomposer.cancel()
        }
    }

    @Test
    fun theFormFlipWritesTheCardsValuesAndMovesNeitherChild() = runBlocking {
        withTimeout(30_000) {
            val clock = ManualFrameClock()
            val recomposer = Recomposer(coroutineContext + clock)
            val failure = AtomicReference<Throwable?>(null)
            val job = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
            job.invokeOnCompletion { failure.set(it) }
            val root = Node("root")
            val applier = FakeApplier(root)
            val composition = Composition(applier, recomposer)

            var minimal by mutableStateOf(false)
            composition.setContent {
                node("paneColumn") {
                    ProbeComposerV81(
                        minimal = minimal,
                        ops = applier.operations,
                        editor = { ProbeEditorBody() },
                        controls = { ProbeControlsBody() },
                    )
                }
            }
            awaitTree(clock, root, "the stacked form") {
                dump(root).trimEnd() == """
                    root
                     paneColumn
                      composerArrangement
                       editorField
                        editorBody
                       toolRow
                        editorShareGhost
                        controlsBody
                """.trimIndent()
            }
            assertNull("the stacked form", failure.get())
            val editorField = root.find("editorField")!!
            val editorBody = root.find("editorBody")!!
            val toolRow = root.find("toolRow")!!
            val ghost = root.find("editorShareGhost")!!
            val controls = root.find("controlsBody")!!

            // The flip that costs the caret on the 420 window: same frame, the
            // form alone.
            applier.operations.clear()
            Snapshot.withMutableSnapshot { minimal = true }
            // The tree cannot move, so the flip's evidence is in the operations:
            // drive frames until the values it writes have landed.
            awaitOps(clock, applier.operations, 4)
            assertNull("a form flip", failure.get())

            // The flip is a **value** change and nothing else — the arrangement's
            // numbers, the editor's chain, the tool row's modifier, the share
            // ghost's weight — with no insert, no remove and no move for either
            // child. That is the v81 fix: a call site that survives a flip cannot
            // be disposed (v80's editor) *and* cannot be moved (the v75 slot's
            // controls, whose move is what threw `Cannot insert LayoutNode@…
            // because it already has a parent` on the 420 window, 4/4).
            assertEquals(
                listOf(
                    "update composerArrangement",
                    "update editorField",
                    "update editorShareGhost",
                    "update toolRow",
                ),
                applier.operations.sorted(),
            )
            assertEquals(
                "a flip may not insert, remove or move a node",
                emptyList<String>(),
                applier.operations.filter { !it.startsWith("update ") },
            )
            // …and every node instance is the one the previous form had: the
            // editor keeps its focus, its input session and its IME connection,
            // and the controls keep the chip's `expanded` and the popup window it
            // owns. Nothing here is rebuilt, so nothing here has to be handed
            // back.
            assertSame(editorField, root.find("editorField"))
            assertSame(editorBody, root.find("editorBody"))
            assertSame(toolRow, root.find("toolRow"))
            assertSame(ghost, root.find("editorShareGhost"))
            assertSame(controls, root.find("controlsBody"))
            recomposer.cancel()
        }
    }

    /** Drives frames until [count] value writes have landed in [ops]. */
    private suspend fun awaitOps(
        clock: ManualFrameClock,
        ops: MutableList<String>,
        count: Int,
    ) {
        repeat(200) {
            if (ops.count { it.startsWith("update ") } >= count) return
            clock.tick()
            delay(2)
        }
        throw AssertionError("the flip's value writes did not land; ops=$ops")
    }

    /** Drives frames until [predicate] holds, then fails the test with the tree. */
    private suspend fun awaitTree(
        clock: ManualFrameClock,
        root: Node,
        what: String,
        predicate: () -> Boolean,
    ) {
        repeat(200) {
            if (predicate()) return
            clock.tick()
            delay(2)
        }
        throw AssertionError("$what did not settle; tree:\n${dump(root)}")
    }

    private fun Node.find(name: String): Node? {
        if (this.name == name) return this
        children.forEach { it.find(name)?.let { found -> return found } }
        return null
    }

    private fun dump(node: Node, depth: Int = 0): String =
        " ".repeat(depth) + node.name + "\n" + node.children.joinToString("") { dump(it, depth + 1) }
}
