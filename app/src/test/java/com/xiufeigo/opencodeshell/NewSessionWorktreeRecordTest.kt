package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.home.NewSessionWorktreeRecords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v68: a worktree created without picking a branch (the project default, the
 * common case — `eager-lagoon` was made that way) was never recorded ("no
 * branch, no record"), so the list's merge backstop had nothing to admit and
 * the fresh worktree waited on the server inventory alone.
 *
 * The record codec admits branch-less creates; the branch stays null and only
 * names the detached chip when one was picked.
 */
class NewSessionWorktreeRecordTest {

    private val dir = "/data/worktree/abc123/eager-lagoon"

    // ------------------------------------------------------------------- entry

    @Test
    fun anUnbranchedCreateStillMakesARecord() {
        assertEquals(dir to null, NewSessionWorktreeRecords.entry(dir, null))
    }

    @Test
    fun aBlankBranchIsTheProjectDefault() {
        assertEquals(dir to null, NewSessionWorktreeRecords.entry(dir, "  "))
    }

    @Test
    fun aPickedBranchIsKept() {
        assertEquals(dir to "main", NewSessionWorktreeRecords.entry(dir, " main "))
    }

    @Test
    fun aBlankDirectoryMakesNoRecord() {
        assertNull(NewSessionWorktreeRecords.entry("  ", "main"))
        assertNull(NewSessionWorktreeRecords.entry(null, null))
    }

    // ------------------------------------------------------------------- parse

    @Test
    fun aBareDirectoryParsesToADefaultBranch() {
        assertEquals(mapOf(dir to null), NewSessionWorktreeRecords.parse(dir))
    }

    @Test
    fun aBranchedLineParsesToItsBranch() {
        assertEquals(
            mapOf(dir to "main"),
            NewSessionWorktreeRecords.parse("$dir=main"),
        )
    }

    @Test
    fun blankLinesAreDroppedAndAnEmptyBranchIsTheDefault() {
        assertEquals(
            mapOf(dir to null, "/other/x" to "dev"),
            NewSessionWorktreeRecords.parse("\n$dir=\n/other/x=dev\n"),
        )
    }

    @Test
    fun emptyTextParsesToNoRecords() {
        assertEquals(emptyMap<String, String?>(), NewSessionWorktreeRecords.parse(null))
        assertEquals(emptyMap<String, String?>(), NewSessionWorktreeRecords.parse("  \n "))
    }

    // ------------------------------------------------------------------ format

    @Test
    fun aDefaultBranchKeepsItsBareDirectory() {
        assertEquals(dir, NewSessionWorktreeRecords.format(listOf(dir to null)))
    }

    @Test
    fun entriesRoundTrip() {
        val entries = listOf(dir to null, "/other/x" to "dev")
        assertEquals(
            entries.toMap(),
            NewSessionWorktreeRecords.parse(NewSessionWorktreeRecords.format(entries)),
        )
    }
}
