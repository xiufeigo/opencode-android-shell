package com.xiufeigo.opencodeshell

import androidx.compose.ui.unit.dp
import com.xiufeigo.opencodeshell.ui.home.HomeRowLayout
import com.xiufeigo.opencodeshell.ui.home.HomeSessions
import com.xiufeigo.opencodeshell.ui.home.applyRenamedTitles
import com.xiufeigo.opencodeshell.ui.home.homeSessionItemIndex
import com.xiufeigo.opencodeshell.ui.home.mergeCatalogueRead
import com.xiufeigo.opencodeshell.ui.home.pruneRenamedTitles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * v47 真机批（`research/v47_verify/tC`）——窄屏残留 + 瞬态错乱的单测钉。
 *
 * - P0 「窄屏改名提交后『今天』整组消失」(`v47_34/35/36/37`，对照 `v46_22`)与
 *   P1 「1080 瞬态」(`v47_08/09`、`v47_24/25`)：乐观镜像与 catalogue 合并的
 *   时序——落在 accepted-`PATCH` 与服务端回显之间的一次全量读把被改名行丢了，
 *   镜像又被当成 dead 修掉，整组少一行。合并改走 [mergeCatalogueRead]：有镜像
 *   在飞的行，读里缺席也留着上一份；确认读照常退休镜像。
 * - P0 「窄屏编辑态」(`v47_30/31/32/33`，对照 `v46_20/21`)：高度预算在窄档的
 *   洞——`listMinHeight` 按 nav 还在算 floor（185 dp 卡上 65 < 68，掉到单行
 *   40 dp），而 nav 编辑时早已让位；编辑滚屏又落在行顶，把 plain item 的分组
 *   头顶到视口外面。floor 跟随编辑态，滚屏按视口落到分组头。
 * - P3 「编辑行左移 84px」(`v47_07`)：编辑行没有会话图标——普通行
 *   leading(24) + gap(8) = 32 dp，@2.625 正好 84 px。编辑器画同一份 leading。
 *
 * 纯函数断言数值；接线按本仓库惯例钉 source fact。
 */
class HomeRenameNarrowV47Test {

    private val zone: ZoneId = ZoneId.of("UTC")
    private val now: Long = at("2024-05-20T12:00:00Z")

    private fun at(iso: String): Long = Instant.parse(iso).toEpochMilli()

    private fun session(
        id: String,
        title: String = id,
        directory: String? = null,
        updatedAt: Long = 0L,
    ) = OpenCodeClient.Session(id = id, title = title, directory = directory, updatedAt = updatedAt)

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private val layout = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeRowLayout.kt")
    private val sessionsList = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeSessionsList.kt")
    private val homeScreen = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeScreen.kt")

    // --------------------------------------- P0-1/P1 the merge timing

    @Test
    fun `a read that misses a mirrored rename keeps the previous row`() {
        val prev = listOf(
            session("s1", "Untitled", "/w/p1", 100L),
            session("s2", "B", "/w/p1", 200L),
        )
        val merged = mergeCatalogueRead(prev, listOf(prev[1]), mapOf("s1" to "New"))
        // 留的是上一份的字段、按 updatedAt 倒序归位；下一轮确认读再收敛。
        assertEquals(listOf("s2", "s1"), merged.map { it.id })
        assertEquals("Untitled", merged.single { it.id == "s1" }.title)
        assertEquals(100L, merged.single { it.id == "s1" }.updatedAt)
        assertEquals("/w/p1", merged.single { it.id == "s1" }.directory)
    }

    @Test
    fun `an unmirrored absence still drops - deletes keep working`() {
        val prev = listOf(session("s1", "A", "/w/p1", 100L), session("s2", "B", "/w/p1", 200L))
        val next = listOf(prev[1])
        // 没有镜像在飞：远端删除/过期照常消失，不复活。
        assertEquals(next, mergeCatalogueRead(prev, next, emptyMap()))
        assertEquals(next, mergeCatalogueRead(prev, next, mapOf("s9" to "Other")))
        // 改名行在读里出现时按原样采用，不注入、不重排。
        assertEquals(next + prev[0], mergeCatalogueRead(prev, next + prev[0], mapOf("s1" to "A2")))
    }

    @Test
    fun `the merge is a no-op without history`() {
        val next = listOf(session("s1", "A", null, 100L))
        assertEquals(next, mergeCatalogueRead(emptyList(), next, mapOf("s1" to "A2")))
    }

    @Test
    fun `stale read keeps the accepted title, confirming read retires the mirror`() {
        val prev = listOf(
            session("s1", "Untitled", "/w/p1", at("2024-05-20T08:00:00Z")),
            session("s2", "B", "/w/p1", at("2024-05-20T09:00:00Z")),
        )
        val mirror = mapOf("s1" to "A-renamed")
        // 落在 PATCH 与回显之间的一次读：s1 缺席。
        val stale = listOf(prev[1])
        val merged = mergeCatalogueRead(prev, stale, mirror)
        assertEquals(listOf("s2", "s1"), merged.map { it.id })
        // 留下来的行带着旧标题出现，镜像不被当 dead 修掉，合成标题仍是接受的那个。
        val kept = pruneRenamedTitles(mirror, merged.associate { it.id to it.title })
        assertEquals(mirror, kept)
        assertEquals(
            "A-renamed",
            applyRenamedTitles(merged, kept).single { it.id == "s1" }.title,
        )
        // 服务端回显的确认读：镜像退休，行用服务端的标题。
        val confirmed = listOf(prev[1], session("s1", "A-renamed", "/w/p1", at("2024-05-20T08:00:00Z")))
        assertEquals(emptyMap<String, String>(), pruneRenamedTitles(mirror, confirmed.associate { it.id to it.title }))
        assertEquals(
            "A-renamed",
            applyRenamedTitles(confirmed, emptyMap()).single { it.id == "s1" }.title,
        )
    }

    @Test
    fun `every catalogue adoption merges before it prunes`() {
        assertEquals(
            "entry + freshness tick + import must all keep a mirrored-but-missing row",
            3,
            Regex("mergeCatalogueRead\\(sessions, list, renamedTitles\\)").findAll(homeScreen).count(),
        )
        assertTrue(homeScreen.contains("pruneRenamedTitles(renamedTitles, merged.associate"))
        assertTrue(homeScreen.contains("pruneRenamedTitles(renamedTitles, tickMerged.associate"))
    }

    @Test
    fun `a confirmed delete drops its row and mirror on the answer`() {
        val call = homeScreen.substring(homeScreen.indexOf("actions.delete(target)"))
        assertTrue(call.contains("sessions = sessions.filterNot { it.id == target.id }"))
        assertTrue(call.contains("renamedTitles = renamedTitles - target.id"))
    }

    // --------------------------------------- P0-2 the narrow budget + landing

    @Test
    fun `the floor follows the editing state on the keyboard-up narrow card`() {
        // 640x1500 @420 dpi 键盘弹起≈185 dp：nav 还在按 72 算，41 < 68 掉到短卡
        // floor（v54 P1：单行 40 dp 的视口连一条行都组不出来，短卡 floor 改为
        // header + pad + row）；编辑时 nav 让位，185 − 48 = 137 dp，本来就放得下
        // header + row。
        assertEquals(HomeRowLayout.LIST_SHORT_HEIGHT, HomeRowLayout.listMinHeight(185.dp, editing = false))
        assertTrue(HomeRowLayout.LIST_SHORT_HEIGHT > 40.dp)
        assertEquals(HomeRowLayout.LIST_MIN_HEIGHT, HomeRowLayout.listMinHeight(185.dp, editing = true))
        // 宽卡不受影响；默认参数保持 v45 契约。
        assertEquals(HomeRowLayout.LIST_MIN_HEIGHT, HomeRowLayout.listMinHeight(900.dp))
        assertEquals(HomeRowLayout.LIST_MIN_HEIGHT, HomeRowLayout.listMinHeight(900.dp, editing = true))
        assertEquals(HomeRowLayout.listMinHeight(185.dp), HomeRowLayout.listMinHeight(185.dp, editing = false))
    }

    @Test
    fun `the host derives the floor from the editing state`() {
        assertTrue(homeScreen.contains("listMinHeight(cardHeight, sessionsEditing)"))
    }

    @Test
    fun `the scroll lands on the group header while header plus row fit`() {
        // header(28) + pad(16) + row(40) = 84：窄屏键盘弹起的 101 dp 视口落头，
        // 40 dp floor 只能落行。
        assertEquals(16.dp, HomeRowLayout.GROUP_PAD_HEIGHT)
        assertFalse(HomeRowLayout.editorScrollToHeader(40.dp))
        assertFalse(HomeRowLayout.editorScrollToHeader(83.dp))
        assertTrue(HomeRowLayout.editorScrollToHeader(84.dp))
        assertTrue(HomeRowLayout.editorScrollToHeader(101.dp))
    }

    @Test
    fun `the first row sits two slots below its group header`() {
        val rows = HomeSessions.rows(
            listOf(
                session("s1", "A", "/w/p1", at("2024-05-20T08:00:00Z")),
                session("s2", "B", "/w/p1", at("2024-05-20T09:00:00Z")),
                session("s3", "C", "/w/p2", at("2024-05-19T08:00:00Z")),
            ),
        )
        val sections = HomeSessions.sections(rows, now, zone)
        // rows() 倒序：s2 在 s1 前；每组 header + pad + rows + 尾 spacer。
        assertEquals(2, homeSessionItemIndex(sections, "s2"))
        assertEquals(3, homeSessionItemIndex(sections, "s1"))
        assertEquals(7, homeSessionItemIndex(sections, "s3"))
        assertNull(homeSessionItemIndex(sections, "nope"))
    }

    @Test
    fun `the editor-open scroll prefers the group header`() {
        assertTrue(sessionsList.contains("HomeRowLayout.editorScrollToHeader("))
        assertTrue(sessionsList.contains("landOnGroupHeader"))
        // 落点仍跟成员签名走（v46 契约），不是 sections 实例。
        assertTrue(sessionsList.contains("LaunchedEffect(editing, sectionsSig)"))
    }

    // ------------------------------------------------------------- P3 alignment

    @Test
    fun `the rename editor draws the row's own leading`() {
        val editor = sessionsList.substring(sessionsList.indexOf("private fun HomeSessionRenameEditor("))
        assertTrue(
            "the draft must start where the row's title does, not one avatar slot left of it",
            editor.contains("HomeSessionLeading(row = row"),
        )
    }
}
