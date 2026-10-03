package com.xiufeigo.opencodeshell.ui.palette

import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.components.OcSearchIcon
import com.xiufeigo.opencodeshell.ui.overlay.DialogSize
import com.xiufeigo.opencodeshell.ui.overlay.LocalDialogController
import com.xiufeigo.opencodeshell.ui.overlay.LocalDialogScrimState
import com.xiufeigo.opencodeshell.ui.overlay.dialogCardBounds
import com.xiufeigo.opencodeshell.ui.settings.OcTextField
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import androidx.compose.ui.geometry.Offset
import java.util.Locale

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
    /**
     * W2-C: every query edit, raw. The host layer uses it to fire the
     * server-side `/find/file` search whose async results re-enter through
     * [sources]; the palette's own filtering stays purely local.
     */
    onQueryChange: (String) -> Unit = {},
) {
    val c = OcTheme.colors
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val density = LocalDensity.current
    val viewportWidth = config.screenWidthDp.dp
    // `100vh` (§6.3) is the *window*, not the display: the keyboard is drawn
    // *over* the composition (`OcActivity` is edge-to-edge), so the usable height
    // is the window minus the IME inset — the same read `SessionScreen` uses to
    // lift its composer. `screenHeightDp` alone does not shrink with the
    // keyboard, and sizing the shell against it put the results area (and every
    // row below the head of the list) behind the IME.
    val windowHeight = config.screenHeightDp.dp
    val imeBottom = with(density) { WindowInsets.ime.getBottom(this).toDp() }
    val visibleHeight = (windowHeight - imeBottom).coerceAtLeast(PALETTE_MIN_HEIGHT.dp)
    val shell = paletteShell(viewportWidth.value, visibleHeight.value)
    val width = shell.width.dp
    val maxHeight = shell.maxHeight.dp

    // v54 (`tB_71` → `tB_73`): every open starts blank —
    // `PALETTE_OPEN_QUERY`, never the previous open's text. The state is
    // dialog-local: `DialogHost` keys each layer by its entry id, so a reopened
    // palette is a new composition and cannot inherit the last one's `remember`
    // (the query, the active-row anchor, the results scroll, the autofocus
    // effect) the way it did while the layer loop was unkeyed.
    var query by remember { mutableStateOf(PALETTE_OPEN_QUERY) }
    var active by remember { mutableStateOf(0) }
    // Autofocus the search field on open (upstream `shell/commands/dialog.tsx`
    // `autofocus`). `requestFocus` throws if the field is not laid out yet, so
    // it is best-effort like the global keybind host.
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { searchFocus.requestFocus() } }

    // Builtin self-sufficient panels ([BuiltinCommands]) ride the command list
    // after the host's registered rows and are intercepted here — they need no
    // `ui/App.kt` side effect, only a dialog layer. FILES_ONLY keeps its
    // upstream contract (no commands at all).
    val hostDialog = LocalDialogController.current
    // v98: the box the layer's scrim guards is the box the host *measured* as
    // this layer's content ([DialogScrimState.contentBounds]) unless the layer
    // reports its own card. The palette's content box is the §6.3 layout box
    // below — `fillMaxWidth` wide and `paletteBoxHeight` tall, i.e. up to
    // `windowHeight - topMargin` from the window top, which with the keyboard up
    // (this composable autofocuses its search field, so the IME is up by
    // default) collapses `topMargin` to its 48 dp floor and swallows the whole
    // bottom tab strip. A tap in that band was answered "inside the content
    // box" by [scrimTapDismisses]: the layer neither dismissed nor handed the
    // event down, so the strip got nothing at all — the palette's dead zone
    // (`research/v97_verify/5564_v98/REPORT_PERF.md` §6.3, both form factors).
    // Reporting the card itself is the fix every other modal already uses
    // (`DialogScrimResidueTest.everyHandRolledModalRoutesThroughTheSharedScrim`).
    val scrim = LocalDialogScrimState.current
    val builtinRows = builtinCommandEntries()
    val effectiveSources = remember(sources, builtinRows, kind) {
        if (kind == PaletteHostKind.FILES_ONLY) sources
        else BuiltinCommands.withBuiltinCommands(sources, builtinRows)
    }
    val handleSelect: (PaletteEntry) -> Unit = select@{ entry ->
        when (entry.id) {
            BuiltinCommands.entryId(BuiltinCommands.AGENT_DETAILS) -> {
                onClose()
                val dialog = hostDialog ?: return@select
                dialog.show(DialogSize.FIT) { controller ->
                    AgentDetailsDialog(onClose = { controller.close() })
                }
            }
            BuiltinCommands.entryId(BuiltinCommands.DEBUG_LOCATIONS) -> {
                onClose()
                val dialog = hostDialog ?: return@select
                dialog.show(DialogSize.FIT) { controller ->
                    DebugLocationsDialog(onClose = { controller.close() })
                }
            }
            BuiltinCommands.entryId(BuiltinCommands.PLUGIN_RPC) -> {
                onClose()
                val dialog = hostDialog ?: return@select
                dialog.show(DialogSize.FIT) { controller ->
                    PluginRpcDialog(onClose = { controller.close() })
                }
            }
            else -> onSelect(entry)
        }
    }

    // Blank query → the whole catalog ([browseEntries]); a query keeps
    // upstream's search semantics. The browse list is what makes the results
    // area a scroller on the phone: the preferred rows alone are shorter than
    // the shell's 280dp minimum, so there was nothing to scroll.
    val rows = remember(effectiveSources, query) { paletteRows(effectiveSources, query) }
    val grouped = remember(rows) { groupEntries(rows) }
    val flat = remember(grouped) { flattenRows(grouped) }
    // `active` indexes `flat`; the lazy list counts a group-title item per
    // group, so the follow-scroll below needs the slot map.
    val lazyIndices = remember(grouped) { lazyRowIndices(grouped) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    // WAVE 2.3a (A4 #5): id-anchored active row (`dialog.tsx:73-100`). The old
    // `LaunchedEffect(query, flat) { active = 0 }` jumped to the top whenever
    // async results landed; upstream keeps `store.active` (an entry id) and
    // falls back to the first row only when it vanishes.
    var activeId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(query, flat) {
        active = anchorActiveIndex(flat, activeId)
        activeId = flat.getOrNull(active)?.id
        onHighlight(flat.getOrNull(active))
    }
    // `block:nearest` follow (`dialog.tsx:93-95`): keep the active row visible.
    // Upstream scrolls only when the row left the viewport, and it scrolls to
    // the *row* — `active` indexes `flat`, while the lazy list counts a
    // group-title item before each group. The row's `scroll-margin: 6px 0`
    // keeps it 6px inside the viewport edge, so the snap is followed by a 6dp
    // nudge ([paletteRowScrollNudgePx]).
    LaunchedEffect(active, flat) {
        val slot = flat.getOrNull(active)?.let { lazyIndices[it.id] } ?: return@LaunchedEffect
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == slot }) {
            runCatching {
                listState.scrollToItem(slot)
                listState.scrollBy(paletteRowScrollNudgePx(density.density))
            }
        }
    }

    // §6.5: `@media (max-width: 640px)` stacks title over description.
    val stacked = viewportWidth <= 640.dp

    // The dialog layer centres its content box in the window (`DialogHost`'s
    // `Alignment.Center`), so this box is sized against the *window* instead of
    // its content: at `windowHeight - 2 * topMargin` the centring offset is
    // exactly §6.3's `margin-top` — the card's top edge — which is what keeps the
    // whole results area above the keyboard. A content-sized box was centred
    // lower and, with the keyboard up, lost every row below its head behind the
    // IME.
    val shellBoxHeight = paletteBoxHeight(windowHeight.value, shell.topMargin).dp
    Box(
        modifier.fillMaxWidth().height(shellBoxHeight),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .width(width)
                .heightIn(min = PALETTE_MIN_HEIGHT.dp, max = maxHeight)
                // After the size modifiers, before the card's own decoration —
                // the placement rule in [DialogScrim]. This is the *card* (the
                // shadowed, bordered box), not the full-width layout box above,
                // so everything the user sees dimmed around it is backdrop again
                // and a tap there closes the palette.
                .then(
                    if (scrim != null) Modifier.dialogCardBounds(scrim) else Modifier,
                )
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
                                activeId = flat.getOrNull(active)?.id
                                onHighlight(flat.getOrNull(active))
                                true
                            }

                            Key.DirectionUp -> {
                                active = moveIndex(active, -1, flat.size)
                                activeId = flat.getOrNull(active)?.id
                                onHighlight(flat.getOrNull(active))
                                true
                            }

                            Key.Enter, Key.NumPadEnter -> {
                                flat.getOrNull(active)?.let(handleSelect)
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
                    onValueChange = {
                        query = it
                        onQueryChange(it)
                    },
                    // Upstream `shell/commands/dialog.tsx:130` autofocuses the
                    // input; without it the palette opens keyboard-dead (arrows
                    // and Enter only bubble to the handler below from a focused
                    // child).
                    modifier = Modifier.focusRequester(searchFocus),
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
                // `.command-palette-v2-group` → `gap:1px`. The list's own
                // arrangement is uniform, so both gaps are applied per item
                // ([paletteSlotSpacings]) instead. `.command-palette-v2-scroll`
                // is `min-height:0; flex:1` inside the 280..480dp shell: this list
                // stays the one bounded scroller — touch drags and wheel events
                // scroll it (`scrollable` handles `PointerEventType.Scroll`) rather
                // than growing the dialog.
                val slotSpacings = remember(grouped) { paletteSlotSpacings(grouped) }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth()
                        .padding(PaddingValues(start = 6.dp, top = 6.dp, end = 6.dp, bottom = 8.dp)),
                ) {
                    var slot = 0
                    for ((category, entries) in grouped) {
                        val titleSlot = slot
                        slot += 1 + entries.size
                        item(key = "group:$category") {
                            GroupTitle(category, topSpacing = slotSpacings[titleSlot].dp)
                        }
                        itemsIndexed(entries, key = { _, entry -> entry.id }) { offset, entry ->
                            val index = flat.indexOfFirst { it.id == entry.id }
                            PaletteRow(
                                entry = entry,
                                active = index == active,
                                stacked = stacked,
                                topSpacing = slotSpacings[titleSlot + 1 + offset].dp,
                                onClick = { handleSelect(entry) },
                                onHover = {
                                    // WAVE 2.3a: hover-activates (`dialog.tsx`
                                    // mouse handlers) — keeps keyboard and
                                    // pointer selection in sync.
                                    val hovered = flat.indexOfFirst { it.id == entry.id }
                                    if (hovered >= 0 && hovered != active) {
                                        active = hovered
                                        activeId = entry.id
                                        onHighlight(entry)
                                    }
                                },
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
private fun SearchField(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    Box(
        modifier
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

/**
 * `.command-palette-v2-group-title` — 13px/440 muted, `margin: 6px 0`,
 * `padding: 0 12px`. [topSpacing] is the group's own gap (0 for the first one,
 * `gap:16px` between groups), kept outside the title's margin.
 */
@Composable
private fun GroupTitle(category: String, topSpacing: Dp) {
    Text(
        text = paletteGroupLabel(category),
        color = OcTheme.colors.textMuted,
        fontSize = 13.sp,
        fontWeight = FontWeight(440),
        lineHeight = 16.sp,
        modifier = Modifier
            .padding(top = topSpacing)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/**
 * Group labels come from the entry's `category` — and session rows carry
 * `command.category.session` ("Session"), NOT `palette.group.*` (§6.2). The
 * session registry's own groups ("View", "Model", …) resolve through the
 * `command.category.*` strings upstream registers them with; a category the
 * shell does not know keeps the raw literal.
 */
@Composable
private fun paletteGroupLabel(category: String): String =
    paletteGroupLabelResId(category)?.let { stringResource(it) } ?: category

/**
 * `.command-palette-v2-row` — `h-36`, `r-6`, `padding 0 12px`, active =
 * overlay-hover. [topSpacing] is the group's inner `gap:1px`, applied outside
 * the row so the active highlight still covers exactly 36dp.
 */
@Composable
private fun PaletteRow(
    entry: PaletteEntry,
    active: Boolean,
    stacked: Boolean,
    topSpacing: Dp,
    onClick: () -> Unit,
    onHover: () -> Unit = {},
) {
    val c = OcTheme.colors
    val density = LocalDensity.current
    // The row's own measured width, for the trailing hint's budget below.
    //
    // v77 P0: kept in px and converted with this composition's density, not as a
    // `Dp` frozen at the callback's creation — a density change that leaves the
    // row's px the same (a `wm density` override, a display-size setting) froze
    // the old configuration's width into the hint's budget. The same sweep
    // applies to the session pane's three mirrors (`SessionScreen`).
    var rowWidthPx by remember { mutableIntStateOf(0) }
    val rowWidth: Dp = with(density) { rowWidthPx.toDp() }
    Row(
        Modifier
            .padding(top = topSpacing)
            .fillMaxWidth()
            .onSizeChanged { rowWidthPx = it.width }
            .height(if (stacked) 48.dp else 36.dp)
            .background(if (active) c.overlayHover else Color.Transparent, RoundedCornerShape(6.dp))
            .clickable(
                // `onMouseDown` preventDefault + `onClick` select (§6.6).
                onClick = onClick,
            )
            .hoverable(onHover)
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
        // Trailing hint column (v43: it was being clipped by the card's right
        // edge). Both halves live in one bounded box that keeps
        // [PALETTE_ROW_EDGE_INSET] of clearance from that edge, so a long chord
        // ellipsises inside its own share instead of running under the card's
        // rounded corner — and the label column, which is weighted, keeps
        // [PALETTE_LABEL_MIN_WIDTH] of the row for itself.
        if (entry.keybind != null || entry.meta != null) {
            val hintMaxWidth = with(density) {
                paletteHintMaxWidth(rowWidthDp = rowWidth.value - 2f * PALETTE_ROW_PADDING).dp
            }
            Row(
                Modifier
                    .widthIn(max = hintMaxWidth)
                    .padding(end = PALETTE_ROW_EDGE_INSET.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                entry.keybind?.let { keybind ->
                    Text(
                        text = keybind,
                        color = c.textFaint,
                        fontSize = 11.sp,
                        fontWeight = FontWeight(530),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                entry.meta?.let { meta ->
                    Text(
                        text = meta,
                        color = c.textMuted,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
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
 *
 * Each row also carries its English copy ([PaletteEntry.searchTerms]) so the
 * search keeps working by the upstream name on a translated device.
 */
@Composable
fun stringResolvedEntries(commands: List<PaletteCommand>): List<PaletteEntry> {
    val english = englishStrings()
    val englishTerm: (Int) -> String? = remember(english) {
        { resId -> runCatching { english.getString(resId) }.getOrNull() }
    }
    val resolved = commands.map { command ->
        Triple(
            command,
            stringResource(command.titleResId),
            command.descriptionResId?.let { stringResource(it) },
        )
    }
    return resolved.filterNot { (command, _, _) -> command.hidden }
        .map { (command, title, description) ->
            command.toEntry().copy(
                title = title,
                description = ocPaletteRowDescription(title, description),
                searchTerms = commandEnglishTerms(command, englishTerm),
            )
        }
}

/**
 * The `values/` string table — the resource default, i.e. **English** (English
 * is the source copy; `values-zh-rCN` / `values-zh-rTW` hold the translations).
 * `stringResource` resolves the *UI* locale, so a locale-forced table is the
 * only way to read a command's upstream name back on a translated device — the
 * dimension that makes `debug` find 「切换调试栏」 ([matchesEntry]).
 */
@Composable
internal fun englishStrings(): Resources {
    val context = LocalContext.current
    return remember(context) {
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocales(LocaleList(Locale.ENGLISH))
        context.createConfigurationContext(configuration).resources
    }
}

/**
 * WAVE 2.3a (A4 #5): hover-activates the row (`dialog.tsx` mouse handlers).
 * A fresh interaction source per row keeps hover state local; firing only on
 * `false → true` avoids re-activating on every recomposition while hovered.
 */
@Composable
private fun Modifier.hoverable(onHover: () -> Unit): Modifier {
    if (onHover == {}) return this
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    LaunchedEffect(hovered) {
        if (hovered) onHover()
    }
    return this.hoverable(interactionSource = source, enabled = true)
}
