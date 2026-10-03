package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.home.dropDeletedSessions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v59 三个小尾巴：
 *
 * 1. Home 会话行长按菜单 ESC 关不掉（`research/v59_verify/5558`）——同一个
 *    `DropdownMenu` 缺陷，`WorkspaceSelector` v58 已修；这里钉住接线。
 * 2. 发送后转录区不立即显示消息（5558）——v2 线上没有 user message 帧，
 *    admit 之后补一次快照。
 * 3. 删除会话后列表行滞留数秒（5560）——删除的在途读取把行请回来，墓碑兜住。
 *
 * 纯函数断数值，接线按本仓库惯例钉 source fact。
 */
class ThreeTailsV59Test {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private val sessionsList = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeSessionsList.kt")
    private val homeScreen = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeScreen.kt")
    private val chatViewModel = source("src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt")

    private fun session(id: String, title: String = id) =
        OpenCodeClient.Session(id = id, title = title, directory = null, updatedAt = 0L)

    // ------------------------------------------------- 1 ESC 接线

    @Test
    fun `session row menu takes the same escape channel as the workspace menu`() {
        assertTrue("import", sessionsList.contains("import com.xiufeigo.opencodeshell.ui.components.ocOverlayEscape"))
        assertTrue("popup properties", sessionsList.contains("import androidx.compose.ui.window.PopupProperties"))
        assertTrue("focusable popup", sessionsList.contains("properties = PopupProperties(\n                focusable = true,"))
        assertTrue(
            "dismiss modifier",
            sessionsList.contains("modifier = Modifier.ocOverlayEscape(open = menuOpen) { onDismissMenu() }"),
        )
    }

    @Test
    fun `the escape modifier is on the row menu, not on some other dropdown`() {
        // 菜单就在 `onDismissRequest = onDismissMenu` 那一处；接线必须落在它同一个
        // `DropdownMenu(` 上，而不是文件里别的地方。
        val anchor = sessionsList.indexOf("onDismissRequest = onDismissMenu")
        assertTrue("row menu", anchor > 0)
        val window = sessionsList.substring(maxOf(0, anchor - 400), anchor + 1200)
        assertTrue("same popup", window.contains("properties = PopupProperties(\n                focusable = true,"))
        assertTrue("same modifier", window.contains("ocOverlayEscape(open = menuOpen) { onDismissMenu() }"))
    }

    // ------------------------------------------------- 2 admit 后补一次快照

    @Test
    fun `an admitted prompt re-reads the session so the user row lands`() {
        assertTrue(
            "post-admit refresh",
            chatViewModel.contains("if (sessionID == id && !admittedID.isNullOrBlank()) refreshSnapshot()"),
        )
        // 不能挂在节流版上：那一次读取是发送的确认，不能被上一条信号顺带吃掉。
        assertTrue("unthrottled", !chatViewModel.contains("!admittedID.isNullOrBlank()) refreshSnapshotThrottled()"))
    }

    // ------------------------------------------------- 3 删除墓碑

    @Test
    fun `no tombstone keeps the read as it is`() {
        val rows = listOf(session("s1"), session("s2"))
        assertEquals(rows, dropDeletedSessions(rows, emptySet()))
    }

    @Test
    fun `a deleted row never comes back through a stale read`() {
        val rows = listOf(session("s1"), session("s2"))
        assertEquals(listOf("s2"), dropDeletedSessions(rows, setOf("s1")).map { it.id })
    }

    @Test
    fun `the tombstone set is order preserving and leaves siblings untouched`() {
        val rows = listOf(session("s1", "A"), session("s2", "B"), session("s3", "C"))
        val kept = dropDeletedSessions(rows, setOf("s1", "s3", "never-existed"))
        assertEquals(listOf("s2"), kept.map { it.id })
        assertEquals("A", rows[0].title)
    }

    @Test
    fun `Home records the tombstone on the delete answer and applies it to the visible rows`() {
        assertTrue(
            "recorded",
            homeScreen.contains("deletedSessions = deletedSessions + target.id"),
        )
        assertTrue(
            "applied",
            homeScreen.contains(
                "dropDeletedSessions(titledSessions.filterNot(HomeSessions::isArchived), deletedSessions)",
            ),
        )
    }
}
