package com.xiufeigo.opencodeshell.ui.session.review

import com.xiufeigo.opencodeshell.OpenCodeClient

/**
 * Artifact classification and open-file tab model behind the rich file viewer,
 * the Android port of upstream `packages/app/src/workspaces/files/artifact.ts`
 * and `packages/app/src/session/helpers.ts` (v2 head `8ce629b`).
 *
 * Wire contract: the viewer receives the v1 `GET /file/content?path=` shape
 * (`OpenCodeClient.FileContent`) that `V2Transport.fileContentFromBytes`
 * rebuilds from `GET /api/fs/read/<rel>` — `{type:"text"|"binary", content,
 * encoding?, mimeType?}` with binary payloads base64-tagged. Upstream derives
 * `mimeType` from the extension server-side (`artifact.ts:16-58`); the v2 shim
 * leaves it null, so [viewerKind] falls back to [mime] exactly like
 * `artifactMime`.
 */
object FileArtifact {

    /** Upstream `ArtifactKind` plus the `ArtifactView` `"binary"` placeholder. */
    enum class Kind {
        IMAGE,
        SVG,
        AUDIO,
        VIDEO,
        PDF,
        HTML,
        MARKDOWN,
        MERMAID,
        TABLE,
        FONT,
        TEXT,
        BINARY,
    }

    /** `artifact.ts:16-58` `mimes`, extension → MIME. */
    private val MIMES = mapOf(
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "avif" to "image/avif",
        "bmp" to "image/bmp",
        "ico" to "image/x-icon",
        "tif" to "image/tiff",
        "tiff" to "image/tiff",
        "heic" to "image/heic",
        "svg" to "image/svg+xml",
        "mp3" to "audio/mpeg",
        "wav" to "audio/wav",
        "ogg" to "audio/ogg",
        "oga" to "audio/ogg",
        "m4a" to "audio/mp4",
        "aac" to "audio/aac",
        "flac" to "audio/flac",
        "opus" to "audio/ogg",
        "weba" to "audio/webm",
        "mp4" to "video/mp4",
        "m4v" to "video/mp4",
        "webm" to "video/webm",
        "mov" to "video/quicktime",
        "ogv" to "video/ogg",
        "mkv" to "video/x-matroska",
        "pdf" to "application/pdf",
        "html" to "text/html",
        "htm" to "text/html",
        "md" to "text/markdown",
        "markdown" to "text/markdown",
        "mdx" to "text/markdown",
        "mmd" to "text/vnd.mermaid",
        "mermaid" to "text/vnd.mermaid",
        "csv" to "text/csv",
        "tsv" to "text/tab-separated-values",
        "ttf" to "font/ttf",
        "otf" to "font/otf",
        "woff" to "font/woff",
        "woff2" to "font/woff2",
    )

    /** Kinds that render a preview from their text and can toggle back to source (`artifact-view.tsx:38`). */
    private val PREVIEWABLE = setOf(Kind.SVG, Kind.HTML, Kind.MARKDOWN, Kind.MERMAID, Kind.TABLE)

    /** Upstream `binaryKinds` (`artifact.ts:87`): bytes kept base64 for media elements. */
    private val MEDIA = setOf(Kind.IMAGE, Kind.AUDIO, Kind.VIDEO, Kind.PDF, Kind.FONT)

    /** Upstream `open-artifact.tsx:50` `/^[a-z]:\//i`: a Windows drive absolute. */
    private val WINDOWS_DRIVE = Regex("^[a-z]:/", RegexOption.IGNORE_CASE)

    /** `artifactExtension` (`artifact.ts:60-65`): lower-case ext without the dot, `""` when none. */
    fun extension(path: String): String {
        val name = ReviewDiff.basename(path)
        val index = name.lastIndexOf('.')
        return if (index <= 0) "" else name.substring(index + 1).lowercase()
    }

    /** `artifactMime` (`artifact.ts:67-69`). */
    fun mime(path: String): String? = MIMES[extension(path)]

    /** `artifactKind` (`artifact.ts:71-84`): unknown extensions are `text`. */
    fun kind(path: String): Kind {
        val mime = mime(path) ?: return Kind.TEXT
        return when {
            mime == "image/svg+xml" -> Kind.SVG
            mime == "application/pdf" -> Kind.PDF
            mime == "text/html" -> Kind.HTML
            mime == "text/markdown" -> Kind.MARKDOWN
            mime == "text/vnd.mermaid" -> Kind.MERMAID
            mime == "text/csv" || mime == "text/tab-separated-values" -> Kind.TABLE
            mime.startsWith("image/") -> Kind.IMAGE
            mime.startsWith("audio/") -> Kind.AUDIO
            mime.startsWith("font/") -> Kind.FONT
            else -> Kind.VIDEO
        }
    }

    /**
     * Which viewer owns a loaded file, upstream `ArtifactView`'s `kind` memo
     * (`artifact-view.tsx:60-64`): a binary with no MIME at all is the
     * placeholder, otherwise the extension kind — and an NUL-tainted `.txt`
     * (the v2 shim's binary flag) has nothing to preview either.
     */
    fun viewerKind(path: String, binary: Boolean, declaredMime: String?): Kind {
        val mime = declaredMime ?: mime(path)
        if (binary && mime == null) return Kind.BINARY
        val kind = kind(path)
        return if (binary && kind == Kind.TEXT) Kind.BINARY else kind
    }

    /** `previewableKinds` (`artifact-view.tsx:38`). */
    fun isPreviewable(kind: Kind): Boolean = kind in PREVIEWABLE

    /** `binaryKinds` (`artifact.ts:87`). */
    fun isMedia(kind: Kind): Boolean = kind in MEDIA

    /** `contentBytes` (`artifact.ts:117-125`): wire `size` first, else the decoded length. */
    fun contentBytes(content: OpenCodeClient.FileContent, declaredSize: Long? = null): Long {
        declaredSize?.let { return it }
        if (content.encoding == "base64") {
            val text = content.content
            val padding = when {
                text.endsWith("==") -> 2
                text.endsWith("=") -> 1
                else -> 0
            }
            return (text.length * 3 / 4 - padding).toLong()
        }
        return content.content.toByteArray(Charsets.UTF_8).size.toLong()
    }

    /**
     * `formatBytes` (`artifact-view.tsx:134-145`) without `Intl`: SI units, one
     * decimal below 100 of the unit, the singular spelled `bytes` like the
     * upstream long-form unit.
     */
    fun formatBytes(bytes: Long): String {
        val units = listOf("bytes", "kB", "MB", "GB")
        val value = if (bytes > 0) bytes else 0
        var index = 0
        var scaled = value.toDouble()
        while (scaled >= 1000 && index < units.lastIndex) {
            scaled /= 1000
            index++
        }
        return if (index == 0) {
            "$value bytes"
        } else if (scaled >= 100 || scaled == scaled.toLong().toDouble()) {
            "${scaled.toLong()} ${units[index]}"
        } else {
            String.format("%.1f %s", scaled, units[index])
        }
    }

    /**
     * `resolveArtifactPath` (`artifact.ts:196-211`): a relative link against a
     * directory; undefined when the link climbs past the base's root.
     */
    fun resolveArtifactPath(base: String, href: String): String? {
        val target = href.replace('\\', '/')
        if (target.startsWith("/")) return null
        val dir = base.replace('\\', '/')
        val segments = dir.split('/').filter { it.isNotEmpty() }.toMutableList()
        for (segment in target.split('/')) {
            if (segment.isEmpty() || segment == ".") continue
            if (segment != "..") {
                segments += segment
                continue
            }
            if (segments.isEmpty()) return null
            segments.removeAt(segments.lastIndex)
        }
        return (if (dir.startsWith("/")) "/" else "") + segments.joinToString("/")
    }

    /**
     * `open-artifact.tsx:47-56` `resolve`: `path:line[:col]` citations keep the
     * file, absolute links stay absolute, and a relative link resolves against
     * the referencing file's directory (or the workspace root).
     */
    fun resolveLink(href: String, base: String, root: String): String {
        val value = href.replace('\\', '/').replace(Regex(":\\d+(:\\d+)?$"), "")
        if (value.startsWith("/") || WINDOWS_DRIVE.matches(value)) return value
        val relative = resolveArtifactPath(base, value)
        if (relative != null) return relative
        val dir = if (base.isNotEmpty()) {
            root.trimEnd('/') + "/" + base.trimEnd('/')
        } else {
            root.trimEnd('/')
        }
        return resolveArtifactPath(dir, value) ?: value
    }

    /**
     * RFC 4180 style parse (`artifact.ts:131-181`): quoted fields may contain
     * the delimiter, newlines and doubled quotes; rows past [limit] are counted
     * but not returned so a huge CSV cannot stall the viewer.
     */
    data class ParsedTable(val rows: List<List<String>>, val total: Int, val columns: Int)

    fun parseDelimited(text: String, delimiter: Char, limit: Int = 1000): ParsedTable {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        var field = StringBuilder()
        var quoted = false
        var total = 0
        fun endRow() {
            row.add(field.toString())
            field = StringBuilder()
            val blank = row.size == 1 && row[0].isEmpty()
            if (!blank) {
                total++
                if (rows.size < limit) rows.add(row.toList())
            }
            row = ArrayList()
        }
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (quoted) {
                if (char != '"') {
                    field.append(char)
                    index++
                    continue
                }
                if (index + 1 < text.length && text[index + 1] == '"') {
                    field.append('"')
                    index += 2
                    continue
                }
                quoted = false
                index++
                continue
            }
            if (char == '"' && field.isEmpty()) {
                quoted = true
                index++
                continue
            }
            if (char == delimiter) {
                row.add(field.toString())
                field = StringBuilder()
                index++
                continue
            }
            if (char == '\r') {
                index++
                continue
            }
            if (char == '\n') {
                endRow()
                index++
                continue
            }
            field.append(char)
            index++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) endRow()
        val columns = rows.maxOfOrNull { it.size } ?: 0
        return ParsedTable(rows = rows, total = total, columns = columns)
    }

    /** Table body delimiter (`artifact-view.tsx:362`): `.tsv` is tab, everything else comma. */
    fun tableDelimiter(path: String): Char = if (extension(path) == "tsv") '\t' else ','
}

/**
 * Open-file tab strip model, the pure half of upstream `session/files/tab.tsx`
 * (`SortableTab`, `@dnd-kit` reorder) and `session/helpers.ts:174-179`
 * (`getTabReorderIndex`). Tabs hold normalized absolute paths; order is the
 * user's drag order and is preserved across opens (a re-open activates the
 * existing tab instead of appending).
 */
object FileTabs {

    /** `open-artifact.tsx:70-74` `openTab`: an already-open path just activates. */
    fun open(tabs: List<String>, path: String): List<String> =
        if (path in tabs) tabs else tabs + path

    fun close(tabs: List<String>, path: String): List<String> =
        tabs.filterNot { it == path }

    /** `getTabReorderIndex` (`helpers.ts:174-179`) applied as a move; unknown keys are a no-op. */
    fun reorder(tabs: List<String>, from: String, to: String): List<String> {
        val fromIndex = tabs.indexOf(from)
        val toIndex = tabs.indexOf(to)
        if (fromIndex == -1 || toIndex == -1 || fromIndex == toIndex) return tabs
        val next = tabs.toMutableList()
        next.removeAt(fromIndex)
        next.add(toIndex, from)
        return next
    }

    /**
     * Activation after [closed] (at [closedIndex] before the removal) is gone:
     * a non-active close keeps [wasActive], the active close steps to the tab
     * that slid into its slot, else the new last tab.
     */
    fun activeAfterClose(wasActive: String?, closed: String, closedIndex: Int, remaining: List<String>): String? {
        if (remaining.isEmpty()) return null
        if (wasActive != null && wasActive != closed && wasActive in remaining) return wasActive
        return remaining.getOrElse(closedIndex) { remaining.last() }
    }

    /** Tab label, upstream `getFilename(file.pathFromTab(tab))` (`session-mobile-files.tsx:73`). */
    fun displayName(path: String): String = ReviewDiff.basename(path)
}
