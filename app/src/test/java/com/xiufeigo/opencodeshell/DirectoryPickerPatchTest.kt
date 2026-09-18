package com.xiufeigo.opencodeshell

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the 「打开项目」 staged-confirm fix: on the Android WebView a single
 * tap must stage (highlight) the row; only the footer button resolves.
 * Desktop mouse behavior (tap-through resolve) is unchanged.
 */
class DirectoryPickerPatchTest {
    private fun patch(): File =
        listOf(
            File("../frontend/patches/android-webview-z-directory-picker.patch"),
            File("../../frontend/patches/android-webview-z-directory-picker.patch"),
            File("frontend/patches/android-webview-z-directory-picker.patch"),
        ).firstOrNull { it.isFile } ?: error("missing directory picker patch")

    @Test
    fun tapStagesRowAndFooterConfirmsOnAndroidOnly() {
        val text = patch().readText()
        assertTrue(text.contains("isAndroidWebView"))
        assertTrue(text.contains("data-android-webview"))
        assertTrue(text.contains("stagedAbsolute"))
        assertTrue(text.contains("setStagedAbsolute(path.absolute)"))
        assertTrue(text.contains("directory-picker-android-footer"))
        assertTrue(text.contains("dialog.directory.action.selectFolder"))
        assertTrue(text.contains("common.cancel"))
        // Desktop path still resolves on tap.
        assertTrue(text.contains("resolve(path.absolute)"))
        // Confirm button is gated until a row is staged.
        assertTrue(text.contains("disabled={!stagedAbsolute()}"))
    }

    @Test
    fun stagedHighlightDoesNotBreakListLayout() {
        val text = patch().readText()
        // Rows live in a flex-column container; the marker wrapper must be
        // display:contents so buttons keep laying out directly.
        assertTrue(text.contains("display: contents"))
        // Staged style stays scoped to the Android WebView build.
        assertTrue(text.contains("html[data-android-webview] [data-android-staged]"))
    }

    @Test
    fun multipleContractIsUnchanged() {
        val text = patch().readText()
        // The footer reuses the existing resolve(), which already wraps for
        // multiple:true callers — so home.project.add sees no type drift and
        // the patch itself never redefines the multiple contract.
        assertTrue(text.contains("if (absolute) resolve(absolute)"))
    }
}
