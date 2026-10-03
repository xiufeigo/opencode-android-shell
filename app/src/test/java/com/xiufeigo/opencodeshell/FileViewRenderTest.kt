package com.xiufeigo.opencodeshell

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source pins for the rich file viewer inside `@Composable` functions: the
 * sortable open-file tab strip, the `ArtifactView` toolbar/bodies and the
 * `ReviewFilesTab` wiring that replaces the old single-file preview.
 */
class FileViewRenderTest {

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText()
    }

    private fun strip() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/review/FileTabStrip.kt")

    private fun artifact() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/review/ArtifactView.kt")

    private fun filesTab() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/session/review/ReviewFilesTab.kt")

    @Test
    fun tabStripCarriesTheUpstreamMobileFilesHeader() {
        val body = strip()
        // `session-mobile-files.tsx:37-43` 「All files」 + `:56-76` tab scroller.
        assertTrue(body.contains("R.string.oc_file_view_all_files"))
        assertTrue(body.contains("R.string.oc_file_view_open_tabs"))
        assertTrue(body.contains("R.string.oc_home_tab_close"))
        // `tab.tsx` `SortableTab` drag reorder.
        assertTrue(body.contains("detectDragGestures"))
        assertTrue(body.contains("onReorder("))
    }

    @Test
    fun artifactToolbarHasPreviewSourceAndOpenInApp() {
        val body = artifact()
        // `artifact-view.tsx:163-172` SegmentedControl + `:134-145` meta.
        assertTrue(body.contains("R.string.oc_file_view_preview"))
        assertTrue(body.contains("R.string.oc_file_view_source"))
        assertTrue(body.contains("FileArtifact.formatBytes("))
        // `open-in-app-button.tsx` menu rows.
        assertTrue(body.contains("R.string.oc_file_view_open_in_app"))
        assertTrue(body.contains("R.string.oc_file_view_copy_path"))
    }

    @Test
    fun artifactRendersMarkdownImageTableAndCode() {
        val body = artifact()
        assertTrue(body.contains("OcMarkdown(markdown ="))
        assertTrue(body.contains("ArtifactImage("))
        assertTrue(body.contains("ArtifactTable("))
        assertTrue(body.contains("FileCodeView("))
        assertTrue(body.contains("CodeHighlight.tokenize("))
        assertTrue(body.contains("R.string.oc_review_binary"))
    }

    @Test
    fun filesTabOpensSortableTabsAndRoutesToArtifactView() {
        val body = filesTab()
        assertTrue(body.contains("FileTabStrip("))
        assertTrue(body.contains("ArtifactView("))
        assertTrue(body.contains("FileTabs.open("))
        assertTrue(body.contains("FileTabs.reorder("))
        assertTrue(body.contains("FileTabs.activeAfterClose("))
        // `file.open` opens a tab (`createOpenReviewFile`), not only the tree.
        assertTrue(body.contains("openFile(path)"))
    }
}
