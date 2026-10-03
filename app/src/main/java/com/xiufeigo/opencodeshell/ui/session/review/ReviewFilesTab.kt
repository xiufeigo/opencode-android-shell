package com.xiufeigo.opencodeshell.ui.session.review

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
 * 「文件」 tab of the review panel: the upstream v2 **file browser + open-file
 * tabs** pair — `SessionMobileFiles` (`session-mobile-files.tsx:14-100`, the
 * `mobileView === "files"` mount of `session/screen.tsx:310-312`) over
 * `SessionFileBrowserTab`'s tree, with each tapped file opening a sortable tab
 * whose body is the rich [ArtifactView] of `session/files/file-tabs.tsx`
 * (`SessionFileView`, `:180-482`).
 *
 * Tree data stays the lazy `GET /file?path=` walk of the old preview
 * (`flattenLiveFileTreeV2` + `file.tree.list`), directories sorting before
 * files ([ReviewFileTree.sortSiblings]). Opening a file loads
 * `GET /file/content?path=` (`V2Api.fsRead`) **once per tab** — the tab strip
 * switches between already-loaded bodies like upstream `file.get(path)`.
 *
 * **W5-R additions (kept):**
 * - the **Filter files** field of the upstream sidebar
 *   (`session-review-v2.tsx:88-123`, `ui.sessionReviewV2.filterFiles`) with the
 *   case-insensitive substring matcher of `review-diff-kinds.ts:57-61` plus the
 *   `matchesModelSearch` normalisation ([ReviewFileFilter]); ancestors of a
 *   match stay visible so the tree keeps its shape;
 * - **A/M/D change badges** (`file-tree-v2.tsx:114-122`) driven by the active
 *   scope's diffs — files only, never directories, exactly like upstream.
 *
 * @param openPath absolute path to open straight into a tab (`file.open`,
 *   `palette.ts:66-80`). New value = new request; the tree behind the tabs
 *   keeps its state so 「All files」 returns to it.
 * @param diffs changed files of the active scope, used for the row badges.
 *   Empty (the default) simply renders a tree without badges.
 * @param refreshKey bumped by the host when the user taps 「刷新」
 *   (`SessionReviewPanel.reprobeKey`, the same settle signal the diff walks
 *   use). A new value is a *new read request* for the root listing: the
 *   cached answer is evicted first, because [load] treats an already-listed
 *   directory as answered and would otherwise hand the previous listing —
 *   narrowed by whatever filter was last typed — back to the screen
 *   (`research/v97_verify/5562_v98/REPORT_SWEEPD2.md` §3.11 U11). A failed
 *   re-read lands in `failedDirs` and the body says so rather than
 *   re-rendering a stale subset. Default `0` keeps the mount-time read only.
 */
@Composable
fun ReviewFilesTab(
    rootPath: String?,
    onLoadChildren: (String, (List<OpenCodeClient.FileNode>?) -> Unit) -> Unit,
    onLoadFile: (String, (OpenCodeClient.FileContent?) -> Unit) -> Unit,
    onResolveRoot: ((String?) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    openPath: String? = null,
    diffs: List<OpenCodeClient.FileDiff> = emptyList(),
    refreshKey: Int = 0,
) {
    val c = OcTheme.colors
    val mountRefreshKey = remember { refreshKey }
    var resolvedRoot by remember { mutableStateOf<String?>(null) }
    var rootRequested by remember { mutableStateOf(false) }
    val children = remember { mutableStateMapOf<String, List<OpenCodeClient.FileNode>>() }
    // Presence-keyed sets (loading / failed / expanded directories).
    val loadingDirs = remember { mutableStateMapOf<String, Boolean>() }
    val failedDirs = remember { mutableStateMapOf<String, Boolean>() }
    val expandedDirs = remember { mutableStateMapOf<String, Boolean>() }
    // `session-mobile-files.tsx:23-24` `browsing` + the open-file tab list
    // (`layout.tabs()` upstream). Tree state above is `remember`-scoped too, so
    // the tab list follows it rather than a saveable join-encode.
    var browsing by rememberSaveable { mutableStateOf(true) }
    var openedTabs by remember { mutableStateOf(emptyList<String>()) }
    var activeTab by remember { mutableStateOf<String?>(null) }
    val contents = remember { mutableStateMapOf<String, FilePreview>() }
    // The filter is transient upstream too (`panel-state.ts:28-30`: a persisted
    // filter would silently hide files after a reload), so a plain saveable.
    var filter by rememberSaveable { mutableStateOf("") }
    val badges = remember(diffs) { ReviewChangeBadges.badges(diffs) }
    val openInApp = rememberOpenInAppActions()

    fun commitTabs(tabs: List<String>, active: String?) {
        openedTabs = tabs
        activeTab = active
    }

    fun load(dir: String) {
        if (children.containsKey(dir) || loadingDirs.containsKey(dir)) return
        loadingDirs[dir] = true
        failedDirs.remove(dir)
        onLoadChildren(dir) { result ->
            loadingDirs.remove(dir)
            if (result == null) failedDirs[dir] = true else children[dir] = result
        }
    }

    /** `session-mobile-files.tsx:27-31` `open(path)`: tab + `file.load` + leave browsing. */
    fun openFile(path: String) {
        commitTabs(FileTabs.open(openedTabs, path), path)
        browsing = false
        if (contents[path] == null || contents[path] is FilePreview.Failed) {
            contents[path] = FilePreview.Loading
            onLoadFile(path) { result ->
                contents[path] =
                    if (result == null) FilePreview.Failed else FilePreview.Loaded(result)
            }
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
    LaunchedEffect(resolvedRoot, refreshKey) {
        val dir = resolvedRoot?.takeIf { it.isNotEmpty() } ?: return@LaunchedEffect
        // The mount-time read is the plain one. A later `refreshKey` is a user
        // 「刷新」, and `load` treats a listed directory as already answered — so
        // the eviction has to come first, or the tap hands the previous listing
        // back and the screen keeps whatever the last filter narrowed it to.
        if (ReviewTreeRead.mustEvictBeforeReload(refreshKey, mountRefreshKey)) {
            children.remove(dir)
        }
        load(dir)
    }
    // `file.open <path>`: open that file as a tab (`createOpenReviewFile`,
    // `helpers.ts:135-151`). The node is synthesised — the tree may not have
    // loaded the containing directory yet; the viewer only needs the path.
    LaunchedEffect(openPath) {
        val path = openPath?.takeIf { it.isNotEmpty() } ?: return@LaunchedEffect
        openFile(path)
    }

    val root = resolvedRoot
    Column(modifier = modifier) {
        // The strip is the `session-mobile-files-header` (`:35-79`): 「All
        // files」 + the sortable open-file tabs. Always mounted, like upstream.
        FileTabStrip(
            tabs = openedTabs,
            active = activeTab,
            browsing = browsing || activeTab == null,
            onBrowse = { browsing = true },
            onSelect = { path ->
                commitTabs(openedTabs, path)
                browsing = false
            },
            onClose = { path ->
                val index = openedTabs.indexOf(path)
                val remaining = FileTabs.close(openedTabs, path)
                val next = FileTabs.activeAfterClose(activeTab, path, index, remaining)
                commitTabs(remaining, next)
                contents.remove(path)
                if (next == null) browsing = true
            },
            onReorder = { from, to ->
                commitTabs(FileTabs.reorder(openedTabs, from, to), activeTab)
            },
        )
        // The filter lives above the tree, like the upstream sidebar header
        // (`session-review-v2.tsx:88-123`); it has no meaning inside a viewer.
        if ((browsing || activeTab == null) && !root.isNullOrEmpty()) {
            FileFilterField(
                value = filter,
                onValueChange = { filter = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        Box(modifier = Modifier.fillMaxSize()) {
            val active = activeTab
            when {
                !browsing && active != null -> {
                    when (val preview = contents[active]) {
                        null, FilePreview.Loading -> CenteredHint(
                            text = stringResource(R.string.oc_common_loading),
                            modifier = Modifier.fillMaxSize(),
                        )

                        FilePreview.Failed -> CenteredHint(
                            text = stringResource(R.string.oc_review_file_error),
                            modifier = Modifier.fillMaxSize(),
                            onRetry = { openFile(active) },
                        )

                        is FilePreview.Loaded -> ArtifactView(
                            path = active,
                            content = preview.content,
                            onOpenInApp = { path -> openInApp.openInApp(path, preview.content) },
                            onCopyPath = openInApp.copyPath,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                root.isNullOrEmpty() -> CenteredHint(
                    text = stringResource(R.string.oc_common_loading),
                    modifier = Modifier.fillMaxSize(),
                )

                else -> {
                    // v97 fix: while a filter is active, search over every
                    // *loaded* row, not only the expanded ones. The browse
                    // flatten below is gated by `expandedDirs`, so a file in a
                    // collapsed directory was unreachable by any query
                    // (searching `gradle` missed `build.gradle.kts` until the
                    // folder was opened by hand). The search flatten descends
                    // into every directory already fetched — no new request, and
                    // `expandedDirs` itself is never written, so clearing the
                    // filter still restores the tree the user left.
                    val allRows = remember(children.toMap(), expandedDirs.toMap(), root) {
                        ReviewFileTree.flatten(
                            children = { key -> children[key].orEmpty() },
                            root = root,
                            expanded = { expandedDirs.containsKey(it) },
                        )
                    }
                    val filtering = ReviewFileFilter.isActive(filter)
                    // U11 (`REPORT_SWEEPD2.md` §3.11 / §4.3): the row input was
                    // never the bug — `filter = ""` did restore every row, and
                    // the dump proves it (the 「Clear filter」 button disappears
                    // and the field goes back to its placeholder). What the
                    // tester was left looking at is this list: `items(key = …)`
                    // makes `LazyListState` pin the *previous* first item by key
                    // when the item set changes, so the viewport stayed on the
                    // match (`.gradle`, index 3 of 15) with the first three
                    // rows scrolled off the top. With the IME up the viewport is
                    // ~2 rows tall, so 「恢复全量」 read as 「只剩 2 行」 — stable
                    // and byte-identical across rounds, which is exactly what a
                    // deterministic scroll offset looks like. Returning to the
                    // top on the active → inactive edge is the upstream
                    // `filterFiles` behaviour and makes the clear observable.
                    val listState = rememberLazyListState()
                    LaunchedEffect(filtering) {
                        if (!filtering) listState.scrollToItem(0)
                    }
                    val rows = if (filtering) {
                        ReviewFileFilter.filterRows(
                            ReviewFileFilter.searchRows(
                                children = { key -> children[key].orEmpty() },
                                root = root,
                                isExpanded = { expandedDirs.containsKey(it) },
                                hasLoadedChildren = { children.containsKey(it) },
                                query = filter,
                            ),
                            filter,
                        )
                    } else {
                        allRows
                    }
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

                        else -> LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                        ) {
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
                                            openFile(node.absolute)
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
            // Hoisted: `semantics {}` is not a composable scope. The label rides
            // the clickable Box, not the `Icon` inside it.
            val clearLabel = stringResource(R.string.oc_review_filter_clear)
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button) { onValueChange("") }
                    .semantics { contentDescription = clearLabel },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_oc_close),
                    contentDescription = null,
                    tint = c.iconMuted,
                    modifier = Modifier.size(11.dp),
                )
            }
        }
    }
}

/** Per-tab fetch state of `GET /file/content?path=` (`file.get(path)` upstream). */
private sealed interface FilePreview {
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
            .padding(start = (8 + row.level * 16).dp, end = FilesRowEndPadding)
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

/**
 * Trailing gutter of a file-tree row.
 *
 * The row's right edge is the **screen's** right edge (the review sidebar is
 * the last column and its own header only reserves 4 dp), so the 8 dp the rows
 * used left the `A`/`M`/`D` badge sitting a few pixels off the bezel — the
 * rows read as clipped where the rows above them (which start 12 dp in) read
 * as padded (`research/v63_verify/5554/39_files.png`). The badge is the last
 * thing in the row, so its gutter is the row's gutter.
 */
internal val FilesRowEndPadding = 12.dp
