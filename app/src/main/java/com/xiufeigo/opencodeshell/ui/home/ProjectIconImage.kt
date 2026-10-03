package com.xiufeigo.opencodeshell.ui.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.ui.markdown.decodeMarkdownImage
import com.xiufeigo.opencodeshell.ui.session.composer.AttachmentPolicy
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * `Project.icon.override` — the custom project icon image (B-14).
 *
 * Upstream `settings/workspaces/project.tsx:63-101` uploads one through a
 * hidden `file` input whose `accept` is the image mime family / a drop target,
 * reads it with `FileReader.readAsDataURL` and stores the resulting `data:` URL
 * in `icon.override` (`project-model.ts:76-134` `selectFile`/`saveIcon`). The
 * server carries that field on the wire (`schema/src/project.ts:20-25`,
 * `core/src/project.ts:216`), so Android does the same thing through SAF
 * instead of inventing a local cache:
 *
 * | step | here |
 * |---|---|
 * | pick | `ActivityResultContracts.GetContent()` filtered to images ([HomeProjectEditDialog]) |
 * | downscale | longest edge ≤ [MAX_EDGE] (`ImageDecoder` on API 28+, `BitmapFactory` `inSampleSize` below it) |
 * | pack | `data:<mime>;base64,…` via [AttachmentPolicy.dataUrl] (the same packer the composer's attachments use) |
 * | store | `PATCH /api/project/{id}` `icon.override` ([HomeProjectEdit.Patch]) |
 * | render | [ProjectIconView] decodes it and draws it before the color/initial fallback |
 *
 * PNG keeps an icon's alpha; an image without any (a JPEG photo) travels as
 * JPEG 85 so a pick does not become a multi-hundred-KB PATCH body. [MAX_EDGE]
 * bounds the payload either way — the avatar is never drawn larger than 32 dp
 * — and [MAX_STORED_BYTES] bounds the **packed bytes** on top of that, which is
 * what lets a client-local (server-unregistered) project actually persist and
 * read its icon back (`research/v97_fix/PROJECT_ICON_PERSIST.md`, B11).
 */
internal object ProjectIconImage {

    /** Longest edge of the stored image; upstream's hint asks for 128×128. */
    const val MAX_EDGE = 256

    /** Decode target for the drawn avatar (32 dp at 3× density is 96 px). */
    const val TARGET_PX = 128

    /**
     * Ceiling on the **packed** icon payload — the B11 fix's load-bearing
     * number (`research/v97_fix/PROJECT_ICON_PERSIST.md`).
     *
     * Why the image needs a bound the other three fields do not: the
     * client-local `ProjectMeta` is persisted as **one** JSON string in **one**
     * `SharedPreferences` value ([HomeProjectStore.update] →
     * `HomeProjectRecords.encode`). `name` / `iconColor` / `startup` are tens
     * of bytes; an icon `data:` URL is base64 of a downsampled bitmap, so a
     * 256×256 PNG lands at several hundred KB. That single oversized member is
     * what made the image the *only* one of the four fields that failed to
     * land and read back on a real device (name/colour/startup saved fine
     * through the very same `setMeta` call).
     *
     * The budget costs no visible quality: the avatar is never drawn larger
     * than 32 dp — 96 px at 3× density, and [TARGET_PX] decodes it to 128 px —
     * so 48 KB of packed bytes is far past the point where a pick is
     * indistinguishable at avatar scale. It is enforced by **re-encoding**
     * ([fitBudget] shrinks the bitmap / drops JPEG quality), never by cutting
     * the `data:` URL, because a truncated base64 body decodes to a corrupt
     * image — the same invisible-avatar symptom in another costume.
     */
    const val MAX_STORED_BYTES = 48 * 1024

    /** Longest edge the budget search will shrink to before giving up. */
    private const val MIN_EDGE = 48

    /** JPEG quality for an icon without transparency. */
    private const val JPEG_QUALITY = 85

    /** PNG is lossless, so its quality argument is only a filter hint. */
    private const val PNG_QUALITY = 100

    /** JPEG qualities [fitBudget] walks down when a PNG will not fit. */
    private val JPEG_QUALITIES = intArrayOf(JPEG_QUALITY, 75, 60, 45)

    /**
     * The `data:` URL's own mime when it is an image one, else null — a stored
     * `override` that is not an image data URL (a stray `https://…`, a bare
     * base64 blob) renders as the color/initial fallback instead of a blank
     * square.
     */
    fun mimeOf(override: String?): String? = override
        ?.takeIf { it.startsWith("data:") }
        ?.substringAfter("data:")
        ?.substringBefore(';')
        ?.trim()
        ?.takeIf { it.startsWith("image/") }

    /**
     * The bytes behind an `override`, or null when it is not a decodable image
     * data URL. Pure (`java.util.Base64`, minSdk 26) so the precedence rule is
     * JVM-testable; the payload split itself is [AttachmentPolicy.imagePayload],
     * the same helper the composer's image chips decode from.
     */
    fun bytesOf(override: String?): ByteArray? {
        val mime = mimeOf(override) ?: return null
        val payload = AttachmentPolicy.imagePayload(mime, override) ?: return null
        return try {
            Base64.getDecoder().decode(payload)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /**
     * Downsampled `data:` URL for one SAF pick, or null when the pick is not an
     * image / cannot be read / cannot be decoded. Blocking — callers run it on
     * `Dispatchers.IO`.
     *
     * The result is bounded by [MAX_STORED_BYTES] ([fitBudget]) because this
     * string is persisted whole into the client-local project record; see that
     * constant for why the image and not the other three fields is the one that
     * needs a size ceiling.
     */
    fun read(context: Context, uri: Uri): String? {
        val resolver = context.contentResolver
        val mime = imageMime(context, uri) ?: return null
        return try {
            val bitmap = decode(resolver, uri) ?: return null
            val packed = fitBudget(bitmap)
            bitmap.recycle()
            packed?.let { AttachmentPolicy.dataUrl(it.first, it.second) }
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    /**
     * The smallest still-legible packing of [bitmap] that fits
     * [MAX_STORED_BYTES], as `(mime, bytes)` — or null when even the floor
     * ([MIN_EDGE], lowest JPEG quality) is over budget, which leaves the
     * project with no image rather than an unrenderable one.
     *
     * The order is deliberate: **alpha first** (an icon's transparency is its
     * shape, so a PNG is kept while it fits), then **size**, then quality. A
     * re-encode is the only shrink applied — the `data:` URL is never cut,
     * because a base64 body truncated mid-quantum decodes to a corrupt bitmap
     * and the row would fall back to the letter badge, which is the very bug
     * this bounds.
     */
    private fun fitBudget(bitmap: Bitmap): Pair<String, ByteArray>? {
        if (bitmap.width <= 0 || bitmap.height <= 0) return null
        val png = bitmap.hasAlpha()
        var edge = maxOf(bitmap.width, bitmap.height)
        var fallback: Pair<String, ByteArray>? = null
        while (edge >= MIN_EDGE) {
            val scaled = scaleTo(bitmap, edge)
            val asPng = compress(scaled, Bitmap.CompressFormat.PNG, PNG_QUALITY)
            if (png && asPng != null && asPng.size <= MAX_STORED_BYTES) {
                return "image/png" to asPng
            }
            for (quality in JPEG_QUALITIES) {
                val asJpeg = compress(scaled, Bitmap.CompressFormat.JPEG, quality)
                if (asJpeg != null && asJpeg.isNotEmpty()) {
                    if (asJpeg.size <= MAX_STORED_BYTES) return "image/jpeg" to asJpeg
                    // Keep the best over-budget attempt: if nothing at any size
                    // fits, the caller gets null rather than this.
                    if (fallback == null || asJpeg.size < fallback.second.size) {
                        fallback = "image/jpeg" to asJpeg
                    }
                }
            }
            if (scaled !== bitmap) scaled.recycle()
            edge = (edge * 3) / 4
        }
        return null
    }

    /** The [bitmap] with its longest edge ≤ [edge] (never upscaled). */
    private fun scaleTo(bitmap: Bitmap, edge: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= edge) return bitmap
        val scale = edge.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    /** One [format] encoding, or null when the bitmap cannot be packed. */
    private fun compress(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int): ByteArray? = try {
        ByteArrayOutputStream().use { out ->
            if (bitmap.compress(format, quality, out)) out.toByteArray().takeIf { it.isNotEmpty() } else null
        }
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }

    /**
     * The picked document's image mime: the resolver's own type, else the
     * display name's extension (`ChatView.resolveMime`'s fallback — a provider
     * that reports nothing for a `.png` would otherwise make the pick a silent
     * no-op). Null for a non-image.
     */
    private fun imageMime(context: Context, uri: Uri): String? {
        val direct = context.contentResolver.getType(uri)
        if (direct != null && direct.startsWith("image/")) return direct
        val name = displayName(context, uri) ?: return null
        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension.isEmpty()) return null
        return android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(extension)
            ?.takeIf { it.startsWith("image/") }
    }

    /** `OpenableColumns.DISPLAY_NAME` of one SAF document. */
    private fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    } catch (_: Exception) {
        null
    }

    /**
     * The picked image, longest edge ≤ [MAX_EDGE]. `ImageDecoder` (API 28+)
     * scales during decode — the same rule as `ChatView.downscaleOrRaw`; on
     * 26/27 `BitmapFactory` bounds-decodes first and re-decodes with
     * `inSampleSize`, the pattern `decodeMarkdownImage` uses.
     */
    private fun decode(resolver: android.content.ContentResolver, uri: Uri): Bitmap? {
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            val source = ImageDecoder.createSource(resolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val maxSide = maxOf(info.size.width, info.size.height)
                if (maxSide > MAX_EDGE) {
                    val scale = MAX_EDGE.toFloat() / maxSide
                    decoder.setTargetSize(
                        (info.size.width * scale).toInt().coerceAtLeast(1),
                        (info.size.height * scale).toInt().coerceAtLeast(1),
                    )
                }
            }
        }
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > MAX_EDGE || bounds.outHeight / sample > MAX_EDGE) sample *= 2
        return BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }
}

/**
 * `ProjectAvatar` surface (`ui/src/data-display/project-avatar`) as the shell
 * draws it: the custom image when one is set, else the color variant's fill
 * with the name's first grapheme, else the neutral surface (and, for the
 * 最近关闭 stack, the outline style).
 *
 * Precedence is upstream's `ProjectIcon`/`ProjectAvatar` chain — `override` →
 * `color` → fallback. The image is decoded once per [override] on the IO
 * dispatcher and downsampled to avatar scale; an `override` that is not a
 * decodable image data URL falls through to the color/initial, never to a
 * blank square. The tinted fill keeps upstream's `--v2-avatar-fg: #ffffff`
 * initial; the surface's per-variant hairline border is the one piece this
 * shell has no token for.
 */
@Composable
internal fun ProjectIconView(
    name: String,
    iconColor: String?,
    override: String?,
    size: Dp,
    outline: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    val shape = OcTheme.shapes.sm
    val tint = HomeProjects.iconColorHex(iconColor)?.let { Color(it) }
    var image by remember(override) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(override) {
        image = if (override == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                ProjectIconImage.bytesOf(override)
                    ?.let { decodeMarkdownImage(it, targetPx = ProjectIconImage.TARGET_PX) }
                    ?.asImageBitmap()
            }
        }
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(if (outline) c.bgBase else tint ?: c.layer02)
            .then(if (outline) Modifier.border(0.5.dp, c.borderMuted, shape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = image
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = name.firstOrNull()?.uppercaseChar()?.toString().orEmpty(),
                color = if (tint != null && !outline) Color.White else c.textMuted,
                fontSize = 10.sp,
                maxLines = 1,
            )
        }
    }
}
