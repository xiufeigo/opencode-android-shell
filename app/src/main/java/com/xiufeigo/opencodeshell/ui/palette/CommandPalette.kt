package com.xiufeigo.opencodeshell.ui.palette

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.components.OcSearchIcon
import com.xiufeigo.opencodeshell.ui.settings.OcTextField
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import androidx.compose.ui.geometry.Offset

/**
 * `DialogCommandPaletteV2` / `DialogHomeCommandPaletteV2`
 * (`dialog-command-palette-v2.tsx` + `.css`, `research/02` §6.3–§6.6).
 *
 * **Host**: a global dialog-stack layer, never a route (`research/05` §1/§2 —
 * `dialog.show()` from Home, session and new-session all land here; the session
 * route merely re-points `command.palette` at `file.open`, whose `onSelect`
 * shows `DialogSelectFile` ⇒ `DialogCommandPaletteV2`).
 *
 * Sizes transcribed verbatim (§6.3):
 * `width: min(100vw-24px, 640px)`; `height: auto`, `min-height 280px`,
 * `max-height: min(100vh-96px, 480px)`; `margin-top: max(48px, (100vh-480px)/2)`;
 * `border-radius 12px`; `box-shadow: elevation-floating`, anchored to the top.
 */
@Composable
fun CommandPalette(
    kind: PaletteHostKind,
    sources: PaletteSources,
    onSelect: (PaletteEntry) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onHighlight: (PaletteEntry?) -> Unit = {},
) {
    val c = OcTheme.colors
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val viewportWidth = config.screenWidthDp.dp
    val viewportHeight = config.screenHeightDp.dp
    val width = minOf(viewportWidth - 24.dp, 640.dp)
    val maxHeight = minOf(viewportHeight - 96.dp, 480.dp)
    val topMargin = maxOf(48.dp, (viewportHeight - 480.dp) / 2)

    var query by remember { mutableStateOf("") }
    var active by remember { mutableStateOf(0) }

    val rows = remember(sources, query) { visibleEntries(sources, query) }
    val grouped = remember(rows) { groupEntries(rows) }
    val flat = remember(grouped) { flattenRows(grouped) }

    // `createEffect` on `query()` / `visibleEntries()` → `setActive(0)`.
    LaunchedEffect(query, flat) {
        active = 0
        onHighlight(flat.firstOrNull())
    }

    // §6.5: `@media (max-width: 640px)` stacks title over description.
    val stacked = viewportWidth <= 640.dp

    Box(
        modifier.fillMaxWidth().padding(top = topMargin),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .width(width)
                .heightIn(min = 280.dp, max = maxHeight)
                .shadow(16.dp, RoundedCornerShape(12.dp))
                .background(c.bgBase, RoundedCornerShape(12.dp))
                .border(0.5.dp, c.borderBase, RoundedCornerShape(12.dp))
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) {
                        false
                    } else {
                        when (event.key) {
                            Key.DirectionDown -> {
                                active = moveIndex(active, 1, flat.size)
                                onHighlight(flat.getOrNull(active))
                                true
                            }

                            Key.DirectionUp -> {
                                active = moveIndex(active, -1, flat.size)
                                onHighlight(flat.getOrNull(active))
                                true
                            }

                            Key.Enter, Key.NumPadEnter -> {
                                flat.getOrNull(active)?.let(onSelect)
                                true
                            }

                            Key.Escape -> {
                                onClose()
                                true
                            }

                            else -> false
                        }
                    }
                },
        ) {
            // `.command-palette-v2-search` → `padding: 6px`, field `h-36 / r-6`.
            Box(Modifier.padding(6.dp)) {
                SearchField(
                    value = query,
                    placeholder = stringResource(kind.placeholderResId()),
                    onValueChange = { query = it },
                )
            }
            if (flat.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.oc_palette_empty),
                        color = c.textMuted,
                        fontSize = 13.sp,
                    )
                }
            } else {
                // `.command-palette-v2-results` → `gap:16px; padding: 6px 6px 8px`;
                // `.command-palette-v2-group` → `gap:1px`.
                LazyColumn(
                    Modifier.fillMaxWidth().padding(PaddingValues(start = 6.dp, end = 6.dp, bottom = 8.dp)),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    for ((category, entries) in grouped) {
                        item(key = "group:$category") { GroupTitle(category) }
                        items(entries, key = { it.id }) { entry ->
                            val index = flat.indexOfFirst { it.id == entry.id }
                            PaletteRow(
                                entry = entry,
                                active = index == active,
                                stacked = stacked,
                                onClick = { onSelect(entry) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** `TextInputV2 appearance="large"`, `layer-02 @60%`, radius 6, 36dp tall. */
@Composable
private fun SearchField(value: String, placeholder: String, onValueChange: (String) -> Unit) {
    val c = OcTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(c.layer02.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OcSearchIcon(c.iconMuted)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                if (value.isEmpty()) {
                    Text(placeholder, color = c.textFaint, fontSize = 13.sp, maxLines = 1)
                }
                androidx.compose.foundation.text.BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = c.textBase,
                        fontSize = 13.sp,
                        fontWeight = FontWeight(440),
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(c.textAccent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** `.command-palette-v2-group-title` — 13px/440 muted, `margin: 6px 0`, `padding: 0 12px`. */
@Composable
private fun GroupTitle(category: String) {
    Text(
        text = paletteGroupLabel(category),
        color = OcTheme.colors.textMuted,
        fontSize = 13.sp,
        fontWeight = FontWeight(440),
        lineHeight = 16.sp,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/**
 * Group labels come from the entry's `category` — and session rows carry
 * `command.category.session` ("Session"), NOT `palette.group.*` (§6.2).
 */
@Composable
private fun paletteGroupLabel(category: String): String = when (category) {
    CATEGORY_COMMANDS -> stringResource(R.string.oc_palette_group_commands)
    CATEGORY_FILES -> stringResource(R.string.oc_palette_group_files)
    CATEGORY_SESSION -> stringResource(R.string.oc_palette_group_session)
    else -> category
}

/** `.command-palette-v2-row` — `h-36`, `r-6`, `padding 0 12px`, active = overlay-hover. */
@Composable
private fun PaletteRow(
    entry: PaletteEntry,
    active: Boolean,
    stacked: Boolean,
    onClick: () -> Unit,
) {
    val c = OcTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(if (stacked) 48.dp else 36.dp)
            .background(if (active) c.overlayHover else Color.Transparent, RoundedCornerShape(6.dp))
            .clickable(
                // `onMouseDown` preventDefault + `onClick` select (§6.6).
                onClick = onClick,
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (entry.type == PaletteEntryType.SESSION && entry.open) {
                // `h-3 w-0.5 rounded-[2px] bg-layer-04` at `right: calc(100% + 4px)`.
                Box(
                    Modifier
                        .size(width = 2.dp, height = 12.dp)
                        .background(c.layer04, RoundedCornerShape(2.dp)),
                )
            }
            if (entry.type == PaletteEntryType.FILE) FileGlyph(c.iconMuted)
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = if (stacked) Modifier.fillMaxWidth() else Modifier,
                ) {
                    if (entry.type == PaletteEntryType.FILE && entry.path != null) {
                        val (dir, name) = paletteSplitPath(entry.path)
                        Text(
                            text = dir,
                            color = c.textMuted,
                            fontSize = 13.sp,
                            fontWeight = FontWeight(440),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Text(
                            text = name,
                            color = c.textBase,
                            fontSize = 13.sp,
                            fontWeight = FontWeight(530),
                            maxLines = 1,
                        )
                    } else {
                        Text(
                            text = entry.title,
                            color = if (entry.archived) c.textBase.copy(alpha = 0.7f) else c.textBase,
                            fontSize = 13.sp,
                            fontWeight = FontWeight(530),
                            lineHeight = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                entry.description?.let { description ->
                    Text(
                        text = description,
                        color = c.textMuted,
                        fontSize = 13.sp,
                        fontWeight = FontWeight(440),
                        lineHeight = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        entry.keybind?.let { keybind ->
            Text(
                text = keybind,
                color = c.textFaint,
                fontSize = 11.sp,
                fontWeight = FontWeight(530),
            )
        }
        entry.meta?.let { meta ->
            Spacer(Modifier.width(8.dp))
            Text(text = meta, color = c.textMuted, fontSize = 13.sp, maxLines = 1)
        }
    }
}

/** `FileIcon` stand-in: a small page outline. */
@Composable
private fun FileGlyph(tint: Color) {
    Canvas(Modifier.size(14.dp)) {
        val s = size.minDimension
        val w = s * 0.1f
        drawLine(tint, Offset(s * 0.2f, s * 0.1f), Offset(s * 0.62f, s * 0.1f), w, StrokeCap.Round)
        drawLine(tint, Offset(s * 0.62f, s * 0.1f), Offset(s * 0.82f, s * 0.32f), w, StrokeCap.Round)
        drawLine(tint, Offset(s * 0.2f, s * 0.1f), Offset(s * 0.2f, s * 0.9f), w, StrokeCap.Round)
        drawLine(tint, Offset(s * 0.82f, s * 0.32f), Offset(s * 0.82f, s * 0.9f), w, StrokeCap.Round)
        drawLine(tint, Offset(s * 0.2f, s * 0.9f), Offset(s * 0.82f, s * 0.9f), w, StrokeCap.Round)
    }
}

/**
 * Compose-side catalogue resolution: turns the registered [PaletteCommand]s into
 * list rows with real strings. Kept here (not in `PaletteModel.kt`) so that file
 * stays Compose-free and JVM-testable.
 */
@Composable
fun stringResolvedEntries(commands: List<PaletteCommand>): List<PaletteEntry> {
    val resolved = commands.map { command ->
        Triple(
            command,
            stringResource(command.titleResId),
            command.descriptionResId?.let { stringResource(it) },
        )
    }
    return resolved.filterNot { (command, _, _) -> command.hidden }
        .map { (command, title, description) ->
            command.toEntry().copy(title = title, description = description)
        }
}
