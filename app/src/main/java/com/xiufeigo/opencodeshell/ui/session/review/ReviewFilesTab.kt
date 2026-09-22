package com.xiufeigo.opencodeshell.ui.session.review

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.OpenCodeClient
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.theme.OcMotion
import com.xiufeigo.opencodeshell.ui.theme.OcTheme

/**
 * 「文件」 tab of the review panel: the project file tree behind
 * `GET /file?path=`, lazily loaded one directory at a time (upstream
 * `flattenLiveFileTreeV2` + `file.tree.list`), directories sorting before
 * files (`ReviewFileTree.sortSiblings`). Tapping a file swaps the tree for a
 * content preview (`GET /file/content?path=`): mono text capped at
 * [MAX_PREVIEW_LINES] lines with a copy action, or a binary hint.
 *
 * Tree state lives in `remember` maps keyed by [OpenCodeClient.FileNode.absolute]
 * so a refresh of one directory never disturbs the rest of the tree.
 *
 * **W5-R additions:**
 * - the **Filter files** field of the upstream sidebar
 *   (`session-review-v2.tsx:88-123`, `ui.sessionReviewV2.filterFiles`) with the
 *   case-insensitive substring matcher of `review-diff-kinds.ts:57-61` plus the
 *   `matchesModelSearch` normalisation ([ReviewFileFilter]); ancestors of a
 *   match stay visible so the tree keeps its shape;
 * - **A/M/D change badges** (`file-tree-v2.tsx:114-122`) driven by the active
 *   scope's diffs — files only, never directories, exactly like upstream.
 *
 * @param openPath absolute path to open straight into the preview (`file.open`).
 * @param diffs changed files of the active scope, used for the row badges.
 *   Empty (the default) simply renders a tree without badges.
 */
@Composable
fun ReviewFilesTab(
    rootPath: String?,
    onLoadChildren: (String, (List<OpenCodeClient.FileNode>?) -> Unit) -> Unit,
    onLoadFile: (String, (OpenCodeClient.FileContent?) -> Unit) -> Unit,
    onResolveRoot: ((String?) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Absolute path to open straight into the preview, from the `file.open`
     * palette command (`palette.ts:66-80`: open the file, show the file tree
     * tab and select it). New value = new request; the tree behind the preview
     * keeps its state so Back returns to the tree.
     */
    openPath: String? = null,
    diffs: List<OpenCodeClient.FileDiff> = emptyList(),
) {
    val c = OcTheme.colors
    var resolvedRoot by remember { mutableStateOf<String?>(null) }
    var rootRequested by remember { mutableStateOf(false) }
    val children = remember { mutableStateMapOf<String, List<OpenCodeClient.FileNode>>() }
    // Presence-keyed sets (loading / failed / expanded directories).
    val loadingDirs = remember { mutableStateMapOf<String, Boolean>() }
    val failedDirs = remember { mutableStateMapOf<String, Boolean>() }
    val expandedDirs = remember { mutableStateMapOf<String, Boolean>() }
    var selected by remember { mutableStateOf<OpenCodeClient.FileNode?>(null) }
    var preview by remember { mutableStateOf<FilePreview>(FilePreview.Idle) }
    // The filter is transient upstream too (`panel-state.ts:28-30`: a persisted
    // filter would silently hide files after a reload), so a plain saveable.
    var filter by rememberSaveable { mutableStateOf("") }
    val badges = remember(diffs) { ReviewChangeBadges.badges(diffs) }

    fun load(dir: String) {
        if (children.containsKey(dir) || loadingDirs.containsKey(dir)) return
        loadingDirs[dir] = true
        failedDirs.remove(dir)
        onLoadChildren(dir) { result ->
            loadingDirs.remove(dir)
            if (result == null) failedDirs[dir] = true else children[dir] = result
        }
    }

    LaunchedEffect(rootPath) {
        if (!rootPath.isNullOrEmpty()) {
            resolvedRoot = rootPath
        } else if (!rootRequested) {
            // Deep-link entry without a known session directory: fall back to
            // the server cwd, exactly like the directory picker does.
            rootRequested = true
            onResolveRoot { resolvedRoot = it }
        }
    }
    LaunchedEffect(resolvedRoot) {
        resolvedRoot?.takeIf { it.isNotEmpty() }?.let(::load)
    }
    // `file.open <path>`: jump straight to that file's preview. The node is
    // synthesised (the tree may not have loaded the containing directory yet);
    // `FilePreviewView` only needs name/absolute.
    LaunchedEffect(openPath) {
        val path = openPath?.takeIf { it.isNotEmpty() } ?: return@LaunchedEffect
        selected = OpenCodeClient.FileNode(
            name = path.trimEnd('/').substringAfterLast('/').ifEmpty { path },
            path = path,
            absolute = path,
            directory = false,
            ignored = false,
        )
        preview = FilePreview.Loading
        onLoadFile(path) { result ->
            preview = if (result == null) FilePreview.Failed else FilePreview.Loaded(result)
        }
    }

    val root = resolvedRoot
    Column(modifier = modifier) {
        // The filter lives above the tree, like the upstream sidebar header
        // (`session-review-v2.tsx:88-123`); it has no meaning inside a preview.
        if (selected == null && !root.isNullOrEmpty()) {
            FileFilterField(
                value = filter,
                onValueChange = { filter = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                selected != null -> FilePreviewView(
                    node = selected!!,
                    preview = preview,
                    onBack = {
                        selected = null
                        preview = FilePreview.Idle
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                root.isNullOrEmpty() -> CenteredHint(
                    text = stringResource(R.string.oc_common_loading),
                    modifier = Modifier.fillMaxSize(),
                )

                else -> {
                    val allRows = remember(children.toMap(), expandedDirs.toMap(), root) {
                        ReviewFileTree.flatten(
                            children = { key -> children[key].orEmpty() },
                            root = root,
                            expanded = { expandedDirs.containsKey(it) },
                        )
                    }
                    val rows = remember(allRows, filter) { ReviewFileFilter.filterRows(allRows, filter) }
                    when {
                        failedDirs.containsKey(root) -> CenteredHint(
                            text = stringResource(R.string.oc_review_files_error),
                            modifier = Modifier.fillMaxSize(),
                            onRetry = { load(root) },
                        )

                        rows.isEmpty() && children.containsKey(root) && ReviewFileFilter.isActive(filter) ->
                            CenteredHint(
                                text = stringResource(R.string.oc_review_filter_empty),
                                modifier = Modifier.fillMaxSize(),
                            )

                        rows.isEmpty() && children.containsKey(root) -> CenteredHint(
                            text = stringResource(R.string.oc_review_files_empty),
                            modifier = Modifier.fillMaxSize(),
                        )

                        rows.isEmpty() -> CenteredHint(
                            text = stringResource(R.string.oc_common_loading),
                            modifier = Modifier.fillMaxSize(),
                        )

                        else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(
                                items = rows,
                                key = { row -> row.node.absolute.ifEmpty { "root:${rows.indexOf(row)}" } },
                            ) { row ->
                                FileTreeRow(
                                    row = row,
                                    expanded = expandedDirs.containsKey(row.node.absolute),
                                    badge = if (row.node.directory) {
                                        null
                                    } else {
                                        ReviewChangeBadges.forPath(row.node.absolute, badges, root)
                                    },
                                    onClick = {
                                        val node = row.node
                                        if (node.directory) {
                                            if (expandedDirs.containsKey(node.absolute)) {
                                                expandedDirs.remove(node.absolute)
                                            } else {
                                                expandedDirs[node.absolute] = true
                                                load(node.absolute)
                                            }
                                        } else {
                                            selected = node
                                            preview = FilePreview.Loading
                                            onLoadFile(node.absolute) { result ->
                                                preview = if (result == null) {
                                                    FilePreview.Failed
                                                } else {
                                                    FilePreview.Loaded(result)
                                                }
                                            }
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
}

/**
 * Sidebar search field, upstream `TextInput type="search"` with a leading
 * magnifier, the `filterFiles` placeholder and a clear button
 * (`session-review-v2.tsx:88-123`).
 */
@Composable
private fun FileFilterField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(30.dp)
            .clip(OcTheme.shapes.sm)
            .background(c.layer02.copy(alpha = 0.6f))
            .border(0.5.dp, c.borderBase, OcTheme.shapes.sm)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_oc_search),
            contentDescription = null,
            tint = c.iconMuted,
            modifier = Modifier.size(12.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(start = 6.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty()) {
                Text(
                    text = stringResource(R.string.oc_review_filter_files),
                    color = c.textFaint,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = c.textBase, fontSize = 12.sp),
                cursorBrush = SolidColor(c.textAccent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button) { onValueChange("") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_oc_close),
                    contentDescription = stringResource(R.string.oc_review_filter_clear),
                    tint = c.iconMuted,
                    modifier = Modifier.size(11.dp),
                )
            }
        }
    }
}

/** Preview fetch state for the tapped file. */
private sealed interface FilePreview {
    data object Idle : FilePreview
    data object Loading : FilePreview
    data class Loaded(val content: OpenCodeClient.FileContent) : FilePreview
    data object Failed : FilePreview
}

/**
 * One tree row (upstream `file-tree-v2-row`, 28 px): indent 8 + level×16,
 * chevron + folder for directories (chevron rotated −90° while collapsed),
 * file icon otherwise; `ignored` entries dimmed. Changed files carry the
 * upstream A/M/D badge (`file-tree-v2.tsx:114-122`).
 */
@Composable
private fun FileTreeRow(
    row: ReviewFileTree.Row,
    expanded: Boolean,
    badge: String?,
    onClick: () -> Unit,
) {
    val c = OcTheme.colors
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 0f else -90f,
        animationSpec = tween(OcMotion.SWAP_MS),
        label = "chevron",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(OcTheme.shapes.sm)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = (8 + row.level * 16).dp, end = 8.dp)
            .alpha(if (row.node.ignored) 0.5f else 1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (row.node.directory) {
            Icon(
                painter = painterResource(R.drawable.ic_oc_chevron_down),
                contentDescription = null,
                tint = c.iconMuted,
                modifier = Modifier
                    .size(16.dp)
                    .rotate(chevronRotation),
            )
        } else {
            Spacer(Modifier.width(16.dp))
        }
        Icon(
            painter = painterResource(
                if (row.node.directory) R.drawable.ic_oc_folder else R.drawable.ic_oc_file,
            ),
            contentDescription = null,
            tint = c.iconMuted,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = row.node.name,
            color = c.textBase,
            fontSize = 12.sp,
            fontWeight = FontWeight.W500,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (badge != null) {
            Spacer(Modifier.weight(1f))
            // `file-tree-v2-change`: the status letter in the change colour
            // (`kindChange` → added/deleted/modified).
            Text(
                text = badge,
                color = when (badge) {
                    "A" -> c.stateSuccessFg
                    "D" -> c.stateDangerFg
                    else -> c.stateInfoFg
                },
                fontSize = 10.sp,
                fontWeight = FontWeight(530),
                modifier = Modifier
                    .widthIn(min = 14.dp)
                    .clip(OcTheme.shapes.xs)
                    .background(c.layer02)
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}

/** Master-detail preview: back row + name + copy, then the capped mono body. */
@Composable
private fun FilePreviewView(
    node: OpenCodeClient.FileNode,
    preview: FilePreview,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    val clipboard = LocalClipboardManager.current
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .padding(start = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PanelIconButton(
                iconRes = R.drawable.ic_oc_chevron_down,
                contentDescription = stringResource(R.string.oc_back),
                iconModifier = Modifier.rotate(90f),
                onClick = onBack,
            )
            Text(
                text = node.name,
                color = c.textBase,
                fontSize = 13.sp,
                fontWeight = FontWeight.W500,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            val loaded = preview as? FilePreview.Loaded
            if (loaded != null && !loaded.content.binary && loaded.content.content.isNotEmpty()) {
                PanelIconButton(
                    iconRes = R.drawable.ic_oc_copy,
                    contentDescription = stringResource(R.string.oc_review_copy),
                    onClick = { clipboard.setText(AnnotatedString(loaded.content.content)) },
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.borderMuted))
        when (preview) {
            FilePreview.Idle, FilePreview.Loading -> CenteredHint(
                text = stringResource(R.string.oc_common_loading),
                modifier = Modifier.fillMaxSize(),
            )

            FilePreview.Failed -> CenteredHint(
                text = stringResource(R.string.oc_review_file_error),
                modifier = Modifier.fillMaxSize(),
            )

            is FilePreview.Loaded -> {
                if (preview.content.binary) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.oc_review_binary),
                            color = c.textMuted,
                            fontSize = 13.sp,
                        )
                        preview.content.mimeType?.let { mime ->
                            Text(
                                text = mime,
                                color = c.textFaint,
                                fontFamily = OcTheme.monoFont,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                } else {
                    FileTextPreview(text = preview.content.content)
                }
            }
        }
    }
}

/**
 * Text preview body: mono 11/16, both axes scrollable, capped at
 * [MAX_PREVIEW_LINES] lines ([MAX_LINE_CHARS] per line) so a huge file cannot
 * stall the panel — a truncated marker closes the listing, and the copy
 * action still carries the full content.
 */
@Composable
private fun FileTextPreview(text: String) {
    val c = OcTheme.colors
    val lines = remember(text) {
        val all = text.split('\n')
        PreviewLines(
            lines = all.take(MAX_PREVIEW_LINES).map { it.take(MAX_LINE_CHARS) },
            truncated = all.size > MAX_PREVIEW_LINES,
        )
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        lines.lines.forEach { line ->
            Text(
                text = line,
                color = c.textMuted,
                fontFamily = OcTheme.monoFont,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                softWrap = false,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
        if (lines.truncated) {
            Text(
                text = stringResource(R.string.oc_review_truncated),
                color = c.textFaint,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

private class PreviewLines(val lines: List<String>, val truncated: Boolean)

/** Empty/loading/failure hint centred in the tab, with an optional retry. */
@Composable
private fun CenteredHint(
    text: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    val c = OcTheme.colors
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Text(text = text, color = c.textFaint, fontSize = 13.sp)
        if (onRetry != null) {
            Text(
                text = stringResource(R.string.retry),
                color = c.textAccent,
                fontSize = 13.sp,
                fontWeight = FontWeight.W500,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .clip(OcTheme.shapes.sm)
                    .clickable(role = Role.Button, onClick = onRetry)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/** Preview body caps — see [FileTextPreview]. */
private const val MAX_PREVIEW_LINES = 400
private const val MAX_LINE_CHARS = 500
