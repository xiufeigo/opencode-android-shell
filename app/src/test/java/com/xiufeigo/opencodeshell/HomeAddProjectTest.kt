package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.data.HomeProjectRecords
import com.xiufeigo.opencodeshell.ui.home.HomeProjects
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 添加项目 (Home's folder+ → `DirectoryPickerDialog` → 「选择此目录」): the picked
 * directory must become a row, and the rows already there must keep everything
 * they had.
 *
 * The v30 device run (`research/v30_verify/tA_notify_ws/tA_16…tA_23`) saw the
 * opposite: the dialog closed, the column stayed at one project, no request was
 * sent and nothing was logged. The picker's confirm never ran — the body was
 * `heightIn(min = 200.dp, max = 320.dp)`, so it followed the listing (320 dp for
 * `/home/xiufeigo`, 200 dp for the 3-entry directory the user then picked) and
 * slid 「选择此目录」 by 146 px between two frames. A tap aimed from the previous
 * frame landed *below* the dialog, on the host's scrim, whose tap-outside
 * handler closed the picker: `onSelect` never fired, so there was no request to
 * send and nothing to log.
 *
 * Pinned here: the add's pure half (it cannot fail silently — no swallowing, no
 * catalogue dependency) and the two source contracts that keep the tap on the
 * button (one fixed body height) and finish upstream's add (the registration
 * probe + the catalogue re-read).
 */
class HomeAddProjectTest {

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText().replace("\r\n", "\n")
    }

    private val picker by lazy {
        source("src/main/java/com/xiufeigo/opencodeshell/ui/home/DirectoryPickerDialog.kt")
    }
    private val home by lazy {
        source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeScreen.kt")
    }
    private val api by lazy {
        source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeProjectApi.kt")
    }

    // ---- the pure half: the add lands, and nothing else moves --------------

    private fun project(worktree: String, vcs: String? = "git") =
        OpenCodeClient.Project(id = worktree, worktree = worktree, vcs = vcs, iconColor = null)

    @Test
    fun `the picked directory becomes the first row`() {
        val opened = HomeProjectRecords.open(
            HomeProjectRecords.open(HomeProjectRecords.State(), "/home/xiufeigo/v29projA"),
            "/home/xiufeigo/v30projA",
        )

        val rows = HomeProjects.openRows(
            opened = opened.list,
            known = HomeProjects.merge(
                projects = listOf(project("/home/xiufeigo/v29projA")),
                sessions = emptyList(),
            ),
        )

        assertEquals(
            listOf("/home/xiufeigo/v30projA", "/home/xiufeigo/v29projA"),
            rows.map { it.path },
        )
        // The new row is not in the catalogue yet: it renders with its folder
        // name and no tint instead of being dropped (upstream's `LocalProject`
        // is the client-local record, `GET /project` only decorates).
        assertEquals("v30projA", rows[0].name)
        assertNull(rows[0].vcs)
        // The project that was already open is untouched — same path, same
        // catalogue metadata, same order.
        assertEquals("/home/xiufeigo/v29projA", rows[1].path)
        assertEquals("git", rows[1].vcs)
    }

    @Test
    fun `the add touches the mru without disturbing the closed stack`() {
        val before = HomeProjectRecords.close(
            HomeProjectRecords.open(HomeProjectRecords.State(), "/work/other"),
            "/work/other",
        )
        val after = HomeProjectRecords.touch(
            HomeProjectRecords.open(before, "/work/added"),
            "/work/added",
        )

        assertEquals(listOf("/work/added"), after.list.map { it.worktree })
        assertEquals("/work/added", after.lastProject)
        // Closing a project is what fills the stack; adding never empties it.
        assertEquals(listOf("/work/other"), after.recentlyClosed)
    }

    @Test
    fun `a directory the user closed before is taken out of the tombstone by the add`() {
        val closed = HomeProjectRecords.close(HomeProjectRecords.State(), "/work/reopened")
        val added = HomeProjectRecords.open(closed, "/work/reopened")

        // `openRows` hides everything the closed stack still holds, so an add
        // of a previously closed directory has to clear its tombstone or the
        // row would never render.
        assertTrue(added.recentlyClosed.isEmpty())
        assertEquals(1, HomeProjects.openRows(opened = added.list, known = emptyList()).size)
    }

    // ---- the picker's confirm must not move out from under the tap --------

    @Test
    fun `the browser body has one fixed height for both of its states`() {
        // One height *expression*, taken verbatim by the list and by the
        // pending-path view: the footer under them cannot move between frames.
        // (The viewport-shaped rewrite: the card is capped at `cardMax` and the
        // body is the elastic `weight(1f)` slot between `LIST_MIN_HEIGHT` and
        // `bodyMax` — same in both states, so still zero movement.)
        assertTrue(
            "the body needs one height expression",
            picker.contains("private val LIST_MIN_HEIGHT = 96.dp"),
        )
        assertEquals(
            "the list and the confirm state must share it",
            2,
            Regex("""\.weight\(1f\)\s*\n\s*\.heightIn\(min = LIST_MIN_HEIGHT, max = bodyMax\)""")
                .findAll(picker).count(),
        )
        // ...and the two states must not differ anywhere in their own height
        // modifiers: any extra per-state height is what let the footer slide
        // (device run: 146 px between `/home/xiufeigo` and a 3-entry directory).
        assertFalse(picker.contains("LIST_MAX_HEIGHT"))
        assertEquals(
            "both body states are the only weight(1f) slots in the body",
            2,
            Regex("""\.weight\(1f\)""").findAll(picker.substringAfter("val confirming = pendingConfirm").substringBefore("// 「选择此目录」")).count(),
        )
    }

    @Test
    fun `the confirm still arms before it submits`() {
        // The two-tap state machine is deliberate (P1) and must survive the
        // height fix: the first tap only arms, the second submits the armed path.
        // (The pure half lives in `MoveConfirmStepTest` / `DirectoryPickerConfirmTest`.)
        // v47: the armed path is the resolved typed target (`pickerArmTarget`),
        // never the browsed `currentPath` — typing a child while browsing its
        // parent used to freeze the parent on the confirm card and submit it.
        assertTrue(picker.contains("pickerArmTarget(pathInput, currentPath, browseRoot, homeTarget)"))
        assertTrue(picker.contains("when (val step = confirmStep(pendingConfirm, target))"))
        assertTrue(picker.contains("is DirectoryConfirm.Submit -> {"))
        assertTrue(picker.contains("onSelect(step.path)"))
        assertTrue(picker.contains("is DirectoryConfirm.Armed -> pendingConfirm = step.path"))
        // A divergent typed target becomes the browsed path first, so the listing,
        // the failed-read gate and later relative inputs follow what the card shows.
        // v54 P1: it now does so *without* arming — the confirm is reached only
        // through a directory the server has actually listed (`pickerCanConfirm`
        // against `verifiedPath`), so a hand-typed path is browsed first.
        assertTrue(picker.contains("if (target.isNotEmpty() && target != currentPath) navigate(target)"))
        assertTrue(picker.contains("if (!pickerCanConfirm(target, atDrives, verifiedPath))"))
    }

    // ---- the add's server half (upstream `home.project.add`) --------------

    @Test
    fun `the pick's onSelect closes the dialog and opens the project`() {
        val body = home.substringAfter("val onAddProject: () -> Unit = {")
            .substringBefore("// Row-menu 「Edit project…」")
        assertTrue(body.contains("controller.close()"))
        assertTrue(body.contains("openProject(directory)"))
    }

    @Test
    fun `the add registers the directory and re-reads the catalogue off the main thread`() {
        val body = home.substringAfter("val onAddProject: () -> Unit = {")
            .substringBefore("// Row-menu 「Edit project…」")
        // `home/model.ts:101-120`: `api.file.list` + `api.location.get` for the
        // directory that was just opened, so `GET /api/project` answers it.
        assertTrue(body.contains("projectActionScope.launch {"))
        assertTrue(body.contains("withContext(Dispatchers.IO) { projectApi.register(directory) }"))
        // The two reads the new row is decorated from.
        assertTrue(body.contains("serverNames = withContext(Dispatchers.IO) { projectApi.loadNames() }"))
        assertTrue(body.contains("viewModel.listProjects { list -> if (list != null) serverProjects = list }"))
    }

    @Test
    fun `browsing the picker never re-scopes the next draft`() {
        val body = home.substringAfter("val onAddProject: () -> Unit = {")
            .substringBefore("// Row-menu 「Edit project…」")
        // Upstream's picker only *reads* a start directory and `projects.touch`
        // runs from the committed actions (`home/model.ts:49-52,101-120`).
        // Routing navigation into the MRU re-scoped the next draft: after a
        // picker session that added nothing, the new-session page opened on the
        // last directory browsed (「本地 / 无 Git」, no worktrees) instead of the
        // project the user had been working in.
        assertFalse(body.contains("onNavigate = "))
        // The committed add still marks the MRU, which is what the picker's
        // remembered start and `newSessionProject()` read.
        assertTrue(home.contains("homeProjectStore?.touch(directory)"))
    }

    @Test
    fun `a registration probe that cannot land never fails the add`() {
        // Upstream's `.catch(() => undefined)`: the row is already local, so the
        // probe reports and is ignored — never an exception, never a rollback.
        val body = api.substringAfter("fun register(worktree: String): Boolean {")
            .substringBefore("/** `POST /api/worktree/refresh`")
        assertTrue(body.contains("api.fsList(path = \".\", locationDirectory = directory)"))
        assertTrue(body.contains("api.location(directory = directory)"))
        assertTrue(body.contains("catch (e: CancellationException)"))
        assertTrue(body.contains("false"))
        // Never on the main thread: `register` is blocking OkHttp.
        assertTrue(api.contains("Blocking (OkHttp)"))
    }
}
