package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.home.HomeProjects
import com.xiufeigo.opencodeshell.ui.home.HomeSessions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Home's projects column over `GET /project` ([HomeProjects.merge]), the
 * add-project browser's parent walk ([HomeProjects.parentOf]), the project
 * display name over Windows/WSL/UNC paths ([HomeSessions.projectName]) and the
 * `icon.color` → avatar colour mapping ([HomeProjects.iconColorHex]).
 */
class HomeProjectsMergeTest {

    private fun session(id: String, directory: String?, updatedAt: Long) =
        OpenCodeClient.Session(id = id, title = id, directory = directory, updatedAt = updatedAt)

    private fun project(worktree: String, vcs: String? = "git", iconColor: String? = null) =
        OpenCodeClient.Project(id = worktree, worktree = worktree, vcs = vcs, iconColor = iconColor)

    // ---- merge -------------------------------------------------------------

    @Test
    fun `server projects come first in server order`() {
        val merged = HomeProjects.merge(
            projects = listOf(project("/work/api"), project("/work/site")),
            sessions = emptyList(),
        )
        assertEquals(listOf("/work/api", "/work/site"), merged.map { it.path })
        assertEquals(listOf("api", "site"), merged.map { it.name })
        assertEquals(listOf(0, 0), merged.map { it.sessionCount })
    }

    @Test
    fun `session counts attach to their project`() {
        val merged = HomeProjects.merge(
            projects = listOf(project("/work/api")),
            sessions = listOf(
                session("a", "/work/api", 100L),
                session("b", "/work/api", 90L),
                session("c", "/work/site", 80L),
            ),
        )
        assertEquals(2, merged.first { it.path == "/work/api" }.sessionCount)
    }

    @Test
    fun `session directories missing from the project list are appended MRU first`() {
        val merged = HomeProjects.merge(
            projects = listOf(project("/work/api")),
            sessions = listOf(
                session("a", "/work/plain", 100L),
                session("b", "/work/other", 200L),
            ),
        )
        // Non-git directories never become server projects, but must stay
        // reachable; they follow the server list, most recently used first.
        assertEquals(listOf("/work/api", "/work/other", "/work/plain"), merged.map { it.path })
    }

    @Test
    fun `trailing separator duplicates collapse onto the server project`() {
        val merged = HomeProjects.merge(
            projects = listOf(project("C:\\src\\app")),
            sessions = listOf(session("a", "C:\\src\\app\\", 100L)),
        )
        assertEquals(1, merged.size)
        assertEquals("C:\\src\\app", merged[0].path)
        assertEquals(1, merged[0].sessionCount)
    }

    @Test
    fun `merge keeps vcs and icon color`() {
        val merged = HomeProjects.merge(
            projects = listOf(project("/work/api", vcs = "git", iconColor = "orange")),
            sessions = emptyList(),
        )
        assertEquals("git", merged[0].vcs)
        assertEquals("orange", merged[0].iconColor)
    }

    // ---- projectName over Windows / WSL / UNC paths ------------------------

    @Test
    fun `projectName takes the last segment of a Windows path`() {
        assertEquals("app", HomeSessions.projectName("C:\\src\\app"))
        assertEquals("app", HomeSessions.projectName("C:/src/app"))
        assertEquals("app", HomeSessions.projectName("C:\\src\\app\\"))
    }

    @Test
    fun `projectName takes the last segment of a WSL UNC path`() {
        assertEquals("proj", HomeSessions.projectName("\\\\wsl.localhost\\Ubuntu\\home\\me\\proj"))
        assertEquals("Ubuntu", HomeSessions.projectName("\\\\wsl.localhost\\Ubuntu"))
        assertEquals("proj", HomeSessions.projectName("\\\\wsl$\\Ubuntu\\home\\me\\proj\\"))
    }

    @Test
    fun `projectName has no name for roots`() {
        assertNull(HomeSessions.projectName("/"))
        assertNull(HomeSessions.projectName("C:\\"))
        assertNull(HomeSessions.projectName("C:/"))
        assertNull(HomeSessions.projectName(null))
        assertNull(HomeSessions.projectName("  "))
    }

    // ---- parentOf ----------------------------------------------------------

    @Test
    fun `parentOf walks POSIX and Windows paths`() {
        assertEquals("/home/me", HomeProjects.parentOf("/home/me/proj"))
        assertEquals("/", HomeProjects.parentOf("/home"))
        assertEquals("C:\\src", HomeProjects.parentOf("C:\\src\\app"))
        // The drive root keeps its browsable form.
        assertEquals("C:\\", HomeProjects.parentOf("C:\\src"))
    }

    @Test
    fun `parentOf walks UNC paths and stops at the share root`() {
        assertEquals(
            "\\\\wsl.localhost\\Ubuntu\\home",
            HomeProjects.parentOf("\\\\wsl.localhost\\Ubuntu\\home\\me"),
        )
        assertEquals("\\\\wsl.localhost\\Ubuntu", HomeProjects.parentOf("\\\\wsl.localhost\\Ubuntu\\home"))
        assertNull(HomeProjects.parentOf("\\\\wsl.localhost\\Ubuntu"))
        assertNull(HomeProjects.parentOf("\\\\wsl.localhost"))
    }

    @Test
    fun `parentOf is null at roots`() {
        assertNull(HomeProjects.parentOf("/"))
        assertNull(HomeProjects.parentOf("C:\\"))
        assertNull(HomeProjects.parentOf("C:/"))
        assertNull(HomeProjects.parentOf(""))
    }

    // ---- iconColorHex ------------------------------------------------------

    @Test
    fun `iconColorHex maps upstream variants`() {
        assertEquals(0xFFEE7330, HomeProjects.iconColorHex("orange"))
        assertEquals(0xFFE4429E, HomeProjects.iconColorHex("pink"))
        // `getProjectAvatarVariant`: mint → cyan, lime → green.
        assertEquals(0xFF0096B8, HomeProjects.iconColorHex("mint"))
        assertEquals(0xFF2EAF5A, HomeProjects.iconColorHex("lime"))
        assertEquals(0xFF5C5C5C, HomeProjects.iconColorHex("gray"))
    }

    @Test
    fun `iconColorHex passes unknown names to no tint`() {
        assertNull(HomeProjects.iconColorHex(null))
        assertNull(HomeProjects.iconColorHex(""))
        assertNull(HomeProjects.iconColorHex("chartreuse"))
    }
}
