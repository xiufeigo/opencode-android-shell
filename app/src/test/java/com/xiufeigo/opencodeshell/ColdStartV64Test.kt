package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.data.HomeProjectRecords
import com.xiufeigo.opencodeshell.data.mergeRestored
import com.xiufeigo.opencodeshell.ui.ColdStart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v64: the cold start on emulator-5558 never drew a window.
 *
 * `dumpsys activity lastanr` for the 3:16:57 report reads
 * `Input dispatching timed out (Application does not have a focused window)`
 * with `firstWindowDrawn=false` and `mNumDrawnWindows=0` — there was no frame to
 * blame, and a live repro (`force-stop` + `monkey`, logcat) showed the process
 * still running `Verification of …` for `AppKt` composables 27 s after start.
 * The dex cost belongs to the build profile, not to this shell; the disk reads
 * belong here, and [ColdStart] is the list that keeps them honest.
 */
class ColdStartV64Test {

    @Test
    fun theOnlyMainThreadDiskReadIsTheOneTheThemeNeeds() {
        // `SettingsStore`'s read is what the first frame's colours are made of,
        // and it shares its file with `AppearancePrefs`. Everything this shell
        // could defer is deferred.
        assertEquals(
            listOf("settings"),
            ColdStart.onMainThread().map { it.id },
        )
    }

    @Test
    fun theProjectRecordAndTheNavRestoreAreOffTheMainThread() {
        val deferred = ColdStart.steps.filter { it.deferred }.map { it.id }
        assertTrue(deferred.contains("home_projects"))
        assertTrue(deferred.contains("nav_route_restore"))
    }

    @Test
    fun everyStepIsAccountedFor() {
        // A step with no cost cannot be reviewed, and a duplicate id would make
        // the list lie about what the launch path does.
        assertEquals(ColdStart.steps.size, ColdStart.steps.map { it.id }.toSet().size)
        assertTrue(ColdStart.steps.all { it.id.isNotBlank() })
    }

    // ------------------------------------------------------------ deferred read

    @Test
    fun anUntouchedStorePublishesTheRecordStraightOffDisk() {
        val loaded = HomeProjectRecords.State(
            list = listOf(HomeProjectRecords.Project("C:\\repo")),
            lastProject = "C:\\repo",
        )
        assertEquals(loaded, mergeRestored(HomeProjectRecords.State(), loaded))
    }

    @Test
    fun aWriteThatBeatTheReadIsNotRolledBack() {
        // The store is built in a `remember` and read off the IO dispatcher, so
        // the user can close a project on the frame Home first appears. The disk
        // record must not resurrect it.
        val current = HomeProjectRecords.State(
            list = listOf(HomeProjectRecords.Project("D:\\other")),
            recentlyClosed = listOf("C:\\repo"),
        )
        val loaded = HomeProjectRecords.State(
            list = listOf(HomeProjectRecords.Project("C:\\repo"), HomeProjectRecords.Project("E:\\third")),
            lastProject = "E:\\third",
        )
        val merged = mergeRestored(current, loaded)
        assertEquals(listOf("D:\\other", "E:\\third"), merged.list.map { it.worktree })
        assertEquals("E:\\third", merged.lastProject)
        assertTrue(merged.recentlyClosed.contains("C:\\repo"))
    }

    @Test
    fun aMergeNeverDuplicatesOneDirectory() {
        // Both sides know `C:\repo`, under the two spellings the store folds
        // (`ProjectPaths.sameDirectory`) — one row is the invariant the whole
        // store rests on, so a merge may not break it.
        val current = HomeProjectRecords.State(list = listOf(HomeProjectRecords.Project("/repo")))
        val loaded = HomeProjectRecords.State(list = listOf(HomeProjectRecords.Project("C:\\repo")))
        val merged = mergeRestored(current, loaded)
        assertEquals(1, merged.list.size)
    }
}
