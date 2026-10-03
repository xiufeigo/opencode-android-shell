package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.home.HomeProjectEdit
import com.xiufeigo.opencodeshell.ui.session.composer.AttachmentPolicy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The edit dialog's pure patch builder (`HomeProjectEdit`): upstream's
 * per-field blur saves (`project-model.ts` `saveName` / `saveIcon` /
 * `saveStartup`) folded into one `UpdatePayload` whose nulls are omitted from
 * the PATCH body. B-14 adds the image half of `saveIcon`
 * (`icon.override`, a `data:` URL).
 */
class HomeProjectEditModelTest {

    private val original = HomeProjectEdit.Original(
        projectID = "prj_1",
        name = "",
        color = null,
        startup = "",
        folderName = "repo",
    )

    @Test
    fun unchangedDraftProducesAnEmptyPatch() {
        val patch = HomeProjectEdit.patchFor(original, HomeProjectEdit.draftOf(original))
        assertTrue(patch.isEmpty)
        assertNull(patch.name)
        assertNull(patch.iconColor)
        assertNull(patch.iconOverride)
        assertNull(patch.startup)
    }

    // ---- icon.override (B-14) ---------------------------------------------

    @Test
    fun pickedImageIsSentAndUnchangedImageIsNot() {
        val image = AttachmentPolicy.dataUrl("image/png", byteArrayOf(1, 2, 3))
        val picked = HomeProjectEdit.patchFor(
            original,
            HomeProjectEdit.Draft(name = "", color = null, startup = "", iconOverride = image),
        )
        assertEquals(image, picked.iconOverride)
        assertFalse(picked.isEmpty)
        // Saving again without touching the image omits the key: the server
        // keeps the stored override (`ProjectIcon.toJson` drops nulls).
        val unchanged = HomeProjectEdit.patchFor(
            original.copy(iconOverride = image),
            HomeProjectEdit.Draft(name = "", color = null, startup = "", iconOverride = image),
        )
        assertNull(unchanged.iconOverride)
        assertTrue(unchanged.isEmpty)
    }

    @Test
    fun clearingTheImageSendsAnEmptyStringLikeUpstream() {
        val image = AttachmentPolicy.dataUrl("image/png", byteArrayOf(9))
        // Upstream `saveIcon(color, override)` → `override ?? ""`.
        val cleared = HomeProjectEdit.patchFor(
            original.copy(iconOverride = image),
            HomeProjectEdit.Draft(name = "", color = null, startup = "", iconOverride = null),
        )
        assertEquals("", cleared.iconOverride)
        // Nothing stored and nothing picked is not a clear.
        val untouched = HomeProjectEdit.patchFor(
            original,
            HomeProjectEdit.Draft(name = "", color = null, startup = ""),
        )
        assertNull(untouched.iconOverride)
    }

    @Test
    fun draftPrefillsTheStoredImage() {
        val image = AttachmentPolicy.dataUrl("image/png", byteArrayOf(4, 5))
        val draft = HomeProjectEdit.draftOf(original.copy(iconOverride = image))
        assertEquals(image, draft.iconOverride)
        assertNull(HomeProjectEdit.draftOf(original).iconOverride)
        assertTrue(HomeProjectEdit.patchFor(original.copy(iconOverride = image), draft).isEmpty)
    }

    @Test
    fun originalFromJsonReadsTheImageOverrideNullSafely() {
        val image = AttachmentPolicy.dataUrl("image/jpeg", byteArrayOf(7))
        val stored = HomeProjectEdit.originalFromJson(
            "prj_1",
            "/home/u/repo",
            JSONObject("""{"id":"prj_1","icon":{"color":"cyan","override":"$image"}}"""),
        )
        assertEquals(image, stored.iconOverride)
        assertEquals("cyan", stored.color)
        // A JSON null must not become the literal "null" image (the org.json trap).
        val nullOverride = HomeProjectEdit.originalFromJson(
            "prj_1",
            "/home/u/repo",
            JSONObject("""{"id":"prj_1","icon":{"color":"cyan","override":null}}"""),
        )
        assertNull(nullOverride.iconOverride)
        // `url`-only icons (no color, no override) keep both null.
        val urlOnly = HomeProjectEdit.originalFromJson(
            "prj_1",
            "/home/u/repo",
            JSONObject("""{"id":"prj_1","icon":{"url":"https://x/i.png"}}"""),
        )
        assertNull(urlOnly.iconOverride)
        assertNull(urlOnly.color)
    }

    @Test
    fun nameEqualToFolderNameClearsAndAnythingElseIsSent() {
        // Upstream `saveName`: trim; a changed name is sent as typed.
        val typed = HomeProjectEdit.patchFor(
            original,
            HomeProjectEdit.Draft(name = "  alpha  ", color = null, startup = ""),
        )
        assertEquals("alpha", typed.name)
        // Typing the folder name is "no custom name" → send "" only when the
        // saved name is not already empty.
        val backToFolder = HomeProjectEdit.patchFor(
            original.copy(name = "alpha"),
            HomeProjectEdit.Draft(name = "repo", color = null, startup = ""),
        )
        assertEquals("", backToFolder.name)
        val alreadyEmpty = HomeProjectEdit.patchFor(
            original,
            HomeProjectEdit.Draft(name = "repo", color = null, startup = ""),
        )
        assertNull(alreadyEmpty.name)
    }

    @Test
    fun colorChangesSendIconColorAndClearsWithEmptyString() {
        val painted = HomeProjectEdit.patchFor(
            original,
            HomeProjectEdit.Draft(name = "", color = "cyan", startup = ""),
        )
        assertEquals("cyan", painted.iconColor)
        val cleared = HomeProjectEdit.patchFor(
            original.copy(color = "cyan"),
            HomeProjectEdit.Draft(name = "", color = null, startup = ""),
        )
        assertEquals("", cleared.iconColor)
        val same = HomeProjectEdit.patchFor(
            original.copy(color = "cyan"),
            HomeProjectEdit.Draft(name = "", color = "cyan", startup = ""),
        )
        assertNull(same.iconColor)
    }

    @Test
    fun startupIsTrimmedAndEmptyClears() {
        val typed = HomeProjectEdit.patchFor(
            original,
            HomeProjectEdit.Draft(name = "", color = null, startup = "  bun install \n"),
        )
        assertEquals("bun install", typed.startup)
        val cleared = HomeProjectEdit.patchFor(
            original.copy(startup = "bun install"),
            HomeProjectEdit.Draft(name = "", color = null, startup = "   "),
        )
        assertEquals("", cleared.startup)
    }

    @Test
    fun serverProjectIdRejectsBlankAndTheGlobalPseudoProject() {
        assertEquals("prj_1", HomeProjectEdit.serverProjectID("prj_1"))
        assertNull(HomeProjectEdit.serverProjectID(""))
        assertNull(HomeProjectEdit.serverProjectID("   "))
        assertNull(HomeProjectEdit.serverProjectID(null))
        // Upstream `project-model.ts:37` skips `id === "global"` (client-local meta).
        assertNull(HomeProjectEdit.serverProjectID("global"))
    }

    @Test
    fun projectIdForMatchesWorktreesCaseAndSeparatorInsensitively() {
        val projects = listOf(
            HomeProjectEdit.ServerProject(id = "prj_a", worktree = "C:\\repo\\app"),
            HomeProjectEdit.ServerProject(id = "prj_b", worktree = "/home/u/other"),
        )
        assertEquals("prj_a", HomeProjectEdit.projectIdFor("c:/repo/app", projects))
        assertEquals("prj_b", HomeProjectEdit.projectIdFor("/home/u/other/", projects))
        assertNull(HomeProjectEdit.projectIdFor("/home/u/nowhere", projects))
        assertNull(HomeProjectEdit.projectIdFor("", projects))
    }

    @Test
    fun originalFromJsonReadsNameIconColorAndStart() {
        val raw = JSONObject(
            """{"id":"prj_1","name":"Alpha","icon":{"color":"cyan"},"commands":{"start":"bun install"}}""",
        )
        val parsed = HomeProjectEdit.originalFromJson("prj_1", "/home/u/repo", raw)
        assertEquals("prj_1", parsed.projectID)
        assertEquals("Alpha", parsed.name)
        assertEquals("cyan", parsed.color)
        assertEquals("bun install", parsed.startup)
        assertEquals("repo", parsed.folderName)
    }

    @Test
    fun originalFromJsonDefaultsMatchUpstreamOptionals() {
        val parsed = HomeProjectEdit.originalFromJson("global", "/home/u/repo", JSONObject("""{}"""))
        assertNull(parsed.projectID)
        assertEquals("", parsed.name)
        assertNull(parsed.color)
        assertEquals("", parsed.startup)
    }

    @Test
    fun draftPrefillsNameWithTheFolderNameWhenUnset() {
        val draft = HomeProjectEdit.draftOf(original)
        assertEquals("repo", draft.name)
        assertEquals("", draft.startup)
        // Prefilling the folder name over an unset name is not a change.
        assertTrue(HomeProjectEdit.patchFor(original, draft).isEmpty)
    }
}
