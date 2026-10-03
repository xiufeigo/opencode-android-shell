package com.xiufeigo.opencodeshell.ui.markdown

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * A-1: one `![alt](src)` of the markdown body — upstream's `<img>` plus the
 * `image-preview.tsx` dialog behind it.
 *
 * Behaviour, in upstream's order (`markdown-image.ts:26-60`, `markdown-cache.tsx:31-39`):
 * - the source is classified by [MarkdownImages.decide]; a rejected one (unsafe
 *   scheme, unknown/undecodable format) renders the **alt text**, exactly the
 *   inert `<img alt>` upstream leaves behind;
 * - the bytes come from [LocalMarkdownImageReader] (null → alt text): local
 *   paths through `GET /api/fs/read/<rel>`, remote `http(s)` URLs without
 *   credentials;
 * - the decoded bitmap is drawn fit-to-width with a height cap, and **tapping
 *   it opens the full-size preview** (`image-preview.tsx`'s dialog; the phone
 *   has no hover preview, so the tap is the whole interaction — same rule as
 *   the composer's attachment thumbnails);
 * - a read that **failed** is not final: the effect re-runs on its own per
 *   [MarkdownImages.Retry], and the failed body is a tap-to-retry target on top
 *   of that. Upstream gets this for free (its browser re-requests the `img` on
 *   every markdown re-render); a Compose body outlives every re-render of its
 *   row, so the re-attempt has to be scheduled here.
 */
@Composable
internal fun MarkdownImageView(
    alt: String,
    src: String,
    color: Color,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    first: Boolean,
    last: Boolean,
) {
    val reader = LocalMarkdownImageReader.current
    val retryLabel = stringResource(R.string.retry)
    // The attempt counter *is* the retry trigger: the effect below keys on it,
    // and both the automatic schedule and a tap on the failed body bump it.
    var attempt by remember(src, reader) { mutableIntStateOf(0) }
    var failed by remember(src, reader) { mutableStateOf(false) }
    var bitmap by remember(src, reader) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(src, reader, attempt) {
        if (reader == null) return@LaunchedEffect
        val decoded = reader.read(src)?.let { withContext(Dispatchers.IO) { decodeMarkdownImage(it) } }
        if (decoded != null) {
            bitmap = decoded
            failed = false
            return@LaunchedEffect
        }
        failed = true
        val next = MarkdownImages.Retry.nextAttempt(attempt + 1)
        if (next != null) {
            delay(MarkdownImages.Retry.delayMillis(next))
            attempt = next
        }
    }
    val image = bitmap
    if (image == null) {
        // Loading, unreadable, or no reader wired: the alt text is the whole
        // fallback (the pre-A-1 rendering), never an empty gap. A body whose
        // read failed stays tappable to re-read (see the class note); an alt-less
        // one shows the retry label instead of nothing, so the way back is never
        // invisible.
        val retryable = failed && MarkdownImages.Retry.canRetry(reader, src)
        if (alt.isNotEmpty() || retryable) {
            Text(
                text = if (alt.isEmpty()) retryLabel else alt,
                color = color,
                fontSize = fontSize,
                lineHeight = lineHeight,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = if (last) 0.dp else 12.dp)
                    .then(
                        if (retryable) {
                            Modifier.clickable(role = Role.Button) { attempt += 1 }
                        } else {
                            Modifier
                        },
                    ),
            )
        }
        return
    }
    var preview by remember(image) { mutableStateOf(false) }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (first) 0.dp else 4.dp, bottom = if (last) 0.dp else 12.dp),
    ) {
        val ratio = image.width.toFloat() / image.height.toFloat()
        val height = (maxWidth / ratio).coerceAtMost(MAX_IMAGE_HEIGHT)
        val width = (height * ratio).coerceAtMost(maxWidth)
        val description = alt.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.oc_markdown_image_description)
        Image(
            bitmap = image.asImageBitmap(),
            contentDescription = description,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(width, height)
                .clip(OcTheme.shapes.md)
                .semantics { contentDescription = description }
                .clickable(role = Role.Button) { preview = true },
        )
    }
    if (preview) {
        MarkdownImagePreview(bitmap = image, alt = alt) { preview = false }
    }
}

/**
 * `image-preview.tsx:1-30`'s dialog (the same in-app overlay the composer's
 * attachment preview uses): the decoded image over a scrim, dismissed by the
 * scrim or the close button.
 */
@Composable
private fun MarkdownImagePreview(bitmap: Bitmap, alt: String, onDismiss: () -> Unit) {
    val c = OcTheme.colors
    val description = alt.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.oc_markdown_image_description)
    // Hoisted: `semantics {}` is not a composable scope. The label rides the
    // clickable Box below, not the `Icon` inside it — only the node carrying
    // ACTION_CLICK is announced as a button.
    val closeLabel = stringResource(R.string.oc_attach_preview_close)
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = PREVIEW_SCRIM_ALPHA))
                .clickable(onClick = onDismiss)
                .semantics { contentDescription = closeLabel },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = description,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(PREVIEW_PADDING)
                    .clip(OcTheme.shapes.md),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(PREVIEW_PADDING)
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(c.layer01)
                    .clickable(role = Role.Button, onClick = onDismiss)
                    .semantics { contentDescription = closeLabel },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_oc_close),
                    contentDescription = null,
                    tint = c.iconBase,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * Downsampled decode of one image body. [TARGET_PX] keeps a phone-sized bitmap
 * (the same rule as `AttachmentPreviewRow.decodePreview` / `ArtifactImage`), and
 * a body Android cannot decode answers null so the caller keeps the alt text.
 */
internal fun decodeMarkdownImage(bytes: ByteArray, targetPx: Int = TARGET_PX): Bitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        null
    } else {
        var sample = 1
        while (bounds.outWidth / sample > targetPx || bounds.outHeight / sample > targetPx) sample *= 2
        BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        )
    }
} catch (_: OutOfMemoryError) {
    null
} catch (_: IllegalArgumentException) {
    null
}

/** Longest edge of the decoded bitmap: crisp on a phone, bounded in memory. */
private const val TARGET_PX = 2048

/** Height cap of the inline image (`markdown.css` has none; a phone needs one). */
private val MAX_IMAGE_HEIGHT = 320.dp

private val PREVIEW_PADDING = 16.dp

private const val PREVIEW_SCRIM_ALPHA = 0.9f
