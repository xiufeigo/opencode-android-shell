package com.xiufeigo.opencodeshell.ui.session.composer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.ChatViewModel
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Base64

/**
 * Pending-attachment preview row: `[data-slot=prompt-attachments-scroll]`
 * (`research/02` §3.4) — a horizontally scrollable strip of chips above the
 * editor.
 *
 * Geometry is the spec'd value pair:
 * - image: 58×46, radius 6, 0.5 dp inner stroke (`bg_attachment_thumb` parity)
 * - file: `layer02`, radius 6, 46 dp tall, 14 dp icon + 12 sp name
 * - remove: 16 dp circle pinned to the chip's top-end corner (the View shell
 *   pinned it at `END or TOP` inside the 58×46 frame, `ChatView` L662-686)
 *
 * Not implemented vs. the spec: the hover-only reveal (touch has no hover — the
 * badge is always visible) and the 24 dp edge fade masks over the scroll
 * container.
 */
@Composable
internal fun AttachmentPreviewRow(
    attachments: List<ChatViewModel.Attachment>,
    onRemove: (ChatViewModel.Attachment) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (attachment in attachments) {
            AttachmentChip(
                attachment = attachment,
                onRemove = { onRemove(attachment) },
            )
        }
    }
}

/** One chip + its remove badge. Keyed by the caller's list key. */
@Composable
private fun AttachmentChip(
    attachment: ChatViewModel.Attachment,
    onRemove: () -> Unit,
) {
    // The badge overhangs the chip by 4 dp (upstream `-top-1 -end-1`): reserve
    // that space inside the row so the scroll container cannot clip it.
    Box(modifier = Modifier.padding(top = BADGE_OVERHANG, end = BADGE_OVERHANG)) {
        if (AttachmentMime.isImage(attachment.mime)) {
            ImageThumbnail(attachment)
        } else {
            FileChip(attachment)
        }
        RemoveBadge(
            onClick = onRemove,
            modifier = Modifier.align(Alignment.TopEnd),
        )
    }
}

/** Image data URL → `layer02` frame while decoding, `ContentScale.Crop` once decoded. */
@Composable
private fun ImageThumbnail(attachment: ChatViewModel.Attachment) {
    val c = OcTheme.colors
    val payload = remember(attachment.dataUrl) {
        AttachmentPolicy.imagePayload(attachment.mime, attachment.dataUrl)
    }
    var bitmap by remember(payload) { mutableStateOf(AttachmentThumbs.get(payload)) }

    LaunchedEffect(payload) {
        if (payload == null || bitmap != null) return@LaunchedEffect
        val decoded = withContext(Dispatchers.IO) { decodeThumbnail(payload) }
        if (decoded != null) {
            AttachmentThumbs.put(payload, decoded)
            bitmap = decoded
        }
    }

    Box(
        modifier = Modifier
            .size(width = THUMB_WIDTH, height = CHIP_HEIGHT)
            .clip(OcTheme.shapes.md)
            .background(c.layer02),
    ) {
        bitmap?.let { image ->
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = attachment.filename,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // Inner stroke: `shadow-[inset_0_0_0_0.5px_border-base]` on the same radius.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(0.5.dp, c.borderBase, OcTheme.shapes.md),
        )
    }
}

/** Non-image attachment: `layer02` chip, file icon + filename (View `bg_attachment_chip`). */
@Composable
private fun FileChip(attachment: ChatViewModel.Attachment) {
    val c = OcTheme.colors
    Row(
        modifier = Modifier
            .height(CHIP_HEIGHT)
            .clip(OcTheme.shapes.md)
            .background(c.layer02)
            .padding(start = 8.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_oc_file),
            contentDescription = null,
            tint = c.iconMuted,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = attachment.filename,
            color = c.textBase,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = FILE_NAME_MAX_WIDTH),
        )
    }
}

/** 16 dp `iconMuted` circle with a `bgBase` glyph (`bg_icon_badge` + "×"). */
@Composable
private fun RemoveBadge(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = OcTheme.colors
    val description = stringResource(R.string.oc_remove_attachment)
    Box(
        modifier = modifier
            .size(BADGE_SIZE)
            .clip(CircleShape)
            .background(c.iconMuted)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "×",
            color = c.bgBase,
            fontSize = 10.sp,
            lineHeight = 10.sp,
            fontWeight = FontWeight.W500,
        )
    }
}

/**
 * data URL → downsampled thumbnail (~3× of 58×46, `ChatView.decodeThumb`'s
 * `THUMB_TARGET_PX = 174`) so a 8 MB photo never becomes a full-size bitmap.
 */
private fun decodeThumbnail(payload: String): Bitmap? = try {
    val bytes = Base64.getDecoder().decode(payload)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= THUMB_TARGET_PX) sample *= 2
    BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample },
    )
} catch (e: Exception) {
    Log.w(TAG, "thumbnail decode failed: ${e.message}")
    null
}

/**
 * Tiny LruCache for decoded thumbnails, keyed by a *hash* of the payload: the
 * View shell keyed `LruCache` by the whole data URL, which pins tens of MB of
 * base64 strings for as long as the cache lives.
 */
private object AttachmentThumbs {

    private val cache = LruCache<String, Bitmap>(THUMB_CACHE_ENTRIES)

    fun key(payload: String?): String? = payload?.let { "${it.hashCode()}:${it.length}" }

    fun get(payload: String?): Bitmap? = key(payload)?.let { cache.get(it) }

    fun put(payload: String, bitmap: Bitmap) {
        key(payload)?.let { cache.put(it, bitmap) }
    }
}

private const val TAG = "AttachmentPreview"

/** `w-[58px] h-[46px]`. */
private val THUMB_WIDTH = 58.dp

/** Chip height (View `bg_attachment_chip` holder). */
private val CHIP_HEIGHT = 46.dp

/** `size-4` remove badge. */
private val BADGE_SIZE = 16.dp

/** `-top-1 -end-1` → 4 dp outside the chip. */
private val BADGE_OVERHANG = 4.dp

/** View clamps the file name to 120 dp. */
private val FILE_NAME_MAX_WIDTH = 120.dp

private const val THUMB_TARGET_PX = 174
private const val THUMB_CACHE_ENTRIES = 12
