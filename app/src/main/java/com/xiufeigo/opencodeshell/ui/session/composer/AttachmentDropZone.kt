package com.xiufeigo.opencodeshell.ui.session.composer

import android.content.ClipData
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropModifierNode
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.theme.OcTheme

/**
 * Composer dropzone — the Android equivalent of upstream
 * `packages/app/src/composer/dropzone.tsx` (overlay + label) and the `document`
 * `dragover`/`drop` listeners in `composer/attachments/attachments.ts:188-217`.
 *
 * Upstream keys off HTML5 `DataTransfer.types.includes("Files")`; on Android a
 * cross-app file drag always advertises `text/uri-list` in the `ClipDescription`
 * ([AttachmentDropPolicy.accepts]). The drop itself then reads `ClipData` URIs —
 * there is no `getPathForFile`, so every pick is a `content://`/`file://` stream
 * handed to the same [AttachmentUploads] reader the SAF picker uses.
 *
 * Compose 1.7.6 ships the node-level DnD API only (`DragAndDropModifierNode`;
 * the `Modifier.dragAndDropTarget` convenience arrived later), so the target is
 * attached through a [DelegatingNode] wrapper.
 */
@Composable
internal fun AttachmentDropZone(
    onUris: (List<Uri>) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    var active by remember { mutableStateOf(false) }
    val holder = remember { DropTargetHolder() }
    // The read callback outlives recompositions; assign every pass so the
    // holder never keeps a stale capture.
    holder.onUris = onUris
    holder.onActiveChange = { active = it }

    Box(modifier = modifier.then(ComposerDropTargetElement(holder))) {
        content()
        if (active) {
            DropOverlay(
                label = stringResource(R.string.oc_composer_drop_files),
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

/**
 * Pure drop rules, JVM-tested: which drags light up the overlay and which clip
 * items count as attachments. Upstream's label variants
 * (`ui.promptInput.dropFiles.image|pdf|imagePdf`) key off `capabilities.input`;
 * that is not plumbed into this composer, so the generic `dropFiles` string is
 * the only one wired.
 */
internal object AttachmentDropPolicy {

    /** `ClipDescription.MIMETYPE_TEXT_URI_LIST` — the file-URI clip flavour. */
    const val MIME_URI_LIST = "text/uri-list"

    /**
     * `attachments.ts:196-198` (`types.includes("Files")`): a drag that carries
     * file URIs. The wildcard flavour (`*` + `/` + `*`) is accepted because
     * several file managers advertise only that; `onDrop` still requires real URIs.
     */
    fun accepts(mimeTypes: Collection<String>): Boolean =
        mimeTypes.any { type ->
            type.equals(MIME_URI_LIST, ignoreCase = true) || type.equals("*/*", ignoreCase = true)
        }

    /** Only `content://` / `file://` items are attachments — a text drop is not. */
    fun isAttachmentUri(uri: String): Boolean =
        uri.startsWith("content://") || uri.startsWith("file://")
}

/** Overlay chrome: `session-dropzone-upload` badge + `session-dropzone-content` label. */
@Composable
private fun DropOverlay(label: String, modifier: Modifier = Modifier) {
    val c = OcTheme.colors
    Box(
        modifier = modifier
            .clip(OcTheme.shapes.xl)
            .background(c.layer02.copy(alpha = DROP_WASH_ALPHA))
            .border(1.dp, c.borderFocus, OcTheme.shapes.xl),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(DROP_LABEL_GAP),
        ) {
            Box(
                modifier = Modifier
                    .size(DROP_BADGE_SIZE)
                    .clip(CircleShape)
                    .background(c.layer01),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_oc_send),
                    contentDescription = null,
                    tint = c.iconMuted,
                    modifier = Modifier.size(DROP_ICON_SIZE),
                )
            }
            Text(
                text = label,
                color = c.textBase,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/** Mutable callback sink so the [DragAndDropTarget] can stay identity-stable. */
private class DropTargetHolder : DragAndDropTarget {
    var onActiveChange: (Boolean) -> Unit = {}
    var onUris: (List<Uri>) -> Unit = {}

    override fun onEntered(event: DragAndDropEvent) = onActiveChange(true)

    override fun onExited(event: DragAndDropEvent) = onActiveChange(false)

    override fun onEnded(event: DragAndDropEvent) = onActiveChange(false)

    override fun onDrop(event: DragAndDropEvent): Boolean {
        onActiveChange(false)
        val uris = event.toAndroidDragEvent().clipData.attachmentUris()
        if (uris.isEmpty()) return false
        onUris(uris)
        return true
    }
}

private fun ClipData?.attachmentUris(): List<Uri> {
    if (this == null) return emptyList()
    return (0 until itemCount)
        .mapNotNull { index -> getItemAt(index).uri }
        .filter { AttachmentDropPolicy.isAttachmentUri(it.toString()) }
}

/**
 * `DragAndDropModifierNode` is a [DelegatingNode] delegate rather than a
 * `Modifier` factory in Compose 1.7.6; the holder keeps `update` a no-op.
 */
private class ComposerDropNode(
    private val holder: DropTargetHolder,
) : DelegatingNode() {
    @Suppress("unused")
    private val drop = delegate(
        DragAndDropModifierNode(
            shouldStartDragAndDrop = { event -> AttachmentDropPolicy.accepts(event.mimeTypes()) },
            target = holder,
        ),
    )
}

private class ComposerDropTargetElement(
    private val holder: DropTargetHolder,
) : ModifierNodeElement<ComposerDropNode>() {
    override fun create(): ComposerDropNode = ComposerDropNode(holder)

    override fun update(node: ComposerDropNode) = Unit

    override fun equals(other: Any?): Boolean =
        other is ComposerDropTargetElement && other.holder === holder

    override fun hashCode(): Int = holder.hashCode()
}

/** Upstream `session-dropzone-wash` / card / icon geometry (px → dp 1:1). */
private const val DROP_WASH_ALPHA = 0.92f

private val DROP_BADGE_SIZE = 40.dp

private val DROP_ICON_SIZE = 16.dp

private val DROP_LABEL_GAP = 20.dp
