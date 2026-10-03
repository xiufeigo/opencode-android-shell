package com.xiufeigo.opencodeshell.ui.session.composer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import com.xiufeigo.opencodeshell.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * SAF (`OpenMultipleDocuments`) → [ChatViewModel.Attachment] reader: display
 * name, mime, bytes and the `data:` URL packing — and since the destination
 * wiring, the [AttachmentDestination] three-branch decision for every pick
 * (result as [AttachmentOutcome], see [Result]).
 *
 * Ported from `ChatView.readAttachment` / `downscaleOrRaw` / `resolveMime` /
 * `queryDisplayName` (L468-578). Two deliberate differences:
 *  1. **mime follows the bytes** — ChatView kept the *original* mime even after
 *     re-encoding the bitmap to JPEG (`data:image/png;base64,<jpeg bytes>`), so
 *     the server could not decode it. Here re-encoded images report
 *     `image/jpeg`.
 *  2. reading happens on [Dispatchers.IO] inside a suspending call instead of a
 *     raw `Thread`, so the caller (composer) can cancel with its scope.
 */
internal object AttachmentReader {

    /** Images are downscaled to a 2048 px long edge before base64 (`ChatView.MAX_IMAGE_EDGE`). */
    private const val MAX_IMAGE_EDGE = 2048

    /** `ChatView` re-encode quality. */
    private const val JPEG_QUALITY = 85

    private const val TAG = "AttachmentReader"

    /**
     * Outcome of one pick round: what was ingested, what must be toasted.
     *
     * [outcomes] is the typed three-branch handoff (upstream `PathAttachmentPart`
     * / `ImageAttachmentPart`); [attachments] is the legacy chip list and today
     * carries **only** [AttachmentOutcome.Inline] picks, because
     * `ChatViewModel.Attachment` is `data:`-URL-shaped and its prompt
     * serialization has no path type yet (see the delivery notes).
     */
    data class Result(
        val attachments: List<ChatViewModel.Attachment>,
        val tooLarge: List<String>,
        val failed: Int,
        val outcomes: List<AttachmentOutcome> = emptyList(),
    )

    /**
     * Reads every picked document, skipping (and reporting) the ones that fail.
     *
     * W5-C B6: [onProgress] is invoked once per URI (before it is read) with the
     * 1-based index, the total count and a display hint — the composer's upload
     * row renders it and offers cancel. Cancelling the calling coroutine stops the
     * round at the next document (the check is per iteration, so a huge single
     * file still has to finish decoding).
     */
    suspend fun readAll(
        context: Context,
        uris: List<Uri>,
        input: AttachmentDestination.Input = AttachmentDestination.Input(image = true, pdf = true),
        local: Boolean = false,
        uploader: AttachmentUploader? = null,
        onProgress: (index: Int, total: Int, label: String?) -> Unit = { _, _, _ -> },
    ): Result = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val outcomes = mutableListOf<AttachmentOutcome>()
        val tooLarge = mutableListOf<String>()
        var failed = 0
        for ((position, uri) in uris.withIndex()) {
            currentCoroutineContext().ensureActive()
            onProgress(position + 1, uris.size, uri.lastPathSegment)
            when (val read = read(appContext, uri, input, local, uploader)) {
                is Read.Ok -> outcomes += read.outcome
                is Read.TooLarge -> tooLarge += read.filename
                Read.Failed -> failed++
            }
        }
        Result(
            attachments = outcomes.mapNotNull { it.inlineChip() },
            tooLarge = tooLarge,
            failed = failed,
            outcomes = outcomes,
        )
    }

    private sealed interface Read {
        data class Ok(val outcome: AttachmentOutcome) : Read
        data class TooLarge(val filename: String) : Read
        data object Failed : Read
    }

    /** The only outcome `ChatViewModel.Attachment` can carry today (`data:` URL chips). */
    private fun AttachmentOutcome.inlineChip(): ChatViewModel.Attachment? = when (this) {
        is AttachmentOutcome.Inline -> ChatViewModel.Attachment(mime = mime, filename = filename, dataUrl = uri)
        is AttachmentOutcome.LocalPath -> null
        is AttachmentOutcome.Uploaded -> null
    }

    /**
     * One pick through `attachments.ts` `add()`: [AttachmentDestination.route],
     * an inline pack attempt when it chose [AttachmentDestination.Route.INLINE],
     * then [AttachmentDestination.escalate] into the path branches.
     */
    private fun read(
        context: Context,
        uri: Uri,
        input: AttachmentDestination.Input,
        local: Boolean,
        uploader: AttachmentUploader?,
    ): Read = try {
        val resolver = context.contentResolver
        val name = displayName(context, uri) ?: "attachment-${System.currentTimeMillis()}"
        val mime = AttachmentMime.resolve(resolver.getType(uri), name) { extension ->
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        }
        val sourcePath = uri.path?.takeIf { uri.scheme == "file" }
        val hasSourcePath = sourcePath != null
        val declared = declaredSize(context, uri)
        val route = AttachmentDestination.route(
            mime = mime,
            size = declared ?: 0L,
            input = input,
            local = local,
            hasSourcePath = hasSourcePath,
        )
        val packed = if (route == AttachmentDestination.Route.INLINE) {
            inlinePack(context, uri, name, mime, declared)
        } else {
            null
        }
        when (AttachmentDestination.escalate(route, packed is Read.Ok, local, hasSourcePath)) {
            AttachmentDestination.Route.INLINE -> packed ?: Read.Failed
            AttachmentDestination.Route.LOCAL_PATH ->
                Read.Ok(AttachmentOutcome.LocalPath(sourcePath!!, mime, name))

            AttachmentDestination.Route.UPLOAD ->
                uploadPick(context, uri, name, mime, uploader) ?: packed ?: inlinePack(context, uri, name, mime, declared)
        }
    } catch (e: Exception) {
        // SecurityException (revoked grant), IOException, OOM on decode…
        Log.w(TAG, "attachment read failed: ${e.message}")
        Read.Failed
    }

    /**
     * `data:` URL outcome, or the packer's verdict: [Read.TooLarge] past
     * `AttachmentPolicy.MAX_ATTACHMENT_BYTES` (rejected before the bytes are
     * held when the size was declared). Callers escalate a refusal instead of
     * ending the pick — see [AttachmentDestination.escalate].
     *
     * The packed mime goes through [AttachmentPolicy.packedMime]: a read that
     * produced **no bytes at all** for an image (the SAF pick of a file that
     * exists but was never written) has nothing the server could decode, so it
     * degrades to the binary type and stays a visible file chip rather than
     * riding along as an empty `data:image/…` reference.
     */
    private fun inlinePack(
        context: Context,
        uri: Uri,
        name: String,
        mime: String,
        declared: Long?,
    ): Read {
        val payload = bytesFor(context, uri, mime, declared) ?: return Read.TooLarge(name)
        val (bytes, effectiveMime) = payload
        if (AttachmentPolicy.isTooLarge(bytes.size)) return Read.TooLarge(name)
        val attachment = AttachmentPolicy.attachment(
            AttachmentPolicy.packedMime(effectiveMime, bytes),
            name,
            bytes,
        )
        return Read.Ok(
            AttachmentOutcome.Inline(
                uri = attachment.dataUrl,
                mime = attachment.mime,
                filename = attachment.filename,
            ),
        )
    }

    /**
     * `Route.UPLOAD`: stream the pick through [AttachmentUploader] (`fsWrite`)
     * and hand back the server path. Null when no sink is wired or the call
     * fails — the caller then falls back to [inlinePack] (pre-destination
     * behaviour), so a pick never disappears while the path outcome type is
     * still landing in `ChatViewModel`.
     */
    private fun uploadPick(
        context: Context,
        uri: Uri,
        name: String,
        mime: String,
        uploader: AttachmentUploader?,
    ): Read.Ok? {
        val sink = uploader ?: return null
        return try {
            val path = sink.upload(name, mime) {
                context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("cannot open $uri")
            }
            if (path.isBlank()) {
                Log.w(TAG, "fsWrite returned no path for $name")
                null
            } else {
                Read.Ok(AttachmentOutcome.Uploaded(path, mime, name))
            }
        } catch (e: Exception) {
            Log.w(TAG, "attachment upload failed: ${e.message}")
            null
        }
    }

    /**
     * Raw bytes, or the downscaled JPEG for images; null when a raw read
     * proves the file is past the cap (rejected before the bytes are held).
     * `ImageDecoder` needs API 28; on 26/27 the raw bytes are kept
     * (`NoSuchMethodError` is not an `Exception` and would escape the catch).
     */
    private fun bytesFor(context: Context, uri: Uri, mime: String, declared: Long?): Pair<ByteArray, String>? {
        if (!AttachmentMime.isImage(mime) || Build.VERSION.SDK_INT < 28) {
            return rawBytes(context, uri, declared)?.let { it to mime }
        }
        return try {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val maxSide = maxOf(info.size.width, info.size.height)
                if (maxSide > MAX_IMAGE_EDGE) {
                    val scale = MAX_IMAGE_EDGE.toFloat() / maxSide
                    decoder.setTargetSize(
                        (info.size.width * scale).toInt().coerceAtLeast(1),
                        (info.size.height * scale).toInt().coerceAtLeast(1),
                    )
                }
            }
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            bitmap.recycle()
            out.toByteArray() to "image/jpeg"
        } catch (e: Exception) {
            Log.w(TAG, "image downscale failed, keeping raw bytes: ${e.message}")
            rawBytes(context, uri, declared)?.let { it to mime }
        }
    }

    /**
     * Null when the file is already over the cap on its *declared* size — a
     * hundreds-of-MB pick is then rejected without opening the stream — else a
     * buffered read that aborts at the cap when the provider hid its size
     * (the unbounded slurp used to OOM first, past every `catch (e: Exception)`).
     */
    private fun rawBytes(context: Context, uri: Uri, declared: Long?): ByteArray? {
        if (declared != null && AttachmentPolicy.isTooLarge(declared)) return null
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("cannot open $uri")
        return input.use { AttachmentPolicy.readCapped(it) }
    }

    /** `OpenableColumns.DISPLAY_NAME`; null when the provider hides the cursor. */
    private fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    } catch (e: Exception) {
        Log.w(TAG, "display name query failed: ${e.message}")
        null
    }

    /**
     * Declared byte size before anything is buffered: `OpenableColumns.SIZE`,
     * else the asset file descriptor. Null when the provider hides both —
     * then only [AttachmentPolicy.readCapped]'s mid-stream cap applies.
     */
    private fun declaredSize(context: Context, uri: Uri): Long? {
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (index >= 0 && !cursor.isNull(index)) {
                        val size = cursor.getLong(index)
                        if (size >= 0) return size
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "size query failed: ${e.message}")
        }
        return try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                afd.length.takeIf { it >= 0 }
            }
        } catch (e: Exception) {
            Log.w(TAG, "size fd failed: ${e.message}")
            null
        }
    }
}
