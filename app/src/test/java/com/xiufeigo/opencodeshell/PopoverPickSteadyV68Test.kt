package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.composer.ComposerSuggestions
import com.xiufeigo.opencodeshell.ui.session.composer.MentionSources
import com.xiufeigo.opencodeshell.ui.session.composer.MentionText
import com.xiufeigo.opencodeshell.ui.session.composer.PopoverPick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v68 `@` 面板行代稳定：`research/v68_verify/5560/21-35` 在 `emulator-5560`
 * 上 4/4 复现点选错位两行（点 build 插入 @explore、点 general 插入
 * @plan），而完全同步的 `/` 菜单命中正确。
 *
 * 点选链路本身对稳定列表是 label-exact 的（行闭包自带 item、
 * [PopoverPick.resolve] 按 key 身份映射，`ComposerPopoverPickV67Test` 已钉死）；
 * `@` 独有的变量是行的异步波次：`refreshV2Catalogs` 按
 * agents → commands → skills → references → resources 顺序流式写入，而
 * references 在 [ComposerSuggestions.mentionItems] 里排在最前——面板画好后
 * 顶入的行会把已绘制的行整体下移，落指位置命中的就是另一行。
 *
 * 修法是打开的面板按住已绘制代 ([PopoverPick.steadyRows])：同 query 下只有
 * 纯尾部追加（`/find/file` 波）放行，顶部/中部插入、删除、重排一律保持，
 * 直到 query 变化或面板重开。本类钉死该合并规则与它所依赖的两条不变量
 * （key 唯一、resolve 跨代 label 一致）。
 */
class PopoverPickSteadyV68Test {

    private fun agent(name: String) = OpenCodeClient.Agent(
        name = name,
        description = "$name description",
        mode = "primary",
        native = true,
        hidden = false,
    )

    private fun command(name: String, description: String = "") = OpenCodeClient.CommandInfo(
        name = name,
        description = description,
        source = "command",
        template = "",
    )

    private fun skill(name: String, description: String = "") =
        OpenCodeClient.SkillInfo(name = name, description = description)

    private fun item(category: ComposerSuggestions.Category, label: String) =
        ComposerSuggestions.Item(category = category, label = label)

    /** `research/v68_verify/5560/31_pre.png` 的裸 `@` 全列表形态。 */
    private fun deviceCatalog(): Triple<List<OpenCodeClient.Agent>, List<OpenCodeClient.CommandInfo>, List<OpenCodeClient.SkillInfo>> =
        Triple(
            listOf(agent("build"), agent("general"), agent("explore"), agent("plan")),
            listOf(
                command("init", "guided AGENTS.md setup"),
                command("review", "review changes [commit|branch|pr], defaults to unco"),
            ),
            listOf(
                skill("OpenCode", "Use this skill for any question about OpenCode its"),
                skill("Report", "Use when the user wants to report an opencode issue"),
                skill("jira-SCB-Collector", "Query Jira SCB ops tickets by week via R"),
            ),
        )

    private fun anchor(query: String) = MentionText.Anchor(start = 0, query = query)

    // ------------------------------------------------------- key 唯一是底座

    @Test
    fun deviceShapedListHasUniqueKeys() {
        val (agents, commands, skills) = deviceCatalog()
        val rows = ComposerSuggestions.mentionItems(
            query = "",
            agents = agents,
            commands = commands,
            skills = skills,
            //  adversarial: a directory literally named like an agent, plus
            //  ordinary deep paths — FILE keys must still not collide.
            files = listOf("build", "src/main/Database.kt", "AGENTS.md"),
            references = listOf(
                MentionSources.Reference(name = "docs", path = "docs", description = "project docs"),
                MentionSources.Reference(name = "notes", path = "docs/notes", description = "team notes"),
            ),
            resources = listOf(
                MentionSources.Resource(server = "jira", name = "ticket", uri = "jira://ticket/1"),
            ),
        )
        val keys = rows.map { PopoverPick.key(it) }
        assertEquals("duplicate popover keys would misroute taps", keys.distinct(), keys)
    }

    @Test
    fun resolveIsLabelExactAcrossGenerations() {
        // 点选链路的不变量：无论列表是哪一代，同一个 key 解析出的永远是同一 label。
        // 这是 v68 定案（观测滞后，app 命中正确）成立的前提，也是 hold 生效时
        // drawn == resolved 的另一半。
        val (agents, commands, skills) = deviceCatalog()
        val painted = ComposerSuggestions.mentionItems("", agents, commands, skills, emptyList())
        val grown = ComposerSuggestions.mentionItems(
            "", agents, commands, skills, listOf("src/a.kt"),
            references = listOf(MentionSources.Reference(name = "docs", path = "docs")),
        )
        for (row in painted) {
            val hit = PopoverPick.resolve(row, grown, null, "s") ?: continue
            assertEquals(row.label, hit.label)
        }
        // 设备报的错位在稳定链路上不可复现：build 的 key 只会解出 build。
        val build = painted.first { it.label == "build" }
        assertEquals("build", PopoverPick.resolve(build, grown, null, "s")?.label)
    }

    // ------------------------------------------------------- steadyRows 规则

    private val painted = listOf(
        item(ComposerSuggestions.Category.AGENT, "build"),
        item(ComposerSuggestions.Category.AGENT, "general"),
        item(ComposerSuggestions.Category.AGENT, "explore"),
        item(ComposerSuggestions.Category.AGENT, "plan"),
    )

    @Test
    fun deadTriggerClearsTheRows() {
        assertTrue(PopoverPick.steadyRows(painted, anchor(""), painted, null).isEmpty())
    }

    @Test
    fun changedQueryTakesTheFreshList() {
        val fresh = listOf(item(ComposerSuggestions.Category.AGENT, "explore"))
        assertEquals(fresh, PopoverPick.steadyRows(painted, anchor(""), fresh, anchor("e")))
    }

    @Test
    fun firstPaintTakesTheFreshList() {
        assertEquals(painted, PopoverPick.steadyRows(emptyList(), anchor(""), painted, anchor("")))
    }

    @Test
    fun pureBottomAppendFlowsSoLateFilesStillAppear() {
        val fresh = painted + item(ComposerSuggestions.Category.FILE, "src/a.kt")
        assertEquals(fresh, PopoverPick.steadyRows(painted, anchor(""), fresh, anchor("")))
    }

    @Test
    fun lateTopPrependIsHeldSoPaintedRowsDoNotMove() {
        // 两个迟到的 reference 顶入：放行会把 build/general 顶到 2/3 位，
        // 正好是 21-35 报的错位量级；按住已绘制代，落指位置保持。
        val fresh = listOf(
            item(ComposerSuggestions.Category.REFERENCE, "docs"),
            item(ComposerSuggestions.Category.REFERENCE, "notes"),
        ) + painted
        assertSame(painted, PopoverPick.steadyRows(painted, anchor(""), fresh, anchor("")))
    }

    @Test
    fun middleInsertionIsHeld() {
        val fresh = painted.subList(0, 2) +
            item(ComposerSuggestions.Category.RESOURCE, "ticket") +
            painted.subList(2, painted.size)
        assertSame(painted, PopoverPick.steadyRows(painted, anchor(""), fresh, anchor("")))
    }

    @Test
    fun removalAndReorderAreHeld() {
        val removed = painted.filter { it.label != "build" }
        assertSame(painted, PopoverPick.steadyRows(painted, anchor(""), removed, anchor("")))
        assertSame(painted, PopoverPick.steadyRows(painted, anchor(""), painted.reversed(), anchor("")))
    }

    // ------------------------------------------------------- 接线不断言会断

    @Test
    fun theComposerHoldsThePaintedGeneration() {
        val composer = source(
            "src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/Composer.kt",
        )
        assertTrue(composer, composer.contains("PopoverPick.steadyRows"))
        // v69: the held generation is the menu's **fixed** block — the file
        // answers flow in live (`joinMentionRows`), because holding them is what
        // made `@` filtering read one keystroke late.
        assertTrue(composer, composer.contains("freshMentionRows.fixed"))
        assertTrue(composer, composer.contains("heldMentionAnchor"))
        assertTrue(composer, composer.contains("ComposerSuggestions.joinMentionRows("))
        // v67 的行身份语义保持：点选仍按 item 走，selected 索引不进 accept。
        assertTrue(
            composer,
            composer.contains("PopoverPick.resolve(picked, items, popoverSession, draftKey) ?: return"),
        )
        assertTrue(composer, composer.contains("onPick = ::accept"))
    }

    private fun source(path: String): String = File(path).readText()
}
