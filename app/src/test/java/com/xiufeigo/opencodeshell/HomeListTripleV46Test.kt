package com.xiufeigo.opencodeshell

import androidx.compose.ui.unit.dp
import com.xiufeigo.opencodeshell.ui.home.HomeRowLayout
import com.xiufeigo.opencodeshell.ui.home.HomeSessions
import com.xiufeigo.opencodeshell.ui.home.applyRenamedTitles
import com.xiufeigo.opencodeshell.ui.home.homeSectionsSignature
import com.xiufeigo.opencodeshell.ui.home.pruneRenamedTitles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * v46 真机三连（`research/v46_verify/tC`）——先单测复现再修。
 *
 * - P0 「宽屏改名输入框文字不可见」(`v46_05/06/14` vs 狭屏正常的 `v46_20`):
 *   改名行被自动滚到视口顶部，而置顶的分组头（28 dp 不透明 `bgBase`）正画在行
 *   的上面——40 dp 的行只剩 12 dp 露在外面，13 sp 的文本行被盖住，uiautomator
 *   能读到节点、肉眼一个字没有。狭屏键盘弹起后视口只剩 40 dp floor、分组头早
 *   已退化成普通 item（[HomeRowLayout.groupHeaderSticky])，所以同一行在狭屏
 *   可见——差异不在字段本身，在盖住它的那层置顶头。
 * - P0 「改名提交/删除后『今天』整组行消失」(`v46_07/15/22/39`，冷启动恢复
 *   `v46_24/41`): 乐观镜像的回收谓词写反了（server 没追认的丢、已追认/已删
 *   的留），加上编辑滚屏以整份 `sections` 实例为 key——30 s 时钟滴答重建一份
 *   内容相同的列表就 yank 一次视口。
 * - P2 「极窄下其余行仍全消失」(`v46_20/21`): 键盘弹起的 185 dp 卡上
 *   search(72) + list floor(40) + nav(72) 把预算吃光，视口只剩编辑行自身，
 *   其余行与分组标题被虚拟化掉出 DOM。
 *
 * 纯几何/纯状态能断言的部分直接断言数值；合成行为按本仓库惯例钉 source fact。
 */
class HomeListTripleV46Test {

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

    private val sessionsList = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeSessionsList.kt")
    private val homeScreen = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeScreen.kt")

    // ------------------------------------------------- P0-1 the covered editor

    @Test
    fun `wide viewport keeps the sticky header - the pinned 28dp covers the scrolled-to-top editor`() {
        // 1080x2424 @420 dpi 手机键盘弹起后的列表视口（≈250 dp，远高于 68 dp 的
        // stickiness 门限）：分组头保持置顶，自动滚到顶部的改名行正落在它下面。
        val wideViewport = 250.dp
        assertTrue(HomeRowLayout.groupHeaderSticky(wideViewport))
        assertEquals(28.dp, HomeRowLayout.editorOverlap(sticky = true))
        // 40 dp 的行只剩 12 dp 露在外面——13 sp 单行（≈16 dp 起）没有一行的
        // 位置，`v46_05/06/14` 的空白灰条。
        assertEquals(12.dp, HomeRowLayout.editorVisibleHeight(sticky = true))
        assertTrue(HomeRowLayout.editorVisibleHeight(true) < 16.dp)
    }

    @Test
    fun `narrow viewport already leaves the editor uncovered - the width difference`() {
        // 640x1500 @420 dpi 键盘弹起：视口被压到 40 dp floor，低于 header + row，
        // 分组头退化成普通 item——改名行无人遮盖（`v46_20` 文字可见的原因）。
        val narrowViewport = 40.dp
        assertFalse(HomeRowLayout.groupHeaderSticky(narrowViewport))
        assertEquals(0.dp, HomeRowLayout.editorOverlap(sticky = false))
        assertEquals(40.dp, HomeRowLayout.editorVisibleHeight(sticky = false))
    }

    @Test
    fun `the list unpins its headers while a rename editor is open`() {
        // 修复：置顶头在编辑期间一律让位，编辑行在任何视口高度都不被盖住。
        assertTrue(
            "stickyHeader must be gated on rowEditor == null or the pinned header covers the editing row",
            sessionsList.contains("rowEditor == null"),
        )
    }

    // ------------------------------------ P0-2 the rename mirror + the scroll

    @Test
    fun `a slow read must not un-rename an accepted row`() {
        val mirror = mapOf("s1" to "New")
        // PATCH 落库前发出的读（server 还是旧标题）：镜像必须留着等确认。
        val serverOld = mapOf("s1" to "Untitled")
        // 上一轮内联谓词的旧行为：恰好在这种读上把已接受的改名丢掉。
        val oldKept = mirror.filter { (id, title) -> serverOld[id] == null || serverOld[id] == title }
        assertTrue("the v45 inline predicate drops the accepted mirror on a slow read", oldKept.isEmpty())
        assertEquals(mirror, pruneRenamedTitles(mirror, serverOld))
    }

    @Test
    fun `a confirmed rename retires its mirror and a deleted session drops its dead one`() {
        assertEquals(
            emptyMap<String, String>(),
            pruneRenamedTitles(mapOf("s1" to "New"), mapOf("s1" to "New")),
        )
        assertEquals(
            emptyMap<String, String>(),
            pruneRenamedTitles(mapOf("gone" to "X"), mapOf("s1" to "Y")),
        )
    }

    @Test
    fun `the host retires mirrors through the helper on every catalogue read`() {
        assertTrue(homeScreen.contains("pruneRenamedTitles(renamedTitles"))
    }

    @Test
    fun `an accepted rename keeps its day group and leaves siblings alone`() {
        val s1 = session("s1", "Untitled", "/w/p1", at("2024-05-20T08:00:00Z"))
        val s2 = session("s2", "B", "/w/p1", at("2024-05-20T09:00:00Z"))
        val s3 = session("s3", "C", "/w/p2", at("2024-05-19T08:00:00Z"))
        val applied = applyRenamedTitles(listOf(s1, s2, s3), mapOf("s1" to "A-renamed"))
        assertEquals("A-renamed", applied[0].title)
        assertEquals(s1.updatedAt, applied[0].updatedAt)
        assertEquals(s1.directory, applied[0].directory)
        assertEquals(s2, applied[1])
        assertEquals(s3, applied[2])
        val sections = HomeSessions.sections(HomeSessions.rows(applied), now, zone)
        // rows() 按 updatedAt 倒序：s2(09:00) 在 s1(08:00) 前面。
        assertEquals(listOf("s2", "s1"), sections.first { it.group == HomeSessions.Group.TODAY }.rows.map { it.id })
        assertEquals(
            "A-renamed",
            sections.first { it.group == HomeSessions.Group.TODAY }.rows.first { it.id == "s1" }.title,
        )
        assertEquals(listOf("s3"), sections.first { it.group == HomeSessions.Group.YESTERDAY }.rows.map { it.id })
    }

    @Test
    fun `removing one session never blanks its surviving group mates`() {
        val rows = HomeSessions.rows(
            listOf(
                session("s1", "A", "/w/p1", at("2024-05-20T08:00:00Z")),
                session("s2", "B", "/w/p1", at("2024-05-20T09:00:00Z")),
            ),
        )
        val afterDelete = HomeSessions.sections(rows.filterNot { it.id == "s1" }, now, zone)
        assertEquals(listOf("s2"), afterDelete.single().rows.map { it.id })
        assertEquals("B", afterDelete.single().rows.single().title)
    }

    @Test
    fun `the editor scroll is keyed on membership not on the sections instance`() {
        val rows = HomeSessions.rows(
            listOf(
                session("s1", "A", "/w/p1", at("2024-05-20T08:00:00Z")),
                session("s2", "B", "/w/p1", at("2024-05-20T09:00:00Z")),
            ),
        )
        val sections = HomeSessions.sections(rows, now, zone)
        // 标题改了（改名提交的那一帧）签名不变——提交不再 yank 视口。
        val retitled = HomeSessions.sections(
            rows.map { if (it.id == "s1") it.copy(title = "A-renamed") else it },
            now,
            zone,
        )
        assertEquals(homeSectionsSignature(sections), homeSectionsSignature(retitled))
        // 成员变了签名才变——编辑中远端增删行仍把编辑行滚回视口。
        val shrunk = HomeSessions.sections(rows.filterNot { it.id == "s2" }, now, zone)
        assertFalse(homeSectionsSignature(sections) == homeSectionsSignature(shrunk))
        assertTrue(sessionsList.contains("homeSectionsSignature(sections)"))
        assertTrue(sessionsList.contains("LaunchedEffect(editing, sectionsSig)"))
        assertFalse(
            "the scroll must not restart on every content-identical sections instance",
            sessionsList.contains("LaunchedEffect(editing, sections)"),
        )
    }

    // ------------------------------------------------------- P2 the viewport

    @Test
    fun `the keyboard-up 185dp card leaves no room for siblings while the nav stands`() {
        // 640x1500 @420 dpi（`v46_20/21`）：卡 185 dp，search 块 72 + nav 72，
        // 列表顶 pad 让位（v54 P1），行自己只剩 41 dp。
        val card = 185.dp
        assertEquals(72.dp, HomeRowLayout.utilityNavReserve(editing = false))
        assertEquals(41.dp, HomeRowLayout.mobileRowsViewport(card, 0.dp, editing = false))
        assertTrue(
            "41dp of rows viewport cannot hold header + editing row — siblings are virtualized out of the DOM",
            HomeRowLayout.mobileRowsViewport(card, 0.dp, editing = false) < HomeRowLayout.LIST_MIN_HEIGHT,
        )
    }

    @Test
    fun `stepping the nav aside while editing restores header plus rows`() {
        val card = 185.dp
        assertEquals(0.dp, HomeRowLayout.utilityNavReserve(editing = true))
        assertEquals(101.dp, HomeRowLayout.mobileRowsViewport(card, 0.dp, editing = true))
        assertTrue(
            HomeRowLayout.mobileRowsViewport(card, 0.dp, editing = true) >= HomeRowLayout.LIST_MIN_HEIGHT,
        )
    }

    @Test
    fun `the host tracks the editor and yields it the mobile column`() {
        assertTrue(homeScreen.contains("onEditingChanged"))
        // Both sessions columns (desktop + mobile) report; the mobile one is
        // what the chrome below answers to.
        assertEquals(2, Regex("onEditingChanged = ").findAll(homeScreen).count())
        assertTrue(
            "the utility nav steps aside while a rename editor is open",
            homeScreen.contains("if (!sessionsEditing)"),
        )
        assertTrue(sessionsList.contains("onEditingChanged"))
    }
}
