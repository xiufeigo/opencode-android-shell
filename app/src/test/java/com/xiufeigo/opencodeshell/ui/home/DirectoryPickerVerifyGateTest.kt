package com.xiufeigo.opencodeshell.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v54 P1（`research/v54_verify/tD/15_badpath.png` → `16_badrESULT.png`）:
 * 「选择此目录」 accepted a directory the server had never listed.
 *
 * The user typed `/tmp/nope_zzz` — a path the 4095 server does not have —
 * without leaving the field. The footer read the listing state of the **browsed**
 * path (`/`, healthy), so the button stayed accent-cyan and armed the *typed*
 * path in the same gesture; that listing then failed, the button greyed out,
 * and the armed confirm row stayed tappable and submitted the missing
 * directory anyway.
 *
 * The gate is now a match between the target the confirm names and the last
 * path the server actually listed ([pickerCanConfirm]), plus a read guard so a
 * late answer from an abandoned path cannot be painted ([pickerReadIsCurrent]).
 */
class DirectoryPickerVerifyGateTest {

    /** A path the user typed but never browsed has not been verified. */
    @Test
    fun aPathTypedButNotBrowsedCannotBeConfirmed() {
        assertFalse(
            pickerCanConfirm(
                target = "/tmp/nope_zzz",
                atDrives = false,
                verifiedPath = "/",
            ),
        )
    }

    /** The browsed, listed directory still confirms — the normal two taps. */
    @Test
    fun theListedDirectoryStillConfirms() {
        assertTrue(
            pickerCanConfirm(
                target = "/home/xiufeigo",
                atDrives = false,
                verifiedPath = "/home/xiufeigo",
            ),
        )
    }

    /**
     * The armed path is the one measured: it was armed before the listing came
     * back, so the second tap used to submit it with the button already greyed.
     */
    @Test
    fun anArmedPathWhoseListingFailedCannotBeSubmitted() {
        // The read for the armed path failed: nothing was verified at all.
        assertFalse(pickerCanConfirm(target = "/tmp/nope_zzz", atDrives = false, verifiedPath = null))
        // …and a verification of some *other* directory does not carry over.
        assertFalse(
            pickerCanConfirm(target = "/tmp/nope_zzz", atDrives = false, verifiedPath = "/home/xiufeigo"),
        )
    }

    /** An empty listing is a directory that exists; a `null` one is not. */
    @Test
    fun anEmptyListingVerifiesWhileAFailedReadDoesNot() {
        assertTrue(pickerCanConfirm(target = "/tmp/empty", atDrives = false, verifiedPath = "/tmp/empty"))
        assertFalse(pickerCanConfirm(target = "/tmp/empty", atDrives = false, verifiedPath = null))
    }

    /** The drive level and a blank path still select nothing. */
    @Test
    fun theDriveLevelAndAnEmptyPathStillSelectNothing() {
        assertFalse(pickerCanConfirm(target = DRIVES_LEVEL, atDrives = true, verifiedPath = DRIVES_LEVEL))
        assertFalse(pickerCanConfirm(target = "", atDrives = false, verifiedPath = "/"))
        assertFalse(pickerCanConfirm(target = "/", atDrives = false, verifiedPath = null))
    }

    /**
     * v54 `17_nodir_err.png`: `/home/xiufeigo` exists, yet the card reported
     * 「目录不存在或无法读取」 — the failure of the read the user had already
     * navigated away from arrived after the new one started.
     */
    @Test
    fun aLateAnswerFromAnAbandonedPathIsDropped() {
        assertTrue(pickerReadIsCurrent(requested = "/home/xiufeigo", currentPath = "/home/xiufeigo"))
        assertFalse(pickerReadIsCurrent(requested = "/tmp/nope_zzz", currentPath = "/home/xiufeigo"))
    }
}
