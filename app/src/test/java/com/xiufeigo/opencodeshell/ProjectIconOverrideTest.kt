package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.data.HomeProjectRecords
import com.xiufeigo.opencodeshell.ui.home.HomeProjectEdit
import com.xiufeigo.opencodeshell.ui.home.HomeProjects
import com.xiufeigo.opencodeshell.ui.home.ProjectIconImage
import com.xiufeigo.opencodeshell.ui.session.composer.AttachmentPolicy
import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.v2ProjectRow
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B-14: the project icon **image** (`icon.override`) — the half of upstream
 * `SettingsProjectGeneral` this shell previously documented as desktop-only.
 * `Project.Icon` carries `override` on the wire (`schema/src/project.ts:20-25`)
 * and `project.update` persists it (`core/src/project.ts:216`), so the shell
 * stores the same `data:` URL upstream's `FileReader` produces.
 *
 * Two halves are pinned here: the pure data-URL contract
 * ([ProjectIconImage.bytesOf] and the catalogue/row plumbing that carries the
 * value), and the wiring that cannot be called from a JVM test (the SAF picker,
 * the dialog's preview/clear controls, the PATCH body).
 */
class ProjectIconOverrideTest {

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText()
    }

    private val image = AttachmentPolicy.dataUrl("image/png", byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))

    // ---- the data URL contract ---------------------------------------------

    @Test
    fun `an image data url decodes back to its bytes`() {
        assertEquals("image/png", ProjectIconImage.mimeOf(image))
        assertArrayEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47), ProjectIconImage.bytesOf(image))
    }

    @Test
    fun `a non-image, empty or malformed override decodes to nothing`() {
        assertNull(ProjectIconImage.bytesOf(null))
        assertNull(ProjectIconImage.bytesOf(""))
        // `org.json` answers the literal "null" for a JSON null.
        assertNull(ProjectIconImage.bytesOf("null"))
        // An `icon.url` value is not a data URL (and the shell does not fetch it).
        assertNull(ProjectIconImage.bytesOf("https://example.com/i.png"))
        assertNull(ProjectIconImage.bytesOf("data:application/octet-stream;base64,AAEC"))
        assertNull(ProjectIconImage.bytesOf("data:image/png;base64,"))
        assertNull(ProjectIconImage.bytesOf("data:image/png;base64,!!not-base64!!"))
        assertNull(ProjectIconImage.mimeOf("data:text/plain;base64,AAEC"))
        assertNull(ProjectIconImage.mimeOf(null))
    }

    // ---- the catalogue and the row carry it --------------------------------

    @Test
    fun `the v1-shaped catalogue row parses both icon halves`() {
        val row = OpenCodeClient.Project.fromJson(
            JSONObject("""{"id":"p","canonical":"/w","icon":{"color":"mint","override":"$image"}}"""),
        )
        assertEquals("mint", row!!.iconColor)
        assertEquals(image, row.iconOverride)
        // A JSON null must not become the literal "null" image.
        assertNull(
            OpenCodeClient.Project.fromJson(
                JSONObject("""{"id":"p","canonical":"/w","icon":{"override":null}}"""),
            )!!.iconOverride,
        )
        assertNull(OpenCodeClient.Project.fromJson(JSONObject("""{"id":"p","canonical":"/w"}"""))!!.iconOverride)
    }

    @Test
    fun `the v2 catalogue row parses both icon halves`() {
        val row = v2ProjectRow(
            V2Api.V2Project.fromJson(
                JSONObject("""{"id":"p","canonical":"/w","icon":{"color":"orange","override":"$image"}}"""),
            ),
        )
        assertEquals("orange", row!!.iconColor)
        assertEquals(image, row.iconOverride)
        // No icon object at all (the 2026-09-22 fixture shape) stays null.
        val bare = v2ProjectRow(V2Api.V2Project.fromJson(JSONObject("""{"id":"p","canonical":"/w"}""")))
        assertNull(bare!!.iconColor)
        assertNull(bare.iconOverride)
    }

    @Test
    fun `merge and openRows put the override on the rendered row`() {
        val catalogue = OpenCodeClient.Project(
            id = "p",
            worktree = "/work/api",
            vcs = "git",
            iconColor = "mint",
            iconOverride = image,
        )
        val known = HomeProjects.merge(projects = listOf(catalogue), sessions = emptyList())
        assertEquals(image, known[0].iconOverride)
        val rows = HomeProjects.openRows(
            opened = listOf(HomeProjectRecords.Project(worktree = "/work/api")),
            known = known,
        )
        assertEquals(image, rows[0].iconOverride)
        assertEquals("mint", rows[0].iconColor)
        // A project without an image keeps null (the avatar falls back to color).
        assertNull(HomeProjects.closedRows(listOf("/work/api"))[0].iconOverride)
    }

    // ---- the wiring --------------------------------------------------------

    @Test
    fun `the editor picks one image through SAF and renders the live preview`() {
        val dialog = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeProjectEditDialog.kt")
        // Upstream's hidden `<input type="file" accept="image/*">` → one SAF pick.
        assertTrue(dialog.contains("ActivityResultContracts.GetContent()"))
        assertTrue(dialog.contains("""pickImage.launch("image/*")"""))
        assertTrue(dialog.contains("ProjectIconImage.read(context, uri)"))
        assertTrue(dialog.contains("draft = draft?.copy(iconOverride = dataUrl)"))
        // The preview + the two controls, and upstream's `<Show when={!iconOverride}>`
        // around the color swatches.
        assertTrue(dialog.contains("ProjectIconView("))
        assertTrue(dialog.contains("R.string.oc_home_project_edit_icon_pick"))
        assertTrue(dialog.contains("R.string.oc_home_project_edit_icon_clear"))
        assertTrue(dialog.contains("if (draft.iconOverride == null) {"))
        assertTrue(dialog.contains("R.string.dialog_project_edit_icon_recommended"))
    }

    @Test
    fun `the avatar draws the image before the color`() {
        val list = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeProjectsList.kt")
        assertTrue(list.contains("override = project.iconOverride"))
        assertTrue(list.contains("iconColor = project.iconColor"))
        val view = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/ProjectIconImage.kt")
        // Image first, then the tinted surface with the initial.
        assertTrue(view.contains("ProjectIconImage.bytesOf(override)"))
        assertTrue(view.contains("HomeProjects.iconColorHex(iconColor)"))
        assertTrue(view.contains("ContentScale.Crop"))
    }

    @Test
    fun `the PATCH sends both icon halves with only the changed one`() {
        val api = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeProjectApi.kt")
        assertTrue(api.contains("ProjectIcon(color = patch.iconColor, `override` = patch.iconOverride)"))
        val model = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeProjectEdit.kt")
        // The `org.json` trap: a JSON-null override must not read as "null".
        assertTrue(model.contains("""icon.isNull("override")"""))
        assertTrue(model.contains("iconOverride = if (draft.iconOverride != original.iconOverride)"))
    }

    @Test
    fun `the new copy exists in all three locales`() {
        val keys = listOf(
            "oc_home_project_edit_icon_pick",
            "oc_home_project_edit_icon_clear",
            "oc_home_project_edit_icon_failed",
        )
        for (relative in listOf(
            "src/main/res/values/strings.xml",
            "src/main/res/values-zh-rCN/strings.xml",
            "src/main/res/values-zh-rTW/strings.xml",
        )) {
            val body = source(relative)
            for (key in keys) assertTrue("$relative is missing $key", body.contains("name=\"$key\""))
        }
    }

    @Test
    fun `the editor's patch is still an omitted-key patch`() {
        // A regression guard for the model: adding icon.override must not make
        // an untouched draft send anything.
        val original = HomeProjectEdit.Original(
            projectID = "prj_1",
            name = "",
            color = null,
            startup = "",
            folderName = "repo",
        )
        assertTrue(HomeProjectEdit.patchFor(original, HomeProjectEdit.draftOf(original)).isEmpty)
    }
}
