package com.xiufeigo.opencodeshell.ui.session.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.theme.OcTheme

/**
 * The composer's completion popover (`PromptInputV2Popover`, `research/02` §4.7):
 * a `layer01` card with radius 12 and `elevation-raised`, capped at 320 dp with
 * its own scroll, rendered directly on the editor's top edge.
 *
 * The View shell used a `PopupWindow` pinned to the window bottom; here the card
 * is part of the composer layout — the composer is bottom-aligned already, so
 * the popover sits on the editor's upper edge with or without the IME, and grows
 * the composer instead of covering the timeline.
 *
 * W5-C B1/B9: one popover serves **both** surfaces — the `/` command menu and the
 * `@` context menu — so every row is a [ComposerSuggestions.Item] and carries its
 * category icon + label. The two extra columns the View shell never had
 * (per-row keybind column, "No matching items" empty state) stay unimplemented:
 * the popover closes while the list is empty.
 */
@Composable
internal fun MentionPopover(
    items: List<ComposerSuggestions.Item>,
    selected: Int,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    val shape = RoundedCornerShape(12.dp)
    val elevation = OcTheme.elevation.raised
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = POPOVER_GAP)
            .shadow(
                elevation = elevation.blur.dp,
                shape = shape,
                ambientColor = elevation.color,
                spotColor = elevation.color,
            )
            .clip(shape)
            .background(c.layer01)
            .heightIn(max = POPOVER_MAX_HEIGHT)
            .padding(POPOVER_PADDING),
    ) {
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            itemsIndexed(
                items = items,
                key = { _, item -> "${item.category}:${item.label}" },
            ) { index, item ->
                SuggestionRow(
                    item = item,
                    active = index == selected,
                    onClick = { onPick(index) },
                )
            }
        }
    }
}

/**
 * `button[data-suggestion-id]`: icon + label + muted description, 6 dp radius rows.
 * The icon is the row's **category** (agent / command / skill / file), which is
 * how the W5-C brief asks the four `@` candidate kinds to be distinguishable;
 * the trailing column keeps the server's own description when it has one, else
 * the category name.
 *
 * W5-G — **the label owns its width, the description gives way**, which is
 * upstream's row exactly: `flex … items-center gap-2` with `shrink-0` on the label
 * (`<bdi>`) and `min-w-0 truncate` on the description
 * (`composer/editor/editor.tsx:799-816`). Compose's `Row` measures its weighted
 * children *last*, so as long as the label was the `weight(1f)` child a long
 * description (measured first, at up to the whole row) pushed it to zero width:
 * the tablet drew the `customize-opencode` and `jira-SCB-Collector` rows as icon +
 * description only (`research/wave5_shots/v6-slash-tablet.png`, §9 of
 * `research/wave5_composer.md`). Inverting the two: the label is measured
 * **unweighted**, i.e. at its natural width, and the description is the row's only
 * weighted child, so it can only ever take the remainder — and the row keeps the
 * placement it already had for every short description (flush at the row's end,
 * `…` when it does not fit).
 *
 * The two strings handed to those `Text`s come from [SuggestionRowText], which
 * pins "a row always draws a non-blank label" as JVM-testable logic.
 */
@Composable
private fun SuggestionRow(
    item: ComposerSuggestions.Item,
    active: Boolean,
    onClick: () -> Unit,
) {
    val c = OcTheme.colors
    val category = stringResource(categoryLabelRes(item.category))
    val description = SuggestionRowText.description(
        item.description?.takeIf { it.isNotBlank() } ?: builtInDescription(item),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .clip(OcTheme.shapes.md)
            .background(if (active) c.overlayHover else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(categoryIconRes(item.category)),
            contentDescription = category,
            tint = c.iconMuted,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        // No modifier on purpose: an unweighted child is measured first, at its
        // intrinsic width, so a description can never take a pixel of the label.
        // Only a label longer than the whole row truncates.
        Text(
            text = SuggestionRowText.label(item.label, category),
            color = c.textBase,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        // The description slot: the row's only weighted child, so it can only take
        // what the label left, and it ends at the row's edge.
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Text(
                text = description ?: category,
                color = c.textMuted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The two built-in slash commands get their description from resources here
 * ([ComposerSuggestions] is pure Kotlin and owns no strings): `/model` opens the
 * model dialog, `/agent` switches the agent (`composer/commands.tsx:49-82`).
 */
@Composable
private fun builtInDescription(item: ComposerSuggestions.Item): String? {
    if (!item.builtIn) return null
    return when (item.label) {
        "/${ComposerSuggestions.BUILT_IN_MODEL}" -> stringResource(R.string.oc_slash_model)
        "/${ComposerSuggestions.BUILT_IN_AGENT}" -> stringResource(R.string.oc_slash_agent)
        else -> null
    }
}

/**
 * Category → icon. The M1.5 mapping (`agent` → task icon, everything else → file)
 * is kept for those two and extended with a distinct glyph per W5-C category:
 * agent (`ic_oc_task`), command (`ic_oc_console`), skill (`ic_oc_code`),
 * file (`ic_oc_file`).
 */
private fun categoryIconRes(category: ComposerSuggestions.Category): Int = when (category) {
    ComposerSuggestions.Category.AGENT -> R.drawable.ic_oc_task
    ComposerSuggestions.Category.COMMAND -> R.drawable.ic_oc_console
    ComposerSuggestions.Category.SKILL -> R.drawable.ic_oc_code
    ComposerSuggestions.Category.FILE -> R.drawable.ic_oc_file
}

/** Category → human label (also the row's content description). */
private fun categoryLabelRes(category: ComposerSuggestions.Category): Int = when (category) {
    ComposerSuggestions.Category.AGENT -> R.string.oc_composer_mention_agent
    ComposerSuggestions.Category.COMMAND -> R.string.oc_composer_mention_command
    ComposerSuggestions.Category.SKILL -> R.string.oc_composer_mention_skill
    ComposerSuggestions.Category.FILE -> R.string.oc_composer_mention_file
}

/** `max-h-80`. */
private val POPOVER_MAX_HEIGHT = 320.dp

/** `p-2`. */
private val POPOVER_PADDING = 8.dp

/** `-top-2` — 8 dp between the popover's bottom edge and the editor. */
private val POPOVER_GAP = 8.dp

/** `px-2 py-1` row with a comfortable touch target. */
private val ROW_HEIGHT = 32.dp
