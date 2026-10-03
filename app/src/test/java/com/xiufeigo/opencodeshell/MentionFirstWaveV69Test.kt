package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.composer.ComposerSuggestions
import com.xiufeigo.opencodeshell.ui.session.composer.MentionSources
import com.xiufeigo.opencodeshell.ui.session.composer.MentionText
import com.xiufeigo.opencodeshell.ui.session.composer.PopoverPick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v69 面板过滤与状态隔离 · 桶 A/B：「`@` 面板首波输入过滤晚一拍」+「`/btw`
 * 卡片锁焦点」。
 *
 * 真机 5558 3/3（`research/v68_verify/5558/19-24`）：空 composer 输 `@` 弹全量
 * 面板 → 立即输 `ex` → **列表不过滤**（`19_at_ex.png` 仍是 build / general /
 * explore / plan / Report / jira-SCB-Collector + 4 条路径），必须再动一次键盘，
 * `@exp` 才第一次收窄（`24_at_exp.png`）。`/` 面板同操作 1/1 正常。
 *
 * 根因两条，都在本类钉死：
 *
 * 1. **过滤键多了 description。** 上游 `useFilteredList` 的 `@` 列表是
 *    `filterKeys: ["label"]`（`composer/editor/interaction.ts:105-107`，
 *    fuzzysort 只跑 label），而本仓库把 description 也当过滤键
 *    （[ComposerSuggestions.matches]）。目录里每条 description 都是一整句英文，
 *    任何两字母 query 都能命中其中之一 —— 所以 `ex` 命中了 build/general/plan
 *    的说明文字，第一波看上去「完全没过滤」。
 * 2. **行代 hold 把 `/find/file` 那一波也冻住了。** v68 的
 *    [PopoverPick.steadyRows] 按住已绘制代，而文件波不是「纯尾部追加」——它
 *    **整块替换**文件区。于是 query 变了（anchor 变了、fixed 区已过滤）之后，
 *    迟到的文件答案落在同一个 anchor 里，被 hold 判成「中部/重排」而不放行，
 *    面板一直显示上一个 query 的路径，直到下一次击键换 anchor 才松开 —— 正是
 *    「必须再动一次键盘才刷新」。
 *
 * 修法：过滤键收回 label（[ComposerSuggestions.mentionRowSets]），并把
 * `@` 菜单拆成 **fixed / files** 两半 —— hold 只覆盖 fixed 区（迟到的 catalog
 * 波才可能往中间插行），文件波走 [ComposerSuggestions.joinMentionRows] 实时
 * 追加在 held 前缀下面，一个已绘制行的位置都不会动。
 *
 * 同类第二半（v69 C2，`/btw` 卡片锁焦点）：`/btw` 面板是 DialogHost 的 layer，
 * 整个窗口焦点归它，关它的那一击是 scrim 吃的，没人把焦点还给 composer ——
 * 只有切 tab 往返（重跑 `LaunchedEffect(draftKey)`）才复位。修法挂在
 * `LocalDialogActive` 翻转上（源钉见本类末尾）。
 */
class MentionFirstWaveV69Test {

    private fun agent(name: String, description: String) = OpenCodeClient.Agent(
        name = name,
        description = description,
        mode = "primary",
        native = true,
        hidden = false,
    )

    private fun skill(name: String, description: String) = OpenCodeClient.SkillInfo(name, description)

    /** `research/v68_verify/5558/19_at_ex.png` 的目录原样（含各自的说明文字）。 */
    private fun deviceAgents() = listOf(
        agent("build", "The default agent. Executes tools based on config..."),
        agent("general", "General-purpose agent for researching comple..."),
        agent("explore", "Fast agent specialized for exploring codebases..."),
        agent("plan", "Read-only agent for exploring the codebase and pl..."),
    )

    private fun deviceSkills() = listOf(
        skill("Report", "Use when the user wants to report an opencode issue"),
        skill("jira-SCB-Collector", "Query Jira SCB ops tickets by week via R"),
    )

    /** `19_at_ex.png` 里那一批 `/find/file` 答案（空 query 的最近路径）。 */
    private val emptyQueryPaths = listOf(
        "/home/xiufeigo/code/swarm-ide/backend/.env",
        "/home/xiufeigo/code/swarm-ide/spells/tree-ex",
        "/home/xiufeigo/code/swarm-ide/spells/router",
        "/home/xiufeigo/code/swarm-ide/secrets/api.key",
    )

    private fun fixed(query: String, files: List<String> = emptyList()) =
        ComposerSuggestions.mentionRowSets(
            query = query,
            agents = deviceAgents(),
            commands = emptyList(),
            skills = deviceSkills(),
            files = files,
            references = listOf(
                MentionSources.Reference(name = "docs", path = "docs", description = "project docs"),
            ),
            resources = emptyList(),
        )

    // ------------------------------------------- 根因 1：过滤键只能是 label

    @Test
    fun theFirstCharactersFilterTheCatalog() {
        // 5558 的 `@ex`：面板必须已经收窄，而不是仍列全量。
        assertEquals(
            listOf("explore"),
            fixed("ex").fixed.map { it.label },
        )
    }

    @Test
    fun aDescriptionIsNotAFilterKey() {
        // 上游 `filterKeys: ["label"]`：`build` 的说明里每个字母都有，
        // 「说明文字命中」从来不是上游的匹配规则。
        val rows = fixed("ex").fixed.map { it.label }
        assertFalse("build matched only through its description", rows.contains("build"))
        assertFalse("plan matched only through its description", rows.contains("plan"))
        assertFalse(
            "jira-SCB-Collector matched only through its description",
            rows.contains("jira-SCB-Collector"),
        )
        // 说明文字仍然显示在行上 —— 它是列，不是匹配目标。
        val explore = fixed("ex").fixed.single()
        assertEquals("Fast agent specialized for exploring codebases...", explore.description)
    }

    @Test
    fun anEmptyQueryStillListsEverything() {
        val rows = fixed("").fixed.map { it.label }
        assertEquals(
            listOf("docs") + listOf("build", "general", "explore", "plan") +
                listOf("Report", "jira-SCB-Collector"),
            rows,
        )
    }

    @Test
    fun theFullWidthQueryStillFoldsToAscii() {
        // W5-F 不变：中文输入法打出的全角 query 照样过滤 ASCII 目录。
        assertEquals(listOf("explore"), fixed("ｅｘ").fixed.map { it.label })
    }

    // ------------------------------------ 根因 2：文件波必须跟着 query 走

    @Test
    fun theFileAnswerForTheQueryOnScreenIsDrawnImmediately() {
        // 真机时序：`@`（answer A）→ 输 `ex`（anchor 变、answer A 仍在架上）→
        // 迟到的 answer B 落地。v68 之前 B 被 hold 冻住，第三次击键才见。
        val emptyGeneration = fixed("", emptyQueryPaths)
        val heldEmpty = PopoverPick.steadyRows(
            previous = emptyGeneration.fixed,
            previousAnchor = anchor(""),
            fresh = emptyGeneration.fixed,
            currentAnchor = anchor(""),
        )
        val heldEx = PopoverPick.steadyRows(
            previous = heldEmpty,
            previousAnchor = anchor(""),
            fresh = fixed("ex", emptyQueryPaths).fixed,
            currentAnchor = anchor("ex"),
        )
        // 击键这一下：anchor 变了 → 取新代（fixed 区已过滤）。
        assertEquals(listOf("explore"), heldEx.map { it.label })
        // 迟到答案这一下：anchor 没变，但文件区不在 hold 里，直接接在 held 前缀后。
        val afterAnswer = ComposerSuggestions.joinMentionRows(
            heldEx,
            fixed("ex", listOf("/repo/src/explore.kt")).files,
        )
        assertEquals(
            listOf("explore", "/repo/src/explore.kt"),
            afterAnswer.map { it.label },
        )
    }

    @Test
    fun aLateCatalogWaveStillCannotMoveAPaintedRow() {
        // v68 的不变量不能因为拆两半而丢：迟到的 reference 顶进 fixed 区时
        // 仍然按住已绘制代，于是已绘制的每一行都留在原位（位置 0..n 不变）。
        val painted = fixed("ex").fixed
        val withLateReference = ComposerSuggestions.mentionRowSets(
            query = "ex",
            agents = deviceAgents(),
            commands = emptyList(),
            skills = deviceSkills(),
            files = emptyList(),
            references = listOf(
                MentionSources.Reference(name = "explore-docs", path = "docs/explore"),
            ),
            resources = emptyList(),
        ).fixed
        val held = PopoverPick.steadyRows(painted, anchor("ex"), withLateReference, anchor("ex"))
        assertSame(painted, held)
        // 位置逐行不变：这就是 5560 `21-35` 报的那个错位量级。
        assertEquals(painted.map { PopoverPick.key(it) }, held.map { PopoverPick.key(it) })
    }

    @Test
    fun theFileWaveNeverShiftsAPaintedRow() {
        // 文件波只在 held 前缀**下面**动：换答案、缩答案，前面每一行的下标不变。
        val held = fixed("ex").fixed
        val painted = ComposerSuggestions.joinMentionRows(held, fixed("ex", emptyQueryPaths).files)
        val afterNarrowerAnswer =
            ComposerSuggestions.joinMentionRows(held, fixed("ex", listOf("/repo/explore.kt")).files)
        assertEquals(
            painted.indices.take(held.size).map { PopoverPick.key(painted[it]) },
            afterNarrowerAnswer.indices.take(held.size).map { PopoverPick.key(afterNarrowerAnswer[it]) },
        )
        assertEquals(listOf("/repo/explore.kt"), afterNarrowerAnswer.drop(held.size).map { it.label })
    }

    @Test
    fun theJoinedMenuIsExactlyTheJoinOfTheTwoHalves() {
        // `mentionItems` 保持既有契约（顺序 + `distinctBy` + 20 行上限），
        // 六个既有测试类继续按它断言。
        val halves = fixed("ex", emptyQueryPaths)
        assertEquals(
            ComposerSuggestions.joinMentionRows(halves.fixed, halves.files),
            ComposerSuggestions.mentionItems(
                query = "ex",
                agents = deviceAgents(),
                commands = emptyList(),
                skills = deviceSkills(),
                files = emptyQueryPaths,
                references = listOf(
                    MentionSources.Reference(name = "docs", path = "docs", description = "project docs"),
                ),
            ),
        )
        val many = (1..50).map { "src/file$it.kt" }
        assertEquals(
            ComposerSuggestions.LIMIT,
            ComposerSuggestions.mentionItems("", emptyList(), emptyList(), emptyList(), many).size,
        )
    }

    @Test
    fun theFixedBlockKeepsTheMenuCategoryOrder() {
        // references → agents → commands → skills → resources，files 单独一块。
        val rows = ComposerSuggestions.mentionItems(
            query = "",
            agents = listOf(agent("build", "")),
            commands = listOf(OpenCodeClient.CommandInfo(name = "init", description = "", source = "command", template = "")),
            skills = listOf(skill("kb", "")),
            files = listOf("src/a.kt"),
            references = listOf(MentionSources.Reference(name = "docs", path = "docs")),
            resources = listOf(MentionSources.Resource(server = "fs", name = "ticket", uri = "fs://t/1")),
        )
        assertEquals(
            listOf(
                ComposerSuggestions.Category.REFERENCE,
                ComposerSuggestions.Category.AGENT,
                ComposerSuggestions.Category.COMMAND,
                ComposerSuggestions.Category.SKILL,
                ComposerSuggestions.Category.RESOURCE,
                ComposerSuggestions.Category.FILE,
            ),
            rows.map { it.category },
        )
    }

    private fun anchor(query: String) = MentionText.Anchor(start = 0, query = query)

    // ------------------------------------------------------------ 接线不断言会断

    @Test
    fun theComposerHoldsTheFixedBlockAndLetsTheFileWaveThrough() {
        val composer = source(
            "src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt",
        )
        // 两半分开算：文件答案不再进 hold 的入参。
        assertTrue(composer, composer.contains("ComposerSuggestions.mentionRowSets("))
        assertTrue(
            composer,
            composer.contains(
                "PopoverPick.steadyRows(heldMentionRows, heldMentionAnchor, freshMentionRows.fixed, live.mention)",
            ),
        )
        assertTrue(composer, composer.contains("ComposerSuggestions.joinMentionRows("))
        // 旧的「按住整张列表」接线必须已经不在，否则 v68 那一版会复活。
        assertFalse(
            "the joined list must not be the held generation again",
            composer.contains("steadyRows(heldMentionRows, heldMentionAnchor, freshMentionItems"),
        )
        // v67 的点选身份语义保持。
        assertTrue(
            composer,
            composer.contains("PopoverPick.resolve(picked, items, popoverSession, draftKey) ?: return"),
        )
    }

    @Test
    fun aDialogLayerHandsTheComposerItsFocusBack() {
        // v69 C2：`/btw` 卡片是 DialogHost layer，关它的 scrim 那一击不会给
        // composer 获焦；焦点归还在栈空的那一刻，不在卡片自己的 dispose 上。
        val composer = source(
            "src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt",
        )
        assertTrue(composer, composer.contains("val dialogActive = LocalDialogActive.current"))
        assertTrue(composer, composer.contains("var layerWasOpen by remember { mutableStateOf(false) }"))
        val effect = composer
            .substringAfter("val dialogActive = LocalDialogActive.current")
            .substringBefore("val restoredPending")
        assertTrue(effect, effect.contains("if (dialogActive) {"))
        assertTrue(effect, effect.contains("layerWasOpen = true"))
        assertTrue(effect, effect.contains("if (!layerWasOpen) return@LaunchedEffect"))
        assertTrue(effect, effect.contains("focusRequester.requestFocus()"))
        // 键盘不跟着起：tab 往返也不起，起键盘会比原来的复位动作更吵。
        assertFalse("no keyboard hijack on layer close", effect.contains("keyboard?.show()"))
    }

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")
}
