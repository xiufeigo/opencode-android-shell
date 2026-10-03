package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.data.HomeProjectRecords
import com.xiufeigo.opencodeshell.ui.home.HomeProjectEdit
import com.xiufeigo.opencodeshell.ui.home.HomeProjects
import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.v2ProjectRow
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B-14 follow-up (device run tB_14k/14m): saving a project **icon** left the row
 * on its letter avatar until the app was force-stopped, while `GET /api/project`
 * already carried the new `icon.override`.
 *
 * Why the name refreshed and the icon did not: a saved name has a client-local
 * mirror (`HomeProjectRecords.setName` → `HomeProjects.displayName`'s
 * `localName`), so the row renames in the same frame; the avatar had no such
 * mirror — [HomeProjects.openRows] took `iconOverride` from the catalogue row
 * alone — and Home opens no SSE (M1 contract), so the `project.updated`
 * revision that would re-drive Home's catalogue read never arrives while the
 * dialog is up. The save's own re-read was names-only.
 *
 * v97 narrowed this: the record now *does* carry an `iconOverride`, but only
 * for a worktree the server has no project id for (upstream's local-meta
 * branch). A registered project's avatar is still the server's to draw, which
 * is what the pins below hold.
 *
 * Pinned here: for a project the server knows, the catalogue really is where
 * the avatar comes from (the local record's own copy must not shadow it), and
 * the save now re-reads the rows (the same re-read the name path already ran).
 */
class HomeProjectIconRefreshTest {

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText().replace("\r\n", "\n")
    }

    private val image = "data:image/png;base64,iVBORw0KGgo="

    private val home by lazy {
        source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeScreen.kt")
    }

    /** The edit dialog's `onSaved` host block. */
    private fun saved(): String =
        home.substringAfter("onSaved = { name ->").substringBefore("val onRefreshWorktrees")

    @Test
    fun `the avatar's override comes from the catalogue row for a registered project`() {
        // The two halves the refresh depends on: the catalogue parses
        // `icon.override`, and `openRows` puts it on the rendered row.
        val row = v2ProjectRow(
            V2Api.V2Project.fromJson(
                JSONObject("""{"id":"prj_1","canonical":"/work/api","icon":{"override":"$image","color":"mint"}}"""),
            ),
        )!!
        assertEquals(image, row.iconOverride)

        val known = HomeProjects.merge(projects = listOf(row), sessions = emptyList())
        val rows = HomeProjects.openRows(
            opened = HomeProjectRecords.open(HomeProjectRecords.State(), "/work/api").list,
            known = known,
        )
        assertEquals(image, rows[0].iconOverride)

        // v97: the client-local record DOES carry an `iconOverride` now, but
        // only for a worktree the server has no project id for — upstream's
        // `sync.project.meta` branch (`project-model.ts:41-46`), read back
        // through `runtime.tsx:151`'s rule that it covers the server's only
        // when there is no server metadata. A **registered** project is owned
        // by the server, so a local copy beside it must never win: this is the
        // half of the icon path the save still has to refresh from the
        // catalogue.
        val store = source("src/main/java/com/xiufeigo/opencodeshell/data/HomeProjectStore.kt")
        assertTrue("the local meta write exists", store.contains("fun setMeta(directory: String, patch:"))
        val shadowed = HomeProjects.openRows(
            opened = listOf(
                HomeProjectRecords.Project("/work/api", iconOverride = "data:image/png;base64,LOCAL"),
            ),
            known = known,
        )
        assertEquals(image, shadowed[0].iconOverride)
        assertFalse(
            "…and it is the server project id that decides, not a match",
            HomeProjectEdit.localMetaApplies(known[0].serverID),
        )
        assertTrue(HomeProjectEdit.localMetaApplies(null))
    }

    @Test
    fun `a successful save re-reads the project rows`() {
        val body = saved()
        // The name half that already worked...
        assertTrue(body.contains("serverNames = withContext(Dispatchers.IO) { projectApi.loadNames() }"))
        // ...plus the rows the avatar is drawn from.
        assertTrue(body.contains("chatViewModel?.listProjects { list -> if (list != null) serverProjects = list }"))
    }

    @Test
    fun `the refresh never blanks a live catalogue`() {
        // A failed read answers null; adopting it would strip every row's
        // vcs/icon for no reason (the sessions guard's rule on Home).
        assertTrue(saved().contains("if (list != null) serverProjects = list"))
    }

    @Test
    fun `the refresh stays off the effect keys`() {
        // `HomeColdStartTest` pins the effect's keys: the save's re-read is a
        // callback, not a new key that would re-drive the whole read.
        assertTrue(home.contains("LaunchedEffect(chatViewModel, projectsRevision, sessionsRevision)"))
        assertTrue(saved().contains("projectActionScope.launch {"))
    }
}
