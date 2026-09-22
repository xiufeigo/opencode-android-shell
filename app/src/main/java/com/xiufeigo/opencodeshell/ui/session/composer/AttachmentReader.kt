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
 * name, mime, bytes and the `data:` URL packing.
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

    /** Outcome of one pick round: what was ingested, what must be toasted. */
    data class Result(
        val attachments: List<ChatViewModel.Attachment>,
        val tooLarge: List<String>,
        val failed: Int,
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
        onProgress: (index: Int, total: Int, label: String?) -> Unit = { _, _, _ -> },
    ): Result = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val attachments = mutableListOf<ChatViewModel.Attachment>()
        val tooLarge = mutableListOf<String>()
        var failed = 0
        for ((position, uri) in uris.withIndex()) {
            currentCoroutineContext().ensureActive()
            onProgress(position + 1, uris.size, uri.lastPathSegment)
            when (val read = read(appContext, uri)) {
                is Read.Ok -> attachments += read.attachment
                is Read.TooLarge -> tooLarge += read.filename
                Read.Failed -> failed++
            }
        }
        Result(attachments, tooLarge, failed)
    }

    private sealed interface Read {
        data class Ok(val attachment: ChatViewModel.Attachment) : Read
        data class TooLarge(val filename: String) : Read
        data object Failed : Read
    }

    private fun read(context: Context, uri: Uri): Read = try {
        val resolver = context.contentResolver
        val name = displayName(context, uri) ?: "attachment-${System.currentTimeMillis()}"
        val mime = AttachmentMime.resolve(resolver.getType(uri), name) { extension ->
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        }
        val (bytes, effectiveMime) = bytesFor(context, uri, mime)
        if (AttachmentPolicy.isTooLarge(bytes.size)) {
            Read.TooLarge(name)
        } else {
            Read.Ok(AttachmentPolicy.attachment(effectiveMime, name, bytes))
        }
    } catch (e: Exception) {
        // SecurityException (revoked grant), IOException, OOM on decode…
        Log.w(TAG, "attachment read failed: ${e.message}")
        Read.Failed
    }

    /**
     * Raw bytes, or the downscaled JPEG for images. `ImageDecoder` needs API 28;
     * on 26/27 the raw bytes are kept (`NoSuchMethodError` is not an
     * `Exception` and would escape the catch).
     */
    private fun bytesFor(context: Context, uri: Uri, mime: String): Pair<ByteArray, String> {
        if (!AttachmentMime.isImage(mime) || Build.VERSION.SDK_INT < 28) {
            return rawBytes(context, uri) to mime
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
            rawBytes(context, uri) to mime
        }
    }

    private fun rawBytes(context: Context, uri: Uri): ByteArray =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("cannot open $uri")

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
}
