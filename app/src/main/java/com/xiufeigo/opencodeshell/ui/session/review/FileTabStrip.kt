package com.xiufeigo.opencodeshell.ui.session.review

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.session.drawer.MobileColors
import com.xiufeigo.opencodeshell.ui.session.drawer.isDarkSurface
import com.xiufeigo.opencodeshell.ui.theme.OcTheme

/**
 * Open-file tab strip, the Compose port of upstream `session-mobile-files.tsx:35-79`
 * (「All files」 + a `variant="line"` tab scroller) and `session/files/tab.tsx`
 * (`SortableTab`: icon + `getFilename` + close, `@dnd-kit` reorder).
 *
 * Geometry follows the mobile strip already in [MobileSessionTabs]: a 40 dp
 * row (`session-mobile-files.css` header `h-10`) whose active trigger is the
 * project's line-tab treatment (13/500 label in `text-base` over a 2 dp
 * accent underline). Tabs scroll horizontally (`Tabs.List overflow-x-auto`,
 * `:56`) and reorder by drag past half a neighbour's width — the dnd-kit
 * `onDragEnd` of `helpers.ts:174-179` `getTabReorderIndex` applied live.
 *
 * @param tabs open paths in drag order ([FileTabs]).
 * @param active the selected path, or null while browsing the tree.
 * @param browsing true while the file tree is showing (`browsing` store,
 *   `session-mobile-files.tsx:23-24`); the 「All files」 trigger is pressed.
 * @param onBrowse 「All files」 (`session.files.all`) — show the tree again.
 * @param onSelect activate a tab (`open(path)`).
 * @param onClose close a tab (`Tabs.CloseButton`, `:65-69`).
 * @param onReorder drag result `(from, to)`; the model applies [FileTabs.reorder].
 */
@Composable
fun FileTabStrip(
    tabs: List<String>,
    active: String?,
    browsing: Boolean,
    onBrowse: () -> Unit,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onReorder: (from: String, to: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    val stripLabel = stringResource(R.string.oc_file_view_open_tabs)
    val closeLabel = stringResource(R.string.oc_home_tab_close)
    val widths = remember { mutableStateMapOf<String, Float>() }
    var dragKey by remember { mutableStateOf<String?>(null) }
    var dragDx by remember { mutableFloatStateOf(0f) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .semantics { contentDescription = stripLabel },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // `session.files.all` → `setStore("browsing", true)` (`session-mobile-files.tsx:37-43`).
            Box(
                modifier = Modifier
                    .padding(start = 4.dp)
                    .height(28.dp)
                    .clip(OcTheme.shapes.sm)
                    .background(if (browsing) c.layer02 else Color.Transparent)
                    .clickable(role = Role.Tab, onClick = onBrowse)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.oc_file_view_all_files),
                    color = if (browsing) c.textBase else c.textMuted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.W500,
                    maxLines = 1,
                )
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEach { path ->
                    FileTabChip(
                        path = path,
                        selected = !browsing && path == active,
                        closeLabel = closeLabel,
                        onSelect = { onSelect(path) },
                        onClose = { onClose(path) },
                        modifier = Modifier
                            .onSizeChanged { widths[path] = it.width.toFloat() }
                            .pointerInput(path, tabs) {
                                detectDragGestures(
                                    onDragStart = {
                                        dragKey = path
                                        dragDx = 0f
                                    },
                                    onDragEnd = {
                                        dragKey = null
                                        dragDx = 0f
                                    },
                                    onDragCancel = {
                                        dragKey = null
                                        dragDx = 0f
                                    },
                                    onDrag = { _, amount ->
                                        if (dragKey != path) return@detectDragGestures
                                        dragDx += amount.x
                                        val step = (widths[path] ?: 0f) * 0.5f
                                        if (step <= 0f) return@detectDragGestures
                                        val index = tabs.indexOf(path)
                                        if (dragDx > step && index < tabs.lastIndex) {
                                            onReorder(path, tabs[index + 1])
                                            dragDx = 0f
                                        } else if (dragDx < -step && index > 0) {
                                            onReorder(path, tabs[index - 1])
                                            dragDx = 0f
                                        }
                                    },
                                )
                            },
                    )
                }
            }
        }
        // `before:h-px before:bg-v2-border-border-base` on the upstream strip.
        Box(
            Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(MobileColors.hairline(c.isDarkSurface())),
        )
    }
}

/**
 * One `SortableTab` (`session/files/tab.tsx:52-96`): 16 dp file icon, LTR
 * `getFilename` truncated at `max-w-48`, and the close `IconButton`.
 */
@Composable
private fun FileTabChip(
    path: String,
    selected: Boolean,
    closeLabel: String,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    Column(
        modifier = modifier
            .height(40.dp)
            .clip(OcTheme.shapes.sm)
            .clickable(role = Role.Tab, onClick = onSelect)
            .padding(horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_oc_file),
                contentDescription = null,
                tint = if (selected) c.iconBase else c.iconMuted,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = FileTabs.displayName(path),
                color = if (selected) c.textBase else c.textMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight.W500,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                softWrap = false,
                modifier = Modifier.width(88.dp),
            )
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(OcTheme.shapes.xs)
                    .clickable(role = Role.Button, onClick = onClose)
                    .semantics { contentDescription = closeLabel },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_oc_close),
                    contentDescription = null,
                    tint = c.iconFaint,
                    modifier = Modifier.size(10.dp),
                )
            }
        }
        Box(
            Modifier
                .padding(top = 6.dp)
                .width(24.dp)
                .height(2.dp)
                .background(if (selected) c.accent else Color.Transparent),
        )
    }
}
