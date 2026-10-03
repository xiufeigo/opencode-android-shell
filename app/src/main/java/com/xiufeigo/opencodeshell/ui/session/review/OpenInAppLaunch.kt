package com.xiufeigo.opencodeshell.ui.session.review

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.core.content.FileProvider
import com.xiufeigo.opencodeshell.OpenCodeClient
import java.io.File
import java.util.Base64

/**
 * Android hand-off for the artifact toolbar's 「Open in app」 menu, the side of
 * [OpenInApp] that needs a `Context`. Upstream's `open-in-app.tsx:84-86`
 * drives the desktop sidecar (`file://` into its browser pane); here the
 * equivalent is staging the artifact bytes under `cacheDir` and firing
 * `ACTION_VIEW` on the FileProvider `content://` URI (`FLAG_GRANT_READ_URI_PERMISSION`),
 * which covers **every MIME**. A file with no bytes, or a staging/resolve
 * failure, degrades to the `session.header.open.copyPath` clipboard row so the
 * button never silently does nothing.
 */
@Composable
internal fun rememberOpenInAppActions(): OpenInAppActions {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    return remember(context, clipboard) {
        OpenInAppActions(
            openInApp = { path, content -> fireOpenInApp(context, path, content) },
            copyPath = { path ->
                clipboard.setText(AnnotatedString(OpenInApp.pathForClipboard(path)))
            },
        )
    }
}

/** The two rows of `OpenInAppMenuItemsV2` (`open-in-app-button.tsx:66-138`). */
internal class OpenInAppActions(
    val openInApp: (String, OpenCodeClient.FileContent) -> Unit,
    val copyPath: (String) -> Unit,
)

/**
 * Stage the artifact and hand it to an external viewer; on any failure (empty
 * body, undecodable base64, no resolvable handler, `FileProvider` refusing the
 * file) fall back to the clipboard path so the row still does something.
 */
internal fun fireOpenInApp(context: Context, path: String, content: OpenCodeClient.FileContent) {
    if (!viewStaged(context, path, content)) copyPath(context, path)
}

private fun copyPath(context: Context, path: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
    clipboard?.setPrimaryClip(
        android.content.ClipData.newPlainText(OpenInApp.pathForClipboard(path), OpenInApp.pathForClipboard(path)),
    )
}

/**
 * The FileProvider authority registered in `AndroidManifest.xml`
 * (`${applicationId}.fileprovider`; `applicationId` is
 * `com.xiufeigo.opencodeshell`, no build-type suffix).
 */
private const val FILE_PROVIDER_AUTHORITY = "com.xiufeigo.opencodeshell.fileprovider"

/** Staging root under `cacheDir` — covered by `res/xml/file_paths.xml`'s `cache-path`. */
private const val STAGING_DIR = "open-in-app"

/**
 * Write the artifact bytes to `cacheDir`, share them through the manifest's
 * `FileProvider` and `ACTION_VIEW` them with a read grant — the all-MIME
 * hand-off (`file_paths.xml` covers the cache root). Returns false when there
 * is nothing to stage or the intent cannot fire, so the caller can fall back to
 * a path copy.
 */
private fun viewStaged(context: Context, path: String, content: OpenCodeClient.FileContent): Boolean {
    if (content.content.isEmpty()) return false
    return try {
        val dir = File(context.cacheDir, STAGING_DIR).apply { mkdirs() }
        val staged = File(dir, stagedFileName(path))
        staged.writeBytes(payloadBytes(content))
        val uri = FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, staged)
        val mime = OpenInApp.mimeType(path, content.mimeType)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
        true
    } catch (_: Exception) {
        false
    }
}

/**
 * The staged file's name: the artifact's basename with path separators and
 * FAT-illegal characters flattened, so a crafted `path` can never escape the
 * staging directory (`FileProvider.getUriForFile` requires a file inside a
 * configured root). Blank / dot names fall back to `artifact`.
 */
internal fun stagedFileName(path: String): String {
    val base = path.replace('\\', '/').trim().trimEnd('/').substringAfterLast('/')
    val cleaned = base.map { if (it in "\\/:*?\"<>|") '_' else it }
        .joinToString("")
        .trim()
    return when (cleaned) {
        "", ".", ".." -> "artifact"
        else -> cleaned
    }
}

/**
 * The bytes to stage: `FileContent.encoding == "base64"` keeps binary payloads
 * base64-tagged (`FileArtifact`), anything else is UTF-8 text. Malformed base64
 * throws and reads as a staging failure at the call site.
 */
internal fun payloadBytes(content: OpenCodeClient.FileContent): ByteArray =
    if (content.encoding == "base64") {
        Base64.getDecoder().decode(content.content)
    } else {
        content.content.toByteArray(Charsets.UTF_8)
    }
