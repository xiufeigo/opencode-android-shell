package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.review.FileArtifact
import com.xiufeigo.opencodeshell.ui.session.review.FileTabs
import com.xiufeigo.opencodeshell.ui.session.review.OpenInApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic coverage for the rich file viewer: upstream `workspaces/files/artifact.ts`
 * classification (kind / MIME / `resolveArtifactPath` / `parseDelimited` /
 * `formatBytes`), the `session/helpers.ts:174-179` tab model and the
 * `open-in-app.tsx` hand-off decision.
 */
class FileArtifactTest {

    private fun content(
        text: String = "",
        binary: Boolean = false,
        encoding: String? = null,
        mime: String? = null,
    ) = OpenCodeClient.FileContent(
        binary = binary,
        content = text,
        diff = null,
        patch = null,
        encoding = encoding,
        mimeType = mime,
    )

    // ------------------------------------------------------------ classification

    @Test
    fun extensionIsLowerCaseWithoutTheDot() {
        assertEquals("png", FileArtifact.extension("src/Logo.PNG"))
        assertEquals("md", FileArtifact.extension("README.md"))
        assertEquals("", FileArtifact.extension("Makefile"))
        assertEquals("", FileArtifact.extension(".gitignore"))
        assertEquals("kt", FileArtifact.extension("C:\\w\\Main.kt"))
    }

    @Test
    fun kindMapsTheUpstreamMimeTable() {
        assertEquals(FileArtifact.Kind.IMAGE, FileArtifact.kind("a.png"))
        assertEquals(FileArtifact.Kind.IMAGE, FileArtifact.kind("a.webp"))
        assertEquals(FileArtifact.Kind.SVG, FileArtifact.kind("icon.svg"))
        assertEquals(FileArtifact.Kind.PDF, FileArtifact.kind("spec.pdf"))
        assertEquals(FileArtifact.Kind.HTML, FileArtifact.kind("index.html"))
        assertEquals(FileArtifact.Kind.MARKDOWN, FileArtifact.kind("notes.md"))
        assertEquals(FileArtifact.Kind.MERMAID, FileArtifact.kind("flow.mmd"))
        assertEquals(FileArtifact.Kind.TABLE, FileArtifact.kind("data.csv"))
        assertEquals(FileArtifact.Kind.TABLE, FileArtifact.kind("data.tsv"))
        assertEquals(FileArtifact.Kind.AUDIO, FileArtifact.kind("a.mp3"))
        assertEquals(FileArtifact.Kind.VIDEO, FileArtifact.kind("a.mp4"))
        assertEquals(FileArtifact.Kind.FONT, FileArtifact.kind("a.woff2"))
        // Unknown extensions are text (`artifact.ts:72`).
        assertEquals(FileArtifact.Kind.TEXT, FileArtifact.kind("Main.kt"))
    }

    @Test
    fun viewerKindFallsBackToTheBinaryPlaceholder() {
        // `artifact-view.tsx:60-64`: binary + no MIME at all → placeholder.
        assertEquals(
            FileArtifact.Kind.BINARY,
            FileArtifact.viewerKind("blob.bin", binary = true, declaredMime = null),
        )
        // An NUL-tainted text file has nothing to preview either.
        assertEquals(
            FileArtifact.Kind.BINARY,
            FileArtifact.viewerKind("a.txt", binary = true, declaredMime = null),
        )
        // A raster keeps its extension kind even when the shim left MIME null.
        assertEquals(
            FileArtifact.Kind.IMAGE,
            FileArtifact.viewerKind("a.png", binary = true, declaredMime = null),
        )
        assertEquals(
            FileArtifact.Kind.MARKDOWN,
            FileArtifact.viewerKind("a.md", binary = false, declaredMime = null),
        )
    }

    @Test
    fun previewableAndMediaMatchUpstreamSets() {
        // `previewableKinds` (`artifact-view.tsx:38`).
        assertTrue(FileArtifact.isPreviewable(FileArtifact.Kind.SVG))
        assertTrue(FileArtifact.isPreviewable(FileArtifact.Kind.MARKDOWN))
        assertTrue(FileArtifact.isPreviewable(FileArtifact.Kind.TABLE))
        assertFalse(FileArtifact.isPreviewable(FileArtifact.Kind.IMAGE))
        assertFalse(FileArtifact.isPreviewable(FileArtifact.Kind.TEXT))
        assertFalse(FileArtifact.isPreviewable(FileArtifact.Kind.BINARY))
        // `binaryKinds` (`artifact.ts:87`).
        assertTrue(FileArtifact.isMedia(FileArtifact.Kind.IMAGE))
        assertTrue(FileArtifact.isMedia(FileArtifact.Kind.PDF))
        assertFalse(FileArtifact.isMedia(FileArtifact.Kind.MARKDOWN))
        assertFalse(FileArtifact.isMedia(FileArtifact.Kind.BINARY))
    }

    // --------------------------------------------------------------- size meta

    @Test
    fun contentBytesCountsBase64PaddingAndUtf8() {
        // "AA==" is 1 byte; "AAAA" is 3.
        assertEquals(1L, FileArtifact.contentBytes(content("AA==", binary = true, encoding = "base64")))
        assertEquals(3L, FileArtifact.contentBytes(content("AAAA", binary = true, encoding = "base64")))
        // Text counts UTF-8 bytes (U+597D is 3).
        assertEquals(3L, FileArtifact.contentBytes(content("好")))
    }

    @Test
    fun formatBytesFollowsIntlUnitRules() {
        // `artifact-view.tsx:134-145`: SI, long-form `bytes`, one decimal below 100.
        assertEquals("0 bytes", FileArtifact.formatBytes(0))
        assertEquals("500 bytes", FileArtifact.formatBytes(500))
        assertEquals("1.5 kB", FileArtifact.formatBytes(1500))
        assertEquals("15 kB", FileArtifact.formatBytes(15_000))
        assertEquals("100 kB", FileArtifact.formatBytes(100_000))
        assertEquals("1.2 MB", FileArtifact.formatBytes(1_200_000))
        assertEquals("2 GB", FileArtifact.formatBytes(2_000_000_000))
    }

    // ------------------------------------------------------------- link resolve

    @Test
    fun resolveArtifactPathStopsAtTheBaseRoot() {
        // `artifact.ts:196-211`.
        assertEquals("docs/a.md", FileArtifact.resolveArtifactPath("docs", "a.md"))
        assertEquals("docs/a.md", FileArtifact.resolveArtifactPath("docs", "./a.md"))
        assertEquals("a.md", FileArtifact.resolveArtifactPath("docs", "../a.md"))
        assertNull(FileArtifact.resolveArtifactPath("docs", "../../a.md"))
        assertNull(FileArtifact.resolveArtifactPath("", "../a.md"))
    }

    @Test
    fun resolveLinkStripsLineCitationsAndKeepsAbsolutes() {
        // `open-artifact.tsx:47-56`.
        assertEquals("/w/a.kt", FileArtifact.resolveLink("/w/a.kt:12:3", "src", "/w"))
        assertEquals("/w/a.kt", FileArtifact.resolveLink("/w/a.kt:12", "src", "/w"))
        assertEquals("src/a.kt", FileArtifact.resolveLink("a.kt", "src", "/w"))
        // Climbing past the base resolves from the referencing folder's root.
        assertEquals("shared/r.md", FileArtifact.resolveLink("../shared/r.md", "docs", "/w"))
    }

    // -------------------------------------------------------------- csv tables

    @Test
    fun parseDelimitedHandlesQuotesAndCountsTruncatedRows() {
        val parsed = FileArtifact.parseDelimited(
            "a,b\n\"x,\"\"y\"\"\",2\n3,4\n5,6\n",
            delimiter = ',',
            limit = 3,
        )
        assertEquals(3, parsed.rows.size)
        assertEquals(4, parsed.total)
        assertEquals(2, parsed.columns)
        assertEquals(listOf("a", "b"), parsed.rows[0])
        assertEquals(listOf("x,\"y\"", "2"), parsed.rows[1])
        assertEquals(listOf("3", "4"), parsed.rows[2])
    }

    @Test
    fun tableDelimiterIsTabOnlyForTsv() {
        assertEquals(',', FileArtifact.tableDelimiter("data.csv"))
        assertEquals('\t', FileArtifact.tableDelimiter("data.tsv"))
    }

    // ---------------------------------------------------------------- file tabs

    @Test
    fun openIsIdempotentAndPreservesDragOrder() {
        assertEquals(listOf("a"), FileTabs.open(listOf("a"), "a"))
        assertEquals(listOf("a", "b"), FileTabs.open(listOf("a"), "b"))
    }

    @Test
    fun closeDropsOnlyTheTarget() {
        assertEquals(listOf("a", "c"), FileTabs.close(listOf("a", "b", "c"), "b"))
    }

    @Test
    fun reorderAppliesGetTabReorderIndexAsAMove() {
        // `helpers.ts:174-179` returns `toIndex`; the move lands `from` there.
        assertEquals(listOf("b", "a", "c"), FileTabs.reorder(listOf("a", "b", "c"), "a", "b"))
        assertEquals(listOf("b", "c", "a"), FileTabs.reorder(listOf("a", "b", "c"), "a", "c"))
        // Unknown keys and a self-drop are no-ops.
        assertEquals(listOf("a"), FileTabs.reorder(listOf("a"), "a", "zz"))
        assertEquals(listOf("a"), FileTabs.reorder(listOf("a"), "zz", "a"))
    }

    @Test
    fun activeAfterCloseStepsToTheNeighbourSlot() {
        val tabs = listOf("a", "b", "c")
        val remaining = FileTabs.close(tabs, "b")
        // Closing a background tab keeps the selection.
        assertEquals("c", FileTabs.activeAfterClose("c", "b", 1, remaining))
        // Closing the active tab steps to whatever slid into its slot.
        assertEquals("c", FileTabs.activeAfterClose("b", "b", 1, remaining))
        // Closing the active last tab falls back to the new last.
        assertEquals("b", FileTabs.activeAfterClose("c", "c", 2, FileTabs.close(tabs, "c")))
        assertNull(FileTabs.activeAfterClose("a", "a", 0, emptyList()))
    }

    @Test
    fun displayNameIsTheBasename() {
        assertEquals("a.kt", FileTabs.displayName("src/a.kt"))
    }

    // ------------------------------------------------------------- open-in-app

    @Test
    fun openInAppActionFollowsThePlatformLimits() {
        // API 29+ image with bytes → `ACTION_VIEW`.
        assertEquals(
            OpenInApp.Action.VIEW,
            OpenInApp.actionFor(FileArtifact.Kind.IMAGE, hasBytes = true, sdkInt = 29),
        )
        // Below 29 a MediaStore write needs storage permission → path copy.
        assertEquals(
            OpenInApp.Action.COPY_PATH,
            OpenInApp.actionFor(FileArtifact.Kind.IMAGE, hasBytes = true, sdkInt = 28),
        )
        // Text-ish kinds share their body (`file.view.preview` source).
        assertEquals(
            OpenInApp.Action.SHARE_TEXT,
            OpenInApp.actionFor(FileArtifact.Kind.MARKDOWN, hasBytes = false, sdkInt = 34),
        )
        assertEquals(
            OpenInApp.Action.SHARE_TEXT,
            OpenInApp.actionFor(FileArtifact.Kind.TABLE, hasBytes = true, sdkInt = 34),
        )
        // Media and the binary placeholder degrade to the copy-path row.
        assertEquals(
            OpenInApp.Action.COPY_PATH,
            OpenInApp.actionFor(FileArtifact.Kind.VIDEO, hasBytes = true, sdkInt = 34),
        )
        assertEquals(
            OpenInApp.Action.COPY_PATH,
            OpenInApp.actionFor(FileArtifact.Kind.BINARY, hasBytes = true, sdkInt = 34),
        )
    }

    @Test
    fun mimeTypePrefersTheDeclaredOneThenTheExtensionTable() {
        assertEquals("image/png", OpenInApp.mimeType("a.png", null))
        assertEquals("text/plain", OpenInApp.mimeType("a.png", "text/plain"))
        assertEquals("application/octet-stream", OpenInApp.mimeType("a.zz", null))
    }

    @Test
    fun canOpenAndClipboardPathAreUpstreamClausess() {
        assertTrue(OpenInApp.canOpen("src/a.kt"))
        assertFalse(OpenInApp.canOpen(null))
        assertFalse(OpenInApp.canOpen(""))
        assertEquals("C:/w/a.kt", OpenInApp.pathForClipboard("C:\\w\\a.kt"))
    }
}
