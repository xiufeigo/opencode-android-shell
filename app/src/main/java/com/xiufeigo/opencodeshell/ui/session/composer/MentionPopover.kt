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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.settings.SHORTCUT_ROWS
import com.xiufeigo.opencodeshell.ui.settings.ShortcutRow
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
 * category icon + label.
 *
 * B-12 fills the two columns the View shell never had: the trailing **keybind**
 * (only a `/` command row has one — [SuggestionKeybind]) and the **empty state**
 * (`px-2 py-1` muted text where the rows would be, `editor.tsx:790-792`). The
 * caller keeps the popover mounted while a trigger is live even with zero rows
 * (`Composer`'s `popoverVisible`), which is what makes the empty state reachable
 * — upstream renders its popover on the trigger, not on a non-empty list.
 *
 * v67 B1: the card can never own focus. Upstream's list is plain buttons next
 * to the `contenteditable`; the textarea keeps the focus and every key keeps
 * routing into the query. `Modifier.clickable` answers keyboard invocation
 * with `focusableInNonTouchMode` (`foundation/Clickable.kt:464`), so on a
 * window with a hardware keyboard every row is a focus target — a focus
 * search landing in the card (after the editor is re-created, say) parked the
 * keys there and the composer went deaf (`research/v67_verify/5558/s21`,
 * `s31`). `canFocus = false` keeps the whole card out of the focus chain on
 * every window shape; the arrows / Enter / Esc keep riding the editor's
 * `onPreviewKeyEvent`, and pointer taps are untouched.
 */
@Composable
internal fun MentionPopover(
    items: List<ComposerSuggestions.Item>,
    selected: Int,
    /**
     * v67 BUG-5: the row's own [ComposerSuggestions.Item], never its index. The
     * index named a row of whatever list was current when the tap arrived, which
     * is not the list that was drawn under the finger ([PopoverPick]).
     */
    onPick: (ComposerSuggestions.Item) -> Unit,
    /** The empty state's copy; null draws nothing (a host without the strings). */
    emptyLabelRes: Int? = null,
    /** Effective chords for the row's trailing column ([SuggestionKeybind.effectiveRows]). */
    keybindRows: List<ShortcutRow> = SHORTCUT_ROWS,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    val shape = RoundedCornerShape(12.dp)
    val elevation = OcTheme.elevation.raised
    // Edge-to-edge: the keyboard is drawn *over* the window, so the room the
    // card has is what is left of the screen between the IME and the chrome
    // above it (see [popoverMaxHeight]).
    val density = LocalDensity.current
    val imeBottom = with(density) { WindowInsets.ime.getBottom(this).toDp() }
    val navBottom = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    val maxHeight = popoverMaxHeight(LocalConfiguration.current.screenHeightDp.dp, imeBottom, navBottom)
    Column(
        modifier = modifier
            .focusProperties { canFocus = false }
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
            .heightIn(max = maxHeight)
            .padding(POPOVER_PADDING),
    ) {
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            if (items.isEmpty()) {
                // `div.px-2.py-1.text-v2-text-text-muted` inside the card's `p-2`
                // (`editor.tsx:791`): the same padding a row has, muted 13 px.
                item(key = "empty") {
                    Text(
                        text = emptyLabelRes?.let { stringResource(it) }.orEmpty(),
                        color = c.textMuted,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            } else {
                itemsIndexed(
                    items = items,
                    key = { _, item -> PopoverPick.key(item) },
                ) { index, item ->
                    SuggestionRow(
                        item = item,
                        active = index == selected,
                        onClick = { onPick(item) },
                        keybind = SuggestionKeybind.keybind(item, keybindRows),
                    )
                }
            }
        }
    }
}

/**
 * `button[data-suggestion-id]`: icon + label + muted description, 6 dp radius rows.
 * The icon is the row's **category** (reference / agent / command / skill /
 * resource / file), which is how the W5-C brief asks the four original `@`
 * candidate kinds — and F-1's two added ones — to be distinguishable;
 * the trailing column keeps the server's own description when it has one, else
 * the category name. v15 splits the file category one step further, because it
 * holds two kinds at once: a row naming a directory draws the folder and says
 * "Directory" ([ComposerSuggestions.Item.directory]).
 *
 * W5-G — **the label owns its width, the description gives way**, which is
 * upstream's row exactly: `flex items-center gap-2` with `shrink-0` on the label
 * (`<bdi>`) and `min-w-0 truncate` on the description
 * (`composer/editor/editor.tsx:799-816`). Compose's `Row` measures its weighted
 * children *last*, so as long as the label was the `weight(1f)` child a long
 * description (measured first, at up to the whole row) pushed it to zero width:
 * the tablet drew the `customize-opencode` and `jira-SCB-Collector` rows as icon +
 * description only (`research/wave5_shots/v6-slash-tablet.png`, §9 of
 * `research/wave5_composer.md`). Inverting the two: the label is measured
 * **unweighted**, i.e. at its natural width, and the description is the row's only
 * weighted child *on that branch*, so it can only ever take the remainder — and the
 * row keeps the placement it already had for every short description (flush at the
 * row's end, `…` when it does not fit).
 *
 * v16 — **but only while the trailing column really is a description.** That same
 * inversion is what broke the `@` file rows (`research/v16_verify/misc/
 * v16_mention_dir_file.png`): their trailing column is the *category tag*
 * ("Directory" / "File", the fallback [SuggestionRowText.description] answers null
 * for), the label is a whole absolute path, and an unweighted path took the entire
 * row and left the tag 0 dp wide — the tag stayed in the a11y tree and out of the
 * picture. [SuggestionRowLayout] decides per row which half is the flexible one,
 * so the tag is protected on the rows that draw it and the label keeps W5-G's
 * protection on the rows that draw a description.
 *
 * The two strings handed to those `Text`s come from [SuggestionRowText], which
 * pins "a row always draws a non-blank label" as JVM-testable logic.
 *
 * B-12 adds the row's fourth column: the keybind, drawn only when the row's
 * command has one ([SuggestionKeybind.keybind]). It is unweighted, so — like the
 * label on a description row — it is measured first and cannot be squeezed out by
 * the flexible trailing slot, and it sits flush at the row's end exactly like
 * upstream's trailing `span.shrink-0` (`editor.tsx:813-815`).
 */
@Composable
private fun SuggestionRow(
    item: ComposerSuggestions.Item,
    active: Boolean,
    onClick: () -> Unit,
    keybind: String?,
) {
    val c = OcTheme.colors
    val category = stringResource(rowLabelRes(item))
    val description = SuggestionRowText.description(
        item.description?.takeIf { it.isNotBlank() } ?: builtInDescription(item),
    )
    // v16: the trailing column draws a description when there is one, else the
    // category tag; the half that is *not* flexible is measured first, at its
    // natural width, and therefore can never be squeezed out by the other.
    val flexible = SuggestionRowLayout.flexible(hasDescription = description != null)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .clip(OcTheme.shapes.md)
            .background(if (active) c.overlayHover else Color.Transparent)
            .clickable(onClick = onClick)
            // The category rides the clickable Row, not the `Icon` inside it:
            // only the node carrying ACTION_CLICK is announced as a button, and
            // Compose reads `contentDescription` from a node's own config.
            .semantics { contentDescription = category }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(categoryIconRes(item)),
            contentDescription = null,
            tint = c.iconMuted,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        // W5-G: with a description present the label is unweighted on purpose —
        // an unweighted child is measured first, at its intrinsic width, so the
        // description can never take a pixel of it. Only a label longer than the
        // whole row truncates. v16: on a row whose trailing column is the category
        // tag, the roles swap — the label takes the remainder and ellipsizes, so
        // the tag always keeps its natural width.
        Text(
            text = SuggestionRowText.label(item.label, category),
            modifier = if (flexible == SuggestionRowLayout.Flexible.LABEL) {
                Modifier.weight(1f)
            } else {
                Modifier
            },
            color = c.textBase,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        // The trailing slot: the row's only weighted child while it draws a
        // description, so it can only take what the label left, and it ends at the
        // row's edge. Unweighted (measured at its own width, first) while it draws
        // the category tag — that is what keeps "Directory"/"File" visible next to
        // a long path.
        Box(
            modifier = if (flexible == SuggestionRowLayout.Flexible.TRAILING) {
                Modifier.weight(1f)
            } else {
                Modifier
            },
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
        // B-12: the row's keybind (`span.shrink-0.text-…-muted`, `editor.tsx:813-815`),
        // drawn only when the command has one — an unassigned binding has no column
        // here. Unweighted, so it is measured **before** the flexible slot above and
        // keeps its natural width whatever the description or path does.
        if (keybind != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = keybind,
                color = c.textMuted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The local slash commands get their description from resources here
 * ([ComposerSuggestions] is pure Kotlin and owns no strings): `/model` opens the
 * model dialog, `/agent` switches the agent (`composer/commands.tsx:49-82`), and
 * `/btw` opens the side-question panel (`btw.tsx:57`, upstream `session.aside`).
 */
@Composable
private fun builtInDescription(item: ComposerSuggestions.Item): String? {
    if (!item.builtIn) return null
    return when (item.label) {
        "/${ComposerSuggestions.BUILT_IN_MODEL}" -> stringResource(R.string.oc_slash_model)
        "/${ComposerSuggestions.BUILT_IN_AGENT}" -> stringResource(R.string.oc_slash_agent)
        "/${ComposerSuggestions.BTW_TRIGGER}" -> stringResource(R.string.oc_btw_description)
        else -> null
    }
}

/**
 * Category → icon. The M1.5 mapping (`agent` → task icon, everything else → file)
 * is kept for those two and extended with a distinct glyph per W5-C category:
 * agent (`ic_oc_task`), command (`ic_oc_console`), skill (`ic_oc_code`),
 * file (`ic_oc_file`).
 *
 * F-1: upstream's `ComposerSuggestionIcon` (`editor.tsx:904-913`) draws a
 * **directory** file icon for `kind === "reference"` and the plain file icon for
 * everything else non-agent/skill/command — so a reference gets
 * `ic_oc_folder` and an MCP resource keeps `ic_oc_file`.
 *
 * v15: the file category needs the same split for a second reason — `/find/file`
 * returns files *and* directories as bare paths, so `/home/xiufeigo/v11diff` and
 * `…/a.txt` arrived as one category and drew the same document glyph. A row that
 * names a directory ([ComposerSuggestions.Item.directory]) takes the folder icon
 * its sibling reference row already had.
 */
private fun categoryIconRes(item: ComposerSuggestions.Item): Int = when (item.category) {
    ComposerSuggestions.Category.REFERENCE -> R.drawable.ic_oc_folder
    ComposerSuggestions.Category.AGENT -> R.drawable.ic_oc_task
    ComposerSuggestions.Category.COMMAND -> R.drawable.ic_oc_console
    ComposerSuggestions.Category.SKILL -> R.drawable.ic_oc_code
    ComposerSuggestions.Category.RESOURCE -> R.drawable.ic_oc_file
    ComposerSuggestions.Category.FILE ->
        if (item.directory) R.drawable.ic_oc_folder else R.drawable.ic_oc_file
}

/**
 * The row's label — its category name, which is also what the trailing column
 * falls back to and what the icon announces as its content description.
 *
 * A [ComposerSuggestions.Category.FILE] row that names a directory says
 * "Directory" instead of "File" (v15): the category is the only column that
 * told the two apart, and it read "File" for both.
 */
private fun rowLabelRes(item: ComposerSuggestions.Item): Int =
    if (item.category == ComposerSuggestions.Category.FILE && item.directory) {
        R.string.oc_composer_mention_directory
    } else {
        categoryLabelRes(item.category)
    }

/** Category → human label. */
private fun categoryLabelRes(category: ComposerSuggestions.Category): Int = when (category) {
    ComposerSuggestions.Category.REFERENCE -> R.string.oc_composer_mention_reference
    ComposerSuggestions.Category.AGENT -> R.string.oc_composer_mention_agent
    ComposerSuggestions.Category.COMMAND -> R.string.oc_composer_mention_command
    ComposerSuggestions.Category.SKILL -> R.string.oc_composer_mention_skill
    ComposerSuggestions.Category.RESOURCE -> R.string.oc_composer_mention_resource
    ComposerSuggestions.Category.FILE -> R.string.oc_composer_mention_file
}

/** `max-h-80`. */
private val POPOVER_MAX_HEIGHT = 320.dp

/**
 * The chrome that sits above the content pane and never scrolls away: the app
 * tab strip and the session header. The popover grows out of the editor's top
 * edge, so this is what it has to leave for them.
 */
private val POPOVER_TOP_RESERVE = 176.dp

/**
 * Floor for the cap: below this the list stops being a list, and the popover is
 * better off drawing the rows it can than nothing at all.
 */
private val POPOVER_MIN_HEIGHT = 96.dp

/**
 * How tall the popover may be, capped by the room the window actually has
 * (v65, `research/v65_verify/5556/19-22`).
 *
 * The card is an **unweighted** child of the composer's column, so it is
 * measured against the pane's full height and its `max-h-80` is the only bound
 * it knows. With the keyboard up on a short window the pane has a couple of
 * hundred dp left between the session header and the IME: a 320 dp card simply
 * did not fit, the top rows were clipped off by the pane, and the list — already
 * a `LazyColumn` — had nothing to scroll inside what was left, so `/` showed
 * four rows and no way to reach the sixth. Collapsing the keyboard freed the
 * space and all ten rows appeared, which is what made it look like a keyboard
 * bug rather than a missing constraint.
 *
 * The cap is the smaller of `max-h-80` and the window's own room, so an
 * unclipped card on a tall window is unchanged and a short one shrinks into a
 * scrollable list instead of a cut-off one.
 */
internal fun popoverMaxHeight(screenHeight: Dp, imeBottom: Dp, navBottom: Dp): Dp {
    val room = screenHeight - imeBottom - navBottom - POPOVER_TOP_RESERVE
    return minOf(POPOVER_MAX_HEIGHT, room.coerceAtLeast(POPOVER_MIN_HEIGHT))
}

/** `p-2`. */
private val POPOVER_PADDING = 8.dp

/** `-top-2` — 8 dp between the popover's bottom edge and the editor. */
private val POPOVER_GAP = 8.dp

/** `px-2 py-1` row with a comfortable touch target. */
private val ROW_HEIGHT = 32.dp
