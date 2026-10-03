package com.xiufeigo.opencodeshell.ui.session.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.ChatStore
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.session.review.ReviewCommentMetadata
import com.xiufeigo.opencodeshell.ui.session.review.ReviewNote
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import org.json.JSONObject

/**
 * F-4: the timeline's **comment card**, the Compose port of
 * `_upstream/packages/session-ui/src/message/comment-card.tsx` +
 * `attachment-card.tsx` (the shared two-line card) and their user-message
 * container (`message-content.tsx:175-200` `UserMessageComments`).
 *
 * Upstream renders the cards under the user's own text, at most five until
 * 「显示更多」 (`:178` `props.bounded && !state.expanded ? props.comments.slice(0, 5)`),
 * each card being the shared `AttachmentCard`:
 *
 * | piece | upstream | here |
 * |---|---|---|
 * | title | the comment text, 11 px / 12 line-height, weight 530, 1 line ellipsis (`attachment-card.css:57-66`) | same |
 * | subtitle | 12 px file icon + `filename:line` (truncated to 14 chars, `comment-card.tsx:45-52`) | same |
 * | surface | `overlay-simple-overlay-hover` + `inset 0 0 0 0.5px border-base`, radius 6, padding 8, gap 6 (`attachment-card.css:2-16`) | same |
 *
 * The data half is [TimelineComments]: a delivered user row carries no metadata
 * on this shell, so the comments are recovered from the message **text** with
 * upstream's `parseCommentNote` fallback (`message-timeline.tsx:296-301`), and a
 * parked steer carries them as `comment` parts
 * (`ChatViewModel.pendingSteerRow`).
 */
object TimelineComments {

    /**
     * The `UiPart.type` a parked steer's comment rides as. The store has no
     * first-class comment field, so the entry is kept verbatim in
     * `UiPart.rawJson` — the carrier `ChatStore.UiPart` documents for exactly
     * this ("payloads without first-class fields").
     */
    const val PART_TYPE = "comment"

    /** `comment-card.tsx:43` `getFilenameTruncated(props.path, 14)`. */
    const val FILENAME_MAX = 14

    /** `message-content.tsx:178` — the bounded view shows five cards. */
    const val BOUNDED = 5

    /** `util/path.ts:15-23` `getFilenameTruncated`: keep the extension, ellipsis the stem. */
    fun filenameTruncated(path: String, maxLength: Int = FILENAME_MAX): String {
        val filename = path.replace('\\', '/').trimEnd('/').substringAfterLast('/')
        if (filename.length <= maxLength) return filename
        val lastDot = filename.lastIndexOf('.')
        val extension = if (lastDot <= 0) "" else filename.substring(lastDot)
        val available = maxLength - extension.length - 1
        if (available <= 0) return filename.take(maxLength - 1) + "…"
        return filename.take(available) + "…" + extension
    }

    /**
     * The comment rows of one message body: `parseCommentNote` over the text
     * (`message-timeline.tsx:296-301`'s `parsed` fallback, line-scoped here).
     */
    fun ofText(text: String): List<ReviewNote> = ReviewCommentMetadata.presentation(text).notes

    /** The body without its note lines — what the bubble draws. */
    fun displayText(text: String): String = ReviewCommentMetadata.presentation(text).displayText

    /**
     * The comment rows carried by a row's `comment` parts (a parked steer's
     * `metadata.comments`). A part whose payload no longer parses is dropped
     * rather than rendered as an empty card.
     */
    fun ofParts(parts: List<ChatStore.UiPart>): List<ReviewNote> = parts
        .filter { it.type == PART_TYPE }
        .mapNotNull { part ->
            val raw = part.rawJson ?: return@mapNotNull null
            runCatching { ReviewCommentMetadata.noteFromJson(JSONObject(raw)) }.getOrNull()
        }
}

/**
 * One card (`CommentCard` → `AttachmentCard`, `surface="base" wide`): the
 * comment on the first line, `filename:line` with a file icon on the second.
 * No click action: upstream only makes it clickable when the host passes
 * `onClick`, and the Android timeline has no line-comment anchor to jump to.
 */
@Composable
internal fun CommentCard(note: ReviewNote, modifier: Modifier = Modifier) {
    val c = OcTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(OcTheme.shapes.md)
            .background(c.overlayHover)
            .border(0.5.dp, c.borderBase, OcTheme.shapes.md)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = note.comment,
            color = c.textBase,
            fontSize = 11.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight(530),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_oc_file),
                contentDescription = null,
                tint = c.iconMuted,
                modifier = Modifier.size(12.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = TimelineComments.filenameTruncated(note.path) + note.lineSuffix,
                color = c.textMuted,
                fontSize = 11.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight(440),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * `UserMessageComments` (`message-content.tsx:175-200`): the cards stacked with
 * an 8 dp gap, `bounded` at [TimelineComments.BOUNDED] rows plus the
 * 「显示更多」 row (`ui.common.showMore`).
 *
 * @param expanded the caller's per-row disclosure (`state.expanded`); a bounded
 *   list only folds while this is false.
 */
@Composable
internal fun UserCommentCards(
    notes: List<ReviewNote>,
    expanded: Boolean,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (notes.isEmpty()) return
    val visible = if (!expanded) notes.take(TimelineComments.BOUNDED) else notes
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (note in visible) {
            CommentCard(note = note)
        }
        if (!expanded && notes.size > TimelineComments.BOUNDED) {
            Text(
                text = stringResource(R.string.oc_common_show_more),
                color = OcTheme.colors.textMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight(530),
                modifier = Modifier
                    .clip(OcTheme.shapes.sm)
                    .clickable(role = Role.Button, onClick = onExpand)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}
