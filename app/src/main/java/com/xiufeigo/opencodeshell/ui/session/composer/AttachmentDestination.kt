package com.xiufeigo.opencodeshell.ui.session.composer

/**
 * Where a prompt attachment is headed: the three branches of upstream
 * `packages/app/src/composer/attachments/destination.ts:11-31` crossed with the
 * branch order of `composer/attachments/attachments.ts:65-70` (`add()`):
 *
 *  1. **inline** — the selected model reads the modality natively
 *     (`capabilities.input`) and the file is within [MAX_INLINE_BYTES]; the bytes
 *     travel with the prompt (`data:` today, upstream's draft-store blob).
 *  2. **local path** — the server shares the client's filesystem (`server.isLocal`)
 *     and the source path resolves as-is; the model opens it via its tools.
 *  3. **upload** — `POST /api/experimental/fs/write` streams the file into the
 *     server's temp dir and the returned absolute path becomes the attachment.
 *
 * This object is the pure decision seam. [AttachmentReader] executes it and
 * answers with an [AttachmentOutcome]; branch 3 streams through
 * `AttachmentUploads.uploader` (`V2Api.fsWriteUpload` in `v2/V2ApiFsWrite.kt`).
 * Branch 2 is unreachable for SAF/clipboard picks on Android — they arrive as
 * `content://` URIs with no server-usable path; only a `file://` drop with
 * `local = true` yields [Route.LOCAL_PATH].
 */
internal object AttachmentDestination {

    /** Upstream `attachments.ts` `MAX_INLINE_BYTES` — past this, media takes the path route. */
    const val MAX_INLINE_BYTES = 20L * 1024 * 1024

    /** `destination.ts` `input` — modalities the selected model reads natively. */
    data class Input(val image: Boolean = false, val pdf: Boolean = false)

    /** The three `attachments.ts:65-70` branches, in order. */
    enum class Route { INLINE, LOCAL_PATH, UPLOAD }

    private val nativeImages = setOf("image/png", "image/jpeg", "image/gif", "image/webp")

    /**
     * `attachments.ts:208-211` `native()`: only the four raster image types and
     * PDF can ride inline as model input; every other mime is a tool-opened path.
     */
    fun isNative(mime: String, input: Input): Boolean = when {
        mime in nativeImages -> input.image
        mime == "application/pdf" -> input.pdf
        else -> false
    }

    /**
     * `add()`'s branch order. [hasSourcePath] is upstream's
     * `getPathForFile?.(file) || undefined` — always false for Android
     * `content://` picks.
     */
    fun route(
        mime: String,
        size: Long,
        input: Input,
        local: Boolean,
        hasSourcePath: Boolean,
    ): Route = when {
        isNative(mime, input) && size <= MAX_INLINE_BYTES -> Route.INLINE
        local && hasSourcePath -> Route.LOCAL_PATH
        else -> Route.UPLOAD
    }

    /**
     * Second half of `add()`: when the [Route.INLINE] pack attempt failed
     * ([packed] false — bytes past `AttachmentPolicy.MAX_ATTACHMENT_BYTES`, or
     * the stream hid its size), fall through to the path branches instead of
     * rejecting the pick. Every other route is already terminal.
     */
    fun escalate(route: Route, packed: Boolean, local: Boolean, hasSourcePath: Boolean): Route = when {
        route != Route.INLINE || packed -> route
        local && hasSourcePath -> Route.LOCAL_PATH
        else -> Route.UPLOAD
    }
}

/**
 * What [AttachmentDestination.route] decided for one pick — the typed handoff
 * the view model turns into prompt parts (upstream `composer/state.ts`):
 * [Inline] → `ImageAttachmentPart` (`data:`/blob url), [LocalPath]/[Uploaded] →
 * `PathAttachmentPart` (`{filename, mime, path}`, sent as `file://…` in
 * `composer/request.ts:74-76`).
 *
 * [mime] and [filename] ride on every variant because `PathAttachmentPart`
 * carries them and the chip row labels with [filename].
 */
internal sealed interface AttachmentOutcome {
    val mime: String
    val filename: String

    /** Bytes ride with the prompt; [uri] is the `data:` URL. */
    data class Inline(
        val uri: String,
        override val mime: String,
        override val filename: String,
    ) : AttachmentOutcome

    /** The pick's own path, resolvable on the server (`Route.LOCAL_PATH`). */
    data class LocalPath(
        val path: String,
        override val mime: String,
        override val filename: String,
    ) : AttachmentOutcome

    /** `fsWrite`'s resolved absolute path on the server (`Route.UPLOAD`). */
    data class Uploaded(
        val path: String,
        override val mime: String,
        override val filename: String,
    ) : AttachmentOutcome
}
