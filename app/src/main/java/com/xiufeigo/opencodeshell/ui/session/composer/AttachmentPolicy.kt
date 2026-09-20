package com.xiufeigo.opencodeshell.ui.session.composer

import com.xiufeigo.opencodeshell.ChatViewModel
import java.util.Base64

/**
 * Attachment rules shared by the SAF reader and the composer preview row.
 *
 * Pure Kotlin on purpose (no `android.*`): the byte → `data:` URL packing, the
 * mime fallback and the "6 × 8 MB" cap are the parts that must not regress, and
 * they are all JVM-testable here. The Android half (content resolver, bitmap
 * downscale) lives in [AttachmentReader].
 *
 * Ported from `ChatView` (`MAX_ATTACHMENTS` / `MAX_ATTACHMENT_BYTES` L1223-1224,
 * `readAttachment` L504, `ingestAttachments` L468) — deliberately 1:1 so the View
 * shell and the Compose shell accept exactly the same payloads.
 */
object AttachmentPolicy {

    /** Upstream/View cap: at most 6 chips per message. */
    const val MAX_ATTACHMENTS = 6

    /** Upstream/View cap: 8 MB of raw bytes per attachment (`data:` URL is ~4/3 of it). */
    const val MAX_ATTACHMENT_BYTES = 8 * 1024 * 1024

    /** Fallback when neither the resolver nor the file extension knows better. */
    const val OCTET_STREAM = "application/octet-stream"

    /** True when the raw payload is past [MAX_ATTACHMENT_BYTES] (strictly greater). */
    fun isTooLarge(bytes: Int): Boolean = bytes > MAX_ATTACHMENT_BYTES

    /**
     * `data:<mime>;base64,<payload>`. `java.util.Base64` (API 26+, minSdk 26) is
     * used instead of `android.util.Base64` so this exact string can be asserted
     * in a JVM test — the format is what the server parses.
     */
    fun dataUrl(mime: String, bytes: ByteArray): String =
        "data:$mime;base64," + Base64.getEncoder().encodeToString(bytes)

    /** Packs raw bytes into the [ChatViewModel.Attachment] the view model sends. */
    fun attachment(
        mime: String,
        filename: String,
        bytes: ByteArray,
    ): ChatViewModel.Attachment = ChatViewModel.Attachment(
        mime = mime,
        filename = filename,
        dataUrl = dataUrl(mime, bytes),
    )

    /** Result of [merge]: what to keep and how many picks the cap discarded. */
    data class Merge(
        val attachments: List<ChatViewModel.Attachment>,
        val dropped: Int,
    )

    /**
     * Appends `incoming` to `current`, de-duplicating on `filename + dataUrl`
     * (re-picking the same file must not add a second chip) and truncating to
     * [max]. [Merge.dropped] > 0 is the "at most N attachments per message"
     * condition the caller turns into a toast.
     */
    fun merge(
        current: List<ChatViewModel.Attachment>,
        incoming: List<ChatViewModel.Attachment>,
        max: Int = MAX_ATTACHMENTS,
    ): Merge {
        val merged = (current + incoming).distinctBy { it.filename to it.dataUrl }
        if (merged.size <= max) return Merge(merged, 0)
        return Merge(merged.take(max), merged.size - max)
    }

    /**
     * Base64 payload of an image `data:` URL, or null when [dataUrl] is not a
     * decodable image data URL (remote `file://`-less urls, non-image mime,
     * empty payload). Thumbnails are decoded from this — never from the whole
     * `data:` URL, which carries the mime prefix.
     */
    fun imagePayload(mime: String?, dataUrl: String?): String? {
        if (mime == null || !mime.startsWith("image/")) return null
        if (dataUrl == null || !dataUrl.startsWith("data:")) return null
        val comma = dataUrl.indexOf(',')
        if (comma < 0) return null
        return dataUrl.substring(comma + 1).takeIf { it.isNotEmpty() }
    }
}

/**
 * Mime resolution for SAF picks: the resolver type wins, but SAF blanks
 * (`null`) and `application/octet-stream` fall back to the file extension
 * (`ChatView.resolveMime` L563, which guards the "SAF returned octet-stream for
 * a .png" case).
 *
 * The extension lookup is injected so the rule is testable off-device
 * (`MimeTypeMap` is the device implementation, wired in [AttachmentReader]).
 */
object AttachmentMime {

    /** Lowercase extension without the dot, or null when the name has none. */
    fun extensionOf(name: String?): String? {
        val extension = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return extension.ifEmpty { null }
    }

    /** `null`/blank/`application/octet-stream` means "the resolver knows nothing". */
    fun declaredIsUsable(declared: String?): Boolean =
        !declared.isNullOrBlank() && declared != AttachmentPolicy.OCTET_STREAM

    /**
     * Resolver type → extension fallback → [AttachmentPolicy.OCTET_STREAM].
     * Never returns null: the server always needs a mime for the file part.
     */
    fun resolve(
        declared: String?,
        filename: String?,
        lookup: (String) -> String?,
    ): String {
        if (declaredIsUsable(declared)) return declared!!
        val extension = extensionOf(filename)
        val fromExtension = extension?.let(lookup)
        if (!fromExtension.isNullOrBlank()) return fromExtension
        return AttachmentPolicy.OCTET_STREAM
    }

    fun isImage(mime: String?): Boolean = mime?.startsWith("image/") == true
}
