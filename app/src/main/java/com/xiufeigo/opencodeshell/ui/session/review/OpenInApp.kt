package com.xiufeigo.opencodeshell.ui.session.review

/**
 * Hand-off decision for the artifact toolbar's **Open in app** menu, the
 * Android stand-in for upstream `session/files/open-in-app.tsx` +
 * `open-in-app-button.tsx` (v2 head `8ce629b`).
 *
 * Upstream drives the desktop sidecar (`ServerConnection.builtin` +
 * `browser.command({type:"tabs.open", url:file://…})`, `open-in-app.tsx:78-86`)
 * or the editor picker. There is no sidecar on Android, so the hand-off is an
 * `Intent.ACTION_VIEW` / `ACTION_SEND` chooser; media reaches other apps
 * through a `MediaStore` copy (`IS_PENDING`, API 29+) and every other kind
 * degrades to the upstream menu's `session.header.open.copyPath` row.
 */
object OpenInApp {

    /** What the toolbar action can do with one loaded artifact. */
    enum class Action {
        /** `ACTION_VIEW` on a MediaStore image (API 29+, decodable bitmap). */
        VIEW,

        /** `ACTION_SEND` of the text body (`file.view.preview` kinds as source). */
        SHARE_TEXT,

        /** `session.header.open.copyPath` — no external handler on this platform. */
        COPY_PATH,
    }

    /** Upstream `canOpenInBrowser` (`open-in-app.tsx:78-82`) minus the sidecar checks. */
    fun canOpen(path: String?): Boolean = !path.isNullOrBlank()

    /**
     * The chooser action for one artifact. `sdkInt` gates the MediaStore write
     * (API 29+ contributes without storage permission); everything else is a
     * path copy so the button never silently does nothing.
     */
    fun actionFor(kind: FileArtifact.Kind, hasBytes: Boolean, sdkInt: Int): Action = when {
        kind == FileArtifact.Kind.IMAGE && hasBytes && sdkInt >= 29 -> Action.VIEW
        kind == FileArtifact.Kind.TEXT ||
            kind == FileArtifact.Kind.MARKDOWN ||
            kind == FileArtifact.Kind.TABLE ||
            kind == FileArtifact.Kind.HTML ||
            kind == FileArtifact.Kind.MERMAID -> Action.SHARE_TEXT
        else -> Action.COPY_PATH
    }

    /** `open-in-app.tsx:85` / `artifact-view.tsx:207` MIME for the `Intent`. */
    fun mimeType(path: String, declared: String?): String =
        declared ?: FileArtifact.mime(path) ?: "application/octet-stream"

    /** Upstream `encodeFilePath` sibling: the absolute path the menu copies. */
    fun pathForClipboard(path: String): String = path.replace('\\', '/')
}
