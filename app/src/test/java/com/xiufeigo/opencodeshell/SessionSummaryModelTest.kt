package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.summary.SessionSummaryModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A1 session summary: the pure row/derivation rules
 * (`_upstream/packages/app/src/session/summary/panel.tsx:48-114`).
 */
class SessionSummaryModelTest {

    @Test
    fun projectRowsKeepUpstreamOrderAndDropUnknownValues() {
        val rows = SessionSummaryModel.projectRows(
            directory = "C:\\sensorsdata\\main\\program\\chenjieyan_test",
            branch = "master",
            changedFiles = 3,
            additions = 12,
            deletions = 4,
        )
        assertEquals(
            listOf(
                SessionSummaryModel.Kind.WORKSPACE,
                SessionSummaryModel.Kind.BRANCH,
                SessionSummaryModel.Kind.CHANGES,
            ),
            rows.map { it.kind },
        )
        assertEquals("chenjieyan_test", rows[0].value)
        assertTrue("paths read as code", rows[0].mono)
        assertEquals("master", rows[1].value)
        assertEquals("3 files · +12 \u22124", rows[2].value)
    }

    @Test
    fun cleanWorkspaceStillShowsTheDirectoryButNoBranchOrChangeRow() {
        val rows = SessionSummaryModel.projectRows(
            directory = "C:/Windows/System32",
            branch = null,
            changedFiles = 0,
        )
        assertEquals(listOf(SessionSummaryModel.Kind.WORKSPACE), rows.map { it.kind })
        assertEquals("System32", rows.single().value)
    }

    @Test
    fun directoryLabelHandlesBothSeparatorsTrailingSlashAndBareName() {
        assertEquals("repo", SessionSummaryModel.directoryLabel("C:\\work\\repo\\"))
        assertEquals("repo", SessionSummaryModel.directoryLabel("C:/work/repo"))
        assertEquals("repo", SessionSummaryModel.directoryLabel("repo"))
        assertNull(SessionSummaryModel.directoryLabel(null))
        assertNull(SessionSummaryModel.directoryLabel("   "))
        // A drive root has no segment after the cut, so the raw path survives.
        assertEquals("C:", SessionSummaryModel.directoryLabel("C:\\"))
    }

    @Test
    fun changeCopyOmitsTheDiffHalfWhenCountsAreMissingAndUsesSingularForOne() {
        assertEquals("1 file", SessionSummaryModel.changesLabel(1, null, null))
        assertEquals("5 files", SessionSummaryModel.changesLabel(5, null, null))
        assertEquals("5 files · +0 \u22120", SessionSummaryModel.changesLabel(5, 0, 0))
        assertNull(SessionSummaryModel.changesLabel(null, 1, 1))
        assertNull(SessionSummaryModel.changesLabel(0, 1, 1))
        assertNull(SessionSummaryModel.changesLabel(-2, 1, 1))
    }

    @Test
    fun serverRowPrefersTheLabelAndFallsBackToTheUrl() {
        assertEquals(
            listOf("本地服务器"),
            SessionSummaryModel.serverRows(label = "本地服务器", url = "http://127.0.0.1:4096").map { it.value },
        )
        assertEquals(
            listOf("http://127.0.0.1:4096"),
            SessionSummaryModel.serverRows(label = "  ", url = "http://127.0.0.1:4096").map { it.value },
        )
        assertTrue(SessionSummaryModel.serverRows(null, null).isEmpty())
    }

    @Test
    fun blankBranchIsTreatedAsAbsent() {
        assertNull(SessionSummaryModel.branchLabel(""))
        assertNull(SessionSummaryModel.branchLabel("  "))
        assertEquals("v2", SessionSummaryModel.branchLabel(" v2 "))
    }
}
