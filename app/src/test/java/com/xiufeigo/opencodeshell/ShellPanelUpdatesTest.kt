package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.settings.ParsedRelease
import com.xiufeigo.opencodeshell.ui.settings.ReleaseHighlight
import com.xiufeigo.opencodeshell.ui.settings.ReleaseHighlightMedia
import com.xiufeigo.opencodeshell.ui.settings.ReleaseNotesLaunch
import com.xiufeigo.opencodeshell.ui.settings.loadReleaseHighlights
import com.xiufeigo.opencodeshell.ui.settings.normalizeReleaseVersion
import com.xiufeigo.opencodeshell.ui.settings.parseChangelog
import com.xiufeigo.opencodeshell.ui.settings.parseHighlight
import com.xiufeigo.opencodeshell.ui.settings.parseHighlightMedia
import com.xiufeigo.opencodeshell.ui.settings.parseRelease
import com.xiufeigo.opencodeshell.ui.settings.releaseHighlightKey
import com.xiufeigo.opencodeshell.ui.settings.releaseNotesFeatureIndex
import com.xiufeigo.opencodeshell.ui.settings.releaseNotesLastIndex
import com.xiufeigo.opencodeshell.ui.settings.releaseNotesLaunch
import com.xiufeigo.opencodeshell.ui.settings.sliceReleaseHighlights
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The pure half of `shell/updates/{highlights,release-notes}.tsx`: changelog
 * projection, the version-changed launch decision and dialog paging. The
 * Composable half is pinned by source below (`*RenderTest` style).
 */
class ShellPanelUpdatesTest {

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText()
    }

    // ------------------------------------------------------------ normalize

    @Test
    fun normalizeReleaseVersionStripsOneLeadingV() {
        assertEquals("1.2.3", normalizeReleaseVersion("v1.2.3"))
        assertEquals("1.2.3", normalizeReleaseVersion("V1.2.3"))
        assertEquals("1.2.3", normalizeReleaseVersion(" 1.2.3 "))
        assertEquals("ersion", normalizeReleaseVersion("version"))
        assertNull(normalizeReleaseVersion(null))
        assertNull(normalizeReleaseVersion("   "))
    }

    // -------------------------------------------------------------- parsing

    @Test
    fun parseHighlightRequiresTitleAndDescription() {
        assertNull(parseHighlight(JSONObject().put("title", "t")))
        assertNull(parseHighlight("nope"))
        val hit = parseHighlight(
            JSONObject().put("title", " T ").put("shortDescription", " d "),
        )
        assertEquals(ReleaseHighlight(title = "T", description = "d"), hit)
    }

    @Test
    fun parseMediaAcceptsImageAndVideoOnly() {
        val image = parseHighlightMedia(
            JSONObject().put("type", "IMAGE").put("src", "https://x/y.png"),
            alt = "alt",
        )
        assertEquals(ReleaseHighlightMedia(type = "image", src = "https://x/y.png", alt = "alt"), image)
        assertEquals(
            "https://x/y.mp4",
            parseHighlightMedia(JSONObject().put("type", "video").put("url", "https://x/y.mp4"), alt = "a")?.src,
        )
        assertNull(parseHighlightMedia(JSONObject().put("type", "gif").put("src", "s"), alt = "a"))
        assertNull(parseHighlightMedia(JSONObject().put("type", "image"), alt = "a"))
    }

    @Test
    fun parseReleaseKeepsOnlyDesktopGroups() {
        val release = parseRelease(
            JSONObject()
                .put("tag", "v1.0.0")
                .put(
                    "highlights",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("source", "Desktop App")
                                .put("items", JSONArray().put(JSONObject().put("title", "A").put("description", "a"))),
                        )
                        .put(JSONObject().put("source", "cli").put("title", "B").put("description", "b"))
                        .put(JSONObject().put("title", "C").put("description", "c")),
                ),
        )
        assertEquals("v1.0.0", release?.tag)
        assertEquals(listOf("A"), release?.highlights?.map { it.title })
    }

    @Test
    fun parseChangelogAcceptsAnArrayOrAReleasesEnvelope() {
        val array = parseChangelog(
            JSONArray().put(JSONObject().put("tag_name", "v2").put("highlights", JSONArray())),
        )
        assertEquals(listOf("v2"), array?.map { it.tag })
        val envelope = parseChangelog(
            JSONObject().put("releases", JSONArray().put(JSONObject().put("name", "v3"))),
        )
        assertEquals(listOf("v3"), envelope?.map { it.tag })
        assertNull(parseChangelog(JSONObject()))
        assertNull(parseChangelog(42))
    }

    // -------------------------------------------------------- slice/dedupe

    private fun release(tag: String, vararg titles: String): JSONObject = JSONObject()
        .put("tag", tag)
        .put(
            "highlights",
            JSONArray().put(
                JSONObject()
                    .put("source", "desktop")
                    .put(
                        "items",
                        JSONArray().apply {
                            titles.forEach { put(JSONObject().put("title", it).put("description", "d-$it")) }
                        },
                    ),
            ),
        )

    @Test
    fun sliceRunsFromCurrentUntilPreviousAndDedupes() {
        val releases = listOf(
            parsedRelease("v3", "C"),
            parsedRelease("v2", "B", "B"),
            parsedRelease("v1", "A"),
        )
        // current=v3, previous=v1 → [v3, v1) = v3 + v2, with B's twin dropped.
        val sliced = sliceReleaseHighlights(releases, current = "v3", previous = "v1")
        assertEquals(listOf("C", "B"), sliced.map { it.title })
    }

    @Test
    fun sliceDropsDuplicateHighlightsAndStopsAtFive() {
        val releases = listOf(
            parsedRelease("v9", "H1", "H2", "H3", "H4", "H5", "H6", "H1"),
        )
        assertEquals(
            listOf("H1", "H2", "H3", "H4", "H5"),
            sliceReleaseHighlights(releases, current = "v9", previous = null).map { it.title },
        )
    }

    @Test
    fun sliceOpenEndsAndUnknownTagsFallBackToTheWholeSpan() {
        val releases = listOf(parsedRelease("v2", "B"), parsedRelease("v1", "A"))
        assertEquals(listOf("B", "A"), sliceReleaseHighlights(releases, current = null, previous = null).map { it.title })
        assertEquals(listOf("B", "A"), sliceReleaseHighlights(releases, current = "nope", previous = "nope").map { it.title })
    }

    @Test
    fun loadReleaseHighlightsMirrorsSliceOnARealJsonBody() {
        val json = JSONArray().put(release("v2", "B")).put(release("v1", "A"))
        assertEquals(listOf("B"), loadReleaseHighlights(json, current = "v2", previous = "v1").map { it.title })
        assertEquals(emptyList<ReleaseHighlight>(), loadReleaseHighlights(json, current = "v2", previous = "v2"))
        assertEquals(emptyList<ReleaseHighlight>(), loadReleaseHighlights(null, "1", "0"))
    }

    @Test
    fun dedupeKeyIncludesMediaIdentity() {
        val plain = ReleaseHighlight("t", "d")
        val media = plain.copy(media = ReleaseHighlightMedia("image", "s"))
        assertTrue(releaseHighlightKey(plain) != releaseHighlightKey(media))
    }

    // ------------------------------------------------------------- launch

    @Test
    fun launchDecisionFollowsTheHighlightsEffect() {
        assertEquals(ReleaseNotesLaunch.SKIP, releaseNotesLaunch(true, null, null))
        assertEquals(ReleaseNotesLaunch.RECORD_ONLY, releaseNotesLaunch(true, null, "1.0.0"))
        assertEquals(ReleaseNotesLaunch.SKIP, releaseNotesLaunch(true, "1.0.0", "1.0.0"))
        // Upstream compares raw strings here — `v1` and `1` differ.
        assertEquals(ReleaseNotesLaunch.CHECK_CHANGELOG, releaseNotesLaunch(true, "v1", "1"))
        assertEquals(ReleaseNotesLaunch.RECORD_ONLY, releaseNotesLaunch(false, "1.0.0", "2.0.0"))
        assertEquals(ReleaseNotesLaunch.CHECK_CHANGELOG, releaseNotesLaunch(true, "1.0.0", "2.0.0"))
    }

    // ------------------------------------------------------------- paging

    @Test
    fun pagingMathMatchesDialogReleaseNotes() {
        assertEquals(0, releaseNotesLastIndex(0))
        assertEquals(0, releaseNotesLastIndex(1))
        assertEquals(2, releaseNotesLastIndex(3))
        assertEquals(0, releaseNotesFeatureIndex(9, 0))
        assertEquals(0, releaseNotesFeatureIndex(0, 1))
        assertEquals(1, releaseNotesFeatureIndex(5, 2))
        assertEquals(1, releaseNotesFeatureIndex(1, 3))
    }

    // ------------------------------------------------------- source pins

    @Test
    fun releaseNotesDialogOffersTheThreeUpstreamActions() {
        val body = source("src/main/java/com/xiufeigo/opencodeshell/ui/settings/ShellPanelUpdates.kt")
        assertTrue(body.contains("R.string.dialog_releaseNotes_action_next"))
        assertTrue(body.contains("R.string.dialog_releaseNotes_action_getStarted"))
        assertTrue(body.contains("R.string.dialog_releaseNotes_action_hideFuture"))
        // Text half only — no image loader in this shell.
        assertTrue(body.contains("R.string.settings_updates_migration_title"))
        assertTrue(body.contains("R.string.settings_updates_migration_description"))
        assertTrue(body.contains("R.string.settings_updates_action_download"))
        assertTrue(body.contains("R.string.common_cancel"))
    }

    /** `parseRelease`'s shape, without the desktop-source filter, for slicing. */
    private fun parsedRelease(tag: String, vararg titles: String): ParsedRelease =
        ParsedRelease(
            tag = tag,
            highlights = titles.map { ReleaseHighlight(title = it, description = "d-$it") },
        )
}
