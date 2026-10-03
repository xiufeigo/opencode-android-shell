package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.home.ScopedSearch
import com.xiufeigo.opencodeshell.ui.home.pickerMissingBrowse
import com.xiufeigo.opencodeshell.ui.home.pickerMissingDecision
import com.xiufeigo.opencodeshell.v2.DirEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v67 (`research/v67_verify/5556/27_badpath.png`): a path that does not exist
 * typed into 「Move to…」 greyed 「选择此目录」 and said nothing. The Enter key's
 * error card + Retry worked; the debounced auto-resolve swallowed the same
 * answer.
 *
 * [pickerMissingBrowse] is what the unlistable branch now asks, and it is the
 * other half of that fix: a path still being typed must stay silent, or the
 * error flashes on every pause in the middle of a segment.
 */
class DirectoryPickerMissingPathTest {

    private fun scope(directory: String, name: String) =
        ScopedSearch(directory = directory, name = name, pathLike = true)

    private fun child(name: String, directory: String) = DirEntry(
        name = name,
        absolute = "$directory/$name",
        isDirectory = true,
    )

    private val parent = "/mnt/c/sensorsdata/main/program"
    private val entries = listOf(
        child("opencode-android-shell", parent),
        child("v36projB", parent),
    )

    @Test
    fun settledPathTheServerCannotListIsReported() {
        assertTrue(
            pickerMissingBrowse(
                scope = scope("$parent/nopexx", "nopexx"),
                entries = entries,
                listed = false,
                settled = true,
            ),
        )
    }

    @Test
    fun aReadablePathIsBrowsedInsteadOfReported() {
        assertFalse(
            pickerMissingBrowse(
                scope = scope("$parent/nopexx", "nopexx"),
                entries = entries,
                listed = true,
                settled = true,
            ),
        )
    }

    @Test
    fun aStillTypingPathIsNotReported() {
        // Same input, not yet settled — the fingerprint of a segment in progress.
        assertFalse(
            pickerMissingBrowse(
                scope = scope("$parent/nope", "nope"),
                entries = entries,
                listed = false,
                settled = false,
            ),
        )
    }

    @Test
    fun aSegmentTheDropdownStillOffersIsNotReported() {
        // `v36` fuzzy-matches `v36projB`: the user is inside the segment, and
        // the suggestion rows are the way to commit it.
        assertFalse(
            pickerMissingBrowse(
                scope = scope("$parent/v36", "v36"),
                entries = entries,
                listed = false,
                settled = true,
            ),
        )
    }

    @Test
    fun anEmptyTailIsNotAMissingDirectory() {
        // A trailing `/` asks for the scope's children, not for one directory.
        assertFalse(
            pickerMissingBrowse(
                scope = scope("$parent/", ""),
                entries = entries,
                listed = false,
                settled = true,
            ),
        )
    }

    /**
     * v68 (`research/v68_verify/5556/30_badpath.png` →
     * `31_badpath_wait.png`): the settled effect's verdict for one typed path.
     * A settled path the server cannot list is browsed — the listing then
     * answers with the error card + Retry instead of the old directory with
     * no hint, which is also what covers bug 3 (no separate hint needed once
     * the error surfaces).
     */
    @Test
    fun settledMissingPathVerdictIsBrowse() {
        assertEquals(
            "$parent/nopexx5556",
            pickerMissingDecision(
                currentPath = "/mnt/c",
                direct = "$parent/nopexx5556",
                scope = scope(parent, "nopexx5556"),
                entries = entries,
                listed = false,
            ),
        )
    }

    @Test
    fun readablePathVerdictIsBrowse() {
        assertEquals(
            "$parent/opencode-android-shell",
            pickerMissingDecision(
                currentPath = "/mnt/c",
                direct = "$parent/opencode-android-shell",
                scope = scope(parent, "opencode-android-shell"),
                entries = entries,
                listed = true,
            ),
        )
    }

    @Test
    fun halfTypedPathVerdictIsStay() {
        // `senso` still fuzzy-matches `sensorsdata`'s sibling rows: the user
        // is inside the segment, and browsing it would flash the error card.
        val siblings = listOf(child("sensorsdata", "/mnt/c"), child("Users", "/mnt/c"))
        assertNull(
            pickerMissingDecision(
                currentPath = "/mnt/c",
                direct = "/mnt/c/senso",
                scope = scope("/mnt/c", "senso"),
                entries = siblings,
                listed = false,
            ),
        )
    }

    @Test
    fun alreadyBrowsedPathVerdictIsStay() {
        assertNull(
            pickerMissingDecision(
                currentPath = parent,
                direct = parent,
                scope = scope(parent, "program"),
                entries = entries,
                listed = true,
            ),
        )
    }

    @Test
    fun relativeInputVerdictIsStay() {
        assertNull(
            pickerMissingDecision(
                currentPath = parent,
                direct = null,
                scope = scope(parent, "openc"),
                entries = entries,
                listed = false,
            ),
        )
    }
}
